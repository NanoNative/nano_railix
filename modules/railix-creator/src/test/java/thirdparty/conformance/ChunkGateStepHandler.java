package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Real bundle-owned Step that exposes bounded example admission through filesystem signals. */
public final class ChunkGateStepHandler implements StepHandler {
    /** Creates one stateless handler. */
    public ChunkGateStepHandler() {
    }

    @Override
    public StepResult run(final StepInput input) throws InterruptedException {
        final Path root = Path.of(input.string("root"));
        final String example = input.string("case");
        final boolean ignoreInterrupt = ((RailixValue.BooleanValue) input.value("ignore_interrupt")).value();
        try {
            Files.createDirectories(root);
            Files.writeString(
                    root.resolve(example + ".started"),
                    "started",
                    StandardCharsets.UTF_8
            );
            while (!Files.exists(root.resolve("release"))) {
                try {
                    Thread.sleep(10);
                } catch (final InterruptedException exception) {
                    if (!ignoreInterrupt) {
                        throw exception;
                    }
                    Files.writeString(
                            root.resolve(example + ".interrupted"),
                            "interrupted",
                            StandardCharsets.UTF_8
                    );
                }
            }
            return StepResult.outcome(input.primaryOutcome());
        } catch (final IOException exception) {
            throw new IllegalStateException("Chunk gate signal could not be written.", exception);
        }
    }
}
