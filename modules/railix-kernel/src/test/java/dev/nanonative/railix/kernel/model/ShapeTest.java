package dev.nanonative.railix.kernel.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShapeTest {

    @Test
    void shouldBuildOpenObjectShapeWithConfidence() {
        final Shape shape = new Shape.ObjectShape(
                Map.of(
                        "email", new Shape.Field(
                                new Shape.ScalarShape(Shape.Kind.STRING),
                                Shape.Presence.REQUIRED,
                                Shape.Confidence.exact(12)
                        )
                ),
                true
        );

        assertThat(shape).isInstanceOf(Shape.ObjectShape.class);
        assertThat(((Shape.ObjectShape) shape).open()).isTrue();
    }

    @Test
    void shouldRejectEmptyUnionShape() {
        assertThatThrownBy(() -> new Shape.UnionShape(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one variant");
    }

    @Test
    void shouldBuildReferenceShapeForFileMetadata() {
        final Shape.ObjectShape metadataShape = new Shape.ObjectShape(
                Map.of("mediaType", new Shape.Field(
                        new Shape.ScalarShape(Shape.Kind.STRING),
                        Shape.Presence.REQUIRED,
                        Shape.Confidence.exact(2)
                )),
                false
        );

        final Shape.RefShape refShape = new Shape.RefShape(Shape.Kind.FILE_REF, metadataShape);

        assertThat(refShape.kind()).isEqualTo(Shape.Kind.FILE_REF);
        assertThat(refShape.metadataShape()).isEqualTo(metadataShape);
    }

    @Test
    void shouldRejectNonReferenceKindForReferenceShape() {
        assertThatThrownBy(() -> new Shape.RefShape(
                Shape.Kind.STRING,
                new Shape.ObjectShape(Map.of(), false)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RefShape requires a ref kind");
    }

    @Test
    void shouldRejectInvalidConfidenceCounts() {
        assertThatThrownBy(() -> new Shape.Confidence(3, 2, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observedCount must be <= sampleCount");
    }

    @Test
    void shouldRejectCollectionKindsForScalarShape() {
        assertThatThrownBy(() -> new Shape.ScalarShape(Shape.Kind.LIST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scalar kind");
        assertThatThrownBy(() -> new Shape.ScalarShape(Shape.Kind.OBJECT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scalar kind");
    }

    @Test
    void shouldRejectNullItemShapeForListShape() {
        assertThatThrownBy(() -> new Shape.ListShape(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("itemShape");
    }

    @Test
    void shouldRejectNegativeConfidenceCounts() {
        assertThatThrownBy(() -> new Shape.Confidence(-1, 2, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observedCount must be >= 0");
        assertThatThrownBy(() -> new Shape.Confidence(0, -1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sampleCount must be >= 0");
    }

    @Test
    void shouldAllowAllReferenceKinds() {
        final Shape.ObjectShape metadataShape = new Shape.ObjectShape(Map.of(), false);

        assertThat(new Shape.RefShape(Shape.Kind.BLOB_REF, metadataShape).kind()).isEqualTo(Shape.Kind.BLOB_REF);
        assertThat(new Shape.RefShape(Shape.Kind.FILE_REF, metadataShape).kind()).isEqualTo(Shape.Kind.FILE_REF);
        assertThat(new Shape.RefShape(Shape.Kind.STREAM_REF, metadataShape).kind()).isEqualTo(Shape.Kind.STREAM_REF);
        assertThat(new Shape.RefShape(Shape.Kind.SESSION_REF, metadataShape).kind()).isEqualTo(Shape.Kind.SESSION_REF);
        assertThat(new Shape.RefShape(Shape.Kind.DEFERRED_REF, metadataShape).kind()).isEqualTo(Shape.Kind.DEFERRED_REF);
        assertThat(new Shape.RefShape(Shape.Kind.SECRET_REF, metadataShape).kind()).isEqualTo(Shape.Kind.SECRET_REF);
    }

    @Test
    void shouldCopyObjectAndUnionVariants() {
        final Map<String, Shape.Field> fields = new java.util.HashMap<>(Map.of(
                "email", new Shape.Field(
                        new Shape.ScalarShape(Shape.Kind.STRING),
                        Shape.Presence.REQUIRED,
                        Shape.Confidence.exact(1)
                )
        ));
        final List<Shape> variants = new java.util.ArrayList<>(List.of(
                new Shape.ScalarShape(Shape.Kind.STRING)
        ));

        final Shape.ObjectShape objectShape = new Shape.ObjectShape(fields, false);
        final Shape.UnionShape unionShape = new Shape.UnionShape(variants);

        fields.clear();
        variants.clear();

        assertThat(objectShape.fields()).containsKey("email");
        assertThatThrownBy(() -> objectShape.fields().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(unionShape.variants()).hasSize(1);
        assertThatThrownBy(() -> unionShape.variants().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
