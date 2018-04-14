package nl.taallijn.has;

import io.vertx.core.AbstractVerticle;

public class MainVerticle extends AbstractVerticle {

  @Override
  public void start() {
    vertx.createHttpServer()
        .requestHandler(req -> req.response().end("Coucou Halink Appreciation Society!"))
        .listen(8080);
  }

}
