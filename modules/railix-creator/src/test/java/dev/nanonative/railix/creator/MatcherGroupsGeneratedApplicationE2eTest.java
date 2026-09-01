package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import thirdparty.conformance.MatcherConformanceSteps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(120)
final class MatcherGroupsGeneratedApplicationE2eTest {
    private static final String PRESENT = "[[{\"option\":\"current\",\"inputs\":{},"
            + "\"when\":{\"transforms\":[],\"all\":[]}}]]";
    private static final String STRUCTURED = "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"railix\"},"
            + "\"when\":{\"transforms\":[{\"use\":\"text.length\",\"inputs\":{}}],\"all\":["
            + "[{\"use\":\"number.greater-than\",\"inputs\":{\"than\":1}}],"
            + "[{\"use\":\"number.less-than\",\"inputs\":{\"than\":10}}]]}}]]";
    private static final List<Flow> FLOWS = List.of(
            new Flow("missing-predicate", "[[{\"option\":\"current\",\"inputs\":{},\"when\":{"
                    + "\"transforms\":[],\"all\":[[{\"use\":\"value.fault\",\"inputs\":{}}]]}}]]"),
            new Flow("missing-structured", "[[{\"option\":\"current\",\"inputs\":{},\"when\":{"
                    + "\"transforms\":[{\"use\":\"value.non-boolean\",\"inputs\":{}}],\"all\":[["
                    + "{\"use\":\"value.fault\",\"inputs\":{}}]]}}]]"),
            new Flow("present", PRESENT),
            new Flow("and-short-circuit", "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"one\"},"
                    + "\"when\":{\"transforms\":[],\"all\":[[{\"use\":\"value.equals\","
                    + "\"inputs\":{\"expected\":\"two\"}}]]}},"
                    + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"fault\"},\"when\":{"
                    + "\"transforms\":[],\"all\":[[{\"use\":\"value.fault\",\"inputs\":{}}]]}}]]"),
            new Flow("and-true", "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"one\"},"
                    + "\"when\":{\"transforms\":[],\"all\":[[{\"use\":\"value.equals\","
                    + "\"inputs\":{\"expected\":\"one\"}}]]}},"
                    + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"two\"},\"when\":{"
                    + "\"transforms\":[],\"all\":[[{\"use\":\"value.equals\","
                    + "\"inputs\":{\"expected\":\"two\"}}]]}}]]"),
            new Flow("and-later-false", "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"one\"},"
                    + "\"when\":{\"transforms\":[],\"all\":[[{\"use\":\"value.equals\","
                    + "\"inputs\":{\"expected\":\"one\"}}]]}},"
                    + "{\"option\":\"literal\",\"inputs\":{\"literal\":\"two\"},\"when\":{"
                    + "\"transforms\":[],\"all\":[[{\"use\":\"value.equals\","
                    + "\"inputs\":{\"expected\":\"other\"}}]]}}]]"),
            new Flow("or-next", "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"one\"},\"when\":{"
                    + "\"transforms\":[],\"all\":[[{\"use\":\"value.equals\","
                    + "\"inputs\":{\"expected\":\"two\"}}]]}}],[{\"option\":\"literal\","
                    + "\"inputs\":{\"literal\":\"two\"},\"when\":{\"transforms\":[],\"all\":[]}}]]"),
            new Flow("or-short-circuit", "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"one\"},"
                    + "\"when\":{\"transforms\":[],\"all\":[]}}],[{\"option\":\"literal\","
                    + "\"inputs\":{\"literal\":\"fault\"},\"when\":{\"transforms\":[],\"all\":[["
                    + "{\"use\":\"value.fault\",\"inputs\":{}}]]}}]]"),
            new Flow("or-false", "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"one\"},\"when\":{"
                    + "\"transforms\":[],\"all\":[[{\"use\":\"value.equals\","
                    + "\"inputs\":{\"expected\":\"other\"}}]]}}],[{\"option\":\"literal\","
                    + "\"inputs\":{\"literal\":\"two\"},\"when\":{\"transforms\":[],\"all\":[["
                    + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"other\"}}]]}}]]"),
            new Flow("authored-order", "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"value\"},"
                    + "\"when\":{\"transforms\":[],\"all\":[["
                    + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"value\"}},"
                    + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":true}}]]}}]]"),
            new Flow("structured", STRUCTURED),
            new Flow("false-first", "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"railix\"},\"when\":{"
                    + "\"transforms\":[{\"use\":\"text.length\",\"inputs\":{}}],\"all\":["
                    + "[{\"use\":\"number.less-than\",\"inputs\":{\"than\":1}}],"
                    + "[{\"use\":\"value.fault\",\"inputs\":{}}]]}}]]"),
            new Flow("false-additional", "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"railix\"},"
                    + "\"when\":{\"transforms\":[{\"use\":\"text.length\",\"inputs\":{}}],\"all\":["
                    + "[{\"use\":\"number.greater-than\",\"inputs\":{\"than\":1}}],"
                    + "[{\"use\":\"number.less-than\",\"inputs\":{\"than\":5}}]]}}]]"),
            new Flow("empty", "[]"),
            new Flow("invalid-output", "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"value\"},"
                    + "\"when\":{\"transforms\":[],\"all\":[["
                    + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"value\"}},"
                    + "{\"use\":\"value.invalid\",\"inputs\":{}}]]}}]]"),
            new Flow("input-rejection", "[[{\"option\":\"current\",\"inputs\":{},\"when\":{"
                    + "\"transforms\":[{\"use\":\"value.non-boolean\",\"inputs\":{}}],\"all\":[["
                    + "{\"use\":\"value.text-boolean\",\"inputs\":{}}]]}}]]"),
            new Flow("predicate-fault", "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"fault\"},"
                    + "\"when\":{\"transforms\":[],\"all\":[["
                    + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"fault\"}},"
                    + "{\"use\":\"value.fault\",\"inputs\":{}}]]}}]]"),
            new Flow("transform-fault", "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"fault\"},\"when\":{"
                    + "\"transforms\":[{\"use\":\"value.fault\",\"inputs\":{}}],\"all\":[["
                    + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":true}}]]}}]]"),
            new Flow("additional-fault", "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"railix\"},\"when\":{"
                    + "\"transforms\":[{\"use\":\"text.length\",\"inputs\":{}}],\"all\":[["
                    + "{\"use\":\"number.greater-than\",\"inputs\":{\"than\":1}}],["
                    + "{\"use\":\"value.fault\",\"inputs\":{}}]]}}]]"),
            new Flow("predicate-interrupt", "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"value\"},"
                    + "\"when\":{\"transforms\":[],\"all\":[["
                    + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":\"value\"}},"
                    + "{\"use\":\"value.interrupt\",\"inputs\":{}}]]}}]]"),
            new Flow("transform-interrupt", "[[{\"option\":\"literal\",\"inputs\":{\"literal\":\"value\"},\"when\":{"
                    + "\"transforms\":[{\"use\":\"value.interrupt\",\"inputs\":{}}],\"all\":[["
                    + "{\"use\":\"value.equals\",\"inputs\":{\"expected\":true}}]]}}]]")
    );

    private GeneratedApplicationFixture application;

    @BeforeAll
    void startGeneratedApplication(@TempDir final Path workspace) throws Exception {
        application = GeneratedApplicationFixture.start(
                workspace,
                project(),
                definitions(),
                MatcherConformanceSteps.Trigger.class,
                MatcherConformanceSteps.Identity.class,
                MatcherConformanceSteps.True.class,
                MatcherConformanceSteps.Fault.class,
                MatcherConformanceSteps.Interrupt.class,
                MatcherConformanceSteps.Invalid.class,
                MatcherConformanceSteps.Probe.class
        );
    }

    @AfterAll
    void stopGeneratedApplication() {
        if (application != null) {
            application.close();
        }
    }

    @Test
    void missingMatcherPathIsFalseWithoutRunningItsPredicate() throws Exception {
        assertSucceeded("missing-predicate", Map.of(), false);
    }

    @Test
    void missingMatcherPathSkipsStructuredTransformsAndPredicates() throws Exception {
        assertSucceeded("missing-structured", Map.of(), false);
    }

    @Test
    void canonicalJsonNullIsAPresentMatcherValue() throws Exception {
        assertPresent(RailixValue.nullValue());
    }

    @Test
    void booleanFalseIsAPresentMatcherValue() throws Exception {
        assertPresent(RailixValue.bool(false));
    }

    @Test
    void numberZeroIsAPresentMatcherValue() throws Exception {
        assertPresent(RailixValue.number(0));
    }

    @Test
    void emptyStringIsAPresentMatcherValue() throws Exception {
        assertPresent(RailixValue.string(""));
    }

    @Test
    void emptyArrayIsAPresentMatcherValue() throws Exception {
        assertPresent(RailixValue.array(List.of()));
    }

    @Test
    void emptyObjectIsAPresentMatcherValue() throws Exception {
        assertPresent(RailixValue.object(Map.of()));
    }

    @Test
    void andShortCircuitsAfterTheFirstFalseMatcher() throws Exception {
        assertSucceeded("and-short-circuit", Map.of(), false, "value.equals");
    }

    @Test
    void andGroupMatchesWhenEveryMatcherIsTrue() throws Exception {
        assertSucceeded("and-true", Map.of(), true, "value.equals", "value.equals");
    }

    @Test
    void andGroupIsFalseWhenALaterMatcherIsFalse() throws Exception {
        assertSucceeded("and-later-false", Map.of(), false, "value.equals", "value.equals");
    }

    @Test
    void falseGroupContinuesToTheNextOrGroup() throws Exception {
        assertSucceeded("or-next", Map.of(), true, "value.equals");
    }

    @Test
    void trueGroupShortCircuitsLaterOrGroups() throws Exception {
        assertSucceeded("or-short-circuit", Map.of(), true);
    }

    @Test
    void orGroupsAreFalseWhenEveryGroupIsFalse() throws Exception {
        assertSucceeded("or-false", Map.of(), false, "value.equals", "value.equals");
    }

    @Test
    void matcherRunsMultipleUnaryStepsInAuthoredOrder() throws Exception {
        assertSucceeded("authored-order", Map.of(), true, "value.equals", "value.equals");
    }

    @Test
    void sharedTransformFeedsEveryAuthoredPredicateOnce() throws Exception {
        assertSucceeded(
                "structured",
                Map.of(),
                true,
                "text.length",
                "number.greater-than",
                "number.less-than"
        );
    }

    @Test
    void traceSeparatesSharedTransformAndMatcherStages() throws Exception {
        final List<RailixValue.ObjectValue> trace = trace("structured", Map.of());

        assertThat(stages(trace, "structured")).containsExactly(
                new Stage(
                        probe("structured") + ".inputs.matches[0][0].when.transforms[0]",
                        "text.length",
                        "succeeded",
                        RailixValue.string("railix"),
                        RailixValue.number(6)
                ),
                new Stage(
                        probe("structured") + ".inputs.matches[0][0].when.all[0][0]",
                        "number.greater-than",
                        "succeeded",
                        RailixValue.number(6),
                        RailixValue.bool(true)
                ),
                new Stage(
                        probe("structured") + ".inputs.matches[0][0].when.all[1][0]",
                        "number.less-than",
                        "succeeded",
                        RailixValue.number(6),
                        RailixValue.bool(true)
                )
        );
    }

    @Test
    void falseFirstPredicateSkipsAdditionalPredicates() throws Exception {
        assertSucceeded("false-first", Map.of(), false, "text.length", "number.less-than");
    }

    @Test
    void falseAdditionalPredicateRejectsTheMatcher() throws Exception {
        assertSucceeded(
                "false-additional",
                Map.of(),
                false,
                "text.length",
                "number.greater-than",
                "number.less-than"
        );
    }

    @Test
    void emptyOuterMatcherGroupsResolveFalse() throws Exception {
        assertSucceeded("empty", Map.of(), false);
    }

    @Test
    void repeatedRunsDoNotRetainMatcherState() throws Exception {
        assertThat(match(run("present", Map.of("source", RailixValue.string("first"))))).isEqualTo(RailixValue.bool(true));
        assertThat(match(run("present", Map.of()))).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void concurrentRunsDoNotShareMatcherState() throws Exception {
        final List<Callable<Boolean>> calls = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            final boolean present = index % 2 == 0;
            calls.add(() -> match(run(
                    "present",
                    present ? Map.of("source", RailixValue.number(0)) : Map.of()
            )).equals(RailixValue.bool(present)));
        }

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(executor.invokeAll(calls).stream().map(future -> {
                try {
                    return future.get();
                } catch (final Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList()).containsOnly(true);
        }
    }

    @Test
    void nestedPredicateInvalidOutputRemainsFailed() throws Exception {
        assertFailed(
                trace("invalid-output", Map.of()),
                new Failure(
                        "STEP_OUTPUT_INVALID",
                        "Nested Step did not return its one declared compatible value.",
                        "value.invalid"
                ),
                List.of(
                        new StepExecution("value.equals", "ok"),
                        new StepExecution("value.invalid", "ok")
                )
        );
    }

    @Test
    void nestedPredicateInputRejectionRemainsRejected() throws Exception {
        final List<RailixValue.ObjectValue> trace = trace(
                "input-rejection",
                Map.of("source", RailixValue.number(0))
        );
        final RailixValue.ObjectValue result = terminal(trace);

        assertThat(text(result, "status")).isEqualTo("rejected");
        assertThat(diagnostics(result)).containsExactly(new Rejection(
                "RUN_NESTED_INPUT_INCOMPATIBLE",
                "Nested Step value.text-boolean requires string but receives number.",
                probePath("input-rejection") + ".inputs.matches[0][0].when.all[0][0]"
        ));
        assertThat(steps(trace)).containsExactly(new StepExecution("value.non-boolean", "ok"));
    }

    @Test
    void nestedPredicateFaultRemainsFailed() throws Exception {
        assertFailed(
                trace("predicate-fault", Map.of()),
                new Failure(
                        "STEP_IMPLEMENTATION_FAULT",
                        "Step implementation threw an unexpected exception.",
                        "value.fault"
                ),
                List.of(new StepExecution("value.equals", "ok"))
        );
    }

    @Test
    void structuredTransformFaultRemainsFailed() throws Exception {
        assertFailed(
                trace("transform-fault", Map.of()),
                new Failure(
                        "STEP_IMPLEMENTATION_FAULT",
                        "Step implementation threw an unexpected exception.",
                        "value.fault"
                ),
                List.of()
        );
    }

    @Test
    void additionalStructuredPredicateFaultRemainsFailed() throws Exception {
        assertFailed(
                trace("additional-fault", Map.of()),
                new Failure(
                        "STEP_IMPLEMENTATION_FAULT",
                        "Step implementation threw an unexpected exception.",
                        "value.fault"
                ),
                List.of(
                        new StepExecution("text.length", "ok"),
                        new StepExecution("number.greater-than", "ok")
                )
        );
    }

    @Test
    void nestedPredicateInterruptionRemainsCancelled() throws Exception {
        assertCancelled(
                trace("predicate-interrupt", Map.of()),
                List.of(
                        new StepExecution("value.equals", "ok"),
                        new StepExecution("value.interrupt", "ok")
                )
        );
    }

    @Test
    void structuredTransformInterruptionRemainsCancelled() throws Exception {
        assertCancelled(
                trace("transform-interrupt", Map.of()),
                List.of(new StepExecution("value.interrupt", "ok"))
        );
    }

    private void assertPresent(final RailixValue value) throws IOException {
        assertSucceeded("present", Map.of("source", value), true);
    }

    private void assertSucceeded(
            final String flow,
            final Map<String, RailixValue> payload,
            final boolean expected,
            final String... nestedSteps
    ) throws IOException {
        final List<RailixValue.ObjectValue> trace = trace(flow, payload);
        final RailixValue.ObjectValue result = terminal(trace);
        final List<StepExecution> expectedSteps = new ArrayList<>(nestedSteps.length + 1);
        for (final String nested : nestedSteps) {
            expectedSteps.add(new StepExecution(nested, "ok"));
        }
        expectedSteps.add(new StepExecution(probe(flow), "next"));

        assertThat(text(result, "status")).isEqualTo("succeeded");
        assertThat(match(result)).isEqualTo(RailixValue.bool(expected));
        assertThat(steps(trace)).containsExactlyElementsOf(expectedSteps);
    }

    private static void assertFailed(
            final List<RailixValue.ObjectValue> trace,
            final Failure expected,
            final List<StepExecution> expectedSteps
    ) {
        final RailixValue.ObjectValue result = terminal(trace);
        final RailixValue.ObjectValue failure = object(result, "failure");

        assertThat(text(result, "status")).isEqualTo("failed");
        assertThat(new Failure(text(failure, "code"), text(failure, "message"), text(failure, "step")))
                .isEqualTo(expected);
        assertThat(steps(trace)).containsExactlyElementsOf(expectedSteps);
    }

    private static void assertCancelled(
            final List<RailixValue.ObjectValue> trace,
            final List<StepExecution> expectedSteps
    ) {
        final RailixValue.ObjectValue result = terminal(trace);

        assertThat(text(result, "status")).isEqualTo("cancelled");
        assertThat(steps(trace)).containsExactlyElementsOf(expectedSteps);
    }

    private List<RailixValue.ObjectValue> trace(
            final String trigger,
            final Map<String, RailixValue> payload
    ) throws IOException {
        return application.trace(trigger, context(payload));
    }

    private DevelopmentApplication.Response run(
            final String trigger,
            final Map<String, RailixValue> payload
    ) throws IOException {
        return application.runTest(trigger, context(payload));
    }

    private static String context(final Map<String, RailixValue> payload) {
        return RailixJson.write(RailixValue.object(Map.of("payload", RailixValue.object(payload))));
    }

    private static RailixValue match(final DevelopmentApplication.Response response) {
        assertThat(response.status()).isEqualTo(200);
        return match(body(response));
    }

    private static RailixValue match(final RailixValue.ObjectValue result) {
        return ((RailixValue.ObjectValue) object(result, "context").values().get("payload"))
                .values().get("match");
    }

    private static RailixValue.ObjectValue body(final DevelopmentApplication.Response response) {
        final RailixJson.Result parsed = RailixJson.parse(response.body());
        assertThat(parsed).isInstanceOf(RailixJson.Parsed.class);
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) parsed).value();
    }

    private static RailixValue.ObjectValue terminal(final List<RailixValue.ObjectValue> trace) {
        assertThat(trace).isNotEmpty();
        final RailixValue.ObjectValue result = trace.getLast();
        assertThat(result.values().get("type")).isEqualTo(RailixValue.string("result"));
        return result;
    }

    private static RailixValue.ObjectValue object(final RailixValue.ObjectValue owner, final String field) {
        return (RailixValue.ObjectValue) owner.values().get(field);
    }

    private static String text(final RailixValue.ObjectValue owner, final String field) {
        return ((RailixValue.StringValue) owner.values().get(field)).value();
    }

    private static List<StepExecution> steps(final List<RailixValue.ObjectValue> trace) {
        return trace.stream()
                .filter(event -> RailixValue.string("step_result").equals(event.values().get("type")))
                .filter(event -> RailixValue.string("succeeded").equals(event.values().get("status")))
                .map(event -> new StepExecution(
                        text(event, "id").contains(".inputs.") ? text(event, "use") : text(event, "id"),
                        text(event, "outcome")
                ))
                .toList();
    }

    private static List<Rejection> diagnostics(final RailixValue.ObjectValue result) {
        return ((RailixValue.ArrayValue) result.values().get("diagnostics")).values().stream()
                .map(RailixValue.ObjectValue.class::cast)
                .map(diagnostic -> new Rejection(
                        text(diagnostic, "code"),
                        text(diagnostic, "message"),
                        text(diagnostic, "path")
                ))
                .toList();
    }

    private static List<Stage> stages(
            final List<RailixValue.ObjectValue> trace,
            final String flow
    ) {
        final String prefix = probe(flow) + ".inputs.";
        return trace.stream()
                .filter(event -> RailixValue.string("step_result").equals(event.values().get("type")))
                .filter(event -> text(event, "id").startsWith(prefix))
                .map(event -> new Stage(
                        text(event, "id"),
                        text(event, "use"),
                        text(event, "status"),
                        object(event, "inputs").values().get("value"),
                        object(event, "returns").values().get("value")
                ))
                .toList();
    }

    private static List<StepDefinition> definitions() {
        final List<StepDefinition> definitions = new ArrayList<>();
        FLOWS.forEach(flow -> definitions.add(StepDefinition.named(trigger(flow.id()), "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("matcher.source." + flow.id().replace('-', '.'))
                .result("result", ValueShape.ANY, RailixValue.nullValue())
                .run(MatcherConformanceSteps.Trigger.class)));
        definitions.add(StepDefinition.named("value.non-boolean", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.ANY)
                .run(MatcherConformanceSteps.Identity.class));
        definitions.add(StepDefinition.named("value.text-boolean", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.BOOLEAN)
                .run(MatcherConformanceSteps.True.class));
        definitions.add(predicate("value.fault").run(MatcherConformanceSteps.Fault.class));
        definitions.add(predicate("value.interrupt").run(MatcherConformanceSteps.Interrupt.class));
        definitions.add(predicate("value.invalid").run(MatcherConformanceSteps.Invalid.class));
        definitions.add(StepDefinition.named("matcher.probe", "1")
                .input("source", StepDefinition.Input.path(READ)
                        .defaultPath("context", "payload", "source"))
                .input("matches", StepDefinition.Input.matcherGroups(
                        StepDefinition.Input.option("current").fromParent("source"),
                        StepDefinition.Input.option("literal")
                                .input("literal", StepDefinition.Input.json(ValueShape.ANY))
                                .fromOwned("literal")
                ))
                .input("target", StepDefinition.Input.path(WRITE)
                        .defaultPath("context", "payload", "match"))
                .run(MatcherConformanceSteps.Probe.class));
        return List.copyOf(definitions);
    }

    private static StepDefinition.Builder predicate(final String id) {
        return StepDefinition.named(id, "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.BOOLEAN);
    }

    private static String project() {
        final String nodes = FLOWS.stream().map(flow -> """
                {"id":"%1$s","use":"%2$s","inputs":{},"examples":[
                  {"name":"example","payload":{}}
                ]},
                {"id":"%3$s","use":"matcher.probe","inputs":{"matches":%4$s}}
                """.formatted(flow.id(), trigger(flow.id()), probe(flow.id()), flow.groups()))
                .collect(Collectors.joining(",\n"));
        final String links = FLOWS.stream().map(flow -> """
                {"from":"app.start","to":"%1$s"},
                {"from":"%1$s.next","to":"%2$s"},
                {"from":"%2$s.next","to":"end"}
                """.formatted(flow.id(), probe(flow.id())))
                .collect(Collectors.joining(",\n"));
        return """
                {"format":1,"id":"matcher-groups-runtime","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  %s
                ],"links":[
                  %s
                ]}
                """.formatted(nodes, links);
    }

    private static String trigger(final String flow) {
        return "matcher.trigger." + flow;
    }

    private static String probe(final String flow) {
        return flow + "-probe";
    }

    private static String probePath(final String flow) {
        for (int index = 0; index < FLOWS.size(); index++) {
            if (FLOWS.get(index).id().equals(flow)) {
                return "nodes[" + (2 + index * 2) + "]";
            }
        }
        throw new IllegalArgumentException("Unknown matcher flow: " + flow + ".");
    }

    private record Flow(String id, String groups) {
    }

    private record StepExecution(String id, String outcome) {
    }

    private record Failure(String code, String message, String step) {
    }

    private record Rejection(String code, String message, String path) {
    }

    private record Stage(
            String invocation,
            String use,
            String status,
            RailixValue input,
            RailixValue output
    ) {
    }
}
