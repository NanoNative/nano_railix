package dev.nanonative.railix.core.project;

import dev.nanonative.railix.core.step.StepCatalog;

import java.util.Collections;
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
