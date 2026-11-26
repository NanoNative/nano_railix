package dev.nanonative.railix.mesh.peer;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.mesh.MeshErrors;
import dev.nanonative.railix.mesh.PeerEndpoint;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class PeerProtocol {
    static final int MAX_NODES = 65_536;
    private static final Set<String> HELLO_FIELDS = Set.of(
            "type", "logicalBuildId", "meshId", "replicaId", "incarnationId");
    private static final Set<String> STATE_FIELDS = Set.of(
            "type", "status", "advertisedEndpoint", "availableFlowSlots", "peerHints", "offloadFlow");
    private static final Set<String> PEER_STATUSES = Set.of("starting", "ready", "draining");
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final Pattern FLOW_ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");
    private static final BigDecimal MAX_FLOW_SLOTS = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final int LIMIT = PeerFrames.MAX_CONTROL_BODY_BYTES;

    private PeerProtocol() {
    }

    static byte[] hello(final String build, final UUID mesh, final UUID replica, final UUID incarnation) {
        return encode(RailixValue.object(Map.of(
                "type", RailixValue.string("hello.v1"),
                "logicalBuildId", RailixValue.string(build),
                "meshId", RailixValue.string(mesh.toString()),
                "replicaId", RailixValue.string(replica.toString()),
                "incarnationId", RailixValue.string(incarnation.toString()))));
    }

    static Optional<String> rejectHello(final RailixValue.ObjectValue hello, final String build, final UUID mesh,
                                        final UUID peer, final UUID local) {
        final Map<String, RailixValue> fields = hello.values();
        if (!fields.keySet().equals(HELLO_FIELDS)
                || !"hello.v1".equals(text(fields.get("type")))
                || !uuid(fields.get("incarnationId"))) {
            return Optional.of(MeshErrors.INVALID_HELLO);
        }
        if (peer.equals(local) || !peer.toString().equals(text(fields.get("replicaId")))
                || !mesh.toString().equals(text(fields.get("meshId")))) {
            return Optional.of(MeshErrors.IDENTITY_MISMATCH);
        }
        if (!build.equals(text(fields.get("logicalBuildId")))) {
            return Optional.of(MeshErrors.BUILD_MISMATCH);
        }
        return Optional.empty();
    }

    static byte[] state(final PeerEndpoint advertised, final int flowCapacity, final String status,
                        final RailixValue offloadFlow) {
        return encode(RailixValue.object(Map.of(
                "type", RailixValue.string("state"),
                "status", RailixValue.string(status),
                "advertisedEndpoint", advertised.value(),
                "availableFlowSlots", RailixValue.number(flowCapacity), "offloadFlow", offloadFlow,
                "peerHints", RailixValue.array(List.of())
        )));
    }

    static byte[] refusal(final String id, final String reason) {
        return encode(RailixValue.object(Map.of("type", RailixValue.string("refused"),
                "id", RailixValue.string(id), "reason", RailixValue.string(reason))));
    }

    static boolean validState(final RailixValue.ObjectValue state) {
        final Map<String, RailixValue> fields = state.values();
        return fields.keySet().equals(STATE_FIELDS)
                && "state".equals(text(fields.get("type")))
                && PEER_STATUSES.contains(text(fields.get("status")))
                && PeerEndpoint.isValid(fields.get("advertisedEndpoint"))
                && validFlowSlots(fields.get("availableFlowSlots"))
                && (fields.get("offloadFlow") instanceof RailixValue.NullValue
                        || FLOW_ID.matcher(text(fields.get("offloadFlow"))).matches())
                && fields.get("peerHints") instanceof RailixValue.ArrayValue hints
                && hints.values().isEmpty();
    }

    static Optional<RailixValue.ObjectValue> read(final PeerChannel channel) throws IOException, InterruptedException {
        final FrameRead frame = channel.read(LIMIT, byte[]::new);
        return frame instanceof FrameBody body ? decode(body) : Optional.empty();
    }

    static Optional<RailixValue.ObjectValue> decode(final FrameBody body) throws IOException {
        if (parse(body) instanceof RailixJson.Parsed parsed
                && parsed.value() instanceof RailixValue.ObjectValue object) {
            return Optional.of(object);
        }
        return Optional.empty();
    }

    static RailixJson.Result parse(final FrameBody body) throws IOException {
        final String document = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body.bytes())).toString();
        return RailixJson.parseDocument(document, MAX_NODES);
    }

    private static byte[] encode(final RailixValue.ObjectValue frame) {
        return RailixJson.write(frame).getBytes(StandardCharsets.UTF_8);
    }

    static void write(final PeerChannel channel, final byte[] body) throws IOException, InterruptedException {
        if (!channel.write(body, LIMIT)) {
            throw new IOException(MeshErrors.CONTROL_FRAME_TOO_LARGE);
        }
    }

    static String text(final RailixValue value) {
        return value instanceof RailixValue.StringValue string ? string.value() : "";
    }

    static boolean uuid(final RailixValue value) {
        return UUID_TEXT.matcher(text(value)).matches();
    }

    private static boolean validFlowSlots(final RailixValue value) {
        return value instanceof RailixValue.NumberValue slots
                && slots.value().signum() >= 0
                && slots.value().compareTo(MAX_FLOW_SLOTS) <= 0
                && slots.value().stripTrailingZeros().scale() <= 0;
    }
}
