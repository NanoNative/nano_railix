package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunSignalTest {

    @Test
    void shouldExposeStepContextAndCopyCollections() {
        final List<RailixPath> changedPaths = new ArrayList<>(List.of(RailixPath.parse("ctx.customer.email")));
        final Map<String, String> labels = new HashMap<>(Map.of("step", "transform"));

        final RunSignal.ContextPatched contextPatched = new RunSignal.ContextPatched(
                "sig-1",
                Instant.parse("2026-06-26T10:15:30Z"),
                "railix-app",
                "order-approval",
                "run-1",
                "transform-step",
                1,
                new RailixValue.StringValue("set ctx.customer.email"),
                changedPaths
        );
        final RunSignal.MetricEmitted metricEmitted = new RunSignal.MetricEmitted(
                "sig-2",
                Instant.parse("2026-06-26T10:15:31Z"),
                "railix-app",
                "order-approval",
                "run-1",
                "transform-step",
                1,
                "step.duration",
                new RailixValue.NumberValue(java.math.BigDecimal.ONE),
                "ms",
                labels
        );

        changedPaths.clear();
        labels.clear();

        assertThat(contextPatched.type()).isEqualTo("context.patched");
        assertThat(contextPatched.stepId()).contains("transform-step");
        assertThat(contextPatched.attempt()).hasValue(1);
        assertThat(contextPatched.changedPaths()).containsExactly(RailixPath.parse("ctx.customer.email"));
        assertThat(metricEmitted.labels()).containsEntry("step", "transform");
    }

    @Test
    void shouldRejectInvalidAttemptAndDuration() {
        assertThatThrownBy(() -> new RunSignal.StepStarted(
                "sig-3",
                Instant.parse("2026-06-26T10:15:30Z"),
                "railix-app",
                "order-approval",
                "run-1",
                "transform-step",
                0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptNumber");

        assertThatThrownBy(() -> new RunSignal.RunFinished(
                "sig-4",
                Instant.parse("2026-06-26T10:15:30Z"),
                "railix-app",
                "order-approval",
                "run-1",
                "ok",
                -1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationMs");
    }

    @Test
    void shouldExposeAllSignalVariantsThroughPublicContracts() {
        final Instant timestamp = Instant.parse("2026-06-27T10:00:00Z");
        final RailixValue.ObjectValue metadata = new RailixValue.ObjectValue(Map.of(
                "contentType", new RailixValue.StringValue("application/json")
        ));

        final RunSignal.RunStarted runStarted = new RunSignal.RunStarted(
                "sig-10",
                timestamp,
                "railix-app",
                "order-approval",
                "run-9",
                new RailixValue.StringValue("manual trigger")
        );
        final RunSignal.StepFinished stepFinished = new RunSignal.StepFinished(
                "sig-11",
                timestamp,
                "railix-app",
                "order-approval",
                "run-9",
                "transform",
                1,
                "ok",
                25
        );
        final RunSignal.StepFailed stepFailed = new RunSignal.StepFailed(
                "sig-12",
                timestamp,
                "railix-app",
                "order-approval",
                "run-9",
                "transform",
                2,
                new RailixValue.ObjectValue(Map.of("message", new RailixValue.StringValue("boom")))
        );
        final RunSignal.PermissionDecided permissionDecided = new RunSignal.PermissionDecided(
                "sig-13",
                timestamp,
                "railix-app",
                "order-approval",
                "run-9",
                "transform",
                1,
                "settings.secret",
                "settings.database.password",
                "granted",
                "explicit grant"
        );
        final RunSignal.AuditEmitted auditEmitted = new RunSignal.AuditEmitted(
                "sig-14",
                timestamp,
                "railix-app",
                "order-approval",
                "run-9",
                "transform",
                1,
                "credentials.materialized",
                new RailixValue.ObjectValue(Map.of("path", new RailixValue.StringValue("settings.database.password")))
        );
        final RunSignal.ResourceCreated resourceCreated = new RunSignal.ResourceCreated(
                "sig-15",
                timestamp,
                "railix-app",
                "order-approval",
                "run-9",
                "reply",
                1,
                "file-1",
                "file",
                metadata
        );
        final RunSignal.ReplyProduced replyProduced = new RunSignal.ReplyProduced(
                "sig-16",
                timestamp,
                "railix-app",
                "order-approval",
                "run-9",
                "file",
                new RailixValue.NumberValue(java.math.BigDecimal.valueOf(200)),
                metadata
        );
        final RunSignal.RunFinished runFinished = new RunSignal.RunFinished(
                "sig-17",
                timestamp,
                "railix-app",
                "order-approval",
                "run-9",
                "ok",
                30
        );

        assertThat(runStarted.type()).isEqualTo("run.started");
        assertThat(stepFinished.type()).isEqualTo("step.finished");
        assertThat(stepFinished.stepId()).contains("transform");
        assertThat(stepFailed.type()).isEqualTo("step.failed");
        assertThat(permissionDecided.type()).isEqualTo("permission.decided");
        assertThat(auditEmitted.type()).isEqualTo("audit.emitted");
        assertThat(resourceCreated.type()).isEqualTo("resource.created");
        assertThat(resourceCreated.metadata()).isEqualTo(metadata);
        assertThat(replyProduced.type()).isEqualTo("reply.produced");
        assertThat(runFinished.type()).isEqualTo("run.finished");
    }

    @Test
    void shouldExposeDefaultAndStepScopedAccessors() {
        final Instant timestamp = Instant.parse("2026-06-27T10:05:00Z");
        final RunSignal.RunStarted runStarted = new RunSignal.RunStarted(
                "sig-20",
                timestamp,
                "railix-app",
                "order-approval",
                "run-10",
                new RailixValue.StringValue("manual trigger")
        );
        final RunSignal.StepStarted stepStarted = new RunSignal.StepStarted(
                "sig-21",
                timestamp,
                "railix-app",
                "order-approval",
                "run-10",
                "transform",
                1
        );
        final RunSignal.ReplyProduced replyProduced = new RunSignal.ReplyProduced(
                "sig-22",
                timestamp,
                "railix-app",
                "order-approval",
                "run-10",
                "file",
                new RailixValue.NumberValue(java.math.BigDecimal.valueOf(200)),
                new RailixValue.ObjectValue(Map.of("contentType", new RailixValue.StringValue("application/json")))
        );

        assertThat(runStarted.stepId()).isEmpty();
        assertThat(runStarted.attempt()).isEmpty();
        assertThat(stepStarted.stepId()).contains("transform");
        assertThat(stepStarted.attempt()).hasValue(1);
        assertThat(replyProduced.stepId()).isEmpty();
        assertThat(replyProduced.attempt()).isEmpty();
    }

    @Test
    void shouldExposeSettingReadAndCopyMetricLabels() {
        final Instant timestamp = Instant.parse("2026-06-27T10:10:00Z");
        final Map<String, String> labels = new HashMap<>(Map.of("step", "transform"));

        final RunSignal.SettingRead settingRead = new RunSignal.SettingRead(
                "sig-23",
                timestamp,
                "railix-app",
                "order-approval",
                "run-10",
                "transform",
                2,
                RailixPath.parse("settings.database.password"),
                true,
                true
        );
        final RunSignal.MetricEmitted metricEmitted = new RunSignal.MetricEmitted(
                "sig-24",
                timestamp,
                "railix-app",
                "order-approval",
                "run-10",
                "transform",
                2,
                "step.duration",
                new RailixValue.NumberValue(java.math.BigDecimal.TEN),
                "ms",
                labels
        );

        labels.clear();

        assertThat(settingRead.type()).isEqualTo("setting.read");
        assertThat(settingRead.stepId()).contains("transform");
        assertThat(settingRead.attempt()).hasValue(2);
        assertThat(metricEmitted.labels()).containsEntry("step", "transform");
        assertThatThrownBy(() -> metricEmitted.labels().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectBlankAndNullSignalFields() {
        final Instant timestamp = Instant.parse("2026-06-27T10:15:00Z");

        assertThatThrownBy(() -> new RunSignal.ReplyProduced(
                "sig-25",
                timestamp,
                "railix-app",
                "order-approval",
                "run-10",
                " ",
                new RailixValue.NumberValue(java.math.BigDecimal.ONE),
                new RailixValue.ObjectValue(Map.of())
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mode must not be blank");

        assertThatThrownBy(() -> new RunSignal.ContextPatched(
                "sig-26",
                timestamp,
                "railix-app",
                "order-approval",
                "run-10",
                "transform",
                1,
                new RailixValue.StringValue("patch"),
                null
        )).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new RunSignal.MetricEmitted(
                "sig-27",
                timestamp,
                "railix-app",
                "order-approval",
                "run-10",
                "transform",
                1,
                "step.duration",
                new RailixValue.NumberValue(java.math.BigDecimal.ONE),
                "ms",
                null
        )).isInstanceOf(NullPointerException.class);
    }
}
