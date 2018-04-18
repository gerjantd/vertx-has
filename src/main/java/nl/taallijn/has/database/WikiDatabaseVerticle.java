package nl.taallijn.has.database;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Properties;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.serviceproxy.ServiceBinder;

public class WikiDatabaseVerticle extends AbstractVerticle {

	public static final String CONFIG_WIKIDB_JDBC_PARAMETERS_RESOURCE_FILE = "wikidb.jdbc.parameters.resource.file";
	public static final String CONFIG_WIKIDB_SQL_QUERIES_RESOURCE_FILE = "wikidb.sql.queries.resource.file";
	public static final String CONFIG_WIKIDB_QUEUE = "wikidb.queue";

	/*
	 * Note: this uses blocking APIs, but data is small...
	 */
	private Properties loadResourceFile(String configName, String defaultFilename)
			throws FileNotFoundException, IOException {
		String resourceFile = config().getString(configName);
		InputStream resourceInputStream;
		if (resourceFile != null) {
			resourceInputStream = new FileInputStream(resourceFile);
		} else {
			resourceInputStream = getClass().getResourceAsStream(defaultFilename);
		}

		Properties dbProps = new Properties();
		dbProps.load(resourceInputStream);
		resourceInputStream.close();
		return dbProps;
	}

	private JsonObject loadJdbcParameters() throws IOException {

		Properties dbProps = loadResourceFile(CONFIG_WIKIDB_JDBC_PARAMETERS_RESOURCE_FILE,
				"/db-jdbc-parameters.properties");

		JsonObject jdbcParameters = new JsonObject();
		jdbcParameters.put("url", dbProps.getProperty("wikidb.jdbc.url"));
		jdbcParameters.put("driver_class", dbProps.getProperty("wikidb.jdbc.driver_class"));
		// TODO Can we avoid the cast to Integer?
		jdbcParameters.put("max_pool_size", Integer.parseInt(dbProps.getProperty("wikidb.jdbc.max_pool_size")));
		jdbcParameters.put("user", dbProps.getProperty("wikidb.jdbc.user"));
		jdbcParameters.put("password", dbProps.getProperty("wikidb.jdbc.password"));
		return jdbcParameters;
	}

	private HashMap<SqlQuery, String> loadSqlQueries() throws IOException {

		Properties dbProps = loadResourceFile(CONFIG_WIKIDB_SQL_QUERIES_RESOURCE_FILE, "/db-sql-queries.properties");

		HashMap<SqlQuery, String> sqlQueries = new HashMap<>();
		sqlQueries.put(SqlQuery.CREATE_PAGES_TABLE, dbProps.getProperty("wikidb.sql.create_pages_table"));
		sqlQueries.put(SqlQuery.ALL_PAGES, dbProps.getProperty("wikidb.sql.all_pages"));
		sqlQueries.put(SqlQuery.GET_PAGE, dbProps.getProperty("wikidb.sql.get_page"));
		sqlQueries.put(SqlQuery.CREATE_PAGE, dbProps.getProperty("wikidb.sql.create_page"));
		sqlQueries.put(SqlQuery.SAVE_PAGE, dbProps.getProperty("wikidb.sql.save_page"));
		sqlQueries.put(SqlQuery.DELETE_PAGE, dbProps.getProperty("wikidb.sql.delete_page"));
		return sqlQueries;
	}

	@Override
	public void start(Future<Void> startFuture) throws Exception {

		HashMap<SqlQuery, String> sqlQueries = loadSqlQueries();
		JsonObject jdbcParameters = loadJdbcParameters();

		JDBCClient dbClient = JDBCClient.createShared(vertx, jdbcParameters);

		WikiDatabaseService.create(dbClient, sqlQueries, ready -> {
			if (ready.succeeded()) {
				ServiceBinder binder = new ServiceBinder(vertx);
				binder.setAddress(CONFIG_WIKIDB_QUEUE).register(WikiDatabaseService.class, ready.result());
				startFuture.complete();
			} else {
				startFuture.fail(ready.cause());
			}
		});
	}

}
