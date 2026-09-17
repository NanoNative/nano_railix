package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Execution(ExecutionMode.SAME_THREAD)
final class NormalizedInputFlowE2eTest extends CreatorServerE2eSupport {

    @ParameterizedTest(name = "{0} direct field mapping")
    @EnumSource(RailixData.Format.class)
    void normalizedInputExecutesTheSameFieldMapping(final RailixData.Format format) throws Exception {
        final RailixValue.ObjectValue result = run(format, Sample.DIRECT);

        assertThat(context(result).values().get("result")).isEqualTo(RailixValue.string("railix"));
    }

    @ParameterizedTest(name = "{0} malformed input rejection")
    @EnumSource(RailixData.Format.class)
    void malformedInputKeepsItsFormatDiagnostic(final RailixData.Format format) {
        final RailixData.Invalid result = (RailixData.Invalid) RailixData.normalize(
                format,
                document(format, Sample.MALFORMED).getBytes(StandardCharsets.UTF_8)
        );

        assertThat(result.code()).isEqualTo("DATA_" + format + "_INVALID");
    }

    @ParameterizedTest(name = "{0} missing mapped field")
    @EnumSource(RailixData.Format.class)
    void normalizedMissingFieldPreservesTheTargetAndContinues(final RailixData.Format format) throws Exception {
        final RailixValue.ObjectValue result = run(format, Sample.MISSING);

        assertThat(context(result).values().get("result")).isEqualTo(RailixValue.nullValue());
    }

    @ParameterizedTest(name = "{0} nested object mapping")
    @EnumSource(RailixData.Format.class)
    void normalizedNestedObjectExecutesTheSameFieldMapping(final RailixData.Format format) throws Exception {
        final RailixValue.ObjectValue result = run(format, Sample.NESTED_OBJECT);

        assertThat(context(result).values().get("result")).isEqualTo(RailixValue.string("railix"));
    }

    @ParameterizedTest(name = "{0} nested array mapping")
    @EnumSource(RailixData.Format.class)
    void normalizedNestedArrayExecutesTheSameFieldMapping(final RailixData.Format format) throws Exception {
        final RailixValue.ObjectValue result = run(format, Sample.NESTED_ARRAY);

        assertThat(context(result).values().get("result")).isEqualTo(RailixValue.string("railix"));
    }

    private RailixValue.ObjectValue run(final RailixData.Format format, final Sample sample) throws Exception {
        final RailixData.Normalized normalized = (RailixData.Normalized) RailixData.normalize(
                format,
                document(format, sample).getBytes(StandardCharsets.UTF_8)
        );
        final RailixValue.ObjectValue context = (RailixValue.ObjectValue) normalized.value();
        final Sample application = application(sample);
        final Path workspace = directory.resolve(format.name().toLowerCase() + "-" + sample.name().toLowerCase());
        Files.createDirectories(workspace);
        final Path project = workspace.resolve("railix.project.json");
        Files.writeString(project, project(
                application,
                format.name().toLowerCase() + "-" + sample.name().toLowerCase(),
                context
        ), StandardCharsets.UTF_8);
        try (CreatorServer creator = CreatorServer.start(0, project, directory.resolve("railix-home"))) {
            awaitExample(creator);
            final HttpResponse<String> response = request(
                    creator.baseUri(),
                    "GET",
                    "/api/examples/command:0/view",
                    ""
            );
            assertThat(response.statusCode()).isEqualTo(200);
            final RailixJson.Result parsed = RailixJson.parse(response.body());
            if (!(parsed instanceof RailixJson.Parsed(RailixValue.ObjectValue view))
                    || !(view.values().get("result") instanceof RailixValue.ObjectValue result)) {
                throw new AssertionError("Example has no result projection: " + response.body());
            }
            return result;
        }
    }

    private static void awaitExample(final CreatorServer creator) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        RailixValue.ObjectValue examples;
        do {
            examples = examples(creator.baseUri());
            if (number(examples, "completed") == 1) {
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Example did not complete: " + examples);
    }

    private static RailixValue.ObjectValue context(final RailixValue.ObjectValue result) {
        return (RailixValue.ObjectValue) result.values().get("context");
    }

    private static Sample application(final Sample sample) {
        return sample == Sample.MISSING ? Sample.DIRECT : sample;
    }

    private static String project(
            final Sample sample,
            final String exampleName,
            final RailixValue.ObjectValue context
    ) {
        final String direct = """
                {"format":1,"id":"normalized-input","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":%s,"payload":%s,"context":%s}
                  ]},
                  {"id":"normalise-name","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","name"],
                    "value":[{"option":"current","inputs":{}}],
                    "steps":[{"use":"text.lowercase","inputs":{}}]
                  }},
                  {"id":"return-name","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],
                    "value":[{"option":"field","inputs":{"source":["context","payload","name"]}}],
                    "steps":[]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"normalise-name"},
                  {"from":"normalise-name.next","to":"return-name"},
                  {"from":"return-name.next","to":"end"}
                ]}
                """.formatted(
                RailixJson.write(RailixValue.string(exampleName)),
                RailixJson.write(context.values().get("payload")),
                RailixJson.write(context)
        );
        return switch (sample) {
            case NESTED_OBJECT -> direct
                    .replace("[\"context\",\"payload\",\"name\"]",
                            "[\"context\",\"payload\",\"person\",\"name\"]");
            case NESTED_ARRAY -> direct
                    .replace("[\"context\",\"payload\",\"name\"]",
                            "[\"context\",\"payload\",\"people\",0,\"name\"]");
            default -> direct;
        };
    }

    private static String document(final RailixData.Format format, final Sample sample) {
        return switch (format) {
            case JSON -> switch (sample) {
                case DIRECT -> "{\"payload\":{\"name\":\"RAILIX\"}}";
                case MISSING -> "{\"payload\":{}}";
                case NESTED_OBJECT -> "{\"payload\":{\"person\":{\"name\":\"RAILIX\"}}}";
                case NESTED_ARRAY -> "{\"payload\":{\"people\":[{\"name\":\"RAILIX\"}]}}";
                case MALFORMED -> "{";
            };
            case YAML -> switch (sample) {
                case DIRECT -> "payload:\n  name: \"RAILIX\"";
                case MISSING -> "payload: {}";
                case NESTED_OBJECT -> "payload:\n  person:\n    name: \"RAILIX\"";
                case NESTED_ARRAY -> "payload:\n  people:\n    -\n      name: \"RAILIX\"";
                case MALFORMED -> "payload: \"RAILIX";
            };
            case XML -> switch (sample) {
                case DIRECT -> """
                        <object><field name="payload"><object>
                          <field name="name"><string>RAILIX</string></field>
                        </object></field></object>
                        """;
                case MISSING -> """
                        <object><field name="payload"><object/></field></object>
                        """;
                case NESTED_OBJECT -> """
                        <object><field name="payload"><object>
                          <field name="person"><object>
                            <field name="name"><string>RAILIX</string></field>
                          </object></field>
                        </object></field></object>
                        """;
                case NESTED_ARRAY -> """
                        <object><field name="payload"><object>
                          <field name="people"><array><item><object>
                            <field name="name"><string>RAILIX</string></field>
                          </object></item></array></field>
                        </object></field></object>
                        """;
                case MALFORMED -> "<object>";
            };
        };
    }

    private enum Sample {
        DIRECT,
        MALFORMED,
        MISSING,
        NESTED_OBJECT,
        NESTED_ARRAY
    }
}
