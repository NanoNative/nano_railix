package dev.nanonative.railix.core.project;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable compiler plan consumed only by application source generation. */
record ApplicationPlan(String projectId, List<NodePlan> nodes, List<TriggerPlan> triggers) {
    static final int END = -1;
    static final int UNROUTED = -2;

    ApplicationPlan {
        nodes = List.copyOf(nodes);
        triggers = List.copyOf(triggers);
    }

    record TriggerPlan(int node, int start) {
    }

    record NodePlan(
            String id,
            StepDefinition step,
            Map<String, Binding> inputs,
            Map<String, Path> receives,
            Map<String, Path> returns,
            int[] destinations,
            List<String> outcomes,
            int owner,
            String path
    ) {
        NodePlan {
            destinations = destinations.clone();
            outcomes = List.copyOf(outcomes);
        }

        @Override
        public int[] destinations() {
            return destinations.clone();
        }

    }

    sealed interface Binding permits JsonBinding, PathBinding, ChoiceBinding, CandidatesBinding,
            MatcherGroupsBinding, StepsBinding {
    }

    record JsonBinding(List<RailixValue> value) implements Binding {
        JsonBinding {
            value = List.copyOf(value);
        }
    }

    record PathBinding(Path path, StepDefinition.PathAccess access) implements Binding {
    }

    record ChoiceBinding(
            String option,
            Map<String, Binding> inputs,
            List<StepDefinition.InputReference> valueSources
    ) implements Binding {
        ChoiceBinding {
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            valueSources = List.copyOf(valueSources);
        }

        Optional<StepDefinition.InputReference> valueSource() {
            return valueSources.stream().findFirst();
        }
    }

    record CandidatesBinding(List<CandidatePlan> candidates, String path) implements Binding {
        CandidatesBinding {
            candidates = List.copyOf(candidates);
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("Candidate input path must be supplied.");
            }
        }
    }

    record MatcherGroupsBinding(List<List<CandidatePlan>> groups) implements Binding {
        MatcherGroupsBinding {
            groups = groups.stream().map(List::copyOf).toList();
        }
    }

    record CandidatePlan(
            ChoiceBinding source,
            List<NestedStepPlan> transforms,
            List<List<NestedStepPlan>> predicates
    ) {
        CandidatePlan {
            if (source == null) {
                throw new IllegalArgumentException("Candidate plan source must be supplied.");
            }
            transforms = List.copyOf(transforms);
            predicates = predicates.stream().map(List::copyOf).toList();
        }
    }

    record StepsBinding(
            List<NestedStepPlan> steps,
            StepDefinition.ValueSource valueSource,
            boolean propagatesOutcomes
    ) implements Binding {
        StepsBinding {
            steps = List.copyOf(steps);
            if (valueSource == null) {
                throw new IllegalArgumentException("Nested Step value source cannot be Java null.");
            }
        }
    }

    record NestedStepPlan(StepDefinition step, Map<String, Binding> inputs, String path) {
        NestedStepPlan {
            inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        }
    }

    record Path(List<PathElement> elements) {
        Path {
            elements = List.copyOf(elements);
        }
    }

    sealed interface PathElement permits Field, Index {
    }

    record Field(String name) implements PathElement {
    }

    record Index(int value) implements PathElement {
    }
}
