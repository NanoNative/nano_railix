package dev.nanonative.railix.kernel.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record Selector(String expression) {
    public Selector {
        Objects.requireNonNull(expression, "expression");
        if (expression.isBlank()) {
            throw new IllegalArgumentException("Selector must not be blank");
        }
    }

    public List<RailixPath> select(final RailixValue value) {
        final List<Segment> segments = parseSegments(expression);
        final List<RailixPath> matches = new ArrayList<>();
        walk(value, segments, 0, new ArrayList<>(), matches);
        return List.copyOf(matches);
    }

    private static void walk(
            final RailixValue currentValue,
            final List<Segment> segments,
            final int segmentIndex,
            final List<RailixPath.Token> path,
            final List<RailixPath> matches
    ) {
        if (segmentIndex == segments.size()) {
            matches.add(new RailixPath(path));
            return;
        }
        final Segment segment = segments.get(segmentIndex);
        if (segment instanceof KeySegment keySegment) {
            if (!(currentValue instanceof RailixValue.ObjectValue objectValue)) {
                return;
            }
            final RailixValue nextValue = objectValue.values().get(keySegment.key());
            if (nextValue == null) {
                return;
            }
            final List<RailixPath.Token> nextPath = new ArrayList<>(path);
            nextPath.add(new RailixPath.KeyToken(keySegment.key()));
            walk(nextValue, segments, segmentIndex + 1, nextPath, matches);
            return;
        }
        if (!(currentValue instanceof RailixValue.ListValue listValue)) {
            return;
        }
        if (segment instanceof IndexSegment indexSegment) {
            if (indexSegment.index() >= listValue.values().size()) {
                return;
            }
            final List<RailixPath.Token> nextPath = new ArrayList<>(path);
            nextPath.add(new RailixPath.IndexToken(indexSegment.index()));
            walk(listValue.values().get(indexSegment.index()), segments, segmentIndex + 1, nextPath, matches);
            return;
        }
        for (int index = 0; index < listValue.values().size(); index++) {
            final List<RailixPath.Token> nextPath = new ArrayList<>(path);
            nextPath.add(new RailixPath.IndexToken(index));
            walk(listValue.values().get(index), segments, segmentIndex + 1, nextPath, matches);
        }
    }

    private static List<Segment> parseSegments(final String rawExpression) {
        final List<Segment> result = new ArrayList<>();
        final StringBuilder key = new StringBuilder();
        var escaped = false;
        for (int index = 0; index < rawExpression.length(); index++) {
            final char current = rawExpression.charAt(index);
            if (escaped) {
                key.append(current);
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '.') {
                flushKey(result, key);
            } else if (current == '[') {
                flushKey(result, key);
                final int close = rawExpression.indexOf(']', index);
                if (close < 0) {
                    throw new IllegalArgumentException("Unclosed selector segment: " + rawExpression);
                }
                final String raw = rawExpression.substring(index + 1, close);
                result.add("*".equals(raw) ? new WildcardSegment() : new IndexSegment(Integer.parseInt(raw)));
                index = close;
            } else {
                key.append(current);
            }
        }
        if (escaped) {
            throw new IllegalArgumentException("Dangling escape in selector: " + rawExpression);
        }
        flushKey(result, key);
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Selector must contain at least one segment");
        }
        return List.copyOf(result);
    }

    private static void flushKey(final List<Segment> result, final StringBuilder key) {
        if (key.isEmpty()) {
            return;
        }
        result.add(new KeySegment(key.toString()));
        key.setLength(0);
    }

    private sealed interface Segment permits KeySegment, IndexSegment, WildcardSegment {}

    private record KeySegment(String key) implements Segment {
        private KeySegment {
            Objects.requireNonNull(key, "key");
            if (key.isBlank()) {
                throw new IllegalArgumentException("Selector key must not be blank");
            }
        }
    }

    private record IndexSegment(int index) implements Segment {
        private IndexSegment {
            if (index < 0) {
                throw new IllegalArgumentException("Selector index must be >= 0");
            }
        }
    }

    private record WildcardSegment() implements Segment {}
}
