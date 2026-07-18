package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.StepContract;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class TestBootstrapStepProvider implements StepProvider {

    public static final String USE = "test.bootstrap.capture";

    @Override
    public List<String> supportedUses() {
        return List.of(USE);
    }

    @Override
    public Optional<Step> resolve(final String use) {
        if (!USE.equals(use)) {
            return Optional.empty();
        }
        return Optional.of(new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        USE,
                        "0.1.0",
                        USE,
                        "Bootstrap test capture step.",
                        StepContract.Kind.NORMAL,
                        List.of(),
                        List.of(),
                        Map.of("ok", new StepContract.Outcome("ok")),
                        new StepContract.Settings(List.of("settings.app.mode")),
                        new PermissionSet(
                                Map.of("settings.read", List.of("settings.app.mode")),
                                Map.of("settings.read", List.of("settings.app.mode")),
                                List.of()
                        ),
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
                final List<Patch> patches = new ArrayList<>();
                patches.add(new Patch.Set(
                        RailixPath.parse("ctx.payload"),
                        new Patch.ExpressionSource(new Patch.PathExpression(RailixPath.parse("payload")))
                ));
                input.settings().read(RailixPath.parse("settings.app.mode")).ifPresent(setting -> patches.add(new Patch.Set(
                        RailixPath.parse("ctx.settings.mode"),
                        new Patch.LiteralSource(setting.value())
                )));
                return new Result("ok", patches);
            }
        });
    }
}
