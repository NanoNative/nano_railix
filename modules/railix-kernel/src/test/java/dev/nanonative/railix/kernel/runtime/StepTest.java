package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepTest {

    @Test
    void shouldRejectBlankNamedExecutionInput() {
        assertThatThrownBy(() -> executionInput(Map.of()).requireInput(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name must not be blank");
    }

    @Test
    void shouldRejectMissingNamedExecutionInput() {
        assertThatThrownBy(() -> executionInput(Map.of()).requireInput("value"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Missing execution input: value");
    }

    @Test
    void shouldPopulateConfigAcrossExecutionInputConvenienceConstructors() {
        final Envelope envelope = envelope();
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final RailixValue.ObjectValue config = new RailixValue.ObjectValue(Map.of(
                "message", new RailixValue.StringValue("hello")
        ));
        final Step.SettingsView settingsView = path -> Optional.of(new Step.ReadValue(
                path,
                new RailixValue.StringValue("configured"),
                true,
                false
        ));
        final Step.ResourceScope resourceScope = new Step.ResourceScope() {
            @Override
            public Step.RegisteredResource register(
                    final String refType,
                    final RailixValue.ObjectValue metadata,
                    final AutoCloseable resource
            ) {
                return new Step.RegisteredResource("ref-1", refType, metadata);
            }
        };

        assertThat(new Step.ExecutionInput(
                envelope,
                context,
                config,
                settingsView,
                resourceScope,
                Step.MetricScope.none(),
                Step.AuditScope.none()
        ).config()).isEqualTo(config);
        assertThat(new Step.ExecutionInput(
                envelope,
                context,
                config,
                settingsView,
                resourceScope,
                Step.MetricScope.none()
        ).config()).isEqualTo(config);
        assertThat(new Step.ExecutionInput(
                envelope,
                context,
                settingsView,
                resourceScope,
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                Step.PermissionScope.none()
        ).config()).isEqualTo(new RailixValue.ObjectValue(Map.of()));
        assertThat(new Step.ExecutionInput(
                envelope,
                context,
                settingsView,
                resourceScope,
                Step.MetricScope.none(),
                Step.AuditScope.none()
        ).config()).isEqualTo(new RailixValue.ObjectValue(Map.of()));
        assertThat(new Step.ExecutionInput(
                envelope,
                context,
                settingsView,
                resourceScope,
                Step.MetricScope.none()
        ).config()).isEqualTo(new RailixValue.ObjectValue(Map.of()));
        assertThat(new Step.ExecutionInput(envelope, context, config, settingsView, resourceScope).config()).isEqualTo(config);
        assertThat(new Step.ExecutionInput(envelope, context, config, settingsView).config()).isEqualTo(config);
        assertThat(new Step.ExecutionInput(envelope, context, config).config()).isEqualTo(config);
        assertThat(new Step.ExecutionInput(envelope, context, settingsView, resourceScope).config())
                .isEqualTo(new RailixValue.ObjectValue(Map.of()));
        assertThat(new Step.ExecutionInput(envelope, context, settingsView).config())
                .isEqualTo(new RailixValue.ObjectValue(Map.of()));
        assertThat(new Step.ExecutionInput(envelope, context).config())
                .isEqualTo(new RailixValue.ObjectValue(Map.of()));
    }

    @Test
    void shouldRejectMissingSettingAndUnavailableScopesClearly() {
        assertThatThrownBy(() -> Step.SettingsView.none().require(RailixPath.parse("settings.app.mode")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing setting for execution");
        assertThatThrownBy(() -> Step.MetricScope.none().emit("metric", RailixValue.NULL, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Metric emission is not available");
        assertThatThrownBy(() -> Step.AuditScope.none().emit("audit", new RailixValue.ObjectValue(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Audit emission is not available");
        assertThatThrownBy(() -> Step.PermissionScope.none().require("settings.read", "settings.app.mode"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Permission checks are not available");
    }

    @Test
    void shouldRejectUnavailableResourceRegistrationsAndAllowEmptyFind() {
        final Step.ResourceScope scope = Step.ResourceScope.none();

        assertThat(scope.find("missing")).isEmpty();
        assertThatThrownBy(() -> scope.find(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("refId");
        assertThatThrownBy(() -> scope.register("file", new RailixValue.ObjectValue(Map.of()), () -> {
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Resource registration is not available");
        assertThatThrownBy(() -> scope.registerMetadata("file", new RailixValue.ObjectValue(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Resource registration is not available");
    }

    private static Step.ExecutionInput executionInput(final Map<String, RailixValue> inputs) {
        final Envelope envelope = envelope();
        return new Step.ExecutionInput(
                envelope,
                InMemoryContextDoc.fromEnvelope(envelope),
                inputs,
                new RailixValue.ObjectValue(Map.of()),
                Step.SettingsView.none(),
                Step.ResourceScope.none(),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                Step.PermissionScope.none()
        );
    }

    private static Envelope envelope() {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "customer", new RailixValue.ObjectValue(Map.of(
                                "email", new RailixValue.StringValue("user@example.com")
                        ))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(dev.nanonative.railix.kernel.model.Reply.Mode.IMMEDIATE))
        );
    }
}
