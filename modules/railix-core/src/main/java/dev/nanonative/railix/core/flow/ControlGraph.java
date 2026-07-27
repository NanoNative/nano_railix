package dev.nanonative.railix.core.flow;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Proves which Steps have executed on every control path to another Step. */
final class ControlGraph {
    static final String END = "end";

    private final Map<String, Set<String>> dominators;

    private ControlGraph(final Map<String, Set<String>> dominators) {
        this.dominators = Map.copyOf(dominators);
    }

    static ControlGraph analyze(
            final String entry,
            final Map<String, DraftFlow.DraftStep> steps
    ) {
        final Map<String, Set<String>> predecessors = predecessors(steps);
        final Set<String> reachable = reachable(entry, steps);
        final Map<String, Set<String>> dominators = new LinkedHashMap<>();
        for (final String node : reachable) {
            dominators.put(node, node.equals(entry)
                    ? Set.of(entry)
                    : new LinkedHashSet<>(reachable));
        }

        boolean changed;
        do {
            changed = false;
            for (final String node : reachable) {
                if (node.equals(entry)) {
                    continue;
                }
                final Set<String> next = new LinkedHashSet<>(reachable);
                final Set<String> incoming = predecessors.getOrDefault(node, Set.of());
                for (final String predecessor : incoming) {
                    if (dominators.containsKey(predecessor)) {
                        next.retainAll(dominators.get(predecessor));
                    }
                }
                next.add(node);
                if (!next.equals(dominators.get(node))) {
                    dominators.put(node, next);
                    changed = true;
                }
            }
        } while (changed);

        return new ControlGraph(dominators);
    }

    boolean outputAvailableAt(final String producer, final String consumer) {
        return !producer.equals(consumer)
                && dominators.getOrDefault(consumer, Set.of()).contains(producer);
    }

    boolean reaches(final String node) {
        return dominators.containsKey(node);
    }

    private static Map<String, Set<String>> predecessors(final Map<String, DraftFlow.DraftStep> steps) {
        final Map<String, Set<String>> result = new LinkedHashMap<>();
        for (final String step : steps.keySet()) {
            result.put(step, new LinkedHashSet<>());
        }
        result.put(END, new LinkedHashSet<>());
        for (final DraftFlow.DraftStep step : steps.values()) {
            for (final String target : step.transitions().values()) {
                result.get(target).add(step.id());
            }
        }
        return result;
    }

    private static Set<String> reachable(
            final String entry,
            final Map<String, DraftFlow.DraftStep> steps
    ) {
        final Set<String> result = new LinkedHashSet<>();
        final Deque<String> pending = new ArrayDeque<>();
        pending.add(entry);
        while (!pending.isEmpty()) {
            final String node = pending.removeFirst();
            if (!result.add(node) || END.equals(node)) {
                continue;
            }
            for (final String target : steps.get(node).transitions().values()) {
                pending.addLast(target);
            }
        }
        return result;
    }
}
