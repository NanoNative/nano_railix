package dev.nanonative.railix.railixstdfile;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StandardFileStepProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldWritePayloadValueAndPatchFileRef() throws Exception {
        final StandardFileStepProvider provider = new StandardFileStepProvider();
        final Envelope envelope = writeEnvelope("reports/out.json", new RailixValue.ObjectValue(Map.of(
                "customer", new RailixValue.StringValue("yuna")
        )));
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardFileStepProvider.FILE_WRITE_USE).orElseThrow();
        final AtomicReference<RailixValue.ObjectValue> metadataRef = new AtomicReference<>();

        final Step.Result result = step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                (refType, metadata, resource) -> {
                    metadataRef.set(metadata);
                    return new Step.RegisteredResource("ref-1", refType, metadata);
                },
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                (permission, resource) -> {
                }
        ));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.file.path")))
                .isEqualTo(new RailixValue.StringValue("reports/out.json"));
        final RailixValue.FileRef fileRef = (RailixValue.FileRef) updatedContext.get(RailixPath.parse("ctx.file.ref"));
        assertThat(fileRef.id()).isEqualTo("ref-1");
        assertThat(fileRef.path()).isEqualTo("reports/out.json");
        assertThat(fileRef.mediaType()).isEqualTo("application/json");
        assertThat(fileRef.digest()).startsWith("sha256:");
        assertThat(fileRef.size()).isPositive();
        assertThat(metadataRef.get()).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "resource", new RailixValue.StringValue("file/reports/out.json"),
                "path", new RailixValue.StringValue("reports/out.json"),
                "mediaType", new RailixValue.StringValue("application/json"),
                "digest", new RailixValue.StringValue(fileRef.digest()),
                "size", new RailixValue.NumberValue(java.math.BigDecimal.valueOf(fileRef.size()))
        )));
        assertThat(KernelContractCodec.railixValueFromUiModel(KernelContractCodec.parseStableJsonObject(
                Files.readString(tempDir.resolve("files").resolve("reports").resolve("out.json"))
        ))).isEqualTo(new RailixValue.ObjectValue(Map.of(
                "customer", new RailixValue.StringValue("yuna")
        )));
        assertThat(step.contract().permissions().granted()).isEmpty();
    }

    @Test
    void shouldPreferContextPathAndValueOverPayload() {
        final StandardFileStepProvider provider = new StandardFileStepProvider();
        final Envelope envelope = writeEnvelope("payload/out.json", new RailixValue.StringValue("payload"));
        final InMemoryContextDoc context = (InMemoryContextDoc) InMemoryContextDoc.fromEnvelope(envelope).applyAll(List.of(
                new Patch.Set(
                        RailixPath.parse("ctx.file.path"),
                        new Patch.LiteralSource(new RailixValue.StringValue("ctx/out.json"))
                ),
                new Patch.Set(
                        RailixPath.parse("ctx.file.value"),
                        new Patch.LiteralSource(new RailixValue.StringValue("ctx"))
                )
        ));
        final Step step = provider.resolve(StandardFileStepProvider.FILE_WRITE_USE).orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                (refType, metadata, resource) -> new Step.RegisteredResource("ref-ctx", refType, metadata),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                (permission, resource) -> {
                }
        ));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(updatedContext.get(RailixPath.parse("ctx.file.path")))
                .isEqualTo(new RailixValue.StringValue("ctx/out.json"));
        assertThat(((RailixValue.FileRef) updatedContext.get(RailixPath.parse("ctx.file.ref"))).id()).isEqualTo("ref-ctx");
    }

    @Test
    void shouldRejectPathTraversal() {
        final StandardFileStepProvider provider = new StandardFileStepProvider();
        final Envelope envelope = writeEnvelope("../escape.json", new RailixValue.StringValue("nope"));
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardFileStepProvider.FILE_WRITE_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                (refType, metadata, resource) -> new Step.RegisteredResource("ref-bad", refType, metadata),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                (permission, resource) -> {
                }
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("file path contains an unsupported segment: ../escape.json");
    }

    @Test
    void shouldRejectWriteThroughSymbolicLinkUnderConfiguredRoot() throws Exception {
        final StandardFileStepProvider provider = new StandardFileStepProvider();
        final Path outsideDir = tempDir.resolve("outside-write");
        final Path symlink = createEscapeSymlink(outsideDir);
        final Envelope envelope = writeEnvelope("reports/escape/out.json", new RailixValue.StringValue("nope"));
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardFileStepProvider.FILE_WRITE_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                (refType, metadata, resource) -> new Step.RegisteredResource("ref-bad-link", refType, metadata),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                (permission, resource) -> {
                }
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("file path traverses a symbolic link under settings.file.rootDir: reports/escape/out.json");
        assertThat(Files.exists(outsideDir.resolve("out.json"))).isFalse();
        assertThat(Files.isSymbolicLink(symlink)).isTrue();
    }

    @Test
    void shouldReadWrittenFileByRelativeRef() throws Exception {
        final StandardFileStepProvider provider = new StandardFileStepProvider();
        final Path filePath = tempDir.resolve("files").resolve("reports").resolve("read.json");
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(
                new RailixValue.ObjectValue(Map.of("ok", new RailixValue.BoolValue(true)))
        )));
        final RailixValue.FileRef fileRef = new RailixValue.FileRef(
                "ref-read",
                "reports/read.json",
                "application/json",
                "sha256:any",
                Files.size(filePath)
        );
        final Envelope envelope = readEnvelope(fileRef);
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardFileStepProvider.FILE_READ_USE).orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                registeredFileScope(fileRef),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                (permission, resource) -> {
                }
        ));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.file.path")))
                .isEqualTo(new RailixValue.StringValue("reports/read.json"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.file.result")))
                .isEqualTo(new RailixValue.ObjectValue(Map.of("ok", new RailixValue.BoolValue(true))));
    }

    @Test
    void shouldReadWrittenFileByAbsoluteRefForBackwardCompatibility() throws Exception {
        final StandardFileStepProvider provider = new StandardFileStepProvider();
        final Path filePath = tempDir.resolve("files").resolve("reports").resolve("read.json");
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(
                new RailixValue.ObjectValue(Map.of("ok", new RailixValue.BoolValue(true)))
        )));
        final Envelope envelope = readEnvelope(new RailixValue.FileRef(
                "ref-read",
                filePath.toString(),
                "application/json",
                "sha256:any",
                Files.size(filePath)
        ));
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardFileStepProvider.FILE_READ_USE).orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                Step.ResourceScope.none(),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                (permission, resource) -> {
                }
        ));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.file.result")))
                .isEqualTo(new RailixValue.ObjectValue(Map.of("ok", new RailixValue.BoolValue(true))));
    }

    @Test
    void shouldRejectReadByRefThroughSymbolicLinkUnderConfiguredRoot() throws Exception {
        final StandardFileStepProvider provider = new StandardFileStepProvider();
        final Path outsideDir = tempDir.resolve("outside-read");
        Files.createDirectories(outsideDir);
        Files.writeString(outsideDir.resolve("read.json"), KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(
                new RailixValue.ObjectValue(Map.of("ok", new RailixValue.BoolValue(true)))
        )));
        createEscapeSymlink(outsideDir);
        final RailixValue.FileRef fileRef = new RailixValue.FileRef(
                "ref-read-link",
                "reports/escape/read.json",
                "application/json",
                "sha256:any",
                Files.size(outsideDir.resolve("read.json"))
        );
        final Envelope envelope = readEnvelope(fileRef);
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardFileStepProvider.FILE_READ_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                registeredFileScope(fileRef),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                (permission, resource) -> {
                }
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("file path traverses a symbolic link under settings.file.rootDir: reports/escape/read.json");
    }

    @Test
    void shouldRejectReadByUnregisteredRelativeRef() throws Exception {
        final StandardFileStepProvider provider = new StandardFileStepProvider();
        final Path filePath = tempDir.resolve("files").resolve("reports").resolve("read.json");
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(
                new RailixValue.ObjectValue(Map.of("ok", new RailixValue.BoolValue(true)))
        )));
        final Envelope envelope = readEnvelope(new RailixValue.FileRef(
                "ref-missing",
                "reports/read.json",
                "application/json",
                "sha256:any",
                Files.size(filePath)
        ));
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardFileStepProvider.FILE_READ_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                Step.ResourceScope.none(),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                (permission, resource) -> {
                }
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("relative file ref is not registered in the current run: ref-missing");
    }

    @Test
    void shouldRejectReadByRelativeRefWhenRegisteredMetadataPathDoesNotMatch() throws Exception {
        final StandardFileStepProvider provider = new StandardFileStepProvider();
        final Path filePath = tempDir.resolve("files").resolve("reports").resolve("read.json");
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(
                new RailixValue.ObjectValue(Map.of("ok", new RailixValue.BoolValue(true)))
        )));
        final RailixValue.FileRef fileRef = new RailixValue.FileRef(
                "ref-mismatch",
                "reports/read.json",
                "application/json",
                "sha256:any",
                Files.size(filePath)
        );
        final Envelope envelope = readEnvelope(fileRef);
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardFileStepProvider.FILE_READ_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                registeredFileScope(
                        fileRef.id(),
                        "reports/other.json",
                        fileRef.mediaType(),
                        fileRef.digest(),
                        fileRef.size()
                ),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                (permission, resource) -> {
                }
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("file ref path does not match registered metadata for ref-mismatch");
    }

    @Test
    void shouldRejectReadByAbsoluteRefWhenLeafIsSymbolicLinkEscapingRoot() throws Exception {
        final StandardFileStepProvider provider = new StandardFileStepProvider();
        final Path outsideFile = tempDir.resolve("outside-read-leaf.json");
        Files.writeString(outsideFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(
                new RailixValue.ObjectValue(Map.of("ok", new RailixValue.BoolValue(true)))
        )));
        final Path symlink = createLeafEscapeSymlink("reports/read-link.json", outsideFile);
        final Envelope envelope = readEnvelope(new RailixValue.FileRef(
                "ref-read-leaf-link",
                symlink.toString(),
                "application/json",
                "sha256:any",
                Files.size(outsideFile)
        ));
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardFileStepProvider.FILE_READ_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                Step.ResourceScope.none(),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                (permission, resource) -> {
                }
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("file path traverses a symbolic link under settings.file.rootDir: reports/read-link.json");
        assertThat(Files.isSymbolicLink(symlink)).isTrue();
    }

    @Test
    void shouldReturnMissWhenReadingMissingFile() {
        final StandardFileStepProvider provider = new StandardFileStepProvider();
        final Envelope envelope = writeEnvelope("reports/missing.json", new RailixValue.StringValue("ignored"));
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardFileStepProvider.FILE_READ_USE).orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                Step.ResourceScope.none(),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                (permission, resource) -> {
                }
        ));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("miss");
        assertThat(updatedContext.get(RailixPath.parse("ctx.file.result"))).isEqualTo(RailixValue.NULL);
    }

    @Test
    void shouldRejectFileRefOutsideConfiguredRoot() {
        final StandardFileStepProvider provider = new StandardFileStepProvider();
        final Envelope envelope = readEnvelope(new RailixValue.FileRef(
                "ref-outside",
                tempDir.resolve("outside.json").toString(),
                "application/json",
                "sha256:any",
                1L
        ));
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardFileStepProvider.FILE_READ_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                Step.ResourceScope.none(),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                (permission, resource) -> {
                }
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("file ref path must stay under settings.file.rootDir");
    }

    @Test
    void shouldDeleteWrittenFileByRelativeRef() throws Exception {
        final StandardFileStepProvider provider = new StandardFileStepProvider();
        final Path filePath = tempDir.resolve("files").resolve("reports").resolve("delete.json");
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(
                new RailixValue.StringValue("delete-me")
        )));
        final RailixValue.FileRef fileRef = new RailixValue.FileRef(
                "ref-delete",
                "reports/delete.json",
                "application/json",
                "sha256:any",
                Files.size(filePath)
        );
        final Envelope envelope = readEnvelope(fileRef);
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardFileStepProvider.FILE_DELETE_USE).orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                registeredFileScope(fileRef),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                (permission, resource) -> {
                }
        ));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.file.path")))
                .isEqualTo(new RailixValue.StringValue("reports/delete.json"));
        assertThat(updatedContext.get(RailixPath.parse("ctx.file.deleted"))).isEqualTo(new RailixValue.BoolValue(true));
        assertThat(Files.exists(filePath)).isFalse();
    }

    @Test
    void shouldRejectDeleteByRefThroughSymbolicLinkUnderConfiguredRoot() throws Exception {
        final StandardFileStepProvider provider = new StandardFileStepProvider();
        final Path outsideDir = tempDir.resolve("outside-delete");
        Files.createDirectories(outsideDir);
        final Path outsideFile = outsideDir.resolve("delete.json");
        Files.writeString(outsideFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(
                new RailixValue.StringValue("delete-me")
        )));
        createEscapeSymlink(outsideDir);
        final RailixValue.FileRef fileRef = new RailixValue.FileRef(
                "ref-delete-link",
                "reports/escape/delete.json",
                "application/json",
                "sha256:any",
                Files.size(outsideFile)
        );
        final Envelope envelope = readEnvelope(fileRef);
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardFileStepProvider.FILE_DELETE_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                registeredFileScope(fileRef),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                (permission, resource) -> {
                }
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("file path traverses a symbolic link under settings.file.rootDir: reports/escape/delete.json");
        assertThat(Files.exists(outsideFile)).isTrue();
    }

    @Test
    void shouldRejectDeleteByAbsoluteRefWhenLeafIsSymbolicLinkEscapingRoot() throws Exception {
        final StandardFileStepProvider provider = new StandardFileStepProvider();
        final Path outsideFile = tempDir.resolve("outside-delete-leaf.json");
        Files.writeString(outsideFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(
                new RailixValue.StringValue("delete-me")
        )));
        final Path symlink = createLeafEscapeSymlink("reports/delete-link.json", outsideFile);
        final Envelope envelope = readEnvelope(new RailixValue.FileRef(
                "ref-delete-leaf-link",
                symlink.toString(),
                "application/json",
                "sha256:any",
                Files.size(outsideFile)
        ));
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardFileStepProvider.FILE_DELETE_USE).orElseThrow();

        assertThatThrownBy(() -> step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                Step.ResourceScope.none(),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                (permission, resource) -> {
                }
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("file path traverses a symbolic link under settings.file.rootDir: reports/delete-link.json");
        assertThat(Files.exists(outsideFile)).isTrue();
        assertThat(Files.isSymbolicLink(symlink)).isTrue();
    }

    @Test
    void shouldDeleteWrittenFileByAbsoluteRefForBackwardCompatibility() throws Exception {
        final StandardFileStepProvider provider = new StandardFileStepProvider();
        final Path filePath = tempDir.resolve("files").resolve("reports").resolve("delete.json");
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(
                new RailixValue.StringValue("delete-me")
        )));
        final Envelope envelope = readEnvelope(new RailixValue.FileRef(
                "ref-delete",
                filePath.toString(),
                "application/json",
                "sha256:any",
                Files.size(filePath)
        ));
        final InMemoryContextDoc context = InMemoryContextDoc.fromEnvelope(envelope);
        final Step step = provider.resolve(StandardFileStepProvider.FILE_DELETE_USE).orElseThrow();

        final Step.Result result = step.execute(new Step.ExecutionInput(
                envelope,
                context,
                settingsView(),
                Step.ResourceScope.none(),
                Step.MetricScope.none(),
                Step.AuditScope.none(),
                (permission, resource) -> {
                }
        ));
        final InMemoryContextDoc updatedContext = (InMemoryContextDoc) context.applyAll(result.patches());

        assertThat(result.outcome()).isEqualTo("ok");
        assertThat(updatedContext.get(RailixPath.parse("ctx.file.deleted"))).isEqualTo(new RailixValue.BoolValue(true));
        assertThat(Files.exists(filePath)).isFalse();
    }

    @Test
    void shouldReturnEmptyWhenUseIsUnknown() {
        final StandardFileStepProvider provider = new StandardFileStepProvider();

        assertThat(provider.resolve("railix.std.file.Missing")).isEmpty();
    }

    private Step.SettingsView settingsView() {
        final RailixPath fileRootPath = RailixPath.parse("settings.file.rootDir");
        return path -> path.equals(fileRootPath)
                ? java.util.Optional.of(new Step.ReadValue(fileRootPath, new RailixValue.StringValue(tempDir.resolve("files").toString()), true, false))
                : java.util.Optional.empty();
    }

    private static Step.ResourceScope registeredFileScope(final RailixValue.FileRef fileRef) {
        return registeredFileScope(fileRef.id(), fileRef.path(), fileRef.mediaType(), fileRef.digest(), fileRef.size());
    }

    private static Step.ResourceScope registeredFileScope(
            final String refId,
            final String relativePath,
            final String mediaType,
            final String digest,
            final long size
    ) {
        return new Step.ResourceScope() {
            @Override
            public Step.RegisteredResource register(
                    final String refType,
                    final RailixValue.ObjectValue metadata,
                    final AutoCloseable resource
            ) {
                throw new IllegalStateException("not used");
            }

            @Override
            public Optional<Step.RegisteredResource> find(final String lookupRefId) {
                if (!refId.equals(lookupRefId)) {
                    return Optional.empty();
                }
                return Optional.of(new Step.RegisteredResource(
                        refId,
                        "file",
                        new RailixValue.ObjectValue(Map.of(
                                "resource", new RailixValue.StringValue("file/" + relativePath),
                                "path", new RailixValue.StringValue(relativePath),
                                "mediaType", new RailixValue.StringValue(mediaType),
                                "digest", new RailixValue.StringValue(digest),
                                "size", new RailixValue.NumberValue(java.math.BigDecimal.valueOf(size))
                        ))
                ));
            }
        };
    }

    private static Envelope writeEnvelope(final String path, final RailixValue value) {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "file", new RailixValue.ObjectValue(Map.of(
                                "path", new RailixValue.StringValue(path),
                                "value", value
                        ))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(false, List.of())
        );
    }

    private static Envelope readEnvelope(final RailixValue.FileRef fileRef) {
        return new Envelope(
                "manual.dev",
                "manual",
                new RailixValue.ObjectValue(Map.of(
                        "file", new RailixValue.ObjectValue(Map.of("ref", fileRef))
                )),
                new RailixValue.ObjectValue(Map.of("actor", new RailixValue.StringValue("developer"))),
                Map.of(),
                new Envelope.ReplyChannel(false, List.of())
        );
    }

    private Path createEscapeSymlink(final Path outsideDir) throws Exception {
        Files.createDirectories(tempDir.resolve("files").resolve("reports"));
        Files.createDirectories(outsideDir);
        final Path symlink = tempDir.resolve("files").resolve("reports").resolve("escape");
        Files.createSymbolicLink(symlink, outsideDir);
        return symlink;
    }

    private Path createLeafEscapeSymlink(final String relativePath, final Path outsideFile) throws Exception {
        final Path symlink = tempDir.resolve("files").resolve(relativePath);
        Files.createDirectories(symlink.getParent());
        Files.createSymbolicLink(symlink, outsideFile);
        return symlink;
    }
}
