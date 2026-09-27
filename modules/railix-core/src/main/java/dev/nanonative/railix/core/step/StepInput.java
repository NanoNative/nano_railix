package dev.nanonative.railix.core.step;

import dev.nanonative.railix.core.project.WorkflowRuntime;
import dev.nanonative.railix.core.value.RailixValue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Immutable named Step data, supplied separately as compiled configuration and invocation context. */
public sealed class StepInput permits StepInput.ManagedInput {
    /** Runtime-owned resource access; Step authors use the resource methods on their context. */
    public interface Resources {
        /** Returns a registered resource with its exact declared type; missing/closed resources fail explicitly. */
        <T> T get(String name, Class<T> type);

        /** Acquires during PRE; a null sharing key means private ownership, otherwise equality selects sharing. */
        <T> T acquire(String name, Class<T> type, Object sharingKey,
                      Supplier<? extends T> factory, Consumer<? super T> close);
    }

    /**
     * Application-local source capability supplied to a Trigger's lifecycle context.
     * A source owns its activation and completion, independently of the kind of transport.
     * Registrations belong in PRE; retained callbacks must not retain invocation contexts.
     */
    public interface Source {
        /**
         * Registers activation after every PRE succeeds. The action must return promptly;
         * finite asynchronous work calls {@link #complete(int)} when finished.
         * Embedded initialization alone does not activate sources; application launch does.
         *
         * @param action one non-null activation action, registered at most once during PRE
         * @return this source for chaining
         * @throws IllegalStateException outside PRE or on duplicate activation registration
         */
        Source onStart(Runnable action);

        /**
         * Registers cleanup after admission closes and admitted requests, including their
         * response consumers, finish. Actions run in reverse registration order after POST,
         * also after partial PRE failure. An action must not wait indefinitely.
         *
         * @param action non-null cleanup action for an acquired source resource
         * @return this source for chaining
         * @throws IllegalStateException outside PRE
         */
        Source onClose(Runnable action);

        /**
         * Returns immutable business launch arguments; read them from the activation action.
         * Embedded initialization before launch and development launch supply an empty list.
         */
        List<String> arguments();

        /**
         * Runs this Trigger with native inputs and consumes the response before leaving admission.
         * Rejection is delivered to the consumer as a result; ordinary flow failure is not thrown.
         * Consumer exceptions propagate after releasing admission. No request is retained.
         *
         * @param values non-null native input values keyed by receive port
         * @param response non-null synchronous response consumer, called even on rejected admission
         * @return the complete flow result and mapped response slots
         */
        WorkflowRuntime.SourceResult run(Map<String, RailixValue> values,
                                         Consumer<WorkflowRuntime.SourceResult> response);

        /** Runs a source without a transport response consumer; returns its result and mapped response slots. */
        default WorkflowRuntime.SourceResult run(final Map<String, RailixValue> values) {
            return run(values, ignored -> { });
        }

        /**
         * Marks finite source activity complete, without stopping other sources or its admitted work.
         * Repeated calls with the same status are harmless; conflicting statuses are rejected.
         *
         * @param status process status from 0 through 255, used only when standalone launch ends
         * @return this source for chaining
         * @throws IllegalArgumentException for an out-of-range status
         * @throws IllegalStateException before activation or on a conflicting repeated status
         */
        Source complete(int status);
    }

    @FunctionalInterface
    public interface Program {
        /**
         * Runs the declared nested Steps synchronously.
         * Runtime-provided programs are valid only on the owning invocation thread and until the
         * enclosing static Step process call returns. Compiled configuration has no invocation programs.
         *
         * @return the nested outcome and optional value
         * @throws InterruptedException when nested execution is cancelled
         * @throws IllegalStateException when the runtime program is used outside its invocation
         */
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

    /**
     * Creates a payload-free lifecycle context, without adding source storage to ordinary invocations.
     *
     * @param primaryOutcome non-blank primary outcome
     * @param source application-owned source, or Java null for an ordinary Step
     * @return a new immutable lifecycle context
     * @throws IllegalArgumentException when the outcome is missing or blank
     */
    public static StepInput lifecycle(final String primaryOutcome, final Source source) {
        return lifecycle(primaryOutcome, source, null);
    }

    /**
     * Creates a payload-free runtime lifecycle context.
     *
     * @param primaryOutcome non-blank primary outcome
     * @param source application-owned source, or Java null for an ordinary Step
     * @param resources placement-owned access, or Java null when unavailable
     * @return a new immutable context with only the supplied capabilities
     * @throws IllegalArgumentException for a missing/blank outcome
     */
    public static StepInput lifecycle(final String primaryOutcome, final Source source, final Resources resources) {
        return source == null && resources == null
                ? new StepInput(Map.of(), Map.of(), Map.of(), Map.of(), primaryOutcome)
                : new ManagedInput(Map.of(), Map.of(), Map.of(), Map.of(), primaryOutcome, source, resources);
    }

    /**
     * Creates a runtime invocation without adding resource storage to ordinary Steps.
     *
     * @param values resolved non-null named values, copied immutably
     * @param options resolved non-null options, copied immutably
     * @param programs invocation-bound non-null nested programs, copied immutably
     * @param selected resolved non-null option/candidate inputs, copied immutably
     * @param outcome non-blank primary outcome
     * @param resources placement-owned access, or Java null for a plain invocation
     * @return a new context without a source capability
     * @throws IllegalArgumentException for missing maps or a missing/blank outcome
     * @throws NullPointerException for Java null map entries
     */
    public static StepInput invocation(final Map<String, RailixValue> values, final Map<String, String> options,
                                       final Map<String, Program> programs, final Map<String, StepInput> selected,
                                       final String outcome, final Resources resources) {
        return resources == null ? new StepInput(values, options, programs, selected, outcome)
                : new ManagedInput(values, options, programs, selected, outcome, null, resources);
    }

    /**
     * Registers a private resource once during this placement's PRE and returns it.
     * It remains available in PROCESS and POST. Railix attempts cleanup once, after admitted
     * work and POST, including partial startup failure. Do not close it manually or retain
     * the context. The resource itself must support concurrent use when the Step does.
     * Ordinary Java-owned objects need no registration and are not cleaned up by Railix.
     * The factory owns any partially constructed object until it returns successfully.
     * Cleanup must not wait indefinitely and must not register further resources.
     *
     * @param name non-blank name, unique within this Step placement
     * @param type exact non-null declared resource type
     * @param factory non-null factory returning a non-null resource
     * @param close non-null cleanup function; failures are reported without skipping other cleanup
     * @return the acquired resource, never Java null
     * @throws IllegalStateException outside PRE or on duplicate registration
     * @throws IllegalArgumentException for an invalid name
     * @throws NullPointerException for a missing type, function or factory result
     * @throws ClassCastException if a raw factory violates the declared Java type
     */
    public final <T> T resource(final String name, final Class<T> type,
                                final Supplier<? extends T> factory, final Consumer<? super T> close) {
        return resources().acquire(name, type, null, factory, close);
    }

    /**
     * Registers an explicitly shared resource during PRE. Sharing requires equal keys,
     * the same Step definition, resource name and declared type in this application.
     * The first registration creates it; later owners reuse it without running their
     * factories. Cleanup runs after the last owner's POST, once, using the first closer.
     * Equal keys must describe equivalent factory/cleanup behavior; functions are not inspected.
     *
     * @param name non-blank name, unique within this Step placement
     * @param type exact non-null declared resource type
     * @param sharingKey immutable, non-null value with stable equals/hashCode, containing
     *                   every setting relevant to compatibility, including session isolation
     * @param factory non-null factory used only for the first compatible registration
     * @param close non-null cleanup function used from the first compatible registration
     * @return the acquired resource, never Java null
     * @throws IllegalArgumentException for an invalid name or null sharing key
     * @throws IllegalStateException outside PRE or on duplicate registration
     * @throws NullPointerException for a missing type, function or factory result
     * @throws ClassCastException if a raw factory violates the declared Java type
     * @see #resource(String, Class, Supplier, Consumer)
     */
    public final <T> T resource(final String name, final Class<T> type, final Object sharingKey,
                                final Supplier<? extends T> factory, final Consumer<? super T> close) {
        if (sharingKey == null) throw new IllegalArgumentException("Shared resource key cannot be Java null.");
        return resources().acquire(name, type, sharingKey, factory, close);
    }

    /**
     * Returns this placement's resource after registration, including from POST.
     * This is a Java capability, not request data, and is never serialized into context.
     *
     * @param name registered resource name
     * @param type exact type used at registration
     * @return the existing resource without creating another instance
     * @throws IllegalStateException for configuration, missing resources or closed resources
     * @throws IllegalArgumentException for an invalid name or different declared type
     * @throws NullPointerException for a missing type
     */
    public final <T> T resource(final String name, final Class<T> type) {
        return resources().get(name, type);
    }

    Resources resources() {
        throw new IllegalStateException("Resources are available only in a registered Step context.");
    }

    /**
     * Returns the application-local source for this Trigger lifecycle context.
     *
     * @return the source capability, never Java null
     * @throws IllegalStateException for configuration, ordinary Steps or invocation contexts
     */
    public Source source() {
        throw new IllegalStateException("Source is available only in a Trigger lifecycle context.");
    }

    private static final class ManagedInput extends StepInput {
        private final Source source;
        private final Resources resources;

        private ManagedInput(final Map<String, RailixValue> values, final Map<String, String> options,
                             final Map<String, Program> programs, final Map<String, StepInput> selected,
                             final String primaryOutcome, final Source source, final Resources resources) {
            super(values, options, programs, selected, primaryOutcome);
            this.source = source;
            this.resources = resources;
        }

        @Override
        public Source source() {
            return source == null ? super.source() : source;
        }

        @Override
        Resources resources() {
            return resources == null ? super.resources() : resources;
        }
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

    /** Returns every resolved named value for this invocation as an immutable map. */
    public Map<String, RailixValue> values() {
        return values;
    }

    /** Returns every selected tagged option for this invocation as an immutable map. */
    public Map<String, String> options() {
        return options;
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

    /**
     * Runs the nested program with the value relationship declared by its {@link StepDefinition}.
     * Runtime programs are synchronous and invocation-scoped as documented by {@link Program#run()}.
     *
     * @param name declared nested-Step input name
     * @return the nested outcome and optional value
     * @throws IllegalArgumentException when the name is blank or unavailable
     * @throws IllegalStateException when a runtime program is used outside its invocation
     * @throws InterruptedException when nested execution is cancelled
     */
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

    /**
     * Returns the stable route selected by one accepted authored candidate.
     *
     * @param name owning candidates input name
     * @return selected authored workflow outcome
     * @throws IllegalArgumentException when the name is blank or no candidate matched
     */
    public String selectedOutcome(final String name) {
        return selected(name).primaryOutcome();
    }

    /**
     * Returns one accepted authored route, or this Step's primary outcome when no candidate matched.
     *
     * @param name owning candidates input name
     * @return selected authored outcome or the primary outcome
     * @throws IllegalArgumentException when the name is blank
     */
    public String selectedOutcomeOrPrimary(final String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Selected input name must be a non-blank string.");
        }
        final StepInput selected = selectedInputs.get(name);
        return selected == null ? primaryOutcome : selected.primaryOutcome();
    }
}
