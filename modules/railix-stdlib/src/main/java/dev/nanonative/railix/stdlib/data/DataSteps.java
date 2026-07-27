package dev.nanonative.railix.stdlib.data;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

/** Compact built-in Steps for explicit validation, mapping, and translation. */
public final class DataSteps {
    private DataSteps() {
    }

    /** Returns a validator that preserves text and routes blank values explicitly. */
    public static StepDefinition nonblank() {
        return StepDefinition.named("text.nonblank", "1.0.0")
                .kind(StepDefinition.Kind.VALIDATOR)
                .config("trimBeforeCheck", ValueShape.BOOLEAN, RailixValue.bool(true))
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("valid")
                .outcome("invalid")
                .run(input -> {
                    final RailixValue value = input.value("text");
                    final String text = ((RailixValue.StringValue) value).value();
                    final boolean trim = ((RailixValue.BooleanValue) input.configValue(
                            "trimBeforeCheck"
                    )).value();
                    final String outcome = (trim ? text.isBlank() : text.isEmpty())
                            ? "invalid"
                            : "valid";
                    return StepResult.outcome(outcome).output("text", value);
                });
    }

    /** Returns a mapper that replaces only explicit JSON null. */
    public static StepDefinition defaultIfNull() {
        return StepDefinition.named("value.default-if-null", "1.0.0")
                .kind(StepDefinition.Kind.MAPPER)
                .requiredConfig("replacement", ValueShape.ANY)
                .input("value", ValueShape.ANY)
                .output("value", ValueShape.ANY)
                .outcome("kept")
                .outcome("defaulted")
                .run(input -> {
                    final RailixValue value = input.value("value");
                    final boolean replace = value instanceof RailixValue.NullValue;
                    return StepResult.outcome(replace ? "defaulted" : "kept").output(
                            "value",
                            replace ? input.configValue("replacement") : value
                    );
                });
    }

    /** Returns a translator that replaces one exact string and exposes misses. */
    public static StepDefinition translateExact() {
        return StepDefinition.named("text.translate-exact", "1.0.0")
                .kind(StepDefinition.Kind.TRANSLATOR)
                .requiredConfig("from", ValueShape.string())
                .requiredConfig("to", ValueShape.string())
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("translated")
                .outcome("unchanged")
                .run(input -> {
                    final RailixValue value = input.value("text");
                    final String text = ((RailixValue.StringValue) value).value();
                    final boolean matched = text.equals(input.configString("from"));
                    return StepResult.outcome(matched ? "translated" : "unchanged").output(
                            "text",
                            matched ? input.configValue("to") : value
                    );
                });
    }
}
