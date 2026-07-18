package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppPlanTest {

    @Test
    void shouldCopyConnectionsAndOrderedOperatorPipeline() {
        final List<AppPlan.OperatorInvocation> operators = new ArrayList<>(List.of(
                new AppPlan.OperatorInvocation(
                        "railix.op.string.trim",
                        new RailixValue.ObjectValue(Map.of())
                )
        ));
        final List<AppPlan.Connection> connections = new ArrayList<>(List.of(new AppPlan.Connection(
                "normalize",
                new AppPlan.PortRef("trigger", "value"),
                new AppPlan.PortRef("reply", "value"),
                operators
        )));
        final AppPlan plan = new AppPlan(
                "railix-app",
                "order-flow",
                "trigger",
                PermissionSet.none(),
                List.of(
                        new AppPlan.StepInvocation("trigger", "test.trigger", Map.of("ok", "reply")),
                        new AppPlan.StepInvocation("reply", "test.reply", Map.of())
                ),
                connections
        );

        operators.clear();
        connections.clear();

        assertThat(plan.connections()).hasSize(1);
        assertThat(plan.connections().getFirst().operators())
                .extracting(AppPlan.OperatorInvocation::use)
                .containsExactly("railix.op.string.trim");
    }

    @Test
    void shouldRejectDuplicateConnectionIds() {
        final AppPlan.Connection connection = connection("duplicate", "trigger", "reply");

        assertThatThrownBy(() -> planWithConnections(List.of(connection, connection)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate connection id: duplicate");
    }

    @Test
    void shouldRejectUnknownConnectionSourceStep() {
        assertThatThrownBy(() -> planWithConnections(List.of(connection("invalid", "missing", "reply"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown connection source step id: missing");
    }

    @Test
    void shouldRejectUnknownConnectionTargetStep() {
        assertThatThrownBy(() -> planWithConnections(List.of(connection("invalid", "trigger", "missing"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown connection target step id: missing");
    }

    @Test
    void shouldCopyStepsAndResolveNextTargets() {
        final RailixValue.ObjectValue config = new RailixValue.ObjectValue(Map.of(
                "message", new RailixValue.StringValue("hello")
        ));
        final List<AppPlan.StepInvocation> steps = new ArrayList<>(List.of(
                new AppPlan.StepInvocation("trigger", "test.trigger", config, Map.of("ok", "reply")),
                new AppPlan.StepInvocation("reply", "test.reply", Map.of())
        ));

        final AppPlan plan = new AppPlan("railix-app", "order-flow", "trigger", steps);

        steps.clear();

        assertThat(plan.steps()).hasSize(2);
        assertThat(plan.step("trigger").use()).isEqualTo("test.trigger");
        assertThat(plan.step("trigger").config()).isEqualTo(config);
        assertThat(plan.nextStepId("trigger", "ok")).contains("reply");
        assertThat(plan.nextStepId("reply", "ok")).isEmpty();
        assertThatThrownBy(() -> plan.steps().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldSupportWildcardNextTransitions() {
        final AppPlan plan = new AppPlan(
                "railix-app",
                "order-flow",
                "trigger",
                List.of(
                        new AppPlan.StepInvocation("trigger", "test.trigger", Map.of("*", "reply")),
                        new AppPlan.StepInvocation("reply", "test.reply", Map.of())
                )
        );

        assertThat(plan.nextStepId("trigger", "unexpected")).contains("reply");
    }

    @Test
    void shouldRejectUnknownTargetsAndDuplicateIds() {
        assertThatThrownBy(() -> new AppPlan(
                "railix-app",
                "order-flow",
                "trigger",
                List.of(new AppPlan.StepInvocation("trigger", "test.trigger", Map.of("ok", "missing")))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown next step id");

        assertThatThrownBy(() -> new AppPlan(
                "railix-app",
                "order-flow",
                "trigger",
                List.of(
                        new AppPlan.StepInvocation("trigger", "test.trigger", Map.of()),
                        new AppPlan.StepInvocation("trigger", "test.reply", Map.of())
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate step id");
    }

    @Test
    void shouldRejectBlankInvocationFields() {
        final Map<String, String> next = new HashMap<>(Map.of("ok", "reply"));

        assertThatThrownBy(() -> new AppPlan.StepInvocation(" ", "test.trigger", next))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(() -> new AppPlan.StepInvocation("trigger", " ", next))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("use");
        assertThatThrownBy(() -> new AppPlan.StepInvocation("trigger", "test.trigger", null, next))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("config");
        assertThatThrownBy(() -> new AppPlan.StepInvocation("trigger", "test.trigger", Map.of(" ", "reply")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcome");
    }

    @Test
    void shouldRejectBlankPlanFieldsAndUnknownStepLookup() {
        assertThatThrownBy(() -> new AppPlan(" ", "order-flow", "trigger", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appId");
        assertThatThrownBy(() -> new AppPlan("railix-app", " ", "trigger", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flowId");
        assertThatThrownBy(() -> new AppPlan(
                "railix-app",
                "order-flow",
                " ",
                List.of(new AppPlan.StepInvocation("trigger", "test.trigger", Map.of()))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("triggerStepId");
        assertThatThrownBy(() -> new AppPlan(
                "railix-app",
                "order-flow",
                "missing",
                List.of(new AppPlan.StepInvocation("trigger", "test.trigger", Map.of()))
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown trigger step id");

        final AppPlan plan = new AppPlan(
                "railix-app",
                "order-flow",
                "trigger",
                List.of(new AppPlan.StepInvocation("trigger", "test.trigger", Map.of()))
        );

        assertThatThrownBy(() -> plan.step("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown step id");
    }

    @Test
    void shouldExposeCopiedPlanPermissionsAndRejectNullPermissions() {
        final PermissionSet permissions = new PermissionSet(
                Map.of("settings.secret", List.of("settings.database.password")),
                Map.of("settings.secret", List.of("settings.database.password")),
                List.of()
        );

        final AppPlan plan = new AppPlan(
                "railix-app",
                "order-flow",
                "trigger",
                permissions,
                List.of(new AppPlan.StepInvocation("trigger", "test.trigger", Map.of()))
        );

        assertThat(plan.permissions()).isEqualTo(permissions);
        assertThatThrownBy(() -> new AppPlan(
                "railix-app",
                "order-flow",
                "trigger",
                null,
                List.of(new AppPlan.StepInvocation("trigger", "test.trigger", Map.of()))
        )).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("permissions");
    }

    private static AppPlan planWithConnections(final List<AppPlan.Connection> connections) {
        return new AppPlan(
                "railix-app",
                "order-flow",
                "trigger",
                PermissionSet.none(),
                List.of(
                        new AppPlan.StepInvocation("trigger", "test.trigger", Map.of("ok", "reply")),
                        new AppPlan.StepInvocation("reply", "test.reply", Map.of())
                ),
                connections
        );
    }

    private static AppPlan.Connection connection(
            final String id,
            final String source,
            final String target
    ) {
        return new AppPlan.Connection(
                id,
                new AppPlan.PortRef(source, "value"),
                new AppPlan.PortRef(target, "value"),
                List.of()
        );
    }
}
