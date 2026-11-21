package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.zip.CRC32;

/** Append-only WAL journal, exclusive lock owner, crash recovery, and resilient compaction. */
final class DatabaseJournal {
    static final int BUCKET_COUNT = 1_024;
    static final int BUCKET_MASK = BUCKET_COUNT - 1;
    static final int MAGIC = 0x5244_4231;
    static final int HEADER_BYTES = 20;

    static final byte OP_UPSERT = 1;
    static final byte OP_DELETE = 2;
    static final byte KEY_STRING = 1;
    static final byte KEY_NUMBER = 2;

    static final int MAX_KEY_BYTES = 1_024;
    static final int MAX_PAYLOAD_BYTES = RailixData.DEFAULT_MAX_SOURCE_BYTES;
    static final int MAX_BULK_FRAME_BYTES = RailixData.MAX_SOURCE_BYTES;
    private static final long COMPACT_MIN_DEAD_BYTES = 65_536L;

    record RecordLocation(long payloadOffset, int keyLength, int payloadLength) {
        long frameOffset() {
            return payloadOffset - HEADER_BYTES - keyLength;
        }
    }

    record PreparedWrite(
            DatabaseKey indexKey,
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

    private final Path directory;
    private final Path lockPath;
    private final Path journalPath;
    private final Path compactPath;
    private final Object readChannelLock = new Object();

    private FileChannel lockChannel;
    private FileLock fileLock;
    private RandomAccessFile writeFile;
    private volatile FileChannel readChannel;
    private long writeOffset;
    private long livePayloadBytes;
    private long deadBytes;
    private long compactionBackoffDeadBytes;
    private volatile long lastCompactionPauseNanos;
    private boolean initialized;
    private boolean closed;

    DatabaseJournal(final Path collectionDirectory) {
        this.directory = collectionDirectory;
        this.lockPath = collectionDirectory.resolve("collection.lock");
        this.journalPath = collectionDirectory.resolve("journal.wal");
        this.compactPath = collectionDirectory.resolve("journal.wal.compact");
    }

    Path directory() {
        return directory;
    }

    long sizeBytes() {
        return writeOffset;
    }

    long lastCompactionPauseNanos() {
        return lastCompactionPauseNanos;
    }

    void ensureOpen() {
        if (closed || writeFile == null) {
            throw new IllegalStateException("Database collection store is already closed: " + directory);
        }
    }

    void initialize(
            final String collectionName,
            final Map<DatabaseKey, RecordLocation> index,
            final NavigableSet<DatabaseKey> sortedKeys
    ) throws IOException {
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
        verifyOrRecordCollectionOwner(collectionName);
        if (!Files.exists(journalPath) && Files.exists(compactPath)) {
            moveJournal(compactPath, journalPath);
        } else {
            Files.deleteIfExists(compactPath);
        }
        openJournalHandles();
        replay(index, sortedKeys);
        if (shouldCompact()) {
            compact(index);
        }
        initialized = true;
    }

    private void verifyOrRecordCollectionOwner(final String collectionName) throws IOException {
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
        lockChannel.truncate(ownerBytes.length);
        lockChannel.force(false);
    }

    /**
     * Replays committed frames from {@code journal.wal}. Only an incomplete trailing write at EOF is
     * truncated; invalid headers, checksums, corrupt keys, or broken payloads leave the file untouched.
     */
    private void replay(
            final Map<DatabaseKey, RecordLocation> index,
            final NavigableSet<DatabaseKey> sortedKeys
    ) throws IOException {
        final long fileSize = writeFile.length();
        final byte[] headerBytes = new byte[HEADER_BYTES];
        final ByteBuffer header = ByteBuffer.wrap(headerBytes);
        long offset = 0L;
        boolean incompleteTrailingWrite = false;

        while (offset < fileSize) {
            final long remaining = fileSize - offset;
            if (remaining < HEADER_BYTES) {
                final byte[] partial = new byte[(int) remaining];
                if (!readFully(writeFile, partial, offset)) {
                    incompleteTrailingWrite = true;
                    break;
                }
                if (!validPartialHeader(partial)) {
                    throw corruptedJournal(offset, "Invalid partial frame header");
                }
                incompleteTrailingWrite = true;
                break;
            }
            if (!readFully(writeFile, headerBytes, offset)) {
                incompleteTrailingWrite = true;
                break;
            }
            header.position(0);
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
            if (!readFully(writeFile, body, offset + HEADER_BYTES)) {
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
            if (DatabaseKey.bucketOf(keyType, rawKey) != bucket) {
                throw corruptedJournal(offset, "Key partition bucket mismatch");
            }
            final DatabaseKey indexKey = DatabaseKey.fromJournal(keyType, rawKey);
            if (indexKey == null) {
                throw corruptedJournal(offset, "Invalid key value");
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
            parseStoredObject(readPayloadBytesUnderWriteLock(location));
        }
        if (incompleteTrailingWrite && offset < fileSize) {
            writeFile.setLength(offset);
            writeFile.getFD().sync();
        }
        sortedKeys.clear();
        sortedKeys.addAll(index.keySet());
        writeOffset = offset;
    }

    void appendUpserts(
            final List<PreparedWrite> writes,
            final Map<DatabaseKey, RecordLocation> index,
            final NavigableSet<DatabaseKey> sortedKeys
    ) throws IOException {
        long totalBytes = 0L;
        for (final PreparedWrite write : writes) {
            totalBytes += write.frameBytes();
        }
        final ByteBuffer batch = ByteBuffer.allocate((int) totalBytes);
        for (final PreparedWrite write : writes) {
            appendFrame(batch, OP_UPSERT, write.keyType(), write.bucket(), write.keyBytes(), write.payloadBytes());
        }
        batch.flip();

        final long startOffset = writeOffset;
        try {
            writeFile.seek(startOffset);
            writeFile.write(batch.array(), 0, batch.limit());
            writeFile.getFD().sync();
        } catch (final IOException failure) {
            rollbackTail(failure);
            throw failure;
        }
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
            } else {
                sortedKeys.add(write.indexKey());
            }
            livePayloadBytes += payloadLen;
            cursor += write.frameBytes();
        }
        writeOffset = cursor;
    }

    void appendDelete(
            final DatabaseKey indexKey,
            final RecordLocation existing,
            final Map<DatabaseKey, RecordLocation> index,
            final NavigableSet<DatabaseKey> sortedKeys
    ) throws IOException {
        final byte keyType = indexKey.wireKeyType();
        final byte[] keyBytes = indexKey.wireKeyBytes();
        final ByteBuffer tombstone = buildFrame(
                OP_DELETE, keyType, (short) indexKey.bucket(), keyBytes, new byte[0]
        );
        final long frameOffset = writeOffset;
        try {
            writeFile.seek(frameOffset);
            writeFile.write(tombstone.array(), 0, tombstone.limit());
            writeFile.getFD().sync();
        } catch (final IOException failure) {
            rollbackTail(failure);
            throw failure;
        }
        writeOffset = frameOffset + HEADER_BYTES + keyBytes.length;
        index.remove(indexKey);
        sortedKeys.remove(indexKey);
        livePayloadBytes -= existing.payloadLength();
        deadBytes += (long) HEADER_BYTES + existing.keyLength() + existing.payloadLength()
                + HEADER_BYTES + keyBytes.length;
    }

    void tryCompactAfterCommit(final Map<DatabaseKey, RecordLocation> index) {
        if (!shouldCompact()) {
            return;
        }
        try {
            compact(index);
        } catch (final IOException failure) {
            // The triggering write is already committed in journal.wal and memory. Back off compaction
            // if the original handle recovered, otherwise fail loudly so callers know the store is broken.
            if (writeFile != null) {
                compactionBackoffDeadBytes = deadBytes + Math.max(COMPACT_MIN_DEAD_BYTES, deadBytes / 2);
                return;
            }
            throw new UncheckedIOException("Database compaction lost journal handle in " + directory + ".", failure);
        }
    }

    byte[] readPayloadBytesUnderWriteLock(final RecordLocation location) throws IOException {
        final byte[] bytes = new byte[location.payloadLength()];
        if (!readFully(writeFile, bytes, location.payloadOffset())) {
            throw new IOException("Truncated journal payload at offset " + location.payloadOffset()
                    + " (" + location.payloadLength() + " bytes).");
        }
        return bytes;
    }

    byte[] readPayloadBytes(final RecordLocation location) throws IOException, InterruptedException {
        final byte[] bytes = new byte[location.payloadLength()];
        for (int attempt = 0; attempt < 3; attempt++) {
            final FileChannel current = ensureReadChannel();
            try {
                if (!readFully(current, ByteBuffer.wrap(bytes), location.payloadOffset())) {
                    throw new IOException("Truncated journal payload at offset " + location.payloadOffset()
                            + " (" + location.payloadLength() + " bytes).");
                }
                return bytes;
            } catch (final ClosedByInterruptException interrupted) {
                Thread.interrupted();
                reopenReadChannelAfterInterrupt();
                throw new InterruptedException("Database read interrupted.");
            } catch (final ClosedChannelException collateralClose) {
                if (closed || writeFile == null) {
                    throw new IllegalStateException("Database collection store is already closed: " + directory);
                }
                reopenReadChannelAfterInterrupt();
            }
        }
        throw new IOException("Unable to read journal payload after channel recovery at offset "
                + location.payloadOffset() + ".");
    }

    static RailixValue.ObjectValue parseStoredObject(final byte[] payloadBytes) {
        final String text = decodeUtf8(payloadBytes, 0, payloadBytes.length);
        if (text != null && RailixJson.parse(text) instanceof RailixJson.Parsed(RailixValue.ObjectValue object)) {
            return object;
        }
        throw new IllegalStateException(
                "DATABASE_JOURNAL_CORRUPTED: Corrupted stored JSON document in database journal."
        );
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        IOException firstFailure = null;
        try {
            closeJournalHandles(true);
        } catch (final IOException failure) {
            firstFailure = failure;
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
        if (firstFailure != null) {
            throw new UncheckedIOException("Failed to close database collection " + directory + ".", firstFailure);
        }
    }

    void closeQuietly() {
        try {
            close();
        } catch (final RuntimeException ignored) {
            // Preserve the primary startup failure.
        }
    }

    private boolean shouldCompact() {
        return deadBytes >= COMPACT_MIN_DEAD_BYTES
                && deadBytes > livePayloadBytes
                && deadBytes >= compactionBackoffDeadBytes;
    }

    private void compact(final Map<DatabaseKey, RecordLocation> index) throws IOException {
        final long pauseStart = System.nanoTime();
        final List<Map.Entry<DatabaseKey, RecordLocation>> ordered = new ArrayList<>(index.entrySet());
        ordered.sort(Comparator.comparingLong(entry -> entry.getValue().frameOffset()));

        final Map<DatabaseKey, RecordLocation> compactedIndex = new HashMap<>(Math.max(16, ordered.size() * 2));
        long compactedOffset = 0L;
        long compactedLiveBytes = 0L;
        try {
            Files.deleteIfExists(compactPath);
            try (RandomAccessFile target = new RandomAccessFile(compactPath.toFile(), "rw")) {
                target.setLength(0L);
                for (final Map.Entry<DatabaseKey, RecordLocation> entry : ordered) {
                    final DatabaseKey indexKey = entry.getKey();
                    final byte[] keyBytes = indexKey.wireKeyBytes();
                    final byte[] payloadBytes = readPayloadBytesUnderWriteLock(entry.getValue());
                    final ByteBuffer frame = buildFrame(
                            OP_UPSERT,
                            indexKey.wireKeyType(),
                            (short) indexKey.bucket(),
                            keyBytes,
                            payloadBytes
                    );
                    target.write(frame.array(), 0, frame.limit());
                    final long payloadOffset = compactedOffset + HEADER_BYTES + keyBytes.length;
                    compactedIndex.put(
                            indexKey,
                            new RecordLocation(payloadOffset, keyBytes.length, payloadBytes.length)
                    );
                    compactedOffset += (long) HEADER_BYTES + keyBytes.length + payloadBytes.length;
                    compactedLiveBytes += payloadBytes.length;
                }
                target.getFD().sync();
            }
        } catch (final IOException writeFailure) {
            deleteQuietly(compactPath, writeFailure);
            throw writeFailure;
        }

        closeJournalHandles(false);
        boolean moved = false;
        try {
            moveJournal(compactPath, journalPath);
            moved = true;
            syncDirectory();
            openJournalHandles();
        } catch (final IOException swapFailure) {
            if (!moved) {
                deleteQuietly(compactPath, swapFailure);
                reopenOriginalJournal(swapFailure);
            } else {
                closeJournalHandlesQuietly(swapFailure);
            }
            throw swapFailure;
        }
        index.clear();
        index.putAll(compactedIndex);
        writeOffset = compactedOffset;
        livePayloadBytes = compactedLiveBytes;
        deadBytes = 0L;
        compactionBackoffDeadBytes = 0L;
        lastCompactionPauseNanos = System.nanoTime() - pauseStart;
    }

    private void openJournalHandles() throws IOException {
        writeFile = new RandomAccessFile(journalPath.toFile(), "rw");
        try {
            readChannel = FileChannel.open(journalPath, StandardOpenOption.READ);
        } catch (final IOException failure) {
            try {
                writeFile.close();
            } catch (final IOException suppressed) {
                failure.addSuppressed(suppressed);
            } finally {
                writeFile = null;
                readChannel = null;
            }
            throw failure;
        }
    }

    private void closeJournalHandles(final boolean syncBeforeClose) throws IOException {
        IOException firstFailure = null;
        synchronized (readChannelLock) {
            if (readChannel != null) {
                try {
                    readChannel.close();
                } catch (final IOException failure) {
                    firstFailure = failure;
                } finally {
                    readChannel = null;
                }
            }
        }
        if (writeFile != null) {
            try {
                if (syncBeforeClose && initialized) {
                    writeFile.getFD().sync();
                }
            } catch (final IOException failure) {
                firstFailure = failure;
            } finally {
                try {
                    writeFile.close();
                } catch (final IOException closeFailure) {
                    if (firstFailure == null) {
                        firstFailure = closeFailure;
                    } else {
                        firstFailure.addSuppressed(closeFailure);
                    }
                } finally {
                    writeFile = null;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private void closeJournalHandlesQuietly(final IOException primaryFailure) {
        try {
            closeJournalHandles(false);
        } catch (final IOException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    private FileChannel ensureReadChannel() throws IOException {
        final FileChannel current = readChannel;
        if (current != null && current.isOpen()) {
            return current;
        }
        return reopenReadChannelAfterInterrupt();
    }

    private FileChannel reopenReadChannelAfterInterrupt() throws IOException {
        synchronized (readChannelLock) {
            if (closed || writeFile == null) {
                throw new IllegalStateException("Database collection store is already closed: " + directory);
            }
            final FileChannel current = readChannel;
            if (current != null && current.isOpen()) {
                return current;
            }
            final FileChannel reopened = FileChannel.open(journalPath, StandardOpenOption.READ);
            readChannel = reopened;
            return reopened;
        }
    }

    private void reopenOriginalJournal(final IOException primaryFailure) {
        if (writeFile != null && readChannel != null && readChannel.isOpen()) {
            return;
        }
        try {
            openJournalHandles();
        } catch (final IOException reopenFailure) {
            closeJournalHandlesQuietly(primaryFailure);
            primaryFailure.addSuppressed(reopenFailure);
        }
    }

    private static void deleteQuietly(final Path path, final IOException primaryFailure) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
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

    private void rollbackTail(final IOException primaryFailure) {
        if (writeFile != null) {
            try {
                if (writeFile.length() > writeOffset) {
                    writeFile.setLength(writeOffset);
                }
            } catch (final IOException suppressed) {
                primaryFailure.addSuppressed(suppressed);
            }
        }
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
            if (len >= 7 && (partial[6] & 0xff) >= (BUCKET_COUNT >>> 8)) {
                return false;
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

    private static boolean readFully(
            final RandomAccessFile source,
            final byte[] target,
            final long startOffset
    ) throws IOException {
        source.seek(startOffset);
        int offset = 0;
        while (offset < target.length) {
            final int read = source.read(target, offset, target.length - offset);
            if (read < 0) {
                return false;
            }
            offset += read;
        }
        return true;
    }

    private static boolean readFully(
            final FileChannel source,
            final ByteBuffer target,
            final long startOffset
    ) throws IOException {
        long position = startOffset;
        while (target.hasRemaining()) {
            final int read = source.read(target, position);
            if (read < 0) {
                return false;
            }
            position += read;
        }
        return true;
    }

    private static void writeFully(
            final FileChannel target,
            final ByteBuffer source,
            final long startOffset
    ) throws IOException {
        long position = startOffset;
        while (source.hasRemaining()) {
            position += target.write(source, position);
        }
    }

    private static String decodeUtf8(final byte[] bytes, final int offset, final int length) {
        try {
            final String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString();
            return DatabaseKey.invalidSurrogate(decoded) ? null : decoded;
        } catch (final CharacterCodingException invalidUtf8) {
            return null;
        }
    }

    private static boolean validJsonObjectDelimiters(final byte[] bytes, final int offset, final int length) {
        return length >= 2 && bytes[offset] == '{' && bytes[offset + length - 1] == '}';
    }
}
