package example.railix.welcome;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.creator.RailixMain;
import dev.nanonative.railix.stdlib.text.LowercaseStep;

import java.util.List;

/** Standalone custom monolith built from the complete authored flow and its two Steps. */
public final class TrustedGreetingMain {
    private static final StepCatalog STEPS = StepCatalog.of(
            LowercaseStep.definition(),
            GreetingStep.definition()
    );

    private TrustedGreetingMain() {
    }

    /** Starts packaged triggers or opens Creator with the exact packaged Step catalog. */
    public static void main(final String[] arguments) {
        RailixMain.exitOnFailure(RailixMain.executeApplication(
                List.of(arguments),
                TrustedGreetingMain.class.getResourceAsStream("/railix.flow.json"),
                TrustedGreetingMain.class.getResourceAsStream("/railix.lock.json"),
                STEPS
        ));
    }
}
