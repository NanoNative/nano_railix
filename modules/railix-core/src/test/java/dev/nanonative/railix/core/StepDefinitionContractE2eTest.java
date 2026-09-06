package dev.nanonative.railix.core;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepDefinition.CandidatesInput;
import dev.nanonative.railix.core.step.StepDefinition.Input;
import dev.nanonative.railix.core.step.StepDefinition.JsonInput;
import dev.nanonative.railix.core.step.StepDefinition.MatcherGroupsInput;
import dev.nanonative.railix.core.step.StepDefinition.Option;
import dev.nanonative.railix.core.step.StepDefinition.OptionsInput;
import dev.nanonative.railix.core.step.StepDefinition.PathInput;
import dev.nanonative.railix.core.step.StepDefinition.Result;
import dev.nanonative.railix.core.step.StepDefinition.Source;
import dev.nanonative.railix.core.step.StepDefinition.StepsInput;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;
import static dev.nanonative.railix.core.value.ValueShape.NUMBER;
import static dev.nanonative.railix.core.value.ValueShape.STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

final class StepDefinitionContractE2eTest {
    private static final Class<? extends StepHandler> NEXT = NextHandler.class;

    @Test
    void kindSurfaceContainsOnlyApplicationTriggerAndStep() {
        assertThat(StepDefinition.Kind.values()).containsExactly(
                StepDefinition.Kind.APP,
                StepDefinition.Kind.TRIGGER,
                StepDefinition.Kind.STEP
        );
    }

    @Test
    void namedHandlerClassIsTheGeneratedApplicationImplementation() {
        final StepDefinition definition = StepDefinition.named("example.named", "1").run(NextHandler.class);

        assertThat(definition.executable()).isTrue();
    }

    @Test
    void implementationJdkModulesReachTheStepCatalog() {
        final StepCatalog catalog = StepCatalog.of(StepDefinition.named("example.http", "1")
                .run(NextHandler.class, "java.net.http", "jdk.httpserver"));

        assertThat(catalog.implementation("example.http"))
                .hasValueSatisfying(implementation -> assertThat(implementation.jdkModules())
                        .containsExactly("java.net.http", "jdk.httpserver"));
    }

    @Test
    void missingImplementationClassIsRejectedAtTheDeveloperBoundary() {
        assertThatIllegalArgumentException().isThrownBy(() -> StepDefinition.named("example.lambda", "1")
                        .run(null))
                .withMessage("Step implementation cannot be Java null.");
    }

    @Test
    void localImplementationClassIsRejectedBeforeApplicationGeneration() {
        final class LocalHandler implements StepHandler {
            @Override
            public StepResult run(final dev.nanonative.railix.core.step.StepInput input) {
                return StepResult.outcome(input.primaryOutcome());
            }
        }

        assertThatIllegalArgumentException().isThrownBy(() -> StepDefinition.named("example.local", "1")
                        .run(LocalHandler.class))
                .withMessage("Step implementation must be a named Java class so generated applications can call it.");
    }

    @Test
    void searchTermsExposeStepDeveloperAliasesWithoutChangingTheDisplayName() {
        final StepDefinition definition = StepDefinition.named("number.greater-or-equal", "1")
                .displayName("Greater Or Equal")
                .searchTerms("gte", "ge")
                .run(NextHandler.class);

        assertThat(definition.displayName()).isEqualTo("Greater Or Equal");
        assertThat(definition.searchTerms()).containsExactly("gte", "ge");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidContracts")
    void invalidStepContractIsRejectedAtTheDeveloperBoundary(
            final String scenario,
            final Executable contract,
            final String message
    ) {
        assertThat(scenario).isNotBlank();
        assertThatIllegalArgumentException().isThrownBy(contract::execute).withMessage(message);
    }

    @Test
    void defaultsMakeGenericInputsOptionalAndRemainReadable() {
        final JsonInput json = Input.json(STRING).defaultValue(RailixValue.string("default"));
        final PathInput path = Input.path(READ).defaultPath("context", "payload");
        final OptionsInput options = Input.options(Input.option("first"), Input.option("second"))
                .defaultOption("second");
        final CandidatesInput candidates = Input.candidates(
                Input.option("first").fromOwned("value")
                        .input("value", Input.json(ValueShape.ANY).optional()),
                Input.option("second").fromOwned("value")
                        .input("value", Input.json(ValueShape.ANY).optional())
        ).defaultCandidate("second");

        assertThat(json.required()).isFalse();
        assertThat(json.defaultValue()).contains(RailixValue.string("default"));
        assertThat(path.required()).isFalse();
        assertThat(path.defaultValue()).contains(RailixValue.array(List.of(
                RailixValue.string("context"),
                RailixValue.string("payload")
        )));
        assertThat(options.required()).isFalse();
        assertThat(options.defaultOption()).contains("second");
        assertThat(candidates.defaultCandidate()).contains("second");
    }

    @Test
    void authoredCandidateOutcomesRemainExplicitAndHaveNoDefault() {
        final CandidatesInput candidates = Input.candidates(
                Input.option("field").fromOwned("value")
                        .input("value", Input.json(ValueShape.ANY))
        ).withAuthoredOutcomes();

        assertThat(candidates.authoredOutcomes()).isTrue();
        assertThat(candidates.defaultCandidate()).isEmpty();
    }

    @Test
    void optionalGenericInputsRemainOptionalWithoutDefaults() {
        assertThat(Input.json(STRING).optional().required()).isFalse();
        assertThat(Input.path(READ).optional().required()).isFalse();
        assertThat(Input.options(Input.option("one")).optional().required()).isFalse();
    }

    @Test
    void matcherGroupsDeclareOrderedBooleanProducingSources() {
        final MatcherGroupsInput groups = Input.matcherGroups(
                Input.option("current").fromParent("value"),
                Input.option("literal")
                        .input("value", Input.json(ValueShape.ANY))
                        .fromOwned("value")
        );

        final StepDefinition definition = StepDefinition.named("example.match", "1")
                .input("value", Input.json(ValueShape.ANY))
                .input("matches", groups)
                .run(NEXT);

        assertThat(groups.options()).extracting(Option::name).containsExactly("current", "literal");
        assertThat(definition.inputs()).extracting(StepDefinition.Field::name)
                .containsExactly("value", "matches");
    }

    @Test
    void triggerResultsExposeRequiredAndDefaultedContracts() {
        final StepDefinition definition = StepDefinition.named("example.trigger", "7")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("example.source")
                .requiredResult("required", STRING)
                .result("defaulted", NUMBER, RailixValue.number(0))
                .maximumInstances(2)
                .run(NEXT);

        assertThat(definition.version()).isEqualTo("7");
        assertThat(definition.maximumInstances()).isEqualTo(2);
        assertThat(definition.results()).extracting(Result::required).containsExactly(true, false);
        assertThat(definition.results().getLast().defaultValue()).contains(RailixValue.number(0));
    }

    @Test
    void optionOwnedAndEarlierReadableInputsAreValidValueSources() {
        final StepDefinition definition = StepDefinition.named("example.generic", "1")
                .input("field", Input.path(READ))
                .input("choice", Input.options(
                        Input.option("current").fromParent("field"),
                        Input.option("literal")
                                .input("literal", Input.json(ValueShape.ANY))
                                .fromOwned("literal")
                ))
                .input("steps", Input.steps(StepDefinition.ValueSource.from("choice")
                        .onMissing("missing")))
                .run(NEXT);

        final StepsInput steps = (StepsInput) definition.inputs().getLast().input();
        assertThat(steps.valueSource()).isEqualTo(StepDefinition.ValueSource.from("choice")
                .onMissing("missing"));
        assertThat(definition.outcomes()).containsExactly("next", "missing");
    }

    @Test
    void orderedCandidatesAreAReadableGenericValueSource() {
        final StepDefinition definition = StepDefinition.named("example.generic", "1")
                .input("field", Input.path(READ))
                .input("value", Input.candidates(
                        Input.option("current").fromParent("field"),
                        Input.option("literal")
                                .input("literal", Input.json(ValueShape.ANY))
                                .fromOwned("literal")
                ))
                .input("steps", Input.steps(
                        StepDefinition.ValueSource.from("value").onMissing("unresolved")
                ))
                .run(NEXT);

        final CandidatesInput candidates = (CandidatesInput) definition.inputs().get(1).input();
        assertThat(candidates.options()).extracting(Option::name).containsExactly("current", "literal");
        assertThat(definition.outcomes()).containsExactly("next", "unresolved");
    }

    @Test
    void dynamicNestedStepSourceMayBeAbsentWithoutCreatingAGraphOutcome() {
        final StepDefinition definition = StepDefinition.named("example.step", "1")
                .input("value", Input.path(READ))
                .input("steps", Input.steps(
                        StepDefinition.ValueSource.from("value")
                ))
                .run(NEXT);

        assertThat(definition.outcomes()).containsExactly("next");
    }

    @Test
    void legacyReceivePortKeepsTheExplicitNoRefinementDefault() {
        final StepDefinition definition = StepDefinition.named("example.step", "1")
                .receive("value", ValueShape.ANY)
                .run(NEXT);

        assertThat(definition.receives().getFirst().refinement()).isEqualTo(ValueRefinement.none());
    }

    @Test
    void receivePortRetainsItsDeclaredRefinement() {
        final ValueRefinement refinement = ValueRefinement.canonical().withMaxDepth(4);
        final StepDefinition definition = StepDefinition.named("example.step", "1")
                .receive("value", ValueShape.ARRAY, refinement)
                .run(NEXT);

        assertThat(definition.receives().getFirst().refinement()).isEqualTo(refinement);
    }

    @Test
    void returnPortRetainsItsDeclaredRefinement() {
        final ValueRefinement refinement = ValueRefinement.canonical().withMaxJsonBytes(32);
        final StepDefinition definition = StepDefinition.named("example.step", "1")
                .returns("value", ValueShape.STRING, refinement)
                .run(NEXT);

        assertThat(definition.returns().getFirst().refinement()).isEqualTo(refinement);
    }

    @Test
    void jsonRangeAcceptsAnInclusiveDefault() {
        final JsonInput input = Input.json(NUMBER)
                .between(RailixValue.number(0), RailixValue.number(10))
                .defaultValue(RailixValue.number(5));

        assertThat(input.defaultValue()).contains(RailixValue.number(5));
    }

    private static Stream<Arguments> invalidContracts() {
        return Stream.of(
                invalid("null option array", () -> Input.options((Option[]) null),
                        "Input options cannot be Java null."),
                invalid("null candidate option array", () -> Input.candidates((Option[]) null),
                        "Candidate options cannot be Java null."),
                invalid("null matcher group option array", () -> Input.matcherGroups((Option[]) null),
                        "Matcher group options cannot be Java null."),
                invalid("empty matcher group options", Input::matcherGroups,
                        "Matcher groups input must declare at least one option."),
                invalid("matcher group option without a source", () -> Input.matcherGroups(Input.option("literal")),
                        "Every matcher group option must declare one value source."),
                invalid("null candidate options", () -> new CandidatesInput(null, List.of()),
                        "Candidates input must declare at least one option."),
                invalid("empty candidate options", Input::candidates,
                        "Candidates input must declare at least one option."),
                invalid("duplicate candidate options", () -> Input.candidates(
                                Input.option("same").fromOwned("value")
                                        .input("value", Input.json(ValueShape.ANY)),
                                Input.option("same").fromOwned("value")
                                        .input("value", Input.json(ValueShape.ANY))
                        ),
                        "Candidate option names must be distinct."),
                invalid("candidate without a source", () -> Input.candidates(Input.option("literal")),
                        "Every candidate option must declare one value source."),
                invalid("multiple default candidates", () -> new CandidatesInput(
                                List.of(Input.option("known").fromOwned("value")
                                        .input("value", Input.json(ValueShape.ANY))),
                                List.of("known", "known")
                        ),
                        "Candidates input must declare zero or one default candidate."),
                invalid("blank default candidate", () -> new CandidatesInput(
                                List.of(Input.option("known").fromOwned("value")
                                        .input("value", Input.json(ValueShape.ANY))),
                                List.of(" ")
                        ),
                        "Default candidate must be a non-blank string."),
                invalid("unknown default candidate", () -> Input.candidates(
                                Input.option("known").fromOwned("value")
                                        .input("value", Input.json(ValueShape.ANY))
                        ).defaultCandidate("missing"),
                        "Candidates input default must name a declared option."),
                invalid("authored outcomes with a default candidate", () -> Input.candidates(
                                Input.option("known").fromOwned("value")
                                        .input("value", Input.json(ValueShape.ANY))
                        ).defaultCandidate("known").withAuthoredOutcomes(),
                        "Authored-outcome candidates cannot declare a default candidate."),
                invalid("multiple authored-outcome inputs", () -> StepDefinition.named("example.routes", "1")
                                .input("first", authoredCandidates())
                                .input("second", authoredCandidates())
                                .run(NEXT),
                        "A Step may declare only one authored-outcome candidates input."),
                invalid("nested authored-outcome input", () -> StepDefinition.named("example.routes", "1")
                                .input("nested", Input.options(Input.option("branch")
                                        .input("cases", authoredCandidates())))
                                .run(NEXT),
                        "Authored-outcome candidates must be a top-level Step input."),
                invalid("trigger authored-outcome input", () -> StepDefinition.named("example.trigger", "1")
                                .kind(StepDefinition.Kind.TRIGGER)
                                .input("cases", authoredCandidates())
                                .run(NEXT),
                        "Only ordinary Steps may declare authored-outcome candidates."),
                invalid("null JSON shape", () -> new JsonInput(null, List.of(), List.of(), true),
                        "JSON input shape cannot be Java null."),
                invalid("wrong JSON default shape", () -> Input.json(STRING).defaultValue(RailixValue.number(1)),
                        "JSON input default must match string."),
                invalid("JSON default outside range", () -> Input.json(NUMBER)
                                .between(RailixValue.number(0), RailixValue.number(10))
                                .defaultValue(RailixValue.number(11)),
                        "JSON input default must be within its range."),
                invalid("JSON default below range", () -> Input.json(NUMBER)
                                .between(RailixValue.number(0), RailixValue.number(10))
                                .defaultValue(RailixValue.number(-1)),
                        "JSON input default must be within its range."),
                invalid("null JSON defaults", () -> new JsonInput(STRING, null, List.of(), true),
                        "JSON input must declare zero or one default."),
                invalid("multiple JSON defaults", () -> new JsonInput(STRING, List.of(
                                RailixValue.string("first"), RailixValue.string("second")), List.of(), true),
                        "JSON input must declare zero or one default."),
                invalid("Java null JSON default", () -> Input.json(STRING).defaultValue(null),
                        "JSON input default cannot be Java null."),
                invalid("Java null JSON range minimum", () -> Input.json(NUMBER).between(null, RailixValue.number(1)),
                        "JSON input range cannot contain Java null."),
                invalid("null JSON range", () -> new JsonInput(NUMBER, List.of(), null, true),
                        "JSON input range must declare zero or two values."),
                invalid("single JSON range value", () -> new JsonInput(
                                NUMBER, List.of(), List.of(RailixValue.number(0)), true),
                        "JSON input range must declare zero or two values."),
                invalid("non-number JSON range", () -> Input.json(STRING).between(
                                RailixValue.string("a"), RailixValue.string("z")),
                        "JSON input range requires number values."),
                invalid("non-number JSON range minimum", () -> new JsonInput(NUMBER, List.of(), List.of(
                                RailixValue.string("a"), RailixValue.number(1)), true),
                        "JSON input range requires number values."),
                invalid("non-number JSON range maximum", () -> new JsonInput(NUMBER, List.of(), List.of(
                                RailixValue.number(0), RailixValue.string("z")), true),
                        "JSON input range requires number values."),
                invalid("reversed JSON range", () -> Input.json(NUMBER).between(
                                RailixValue.number(2), RailixValue.number(1)),
                        "JSON input range minimum cannot exceed maximum."),
                invalid("null path access", () -> new PathInput(null, List.of(), true),
                        "Path input access cannot be Java null."),
                invalid("multiple path defaults", () -> new PathInput(READ, List.of(
                                path("context", "first"), path("context", "second")), true),
                        "Path input must declare zero or one default."),
                invalid("Java null path segments", () -> Input.path(READ).defaultPath((String[]) null),
                        "Path input default cannot be Java null."),
                invalid("Java null path default", () -> Input.path(READ).defaultValue(null),
                        "Path input default cannot be Java null."),
                invalid("shallow path default", () -> Input.path(READ).defaultValue(path("context")),
                        "Path input default must start below context."),
                invalid("wrong path root", () -> Input.path(READ).defaultValue(path("payload", "value")),
                        "Path input default must start below context."),
                invalid("non-text path root", () -> Input.path(READ).defaultValue(RailixValue.array(List.of(
                                RailixValue.number(0), RailixValue.string("value")))),
                        "Path input default must start below context."),
                invalid("blank path field", () -> Input.path(READ).defaultPath("context", " "),
                        "Path input default elements must be fields or non-negative indexes."),
                invalid("boolean path segment", () -> Input.path(READ).defaultValue(RailixValue.array(List.of(
                                RailixValue.string("context"), RailixValue.bool(true)))),
                        "Path input default elements must be fields or non-negative indexes."),
                invalid("negative path index", () -> Input.path(READ).defaultValue(RailixValue.array(List.of(
                                RailixValue.string("context"), RailixValue.number(-1)))),
                        "Path input default elements must be fields or non-negative indexes."),
                invalid("decimal path index", () -> Input.path(READ).defaultValue(RailixValue.array(List.of(
                                RailixValue.string("context"),
                                RailixValue.number(new java.math.BigDecimal("1.5"))))),
                        "Path input default elements must be fields or non-negative indexes."),
                invalid("path default deeper than context supports", () -> Input.path(READ).defaultValue(path(
                                Stream.concat(Stream.of("context"), Stream.generate(() -> "field").limit(64))
                                        .toArray(String[]::new)
                        )), "Path input default must not exceed 64 elements."),
                invalid("writable runtime path", () -> Input.path(WRITE).defaultPath("context", "runtime"),
                        "Path input default must use writable context."),
                invalid("empty options", Input::options,
                        "Options input must declare at least one option."),
                invalid("duplicate options", () -> Input.options(Input.option("same"), Input.option("same")),
                        "Option names must be distinct."),
                invalid("blank default option", () -> new OptionsInput(
                                List.of(Input.option("known")), List.of(" "), true),
                        "Default option must be a non-blank string."),
                invalid("unknown default option", () -> Input.options(Input.option("known")).defaultOption("missing"),
                        "Options input default must name a declared option."),
                invalid("null nested Step source", () -> new StepsInput(null, false),
                        "Nested Step value source cannot be Java null."),
                invalid("Java null nested Step source", () -> Input.steps(null),
                        "Nested Step value source cannot be Java null."),
                invalid("null nested Step missing outcome list", () -> new StepDefinition.ValueSource(
                                "value", null),
                        "Nested Step value source must declare zero or one missing outcome."),
                invalid("multiple option value sources", () -> new Option(
                                "choice", List.of(), List.of(
                                        new StepDefinition.InputReference(
                                                StepDefinition.ReferenceScope.PARENT, "first"),
                                        new StepDefinition.InputReference(
                                                StepDefinition.ReferenceScope.OWNED, "second")
                                )),
                        "Option must declare zero or one value source."),
                invalid("null option value sources", () -> new Option("choice", List.of(), null),
                        "Option must declare zero or one value source."),
                invalid("null option value source scope", () -> new StepDefinition.InputReference(null, "value"),
                        "Option value source scope cannot be Java null."),
                invalid("null input type", () -> new StepDefinition.Field("input", null),
                        "Input type cannot be Java null."),
                invalid("null source responses", () -> new Source("example.source", null),
                        "Trigger source responses cannot be Java null."),
                invalid("ordinary Step source", () -> StepDefinition.named("example.step", "1")
                                .source("example.source").run(NEXT),
                        "Only Trigger Steps may declare an external source."),
                invalid("ordinary Step example", () -> StepDefinition.named("example.step", "1")
                                .example("case", RailixValue.object(Map.of())).run(NEXT),
                        "Only Trigger Steps may declare example templates."),
                invalid("ordinary Step example target", () -> StepDefinition.named("example.step", "1")
                                .input("target", Input.path(WRITE))
                                .exampleTarget("target")
                                .run(NEXT),
                        "Only Trigger Steps may declare an example target."),
                invalid("Trigger example without target", () -> StepDefinition.named("example.trigger", "1")
                                .kind(StepDefinition.Kind.TRIGGER)
                                .example("case", RailixValue.object(Map.of()))
                                .run(NEXT),
                        "Trigger example templates require an example target."),
                invalid("unknown Trigger example target", () -> StepDefinition.named("example.trigger", "1")
                                .kind(StepDefinition.Kind.TRIGGER)
                                .exampleTarget("missing")
                                .example("case", RailixValue.object(Map.of()))
                                .run(NEXT),
                        "Trigger example target must reference a declared PATH input: missing."),
                invalid("read-only Trigger example target", () -> StepDefinition.named("example.trigger", "1")
                                .kind(StepDefinition.Kind.TRIGGER)
                                .input("target", Input.path(READ))
                                .exampleTarget("target")
                                .example("case", RailixValue.object(Map.of()))
                                .run(NEXT),
                        "Trigger example target must reference a writable PATH input: target."),
                invalid("response without result", () -> StepDefinition.named("example.trigger", "1")
                                .kind(StepDefinition.Kind.TRIGGER)
                                .source("example.source")
                                .response("output", "missing")
                                .run(NEXT),
                        "Trigger response output must reference a declared result: missing."),
                invalid("null port shape", () -> new StepDefinition.Port("value", null),
                        "Port shape cannot be Java null."),
                invalid("null port refinement", () -> new StepDefinition.Port("value", ValueShape.ANY, null),
                        "Port refinement cannot be Java null."),
                invalid("negative refinement depth", () -> new ValueRefinement(true, -1, 0),
                        "Maximum value depth must be zero or positive."),
                invalid("zero fluent refinement depth", () -> ValueRefinement.canonical().withMaxDepth(0),
                        "Maximum value depth must be at least 1."),
                invalid("refinement depth above the global domain", () -> new ValueRefinement(true, 65, 0),
                        "Maximum value depth must not exceed 64."),
                invalid("depth without canonical values", () -> new ValueRefinement(false, 1, 0),
                        "Value depth refinement requires canonical values."),
                invalid("negative JSON byte refinement", () -> new ValueRefinement(true, 0, -1),
                        "Maximum canonical JSON bytes must be zero or positive."),
                invalid("zero fluent JSON byte refinement", () -> ValueRefinement.canonical().withMaxJsonBytes(0),
                        "Maximum canonical JSON bytes must be at least 1."),
                invalid("JSON byte refinement above the hard limit", () -> new ValueRefinement(
                                true, 0, dev.nanonative.railix.core.value.RailixData.MAX_SOURCE_BYTES + 1),
                        "Maximum canonical JSON bytes must not exceed 8388608."),
                invalid("JSON bytes without canonical values", () -> new ValueRefinement(false, 0, 1),
                        "JSON byte refinement requires canonical values."),
                invalid("scalar port with a depth refinement", () -> new StepDefinition.Port(
                                "value", STRING, ValueRefinement.canonical().withMaxDepth(1)),
                        "Port maximum depth requires any, array, or object shape."),
                invalid("container byte limit without explicit depth", () -> new StepDefinition.Port(
                                "value", ValueShape.ARRAY,
                                ValueRefinement.canonical().withMaxJsonBytes(32)),
                        "Container JSON byte refinement requires an explicit maximum depth."),
                invalid("null result shape", () -> new Result("result", null, List.of()),
                        "Result shape cannot be Java null."),
                invalid("Java null result default", () -> StepDefinition.named("example.trigger", "1")
                                .result("result", STRING, null),
                        "Result default cannot be Java null."),
                invalid("Java null Trigger example", () -> new StepDefinition.Example(
                                "case", null, RailixValue.object(Map.of())),
                        "Trigger example template values cannot be Java null."),
                invalid("reserved runtime Trigger example", () -> new StepDefinition.Example(
                                "case",
                                RailixValue.nullValue(),
                                RailixValue.object(Map.of("runtime", RailixValue.object(Map.of())))),
                        "Trigger example template cannot claim context.runtime."),
                invalid("null Step kind", () -> StepDefinition.named("example.step", "1").kind(null),
                        "Step kind cannot be Java null."),
                invalid("null Step search terms", () -> StepDefinition.named("example.step", "1")
                                .searchTerms((String[]) null),
                        "Step search terms cannot be Java null."),
                invalid("blank Step search term", () -> StepDefinition.named("example.step", "1")
                                .searchTerms(" "),
                        "Step search term must be a non-blank string."),
                invalid("duplicate Step search terms", () -> StepDefinition.named("example.step", "1")
                                .searchTerms("alias", "alias")
                                .run(NEXT),
                        "Step search terms must be distinct."),
                invalid("zero maximum instances", () -> StepDefinition.named("example.step", "1").maximumInstances(0),
                        "Maximum Step instances must be positive."),
                invalid("Java null implementation", () -> StepDefinition.named("example.step", "1").run(null),
                        "Step implementation cannot be Java null."),
                invalid("unknown option value source", () -> StepDefinition.named("example.step", "1")
                                .input("choice", Input.options(Input.option("current").fromParent("missing")))
                                .run(NEXT),
                        "Option parent source must reference a readable earlier input: missing."),
                invalid("write-only option value source", () -> StepDefinition.named("example.step", "1")
                                .input("target", Input.path(WRITE))
                                .input("choice", Input.options(Input.option("current").fromParent("target")))
                                .run(NEXT),
                        "Option parent source must reference a readable earlier input: target."),
                invalid("unknown option-owned value source", () -> StepDefinition.named("example.step", "1")
                                .input("choice", Input.options(Input.option("literal").fromOwned("missing")))
                                .run(NEXT),
                        "Option-owned source must reference a readable input owned by option literal: missing."),
                invalid("unknown nested Step value source", () -> StepDefinition.named("example.step", "1")
                                .input("steps", Input.steps(
                                        StepDefinition.ValueSource.from("missing").onMissing("missing")
                                ))
                                .run(NEXT),
                        "Nested Step source must reference a readable earlier input: missing."),
                invalid("write-only nested Step value source", () -> StepDefinition.named("example.step", "1")
                                .input("target", Input.path(WRITE))
                                .input("steps", Input.steps(
                                        StepDefinition.ValueSource.from("target").onMissing("missing")
                                ))
                                .run(NEXT),
                        "Nested Step source must reference a readable earlier input: target."),
                invalid("nested Step list as value source", () -> StepDefinition.named("example.step", "1")
                                .input("seed", Input.json(ValueShape.ANY)
                                        .defaultValue(RailixValue.nullValue()))
                                .input("first", Input.steps(
                                        StepDefinition.ValueSource.from("seed")
                                ))
                                .input("second", Input.steps(
                                        StepDefinition.ValueSource.from("first").onMissing("missing")
                                ))
                                .run(NEXT),
                        "Nested Step source must reference a readable earlier input: first."),
                invalid("source-less option as nested Step value", () -> StepDefinition.named("example.step", "1")
                                .input("choice", Input.options(Input.option("literal")))
                                .input("steps", Input.steps(StepDefinition.ValueSource.from("choice")))
                                .run(NEXT),
                        "Nested Step source must reference a readable earlier input: choice."),
                invalid("duplicate received values", () -> StepDefinition.named("example.step", "1")
                                .receive("value", STRING).receive("value", STRING)
                                .run(NEXT),
                        "Received value names must be distinct."),
                invalid("duplicate returned values", () -> StepDefinition.named("example.step", "1")
                                .returns("value", STRING).returns("value", STRING)
                                .run(NEXT),
                        "Returned value names must be distinct."),
                invalid("duplicate inputs", () -> StepDefinition.named("example.step", "1")
                                .input("value", Input.json(STRING)).input("value", Input.json(STRING))
                                .run(NEXT),
                        "Input names must be distinct."),
                invalid("duplicate results", () -> StepDefinition.named("example.trigger", "1")
                                .kind(StepDefinition.Kind.TRIGGER)
                                .requiredResult("result", STRING).requiredResult("result", STRING)
                                .run(NEXT),
                        "Result names must be distinct."),
                invalid("duplicate examples", () -> StepDefinition.named("example.trigger", "1")
                                .kind(StepDefinition.Kind.TRIGGER)
                                .input("target", Input.path(WRITE))
                                .exampleTarget("target")
                                .example("case", RailixValue.object(Map.of()))
                                .example("case", RailixValue.object(Map.of()))
                                .run(NEXT),
                        "Trigger example template names must be distinct."),
                invalid("duplicate outcomes", () -> StepDefinition.named("example.step", "1")
                                .outcome("next").run(NEXT),
                        "Step outcomes must be distinct."),
                invalid("nested missing outcome conflicts with primary outcome", () -> StepDefinition
                                .named("example.step", "1")
                                .input("seed", Input.json(ValueShape.ANY).defaultValue(RailixValue.nullValue()))
                                .input("steps", Input.steps(
                                        StepDefinition.ValueSource.from("seed").onMissing("next")
                                ))
                                .run(NEXT),
                        "Nested Step missing outcomes must differ from enclosing Step outcomes.")
        );
    }

    private static CandidatesInput authoredCandidates() {
        return Input.candidates(
                Input.option("field").fromOwned("value")
                        .input("value", Input.json(ValueShape.ANY))
        ).withAuthoredOutcomes();
    }

    private static Arguments invalid(
            final String scenario,
            final Executable contract,
            final String message
    ) {
        return Arguments.of(scenario, contract, message);
    }

    private static RailixValue.ArrayValue path(final String... fields) {
        return RailixValue.array(Stream.of(fields).<RailixValue>map(RailixValue::string).toList());
    }

    private static StepResult next(final dev.nanonative.railix.core.step.StepInput input) {
        return StepResult.outcome(input.primaryOutcome());
    }

    private static final class NextHandler implements StepHandler {
        @Override
        public StepResult run(final dev.nanonative.railix.core.step.StepInput input) {
            return next(input);
        }
    }
}
