package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.model.StepContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuiltRailixAppTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExecuteEmbeddedPlanThroughInjectedResolver() {
        final AppPlan plan = new AppPlan(
                "railix-app",
                "order-flow",
                "capture",
                List.of(new AppPlan.StepInvocation("capture", "test.capture", Map.of()))
        );
        final BuiltRailixApp app = new BuiltRailixApp(
                plan,
                new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC)),
                use -> step(input -> new Step.Result(
                        "ok",
                        List.of(new Patch.Set(
                                RailixPath.parse("ctx.payload"),
                                new Patch.ExpressionSource(new Patch.PathExpression(RailixPath.parse("payload")))
                        ))
                ))
        );

        final LocalExecutionKernel.RunRecord record = app.run(
                new LocalExecutionKernel.RunRequest("run-built-1", tempDir, envelope())
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.context().get(RailixPath.parse("ctx.payload"))).isEqualTo(envelope().payload());
        assertThat(app.plan()).isEqualTo(plan);
    }

    @Test
    void shouldRejectNullBootstrapCollaborators() {
        final AppPlan plan = new AppPlan(
                "railix-app",
                "order-flow",
                "capture",
                List.of(new AppPlan.StepInvocation("capture", "test.capture", Map.of()))
        );
        final LocalExecutionKernel kernel = new LocalExecutionKernel();
        final StepResolver resolver = use -> step(input -> new Step.Result("ok", List.of()));

        assertThatThrownBy(() -> new BuiltRailixApp(null, kernel, resolver))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("plan");
        assertThatThrownBy(() -> new BuiltRailixApp(plan, null, resolver))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("kernel");
        assertThatThrownBy(() -> new BuiltRailixApp(plan, kernel, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("resolver");
    }

    private static Step step(final java.util.function.Function<Step.ExecutionInput, Step.Result> executor) {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        "test.capture",
                        "0.1.0",
                        "test.capture",
                        "test.capture",
                        StepContract.Kind.NORMAL,
                        List.of(),
                        List.of(),
                        Map.of(),
                        new StepContract.Settings(List.of()),
                        dev.nanonative.railix.kernel.model.PermissionSet.none(),
                        new StepContract.Timeout(Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
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

    private static Envelope envelope() {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "customer", new RailixValue.ObjectValue(Map.of(
                                "email", new RailixValue.StringValue("USER@EXAMPLE.COM")
                        ))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        );
    }
}
