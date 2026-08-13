package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Real bundle-owned Step used to prove graceful rolling shutdown. */
public final class SlowStepHandler implements StepHandler {
    /** Creates one stateless handler. */
    public SlowStepHandler() {
    }

    @Override
    public StepResult run(final StepInput input) throws InterruptedException {
        try {
            Files.writeString(Path.of(input.string("marker")), "started", StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            throw new IllegalStateException("Slow Step marker cannot be written.", exception);
        }
        final long delay = ((RailixValue.NumberValue) input.value("delay_millis"))
                .value()
                .longValueExact();
        Thread.sleep(delay);
        return StepResult.outcome(input.primaryOutcome());
    }
}
