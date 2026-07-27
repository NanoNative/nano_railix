package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.stdlib.data.DataSteps;
import dev.nanonative.railix.stdlib.file.FileSteps;
import dev.nanonative.railix.stdlib.http.HttpSteps;
import dev.nanonative.railix.stdlib.text.LowercaseStep;

/** Explicit standard Step registry used by the compiler and generated applications. */
public final class StandardLibrary {
    private static final StepCatalog CATALOG = StepCatalog.of(
            LowercaseStep.definition(),
            DataSteps.nonblank(),
            DataSteps.translateExact(),
            DataSteps.defaultIfNull(),
            FileSteps.read(),
            FileSteps.write(),
            FileSteps.delete(),
            HttpSteps.get(),
            HttpSteps.post(),
            HttpSteps.delete()
    );

    private StandardLibrary() {
    }

    public static StepCatalog catalog() {
        return CATALOG;
    }
}
