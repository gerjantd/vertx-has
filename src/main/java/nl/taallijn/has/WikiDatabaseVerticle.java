package nl.taallijn.has;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.jdbc.JDBCClient;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.SQLConnection;

public class WikiDatabaseVerticle extends AbstractVerticle {

	// TODO Do we need this?
	public static final String CONFIG_WIKIDB_RESOURCE_FILE = "wikidb.resource.file";

	public static final String CONFIG_WIKIDB_QUEUE = "wikidb.queue";

	private static final Logger LOGGER = LoggerFactory.getLogger(WikiDatabaseVerticle.class);

	private enum SqlQuery {
		CREATE_PAGES_TABLE, ALL_PAGES, GET_PAGE, CREATE_PAGE, SAVE_PAGE, DELETE_PAGE
	}

	private final HashMap<SqlQuery, String> sqlQueries = new HashMap<>();
	
	// TODO shouldn't this be static?
	private final JsonObject jdbcParameters = new JsonObject();

	private void loadDbProperties() throws IOException {

		String resourceFile = config().getString(CONFIG_WIKIDB_RESOURCE_FILE);
		InputStream resourceInputStream;
		if (resourceFile != null) {
			resourceInputStream = new FileInputStream(resourceFile);
		} else {
			resourceInputStream = getClass().getResourceAsStream("/db.properties");
		}

		Properties dbProps = new Properties();
		dbProps.load(resourceInputStream);
		resourceInputStream.close();
		
		jdbcParameters.put("url", dbProps.getProperty("wikidb.jdbc.url"));
		jdbcParameters.put("driver_class", dbProps.getProperty("wikidb.jdbc.driver_class"));
		// TODO Can we avoid the cast to Integer?
		jdbcParameters.put("max_pool_size", Integer.parseInt(dbProps.getProperty("wikidb.jdbc.max_pool_size")));
		jdbcParameters.put("user", dbProps.getProperty("wikidb.jdbc.user"));
		jdbcParameters.put("password", dbProps.getProperty("wikidb.jdbc.password"));

		sqlQueries.put(SqlQuery.CREATE_PAGES_TABLE, dbProps.getProperty("wikidb.sql.create_pages_table"));
		sqlQueries.put(SqlQuery.ALL_PAGES, dbProps.getProperty("wikidb.sql.all_pages"));
		sqlQueries.put(SqlQuery.GET_PAGE, dbProps.getProperty("wikidb.sql.get_page"));
		sqlQueries.put(SqlQuery.CREATE_PAGE, dbProps.getProperty("wikidb.sql.create_page"));
		sqlQueries.put(SqlQuery.SAVE_PAGE, dbProps.getProperty("wikidb.sql.save_page"));
		sqlQueries.put(SqlQuery.DELETE_PAGE, dbProps.getProperty("wikidb.sql.delete_page"));
	}

	private JDBCClient dbClient;

	@Override
	public void start(Future<Void> startFuture) throws Exception {

		// Note: this uses blocking APIs, but data is small
		loadDbProperties();
		
		dbClient = JDBCClient.createShared(vertx, jdbcParameters);

		dbClient.getConnection(ar -> {
			if (ar.failed()) {
				LOGGER.error("Could not open a database connection", ar.cause());
				startFuture.fail(ar.cause());
			} else {
				SQLConnection connection = ar.result();
				connection.execute(sqlQueries.get(SqlQuery.CREATE_PAGES_TABLE), create -> {
					connection.close();
					if (create.failed()) {
						LOGGER.error("Database preparation error", create.cause());
						startFuture.fail(create.cause());
					} else {
						vertx.eventBus().consumer(config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue"),
								this::onMessage);
						startFuture.complete();
					}
				});
			}
		});
	}

	public enum ErrorCodes {
		NO_ACTION_SPECIFIED, BAD_ACTION, DB_ERROR
	}

	public void onMessage(Message<JsonObject> message) {

		if (!message.headers().contains("action")) {
			LOGGER.error("No action header specified for message with headers {} and body {}", message.headers(),
					message.body().encodePrettily());
			message.fail(ErrorCodes.NO_ACTION_SPECIFIED.ordinal(), "No action header specified");
			return;
		}
		String action = message.headers().get("action");

		switch (action) {
		case "all-pages":
			fetchAllPages(message);
			break;
		case "get-page":
			fetchPage(message);
			break;
		case "create-page":
			createPage(message);
			break;
		case "save-page":
			savePage(message);
			break;
		case "delete-page":
			deletePage(message);
			break;
		default:
			message.fail(ErrorCodes.BAD_ACTION.ordinal(), "Bad action: " + action);
		}
	}

	private void fetchAllPages(Message<JsonObject> message) {
		dbClient.query(sqlQueries.get(SqlQuery.ALL_PAGES), res -> {
			if (res.succeeded()) {
				List<String> pages = res.result().getResults().stream().map(json -> json.getString(0)).sorted()
						.collect(Collectors.toList());
				message.reply(new JsonObject().put("pages", new JsonArray(pages)));
			} else {
				reportQueryError(message, res.cause());
			}
		});
	}

	private void fetchPage(Message<JsonObject> message) {
		String requestedPage = message.body().getString("page");
		JsonArray params = new JsonArray().add(requestedPage);

		dbClient.queryWithParams(sqlQueries.get(SqlQuery.GET_PAGE), params, fetch -> {
			if (fetch.succeeded()) {
				JsonObject response = new JsonObject();
				ResultSet resultSet = fetch.result();
				if (resultSet.getNumRows() == 0) {
					response.put("found", false);
				} else {
					response.put("found", true);
					JsonArray row = resultSet.getResults().get(0);
					response.put("id", row.getInteger(0));
					response.put("rawContent", row.getString(1));
				}
				message.reply(response);
			} else {
				reportQueryError(message, fetch.cause());
			}
		});
	}

	private void createPage(Message<JsonObject> message) {
		JsonObject request = message.body();
		JsonArray data = new JsonArray().add(request.getString("title")).add(request.getString("markdown"));

		dbClient.updateWithParams(sqlQueries.get(SqlQuery.CREATE_PAGE), data, res -> {
			if (res.succeeded()) {
				message.reply("ok");
			} else {
				reportQueryError(message, res.cause());
			}
		});
	}

	private void savePage(Message<JsonObject> message) {
		JsonObject request = message.body();
		JsonArray data = new JsonArray().add(request.getString("markdown")).add(request.getString("id"));
		LOGGER.debug("savePage: data = {}", data);

		dbClient.updateWithParams(sqlQueries.get(SqlQuery.SAVE_PAGE), data, res -> {
			if (res.succeeded()) {
				message.reply("ok");
			} else {
				reportQueryError(message, res.cause());
			}
		});
	}

	private void deletePage(Message<JsonObject> message) {
		JsonArray data = new JsonArray().add(message.body().getString("id"));

		dbClient.updateWithParams(sqlQueries.get(SqlQuery.DELETE_PAGE), data, res -> {
			if (res.succeeded()) {
				message.reply("ok");
			} else {
				reportQueryError(message, res.cause());
			}
		});
	}

	private void reportQueryError(Message<JsonObject> message, Throwable cause) {
		LOGGER.error("Database query error", cause);
		message.fail(ErrorCodes.DB_ERROR.ordinal(), cause.getMessage());
	}

}
