package dev.nanonative.railix.core.step;

import dev.nanonative.railix.core.value.RailixValue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable resolved values and nested Step programs for one invocation. */
public final class StepInput {
    @FunctionalInterface
    public interface Program {
        ProgramResult run() throws InterruptedException;
    }

    /** One completed, skipped, or explicitly branched nested program. */
    public record ProgramResult(
            String primaryOutcome,
            String outcome,
            List<RailixValue> values
    ) {
        public ProgramResult {
            if (primaryOutcome == null || primaryOutcome.isBlank()
                    || outcome == null || outcome.isBlank()) {
                throw new IllegalArgumentException("Nested Step outcomes must be non-blank strings.");
            }
            if (values == null) {
                throw new IllegalArgumentException("Nested Step values cannot be Java null.");
            }
            if (values.stream().anyMatch(value -> value == null)) {
                throw new IllegalArgumentException("Nested Step values cannot contain Java null.");
            }
            values = List.copyOf(values);
            if (primaryOutcome.equals(outcome) && values.size() > 1) {
                throw new IllegalArgumentException("Completed nested Steps may return at most one value.");
            }
            if (!primaryOutcome.equals(outcome) && !values.isEmpty()) {
                throw new IllegalArgumentException("Branched nested Steps cannot return a value.");
            }
        }

        /** Writes a completed value, continues a skipped program, or propagates its explicit outcome. */
        public StepResult write(final String input) {
            if (!primaryOutcome.equals(outcome)) {
                return StepResult.outcome(outcome);
            }
            return values.isEmpty()
                    ? StepResult.outcome(primaryOutcome)
                    : StepResult.outcome(primaryOutcome).write(input, values.getFirst());
        }

        /** Writes a completed value and otherwise continues without changing workflow context. */
        public StepResult writeWhenPresent(final String input) {
            return values.isEmpty()
                    ? StepResult.outcome(primaryOutcome)
                    : StepResult.outcome(primaryOutcome).write(input, values.getFirst());
        }
    }

    private final Map<String, RailixValue> values;
    private final Map<String, String> options;
    private final Map<String, Program> programs;
    private final Map<String, StepInput> selectedInputs;
    private final String primaryOutcome;

    public StepInput(
            final Map<String, RailixValue> values,
            final Map<String, String> options,
            final Map<String, Program> programs,
            final Map<String, StepInput> selectedInputs,
            final String primaryOutcome
    ) {
        if (values == null || options == null || programs == null || selectedInputs == null) {
            throw new IllegalArgumentException("Step invocation maps cannot be Java null.");
        }
        if (primaryOutcome == null || primaryOutcome.isBlank()) {
            throw new IllegalArgumentException("Step primary outcome must be a non-blank string.");
        }
        this.values = Map.copyOf(values);
        this.options = Map.copyOf(options);
        this.programs = Map.copyOf(programs);
        this.selectedInputs = Map.copyOf(selectedInputs);
        this.primaryOutcome = primaryOutcome;
    }

    public RailixValue value(final String name) {
        return optionalValue(name).orElseThrow(() ->
                new IllegalArgumentException("Step input is not available: " + name));
    }

    public Optional<RailixValue> optionalValue(final String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Step input name must be a non-blank string.");
        }
        return Optional.ofNullable(values.get(name));
    }

    public String string(final String name) {
        return switch (value(name)) {
            case RailixValue.StringValue string -> string.value();
            default -> throw new IllegalArgumentException("Step input is not a string: " + name);
        };
    }

    public String option(final String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Step option name must be a non-blank string.");
        }
        final String option = options.get(name);
        if (option == null) {
            throw new IllegalArgumentException("Step option is not available: " + name);
        }
        return option;
    }

    /** Returns the resolved child inputs owned by the selected tagged option. */
    public StepInput selected(final String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Selected input name must be a non-blank string.");
        }
        final StepInput selected = selectedInputs.get(name);
        if (selected == null) {
            throw new IllegalArgumentException("Selected input is not available: " + name);
        }
        return selected;
    }

    /** Runs the nested program with the value relationship declared by its {@link StepDefinition}. */
    public ProgramResult run(final String name) throws InterruptedException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Nested Step input name must be a non-blank string.");
        }
        final Program program = programs.get(name);
        if (program == null) {
            throw new IllegalArgumentException("Nested Step input is not available: " + name);
        }
        return program.run();
    }

    public String primaryOutcome() {
        return primaryOutcome;
    }
}
