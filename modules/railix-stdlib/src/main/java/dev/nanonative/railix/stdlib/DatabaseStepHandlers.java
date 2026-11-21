package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Pure-JDK local document collection persistence Step implementation ({@code railix.database}).
 *
 * <p>Delegates framing/recovery/compaction to {@link DatabaseJournal}, query/filter/projection logic
 * to {@link DatabaseQuery}, and comparable typed keys to {@link DatabaseKey}.</p>
 */
public final class DatabaseStepHandlers {
    static final int BUCKET_COUNT = DatabaseJournal.BUCKET_COUNT;
    static final int MAGIC = DatabaseJournal.MAGIC;
    static final int HEADER_BYTES = DatabaseJournal.HEADER_BYTES;
    static final int DEFAULT_FIND_LIMIT = DatabaseQuery.DEFAULT_FIND_LIMIT;
    static final int MAX_FIND_LIMIT = DatabaseQuery.MAX_FIND_LIMIT;
    static final int MAX_FILTER_DEPTH = DatabaseQuery.MAX_FILTER_DEPTH;

    private static final Set<String> RESERVED_DEVICE_NAMES = Set.of(
            "con", "prn", "aux", "nul",
            "com0", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt0", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    );

    private enum WriteOutcome {
        OK, NOT_FOUND, INVALID
    }

    private final String collectionName;
    private final DatabaseJournal journal;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Map<DatabaseKey, DatabaseJournal.RecordLocation> index = new HashMap<>();
    private final NavigableSet<DatabaseKey> sortedKeys = new TreeSet<>();
    /** Secondary index field -> primary key -> indexed scalar; mutated only under write lock. */
    private final Map<String, Map<DatabaseKey, DatabaseKey>> indexValueByKey = new HashMap<>();
    /** Secondary index field -> ordered scalar -> sorted primary keys; mutated only under write lock. */
    private final Map<String, NavigableMap<DatabaseKey, NavigableSet<DatabaseKey>>> indexKeysByValue = new HashMap<>();
    /** Immutable snapshots replaced only during PRE, so request preparation reads without locking. */
    private volatile Set<String> uniqueFields = Set.of();
    private volatile List<String> indexedFields = List.of();
    private volatile Map<String, List<String>> fieldPathCache = Map.of();
    private String configuredKeyField;

    private DatabaseStepHandlers(final String collectionName, final Path collectionDirectory) {
        this.collectionName = collectionName;
        this.journal = new DatabaseJournal(collectionDirectory);
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
                instance -> instance.closeStore(false)
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
     * {@code upsert} replaces the stored record for each document and, when {@code fields} is non-empty,
     * persists only {@code key_field} plus the listed top-level fields (dropping unlisted input fields).
     * {@code get} and {@code find} apply {@code fields} and {@code defaults} only to the returned view.
     *
     * @param configuration compiled Step settings
     * @param input resolved runtime invocation values
     * @return {@code ok} (with {@code target} write), {@code not_found}, or {@code invalid}
     * @throws InterruptedException when the calling virtual thread is interrupted while waiting or scanning
     */
    public static StepResult process(final StepInput configuration, final StepInput input)
            throws InterruptedException {
        final DatabaseStepHandlers store = input.resource("store", DatabaseStepHandlers.class);
        final String keyField = configuration.string("key_field");
        final String operation = input.option("operation");
        final StepInput selected = input.selected("operation");
        return switch (operation) {
            case "upsert" -> store.upsert(
                    keyField,
                    booleanOption(selected, "auto_key"),
                    fieldNames(selected.optionalValue("fields")),
                    selected.optionalValue("document").orElse(null),
                    DatabaseQuery.parseCondition(configuration, selected)
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
            case "delete" -> store.delete(
                    keyField,
                    selected.optionalValue("key").orElse(null),
                    DatabaseQuery.parseCondition(configuration, selected)
            );
            default -> throw new IllegalArgumentException("Unsupported database operation: " + operation);
        };
    }

    static int bucketForKey(final RailixValue key) {
        final DatabaseKey encoded = DatabaseKey.encodePrimary(key);
        if (encoded == null) {
            throw new IllegalArgumentException("Database key must be a valid non-empty string or canonical number.");
        }
        return encoded.bucket();
    }

    long journalSizeBytes() {
        rwLock.readLock().lock();
        try {
            return journal.sizeBytes();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    long lastCompactionPauseNanos() {
        return journal.lastCompactionPauseNanos();
    }

    static Optional<String> fieldListRejection(final RailixValue.ArrayValue value) {
        return DatabaseQuery.fieldListRejection(value, false);
    }

    static Optional<String> indexFieldListRejection(final RailixValue.ArrayValue value) {
        return DatabaseQuery.fieldListRejection(value, true);
    }

    static Optional<String> defaultsRejection(final RailixValue.ObjectValue value) {
        return DatabaseQuery.defaultsRejection(value);
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
        return !reservedDeviceName(value);
    }

    private static boolean reservedDeviceName(final String segment) {
        final int dot = segment.indexOf('.');
        final String stem = (dot >= 0 ? segment.substring(0, dot) : segment).toLowerCase(Locale.ROOT);
        return RESERVED_DEVICE_NAMES.contains(stem);
    }

    static boolean validStorageDirectory(final String value) {
        if (value == null || value.isBlank() || value.length() > 512 || DatabaseKey.invalidSurrogate(value)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c < 32 || c == 127 || c == '<' || c == '>' || c == '"' || c == '|' || c == '?' || c == '*') {
                return false;
            }
            if (c == ':' && !(i == 1 && asciiLetter(value.charAt(0)))) {
                return false;
            }
        }
        int start = 0;
        if (value.length() >= 2 && value.charAt(1) == ':') {
            if (value.length() < 3 || (value.charAt(2) != '/' && value.charAt(2) != '\\')) {
                return false;
            }
            start = 3;
        }
        try {
            Path.of(value);
        } catch (final InvalidPathException invalid) {
            return false;
        }
        int segmentCount = 0;
        final int length = value.length();
        int cursor = start;
        while (cursor < length) {
            while (cursor < length && (value.charAt(cursor) == '/' || value.charAt(cursor) == '\\')) {
                cursor++;
            }
            if (cursor >= length) {
                break;
            }
            int end = cursor;
            while (end < length && value.charAt(end) != '/' && value.charAt(end) != '\\') {
                end++;
            }
            final String part = value.substring(cursor, end);
            if ("..".equals(part) || ".".equals(part)
                    || part.endsWith(" ") || part.endsWith(".")
                    || reservedDeviceName(part)) {
                return false;
            }
            segmentCount++;
            cursor = end + 1;
        }
        return segmentCount > 0;
    }

    static boolean validKeyField(final String value) {
        return DatabaseQuery.validFieldName(value);
    }

    private static DatabaseStepHandlers openStore(final String collectionName, final Path collectionDirectory) {
        final DatabaseStepHandlers store = new DatabaseStepHandlers(collectionName, collectionDirectory);
        try {
            store.journal.initialize(collectionName, store.index, store.sortedKeys);
            return store;
        } catch (final IOException exception) {
            store.closeStore(true);
            throw new UncheckedIOException(
                    "Cannot initialize database collection at " + collectionDirectory + ".",
                    exception
            );
        } catch (final RuntimeException | Error failure) {
            store.closeStore(true);
            throw failure;
        }
    }

    private void ensureIndexes(
            final String requestedCollection,
            final String keyField,
            final List<String> unique,
            final List<String> indexed
    ) {
        rwLock.writeLock().lock();
        final List<String> added = new ArrayList<>();
        try {
            journal.ensureOpen();
            if (!collectionName.equals(requestedCollection)) {
                throw new IllegalStateException("DATABASE_COLLECTION_COLLISION: Directory " + journal.directory()
                        + " belongs to collection '" + collectionName + "', not '" + requestedCollection + "'.");
            }
            if (configuredKeyField == null) {
                configuredKeyField = keyField;
            } else if (!configuredKeyField.equals(keyField)) {
                throw new IllegalStateException("DATABASE_KEY_FIELD_MISMATCH: Collection '" + collectionName
                        + "' in " + journal.directory() + " uses key_field '" + configuredKeyField
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
            final Map<String, List<String>> nextPathCache = new HashMap<>(fieldPathCache);
            for (final String field : nextIndexed) {
                nextPathCache.computeIfAbsent(field, DatabaseQuery::splitFieldPath);
            }
            for (final String field : added) {
                indexValueByKey.put(field, new HashMap<>());
                indexKeysByValue.put(field, new TreeMap<>());
            }
            for (final Map.Entry<DatabaseKey, DatabaseJournal.RecordLocation> entry : index.entrySet()) {
                final RailixValue.ObjectValue document =
                        DatabaseJournal.parseStoredObject(journal.readPayloadBytesUnderWriteLock(entry.getValue()));
                for (final String field : nextUnique) {
                    if (!uniqueCompatible(DatabaseQuery.readPathValue(document, nextPathCache.get(field)))) {
                        throw new IllegalStateException("DATABASE_UNIQUE_INDEX_VIOLATION: Unique field " + field
                                + " holds an array or object value in collection " + journal.directory() + ".");
                    }
                }
                for (final String field : added) {
                    addIndexEntry(
                            field,
                            entry.getKey(),
                            DatabaseQuery.readPathValue(document, nextPathCache.get(field))
                    );
                }
            }
            for (final String field : nextUnique) {
                for (final NavigableSet<DatabaseKey> holders : indexKeysByValue.get(field).values()) {
                    if (holders.size() > 1) {
                        throw new IllegalStateException("DATABASE_UNIQUE_INDEX_VIOLATION: Unique field " + field
                                + " has duplicate stored values in collection " + journal.directory() + ".");
                    }
                }
            }
            fieldPathCache = Map.copyOf(nextPathCache);
            uniqueFields = Set.copyOf(nextUnique);
            indexedFields = List.copyOf(nextIndexed);
            added.clear();
        } catch (final IOException exception) {
            throw new UncheckedIOException(
                    "Cannot build database indexes in collection " + journal.directory() + ".",
                    exception
            );
        } finally {
            for (final String field : added) {
                indexValueByKey.remove(field);
                indexKeysByValue.remove(field);
            }
            rwLock.writeLock().unlock();
        }
    }

    private StepResult upsert(
            final String keyField,
            final boolean autoKey,
            final List<String> fields,
            final RailixValue candidate,
            final DatabaseQuery.ParsedCondition condition
    ) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("Database upsert interrupted.");
        }
        if (!condition.valid()) {
            return StepResult.outcome("invalid");
        }
        if (candidate instanceof RailixValue.ArrayValue array) {
            return upsertBatch(keyField, autoKey, fields, array, condition);
        }
        if (!(candidate instanceof RailixValue.ObjectValue rawObject)) {
            return StepResult.outcome("invalid");
        }
        final DatabaseJournal.PreparedWrite prepared = prepareWrite(rawObject, keyField, autoKey, fields);
        if (prepared == null) {
            return StepResult.outcome("invalid");
        }
        final WriteOutcome outcome = commitPreparedWrites(
                List.of(prepared),
                Map.of(prepared.indexKey(), prepared.document()),
                keyField,
                condition.filter()
        );
        return switch (outcome) {
            case OK -> StepResult.outcome("ok").write("target", prepared.document());
            case NOT_FOUND -> StepResult.outcome("not_found");
            case INVALID -> StepResult.outcome("invalid");
        };
    }

    private StepResult upsertBatch(
            final String keyField,
            final boolean autoKey,
            final List<String> fields,
            final RailixValue.ArrayValue array,
            final DatabaseQuery.ParsedCondition condition
    ) throws InterruptedException {
        final List<RailixValue> items = array.values();
        if (items.isEmpty()) {
            if (Thread.interrupted()) {
                throw new InterruptedException("Database upsert interrupted.");
            }
            rwLock.readLock().lockInterruptibly();
            try {
                journal.ensureOpen();
            } finally {
                rwLock.readLock().unlock();
            }
            return StepResult.outcome("ok").write("target", RailixValue.array(List.of()));
        }
        final int count = items.size();
        final List<DatabaseJournal.PreparedWrite> preparedWrites = new ArrayList<>(count);
        final List<RailixValue> savedDocuments = new ArrayList<>(count);
        final Map<DatabaseKey, RailixValue.ObjectValue> finalDocuments = new LinkedHashMap<>();
        long totalFrameBytes = 0L;

        for (int i = 0; i < count; i++) {
            if (!(items.get(i) instanceof RailixValue.ObjectValue rawObject)) {
                return StepResult.outcome("invalid");
            }
            final DatabaseJournal.PreparedWrite prepared = prepareWrite(rawObject, keyField, autoKey, fields);
            if (prepared == null) {
                return StepResult.outcome("invalid");
            }
            totalFrameBytes += prepared.frameBytes();
            if (totalFrameBytes > DatabaseJournal.MAX_BULK_FRAME_BYTES) {
                return StepResult.outcome("invalid");
            }
            preparedWrites.add(prepared);
            savedDocuments.add(prepared.document());
            finalDocuments.put(prepared.indexKey(), prepared.document());
        }
        final WriteOutcome outcome = commitPreparedWrites(
                preparedWrites,
                finalDocuments,
                keyField,
                condition.filter()
        );
        return switch (outcome) {
            case OK -> StepResult.outcome("ok").write("target", RailixValue.array(savedDocuments));
            case NOT_FOUND -> StepResult.outcome("not_found");
            case INVALID -> StepResult.outcome("invalid");
        };
    }

    private DatabaseJournal.PreparedWrite prepareWrite(
            final RailixValue.ObjectValue rawObject,
            final String keyField,
            final boolean autoKey,
            final List<String> fields
    ) {
        final RailixValue.ObjectValue document = prepareDocumentKey(rawObject, keyField, autoKey);
        if (document == null) {
            return null;
        }
        final DatabaseKey indexKey = DatabaseKey.encodePrimary(document.values().get(keyField));
        if (indexKey == null || DatabaseQuery.DOCUMENT_REFINEMENT.rejection(document).isPresent()) {
            return null;
        }
        final RailixValue.ObjectValue canonicalDocument =
                DatabaseKey.canonicalObject(DatabaseQuery.project(document, keyField, fields));
        if (!uniqueFieldsCompatible(uniqueFields, canonicalDocument)) {
            return null;
        }
        final Optional<String> serialized = RailixJson.write(canonicalDocument, DatabaseJournal.MAX_PAYLOAD_BYTES);
        if (serialized.isEmpty()) {
            return null;
        }
        return new DatabaseJournal.PreparedWrite(
                indexKey,
                indexKey.wireKeyType(),
                (short) indexKey.bucket(),
                indexKey.wireKeyBytes(),
                serialized.get().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                canonicalDocument
        );
    }

    private WriteOutcome commitPreparedWrites(
            final List<DatabaseJournal.PreparedWrite> writes,
            final Map<DatabaseKey, RailixValue.ObjectValue> finalDocuments,
            final String keyField,
            final DatabaseQuery.FilterNode condition
    ) throws InterruptedException {
        rwLock.writeLock().lockInterruptibly();
        try {
            journal.ensureOpen();
            if (condition != null && !conditionsSatisfiedUnderLock(writes, keyField, condition)) {
                return WriteOutcome.NOT_FOUND;
            }
            if (!uniqueFields.isEmpty() && uniqueConflict(finalDocuments)) {
                return WriteOutcome.INVALID;
            }
            if (Thread.interrupted()) {
                throw new InterruptedException("Database upsert interrupted.");
            }
            journal.appendUpserts(writes, index, sortedKeys);
            for (final Map.Entry<DatabaseKey, RailixValue.ObjectValue> entry : finalDocuments.entrySet()) {
                reindex(entry.getKey(), entry.getValue());
            }
            journal.tryCompactAfterCommit(index);
            return WriteOutcome.OK;
        } catch (final IOException exception) {
            throw new UncheckedIOException(
                    "Database upsert failed in collection " + journal.directory() + ".",
                    exception
            );
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private boolean conditionsSatisfiedUnderLock(
            final List<DatabaseJournal.PreparedWrite> writes,
            final String keyField,
            final DatabaseQuery.FilterNode condition
    ) throws IOException {
        final Map<DatabaseKey, RailixValue.ObjectValue> staged = new HashMap<>();
        for (final DatabaseJournal.PreparedWrite write : writes) {
            final DatabaseKey indexKey = write.indexKey();
            final RailixValue.ObjectValue current;
            if (staged.containsKey(indexKey)) {
                current = staged.get(indexKey);
            } else {
                final DatabaseJournal.RecordLocation existingLoc = index.get(indexKey);
                current = existingLoc == null
                        ? null
                        : DatabaseJournal.parseStoredObject(journal.readPayloadBytesUnderWriteLock(existingLoc));
            }
            if (!DatabaseQuery.matchesCondition(current, condition, keyField)) {
                return false;
            }
            staged.put(indexKey, write.document());
        }
        return true;
    }

    private StepResult get(
            final String keyField,
            final List<String> fields,
            final RailixValue.ObjectValue defaults,
            final RailixValue keyCandidate
    ) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("Database get interrupted.");
        }
        final DatabaseKey indexKey = DatabaseKey.encodePrimary(keyCandidate);
        if (indexKey == null) {
            return StepResult.outcome("invalid");
        }
        final byte[] payloadBytes;
        rwLock.readLock().lockInterruptibly();
        try {
            journal.ensureOpen();
            final DatabaseJournal.RecordLocation location = index.get(indexKey);
            if (location == null) {
                return StepResult.outcome("not_found");
            }
            payloadBytes = journal.readPayloadBytes(location);
        } catch (final IOException exception) {
            throw new UncheckedIOException(
                    "Database get failed in collection " + journal.directory() + ".",
                    exception
            );
        } finally {
            rwLock.readLock().unlock();
        }
        final RailixValue.ObjectValue stored = DatabaseJournal.parseStoredObject(payloadBytes);
        return StepResult.outcome("ok").write("target", DatabaseQuery.readView(stored, keyField, fields, defaults));
    }

    private StepResult find(
            final String keyField,
            final List<String> fields,
            final RailixValue.ObjectValue defaults,
            final int limit,
            final RailixValue filterCandidate,
            final RailixValue afterCandidate
    ) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("Database find interrupted.");
        }
        final DatabaseQuery.FilterNode filter = DatabaseQuery.parseFilter(filterCandidate);
        if (filter == null) {
            return StepResult.outcome("invalid");
        }
        final DatabaseKey afterKey;
        if (afterCandidate == null || afterCandidate instanceof RailixValue.NullValue) {
            afterKey = null;
        } else {
            afterKey = DatabaseKey.encodePrimary(afterCandidate);
            if (afterKey == null) {
                return StepResult.outcome("invalid");
            }
        }

        final List<RailixValue> results = new ArrayList<>();
        rwLock.readLock().lockInterruptibly();
        try {
            journal.ensureOpen();
            final NavigableSet<DatabaseKey> candidateKeys = resolveCandidateKeys(keyField, filter, afterKey);
            if (candidateKeys != null && candidateKeys.isEmpty()) {
                return StepResult.outcome("ok").write("target", RailixValue.array(List.of()));
            }
            final NavigableSet<DatabaseKey> rawOrdered = candidateKeys != null ? candidateKeys : sortedKeys;
            final NavigableSet<DatabaseKey> orderedKeys = DatabaseQuery.tailAfter(rawOrdered, afterKey);

            for (final DatabaseKey indexKey : orderedKeys) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("Database find interrupted.");
                }
                final DatabaseJournal.RecordLocation location = index.get(indexKey);
                if (location == null) {
                    continue;
                }
                final byte[] payloadBytes = journal.readPayloadBytes(location);
                final RailixValue.ObjectValue stored = DatabaseJournal.parseStoredObject(payloadBytes);
                if (DatabaseQuery.matchesFilterNode(stored, filter)) {
                    results.add(DatabaseQuery.readView(stored, keyField, fields, defaults));
                    if (results.size() >= limit) {
                        break;
                    }
                }
            }
        } catch (final IOException exception) {
            throw new UncheckedIOException(
                    "Database find failed in collection " + journal.directory() + ".",
                    exception
            );
        } finally {
            rwLock.readLock().unlock();
        }
        return StepResult.outcome("ok").write("target", RailixValue.array(results));
    }

    private StepResult delete(
            final String keyField,
            final RailixValue keyCandidate,
            final DatabaseQuery.ParsedCondition condition
    ) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException("Database delete interrupted.");
        }
        if (!condition.valid()) {
            return StepResult.outcome("invalid");
        }
        final DatabaseKey indexKey = DatabaseKey.encodePrimary(keyCandidate);
        if (indexKey == null) {
            return StepResult.outcome("invalid");
        }
        final byte[] removedBytes;
        RailixValue.ObjectValue deletedDocument = null;

        rwLock.writeLock().lockInterruptibly();
        try {
            journal.ensureOpen();
            final DatabaseJournal.RecordLocation existing = index.get(indexKey);
            if (existing == null) {
                return StepResult.outcome("not_found");
            }
            removedBytes = journal.readPayloadBytesUnderWriteLock(existing);
            if (condition.filter() != null) {
                deletedDocument = DatabaseJournal.parseStoredObject(removedBytes);
                if (!DatabaseQuery.matchesCondition(deletedDocument, condition.filter(), keyField)) {
                    return StepResult.outcome("not_found");
                }
            }
            if (Thread.interrupted()) {
                throw new InterruptedException("Database delete interrupted.");
            }
            journal.appendDelete(indexKey, existing, index, sortedKeys);
            reindex(indexKey, null);
            journal.tryCompactAfterCommit(index);
        } catch (final IOException exception) {
            throw new UncheckedIOException(
                    "Database delete failed in collection " + journal.directory() + ".",
                    exception
            );
        } finally {
            rwLock.writeLock().unlock();
        }
        final RailixValue.ObjectValue returned = deletedDocument != null
                ? deletedDocument
                : DatabaseJournal.parseStoredObject(removedBytes);
        return StepResult.outcome("ok").write("target", returned);
    }

    private NavigableSet<DatabaseKey> resolveCandidateKeys(
            final String keyField,
            final DatabaseQuery.FilterNode node,
            final DatabaseKey afterKey
    ) {
        return switch (node) {
            case DatabaseQuery.AndNode and -> intersectClauseCandidates(keyField, and.children(), afterKey);
            case DatabaseQuery.OrNode or -> unionBranchCandidates(keyField, or.children(), afterKey);
            case DatabaseQuery.FieldNode field -> resolveFieldCandidates(keyField, field, afterKey);
        };
    }

    private NavigableSet<DatabaseKey> intersectClauseCandidates(
            final String keyField,
            final List<DatabaseQuery.FilterNode> children,
            final DatabaseKey afterKey
    ) {
        if (children.isEmpty()) {
            return null;
        }
        List<NavigableSet<DatabaseKey>> matchedSets = null;
        for (final DatabaseQuery.FilterNode child : children) {
            final NavigableSet<DatabaseKey> candidates = resolveCandidateKeys(keyField, child, afterKey);
            if (candidates == null) {
                continue;
            }
            if (candidates.isEmpty()) {
                return Collections.emptyNavigableSet();
            }
            if (matchedSets == null) {
                matchedSets = new ArrayList<>(2);
            }
            matchedSets.add(candidates);
        }
        return intersectMatchedSets(matchedSets, afterKey);
    }

    private NavigableSet<DatabaseKey> unionBranchCandidates(
            final String keyField,
            final List<DatabaseQuery.FilterNode> branches,
            final DatabaseKey afterKey
    ) {
        final NavigableSet<DatabaseKey> union = new TreeSet<>();
        for (final DatabaseQuery.FilterNode branch : branches) {
            final NavigableSet<DatabaseKey> candidates = resolveCandidateKeys(keyField, branch, afterKey);
            if (candidates == null) {
                return null;
            }
            if (!candidates.isEmpty()) {
                union.addAll(DatabaseQuery.tailAfter(candidates, afterKey));
            }
        }
        return union;
    }

    private NavigableSet<DatabaseKey> resolveFieldCandidates(
            final String keyField,
            final DatabaseQuery.FieldNode field,
            final DatabaseKey afterKey
    ) {
        if (field.fieldPath().equals(keyField)) {
            return resolvePrimaryKeyCandidates(field.predicates(), afterKey);
        }
        final NavigableMap<DatabaseKey, NavigableSet<DatabaseKey>> byValue = indexKeysByValue.get(field.fieldPath());
        if (byValue == null) {
            return null;
        }
        List<NavigableSet<DatabaseKey>> matchedSets = null;
        for (final DatabaseQuery.FieldPredicate predicate : field.predicates()) {
            final NavigableSet<DatabaseKey> set = switch (predicate.op()) {
                case EQ -> {
                    final DatabaseKey enc = DatabaseKey.encodeIndexValue(predicate.scalar());
                    yield enc == null ? null : byValue.getOrDefault(enc, Collections.emptyNavigableSet());
                }
                case IN -> secondaryInCandidates(byValue, predicate.set(), afterKey);
                default -> null;
            };
            if (set != null) {
                if (set.isEmpty()) {
                    return Collections.emptyNavigableSet();
                }
                if (matchedSets == null) {
                    matchedSets = new ArrayList<>(2);
                }
                matchedSets.add(set);
            }
        }
        final DatabaseQuery.RangeBounds bounds = DatabaseQuery.rangeBounds(field.predicates());
        if (bounds != null) {
            if (bounds.empty()) {
                return Collections.emptyNavigableSet();
            }
            final NavigableSet<DatabaseKey> rangeMatches = new TreeSet<>();
            DatabaseQuery.collectSecondaryRangeKeys(byValue, bounds, afterKey, rangeMatches);
            if (rangeMatches.isEmpty()) {
                return Collections.emptyNavigableSet();
            }
            if (matchedSets == null) {
                matchedSets = new ArrayList<>(1);
            }
            matchedSets.add(rangeMatches);
        }
        return intersectMatchedSets(matchedSets, afterKey);
    }

    private static NavigableSet<DatabaseKey> secondaryInCandidates(
            final NavigableMap<DatabaseKey, NavigableSet<DatabaseKey>> byValue,
            final Set<RailixValue> items,
            final DatabaseKey afterKey
    ) {
        final NavigableSet<DatabaseKey> union = new TreeSet<>();
        for (final RailixValue item : items) {
            final DatabaseKey enc = DatabaseKey.encodeIndexValue(item);
            if (enc == null) {
                return null;
            }
            final NavigableSet<DatabaseKey> posting = byValue.get(enc);
            if (posting != null) {
                union.addAll(DatabaseQuery.tailAfter(posting, afterKey));
            }
        }
        return union;
    }

    private NavigableSet<DatabaseKey> resolvePrimaryKeyCandidates(
            final List<DatabaseQuery.FieldPredicate> predicates,
            final DatabaseKey afterKey
    ) {
        List<NavigableSet<DatabaseKey>> matchedSets = null;
        for (final DatabaseQuery.FieldPredicate predicate : predicates) {
            final NavigableSet<DatabaseKey> set = switch (predicate.op()) {
                case EQ -> {
                    final DatabaseKey key = DatabaseKey.encodePrimary(predicate.scalar());
                    if (key == null || !index.containsKey(key)) {
                        yield Collections.emptyNavigableSet();
                    }
                    final NavigableSet<DatabaseKey> single = new TreeSet<>();
                    single.add(key);
                    yield single;
                }
                case IN -> primaryInCandidates(predicate.set(), afterKey);
                case EXISTS -> predicate.boolValue() ? null : Collections.emptyNavigableSet();
                default -> null;
            };
            if (set != null) {
                if (set.isEmpty()) {
                    return Collections.emptyNavigableSet();
                }
                if (matchedSets == null) {
                    matchedSets = new ArrayList<>(2);
                }
                matchedSets.add(set);
            }
        }
        final DatabaseQuery.RangeBounds bounds = DatabaseQuery.rangeBounds(predicates);
        if (bounds != null) {
            final NavigableSet<DatabaseKey> rangeView = DatabaseQuery.primaryRangeView(sortedKeys, bounds);
            if (rangeView.isEmpty()) {
                return Collections.emptyNavigableSet();
            }
            if (matchedSets == null) {
                matchedSets = new ArrayList<>(1);
            }
            matchedSets.add(rangeView);
        }
        return intersectMatchedSets(matchedSets, afterKey);
    }

    private NavigableSet<DatabaseKey> primaryInCandidates(final Set<RailixValue> items, final DatabaseKey afterKey) {
        final NavigableSet<DatabaseKey> matches = new TreeSet<>();
        for (final RailixValue item : items) {
            final DatabaseKey key = DatabaseKey.encodePrimary(item);
            if (key != null && (afterKey == null || key.compareTo(afterKey) > 0) && index.containsKey(key)) {
                matches.add(key);
            }
        }
        return matches;
    }

    private static NavigableSet<DatabaseKey> intersectMatchedSets(
            final List<NavigableSet<DatabaseKey>> matchedSets,
            final DatabaseKey afterKey
    ) {
        if (matchedSets == null) {
            return null;
        }
        if (matchedSets.size() == 1) {
            return matchedSets.getFirst();
        }
        matchedSets.sort(Comparator.comparingInt(DatabaseStepHandlers::estimatedSetSize));
        final NavigableSet<DatabaseKey> smallest = matchedSets.getFirst();
        final NavigableSet<DatabaseKey> slice = DatabaseQuery.tailAfter(smallest, afterKey);
        final NavigableSet<DatabaseKey> intersection = new TreeSet<>();
        for (final DatabaseKey key : slice) {
            boolean inAll = true;
            for (int i = 1; i < matchedSets.size(); i++) {
                if (!matchedSets.get(i).contains(key)) {
                    inAll = false;
                    break;
                }
            }
            if (inAll) {
                intersection.add(key);
            }
        }
        return intersection;
    }

    private static int estimatedSetSize(final NavigableSet<DatabaseKey> set) {
        return set instanceof TreeSet<?> ? set.size() : Integer.MAX_VALUE;
    }

    private void addIndexEntry(final String field, final DatabaseKey indexKey, final RailixValue value) {
        final DatabaseKey encoded = DatabaseKey.encodeIndexValue(value);
        if (encoded != null) {
            indexValueByKey.get(field).put(indexKey, encoded);
            indexKeysByValue.get(field).computeIfAbsent(encoded, ignored -> new TreeSet<>()).add(indexKey);
        }
    }

    private void reindex(final DatabaseKey indexKey, final RailixValue.ObjectValue document) {
        for (final String field : indexedFields) {
            final DatabaseKey previous = indexValueByKey.get(field).remove(indexKey);
            if (previous != null) {
                final NavigableMap<DatabaseKey, NavigableSet<DatabaseKey>> byValue = indexKeysByValue.get(field);
                final NavigableSet<DatabaseKey> holders = byValue.get(previous);
                holders.remove(indexKey);
                if (holders.isEmpty()) {
                    byValue.remove(previous);
                }
            }
            if (document != null) {
                addIndexEntry(field, indexKey, DatabaseQuery.readPathValue(document, cachedFieldPath(field)));
            }
        }
    }

    private boolean uniqueConflict(final Map<DatabaseKey, RailixValue.ObjectValue> finalDocuments) {
        for (final String field : uniqueFields) {
            final List<String> segments = cachedFieldPath(field);
            final Map<DatabaseKey, DatabaseKey> claimed = new HashMap<>();
            for (final Map.Entry<DatabaseKey, RailixValue.ObjectValue> entry : finalDocuments.entrySet()) {
                final DatabaseKey encoded =
                        DatabaseKey.encodeIndexValue(DatabaseQuery.readPathValue(entry.getValue(), segments));
                if (encoded != null && claimed.putIfAbsent(encoded, entry.getKey()) != null) {
                    return true;
                }
            }
            final NavigableMap<DatabaseKey, NavigableSet<DatabaseKey>> byValue = indexKeysByValue.get(field);
            for (final Map.Entry<DatabaseKey, DatabaseKey> claim : claimed.entrySet()) {
                for (final DatabaseKey holder : byValue.getOrDefault(claim.getKey(), Collections.emptyNavigableSet())) {
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

    private boolean uniqueFieldsCompatible(final Set<String> unique, final RailixValue.ObjectValue document) {
        for (final String field : unique) {
            if (!uniqueCompatible(DatabaseQuery.readPathValue(document, cachedFieldPath(field)))) {
                return false;
            }
        }
        return true;
    }

    private List<String> cachedFieldPath(final String field) {
        final List<String> cached = fieldPathCache.get(field);
        return cached != null ? cached : DatabaseQuery.splitFieldPath(field);
    }

    private static boolean booleanOption(final StepInput selected, final String name) {
        return ((RailixValue.BooleanValue) selected.value(name)).value();
    }

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
        return selected.optionalValue("defaults").orElse(DatabaseQuery.NO_DEFAULTS)
                instanceof RailixValue.ObjectValue object ? object : DatabaseQuery.NO_DEFAULTS;
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

    private static boolean asciiLetter(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean lowercaseAlphanumeric(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static Path defaultBaseDirectory() {
        final String prop = System.getProperty("railix.data.dir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop.strip()).toAbsolutePath().normalize();
        }
        final String env = System.getenv("RAILIX_DATA_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env.strip()).toAbsolutePath().normalize();
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
            } catch (final URISyntaxException | IllegalArgumentException
                    | FileSystemNotFoundException | SecurityException ignored) {
                // Fall back to the process working directory when code source is not a file path.
            }
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private void closeStore(final boolean quiet) {
        rwLock.writeLock().lock();
        try {
            if (quiet) {
                journal.closeQuietly();
            } else {
                journal.close();
            }
            index.clear();
            sortedKeys.clear();
            indexValueByKey.clear();
            indexKeysByValue.clear();
        } finally {
            rwLock.writeLock().unlock();
        }
    }
}
