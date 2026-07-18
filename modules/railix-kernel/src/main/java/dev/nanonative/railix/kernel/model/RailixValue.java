package dev.nanonative.railix.kernel.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public sealed interface RailixValue
        permits RailixValue.NullValue,
                RailixValue.BoolValue,
                RailixValue.NumberValue,
                RailixValue.StringValue,
                RailixValue.ListValue,
                RailixValue.ObjectValue,
                RailixValue.BlobRef,
                RailixValue.FileRef,
                RailixValue.StreamRef,
                RailixValue.SessionRef,
                RailixValue.DeferredRef,
                RailixValue.SecretRef {

    NullValue NULL = new NullValue();

    record NullValue() implements RailixValue {}

    record BoolValue(boolean value) implements RailixValue {}

    record NumberValue(BigDecimal value) implements RailixValue {
        public NumberValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record StringValue(String value) implements RailixValue {
        public StringValue {
            Objects.requireNonNull(value, "value");
        }
    }

    record ListValue(List<RailixValue> values) implements RailixValue {
        public ListValue {
            values = List.copyOf(values);
        }
    }

    record ObjectValue(Map<String, RailixValue> values) implements RailixValue {
        public ObjectValue {
            values = Map.copyOf(values);
        }
    }

    record BlobRef(String id, String mediaType, String digest, long size) implements RailixValue {}

    record FileRef(String id, String path, String mediaType, String digest, long size) implements RailixValue {}

    record StreamRef(String id, String itemType, Map<String, RailixValue> metadata) implements RailixValue {
        public StreamRef {
            metadata = Map.copyOf(metadata);
        }
    }

    record SessionRef(String id, String protocol, Map<String, RailixValue> metadata) implements RailixValue {
        public SessionRef {
            metadata = Map.copyOf(metadata);
        }
    }

    record DeferredRef(String id, String statusPath, Map<String, RailixValue> metadata) implements RailixValue {
        public DeferredRef {
            metadata = Map.copyOf(metadata);
        }
    }

    record SecretRef(RailixPath path) implements RailixValue {}
}
