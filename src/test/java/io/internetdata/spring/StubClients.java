package io.internetdata.spring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.internetdata.InternetData;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Clients pointed at a real HTTP server on an ephemeral port.
 *
 * <p>A real server rather than a mocked HttpClient: the SDK builds its own client internally, and
 * a stub would have to reimplement enough of java.net.http to be its own source of bugs. The
 * server costs a millisecond and exercises the actual transport.
 */
final class StubClients {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode CORPUS = corpus();

    private StubClients() {}

    /**
     * A served answer from the corpus's lookup cases: {@code unlimited-answer}, Unlimited's, or
     * {@code paid-full-shape}, a paid plan's. A served answer needs every member the spec requires,
     * so {@code {"ip": ...}} alone is no answer here.
     */
    static String answer(String name) {
        for (JsonNode c : CORPUS.get("lookup")) {
            if (name.equals(c.get("name").asText())) {
                return c.get("body").toString();
            }
        }
        throw new AssertionError("no " + name + " in the corpus's lookup cases");
    }

    /** {@code body} with members replaced, each a name and then its JSON value. */
    static String with(String body, String... replaced) {
        try {
            ObjectNode out = (ObjectNode) MAPPER.readTree(body);
            for (int i = 0; i + 1 < replaced.length; i += 2) {
                out.set(replaced[i], MAPPER.readTree(replaced[i + 1]));
            }
            return MAPPER.writeValueAsString(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Answers every lookup with {@code body}, about {@code ip}. */
    static InternetData serving(String body, String ip) {
        return clientOn(200, with(body, "ip", MAPPER.valueToTree(ip).toString()));
    }

    /** Answers every lookup with a 500, for the fail-open path. */
    static InternetData failing() {
        return clientOn(500, "{\"error\":\"boom\"}");
    }

    private static InternetData clientOn(int status, String body) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders().add("content-type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return InternetData.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .cacheEnabled(false)
                    .retries(0)
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode corpus() {
        try {
            return MAPPER.readTree(Files.readString(Path.of("testdata", "testdata.json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
