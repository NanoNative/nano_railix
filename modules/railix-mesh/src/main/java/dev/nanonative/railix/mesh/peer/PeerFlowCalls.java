package dev.nanonative.railix.mesh.peer;

import dev.nanonative.railix.core.project.FlowContinuation;
import dev.nanonative.railix.core.runtime.RunFailure;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.mesh.MeshErrors;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Correlates calls on one authenticated peer channel. The session reader owns incoming frames. */
public final class PeerFlowCalls implements AutoCloseable {
    private static final int MAX_OFFLOAD_ATTEMPTS = 3;
    private static final int MAX_INVOCATION_WRITES = 2;
    private static final int MAX_PENDING_REPLIES = 2;
    private static final int MAX_PENDING_REFUSALS = 12;
    private static final Set<String> REFUSALS = Set.of("build-mismatch", "expired", "unavailable");

    private final PeerChannel channel;
    private final Runnable abort;
    private final String build;
    private final Function<FlowContinuation, Optional<AdmittedFlow>> admit;
    private final Semaphore retainedBytes;
    private final PeerWriter writer;
    private final Semaphore invocationWrites = new Semaphore(MAX_INVOCATION_WRITES);
    private final Semaphore replies = new Semaphore(MAX_PENDING_REPLIES);
    private final Semaphore refusals = new Semaphore(MAX_PENDING_REFUSALS);
    private final Semaphore heartbeats = new Semaphore(1);
    private final Map<String, OutgoingCall> outgoing = new ConcurrentHashMap<>();
    private final Set<String> incoming = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    PeerFlowCalls(final PeerChannel channel, final Runnable abort, final String build,
                  final Function<FlowContinuation, Optional<AdmittedFlow>> admit,
                  final ScheduledExecutorService timers, final Semaphore retainedBytes) {
        this.channel = channel;
        this.abort = abort;
        this.build = build;
        this.admit = admit;
        this.retainedBytes = retainedBytes;
        writer = new PeerWriter(channel, timers, abort,
                MAX_INVOCATION_WRITES + MAX_PENDING_REPLIES + MAX_PENDING_REFUSALS + 1);
    }

    /**
     * Tries eligible peers in order within one ingress deadline.
     * @param targets ready peers selected by placement
     * @param continuation captures the input once a writer buffer is available
     * @param deadline absolute deadline on the local monotonic clock
     * @param recordOffload receives true for a peer outcome or false for uncertain completion after possible delivery
     * @return the terminal result, or empty if no attempt admitted the Flow
     */
    public static Optional<RunResult> offload(final List<PeerConnection> targets,
                                              final Supplier<FlowContinuation> continuation, final long deadline,
                                              final Consumer<Boolean> recordOffload) {
        final InvocationFrame invocation = new InvocationFrame(continuation);
        for (int index = 0; index < Math.min(MAX_OFFLOAD_ATTEMPTS, targets.size()); index++) {
            final Reply reply = targets.get(index).flows().call(invocation, deadline);
            if (reply.offloaded()) {
                recordOffload.accept(reply != Reply.UNCERTAIN);
            }
            if (reply.result().isPresent() || deadline - System.nanoTime() <= 0) {
                return reply.result();
            }
        }
        return Optional.empty();
    }

    private Reply call(final InvocationFrame invocation, final long deadline) {
        if (Thread.currentThread().isInterrupted()) {
            return Reply.CANCELLED;
        }
        final String id = invocation.id;
        final OutgoingCall call = new OutgoingCall(deadline);
        try {
            if (!queueInvocation(invocation, deadline, call)) {
                return Thread.currentThread().isInterrupted() ? Reply.CANCELLED : Reply.NOT_ADMITTED;
            }
            final Reply reply =
                    call.reply.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (reply == Reply.NOT_ADMITTED) {
                // Only a retry needs the previous writer to release the shared frame.
                call.written.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
            return reply;
        } catch (final ExecutionException failure) {
            return Reply.UNCERTAIN;
        } catch (final InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return abandonInvocation(call, Reply.CANCELLED);
        } catch (final TimeoutException expired) {
            return abandonInvocation(call, Reply.NOT_ADMITTED);
        } finally {
            outgoing.remove(id, call);
        }
    }

    private boolean queueInvocation(final InvocationFrame invocation, final long deadline, final OutgoingCall call) {
        if (closed || !invocationWrites.tryAcquire()) {
            return false;
        }
        boolean queued = false;
        try {
            if (deadline - System.nanoTime() <= 0) {
                return false;
            }
            final byte[] frame = invocation.encode(build);
            if (frame.length == 0 || Thread.currentThread().isInterrupted()) {
                return false;
            }
            outgoing.put(invocation.id, call);
            call.write = new PeerWrite(() -> call.prepare(frame),
                    PeerFrames.MAX_DATA_BODY_BYTES, writeDeadline(deadline),
                    status -> completeInvocationWrite(call, status));
            queued = writer.offer(call.write);
            return queued;
        } finally {
            if (!queued) {
                invocationWrites.release();
            }
        }
    }

    private void completeInvocationWrite(final OutgoingCall call, final PeerWrite.Status status) {
        invocationWrites.release();
        switch (status) {
            case SKIPPED -> call.reply.complete(Reply.NOT_ADMITTED);
            case FAILED -> call.abandon(Reply.NOT_ADMITTED);
            case WRITTEN -> { }
        }
        call.written.countDown();
    }

    private Reply abandonInvocation(final OutgoingCall call, final Reply unsent) {
        final Reply reply = call.abandon(unsent);
        if (reply == Reply.NOT_ADMITTED || reply == Reply.CANCELLED) {
            writer.remove(call.write);
        }
        return reply;
    }

    FrameRead read() throws IOException, InterruptedException {
        final FrameRead frame = channel.read(PeerFrames.MAX_DATA_BODY_BYTES, byte[]::new);
        if (!(frame instanceof FrameBody body)) {
            return frame;
        }
        if (PeerProtocol.parse(body) instanceof RailixJson.Parsed parsed
                && parsed.value() instanceof RailixValue.ObjectValue message) {
            return new PeerMessage(message, body.bytes().length, body.headerReceivedNanos());
        }
        return FrameReadStatus.INVALID_MESSAGE;
    }

    boolean receive(final PeerMessage message) {
        final Map<String, RailixValue> fields = message.value().values();
        final RailixValue invocationId = fields.get("id");
        if (!PeerProtocol.uuid(invocationId)) {
            return false;
        }
        final String id = PeerProtocol.text(invocationId);
        return switch (PeerProtocol.text(fields.get("type"))) {
            case "invoke" -> receiveInvoke(id, message);
            case "outcome" -> fields.size() == 3 && receiveOutcome(id, fields.get("payload"));
            case "refused" -> message.bodyBytes() <= PeerFrames.MAX_CONTROL_BODY_BYTES && fields.size() == 3
                    && REFUSALS.contains(PeerProtocol.text(fields.get("reason"))) && receiveRefusal(id);
            default -> false;
        };
    }

    private boolean receiveInvoke(final String id, final PeerMessage message) {
        final Map<String, RailixValue> fields = message.value().values();
        if (!fields.keySet().equals(FlowWire.INVOCATION_FIELDS)) {
            return false;
        }
        if (!build.equals(PeerProtocol.text(fields.get(FlowWire.LOGICAL_BUILD_ID)))) {
            return queueRefusal(id, "build-mismatch");
        }
        final long remaining = FlowWire.admissionNanos(fields.get(FlowWire.ADMISSION_NANOS));
        final long outcome = FlowWire.integer(fields.get(FlowWire.TRIGGER_OUTCOME), 0, Integer.MAX_VALUE);
        final String trigger = PeerProtocol.text(fields.get(FlowWire.TRIGGER));
        if (remaining < 0 || outcome < 0 || trigger.isBlank()
                || !(fields.get(FlowWire.INPUT) instanceof RailixValue.ObjectValue input)
                || input.values().containsKey("runtime") || !FlowWire.fitsInvocationLimits(input)) {
            return false;
        }
        final long deadline = message.headerReceivedNanos() + remaining;
        if (incoming.contains(id)) {
            return false;
        }
        final FlowContinuation continuation = new FlowContinuation(trigger, (int) outcome, input);
        return admitIncoming(id, continuation, deadline, message.bodyBytes());
    }

    private boolean admitIncoming(final String id, final FlowContinuation continuation,
                                  final long deadline, final int reserved) {
        if (!replies.tryAcquire()) {
            return queueRefusal(id, "unavailable");
        }
        if (!retainedBytes.tryAcquire(reserved)) {
            replies.release();
            return queueRefusal(id, "unavailable");
        }
        boolean started = false;
        try {
            if (deadline - System.nanoTime() <= 0) {
                return queueRefusal(id, "expired");
            }
            final Optional<AdmittedFlow> flow = admit.apply(continuation);
            if (flow.isEmpty()) {
                return queueRefusal(id, "unavailable");
            }
            startIncoming(id, flow.orElseThrow(), deadline, reserved);
            started = true;
            return true;
        } finally {
            if (!started) {
                retainedBytes.release(reserved);
                replies.release();
            }
        }
    }

    private void startIncoming(final String id, final AdmittedFlow flow, final long deadline, final int reserved) {
        boolean started = false;
        try {
            incoming.add(id);
            Thread.ofVirtual().name("railix-mesh-flow").start(() -> execute(id, flow, deadline, reserved));
            started = true;
        } finally {
            if (!started) {
                incoming.remove(id);
                flow.close();
            }
        }
    }

    private void execute(final String id, final AdmittedFlow flow, final long deadline, final int reserved) {
        boolean queued = false;
        try (flow) {
            // The admission deadline limits replies. The application owns execution once admitted.
            final Optional<RunResult> result = flow.execute().get();
            if (closed || deadline - System.nanoTime() <= 0) {
                return;
            }
            if (Thread.currentThread().isInterrupted()
                    && (result.isEmpty() || !(result.orElseThrow() instanceof RunResult.Cancelled))) {
                return;
            }
            queued = queueReply(id, result, deadline);
        } catch (final RuntimeException disconnected) {
            abort.run();
        } finally {
            retainedBytes.release(reserved);
            if (!queued) {
                releaseReply(id);
            }
        }
    }

    private boolean queueReply(final String id, final Optional<RunResult> result, final long deadline) {
        final byte[] frame = result.isPresent()
                ? FlowWire.encodeOutcome(id, result.orElseThrow()) : PeerProtocol.refusal(id, "unavailable");
        return writer.offer(new PeerWrite(() -> frame, PeerFrames.MAX_DATA_BODY_BYTES,
                writeDeadline(deadline), ignored -> releaseReply(id)));
    }

    private void releaseReply(final String id) {
        incoming.remove(id);
        replies.release();
    }

    /**
     * Waits for reserved replies to finish after application admission has stopped.
     * @param deadline absolute drain deadline on the local monotonic clock
     * @return whether all reply reservations were returned within the drain budget
     * @throws InterruptedException if the drain is interrupted
     */
    public boolean awaitReplies(final long deadline) throws InterruptedException {
        if (!replies.tryAcquire(MAX_PENDING_REPLIES,
                Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
            return false;
        }
        replies.release(MAX_PENDING_REPLIES);
        return true;
    }

    private boolean receiveOutcome(final String id, final RailixValue payload) {
        final OutgoingCall pending = outgoing.get(id);
        if (pending == null) {
            return true;
        }
        final Optional<RunResult> result = FlowWire.decodeOutcome(payload);
        if (result.isEmpty()) {
            return false;
        }
        pending.receive(new Reply(result, true));
        return true;
    }

    private boolean receiveRefusal(final String id) {
        final OutgoingCall pending = outgoing.get(id);
        if (pending != null) {
            pending.receive(Reply.NOT_ADMITTED);
        }
        return true;
    }

    private boolean queueRefusal(final String id, final String reason) {
        queueControl(refusals, () -> PeerProtocol.refusal(id, reason));
        return true;
    }

    private static long writeDeadline(final long deadline) {
        final long now = System.nanoTime();
        return now + Math.min(deadline - now, PeerChannel.WRITE_NANOS);
    }

    void queueHeartbeat(final Supplier<byte[]> state) {
        queueControl(heartbeats, state);
    }

    private void queueControl(final Semaphore slots, final Supplier<byte[]> frame) {
        if (closed || !slots.tryAcquire()) {
            return;
        }
        final long deadline = System.nanoTime() + PeerChannel.WRITE_NANOS;
        final PeerWrite write = new PeerWrite(frame, PeerFrames.MAX_CONTROL_BODY_BYTES,
                deadline, ignored -> slots.release());
        if (!writer.offer(write)) {
            slots.release();
        }
    }

    @Override
    public void close() {
        closed = true;
        writer.close();
        outgoing.values().forEach(call -> call.abandon(Reply.NOT_ADMITTED));
    }

    private record Reply(Optional<RunResult> result, boolean offloaded) {
        private static final Reply NOT_ADMITTED = new Reply(Optional.empty(), false);
        private static final Reply CANCELLED = new Reply(Optional.of(new RunResult.Cancelled()), false);
        private static final Reply UNCERTAIN = new Reply(Optional.of(new RunResult.Failed(new RunFailure(
                MeshErrors.FLOW_UNCERTAIN, "The peer may have admitted the Flow. Its outcome is unavailable.", ""))), true);
    }

    /** The ingress prepares at most one body. A completed write releases it for the next attempt. */
    private static final class InvocationFrame {
        private final String id = UUID.randomUUID().toString();
        private Supplier<FlowContinuation> continuation;
        private byte[] frame = PeerFrames.NO_BODY;

        private InvocationFrame(final Supplier<FlowContinuation> continuation) {
            this.continuation = continuation;
        }

        private byte[] encode(final String build) {
            if (continuation != null) {
                frame = FlowWire.encodeInvocation(continuation.get(), id, build);
                continuation = null;
            }
            return frame;
        }
    }

    /** Orders receipt of a reply and the caller's departure with the writer claiming the frame. */
    private static final class OutgoingCall {
        private final long deadline;
        private final CompletableFuture<Reply> reply = new CompletableFuture<>();
        private final CountDownLatch written = new CountDownLatch(1);
        private PeerWrite write;
        private boolean abandoned;
        private boolean writeStarted;

        private OutgoingCall(final long deadline) {
            this.deadline = deadline;
        }

        private synchronized byte[] prepare(final byte[] frame) {
            if (abandoned) {
                return PeerFrames.NO_BODY;
            }
            writeStarted = true;
            return FlowWire.stampInvocation(frame, deadline - System.nanoTime());
        }

        private synchronized void receive(final Reply received) {
            if (deadline - System.nanoTime() > 0) {
                reply.complete(received);
            }
        }

        private synchronized Reply abandon(final Reply unsent) {
            abandoned = true;
            reply.complete(writeStarted ? Reply.UNCERTAIN : unsent);
            return reply.join();
        }
    }
}
