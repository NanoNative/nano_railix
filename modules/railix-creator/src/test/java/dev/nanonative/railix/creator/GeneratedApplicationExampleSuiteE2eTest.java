package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.ValueShape;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.development.ExampleSuiteTestAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import thirdparty.conformance.ChunkGateStepHandler;
import thirdparty.conformance.DevelopmentRuntimeConformanceSteps;
import thirdparty.conformance.GenericContractSteps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Execution(ExecutionMode.SAME_THREAD)
final class GeneratedApplicationExampleSuiteE2eTest extends CreatorServerE2eSupport {
    private static final int SNAPSHOT_REPLAY_STEPS = 40;
    private static final int SNAPSHOT_GATE_NODE = SNAPSHOT_REPLAY_STEPS + 3;

    @Test
    void examplePayloadCanTargetAnExistingArraySlot() throws Exception {
        assertArrayTargetExample(
                "existing-array",
                "{\"payload\":{\"arguments\":[\"first\"]}}",
                "[\"first\",\"second\"]"
        );
    }

    @Test
    void examplePayloadCreatesAMissingArrayPath() throws Exception {
        assertArrayTargetExample(
                "missing-array",
                "{}",
                "[null,\"second\"]"
        );
    }

    @Test
    void examplePayloadCreatesAFieldInsideAMissingArraySlot() throws Exception {
        final Path workspace = directory.resolve("missing-nested-array-field");
        final Path project = workspace.resolve("railix.project.json");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(nestedArrayTrigger()),
                GenericContractSteps.Trigger.class
        );
        Files.writeString(project, nestedArrayTargetExample(), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            awaitExamples(creator, 1);
            final HttpResponse<String> view = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0/view",
                    ""
            );

            assertThat(view.statusCode()).isEqualTo(200);
            assertThat(view.body()).contains("\"items\":[null,{\"name\":\"second\"}]");
        }
    }

    @Test
    void compiledExampleStepExecutesInsideTheGeneratedApplicationProcess() throws Exception {
        final Path workspace = directory.resolve("application-owned-example");
        final Path project = workspace.resolve("railix.project.json");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(
                        StepDefinition.named("test.process-trigger", "1")
                                .kind(StepDefinition.Kind.TRIGGER)
                                .source("test.process")
                                .run(DevelopmentRuntimeConformanceSteps.Pass.class),
                        StepDefinition.named("test.process-id", "1")
                                .input("target", StepDefinition.Input.path(StepDefinition.PathAccess.WRITE)
                                        .defaultPath("context", "result"))
                                .run(DevelopmentRuntimeConformanceSteps.ProcessId.class)
                ),
                DevelopmentRuntimeConformanceSteps.Pass.class,
                DevelopmentRuntimeConformanceSteps.ProcessId.class
        );
        Files.writeString(project, """
                {"format":1,"id":"application-owned-example","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"test.process-trigger","inputs":{},"examples":[
                    {"name":"process","payload":[],"context":{"payload":{}}}
                  ]},
                  {"id":"process-id","use":"test.process-id","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"process-id"},
                  {"from":"process-id.next","to":"end"}
                ]}
                """, StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            awaitExamples(creator, 1);
            final long applicationPid = CreatorServerE2eSupport.number(application(creator.baseUri()), "pid");
            final HttpResponse<String> view = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0/view",
                    ""
            );

            assertThat(view.statusCode()).isEqualTo(200);
            assertThat(view.body()).contains("\"result\":" + applicationPid);
        }
    }

    @Test
    void automaticExampleStartsAtTriggerOutputWithoutInvokingItsIngressHandler() throws Exception {
        final Path workspace = directory.resolve("trigger-output-example");
        final Path project = workspace.resolve("railix.project.json");
        final Path ingressMarker = workspace.resolve("ingress.marker");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(
                        StepDefinition.named("test.side-effect-trigger", "1")
                                .kind(StepDefinition.Kind.TRIGGER)
                                .source("test.side-effect-source")
                                .input("file", StepDefinition.Input.json(ValueShape.STRING)
                                        .defaultValue(RailixValue.string(ingressMarker.toString())))
                                .run(DevelopmentRuntimeConformanceSteps.Append.class),
                        StepDefinition.named("test.process-id", "1")
                                .input("target", StepDefinition.Input.path(StepDefinition.PathAccess.WRITE)
                                        .defaultPath("context", "result"))
                                .run(DevelopmentRuntimeConformanceSteps.ProcessId.class)
                ),
                DevelopmentRuntimeConformanceSteps.Append.class,
                DevelopmentRuntimeConformanceSteps.ProcessId.class
        );
        Files.writeString(project, """
                {"format":1,"id":"trigger-output-example","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"source","use":"test.side-effect-trigger","inputs":{},"examples":[
                    {"name":"output","payload":{"value":"ready"}}
                  ]},
                  {"id":"process-id","use":"test.process-id","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"source"},
                  {"from":"source.next","to":"process-id"},
                  {"from":"process-id.next","to":"end"}
                ]}
                """, StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            awaitExamples(creator, 1);
            final long applicationPid = CreatorServerE2eSupport.number(application(creator.baseUri()), "pid");
            final HttpResponse<String> view = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/source:0/view",
                    ""
            );

            assertThat(view.statusCode()).isEqualTo(200);
            assertThat(view.body()).contains("\"result\":" + applicationPid);
            assertThat(ingressMarker).doesNotExist();
        }
    }

    @Test
    void examplePayloadRejectsAnIncompatibleTargetParentBeforeBuild() throws Exception {
        final Path workspace = directory.resolve("incompatible-parent");
        final Path project = workspace.resolve("railix.project.json");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(arrayTrigger()),
                GenericContractSteps.Trigger.class
        );
        Files.writeString(project, arrayTargetExample("{\"payload\":\"old\"}"), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> CreatorServer.start(0, project, workspace.resolve("railix-home")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("PROJECT_TRIGGER_EXAMPLE_TARGET_CONFLICT")
                .hasMessageContaining("nodes[1].examples[0].context");
    }

    @Test
    void projectionRejectsANonObjectContextChange() {
        assertContextChangeFailure("null", "Development trace context change is invalid.");
    }

    @Test
    void projectionRejectsAContextChangeWithoutKind() {
        assertContextChangeFailure("{}", "Development trace context change is invalid.");
    }

    @Test
    void projectionRejectsAContextChangeWithoutPath() {
        assertContextChangeFailure(
                "{\"kind\":\"changed\"}",
                "Development trace context change is invalid."
        );
    }

    @Test
    void projectionRejectsATooShortContextChangePath() {
        assertContextChangeFailure(
                "{\"kind\":\"changed\",\"path\":[\"context\"]}",
                "Development trace context change is invalid."
        );
    }

    @Test
    void projectionRejectsANonStringContextChangeRoot() {
        assertContextChangeFailure(
                "{\"kind\":\"changed\",\"path\":[0,\"value\"]}",
                "Development trace context change is invalid."
        );
    }

    @Test
    void projectionRejectsAContextChangeOutsideTheContextRoot() {
        assertContextChangeFailure(
                "{\"kind\":\"changed\",\"path\":[\"payload\",\"value\"]}",
                "Development trace context change is invalid."
        );
    }

    @Test
    void projectionRejectsAnObjectChangeWithoutAResultValue() {
        assertContextChangeFailure(
                "{\"kind\":\"changed\",\"path\":[\"context\",\"value\"]}",
                "Development trace context change has no result value."
        );
    }

    @Test
    void projectionRejectsAnOutOfRangeArrayRemoval() {
        assertContextChangeFailure(
                "{\"kind\":\"removed\",\"path\":[\"context\",\"payload\",1]}",
                "{\"payload\":[1]}",
                "Development trace array removal is invalid."
        );
    }

    @Test
    void projectionRejectsAnArrayChangeWithoutAResultValue() {
        assertContextChangeFailure(
                "{\"kind\":\"changed\",\"path\":[\"context\",\"payload\",0]}",
                "{\"payload\":[1]}",
                "Development trace array change is invalid."
        );
    }

    @Test
    void projectionRejectsAnArrayInsertionBeyondItsEnd() {
        assertContextChangeFailure(
                "{\"kind\":\"added\",\"path\":[\"context\",\"payload\",2],\"after\":2}",
                "{\"payload\":[1]}",
                "Development trace array change is invalid."
        );
    }

    @Test
    void projectionRejectsANumericFieldOnAnObject() {
        assertContextChangeFailure(
                "{\"kind\":\"changed\",\"path\":[\"context\",0],\"after\":1}",
                "Development trace context change path is invalid."
        );
    }

    @Test
    void projectionRejectsAStringFieldOnAnArray() {
        assertContextChangeFailure(
                "{\"kind\":\"changed\",\"path\":[\"context\",\"payload\",\"name\"],\"after\":1}",
                "{\"payload\":[]}",
                "Development trace context change path is invalid."
        );
    }

    @Test
    void projectionRejectsAMissingNestedObject() {
        assertContextChangeFailure(
                "{\"kind\":\"changed\",\"path\":[\"context\",\"payload\",\"missing\",\"name\"],"
                        + "\"after\":1}",
                "{\"payload\":{}}",
                "Development trace context change path is invalid."
        );
    }

    @Test
    void projectionRejectsAnOutOfRangeNestedArraySlot() {
        assertContextChangeFailure(
                "{\"kind\":\"changed\",\"path\":[\"context\",\"payload\",2,\"name\"],\"after\":1}",
                "{\"payload\":[[]]}",
                "Development trace context change path is invalid."
        );
    }

    @Test
    void projectionRejectsANumericTraversalThroughAnObject() {
        assertContextChangeFailure(
                "{\"kind\":\"changed\",\"path\":[\"context\",0,\"name\"],\"after\":1}",
                "Development trace context change path is invalid."
        );
    }

    @Test
    void projectionRejectsANegativeArrayIndex() {
        assertContextChangeFailure(
                "{\"kind\":\"changed\",\"path\":[\"context\",\"payload\",-1],\"after\":1}",
                "{\"payload\":[]}",
                "Development trace array index is invalid."
        );
    }

    @Test
    void projectionRejectsAFractionalArrayIndex() {
        assertContextChangeFailure(
                "{\"kind\":\"changed\",\"path\":[\"context\",\"payload\",0.5],\"after\":1}",
                "{\"payload\":[]}",
                "Development trace array index is invalid."
        );
    }

    @Test
    void projectionRejectsASecondInitialContext() {
        assertProjectionFailure(
                "{\"type\":\"trace\",\"context\":{}}\n{\"type\":\"trace\",\"context\":{}}\n",
                "Development trace initial context is invalid."
        );
    }

    @Test
    void projectionRejectsANonObjectInitialContext() {
        assertProjectionFailure(
                "{\"type\":\"trace\",\"context\":[]}\n",
                "Development trace initial context is invalid."
        );
    }

    @Test
    void selectedStepProjectionRequiresAnInitialContext() {
        assertProjectionFailure(stepStart(), 1, false, "Development trace has no initial context.");
    }

    @Test
    void stepResultProjectionRequiresAnInitialContext() {
        assertProjectionFailure(
                stepStart() + "{\"type\":\"step_result\",\"sequence\":0,\"occurrence\":0,"
                        + "\"id\":\"step\",\"changes\":[]}\n",
                "Development trace has no initial context."
        );
    }

    @Test
    void summaryProjectionRequiresAnInitialContext() {
        assertProjectionFailure(
                "{\"type\":\"result\",\"status\":\"succeeded\",\"context\":{}}\n",
                "Development trace has no initial context."
        );
    }

    @Test
    void nestedStageProjectionHandlesAbsentOptionalFields() throws Exception {
        final String trace = "{\"type\":\"trace\",\"context\":{}}\n"
                + "{\"type\":\"step_start\",\"sequence\":0,\"occurrence\":0,\"id\":\"outer\",\"node\":1}\n"
                + "{\"type\":\"step_start\",\"sequence\":1,\"occurrence\":0,\"id\":\"nested\",\"node\":-1}\n"
                + "{\"type\":\"step_result\",\"sequence\":1,\"occurrence\":0,\"id\":\"nested\","
                + "\"outcome\":\"next\"}\n"
                + "{\"type\":\"step_result\",\"sequence\":0,\"occurrence\":0,\"id\":\"outer\","
                + "\"changes\":[]}\n"
                + "{\"type\":\"result\",\"status\":\"succeeded\",\"context\":{}}\n";
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        ExampleSuiteTestAccess.project(
                new ByteArrayInputStream(trace.getBytes(StandardCharsets.UTF_8)),
                output,
                1,
                false
        );

        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("\"stages\":[{\"input\":\"\",\"invocation\":\"nested\",\"status\":\"next\"}]");
    }

    @Test
    void nestedStageProjectionOmitsAnEmptyReturnObject() throws Exception {
        final String trace = "{\"type\":\"trace\",\"context\":{}}\n"
                + "{\"type\":\"step_start\",\"sequence\":0,\"occurrence\":0,\"id\":\"outer\",\"node\":1}\n"
                + "{\"type\":\"step_start\",\"sequence\":1,\"occurrence\":0,\"id\":\"nested\",\"node\":-1}\n"
                + "{\"type\":\"step_result\",\"sequence\":1,\"occurrence\":0,\"id\":\"nested\","
                + "\"outcome\":\"next\",\"returns\":{}}\n"
                + "{\"type\":\"step_result\",\"sequence\":0,\"occurrence\":0,\"id\":\"outer\","
                + "\"changes\":[]}\n"
                + "{\"type\":\"result\",\"status\":\"succeeded\",\"context\":{}}\n";

        assertThat(project(trace, 1, false))
                .contains("\"stages\":[{\"input\":\"\",\"invocation\":\"nested\",\"status\":\"next\"}]")
                .doesNotContain("\"value\"");
    }

    @Test
    void projectionAppliesAnObjectRemoval() throws Exception {
        assertThat(project(completedChangeTrace(
                "{\"value\":1}",
                "{\"kind\":\"removed\",\"path\":[\"context\",\"value\"]}"
        ), 1, false)).contains("\"context\":{}");
    }

    @Test
    void projectionAppliesAnArrayReplacement() throws Exception {
        assertThat(project(completedChangeTrace(
                "{\"payload\":[1]}",
                "{\"kind\":\"changed\",\"path\":[\"context\",\"payload\",0],\"after\":2}"
        ), 1, false)).contains("\"context\":{\"payload\":[2]}");
    }

    @Test
    void projectionAppliesAnArrayAppend() throws Exception {
        assertThat(project(completedChangeTrace(
                "{\"payload\":[1]}",
                "{\"kind\":\"added\",\"path\":[\"context\",\"payload\",1],\"after\":2}"
        ), 1, false)).contains("\"context\":{\"payload\":[1,2]}");
    }

    @Test
    void projectionAppliesAnArrayRemoval() throws Exception {
        assertThat(project(completedChangeTrace(
                "{\"payload\":[1]}",
                "{\"kind\":\"removed\",\"path\":[\"context\",\"payload\",0]}"
        ), 1, false)).contains("\"context\":{\"payload\":[]}");
    }

    @Test
    void projectionAppliesAChangeThroughAnArraySlot() throws Exception {
        assertThat(project(completedChangeTrace(
                "{\"payload\":[{\"name\":\"before\"}]}",
                "{\"kind\":\"changed\",\"path\":[\"context\",\"payload\",0,\"name\"],"
                        + "\"after\":\"after\"}"
        ), 1, false)).contains("\"context\":{\"payload\":[{\"name\":\"after\"}]}");
    }

    @Test
    void projectionReturnsTheSelectedStepAtATraceError() throws Exception {
        final String trace = "{\"type\":\"trace\",\"context\":{\"payload\":1}}\n"
                + stepStart()
                + "{\"type\":\"trace_error\",\"code\":\"STEP_FAILED\",\"message\":\"broken\","
                + "\"status\":\"failed\"}\n";

        assertThat(project(trace, 1, false))
                .contains("\"code\":\"STEP_FAILED\"")
                .contains("\"message\":\"broken\"")
                .contains("\"input_context\":{\"payload\":1}")
                .contains("\"context\":{\"payload\":1}");
    }

    @Test
    void projectionOmitsAnUnselectedTraceError() throws Exception {
        final String trace = "{\"type\":\"trace\",\"context\":{}}\n"
                + "{\"type\":\"trace_error\",\"message\":\"broken\"}\n";

        assertThat(project(trace, 1, false)).isEmpty();
    }

    @Test
    void summaryProjectionReturnsATraceError() throws Exception {
        final String trace = "{\"type\":\"trace\",\"context\":{\"payload\":1}}\n"
                + "{\"type\":\"trace_error\",\"code\":\"FLOW_FAILED\",\"message\":\"broken\","
                + "\"status\":\"failed\"}\n";

        assertThat(project(trace, -1, true))
                .contains("\"initial_context\":{\"payload\":1}")
                .contains("\"result\":{\"code\":\"FLOW_FAILED\",\"message\":\"broken\","
                        + "\"status\":\"failed\"}");
    }

    @Test
    void projectionRejectsInvalidJson() {
        assertProjectionFailure("{\n", "Development trace contains invalid JSON.");
    }

    @Test
    void projectionRejectsANonObjectEvent() {
        assertProjectionFailure("[]\n", "Development trace contains invalid JSON.");
    }

    @Test
    void projectionRejectsAnUnknownEventType() {
        assertProjectionFailure(
                "{\"type\":\"unknown\"}\n",
                "Development trace event type is invalid."
        );
    }

    @Test
    void projectionRejectsAStepResultWithoutAStart() {
        assertProjectionFailure(
                "{\"type\":\"step_result\",\"sequence\":0,\"occurrence\":0,\"id\":\"step\"}\n",
                "Development trace Step nesting is invalid."
        );
    }

    @Test
    void projectionRejectsAMismatchedStepSequence() {
        assertProjectionFailure(
                stepStart() + "{\"type\":\"step_result\",\"sequence\":1,\"occurrence\":0,\"id\":\"step\"}\n",
                "Development trace Step nesting is invalid."
        );
    }

    @Test
    void projectionRejectsAMismatchedStepOccurrence() {
        assertProjectionFailure(
                stepStart() + "{\"type\":\"step_result\",\"sequence\":0,\"occurrence\":1,\"id\":\"step\"}\n",
                "Development trace Step nesting is invalid."
        );
    }

    @Test
    void projectionRejectsAMismatchedStepIdentifier() {
        assertProjectionFailure(
                stepStart() + "{\"type\":\"step_result\",\"sequence\":0,\"occurrence\":0,\"id\":\"other\"}\n",
                "Development trace Step nesting is invalid."
        );
    }

    @Test
    void projectionRejectsATerminalResultWithAnOpenStep() {
        assertProjectionFailure(
                stepStart() + "{\"type\":\"result\",\"status\":\"succeeded\",\"context\":{}}\n",
                "Development trace completed with open Step records."
        );
    }

    @Test
    void projectionRejectsAMissingOccurrence() {
        assertProjectionFailure(
                "{\"type\":\"step_start\",\"sequence\":0,\"id\":\"step\",\"node\":1}\n",
                "Development trace field 'occurrence' is invalid."
        );
    }

    @Test
    void projectionRejectsAFractionalOccurrence() {
        assertProjectionFailure(
                "{\"type\":\"step_start\",\"sequence\":0,\"occurrence\":0.5,\"id\":\"step\",\"node\":1}\n",
                "Development trace field 'occurrence' is invalid."
        );
    }

    @Test
    void projectionRejectsAMissingSequence() {
        assertProjectionFailure(
                "{\"type\":\"step_start\",\"occurrence\":0,\"id\":\"step\",\"node\":1}\n",
                "Development trace field 'sequence' is invalid."
        );
    }

    @Test
    void projectionRejectsAnOverflowingSequence() {
        assertProjectionFailure(
                "{\"type\":\"step_start\",\"sequence\":9223372036854775808,\"occurrence\":0,\"id\":\"step\",\"node\":1}\n",
                "Development trace field 'sequence' is invalid."
        );
    }

    @Test
    void projectionStopsWhenItsReaderThreadIsInterrupted() {
        Thread.currentThread().interrupt();
        try {
            assertProjectionFailure(
                    "{\"type\":\"trace\",\"context\":{}}\n",
                    "Example view was interrupted."
            );
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void projectionDoesNotExposeACompletePrefixFromATruncatedTrace() throws Exception {
        final String trace = "{\"type\":\"trace\",\"context\":{}}\n";
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        ExampleSuiteTestAccess.project(
                new ByteArrayInputStream(trace.getBytes(StandardCharsets.UTF_8)),
                output,
                -1,
                false
        );

        assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void selectedStepProjectionIgnoresAnEmptyDiffFromAnotherStep() throws Exception {
        final String trace = "{\"type\":\"trace\",\"context\":{}}\n"
                + stepStart()
                + "{\"type\":\"step_result\",\"sequence\":0,\"occurrence\":0,"
                + "\"id\":\"step\",\"changes\":[]}\n";
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        ExampleSuiteTestAccess.project(
                new ByteArrayInputStream(trace.getBytes(StandardCharsets.UTF_8)),
                output,
                2,
                false
        );

        assertThat(output.toString(StandardCharsets.UTF_8))
                .isEmpty();
    }

    @Test
    void exampleProjectionRejectsMutation() throws Exception {
        assertCompletedExampleRoute("POST", "/api/examples/command:0/view", 405);
    }

    @Test
    void exampleProjectionRejectsAnUnknownExampleIdentifier() throws Exception {
        assertCompletedExampleRoute("GET", "/api/examples/unknown/view", 404);
    }

    @Test
    void exampleProjectionRejectsAMalformedExampleIdentifier() throws Exception {
        assertCompletedExampleRoute("GET", "/api/examples/%FF/view", 404);
    }

    @Test
    void exampleProjectionRejectsAnotherUnknownExampleIdentifier() throws Exception {
        assertCompletedExampleRoute("GET", "/api/examples/command:1/view", 404);
    }

    @Test
    void exampleProjectionRejectsANonNumericStepIndex() throws Exception {
        assertCompletedExampleRoute("GET", "/api/examples/command:0/steps/not-a-number", 404);
    }

    @Test
    void exampleProjectionRejectsANegativeStepIndex() throws Exception {
        assertCompletedExampleRoute("GET", "/api/examples/command:0/steps/-1", 404);
    }

    @Test
    void exampleProjectionRejectsAnOutOfRangeStepIndex() throws Exception {
        assertCompletedExampleRoute("GET", "/api/examples/command:0/steps/2", 404);
    }

    @Test
    void exampleProjectionRejectsAStepRouteWithoutAnIndex() throws Exception {
        assertCompletedExampleRoute("GET", "/api/examples/command:0/steps", 404);
    }

    @Test
    void exampleProjectionRejectsAnEmptyStepIndex() throws Exception {
        assertCompletedExampleRoute("GET", "/api/examples/command:0/steps/", 404);
    }

    @Test
    void aggregateExampleProjectionRejectsANonNumericStepIndex() throws Exception {
        assertCompletedExampleRoute("GET", "/api/examples/steps/not-a-number", 404);
    }

    @Test
    void aggregateExampleProjectionRejectsANegativeStepIndex() throws Exception {
        assertCompletedExampleRoute("GET", "/api/examples/steps/-1", 404);
    }

    @Test
    void aggregateExampleProjectionRejectsAnOutOfRangeStepIndex() throws Exception {
        assertCompletedExampleRoute("GET", "/api/examples/steps/2", 404);
    }

    @Test
    void aggregateExampleProjectionRejectsAnEmptyStepIndex() throws Exception {
        assertCompletedExampleRoute("GET", "/api/examples/steps/", 404);
    }

    @Test
    void runningExampleCaseStatusIsReadAsOneCoherentApplicationSnapshot() throws Exception {
        final Path workspace = directory.resolve("running-case-status");
        final Path project = workspace.resolve("railix.project.json");
        final Path gate = workspace.resolve("gate");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(chunkGateStep()),
                ChunkGateStepHandler.class
        );
        Files.writeString(project, gatedExamples(gate), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            awaitStarted(gate, ExampleSuiteTestAccess.CHUNK_SIZE);

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0",
                    ""
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("\"id\":\"command:0\"")
                    .contains("\"status\":\"running\"")
                    .contains("\"events\":2")
                    .doesNotContain("\"cases\"");

            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
            awaitExamples(creator, ExampleSuiteTestAccess.CHUNK_SIZE + 1L);
        } finally {
            Files.createDirectories(gate);
            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
        }
    }

    @Test
    void exampleProjectionDoesNotExposeAPartialRunningTrace() throws Exception {
        final Path workspace = directory.resolve("partial-trace");
        final Path project = workspace.resolve("railix.project.json");
        final Path gate = workspace.resolve("gate");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(chunkGateStep()),
                ChunkGateStepHandler.class
        );
        Files.writeString(project, gatedExamples(gate), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            awaitStarted(gate, ExampleSuiteTestAccess.CHUNK_SIZE);

            assertThat(request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0/view",
                    ""
            ).statusCode()).isEqualTo(404);

            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
            awaitExamples(creator, ExampleSuiteTestAccess.CHUNK_SIZE + 1L);
        } finally {
            Files.createDirectories(gate);
            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
        }
    }

    @Test
    void automaticExampleTraceRemainsBufferedUntilTheExampleCompletes() throws Exception {
        final Path workspace = directory.resolve("buffered-trace");
        final Path project = workspace.resolve("railix.project.json");
        final Path gate = workspace.resolve("gate");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(chunkGateStep()),
                ChunkGateStepHandler.class
        );
        Files.writeString(project, gatedExamples(gate), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            awaitStarted(gate, ExampleSuiteTestAccess.CHUNK_SIZE);
            final Path trace = runtimeDirectory(creator.baseUri()).resolve("0.ndjson");
            final RailixValue.ObjectValue running = object(request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0",
                    ""
            ).body());

            assertThat(trace).isRegularFile();
            assertThat(Files.size(trace)).isZero();
            assertThat(CreatorServerE2eSupport.string(running, "status")).isEqualTo("running");
            assertThat(CreatorServerE2eSupport.number(running, "events")).isEqualTo(2);
            assertThat(CreatorServerE2eSupport.number(running, "storage_bytes")).isPositive();

            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
            awaitExamples(creator, ExampleSuiteTestAccess.CHUNK_SIZE + 1L);
            final RailixValue.ObjectValue completed = object(request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0",
                    ""
            ).body());
            final List<RailixValue.ObjectValue> events = Files.readAllLines(trace, StandardCharsets.UTF_8).stream()
                    .map(CreatorServerE2eSupport::object)
                    .toList();

            assertThat(Files.size(trace))
                    .isEqualTo(CreatorServerE2eSupport.number(completed, "storage_bytes"));
            assertThat(events).hasSize((int) CreatorServerE2eSupport.number(completed, "events"));
            assertThat(events.getLast().values())
                    .containsEntry("type", RailixValue.string("result"))
                    .containsEntry("status", RailixValue.string("succeeded"));
        } finally {
            Files.createDirectories(gate);
            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
        }
    }

    @Test
    void aggregateStepProjectionUsesOneCompletedCaseSnapshot() throws Exception {
        final Path workspace = directory.resolve("aggregate-running-snapshot");
        final Path project = workspace.resolve("railix.project.json");
        final Path gate = workspace.resolve("gate");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(chunkGateStep()),
                ChunkGateStepHandler.class
        );
        Files.writeString(project, gatedExamples(gate), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            awaitStarted(gate, ExampleSuiteTestAccess.CHUNK_SIZE);

            final HttpResponse<String> running = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/steps/2",
                    ""
            );
            assertThat(running.statusCode()).isEqualTo(200);
            assertThat(running.body())
                    .contains("\"status\":\"running\"")
                    .contains("\"status\":\"queued\"")
                    .doesNotContain("\"projection\"");

            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
            awaitExamples(creator, ExampleSuiteTestAccess.CHUNK_SIZE + 1L);

            final HttpResponse<String> completed = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/steps/2",
                    ""
            );
            assertThat(completed.statusCode()).isEqualTo(200);
            assertThat(completed.body())
                    .contains("\"status\":\"succeeded\"")
                    .contains("\"projection\":{");
        } finally {
            Files.createDirectories(gate);
            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
        }
    }

    @Test
    void aggregateStepProjectionRemainsFrozenWhenACaseFinishesAfterItsSnapshot() throws Exception {
        final Path workspace = directory.resolve("aggregate-replay-snapshot");
        final Path project = workspace.resolve("railix.project.json");
        final Path gate = workspace.resolve("gate");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(
                        trigger("test.trigger.snapshot-large", "test.source.snapshot-large"),
                        trigger("test.trigger.snapshot-gate", "test.source.snapshot-gate"),
                        chunkGateStep()
                ),
                GenericContractSteps.Trigger.class,
                ChunkGateStepHandler.class
        );
        Files.writeString(project, snapshotRaceExamples(gate), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"));
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            awaitSnapshotRaceReady(creator, gate);
            final String path = "/api/examples/steps/" + SNAPSHOT_GATE_NODE;
            final ExecutorCompletionService<HttpResponse<String>> completions =
                    new ExecutorCompletionService<>(executor);
            final Future<HttpResponse<String>> first = completions.submit(() -> request(
                    creator.baseUri(),
                    "GET",
                    path,
                    ""
            ));
            final Future<HttpResponse<String>> second = completions.submit(() -> request(
                    creator.baseUri(),
                    "GET",
                    path,
                    ""
            ));

            final Future<HttpResponse<String>> rejected = completions.take();
            final HttpResponse<String> saturated = rejected.get(60, TimeUnit.SECONDS);
            final Future<HttpResponse<String>> aggregate = rejected == first ? second : first;
            assertThat(saturated.statusCode()).isEqualTo(503);
            assertThat(saturated.body()).isEqualTo("{\"reason\":\"saturated\",\"status\":\"unavailable\"}");
            assertThat(aggregate.isDone()).isFalse();
            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
            awaitExampleStatusWhilePending(creator, aggregate, "gate:0", "succeeded");

            final HttpResponse<String> frozen = aggregate.get(60, TimeUnit.SECONDS);
            final RailixValue.ObjectValue frozenGate = exampleCase(object(frozen.body()), "gate:0");
            assertThat(frozen.statusCode()).isEqualTo(200);
            assertThat(CreatorServerE2eSupport.string(frozenGate, "status")).isEqualTo("running");
            assertThat(frozenGate.values()).doesNotContainKey("projection");

            awaitExamples(creator, 2, Duration.ofSeconds(60));
            final RailixValue.ObjectValue completedGate = exampleCase(object(request(
                    creator.baseUri(),
                    "GET",
                    path,
                    ""
            ).body()), "gate:0");
            assertThat(CreatorServerE2eSupport.string(completedGate, "status")).isEqualTo("succeeded");
            assertThat(completedGate.values()).containsKey("projection");
        } finally {
            Files.createDirectories(gate);
            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
        }
    }

    @Test
    void sequentialStepProjectionsObserveFreshRunningFieldsWithinOneRevision() throws Exception {
        final Path workspace = directory.resolve("fresh-running-projection");
        final Path project = workspace.resolve("railix.project.json");
        final Path firstGate = workspace.resolve("first-gate");
        final Path secondGate = workspace.resolve("second-gate");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(chunkGateStep()),
                ChunkGateStepHandler.class
        );
        Files.writeString(project, sequentialGatedExample(firstGate, secondGate), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            awaitStarted(firstGate, 1);
            final String path = "/api/examples/steps/3";
            final RailixValue.ObjectValue before = object(request(creator.baseUri(), "GET", path, "").body());
            final RailixValue.ObjectValue beforeCase = exampleCase(before, "command:0");

            release(firstGate);
            awaitStarted(secondGate, 1);
            final RailixValue.ObjectValue after = object(request(creator.baseUri(), "GET", path, "").body());
            final RailixValue.ObjectValue afterCase = exampleCase(after, "command:0");

            assertThat(CreatorServerE2eSupport.number(after, "revision"))
                    .isEqualTo(CreatorServerE2eSupport.number(before, "revision"));
            assertThat(CreatorServerE2eSupport.string(beforeCase, "status")).isEqualTo("running");
            assertThat(CreatorServerE2eSupport.string(afterCase, "status")).isEqualTo("running");
            assertThat(CreatorServerE2eSupport.number(afterCase, "events"))
                    .isGreaterThan(CreatorServerE2eSupport.number(beforeCase, "events"));
            assertThat(CreatorServerE2eSupport.number(afterCase, "storage_bytes"))
                    .isGreaterThan(CreatorServerE2eSupport.number(beforeCase, "storage_bytes"));

            release(secondGate);
            awaitExamples(creator, 1);
        } finally {
            release(firstGate);
            release(secondGate);
        }
    }

    @Test
    void replacingAChunkedRunningSuiteCancelsItBeforeStartingTheAcceptedExamples() throws Exception {
        final Path workspace = directory.resolve("replace-running-suite");
        final Path project = workspace.resolve("railix.project.json");
        final Path gate = workspace.resolve("gate");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(chunkGateStep()),
                ChunkGateStepHandler.class
        );
        Files.writeString(project, gatedExamples(gate), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            awaitStarted(gate, ExampleSuiteTestAccess.CHUNK_SIZE);
            final Path previousRuntime = runtimeDirectory(creator.baseUri());

            final HttpResponse<String> replacement = request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    largeExample("replacement")
            );

            assertThat(replacement.statusCode()).isEqualTo(200);
            awaitExample(creator, "replacement");
            assertThat(Files.exists(gate.resolve("case-17.started"))).isFalse();
            assertThat(previousRuntime).doesNotExist();
            assertThat(runtimeDirectory(creator.baseUri()).resolve("0.ndjson")).isRegularFile();
        }
    }

    @Test
    void reusedApplicationPersistenceFailureKeepsItsRunningProcessAndAcceptedExamples() throws Exception {
        final Path project = directory.resolve("reused-persistence.json");
        Files.writeString(project, namedExample("accepted"), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            awaitExample(creator, "accepted");
            final long pid = CreatorServerE2eSupport.number(application(creator.baseUri()), "pid");
            Files.delete(project);
            Files.createDirectory(project);

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    namedExample("not-persisted")
            );

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).contains("\"message\":\"Project could not be persisted.\"");
            assertThat(CreatorServerE2eSupport.number(application(creator.baseUri()), "pid")).isEqualTo(pid);
            assertThat(request(creator.baseUri(), "GET", "/api/examples/command:0/view", "").body())
                    .contains("\"accepted\"");
        }
    }

    @Test
    void unpersistedRollingApplicationNeverExecutesItsExamples() throws Exception {
        final Path workspace = directory.resolve("unpersisted-candidate");
        final Path project = workspace.resolve("railix.project.json");
        final Path marker = workspace.resolve("candidate.marker");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(markerStep(marker)),
                DevelopmentRuntimeConformanceSteps.Append.class
        );
        Files.writeString(project, namedExample("accepted"), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            awaitExample(creator, "accepted");
            final RailixValue.ObjectValue accepted = application(creator.baseUri());
            final long acceptedPid = CreatorServerE2eSupport.number(accepted, "pid");
            final Path acceptedArtifact = Path.of(CreatorServerE2eSupport.string(accepted, "build_path"))
                    .getParent();
            final Path buildRoot = acceptedArtifact.getParent();
            Files.delete(project);
            Files.createDirectory(project);

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    markerExample(marker)
            );

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).contains("\"message\":\"Project could not be persisted.\"");
            awaitOnlyRuntime(buildRoot, acceptedPid);
            assertThat(marker).doesNotExist();
            assertThat(CreatorServerE2eSupport.number(application(creator.baseUri()), "pid"))
                    .isEqualTo(acceptedPid);
            try (var builds = Files.list(buildRoot)) {
                assertThat(builds.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith("sha256-"))
                        .toList()).containsExactly(acceptedArtifact);
            }
            assertThat(request(creator.baseUri(), "GET", "/api/examples/command:0/view", "").body())
                    .contains("\"accepted\"");
        }
    }

    @Test
    void supersededRollingApplicationNeverExecutesItsExamples() throws Exception {
        final Path workspace = directory.resolve("superseded-candidate");
        final Path project = workspace.resolve("railix.project.json");
        final Path marker = workspace.resolve("candidate.marker");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(markerStep(marker)),
                DevelopmentRuntimeConformanceSteps.Append.class
        );
        Files.writeString(project, namedExample("accepted"), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"));
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            awaitExample(creator, "accepted");
            final Path buildRoot = Path.of(CreatorServerE2eSupport.string(
                    application(creator.baseUri()),
                    "build_path"
            )).getParent().getParent();
            final Future<HttpResponse<String>> candidate;
            synchronized (ApplicationBuilder.class) {
                candidate = executor.submit(() -> request(
                        creator.baseUri(),
                        "POST",
                        "/api/project",
                        markerExample(marker)
                ));
                awaitDeploymentPending(creator.baseUri());
                final HttpResponse<String> newerRevision = request(
                        creator.baseUri(),
                        "POST",
                        "/api/project",
                        "{"
                );
                assertThat(newerRevision.statusCode()).isEqualTo(422);
            }

            final HttpResponse<String> superseded = candidate.get(60, TimeUnit.SECONDS);
            assertThat(superseded.body()).contains("\"status\":\"superseded\"");
            assertThat(superseded.statusCode()).isEqualTo(409);
            final HttpResponse<String> winner = request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    namedExample("winner")
            );
            assertThat(winner.statusCode()).isEqualTo(200);
            assertThat(winner.body()).contains("\"name\":\"winner\"");
            awaitExample(creator, "winner");
            awaitOnlyRuntime(
                    buildRoot,
                    CreatorServerE2eSupport.number(application(creator.baseUri()), "pid")
            );
            assertThat(marker).doesNotExist();
        }
    }

    @Test
    void triggerWithoutAnExplicitExampleTargetUsesContextPayload() throws Exception {
        try (CreatorServer creator = startWithoutInjectableExamples("missing-example-target")) {
            final RailixValue.ObjectValue application = application(creator.baseUri());
            final RailixValue.ObjectValue examples = awaitExample(creator, "default-payload");

            assertThat(CreatorServerE2eSupport.string(application, "state")).isEqualTo("running");
            assertThat(CreatorServerE2eSupport.string(examples, "state")).isEqualTo("completed");
            assertThat(CreatorServerE2eSupport.number(examples, "succeeded")).isEqualTo(1);
        }
    }

    @Test
    void rollingBuildAppliesTheDefaultContextPayloadExampleTarget() throws Exception {
        final Path workspace = directory.resolve("rolling-missing-example-target");
        final Path project = workspace.resolve("railix.project.json");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(
                        trigger("test.trigger.example-target", "test.source.example-target"),
                        triggerWithoutExampleTarget()
                ),
                GenericContractSteps.Trigger.class
        );
        Files.writeString(project, injectableExampleTargetProject(), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            awaitExample(creator, "injectable");
            final RailixValue.ObjectValue before = application(creator.baseUri());

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    missingExampleTargetProject()
            );
            final RailixValue.ObjectValue after = application(creator.baseUri());
            final RailixValue.ObjectValue examples = awaitExample(creator, "default-payload");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(CreatorServerE2eSupport.string(after, "state")).isEqualTo("running");
            assertThat(CreatorServerE2eSupport.number(after, "pid"))
                    .isNotEqualTo(CreatorServerE2eSupport.number(before, "pid"));
            assertThat(CreatorServerE2eSupport.string(after, "fingerprint"))
                    .isNotEqualTo(CreatorServerE2eSupport.string(before, "fingerprint"));
            assertThat(CreatorServerE2eSupport.string(examples, "state")).isEqualTo("completed");
            assertThat(CreatorServerE2eSupport.number(examples, "succeeded")).isEqualTo(1);
            assertThat(request(creator.baseUri(), "GET", "/api/examples/command:0/view", "").statusCode())
                    .isEqualTo(200);
        }
    }

    @Test
    void defaultExampleTargetExposesItsCompletedProjection() throws Exception {
        try (CreatorServer creator = startWithoutInjectableExamples("failed-example-projection")) {
            awaitExample(creator, "default-payload");
            assertThat(request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0/view",
                    ""
            ).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void sparseExampleTargetIsRejectedBeforeAnApplicationStarts() throws Exception {
        final Path project = directory.resolve("oversized-materialized-context.json");
        Files.writeString(project, oversizedMaterializedContext(), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> start(project))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("PROJECT_TRIGGER_EXAMPLE_TARGET_SPARSE")
                .hasMessageContaining("nodes[1].examples[0].context");
    }

    @Test
    void generatedApplicationRunsEveryExampleAndCreatorProxiesTheSelectedStepView() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, twoExamples(), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue examples = awaitExamples(creator, 2);
            final RailixValue.ArrayValue cases = (RailixValue.ArrayValue) examples.values().get("cases");
            final long applicationPid = CreatorServerE2eSupport.number(application(creator.baseUri()), "pid");

            assertThat(CreatorServerE2eSupport.string(examples, "state")).isEqualTo("completed");
            assertThat(CreatorServerE2eSupport.number(examples, "completed")).isEqualTo(2);
            assertThat(CreatorServerE2eSupport.number(examples, "succeeded")).isEqualTo(2);
            assertThat(CreatorServerE2eSupport.number(examples, "covered_steps")).isEqualTo(2);
            assertThat(cases.values()).extracting(value -> CreatorServerE2eSupport.string(
                    (RailixValue.ObjectValue) value,
                    "name"
            )).containsExactly("mixed-case", "upper-case");

            final HttpResponse<String> first = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0/steps/2",
                    ""
            );
            final HttpResponse<String> second = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:1/view",
                    ""
            );
            final HttpResponse<String> aggregate = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/steps/2",
                    ""
            );

            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(first.headers().firstValue("Content-Type"))
                    .contains("application/json; charset=utf-8");
            assertThat(first.body())
                    .contains("\"application_pid\":" + applicationPid)
                    .contains("\"payload\":{\"arguments\":[\"Hello RAILIX\"]}")
                    .contains("\"id\":\"lowercase-text\"")
                    .contains("\"returns\":{\"value\":\"hello railix\"}")
                    .doesNotContain("\"type\"")
                    .doesNotContain("duration");
            assertThat(second.body())
                    .contains("\"application_pid\":" + applicationPid)
                    .contains("\"payload\":{\"arguments\":[\"SECOND\"]}")
                    .contains("\"result\":\"second\"")
                    .doesNotContain("\"type\":\"step_result\"")
                    .doesNotContain("duration");
            assertThat(aggregate.statusCode()).isEqualTo(200);
            assertThat(aggregate.body())
                    .contains("\"application_pid\":" + applicationPid)
                    .contains("\"node\":2")
                    .contains("\"id\":\"command:0\"")
                    .contains("\"id\":\"command:1\"")
                    .contains("\"initial_context\"")
                    .contains("\"projection\":{")
                    .contains("\"id\":\"lowercase-text\"")
                    .doesNotContain("\"type\"")
                    .doesNotContain("duration");
        }
    }

    @Test
    void summaryProjectionEndsWithTheRealResultAfterANestedChoiceMatcher() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, choiceExample(), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            awaitExamples(creator, 2);
            final String persisted = Files.readString(
                    runtimeDirectory(creator.baseUri()).resolve("0.ndjson"),
                    StandardCharsets.UTF_8
            );
            assertThat(persisted).contains("\"type\":\"result\"");

            final HttpResponse<String> matched = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0/view",
                    ""
            );
            final HttpResponse<String> otherwise = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:1/view",
                    ""
            );
            final HttpResponse<String> choice = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/steps/2",
                    ""
            );

            assertThat(matched.statusCode()).isEqualTo(200);
            assertThat(matched.body()).as("Persisted trace:%n%s", persisted)
                    .doesNotContain("\"type\"")
                    .contains("\"result\":\"matched\"");
            assertThat(otherwise.statusCode()).isEqualTo(200);
            assertThat(otherwise.body())
                    .doesNotContain("\"type\"")
                    .contains("\"result\":\"otherwise\"");
            assertThat(choice.statusCode()).isEqualTo(200);
            assertThat(choice.body())
                    .contains("\"id\":\"choice\"")
                    .contains("\"input\":\"conditions[0][0].when.all[0]\"")
                    .contains("\"value\":true")
                    .contains("\"value\":false")
                    .doesNotContain("\"type\"");
        }
    }

    @Test
    void successfulResultLargerThanTheTerminalReservationIsPreserved() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, largeExample("large-result"), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue examples = awaitExamples(creator, 1);
            final HttpResponse<String> view = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0/view",
                    ""
            );

            assertThat(CreatorServerE2eSupport.number(examples, "succeeded")).isEqualTo(1);
            assertThat(view.statusCode()).isEqualTo(200);
            assertThat(view.body().getBytes(StandardCharsets.UTF_8).length).isGreaterThan(512);
            assertThat(view.body())
                    .doesNotContain("\"type\"")
                    .contains("\"status\":\"succeeded\"");
        }
    }

    @Test
    void generatedApplicationRunsExamplesInCompleteExecutionChunks() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, examples(ExampleSuiteTestAccess.CHUNK_SIZE + 1), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            final RailixValue.ObjectValue examples = awaitExamples(creator, ExampleSuiteTestAccess.CHUNK_SIZE + 1L);
            final RailixValue.ArrayValue cases = (RailixValue.ArrayValue) examples.values().get("cases");

            assertThat(CreatorServerE2eSupport.string(examples, "state")).isEqualTo("completed");
            assertThat(CreatorServerE2eSupport.number(examples, "succeeded"))
                    .isEqualTo(ExampleSuiteTestAccess.CHUNK_SIZE + 1L);
            assertThat(cases.values()).hasSize(ExampleSuiteTestAccess.CHUNK_SIZE + 1);
            assertThat(CreatorServerE2eSupport.string(
                    (RailixValue.ObjectValue) cases.values().getLast(),
                    "name"
            )).isEqualTo("case-17");
        }
    }

    @Test
    void exampleAfterTheFirstChunkCannotEnterUntilTheFirstSixteenFinish() throws Exception {
        final Path workspace = directory.resolve("chunk-admission");
        final Path project = workspace.resolve("railix.project.json");
        final Path gate = workspace.resolve("gate");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(chunkGateStep()),
                ChunkGateStepHandler.class
        );
        Files.writeString(project, gatedExamples(gate), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            awaitStarted(gate, ExampleSuiteTestAccess.CHUNK_SIZE);

            assertThat(Files.exists(gate.resolve("case-17.started"))).isFalse();

            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
            final RailixValue.ObjectValue examples = awaitExamples(creator, ExampleSuiteTestAccess.CHUNK_SIZE + 1L);
            assertThat(CreatorServerE2eSupport.number(examples, "succeeded"))
                    .isEqualTo(ExampleSuiteTestAccess.CHUNK_SIZE + 1L);
            assertThat(Files.readString(gate.resolve("case-17.started"), StandardCharsets.UTF_8))
                    .isEqualTo("started");
        }
    }

    @Test
    void creatorStreamsAnApplicationOwnedExampleSummaryBeyondOneMebibyte() throws Exception {
        final int count = 7_000;
        final Path workspace = directory.resolve("large-example-summary");
        final Path project = workspace.resolve("railix.project.json");
        final Path gate = workspace.resolve("gate");
        final String source = manyExamples(count);
        Files.createDirectories(workspace);
        assertThat(source.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1_048_576);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(summaryGateStep(gate)),
                ChunkGateStepHandler.class
        );
        Files.writeString(project, source, StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            awaitStarted(gate, ExampleSuiteTestAccess.CHUNK_SIZE);

            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples",
                    ""
            );

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body().getBytes(StandardCharsets.UTF_8).length).isGreaterThan(1_048_576);
            assertThat(response.body())
                    .contains("\"total\":" + count)
                    .contains("\"id\":\"command:0\"")
                    .contains("\"id\":\"command:" + (count - 1) + "\"");
        }
    }

    @Test
    void automaticCoverageBitsIdentifyEachReachedStepAcrossMultipleGenericTriggers() throws Exception {
        final Path workspace = directory.resolve("multi-trigger-coverage");
        final Path project = workspace.resolve("railix.project.json");
        final StepDefinition first = trigger("test.trigger.first", "test.source.first");
        final StepDefinition second = trigger("test.trigger.second", "test.source.second");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(first, second),
                GenericContractSteps.Trigger.class
        );
        Files.writeString(project, multiTriggerExamples(), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            final RailixValue.ObjectValue examples = awaitExamples(creator, 2);
            final RailixValue.ArrayValue cases = (RailixValue.ArrayValue) examples.values().get("cases");
            final BitSet coverage = BitSet.valueOf(Base64.getDecoder().decode(
                    CreatorServerE2eSupport.string(examples, "coverage_bits")
            ));

            assertThat(cases.values()).extracting(value -> CreatorServerE2eSupport.string(
                    (RailixValue.ObjectValue) value,
                    "trigger"
            )).containsExactly("first", "second");
            assertThat(coverage.stream().boxed()).containsExactly(1, 2, 3, 4);
        }
    }

    @Test
    void oversizedTraceEventIsStoredAsADeterministicExampleFailure() throws Exception {
        final Path workspace = directory.resolve("oversized-trace-event");
        final Path project = workspace.resolve("railix.project.json");
        final Path downstream = workspace.resolve("downstream.marker");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(oversizedTraceStep(), markerStep(downstream)),
                DevelopmentRuntimeConformanceSteps.OversizedTrace.class,
                DevelopmentRuntimeConformanceSteps.Append.class
        );
        Files.writeString(project, oversizedTraceEventExample(), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            final RailixValue.ObjectValue examples = awaitExamples(creator, 1);
            final RailixValue.ObjectValue example = (RailixValue.ObjectValue) ((RailixValue.ArrayValue)
                    examples.values().get("cases")).values().getFirst();
            final HttpResponse<String> view = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0/view",
                    ""
            );

            assertThat(CreatorServerE2eSupport.string(example, "status")).isEqualTo("failed");
            assertThat(CreatorServerE2eSupport.string(example, "message"))
                    .isEqualTo("One development trace event exceeded 4194304 bytes.");
            assertThat(view.body()).contains("\"code\":\"TRACE_EVENT_TOO_LARGE\"");
            assertThat(Files.readString(downstream, StandardCharsets.UTF_8)).isEqualTo("x");
        }
    }

    @Test
    void observationFailureDoesNotSuppressTheExampleExecutionTimeout() throws Exception {
        final Path workspace = directory.resolve("trace-failure-timeout");
        final Path project = workspace.resolve("railix.project.json");
        final Path gate = workspace.resolve("gate");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(oversizedTraceStep(), chunkGateStep()),
                DevelopmentRuntimeConformanceSteps.OversizedTrace.class,
                ChunkGateStepHandler.class
        );
        Files.writeString(project, oversizedTraceThenGateExample(gate), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            awaitStarted(gate, 1);
            final RailixValue.ObjectValue examples = awaitExamples(creator, 1, Duration.ofSeconds(40));
            final RailixValue.ObjectValue example = (RailixValue.ObjectValue) ((RailixValue.ArrayValue)
                    examples.values().get("cases")).values().getFirst();
            final HttpResponse<String> view = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0/view",
                    ""
            );

            assertThat(CreatorServerE2eSupport.string(example, "status")).isEqualTo("timed-out");
            assertThat(CreatorServerE2eSupport.string(example, "message"))
                    .isEqualTo("Example exceeded the 30-second execution limit.");
            assertThat(view.body()).contains("\"code\":\"TRACE_EVENT_TOO_LARGE\"");
        } finally {
            Files.createDirectories(gate);
            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
        }
    }

    @Test
    void rollingApplicationDeletesADeadProcessRuntimeDirectory() throws Exception {
        final Path project = directory.resolve("dead-runtime.json");
        Files.writeString(project, namedExample("original"), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            awaitExample(creator, "original");
            final Path runtimeRoot = runtimeDirectory(creator.baseUri()).getParent();
            final Path dead = runtimeRoot.resolve(Long.toString(Long.MAX_VALUE));
            Files.createDirectories(dead);
            Files.writeString(dead.resolve("orphan.ndjson"), "orphan", StandardCharsets.UTF_8);

            assertThat(request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    namedExample("replacement")
            ).statusCode()).isEqualTo(200);
            awaitExample(creator, "replacement");

            assertThat(dead).doesNotExist();
        }
    }

    @Test
    void childErrorStillFinalizesTheExampleAsFailed() throws Exception {
        try (CreatorServer creator = startFatalExample("fatal-example")) {
            final RailixValue.ObjectValue examples = awaitExamples(creator, 1);
            final RailixValue.ObjectValue example = (RailixValue.ObjectValue) ((RailixValue.ArrayValue)
                    examples.values().get("cases")).values().getFirst();
            final HttpResponse<String> view = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0/view",
                    ""
            );

            assertThat(CreatorServerE2eSupport.string(examples, "state")).isEqualTo("completed");
            assertThat(CreatorServerE2eSupport.number(examples, "failed")).isEqualTo(1);
            assertThat(CreatorServerE2eSupport.string(example, "status")).isEqualTo("failed");
            assertThat(view.body()).contains(
                    "\"code\":\"TRACE_EXECUTION_FAILED\""
            ).doesNotContain("\"type\"");
        }
    }

    @Test
    void selectedStepViewIncludesTheApplicationTraceFailure() throws Exception {
        try (CreatorServer creator = startFatalExample("fatal-step-view")) {
            awaitExamples(creator, 1);

            final HttpResponse<String> view = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0/steps/2",
                    ""
            );

            assertThat(view.statusCode()).isEqualTo(200);
            assertThat(view.body())
                    .contains("\"id\":\"fatal\"")
                    .contains("\"status\":\"failed\"")
                    .contains("\"code\":\"TRACE_EXECUTION_FAILED\"")
                    .contains("\"input_context\"")
                    .doesNotContain("\"type\"");
        }
    }

    @Test
    void timedOutChunkDoesNotAdmitMoreExamplesUntilEveryWorkerActuallyExits() throws Exception {
        final Path workspace = directory.resolve("timed-chunk");
        final Path project = workspace.resolve("railix.project.json");
        final Path gate = workspace.resolve("gate");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(chunkGateStep(true)),
                ChunkGateStepHandler.class
        );
        Files.writeString(project, gatedExamples(gate), StandardCharsets.UTF_8);
        final CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"));

        try {
            awaitInterrupted(gate, ExampleSuiteTestAccess.CHUNK_SIZE);
            assertThat(Files.exists(gate.resolve("case-17.started"))).isFalse();

            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
            final RailixValue.ObjectValue examples = awaitExamples(
                    creator,
                    ExampleSuiteTestAccess.CHUNK_SIZE + 1L
            );
            final HttpResponse<String> view = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0/view",
                    ""
            );

            assertThat(CreatorServerE2eSupport.number(examples, "failed"))
                    .isEqualTo(ExampleSuiteTestAccess.CHUNK_SIZE);
            assertThat(CreatorServerE2eSupport.number(examples, "succeeded")).isEqualTo(1);
            assertThat(Files.readString(gate.resolve("case-17.started"), StandardCharsets.UTF_8))
                    .isEqualTo("started");
            assertThat(view.body()).contains(
                    "\"code\":\"TRACE_EXECUTION_TIMEOUT\""
            ).doesNotContain("\"type\"");
        } finally {
            Files.createDirectories(gate);
            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
            creator.close();
        }
    }

    @Test
    void creatorRemovesAnInterruptedChildRuntimeWithoutWaitingForAnotherLaunch() throws Exception {
        final Path workspace = directory.resolve("forced-runtime-cleanup");
        final Path project = workspace.resolve("railix.project.json");
        final Path gate = workspace.resolve("gate");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(chunkGateStep(true)),
                ChunkGateStepHandler.class
        );
        Files.writeString(project, singleGatedExample(gate), StandardCharsets.UTF_8);
        final CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"));

        try {
            awaitStarted(gate, 1);
            final Path runtime = runtimeDirectory(creator.baseUri());

            creator.close();

            assertThat(runtime).doesNotExist();
        } finally {
            Files.createDirectories(gate);
            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
            creator.close();
        }
    }

    @Test
    void creatorDrainsApplicationExampleViewsBeforeSlowClientsCanExhaustApplicationReads() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, largeExample("original"), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            awaitExample(creator, "original");
            try (OpenExampleViews views = OpenExampleViews.open(
                    creator.baseUri(),
                    2
            )) {
                final HttpResponse<String> additional = request(
                        creator.baseUri(),
                        "GET",
                        "/api/examples/command:0/view",
                        ""
                );
                assertThat(additional.statusCode()).isEqualTo(200);
                views.close();
                awaitTrace(creator);
            }
        }
    }

    @Test
    void creatorBoundsClientsHoldingBufferedExampleViews() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, largeExample("bounded"), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            awaitExample(creator, "bounded");
            try (OpenExampleViews views = OpenExampleViews.open(
                    creator.baseUri(),
                    CreatorServer.MAX_CONCURRENT_EXAMPLE_RESPONSES
            )) {
                final HttpResponse<String> saturated = request(
                        creator.baseUri(),
                        "GET",
                        "/api/examples/command:0/view",
                        ""
                );

                assertThat(saturated.statusCode()).isEqualTo(503);
                assertThat(saturated.body())
                        .isEqualTo("{\"reason\":\"saturated\",\"status\":\"unavailable\"}");
            }
        }
    }

    @Test
    void creatorReleasesBufferedExampleAdmissionAfterClientsDisconnect() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, largeExample("disconnect"), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            awaitExample(creator, "disconnect");
            final OpenExampleViews views = OpenExampleViews.open(
                    creator.baseUri(),
                    CreatorServer.MAX_CONCURRENT_EXAMPLE_RESPONSES
            );
            views.close();
            awaitTrace(creator);
            try (OpenExampleViews reopened = awaitOpenExampleViews(
                    creator,
                    CreatorServer.MAX_CONCURRENT_EXAMPLE_RESPONSES
            )) {
                final HttpResponse<String> saturated = request(
                        creator.baseUri(),
                        "GET",
                        "/api/examples/command:0/view",
                        ""
                );
                assertThat(saturated.statusCode()).isEqualTo(503);
            }
        }
    }

    @Test
    void replacingExamplesDoesNotBlockOnAClientHoldingThePreviousView() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, largeExample("original"), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            awaitExample(creator, "original");
            try (OpenExampleViews views = OpenExampleViews.open(creator.baseUri(), 1)) {
                final HttpResponse<String> replacement = request(
                        creator.baseUri(),
                        "POST",
                        "/api/project",
                        largeExample("replacement")
                );

                assertThat(replacement.statusCode()).isEqualTo(200);
                awaitExample(creator, "replacement");
            }
        }
    }

    @Test
    void exampleRelayRejectsAProjectionFromTheApplicationBehindAnAcceptedBuild() throws Exception {
        final Path workspace = directory.resolve("replace-during-example-read");
        final Path project = workspace.resolve("railix.project.json");
        final Path gate = workspace.resolve("gate");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(
                        trigger("test.trigger.snapshot-large", "test.source.snapshot-large"),
                        trigger("test.trigger.snapshot-gate", "test.source.snapshot-gate"),
                        chunkGateStep()
                ),
                GenericContractSteps.Trigger.class,
                ChunkGateStepHandler.class
        );
        Files.writeString(project, snapshotRaceExamples(gate), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"));
             ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            awaitSnapshotRaceReady(creator, gate);
            final RailixValue.ObjectValue previous = application(creator.baseUri());
            final long previousPid = CreatorServerE2eSupport.number(previous, "pid");
            final String path = "/api/examples/steps/" + SNAPSHOT_GATE_NODE;
            final Future<HttpResponse<String>> replacement;
            synchronized (ApplicationBuilder.class) {
                replacement = executor.submit(() -> request(
                        creator.baseUri(),
                        "POST",
                        "/api/project",
                        largeExample("replacement")
                ));
                final HttpResponse<String> stale = awaitUnavailableProjection(creator, replacement, path);
                assertThat(stale.statusCode()).isEqualTo(503);
                assertThat(stale.body()).isEqualTo("{\"reason\":\"application\",\"status\":\"unavailable\"}");
            }
            awaitApplicationReplacement(creator, previousPid);

            assertThat(replacement.get(60, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            awaitExample(creator, "replacement");
        } finally {
            Files.createDirectories(gate);
            Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
        }
    }

    @Test
    void closingCreatorDoesNotBlockOnAClientHoldingAnExampleView() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, largeExample("shutdown"), StandardCharsets.UTF_8);
        final CreatorServer creator = start(project);

        try {
            awaitExample(creator, "shutdown");
        } catch (final Exception exception) {
            creator.close();
            throw exception;
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
             OpenExampleViews views = OpenExampleViews.open(
                     creator.baseUri(),
                     CreatorServer.MAX_CONCURRENT_EXAMPLE_RESPONSES
             )) {
            assertThat(request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0/view",
                    ""
            ).statusCode()).isEqualTo(503);
            final Future<?> close = executor.submit(creator::close);
            close.get(10, TimeUnit.SECONDS);
        } finally {
            creator.close();
        }
    }

    @Test
    void failedExampleDeletionRemainsOwnedUntilCreatorCloseCanRetry() throws Exception {
        final Path workspace = directory.resolve("cleanup-retry");
        final Path project = workspace.resolve("railix.project.json");
        Files.createDirectories(workspace);
        Files.writeString(project, largeExample("owned"), StandardCharsets.UTF_8);
        final CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"));

        try {
            awaitExample(creator, "owned");
            final Path traceDirectory = runtimeDirectory(creator.baseUri());
            Files.setPosixFilePermissions(traceDirectory, PosixFilePermissions.fromString("r-x------"));

            final HttpResponse<String> replacement = request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    largeExample("replacement")
            );

            assertThat(replacement.statusCode()).isEqualTo(200);
            assertThatThrownBy(creator::close)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Retired generated application did not clean up");

            Files.setPosixFilePermissions(traceDirectory, PosixFilePermissions.fromString("rwx------"));
            creator.close();

            assertThat(Files.exists(traceDirectory)).isFalse();
        } finally {
            creator.close();
        }
    }

    @Test
    void laterRollingBuildRetriesFailedExampleDeletionWhileCreatorRemainsOpen() throws Exception {
        final Path workspace = directory.resolve("rolling-cleanup-retry");
        final Path project = workspace.resolve("railix.project.json");
        Files.createDirectories(workspace);
        Files.writeString(project, largeExample("owned"), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            awaitExample(creator, "owned");
            final Path traceDirectory = runtimeDirectory(creator.baseUri());
            Files.setPosixFilePermissions(traceDirectory, PosixFilePermissions.fromString("r-x------"));

            assertThat(request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    largeExample("blocked")
            ).statusCode()).isEqualTo(200);
            Files.setPosixFilePermissions(traceDirectory, PosixFilePermissions.fromString("rwx------"));
            assertThat(request(
                    creator.baseUri(),
                    "POST",
                    "/api/project",
                    largeExample("recovered")
            ).statusCode()).isEqualTo(200);

            awaitExample(creator, "recovered");
            assertThat(Files.exists(traceDirectory)).isFalse();
        }
    }

    private RailixValue.ObjectValue awaitExamples(
            final CreatorServer creator,
            final long expected
    ) throws Exception {
        return awaitExamples(creator, expected, Duration.ofSeconds(10));
    }

    private static Path runtimeDirectory(final URI baseUri) throws Exception {
        final RailixValue.ObjectValue application = application(baseUri);
        return Path.of(CreatorServerE2eSupport.string(application, "build_path"))
                .getParent()
                .resolve(".railix-runtime")
                .resolve(Long.toString(CreatorServerE2eSupport.number(application, "pid")));
    }

    private void assertArrayTargetExample(
            final String workspaceName,
            final String context,
            final String expectedArguments
    ) throws Exception {
        final Path workspace = directory.resolve(workspaceName);
        final Path project = workspace.resolve("railix.project.json");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(arrayTrigger()),
                GenericContractSteps.Trigger.class
        );
        Files.writeString(project, arrayTargetExample(context), StandardCharsets.UTF_8);

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.resolve("railix-home"))) {
            awaitExamples(creator, 1);
            final HttpResponse<String> view = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0/view",
                    ""
            );

            assertThat(view.statusCode()).isEqualTo(200);
            assertThat(view.body()).contains("\"arguments\":" + expectedArguments);
        }
    }

    private void assertCompletedExampleRoute(
            final String method,
            final String path,
            final int expectedStatus
    ) throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, largeExample("route"), StandardCharsets.UTF_8);

        try (CreatorServer creator = start(project)) {
            awaitExample(creator, "route");

            assertThat(request(creator.baseUri(), method, path, "").statusCode())
                    .isEqualTo(expectedStatus);
        }
    }

    private CreatorServer startWithoutInjectableExamples(final String workspaceName) throws Exception {
        final Path workspace = directory.resolve(workspaceName);
        final Path project = workspace.resolve("railix.project.json");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(triggerWithoutExampleTarget()),
                GenericContractSteps.Trigger.class
        );
        Files.writeString(project, missingExampleTargetProject(), StandardCharsets.UTF_8);
        return CreatorServer.start(0, project, workspace.resolve("railix-home"));
    }

    private CreatorServer startFatalExample(final String workspaceName) throws Exception {
        final Path workspace = directory.resolve(workspaceName);
        final Path project = workspace.resolve("railix.project.json");
        Files.createDirectories(workspace);
        GeneratedApplicationFixture.installedCatalog(
                workspace,
                List.of(StepDefinition.named("test.fatal", "1")
                        .run(DevelopmentRuntimeConformanceSteps.Fatal.class)),
                DevelopmentRuntimeConformanceSteps.Fatal.class
        );
        Files.writeString(project, fatalExample(), StandardCharsets.UTF_8);
        return CreatorServer.start(0, project, workspace.resolve("railix-home"));
    }

    private static void assertProjectionFailure(final String trace, final String message) {
        assertProjectionFailure(trace, -1, true, message);
    }

    private static void assertProjectionFailure(
            final String trace,
            final int selectedNode,
            final boolean summary,
            final String message
    ) {
        assertThatThrownBy(() -> ExampleSuiteTestAccess.project(
                new ByteArrayInputStream(trace.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(),
                selectedNode,
                summary
        )).isInstanceOf(IOException.class).hasMessage(message);
    }

    private static void assertContextChangeFailure(final String change, final String message) {
        assertContextChangeFailure(change, "{}", message);
    }

    private static void assertContextChangeFailure(
            final String change,
            final String context,
            final String message
    ) {
        assertProjectionFailure(
                "{\"type\":\"trace\",\"context\":" + context + "}\n"
                        + stepStart()
                        + "{\"type\":\"step_result\",\"sequence\":0,\"occurrence\":0,"
                        + "\"id\":\"step\",\"changes\":[" + change + "]}\n",
                message
        );
    }

    private static String completedChangeTrace(final String context, final String change) {
        return "{\"type\":\"trace\",\"context\":" + context + "}\n"
                + stepStart()
                + "{\"type\":\"step_result\",\"sequence\":0,\"occurrence\":0,"
                + "\"id\":\"step\",\"changes\":[" + change + "]}\n"
                + "{\"type\":\"result\",\"status\":\"succeeded\",\"context\":{}}\n";
    }

    private static String project(
            final String trace,
            final int selectedNode,
            final boolean summary
    ) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        ExampleSuiteTestAccess.project(
                new ByteArrayInputStream(trace.getBytes(StandardCharsets.UTF_8)),
                output,
                selectedNode,
                summary
        );
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String stepStart() {
        return "{\"type\":\"step_start\",\"sequence\":0,\"occurrence\":0,"
                + "\"id\":\"step\",\"use\":\"test.step\",\"node\":1}\n";
    }

    private RailixValue.ObjectValue awaitExamples(
            final CreatorServer creator,
            final long expected,
            final Duration timeout
    ) throws Exception {
        final long deadline = System.nanoTime() + timeout.toNanos();
        RailixValue.ObjectValue examples;
        do {
            examples = examples(creator.baseUri());
            if (CreatorServerE2eSupport.number(examples, "completed") == expected) {
                return examples;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return examples;
    }

    private RailixValue.ObjectValue awaitExample(
            final CreatorServer creator,
            final String name
    ) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        RailixValue.ObjectValue examples;
        do {
            examples = examples(creator.baseUri());
            final RailixValue.ArrayValue cases = (RailixValue.ArrayValue) examples.values().get("cases");
            if (CreatorServerE2eSupport.number(examples, "completed") == 1
                    && cases.values().size() == 1
                    && name.equals(CreatorServerE2eSupport.string(
                            (RailixValue.ObjectValue) cases.values().getFirst(),
                            "name"
                    ))) {
                return examples;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Example did not complete: " + name + ". Last state: " + examples);
    }

    private void awaitTrace(final CreatorServer creator) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        HttpResponse<String> response;
        do {
            response = request(creator.baseUri(), "GET", "/api/examples/command:0/view", "");
            if (response.statusCode() == 200) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Example trace capacity was not released. Last status: " + response.statusCode());
    }

    private OpenExampleViews awaitOpenExampleViews(
            final CreatorServer creator,
            final int count
    ) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        IOException failure;
        do {
            try {
                return OpenExampleViews.open(creator.baseUri(), count);
            } catch (final IOException exception) {
                failure = exception;
                Thread.sleep(20);
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Creator Example response capacity was not restored.", failure);
    }

    private static String choiceExample() {
        return """
                {"format":1,"id":"choice-example","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"match","payload":[],"context":{"payload":{"value":"allow"}}},
                    {"name":"otherwise","payload":[],"context":{"payload":{"value":"deny"}}}
                  ]},
                  {"id":"choice","use":"railix.choice","inputs":{"conditions":[[{
                    "option":"field","inputs":{"field":["context","payload","value"]},
                    "when":{"transforms":[],"all":[[
                      {"use":"value.equals","inputs":{"expected":"allow"}}
                    ]]}
                  }]]}},
                  {"id":"matched","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],
                    "value":[{"option":"literal","inputs":{"literal":"matched"}}],"steps":[]}},
                  {"id":"otherwise","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],
                    "value":[{"option":"literal","inputs":{"literal":"otherwise"}}],"steps":[]}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"choice"},
                  {"from":"choice.match","to":"matched"},
                  {"from":"choice.otherwise","to":"otherwise"},
                  {"from":"matched.next","to":"end"},
                  {"from":"otherwise.next","to":"end"}
                ]}
                """;
    }

    private static String twoExamples() {
        return """
                {"format":1,"id":"automatic-examples","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"mixed-case","payload":["Hello RAILIX"]},
                    {"name":"upper-case","payload":["SECOND"]}
                  ]},
                  {"id":"lowercase-text","use":"text.lowercase","inputs":{},
                    "receives":{"value":["context","payload","arguments",0]},
                    "returns":{"value":["context","result"]}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"lowercase-text"},
                  {"from":"lowercase-text.ok","to":"end"}
                ]}
                """;
    }

    private static String examples(final int count) {
        final String values = java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> "{\"name\":\"case-" + index + "\",\"payload\":[\"VALUE-" + index + "\"]}")
                .collect(java.util.stream.Collectors.joining(","));
        return """
                {"format":1,"id":"chunked-examples","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[%s]},
                  {"id":"lowercase-text","use":"text.lowercase","inputs":{},
                    "receives":{"value":["context","payload","arguments",0]},
                    "returns":{"value":["context","result"]}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"lowercase-text"},
                  {"from":"lowercase-text.ok","to":"end"}
                ]}
                """.formatted(values);
    }

    private static String snapshotRaceExamples(final Path gate) {
        final StringBuilder nodes = new StringBuilder("""
                {"format":1,"id":"aggregate-replay-snapshot","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"large","use":"test.trigger.snapshot-large","inputs":{
                    "target":["context","payload","arguments"]
                  },"examples":[{"name":"large","payload":["%s"]}]}
                """.formatted("x".repeat(900_000)));
        final StringBuilder links = new StringBuilder("""
                ],"links":[
                  {"from":"app.start","to":"large"},
                  {"from":"large.next","to":"lowercase-0"}
                """);
        for (int index = 0; index < SNAPSHOT_REPLAY_STEPS; index++) {
            nodes.append("""
                    ,{"id":"lowercase-%d","use":"text.lowercase","inputs":{},
                      "receives":{"value":["context","payload","arguments",0]},
                      "returns":{"value":["context","payload","arguments",0]}}
                    """.formatted(index));
            links.append("""
                    ,{"from":"lowercase-%d.ok","to":%s}
                    """.formatted(
                    index,
                    index == SNAPSHOT_REPLAY_STEPS - 1 ? "\"end\"" : "\"lowercase-" + (index + 1) + "\""
            ));
        }
        nodes.append("""
                ,{"id":"gate","use":"test.trigger.snapshot-gate","inputs":{
                  "target":["context","payload","arguments"]
                },"examples":[{"name":"gate","payload":{"case":"gate","root":%s}}]},
                {"id":"snapshot-gate","use":"test.chunk-gate","inputs":{}}
                """.formatted(RailixJson.write(RailixValue.string(gate.toString()))));
        links.append("""
                ,{"from":"app.start","to":"gate"},
                {"from":"gate.next","to":"snapshot-gate"},
                {"from":"snapshot-gate.next","to":"end"}]}
                """);
        return nodes.append(links).toString();
    }

    private static StepDefinition oversizedTraceStep() {
        return StepDefinition.named("test.oversized-trace", "1")
                .input("target", StepDefinition.Input.path(StepDefinition.PathAccess.READ_WRITE)
                        .defaultPath("context", "payload", "large"))
                .run(DevelopmentRuntimeConformanceSteps.OversizedTrace.class);
    }

    private static StepDefinition markerStep(final Path file) {
        return StepDefinition.named("test.marker", "1")
                .input("file", StepDefinition.Input.json(ValueShape.STRING)
                        .defaultValue(RailixValue.string(file.toString())))
                .run(DevelopmentRuntimeConformanceSteps.Append.class);
    }

    private static StepDefinition chunkGateStep() {
        return chunkGateStep(false);
    }

    private static StepDefinition chunkGateStep(final boolean ignoreInterrupt) {
        return StepDefinition.named("test.chunk-gate", "1")
                .input("root", StepDefinition.Input.path(StepDefinition.PathAccess.READ)
                        .defaultPath("context", "payload", "arguments", "root"))
                .input("case", StepDefinition.Input.path(StepDefinition.PathAccess.READ)
                        .defaultPath("context", "payload", "arguments", "case"))
                .input("ignore_interrupt", StepDefinition.Input.json(ValueShape.BOOLEAN)
                        .defaultValue(RailixValue.bool(ignoreInterrupt)))
                .run(ChunkGateStepHandler.class);
    }

    private static StepDefinition summaryGateStep(final Path root) {
        return StepDefinition.named("test.summary-gate", "1")
                .input("root", StepDefinition.Input.json(ValueShape.STRING)
                        .defaultValue(RailixValue.string(root.toString())))
                .input("case", StepDefinition.Input.path(StepDefinition.PathAccess.READ)
                        .defaultPath("context", "payload", "arguments"))
                .input("ignore_interrupt", StepDefinition.Input.json(ValueShape.BOOLEAN)
                        .defaultValue(RailixValue.bool(false)))
                .run(ChunkGateStepHandler.class);
    }

    private static StepDefinition trigger(final String id, final String source) {
        return StepDefinition.named(id, "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source(source)
                .input("target", StepDefinition.Input.path(StepDefinition.PathAccess.WRITE)
                        .defaultPath("context", "payload", "value"))
                .exampleTarget("target")
                .run(GenericContractSteps.Trigger.class);
    }

    private static StepDefinition arrayTrigger() {
        return StepDefinition.named("test.trigger.array", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("test.source.array")
                .input("target", StepDefinition.Input.path(StepDefinition.PathAccess.WRITE)
                        .defaultValue(RailixValue.array(List.of(
                                RailixValue.string("context"),
                                RailixValue.string("payload"),
                                RailixValue.string("arguments"),
                                RailixValue.number(1)
                        ))))
                .exampleTarget("target")
                .run(GenericContractSteps.Trigger.class);
    }

    private static StepDefinition nestedArrayTrigger() {
        return StepDefinition.named("test.trigger.nested-array", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("test.source.nested-array")
                .input("target", StepDefinition.Input.path(StepDefinition.PathAccess.WRITE)
                        .defaultValue(RailixValue.array(List.of(
                                RailixValue.string("context"),
                                RailixValue.string("payload"),
                                RailixValue.string("items"),
                                RailixValue.number(1),
                                RailixValue.string("name")
                        ))))
                .exampleTarget("target")
                .run(GenericContractSteps.Trigger.class);
    }

    private static StepDefinition triggerWithoutExampleTarget() {
        return StepDefinition.named("test.trigger.no-example-target", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("test.source.no-example-target")
                .run(GenericContractSteps.Trigger.class);
    }

    private static String arrayTargetExample(final String context) {
        return """
                {"format":1,"id":"array-target-example","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"test.trigger.array","inputs":{},"examples":[{
                    "name":"array-slot","payload":"second","context":%s
                  }]}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"end"}
                ]}
                """.formatted(context);
    }

    private static String nestedArrayTargetExample() {
        return """
                {"format":1,"id":"nested-array-target-example","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"test.trigger.nested-array","inputs":{},"examples":[{
                    "name":"nested-array-field","payload":"second","context":{}
                  }]}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"end"}
                ]}
                """;
    }

    private static String missingExampleTargetProject() {
        return """
                {"format":1,"id":"missing-example-target","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"test.trigger.no-example-target","inputs":{},"examples":[{
                    "name":"default-payload","payload":{}
                  }]}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"end"}
                ]}
                """;
    }

    private static String injectableExampleTargetProject() {
        return """
                {"format":1,"id":"injectable-example-target","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"test.trigger.example-target","inputs":{},"examples":[{
                    "name":"injectable","payload":{"value":"ready"}
                  }]}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"end"}
                ]}
                """;
    }

    private static String gatedExamples(final Path gate) {
        final String root = RailixJson.write(RailixValue.string(gate.toString()));
        final String values = java.util.stream.IntStream.rangeClosed(1, ExampleSuiteTestAccess.CHUNK_SIZE + 1)
                .mapToObj(index -> "{\"name\":\"case-" + index + "\",\"payload\":{"
                        + "\"case\":\"case-" + index + "\",\"root\":" + root + "}}")
                .collect(java.util.stream.Collectors.joining(","));
        return """
                {"format":1,"id":"chunk-admission","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[%s]},
                  {"id":"gate","use":"test.chunk-gate","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"gate"},
                  {"from":"gate.next","to":"end"}
                ]}
                """.formatted(values);
    }

    private static String sequentialGatedExample(final Path firstGate, final Path secondGate) {
        return """
                {"format":1,"id":"fresh-running-projection","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"fresh","payload":{
                      "first":{"case":"first","root":%s},
                      "second":{"case":"second","root":%s}
                    }}
                  ]},
                  {"id":"first","use":"test.chunk-gate","inputs":{
                    "root":["context","payload","arguments","first","root"],
                    "case":["context","payload","arguments","first","case"]
                  }},
                  {"id":"second","use":"test.chunk-gate","inputs":{
                    "root":["context","payload","arguments","second","root"],
                    "case":["context","payload","arguments","second","case"]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"first"},
                  {"from":"first.next","to":"second"},
                  {"from":"second.next","to":"end"}
                ]}
                """.formatted(
                RailixJson.write(RailixValue.string(firstGate.toString())),
                RailixJson.write(RailixValue.string(secondGate.toString()))
        );
    }

    private static String singleGatedExample(final Path gate) {
        return """
                {"format":1,"id":"forced-runtime-cleanup","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"blocked","payload":{"case":"blocked","root":%s}}
                  ]},
                  {"id":"gate","use":"test.chunk-gate","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"gate"},
                  {"from":"gate.next","to":"end"}
                ]}
                """.formatted(RailixJson.write(RailixValue.string(gate.toString())));
    }

    private static String manyExamples(final int count) {
        final String values = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> {
                    final String id = "case-" + index;
                    return "{\"name\":\"" + id + "-" + "x".repeat(64)
                            + "\",\"payload\":\"" + id + "\"}";
                })
                .collect(java.util.stream.Collectors.joining(","));
        return """
                {"format":1,"id":"large-example-summary","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[%s]},
                  {"id":"gate","use":"test.summary-gate","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"gate"},
                  {"from":"gate.next","to":"end"}
                ]}
                """.formatted(values);
    }

    private static String multiTriggerExamples() {
        return """
                {"format":1,"id":"multi-trigger-coverage","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"first","use":"test.trigger.first","inputs":{},"examples":[{
                    "name":"first-example","payload":"FIRST"
                  }]},
                  {"id":"first-lowercase","use":"text.lowercase","inputs":{},
                    "receives":{"value":["context","payload","value"]},
                    "returns":{"value":["context","result"]}},
                  {"id":"second","use":"test.trigger.second","inputs":{},"examples":[{
                    "name":"second-example","payload":"SECOND"
                  }]},
                  {"id":"second-lowercase","use":"text.lowercase","inputs":{},
                    "receives":{"value":["context","payload","value"]},
                    "returns":{"value":["context","result"]}}
                ],"links":[
                  {"from":"app.start","to":"first"},
                  {"from":"first.next","to":"first-lowercase"},
                  {"from":"first-lowercase.ok","to":"end"},
                  {"from":"app.start","to":"second"},
                  {"from":"second.next","to":"second-lowercase"},
                  {"from":"second-lowercase.ok","to":"end"}
                ]}
                """;
    }

    private static String oversizedTraceEventExample() {
        return """
                {"format":1,"id":"oversized-trace-event","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"large-event","payload":[]}
                  ]},
                  {"id":"large","use":"test.oversized-trace","inputs":{}},
                  {"id":"downstream","use":"test.marker","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"large"},
                  {"from":"large.next","to":"downstream"},
                  {"from":"downstream.next","to":"end"}
                ]}
                """;
    }

    private static String oversizedTraceThenGateExample(final Path gate) {
        return """
                {"format":1,"id":"trace-failure-timeout","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"timeout","payload":{"root":%s,"case":"case-1"}}
                  ]},
                  {"id":"large","use":"test.oversized-trace","inputs":{}},
                  {"id":"gate","use":"test.chunk-gate","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"large"},
                  {"from":"large.next","to":"gate"},
                  {"from":"gate.next","to":"end"}
                ]}
                """.formatted(RailixJson.write(RailixValue.string(gate.toString())));
    }

    private static String fatalExample() {
        return """
                {"format":1,"id":"fatal-example","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"fatal","payload":[]}
                  ]},
                  {"id":"fatal","use":"test.fatal","inputs":{}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"fatal"},
                  {"from":"fatal.next","to":"end"}
                ]}
                """;
    }

    private static void awaitStarted(final Path gate, final int expected) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        int started = 0;
        do {
            if (Files.isDirectory(gate)) {
                try (var files = Files.list(gate)) {
                    started = (int) files.filter(path -> path.getFileName().toString().endsWith(".started"))
                            .count();
                }
            }
            if (started == expected) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected " + expected + " admitted examples but observed " + started + ".");
    }

    private static void release(final Path gate) throws IOException {
        Files.createDirectories(gate);
        Files.writeString(gate.resolve("release"), "release", StandardCharsets.UTF_8);
    }

    private static void awaitInterrupted(final Path gate, final int expected) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        int interrupted = 0;
        do {
            if (Files.isDirectory(gate)) {
                try (var files = Files.list(gate)) {
                    interrupted = (int) files.filter(path -> path.getFileName().toString().endsWith(".interrupted"))
                            .count();
                }
            }
            if (interrupted == expected) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected " + expected + " interrupted examples but observed " + interrupted + ".");
    }

    private void awaitSnapshotRaceReady(final CreatorServer creator, final Path gate) throws Exception {
        awaitStarted(gate, 1);
        final long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        RailixValue.ObjectValue examples;
        do {
            examples = examples(creator.baseUri());
            final RailixValue.ObjectValue large = exampleCase(examples, "large:0");
            final RailixValue.ObjectValue waiting = exampleCase(examples, "gate:0");
            if ("failed".equals(CreatorServerE2eSupport.string(large, "status"))
                    && "running".equals(CreatorServerE2eSupport.string(waiting, "status"))
                    && CreatorServerE2eSupport.number(large, "storage_bytes") > 60L * 1_024 * 1_024) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Snapshot replay Examples did not reach the required states: " + examples);
    }

    private static void awaitApplicationReplacement(
            final CreatorServer creator,
            final long previousPid
    ) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (CreatorServerE2eSupport.number(application(creator.baseUri()), "pid") != previousPid) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Rolling build did not replace the generated application.");
    }

    private static void awaitOnlyRuntime(final Path buildRoot, final long expectedPid) throws Exception {
        final Set<String> expected = Set.of(Long.toString(expectedPid));
        final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        Set<String> actual;
        do {
            try (var paths = Files.walk(buildRoot, 3)) {
                actual = paths
                        .filter(Files::isDirectory)
                        .filter(path -> path.getParent() != null
                                && ".railix-runtime".equals(path.getParent().getFileName().toString()))
                        .map(path -> path.getFileName().toString())
                        .collect(java.util.stream.Collectors.toSet());
            }
            if (actual.equals(expected)) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Generated application runtimes remained " + actual + "; expected " + expected + ".");
    }

    private static HttpResponse<String> awaitUnavailableProjection(
            final CreatorServer creator,
            final Future<HttpResponse<String>> replacement,
            final String path
    ) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (replacement.isDone()) {
                final HttpResponse<String> response = replacement.get(1, TimeUnit.SECONDS);
                throw new AssertionError("Rolling build completed before its pending projection was observed: "
                        + response.statusCode() + " " + response.body());
            }
            final HttpResponse<String> response = request(creator.baseUri(), "GET", path, "");
            if (response.statusCode() == 503) {
                return response;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Rolling build did not expose its pending application state.");
    }

    private static void awaitExampleStatusWhilePending(
            final CreatorServer creator,
            final Future<?> request,
            final String id,
            final String expected
    ) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        String actual;
        do {
            if (request.isDone()) {
                throw new AssertionError("Projection completed before Example " + id + " reached " + expected + ".");
            }
            actual = CreatorServerE2eSupport.string(exampleCase(examples(creator.baseUri()), id), "status");
            if (expected.equals(actual)) {
                if (request.isDone()) {
                    throw new AssertionError("Projection completed before Example " + id + " reached " + expected + ".");
                }
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Example " + id + " remained " + actual + "; expected " + expected + ".");
    }

    private static RailixValue.ObjectValue exampleCase(
            final RailixValue.ObjectValue examples,
            final String id
    ) {
        final RailixValue.ArrayValue cases = (RailixValue.ArrayValue) examples.values().get("cases");
        return cases.values().stream()
                .map(RailixValue.ObjectValue.class::cast)
                .filter(value -> id.equals(CreatorServerE2eSupport.string(value, "id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Example case is missing: " + id));
    }

    private static String largeExample(final String name) {
        return """
                {"format":1,"id":"trace-ownership","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"%s","payload":["%s"]}
                  ]}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"end"}
                ]}
                """.formatted(name, "x".repeat(900_000));
    }

    private static String namedExample(final String name) {
        return """
                {"format":1,"id":"reused-persistence","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"%s","payload":["%s"]}
                  ]}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"end"}
                ]}
                """.formatted(name, name);
    }

    private static String markerExample(final Path marker) {
        return """
                {"format":1,"id":"unpersisted-candidate","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"candidate","payload":[]}
                  ]},
                  {"id":"marker","use":"test.marker","inputs":{"file":%s}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"marker"},
                  {"from":"marker.next","to":"end"}
                ]}
                """.formatted(RailixJson.write(RailixValue.string(marker.toString())));
    }

    private static void awaitDeploymentPending(final URI creator) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        HttpResponse<String> response;
        do {
            response = request(creator, "GET", "/api/examples", "");
            if (response.statusCode() == 503 && response.body().contains("\"reason\":\"application\"")) {
                return;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Creator did not expose its rolling deployment state. Last response: " + response);
    }

    private static String oversizedMaterializedContext() {
        return """
                {"format":1,"id":"oversized-materialized-context","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{
                    "target":["context","payload","arguments",300000]
                  },"examples":[{"name":"oversized","payload":"value"}]}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"end"}
                ]}
                """;
    }

    private static final class OpenExampleViews implements AutoCloseable {
        private final List<Socket> sockets;

        private OpenExampleViews(final List<Socket> sockets) {
            this.sockets = sockets;
        }

        static OpenExampleViews open(final URI creator, final int count) throws IOException {
            final List<Socket> sockets = new ArrayList<>(count);
            try {
                for (int index = 0; index < count; index++) {
                    sockets.add(open(creator));
                }
                return new OpenExampleViews(sockets);
            } catch (final IOException | RuntimeException exception) {
                sockets.forEach(OpenExampleViews::close);
                throw exception;
            }
        }

        private static Socket open(final URI creator) throws IOException {
            final Socket socket = new Socket();
            socket.setReceiveBufferSize(1_024);
            socket.setSoTimeout(10_000);
            socket.connect(new InetSocketAddress(creator.getHost(), creator.getPort()));
            socket.getOutputStream().write(("""
                    GET /api/examples/command:0/view HTTP/1.1\r
                    Host: %s:%d\r
                    X-Railix-Creator-Token: %s\r
                    Connection: close\r
                    \r
                    """.formatted(
                    creator.getHost(),
                    creator.getPort(),
                    tokenOrIncorrect(creator)
            )).getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            final String header = header(socket.getInputStream());
            if (!header.startsWith("HTTP/1.1 200")) {
                close(socket);
                throw new IOException("Trace response was not accepted: " + header.lines().findFirst().orElse(header));
            }
            return socket;
        }

        private static String header(final InputStream input) throws IOException {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4 && bytes.size() < 16_384) {
                final int next = input.read();
                if (next < 0) {
                    break;
                }
                bytes.write(next);
                matched = switch (matched) {
                    case 0 -> next == '\r' ? 1 : 0;
                    case 1 -> next == '\n' ? 2 : next == '\r' ? 1 : 0;
                    case 2 -> next == '\r' ? 3 : 0;
                    case 3 -> next == '\n' ? 4 : 0;
                    default -> matched;
                };
            }
            return bytes.toString(StandardCharsets.US_ASCII);
        }

        @Override
        public void close() {
            sockets.forEach(OpenExampleViews::close);
        }

        private static void close(final Socket socket) {
            try {
                socket.close();
            } catch (final IOException ignored) {
                // Server-side ownership is asserted before client cleanup.
            }
        }
    }
}
