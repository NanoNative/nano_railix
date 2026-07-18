package dev.nanonative.railix.railixstdstore;

import dev.nanonative.railix.kernel.model.Envelope;
import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.runtime.InMemoryContextDoc;
import dev.nanonative.railix.kernel.runtime.Step;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StandardStoreStepProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWriteStoreValueToConfiguredRoot() throws Exception {
        final StandardStoreStepProvider provider = new StandardStoreStepProvider();
        final Envelope envelope = envelope("customer/u-123", "customer");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardStoreStepProvider.STORE_WRITE_USE).orElseThrow();

        final Step.Result result = step.execute(executionInput(envelope, context));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.store.key")))
                .isEqualTo(new RailixValue.StringValue("customer/u-123"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.store.result")))
                .isEqualTo(storeValue());
        assertThat(readStoredValue(tempDir.resolve("store").resolve("customer").resolve("u-123").resolve("entry.json")))
                .isEqualTo(storeValue());
        assertThat(step.contract().settings().requested()).containsExactly("settings.store.rootDir");
        assertThat(step.contract().permissions().requested()).containsEntry("store.write", List.of("store/*"));
        assertThat(step.contract().permissions().granted()).isEmpty();
    }

    @Test
    void shouldReadStoredStoreValueFromConfiguredRoot() throws Exception {
        final StandardStoreStepProvider provider = new StandardStoreStepProvider();
        final Envelope envelope = envelope("customer/u-123", "customer");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardStoreStepProvider.STORE_READ_USE).orElseThrow();
        writeStoredValue("customer/u-123", storeValue());

        final Step.Result result = step.execute(executionInput(envelope, context));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.store.result"))).isEqualTo(storeValue());
    }

    @Test
    void shouldReturnMissWhenStoredValueDoesNotExist() {
        final StandardStoreStepProvider provider = new StandardStoreStepProvider();
        final Envelope envelope = envelope("customer/u-404", "customer");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardStoreStepProvider.STORE_READ_USE).orElseThrow();

        final Step.Result result = step.execute(executionInput(envelope, context));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("miss");
        assertThat(updatedContext.get(RailixPath.parse("ctx.store.result"))).isEqualTo(RailixValue.NULL);
    }

    @Test
    void shouldWriteAndReadExplicitNullValue() throws Exception {
        final StandardStoreStepProvider provider = new StandardStoreStepProvider();
        final Envelope envelope = envelope("customer/u-null", "customer", RailixValue.NULL);
        final InMemoryContextDoc writeContext = InMemoryContextDoc.fromEnvelope(envelope);
        final Step writeStep = provider.resolve(StandardStoreStepProvider.STORE_WRITE_USE).orElseThrow();
        final Step readStep = provider.resolve(StandardStoreStepProvider.STORE_READ_USE).orElseThrow();

        final Step.Result writeResult = writeStep.execute(executionInput(envelope, writeContext));
        final InMemoryContextDoc writtenContext = (InMemoryContextDoc) writeContext.applyAll(writeResult.patches());
        final Step.Result readResult = readStep.execute(executionInput(envelope, writtenContext));
        final InMemoryContextDoc readContext = (InMemoryContextDoc) writtenContext.applyAll(readResult.patches());

        assertThat(writeResult.outcome()).isEqualTo("ok");
        assertThat(readResult.outcome()).isEqualTo("ok");
        assertThat(readContext.get(RailixPath.parse("ctx.store.result"))).isSameAs(RailixValue.NULL);
        assertThat(readStoredValue(tempDir.resolve("store").resolve("customer").resolve("u-null").resolve("entry.json")))
                .isSameAs(RailixValue.NULL);
    }

    @Test
    void shouldReportStoredValueExists() throws Exception {
        final StandardStoreStepProvider provider = new StandardStoreStepProvider();
        final Envelope envelope = envelope("customer/u-123", "customer");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardStoreStepProvider.STORE_EXISTS_USE).orElseThrow();
        writeStoredValue("customer/u-123", storeValue());

        final Step.Result result = step.execute(executionInput(envelope, context));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.store.exists")))
                .isEqualTo(new RailixValue.BoolValue(true));
    }

    @Test
    void shouldReturnMissWhenStoreKeyDoesNotExistForExistsStep() {
        final StandardStoreStepProvider provider = new StandardStoreStepProvider();
        final Envelope envelope = envelope("customer/u-404", "customer");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardStoreStepProvider.STORE_EXISTS_USE).orElseThrow();

        final Step.Result result = step.execute(executionInput(envelope, context));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("miss");
        assertThat(updatedContext.get(RailixPath.parse("ctx.store.exists")))
                .isEqualTo(new RailixValue.BoolValue(false));
    }

    @Test
    void shouldListStoredKeysForPrefix() throws Exception {
        final StandardStoreStepProvider provider = new StandardStoreStepProvider();
        final Envelope envelope = envelope("customer/u-123", "customer");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardStoreStepProvider.STORE_LIST_USE).orElseThrow();
        writeStoredValue("customer/u-123", storeValue());
        writeStoredValue("customer/u-456", new RailixValue.StringValue("second"));

        final Step.Result result = step.execute(executionInput(envelope, context));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.store.keys")))
                .isEqualTo(new RailixValue.ListValue(List.of(
                        new RailixValue.StringValue("customer/u-123"),
                        new RailixValue.StringValue("customer/u-456")
                )));
    }

    @Test
    void shouldReturnMissWhenNoKeysMatchListPrefix() {
        final StandardStoreStepProvider provider = new StandardStoreStepProvider();
        final Envelope envelope = envelope("customer/u-123", "missing-prefix");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardStoreStepProvider.STORE_LIST_USE).orElseThrow();

        final Step.Result result = step.execute(executionInput(envelope, context));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("miss");
        assertThat(updatedContext.get(RailixPath.parse("ctx.store.keys")))
                .isEqualTo(new RailixValue.ListValue(List.of()));
    }

    @Test
    void shouldPreferContextStoreKeyAndValueOverPayload() throws Exception {
        final StandardStoreStepProvider provider = new StandardStoreStepProvider();
        final Envelope envelope = envelope("payload/u-123", "payload", storeValue());
        final RailixValue.ObjectValue contextValue = new RailixValue.ObjectValue(Map.of(
                "email", new RailixValue.StringValue("ctx@example.com"),
                "status", new RailixValue.StringValue("OVERRIDE")
        ));
        final InMemoryContextDoc context = (InMemoryContextDoc) InMemoryContextDoc.fromEnvelope(envelope).applyAll(List.of(
                new Patch.Set(
                        RailixPath.parse("ctx.store.key"),
                        new Patch.LiteralSource(new RailixValue.StringValue("ctx/u-999"))
                ),
                new Patch.Set(
                        RailixPath.parse("ctx.store.value"),
                        new Patch.LiteralSource(contextValue)
                )
        ));
        final Step step = provider.resolve(StandardStoreStepProvider.STORE_WRITE_USE).orElseThrow();

        final Step.Result result = step.execute(executionInput(envelope, context));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.store.key")))
                .isEqualTo(new RailixValue.StringValue("ctx/u-999"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.store.result"))).isEqualTo(contextValue);
        assertThat(readStoredValue(tempDir.resolve("store").resolve("ctx").resolve("u-999").resolve("entry.json")))
                .isEqualTo(contextValue);
        assertThat(Files.exists(tempDir.resolve("store").resolve("payload").resolve("u-123").resolve("entry.json"))).isFalse();
    }

    @Test
    void shouldDeleteStoredValue() throws Exception {
        final StandardStoreStepProvider provider = new StandardStoreStepProvider();
        final Envelope envelope = envelope("customer/u-123", "customer");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardStoreStepProvider.STORE_DELETE_USE).orElseThrow();
        writeStoredValue("customer/u-123", storeValue());

        final Step.Result result = step.execute(executionInput(envelope, context));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.store.deleted")))
                .isEqualTo(new RailixValue.BoolValue(true));
        assertThat(Files.exists(tempDir.resolve("store").resolve("customer").resolve("u-123").resolve("entry.json"))).isFalse();
    }

    @Test
    void shouldReturnMissWhenDeletingUnknownStoreKey() {
        final StandardStoreStepProvider provider = new StandardStoreStepProvider();
        final Envelope envelope = envelope("customer/u-404", "customer");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardStoreStepProvider.STORE_DELETE_USE).orElseThrow();

        final Step.Result result = step.execute(executionInput(envelope, context));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("miss");
        assertThat(updatedContext.get(RailixPath.parse("ctx.store.deleted")))
                .isEqualTo(new RailixValue.BoolValue(false));
    }

    @Test
    void shouldRejectBlankStoreRoot() {
        final StandardStoreStepProvider provider = new StandardStoreStepProvider();
        final Envelope envelope = envelope("customer/u-123", "customer");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardStoreStepProvider.STORE_WRITE_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(new RailixValue.StringValue("   ")),
                Step.ResourceScope.none(),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                permissionScope()
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("settings.store.rootDir must not be blank");
    }

    @Test
    void shouldRejectStoreKeysWithEmptySegments() {
        final StandardStoreStepProvider provider = new StandardStoreStepProvider();
        final Envelope envelope = envelope("customer//u-123", "customer");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardStoreStepProvider.STORE_WRITE_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(executionInput(envelope, context)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("store key contains an empty segment");
    }

    @Test
    void shouldFailReadWhenStoredJsonIsCorrupt() throws Exception {
        final StandardStoreStepProvider provider = new StandardStoreStepProvider();
        final Envelope envelope = envelope("customer/u-bad", "customer");
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardStoreStepProvider.STORE_READ_USE).orElseThrow();
        final Path entryFile = tempDir.resolve("store").resolve("customer").resolve("u-bad").resolve("entry.json");
        Files.createDirectories(entryFile.getParent());
        Files.writeString(entryFile, "{ definitely-not-json");

        assertThatThrownBy(() -> step.execute(executionInput(envelope, context)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read store key customer/u-bad");
    }

    @Test
    void shouldReturnEmptyWhenUseIsUnknown() {
        final StandardStoreStepProvider provider = new StandardStoreStepProvider();

        assertThat(provider.resolve("railix.std.store.Missing")).isEmpty();
    }

    private Step.SettingsView settingsView() {
        return settingsView(new RailixValue.StringValue(tempDir.resolve("store").toString()));
    }

    private Step.SettingsView settingsView(final RailixValue rootValue) {
        return path -> RailixPath.parse("settings.store.rootDir").equals(path)
                ? Optional.of(new Step.ReadValue(path, rootValue, true, false))
                : Optional.empty();
    }

    private Step.ExecutionInput executionInput(final Envelope envelope, final InMemoryContextDoc context) {
        return new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                Step.ResourceScope.none(),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                permissionScope()
        );
    }

    private static Step.PermissionScope permissionScope() {
        return (permission, resource) -> {
        };
    }

    private void writeStoredValue(final String key, final RailixValue value) throws Exception {
        final Path entryFile = tempDir.resolve("store");
        Path current = entryFile;
        for (final String segment : key.split("/")) {
            current = current.resolve(segment);
        }
        Files.createDirectories(current);
        Files.writeString(current.resolve("entry.json"), KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(value)));
    }

    private static RailixValue readStoredValue(final Path entryFile) throws Exception {
        return KernelContractCodec.railixValueFromUiModel(KernelContractCodec.parseStableJsonObject(Files.readString(entryFile)));
    }

    private static Envelope envelope(final String key, final String prefix) {
        return envelope(key, prefix, storeValue());
    }

    private static Envelope envelope(final String key, final String prefix, final RailixValue value) {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "store", new RailixValue.ObjectValue(Map.of(
                                "key", new RailixValue.StringValue(key),
                                "prefix", new RailixValue.StringValue(prefix),
                                "value", value
                        ))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(false, List.of())
        );
    }

    private static RailixValue.ObjectValue storeValue() {
        return new RailixValue.ObjectValue(Map.of(
                "email", new RailixValue.StringValue("user@example.com"),
                "status", new RailixValue.StringValue("ACTIVE")
        ));
    }
}
