package dev.nanonative.railix.kernel.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RailixPathTest {

    @Test
    void shouldParseSimpleCanonicalPath() {
        final RailixPath path = RailixPath.parse("payload.orders[0].items[1].sku");

        assertThat(path.tokens()).containsExactly(
                new RailixPath.KeyToken("payload"),
                new RailixPath.KeyToken("orders"),
                new RailixPath.IndexToken(0),
                new RailixPath.KeyToken("items"),
                new RailixPath.IndexToken(1),
                new RailixPath.KeyToken("sku")
        );
        assertThat(path.toString()).isEqualTo("payload.orders[0].items[1].sku");
    }

    @Test
    void shouldRoundTripEscapedKeys() {
        final RailixPath path = RailixPath.parse("payload.customer\\.profile.email\\[work\\]");

        assertThat(path.tokens()).containsExactly(
                new RailixPath.KeyToken("payload"),
                new RailixPath.KeyToken("customer.profile"),
                new RailixPath.KeyToken("email[work]")
        );
        assertThat(path.toString()).isEqualTo("payload.customer\\.profile.email\\[work\\]");
    }

    @Test
    void shouldRejectDanglingEscape() {
        assertThatThrownBy(() -> RailixPath.parse("payload.customer\\"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dangling escape");
    }

    @Test
    void shouldRejectMalformedSeparatorsAndRootIndexes() {
        assertThatThrownBy(() -> RailixPath.parse("payload..customer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid path separator");
        assertThatThrownBy(() -> RailixPath.parse(".payload.customer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path key must not be empty");
        assertThatThrownBy(() -> RailixPath.parse("[0].customer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Path key must not be empty");
        assertThatThrownBy(() -> RailixPath.parse("payload.[0]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid path separator");
    }

    @Test
    void shouldRejectInvalidIndexSyntaxAndMissingSeparators() {
        assertThatThrownBy(() -> RailixPath.parse("payload.orders[].id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index must not be blank");
        assertThatThrownBy(() -> RailixPath.parse("payload.orders[abc].id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("index must be an integer");
        assertThatThrownBy(() -> RailixPath.parse("payload.orders[0]id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Expected '.' or '[' after index");
    }

    @Test
    void shouldRejectIndexFirstConstructorUsage() {
        assertThatThrownBy(() -> new RailixPath(List.of(new RailixPath.IndexToken(0))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root namespace must be a key");
    }

    @Test
    void shouldRejectEmptyConstructorUsage() {
        assertThatThrownBy(() -> new RailixPath(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("root namespace");
    }

    @Test
    void shouldRejectUnexpectedClosingBracketAndTrailingSeparator() {
        assertThatThrownBy(() -> RailixPath.parse("payload.customer]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unexpected ']'");
        assertThatThrownBy(() -> RailixPath.parse("payload.customer."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not end with '.'");
    }

    @Test
    void shouldRejectInvalidTokenConstructors() {
        assertThatThrownBy(() -> new RailixPath.KeyToken(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> new RailixPath.IndexToken(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be >= 0");
    }

    @Test
    void shouldRejectBlankPathAndUnclosedIndex() {
        assertThatThrownBy(() -> RailixPath.parse(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> RailixPath.parse("payload.orders[0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unclosed index");
    }

    @Test
    void shouldRejectNullPathInput() {
        assertThatThrownBy(() -> RailixPath.parse(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value");
    }
}
