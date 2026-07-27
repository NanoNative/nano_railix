package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.stdlib.StandardLibrary;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Canonical flow-and-input runner for shipped and explicitly registered Steps. */
public final class RailixRunner {
    private RailixRunner() {
    }

    static Outcome run(final String flowSource, final String inputSource) {
        return run(flowSource, inputSource, StandardLibrary.catalog());
    }

    /**
     * Compiles and runs one flow against exactly the supplied trusted Step dependencies.
     *
     * @param flowSource canonical flow JSON source, limited by the compiler to 1 MiB
     * @param inputSource invocation input JSON source, limited to 1 MiB; Java null is rejected
     * @param catalog explicit trusted Step dependencies
     * @return a completed output or deterministic rejection
     */
    public static Outcome run(
            final String flowSource,
            final String inputSource,
            final StepCatalog catalog
    ) {
        final CompileResult compilation = FlowCompiler.compile(flowSource, catalog);
        if (compilation instanceof CompileResult.Rejected rejected) {
            return new Rejected(2, RailixProtocol.diagnostics("compile-rejected", rejected.diagnostics()));
        }
        if (inputSource != null && inputSource.length() > RailixData.DEFAULT_MAX_SOURCE_BYTES) {
            return inputRejected(Diagnostic.atPath(
                    "INPUT_SOURCE_TOO_LARGE",
                    "Input source exceeds the " + RailixData.DEFAULT_MAX_SOURCE_BYTES + "-byte limit.",
                    "$"
            ));
        }
        if (inputSource != null && !StandardCharsets.UTF_8.newEncoder().canEncode(inputSource)) {
            return inputRejected(new Diagnostic(
                    "INPUT_JSON_INVALID",
                    "Unpaired Unicode surrogate is not allowed in JSON strings.",
                    "$",
                    1,
                1
            ));
        }
        return admit(
                ((CompileResult.Compiled) compilation).flow(),
                inputSource == null ? null : inputSource.getBytes(StandardCharsets.UTF_8)
        );
    }

    static Outcome run(
            final String flowSource,
            final byte[] inputSource,
            final StepCatalog catalog
    ) {
        final CompileResult compilation = FlowCompiler.compile(flowSource, catalog);
        if (compilation instanceof CompileResult.Rejected rejected) {
            return new Rejected(2, RailixProtocol.diagnostics("compile-rejected", rejected.diagnostics()));
        }
        return admit(((CompileResult.Compiled) compilation).flow(), inputSource);
    }

    private static Outcome admit(final CompiledFlow flow, final byte[] inputSource) {
        final RailixData.Result normalized = RailixData.normalize(RailixData.Format.JSON, inputSource);
        if (normalized instanceof RailixData.Invalid invalid) {
            return inputRejected(inputDiagnostic(invalid));
        }
        final RailixValue input = ((RailixData.Normalized) normalized).value();
        if (!(input instanceof RailixValue.ObjectValue objectInput)) {
            return inputRejected(
                    Diagnostic.atPath("FLOW_INPUT_OBJECT_REQUIRED", "Flow inputs must be an object.", "inputs")
            );
        }

        return run(flow, objectInput);
    }

    private static Diagnostic inputDiagnostic(final RailixData.Invalid invalid) {
        final String code = switch (invalid.code()) {
            case "DATA_SOURCE_REQUIRED" -> "INPUT_SOURCE_REQUIRED";
            case "DATA_SOURCE_TOO_LARGE" -> "INPUT_SOURCE_TOO_LARGE";
            case "DATA_SOURCE_UTF8_INVALID" -> "INPUT_SOURCE_UTF8_INVALID";
            case "DATA_BOM_UNSUPPORTED" -> "INPUT_BOM_UNSUPPORTED";
            case "DATA_DEPTH_EXCEEDED" -> "INPUT_DEPTH_EXCEEDED";
            case "DATA_NUMBER_LIMIT_EXCEEDED" -> "INPUT_NUMBER_LIMIT_EXCEEDED";
            default -> "INPUT_JSON_INVALID";
        };
        final String message = "DATA_SOURCE_TOO_LARGE".equals(invalid.code())
                ? "Input source exceeds the " + RailixData.DEFAULT_MAX_SOURCE_BYTES + "-byte limit."
                : invalid.message();
        return new Diagnostic(code, message, "$", invalid.line(), invalid.column());
    }

    private static Rejected inputRejected(final Diagnostic diagnostic) {
        return new Rejected(2, RailixProtocol.diagnostics("input-rejected", List.of(diagnostic)));
    }

    static Outcome run(
            final CompiledFlow flow,
            final RailixValue.ObjectValue input
    ) {
        return outcome(flow.run(input));
    }

    static Outcome runStepEvent(
            final CompiledFlow flow,
            final String stepId,
            final RailixValue.ObjectValue input
    ) {
        return outcome(flow.runStepEvent(stepId, input));
    }

    private static Outcome outcome(final RunResult run) {
        return switch (run) {
            case RunResult.Succeeded succeeded -> new Completed(
                    succeeded.outputs(),
                    RailixProtocol.success(succeeded.outputs(), succeeded.steps())
            );
            case RunResult.Rejected rejected -> new Rejected(2,
                    RailixProtocol.diagnostics("run-rejected", rejected.diagnostics(), rejected.steps())
            );
            case RunResult.Failed failed -> new Rejected(3,
                    RailixProtocol.failure("run-failed", failed.failure(), failed.steps())
            );
            case RunResult.Cancelled cancelled -> new Rejected(130,
                    RailixProtocol.cancelled(cancelled.steps())
            );
        };
    }

    /** Explicit result returned without throwing flow or Step contract failures. */
    public sealed interface Outcome permits Completed, Rejected {
        RailixValue.ObjectValue payload();
    }

    /** Successful flow outputs and their canonical execution payload. */
    public record Completed(
            RailixValue.ObjectValue outputs,
            RailixValue.ObjectValue payload
    ) implements Outcome {
    }

    /** Deterministic compile, admission, or runtime rejection and process exit code. */
    public record Rejected(int exitCode, RailixValue.ObjectValue payload) implements Outcome {
    }
}
