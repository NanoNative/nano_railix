package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;

import java.io.IOException;

/** Invalid bundle fixture whose checked exception cannot implement the Step call contract. */
public final class CheckedProcessStep {
    public static Class<?> inaccessibleImplementation() {
        return Hidden.class;
    }

    static final class Hidden {
        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    public static StepResult process(final StepInput configuration, final StepInput input) throws IOException {
        throw new IOException("This invalid PROCESS must never execute.");
    }

    public static final class Pre {
        public static StepResult pre(final StepInput configuration, final StepInput input) throws IOException {
            throw new IOException("This invalid PRE must never execute.");
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome());
        }
    }

    public static final class Post {
        public static StepResult post(final StepInput configuration, final StepInput input) throws IOException {
            throw new IOException("This invalid POST must never execute.");
        }

        public static StepResult process(final StepInput configuration, final StepInput input) {
            return StepResult.outcome(input.primaryOutcome());
        }
    }
}
