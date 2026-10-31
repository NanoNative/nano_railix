package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepDefinition.Input;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Bundle-owned authoring code; the worker must load this class from its locked JAR. */
public final class ValidationSteps {
    private static final Set<String> CHECKED = new HashSet<>();

    public static List<StepDefinition> definitions() {
        return List.of(
                StepDefinition.named("external.validation", "1")
                        .input("marker", Input.json(ValueShape.STRING).defaultValue(RailixValue.string("")))
                        .input("value", Input.json(ValueShape.STRING).defaultValue(RailixValue.string("valid"))
                                .validation(String.class, ValidationSteps::validate))
                        .input("options", Input.options(Input.option("custom").input("port",
                                Input.json(ValueShape.NUMBER).defaultValue(RailixValue.number(8080))
                                        .validation(Integer.class, value -> value > 0 && value < 65536
                                                ? Optional.empty() : Optional.of("Choose a usable port."))))
                                .defaultOption("custom"))
                        .implementation(ValidationProcess.class),
                StepDefinition.named("external.validated-value", "1")
                        .receive("value", ValueShape.ANY).returns("value", ValueShape.ANY)
                        .input("value", Input.json(ValueShape.STRING).validation(String.class, ValidationSteps::validate))
                        .implementation(ValidationProcess.class));
    }

    public static final class EmptyProvider {
        public static List<StepDefinition> definitions() { return List.of(); }
    }

    private static Optional<String> validate(final String value) {
        if (!ValidationSteps.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith(".jar")) {
            return Optional.of("Validator was not loaded from the installed bundle.");
        }
        if (!CHECKED.add(value)) return Optional.of("Duplicate evaluation in the same batch.");
        if (value.startsWith("await:")) {
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(value.substring(6)),
                        Long.toString(ProcessHandle.current().pid()));
            } catch (final java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
            while (true) java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
        }
        return switch (value) {
            case "invalid" -> Optional.of("Choose a supported mode.");
            case "pid" -> Optional.of("worker:" + ProcessHandle.current().pid());
            case "null" -> null;
            case "blank" -> Optional.of(" ");
            case "oversized" -> Optional.of("x".repeat(1_048_577));
            case "throw" -> throw new IllegalStateException("private details");
            case "exit" -> { System.exit(7); yield Optional.empty(); }
            case "hang" -> {
                while (true) java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L);
            }
            default -> Optional.empty();
        };
    }
}
