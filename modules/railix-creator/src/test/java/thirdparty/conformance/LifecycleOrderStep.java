package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Records actual lifecycle calls from an installed bundle without retaining invocation data. */
public final class LifecycleOrderStep {
    public static StepResult pre(final StepInput configuration, final StepInput input) {
        return record(configuration,input,"pre");
    }

    public static StepResult process(final StepInput configuration, final StepInput input) {
        return StepResult.outcome(input.primaryOutcome()).output("value",input.value("value"));
    }

    public static StepResult post(final StepInput configuration, final StepInput input) {
        return record(configuration,input,"post");
    }

    private static StepResult record(final StepInput configuration, final StepInput input, final String phase) {
        try {
            Files.writeString(Path.of(configuration.string("log")),phase+":"+configuration.string("name")+"\n",
                    StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            return StepResult.outcome(input.primaryOutcome());
        } catch (final IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public static final class Parent {
        public static StepResult process(final StepInput configuration, final StepInput input) throws InterruptedException {
            return input.run("steps").writeWhenPresent("field");
        }
    }
}
