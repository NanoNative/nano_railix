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
    @Test
    void missingProjectSourceIsRejected() {
        assertRejected(null, catalog(), "PROJECT_SOURCE_REQUIRED", "");
    }

    @Test
    void blankProjectSourceIsRejected() {
        assertRejected(" ", catalog(), "PROJECT_SOURCE_REQUIRED", "");
    }

    @Test
    void missingStepCatalogIsRejectedAtTheCompilerBoundary() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProjectCompiler.compileApplication(baseProject(inputs()), null))
                .withMessage("Step catalog cannot be Java null.");
    }

    @Test
    void malformedJsonIsRejected() {
        assertRejected("{", catalog(), "PROJECT_JSON_INVALID", "");
    }

    @Test
    void projectMustBeAnObject() {
        assertRejected("[]", catalog(), "PROJECT_OBJECT_REQUIRED", "");
    }

    @Test
    void unknownProjectFieldIsRejected() {
        assertRejected(baseProject(inputs()).replace("\"format\":1", "\"format\":1,\"unknown\":true"),
                catalog(), "PROJECT_FIELD_UNKNOWN", "unknown");
    }

    @Test
    void reusableDefinitionsAreCreatorMetadataNotCompilerInput() {
        assertRejected(baseProject(inputs()).replace(
                        "\"nodes\":[",
                        "\"flows\":[],\"nodes\":["
                ),
                catalog(), "PROJECT_FIELD_UNKNOWN", "flows");
    }

    @Test
    void missingProjectFormatIsRejected() {
        assertRejected(baseProject(inputs()).replace("\"format\":1,", ""),
                catalog(), "PROJECT_FORMAT_UNSUPPORTED", "format");
    }

    @Test
    void unsupportedProjectFormatIsRejected() {
        assertRejected(baseProject(inputs()).replace("\"format\":1", "\"format\":2"),
                catalog(), "PROJECT_FORMAT_UNSUPPORTED", "format");
    }

    @Test
    void missingProjectIdIsRejected() {
        assertRejected(baseProject(inputs()).replace("\"id\":\"generic-project\",", ""),
                catalog(), "PROJECT_ID_REQUIRED", "id");
    }

    @Test
    void projectIdMustBeSafe() {
        assertRejected(baseProject(inputs()).replace("generic-project", "Generic/Project"),
                catalog(), "PROJECT_ID_INVALID", "id");
    }

    @Test
    void projectIdCannotContainAnUnsafeInnerCharacter() {
        assertRejected(baseProject(inputs()).replace("generic-project", "generic_project"),
                catalog(), "PROJECT_ID_INVALID", "id");
    }

    @Test
    void nodesMustBeAnArray() {
        assertRejected("{\"format\":1,\"id\":\"generic-project\",\"nodes\":{},\"links\":[]}",
                catalog(), "PROJECT_NODES_ARRAY_REQUIRED", "nodes");
    }

    @Test
    void linksMustBeAnArray() {
        assertRejected("{\"format\":1,\"id\":\"generic-project\",\"nodes\":[" + appNode() + "],\"links\":{}}",
                catalog(), "PROJECT_LINKS_ARRAY_REQUIRED", "links");
    }

    @Test
    void nodeMustBeAnObject() {
        assertRejected(baseProject(inputs()).replace(appNode(), "\"app\""),
                catalog(), "PROJECT_NODE_OBJECT_REQUIRED", "nodes[0]");
    }

    @Test
    void unknownNodeFieldIsRejected() {
        assertRejected(baseProject(inputs()).replace("\"id\":\"step\"", "\"unknown\":true,\"id\":\"step\""),
                catalog(), "PROJECT_NODE_FIELD_UNKNOWN", "nodes[2].unknown");
    }

    @Test
    void nodePresentationIsCreatorMetadataNotCompilerInput() {
        assertRejected(baseProject(inputs()).replace(
                        "\"id\":\"step\"",
                        "\"id\":\"step\",\"presentation\":{\"name\":\"Visible only in Creator\"}"
                ),
                catalog(), "PROJECT_NODE_FIELD_UNKNOWN", "nodes[2].presentation");
    }

    @Test
    void reusableInvocationIsCreatorMetadataNotCompilerInput() {
        assertRejected(baseProject(inputs()).replace(
                        "\"id\":\"step\"",
                        "\"id\":\"step\",\"flow\":\"normalize-name\""
                ),
                catalog(), "PROJECT_NODE_FIELD_UNKNOWN", "nodes[2].flow");
    }

    @Test
    void nodeIdIsRequired() {
        assertRejected(baseProject(inputs()).replace("\"id\":\"step\",", ""),
                catalog(), "PROJECT_NODE_ID_REQUIRED", "nodes[2].id");
    }

    @Test
    void nodeIdMustBeSafe() {
        assertRejected(baseProject(inputs()).replace("\"id\":\"step\"", "\"id\":\"Step\""),
                catalog(), "PROJECT_NODE_ID_INVALID", "nodes[2].id");
    }

    @Test
    void nodeUseIsRequired() {
        assertRejected(baseProject(inputs()).replace("\"use\":\"example.step\",", ""),
                catalog(), "PROJECT_NODE_USE_REQUIRED", "nodes[2].use");
    }

    @Test
    void duplicateNodeIdIsRejected() {
        assertRejected(baseProject(inputs()).replace("\"id\":\"trigger\"", "\"id\":\"app\""),
                catalog(), "PROJECT_NODE_ID_DUPLICATE", "nodes[1].id");
    }

    @Test
    void unknownStepIsRejected() {
        assertRejected(baseProject(inputs()).replace("\"use\":\"example.step\"", "\"use\":\"example.unknown\""),
                catalog(), "PROJECT_STEP_UNKNOWN", "nodes[2].use");
    }

    @Test
    void unaryStepRequiresExplicitGraphReceiveBindings() {
        assertRejected(simpleProject("example.primitive", "{}"),
                catalog(), "PROJECT_NODE_RECEIVES_OBJECT_REQUIRED", "nodes[2].receives");
    }

    @Test
    void graphReceivePortMustBeDeclaredByTheStep() {
        assertRejected(primitiveProject(
                        "{\"other\":[\"context\",\"payload\",\"value\"]}",
                        "{\"value\":[\"context\",\"result\"]}"
                ), catalog(), "PROJECT_NODE_PORT_UNKNOWN", "nodes[2].receives.other");
    }

    @Test
    void graphReceivePortRequiresAPath() {
        assertRejected(primitiveProject("{}", "{\"value\":[\"context\",\"result\"]}"),
                catalog(), "PROJECT_NODE_PORT_REQUIRED", "nodes[2].receives.value");
    }

    @Test
    void graphReceivePortPathMustUseCanonicalSegments() {
        assertRejected(primitiveProject(
                        "{\"value\":\"context.payload.value\"}",
                        "{\"value\":[\"context\",\"result\"]}"
                ), catalog(), "PROJECT_CONTEXT_PATH_REQUIRED", "nodes[2].receives.value");
    }

    @Test
    void graphReturnPortMustBeDeclaredByTheStep() {
        assertRejected(primitiveProject(
                        "{\"value\":[\"context\",\"payload\",\"value\"]}",
                        "{\"other\":[\"context\",\"result\"]}"
                ), catalog(), "PROJECT_NODE_PORT_UNKNOWN", "nodes[2].returns.other");
    }

    @Test
    void graphReturnPortRequiresAPath() {
        assertRejected(primitiveProject("{\"value\":[\"context\",\"payload\",\"value\"]}", "{}"),
                catalog(), "PROJECT_NODE_PORT_REQUIRED", "nodes[2].returns.value");
    }

    @Test
    void graphReturnPortPathMustUseCanonicalSegments() {
        assertRejected(primitiveProject(
                        "{\"value\":[\"context\",\"payload\",\"value\"]}",
                        "{\"value\":\"context.result\"}"
                ), catalog(), "PROJECT_CONTEXT_PATH_REQUIRED", "nodes[2].returns.value");
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
    void nodeInputsMustBeAnObject() {
        assertRejected(baseProject(inputs()).replace("\"inputs\":{}", "\"inputs\":[]"),
                catalog(), "PROJECT_NODE_INPUTS_OBJECT_REQUIRED", "nodes[0].inputs");
    }

    @Test
    void projectRequiresTheReservedAppNode() {
        assertRejected(baseProject(inputs()).replace(appNode() + ",", ""),
                catalog(), "PROJECT_APP_REQUIRED", "nodes");
    }

    @Test
    void appNodeMustKeepItsReservedId() {
        assertRejected(baseProject(inputs())
                        .replace("\"id\":\"app\"", "\"id\":\"root\"")
                        .replace("app.start", "root.start"),
                catalog(), "PROJECT_APP_ID_INVALID", "nodes[0].id");
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

    @Test
    void undeclaredInputIsRejected() {
        assertRejected(baseProject(inputs().replace("\"steps\":[", "\"unknown\":true,\"steps\":[")),
                catalog(), "PROJECT_INPUT_UNKNOWN", "nodes[2].inputs.unknown");
    }

    @Test
    void jsonInputIsRequiredWhenNoDefaultExists() {
        assertRejected(baseProject(inputs().replace("\"json\":4,", "")),
                catalog(), "PROJECT_INPUT_REQUIRED", "nodes[2].inputs.json");
    }

    @Test
    void jsonInputMustMatchItsDeclaredShape() {
        assertRejected(baseProject(inputs().replace("\"json\":4", "\"json\":\"four\"")),
                catalog(), "PROJECT_INPUT_SHAPE_INVALID", "nodes[2].inputs.json");
    }

    @Test
    void jsonInputMustRemainWithinItsDeclaredRange() {
        assertRejected(baseProject(inputs().replace("\"json\":4", "\"json\":11")),
                catalog(), "PROJECT_INPUT_RANGE_INVALID", "nodes[2].inputs.json");
    }

    @Test
    void omittedOptionalJsonInputCompiles() {
        final StepDefinition optional = StepDefinition.named("example.step", "1")
                .input("value", Input.json(ValueShape.STRING).optional())
                .run(TestStepHandlers.PrimaryOutcome.class);

        assertCompiled(simpleProject("example.step", "{}"), catalog(app(), trigger(), optional, primitive()));
    }

    @Test
    void pathInputIsRequiredWhenNoDefaultExists() {
        assertRejected(baseProject(inputs().replace("\"path\":[\"context\",\"payload\",\"value\"],", "")),
                catalog(), "PROJECT_INPUT_REQUIRED", "nodes[2].inputs.path");
    }

    @Test
    void pathInputMustContainAContextChild() {
        assertRejected(baseProject(inputs().replace("[\"context\",\"payload\",\"value\"]", "[\"context\"]")),
                catalog(), "PROJECT_CONTEXT_PATH_REQUIRED", "nodes[2].inputs.path");
    }

    @Test
    void pathInputMustStartAtContext() {
        assertRejected(baseProject(inputs().replace("[\"context\",\"payload\",\"value\"]", "[\"payload\",\"value\"]")),
                catalog(), "PROJECT_CONTEXT_PATH_ROOT_REQUIRED", "nodes[2].inputs.path[0]");
    }

    @Test
    void pathInputMustContainOnlyFieldOrIndexSegments() {
        assertRejected(baseProject(inputs().replace("[\"context\",\"payload\",\"value\"]", "[\"context\",true]")),
                catalog(), "PROJECT_CONTEXT_PATH_ELEMENT_INVALID", "nodes[2].inputs.path[1]");
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
    void negativePathIndexIsRejected() {
        assertRejected(baseProject(inputs().replace(
                        "[\"context\",\"payload\",\"value\"]", "[\"context\",-1]")),
                catalog(), "PROJECT_CONTEXT_PATH_ELEMENT_INVALID", "nodes[2].inputs.path[1]");
    }

    @Test
    void fractionalPathIndexIsRejected() {
        assertRejected(baseProject(inputs().replace(
                        "[\"context\",\"payload\",\"value\"]", "[\"context\",1.5]")),
                catalog(), "PROJECT_CONTEXT_PATH_ELEMENT_INVALID", "nodes[2].inputs.path[1]");
    }

    @Test
    void pathDeeperThanTheCanonicalContextLimitIsRejected() {
        final String deepPath = "[\"context\"," + "\"field\",".repeat(63) + "\"field\"]";

        assertRejected(baseProject(inputs().replace(
                        "[\"context\",\"payload\",\"value\"]", deepPath)),
                catalog(), "PROJECT_CONTEXT_PATH_TOO_DEEP", "nodes[2].inputs.path");
    }

    @Test
    void optionsInputIsRequiredWhenNoDefaultExists() {
        assertRejected(baseProject(inputs().replace("\"choice\":{\"option\":\"literal\",\"inputs\":{\"literal\":\"name\"}},", "")),
                catalog(), "PROJECT_INPUT_REQUIRED", "nodes[2].inputs.choice");
    }

    @Test
    void optionsInputMustBeAnObject() {
        assertRejected(baseProject(inputs().replace("{\"option\":\"literal\",\"inputs\":{\"literal\":\"name\"}}", "\"literal\"")),
                catalog(), "PROJECT_OPTION_OBJECT_REQUIRED", "nodes[2].inputs.choice");
    }

    @Test
    void optionsInputMustNameADeclaredOption() {
        assertRejected(baseProject(inputs().replace("\"option\":\"literal\"", "\"option\":\"unknown\"")),
                catalog(), "PROJECT_OPTION_UNKNOWN", "nodes[2].inputs.choice.option");
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
    void unknownOptionFieldIsRejected() {
        assertRejected(baseProject(inputs().replace(
                        "{\"option\":\"literal\",\"inputs\":{\"literal\":\"name\"}}",
                        "{\"option\":\"literal\",\"inputs\":{\"literal\":\"name\"},\"unknown\":true}")),
                catalog(), "PROJECT_OPTION_FIELD_UNKNOWN", "nodes[2].inputs.choice.unknown");
    }

    @Test
    void optionNameIsRequired() {
        assertRejected(baseProject(inputs().replace(
                        "{\"option\":\"literal\",\"inputs\":{\"literal\":\"name\"}}",
                        "{\"inputs\":{\"literal\":\"name\"}}")),
                catalog(), "PROJECT_OPTION_REQUIRED", "nodes[2].inputs.choice.option");
    }

    @Test
    void optionInputsMustBeAnObject() {
        assertRejected(baseProject(inputs().replace(
                        "{\"option\":\"literal\",\"inputs\":{\"literal\":\"name\"}}",
                        "{\"option\":\"literal\",\"inputs\":[]}")),
                catalog(), "PROJECT_OPTION_INPUTS_OBJECT_REQUIRED", "nodes[2].inputs.choice.inputs");
    }

    @Test
    void selectedOptionRejectsUnknownChildInput() {
        assertRejected(baseProject(inputs().replace(
                        "\"literal\":\"name\"", "\"literal\":\"name\",\"unknown\":true")),
                catalog(), "PROJECT_INPUT_UNKNOWN", "nodes[2].inputs.choice.inputs.unknown");
    }

    @Test
    void selectedOptionMustSupplyItsRequiredChildren() {
        assertRejected(baseProject(inputs().replace("\"literal\":\"name\"", "")),
                catalog(), "PROJECT_INPUT_REQUIRED", "nodes[2].inputs.choice.inputs.literal");
    }

    @Test
    void stepsInputMustBeAnArray() {
        assertRejected(baseProject(inputs().replace("\"steps\":[{\"use\":\"example.primitive\",\"inputs\":{}}]", "\"steps\":{}")),
                catalog(), "PROJECT_STEPS_ARRAY_REQUIRED", "nodes[2].inputs.steps");
    }

    @Test
    void nestedStepMustBeAnObject() {
        assertRejected(baseProject(inputs().replace("[{\"use\":\"example.primitive\",\"inputs\":{}}]", "[\"example.primitive\"]")),
                catalog(), "PROJECT_NESTED_STEP_OBJECT_REQUIRED", "nodes[2].inputs.steps[0]");
    }

    @Test
    void nestedStepMustBeRegistered() {
        assertRejected(baseProject(inputs().replace("example.primitive", "example.unknown")),
                catalog(), "PROJECT_NESTED_STEP_UNKNOWN", "nodes[2].inputs.steps[0].use");
    }

    @Test
    void unknownNestedStepFieldIsRejected() {
        assertRejected(baseProject(inputs().replace(
                        "{\"use\":\"example.primitive\",\"inputs\":{}}",
                        "{\"use\":\"example.primitive\",\"inputs\":{},\"unknown\":true}")),
                catalog(), "PROJECT_NESTED_STEP_FIELD_UNKNOWN", "nodes[2].inputs.steps[0].unknown");
    }

    @Test
    void nestedStepUseIsRequired() {
        assertRejected(baseProject(inputs().replace(
                        "{\"use\":\"example.primitive\",\"inputs\":{}}",
                        "{\"inputs\":{}}")),
                catalog(), "PROJECT_NESTED_STEP_USE_REQUIRED", "nodes[2].inputs.steps[0].use");
    }

    @Test
    void nestedStepMustMatchTheDeclaredKind() {
        assertRejected(baseProject(inputs().replace("example.primitive", "example.trigger")),
                catalog(), "PROJECT_NESTED_STEP_KIND_INVALID", "nodes[2].inputs.steps[0].use");
    }

    @Test
    void nestedStepInputsMustBeAnObject() {
        assertRejected(baseProject(inputs().replace("\"inputs\":{}}]", "\"inputs\":[]}]")),
                catalog(), "PROJECT_NESTED_INPUTS_OBJECT_REQUIRED", "nodes[2].inputs.steps[0].inputs");
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

    @Test
    void nonTriggerNodeCannotDeclareExamples() {
        assertRejected(baseProject(inputs()).replace("\"inputs\":" + inputs() + "}",
                        "\"inputs\":" + inputs() + ",\"examples\":[]}"),
                catalog(), "PROJECT_NODE_EXAMPLES_UNSUPPORTED", "nodes[2].examples");
    }

    @Test
    void triggerRequiresAtLeastOneExample() {
        assertRejected(baseProject(inputs()).replace(",\"examples\":" + examples(), ""),
                catalog(), "PROJECT_TRIGGER_EXAMPLE_REQUIRED", "nodes[1].examples");
    }

    @Test
    void triggerExampleRejectsUnknownFields() {
        assertRejected(baseProject(inputs()).replace("\"name\":\"sample\"", "\"unknown\":true,\"name\":\"sample\""),
                catalog(), "PROJECT_TRIGGER_EXAMPLE_FIELD_UNKNOWN", "nodes[1].examples[0].unknown");
    }

    @Test
    void triggerExampleMustBeAnObject() {
        assertRejected(baseProject(inputs()).replace(examples(), "[\"sample\"]"),
                catalog(), "PROJECT_TRIGGER_EXAMPLE_OBJECT_REQUIRED", "nodes[1].examples[0]");
    }

    @Test
    void triggerExampleNameIsRequired() {
        assertRejected(baseProject(inputs()).replace(examples(),
                        "[{\"payload\":[],\"context\":{\"payload\":{\"value\":4}}}]"),
                catalog(), "PROJECT_TRIGGER_EXAMPLE_NAME_REQUIRED", "nodes[1].examples[0].name");
    }

    @Test
    void triggerExampleNamesMustBeUnique() {
        assertRejected(baseProject(inputs()).replace(examples(),
                        "[{\"name\":\"sample\",\"payload\":4},"
                                + "{\"name\":\"sample\",\"payload\":5}]"),
                catalog(), "PROJECT_TRIGGER_EXAMPLE_NAME_DUPLICATE", "nodes[1].examples[1].name");
    }

    @Test
    void triggerExamplePayloadIsRequired() {
        assertRejected(baseProject(inputs()).replace(examples(), "[{\"name\":\"sample\"}]"),
                catalog(), "PROJECT_TRIGGER_EXAMPLE_PAYLOAD_REQUIRED", "nodes[1].examples[0].payload");
    }

    @Test
    void triggerExampleContextMustBeAnObjectWhenSupplied() {
        assertRejected(baseProject(inputs()).replace(examples(),
                        "[{\"name\":\"sample\",\"payload\":[],\"context\":[]}]"),
                catalog(), "PROJECT_TRIGGER_EXAMPLE_CONTEXT_OBJECT_REQUIRED", "nodes[1].examples[0].context");
    }

    @Test
    void triggerExamplesCannotClaimRuntime() {
        assertRejected(baseProject(inputs()).replace("\"context\":{\"payload\":{\"value\":4}}",
                        "\"context\":{\"runtime\":{}}"),
                catalog(), "PROJECT_TRIGGER_EXAMPLE_RUNTIME_RESERVED", "nodes[1].examples[0].context.runtime");
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

    @Test
    void linkMustBeAnObject() {
        assertRejected(baseProject(inputs()).replace("{\"from\":\"app.start\",\"to\":\"trigger\"}", "\"link\""),
                catalog(), "PROJECT_LINK_OBJECT_REQUIRED", "links[0]");
    }

    @Test
    void linkMustUseNodeAndOutcomeSyntax() {
        assertRejected(baseProject(inputs()).replace("\"from\":\"app.start\"", "\"from\":\"app\""),
                catalog(), "PROJECT_LINK_FROM_INVALID", "links[0].from");
    }

    @Test
    void unknownLinkFieldIsRejected() {
        assertRejected(baseProject(inputs()).replace(
                        "{\"from\":\"app.start\",\"to\":\"trigger\"}",
                        "{\"from\":\"app.start\",\"to\":\"trigger\",\"unknown\":true}"),
                catalog(), "PROJECT_LINK_FIELD_UNKNOWN", "links[0].unknown");
    }

    @Test
    void linkFromIsRequired() {
        assertRejected(baseProject(inputs()).replace(
                        "{\"from\":\"app.start\",\"to\":\"trigger\"}",
                        "{\"to\":\"trigger\"}"),
                catalog(), "PROJECT_LINK_FROM_REQUIRED", "links[0].from");
    }

    @Test
    void linkTargetIsRequired() {
        assertRejected(baseProject(inputs()).replace(
                        "{\"from\":\"app.start\",\"to\":\"trigger\"}",
                        "{\"from\":\"app.start\"}"),
                catalog(), "PROJECT_LINK_TO_REQUIRED", "links[0].to");
    }

    @Test
    void linkSourceMustExist() {
        assertRejected(baseProject(inputs()).replace("\"from\":\"app.start\"", "\"from\":\"unknown.start\""),
                catalog(), "PROJECT_LINK_SOURCE_UNKNOWN", "links[0].from");
    }

    @Test
    void linkOutcomeMustExist() {
        assertRejected(baseProject(inputs()).replace("\"from\":\"trigger.next\"", "\"from\":\"trigger.missing\""),
                catalog(), "PROJECT_LINK_OUTCOME_UNKNOWN", "links[1].from");
    }

    @Test
    void ordinaryOutcomeCanHaveOnlyOneConnection() {
        assertRejected(baseProject(inputs()).replace("{\"from\":\"step.next\",\"to\":\"end\"}",
                        "{\"from\":\"step.next\",\"to\":\"end\"},{\"from\":\"step.next\",\"to\":\"end\"}"),
                catalog(), "PROJECT_PORT_CONNECTION_DUPLICATE", "links[3].from");
    }

    @Test
    void linkTargetMustExist() {
        assertRejected(baseProject(inputs()).replace("\"to\":\"step\"", "\"to\":\"missing\""),
                catalog(), "PROJECT_LINK_TARGET_UNKNOWN", "links[1].to");
    }

    @Test
    void appMayConnectOnlyToTriggers() {
        assertRejected(baseProject(inputs()).replace("\"to\":\"trigger\"", "\"to\":\"step\""),
                catalog(), "PROJECT_APP_CONNECTION_INVALID", "links[0]");
    }

    @Test
    void triggerMayConnectOnlyToOrdinaryStepsOrEnd() {
        assertRejected(baseProject(inputs()).replace("\"to\":\"step\"", "\"to\":\"app\""),
                catalog(), "PROJECT_TRIGGER_CONNECTION_INVALID", "links[1]");
    }

    @Test
    void ordinaryStepMayConnectOnlyToOrdinaryStepsOrEnd() {
        assertRejected(baseProject(inputs()).replace(
                        "{\"from\":\"step.next\",\"to\":\"end\"}",
                        "{\"from\":\"step.next\",\"to\":\"trigger\"}"),
                catalog(), "PROJECT_STEP_CONNECTION_INVALID", "links[2]");
    }

    @Test
    void everyGraphNodeNeedsOneIncomingConnection() {
        assertRejected(baseProject(inputs()).replace("\"to\":\"step\"", "\"to\":\"end\""),
                catalog(), "PROJECT_NODE_INPUT_CONNECTION_REQUIRED", "nodes[2]");
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
