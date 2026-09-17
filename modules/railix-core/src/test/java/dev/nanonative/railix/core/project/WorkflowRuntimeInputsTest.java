package dev.nanonative.railix.core.project;

import com.sun.management.ThreadMXBean;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class WorkflowRuntimeInputsTest {
    private static final WorkflowRuntime.StepPlan PLAN = new WorkflowRuntime.StepPlan(
            "step", "test.step", false, List.of(), List.of(), List.of("next", "skip"),
            Map.of(), Map.of(), "nodes[0]"
    );
    private static final WorkflowRuntime.InputResolver RECEIVES =
            (execution, received, outcome) -> WorkflowRuntime.inputs(received, outcome);
    private static final StepResult NEXT = StepResult.outcome("next");
    private static final RailixValue VALUE = RailixValue.string("value");

    @Test
    void emptyInputsRemainImmutableAndInvocationOwnedAcrossRepeatedCalls() {
        final WorkflowRuntime.Execution execution = execution(Map.of());
        final List<StepInput> captured = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            assertThat(execution.call(PLAN, input -> {
                captured.add(input);
                return NEXT;
            }, Map.of(), RECEIVES)).isZero();
        }

        assertThat(captured).hasSize(10).doesNotHaveDuplicates();
        for (final StepInput input : captured) {
            assertThat(input.values()).isEmpty();
            assertThat(input.options()).isEmpty();
            assertThat(input.primaryOutcome()).isEqualTo("next");
            assertThatThrownBy(() -> input.values().put("added", VALUE))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
        assertThat(execution.finish()).isInstanceOf(RunResult.Succeeded.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void receivedValuesAreCopiedBeforeResolutionAndNeverMutateCallerMaps(final boolean populated) {
        final Map<String, RailixValue> received = new LinkedHashMap<>();
        if (populated) {
            received.put("source", VALUE);
        }
        final List<StepInput> captured = new ArrayList<>();
        final WorkflowRuntime.Execution execution = execution(Map.of());

        assertThat(execution.call(PLAN, input -> {
            captured.add(input);
            return NEXT;
        }, received, (current, source, outcome) -> {
            final WorkflowRuntime.Inputs inputs = WorkflowRuntime.inputs(source, outcome);
            source.put("source", RailixValue.string("changed"));
            inputs.value("literal", VALUE);
            return inputs;
        })).isZero();

        assertThat(received).containsExactlyEntriesOf(Map.of("source", RailixValue.string("changed")));
        received.clear();
        assertThat(captured.getFirst().values()).containsExactlyInAnyOrderEntriesOf(populated
                ? Map.of("source", VALUE, "literal", VALUE)
                : Map.of("literal", VALUE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"literal", "path", "choice", "candidate", "matched", "unmatched"})
    void eachValueResolutionCanPopulateInitiallyEmptyInputs(final String kind) {
        final WorkflowRuntime.Execution execution = execution(Map.of("source", VALUE));
        final List<StepInput> captured = new ArrayList<>();
        final WorkflowRuntime.InputResolver child = (current, received, outcome) -> {
            final WorkflowRuntime.Inputs inputs = WorkflowRuntime.inputs(received, outcome);
            inputs.value("source", VALUE);
            return inputs;
        };
        final List<WorkflowRuntime.InputReference> sources = List.of(
                new WorkflowRuntime.InputReference(true, "source")
        );
        final WorkflowRuntime.CandidatePlan candidate = new WorkflowRuntime.CandidatePlan(
                "literal", "", child, sources, new WorkflowRuntime.NestedProgram(List.of()), List.of()
        );

        assertThat(execution.call(PLAN, input -> {
            captured.add(input);
            return NEXT;
        }, Map.of(), (current, received, outcome) -> {
            final WorkflowRuntime.Inputs inputs = WorkflowRuntime.inputs(received, outcome);
            switch (kind) {
                case "literal" -> inputs.value("value", VALUE);
                case "path" -> inputs.path("value", binding(true, false, "source"), current);
                case "choice" -> inputs.choice("value", "literal", child, sources, current);
                case "candidate" -> inputs.candidates("value", List.of(candidate), current);
                case "matched" -> inputs.matcherGroups("value", List.of(List.of(candidate)), current);
                case "unmatched" -> inputs.matcherGroups("value", List.of(), current);
                default -> throw new AssertionError(kind);
            }
            return inputs;
        })).isZero();

        final StepInput input = captured.getFirst();
        assertThat(input.values()).containsExactlyInAnyOrderEntriesOf(Map.of("value", switch (kind) {
            case "matched" -> RailixValue.bool(true);
            case "unmatched" -> RailixValue.bool(false);
            default -> VALUE;
        }));
        assertThatThrownBy(() -> input.values().clear()).isInstanceOf(UnsupportedOperationException.class);
        if (kind.equals("choice") || kind.equals("candidate")) {
            assertThat(input.option("value")).isEqualTo("literal");
            assertThat(input.selected("value").values()).containsExactlyInAnyOrderEntriesOf(Map.of("source", VALUE));
        }
    }

    @Test
    void missingReadPathsAndUnselectedCandidatesLeaveInputsEmpty() {
        final WorkflowRuntime.Execution execution = execution(Map.of());
        assertThat(execution.call(PLAN, input -> {
            assertThat(input.values()).isEmpty();
            assertThat(input.optionalValue("missing")).isEmpty();
            assertThat(input.options()).containsExactlyInAnyOrderEntriesOf(Map.of("choice", "empty"));
            assertThat(input.selected("choice").values()).isEmpty();
            return StepResult.outcome("skip");
        }, Map.of(), (current, received, outcome) -> {
            final WorkflowRuntime.Inputs inputs = WorkflowRuntime.inputs(received, outcome);
            inputs.value("null", null);
            inputs.path("missing", binding(true, false, "missing"), current);
            inputs.candidates("candidate", List.of(), current);
            inputs.choice("choice", "empty", RECEIVES, List.of(), current);
            return inputs;
        })).isEqualTo(1);
        assertThat(execution.finish()).isInstanceOf(RunResult.Succeeded.class);
    }

    @Test
    void aProgramWithAMissingSourceKeepsEmptyInputsAndItsInvocationScope() {
        final WorkflowRuntime.Execution execution = execution(Map.of());
        final List<StepInput> captured = new ArrayList<>();
        assertThat(execution.call(PLAN, input -> {
            captured.add(input);
            assertThat(input.values()).isEmpty();
            final StepInput.ProgramResult result = input.run("operations");
            assertThat(result.outcome()).isEqualTo("skip");
            assertThat(result.values()).isEmpty();
            assertThat(input.run("operations")).isEqualTo(result);
            return NEXT;
        }, Map.of(), (current, received, outcome) -> {
            final WorkflowRuntime.Inputs inputs = WorkflowRuntime.inputs(received, outcome);
            inputs.program("operations", new WorkflowRuntime.NestedProgram(List.of()), "missing", "skip", current);
            return inputs;
        })).isZero();
        assertThatThrownBy(() -> captured.getFirst().run("operations"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Nested Step program is no longer active.");
    }

    @Test
    void readSnapshotsRemainImmutableAfterALaterWrite() {
        final WorkflowRuntime.Execution execution = execution(Map.of("source", VALUE));
        final List<StepInput> captured = new ArrayList<>();
        final WorkflowRuntime.InputResolver read = (current, received, outcome) -> {
            final WorkflowRuntime.Inputs inputs = WorkflowRuntime.inputs(received, outcome);
            inputs.path("value", binding(true, false), current);
            return inputs;
        };
        assertThat(execution.call(PLAN, input -> {
            captured.add(input);
            return NEXT;
        }, Map.of(), read)).isZero();
        final RailixValue.ObjectValue before = execution.context();

        assertThat(execution.call(PLAN, input -> {
            assertThat(input.values()).isEmpty();
            return NEXT.write("target", RailixValue.string("changed"));
        }, Map.of(), (current, received, outcome) -> {
            final WorkflowRuntime.Inputs inputs = WorkflowRuntime.inputs(received, outcome);
            inputs.path("target", binding(false, true, "source"), current);
            return inputs;
        })).isZero();

        assertThat(captured.getFirst().value("value")).isEqualTo(before);
        assertThat(before.values()).containsEntry("source", VALUE);
        assertThat(((RunResult.Succeeded) execution.finish()).context().values())
                .containsEntry("source", RailixValue.string("changed"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedMultiWritesDoNotPublishAnyChanges(final boolean sparse) {
        final WorkflowRuntime.Execution execution = execution(Map.of("primitive", VALUE));
        final RailixValue.ObjectValue before = execution.context();
        final WorkflowRuntime.PathBinding invalid = sparse
                ? new WorkflowRuntime.PathBinding(new WorkflowRuntime.Path(List.of(
                        new WorkflowRuntime.Field("context"), new WorkflowRuntime.Field("array"),
                        new WorkflowRuntime.Index(1_025)
                )), false, true)
                : binding(false, true, "primitive", "child");

        assertThat(execution.call(PLAN, input -> NEXT.write("first", VALUE).write("second", VALUE),
                Map.of(), (current, received, outcome) -> {
                    final WorkflowRuntime.Inputs inputs = WorkflowRuntime.inputs(received, outcome);
                    inputs.path("first", binding(false, true, "created", "child"), current);
                    inputs.path("second", invalid, current);
                    return inputs;
                })).isEqualTo(-1);

        assertThat(execution.context()).isSameAs(before);
        assertThat(execution.context().values()).doesNotContainKeys("created", "array");
        assertThat(execution.resolve(binding(true, false).path())).isEqualTo(before);
        final RunResult.Rejected rejected = (RunResult.Rejected) execution.finish();
        assertThat(rejected.diagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(sparse ? "RUN_ARRAY_TARGET_SPARSE" : "RUN_FIELD_TARGET_CONFLICT");
            assertThat(diagnostic.path()).isEqualTo("nodes[0].inputs.second");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"outcome", "output", "write", "null", "exception"})
    void emptyInputsDoNotBypassStepFailureValidation(final String failure) {
        final WorkflowRuntime.Execution execution = execution(Map.of());
        assertThat(execution.call(PLAN, input -> switch (failure) {
            case "outcome" -> StepResult.outcome("undeclared");
            case "output" -> NEXT.output("undeclared", VALUE);
            case "write" -> NEXT.write("undeclared", VALUE);
            case "null" -> null;
            case "exception" -> throw new IllegalStateException("failure");
            default -> throw new AssertionError(failure);
        }, Map.of(), RECEIVES)).isEqualTo(-1);
        assertThat(((RunResult.Failed) execution.finish()).failure().code()).isEqualTo(switch (failure) {
            case "outcome" -> "STEP_OUTCOME_INVALID";
            case "output" -> "STEP_OUTPUT_INVALID";
            case "write" -> "STEP_WRITE_UNDECLARED";
            case "null" -> "STEP_RESULT_REQUIRED";
            case "exception" -> "STEP_IMPLEMENTATION_FAULT";
            default -> throw new AssertionError(failure);
        });
    }

    @Test
    void repeatedNoInputCallsReportAllocationWithoutAPlatformDependentGate() {
        // Use -Xint and jacoco.skip for comparable before/after measurements, not a CI threshold.
        final var management = ManagementFactory.getThreadMXBean();
        assumeTrue(management instanceof ThreadMXBean);
        final ThreadMXBean allocations = (ThreadMXBean) management;
        assumeTrue(allocations.isThreadAllocatedMemorySupported());
        assumeTrue(allocations.isThreadAllocatedMemoryEnabled());
        final WorkflowRuntime.Execution execution = execution(Map.of());
        final StepInput[] captured = new StepInput[1];
        final WorkflowRuntime.StepCall handler = input -> {
            captured[0] = input;
            return NEXT;
        };
        final Map<String, RailixValue> received = Map.of();
        for (int index = 0; index < 10_000; index++) {
            execution.call(PLAN, handler, received, RECEIVES);
        }
        final int iterations = 50_000;
        final long thread = Thread.currentThread().threadId();
        final long before = allocations.getThreadAllocatedBytes(thread);
        int outcomes = 0;
        for (int index = 0; index < iterations; index++) {
            outcomes += execution.call(PLAN, handler, received, RECEIVES);
        }
        final long allocated = allocations.getThreadAllocatedBytes(thread) - before;
        final double bytesPerCall = (double) allocated / iterations;
        System.out.printf(java.util.Locale.ROOT,
                "RAILIX_INPUT_ALLOCATION advisory=true bytes_per_call=%.2f calls=%d%n", bytesPerCall, iterations);
        assertThat(outcomes).isZero();
        assertThat(captured[0].values()).isEmpty();
    }

    private static WorkflowRuntime.Execution execution(final Map<String, RailixValue> context) {
        return WorkflowRuntime.execution(List.of(), RailixValue.object(context), RailixValue.object(Map.of()));
    }

    private static WorkflowRuntime.PathBinding binding(
            final boolean readable,
            final boolean writable,
            final String... fields
    ) {
        final List<WorkflowRuntime.PathElement> elements = new ArrayList<>();
        elements.add(new WorkflowRuntime.Field("context"));
        for (final String field : fields) {
            elements.add(new WorkflowRuntime.Field(field));
        }
        return new WorkflowRuntime.PathBinding(new WorkflowRuntime.Path(List.copyOf(elements)), readable, writable);
    }
}
