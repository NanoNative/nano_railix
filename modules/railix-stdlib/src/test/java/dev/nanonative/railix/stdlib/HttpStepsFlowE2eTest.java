package dev.nanonative.railix.stdlib;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class HttpStepsFlowE2eTest {
    @Test
    void getSendsExplicitHeadersAndNormalizesJson() throws Exception {
        final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        try (LocalServer server = server(exchange -> {
            captured.set(capture(exchange));
            exchange.getResponseHeaders().add("X-Result", "one");
            exchange.getResponseHeaders().add("X-Result", "two");
            respond(exchange, 200, "{\"name\":\"Railix\"}".getBytes(StandardCharsets.UTF_8));
        })) {
            final RunResult result = run(
                    "http.get",
                    server.url("/value?mode=full"),
                    headers("Authorization", "Bearer local"),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            );

            assertResponse(
                    result,
                    "success",
                    200,
                    RailixValue.object(Map.of("name", RailixValue.string("Railix")))
            );
            assertThat(captured.get()).satisfies(request -> {
                assertThat(request.method()).isEqualTo("GET");
                assertThat(request.path()).isEqualTo("/value?mode=full");
                assertThat(request.body()).isEmpty();
                assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer local");
            });
            assertThat(responseHeaders(result).values().get("x-result"))
                    .isEqualTo(RailixValue.array(List.of(
                            RailixValue.string("one"),
                            RailixValue.string("two")
                    )));
        }
    }

    @Test
    void postSendsCanonicalJsonAndDefaultContentType() throws Exception {
        final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        try (LocalServer server = server(exchange -> {
            captured.set(capture(exchange));
            respond(exchange, 201, "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8));
        })) {
            final Map<String, RailixValue> fields = new LinkedHashMap<>();
            fields.put("b", RailixValue.number(2));
            fields.put("a", RailixValue.number(1));

            assertResponse(run(
                    "http.post",
                    server.url("/items"),
                    RailixValue.object(Map.of()),
                    RailixValue.object(fields),
                    "json",
                    5_000
            ), "success", 201, RailixValue.object(Map.of("accepted", RailixValue.bool(true))));
            assertThat(captured.get()).satisfies(request -> {
                assertThat(request.method()).isEqualTo("POST");
                assertThat(request.body()).isEqualTo("{\"a\":1,\"b\":2}");
                assertThat(request.headers().getFirst("Content-Type"))
                        .isEqualTo("application/json");
            });
        }
    }

    @Test
    void postPreservesAnExplicitVendorJsonContentType() throws Exception {
        final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        try (LocalServer server = server(exchange -> {
            captured.set(capture(exchange));
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        })) {
            assertResponse(run(
                    "http.post",
                    server.url("/items"),
                    headers("content-type", "application/vnd.railix+json"),
                    RailixValue.object(Map.of()),
                    "json",
                    5_000
            ), "success", 200, RailixValue.object(Map.of()));

            assertThat(captured.get().headers().get("Content-Type"))
                    .containsExactly("application/vnd.railix+json");
        }
    }

    @Test
    void deleteSendsNoBody() throws Exception {
        final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        try (LocalServer server = server(exchange -> {
            captured.set(capture(exchange));
            respond(exchange, 204, new byte[0]);
        })) {
            assertResponse(run(
                    "http.delete",
                    server.url("/items/7"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "success", 204, RailixValue.nullValue());
            assertThat(captured.get()).satisfies(request -> {
                assertThat(request.method()).isEqualTo("DELETE");
                assertThat(request.body()).isEmpty();
            });
        }
    }

    @Test
    void yamlResponseUsesTheExplicitFormat() throws Exception {
        try (LocalServer server = server(exchange ->
                respond(exchange, 200, "name: \"Railix\"\n".getBytes(StandardCharsets.UTF_8)))) {
            assertResponse(run(
                    "http.get",
                    server.url("/yaml"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "yaml",
                    5_000
            ), "success", 200, RailixValue.object(Map.of(
                    "name",
                    RailixValue.string("Railix")
            )));
        }
    }

    @Test
    void xmlResponseUsesTheExplicitFormat() throws Exception {
        final byte[] response = "<object><field name=\"name\"><string>Railix</string>"
                .concat("</field></object>")
                .getBytes(StandardCharsets.UTF_8);
        try (LocalServer server = server(exchange -> respond(exchange, 200, response))) {
            assertResponse(run(
                    "http.get",
                    server.url("/xml"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "xml",
                    5_000
            ), "success", 200, RailixValue.object(Map.of(
                    "name",
                    RailixValue.string("Railix")
            )));
        }
    }

    @Test
    void emptyResponseIsExplicitNull() throws Exception {
        try (LocalServer server = server(exchange -> respond(exchange, 200, new byte[0]))) {
            assertResponse(run(
                    "http.get",
                    server.url("/empty"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "success", 200, RailixValue.nullValue());
        }
    }

    @Test
    void errorResponseWithoutBodyIsExplicitNull() throws Exception {
        try (LocalServer server = server(exchange -> respond(exchange, 404, new byte[0]))) {
            assertResponse(run(
                    "http.get",
                    server.url("/empty"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "client-error", 404, RailixValue.nullValue());
        }
    }

    @Test
    void invalidSelectedResponseFormatRoutesRejectedWithStatus() throws Exception {
        try (LocalServer server = server(exchange ->
                respond(exchange, 200, "not json".getBytes(StandardCharsets.UTF_8)))) {
            assertResponse(run(
                    "http.get",
                    server.url("/invalid"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "rejected", 200, RailixValue.nullValue());
        }
    }

    @Test
    void normalizationFailurePreservesAcceptedResponseHeaders() throws Exception {
        try (LocalServer server = server(exchange -> {
            exchange.getResponseHeaders().set("X-Result", "present");
            respond(exchange, 200, "not json".getBytes(StandardCharsets.UTF_8));
        })) {
            final RunResult result = run(
                    "http.get",
                    server.url("/invalid"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            );

            assertResponse(result, "rejected", 200, RailixValue.nullValue());
            assertThat(responseHeaders(result).values().get("x-result"))
                    .isEqualTo(RailixValue.array(List.of(RailixValue.string("present"))));
        }
    }

    @Test
    void invalidUtf8ResponseRoutesRejectedWithStatus() throws Exception {
        try (LocalServer server = server(exchange ->
                respond(exchange, 200, new byte[]{'{', '"', (byte) 0xc3, '(', '"', '}'}))) {
            assertResponse(run(
                    "http.get",
                    server.url("/invalid"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "rejected", 200, RailixValue.nullValue());
        }
    }

    @Test
    void redirectIsReturnedWithoutFollowingIt() throws Exception {
        final AtomicInteger redirected = new AtomicInteger();
        try (LocalServer server = server(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/target")) {
                redirected.incrementAndGet();
                respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            exchange.getResponseHeaders().set("Location", "/target");
            respond(exchange, 302, "{}".getBytes(StandardCharsets.UTF_8));
        })) {
            assertResponse(run(
                    "http.get",
                    server.url("/redirect"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "redirect", 302, RailixValue.object(Map.of()));
            assertThat(redirected).hasValue(0);
        }
    }

    @Test
    void clientErrorKeepsItsNormalizedBody() throws Exception {
        try (LocalServer server = server(exchange ->
                respond(exchange, 404, "{\"error\":\"missing\"}".getBytes(StandardCharsets.UTF_8)))) {
            assertResponse(run(
                    "http.get",
                    server.url("/missing"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "client-error", 404, RailixValue.object(Map.of(
                    "error",
                    RailixValue.string("missing")
            )));
        }
    }

    @Test
    void serverErrorKeepsItsNormalizedBody() throws Exception {
        try (LocalServer server = server(exchange ->
                respond(exchange, 503, "{\"error\":\"busy\"}".getBytes(StandardCharsets.UTF_8)))) {
            assertResponse(run(
                    "http.get",
                    server.url("/busy"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "server-error", 503, RailixValue.object(Map.of(
                    "error",
                    RailixValue.string("busy")
            )));
        }
    }

    @Test
    void status299UsesSuccessOutcome() throws Exception {
        assertStatus(299, "success");
    }

    @Test
    void status300UsesRedirectOutcome() throws Exception {
        assertStatus(300, "redirect");
    }

    @Test
    void status399UsesRedirectOutcome() throws Exception {
        assertStatus(399, "redirect");
    }

    @Test
    void status400UsesClientErrorOutcome() throws Exception {
        assertStatus(400, "client-error");
    }

    @Test
    void status499UsesClientErrorOutcome() throws Exception {
        assertStatus(499, "client-error");
    }

    @Test
    void status500UsesServerErrorOutcome() throws Exception {
        assertStatus(500, "server-error");
    }

    @Test
    void status599UsesServerErrorOutcome() throws Exception {
        assertStatus(599, "server-error");
    }

    @Test
    void nonstandardStatusUsesOtherOutcome() throws Exception {
        try (LocalServer server = server(exchange ->
                respond(exchange, 600, "{}".getBytes(StandardCharsets.UTF_8)))) {
            assertResponse(run(
                    "http.get",
                    server.url("/other"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "other", 600, RailixValue.object(Map.of()));
        }
    }

    @Test
    void exactMaximumResponseBodyIsAccepted() throws Exception {
        final byte[] response = ("\"" + "x".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - 2) + "\"")
                .getBytes(StandardCharsets.UTF_8);
        try (LocalServer server = server(exchange -> respond(exchange, 200, response))) {
            assertResponse(run(
                    "http.get",
                    server.url("/maximum"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "success", 200, RailixValue.string(
                    "x".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - 2)
            ));
        }
    }

    @Test
    void oversizedResponseBodyRoutesRejected() throws Exception {
        final byte[] response = new byte[RailixData.DEFAULT_MAX_SOURCE_BYTES + 1];
        try (LocalServer server = server(exchange -> respond(exchange, 200, response))) {
            assertResponse(run(
                    "http.get",
                    server.url("/oversized"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "rejected", 200, RailixValue.nullValue());
        }
    }

    @Test
    void exactMaximumPostBodyIsAccepted() throws Exception {
        final AtomicInteger received = new AtomicInteger();
        try (LocalServer server = server(exchange -> {
            received.set(exchange.getRequestBody().readAllBytes().length);
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        })) {
            assertResponse(run(
                    "http.post",
                    server.url("/maximum"),
                    RailixValue.object(Map.of()),
                    RailixValue.string("x".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - 2)),
                    "json",
                    5_000
            ), "success", 200, RailixValue.object(Map.of()));
            assertThat(received).hasValue(RailixData.DEFAULT_MAX_SOURCE_BYTES);
        }
    }

    @Test
    void exactMaximumMultibytePostBodyIsAccepted() throws Exception {
        final AtomicInteger received = new AtomicInteger();
        final String value = "€".repeat(349_524) + "aa";
        try (LocalServer server = server(exchange -> {
            received.set(exchange.getRequestBody().readAllBytes().length);
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        })) {
            assertResponse(run(
                    "http.post",
                    server.url("/maximum"),
                    RailixValue.object(Map.of()),
                    RailixValue.string(value),
                    "json",
                    5_000
            ), "success", 200, RailixValue.object(Map.of()));
            assertThat(received).hasValue(RailixData.DEFAULT_MAX_SOURCE_BYTES);
        }
    }

    @Test
    void oversizedPostBodyRejectsBeforeConnecting() throws Exception {
        final AtomicInteger received = new AtomicInteger();
        try (LocalServer server = server(exchange -> {
            received.incrementAndGet();
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        })) {
            assertResponse(run(
                    "http.post",
                    server.url("/oversized"),
                    RailixValue.object(Map.of()),
                    RailixValue.string("x".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - 1)),
                    "json",
                    5_000
            ), "rejected", 0, RailixValue.nullValue());
            assertThat(received).hasValue(0);
        }
    }

    @Test
    void oversizedMultibytePostBodyRejectsBeforeConnecting() throws Exception {
        final AtomicInteger received = new AtomicInteger();
        final String value = "€".repeat(349_524) + "aaa";
        try (LocalServer server = server(exchange -> {
            received.incrementAndGet();
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        })) {
            assertResponse(run(
                    "http.post",
                    server.url("/oversized"),
                    RailixValue.object(Map.of()),
                    RailixValue.string(value),
                    "json",
                    5_000
            ), "rejected", 0, RailixValue.nullValue());
            assertThat(received).hasValue(0);
        }
    }

    @Test
    void invalidCanonicalPostValueRejectsBeforeConnecting() throws Exception {
        RailixValue value = RailixValue.nullValue();
        for (int depth = 0; depth < 65; depth++) {
            value = RailixValue.array(List.of(value));
        }
        final AtomicInteger received = new AtomicInteger();
        try (LocalServer server = server(exchange -> {
            received.incrementAndGet();
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        })) {
            assertResponse(run(
                    "http.post",
                    server.url("/invalid"),
                    RailixValue.object(Map.of()),
                    value,
                    "json",
                    5_000
            ), "rejected", 0, RailixValue.nullValue());
            assertThat(received).hasValue(0);
        }
    }

    @Test
    void invalidUnicodePostValueRejectsBeforeConnecting() throws Exception {
        final AtomicInteger received = new AtomicInteger();
        try (LocalServer server = server(exchange -> {
            received.incrementAndGet();
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        })) {
            assertResponse(run(
                    "http.post",
                    server.url("/invalid"),
                    RailixValue.object(Map.of()),
                    RailixValue.string("\ud800"),
                    "json",
                    5_000
            ), "rejected", 0, RailixValue.nullValue());
            assertThat(received).hasValue(0);
        }
    }

    @Test
    void relativeUrlRoutesRejected() {
        assertResponse(run(
                "http.get",
                "/relative",
                RailixValue.object(Map.of()),
                RailixValue.nullValue(),
                "json",
                5_000
        ), "rejected", 0, RailixValue.nullValue());
    }

    @Test
    void emptyUrlRoutesRejected() {
        assertResponse(run(
                "http.get",
                "",
                RailixValue.object(Map.of()),
                RailixValue.nullValue(),
                "json",
                5_000
        ), "rejected", 0, RailixValue.nullValue());
    }

    @Test
    void unsupportedUrlSchemeRoutesRejected() throws Exception {
        assertRejectedBeforeConnecting(
                server -> server.url("/value").replace("http://", "ftp://"),
                RailixValue.object(Map.of())
        );
    }

    @Test
    void urlUserInfoRoutesRejected() throws Exception {
        assertRejectedBeforeConnecting(
                server -> server.url("/value").replace("http://", "http://user@"),
                RailixValue.object(Map.of())
        );
    }

    @Test
    void urlFragmentRoutesRejected() throws Exception {
        assertRejectedBeforeConnecting(
                server -> server.url("/value#fragment"),
                RailixValue.object(Map.of())
        );
    }

    @Test
    void missingUrlHostRoutesRejected() {
        assertResponse(run(
                "http.get",
                "http:///value",
                RailixValue.object(Map.of()),
                RailixValue.nullValue(),
                "json",
                5_000
        ), "rejected", 0, RailixValue.nullValue());
    }

    @Test
    void invalidUrlPortRoutesRejected() {
        assertResponse(run(
                "http.get",
                "http://127.0.0.1:70000/value",
                RailixValue.object(Map.of()),
                RailixValue.nullValue(),
                "json",
                5_000
        ), "rejected", 0, RailixValue.nullValue());
    }

    @Test
    void zeroUrlPortRoutesRejected() {
        assertResponse(run(
                "http.get",
                "http://127.0.0.1:0/value",
                RailixValue.object(Map.of()),
                RailixValue.nullValue(),
                "json",
                5_000
        ), "rejected", 0, RailixValue.nullValue());
    }

    @Test
    void malformedUrlSyntaxRoutesRejected() {
        assertResponse(run(
                "http.get",
                "http://[::1",
                RailixValue.object(Map.of()),
                RailixValue.nullValue(),
                "json",
                5_000
        ), "rejected", 0, RailixValue.nullValue());
    }

    @Test
    void exact8192ByteUrlIsAccepted() throws Exception {
        try (LocalServer server = server(exchange ->
                respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8)))) {
            final String base = server.url("/");
            final String url = base + "x".repeat(8_192 - base.length());

            assertThat(url.getBytes(StandardCharsets.UTF_8)).hasSize(8_192);
            assertResponse(run(
                    "http.get",
                    url,
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "success", 200, RailixValue.object(Map.of()));
        }
    }

    @Test
    void urlOver8192Utf8BytesRoutesRejected() throws Exception {
        final AtomicInteger received = new AtomicInteger();
        try (LocalServer server = server(exchange -> {
            received.incrementAndGet();
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        })) {
            final String base = server.url("/");
            final String url = base + "x".repeat(8_193 - base.length());

            assertThat(url.getBytes(StandardCharsets.UTF_8)).hasSize(8_193);
            assertResponse(run(
                    "http.get",
                    url,
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "rejected", 0, RailixValue.nullValue());
            assertThat(received).hasValue(0);
        }
    }

    @Test
    void requestHeaderValueMustBeAString() throws Exception {
        assertRejectedBeforeConnecting(
                server -> server.url("/"),
                RailixValue.object(Map.of("X-Test", RailixValue.number(7)))
        );
    }

    @Test
    void duplicateCaseInsensitiveRequestHeadersRouteRejected() throws Exception {
        assertRejectedBeforeConnecting(
                server -> server.url("/"),
                RailixValue.object(Map.of(
                        "X-Test",
                        RailixValue.string("one"),
                        "x-test",
                        RailixValue.string("two")
                ))
        );
    }

    @Test
    void moreThan64RequestHeadersRouteRejected() throws Exception {
        final Map<String, RailixValue> values = new LinkedHashMap<>();
        for (int index = 0; index < 65; index++) {
            values.put("X-" + index, RailixValue.string("value"));
        }

        assertRejectedBeforeConnecting(server -> server.url("/"), RailixValue.object(values));
    }

    @Test
    void exactly64RequestHeadersAreAccepted() throws Exception {
        final Map<String, RailixValue> values = new LinkedHashMap<>();
        for (int index = 0; index < 64; index++) {
            values.put("X-" + index, RailixValue.string("value"));
        }
        try (LocalServer server = server(exchange ->
                respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8)))) {
            assertResponse(run(
                    "http.get",
                    server.url("/headers"),
                    RailixValue.object(values),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "success", 200, RailixValue.object(Map.of()));
        }
    }

    @Test
    void requestHeadersOver16KibibytesRouteRejected() throws Exception {
        assertRejectedBeforeConnecting(
                server -> server.url("/"),
                headers("X-Large", "x".repeat(16_378))
        );
    }

    @Test
    void requestHeaderNameOver16KibibytesRoutesRejected() throws Exception {
        assertRejectedBeforeConnecting(
                server -> server.url("/"),
                headers("X".repeat(16_385), "")
        );
    }

    @Test
    void exactly16KibibytesOfMultibyteRequestHeadersAreAccepted() throws Exception {
        try (LocalServer server = server(exchange ->
                respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8)))) {
            assertResponse(run(
                    "http.get",
                    server.url("/headers"),
                    headers("X-Large", "€".repeat(5_459)),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "success", 200, RailixValue.object(Map.of()));
        }
    }

    @Test
    void twoByteCharactersUseTheirUtf8HeaderWidth() throws Exception {
        try (LocalServer server = server(exchange ->
                respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8)))) {
            assertResponse(run(
                    "http.get",
                    server.url("/headers"),
                    headers("X-Large", "é".repeat(8_188) + "a"),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "success", 200, RailixValue.object(Map.of()));
        }
    }

    @Test
    void validSurrogatePairUsesFourUtf8HeaderBytes() throws Exception {
        try (LocalServer server = server(exchange ->
                respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8)))) {
            assertResponse(run(
                    "http.get",
                    server.url("/headers"),
                    headers("X-Emoji", "😀"),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "success", 200, RailixValue.object(Map.of()));
        }
    }

    @Test
    void postGeneratedContentTypeDoesNotConsumeTheExplicitHeaderBudget() throws Exception {
        final Map<String, RailixValue> values = new LinkedHashMap<>();
        for (int index = 0; index < 64; index++) {
            values.put("X-" + index, RailixValue.string("value"));
        }
        final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        try (LocalServer server = server(exchange -> {
            captured.set(capture(exchange));
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        })) {
            assertResponse(run(
                    "http.post",
                    server.url("/headers"),
                    RailixValue.object(values),
                    RailixValue.object(Map.of()),
                    "json",
                    5_000
            ), "success", 200, RailixValue.object(Map.of()));
            assertThat(captured.get().headers().getFirst("Content-Type"))
                    .isEqualTo("application/json");
        }
    }

    @Test
    void invalidRequestHeaderSyntaxRoutesRejected() throws Exception {
        assertRejectedBeforeConnecting(
                server -> server.url("/"),
                headers("Bad Header", "value")
        );
    }

    @Test
    void emptyRequestHeaderNameRoutesRejected() throws Exception {
        assertRejectedBeforeConnecting(
                server -> server.url("/"),
                headers("", "value")
        );
    }

    @Test
    void controlCharacterInRequestHeaderValueRoutesRejected() throws Exception {
        assertRejectedBeforeConnecting(
                server -> server.url("/"),
                headers("X-Test", "line\nbreak")
        );
    }

    @Test
    void horizontalTabInRequestHeaderValueIsAccepted() throws Exception {
        try (LocalServer server = server(exchange ->
                respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8)))) {
            assertResponse(run(
                    "http.get",
                    server.url("/headers"),
                    headers("X-Test", "one\ttwo"),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "success", 200, RailixValue.object(Map.of()));
        }
    }

    @Test
    void deleteCharacterInRequestHeaderValueRoutesRejected() throws Exception {
        assertRejectedBeforeConnecting(
                server -> server.url("/"),
                headers("X-Test", "one\u007ftwo")
        );
    }

    @Test
    void invalidUnicodeRequestHeaderValueRoutesRejected() throws Exception {
        assertRejectedBeforeConnecting(
                server -> server.url("/"),
                headers("X-Test", "\ud800")
        );
    }

    @Test
    void restrictedRequestHeaderRoutesRejected() throws Exception {
        assertRejectedBeforeConnecting(
                server -> server.url("/"),
                headers("Content-Length", "7")
        );
    }

    @Test
    void moreThan64ResponseHeadersRoutesRejectedWithStatus() throws Exception {
        try (LocalServer server = server(exchange -> {
            for (int index = 0; index < 65; index++) {
                exchange.getResponseHeaders().set("X-" + index, "value");
            }
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        })) {
            final RunResult result = run(
                    "http.get",
                    server.url("/headers"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            );

            assertResponse(result, "rejected", 200, RailixValue.nullValue());
            assertThat(responseHeaders(result)).isEqualTo(RailixValue.object(Map.of()));
        }
    }

    @Test
    void exactly64ResponseHeadersAreAccepted() throws Exception {
        try (LocalServer server = server(exchange -> {
            for (int index = 0; index < 62; index++) {
                exchange.getResponseHeaders().set("X-" + index, "value");
            }
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        })) {
            final RunResult result = run(
                    "http.get",
                    server.url("/headers"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            );

            assertResponse(result, "success", 200, RailixValue.object(Map.of()));
            assertThat(responseHeaders(result).values()).hasSize(64);
        }
    }

    @Test
    void responseHeadersOver16KibibytesRouteRejectedWithStatus() throws Exception {
        try (LocalServer server = server(exchange -> {
            exchange.getResponseHeaders().set("X-Large", "x".repeat(16_385));
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        })) {
            final RunResult result = run(
                    "http.get",
                    server.url("/headers"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            );

            assertResponse(result, "rejected", 200, RailixValue.nullValue());
            assertThat(responseHeaders(result)).isEqualTo(RailixValue.object(Map.of()));
        }
    }

    @Test
    void exactly16KibibytesOfResponseHeadersAreAccepted() throws Exception {
        try (LocalServer server = server(exchange -> {
            exchange.getResponseHeaders().set("X-Fill", "x".repeat(16_330));
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        })) {
            final RunResult result = run(
                    "http.get",
                    server.url("/headers"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            );

            assertResponse(result, "success", 200, RailixValue.object(Map.of()));
            assertThat(responseHeaderBytes(result)).isEqualTo(16_384);
        }
    }

    @Test
    void zeroTimeoutIsRejectedByTheCompiler() {
        assertConfigDiagnostic(
                0,
                "FLOW_STEP_CONFIG_FORMAT_MISMATCH",
                "Step configuration timeoutMillis requires format timeout-millis."
        );
    }

    @Test
    void negativeTimeoutIsRejectedByTheCompiler() {
        assertConfigDiagnostic(
                -1,
                "FLOW_STEP_CONFIG_FORMAT_MISMATCH",
                "Step configuration timeoutMillis requires format timeout-millis."
        );
    }

    @Test
    void decimalTimeoutIsRejectedByTheCompiler() {
        assertConfigDiagnostic(
                1.5,
                "FLOW_STEP_CONFIG_FORMAT_MISMATCH",
                "Step configuration timeoutMillis requires format timeout-millis."
        );
    }

    @Test
    void timeoutAboveFiveMinutesIsRejectedByTheCompiler() {
        assertConfigDiagnostic(
                300_001,
                "FLOW_STEP_CONFIG_FORMAT_MISMATCH",
                "Step configuration timeoutMillis requires format timeout-millis."
        );
    }

    @Test
    void oneMillisecondTimeoutIsAcceptedByTheCompiler() {
        assertThat(FlowCompiler.compile(
                flow("http.get", "json", "1"),
                StandardLibrary.catalog()
        )).isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void fiveMinuteTimeoutIsAcceptedByTheCompiler() {
        assertThat(FlowCompiler.compile(
                flow("http.get", "json", "300000"),
                StandardLibrary.catalog()
        )).isInstanceOf(CompileResult.Compiled.class);
    }

    @Test
    void timeoutMustBeANumber() {
        assertThat(FlowCompiler.compile(
                flow("http.get", "json", "\"slow\""),
                StandardLibrary.catalog()
        )).isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                "FLOW_STEP_CONFIG_TYPE_MISMATCH",
                "Step configuration timeoutMillis requires NUMBER but received STRING.",
                "steps.client.config.timeoutMillis"
        ))));
    }

    @Test
    void unsupportedResponseFormatIsRejectedByTheCompiler() {
        assertThat(FlowCompiler.compile(
                flow("http.get", "toml", "5000"),
                StandardLibrary.catalog()
        )).isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                "FLOW_STEP_CONFIG_FORMAT_MISMATCH",
                "Step configuration format requires format data-format.",
                "steps.client.config.format"
        ))));
    }

    @Test
    void omittedHttpConfigurationUsesBothVisibleDefaults() throws Exception {
        final String source = flow("http.get", "json", "5000")
                .replace("\"format\":\"json\",\"timeoutMillis\":5000", "");
        final CompiledFlow flow = compiled(source);
        try (LocalServer server = server(exchange ->
                respond(exchange, 200, "{\"name\":\"Railix\"}".getBytes(StandardCharsets.UTF_8)))) {
            assertResponse(run(
                    flow,
                    "http.get",
                    server.url("/defaults"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue()
            ), "success", 200, RailixValue.object(Map.of(
                    "name",
                    RailixValue.string("Railix")
            )));
        }
    }

    @Test
    void connectionRefusalRoutesRejected() throws Exception {
        final int port;
        try (ServerSocket reserved = new ServerSocket(0)) {
            port = reserved.getLocalPort();
        }

        assertResponse(run(
                "http.get",
                "http://127.0.0.1:" + port + "/",
                RailixValue.object(Map.of()),
                RailixValue.nullValue(),
                "json",
                500
        ), "rejected", 0, RailixValue.nullValue());
    }

    @Test
    void httpsSchemeAttemptsALocalTlsHandshake() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<Integer> firstByte = executor.submit(() -> {
                try (var socket = server.accept()) {
                    return socket.getInputStream().read();
                }
            });
            assertResponse(run(
                    "http.get",
                    "https://127.0.0.1:" + server.getLocalPort() + "/",
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    500
            ), "rejected", 0, RailixValue.nullValue());
            assertThat(firstByte.get()).isEqualTo(22);
        }
    }

    @Test
    void requestTimeoutRoutesRejectedAndClosesTheExchange() throws Exception {
        final CountDownLatch received = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger closed = new AtomicInteger();
        try (LocalServer server = server(exchange -> {
            received.countDown();
            await(release);
            streamUntilClosed(exchange, closed, finished);
        })) {
            try {
                assertResponse(run(
                        "http.get",
                        server.url("/slow"),
                        RailixValue.object(Map.of()),
                        RailixValue.nullValue(),
                        "json",
                        50
                ), "rejected", 0, RailixValue.nullValue());
                assertThat(received.getCount()).isZero();
            } finally {
                release.countDown();
            }
            assertThat(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(closed).hasValue(1);
        }
    }

    @Test
    @Timeout(10)
    void responseBodyStallIsBoundedAfterHeadersArrive() throws Exception {
        final CountDownLatch bodyStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try (LocalServer server = server(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (exchange; var output = exchange.getResponseBody()) {
                output.write("{\"active\":".getBytes(StandardCharsets.UTF_8));
                output.flush();
                bodyStarted.countDown();
                await(release);
            } catch (final IOException ignored) {
                exchange.close();
            }
        })) {
            try {
                final long started = System.nanoTime();
                assertResponse(run(
                        "http.get",
                        server.url("/stall"),
                        RailixValue.object(Map.of()),
                        RailixValue.nullValue(),
                        "json",
                        50
                ), "rejected", 200, RailixValue.nullValue());
                assertThat(bodyStarted.getCount()).isZero();
                assertThat(Duration.ofNanos(System.nanoTime() - started))
                        .isLessThan(Duration.ofSeconds(2));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    @Timeout(10)
    void responseBodyTrickleDoesNotResetTheRequestDeadline() throws Exception {
        final CountDownLatch release = new CountDownLatch(1);
        try (LocalServer server = server(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (exchange; var output = exchange.getResponseBody()) {
                output.write('{');
                output.flush();
                while (release.getCount() > 0) {
                    output.write(' ');
                    output.flush();
                    Thread.sleep(20);
                }
            } catch (final IOException ignored) {
                exchange.close();
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        })) {
            try {
                final long started = System.nanoTime();
                assertResponse(run(
                        "http.get",
                        server.url("/trickle"),
                        RailixValue.object(Map.of()),
                        RailixValue.nullValue(),
                        "json",
                        75
                ), "rejected", 200, RailixValue.nullValue());
                assertThat(Duration.ofNanos(System.nanoTime() - started))
                        .isLessThan(Duration.ofSeconds(2));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    @Timeout(10)
    void interruptionDuringResponseBodyCancelsTheFlow() throws Exception {
        final CountDownLatch bodyStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CompiledFlow flow = compiled(flow("http.get", "json", "300000"));
        try (LocalServer server = server(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (exchange; var output = exchange.getResponseBody()) {
                output.write('{');
                output.flush();
                bodyStarted.countDown();
                await(release);
            } catch (final IOException ignored) {
                exchange.close();
            }
        })) {
            final AtomicReference<RunResult> result = new AtomicReference<>();
            final Thread execution = Thread.ofVirtual().start(() -> result.set(run(
                    flow,
                    "http.get",
                    server.url("/body"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue()
            )));
            bodyStarted.await();
            execution.interrupt();
            try {
                assertThat(execution.join(Duration.ofSeconds(5))).isTrue();
                assertThat(result.get()).isEqualTo(new RunResult.Cancelled(List.of()));
            } finally {
                release.countDown();
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    @Timeout(10)
    void interruptionCancelsTheFlowAndLeavesTheClientReusable() throws Exception {
        final CompiledFlow flow = compiled(flow("http.get", "json", "300000"));
        final CountDownLatch received = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger closed = new AtomicInteger();
        try (LocalServer server = server(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/after")) {
                respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            received.countDown();
            await(release);
            streamUntilClosed(exchange, closed, finished);
        })) {
            final AtomicReference<RunResult> result = new AtomicReference<>();
            final Thread execution = Thread.ofVirtual().start(() -> result.set(run(
                    flow,
                    "http.get",
                    server.url("/cancel"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue()
            )));
            received.await();
            execution.interrupt();
            assertThat(execution.join(Duration.ofSeconds(5))).isTrue();
            assertThat(result.get()).isEqualTo(new RunResult.Cancelled(List.of()));
            release.countDown();
            assertThat(finished.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(closed).hasValue(1);

            assertResponse(run(
                    flow,
                    "http.get",
                    server.url("/after"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue()
            ), "success", 200, RailixValue.object(Map.of()));
        } finally {
            release.countDown();
        }
    }

    @Test
    void serverClosingWithoutAResponseRoutesRejected() throws Exception {
        try (LocalServer server = server(HttpExchange::close)) {
            assertResponse(run(
                    "http.get",
                    server.url("/close"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "rejected", 0, RailixValue.nullValue());
        }
    }

    @Test
    void repeatedRequestsDoNotRetainPriorResponses() throws Exception {
        final AtomicInteger value = new AtomicInteger();
        final CompiledFlow flow = compiled(flow("http.get", "json", "5000"));
        try (LocalServer server = server(exchange -> respond(
                exchange,
                200,
                Integer.toString(value.incrementAndGet()).getBytes(StandardCharsets.UTF_8)
        ))) {
            final RunResult first = run(
                    flow,
                    "http.get",
                    server.url("/value"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue()
            );
            final RunResult second = run(
                    flow,
                    "http.get",
                    server.url("/value"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue()
            );

            assertResponse(first, "success", 200, RailixValue.number(1));
            assertResponse(second, "success", 200, RailixValue.number(2));
        }
    }

    @Test
    void concurrentRequestsRemainIsolated() throws Exception {
        final CompiledFlow flow = compiled(flow("http.get", "json", "5000"));
        try (LocalServer server = server(exchange -> {
            final String value = exchange.getRequestURI().getPath().substring(1);
            exchange.getResponseHeaders().set("X-Value", value);
            respond(exchange, 200, ("\"" + value + "\"").getBytes(StandardCharsets.UTF_8));
        });
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<RunResult>> requests = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                final String value = "value-" + index;
                requests.add(executor.submit(() -> run(
                        flow,
                        "http.get",
                        server.url("/" + value),
                        RailixValue.object(Map.of()),
                        RailixValue.nullValue()
                )));
            }
            for (int index = 0; index < requests.size(); index++) {
                final RunResult result = requests.get(index).get();
                assertResponse(
                        result,
                        "success",
                        200,
                        RailixValue.string("value-" + index)
                );
                assertThat(responseHeaders(result).values().get("x-value"))
                        .isEqualTo(RailixValue.array(List.of(
                                RailixValue.string("value-" + index)
                        )));
            }
        }
    }

    @Test
    @Timeout(20)
    void concurrentRequestsUseOneBoundedTransportOwner() throws Exception {
        final int count = 64;
        final CountDownLatch received = new CountDownLatch(count);
        final CountDownLatch release = new CountDownLatch(1);
        final CompiledFlow flow = compiled(flow("http.get", "json", "5000"));
        final long selectors = httpClientSelectorThreads();
        try (LocalServer server = server(exchange -> {
            received.countDown();
            await(release);
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        });
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<RunResult>> requests = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                requests.add(executor.submit(() -> run(
                        flow,
                        "http.get",
                        server.url("/bounded"),
                        RailixValue.object(Map.of()),
                        RailixValue.nullValue()
                )));
            }
            try {
                assertThat(received.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(httpClientSelectorThreads()).isLessThanOrEqualTo(selectors + 1);
            } finally {
                release.countDown();
            }
            for (final Future<RunResult> request : requests) {
                assertResponse(
                        request.get(),
                        "success",
                        200,
                        RailixValue.object(Map.of())
                );
            }
        }
        assertThat(httpClientSelectorThreads()).isLessThanOrEqualTo(selectors);
    }

    @Test
    @Timeout(30)
    void repeatedRequestsReleaseTransportResources() throws Exception {
        final int repetitions = 1_000;
        final AtomicInteger received = new AtomicInteger();
        final CompiledFlow flow = compiled(flow("http.get", "json", "5000"));
        try (LocalServer server = server(exchange -> {
            received.incrementAndGet();
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        })) {
            assertResponse(run(
                    flow,
                    "http.get",
                    server.url("/warmup"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue()
            ), "success", 200, RailixValue.object(Map.of()));
            final long selectors = httpClientSelectorThreads();
            final OptionalLong descriptors = openFileDescriptors();

            for (int index = 0; index < repetitions; index++) {
                assertResponse(run(
                        flow,
                        "http.get",
                        server.url("/soak"),
                        RailixValue.object(Map.of()),
                        RailixValue.nullValue()
                ), "success", 200, RailixValue.object(Map.of()));
            }

            assertThat(received).hasValue(repetitions + 1);
            assertThat(httpClientSelectorThreads()).isLessThanOrEqualTo(selectors);
            descriptors.ifPresent(before -> {
                final OptionalLong after = openFileDescriptors();
                assertThat(after).isPresent();
                assertThat(after.getAsLong()).isLessThanOrEqualTo(before + 4);
            });
        }
    }

    private static RunResult run(
            final String step,
            final String url,
            final RailixValue.ObjectValue headers,
            final RailixValue body,
            final String format,
            final long timeoutMillis
    ) {
        final CompiledFlow flow = compiled(flow(
                step,
                format,
                Long.toString(timeoutMillis)
        ));
        return run(flow, step, url, headers, body);
    }

    private static RunResult run(
            final CompiledFlow flow,
            final String step,
            final String url,
            final RailixValue.ObjectValue headers,
            final RailixValue body
    ) {
        final Map<String, RailixValue> event = new LinkedHashMap<>();
        event.put("url", RailixValue.string(url));
        event.put("headers", headers);
        if (step.equals("http.post")) {
            event.put("body", body);
        }
        return flow.run(RailixValue.object(event));
    }

    private static long httpClientSelectorThreads() {
        long selectors = 0;
        for (final Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive()
                    && thread.getName().startsWith("HttpClient-")
                    && thread.getName().endsWith("-SelectorManager")) {
                selectors++;
            }
        }
        return selectors;
    }

    private static OptionalLong openFileDescriptors() {
        if (ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
            return OptionalLong.of(unix.getOpenFileDescriptorCount());
        }
        return OptionalLong.empty();
    }

    private static CompiledFlow compiled(final String source) {
        final CompileResult compilation = FlowCompiler.compile(source, StandardLibrary.catalog());
        if (!(compilation instanceof CompileResult.Compiled compiled)) {
            throw new AssertionError("HTTP flow did not compile: " + compilation);
        }
        return compiled.flow();
    }

    private static String flow(
            final String step,
            final String format,
            final String timeoutMillis
    ) {
        final boolean post = step.equals("http.post");
        return """
                {
                  "id":"http-flow",
                  "triggers":[],
                  "entry":"client",
                  "inputs":{"url":"string","headers":"object"%s},
                  "outputs":{"status":"number","headers":"object","body":"any"},
                  "steps":[
                    {"id":"client","use":"%s","config":{
                      "format":"%s","timeoutMillis":%s
                    },"on":{
                      "success":"end","redirect":"end","client-error":"end",
                      "server-error":"end","other":"end","rejected":"end"
                    }}
                  ],
                  "connections":[
                    {"from":"input.url","to":"client.url"},
                    {"from":"input.headers","to":"client.headers"}%s,
                    {"from":"client.status","to":"output.status"},
                    {"from":"client.headers","to":"output.headers"},
                    {"from":"client.body","to":"output.body"}
                  ]
                }
                """.formatted(
                post ? ",\"body\":\"any\"" : "",
                step,
                format,
                timeoutMillis,
                post ? ",{\"from\":\"input.body\",\"to\":\"client.body\"}" : ""
        );
    }

    private static void assertConfigDiagnostic(
            final Number timeout,
            final String code,
            final String message
    ) {
        assertThat(FlowCompiler.compile(
                flow("http.get", "json", timeout.toString()),
                StandardLibrary.catalog()
        )).isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                code,
                message,
                "steps.client.config.timeoutMillis"
        ))));
    }

    private static void assertStatus(final int status, final String outcome) throws Exception {
        try (LocalServer server = server(exchange ->
                respond(exchange, status, "{}".getBytes(StandardCharsets.UTF_8)))) {
            assertResponse(run(
                    "http.get",
                    server.url("/status"),
                    RailixValue.object(Map.of()),
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), outcome, status, RailixValue.object(Map.of()));
        }
    }

    private static void assertResponse(
            final RunResult result,
            final String outcome,
            final int status,
            final RailixValue body
    ) {
        assertThat(result).isInstanceOf(RunResult.Succeeded.class);
        final RunResult.Succeeded succeeded = (RunResult.Succeeded) result;
        assertThat(succeeded.steps()).containsExactly(
                new RunResult.StepExecution("client", outcome)
        );
        assertThat(succeeded.outputs().values().get("status"))
                .isEqualTo(RailixValue.number(status));
        assertThat(succeeded.outputs().values().get("body")).isEqualTo(body);
    }

    private static RailixValue.ObjectValue responseHeaders(final RunResult result) {
        return (RailixValue.ObjectValue) ((RunResult.Succeeded) result)
                .outputs()
                .values()
                .get("headers");
    }

    private static int responseHeaderBytes(final RunResult result) {
        int bytes = 0;
        for (final Map.Entry<String, RailixValue> header
                : responseHeaders(result).values().entrySet()) {
            final RailixValue.ArrayValue values = (RailixValue.ArrayValue) header.getValue();
            for (final RailixValue value : values.values()) {
                bytes += header.getKey().getBytes(StandardCharsets.UTF_8).length;
                bytes += ((RailixValue.StringValue) value)
                        .value()
                        .getBytes(StandardCharsets.UTF_8)
                        .length;
            }
        }
        return bytes;
    }

    private static RailixValue.ObjectValue headers(final String name, final String value) {
        return RailixValue.object(Map.of(name, RailixValue.string(value)));
    }

    private static void assertRejectedBeforeConnecting(
            final Function<LocalServer, String> url,
            final RailixValue.ObjectValue headers
    ) throws Exception {
        final AtomicInteger requests = new AtomicInteger();
        try (LocalServer server = server(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
        })) {
            assertResponse(run(
                    "http.get",
                    url.apply(server),
                    headers,
                    RailixValue.nullValue(),
                    "json",
                    5_000
            ), "rejected", 0, RailixValue.nullValue());
            assertThat(requests).hasValue(0);
        }
    }

    private static LocalServer server(final HttpHandler handler) throws IOException {
        return new LocalServer(handler);
    }

    private static CapturedRequest capture(final HttpExchange exchange) throws IOException {
        return new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().toString(),
                exchange.getRequestHeaders(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
        );
    }

    private static void respond(
            final HttpExchange exchange,
            final int status,
            final byte[] body
    ) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (exchange; var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static void streamUntilClosed(
            final HttpExchange exchange,
            final AtomicInteger closed,
            final CountDownLatch finished
    ) {
        final byte[] body = new byte[65_536];
        try (exchange; var output = exchange.getResponseBody()) {
            exchange.sendResponseHeaders(200, 0);
            for (int index = 0; index < 1_024; index++) {
                output.write(body);
                output.flush();
            }
        } catch (final IOException exception) {
            closed.incrementAndGet();
        } finally {
            finished.countDown();
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record CapturedRequest(
            String method,
            String path,
            com.sun.net.httpserver.Headers headers,
            String body
    ) {
    }

    private static final class LocalServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;

        private LocalServer(final HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/", handler);
            server.start();
        }

        private String url(final String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        @Override
        public void close() {
            server.stop(0);
            executor.close();
        }
    }
}
