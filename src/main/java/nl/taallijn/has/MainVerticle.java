package nl.taallijn.has;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.rjeschke.txtmark.Processor;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.asyncsql.PostgreSQLClient;
// HSQLDB
import io.vertx.ext.jdbc.JDBCClient;
// PostgreSQL
//import io.vertx.ext.sql.SQLClient;
import io.vertx.ext.sql.SQLConnection;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.templ.FreeMarkerTemplateEngine;

public class MainVerticle extends AbstractVerticle {

	// HSQLDB
	private static final String SQL_CREATE_PAGES_TABLE = "create table if not exists Pages (Id integer identity primary key, Name varchar(255) unique, Content clob)";
	private static final String SQL_GET_PAGE = "select Id, Content from Pages where Name = ?";
	private static final String SQL_CREATE_PAGE = "insert into Pages values (NULL, ?, ?)";
	private static final String SQL_SAVE_PAGE = "update Pages set Content = ? where Id = ?";
	private static final String SQL_ALL_PAGES = "select Name from Pages";
	private static final String SQL_DELETE_PAGE = "delete from Pages where Id = ?";
	private JDBCClient dbClient;
	
	// PostgreSQL
//	private static final String SQL_CREATE_PAGES_TABLE = "CREATE TABLE IF NOT EXISTS Pages (Id bigint, Name character varying(255), Content character varying(1023)); ALTER TABLE Pages OWNER TO has;";
//	private static final String SQL_GET_PAGE = "select Id, Content from Pages where Name = ?";
//	private static final String SQL_CREATE_PAGE = "insert into Pages values (NULL, ?, ?)";
//	private static final String SQL_SAVE_PAGE = "update Pages set Content = ? where Id = ?";
//	private static final String SQL_ALL_PAGES = "select Name from Pages";
//	private static final String SQL_DELETE_PAGE = "delete from Pages where Id = ?";
//	private SQLClient dbClient;

	private static final Logger LOGGER = LoggerFactory.getLogger(MainVerticle.class);

	private Future<Void> prepareDatabase() {
		Future<Void> future = Future.future();

		// HSQLDB
		JsonObject dbClientConfig = new JsonObject().put("url", "jdbc:hsqldb:file:db/wiki")
				.put("driver_class", "org.hsqldb.jdbcDriver").put("max_pool_size", 30);
		dbClient = JDBCClient.createShared(vertx, dbClientConfig);
		
		// PostgreSQL
//		JsonObject dbClientConfig = new JsonObject().put("host", "127.0.0.1").put("username", "has")
//				.put("password", "has").put("database", "has");
//		dbClient = PostgreSQLClient.createShared(vertx, dbClientConfig);

		dbClient.getConnection(ar -> {
			if (ar.failed()) {
				LOGGER.error("Could not open a database connection", ar.cause());
				future.fail(ar.cause());
			} else {
				SQLConnection connection = ar.result();
				connection.execute(SQL_CREATE_PAGES_TABLE, create -> {
					connection.close();
					if (create.failed()) {
						LOGGER.error("Database preparation error", create.cause());
						future.fail(create.cause());
					} else {
						future.complete();
					}
				});
			}
		});


		return future;
	}

	private Future<Void> startHttpServer() {
		Future<Void> future = Future.future();
		HttpServer server = vertx.createHttpServer();

		Router router = Router.router(vertx);
		router.get("/").handler(this::indexHandler);
		router.get("/wiki/:page").handler(this::pageRenderingHandler);
		router.post().handler(BodyHandler.create());
		router.post("/save").handler(this::pageUpdateHandler);
		router.post("/create").handler(this::pageCreateHandler);
		router.post("/delete").handler(this::pageDeletionHandler);

		server.requestHandler(router::accept).listen(8080, ar -> {
			if (ar.succeeded()) {
				LOGGER.info("HTTP server running on port 8080");
				future.complete();
			} else {
				LOGGER.error("Could not start a HTTP server", ar.cause());
				future.fail(ar.cause());
			}
		});

		return future;
	}

	private void pageDeletionHandler(RoutingContext context) {
		String id = context.request().getParam("id");
		dbClient.getConnection(car -> {
			if (car.succeeded()) {
				SQLConnection connection = car.result();
				connection.updateWithParams(SQL_DELETE_PAGE, new JsonArray().add(id), res -> {
					connection.close();
					if (res.succeeded()) {
						context.response().setStatusCode(303);
						context.response().putHeader("Location", "/");
						context.response().end();
					} else {
						context.fail(res.cause());
					}
				});
			} else {
				context.fail(car.cause());
			}
		});
	}

	private void pageCreateHandler(RoutingContext context) {
		String pageName = context.request().getParam("name");
		String location = "/wiki/" + pageName;
		if (pageName == null || pageName.isEmpty()) {
			location = "/";
		}
		context.response().setStatusCode(303);
		context.response().putHeader("Location", location);
		context.response().end();
	}

	private final FreeMarkerTemplateEngine templateEngine = FreeMarkerTemplateEngine.create();

	private void indexHandler(RoutingContext context) {
		dbClient.getConnection(car -> {
			if (car.succeeded()) {
				SQLConnection connection = car.result();
				connection.query(SQL_ALL_PAGES, res -> {
					connection.close();

					if (res.succeeded()) {
						List<String> pages = res.result().getResults().stream().map(json -> json.getString(0)).sorted()
								.collect(Collectors.toList());

						context.put("title", "Wiki home");
						context.put("pages", pages);
						templateEngine.render(context, "templates", "/index.ftl", ar -> {
							if (ar.succeeded()) {
								context.response().putHeader("Content-Type", "text/html");
								context.response().end(ar.result());
							} else {
								context.fail(ar.cause());
							}
						});

					} else {
						context.fail(res.cause());
					}
				});
			} else {
				context.fail(car.cause());
			}
		});
	}

	private void pageUpdateHandler(RoutingContext context) {
		String id = context.request().getParam("id");
		String title = context.request().getParam("title");
		String markdown = context.request().getParam("markdown");
		boolean newPage = "yes".equals(context.request().getParam("newPage"));

		dbClient.getConnection(car -> {
			if (car.succeeded()) {
				SQLConnection connection = car.result();
				String sql = newPage ? SQL_CREATE_PAGE : SQL_SAVE_PAGE;
				JsonArray params = new JsonArray();
				if (newPage) {
					params.add(title).add(markdown);
				} else {
					params.add(markdown).add(id);
				}
				connection.updateWithParams(sql, params, res -> {
					connection.close();
					if (res.succeeded()) {
						context.response().setStatusCode(303);
						context.response().putHeader("Location", "/wiki/" + title);
						context.response().end();
					} else {
						context.fail(res.cause());
					}
				});
			} else {
				context.fail(car.cause());
			}
		});
	}

	private static final String EMPTY_PAGE_MARKDOWN = "# A new page\n" + "\n" + "Feel-free to write in Markdown!\n";

	private void pageRenderingHandler(RoutingContext context) {
		String page = context.request().getParam("page");

		dbClient.getConnection(car -> {
			if (car.succeeded()) {

				SQLConnection connection = car.result();
				connection.queryWithParams(SQL_GET_PAGE, new JsonArray().add(page), fetch -> {
					connection.close();
					if (fetch.succeeded()) {

						JsonArray row = fetch.result().getResults().stream().findFirst()
								.orElseGet(() -> new JsonArray().add(-1).add(EMPTY_PAGE_MARKDOWN));
						Integer id = row.getInteger(0);
						String rawContent = row.getString(1);

						context.put("title", page);
						context.put("id", id);
						context.put("newPage", fetch.result().getResults().size() == 0 ? "yes" : "no");
						context.put("rawContent", rawContent);
						context.put("content", Processor.process(rawContent));
						context.put("timestamp", new Date().toString());

						templateEngine.render(context, "templates", "/page.ftl", ar -> {
							if (ar.succeeded()) {
								context.response().putHeader("Content-Type", "text/html");
								context.response().end(ar.result());
							} else {
								context.fail(ar.cause());
							}
						});
					} else {
						context.fail(fetch.cause());
					}
				});

			} else {
				context.fail(car.cause());
			}
		});
	}

	@Override
	public void start(Future<Void> startFuture) throws Exception {
		Future<Void> steps = prepareDatabase().compose(v -> startHttpServer());
		steps.setHandler(startFuture.completer());
	}

	public void anotherStart(Future<Void> startFuture) throws Exception {
		Future<Void> steps = prepareDatabase().compose(v -> startHttpServer());
		steps.setHandler(ar -> {
			if (ar.succeeded()) {
				startFuture.complete();
			} else {
				startFuture.fail(ar.cause());
			}
		});
	}

	public void originalStart() {
		vertx.createHttpServer().requestHandler(req -> req.response().end("Coucou Halink Appreciation Society!"))
				.listen(8080);
	}

}
