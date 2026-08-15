package dev.nanonative.railix.core;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepDefinition.Input;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.READ;
import static dev.nanonative.railix.core.step.StepDefinition.PathAccess.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

final class CreatorFirstCompilerRejectionE2eTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("projectRejections")
    void malformedProjectStructureIsRejected(final RejectionCase rejection) {
        assertRejected(rejection.source(), catalog(), rejection.code(), rejection.path());
    }

    @Test
    void missingStepCatalogIsRejectedAtTheCompilerBoundary() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProjectCompiler.compileApplication(baseProject(inputs()), null))
                .withMessage("Step catalog cannot be Java null.");
    }

    @Test
    void stepInstanceLimitIsEnforced() {
        final StepDefinition limited = StepDefinition.named("example.step", "1")
                .maximumInstances(1)
                .run(TestStepHandlers.PrimaryOutcome.class);
        final String source = """
                {"format":1,"id":"limited-project","nodes":[
                  %s,
                  {"id":"trigger","use":"example.trigger","inputs":{},"examples":%s},
                  {"id":"first","use":"example.step","inputs":{}},
                  {"id":"second","use":"example.step","inputs":{}}
                ],"links":[]}
                """.formatted(appNode(), examples());

        assertRejected(source, catalog(app(), trigger(), limited, primitive()),
                "PROJECT_STEP_INSTANCE_LIMIT", "nodes[3].use");
    }

    @Test
    void malformedAppDefinitionIsRejectedAtTheProjectBoundary() {
        final StepDefinition malformed = StepDefinition.named("railix.app", "1")
                .kind(StepDefinition.Kind.APP)
                .input("value", Input.json(ValueShape.STRING).defaultValue(RailixValue.string("default")))
                .define();

        assertRejected(baseProject(inputs()), catalog(malformed, trigger(), step(), primitive()),
                "PROJECT_APP_CONTRACT_INVALID", "nodes[0].use");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedAppContracts")
    void everyMalformedAppContractIsRejectedAtTheProjectBoundary(
            final String scenario,
            final StepDefinition malformed
    ) {
        final String source = baseProject(inputs()).replace(
                "\"use\":\"railix.app\"",
                "\"use\":\"" + malformed.id() + "\""
        );

        assertThat(scenario).isNotBlank();
        assertRejected(source, catalog(malformed, trigger(), step(), primitive()),
                "PROJECT_APP_CONTRACT_INVALID", "nodes[0].use");
    }

    @Test
    void triggerMustDeclareASource() {
        final StepDefinition malformed = StepDefinition.named("example.trigger", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .run(TestStepHandlers.PrimaryOutcome.class);

        assertRejected(baseProject(inputs()), catalog(app(), malformed, step(), primitive()),
                "PROJECT_TRIGGER_CONTRACT_INVALID", "nodes[1].use");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedTriggerContracts")
    void everyMalformedTriggerContractIsRejectedAtTheProjectBoundary(
            final String scenario,
            final StepDefinition malformed
    ) {
        final String source = baseProject(inputs()).replace("example.trigger", malformed.id());

        assertThat(scenario).isNotBlank();
        assertRejected(source, catalog(app(), malformed, step(), primitive()),
                "PROJECT_TRIGGER_CONTRACT_INVALID", "nodes[1].use");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedGraphStepContracts")
    void everyMalformedGraphStepContractIsRejectedAtTheProjectBoundary(
            final String scenario,
            final StepDefinition malformed
    ) {
        assertThat(scenario).isNotBlank();
        assertRejected(simpleProject(malformed.id(), "{}"), catalog(app(), trigger(), malformed, primitive()),
                "PROJECT_STEP_CONTRACT_INVALID", "nodes[2].use");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedNestedStepContracts")
    void everyMalformedNestedStepContractIsRejectedAtTheProjectBoundary(
            final String scenario,
            final StepDefinition malformed,
            final String code
    ) {
        final StepDefinition pipeline = StepDefinition.named("example.pipeline", "1")
                .input("field", Input.path(READ))
                .input("steps", Input.steps(StepDefinition.ValueSource.from("field")))
                .run(TestStepHandlers.PrimaryOutcome.class);

        assertThat(scenario).isNotBlank();
        assertRejected(
                nestedProject("4").replace("text.lowercase", malformed.id()),
                catalog(app(), trigger(), pipeline, malformed),
                code,
                "nodes[2].inputs.steps[0].use"
        );
    }

    @Test
    void triggerResultCannotClaimRuntime() {
        final StepDefinition malformed = StepDefinition.named("example.trigger", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("application.example")
                .result("runtime", ValueShape.ANY, RailixValue.nullValue())
                .run(TestStepHandlers.PrimaryOutcome.class);

        assertRejected(baseProject(inputs()), catalog(app(), malformed, step(), primitive()),
                "PROJECT_TRIGGER_RESULT_RESERVED", "nodes[1].use");
    }

    @Test
    void graphStepRequiresExplicitReturnBindings() {
        final StepDefinition malformed = StepDefinition.named("example.step", "1")
                .returns("value", ValueShape.STRING)
                .run(TestStepHandlers.PrimaryConstantValue.class);

        assertRejected(simpleProject("example.step", "{}"), catalog(app(), trigger(), malformed, primitive()),
                "PROJECT_NODE_RETURNS_OBJECT_REQUIRED", "nodes[2].returns");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inputRejections")
    void malformedInputsAreRejected(final RejectionCase rejection) {
        assertRejected(rejection.source(), catalog(), rejection.code(), rejection.path());
    }

    @Test
    void omittedOptionalJsonInputCompiles() {
        final StepDefinition optional = StepDefinition.named("example.step", "1")
                .input("value", Input.json(ValueShape.STRING).optional())
                .run(TestStepHandlers.PrimaryOutcome.class);

        assertCompiled(simpleProject("example.step", "{}"), catalog(app(), trigger(), optional, primitive()));
    }

    @Test
    void writablePathCannotTargetRuntime() {
        final StepDefinition writer = StepDefinition.named("example.step", "1")
                .input("target", Input.path(WRITE))
                .run(TestStepHandlers.PrimaryOutcome.class);

        assertRejected(simpleProject("example.step", "{\"target\":[\"context\",\"runtime\",\"value\"]}"),
                catalog(app(), trigger(), writer, primitive()),
                "PROJECT_RUNTIME_READ_ONLY", "nodes[2].inputs.target");
    }

    @Test
    void omittedOptionalPathInputCompiles() {
        final StepDefinition optional = StepDefinition.named("example.step", "1")
                .input("value", Input.path(READ).optional())
                .run(TestStepHandlers.PrimaryOutcome.class);

        assertCompiled(simpleProject("example.step", "{}"), catalog(app(), trigger(), optional, primitive()));
    }

    @Test
    void omittedDefaultedOptionCompilesAndReachesTheStep() {
        final StepDefinition defaulted = StepDefinition.named("example.step", "1")
                .input("choice", Input.options(Input.option("first")).defaultOption("first"))
                .run(TestStepHandlers.ReadChoice.class);

        assertCompiled(simpleProject("example.step", "{}"), catalog(app(), trigger(), defaulted, primitive()));
    }

    @Test
    void omittedOptionalOptionsInputCompiles() {
        final StepDefinition optional = StepDefinition.named("example.step", "1")
                .input("choice", Input.options(Input.option("first")).optional())
                .run(TestStepHandlers.PrimaryOutcome.class);

        assertCompiled(simpleProject("example.step", "{}"), catalog(app(), trigger(), optional, primitive()));
    }

    @Test
    void malformedUnaryDefinitionIsRejectedInsideSteps() {
        final StepDefinition malformed = StepDefinition.named("example.primitive", "1")
                .primaryOutcome("ok")
                .receive("left", ValueShape.NUMBER)
                .receive("right", ValueShape.NUMBER)
                .returns("result", ValueShape.NUMBER)
                .run(TestStepHandlers.PrimaryOutcome.class);

        assertRejected(baseProject(inputs()), catalog(app(), trigger(), step(), malformed),
                "PROJECT_NESTED_STEP_CONTRACT_INVALID", "nodes[2].inputs.steps[0].use");
    }

    @Test
    void nestedStepMustSupplyItsRequiredInputs() {
        final StepDefinition configured = StepDefinition.named("example.primitive", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.NUMBER)
                .returns("value", ValueShape.NUMBER)
                .input("scale", Input.json(ValueShape.NUMBER))
                .run(TestStepHandlers.PrimaryIdentity.class);

        assertRejected(baseProject(inputs()), catalog(app(), trigger(), step(), configured),
                "PROJECT_INPUT_REQUIRED", "nodes[2].inputs.steps[0].inputs.scale");
    }

    @Test
    void triggerExampleAtANestedStepDepthRefinementCompiles() {
        final StepDefinition primitive = StepDefinition.named("text.lowercase", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.ANY, ValueRefinement.canonical().withMaxDepth(1))
                .returns("value", ValueShape.ANY, ValueRefinement.canonical().withMaxDepth(1))
                .run(TestStepHandlers.OkIdentity.class);
        assertCompiled(nestedProject("{}"), nestedCatalog(primitive));
    }

    @Test
    void triggerExampleAtANestedStepByteRefinementCompiles() {
        final StepDefinition primitive = StepDefinition.named("text.lowercase", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.STRING, ValueRefinement.canonical().withMaxJsonBytes(14))
                .returns("value", ValueShape.STRING, ValueRefinement.canonical().withMaxJsonBytes(14))
                .run(TestStepHandlers.OkIdentity.class);

        assertCompiled(nestedProject("\"Hello RAILIX\""), nestedCatalog(primitive));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exampleRejections")
    void malformedTriggerExamplesAreRejected(final RejectionCase rejection) {
        assertRejected(rejection.source(), catalog(), rejection.code(), rejection.path());
    }

    @Test
    void sourceIdentityCanOnlyBeDeclaredOnce() {
        final String source = """
                {"format":1,"id":"source-duplicate","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"first","use":"example.trigger","inputs":{},"examples":[{"name":"one","payload":[]}]},
                  {"id":"second","use":"example.trigger-two","inputs":{},"examples":[{"name":"two","payload":[]}]}
                ],"links":[
                  {"from":"app.start","to":"first"},{"from":"first.next","to":"end"},
                  {"from":"app.start","to":"second"},{"from":"second.next","to":"end"}
                ]}
                """;
        final StepDefinition second = StepDefinition.named("example.trigger-two", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("application.example")
                .run(TestStepHandlers.PrimaryOutcome.class);

        assertRejected(source, catalog(app(), trigger(), second, step(), primitive()),
                "PROJECT_TRIGGER_SOURCE_DUPLICATE", "nodes[2].use");
    }

    @Test
    void propagatedNestedOutcomeRequiresItsOwnLink() {
        final StepDefinition primitive = StepDefinition.named("example.primitive", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.NUMBER)
                .returns("value", ValueShape.NUMBER)
                .outcome("invalid")
                .run(TestStepHandlers.PrimaryIdentity.class);
        final StepDefinition step = StepDefinition.named("example.step", "1")
                .input("value", Input.json(ValueShape.NUMBER).defaultValue(RailixValue.number(0)))
                .input("steps", Input.steps(StepDefinition.ValueSource.from("value"))
                        .propagateOutcomes())
                .run(TestStepHandlers.PrimaryOutcome.class);
        final String source = simpleProject("example.step", "{\"steps\":[{\"use\":\"example.primitive\",\"inputs\":{}}]}");

        assertRejected(source, catalog(app(), trigger(), step, primitive),
                "PROJECT_NODE_OUTCOME_CONNECTION_REQUIRED", "nodes[2]");
    }

    @Test
    void propagatedNestedOutcomeCannotCollideWithAnEnclosingOutcome() {
        final StepDefinition primitive = StepDefinition.named("example.primitive", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.NUMBER)
                .returns("value", ValueShape.NUMBER)
                .outcome("invalid")
                .run(TestStepHandlers.PrimaryIdentity.class);
        final StepDefinition step = StepDefinition.named("example.step", "1")
                .outcome("invalid")
                .input("value", Input.json(ValueShape.NUMBER).defaultValue(RailixValue.number(0)))
                .input("steps", Input.steps(StepDefinition.ValueSource.from("value"))
                        .propagateOutcomes())
                .run(TestStepHandlers.PrimaryOutcome.class);
        final String source = simpleProject("example.step", "{\"steps\":[{\"use\":\"example.primitive\",\"inputs\":{}}]}");

        assertRejected(source, catalog(app(), trigger(), step, primitive),
                "PROJECT_NESTED_OUTCOME_COLLISION", "nodes[2].inputs");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("linkRejections")
    void malformedLinksAreRejected(final RejectionCase rejection) {
        assertRejected(rejection.source(), catalog(), rejection.code(), rejection.path());
    }

    @Test
    void everyOutcomeNeedsOneOutgoingConnection() {
        final String source = """
                {"format":1,"id":"missing-output","nodes":[
                  %s,
                  {"id":"trigger","use":"example.trigger","inputs":{},"examples":%s},
                  {"id":"step","use":"example.step","inputs":%s}
                ],"links":[
                  {"from":"app.start","to":"trigger"},
                  {"from":"trigger.next","to":"step"}
                ]}
                """.formatted(appNode(), examples(), inputs());

        assertRejected(source,
                catalog(), "PROJECT_NODE_OUTPUT_CONNECTION_REQUIRED", "nodes[2]");
    }

    @Test
    void disconnectedGraphNodeIsRejected() {
        final String source = """
                {"format":1,"id":"unreachable-project","nodes":[
                  %s,
                  {"id":"trigger","use":"example.trigger","inputs":{},"examples":%s},
                  {"id":"step","use":"example.step","inputs":%s},
                  {"id":"other","use":"example.step","inputs":%s},
                  {"id":"again","use":"example.step","inputs":%s}
                ],"links":[
                  {"from":"app.start","to":"trigger"},
                  {"from":"trigger.next","to":"step"},
                  {"from":"step.next","to":"end"},
                  {"from":"other.next","to":"again"},
                  {"from":"again.next","to":"other"}
                ]}
                """.formatted(appNode(), examples(), inputs(), inputs(), inputs());

        assertRejected(source, catalog(), "PROJECT_NODE_UNREACHABLE", "nodes[3]");
    }

    @Test
    void reachableGraphCycleIsRejected() {
        final String source = """
                {"format":1,"id":"cycle-project","nodes":[
                  %s,
                  {"id":"trigger","use":"example.trigger","inputs":{},"examples":%s},
                  {"id":"step","use":"example.step","inputs":%s},
                  {"id":"other","use":"example.step","inputs":%s}
                ],"links":[
                  {"from":"app.start","to":"trigger"},
                  {"from":"trigger.next","to":"step"},
                  {"from":"step.next","to":"other"},
                  {"from":"other.next","to":"step"}
                ]}
                """.formatted(appNode(), examples(), inputs(), inputs());

        assertRejected(source, catalog(), "PROJECT_GRAPH_CYCLE", "nodes[2]");
    }

    private static Stream<RejectionCase> projectRejections() {
        return Stream.of(
                new RejectionCase("missingProjectSourceIsRejected", null,
                        "PROJECT_SOURCE_REQUIRED", ""),
                new RejectionCase("blankProjectSourceIsRejected", " ",
                        "PROJECT_SOURCE_REQUIRED", ""),
                new RejectionCase("malformedJsonIsRejected", "{",
                        "PROJECT_JSON_INVALID", ""),
                new RejectionCase("projectMustBeAnObject", "[]",
                        "PROJECT_OBJECT_REQUIRED", ""),
                new RejectionCase("unknownProjectFieldIsRejected",
                        baseProject(inputs()).replace("\"format\":1", "\"format\":1,\"unknown\":true"),
                        "PROJECT_FIELD_UNKNOWN", "unknown"),
                new RejectionCase("reusableDefinitionsAreCreatorMetadataNotCompilerInput",
                        baseProject(inputs()).replace("\"nodes\":[", "\"flows\":[],\"nodes\":["),
                        "PROJECT_FIELD_UNKNOWN", "flows"),
                new RejectionCase("missingProjectFormatIsRejected",
                        baseProject(inputs()).replace("\"format\":1,", ""),
                        "PROJECT_FORMAT_UNSUPPORTED", "format"),
                new RejectionCase("unsupportedProjectFormatIsRejected",
                        baseProject(inputs()).replace("\"format\":1", "\"format\":2"),
                        "PROJECT_FORMAT_UNSUPPORTED", "format"),
                new RejectionCase("missingProjectIdIsRejected",
                        baseProject(inputs()).replace("\"id\":\"generic-project\",", ""),
                        "PROJECT_ID_REQUIRED", "id"),
                new RejectionCase("projectIdMustBeSafe",
                        baseProject(inputs()).replace("generic-project", "Generic/Project"),
                        "PROJECT_ID_INVALID", "id"),
                new RejectionCase("projectIdCannotContainAnUnsafeInnerCharacter",
                        baseProject(inputs()).replace("generic-project", "generic_project"),
                        "PROJECT_ID_INVALID", "id"),
                new RejectionCase("nodesMustBeAnArray",
                        "{\"format\":1,\"id\":\"generic-project\",\"nodes\":{},\"links\":[]}",
                        "PROJECT_NODES_ARRAY_REQUIRED", "nodes"),
                new RejectionCase("linksMustBeAnArray",
                        "{\"format\":1,\"id\":\"generic-project\",\"nodes\":[" + appNode() + "],\"links\":{}}",
                        "PROJECT_LINKS_ARRAY_REQUIRED", "links"),
                new RejectionCase("nodeMustBeAnObject",
                        baseProject(inputs()).replace(appNode(), "\"app\""),
                        "PROJECT_NODE_OBJECT_REQUIRED", "nodes[0]"),
                new RejectionCase("unknownNodeFieldIsRejected",
                        baseProject(inputs()).replace("\"id\":\"step\"", "\"unknown\":true,\"id\":\"step\""),
                        "PROJECT_NODE_FIELD_UNKNOWN", "nodes[2].unknown"),
                new RejectionCase("nodePresentationIsCreatorMetadataNotCompilerInput",
                        baseProject(inputs()).replace("\"id\":\"step\"",
                                "\"id\":\"step\",\"presentation\":{\"name\":\"Visible only in Creator\"}"),
                        "PROJECT_NODE_FIELD_UNKNOWN", "nodes[2].presentation"),
                new RejectionCase("reusableInvocationIsCreatorMetadataNotCompilerInput",
                        baseProject(inputs()).replace("\"id\":\"step\"",
                                "\"id\":\"step\",\"flow\":\"normalize-name\""),
                        "PROJECT_NODE_FIELD_UNKNOWN", "nodes[2].flow"),
                new RejectionCase("nodeIdIsRequired",
                        baseProject(inputs()).replace("\"id\":\"step\",", ""),
                        "PROJECT_NODE_ID_REQUIRED", "nodes[2].id"),
                new RejectionCase("nodeIdMustBeSafe",
                        baseProject(inputs()).replace("\"id\":\"step\"", "\"id\":\"Step\""),
                        "PROJECT_NODE_ID_INVALID", "nodes[2].id"),
                new RejectionCase("nodeUseIsRequired",
                        baseProject(inputs()).replace("\"use\":\"example.step\",", ""),
                        "PROJECT_NODE_USE_REQUIRED", "nodes[2].use"),
                new RejectionCase("duplicateNodeIdIsRejected",
                        baseProject(inputs()).replace("\"id\":\"trigger\"", "\"id\":\"app\""),
                        "PROJECT_NODE_ID_DUPLICATE", "nodes[1].id"),
                new RejectionCase("unknownStepIsRejected",
                        baseProject(inputs()).replace("\"use\":\"example.step\"", "\"use\":\"example.unknown\""),
                        "PROJECT_STEP_UNKNOWN", "nodes[2].use"),
                new RejectionCase("unaryStepRequiresExplicitGraphReceiveBindings",
                        simpleProject("example.primitive", "{}"),
                        "PROJECT_NODE_RECEIVES_OBJECT_REQUIRED", "nodes[2].receives"),
                new RejectionCase("graphReceivePortMustBeDeclaredByTheStep",
                        primitiveProject("{\"other\":[\"context\",\"payload\",\"value\"]}",
                                "{\"value\":[\"context\",\"result\"]}"),
                        "PROJECT_NODE_PORT_UNKNOWN", "nodes[2].receives.other"),
                new RejectionCase("graphReceivePortRequiresAPath",
                        primitiveProject("{}", "{\"value\":[\"context\",\"result\"]}"),
                        "PROJECT_NODE_PORT_REQUIRED", "nodes[2].receives.value"),
                new RejectionCase("graphReceivePortPathMustUseCanonicalSegments",
                        primitiveProject("{\"value\":\"context.payload.value\"}",
                                "{\"value\":[\"context\",\"result\"]}"),
                        "PROJECT_CONTEXT_PATH_REQUIRED", "nodes[2].receives.value"),
                new RejectionCase("graphReturnPortMustBeDeclaredByTheStep",
                        primitiveProject("{\"value\":[\"context\",\"payload\",\"value\"]}",
                                "{\"other\":[\"context\",\"result\"]}"),
                        "PROJECT_NODE_PORT_UNKNOWN", "nodes[2].returns.other"),
                new RejectionCase("graphReturnPortRequiresAPath",
                        primitiveProject("{\"value\":[\"context\",\"payload\",\"value\"]}", "{}"),
                        "PROJECT_NODE_PORT_REQUIRED", "nodes[2].returns.value"),
                new RejectionCase("graphReturnPortPathMustUseCanonicalSegments",
                        primitiveProject("{\"value\":[\"context\",\"payload\",\"value\"]}",
                                "{\"value\":\"context.result\"}"),
                        "PROJECT_CONTEXT_PATH_REQUIRED", "nodes[2].returns.value"),
                new RejectionCase("nodeInputsMustBeAnObject",
                        baseProject(inputs()).replace("\"inputs\":{}", "\"inputs\":[]"),
                        "PROJECT_NODE_INPUTS_OBJECT_REQUIRED", "nodes[0].inputs"),
                new RejectionCase("projectRequiresTheReservedAppNode",
                        baseProject(inputs()).replace(appNode() + ",", ""),
                        "PROJECT_APP_REQUIRED", "nodes"),
                new RejectionCase("appNodeMustKeepItsReservedId",
                        baseProject(inputs())
                                .replace("\"id\":\"app\"", "\"id\":\"root\"")
                                .replace("app.start", "root.start"),
                        "PROJECT_APP_ID_INVALID", "nodes[0].id")
        );
    }

    private static Stream<RejectionCase> inputRejections() {
        return Stream.of(
                new RejectionCase("undeclaredInputIsRejected",
                        baseProject(inputs().replace("\"steps\":[", "\"unknown\":true,\"steps\":[")),
                        "PROJECT_INPUT_UNKNOWN", "nodes[2].inputs.unknown"),
                new RejectionCase("jsonInputIsRequiredWhenNoDefaultExists",
                        baseProject(inputs().replace("\"json\":4,", "")),
                        "PROJECT_INPUT_REQUIRED", "nodes[2].inputs.json"),
                new RejectionCase("jsonInputMustMatchItsDeclaredShape",
                        baseProject(inputs().replace("\"json\":4", "\"json\":\"four\"")),
                        "PROJECT_INPUT_SHAPE_INVALID", "nodes[2].inputs.json"),
                new RejectionCase("jsonInputMustRemainWithinItsDeclaredRange",
                        baseProject(inputs().replace("\"json\":4", "\"json\":11")),
                        "PROJECT_INPUT_RANGE_INVALID", "nodes[2].inputs.json"),
                new RejectionCase("pathInputIsRequiredWhenNoDefaultExists",
                        baseProject(inputs().replace("\"path\":[\"context\",\"payload\",\"value\"],", "")),
                        "PROJECT_INPUT_REQUIRED", "nodes[2].inputs.path"),
                new RejectionCase("pathInputMustContainAContextChild",
                        baseProject(inputs().replace("[\"context\",\"payload\",\"value\"]", "[\"context\"]")),
                        "PROJECT_CONTEXT_PATH_REQUIRED", "nodes[2].inputs.path"),
                new RejectionCase("pathInputMustStartAtContext",
                        baseProject(inputs().replace("[\"context\",\"payload\",\"value\"]",
                                "[\"payload\",\"value\"]")),
                        "PROJECT_CONTEXT_PATH_ROOT_REQUIRED", "nodes[2].inputs.path[0]"),
                new RejectionCase("pathInputMustContainOnlyFieldOrIndexSegments",
                        baseProject(inputs().replace("[\"context\",\"payload\",\"value\"]", "[\"context\",true]")),
                        "PROJECT_CONTEXT_PATH_ELEMENT_INVALID", "nodes[2].inputs.path[1]"),
                new RejectionCase("negativePathIndexIsRejected",
                        baseProject(inputs().replace("[\"context\",\"payload\",\"value\"]", "[\"context\",-1]")),
                        "PROJECT_CONTEXT_PATH_ELEMENT_INVALID", "nodes[2].inputs.path[1]"),
                new RejectionCase("fractionalPathIndexIsRejected",
                        baseProject(inputs().replace("[\"context\",\"payload\",\"value\"]", "[\"context\",1.5]")),
                        "PROJECT_CONTEXT_PATH_ELEMENT_INVALID", "nodes[2].inputs.path[1]"),
                new RejectionCase("pathDeeperThanTheCanonicalContextLimitIsRejected",
                        baseProject(inputs().replace("[\"context\",\"payload\",\"value\"]",
                                "[\"context\"," + "\"field\",".repeat(63) + "\"field\"]")),
                        "PROJECT_CONTEXT_PATH_TOO_DEEP", "nodes[2].inputs.path"),
                new RejectionCase("optionsInputIsRequiredWhenNoDefaultExists",
                        baseProject(inputs().replace(
                                "\"choice\":{\"option\":\"literal\",\"inputs\":{\"literal\":\"name\"}},", "")),
                        "PROJECT_INPUT_REQUIRED", "nodes[2].inputs.choice"),
                new RejectionCase("optionsInputMustBeAnObject",
                        baseProject(inputs().replace(
                                "{\"option\":\"literal\",\"inputs\":{\"literal\":\"name\"}}", "\"literal\"")),
                        "PROJECT_OPTION_OBJECT_REQUIRED", "nodes[2].inputs.choice"),
                new RejectionCase("optionsInputMustNameADeclaredOption",
                        baseProject(inputs().replace("\"option\":\"literal\"", "\"option\":\"unknown\"")),
                        "PROJECT_OPTION_UNKNOWN", "nodes[2].inputs.choice.option"),
                new RejectionCase("unknownOptionFieldIsRejected",
                        baseProject(inputs()).replace(
                                "{\"option\":\"literal\",\"inputs\":{\"literal\":\"name\"}}",
                                "{\"option\":\"literal\",\"inputs\":{\"literal\":\"name\"},\"unknown\":true}"),
                        "PROJECT_OPTION_FIELD_UNKNOWN", "nodes[2].inputs.choice.unknown"),
                new RejectionCase("optionNameIsRequired",
                        baseProject(inputs()).replace(
                                "{\"option\":\"literal\",\"inputs\":{\"literal\":\"name\"}}",
                                "{\"inputs\":{\"literal\":\"name\"}}"),
                        "PROJECT_OPTION_REQUIRED", "nodes[2].inputs.choice.option"),
                new RejectionCase("optionInputsMustBeAnObject",
                        baseProject(inputs()).replace(
                                "{\"option\":\"literal\",\"inputs\":{\"literal\":\"name\"}}",
                                "{\"option\":\"literal\",\"inputs\":[]}"),
                        "PROJECT_OPTION_INPUTS_OBJECT_REQUIRED", "nodes[2].inputs.choice.inputs"),
                new RejectionCase("selectedOptionRejectsUnknownChildInput",
                        baseProject(inputs()).replace("\"literal\":\"name\"",
                                "\"literal\":\"name\",\"unknown\":true"),
                        "PROJECT_INPUT_UNKNOWN", "nodes[2].inputs.choice.inputs.unknown"),
                new RejectionCase("selectedOptionMustSupplyItsRequiredChildren",
                        baseProject(inputs()).replace("\"literal\":\"name\"", ""),
                        "PROJECT_INPUT_REQUIRED", "nodes[2].inputs.choice.inputs.literal"),
                new RejectionCase("stepsInputMustBeAnArray",
                        baseProject(inputs()).replace(
                                "\"steps\":[{\"use\":\"example.primitive\",\"inputs\":{}}]", "\"steps\":{}"),
                        "PROJECT_STEPS_ARRAY_REQUIRED", "nodes[2].inputs.steps"),
                new RejectionCase("nestedStepMustBeAnObject",
                        baseProject(inputs()).replace(
                                "[{\"use\":\"example.primitive\",\"inputs\":{}}]", "[\"example.primitive\"]"),
                        "PROJECT_NESTED_STEP_OBJECT_REQUIRED", "nodes[2].inputs.steps[0]"),
                new RejectionCase("nestedStepMustBeRegistered",
                        baseProject(inputs()).replace("example.primitive", "example.unknown"),
                        "PROJECT_NESTED_STEP_UNKNOWN", "nodes[2].inputs.steps[0].use"),
                new RejectionCase("unknownNestedStepFieldIsRejected",
                        baseProject(inputs()).replace(
                                "{\"use\":\"example.primitive\",\"inputs\":{}}",
                                "{\"use\":\"example.primitive\",\"inputs\":{},\"unknown\":true}"),
                        "PROJECT_NESTED_STEP_FIELD_UNKNOWN", "nodes[2].inputs.steps[0].unknown"),
                new RejectionCase("nestedStepUseIsRequired",
                        baseProject(inputs()).replace(
                                "{\"use\":\"example.primitive\",\"inputs\":{}}", "{\"inputs\":{}}"),
                        "PROJECT_NESTED_STEP_USE_REQUIRED", "nodes[2].inputs.steps[0].use"),
                new RejectionCase("nestedStepMustMatchTheDeclaredKind",
                        baseProject(inputs()).replace("example.primitive", "example.trigger"),
                        "PROJECT_NESTED_STEP_KIND_INVALID", "nodes[2].inputs.steps[0].use"),
                new RejectionCase("nestedStepInputsMustBeAnObject",
                        baseProject(inputs()).replace("\"inputs\":{}}]", "\"inputs\":[]}]"),
                        "PROJECT_NESTED_INPUTS_OBJECT_REQUIRED", "nodes[2].inputs.steps[0].inputs")
        );
    }

    private static Stream<RejectionCase> exampleRejections() {
        return Stream.of(
                new RejectionCase("nonTriggerNodeCannotDeclareExamples",
                        baseProject(inputs()).replace("\"inputs\":" + inputs() + "}",
                                "\"inputs\":" + inputs() + ",\"examples\":[]}"),
                        "PROJECT_NODE_EXAMPLES_UNSUPPORTED", "nodes[2].examples"),
                new RejectionCase("triggerRequiresAtLeastOneExample",
                        baseProject(inputs()).replace(",\"examples\":" + examples(), ""),
                        "PROJECT_TRIGGER_EXAMPLE_REQUIRED", "nodes[1].examples"),
                new RejectionCase("triggerExampleRejectsUnknownFields",
                        baseProject(inputs()).replace("\"name\":\"sample\"",
                                "\"unknown\":true,\"name\":\"sample\""),
                        "PROJECT_TRIGGER_EXAMPLE_FIELD_UNKNOWN", "nodes[1].examples[0].unknown"),
                new RejectionCase("triggerExampleMustBeAnObject",
                        baseProject(inputs()).replace(examples(), "[\"sample\"]"),
                        "PROJECT_TRIGGER_EXAMPLE_OBJECT_REQUIRED", "nodes[1].examples[0]"),
                new RejectionCase("triggerExampleNameIsRequired",
                        baseProject(inputs()).replace(examples(),
                                "[{\"payload\":[],\"context\":{\"payload\":{\"value\":4}}}]"),
                        "PROJECT_TRIGGER_EXAMPLE_NAME_REQUIRED", "nodes[1].examples[0].name"),
                new RejectionCase("triggerExampleNamesMustBeUnique",
                        baseProject(inputs()).replace(examples(),
                                "[{\"name\":\"sample\",\"payload\":4},"
                                        + "{\"name\":\"sample\",\"payload\":5}]"),
                        "PROJECT_TRIGGER_EXAMPLE_NAME_DUPLICATE", "nodes[1].examples[1].name"),
                new RejectionCase("triggerExamplePayloadIsRequired",
                        baseProject(inputs()).replace(examples(), "[{\"name\":\"sample\"}]"),
                        "PROJECT_TRIGGER_EXAMPLE_PAYLOAD_REQUIRED", "nodes[1].examples[0].payload"),
                new RejectionCase("triggerExampleContextMustBeAnObjectWhenSupplied",
                        baseProject(inputs()).replace(examples(),
                                "[{\"name\":\"sample\",\"payload\":[],\"context\":[]}]"),
                        "PROJECT_TRIGGER_EXAMPLE_CONTEXT_OBJECT_REQUIRED", "nodes[1].examples[0].context"),
                new RejectionCase("triggerExamplesCannotClaimRuntime",
                        baseProject(inputs()).replace("\"context\":{\"payload\":{\"value\":4}}",
                                "\"context\":{\"runtime\":{}}"),
                        "PROJECT_TRIGGER_EXAMPLE_RUNTIME_RESERVED", "nodes[1].examples[0].context.runtime")
        );
    }

    private static Stream<RejectionCase> linkRejections() {
        return Stream.of(
                new RejectionCase("linkMustBeAnObject",
                        baseProject(inputs()).replace(
                                "{\"from\":\"app.start\",\"to\":\"trigger\"}", "\"link\""),
                        "PROJECT_LINK_OBJECT_REQUIRED", "links[0]"),
                new RejectionCase("linkMustUseNodeAndOutcomeSyntax",
                        baseProject(inputs()).replace("\"from\":\"app.start\"", "\"from\":\"app\""),
                        "PROJECT_LINK_FROM_INVALID", "links[0].from"),
                new RejectionCase("unknownLinkFieldIsRejected",
                        baseProject(inputs()).replace(
                                "{\"from\":\"app.start\",\"to\":\"trigger\"}",
                                "{\"from\":\"app.start\",\"to\":\"trigger\",\"unknown\":true}"),
                        "PROJECT_LINK_FIELD_UNKNOWN", "links[0].unknown"),
                new RejectionCase("linkFromIsRequired",
                        baseProject(inputs()).replace(
                                "{\"from\":\"app.start\",\"to\":\"trigger\"}", "{\"to\":\"trigger\"}"),
                        "PROJECT_LINK_FROM_REQUIRED", "links[0].from"),
                new RejectionCase("linkTargetIsRequired",
                        baseProject(inputs()).replace(
                                "{\"from\":\"app.start\",\"to\":\"trigger\"}", "{\"from\":\"app.start\"}"),
                        "PROJECT_LINK_TO_REQUIRED", "links[0].to"),
                new RejectionCase("linkSourceMustExist",
                        baseProject(inputs()).replace("\"from\":\"app.start\"", "\"from\":\"unknown.start\""),
                        "PROJECT_LINK_SOURCE_UNKNOWN", "links[0].from"),
                new RejectionCase("linkOutcomeMustExist",
                        baseProject(inputs()).replace("\"from\":\"trigger.next\"", "\"from\":\"trigger.missing\""),
                        "PROJECT_LINK_OUTCOME_UNKNOWN", "links[1].from"),
                new RejectionCase("ordinaryOutcomeCanHaveOnlyOneConnection",
                        baseProject(inputs()).replace(
                                "{\"from\":\"step.next\",\"to\":\"end\"}",
                                "{\"from\":\"step.next\",\"to\":\"end\"},{\"from\":\"step.next\",\"to\":\"end\"}"),
                        "PROJECT_PORT_CONNECTION_DUPLICATE", "links[3].from"),
                new RejectionCase("linkTargetMustExist",
                        baseProject(inputs()).replace("\"to\":\"step\"", "\"to\":\"missing\""),
                        "PROJECT_LINK_TARGET_UNKNOWN", "links[1].to"),
                new RejectionCase("appMayConnectOnlyToTriggers",
                        baseProject(inputs()).replace("\"to\":\"trigger\"", "\"to\":\"step\""),
                        "PROJECT_APP_CONNECTION_INVALID", "links[0]"),
                new RejectionCase("triggerMayConnectOnlyToOrdinaryStepsOrEnd",
                        baseProject(inputs()).replace("\"to\":\"step\"", "\"to\":\"app\""),
                        "PROJECT_TRIGGER_CONNECTION_INVALID", "links[1]"),
                new RejectionCase("ordinaryStepMayConnectOnlyToOrdinaryStepsOrEnd",
                        baseProject(inputs()).replace(
                                "{\"from\":\"step.next\",\"to\":\"end\"}",
                                "{\"from\":\"step.next\",\"to\":\"trigger\"}"),
                        "PROJECT_STEP_CONNECTION_INVALID", "links[2]"),
                new RejectionCase("everyGraphNodeNeedsOneIncomingConnection",
                        baseProject(inputs()).replace("\"to\":\"step\"", "\"to\":\"end\""),
                        "PROJECT_NODE_INPUT_CONNECTION_REQUIRED", "nodes[2]")
        );
    }

    private static Stream<Arguments> malformedAppContracts() {
        return Stream.of(
                Arguments.of("wrong reserved definition id", StepDefinition.named("example.app", "1")
                        .kind(StepDefinition.Kind.APP).define()),
                Arguments.of("wrong primary outcome", StepDefinition.named("railix.app", "1")
                        .kind(StepDefinition.Kind.APP).primaryOutcome("next").define()),
                Arguments.of("additional outcome", StepDefinition.named("railix.app", "1")
                        .kind(StepDefinition.Kind.APP).outcome("extra").define()),
                Arguments.of("received value", StepDefinition.named("railix.app", "1")
                        .kind(StepDefinition.Kind.APP).receive("value", ValueShape.ANY).define()),
                Arguments.of("returned value", StepDefinition.named("railix.app", "1")
                        .kind(StepDefinition.Kind.APP).returns("value", ValueShape.ANY).define()),
                Arguments.of("executable behavior", StepDefinition.named("railix.app", "1")
                        .kind(StepDefinition.Kind.APP).run(TestStepHandlers.PrimaryOutcome.class)),
                Arguments.of("Trigger result", StepDefinition.named("railix.app", "1")
                        .kind(StepDefinition.Kind.APP)
                        .result("result", ValueShape.ANY, RailixValue.nullValue())
                        .define())
        );
    }

    private static Stream<Arguments> malformedTriggerContracts() {
        return Stream.of(
                Arguments.of("additional outcome", StepDefinition.named("example.trigger.extra", "1")
                        .kind(StepDefinition.Kind.TRIGGER)
                        .source("application.example.extra")
                        .outcome("extra")
                        .run(TestStepHandlers.PrimaryOutcome.class)),
                Arguments.of("returned value", StepDefinition.named("example.trigger.return", "1")
                        .kind(StepDefinition.Kind.TRIGGER)
                        .source("application.example.return")
                        .returns("value", ValueShape.ANY)
                        .run(TestStepHandlers.PrimaryOutcome.class)),
                Arguments.of("missing executable behavior", StepDefinition.named("example.trigger.static", "1")
                        .kind(StepDefinition.Kind.TRIGGER)
                        .source("application.example.static")
                        .define())
        );
    }

    private static Stream<Arguments> malformedGraphStepContracts() {
        return Stream.of(
                Arguments.of("missing executable behavior", StepDefinition.named("example.static", "1").define()),
                Arguments.of("Trigger result", StepDefinition.named("example.result", "1")
                        .result("result", ValueShape.ANY, RailixValue.nullValue())
                        .run(TestStepHandlers.PrimaryOutcome.class))
        );
    }

    private static Stream<Arguments> malformedNestedStepContracts() {
        return Stream.of(
                Arguments.of("Trigger kind", StepDefinition.named("nested.trigger", "1")
                        .kind(StepDefinition.Kind.TRIGGER)
                        .source("application.nested")
                        .receive("value", ValueShape.ANY)
                        .returns("value", ValueShape.ANY)
                        .run(TestStepHandlers.PrimaryOutcome.class), "PROJECT_NESTED_STEP_KIND_INVALID"),
                Arguments.of("missing receive", StepDefinition.named("nested.no-receive", "1")
                        .returns("value", ValueShape.ANY)
                        .run(TestStepHandlers.PrimaryOutcome.class), "PROJECT_NESTED_STEP_CONTRACT_INVALID"),
                Arguments.of("multiple receives", StepDefinition.named("nested.receives", "1")
                        .receive("first", ValueShape.ANY)
                        .receive("second", ValueShape.ANY)
                        .returns("value", ValueShape.ANY)
                        .run(TestStepHandlers.PrimaryOutcome.class), "PROJECT_NESTED_STEP_CONTRACT_INVALID"),
                Arguments.of("missing return", StepDefinition.named("nested.no-return", "1")
                        .receive("value", ValueShape.ANY)
                        .run(TestStepHandlers.PrimaryOutcome.class), "PROJECT_NESTED_STEP_CONTRACT_INVALID"),
                Arguments.of("multiple returns", StepDefinition.named("nested.returns", "1")
                        .receive("value", ValueShape.ANY)
                        .returns("first", ValueShape.ANY)
                        .returns("second", ValueShape.ANY)
                        .run(TestStepHandlers.PrimaryOutcome.class), "PROJECT_NESTED_STEP_CONTRACT_INVALID"),
                Arguments.of("missing executable behavior", StepDefinition.named("nested.static", "1")
                        .receive("value", ValueShape.ANY)
                        .returns("value", ValueShape.ANY)
                        .define(), "PROJECT_NESTED_STEP_CONTRACT_INVALID"),
                Arguments.of("Trigger result", StepDefinition.named("nested.result", "1")
                        .receive("value", ValueShape.ANY)
                        .returns("value", ValueShape.ANY)
                        .result("result", ValueShape.ANY, RailixValue.nullValue())
                        .run(TestStepHandlers.PrimaryOutcome.class), "PROJECT_NESTED_STEP_CONTRACT_INVALID")
        );
    }

    private record RejectionCase(String name, String source, String code, String path) {
        @Override
        public String toString() { return name; }
    }

    private static StepCatalog catalog(final StepDefinition... definitions) {
        return StepCatalog.of(definitions.length == 0
                ? new StepDefinition[]{app(), trigger(), step(), primitive()}
                : definitions);
    }

    private static StepCatalog nestedCatalog(final StepDefinition primitive) {
        final StepDefinition pipeline = StepDefinition.named("example.pipeline", "1")
                .input("field", Input.path(READ))
                .input("steps", Input.steps(StepDefinition.ValueSource.from("field")))
                .run(TestStepHandlers.PrimaryOutcome.class);
        return StepCatalog.of(app(), trigger(), pipeline, primitive);
    }

    private static String nestedProject(final String exampleValue) {
        return """
                {"format":1,"id":"nested-boundary","nodes":[
                  %s,
                  {"id":"trigger","use":"example.trigger","inputs":{},"examples":[{
                    "name":"boundary","payload":[],"context":{"payload":{"name":%s}}
                  }]},
                  {"id":"step","use":"example.pipeline","inputs":{
                    "field":["context","payload","name"],
                    "steps":[{"use":"text.lowercase","inputs":{}}]
                  }}
                ],"links":[
                  {"from":"app.start","to":"trigger"},
                  {"from":"trigger.next","to":"step"},
                  {"from":"step.next","to":"end"}
                ]}
                """.formatted(appNode(), exampleValue);
    }

    private static StepDefinition app() {
        return StepDefinition.named("railix.app", "1").kind(StepDefinition.Kind.APP).define();
    }

    private static StepDefinition trigger() {
        return StepDefinition.named("example.trigger", "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source("application.example")
                .run(TestStepHandlers.PrimaryOutcome.class);
    }

    private static StepDefinition step() {
        return StepDefinition.named("example.step", "1")
                .input("json", Input.json(ValueShape.NUMBER)
                        .between(RailixValue.number(0), RailixValue.number(10)))
                .input("path", Input.path(READ))
                .input("choice", Input.options(
                        Input.option("literal")
                                .input("literal", Input.json(ValueShape.STRING))
                                .fromOwned("literal")
                ))
                .input("steps", Input.steps(StepDefinition.ValueSource.from("json")))
                .run(TestStepHandlers.PrimaryOutcome.class);
    }

    private static StepDefinition primitive() {
        return StepDefinition.named("example.primitive", "1")
                .primaryOutcome("ok")
                .receive("value", ValueShape.NUMBER)
                .returns("value", ValueShape.NUMBER)
                .run(TestStepHandlers.PrimaryIdentity.class);
    }

    private static String baseProject(final String stepInputs) {
        return """
                {"format":1,"id":"generic-project","nodes":[
                  %s,
                  {"id":"trigger","use":"example.trigger","inputs":{},"examples":%s},
                  {"id":"step","use":"example.step","inputs":%s}
                ],"links":[
                  {"from":"app.start","to":"trigger"},
                  {"from":"trigger.next","to":"step"},
                  {"from":"step.next","to":"end"}
                ]}
                """.formatted(appNode(), examples(), stepInputs);
    }

    private static String simpleProject(final String stepUse, final String stepInputs) {
        return """
                {"format":1,"id":"simple-project","nodes":[
                  %s,
                  {"id":"trigger","use":"example.trigger","inputs":{},"examples":%s},
                  {"id":"step","use":"%s","inputs":%s}
                ],"links":[
                  {"from":"app.start","to":"trigger"},
                  {"from":"trigger.next","to":"step"},
                  {"from":"step.next","to":"end"}
                ]}
                """.formatted(appNode(), examples(), stepUse, stepInputs);
    }

    private static String primitiveProject(final String receives, final String returns) {
        return """
                {"format":1,"id":"ported-project","nodes":[
                  %s,
                  {"id":"trigger","use":"example.trigger","inputs":{},"examples":%s},
                  {"id":"step","use":"example.primitive","inputs":{},
                   "receives":%s,"returns":%s}
                ],"links":[
                  {"from":"app.start","to":"trigger"},
                  {"from":"trigger.next","to":"step"},
                  {"from":"step.ok","to":"end"}
                ]}
                """.formatted(appNode(), examples(), receives, returns);
    }

    private static String appNode() {
        return "{\"id\":\"app\",\"use\":\"railix.app\",\"inputs\":{}}";
    }

    private static String examples() {
        return "[{\"name\":\"sample\",\"payload\":[],\"context\":{\"payload\":{\"value\":4}}}]";
    }

    private static String inputs() {
        return """
                {"json":4,"path":["context","payload","value"],
                "choice":{"option":"literal","inputs":{"literal":"name"}},
                "steps":[{"use":"example.primitive","inputs":{}}]}
                """.replace("\n", "").replace("                ", "");
    }

    private static void assertRejected(
            final String source,
            final StepCatalog catalog,
            final String code,
            final String path
    ) {
        final CompileResult result = ProjectCompiler.compileApplication(source, catalog);
        assertThat(result).isInstanceOf(CompileResult.Rejected.class);
        assertThat(((CompileResult.Rejected) result).diagnostics()).hasSize(1);
        final Diagnostic diagnostic = ((CompileResult.Rejected) result).diagnostics().getFirst();
        assertThat(diagnostic.code()).isEqualTo(code);
        assertThat(diagnostic.path()).isEqualTo(path);
    }

    private static void assertCompiled(final String source, final StepCatalog catalog) {
        assertThat(ProjectCompiler.compileApplication(source, catalog))
                .isInstanceOf(CompileResult.Compiled.class);
    }
}
