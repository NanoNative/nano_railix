package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.SettingsTree;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Minimal single-run entrypoint for a persisted built Railix app.
 */
public final class BuiltRailixAppMain {

    private static final String USAGE = "Usage: railix-app --plan <plan.json|railix.app.yaml> --envelope <envelope.json|sample.envelope.yaml> --runs-root <dir> [--profile <name>] [--settings <settings.json|classpath:/resource.json>]... [--set <railix.path=value>]... [--run-id <id>] (JVM properties: -Drailix.setting.<path>=<value>)";

    private BuiltRailixAppMain() {}

    /**
     * Process entrypoint for packaged built apps.
     *
     * @param args launcher arguments
     */
    public static void main(final String[] args) {
        final LaunchResult result = launch(args, System.out, System.err);
        if (result.exitCode() != 0) {
            System.exit(result.exitCode());
        }
    }

    /**
     * Launches a single built-app run without exiting the current process.
     *
     * @param args launcher arguments
     * @param stdout stdout sink
     * @param stderr stderr sink
     * @return deterministic launch result
     */
    public static LaunchResult launch(final String[] args, final PrintStream stdout, final PrintStream stderr) {
        Objects.requireNonNull(args, "args");
        return launch(List.of(args), stdout, stderr);
    }

    /**
     * Launches a single built-app run without exiting the current process.
     *
     * @param args launcher arguments
     * @param stdout stdout sink
     * @param stderr stderr sink
     * @return deterministic launch result
     */
    public static LaunchResult launch(final List<String> args, final PrintStream stdout, final PrintStream stderr) {
        return launch(args, stdout, stderr, BuiltRailixAppLoader::loadApp);
    }

    static LaunchResult launch(
            final List<String> args,
            final PrintStream stdout,
            final PrintStream stderr,
            final Function<String, BuiltRailixApp> appLoader
    ) {
        return launch(args, stdout, stderr, appLoader, System.getenv(), systemPropertiesMap(System.getProperties()));
    }

    static LaunchResult launch(
            final List<String> args,
            final PrintStream stdout,
            final PrintStream stderr,
            final Function<String, BuiltRailixApp> appLoader,
            final Map<String, String> environment
    ) {
        return launch(args, stdout, stderr, appLoader, environment, systemPropertiesMap(System.getProperties()));
    }

    static LaunchResult launch(
            final List<String> args,
            final PrintStream stdout,
            final PrintStream stderr,
            final Function<String, BuiltRailixApp> appLoader,
            final Map<String, String> environment,
            final Map<String, String> systemProperties
    ) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
        Objects.requireNonNull(appLoader, "appLoader");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(systemProperties, "systemProperties");
        try {
            final CliArguments cliArguments = parseArgs(args);
            final String runId = cliArguments.runId().isEmpty() ? generateRunId() : cliArguments.runId();
            final List<String> settingsLocations = new ArrayList<>(resolvePackagedSettingsLocations(cliArguments.profileName()));
            settingsLocations.addAll(cliArguments.settingsLocations());
            SettingsTree settingsTree = settingsLocations.isEmpty()
                    ? SettingsTree.empty()
                    : BuiltRailixAppLoader.loadSettingsTree(settingsLocations);
            settingsTree = settingsTree.overlay(BuiltRailixAppLoader.environmentOverrideSettings(environment));
            settingsTree = settingsTree.overlay(BuiltRailixAppLoader.systemPropertyOverrideSettings(systemProperties));
            settingsTree = settingsTree.overlay(BuiltRailixAppLoader.cliOverrideSettings(cliArguments.inlineSettings()));
            final BuiltRailixApp app = appLoader.apply(cliArguments.planLocation());
            final Envelope envelope = BuiltRailixAppLoader.loadEnvelope(cliArguments.envelopeLocation());
            final LocalExecutionKernel.RunRecord record = app.run(new LocalExecutionKernel.RunRequest(
                    runId,
                    Path.of(cliArguments.runsRoot()),
                    envelope,
                    settingsTree
            ));
            final String summary = "runId=" + record.runId() + " outcome=" + record.outcome() + " runFolder=" + record.runFolder();
            if ("failed".equals(record.outcome())) {
                stderr.println("Run failed: " + summary);
                return new LaunchResult(1, summary, record.runId(), record.outcome(), record.runFolder().toString());
            }
            stdout.println(summary);
            return new LaunchResult(0, summary, record.runId(), record.outcome(), record.runFolder().toString());
        } catch (final CliUsageException exception) {
            stderr.println(USAGE);
            stderr.println(exception.getMessage());
            return new LaunchResult(2, exception.getMessage(), "", "failed", "");
        } catch (final RuntimeException exception) {
            final String message = "Failed to launch built app: " + summarize(exception);
            stderr.println(message);
            return new LaunchResult(1, message, "", "failed", "");
        }
    }

    /**
     * Result of one launcher invocation.
     *
     * @param exitCode process-style exit code
     * @param message user-facing result summary
     * @param runId created or requested run id
     * @param outcome final run outcome or {@code failed}
     * @param runFolder resolved run artifact folder when available
     */
    public record LaunchResult(
            int exitCode,
            String message,
            String runId,
            String outcome,
            String runFolder
    ) {
        public LaunchResult {
            if (exitCode < 0) {
                throw new IllegalArgumentException("exitCode must be >= 0");
            }
            message = Objects.requireNonNull(message, "message");
            runId = Objects.requireNonNull(runId, "runId");
            outcome = Objects.requireNonNull(outcome, "outcome");
            runFolder = Objects.requireNonNull(runFolder, "runFolder");
        }

        public boolean succeeded() {
            return exitCode == 0;
        }
    }

    private static CliArguments parseArgs(final List<String> args) {
        String planLocation = "";
        String envelopeLocation = "";
        String runsRoot = "";
        String profileName = "";
        final List<String> settingsLocations = new ArrayList<>();
        final List<String> inlineSettings = new ArrayList<>();
        final Set<RailixPath> inlineSettingPaths = new HashSet<>();
        String runId = "";
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
                case "--plan" -> planLocation = assignOnce(planLocation, value, argument);
                case "--envelope" -> envelopeLocation = assignOnce(envelopeLocation, value, argument);
                case "--runs-root" -> runsRoot = assignOnce(runsRoot, value, argument);
                case "--profile" -> profileName = assignOnce(profileName, validateProfileName(value), argument);
                case "--settings" -> settingsLocations.add(value);
                case "--set" -> inlineSettings.add(validateUniqueInlineSetting(value, inlineSettingPaths));
                case "--run-id" -> runId = assignOnce(runId, value, argument);
                default -> throw new CliUsageException("Unknown argument: " + argument);
            }
        }
        if (planLocation.isEmpty()) {
            throw new CliUsageException("Missing required argument: --plan");
        }
        if (envelopeLocation.isEmpty()) {
            throw new CliUsageException("Missing required argument: --envelope");
        }
        if (runsRoot.isEmpty()) {
            throw new CliUsageException("Missing required argument: --runs-root");
        }
        return new CliArguments(planLocation, envelopeLocation, runsRoot, profileName, List.copyOf(settingsLocations), List.copyOf(inlineSettings), runId);
    }

    private static String assignOnce(final String currentValue, final String nextValue, final String argument) {
        if (!currentValue.isEmpty()) {
            throw new CliUsageException("Argument may only be provided once: " + argument);
        }
        return nextValue;
    }

    private static String generateRunId() {
        return "run-" + UUID.randomUUID();
    }

    private static String summarize(final RuntimeException exception) {
        final String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getName();
        }
        return exception.getClass().getName() + ": " + message;
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new CliUsageException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String validateUniqueInlineSetting(final String assignment, final Set<RailixPath> inlineSettingPaths) {
        Objects.requireNonNull(inlineSettingPaths, "inlineSettingPaths");
        final String value = requireNonBlank(assignment, "--set");
        final int separatorIndex = value.indexOf('=');
        if (separatorIndex < 1) {
            throw new CliUsageException("--set must use <path>=<value>");
        }
        final RailixPath path;
        try {
            path = RailixPath.parse(value.substring(0, separatorIndex));
        } catch (final IllegalArgumentException exception) {
            throw new CliUsageException("--set path is invalid: " + exception.getMessage());
        }
        if (!inlineSettingPaths.add(path)) {
            throw new CliUsageException("Duplicate --set path: " + path);
        }
        return value;
    }

    private static String validateProfileName(final String profileName) {
        final String value = requireNonBlank(profileName, "--profile");
        if (!value.matches("[A-Za-z0-9._-]+")) {
            throw new CliUsageException("--profile must match [A-Za-z0-9._-]+");
        }
        return value;
    }

    private static List<String> resolvePackagedSettingsLocations(final String profileName) {
        try {
            return BuiltRailixAppLoader.packagedSettingsLocations(
                    profileName.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(profileName)
            );
        } catch (final IllegalArgumentException exception) {
            throw new CliUsageException(exception.getMessage());
        }
    }

    private static Map<String, String> systemPropertiesMap(final Properties properties) {
        Objects.requireNonNull(properties, "properties");
        final java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        for (final String propertyName : properties.stringPropertyNames()) {
            values.put(propertyName, properties.getProperty(propertyName));
        }
        return Map.copyOf(values);
    }

    private record CliArguments(
            String planLocation,
            String envelopeLocation,
            String runsRoot,
            String profileName,
            List<String> settingsLocations,
            List<String> inlineSettings,
            String runId
    ) {
        private CliArguments {
            planLocation = Objects.requireNonNull(planLocation, "planLocation");
            envelopeLocation = Objects.requireNonNull(envelopeLocation, "envelopeLocation");
            runsRoot = Objects.requireNonNull(runsRoot, "runsRoot");
            profileName = Objects.requireNonNull(profileName, "profileName");
            settingsLocations = List.copyOf(settingsLocations);
            inlineSettings = List.copyOf(inlineSettings);
            runId = Objects.requireNonNull(runId, "runId");
        }
    }

    private static final class CliUsageException extends RuntimeException {
        private CliUsageException(final String message) {
            super(message);
        }
    }
}
