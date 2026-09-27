package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;

/** Runtime code does not depend on configuration-validation infrastructure. */
public final class ValidationProcess {
    public static StepResult pre(final StepInput configuration, final StepInput context) {
        return mark(configuration, context, "pre");
    }

    public static StepResult process(final StepInput configuration, final StepInput context) {
        return mark(configuration, context, "process");
    }

    public static StepResult post(final StepInput configuration, final StepInput context) {
        return mark(configuration, context, "post");
    }

    private static StepResult mark(final StepInput configuration, final StepInput context, final String phase) {
        if (configuration.values().get("marker") instanceof dev.nanonative.railix.core.value.RailixValue.StringValue marker
                && !marker.value().isEmpty()) {
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(marker.value()), phase + "\n",
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (final java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }
        return StepResult.outcome(context.primaryOutcome());
    }
}
