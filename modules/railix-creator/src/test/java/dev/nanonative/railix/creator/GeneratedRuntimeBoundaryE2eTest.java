package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepDefinition;
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
import thirdparty.conformance.RuntimeBoundaryProbeStep;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(90)
final class GeneratedRuntimeBoundaryE2eTest {
    private GeneratedApplicationFixture application;

    @BeforeAll
    void startGeneratedApplication(@TempDir final Path workspace) throws Exception {
        application = GeneratedApplicationFixture.start(
                workspace,
                project(),
                definitions(),
                RuntimeBoundaryProbeStep.class
        );
    }

    @AfterAll
    void stopGeneratedApplication() {
        if (application != null) {
            application.close();
        }
    }

    @Test
    void nestedInputBeyondMaximumDepthIsRejectedByTheGeneratedApplication() throws Exception {
        final DevelopmentApplication.Response response = request(
                "depth",
                "{\"payload\":{\"value\":{\"nested\":{}}}}"
        );

        assertThat(response).extracting(
                        DevelopmentApplication.Response::status,
                        DevelopmentApplication.Response::body
                )
                .containsExactly(
                        422,
                        "{\"diagnostics\":[{\"code\":\"RUN_NESTED_INPUT_INCOMPATIBLE\","
                                + "\"message\":\"Nested Step runtime.nested.depth rejects value: "
                                + "Value exceeds maximum container depth 1.\","
                                + "\"path\":\"nodes[2].inputs.steps[0]\"}],"
                                + "\"status\":\"rejected\"}"
                );
    }

    @Test
    void nestedInputBeyondMaximumBytesIsRejectedByTheGeneratedApplication() throws Exception {
        final DevelopmentApplication.Response response = request(
                "bytes",
                "{\"payload\":{\"value\":\"Hello RAILIX\"}}"
        );

        assertThat(response).extracting(
                        DevelopmentApplication.Response::status,
                        DevelopmentApplication.Response::body
                )
                .containsExactly(
                        422,
                        "{\"diagnostics\":[{\"code\":\"RUN_NESTED_INPUT_INCOMPATIBLE\","
                                + "\"message\":\"Nested Step runtime.nested.bytes rejects value: "
                                + "Canonical JSON exceeds 13 bytes.\","
                                + "\"path\":\"nodes[4].inputs.steps[0]\"}],"
                                + "\"status\":\"rejected\"}"
                );
    }

    @Test
    void invalidNestedOutputFailsTheGeneratedApplication() throws Exception {
        final DevelopmentApplication.Response response = request(
                "invalid-output",
                "{\"payload\":{\"value\":\"Hello RAILIX\"}}"
        );

        assertThat(response).extracting(
                        DevelopmentApplication.Response::status,
                        DevelopmentApplication.Response::body
                )
                .containsExactly(
                        500,
                        "{\"failure\":{\"code\":\"STEP_OUTPUT_INVALID\","
                                + "\"message\":\"Nested Step returned an incompatible value: "
                                + "Value contains an unpaired Unicode surrogate.\","
                                + "\"path\":\"nodes[6].inputs.steps[0]\","
                                + "\"step\":\"runtime.nested.invalid-output\"},"
                                + "\"status\":\"failed\"}"
                );
    }

    @Test
    void missingRequiredTriggerResultIsRejectedByTheGeneratedApplication() throws Exception {
        final DevelopmentApplication.Response response = request("required-result", "{\"payload\":{}}");

        assertThat(response).extracting(
                        DevelopmentApplication.Response::status,
                        DevelopmentApplication.Response::body
                )
                .containsExactly(
                        422,
                        "{\"diagnostics\":[{\"code\":\"RUN_RESULT_REQUIRED\","
                                + "\"message\":\"Trigger result is missing: result.\","
                                + "\"path\":\"context.result\"}],"
                                + "\"status\":\"rejected\"}"
                );
    }

    private DevelopmentApplication.Response request(final String trigger, final String body) throws IOException {
        return application.runTest(trigger, body);
    }

    private static List<StepDefinition> definitions() {
        return List.of(
                nested(
                        "runtime.nested.bytes",
                        ValueShape.STRING,
                        ValueRefinement.canonical().withMaxJsonBytes(13),
                        ValueShape.STRING,
                        ValueRefinement.canonical().withMaxJsonBytes(13),
                        false
                ),
                nested(
                        "runtime.nested.depth",
                        ValueShape.ANY,
                        ValueRefinement.canonical().withMaxDepth(1),
                        ValueShape.ANY,
                        ValueRefinement.canonical().withMaxDepth(1),
                        false
                ),
                nested(
                        "runtime.nested.invalid-output",
                        ValueShape.STRING,
                        ValueRefinement.canonical(),
                        ValueShape.ANY,
                        ValueRefinement.canonical().withMaxDepth(64),
                        true
                ),
                trigger("runtime.trigger.bytes", "application.runtime.bytes", false),
                trigger("runtime.trigger.depth", "application.runtime.depth", false),
                trigger("runtime.trigger.invalid-output", "application.runtime.invalid-output", false),
                trigger("runtime.trigger.required-result", "application.runtime.required-result", true)
        );
    }

    private static StepDefinition nested(
            final String id,
            final ValueShape receiveShape,
            final ValueRefinement receiveRefinement,
            final ValueShape returnShape,
            final ValueRefinement returnRefinement,
            final boolean invalid
    ) {
        return StepDefinition.named(id, "1")
                .primaryOutcome("ok")
                .receive("value", receiveShape, receiveRefinement)
                .returns("value", returnShape, returnRefinement)
                .input("invalid", StepDefinition.Input.json(ValueShape.BOOLEAN)
                        .defaultValue(RailixValue.bool(invalid)))
                .run(RuntimeBoundaryProbeStep.class);
    }

    private static StepDefinition trigger(final String id, final String source, final boolean requiredResult) {
        final StepDefinition.Builder definition = StepDefinition.named(id, "1")
                .kind(StepDefinition.Kind.TRIGGER)
                .source(source);
        if (requiredResult) {
            definition.requiredResult("result", ValueShape.STRING);
        }
        return definition.run(RuntimeBoundaryProbeStep.class);
    }

    private static String project() {
        return """
                {"format":1,"id":"generated-runtime-boundaries","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"depth","use":"runtime.trigger.depth","inputs":{},"examples":[{
                    "name":"too-deep","payload":{"value":{"nested":{}}}
                  }]},
                  {"id":"depth-step","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","value"],
                    "value":[{"option":"current","inputs":{}}],
                    "steps":[{"use":"runtime.nested.depth","inputs":{}}]
                  }},
                  {"id":"bytes","use":"runtime.trigger.bytes","inputs":{},"examples":[{
                    "name":"too-large","payload":{"value":"Hello RAILIX"}
                  }]},
                  {"id":"bytes-step","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","value"],
                    "value":[{"option":"current","inputs":{}}],
                    "steps":[{"use":"runtime.nested.bytes","inputs":{}}]
                  }},
                  {"id":"invalid-output","use":"runtime.trigger.invalid-output","inputs":{},"examples":[{
                    "name":"invalid-output","payload":{"value":"Hello RAILIX"}
                  }]},
                  {"id":"invalid-output-step","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","value"],
                    "value":[{"option":"current","inputs":{}}],
                    "steps":[{"use":"runtime.nested.invalid-output","inputs":{}}]
                  }},
                  {"id":"required-result","use":"runtime.trigger.required-result","inputs":{},"examples":[{
                    "name":"missing-result","payload":{}
                  }]}
                ],"links":[
                  {"from":"app.start","to":"depth"},
                  {"from":"depth.next","to":"depth-step"},
                  {"from":"depth-step.next","to":"end"},
                  {"from":"app.start","to":"bytes"},
                  {"from":"bytes.next","to":"bytes-step"},
                  {"from":"bytes-step.next","to":"end"},
                  {"from":"app.start","to":"invalid-output"},
                  {"from":"invalid-output.next","to":"invalid-output-step"},
                  {"from":"invalid-output-step.next","to":"end"},
                  {"from":"app.start","to":"required-result"},
                  {"from":"required-result.next","to":"end"}
                ]}
                """;
    }

}
