package dev.nanonative.railix.kernel.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchTest {

    @Test
    void shouldRepresentNestedExpressionSource() {
        final Map<String, Patch.Expression> trimArguments = new HashMap<>(Map.of(
                "input", new Patch.PathExpression(RailixPath.parse("payload.customer.email"))
        ));
        final Map<String, Patch.Expression> lowerArguments = new HashMap<>(Map.of(
                "input", new Patch.OperationExpression("trim", trimArguments)
        ));

        final Patch.Set patch = new Patch.Set(
                RailixPath.parse("ctx.customer.email"),
                new Patch.ExpressionSource(new Patch.OperationExpression("lower", lowerArguments))
        );

        trimArguments.clear();
        lowerArguments.clear();

        assertThat(patch.source()).isInstanceOf(Patch.ExpressionSource.class);
        final Patch.ExpressionSource expressionSource = (Patch.ExpressionSource) patch.source();
        final Patch.OperationExpression lower = (Patch.OperationExpression) expressionSource.expression();
        assertThat(lower.op()).isEqualTo("lower");
        assertThat(lower.arguments()).containsKey("input");
    }

    @Test
    void shouldRejectBlankOperationName() {
        assertThatThrownBy(() -> new Patch.OperationExpression(" ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("op");
    }

    @Test
    void shouldRepresentAllPatchVariants() {
        final RailixPath sourcePath = RailixPath.parse("payload.customer.email");
        final RailixPath targetPath = RailixPath.parse("ctx.customer.email");
        final Patch.LiteralSource literalSource = new Patch.LiteralSource(new RailixValue.StringValue("user@example.com"));
        final Patch.LiteralExpression literalExpression = new Patch.LiteralExpression(new RailixValue.BoolValue(true));

        final Patch.Remove remove = new Patch.Remove(targetPath);
        final Patch.Append append = new Patch.Append(targetPath, literalSource);
        final Patch.Merge merge = new Patch.Merge(
                RailixPath.parse("ctx.customer"),
                new Patch.ExpressionSource(literalExpression),
                Patch.Strategy.DEEP_MERGE
        );
        final Patch.Copy copy = new Patch.Copy(sourcePath, targetPath);
        final Patch.Move move = new Patch.Move(sourcePath, targetPath);
        final Patch.Clear clear = new Patch.Clear(RailixPath.parse("ctx.customer.tags"));

        assertThat(remove.path()).isEqualTo(targetPath);
        assertThat(append.source()).isEqualTo(literalSource);
        assertThat(merge.strategy()).isEqualTo(Patch.Strategy.DEEP_MERGE);
        assertThat(((Patch.ExpressionSource) merge.source()).expression()).isEqualTo(literalExpression);
        assertThat(copy.from()).isEqualTo(sourcePath);
        assertThat(move.to()).isEqualTo(targetPath);
        assertThat(clear.path()).isEqualTo(RailixPath.parse("ctx.customer.tags"));
    }

    @Test
    void shouldRejectNullCoreFieldsAcrossPatchVariants() {
        assertThatThrownBy(() -> new Patch.Set(null, new Patch.LiteralSource(RailixValue.NULL)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("path");
        assertThatThrownBy(() -> new Patch.Set(RailixPath.parse("ctx.customer.email"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source");
        assertThatThrownBy(() -> new Patch.Remove(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("path");
        assertThatThrownBy(() -> new Patch.Append(RailixPath.parse("ctx.customer.tags"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source");
        assertThatThrownBy(() -> new Patch.Merge(RailixPath.parse("ctx.customer"), new Patch.LiteralSource(RailixValue.NULL), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("strategy");
        assertThatThrownBy(() -> new Patch.Copy(null, RailixPath.parse("ctx.customer.email")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("from");
        assertThatThrownBy(() -> new Patch.Move(RailixPath.parse("payload.customer.email"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("to");
        assertThatThrownBy(() -> new Patch.Clear(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("path");
    }
}
