package dev.nanonative.railix.kernel.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectorTest {

    @Test
    void shouldSelectAllWildcardMatches() {
        final RailixValue value = new RailixValue.ObjectValue(Map.of(
                "payload", new RailixValue.ObjectValue(Map.of(
                        "orders", new RailixValue.ListValue(List.of(
                                new RailixValue.ObjectValue(Map.of(
                                        "items", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of("sku", new RailixValue.StringValue("A-1"))),
                                                new RailixValue.ObjectValue(Map.of("sku", new RailixValue.StringValue("A-2")))
                                        ))
                                )),
                                new RailixValue.ObjectValue(Map.of(
                                        "items", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of("sku", new RailixValue.StringValue("B-1")))
                                        ))
                                ))
                        ))
                ))
        ));

        final List<RailixPath> paths = new Selector("payload.orders[*].items[*].sku").select(value);

        assertThat(paths).containsExactly(
                RailixPath.parse("payload.orders[0].items[0].sku"),
                RailixPath.parse("payload.orders[0].items[1].sku"),
                RailixPath.parse("payload.orders[1].items[0].sku")
        );
    }

    @Test
    void shouldReturnEmptyWhenSelectorDoesNotMatch() {
        final RailixValue value = new RailixValue.ObjectValue(Map.of(
                "payload", new RailixValue.ObjectValue(Map.of("orders", new RailixValue.ListValue(List.of())))
        ));

        assertThat(new Selector("payload.orders[*].items[*].sku").select(value)).isEmpty();
    }

    @Test
    void shouldSelectIndexedPathMatch() {
        final RailixValue value = new RailixValue.ObjectValue(Map.of(
                "payload", new RailixValue.ObjectValue(Map.of(
                        "orders", new RailixValue.ListValue(List.of(
                                new RailixValue.ObjectValue(Map.of("id", new RailixValue.StringValue("first"))),
                                new RailixValue.ObjectValue(Map.of("id", new RailixValue.StringValue("second")))
                        ))
                ))
        ));

        assertThat(new Selector("payload.orders[1].id").select(value))
                .containsExactly(RailixPath.parse("payload.orders[1].id"));
    }

    @Test
    void shouldReturnEmptyWhenIntermediateValueHasWrongType() {
        final RailixValue value = new RailixValue.ObjectValue(Map.of(
                "payload", new RailixValue.ObjectValue(Map.of(
                        "orders", new RailixValue.ObjectValue(Map.of("id", new RailixValue.StringValue("not-a-list")))
                ))
        ));

        assertThat(new Selector("payload.orders[*].id").select(value)).isEmpty();
    }

    @Test
    void shouldRejectUnclosedListSegment() {
        assertThatThrownBy(() -> new Selector("payload.orders[1").select(RailixValue.NULL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unclosed selector segment");
    }

    @Test
    void shouldRejectDanglingEscape() {
        assertThatThrownBy(() -> new Selector("payload.customer\\").select(RailixValue.NULL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dangling escape");
    }

    @Test
    void shouldRejectNegativeIndex() {
        assertThatThrownBy(() -> new Selector("payload.orders[-1]").select(RailixValue.NULL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Selector index must be >= 0");
    }

    @Test
    void shouldSelectEscapedKeySegments() {
        final RailixValue value = new RailixValue.ObjectValue(Map.of(
                "payload", new RailixValue.ObjectValue(Map.of(
                        "customer.name", new RailixValue.ObjectValue(Map.of(
                                "id", new RailixValue.StringValue("cust-1")
                        ))
                ))
        ));

        assertThat(new Selector("payload.customer\\.name.id").select(value))
                .containsExactly(RailixPath.parse("payload.customer\\.name.id"));
    }

    @Test
    void shouldRejectSelectorWithoutAnyRealSegments() {
        assertThatThrownBy(() -> new Selector(".").select(RailixValue.NULL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one segment");
    }

    @Test
    void shouldRejectEscapedBlankKeySegment() {
        assertThatThrownBy(() -> new Selector("payload.\\ ").select(RailixValue.NULL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Selector key must not be blank");
    }

    @Test
    void shouldReturnEmptyForMissingKeyAndOutOfBoundsIndex() {
        final RailixValue value = new RailixValue.ObjectValue(Map.of(
                "payload", new RailixValue.ObjectValue(Map.of(
                        "orders", new RailixValue.ListValue(List.of(
                                new RailixValue.ObjectValue(Map.of("id", new RailixValue.StringValue("first")))
                        ))
                ))
        ));

        assertThat(new Selector("payload.missing.id").select(value)).isEmpty();
        assertThat(new Selector("payload.orders[9].id").select(value)).isEmpty();
    }
}
