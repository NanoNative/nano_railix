package thirdparty.conformance;

import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/** Real bundle-owned Step that starts process trees for ownership verification. */
public final class ProcessTreeStepHandler implements StepHandler {
    /** Creates one stateless handler. */
    public ProcessTreeStepHandler() {
    }

    @Override
    public StepResult run(final StepInput input) {
        start(input.string("mode"), ProcessHandle.current().pid(), Path.of(input.string("marker")));
        return StepResult.outcome(input.primaryOutcome());
    }

    /** Runs a child or grandchild process from the real generated application JAR. */
    public static void main(final String[] arguments) throws Exception {
        switch (arguments[0]) {
            case "established" -> established(Long.parseLong(arguments[1]), Path.of(arguments[2]));
            case "after-exit" -> afterExit(Long.parseLong(arguments[1]), Path.of(arguments[2]));
            case "sleep" -> new CountDownLatch(1).await();
            default -> throw new IllegalArgumentException("Unknown process-tree mode: " + arguments[0]);
        }
    }

    private static void established(final long parent, final Path marker) throws Exception {
        final Process grandchild = start("sleep", parent, marker);
        write(marker, ProcessHandle.current().pid(), grandchild.pid());
        new CountDownLatch(1).await();
    }

    private static void afterExit(final long parent, final Path marker) throws Exception {
        write(marker, ProcessHandle.current().pid());
        while (ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false)) {
            Thread.sleep(Duration.ofMillis(10));
        }
        final Process grandchild = start("sleep", parent, marker);
        write(marker, grandchild.pid());
        new CountDownLatch(1).await();
    }

    private static Process start(final String mode, final long parent, final Path marker) {
        try {
            return new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    ProcessTreeStepHandler.class.getName(),
                    mode,
                    Long.toString(parent),
                    marker.toString()
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (final IOException exception) {
            throw new IllegalStateException("Process-tree Step could not start its child.", exception);
        }
    }

    private static void write(final Path marker, final long... pids) throws IOException {
        final StringBuilder value = new StringBuilder();
        for (final long pid : pids) {
            value.append(pid).append('\n');
        }
        Files.writeString(
                marker,
                value,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }
}
