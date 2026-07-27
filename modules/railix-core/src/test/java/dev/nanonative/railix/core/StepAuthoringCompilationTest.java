package dev.nanonative.railix.core;

import dev.nanonative.railix.core.fixtures.FlowFixtures;
import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class StepAuthoringCompilationTest {
    @Test
    void stepKindDefaultsToRealStep() {
        final StepDefinition definition = StepDefinition.named("example", "1.0.0")
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertThat(definition.kind()).isEqualTo(StepDefinition.Kind.STEP);
    }

    @Test
    void stepKindCanBeDeclaredExplicitly() {
        final StepDefinition definition = StepDefinition.named("example", "1.0.0")
                .kind(StepDefinition.Kind.VALIDATOR)
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertThat(definition.kind()).isEqualTo(StepDefinition.Kind.VALIDATOR);
    }

    @Test
    void JavaNullStepKindIsRejectedAtTheBuilderBoundary() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StepDefinition.named("example", "1.0.0").kind(null))
                .withMessage("Step kind cannot be Java null.");
    }

    @Test
    void stepIdMustBeNonBlank() {
        final StepDefinition definition = StepDefinition.named(null, "1.0.0")
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertCatalogDiagnostic(
                StepCatalog.of(definition),
                "STEP_ID_REQUIRED",
                "Step id must be non-blank.",
                "catalog[0].id"
        );
    }

    @Test
    void stepIdCannotContainOnlyWhitespace() {
        final StepDefinition definition = StepDefinition.named(" ", "1.0.0")
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertCatalogDiagnostic(
                StepCatalog.of(definition),
                "STEP_ID_REQUIRED",
                "Step id must be non-blank.",
                "catalog[0].id"
        );
    }

    @Test
    void stepDependencyIdsMustBeUnique() {
        final StepDefinition first = StepDefinition.named("duplicate", "1.0.0")
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));
        final StepDefinition second = StepDefinition.named("duplicate", "1.0.0")
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertCatalogDiagnostic(
                StepCatalog.of(first, second),
                "STEP_ID_DUPLICATE",
                "Duplicate Step dependency: duplicate",
                "catalog[1].id"
        );
    }

    @Test
    void stepOutcomeMustBeNonBlank() {
        final StepDefinition definition = StepDefinition.named("text.lowercase", "1.0.0")
                .outcome(null)
                .run(input -> StepResult.outcome("ok"));

        assertCatalogDiagnostic(
                StepCatalog.of(definition),
                "STEP_OUTCOME_REQUIRED",
                "Outcome must be non-blank.",
                "catalog[0].outcomes"
        );
    }

    @Test
    void stepOutcomesMustBeUnique() {
        final StepDefinition definition = StepDefinition.named("text.lowercase", "1.0.0")
                .outcome("ok")
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertCatalogDiagnostic(
                StepCatalog.of(definition),
                "STEP_OUTCOME_DUPLICATE",
                "Duplicate Step outcome: ok",
                "catalog[0].outcomes.ok"
        );
    }

    @Test
    void stepMustDeclareAtLeastOneOutcome() {
        final StepDefinition definition = StepDefinition.named("text.lowercase", "1.0.0")
                .run(input -> StepResult.outcome("ok"));

        assertCatalogDiagnostic(
                StepCatalog.of(definition),
                "STEP_OUTCOME_REQUIRED",
                "Step must declare an outcome.",
                "catalog[0].outcomes"
        );
    }

    @Test
    void stepPortNameMustBeNonBlank() {
        final StepDefinition definition = StepDefinition.named("text.lowercase", "1.0.0")
                .input(null, ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertCatalogDiagnostic(
                StepCatalog.of(definition),
                "STEP_PORT_INVALID",
                "Step port needs a name and shape.",
                "catalog[0].inputs"
        );
    }

    @Test
    void stepPortNamesMustBeUniqueWithinOneDirection() {
        final StepDefinition definition = StepDefinition.named("text.lowercase", "1.0.0")
                .input("text", ValueShape.string())
                .input("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertCatalogDiagnostic(
                StepCatalog.of(definition),
                "STEP_PORT_DUPLICATE",
                "Duplicate Step port: text",
                "catalog[0].inputs.text"
        );
    }

    @Test
    void stepConfigurationNeedsANameAndShape() {
        final StepDefinition definition = StepDefinition.named("text.lowercase", "1.0.0")
                .requiredConfig(null, ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertCatalogDiagnostic(
                StepCatalog.of(definition),
                "STEP_CONFIG_INVALID",
                "Step configuration needs a name and shape.",
                "catalog[0].config"
        );
    }

    @Test
    void stepConfigurationNeedsAShape() {
        final StepDefinition definition = StepDefinition.named("text.lowercase", "1.0.0")
                .requiredConfig("languageTag", null)
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertCatalogDiagnostic(
                StepCatalog.of(definition),
                "STEP_CONFIG_INVALID",
                "Step configuration needs a name and shape.",
                "catalog[0].config"
        );
    }

    @Test
    void stepConfigurationNamesMustBeUnique() {
        final StepDefinition definition = StepDefinition.named("text.lowercase", "1.0.0")
                .requiredConfig("languageTag", ValueShape.string())
                .requiredConfig("languageTag", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertCatalogDiagnostic(
                StepCatalog.of(definition),
                "STEP_CONFIG_DUPLICATE",
                "Duplicate Step configuration: languageTag",
                "catalog[0].config.languageTag"
        );
    }

    @Test
    void stepConfigurationDefaultMustMatchItsShape() {
        final StepDefinition definition = StepDefinition.named("text.lowercase", "1.0.0")
                .config("languageTag", ValueShape.string(), RailixValue.number(1))
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertCatalogDiagnostic(
                StepCatalog.of(definition),
                "STEP_CONFIG_DEFAULT_TYPE_MISMATCH",
                "Step configuration default languageTag requires STRING but received NUMBER.",
                "catalog[0].config.languageTag.default"
        );
    }

    @Test
    void javaNullIsNotAConfigurationDefault() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StepDefinition.named("text.lowercase", "1.0.0")
                        .config("languageTag", ValueShape.string(), null))
                .withMessage("Step configuration default cannot be Java null.");
    }

    @Test
    void unformattedConfigurationDeclaresNoFormat() {
        final StepDefinition definition = StepDefinition.named("text.lowercase", "1.0.0")
                .config("languageTag", ValueShape.string(), RailixValue.string("und"))
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertThat(definition.config().getFirst().format()).isEmpty();
    }

    @Test
    void formattedConfigurationExposesItsFormat() {
        final StepDefinition definition = StepDefinition.named("text.lowercase", "1.0.0")
                .config(
                        "languageTag",
                        ValueShape.string(),
                        StepDefinition.ConfigFormat.LANGUAGE_TAG,
                        RailixValue.string("und")
                )
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertThat(definition.config().getFirst().format())
                .contains(StepDefinition.ConfigFormat.LANGUAGE_TAG);
    }

    @Test
    void formattedRequiredConfigurationExposesItsFormat() {
        final StepDefinition definition = StepDefinition.named("text.lowercase", "1.0.0")
                .requiredConfig(
                        "languageTag",
                        ValueShape.string(),
                        StepDefinition.ConfigFormat.LANGUAGE_TAG
                )
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertThat(definition.config().getFirst().format())
                .contains(StepDefinition.ConfigFormat.LANGUAGE_TAG);
    }

    @Test
    void languageTagFormatRejectsANonStringValue() {
        assertThat(StepDefinition.ConfigFormat.LANGUAGE_TAG.accepts(RailixValue.number(1)))
                .isFalse();
    }

    @Test
    void languageTagFormatRejectsJavaNull() {
        assertThat(StepDefinition.ConfigFormat.LANGUAGE_TAG.accepts(null)).isFalse();
    }

    @Test
    void javaNullDefaultConfigurationFormatIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StepDefinition.named("text.lowercase", "1.0.0")
                        .config("languageTag", ValueShape.string(), null, RailixValue.string("und")))
                .withMessage("Step configuration format cannot be Java null.");
    }

    @Test
    void javaNullRequiredConfigurationFormatIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StepDefinition.named("text.lowercase", "1.0.0")
                        .requiredConfig("languageTag", ValueShape.string(), null))
                .withMessage("Step configuration format cannot be Java null.");
    }

    @Test
    void javaNullFormattedConfigurationDefaultIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StepDefinition.named("text.lowercase", "1.0.0")
                        .config(
                                "languageTag",
                                ValueShape.string(),
                                StepDefinition.ConfigFormat.LANGUAGE_TAG,
                                null
                        ))
                .withMessage("Step configuration default cannot be Java null.");
    }

    @Test
    void configurationFormatMustSupportTheDeclaredShape() {
        final StepDefinition definition = StepDefinition.named("text.lowercase", "1.0.0")
                .config(
                        "languageTag",
                        ValueShape.number(),
                        StepDefinition.ConfigFormat.LANGUAGE_TAG,
                        RailixValue.number(1)
                )
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertCatalogDiagnostic(
                StepCatalog.of(definition),
                "STEP_CONFIG_FORMAT_SHAPE_MISMATCH",
                "Step configuration format language-tag requires STRING shape.",
                "catalog[0].config.languageTag.format"
        );
    }

    @Test
    void configurationDefaultMustMatchItsFormat() {
        final StepDefinition definition = StepDefinition.named("text.lowercase", "1.0.0")
                .config(
                        "languageTag",
                        ValueShape.string(),
                        StepDefinition.ConfigFormat.LANGUAGE_TAG,
                        RailixValue.string("en-")
                )
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertCatalogDiagnostic(
                StepCatalog.of(definition),
                "STEP_CONFIG_DEFAULT_FORMAT_MISMATCH",
                "Step configuration default languageTag requires format language-tag.",
                "catalog[0].config.languageTag.default"
        );
    }

    @Test
    void aMissingStepHandlerIsACompileDiagnostic() {
        final StepDefinition definition = StepDefinition.named("text.lowercase", "1.0.0")
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(null);

        final CompileResult result = FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(definition)
        );

        assertThat(result).isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                "STEP_HANDLER_REQUIRED",
                "Step handler is missing.",
                "catalog[0].handler"
        ))));
    }

    @Test
    void aMissingStepPortShapeIsACompileDiagnostic() {
        final StepDefinition definition = StepDefinition.named("text.lowercase", "1.0.0")
                .input("text", null)
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok")
                        .output("text", RailixValue.string(input.string("text"))));

        final CompileResult result = FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(definition)
        );

        assertThat(result).isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                "STEP_PORT_INVALID",
                "Step port needs a name and shape.",
                "catalog[0].inputs"
        ))));
    }

    @Test
    void aMissingCatalogArrayFailsWithAnExplicitDeveloperMessage() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StepCatalog.of((StepDefinition[]) null))
                .withMessage("Step definitions cannot be Java null.");
    }

    @Test
    void aMissingCatalogEntryFailsWithAnExplicitDeveloperMessage() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> StepCatalog.of((StepDefinition) null))
                .withMessage("Step definition at index 0 cannot be Java null.");
    }

    private static void assertCatalogDiagnostic(
            final StepCatalog catalog,
            final String code,
            final String message,
            final String path
    ) {
        final CompileResult result = FlowCompiler.compile(FlowFixtures.lowercaseFlow(), catalog);

        assertThat(result).isEqualTo(new CompileResult.Rejected(List.of(
                Diagnostic.atPath(code, message, path)
        )));
    }
}
