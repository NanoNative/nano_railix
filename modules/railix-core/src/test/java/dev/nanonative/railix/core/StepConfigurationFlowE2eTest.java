package dev.nanonative.railix.core;

import dev.nanonative.railix.core.fixtures.FlowFixtures;
import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepConfigurationFlowE2eTest {
    @Test
    void developerDefaultIsResolvedBeforeTheStepRuns() {
        assertThat(run(FlowFixtures.lowercaseFlow(), configurableLowercase())).isEqualTo(
                succeeded("i")
        );
    }

    @Test
    void explicitFlowConfigurationOverridesTheDeveloperDefault() {
        final String flow = FlowFixtures.lowercaseFlow().replace(
                "\"config\": {}",
                "\"config\": {\"languageTag\": \"tr\"}"
        );

        assertThat(run(flow, configurableLowercase())).isEqualTo(succeeded("ı"));
    }

    @Test
    void mixedCaseLanguageTagIsAcceptedWithoutRewritingTheFlow() {
        assertThat(run(configured("\"languageTag\": \"TR\""), configurableLowercase()))
                .isEqualTo(succeeded("ı"));
    }

    @Test
    void languageTagExtensionIsAccepted() {
        assertThat(run(
                configured("\"languageTag\": \"de-DE-u-co-phonebk\""),
                configurableLowercase()
        )).isEqualTo(succeeded("i"));
    }

    @Test
    void privateUseLanguageTagIsAccepted() {
        assertThat(run(configured("\"languageTag\": \"x-private\""), configurableLowercase()))
                .isEqualTo(succeeded("i"));
    }

    @Test
    void grandfatheredLanguageTagIsAccepted() {
        assertThat(run(configured("\"languageTag\": \"i-klingon\""), configurableLowercase()))
                .isEqualTo(succeeded("i"));
    }

    @Test
    void explicitRequiredConfigurationRuns() {
        assertThat(run(configured("\"languageTag\": \"tr\""), requiredLowercase())).isEqualTo(
                succeeded("ı")
        );
    }

    @Test
    void missingRequiredConfigurationRejectsCompilation() {
        assertDiagnostic(
                FlowFixtures.lowercaseFlow(),
                requiredLowercase(),
                "FLOW_STEP_CONFIG_REQUIRED",
                "Required Step configuration is missing: languageTag",
                "steps.lowercase.config.languageTag"
        );
    }

    @Test
    void unknownConfigurationRejectsCompilation() {
        assertDiagnostic(
                configured("\"unknown\": true"),
                configurableLowercase(),
                "FLOW_STEP_CONFIG_UNKNOWN",
                "Unknown Step configuration: unknown",
                "steps.lowercase.config.unknown"
        );
    }

    @Test
    void wrongConfigurationShapeRejectsCompilation() {
        assertDiagnostic(
                configured("\"languageTag\": 1"),
                configurableLowercase(),
                "FLOW_STEP_CONFIG_TYPE_MISMATCH",
                "Step configuration languageTag requires STRING but received NUMBER.",
                "steps.lowercase.config.languageTag"
        );
    }

    @Test
    void wrongRequiredConfigurationShapeProducesOnlyTheTypeDiagnostic() {
        assertDiagnostic(
                configured("\"languageTag\": 1"),
                requiredLowercase(),
                "FLOW_STEP_CONFIG_TYPE_MISMATCH",
                "Step configuration languageTag requires STRING but received NUMBER.",
                "steps.lowercase.config.languageTag"
        );
    }

    @Test
    void emptyLanguageTagRejectsCompilation() {
        assertFormatDiagnostic("");
    }

    @Test
    void whitespaceLanguageTagRejectsCompilation() {
        assertFormatDiagnostic(" ");
    }

    @Test
    void underscoreLanguageTagRejectsCompilation() {
        assertFormatDiagnostic("en_US");
    }

    @Test
    void truncatedLanguageTagRejectsCompilation() {
        assertFormatDiagnostic("en-");
    }

    @Test
    void emptyLanguageSubtagRejectsCompilation() {
        assertFormatDiagnostic("en--US");
    }

    @Test
    void malformedRequiredLanguageTagProducesOnlyTheFormatDiagnostic() {
        assertDiagnostic(
                configured("\"languageTag\": \"not_a_tag\""),
                requiredLowercase(),
                "FLOW_STEP_CONFIG_FORMAT_MISMATCH",
                "Step configuration languageTag requires format language-tag.",
                "steps.lowercase.config.languageTag"
        );
    }

    @Test
    void unformattedStringConfigurationRemainsUnrestricted() {
        assertThat(run(configured("\"languageTag\": \"not_a_tag\""), unformattedLowercase()))
                .isEqualTo(succeeded("i"));
    }

    private static RunResult run(final String flow, final StepDefinition definition) {
        final CompileResult result = FlowCompiler.compile(flow, StepCatalog.of(definition));
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        return ((CompileResult.Compiled) result).flow().run(
                RailixValue.object(Map.of("text", RailixValue.string("I")))
        );
    }

    private static RunResult.Succeeded succeeded(final String output) {
        return new RunResult.Succeeded(
                RailixValue.object(Map.of("text", RailixValue.string(output))),
                List.of(new RunResult.StepExecution("lowercase", "ok"))
        );
    }

    private static void assertDiagnostic(
            final String flow,
            final StepDefinition definition,
            final String code,
            final String message,
            final String path
    ) {
        assertThat(FlowCompiler.compile(flow, StepCatalog.of(definition))).isEqualTo(
                new CompileResult.Rejected(List.of(Diagnostic.atPath(code, message, path)))
        );
    }

    private static String configured(final String fields) {
        return FlowFixtures.lowercaseFlow().replace("\"config\": {}", "\"config\": {%s}".formatted(fields));
    }

    private static void assertFormatDiagnostic(final String languageTag) {
        assertDiagnostic(
                configured("\"languageTag\": \"" + languageTag + "\""),
                configurableLowercase(),
                "FLOW_STEP_CONFIG_FORMAT_MISMATCH",
                "Step configuration languageTag requires format language-tag.",
                "steps.lowercase.config.languageTag"
        );
    }

    private static StepDefinition configurableLowercase() {
        return lowercase(false, true);
    }

    private static StepDefinition requiredLowercase() {
        return lowercase(true, true);
    }

    private static StepDefinition unformattedLowercase() {
        return lowercase(false, false);
    }

    private static StepDefinition lowercase(final boolean required, final boolean formatted) {
        final StepDefinition.Builder builder = StepDefinition.named("text.lowercase", "1.0.0");
        if (required) {
            if (formatted) {
                builder.requiredConfig(
                        "languageTag",
                        ValueShape.string(),
                        StepDefinition.ConfigFormat.LANGUAGE_TAG
                );
            } else {
                builder.requiredConfig("languageTag", ValueShape.string());
            }
        } else if (formatted) {
            builder.config(
                    "languageTag",
                    ValueShape.string(),
                    StepDefinition.ConfigFormat.LANGUAGE_TAG,
                    RailixValue.string("und")
            );
        } else {
            builder.config("languageTag", ValueShape.string(), RailixValue.string("und"));
        }
        return builder
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output(
                        "text",
                        RailixValue.string(input.string("text").toLowerCase(
                                Locale.forLanguageTag(input.configString("languageTag"))
                        ))
                ));
    }
}
