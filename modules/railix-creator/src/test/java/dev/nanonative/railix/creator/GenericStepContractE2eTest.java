package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import thirdparty.conformance.GenericContractSteps;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ_WRITE;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(90)
final class GenericStepContractE2eTest {
    private GeneratedApplicationFixture application;

    @BeforeAll
    void startGeneratedApplication(@TempDir final Path workspace) throws Exception {
        application = GeneratedApplicationFixture.start(
                workspace,
                project(),
                definitions(),
                GenericContractSteps.Trigger.class,
                GenericContractSteps.Operation.class,
                GenericContractSteps.Option.class
        );
    }

    @AfterAll
    void stopGeneratedApplication() {
        if (application != null) {
            application.close();
        }
    }

    @Test
    void thirdPartyOrdinaryStepExecutesPathOptionsAndNestedPrimitiveInputs() throws Exception {
        final DevelopmentApplication.Response response = run("current", "Hello RAILIX");

        assertThat(response.status()).isEqualTo(200);
        assertThat(payload(response).values().get("text")).isEqualTo(RailixValue.string("hello railix"));
    }

    @Test
    void missingSourceUsesTheExplicitMissingOutcome() throws Exception {
        final DevelopmentApplication.Response response = run("missing", "unchanged");

        assertThat(response.status()).isEqualTo(200);
        assertThat(payload(response).values().get("route")).isEqualTo(RailixValue.string("missing"));
    }

    @Test
    void absentPrimarySourceUsesTheDeclaredFallback() throws Exception {
        final DevelopmentApplication.Response response = run("fallback", "unchanged");

        assertThat(response.status()).isEqualTo(200);
        assertThat(payload(response).values().get("text")).isEqualTo(RailixValue.string("fallback"));
    }

    @Test
    void explicitJsonNullDoesNotActivateTheFallback() throws Exception {
        final DevelopmentApplication.Response response = run("null", "unchanged");

        assertThat(response.status()).isEqualTo(200);
        assertThat(payload(response).values().get("text")).isEqualTo(RailixValue.nullValue());
    }

    @Test
    void parentOptionSourceIgnoresASameNamedOwnedInput() throws Exception {
        final DevelopmentApplication.Response response = run("parent-shadow", "parent");

        assertThat(response.status()).isEqualTo(200);
        assertThat(payload(response).values().get("text")).isEqualTo(RailixValue.string("parent"));
    }

    @Test
    void ownedOptionSourceIgnoresASameNamedParentInput() throws Exception {
        final DevelopmentApplication.Response response = run("owned-shadow", "wrong");

        assertThat(response.status()).isEqualTo(200);
        assertThat(payload(response).values().get("text")).isEqualTo(RailixValue.string("owned"));
    }

    @Test
    void optionFromParentFeedsNestedProgram() throws Exception {
        final DevelopmentApplication.Response response = run("parent-program", "PARENT");

        assertThat(response.status()).isEqualTo(200);
        assertThat(payload(response).values().get("text")).isEqualTo(RailixValue.string("parent"));
    }

    @Test
    void optionFromOwnedInputFeedsNestedProgram() throws Exception {
        final DevelopmentApplication.Response response = run("owned-program", "wrong");

        assertThat(response.status()).isEqualTo(200);
        assertThat(payload(response).values().get("text")).isEqualTo(RailixValue.string("owned"));
    }

    @Test
    void ordinaryOptionCanReadAValueFromItsParentInput() throws Exception {
        final DevelopmentApplication.Response response = run("option-parent", "parent");

        assertThat(response.status()).isEqualTo(200);
        assertThat(payload(response).values().get("text")).isEqualTo(RailixValue.string("parent"));
    }

    @Test
    void ordinaryOptionCanReadAValueFromItsOwnedInput() throws Exception {
        final DevelopmentApplication.Response response = run("option-owned", "ignored");

        assertThat(response.status()).isEqualTo(200);
        assertThat(context(response).values().get("result")).isEqualTo(RailixValue.string("owned"));
    }

    @Test
    void ordinaryOptionWithoutAValueLeavesTheContextUnchanged() throws Exception {
        final DevelopmentApplication.Response response = run("option-empty", "ignored");

        assertThat(response.status()).isEqualTo(200);
        assertThat(context(response).values()).doesNotContainKey("result");
    }

    private DevelopmentApplication.Response run(final String trigger, final String text) throws Exception {
        return application.run(trigger, "{\"payload\":{\"text\":\"" + text + "\"}}");
    }

    private static RailixValue.ObjectValue payload(final DevelopmentApplication.Response response) {
        return (RailixValue.ObjectValue) context(response).values().get("payload");
    }

    private static RailixValue.ObjectValue context(final DevelopmentApplication.Response response) {
        final RailixValue.ObjectValue body = (RailixValue.ObjectValue)
                ((RailixJson.Parsed) RailixJson.parse(response.body())).value();
        return (RailixValue.ObjectValue) body.values().get("context");
    }

    private static List<StepDefinition> definitions() {
        final List<StepDefinition> definitions = new ArrayList<>();
        List.of(
                "current",
                "missing",
                "fallback",
                "null",
                "parent-shadow",
                "owned-shadow",
                "parent-program",
                "owned-program",
                "option-parent",
                "option-owned",
                "option-empty"
        )
                .forEach(id -> definitions.add(trigger(id)));
        definitions.add(StepDefinition.named("example.generic-operation", "1")
                        .input("field", StepDefinition.Input.path(READ_WRITE))
                        .input("value", StepDefinition.Input.candidates(
                                StepDefinition.Input.option("current").fromParent("field"),
                                StepDefinition.Input.option("literal")
                                        .input("literal", StepDefinition.Input.json(dev.nanonative.railix.core.value.ValueShape.ANY))
                                        .fromOwned("literal"),
                                StepDefinition.Input.option("field")
                                        .input("source", StepDefinition.Input.path(READ))
                                        .fromOwned("source"),
                                StepDefinition.Input.option("parent-shadow")
                                        .input("field", StepDefinition.Input.json(dev.nanonative.railix.core.value.ValueShape.ANY))
                                        .fromParent("field"),
                                StepDefinition.Input.option("owned-shadow")
                                        .input("field", StepDefinition.Input.json(dev.nanonative.railix.core.value.ValueShape.ANY))
                                        .fromOwned("field")
                        ))
                        .input("operations", StepDefinition.Input.steps(
                                StepDefinition.ValueSource.from("value").onMissing("missing")
                        ).propagateOutcomes())
                        .run(GenericContractSteps.Operation.class));
        definitions.add(StepDefinition.named("example.generic-option", "1")
                .input("target", StepDefinition.Input.path(READ_WRITE))
                .input("choice", StepDefinition.Input.options(
                        StepDefinition.Input.option("current").fromParent("target"),
                        StepDefinition.Input.option("literal")
                                .input("value", StepDefinition.Input.json(
                                        dev.nanonative.railix.core.value.ValueShape.ANY
                                ))
                                .fromOwned("value"),
                        StepDefinition.Input.option("none")
                ))
                .run(GenericContractSteps.Option.class));
        return List.copyOf(definitions);
    }

    private static StepDefinition trigger(final String id) {
        return StepDefinition.named("example.trigger." + id, "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("example.input." + id)
                .run(GenericContractSteps.Trigger.class);
    }

    private static String project() {
        return """
                {"format":1,"id":"generic-contract","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  %s,
                  %s,
                  %s,
                  %s,
                  %s,
                  %s,
                  %s,
                  %s,
                  %s,
                  %s,
                  %s,
                  {"id":"missing-marker","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","route"],
                    "value":[{"option":"literal","inputs":{"literal":"missing"}}],
                    "steps":[]
                  }}
                ],"links":[
                  {"from":"app.start","to":"current"},
                  {"from":"current.next","to":"current-operation"},
                  {"from":"current-operation.next","to":"end"},
                  {"from":"current-operation.missing","to":"end"},
                  {"from":"app.start","to":"missing"},
                  {"from":"missing.next","to":"missing-operation"},
                  {"from":"missing-operation.next","to":"end"},
                  {"from":"missing-operation.missing","to":"missing-marker"},
                  {"from":"missing-marker.next","to":"end"},
                  {"from":"app.start","to":"fallback"},
                  {"from":"fallback.next","to":"fallback-operation"},
                  {"from":"fallback-operation.next","to":"end"},
                  {"from":"fallback-operation.missing","to":"end"},
                  {"from":"app.start","to":"null"},
                  {"from":"null.next","to":"null-operation"},
                  {"from":"null-operation.next","to":"end"},
                  {"from":"null-operation.missing","to":"end"},
                  {"from":"app.start","to":"parent-shadow"},
                  {"from":"parent-shadow.next","to":"parent-shadow-operation"},
                  {"from":"parent-shadow-operation.next","to":"end"},
                  {"from":"parent-shadow-operation.missing","to":"end"},
                  {"from":"app.start","to":"owned-shadow"},
                  {"from":"owned-shadow.next","to":"owned-shadow-operation"},
                  {"from":"owned-shadow-operation.next","to":"end"},
                  {"from":"owned-shadow-operation.missing","to":"end"},
                  {"from":"app.start","to":"parent-program"},
                  {"from":"parent-program.next","to":"parent-program-operation"},
                  {"from":"parent-program-operation.next","to":"end"},
                  {"from":"parent-program-operation.missing","to":"end"},
                  {"from":"app.start","to":"owned-program"},
                  {"from":"owned-program.next","to":"owned-program-operation"},
                  {"from":"owned-program-operation.next","to":"end"},
                  {"from":"owned-program-operation.missing","to":"end"},
                  {"from":"app.start","to":"option-parent"},
                  {"from":"option-parent.next","to":"option-parent-operation"},
                  {"from":"option-parent-operation.next","to":"end"},
                  {"from":"app.start","to":"option-owned"},
                  {"from":"option-owned.next","to":"option-owned-operation"},
                  {"from":"option-owned-operation.next","to":"end"},
                  {"from":"app.start","to":"option-empty"},
                  {"from":"option-empty.next","to":"option-empty-operation"},
                  {"from":"option-empty-operation.next","to":"end"}
                ]}
                """.formatted(
                flow("current", "[{\"option\":\"current\",\"inputs\":{}}]", lowercase()),
                flow("missing", "[{\"option\":\"field\",\"inputs\":{\"source\":[\"context\",\"payload\",\"absent\"]}}]", lowercase()),
                flow("fallback", "[{\"option\":\"field\",\"inputs\":{\"source\":[\"context\",\"payload\",\"absent\"]}},{\"option\":\"literal\",\"inputs\":{\"literal\":\"FALLBACK\"}}]", lowercase()),
                flow("null", "[{\"option\":\"literal\",\"inputs\":{\"literal\":null}},{\"option\":\"literal\",\"inputs\":{\"literal\":\"wrong\"}}]", "[]"),
                flow("parent-shadow", "[{\"option\":\"parent-shadow\",\"inputs\":{\"field\":\"wrong\"}}]", "[]"),
                flow("owned-shadow", "[{\"option\":\"owned-shadow\",\"inputs\":{\"field\":\"owned\"}}]", "[]"),
                flow("parent-program", "[{\"option\":\"parent-shadow\",\"inputs\":{\"field\":\"wrong\"}}]", lowercase()),
                flow("owned-program", "[{\"option\":\"owned-shadow\",\"inputs\":{\"field\":\"OWNED\"}}]", lowercase()),
                optionFlow("option-parent", "[\"context\",\"payload\",\"text\"]",
                        "{\"option\":\"current\",\"inputs\":{}}"),
                optionFlow("option-owned", "[\"context\",\"result\"]",
                        "{\"option\":\"literal\",\"inputs\":{\"value\":\"owned\"}}"),
                optionFlow("option-empty", "[\"context\",\"result\"]",
                        "{\"option\":\"none\",\"inputs\":{}}")
        );
    }

    private static String flow(final String id, final String candidates, final String operations) {
        return """
                {"id":"%1$s","use":"example.trigger.%1$s","inputs":{},"examples":[
                  {"name":"example","payload":{},"context":{"payload":{"text":"Hello RAILIX"}}}
                ]},
                {"id":"%1$s-operation","use":"example.generic-operation","inputs":{
                  "field":["context","payload","text"],
                  "value":%2$s,
                  "operations":%3$s
                }}
                """.formatted(id, candidates, operations);
    }

    private static String lowercase() {
        return "[{\"use\":\"text.lowercase\",\"inputs\":{}}]";
    }

    private static String optionFlow(final String id, final String target, final String choice) {
        return """
                {"id":"%1$s","use":"example.trigger.%1$s","inputs":{},"examples":[
                  {"name":"example","payload":{},"context":{"payload":{"text":"parent"}}}
                ]},
                {"id":"%1$s-operation","use":"example.generic-option","inputs":{
                  "target":%2$s,
                  "choice":%3$s
                }}
                """.formatted(id, target, choice);
    }
}
