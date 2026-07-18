package dev.nanonative.railix.kernel.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsTreeTest {

    @Test
    void shouldExposeDefaultScopesAndPrecedence() {
        final SettingsTree tree = SettingsTree.empty();

        assertThat(tree.description()).isEqualTo("Unified config and secrets tree.");
        assertThat(tree.entries()).isEmpty();
        assertThat(tree.scopes()).containsExactly(
                SettingsTree.Scope.APP,
                SettingsTree.Scope.ENVIRONMENT,
                SettingsTree.Scope.FLOW,
                SettingsTree.Scope.GROUP,
                SettingsTree.Scope.STEP,
                SettingsTree.Scope.RUN
        );
        assertThat(tree.precedence()).containsExactly(
                SettingsTree.SourceLayer.PACKAGED_DEFAULTS,
                SettingsTree.SourceLayer.ENVIRONMENT_DEFAULTS,
                SettingsTree.SourceLayer.SETTINGS_FILE,
                SettingsTree.SourceLayer.ENCRYPTED_SETTINGS_FILE,
                SettingsTree.SourceLayer.ENVIRONMENT_VARIABLES,
                SettingsTree.SourceLayer.SYSTEM_PROPERTIES,
                SettingsTree.SourceLayer.CLI_ARGS,
                SettingsTree.SourceLayer.MANUAL_RUN_INPUT
        );
    }

    @Test
    void shouldCopyEntriesAndRetainEntryMetadata() {
        final Map<RailixPath, SettingsTree.Entry> entries = new HashMap<>(Map.of(
                RailixPath.parse("settings.database.password"),
                new SettingsTree.Entry(
                        RailixPath.parse("settings.database.password"),
                        "string",
                        new SettingsTree.EncryptedValue("ENC[abc]"),
                        true,
                        true,
                        true,
                        "settings/dev.sops.yaml",
                        SettingsTree.Visibility.HIDDEN,
                        SettingsTree.Audit.ON_MATERIALIZE,
                        SettingsTree.OverridePolicy.TRUSTED_ONLY
                )
        ));

        final SettingsTree tree = new SettingsTree(
                "Settings for the app.",
                entries,
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );

        entries.clear();

        assertThat(tree.entries()).hasSize(1);
        assertThat(tree.entries().values().iterator().next().audit()).isEqualTo(SettingsTree.Audit.ON_MATERIALIZE);
        assertThatThrownBy(() -> tree.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldKeepSecretMetadataStickyWhenOverlaying() {
        final RailixPath path = RailixPath.parse("settings.database.password");
        final SettingsTree baseTree = new SettingsTree(
                "Base settings",
                Map.of(
                        path,
                        new SettingsTree.Entry(
                                path,
                                "string",
                                new SettingsTree.EncryptedValue("ENC[base]"),
                                true,
                                true,
                                true,
                                "settings/base.sops.yaml",
                                SettingsTree.Visibility.HIDDEN,
                                SettingsTree.Audit.ON_MATERIALIZE,
                                SettingsTree.OverridePolicy.TRUSTED_ONLY
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.PACKAGED_DEFAULTS)
        );
        final SettingsTree overrideTree = new SettingsTree(
                "Override settings",
                Map.of(
                        path,
                        new SettingsTree.Entry(
                                path,
                                "string",
                                new SettingsTree.PlainValue(new RailixValue.StringValue("dev-password")),
                                false,
                                false,
                                false,
                                "settings/dev.yaml",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.NEVER,
                                SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );

        final SettingsTree merged = baseTree.overlay(overrideTree);
        final SettingsTree.Entry mergedEntry = merged.entries().get(path);

        assertThat(mergedEntry.secret()).isTrue();
        assertThat(mergedEntry.encrypted()).isTrue();
        assertThat(mergedEntry.visibility()).isEqualTo(SettingsTree.Visibility.HIDDEN);
        assertThat(mergedEntry.audit()).isEqualTo(SettingsTree.Audit.ON_MATERIALIZE);
        assertThat(mergedEntry.overridePolicy()).isEqualTo(SettingsTree.OverridePolicy.TRUSTED_ONLY);
    }

    @Test
    void shouldExcludeSecretSettingsFromCacheKeys() {
        final SettingsTree tree = new SettingsTree(
                "Cache key test",
                Map.of(
                        RailixPath.parse("settings.http.port"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.http.port"),
                                "int",
                                new SettingsTree.PlainValue(new RailixValue.NumberValue(java.math.BigDecimal.valueOf(8080))),
                                true,
                                false,
                                false,
                                "settings/app.yaml",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.NEVER,
                                SettingsTree.OverridePolicy.ALLOW
                        ),
                        RailixPath.parse("settings.database.password"),
                        new SettingsTree.Entry(
                                RailixPath.parse("settings.database.password"),
                                "string",
                                new SettingsTree.EncryptedValue("ENC[pwd]"),
                                true,
                                true,
                                true,
                                "settings/prod.sops.yaml",
                                SettingsTree.Visibility.HIDDEN,
                                SettingsTree.Audit.ON_MATERIALIZE,
                                SettingsTree.OverridePolicy.TRUSTED_ONLY
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );

        assertThat(tree.cacheKeyEntries()).containsOnlyKeys(RailixPath.parse("settings.http.port"));
    }

    @Test
    void shouldEmitAuditSignalWhenSecretIsMaterialized() {
        final RailixPath path = RailixPath.parse("settings.api.token");
        final SettingsTree tree = new SettingsTree(
                "Execution settings",
                Map.of(
                        path,
                        new SettingsTree.Entry(
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
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );

        final SettingsTree.ReadResult readResult = tree.readForExecution(
                        path,
                        new SettingsTree.ReadContext(
                                "sig-100",
                                Instant.parse("2026-06-27T09:30:00Z"),
                                "railix-app",
                                "order-approval",
                                "run-42",
                                "fetch-secret",
                                1
                        )
                )
                .orElseThrow();

        assertThat(readResult.materialized()).isTrue();
        assertThat(readResult.auditSignal()).isPresent();
        assertThat(readResult.auditSignal().orElseThrow().type()).isEqualTo("setting.read");
        assertThat(readResult.auditSignal().orElseThrow().secret()).isTrue();
        assertThat(readResult.auditSignal().orElseThrow().materialized()).isTrue();
    }

    @Test
    void shouldExposeUiProjectionWithoutLeakingValue() {
        final RailixPath path = RailixPath.parse("settings.database.password");
        final SettingsTree tree = new SettingsTree(
                "UI settings",
                Map.of(
                        path,
                        new SettingsTree.Entry(
                                path,
                                "string",
                                new SettingsTree.EncryptedValue("ENC[pwd]"),
                                true,
                                true,
                                true,
                                "settings/prod.sops.yaml",
                                SettingsTree.Visibility.HIDDEN,
                                SettingsTree.Audit.ON_MATERIALIZE,
                                SettingsTree.OverridePolicy.TRUSTED_ONLY
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.ENCRYPTED_SETTINGS_FILE)
        );

        final SettingsTree.UiEntry uiEntry = tree.uiEntry(path).orElseThrow();

        assertThat(uiEntry.path()).isEqualTo(path);
        assertThat(uiEntry.source()).isEqualTo("settings/prod.sops.yaml");
        assertThat(uiEntry.secret()).isTrue();
        assertThat(uiEntry.present()).isTrue();
        assertThat(uiEntry.visibility()).isEqualTo(SettingsTree.Visibility.HIDDEN);
    }

    @Test
    void shouldReturnEmptyForMissingRuntimeReadAndUiProjection() {
        final SettingsTree tree = SettingsTree.empty();
        final RailixPath missingPath = RailixPath.parse("settings.unknown.value");
        final SettingsTree.ReadContext context = new SettingsTree.ReadContext(
                "sig-101",
                Instant.parse("2026-06-27T09:35:00Z"),
                "railix-app",
                "order-approval",
                "run-42",
                "lookup-setting",
                1
        );

        assertThat(tree.readForExecution(missingPath, context)).isEmpty();
        assertThat(tree.uiEntry(missingPath)).isEmpty();
    }

    @Test
    void shouldNotEmitAuditSignalForEncryptedSettingWithoutMaterialization() {
        final RailixPath path = RailixPath.parse("settings.database.password");
        final SettingsTree tree = new SettingsTree(
                "Execution settings",
                Map.of(
                        path,
                        new SettingsTree.Entry(
                                path,
                                "string",
                                new SettingsTree.EncryptedValue("ENC[pwd]"),
                                true,
                                false,
                                true,
                                "settings/prod.sops.yaml",
                                SettingsTree.Visibility.HIDDEN,
                                SettingsTree.Audit.ON_MATERIALIZE,
                                SettingsTree.OverridePolicy.TRUSTED_ONLY
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.ENCRYPTED_SETTINGS_FILE)
        );

        final SettingsTree.ReadResult readResult = tree.readForExecution(
                        path,
                        new SettingsTree.ReadContext(
                                "sig-102",
                                Instant.parse("2026-06-27T09:40:00Z"),
                                "railix-app",
                                "order-approval",
                                "run-42",
                                "fetch-secret",
                                1
                        )
                )
                .orElseThrow();

        assertThat(readResult.materialized()).isFalse();
        assertThat(readResult.auditSignal()).isEmpty();
    }

    @Test
    void shouldEmitAuditSignalForNonSecretOnReadSetting() {
        final RailixPath path = RailixPath.parse("settings.feature.newCheckout");
        final SettingsTree tree = new SettingsTree(
                "Execution settings",
                Map.of(
                        path,
                        new SettingsTree.Entry(
                                path,
                                "boolean",
                                new SettingsTree.PlainValue(new RailixValue.BoolValue(true)),
                                true,
                                false,
                                false,
                                "settings/app.yaml",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.ON_READ,
                                SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );

        final SettingsTree.ReadResult readResult = tree.readForExecution(
                        path,
                        new SettingsTree.ReadContext(
                                "sig-103",
                                Instant.parse("2026-06-27T09:45:00Z"),
                                "railix-app",
                                "order-approval",
                                "run-42",
                                "feature-check",
                                1
                        )
                )
                .orElseThrow();

        assertThat(readResult.materialized()).isTrue();
        assertThat(readResult.auditSignal()).isPresent();
        assertThat(readResult.auditSignal().orElseThrow().secret()).isFalse();
        assertThat(readResult.auditSignal().orElseThrow().materialized()).isTrue();
    }

    @Test
    void shouldRejectInvalidReadContext() {
        assertThatThrownBy(() -> new SettingsTree.ReadContext(
                "sig-104",
                Instant.parse("2026-06-27T09:50:00Z"),
                "railix-app",
                "order-approval",
                "run-42",
                "feature-check",
                0
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attemptNumber");
    }

    @Test
    void shouldPreserveBaseDescriptionAndEscalateSecretOverlayMetadata() {
        final RailixPath path = RailixPath.parse("settings.database.password");
        final SettingsTree baseTree = new SettingsTree(
                "Base settings",
                Map.of(
                        path,
                        new SettingsTree.Entry(
                                path,
                                "string",
                                new SettingsTree.PlainValue(new RailixValue.StringValue("dev-password")),
                                false,
                                false,
                                false,
                                "settings/base.yaml",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.NEVER,
                                SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.PACKAGED_DEFAULTS)
        );
        final SettingsTree overrideTree = new SettingsTree(
                "",
                Map.of(
                        path,
                        new SettingsTree.Entry(
                                path,
                                "string",
                                new SettingsTree.EncryptedValue("ENC[pwd]"),
                                false,
                                true,
                                true,
                                "settings/prod.sops.yaml",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.NEVER,
                                SettingsTree.OverridePolicy.DENY
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.ENCRYPTED_SETTINGS_FILE)
        );

        final SettingsTree merged = baseTree.overlay(overrideTree);
        final SettingsTree.Entry entry = merged.entries().get(path);

        assertThat(merged.description()).isEqualTo("Base settings");
        assertThat(entry.secret()).isTrue();
        assertThat(entry.visibility()).isEqualTo(SettingsTree.Visibility.MASKED);
        assertThat(entry.audit()).isEqualTo(SettingsTree.Audit.ON_MATERIALIZE);
        assertThat(entry.overridePolicy()).isEqualTo(SettingsTree.OverridePolicy.DENY);
    }

    @Test
    void shouldEmitAuditSignalForAlwaysAuditWithoutMaterialization() {
        final RailixPath path = RailixPath.parse("settings.database.password");
        final SettingsTree tree = new SettingsTree(
                "Execution settings",
                Map.of(
                        path,
                        new SettingsTree.Entry(
                                path,
                                "string",
                                new SettingsTree.EncryptedValue("ENC[pwd]"),
                                true,
                                false,
                                true,
                                "settings/prod.sops.yaml",
                                SettingsTree.Visibility.HIDDEN,
                                SettingsTree.Audit.ALWAYS,
                                SettingsTree.OverridePolicy.TRUSTED_ONLY
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.ENCRYPTED_SETTINGS_FILE)
        );

        final SettingsTree.ReadResult readResult = tree.readForExecution(
                        path,
                        new SettingsTree.ReadContext(
                                "sig-105",
                                Instant.parse("2026-06-27T09:55:00Z"),
                                "railix-app",
                                "order-approval",
                                "run-42",
                                "fetch-secret",
                                1
                        )
                )
                .orElseThrow();

        assertThat(readResult.materialized()).isFalse();
        assertThat(readResult.auditSignal()).isPresent();
        assertThat(readResult.auditSignal().orElseThrow().materialized()).isFalse();
    }

    @Test
    void shouldEmitAuditSignalForSecretOnReadWithoutMaterialization() {
        final RailixPath path = RailixPath.parse("settings.database.password");
        final SettingsTree tree = new SettingsTree(
                "Execution settings",
                Map.of(
                        path,
                        new SettingsTree.Entry(
                                path,
                                "string",
                                new SettingsTree.EncryptedValue("ENC[pwd]"),
                                true,
                                true,
                                true,
                                "settings/prod.sops.yaml",
                                SettingsTree.Visibility.HIDDEN,
                                SettingsTree.Audit.ON_READ,
                                SettingsTree.OverridePolicy.TRUSTED_ONLY
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.ENCRYPTED_SETTINGS_FILE)
        );

        final SettingsTree.ReadResult readResult = tree.readForExecution(
                        path,
                        new SettingsTree.ReadContext(
                                "sig-106",
                                Instant.parse("2026-06-27T10:00:00Z"),
                                "railix-app",
                                "order-approval",
                                "run-42",
                                "fetch-secret",
                                1
                        )
                )
                .orElseThrow();

        assertThat(readResult.materialized()).isFalse();
        assertThat(readResult.auditSignal()).isPresent();
        assertThat(readResult.auditSignal().orElseThrow().secret()).isTrue();
    }

    @Test
    void shouldRejectInvalidUiEntryState() {
        assertThatThrownBy(() -> new SettingsTree.UiEntry(
                RailixPath.parse("settings.http.port"),
                " ",
                "settings/app.yaml",
                false,
                true,
                SettingsTree.Visibility.NORMAL
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");

        assertThatThrownBy(() -> new SettingsTree.UiEntry(
                RailixPath.parse("settings.http.port"),
                "int",
                " ",
                false,
                true,
                SettingsTree.Visibility.NORMAL
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    @Test
    void shouldNormalizeNullDescriptionAndSkipAuditForPlainNeverEntry() {
        final RailixPath path = RailixPath.parse("settings.http.port");
        final SettingsTree tree = new SettingsTree(
                null,
                Map.of(
                        path,
                        new SettingsTree.Entry(
                                path,
                                "int",
                                new SettingsTree.PlainValue(new RailixValue.NumberValue(java.math.BigDecimal.valueOf(8080))),
                                true,
                                false,
                                false,
                                "settings/app.yaml",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.NEVER,
                                SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );

        final SettingsTree.ReadResult readResult = tree.readForExecution(
                        path,
                        new SettingsTree.ReadContext(
                                "sig-107",
                                Instant.parse("2026-06-27T10:05:00Z"),
                                "railix-app",
                                "order-approval",
                                "run-42",
                                "load-port",
                                1
                        )
                )
                .orElseThrow();

        assertThat(tree.description()).isEmpty();
        assertThat(readResult.materialized()).isTrue();
        assertThat(readResult.auditSignal()).isEmpty();
    }

    @Test
    void shouldOnlyAuditOnMaterializeWhenValueIsActuallyMaterialized() {
        final RailixPath path = RailixPath.parse("settings.database.password");
        final SettingsTree tree = new SettingsTree(
                "Encrypted settings",
                Map.of(
                        path,
                        new SettingsTree.Entry(
                                path,
                                "string",
                                new SettingsTree.EncryptedValue("ENC[pwd]"),
                                true,
                                true,
                                true,
                                "settings/prod.sops.yaml",
                                SettingsTree.Visibility.HIDDEN,
                                SettingsTree.Audit.ON_MATERIALIZE,
                                SettingsTree.OverridePolicy.TRUSTED_ONLY
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.ENCRYPTED_SETTINGS_FILE)
        );

        final SettingsTree.ReadResult readResult = tree.readForExecution(
                        path,
                        new SettingsTree.ReadContext(
                                "sig-108",
                                Instant.parse("2026-06-27T10:10:00Z"),
                                "railix-app",
                                "order-approval",
                                "run-42",
                                "load-secret",
                                1
                        )
                )
                .orElseThrow();

        assertThat(readResult.materialized()).isFalse();
        assertThat(readResult.auditSignal()).isEmpty();
    }

    @Test
    void shouldAlwaysAuditWhenAuditPolicyIsAlways() {
        final RailixPath path = RailixPath.parse("settings.http.timeoutMs");
        final SettingsTree tree = new SettingsTree(
                "Execution settings",
                Map.of(
                        path,
                        new SettingsTree.Entry(
                                path,
                                "int",
                                new SettingsTree.PlainValue(new RailixValue.NumberValue(java.math.BigDecimal.valueOf(5000))),
                                true,
                                false,
                                false,
                                "settings/app.yaml",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.ALWAYS,
                                SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );

        final SettingsTree.ReadResult readResult = tree.readForExecution(
                        path,
                        new SettingsTree.ReadContext(
                                "sig-109",
                                Instant.parse("2026-06-27T10:15:00Z"),
                                "railix-app",
                                "order-approval",
                                "run-42",
                                "load-timeout",
                                1
                        )
                )
                .orElseThrow();

        assertThat(readResult.materialized()).isTrue();
        assertThat(readResult.auditSignal()).isPresent();
        assertThat(readResult.auditSignal().orElseThrow().type()).isEqualTo("setting.read");
    }

    @Test
    void shouldOverlayNonSecretEntriesWithoutEscalatingMetadata() {
        final RailixPath path = RailixPath.parse("settings.http.baseUrl");
        final SettingsTree baseTree = new SettingsTree(
                "Base settings",
                Map.of(
                        path,
                        new SettingsTree.Entry(
                                path,
                                "string",
                                new SettingsTree.PlainValue(new RailixValue.StringValue("https://base.example")),
                                false,
                                false,
                                false,
                                "settings/base.yaml",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.NEVER,
                                SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.SETTINGS_FILE)
        );
        final SettingsTree overrideTree = new SettingsTree(
                "Override settings",
                Map.of(
                        path,
                        new SettingsTree.Entry(
                                path,
                                "string",
                                new SettingsTree.PlainValue(new RailixValue.StringValue("https://override.example")),
                                false,
                                false,
                                false,
                                "settings/override.yaml",
                                SettingsTree.Visibility.NORMAL,
                                SettingsTree.Audit.NEVER,
                                SettingsTree.OverridePolicy.ALLOW
                        )
                ),
                List.of(SettingsTree.Scope.APP),
                List.of(SettingsTree.SourceLayer.CLI_ARGS)
        );

        final SettingsTree merged = baseTree.overlay(overrideTree);
        final SettingsTree.Entry mergedEntry = merged.entries().get(path);

        assertThat(merged.description()).isEqualTo("Override settings");
        assertThat(mergedEntry.required()).isFalse();
        assertThat(mergedEntry.secret()).isFalse();
        assertThat(mergedEntry.encrypted()).isFalse();
        assertThat(mergedEntry.visibility()).isEqualTo(SettingsTree.Visibility.NORMAL);
        assertThat(mergedEntry.audit()).isEqualTo(SettingsTree.Audit.NEVER);
        assertThat(mergedEntry.overridePolicy()).isEqualTo(SettingsTree.OverridePolicy.ALLOW);
        assertThat(((SettingsTree.PlainValue) mergedEntry.value()).value()).isEqualTo(new RailixValue.StringValue("https://override.example"));
    }
}
