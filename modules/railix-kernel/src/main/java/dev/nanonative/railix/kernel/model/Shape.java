package dev.nanonative.railix.kernel.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable structural type used by Step ports, configuration fields, and connection operators.
 * Shapes describe only Railix values and require no Java reflection or runtime class discovery.
 */
public sealed interface Shape permits Shape.AnyShape, Shape.ScalarShape, Shape.ListShape, Shape.ObjectShape, Shape.RefShape, Shape.UnionShape {

    AnyShape ANY = new AnyShape();

    /**
     * @return the unconstrained shape accepted by any consumer
     */
    static Shape any() {
        return ANY;
    }

    /**
     * @return the boolean scalar shape
     */
    static Shape bool() {
        return new ScalarShape(Kind.BOOL);
    }

    /**
     * @return the explicit null scalar shape
     */
    static Shape nullValue() {
        return new ScalarShape(Kind.NULL);
    }

    /**
     * @return the arbitrary-precision number scalar shape
     */
    static Shape number() {
        return new ScalarShape(Kind.NUMBER);
    }

    /**
     * @return the string scalar shape
     */
    static Shape string() {
        return new ScalarShape(Kind.STRING);
    }

    /**
     * @param itemShape shape required for every list item
     * @return a homogeneous list shape
     */
    static Shape list(final Shape itemShape) {
        return new ListShape(itemShape);
    }

    /**
     * @return an object shape that permits every field
     */
    static Shape openObject() {
        return new ObjectShape(Map.of(), true);
    }

    /**
     * @param fields declared object fields
     * @param open whether undeclared fields are accepted
     * @return an object shape with deterministic field semantics
     */
    static Shape object(final Map<String, Field> fields, final boolean open) {
        return new ObjectShape(fields, open);
    }

    /**
     * @param variants accepted alternatives; at least one is required
     * @return a union shape preserving the declared variants
     */
    static Shape union(final Shape... variants) {
        return new UnionShape(List.of(variants));
    }

    record AnyShape() implements Shape {}

    enum Kind {
        NULL,
        BOOL,
        NUMBER,
        STRING,
        LIST,
        OBJECT,
        BLOB_REF,
        FILE_REF,
        STREAM_REF,
        SESSION_REF,
        DEFERRED_REF,
        SECRET_REF
    }

    record ScalarShape(Kind kind) implements Shape {
        public ScalarShape {
            Objects.requireNonNull(kind, "kind");
            if (kind != Kind.NULL && kind != Kind.BOOL && kind != Kind.NUMBER && kind != Kind.STRING) {
                throw new IllegalArgumentException("ScalarShape requires a scalar kind: " + kind);
            }
        }
    }

    record ListShape(Shape itemShape) implements Shape {
        public ListShape {
            itemShape = Objects.requireNonNull(itemShape, "itemShape");
        }
    }

    record ObjectShape(Map<String, Field> fields, boolean open) implements Shape {
        public ObjectShape {
            fields = Map.copyOf(fields);
        }
    }

    record RefShape(Kind kind, ObjectShape metadataShape) implements Shape {
        public RefShape {
            Objects.requireNonNull(kind, "kind");
            if (kind != Kind.BLOB_REF
                    && kind != Kind.FILE_REF
                    && kind != Kind.STREAM_REF
                    && kind != Kind.SESSION_REF
                    && kind != Kind.DEFERRED_REF
                    && kind != Kind.SECRET_REF) {
                throw new IllegalArgumentException("RefShape requires a ref kind");
            }
            metadataShape = Objects.requireNonNull(metadataShape, "metadataShape");
        }
    }

    record UnionShape(List<Shape> variants) implements Shape {
        public UnionShape {
            variants = List.copyOf(variants);
            if (variants.isEmpty()) {
                throw new IllegalArgumentException("UnionShape requires at least one variant");
            }
        }
    }

    record Field(Shape shape, Presence presence, Confidence confidence) {
        public Field {
            shape = Objects.requireNonNull(shape, "shape");
            presence = Objects.requireNonNull(presence, "presence");
            confidence = Objects.requireNonNull(confidence, "confidence");
        }

        /**
         * @param shape required field shape
         * @return a required field with exact empty-sample confidence
         */
        public static Field required(final Shape shape) {
            return new Field(shape, Presence.REQUIRED, Confidence.exact(0));
        }

        /**
         * @param shape optional field shape
         * @return an optional field with exact empty-sample confidence
         */
        public static Field optional(final Shape shape) {
            return new Field(shape, Presence.OPTIONAL, Confidence.exact(0));
        }
    }

    record Confidence(int observedCount, int sampleCount, List<Kind> conflictingKinds) {
        public Confidence {
            conflictingKinds = List.copyOf(conflictingKinds);
            if (observedCount < 0) {
                throw new IllegalArgumentException("observedCount must be >= 0");
            }
            if (sampleCount < 0) {
                throw new IllegalArgumentException("sampleCount must be >= 0");
            }
            if (observedCount > sampleCount) {
                throw new IllegalArgumentException("observedCount must be <= sampleCount");
            }
        }

        public static Confidence exact(final int sampleCount) {
            return new Confidence(sampleCount, sampleCount, List.of());
        }
    }

    enum Presence {
        REQUIRED,
        OPTIONAL
    }
}
