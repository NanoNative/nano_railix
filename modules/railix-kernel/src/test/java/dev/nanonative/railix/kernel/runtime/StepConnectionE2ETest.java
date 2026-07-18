package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Shape;
import dev.nanonative.railix.kernel.model.StepContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepConnectionE2ETest {

    @TempDir
    Path tempDir;

    @Test
    void shouldApplyConnectionOperatorsInDeclaredOrderThroughBuiltAppBoundary() {
        final BuiltRailixApp app = new BuiltRailixApp(
                connectedPlan(Shape.string(), List.of(operator("railix.op.string.trim"), operator("railix.op.string.lowercase"))),
                kernel(),
                resolver(
                        emittingStep(Shape.string(), Map.of("email", new RailixValue.StringValue("  USER@EXAMPLE.COM  "))),
                        consumingStep()
                )
        );

        final LocalExecutionKernel.RunRecord record = app.run(request("connection-success"));

        assertThat(record.context().get(RailixPath.parse("ctx.result")))
                .isEqualTo(new RailixValue.StringValue("user@example.com"));
    }

    @Test
    void shouldBindCompatiblePortsWithoutOperators() {
        final BuiltRailixApp app = new BuiltRailixApp(
                connectedPlan(Shape.string(), List.of()),
                kernel(),
                resolver(
                        emittingStep(Shape.string(), Map.of("email", new RailixValue.StringValue("Railix"))),
                        consumingStep()
                )
        );

        final LocalExecutionKernel.RunRecord record = app.run(request("connection-direct"));

        assertThat(record.context().get(RailixPath.parse("ctx.result")))
                .isEqualTo(new RailixValue.StringValue("Railix"));
    }

    @Test
    void shouldExecuteConnectionThroughDirectKernelBoundary() {
        final LocalExecutionKernel.RunRecord record = kernel().run(
                connectedPlan(Shape.string(), List.of()),
                request("connection-direct-kernel"),
                resolver(
                        emittingStep(Shape.string(), Map.of("email", new RailixValue.StringValue("Railix"))),
                        consumingStep()
                )
        );

        assertThat(record.context().get(RailixPath.parse("ctx.result")))
                .isEqualTo(new RailixValue.StringValue("Railix"));
    }

    @Test
    void shouldMaterializeTypedConfigurationDefaultThroughDirectKernelBoundary() {
        final Step step = typedStep(
                "test.config",
                List.of(),
                List.of(),
                Map.of("mode", new StepContract.ConfigField(
                        Shape.string(),
                        false,
                        StepContract.DefaultValue.of(new RailixValue.StringValue("safe"))
                )),
                input -> new Step.Result("ok", List.of(new Patch.Set(
                        RailixPath.parse("ctx.result"),
                        new Patch.LiteralSource(input.config().values().get("mode"))
                )))
        );

        final LocalExecutionKernel.RunRecord record = kernel().run(
                standalonePlan("test.config", new RailixValue.ObjectValue(Map.of())),
                request("config-direct-kernel"),
                resolver(step)
        );

        assertThat(record.context().get(RailixPath.parse("ctx.result")))
                .isEqualTo(new RailixValue.StringValue("safe"));
    }

    @Test
    void shouldValidateTypedOutputThroughDirectKernelBoundary() {
        final Step step = typedStep(
                "test.output",
                List.of(),
                List.of(new StepContract.Port("value", Shape.string(), true)),
                input -> new Step.Result(
                        "ok",
                        Map.of("value", new RailixValue.NumberValue(java.math.BigDecimal.ONE)),
                        List.of()
                )
        );

        final LocalExecutionKernel.RunRecord record = kernel().run(
                standalonePlan("test.output", new RailixValue.ObjectValue(Map.of())),
                request("output-direct-kernel"),
                resolver(step)
        );

        assertThat(record.outcome()).isEqualTo("failed");
    }

    @Test
    void shouldRejectIncompatibleConnectionBeforeAnyStepExecutes() {
        final AtomicInteger executions = new AtomicInteger();
        final Step source = typedStep(
                "test.emit",
                List.of(),
                List.of(new StepContract.Port("email", Shape.number(), true)),
                input -> {
                    executions.incrementAndGet();
                    return new Step.Result("ok", Map.of("email", new RailixValue.NumberValue(java.math.BigDecimal.ONE)), List.of());
                }
        );
        final Step target = typedStep(
                "test.consume",
                List.of(new StepContract.Port("value", Shape.string(), true)),
                List.of(),
                input -> {
                    executions.incrementAndGet();
                    return new Step.Result("ok", List.of());
                }
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                connectedPlan(Shape.number(), List.of(operator("railix.op.string.lowercase"))),
                kernel(),
                resolver(source, target)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connection normalize-email")
                .hasMessageContaining("railix.op.string.lowercase")
                .hasMessageContaining("number")
                .hasMessageContaining("string");
        assertThat(executions).hasValue(0);
    }

    @Test
    void shouldFailRunWhenStepOmitsRequiredConnectedOutput() {
        final BuiltRailixApp app = new BuiltRailixApp(
                connectedPlan(Shape.string(), List.of(operator("railix.op.string.lowercase"))),
                kernel(),
                resolver(emittingStep(Shape.string(), Map.of()), consumingStep())
        );

        final LocalExecutionKernel.RunRecord record = app.run(request("connection-missing-output"));

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(record.signalLog().readSignals(0, 20).stream()
                .filter(RunSignal.StepFailed.class::isInstance)
                .map(RunSignal.StepFailed.class::cast)
                .map(signal -> signal.errorSummary().toString()))
                .anyMatch(summary -> summary.contains("required output email"));
    }

    @Test
    void shouldFailRunWhenConnectedOptionalSourceOutputIsAbsent() {
        final Step source = typedStep(
                "test.emit",
                List.of(),
                List.of(new StepContract.Port("email", Shape.string(), false)),
                input -> new Step.Result("ok", Map.of(), List.of())
        );
        final BuiltRailixApp app = new BuiltRailixApp(
                connectedPlan(Shape.string(), List.of()),
                kernel(),
                resolver(source, consumingStep())
        );

        final LocalExecutionKernel.RunRecord record = app.run(request("connection-optional-output"));

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(record.signalLog().readSignals(0, 20).stream()
                .filter(RunSignal.StepFailed.class::isInstance)
                .map(RunSignal.StepFailed.class::cast)
                .map(signal -> signal.errorSummary().toString()))
                .anyMatch(summary -> summary.contains("source step omitted output email"));
    }

    @Test
    void shouldMaterializeTypedConfigurationDefaultBeforeStandaloneExecution() {
        final Step step = typedStep(
                "test.config",
                List.of(),
                List.of(),
                Map.of("mode", new StepContract.ConfigField(
                        Shape.string(),
                        false,
                        StepContract.DefaultValue.of(new RailixValue.StringValue("safe"))
                )),
                input -> new Step.Result("ok", List.of(new Patch.Set(
                        RailixPath.parse("ctx.result"),
                        new Patch.LiteralSource(input.config().values().get("mode"))
                )))
        );
        final BuiltRailixApp app = new BuiltRailixApp(
                standalonePlan("test.config", new RailixValue.ObjectValue(Map.of())),
                kernel(),
                resolver(step)
        );

        final LocalExecutionKernel.RunRecord record = app.run(request("config-default"));

        assertThat(record.context().get(RailixPath.parse("ctx.result")))
                .isEqualTo(new RailixValue.StringValue("safe"));
    }

    @Test
    void shouldPreferExplicitTypedConfigurationOverDefault() {
        final Step step = typedStep(
                "test.config",
                List.of(),
                List.of(),
                Map.of("mode", new StepContract.ConfigField(
                        Shape.string(),
                        false,
                        StepContract.DefaultValue.of(new RailixValue.StringValue("safe"))
                )),
                input -> new Step.Result("ok", List.of(new Patch.Set(
                        RailixPath.parse("ctx.result"),
                        new Patch.LiteralSource(input.config().values().get("mode"))
                )))
        );
        final BuiltRailixApp app = new BuiltRailixApp(
                standalonePlan("test.config", objectValue("mode", new RailixValue.StringValue("fast"))),
                kernel(),
                resolver(step)
        );

        final LocalExecutionKernel.RunRecord record = app.run(request("config-explicit"));

        assertThat(record.context().get(RailixPath.parse("ctx.result")))
                .isEqualTo(new RailixValue.StringValue("fast"));
    }

    @Test
    void shouldLeaveAbsentOptionalTypedConfigurationUnset() {
        final Step step = typedStep(
                "test.config",
                List.of(),
                List.of(),
                Map.of("mode", new StepContract.ConfigField(
                        Shape.string(),
                        false,
                        StepContract.DefaultValue.none()
                )),
                input -> new Step.Result(
                        input.config().values().containsKey("mode") ? "configured" : "ok",
                        List.of()
                )
        );
        final BuiltRailixApp app = new BuiltRailixApp(
                standalonePlan("test.config", new RailixValue.ObjectValue(Map.of())),
                kernel(),
                resolver(step)
        );

        final LocalExecutionKernel.RunRecord record = app.run(request("config-optional"));

        assertThat(record.outcome()).isEqualTo("ok");
    }

    @Test
    void shouldPreserveExplicitNullTypedConfigurationInsteadOfApplyingDefault() {
        final Step step = typedStep(
                "test.config",
                List.of(),
                List.of(),
                Map.of("mode", new StepContract.ConfigField(
                        Shape.union(Shape.nullValue(), Shape.string()),
                        false,
                        StepContract.DefaultValue.of(new RailixValue.StringValue("safe"))
                )),
                input -> new Step.Result("ok", List.of(new Patch.Set(
                        RailixPath.parse("ctx.result"),
                        new Patch.LiteralSource(input.config().values().get("mode"))
                )))
        );
        final BuiltRailixApp app = new BuiltRailixApp(
                standalonePlan("test.config", objectValue("mode", RailixValue.NULL)),
                kernel(),
                resolver(step)
        );

        final LocalExecutionKernel.RunRecord record = app.run(request("config-null"));

        assertThat(record.context().get(RailixPath.parse("ctx.result"))).isEqualTo(RailixValue.NULL);
    }

    @Test
    void shouldRejectMissingRequiredTypedConfigurationAtBuildBoundary() {
        final Step step = typedStep(
                "test.config",
                List.of(),
                List.of(),
                Map.of("mode", new StepContract.ConfigField(
                        Shape.string(),
                        true,
                        StepContract.DefaultValue.none()
                )),
                input -> new Step.Result("ok", List.of())
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                standalonePlan("test.config", new RailixValue.ObjectValue(Map.of())),
                kernel(),
                resolver(step)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("step invoke requires config field mode");
    }

    @Test
    void shouldRejectUndeclaredTypedConfigurationAtBuildBoundary() {
        final Step step = typedStep(
                "test.config",
                List.of(),
                List.of(),
                Map.of(),
                input -> new Step.Result("ok", List.of())
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                standalonePlan("test.config", objectValue("mystery", new RailixValue.StringValue("value"))),
                kernel(),
                resolver(step)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("step invoke has undeclared config field mystery");
    }

    @Test
    void shouldReportFirstUndeclaredConfigurationFieldDeterministically() {
        final Step step = typedStep(
                "test.config",
                List.of(),
                List.of(),
                Map.of(),
                input -> new Step.Result("ok", List.of())
        );
        final Map<String, RailixValue> values = new LinkedHashMap<>();
        values.put("zeta", new RailixValue.StringValue("last"));
        values.put("alpha", new RailixValue.StringValue("first"));

        assertThatThrownBy(() -> new BuiltRailixApp(
                standalonePlan("test.config", new RailixValue.ObjectValue(values)),
                kernel(),
                resolver(step)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("step invoke has undeclared config field alpha");
    }

    @Test
    void shouldRejectTypedConfigurationWithWrongRuntimeShapeAtBuildBoundary() {
        final Step step = typedStep(
                "test.config",
                List.of(),
                List.of(),
                Map.of("mode", new StepContract.ConfigField(
                        Shape.string(),
                        false,
                        StepContract.DefaultValue.none()
                )),
                input -> new Step.Result("ok", List.of())
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                standalonePlan("test.config", objectValue(
                        "mode",
                        new RailixValue.NumberValue(java.math.BigDecimal.ONE)
                )),
                kernel(),
                resolver(step)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("step invoke config mode expected string but received number");
    }

    @Test
    void shouldFailRunWhenStandaloneTypedStepReturnsWrongOutputShape() {
        final Step step = typedStep(
                "test.output",
                List.of(),
                List.of(new StepContract.Port("value", Shape.string(), true)),
                input -> new Step.Result(
                        "ok",
                        Map.of("value", new RailixValue.NumberValue(java.math.BigDecimal.ONE)),
                        List.of()
                )
        );
        final BuiltRailixApp app = new BuiltRailixApp(
                standalonePlan("test.output", new RailixValue.ObjectValue(Map.of())),
                kernel(),
                resolver(step)
        );

        final LocalExecutionKernel.RunRecord record = app.run(request("output-shape"));

        assertThat(record.outcome()).isEqualTo("failed");
    }

    @Test
    void shouldFailRunWhenStandaloneTypedStepReturnsUndeclaredOutput() {
        final Step step = typedStep(
                "test.output",
                List.of(),
                List.of(),
                input -> new Step.Result(
                        "ok",
                        Map.of("mystery", new RailixValue.StringValue("value")),
                        List.of()
                )
        );
        final BuiltRailixApp app = new BuiltRailixApp(
                standalonePlan("test.output", new RailixValue.ObjectValue(Map.of())),
                kernel(),
                resolver(step)
        );

        final LocalExecutionKernel.RunRecord record = app.run(request("output-undeclared"));

        assertThat(record.outcome()).isEqualTo("failed");
    }

    @Test
    void shouldAllowStandaloneTypedStepToOmitOptionalOutput() {
        final Step step = typedStep(
                "test.output",
                List.of(),
                List.of(new StepContract.Port("value", Shape.string(), false)),
                input -> new Step.Result("ok", Map.of(), List.of())
        );
        final BuiltRailixApp app = new BuiltRailixApp(
                standalonePlan("test.output", new RailixValue.ObjectValue(Map.of())),
                kernel(),
                resolver(step)
        );

        final LocalExecutionKernel.RunRecord record = app.run(request("output-optional"));

        assertThat(record.outcome()).isEqualTo("ok");
    }

    @Test
    void shouldAllowStandaloneTypedStepWithOptionalUnconnectedInput() {
        final Step step = typedStep(
                "test.input",
                List.of(new StepContract.Port("value", Shape.string(), false)),
                List.of(),
                input -> new Step.Result("ok", List.of())
        );
        final BuiltRailixApp app = new BuiltRailixApp(
                standalonePlan("test.input", new RailixValue.ObjectValue(Map.of())),
                kernel(),
                resolver(step)
        );

        assertThat(app.run(request("input-optional")).outcome()).isEqualTo("ok");
    }

    @Test
    void shouldRejectUnsupportedConnectionOperatorAtBuildBoundary() {
        assertThatThrownBy(() -> new BuiltRailixApp(
                connectedPlan(Shape.string(), List.of(operator("railix.op.unknown"))),
                kernel(),
                resolver(emittingStep(Shape.string(), Map.of()), consumingStep())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connection normalize-email uses unsupported operator railix.op.unknown");
    }

    @Test
    void shouldRejectConfigurationForOperatorThatDeclaresNone() {
        final AppPlan.OperatorInvocation configured = new AppPlan.OperatorInvocation(
                "railix.op.string.trim",
                objectValue("mode", new RailixValue.StringValue("all"))
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                connectedPlan(Shape.string(), List.of(configured)),
                kernel(),
                resolver(emittingStep(Shape.string(), Map.of()), consumingStep())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connection normalize-email operator railix.op.string.trim does not accept config");
    }

    @Test
    void shouldRejectUnknownSourcePortAtBuildBoundary() {
        final AppPlan plan = connectedPlan(
                Shape.string(),
                List.of(),
                new AppPlan.PortRef("emit", "missing"),
                new AppPlan.PortRef("consume", "value")
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                plan,
                kernel(),
                resolver(emittingStep(Shape.string(), Map.of()), consumingStep())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connection normalize-email references unknown source output port missing");
    }

    @Test
    void shouldRejectUnknownTargetPortAtBuildBoundary() {
        final AppPlan plan = connectedPlan(
                Shape.string(),
                List.of(),
                new AppPlan.PortRef("emit", "email"),
                new AppPlan.PortRef("consume", "missing")
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                plan,
                kernel(),
                resolver(emittingStep(Shape.string(), Map.of()), consumingStep())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connection normalize-email references unknown target input port missing");
    }

    @Test
    void shouldRejectRequiredTargetInputWithoutConnectionAtBuildBoundary() {
        final Step target = typedStep(
                "test.consume",
                List.of(
                        new StepContract.Port("value", Shape.string(), true),
                        new StepContract.Port("tenant", Shape.string(), true)
                ),
                List.of(),
                input -> new Step.Result("ok", List.of())
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                connectedPlan(Shape.string(), List.of()),
                kernel(),
                resolver(emittingStep(Shape.string(), Map.of()), target)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("step consume requires input tenant but no connection supplies it");
    }

    @Test
    void shouldRejectDuplicateTargetInputOwnershipAtBuildBoundary() {
        final AppPlan.Connection first = connection(
                "first",
                new AppPlan.PortRef("emit", "email"),
                new AppPlan.PortRef("consume", "value"),
                List.of()
        );
        final AppPlan.Connection duplicate = connection(
                "duplicate",
                new AppPlan.PortRef("emit", "email"),
                new AppPlan.PortRef("consume", "value"),
                List.of()
        );
        final AppPlan plan = flowPlan(
                "emit",
                List.of(
                        new AppPlan.StepInvocation("emit", "test.emit", Map.of("ok", "consume")),
                        new AppPlan.StepInvocation("consume", "test.consume", Map.of())
                ),
                List.of(first, duplicate)
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                plan,
                kernel(),
                resolver(emittingStep(Shape.string(), Map.of()), consumingStep())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connection duplicate duplicates target consume/value already owned by connection first");
    }

    @Test
    void shouldRejectConnectionWithoutDirectControlTransition() {
        final AppPlan plan = flowPlan(
                "emit",
                List.of(
                        new AppPlan.StepInvocation("emit", "test.emit", Map.of("ok", "middle")),
                        new AppPlan.StepInvocation("middle", "test.middle", Map.of("ok", "consume")),
                        new AppPlan.StepInvocation("consume", "test.consume", Map.of())
                ),
                List.of(connection(
                        "normalize-email",
                        new AppPlan.PortRef("emit", "email"),
                        new AppPlan.PortRef("consume", "value"),
                        List.of()
                ))
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                plan,
                kernel(),
                resolver(
                        emittingStep(Shape.string(), Map.of()),
                        typedStep("test.middle", List.of(), List.of(), input -> new Step.Result("ok", List.of())),
                        consumingStep()
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connection normalize-email source step emit must transition directly to target step consume");
    }

    @Test
    void shouldRejectConnectedTargetAsFlowTrigger() {
        final AppPlan plan = flowPlan(
                "consume",
                List.of(
                        new AppPlan.StepInvocation("emit", "test.emit", Map.of("ok", "consume")),
                        new AppPlan.StepInvocation("consume", "test.consume", Map.of())
                ),
                List.of(connection(
                        "normalize-email",
                        new AppPlan.PortRef("emit", "email"),
                        new AppPlan.PortRef("consume", "value"),
                        List.of()
                ))
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                plan,
                kernel(),
                resolver(emittingStep(Shape.string(), Map.of()), consumingStep())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connected target step consume cannot be the flow trigger");
    }

    @Test
    void shouldRejectTargetInputsFromMultipleSourceSteps() {
        final Step secondSource = typedStep(
                "test.emit-second",
                List.of(),
                List.of(new StepContract.Port("tenant", Shape.string(), true)),
                input -> new Step.Result("ok", Map.of("tenant", new RailixValue.StringValue("acme")), List.of())
        );
        final Step target = typedStep(
                "test.consume",
                List.of(
                        new StepContract.Port("value", Shape.string(), true),
                        new StepContract.Port("tenant", Shape.string(), true)
                ),
                List.of(),
                input -> new Step.Result("ok", List.of())
        );
        final AppPlan plan = flowPlan(
                "emit",
                List.of(
                        new AppPlan.StepInvocation("emit", "test.emit", Map.of("ok", "consume")),
                        new AppPlan.StepInvocation("emit-second", "test.emit-second", Map.of("ok", "consume")),
                        new AppPlan.StepInvocation("consume", "test.consume", Map.of())
                ),
                List.of(
                        connection("value", new AppPlan.PortRef("emit", "email"), new AppPlan.PortRef("consume", "value"), List.of()),
                        connection("tenant", new AppPlan.PortRef("emit-second", "tenant"), new AppPlan.PortRef("consume", "tenant"), List.of())
                )
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                plan,
                kernel(),
                resolver(emittingStep(Shape.string(), Map.of()), secondSource, target)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connected target step consume cannot consume inputs from multiple source steps");
    }

    @Test
    void shouldRejectAlternateControlPredecessorForConnectedTarget() {
        final AppPlan plan = flowPlan(
                "emit",
                List.of(
                        new AppPlan.StepInvocation("emit", "test.emit", Map.of("ok", "consume")),
                        new AppPlan.StepInvocation("alternate", "test.alternate", Map.of("ok", "consume")),
                        new AppPlan.StepInvocation("consume", "test.consume", Map.of())
                ),
                List.of(connection(
                        "normalize-email",
                        new AppPlan.PortRef("emit", "email"),
                        new AppPlan.PortRef("consume", "value"),
                        List.of()
                ))
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                plan,
                kernel(),
                resolver(
                        emittingStep(Shape.string(), Map.of()),
                        typedStep("test.alternate", List.of(), List.of(), input -> new Step.Result("ok", List.of())),
                        consumingStep()
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connected target step consume has an alternate predecessor alternate");
    }

    @Test
    void shouldRejectLegacySourceContractForTypedConnection() {
        final Step legacySource = legacyStep(
                "test.emit",
                List.of(),
                List.of(new StepContract.Port("email", "string", "", true, List.of())),
                input -> new Step.Result("ok", Map.of("email", new RailixValue.StringValue("value")), List.of())
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                connectedPlan(Shape.string(), List.of()),
                kernel(),
                resolver(legacySource, consumingStep())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connection normalize-email source step must use Step Contract 1");
    }

    @Test
    void shouldRejectLegacyTargetContractForTypedConnection() {
        final Step legacyTarget = legacyStep(
                "test.consume",
                List.of(new StepContract.Port("value", "string", "", true, List.of())),
                List.of(),
                input -> new Step.Result("ok", List.of())
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                connectedPlan(Shape.string(), List.of()),
                kernel(),
                resolver(emittingStep(Shape.string(), Map.of()), legacyTarget)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connection normalize-email target step must use Step Contract 1");
    }

    @Test
    void shouldRejectNullStepResolutionAtBuildBoundary() {
        assertThatThrownBy(() -> new BuiltRailixApp(
                connectedPlan(Shape.string(), List.of()),
                kernel(),
                use -> null
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("No step resolved for invocation: emit [test.emit]");
    }

    @Test
    void shouldRejectResolverContractIdentityDriftAtBuildBoundary() {
        final Step wrongStep = typedStep(
                "test.other",
                List.of(),
                List.of(),
                input -> new Step.Result("ok", List.of())
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                standalonePlan("test.expected", new RailixValue.ObjectValue(Map.of())),
                kernel(),
                use -> wrongStep
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("step invoke resolved use test.expected to contract test.other");
    }

    @Test
    void shouldFreezeStepContractAtBuildBoundary() {
        final AtomicInteger contractReads = new AtomicInteger();
        final StepContract contract = StepContract.typed(
                "test.frozen",
                "1.0.0",
                "Frozen",
                "Proves the execution kernel uses the compiled contract snapshot.",
                StepContract.Kind.NORMAL,
                List.of(),
                List.of(),
                Map.of("ok", new StepContract.Outcome("Completed."))
        );
        final Step step = new Step() {
            @Override
            public StepContract contract() {
                contractReads.incrementAndGet();
                return contract;
            }

            @Override
            public Result execute(final ExecutionInput input) {
                return new Result("ok", List.of());
            }
        };

        final BuiltRailixApp app = new BuiltRailixApp(
                standalonePlan("test.frozen", new RailixValue.ObjectValue(Map.of())),
                kernel(),
                use -> step
        );
        assertThat(contractReads).hasValue(1);

        assertThat(app.run(request("frozen-contract")).outcome()).isEqualTo("ok");
        assertThat(contractReads).hasValue(1);
    }

    @Test
    void shouldRejectDirectlyConnectedIncompatiblePortShapes() {
        final Step target = typedStep(
                "test.consume",
                List.of(new StepContract.Port("value", Shape.number(), true)),
                List.of(),
                input -> new Step.Result("ok", List.of())
        );

        assertThatThrownBy(() -> new BuiltRailixApp(
                connectedPlan(Shape.string(), List.of()),
                kernel(),
                resolver(emittingStep(Shape.string(), Map.of()), target)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("connection normalize-email target consume/value produces string but requires number");
    }

    private AppPlan connectedPlan(
            final Shape sourceShape,
            final List<AppPlan.OperatorInvocation> operators
    ) {
        return connectedPlan(
                sourceShape,
                operators,
                new AppPlan.PortRef("emit", "email"),
                new AppPlan.PortRef("consume", "value")
        );
    }

    private AppPlan connectedPlan(
            final Shape sourceShape,
            final List<AppPlan.OperatorInvocation> operators,
            final AppPlan.PortRef from,
            final AppPlan.PortRef to
    ) {
        return flowPlan(
                "emit",
                List.of(
                        new AppPlan.StepInvocation("emit", "test.emit", Map.of("ok", "consume")),
                        new AppPlan.StepInvocation("consume", "test.consume", Map.of())
                ),
                List.of(connection("normalize-email", from, to, operators))
        );
    }

    private static AppPlan standalonePlan(
            final String use,
            final RailixValue.ObjectValue config
    ) {
        return flowPlan(
                "invoke",
                List.of(new AppPlan.StepInvocation("invoke", use, config, Map.of())),
                List.of()
        );
    }

    private static AppPlan flowPlan(
            final String trigger,
            final List<AppPlan.StepInvocation> steps,
            final List<AppPlan.Connection> connections
    ) {
        return new AppPlan(
                "railix-app",
                "connection-flow",
                trigger,
                PermissionSet.none(),
                steps,
                connections
        );
    }

    private static AppPlan.Connection connection(
            final String id,
            final AppPlan.PortRef from,
            final AppPlan.PortRef to,
            final List<AppPlan.OperatorInvocation> operators
    ) {
        return new AppPlan.Connection(id, from, to, operators);
    }

    private static AppPlan.OperatorInvocation operator(final String use) {
        return new AppPlan.OperatorInvocation(use, new RailixValue.ObjectValue(Map.of()));
    }

    private static RailixValue.ObjectValue objectValue(final String key, final RailixValue value) {
        return new RailixValue.ObjectValue(Map.of(key, value));
    }

    private static Step emittingStep(final Shape outputShape, final Map<String, RailixValue> outputs) {
        return typedStep(
                "test.emit",
                List.of(),
                List.of(new StepContract.Port("email", outputShape, true)),
                input -> new Step.Result("ok", outputs, List.of())
        );
    }

    private static Step consumingStep() {
        return typedStep(
                "test.consume",
                List.of(new StepContract.Port("value", Shape.string(), true)),
                List.of(),
                input -> new Step.Result(
                        "ok",
                        List.of(new Patch.Set(
                                RailixPath.parse("ctx.result"),
                                new Patch.LiteralSource(input.requireInput("value"))
                        ))
                )
        );
    }

    private static Step typedStep(
            final String id,
            final List<StepContract.Port> inputs,
            final List<StepContract.Port> outputs,
            final Function<Step.ExecutionInput, Step.Result> executor
    ) {
        return typedStep(id, inputs, outputs, Map.of(), executor);
    }

    private static Step typedStep(
            final String id,
            final List<StepContract.Port> inputs,
            final List<StepContract.Port> outputs,
            final Map<String, StepContract.ConfigField> config,
            final Function<Step.ExecutionInput, Step.Result> executor
    ) {
        return new Step() {
            @Override
            public StepContract contract() {
                return StepContract.typed(
                        id,
                        "1.0.0",
                        id,
                        id,
                        StepContract.Kind.NORMAL,
                        inputs,
                        outputs,
                        config,
                        Map.of("ok", new StepContract.Outcome("Completed.")),
                        StepContract.Semantics.pure()
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                return executor.apply(input);
            }
        };
    }

    private static Step legacyStep(
            final String id,
            final List<StepContract.Port> inputs,
            final List<StepContract.Port> outputs,
            final Function<Step.ExecutionInput, Step.Result> executor
    ) {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        id,
                        "1.0.0",
                        id,
                        id,
                        StepContract.Kind.NORMAL,
                        inputs,
                        outputs,
                        Map.of("ok", new StepContract.Outcome("Completed.")),
                        new StepContract.Settings(List.of()),
                        PermissionSet.none(),
                        new StepContract.Timeout(java.time.Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, java.time.Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", java.time.Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(List.of()),
                        Map.of()
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                return executor.apply(input);
            }
        };
    }

    private static StepResolver resolver(final Step... steps) {
        final Map<String, Step> byUse = new LinkedHashMap<>();
        for (final Step step : steps) {
            byUse.put(step.contract().id(), step);
        }
        return use -> {
            final Step step = byUse.get(use);
            if (step == null) {
                throw new IllegalArgumentException("Unknown test use: " + use);
            }
            return step;
        };
    }

    private LocalExecutionKernel kernel() {
        return new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-07-18T10:00:00Z"), ZoneOffset.UTC));
    }

    private LocalExecutionKernel.RunRequest request(final String runId) {
        return new LocalExecutionKernel.RunRequest(runId, tempDir, new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of()),
                new RailixValue.ObjectValue(Map.of()),
                Map.of(),
                new Envelope.ReplyChannel(false, List.of())
        ));
    }
}
