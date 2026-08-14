package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import thirdparty.conformance.StepRuntimeContractProbe;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static dev.nanonative.railix.core.step.StepDefinition.Input;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(120)
final class StepRuntimeContractGeneratedE2eTest {
    private static final String SOURCE_LAUNCHER = """
            package dev.nanonative.railix.core.project;

            import dev.nanonative.railix.core.runtime.RunResult;
            import dev.nanonative.railix.core.value.RailixJson;
            import dev.nanonative.railix.core.value.RailixValue;
            import java.math.BigDecimal;
            import java.util.LinkedHashMap;
            import java.util.List;
            import java.util.Map;

            public final class StepRuntimeContractSourceLauncher {
                private StepRuntimeContractSourceLauncher() {
                }

                public static void main(final String[] arguments) {
                    if (arguments.length != 1) {
                        throw new IllegalArgumentException("One source scenario is required.");
                    }
                    final WorkflowRuntime.SourceResult source = RailixApplication.runtime().runSource(
                            "test.source",
                            Map.of("value", value(arguments[0]))
                    );
                    System.out.print(RailixJson.write(summary(source)));
                }

                private static RailixValue value(final String scenario) {
                    return switch (scenario) {
                        case "fault" -> RailixValue.string("fault");
                        case "noncanonical-number" -> RailixValue.array(List.of(
                                RailixValue.number(BigDecimal.TEN.pow(1024))
                        ));
                        case "noncanonical-mixed" -> RailixValue.array(List.of(
                                RailixValue.number(BigDecimal.TEN.pow(1024)),
                                RailixValue.string(Character.toString((char) 0xD800))
                        ));
                        case "byte-limit" -> RailixValue.string("\u00e9");
                        case "byte-over-limit" -> RailixValue.string("\u00e9\u00e9");
                        case "depth-limit" -> RailixValue.array(List.of(RailixValue.nullValue()));
                        case "depth-over-limit" -> RailixValue.array(List.of(RailixValue.array(List.of())));
                        default -> throw new IllegalArgumentException("Unknown source scenario: " + scenario + ".");
                    };
                }

                private static RailixValue summary(final WorkflowRuntime.SourceResult source) {
                    final Map<String, RailixValue> value = new LinkedHashMap<>();
                    value.put("responses", RailixValue.number(source.responses().size()));
                    switch (source.result()) {
                        case RunResult.Succeeded succeeded -> {
                            value.put("status", RailixValue.string("succeeded"));
                            value.put("steps", RailixValue.number(succeeded.steps().size()));
                        }
                        case RunResult.Rejected rejected -> {
                            final Diagnostic diagnostic = rejected.diagnostics().getFirst();
                            value.put("status", RailixValue.string("rejected"));
                            value.put("code", RailixValue.string(diagnostic.code()));
                            value.put("message", RailixValue.string(diagnostic.message()));
                            value.put("path", RailixValue.string(diagnostic.path()));
                            value.put("steps", RailixValue.number(rejected.steps().size()));
                        }
                        case RunResult.Failed failed -> {
                            value.put("status", RailixValue.string("failed"));
                            value.put("code", RailixValue.string(failed.failure().code()));
                            value.put("message", RailixValue.string(failed.failure().message()));
                            value.put("step", RailixValue.string(failed.failure().stepId()));
                            value.put("steps", RailixValue.number(failed.steps().size()));
                        }
                        case RunResult.Cancelled cancelled -> {
                            value.put("status", RailixValue.string("cancelled"));
                            value.put("steps", RailixValue.number(cancelled.steps().size()));
                        }
                    }
                    return RailixValue.object(value);
                }
            }
            """;
    private static final List<GraphCase> GRAPH_CASES = List.of(
            new GraphCase("graph-exception", false),
            new GraphCase("graph-null", false),
            new GraphCase("graph-undeclared-outcome", false),
            new GraphCase("graph-unexpected-output", false),
            new GraphCase("graph-unknown-write", false),
            new GraphCase("graph-read-only-write", false),
            new GraphCase("graph-interrupt", false),
            new GraphCase("incompatible-result", true),
            new GraphCase("graph-multi-write", false),
            new GraphCase("graph-multi-write-sparse", false),
            new GraphCase("graph-multi-write-order", false),
            new GraphCase("receive-refinement", false),
            new GraphCase("interrupt-after-result", false),
            new GraphCase("secondary-output", false),
            new GraphCase("output-shape", false),
            new GraphCase("output-number-domain", false),
            new GraphCase("write-number-domain", true),
            new GraphCase("nested-preinterrupted", false),
            new GraphCase("nested-write", false),
            new GraphCase("nested-secondary-output", false),
            new GraphCase("nested-number-domain", false),
            new GraphCase("nested-retain", false),
            new GraphCase("nested-late-invoke", false),
            new GraphCase("nested-wrong-thread", false),
            new GraphCase("payload-number-domain", false),
            new GraphCase("payload-unicode", false),
            new GraphCase("payload-depth", false),
            new GraphCase("array-descend", false),
            new GraphCase("array-replace", false),
            new GraphCase("array-sparse-existing", false),
            new GraphCase("array-scalar-conflict", false),
            new GraphCase("array-later-read", false),
            new GraphCase("array-fork-copy", false),
            new GraphCase("array-cache-invalidate", false),
            new GraphCase("indexed-scalar-missing", false)
    );

    private GeneratedApplicationFixture graphApplication;
    private Path sourceFaultJar;
    private Path downstreamFailureJar;
    private Path refinedNumberJar;
    private Path unrefinedJar;
    private Path byteRefinementJar;
    private Path depthRefinementJar;
    private Path sourceLauncherClasses;

    @BeforeAll
    void buildGeneratedApplications(@TempDir final Path workspace) throws Exception {
        graphApplication = GeneratedApplicationFixture.start(
                workspace.resolve("graph"),
                graphProject(),
                graphDefinitions(),
                StepRuntimeContractProbe.class
        );
        sourceFaultJar = buildJar(
                workspace.resolve("source-fault"),
                sourceProject("source-fault", "contract.source.fault", ""),
                List.of(sourceTrigger("contract.source.fault", "source-always-fault"))
        );
        downstreamFailureJar = buildJar(
                workspace.resolve("downstream-failure"),
                sourceProject("downstream-failure", "contract.source.write", "contract.source.downstream"),
                List.of(
                        sourceTrigger("contract.source.write", "source-write-target"),
                        StepDefinition.named("contract.source.downstream", "1")
                                .input("value", Input.path(READ).defaultPath("context", "payload", "value"))
                                .input("mode", mode("graph-exception"))
                                .run(StepRuntimeContractProbe.class)
                )
        );
        refinedNumberJar = buildJar(
                workspace.resolve("refined-number"),
                sourceProject("refined-number", "contract.source.refined-number", ""),
                List.of(sourceTrigger(
                        "contract.source.refined-number",
                        ValueShape.ANY,
                        ValueRefinement.canonical().withMaxDepth(64),
                        "refined-source-guard"
                ))
        );
        unrefinedJar = buildJar(
                workspace.resolve("unrefined"),
                sourceProject("unrefined", "contract.source.unrefined", ""),
                List.of(sourceTrigger(
                        "contract.source.unrefined",
                        ValueShape.ANY,
                        ValueRefinement.none(),
                        "unrefined-source-verifier"
                ))
        );
        byteRefinementJar = buildJar(
                workspace.resolve("source-byte-refinement"),
                sourceProject("source-byte-refinement", "contract.source.bytes", ""),
                List.of(sourceTrigger(
                        "contract.source.bytes",
                        ValueShape.STRING,
                        ValueRefinement.canonical().withMaxJsonBytes(4),
                        "primary"
                ))
        );
        depthRefinementJar = buildJar(
                workspace.resolve("source-depth-refinement"),
                sourceProject("source-depth-refinement", "contract.source.depth", ""),
                List.of(sourceTrigger(
                        "contract.source.depth",
                        ValueShape.ANY,
                        ValueRefinement.canonical().withMaxDepth(1),
                        "primary"
                ))
        );
        sourceLauncherClasses = compileSourceLauncher(workspace.resolve("source-launcher"), sourceFaultJar);
    }

    @AfterAll
    void stopGeneratedApplication() {
        if (graphApplication != null) {
            graphApplication.close();
        }
    }

    @Test
    void graphStepImplementationExceptionBecomesAnExplicitFailure() throws Exception {
        assertFailure(runGraph("graph-exception"), "STEP_IMPLEMENTATION_FAULT", "graph-exception-step");
    }

    @Test
    void graphStepJavaNullResultBecomesAnExplicitFailure() throws Exception {
        assertFailure(runGraph("graph-null"), "STEP_RESULT_REQUIRED", "graph-null-step");
    }

    @Test
    void graphStepUndeclaredOutcomeBecomesAnExplicitFailure() throws Exception {
        assertFailure(runGraph("graph-undeclared-outcome"), "STEP_OUTCOME_INVALID", "graph-undeclared-outcome-step");
    }

    @Test
    void graphStepNestedOutputBecomesAnExplicitFailure() throws Exception {
        assertFailure(runGraph("graph-unexpected-output"), "STEP_OUTPUT_INVALID", "graph-unexpected-output-step");
    }

    @Test
    void graphStepUndeclaredWriteBecomesAnExplicitFailure() throws Exception {
        assertFailure(runGraph("graph-unknown-write"), "STEP_WRITE_UNDECLARED", "graph-unknown-write-step");
    }

    @Test
    void graphStepWriteThroughReadOnlyInputBecomesAnExplicitFailure() throws Exception {
        assertFailure(runGraph("graph-read-only-write"), "STEP_WRITE_UNDECLARED", "graph-read-only-write-step");
    }

    @Test
    void graphStepInterruptionCancelsTheStream() throws Exception {
        final DevelopmentApplication.Response response = runGraph("graph-interrupt");

        assertThat(response.status()).isEqualTo(409);
        assertThat(string(body(response), "status")).isEqualTo("cancelled");
    }

    @Test
    void incompatibleFinalTriggerResultBecomesAnExplicitRejection() throws Exception {
        assertRejection(runGraph("incompatible-result"), "RUN_RESULT_INCOMPATIBLE", "context.result");
    }

    @Test
    void graphStepCommitsMultipleDeclaredWritesInOneEvent() throws Exception {
        final DevelopmentApplication.Response response = runGraph("graph-multi-write");
        final RailixValue.ObjectValue context = object(body(response), "context");
        final RailixValue.ObjectValue payload = object(context, "payload");

        assertThat(response.status()).isEqualTo(200);
        assertThat(string(payload, "first")).isEqualTo("first");
        assertThat(string(payload, "second")).isEqualTo("second");
    }

    @Test
    void graphStepRejectsAnUnboundedGapInALaterWrite() throws Exception {
        assertRejection(
                runGraph("graph-multi-write-sparse"),
                "RUN_ARRAY_TARGET_SPARSE",
                "nodes[20].inputs.second"
        );
    }

    @Test
    void graphStepAppliesWritesInDeclaredInputOrderRatherThanResultMapOrder() throws Exception {
        assertRejection(
                runGraph("graph-multi-write-order"),
                "RUN_FIELD_TARGET_CONFLICT",
                "nodes[22].inputs.second"
        );
    }

    @Test
    void ordinaryReceiveRejectsRuntimeRefinementViolation() throws Exception {
        assertRejection(
                runGraph("receive-refinement"),
                "RUN_STEP_RECEIVE_INCOMPATIBLE",
                graphPath("receive-refinement") + ".receives.received"
        );
    }

    @Test
    void interruptSetByHandlerCancelsBeforeCommit() throws Exception {
        assertCancelled(runGraph("interrupt-after-result"));
    }

    @Test
    void secondaryOutcomeCannotReturnOutputs() throws Exception {
        assertFailure(runGraph("secondary-output"), "STEP_OUTPUT_UNEXPECTED", "secondary-output-step");
    }

    @Test
    void declaredOutputRejectsWrongShape() throws Exception {
        assertFailure(runGraph("output-shape"), "STEP_OUTPUT_INVALID", "output-shape-step");
    }

    @Test
    void declaredNumberOutputRejectsNoncanonicalValue() throws Exception {
        assertFailure(
                runGraph("output-number-domain"),
                "STEP_OUTPUT_INVALID",
                "output-number-domain-step"
        );
    }

    @Test
    void noncanonicalTriggerResultIsRejectedBeforeSerialization() throws Exception {
        assertRejection(
                runGraph("write-number-domain"),
                "RUN_RESULT_INCOMPATIBLE",
                "context.result"
        );
    }

    @Test
    void nestedProgramObservesPreexistingInterrupt() throws Exception {
        assertCancelled(runGraph("nested-preinterrupted"));
    }

    @Test
    void nestedStepCannotWriteContext() throws Exception {
        assertFailure(runGraph("nested-write"), "STEP_WRITE_UNEXPECTED", "contract.nested.write");
    }

    @Test
    void nestedSecondaryOutcomeCannotReturnOutput() throws Exception {
        assertFailure(
                runGraph("nested-secondary-output"),
                "STEP_OUTPUT_UNEXPECTED",
                "contract.nested.secondary-output"
        );
    }

    @Test
    void nestedNumberRejectsNoncanonicalValue() throws Exception {
        assertFailure(
                runGraph("nested-number-domain"),
                "STEP_OUTPUT_INVALID",
                "contract.nested.number-domain"
        );
    }

    @Test
    void nestedProgramCannotRunAfterOwningStepReturns() throws Exception {
        assertThat(runGraph("nested-retain").status()).isEqualTo(200);

        final RailixValue.ObjectValue payload = payload(runGraph("nested-late-invoke"));
        assertThat(payload.values().get("observed")).isEqualTo(RailixValue.string("closed"));
    }

    @Test
    void nestedProgramCannotRunOnAnotherThread() throws Exception {
        final RailixValue.ObjectValue payload = payload(runGraph("nested-wrong-thread"));

        assertThat(payload.values().get("observed")).isEqualTo(RailixValue.string("wrong-thread"));
    }

    @Test
    void developmentHttpReportsNoncanonicalPayloadNumber() throws Exception {
        assertInvalidDevelopmentResponse(runGraph("payload-number-domain"));
    }

    @Test
    void developmentHttpReportsInvalidPayloadUnicode() throws Exception {
        assertInvalidDevelopmentResponse(runGraph("payload-unicode"));
    }

    @Test
    void developmentHttpReportsOverDepthPayload() throws Exception {
        assertInvalidDevelopmentResponse(runGraph("payload-depth"));
    }

    @Test
    void developmentPreviewReportsNoncanonicalPayload() throws Exception {
        assertInvalidDevelopmentResponse(graphApplication.preview(
                "payload-number-domain",
                "payload-number-domain-step",
                graphContext("payload-number-domain")
        ));
    }

    @Test
    void writeDescendsIntoExistingArrayObject() throws Exception {
        final RailixValue.ObjectValue payload = payload(runGraph("array-descend"));
        final RailixValue.ObjectValue item = (RailixValue.ObjectValue)
                ((RailixValue.ArrayValue) payload.values().get("items")).values().getFirst();

        assertThat(item.values()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "before", RailixValue.string("keep"),
                "after", RailixValue.string("written")
        ));
    }

    @Test
    void writeReplacesExistingArrayElement() throws Exception {
        final RailixValue.ObjectValue payload = payload(runGraph("array-replace"));

        assertThat(((RailixValue.ArrayValue) payload.values().get("items")).values())
                .containsExactly(RailixValue.string("written"));
    }

    @Test
    void sparseWriteUsesExistingArraySize() throws Exception {
        assertRejection(
                runGraph("array-sparse-existing"),
                "RUN_ARRAY_TARGET_SPARSE",
                graphPath("array-sparse-existing") + ".inputs.target"
        );
    }

    @Test
    void arrayPathRejectsScalarRuntimeValue() throws Exception {
        assertRejection(
                runGraph("array-scalar-conflict"),
                "RUN_FIELD_TARGET_CONFLICT",
                graphPath("array-scalar-conflict") + ".inputs.target"
        );
    }

    @Test
    void laterStepReadsMaterializedArray() throws Exception {
        final RailixValue.ObjectValue payload = payload(runGraph("array-later-read"));

        assertThat(payload.values().get("observed")).isEqualTo(RailixValue.string("materialized"));
    }

    @Test
    void multiWriteForkCopiesMaterializedArray() throws Exception {
        final RailixValue.ObjectValue payload = payload(runGraph("array-fork-copy"));
        final RailixValue.ObjectValue item = (RailixValue.ObjectValue)
                ((RailixValue.ArrayValue) payload.values().get("items")).values().getFirst();

        assertThat(item.values()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "seed", RailixValue.string("materialized"),
                "first", RailixValue.string("first"),
                "second", RailixValue.string("second")
        ));
    }

    @Test
    void writeInvalidatesAnEarlierImmutableArraySnapshot() throws Exception {
        final RailixValue.ObjectValue payload = payload(runGraph("array-cache-invalidate"));
        final RailixValue.ObjectValue item = (RailixValue.ObjectValue)
                ((RailixValue.ArrayValue) payload.values().get("items")).values().getFirst();

        assertThat(item.values().get("value")).isEqualTo(RailixValue.string("after"));
    }

    @Test
    void indexedReadOfScalarResolvesMissing() throws Exception {
        final RailixValue.ObjectValue payload = payload(runGraph("indexed-scalar-missing"));

        assertThat(payload.values().get("observed")).isEqualTo(RailixValue.string("missing"));
    }

    @Test
    void sourceTriggerImplementationExceptionBecomesAnExplicitFailure() throws Exception {
        final RailixValue.ObjectValue result = runSource(sourceFaultJar, "fault");

        assertSourceFailure(result, "STEP_IMPLEMENTATION_FAULT");
        assertThat(string(result, "step")).isEqualTo("command");
    }

    @Test
    void downstreamFailureIsReturnedThroughTheSourceBoundary() throws Exception {
        final RailixValue.ObjectValue result = runSource(downstreamFailureJar, "fault");

        assertSourceFailure(result, "STEP_IMPLEMENTATION_FAULT");
        assertThat(string(result, "step")).isEqualTo("probe");
    }

    @Test
    void refinedSourceRejectsANoncanonicalDescendantBeforeCallingTheHandler() throws Exception {
        assertSourceRejection(
                runSource(refinedNumberJar, "noncanonical-number"),
                "Value contains a number outside the canonical 1024-character domain."
        );
    }

    @Test
    void unrefinedSourcePassesANoncanonicalDescendantToTheHandler() throws Exception {
        assertSourceSuccess(runSource(unrefinedJar, "noncanonical-mixed"));
    }

    @Test
    void refinedSourceAcceptsItsExactCanonicalJsonByteLimit() throws Exception {
        assertSourceSuccess(runSource(byteRefinementJar, "byte-limit"));
    }

    @Test
    void refinedSourceRejectsTheNextCanonicalJsonByte() throws Exception {
        assertSourceRejection(
                runSource(byteRefinementJar, "byte-over-limit"),
                "Canonical JSON exceeds 4 bytes."
        );
    }

    @Test
    void refinedSourceAcceptsItsExactContainerDepth() throws Exception {
        assertSourceSuccess(runSource(depthRefinementJar, "depth-limit"));
    }

    @Test
    void refinedSourceRejectsTheNextContainerDepth() throws Exception {
        assertSourceRejection(
                runSource(depthRefinementJar, "depth-over-limit"),
                "Value exceeds maximum container depth 1."
        );
    }

    private DevelopmentApplication.Response runGraph(final String trigger) throws IOException {
        return graphApplication.runTest(trigger, graphContext(trigger));
    }

    private RailixValue.ObjectValue runSource(final Path application, final String scenario) throws Exception {
        final ProcessResult process = runJava(List.of(
                java("java"),
                "-cp",
                sourceLauncherClasses + File.pathSeparator + application,
                "dev.nanonative.railix.core.project.StepRuntimeContractSourceLauncher",
                scenario
        ));
        assertThat(process.exitCode()).as(process.output()).isZero();
        final RailixJson.Result parsed = RailixJson.parse(process.output());
        assertThat(parsed).as(process.output()).isInstanceOf(RailixJson.Parsed.class);
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) parsed).value();
    }

    private static void assertSourceFailure(final RailixValue.ObjectValue result, final String code) {
        assertThat(string(result, "status")).isEqualTo("failed");
        assertThat(string(result, "code")).isEqualTo(code);
        assertThat(number(result, "responses")).isZero();
    }

    private static void assertSourceSuccess(final RailixValue.ObjectValue result) {
        assertThat(string(result, "status")).isEqualTo("succeeded");
        assertThat(number(result, "responses")).isZero();
    }

    private static void assertSourceRejection(
            final RailixValue.ObjectValue result,
            final String reason
    ) {
        assertThat(string(result, "status")).isEqualTo("rejected");
        assertThat(string(result, "code")).isEqualTo("RUN_SOURCE_VALUE_INCOMPATIBLE");
        assertThat(string(result, "message"))
                .isEqualTo("Trigger source value value is incompatible: " + reason);
        assertThat(string(result, "path")).isEqualTo("nodes[1].value");
        assertThat(number(result, "responses")).isZero();
        assertThat(number(result, "steps")).isZero();
    }

    private static void assertFailure(
            final DevelopmentApplication.Response response,
            final String code,
            final String step
    ) {
        final RailixValue.ObjectValue body = body(response);
        final RailixValue.ObjectValue failure = object(body, "failure");
        assertThat(response.status()).isEqualTo(500);
        assertThat(string(body, "status")).isEqualTo("failed");
        assertThat(string(failure, "code")).isEqualTo(code);
        assertThat(string(failure, "step")).isEqualTo(step);
    }

    private static void assertCancelled(final DevelopmentApplication.Response response) {
        assertThat(response.status()).isEqualTo(409);
        assertThat(string(body(response), "status")).isEqualTo("cancelled");
    }

    private static void assertInvalidDevelopmentResponse(final DevelopmentApplication.Response response) {
        final RailixValue.ObjectValue body = body(response);
        final RailixValue.ArrayValue diagnostics = (RailixValue.ArrayValue) body.values().get("diagnostics");
        final RailixValue.ObjectValue diagnostic = (RailixValue.ObjectValue) diagnostics.values().getFirst();

        assertThat(response.status()).isEqualTo(500);
        assertThat(string(body, "status")).isEqualTo("rejected");
        assertThat(string(diagnostic, "code")).isEqualTo("RUN_RESPONSE_INVALID");
    }

    private static void assertRejection(
            final DevelopmentApplication.Response response,
            final String code,
            final String path
    ) {
        final RailixValue.ObjectValue body = body(response);
        final RailixValue.ArrayValue diagnostics = (RailixValue.ArrayValue) body.values().get("diagnostics");
        final RailixValue.ObjectValue diagnostic = (RailixValue.ObjectValue) diagnostics.values().getFirst();
        assertThat(response.status()).isEqualTo(422);
        assertThat(string(body, "status")).isEqualTo("rejected");
        assertThat(string(diagnostic, "code")).isEqualTo(code);
        assertThat(string(diagnostic, "path")).isEqualTo(path);
    }

    private static RailixValue.ObjectValue body(final DevelopmentApplication.Response response) {
        final RailixJson.Result parsed = RailixJson.parse(response.body());
        assertThat(parsed).isInstanceOf(RailixJson.Parsed.class);
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) parsed).value();
    }

    private static RailixValue.ObjectValue payload(final DevelopmentApplication.Response response) {
        assertThat(response.status()).isEqualTo(200);
        return object(object(body(response), "context"), "payload");
    }

    private static RailixValue.ObjectValue object(final RailixValue.ObjectValue value, final String name) {
        return (RailixValue.ObjectValue) value.values().get(name);
    }

    private static String string(final RailixValue.ObjectValue value, final String name) {
        return ((RailixValue.StringValue) value.values().get(name)).value();
    }

    private static long number(final RailixValue.ObjectValue value, final String name) {
        return ((RailixValue.NumberValue) value.values().get(name)).value().longValueExact();
    }

    private static List<StepDefinition> graphDefinitions() {
        final List<StepDefinition> definitions = new ArrayList<>(GRAPH_CASES.size() * 2 + 6);
        for (final GraphCase graph : GRAPH_CASES) {
            definitions.add(graphTrigger(graph));
            definitions.add(graphStep(graph));
            if (graph.hasTail()) {
                definitions.add(graphTail(graph));
            }
        }
        definitions.add(nestedStep("contract.nested.identity", "nested-identity", ValueShape.ANY, false));
        definitions.add(nestedStep("contract.nested.write", "nested-write", ValueShape.ANY, false));
        definitions.add(nestedStep(
                "contract.nested.secondary-output",
                "nested-secondary-output",
                ValueShape.ANY,
                true
        ));
        definitions.add(nestedStep(
                "contract.nested.number-domain",
                "nested-number-domain",
                ValueShape.NUMBER,
                false
        ));
        return List.copyOf(definitions);
    }

    private static StepDefinition graphTrigger(final GraphCase graph) {
        final StepDefinition.Builder definition = StepDefinition.named(graph.triggerUse(), "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("contract.http." + graph.id());
        if (graph.numericResult()) {
            definition.result("result", ValueShape.NUMBER, RailixValue.number(0));
        } else {
            definition.result("result", ValueShape.ANY, RailixValue.nullValue());
        }
        return definition.input("mode", mode("primary")).run(StepRuntimeContractProbe.class);
    }

    private static StepDefinition graphStep(final GraphCase graph) {
        final StepDefinition.Builder definition = StepDefinition.named(graph.stepUse(), "1")
                .input("value", Input.path(READ).defaultPath("context", "payload", "value"))
                .input("mode", mode(graphMode(graph.id())));
        if (graph.numericResult()) {
            definition.input("result", Input.path(WRITE).defaultPath("context", "result"));
        }
        if (graph.id().startsWith("graph-multi-write")) {
            definition.input("first", Input.path(WRITE).defaultPath("context", "payload", "first"));
            definition.input("second", Input.path(WRITE).defaultPath("context", "payload", "second"));
        }
        switch (graph.id()) {
            case "receive-refinement" -> definition.receive(
                    "received",
                    ValueShape.ANY,
                    ValueRefinement.canonical().withMaxDepth(64).withMaxJsonBytes(4)
            );
            case "interrupt-after-result" -> definition.input(
                    "target",
                    Input.path(WRITE).defaultPath("context", "payload", "target")
            );
            case "secondary-output" -> definition
                    .returns("value", ValueShape.ANY)
                    .outcome("secondary");
            case "output-shape" -> definition.returns("value", ValueShape.STRING);
            case "output-number-domain" -> definition.returns("value", ValueShape.NUMBER);
            case "nested-preinterrupted", "nested-write", "nested-secondary-output", "nested-number-domain",
                 "nested-retain" ->
                    definition.input("steps", Input.steps(StepDefinition.ValueSource.from("value"))
                            .propagateOutcomes());
            case "nested-late-invoke" -> definition.input(
                    "observed",
                    Input.path(WRITE).defaultPath("context", "payload", "observed")
            );
            case "nested-wrong-thread" -> definition
                    .input("steps", Input.steps(StepDefinition.ValueSource.from("value")))
                    .input("observed", Input.path(WRITE).defaultPath("context", "payload", "observed"));
            case "payload-number-domain", "payload-unicode", "payload-depth" -> definition.input(
                    "target",
                    Input.path(WRITE).defaultPath("context", "payload", "bad")
            );
            case "array-descend", "array-replace", "array-sparse-existing", "array-scalar-conflict",
                 "array-later-read", "array-fork-copy", "array-cache-invalidate" -> definition.input(
                    "target",
                    Input.path(WRITE).defaultPath("context", "payload", "target")
            );
            case "indexed-scalar-missing" -> definition
                    .input("probe", Input.path(READ).optional())
                    .input("observed", Input.path(WRITE).defaultPath("context", "payload", "observed"));
            default -> {
            }
        }
        return definition.run(StepRuntimeContractProbe.class);
    }

    private static StepDefinition graphTail(final GraphCase graph) {
        final StepDefinition.Builder definition = StepDefinition.named(graph.tailUse(), "1")
                .input("mode", mode(graph.id() + "-tail"));
        if ("array-later-read".equals(graph.id())) {
            definition.receive("received", ValueShape.ANY)
                    .input("observed", Input.path(WRITE).defaultPath("context", "payload", "observed"));
        } else if ("array-cache-invalidate".equals(graph.id())) {
            definition.input("snapshot", Input.path(READ))
                    .input("target", Input.path(WRITE));
        } else {
            definition.input("first", Input.path(WRITE))
                    .input("second", Input.path(WRITE));
        }
        return definition.run(StepRuntimeContractProbe.class);
    }

    private static StepDefinition nestedStep(
            final String id,
            final String selectedMode,
            final ValueShape returned,
            final boolean secondaryOutcome
    ) {
        final StepDefinition.Builder definition = StepDefinition.named(id, "1")
                .receive("value", ValueShape.ANY)
                .returns("value", returned)
                .input("mode", mode(selectedMode));
        if (secondaryOutcome) {
            definition.outcome("secondary");
        }
        return definition.run(StepRuntimeContractProbe.class);
    }

    private static StepDefinition sourceTrigger(final String id, final String mode) {
        return StepDefinition.named(id, "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("test.source")
                .receive("value", ValueShape.STRING)
                .input("target", Input.path(WRITE).defaultPath("context", "payload", "value"))
                .input("mode", mode(mode))
                .result("result", ValueShape.ANY, RailixValue.nullValue())
                .run(StepRuntimeContractProbe.class);
    }

    private static StepDefinition sourceTrigger(
            final String id,
            final ValueShape shape,
            final ValueRefinement refinement,
            final String mode
    ) {
        return StepDefinition.named(id, "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("test.source")
                .receive("value", shape, refinement)
                .input("mode", mode(mode))
                .result("result", ValueShape.ANY, RailixValue.nullValue())
                .run(StepRuntimeContractProbe.class);
    }

    private static Input mode(final String value) {
        return Input.json(ValueShape.STRING).defaultValue(RailixValue.string(value));
    }

    private static String graphProject() {
        final StringBuilder source = new StringBuilder("{\"format\":1,\"id\":\"step-runtime-contract\",\"nodes\":[")
                .append("{\"id\":\"app\",\"use\":\"railix.app\",\"inputs\":{}}");
        for (final GraphCase graph : GRAPH_CASES) {
            source.append(",{")
                    .append("\"id\":\"").append(graph.id()).append("\",")
                    .append("\"use\":\"").append(graph.triggerUse()).append("\",\"inputs\":{},")
                    .append("\"examples\":[{\"name\":\"fault\",\"payload\":{\"value\":\"fault\"}}]}")
                    .append(",{")
                    .append("\"id\":\"").append(graph.stepId()).append("\",")
                    .append("\"use\":\"").append(graph.stepUse()).append("\",\"inputs\":")
                    .append(graphInputs(graph))
                    .append(graphMappings(graph))
                    .append('}');
            if (graph.hasTail()) {
                source.append(",{")
                        .append("\"id\":\"").append(graph.tailId()).append("\",")
                        .append("\"use\":\"").append(graph.tailUse()).append("\",\"inputs\":")
                        .append(graphTailInputs(graph))
                        .append(graphTailMappings(graph))
                        .append('}');
            }
        }
        source.append("],\"links\":[");
        for (int index = 0; index < GRAPH_CASES.size(); index++) {
            final GraphCase graph = GRAPH_CASES.get(index);
            if (index > 0) {
                source.append(',');
            }
            source.append("{\"from\":\"app.start\",\"to\":\"").append(graph.id()).append("\"},")
                    .append("{\"from\":\"").append(graph.id()).append(".next\",\"to\":\"")
                    .append(graph.stepId()).append("\"},")
                    .append("{\"from\":\"").append(graph.stepId()).append(".next\",\"to\":\"")
                    .append(graph.hasTail() ? graph.tailId() : "end").append("\"}");
            if (graph.hasSecondaryOutcome()) {
                source.append(",{")
                        .append("\"from\":\"").append(graph.stepId())
                        .append(".secondary\",\"to\":\"end\"}");
            }
            if (graph.hasTail()) {
                source.append(",{")
                        .append("\"from\":\"").append(graph.tailId())
                        .append(".next\",\"to\":\"end\"}");
            }
        }
        return source.append("]}").toString();
    }

    private static String graphInputs(final GraphCase graph) {
        if (graph.id().endsWith("sparse")) {
            return "{\"second\":[\"context\",\"payload\",\"items\",100000]}";
        }
        if (graph.id().endsWith("order")) {
            return "{\"first\":[\"context\",\"payload\",\"parent\"],"
                    + "\"second\":[\"context\",\"payload\",\"parent\",\"child\"]}";
        }
        return switch (graph.id()) {
            case "nested-preinterrupted" -> nestedInputs("contract.nested.identity");
            case "nested-write" -> nestedInputs("contract.nested.write");
            case "nested-secondary-output" -> nestedInputs("contract.nested.secondary-output");
            case "nested-number-domain" -> nestedInputs("contract.nested.number-domain");
            case "nested-retain" -> nestedInputs("contract.nested.identity");
            case "nested-wrong-thread" -> nestedInputs("contract.nested.identity");
            case "array-descend" -> "{\"target\":[\"context\",\"payload\",\"items\",0,\"after\"]}";
            case "array-replace" -> "{\"target\":[\"context\",\"payload\",\"items\",0]}";
            case "array-sparse-existing" -> "{\"target\":[\"context\",\"payload\",\"items\",1026]}";
            case "array-scalar-conflict" -> "{\"target\":[\"context\",\"payload\",\"items\",0]}";
            case "array-later-read" ->
                    "{\"target\":[\"context\",\"payload\",\"items\",0,\"value\"]}";
            case "array-fork-copy" ->
                    "{\"target\":[\"context\",\"payload\",\"items\",0,\"seed\"]}";
            case "array-cache-invalidate" ->
                    "{\"target\":[\"context\",\"payload\",\"items\",0,\"value\"]}";
            case "indexed-scalar-missing" -> "{"
                    + "\"probe\":[\"context\",\"payload\",\"scalar\",0],"
                    + "\"observed\":[\"context\",\"payload\",\"observed\"]}";
            default -> "{}";
        };
    }

    private static String graphMappings(final GraphCase graph) {
        return switch (graph.id()) {
            case "receive-refinement" ->
                    ",\"receives\":{\"received\":[\"context\",\"payload\",\"value\"]}";
            case "secondary-output", "output-shape", "output-number-domain" ->
                    ",\"returns\":{\"value\":[\"context\",\"payload\",\"output\"]}";
            default -> "";
        };
    }

    private static String graphTailInputs(final GraphCase graph) {
        return switch (graph.id()) {
            case "array-later-read" -> "{\"observed\":[\"context\",\"payload\",\"observed\"]}";
            case "array-cache-invalidate" -> "{"
                    + "\"snapshot\":[\"context\",\"payload\",\"items\"],"
                    + "\"target\":[\"context\",\"payload\",\"items\",0,\"value\"]}";
            default -> "{\"first\":[\"context\",\"payload\",\"items\",0,\"first\"],"
                    + "\"second\":[\"context\",\"payload\",\"items\",0,\"second\"]}";
        };
    }

    private static String graphTailMappings(final GraphCase graph) {
        return "array-later-read".equals(graph.id())
                ? ",\"receives\":{\"received\":[\"context\",\"payload\",\"items\",0,\"value\"]}"
                : "";
    }

    private static String nestedInputs(final String use) {
        return "{\"steps\":[{\"use\":\"" + use + "\",\"inputs\":{}}]}";
    }

    private static String graphMode(final String id) {
        return switch (id) {
            case "nested-retain" -> "retain-nested";
            case "nested-late-invoke" -> "invoke-retained";
            default -> id.startsWith("nested-") ? "run-" + id : id;
        };
    }

    private static String graphContext(final String trigger) {
        return switch (trigger) {
            case "array-descend" ->
                    "{\"payload\":{\"value\":\"fault\",\"items\":[{\"before\":\"keep\"}]}}";
            case "array-replace" ->
                    "{\"payload\":{\"value\":\"fault\",\"items\":[\"before\"]}}";
            case "array-sparse-existing" ->
                    "{\"payload\":{\"value\":\"fault\",\"items\":[\"seed\"]}}";
            case "array-scalar-conflict" ->
                    "{\"payload\":{\"value\":\"fault\",\"items\":\"scalar\"}}";
            case "array-cache-invalidate" ->
                    "{\"payload\":{\"value\":\"fault\",\"items\":[{}]}}";
            case "indexed-scalar-missing" ->
                    "{\"payload\":{\"value\":\"fault\",\"scalar\":\"not-an-array\"}}";
            case "nested-number-domain" -> "{\"payload\":{\"value\":1}}";
            default -> "{\"payload\":{\"value\":\"fault\"}}";
        };
    }

    private static String graphPath(final String id) {
        int index = 1;
        for (final GraphCase graph : GRAPH_CASES) {
            if (graph.id().equals(id)) {
                return "nodes[" + (index + 1) + "]";
            }
            index += graph.hasTail() ? 3 : 2;
        }
        throw new IllegalArgumentException("Unknown graph case: " + id + ".");
    }

    private static String sourceProject(
            final String id,
            final String trigger,
            final String downstream
    ) {
        final String node = downstream.isEmpty()
                ? ""
                : ",{\"id\":\"probe\",\"use\":\"" + downstream + "\",\"inputs\":{}}";
        final String destination = downstream.isEmpty() ? "end" : "probe";
        final String link = downstream.isEmpty()
                ? ""
                : ",{\"from\":\"probe.next\",\"to\":\"end\"}";
        return """
                {"format":1,"id":"%s","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"%s","inputs":{},"examples":[
                    {"name":"safe","payload":{},"context":{"payload":{"value":"safe"}}}
                  ]}%s
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"%s"}%s
                ]}
                """.formatted(id, trigger, node, destination, link);
    }

    private static Path buildJar(
            final Path workspace,
            final String project,
            final List<StepDefinition> definitions
    ) throws IOException {
        try (GeneratedApplicationFixture ignored = GeneratedApplicationFixture.start(
                workspace,
                project,
                definitions,
                StepRuntimeContractProbe.class
        )) {
            return generatedJar(workspace);
        }
    }

    private static Path generatedJar(final Path workspace) throws IOException {
        try (var files = Files.find(
                workspace.resolve(".railix/build"),
                2,
                (path, attributes) -> attributes.isRegularFile()
                        && "application.jar".equals(path.getFileName().toString())
        )) {
            final List<Path> jars = files.toList();
            assertThat(jars).hasSize(1);
            return jars.getFirst();
        }
    }

    private static Path compileSourceLauncher(final Path workspace, final Path application) throws Exception {
        final Path source = workspace.resolve(
                "src/dev/nanonative/railix/core/project/StepRuntimeContractSourceLauncher.java"
        );
        final Path classes = workspace.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, SOURCE_LAUNCHER, StandardCharsets.UTF_8);
        final ProcessResult compilation = run(List.of(
                java("javac"),
                "-encoding",
                "UTF-8",
                "-cp",
                application.toString(),
                "-d",
                classes.toString(),
                source.toString()
        ));
        assertThat(compilation.exitCode()).as(compilation.output()).isZero();
        return classes;
    }

    private static ProcessResult run(final List<String> command) throws Exception {
        return run(command, false);
    }

    private static ProcessResult runJava(final List<String> command) throws Exception {
        return run(command, true);
    }

    private static ProcessResult run(final List<String> command, final boolean instrument) throws Exception {
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        final Process process = (instrument ? RailixPackageIT.instrumentJava(builder) : builder)
                .start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                throw new AssertionError("Conformance process did not exit within 20 seconds.");
            }
            final String output;
            try (var input = process.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
            return new ProcessResult(process.exitValue(), output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor();
            }
        }
    }

    private static String java(final String executable) {
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private record GraphCase(String id, boolean numericResult) {
        private String triggerUse() {
            return "contract.trigger." + id;
        }

        private String stepUse() {
            return "contract.step." + id;
        }

        private String stepId() {
            return id + "-step";
        }

        private boolean hasTail() {
            return "array-later-read".equals(id)
                    || "array-fork-copy".equals(id)
                    || "array-cache-invalidate".equals(id);
        }

        private boolean hasSecondaryOutcome() {
            return "secondary-output".equals(id) || "nested-secondary-output".equals(id);
        }

        private String tailUse() {
            return stepUse() + ".tail";
        }

        private String tailId() {
            return id + "-tail";
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
