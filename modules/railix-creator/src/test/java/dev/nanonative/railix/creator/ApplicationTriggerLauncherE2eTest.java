package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationTriggerLauncherE2eTest {
    private static final String APPLICATION_USAGE =
            "Usage: <no arguments> | cli <trigger-id> [argument...] | creator "
                    + "| lock [--check | --write <output>...]";

    @Test
    void cliTriggerRunsOneDirectJsonObjectFromStdin() {
        assertThat(invoke(
                directFlow("""
                        [{"id":"command","type":"cli","config":{"stdin":true}}]
                        """, "{\"text\":\"string\"}", "{\"text\":\"string\"}",
                        "[{\"from\":\"input.text\",\"to\":\"output.text\"}]"),
                StepCatalog.of(noopStep()),
                json("{\"text\":\"Railix\"}"),
                "cli",
                "command"
        )).isEqualTo(new Invocation(0, "{\"text\":\"Railix\"}\n", ""));
    }

    @Test
    void cliTriggerWithStdinDisabledNeverReadsTheStream() {
        final InputStream unreadable = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("must not be read");
            }
        };

        assertThat(invoke(
                constantFlow("[{\"id\":\"command\",\"type\":\"cli\",\"config\":{\"stdin\":false}}]"),
                StepCatalog.of(constantStep()),
                unreadable,
                "cli",
                "command"
        )).isEqualTo(new Invocation(0, "{\"message\":\"started\"}\n", ""));
    }

    @Test
    void cliArgumentsAreInsertedOnlyIntoTheDeclaredInput() {
        assertThat(invoke(
                directFlow("""
                        [{"id":"command","type":"cli","config":{"stdin":false,"arguments":"arguments"}}]
                        """, "{\"arguments\":\"array\"}", "{\"arguments\":\"array\"}",
                        "[{\"from\":\"input.arguments\",\"to\":\"output.arguments\"}]"),
                StepCatalog.of(noopStep()),
                InputStream.nullInputStream(),
                "cli",
                "command",
                "--uppercase",
                "fast"
        )).isEqualTo(new Invocation(
                0,
                "{\"arguments\":[\"--uppercase\",\"fast\"]}\n",
                ""
        ));
    }

    @Test
    void cliArgumentsMappingSuppliesAnExplicitEmptyArray() {
        assertThat(invoke(
                directFlow("""
                        [{"id":"command","type":"cli","config":{"stdin":false,"arguments":"arguments"}}]
                        """, "{\"arguments\":\"array\"}", "{\"arguments\":\"array\"}",
                        "[{\"from\":\"input.arguments\",\"to\":\"output.arguments\"}]"),
                StepCatalog.of(noopStep()),
                InputStream.nullInputStream(),
                "cli",
                "command"
        )).isEqualTo(new Invocation(0, "{\"arguments\":[]}\n", ""));
    }

    @Test
    void cliArgumentsCanFillTheExactEventByteLimit() {
        final Invocation invocation = invoke(
                directFlow("""
                        [{"id":"command","type":"cli","config":{"stdin":false,"arguments":"arguments"}}]
                        """, "{\"arguments\":\"array\"}", "{\"arguments\":\"array\"}",
                        "[{\"from\":\"input.arguments\",\"to\":\"output.arguments\"}]"),
                StepCatalog.of(noopStep()),
                InputStream.nullInputStream(),
                "cli",
                "command",
                "a".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - 18)
        );

        assertThat(List.of(
                invocation.exitCode(),
                invocation.stdout().getBytes(StandardCharsets.UTF_8).length,
                invocation.stderr().length()
        )).containsExactly(0, RailixData.DEFAULT_MAX_SOURCE_BYTES + 1, 0);
    }

    @Test
    void cliArgumentsCannotExceedTheEventByteLimit() {
        assertThat(invoke(
                directFlow("""
                        [{"id":"command","type":"cli","config":{"stdin":false,"arguments":"arguments"}}]
                        """, "{\"arguments\":\"array\"}", "{\"arguments\":\"array\"}",
                        "[{\"from\":\"input.arguments\",\"to\":\"output.arguments\"}]"),
                StepCatalog.of(noopStep()),
                InputStream.nullInputStream(),
                "cli",
                "command",
                "a".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - 17)
        )).isEqualTo(new Invocation(
                2,
                "",
                "{\"error\":{\"code\":\"CLI_EVENT_TOO_LARGE\","
                        + "\"message\":\"CLI event exceeds the 1048576-byte limit.\"},"
                        + "\"status\":\"event-rejected\"}\n"
        ));
    }

    @Test
    void oversizedArgumentsDoNotReadStdin() {
        final AtomicInteger reads = new AtomicInteger();
        final InputStream unreadable = new InputStream() {
            @Override
            public int read() throws IOException {
                reads.incrementAndGet();
                throw new IOException("must not be read");
            }
        };

        assertThat(new InvocationWithRuns(invoke(
                directFlow("""
                        [{"id":"command","type":"cli","config":{"stdin":true,"arguments":"arguments"}}]
                        """, "{\"arguments\":\"array\"}", "{\"arguments\":\"array\"}",
                        "[{\"from\":\"input.arguments\",\"to\":\"output.arguments\"}]"),
                StepCatalog.of(noopStep()),
                unreadable,
                "cli",
                "command",
                "a".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES)
        ), reads.get())).isEqualTo(new InvocationWithRuns(
                new Invocation(
                        2,
                        "",
                        "{\"error\":{\"code\":\"CLI_EVENT_TOO_LARGE\","
                                + "\"message\":\"CLI event exceeds the 1048576-byte limit.\"},"
                                + "\"status\":\"event-rejected\"}\n"
                ),
                0
        ));
    }

    @Test
    void oversizedProgrammaticArgumentsStopAtTheLimit() {
        final AtomicInteger reads = new AtomicInteger();
        final String chunk = "a".repeat(1_024);
        final List<String> arguments = new AbstractList<>() {
            @Override
            public String get(final int index) {
                reads.incrementAndGet();
                if (index > 1_200) {
                    throw new AssertionError("Launcher scanned beyond the event limit.");
                }
                return switch (index) {
                    case 0 -> "cli";
                    case 1 -> "command";
                    default -> chunk;
                };
            }

            @Override
            public int size() {
                return Integer.MAX_VALUE;
            }
        };

        final Invocation invocation = invoke(
                directFlow("""
                        [{"id":"command","type":"cli","config":{"stdin":false,"arguments":"arguments"}}]
                        """, "{\"arguments\":\"array\"}", "{\"arguments\":\"array\"}",
                        "[{\"from\":\"input.arguments\",\"to\":\"output.arguments\"}]"),
                StepCatalog.of(noopStep()),
                InputStream.nullInputStream(),
                arguments
        );

        assertThat(List.of(
                invocation.exitCode(),
                invocation.stderr().contains("\"code\":\"CLI_EVENT_TOO_LARGE\""),
                reads.get() < 1_100
        )).containsExactly(2, true, true);
    }

    @Test
    void cliArgumentsWithoutAMappingAreRejected() {
        assertThat(invoke(
                constantFlow("[{\"id\":\"command\",\"type\":\"cli\",\"config\":{\"stdin\":false}}]"),
                StepCatalog.of(constantStep()),
                InputStream.nullInputStream(),
                "cli",
                "command",
                "unexpected"
        )).isEqualTo(new Invocation(
                2,
                "",
                "{\"error\":{\"code\":\"CLI_ARGUMENTS_UNSUPPORTED\","
                        + "\"message\":\"CLI trigger does not accept arguments: command.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        ));
    }

    @Test
    void cliArgumentsNeverOverwriteAStdinField() {
        assertThat(invoke(
                directFlow("""
                        [{"id":"command","type":"cli","config":{"stdin":true,"arguments":"arguments"}}]
                        """, "{\"arguments\":\"array\"}", "{\"arguments\":\"array\"}",
                        "[{\"from\":\"input.arguments\",\"to\":\"output.arguments\"}]"),
                StepCatalog.of(noopStep()),
                json("{\"arguments\":[\"stdin\"]}"),
                "cli",
                "command",
                "process"
        )).isEqualTo(new Invocation(
                2,
                "",
                "{\"error\":{\"code\":\"CLI_ARGUMENTS_INPUT_CONFLICT\","
                        + "\"message\":\"CLI stdin already supplies arguments input: arguments.\"},"
                        + "\"status\":\"event-rejected\"}\n"
        ));
    }

    @Test
    void cliArgumentsRejectUnpairedUnicodeWithoutThrowing() {
        assertThat(invoke(
                directFlow("""
                        [{"id":"command","type":"cli","config":{"stdin":false,"arguments":"arguments"}}]
                        """, "{\"arguments\":\"array\"}", "{\"arguments\":\"array\"}",
                        "[{\"from\":\"input.arguments\",\"to\":\"output.arguments\"}]"),
                StepCatalog.of(noopStep()),
                InputStream.nullInputStream(),
                "cli",
                "command",
                "\ud800"
        )).isEqualTo(new Invocation(
                2,
                "",
                "{\"error\":{\"code\":\"CLI_ARGUMENTS_INVALID\","
                        + "\"message\":\"CLI arguments must contain valid Unicode.\"},"
                        + "\"status\":\"event-rejected\"}\n"
        ));
    }

    @Test
    void cliArgumentsRejectJavaNullWithoutThrowing() {
        assertThat(invoke(
                directFlow("""
                        [{"id":"command","type":"cli","config":{"stdin":false,"arguments":"arguments"}}]
                        """, "{\"arguments\":\"array\"}", "{\"arguments\":\"array\"}",
                        "[{\"from\":\"input.arguments\",\"to\":\"output.arguments\"}]"),
                StepCatalog.of(noopStep()),
                InputStream.nullInputStream(),
                Arrays.asList("cli", "command", null)
        )).isEqualTo(new Invocation(
                2,
                "",
                "{\"error\":{\"code\":\"CLI_ARGUMENTS_INVALID\","
                        + "\"message\":\"CLI arguments must contain valid Unicode.\"},"
                        + "\"status\":\"event-rejected\"}\n"
        ));
    }

    @Test
    void unknownCliTriggerIsRejectedBeforeStartupRuns() {
        final AtomicInteger runs = new AtomicInteger();
        final String flow = counterFlow("""
                [
                  {"id":"initialize","type":"startup","config":{}},
                  {"id":"command","type":"cli","config":{"stdin":false}}
                ]
                """);

        assertThat(new InvocationWithRuns(invoke(
                flow,
                StepCatalog.of(counterStep(runs)),
                InputStream.nullInputStream(),
                "cli",
                "missing"
        ), runs.get())).isEqualTo(new InvocationWithRuns(
                new Invocation(
                        2,
                        "",
                        "{\"error\":{\"code\":\"CLI_TRIGGER_UNKNOWN\","
                                + "\"message\":\"CLI trigger does not exist: missing.\"},"
                                + "\"status\":\"launcher-rejected\"}\n"
                ),
                0
        ));
    }

    @Test
    void applicationWithoutAStartupTriggerRejectsNoArgumentStartup() {
        assertThat(invoke(
                constantFlow("[{\"id\":\"command\",\"type\":\"cli\",\"config\":{\"stdin\":false}}]"),
                StepCatalog.of(constantStep()),
                InputStream.nullInputStream()
        )).isEqualTo(new Invocation(
                2,
                "",
                "{\"error\":{\"code\":\"STARTUP_TRIGGER_MISSING\","
                        + "\"message\":\"Packaged flow has no startup trigger.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        ));
    }

    @Test
    void noArgumentsRunTheStartupTriggerOnce() {
        assertThat(invoke(
                constantFlow("[{\"id\":\"initialize\",\"type\":\"startup\",\"config\":{}}]"),
                StepCatalog.of(constantStep()),
                InputStream.nullInputStream()
        )).isEqualTo(new Invocation(0, "{\"message\":\"started\"}\n", ""));
    }

    @Test
    void successfulStartupClosesBothPackagedResources() {
        assertThat(invokeWithTrackedResources(
                constantFlow("[{\"id\":\"initialize\",\"type\":\"startup\",\"config\":{}}]"),
                StepCatalog.of(constantStep()),
                InputStream.nullInputStream()
        )).isEqualTo(new ResourceInvocation(
                new Invocation(0, "{\"message\":\"started\"}\n", ""),
                true,
                true
        ));
    }

    @Test
    void multipleStartupTriggersRunSeriallyInDeclarationOrder() {
        final AtomicInteger runs = new AtomicInteger();

        assertThat(invoke(
                counterFlow("""
                        [
                          {"id":"first","type":"startup","config":{}},
                          {"id":"second","type":"startup","config":{}}
                        ]
                        """),
                StepCatalog.of(counterStep(runs)),
                InputStream.nullInputStream()
        )).isEqualTo(new Invocation(
                0,
                "{\"sequence\":1}\n{\"sequence\":2}\n",
                ""
        ));
    }

    @Test
    void cliInvocationRunsStartupBeforeTheSelectedTrigger() {
        final AtomicInteger runs = new AtomicInteger();

        assertThat(invoke(
                counterFlow("""
                        [
                          {"id":"initialize","type":"startup","config":{}},
                          {"id":"command","type":"cli","config":{"stdin":false}}
                        ]
                        """),
                StepCatalog.of(counterStep(runs)),
                InputStream.nullInputStream(),
                "cli",
                "command"
        )).isEqualTo(new Invocation(
                0,
                "{\"sequence\":1}\n{\"sequence\":2}\n",
                ""
        ));
    }

    @Test
    void cliInvocationRunsOnlyTheSelectedCliTrigger() {
        final AtomicInteger runs = new AtomicInteger();

        assertThat(new InvocationWithRuns(invoke(
                counterFlow("""
                        [
                          {"id":"first","type":"cli","config":{"stdin":false}},
                          {"id":"second","type":"cli","config":{"stdin":false}}
                        ]
                        """),
                StepCatalog.of(counterStep(runs)),
                InputStream.nullInputStream(),
                "cli",
                "second"
        ), runs.get())).isEqualTo(new InvocationWithRuns(
                new Invocation(0, "{\"sequence\":1}\n", ""),
                1
        ));
    }

    @Test
    void successfulCliInvocationClosesBothPackagedResources() {
        assertThat(invokeWithTrackedResources(
                constantFlow("[{\"id\":\"command\",\"type\":\"cli\",\"config\":{\"stdin\":false}}]"),
                StepCatalog.of(constantStep()),
                InputStream.nullInputStream(),
                "cli",
                "command"
        )).isEqualTo(new ResourceInvocation(
                new Invocation(0, "{\"message\":\"started\"}\n", ""),
                true,
                true
        ));
    }

    @Test
    void packagedRunCommandHasNoCompatibilityFallback() {
        assertThat(invoke(
                constantFlow("[{\"id\":\"command\",\"type\":\"cli\",\"config\":{\"stdin\":false}}]"),
                StepCatalog.of(constantStep()),
                InputStream.nullInputStream(),
                "run",
                "input.json"
        )).isEqualTo(new Invocation(2, "", APPLICATION_USAGE + "\n"));
    }

    @Test
    void lockCheckDoesNotExecuteStartupTriggers() {
        final AtomicInteger runs = new AtomicInteger();

        assertThat(new InvocationWithRuns(invoke(
                counterFlow("[{\"id\":\"initialize\",\"type\":\"startup\",\"config\":{}}]"),
                StepCatalog.of(counterStep(runs)),
                InputStream.nullInputStream(),
                "lock",
                "--check"
        ), runs.get())).isEqualTo(new InvocationWithRuns(
                new Invocation(0, "", ""),
                0
        ));
    }

    @Test
    void malformedCliEventIsRejectedBeforeStartupRuns() {
        final AtomicInteger runs = new AtomicInteger();
        final String flow = counterFlow("""
                [
                  {"id":"initialize","type":"startup","config":{}},
                  {"id":"command","type":"cli","config":{"stdin":true}}
                ]
                """);

        final Invocation invocation = invoke(
                flow,
                StepCatalog.of(counterStep(runs)),
                json("{"),
                "cli",
                "command"
        );

        assertThat(new EventRejectionObservation(
                invocation.exitCode(),
                invocation.stdout(),
                invocation.stderr().contains("\"status\":\"event-rejected\""),
                invocation.stderr().contains("\"code\":\"DATA_JSON_INVALID\""),
                runs.get()
        )).isEqualTo(new EventRejectionObservation(2, "", true, true, 0));
    }

    @Test
    void emptyCliStdinIsRejectedAsInvalidJson() {
        assertEventDiagnostic(
                invoke(
                        directFlow("""
                                [{"id":"command","type":"cli","config":{"stdin":true}}]
                                """, "{\"text\":\"string\"}", "{}",
                                "[]"),
                        StepCatalog.of(noopStep()),
                        InputStream.nullInputStream(),
                        "cli",
                        "command"
                ),
                "DATA_JSON_INVALID"
        );
    }

    @Test
    void nonObjectCliStdinIsRejectedBeforeFlowAdmission() {
        assertEventDiagnostic(
                invoke(
                        directFlow("""
                                [{"id":"command","type":"cli","config":{"stdin":true}}]
                                """, "{\"text\":\"string\"}", "{}",
                                "[]"),
                        StepCatalog.of(noopStep()),
                        json("[]"),
                        "cli",
                        "command"
                ),
                "FLOW_INPUT_OBJECT_REQUIRED"
        );
    }

    @Test
    void invalidUtf8CliStdinIsRejectedBeforeFlowAdmission() {
        assertEventDiagnostic(
                invoke(
                        directFlow("""
                                [{"id":"command","type":"cli","config":{"stdin":true}}]
                                """, "{\"text\":\"string\"}", "{}",
                                "[]"),
                        StepCatalog.of(noopStep()),
                        new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28}),
                        "cli",
                        "command"
                ),
                "DATA_SOURCE_UTF8_INVALID"
        );
    }

    @Test
    void oversizedCliStdinIsRejectedAtThePublishedEventLimit() {
        assertEventDiagnostic(
                invoke(
                        directFlow("""
                                [{"id":"command","type":"cli","config":{"stdin":true}}]
                                """, "{\"text\":\"string\"}", "{}",
                                "[]"),
                        StepCatalog.of(noopStep()),
                        new ByteArrayInputStream(new byte[RailixData.DEFAULT_MAX_SOURCE_BYTES + 1]),
                        "cli",
                        "command"
                ),
                "DATA_SOURCE_TOO_LARGE"
        );
    }

    @Test
    void overDepthCliStdinIsRejectedBeforeFlowAdmission() {
        final String source = "{\"value\":".repeat(RailixData.DEFAULT_MAX_DEPTH + 1)
                + "0"
                + "}".repeat(RailixData.DEFAULT_MAX_DEPTH + 1);

        assertEventDiagnostic(
                invoke(
                        directFlow("""
                                [{"id":"command","type":"cli","config":{"stdin":true}}]
                                """, "{\"text\":\"string\"}", "{}",
                                "[]"),
                        StepCatalog.of(noopStep()),
                        json(source),
                        "cli",
                        "command"
                ),
                "DATA_DEPTH_EXCEEDED"
        );
    }

    @Test
    void unreadableCliStdinReturnsAnIoFailure() {
        final InputStream unreadable = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("unreadable");
            }
        };

        assertThat(invoke(
                directFlow("""
                        [{"id":"command","type":"cli","config":{"stdin":true}}]
                        """, "{\"text\":\"string\"}", "{}",
                        "[]"),
                StepCatalog.of(noopStep()),
                unreadable,
                "cli",
                "command"
        )).isEqualTo(new Invocation(
                4,
                "",
                "{\"error\":{\"code\":\"CLI_STDIN_READ_FAILED\","
                        + "\"message\":\"Could not read CLI stdin.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        ));
    }

    @Test
    void javaNullCliStdinReturnsAnIoFailure() {
        assertThat(invoke(
                directFlow("""
                        [{"id":"command","type":"cli","config":{"stdin":true}}]
                        """, "{\"text\":\"string\"}", "{}",
                        "[]"),
                StepCatalog.of(noopStep()),
                null,
                "cli",
                "command"
        )).isEqualTo(new Invocation(
                4,
                "",
                "{\"error\":{\"code\":\"CLI_STDIN_READ_FAILED\","
                        + "\"message\":\"Could not read CLI stdin.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        ));
    }

    @Test
    void uncheckedCliStdinFailureReturnsDeterministicLauncherError() {
        final InputStream unreadable = new InputStream() {
            @Override
            public int read() {
                throw new IllegalStateException("unreadable");
            }
        };

        assertThat(invoke(
                directFlow("""
                        [{"id":"command","type":"cli","config":{"stdin":true}}]
                        """, "{\"text\":\"string\"}", "{}",
                        "[]"),
                StepCatalog.of(noopStep()),
                unreadable,
                "cli",
                "command"
        )).isEqualTo(new Invocation(
                4,
                "",
                "{\"error\":{\"code\":\"CLI_STDIN_READ_FAILED\","
                        + "\"message\":\"Could not read CLI stdin.\"},"
                        + "\"status\":\"launcher-rejected\"}\n"
        ));
    }

    @Test
    void cliStdinRemainsOwnedByTheCaller() {
        final AtomicInteger closes = new AtomicInteger();
        final InputStream stdin = new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closes.incrementAndGet();
                super.close();
            }
        };

        assertThat(new InvocationWithRuns(invoke(
                constantFlow("[{\"id\":\"command\",\"type\":\"cli\",\"config\":{\"stdin\":true}}]"),
                StepCatalog.of(constantStep()),
                stdin,
                "cli",
                "command"
        ), closes.get())).isEqualTo(new InvocationWithRuns(
                new Invocation(0, "{\"message\":\"started\"}\n", ""),
                0
        ));
    }

    @Test
    void blockedCliStdinStopsOnlyWhenItsOwnerClosesIt() throws Exception {
        final BlockingInputStream stdin = new BlockingInputStream();
        final AtomicReference<Invocation> invocation = new AtomicReference<>();
        final Thread launcher = Thread.ofVirtual().start(() -> invocation.set(invoke(
                constantFlow("[{\"id\":\"command\",\"type\":\"cli\",\"config\":{\"stdin\":true}}]"),
                StepCatalog.of(constantStep()),
                stdin,
                "cli",
                "command"
        )));

        assertThat(stdin.reading.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(launcher.isAlive()).isTrue();
        stdin.close();
        launcher.join(5_000);

        assertThat(List.of(launcher.isAlive(), stdin.closes.get(), invocation.get()))
                .containsExactly(
                        false,
                        1,
                        new Invocation(
                                4,
                                "",
                                "{\"error\":{\"code\":\"CLI_STDIN_READ_FAILED\","
                                        + "\"message\":\"Could not read CLI stdin.\"},"
                                        + "\"status\":\"launcher-rejected\"}\n"
                        )
                );
    }

    @Test
    void triggerExecutionUsesTheCallingThread() {
        final Thread caller = Thread.currentThread();
        final AtomicReference<Thread> executed = new AtomicReference<>();
        final StepDefinition step = StepDefinition.named("step", "1.0.0")
                .output("message", ValueShape.string())
                .outcome("ok")
                .run(input -> {
                    executed.set(Thread.currentThread());
                    return StepResult.outcome("ok").output("message", RailixValue.string("started"));
                });

        assertThat(invoke(
                constantFlow("[{\"id\":\"initialize\",\"type\":\"startup\",\"config\":{}}]"),
                StepCatalog.of(step),
                InputStream.nullInputStream()
        )).isEqualTo(new Invocation(0, "{\"message\":\"started\"}\n", ""));
        assertThat(executed.get()).isSameAs(caller);
    }

    @Test
    void missingRequiredCliEventInputUsesTheCanonicalRunRejection() {
        final Invocation invocation = invoke(
                directFlow("""
                        [{"id":"command","type":"cli","config":{"stdin":true}}]
                        """, "{\"text\":\"string\"}", "{}",
                        "[]"),
                StepCatalog.of(noopStep()),
                json("{}"),
                "cli",
                "command"
        );

        assertThat(new DiagnosticObservation(
                invocation.exitCode(),
                invocation.stdout(),
                invocation.stderr().contains("\"status\":\"run-rejected\""),
                invocation.stderr().contains("\"code\":\"FLOW_INPUT_REQUIRED\""),
                invocation.stderr().contains("\"path\":\"inputs.text\"")
        )).isEqualTo(new DiagnosticObservation(2, "", true, true, true));
    }

    @Test
    void laterStartupFailureStopsTheSequenceAfterVisiblePriorOutput() {
        final AtomicInteger runs = new AtomicInteger();
        final StepDefinition step = StepDefinition.named("step", "1.0.0")
                .output("sequence", ValueShape.number())
                .outcome("ok")
                .run(input -> runs.incrementAndGet() == 1
                        ? StepResult.outcome("ok").output("sequence", RailixValue.number(1))
                        : StepResult.outcome("ok"));

        final Invocation invocation = invoke(
                counterFlow("""
                        [
                          {"id":"first","type":"startup","config":{}},
                          {"id":"second","type":"startup","config":{}},
                          {"id":"third","type":"startup","config":{}}
                        ]
                        """),
                StepCatalog.of(step),
                InputStream.nullInputStream()
        );

        assertThat(new SequenceFailureObservation(
                invocation.exitCode(),
                invocation.stdout(),
                invocation.stderr().contains("\"status\":\"run-failed\""),
                runs.get()
        )).isEqualTo(new SequenceFailureObservation(
                3,
                "{\"sequence\":1}\n",
                true,
                2
        ));
    }

    @Test
    void startupFailurePreventsTheSelectedCliTrigger() {
        final AtomicInteger runs = new AtomicInteger();
        final StepDefinition broken = StepDefinition.named("step", "1.0.0")
                .output("message", ValueShape.string())
                .outcome("ok")
                .run(input -> {
                    runs.incrementAndGet();
                    return StepResult.outcome("ok");
                });

        final Invocation invocation = invoke(
                constantFlow("""
                        [
                          {"id":"initialize","type":"startup","config":{}},
                          {"id":"command","type":"cli","config":{"stdin":false}}
                        ]
                        """),
                StepCatalog.of(broken),
                InputStream.nullInputStream(),
                "cli",
                "command"
        );

        assertThat(new SequenceFailureObservation(
                invocation.exitCode(),
                invocation.stdout(),
                invocation.stderr().contains("\"status\":\"run-failed\""),
                runs.get()
        )).isEqualTo(new SequenceFailureObservation(3, "", true, 1));
    }

    @Test
    void startupStepContractFailureUsesExitCodeThree() {
        assertThat(invoke(
                constantFlow("[{\"id\":\"initialize\",\"type\":\"startup\",\"config\":{}}]"),
                StepCatalog.of(brokenStep()),
                InputStream.nullInputStream()
        )).isEqualTo(new Invocation(
                3,
                "",
                "{\"failure\":{\"code\":\"STEP_OUTPUT_REQUIRED\","
                        + "\"message\":\"Step did not produce required output: message\","
                        + "\"step\":\"step\"},\"status\":\"run-failed\",\"steps\":[]}\n"
        ));
    }

    @Test
    void interruptedStartupUsesExitCodeOneHundredThirty() throws InterruptedException {
        final AtomicReference<Invocation> invocation = new AtomicReference<>();
        final Thread thread = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            invocation.set(invoke(
                    constantFlow("[{\"id\":\"initialize\",\"type\":\"startup\",\"config\":{}}]"),
                    StepCatalog.of(constantStep()),
                    InputStream.nullInputStream()
            ));
        });
        thread.join();

        assertThat(invocation.get()).isEqualTo(new Invocation(
                130,
                "",
                "{\"status\":\"run-cancelled\",\"steps\":[]}\n"
        ));
    }

    @Test
    void packagedApplicationCanBeInvokedRepeatedly() {
        final AtomicInteger runs = new AtomicInteger();
        final String flow = counterFlow("[{\"id\":\"initialize\",\"type\":\"startup\",\"config\":{}}]");
        final StepCatalog catalog = StepCatalog.of(counterStep(runs));

        assertThat(List.of(
                invoke(flow, catalog, InputStream.nullInputStream()),
                invoke(flow, catalog, InputStream.nullInputStream())
        )).containsExactly(
                new Invocation(0, "{\"sequence\":1}\n", ""),
                new Invocation(0, "{\"sequence\":2}\n", "")
        );
    }

    @Test
    void repeatedCliArgumentsDoNotRetainThePriorEvent() {
        final String flow = directFlow("""
                [{"id":"command","type":"cli","config":{"stdin":false,"arguments":"arguments"}}]
                """, "{\"arguments\":\"array\"}", "{\"arguments\":\"array\"}",
                "[{\"from\":\"input.arguments\",\"to\":\"output.arguments\"}]");
        final StepCatalog catalog = StepCatalog.of(noopStep());

        assertThat(List.of(
                invoke(flow, catalog, InputStream.nullInputStream(), "cli", "command", "first"),
                invoke(flow, catalog, InputStream.nullInputStream(), "cli", "command", "second")
        )).containsExactly(
                new Invocation(0, "{\"arguments\":[\"first\"]}\n", ""),
                new Invocation(0, "{\"arguments\":[\"second\"]}\n", "")
        );
    }

    private static StepDefinition noopStep() {
        return StepDefinition.named("step", "1.0.0")
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));
    }

    private static StepDefinition constantStep() {
        return StepDefinition.named("step", "1.0.0")
                .output("message", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output(
                        "message",
                        RailixValue.string("started")
                ));
    }

    private static StepDefinition brokenStep() {
        return StepDefinition.named("step", "1.0.0")
                .output("message", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));
    }

    private static StepDefinition counterStep(final AtomicInteger runs) {
        return StepDefinition.named("step", "1.0.0")
                .output("sequence", ValueShape.number())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output(
                        "sequence",
                        RailixValue.number(runs.incrementAndGet())
                ));
    }

    private static String directFlow(
            final String triggers,
            final String inputs,
            final String outputs,
            final String connections
    ) {
        return flow(triggers, inputs, outputs, connections);
    }

    private static String constantFlow(final String triggers) {
        return flow(
                triggers,
                "{}",
                "{\"message\":\"string\"}",
                "[{\"from\":\"step.message\",\"to\":\"output.message\"}]"
        );
    }

    private static String counterFlow(final String triggers) {
        return flow(
                triggers,
                "{}",
                "{\"sequence\":\"number\"}",
                "[{\"from\":\"step.sequence\",\"to\":\"output.sequence\"}]"
        );
    }

    private static String flow(
            final String triggers,
            final String inputs,
            final String outputs,
            final String connections
    ) {
        return """
                {
                  "id":"application-trigger-launcher",
                  "triggers":%s,
                  "entry":"step",
                  "inputs":%s,
                  "outputs":%s,
                  "steps":[{"id":"step","use":"step","config":{},"on":{"ok":"end"}}],
                  "connections":%s
                }
                """.formatted(triggers, inputs, outputs, connections);
    }

    private static InputStream json(final String source) {
        return new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertEventDiagnostic(final Invocation invocation, final String code) {
        assertThat(new DiagnosticObservation(
                invocation.exitCode(),
                invocation.stdout(),
                invocation.stderr().contains("\"status\":\"event-rejected\""),
                invocation.stderr().contains("\"code\":\"" + code + "\""),
                invocation.stderr().contains("\"path\":\"stdin\"")
        )).isEqualTo(new DiagnosticObservation(2, "", true, true, true));
    }

    private static Invocation invoke(
            final String flow,
            final StepCatalog catalog,
            final InputStream stdin,
            final String... arguments
    ) {
        return invoke(flow, catalog, stdin, List.of(arguments));
    }

    private static Invocation invoke(
            final String flow,
            final StepCatalog catalog,
            final InputStream stdin,
            final List<String> arguments
    ) {
        final CompileResult.Compiled compiled = (CompileResult.Compiled) FlowCompiler.compile(flow, catalog);
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final int exitCode = RailixMain.executeApplication(
                arguments,
                json(flow),
                json(compiled.lock()),
                catalog,
                stdin,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Invocation(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8)
        );
    }

    private static ResourceInvocation invokeWithTrackedResources(
            final String flow,
            final StepCatalog catalog,
            final InputStream stdin,
            final String... arguments
    ) {
        final CompileResult.Compiled compiled = (CompileResult.Compiled) FlowCompiler.compile(flow, catalog);
        final TrackingInputStream flowSource = new TrackingInputStream(flow);
        final TrackingInputStream lockSource = new TrackingInputStream(compiled.lock());
        final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final int exitCode = RailixMain.executeApplication(
                List.of(arguments),
                flowSource,
                lockSource,
                catalog,
                stdin,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new ResourceInvocation(
                new Invocation(
                        exitCode,
                        stdout.toString(StandardCharsets.UTF_8),
                        stderr.toString(StandardCharsets.UTF_8)
                ),
                flowSource.closed,
                lockSource.closed
        );
    }

    private record Invocation(int exitCode, String stdout, String stderr) {
    }

    private record ResourceInvocation(Invocation invocation, boolean flowClosed, boolean lockClosed) {
    }

    private record InvocationWithRuns(Invocation invocation, int runs) {
    }

    private record EventRejectionObservation(
            int exitCode,
            String stdout,
            boolean rejected,
            boolean diagnostic,
            int runs
    ) {
    }

    private record DiagnosticObservation(
            int exitCode,
            String stdout,
            boolean rejected,
            boolean diagnostic,
            boolean path
    ) {
    }

    private record SequenceFailureObservation(
            int exitCode,
            String stdout,
            boolean failed,
            int runs
    ) {
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch reading = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public int read() throws IOException {
            reading.countDown();
            try {
                closed.await();
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", exception);
            }
            throw new IOException("closed by owner");
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(final String source) {
            super(source.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
