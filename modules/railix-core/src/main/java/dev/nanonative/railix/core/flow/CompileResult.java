package dev.nanonative.railix.core.flow;

import java.util.List;

/** A flow either compiles completely or returns explicit diagnostics. */
public sealed interface CompileResult permits CompileResult.Compiled, CompileResult.Rejected {
    record Compiled(CompiledFlow flow, String source, String lock) implements CompileResult {
    }

    record Rejected(List<Diagnostic> diagnostics) implements CompileResult {
        public Rejected {
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
