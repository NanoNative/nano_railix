package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.model.Selector;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryContextDocTest {

    @Test
    void shouldSeedEnvelopeNamespacesAndReturnNullForMissingPaths() {
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope());

        assertThat(context.root()).isNotNull();
        assertThat(context.get(RailixPath.parse("payload.customer.email")))
                .isEqualTo(new RailixValue.StringValue("USER@EXAMPLE.COM"));
        assertThat(context.get(RailixPath.parse("ctx.customer.email"))).isSameAs(RailixValue.NULL);
        assertThat(context.get(RailixPath.parse("payload.customer.email[0]"))).isSameAs(RailixValue.NULL);
        assertThat(context.get(RailixPath.parse("payload.orders[9]"))).isSameAs(RailixValue.NULL);
        assertThat(context.select(new Selector("payload.orders[*].items[*].sku"))).containsExactly(
                new RailixValue.StringValue("A"),
                new RailixValue.StringValue("B")
        );
    }

    @Test
    void shouldExposeSelectedConcretePathsInSelectorOrder() {
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope());

        assertThat(context.selectedPaths(new Selector("payload.orders[*].items[*]"))).containsExactly(
                RailixPath.parse("payload.orders[0].items[0]"),
                RailixPath.parse("payload.orders[0].items[1]")
        );
    }

    @Test
    void shouldApplyPatchOperationsImmutably() {
        final InMemoryContextDoc base = InMemoryContextDoc.fromEnvelope(envelope());

        final InMemoryContextDoc updated = (InMemoryContextDoc) base.applyAll(List.of(
                new Patch.Set(
                        RailixPath.parse("ctx.customer.email"),
                        new Patch.ExpressionSource(new Patch.PathExpression(RailixPath.parse("payload.customer.email")))
                ),
                new Patch.Append(
                        RailixPath.parse("ctx.tags"),
                        new Patch.LiteralSource(new RailixValue.StringValue("vip"))
                ),
                new Patch.Append(
                        RailixPath.parse("ctx.tags"),
                        new Patch.ExpressionSource(new Patch.LiteralExpression(new RailixValue.StringValue("priority")))
                ),
                new Patch.Merge(
                        RailixPath.parse("ctx.customer.email"),
                        new Patch.LiteralSource(new RailixValue.StringValue("ignored@example.com")),
                        Patch.Strategy.KEEP_EXISTING
                ),
                new Patch.Merge(
                        RailixPath.parse("reply.metadata"),
                        new Patch.LiteralSource(new RailixValue.ObjectValue(Map.of(
                                "contentType", new RailixValue.StringValue("application/json")
                        ))),
                        Patch.Strategy.DEEP_MERGE
                ),
                new Patch.Set(
                        RailixPath.parse("reply.mode"),
                        new Patch.LiteralSource(new RailixValue.StringValue("immediate"))
                ),
                new Patch.Set(
                        RailixPath.parse("reply.status"),
                        new Patch.LiteralSource(new RailixValue.NumberValue(BigDecimal.valueOf(201)))
                ),
                new Patch.Copy(
                        RailixPath.parse("ctx.customer.email"),
                        RailixPath.parse("reply.payload.customerEmail")
                )
        ));

        assertThat(base.get(RailixPath.parse("ctx.customer.email"))).isSameAs(RailixValue.NULL);
        assertThat(updated.get(RailixPath.parse("ctx.customer.email")))
                .isEqualTo(new RailixValue.StringValue("USER@EXAMPLE.COM"));
        assertThat(updated.get(RailixPath.parse("ctx.tags")))
                .isEqualTo(new RailixValue.ListValue(List.of(
                        new RailixValue.StringValue("vip"),
                        new RailixValue.StringValue("priority")
                )));
        assertThat(updated.reply().mode()).isEqualTo(Reply.Mode.IMMEDIATE);
        assertThat(updated.reply().status()).isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(201)));
        assertThat(updated.reply().payload()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "customerEmail", new RailixValue.StringValue("USER@EXAMPLE.COM")
        )));
    }

    @Test
    void shouldRemoveMoveClearAndDiffChangedPaths() {
        final InMemoryContextDoc base = InMemoryContextDoc.fromEnvelope(envelope());
        final InMemoryContextDoc updated = (InMemoryContextDoc) base.applyAll(List.of(
                new Patch.Set(
                        RailixPath.parse("ctx.items"),
                        new Patch.LiteralSource(new RailixValue.ListValue(List.of(
                                new RailixValue.StringValue("A"),
                                new RailixValue.StringValue("B")
                        )))
                ),
                new Patch.Move(
                        RailixPath.parse("payload.customer.email"),
                        RailixPath.parse("ctx.originalEmail")
                ),
                new Patch.Merge(
                        RailixPath.parse("ctx.state"),
                        new Patch.LiteralSource(new RailixValue.StringValue("ready")),
                        Patch.Strategy.REPLACE
                ),
                new Patch.Remove(RailixPath.parse("ctx.items[0]")),
                new Patch.Clear(RailixPath.parse("ctx.items"))
        ));

        final ContextDoc.ContextDiff diff = base.diff(updated);

        assertThat(updated.get(RailixPath.parse("payload.customer.email"))).isSameAs(RailixValue.NULL);
        assertThat(updated.get(RailixPath.parse("ctx.originalEmail")))
                .isEqualTo(new RailixValue.StringValue("USER@EXAMPLE.COM"));
        assertThat(updated.get(RailixPath.parse("ctx.items")))
                .isEqualTo(new RailixValue.ListValue(List.of()));
        assertThat(updated.get(RailixPath.parse("ctx.state")))
                .isEqualTo(new RailixValue.StringValue("ready"));
        assertThat(diff.changedPaths()).contains(
                RailixPath.parse("payload.customer.email"),
                RailixPath.parse("ctx.originalEmail"),
                RailixPath.parse("ctx.items")
        );
    }

    @Test
    void shouldRejectUnsupportedOperationExpressionsAndAppendTargets() {
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope());

        assertThatThrownBy(() -> context.apply(new Patch.Set(
                RailixPath.parse("ctx.customer.email"),
                new Patch.ExpressionSource(new Patch.OperationExpression("lower", Map.of()))
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported patch operation expression");

        assertThatThrownBy(() -> context.apply(new Patch.Append(
                RailixPath.parse("payload.customer.email"),
                new Patch.LiteralSource(new RailixValue.StringValue("vip"))
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("append target must be a list");
    }

    @Test
    void shouldLeaveBaseUntouchedWhenApplyAllFailsMidBatch() {
        final InMemoryContextDoc base = InMemoryContextDoc.fromEnvelope(envelope());

        assertThatThrownBy(() -> base.applyAll(List.of(
                new Patch.Set(
                        RailixPath.parse("ctx.customer.email"),
                        new Patch.LiteralSource(new RailixValue.StringValue("user@example.com"))
                ),
                new Patch.Append(
                        RailixPath.parse("payload.customer.email"),
                        new Patch.LiteralSource(new RailixValue.StringValue("vip"))
                )
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("append target must be a list");

        assertThat(base.get(RailixPath.parse("ctx.customer.email"))).isSameAs(RailixValue.NULL);
    }

    @Test
    void shouldHandleMissingRemovalsKeepExistingMergesAndInvalidReplyModes() {
        final InMemoryContextDoc base = InMemoryContextDoc.fromEnvelope(envelope());
        final InMemoryContextDoc updated = (InMemoryContextDoc) base.applyAll(List.of(
                new Patch.Remove(RailixPath.parse("ctx.missing.value")),
                new Patch.Remove(RailixPath.parse("payload.orders[4]")),
                new Patch.Set(
                        RailixPath.parse("ctx.customer.email"),
                        new Patch.LiteralSource(new RailixValue.StringValue("existing@example.com"))
                ),
                new Patch.Merge(
                        RailixPath.parse("ctx.customer.email"),
                        new Patch.LiteralSource(new RailixValue.StringValue("ignored@example.com")),
                        Patch.Strategy.KEEP_EXISTING
                ),
                new Patch.Merge(
                        RailixPath.parse("ctx.createdBy"),
                        new Patch.LiteralSource(new RailixValue.StringValue("system")),
                        Patch.Strategy.KEEP_EXISTING
                ),
                new Patch.Clear(RailixPath.parse("ctx.createdBy"))
        ));

        assertThat(updated.get(RailixPath.parse("ctx.customer.email")))
                .isEqualTo(new RailixValue.StringValue("existing@example.com"));
        assertThat(updated.get(RailixPath.parse("ctx.createdBy"))).isSameAs(RailixValue.NULL);
        assertThat(updated.get(RailixPath.parse("payload.orders")))
                .isEqualTo(base.get(RailixPath.parse("payload.orders")));

        final InMemoryContextDoc invalidMode = (InMemoryContextDoc) base.applyAll(List.of(
                new Patch.Set(
                        RailixPath.parse("reply.mode"),
                        new Patch.LiteralSource(new RailixValue.StringValue("side-channel"))
                )
        ));
        final InMemoryContextDoc invalidMetadata = (InMemoryContextDoc) base.applyAll(List.of(
                new Patch.Set(
                        RailixPath.parse("reply.metadata"),
                        new Patch.LiteralSource(new RailixValue.StringValue("wrong"))
                )
        ));
        final InMemoryContextDoc invalidModeType = (InMemoryContextDoc) base.applyAll(List.of(
                new Patch.Set(
                        RailixPath.parse("reply.mode"),
                        new Patch.LiteralSource(new RailixValue.NumberValue(BigDecimal.ONE))
                )
        ));
        final InMemoryContextDoc nullMetadata = (InMemoryContextDoc) base.applyAll(List.of(
                new Patch.Set(
                        RailixPath.parse("reply.mode"),
                        new Patch.LiteralSource(new RailixValue.StringValue("immediate"))
                ),
                new Patch.Set(
                        RailixPath.parse("reply.metadata"),
                        new Patch.LiteralSource(RailixValue.NULL)
                )
        ));

        assertThatThrownBy(invalidMode::reply)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported reply mode");
        assertThatThrownBy(invalidMetadata::reply)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reply.metadata");
        assertThatThrownBy(invalidModeType::reply)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reply.mode");
        assertThat(nullMetadata.reply().metadata()).isEqualTo(new RailixValue.ObjectValue(Map.of()));
    }

    @Test
    void shouldRejectDiffAgainstForeignContextImplementation() {
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope());

        assertThatThrownBy(() -> context.diff(new ContextDoc() {
            @Override
            public RailixValue get(final RailixPath path) {
                return RailixValue.NULL;
            }

            @Override
            public List<RailixValue> select(final Selector selector) {
                return List.of();
            }

            @Override
            public ContextDoc apply(final Patch patch) {
                return this;
            }

            @Override
            public ContextDoc applyAll(final List<Patch> patches) {
                return this;
            }

            @Override
            public ContextDiff diff(final ContextDoc other) {
                return new ContextDiff(List.of());
            }
        })).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("InMemoryContextDoc");
    }

    @Test
    void shouldSupportIndexedSetsNestedRemovalsObjectClearsAndListDiffs() {
        final InMemoryContextDoc base = InMemoryContextDoc.fromEnvelope(envelope());
        final InMemoryContextDoc seeded = (InMemoryContextDoc) base.applyAll(List.of(
                new Patch.Set(
                        RailixPath.parse("ctx.queue[2].name"),
                        new Patch.LiteralSource(new RailixValue.StringValue("queued"))
                ),
                new Patch.Set(
                        RailixPath.parse("ctx.queue[2].status"),
                        new Patch.LiteralSource(new RailixValue.StringValue("ready"))
                ),
                new Patch.Set(
                        RailixPath.parse("ctx.profile"),
                        new Patch.LiteralSource(new RailixValue.ObjectValue(Map.of(
                                "name", new RailixValue.StringValue("Yuna"),
                                "role", new RailixValue.StringValue("operator")
                        )))
                ),
                new Patch.Set(
                        RailixPath.parse("ctx.records"),
                        new Patch.LiteralSource(new RailixValue.ListValue(List.of(
                                new RailixValue.ObjectValue(Map.of(
                                        "flag", new RailixValue.BoolValue(true),
                                        "name", new RailixValue.StringValue("alpha")
                                )),
                                new RailixValue.ObjectValue(Map.of(
                                        "flag", new RailixValue.BoolValue(false),
                                        "name", new RailixValue.StringValue("beta")
                                ))
                        )))
                ),
                new Patch.Set(
                        RailixPath.parse("ctx.scalar"),
                        new Patch.LiteralSource(new RailixValue.StringValue("locked"))
                )
        ));
        final InMemoryContextDoc updated = (InMemoryContextDoc) seeded.applyAll(List.of(
                new Patch.Remove(RailixPath.parse("ctx.records[0].flag")),
                new Patch.Remove(RailixPath.parse("ctx.scalar.value")),
                new Patch.Remove(RailixPath.parse("ctx.scalar[0]")),
                new Patch.Merge(
                        RailixPath.parse("ctx.deep"),
                        new Patch.LiteralSource(new RailixValue.ObjectValue(Map.of(
                                "status", new RailixValue.StringValue("new")
                        ))),
                        Patch.Strategy.DEEP_MERGE
                ),
                new Patch.Clear(RailixPath.parse("ctx.profile"))
        ));

        assertThat(updated.get(RailixPath.parse("ctx.queue"))).isEqualTo(new RailixValue.ListValue(List.of(
                RailixValue.NULL,
                RailixValue.NULL,
                new RailixValue.ObjectValue(Map.of(
                        "name", new RailixValue.StringValue("queued"),
                        "status", new RailixValue.StringValue("ready")
                ))
        )));
        assertThat(updated.get(RailixPath.parse("ctx.records[0]"))).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "name", new RailixValue.StringValue("alpha")
        )));
        assertThat(updated.get(RailixPath.parse("ctx.scalar")))
                .isEqualTo(new RailixValue.StringValue("locked"));
        assertThat(updated.get(RailixPath.parse("ctx.deep"))).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "status", new RailixValue.StringValue("new")
        )));
        assertThat(updated.get(RailixPath.parse("ctx.profile")))
                .isEqualTo(new RailixValue.ObjectValue(Map.of()));

        final InMemoryContextDoc beforeDiff = (InMemoryContextDoc) base.applyAll(List.of(
                new Patch.Set(
                        RailixPath.parse("ctx.list[0].name"),
                        new Patch.LiteralSource(new RailixValue.StringValue("alpha"))
                ),
                new Patch.Set(
                        RailixPath.parse("ctx.list[1]"),
                        new Patch.LiteralSource(new RailixValue.StringValue("one"))
                )
        ));
        final InMemoryContextDoc afterDiff = (InMemoryContextDoc) beforeDiff.applyAll(List.of(
                new Patch.Set(
                        RailixPath.parse("ctx.list[0].name"),
                        new Patch.LiteralSource(new RailixValue.StringValue("beta"))
                ),
                new Patch.Set(
                        RailixPath.parse("ctx.list[1]"),
                        new Patch.LiteralSource(new RailixValue.StringValue("two"))
                ),
                new Patch.Set(
                        RailixPath.parse("ctx.list[2]"),
                        new Patch.LiteralSource(new RailixValue.StringValue("three"))
                )
        ));

        assertThat(beforeDiff.diff(afterDiff).changedPaths()).contains(
                RailixPath.parse("ctx.list[0].name"),
                RailixPath.parse("ctx.list[1]"),
                RailixPath.parse("ctx.list[2]")
        );
    }

    private static Envelope envelope() {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "customer", new RailixValue.ObjectValue(Map.of(
                                "email", new RailixValue.StringValue("USER@EXAMPLE.COM")
                        )),
                        "orders", new RailixValue.ListValue(List.of(
                                new RailixValue.ObjectValue(Map.of(
                                        "items", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of("sku", new RailixValue.StringValue("A"))),
                                                new RailixValue.ObjectValue(Map.of("sku", new RailixValue.StringValue("B")))
                                        ))
                                ))
                        ))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(true, List.of(Reply.Mode.IMMEDIATE))
        );
    }
}
