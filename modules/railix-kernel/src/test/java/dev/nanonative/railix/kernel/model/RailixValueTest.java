package dev.nanonative.railix.kernel.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RailixValueTest {

    @Test
    void shouldRejectNullScalarValues() {
        assertThatThrownBy(() -> new RailixValue.NumberValue(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RailixValue.StringValue(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldCopyListAndObjectValues() {
        final List<RailixValue> values = new ArrayList<>(List.of(
                new RailixValue.NumberValue(BigDecimal.ONE)
        ));
        final Map<String, RailixValue> object = new HashMap<>(Map.of(
                "email", new RailixValue.StringValue("user@example.com")
        ));

        final RailixValue.ListValue listValue = new RailixValue.ListValue(values);
        final RailixValue.ObjectValue objectValue = new RailixValue.ObjectValue(object);

        values.clear();
        object.clear();

        assertThat(listValue.values()).hasSize(1);
        assertThat(objectValue.values()).containsKey("email");
        assertThatThrownBy(() -> listValue.values().add(RailixValue.NULL)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldCopyReferenceMetadata() {
        final Map<String, RailixValue> metadata = new HashMap<>(Map.of(
                "kind", new RailixValue.StringValue("json")
        ));

        final RailixValue.StreamRef streamRef = new RailixValue.StreamRef("stream-1", "event", metadata);

        metadata.clear();

        assertThat(streamRef.metadata()).containsKey("kind");
        assertThatThrownBy(() -> streamRef.metadata().put("x", RailixValue.NULL)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldCopySessionAndDeferredReferenceMetadata() {
        final Map<String, RailixValue> sessionMetadata = new HashMap<>(Map.of(
                "tenant", new RailixValue.StringValue("acme")
        ));
        final Map<String, RailixValue> deferredMetadata = new HashMap<>(Map.of(
                "jobType", new RailixValue.StringValue("export")
        ));

        final RailixValue.SessionRef sessionRef = new RailixValue.SessionRef("session-1", "http", sessionMetadata);
        final RailixValue.DeferredRef deferredRef = new RailixValue.DeferredRef("deferred-1", "ctx.jobs[0].status", deferredMetadata);

        sessionMetadata.clear();
        deferredMetadata.clear();

        assertThat(sessionRef.metadata()).containsKey("tenant");
        assertThat(deferredRef.metadata()).containsKey("jobType");
    }

    @Test
    void shouldRepresentBlobFileAndSecretReferences() {
        final RailixValue.BlobRef blobRef = new RailixValue.BlobRef("blob-1", "application/json", "sha256:abc", 128L);
        final RailixValue.FileRef fileRef = new RailixValue.FileRef("file-1", "/tmp/report.json", "application/json", "sha256:def", 256L);
        final RailixValue.SecretRef secretRef = new RailixValue.SecretRef(RailixPath.parse("settings.database.password"));

        assertThat(blobRef.id()).isEqualTo("blob-1");
        assertThat(fileRef.path()).isEqualTo("/tmp/report.json");
        assertThat(secretRef.path()).isEqualTo(RailixPath.parse("settings.database.password"));
    }
}
