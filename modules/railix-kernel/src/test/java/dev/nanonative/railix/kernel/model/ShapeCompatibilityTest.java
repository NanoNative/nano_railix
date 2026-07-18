package dev.nanonative.railix.kernel.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShapeCompatibilityTest {

    @Test
    void shouldAssignAnyToAny() {
        assertThat(ShapeCompatibility.isAssignable(Shape.any(), Shape.any())).isTrue();
    }

    @Test
    void shouldTreatAnyAsAConsumerButNotAsASpecificProducer() {
        assertThat(ShapeCompatibility.isAssignable(Shape.string(), Shape.any())).isTrue();
        assertThat(ShapeCompatibility.isAssignable(Shape.any(), Shape.string())).isFalse();
    }

    @Test
    void shouldAssignOnlyMatchingScalarShapes() {
        assertThat(ShapeCompatibility.isAssignable(Shape.number(), Shape.number())).isTrue();
        assertThat(ShapeCompatibility.isAssignable(Shape.number(), Shape.string())).isFalse();
    }

    @Test
    void shouldAssignListsByTheirItemShape() {
        assertThat(ShapeCompatibility.isAssignable(Shape.list(Shape.string()), Shape.list(Shape.string()))).isTrue();
        assertThat(ShapeCompatibility.isAssignable(Shape.list(Shape.number()), Shape.list(Shape.string()))).isFalse();
    }

    @Test
    void shouldRequireEveryProducerUnionVariantToFitConsumer() {
        assertThat(ShapeCompatibility.isAssignable(
                Shape.union(Shape.string(), Shape.number()),
                Shape.union(Shape.number(), Shape.string(), Shape.bool())
        )).isTrue();
        assertThat(ShapeCompatibility.isAssignable(
                Shape.union(Shape.string(), Shape.number()),
                Shape.string()
        )).isFalse();
    }

    @Test
    void shouldAcceptProducerWhenOneConsumerUnionVariantFits() {
        assertThat(ShapeCompatibility.isAssignable(
                Shape.string(),
                Shape.union(Shape.number(), Shape.string())
        )).isTrue();
    }

    @Test
    void shouldRejectMissingRequiredObjectFieldAtCompileTime() {
        final Shape producer = Shape.object(Map.of(), false);
        final Shape consumer = Shape.object(Map.of("name", Shape.Field.required(Shape.string())), false);

        assertThat(ShapeCompatibility.isAssignable(producer, consumer)).isFalse();
    }

    @Test
    void shouldAllowMissingOptionalObjectFieldAtCompileTime() {
        final Shape producer = Shape.object(Map.of(), false);
        final Shape consumer = Shape.object(Map.of("name", Shape.Field.optional(Shape.string())), false);

        assertThat(ShapeCompatibility.isAssignable(producer, consumer)).isTrue();
    }

    @Test
    void shouldRejectOptionalProducerFieldForRequiredConsumerField() {
        final Shape producer = Shape.object(Map.of("name", Shape.Field.optional(Shape.string())), false);
        final Shape consumer = Shape.object(Map.of("name", Shape.Field.required(Shape.string())), false);

        assertThat(ShapeCompatibility.isAssignable(producer, consumer)).isFalse();
    }

    @Test
    void shouldRejectExtraProducerFieldForClosedConsumer() {
        final Shape producer = Shape.object(Map.of(
                "name", Shape.Field.required(Shape.string()),
                "extra", Shape.Field.required(Shape.string())
        ), false);
        final Shape consumer = Shape.object(Map.of("name", Shape.Field.required(Shape.string())), false);

        assertThat(ShapeCompatibility.isAssignable(producer, consumer)).isFalse();
    }

    @Test
    void shouldAllowExtraProducerFieldForOpenConsumer() {
        final Shape producer = Shape.object(Map.of(
                "name", Shape.Field.required(Shape.string()),
                "extra", Shape.Field.required(Shape.string())
        ), false);
        final Shape consumer = Shape.object(Map.of("name", Shape.Field.required(Shape.string())), true);

        assertThat(ShapeCompatibility.isAssignable(producer, consumer)).isTrue();
    }

    @Test
    void shouldRejectOpenProducerForClosedConsumer() {
        assertThat(ShapeCompatibility.isAssignable(Shape.openObject(), Shape.object(Map.of(), false))).isFalse();
    }

    @Test
    void shouldAssignMatchingReferenceShapes() {
        final Shape metadata = Shape.object(Map.of("mediaType", Shape.Field.required(Shape.string())), true);

        assertThat(ShapeCompatibility.isAssignable(
                new Shape.RefShape(Shape.Kind.FILE_REF, (Shape.ObjectShape) metadata),
                new Shape.RefShape(Shape.Kind.FILE_REF, (Shape.ObjectShape) metadata)
        )).isTrue();
        assertThat(ShapeCompatibility.isAssignable(
                new Shape.RefShape(Shape.Kind.FILE_REF, (Shape.ObjectShape) metadata),
                new Shape.RefShape(Shape.Kind.BLOB_REF, (Shape.ObjectShape) metadata)
        )).isFalse();
    }

    @Test
    void shouldRejectReferenceWhenMetadataShapeIsIncompatible() {
        final Shape producerMetadata = Shape.object(
                Map.of("mediaType", Shape.Field.required(Shape.number())),
                false
        );
        final Shape consumerMetadata = Shape.object(
                Map.of("mediaType", Shape.Field.required(Shape.string())),
                false
        );

        assertThat(ShapeCompatibility.isAssignable(
                new Shape.RefShape(Shape.Kind.FILE_REF, (Shape.ObjectShape) producerMetadata),
                new Shape.RefShape(Shape.Kind.FILE_REF, (Shape.ObjectShape) consumerMetadata)
        )).isFalse();
    }

    @Test
    void shouldRejectUnrelatedCompositeShapes() {
        assertThat(ShapeCompatibility.isAssignable(Shape.list(Shape.string()), Shape.openObject())).isFalse();
    }

    @Test
    void shouldRejectScalarProducerForListConsumer() {
        assertThat(ShapeCompatibility.isAssignable(Shape.string(), Shape.list(Shape.string()))).isFalse();
    }

    @Test
    void shouldRejectReferenceProducerForScalarConsumer() {
        final Shape reference = new Shape.RefShape(
                Shape.Kind.FILE_REF,
                new Shape.ObjectShape(Map.of(), true)
        );

        assertThat(ShapeCompatibility.isAssignable(reference, Shape.string())).isFalse();
    }

    @Test
    void shouldRejectObjectProducerForScalarConsumer() {
        assertThat(ShapeCompatibility.isAssignable(Shape.openObject(), Shape.string())).isFalse();
    }

    @Test
    void shouldAcceptPresentOptionalConsumerField() {
        final Shape producer = Shape.object(Map.of("name", Shape.Field.required(Shape.string())), false);
        final Shape consumer = Shape.object(Map.of("name", Shape.Field.optional(Shape.string())), false);

        assertThat(ShapeCompatibility.isAssignable(producer, consumer)).isTrue();
    }

    @Test
    void shouldValidateEveryScalarRailixValue() {
        assertThatCode(() -> ShapeCompatibility.requireValue(RailixValue.NULL, Shape.nullValue(), "value"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ShapeCompatibility.requireValue(new RailixValue.BoolValue(true), Shape.bool(), "value"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ShapeCompatibility.requireValue(new RailixValue.NumberValue(BigDecimal.ONE), Shape.number(), "value"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ShapeCompatibility.requireValue(new RailixValue.StringValue("railix"), Shape.string(), "value"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldReturnValidatedValue() {
        final RailixValue value = new RailixValue.StringValue("railix");

        assertThat(ShapeCompatibility.requireValue(value, Shape.string(), "value")).isSameAs(value);
    }

    @Test
    void shouldReturnValidatedProducerShape() {
        final Shape producer = Shape.string();

        assertThat(ShapeCompatibility.requireAssignable(producer, Shape.string(), "value")).isSameAs(producer);
    }

    @Test
    void shouldAcceptEveryValueForAnyShape() {
        assertThatCode(() -> ShapeCompatibility.requireValue(
                new RailixValue.ObjectValue(Map.of()),
                Shape.any(),
                "value"
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldValidateEveryListItem() {
        assertThatCode(() -> ShapeCompatibility.requireValue(
                new RailixValue.ListValue(List.of(
                        new RailixValue.StringValue("one"),
                        new RailixValue.StringValue("two")
                )),
                Shape.list(Shape.string()),
                "items"
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectInvalidListItem() {
        assertThatThrownBy(() -> ShapeCompatibility.requireValue(
                new RailixValue.ListValue(List.of(new RailixValue.NumberValue(BigDecimal.ONE))),
                Shape.list(Shape.string()),
                "items"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("items expected list<string> but received list");
    }

    @Test
    void shouldRejectNonListValueForListShape() {
        assertThatThrownBy(() -> ShapeCompatibility.requireValue(
                new RailixValue.StringValue("not-a-list"),
                Shape.list(Shape.string()),
                "items"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("items expected list<string> but received string");
    }

    @Test
    void shouldValidateRequiredAndOptionalObjectFields() {
        final Shape shape = Shape.object(Map.of(
                "name", Shape.Field.required(Shape.string()),
                "nickname", Shape.Field.optional(Shape.string())
        ), false);

        assertThatCode(() -> ShapeCompatibility.requireValue(
                new RailixValue.ObjectValue(Map.of("name", new RailixValue.StringValue("Railix"))),
                shape,
                "user"
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectMissingRequiredObjectFieldAtRuntime() {
        final Shape shape = Shape.object(Map.of("name", Shape.Field.required(Shape.string())), false);

        assertThatThrownBy(() -> ShapeCompatibility.requireValue(
                new RailixValue.ObjectValue(Map.of()),
                shape,
                "user"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user expected object");
    }

    @Test
    void shouldRejectExtraObjectFieldAtRuntimeWhenShapeIsClosed() {
        final Shape shape = Shape.object(Map.of("name", Shape.Field.required(Shape.string())), false);

        assertThatThrownBy(() -> ShapeCompatibility.requireValue(
                new RailixValue.ObjectValue(Map.of(
                        "name", new RailixValue.StringValue("Railix"),
                        "extra", new RailixValue.StringValue("nope")
                )),
                shape,
                "user"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user expected object");
    }

    @Test
    void shouldRejectNonObjectValueForObjectShape() {
        assertThatThrownBy(() -> ShapeCompatibility.requireValue(
                new RailixValue.StringValue("not-an-object"),
                Shape.openObject(),
                "user"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("user expected open-object but received string");
    }

    @Test
    void shouldRejectObjectFieldWithWrongRuntimeShape() {
        final Shape shape = Shape.object(Map.of("name", Shape.Field.required(Shape.string())), false);

        assertThatThrownBy(() -> ShapeCompatibility.requireValue(
                new RailixValue.ObjectValue(Map.of(
                        "name", new RailixValue.NumberValue(BigDecimal.ONE)
                )),
                shape,
                "user"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("user expected object but received object");
    }

    @Test
    void shouldAllowExtraObjectFieldAtRuntimeWhenShapeIsOpen() {
        final Shape shape = Shape.object(Map.of("name", Shape.Field.required(Shape.string())), true);

        assertThatCode(() -> ShapeCompatibility.requireValue(
                new RailixValue.ObjectValue(Map.of(
                        "name", new RailixValue.StringValue("Railix"),
                        "extra", new RailixValue.StringValue("allowed")
                )),
                shape,
                "user"
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectFileReferenceForBlobReferenceShape() {
        final Shape blobRef = new Shape.RefShape(
                Shape.Kind.BLOB_REF,
                new Shape.ObjectShape(Map.of(), true)
        );

        assertThatThrownBy(() -> ShapeCompatibility.requireValue(
                new RailixValue.FileRef("file", "file.json", "application/json", "sha256:2", 2),
                blobRef,
                "value"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("value expected blob-ref but received file-ref");
    }

    @Test
    void shouldValidateEveryReferenceValue() {
        final Shape.ObjectShape metadata = new Shape.ObjectShape(Map.of(), true);

        assertThatCode(() -> ShapeCompatibility.requireValue(
                new RailixValue.BlobRef("blob", "application/json", "sha256:1", 1),
                new Shape.RefShape(Shape.Kind.BLOB_REF, metadata),
                "ref"
        )).doesNotThrowAnyException();
        assertThatCode(() -> ShapeCompatibility.requireValue(
                new RailixValue.FileRef("file", "file.json", "application/json", "sha256:2", 2),
                new Shape.RefShape(Shape.Kind.FILE_REF, metadata),
                "ref"
        )).doesNotThrowAnyException();
        assertThatCode(() -> ShapeCompatibility.requireValue(
                new RailixValue.StreamRef("stream", "string", Map.of()),
                new Shape.RefShape(Shape.Kind.STREAM_REF, metadata),
                "ref"
        )).doesNotThrowAnyException();
        assertThatCode(() -> ShapeCompatibility.requireValue(
                new RailixValue.SessionRef("session", "http", Map.of()),
                new Shape.RefShape(Shape.Kind.SESSION_REF, metadata),
                "ref"
        )).doesNotThrowAnyException();
        assertThatCode(() -> ShapeCompatibility.requireValue(
                new RailixValue.DeferredRef("deferred", "status", Map.of()),
                new Shape.RefShape(Shape.Kind.DEFERRED_REF, metadata),
                "ref"
        )).doesNotThrowAnyException();
        assertThatCode(() -> ShapeCompatibility.requireValue(
                new RailixValue.SecretRef(RailixPath.parse("settings.secret")),
                new Shape.RefShape(Shape.Kind.SECRET_REF, metadata),
                "ref"
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldValidateUnionValueAgainstAnyMatchingVariant() {
        assertThatCode(() -> ShapeCompatibility.requireValue(
                new RailixValue.StringValue("railix"),
                Shape.union(Shape.number(), Shape.string()),
                "value"
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectValueWhenNoUnionVariantMatches() {
        assertThatThrownBy(() -> ShapeCompatibility.requireValue(
                new RailixValue.BoolValue(true),
                Shape.union(Shape.number(), Shape.string()),
                "value"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("value expected number|string but received bool");
    }

    @ParameterizedTest(name = "{0} value is rejected by string shape")
    @MethodSource("nonStringValues")
    void shouldRejectEveryNonStringValueForStringShape(
            final String expectedKind,
            final RailixValue value
    ) {
        assertThatThrownBy(() -> ShapeCompatibility.requireValue(value, Shape.string(), "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("value expected string but received " + expectedKind);
    }

    @ParameterizedTest(name = "{0} value is rejected by file reference shape")
    @MethodSource("nonReferenceValues")
    void shouldRejectEveryNonReferenceValueForReferenceShape(
            final String expectedKind,
            final RailixValue value
    ) {
        final Shape.RefShape fileRef = new Shape.RefShape(
                Shape.Kind.FILE_REF,
                new Shape.ObjectShape(Map.of(), true)
        );

        assertThatThrownBy(() -> ShapeCompatibility.requireValue(value, fileRef, "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("value expected file-ref but received " + expectedKind);
    }

    @ParameterizedTest(name = "{0} reference is rejected by file reference shape")
    @MethodSource("nonFileReferenceValues")
    void shouldRejectEveryOtherReferenceKindForFileReferenceShape(
            final String expectedKind,
            final RailixValue value
    ) {
        final Shape.RefShape fileRef = new Shape.RefShape(
                Shape.Kind.FILE_REF,
                new Shape.ObjectShape(Map.of(), true)
        );

        assertThatThrownBy(() -> ShapeCompatibility.requireValue(value, fileRef, "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("value expected file-ref but received " + expectedKind);
    }

    @Test
    void shouldExposeStableCompatibilityDiagnostic() {
        assertThatThrownBy(() -> ShapeCompatibility.requireAssignable(Shape.number(), Shape.string(), "operator lower"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("operator lower produces number but requires string");
    }

    @Test
    void shouldDescribeCanonicalUnionOrderAndReferenceName() {
        assertThat(ShapeCompatibility.describe(Shape.union(Shape.string(), Shape.number())))
                .isEqualTo("number|string");
        assertThat(ShapeCompatibility.describe(new Shape.RefShape(
                Shape.Kind.FILE_REF,
                new Shape.ObjectShape(Map.of(), true)
        ))).isEqualTo("file-ref");
    }

    @ParameterizedTest(name = "describes {0}")
    @MethodSource("shapeDescriptions")
    void shouldDescribeEveryShapeKind(final String expected, final Shape shape) {
        assertThat(ShapeCompatibility.describe(shape)).isEqualTo(expected);
    }

    private static Stream<Arguments> nonStringValues() {
        return Stream.of(
                Arguments.of("null", RailixValue.NULL),
                Arguments.of("bool", new RailixValue.BoolValue(true)),
                Arguments.of("number", new RailixValue.NumberValue(BigDecimal.ONE)),
                Arguments.of("list", new RailixValue.ListValue(List.of())),
                Arguments.of("object", new RailixValue.ObjectValue(Map.of())),
                Arguments.of("blob-ref", new RailixValue.BlobRef("blob", "application/json", "sha256:1", 1)),
                Arguments.of("file-ref", new RailixValue.FileRef("file", "file.json", "application/json", "sha256:2", 2)),
                Arguments.of("stream-ref", new RailixValue.StreamRef("stream", "string", Map.of())),
                Arguments.of("session-ref", new RailixValue.SessionRef("session", "http", Map.of())),
                Arguments.of("deferred-ref", new RailixValue.DeferredRef("deferred", "status", Map.of())),
                Arguments.of("secret-ref", new RailixValue.SecretRef(RailixPath.parse("settings.secret")))
        );
    }

    private static Stream<Arguments> nonReferenceValues() {
        return Stream.of(
                Arguments.of("null", RailixValue.NULL),
                Arguments.of("bool", new RailixValue.BoolValue(true)),
                Arguments.of("number", new RailixValue.NumberValue(BigDecimal.ONE)),
                Arguments.of("string", new RailixValue.StringValue("railix")),
                Arguments.of("list", new RailixValue.ListValue(List.of())),
                Arguments.of("object", new RailixValue.ObjectValue(Map.of()))
        );
    }

    private static Stream<Arguments> nonFileReferenceValues() {
        return Stream.of(
                Arguments.of("blob-ref", new RailixValue.BlobRef("blob", "application/json", "sha256:1", 1)),
                Arguments.of("stream-ref", new RailixValue.StreamRef("stream", "string", Map.of())),
                Arguments.of("session-ref", new RailixValue.SessionRef("session", "http", Map.of())),
                Arguments.of("deferred-ref", new RailixValue.DeferredRef("deferred", "status", Map.of())),
                Arguments.of("secret-ref", new RailixValue.SecretRef(RailixPath.parse("settings.secret")))
        );
    }

    private static Stream<Arguments> shapeDescriptions() {
        return Stream.of(
                Arguments.of("any", Shape.any()),
                Arguments.of("null", Shape.nullValue()),
                Arguments.of("bool", Shape.bool()),
                Arguments.of("number", Shape.number()),
                Arguments.of("string", Shape.string()),
                Arguments.of("list<string>", Shape.list(Shape.string())),
                Arguments.of("object", Shape.object(Map.of(), false)),
                Arguments.of("open-object", Shape.openObject()),
                Arguments.of("blob-ref", new Shape.RefShape(
                        Shape.Kind.BLOB_REF,
                        new Shape.ObjectShape(Map.of(), true)
                )),
                Arguments.of("number|string", Shape.union(Shape.string(), Shape.number()))
        );
    }
}
