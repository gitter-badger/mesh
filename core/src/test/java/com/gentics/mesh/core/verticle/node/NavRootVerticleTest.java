package com.gentics.mesh.core.verticle.node;

import static com.gentics.mesh.assertj.MeshAssertions.assertThat;
import static com.gentics.mesh.demo.TestDataProvider.PROJECT_NAME;
import static com.gentics.mesh.util.MeshAssert.assertSuccess;
import static com.gentics.mesh.util.MeshAssert.latchFor;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.gentics.mesh.core.AbstractSpringVerticle;
import com.gentics.mesh.core.rest.navigation.NavigationResponse;
import com.gentics.mesh.core.verticle.navroot.NavRootVerticle;
import com.gentics.mesh.query.impl.NavigationRequestParameter;
import com.gentics.mesh.query.impl.NodeRequestParameter;
import com.gentics.mesh.test.AbstractRestVerticleTest;

import io.vertx.core.Future;

public class NavRootVerticleTest extends AbstractRestVerticleTest {

	@Autowired
	private NavRootVerticle navRootVerticle;

	@Autowired
	private NodeVerticle nodeVerticle;

	@Override
	public List<AbstractSpringVerticle> getAdditionalVertices() {
		List<AbstractSpringVerticle> list = new ArrayList<>();
		list.add(navRootVerticle);
		list.add(nodeVerticle);
		return list;
	}

	/**
	 * Test reading a navigation concurrently.
	 */
	@Test
	public void testReadMultithreaded() {
		int nJobs = 200;
		String path = "/";

		List<Future<NavigationResponse>> futures = new ArrayList<>();
		for (int i = 0; i < nJobs; i++) {
			futures.add(getClient().navroot(PROJECT_NAME, path, new NodeRequestParameter().setLanguages("en", "de")));
		}

		for (Future<NavigationResponse> fut : futures) {
			latchFor(fut);
			assertSuccess(fut);
			assertThat(fut.result()).isValid(7).hasDepth(3);
		}
	}

	/**
	 * Test reading a navigation using a valid path.
	 */
	@Test
	public void testReadNavWithValidPath() {
		String path = "/News/2015";
		Future<NavigationResponse> future = getClient().navroot(PROJECT_NAME, path, new NavigationRequestParameter().setMaxDepth(10));
		latchFor(future);
		assertSuccess(future);
		assertThat(future.result()).hasDepth(0).isValid(1);
	}

	/**
	 * Test reading a navigation using a valid path.
	 */
	@Test
	public void testReadNavWithValidPath2() {
		String path = "/News/2015/";
		Future<NavigationResponse> future = getClient().navroot(PROJECT_NAME, path, new NavigationRequestParameter().setMaxDepth(10));
		latchFor(future);
		assertSuccess(future);
		assertThat(future.result()).isValid(1).hasDepth(0);
	}

	/**
	 * Test reading a navigation using the project basenode as root element.
	 */
	@Test
	public void testReadNavForBasenode() {
		String path = "/";
		Future<NavigationResponse> future = getClient().navroot(PROJECT_NAME, path, new NavigationRequestParameter().setMaxDepth(10));
		latchFor(future);
		assertSuccess(future);
		assertThat(future.result()).isValid(7).hasDepth(3);
		assertEquals("The root element of the navigation did not contain the project basenode uuid.", project().getBaseNode().getUuid(),
				future.result().getRoot().getUuid());
	}

	/**
	 * Test reading a navigation using a bogus path.
	 */
	@Test
	public void testReadNavWithInvalidPath() {
		String path = "/blub";
		Future<NavigationResponse> future = getClient().navroot(PROJECT_NAME, path);
		latchFor(future);
		expectException(future, NOT_FOUND, "node_not_found_for_path", "/blub");
	}

	/**
	 * Test reading a navigation by using a path that points to a content instead of a container.
	 */
	@Test
	public void testReadNavWithPathToContent() {
		String path = "/News/2015/News_2015.en.html";
		Future<NavigationResponse> future = getClient().navroot(PROJECT_NAME, path, new NodeRequestParameter().setLanguages("en", "de"));
		latchFor(future);
		expectException(future, BAD_REQUEST, "navigation_error_no_container");
	}
}
