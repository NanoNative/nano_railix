package dev.nanonative.railix.core.flow;

import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Canonical lock for the exact Step definitions referenced by one compiled flow. */
final class StepLock {
    private static final String FLOW_DOMAIN = "railix-flow-v1\n";
    private static final String STEP_DOMAIN = "railix-step-contract-v3\n";
    private static final Set<String> ROOT_FIELDS = Set.of("flow", "format", "steps");
    private static final Set<String> STEP_FIELDS = Set.of("contract", "id", "version");

    private final String flow;
    private final Map<String, LockedStep> steps;
    private final String source;
    private final boolean canonical;

    private StepLock(
            final String flow,
            final Map<String, LockedStep> steps,
            final String source,
            final boolean canonical
    ) {
        this.flow = flow;
        this.steps = Collections.unmodifiableMap(new TreeMap<>(steps));
        this.source = source;
        this.canonical = canonical;
    }

    static StepLock derive(
            final String canonicalFlow,
            final Collection<StepDefinition> referencedDefinitions
    ) {
        final Map<String, LockedStep> closure = new TreeMap<>();
        for (final StepDefinition definition : referencedDefinitions) {
            closure.put(definition.id(), locked(definition));
        }
        final String flow = digest(FLOW_DOMAIN, canonicalFlow);
        final String source = source(flow, closure);
        return new StepLock(flow, closure, source, true);
    }

    static List<Diagnostic> verify(final String expectedSource, final String actualSource) {
        if (expectedSource == null) {
            return List.of(Diagnostic.atPath(
                    "STEP_LOCK_REQUIRED",
                    "A Step dependency lock is required.",
                    "lock"
            ));
        }
        if (expectedSource.length() > RailixData.MAX_SOURCE_BYTES
                || expectedSource.getBytes(StandardCharsets.UTF_8).length > RailixData.MAX_SOURCE_BYTES) {
            return List.of(Diagnostic.atPath(
                    "STEP_LOCK_SOURCE_TOO_LARGE",
                    "Step dependency lock exceeds the " + RailixData.MAX_SOURCE_BYTES + "-byte limit.",
                    "lock"
            ));
        }
        if (expectedSource.isBlank()) {
            return List.of(Diagnostic.atPath(
                    "STEP_LOCK_REQUIRED",
                    "A Step dependency lock is required.",
                    "lock"
            ));
        }
        final ReadResult expectedResult = read(expectedSource);
        if (expectedResult instanceof Invalid invalid) {
            return List.of(invalid.diagnostic());
        }
        final ReadResult actualResult = read(actualSource);
        if (actualResult instanceof Invalid invalid) {
            return List.of(invalid.diagnostic());
        }
        final StepLock expected = ((Parsed) expectedResult).lock();
        final StepLock actual = ((Parsed) actualResult).lock();
        if (!expected.flow.equals(actual.flow)) {
            return List.of(Diagnostic.atPath(
                    "STEP_LOCK_FLOW_MISMATCH",
                    "Step dependency lock does not match the authored flow.",
                    "flow"
            ));
        }

        final List<Diagnostic> diagnostics = new java.util.ArrayList<>();
        for (final String id : actual.steps.keySet()) {
            if (!expected.steps.containsKey(id)) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_LOCK_DEPENDENCY_MISSING",
                        "Step dependency is missing from the lock: " + id,
                        "steps." + id
                ));
            }
        }
        for (final String id : expected.steps.keySet()) {
            if (!actual.steps.containsKey(id)) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_LOCK_DEPENDENCY_UNEXPECTED",
                        "Lock contains an unreferenced Step dependency: " + id,
                        "steps." + id
                ));
            }
        }
        if (!diagnostics.isEmpty()) {
            return List.copyOf(diagnostics);
        }

        for (final Map.Entry<String, LockedStep> entry : actual.steps.entrySet()) {
            final LockedStep expectedStep = expected.steps.get(entry.getKey());
            if (!expectedStep.version().equals(entry.getValue().version())) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_LOCK_VERSION_MISMATCH",
                        "Locked Step version does not match: " + entry.getKey(),
                        "steps." + entry.getKey() + ".version"
                ));
            } else if (!expectedStep.contract().equals(entry.getValue().contract())) {
                diagnostics.add(Diagnostic.atPath(
                        "STEP_LOCK_CONTRACT_MISMATCH",
                        "Locked Step contract does not match: " + entry.getKey(),
                        "steps." + entry.getKey() + ".contract"
                ));
            }
        }
        if (!diagnostics.isEmpty()) {
            return List.copyOf(diagnostics);
        }
        return expected.canonical ? List.of() : List.of(Diagnostic.atPath(
                "STEP_LOCK_NON_CANONICAL",
                "Step dependency lock must use canonical JSON with one final newline.",
                "$"
        ));
    }

    String source() {
        return source;
    }

    private static ReadResult read(final String source) {
        final RailixJson.Result parsed = RailixJson.parse(source);
        if (parsed instanceof RailixJson.Invalid invalid) {
            return new Invalid(new Diagnostic(
                    "STEP_LOCK_JSON_INVALID",
                    invalid.message(),
                    "$",
                    invalid.line(),
                    invalid.column()
            ));
        }
        final RailixValue value = ((RailixJson.Parsed) parsed).value();
        if (!(value instanceof RailixValue.ObjectValue root)
                || !root.values().keySet().equals(ROOT_FIELDS)) {
            return invalid("$");
        }
        final RailixValue format = root.values().get("format");
        if (!(format instanceof RailixValue.NumberValue number)) {
            return invalid("format");
        }
        if (number.value().compareTo(BigDecimal.ONE) != 0) {
            return new Invalid(Diagnostic.atPath(
                    "STEP_LOCK_FORMAT_UNSUPPORTED",
                    "Unsupported Step dependency lock format: " + number.value().toPlainString() + ".",
                    "format"
            ));
        }
        final RailixValue flow = root.values().get("flow");
        if (!(flow instanceof RailixValue.StringValue flowValue) || !validDigest(flowValue.value())) {
            return invalid("flow");
        }
        final RailixValue stepValues = root.values().get("steps");
        if (!(stepValues instanceof RailixValue.ArrayValue array)) {
            return invalid("steps");
        }
        final Map<String, LockedStep> steps = new TreeMap<>();
        for (int index = 0; index < array.values().size(); index++) {
            final RailixValue stepValue = array.values().get(index);
            final String path = "steps[" + index + "]";
            if (!(stepValue instanceof RailixValue.ObjectValue step)
                    || !step.values().keySet().equals(STEP_FIELDS)) {
                return invalid(path);
            }
            final RailixValue id = step.values().get("id");
            final RailixValue version = step.values().get("version");
            final RailixValue contract = step.values().get("contract");
            if (!(id instanceof RailixValue.StringValue idValue) || idValue.value().isBlank()
                    || !(version instanceof RailixValue.StringValue versionValue) || versionValue.value().isBlank()) {
                return invalid(path);
            }
            if (!(contract instanceof RailixValue.StringValue contractValue)
                    || !validDigest(contractValue.value())) {
                return invalid(path + ".contract");
            }
            if (steps.putIfAbsent(idValue.value(), new LockedStep(
                    idValue.value(),
                    versionValue.value(),
                    contractValue.value()
            )) != null) {
                return invalid(path + ".id");
            }
        }
        final String canonicalSource = source(flowValue.value(), steps);
        return new Parsed(new StepLock(
                flowValue.value(),
                steps,
                canonicalSource,
                source.equals(canonicalSource)
        ));
    }

    private static Invalid invalid(final String path) {
        return new Invalid(Diagnostic.atPath(
                "STEP_LOCK_INVALID",
                "Step dependency lock has an invalid shape.",
                path
        ));
    }

    private static boolean validDigest(final String value) {
        if (value.length() != 71 || !value.startsWith("sha256:")) {
            return false;
        }
        for (int index = 7; index < value.length(); index++) {
            final char digit = value.charAt(index);
            if ((digit < '0' || digit > '9') && (digit < 'a' || digit > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static String source(final String flow, final Map<String, LockedStep> steps) {
        return RailixJson.write(RailixValue.object(Map.of(
                "format", RailixValue.number(1),
                "flow", RailixValue.string(flow),
                "steps", RailixValue.array(steps.values().stream()
                        .<RailixValue>map(step -> RailixValue.object(Map.of(
                                "id", RailixValue.string(step.id()),
                                "version", RailixValue.string(step.version()),
                                "contract", RailixValue.string(step.contract())
                        )))
                        .toList())
        ))) + "\n";
    }

    private static LockedStep locked(final StepDefinition definition) {
        final String contract = RailixJson.write(RailixValue.object(Map.of(
                "id", RailixValue.string(definition.id()),
                "version", RailixValue.string(definition.version()),
                "kind", RailixValue.string(definition.kind().name().toLowerCase(Locale.ROOT)),
                "inputs", ports(definition.inputs()),
                "outputs", ports(definition.outputs()),
                "config", config(definition.config()),
                "outcomes", RailixValue.array(definition.outcomes().stream()
                        .sorted()
                        .<RailixValue>map(RailixValue::string)
                        .toList())
        )));
        return new LockedStep(
                definition.id(),
                definition.version(),
                digest(STEP_DOMAIN, contract)
        );
    }

    private static RailixValue.ArrayValue ports(final List<StepDefinition.Port> ports) {
        return RailixValue.array(ports.stream()
                .sorted(Comparator.comparing(StepDefinition.Port::name))
                .<RailixValue>map(port -> RailixValue.object(Map.of(
                        "name", RailixValue.string(port.name()),
                        "shape", RailixValue.string(shape(port.shape().orElseThrow()))
                )))
                .toList());
    }

    private static RailixValue.ArrayValue config(final List<StepDefinition.Config> config) {
        return RailixValue.array(config.stream()
                .sorted(Comparator.comparing(StepDefinition.Config::name))
                .<RailixValue>map(StepLock::config)
                .toList());
    }

    private static RailixValue config(final StepDefinition.Config config) {
        final Map<String, RailixValue> value = new LinkedHashMap<>();
        value.put("name", RailixValue.string(config.name()));
        value.put("shape", RailixValue.string(shape(config.shape().orElseThrow())));
        value.put("required", RailixValue.bool(config.required()));
        config.format().ifPresent(format ->
                value.put("format", RailixValue.string(format.wireName()))
        );
        config.defaultValue().ifPresent(defaultValue -> value.put("default", defaultValue));
        return RailixValue.object(value);
    }

    private static String shape(final ValueShape shape) {
        return shape.name().toLowerCase(Locale.ROOT);
    }

    private static String digest(final String domain, final String source) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((domain + source).getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime does not provide SHA-256.", exception);
        }
    }

    private sealed interface ReadResult permits Parsed, Invalid {
    }

    private record Parsed(StepLock lock) implements ReadResult {
    }

    private record Invalid(Diagnostic diagnostic) implements ReadResult {
    }

    private record LockedStep(String id, String version, String contract) {
    }
}
