package thirdparty.suffix.dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;

import java.io.IOException;

/** Invalid bundle class whose name ends with a valid built-in implementation's full name. */
public final class StandardStepHandlers {
    public static final class Cli {
        public static StepResult process(final StepInput configuration, final StepInput input) throws IOException {
            throw new IOException("This invalid PROCESS must never execute.");
        }
    }
}
