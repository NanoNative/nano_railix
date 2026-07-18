package dev.nanonative.railix.railixstdtrigger;

import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.StepContract;
import dev.nanonative.railix.kernel.runtime.Step;
import dev.nanonative.railix.kernel.runtime.StepProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal standard trigger pack provider used by the local execution kernel.
 */
public final class StandardTriggerStepProvider implements StepProvider {

    static final String MANUAL_TRIGGER_USE = "railix.std.trigger.ManualTrigger";
    static final String CLI_TRIGGER_USE = "railix.std.trigger.CliTrigger";

    private static final RailixPath CONTEXT_PAYLOAD_PATH = RailixPath.parse("ctx.payload");
    private static final RailixPath CONTEXT_METADATA_PATH = RailixPath.parse("ctx.metadata");
    private static final RailixPath TRIGGER_KIND_PATH = RailixPath.parse("ctx.trigger.kind");
    private static final RailixPath TRIGGER_SOURCE_PATH = RailixPath.parse("ctx.trigger.source");
    private static final RailixPath TRIGGER_PROTOCOL_PATH = RailixPath.parse("ctx.trigger.protocol");
    private static final RailixPath TRIGGER_REPLY_SUPPORTED_PATH = RailixPath.parse("ctx.trigger.reply.supported");
    private static final RailixPath TRIGGER_REPLY_MODES_PATH = RailixPath.parse("ctx.trigger.reply.modes");

    @Override
    public Optional<Step> resolve(final String use) {
        return switch (use) {
            case MANUAL_TRIGGER_USE -> Optional.of(triggerStep(
                    MANUAL_TRIGGER_USE,
                    "Manual Trigger",
                    "Normalizes a prebuilt manual/dev envelope into trigger-owned context fields.",
                    "manual",
                    "trigger.manual"
            ));
            case CLI_TRIGGER_USE -> Optional.of(triggerStep(
                    CLI_TRIGGER_USE,
                    "CLI Trigger",
                    "Normalizes a prebuilt CLI envelope into trigger-owned context fields.",
                    "cli",
                    "trigger.cli"
            ));
            default -> Optional.empty();
        };
    }

    @Override
    public List<String> supportedUses() {
        return List.of(MANUAL_TRIGGER_USE, CLI_TRIGGER_USE);
    }

    private static Step triggerStep(
            final String use,
            final String displayName,
            final String description,
            final String triggerKind,
            final String editor
    ) {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        use,
                        "0.1.0",
                        displayName,
                        description,
                        StepContract.Kind.TRIGGER,
                        List.of(new StepContract.Port("envelope", "envelope", "envelope", true, List.of())),
                        List.of(new StepContract.Port("trigger", "document", "ctx.trigger", false, List.of())),
                        Map.of("triggered", new StepContract.Outcome("Envelope normalized into trigger context")),
                        new StepContract.Settings(List.of()),
                        PermissionSet.none(),
                        new StepContract.Timeout(Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(1, Duration.ZERO),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(List.of()),
                        Map.of("editor", new RailixValue.StringValue(editor))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                return new Result(
                        "triggered",
                        List.of(
                                new Patch.Set(CONTEXT_PAYLOAD_PATH, new Patch.LiteralSource(input.envelope().payload())),
                                new Patch.Set(CONTEXT_METADATA_PATH, new Patch.LiteralSource(input.envelope().metadata())),
                                new Patch.Set(TRIGGER_KIND_PATH, new Patch.LiteralSource(new RailixValue.StringValue(triggerKind))),
                                new Patch.Set(TRIGGER_SOURCE_PATH, new Patch.LiteralSource(new RailixValue.StringValue(input.envelope().source()))),
                                new Patch.Set(TRIGGER_PROTOCOL_PATH, new Patch.LiteralSource(new RailixValue.StringValue(input.envelope().protocol()))),
                                new Patch.Set(
                                        TRIGGER_REPLY_SUPPORTED_PATH,
                                        new Patch.LiteralSource(new RailixValue.BoolValue(input.envelope().replyChannel().supported()))
                                ),
                                new Patch.Set(
                                        TRIGGER_REPLY_MODES_PATH,
                                        new Patch.LiteralSource(new RailixValue.ListValue(
                                                input.envelope().replyChannel().modes().stream()
                                                        .map(mode -> (RailixValue) new RailixValue.StringValue(mode.name().toLowerCase(java.util.Locale.ROOT)))
                                                        .toList()
                                        ))
                                )
                        )
                );
            }
        };
    }
}
