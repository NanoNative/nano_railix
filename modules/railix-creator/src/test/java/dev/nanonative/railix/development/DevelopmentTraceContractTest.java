package dev.nanonative.railix.development;

import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.runtime.RunFailure;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DevelopmentTraceContractTest {
    @Test
    void graphStepStartIsANoOpWithoutAnActiveTrace() {
        assertThat(DevelopmentRuntime.Trace.before(1, "step", "test.step", empty())).isFalse();
    }

    @Test
    void successfulGraphStepEndIsANoOpWithoutAnActiveTrace() {
        assertThat(DevelopmentRuntime.Trace.after("step", "next", empty())).isFalse();
    }

    @Test
    void terminalGraphStepEndIsANoOpWithoutAnActiveTrace() {
        assertThat(DevelopmentRuntime.Trace.after("step", new RunResult.Cancelled(), empty())).isFalse();
    }

    @Test
    void graphHandlerExecutesDirectlyWithoutAnActiveTrace() throws Exception {
        final StepResult result = DevelopmentRuntime.Trace.invoke(
                input(),
                ignored -> StepResult.outcome("direct")
        );

        assertThat(result.outcome()).isEqualTo("direct");
    }

    @Test
    void nestedHandlerExecutesDirectlyWithoutAnActiveTrace() throws Exception {
        final StepResult result = DevelopmentRuntime.Trace.invoke(
                "nested",
                "test.nested",
                input(),
                ignored -> StepResult.outcome("direct")
        );

        assertThat(result.outcome()).isEqualTo("direct");
    }

    @Test
    void traceRejectsANullInitialContext() {
        assertThatThrownBy(() -> DevelopmentRuntime.Trace.start(
                null,
                event -> true,
                DevelopmentTraceContractTest::success
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Trace inputs cannot be Java null.");
    }

    @Test
    void traceRejectsANullSink() {
        assertThatThrownBy(() -> DevelopmentRuntime.Trace.start(
                empty(),
                null,
                DevelopmentTraceContractTest::success
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Trace inputs cannot be Java null.");
    }

    @Test
    void traceRejectsANullExecution() {
        assertThatThrownBy(() -> DevelopmentRuntime.Trace.start(
                empty(),
                event -> true,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Trace inputs cannot be Java null.");
    }

    @Test
    void traceRejectsASecondTraceOnTheSameExecutionThread() {
        assertThatThrownBy(() -> DevelopmentRuntime.Trace.start(empty(), event -> true, () ->
                DevelopmentRuntime.Trace.start(empty(), event -> true, DevelopmentTraceContractTest::success)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("A trace is already active on this execution thread.");
    }

    @Test
    void graphHandlerInvocationRequiresAnOpenStep() {
        assertThatThrownBy(() -> DevelopmentRuntime.Trace.start(empty(), event -> true, () -> {
            try {
                DevelopmentRuntime.Trace.invoke(input(), ignored -> StepResult.outcome("next"));
            } catch (final InterruptedException exception) {
                throw new AssertionError(exception);
            }
            return success();
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("Trace has no pending graph Step invocation.");
    }

    @Test
    void graphHandlerCanOnlyEnterItsOpenStepOnce() {
        assertThatThrownBy(() -> DevelopmentRuntime.Trace.start(empty(), event -> true, () -> {
            DevelopmentRuntime.Trace.before(1, "step", "test.step", empty());
            try {
                DevelopmentRuntime.Trace.invoke(input(), ignored -> StepResult.outcome("next"));
                DevelopmentRuntime.Trace.invoke(input(), ignored -> StepResult.outcome("next"));
            } catch (final InterruptedException exception) {
                throw new AssertionError(exception);
            }
            return success();
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("Trace has no pending graph Step invocation.");
    }

    @Test
    void traceRejectsAMismatchedClosingStep() {
        assertThatThrownBy(() -> DevelopmentRuntime.Trace.start(empty(), event -> true, () -> {
            DevelopmentRuntime.Trace.before(1, "first", "test.step", empty());
            DevelopmentRuntime.Trace.after("second", "next", empty());
            return success();
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("Trace Step nesting is inconsistent: second.");
    }

    @Test
    void traceRejectsATerminalResultWhileAStepRemainsOpen() {
        assertThatThrownBy(() -> DevelopmentRuntime.Trace.start(empty(), event -> true, () -> {
            DevelopmentRuntime.Trace.before(1, "step", "test.step", empty());
            return success();
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("Trace completed with unfinished Step records.");
    }

    @Test
    void nestedNullResultIsRecordedAsFailed() {
        final List<RailixValue.ObjectValue> events = new ArrayList<>();

        DevelopmentRuntime.Trace.start(empty(), sink(events), () -> {
            try {
                assertThat(DevelopmentRuntime.Trace.invoke(
                        "nested",
                        "test.nested",
                        input(),
                        ignored -> null
                )).isNull();
            } catch (final InterruptedException exception) {
                throw new AssertionError(exception);
            }
            return success();
        });

        assertThat(events).extracting(DevelopmentTraceContractTest::type)
                .containsExactly("trace", "step_start", "step_result", "result");
        assertThat(events.get(2).values()).containsEntry("status", RailixValue.string("failed"));
    }

    @Test
    void nestedRuntimeFailureIsRecordedBeforeItPropagates() {
        final List<RailixValue.ObjectValue> events = new ArrayList<>();

        assertThatThrownBy(() -> DevelopmentRuntime.Trace.start(empty(), sink(events), () -> {
            try {
                DevelopmentRuntime.Trace.invoke(
                        "nested",
                        "test.nested",
                        input(),
                        ignored -> {
                            throw new IllegalStateException("failed");
                        }
                );
            } catch (final InterruptedException exception) {
                throw new AssertionError(exception);
            }
            return success();
        })).isInstanceOf(IllegalStateException.class).hasMessage("failed");

        assertThat(events).extracting(DevelopmentTraceContractTest::type)
                .containsExactly("trace", "step_start", "step_result");
        assertThat(events.getLast().values()).containsEntry("status", RailixValue.string("failed"));
    }

    @Test
    void nestedInterruptionIsRecordedAsCancelled() {
        final List<RailixValue.ObjectValue> events = new ArrayList<>();

        DevelopmentRuntime.Trace.start(empty(), sink(events), () -> {
            try {
                DevelopmentRuntime.Trace.invoke(
                        "nested",
                        "test.nested",
                        input(),
                        ignored -> {
                            throw new InterruptedException("cancelled");
                        }
                );
                throw new AssertionError("Nested handler did not interrupt.");
            } catch (final InterruptedException expected) {
                return new RunResult.Cancelled();
            }
        });

        assertThat(events).extracting(DevelopmentTraceContractTest::type)
                .containsExactly("trace", "step_start", "step_result", "result");
        assertThat(events.get(2).values()).containsEntry("status", RailixValue.string("cancelled"));
        assertThat(events.getLast().values()).containsEntry("status", RailixValue.string("cancelled"));
    }

    @Test
    void rejectedFlowWritesItsDiagnosticsToTheTerminalTraceEvent() {
        final List<RailixValue.ObjectValue> events = new ArrayList<>();

        DevelopmentRuntime.Trace.start(empty(), sink(events), () -> new RunResult.Rejected(List.of(
                Diagnostic.atPath("INVALID", "Invalid input.", "context.payload")
        )));

        assertThat(events.getLast().values())
                .containsEntry("status", RailixValue.string("rejected"))
                .containsKey("diagnostics");
    }

    @Test
    void topLevelFailureOmitsAnEmptyPathFromTheTerminalTraceEvent() {
        final List<RailixValue.ObjectValue> events = new ArrayList<>();

        DevelopmentRuntime.Trace.start(empty(), sink(events), () -> new RunResult.Failed(
                new RunFailure("FAILED", "Failed.", "step")
        ));

        final RailixValue.ObjectValue failure = (RailixValue.ObjectValue) events.getLast().values().get("failure");
        assertThat(failure.values()).doesNotContainKey("path");
    }

    @Test
    void nestedFailurePreservesItsPathInTheTerminalTraceEvent() {
        final List<RailixValue.ObjectValue> events = new ArrayList<>();

        DevelopmentRuntime.Trace.start(empty(), sink(events), () -> new RunResult.Failed(
                new RunFailure("FAILED", "Failed.", "step", "inputs.operations[0]")
        ));

        final RailixValue.ObjectValue failure = (RailixValue.ObjectValue) events.getLast().values().get("failure");
        assertThat(failure.values())
                .containsEntry("path", RailixValue.string("inputs.operations[0]"));
    }

    @Test
    void unchangedContextInstanceProducesNoStepDiff() {
        final List<RailixValue.ObjectValue> events = new ArrayList<>();
        final RailixValue.ObjectValue context = RailixValue.object(Map.of(
                "payload", RailixValue.string("same")
        ));

        DevelopmentRuntime.Trace.start(context, sink(events), () -> {
            DevelopmentRuntime.Trace.before(1, "step", "test.step", context);
            DevelopmentRuntime.Trace.after("step", "next", context);
            return new RunResult.Succeeded(context);
        });

        assertThat(events.get(2).values()).doesNotContainKey("changes");
    }

    @Test
    void traceWritesInitialStepAndTerminalEventsWithoutDuration() {
        final List<RailixValue.ObjectValue> events = new ArrayList<>();
        final RailixValue.ObjectValue before = RailixValue.object(Map.of(
                "payload", RailixValue.object(Map.of("name", RailixValue.string("BEFORE")))
        ));
        final RailixValue.ObjectValue after = RailixValue.object(Map.of(
                "payload", RailixValue.object(Map.of("name", RailixValue.string("after")))
        ));

        final RunResult result = DevelopmentRuntime.Trace.start(before, sink(events), () -> {
            DevelopmentRuntime.Trace.before(1, "lowercase", "text.lowercase", before);
            final StepResult step;
            try {
                step = DevelopmentRuntime.Trace.invoke(
                        new StepInput(
                                Map.of("value", RailixValue.string("BEFORE")),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                "ok"
                        ),
                        input -> StepResult.outcome("ok")
                                .output("value", RailixValue.string("after"))
                );
            } catch (final InterruptedException exception) {
                throw new AssertionError(exception);
            }
            assertThat(step.outcome()).isEqualTo("ok");
            DevelopmentRuntime.Trace.after("lowercase", "ok", after);
            return new RunResult.Succeeded(after);
        });

        assertThat(result).isEqualTo(new RunResult.Succeeded(after));
        assertThat(events).extracting(DevelopmentTraceContractTest::type)
                .containsExactly("trace", "step_start", "step_result", "result");
        assertThat(events.get(1).values().get("node")).isEqualTo(RailixValue.number(1));
        assertThat(events.toString()).doesNotContain("duration");
        assertThat(events.get(2).values()).containsKeys("inputs", "returns", "changes");
    }

    @Test
    void traceDistinguishesAddedRemovedAndChangedValuesFromJsonNull() {
        final List<RailixValue.ObjectValue> events = new ArrayList<>();
        final RailixValue.ObjectValue before = RailixValue.object(Map.of(
                "changed", RailixValue.string("before"),
                "removed", RailixValue.nullValue()
        ));
        final RailixValue.ObjectValue after = RailixValue.object(Map.of(
                "changed", RailixValue.string("after"),
                "added", RailixValue.nullValue()
        ));

        DevelopmentRuntime.Trace.start(before, sink(events), () -> {
            DevelopmentRuntime.Trace.before(1, "step", "example.step", before);
            DevelopmentRuntime.Trace.after("step", "next", after);
            return new RunResult.Succeeded(after);
        });

        final RailixValue.ArrayValue changes = (RailixValue.ArrayValue) events.get(2).values().get("changes");
        assertThat(changes.values().stream()
                .map(RailixValue.ObjectValue.class::cast)
                .map(value -> ((RailixValue.StringValue) value.values().get("kind")).value()))
                .containsExactlyInAnyOrder("changed", "removed", "added");
    }

    @Test
    void traceDiffsChangedAndAddedArrayItemsByIndex() {
        final List<RailixValue.ObjectValue> events = new ArrayList<>();
        final RailixValue.ObjectValue before = RailixValue.object(Map.of(
                "items", RailixValue.array(List.of(RailixValue.number(1), RailixValue.number(2)))
        ));
        final RailixValue.ObjectValue after = RailixValue.object(Map.of(
                "items", RailixValue.array(List.of(
                        RailixValue.number(1),
                        RailixValue.number(3),
                        RailixValue.number(4)
                ))
        ));

        traceChange(before, after, events);

        final RailixValue.ArrayValue changes = (RailixValue.ArrayValue) events.get(2).values().get("changes");
        assertThat(changes.values().stream()
                .map(RailixValue.ObjectValue.class::cast)
                .map(value -> value.values().get("kind")))
                .containsExactly(RailixValue.string("changed"), RailixValue.string("added"));
    }

    @Test
    void traceDiffsRemovedArrayItemsByIndex() {
        final List<RailixValue.ObjectValue> events = new ArrayList<>();
        final RailixValue.ObjectValue before = RailixValue.object(Map.of(
                "items", RailixValue.array(List.of(RailixValue.number(1), RailixValue.number(2)))
        ));
        final RailixValue.ObjectValue after = RailixValue.object(Map.of(
                "items", RailixValue.array(List.of(RailixValue.number(1)))
        ));

        traceChange(before, after, events);

        final RailixValue.ArrayValue changes = (RailixValue.ArrayValue) events.get(2).values().get("changes");
        assertThat(changes.values()).singleElement().satisfies(change ->
                assertThat(((RailixValue.ObjectValue) change).values())
                        .containsEntry("kind", RailixValue.string("removed"))
        );
    }

    @Test
    void completedProjectionReconstructsNestedObjectAndArrayChanges() throws Exception {
        final RailixValue.ObjectValue before = RailixValue.object(Map.of(
                "payload", RailixValue.object(Map.of(
                        "items", RailixValue.array(List.of(
                                RailixValue.object(Map.of(
                                        "name", RailixValue.string(" BEFORE "),
                                        "obsolete", RailixValue.bool(true)
                                )),
                                RailixValue.object(Map.of("remove", RailixValue.bool(true)))
                        )),
                        "shrinking", RailixValue.array(List.of(
                                RailixValue.number(1),
                                RailixValue.number(2),
                                RailixValue.number(3)
                        )),
                        "removed", RailixValue.string("gone")
                ))
        ));
        final RailixValue.ObjectValue after = RailixValue.object(Map.of(
                "payload", RailixValue.object(Map.of(
                        "items", RailixValue.array(List.of(
                                RailixValue.object(Map.of(
                                        "name", RailixValue.string("before"),
                                        "added", RailixValue.number(7)
                                )),
                                RailixValue.object(Map.of("new", RailixValue.bool(true))),
                                RailixValue.object(Map.of("tail", RailixValue.bool(true)))
                        )),
                        "shrinking", RailixValue.array(List.of(RailixValue.number(4))),
                        "added", RailixValue.string("ready")
                ))
        ));
        final List<RailixValue.ObjectValue> events = new ArrayList<>();

        DevelopmentRuntime.Trace.start(before, sink(events), () -> {
            DevelopmentRuntime.Trace.before(1, "manipulation", "test.manipulation", before);
            try {
                DevelopmentRuntime.Trace.invoke(input(RailixValue.string(" BEFORE ")), ignored -> {
                    DevelopmentRuntime.Trace.invoke(
                            "manipulation.inputs.operations[0]",
                            "text.trim",
                            input(RailixValue.string(" BEFORE ")),
                            nested -> StepResult.outcome("ok")
                                    .output("value", RailixValue.string("BEFORE"))
                    );
                    DevelopmentRuntime.Trace.invoke(
                            "manipulation.inputs.operations[1]",
                            "text.lowercase",
                            input(RailixValue.string("BEFORE")),
                            nested -> StepResult.outcome("ok")
                                    .write("target", RailixValue.string("before"))
                    );
                    return StepResult.outcome("next")
                            .output("value", RailixValue.string("before"));
                });
            } catch (final InterruptedException exception) {
                throw new AssertionError(exception);
            }
            DevelopmentRuntime.Trace.after("manipulation", "next", after);
            return new RunResult.Succeeded(after);
        });

        final RailixValue.ObjectValue projection = project(events, 1);
        final RailixValue.ArrayValue stages = (RailixValue.ArrayValue) projection.values().get("stages");

        assertThat(projection.values())
                .containsEntry("input_context", before)
                .containsEntry("context", after);
        assertThat(stages.values()).hasSize(2);
        assertThat(((RailixValue.ObjectValue) stages.values().getFirst()).values())
                .containsEntry("input", RailixValue.string("operations"))
                .containsEntry("invocation", RailixValue.string("text.trim"))
                .containsEntry("status", RailixValue.string("ok"))
                .containsEntry("value", RailixValue.string("BEFORE"));
        assertThat(((RailixValue.ObjectValue) stages.values().getLast()).values())
                .containsEntry("invocation", RailixValue.string("text.lowercase"))
                .containsEntry("value", RailixValue.string("before"));
    }

    @Test
    void failedProjectionPreservesCompletedStagesAndLastContext() throws Exception {
        final RailixValue.ObjectValue context = RailixValue.object(Map.of(
                "payload", RailixValue.object(Map.of("value", RailixValue.string("before")))
        ));
        final List<RailixValue.ObjectValue> events = new ArrayList<>();

        assertThatThrownBy(() -> DevelopmentRuntime.Trace.start(context, sink(events), () -> {
            DevelopmentRuntime.Trace.before(1, "failed", "test.failed", context);
            try {
                DevelopmentRuntime.Trace.invoke(input(RailixValue.string("before")), ignored -> {
                    DevelopmentRuntime.Trace.invoke(
                            "failed.inputs.operations[0]",
                            "text.uppercase",
                            input(RailixValue.string("before")),
                            nested -> StepResult.outcome("ok")
                                    .output("value", RailixValue.string("BEFORE"))
                    );
                    throw new IllegalStateException("failed after primitive");
                });
            } catch (final InterruptedException exception) {
                throw new AssertionError(exception);
            }
            return success();
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("failed after primitive");
        events.add(RailixValue.object(Map.of(
                "code", RailixValue.string("TRACE_EXECUTION_FAILED"),
                "message", RailixValue.string("Example execution failed before a terminal result."),
                "status", RailixValue.string("failed"),
                "type", RailixValue.string("trace_error")
        )));

        final RailixValue.ObjectValue projection = project(events, 1);
        final RailixValue.ArrayValue stages = (RailixValue.ArrayValue) projection.values().get("stages");

        assertThat(projection.values())
                .containsEntry("code", RailixValue.string("TRACE_EXECUTION_FAILED"))
                .containsEntry("input_context", context)
                .containsEntry("context", context);
        assertThat(stages.values()).singleElement().satisfies(stage ->
                assertThat(((RailixValue.ObjectValue) stage).values())
                        .containsEntry("invocation", RailixValue.string("text.uppercase"))
                        .containsEntry("value", RailixValue.string("BEFORE"))
        );
    }

    @Test
    void traceSinkIOExceptionDoesNotChangeFlowExecution() {
        final AtomicBoolean executed = new AtomicBoolean();

        final RunResult result = DevelopmentRuntime.Trace.start(
                RailixValue.object(Map.of()),
                event -> {
                    throw new IOException("closed");
                },
                () -> {
                    executed.set(true);
                    return new RunResult.Succeeded(RailixValue.object(Map.of()));
                }
        );

        assertThat(result).isInstanceOf(RunResult.Succeeded.class);
        assertThat(executed).isTrue();
    }

    @Test
    void traceSinkIOExceptionStillRemovesRequestScopedState() {
        DevelopmentRuntime.Trace.start(
                RailixValue.object(Map.of()),
                event -> {
                    throw new IOException("closed");
                },
                () -> new RunResult.Succeeded(RailixValue.object(Map.of()))
        );

        final List<RailixValue.ObjectValue> events = new ArrayList<>();
        DevelopmentRuntime.Trace.start(
                RailixValue.object(Map.of()),
                sink(events),
                () -> new RunResult.Succeeded(RailixValue.object(Map.of()))
        );
        assertThat(events).hasSize(2);
    }

    @Test
    void refusedInitialTraceWriteDoesNotStopFlowExecution() {
        final AtomicBoolean executed = new AtomicBoolean();

        final RunResult result = DevelopmentRuntime.Trace.start(
                RailixValue.object(Map.of()),
                event -> false,
                () -> {
                    executed.set(true);
                    return new RunResult.Succeeded(RailixValue.object(Map.of()));
                }
        );

        assertThat(result).isInstanceOf(RunResult.Succeeded.class);
        assertThat(executed).isTrue();
    }

    @Test
    void refusedNestedStepStartDoesNotStopFlowExecution() {
        final AtomicBoolean nestedExecuted = new AtomicBoolean();
        final AtomicBoolean outerExecuted = new AtomicBoolean();
        final AtomicBoolean refuseNestedStart = new AtomicBoolean();

        final RunResult result = DevelopmentRuntime.Trace.start(
                empty(),
                event -> !type(event).equals("step_start") || !refuseNestedStart.getAndSet(true),
                () -> {
                    DevelopmentRuntime.Trace.before(1, "outer", "test.outer", empty());
                    try {
                        DevelopmentRuntime.Trace.invoke(input(), ignored -> {
                            final StepResult nested = DevelopmentRuntime.Trace.invoke(
                                    "outer/nested",
                                    "test.nested",
                                    input(),
                                    nestedInput -> {
                                        nestedExecuted.set(true);
                                        return StepResult.outcome("next");
                                    }
                            );
                            outerExecuted.set(true);
                            return nested;
                        });
                    } catch (final InterruptedException exception) {
                        throw new AssertionError(exception);
                    }
                    DevelopmentRuntime.Trace.after("outer", "next", empty());
                    return success();
                }
        );

        assertThat(result).isInstanceOf(RunResult.Succeeded.class);
        assertThat(nestedExecuted).isTrue();
        assertThat(outerExecuted).isTrue();
    }

    @Test
    void wideContextChangeStopsObservationButNotFlowExecution() {
        final List<RailixValue.ObjectValue> events = new ArrayList<>();
        final RailixValue.ObjectValue before = RailixValue.object(Map.of());
        final Map<String, RailixValue> fields = new LinkedHashMap<>();
        for (int index = 0; index <= DevelopmentRuntime.Trace.MAX_CONTEXT_CHANGES; index++) {
            fields.put("field-" + index, RailixValue.number(index));
        }
        final RailixValue.ObjectValue after = RailixValue.object(fields);

        final RunResult result = DevelopmentRuntime.Trace.start(before, sink(events), () -> {
            DevelopmentRuntime.Trace.before(1, "wide", "test.wide", before);
            DevelopmentRuntime.Trace.after("wide", "next", after);
            return new RunResult.Succeeded(after);
        });

        assertThat(result).isEqualTo(new RunResult.Succeeded(after));
        assertThat(events).extracting(DevelopmentTraceContractTest::type)
                .containsExactly("trace", "step_start", "trace_error");
        assertThat(events.getLast().values())
                .containsEntry("code", RailixValue.string("TRACE_CHANGES_TOO_LARGE"))
                .containsEntry("status", RailixValue.string("failed"));
    }

    private static DevelopmentRuntime.TraceSink sink(final List<RailixValue.ObjectValue> events) {
        return event -> {
            events.add(event);
            return true;
        };
    }

    private static void traceChange(
            final RailixValue.ObjectValue before,
            final RailixValue.ObjectValue after,
            final List<RailixValue.ObjectValue> events
    ) {
        DevelopmentRuntime.Trace.start(before, sink(events), () -> {
            DevelopmentRuntime.Trace.before(1, "step", "example.step", before);
            DevelopmentRuntime.Trace.after("step", "next", after);
            return new RunResult.Succeeded(after);
        });
    }

    private static StepInput input() {
        return new StepInput(Map.of(), Map.of(), Map.of(), Map.of(), "next");
    }

    private static StepInput input(final RailixValue value) {
        return new StepInput(Map.of("value", value), Map.of(), Map.of(), Map.of(), "next");
    }

    private static RailixValue.ObjectValue project(
            final List<RailixValue.ObjectValue> events,
            final int selectedNode
    ) throws IOException {
        final String trace = events.stream()
                .map(RailixJson::write)
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        return ExampleSuite.project(
                new ByteArrayInputStream(trace.getBytes(StandardCharsets.UTF_8)),
                selectedNode,
                false
        ).orElseThrow();
    }

    private static RailixValue.ObjectValue empty() {
        return RailixValue.object(Map.of());
    }

    private static RunResult success() {
        return new RunResult.Succeeded(empty());
    }

    private static String type(final RailixValue.ObjectValue event) {
        return ((RailixValue.StringValue) event.values().get("type")).value();
    }
}
