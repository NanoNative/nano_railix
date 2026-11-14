package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueRefinement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.zip.CRC32;

/**
 * Pure-JDK local document collection persistence Step implementation ({@code railix.database}).
 *
 * <h2>Durability and Recovery Guarantees</h2>
 * <ul>
 *   <li>Every committed {@code upsert} (single document or batch array) and {@code delete} appends
 *       CRC32-checksummed binary frames to {@code journal.wal} and flushes file content via
 *       {@link FileChannel#force(boolean) journal.force(false)} before returning {@code ok}.</li>
 *   <li>On startup, {@link #replayJournal()} distinguishes an interrupted trailing write at EOF
 *       (a partial header matching the valid header prefix or a valid header whose declared payload
 *       extends past EOF) from data corruption (invalid header magic/bounds, CRC32 mismatch on a
 *       complete frame, invalid UTF-8 key, bucket mismatch, or corrupted JSON payload). Incomplete
 *       trailing writes at EOF are truncated cleanly; corruption throws {@link IllegalStateException}
 *       with {@code DATABASE_JOURNAL_CORRUPTED} while preserving the damaged {@code journal.wal}
 *       file intact on disk without truncating subsequent records.</li>
 *   <li>When superseded and tombstoned bytes reach {@link #COMPACT_MIN_DEAD_BYTES} and exceed live
 *       payload bytes, compaction rewrites live records sequentially to {@code journal.wal.compact},
 *       forces it to disk, and atomically replaces {@code journal.wal}.</li>
 * </ul>
 *
 * <h2>Concurrency and Scope</h2>
 * <ul>
 *   <li>Cross-process ownership is enforced via an exclusive OS {@link FileLock} on
 *       {@code collection.lock}, which also records the canonical collection name to detect
 *       filesystem path or case-insensitivity collisions.</li>
 *   <li>Within one running application, placements referencing the same collection directory share
 *       one store instance via {@code context.resource} and coordinate concurrent virtual-thread
 *       reads and serialized writes through a {@link ReentrantReadWriteLock}.</li>
 *   <li>Multi-node replication and cross-document/multi-collection transactions are out of scope.</li>
 * </ul>
 *
 * <h2>Storage Location and Safety Bounds</h2>
 * <ul>
 *   <li>Absolute {@code directory} paths are used directly; relative paths resolve against the
 *       {@code railix.data.dir} system property, the {@code RAILIX_DATA_DIR} environment variable,
 *       the Creator project root (when launched from {@code .railix/build/<entry>/application.jar}),
 *       or the process working directory.</li>
 *   <li>{@link #MAX_PAYLOAD_BYTES} (1 MiB) matches {@link RailixData#DEFAULT_MAX_SOURCE_BYTES} to
 *       bound per-document memory during concurrent reads and compaction; {@link #MAX_FIELD_NAMES}
 *       (32) bounds per-placement secondary index maintenance and projection fan-out; {@code find}
 *       returns pages of up to {@link #MAX_FIND_LIMIT} (1,000) documents with keyset continuation
 *       via the optional {@code after} input.</li>
 * </ul>
 */
public final class DatabaseStepHandlers {
    static final int BUCKET_COUNT = 1_024;
    static final int BUCKET_MASK = BUCKET_COUNT - 1;
    static final int MAGIC = 0x5244_4231;
    static final int HEADER_BYTES = 20;

    private static final byte OP_UPSERT = 1;
    private static final byte OP_DELETE = 2;
    private static final byte KEY_STRING = 1;
    private static final byte KEY_NUMBER = 2;

    private static final int MAX_KEY_BYTES = 1_024;
    /** Matches {@link RailixData#DEFAULT_MAX_SOURCE_BYTES} (1 MiB) to bound single-document heap allocations. */
    static final int MAX_PAYLOAD_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;
    /** Matches {@link RailixData#MAX_SOURCE_BYTES} (16 MiB) to bound total buffered bytes in one batch write. */
    static final int MAX_BULK_FRAME_BYTES = RailixData.MAX_SOURCE_BYTES;
    private static final long COMPACT_MIN_DEAD_BYTES = 65_536L;

    /** Caps per-placement field lists so secondary-index maintenance and projection fan-out remain bounded. */
    static final int MAX_FIELD_NAMES = 32;
    static final int DEFAULT_FIND_LIMIT = 100;
    static final int MAX_FIND_LIMIT = 1_000;

    private static final Set<String> RESERVED_DEVICE_NAMES = Set.of(
            "con", "prn", "aux", "nul",
            "com0", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt0", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    );

    private static final ValueRefinement DOCUMENT_REFINEMENT = ValueRefinement.canonical()
            .withMaxJsonBytes(MAX_PAYLOAD_BYTES);
    private static final RailixValue.ObjectValue NO_DEFAULTS = RailixValue.object(Map.of());

    private record RecordLocation(long payloadOffset, int keyLength, int payloadLength) {
        long frameOffset() {
            return payloadOffset - HEADER_BYTES - keyLength;
        }
    }

    private record PreparedWrite(
            String indexKey,
            byte keyType,
            short bucket,
            byte[] keyBytes,
            byte[] payloadBytes,
            RailixValue.ObjectValue document
    ) {
        long frameBytes() {
            return (long) HEADER_BYTES + keyBytes.length + payloadBytes.length;
        }
    }

    private final String collectionName;
    private final Path directory;
    private final Path lockPath;
    private final Path journalPath;
    private final Path compactPath;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Map<String, RecordLocation> index = new HashMap<>();
    /** Secondary index field -> primary index key -> encoded scalar value; mutated only under the write lock. */
    private final Map<String, Map<String, String>> indexValueByKey = new HashMap<>();
    /** Secondary index field -> encoded scalar value -> primary index keys; mutated only under the write lock. */
    private final Map<String, Map<String, Set<String>>> indexKeysByValue = new HashMap<>();
    /** Immutable snapshots replaced only during PRE, so request preparation can read them without locking. */
    private volatile Set<String> uniqueFields = Set.of();
    private volatile List<String> indexedFields = List.of();

    private FileChannel lockChannel;
    private FileLock fileLock;
    private FileChannel journal;
    private long writeOffset;
    private long livePayloadBytes;
    private long deadBytes;
    private volatile long lastCompactionPauseNanos;
    private String configuredKeyField;
    private boolean initialized;
    private boolean closed;

    private DatabaseStepHandlers(final String collectionName, final Path collectionDirectory) {
        this.collectionName = collectionName;
        this.directory = collectionDirectory;
        this.lockPath = collectionDirectory.resolve("collection.lock");
        this.journalPath = collectionDirectory.resolve("journal.wal");
        this.compactPath = collectionDirectory.resolve("journal.wal.compact");
    }

    /**
     * Opens or reuses the configured local collection store during application startup and ensures the
     * placement's declared secondary indexes. Indexes are collection-wide: the store maintains the union
     * of every placement's {@code unique_fields} and {@code index_fields}.
     *
     * @param configuration compiled collection, directory, key_field, index, and operation configuration
     * @param context lifecycle context supplying placement-owned resource management
     * @return primary outcome once the collection journal is replayed, locked, and indexed
     * @throws IllegalStateException when existing records violate a declared unique index or journal is corrupted
     */
    public static StepResult pre(final StepInput configuration, final StepInput context) {
        final String collection = configuration.string("collection");
        final Path collectionDirectory = resolveCollectionDirectory(
                configuration.string("directory"),
                collection
        );
        final DatabaseStepHandlers store = context.resource(
                "store",
                DatabaseStepHandlers.class,
                collectionDirectory,
                () -> openStore(collection, collectionDirectory),
                DatabaseStepHandlers::closeStore
        );
        store.ensureIndexes(
                collection,
                configuration.string("key_field"),
                fieldNames(configuration.optionalValue("unique_fields")),
                fieldNames(configuration.optionalValue("index_fields"))
        );
        return StepResult.outcome(context.primaryOutcome());
    }

    /**
     * Executes the configured collection operation ({@code upsert}, {@code get}, {@code find}, or {@code delete}).
     * {@code upsert} accepts either a single document ({@code OBJECT}) or a batch list of documents ({@code ARRAY}).
     *
     * @param configuration compiled Step settings
     * @param input resolved runtime invocation values
     * @return {@code ok} (with {@code target} write), {@code not_found}, or {@code invalid}
     */
    public static StepResult process(final StepInput configuration, final StepInput input) {
        final DatabaseStepHandlers store = input.resource("store", DatabaseStepHandlers.class);
        final String keyField = configuration.string("key_field");
        final String operation = input.option("operation");
        final StepInput selected = input.selected("operation");
        return switch (operation) {
            case "upsert" -> store.upsert(
                    keyField,
                    booleanOption(selected, "auto_key"),
                    fieldNames(selected.optionalValue("fields")),
                    selected.optionalValue("document")
                            .or(() -> selected.optionalValue("documents"))
                            .orElse(null)
            );
            case "bulk_upsert" -> store.upsertBatch(
                    keyField,
                    booleanOption(selected, "auto_key"),
                    fieldNames(selected.optionalValue("fields")),
                    selected.optionalValue("documents")
                            .or(() -> selected.optionalValue("document"))
                            .orElse(null)
            );
            case "get" -> store.get(
                    keyField,
                    fieldNames(selected.optionalValue("fields")),
                    defaults(selected),
                    selected.optionalValue("key").orElse(null)
            );
            case "find" -> store.find(
                    keyField,
                    fieldNames(selected.optionalValue("fields")),
                    defaults(selected),
                    limit(selected),
                    selected.optionalValue("filter").orElse(null),
                    selected.optionalValue("after").orElse(null)
            );
            case "delete" -> store.delete(selected.optionalValue("key").orElse(null));
            default -> throw new IllegalArgumentException("Unsupported database operation: " + operation);
        };
    }

    /** Computes the deterministic 1,024-bucket partition ID for a valid string or number key. */
    static int bucketForKey(final RailixValue key) {
        final String indexKey = encodeIndexKey(key);
        if (indexKey == null) {
            throw new IllegalArgumentException("Database key must be a valid non-empty string or canonical number.");
        }
        return bucketOf(indexKey);
    }

    long journalSizeBytes() {
        rwLock.readLock().lock();
        try {
            return writeOffset;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    long lastCompactionPauseNanos() {
        return lastCompactionPauseNanos;
    }

    private static boolean booleanOption(final StepInput selected, final String name) {
        return ((RailixValue.BooleanValue) selected.value(name)).value();
    }

    /** Reads a validated field-name list; an absent input means "no fields" (whole document / no index). */
    private static List<String> fieldNames(final Optional<RailixValue> value) {
        if (value.isEmpty() || !(value.get() instanceof RailixValue.ArrayValue array) || array.values().isEmpty()) {
            return List.of();
        }
        final List<String> names = new ArrayList<>(array.values().size());
        for (final RailixValue item : array.values()) {
            if (item instanceof RailixValue.StringValue string) {
                names.add(string.value());
            }
        }
        return List.copyOf(names);
    }

    private static RailixValue.ObjectValue defaults(final StepInput selected) {
        return selected.optionalValue("defaults").orElse(NO_DEFAULTS) instanceof RailixValue.ObjectValue object
                ? object
                : NO_DEFAULTS;
    }

    private static int limit(final StepInput selected) {
        if (selected.optionalValue("limit").orElse(null) instanceof RailixValue.NumberValue number) {
            final BigDecimal value = number.value();
            if (value.compareTo(BigDecimal.ONE) >= 0 && value.compareTo(BigDecimal.valueOf(MAX_FIND_LIMIT)) <= 0) {
                return value.intValue();
            }
        }
        return DEFAULT_FIND_LIMIT;
    }

    /**
     * Validates a configured top-level field-name list: at most {@value #MAX_FIELD_NAMES} distinct,
     * non-blank names up to 128 UTF-8 bytes that do not start with {@code $}.
     *
     * @param value authored array
     * @return empty when valid, otherwise a user-facing rejection
     */
    static Optional<String> fieldListRejection(final RailixValue.ArrayValue value) {
        if (value.values().size() > MAX_FIELD_NAMES) {
            return Optional.of("Use at most " + MAX_FIELD_NAMES + " field names.");
        }
        final Set<String> seen = new HashSet<>();
        for (final RailixValue item : value.values()) {
            if (!(item instanceof RailixValue.StringValue string) || !validFieldName(string.value())) {
                return Optional.of("Field names must be non-blank text up to 128 bytes and must not start with $.");
            }
            if (!seen.add(string.value())) {
                return Optional.of("Field name is listed more than once: " + string.value() + ".");
            }
        }
        return Optional.empty();
    }

    /**
     * Validates configured read defaults: at most {@value #MAX_FIELD_NAMES} top-level fields with valid names.
     *
     * @param value authored defaults object
     * @return empty when valid, otherwise a user-facing rejection
     */
    static Optional<String> defaultsRejection(final RailixValue.ObjectValue value) {
        if (value.values().size() > MAX_FIELD_NAMES) {
            return Optional.of("Use at most " + MAX_FIELD_NAMES + " default fields.");
        }
        for (final String name : value.values().keySet()) {
            if (!validFieldName(name)) {
                return Optional.of(
                        "Default field names must be non-blank text up to 128 bytes and must not start with $."
                );
            }
        }
        return DOCUMENT_REFINEMENT.rejection(value).isPresent()
                ? Optional.of("Defaults exceed the supported JSON value bounds.")
                : Optional.empty();
    }

    private static boolean validFieldName(final String value) {
        return validKeyField(value) && value.charAt(0) != '$';
    }

    /**
     * Builds collection-wide secondary indexes requested by one placement during PRE.
     * Existing indexes are kept; newly requested fields are built from stored documents, and newly
     * unique fields are verified before any writes are admitted.
     */
    private void ensureIndexes(
            final String requestedCollection,
            final String keyField,
            final List<String> unique,
            final List<String> indexed
    ) {
        rwLock.writeLock().lock();
        final List<String> added = new ArrayList<>();
        try {
            ensureOpen();
            if (!collectionName.equals(requestedCollection)) {
                throw new IllegalStateException("DATABASE_COLLECTION_COLLISION: Directory " + directory
                        + " belongs to collection '" + collectionName + "', not '" + requestedCollection + "'.");
            }
            if (configuredKeyField == null) {
                configuredKeyField = keyField;
            } else if (!configuredKeyField.equals(keyField)) {
                throw new IllegalStateException("DATABASE_KEY_FIELD_MISMATCH: Collection '" + collectionName
                        + "' in " + directory + " uses key_field '" + configuredKeyField
                        + "', not '" + keyField + "'.");
            }
            final Set<String> nextUnique = new LinkedHashSet<>(uniqueFields);
            final Set<String> nextIndexed = new LinkedHashSet<>(indexedFields);
            for (final String field : unique) {
                if (!field.equals(keyField)) {
                    nextUnique.add(field);
                    nextIndexed.add(field);
                }
            }
            for (final String field : indexed) {
                if (!field.equals(keyField)) {
                    nextIndexed.add(field);
                }
            }
            for (final String field : nextIndexed) {
                if (!indexValueByKey.containsKey(field)) {
                    added.add(field);
                }
            }
            final boolean newlyUnique = !uniqueFields.containsAll(nextUnique);
            if (added.isEmpty() && !newlyUnique) {
                return;
            }
            for (final String field : added) {
                indexValueByKey.put(field, new HashMap<>());
                indexKeysByValue.put(field, new HashMap<>());
            }
            for (final Map.Entry<String, RecordLocation> entry : index.entrySet()) {
                final RailixValue.ObjectValue document = parseStoredObject(readPayloadBytes(journal, entry.getValue()));
                for (final String field : nextUnique) {
                    if (!uniqueCompatible(document.values().get(field))) {
                        throw new IllegalStateException("DATABASE_UNIQUE_INDEX_VIOLATION: Unique field " + field
                                + " holds an array or object value in collection " + directory + ".");
                    }
                }
                for (final String field : added) {
                    addIndexEntry(field, entry.getKey(), document.values().get(field));
                }
            }
            for (final String field : nextUnique) {
                for (final Set<String> holders : indexKeysByValue.get(field).values()) {
                    if (holders.size() > 1) {
                        throw new IllegalStateException("DATABASE_UNIQUE_INDEX_VIOLATION: Unique field " + field
                                + " has duplicate stored values in collection " + directory + ".");
                    }
                }
            }
            uniqueFields = Set.copyOf(nextUnique);
            indexedFields = List.copyOf(nextIndexed);
            added.clear();
        } catch (final IOException exception) {
            throw new UncheckedIOException("Cannot build database indexes in collection " + directory + ".", exception);
        } finally {
            for (final String field : added) {
                indexValueByKey.remove(field);
                indexKeysByValue.remove(field);
            }
            rwLock.writeLock().unlock();
        }
    }

    private void addIndexEntry(final String field, final String indexKey, final RailixValue value) {
        final String encoded = indexValue(value);
        if (encoded != null) {
            indexValueByKey.get(field).put(indexKey, encoded);
            indexKeysByValue.get(field).computeIfAbsent(encoded, ignored -> new LinkedHashSet<>()).add(indexKey);
        }
    }

    /** Replaces (or, for a Java-null document, removes) every secondary index entry of one record. */
    private void reindex(final String indexKey, final RailixValue.ObjectValue document) {
        for (final String field : indexedFields) {
            final String previous = indexValueByKey.get(field).remove(indexKey);
            if (previous != null) {
                final Map<String, Set<String>> byValue = indexKeysByValue.get(field);
                final Set<String> holders = byValue.get(previous);
                holders.remove(indexKey);
                if (holders.isEmpty()) {
                    byValue.remove(previous);
                }
            }
            if (document != null) {
                addIndexEntry(field, indexKey, document.values().get(field));
            }
        }
    }

    /**
     * Returns whether committing the final documents of one write would duplicate a unique value,
     * considering records rewritten by the same write as released. Call under the write lock.
     */
    private boolean uniqueConflict(final Map<String, RailixValue.ObjectValue> finalDocuments) {
        for (final String field : uniqueFields) {
            final Map<String, String> claimed = new HashMap<>();
            for (final Map.Entry<String, RailixValue.ObjectValue> entry : finalDocuments.entrySet()) {
                final String encoded = indexValue(entry.getValue().values().get(field));
                if (encoded != null && claimed.putIfAbsent(encoded, entry.getKey()) != null) {
                    return true;
                }
            }
            final Map<String, Set<String>> byValue = indexKeysByValue.get(field);
            for (final Map.Entry<String, String> claim : claimed.entrySet()) {
                for (final String holder : byValue.getOrDefault(claim.getKey(), Set.of())) {
                    if (!holder.equals(claim.getValue()) && !finalDocuments.containsKey(holder)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean uniqueCompatible(final RailixValue value) {
        return !(value instanceof RailixValue.ArrayValue) && !(value instanceof RailixValue.ObjectValue);
    }

    private static boolean uniqueFieldsCompatible(final Set<String> unique, final RailixValue.ObjectValue document) {
        for (final String field : unique) {
            if (!uniqueCompatible(document.values().get(field))) {
                return false;
            }
        }
        return true;
    }

    /** Encodes an indexable scalar; null, missing, arrays and objects are not indexed (sparse). */
    private static String indexValue(final RailixValue value) {
        return switch (value) {
            case null -> null;
            case RailixValue.StringValue string -> "s:" + string.value();
            case RailixValue.NumberValue number -> {
                final BigDecimal raw = number.value();
                yield "n:" + (raw.signum() == 0 ? BigDecimal.ZERO : raw.stripTrailingZeros()).toString();
            }
            case RailixValue.BooleanValue bool -> bool.value() ? "b:true" : "b:false";
            default -> null;
        };
    }

    /** Keeps only the listed top-level fields plus the key field; an empty list keeps the whole document. */
    private static RailixValue.ObjectValue project(
            final RailixValue.ObjectValue document,
            final String keyField,
            final List<String> fields
    ) {
        if (fields.isEmpty()) {
            return document;
        }
        final Map<String, RailixValue> projected = new TreeMap<>();
        final RailixValue key = document.values().get(keyField);
        if (key != null) {
            projected.put(keyField, key);
        }
        for (final String field : fields) {
            final RailixValue value = document.values().get(field);
            if (value != null) {
                projected.put(field, value);
            }
        }
        return RailixValue.object(projected);
    }

    /** Applies a read projection, then fills each configured default where the field is missing or JSON null. */
    private static RailixValue.ObjectValue readView(
            final RailixValue.ObjectValue document,
            final String keyField,
            final List<String> fields,
            final RailixValue.ObjectValue defaults
    ) {
        final RailixValue.ObjectValue projected = project(document, keyField, fields);
        if (defaults.values().isEmpty()) {
            return projected;
        }
        final Map<String, RailixValue> filled = new TreeMap<>(projected.values());
        for (final Map.Entry<String, RailixValue> entry : defaults.values().entrySet()) {
            final RailixValue current = filled.get(entry.getKey());
            if (current == null || current instanceof RailixValue.NullValue) {
                filled.put(entry.getKey(), entry.getValue());
            }
        }
        return RailixValue.object(filled);
    }

    static Path resolveCollectionDirectory(final String configuredDirectory, final String collection) {
        if (!validCollectionName(collection)) {
            throw new IllegalArgumentException("Invalid collection name: " + collection);
        }
        if (!validStorageDirectory(configuredDirectory)) {
            throw new IllegalArgumentException("Invalid storage directory: " + configuredDirectory);
        }
        final Path configured = Path.of(configuredDirectory);
        final Path base = configured.isAbsolute()
                ? configured.toAbsolutePath().normalize()
                : defaultBaseDirectory().resolve(configured).toAbsolutePath().normalize();
        return base.resolve(collection).normalize();
    }

    /**
     * Enforces portable, OS-independent collection naming: 1 to 64 characters starting and ending
     * with a lowercase ASCII letter or digit, containing only {@code a-z}, {@code 0-9}, {@code .},
     * {@code _}, or {@code -} without consecutive dots or Windows-reserved device names.
     */
    static boolean validCollectionName(final String value) {
        if (value == null || value.isEmpty() || value.length() > 64) {
            return false;
        }
        if (!lowercaseAlphanumeric(value.charAt(0)) || !lowercaseAlphanumeric(value.charAt(value.length() - 1))) {
            return false;
        }
        char previous = 0;
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (!lowercaseAlphanumeric(c) && c != '.' && c != '_' && c != '-') {
                return false;
            }
            if (c == '.' && previous == '.') {
                return false;
            }
            previous = c;
        }
        final int dot = value.indexOf('.');
        final String stem = (dot >= 0 ? value.substring(0, dot) : value).toLowerCase(Locale.ROOT);
        return !RESERVED_DEVICE_NAMES.contains(stem);
    }

    static boolean validStorageDirectory(final String value) {
        if (value == null || value.isBlank() || value.length() > 512) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c < 32 || c == '<' || c == '>' || c == '"' || c == '|' || c == '?' || c == '*') {
                return false;
            }
            if (c == ':' && !(i == 1 && asciiLetter(value.charAt(0)))) {
                return false;
            }
        }
        final Path path;
        try {
            path = Path.of(value);
        } catch (final InvalidPathException invalid) {
            return false;
        }
        for (final Path segment : path) {
            final String part = segment.toString();
            if ("..".equals(part) || ".".equals(part) || part.endsWith(" ") || part.endsWith(".")) {
                return false;
            }
        }
        return true;
    }

    static boolean validKeyField(final String value) {
        if (value == null || value.isBlank() || invalidSurrogate(value)) {
            return false;
        }
        return value.getBytes(StandardCharsets.UTF_8).length <= 128;
    }

    private static boolean asciiLetter(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean lowercaseAlphanumeric(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static Path defaultBaseDirectory() {
        final String prop = System.getProperty("railix.data.dir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop).toAbsolutePath().normalize();
        }
        final String env = System.getenv("RAILIX_DATA_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        final var source = DatabaseStepHandlers.class.getProtectionDomain().getCodeSource();
        if (source != null && source.getLocation() != null) {
            try {
                final Path location = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
                final Path buildEntry = location.getParent();
                final Path buildDir = buildEntry == null ? null : buildEntry.getParent();
                final Path railixDir = buildDir == null ? null : buildDir.getParent();
                final Path projectRoot = railixDir == null ? null : railixDir.getParent();
                if (projectRoot != null
                        && Path.of("application.jar").equals(location.getFileName())
                        && Path.of("build").equals(buildDir.getFileName())
                        && Path.of(".railix").equals(railixDir.getFileName())) {
                    return projectRoot;
                }
            } catch (final URISyntaxException | IllegalArgumentException ignored) {
                // Fall back to the process working directory when code source is not a file path.
            }
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private static DatabaseStepHandlers openStore(final String collectionName, final Path collectionDirectory) {
        final DatabaseStepHandlers store = new DatabaseStepHandlers(collectionName, collectionDirectory);
        try {
            store.initialize();
            return store;
        } catch (final IOException exception) {
            store.closeQuietly();
            throw new UncheckedIOException(
                    "Cannot initialize database collection at " + collectionDirectory + ".",
                    exception
            );
        } catch (final RuntimeException | Error failure) {
            store.closeQuietly();
            throw failure;
        }
    }

    private void initialize() throws IOException {
        Files.createDirectories(directory);
        lockChannel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        );
        try {
            fileLock = lockChannel.tryLock();
        } catch (final OverlappingFileLockException lockedInJvm) {
            fileLock = null;
        }
        if (fileLock == null) {
            throw new IllegalStateException(
                    "DATABASE_COLLECTION_LOCKED: Collection is already locked by another writer: " + directory
            );
        }
        verifyOrRecordCollectionOwner();
        if (!Files.exists(journalPath) && Files.exists(compactPath)) {
            moveJournal(compactPath, journalPath);
        } else {
            Files.deleteIfExists(compactPath);
        }
        journal = FileChannel.open(
                journalPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        );
        replayJournal();
        if (shouldCompact()) {
            compactUnderWriteLock();
        }
        initialized = true;
    }

    private void verifyOrRecordCollectionOwner() throws IOException {
        final long lockSize = lockChannel.size();
        if (lockSize > 0L) {
            final int length = (int) Math.min(lockSize, 256L);
            final byte[] bytes = new byte[length];
            readFully(lockChannel, ByteBuffer.wrap(bytes), 0L);
            final String recorded = decodeUtf8(bytes, 0, length);
            final String existing = recorded == null ? "" : recorded.strip();
            if (!existing.isEmpty() && !existing.equals(collectionName)) {
                throw new IllegalStateException("DATABASE_COLLECTION_COLLISION: Directory " + directory
                        + " belongs to collection '" + existing + "', not '" + collectionName + "'.");
            }
            if (!existing.isEmpty()) {
                return;
            }
        }
        final byte[] ownerBytes = (collectionName + "\n").getBytes(StandardCharsets.UTF_8);
        writeFully(lockChannel, ByteBuffer.wrap(ownerBytes), 0L);
        lockChannel.force(false);
    }

    /**
     * Replays committed frames from {@code journal.wal}.
     *
     * <p>Only an incomplete trailing write at EOF (a partial frame header matching the valid header
     * prefix or a valid header whose declared payload extends beyond EOF) is truncated. Any invalid
     * magic, invalid header field, CRC32 mismatch on a complete frame, invalid UTF-8 key, bucket
     * mismatch, or corrupted JSON payload throws {@link IllegalStateException} without truncating
     * {@code journal.wal} so damaged files are preserved intact.</p>
     */
    private void replayJournal() throws IOException {
        final long fileSize = journal.size();
        final ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
        long offset = 0L;
        boolean incompleteTrailingWrite = false;

        while (offset < fileSize) {
            final long remaining = fileSize - offset;
            if (remaining < HEADER_BYTES) {
                final byte[] partial = new byte[(int) remaining];
                if (!readFully(journal, ByteBuffer.wrap(partial), offset)) {
                    incompleteTrailingWrite = true;
                    break;
                }
                if (!validPartialHeader(partial)) {
                    throw corruptedJournal(offset, "Invalid partial frame header");
                }
                incompleteTrailingWrite = true;
                break;
            }
            header.clear();
            if (!readFully(journal, header, offset)) {
                incompleteTrailingWrite = true;
                break;
            }
            header.flip();
            final int magic = header.getInt();
            final byte op = header.get();
            final byte keyType = header.get();
            final int bucket = Short.toUnsignedInt(header.getShort());
            final int keyLength = header.getInt();
            final int payloadLength = header.getInt();
            final int expectedCrc = header.getInt();
            if (!validFrameHeader(magic, op, keyType, bucket, keyLength, payloadLength)) {
                throw corruptedJournal(offset, "Invalid frame header");
            }
            final long frameBytes = (long) HEADER_BYTES + keyLength + payloadLength;
            if (offset + frameBytes > fileSize) {
                incompleteTrailingWrite = true;
                break;
            }
            final byte[] body = new byte[keyLength + payloadLength];
            if (!readFully(journal, ByteBuffer.wrap(body), offset + HEADER_BYTES)) {
                incompleteTrailingWrite = true;
                break;
            }
            if (frameChecksum(op, keyType, (short) bucket, keyLength, payloadLength, body) != expectedCrc) {
                throw corruptedJournal(offset, "Frame CRC32 checksum mismatch");
            }
            final String rawKey = decodeUtf8(body, 0, keyLength);
            if (rawKey == null) {
                throw corruptedJournal(offset, "Invalid UTF-8 key");
            }
            final String indexKey = (keyType == KEY_STRING ? "s:" : "n:") + rawKey;
            if (bucketOf(indexKey) != bucket) {
                throw corruptedJournal(offset, "Key partition bucket mismatch");
            }
            if (op == OP_UPSERT) {
                if (!validJsonObjectDelimiters(body, keyLength, payloadLength)) {
                    throw corruptedJournal(offset, "Corrupted JSON document payload");
                }
                final long payloadOffset = offset + HEADER_BYTES + keyLength;
                final RecordLocation previous = index.put(
                        indexKey,
                        new RecordLocation(payloadOffset, keyLength, payloadLength)
                );
                if (previous != null) {
                    livePayloadBytes -= previous.payloadLength();
                    deadBytes += (long) HEADER_BYTES + previous.keyLength() + previous.payloadLength();
                }
                livePayloadBytes += payloadLength;
            } else {
                final RecordLocation previous = index.remove(indexKey);
                if (previous != null) {
                    livePayloadBytes -= previous.payloadLength();
                    deadBytes += (long) HEADER_BYTES + previous.keyLength() + previous.payloadLength()
                            + HEADER_BYTES + keyLength;
                } else {
                    deadBytes += (long) HEADER_BYTES + keyLength;
                }
            }
            offset += frameBytes;
        }
        for (final RecordLocation location : index.values()) {
            parseStoredObject(readPayloadBytes(journal, location));
        }
        if (incompleteTrailingWrite && offset < fileSize) {
            journal.truncate(offset);
            journal.force(false);
        }
        writeOffset = offset;
    }

    private IllegalStateException corruptedJournal(final long offset, final String detail) {
        return new IllegalStateException("DATABASE_JOURNAL_CORRUPTED: " + detail + " at byte offset " + offset
                + " in " + journalPath + "; damaged journal preserved without truncation.");
    }

    private static boolean validPartialHeader(final byte[] partial) {
        final int len = partial.length;
        for (int i = 0; i < Math.min(len, 4); i++) {
            final byte expectedMagicByte = (byte) ((MAGIC >>> (24 - i * 8)) & 0xff);
            if (partial[i] != expectedMagicByte) {
                return false;
            }
        }
        final ByteBuffer buf = ByteBuffer.wrap(partial);
        if (len >= 5) {
            final byte op = buf.get(4);
            if (op != OP_UPSERT && op != OP_DELETE) {
                return false;
            }
            if (len >= 6) {
                final byte keyType = buf.get(5);
                if (keyType != KEY_STRING && keyType != KEY_NUMBER) {
                    return false;
                }
            }
            if (len >= 8) {
                final int bucket = Short.toUnsignedInt(buf.getShort(6));
                if (bucket >= BUCKET_COUNT) {
                    return false;
                }
            }
            if (len >= 12) {
                final int keyLength = buf.getInt(8);
                if (keyLength < 1 || keyLength > MAX_KEY_BYTES) {
                    return false;
                }
            }
            if (len >= 16) {
                final int payloadLength = buf.getInt(12);
                final boolean validPayload = op == OP_UPSERT
                        ? payloadLength >= 2 && payloadLength <= MAX_PAYLOAD_BYTES
                        : payloadLength == 0;
                if (!validPayload) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean validFrameHeader(
            final int magic,
            final byte op,
            final byte keyType,
            final int bucket,
            final int keyLength,
            final int payloadLength
    ) {
        if (magic != MAGIC) {
            return false;
        }
        if (op != OP_UPSERT && op != OP_DELETE) {
            return false;
        }
        if (keyType != KEY_STRING && keyType != KEY_NUMBER) {
            return false;
        }
        if (bucket < 0 || bucket >= BUCKET_COUNT) {
            return false;
        }
        if (keyLength < 1 || keyLength > MAX_KEY_BYTES) {
            return false;
        }
        return op == OP_UPSERT
                ? payloadLength >= 2 && payloadLength <= MAX_PAYLOAD_BYTES
                : payloadLength == 0;
    }

    private StepResult upsert(
            final String keyField,
            final boolean autoKey,
            final List<String> fields,
            final RailixValue candidate
    ) {
        if (candidate instanceof RailixValue.ArrayValue array) {
            return upsertBatch(keyField, autoKey, fields, array);
        }
        if (!(candidate instanceof RailixValue.ObjectValue rawObject)) {
            return StepResult.outcome("invalid");
        }
        final PreparedWrite prepared = prepareWrite(rawObject, keyField, autoKey, fields);
        if (prepared == null) {
            return StepResult.outcome("invalid");
        }
        if (!commitPreparedWrites(List.of(prepared), Map.of(prepared.indexKey(), prepared.document()), "upsert")) {
            return StepResult.outcome("invalid");
        }
        return StepResult.outcome("ok").write("target", prepared.document());
    }

    private StepResult upsertBatch(
            final String keyField,
            final boolean autoKey,
            final List<String> fields,
            final RailixValue candidate
    ) {
        if (!(candidate instanceof RailixValue.ArrayValue array)) {
            return StepResult.outcome("invalid");
        }
        final List<RailixValue> items = array.values();
        if (items.isEmpty()) {
            return StepResult.outcome("ok").write("target", RailixValue.array(List.of()));
        }
        final int count = items.size();
        final List<PreparedWrite> preparedWrites = new ArrayList<>(count);
        final List<RailixValue> savedDocuments = new ArrayList<>(count);
        final Map<String, RailixValue.ObjectValue> finalDocuments = new LinkedHashMap<>();
        long totalFrameBytes = 0L;

        for (int i = 0; i < count; i++) {
            if (!(items.get(i) instanceof RailixValue.ObjectValue rawObject)) {
                return StepResult.outcome("invalid");
            }
            final PreparedWrite prepared = prepareWrite(rawObject, keyField, autoKey, fields);
            if (prepared == null) {
                return StepResult.outcome("invalid");
            }
            totalFrameBytes += prepared.frameBytes();
            if (totalFrameBytes > MAX_BULK_FRAME_BYTES) {
                return StepResult.outcome("invalid");
            }
            preparedWrites.add(prepared);
            savedDocuments.add(prepared.document());
            finalDocuments.put(prepared.indexKey(), prepared.document());
        }
        if (!commitPreparedWrites(preparedWrites, finalDocuments, "upsert")) {
            return StepResult.outcome("invalid");
        }
        return StepResult.outcome("ok").write("target", RailixValue.array(savedDocuments));
    }

    private PreparedWrite prepareWrite(
            final RailixValue.ObjectValue rawObject,
            final String keyField,
            final boolean autoKey,
            final List<String> fields
    ) {
        final RailixValue.ObjectValue document = prepareDocumentKey(rawObject, keyField, autoKey);
        if (document == null) {
            return null;
        }
        final String indexKey = encodeIndexKey(document.values().get(keyField));
        if (indexKey == null || DOCUMENT_REFINEMENT.rejection(document).isPresent()) {
            return null;
        }
        final RailixValue.ObjectValue canonicalDocument = canonicalObject(project(document, keyField, fields));
        if (!uniqueFieldsCompatible(uniqueFields, canonicalDocument)) {
            return null;
        }
        final Optional<String> serialized = RailixJson.write(canonicalDocument, MAX_PAYLOAD_BYTES);
        if (serialized.isEmpty()) {
            return null;
        }
        final byte keyType = indexKey.charAt(0) == 's' ? KEY_STRING : KEY_NUMBER;
        final byte[] keyBytes = indexKey.substring(2).getBytes(StandardCharsets.UTF_8);
        final byte[] payloadBytes = serialized.get().getBytes(StandardCharsets.UTF_8);
        return new PreparedWrite(
                indexKey,
                keyType,
                (short) bucketOf(indexKey),
                keyBytes,
                payloadBytes,
                canonicalDocument
        );
    }

    private boolean commitPreparedWrites(
            final List<PreparedWrite> writes,
            final Map<String, RailixValue.ObjectValue> finalDocuments,
            final String operationLabel
    ) {
        long totalBytes = 0L;
        for (final PreparedWrite write : writes) {
            totalBytes += write.frameBytes();
        }
        final ByteBuffer batch = ByteBuffer.allocate((int) totalBytes);
        for (final PreparedWrite write : writes) {
            appendFrame(batch, OP_UPSERT, write.keyType(), write.bucket(), write.keyBytes(), write.payloadBytes());
        }
        batch.flip();

        rwLock.writeLock().lock();
        try {
            ensureOpen();
            if (!uniqueFields.isEmpty() && uniqueConflict(finalDocuments)) {
                return false;
            }
            final long startOffset = writeOffset;
            writeFully(journal, batch, startOffset);
            journal.force(false);
            long cursor = startOffset;
            for (final PreparedWrite write : writes) {
                final int keyLen = write.keyBytes().length;
                final int payloadLen = write.payloadBytes().length;
                final long payloadOffset = cursor + HEADER_BYTES + keyLen;
                final RecordLocation previous = index.put(
                        write.indexKey(),
                        new RecordLocation(payloadOffset, keyLen, payloadLen)
                );
                if (previous != null) {
                    livePayloadBytes -= previous.payloadLength();
                    deadBytes += (long) HEADER_BYTES + previous.keyLength() + previous.payloadLength();
                }
                livePayloadBytes += payloadLen;
                cursor += write.frameBytes();
            }
            writeOffset = cursor;
            for (final Map.Entry<String, RailixValue.ObjectValue> entry : finalDocuments.entrySet()) {
                reindex(entry.getKey(), entry.getValue());
            }
            if (shouldCompact()) {
                compactUnderWriteLock();
            }
            return true;
        } catch (final IOException exception) {
            rollbackJournalTail(exception);
            throw new UncheckedIOException(
                    "Database " + operationLabel + " failed in collection " + directory + ".",
                    exception
            );
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private StepResult get(
            final String keyField,
            final List<String> fields,
            final RailixValue.ObjectValue defaults,
            final RailixValue keyCandidate
    ) {
        final String indexKey = encodeIndexKey(keyCandidate);
        if (indexKey == null) {
            return StepResult.outcome("invalid");
        }
        final byte[] payloadBytes;
        rwLock.readLock().lock();
        try {
            ensureOpen();
            final RecordLocation location = index.get(indexKey);
            if (location == null) {
                return StepResult.outcome("not_found");
            }
            payloadBytes = readPayloadBytes(journal, location);
        } catch (final IOException exception) {
            throw new UncheckedIOException("Database get failed in collection " + directory + ".", exception);
        } finally {
            rwLock.readLock().unlock();
        }
        final RailixValue.ObjectValue stored = parseStoredObject(payloadBytes);
        return StepResult.outcome("ok").write("target", readView(stored, keyField, fields, defaults));
    }

    private StepResult find(
            final String keyField,
            final List<String> fields,
            final RailixValue.ObjectValue defaults,
            final int limit,
            final RailixValue filterCandidate,
            final RailixValue afterCandidate
    ) {
        if (filterCandidate != null && !(filterCandidate instanceof RailixValue.ObjectValue)
                && !(filterCandidate instanceof RailixValue.NullValue)) {
            return StepResult.outcome("invalid");
        }
        final String afterIndexKey;
        if (afterCandidate == null || afterCandidate instanceof RailixValue.NullValue) {
            afterIndexKey = null;
        } else {
            afterIndexKey = encodeIndexKey(afterCandidate);
            if (afterIndexKey == null) {
                return StepResult.outcome("invalid");
            }
        }
        final RailixValue.ObjectValue filter = filterCandidate instanceof RailixValue.ObjectValue object
                ? object
                : RailixValue.object(Map.of());
        if (DOCUMENT_REFINEMENT.rejection(filter).isPresent()) {
            return StepResult.outcome("invalid");
        }
        for (final String field : filter.values().keySet()) {
            if (!validFieldName(field)) {
                return StepResult.outcome("invalid");
            }
        }

        final List<RailixValue> results = new ArrayList<>();
        rwLock.readLock().lock();
        try {
            ensureOpen();
            final Set<String> candidateKeys = resolveCandidateKeys(keyField, filter);
            if (candidateKeys != null && candidateKeys.isEmpty()) {
                return StepResult.outcome("ok").write("target", RailixValue.array(List.of()));
            }

            final List<String> targetKeys = candidateKeys != null
                    ? new ArrayList<>(candidateKeys)
                    : new ArrayList<>(index.keySet());
            targetKeys.sort(DatabaseStepHandlers::compareIndexKeys);

            for (final String indexKey : targetKeys) {
                if (afterIndexKey != null && compareIndexKeys(indexKey, afterIndexKey) <= 0) {
                    continue;
                }
                final RecordLocation location = index.get(indexKey);
                if (location == null) {
                    continue;
                }
                final byte[] payloadBytes = readPayloadBytes(journal, location);
                final RailixValue.ObjectValue stored = parseStoredObject(payloadBytes);
                if (matchesFilter(stored, filter)) {
                    results.add(readView(stored, keyField, fields, defaults));
                    if (results.size() >= limit) {
                        break;
                    }
                }
            }
        } catch (final IOException exception) {
            throw new UncheckedIOException("Database find failed in collection " + directory + ".", exception);
        } finally {
            rwLock.readLock().unlock();
        }
        return StepResult.outcome("ok").write("target", RailixValue.array(results));
    }

    private static int compareIndexKeys(final String left, final String right) {
        if (left.equals(right)) {
            return 0;
        }
        final char leftType = left.charAt(0);
        final char rightType = right.charAt(0);
        if (leftType != rightType) {
            return leftType == 'n' ? -1 : 1;
        }
        if (leftType == 'n') {
            return new BigDecimal(left.substring(2)).compareTo(new BigDecimal(right.substring(2)));
        }
        return left.substring(2).compareTo(right.substring(2));
    }

    private Set<String> resolveCandidateKeys(final String keyField, final RailixValue.ObjectValue filter) {
        if (filter.values().containsKey(keyField)) {
            final String primary = encodeIndexKey(filter.values().get(keyField));
            return primary == null ? Set.of() : (index.containsKey(primary) ? Set.of(primary) : Set.of());
        }
        List<Set<String>> matchedSets = null;
        for (final String indexed : indexedFields) {
            if (filter.values().containsKey(indexed)) {
                final String enc = indexValue(filter.values().get(indexed));
                if (enc == null) {
                    continue;
                }
                final Map<String, Set<String>> byValue = indexKeysByValue.get(indexed);
                final Set<String> matching = byValue == null ? null : byValue.get(enc);
                if (matching == null || matching.isEmpty()) {
                    return Set.of();
                }
                if (matchedSets == null) {
                    matchedSets = new ArrayList<>(2);
                }
                matchedSets.add(matching);
            }
        }
        if (matchedSets == null) {
            return null;
        }
        if (matchedSets.size() == 1) {
            return Set.copyOf(matchedSets.getFirst());
        }
        matchedSets.sort(Comparator.comparingInt(Set::size));
        final Set<String> intersection = new LinkedHashSet<>(matchedSets.getFirst());
        for (int i = 1; i < matchedSets.size(); i++) {
            intersection.retainAll(matchedSets.get(i));
            if (intersection.isEmpty()) {
                return Set.of();
            }
        }
        return intersection;
    }

    private static boolean matchesFilter(
            final RailixValue.ObjectValue stored,
            final RailixValue.ObjectValue filter
    ) {
        for (final Map.Entry<String, RailixValue> entry : filter.values().entrySet()) {
            final String field = entry.getKey();
            final RailixValue expected = entry.getValue();
            final RailixValue actual = stored.values().get(field);
            if (!filterValuesEqual(actual, expected)) {
                return false;
            }
        }
        return true;
    }

    private static boolean filterValuesEqual(final RailixValue actual, final RailixValue expected) {
        if (expected instanceof RailixValue.NullValue) {
            return actual == null || actual instanceof RailixValue.NullValue;
        }
        if (actual == null) {
            return false;
        }
        return canonicalValue(actual).equals(canonicalValue(expected));
    }

    private StepResult delete(final RailixValue keyCandidate) {
        final String indexKey = encodeIndexKey(keyCandidate);
        if (indexKey == null) {
            return StepResult.outcome("invalid");
        }
        final byte keyType = indexKey.charAt(0) == 's' ? KEY_STRING : KEY_NUMBER;
        final byte[] keyBytes = indexKey.substring(2).getBytes(StandardCharsets.UTF_8);
        final ByteBuffer tombstone = buildFrame(OP_DELETE, keyType, (short) bucketOf(indexKey), keyBytes, new byte[0]);
        final byte[] removedBytes;

        rwLock.writeLock().lock();
        try {
            ensureOpen();
            final RecordLocation existing = index.get(indexKey);
            if (existing == null) {
                return StepResult.outcome("not_found");
            }
            removedBytes = readPayloadBytes(journal, existing);
            final long frameOffset = writeOffset;
            writeFully(journal, tombstone, frameOffset);
            journal.force(false);
            writeOffset = frameOffset + HEADER_BYTES + keyBytes.length;
            index.remove(indexKey);
            reindex(indexKey, null);
            livePayloadBytes -= existing.payloadLength();
            deadBytes += (long) HEADER_BYTES + existing.keyLength() + existing.payloadLength()
                    + HEADER_BYTES + keyBytes.length;
            if (shouldCompact()) {
                compactUnderWriteLock();
            }
        } catch (final IOException exception) {
            rollbackJournalTail(exception);
            throw new UncheckedIOException("Database delete failed in collection " + directory + ".", exception);
        } finally {
            rwLock.writeLock().unlock();
        }
        return StepResult.outcome("ok").write("target", parseStoredObject(removedBytes));
    }

    private boolean shouldCompact() {
        return deadBytes >= COMPACT_MIN_DEAD_BYTES && deadBytes > livePayloadBytes;
    }

    private void compactUnderWriteLock() throws IOException {
        final long pauseStart = System.nanoTime();
        final List<Map.Entry<String, RecordLocation>> ordered = new ArrayList<>(index.entrySet());
        ordered.sort(Comparator.comparingLong(entry -> entry.getValue().frameOffset()));

        final Map<String, RecordLocation> compactedIndex = new HashMap<>(Math.max(16, ordered.size() * 2));
        long compactedOffset = 0L;
        long compactedLiveBytes = 0L;
        Files.deleteIfExists(compactPath);
        try (FileChannel target = FileChannel.open(
                compactPath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )) {
            for (final Map.Entry<String, RecordLocation> entry : ordered) {
                final String indexKey = entry.getKey();
                final byte keyType = indexKey.charAt(0) == 's' ? KEY_STRING : KEY_NUMBER;
                final byte[] keyBytes = indexKey.substring(2).getBytes(StandardCharsets.UTF_8);
                final byte[] payloadBytes = readPayloadBytes(journal, entry.getValue());
                final ByteBuffer frame = buildFrame(
                        OP_UPSERT,
                        keyType,
                        (short) bucketOf(indexKey),
                        keyBytes,
                        payloadBytes
                );
                writeFully(target, frame, compactedOffset);
                final long payloadOffset = compactedOffset + HEADER_BYTES + keyBytes.length;
                compactedIndex.put(indexKey, new RecordLocation(payloadOffset, keyBytes.length, payloadBytes.length));
                compactedOffset += (long) HEADER_BYTES + keyBytes.length + payloadBytes.length;
                compactedLiveBytes += payloadBytes.length;
            }
            target.force(true);
        }
        final FileChannel previousJournal = journal;
        journal = null;
        previousJournal.close();
        moveJournal(compactPath, journalPath);
        syncDirectory();
        journal = FileChannel.open(
                journalPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        );
        index.clear();
        index.putAll(compactedIndex);
        writeOffset = compactedOffset;
        livePayloadBytes = compactedLiveBytes;
        deadBytes = 0L;
        lastCompactionPauseNanos = System.nanoTime() - pauseStart;
    }

    private static void moveJournal(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void syncDirectory() {
        try (FileChannel dirChannel = FileChannel.open(directory, StandardOpenOption.READ)) {
            dirChannel.force(true);
        } catch (final IOException | UnsupportedOperationException ignored) {
            // Directory fsync is best-effort on filesystems/OSes that reject directory channels.
        }
    }

    private void rollbackJournalTail(final IOException primaryFailure) {
        if (journal != null && journal.isOpen()) {
            try {
                if (journal.size() > writeOffset) {
                    journal.truncate(writeOffset);
                }
            } catch (final IOException suppressed) {
                primaryFailure.addSuppressed(suppressed);
            }
        }
    }

    private void ensureOpen() {
        if (closed || journal == null || !journal.isOpen()) {
            throw new IllegalStateException("Database collection store is already closed: " + directory);
        }
    }

    private void closeStore() {
        rwLock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            IOException firstFailure = null;
            if (journal != null) {
                try {
                    if (journal.isOpen()) {
                        if (initialized) {
                            journal.force(true);
                        }
                        journal.close();
                    }
                } catch (final IOException failure) {
                    firstFailure = failure;
                } finally {
                    journal = null;
                }
            }
            if (fileLock != null) {
                try {
                    if (fileLock.isValid()) {
                        fileLock.release();
                    }
                } catch (final IOException failure) {
                    if (firstFailure == null) {
                        firstFailure = failure;
                    }
                } finally {
                    fileLock = null;
                }
            }
            if (lockChannel != null) {
                try {
                    lockChannel.close();
                } catch (final IOException failure) {
                    if (firstFailure == null) {
                        firstFailure = failure;
                    }
                } finally {
                    lockChannel = null;
                }
            }
            index.clear();
            indexValueByKey.clear();
            indexKeysByValue.clear();
            if (firstFailure != null) {
                throw new UncheckedIOException("Failed to close database collection " + directory + ".", firstFailure);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void closeQuietly() {
        try {
            closeStore();
        } catch (final RuntimeException ignored) {
            // Preserve the primary startup failure.
        }
    }

    private static RailixValue.ObjectValue prepareDocumentKey(
            final RailixValue.ObjectValue source,
            final String keyField,
            final boolean autoKey
    ) {
        final RailixValue existing = source.values().get(keyField);
        if (existing == null || existing instanceof RailixValue.NullValue) {
            if (!autoKey) {
                return null;
            }
            final Map<String, RailixValue> copy = new TreeMap<>(source.values());
            copy.put(keyField, RailixValue.string(UUID.randomUUID().toString()));
            return RailixValue.object(copy);
        }
        return source;
    }

    private static String encodeIndexKey(final RailixValue key) {
        if (key instanceof RailixValue.StringValue string) {
            final String raw = string.value();
            if (raw.isEmpty() || invalidSurrogate(raw)) {
                return null;
            }
            if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
                return null;
            }
            return "s:" + raw;
        }
        if (key instanceof RailixValue.NumberValue number) {
            final BigDecimal raw = number.value();
            if (!RailixData.fitsCanonicalNumber(raw)) {
                return null;
            }
            final BigDecimal canonical = raw.signum() == 0 ? BigDecimal.ZERO : raw.stripTrailingZeros();
            final String encoded = canonical.toString();
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
                return null;
            }
            return "n:" + encoded;
        }
        return null;
    }

    private static int bucketOf(final String indexKey) {
        final CRC32 crc = new CRC32();
        final byte[] bytes = indexKey.getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return ((int) crc.getValue()) & BUCKET_MASK;
    }

    private static ByteBuffer buildFrame(
            final byte op,
            final byte keyType,
            final short bucket,
            final byte[] keyBytes,
            final byte[] payloadBytes
    ) {
        final ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + keyBytes.length + payloadBytes.length);
        appendFrame(buffer, op, keyType, bucket, keyBytes, payloadBytes);
        buffer.flip();
        return buffer;
    }

    private static void appendFrame(
            final ByteBuffer target,
            final byte op,
            final byte keyType,
            final short bucket,
            final byte[] keyBytes,
            final byte[] payloadBytes
    ) {
        final CRC32 crc = new CRC32();
        crc.update(op & 0xff);
        crc.update(keyType & 0xff);
        crc.update((bucket >>> 8) & 0xff);
        crc.update(bucket & 0xff);
        updateInt(crc, keyBytes.length);
        updateInt(crc, payloadBytes.length);
        crc.update(keyBytes, 0, keyBytes.length);
        crc.update(payloadBytes, 0, payloadBytes.length);

        target.putInt(MAGIC);
        target.put(op);
        target.put(keyType);
        target.putShort(bucket);
        target.putInt(keyBytes.length);
        target.putInt(payloadBytes.length);
        target.putInt((int) crc.getValue());
        target.put(keyBytes);
        target.put(payloadBytes);
    }

    private static int frameChecksum(
            final byte op,
            final byte keyType,
            final short bucket,
            final int keyLength,
            final int payloadLength,
            final byte[] body
    ) {
        final CRC32 crc = new CRC32();
        crc.update(op & 0xff);
        crc.update(keyType & 0xff);
        crc.update((bucket >>> 8) & 0xff);
        crc.update(bucket & 0xff);
        updateInt(crc, keyLength);
        updateInt(crc, payloadLength);
        crc.update(body, 0, body.length);
        return (int) crc.getValue();
    }

    private static void updateInt(final CRC32 crc, final int value) {
        crc.update((value >>> 24) & 0xff);
        crc.update((value >>> 16) & 0xff);
        crc.update((value >>> 8) & 0xff);
        crc.update(value & 0xff);
    }

    private static byte[] readPayloadBytes(
            final FileChannel channel,
            final RecordLocation location
    ) throws IOException {
        final byte[] bytes = new byte[location.payloadLength()];
        if (!readFully(channel, ByteBuffer.wrap(bytes), location.payloadOffset())) {
            throw new IOException("Truncated journal payload at offset " + location.payloadOffset()
                    + " (" + location.payloadLength() + " bytes).");
        }
        return bytes;
    }

    private static boolean readFully(
            final FileChannel channel,
            final ByteBuffer target,
            final long startOffset
    ) throws IOException {
        long position = startOffset;
        while (target.hasRemaining()) {
            final int read = channel.read(target, position);
            if (read < 0) {
                return false;
            }
            position += read;
        }
        return true;
    }

    private static void writeFully(
            final FileChannel channel,
            final ByteBuffer source,
            final long startOffset
    ) throws IOException {
        long position = startOffset;
        while (source.hasRemaining()) {
            position += channel.write(source, position);
        }
    }

    private static String decodeUtf8(final byte[] bytes, final int offset, final int length) {
        try {
            final String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString();
            return invalidSurrogate(decoded) ? null : decoded;
        } catch (final CharacterCodingException invalidUtf8) {
            return null;
        }
    }

    private static boolean validJsonObjectDelimiters(final byte[] bytes, final int offset, final int length) {
        return length >= 2 && bytes[offset] == '{' && bytes[offset + length - 1] == '}';
    }

    private static RailixValue.ObjectValue parseStoredObject(final byte[] payloadBytes) {
        final String text = decodeUtf8(payloadBytes, 0, payloadBytes.length);
        if (text != null && RailixJson.parse(text) instanceof RailixJson.Parsed(RailixValue.ObjectValue object)) {
            return object;
        }
        throw new IllegalStateException(
                "DATABASE_JOURNAL_CORRUPTED: Corrupted stored JSON document in database journal."
        );
    }

    private static RailixValue.ObjectValue canonicalObject(final RailixValue.ObjectValue object) {
        return (RailixValue.ObjectValue) canonicalValue(object);
    }

    private static RailixValue canonicalValue(final RailixValue value) {
        return switch (value) {
            case RailixValue.NullValue ignored -> RailixValue.nullValue();
            case RailixValue.BooleanValue bool -> bool;
            case RailixValue.StringValue string -> string;
            case RailixValue.NumberValue number -> {
                final BigDecimal raw = number.value();
                final BigDecimal stripped = raw.signum() == 0 ? BigDecimal.ZERO : raw.stripTrailingZeros();
                final BigDecimal canonical = stripped.scale() < 0 ? stripped.setScale(0) : stripped;
                yield canonical.equals(raw) ? number : RailixValue.number(canonical);
            }
            case RailixValue.ArrayValue array -> {
                final List<RailixValue> items = new ArrayList<>(array.values().size());
                for (final RailixValue item : array.values()) {
                    items.add(canonicalValue(item));
                }
                yield RailixValue.array(items);
            }
            case RailixValue.ObjectValue object -> {
                final Map<String, RailixValue> fields = new TreeMap<>();
                for (final Map.Entry<String, RailixValue> entry : object.values().entrySet()) {
                    fields.put(entry.getKey(), canonicalValue(entry.getValue()));
                }
                yield RailixValue.object(fields);
            }
        };
    }

    private static boolean invalidSurrogate(final String value) {
        for (int index = 0; index < value.length(); index++) {
            final char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }
}
