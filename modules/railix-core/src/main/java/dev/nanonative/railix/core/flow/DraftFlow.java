package dev.nanonative.railix.core.flow;

import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.ValueShape;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

record DraftFlow(
        String id,
        List<DraftTrigger> triggers,
        String entry,
        Map<String, ValueShape> inputs,
        Map<String, ValueShape> outputs,
        List<DraftStep> steps,
        List<DraftConnection> connections
) {
    record DraftTrigger(
            String id,
            String type,
            RailixValue.ObjectValue config,
            int index
    ) {
    }

    record DraftStep(
            String id,
            String use,
            Map<String, RailixValue> config,
            Map<String, String> transitions
    ) {
    }

    record DraftConnection(
            String from,
            Path sourcePath,
            List<RailixValue> defaultValue,
            String conversion,
            String to,
            Path targetPath,
            int index
    ) {
        DraftConnection {
            defaultValue = List.copyOf(defaultValue);
        }
    }

    record Path(List<Element> elements) implements Comparable<Path> {
        Path {
            elements = List.copyOf(elements);
        }

        static Path empty() {
            return new Path(List.of());
        }

        Path prefix(final int length) {
            return new Path(elements.subList(0, length));
        }

        boolean startsWith(final Path other) {
            return elements.size() >= other.elements.size()
                    && elements.subList(0, other.elements.size()).equals(other.elements);
        }

        String json() {
            final List<RailixValue> values = new ArrayList<>(elements.size());
            for (final Element element : elements) {
                values.add(switch (element) {
                    case Field field -> RailixValue.string(field.name());
                    case Index index -> RailixValue.number(index.value());
                });
            }
            return RailixJson.write(RailixValue.array(values));
        }

        @Override
        public int compareTo(final Path other) {
            final int common = Math.min(elements.size(), other.elements.size());
            for (int index = 0; index < common; index++) {
                final int compared = compare(elements.get(index), other.elements.get(index));
                if (compared != 0) {
                    return compared;
                }
            }
            return Integer.compare(elements.size(), other.elements.size());
        }

        private static int compare(final Element left, final Element right) {
            if (left instanceof Field leftField && right instanceof Field rightField) {
                return leftField.name().compareTo(rightField.name());
            }
            if (left instanceof Index leftIndex && right instanceof Index rightIndex) {
                return Integer.compare(leftIndex.value(), rightIndex.value());
            }
            return left instanceof Field ? -1 : 1;
        }

        sealed interface Element permits Field, Index {
        }

        record Field(String name) implements Element {
        }

        record Index(int value) implements Element {
        }
    }
}
