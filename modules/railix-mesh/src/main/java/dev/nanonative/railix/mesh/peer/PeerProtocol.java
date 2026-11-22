package dev.nanonative.railix.mesh.peer;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.mesh.MeshErrors;
import dev.nanonative.railix.mesh.PeerEndpoint;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class PeerProtocol {
    private static final Set<String> ACCEPTOR_HELLO = Set.of(
            "type", "logicalBuildId", "meshId", "replicaId", "incarnationId");
    private static final Set<String> DIALER_HELLO = Set.of(
            "type", "logicalBuildId", "meshId", "replicaId", "incarnationId", "connectionId");
    private static final Set<String> STATE_FIELDS = Set.of(
            "type", "status", "advertisedEndpoint", "availableFlowSlots", "peerHints");
    private static final Set<String> PEER_STATUSES = Set.of("starting", "ready", "draining");
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final BigDecimal MAX_FLOW_SLOTS = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final int LIMIT = PeerFrames.MAX_CONTROL_BODY_BYTES;

    private PeerProtocol() {
    }

    static RailixValue.ObjectValue hello(final String build, final UUID mesh, final UUID replica, final UUID incarnation,
                                         final Optional<UUID> connectionId) {
        final Map<String, RailixValue> fields = new LinkedHashMap<>(Map.of(
                "type", RailixValue.string("hello.v1"),
                "logicalBuildId", RailixValue.string(build),
                "meshId", RailixValue.string(mesh.toString()),
                "replicaId", RailixValue.string(replica.toString()),
                "incarnationId", RailixValue.string(incarnation.toString())));
        connectionId.ifPresent(id -> fields.put("connectionId", RailixValue.string(id.toString())));
        return RailixValue.object(fields);
    }

    static Optional<String> rejectHello(final RailixValue.ObjectValue hello, final String build, final UUID mesh,
                                        final UUID peer, final UUID local, final boolean dialer) {
        final var fields = hello.values();
        if (!fields.keySet().equals(dialer ? DIALER_HELLO : ACCEPTOR_HELLO)
                || !"hello.v1".equals(text(fields.get("type")))
                || !uuid(fields.get("incarnationId"))
                || dialer && !uuid(fields.get("connectionId"))) {
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

    static RailixValue.ObjectValue state(final PeerEndpoint advertised, final int flowCapacity) {
        return RailixValue.object(Map.of(
                "type", RailixValue.string("state"),
                "status", RailixValue.string("ready"),
                "advertisedEndpoint", advertised.value(),
                "availableFlowSlots", RailixValue.number(flowCapacity),
                "peerHints", RailixValue.array(List.of())
        ));
    }

    static boolean validState(final RailixValue.ObjectValue state) {
        final var fields = state.values();
        return fields.keySet().equals(STATE_FIELDS)
                && "state".equals(text(fields.get("type")))
                && PEER_STATUSES.contains(text(fields.get("status")))
                && PeerEndpoint.read(fields.get("advertisedEndpoint")).isPresent()
                && validFlowSlots(fields.get("availableFlowSlots"))
                && RailixValue.array(List.of()).equals(fields.get("peerHints"));
    }

    static Optional<RailixValue.ObjectValue> read(final PeerChannel channel) throws IOException, InterruptedException {
        final var frame = channel.read(LIMIT, size -> Optional.of(new byte[size]));
        if (!(frame instanceof FrameBody body)) {
            return Optional.empty();
        }
        final var document = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body.bytes())).toString();
        if (RailixJson.parseDocument(document) instanceof RailixJson.Parsed parsed
                && parsed.value() instanceof RailixValue.ObjectValue object) {
            return Optional.of(object);
        }
        return Optional.empty();
    }

    static void write(final PeerChannel channel, final RailixValue.ObjectValue frame)
            throws IOException, InterruptedException {
        if (!channel.write(RailixJson.write(frame).getBytes(StandardCharsets.UTF_8), LIMIT)) {
            throw new IOException(MeshErrors.CONTROL_FRAME_TOO_LARGE);
        }
    }

    private static String text(final RailixValue value) {
        return value instanceof RailixValue.StringValue string ? string.value() : "";
    }

    private static boolean uuid(final RailixValue value) {
        return UUID_TEXT.matcher(text(value)).matches();
    }

    private static boolean validFlowSlots(final RailixValue value) {
        return value instanceof RailixValue.NumberValue slots
                && slots.value().signum() >= 0
                && slots.value().compareTo(MAX_FLOW_SLOTS) <= 0
                && slots.value().stripTrailingZeros().scale() <= 0;
    }
}
