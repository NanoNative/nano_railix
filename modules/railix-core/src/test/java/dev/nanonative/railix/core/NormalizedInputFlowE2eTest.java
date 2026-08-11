package dev.nanonative.railix.core;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.CompiledProject;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;

import static dev.nanonative.railix.core.CreatorFirstProjectCompilationE2eTest.contextCatalog;
import static dev.nanonative.railix.core.CreatorFirstProjectCompilationE2eTest.contextProject;
import static org.assertj.core.api.Assertions.assertThat;

final class NormalizedInputFlowE2eTest {
    @ParameterizedTest(name = "{0} direct field mapping")
    @EnumSource(RailixData.Format.class)
    void normalizedInputExecutesTheSameFieldMapping(final RailixData.Format format) {
        final RunResult.Succeeded result = (RunResult.Succeeded) run(format, Sample.DIRECT);

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("railix"));
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
    void normalizedMissingFieldPreservesTheTargetAndContinues(final RailixData.Format format) {
        final RunResult.Succeeded result = (RunResult.Succeeded) run(format, Sample.MISSING);

        assertThat(result.steps()).containsExactly(
                new RunResult.StepExecution("normalise-name", "next"),
                new RunResult.StepExecution("return-name", "next")
        );
        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.nullValue());
    }

    @ParameterizedTest(name = "{0} nested object mapping")
    @EnumSource(RailixData.Format.class)
    void normalizedNestedObjectExecutesTheSameFieldMapping(final RailixData.Format format) {
        final RunResult.Succeeded result = (RunResult.Succeeded) run(format, Sample.NESTED_OBJECT);

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("railix"));
    }

    @ParameterizedTest(name = "{0} nested array mapping")
    @EnumSource(RailixData.Format.class)
    void normalizedNestedArrayExecutesTheSameFieldMapping(final RailixData.Format format) {
        final RunResult.Succeeded result = (RunResult.Succeeded) run(format, Sample.NESTED_ARRAY);

        assertThat(result.context().values().get("result")).isEqualTo(RailixValue.string("railix"));
    }

    private static RunResult run(final RailixData.Format format, final Sample sample) {
        final RailixData.Normalized normalized = (RailixData.Normalized) RailixData.normalize(
                format,
                document(format, sample).getBytes(StandardCharsets.UTF_8)
        );
        final CompiledProject project = ((CompileResult.Compiled) ProjectCompiler.compile(
                project(sample),
                contextCatalog()
        )).project();
        return project.run(
                "command",
                new CompiledProject.StreamItem(false, (RailixValue.ObjectValue) normalized.value())
        );
    }

    private static String project(final Sample sample) {
        return switch (sample) {
            case NESTED_OBJECT -> contextProject()
                    .replace(
                            "\"payload\":{\"name\":\"Hello RAILIX\"}",
                            "\"payload\":{\"person\":{\"name\":\"Hello RAILIX\"}}"
                    )
                    .replace(
                            "[\"context\",\"payload\",\"name\"]",
                            "[\"context\",\"payload\",\"person\",\"name\"]"
                    );
            case NESTED_ARRAY -> contextProject()
                    .replace(
                            "\"payload\":{\"name\":\"Hello RAILIX\"}",
                            "\"payload\":{\"people\":[{\"name\":\"Hello RAILIX\"}]}"
                    )
                    .replace(
                            "[\"context\",\"payload\",\"name\"]",
                            "[\"context\",\"payload\",\"people\",0,\"name\"]"
                    );
            default -> contextProject();
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
