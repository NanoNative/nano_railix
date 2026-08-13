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
import thirdparty.conformance.CompactFieldProgramSteps;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(120)
final class CompactFieldProgramGeneratedE2eTest {
    private static final String ACCEPTED = "accepted";
    private static final String PREVIEW = "preview";
    private static final String FAILED_OCCURRENCE = "failed-occurrence";
    private static final String NULL_ACCEPTED = "null-accepted";
    private static final String NULL_FALLBACK = "null-fallback";
    private static final String EMPTY_FALLBACK = "empty-fallback";
    private static final String FALSE_ACCEPTED = "false-accepted";
    private static final String ZERO_ACCEPTED = "zero-accepted";
    private static final String MISSING_FALLBACK = "missing-fallback";
    private static final String FIELD_COPY = "field-copy";
    private static final String UNRESOLVED = "unresolved";
    private static final String STOP_LATER = "stop-later";
    private static final String INTERRUPTED = "interrupted";
    private static final String FAULTED = "faulted";
    private static final String DEFAULT_CANDIDATES = """
            [
              {"option":"current","inputs":{},"when":{"transforms":[],"all":[[
                {"use":"value.equals","inputs":{"expected":"different"}}
              ]]}},
              {"option":"literal","inputs":{"literal":"fallback"},
               "when":{"transforms":[],"all":[]}}
            ]
            """;
    private static final String IDENTITY_STEPS = "[{\"use\":\"value.identity\",\"inputs\":{}}]";
    private static final List<Flow> FLOWS = List.of(
            new Flow(ACCEPTED, DEFAULT_CANDIDATES, IDENTITY_STEPS),
            new Flow(PREVIEW, DEFAULT_CANDIDATES, IDENTITY_STEPS),
            new Flow(FAILED_OCCURRENCE, """
                    [{"option":"field","inputs":{
                      "source":["context","payload","missing"]},"when":{"transforms":[],"all":[[
                      {"use":"value.fault","inputs":{}}]]}},
                     {"option":"literal","inputs":{"literal":"fault"},"when":{"transforms":[],"all":[[
                      {"use":"value.fault","inputs":{}}]]}}]
                    """, IDENTITY_STEPS),
            new Flow(NULL_ACCEPTED,
                    "[{\"option\":\"literal\",\"inputs\":{\"literal\":null}}]",
                    IDENTITY_STEPS),
            new Flow(NULL_FALLBACK, """
                    [{"option":"literal","inputs":{"literal":null},"when":{"transforms":[],"all":[[
                      {"use":"value.equals","inputs":{"expected":null}},
                      {"use":"boolean.not","inputs":{}}]]}},
                     {"option":"literal","inputs":{"literal":"fallback"}}]
                    """, IDENTITY_STEPS),
            new Flow(EMPTY_FALLBACK, """
                    [{"option":"current","inputs":{},"when":{"transforms":[],"all":[[
                      {"use":"value.equals","inputs":{"expected":""}},
                      {"use":"boolean.not","inputs":{}}]]}},
                     {"option":"literal","inputs":{"literal":"fallback"}}]
                    """, IDENTITY_STEPS),
            new Flow(FALSE_ACCEPTED,
                    "[{\"option\":\"literal\",\"inputs\":{\"literal\":false}}]",
                    IDENTITY_STEPS),
            new Flow(ZERO_ACCEPTED,
                    "[{\"option\":\"literal\",\"inputs\":{\"literal\":0}}]",
                    IDENTITY_STEPS),
            new Flow(MISSING_FALLBACK, """
                    [{"option":"field","inputs":{"source":["context","payload","missing"]}},
                     {"option":"literal","inputs":{"literal":"fallback"}}]
                    """, IDENTITY_STEPS),
            new Flow(FIELD_COPY, """
                    [{"option":"field","inputs":{
                      "source":["context","payload","alias"]}}]
                    """, IDENTITY_STEPS),
            new Flow(UNRESOLVED, """
                    [{"option":"field","inputs":{
                      "source":["context","payload","missing"]}}]
                    """, IDENTITY_STEPS),
            new Flow(STOP_LATER, """
                    [{"option":"literal","inputs":{"literal":"first"}},
                     {"option":"literal","inputs":{"literal":"second"},"when":{"transforms":[],"all":[[
                      {"use":"value.equals","inputs":{"expected":"second"}}]]}}]
                    """, IDENTITY_STEPS),
            new Flow(INTERRUPTED, """
                    [{"option":"current","inputs":{}},
                     {"option":"literal","inputs":{"literal":"fallback"},"when":{"transforms":[],"all":[[
                      {"use":"value.interrupt","inputs":{}}]]}}]
                    """, "[]"),
            new Flow(FAULTED, """
                    [{"option":"current","inputs":{},"when":{"transforms":[],"all":[[
                      {"use":"value.fault","inputs":{}}]]}}]
                    """, IDENTITY_STEPS)
    );

    private GeneratedApplicationFixture application;

    @BeforeAll
    void startGeneratedApplication(@TempDir final Path workspace) throws Exception {
        application = GeneratedApplicationFixture.start(
                workspace,
                project(),
                definitions(),
                CompactFieldProgramSteps.Trigger.class,
                CompactFieldProgramSteps.Identity.class,
                CompactFieldProgramSteps.Change.class,
                CompactFieldProgramSteps.Interrupt.class,
                CompactFieldProgramSteps.Fault.class
        );
    }

    @AfterAll
    void stopGeneratedApplication() {
        if (application != null) {
            application.close();
        }
    }

    @Test
    void firstCandidateAcceptedByItsPredicateProgramBecomesTheOrdinaryStepValue() throws Exception {
        final RailixValue.ObjectValue body = succeeded(application.runTest(
                ACCEPTED,
                "{\"payload\":{\"name\":\"existing\"}}"
        ));

        assertThat(payload(body).values().get("name")).isEqualTo(RailixValue.string("fallback"));
        assertThat(steps(body).values()).containsExactly(
                step("value.equals", "ok"),
                step("value.identity", "ok"),
                step(change(ACCEPTED), "next")
        );
    }

    @Test
    void previewReportsWhichOrderedCandidateSuppliedTheValue() throws Exception {
        final RailixValue.ObjectValue body = succeeded(application.preview(
                PREVIEW,
                change(PREVIEW),
                "{\"payload\":{\"name\":\"existing\"}}"
        ));

        assertThat(preview(body).values().get("selected_candidates")).isEqualTo(RailixValue.object(Map.of(
                "nodes[4].inputs.value", RailixValue.number(1)
        )));
    }

    @Test
    void failedPredicateRuntimeDiagnosticNamesTheExecutedCandidateOccurrence() throws Exception {
        final DevelopmentApplication.Response response = application.preview(
                FAILED_OCCURRENCE,
                change(FAILED_OCCURRENCE),
                "{\"payload\":{\"name\":\"existing\"}}"
        );
        final RailixValue.ObjectValue body = body(response);

        assertThat(response.status()).isEqualTo(500);
        assertThat(body.values().get("status")).isEqualTo(RailixValue.string("failed"));
        assertFailure(body, "value.fault");
        assertStage(body, "nodes[6].inputs.value[1].when.all[0][0]", "value.fault", "failed");
    }

    @Test
    void canonicalJsonNullIsAcceptedWhenNoPredicateRejectsIt() throws Exception {
        final RailixValue.ObjectValue payload = productionPayload(
                NULL_ACCEPTED,
                "{\"payload\":{\"name\":\"existing\"}}"
        );

        assertThat(payload.values().get("name")).isEqualTo(RailixValue.nullValue());
    }

    @Test
    void projectPredicateCanRejectCanonicalJsonNullAndUseTheNextCandidate() throws Exception {
        final RailixValue.ObjectValue payload = productionPayload(
                NULL_FALLBACK,
                "{\"payload\":{\"name\":\"existing\"}}"
        );

        assertThat(payload.values().get("name")).isEqualTo(RailixValue.string("fallback"));
    }

    @Test
    void projectPredicateCanDefineEmptyTextAndUseTheNextCandidate() throws Exception {
        final RailixValue.ObjectValue payload = productionPayload(
                EMPTY_FALLBACK,
                "{\"payload\":{\"name\":\"\"}}"
        );

        assertThat(payload.values().get("name")).isEqualTo(RailixValue.string("fallback"));
    }

    @Test
    void booleanFalseIsAcceptedWhenNoPredicateRejectsIt() throws Exception {
        final RailixValue.ObjectValue payload = productionPayload(FALSE_ACCEPTED, "{\"payload\":{}}");

        assertThat(payload.values().get("name")).isEqualTo(RailixValue.bool(false));
    }

    @Test
    void numberZeroIsAcceptedWhenNoPredicateRejectsIt() throws Exception {
        final RailixValue.ObjectValue payload = productionPayload(ZERO_ACCEPTED, "{\"payload\":{}}");

        assertThat(payload.values().get("name")).isEqualTo(RailixValue.number(0));
    }

    @Test
    void missingFieldCandidateFallsThroughToTheNextLiteral() throws Exception {
        final RailixValue.ObjectValue payload = productionPayload(MISSING_FALLBACK, "{\"payload\":{}}");

        assertThat(payload.values().get("name")).isEqualTo(RailixValue.string("fallback"));
    }

    @Test
    void anotherFieldCandidateCopiesTheCurrentValueWithoutCreatingALiveAlias() throws Exception {
        final RailixValue.ObjectValue payload = productionPayload(
                FIELD_COPY,
                "{\"payload\":{\"alias\":\"copied\",\"name\":\"existing\"}}"
        );

        assertThat(payload.values()).containsEntry("alias", RailixValue.string("copied"))
                .containsEntry("name", RailixValue.string("copied"));
    }

    @Test
    void allUnresolvedCandidatesSelectTheExplicitOutcomeWithoutWriting() throws Exception {
        final RailixValue.ObjectValue body = succeeded(application.runTest(
                UNRESOLVED,
                "{\"payload\":{\"name\":\"unchanged\"}}"
        ));

        assertThat(payload(body).values().get("name")).isEqualTo(RailixValue.string("unchanged"));
        assertThat(steps(body).values()).containsExactly(step(change(UNRESOLVED), "unresolved"));
    }

    @Test
    void firstAcceptedCandidateStopsEvaluationOfLaterPredicates() throws Exception {
        final RailixValue.ObjectValue body = succeeded(application.runTest(STOP_LATER, "{\"payload\":{}}"));

        assertThat(payload(body).values().get("name")).isEqualTo(RailixValue.string("first"));
        assertThat(steps(body).values()).containsExactly(
                step("value.identity", "ok"),
                step(change(STOP_LATER), "next")
        );
    }

    @Test
    void interruptionAfterAPredicateHandlerCancelsBeforeTheOuterStepWrites() throws Exception {
        final DevelopmentApplication.Response response = application.preview(
                INTERRUPTED,
                change(INTERRUPTED),
                "{\"payload\":{}}"
        );
        final RailixValue.ObjectValue body = body(response);

        assertThat(response.status()).isEqualTo(409);
        assertThat(body.values().get("status")).isEqualTo(RailixValue.string("cancelled"));
        assertThat(steps(body).values()).isEmpty();
        assertStage(body, "nodes[26].inputs.value[1].when.all[0][0]", "value.interrupt", "cancelled");
    }

    @Test
    void predicateImplementationFaultStopsBeforeTheOuterStepRuns() throws Exception {
        final DevelopmentApplication.Response response = application.preview(
                FAULTED,
                change(FAULTED),
                "{\"payload\":{\"name\":\"fault\"}}"
        );
        final RailixValue.ObjectValue body = body(response);

        assertThat(response.status()).isEqualTo(500);
        assertThat(body.values().get("status")).isEqualTo(RailixValue.string("failed"));
        assertFailure(body, "value.fault");
        assertThat(steps(body).values()).isEmpty();
        assertStage(body, "nodes[28].inputs.value[0].when.all[0][0]", "value.fault", "failed");
    }

    private RailixValue.ObjectValue productionPayload(final String trigger, final String context) throws Exception {
        final RailixValue.ObjectValue body = succeeded(application.run(trigger, context));
        assertThat(steps(body).values()).isEmpty();
        return payload(body);
    }

    private static RailixValue.ObjectValue succeeded(final DevelopmentApplication.Response response) {
        final RailixValue.ObjectValue body = body(response);
        assertThat(response.status()).isEqualTo(200);
        assertThat(body.values().get("status")).isEqualTo(RailixValue.string("succeeded"));
        return body;
    }

    private static RailixValue.ObjectValue body(final DevelopmentApplication.Response response) {
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(response.body())).value();
    }

    private static RailixValue.ObjectValue payload(final RailixValue.ObjectValue body) {
        final RailixValue.ObjectValue context = (RailixValue.ObjectValue) body.values().get("context");
        return (RailixValue.ObjectValue) context.values().get("payload");
    }

    private static RailixValue.ArrayValue steps(final RailixValue.ObjectValue body) {
        return (RailixValue.ArrayValue) body.values().get("steps");
    }

    private static RailixValue.ObjectValue preview(final RailixValue.ObjectValue body) {
        return (RailixValue.ObjectValue) body.values().get("preview");
    }

    private static RailixValue.ObjectValue step(final String id, final String outcome) {
        return RailixValue.object(Map.of(
                "id", RailixValue.string(id),
                "outcome", RailixValue.string(outcome)
        ));
    }

    private static void assertFailure(final RailixValue.ObjectValue body, final String step) {
        assertThat(body.values().get("failure")).isEqualTo(RailixValue.object(Map.of(
                "code", RailixValue.string("STEP_IMPLEMENTATION_FAULT"),
                "message", RailixValue.string("Step implementation threw an unexpected exception."),
                "step", RailixValue.string(step)
        )));
    }

    private static void assertStage(
            final RailixValue.ObjectValue body,
            final String invocation,
            final String use,
            final String status
    ) {
        final RailixValue.ArrayValue stages = (RailixValue.ArrayValue) preview(body).values().get("stages");
        assertThat(stages.values()).singleElement().satisfies(value -> {
            final RailixValue.ObjectValue stage = (RailixValue.ObjectValue) value;
            assertThat(stage.values().get("invocation")).isEqualTo(RailixValue.string(invocation));
            assertThat(stage.values().get("use")).isEqualTo(RailixValue.string(use));
            assertThat(stage.values().get("status")).isEqualTo(RailixValue.string(status));
        });
    }

    private static List<StepDefinition> definitions() {
        final List<StepDefinition> definitions = new ArrayList<>();
        FLOWS.forEach(flow -> definitions.add(StepDefinition.named(trigger(flow.id()), "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("compact.source." + flow.id())
                .run(CompactFieldProgramSteps.Trigger.class)));
        definitions.add(StepDefinition.named("value.identity", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.ANY)
                .run(CompactFieldProgramSteps.Identity.class));
        definitions.add(StepDefinition.named("value.interrupt", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.BOOLEAN)
                .run(CompactFieldProgramSteps.Interrupt.class));
        definitions.add(StepDefinition.named("value.fault", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.BOOLEAN)
                .run(CompactFieldProgramSteps.Fault.class));
        definitions.add(StepDefinition.named("compact.change-field", "1")
                .input("field", StepDefinition.Input.path(READ_WRITE))
                .input("value", StepDefinition.Input.candidates(
                        StepDefinition.Input.option("current").fromParent("field"),
                        StepDefinition.Input.option("field")
                                .input("source", StepDefinition.Input.path(READ))
                                .fromOwned("source"),
                        StepDefinition.Input.option("literal")
                                .input("literal", StepDefinition.Input.json(ValueShape.ANY))
                                .fromOwned("literal")
                ))
                .input("steps", StepDefinition.Input.steps(
                        StepDefinition.ValueSource.from("value").onMissing("unresolved")
                ))
                .run(CompactFieldProgramSteps.Change.class));
        return List.copyOf(definitions);
    }

    private static String project() {
        final StringBuilder nodes = new StringBuilder("{\"id\":\"app\",\"use\":\"railix.app\",\"inputs\":{}}");
        final StringBuilder links = new StringBuilder();
        for (final Flow flow : FLOWS) {
            nodes.append(',').append("""
                    {"id":"%1$s","use":"%2$s","inputs":{},"examples":[
                      {"name":"example","payload":{},"context":{
                        "payload":{"name":"existing","alias":"copied"}}}
                    ]},
                    {"id":"%3$s","use":"compact.change-field","inputs":{
                      "field":["context","payload","name"],
                      "value":%4$s,
                      "steps":%5$s
                    }}
                    """.formatted(flow.id(), trigger(flow.id()), change(flow.id()), flow.candidates(), flow.steps()));
            if (!links.isEmpty()) {
                links.append(',');
            }
            links.append("""
                    {"from":"app.start","to":"%1$s"},
                    {"from":"%1$s.next","to":"%2$s"},
                    {"from":"%2$s.next","to":"end"},
                    {"from":"%2$s.unresolved","to":"end"}
                    """.formatted(flow.id(), change(flow.id())));
        }
        return "{\"format\":1,\"id\":\"compact-field-generated\",\"nodes\":["
                + nodes + "],\"links\":[" + links + "]}";
    }

    private static String trigger(final String id) {
        return "compact.trigger." + id;
    }

    private static String change(final String id) {
        return id + "-change";
    }

    private record Flow(String id, String candidates, String steps) {
    }
}
