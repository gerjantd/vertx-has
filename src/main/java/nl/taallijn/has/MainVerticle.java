package nl.taallijn.has;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import nl.taallijn.has.database.WikiDatabaseVerticle;

public class MainVerticle extends AbstractVerticle {

	private static final Logger LOGGER = LoggerFactory.getLogger(MainVerticle.class);

	private static final String JDBC_PARAMETERS_RESOURCE_FILE = "/db-jdbc-parameters.properties";

	@Override
	public void start(Future<Void> startFuture) throws Exception {
		JsonObject dbConfig = loadDbConfig();
		LOGGER.debug("DB config about to be passed to WikiDatabaseVerticle = {}", dbConfig.encodePrettily());
		Future<String> dbVerticleDeployment = Future.future();
		vertx.deployVerticle(new WikiDatabaseVerticle(), new DeploymentOptions().setConfig(dbConfig),
				dbVerticleDeployment.completer());
		dbVerticleDeployment.compose(id -> {
			Future<String> httpVerticleDeployment = Future.future();
			vertx.deployVerticle("nl.taallijn.has.http.HttpServerVerticle", new DeploymentOptions().setInstances(2),
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

	/*
	 * Note: this uses blocking APIs, but data is small...
	 */
	private Properties loadResourceFile(String filename) throws FileNotFoundException, IOException {
		InputStream resourceInputStream = getClass().getResourceAsStream(filename);
		Properties props = new Properties();
		props.load(resourceInputStream);
		resourceInputStream.close();
		return props;
	}

	private JsonObject loadDbConfig() throws IOException {
		Properties dbProps = loadResourceFile(JDBC_PARAMETERS_RESOURCE_FILE);
		JsonObject dbConfig = new JsonObject();
		dbConfig.put("wikidb.jdbc.url", dbProps.getProperty("wikidb.jdbc.url"));
		dbConfig.put("wikidb.jdbc.driver_class", dbProps.getProperty("wikidb.jdbc.driver_class"));
		// TODO Can we avoid the cast to Integer?
		dbConfig.put("wikidb.jdbc.max_pool_size", Integer.parseInt(dbProps.getProperty("wikidb.jdbc.max_pool_size")));
		dbConfig.put("wikidb.jdbc.user", dbProps.getProperty("wikidb.jdbc.user"));
		dbConfig.put("wikidb.jdbc.password", dbProps.getProperty("wikidb.jdbc.password"));
		return dbConfig;
	}

}
