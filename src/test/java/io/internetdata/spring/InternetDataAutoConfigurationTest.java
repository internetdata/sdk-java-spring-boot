package io.internetdata.spring;

import com.sun.net.httpserver.HttpServer;
import io.internetdata.middleware.Core;
import io.internetdata.middleware.Lookup;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The starter driven the way its README documents it: from application.yml.
 *
 * <p>Every other test builds its condition as Java values, which is how a block condition that
 * only ever arrives as strings from the binder passed a green suite while blocking nobody.
 */
class InternetDataAutoConfigurationTest {
    // Served answers from the corpus's lookup cases, about its own public address.
    private static final String UNLIMITED = StubClients.answer("unlimited-answer");
    private static final String PAID = StubClients.answer("paid-full-shape");
    private static final String PUBLIC_IP = "146.70.116.130";
    private static final String RESPROXY_SEEN_SEVEN_TIMES =
            StubClients.with(PAID, "is_resproxy", "true", "resproxy", "{\"hits\": 7}");

    private static HttpServer serving(String body) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] bytes = StubClients.with(body, "ip", "\"" + PUBLIC_IP + "\"")
                    .getBytes(StandardCharsets.UTF_8);
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders().add("content-type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static WebApplicationContextRunner runner(HttpServer server, String... condition) {
        String[] properties = new String[condition.length + 3];
        properties[0] = "internetdata.api-key=k";
        properties[1] = "internetdata.base-url=http://127.0.0.1:" + server.getAddress().getPort();
        properties[2] = "internetdata.client-ip-header=X-Real-IP";
        System.arraycopy(condition, 0, properties, 3, condition.length);
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(InternetDataAutoConfiguration.class))
                .withPropertyValues(properties);
    }

    private static int statusThrough(InternetDataFilter filter) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Real-IP", PUBLIC_IP);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    // The README's first condition, through the binder that hands every value over as a string.
    @Test
    void aCountryConditionFromYamlBlocksAndAnotherCountryPasses() {
        HttpServer server = serving(UNLIMITED);
        try {
            runner(server, "internetdata.block-condition[0].location.country[0]=CU",
                    "internetdata.block-condition[0].location.country[1]=AT").run(context ->
                    assertEquals(403, statusThrough(context.getBean(InternetDataFilter.class)),
                            "a country condition written in application.yml has to actually block"));
            runner(server, "internetdata.block-condition[0].location.country=DE").run(context ->
                    assertEquals(200, statusThrough(context.getBean(InternetDataFilter.class))));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aNetworkTypeFromYamlBlocks() {
        HttpServer server = serving(PAID);
        try {
            runner(server, "internetdata.block-condition[0].asn.type=hosting").run(context ->
                    assertEquals(403, statusThrough(context.getBean(InternetDataFilter.class))));
        } finally {
            server.stop(0);
        }
    }

    // A postal code is a string of digits and an AS number a number, and YAML gives both as digits.
    @Test
    void digitsFromYamlCompareWithAStringMemberAndANumberAlike() {
        HttpServer server = serving(PAID);
        try {
            runner(server, "internetdata.block-condition[0].location.postal_code=1230").run(context ->
                    assertEquals(403, statusThrough(context.getBean(InternetDataFilter.class)),
                            "a postal code written in application.yml has to match the one served"));
            runner(server, "internetdata.block-condition[0].asn.asn=9009").run(context ->
                    assertEquals(403, statusThrough(context.getBean(InternetDataFilter.class))));
            runner(server, "internetdata.block-condition[0].location.postal_code=1010").run(context ->
                    assertEquals(200, statusThrough(context.getBean(InternetDataFilter.class))));
        } finally {
            server.stop(0);
        }
    }

    // The README skips paths it does not care about; a skipped request is never looked up.
    @Test
    void skipPathsFromYamlLeaveTheirRequestsUntouched() {
        List<String> sent = new CopyOnWriteArrayList<>();
        HttpServer server = recordingAuthorization(sent);
        try {
            keyed(server, "k").withPropertyValues("internetdata.skip-paths[0]=/static/**").run(context -> {
                InternetDataFilter filter = context.getBean(InternetDataFilter.class);
                for (String path : List.of("/static/app.css", "/")) {
                    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
                    request.setRemoteAddr("127.0.0.1");
                    request.addHeader("X-Real-IP", PUBLIC_IP);
                    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
                    assertEquals(path.equals("/"), InternetDataFilter.lookup(request).isPresent(), path);
                }
                assertEquals(1, sent.size(), "only the path not skipped is looked up");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aFlagConditionFromYamlBlocks() {
        HttpServer server = serving(PAID);
        try {
            runner(server, "internetdata.block-condition[0].is_vpn=true").run(context -> {
                assertTrue(context.getBean(Core.class).isBlocking());
                assertEquals(403, statusThrough(context.getBean(InternetDataFilter.class)),
                        "a block-condition written in application.yml has to actually block");
            });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aNumericBoundFromYamlBlocks() {
        HttpServer server = serving(RESPROXY_SEEN_SEVEN_TIMES);
        try {
            runner(server,
                    "internetdata.block-condition[0].is_resproxy=true",
                    "internetdata.block-condition[0].resproxy.hits.gte=7").run(context ->
                    assertEquals(403, statusThrough(context.getBean(InternetDataFilter.class)),
                            "gte on the bound itself has to match, not just above it"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aBoundThatMissesLeavesTheVisitorThrough() {
        HttpServer server = serving(RESPROXY_SEEN_SEVEN_TIMES);
        try {
            runner(server,
                    "internetdata.block-condition[0].is_resproxy=true",
                    "internetdata.block-condition[0].resproxy.hits.gt=7").run(context ->
                    assertEquals(200, statusThrough(context.getBean(InternetDataFilter.class)),
                            "gt on the bound must not match, or the bound is being ignored"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aStringMemberFromYamlStillCompares() {
        HttpServer server = serving(PAID);
        try {
            runner(server,
                    "internetdata.block-condition[0].is_vpn=true",
                    "internetdata.block-condition[0].vpn.provider=Mullvad").run(context ->
                    assertEquals(403, statusThrough(context.getBean(InternetDataFilter.class)),
                            "a provider slug stays a string, and compares without case"));
        } finally {
            server.stop(0);
        }
    }

    // The README's whole configuration is the key. Every other test here also sets a base URL and a
    // client-address header, so none would see a starter that cannot start without them.
    @Test
    void theReadmesConfigurationAloneStartsTheFilter() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(InternetDataAutoConfiguration.class))
                .withPropertyValues("internetdata.api-key=k")
                .run(context -> {
                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    context.getBean(InternetDataFilter.class).doFilter(request, response, new MockFilterChain());
                    assertEquals(200, response.getStatus());
                    // The mock's 127.0.0.1 is a bogon, answered with no request leaving the test.
                    assertTrue(InternetDataFilter.lookup(request).orElseThrow().result().orElseThrow().isBogon());
                });
    }

    // A starter that dropped the key would pass without this: it starts, a loopback peer is a
    // bogon, and a 401 fails open.
    @Test
    void theReadmesKeyGoesOutForThePublicPeerAndAForgedHeaderIsIgnored() {
        List<String> sent = new CopyOnWriteArrayList<>();
        HttpServer server = recordingAuthorization(sent);
        try {
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(InternetDataAutoConfiguration.class))
                    .withPropertyValues("internetdata.api-key=k",
                            "internetdata.base-url=http://127.0.0.1:" + server.getAddress().getPort())
                    .run(context -> {
                        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
                        request.setRemoteAddr(PUBLIC_IP);
                        request.addHeader("X-Forwarded-For", "1.1.1.1");
                        MockHttpServletResponse response = new MockHttpServletResponse();
                        context.getBean(InternetDataFilter.class).doFilter(request, response, new MockFilterChain());
                        assertEquals(200, response.getStatus());
                        assertEquals(List.of("Bearer k"), sent, "the configured key has to reach the API");
                        Lookup lookup = InternetDataFilter.lookup(request).orElseThrow();
                        assertEquals(PUBLIC_IP, lookup.ip().orElseThrow(), "the default is the socket peer, never the header");
                        assertFalse(lookup.result().orElseThrow().isBogon());
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aKeyPlaceholderNothingResolvedGoesOutAsNoKeyAndSaysSo() {
        List<String> warnings = new CopyOnWriteArrayList<>();
        Handler handler = capturing(warnings);
        List<String> sent = new CopyOnWriteArrayList<>();
        HttpServer server = recordingAuthorization(sent);
        try {
            keyed(server, "${INTERNETDATA_TEST_KEY_NOBODY_SETS}").run(context -> {
                assertEquals(200, statusThrough(context.getBean(InternetDataFilter.class)));
                assertEquals(List.of("none"), sent,
                        "an unset variable must not go out as its own placeholder text");
                assertTrue(warnings.stream().anyMatch(w -> w.contains("INTERNETDATA_TEST_KEY_NOBODY_SETS")),
                        "the unset variable has to be named at startup, got " + warnings);
            });
        } finally {
            Logger.getLogger("io.internetdata").removeHandler(handler);
            server.stop(0);
        }
    }

    @Test
    void aBlankKeyGoesOutAsNoKeyWithoutAWarning() {
        List<String> warnings = new CopyOnWriteArrayList<>();
        Handler handler = capturing(warnings);
        List<String> sent = new CopyOnWriteArrayList<>();
        HttpServer server = recordingAuthorization(sent);
        try {
            keyed(server, "${INTERNETDATA_TEST_KEY_NOBODY_SETS:}").run(context -> {
                assertEquals(200, statusThrough(context.getBean(InternetDataFilter.class)));
                assertEquals(List.of("none"), sent, "a blank key is no key, never a bare Bearer");
                assertTrue(warnings.isEmpty(), "a default written on purpose is no mistake: " + warnings);
            });
        } finally {
            Logger.getLogger("io.internetdata").removeHandler(handler);
            server.stop(0);
        }
    }

    @Test
    void aConditionThatConstrainsNothingRefusesToStart() {
        HttpServer server = serving(PAID);
        try {
            runner(server, "internetdata.block-condition[0].is_vpn=false").run(context -> {
                assertNotNull(context.getStartupFailure(),
                        "a condition with no terms left blocks everybody, so the context must fail");
                assertTrue(rootCause(context.getStartupFailure()) instanceof IllegalArgumentException);
            });
        } finally {
            server.stop(0);
        }
    }

    // Answers every lookup and records the Authorization header each one carried, "none" for none.
    private static HttpServer recordingAuthorization(List<String> sent) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] bytes = StubClients.with(UNLIMITED, "ip", "\"" + PUBLIC_IP + "\"")
                    .getBytes(StandardCharsets.UTF_8);
            server.createContext("/", exchange -> {
                String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                sent.add(authorization == null ? "none" : authorization);
                exchange.getResponseHeaders().add("content-type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static WebApplicationContextRunner keyed(HttpServer server, String apiKey) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(InternetDataAutoConfiguration.class))
                .withPropertyValues(
                        "internetdata.api-key=" + apiKey,
                        "internetdata.base-url=http://127.0.0.1:" + server.getAddress().getPort(),
                        "internetdata.client-ip-header=X-Real-IP");
    }

    private static Handler capturing(List<String> warnings) {
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger.getLogger("io.internetdata").addHandler(handler);
        return handler;
    }

    private static Throwable rootCause(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
