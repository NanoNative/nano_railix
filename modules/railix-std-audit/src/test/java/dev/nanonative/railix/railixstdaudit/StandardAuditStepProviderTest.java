package dev.nanonative.railix.railixstdaudit;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.runtime.InMemoryContextDoc;
import dev.nanonative.railix.kernel.runtime.Step;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StandardAuditStepProviderTest {

    @Test
    void shouldEmitAuditFromPayloadAndPatchContext() {
        final StandardAuditStepProvider provider = new StandardAuditStepProvider();
        final Envelope envelope = envelope(
                "customer.reviewed",
                Map.of(
                        "actor", new RailixValue.StringValue("developer"),
                        "channel", new RailixValue.StringValue("manual")
                )
        );
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardAuditStepProvider.AUDIT_CUSTOM_USE).orElseThrow();
        final List<EmittedAudit> emittedAudits = new ArrayList<>();

        final Step.Result result = step.execute(new Step.ExecutionInput(
                envelope,
                context,
                Step.SettingsView.none(),
                Step.ResourceScope.none(),
                Step.MetricScope.none(),
                (eventName, data) -> emittedAudits.add(new EmittedAudit(eventName, data))
        ));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.audit.eventName")))
                .isEqualTo(new RailixValue.StringValue("customer.reviewed"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.audit.emitted")))
                .isEqualTo(new RailixValue.BoolValue(true));
        assertThat(emittedAudits).containsExactly(new EmittedAudit(
                "customer.reviewed",
                new RailixValue.ObjectValue(Map.of(
                        "actor", new RailixValue.StringValue("developer"),
                        "channel", new RailixValue.StringValue("manual")
                ))
        ));
    }

    @Test
    void shouldPreferContextAuditEventAndDataOverPayload() {
        final StandardAuditStepProvider provider = new StandardAuditStepProvider();
        final Envelope envelope = envelope(
                "payload.audit",
                Map.of("actor", new RailixValue.StringValue("payload"))
        );
        final InMemoryContextDoc context = (InMemoryContextDoc) InMemoryContextDoc.fromEnvelope(envelope).applyAll(List.of(
                new Patch.Set(
                        RailixPath.parse("ctx.audit.eventName"),
                        new Patch.LiteralSource(new RailixValue.StringValue("ctx.audit"))
                ),
                new Patch.Set(
                        RailixPath.parse("ctx.audit.data"),
                        new Patch.LiteralSource(new RailixValue.ObjectValue(Map.of(
                                "actor", new RailixValue.StringValue("ctx"),
                                "reason", new RailixValue.StringValue("override")
                        )))
                )
        ));
        final Step step = provider.resolve(StandardAuditStepProvider.AUDIT_CUSTOM_USE).orElseThrow();
        final List<EmittedAudit> emittedAudits = new ArrayList<>();

        step.execute(new Step.ExecutionInput(
                envelope,
                context,
                Step.SettingsView.none(),
                Step.ResourceScope.none(),
                Step.MetricScope.none(),
                (eventName, data) -> emittedAudits.add(new EmittedAudit(eventName, data))
        ));

        assertThat(emittedAudits).containsExactly(new EmittedAudit(
                "ctx.audit",
                new RailixValue.ObjectValue(Map.of(
                        "actor", new RailixValue.StringValue("ctx"),
                        "reason", new RailixValue.StringValue("override")
                ))
        ));
    }

    @Test
    void shouldRejectMissingAuditEventName() {
        final StandardAuditStepProvider provider = new StandardAuditStepProvider();
        final Envelope envelope = envelope(null, Map.of("actor", new RailixValue.StringValue("developer")));
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardAuditStepProvider.AUDIT_CUSTOM_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope,
                context,
                Step.SettingsView.none(),
                Step.ResourceScope.none(),
                Step.MetricScope.none(),
                (eventName, data) -> {
                }
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AuditCustom requires ctx.audit.eventName or payload.audit.eventName");
    }

    @Test
    void shouldDefaultMissingAuditDataToEmptyObject() {
        final StandardAuditStepProvider provider = new StandardAuditStepProvider();
        final Envelope envelope = new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "audit", new RailixValue.ObjectValue(Map.of(
                                "eventName", new RailixValue.StringValue("customer.reviewed")
                        ))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(false, List.of())
        );
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardAuditStepProvider.AUDIT_CUSTOM_USE).orElseThrow();
        final List<EmittedAudit> emittedAudits = new ArrayList<>();

        step.execute(new Step.ExecutionInput(
                envelope,
                context,
                Step.SettingsView.none(),
                Step.ResourceScope.none(),
                Step.MetricScope.none(),
                (eventName, data) -> emittedAudits.add(new EmittedAudit(eventName, data))
        ));

        assertThat(emittedAudits).containsExactly(new EmittedAudit(
                "customer.reviewed",
                new RailixValue.ObjectValue(Map.of())
        ));
    }

    @Test
    void shouldRejectBlankAuditEventName() {
        final StandardAuditStepProvider provider = new StandardAuditStepProvider();
        final Envelope envelope = envelope("   ", Map.of("actor", new RailixValue.StringValue("developer")));
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardAuditStepProvider.AUDIT_CUSTOM_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope,
                context,
                Step.SettingsView.none(),
                Step.ResourceScope.none(),
                Step.MetricScope.none(),
                (eventName, data) -> {
                }
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("payload.audit.eventName must not be blank");
    }

    @Test
    void shouldRejectNonObjectAuditData() {
        final StandardAuditStepProvider provider = new StandardAuditStepProvider();
        final Envelope envelope = new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "audit", new RailixValue.ObjectValue(Map.of(
                                "eventName", new RailixValue.StringValue("customer.reviewed"),
                                "data", new RailixValue.StringValue("not-an-object")
                        ))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(false, List.of())
        );
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardAuditStepProvider.AUDIT_CUSTOM_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope,
                context,
                Step.SettingsView.none(),
                Step.ResourceScope.none(),
                Step.MetricScope.none(),
                (eventName, data) -> {
                }
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("payload.audit.data must be an object");
    }

    @Test
    void shouldReturnEmptyWhenUseIsUnknown() {
        final StandardAuditStepProvider provider = new StandardAuditStepProvider();

        assertThat(provider.resolve("railix.std.audit.Missing")).isEmpty();
    }

    private static Envelope envelope(final String eventName, final Map<String, RailixValue> auditData) {
        final Map<String, RailixValue> auditFields = new java.util.LinkedHashMap<>();
        if (eventName != null) {
            auditFields.put("eventName", new RailixValue.StringValue(eventName));
        }
        auditFields.put("data", new RailixValue.ObjectValue(auditData));
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of("audit", new RailixValue.ObjectValue(auditFields))),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(false, List.of())
        );
    }

    private record EmittedAudit(String eventName, RailixValue.ObjectValue data) {
    }
}
