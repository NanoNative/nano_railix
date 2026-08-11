package dev.nanonative.railix.core.value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lossless YAML block subset owned by {@link RailixData}. */
final class RailixYaml {
    private final List<Line> lines;
    private final int maxDepth;
    private int index;

    private RailixYaml(final List<Line> lines, final int maxDepth) {
        this.lines = lines;
        this.maxDepth = maxDepth;
    }

    static RailixValue parse(final String source, final int maxDepth) {
        final List<Line> lines = sourceLines(source);
        if (lines.isEmpty()) {
            throw invalid("Expected a YAML value.", 1, 1);
        }
        final RailixYaml parser = new RailixYaml(lines, maxDepth);
        final RailixValue value = parser.node(0, 0);
        if (parser.index < lines.size()) {
            final Line line = lines.get(parser.index);
            throw invalid("Unexpected YAML indentation.", line.number(), line.indent() + 1);
        }
        return value;
    }

    private RailixValue node(final int indent, final int enclosingDepth) {
        final Line line = lines.get(index);
        if (line.indent() != indent) {
            throw invalid("Unexpected YAML indentation.", line.number(), line.indent() + 1);
        }
        if (sequence(line.text())) {
            return sequence(indent, enclosingDepth + 1);
        }
        if (mappingColon(line.text()) >= 0) {
            return mapping(indent, enclosingDepth + 1);
        }
        index++;
        final RailixValue value = scalar(line.text(), line.number(), indent + 1, enclosingDepth);
        rejectIndentedContinuation(indent);
        return value;
    }

    private RailixValue.ArrayValue sequence(final int indent, final int depth) {
        checkDepth(depth, lines.get(index), indent + 1);
        final List<RailixValue> values = new ArrayList<>();
        while (index < lines.size() && lines.get(index).indent() == indent) {
            final Line line = lines.get(index);
            if (!sequence(line.text())) {
                throw invalid(
                        "Cannot mix YAML mappings and sequences at the same depth.",
                        line.number(),
                        indent + 1
                );
            }
            final int valueOffset = firstContent(line.text(), 1);
            index++;
            if (valueOffset == line.text().length()) {
                values.add(indentedValue(indent, depth, line));
            } else {
                values.add(scalar(
                        line.text().substring(valueOffset),
                        line.number(),
                        indent + valueOffset + 1,
                        depth
                ));
                rejectIndentedContinuation(indent);
            }
        }
        rejectIndentedContinuation(indent);
        return RailixValue.array(values);
    }

    private RailixValue.ObjectValue mapping(final int indent, final int depth) {
        checkDepth(depth, lines.get(index), indent + 1);
        final Map<String, RailixValue> values = new LinkedHashMap<>();
        while (index < lines.size() && lines.get(index).indent() == indent) {
            final Line line = lines.get(index);
            if (sequence(line.text())) {
                throw invalid(
                        "Cannot mix YAML mappings and sequences at the same depth.",
                        line.number(),
                        indent + 1
                );
            }
            final int colon = mappingColon(line.text());
            if (colon < 0) {
                throw invalid(
                        "Cannot mix YAML mappings and sequences at the same depth.",
                        line.number(),
                        indent + 1
                );
            }
            final String key = line.text().substring(0, colon);
            if (key.equals("<<")) {
                throw unsupported("YAML merge keys are not supported.", line.number(), indent + 1);
            }
            if (!key.isEmpty() && referencePrefix(key.charAt(0))) {
                throw unsupported("YAML anchors, aliases, and tags are not supported.", line.number(), indent + 1);
            }
            if (!fieldName(key)) {
                throw invalid(
                        "YAML mapping keys must match [A-Za-z_][A-Za-z0-9_-]*.",
                        line.number(),
                        indent + 1
                );
            }
            if (values.containsKey(key)) {
                throw RailixData.failure(
                        "DATA_FIELD_DUPLICATE",
                        "Duplicate object field: " + key,
                        line.number(),
                        indent + 1
                );
            }

            final int valueOffset = firstContent(line.text(), colon + 1);
            if (valueOffset < line.text().length() && line.text().charAt(colon + 1) != ' ') {
                throw invalid("Expected a space after the YAML mapping colon.", line.number(), indent + colon + 2);
            }
            index++;
            if (valueOffset == line.text().length()) {
                values.put(key, indentedValue(indent, depth, line));
            } else {
                values.put(key, scalar(
                        line.text().substring(valueOffset),
                        line.number(),
                        indent + valueOffset + 1,
                        depth
                ));
                rejectIndentedContinuation(indent);
            }
        }
        rejectIndentedContinuation(indent);
        return RailixValue.object(values);
    }

    private RailixValue indentedValue(final int indent, final int depth, final Line parent) {
        if (index >= lines.size() || lines.get(index).indent() <= indent) {
            throw invalid("Expected an indented YAML value.", parent.number(), parent.text().length() + 1);
        }
        final Line child = lines.get(index);
        if (child.indent() != indent + 2) {
            throw invalid("Unexpected YAML indentation.", child.number(), child.indent() + 1);
        }
        return node(indent + 2, depth);
    }

    private RailixValue scalar(
            final String source,
            final int line,
            final int column,
            final int enclosingDepth
    ) {
        return switch (source) {
            case "null" -> RailixValue.nullValue();
            case "true" -> RailixValue.bool(true);
            case "false" -> RailixValue.bool(false);
            case "{}" -> {
                checkDepth(enclosingDepth + 1, new Line(line, column - 1, source), column);
                yield RailixValue.object(Map.of());
            }
            case "[]" -> {
                checkDepth(enclosingDepth + 1, new Line(line, column - 1, source), column);
                yield RailixValue.array(List.of());
            }
            default -> scalarText(source, line, column);
        };
    }

    private RailixValue scalarText(final String source, final int line, final int column) {
        final char first = source.charAt(0);
        if (first == '"') {
            return switch (RailixJson.parse(source)) {
                case RailixJson.Parsed parsed -> parsed.value();
                default -> throw invalid("Invalid JSON-quoted YAML string.", line, column);
            };
        }
        if (first == '\'') {
            throw unsupported("YAML single-quoted strings are not supported.", line, column);
        }
        if (first == '[' || first == '{') {
            throw unsupported("Non-empty YAML flow collections are not supported.", line, column);
        }
        if (referencePrefix(first)) {
            throw unsupported("YAML anchors, aliases, and tags are not supported.", line, column);
        }
        if (first == '|' || first == '>') {
            throw unsupported("YAML block scalars are not supported.", line, column);
        }
        if (first == '-' || asciiDigit(first)) {
            return switch (RailixJson.parse(source)) {
                case RailixJson.Parsed parsed -> parsed.value();
                case RailixJson.Invalid invalid
                        when RailixJson.NUMBER_SOURCE_LIMIT_MESSAGE.equals(invalid.message()) ->
                        throw RailixData.numberLimitExceeded();
                case RailixJson.Invalid ignored -> throw invalid("Invalid YAML number.", line, column);
            };
        }
        throw unsupported(
                "Plain YAML strings are not supported; use JSON double quotes.",
                line,
                column
        );
    }

    private void checkDepth(final int depth, final Line line, final int column) {
        if (depth > maxDepth) {
            throw RailixData.failure(
                    "DATA_DEPTH_EXCEEDED",
                    "Data exceeds the maximum container depth of " + maxDepth + ".",
                    line.number(),
                    column
            );
        }
    }

    private void rejectIndentedContinuation(final int indent) {
        if (index < lines.size() && lines.get(index).indent() > indent) {
            final Line line = lines.get(index);
            throw invalid("Unexpected YAML indentation.", line.number(), line.indent() + 1);
        }
    }

    private static List<Line> sourceLines(final String source) {
        final String[] rawLines = source.split("\\r\\n|\\r|\\n", -1);
        final List<Line> lines = new ArrayList<>();
        for (int index = 0; index < rawLines.length; index++) {
            final String raw = rawLines[index];
            final int tab = raw.indexOf('\t');
            if (tab >= 0) {
                throw unsupported("YAML tabs are not supported.", index + 1, tab + 1);
            }
            final int comment = outsideQuote(raw, '#');
            if (comment >= 0) {
                throw unsupported("YAML comments are not supported.", index + 1, comment + 1);
            }
            final String trimmed = stripTrailingSpaces(raw);
            final int indent = leadingSpaces(trimmed);
            if (indent == trimmed.length()) {
                continue;
            }
            if (indent % 2 != 0) {
                throw invalid(
                        "YAML indentation must use multiples of two spaces.",
                        index + 1,
                        1
                );
            }
            final String text = trimmed.substring(indent);
            if (text.equals("---") || text.equals("...")) {
                throw unsupported("YAML document markers are not supported.", index + 1, indent + 1);
            }
            if (text.charAt(0) == '%') {
                throw unsupported("YAML directives are not supported.", index + 1, indent + 1);
            }
            lines.add(new Line(index + 1, indent, text));
        }
        return lines;
    }

    private static int mappingColon(final String source) {
        return outsideQuote(source, ':');
    }

    private static int outsideQuote(final String source, final char expected) {
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            final char character = source.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    quoted = false;
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == expected) {
                return index;
            }
        }
        return -1;
    }

    private static int firstContent(final String source, final int start) {
        int index = start;
        while (index < source.length() && source.charAt(index) == ' ') {
            index++;
        }
        return index;
    }

    private static boolean sequence(final String source) {
        return source.equals("-") || source.startsWith("- ");
    }

    private static boolean fieldName(final String source) {
        if (source.isEmpty() || !fieldStart(source.charAt(0))) {
            return false;
        }
        for (int index = 1; index < source.length(); index++) {
            final char character = source.charAt(index);
            if (!fieldStart(character) && !asciiDigit(character) && character != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean fieldStart(final char character) {
        return character == '_'
                || character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z';
    }

    private static boolean asciiDigit(final char character) {
        return character >= '0' && character <= '9';
    }

    private static boolean referencePrefix(final char character) {
        return character == '&' || character == '*' || character == '!';
    }

    private static int leadingSpaces(final String source) {
        int index = 0;
        while (index < source.length() && source.charAt(index) == ' ') {
            index++;
        }
        return index;
    }

    private static String stripTrailingSpaces(final String source) {
        int end = source.length();
        while (end > 0 && source.charAt(end - 1) == ' ') {
            end--;
        }
        return source.substring(0, end);
    }

    private static RailixData.Failure invalid(final String message, final int line, final int column) {
        return RailixData.failure("DATA_YAML_INVALID", message, line, column);
    }

    private static RailixData.Failure unsupported(final String message, final int line, final int column) {
        return RailixData.failure("DATA_YAML_UNSUPPORTED", message, line, column);
    }

    private record Line(int number, int indent, String text) {
    }
}
