package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationSocketIngressE2eTest {
    private static final PrintStream DISCARD = new PrintStream(OutputStream.nullOutputStream());

    @Test
    void socketFrameRunsTheCompiledFlowAndReturnsTheCanonicalReply() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.send(json("HELLO"));

            assertThat(client.reply()).isEqualTo(
                    "{\"outputs\":{\"text\":\"hello\"},\"status\":\"succeeded\","
                            + "\"steps\":[{\"outcome\":\"ok\",\"step\":\"lowercase\"}]}"
            );
        } finally {
            runtime.stop();
        }
    }

    @Test
    void socketConnectionProcessesSequentialFramesWithoutRetainingPriorInput() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.send(json("FIRST"));
            final String first = client.reply();
            client.send(json("SECOND"));
            final String second = client.reply();

            assertThat(List.of(first, second)).containsExactly(
                    success("first"),
                    success("second")
            );
        } finally {
            runtime.stop();
        }
    }

    @Test
    void lengthFramingAcceptsFormattedJsonContainingNewlines() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.send("""
                    {
                      "text": "MULTILINE"
                    }
                    """);

            assertThat(client.reply()).isEqualTo(success("multiline"));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void malformedJsonReturnsOneRejectionAndLeavesTheConnectionReusable() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.send("{");
            final String rejected = client.reply();
            client.send(json("RECOVERED"));
            final String recovered = client.reply();

            assertThat(new ReplyPair(rejected, recovered)).isEqualTo(new ReplyPair(
                    error(
                            "SOCKET_EVENT_JSON_INVALID",
                            "Expected an object field name."
                    ),
                    success("recovered")
            ));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void invalidUtf8ReturnsOneRejectionAndLeavesTheConnectionReusable() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.send(new byte[]{(byte) 0xc3, 0x28});
            final String rejected = client.reply();
            client.send(json("RECOVERED"));
            final String recovered = client.reply();

            assertThat(new ReplyPair(rejected, recovered)).isEqualTo(new ReplyPair(
                    error(
                            "SOCKET_EVENT_UTF8_INVALID",
                            "Data source is not valid UTF-8."
                    ),
                    success("recovered")
            ));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void nonObjectJsonReturnsCanonicalFlowAdmissionDiagnostics() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.send("[]");

            assertThat(client.reply()).isEqualTo(
                    "{\"diagnostics\":[{\"code\":\"FLOW_INPUT_OBJECT_REQUIRED\",\"column\":0,"
                            + "\"line\":0,\"message\":\"Flow inputs must be an object.\","
                            + "\"path\":\"inputs\"}],\"status\":\"run-rejected\",\"steps\":[]}"
            );
        } finally {
            runtime.stop();
        }
    }

    @Test
    void exactMaximumSocketEventIsAccepted() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.send(paddedEvent(RailixData.DEFAULT_MAX_SOURCE_BYTES));

            assertThat(client.reply()).isEqualTo(success("hello"));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void oversizedFrameLengthIsRejectedWithoutReadingTheClaimedBody() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.sendLength(RailixData.DEFAULT_MAX_SOURCE_BYTES + 1);

            assertThat(new ClosedReply(client.reply(), client.readAfterReply())).isEqualTo(new ClosedReply(
                    error(
                            "SOCKET_FRAME_TOO_LARGE",
                            "Socket event exceeds the " + RailixData.DEFAULT_MAX_SOURCE_BYTES + "-byte limit."
                    ),
                    -1
            ));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void zeroFrameLengthIsRejectedAndClosesTheConnection() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.sendLength(0);

            assertThat(new ClosedReply(client.reply(), client.readAfterReply())).isEqualTo(new ClosedReply(
                    error(
                            "SOCKET_FRAME_LENGTH_INVALID",
                            "Socket frame length must be between 1 and "
                                    + RailixData.DEFAULT_MAX_SOURCE_BYTES + "."
                    ),
                    -1
            ));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void negativeFrameLengthIsRejectedAndClosesTheConnection() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.sendLength(-1);

            assertThat(new ClosedReply(client.reply(), client.readAfterReply())).isEqualTo(new ClosedReply(
                    error(
                            "SOCKET_FRAME_LENGTH_INVALID",
                            "Socket frame length must be between 1 and "
                                    + RailixData.DEFAULT_MAX_SOURCE_BYTES + "."
                    ),
                    -1
            ));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void truncatedLengthPrefixReturnsAFramedErrorThenCloses() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.raw().write(new byte[]{0, 0});
            client.shutdownOutput();

            assertThat(new ClosedReply(client.reply(), client.readAfterReply())).isEqualTo(new ClosedReply(
                    error("SOCKET_FRAME_TRUNCATED", "Socket frame ended before its declared length."),
                    -1
            ));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void truncatedFrameBodyReturnsAFramedErrorThenCloses() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.sendLength(10);
            client.raw().write(new byte[]{'{', '}'});
            client.shutdownOutput();

            assertThat(new ClosedReply(client.reply(), client.readAfterReply())).isEqualTo(new ClosedReply(
                    error("SOCKET_FRAME_TRUNCATED", "Socket frame ended before its declared length."),
                    -1
            ));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void frameReadUsesAnAbsoluteDeadlineAndClosesTheConnection() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 120, 32));
        try (FrameClient client = connect(port)) {
            final long started = System.nanoTime();
            client.raw().write(0);
            TimeUnit.MILLISECONDS.sleep(80);
            client.raw().write(0);

            final ClosedReply reply = new ClosedReply(client.reply(), client.readAfterReply());
            final Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            assertThat(new TimeoutObservation(reply, elapsed.toMillis() < 240)).isEqualTo(
                    new TimeoutObservation(
                            new ClosedReply(
                                    error(
                                            "SOCKET_FRAME_TIMEOUT",
                                            "Socket frame was not received within 120 milliseconds."
                                    ),
                                    -1
                            ),
                            true
                    )
            );
        } finally {
            runtime.stop();
        }
    }

    @Test
    void frameBodyUsesTheSameAbsoluteDeadlineAndClosesTheConnection() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 120, 32));
        try (FrameClient client = connect(port)) {
            final long started = System.nanoTime();
            client.sendLength(4);
            client.raw().write('{');

            final ClosedReply reply = new ClosedReply(client.reply(), client.readAfterReply());
            final Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            assertThat(new TimeoutObservation(reply, elapsed.toMillis() < 240)).isEqualTo(
                    new TimeoutObservation(
                            new ClosedReply(
                                    error(
                                            "SOCKET_FRAME_TIMEOUT",
                                            "Socket frame was not received within 120 milliseconds."
                                    ),
                                    -1
                            ),
                            true
                    )
            );
        } finally {
            runtime.stop();
        }
    }

    @Test
    void connectionLimitRejectsExcessClientsWithoutQueueingThem() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 1));
        try (FrameClient occupied = connect(port)) {
            occupied.raw().write(0);
            try (FrameClient rejected = connect(port)) {
                assertThat(new ClosedReply(rejected.reply(), rejected.readAfterReply()))
                        .isEqualTo(new ClosedReply(
                                error(
                                        "SOCKET_BACKPRESSURE",
                                        "Socket connection limit is reached."
                                ),
                                -1
                        ));
            }
        } finally {
            runtime.stop();
        }
    }

    @Test
    void socketListenerRejectsEventsUntilApplicationReadiness() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = ApplicationRuntime.start(
                echoFlow(port, 30_000, 32),
                DISCARD,
                DISCARD
        );
        try (FrameClient client = connect(port)) {
            assertThat(new ClosedReply(client.reply(), client.readAfterReply())).isEqualTo(new ClosedReply(
                    error(
                            "APPLICATION_STARTING",
                            "Application startup has not completed."
                    ),
                    -1
            ));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void socketAdmissionIsOpenWhenTheReadinessLineIsPublished() throws Exception {
        final int port = freePort();
        final BlockingReadinessOutput output = new BlockingReadinessOutput();
        final ApplicationRuntime runtime = ApplicationRuntime.start(
                echoFlow(port, 30_000, 32),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                DISCARD
        );
        final Thread announcing = Thread.startVirtualThread(runtime::ready);
        try {
            assertThat(output.published.await(2, TimeUnit.SECONDS)).isTrue();
            try (FrameClient client = connect(port)) {
                client.send(json("READY"));
                assertThat(client.reply()).isEqualTo(success("ready"));
            }
        } finally {
            output.release.countDown();
            announcing.join(Duration.ofSeconds(2));
            runtime.stop();
        }
    }

    @Test
    void socketListenerBindsOnlyIpv4Loopback() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try {
            assertThat(canConnect(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port)).isTrue();
        } finally {
            runtime.stop();
        }
    }

    @Test
    void stopIsIdempotentAndReleasesTheSocketPort() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));

        assertThat(List.of(runtime.stop(), runtime.stop())).containsOnly(runtime);
        assertThat(canBind(port)).isTrue();
    }

    @Test
    void missingFlowInputReturnsCanonicalAdmissionDiagnostics() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.send("{}");

            assertThat(client.reply()).contains(
                    "\"code\":\"FLOW_INPUT_REQUIRED\"",
                    "\"path\":\"inputs.text\"",
                    "\"status\":\"run-rejected\""
            );
        } finally {
            runtime.stop();
        }
    }

    @Test
    void wrongFlowInputShapeReturnsCanonicalAdmissionDiagnostics() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.send("{\"text\":7}");

            assertThat(client.reply()).contains(
                    "\"code\":\"FLOW_INPUT_TYPE_MISMATCH\"",
                    "\"path\":\"inputs.text\"",
                    "\"status\":\"run-rejected\""
            );
        } finally {
            runtime.stop();
        }
    }

    @Test
    void unknownFlowInputReturnsCanonicalAdmissionDiagnostics() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.send("{\"text\":\"HELLO\",\"extra\":true}");

            assertThat(client.reply()).contains(
                    "\"code\":\"FLOW_INPUT_UNKNOWN\"",
                    "\"path\":\"inputs.extra\"",
                    "\"status\":\"run-rejected\""
            );
        } finally {
            runtime.stop();
        }
    }

    @Test
    void overDepthEventReturnsOneRejectionAndLeavesTheConnectionReusable() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.send("{\"text\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}");
            final String rejected = client.reply();
            client.send(json("RECOVERED"));
            final String recovered = client.reply();

            assertThat(new ReplyPair(rejected, recovered)).isEqualTo(new ReplyPair(
                    error(
                            "SOCKET_EVENT_DEPTH_EXCEEDED",
                            "Data exceeds the maximum container depth of 64."
                    ),
                    success("recovered")
            ));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void overLimitNumberReturnsOneRejectionAndLeavesTheConnectionReusable() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            client.send("{\"text\":" + "1" + "0".repeat(1_024) + "}");
            final String rejected = client.reply();
            client.send(json("RECOVERED"));
            final String recovered = client.reply();

            assertThat(new ReplyPair(rejected, recovered)).isEqualTo(new ReplyPair(
                    error(
                            "SOCKET_EVENT_NUMBER_LIMIT_EXCEEDED",
                            "JSON number exceeds the 1024-character source limit."
                    ),
                    success("recovered")
            ));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void utf8BomReturnsOneRejectionAndLeavesTheConnectionReusable() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (FrameClient client = connect(port)) {
            final byte[] json = json("HELLO").getBytes(StandardCharsets.UTF_8);
            final byte[] source = new byte[json.length + 3];
            source[0] = (byte) 0xef;
            source[1] = (byte) 0xbb;
            source[2] = (byte) 0xbf;
            System.arraycopy(json, 0, source, 3, json.length);
            client.send(source);
            final String rejected = client.reply();
            client.send(json("RECOVERED"));
            final String recovered = client.reply();

            assertThat(new ReplyPair(rejected, recovered)).isEqualTo(new ReplyPair(
                    error(
                            "SOCKET_EVENT_BOM_UNSUPPORTED",
                            "UTF-8 BOM is not supported."
                    ),
                    success("recovered")
            ));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void stepImplementationFaultReturnsTheCanonicalFailure() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(actionFlow(
                port,
                30_000,
                32,
                input -> {
                    throw new IllegalStateException("broken");
                }
        ));
        try (FrameClient client = connect(port)) {
            client.send(json("HELLO"));

            assertThat(client.reply()).contains(
                    "\"code\":\"STEP_IMPLEMENTATION_FAULT\"",
                    "\"status\":\"run-failed\"",
                    "\"step\":\"lowercase\""
            );
        } finally {
            runtime.stop();
        }
    }

    @Test
    void stepContractFaultReturnsTheCanonicalFailure() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(actionFlow(
                port,
                30_000,
                32,
                input -> StepResult.outcome("ok").output("text", RailixValue.number(7))
        ));
        try (FrameClient client = connect(port)) {
            client.send(json("HELLO"));

            assertThat(client.reply()).contains(
                    "\"code\":\"STEP_OUTPUT_TYPE_MISMATCH\"",
                    "\"status\":\"run-failed\"",
                    "\"step\":\"lowercase\""
            );
        } finally {
            runtime.stop();
        }
    }

    @Test
    void oversizedReplyReturnsOneBoundedErrorAndLeavesTheConnectionReusable() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(actionFlow(
                port,
                30_000,
                32,
                input -> StepResult.outcome("ok").output(
                        "text",
                        RailixValue.string("x".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES))
                )
        ));
        try (FrameClient client = connect(port)) {
            client.send(json("FIRST"));
            final String first = client.reply();
            client.send(json("SECOND"));
            final String second = client.reply();

            assertThat(List.of(first, second)).containsExactly(
                    error(
                            "SOCKET_REPLY_TOO_LARGE",
                            "Socket reply exceeds the " + RailixData.DEFAULT_MAX_SOURCE_BYTES + "-byte limit."
                    ),
                    error(
                            "SOCKET_REPLY_TOO_LARGE",
                            "Socket reply exceeds the " + RailixData.DEFAULT_MAX_SOURCE_BYTES + "-byte limit."
                    )
            );
        } finally {
            runtime.stop();
        }
    }

    @Test
    void replyWriteDeadlineClosesASlowPeerAndReleasesItsConnectionSlot() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(actionFlow(
                port,
                50,
                1,
                input -> {
                    final String text = ((RailixValue.StringValue) input.value("text")).value();
                    return StepResult.outcome("ok").output(
                            "text",
                            RailixValue.string("BLOCK".equals(text)
                                    ? "x".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - 256)
                                    : text.toLowerCase(java.util.Locale.ROOT))
                    );
                }
        ));
        try (FrameClient slow = connect(port, 1_024)) {
            slow.send(json("BLOCK"));
            TimeUnit.MILLISECONDS.sleep(300);
            try (FrameClient recovered = connect(port)) {
                recovered.send(json("RECOVERED"));
                assertThat(recovered.reply()).isEqualTo(success("recovered"));
            }
        } finally {
            runtime.stop();
        }
    }

    @Test
    void concurrentSocketEventsRemainIsolated() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 30_000, 32));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<String>> replies = new java.util.ArrayList<>();
            for (int index = 0; index < 32; index++) {
                final String text = "EVENT-" + index;
                replies.add(executor.submit(() -> {
                    try (FrameClient client = connect(port)) {
                        client.send(json(text));
                        return client.reply();
                    }
                }));
            }
            for (int index = 0; index < replies.size(); index++) {
                assertThat(replies.get(index).get(2, TimeUnit.SECONDS))
                        .isEqualTo(success(("EVENT-" + index).toLowerCase(java.util.Locale.ROOT)));
            }
        } finally {
            runtime.stop();
        }
    }

    @Test
    void stopClosesAnIdleConnectionWithoutWaitingForItsFrameDeadline() throws Exception {
        final int port = freePort();
        final ApplicationRuntime runtime = start(echoFlow(port, 300_000, 1));
        try (FrameClient client = connect(port)) {
            client.raw().write(0);
            try (FrameClient probe = connect(port)) {
                assertThat(probe.reply()).contains("\"code\":\"SOCKET_BACKPRESSURE\"");
            }
            final long started = System.nanoTime();

            runtime.stop();

            assertThat(new IdleStopObservation(
                    client.readAfterReply(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis() < 500,
                    await(() -> canBind(port), Duration.ofSeconds(2))
            )).isEqualTo(new IdleStopObservation(-1, true, true));
        }
    }

    @Test
    void stopInterruptsAnActiveCooperativeSocketStep() throws Exception {
        final int port = freePort();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final ApplicationRuntime runtime = start(actionFlow(
                port,
                30_000,
                1,
                input -> {
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (final InterruptedException exception) {
                        interrupted.countDown();
                        throw exception;
                    }
                    return StepResult.outcome("ok").output("text", RailixValue.string("never"));
                }
        ));
        try (FrameClient client = connect(port)) {
            client.send(json("HELLO"));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            runtime.stop();

            assertThat(new CooperativeStopObservation(
                    interrupted.await(2, TimeUnit.SECONDS),
                    client.readAfterReply(),
                    canBind(port)
            )).isEqualTo(new CooperativeStopObservation(true, -1, true));
        }
    }

    @Test
    void stopReturnsWhenAnUnsupportedNonCooperativeSocketStepIgnoresInterruption() throws Exception {
        final int port = freePort();
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicBoolean release = new AtomicBoolean();
        final AtomicBoolean observedInterrupt = new AtomicBoolean();
        final ApplicationRuntime runtime = start(actionFlow(
                port,
                30_000,
                1,
                input -> {
                    entered.countDown();
                    while (!release.get()) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(5);
                        } catch (final InterruptedException exception) {
                            observedInterrupt.set(true);
                        }
                    }
                    return StepResult.outcome("ok").output("text", RailixValue.string("released"));
                }
        ));
        try (FrameClient client = connect(port)) {
            client.send(json("HELLO"));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            final long started = System.nanoTime();

            runtime.stop();

            assertThat(new NonCooperativeStopObservation(
                    Duration.ofNanos(System.nanoTime() - started).toMillis() < 500,
                    await(observedInterrupt::get, Duration.ofSeconds(2)),
                    canBind(port)
            )).isEqualTo(new NonCooperativeStopObservation(true, true, true));
        } finally {
            release.set(true);
        }
    }

    private static ApplicationRuntime start(final CompiledFlow flow) throws IOException {
        return ApplicationRuntime.start(flow, DISCARD, DISCARD).ready();
    }

    private static FrameClient connect(final int port) throws IOException {
        return connect(port, 0);
    }

    private static FrameClient connect(final int port, final int receiveBufferBytes) throws IOException {
        final Socket socket = new Socket();
        if (receiveBufferBytes > 0) {
            socket.setReceiveBufferSize(receiveBufferBytes);
        }
        socket.connect(new java.net.InetSocketAddress(
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}),
                port
        ));
        socket.setSoTimeout(2_000);
        return new FrameClient(socket);
    }

    private static CompiledFlow echoFlow(
            final int port,
            final int timeoutMillis,
            final int maxConnections
    ) {
        return actionFlow(port, timeoutMillis, maxConnections, input -> {
            final String text = ((RailixValue.StringValue) input.value("text")).value();
            return StepResult.outcome("ok").output(
                    "text",
                    RailixValue.string(text.toLowerCase(java.util.Locale.ROOT))
            );
        });
    }

    private static CompiledFlow actionFlow(
            final int port,
            final int timeoutMillis,
            final int maxConnections,
            final StepHandler handler
    ) {
        final String source = """
                {
                  "id":"socket-app",
                  "triggers":[{"id":"events","type":"socket","config":{
                    "port":%d,
                    "timeoutMillis":%d,
                    "maxConnections":%d
                  }}],
                  "entry":"lowercase",
                  "inputs":{"text":"string"},
                  "outputs":{"text":"string"},
                  "steps":[
                    {"id":"lowercase","use":"lowercase","config":{},"on":{"ok":"end"}}
                  ],
                  "connections":[
                    {"from":"input.text","to":"lowercase.text"},
                    {"from":"lowercase.text","to":"output.text"}
                  ]
                }
                """.formatted(port, timeoutMillis, maxConnections);
        return ((CompileResult.Compiled) FlowCompiler.compile(source, StepCatalog.of(
                StepDefinition.named("lowercase", "1.0.0")
                        .input("text", ValueShape.STRING)
                        .output("text", ValueShape.STRING)
                        .outcome("ok")
                        .run(handler)
        ))).flow();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(
                0,
                1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1})
        )) {
            return socket.getLocalPort();
        }
    }

    private static boolean canBind(final int port) {
        try (ServerSocket ignored = new ServerSocket(
                port,
                1,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1})
        )) {
            return true;
        } catch (final IOException exception) {
            return false;
        }
    }

    private static boolean canConnect(final InetAddress address, final int port) {
        try (Socket ignored = new Socket(address, port)) {
            return true;
        } catch (final IOException exception) {
            return false;
        }
    }

    private static boolean await(
            final BooleanSupplier condition,
            final Duration timeout
    ) throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return condition.getAsBoolean();
    }

    private static byte[] paddedEvent(final int bytes) {
        final byte[] prefix = json("HELLO").getBytes(StandardCharsets.UTF_8);
        final byte[] result = new byte[bytes];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        Arrays.fill(result, prefix.length, result.length, (byte) ' ');
        return result;
    }

    private static String json(final String text) {
        return "{\"text\":\"" + text + "\"}";
    }

    private static String success(final String text) {
        return "{\"outputs\":{\"text\":\"" + text + "\"},\"status\":\"succeeded\","
                + "\"steps\":[{\"outcome\":\"ok\",\"step\":\"lowercase\"}]}";
    }

    private static String error(final String code, final String message) {
        return "{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message
                + "\"},\"status\":\"event-rejected\"}";
    }

    private record ReplyPair(String rejected, String recovered) {
    }

    private record ClosedReply(String reply, int trailingByte) {
    }

    private record TimeoutObservation(ClosedReply reply, boolean absolute) {
    }

    private record IdleStopObservation(int trailingByte, boolean returned, boolean portReleased) {
    }

    private record CooperativeStopObservation(boolean interrupted, int trailingByte, boolean portReleased) {
    }

    private record NonCooperativeStopObservation(boolean returned, boolean interrupted, boolean portReleased) {
    }

    private static final class FrameClient implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;

        private FrameClient(final Socket socket) throws IOException {
            this.socket = socket;
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
        }

        private FrameClient send(final String source) throws IOException {
            return send(source.getBytes(StandardCharsets.UTF_8));
        }

        private FrameClient send(final byte[] source) throws IOException {
            output.writeInt(source.length);
            output.write(source);
            output.flush();
            return this;
        }

        private FrameClient sendLength(final int length) throws IOException {
            output.writeInt(length);
            output.flush();
            return this;
        }

        private OutputStream raw() {
            return output;
        }

        private FrameClient shutdownOutput() throws IOException {
            socket.shutdownOutput();
            return this;
        }

        private String reply() throws IOException {
            final int length = input.readInt();
            return new String(input.readNBytes(length), StandardCharsets.UTF_8);
        }

        private int readAfterReply() throws IOException {
            return input.read();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static final class BlockingReadinessOutput extends OutputStream {
        private final CountDownLatch published = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void write(final int value) throws IOException {
            if (value == '\n') {
                published();
            }
        }

        @Override
        public void write(final byte[] source, final int offset, final int length) throws IOException {
            for (int index = offset; index < offset + length; index++) {
                if (source[index] == '\n') {
                    published();
                    return;
                }
            }
        }

        private void published() throws IOException {
            published.countDown();
            try {
                release.await();
            } catch (final InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Readiness publication was interrupted.", exception);
            }
        }
    }
}
