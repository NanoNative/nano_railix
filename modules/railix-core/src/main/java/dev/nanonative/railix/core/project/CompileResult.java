package dev.nanonative.railix.core.project;

import dev.nanonative.railix.core.step.StepCatalog;

import java.util.List;

/** A project either compiles completely or returns deterministic diagnostics. */
public sealed interface CompileResult permits CompileResult.Compiled, CompileResult.Rejected {
    record Compiled(
            String source,
            String applicationClass,
            String productionApplicationSource,
            String developmentApplicationSource,
            String developmentLauncherClass,
            String developmentLauncherSource,
            List<StepCatalog.Implementation> applicationDependencies
    ) implements CompileResult {
        public Compiled {
            if (source == null || source.isBlank()
                    || applicationClass == null || applicationClass.isBlank()
                    || productionApplicationSource == null || productionApplicationSource.isBlank()
                    || developmentApplicationSource == null || developmentApplicationSource.isBlank()
                    || developmentLauncherClass == null || developmentLauncherClass.isBlank()
                    || developmentLauncherSource == null || developmentLauncherSource.isBlank()
                    || applicationDependencies == null) {
                throw new IllegalArgumentException("Compiled application output must be complete.");
            }
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
}
