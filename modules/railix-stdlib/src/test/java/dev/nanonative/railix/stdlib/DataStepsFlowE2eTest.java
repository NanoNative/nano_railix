package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataStepsFlowE2eTest {
    @Test
    void nonblankDefaultAcceptsTextAndPreservesIt() {
        assertThat(run(nonblank(""), "text", RailixValue.string(" Railix ")))
                .isEqualTo(succeeded("text", RailixValue.string(" Railix "), "valid"));
    }

    @Test
    void nonblankDefaultRejectsWhitespaceAndPreservesIt() {
        assertThat(run(nonblank(""), "text", RailixValue.string(" \t\n")))
                .isEqualTo(succeeded("text", RailixValue.string(" \t\n"), "invalid"));
    }

    @Test
    void nonblankOverrideCanCheckRawText() {
        assertThat(run(nonblank("\"trimBeforeCheck\":false"), "text", RailixValue.string(" ")))
                .isEqualTo(succeeded("text", RailixValue.string(" "), "valid"));
    }

    @Test
    void nonblankOverrideStillRejectsEmptyText() {
        assertThat(run(nonblank("\"trimBeforeCheck\":false"), "text", RailixValue.string("")))
                .isEqualTo(succeeded("text", RailixValue.string(""), "invalid"));
    }

    @Test
    void nonblankConfigurationMustBeBoolean() {
        assertDiagnostic(
                nonblank("\"trimBeforeCheck\":\"yes\""),
                "FLOW_STEP_CONFIG_TYPE_MISMATCH",
                "Step configuration trimBeforeCheck requires BOOLEAN but received STRING.",
                "steps.operator.config.trimBeforeCheck"
        );
    }

    @Test
    void defaultIfNullUsesTheRequiredReplacement() {
        assertThat(run(defaultIfNull("\"replacement\":\"missing\""), "value", RailixValue.nullValue()))
                .isEqualTo(succeeded("value", RailixValue.string("missing"), "defaulted"));
    }

    @Test
    void defaultIfNullPreservesANonNullObject() {
        final RailixValue value = RailixValue.object(Map.of("present", RailixValue.bool(true)));

        assertThat(run(defaultIfNull("\"replacement\":\"missing\""), "value", value))
                .isEqualTo(succeeded("value", value, "kept"));
    }

    @Test
    void defaultIfNullRequiresAReplacement() {
        assertDiagnostic(
                defaultIfNull(""),
                "FLOW_STEP_CONFIG_REQUIRED",
                "Required Step configuration is missing: replacement",
                "steps.operator.config.replacement"
        );
    }

    @Test
    void defaultIfNullAllowsAnExplicitNullReplacement() {
        assertThat(run(defaultIfNull("\"replacement\":null"), "value", RailixValue.nullValue()))
                .isEqualTo(succeeded("value", RailixValue.nullValue(), "defaulted"));
    }

    @Test
    void translateExactReplacesAnExactMatch() {
        assertThat(run(translate("\"from\":\"yes\",\"to\":\"accepted\""), "text", RailixValue.string("yes")))
                .isEqualTo(succeeded("text", RailixValue.string("accepted"), "translated"));
    }

    @Test
    void translateExactPreservesAnUnmatchedValue() {
        assertThat(run(translate("\"from\":\"yes\",\"to\":\"accepted\""), "text", RailixValue.string("no")))
                .isEqualTo(succeeded("text", RailixValue.string("no"), "unchanged"));
    }

    @Test
    void translateExactIsCaseSensitive() {
        assertThat(run(translate("\"from\":\"yes\",\"to\":\"accepted\""), "text", RailixValue.string("YES")))
                .isEqualTo(succeeded("text", RailixValue.string("YES"), "unchanged"));
    }

    @Test
    void translateExactSupportsAnEmptyMatch() {
        assertThat(run(translate("\"from\":\"\",\"to\":\"empty\""), "text", RailixValue.string("")))
                .isEqualTo(succeeded("text", RailixValue.string("empty"), "translated"));
    }

    @Test
    void translateExactRequiresFrom() {
        assertDiagnostic(
                translate("\"to\":\"accepted\""),
                "FLOW_STEP_CONFIG_REQUIRED",
                "Required Step configuration is missing: from",
                "steps.operator.config.from"
        );
    }

    @Test
    void translateExactRequiresTo() {
        assertDiagnostic(
                translate("\"from\":\"yes\""),
                "FLOW_STEP_CONFIG_REQUIRED",
                "Required Step configuration is missing: to",
                "steps.operator.config.to"
        );
    }

    @Test
    void translateExactFromMustBeAString() {
        assertDiagnostic(
                translate("\"from\":1,\"to\":\"accepted\""),
                "FLOW_STEP_CONFIG_TYPE_MISMATCH",
                "Step configuration from requires STRING but received NUMBER.",
                "steps.operator.config.from"
        );
    }

    @Test
    void translateExactToMustBeAString() {
        assertDiagnostic(
                translate("\"from\":\"yes\",\"to\":true"),
                "FLOW_STEP_CONFIG_TYPE_MISMATCH",
                "Step configuration to requires STRING but received BOOLEAN.",
                "steps.operator.config.to"
        );
    }

    private static RunResult run(
            final String flow,
            final String port,
            final RailixValue value
    ) {
        final CompileResult result = FlowCompiler.compile(flow, StandardLibrary.catalog());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        return ((CompileResult.Compiled) result).flow().run(
                RailixValue.object(Map.of(port, value))
        );
    }

    private static void assertDiagnostic(
            final String flow,
            final String code,
            final String message,
            final String path
    ) {
        assertThat(FlowCompiler.compile(flow, StandardLibrary.catalog()))
                .isEqualTo(new CompileResult.Rejected(List.of(
                        Diagnostic.atPath(code, message, path)
                )));
    }

    private static RunResult.Succeeded succeeded(
            final String port,
            final RailixValue value,
            final String outcome
    ) {
        return new RunResult.Succeeded(
                RailixValue.object(Map.of(port, value)),
                List.of(new RunResult.StepExecution("operator", outcome))
        );
    }

    private static String nonblank(final String config) {
        return flow(
                "text.nonblank",
                "text",
                "string",
                config,
                "\"valid\":\"end\",\"invalid\":\"end\""
        );
    }

    private static String defaultIfNull(final String config) {
        return flow(
                "value.default-if-null",
                "value",
                "any",
                config,
                "\"kept\":\"end\",\"defaulted\":\"end\""
        );
    }

    private static String translate(final String config) {
        return flow(
                "text.translate-exact",
                "text",
                "string",
                config,
                "\"translated\":\"end\",\"unchanged\":\"end\""
        );
    }

    private static String flow(
            final String use,
            final String port,
            final String shape,
            final String config,
            final String outcomes
    ) {
        return """
                {
                  "id": "operator-flow",
                  "triggers": [],
                  "entry": "operator",
                  "inputs": {"%1$s": "%2$s"},
                  "outputs": {"%1$s": "%2$s"},
                  "steps": [
                    {"id": "operator", "use": "%3$s", "config": {%4$s}, "on": {%5$s}}
                  ],
                  "connections": [
                    {"from": "input.%1$s", "to": "operator.%1$s"},
                    {"from": "operator.%1$s", "to": "output.%1$s"}
                  ]
                }
                """.formatted(port, shape, use, config, outcomes);
    }
}
