package dev.nanonative.railix.railixstdhttp;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Minimal process entrypoint for the first std-http traffic panel surface.
 */
public final class HttpTrafficPanelMain {

    private static final String USAGE = "Usage: railix-http-panel --runs-root <dir> [--limit <count>] [--format <table|json>]";
    private static final List<Column> TABLE_COLUMNS = List.of(
            new Column("timestamp", row -> DateTimeFormatter.ISO_INSTANT.format(row.timestamp())),
            new Column("method", HttpTrafficPanelQuery.HttpTrafficRow::method),
            new Column("path", HttpTrafficPanelQuery.HttpTrafficRow::path),
            new Column("status", row -> Integer.toString(row.status())),
            new Column("durationMs", row -> Long.toString(row.durationMs()))
    );

    private HttpTrafficPanelMain() {}

    /**
     * Process entrypoint for HTTP traffic panel rendering.
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
     * Renders the current HTTP traffic panel without exiting the current process.
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
            final String output = switch (cliArguments.format()) {
                case TABLE -> renderTable(new HttpTrafficPanelQuery().read(Path.of(cliArguments.runsRoot()), cliArguments.limit()));
                case JSON -> new HttpTrafficReport().toStableJson(Path.of(cliArguments.runsRoot()), cliArguments.limit());
            };
            stdout.println(output);
            return new LaunchResult(0, "ok", output);
        } catch (final CliUsageException exception) {
            stderr.println(USAGE);
            stderr.println(exception.getMessage());
            return new LaunchResult(2, exception.getMessage(), "");
        } catch (final RuntimeException exception) {
            final String message = "Failed to render HTTP traffic panel: " + summarize(exception);
            stderr.println(message);
            return new LaunchResult(1, message, "");
        }
    }

    /**
     * Result of one panel invocation.
     *
     * @param exitCode process-style exit code
     * @param message user-facing summary
     * @param output rendered panel output when successful
     */
    public record LaunchResult(int exitCode, String message, String output) {
        public LaunchResult {
            if (exitCode < 0) {
                throw new IllegalArgumentException("exitCode must be >= 0");
            }
            message = Objects.requireNonNull(message, "message");
            output = Objects.requireNonNull(output, "output");
        }

        public boolean succeeded() {
            return exitCode == 0;
        }
    }

    static String renderTable(final List<HttpTrafficPanelQuery.HttpTrafficRow> rows) {
        final List<List<String>> renderedRows = rows.stream()
                .map(HttpTrafficPanelMain::renderRow)
                .toList();
        final int[] widths = columnWidths(renderedRows);
        final StringBuilder output = new StringBuilder();
        output.append(renderHeader(widths)).append('\n');
        output.append(renderDivider(widths));
        for (final List<String> row : renderedRows) {
            output.append('\n').append(renderLine(row, widths));
        }
        return output.toString();
    }

    private static List<String> renderRow(final HttpTrafficPanelQuery.HttpTrafficRow row) {
        final List<String> values = new ArrayList<>(TABLE_COLUMNS.size());
        for (final Column column : TABLE_COLUMNS) {
            values.add(column.value().apply(row));
        }
        return List.copyOf(values);
    }

    private static int[] columnWidths(final List<List<String>> rows) {
        final int[] widths = TABLE_COLUMNS.stream().mapToInt(column -> column.name().length()).toArray();
        for (final List<String> row : rows) {
            for (int index = 0; index < row.size(); index++) {
                widths[index] = Math.max(widths[index], row.get(index).length());
            }
        }
        return widths;
    }

    private static String renderHeader(final int[] widths) {
        final List<String> values = TABLE_COLUMNS.stream().map(Column::name).toList();
        return renderLine(values, widths);
    }

    private static String renderDivider(final int[] widths) {
        final StringBuilder divider = new StringBuilder();
        for (int index = 0; index < widths.length; index++) {
            if (index > 0) {
                divider.append("-+-");
            }
            divider.append("-".repeat(widths[index]));
        }
        return divider.toString();
    }

    private static String renderLine(final List<String> values, final int[] widths) {
        final StringBuilder line = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                line.append(" | ");
            }
            line.append(pad(values.get(index), widths[index]));
        }
        return line.toString();
    }

    private static String pad(final String value, final int width) {
        if (value.length() >= width) {
            return value;
        }
        return value + " ".repeat(width - value.length());
    }

    private static CliArguments parseArgs(final List<String> args) {
        String runsRoot = "";
        int limit = 100;
        boolean limitAssigned = false;
        Format format = Format.TABLE;
        boolean formatAssigned = false;
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
                case "--format" -> {
                    if (formatAssigned) {
                        throw new CliUsageException("Argument may only be provided once: --format");
                    }
                    format = parseFormat(value);
                    formatAssigned = true;
                }
                default -> throw new CliUsageException("Unknown argument: " + argument);
            }
        }
        if (runsRoot.isEmpty()) {
            throw new CliUsageException("Missing required argument: --runs-root");
        }
        return new CliArguments(runsRoot, limit, format);
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

    private static Format parseFormat(final String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "table" -> Format.TABLE;
            case "json" -> Format.JSON;
            default -> throw new CliUsageException("--format must be table or json");
        };
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

    private record CliArguments(String runsRoot, int limit, Format format) {
        private CliArguments {
            runsRoot = Objects.requireNonNull(runsRoot, "runsRoot");
            if (limit < 0) {
                throw new IllegalArgumentException("limit must be >= 0");
            }
            format = Objects.requireNonNull(format, "format");
        }
    }

    private record Column(String name, Function<HttpTrafficPanelQuery.HttpTrafficRow, String> value) {
        private Column {
            name = Objects.requireNonNull(name, "name");
            value = Objects.requireNonNull(value, "value");
        }
    }

    private enum Format {
        TABLE,
        JSON
    }

    private static final class CliUsageException extends RuntimeException {
        private CliUsageException(final String message) {
            super(message);
        }
    }
}
