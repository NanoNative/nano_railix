package dev.nanonative.railix.core.step;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Explicit Step dependency catalog. No reflection or scanning is involved. */
public final class StepCatalog {
    private final List<StepDefinition> definitions;

    private StepCatalog(final List<StepDefinition> definitions) {
        this.definitions = Collections.unmodifiableList(new ArrayList<>(definitions));
    }

    public static StepCatalog of(final StepDefinition... definitions) {
        if (definitions == null) {
            throw new IllegalArgumentException("Step definitions cannot be Java null.");
        }
        final List<StepDefinition> copy = new ArrayList<>(definitions.length);
        final Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < definitions.length; index++) {
            final StepDefinition definition = definitions[index];
            if (definition == null) {
                throw new IllegalArgumentException("Step definition at index " + index + " cannot be Java null.");
            }
            if (!ids.add(definition.id())) {
                throw new IllegalArgumentException("Step definition id is duplicated: " + definition.id());
            }
            copy.add(definition);
        }
        return new StepCatalog(copy);
    }

    public Optional<StepDefinition> find(final String id) {
        return definitions.stream().filter(definition -> definition.id().equals(id)).findFirst();
    }

    public List<StepDefinition> definitions() {
        return definitions;
    }
}
