package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CreatorServerComponentTest {
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void healthEndpointReportsReady() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(get(server, "health")).isEqualTo(new Response(
                    200,
                    "application/json; charset=utf-8",
                    "{\"status\":\"ready\"}"
            ));
        }
    }

    @Test
    void creatorServesBundledIde() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = get(server, "");

            assertThat(response).satisfies(page -> {
                assertThat(page.status()).isEqualTo(200);
                assertThat(page.contentType()).isEqualTo("text/html; charset=utf-8");
                assertThat(page.body()).contains("Railix Creator", "flow-canvas", "/app.js");
            });
        }
    }

    @Test
    void creatorServesBundledStylesheet() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = get(server, "app.css");

            assertThat(response).satisfies(page -> {
                assertThat(page.status()).isEqualTo(200);
                assertThat(page.contentType()).isEqualTo("text/css; charset=utf-8");
                assertThat(page.body()).contains(".flow-canvas", "--accent: #d85f2d");
            });
        }
    }

    @Test
    void creatorServesBundledJavascript() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = get(server, "app.js");

            assertThat(response).satisfies(page -> {
                assertThat(page.status()).isEqualTo(200);
                assertThat(page.contentType()).isEqualTo("text/javascript; charset=utf-8");
                assertThat(page.body()).contains("/api/compile", "/api/run");
            });
        }
    }

    @Test
    void stepPaletteComesFromRealStandardLibrary() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = get(server, "api/steps");

            assertThat(response).satisfies(result -> {
                assertThat(result.status()).isEqualTo(200);
                assertThat(result.body()).contains(
                        "\"id\":\"text.lowercase\"",
                        "\"name\":\"text\"",
                        "\"shape\":\"string\""
                );
            });
        }
    }

    @Test
    void callerSuppliedCatalogOwnsTheStepPalette() throws Exception {
        try (CreatorServer server = CreatorServer.start(0, customCatalog())) {
            final Response response = get(server, "api/steps");

            assertThat(response.body())
                    .contains("\"id\":\"example.text.prefix\"")
                    .doesNotContain("\"id\":\"text.lowercase\"");
        }
    }

    @Test
    void realStepCatalogEntryUsesTheStepKind() throws Exception {
        try (CreatorServer server = CreatorServer.start(0, customCatalog())) {
            assertThat(catalogStep(server, "example.text.prefix").values().get("kind"))
                    .isEqualTo(RailixValue.string("step"));
        }
    }

    @Test
    void lowercaseCatalogEntryUsesTheNormalizerKind() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(catalogStep(server, "text.lowercase").values().get("kind"))
                    .isEqualTo(RailixValue.string("normalizer"));
        }
    }

    @Test
    void nonblankCatalogEntryUsesTheValidatorKind() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(catalogStep(server, "text.nonblank").values().get("kind"))
                    .isEqualTo(RailixValue.string("validator"));
        }
    }

    @Test
    void defaultIfNullCatalogEntryUsesTheMapperKind() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(catalogStep(server, "value.default-if-null").values().get("kind"))
                    .isEqualTo(RailixValue.string("mapper"));
        }
    }

    @Test
    void translateExactCatalogEntryUsesTheTranslatorKind() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(catalogStep(server, "text.translate-exact").values().get("kind"))
                    .isEqualTo(RailixValue.string("translator"));
        }
    }

    @Test
    void nonblankCatalogEntryExposesItsVisibleDefault() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(catalogStep(server, "text.nonblank").values().get("config"))
                    .isEqualTo(RailixValue.array(List.of(RailixValue.object(Map.of(
                            "default", RailixValue.bool(true),
                            "name", RailixValue.string("trimBeforeCheck"),
                            "required", RailixValue.bool(false),
                            "shape", RailixValue.string("boolean")
                    )))));
        }
    }

    @Test
    void defaultIfNullCatalogEntryExposesRequiredReplacement() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(catalogStep(server, "value.default-if-null").values().get("config"))
                    .isEqualTo(RailixValue.array(List.of(RailixValue.object(Map.of(
                            "name", RailixValue.string("replacement"),
                            "required", RailixValue.bool(true),
                            "shape", RailixValue.string("any")
                    )))));
        }
    }

    @Test
    void translateExactCatalogEntryExposesRequiredValues() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(catalogStep(server, "text.translate-exact").values().get("config"))
                    .isEqualTo(RailixValue.array(List.of(
                            RailixValue.object(Map.of(
                                    "name", RailixValue.string("from"),
                                    "required", RailixValue.bool(true),
                                    "shape", RailixValue.string("string")
                            )),
                            RailixValue.object(Map.of(
                                    "name", RailixValue.string("to"),
                                    "required", RailixValue.bool(true),
                                    "shape", RailixValue.string("string")
                            ))
                    )));
        }
    }

    @Test
    void lowercaseCatalogEntryExposesItsLanguageTagFormat() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final RailixValue.ArrayValue config = (RailixValue.ArrayValue) catalogStep(
                    server,
                    "text.lowercase"
            ).values().get("config");
            final RailixValue.ObjectValue languageTag =
                    (RailixValue.ObjectValue) config.values().getFirst();

            assertThat(languageTag.values().get("format"))
                    .isEqualTo(RailixValue.string("language-tag"));
        }
    }

    @Test
    void fileReadCatalogEntryExposesItsDataFormatDefault() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(catalogStep(server, "file.read").values().get("config"))
                    .isEqualTo(RailixValue.array(List.of(RailixValue.object(Map.of(
                            "default", RailixValue.string("json"),
                            "format", RailixValue.string("data-format"),
                            "name", RailixValue.string("format"),
                            "required", RailixValue.bool(false),
                            "shape", RailixValue.string("string")
                    )))));
        }
    }

    @Test
    void fileWriteCatalogEntryExposesItsCompleteContract() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(catalogStep(server, "file.write")).isEqualTo(RailixValue.object(Map.of(
                    "config", RailixValue.array(List.of(RailixValue.object(Map.of(
                            "default", RailixValue.bool(false),
                            "name", RailixValue.string("overwrite"),
                            "required", RailixValue.bool(false),
                            "shape", RailixValue.string("boolean")
                    )))),
                    "id", RailixValue.string("file.write"),
                    "inputs", RailixValue.array(List.of(
                            RailixValue.object(Map.of(
                                    "name", RailixValue.string("path"),
                                    "shape", RailixValue.string("string")
                            )),
                            RailixValue.object(Map.of(
                                    "name", RailixValue.string("value"),
                                    "shape", RailixValue.string("any")
                            ))
                    )),
                    "kind", RailixValue.string("step"),
                    "outcomes", RailixValue.array(List.of(
                            RailixValue.string("written"),
                            RailixValue.string("conflict"),
                            RailixValue.string("rejected")
                    )),
                    "outputs", RailixValue.array(List.of())
            )));
        }
    }

    @Test
    void fileDeleteCatalogEntryExposesItsCompleteContract() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(catalogStep(server, "file.delete")).isEqualTo(RailixValue.object(Map.of(
                    "config", RailixValue.array(List.of()),
                    "id", RailixValue.string("file.delete"),
                    "inputs", RailixValue.array(List.of(RailixValue.object(Map.of(
                            "name", RailixValue.string("path"),
                            "shape", RailixValue.string("string")
                    )))),
                    "kind", RailixValue.string("step"),
                    "outcomes", RailixValue.array(List.of(
                            RailixValue.string("deleted"),
                            RailixValue.string("missing"),
                            RailixValue.string("rejected")
                    )),
                    "outputs", RailixValue.array(List.of())
            )));
        }
    }

    @Test
    void httpGetCatalogEntryExposesItsCompleteContract() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(catalogStep(server, "http.get"))
                    .isEqualTo(httpCatalogEntry("http.get", false));
        }
    }

    @Test
    void httpPostCatalogEntryExposesItsCompleteContract() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(catalogStep(server, "http.post"))
                    .isEqualTo(httpCatalogEntry("http.post", true));
        }
    }

    @Test
    void httpDeleteCatalogEntryExposesItsCompleteContract() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(catalogStep(server, "http.delete"))
                    .isEqualTo(httpCatalogEntry("http.delete", false));
        }
    }

    @Test
    void callerSuppliedCatalogOwnsCompilation() throws Exception {
        try (CreatorServer server = CreatorServer.start(0, customCatalog())) {
            final Response response = post(server, "api/compile", customFlowSource());

            assertThat(response).satisfies(result -> {
                assertThat(result.status()).isEqualTo(200);
                assertThat(result.body()).contains(
                        "\"flow\":\"custom-catalog-flow\"",
                        "\"status\":\"compiled\""
                );
            });
        }
    }

    @Test
    void callerSuppliedCatalogOwnsExecution() throws Exception {
        final String request = runRequest(customFlowSource(), "{\"text\":\"railix\"}");
        try (CreatorServer server = CreatorServer.start(0, customCatalog())) {
            final Response response = post(server, "api/run", request);

            assertThat(response).isEqualTo(new Response(
                    200,
                    "application/json; charset=utf-8",
                    "{\"outputs\":{\"text\":\"Welcome, railix\"},\"status\":\"succeeded\","
                            + "\"steps\":[{\"outcome\":\"ok\",\"step\":\"prefix\"}]}"
            ));
        }
    }

    @Test
    void eventSourceAtTheCanonicalByteLimitRunsInsideItsEnvelope() throws Exception {
        final String source = "{\"text\":\""
                + "x".repeat(1_048_576 - "{\"text\":\"\"}".length())
                + "\"}";
        try (CreatorServer server = CreatorServer.start(0, outputCatalog("ok"))) {
            final Response response = post(server, "api/run", runRequest(customFlowSource(), source));

            assertThat(response).isEqualTo(new Response(
                    200,
                    "application/json; charset=utf-8",
                    "{\"outputs\":{\"text\":\"ok\"},\"status\":\"succeeded\","
                            + "\"steps\":[{\"outcome\":\"ok\",\"step\":\"prefix\"}]}"
            ));
        }
    }

    @Test
    void validatorRunsThroughTheLoopbackHttpBoundary() throws Exception {
        final String request = runRequest(
                operatorFlow("text.nonblank", "text", "string", "", "\"valid\":\"end\",\"invalid\":\"end\""),
                "{\"text\":\" \"}"
        );
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(server, "api/run", request)).isEqualTo(new Response(
                    200,
                    "application/json; charset=utf-8",
                    "{\"outputs\":{\"text\":\" \"},\"status\":\"succeeded\","
                            + "\"steps\":[{\"outcome\":\"invalid\",\"step\":\"operator\"}]}"
            ));
        }
    }

    @Test
    void mapperRunsThroughTheLoopbackHttpBoundary() throws Exception {
        final String request = runRequest(
                operatorFlow(
                        "value.default-if-null",
                        "value",
                        "any",
                        "\"replacement\":\"missing\"",
                        "\"kept\":\"end\",\"defaulted\":\"end\""
                ),
                "{\"value\":null}"
        );
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(server, "api/run", request)).isEqualTo(new Response(
                    200,
                    "application/json; charset=utf-8",
                    "{\"outputs\":{\"value\":\"missing\"},\"status\":\"succeeded\","
                            + "\"steps\":[{\"outcome\":\"defaulted\",\"step\":\"operator\"}]}"
            ));
        }
    }

    @Test
    void translatorRunsThroughTheLoopbackHttpBoundary() throws Exception {
        final String request = runRequest(
                operatorFlow(
                        "text.translate-exact",
                        "text",
                        "string",
                        "\"from\":\"yes\",\"to\":\"accepted\"",
                        "\"translated\":\"end\",\"unchanged\":\"end\""
                ),
                "{\"text\":\"yes\"}"
        );
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(server, "api/run", request)).isEqualTo(new Response(
                    200,
                    "application/json; charset=utf-8",
                    "{\"outputs\":{\"text\":\"accepted\"},\"status\":\"succeeded\","
                            + "\"steps\":[{\"outcome\":\"translated\",\"step\":\"operator\"}]}"
            ));
        }
    }

    @Test
    void runEndpointRejectsMalformedLanguageTagBeforeExecution() throws Exception {
        final String flow = flowSource().replace(
                "\"config\": {}",
                "\"config\": {\"languageTag\": \"not_a_tag\"}"
        );
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(server, "api/run", runRequest(flow, "{\"text\":\"I\"}")))
                    .isEqualTo(new Response(
                            422,
                            "application/json; charset=utf-8",
                            "{\"diagnostics\":[{\"code\":\"FLOW_STEP_CONFIG_FORMAT_MISMATCH\","
                                    + "\"column\":0,\"line\":0,\"message\":\"Step configuration "
                                    + "languageTag requires format language-tag.\","
                                    + "\"path\":\"steps.lowercase.config.languageTag\"}],"
                                    + "\"status\":\"compile-rejected\"}"
                    ));
        }
    }

    @Test
    void nestedMappingsRunThroughTheLoopbackHttpBoundary() throws Exception {
        final String request = runRequest(
                nestedFlowSource(),
                "{\"payload\":{\"person\":{\"name\":\"HELLO RAILIX\"}}}"
        );
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/run", request);

            assertThat(response).isEqualTo(new Response(
                    200,
                    "application/json; charset=utf-8",
                    "{\"outputs\":{\"response\":{\"person\":{\"name\":\"hello railix\"}}},"
                            + "\"status\":\"succeeded\","
                            + "\"steps\":[{\"outcome\":\"ok\",\"step\":\"lowercase\"}]}"
            ));
        }
    }

    @Test
    void missingNestedSourceReturnsTheCompilerOwnedMappingDiagnosticOverHttp() throws Exception {
        final String request = runRequest(nestedFlowSource(), "{\"payload\":{\"person\":{}}}");
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/run", request);

            assertThat(response).isEqualTo(new Response(
                    422,
                    "application/json; charset=utf-8",
                    "{\"diagnostics\":[{\"code\":\"FLOW_MAPPING_SOURCE_MISSING\",\"column\":0,"
                            + "\"line\":0,\"message\":\"Source path "
                            + "[\\\"person\\\",\\\"name\\\"] does not exist.\","
                            + "\"path\":\"connections[0].sourcePath[1]\"}],\"status\":\"run-rejected\","
                            + "\"steps\":[]}"
            ));
        }
    }

    @Test
    void lateOutputMappingRejectionReturnsExecutedStepsOverHttp() throws Exception {
        final String request = runRequest(
                lateMappingFlowSource(),
                "{\"payload\":{\"name\":\"HELLO RAILIX\"}}"
        );
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/run", request);

            assertThat(response).isEqualTo(new Response(
                    422,
                    "application/json; charset=utf-8",
                    "{\"diagnostics\":[{\"code\":\"FLOW_MAPPING_SOURCE_MISSING\",\"column\":0,"
                            + "\"line\":0,\"message\":\"Source path [\\\"missing\\\"] does not exist.\","
                            + "\"path\":\"connections[1].sourcePath[0]\"}],\"status\":\"run-rejected\","
                            + "\"steps\":[{\"outcome\":\"ok\",\"step\":\"lowercase\"}]}"
            ));
        }
    }

    @Test
    void callerSuppliedCatalogCannotBeJavaNull() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CreatorServer.start(0, null))
                .withMessage("Creator Step catalog cannot be Java null.");
    }

    @Test
    void stepCatalogExposesConfigurationDefaults() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = get(server, "api/steps");

            assertThat(response.body()).contains(
                    "\"config\":[{\"default\":\"und\",\"format\":\"language-tag\",\"name\":\"languageTag\","
                            + "\"required\":false,\"shape\":\"string\"}]"
            );
        }
    }

    @Test
    void stepCatalogExposesEveryCanonicalShape() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = get(server, "api/steps");

            assertThat(response.body()).contains(
                    "\"shapes\":[\"any\",\"null\",\"boolean\",\"number\",\"string\",\"array\",\"object\"]"
            );
        }
    }

    @Test
    void stepCatalogExposesTheCompilerOwnedConversions() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = get(server, "api/steps");

            assertThat(response.body()).contains(
                    "\"conversions\":[\"string-to-number\",\"number-to-string\","
                            + "\"string-to-boolean\",\"boolean-to-string\"]"
            );
        }
    }

    @Test
    void stepCatalogExposesOnlyImplementedTriggers() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(get(server, "api/steps").body())
                    .contains("\"triggers\":[\"cli\",\"startup\",\"http\",\"socket\",\"scheduled\"]");
        }
    }

    @Test
    void stepCatalogExposesTheCanonicalEventSourceLimit() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = get(server, "api/steps");

            assertThat(response.body()).contains("\"maxEventSourceBytes\":1048576");
        }
    }

    @Test
    void compileEndpointAcceptsCommittedFlow() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/compile", flowSource());

            assertThat(response).isEqualTo(new Response(
                    200,
                    "application/json; charset=utf-8",
                    "{\"flow\":\"lowercase-app\",\"inputs\":{\"text\":\"string\"},"
                            + "\"outputs\":{\"text\":\"string\"},\"source\":"
                            + RailixJson.write(RailixValue.string(canonicalFlowSource()))
                            + ",\"status\":\"compiled\"}"
            ));
        }
    }

    @Test
    void compileEndpointPreservesTheExplicitEmptyTriggerSet() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(server, "api/compile", flowSource()).body())
                    .contains("\\\"triggers\\\":[]");
        }
    }

    @Test
    void compileEndpointRejectsAnUnimplementedTriggerDeclaration() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(server, "api/compile", flowWithTrigger("custom")))
                    .isEqualTo(unsupportedTriggerResponse("custom"));
        }
    }

    @Test
    void compileEndpointRejectsMalformedLanguageTag() throws Exception {
        final String flow = flowSource().replace(
                "\"config\": {}",
                "\"config\": {\"languageTag\": \"not_a_tag\"}"
        );
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/compile", flow);

            assertThat(response).satisfies(result -> {
                assertThat(result.status()).isEqualTo(422);
                assertThat(result.body()).contains(
                        "\"code\":\"FLOW_STEP_CONFIG_FORMAT_MISMATCH\"",
                        "\"message\":\"Step configuration languageTag requires format language-tag.\"",
                        "\"path\":\"steps.lowercase.config.languageTag\""
                );
            });
        }
    }

    @Test
    void canonicalCompileSourceIsIdempotent() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final String first = compileSource(post(server, "api/compile", flowSource()));

            assertThat(compileSource(post(server, "api/compile", first))).isEqualTo(first);
        }
    }

    @Test
    void compileEndpointRejectsInvalidUtf8() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/compile", new byte[]{'"', (byte) 0xc3, '(', '"'});

            assertThat(response).isEqualTo(new Response(
                    400,
                    "application/json; charset=utf-8",
                    "{\"error\":{\"code\":\"REQUEST_UTF8_INVALID\","
                            + "\"message\":\"Request body must be valid UTF-8.\"},"
                            + "\"status\":\"request-rejected\"}"
            ));
        }
    }

    @Test
    void compileEndpointRejectsEscapedLoneHighSurrogate() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/compile", "{\"id\":\"\\uD800\"}");

            assertThat(response).satisfies(result -> {
                assertThat(result.status()).isEqualTo(422);
                assertThat(result.body()).contains(
                        "\"code\":\"FLOW_JSON_INVALID\"",
                        "Unpaired Unicode surrogate is not allowed in JSON strings."
                );
            });
        }
    }

    @Test
    void compileEndpointRejectsEscapedLoneLowSurrogate() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/compile", "{\"id\":\"\\uDC00\"}");

            assertThat(response).satisfies(result -> {
                assertThat(result.status()).isEqualTo(422);
                assertThat(result.body()).contains(
                        "\"code\":\"FLOW_JSON_INVALID\"",
                        "Unpaired Unicode surrogate is not allowed in JSON strings."
                );
            });
        }
    }

    @Test
    void compileEndpointRejectsOversizedFlow() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/compile", "x".repeat(1_048_577));

            assertThat(response.status()).isEqualTo(413);
        }
    }

    @Test
    void deepDefaultReturnsDeterministicDepthDiagnosticOverCompileHttp() throws Exception {
        final String nestedDefault = "[".repeat(100) + "0" + "]".repeat(100);
        final String source = nestedFlowSource().replace(
                "\"sourcePath\":[\"person\",\"name\"]",
                "\"sourcePath\":[\"person\",\"name\"],\"default\":" + nestedDefault
        );
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/compile", source);

            assertThat(response.body()).contains(
                    "\"code\":\"FLOW_DEPTH_EXCEEDED\"",
                    "Data exceeds the maximum container depth of 64."
            );
        }
    }

    @Test
    void compileEndpointReturnsRealDiagnostics() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/compile", "{");

            assertThat(response).satisfies(result -> {
                assertThat(result.status()).isEqualTo(422);
                assertThat(result.body()).contains(
                        "\"status\":\"compile-rejected\"",
                        "\"code\":\"FLOW_JSON_INVALID\""
                );
            });
        }
    }

    @Test
    void runEndpointExecutesCommittedFlow() throws Exception {
        final String request = runRequest(flowSource(), "{\"text\":\"Hello RAILIX\"}");
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/run", request);

            assertThat(response).isEqualTo(new Response(
                    200,
                    "application/json; charset=utf-8",
                    "{\"outputs\":{\"text\":\"hello railix\"},\"status\":\"succeeded\","
                            + "\"steps\":[{\"outcome\":\"ok\",\"step\":\"lowercase\"}]}"
                ));
        }
    }

    @Test
    void runEndpointRefusesAnUnimplementedFlowTriggerBeforeExecutingTheEvent() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(
                    server,
                    "api/run",
                    runRequest(flowWithTrigger("custom"), "{\"text\":\"Hello RAILIX\"}")
            )).isEqualTo(unsupportedTriggerResponse("custom"));
        }
    }

    @Test
    void runEndpointExecutesEquivalentYamlEvent() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(server, "api/run", eventRequest(
                    flowSource(),
                    "yaml",
                    "text: \"Hello RAILIX\""
            ))).isEqualTo(successfulLowercaseResponse());
        }
    }

    @Test
    void runEndpointExecutesEquivalentXmlEvent() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(server, "api/run", eventRequest(
                    flowSource(),
                    "xml",
                    "<object><field name=\"text\"><string>Hello RAILIX</string></field></object>"
            ))).isEqualTo(successfulLowercaseResponse());
        }
    }

    @Test
    void runEndpointPreservesMalformedJsonEventDiagnostic() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(server, "api/run", eventRequest(flowSource(), "json", "{\"x\":}")))
                    .isEqualTo(eventRejection(
                            "DATA_JSON_INVALID",
                            "Expected a JSON value.",
                            1,
                            6
                    ));
        }
    }

    @Test
    void runEndpointPreservesMalformedYamlEventDiagnostic() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(server, "api/run", eventRequest(flowSource(), "yaml", " \n")))
                    .isEqualTo(eventRejection(
                            "DATA_YAML_INVALID",
                            "Expected a YAML value.",
                            1,
                            1
                    ));
        }
    }

    @Test
    void runEndpointPreservesMalformedXmlEventDiagnostic() throws Exception {
        final String source = "<object><field name=\"x\"><null/></object>";
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(server, "api/run", eventRequest(flowSource(), "xml", source)))
                    .isEqualTo(eventRejection(
                            "DATA_XML_INVALID",
                            "Malformed XML document.",
                            1,
                            34
                    ));
        }
    }

    @Test
    void runEndpointPreservesUnsupportedYamlEventDiagnostic() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(server, "api/run", eventRequest(flowSource(), "yaml", "text:\tvalue")))
                    .isEqualTo(eventRejection(
                            "DATA_YAML_UNSUPPORTED",
                            "YAML tabs are not supported.",
                            1,
                            6
                    ));
        }
    }

    @Test
    void runEndpointPreservesUnsupportedXmlEventDiagnostic() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(server, "api/run", eventRequest(
                    flowSource(),
                    "xml",
                    "<!DOCTYPE object><object/>"
            ))).isEqualTo(eventRejection(
                    "DATA_XML_UNSUPPORTED",
                    "XML DTD and custom entities are not supported.",
                    1,
                    1
            ));
        }
    }

    @Test
    void runEndpointRejectsUnsupportedEventFormatWithoutGuessing() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(server, "api/run", eventRequest(flowSource(), "toml", "text = \"value\"")))
                    .isEqualTo(new Response(
                            400,
                            "application/json; charset=utf-8",
                            "{\"error\":{\"code\":\"EVENT_FORMAT_UNSUPPORTED\","
                                    + "\"message\":\"Event format must be json, yaml, or xml.\"},"
                                    + "\"status\":\"request-rejected\"}"
                    ));
        }
    }

    @Test
    void runEndpointRejectsNormalizedNonObjectEvent() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(server, "api/run", eventRequest(flowSource(), "json", "[]")))
                    .isEqualTo(new Response(
                            422,
                            "application/json; charset=utf-8",
                            "{\"diagnostics\":[{\"code\":\"FLOW_INPUT_OBJECT_REQUIRED\","
                                    + "\"column\":0,\"line\":0,\"message\":\"Flow inputs must be an object.\","
                                    + "\"path\":\"event.source\"}],\"status\":\"event-rejected\"}"
                    ));
        }
    }

    @Test
    void runEndpointReturnsCanonicalNumberLimitInsteadOfInternalFailure() throws Exception {
        final String request = runRequest(flowWithDefault("1e2147483647"), "{\"payload\":{}}");
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/run", request);

            assertThat(response).isEqualTo(numberLimitRunRejection());
        }
    }

    @Test
    void runEndpointReturnsScaleOverflowLimitInsteadOfInternalFailure() throws Exception {
        final String request = runRequest(flowWithDefault("100e2147483647"), "{\"payload\":{}}");
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/run", request);

            assertThat(response).isEqualTo(numberLimitRunRejection());
        }
    }

    @Test
    void runEndpointRejectsInvalidUtf8() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/run", new byte[]{'"', (byte) 0xc3, '(', '"'});

            assertThat(response).isEqualTo(new Response(
                    400,
                    "application/json; charset=utf-8",
                    "{\"error\":{\"code\":\"REQUEST_UTF8_INVALID\","
                            + "\"message\":\"Request body must be valid UTF-8.\"},"
                            + "\"status\":\"request-rejected\"}"
            ));
        }
    }

    @Test
    void runEndpointRejectsMalformedRequestJson() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/run", "{");

            assertThat(response).satisfies(result -> {
                assertThat(result.status()).isEqualTo(400);
                assertThat(result.body()).contains(
                        "\"status\":\"request-rejected\"",
                        "\"code\":\"REQUEST_JSON_INVALID\""
                );
            });
        }
    }

    @Test
    void runEndpointRejectsDeepRequestJsonBeforeRecursiveParse() throws Exception {
        final String request = "[".repeat(100) + "0" + "]".repeat(100);
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/run", request);

            assertThat(response.body()).contains(
                    "\"code\":\"REQUEST_JSON_INVALID\"",
                    "JSON exceeds the maximum container depth of 64."
            );
        }
    }

    @Test
    void runEndpointRequiresFlowEventFormatAndSource() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/run", "{}");

            assertThat(response).isEqualTo(new Response(
                    400,
                    "application/json; charset=utf-8",
                    "{\"error\":{\"code\":\"RUN_REQUEST_INVALID\","
                            + "\"message\":\"Run request requires object field flow and event object "
                            + "string fields format and source.\"},"
                            + "\"status\":\"request-rejected\"}"
            ));
        }
    }

    @Test
    void runEndpointRejectsLegacyInputEnvelopeWithoutFallback() throws Exception {
        final String request = "{\"flow\":" + flowSource() + ",\"input\":{\"text\":\"value\"}}";
        try (CreatorServer server = CreatorServer.start(0)) {
            assertThat(post(server, "api/run", request).body())
                    .contains("\"code\":\"RUN_REQUEST_INVALID\"")
                    .doesNotContain("\"status\":\"succeeded\"");
        }
    }

    @Test
    void runEndpointReturnsAdmissionDiagnostics() throws Exception {
        final String request = runRequest(flowSource(), "{\"text\":\"value\",\"extra\":true}");
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/run", request);

            assertThat(response).satisfies(result -> {
                assertThat(result.status()).isEqualTo(422);
                assertThat(result.body()).contains(
                        "\"status\":\"run-rejected\"",
                        "\"code\":\"FLOW_INPUT_UNKNOWN\""
                );
            });
        }
    }

    @Test
    void runEndpointRejectsOversizedStepOutputWithoutReturningIt() throws Exception {
        try (CreatorServer server = CreatorServer.start(0, oversizedOutputCatalog())) {
            final Response response = post(server, "api/run", runRequest(customFlowSource(), "{\"text\":\"value\"}"));

            assertThat(new OversizedResponseObservation(
                    response.status(),
                    response.body().contains("RUN_RESPONSE_TOO_LARGE"),
                    response.body().contains("x".repeat(1_000))
            )).isEqualTo(new OversizedResponseObservation(422, true, false));
        }
    }

    @Test
    void endpointRejectsWrongHttpMethod() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = get(server, "api/run");

            assertThat(response).isEqualTo(new Response(
                    405,
                    "application/json; charset=utf-8",
                    "{\"error\":{\"code\":\"METHOD_NOT_ALLOWED\","
                            + "\"message\":\"HTTP method is not allowed for this route.\"},"
                            + "\"status\":\"request-rejected\"}"
            ));
        }
    }

    @Test
    void compileEndpointRejectsWrongHttpMethod() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = get(server, "api/compile");

            assertThat(response.status()).isEqualTo(405);
        }
    }

    @Test
    void postEndpointRejectsWrongMethodBeforeReadingItsBody() throws Exception {
        final String oversizedBody = "x".repeat(RailixData.MAX_SOURCE_BYTES + 1);
        try (CreatorServer server = CreatorServer.start(0)) {
            final HttpRequest request = HttpRequest.newBuilder(server.baseUri().resolve("api/run"))
                    .method("GET", HttpRequest.BodyPublishers.ofString(oversizedBody))
                    .build();

            assertThat(send(request).status()).isEqualTo(405);
        }
    }

    @Test
    void getEndpointRejectsPostMethod() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "health", "{}");

            assertThat(response.status()).isEqualTo(405);
        }
    }

    @Test
    void staticAssetRejectsPostMethod() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "", "{}");

            assertThat(response.status()).isEqualTo(405);
        }
    }

    @Test
    void runEndpointRequiresRequestObject() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/run", "[]");

            assertThat(response.status()).isEqualTo(400);
        }
    }

    @Test
    void runEndpointRequiresEventObject() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/run", "{\"flow\":{},\"event\":1}");

            assertThat(response.status()).isEqualTo(400);
        }
    }

    @Test
    void unknownRouteReturnsNotFound() throws Exception {
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = get(server, "missing");

            assertThat(response).isEqualTo(new Response(
                    404,
                    "application/json; charset=utf-8",
                    "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"Route does not exist.\"},"
                            + "\"status\":\"request-rejected\"}"
            ));
        }
    }

    @Test
    void requestBodyIsBounded() throws Exception {
        final String body = "x".repeat(RailixData.MAX_SOURCE_BYTES + 1);
        try (CreatorServer server = CreatorServer.start(0)) {
            final Response response = post(server, "api/run", body);

            assertThat(response).isEqualTo(new Response(
                    413,
                    "application/json; charset=utf-8",
                    "{\"error\":{\"code\":\"REQUEST_TOO_LARGE\","
                            + "\"message\":\"Request body exceeds 8388608 bytes.\"},"
                            + "\"status\":\"request-rejected\"}"
            ));
        }
    }

    @Test
    void closeIsIdempotent() throws IOException {
        final CreatorServer server = CreatorServer.start(0);

        server.close();
        server.close();

        assertThat(server.baseUri().getHost()).isEqualTo("127.0.0.1");
    }

    @Test
    void awaitCloseReturnsAfterServerCloses() throws Exception {
        final CreatorServer server = CreatorServer.start(0);
        final FutureTask<CreatorServer> waiting = new FutureTask<>(server::awaitClose);
        Thread.startVirtualThread(waiting);

        server.close();

        assertThat(waiting.get(2, TimeUnit.SECONDS)).isSameAs(server);
    }

    @Test
    void closeAndAwaitCloseWaitForRunningStepTermination() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CreatorServer server = CreatorServer.start(0, blockingCatalog(started, interrupted, release));
        final FutureTask<Response> running = new FutureTask<>(
                () -> post(server, "api/run", runRequest(customFlowSource(), "{\"text\":\"value\"}"))
        );
        Thread.startVirtualThread(running);
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        final FutureTask<CreatorServer> waiting = new FutureTask<>(server::awaitClose);
        Thread.startVirtualThread(waiting);
        final FutureTask<CreatorServer> closing = new FutureTask<>(() -> {
            server.close();
            return server;
        });
        Thread.startVirtualThread(closing);
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();

        try {
            assertThat(new ShutdownObservation(closing.isDone(), waiting.isDone()))
                    .isEqualTo(new ShutdownObservation(false, false));
        } finally {
            release.countDown();
        }
        assertThat(closing.get(2, TimeUnit.SECONDS)).isSameAs(server);
        assertThat(waiting.get(2, TimeUnit.SECONDS)).isSameAs(server);
        try {
            running.get(2, TimeUnit.SECONDS);
        } catch (final ExecutionException ignored) {
            // The stopped HTTP exchange may disconnect after its trusted Step terminates.
        }
    }

    private static Response get(final CreatorServer server, final String path) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder(server.baseUri().resolve(path)).GET().build();
        return send(request);
    }

    private static RailixValue.ObjectValue catalogStep(
            final CreatorServer server,
            final String id
    ) throws Exception {
        final RailixJson.Parsed parsed = (RailixJson.Parsed) RailixJson.parse(get(server, "api/steps").body());
        final RailixValue.ObjectValue root = (RailixValue.ObjectValue) parsed.value();
        final RailixValue.ArrayValue steps = (RailixValue.ArrayValue) root.values().get("steps");
        for (final RailixValue value : steps.values()) {
            final RailixValue.ObjectValue step = (RailixValue.ObjectValue) value;
            if (RailixValue.string(id).equals(step.values().get("id"))) {
                return step;
            }
        }
        throw new AssertionError("Catalog Step is missing: " + id);
    }

    private static RailixValue.ObjectValue httpCatalogEntry(
            final String id,
            final boolean body
    ) {
        final List<RailixValue> inputs = new ArrayList<>(List.of(
                catalogPort("url", "string"),
                catalogPort("headers", "object")
        ));
        if (body) {
            inputs.add(catalogPort("body", "any"));
        }
        return RailixValue.object(Map.of(
                "config", RailixValue.array(List.of(
                        RailixValue.object(Map.of(
                                "default", RailixValue.string("json"),
                                "format", RailixValue.string("data-format"),
                                "name", RailixValue.string("format"),
                                "required", RailixValue.bool(false),
                                "shape", RailixValue.string("string")
                        )),
                        RailixValue.object(Map.of(
                                "default", RailixValue.number(30_000),
                                "format", RailixValue.string("timeout-millis"),
                                "name", RailixValue.string("timeoutMillis"),
                                "required", RailixValue.bool(false),
                                "shape", RailixValue.string("number")
                        ))
                )),
                "id", RailixValue.string(id),
                "inputs", RailixValue.array(inputs),
                "kind", RailixValue.string("step"),
                "outcomes", RailixValue.array(List.of(
                        RailixValue.string("success"),
                        RailixValue.string("redirect"),
                        RailixValue.string("client-error"),
                        RailixValue.string("server-error"),
                        RailixValue.string("other"),
                        RailixValue.string("rejected")
                )),
                "outputs", RailixValue.array(List.of(
                        catalogPort("status", "number"),
                        catalogPort("headers", "object"),
                        catalogPort("body", "any")
                ))
        ));
    }

    private static RailixValue.ObjectValue catalogPort(
            final String name,
            final String shape
    ) {
        return RailixValue.object(Map.of(
                "name", RailixValue.string(name),
                "shape", RailixValue.string(shape)
        ));
    }

    private static Response post(
            final CreatorServer server,
            final String path,
            final String body
    ) throws Exception {
        return post(server, path, body.getBytes(StandardCharsets.UTF_8));
    }

    private static Response post(
            final CreatorServer server,
            final String path,
            final byte[] body
    ) throws Exception {
        final HttpRequest request = HttpRequest.newBuilder(server.baseUri().resolve(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return send(request);
    }

    private static Response send(final HttpRequest request) throws Exception {
        final HttpResponse<String> response = CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        return new Response(
                response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(""),
                response.body()
        );
    }

    private static String flowSource() throws IOException {
        return Files.readString(Path.of("..", "..", "examples", "lowercase-app", "railix.flow.json"));
    }

    private static String flowWithTrigger(final String type) throws IOException {
        return flowSource().replace(
                "\"triggers\": []",
                "\"triggers\":[{\"id\":\"source\",\"type\":\"" + type + "\",\"config\":{}}]"
        );
    }

    private static Response unsupportedTriggerResponse(final String type) {
        return new Response(
                422,
                "application/json; charset=utf-8",
                "{\"diagnostics\":[{\"code\":\"FLOW_TRIGGER_TYPE_UNSUPPORTED\","
                        + "\"column\":0,\"line\":0,\"message\":\"Trigger type is not implemented: "
                        + type + ".\",\"path\":\"triggers[0].type\"}],\"status\":\"compile-rejected\"}"
        );
    }

    private static String canonicalFlowSource() throws IOException {
        final RailixJson.Parsed parsed = (RailixJson.Parsed) RailixJson.parse(flowSource());
        return RailixJson.write(parsed.value()) + "\n";
    }

    private static StepCatalog customCatalog() {
        return StepCatalog.of(StepDefinition.named("example.text.prefix", "1.0.0")
                .config("prefix", ValueShape.string(), RailixValue.string("Welcome, "))
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output(
                        "text",
                        RailixValue.string(input.configString("prefix") + input.string("text"))
                )));
    }

    private static StepCatalog oversizedOutputCatalog() {
        return outputCatalog("x".repeat(1_048_576));
    }

    private static StepCatalog outputCatalog(final String output) {
        return StepCatalog.of(StepDefinition.named("example.text.prefix", "1.0.0")
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output(
                        "text",
                        RailixValue.string(output)
                )));
    }

    private static StepCatalog blockingCatalog(
            final CountDownLatch started,
            final CountDownLatch interrupted,
            final CountDownLatch release
    ) {
        return StepCatalog.of(StepDefinition.named("example.text.prefix", "1.0.0")
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> {
                    started.countDown();
                    while (release.getCount() > 0) {
                        try {
                            release.await();
                        } catch (final InterruptedException exception) {
                            interrupted.countDown();
                        }
                    }
                    return StepResult.outcome("ok").output("text", RailixValue.string(input.string("text")));
                }));
    }

    private static String customFlowSource() {
        return """
                {
                  "id": "custom-catalog-flow",
                  "triggers": [],
                  "entry": "prefix",
                  "inputs": {"text": "string"},
                  "outputs": {"text": "string"},
                  "steps": [
                    {
                      "id": "prefix",
                      "use": "example.text.prefix",
                      "config": {},
                      "on": {"ok": "end"}
                    }
                  ],
                  "connections": [
                    {"from": "input.text", "to": "prefix.text"},
                    {"from": "prefix.text", "to": "output.text"}
                  ]
                }
                """;
    }

    private static String runRequest(final String flow, final String input) {
        return eventRequest(flow, "json", input);
    }

    private static String eventRequest(
            final String flow,
            final String format,
            final String source
    ) {
        return "{\"flow\":" + flow + ",\"event\":{\"format\":"
                + RailixJson.write(RailixValue.string(format))
                + ",\"source\":" + RailixJson.write(RailixValue.string(source)) + "}}";
    }

    private static Response successfulLowercaseResponse() {
        return new Response(
                200,
                "application/json; charset=utf-8",
                "{\"outputs\":{\"text\":\"hello railix\"},\"status\":\"succeeded\","
                        + "\"steps\":[{\"outcome\":\"ok\",\"step\":\"lowercase\"}]}"
        );
    }

    private static Response eventRejection(
            final String code,
            final String message,
            final int line,
            final int column
    ) {
        return new Response(
                422,
                "application/json; charset=utf-8",
                "{\"diagnostics\":[{\"code\":" + RailixJson.write(RailixValue.string(code))
                        + ",\"column\":" + column
                        + ",\"line\":" + line
                        + ",\"message\":" + RailixJson.write(RailixValue.string(message))
                        + ",\"path\":\"event.source\"}],\"status\":\"event-rejected\"}"
        );
    }

    private static String operatorFlow(
            final String use,
            final String port,
            final String shape,
            final String config,
            final String outcomes
    ) {
        return """
                {
                  "id":"operator-flow",
                  "triggers":[],
                  "entry":"operator",
                  "inputs":{"%1$s":"%2$s"},
                  "outputs":{"%1$s":"%2$s"},
                  "steps":[
                    {"id":"operator","use":"%3$s","config":{%4$s},"on":{%5$s}}
                  ],
                  "connections":[
                    {"from":"input.%1$s","to":"operator.%1$s"},
                    {"from":"operator.%1$s","to":"output.%1$s"}
                  ]
                }
                """.formatted(port, shape, use, config, outcomes);
    }

    private static String nestedFlowSource() {
        return """
                {
                  "id": "nested-lowercase",
                  "triggers": [],
                  "entry": "lowercase",
                  "inputs": {"payload": "object"},
                  "outputs": {"response": "object"},
                  "steps": [
                    {"id":"lowercase","use":"text.lowercase","config":{},"on":{"ok":"end"}}
                  ],
                  "connections": [
                    {"from":"input.payload","sourcePath":["person","name"],"to":"lowercase.text"},
                    {"from":"lowercase.text","to":"output.response","targetPath":["person","name"]}
                  ]
                }
                """;
    }

    private static String lateMappingFlowSource() {
        return """
                {
                  "id": "late-mapping",
                  "triggers": [],
                  "entry": "lowercase",
                  "inputs": {"payload": "object"},
                  "outputs": {"response": "string"},
                  "steps": [
                    {"id":"lowercase","use":"text.lowercase","config":{},"on":{"ok":"end"}}
                  ],
                  "connections": [
                    {"from":"input.payload","sourcePath":["name"],"to":"lowercase.text"},
                    {"from":"input.payload","sourcePath":["missing"],"to":"output.response"}
                  ]
                }
                """;
    }

    private static String flowWithDefault(final String value) {
        return nestedFlowSource().replace(
                "{\"from\":\"input.payload\",\"sourcePath\":[\"person\",\"name\"],\"to\":\"lowercase.text\"}",
                "{\"from\":\"input.payload\",\"sourcePath\":[\"missing\"],\"default\":"
                        + value + ",\"to\":\"lowercase.text\"}"
        );
    }

    private static Response numberLimitRunRejection() {
        return new Response(
                422,
                "application/json; charset=utf-8",
                "{\"diagnostics\":[{\"code\":\"FLOW_NUMBER_LIMIT_EXCEEDED\",\"column\":0,"
                        + "\"line\":0,\"message\":\"Number exceeds the 1024-character canonical limit.\","
                        + "\"path\":\"$\"}],\"status\":\"run-rejected\",\"steps\":[]}"
        );
    }

    private static String compileSource(final Response response) {
        final RailixJson.Parsed parsed = (RailixJson.Parsed) RailixJson.parse(response.body());
        final RailixValue.ObjectValue payload = (RailixValue.ObjectValue) parsed.value();
        return ((RailixValue.StringValue) payload.values().get("source")).value();
    }

    private record Response(int status, String contentType, String body) {
    }

    private record OversizedResponseObservation(int status, boolean diagnostic, boolean outputReturned) {
    }

    private record ShutdownObservation(boolean closeReturned, boolean awaitCloseReturned) {
    }
}
