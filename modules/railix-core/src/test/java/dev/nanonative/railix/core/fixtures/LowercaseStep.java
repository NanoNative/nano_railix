package dev.nanonative.railix.core.fixtures;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.Locale;

public final class LowercaseStep {
    private LowercaseStep() {
    }

    public static StepDefinition definition() {
        return StepDefinition.named("text.lowercase", "1.0.0")
                .config("languageTag", ValueShape.string(), RailixValue.string("und"))
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output(
                        "text",
                        RailixValue.string(input.string("text").toLowerCase(
                                Locale.forLanguageTag(input.configString("languageTag"))
                        ))
                ));
    }
}
