package dev.nanonative.railix.core.project;

import java.util.List;

/** A project either compiles completely or returns deterministic diagnostics. */
public sealed interface CompileResult permits CompileResult.Compiled, CompileResult.Rejected {
    record Compiled(CompiledProject project, String source, String executableSource) implements CompileResult {
    }

    record Rejected(List<Diagnostic> diagnostics) implements CompileResult {
        public Rejected {
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
