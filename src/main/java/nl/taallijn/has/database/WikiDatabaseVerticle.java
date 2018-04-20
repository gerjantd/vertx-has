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

	public static final String CONFIG_WIKIDB_JDBC_URL = "wikidb.jdbc.url";
	public static final String CONFIG_WIKIDB_JDBC_DRIVER_CLASS = "wikidb.jdbc.driver_class";
	public static final String CONFIG_WIKIDB_JDBC_MAX_POOL_SIZE = "wikidb.jdbc.max_pool_size";
	public static final String CONFIG_WIKIDB_JDBC_USER = "wikidb.jdbc.user";
	public static final String CONFIG_WIKIDB_JDBC_PASSWORD = "wikidb.jdbc.password";
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
		Properties props = new Properties();
		props.load(resourceInputStream);
		resourceInputStream.close();
		return props;
	}

	private HashMap<SqlQuery, String> loadSqlQueries() throws IOException {
		Properties props = loadResourceFile(CONFIG_WIKIDB_SQL_QUERIES_RESOURCE_FILE, "/db-sql-queries.properties");
		HashMap<SqlQuery, String> sqlQueries = new HashMap<>();
		sqlQueries.put(SqlQuery.CREATE_PAGES_TABLE, props.getProperty("wikidb.sql.create_pages_table"));
		sqlQueries.put(SqlQuery.ALL_PAGES, props.getProperty("wikidb.sql.all_pages"));
		sqlQueries.put(SqlQuery.GET_PAGE, props.getProperty("wikidb.sql.get_page"));
		sqlQueries.put(SqlQuery.CREATE_PAGE, props.getProperty("wikidb.sql.create_page"));
		sqlQueries.put(SqlQuery.SAVE_PAGE, props.getProperty("wikidb.sql.save_page"));
		sqlQueries.put(SqlQuery.DELETE_PAGE, props.getProperty("wikidb.sql.delete_page"));
		return sqlQueries;
	}

	@Override
	public void start(Future<Void> startFuture) throws Exception {
		HashMap<SqlQuery, String> sqlQueries = loadSqlQueries();
		JDBCClient dbClient = JDBCClient.createShared(vertx, new JsonObject()
				.put("url", config().getString(CONFIG_WIKIDB_JDBC_URL, "jdbc:hsqldb:file:db/wiki"))
				.put("driver_class", config().getString(CONFIG_WIKIDB_JDBC_DRIVER_CLASS, "org.hsqldb.jdbcDriver"))
				.put("max_pool_size", config().getInteger(CONFIG_WIKIDB_JDBC_MAX_POOL_SIZE, 30))
				.put("user", config().getString(CONFIG_WIKIDB_JDBC_USER, "sa"))
				.put("password", config().getString(CONFIG_WIKIDB_JDBC_PASSWORD, "")));
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
