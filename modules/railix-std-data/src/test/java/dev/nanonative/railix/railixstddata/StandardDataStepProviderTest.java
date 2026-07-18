package dev.nanonative.railix.railixstddata;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.Selector;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.runtime.ContextDoc;
import dev.nanonative.railix.kernel.runtime.InMemoryContextDoc;
import dev.nanonative.railix.kernel.runtime.Step;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StandardDataStepProviderTest {

    @Test
    void shouldResolveDataTransformStepWithSequentialMappings() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();
        final Envelope envelope = envelope();
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve("railix.std.data.DataTransform").orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(envelope, context, dataTransformConfig()));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.user.email")))
                .isEqualTo(new RailixValue.StringValue("user@example.com"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.user.name")))
                .isEqualTo(new RailixValue.StringValue("World"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.lineTotal")))
                .isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(10)));
        assertThat(updatedContext.reply().mode()).isEqualTo(dev.nanonative.railix.kernel.model.Reply.Mode.IMMEDIATE);
        assertThat(updatedContext.reply().payload()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "message", new RailixValue.StringValue("Hello World")
        )));
        assertThat(updatedContext.reply().metadata()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "normalizedEmail", new RailixValue.StringValue("user@example.com")
        )));
    }

    @Test
    void shouldWorkThroughContextDocAndRejectNonReplyOrContextTargets() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();
        final Envelope envelope = envelope();
        final ContextDoc context = new DelegatingContextDoc(InMemoryContextDoc.fromEnvelope(envelope));
        final Step step = provider.resolve("railix.std.data.DataTransform").orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(envelope, context, dataTransformConfig()));

        assertThat(result.outcome()).isEqualTo("ok");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope, context, invalidTargetConfig())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targets must start with ctx or reply");
    }

    @Test
    void shouldResolveCapturePayloadStep() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();
        final Envelope envelope = envelope();
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve("std.data.capture-payload").orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(envelope, context));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.payload"))).isEqualTo(envelope.payload());
    }

    @Test
    void shouldResolveDataValidateStepForRequiredPathRules() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();
        final Envelope envelope = envelope();
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve("railix.std.data.DataValidate").orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(envelope, context, validationConfig()));

        assertThat(result.outcome()).isEqualTo("valid");
        assertThat(result.patches()).isEmpty();
    }

    @Test
    void shouldReturnInvalidForMissingRequiredValidationRules() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();
        final Envelope envelope = invalidValidationEnvelope();
        final ContextDoc context = new DelegatingContextDoc(InMemoryContextDoc.fromEnvelope(envelope));
        final Step step = provider.resolve("railix.std.data.DataValidate").orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(envelope, context, validationConfig()));

        assertThat(result.outcome()).isEqualTo("invalid");
        assertThat(result.patches()).isEmpty();
    }

    @Test
    void shouldRejectMalformedValidationRules() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();
        final Envelope envelope = envelope();
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve("railix.std.data.DataValidate").orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope, context, invalidValidationConfigNotAList())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config.required must be a list");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope, context, invalidValidationConfigWithNonStringPath())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config.required[0] must be a string");
    }

    @Test
    void shouldResolveDataRouteStepWithOrderedRoutes() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();
        final Envelope envelope = envelope();
        final ContextDoc context = new DelegatingContextDoc((InMemoryContextDoc) InMemoryContextDoc.fromEnvelope(envelope).apply(new Patch.Set(
                RailixPath.parse("ctx.order.total"),
                new Patch.LiteralSource(new RailixValue.NumberValue(BigDecimal.valueOf(1500)))
        )));
        final Step step = provider.resolve("railix.std.data.DataRoute").orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(envelope, context, routeConfig()));

        assertThat(result.outcome()).isEqualTo("approval");
        assertThat(result.patches()).isEmpty();
    }

    @Test
    void shouldResolveFallbackRouteAndRejectMalformedRouteConfig() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();
        final Envelope envelope = envelope();
        final InMemoryContextDoc fallbackContext = (InMemoryContextDoc) InMemoryContextDoc.fromEnvelope(envelope).apply(new Patch.Set(
                RailixPath.parse("ctx.order.total"),
                new Patch.LiteralSource(new RailixValue.NumberValue(BigDecimal.valueOf(100)))
        ));
        final Step step = provider.resolve("railix.std.data.DataRoute").orElseThrow();

        assertThat(step.execute(new Step.ExecutionInput(envelope, fallbackContext, routeConfig())).outcome())
                .isEqualTo("auto");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope, fallbackContext, invalidRouteConfigNotAList())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config.routes must be a list");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope, fallbackContext, invalidRouteConfigMissingOutcome())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config.routes[0].outcome");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope, fallbackContext, invalidRouteConfigUnsupportedOperator())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported data route operator");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope, fallbackContext, invalidRouteConfigWithoutMatch())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("did not match any configured route");
    }

    @Test
    void shouldResolveDataForEachStepWithCursorStateAndLoopOutcomes() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();
        final Envelope envelope = envelope();
        final Step step = provider.resolve("railix.std.data.DataForEach").orElseThrow();
        final InMemoryContextDoc initialContext = InMemoryContextDoc.fromEnvelope(envelope);

        final Step.Result first = step.execute(new Step.ExecutionInput(envelope, initialContext, forEachConfig()));
        final InMemoryContextDoc firstContext = (InMemoryContextDoc) initialContext.applyAll(first.patches());
        final Step.Result second = step.execute(new Step.ExecutionInput(envelope, firstContext, forEachConfig()));
        final InMemoryContextDoc secondContext = (InMemoryContextDoc) firstContext.applyAll(second.patches());
        final Step.Result third = step.execute(new Step.ExecutionInput(envelope, secondContext, forEachConfig()));
        final InMemoryContextDoc thirdContext = (InMemoryContextDoc) secondContext.applyAll(third.patches());
        final Step.Result done = step.execute(new Step.ExecutionInput(envelope, thirdContext, forEachConfig()));
        final InMemoryContextDoc doneContext = (InMemoryContextDoc) thirdContext.applyAll(done.patches());

        assertThat(first.outcome()).isEqualTo("item");
        assertThat(firstContext.get(RailixPath.parse("ctx.foreach.item.sku")))
                .isEqualTo(new RailixValue.StringValue("A"));
        assertThat(firstContext.get(RailixPath.parse("ctx.foreach.order.id")))
                .isEqualTo(new RailixValue.StringValue("order-1"));
        assertThat(firstContext.get(RailixPath.parse("ctx.foreach._nextIndex")))
                .isEqualTo(new RailixValue.NumberValue(BigDecimal.ONE));

        assertThat(second.outcome()).isEqualTo("item");
        assertThat(secondContext.get(RailixPath.parse("ctx.foreach.item.sku")))
                .isEqualTo(new RailixValue.StringValue("B"));
        assertThat(secondContext.get(RailixPath.parse("ctx.foreach.order.id")))
                .isEqualTo(new RailixValue.StringValue("order-1"));
        assertThat(secondContext.get(RailixPath.parse("ctx.foreach._nextIndex")))
                .isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(2)));

        assertThat(third.outcome()).isEqualTo("item");
        assertThat(thirdContext.get(RailixPath.parse("ctx.foreach.item.sku")))
                .isEqualTo(new RailixValue.StringValue("C"));
        assertThat(thirdContext.get(RailixPath.parse("ctx.foreach.order.id")))
                .isEqualTo(new RailixValue.StringValue("order-2"));
        assertThat(thirdContext.get(RailixPath.parse("ctx.foreach._nextIndex")))
                .isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(3)));

        assertThat(done.outcome()).isEqualTo("done");
        assertThat(doneContext.get(RailixPath.parse("ctx.foreach"))).isEqualTo(RailixValue.NULL);
    }

    @Test
    void shouldReturnEmptyForEachOutcomeAndRejectMalformedForEachConfig() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();
        final Step step = provider.resolve("railix.std.data.DataForEach").orElseThrow();
        final Envelope emptyEnvelope = invalidValidationEnvelope();
        final InMemoryContextDoc emptyContext = InMemoryContextDoc.fromEnvelope(emptyEnvelope);
        final Step.Result empty = step.execute(new Step.ExecutionInput(emptyEnvelope, emptyContext, forEachConfig()));
        final InMemoryContextDoc clearedContext = (InMemoryContextDoc) emptyContext.applyAll(empty.patches());

        assertThat(empty.outcome()).isEqualTo("empty");
        assertThat(clearedContext.get(RailixPath.parse("ctx.foreach"))).isEqualTo(RailixValue.NULL);
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope(), InMemoryContextDoc.fromEnvelope(envelope()), invalidForEachConfigReservedAlias())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config.as must not reuse a root namespace");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope(), InMemoryContextDoc.fromEnvelope(envelope()), invalidForEachConfigReservedCursorAlias())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config.as must not reuse a foreach cursor field");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope(), InMemoryContextDoc.fromEnvelope(envelope()), invalidForEachConfigInvalidStatePath())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config.statePath must start with ctx");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope(),
                InMemoryContextDoc.fromEnvelope(envelope()),
                forEachConfigWithStatePath("ctx")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config.statePath must resolve to an empty or foreach-owned object subtree");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope(),
                (InMemoryContextDoc) InMemoryContextDoc.fromEnvelope(envelope()).apply(new Patch.Set(
                        RailixPath.parse("ctx.cursor"),
                        new Patch.LiteralSource(object(Map.of(
                                "live", string("value")
                        )))
                )),
                forEachConfigWithStatePath("ctx.cursor")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config.statePath must resolve to an empty or foreach-owned object subtree");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope(),
                InMemoryContextDoc.fromEnvelope(envelope()),
                invalidForEachConfigReservedParentAlias()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config.parentAliases._selector must not reuse a foreach cursor field");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope(),
                (InMemoryContextDoc) InMemoryContextDoc.fromEnvelope(envelope()).applyAll(List.of(
                        new Patch.Set(
                                RailixPath.parse("ctx.foreach._selector"),
                                new Patch.LiteralSource(string("payload.orders[*].items[*]"))
                        ),
                        new Patch.Set(
                                RailixPath.parse("ctx.foreach._alias"),
                                new Patch.LiteralSource(string("item"))
                        ),
                        new Patch.Set(
                                RailixPath.parse("ctx.foreach._nextIndex"),
                                new Patch.LiteralSource(string("bad-index"))
                        )
                )),
                forEachConfig()
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Existing foreach state index must be numeric");
    }

    @Test
    void shouldSupportCustomForEachStatePath() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();
        final Envelope envelope = envelope();
        final Step step = provider.resolve("railix.std.data.DataForEach").orElseThrow();
        final InMemoryContextDoc initialContext = InMemoryContextDoc.fromEnvelope(envelope);

        final Step.Result first = step.execute(new Step.ExecutionInput(envelope, initialContext, forEachConfigWithStatePath("ctx.loopState")));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) initialContext.applyAll(first.patches());

        assertThat(first.outcome()).isEqualTo("item");
        assertThat(updatedContext.get(RailixPath.parse("ctx.loopState.item.sku")))
                .isEqualTo(new RailixValue.StringValue("A"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.loopState.order.id")))
                .isEqualTo(new RailixValue.StringValue("order-1"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.loopState._nextIndex")))
                .isEqualTo(new RailixValue.NumberValue(BigDecimal.ONE));
    }

    @Test
    void shouldResolveDataAggregateStepWithCountSumMinMaxCollectOutputs() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();
        final Envelope envelope = envelope();
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve("railix.std.data.DataAggregate").orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(envelope, context, aggregateConfig()));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.summary.itemCount")))
                .isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(3)));
        assertThat(updatedContext.get(RailixPath.parse("ctx.summary.totalQty")))
                .isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(6)));
        assertThat(updatedContext.get(RailixPath.parse("ctx.summary.minPrice")))
                .isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(2.5)));
        assertThat(updatedContext.get(RailixPath.parse("ctx.summary.maxPrice")))
                .isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(4)));
        assertThat(updatedContext.get(RailixPath.parse("ctx.summary.skus")))
                .isEqualTo(new RailixValue.ListValue(List.of(
                        string("A"),
                        string("B"),
                        string("C")
                )));
    }

    @Test
    void shouldReturnEmptyForAggregateSelectorMissAndRejectMalformedAggregateConfig() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();
        final Step step = provider.resolve("railix.std.data.DataAggregate").orElseThrow();
        final Envelope emptyEnvelope = invalidValidationEnvelope();
        final InMemoryContextDoc emptyContext = InMemoryContextDoc.fromEnvelope(emptyEnvelope);

        assertThat(step.execute(new Step.ExecutionInput(emptyEnvelope, emptyContext, aggregateConfig())).outcome())
                .isEqualTo("empty");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope(), InMemoryContextDoc.fromEnvelope(envelope()), invalidAggregateConfigNotAList())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config.aggregations must be a list");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope(), InMemoryContextDoc.fromEnvelope(envelope()), invalidAggregateConfigInvalidTarget())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DataAggregate targets must start with ctx");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope(), InMemoryContextDoc.fromEnvelope(envelope()), invalidAggregateConfigUnsupportedOp())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported data aggregate op");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope(), InMemoryContextDoc.fromEnvelope(envelope()), invalidAggregateConfigNonNumericPath())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config.aggregations[0].path must resolve to numbers");
    }

    @Test
    void shouldReturnEmptyWhenUseIsUnknown() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();

        assertThat(provider.resolve("std.data.missing")).isEmpty();
    }

    @Test
    void shouldResolveRepeatMappingsWithParentAliasesAndWildcardTargets() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();
        final Envelope envelope = envelope();
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve("railix.std.data.DataTransform").orElseThrow();
        final Step.Result result = step.execute(new Step.ExecutionInput(envelope, context, repeatConfig()));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.customer.email")))
                .isEqualTo(new RailixValue.StringValue("user@example.com"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.items"))).isEqualTo(expectedRepeatedItems());
        assertThat(updatedContext.reply().mode()).isEqualTo(dev.nanonative.railix.kernel.model.Reply.Mode.IMMEDIATE);
        assertThat(updatedContext.reply().payload()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "lastSku", new RailixValue.StringValue("C")
        )));
    }

    @Test
    void shouldRejectRepeatTargetsWithoutExactlyOneWildcardAndUnsupportedOperators() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();
        final Envelope envelope = envelope();
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve("railix.std.data.DataTransform").orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope, context, repeatConfigWithoutWildcardTarget())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain exactly one [*]");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope, context, repeatConfigWithMultipleWildcardTargets())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must contain exactly one [*]");
        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(envelope, context, unsupportedOperatorConfig())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported data transform operator");
    }

    @Test
    void shouldResolveCaptureSettingStep() {
        final StandardDataStepProvider provider = new StandardDataStepProvider();
        final Envelope envelope = envelope();
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve("std.data.capture-setting").orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(
                envelope,
                context,
                path -> RailixPath.parse("settings.app.mode").equals(path)
                        ? java.util.Optional.of(new Step.ReadValue(
                        path,
                        new RailixValue.StringValue("packaged-default"),
                        true,
                        false
                ))
                        : java.util.Optional.empty()
        ));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.settings.mode")))
                .isEqualTo(new RailixValue.StringValue("packaged-default"));
        assertThat(result.reply().mode()).isEqualTo(dev.nanonative.railix.kernel.model.Reply.Mode.IMMEDIATE);
        assertThat(result.reply().metadata().values())
                .containsEntry("resolvedMode", new RailixValue.StringValue("packaged-default"));
        assertThat(step.contract().settings().requested()).containsExactly("settings.app.mode");
        assertThat(step.contract().permissions().requested()).containsEntry("settings.read", List.of("settings.app.mode"));
        assertThat(step.contract().permissions().granted()).isEmpty();
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
                                        "id", new RailixValue.StringValue("order-1"),
                                        "items", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of(
                                                        "sku", new RailixValue.StringValue("A"),
                                                        "qty", new RailixValue.StringValue("2"),
                                                        "price", new RailixValue.NumberValue(BigDecimal.valueOf(3.5))
                                                )),
                                                new RailixValue.ObjectValue(Map.of(
                                                        "sku", new RailixValue.StringValue("B"),
                                                        "qty", new RailixValue.NumberValue(BigDecimal.ONE),
                                                        "price", new RailixValue.StringValue("2.5")
                                                ))
                                        ))
                                )),
                                new RailixValue.ObjectValue(Map.of(
                                        "id", new RailixValue.StringValue("order-2"),
                                        "items", new RailixValue.ListValue(List.of(
                                                new RailixValue.ObjectValue(Map.of(
                                                        "sku", new RailixValue.StringValue("C"),
                                                        "qty", new RailixValue.StringValue("3"),
                                                        "price", new RailixValue.NumberValue(BigDecimal.valueOf(4))
                                                ))
                                        ))
                                ))
                        ))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(false, List.of())
        );
    }

    private static Envelope invalidValidationEnvelope() {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "customer", new RailixValue.ObjectValue(Map.of()),
                        "orders", new RailixValue.ListValue(List.of())
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(false, List.of())
        );
    }

    private static RailixValue.ObjectValue dataTransformConfig() {
        return new RailixValue.ObjectValue(Map.of(
                "mappings", new RailixValue.ListValue(List.of(
                        mapping("ctx.user.email", object(Map.of(
                                "op", string("lower"),
                                "input", object(Map.of(
                                        "op", string("trim"),
                                        "input", object(Map.of(
                                                "path", string("payload.customer.email")
                                        ))
                                ))
                        ))),
                        mapping("ctx.user.name", object(Map.of(
                                "op", string("default"),
                                "value", object(Map.of("path", string("payload.customer.name"))),
                                "default", object(Map.of("const", string("World")))
                        ))),
                        mapping("ctx.itemCount", object(Map.of(
                                "op", string("toInt"),
                                "input", object(Map.of("const", string("4")))
                        ))),
                        mapping("ctx.lineTotal", object(Map.of(
                                "op", string("multiply"),
                                "left", object(Map.of("path", string("ctx.itemCount"))),
                                "right", object(Map.of("const", number(2.5)))
                        ))),
                        mapping("reply.mode", object(Map.of("const", string("immediate")))),
                        mapping("reply.metadata.normalizedEmail", object(Map.of("path", string("ctx.user.email")))),
                        mapping("reply.payload.message", object(Map.of(
                                "op", string("template"),
                                "template", string("Hello ${ctx.user.name}")
                        )))
                ))
        ));
    }

    private static RailixValue.ObjectValue validationConfig() {
        return new RailixValue.ObjectValue(Map.of(
                "required", new RailixValue.ListValue(List.of(
                        string("payload.customer.email"),
                        string("payload.orders[0].items[0].sku")
                ))
        ));
    }

    private static RailixValue.ObjectValue invalidValidationConfigNotAList() {
        return new RailixValue.ObjectValue(Map.of(
                "required", string("payload.customer.email")
        ));
    }

    private static RailixValue.ObjectValue invalidValidationConfigWithNonStringPath() {
        return new RailixValue.ObjectValue(Map.of(
                "required", new RailixValue.ListValue(List.of(
                        number(7)
                ))
        ));
    }

    private static RailixValue.ObjectValue routeConfig() {
        return new RailixValue.ObjectValue(Map.of(
                "routes", new RailixValue.ListValue(List.of(
                        object(Map.of(
                                "outcome", string("approval"),
                                "when", object(Map.of(
                                        "op", string("greaterThan"),
                                        "left", object(Map.of("path", string("ctx.order.total"))),
                                        "right", object(Map.of("const", number(1000)))
                                ))
                        )),
                        object(Map.of(
                                "outcome", string("auto"),
                                "when", object(Map.of("const", new RailixValue.BoolValue(true)))
                        ))
                ))
        ));
    }

    private static RailixValue.ObjectValue invalidRouteConfigNotAList() {
        return new RailixValue.ObjectValue(Map.of(
                "routes", string("approval")
        ));
    }

    private static RailixValue.ObjectValue invalidRouteConfigMissingOutcome() {
        return new RailixValue.ObjectValue(Map.of(
                "routes", new RailixValue.ListValue(List.of(
                        object(Map.of(
                                "when", object(Map.of("const", new RailixValue.BoolValue(true)))
                        ))
                ))
        ));
    }

    private static RailixValue.ObjectValue invalidRouteConfigUnsupportedOperator() {
        return new RailixValue.ObjectValue(Map.of(
                "routes", new RailixValue.ListValue(List.of(
                        object(Map.of(
                                "outcome", string("approval"),
                                "when", object(Map.of(
                                        "op", string("explode")
                                ))
                        ))
                ))
        ));
    }

    private static RailixValue.ObjectValue invalidRouteConfigWithoutMatch() {
        return new RailixValue.ObjectValue(Map.of(
                "routes", new RailixValue.ListValue(List.of(
                        object(Map.of(
                                "outcome", string("approval"),
                                "when", object(Map.of(
                                        "op", string("greaterThan"),
                                        "left", object(Map.of("path", string("ctx.order.total"))),
                                        "right", object(Map.of("const", number(1000)))
                                ))
                        ))
                ))
        ));
    }

    private static RailixValue.ObjectValue forEachConfig() {
        return new RailixValue.ObjectValue(Map.of(
                "selector", string("payload.orders[*].items[*]"),
                "as", string("item"),
                "statePath", string("ctx.foreach"),
                "parentAliases", object(Map.of(
                        "order", string("payload.orders[*]")
                ))
        ));
    }

    private static RailixValue.ObjectValue invalidForEachConfigReservedAlias() {
        return new RailixValue.ObjectValue(Map.of(
                "selector", string("payload.orders[*].items[*]"),
                "as", string("payload")
        ));
    }

    private static RailixValue.ObjectValue invalidForEachConfigReservedCursorAlias() {
        return new RailixValue.ObjectValue(Map.of(
                "selector", string("payload.orders[*].items[*]"),
                "as", string("_nextIndex")
        ));
    }

    private static RailixValue.ObjectValue invalidForEachConfigInvalidStatePath() {
        return new RailixValue.ObjectValue(Map.of(
                "selector", string("payload.orders[*].items[*]"),
                "statePath", string("reply.foreach")
        ));
    }

    private static RailixValue.ObjectValue invalidForEachConfigReservedParentAlias() {
        return new RailixValue.ObjectValue(Map.of(
                "selector", string("payload.orders[*].items[*]"),
                "parentAliases", object(Map.of(
                        "_selector", string("payload.orders[*]")
                ))
        ));
    }

    private static RailixValue.ObjectValue forEachConfigWithStatePath(final String statePath) {
        return new RailixValue.ObjectValue(Map.of(
                "selector", string("payload.orders[*].items[*]"),
                "as", string("item"),
                "statePath", string(statePath),
                "parentAliases", object(Map.of(
                        "order", string("payload.orders[*]")
                ))
        ));
    }

    private static RailixValue.ObjectValue aggregateConfig() {
        return new RailixValue.ObjectValue(Map.of(
                "selector", string("payload.orders[*].items[*]"),
                "as", string("item"),
                "aggregations", new RailixValue.ListValue(List.of(
                        object(Map.of(
                                "target", string("ctx.summary.itemCount"),
                                "op", string("count")
                        )),
                        object(Map.of(
                                "target", string("ctx.summary.totalQty"),
                                "op", string("sum"),
                                "path", string("item.qty")
                        )),
                        object(Map.of(
                                "target", string("ctx.summary.minPrice"),
                                "op", string("min"),
                                "path", string("item.price")
                        )),
                        object(Map.of(
                                "target", string("ctx.summary.maxPrice"),
                                "op", string("max"),
                                "path", string("item.price")
                        )),
                        object(Map.of(
                                "target", string("ctx.summary.skus"),
                                "op", string("collect"),
                                "path", string("item.sku")
                        ))
                ))
        ));
    }

    private static RailixValue.ObjectValue invalidAggregateConfigNotAList() {
        return new RailixValue.ObjectValue(Map.of(
                "selector", string("payload.orders[*].items[*]"),
                "aggregations", string("count")
        ));
    }

    private static RailixValue.ObjectValue invalidAggregateConfigInvalidTarget() {
        return new RailixValue.ObjectValue(Map.of(
                "selector", string("payload.orders[*].items[*]"),
                "aggregations", new RailixValue.ListValue(List.of(
                        object(Map.of(
                                "target", string("payload.summary.itemCount"),
                                "op", string("count")
                        ))
                ))
        ));
    }

    private static RailixValue.ObjectValue invalidAggregateConfigUnsupportedOp() {
        return new RailixValue.ObjectValue(Map.of(
                "selector", string("payload.orders[*].items[*]"),
                "aggregations", new RailixValue.ListValue(List.of(
                        object(Map.of(
                                "target", string("ctx.summary.fail"),
                                "op", string("group")
                        ))
                ))
        ));
    }

    private static RailixValue.ObjectValue invalidAggregateConfigNonNumericPath() {
        return new RailixValue.ObjectValue(Map.of(
                "selector", string("payload.orders[*].items[*]"),
                "as", string("item"),
                "aggregations", new RailixValue.ListValue(List.of(
                        object(Map.of(
                                "target", string("ctx.summary.fail"),
                                "op", string("sum"),
                                "path", string("item.sku")
                        ))
                ))
        ));
    }

    private static RailixValue.ObjectValue repeatConfig() {
        return new RailixValue.ObjectValue(Map.of(
                "mappings", new RailixValue.ListValue(List.of(
                        mapping("ctx.customer.email", object(Map.of(
                                "op", string("lower"),
                                "input", object(Map.of(
                                        "op", string("trim"),
                                        "input", object(Map.of(
                                                "path", string("payload.customer.email")
                                        ))
                                ))
                        ))),
                        new RailixValue.ObjectValue(Map.of(
                                "repeat", object(Map.of(
                                        "selector", string("payload.orders[*].items[*]"),
                                        "as", string("item"),
                                        "parentAliases", object(Map.of(
                                                "order", string("payload.orders[*]")
                                        )),
                                        "mappings", new RailixValue.ListValue(List.of(
                                                mapping("ctx.items[*].sku", object(Map.of(
                                                        "path", string("item.sku")
                                                ))),
                                                mapping("ctx.items[*].quantity", object(Map.of(
                                                        "op", string("toInt"),
                                                        "input", object(Map.of(
                                                                "path", string("item.qty")
                                                        ))
                                                ))),
                                                mapping("ctx.items[*].lineTotal", object(Map.of(
                                                        "op", string("multiply"),
                                                        "left", object(Map.of(
                                                                "path", string("item.qty")
                                                        )),
                                                        "right", object(Map.of(
                                                                "path", string("item.price")
                                                        ))
                                                ))),
                                                mapping("ctx.items[*].orderId", object(Map.of(
                                                        "path", string("order.id")
                                                )))
                                        ))
                                ))
                        )),
                        mapping("reply.mode", object(Map.of(
                                "const", string("immediate")
                        ))),
                        mapping("reply.payload.lastSku", object(Map.of(
                                "path", string("ctx.items[2].sku")
                        )))
                ))
        ));
    }

    private static RailixValue.ObjectValue repeatConfigWithoutWildcardTarget() {
        return new RailixValue.ObjectValue(Map.of(
                "mappings", new RailixValue.ListValue(List.of(
                        new RailixValue.ObjectValue(Map.of(
                                "repeat", object(Map.of(
                                        "selector", string("payload.orders[*].items[*]"),
                                        "as", string("item"),
                                        "mappings", new RailixValue.ListValue(List.of(
                                                mapping("ctx.items.sku", object(Map.of(
                                                        "path", string("item.sku")
                                                )))
                                        ))
                                ))
                        ))
                ))
        ));
    }

    private static RailixValue.ObjectValue repeatConfigWithMultipleWildcardTargets() {
        return new RailixValue.ObjectValue(Map.of(
                "mappings", new RailixValue.ListValue(List.of(
                        new RailixValue.ObjectValue(Map.of(
                                "repeat", object(Map.of(
                                        "selector", string("payload.orders[*].items[*]"),
                                        "as", string("item"),
                                        "mappings", new RailixValue.ListValue(List.of(
                                                mapping("ctx.items[*].nested[*].sku", object(Map.of(
                                                        "path", string("item.sku")
                                                )))
                                        ))
                                ))
                        ))
                ))
        ));
    }

    private static RailixValue.ListValue expectedRepeatedItems() {
        return new RailixValue.ListValue(List.of(
                new RailixValue.ObjectValue(Map.of(
                        "sku", new RailixValue.StringValue("A"),
                        "quantity", new RailixValue.NumberValue(BigDecimal.valueOf(2)),
                        "lineTotal", new RailixValue.NumberValue(BigDecimal.valueOf(7)),
                        "orderId", new RailixValue.StringValue("order-1")
                )),
                new RailixValue.ObjectValue(Map.of(
                        "sku", new RailixValue.StringValue("B"),
                        "quantity", new RailixValue.NumberValue(BigDecimal.ONE),
                        "lineTotal", new RailixValue.NumberValue(BigDecimal.valueOf(2.5)),
                        "orderId", new RailixValue.StringValue("order-1")
                )),
                new RailixValue.ObjectValue(Map.of(
                        "sku", new RailixValue.StringValue("C"),
                        "quantity", new RailixValue.NumberValue(BigDecimal.valueOf(3)),
                        "lineTotal", new RailixValue.NumberValue(BigDecimal.valueOf(12)),
                        "orderId", new RailixValue.StringValue("order-2")
                ))
        ));
    }

    private static RailixValue.ObjectValue unsupportedOperatorConfig() {
        return new RailixValue.ObjectValue(Map.of(
                "mappings", new RailixValue.ListValue(List.of(
                        mapping("ctx.fail", object(Map.of(
                                "op", string("explode"),
                                "input", object(Map.of("const", string("x")))
                        )))
                ))
        ));
    }

    private static RailixValue.ObjectValue invalidTargetConfig() {
        return new RailixValue.ObjectValue(Map.of(
                "mappings", new RailixValue.ListValue(List.of(
                        mapping("payload.customer.email", object(Map.of(
                                "const", string("rewritten")
                        )))
                ))
        ));
    }

    private static RailixValue.ObjectValue mapping(final String target, final RailixValue.ObjectValue expression) {
        return new RailixValue.ObjectValue(Map.of(
                "target", new RailixValue.StringValue(target),
                "expression", expression
        ));
    }

    private static RailixValue.ObjectValue object(final Map<String, RailixValue> values) {
        return new RailixValue.ObjectValue(values);
    }

    private static RailixValue.StringValue string(final String value) {
        return new RailixValue.StringValue(value);
    }

    private static RailixValue.NumberValue number(final double value) {
        return new RailixValue.NumberValue(BigDecimal.valueOf(value));
    }

    private record DelegatingContextDoc(InMemoryContextDoc delegate) implements ContextDoc {
        @Override
        public RailixValue get(final RailixPath path) {
            return delegate.get(path);
        }

        @Override
        public List<RailixValue> select(final Selector selector) {
            return delegate.select(selector);
        }

        @Override
        public List<RailixPath> selectedPaths(final Selector selector) {
            return delegate.selectedPaths(selector);
        }

        @Override
        public ContextDoc apply(final Patch patch) {
            return new DelegatingContextDoc((InMemoryContextDoc) delegate.apply(patch));
        }

        @Override
        public ContextDoc applyAll(final List<Patch> patches) {
            return new DelegatingContextDoc((InMemoryContextDoc) delegate.applyAll(patches));
        }

        @Override
        public ContextDiff diff(final ContextDoc other) {
            if (other instanceof DelegatingContextDoc otherDoc) {
                return delegate.diff(otherDoc.delegate);
            }
            return delegate.diff(other);
        }
    }
}
