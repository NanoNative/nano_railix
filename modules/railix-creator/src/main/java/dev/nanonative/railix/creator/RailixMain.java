package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.stdlib.StandardLibrary;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public Railix launcher for running an app or opening Creator. */
public final class RailixMain {
    private static final String USAGE = "Usage: railix run <app-directory> | railix creator [--port <1-65535>]";
    private static final String APPLICATION_USAGE =
            "Usage: <no arguments> | cli <trigger-id> [argument...] | creator "
                    + "| lock [--check | --write <output>...]";
    private static final int DEFAULT_CREATOR_PORT = 4173;

    private RailixMain() {
    }

    public static void main(final String[] args) {
        exitOnFailure(execute(List.of(args), System.out, System.err));
    }

    /** Returns success to the caller or terminates the process with the supplied failure code. */
    public static int exitOnFailure(final int exitCode) {
        if (exitCode != 0) {
            System.exit(exitCode);
        }
        return exitCode;
    }

    public static int execute(
            final List<String> arguments,
            final PrintStream stdout,
            final PrintStream stderr
    ) {
        if (arguments.isEmpty() || "--help".equals(arguments.getFirst())) {
            stdout.println(USAGE);
            return 0;
        }
        return switch (arguments.getFirst()) {
            case "run" -> runCommand(arguments, stdout, stderr);
            case "creator" -> creatorCommand(arguments, stdout, stderr);
            default -> usageError("Unknown command: " + arguments.getFirst(), stderr);
        };
    }

    /**
     * Starts a custom monolith's declared application triggers or opens Creator.
     * The packaged flow stream is always closed before the command returns or Creator starts.
     *
     * @param arguments application arguments: none, {@code cli <trigger-id>}, {@code creator}, or a lock command
     * @param packagedFlow committed {@code railix.flow.json}, limited to 1 MiB; Java null is rejected
     * @param packagedLock committed {@code railix.lock.json}, limited to 8 MiB;
     *                     Java null is accepted only by lock commands
     * @param catalog exact trusted Step dependencies available to the flow and Creator
     * @return process exit code without terminating the JVM
     */
    public static int executeApplication(
            final List<String> arguments,
            final InputStream packagedFlow,
            final InputStream packagedLock,
            final StepCatalog catalog
    ) {
        return executeApplication(
                arguments,
                packagedFlow,
                packagedLock,
                catalog,
                System.in,
                System.out,
                System.err
        );
    }

    static int executeApplication(
            final List<String> arguments,
            final InputStream packagedFlow,
            final InputStream packagedLock,
            final StepCatalog catalog,
            final InputStream stdin,
            final PrintStream stdout,
            final PrintStream stderr
    ) {
        if (packagedFlow == null) {
            if (!closeResource(packagedLock)) {
                return resourceCloseError(stderr);
            }
            return launcherError("PACKAGED_FLOW_MISSING", "Packaged flow resource is missing.", 4, stderr);
        }
        final ResourceRead flowRead = readResource(packagedFlow, RailixData.DEFAULT_MAX_SOURCE_BYTES);
        if (flowRead.failed()) {
            final boolean lockClosed = closeResource(packagedLock);
            if (!flowRead.closed() || !lockClosed) {
                return resourceCloseError(stderr);
            }
            return launcherError("PACKAGED_FLOW_READ_FAILED", "Could not read packaged flow.", 4, stderr);
        }
        if (!flowRead.closed()) {
            closeResource(packagedLock);
            return resourceCloseError(stderr);
        }
        final String flowSource;
        try {
            flowSource = decodeUtf8(flowRead.source());
        } catch (final CharacterCodingException exception) {
            if (!closeResource(packagedLock)) {
                return resourceCloseError(stderr);
            }
            return invalidFlowSource(stderr);
        }
        if (catalog == null) {
            if (!closeResource(packagedLock)) {
                return resourceCloseError(stderr);
            }
            return launcherError("STEP_CATALOG_MISSING", "Step catalog is missing.", 4, stderr);
        }
        if (arguments == null) {
            if (!closeResource(packagedLock)) {
                return resourceCloseError(stderr);
            }
            return applicationUsageError(stderr);
        }
        if (arguments.equals(List.of("lock"))) {
            if (!closeResource(packagedLock)) {
                return resourceCloseError(stderr);
            }
            return printLock(flowSource, catalog, stdout, stderr);
        }
        if (arguments.size() >= 3
                && "lock".equals(arguments.getFirst())
                && "--write".equals(arguments.get(1))) {
            if (!closeResource(packagedLock)) {
                return resourceCloseError(stderr);
            }
            return writeLock(flowSource, catalog, arguments.subList(2, arguments.size()), stderr);
        }
        final boolean startup = arguments.isEmpty();
        final boolean cli = arguments.size() >= 2 && "cli".equals(arguments.getFirst());
        final boolean creator = arguments.equals(List.of("creator"));
        final boolean check = arguments.equals(List.of("lock", "--check"));
        if ((!startup && !cli && !creator && !check)
                || (cli && arguments.get(1) == null)) {
            if (!closeResource(packagedLock)) {
                return resourceCloseError(stderr);
            }
            return applicationUsageError(stderr);
        }
        if (packagedLock == null) {
            return launcherError(
                    "PACKAGED_LOCK_MISSING",
                    "Packaged dependency lock resource is missing.",
                    4,
                    stderr
            );
        }
        final ResourceRead lockRead = readResource(packagedLock, RailixData.MAX_SOURCE_BYTES);
        if (lockRead.failed()) {
            if (!lockRead.closed()) {
                return resourceCloseError(stderr);
            }
            return launcherError(
                    "PACKAGED_LOCK_READ_FAILED",
                    "Could not read packaged dependency lock.",
                    4,
                    stderr
            );
        }
        if (!lockRead.closed()) {
            return resourceCloseError(stderr);
        }
        final String lockSource;
        try {
            lockSource = decodeUtf8(lockRead.source());
        } catch (final CharacterCodingException exception) {
            return sourceError(
                    "STEP_LOCK_SOURCE_UTF8_INVALID",
                    "Step dependency lock is not valid UTF-8.",
                    "lock",
                    stderr
            );
        }
        final CompileResult compilation = FlowCompiler.compile(flowSource, catalog, lockSource);
        final int lockResult = printCompilation(compilation, stderr);
        if (lockResult != 0 || check) {
            return lockResult;
        }
        if (creator) {
            return startCreator(0, catalog, stdout, stderr);
        }

        final CompiledFlow flow = ((CompileResult.Compiled) compilation).flow();
        final List<CompiledFlow.Trigger> startupTriggers = flow.triggers().stream()
                .filter(trigger -> "startup".equals(trigger.type()))
                .toList();
        if (startup) {
            final boolean longRunning = flow.triggers().stream().anyMatch(trigger ->
                    "http".equals(trigger.type())
                            || "socket".equals(trigger.type())
                            || "scheduled".equals(trigger.type())
            );
            if (longRunning) {
                return runApplication(flow, startupTriggers, stdout, stderr);
            }
            if (startupTriggers.isEmpty()) {
                return launcherError(
                        "STARTUP_TRIGGER_MISSING",
                        "Packaged flow has no startup trigger.",
                        2,
                        stderr
                );
            }
            return runStartupTriggers(flow, startupTriggers, stdout, stderr);
        }

        final String triggerId = arguments.get(1);
        for (final CompiledFlow.Trigger trigger : flow.triggers()) {
            if ("cli".equals(trigger.type()) && trigger.id().equals(triggerId)) {
                return runCliTrigger(
                        flow,
                        startupTriggers,
                        trigger,
                        arguments.subList(2, arguments.size()),
                        stdin,
                        stdout,
                        stderr
                );
            }
        }
        return launcherError(
                "CLI_TRIGGER_UNKNOWN",
                "CLI trigger does not exist: " + triggerId + ".",
                2,
                stderr
        );
    }

    private static int runApplication(
            final CompiledFlow flow,
            final List<CompiledFlow.Trigger> startupTriggers,
            final PrintStream stdout,
            final PrintStream stderr
    ) {
        final ApplicationRuntime application;
        try {
            application = ApplicationRuntime.start(flow, stdout, stderr);
        } catch (final ApplicationRuntime.StartFailure failure) {
            return launcherError(
                    failure.code(),
                    failure.detail(),
                    4,
                    stderr
                );
        } catch (final RuntimeException exception) {
            return launcherError(
                    "APPLICATION_START_FAILED",
                    "Application ingress could not start.",
                    4,
                    stderr
            );
        }
        try {
            final int startupResult = runStartupTriggers(flow, startupTriggers, stdout, stderr);
            if (startupResult != 0) {
                return startupResult;
            }
            application.ready();
            application.awaitStop();
            return 0;
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 130;
        } finally {
            application.stop();
        }
    }

    private static int runCliTrigger(
            final CompiledFlow flow,
            final List<CompiledFlow.Trigger> startupTriggers,
            final CompiledFlow.Trigger trigger,
            final List<String> arguments,
            final InputStream stdin,
            final PrintStream stdout,
            final PrintStream stderr
    ) {
        final Map<String, RailixValue> config = trigger.config().values();
        final String argumentsInput = config.get("arguments") instanceof RailixValue.StringValue value
                ? value.value()
                : "";
        if (!arguments.isEmpty() && argumentsInput.isEmpty()) {
            return launcherError(
                    "CLI_ARGUMENTS_UNSUPPORTED",
                    "CLI trigger does not accept arguments: " + trigger.id() + ".",
                    2,
                    stderr
            );
        }
        if (!argumentsInput.isEmpty()) {
            final int argumentResult = validateCliArguments(arguments, stderr);
            if (argumentResult != 0) {
                return argumentResult;
            }
        }

        final RailixValue.ObjectValue stdinEvent;
        if (((RailixValue.BooleanValue) config.get("stdin")).value()) {
            if (stdin == null) {
                return cliStdinReadError(stderr);
            }
            final byte[] source;
            try {
                source = stdin.readNBytes(RailixData.DEFAULT_MAX_SOURCE_BYTES + 1);
            } catch (final IOException | RuntimeException exception) {
                return cliStdinReadError(stderr);
            }
            final RailixData.Result normalized = RailixData.normalize(RailixData.Format.JSON, source);
            if (normalized instanceof RailixData.Invalid invalid) {
                stderr.println(RailixJson.write(RailixProtocol.diagnostics(
                        "event-rejected",
                        List.of(new Diagnostic(
                                invalid.code(),
                                invalid.message(),
                                "stdin",
                                invalid.line(),
                                invalid.column()
                        ))
                )));
                return 2;
            }
            final RailixValue event = ((RailixData.Normalized) normalized).value();
            if (!(event instanceof RailixValue.ObjectValue object)) {
                stderr.println(RailixJson.write(RailixProtocol.diagnostics(
                        "event-rejected",
                        List.of(Diagnostic.atPath(
                                "FLOW_INPUT_OBJECT_REQUIRED",
                                "Flow inputs must be an object.",
                                "stdin"
                        ))
                )));
                return 2;
            }
            stdinEvent = object;
        } else {
            stdinEvent = RailixValue.object(Map.of());
        }

        final RailixValue.ObjectValue event;
        if (argumentsInput.isEmpty()) {
            event = stdinEvent;
        } else {
            if (stdinEvent.values().containsKey(argumentsInput)) {
                return eventError(
                        "CLI_ARGUMENTS_INPUT_CONFLICT",
                        "CLI stdin already supplies arguments input: " + argumentsInput + ".",
                        stderr
                );
            }
            final Map<String, RailixValue> values = new LinkedHashMap<>(stdinEvent.values());
            values.put(argumentsInput, RailixValue.array(arguments.stream()
                    .map(RailixValue::string)
                    .map(RailixValue.class::cast)
                    .toList()));
            event = RailixValue.object(values);
            if (RailixJson.write(event, RailixData.DEFAULT_MAX_SOURCE_BYTES).isEmpty()) {
                return cliEventTooLarge(stderr);
            }
        }

        final int startupResult = runStartupTriggers(flow, startupTriggers, stdout, stderr);
        return startupResult == 0
                ? printOutcome(RailixRunner.run(flow, event), stdout, stderr)
                : startupResult;
    }

    private static int runStartupTriggers(
            final CompiledFlow flow,
            final List<CompiledFlow.Trigger> triggers,
            final PrintStream stdout,
            final PrintStream stderr
    ) {
        for (final CompiledFlow.Trigger ignored : triggers) {
            final int exitCode = printOutcome(
                    RailixRunner.run(flow, RailixValue.object(Map.of())),
                    stdout,
                    stderr
            );
            if (exitCode != 0) {
                return exitCode;
            }
        }
        return 0;
    }

    private static int eventError(
            final String code,
            final String message,
            final PrintStream stderr
    ) {
        stderr.println(RailixJson.write(RailixProtocol.error("event-rejected", code, message)));
        return 2;
    }

    private static int cliEventTooLarge(final PrintStream stderr) {
        return eventError(
                "CLI_EVENT_TOO_LARGE",
                "CLI event exceeds the " + RailixData.DEFAULT_MAX_SOURCE_BYTES + "-byte limit.",
                stderr
        );
    }

    private static int validateCliArguments(
            final List<String> arguments,
            final PrintStream stderr
    ) {
        long bytes = 2;
        boolean first = true;
        final var encoder = StandardCharsets.UTF_8.newEncoder();
        for (final String argument : arguments) {
            if (argument == null) {
                return invalidCliArguments(stderr);
            }
            if (!first) {
                bytes++;
            }
            first = false;
            bytes += argument.length() + 2L;
            if (bytes > RailixData.DEFAULT_MAX_SOURCE_BYTES) {
                return cliEventTooLarge(stderr);
            }
            if (!encoder.canEncode(argument)) {
                return invalidCliArguments(stderr);
            }
        }
        return 0;
    }

    private static int invalidCliArguments(final PrintStream stderr) {
        return eventError(
                "CLI_ARGUMENTS_INVALID",
                "CLI arguments must contain valid Unicode.",
                stderr
        );
    }

    private static int cliStdinReadError(final PrintStream stderr) {
        return launcherError("CLI_STDIN_READ_FAILED", "Could not read CLI stdin.", 4, stderr);
    }

    private static String decodeUtf8(final byte[] source) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source)).toString();
    }

    private static ResourceRead readResource(final InputStream resource, final int maxBytes) {
        byte[] source = new byte[0];
        boolean failed = false;
        try {
            source = resource.readNBytes(maxBytes + 1);
        } catch (final IOException | RuntimeException exception) {
            failed = true;
        }
        return new ResourceRead(source, failed, closeResource(resource));
    }

    private static boolean closeResource(final InputStream resource) {
        if (resource == null) {
            return true;
        }
        try {
            resource.close();
            return true;
        } catch (final IOException | RuntimeException exception) {
            return false;
        }
    }

    private static int resourceCloseError(final PrintStream stderr) {
        return launcherError(
                "PACKAGED_RESOURCE_CLOSE_FAILED",
                "Could not close packaged application resources.",
                4,
                stderr
        );
    }

    private static int printLock(
            final String flowSource,
            final StepCatalog catalog,
            final PrintStream stdout,
            final PrintStream stderr
    ) {
        final CompileResult result = FlowCompiler.compile(flowSource, catalog);
        final int compileResult = printCompilation(result, stderr);
        if (compileResult != 0) {
            return compileResult;
        }
        stdout.print(((CompileResult.Compiled) result).lock());
        return 0;
    }

    private static int writeLock(
            final String flowSource,
            final StepCatalog catalog,
            final List<String> outputs,
            final PrintStream stderr
    ) {
        final CompileResult result = FlowCompiler.compile(flowSource, catalog);
        final int compileResult = printCompilation(result, stderr);
        if (compileResult != 0) {
            return compileResult;
        }
        final List<Path> paths;
        try {
            paths = outputs.stream().map(Path::of).toList();
        } catch (final InvalidPathException | NullPointerException exception) {
            return launcherError(
                    "STEP_LOCK_PATH_INVALID",
                    "Step dependency lock output path is invalid.",
                    2,
                    stderr
            );
        }
        try {
            for (final Path path : paths) {
                writeAtomically(path, ((CompileResult.Compiled) result).lock());
            }
            return 0;
        } catch (final IOException exception) {
            return launcherError(
                    "STEP_LOCK_WRITE_FAILED",
                    "Could not write Step dependency lock.",
                    4,
                    stderr
            );
        }
    }

    private static Path writeAtomically(final Path output, final String source) throws IOException {
        final Path target = output.toAbsolutePath().normalize();
        final Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("Step lock output has no parent directory.");
        }
        Files.createDirectories(parent);
        final Path temporary = Files.createTempFile(parent, ".railix-lock-", ".tmp");
        try {
            Files.writeString(temporary, source, StandardCharsets.UTF_8);
            try {
                return Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (final AtomicMoveNotSupportedException exception) {
                return Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static int printCompilation(final CompileResult result, final PrintStream stderr) {
        if (result instanceof CompileResult.Rejected rejected) {
            stderr.println(RailixJson.write(RailixProtocol.diagnostics(
                    "compile-rejected",
                    rejected.diagnostics()
            )));
            return 2;
        }
        return 0;
    }

    private static int sourceError(
            final String code,
            final String message,
            final String path,
            final PrintStream stderr
    ) {
        return printCompilation(
                new CompileResult.Rejected(List.of(Diagnostic.atPath(code, message, path))),
                stderr
        );
    }

    private static int invalidFlowSource(final PrintStream stderr) {
        return sourceError("FLOW_SOURCE_UTF8_INVALID", "Flow source is not valid UTF-8.", "$", stderr);
    }

    private static int runCommand(
            final List<String> arguments,
            final PrintStream stdout,
            final PrintStream stderr
    ) {
        if (arguments.size() != 2) {
            return usageError("run requires exactly one app directory.", stderr);
        }
        final Path appDirectory;
        try {
            appDirectory = Path.of(arguments.get(1));
        } catch (final InvalidPathException exception) {
            return launcherError("APP_DIRECTORY_INVALID", "App directory path is invalid.", 2, stderr);
        }
        if (!Files.isDirectory(appDirectory)) {
            return launcherError("APP_DIRECTORY_MISSING", "App directory does not exist.", 2, stderr);
        }
        final Path flowFile = appDirectory.resolve("railix.flow.json");
        final Path inputFile = appDirectory.resolve("input.json");
        if (!Files.isRegularFile(flowFile)) {
            return launcherError("FLOW_FILE_MISSING", "Missing railix.flow.json.", 2, stderr);
        }
        if (!Files.isRegularFile(inputFile)) {
            return launcherError("INPUT_FILE_MISSING", "Missing input.json.", 2, stderr);
        }

        final byte[] flowSource;
        final byte[] inputSource;
        try {
            flowSource = readFile(flowFile);
            inputSource = readFile(inputFile);
        } catch (final IOException | RuntimeException exception) {
            return launcherError("APP_READ_FAILED", "Could not read app files.", 4, stderr);
        }
        final String decodedFlow;
        try {
            decodedFlow = decodeUtf8(flowSource);
        } catch (final CharacterCodingException exception) {
            return invalidFlowSource(stderr);
        }
        return printOutcome(
                RailixRunner.run(decodedFlow, inputSource, StandardLibrary.catalog()),
                stdout,
                stderr
        );
    }

    private static byte[] readFile(final Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return input.readNBytes(RailixData.DEFAULT_MAX_SOURCE_BYTES + 1);
        }
    }

    private static int printOutcome(
            final RailixRunner.Outcome outcome,
            final PrintStream stdout,
            final PrintStream stderr
    ) {
        if (outcome instanceof RailixRunner.Completed completed) {
            stdout.println(RailixJson.write(completed.outputs()));
            return 0;
        }
        final RailixRunner.Rejected rejected = (RailixRunner.Rejected) outcome;
        stderr.println(RailixJson.write(rejected.payload()));
        return rejected.exitCode();
    }

    private static int creatorCommand(
            final List<String> arguments,
            final PrintStream stdout,
            final PrintStream stderr
    ) {
        final PortResult port = parsePort(arguments);
        if (port instanceof InvalidPort invalid) {
            return usageError(invalid.message(), stderr);
        }
        return startCreator(((ValidPort) port).value(), StandardLibrary.catalog(), stdout, stderr);
    }

    private static int startCreator(
            final int port,
            final StepCatalog catalog,
            final PrintStream stdout,
            final PrintStream stderr
    ) {
        try (CreatorServer server = CreatorServer.start(port, catalog)) {
            stdout.println(RailixJson.write(RailixProtocol.creatorReady(server.baseUri().toString())));
            server.awaitClose();
            return 0;
        } catch (final IOException exception) {
            return launcherError("CREATOR_START_FAILED", "Could not start Creator.", 4, stderr);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return launcherError("CREATOR_INTERRUPTED", "Creator was interrupted.", 4, stderr);
        }
    }

    private static PortResult parsePort(final List<String> arguments) {
        if (arguments.size() == 1) {
            return new ValidPort(DEFAULT_CREATOR_PORT);
        }
        if (arguments.size() != 3 || !"--port".equals(arguments.get(1))) {
            return new InvalidPort("creator accepts only --port <1-65535>.");
        }
        try {
            final int port = Integer.parseInt(arguments.get(2));
            return port >= 1 && port <= 65_535
                    ? new ValidPort(port)
                    : new InvalidPort("Creator port must be between 1 and 65535.");
        } catch (final NumberFormatException exception) {
            return new InvalidPort("Creator port must be an integer.");
        }
    }

    private static int usageError(final String message, final PrintStream stderr) {
        stderr.println(message);
        stderr.println(USAGE);
        return 2;
    }

    private static int applicationUsageError(final PrintStream stderr) {
        stderr.println(APPLICATION_USAGE);
        return 2;
    }

    private static int launcherError(
            final String code,
            final String message,
            final int exitCode,
            final PrintStream stderr
    ) {
        stderr.println(RailixJson.write(RailixProtocol.error("launcher-rejected", code, message)));
        return exitCode;
    }

    private sealed interface PortResult permits ValidPort, InvalidPort {
    }

    private record ValidPort(int value) implements PortResult {
    }

    private record InvalidPort(String message) implements PortResult {
    }

    private record ResourceRead(byte[] source, boolean failed, boolean closed) {
    }
}
