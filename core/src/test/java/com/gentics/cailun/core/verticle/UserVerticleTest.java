package com.gentics.cailun.core.verticle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import io.vertx.core.http.HttpMethod;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.codehaus.jackson.JsonGenerationException;
import org.junit.Test;
import org.neo4j.graphdb.Transaction;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gentics.cailun.core.AbstractRestVerticle;
import com.gentics.cailun.core.data.model.auth.PermissionType;
import com.gentics.cailun.core.data.model.auth.User;
import com.gentics.cailun.core.rest.user.request.UserCreateRequest;
import com.gentics.cailun.core.rest.user.request.UserUpdateRequest;
import com.gentics.cailun.core.rest.user.response.UserListResponse;
import com.gentics.cailun.core.rest.user.response.UserResponse;
import com.gentics.cailun.test.AbstractRestVerticleTest;
import com.gentics.cailun.util.JsonUtils;

public class UserVerticleTest extends AbstractRestVerticleTest {

	@Override
	public AbstractRestVerticle getVerticle() {
		return userVerticle;
	}

	// Read Tests

	@Test
	public void testReadTagByUUID() throws Exception {
		User user = info.getUser();
		assertNotNull("The UUID of the user must not be null.", user.getUuid());

		roleService.addPermission(info.getRole(), user, PermissionType.READ);

		String response = request(info, HttpMethod.GET, "/api/v1/users/" + user.getUuid(), 200, "OK");
		UserResponse restUser = JsonUtils.readValue(response, UserResponse.class);

		test.assertUser(user, restUser);
		// TODO assert groups
		// TODO assert perms
	}

	@Test
	public void testReadByUUIDWithNoPermission() throws Exception {
		User user = info.getUser();
		assertNotNull("The username of the user must not be null.", user.getUsername());

		try (Transaction tx = graphDb.beginTx()) {
			roleService.addPermission(info.getRole(), user, PermissionType.DELETE);
			roleService.addPermission(info.getRole(), user, PermissionType.CREATE);
			roleService.addPermission(info.getRole(), user, PermissionType.UPDATE);
			roleService.revokePermission(info.getRole(), user, PermissionType.READ);
			tx.success();
		}

		String response = request(info, HttpMethod.GET, "/api/v1/users/" + user.getUuid(), 403, "Forbidden");
		expectMessageResponse("error_missing_perm", response, user.getUuid());
	}

	@Test
	public void testReadAllUsers() throws Exception {
		User user = info.getUser();
		roleService.addPermission(info.getRole(), user, PermissionType.READ);

		final int nUsers = 48;
		try (Transaction tx = graphDb.beginTx()) {
			for (int i = 0; i < nUsers; i++) {
				User extraUser = new User("extra_user_" + i);
				extraUser.setLastname("A" + i);
				extraUser.setFirstname("A" + i);
				extraUser.setEmailAddress("test" + i);
				extraUser = userService.save(extraUser);
				extraUser.getGroups().add(info.getGroup());
				// info.getGroup().addUser(extraUser);
				roleService.addPermission(info.getRole(), extraUser, PermissionType.READ);
			}
			tx.success();
		}
		User user3 = new User("testuser_3");
		try (Transaction tx = graphDb.beginTx()) {
			user3.setLastname("should_not_be_listed");
			user3.setFirstname("should_not_be_listed");
			user3.setEmailAddress("should_not_be_listed");
			user3 = userService.save(user3);
			info.getGroup().addUser(user3);
			groupService.save(info.getGroup());
			tx.success();
		}

		// Don't grant permissions to user3

		// Test default paging parameters
		String response = request(info, HttpMethod.GET, "/api/v1/users/", 200, "OK");
		UserListResponse restResponse = JsonUtils.readValue(response, UserListResponse.class);
		assertEquals(25, restResponse.getMetainfo().getPerPage());
		assertEquals(0, restResponse.getMetainfo().getCurrentPage());
		assertEquals(25, restResponse.getData().size());

		int perPage = 11;
		response = request(info, HttpMethod.GET, "/api/v1/users/?per_page=" + perPage + "&page=" + 3, 200, "OK");
		restResponse = JsonUtils.readValue(response, UserListResponse.class);
		assertEquals("The page did not contain the expected amount of items", perPage, restResponse.getData().size());

		// Extrausers + user for login
		int totalUsers = nUsers + data().getTotalUsers();
		int totalPages = (int) Math.ceil(totalUsers / (double) perPage);
		assertEquals("The response did not contain the correct amount of items", perPage, restResponse.getData().size());
		assertEquals(3, restResponse.getMetainfo().getCurrentPage());
		assertEquals(totalPages, restResponse.getMetainfo().getPageCount());
		assertEquals(perPage, restResponse.getMetainfo().getPerPage());
		assertEquals(totalUsers, restResponse.getMetainfo().getTotalCount());

		List<UserResponse> allUsers = new ArrayList<>();
		for (int page = 0; page < totalPages; page++) {
			response = request(info, HttpMethod.GET, "/api/v1/users/?per_page=" + perPage + "&page=" + page, 200, "OK");
			restResponse = JsonUtils.readValue(response, UserListResponse.class);
			allUsers.addAll(restResponse.getData());
		}
		assertEquals("Somehow not all users were loaded when loading all pages.", totalUsers, allUsers.size());

		// Verify that user3 is not part of the response
		final String extra3Username = user3.getUsername();
		List<UserResponse> filteredUserList = allUsers.parallelStream().filter(restUser -> restUser.getUsername().equals(extra3Username))
				.collect(Collectors.toList());
		assertTrue("User 3 should not be part of the list since no permissions were added.", filteredUserList.size() == 0);

		response = request(info, HttpMethod.GET, "/api/v1/users/?per_page=" + perPage + "&page=" + -1, 400, "Bad Request");
		expectMessageResponse("error_invalid_paging_parameters", response);
		response = request(info, HttpMethod.GET, "/api/v1/users/?per_page=" + 0 + "&page=" + 1, 400, "Bad Request");
		expectMessageResponse("error_invalid_paging_parameters", response);
		response = request(info, HttpMethod.GET, "/api/v1/users/?per_page=" + -1 + "&page=" + 1, 400, "Bad Request");
		expectMessageResponse("error_invalid_paging_parameters", response);

		response = request(info, HttpMethod.GET, "/api/v1/users/?per_page=" + 25 + "&page=" + 4242, 200, "OK");
		String json = "{\"data\":[],\"_metainfo\":{\"page\":4242,\"per_page\":25,\"page_count\":6,\"total_count\":143}}";
		assertEqualsSanitizedJson("The json did not match the expected one.", json, response);

	}

	// Update tests

	@Test
	public void testUpdateUser() throws Exception {
		User user = info.getUser();
		roleService.addPermission(info.getRole(), user, PermissionType.UPDATE);

		UserUpdateRequest updateRequest = new UserUpdateRequest();
		updateRequest.setUuid(user.getUuid());
		updateRequest.setEmailAddress("t.stark@stark-industries.com");
		updateRequest.setFirstname("Tony Awesome");
		updateRequest.setLastname("Epic Stark");
		updateRequest.setUsername("dummy_user_changed");

		String response = request(info, HttpMethod.PUT, "/api/v1/users/" + user.getUuid(), 200, "OK", JsonUtils.toJson(updateRequest));
		UserResponse restUser = JsonUtils.readValue(response, UserResponse.class);
		test.assertUser(updateRequest, restUser);

		User reloadedUser = userService.findByUUID(user.getUuid());
		assertEquals("Epic Stark", reloadedUser.getLastname());
		assertEquals("Tony Awesome", reloadedUser.getFirstname());
		assertEquals("t.stark@stark-industries.com", reloadedUser.getEmailAddress());
		assertEquals("dummy_user_changed", reloadedUser.getUsername());
	}

	@Test
	public void testUpdatePassword() throws JsonGenerationException, JsonMappingException, IOException, Exception {
		User user = info.getUser();
		String oldHash = user.getPasswordHash();
		roleService.addPermission(info.getRole(), user, PermissionType.UPDATE);

		UserUpdateRequest updateRequest = new UserUpdateRequest();
		updateRequest.setPassword("new_password");

		String response = request(info, HttpMethod.PUT, "/api/v1/users/" + user.getUuid(), 200, "OK",
				new ObjectMapper().writeValueAsString(updateRequest));
		UserResponse restUser = JsonUtils.readValue(response, UserResponse.class);
		test.assertUser(updateRequest, restUser);

		User reloadedUser = userService.findByUUID(user.getUuid());
		assertTrue("The hash should be different and thus the password updated.", oldHash != reloadedUser.getPasswordHash());
		assertEquals(user.getUsername(), reloadedUser.getUsername());
		assertEquals(user.getFirstname(), reloadedUser.getFirstname());
		assertEquals(user.getLastname(), reloadedUser.getLastname());
		assertEquals(user.getEmailAddress(), reloadedUser.getEmailAddress());
	}

	@Test
	public void testUpdatePasswordWithNoPermission() throws JsonGenerationException, JsonMappingException, IOException, Exception {
		User user = info.getUser();
		String oldHash = user.getPasswordHash();

		roleService.addPermission(info.getRole(), user, PermissionType.READ);
		roleService.revokePermission(info.getRole(), user, PermissionType.UPDATE);

		UserUpdateRequest restUser = new UserUpdateRequest();
		restUser.setPassword("new_password");

		String response = request(info, HttpMethod.PUT, "/api/v1/users/" + user.getUuid(), 403, "Forbidden",
				new ObjectMapper().writeValueAsString(restUser));
		expectMessageResponse("error_missing_perm", response, user.getUuid());

		User reloadedUser = userService.findByUUID(user.getUuid());
		assertTrue("The hash should not be updated.", oldHash.equals(reloadedUser.getPasswordHash()));
	}

	@Test
	public void testUpdateUserWithNoPermission() throws Exception {
		User user = info.getUser();
		String oldHash = user.getPasswordHash();
		try (Transaction tx = graphDb.beginTx()) {
			roleService.addPermission(info.getRole(), user, PermissionType.READ);
			roleService.revokePermission(info.getRole(), user, PermissionType.UPDATE);
			tx.success();
		}

		UserResponse updatedUser = new UserResponse();
		updatedUser.setEmailAddress("n.user@spam.gentics.com");
		updatedUser.setFirstname("Joe");
		updatedUser.setLastname("Doe");
		updatedUser.setUsername("new_user");
		updatedUser.addGroup(info.getGroup().getName());

		String response = request(info, HttpMethod.PUT, "/api/v1/users/" + user.getUuid(), 403, "Forbidden",
				new ObjectMapper().writeValueAsString(updatedUser));
		expectMessageResponse("error_missing_perm", response, user.getUuid());

		User reloadedUser = userService.findByUUID(user.getUuid());
		assertTrue("The hash should not be updated.", oldHash.equals(reloadedUser.getPasswordHash()));
		assertEquals("The firstname should not be updated.", user.getFirstname(), reloadedUser.getFirstname());
		assertEquals("The firstname should not be updated.", user.getLastname(), reloadedUser.getLastname());
	}

	@Test
	public void testUpdateUserWithConflictingUsername() throws Exception {
		User user = info.getUser();

		roleService.addPermission(info.getRole(), user, PermissionType.UPDATE);

		// Create an user with a conflicting username
		User conflictingUser = new User("existing_username");
		try (Transaction tx = graphDb.beginTx()) {
			conflictingUser = userService.save(conflictingUser);
			info.getGroup().addUser(conflictingUser);
			tx.success();
		}

		UserUpdateRequest newUser = new UserUpdateRequest();
		newUser.setUsername("existing_username");
		newUser.setUuid(user.getUuid());

		String requestJson = new ObjectMapper().writeValueAsString(newUser);
		String response = request(info, HttpMethod.PUT, "/api/v1/users/" + user.getUuid(), 409, "Conflict", requestJson);
		expectMessageResponse("user_conflicting_username", response);

	}

	// Create tests

	@Test
	public void testCreateUserWithConflictingUsername() throws Exception {

		// Create an user with a conflicting username
		User conflictingUser = new User("existing_username");
		try (Transaction tx = graphDb.beginTx()) {
			conflictingUser = userService.save(conflictingUser);
			info.getGroup().addUser(conflictingUser);
			// Add update permission to group in order to create the user in that group
			roleService.addPermission(info.getRole(), info.getGroup(), PermissionType.CREATE);
			tx.success();
		}

		UserCreateRequest newUser = new UserCreateRequest();
		newUser.setUsername("existing_username");
		newUser.setGroupUuid(info.getGroup().getUuid());
		newUser.setPassword("test1234");

		String requestJson = new ObjectMapper().writeValueAsString(newUser);
		String response = request(info, HttpMethod.POST, "/api/v1/users/", 409, "Conflict", requestJson);
		expectMessageResponse("user_conflicting_username", response);

	}

	@Test
	public void testCreateUserWithNoPassword() throws Exception {
		// Add update permission to group in order to create the user in that group
		roleService.addPermission(info.getRole(), info.getGroup(), PermissionType.UPDATE);

		UserCreateRequest newUser = new UserCreateRequest();
		newUser.setEmailAddress("n.user@spam.gentics.com");
		newUser.setFirstname("Joe");
		newUser.setLastname("Doe");
		newUser.setUsername("new_user_test123");
		newUser.setGroupUuid(info.getGroup().getUuid());

		String requestJson = new ObjectMapper().writeValueAsString(newUser);
		String response = request(info, HttpMethod.POST, "/api/v1/users/", 400, "Bad Request", requestJson);
		expectMessageResponse("user_missing_password", response);
	}

	@Test
	public void testCreateUserWithNoUsername() throws Exception {
		// Add update permission to group in order to create the user in that group
		roleService.addPermission(info.getRole(), info.getGroup(), PermissionType.UPDATE);

		UserCreateRequest newUser = new UserCreateRequest();
		newUser.setEmailAddress("n.user@spam.gentics.com");
		newUser.setFirstname("Joe");
		newUser.setLastname("Doe");
		newUser.setPassword("test123456");

		String requestJson = new ObjectMapper().writeValueAsString(newUser);
		String response = request(info, HttpMethod.POST, "/api/v1/users/", 400, "Bad Request", requestJson);
		expectMessageResponse("user_missing_username", response);
	}

	@Test
	public void testCreateUserWithNoParentGroup() throws Exception {
		// Add update permission to group in order to create the user in that group
		roleService.addPermission(info.getRole(), info.getGroup(), PermissionType.UPDATE);

		UserCreateRequest newUser = new UserCreateRequest();
		newUser.setEmailAddress("n.user@spam.gentics.com");
		newUser.setFirstname("Joe");
		newUser.setLastname("Doe");
		newUser.setUsername("new_user");
		newUser.setPassword("test123456");

		String requestJson = new ObjectMapper().writeValueAsString(newUser);
		String response = request(info, HttpMethod.POST, "/api/v1/users/", 400, "Bad Request", requestJson);
		expectMessageResponse("user_missing_parentgroup_field", response);

	}

	@Test
	public void testCreateUserWithBogusParentGroup() throws Exception {
		// Add update permission to group in order to create the user in that group
		roleService.addPermission(info.getRole(), info.getGroup(), PermissionType.UPDATE);

		UserCreateRequest newUser = new UserCreateRequest();
		newUser.setEmailAddress("n.user@spam.gentics.com");
		newUser.setFirstname("Joe");
		newUser.setLastname("Doe");
		newUser.setUsername("new_user");
		newUser.setPassword("test123456");
		newUser.setGroupUuid("bogus");

		String requestJson = new ObjectMapper().writeValueAsString(newUser);
		String response = request(info, HttpMethod.POST, "/api/v1/users/", 404, "Not Found", requestJson);
		expectMessageResponse("object_not_found_for_uuid", response, "bogus");

	}

	@Test
	public void testCreateUser() throws Exception {

		// Add update permission to group in order to create the user in that group
		roleService.addPermission(info.getRole(), info.getGroup(), PermissionType.UPDATE);

		UserCreateRequest newUser = new UserCreateRequest();
		newUser.setEmailAddress("n.user@spam.gentics.com");
		newUser.setFirstname("Joe");
		newUser.setLastname("Doe");
		newUser.setUsername("new_user");
		newUser.setPassword("test123456");
		newUser.setGroupUuid(info.getGroup().getUuid());

		String requestJson = new ObjectMapper().writeValueAsString(newUser);
		String response = request(info, HttpMethod.POST, "/api/v1/users/", 200, "OK", requestJson);
		UserResponse restUser = JsonUtils.readValue(response, UserResponse.class);
		test.assertUser(newUser, restUser);

		User user = userService.findByUUID(restUser.getUuid());
		test.assertUser(user, restUser);

	}

	/**
	 * Test whether the create rest call will create the correct permissions that allow removal of the object.
	 * 
	 * @throws Exception
	 */
	@Test
	public void testCreateDeleteUser() throws Exception {

		// Add create permission to group in order to create the user in that group
		roleService.addPermission(info.getRole(), info.getGroup(), PermissionType.CREATE);

		UserCreateRequest newUser = new UserCreateRequest();
		newUser.setEmailAddress("n.user@spam.gentics.com");
		newUser.setFirstname("Joe");
		newUser.setLastname("Doe");
		newUser.setUsername("new_user");
		newUser.setPassword("test123456");
		newUser.setGroupUuid(info.getGroup().getUuid());

		String requestJson = new ObjectMapper().writeValueAsString(newUser);
		String response = request(info, HttpMethod.POST, "/api/v1/users/", 200, "OK", requestJson);
		UserResponse restUser = JsonUtils.readValue(response, UserResponse.class);
		test.assertUser(newUser, restUser);

		response = request(info, HttpMethod.DELETE, "/api/v1/users/" + restUser.getUuid(), 200, "OK");
		expectMessageResponse("user_deleted", response, restUser.getUuid());

	}

	@Test
	public void testCreateUserWithBogusJson() throws Exception {

		String requestJson = "bogus text";
		String response = request(info, HttpMethod.POST, "/api/v1/users/", 400, "Bad Request", requestJson);

		expectMessageResponse("error_parse_request_json_error", response);
	}

	// Delete tests

	@Test
	public void testDeleteUserByUUID() throws Exception {
		User user = info.getUser();

		roleService.addPermission(info.getRole(), user, PermissionType.DELETE);

		String response = request(info, HttpMethod.DELETE, "/api/v1/users/" + user.getUuid(), 200, "OK");
		expectMessageResponse("user_deleted", response, user.getUuid());
		assertNull("The user should have been deleted", userService.findByUUID(user.getUuid()));
	}

	@Test
	public void testDeleteByUUIDWithNoPermission() throws Exception {
		User user = new User("extraUser");
		try (Transaction tx = graphDb.beginTx()) {
			user = userService.save(user);
			roleService.addPermission(info.getRole(), user, PermissionType.UPDATE);
			roleService.addPermission(info.getRole(), user, PermissionType.CREATE);
			roleService.addPermission(info.getRole(), user, PermissionType.READ);
			tx.success();
		}
		user = userService.reload(user);
		assertNotNull(user.getUuid());

		String response = request(info, HttpMethod.DELETE, "/api/v1/users/" + user.getUuid(), 403, "Forbidden");
		expectMessageResponse("error_missing_perm", response, user.getUuid());
		assertNotNull("The user should not have been deleted", userService.findByUUID(user.getUuid()));
	}

	@Test
	public void testDeleteWithUuidNull() throws Exception {
		String response = request(info, HttpMethod.DELETE, "/api/v1/users/" + null, 404, "Not Found");
		expectMessageResponse("object_not_found_for_uuid", response, "null");
	}

	@Test
	public void testDeleteByUUID() throws Exception {
		User user = new User("extraUser");
		try (Transaction tx = graphDb.beginTx()) {
			user = userService.save(user);
			roleService.addPermission(info.getRole(), user, PermissionType.DELETE);
			tx.success();
		}
		user = userService.reload(user);

		String response = request(info, HttpMethod.DELETE, "/api/v1/users/" + user.getUuid(), 200, "OK");
		expectMessageResponse("user_deleted", response, user.getUuid());
		assertNull("The user should have been deleted", userService.reload(user));
	}

	@Test
	public void testDeleteOwnUser() {

		// String response = request(info, HttpMethod.DELETE, "/api/v1/users/" + user.getUuid(), 403, "Forbidden");
	}
}
