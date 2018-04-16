package nl.taallijn.has;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import nl.taallijn.has.database.WikiDatabaseVerticle;

public class MainVerticle extends AbstractVerticle {

	@Override
	public void start(Future<Void> startFuture) throws Exception {

		Future<String> dbVerticleDeployment = Future.future();
		vertx.deployVerticle(new WikiDatabaseVerticle(), dbVerticleDeployment.completer());

		dbVerticleDeployment.compose(id -> {

			Future<String> httpVerticleDeployment = Future.future();
			vertx.deployVerticle("nl.taallijn.has.HttpServerVerticle", new DeploymentOptions().setInstances(2),
					httpVerticleDeployment.completer());

			return httpVerticleDeployment;

		}).setHandler(ar -> {
			if (ar.succeeded()) {
				startFuture.complete();
			} else {
				startFuture.fail(ar.cause());
			}
		});
	}

}
