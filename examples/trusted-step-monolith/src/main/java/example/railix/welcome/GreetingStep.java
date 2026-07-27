package example.railix.welcome;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

/** External trusted Step that prefixes one string. */
public final class GreetingStep {
    private GreetingStep() {
    }

    /** Returns the complete Step contract and behavior used by the authored flow. */
    public static StepDefinition definition() {
        return StepDefinition.named("example.text.prefix", "1.0.0")
                .config("prefix", ValueShape.string(), RailixValue.string("Welcome, "))
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output(
                        "text",
                        RailixValue.string(input.configString("prefix") + input.string("text"))
                ));
    }
}
