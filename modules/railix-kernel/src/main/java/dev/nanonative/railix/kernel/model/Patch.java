package dev.nanonative.railix.kernel.model;

import java.util.Map;
import java.util.Objects;

public sealed interface Patch permits Patch.Set, Patch.Remove, Patch.Append, Patch.Merge, Patch.Copy, Patch.Move, Patch.Clear {
    record Set(RailixPath path, Source source) implements Patch {
        public Set {
            path = Objects.requireNonNull(path, "path");
            source = Objects.requireNonNull(source, "source");
        }
    }
    record Remove(RailixPath path) implements Patch {
        public Remove {
            path = Objects.requireNonNull(path, "path");
        }
    }
    record Append(RailixPath path, Source source) implements Patch {
        public Append {
            path = Objects.requireNonNull(path, "path");
            source = Objects.requireNonNull(source, "source");
        }
    }
    record Merge(RailixPath path, Source source, Strategy strategy) implements Patch {
        public Merge {
            path = Objects.requireNonNull(path, "path");
            source = Objects.requireNonNull(source, "source");
            strategy = Objects.requireNonNull(strategy, "strategy");
        }
    }
    record Copy(RailixPath from, RailixPath to) implements Patch {
        public Copy {
            from = Objects.requireNonNull(from, "from");
            to = Objects.requireNonNull(to, "to");
        }
    }
    record Move(RailixPath from, RailixPath to) implements Patch {
        public Move {
            from = Objects.requireNonNull(from, "from");
            to = Objects.requireNonNull(to, "to");
        }
    }
    record Clear(RailixPath path) implements Patch {
        public Clear {
            path = Objects.requireNonNull(path, "path");
        }
    }

    sealed interface Source permits LiteralSource, ExpressionSource {}

    record LiteralSource(RailixValue value) implements Source {
        public LiteralSource {
            value = Objects.requireNonNull(value, "value");
        }
    }

    record ExpressionSource(Expression expression) implements Source {
        public ExpressionSource {
            expression = Objects.requireNonNull(expression, "expression");
        }
    }

    sealed interface Expression permits PathExpression, LiteralExpression, OperationExpression {}

    record PathExpression(RailixPath path) implements Expression {
        public PathExpression {
            path = Objects.requireNonNull(path, "path");
        }
    }

    record LiteralExpression(RailixValue value) implements Expression {
        public LiteralExpression {
            value = Objects.requireNonNull(value, "value");
        }
    }

    record OperationExpression(String op, Map<String, Expression> arguments) implements Expression {
        public OperationExpression {
            Objects.requireNonNull(op, "op");
            if (op.isBlank()) {
                throw new IllegalArgumentException("op must not be blank");
            }
            arguments = Map.copyOf(arguments);
        }
    }

    enum Strategy {
        REPLACE,
        DEEP_MERGE,
        KEEP_EXISTING
    }
}
