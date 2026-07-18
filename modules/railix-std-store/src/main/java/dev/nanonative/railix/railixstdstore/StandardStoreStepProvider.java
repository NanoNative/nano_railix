package dev.nanonative.railix.railixstdstore;

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

import java.nio.file.AtomicMoveNotSupportedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Filesystem-backed standard store provider for the first honest store-pack slice.
 */
public final class StandardStoreStepProvider implements StepProvider {

    static final String STORE_READ_USE = "railix.std.store.StoreRead";
    static final String STORE_WRITE_USE = "railix.std.store.StoreWrite";
    static final String STORE_DELETE_USE = "railix.std.store.StoreDelete";
    static final String STORE_EXISTS_USE = "railix.std.store.StoreExists";
    static final String STORE_LIST_USE = "railix.std.store.StoreList";

    private static final RailixPath STORE_ROOT_PATH = RailixPath.parse("settings.store.rootDir");
    private static final RailixPath CONTEXT_KEY_PATH = RailixPath.parse("ctx.store.key");
    private static final RailixPath PAYLOAD_KEY_PATH = RailixPath.parse("payload.store.key");
    private static final RailixPath CONTEXT_PREFIX_PATH = RailixPath.parse("ctx.store.prefix");
    private static final RailixPath PAYLOAD_PREFIX_PATH = RailixPath.parse("payload.store.prefix");
    private static final RailixPath CONTEXT_VALUE_PATH = RailixPath.parse("ctx.store.value");
    private static final RailixPath PAYLOAD_VALUE_PATH = RailixPath.parse("payload.store.value");
    private static final RailixPath CONTEXT_RESULT_PATH = RailixPath.parse("ctx.store.result");
    private static final RailixPath CONTEXT_EXISTS_PATH = RailixPath.parse("ctx.store.exists");
    private static final RailixPath CONTEXT_DELETED_PATH = RailixPath.parse("ctx.store.deleted");
    private static final RailixPath CONTEXT_KEYS_PATH = RailixPath.parse("ctx.store.keys");

    private static final String STORE_READ_PERMISSION = "store.read";
    private static final String STORE_WRITE_PERMISSION = "store.write";
    private static final String STORE_RESOURCE_WILDCARD = "store/*";

    @Override
    public Optional<Step> resolve(final String use) {
        return switch (use) {
            case STORE_WRITE_USE -> Optional.of(storeWriteStep());
            case STORE_READ_USE -> Optional.of(storeReadStep());
            case STORE_DELETE_USE -> Optional.of(storeDeleteStep());
            case STORE_EXISTS_USE -> Optional.of(storeExistsStep());
            case STORE_LIST_USE -> Optional.of(storeListStep());
            default -> Optional.empty();
        };
    }

    @Override
    public List<String> supportedUses() {
        return List.of(
                STORE_READ_USE,
                STORE_WRITE_USE,
                STORE_DELETE_USE,
                STORE_EXISTS_USE,
                STORE_LIST_USE
        );
    }

    private static Step storeWriteStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return StandardStoreStepProvider.contract(
                        STORE_WRITE_USE,
                        "StoreWrite",
                        "Persists a Railix value into the configured filesystem-backed store root.",
                        List.of(
                                new StepContract.Port("key", "string", CONTEXT_KEY_PATH.toString(), true, List.of()),
                                new StepContract.Port("value", "any", CONTEXT_VALUE_PATH.toString(), true, List.of())
                        ),
                        List.of(new StepContract.Port("result", "any", CONTEXT_RESULT_PATH.toString(), false, List.of())),
                        Map.of("ok", new StepContract.Outcome("Value stored")),
                        PermissionSet.requestedOnly(Map.of(
                                "settings.read", List.of(STORE_ROOT_PATH.toString()),
                                STORE_WRITE_PERMISSION, List.of(STORE_RESOURCE_WILDCARD)
                        ))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final Path storeRoot = storeRoot(input);
                final String key = storeKey(input);
                input.permissions().require(STORE_WRITE_PERMISSION, permissionResource(key));
                final RailixValue value = storeValue(input);
                writeValue(storeRoot, key, value);
                return new Result(
                        "ok",
                        List.of(
                                new Patch.Set(CONTEXT_KEY_PATH, new Patch.LiteralSource(new RailixValue.StringValue(key))),
                                new Patch.Set(CONTEXT_RESULT_PATH, new Patch.LiteralSource(value))
                        )
                );
            }
        };
    }

    private static Step storeReadStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return StandardStoreStepProvider.contract(
                        STORE_READ_USE,
                        "StoreRead",
                        "Reads a Railix value from the configured filesystem-backed store root.",
                        List.of(new StepContract.Port("key", "string", CONTEXT_KEY_PATH.toString(), true, List.of())),
                        List.of(new StepContract.Port("result", "any", CONTEXT_RESULT_PATH.toString(), false, List.of())),
                        Map.of(
                                "ok", new StepContract.Outcome("Value loaded"),
                                "miss", new StepContract.Outcome("No stored value for the requested key")
                        ),
                        PermissionSet.requestedOnly(Map.of(
                                "settings.read", List.of(STORE_ROOT_PATH.toString()),
                                STORE_READ_PERMISSION, List.of(STORE_RESOURCE_WILDCARD)
                        ))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final Path storeRoot = storeRoot(input);
                final String key = storeKey(input);
                input.permissions().require(STORE_READ_PERMISSION, permissionResource(key));
                final Optional<RailixValue> value = readValue(storeRoot, key);
                return new Result(
                        value.isPresent() ? "ok" : "miss",
                        List.of(
                                new Patch.Set(CONTEXT_KEY_PATH, new Patch.LiteralSource(new RailixValue.StringValue(key))),
                                new Patch.Set(
                                        CONTEXT_RESULT_PATH,
                                        new Patch.LiteralSource(value.orElse(RailixValue.NULL))
                                )
                        )
                );
            }
        };
    }

    private static Step storeDeleteStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return StandardStoreStepProvider.contract(
                        STORE_DELETE_USE,
                        "StoreDelete",
                        "Deletes a Railix value from the configured filesystem-backed store root.",
                        List.of(new StepContract.Port("key", "string", CONTEXT_KEY_PATH.toString(), true, List.of())),
                        List.of(new StepContract.Port("deleted", "bool", CONTEXT_DELETED_PATH.toString(), false, List.of())),
                        Map.of(
                                "ok", new StepContract.Outcome("Stored value deleted"),
                                "miss", new StepContract.Outcome("No stored value for the requested key")
                        ),
                        PermissionSet.requestedOnly(Map.of(
                                "settings.read", List.of(STORE_ROOT_PATH.toString()),
                                STORE_WRITE_PERMISSION, List.of(STORE_RESOURCE_WILDCARD)
                        ))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final Path storeRoot = storeRoot(input);
                final String key = storeKey(input);
                input.permissions().require(STORE_WRITE_PERMISSION, permissionResource(key));
                final boolean deleted = deleteValue(storeRoot, key);
                return new Result(
                        deleted ? "ok" : "miss",
                        List.of(
                                new Patch.Set(CONTEXT_KEY_PATH, new Patch.LiteralSource(new RailixValue.StringValue(key))),
                                new Patch.Set(CONTEXT_DELETED_PATH, new Patch.LiteralSource(new RailixValue.BoolValue(deleted)))
                        )
                );
            }
        };
    }

    private static Step storeExistsStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return StandardStoreStepProvider.contract(
                        STORE_EXISTS_USE,
                        "StoreExists",
                        "Checks whether a Railix value exists in the configured filesystem-backed store root.",
                        List.of(new StepContract.Port("key", "string", CONTEXT_KEY_PATH.toString(), true, List.of())),
                        List.of(new StepContract.Port("exists", "bool", CONTEXT_EXISTS_PATH.toString(), false, List.of())),
                        Map.of(
                                "ok", new StepContract.Outcome("Stored value exists"),
                                "miss", new StepContract.Outcome("No stored value for the requested key")
                        ),
                        PermissionSet.requestedOnly(Map.of(
                                "settings.read", List.of(STORE_ROOT_PATH.toString()),
                                STORE_READ_PERMISSION, List.of(STORE_RESOURCE_WILDCARD)
                        ))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final Path storeRoot = storeRoot(input);
                final String key = storeKey(input);
                input.permissions().require(STORE_READ_PERMISSION, permissionResource(key));
                final boolean exists = existsValue(storeRoot, key);
                return new Result(
                        exists ? "ok" : "miss",
                        List.of(
                                new Patch.Set(CONTEXT_KEY_PATH, new Patch.LiteralSource(new RailixValue.StringValue(key))),
                                new Patch.Set(CONTEXT_EXISTS_PATH, new Patch.LiteralSource(new RailixValue.BoolValue(exists)))
                        )
                );
            }
        };
    }

    private static Step storeListStep() {
        return new Step() {
            @Override
            public StepContract contract() {
                return StandardStoreStepProvider.contract(
                        STORE_LIST_USE,
                        "StoreList",
                        "Lists stored keys under a prefix in the configured filesystem-backed store root.",
                        List.of(new StepContract.Port("prefix", "string", CONTEXT_PREFIX_PATH.toString(), true, List.of())),
                        List.of(new StepContract.Port("keys", "list", CONTEXT_KEYS_PATH.toString(), false, List.of())),
                        Map.of(
                                "ok", new StepContract.Outcome("Stored keys listed"),
                                "miss", new StepContract.Outcome("No stored values matched the requested prefix")
                        ),
                        PermissionSet.requestedOnly(Map.of(
                                "settings.read", List.of(STORE_ROOT_PATH.toString()),
                                STORE_READ_PERMISSION, List.of(STORE_RESOURCE_WILDCARD)
                        ))
                );
            }

            @Override
            public Result execute(final ExecutionInput input) {
                final Path storeRoot = storeRoot(input);
                final String prefix = storePrefix(input);
                input.permissions().require(STORE_READ_PERMISSION, permissionResource(prefix));
                final List<String> keys = listKeys(storeRoot, prefix);
                final RailixValue.ListValue listedKeys = new RailixValue.ListValue(
                        keys.stream()
                                .map(key -> (RailixValue) new RailixValue.StringValue(key))
                                .toList()
                );
                return new Result(
                        keys.isEmpty() ? "miss" : "ok",
                        List.of(
                                new Patch.Set(CONTEXT_PREFIX_PATH, new Patch.LiteralSource(new RailixValue.StringValue(prefix))),
                                new Patch.Set(CONTEXT_KEYS_PATH, new Patch.LiteralSource(listedKeys))
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
                new StepContract.Settings(List.of(STORE_ROOT_PATH.toString())),
                requestedPermissions,
                new StepContract.Timeout(Duration.ofSeconds(30)),
                new StepContract.RetryPolicy(1, Duration.ZERO),
                new StepContract.CachePolicy(StepContract.CachePolicy.Mode.NONE, "", Duration.ZERO),
                new StepContract.Resources(new StepContract.Limits(RailixValue.NULL, RailixValue.NULL)),
                new StepContract.Metrics(List.of()),
                Map.of("editor", new RailixValue.StringValue("store.operation"))
        );
    }

    private static Path storeRoot(final Step.ExecutionInput input) {
        final Step.ReadValue rootDir = input.settings().require(STORE_ROOT_PATH);
        if (!(rootDir.value() instanceof RailixValue.StringValue stringValue)) {
            throw new IllegalStateException("settings.store.rootDir must be a string");
        }
        final String value = stringValue.value().trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("settings.store.rootDir must not be blank");
        }
        return Path.of(value).normalize();
    }

    private static String storeKey(final Step.ExecutionInput input) {
        return stringValue(input.context(), CONTEXT_KEY_PATH)
                .or(() -> stringValue(input.context(), PAYLOAD_KEY_PATH))
                .map(StandardStoreStepProvider::normalizeStoreKey)
                .orElseThrow(() -> new IllegalStateException("Store step requires ctx.store.key or payload.store.key"));
    }

    private static String storePrefix(final Step.ExecutionInput input) {
        return stringValue(input.context(), CONTEXT_PREFIX_PATH)
                .or(() -> stringValue(input.context(), PAYLOAD_PREFIX_PATH))
                .or(() -> stringValue(input.context(), CONTEXT_KEY_PATH))
                .or(() -> stringValue(input.context(), PAYLOAD_KEY_PATH))
                .map(StandardStoreStepProvider::normalizeStoreKey)
                .orElseThrow(() -> new IllegalStateException("StoreList requires ctx.store.prefix, payload.store.prefix, ctx.store.key, or payload.store.key"));
    }

    private static RailixValue storeValue(final Step.ExecutionInput input) {
        return presentValue(input.context(), CONTEXT_VALUE_PATH)
                .or(() -> presentValue(input.context(), PAYLOAD_VALUE_PATH))
                .orElseThrow(() -> new IllegalStateException("StoreWrite requires ctx.store.value or payload.store.value"));
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

    private static Optional<RailixValue> presentValue(final ContextDoc context, final RailixPath path) {
        if (context.select(new Selector(path.toString())).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(context.get(path));
    }

    private static String permissionResource(final String key) {
        return "store/" + normalizeStoreKey(key);
    }

    private static String normalizeStoreKey(final String key) {
        final String trimmed = key.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException("store key must not be blank");
        }
        final String[] rawSegments = trimmed.split("/");
        final List<String> normalizedSegments = new ArrayList<>();
        for (final String rawSegment : rawSegments) {
            final String segment = rawSegment.trim();
            if (segment.isEmpty()) {
                throw new IllegalStateException("store key contains an empty segment: " + key);
            }
            if (".".equals(segment) || "..".equals(segment) || segment.contains("\\") || segment.contains(":")) {
                throw new IllegalStateException("store key contains an unsupported segment: " + key);
            }
            normalizedSegments.add(segment);
        }
        return String.join("/", normalizedSegments);
    }

    private static void writeValue(final Path storeRoot, final String key, final RailixValue value) {
        final Path entryFile = entryFile(storeRoot, key);
        Path tempFile = null;
        try {
            Files.createDirectories(entryFile.getParent());
            tempFile = Files.createTempFile(entryFile.getParent(), "entry-", ".json.tmp");
            Files.writeString(tempFile, KernelContractCodec.toStableJson(KernelContractCodec.toUiModel(value)));
            replaceFile(tempFile, entryFile);
        } catch (final IOException exception) {
            deleteQuietly(tempFile, exception);
            throw new IllegalStateException("Failed to write store key " + key + " under " + storeRoot, exception);
        }
    }

    private static Optional<RailixValue> readValue(final Path storeRoot, final String key) {
        final Path entryFile = entryFile(storeRoot, key);
        if (!Files.exists(entryFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(decodeValue(Files.readString(entryFile)));
        } catch (final IOException | RuntimeException exception) {
            throw new IllegalStateException("Failed to read store key " + key + " under " + storeRoot, exception);
        }
    }

    private static boolean deleteValue(final Path storeRoot, final String key) {
        final Path entryFile = entryFile(storeRoot, key);
        if (!Files.exists(entryFile)) {
            return false;
        }
        try {
            Files.delete(entryFile);
            deleteEmptyParents(storeRoot, entryFile.getParent());
            return true;
        } catch (final IOException exception) {
            throw new IllegalStateException("Failed to delete store key " + key + " under " + storeRoot, exception);
        }
    }

    private static boolean existsValue(final Path storeRoot, final String key) {
        return Files.exists(entryFile(storeRoot, key));
    }

    private static List<String> listKeys(final Path storeRoot, final String prefix) {
        final Path prefixDirectory = directoryForKey(storeRoot, prefix);
        if (!Files.exists(prefixDirectory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(prefixDirectory)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("entry.json"))
                    .map(path -> storeKeyForEntry(storeRoot, path))
                    .sorted()
                    .toList();
        } catch (final IOException exception) {
            throw new IllegalStateException("Failed to list store keys under " + prefix + " in " + storeRoot, exception);
        }
    }

    private static String storeKeyForEntry(final Path storeRoot, final Path entryFile) {
        final Path relative = storeRoot.relativize(entryFile.getParent());
        final List<String> segments = new ArrayList<>();
        for (final Path segment : relative) {
            segments.add(segment.toString());
        }
        return String.join("/", segments);
    }

    @SuppressWarnings("unchecked")
    private static RailixValue decodeValue(final String encodedValue) {
        final Object parsed = KernelContractCodec.parseStableJson(encodedValue);
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalStateException("Stored Railix value must decode to an object model");
        }
        return KernelContractCodec.railixValueFromUiModel((Map<String, Object>) rawMap);
    }

    private static Path entryFile(final Path storeRoot, final String key) {
        return directoryForKey(storeRoot, key).resolve("entry.json");
    }

    private static Path directoryForKey(final Path storeRoot, final String key) {
        Path current = storeRoot;
        for (final String segment : normalizeStoreKey(key).split("/")) {
            current = current.resolve(segment);
        }
        return current.normalize();
    }

    private static void deleteEmptyParents(final Path storeRoot, final Path candidate) throws IOException {
        Path current = candidate;
        while (current != null && !current.equals(storeRoot) && current.startsWith(storeRoot)) {
            try (Stream<Path> children = Files.list(current)) {
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
}
