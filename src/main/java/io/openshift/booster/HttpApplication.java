package io.openshift.booster;

import static io.vertx.core.http.HttpHeaders.CONTENT_TYPE;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.reactivex.config.ConfigRetriever;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.Future;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.ext.web.client.WebClient;
import io.vertx.reactivex.ext.web.client.predicate.ResponsePredicate;
import io.vertx.reactivex.ext.web.codec.BodyCodec;
import io.vertx.reactivex.ext.web.handler.StaticHandler;

/**
 *
 */
public class HttpApplication extends AbstractVerticle {

    private static final Logger LOGGER = LogManager.getLogger(HttpApplication.class);

    private static final String MESSAGE_SERVICE_API = "/api/messages/1";
    private static final String EMPTY_GREETINGS = "Empty Greetings (Not Available)";
    
    private ConfigRetriever configRetriever;
    private JsonObject config;

    private String messageTemplate;
    private String receiverName;
    private String messageServiceHost;
    private int messageServicePort;
    private WebClient messageClient;

    @Override
    public void start() {

        // Router
        Router router = Router.router(vertx);
        router.get("/api/hello").handler(this::helloHandler);
        router.get(MESSAGE_SERVICE_API)
                .handler(rc -> rc.response().putHeader(CONTENT_TYPE, "application/json; charset=utf-8")
                        .end(new JsonObject().put("id", "1").put("value", "Greetings from Inside").encodePrettily()));
        router.get("/health").handler(rc -> rc.response().end("OK"));
        router.get("/*").handler(StaticHandler.create());

        // Configuration File
        configRetriever = ConfigRetriever.create(vertx);
        Future<JsonObject> configFuture = ConfigRetriever.getConfigAsFuture(configRetriever);
        configFuture.setHandler(ar -> {
            // Once retrieved, store it and start the HTTP server.
            config = ar.result();
            updateConfiguration();

            vertx.createHttpServer().requestHandler(router).listen(
                    // Retrieve the port from the configuration,
                    // default to 8080.
                    config().getInteger("http.port", 8080));

        });

        // It should use the retrieve.listen method, however it does not catch the
        // deletion of the config map.
        // https://github.com/vert-x3/vertx-config/issues/7
        vertx.setPeriodic(2000, l -> {
            configFuture.setHandler(ar -> {
                if (ar.succeeded()) {
                    if (config == null || !config.encode().equals(ar.result().encode())) {
                        config = ar.result();
                        updateConfiguration();
                    }
                } else {
                    messageTemplate = null;
                    messageServiceHost = null;
                    messageServicePort = -1;
                    messageClient = null;
                }
            });
        });
    }

    private void helloHandler(RoutingContext rc) {
        if (messageTemplate == null) {
            rc.response().setStatusCode(500).putHeader(CONTENT_TYPE, "application/json; charset=utf-8")
                    .end(new JsonObject().put("content", "no config map").encode());
            return;
        }
        
        // Name or End User
        String nameParam = rc.request().getParam("name");
        if (nameParam == null) {
            nameParam = "World";
        }
        final String endUserName = nameParam;
        
        // Call to Message Service
        messageClient
            .get(MESSAGE_SERVICE_API)
            .putHeader("end-user", endUserName)
            .expect(ResponsePredicate.SC_OK).as(BodyCodec.jsonObject())
                .rxSend().map(resp -> {

                    if (resp.statusCode() != 200) {
                        LOGGER.error("Message Client Error when calling: status code {}", resp.statusCode());
                        return new JsonObject().put("content", EMPTY_GREETINGS);
                    }

                    String greetings = resp.body().getString("value");
                    return new JsonObject().put("content",
                            String.format(messageTemplate, greetings, endUserName, receiverName));
                }).subscribe(
                        list -> rc.response().putHeader(CONTENT_TYPE, "application/json; charset=utf-8")
                                .end(list.encodePrettily()),
                        error -> rc.response().putHeader(CONTENT_TYPE, "application/json; charset=utf-8")
                                .setStatusCode(500).end(new JsonObject().put("error", error.getMessage()).toString()));
    }

    private void updateConfiguration() {

        // Message Template
        receiverName = config.getString("name");
        LOGGER.info("New Receiver Name retrieved: {}", receiverName);

        JsonObject messageConfig = config.getJsonObject("message");

        // Message Template
        messageTemplate = messageConfig.getString("template");
        LOGGER.info("New Message Template retrieved: {}", messageTemplate);

        // Message Service Client
        JsonObject serviceConfig = messageConfig.getJsonObject("service");
        messageServiceHost = serviceConfig.getString("host", "localhost");
        messageServicePort = serviceConfig.getInteger("port", 8080);

        messageClient = WebClient.create(vertx,
                new WebClientOptions().setDefaultHost(messageServiceHost).setDefaultPort(messageServicePort));
        LOGGER.info("New Message Service Endpoint retrieved: {}:{}", messageServiceHost, messageServicePort);
    }
}