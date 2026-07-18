package dev.nanonative.railix.railixstdfile;

import dev.nanonative.railix.kernel.model.KernelContractCodec;
import dev.nanonative.railix.kernel.model.Patch;
import dev.nanonative.railix.kernel.model.PermissionSet;
import dev.nanonative.railix.kernel.model.RailixPath;
import dev.nanonative.railix.kernel.model.RailixValue;
import dev.nanonative.railix.kernel.model.Selector;
import dev.nanonative.railix.kernel.model.StepContract;
import dev.nanonative.railix.kernel.runtime.ContextDoc;
import dev.nanonative.railix.kernel.runtime.Step;
import dev.nanonative.railix.kernel.runtime.StepProvider;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Filesystem-backed standard file provider for the first honest JSON file-ref slice.
 */
public final class StandardFileStepProvider implements StepProvider {

    static final String FILE_WRITE_USE = "railix.std.file.FileWrite";
    static final String FILE_READ_USE = "railix.std.file.FileRead";
    static final String FILE_DELETE_USE = "railix.std.file.FileDelete";

    private static final String FILE_MEDIA_TYPE = "application/json";
    private static final String FILE_RESOURCE_TYPE = "file";
    private static final String FILE_RESOURCE_WILDCARD = "file/*";
    private static final LinkOption[] NO_FOLLOW_LINKS = new LinkOption[]{LinkOption.NOFOLLOW_LINKS};

    private static final RailixPath FILE_ROOT_PATH = RailixPath.parse("settings.file.rootDir");
    private static final RailixPath CONTEXT_PATH_PATH = RailixPath.parse("ctx.file.path");
    private static final RailixPath PAYLOAD_PATH_PATH = RailixPath.parse("payload.file.path");
    private static final RailixPath CONTEXT_VALUE_PATH = RailixPath.parse("ctx.file.value");
    private static final RailixPath PAYLOAD_VALUE_PATH = RailixPath.parse("payload.file.value");
    private static final RailixPath CONTEXT_REF_PATH = RailixPath.parse("ctx.file.ref");
    private static final RailixPath PAYLOAD_REF_PATH = RailixPath.parse("payload.file.ref");
    private static final RailixPath CONTEXT_RESULT_PATH = RailixPath.parse("ctx.file.result");
    private static final RailixPath CONTEXT_DELETED_PATH = RailixPath.parse("ctx.file.deleted");

    @Override
    public Optional<Step> resolve(final String use) {
        return switch (use) {
            case FILE_WRITE_USE -> Optional.of(fileWriteStep());
            case FILE_READ_USE -> Optional.of(fileReadStep());
            case FILE_DELETE_USE -> Optional.of(fileDeleteStep());
            default -> Optional.empty();
        };
    }

    @Override
    public List<String> supportedUses() {
        return List.of(FILE_WRITE_USE, FILE_READ_USE, FILE_DELETE_USE);
    }

    private static Step fileWriteStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return StandardFileStepProvider.contract(
                        FILE_WRITE_USE,
                        "FileWrite",
                        "Writes a Railix value as stable JSON under the configured file root and returns a file ref.",
                        List.of(
                                new StepContract.Port("path", "string", CONTEXT_PATH_PATH.toString(), true, List.of()),
                                new StepContract.Port("value", "any", CONTEXT_VALUE_PATH.toString(), true, List.of())
                        ),
                        List.of(new StepContract.Port("ref", "file-ref", CONTEXT_REF_PATH.toString(), false, List.of())),
                        Map.of("ok", new StepContract.Outcome("File written")),
                        PermissionSet.requestedOnly(Map.of(
                                "settings.read", List.of(FILE_ROOT_PATH.toString()),
                                "resource.create", List.of(FILE_RESOURCE_WILDCARD),
                                "resource.write", List.of(FILE_RESOURCE_WILDCARD)
                        ))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final Path fileRoot = fileRoot(input);
                final String relativePath = requiredRelativePath(input.context());
                final String permissionResource = permissionResource(relativePath);
                input.permissions().require("resource.write", permissionResource);
                input.permissions().require("resource.create", permissionResource);
                final RailixValue value = requiredFileValue(input.context());
                final WrittenFile writtenFile = writeValue(fileRoot, relativePath, value);
                final Step.RegisteredResource registeredResource = input.resources().registerMetadata(
                        FILE_RESOURCE_TYPE,
                        fileMetadata(relativePath, writtenFile)
                );
                final RailixValue.FileRef fileRef = new RailixValue.FileRef(
                        registeredResource.refId(),
                        relativePath,
                        FILE_MEDIA_TYPE,
                        writtenFile.digest(),
                        writtenFile.size()
                );
                return new Result(
                        "ok",
                        List.of(
                                new Patch.Set(CONTEXT_PATH_PATH, new Patch.LiteralSource(new RailixValue.StringValue(relativePath))),
                                new Patch.Set(CONTEXT_REF_PATH, new Patch.LiteralSource(fileRef))
                        )
                );
            }
        };
    }

    private static Step fileReadStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return StandardFileStepProvider.contract(
                        FILE_READ_USE,
                        "FileRead",
                        "Reads a Railix value from a stable-JSON file under the configured file root.",
                        List.of(
                                new StepContract.Port("path", "string", CONTEXT_PATH_PATH.toString(), false, List.of()),
                                new StepContract.Port("ref", "file-ref", CONTEXT_REF_PATH.toString(), false, List.of())
                        ),
                        List.of(new StepContract.Port("result", "any", CONTEXT_RESULT_PATH.toString(), false, List.of())),
                        Map.of(
                                "ok", new StepContract.Outcome("File read"),
                                "miss", new StepContract.Outcome("No file matched the requested path")
                        ),
                        PermissionSet.requestedOnly(Map.of(
                                "settings.read", List.of(FILE_ROOT_PATH.toString()),
                                "resource.read", List.of(FILE_RESOURCE_WILDCARD)
                        ))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final Path fileRoot = fileRoot(input);
                final ResolvedFile resolvedFile = resolvedFile(input.context(), fileRoot, input.resources());
                input.permissions().require("resource.read", permissionResource(resolvedFile.relativePath()));
                final Optional<RailixValue> value = readValue(resolvedFile.path());
                return new Result(
                        value.isPresent() ? "ok" : "miss",
                        List.of(
                                new Patch.Set(CONTEXT_PATH_PATH, new Patch.LiteralSource(new RailixValue.StringValue(resolvedFile.relativePath()))),
                                new Patch.Set(CONTEXT_RESULT_PATH, new Patch.LiteralSource(value.orElse(RailixValue.NULL)))
                        )
                );
            }
        };
    }

    private static Step fileDeleteStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return StandardFileStepProvider.contract(
                        FILE_DELETE_USE,
                        "FileDelete",
                        "Deletes a stable-JSON file under the configured file root.",
                        List.of(
                                new StepContract.Port("path", "string", CONTEXT_PATH_PATH.toString(), false, List.of()),
                                new StepContract.Port("ref", "file-ref", CONTEXT_REF_PATH.toString(), false, List.of())
                        ),
                        List.of(new StepContract.Port("deleted", "bool", CONTEXT_DELETED_PATH.toString(), false, List.of())),
                        Map.of(
                                "ok", new StepContract.Outcome("File deleted"),
                                "miss", new StepContract.Outcome("No file matched the requested path")
                        ),
                        PermissionSet.requestedOnly(Map.of(
                                "settings.read", List.of(FILE_ROOT_PATH.toString()),
                                "resource.write", List.of(FILE_RESOURCE_WILDCARD)
                        ))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final Path fileRoot = fileRoot(input);
                final ResolvedFile resolvedFile = resolvedFile(input.context(), fileRoot, input.resources());
                input.permissions().require("resource.write", permissionResource(resolvedFile.relativePath()));
                final boolean deleted = deleteValue(fileRoot, resolvedFile.path());
                return new Result(
                        deleted ? "ok" : "miss",
                        List.of(
                                new Patch.Set(CONTEXT_PATH_PATH, new Patch.LiteralSource(new RailixValue.StringValue(resolvedFile.relativePath()))),
                                new Patch.Set(CONTEXT_DELETED_PATH, new Patch.LiteralSource(new RailixValue.BoolValue(deleted)))
                        )
                );
            }
        };
    }

    private static StepContract contract(
            final String id,
            final String displayName,
            final String description,
            final List<StepContract.Port> inputs,
            final List<StepContract.Port> outputs,
            final Map<String, StepContract.Outcome> outcomes,
            final PermissionSet requestedPermissions
    ) {
        return new StepContract(
                id,
                "0.1.0",
                displayName,
                description,
                StepContract.Kind.NORMAL,
                inputs,
                outputs,
                outcomes,
                new StepContract.Settings(List.of(FILE_ROOT_PATH.toString())),
                requestedPermissions,
                new StepContract.Timeout(Duration.ofSeconds(30)),
                new StepContract.RetryPolicy(1, Duration.ZERO),
                new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                new StepContract.Metrics(List.of()),
                Map.of("editor", new RailixValue.StringValue("file.operation"))
        );
    }

    private static Path fileRoot(final Step.ExecutionInput input) {
        final Step.ReadValue rootDir = input.settings().require(FILE_ROOT_PATH);
        if (!(rootDir.value() instanceof RailixValue.StringValue stringValue)) {
            throw new IllegalStateException("settings.file.rootDir must be a string");
        }
        final String value = stringValue.value().trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("settings.file.rootDir must not be blank");
        }
        return Path.of(value).normalize();
    }

    private static String requiredRelativePath(final ContextDoc context) {
        return stringValue(context, CONTEXT_PATH_PATH)
                .or(() -> stringValue(context, PAYLOAD_PATH_PATH))
                .map(StandardFileStepProvider::normalizeRelativePath)
                .orElseThrow(() -> new IllegalStateException("File step requires ctx.file.path or payload.file.path"));
    }

    private static RailixValue requiredFileValue(final ContextDoc context) {
        return presentValue(context, CONTEXT_VALUE_PATH)
                .or(() -> presentValue(context, PAYLOAD_VALUE_PATH))
                .orElseThrow(() -> new IllegalStateException("FileWrite requires ctx.file.value or payload.file.value"));
    }

    private static ResolvedFile resolvedFile(final ContextDoc context, final Path fileRoot, final Step.ResourceScope resources) {
        final Optional<RailixValue.FileRef> fileRef = fileRefValue(context, CONTEXT_REF_PATH)
                .or(() -> fileRefValue(context, PAYLOAD_REF_PATH));
        if (fileRef.isPresent()) {
            return resolvedFileFromRef(fileRoot, resources, fileRef.orElseThrow());
        }
        final String relativePath = requiredRelativePath(context);
        return new ResolvedFile(relativePath, validatedFilePath(fileRoot, relativePath));
    }

    private static ResolvedFile resolvedFileFromRef(final Path fileRoot, final Step.ResourceScope resources, final RailixValue.FileRef fileRef) {
        if (!FILE_MEDIA_TYPE.equals(fileRef.mediaType())) {
            throw new IllegalStateException("file ref mediaType must be " + FILE_MEDIA_TYPE);
        }
        final String rawPath = fileRef.path().trim();
        if (rawPath.isEmpty()) {
            throw new IllegalStateException("file ref path must not be blank");
        }
        final Path root = fileRoot.toAbsolutePath().normalize();
        final Path refPath = Path.of(rawPath).normalize();
        if (!refPath.isAbsolute()) {
            return resolvedRelativeFileFromRef(root, resources, fileRef);
        }
        final Path absoluteRefPath = refPath.toAbsolutePath().normalize();
        if (!absoluteRefPath.startsWith(root)) {
            throw new IllegalStateException("file ref path must stay under settings.file.rootDir");
        }
        final String relativePath = root.relativize(absoluteRefPath).toString().replace('\\', '/');
        final String normalizedRelativePath = normalizeRelativePath(relativePath);
        return new ResolvedFile(normalizedRelativePath, validatedFilePath(root, normalizedRelativePath));
    }

    private static ResolvedFile resolvedRelativeFileFromRef(
            final Path fileRoot,
            final Step.ResourceScope resources,
            final RailixValue.FileRef fileRef
    ) {
        final String refPath = normalizeRelativePath(fileRef.path());
        final Step.RegisteredResource registeredResource = resources.find(fileRef.id())
                .orElseThrow(() -> new IllegalStateException("relative file ref is not registered in the current run: " + fileRef.id()));
        if (!FILE_RESOURCE_TYPE.equals(registeredResource.refType())) {
            throw new IllegalStateException("file ref " + fileRef.id() + " does not resolve to a file resource");
        }
        final RailixValue.ObjectValue metadata = registeredResource.metadata();
        final String metadataPath = requiredMetadataString(metadata, "path");
        final String normalizedMetadataPath = normalizeRelativePath(metadataPath);
        if (!normalizedMetadataPath.equals(refPath)) {
            throw new IllegalStateException("file ref path does not match registered metadata for " + fileRef.id());
        }
        final String metadataMediaType = requiredMetadataString(metadata, "mediaType");
        if (!FILE_MEDIA_TYPE.equals(metadataMediaType) || !metadataMediaType.equals(fileRef.mediaType())) {
            throw new IllegalStateException("file ref mediaType does not match registered metadata for " + fileRef.id());
        }
        final String metadataDigest = requiredMetadataString(metadata, "digest");
        if (!metadataDigest.equals(fileRef.digest())) {
            throw new IllegalStateException("file ref digest does not match registered metadata for " + fileRef.id());
        }
        final long metadataSize = requiredMetadataLong(metadata, "size");
        if (metadataSize != fileRef.size()) {
            throw new IllegalStateException("file ref size does not match registered metadata for " + fileRef.id());
        }
        final String metadataResource = requiredMetadataString(metadata, "resource");
        if (!permissionResource(normalizedMetadataPath).equals(metadataResource)) {
            throw new IllegalStateException("file ref resource does not match registered metadata for " + fileRef.id());
        }
        return new ResolvedFile(normalizedMetadataPath, validatedFilePath(fileRoot, normalizedMetadataPath));
    }

    private static Optional<String> stringValue(final ContextDoc context, final RailixPath path) {
        final RailixValue value = context.get(path);
        if (value == RailixValue.NULL) {
            return Optional.empty();
        }
        if (!(value instanceof RailixValue.StringValue stringValue)) {
            throw new IllegalStateException(path + " must be a string");
        }
        return Optional.of(stringValue.value());
    }

    private static Optional<RailixValue.FileRef> fileRefValue(final ContextDoc context, final RailixPath path) {
        final RailixValue value = context.get(path);
        if (value == RailixValue.NULL) {
            return Optional.empty();
        }
        if (!(value instanceof RailixValue.FileRef fileRef)) {
            throw new IllegalStateException(path + " must be a file ref");
        }
        return Optional.of(fileRef);
    }

    private static Optional<RailixValue> presentValue(final ContextDoc context, final RailixPath path) {
        if (context.select(new Selector(path.toString())).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(context.get(path));
    }

    private static String requiredMetadataString(final RailixValue.ObjectValue metadata, final String key) {
        final RailixValue value = metadata.values().get(key);
        if (!(value instanceof RailixValue.StringValue stringValue)) {
            throw new IllegalStateException("registered file metadata " + key + " must be a string");
        }
        return stringValue.value();
    }

    private static long requiredMetadataLong(final RailixValue.ObjectValue metadata, final String key) {
        final RailixValue value = metadata.values().get(key);
        if (!(value instanceof RailixValue.NumberValue numberValue)) {
            throw new IllegalStateException("registered file metadata " + key + " must be a number");
        }
        return numberValue.value().longValueExact();
    }

    private static String permissionResource(final String relativePath) {
        return FILE_RESOURCE_TYPE + "/" + normalizeRelativePath(relativePath);
    }

    private static String normalizeRelativePath(final String rawPath) {
        final String trimmed = rawPath.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException("file path must not be blank");
        }
        final String[] rawSegments = trimmed.split("/");
        final List<String> normalizedSegments = new ArrayList<>();
        for (final String rawSegment : rawSegments) {
            final String segment = rawSegment.trim();
            if (segment.isEmpty()) {
                throw new IllegalStateException("file path contains an empty segment: " + rawPath);
            }
            if (".".equals(segment) || "..".equals(segment) || segment.contains("\\") || segment.contains(":")) {
                throw new IllegalStateException("file path contains an unsupported segment: " + rawPath);
            }
            normalizedSegments.add(segment);
        }
        return String.join("/", normalizedSegments);
    }

    private static Path filePath(final Path fileRoot, final String relativePath) {
        Path current = fileRoot.toAbsolutePath().normalize();
        for (final String segment : normalizeRelativePath(relativePath).split("/")) {
            current = current.resolve(segment);
        }
        return current.normalize();
    }

    private static Path validatedFilePath(final Path fileRoot, final String relativePath) {
        final String normalizedRelativePath = normalizeRelativePath(relativePath);
        final Path root = fileRoot.toAbsolutePath().normalize();
        rejectSymbolicLink(root, normalizedRelativePath);
        Path current = root;
        for (final String segment : normalizedRelativePath.split("/")) {
            current = current.resolve(segment).normalize();
            rejectSymbolicLink(current, normalizedRelativePath);
        }
        return current;
    }

    private static WrittenFile writeValue(final Path fileRoot, final String relativePath, final RailixValue value) {
        final Path targetFile = validatedFilePath(fileRoot, relativePath);
        final byte[] encodedValue = KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(value))
                .getBytes(StandardCharsets.UTF_8);
        Path tempFile = null;
        try {
            Files.createDirectories(targetFile.getParent());
            tempFile = Files.createTempFile(targetFile.getParent(), "file-", ".json.tmp");
            Files.write(tempFile, encodedValue);
            replaceFile(tempFile, targetFile);
            return new WrittenFile(targetFile, encodedValue.length, sha256Digest(encodedValue));
        } catch (final IOException exception) {
            deleteQuietly(tempFile, exception);
            throw new IllegalStateException("Failed to write file " + relativePath + " under " + fileRoot, exception);
        }
    }

    private static Optional<RailixValue> readValue(final Path filePath) {
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(decodeValue(Files.readString(filePath)));
        } catch (final IOException | RuntimeException exception) {
            throw new IllegalStateException("Failed to read file " + filePath, exception);
        }
    }

    private static boolean deleteValue(final Path fileRoot, final Path filePath) {
        if (!Files.exists(filePath)) {
            return false;
        }
        try {
            Files.delete(filePath);
            deleteEmptyParents(fileRoot, filePath.getParent());
            return true;
        } catch (final IOException exception) {
            throw new IllegalStateException("Failed to delete file " + filePath, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static RailixValue decodeValue(final String encodedValue) {
        final Object parsed = KernelContractCodec.parseStableJson(encodedValue);
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("Stored Railix value must decode to an object model");
        }
        return KernelContractCodec.railixValueFromUiModel((Map<String, Object>) rawMap);
    }

    private static RailixValue.ObjectValue fileMetadata(final String relativePath, final WrittenFile writtenFile) {
        return new RailixValue.ObjectValue(Map.of(
                "resource", new RailixValue.StringValue(permissionResource(relativePath)),
                "path", new RailixValue.StringValue(normalizeRelativePath(relativePath)),
                "mediaType", new RailixValue.StringValue(FILE_MEDIA_TYPE),
                "digest", new RailixValue.StringValue(writtenFile.digest()),
                "size", new RailixValue.NumberValue(BigDecimal.valueOf(writtenFile.size()))
        ));
    }

    private static void rejectSymbolicLink(final Path path, final String relativePath) {
        if (Files.exists(path, NO_FOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IllegalStateException("file path traverses a symbolic link under settings.file.rootDir: " + relativePath);
        }
    }

    private static String sha256Digest(final byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest algorithm is unavailable", exception);
        }
    }

    private static void deleteEmptyParents(final Path fileRoot, final Path candidate) throws IOException {
        Path current = candidate;
        while (current != null && !current.equals(fileRoot) && current.startsWith(fileRoot)) {
            try (var children = Files.list(current)) {
                if (children.findAny().isPresent()) {
                    return;
                }
            }
            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }

    private static void replaceFile(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(final Path candidate, final IOException failure) {
        if (candidate == null) {
            return;
        }
        try {
            Files.deleteIfExists(candidate);
        } catch (final IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private record ResolvedFile(String relativePath, Path path) {
    }

    private record WrittenFile(Path path, long size, String digest) {
    }
}
