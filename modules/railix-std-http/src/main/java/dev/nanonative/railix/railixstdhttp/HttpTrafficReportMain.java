package dev.nanonative.railix.railixstdhttp;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Minimal process entrypoint for reading persisted local HTTP traffic artifacts.
 */
public final class HttpTrafficReportMain {

    private static final String USAGE = "Usage: railix-http-traffic --runs-root <dir> [--limit <count>]";

    private HttpTrafficReportMain() {}

    /**
     * Process entrypoint for HTTP traffic report generation.
     *
     * @param args CLI arguments
     */
    public static void main(final String[] args) {
        final LaunchResult result = launch(List.of(args), System.out, System.err);
        if (result.exitCode() != 0) {
            System.exit(result.exitCode());
        }
    }

    /**
     * Generates a stable-JSON traffic report without exiting the current process.
     *
     * @param args CLI arguments
     * @param stdout stdout sink
     * @param stderr stderr sink
     * @return deterministic launch result
     */
    public static LaunchResult launch(final List<String> args, final PrintStream stdout, final PrintStream stderr) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        try {
            final CliArguments cliArguments = parseArgs(args);
            final String json = new HttpTrafficReport().toStableJson(Path.of(cliArguments.runsRoot()), cliArguments.limit());
            stdout.println(json);
            return new LaunchResult(0, "ok", json);
        } catch (final CliUsageException exception) {
            stderr.println(USAGE);
            stderr.println(exception.getMessage());
            return new LaunchResult(2, exception.getMessage(), "");
        } catch (final RuntimeException exception) {
            final String message = "Failed to read HTTP traffic report: " + summarize(exception);
            stderr.println(message);
            return new LaunchResult(1, message, "");
        }
    }

    /**
     * Result of one report invocation.
     *
     * @param exitCode process-style exit code
     * @param message user-facing summary
     * @param reportJson stable-JSON report when successful
     */
    public record LaunchResult(int exitCode, String message, String reportJson) {
        public LaunchResult {
            if (exitCode < 0) {
                throw new IllegalArgumentException("exitCode must be >= 0");
            }
            message = Objects.requireNonNull(message, "message");
            reportJson = Objects.requireNonNull(reportJson, "reportJson");
        }

        public boolean succeeded() {
            return exitCode == 0;
        }
    }

    private static CliArguments parseArgs(final List<String> args) {
        String runsRoot = "";
        int limit = 100;
        boolean limitAssigned = false;
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
                case "--runs-root" -> runsRoot = assignOnce(runsRoot, value, argument);
                case "--limit" -> {
                    if (limitAssigned) {
                        throw new CliUsageException("Argument may only be provided once: --limit");
                    }
                    limit = parseLimit(value);
                    limitAssigned = true;
                }
                default -> throw new CliUsageException("Unknown argument: " + argument);
            }
        }
        if (runsRoot.isEmpty()) {
            throw new CliUsageException("Missing required argument: --runs-root");
        }
        return new CliArguments(runsRoot, limit);
    }

    private static int parseLimit(final String value) {
        try {
            final int limit = Integer.parseInt(value);
            if (limit < 0) {
                throw new CliUsageException("--limit must be >= 0");
            }
            return limit;
        } catch (final NumberFormatException exception) {
            throw new CliUsageException("--limit must be an integer");
        }
    }

    private static String assignOnce(final String currentValue, final String nextValue, final String argument) {
        if (!currentValue.isEmpty()) {
            throw new CliUsageException("Argument may only be provided once: " + argument);
        }
        return nextValue;
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        if (value == null || value.isBlank()) {
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

    private record CliArguments(String runsRoot, int limit) {
        private CliArguments {
            runsRoot = Objects.requireNonNull(runsRoot, "runsRoot");
            if (limit < 0) {
                throw new IllegalArgumentException("limit must be >= 0");
            }
        }
    }

    private static final class CliUsageException extends RuntimeException {
        private CliUsageException(final String message) {
            super(message);
        }
    }
}
