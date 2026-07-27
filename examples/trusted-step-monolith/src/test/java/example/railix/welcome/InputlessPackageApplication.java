package example.railix.welcome;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import dev.nanonative.railix.creator.RailixMain;

import java.util.List;

/** Real inputless application used by packaged startup and scheduled subprocess E2Es. */
public final class InputlessPackageApplication {
    private InputlessPackageApplication() {
    }

    static StepDefinition definition() {
        return StepDefinition.named("example.package-signal", "1.0.0")
                .output("message", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output(
                        "message",
                        RailixValue.string("triggered")
                ));
    }

    /** Runs an inputless packaged trigger through the public application launcher. */
    public static void main(final String[] arguments) {
        RailixMain.exitOnFailure(RailixMain.executeApplication(
                List.of(arguments),
                InputlessPackageApplication.class.getResourceAsStream("/railix.flow.json"),
                InputlessPackageApplication.class.getResourceAsStream("/railix.lock.json"),
                StepCatalog.of(definition())
        ));
    }
}
