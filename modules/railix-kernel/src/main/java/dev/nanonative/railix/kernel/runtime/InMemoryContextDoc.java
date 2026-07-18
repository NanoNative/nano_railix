package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.model.Selector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class InMemoryContextDoc implements ContextDoc {

    private final RailixValue.ObjectValue root;

    public InMemoryContextDoc(final RailixValue.ObjectValue root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public static InMemoryContextDoc fromEnvelope(final Envelope envelope) {
        return new InMemoryContextDoc(new RailixValue.ObjectValue(Map.of(
                "payload", envelope.payload(),
                "metadata", envelope.metadata(),
                "refs", new RailixValue.ObjectValue(envelope.refs()),
                "ctx", new RailixValue.ObjectValue(Map.of()),
                "reply", new RailixValue.ObjectValue(Map.of(
                        "mode", RailixValue.NULL,
                        "status", RailixValue.NULL,
                        "metadata", new RailixValue.ObjectValue(Map.of()),
                        "payload", RailixValue.NULL,
                        "file", RailixValue.NULL,
                        "stream", RailixValue.NULL,
                        "session", RailixValue.NULL,
                        "deferred", RailixValue.NULL
                ))
        )));
    }

    public RailixValue.ObjectValue root() {
        return root;
    }

    @Override
    public RailixValue get(final RailixPath path) {
        return getValue(root, path.tokens(), 0);
    }

    @Override
    public List<RailixValue> select(final Selector selector) {
        final List<RailixValue> values = new ArrayList<>();
        for (final RailixPath path : selector.select(root)) {
            values.add(get(path));
        }
        return List.copyOf(values);
    }

    @Override
    public List<RailixPath> selectedPaths(final Selector selector) {
        return selector.select(root);
    }

    @Override
    public ContextDoc apply(final Patch patch) {
        return new InMemoryContextDoc((RailixValue.ObjectValue) applyPatch(root, patch));
    }

    @Override
    public ContextDoc applyAll(final List<Patch> patches) {
        ContextDoc current = this;
        for (final Patch patch : patches) {
            current = current.apply(patch);
        }
        return current;
    }

    @Override
    public ContextDiff diff(final ContextDoc other) {
        if (!(other instanceof InMemoryContextDoc otherDoc)) {
            throw new IllegalArgumentException("other must be InMemoryContextDoc");
        }
        final List<RailixPath> changedPaths = new ArrayList<>();
        diffValues(root, otherDoc.root, new ArrayList<>(), changedPaths);
        return new ContextDiff(changedPaths);
    }

    public Reply reply() {
        final Reply.Mode mode = parseReplyMode(get(RailixPath.parse("reply.mode")));
        final RailixValue.ObjectValue metadata = requireObjectOrNull(
                get(RailixPath.parse("reply.metadata")),
                "reply.metadata"
        );
        return new Reply(
                mode,
                get(RailixPath.parse("reply.status")),
                metadata,
                get(RailixPath.parse("reply.payload")),
                get(RailixPath.parse("reply.file")),
                get(RailixPath.parse("reply.stream")),
                get(RailixPath.parse("reply.session")),
                get(RailixPath.parse("reply.deferred"))
        );
    }

    private static Reply.Mode parseReplyMode(final RailixValue modeValue) {
        if (modeValue == RailixValue.NULL) {
            return Reply.Mode.NONE;
        }
        if (!(modeValue instanceof RailixValue.StringValue stringValue)) {
            throw new IllegalArgumentException("reply.mode must be a string or null");
        }
        try {
            return Reply.Mode.valueOf(stringValue.value().toUpperCase());
        } catch (final IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported reply mode: " + stringValue.value(), exception);
        }
    }

    private static RailixValue.ObjectValue requireObjectOrNull(final RailixValue value, final String path) {
        if (value == RailixValue.NULL) {
            return new RailixValue.ObjectValue(Map.of());
        }
        if (value instanceof RailixValue.ObjectValue objectValue) {
            return objectValue;
        }
        throw new IllegalArgumentException(path + " must be an object or null");
    }

    private static RailixValue applyPatch(final RailixValue.ObjectValue document, final Patch patch) {
        return switch (patch) {
            case Patch.Set set -> setValue(document, set.path().tokens(), 0, evaluateSource(document, set.source()));
            case Patch.Remove remove -> removeValue(document, remove.path().tokens(), 0);
            case Patch.Append append -> appendValue(document, append.path(), evaluateSource(document, append.source()));
            case Patch.Merge merge -> mergeValue(document, merge.path(), evaluateSource(document, merge.source()), merge.strategy());
            case Patch.Copy copy -> setValue(document, copy.to().tokens(), 0, getValue(document, copy.from().tokens(), 0));
            case Patch.Move move -> removeValue(
                    (RailixValue.ObjectValue) setValue(document, move.to().tokens(), 0, getValue(document, move.from().tokens(), 0)),
                    move.from().tokens(),
                    0
            );
            case Patch.Clear clear -> clearValue(document, clear.path());
        };
    }

    private static RailixValue evaluateSource(final RailixValue.ObjectValue document, final Patch.Source source) {
        return switch (source) {
            case Patch.LiteralSource literalSource -> literalSource.value();
            case Patch.ExpressionSource expressionSource -> evaluateExpression(document, expressionSource.expression());
        };
    }

    private static RailixValue evaluateExpression(final RailixValue.ObjectValue document, final Patch.Expression expression) {
        return switch (expression) {
            case Patch.PathExpression pathExpression -> getValue(document, pathExpression.path().tokens(), 0);
            case Patch.LiteralExpression literalExpression -> literalExpression.value();
            case Patch.OperationExpression operationExpression ->
                    throw new IllegalArgumentException("Unsupported patch operation expression: " + operationExpression.op());
        };
    }

    private static RailixValue getValue(final RailixValue current, final List<RailixPath.Token> tokens, final int index) {
        if (index == tokens.size()) {
            return current;
        }
        final RailixPath.Token token = tokens.get(index);
        if (token instanceof RailixPath.KeyToken keyToken) {
            if (!(current instanceof RailixValue.ObjectValue objectValue)) {
                return RailixValue.NULL;
            }
            return getValue(objectValue.values().getOrDefault(keyToken.key(), RailixValue.NULL), tokens, index + 1);
        }
        if (!(current instanceof RailixValue.ListValue listValue)) {
            return RailixValue.NULL;
        }
        final int tokenIndex = ((RailixPath.IndexToken) token).index();
        if (tokenIndex >= listValue.values().size()) {
            return RailixValue.NULL;
        }
        return getValue(listValue.values().get(tokenIndex), tokens, index + 1);
    }

    private static RailixValue setValue(final RailixValue current, final List<RailixPath.Token> tokens, final int index, final RailixValue value) {
        final RailixPath.Token token = tokens.get(index);
        if (token instanceof RailixPath.KeyToken keyToken) {
            final Map<String, RailixValue> values = new LinkedHashMap<>(asObject(current, keyToken.key()).values());
            if (index == tokens.size() - 1) {
                values.put(keyToken.key(), value);
                return new RailixValue.ObjectValue(values);
            }
            values.put(keyToken.key(), setValue(values.getOrDefault(keyToken.key(), RailixValue.NULL), tokens, index + 1, value));
            return new RailixValue.ObjectValue(values);
        }
        final int tokenIndex = ((RailixPath.IndexToken) token).index();
        final List<RailixValue> values = new ArrayList<>(asList(current, tokenIndex).values());
        while (values.size() <= tokenIndex) {
            values.add(RailixValue.NULL);
        }
        if (index == tokens.size() - 1) {
            values.set(tokenIndex, value);
            return new RailixValue.ListValue(values);
        }
        values.set(tokenIndex, setValue(values.get(tokenIndex), tokens, index + 1, value));
        return new RailixValue.ListValue(values);
    }

    private static RailixValue removeValue(final RailixValue current, final List<RailixPath.Token> tokens, final int index) {
        final RailixPath.Token token = tokens.get(index);
        if (token instanceof RailixPath.KeyToken keyToken) {
            if (!(current instanceof RailixValue.ObjectValue objectValue)) {
                return current;
            }
            final Map<String, RailixValue> values = new LinkedHashMap<>(objectValue.values());
            if (index == tokens.size() - 1) {
                values.remove(keyToken.key());
                return new RailixValue.ObjectValue(values);
            }
            final RailixValue nested = values.get(keyToken.key());
            if (nested == null) {
                return current;
            }
            values.put(keyToken.key(), removeValue(nested, tokens, index + 1));
            return new RailixValue.ObjectValue(values);
        }
        if (!(current instanceof RailixValue.ListValue listValue)) {
            return current;
        }
        final int tokenIndex = ((RailixPath.IndexToken) token).index();
        if (tokenIndex >= listValue.values().size()) {
            return current;
        }
        final List<RailixValue> values = new ArrayList<>(listValue.values());
        if (index == tokens.size() - 1) {
            values.remove(tokenIndex);
            return new RailixValue.ListValue(values);
        }
        values.set(tokenIndex, removeValue(values.get(tokenIndex), tokens, index + 1));
        return new RailixValue.ListValue(values);
    }

    private static RailixValue appendValue(final RailixValue.ObjectValue document, final RailixPath path, final RailixValue value) {
        final RailixValue current = getValue(document, path.tokens(), 0);
        if (current == RailixValue.NULL) {
            return setValue(document, path.tokens(), 0, new RailixValue.ListValue(List.of(value)));
        }
        if (!(current instanceof RailixValue.ListValue listValue)) {
            throw new IllegalArgumentException("append target must be a list: " + path);
        }
        final List<RailixValue> values = new ArrayList<>(listValue.values());
        values.add(value);
        return setValue(document, path.tokens(), 0, new RailixValue.ListValue(values));
    }

    private static RailixValue mergeValue(
            final RailixValue.ObjectValue document,
            final RailixPath path,
            final RailixValue value,
            final Patch.Strategy strategy
    ) {
        final RailixValue current = getValue(document, path.tokens(), 0);
        return switch (strategy) {
            case REPLACE -> setValue(document, path.tokens(), 0, value);
            case KEEP_EXISTING -> current == RailixValue.NULL ? setValue(document, path.tokens(), 0, value) : document;
            case DEEP_MERGE -> setValue(document, path.tokens(), 0, deepMerge(current, value));
        };
    }

    private static RailixValue deepMerge(final RailixValue base, final RailixValue update) {
        if (base == RailixValue.NULL) {
            return update;
        }
        if (base instanceof RailixValue.ObjectValue baseObject && update instanceof RailixValue.ObjectValue updateObject) {
            final Map<String, RailixValue> merged = new LinkedHashMap<>(baseObject.values());
            for (final Map.Entry<String, RailixValue> entry : updateObject.values().entrySet()) {
                merged.merge(entry.getKey(), entry.getValue(), InMemoryContextDoc::deepMerge);
            }
            return new RailixValue.ObjectValue(merged);
        }
        return update;
    }

    private static RailixValue clearValue(final RailixValue.ObjectValue document, final RailixPath path) {
        final RailixValue current = getValue(document, path.tokens(), 0);
        if (current instanceof RailixValue.ObjectValue) {
            return setValue(document, path.tokens(), 0, new RailixValue.ObjectValue(Map.of()));
        }
        if (current instanceof RailixValue.ListValue) {
            return setValue(document, path.tokens(), 0, new RailixValue.ListValue(List.of()));
        }
        return setValue(document, path.tokens(), 0, RailixValue.NULL);
    }

    private static RailixValue.ObjectValue asObject(final RailixValue value, final String path) {
        if (value == RailixValue.NULL) {
            return new RailixValue.ObjectValue(Map.of());
        }
        if (value instanceof RailixValue.ObjectValue objectValue) {
            return objectValue;
        }
        throw new IllegalArgumentException("Expected object at " + path);
    }

    private static RailixValue.ListValue asList(final RailixValue value, final int index) {
        if (value == RailixValue.NULL) {
            return new RailixValue.ListValue(List.of());
        }
        if (value instanceof RailixValue.ListValue listValue) {
            return listValue;
        }
        throw new IllegalArgumentException("Expected list at index " + index);
    }

    private static void diffValues(
            final RailixValue before,
            final RailixValue after,
            final List<RailixPath.Token> path,
            final List<RailixPath> changedPaths
    ) {
        if (Objects.equals(before, after)) {
            return;
        }
        if (before instanceof RailixValue.ObjectValue beforeObject && after instanceof RailixValue.ObjectValue afterObject) {
            final Map<String, RailixValue> all = new LinkedHashMap<>(beforeObject.values());
            all.putAll(afterObject.values());
            for (final String key : all.keySet()) {
                final List<RailixPath.Token> nextPath = new ArrayList<>(path);
                nextPath.add(new RailixPath.KeyToken(key));
                diffValues(beforeObject.values().getOrDefault(key, RailixValue.NULL), afterObject.values().getOrDefault(key, RailixValue.NULL), nextPath, changedPaths);
            }
            return;
        }
        if (before instanceof RailixValue.ListValue beforeList && after instanceof RailixValue.ListValue afterList) {
            final int size = Math.max(beforeList.values().size(), afterList.values().size());
            for (int index = 0; index < size; index++) {
                final List<RailixPath.Token> nextPath = new ArrayList<>(path);
                nextPath.add(new RailixPath.IndexToken(index));
                diffValues(
                        index < beforeList.values().size() ? beforeList.values().get(index) : RailixValue.NULL,
                        index < afterList.values().size() ? afterList.values().get(index) : RailixValue.NULL,
                        nextPath,
                        changedPaths
                );
            }
            return;
        }
        changedPaths.add(new RailixPath(path));
    }
}
