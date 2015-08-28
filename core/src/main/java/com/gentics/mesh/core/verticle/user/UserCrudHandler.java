package com.gentics.mesh.core.verticle.user;

import static com.gentics.mesh.core.data.relationship.GraphPermission.READ_PERM;
import static com.gentics.mesh.util.VerticleHelper.createObject;
import static com.gentics.mesh.util.VerticleHelper.deleteObject;
import static com.gentics.mesh.util.VerticleHelper.loadObject;
import static com.gentics.mesh.util.VerticleHelper.loadTransformAndResponde;
import static com.gentics.mesh.util.VerticleHelper.updateObject;

import org.springframework.stereotype.Component;

import com.gentics.mesh.core.rest.user.UserListResponse;
import com.gentics.mesh.core.verticle.handler.AbstractCrudHandler;
import com.gentics.mesh.graphdb.Trx;

import io.vertx.ext.web.RoutingContext;

@Component
public class UserCrudHandler extends AbstractCrudHandler {

	@Override
	public void handleDelete(RoutingContext rc) {
		try (Trx tx = db.trx()) {
			deleteObject(rc, "uuid", "user_deleted", boot.userRoot());
		}
	}

	@Override
	public void handleCreate(RoutingContext rc) {
		try (Trx tx = db.trx()) {
			createObject(rc, boot.userRoot());
		}
	}

	@Override
	public void handleUpdate(RoutingContext rc) {
		try (Trx tx = db.trx()) {
			updateObject(rc, "uuid", boot.userRoot());
		}
	}

	@Override
	public void handleRead(RoutingContext rc) {
		try (Trx tx = db.trx()) {
			loadObject(rc, "uuid", READ_PERM, boot.userRoot(), rh -> {
				loadTransformAndResponde(rc, "uuid", READ_PERM, boot.userRoot());
			});
		}
	}

	@Override
	public void handleReadList(RoutingContext rc) {
		try (Trx tx = db.trx()) {
			loadTransformAndResponde(rc, boot.userRoot(), new UserListResponse());
		}
	}

}