package dev.nanonative.railix.core.project;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.util.Sha256;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A project either compiles completely or returns deterministic diagnostics. */
public sealed interface CompileResult permits CompileResult.Compiled, CompileResult.Rejected {
    record Compiled(
            String source,
            String applicationClass,
            String productionApplicationSource,
            String developmentApplicationSource,
            String developmentLauncherClass,
            String developmentLauncherSource,
            Map<String, String> developmentResources,
            List<StepCatalog.Implementation> applicationDependencies
    ) implements CompileResult {
        public Compiled {
            if (source == null || source.isBlank()
                    || applicationClass == null || applicationClass.isBlank()
                    || productionApplicationSource == null || productionApplicationSource.isBlank()
                    || developmentApplicationSource == null || developmentApplicationSource.isBlank()
                    || developmentLauncherClass == null || developmentLauncherClass.isBlank()
                    || developmentLauncherSource == null || developmentLauncherSource.isBlank()
                    || developmentResources == null
                    || developmentResources.entrySet().stream().anyMatch(CompileResult::invalidResource)
                    || applicationDependencies == null) {
                throw new IllegalArgumentException("Compiled application output must be complete.");
            }
            developmentResources = Collections.unmodifiableMap(new TreeMap<>(developmentResources));
            applicationDependencies = List.copyOf(applicationDependencies);
        }

        /** @return the canonical functional project revision, including the used provider identities */
        public String functionalIdentity() {
            if (!(RailixJson.parse(source) instanceof RailixJson.Parsed parsed)
                    || !(parsed.value() instanceof RailixValue.ObjectValue graph)
                    || !(graph.values().get("nodes") instanceof RailixValue.ArrayValue nodes)) {
                throw new IllegalArgumentException("Compiled project source is invalid.");
            }
            final var functionalNodes = nodes.values().stream()
                    .map(RailixValue.ObjectValue.class::cast)
                    .<RailixValue>map(value -> {
                        final var node = new LinkedHashMap<>(value.values());
                        node.remove("examples");
                        return RailixValue.object(node);
                    })
                    .toList();
            final var fields = new LinkedHashMap<>(graph.values());
            fields.put("nodes", RailixValue.array(functionalNodes));

            final var hash = Sha256.newDigest();
            hash.update(RailixJson.write(RailixValue.object(fields)).getBytes(StandardCharsets.UTF_8));
            hash.update((byte) 0);
            applicationDependencies.stream()
                    .sorted(Comparator.comparing(StepCatalog.Implementation::className))
                    .forEach(implementation -> {
                        hash.update((byte) 0);
                        hash.update(implementation.className().getBytes(StandardCharsets.UTF_8));
                        hash.update((byte) 0);
                        hash.update(implementation.contractDigest().getBytes(StandardCharsets.UTF_8));
                        implementation.artifacts().stream()
                                .map(StepCatalog.Artifact::digest)
                                .sorted()
                                .forEach(digest -> {
                                    hash.update((byte) 0);
                                    hash.update(digest.getBytes(StandardCharsets.UTF_8));
                                });
                    });
            return "sha256-" + Sha256.finishHex(hash);
        }

        /**
         * @param coreRuntimeDigest fingerprint of the core classes packaged with the application
         * @return the platform-independent identity for peers executing this production build
         */
        public String logicalBuildId(final String coreRuntimeDigest) {
            return "sha256-" + Sha256.hexDigest("railix-execution-v2\n" + functionalIdentity() + "\n" + coreRuntimeDigest
                    + "\n" + applicationClass + "\n" + productionApplicationSource);
        }
    }

    record Rejected(List<Diagnostic> diagnostics) implements CompileResult {
        public Rejected {
            if (diagnostics == null || diagnostics.isEmpty()) {
                throw new IllegalArgumentException("Rejected compilation must contain diagnostics.");
            }
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private static boolean invalidResource(final Map.Entry<String, String> entry) {
        final String name = entry.getKey();
        return name == null || name.isBlank() || name.startsWith("/") || name.contains("\\")
                || name.indexOf('\0') >= 0 || entry.getValue() == null
                || java.util.Arrays.stream(name.split("/", -1))
                .anyMatch(part -> part.isEmpty() || ".".equals(part) || "..".equals(part));
    }
}
