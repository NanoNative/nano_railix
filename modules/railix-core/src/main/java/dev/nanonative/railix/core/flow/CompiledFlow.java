package dev.nanonative.railix.core.flow;

import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An immutable executable plan that can only be produced by {@link FlowCompiler}. */
public final class CompiledFlow {
    private final String id;
    private final List<Trigger> triggers;
    private final String entry;
    private final Map<String, ValueShape> inputs;
    private final Map<String, ValueShape> outputs;
    private final Map<String, Node> nodes;
    private final Map<String, Binding> outputBindings;

    CompiledFlow(
            final String id,
            final List<Trigger> triggers,
            final String entry,
            final Map<String, ValueShape> inputs,
            final Map<String, ValueShape> outputs,
            final Map<String, Node> nodes,
            final Map<String, Binding> outputBindings
    ) {
        this.id = id;
        this.triggers = List.copyOf(triggers);
        this.entry = entry;
        this.inputs = immutable(inputs);
        this.outputs = immutable(outputs);
        this.nodes = immutable(nodes);
        this.outputBindings = immutable(outputBindings);
    }

    public String id() {
        return id;
    }

    /** Returns compiler-validated application ingress declarations in authored order. */
    public List<Trigger> triggers() {
        return triggers;
    }

    public Map<String, ValueShape> inputs() {
        return inputs;
    }

    public Map<String, ValueShape> outputs() {
        return outputs;
    }

    /** Runs one object event and returns the canonical trigger-independent reply. */
    public RunResult run(final RailixValue.ObjectValue event) {
        return FlowExecutor.run(this, event);
    }

    /**
     * Runs an explicitly declared HTTP Step event from that Step through flow completion.
     *
     * @param stepId Step selected by an HTTP trigger
     * @param event exact object matching the selected Step inputs
     * @return the canonical flow result or deterministic event rejection
     */
    public RunResult runStepEvent(final String stepId, final RailixValue.ObjectValue event) {
        return FlowExecutor.runStepEvent(this, stepId, event);
    }

    String entry() {
        return entry;
    }

    Map<String, Node> nodes() {
        return nodes;
    }

    Map<String, Binding> outputBindings() {
        return outputBindings;
    }

    static final class Node {
        private final String id;
        private final List<Output> outputs;
        private final Set<String> outcomes;
        private final StepHandler handler;
        private final Map<String, RailixValue> config;
        private final Map<String, Binding> inputBindings;
        private final Map<String, String> transitions;

        Node(
                final String id,
                final List<Output> outputs,
                final Set<String> outcomes,
                final StepHandler handler,
                final Map<String, RailixValue> config,
                final Map<String, Binding> inputBindings,
                final Map<String, String> transitions
        ) {
            this.id = id;
            this.outputs = List.copyOf(outputs);
            this.outcomes = Set.copyOf(outcomes);
            this.handler = handler;
            this.config = immutable(config);
            this.inputBindings = immutable(inputBindings);
            this.transitions = immutable(transitions);
        }

        String id() {
            return id;
        }

        List<Output> outputs() {
            return outputs;
        }

        Set<String> outcomes() {
            return outcomes;
        }

        StepHandler handler() {
            return handler;
        }

        Map<String, RailixValue> config() {
            return config;
        }

        Map<String, Binding> inputBindings() {
            return inputBindings;
        }

        Map<String, String> transitions() {
            return transitions;
        }
    }

    record Output(String name, ValueShape shape) {
    }

    /** One compiler-validated application ingress declaration in source order. */
    public record Trigger(String id, String type, RailixValue.ObjectValue config) {
    }

    record Binding(ValueShape shape, List<Mapping> mappings, List<Mapping> assemblyMappings) {
        Binding(final ValueShape shape, final List<Mapping> mappings) {
            this(shape, List.copyOf(mappings), mappings.stream()
                    .sorted((left, right) -> left.targetPath().compareTo(right.targetPath()))
                    .toList());
        }

        Binding {
            mappings = List.copyOf(mappings);
            assemblyMappings = List.copyOf(assemblyMappings);
        }
    }

    record Mapping(
            ValueSource source,
            DraftFlow.Path sourcePath,
            List<RailixValue> defaultValue,
            Conversion conversion,
            DraftFlow.Path targetPath,
            int connectionIndex
    ) {
    }

    enum Conversion {
        NONE("", ValueShape.ANY, ValueShape.ANY),
        STRING_TO_NUMBER("string-to-number", ValueShape.STRING, ValueShape.NUMBER),
        NUMBER_TO_STRING("number-to-string", ValueShape.NUMBER, ValueShape.STRING),
        STRING_TO_BOOLEAN("string-to-boolean", ValueShape.STRING, ValueShape.BOOLEAN),
        BOOLEAN_TO_STRING("boolean-to-string", ValueShape.BOOLEAN, ValueShape.STRING);

        private final String encoded;
        private final ValueShape source;
        private final ValueShape target;

        Conversion(final String encoded, final ValueShape source, final ValueShape target) {
            this.encoded = encoded;
            this.source = source;
            this.target = target;
        }

        String encoded() {
            return encoded;
        }

        ValueShape source() {
            return source;
        }

        ValueShape target() {
            return target;
        }
    }

    sealed interface ValueSource permits FlowInput, StepOutput {
    }

    record FlowInput(String name) implements ValueSource {
    }

    record StepOutput(String stepId, String port) implements ValueSource {
    }

    private static <K, V> Map<K, V> immutable(final Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
