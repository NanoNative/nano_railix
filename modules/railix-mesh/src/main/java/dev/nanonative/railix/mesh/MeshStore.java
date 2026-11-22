package dev.nanonative.railix.mesh;

import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

record MeshStore(Path path, Path passwordFile) {
    static MeshStore read(final Path directory, final RailixValue value, final String field) throws IOException {
        if (!(value instanceof RailixValue.ObjectValue store)
                || !store.values().keySet().equals(Set.of("path", "passwordFile"))
                || !(store.values().get("path") instanceof RailixValue.StringValue path)
                || !(store.values().get("passwordFile") instanceof RailixValue.StringValue passwordFile)
                || path.value().isBlank() || passwordFile.value().isBlank()) {
            throw new IOException(MeshErrors.INVALID_STORE_PATHS.formatted(field));
        }
        return new MeshStore(directory.resolve(path.value()), directory.resolve(passwordFile.value()));
    }
}
