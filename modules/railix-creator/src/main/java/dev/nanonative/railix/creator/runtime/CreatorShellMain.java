package dev.nanonative.railix.creator.runtime;

import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * Minimal Creator shell entrypoint that serves bundled static assets plus a workspace API.
 */
public final class CreatorShellMain {

    private static final String USAGE = "Usage: railix-creator --repo-root <dir> [--runs-root <dir>] [--instance-registry-root <dir>] [--port <0-65535>]";

    private CreatorShellMain() {}

    /**
     * Process entrypoint.
     *
     * @param args CLI args
     */
    public static void main(final String[] args) {
        try (RunningCreator runningCreator = launch(List.of(args), System.out, System.err)) {
            if (!runningCreator.result().succeeded()) {
                System.exit(runningCreator.result().exitCode());
            }
            runningCreator.awaitShutdown();
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.exit(1);
        }
    }

    /**
     * Starts the Creator shell without exiting the current process.
     *
     * @param args CLI args
     * @param stdout stdout sink
     * @param stderr stderr sink
     * @return running Creator shell handle
     */
    public static RunningCreator launch(final List<String> args, final PrintStream stdout, final PrintStream stderr) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        try {
            final CliArguments cliArguments = parseArgs(args);
            final CreatorShellServer server = CreatorShellServer.start(
                    Path.of(cliArguments.repoRoot()),
                    Path.of(cliArguments.runsRoot()),
                    Path.of(cliArguments.instanceRegistryRoot()),
                    cliArguments.port()
            );
            final CountDownLatch shutdownLatch = new CountDownLatch(1);
            stdout.println("creatorUrl=" + server.baseUri());
            return new RunningCreator(
                    new LaunchResult(0, "creator-started", server.baseUri(), cliArguments.repoRoot(), cliArguments.runsRoot()),
                    server,
                    shutdownLatch
            );
        } catch (final CliUsageException exception) {
            stderr.println(USAGE);
            stderr.println(exception.getMessage());
            return new RunningCreator(
                    new LaunchResult(2, exception.getMessage(), URI.create("http://127.0.0.1/"), "", ""),
                    null,
                    new CountDownLatch(0)
            );
        } catch (final RuntimeException exception) {
            final String message = "Failed to start Creator shell: " + summarize(exception);
            stderr.println(message);
            return new RunningCreator(
                    new LaunchResult(1, message, URI.create("http://127.0.0.1/"), "", ""),
                    null,
                    new CountDownLatch(0)
            );
        }
    }

    /**
     * Result of one launch attempt.
     *
     * @param exitCode process-style exit code
     * @param message user-facing summary
     * @param creatorUrl bound Creator URL when available
     * @param repoRoot resolved repo root
     * @param runsRoot resolved runs root
     */
    public record LaunchResult(
            int exitCode,
            String message,
            URI creatorUrl,
            String repoRoot,
            String runsRoot
    ) {
        public LaunchResult {
            if (exitCode < 0) {
                throw new IllegalArgumentException("exitCode must be >= 0");
            }
            message = Objects.requireNonNull(message, "message");
            creatorUrl = Objects.requireNonNull(creatorUrl, "creatorUrl");
            repoRoot = Objects.requireNonNull(repoRoot, "repoRoot");
            runsRoot = Objects.requireNonNull(runsRoot, "runsRoot");
        }

        public boolean succeeded() {
            return exitCode == 0;
        }
    }

    /**
     * Running Creator shell handle for tests and embedding.
     */
    public static final class RunningCreator implements AutoCloseable {
        private final LaunchResult result;
        private final CreatorShellServer server;
        private final CountDownLatch shutdownLatch;

        private RunningCreator(final LaunchResult result, final CreatorShellServer server, final CountDownLatch shutdownLatch) {
            this.result = Objects.requireNonNull(result, "result");
            this.server = server;
            this.shutdownLatch = Objects.requireNonNull(shutdownLatch, "shutdownLatch");
        }

        public LaunchResult result() {
            return result;
        }

        public void awaitShutdown() throws InterruptedException {
            shutdownLatch.await();
        }

        @Override
        public void close() {
            if (server != null) {
                server.close();
            }
            shutdownLatch.countDown();
        }
    }

    private static CliArguments parseArgs(final List<String> args) {
        String repoRoot = "";
        String runsRoot = "";
        String instanceRegistryRoot = "";
        Integer port = null;
        for (int index = 0; index < args.size(); index++) {
            final String argument = Objects.requireNonNull(args.get(index), "arg");
            if (!argument.startsWith("--")) {
                throw new CliUsageException("Unknown argument: " + argument);
            }
            if (index + 1 >= args.size()) {
                throw new CliUsageException("Missing value for argument: " + argument);
            }
            final String value = requireNonBlank(args.get(++index), argument);
            switch (argument) {
                case "--repo-root" -> repoRoot = assignOnce(repoRoot, value, argument);
                case "--runs-root" -> runsRoot = assignOnce(runsRoot, value, argument);
                case "--instance-registry-root" -> instanceRegistryRoot = assignOnce(instanceRegistryRoot, value, argument);
                case "--port" -> port = parsePort(assignOnce(port == null ? "" : String.valueOf(port), value, argument));
                default -> throw new CliUsageException("Unknown argument: " + argument);
            }
        }
        if (repoRoot.isEmpty()) {
            throw new CliUsageException("Missing required argument: --repo-root");
        }
        final String resolvedRunsRoot = runsRoot.isEmpty() ? Path.of(repoRoot).resolve("build").resolve("runs").toString() : runsRoot;
        final String resolvedInstanceRegistryRoot = instanceRegistryRoot.isEmpty()
                ? Path.of(repoRoot).resolve("build").resolve("instance-registry").toString()
                : instanceRegistryRoot;
        return new CliArguments(repoRoot, resolvedRunsRoot, resolvedInstanceRegistryRoot, port == null ? 0 : port);
    }

    private static String assignOnce(final String currentValue, final String nextValue, final String argument) {
        if (!currentValue.isEmpty()) {
            throw new CliUsageException("Argument may only be provided once: " + argument);
        }
        return nextValue;
    }

    private static int parsePort(final String value) {
        try {
            final int port = Integer.parseInt(value);
            if (port < 0 || port > 65535) {
                throw new CliUsageException("--port must be between 0 and 65535");
            }
            return port;
        } catch (final NumberFormatException exception) {
            throw new CliUsageException("--port must be an integer");
        }
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new CliUsageException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String summarize(final RuntimeException exception) {
        final String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getName();
        }
        return exception.getClass().getName() + ": " + message;
    }

    private record CliArguments(String repoRoot, String runsRoot, String instanceRegistryRoot, int port) {
        private CliArguments {
            Objects.requireNonNull(repoRoot, "repoRoot");
            Objects.requireNonNull(runsRoot, "runsRoot");
            Objects.requireNonNull(instanceRegistryRoot, "instanceRegistryRoot");
        }
    }

    private static final class CliUsageException extends RuntimeException {
        private CliUsageException(final String message) {
            super(message);
        }
    }
}
