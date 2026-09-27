package dev.nanonative.railix.core.step;

import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Authoring-only typed checks and batched, bounded execution of explicitly registered bundle validators. */
public final class StepValidation {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int OUTPUT_LIMIT = 1024 * 1024;
    private static final String WORKER = "RailixConfigurationValidation";

    private StepValidation() { }

    /** One shape-checked, resolved configuration value and its compiler diagnostic location. */
    public record Request(StepDefinition.JsonInput input, RailixValue value, String path) { }

    private record Location(String step, String input) { }
    private record Check(Request request, Location location) { }

    /**
     * Checks resolved configuration values, grouping external calls by definition provider. Directly
     * constructed catalogs without a provider execute their caller-owned functions in-process.
     * Registered providers run in a separate JVM with a ten-second batch deadline, not a security sandbox.
     *
     * @param catalog explicit, integrity-checked definitions and providers
     * @param requests values selected by the compiler, excluding absent optional inputs
     * @return deterministic field diagnostics; empty when all checks accept
     */
    public static List<Diagnostic> validate(final StepCatalog catalog, final List<Request> requests) {
        if (requests.isEmpty()) return List.of();
        final Map<StepDefinition.JsonInput, Location> locations = new IdentityHashMap<>();
        catalog.definitions().forEach(definition -> inputs(definition).forEach((key, input) ->
                locations.put(input, new Location(definition.id(), key))));
        final Map<String, List<Check>> batches = new LinkedHashMap<>();
        final List<Diagnostic> diagnostics = new ArrayList<>();
        for (final Request request : requests) {
            final var rule = request.input().validations().getFirst();
            final Optional<String> typeProblem = rule.typeProblem(request.value());
            if (typeProblem.isPresent()) {
                diagnostics.add(Diagnostic.atPath("PROJECT_INPUT_VALIDATION_TYPE", typeProblem.get(), request.path()));
                continue;
            }
            final Location location = locations.get(request.input());
            final var provider = catalog.validationProvider(location.step());
            if (provider.isEmpty()) {
                evaluate(request.input(), request.value()).ifPresent(problem -> diagnostics.add(at(problem, request.path())));
            } else {
                final String key = provider.get().classEntry() + provider.get().artifacts().stream()
                        .map(StepCatalog.Artifact::digest).toList();
                batches.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Check(request, location));
            }
        }
        // No user code is started while a known type error already prevents compilation.
        if (!diagnostics.isEmpty()) return List.copyOf(diagnostics);
        for (final List<Check> checks : batches.values()) {
            final var provider = catalog.validationProvider(checks.getFirst().location().step()).orElseThrow();
            diagnostics.addAll(execute(catalog, provider, checks));
        }
        return List.copyOf(diagnostics);
    }

    /** Enumerates validated JSON declarations, including owned option inputs, in contract order. */
    public static Map<String, StepDefinition.JsonInput> inputs(final StepDefinition definition) {
        final Map<String, StepDefinition.JsonInput> inputs = new LinkedHashMap<>();
        collect(definition.inputs(), "inputs", inputs);
        return java.util.Collections.unmodifiableMap(inputs);
    }

    private static void collect(final List<StepDefinition.Field> fields, final String path,
                                final Map<String, StepDefinition.JsonInput> target) {
        for (int index = 0; index < fields.size(); index++) {
            final String key = path + "[" + index + "]";
            final var input = fields.get(index).input();
            if (input instanceof StepDefinition.JsonInput json && !json.validations().isEmpty()) target.put(key, json);
            final List<StepDefinition.Option> options = switch (input) {
                case StepDefinition.OptionsInput value -> value.options();
                case StepDefinition.CandidatesInput value -> value.options();
                case StepDefinition.MatcherGroupsInput value -> value.options();
                default -> List.of();
            };
            for (int option = 0; option < options.size(); option++) {
                collect(options.get(option).inputs(), key + ".options[" + option + "].inputs", target);
            }
        }
    }

    private static Optional<Diagnostic> evaluate(final StepDefinition.JsonInput input, final RailixValue value) {
        final var rule = input.validations().getFirst();
        if (rule.functions().isEmpty()) return Optional.of(failed("Configuration validator has no executable function."));
        try {
            final Optional<String> message = rule.functions().getFirst().apply(value);
            if (message == null || message.filter(String::isBlank).isPresent()) {
                return Optional.of(failed("Configuration validator must return Optional.empty() or a nonblank message."));
            }
            return message.map(text -> Diagnostic.atPath("PROJECT_INPUT_VALIDATION_INVALID", text, ""));
        } catch (final RuntimeException exception) {
            return Optional.of(failed("Configuration validator failed: " + exception.getClass().getSimpleName() + "."));
        }
    }

    private static List<Diagnostic> execute(final StepCatalog catalog, final StepCatalog.Implementation provider,
                                            final List<Check> checks) {
        Path directory = null;
        Process process = null;
        final Map<Long, ProcessHandle> children = new LinkedHashMap<>();
        final List<Diagnostic> diagnostics = new ArrayList<>();
        try {
            final Optional<String> signatureProblem = provider.definitionsProblem();
            if (signatureProblem.isPresent()) return List.of(at(failed(signatureProblem.get()), checks.getFirst().request().path()));
            directory = Files.createTempDirectory("railix-validation-");
            final Path input = directory.resolve("input.json");
            final Path output = directory.resolve("output.json");
            Files.write(directory.resolve(WORKER + ".class"), worker(provider.classEntry()));
            final Map<String, String> contracts = new LinkedHashMap<>();
            final List<RailixValue> values = checks.stream().<RailixValue>map(check -> RailixValue.object(Map.of(
                    "step", RailixValue.string(check.location().step()),
                    "input", RailixValue.string(check.location().input()),
                    "contract", RailixValue.string(contracts.computeIfAbsent(check.location().step(),
                            id -> StepContractJson.write(catalog.find(id).orElseThrow()))),
                    "value", check.request().value()
            ))).toList();
            Files.writeString(input, RailixJson.write(RailixValue.array(values)));
            final String separator = java.io.File.pathSeparator;
            final String platform = provider.platformOwned() ? System.getProperty("java.class.path")
                    : Path.of(StepValidation.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            final String classpath = directory + separator + platform
                    + provider.artifacts().stream().map(artifact -> separator + artifact.path()).collect(java.util.stream.Collectors.joining());
            final String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
            process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-Xmx128m", "-cp", classpath, WORKER, input.toString(), output.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            final long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (true) {
                process.descendants().forEach(child -> children.put(child.pid(), child));
                if (process.waitFor(50, TimeUnit.MILLISECONDS)) break;
                if (System.nanoTime() >= deadline) {
                    diagnostics.add(at(failed("Configuration validation exceeded its ten-second time limit."), checks.getFirst().request().path()));
                    break;
                }
            }
            if (diagnostics.isEmpty()) {
                if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
                    diagnostics.add(at(failed("Configuration validation process failed or returned no bounded result."), checks.getFirst().request().path()));
                } else {
                    final byte[] bytes;
                    try (var response = Files.newInputStream(output)) {
                        bytes = response.readNBytes(OUTPUT_LIMIT + 1);
                    }
                    if (bytes.length > OUTPUT_LIMIT) {
                        diagnostics.add(at(failed("Configuration validation response exceeds its size limit."), checks.getFirst().request().path()));
                    } else {
                        diagnostics.addAll(response(new String(bytes, StandardCharsets.UTF_8), checks));
                    }
                }
            }
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            diagnostics.add(at(failed("Configuration validation was interrupted."), checks.getFirst().request().path()));
        } catch (final IOException | java.net.URISyntaxException exception) {
            diagnostics.add(at(failed("Cannot complete configuration validation: " + exception.getMessage()), checks.getFirst().request().path()));
        } catch (final RuntimeException exception) {
            diagnostics.add(at(failed("Cannot manage configuration validation process: "
                    + exception.getClass().getSimpleName() + "."), checks.getFirst().request().path()));
        } finally {
            if (process != null) {
                try {
                    process.descendants().forEach(child -> children.put(child.pid(), child));
                    children.values().forEach(ProcessHandle::destroyForcibly);
                } catch (final RuntimeException exception) {
                    diagnostics.add(at(failed("Cannot inspect or terminate validation child processes."), checks.getFirst().request().path()));
                } finally {
                    if (process.isAlive()) process.destroyForcibly();
                }
                // Preserve cancellation, but finish bounded cleanup before returning to the caller.
                boolean interrupted = Thread.interrupted();
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (process.isAlive() && System.nanoTime() < deadline) {
                    try {
                        process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    } catch (final InterruptedException exception) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
                if (process.isAlive()) {
                    diagnostics.add(at(failed("Validation process cleanup did not complete."), checks.getFirst().request().path()));
                }
            }
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (final IOException exception) {
                    diagnostics.add(at(failed("Cannot remove validation temporary files: " + directory), checks.getFirst().request().path()));
                }
            }
        }
        return List.copyOf(diagnostics);
    }

    private static List<Diagnostic> response(final String source, final List<Check> checks) {
        final var parsed = RailixJson.parse(source);
        if (!(parsed instanceof RailixJson.Parsed valid)
                || !(valid.value() instanceof RailixValue.ArrayValue results) || results.values().size() != checks.size()) {
            return List.of(at(failed("Invalid validator response."), checks.getFirst().request().path()));
        }
        final List<Diagnostic> diagnostics = new ArrayList<>();
        for (int index = 0; index < checks.size(); index++) {
            final RailixValue result = results.values().get(index);
            if (result.equals(RailixValue.nullValue())) continue;
            if (!(result instanceof RailixValue.ObjectValue object)
                    || !(object.values().get("code") instanceof RailixValue.StringValue code)
                    || !(object.values().get("message") instanceof RailixValue.StringValue message)
                    || !List.of("PROJECT_INPUT_VALIDATION_INVALID", "PROJECT_INPUT_VALIDATION_FAILED").contains(code.value())
                    || message.value().isBlank()) {
                return List.of(at(failed("Invalid validator diagnostic."), checks.get(index).request().path()));
            }
            diagnostics.add(Diagnostic.atPath(code.value(), message.value(), checks.get(index).request().path()));
        }
        return List.copyOf(diagnostics);
    }

    private static byte[] worker(final String providerEntry) {
        final ClassDesc provider = ClassDesc.ofDescriptor("L" + providerEntry.substring(0, providerEntry.length() - 6) + ";");
        return ClassFile.of().build(ClassDesc.of(WORKER), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL)
                .withMethodBody("main", MethodTypeDesc.ofDescriptor("([Ljava/lang/String;)V"),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code
                                .invokestatic(provider, "definitions", MethodTypeDesc.ofDescriptor("()Ljava/util/List;"))
                                .aload(0)
                                .invokestatic(ClassDesc.of(StepValidation.class.getName()), "run",
                                        MethodTypeDesc.ofDescriptor("(Ljava/util/List;[Ljava/lang/String;)I"))
                                .invokestatic(ClassDesc.of("java.lang.System"), "exit", MethodTypeDesc.ofDescriptor("(I)V"))
                                .return_()));
    }

    /**
     * Worker entrypoint used by generated direct calls, not by the application runtime.
     *
     * @param definitions fresh definitions from the explicitly registered provider
     * @param arguments request and response file paths owned by the parent validation process
     * @return zero after writing the complete response
     * @throws IOException when the request or response cannot be read/written
     */
    public static int run(final List<StepDefinition> definitions, final String[] arguments) throws IOException {
        final Map<String, StepDefinition> catalog = new LinkedHashMap<>();
        for (final var definition : definitions) {
            if (catalog.putIfAbsent(definition.id(), definition) != null) throw new IOException("Duplicate validation definition.");
        }
        final var parsed = RailixJson.parse(Files.readString(Path.of(arguments[0])));
        if (!(parsed instanceof RailixJson.Parsed valid) || !(valid.value() instanceof RailixValue.ArrayValue requests)) {
            throw new IOException("Invalid validation requests.");
        }
        final Map<RailixValue, RailixValue> memo = new LinkedHashMap<>();
        final Map<String, String> contracts = new LinkedHashMap<>();
        final Map<String, Map<String, StepDefinition.JsonInput>> declarations = new LinkedHashMap<>();
        catalog.forEach((id, definition) -> {
            contracts.put(id, StepContractJson.write(definition));
            declarations.put(id, inputs(definition));
        });
        final List<RailixValue> results = new ArrayList<>();
        for (final RailixValue value : requests.values()) {
            results.add(memo.computeIfAbsent(value, item -> {
                final var request = (RailixValue.ObjectValue) item;
                final String id = ((RailixValue.StringValue) request.values().get("step")).value();
                final String contract = ((RailixValue.StringValue) request.values().get("contract")).value();
                final String field = ((RailixValue.StringValue) request.values().get("input")).value();
                final Optional<Diagnostic> problem;
                if (!contract.equals(contracts.get(id))) {
                    problem = Optional.of(failed("Validation provider does not match the installed Step contract."));
                } else {
                    final var input = declarations.get(id).get(field);
                    problem = input == null ? Optional.of(failed("Validation declaration is missing."))
                            : evaluate(input, request.values().get("value"));
                }
                return problem.<RailixValue>map(issue -> RailixValue.object(Map.of(
                        "code", RailixValue.string(issue.code()), "message", RailixValue.string(issue.message())
                ))).orElseGet(RailixValue::nullValue);
            }));
        }
        Files.writeString(Path.of(arguments[1]), RailixJson.write(RailixValue.array(results)));
        return 0;
    }

    private static Diagnostic failed(final String message) {
        return Diagnostic.atPath("PROJECT_INPUT_VALIDATION_FAILED", message, "");
    }

    private static Diagnostic at(final Diagnostic diagnostic, final String path) {
        return Diagnostic.atPath(diagnostic.code(), diagnostic.message(), path);
    }
}
