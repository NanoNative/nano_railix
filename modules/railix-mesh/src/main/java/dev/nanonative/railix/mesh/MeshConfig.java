package dev.nanonative.railix.mesh;

import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

record MeshConfig(UUID meshId, PeerEndpoint listen, PeerEndpoint advertised, List<PeerEndpoint> bootstrap,
                  int flowCapacity, MeshStore identity, MeshStore trust) {
    private static final int MAX_BYTES = 64 * 1024;
    private static final Set<String> FIELDS = Set.of("meshId", "listenEndpoint", "advertisedEndpoint",
            "bootstrapEndpoints", "flowCapacity", "identityStore", "trustStore");
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");

    static MeshConfig load(final Path path) throws IOException {
        if (!Files.readAttributes(path, BasicFileAttributes.class).isRegularFile()) {
            throw new IOException(MeshErrors.REGULAR_FILE_REQUIRED + path);
        }
        final byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) {
            throw new IOException(MeshErrors.CONFIG_TOO_LARGE);
        }
        final RailixJson.Result parsed = RailixJson.parse(
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
        if (!(parsed instanceof RailixJson.Parsed document)
                || !(document.value() instanceof RailixValue.ObjectValue config)
                || !config.values().keySet().equals(FIELDS)) {
            throw new IOException(MeshErrors.INVALID_CONFIG);
        }
        final Map<String, RailixValue> values = config.values();
        if (!(values.get("meshId") instanceof RailixValue.StringValue id) || !uuid(id.value())) {
            throw new IOException(MeshErrors.INVALID_MESH_ID);
        }
        final PeerEndpoint listen = PeerEndpoint.read(values.get("listenEndpoint"))
                .orElseThrow(() -> new IOException(MeshErrors.INVALID_LISTEN_ENDPOINT));
        final PeerEndpoint advertised = PeerEndpoint.read(values.get("advertisedEndpoint"))
                .orElseThrow(() -> new IOException(MeshErrors.INVALID_ADVERTISED_ENDPOINT));
        if (!(values.get("bootstrapEndpoints") instanceof RailixValue.ArrayValue seeds)) {
            throw new IOException(MeshErrors.INVALID_BOOTSTRAP_ENDPOINTS);
        }
        final List<Optional<PeerEndpoint>> parsedSeeds = seeds.values().stream()
                .map(PeerEndpoint::read)
                .toList();
        if (parsedSeeds.stream().anyMatch(Optional::isEmpty)) {
            throw new IOException(MeshErrors.INVALID_BOOTSTRAP_ENDPOINT);
        }
        final List<PeerEndpoint> bootstrap = parsedSeeds.stream()
                .flatMap(Optional::stream)
                .distinct()
                .toList();
        final OptionalInt capacity = PeerEndpoint.integer(values.get("flowCapacity"), 1, Integer.MAX_VALUE);
        if (capacity.isEmpty()) {
            throw new IOException(MeshErrors.INVALID_FLOW_CAPACITY);
        }
        final Path directory = path.toAbsolutePath().getParent();
        final MeshStore identity = MeshStore.read(directory, values.get("identityStore"), "identityStore");
        final MeshStore trust = MeshStore.read(directory, values.get("trustStore"), "trustStore");
        return new MeshConfig(UUID.fromString(id.value()), listen, advertised, bootstrap, capacity.getAsInt(), identity, trust);
    }

    private static boolean uuid(final String text) {
        return UUID_TEXT.matcher(text).matches();
    }
}
