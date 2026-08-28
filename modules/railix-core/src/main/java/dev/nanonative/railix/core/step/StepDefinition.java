package dev.nanonative.railix.core.step;

import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One explicit Step contract shared by Creator, compiler, and runtime.
 *
 * <p>An ordinary Step may combine generic authored inputs without receiving special treatment from
 * the compiler or Creator:</p>
 * {@snippet :
 * StepDefinition.named("example.change-field", "1")
 *         .input("target", Input.path(PathAccess.READ_WRITE))
 *         .input("source", Input.candidates(
 *                 Input.option("current").fromParent("target"),
 *                 Input.option("literal")
 *                         .input("value", Input.json(ValueShape.ANY))
 *                         .fromOwned("value")
 *         ))
 *         .input("pipeline", Input.steps(
 *                 ValueSource.from("source")
 *                         .onMissing("missing")))
 *         .run(ChangeField.class);
 * }
 *
 * <p>A unary Step uses the same authored-input grammar for its own configuration:</p>
 * {@snippet :
 * StepDefinition.named("text.prefix", "1")
 *         .receive("value", ValueShape.STRING)
 *         .input("prefix", Input.json(ValueShape.STRING)
 *                 .defaultValue(RailixValue.string("railix:")))
 *         .returns("value", ValueShape.STRING)
 *         .run(Prefix.class);
 * }
 */
public final class StepDefinition {
    /** Only graph roles with genuinely different lifecycle or rendering are kinds. */
    public enum Kind {
        /** Structural, non-deletable application root. */
        APP,
        /** External ingress rendered directly below the application root. */
        TRIGGER,
        /** Ordinary Step, including unary value operations nested inside a STEPS input. */
        STEP
    }

    /** Declares whether a selected workflow-context path may be read, written, or both. */
    public enum PathAccess {
        /** Resolve a value without permitting writes. */
        READ,
        /** Permit writes without resolving the current value. */
        WRITE,
        /** Resolve the current value and permit writes. */
        READ_WRITE;

        /**
         * Returns whether runtime may resolve the selected path into a Step value.
         *
         * @return {@code true} for {@link #READ} and {@link #READ_WRITE}
         */
        public boolean readable() {
            return this != WRITE;
        }

        /**
         * Returns whether a Step result may write through the selected path.
         *
         * @return {@code true} for {@link #WRITE} and {@link #READ_WRITE}
         */
        public boolean writable() {
            return this != READ;
        }
    }

    /** Closed authoring-input algebra rendered and compiled without Step-specific branches. */
    public sealed interface Input permits JsonInput, PathInput, OptionsInput, CandidatesInput, MatcherGroupsInput,
            StepsInput {
        /**
         * Declares one canonical JSON value with the requested outer shape.
         *
         * @param shape accepted outer shape
         * @return required JSON input declaration
         */
        static JsonInput json(final ValueShape shape) {
            return new JsonInput(shape, List.of(), List.of(), true);
        }

        /**
         * Declares one author-selected workflow-context path with explicit access.
         *
         * @param access permitted read and write operations
         * @return required path input declaration
         */
        static PathInput path(final PathAccess access) {
            return new PathInput(access, List.of(), true);
        }

        /**
         * Declares one tagged choice from the provided alternatives.
         *
         * @param options selectable alternatives in display order
         * @return required options input declaration
         */
        static OptionsInput options(final Option... options) {
            if (options == null) {
                throw new IllegalArgumentException("Input options cannot be Java null.");
            }
            return new OptionsInput(List.of(options), List.of(), true);
        }

        /**
         * Declares an ordered list of authored value candidates.
         *
         * <p>Each candidate selects one tagged option and may declare a condition containing shared
         * unary {@code transforms} and independent Boolean {@code all} programs. Runtime exposes the
         * first present candidate accepted by every program. An omitted condition accepts any present
         * source. JSON null is present and receives no special treatment.</p>
         *
         * @param options available tagged value sources in display order
         * @return ordered candidate input declaration
         */
        static CandidatesInput candidates(final Option... options) {
            if (options == null) {
                throw new IllegalArgumentException("Candidate options cannot be Java null.");
            }
            return new CandidatesInput(List.of(options), List.of(), false);
        }

        /**
         * Declares ordered OR groups of ordered AND matchers that resolve to one BOOLEAN value.
         *
         * <p>Each matcher reuses the candidate option and condition grammar. A missing source is false
         * and skips its condition; canonical JSON null remains present. Groups, matchers, and condition
         * programs short-circuit in authored order. An omitted or empty outer list resolves false;
         * every authored inner group must contain at least one matcher. Step failures and cancellation
         * propagate instead of resolving false.</p>
         *
         * @param options available matcher source alternatives in display order
         * @return boolean-producing matcher-groups input declaration
         */
        static MatcherGroupsInput matcherGroups(final Option... options) {
            if (options == null) {
                throw new IllegalArgumentException("Matcher group options cannot be Java null.");
            }
            return new MatcherGroupsInput(List.of(options));
        }

        /**
         * Declares one ordered nested-Step pipeline bound to an explicit value source.
         *
         * @param valueSource source and missing-value relationship
         * @return ordered nested-Step input declaration
         */
        static StepsInput steps(final ValueSource valueSource) {
            if (valueSource == null) {
                throw new IllegalArgumentException("Nested Step value source cannot be Java null.");
            }
            return new StepsInput(valueSource, false);
        }

        /**
         * Starts one alternative owned by an {@link OptionsInput}, {@link CandidatesInput}, or
         * {@link MatcherGroupsInput}.
         *
         * @param name stable option tag stored in the project
         * @return option without child inputs or a resolved value source
         */
        static Option option(final String name) {
            return new Option(name, List.of(), List.of());
        }
    }

    /**
     * One canonical JSON authoring value.
     *
     * @param shape accepted outer value shape
     * @param defaults zero or one project default
     * @param range zero or two inclusive NUMBER bounds
     * @param required whether the project must provide the value
     */
    public record JsonInput(
            ValueShape shape,
            List<RailixValue> defaults,
            List<RailixValue> range,
            boolean required
    ) implements Input {
        /** Validates direct canonical construction; prefer {@link Input#json(ValueShape)}. */
        public JsonInput {
            if (shape == null) {
                throw new IllegalArgumentException("JSON input shape cannot be Java null.");
            }
            defaults = StepDefinition.defaults(defaults, shape, "JSON input");
            range = StepDefinition.range(range, shape);
            if (!defaults.isEmpty() && !range.isEmpty() && !within(defaults.getFirst(), range)) {
                throw new IllegalArgumentException("JSON input default must be within its range.");
            }
            if (required && !defaults.isEmpty()) {
                required = false;
            }
        }

        /**
         * Returns a copy with one canonical default, making the input optional to author.
         *
         * @param value canonical default matching {@link #shape()}
         * @return input with the supplied default
         */
        public JsonInput defaultValue(final RailixValue value) {
            if (value == null) {
                throw new IllegalArgumentException("JSON input default cannot be Java null.");
            }
            return new JsonInput(shape, List.of(value), range, false);
        }

        /**
         * Returns a copy that accepts an omitted project value.
         *
         * @return optional copy of this input
         */
        public JsonInput optional() {
            return new JsonInput(shape, defaults, range, false);
        }

        /**
         * Restricts a NUMBER input to the inclusive minimum and maximum.
         *
         * @param minimum inclusive lower NUMBER bound
         * @param maximum inclusive upper NUMBER bound
         * @return input with the supplied numeric range
         */
        public JsonInput between(final RailixValue minimum, final RailixValue maximum) {
            if (minimum == null || maximum == null) {
                throw new IllegalArgumentException("JSON input range cannot contain Java null.");
            }
            return new JsonInput(shape, defaults, List.of(minimum, maximum), required);
        }

        /**
         * Returns the authored default when declared.
         *
         * @return declared default, or empty when the input has none
         */
        public Optional<RailixValue> defaultValue() {
            return defaults.stream().findFirst();
        }

        /**
         * Returns whether a value satisfies this input's optional numeric range.
         *
         * @param value candidate canonical value
         * @return {@code true} when no range exists or the NUMBER lies inside it
         */
        public boolean withinRange(final RailixValue value) {
            return range.isEmpty() || within(value, range);
        }
    }

    /**
     * One guided workflow-context path selected by the project author.
     *
     * @param access allowed runtime path operations
     * @param defaults zero or one default path
     * @param required whether the project must select a path
     */
    public record PathInput(
            PathAccess access,
            List<RailixValue.ArrayValue> defaults,
            boolean required
    ) implements Input {
        /** Validates direct canonical construction; prefer {@link Input#path(PathAccess)}. */
        public PathInput {
            if (access == null) {
                throw new IllegalArgumentException("Path input access cannot be Java null.");
            }
            if (defaults == null || defaults.size() > 1) {
                throw new IllegalArgumentException("Path input must declare zero or one default.");
            }
            defaults = List.copyOf(defaults);
            defaults.forEach(value -> validatePath(value, access.writable(), "Path input default"));
            if (required && !defaults.isEmpty()) {
                required = false;
            }
        }

        /**
         * Returns a copy with one string-only default path.
         *
         * @param segments ordered fields beginning with {@code context}
         * @return input with the supplied default path
         */
        public PathInput defaultPath(final String... segments) {
            if (segments == null) {
                throw new IllegalArgumentException("Path input default cannot be Java null.");
            }
            return defaultValue(RailixValue.array(java.util.Arrays.stream(segments)
                    .<RailixValue>map(RailixValue::string)
                    .toList()));
        }

        /**
         * Returns a copy with one canonical path that may also contain array indexes.
         *
         * @param value canonical path beginning with {@code context}
         * @return input with the supplied default path
         */
        public PathInput defaultValue(final RailixValue.ArrayValue value) {
            if (value == null) {
                throw new IllegalArgumentException("Path input default cannot be Java null.");
            }
            return new PathInput(access, List.of(value), false);
        }

        /**
         * Returns a copy that accepts an omitted project path.
         *
         * @return optional copy of this input
         */
        public PathInput optional() {
            return new PathInput(access, defaults, false);
        }

        /**
         * Returns the default path when declared.
         *
         * @return declared path, or empty when the input has none
         */
        public Optional<RailixValue.ArrayValue> defaultValue() {
            return defaults.stream().findFirst();
        }
    }

    /**
     * One explicit tagged choice whose selected option owns its conditional child inputs.
     *
     * @param options selectable alternatives
     * @param defaults zero or one default option name
     * @param required whether the project must select an option
     */
    public record OptionsInput(
            List<Option> options,
            List<String> defaults,
            boolean required
    ) implements Input {
        /** Validates direct canonical construction; prefer {@link Input#options(Option...)}. */
        public OptionsInput {
            if (options == null || options.isEmpty()) {
                throw new IllegalArgumentException("Options input must declare at least one option.");
            }
            options = List.copyOf(options);
            distinct(options.stream().map(Option::name).toList(), "Option names");
            if (defaults == null || defaults.size() > 1) {
                throw new IllegalArgumentException("Options input must declare zero or one default option.");
            }
            defaults = List.copyOf(defaults);
            final String defaultName = defaults.isEmpty()
                    ? ""
                    : requiredText(defaults.getFirst(), "Default option");
            if (!defaultName.isEmpty()
                    && options.stream().noneMatch(option -> option.name().equals(defaultName))) {
                throw new IllegalArgumentException("Options input default must name a declared option.");
            }
            if (required && !defaults.isEmpty()) {
                required = false;
            }
        }

        /**
         * Returns a copy whose named option is selected when the project omits this input.
         *
         * @param option declared option name
         * @return input with the supplied default selection
         */
        public OptionsInput defaultOption(final String option) {
            return new OptionsInput(options, List.of(requiredText(option, "Default option")), false);
        }

        /**
         * Returns a copy that accepts no selected option.
         *
         * @return optional copy of this input
         */
        public OptionsInput optional() {
            return new OptionsInput(options, defaults, false);
        }

        /**
         * Returns the default option name when declared.
         *
         * @return declared option name, or empty when the input has none
         */
        public Optional<String> defaultOption() {
            return defaults.stream().findFirst();
        }
    }

    /**
     * One ordered authored list of tagged value sources with per-candidate conditions.
     *
     * <p>The input itself may resolve no value. An ordinary Step declares that behavior explicitly,
     * for example by binding a later {@link StepsInput} through
     * {@link ValueSource#onMissing(String)}.</p>
     *
     * <p>A project candidate stores {@code option}, owned {@code inputs}, and {@code when}. The
     * presence-only form is {@code "when":{"transforms":[],"all":[]}}. The canonical conditional
     * form is:</p>
     * <pre>{@code
     * "when": {
     *   "transforms": [{"use":"list.size","inputs":{}}],
     *   "all": [
     *     [{"use":"number.greater-than","inputs":{"than":1}}],
     *     [{"use":"number.less-than","inputs":{"than":5}}]
     *   ]
     * }
     * }</pre>
     * <p>Transforms run once in order. Every non-empty {@code all} program starts independently with
     * that transformed value and must end in BOOLEAN. Evaluation stops at the first false program.
     * The selected candidate still supplies its original source value.</p>
     *
     * @param options available candidate source alternatives
     * @param defaults zero or one default candidate option name
     * @param authoredOutcomes whether each project candidate owns one stable workflow outcome
     */
    public record CandidatesInput(
            List<Option> options,
            List<String> defaults,
            boolean authoredOutcomes
    ) implements Input {
        /** Preserves the ordinary candidate contract for direct construction. */
        public CandidatesInput(final List<Option> options, final List<String> defaults) {
            this(options, defaults, false);
        }

        /** Validates direct canonical construction; prefer {@link Input#candidates(Option...)}. */
        public CandidatesInput {
            if (options == null || options.isEmpty()) {
                throw new IllegalArgumentException("Candidates input must declare at least one option.");
            }
            options = List.copyOf(options);
            distinct(options.stream().map(Option::name).toList(), "Candidate option names");
            if (options.stream().anyMatch(option -> option.valueSource().isEmpty())) {
                throw new IllegalArgumentException("Every candidate option must declare one value source.");
            }
            if (defaults == null || defaults.size() > 1) {
                throw new IllegalArgumentException("Candidates input must declare zero or one default candidate.");
            }
            defaults = List.copyOf(defaults);
            final String defaultName = defaults.isEmpty()
                    ? ""
                    : requiredText(defaults.getFirst(), "Default candidate");
            if (!defaultName.isEmpty()
                    && options.stream().noneMatch(option -> option.name().equals(defaultName))) {
                throw new IllegalArgumentException("Candidates input default must name a declared option.");
            }
            if (authoredOutcomes && !defaults.isEmpty()) {
                throw new IllegalArgumentException(
                        "Authored-outcome candidates cannot declare a default candidate."
                );
            }
        }

        /**
         * Uses one candidate with no authored child overrides when the project omits this input.
         *
         * @param option declared candidate option name
         * @return input with the supplied single default candidate
         */
        public CandidatesInput defaultCandidate(final String option) {
            return new CandidatesInput(options, List.of(requiredText(option, "Default candidate")), authoredOutcomes);
        }

        /**
         * Returns the default candidate option when declared.
         *
         * @return declared candidate option, or empty when omission resolves no candidate
         */
        public Optional<String> defaultCandidate() {
            return defaults.stream().findFirst();
        }

        /**
         * Makes every project-authored candidate declare one stable workflow outcome.
         *
         * <p>The enclosing ordinary Step may declare this capability on one top-level input. The
         * candidate list must remain explicit because Railix cannot invent stable route identities
         * for a default candidate.</p>
         *
         * @return candidate input whose authored items become workflow routes
         */
        public CandidatesInput withAuthoredOutcomes() {
            return new CandidatesInput(options, defaults, true);
        }
    }

    /**
     * One Boolean-producing input whose project value is an ordered OR of ordered AND matchers.
     *
     * <p>Every matcher uses the same candidate condition structure documented by
     * {@link CandidatesInput}. A missing source is false. A present source without a condition is
     * true. Runtime short-circuits false matchers within a group and true groups within the outer
     * list while propagating nested Step failures and cancellation unchanged.</p>
     *
     * @param options available matcher source alternatives
     */
    public record MatcherGroupsInput(List<Option> options) implements Input {
        /** Validates direct canonical construction; prefer {@link Input#matcherGroups(Option...)}. */
        public MatcherGroupsInput {
            if (options == null || options.isEmpty()) {
                throw new IllegalArgumentException("Matcher groups input must declare at least one option.");
            }
            options = List.copyOf(options);
            distinct(options.stream().map(Option::name).toList(), "Matcher group option names");
            if (options.stream().anyMatch(option -> option.valueSource().isEmpty())) {
                throw new IllegalArgumentException("Every matcher group option must declare one value source.");
            }
        }
    }

    /** Scope of one option value reference. */
    public enum ReferenceScope {
        /** A readable input declared before the enclosing option-bearing input. */
        PARENT,
        /** A readable child input declared by the selected option itself. */
        OWNED
    }

    /**
     * One unambiguous reference used to resolve an option into a value.
     *
     * @param scope whether the input belongs to the enclosing Step or option
     * @param input referenced input name
     */
    public record InputReference(ReferenceScope scope, String input) {
        /** Validates the reference scope and input name. */
        public InputReference {
            if (scope == null) {
                throw new IllegalArgumentException("Option value source scope cannot be Java null.");
            }
            input = requiredText(input, "Option value source");
        }
    }

    /**
     * The value consumed by a nested-Step pipeline.
     *
     * <p>A canonical JSON null is present. An absent input skips the pipeline and continues the
     * enclosing Step unless {@link #onMissing(String)} declares an explicit outcome.</p>
     *
     * @param input primary earlier input name
     * @param missingOutcomes zero or one outcome used when the input is absent
     */
    public record ValueSource(
            String input,
            List<String> missingOutcomes
    ) {
        /** Validates direct canonical construction; prefer {@link #from(String)}. */
        public ValueSource {
            input = requiredText(input, "Nested Step value source");
            if (missingOutcomes == null || missingOutcomes.size() > 1) {
                throw new IllegalArgumentException(
                        "Nested Step value source must declare zero or one missing outcome."
                );
            }
            missingOutcomes = missingOutcomes.stream()
                    .map(outcome -> requiredText(outcome, "Nested Step missing outcome"))
                    .toList();
        }

        /**
         * Starts a source relationship with one primary earlier input.
         *
         * @param input readable input declared before the STEPS input
         * @return value source that continues without running nested Steps when absent
         */
        public static ValueSource from(final String input) {
            return new ValueSource(input, List.of());
        }

        /**
         * Returns this outcome without executing nested Steps when no value is available.
         *
         * @param outcome explicit enclosing Step outcome
         * @return value source with the supplied missing-value outcome
         */
        public ValueSource onMissing(final String outcome) {
            return new ValueSource(input, List.of(outcome));
        }

        /**
         * Returns the explicit absence outcome when the declared inputs can be absent.
         *
         * @return missing-value outcome, or empty when none is declared
         */
        public Optional<String> missingOutcome() {
            return missingOutcomes.stream().findFirst();
        }
    }

    /**
     * One ordered list of unary nested Steps bound to one explicit value source.
     *
     * @param valueSource one explicit value relationship
     * @param propagatesOutcomes whether nested non-primary outcomes become enclosing outcomes
     */
    public record StepsInput(
            ValueSource valueSource,
            boolean propagatesOutcomes
    ) implements Input {
        /** Validates direct canonical construction; prefer {@link Input#steps(ValueSource)}. */
        public StepsInput {
            if (valueSource == null) {
                throw new IllegalArgumentException("Nested Step value source cannot be Java null.");
            }
        }

        /**
         * Propagates non-primary outcomes declared by configured nested Steps.
         *
         * @return input whose nested non-primary outcomes become enclosing outcomes
         */
        public StepsInput propagateOutcomes() {
            return new StepsInput(valueSource, true);
        }

    }

    /**
     * One named child set and its optional, explicitly scoped resolved value.
     *
     * @param name stable option tag stored in the project
     * @param inputs child inputs visible only while selected
     * @param valueSources zero or one scoped source during direct construction
     */
    public record Option(String name, List<Field> inputs, List<InputReference> valueSources) {
        /** Validates direct canonical construction; prefer {@link Input#option(String)}. */
        public Option {
            name = requiredText(name, "Option name");
            inputs = immutable(inputs);
            distinct(inputs.stream().map(Field::name).toList(), "Option input names");
            if (valueSources == null || valueSources.size() > 1) {
                throw new IllegalArgumentException("Option must declare zero or one value source.");
            }
            valueSources = List.copyOf(valueSources);
            if (valueSources.stream().anyMatch(source -> source == null)) {
                throw new IllegalArgumentException("Option value source cannot be Java null.");
            }
        }

        /**
         * Adds one child input rendered only while this option is selected.
         *
         * @param name child input name unique within this option
         * @param input generic child input declaration
         * @return option containing the appended child input
         */
        public Option input(final String name, final Input input) {
            final List<Field> fields = new ArrayList<>(inputs);
            fields.add(new Field(name, input));
            return new Option(this.name, fields, valueSources);
        }

        /**
         * Resolves this option from one readable input declared before its OPTIONS parent.
         *
         * @param input earlier parent input name
         * @return option resolved from that parent input
         */
        public Option fromParent(final String input) {
            return new Option(name, inputs, List.of(new InputReference(ReferenceScope.PARENT, input)));
        }

        /**
         * Resolves this option from one readable child input owned by this option.
         *
         * @param input child input name owned by this option
         * @return option resolved from that child input
         */
        public Option fromOwned(final String input) {
            return new Option(name, inputs, List.of(new InputReference(ReferenceScope.OWNED, input)));
        }

        /**
         * Returns the optional, scoped input that becomes the enclosing OPTIONS value.
         *
         * @return scoped source, or empty for a tag-only option
         */
        public Optional<InputReference> valueSource() {
            return valueSources.stream().findFirst();
        }
    }

    /**
     * One named authored input in declaration order.
     *
     * @param name project field name
     * @param input generic input declaration
     */
    public record Field(String name, Input input) {
        /** Validates a non-blank name and non-null input declaration. */
        public Field {
            name = requiredText(name, "Input name");
            if (input == null) {
                throw new IllegalArgumentException("Input type cannot be Java null.");
            }
        }
    }

    /**
     * One unique external Trigger source.
     *
     * @param name source identity implemented by the runtime launcher
     * @param responses source slots mapped to Trigger result names
     */
    public record Source(String name, Map<String, String> responses) {
        /** Validates the source and its response-slot mappings. */
        public Source {
            name = requiredText(name, "Trigger source");
            if (responses == null) {
                throw new IllegalArgumentException("Trigger source responses cannot be Java null.");
            }
            responses = Collections.unmodifiableMap(new LinkedHashMap<>(responses));
            responses.forEach((slot, result) -> {
                requiredText(slot, "Trigger response slot");
                requiredText(result, "Trigger response result");
            });
        }
    }

    record ImplementationAddress(String sourceName, String classEntry) {
        ImplementationAddress {
            sourceName = implementationName(sourceName);
            classEntry = implementationEntry(classEntry);
            final String binaryName = classEntry.substring(0, classEntry.length() - ".class".length())
                    .replace('/', '.');
            if (!sourceName.equals(binaryName) && !sourceName.equals(binaryName.replace('$', '.'))) {
                throw new IllegalArgumentException(
                        "Step implementation class and JAR entry must identify the same Java class."
                );
            }
        }
    }

    private final String id;
    private final String version;
    private final String displayName;
    private final List<String> searchTerms;
    private final Kind kind;
    private final List<Port> receives;
    private final List<Port> returns;
    private final List<Field> inputs;
    private final List<String> outcomes;
    private final List<Result> results;
    private final List<Example> examples;
    private final String exampleTarget;
    private final int maximumInstances;
    private final Source source;
    private final ImplementationAddress implementation;

    private StepDefinition(
            final Builder builder,
            final ImplementationAddress implementation
    ) {
        id = requiredText(builder.id, "Step id");
        version = requiredText(builder.version, "Step version");
        displayName = builder.displayName == null ? displayName(id) : builder.displayName;
        searchTerms = immutable(builder.searchTerms);
        kind = builder.kind;
        receives = immutable(builder.receives);
        returns = immutable(builder.returns);
        inputs = immutable(builder.inputs);
        outcomes = outcomes(builder);
        results = immutable(builder.results);
        examples = immutable(builder.examples);
        exampleTarget = builder.exampleTarget;
        maximumInstances = builder.maximumInstances;
        source = builder.sourceName == null
                ? null
                : new Source(builder.sourceName, builder.responses);
        this.implementation = implementation;
        distinct(receives.stream().map(Port::name).toList(), "Received value names");
        distinct(returns.stream().map(Port::name).toList(), "Returned value names");
        distinct(inputs.stream().map(Field::name).toList(), "Input names");
        distinct(results.stream().map(Result::name).toList(), "Result names");
        distinct(searchTerms, "Step search terms");
        if (source != null && kind != Kind.TRIGGER) {
            throw new IllegalArgumentException("Only Trigger Steps may declare an external source.");
        }
        if (!examples.isEmpty() && kind != Kind.TRIGGER) {
            throw new IllegalArgumentException("Only Trigger Steps may declare example templates.");
        }
        if (exampleTarget != null && kind != Kind.TRIGGER) {
            throw new IllegalArgumentException("Only Trigger Steps may declare an example target.");
        }
        if (!examples.isEmpty() && exampleTarget == null) {
            throw new IllegalArgumentException("Trigger example templates require an example target.");
        }
        if (exampleTarget != null) {
            final Input target = inputs.stream()
                    .filter(field -> field.name().equals(exampleTarget))
                    .map(Field::input)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Trigger example target must reference a declared PATH input: " + exampleTarget + "."
                    ));
            if (!(target instanceof PathInput path) || !path.access().writable()) {
                throw new IllegalArgumentException(
                        "Trigger example target must reference a writable PATH input: " + exampleTarget + "."
                );
            }
        }
        distinct(examples.stream().map(Example::name).toList(), "Trigger example template names");
        if (source != null) {
            source.responses().forEach((slot, result) -> {
                if (results.stream().noneMatch(candidate -> candidate.name().equals(result))) {
                    throw new IllegalArgumentException(
                            "Trigger response " + slot + " must reference a declared result: " + result + "."
                    );
                }
            });
        }
        validateReferences(inputs);
        validateAuthoredOutcomes(inputs, kind);
    }

    /**
     * Starts one named, versioned Step definition.
     *
     * @param id stable catalog identifier stored by projects
     * @param version Step developer's contract version
     * @return fluent definition builder
     */
    public static Builder named(final String id, final String version) {
        return new Builder(id, version);
    }

    /**
     * Returns the stable catalog identifier used by projects.
     *
     * @return catalog identifier
     */
    public String id() {
        return id;
    }

    /**
     * Returns the Step developer's contract version.
     *
     * @return contract version
     */
    public String version() {
        return version;
    }

    /**
     * Returns the human-readable Creator label.
     *
     * @return Creator display label
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns optional Step-developer aliases used only for Creator catalog search.
     *
     * @return immutable search aliases in declaration order
     */
    public List<String> searchTerms() {
        return searchTerms;
    }

    /**
     * Returns the lifecycle and rendering role.
     *
     * @return explicit Step kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Runtime values supplied by an enclosing Step program or external Trigger source.
     *
     * @return immutable received-port declarations in order
     */
    public List<Port> receives() {
        return receives;
    }

    /**
     * Runtime values returned to an enclosing Step program.
     *
     * @return immutable returned-port declarations in order
     */
    public List<Port> returns() {
        return returns;
    }

    /**
     * Project-authored inputs rendered and compiled from the generic input algebra.
     *
     * @return immutable authored-input declarations in order
     */
    public List<Field> inputs() {
        return inputs;
    }

    /**
     * Returns all normal and explicit branch outcomes in stable order.
     *
     * @return immutable outcomes with the primary outcome first
     */
    public List<String> outcomes() {
        return outcomes;
    }

    /**
     * Normal completion outcome; additional outcomes follow it in {@link #outcomes()}.
     *
     * @return primary outcome name
     */
    public String primaryOutcome() {
        return outcomes.getFirst();
    }

    /**
     * Returns values a Trigger asks Railix to collect when its flow finishes.
     *
     * @return immutable Trigger result declarations
     */
    public List<Result> results() {
        return results;
    }

    /**
     * Initial editable examples Creator adds with a new Trigger node.
     *
     * @return immutable Trigger example templates
     */
    public List<Example> examples() {
        return examples;
    }

    /**
     * Returns the writable PATH input where Creator places each example payload.
     *
     * @return declared Trigger example target, or empty when no example target exists
     */
    public Optional<String> exampleTarget() {
        return Optional.ofNullable(exampleTarget);
    }

    /**
     * Returns the maximum allowed project nodes using this definition.
     *
     * @return positive maximum instance count
     */
    public int maximumInstances() {
        return maximumInstances;
    }

    /**
     * Returns the external source declaration for a Trigger.
     *
     * @return Trigger source, or empty for non-Trigger and unbound definitions
     */
    public Optional<Source> source() {
        return Optional.ofNullable(source);
    }

    /** Returns whether generated applications must construct executable behavior for this Step. */
    public boolean executable() {
        return implementation != null;
    }

    Optional<ImplementationAddress> implementationAddress() {
        return Optional.ofNullable(implementation);
    }

    /**
     * One runtime value boundary with optional explicit recursive constraints.
     *
     * @param name handler-visible value name
     * @param shape accepted outer value shape
     * @param refinement optional recursive canonical constraints
     */
    public record Port(String name, ValueShape shape, ValueRefinement refinement) {
        /**
         * Creates the normal outer-shape-only port.
         *
         * @param name handler-visible value name
         * @param shape accepted outer value shape
         */
        public Port(final String name, final ValueShape shape) {
            this(name, shape, ValueRefinement.none());
        }

        /** Validates direct construction and refinement compatibility. */
        public Port {
            name = requiredText(name, "Port name");
            if (shape == null) {
                throw new IllegalArgumentException("Port shape cannot be Java null.");
            }
            if (refinement == null) {
                throw new IllegalArgumentException("Port refinement cannot be Java null.");
            }
            final boolean container = shape == ValueShape.ANY
                    || shape == ValueShape.ARRAY
                    || shape == ValueShape.OBJECT;
            if (refinement.maxDepth() > 0 && !container) {
                throw new IllegalArgumentException("Port maximum depth requires any, array, or object shape.");
            }
            if (refinement.maxJsonBytes() > 0 && container && refinement.maxDepth() == 0) {
                throw new IllegalArgumentException(
                        "Container JSON byte refinement requires an explicit maximum depth."
                );
            }
        }
    }

    /**
     * One value requested by a Trigger when its stream item finishes.
     *
     * @param name workflow-context result field
     * @param shape accepted result shape
     * @param defaults zero or one fallback result
     */
    public record Result(String name, ValueShape shape, List<RailixValue> defaults) {
        /** Validates the result shape and optional canonical default. */
        public Result {
            name = requiredText(name, "Result name");
            if (shape == null) {
                throw new IllegalArgumentException("Result shape cannot be Java null.");
            }
            defaults = StepDefinition.defaults(defaults, shape, "Result");
        }

        /**
         * Returns the fallback result used when the completed flow did not set a value.
         *
         * @return default result, or empty for a required result
         */
        public Optional<RailixValue> defaultValue() {
            return defaults.stream().findFirst();
        }

        /**
         * Returns whether the completed flow must provide this result.
         *
         * @return {@code true} when no default result exists
         */
        public boolean required() {
            return defaults.isEmpty();
        }
    }

    /**
     * One Trigger-provided Creator example template.
     *
     * @param name editable example name
     * @param payload JSON value placed at the Trigger's configured example target
     * @param context optional surrounding workflow context excluding reserved runtime values;
     *                an empty object means no authored context
     */
    public record Example(String name, RailixValue payload, RailixValue.ObjectValue context) {
        /** Validates one editable payload and its optional surrounding context. */
        public Example {
            name = requiredText(name, "Trigger example template name");
            if (payload == null || context == null) {
                throw new IllegalArgumentException("Trigger example template values cannot be Java null.");
            }
            if (context.values().containsKey("runtime")) {
                throw new IllegalArgumentException("Trigger example template cannot claim context.runtime.");
            }
        }
    }

    /** Small fluent Step authoring surface. */
    public static final class Builder {
        private final String id;
        private final String version;
        private String displayName;
        private final List<String> searchTerms = new ArrayList<>();
        private Kind kind = Kind.STEP;
        private final List<Port> receives = new ArrayList<>();
        private final List<Port> returns = new ArrayList<>();
        private final List<Field> inputs = new ArrayList<>();
        private final List<String> outcomes = new ArrayList<>();
        private final List<Result> results = new ArrayList<>();
        private final List<Example> examples = new ArrayList<>();
        private final Map<String, String> responses = new LinkedHashMap<>();
        private int maximumInstances = Integer.MAX_VALUE;
        private String primaryOutcome;
        private String sourceName;
        private String exampleTarget;

        private Builder(final String id, final String version) {
            this.id = id;
            this.version = version;
        }

        /**
         * Sets the lifecycle and Creator-rendering role; ordinary STEP is the default.
         *
         * @param kind explicit Step kind
         * @return this builder
         */
        public Builder kind(final Kind kind) {
            if (kind == null) {
                throw new IllegalArgumentException("Step kind cannot be Java null.");
            }
            this.kind = kind;
            return this;
        }

        /**
         * Overrides the id-derived Creator label only when a Step needs different spelling.
         *
         * @param displayName non-blank Creator label
         * @return this builder
         */
        public Builder displayName(final String displayName) {
            this.displayName = requiredText(displayName, "Step display name");
            return this;
        }

        /**
         * Adds optional Creator search aliases without changing the displayed Step name.
         *
         * <p>Use short terms that developers naturally type, for example
         * {@code searchTerms("gte", "ge")} for a greater-or-equal Step.</p>
         *
         * @param terms non-blank search aliases
         * @return this builder
         */
        public Builder searchTerms(final String... terms) {
            if (terms == null) {
                throw new IllegalArgumentException("Step search terms cannot be Java null.");
            }
            for (final String term : terms) {
                searchTerms.add(requiredText(term, "Step search term"));
            }
            return this;
        }

        /**
         * Adds one runtime value received from an enclosing pipeline or Trigger source.
         *
         * @param name handler-visible value name
         * @param shape accepted outer value shape
         * @return this builder
         */
        public Builder receive(final String name, final ValueShape shape) {
            return receive(name, shape, ValueRefinement.none());
        }

        /**
         * Adds one runtime input with explicit recursive constraints.
         *
         * @param name handler-visible value name
         * @param shape accepted outer value shape
         * @param refinement recursive canonical constraints
         * @return this builder
         */
        public Builder receive(
                final String name,
                final ValueShape shape,
                final ValueRefinement refinement
        ) {
            receives.add(new Port(name, shape, refinement));
            return this;
        }

        /**
         * Adds one runtime value returned to an enclosing pipeline.
         *
         * @param name handler-visible value name
         * @param shape returned outer value shape
         * @return this builder
         */
        public Builder returns(final String name, final ValueShape shape) {
            return returns(name, shape, ValueRefinement.none());
        }

        /**
         * Adds one runtime output with explicit recursive constraints.
         *
         * @param name handler-visible value name
         * @param shape returned outer value shape
         * @param refinement recursive canonical constraints
         * @return this builder
         */
        public Builder returns(
                final String name,
                final ValueShape shape,
                final ValueRefinement refinement
        ) {
            returns.add(new Port(name, shape, refinement));
            return this;
        }

        /**
         * Adds one top-level project-authored field.
         *
         * <p>This differs from {@link Input#option(String)}: an option is one selectable alternative
         * inside an {@link OptionsInput}; this method names the OPTIONS input itself.</p>
         *
         * @param name top-level authored input name
         * @param input generic input declaration
         * @return this builder
         */
        public Builder input(final String name, final Input input) {
            inputs.add(new Field(name, input));
            return this;
        }

        /**
         * Declares the unique opaque external source that invokes this Trigger.
         *
         * @param name runtime source identity
         * @return this builder
         */
        public Builder source(final String name) {
            sourceName = requiredText(name, "Trigger source");
            return this;
        }

        /**
         * Maps one source-specific response slot to a declared Trigger result.
         *
         * @param slot source response slot
         * @param result declared Trigger result name
         * @return this builder
         */
        public Builder response(final String slot, final String result) {
            responses.put(requiredText(slot, "Trigger response slot"), requiredText(result, "Trigger response result"));
            return this;
        }

        /**
         * Replaces the kind's normal outcome only when this Step uses a different name.
         *
         * @param outcome non-blank primary outcome name
         * @return this builder
         */
        public Builder primaryOutcome(final String outcome) {
            primaryOutcome = requiredText(outcome, "Primary outcome");
            return this;
        }

        /**
         * Adds one explicit branch after the normal outcome.
         *
         * @param outcome non-blank branch outcome name
         * @return this builder
         */
        public Builder outcome(final String outcome) {
            outcomes.add(requiredText(outcome, "Outcome"));
            return this;
        }

        /**
         * Adds one Trigger result with a fallback used when the flow leaves it unset.
         *
         * @param name workflow-context result field
         * @param shape accepted result shape
         * @param defaultValue canonical fallback matching the shape
         * @return this builder
         */
        public Builder result(final String name, final ValueShape shape, final RailixValue defaultValue) {
            if (defaultValue == null) {
                throw new IllegalArgumentException("Result default cannot be Java null.");
            }
            results.add(new Result(name, shape, List.of(defaultValue)));
            return this;
        }

        /**
         * Adds one Trigger result that the completed flow must set.
         *
         * @param name workflow-context result field
         * @param shape accepted result shape
         * @return this builder
         */
        public Builder requiredResult(final String name, final ValueShape shape) {
            results.add(new Result(name, shape, List.of()));
            return this;
        }

        /**
         * Adds one initial editable example Creator copies into a new Trigger node.
         *
         * @param name editable example name
         * @param payload JSON value Creator writes to the configured example target
         * @return this builder
         */
        public Builder example(final String name, final RailixValue payload) {
            return example(name, payload, RailixValue.object(Map.of()));
        }

        /**
         * Adds one initial editable example with optional surrounding workflow context.
         *
         * @param name editable example name
         * @param payload JSON value Creator writes to the configured example target
         * @param context surrounding workflow context, or an empty object when none is needed
         * @return this builder
         */
        public Builder example(
                final String name,
                final RailixValue payload,
                final RailixValue.ObjectValue context
        ) {
            examples.add(new Example(name, payload, context));
            return this;
        }

        /**
         * Names the writable PATH input that receives every Creator example payload.
         *
         * @param input declared writable PATH input name
         * @return this builder
         */
        public Builder exampleTarget(final String input) {
            exampleTarget = requiredText(input, "Trigger example target");
            return this;
        }

        /**
         * Restricts how many nodes may use this definition in one project.
         *
         * @param maximumInstances positive project-wide maximum
         * @return this builder
         */
        public Builder maximumInstances(final int maximumInstances) {
            if (maximumInstances < 1) {
                throw new IllegalArgumentException("Maximum Step instances must be positive.");
            }
            this.maximumInstances = maximumInstances;
            return this;
        }

        /**
         * Completes a structural App definition without executable behavior.
         *
         * @return immutable structural Step definition
         */
        public StepDefinition define() {
            return new StepDefinition(this, null);
        }

        /**
         * Completes an executable Trigger or ordinary Step definition.
         *
         * <p>The implementation must be a named class with an accessible no-argument constructor
         * so the compiler can emit a concrete constructor call without reflection. Railix stores
         * only the immutable class address; it never constructs or retains a handler here.</p>
         *
         * @param implementation stateless or thread-safe executable behavior owned by a named Java class
         * @return immutable executable Step definition
         */
        public StepDefinition run(final Class<? extends StepHandler> implementation) {
            if (implementation == null) {
                throw new IllegalArgumentException("Step implementation cannot be Java null.");
            }
            final String canonicalName = implementation.getCanonicalName();
            if (canonicalName == null) {
                throw new IllegalArgumentException(
                        "Step implementation must be a named Java class so generated applications can call it."
                );
            }
            return new StepDefinition(
                    this,
                    new ImplementationAddress(
                            canonicalName,
                            implementation.getName().replace('.', '/') + ".class"
                    )
            );
        }

        /** Binds an implementation address while decoding a verified bundle manifest. */
        StepDefinition implementedBy(final String className, final String classEntry) {
            return new StepDefinition(
                    this,
                    new ImplementationAddress(className, classEntry)
            );
        }
    }

    private static String implementationName(final String className) {
        final String value = requiredText(className, "Step implementation class");
        final String[] parts = value.split("\\.", -1);
        if (parts.length < 2) {
            throw new IllegalArgumentException("Step implementation must be a canonical Java class name.");
        }
        for (final String part : parts) {
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.charAt(0))) {
                throw new IllegalArgumentException("Step implementation must be a canonical Java class name.");
            }
            for (int index = 1; index < part.length(); index++) {
                if (!Character.isJavaIdentifierPart(part.charAt(index))) {
                    throw new IllegalArgumentException("Step implementation must be a canonical Java class name.");
                }
            }
        }
        return value;
    }

    private static String implementationEntry(final String classEntry) {
        final String value = requiredText(classEntry, "Step implementation entry");
        if (!value.endsWith(".class") || value.startsWith("/") || value.contains("\\")
                || java.util.Arrays.stream(value.split("/", -1)).anyMatch(part -> part.isEmpty()
                || ".".equals(part) || "..".equals(part))) {
            throw new IllegalArgumentException("Step implementation entry must be a safe JAR class entry.");
        }
        return value;
    }

    private static void validateReferences(final List<Field> fields) {
        final Map<String, Input> declared = new LinkedHashMap<>();
        for (final Field field : fields) {
            if (field.input() instanceof OptionsInput options) {
                validateOptionReferences(options.options(), declared);
            } else if (field.input() instanceof CandidatesInput candidates) {
                validateOptionReferences(candidates.options(), declared);
            } else if (field.input() instanceof MatcherGroupsInput matcherGroups) {
                validateOptionReferences(matcherGroups.options(), declared);
            }
            if (field.input() instanceof StepsInput steps) {
                final ValueSource source = steps.valueSource();
                validateNestedReference(declared, source.input(), "source");
            }
            declared.put(field.name(), field.input());
        }
    }

    private static void validateAuthoredOutcomes(final List<Field> fields, final Kind kind) {
        final long direct = fields.stream()
                .map(Field::input)
                .filter(CandidatesInput.class::isInstance)
                .map(CandidatesInput.class::cast)
                .filter(CandidatesInput::authoredOutcomes)
                .count();
        if (direct > 1) {
            throw new IllegalArgumentException("A Step may declare only one authored-outcome candidates input.");
        }
        if (fields.stream().map(Field::input).anyMatch(StepDefinition::hasNestedAuthoredOutcomes)) {
            throw new IllegalArgumentException("Authored-outcome candidates must be a top-level Step input.");
        }
        if (direct == 1 && kind != Kind.STEP) {
            throw new IllegalArgumentException("Only ordinary Steps may declare authored-outcome candidates.");
        }
    }

    private static boolean hasNestedAuthoredOutcomes(final Input input) {
        final List<Option> options = switch (input) {
            case OptionsInput value -> value.options();
            case CandidatesInput value -> value.options();
            case MatcherGroupsInput value -> value.options();
            default -> List.of();
        };
        return options.stream().flatMap(option -> option.inputs().stream()).anyMatch(field ->
                field.input() instanceof CandidatesInput candidates && candidates.authoredOutcomes()
                        || hasNestedAuthoredOutcomes(field.input())
        );
    }

    private static void validateOptionReferences(
            final List<Option> options,
            final Map<String, Input> declared
    ) {
        for (final Option option : options) {
            validateReferences(option.inputs());
            option.valueSource().ifPresent(source -> {
                final Input referenced = source.scope() == ReferenceScope.PARENT
                        ? declared.get(source.input())
                        : option.inputs().stream()
                        .filter(input -> input.name().equals(source.input()))
                        .map(Field::input)
                        .findFirst()
                        .orElse(null);
                if (referenced == null || !producesValue(referenced)) {
                    throw new IllegalArgumentException(
                            source.scope() == ReferenceScope.PARENT
                                    ? "Option parent source must reference a readable earlier input: "
                                    + source.input() + "."
                                    : "Option-owned source must reference a readable input owned by option "
                                    + option.name() + ": " + source.input() + "."
                    );
                }
            });
        }
    }

    private static void validateNestedReference(
            final Map<String, Input> declared,
            final String name,
            final String relation
    ) {
        final Input referenced = declared.get(name);
        if (referenced == null || !producesValue(referenced)) {
            throw new IllegalArgumentException(
                    "Nested Step " + relation + " must reference a readable earlier input: " + name + "."
            );
        }
    }

    private static boolean producesValue(final Input input) {
        return !(input instanceof StepsInput)
                && (!(input instanceof PathInput path) || path.access().readable())
                && (!(input instanceof OptionsInput options)
                || options.options().stream().allMatch(option -> option.valueSource().isPresent()));
    }

    private static void validatePath(
            final RailixValue.ArrayValue path,
            final boolean writable,
            final String label
    ) {
        if (path.values().size() < 2
                || !(path.values().getFirst() instanceof RailixValue.StringValue root)
                || !"context".equals(root.value())) {
            throw new IllegalArgumentException(label + " must start below context.");
        }
        if (path.values().size() > RailixData.DEFAULT_MAX_DEPTH) {
            throw new IllegalArgumentException(
                    label + " must not exceed " + RailixData.DEFAULT_MAX_DEPTH + " elements."
            );
        }
        for (final RailixValue segment : path.values()) {
            final boolean field = segment instanceof RailixValue.StringValue string && !string.value().isBlank();
            final boolean index = segment instanceof RailixValue.NumberValue number
                    && number.value().scale() <= 0
                    && number.value().signum() >= 0;
            if (!field && !index) {
                throw new IllegalArgumentException(label + " elements must be fields or non-negative indexes.");
            }
        }
        if (writable
                && path.values().get(1) instanceof RailixValue.StringValue field
                && "runtime".equals(field.value())) {
            throw new IllegalArgumentException(label + " must use writable context.");
        }
    }

    private static <T> List<T> immutable(final List<T> values) {
        if (values == null) {
            throw new IllegalArgumentException("Definition values cannot be Java null.");
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static void distinct(final List<String> values, final String label) {
        if (new LinkedHashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(label + " must be distinct.");
        }
    }

    private static List<String> outcomes(final Builder builder) {
        final List<String> values = new ArrayList<>(builder.outcomes.size() + 1);
        values.add(builder.primaryOutcome == null ? defaultOutcome(builder.kind) : builder.primaryOutcome);
        values.addAll(builder.outcomes);
        inputOutcomes(builder.inputs).forEach(outcome -> {
            if (values.contains(outcome)) {
                throw new IllegalArgumentException(
                        "Nested Step missing outcomes must differ from enclosing Step outcomes."
                );
            }
            values.add(outcome);
        });
        distinct(values, "Step outcomes");
        return immutable(values);
    }

    private static List<String> inputOutcomes(final List<Field> fields) {
        final List<String> outcomes = new ArrayList<>();
        fields.forEach(field -> {
            if (field.input() instanceof StepsInput steps) {
                steps.valueSource().missingOutcome().ifPresent(outcomes::add);
            } else if (field.input() instanceof OptionsInput options) {
                options.options().forEach(option -> outcomes.addAll(inputOutcomes(option.inputs())));
            } else if (field.input() instanceof CandidatesInput candidates) {
                candidates.options().forEach(option -> outcomes.addAll(inputOutcomes(option.inputs())));
            } else if (field.input() instanceof MatcherGroupsInput matcherGroups) {
                matcherGroups.options().forEach(option -> outcomes.addAll(inputOutcomes(option.inputs())));
            }
        });
        return outcomes;
    }

    private static String defaultOutcome(final Kind kind) {
        return switch (kind) {
            case APP -> "start";
            case TRIGGER, STEP -> "next";
        };
    }

    private static String displayName(final String id) {
        final String value = id.substring(id.lastIndexOf('.') + 1);
        final StringBuilder result = new StringBuilder(value.length());
        for (final String word : value.split("-")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.isEmpty() ? id : result.toString();
    }

    private static String requiredText(final String value, final String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must be a non-blank string.");
        }
        return value;
    }

    private static List<RailixValue> defaults(
            final List<RailixValue> values,
            final ValueShape shape,
            final String label
    ) {
        if (values == null || values.size() > 1) {
            throw new IllegalArgumentException(label + " must declare zero or one default.");
        }
        final List<RailixValue> copy = List.copyOf(values);
        if (!copy.isEmpty() && !shape.accepts(copy.getFirst())) {
            throw new IllegalArgumentException(
                    label + " default must match " + shape.name().toLowerCase(Locale.ROOT) + "."
            );
        }
        return copy;
    }

    private static List<RailixValue> range(final List<RailixValue> values, final ValueShape shape) {
        if (values == null || (!values.isEmpty() && values.size() != 2)) {
            throw new IllegalArgumentException("JSON input range must declare zero or two values.");
        }
        final List<RailixValue> copy = List.copyOf(values);
        if (copy.isEmpty()) {
            return copy;
        }
        if (shape != ValueShape.NUMBER
                || !(copy.getFirst() instanceof RailixValue.NumberValue minimum)
                || !(copy.getLast() instanceof RailixValue.NumberValue maximum)) {
            throw new IllegalArgumentException("JSON input range requires number values.");
        }
        if (minimum.value().compareTo(maximum.value()) > 0) {
            throw new IllegalArgumentException("JSON input range minimum cannot exceed maximum.");
        }
        return copy;
    }

    private static boolean within(final RailixValue value, final List<RailixValue> range) {
        if (!(value instanceof RailixValue.NumberValue number)) {
            return false;
        }
        final var minimum = (RailixValue.NumberValue) range.getFirst();
        final var maximum = (RailixValue.NumberValue) range.getLast();
        return number.value().compareTo(minimum.value()) >= 0
                && number.value().compareTo(maximum.value()) <= 0;
    }
}
