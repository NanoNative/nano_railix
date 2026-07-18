package dev.nanonative.railix.railixstdaudit;

import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.StepContract;
import dev.nanonative.railix.kernel.runtime.ContextDoc;
import dev.nanonative.railix.kernel.runtime.Step;
import dev.nanonative.railix.kernel.runtime.StepProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal standard audit pack provider for the first honest custom audit slice.
 */
public final class StandardAuditStepProvider implements StepProvider {

    static final String AUDIT_CUSTOM_USE = "railix.std.audit.AuditCustom";

    private static final RailixPath CONTEXT_EVENT_NAME_PATH = RailixPath.parse("ctx.audit.eventName");
    private static final RailixPath PAYLOAD_EVENT_NAME_PATH = RailixPath.parse("payload.audit.eventName");
    private static final RailixPath CONTEXT_DATA_PATH = RailixPath.parse("ctx.audit.data");
    private static final RailixPath PAYLOAD_DATA_PATH = RailixPath.parse("payload.audit.data");
    private static final RailixPath EMITTED_EVENT_NAME_PATH = RailixPath.parse("ctx.audit.eventName");
    private static final RailixPath EMITTED_FLAG_PATH = RailixPath.parse("ctx.audit.emitted");

    @Override
    public Optional<Step> resolve(final String use) {
        return AUDIT_CUSTOM_USE.equals(use) ? Optional.of(auditCustomStep()) : Optional.empty();
    }

    @Override
    public List<String> supportedUses() {
        return List.of(AUDIT_CUSTOM_USE);
    }

    private static Step auditCustomStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        AUDIT_CUSTOM_USE,
                        "0.1.0",
                        "AuditCustom",
                        "Emits the first standard custom audit event into the run signal log.",
                        StepContract.Kind.NORMAL,
                        List.of(
                                new StepContract.Port("eventName", "string", CONTEXT_EVENT_NAME_PATH.toString(), true, List.of()),
                                new StepContract.Port("data", "object", CONTEXT_DATA_PATH.toString(), false, List.of())
                        ),
                        List.of(
                                new StepContract.Port("eventName", "string", EMITTED_EVENT_NAME_PATH.toString(), false, List.of()),
                                new StepContract.Port("emitted", "bool", EMITTED_FLAG_PATH.toString(), false, List.of())
                        ),
                        Map.of("ok", new StepContract.Outcome("Audit emitted")),
                        new StepContract.Settings(List.of()),
                        PermissionSet.none(),
                        new StepContract.Timeout(Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(List.of()),
                        Map.of("editor", new RailixValue.StringValue("audit.custom"))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final String eventName = requiredEventName(input.context());
                final RailixValue.ObjectValue data = auditData(input.context());
                input.audits().emit(eventName, data);
                return new Result(
                        "ok",
                        List.of(
                                new Patch.Set(EMITTED_EVENT_NAME_PATH, new Patch.LiteralSource(new RailixValue.StringValue(eventName))),
                                new Patch.Set(EMITTED_FLAG_PATH, new Patch.LiteralSource(new RailixValue.BoolValue(true)))
                        )
                );
            }
        };
    }

    private static String requiredEventName(final ContextDoc context) {
        final RailixValue value = context.get(CONTEXT_EVENT_NAME_PATH) != RailixValue.NULL
                ? context.get(CONTEXT_EVENT_NAME_PATH)
                : context.get(PAYLOAD_EVENT_NAME_PATH);
        if (!(value instanceof RailixValue.StringValue stringValue)) {
            if (value == RailixValue.NULL) {
                throw new IllegalStateException("AuditCustom requires ctx.audit.eventName or payload.audit.eventName");
            }
            throw new IllegalStateException("AuditCustom eventName must be a string");
        }
        final String normalized = stringValue.value().trim();
        if (normalized.isEmpty()) {
            final String sourcePath = context.get(CONTEXT_EVENT_NAME_PATH) != RailixValue.NULL
                    ? CONTEXT_EVENT_NAME_PATH.toString()
                    : PAYLOAD_EVENT_NAME_PATH.toString();
            throw new IllegalStateException(sourcePath + " must not be blank");
        }
        return normalized;
    }

    private static RailixValue.ObjectValue auditData(final ContextDoc context) {
        final RailixValue value = context.get(CONTEXT_DATA_PATH) != RailixValue.NULL
                ? context.get(CONTEXT_DATA_PATH)
                : context.get(PAYLOAD_DATA_PATH);
        if (value == RailixValue.NULL) {
            return new RailixValue.ObjectValue(Map.of());
        }
        if (value instanceof RailixValue.ObjectValue objectValue) {
            return objectValue;
        }
        final String sourcePath = context.get(CONTEXT_DATA_PATH) != RailixValue.NULL
                ? CONTEXT_DATA_PATH.toString()
                : PAYLOAD_DATA_PATH.toString();
        throw new IllegalStateException(sourcePath + " must be an object");
    }
}
