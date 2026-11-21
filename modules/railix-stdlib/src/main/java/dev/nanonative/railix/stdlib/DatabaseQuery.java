package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Filter/condition AST, field/path validation, range solving, and projections for {@code railix.database}. */
final class DatabaseQuery {
    static final int MAX_FIELD_NAMES = 32;
    static final int DEFAULT_FIND_LIMIT = 100;
    static final int MAX_FIND_LIMIT = 1_000;
    static final int MAX_FILTER_DEPTH = 16;
    static final int MAX_FILTER_CLAUSES = 128;
    static final int MAX_OPERATOR_ARRAY_SIZE = 1_000;
    static final int MAX_FILTER_PATH_SEGMENTS = 16;
    static final int MAX_FIELD_PATH_BYTES = 512;

    static final ValueRefinement DOCUMENT_REFINEMENT = ValueRefinement.canonical()
            .withMaxJsonBytes(RailixData.DEFAULT_MAX_SOURCE_BYTES);
    static final RailixValue.ObjectValue NO_DEFAULTS = RailixValue.object(Map.of());
    static final FilterNode MATCH_ALL_FILTER = new AndNode(List.of());

    private DatabaseQuery() {
    }

    enum FilterOp {
        EQ, NE, GT, GTE, LT, LTE, IN, NIN, EXISTS
    }

    private enum AbsentEval {
        FALSE, TRUE_IMPLICIT, TRUE_EXPLICIT
    }

    record FieldPredicate(
            FilterOp op,
            RailixValue scalar,
            Set<RailixValue> set,
            boolean boolValue
    ) {}

    sealed interface FilterNode permits AndNode, OrNode, FieldNode {}

    record AndNode(List<FilterNode> children) implements FilterNode {}

    record OrNode(List<FilterNode> children) implements FilterNode {}

    record FieldNode(
            String fieldPath,
            List<String> segments,
            List<FieldPredicate> predicates
    ) implements FilterNode {}

    record RangeBounds(
            byte targetType,
            DatabaseKey loKey,
            boolean loInclusive,
            DatabaseKey hiKey,
            boolean hiInclusive,
            boolean empty
    ) {
        static final RangeBounds EMPTY = new RangeBounds((byte) -1, null, false, null, false, true);
    }

    record ParsedCondition(boolean valid, FilterNode filter) {
        static final ParsedCondition NONE = new ParsedCondition(true, null);
        static final ParsedCondition INVALID = new ParsedCondition(false, null);
    }

    static boolean validFieldName(final String value) {
        if (value == null || value.isBlank() || DatabaseKey.invalidSurrogate(value)) {
            return false;
        }
        if (value.charAt(0) == '$' || value.indexOf('.') >= 0) {
            return false;
        }
        return value.getBytes(StandardCharsets.UTF_8).length <= 128;
    }

    static boolean validFieldPath(final String value) {
        if (value == null || value.isBlank() || DatabaseKey.invalidSurrogate(value)) {
            return false;
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_FIELD_PATH_BYTES) {
            return false;
        }
        int segmentCount = 0;
        int start = 0;
        while (start <= value.length()) {
            final int dot = value.indexOf('.', start);
            final String segment = dot < 0 ? value.substring(start) : value.substring(start, dot);
            if (!validFieldName(segment)) {
                return false;
            }
            segmentCount++;
            if (segmentCount > MAX_FILTER_PATH_SEGMENTS) {
                return false;
            }
            if (dot < 0) {
                break;
            }
            start = dot + 1;
        }
        return true;
    }

    static List<String> splitFieldPath(final String path) {
        if (path.indexOf('.') < 0) {
            return List.of(path);
        }
        final List<String> segments = new ArrayList<>(4);
        int start = 0;
        while (start <= path.length()) {
            final int dot = path.indexOf('.', start);
            if (dot < 0) {
                segments.add(path.substring(start));
                break;
            }
            segments.add(path.substring(start, dot));
            start = dot + 1;
        }
        return List.copyOf(segments);
    }

    static Optional<String> fieldListRejection(final RailixValue.ArrayValue value, final boolean allowDotPaths) {
        if (value.values().size() > MAX_FIELD_NAMES) {
            return Optional.of("Use at most " + MAX_FIELD_NAMES + " field names.");
        }
        final Set<String> seen = new HashSet<>();
        for (final RailixValue item : value.values()) {
            if (!(item instanceof RailixValue.StringValue string)
                    || !(allowDotPaths ? validFieldPath(string.value()) : validFieldName(string.value()))) {
                return Optional.of(allowDotPaths
                        ? "Index field paths must have valid non-blank segments without leading $, dots at "
                                + "boundaries, or '..'."
                        : "Field names must be non-blank text up to 128 bytes without '.' or leading '$'.");
            }
            if (!seen.add(string.value())) {
                return Optional.of("Field name is listed more than once: " + string.value() + ".");
            }
        }
        return Optional.empty();
    }

    static Optional<String> defaultsRejection(final RailixValue.ObjectValue value) {
        if (value.values().size() > MAX_FIELD_NAMES) {
            return Optional.of("Use at most " + MAX_FIELD_NAMES + " default fields.");
        }
        for (final String name : value.values().keySet()) {
            if (!validFieldName(name)) {
                return Optional.of(
                        "Default field names must be non-blank text up to 128 bytes without '.' or leading '$'."
                );
            }
        }
        return DOCUMENT_REFINEMENT.rejection(value).isPresent()
                ? Optional.of("Defaults exceed the supported JSON value bounds.")
                : Optional.empty();
    }

    /** Returns Java null when any path step is absent; returns {@link RailixValue.NullValue} for JSON null. */
    static RailixValue readPathValue(final RailixValue.ObjectValue document, final List<String> segments) {
        if (segments.size() == 1) {
            return document.values().get(segments.getFirst());
        }
        RailixValue current = document;
        for (final String segment : segments) {
            if (current instanceof RailixValue.ObjectValue object) {
                current = object.values().get(segment);
                if (current == null) {
                    return null;
                }
            } else if (current instanceof RailixValue.ArrayValue array) {
                final int index = parseArrayIndex(segment);
                if (index < 0 || index >= array.values().size()) {
                    return null;
                }
                current = array.values().get(index);
            } else {
                return null;
            }
        }
        return current;
    }

    private static int parseArrayIndex(final String segment) {
        final int len = segment.length();
        if (len == 0 || len > 7) {
            return -1;
        }
        if (len == 1) {
            final char c = segment.charAt(0);
            return (c >= '0' && c <= '9') ? c - '0' : -1;
        }
        if (segment.charAt(0) < '1' || segment.charAt(0) > '9') {
            return -1;
        }
        int parsed = 0;
        for (int i = 0; i < len; i++) {
            final char c = segment.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            parsed = parsed * 10 + (c - '0');
        }
        return parsed;
    }

    static ParsedCondition parseCondition(final StepInput configuration, final StepInput selected) {
        final boolean authored = configuration.options().containsKey("operation")
                && configuration.selected("operation").optionalValue("condition").isPresent();
        final RailixValue runtimeValue = selected.optionalValue("condition").orElse(null);
        if (!authored && runtimeValue == null) {
            return ParsedCondition.NONE;
        }
        final FilterNode filter = parseFilter(runtimeValue);
        return filter == null ? ParsedCondition.INVALID : new ParsedCondition(true, filter);
    }

    static FilterNode parseFilter(final RailixValue candidate) {
        if (!(candidate instanceof RailixValue.ObjectValue object)
                || DOCUMENT_REFINEMENT.rejection(object).isPresent()) {
            return null;
        }
        return parseFilterObject(object, 0, new int[]{MAX_FILTER_CLAUSES});
    }

    private static FilterNode parseFilterObject(
            final RailixValue.ObjectValue object,
            final int depth,
            final int[] budget
    ) {
        if (depth > MAX_FILTER_DEPTH) {
            return null;
        }
        final Map<String, RailixValue> entries = object.values();
        if (entries.isEmpty()) {
            return depth > 0 && --budget[0] < 0 ? null : MATCH_ALL_FILTER;
        }
        final List<FilterNode> children = new ArrayList<>(entries.size());
        for (final Map.Entry<String, RailixValue> entry : entries.entrySet()) {
            if (--budget[0] < 0) {
                return null;
            }
            final String key = entry.getKey();
            final RailixValue value = entry.getValue();
            if ("$and".equals(key) || "$or".equals(key)) {
                if (!(value instanceof RailixValue.ArrayValue array) || array.values().isEmpty()) {
                    return null;
                }
                final List<FilterNode> subNodes = new ArrayList<>(array.values().size());
                for (final RailixValue item : array.values()) {
                    if (!(item instanceof RailixValue.ObjectValue subObject)) {
                        return null;
                    }
                    final FilterNode parsed = parseFilterObject(subObject, depth + 1, budget);
                    if (parsed == null) {
                        return null;
                    }
                    subNodes.add(parsed);
                }
                children.add("$and".equals(key) ? new AndNode(subNodes) : new OrNode(subNodes));
            } else if (key.startsWith("$") || !validFieldPath(key)) {
                return null;
            } else {
                final List<FieldPredicate> predicates = parseFieldPredicates(value, budget);
                if (predicates == null) {
                    return null;
                }
                children.add(new FieldNode(key, splitFieldPath(key), predicates));
            }
        }
        return children.size() == 1 ? children.getFirst() : new AndNode(List.copyOf(children));
    }

    private static List<FieldPredicate> parseFieldPredicates(final RailixValue value, final int[] budget) {
        if (!(value instanceof RailixValue.ObjectValue object) || !hasOperatorKey(object)) {
            return List.of(new FieldPredicate(FilterOp.EQ, DatabaseKey.canonicalValue(value), Set.of(), false));
        }
        final List<FieldPredicate> predicates = new ArrayList<>(object.values().size());
        for (final Map.Entry<String, RailixValue> entry : object.values().entrySet()) {
            if (--budget[0] < 0) {
                return null;
            }
            final String opName = entry.getKey();
            if (!opName.startsWith("$")) {
                return null;
            }
            final FieldPredicate parsed = parseSingleOperator(opName, entry.getValue());
            if (parsed == null) {
                return null;
            }
            predicates.add(parsed);
        }
        return List.copyOf(predicates);
    }

    private static FieldPredicate parseSingleOperator(final String opName, final RailixValue operand) {
        return switch (opName) {
            case "$eq" -> new FieldPredicate(FilterOp.EQ, DatabaseKey.canonicalValue(operand), Set.of(), false);
            case "$ne" -> new FieldPredicate(FilterOp.NE, DatabaseKey.canonicalValue(operand), Set.of(), false);
            case "$gt", "$gte", "$lt", "$lte" -> {
                if (!(operand instanceof RailixValue.NumberValue)
                        && !(operand instanceof RailixValue.StringValue)) {
                    yield null;
                }
                final FilterOp op = switch (opName) {
                    case "$gt" -> FilterOp.GT;
                    case "$gte" -> FilterOp.GTE;
                    case "$lt" -> FilterOp.LT;
                    default -> FilterOp.LTE;
                };
                yield new FieldPredicate(op, DatabaseKey.canonicalValue(operand), Set.of(), false);
            }
            case "$in", "$nin" -> {
                if (!(operand instanceof RailixValue.ArrayValue array)
                        || array.values().size() > MAX_OPERATOR_ARRAY_SIZE) {
                    yield null;
                }
                final Set<RailixValue> canonicalSet = new HashSet<>(Math.max(4, array.values().size() * 2));
                for (final RailixValue item : array.values()) {
                    canonicalSet.add(DatabaseKey.canonicalValue(item));
                }
                yield new FieldPredicate(
                        "$in".equals(opName) ? FilterOp.IN : FilterOp.NIN,
                        null,
                        Set.copyOf(canonicalSet),
                        false
                );
            }
            case "$exists" -> operand instanceof RailixValue.BooleanValue bool
                    ? new FieldPredicate(FilterOp.EXISTS, null, Set.of(), bool.value())
                    : null;
            default -> null;
        };
    }

    private static boolean hasOperatorKey(final RailixValue.ObjectValue object) {
        for (final String key : object.values().keySet()) {
            if (key.startsWith("$")) {
                return true;
            }
        }
        return false;
    }

    static RangeBounds rangeBounds(final List<FieldPredicate> predicates) {
        byte targetType = -1;
        DatabaseKey loKey = null;
        boolean loInc = false;
        DatabaseKey hiKey = null;
        boolean hiInc = false;
        boolean hasRange = false;

        for (final FieldPredicate predicate : predicates) {
            final FilterOp op = predicate.op();
            if (op != FilterOp.GT && op != FilterOp.GTE && op != FilterOp.LT && op != FilterOp.LTE) {
                continue;
            }
            hasRange = true;
            final DatabaseKey encoded = DatabaseKey.encodeIndexValue(predicate.scalar());
            if (encoded == null) {
                return RangeBounds.EMPTY;
            }
            final byte type = encoded.typeTag();
            if (targetType < 0) {
                targetType = type;
            } else if (targetType != type) {
                return RangeBounds.EMPTY;
            }
            final boolean inclusive = op == FilterOp.GTE || op == FilterOp.LTE;
            if (op == FilterOp.GT || op == FilterOp.GTE) {
                final int cmp = loKey == null ? 1 : encoded.compareTo(loKey);
                if (loKey == null || cmp > 0 || (cmp == 0 && !inclusive)) {
                    loKey = encoded;
                    loInc = inclusive;
                }
            } else {
                final int cmp = hiKey == null ? -1 : encoded.compareTo(hiKey);
                if (hiKey == null || cmp < 0 || (cmp == 0 && !inclusive)) {
                    hiKey = encoded;
                    hiInc = inclusive;
                }
            }
        }
        if (!hasRange) {
            return null;
        }
        if (loKey != null && hiKey != null) {
            final int cmp = loKey.compareTo(hiKey);
            if (cmp > 0 || (cmp == 0 && (!loInc || !hiInc))) {
                return RangeBounds.EMPTY;
            }
        }
        return new RangeBounds(targetType, loKey, loInc, hiKey, hiInc, false);
    }

    static NavigableSet<DatabaseKey> primaryRangeView(
            final NavigableSet<DatabaseKey> sortedKeys,
            final RangeBounds bounds
    ) {
        if (bounds.empty()) {
            return Collections.emptyNavigableSet();
        }
        final DatabaseKey lo = bounds.loKey();
        final DatabaseKey hi = bounds.hiKey();
        if (lo != null && hi != null) {
            return sortedKeys.subSet(lo, bounds.loInclusive(), hi, bounds.hiInclusive());
        }
        if (bounds.targetType() == DatabaseKey.TYPE_NUMBER) {
            return lo != null
                    ? sortedKeys.subSet(lo, bounds.loInclusive(), DatabaseKey.EMPTY_STRING, false)
                    : sortedKeys.headSet(hi, bounds.hiInclusive());
        }
        if (lo != null) {
            return sortedKeys.tailSet(lo, bounds.loInclusive());
        }
        if (hi.compareTo(DatabaseKey.EMPTY_STRING) <= 0) {
            return Collections.emptyNavigableSet();
        }
        return sortedKeys.subSet(DatabaseKey.EMPTY_STRING, false, hi, bounds.hiInclusive());
    }

    static NavigableSet<DatabaseKey> tailAfter(final NavigableSet<DatabaseKey> set, final DatabaseKey afterKey) {
        if (afterKey == null || set.isEmpty()) {
            return set;
        }
        if (afterKey.compareTo(set.first()) < 0) {
            return set;
        }
        if (afterKey.compareTo(set.last()) >= 0) {
            return Collections.emptyNavigableSet();
        }
        return set.tailSet(afterKey, false);
    }

    static void collectSecondaryRangeKeys(
            final NavigableMap<DatabaseKey, NavigableSet<DatabaseKey>> byValue,
            final RangeBounds bounds,
            final DatabaseKey afterKey,
            final Set<DatabaseKey> target
    ) {
        if (bounds.empty()) {
            return;
        }
        final NavigableSet<DatabaseKey> slice;
        if (bounds.loKey() != null && bounds.hiKey() != null) {
            slice = byValue.navigableKeySet().subSet(
                    bounds.loKey(), bounds.loInclusive(), bounds.hiKey(), bounds.hiInclusive()
            );
        } else if (bounds.loKey() != null) {
            slice = byValue.navigableKeySet().tailSet(bounds.loKey(), bounds.loInclusive());
        } else {
            slice = byValue.navigableKeySet().headSet(bounds.hiKey(), bounds.hiInclusive()).descendingSet();
        }
        for (final DatabaseKey valueKey : slice) {
            if (valueKey.typeTag() != bounds.targetType()) {
                break;
            }
            final NavigableSet<DatabaseKey> posting = byValue.get(valueKey);
            if (posting != null) {
                target.addAll(tailAfter(posting, afterKey));
            }
        }
    }

    static boolean matchesCondition(
            final RailixValue.ObjectValue existing,
            final FilterNode condition,
            final String keyField
    ) {
        return existing == null
                ? evalAbsent(condition, keyField) == AbsentEval.TRUE_EXPLICIT
                : matchesFilterNode(existing, condition);
    }

    private static AbsentEval evalAbsent(final FilterNode node, final String keyField) {
        return switch (node) {
            case AndNode and -> {
                boolean explicit = false;
                for (final FilterNode child : and.children()) {
                    final AbsentEval childEval = evalAbsent(child, keyField);
                    if (childEval == AbsentEval.FALSE) {
                        yield AbsentEval.FALSE;
                    }
                    if (childEval == AbsentEval.TRUE_EXPLICIT) {
                        explicit = true;
                    }
                }
                yield explicit ? AbsentEval.TRUE_EXPLICIT : AbsentEval.TRUE_IMPLICIT;
            }
            case OrNode or -> {
                boolean implicit = false;
                for (final FilterNode child : or.children()) {
                    final AbsentEval childEval = evalAbsent(child, keyField);
                    if (childEval == AbsentEval.TRUE_EXPLICIT) {
                        yield AbsentEval.TRUE_EXPLICIT;
                    }
                    if (childEval == AbsentEval.TRUE_IMPLICIT) {
                        implicit = true;
                    }
                }
                yield implicit ? AbsentEval.TRUE_IMPLICIT : AbsentEval.FALSE;
            }
            case FieldNode field -> {
                boolean explicit = false;
                final boolean isKey = field.fieldPath().equals(keyField);
                for (final FieldPredicate predicate : field.predicates()) {
                    if (!matchesFieldPredicate(null, predicate)) {
                        yield AbsentEval.FALSE;
                    }
                    if (isKey && predicate.op() == FilterOp.EXISTS && !predicate.boolValue()) {
                        explicit = true;
                    }
                }
                yield explicit ? AbsentEval.TRUE_EXPLICIT : AbsentEval.TRUE_IMPLICIT;
            }
        };
    }

    static boolean matchesFilterNode(final RailixValue.ObjectValue stored, final FilterNode node) {
        return switch (node) {
            case AndNode and -> {
                for (final FilterNode child : and.children()) {
                    if (!matchesFilterNode(stored, child)) {
                        yield false;
                    }
                }
                yield true;
            }
            case OrNode or -> {
                for (final FilterNode child : or.children()) {
                    if (matchesFilterNode(stored, child)) {
                        yield true;
                    }
                }
                yield false;
            }
            case FieldNode field -> {
                final RailixValue actual = readPathValue(stored, field.segments());
                for (final FieldPredicate predicate : field.predicates()) {
                    if (!matchesFieldPredicate(actual, predicate)) {
                        yield false;
                    }
                }
                yield true;
            }
        };
    }

    private static boolean matchesFieldPredicate(final RailixValue actual, final FieldPredicate predicate) {
        return switch (predicate.op()) {
            case EXISTS -> (actual != null) == predicate.boolValue();
            case EQ -> eqMatch(actual, predicate.scalar());
            case NE -> !eqMatch(actual, predicate.scalar());
            case IN -> inMatch(actual, predicate.set());
            case NIN -> !inMatch(actual, predicate.set());
            case GT, GTE, LT, LTE -> compareMatch(actual, predicate.op(), predicate.scalar());
        };
    }

    private static boolean eqMatch(final RailixValue actual, final RailixValue expected) {
        if (expected instanceof RailixValue.NullValue) {
            return actual == null || actual instanceof RailixValue.NullValue;
        }
        if (actual == null) {
            return false;
        }
        return canonicalValueEquals(actual, expected);
    }

    private static boolean inMatch(final RailixValue actual, final Set<RailixValue> expectedSet) {
        if (actual == null || actual instanceof RailixValue.NullValue) {
            return expectedSet.contains(RailixValue.nullValue());
        }
        return expectedSet.contains(DatabaseKey.canonicalValue(actual));
    }

    private static boolean compareMatch(
            final RailixValue actual,
            final FilterOp op,
            final RailixValue expected
    ) {
        if (actual == null) {
            return false;
        }
        final int cmp;
        if (expected instanceof RailixValue.NumberValue expectedNum) {
            if (!(actual instanceof RailixValue.NumberValue actualNum)) {
                return false;
            }
            cmp = actualNum.value().compareTo(expectedNum.value());
        } else if (expected instanceof RailixValue.StringValue expectedStr) {
            if (!(actual instanceof RailixValue.StringValue actualStr)) {
                return false;
            }
            cmp = actualStr.value().compareTo(expectedStr.value());
        } else {
            return false;
        }
        return switch (op) {
            case GT -> cmp > 0;
            case GTE -> cmp >= 0;
            case LT -> cmp < 0;
            case LTE -> cmp <= 0;
            default -> false;
        };
    }

    private static boolean canonicalValueEquals(final RailixValue actual, final RailixValue expectedCanonical) {
        if (expectedCanonical instanceof RailixValue.NumberValue expectedNum) {
            return actual instanceof RailixValue.NumberValue actualNum
                    && actualNum.value().compareTo(expectedNum.value()) == 0;
        }
        if (expectedCanonical instanceof RailixValue.StringValue
                || expectedCanonical instanceof RailixValue.BooleanValue) {
            return actual.equals(expectedCanonical);
        }
        return DatabaseKey.canonicalValue(actual).equals(expectedCanonical);
    }

    static RailixValue.ObjectValue project(
            final RailixValue.ObjectValue document,
            final String keyField,
            final List<String> fields
    ) {
        if (fields.isEmpty()) {
            return document;
        }
        final Map<String, RailixValue> projected = new TreeMap<>();
        final RailixValue key = document.values().get(keyField);
        if (key != null) {
            projected.put(keyField, key);
        }
        for (final String field : fields) {
            final RailixValue value = document.values().get(field);
            if (value != null) {
                projected.put(field, value);
            }
        }
        return RailixValue.object(projected);
    }

    static RailixValue.ObjectValue readView(
            final RailixValue.ObjectValue document,
            final String keyField,
            final List<String> fields,
            final RailixValue.ObjectValue defaults
    ) {
        final RailixValue.ObjectValue projected = project(document, keyField, fields);
        if (defaults.values().isEmpty()) {
            return projected;
        }
        final Map<String, RailixValue> filled = new TreeMap<>(projected.values());
        for (final Map.Entry<String, RailixValue> entry : defaults.values().entrySet()) {
            final RailixValue current = document.values().get(entry.getKey());
            if (current == null || current instanceof RailixValue.NullValue) {
                filled.put(entry.getKey(), DatabaseKey.canonicalValue(entry.getValue()));
            } else {
                filled.put(entry.getKey(), current);
            }
        }
        return RailixValue.object(filled);
    }
}
