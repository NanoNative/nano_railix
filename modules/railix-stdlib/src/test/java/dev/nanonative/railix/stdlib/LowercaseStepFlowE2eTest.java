package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LowercaseStepFlowE2eTest {
    @Test
    void committedFlowProducesItsCommittedOutput() throws IOException {
        final Path example = example();

        assertThat(run(example)).isEqualTo(readObject(example.resolve("expected-output.json")));
    }

    @Test
    void validTurkishLanguageTagRunsThroughTheStandardStep() throws IOException {
        assertThat(run(
                configured("tr"),
                RailixValue.object(Map.of("text", RailixValue.string("I")))
        )).isEqualTo(RailixValue.object(Map.of("text", RailixValue.string("ı"))));
    }

    @Test
    void mixedCaseTurkishLanguageTagUsesTurkishCaseRules() throws IOException {
        assertThat(run(
                configured("TR"),
                RailixValue.object(Map.of("text", RailixValue.string("I")))
        )).isEqualTo(RailixValue.object(Map.of("text", RailixValue.string("ı"))));
    }

    @Test
    void turkishPrimaryWithExtlangUsesTheEffectiveLanguage() throws IOException {
        assertThat(run(
                configured("tr-foo"),
                RailixValue.object(Map.of("text", RailixValue.string("I")))
        )).isEqualTo(RailixValue.object(Map.of("text", RailixValue.string("i"))));
    }

    @Test
    void uppercaseExtlangUsesTheEffectiveLanguage() throws IOException {
        assertThat(run(
                configured("tr-FOO"),
                RailixValue.object(Map.of("text", RailixValue.string("I")))
        )).isEqualTo(RailixValue.object(Map.of("text", RailixValue.string("i"))));
    }

    @Test
    void numericRegionDoesNotBecomeAnExtlang() throws IOException {
        assertThat(run(
                configured("tr-419"),
                RailixValue.object(Map.of("text", RailixValue.string("I")))
        )).isEqualTo(RailixValue.object(Map.of("text", RailixValue.string("ı"))));
    }

    @Test
    void validAzeriLanguageTagUsesAzeriCaseRules() throws IOException {
        assertThat(run(
                configured("az-Latn-AZ"),
                RailixValue.object(Map.of("text", RailixValue.string("I")))
        )).isEqualTo(RailixValue.object(Map.of("text", RailixValue.string("ı"))));
    }

    @Test
    void validLithuanianLanguageTagUsesLithuanianCaseRules() throws IOException {
        assertThat(run(
                configured("lt-LT"),
                RailixValue.object(Map.of("text", RailixValue.string("I\u0301")))
        )).isEqualTo(RailixValue.object(Map.of("text", RailixValue.string("i\u0307\u0301"))));
    }

    @Test
    void validThaiLanguageTagUsesThaiWordBoundaries() throws IOException {
        assertThat(run(
                configured("th"),
                RailixValue.object(Map.of("text", RailixValue.string("AภาษาΣ")))
        )).isEqualTo(RailixValue.object(Map.of("text", RailixValue.string("aภาษาσ"))));
    }

    @Test
    void ordinaryLanguageTagUsesRootCaseRules() throws IOException {
        assertThat(run(
                configured("en-US"),
                RailixValue.object(Map.of("text", RailixValue.string("I")))
        )).isEqualTo(RailixValue.object(Map.of("text", RailixValue.string("i"))));
    }

    @Test
    void longerLanguageBeginningWithTrDoesNotUseTurkishRules() throws IOException {
        assertThat(run(
                configured("tricky"),
                RailixValue.object(Map.of("text", RailixValue.string("I")))
        )).isEqualTo(RailixValue.object(Map.of("text", RailixValue.string("i"))));
    }

    @Test
    void emptyLanguageTagIsRejectedBeforeTheStandardStepRuns() throws IOException {
        assertLanguageTagRejected("");
    }

    @Test
    void malformedLanguageTagIsRejectedBeforeTheStandardStepRuns() throws IOException {
        assertLanguageTagRejected("not_a_tag");
    }

    @Test
    void truncatedLanguageTagIsRejectedBeforeTheStandardStepRuns() throws IOException {
        assertLanguageTagRejected("en-");
    }

    private static RailixValue.ObjectValue run(final Path example) throws IOException {
        return run(
                Files.readString(example.resolve("railix.flow.json")),
                readObject(example.resolve("input.json"))
        );
    }

    private static RailixValue.ObjectValue run(
            final String source,
            final RailixValue.ObjectValue input
    ) {
        final CompileResult result = FlowCompiler.compile(source, StandardLibrary.catalog());
        if (!(result instanceof CompileResult.Compiled compiled)) {
            throw new AssertionError("Committed example did not compile: " + result);
        }
        final RunResult run = compiled.flow().run(input);
        if (!(run instanceof RunResult.Succeeded succeeded)) {
            throw new AssertionError("Committed example did not succeed: " + run);
        }
        return succeeded.outputs();
    }

    private static void assertLanguageTagRejected(final String languageTag) throws IOException {
        assertThat(FlowCompiler.compile(configured(languageTag), StandardLibrary.catalog()))
                .isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                        "FLOW_STEP_CONFIG_FORMAT_MISMATCH",
                        "Step configuration languageTag requires format language-tag.",
                        "steps.lowercase.config.languageTag"
                ))));
    }

    private static String configured(final String languageTag) throws IOException {
        return Files.readString(example().resolve("railix.flow.json")).replace(
                "\"config\": {}",
                "\"config\": {\"languageTag\": \"" + languageTag + "\"}"
        );
    }

    private static Path example() {
        return Path.of("..", "..", "examples", "lowercase-app")
                .toAbsolutePath()
                .normalize();
    }

    private static RailixValue.ObjectValue readObject(final Path path) throws IOException {
        final RailixJson.Result result = RailixJson.parse(Files.readString(path));
        if (result instanceof RailixJson.Parsed parsed
                && parsed.value() instanceof RailixValue.ObjectValue object) {
            return object;
        }
        throw new AssertionError("Committed example value is not a JSON object: " + path);
    }
}
