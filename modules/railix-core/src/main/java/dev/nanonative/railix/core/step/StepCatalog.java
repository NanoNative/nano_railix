package dev.nanonative.railix.core.step;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Explicit Step dependency catalog. No reflection or runtime scanning is involved. */
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
        for (int index = 0; index < definitions.length; index++) {
            if (definitions[index] == null) {
                throw new IllegalArgumentException(
                        "Step definition at index " + index + " cannot be Java null."
                );
            }
            copy.add(definitions[index]);
        }
        return new StepCatalog(copy);
    }

    public List<StepDefinition> definitions() {
        return definitions;
    }
}
