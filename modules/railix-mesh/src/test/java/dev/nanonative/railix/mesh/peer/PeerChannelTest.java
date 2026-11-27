package dev.nanonative.railix.mesh.peer;

import dev.nanonative.railix.core.project.FlowContinuation;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.mesh.MeshErrors;
import dev.nanonative.railix.mesh.PeerEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerChannelTest {
    @Test
    void onlyOneChannelCanClaimTheSameSocket() throws Exception {
        final CyclicBarrier start = new CyclicBarrier(2);
        try (PeerStreamSocket socket = new PeerStreamSocket(
                InputStream.nullInputStream(), OutputStream.nullOutputStream());
             ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            final Callable<Optional<PeerChannel>> claim = () -> {
                start.await(2, SECONDS);
                try {
                    return Optional.of(new PeerChannel(socket));
                } catch (final IllegalStateException duplicate) {
                    return Optional.empty();
                }
            };
            final Future<Optional<PeerChannel>> first = tasks.submit(claim);
            final Future<Optional<PeerChannel>> second = tasks.submit(claim);
            final List<PeerChannel> channels = Stream.of(first.get(2, SECONDS), second.get(2, SECONDS))
                    .flatMap(Optional::stream).toList();
            assertEquals(1, channels.size());
            assertFalse(socket.isClosed());
            channels.getFirst().close();
            assertTrue(socket.isClosed());
            assertThrows(SocketException.class, () -> new PeerChannel(socket));
        }
    }

    @Test
    void aStalledControlFrameWriteAbortsTheConnection() throws Exception {
        final FrameWriteGate output = new FrameWriteGate();
        final CountDownLatch aborted = new CountDownLatch(1);
        try (PeerChannel channel = new PeerChannel(new PeerStreamSocket(InputStream.nullInputStream(), output));
             ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
             ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            final byte[] state = readyState();
            final Future<Boolean> heartbeat = tasks.submit(() -> channel.writeBefore(() -> state,
                    PeerFrames.MAX_CONTROL_BODY_BYTES, System.nanoTime() + MILLISECONDS.toNanos(500),
                    timers, aborted::countDown));
            try {
                assertTrue(output.headerWritten.await(1, SECONDS));
                assertTrue(aborted.await(2, SECONDS));
            } finally {
                output.resume.countDown();
            }
            heartbeat.get(1, SECONDS);
        }
    }

    @Test
    void invocationBudgetAccountsForTimeWaitingForTheWriter() throws Exception {
        final FrameWriteGate output = new FrameWriteGate();
        final byte[] heartbeat = readyState();
        try (PeerChannel channel = new PeerChannel(new PeerStreamSocket(InputStream.nullInputStream(), output));
             ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
             ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
             PeerFlowCalls calls = new PeerFlowCalls(channel, () -> { throw new AssertionError("Peer disconnected."); },
                     "test-build", ignored -> Optional.empty(), timers, new Semaphore(0))) {
            calls.queueHeartbeat(() -> heartbeat);
            try {
                assertTrue(output.headerWritten.await(1, SECONDS));
                final long deadline = System.nanoTime() + SECONDS.toNanos(5);
                final CompletableFuture<Thread> caller = new CompletableFuture<>();
                final Future<Optional<RunResult>> call = tasks.submit(() -> {
                    caller.complete(Thread.currentThread());
                    return PeerFlowCalls.offload(targets(calls), PeerChannelTest::emptyContinuation, deadline, ignored -> {});
                });
                awaitTimedWait(caller.get(1, SECONDS));
                final long releasedAt = System.nanoTime();
                output.resume.countDown();
                assertTrue(output.flushed.tryAcquire(2, 1, SECONDS));
                final ByteArrayInputStream frames = new ByteArrayInputStream(output.bytes.toByteArray());
                assertTrue(PeerProtocol.validState(response(frames)));
                final long budget = FlowWire.admissionNanos(response(frames).values().get(FlowWire.ADMISSION_NANOS));
                assertTrue(budget > 0 && budget <= deadline - releasedAt);
                calls.close();
                assertTrue(call.get(1, SECONDS).isPresent());
            } finally {
                output.resume.countDown();
            }
        }
    }

    @Test
    void interruptionBeforeDispatchCancelsWithoutWritingAFrame() throws Exception {
        final FrameWriteGate output = new FrameWriteGate();
        final byte[] heartbeat = readyState();
        final AtomicInteger offloaded = new AtomicInteger();
        try (PeerChannel channel = new PeerChannel(new PeerStreamSocket(InputStream.nullInputStream(), output));
             ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
             ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
             PeerFlowCalls calls = new PeerFlowCalls(channel, () -> { throw new AssertionError("Peer disconnected."); },
                     "test-build", ignored -> Optional.empty(), timers, new Semaphore(0))) {
            calls.queueHeartbeat(() -> heartbeat);
            try {
                assertTrue(output.headerWritten.await(1, SECONDS));
                final CompletableFuture<Thread> caller = new CompletableFuture<>();
                final Future<RunResult> result = tasks.submit(() -> {
                    caller.complete(Thread.currentThread());
                    return PeerFlowCalls.offload(targets(calls), PeerChannelTest::emptyContinuation,
                            System.nanoTime() + SECONDS.toNanos(5), ignored -> offloaded.incrementAndGet()).orElseThrow();
                });
                final Thread source = caller.get(1, SECONDS);
                awaitTimedWait(source);
                source.interrupt();
                assertInstanceOf(RunResult.Cancelled.class, result.get(1, SECONDS));
                assertEquals(0, offloaded.get());
            } finally {
                output.resume.countDown();
            }
            assertTrue(output.flushed.tryAcquire(1, SECONDS));
            assertFalse(output.flushed.tryAcquire(100, MILLISECONDS));
            final ByteArrayInputStream frames = new ByteArrayInputStream(output.bytes.toByteArray());
            assertTrue(PeerProtocol.validState(response(frames)));
            assertEquals(0, frames.available());
        }
    }

    @Test
    void interruptionDuringDispatchLeavesTheFrameIntactAndReportsUncertainty() throws Exception {
        final FrameWriteGate output = new FrameWriteGate();
        final PeerStreamSocket socket = new PeerStreamSocket(InputStream.nullInputStream(), output);
        final AtomicBoolean aborted = new AtomicBoolean();
        final AtomicInteger offloaded = new AtomicInteger();
        try (PeerChannel channel = new PeerChannel(socket);
             ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
             ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
             PeerFlowCalls calls = new PeerFlowCalls(channel, () -> aborted.set(true), "test-build",
                     ignored -> Optional.empty(), timers, new Semaphore(0))) {
            final CompletableFuture<Thread> caller = new CompletableFuture<>();
            final Future<RunResult> result = tasks.submit(() -> {
                caller.complete(Thread.currentThread());
                return PeerFlowCalls.offload(targets(calls), PeerChannelTest::emptyContinuation,
                        System.nanoTime() + SECONDS.toNanos(5), acknowledged -> {
                            assertFalse(acknowledged);
                            offloaded.incrementAndGet();
                        }).orElseThrow();
            });
            try {
                assertTrue(output.headerWritten.await(1, SECONDS));
                caller.get(1, SECONDS).interrupt();
                final RunResult.Failed failure = assertInstanceOf(RunResult.Failed.class, result.get(1, SECONDS));
                assertEquals(MeshErrors.FLOW_UNCERTAIN, failure.failure().code());
                assertEquals(1, offloaded.get());
                assertFalse(socket.isClosed());
            } finally {
                output.resume.countDown();
            }
            assertTrue(output.flushed.tryAcquire(1, SECONDS));
            assertFalse(output.flushed.tryAcquire(100, MILLISECONDS));
            final ByteArrayInputStream frames = new ByteArrayInputStream(output.bytes.toByteArray());
            final Map<String, RailixValue> invocation = response(frames).values();
            assertEquals(RailixValue.string("invoke"), invocation.get("type"));
            assertTrue(PeerProtocol.uuid(invocation.get("id")));
            assertEquals(0, frames.available());
            assertFalse(aborted.get());
        }
    }

    private static List<PeerConnection> targets(final PeerFlowCalls calls) {
        final UUID replica = UUID.randomUUID();
        return List.of(new PeerConnection(replica, UUID.randomUUID(), calls, calls::close));
    }

    private static byte[] readyState() {
        return PeerProtocol.state(new PeerEndpoint("127.0.0.1", 7401), 1, "ready", RailixValue.nullValue());
    }

    private static FlowContinuation emptyContinuation() {
        return new FlowContinuation("http", 0, RailixValue.object(Map.of()));
    }

    private static void awaitTimedWait(final Thread thread) throws InterruptedException {
        final long waitUntil = System.nanoTime() + SECONDS.toNanos(1);
        while (thread.getState() != Thread.State.TIMED_WAITING && waitUntil - System.nanoTime() > 0) {
            Thread.sleep(1);
        }
        assertEquals(Thread.State.TIMED_WAITING, thread.getState());
    }

    @Test
    void failedConstructionLeavesTheSocketWithItsCaller() throws Exception {
        try (Socket socket = new Socket()) {
            assertThrows(IOException.class, () -> new PeerChannel(socket));
            assertThrows(IOException.class, () -> new PeerChannel(socket));
            assertFalse(socket.isClosed());
        }
    }

    @Test
    void overlappingWritesKeepWholeFramesAndAllowAnIndependentRead() throws Exception {
        final FrameWriteGate output = new FrameWriteGate();
        final ByteArrayInputStream input = new ByteArrayInputStream(wire("000000012a"));
        try (PeerChannel channel = new PeerChannel(new PeerStreamSocket(input, output));
             ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
             ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor()) {
            final Future<Boolean> first = tasks.submit(() -> channel.write(new byte[]{1, 2}, 3));
            try {
                assertTrue(output.headerWritten.await(2, SECONDS));
                final AtomicBoolean aborted = new AtomicBoolean();
                assertFalse(channel.writeBefore(() -> new byte[]{9}, 3,
                        System.nanoTime() + MILLISECONDS.toNanos(100),
                        timers, () -> aborted.set(true)));
                assertFalse(aborted.get());
                final CountDownLatch secondStarted = new CountDownLatch(1);
                final Future<Boolean> second = tasks.submit(() -> {
                    secondStarted.countDown();
                    return channel.write(new byte[]{3, 4, 5}, 3);
                });
                assertTrue(secondStarted.await(2, SECONDS));

                final Future<FrameRead> read = tasks.submit(() -> channel.read(1, byte[]::new));
                assertArrayEquals(new byte[]{42}, assertInstanceOf(FrameBody.class, read.get(2, SECONDS)).bytes());
                assertThrows(TimeoutException.class, () -> second.get(100, MILLISECONDS));

                output.resume.countDown();
                assertTrue(first.get(2, SECONDS));
                assertTrue(second.get(2, SECONDS));
                assertArrayEquals(wire("00000002010200000003030405"), output.bytes.toByteArray());
            } finally {
                output.resume.countDown();
            }
        }
    }

    @Test
    void refusalUnderInputPressureKeepsTheReaderAvailableWhileTheWriterIsBusy() throws Exception {
        final byte[] heartbeat = readyState();
        final FrameWriteGate output = new FrameWriteGate();
        final PeerStreamSocket socket = new PeerStreamSocket(invocationWire(heartbeat), output);
        try (PeerChannel channel = new PeerChannel(socket);
             ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
             ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
             PeerFlowCalls calls = new PeerFlowCalls(channel, () -> { throw new AssertionError("Peer disconnected."); },
                     "test-build", ignored -> { throw new AssertionError("Receiver admitted work."); },
                     timers, new Semaphore(0))) {
            calls.queueHeartbeat(() -> heartbeat);
            try {
                assertTrue(output.headerWritten.await(1, SECONDS));
                final Future<RailixValue.ObjectValue> next = tasks.submit(() -> {
                    assertTrue(calls.receive(assertInstanceOf(PeerMessage.class, calls.read())));
                    return assertInstanceOf(PeerMessage.class, calls.read()).value();
                });
                assertTrue(PeerProtocol.validState(next.get(1, SECONDS)));
            } finally {
                output.resume.countDown();
            }
            assertTrue(output.flushed.tryAcquire(2, 1, SECONDS));
            final ByteArrayInputStream frames = new ByteArrayInputStream(output.bytes.toByteArray());
            assertTrue(PeerProtocol.validState(response(frames)));
            final Map<String, RailixValue> refusal = response(frames).values();
            assertEquals("refused", PeerProtocol.text(refusal.get("type")));
            assertEquals("unavailable", PeerProtocol.text(refusal.get("reason")));
            assertFalse(socket.isClosed());
        }
    }

    @Test
    void admittedFlowKeepsRunningAfterTheReplyDeadline() throws Exception {
        final BlockingStep step = new BlockingStep();
        final byte[] invoke = invocationFrame(UUID.randomUUID(), MILLISECONDS.toNanos(500));
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PeerChannel channel = new PeerChannel(new PeerStreamSocket(frameStream(invoke), output));
             ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
             PeerFlowCalls calls = new PeerFlowCalls(channel, () -> { throw new AssertionError("Peer disconnected."); },
                     "test-build", ignored -> Optional.of(new AdmittedFlow(step::run, () -> {})),
                     timers, new Semaphore(1024))) {
            try {
                assertTrue(calls.receive(assertInstanceOf(PeerMessage.class, calls.read())));
                assertTrue(step.running.await(1, SECONDS));
                assertFalse(step.finished.await(1, SECONDS));
            } finally {
                step.resume.countDown();
            }
            assertTrue(calls.awaitReplies(System.nanoTime() + SECONDS.toNanos(1)));
            assertFalse(step.interrupted.get());
            assertEquals(0, output.size());
        }
    }

    @Test
    void admittedFlowKeepsRunningAfterTheIngressConnectionCloses() throws Exception {
        final BlockingStep step = new BlockingStep();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final Semaphore retainedBytes = new Semaphore(1024);
        try (PeerChannel channel = new PeerChannel(new PeerStreamSocket(invocationWire(), output));
             ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor()) {
            final PeerFlowCalls calls = new PeerFlowCalls(channel, () -> { throw new AssertionError("Peer disconnected."); },
                    "test-build", ignored -> Optional.of(new AdmittedFlow(step::run, () -> {})), timers, retainedBytes);
            try {
                try (calls) {
                    assertTrue(calls.receive(assertInstanceOf(PeerMessage.class, calls.read())));
                    assertTrue(step.running.await(1, SECONDS));
                }
                assertFalse(step.finished.await(200, MILLISECONDS));
            } finally {
                step.resume.countDown();
            }
            assertTrue(calls.awaitReplies(System.nanoTime() + SECONDS.toNanos(1)));
            assertFalse(step.interrupted.get());
            assertEquals(1024, retainedBytes.availablePermits());
            assertEquals(0, output.size());
        }
    }

    @Test
    void matchesOutOfOrderRepliesToTheCallersOnOneChannel() throws Exception {
        final RunResult firstResult = success("first");
        final RunResult secondResult = new RunResult.Completed(RailixValue.object(Map.of()),
                List.of(new RunResult.PathResult("branch", "next", success("second"))));
        final FrameWriteGate output = new FrameWriteGate();
        output.resume.countDown();
        try (PipedInputStream replies = new PipedInputStream();
             DataOutputStream peer = new DataOutputStream(new PipedOutputStream(replies));
             PeerChannel channel = new PeerChannel(new PeerStreamSocket(replies, output));
             ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
             ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
             PeerFlowCalls calls = new PeerFlowCalls(channel, () -> { throw new AssertionError("Peer disconnected."); },
                     "test-build", ignored -> Optional.empty(), timers, new Semaphore(0))) {
            final long deadline = System.nanoTime() + SECONDS.toNanos(5);
            final List<PeerConnection> targets = targets(calls);
            final Future<Optional<RunResult>> first = tasks.submit(() ->
                    PeerFlowCalls.offload(targets, PeerChannelTest::emptyContinuation, deadline, ignored -> {}));
            assertTrue(output.flushed.tryAcquire(1, SECONDS));
            final Future<Optional<RunResult>> second = tasks.submit(() ->
                    PeerFlowCalls.offload(targets, PeerChannelTest::emptyContinuation, deadline, ignored -> {}));
            assertTrue(output.flushed.tryAcquire(1, SECONDS));
            final ByteArrayInputStream requests = new ByteArrayInputStream(output.bytes.toByteArray());
            final String firstId = PeerProtocol.text(response(requests).values().get("id"));
            final String secondId = PeerProtocol.text(response(requests).values().get("id"));

            assertTrue(PeerFrames.write(peer, FlowWire.encodeOutcome(secondId, secondResult), PeerFrames.MAX_DATA_BODY_BYTES));
            assertTrue(calls.receive(assertInstanceOf(PeerMessage.class, calls.read())));
            assertEquals(Optional.of(secondResult), second.get(1, SECONDS));
            assertFalse(first.isDone());
            assertTrue(PeerFrames.write(peer, FlowWire.encodeOutcome(firstId, firstResult), PeerFrames.MAX_DATA_BODY_BYTES));
            assertTrue(calls.receive(assertInstanceOf(PeerMessage.class, calls.read())));
            assertEquals(Optional.of(firstResult), first.get(1, SECONDS));
        }
    }

    @Test
    void anOversizedChildResultBecomesOneTerminalFailure() throws IOException {
        final RunResult child = success("x".repeat(PeerFrames.MAX_DATA_BODY_BYTES));
        final RunResult completed = new RunResult.Completed(RailixValue.object(Map.of()),
                List.of(new RunResult.PathResult("branch", "next", child)));
        final byte[] encoded = FlowWire.encodeOutcome(UUID.randomUUID().toString(), completed);
        final RailixValue payload = response(frameStream(encoded)).values().get("payload");

        final RunResult.Failed outcome = assertInstanceOf(RunResult.Failed.class,
                FlowWire.decodeOutcome(payload).orElseThrow());
        assertEquals(MeshErrors.FLOW_RESULT_INVALID, outcome.failure().code());
    }

    private static RunResult success(final String value) {
        final RailixValue.ObjectValue result = RailixValue.object(Map.of("value", RailixValue.string(value)));
        return new RunResult.Succeeded(result);
    }

    @Test
    void aTimelyOutcomeSettlesTheCallBeforeTheWriterReturns() throws Exception {
        final CountDownLatch bodyWritten = new CountDownLatch(1);
        final CountDownLatch finishWrite = new CountDownLatch(1);
        final ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override
            public void flush() throws IOException {
                bodyWritten.countDown();
                try {
                    if (!finishWrite.await(5, SECONDS)) {
                        throw new IOException("Test did not release the writer.");
                    }
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
        };
        try (PipedInputStream input = new PipedInputStream();
             DataOutputStream peer = new DataOutputStream(new PipedOutputStream(input));
             PeerChannel channel = new PeerChannel(new PeerStreamSocket(input, output));
             ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
             ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
             PeerFlowCalls calls = new PeerFlowCalls(channel, () -> { throw new AssertionError("Peer disconnected."); },
                     "test-build", ignored -> Optional.empty(), timers, new Semaphore(0))) {
            final AtomicBoolean acknowledged = new AtomicBoolean();
            final Future<Optional<RunResult>> call = tasks.submit(() -> PeerFlowCalls.offload(
                    targets(calls), PeerChannelTest::emptyContinuation, System.nanoTime() + SECONDS.toNanos(5),
                    acknowledged::set));
            try {
                assertTrue(bodyWritten.await(1, SECONDS));
                final String invocationId = PeerProtocol.text(response(output).values().get("id"));
                final RunResult outcome = success("received");
                assertTrue(PeerFrames.write(peer, FlowWire.encodeOutcome(invocationId, outcome), PeerFrames.MAX_DATA_BODY_BYTES));
                assertTrue(calls.receive(assertInstanceOf(PeerMessage.class, calls.read())));
                assertEquals(Optional.of(outcome), call.get(1, SECONDS));
                assertTrue(acknowledged.get());
            } finally {
                finishWrite.countDown();
            }
        }
    }

    @Test
    void releasesAdmissionAndReturnsARefusalWhenTheApplicationDeclines() throws Exception {
        final FrameWriteGate output = new FrameWriteGate();
        output.resume.countDown();
        final CountDownLatch released = new CountDownLatch(1);
        try (PeerChannel channel = new PeerChannel(new PeerStreamSocket(invocationWire(), output));
             ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
             PeerFlowCalls calls = new PeerFlowCalls(channel, () -> { throw new AssertionError("Peer disconnected."); },
                     "test-build", ignored -> Optional.of(new AdmittedFlow(Optional::empty, released::countDown)),
                     timers, new Semaphore(1024))) {
            assertTrue(calls.receive(assertInstanceOf(PeerMessage.class, calls.read())));
            assertTrue(released.await(1, SECONDS));
            assertTrue(output.flushed.tryAcquire(1, SECONDS));
            final Map<String, RailixValue> reply = response(output.bytes).values();
            assertEquals("refused", PeerProtocol.text(reply.get("type")));
            assertEquals("unavailable", PeerProtocol.text(reply.get("reason")));
        }
    }

    @Test
    void theFlowSlotReturnsAtHandoffWhileDrainStillWaitsForTheReply() throws Exception {
        final FrameWriteGate output = new FrameWriteGate();
        final Semaphore execution = new Semaphore(1);
        final CountDownLatch released = new CountDownLatch(1);
        final RunResult result = success("finished");
        try (PeerChannel channel = new PeerChannel(new PeerStreamSocket(invocationWire(), output));
             ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
             PeerFlowCalls calls = new PeerFlowCalls(channel, () -> { throw new AssertionError("Peer disconnected."); },
                     "test-build", ignored -> {
                         assertTrue(execution.tryAcquire());
                         return Optional.of(new AdmittedFlow(() -> Optional.of(result), () -> {
                             execution.release();
                             released.countDown();
                         }));
                     }, timers, new Semaphore(1024))) {
            try {
                assertTrue(calls.receive(assertInstanceOf(PeerMessage.class, calls.read())));
                assertTrue(output.headerWritten.await(1, SECONDS));
                assertTrue(released.await(1, SECONDS));
                assertTrue(execution.tryAcquire());
                assertFalse(calls.awaitReplies(System.nanoTime() + MILLISECONDS.toNanos(100)));
            } finally {
                output.resume.countDown();
            }
            assertTrue(calls.awaitReplies(System.nanoTime() + SECONDS.toNanos(1)));
            assertEquals(result, FlowWire.decodeOutcome(response(output.bytes).values().get("payload")).orElseThrow());
        }
    }

    @Test
    void aStalledConnectionLeavesAnotherConnectionsReplyAvailable() throws Exception {
        final FrameWriteGate stalled = new FrameWriteGate();
        final FrameWriteGate healthy = new FrameWriteGate();
        healthy.resume.countDown();
        final RunResult result = success("healthy");
        try (PeerChannel first = new PeerChannel(new PeerStreamSocket(InputStream.nullInputStream(), stalled));
             PeerChannel second = new PeerChannel(new PeerStreamSocket(invocationWire(), healthy));
             ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
             ExecutorService sources = Executors.newVirtualThreadPerTaskExecutor();
             PeerFlowCalls toStalled = new PeerFlowCalls(first, stalled.resume::countDown,
                     "test-build", ignored -> Optional.empty(), timers, new Semaphore(1024));
             PeerFlowCalls toHealthy = new PeerFlowCalls(second, () -> { throw new AssertionError("Peer disconnected."); },
                     "test-build", ignored -> Optional.of(new AdmittedFlow(() -> Optional.of(result), () -> {})),
                     timers, new Semaphore(1024))) {
            final long deadline = System.nanoTime() + SECONDS.toNanos(5);
            final List<PeerConnection> targets = targets(toStalled);
            try {
                sources.submit(() -> PeerFlowCalls.offload(targets, PeerChannelTest::emptyContinuation, deadline, ignored -> {}));
                assertTrue(stalled.headerWritten.await(1, SECONDS));
                final CompletableFuture<Thread> queued = new CompletableFuture<>();
                sources.submit(() -> {
                    queued.complete(Thread.currentThread());
                    return PeerFlowCalls.offload(targets, PeerChannelTest::emptyContinuation, deadline, ignored -> {});
                });
                awaitTimedWait(queued.get(1, SECONDS));

                assertTrue(toHealthy.receive(assertInstanceOf(PeerMessage.class, toHealthy.read())));
                assertTrue(healthy.flushed.tryAcquire(1, SECONDS));
                assertEquals(1, stalled.resume.getCount());
                assertEquals(result, FlowWire.decodeOutcome(response(healthy.bytes).values().get("payload")).orElseThrow());
            } finally {
                stalled.resume.countDown();
            }
        }
    }

    @Test
    void refusesBeforeExecutionWhenTheConnectionHasNoReplyCapacity() throws Exception {
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        final UUID third = UUID.randomUUID();
        final long budget = SECONDS.toNanos(5);
        final InputStream requests = frameStream(invocationFrame(first, budget), invocationFrame(second, budget),
                invocationFrame(third, budget));
        final FrameWriteGate output = new FrameWriteGate();
        final AtomicInteger admissions = new AtomicInteger();
        try (PeerChannel channel = new PeerChannel(new PeerStreamSocket(requests, output));
             ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
             PeerFlowCalls calls = new PeerFlowCalls(channel, () -> { throw new AssertionError("Peer disconnected."); },
                     "test-build", ignored -> {
                         admissions.incrementAndGet();
                         return Optional.of(new AdmittedFlow(() -> Optional.of(success("accepted")), () -> {}));
                     }, timers, new Semaphore(4096))) {
            try {
                assertTrue(calls.receive(assertInstanceOf(PeerMessage.class, calls.read())));
                assertTrue(output.headerWritten.await(1, SECONDS));
                assertTrue(calls.receive(assertInstanceOf(PeerMessage.class, calls.read())));
                assertTrue(calls.receive(assertInstanceOf(PeerMessage.class, calls.read())));
                assertEquals(2, admissions.get());
            } finally {
                output.resume.countDown();
            }
            assertTrue(output.flushed.tryAcquire(3, 1, SECONDS));
            final ByteArrayInputStream frames = new ByteArrayInputStream(output.bytes.toByteArray());
            final Map<String, String> terminal = new HashMap<>();
            for (int index = 0; index < 3; index++) {
                final Map<String, RailixValue> reply = response(frames).values();
                terminal.put(PeerProtocol.text(reply.get("id")), PeerProtocol.text(reply.get("type")));
            }
            assertEquals(Map.of(first.toString(), "outcome", second.toString(), "outcome",
                    third.toString(), "refused"), terminal);
        }
    }

    private static ByteArrayInputStream invocationWire(final byte[]... additionalFrames) throws IOException {
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        final DataOutputStream frames = new DataOutputStream(wire);
        PeerFrames.write(frames, invocationFrame(UUID.randomUUID(), SECONDS.toNanos(5)), PeerFrames.MAX_DATA_BODY_BYTES);
        for (final byte[] frame : additionalFrames) {
            PeerFrames.write(frames, frame, PeerFrames.MAX_CONTROL_BODY_BYTES);
        }
        return new ByteArrayInputStream(wire.toByteArray());
    }

    private static byte[] invocationFrame(final UUID id, final long budget) {
        final byte[] frame = FlowWire.encodeInvocation(emptyContinuation(), id.toString(), "test-build");
        return FlowWire.stampInvocation(frame, budget);
    }

    private static ByteArrayInputStream frameStream(final byte[]... bodies) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final DataOutputStream frames = new DataOutputStream(output);
        for (final byte[] body : bodies) {
            PeerFrames.write(frames, body, PeerFrames.MAX_DATA_BODY_BYTES);
        }
        return new ByteArrayInputStream(output.toByteArray());
    }

    private static RailixValue.ObjectValue response(final ByteArrayOutputStream output) throws IOException {
        return response(new ByteArrayInputStream(output.toByteArray()));
    }

    private static RailixValue.ObjectValue response(final InputStream input) throws IOException {
        final FrameRead frame = PeerFrames.read(input, ByteBuffer.allocate(Integer.BYTES), PeerFrames.MAX_CONTROL_BODY_BYTES,
                byte[]::new);
        final byte[] bytes = assertInstanceOf(FrameBody.class, frame).bytes();
        final RailixJson.Result document = RailixJson.parseDocument(new String(bytes, StandardCharsets.UTF_8));
        return assertInstanceOf(RailixValue.ObjectValue.class, assertInstanceOf(RailixJson.Parsed.class, document).value());
    }

    @ParameterizedTest
    @CsvSource({
            "'', END_OF_STREAM, , 0",
            "0001, TRUNCATED_HEADER, , 0",
            "0000000201, TRUNCATED_BODY, 2, 0",
            "00000000aa, INVALID_LENGTH, , 1",
            "00010001aa, INVALID_LENGTH, , 1",
            "ffffffffaa, INVALID_LENGTH, , 1"
    })
    void closesOnTerminalFrameReads(final String bytes, final FrameReadStatus reason,
                                    final Integer requestedBytes, final int unreadBytes) throws Exception {
        final ByteArrayInputStream input = new ByteArrayInputStream(wire(bytes));
        final PeerStreamSocket socket = new PeerStreamSocket(input, OutputStream.nullOutputStream());
        final ArrayList<Integer> requests = new ArrayList<Integer>();
        try (PeerChannel channel = new PeerChannel(socket)) {
            assertEquals(reason, channel.read(PeerFrames.MAX_CONTROL_BODY_BYTES, size -> {
                requests.add(size);
                return new byte[size];
            }));
            assertEquals(requestedBytes == null ? List.of() : List.of(requestedBytes), requests);
            assertEquals(unreadBytes, input.available());
            assertTrue(socket.isClosed());
        }
    }

    @Test
    void closesWithoutReadingTheBodyWhenCapacityIsUnavailable() throws Exception {
        final ByteArrayInputStream input = new ByteArrayInputStream(wire("00000002aabb"));
        final PeerStreamSocket socket = new PeerStreamSocket(input, OutputStream.nullOutputStream());
        try (PeerChannel channel = new PeerChannel(socket)) {
            assertEquals(FrameReadStatus.BODY_UNAVAILABLE, channel.read(2, size -> {
                assertEquals(2, size);
                return PeerFrames.NO_BODY;
            }));
            assertEquals(2, input.available());
            assertTrue(socket.isClosed());
        }
    }

    @Test
    void closesSocketAfterAPartialWrite() throws Exception {
        final HeaderOnlyOutputStream output = new HeaderOnlyOutputStream();
        final PeerStreamSocket socket = new PeerStreamSocket(InputStream.nullInputStream(), output);
        try (PeerChannel channel = new PeerChannel(socket)) {
            assertSame(output.failure, assertThrows(IOException.class, () -> channel.write(new byte[]{42}, 1)));
            assertArrayEquals(wire("00000001"), output.bytes.toByteArray());
            assertTrue(socket.isClosed());
        }
    }

    private static byte[] wire(final String bytes) {
        return HexFormat.of().parseHex(bytes);
    }

    private static final class BlockingStep {
        private final CountDownLatch running = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private final AtomicBoolean interrupted = new AtomicBoolean();

        private Optional<RunResult> run() {
            running.countDown();
            try {
                assertTrue(resume.await(5, SECONDS));
                return Optional.of(success("completed"));
            } catch (final InterruptedException cancelled) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                return Optional.of(new RunResult.Cancelled());
            } finally {
                finished.countDown();
            }
        }
    }
}

final class PeerStreamSocket extends Socket {
    private final InputStream input;
    private final OutputStream output;

    PeerStreamSocket(final InputStream input, final OutputStream output) {
        this.input = input;
        this.output = output;
    }

    @Override
    public InputStream getInputStream() {
        return input;
    }

    @Override
    public OutputStream getOutputStream() {
        return output;
    }
}

final class FrameWriteGate extends OutputStream {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final CountDownLatch headerWritten = new CountDownLatch(1);
    final CountDownLatch resume = new CountDownLatch(1);
    final Semaphore flushed = new Semaphore(0);

    @Override
    public void flush() {
        flushed.release();
    }

    @Override
    public void write(final int value) throws IOException {
        write(new byte[]{(byte) value}, 0, 1);
    }

    @Override
    public void write(final byte[] source, final int offset, final int length) throws IOException {
        bytes.write(source, offset, length);
        if (bytes.size() == Integer.BYTES) {
            headerWritten.countDown();
            try {
                if (!resume.await(5, SECONDS)) {
                    throw new IOException("Test did not release the frame header.");
                }
            } catch (final InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IOException("Test interrupted the frame write.", interruption);
            }
        }
    }
}

final class HeaderOnlyOutputStream extends OutputStream {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream(Integer.BYTES);
    final IOException failure = new IOException("Test stopped the stream after the header.");

    @Override
    public void write(final int value) throws IOException {
        if (bytes.size() == Integer.BYTES) {
            throw failure;
        }
        bytes.write(value);
    }
}
