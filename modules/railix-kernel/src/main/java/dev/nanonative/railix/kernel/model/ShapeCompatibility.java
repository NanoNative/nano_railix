package dev.nanonative.railix.kernel.model;

import java.util.Map;
import java.util.Objects;

/**
 * Structural compatibility and runtime validation for declared Railix shapes.
 */
public final class ShapeCompatibility {

    private ShapeCompatibility() {}

    /**
     * Tests whether every value described by a producer shape can be consumed by another shape.
     *
     * @param producer shape declared by an output or operator
     * @param consumer shape declared by an input or operator
     * @return {@code true} when assignment is structurally safe
     */
    public static boolean isAssignable(final Shape producer, final Shape consumer) {
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(consumer, "consumer");
        if (consumer instanceof Shape.AnyShape) {
            return true;
        }
        if (producer instanceof Shape.AnyShape) {
            return false;
        }
        if (producer instanceof Shape.UnionShape producerUnion) {
            return producerUnion.variants().stream().allMatch(variant -> isAssignable(variant, consumer));
        }
        if (consumer instanceof Shape.UnionShape consumerUnion) {
            return consumerUnion.variants().stream().anyMatch(variant -> isAssignable(producer, variant));
        }
        if (producer instanceof Shape.ScalarShape producerScalar
                && consumer instanceof Shape.ScalarShape consumerScalar) {
            return producerScalar.kind() == consumerScalar.kind();
        }
        if (producer instanceof Shape.ListShape producerList
                && consumer instanceof Shape.ListShape consumerList) {
            return isAssignable(producerList.itemShape(), consumerList.itemShape());
        }
        if (producer instanceof Shape.RefShape producerRef
                && consumer instanceof Shape.RefShape consumerRef) {
            return producerRef.kind() == consumerRef.kind()
                    && isAssignable(producerRef.metadataShape(), consumerRef.metadataShape());
        }
        if (producer instanceof Shape.ObjectShape producerObject
                && consumer instanceof Shape.ObjectShape consumerObject) {
            return isObjectAssignable(producerObject, consumerObject);
        }
        return false;
    }

    /**
     * Requires structural assignment compatibility.
     *
     * @param producer shape declared by an output or operator
     * @param consumer shape declared by an input or operator
     * @param subject diagnostic subject identifying the connection
     * @return the validated producer shape
     * @throws IllegalArgumentException when the producer cannot satisfy the consumer
     */
    public static Shape requireAssignable(final Shape producer, final Shape consumer, final String subject) {
        if (!isAssignable(producer, consumer)) {
            throw new IllegalArgumentException(subject + " produces " + describe(producer)
                    + " but requires " + describe(consumer));
        }
        return producer;
    }

    /**
     * Requires a runtime value to conform to a declared shape.
     *
     * @param value runtime value to validate
     * @param shape required shape
     * @param subject diagnostic subject identifying the value
     * @return the validated value
     * @throws IllegalArgumentException when the value does not conform
     */
    public static RailixValue requireValue(final RailixValue value, final Shape shape, final String subject) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(shape, "shape");
        if (!matches(value, shape)) {
            throw new IllegalArgumentException(subject + " expected " + describe(shape)
                    + " but received " + valueKind(value));
        }
        return value;
    }

    /**
     * Returns the canonical human-readable spelling of a shape.
     *
     * @param shape shape to describe
     * @return deterministic shape description
     */
    public static String describe(final Shape shape) {
        Objects.requireNonNull(shape, "shape");
        return switch (shape) {
            case Shape.AnyShape ignored -> "any";
            case Shape.ScalarShape scalar -> scalar.kind().name().toLowerCase();
            case Shape.ListShape list -> "list<" + describe(list.itemShape()) + ">";
            case Shape.ObjectShape object -> object.open() ? "open-object" : "object";
            case Shape.RefShape ref -> ref.kind().name().toLowerCase().replace('_', '-');
            case Shape.UnionShape union -> union.variants().stream()
                    .map(ShapeCompatibility::describe)
                    .sorted()
                    .reduce((left, right) -> left + "|" + right)
                    .orElseThrow();
        };
    }

    private static boolean isObjectAssignable(
            final Shape.ObjectShape producer,
            final Shape.ObjectShape consumer
    ) {
        for (final Map.Entry<String, Shape.Field> required : consumer.fields().entrySet()) {
            final Shape.Field available = producer.fields().get(required.getKey());
            if (available == null) {
                if (required.getValue().presence() == Shape.Presence.REQUIRED) {
                    return false;
                }
                continue;
            }
            if (required.getValue().presence() == Shape.Presence.REQUIRED
                    && available.presence() != Shape.Presence.REQUIRED) {
                return false;
            }
            if (!isAssignable(available.shape(), required.getValue().shape())) {
                return false;
            }
        }
        if (!consumer.open()) {
            if (producer.open()) {
                return false;
            }
            return consumer.fields().keySet().containsAll(producer.fields().keySet());
        }
        return true;
    }

    private static boolean matches(final RailixValue value, final Shape shape) {
        return switch (shape) {
            case Shape.AnyShape ignored -> true;
            case Shape.ScalarShape scalar -> matchesScalar(value, scalar.kind());
            case Shape.ListShape list -> value instanceof RailixValue.ListValue listValue
                    && listValue.values().stream().allMatch(item -> matches(item, list.itemShape()));
            case Shape.ObjectShape object -> value instanceof RailixValue.ObjectValue objectValue
                    && matchesObject(objectValue, object);
            case Shape.RefShape ref -> matchesRef(value, ref.kind());
            case Shape.UnionShape union -> union.variants().stream().anyMatch(variant -> matches(value, variant));
        };
    }

    private static boolean matchesScalar(final RailixValue value, final Shape.Kind kind) {
        return switch (value) {
            case RailixValue.NullValue ignored -> kind == Shape.Kind.NULL;
            case RailixValue.BoolValue ignored -> kind == Shape.Kind.BOOL;
            case RailixValue.NumberValue ignored -> kind == Shape.Kind.NUMBER;
            case RailixValue.StringValue ignored -> kind == Shape.Kind.STRING;
            case RailixValue.ListValue ignored -> false;
            case RailixValue.ObjectValue ignored -> false;
            case RailixValue.BlobRef ignored -> false;
            case RailixValue.FileRef ignored -> false;
            case RailixValue.StreamRef ignored -> false;
            case RailixValue.SessionRef ignored -> false;
            case RailixValue.DeferredRef ignored -> false;
            case RailixValue.SecretRef ignored -> false;
        };
    }

    private static boolean matchesObject(final RailixValue.ObjectValue value, final Shape.ObjectShape shape) {
        for (final Map.Entry<String, Shape.Field> field : shape.fields().entrySet()) {
            final RailixValue fieldValue = value.values().get(field.getKey());
            if (fieldValue == null) {
                if (field.getValue().presence() == Shape.Presence.REQUIRED) {
                    return false;
                }
                continue;
            }
            if (!matches(fieldValue, field.getValue().shape())) {
                return false;
            }
        }
        return shape.open() || shape.fields().keySet().containsAll(value.values().keySet());
    }

    private static boolean matchesRef(final RailixValue value, final Shape.Kind kind) {
        return switch (value) {
            case RailixValue.BlobRef ignored -> kind == Shape.Kind.BLOB_REF;
            case RailixValue.FileRef ignored -> kind == Shape.Kind.FILE_REF;
            case RailixValue.StreamRef ignored -> kind == Shape.Kind.STREAM_REF;
            case RailixValue.SessionRef ignored -> kind == Shape.Kind.SESSION_REF;
            case RailixValue.DeferredRef ignored -> kind == Shape.Kind.DEFERRED_REF;
            case RailixValue.SecretRef ignored -> kind == Shape.Kind.SECRET_REF;
            case RailixValue.NullValue ignored -> false;
            case RailixValue.BoolValue ignored -> false;
            case RailixValue.NumberValue ignored -> false;
            case RailixValue.StringValue ignored -> false;
            case RailixValue.ListValue ignored -> false;
            case RailixValue.ObjectValue ignored -> false;
        };
    }

    private static String valueKind(final RailixValue value) {
        return switch (value) {
            case RailixValue.NullValue ignored -> "null";
            case RailixValue.BoolValue ignored -> "bool";
            case RailixValue.NumberValue ignored -> "number";
            case RailixValue.StringValue ignored -> "string";
            case RailixValue.ListValue ignored -> "list";
            case RailixValue.ObjectValue ignored -> "object";
            case RailixValue.BlobRef ignored -> "blob-ref";
            case RailixValue.FileRef ignored -> "file-ref";
            case RailixValue.StreamRef ignored -> "stream-ref";
            case RailixValue.SessionRef ignored -> "session-ref";
            case RailixValue.DeferredRef ignored -> "deferred-ref";
            case RailixValue.SecretRef ignored -> "secret-ref";
        };
    }
}
