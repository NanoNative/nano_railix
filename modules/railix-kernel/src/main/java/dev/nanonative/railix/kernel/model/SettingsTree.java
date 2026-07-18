package dev.nanonative.railix.kernel.model;

import dev.nanonative.railix.kernel.runtime.RunSignal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record SettingsTree(
        String description,
        Map<RailixPath, Entry> entries,
        List<Scope> scopes,
        List<SourceLayer> precedence
) {
    public SettingsTree {
        description = description == null ? "" : description;
        entries = Map.copyOf(entries);
        scopes = List.copyOf(scopes);
        precedence = List.copyOf(precedence);
    }

    public record Entry(
            RailixPath path,
            String type,
            Value value,
            boolean required,
            boolean secret,
            boolean encrypted,
            String source,
            Visibility visibility,
            Audit audit,
            OverridePolicy overridePolicy
    ) {}

    public sealed interface Value permits PlainValue, EncryptedValue, ReferenceValue {}

    public record PlainValue(RailixValue value) implements Value {}

    public record EncryptedValue(String cipherText) implements Value {}

    public record ReferenceValue(RailixValue value) implements Value {}

    public enum Scope {
        APP,
        ENVIRONMENT,
        FLOW,
        GROUP,
        STEP,
        RUN
    }

    public enum SourceLayer {
        PACKAGED_DEFAULTS,
        ENVIRONMENT_DEFAULTS,
        SETTINGS_FILE,
        ENCRYPTED_SETTINGS_FILE,
        ENVIRONMENT_VARIABLES,
        SYSTEM_PROPERTIES,
        CLI_ARGS,
        MANUAL_RUN_INPUT
    }

    public enum Visibility {
        NORMAL,
        MASKED,
        HIDDEN
    }

    public enum Audit {
        NEVER,
        ON_READ,
        ON_MATERIALIZE,
        ALWAYS
    }

    public enum OverridePolicy {
        ALLOW,
        DENY,
        TRUSTED_ONLY
    }

    public static SettingsTree empty() {
        return new SettingsTree(
                "Unified config and secrets tree.",
                Map.of(),
                List.of(Scope.APP, Scope.ENVIRONMENT, Scope.FLOW, Scope.GROUP, Scope.STEP, Scope.RUN),
                List.of(
                        SourceLayer.PACKAGED_DEFAULTS,
                        SourceLayer.ENVIRONMENT_DEFAULTS,
                        SourceLayer.SETTINGS_FILE,
                        SourceLayer.ENCRYPTED_SETTINGS_FILE,
                        SourceLayer.ENVIRONMENT_VARIABLES,
                        SourceLayer.SYSTEM_PROPERTIES,
                        SourceLayer.CLI_ARGS,
                        SourceLayer.MANUAL_RUN_INPUT
                )
        );
    }

    /**
     * Overlays another settings tree while preserving sticky secret metadata from existing entries.
     *
     * @param overrides tree with higher-precedence entries
     * @return merged tree
     */
    public SettingsTree overlay(final SettingsTree overrides) {
        final Map<RailixPath, Entry> mergedEntries = new LinkedHashMap<>(entries);
        overrides.entries.forEach((path, overrideEntry) -> mergedEntries.merge(path, overrideEntry, SettingsTree::mergeEntry));
        final String mergedDescription = overrides.description.isBlank() ? description : overrides.description;
        return new SettingsTree(mergedDescription, mergedEntries, scopes, precedence);
    }

    /**
     * Returns the settings that are safe to contribute to cache keys.
     *
     * @return non-secret settings only
     */
    public Map<RailixPath, Entry> cacheKeyEntries() {
        final Map<RailixPath, Entry> cacheSafeEntries = new LinkedHashMap<>();
        entries.forEach((path, entry) -> {
            if (!entry.secret()) {
                cacheSafeEntries.put(path, entry);
            }
        });
        return Map.copyOf(cacheSafeEntries);
    }

    /**
     * Reads a setting for runtime use and emits a `setting.read` signal when the entry requires auditing.
     *
     * @param path setting path
     * @param context audit signal context
     * @return read result when the setting exists
     */
    public Optional<ReadResult> readForExecution(final RailixPath path, final ReadContext context) {
        final Entry entry = entries.get(path);
        if (entry == null) {
            return Optional.empty();
        }
        final boolean materialized = !(entry.value() instanceof EncryptedValue);
        final Optional<RunSignal.SettingRead> auditSignal = shouldAudit(entry, materialized)
                ? Optional.of(new RunSignal.SettingRead(
                context.signalId(),
                context.timestamp(),
                context.appId(),
                context.flowId(),
                context.runId(),
                context.stepId(),
                context.attemptNumber(),
                path,
                entry.secret(),
                materialized
        ))
                : Optional.empty();
        return Optional.of(new ReadResult(entry, materialized, auditSignal));
    }

    /**
     * Returns a UI-safe projection that reports presence and source without exposing the setting value.
     *
     * @param path setting path
     * @return UI projection when the setting exists
     */
    public Optional<UiEntry> uiEntry(final RailixPath path) {
        final Entry entry = entries.get(path);
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(new UiEntry(
                entry.path(),
                entry.type(),
                entry.source(),
                entry.secret(),
                true,
                entry.visibility()
        ));
    }

    private static Entry mergeEntry(final Entry baseEntry, final Entry overrideEntry) {
        final boolean secret = baseEntry.secret() || overrideEntry.secret();
        final boolean encrypted = baseEntry.encrypted() || overrideEntry.encrypted();
        return new Entry(
                overrideEntry.path(),
                overrideEntry.type(),
                overrideEntry.value(),
                baseEntry.required() || overrideEntry.required(),
                secret,
                encrypted,
                overrideEntry.source(),
                stricterVisibility(baseEntry.visibility(), overrideEntry.visibility(), secret),
                stricterAudit(baseEntry.audit(), overrideEntry.audit(), secret),
                stricterOverridePolicy(baseEntry.overridePolicy(), overrideEntry.overridePolicy())
        );
    }

    private static Visibility stricterVisibility(
            final Visibility baseVisibility,
            final Visibility overrideVisibility,
            final boolean secret
    ) {
        final Visibility strictest = baseVisibility.ordinal() > overrideVisibility.ordinal() ? baseVisibility : overrideVisibility;
        if (secret && strictest == Visibility.NORMAL) {
            return Visibility.MASKED;
        }
        return strictest;
    }

    private static Audit stricterAudit(final Audit baseAudit, final Audit overrideAudit, final boolean secret) {
        final Audit strictest = baseAudit.ordinal() > overrideAudit.ordinal() ? baseAudit : overrideAudit;
        if (secret && strictest.ordinal() < Audit.ON_MATERIALIZE.ordinal()) {
            return Audit.ON_MATERIALIZE;
        }
        return strictest;
    }

    private static OverridePolicy stricterOverridePolicy(
            final OverridePolicy basePolicy,
            final OverridePolicy overridePolicy
    ) {
        return basePolicy.ordinal() > overridePolicy.ordinal() ? basePolicy : overridePolicy;
    }

    private static boolean shouldAudit(final Entry entry, final boolean materialized) {
        if (entry.secret() && materialized) {
            return true;
        }
        return switch (entry.audit()) {
            case NEVER -> false;
            case ON_READ, ALWAYS -> true;
            case ON_MATERIALIZE -> materialized;
        };
    }

    public record ReadContext(
            String signalId,
            Instant timestamp,
            String appId,
            String flowId,
            String runId,
            String stepId,
            int attemptNumber
    ) {
        public ReadContext {
            signalId = requireNonBlank(signalId, "signalId");
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            appId = requireNonBlank(appId, "appId");
            flowId = requireNonBlank(flowId, "flowId");
            runId = requireNonBlank(runId, "runId");
            stepId = requireNonBlank(stepId, "stepId");
            if (attemptNumber < 1) {
                throw new IllegalArgumentException("attemptNumber must be >= 1");
            }
        }
    }

    public record ReadResult(Entry entry, boolean materialized, Optional<RunSignal.SettingRead> auditSignal) {
        public ReadResult {
            entry = Objects.requireNonNull(entry, "entry");
            auditSignal = Objects.requireNonNull(auditSignal, "auditSignal");
        }
    }

    public record UiEntry(
            RailixPath path,
            String type,
            String source,
            boolean secret,
            boolean present,
            Visibility visibility
    ) {
        public UiEntry {
            path = Objects.requireNonNull(path, "path");
            type = requireNonBlank(type, "type");
            source = requireNonBlank(source, "source");
            visibility = Objects.requireNonNull(visibility, "visibility");
        }
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
