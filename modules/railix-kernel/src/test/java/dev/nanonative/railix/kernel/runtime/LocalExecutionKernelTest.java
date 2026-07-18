package dev.nanonative.railix.kernel.runtime;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Reply;
import dev.nanonative.railix.kernel.model.SettingsTree;
import dev.nanonative.railix.kernel.model.StepContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalExecutionKernelTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRunLinearFlowAndEmitCoreSignalsInOrder() throws Exception {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final AppPlan plan = new AppPlan(
                "railix-app",
                "order-flow",
                "normalize",
                List.of(
                        new AppPlan.StepInvocation("normalize", "test.normalize", Map.of("ok", "reply")),
                        new AppPlan.StepInvocation("reply", "test.reply", Map.of())
                )
        );

        final LocalExecutionKernel.RunRecord record = kernel.run(
                plan,
                new LocalExecutionKernel.RunRequest("run-1", tempDir, envelope(true)),
                use -> switch (use) {
                    case "test.normalize" -> step("test.normalize", Map.of("ok", "done"), input -> new Step.Result(
                            "ok",
                            List.of(new Patch.Set(
                                    RailixPath.parse("ctx.customer.email"),
                                    new Patch.ExpressionSource(new Patch.PathExpression(RailixPath.parse("payload.customer.email")))
                            ))
                    ));
                    case "test.reply" -> step("test.reply", Map.of(), input -> new Step.Result(
                            "ok",
                            List.of(),
                            new Reply(
                                    Reply.Mode.IMMEDIATE,
                                    new RailixValue.NumberValue(BigDecimal.valueOf(201)),
                                    new RailixValue.ObjectValue(Map.of("contentType", new RailixValue.StringValue("application/json"))),
                                    new RailixValue.ObjectValue(Map.of(
                                            "customerEmail", input.context().get(RailixPath.parse("ctx.customer.email"))
                                    )),
                                    RailixValue.NULL,
                                    RailixValue.NULL,
                                    RailixValue.NULL,
                                    RailixValue.NULL
                            )
                    ));
                    default -> null;
                }
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.executedSteps()).isEqualTo(2);
        assertThat(record.reply().mode()).isEqualTo(Reply.Mode.IMMEDIATE);
        assertThat(record.context().get(RailixPath.parse("ctx.customer.email")))
                .isEqualTo(new RailixValue.StringValue("USER@EXAMPLE.COM"));
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "context.patched",
                "step.finished",
                "step.started",
                "step.finished",
                "reply.produced",
                "run.finished"
        );
        assertThat(Files.exists(record.signalLog().file())).isTrue();
        assertThat(Files.readString(record.runFolder().resolve("summary.json"))).contains("\"replyMode\":\"immediate\"");
    }

    @Test
    void shouldPassInvocationConfigIntoStepExecution() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final AppPlan plan = new AppPlan(
                "railix-app",
                "config-flow",
                "config-step",
                List.of(new AppPlan.StepInvocation(
                        "config-step",
                        "test.config",
                        new RailixValue.ObjectValue(Map.of(
                                "message", new RailixValue.StringValue("from-config")
                        )),
                        Map.of()
                ))
        );

        final LocalExecutionKernel.RunRecord record = kernel.run(
                plan,
                new LocalExecutionKernel.RunRequest("run-config-1", tempDir, envelope(true)),
                use -> step("test.config", Map.of("ok", "done"), input -> new Step.Result(
                        "ok",
                        List.of(new Patch.Set(
                                RailixPath.parse("ctx.message"),
                                new Patch.LiteralSource(input.config().values().get("message"))
                        ))
                ))
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.context().get(RailixPath.parse("ctx.message")))
                .isEqualTo(new RailixValue.StringValue("from-config"));
    }

    @Test
    void shouldStopDispatchOnStepFailureAndEmitFailedSignals() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final AppPlan plan = new AppPlan(
                "railix-app",
                "order-flow",
                "explode",
                List.of(new AppPlan.StepInvocation("explode", "test.explode", Map.of()))
        );

        final LocalExecutionKernel.RunRecord record = kernel.run(
                plan,
                new LocalExecutionKernel.RunRequest("run-2", tempDir, envelope(true)),
                use -> step("test.explode", Map.of("ok", "done"), input -> {
                    throw new IllegalStateException("boom");
                })
        );

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(record.reply()).isEqualTo(Reply.none());
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "step.failed",
                "run.finished"
        );
    }

    @Test
    void shouldRejectUndeclaredOutcomesAndUnsupportedReplyModes() throws Exception {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));

        final LocalExecutionKernel.RunRecord undeclaredOutcomeRecord = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "route",
                        List.of(new AppPlan.StepInvocation("route", "test.route", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-3", tempDir, envelope(true)),
                use -> step("test.route", Map.of("ok", "done"), input -> new Step.Result("unexpected", List.of()))
        );

        assertThat(undeclaredOutcomeRecord.outcome()).isEqualTo("failed");
        assertThat(allSignals(undeclaredOutcomeRecord).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "step.failed",
                "run.finished"
        );

        final LocalExecutionKernel.RunRecord unsupportedReplyRecord = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "reply",
                        List.of(new AppPlan.StepInvocation("reply", "test.reply", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-4", tempDir, envelope(false)),
                use -> step("test.reply", Map.of("ok", "done"), input -> new Step.Result(
                        "ok",
                        List.of(),
                        new Reply(
                                Reply.Mode.IMMEDIATE,
                                new RailixValue.NumberValue(BigDecimal.valueOf(200)),
                                new RailixValue.ObjectValue(Map.of()),
                                new RailixValue.StringValue("hi"),
                                RailixValue.NULL,
                                RailixValue.NULL,
                                RailixValue.NULL,
                                RailixValue.NULL
                        )
                ))
        );

        assertThat(unsupportedReplyRecord.outcome()).isEqualTo("failed");
        assertThat(unsupportedReplyRecord.reply()).isEqualTo(Reply.none());
        assertThat(allSignals(unsupportedReplyRecord).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "step.finished",
                "run.finished"
        );
        assertThat(Files.readString(unsupportedReplyRecord.runFolder().resolve("summary.json")))
                .contains("\"phase\":\"reply.validation\"")
                .contains("\"message\":\"Reply channel does not support replies\"");
    }

    @Test
    void shouldUseContextReplyFallbackWildcardTransitionsAndRejectMissingResolverEntries() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final AppPlan wildcardPlan = new AppPlan(
                "railix-app",
                "order-flow",
                "route",
                List.of(
                        new AppPlan.StepInvocation("route", "test.route", Map.of("*", "reply")),
                        new AppPlan.StepInvocation("reply", "test.reply", Map.of())
                )
        );

        final LocalExecutionKernel.RunRecord record = kernel.run(
                wildcardPlan,
                new LocalExecutionKernel.RunRequest("run-5", tempDir, envelope(true)),
                use -> switch (use) {
                    case "test.route" -> step("test.route", Map.of("later", "branch"), input -> new Step.Result(
                            "later",
                            List.of(new Patch.Set(
                                    RailixPath.parse("ctx.routed"),
                                    new Patch.LiteralSource(new RailixValue.BoolValue(true))
                            ))
                    ));
                    case "test.reply" -> step("test.reply", Map.of(), input -> new Step.Result(
                            "ok",
                            List.of(
                                    new Patch.Set(
                                            RailixPath.parse("reply.mode"),
                                            new Patch.LiteralSource(new RailixValue.StringValue("immediate"))
                                    ),
                                    new Patch.Set(
                                            RailixPath.parse("reply.status"),
                                            new Patch.LiteralSource(new RailixValue.NumberValue(BigDecimal.valueOf(202)))
                                    ),
                                    new Patch.Set(
                                            RailixPath.parse("reply.payload.message"),
                                            new Patch.LiteralSource(new RailixValue.StringValue("queued"))
                                    )
                            )
                    ));
                    default -> null;
                }
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.executedSteps()).isEqualTo(2);
        assertThat(record.reply().mode()).isEqualTo(Reply.Mode.IMMEDIATE);
        assertThat(record.reply().status()).isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(202)));
        assertThat(record.reply().payload()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "message", new RailixValue.StringValue("queued")
        )));
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "context.patched",
                "step.finished",
                "step.started",
                "context.patched",
                "step.finished",
                "reply.produced",
                "run.finished"
        );

        final LocalExecutionKernel.RunRecord missingResolverRecord = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "missing",
                        List.of(new AppPlan.StepInvocation("missing", "test.missing", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-6", tempDir, envelope(true)),
                use -> null
        );

        assertThat(missingResolverRecord.outcome()).isEqualTo("failed");
        assertThat(missingResolverRecord.executedSteps()).isZero();
        assertThat(allSignals(missingResolverRecord).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.failed",
                "run.finished"
        );
        final RunSignal.StepFailed missingResolverFailure = (RunSignal.StepFailed) allSignals(missingResolverRecord).get(1);
        assertThat(((RailixValue.StringValue) ((RailixValue.ObjectValue) missingResolverFailure.errorSummary()).values().get("message")).value())
                .isEqualTo("Unknown step use: test.missing");
    }

    @Test
    void shouldAllowReplyNoneWhenEnvelopeDoesNotSupportReplies() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "noop",
                        List.of(new AppPlan.StepInvocation("noop", "test.noop", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-7", tempDir, envelope(false)),
                use -> step("test.noop", Map.of(), input -> new Step.Result("ok", List.of()))
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.reply()).isEqualTo(Reply.none());
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "step.finished",
                "reply.produced",
                "run.finished"
        );
    }

    @Test
    void shouldFailRunWhenContextReplyStateIsInvalid() throws Exception {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "reply",
                        List.of(new AppPlan.StepInvocation("reply", "test.reply", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-8", tempDir, envelope(true)),
                use -> step("test.reply", Map.of(), input -> new Step.Result(
                        "ok",
                        List.of(new Patch.Set(
                                RailixPath.parse("reply.mode"),
                                new Patch.LiteralSource(new RailixValue.StringValue("side-channel"))
                        ))
                ))
        );

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(record.reply()).isEqualTo(Reply.none());
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "context.patched",
                "step.finished",
                "run.finished"
        );
        assertThat(Files.readString(record.runFolder().resolve("summary.json")))
                .contains("\"phase\":\"reply.validation\"")
                .contains("\"message\":\"Unsupported reply mode: side-channel\"");
    }

    @Test
    void shouldReadGrantedSecretSettingAndEmitPermissionAndSettingSignals() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final RailixPath path = RailixPath.parse("settings.api.token");
        final SettingsTree settingsTree = new SettingsTree(
                "Execution settings",
                Map.of(path, new SettingsTree.Entry(
                        path,
                        "string",
                        new SettingsTree.PlainValue(new RailixValue.StringValue("token-123")),
                        true,
                        true,
                        false,
                        "settings/dev.yaml",
                        SettingsTree.Visibility.MASKED,
                        SettingsTree.Audit.NEVER,
                        SettingsTree.OverridePolicy.ALLOW
                )),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "read-secret",
                        List.of(new AppPlan.StepInvocation("read-secret", "test.read-secret", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-9", tempDir, envelope(false), settingsTree),
                use -> step(
                        "test.read-secret",
                        Map.of(),
                        List.of(path.toString()),
                        permissionSet(
                                Map.of("settings.secret", List.of(path.toString())),
                                Map.of("settings.secret", List.of(path.toString()))
                        ),
                        input -> new Step.Result(
                                "ok",
                                List.of(new Patch.Set(
                                        RailixPath.parse("ctx.token"),
                                        new Patch.LiteralSource(input.settings().require(path).value())
                                ))
                        )
                )
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.context().get(RailixPath.parse("ctx.token")))
                .isEqualTo(new RailixValue.StringValue("token-123"));
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "permission.decided",
                "setting.read",
                "context.patched",
                "step.finished",
                "reply.produced",
                "run.finished"
        );
        final RunSignal.PermissionDecided permissionDecided = (RunSignal.PermissionDecided) allSignals(record).get(2);
        final RunSignal.SettingRead settingRead = (RunSignal.SettingRead) allSignals(record).get(3);
        assertThat(permissionDecided.permission()).isEqualTo("settings.secret");
        assertThat(permissionDecided.decision()).isEqualTo("granted");
        assertThat(permissionDecided.reason()).isEqualTo("permission-granted");
        assertThat(settingRead.secret()).isTrue();
        assertThat(settingRead.materialized()).isTrue();
        assertThat(settingRead.path()).isEqualTo(path);
    }

    @Test
    void shouldReadGrantedSettingFromPlanLevelPermissions() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final RailixPath path = RailixPath.parse("settings.api.token");
        final SettingsTree settingsTree = new SettingsTree(
                "Execution settings",
                Map.of(path, new SettingsTree.Entry(
                        path,
                        "string",
                        new SettingsTree.PlainValue(new RailixValue.StringValue("token-123")),
                        true,
                        true,
                        false,
                        "settings/dev.yaml",
                        SettingsTree.Visibility.MASKED,
                        SettingsTree.Audit.NEVER,
                        SettingsTree.OverridePolicy.ALLOW
                )),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "read-secret",
                        permissionSet(
                                Map.of(),
                                Map.of("settings.secret", List.of(path.toString()))
                        ),
                        List.of(new AppPlan.StepInvocation("read-secret", "test.read-secret", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-9b", tempDir, envelope(false), settingsTree),
                use -> step(
                        "test.read-secret",
                        Map.of(),
                        List.of(path.toString()),
                        permissionSet(
                                Map.of("settings.secret", List.of(path.toString())),
                                Map.of()
                        ),
                        input -> new Step.Result(
                                "ok",
                                List.of(new Patch.Set(
                                        RailixPath.parse("ctx.token"),
                                        new Patch.LiteralSource(input.settings().require(path).value())
                                ))
                        )
                )
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.context().get(RailixPath.parse("ctx.token")))
                .isEqualTo(new RailixValue.StringValue("token-123"));
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "permission.decided",
                "setting.read",
                "context.patched",
                "step.finished",
                "reply.produced",
                "run.finished"
        );
        final RunSignal.PermissionDecided permissionDecided = (RunSignal.PermissionDecided) allSignals(record).get(2);
        assertThat(permissionDecided.permission()).isEqualTo("settings.secret");
        assertThat(permissionDecided.decision()).isEqualTo("granted");
        assertThat(permissionDecided.reason()).isEqualTo("permission-granted-by-plan");
    }

    @Test
    void shouldReturnEmptyForMissingGrantedSettingWithoutPhantomAuditSignal() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final RailixPath path = RailixPath.parse("settings.http.port");

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "read-missing",
                        List.of(new AppPlan.StepInvocation("read-missing", "test.read-missing", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-10", tempDir, envelope(false), SettingsTree.empty()),
                use -> step(
                        "test.read-missing",
                        Map.of(),
                        List.of(path.toString()),
                        permissionSet(
                                Map.of("settings.read", List.of(path.toString())),
                                Map.of("settings.read", List.of(path.toString()))
                        ),
                        input -> new Step.Result(
                                "ok",
                                List.of(new Patch.Set(
                                        RailixPath.parse("ctx.missing"),
                                        new Patch.LiteralSource(new RailixValue.BoolValue(input.settings().read(path).isEmpty()))
                                ))
                        )
                )
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.context().get(RailixPath.parse("ctx.missing")))
                .isEqualTo(new RailixValue.BoolValue(true));
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "permission.decided",
                "context.patched",
                "step.finished",
                "reply.produced",
                "run.finished"
        );
    }

    @Test
    void shouldFailDeniedSettingReadAfterDeniedPermissionDecision() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final RailixPath path = RailixPath.parse("settings.http.port");
        final SettingsTree settingsTree = new SettingsTree(
                "Execution settings",
                Map.of(path, new SettingsTree.Entry(
                        path,
                        "int",
                        new SettingsTree.PlainValue(new RailixValue.NumberValue(BigDecimal.valueOf(8080))),
                        true,
                        false,
                        false,
                        "settings/app.yaml",
                        SettingsTree.Visibility.NORMAL,
                        SettingsTree.Audit.NEVER,
                        SettingsTree.OverridePolicy.ALLOW
                )),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "deny-read",
                        List.of(new AppPlan.StepInvocation("deny-read", "test.deny-read", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-11", tempDir, envelope(false), settingsTree),
                use -> step(
                        "test.deny-read",
                        Map.of(),
                        List.of(path.toString()),
                        permissionSet(
                                Map.of("settings.read", List.of(path.toString())),
                                Map.of()
                        ),
                        input -> new Step.Result(
                                "ok",
                                List.of(new Patch.Set(
                                        RailixPath.parse("ctx.port"),
                                        new Patch.LiteralSource(input.settings().require(path).value())
                                ))
                        )
                )
        );

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "permission.decided",
                "step.failed",
                "run.finished"
        );
        final RunSignal.PermissionDecided permissionDecided = (RunSignal.PermissionDecided) allSignals(record).get(2);
        assertThat(permissionDecided.permission()).isEqualTo("settings.read");
        assertThat(permissionDecided.decision()).isEqualTo("denied");
        assertThat(permissionDecided.reason()).isEqualTo("permission-not-granted");
    }

    @Test
    void shouldGrantWildcardPlanPermissionForRuntimeResourceOperation() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "read-file",
                        permissionSet(
                                Map.of(),
                                Map.of("resource.read", List.of("file/*"))
                        ),
                        List.of(new AppPlan.StepInvocation("read-file", "test.read-file", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-11b", tempDir, envelope(false), SettingsTree.empty()),
                use -> step(
                        "test.read-file",
                        Map.of(),
                        List.of(),
                        permissionSet(
                                Map.of("resource.read", List.of("file/*")),
                                Map.of()
                        ),
                        input -> {
                            input.permissions().require("resource.read", "file/reports/result.json");
                            return new Step.Result("ok", List.of());
                        }
                )
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "permission.decided",
                "step.finished",
                "reply.produced",
                "run.finished"
        );
        final RunSignal.PermissionDecided permissionDecided = (RunSignal.PermissionDecided) allSignals(record).get(2);
        assertThat(permissionDecided.permission()).isEqualTo("resource.read");
        assertThat(permissionDecided.resource()).isEqualTo("file/reports/result.json");
        assertThat(permissionDecided.reason()).isEqualTo("permission-granted-by-plan");
    }

    @Test
    void shouldFailUndeclaredRuntimeResourcePermissionBeforeSideEffects() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "read-file",
                        permissionSet(
                                Map.of(),
                                Map.of("resource.read", List.of("file/*"))
                        ),
                        List.of(new AppPlan.StepInvocation("read-file", "test.read-file", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-11c", tempDir, envelope(false), SettingsTree.empty()),
                use -> step(
                        "test.read-file",
                        Map.of(),
                        List.of(),
                        permissionSet(
                                Map.of("resource.write", List.of("file/*")),
                                Map.of()
                        ),
                        input -> {
                            input.permissions().require("resource.read", "file/reports/result.json");
                            return new Step.Result("ok", List.of());
                        }
                )
        );

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "permission.decided",
                "step.failed",
                "run.finished"
        );
        final RunSignal.PermissionDecided permissionDecided = (RunSignal.PermissionDecided) allSignals(record).get(2);
        assertThat(permissionDecided.permission()).isEqualTo("resource.read");
        assertThat(permissionDecided.resource()).isEqualTo("file/reports/result.json");
        assertThat(permissionDecided.reason()).isEqualTo("permission-not-declared");
    }

    @Test
    void shouldFailEncryptedSettingReadsWithExplicitDiagnostic() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final RailixPath path = RailixPath.parse("settings.database.password");
        final SettingsTree settingsTree = new SettingsTree(
                "Execution settings",
                Map.of(path, new SettingsTree.Entry(
                        path,
                        "string",
                        new SettingsTree.EncryptedValue("ENC[pwd]"),
                        true,
                        true,
                        true,
                        "settings/prod.sops.yaml",
                        SettingsTree.Visibility.HIDDEN,
                        SettingsTree.Audit.ALWAYS,
                        SettingsTree.OverridePolicy.TRUSTED_ONLY
                )),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.ENCRYPTED_SETTINGS_FILE)
        );

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "read-encrypted",
                        List.of(new AppPlan.StepInvocation("read-encrypted", "test.read-encrypted", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-12", tempDir, envelope(false), settingsTree),
                use -> step(
                        "test.read-encrypted",
                        Map.of(),
                        List.of(path.toString()),
                        permissionSet(
                                Map.of("settings.secret", List.of(path.toString())),
                                Map.of("settings.secret", List.of(path.toString()))
                        ),
                        input -> new Step.Result(
                                "ok",
                                List.of(new Patch.Set(
                                        RailixPath.parse("ctx.password"),
                                        new Patch.LiteralSource(input.settings().require(path).value())
                                ))
                        )
                )
        );

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "permission.decided",
                "setting.read",
                "step.failed",
                "run.finished"
        );
    }

    @Test
    void shouldRetryTransientFailureAndSucceedOnSecondAttempt() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final AtomicInteger attempts = new AtomicInteger();

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "retry-step",
                        List.of(new AppPlan.StepInvocation("retry-step", "test.retry-step", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-13", tempDir, envelope(false)),
                use -> step(
                        "test.retry-step",
                        Map.of(),
                        List.of(),
                        PermissionSet.none(),
                        2,
                        Duration.ZERO,
                        input -> {
                            if (attempts.incrementAndGet() == 1) {
                                throw new RuntimeException("transient");
                            }
                            return new Step.Result(
                                    "ok",
                                    List.of(new Patch.Set(
                                            RailixPath.parse("ctx.retryCount"),
                                            new Patch.LiteralSource(new RailixValue.NumberValue(BigDecimal.valueOf(attempts.get())))
                                    ))
                            );
                        }
                )
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.executedSteps()).isEqualTo(1);
        assertThat(record.context().get(RailixPath.parse("ctx.retryCount")))
                .isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(2)));
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "step.failed",
                "step.started",
                "context.patched",
                "step.finished",
                "metric.emitted",
                "metric.emitted",
                "reply.produced",
                "run.finished"
        );
        assertThat(((RunSignal.StepStarted) allSignals(record).get(1)).attemptNumber()).isEqualTo(1);
        assertThat(((RunSignal.StepFailed) allSignals(record).get(2)).attemptNumber()).isEqualTo(1);
        assertThat(((RunSignal.StepStarted) allSignals(record).get(3)).attemptNumber()).isEqualTo(2);
        assertThat(((RunSignal.StepFinished) allSignals(record).get(5)).attemptNumber()).isEqualTo(2);
    }

    @Test
    void shouldStopAfterMaxAttemptsAndEmitEachAttemptFailure() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final AtomicInteger attempts = new AtomicInteger();

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "always-fail",
                        List.of(new AppPlan.StepInvocation("always-fail", "test.always-fail", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-14", tempDir, envelope(false)),
                use -> step(
                        "test.always-fail",
                        Map.of(),
                        List.of(),
                        PermissionSet.none(),
                        3,
                        Duration.ZERO,
                        input -> {
                            attempts.incrementAndGet();
                            throw new RuntimeException("still failing");
                        }
                )
        );

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(record.executedSteps()).isZero();
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "step.failed",
                "step.started",
                "step.failed",
                "step.started",
                "step.failed",
                "metric.emitted",
                "metric.emitted",
                "run.finished"
        );
        assertThat(((RunSignal.StepFailed) allSignals(record).get(2)).attemptNumber()).isEqualTo(1);
        assertThat(((RunSignal.StepFailed) allSignals(record).get(4)).attemptNumber()).isEqualTo(2);
        assertThat(((RunSignal.StepFailed) allSignals(record).get(6)).attemptNumber()).isEqualTo(3);
    }

    @Test
    void shouldNotRetryDeterministicSettingsBoundaryFailure() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final AtomicInteger attempts = new AtomicInteger();
        final RailixPath path = RailixPath.parse("settings.http.port");

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "deny-read",
                        List.of(new AppPlan.StepInvocation("deny-read", "test.deny-read", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-15", tempDir, envelope(false), SettingsTree.empty()),
                use -> step(
                        "test.deny-read",
                        Map.of(),
                        List.of(path.toString()),
                        permissionSet(
                                Map.of("settings.read", List.of(path.toString())),
                                Map.of()
                        ),
                        3,
                        Duration.ZERO,
                        input -> {
                            attempts.incrementAndGet();
                            input.settings().require(path);
                            return new Step.Result("ok", List.of());
                        }
                )
        );

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "permission.decided",
                "step.failed",
                "run.finished"
        );
        assertThat(((RunSignal.StepFailed) allSignals(record).get(3)).attemptNumber()).isEqualTo(1);
    }

    @Test
    void shouldCarryAttemptNumbersIntoSettingsSignalsAcrossRetries() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final AtomicInteger attempts = new AtomicInteger();
        final RailixPath path = RailixPath.parse("settings.api.token");
        final SettingsTree settingsTree = new SettingsTree(
                "Execution settings",
                Map.of(path, new SettingsTree.Entry(
                        path,
                        "string",
                        new SettingsTree.PlainValue(new RailixValue.StringValue("token-123")),
                        true,
                        true,
                        false,
                        "settings/dev.yaml",
                        SettingsTree.Visibility.MASKED,
                        SettingsTree.Audit.NEVER,
                        SettingsTree.OverridePolicy.ALLOW
                )),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "retry-secret",
                        List.of(new AppPlan.StepInvocation("retry-secret", "test.retry-secret", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-16", tempDir, envelope(false), settingsTree),
                use -> step(
                        "test.retry-secret",
                        Map.of(),
                        List.of(path.toString()),
                        permissionSet(
                                Map.of("settings.secret", List.of(path.toString())),
                                Map.of("settings.secret", List.of(path.toString()))
                        ),
                        2,
                        Duration.ZERO,
                        input -> {
                            final Step.ReadValue readValue = input.settings().require(path);
                            if (attempts.incrementAndGet() == 1) {
                                throw new RuntimeException("retry after read");
                            }
                            return new Step.Result(
                                    "ok",
                                    List.of(new Patch.Set(
                                            RailixPath.parse("ctx.token"),
                                            new Patch.LiteralSource(readValue.value())
                                    ))
                            );
                        }
                )
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "permission.decided",
                "setting.read",
                "step.failed",
                "step.started",
                "permission.decided",
                "setting.read",
                "context.patched",
                "step.finished",
                "metric.emitted",
                "metric.emitted",
                "reply.produced",
                "run.finished"
        );
        assertThat(((RunSignal.PermissionDecided) allSignals(record).get(2)).attemptNumber()).isEqualTo(1);
        assertThat(((RunSignal.SettingRead) allSignals(record).get(3)).attemptNumber()).isEqualTo(1);
        assertThat(((RunSignal.PermissionDecided) allSignals(record).get(6)).attemptNumber()).isEqualTo(2);
        assertThat(((RunSignal.SettingRead) allSignals(record).get(7)).attemptNumber()).isEqualTo(2);
    }

    @Test
    void shouldEmitRetryMetricsWhenTransientFailureSucceedsOnLaterAttempt() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final AtomicInteger attempts = new AtomicInteger();

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "retry-step",
                        List.of(new AppPlan.StepInvocation("retry-step", "test.retry-step", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-17", tempDir, envelope(false)),
                use -> step(
                        "test.retry-step",
                        Map.of(),
                        List.of(),
                        PermissionSet.none(),
                        2,
                        Duration.ZERO,
                        input -> {
                            if (attempts.incrementAndGet() == 1) {
                                throw new RuntimeException("transient");
                            }
                            return new Step.Result("ok", List.of());
                        }
                )
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "step.failed",
                "step.started",
                "step.finished",
                "metric.emitted",
                "metric.emitted",
                "reply.produced",
                "run.finished"
        );
        final RunSignal.MetricEmitted attemptMetric = (RunSignal.MetricEmitted) allSignals(record).get(5);
        final RunSignal.MetricEmitted retryCountMetric = (RunSignal.MetricEmitted) allSignals(record).get(6);
        assertThat(attemptMetric.name()).isEqualTo("step.attempt");
        assertThat(attemptMetric.value()).isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(2)));
        assertThat(attemptMetric.unit()).isEqualTo("attempts");
        assertThat(attemptMetric.attemptNumber()).isEqualTo(2);
        assertThat(attemptMetric.labels()).containsEntry("result", "success");
        assertThat(retryCountMetric.name()).isEqualTo("step.retryCount");
        assertThat(retryCountMetric.value()).isEqualTo(new RailixValue.NumberValue(BigDecimal.ONE));
        assertThat(retryCountMetric.unit()).isEqualTo("retries");
        assertThat(retryCountMetric.attemptNumber()).isEqualTo(2);
        assertThat(retryCountMetric.labels()).containsEntry("result", "success");
    }

    @Test
    void shouldEmitRetryMetricsWhenRunExhaustsMaxAttempts() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "always-fail",
                        List.of(new AppPlan.StepInvocation("always-fail", "test.always-fail", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-18", tempDir, envelope(false)),
                use -> step(
                        "test.always-fail",
                        Map.of(),
                        List.of(),
                        PermissionSet.none(),
                        3,
                        Duration.ZERO,
                        input -> {
                            throw new RuntimeException("still failing");
                        }
                )
        );

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "step.failed",
                "step.started",
                "step.failed",
                "step.started",
                "step.failed",
                "metric.emitted",
                "metric.emitted",
                "run.finished"
        );
        final RunSignal.MetricEmitted attemptMetric = (RunSignal.MetricEmitted) allSignals(record).get(7);
        final RunSignal.MetricEmitted retryCountMetric = (RunSignal.MetricEmitted) allSignals(record).get(8);
        assertThat(attemptMetric.name()).isEqualTo("step.attempt");
        assertThat(attemptMetric.value()).isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(3)));
        assertThat(attemptMetric.unit()).isEqualTo("attempts");
        assertThat(attemptMetric.attemptNumber()).isEqualTo(3);
        assertThat(attemptMetric.labels()).containsEntry("result", "failure");
        assertThat(retryCountMetric.name()).isEqualTo("step.retryCount");
        assertThat(retryCountMetric.value()).isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(2)));
        assertThat(retryCountMetric.unit()).isEqualTo("retries");
        assertThat(retryCountMetric.attemptNumber()).isEqualTo(3);
        assertThat(retryCountMetric.labels()).containsEntry("result", "failure");
    }

    @Test
    void shouldEmitStepOwnedMetricSignalsThroughKernelBoundary() throws Exception {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "metric-step",
                        List.of(new AppPlan.StepInvocation("metric-step", "test.metric-step", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-18b", tempDir, envelope(false)),
                use -> step(
                        "test.metric-step",
                        Map.of(),
                        List.of(),
                        PermissionSet.none(),
                        1,
                        Duration.ZERO,
                        List.of(new StepContract.MetricDefinition("custom.duration", "duration", "ms", List.of("phase"))),
                        input -> {
                            input.metrics().emit(
                                    "custom.duration",
                                    new RailixValue.NumberValue(BigDecimal.valueOf(12)),
                                    Map.of("phase", "transform")
                            );
                            return new Step.Result("ok", List.of());
                        }
                )
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "metric.emitted",
                "step.finished",
                "reply.produced",
                "run.finished"
        );
        final RunSignal.MetricEmitted metric = (RunSignal.MetricEmitted) allSignals(record).get(2);
        assertThat(metric.step()).isEqualTo("metric-step");
        assertThat(metric.attemptNumber()).isEqualTo(1);
        assertThat(metric.name()).isEqualTo("custom.duration");
        assertThat(metric.value()).isEqualTo(new RailixValue.NumberValue(BigDecimal.valueOf(12)));
        assertThat(metric.unit()).isEqualTo("ms");
        assertThat(metric.labels()).containsExactly(Map.entry("phase", "transform"));
        assertThat(Files.readString(record.signalLog().file())).contains("\"type\":\"metric.emitted\"");
    }

    @Test
    void shouldEmitStepOwnedAuditSignalsThroughKernelBoundary() throws Exception {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "audit-step",
                        List.of(new AppPlan.StepInvocation("audit-step", "test.audit-step", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-18audit", tempDir, envelope(false)),
                use -> step(
                        "test.audit-step",
                        Map.of(),
                        List.of(),
                        PermissionSet.none(),
                        1,
                        Duration.ZERO,
                        input -> {
                            input.audits().emit(
                                    "customer.reviewed",
                                    new RailixValue.ObjectValue(Map.of(
                                            "actor", new RailixValue.StringValue("developer"),
                                            "channel", new RailixValue.StringValue("manual")
                                    ))
                            );
                            return new Step.Result("ok", List.of());
                        }
                )
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "audit.emitted",
                "step.finished",
                "reply.produced",
                "run.finished"
        );
        final RunSignal.AuditEmitted audit = (RunSignal.AuditEmitted) allSignals(record).get(2);
        assertThat(audit.step()).isEqualTo("audit-step");
        assertThat(audit.attemptNumber()).isEqualTo(1);
        assertThat(audit.eventName()).isEqualTo("customer.reviewed");
        assertThat(audit.data()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "actor", new RailixValue.StringValue("developer"),
                "channel", new RailixValue.StringValue("manual")
        )));
        assertThat(Files.readString(record.signalLog().file())).contains("\"type\":\"audit.emitted\"");
    }

    @Test
    void shouldFailRunWhenStepEmitsBlankAuditEventName() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "audit-step",
                        List.of(new AppPlan.StepInvocation("audit-step", "test.audit-step", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-18audit-blank", tempDir, envelope(false)),
                use -> step(
                        "test.audit-step",
                        Map.of(),
                        List.of(),
                        PermissionSet.none(),
                        1,
                        Duration.ZERO,
                        input -> {
                            input.audits().emit(
                                    " ",
                                    new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer")))
                            );
                            return new Step.Result("ok", List.of());
                        }
                )
        );

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "step.failed",
                "run.finished"
        );
    }

    @Test
    void shouldReEmitAuditSignalOnRetryAttempts() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final AtomicInteger attempts = new AtomicInteger();

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "audit-step",
                        List.of(new AppPlan.StepInvocation("audit-step", "test.audit-step", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-18audit-retry", tempDir, envelope(false)),
                use -> step(
                        "test.audit-step",
                        Map.of(),
                        List.of(),
                        PermissionSet.none(),
                        2,
                        Duration.ZERO,
                        input -> {
                            input.audits().emit(
                                    "customer.reviewed",
                                    new RailixValue.ObjectValue(Map.of(
                                            "attempt", new RailixValue.NumberValue(BigDecimal.valueOf(attempts.incrementAndGet()))
                                    ))
                            );
                            if (attempts.get() == 1) {
                                throw new RuntimeException("retry after audit");
                            }
                            return new Step.Result("ok", List.of());
                        }
                )
        );

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "audit.emitted",
                "step.failed",
                "step.started",
                "audit.emitted",
                "step.finished",
                "metric.emitted",
                "metric.emitted",
                "reply.produced",
                "run.finished"
        );
        final RunSignal.AuditEmitted firstAudit = (RunSignal.AuditEmitted) allSignals(record).get(2);
        final RunSignal.AuditEmitted secondAudit = (RunSignal.AuditEmitted) allSignals(record).get(5);
        assertThat(firstAudit.attemptNumber()).isEqualTo(1);
        assertThat(firstAudit.data()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "attempt", new RailixValue.NumberValue(BigDecimal.ONE)
        )));
        assertThat(secondAudit.attemptNumber()).isEqualTo(2);
        assertThat(secondAudit.data()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "attempt", new RailixValue.NumberValue(BigDecimal.valueOf(2))
        )));
    }

    @Test
    void shouldFailRunWhenStepEmitsUndeclaredMetric() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "metric-step",
                        List.of(new AppPlan.StepInvocation("metric-step", "test.metric-step", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-18c", tempDir, envelope(false)),
                use -> step(
                        "test.metric-step",
                        Map.of(),
                        List.of(),
                        PermissionSet.none(),
                        1,
                        Duration.ZERO,
                        List.of(new StepContract.MetricDefinition("declared.metric", "gauge", "count", List.of())),
                        input -> {
                            input.metrics().emit(
                                    "custom.duration",
                                    new RailixValue.NumberValue(BigDecimal.valueOf(12)),
                                    Map.of()
                            );
                            return new Step.Result("ok", List.of());
                        }
                )
        );

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "step.failed",
                "run.finished"
        );
        assertThat(((RunSignal.StepFailed) allSignals(record).get(2)).errorSummary().toString())
                .contains("Metric is not declared by the step contract");
    }

    @Test
    void shouldFailRunWhenStepEmitsUndeclaredMetricLabel() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "metric-step",
                        List.of(new AppPlan.StepInvocation("metric-step", "test.metric-step", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-18d", tempDir, envelope(false)),
                use -> step(
                        "test.metric-step",
                        Map.of(),
                        List.of(),
                        PermissionSet.none(),
                        1,
                        Duration.ZERO,
                        List.of(new StepContract.MetricDefinition("custom.duration", "duration", "ms", List.of("phase"))),
                        input -> {
                            input.metrics().emit(
                                    "custom.duration",
                                    new RailixValue.NumberValue(BigDecimal.valueOf(12)),
                                    Map.of("unexpected", "transform")
                            );
                            return new Step.Result("ok", List.of());
                        }
                )
        );

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "step.failed",
                "run.finished"
        );
        assertThat(((RunSignal.StepFailed) allSignals(record).get(2)).errorSummary().toString())
                .contains("Metric label is not declared by the step contract");
    }

    @Test
    void shouldFailRunWhenStepDeclaresDuplicateMetricDefinitions() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "metric-step",
                        List.of(new AppPlan.StepInvocation("metric-step", "test.metric-step", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-18e", tempDir, envelope(false)),
                use -> step(
                        "test.metric-step",
                        Map.of(),
                        List.of(),
                        PermissionSet.none(),
                        1,
                        Duration.ZERO,
                        List.of(
                                new StepContract.MetricDefinition("custom.duration", "duration", "ms", List.of()),
                                new StepContract.MetricDefinition("custom.duration", "duration", "ms", List.of())
                        ),
                        input -> {
                            input.metrics().emit(
                                    "custom.duration",
                                    new RailixValue.NumberValue(BigDecimal.valueOf(12)),
                                    Map.of()
                            );
                            return new Step.Result("ok", List.of());
                        }
                )
        );

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "step.failed",
                "run.finished"
        );
        assertThat(((RunSignal.StepFailed) allSignals(record).get(2)).errorSummary().toString())
                .contains("Duplicate metric definition declared");
    }

    @Test
    void shouldCloseRegisteredResourcesAfterSuccessfulRun() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final List<String> events = new ArrayList<>();

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "resource-ok",
                        List.of(new AppPlan.StepInvocation("resource-ok", "test.resource-ok", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-19", tempDir, envelope(false)),
                use -> step(
                        "test.resource-ok",
                        Map.of(),
                        input -> {
                            final Step.RegisteredResource resource = input.resources().register(
                                    "temp.file",
                                    new RailixValue.ObjectValue(Map.of("name", new RailixValue.StringValue("artifact.txt"))),
                                    () -> events.add("close-success")
                            );
                            events.add("step-returned");
                            return new Step.Result(
                                    "ok",
                                    List.of(new Patch.Set(
                                            RailixPath.parse("ctx.resourceRef"),
                                            new Patch.LiteralSource(new RailixValue.StringValue(resource.refId()))
                                    ))
                            );
                        }
                )
        );
        events.add("after-run");

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "resource.created",
                "context.patched",
                "step.finished",
                "reply.produced",
                "run.finished"
        );
        final RunSignal.ResourceCreated resourceCreated = (RunSignal.ResourceCreated) allSignals(record).get(2);
        assertThat(resourceCreated.refType()).isEqualTo("temp.file");
        assertThat(resourceCreated.metadata()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "name", new RailixValue.StringValue("artifact.txt")
        )));
        assertThat(record.context().get(RailixPath.parse("ctx.resourceRef")))
                .isEqualTo(new RailixValue.StringValue(resourceCreated.refId()));
        assertThat(events).containsExactly("step-returned", "close-success", "after-run");
    }

    @Test
    void shouldEmitMetadataOnlyResourcesWithoutAddingCleanupWork() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final List<String> events = new ArrayList<>();

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "resource-metadata-only",
                        List.of(new AppPlan.StepInvocation("resource-metadata-only", "test.resource-metadata-only", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-19b", tempDir, envelope(false)),
                use -> step(
                        "test.resource-metadata-only",
                        Map.of(),
                        input -> {
                            final Step.RegisteredResource provenanceResource = input.resources().registerMetadata(
                                    "durable.file",
                                    new RailixValue.ObjectValue(Map.of("name", new RailixValue.StringValue("artifact.json")))
                            );
                            input.resources().register(
                                    "temp.file",
                                    new RailixValue.ObjectValue(Map.of("name", new RailixValue.StringValue("scratch.tmp"))),
                                    () -> events.add("close-owned")
                            );
                            events.add("step-returned");
                            return new Step.Result(
                                    "ok",
                                    List.of(new Patch.Set(
                                            RailixPath.parse("ctx.provenanceRef"),
                                            new Patch.LiteralSource(new RailixValue.StringValue(provenanceResource.refId()))
                                    ))
                            );
                        }
                )
        );
        events.add("after-run");

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "resource.created",
                "resource.created",
                "context.patched",
                "step.finished",
                "reply.produced",
                "run.finished"
        );
        final RunSignal.ResourceCreated provenanceResource = (RunSignal.ResourceCreated) allSignals(record).get(2);
        final RunSignal.ResourceCreated ownedResource = (RunSignal.ResourceCreated) allSignals(record).get(3);
        assertThat(provenanceResource.refType()).isEqualTo("durable.file");
        assertThat(ownedResource.refType()).isEqualTo("temp.file");
        assertThat(record.context().get(RailixPath.parse("ctx.provenanceRef")))
                .isEqualTo(new RailixValue.StringValue(provenanceResource.refId()));
        assertThat(events).containsExactly("step-returned", "close-owned", "after-run");
    }

    @Test
    void shouldCloseRegisteredResourcesWhenTerminalReplyValidationFails() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final List<String> events = new ArrayList<>();

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "resource-fail",
                        List.of(new AppPlan.StepInvocation("resource-fail", "test.resource-fail", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-20", tempDir, envelope(false)),
                use -> step(
                        "test.resource-fail",
                        Map.of(),
                        input -> {
                            input.resources().register(
                                    "temp.file",
                                    new RailixValue.ObjectValue(Map.of("name", new RailixValue.StringValue("artifact.txt"))),
                                    () -> events.add("close-failure")
                            );
                            events.add("step-returned");
                            return new Step.Result(
                                    "ok",
                                    List.of(),
                                    new Reply(
                                            Reply.Mode.IMMEDIATE,
                                            new RailixValue.NumberValue(BigDecimal.valueOf(200)),
                                            new RailixValue.ObjectValue(Map.of()),
                                            new RailixValue.StringValue("hi"),
                                            RailixValue.NULL,
                                            RailixValue.NULL,
                                            RailixValue.NULL,
                                            RailixValue.NULL
                                    )
                            );
                        }
                )
        );
        events.add("after-run");

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "resource.created",
                "step.finished",
                "run.finished"
        );
        assertThat(events).containsExactly("step-returned", "close-failure", "after-run");
    }

    @Test
    void shouldCloseFailedAttemptResourcesBeforeRetryAndClosePromotedResourcesAtRunEnd() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final AtomicInteger attempts = new AtomicInteger();
        final List<String> events = new ArrayList<>();

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "resource-retry",
                        List.of(new AppPlan.StepInvocation("resource-retry", "test.resource-retry", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-21", tempDir, envelope(false)),
                use -> step(
                        "test.resource-retry",
                        Map.of(),
                        List.of(),
                        PermissionSet.none(),
                        2,
                        Duration.ZERO,
                        input -> {
                            if (attempts.incrementAndGet() == 1) {
                                input.resources().register(
                                        "temp.file",
                                        new RailixValue.ObjectValue(Map.of("name", new RailixValue.StringValue("attempt-1.txt"))),
                                        () -> events.add("close-a")
                                );
                                events.add("throw-a");
                                throw new RuntimeException("retry");
                            }
                            input.resources().register(
                                    "temp.file",
                                    new RailixValue.ObjectValue(Map.of("name", new RailixValue.StringValue("attempt-2.txt"))),
                                    () -> events.add("close-b")
                            );
                            events.add("return-b");
                            return new Step.Result("ok", List.of());
                        }
                )
        );
        events.add("after-run");

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "resource.created",
                "step.failed",
                "step.started",
                "resource.created",
                "step.finished",
                "metric.emitted",
                "metric.emitted",
                "reply.produced",
                "run.finished"
        );
        final RunSignal.ResourceCreated firstResource = (RunSignal.ResourceCreated) allSignals(record).get(2);
        final RunSignal.ResourceCreated secondResource = (RunSignal.ResourceCreated) allSignals(record).get(5);
        assertThat(firstResource.attemptNumber()).isEqualTo(1);
        assertThat(secondResource.attemptNumber()).isEqualTo(2);
        assertThat(events).containsExactly("throw-a", "close-a", "return-b", "close-b", "after-run");
    }

    @Test
    void shouldFailWithoutRetryWhenAttemptCleanupFailsAndExposeCleanupError() {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final AtomicInteger attempts = new AtomicInteger();
        final List<String> events = new ArrayList<>();

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "cleanup-fail",
                        List.of(new AppPlan.StepInvocation("cleanup-fail", "test.cleanup-fail", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-24", tempDir, envelope(false)),
                use -> step(
                        "test.cleanup-fail",
                        Map.of(),
                        List.of(),
                        PermissionSet.none(),
                        2,
                        Duration.ZERO,
                        input -> {
                            attempts.incrementAndGet();
                            input.resources().register(
                                    "temp.file",
                                    new RailixValue.ObjectValue(Map.of("name", new RailixValue.StringValue("attempt-cleanup.txt"))),
                                    () -> {
                                        events.add("close-attempt");
                                        throw new IllegalStateException("close boom");
                                    }
                            );
                            events.add("throw-attempt");
                            throw new RuntimeException("retry");
                        }
                )
        );
        events.add("after-run");

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "resource.created",
                "step.failed",
                "run.finished"
        );
        final RunSignal.StepFailed stepFailed = (RunSignal.StepFailed) allSignals(record).get(3);
        final RailixValue.ObjectValue errorSummary = (RailixValue.ObjectValue) stepFailed.errorSummary();
        assertThat(errorSummary.values()).containsEntry("type", new RailixValue.StringValue(RuntimeException.class.getName()));
        assertThat(errorSummary.values()).containsEntry("message", new RailixValue.StringValue("Failed to close resource: run-24-res-1"));
        assertThat(events).containsExactly("throw-attempt", "close-attempt", "after-run");
    }

    @Test
    void shouldFailRunWhenPromotedResourceCleanupFailsWithoutReplyProducedArtifact() throws Exception {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final List<String> events = new ArrayList<>();

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "resource-close-fail",
                        List.of(new AppPlan.StepInvocation("resource-close-fail", "test.resource-close-fail", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-25", tempDir, envelope(false)),
                use -> step(
                        "test.resource-close-fail",
                        Map.of(),
                        input -> {
                            input.resources().register(
                                    "temp.file",
                                    new RailixValue.ObjectValue(Map.of("name", new RailixValue.StringValue("late-close.txt"))),
                                    () -> {
                                        events.add("close-run-resource");
                                        throw new IllegalStateException("close boom");
                                    }
                            );
                            events.add("step-returned");
                            return new Step.Result(
                                    "ok",
                                    List.of(new Patch.Set(
                                            RailixPath.parse("ctx.closeAttempted"),
                                            new Patch.LiteralSource(new RailixValue.BoolValue(true))
                                    ))
                            );
                        }
                )
        );
        events.add("after-run");

        final String summary = Files.readString(record.runFolder().resolve("summary.json"));

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(record.reply()).isEqualTo(Reply.none());
        assertThat(record.context().get(RailixPath.parse("ctx.closeAttempted")))
                .isEqualTo(new RailixValue.BoolValue(true));
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "resource.created",
                "context.patched",
                "step.finished",
                "run.finished"
        );
        assertThat(summary).contains("\"phase\":\"run.cleanup\"");
        assertThat(summary).contains("\"message\":\"Failed to close resource: run-25-res-1\"");
        assertThat(summary).contains("\"outcome\":\"failed\"");
        assertThat(summary).contains("\"replyMode\":\"none\"");
        assertThat(summary).contains("\"signalCount\":6");
        assertThat(events).containsExactly("step-returned", "close-run-resource", "after-run");
    }

    @Test
    void shouldAttemptAllRunResourceClosuresInReverseOrderWhenCleanupFails() throws Exception {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final List<String> events = new ArrayList<>();

        final LocalExecutionKernel.RunRecord record = kernel.run(
                new AppPlan(
                        "railix-app",
                        "order-flow",
                        "resource-close-fail-multi",
                        List.of(new AppPlan.StepInvocation("resource-close-fail-multi", "test.resource-close-fail-multi", Map.of()))
                ),
                new LocalExecutionKernel.RunRequest("run-26", tempDir, envelope(false)),
                use -> step(
                        "test.resource-close-fail-multi",
                        Map.of(),
                        input -> {
                            input.resources().register(
                                    "temp.file",
                                    new RailixValue.ObjectValue(Map.of("name", new RailixValue.StringValue("first.txt"))),
                                    () -> {
                                        events.add("close-first");
                                        throw new IllegalStateException("first close boom");
                                    }
                            );
                            input.resources().register(
                                    "temp.file",
                                    new RailixValue.ObjectValue(Map.of("name", new RailixValue.StringValue("second.txt"))),
                                    () -> {
                                        events.add("close-second");
                                        throw new IllegalStateException("second close boom");
                                    }
                            );
                            events.add("step-returned");
                            return new Step.Result("ok", List.of());
                        }
                )
        );
        events.add("after-run");

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(allSignals(record).stream().map(RunSignal::type)).containsExactly(
                "run.started",
                "step.started",
                "resource.created",
                "resource.created",
                "step.finished",
                "run.finished"
        );
        assertThat(Files.readString(record.runFolder().resolve("summary.json")))
                .contains("\"phase\":\"run.cleanup\"")
                .contains("\"suppressed\":[{\"message\":\"Failed to close resource: run-26-res-1\"");
        assertThat(events).containsExactly("step-returned", "close-second", "close-first", "after-run");
    }

    @Test
    void shouldWriteSummarySignalCountFromPersistedSignalLog() throws Exception {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final AppPlan plan = new AppPlan(
                "railix-app",
                "order-flow",
                "normalize",
                List.of(
                        new AppPlan.StepInvocation("normalize", "test.normalize", Map.of("ok", "reply")),
                        new AppPlan.StepInvocation("reply", "test.reply", Map.of())
                )
        );

        final LocalExecutionKernel.RunRecord record = kernel.run(
                plan,
                new LocalExecutionKernel.RunRequest("run-22", tempDir, envelope(true)),
                use -> switch (use) {
                    case "test.normalize" -> step("test.normalize", Map.of("ok", "done"), input -> new Step.Result(
                            "ok",
                            List.of(new Patch.Set(
                                    RailixPath.parse("ctx.customer.email"),
                                    new Patch.ExpressionSource(new Patch.PathExpression(RailixPath.parse("payload.customer.email")))
                            ))
                    ));
                    case "test.reply" -> step("test.reply", Map.of(), input -> new Step.Result(
                            "ok",
                            List.of(),
                            new Reply(
                                    Reply.Mode.IMMEDIATE,
                                    new RailixValue.NumberValue(BigDecimal.valueOf(201)),
                                    new RailixValue.ObjectValue(Map.of("contentType", new RailixValue.StringValue("application/json"))),
                                    new RailixValue.ObjectValue(Map.of(
                                            "customerEmail", input.context().get(RailixPath.parse("ctx.customer.email"))
                                    )),
                                    RailixValue.NULL,
                                    RailixValue.NULL,
                                    RailixValue.NULL,
                                    RailixValue.NULL
                            )
                    ));
                    default -> null;
                }
        );

        final long persistedSignals = Files.lines(record.signalLog().file()).count();
        final String summary = Files.readString(record.runFolder().resolve("summary.json"));

        assertThat(record.outcome()).isEqualTo("ok");
        assertThat(record.signalLog().signalCount()).isEqualTo(8);
        assertThat(persistedSignals).isEqualTo(record.signalLog().signalCount());
        assertThat(summary).contains("\"signalCount\":8");
        assertThat(summary).contains("\"replyMode\":\"immediate\"");
    }

    @Test
    void shouldWriteFailedSummarySignalCountFromPersistedSignalLog() throws Exception {
        final LocalExecutionKernel kernel = new LocalExecutionKernel(Clock.fixed(Instant.parse("2026-06-27T14:00:00Z"), ZoneOffset.UTC));
        final AppPlan plan = new AppPlan(
                "railix-app",
                "order-flow",
                "explode",
                List.of(new AppPlan.StepInvocation("explode", "test.explode", Map.of()))
        );

        final LocalExecutionKernel.RunRecord record = kernel.run(
                plan,
                new LocalExecutionKernel.RunRequest("run-23", tempDir, envelope(true)),
                use -> step("test.explode", Map.of("ok", "done"), input -> {
                    throw new IllegalStateException("boom");
                })
        );

        final long persistedSignals;
        try (var lines = Files.lines(record.signalLog().file())) {
            persistedSignals = lines.count();
        }
        final String summary = Files.readString(record.runFolder().resolve("summary.json"));

        assertThat(record.outcome()).isEqualTo("failed");
        assertThat(record.signalLog().signalCount()).isEqualTo(4);
        assertThat(persistedSignals).isEqualTo(record.signalLog().signalCount());
        assertThat(summary).contains("\"signalCount\":4");
        assertThat(summary).contains("\"replyMode\":\"none\"");
    }

    @Test
    void shouldRejectInvalidRunRequestAndRunRecordFields() {
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope(true));
        final RunSignalLog signalLog = new RunSignalLog(tempDir.resolve("signals.ndjson"));

        assertThatThrownBy(() -> new LocalExecutionKernel.RunRequest(" ", tempDir, envelope(true)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
        assertThatThrownBy(() -> new LocalExecutionKernel.RunRequest("run-8", null, envelope(true)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("runsRoot");
        assertThatThrownBy(() -> new LocalExecutionKernel.RunRequest("run-8", tempDir, envelope(true), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("settingsTree");
        assertThatThrownBy(() -> new LocalExecutionKernel.RunRecord(
                " ",
                "order-flow",
                "run-8",
                "ok",
                tempDir,
                context,
                Reply.none(),
                signalLog,
                0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appId");
        assertThatThrownBy(() -> new LocalExecutionKernel.RunRecord(
                "railix-app",
                "order-flow",
                "run-8",
                "ok",
                tempDir,
                context,
                Reply.none(),
                signalLog,
                -1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executedSteps");
    }

    private static List<RunSignal> allSignals(final LocalExecutionKernel.RunRecord record) {
        final List<RunSignal> signals = new ArrayList<>();
        record.signalLog().forEachSignal(signals::add);
        return List.copyOf(signals);
    }

    private static Envelope envelope(final boolean repliesSupported) {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "customer", new RailixValue.ObjectValue(Map.of(
                                "email", new RailixValue.StringValue("USER@EXAMPLE.COM")
                        ))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(repliesSupported, repliesSupported ? List.of(Reply.Mode.IMMEDIATE) : List.of())
        );
    }

    private static Step step(
            final String id,
            final Map<String, String> outcomes,
            final java.util.function.Function<Step.ExecutionInput, Step.Result> executor
    ) {
        return step(id, outcomes, List.of(), PermissionSet.none(), 1, Duration.ZERO, List.of(), executor);
    }

    private static Step step(
            final String id,
            final Map<String, String> outcomes,
            final List<String> requestedSettings,
            final PermissionSet permissions,
            final java.util.function.Function<Step.ExecutionInput, Step.Result> executor
    ) {
        return step(id, outcomes, requestedSettings, permissions, 1, Duration.ZERO, List.of(), executor);
    }

    private static Step step(
            final String id,
            final Map<String, String> outcomes,
            final List<String> requestedSettings,
            final PermissionSet permissions,
            final int maxAttempts,
            final Duration backoff,
            final java.util.function.Function<Step.ExecutionInput, Step.Result> executor
    ) {
        return step(id, outcomes, requestedSettings, permissions, maxAttempts, backoff, List.of(), executor);
    }

    private static Step step(
            final String id,
            final Map<String, String> outcomes,
            final List<String> requestedSettings,
            final PermissionSet permissions,
            final int maxAttempts,
            final Duration backoff,
            final List<StepContract.MetricDefinition> emittedMetrics,
            final java.util.function.Function<Step.ExecutionInput, Step.Result> executor
    ) {
        return new Step() {
            @Override
            public StepContract contract() {
                return new StepContract(
                        id,
                        "0.1.0",
                        id,
                        id,
                        StepContract.Kind.NORMAL,
                        List.of(),
                        List.of(),
                        outcomes.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> new StepContract.Outcome(entry.getValue()))),
                        new StepContract.Settings(requestedSettings),
                        permissions,
                        new StepContract.Timeout(java.time.Duration.ofSeconds(30)),
                        new StepContract.RetryPolicy(maxAttempts, backoff),
                        new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", java.time.Duration.ZERO),
                        new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                        new StepContract.Metrics(emittedMetrics),
                        Map.of()
                );
            }

            @Override
            public Step.Result execute(final Step.ExecutionInput input) {
                return executor.apply(input);
            }
        };
    }

    private static PermissionSet permissionSet(
            final Map<String, List<String>> requested,
            final Map<String, List<String>> granted
    ) {
        return new PermissionSet(requested, granted, List.of());
    }
}
