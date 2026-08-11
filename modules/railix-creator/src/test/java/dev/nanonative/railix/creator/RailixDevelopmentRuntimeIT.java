package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.stdlib.StandardLibrary;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
final class RailixDevelopmentRuntimeIT {
    private static final Path JAR = Path.of("target", "railix.jar").toAbsolutePath().normalize();
    private static final String TOKEN = "runtime-e2e-token";
    private static final String CONTEXT = """
            {"payload":{"arguments":["Hello RAILIX"]}}
            """;

    Path directory;

    private Process process;
    private BufferedReader output;
    private HttpClient client;
    private URI uri;

    @BeforeAll
    void startPackagedDevelopmentRuntime(@TempDir final Path runtimeDirectory) throws Exception {
        directory = runtimeDirectory;
        final Path project = directory.resolve("project.json");
        Files.writeString(project, CreatorProjects.lowercaseCli(), StandardCharsets.UTF_8);
        final ProcessBuilder builder = RailixPackageIT.instrument(new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar",
                JAR.toString(),
                "application",
                project.toString(),
                TOKEN
        ).redirectErrorStream(true));
        process = builder.start();
        output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        uri = readiness(output);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    void stopPackagedDevelopmentRuntime() throws Exception {
        if (client != null) {
            client.close();
        }
        if (process != null) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
        if (output != null) {
            output.close();
        }
    }

    @Test
    void authenticatedContextRunsThroughThePackagedApplication() throws Exception {
        final HttpResponse<String> response = request("POST", "/v1/run/command", CONTEXT, true, "true");

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        200,
                        "{\"context\":{\"exit_code\":0,\"payload\":{\"arguments\":[\"Hello RAILIX\"]},"
                                + "\"result\":\"hello railix\",\"runtime\":{\"test\":true,"
                                + "\"trigger\":\"command\"}},\"status\":\"succeeded\",\"steps\":["
                                + "{\"id\":\"lowercase-text\",\"outcome\":\"ok\"}]}"
                );
    }

    @Test
    void authenticatedPreviewReturnsTheActualResolvedInputs() throws Exception {
        final HttpResponse<String> response = request(
                "POST",
                "/v1/preview/command/lowercase-text",
                CONTEXT,
                true,
                "true"
        );

        assertThat(response.body()).contains(
                "\"inputs\":{\"value\":\"Hello RAILIX\"}"
        );
    }

    @Test
    void authenticatedPreviewReturnsTheContextBeforeTheSelectedStepRuns() throws Exception {
        final HttpResponse<String> response = request(
                "POST",
                "/v1/preview/command/lowercase-text",
                CONTEXT,
                true,
                "true"
        );

        assertThat(response.body()).contains(
                "\"input_context\":{\"exit_code\":0,\"payload\":{\"arguments\":[\"Hello RAILIX\"]},"
                        + "\"result\":null,\"runtime\":{\"test\":true,\"trigger\":\"command\"}}"
        );
    }

    @Test
    void authenticatedPreviewReturnsTheActualMappedPrimitiveOutput() throws Exception {
        final HttpResponse<String> response = request(
                "POST",
                "/v1/preview/command/lowercase-text",
                CONTEXT,
                true,
                "true"
        );

        assertThat(response.body()).contains(
                "\"result\":\"hello railix\"",
                "\"steps\":[{\"id\":\"lowercase-text\",\"outcome\":\"ok\"}]"
        );
    }

    @Test
    void authenticatedPreviewReturnsCanonicalGraphStepIdentity() throws Exception {
        final HttpResponse<String> response = request(
                "POST",
                "/v1/preview/command/lowercase-text",
                CONTEXT,
                true,
                "true"
        );

        assertThat(response.body()).contains(
                "\"step\":\"lowercase-text\"",
                "\"id\":\"lowercase-text\""
        );
    }

    @Test
    void successfulNestedPreviewStageReturnsItsActualValue() throws Exception {
        final HttpResponse<String> response = isolatedRequest(
                CreatorProjects.nestedLowercase(),
                "/v1/preview/command/lowercase-text",
                "{\"payload\":{\"text\":\"Hello RAILIX\"}}"
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(
                "\"stages\":[{\"input\":\"steps\","
                        + "\"invocation\":\"nodes[2].inputs.steps[0]\","
                        + "\"status\":\"succeeded\",\"use\":\"text.lowercase\","
                        + "\"value\":\"hello railix\"}]"
        );
    }

    @Test
    void previewStopsBeforeTheLaterResultManipulationAndKeepsTheTriggerDefault() throws Exception {
        final HttpResponse<String> response = isolatedRequest(
                CreatorProjects.previewBoundary(),
                "/v1/preview/command/lowercase-text",
                CONTEXT
        );

        assertThat(response.body())
                .contains("\"result\":\"hello railix\"")
                .doesNotContain("\"id\":\"overwrite\"");
    }

    @Test
    void unknownPreviewTargetReturnsCompiledRejection() throws Exception {
        final HttpResponse<String> response = request(
                "POST",
                "/v1/preview/command/missing",
                CONTEXT,
                true,
                "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        422,
                        "{\"diagnostics\":[{\"code\":\"PREVIEW_STEP_UNKNOWN\","
                                + "\"message\":\"Step is not part of the selected Trigger branch: missing.\","
                                + "\"path\":\"step\"}],\"preview\":{\"input_context\":{},\"inputs\":{},"
                                + "\"selected_candidates\":{},\"stages\":[],"
                                + "\"step\":\"missing\"},"
                                + "\"status\":\"rejected\",\"steps\":[]}"
                );
    }

    @Test
    void missingPreviewTokenIsRejected() throws Exception {
        final HttpResponse<String> response = request(
                "POST",
                "/v1/preview/command/lowercase-text",
                CONTEXT,
                false,
                "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(401, "{\"status\":\"unauthorized\"}");
    }

    @Test
    void unsupportedPreviewMethodIsRejected() throws Exception {
        final HttpResponse<String> response = request(
                "GET",
                "/v1/preview/command/lowercase-text",
                "",
                true,
                "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(405, "{\"status\":\"method-not-allowed\"}");
    }

    @Test
    void incompletePreviewPathIsNotFound() throws Exception {
        final HttpResponse<String> response = request(
                "POST",
                "/v1/preview/command",
                CONTEXT,
                true,
                "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(404, "{\"status\":\"not-found\"}");
    }

    @Test
    void nestedPreviewPathIsNotFound() throws Exception {
        final HttpResponse<String> response = request(
                "POST",
                "/v1/preview/command/lowercase-text/nested",
                CONTEXT,
                true,
                "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(404, "{\"status\":\"not-found\"}");
    }

    @Test
    void malformedPreviewContextIsRejectedBeforeExecution() throws Exception {
        final HttpResponse<String> response = request(
                "POST",
                "/v1/preview/command/lowercase-text",
                "{",
                true,
                "true"
        );

        assertThat(response.statusCode()).isEqualTo(422);
    }

    @Test
    void oversizedPreviewResponseIsRejectedBeforeWriting() throws Exception {
        final String body = "{\"payload\":{\"arguments\":[\"" + "x".repeat(400_000) + "\"]}}";

        final HttpResponse<String> response = request(
                "POST",
                "/v1/preview/command/lowercase-text",
                body,
                true,
                "true"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        413,
                        "{\"diagnostics\":[{\"code\":\"PREVIEW_RESPONSE_TOO_LARGE\","
                                + "\"message\":\"Preview response exceeds the 1048576-byte limit.\","
                                + "\"path\":\"\"}],\"status\":\"rejected\"}"
                );
    }

    @Test
    void missingRuntimeTokenIsRejected() throws Exception {
        final HttpResponse<String> response = request("POST", "/v1/run/command", CONTEXT, false, "true");

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(401, "{\"status\":\"unauthorized\"}");
    }

    @Test
    void unsupportedRunMethodIsRejected() throws Exception {
        final HttpResponse<String> response = request("GET", "/v1/run/command", "", true, "true");

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(405, "{\"status\":\"method-not-allowed\"}");
    }

    @Test
    void blankRuntimeTriggerPathIsNotFound() throws Exception {
        final HttpResponse<String> response = request("POST", "/v1/run/", CONTEXT, true, "true");

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(404, "{\"status\":\"not-found\"}");
    }

    @Test
    void nestedRuntimeTriggerPathIsNotFound() throws Exception {
        final HttpResponse<String> response = request("POST", "/v1/run/command/nested", CONTEXT, true, "true");

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(404, "{\"status\":\"not-found\"}");
    }

    @Test
    void oversizedWorkflowContextIsRejectedBeforeParsing() throws Exception {
        final String body = "{\"payload\":{\"text\":\"" + "x".repeat(1_048_577) + "\"}}";

        final HttpResponse<String> response = request("POST", "/v1/run/command", body, true, "true");

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        413,
                        "{\"diagnostics\":[{\"code\":\"CONTEXT_TOO_LARGE\","
                                + "\"message\":\"Workflow context exceeds the 1048576-byte limit.\","
                                + "\"path\":\"\"}],\"status\":\"rejected\"}"
                );
    }

    @Test
    void malformedWorkflowContextIsRejectedBeforeExecution() throws Exception {
        final HttpResponse<String> response = request("POST", "/v1/run/command", "{", true, "true");

        assertThat(response.statusCode()).isEqualTo(422);
    }

    @Test
    void nonObjectWorkflowContextIsRejectedBeforeExecution() throws Exception {
        final HttpResponse<String> response = request("POST", "/v1/run/command", "[]", true, "true");

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        422,
                        "{\"diagnostics\":[{\"code\":\"CONTEXT_OBJECT_REQUIRED\","
                                + "\"message\":\"Workflow context must be an object.\",\"path\":\"\"}],"
                                + "\"status\":\"rejected\"}"
                );
    }

    @Test
    void arbitraryWorkflowContextFieldsArePreserved() throws Exception {
        final HttpResponse<String> response = request(
                "POST",
                "/v1/run/command",
                "{\"payload\":{\"arguments\":[\"Hello\"]},\"custom\":7}",
                true,
                "true"
        );

        assertThat(response.body()).contains("\"custom\":7");
    }

    @Test
    void missingTestHeaderDefaultsToProductionMode() throws Exception {
        final HttpResponse<String> response = request("POST", "/v1/run/command", CONTEXT, true, null);

        assertThat(response.body()).contains("\"test\":false");
    }

    @Test
    void explicitFalseTestHeaderRunsInProductionMode() throws Exception {
        final HttpResponse<String> response = request("POST", "/v1/run/command", CONTEXT, true, "false");

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        200,
                        "{\"context\":{\"exit_code\":0,\"payload\":{\"arguments\":[\"Hello RAILIX\"]},"
                                + "\"result\":\"hello railix\",\"runtime\":{\"test\":false,"
                                + "\"trigger\":\"command\"}},\"status\":\"succeeded\",\"steps\":["
                                + "{\"id\":\"lowercase-text\",\"outcome\":\"ok\"}]}"
                );
    }

    @Test
    void invalidTestHeaderIsRejected() throws Exception {
        final HttpResponse<String> response = request("POST", "/v1/run/command", CONTEXT, true, "yes");

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        422,
                        "{\"diagnostics\":[{\"code\":\"RUN_TEST_FLAG_INVALID\","
                                + "\"message\":\"X-Railix-Test must be true or false.\","
                                + "\"path\":\"X-Railix-Test\"}],\"status\":\"rejected\"}"
                );
    }

    @Test
    void unknownRuntimeTriggerReturnsCompiledRejection() throws Exception {
        final HttpResponse<String> response = request("POST", "/v1/run/missing", CONTEXT, true, "true");

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        422,
                        "{\"diagnostics\":[{\"code\":\"RUN_TRIGGER_UNKNOWN\","
                                + "\"message\":\"Trigger is not part of this project: missing.\","
                                + "\"path\":\"trigger\"}],\"status\":\"rejected\",\"steps\":[]}"
                );
    }

    @Test
    void missingSelectedFieldPreservesTheTargetAndContinuesWithoutNestedInvocation() throws Exception {
        final HttpResponse<String> response = isolatedRequest(
                CreatorProjects.nestedLowercase(),
                "/v1/run/command",
                "{\"payload\":{}}"
        );

        assertThat(response.body()).isEqualTo(
                "{\"context\":{\"exit_code\":0,\"payload\":{},\"result\":null,"
                        + "\"runtime\":{\"test\":true,\"trigger\":\"command\"}},"
                        + "\"status\":\"succeeded\",\"steps\":[{\"id\":\"lowercase-text\","
                        + "\"outcome\":\"next\"},{\"id\":\"return-text\",\"outcome\":\"next\"}]}"
        );
    }

    @Test
    void wrongSelectedFieldTypeIsRejectedBeforeNestedStepInvocation() throws Exception {
        final HttpResponse<String> response = isolatedRequest(
                CreatorProjects.nestedLowercase(),
                "/v1/run/command",
                "{\"payload\":{\"text\":7}}"
        );

        assertThat(response.body()).contains(
                "\"code\":\"RUN_NESTED_INPUT_INCOMPATIBLE\"",
                "\"message\":\"Nested Step text.lowercase requires string but receives number.\"",
                "\"path\":\"nodes[2].inputs.steps[0]\""
        );
    }

    @Test
    void suppliedRuntimeNamespaceIsRejected() throws Exception {
        final HttpResponse<String> response = request(
                "POST",
                "/v1/run/command",
                "{\"payload\":{\"arguments\":[\"Hello\"]},\"runtime\":{}}",
                true,
                "true"
        );

        assertThat(response.body()).contains("\"code\":\"RUN_RUNTIME_RESERVED\"");
    }

    @Test
    void missingDevelopmentProjectMakesApplicationCommandFail() throws Exception {
        final ProcessResult result = runApplication(directory.resolve("missing.json"));

        assertThat(result).isEqualTo(new ProcessResult(
                2,
                "Cannot read development project: " + directory.resolve("missing.json")
        ));
    }

    @Test
    void oversizedDevelopmentProjectMakesApplicationCommandFail() throws Exception {
        final Path project = directory.resolve("oversized.json");
        Files.writeString(project, "x".repeat(1_048_577), StandardCharsets.UTF_8);

        final ProcessResult result = runApplication(project);

        assertThat(result).isEqualTo(new ProcessResult(
                2,
                "Project exceeds the 1048576-byte development limit."
        ));
    }

    @Test
    void invalidDevelopmentProjectMakesApplicationCommandFail() throws Exception {
        final Path project = directory.resolve("invalid.json");
        Files.writeString(project, "{}", StandardCharsets.UTF_8);

        final ProcessResult result = runApplication(project);

        assertThat(result.status()).isEqualTo(2);
    }

    @Test
    void implementationFaultReturnsAnExplicitHttpFailure() throws Exception {
        final StepDefinition fault = StepDefinition.named("example.fault", "1")
                .input("mode", StepDefinition.Input.path(StepDefinition.PathAccess.READ)
                        .defaultPath("context", "payload", "mode"))
                .run(input -> {
                    if (input.string("mode").equals("runtime")) {
                        throw new IllegalStateException("private detail");
                    }
                    return StepResult.outcome(input.primaryOutcome());
                });

        final HttpResponse<String> response = isolatedRequest(
                customStepProject(fault.id()),
                "/v1/run/command",
                "{\"payload\":{\"mode\":\"runtime\"}}",
                fault
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        500,
                        "{\"failure\":{\"code\":\"STEP_IMPLEMENTATION_FAULT\","
                                + "\"message\":\"Step implementation threw an unexpected exception.\","
                                + "\"step\":\"probe\"},\"status\":\"failed\",\"steps\":[]}"
                );
    }

    @Test
    void interruptedStepReturnsAnExplicitHttpCancellation() throws Exception {
        final StepDefinition interrupted = interruptingStep();

        final HttpResponse<String> response = isolatedRequest(
                customStepProject(interrupted.id()),
                "/v1/run/command",
                "{\"payload\":{\"mode\":\"runtime\"}}",
                interrupted
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(409, "{\"status\":\"cancelled\",\"steps\":[]}");
    }

    @Test
    void interruptedPreviewReturnsAnExplicitHttpCancellation() throws Exception {
        final StepDefinition interrupted = interruptingStep();

        final HttpResponse<String> response = isolatedRequest(
                customStepProject(interrupted.id()),
                "/v1/preview/command/probe",
                "{\"payload\":{\"mode\":\"runtime\"}}",
                interrupted
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        409,
                        "{\"preview\":{\"input_context\":{\"exit_code\":0,"
                                + "\"payload\":{\"mode\":\"runtime\"},\"result\":null,"
                                + "\"runtime\":{\"test\":true,\"trigger\":\"command\"}},"
                                + "\"inputs\":{\"mode\":\"runtime\"},"
                                + "\"selected_candidates\":{},\"stages\":[],\"step\":\"probe\"},"
                                + "\"status\":\"cancelled\",\"steps\":[]}"
                );
    }

    @Test
    void falliblePrimitivePreviewOmitsAnOutputValue() throws Exception {
        final HttpResponse<String> response = isolatedRequest(
                CreatorProjects.fallibleNumber(),
                "/v1/preview/command/convert",
                "{\"payload\":{\"value\":\"not-a-number\"}}"
        );

        assertThat(response).extracting(HttpResponse::statusCode, HttpResponse::body)
                .containsExactly(
                        200,
                        "{\"context\":{\"exit_code\":0,\"payload\":{\"value\":\"not-a-number\"},"
                                + "\"result\":null,\"runtime\":{\"test\":true,\"trigger\":\"command\"}},"
                                + "\"preview\":{\"input_context\":{\"exit_code\":0,"
                                + "\"payload\":{\"value\":\"not-a-number\"},\"result\":null,"
                                + "\"runtime\":{\"test\":true,\"trigger\":\"command\"}},"
                                + "\"inputs\":{\"field\":\"not-a-number\","
                                + "\"value\":\"not-a-number\"},\"selected_candidates\":{"
                                + "\"nodes[2].inputs.value\":0},\"stages\":[{\"input\":\"steps\","
                                + "\"invocation\":\"nodes[2].inputs.steps[0]\",\"status\":\"invalid\","
                                + "\"use\":\"text.to-number\"}],\"step\":\"convert\"},"
                                + "\"status\":\"succeeded\",\"steps\":["
                                + "{\"id\":\"text.to-number\",\"outcome\":\"invalid\"},"
                                + "{\"id\":\"convert\",\"outcome\":\"next\"}]}"
                );
    }

    private HttpResponse<String> request(
            final String method,
            final String path,
            final String body,
            final boolean authorized,
            final String test
    ) throws IOException, InterruptedException {
        return request(client, uri, authorized ? TOKEN : "", method, path, body, test);
    }

    private static HttpResponse<String> request(
            final HttpClient client,
            final URI uri,
            final String token,
            final String method,
            final String path,
            final String body,
            final String test
    ) throws IOException, InterruptedException {
        final HttpRequest.Builder request = HttpRequest.newBuilder(uri.resolve(path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        if (!token.isEmpty()) {
            request.header("Authorization", "Bearer " + token);
        }
        if (test != null) {
            request.header("X-Railix-Test", test);
        }
        return client.send(
                request.method(method, body.isEmpty()
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
    }

    private static HttpResponse<String> isolatedRequest(
            final String source,
            final String path,
            final String body,
            final StepDefinition... additional
    ) throws Exception {
        final ArrayList<StepDefinition> definitions = new ArrayList<>(StandardLibrary.catalog().definitions());
        definitions.addAll(java.util.List.of(additional));
        final CompileResult result = ProjectCompiler.compile(
                source,
                StepCatalog.of(definitions.toArray(StepDefinition[]::new))
        );
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        final DevelopmentRuntime.RuntimeServer runtime = DevelopmentRuntime.RuntimeServer.start(
                (CompileResult.Compiled) result,
                TOKEN
        );
        try (HttpClient isolatedClient = HttpClient.newHttpClient()) {
            return request(
                    isolatedClient,
                    URI.create("http://127.0.0.1:" + runtime.port()),
                    TOKEN,
                    "POST",
                    path,
                    body,
                    "true"
            );
        } finally {
            runtime.close();
        }
    }

    private static String customStepProject(final String use) {
        return """
                {"format":1,"id":"runtime-result-proof","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"example","payload":[],"context":{"payload":{"mode":"compile"}}
                  }]},
                  {"id":"probe","use":"%s","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"probe"},
                  {"from":"probe.next","to":"end"}
                ]}
                """.formatted(use);
    }

    private static StepDefinition interruptingStep() {
        return StepDefinition.named("example.interrupted", "1")
                .input("mode", StepDefinition.Input.path(StepDefinition.PathAccess.READ)
                        .defaultPath("context", "payload", "mode"))
                .run(input -> {
                    if (input.string("mode").equals("runtime")) {
                        throw new InterruptedException("stop");
                    }
                    return StepResult.outcome(input.primaryOutcome());
                });
    }

    private static ProcessResult runApplication(final Path project) throws Exception {
        final ProcessBuilder builder = RailixPackageIT.instrument(new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar",
                JAR.toString(),
                "application",
                project.toString(),
                TOKEN
        ).redirectErrorStream(true));
        final Process child = builder.start();
        final String text;
        try (var childOutput = child.inputReader(StandardCharsets.UTF_8)) {
            text = childOutput.lines().collect(java.util.stream.Collectors.joining("\n")).strip();
        }
        assertThat(child.waitFor(15, TimeUnit.SECONDS)).isTrue();
        return new ProcessResult(child.exitValue(), withoutAgentNotice(text));
    }

    private static URI readiness(final BufferedReader output) throws IOException {
        String line;
        while ((line = output.readLine()) != null) {
            if (line.startsWith("RAILIX_READY ")) {
                return URI.create("http://127.0.0.1:" + line.substring("RAILIX_READY ".length()));
            }
        }
        throw new IOException("Packaged development runtime exited before readiness.");
    }

    private static String withoutAgentNotice(final String output) {
        return output.lines()
                .filter(line -> !line.startsWith("Picked up JAVA_TOOL_OPTIONS:"))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private record ProcessResult(int status, String output) {
    }
}
