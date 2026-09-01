package dev.nanonative.railix.development;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** OS-backed ownership of one development artifact directory. */
public final class ArtifactLease implements AutoCloseable {
    private static final String PREFIX = ".lease-";
    private static final String SUFFIX = ".lock";
    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private ArtifactLease(final Path file, final FileChannel channel, final FileLock lock) {
        this.file = file;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Acquires one unique lease in {@code directory}.
     *
     * @param directory owner directory retained while this lease remains open
     * @return the acquired lease
     * @throws IOException when the directory or lock cannot be created
     */
    public static ArtifactLease acquire(final Path directory) throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("Artifact lease directory cannot be Java null.");
        }
        final Path owner = directory.toAbsolutePath().normalize();
        Files.createDirectories(owner);
        final Path file = owner.resolve(PREFIX + UUID.randomUUID() + SUFFIX);
        FileChannel channel = null;
        try {
            channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return new ArtifactLease(file, channel, channel.lock());
        } catch (final IOException | RuntimeException | Error failure) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (final IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
            try {
                Files.deleteIfExists(file);
            } catch (final IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * Reports whether {@code directory} contains at least one lease locked by a live owner.
     * Unlocked files are stale and never confer ownership.
     *
     * @param directory directory containing lease files
     * @return {@code true} when at least one live lease exists
     * @throws IOException when an existing lease cannot be inspected safely
     */
    public static boolean active(final Path directory) throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("Artifact lease directory cannot be Java null.");
        }
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var files = Files.list(directory)) {
            for (final Path file : files.filter(ArtifactLease::leaseFile).toList()) {
                try {
                    if (locked(file)) {
                        return true;
                    }
                    Files.deleteIfExists(file);
                } catch (final NoSuchFileException removedConcurrently) {
                    // A released lease may disappear between listing and inspection.
                }
            }
        } catch (final NoSuchFileException removedConcurrently) {
            return false;
        }
        return false;
    }

    private static boolean leaseFile(final Path file) {
        final String name = file.getFileName().toString();
        return Files.isRegularFile(file) && name.startsWith(PREFIX) && name.endsWith(SUFFIX);
    }

    private static boolean locked(final Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            try (FileLock probe = channel.tryLock()) {
                return probe == null;
            } catch (final OverlappingFileLockException liveOwnerInThisJvm) {
                return true;
            }
        }
    }

    /**
     * Releases this lease and removes its lock file.
     * Repeated calls have no effect.
     *
     * @throws IOException when release or cleanup fails
     */
    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException failure = null;
        try {
            lock.release();
        } catch (final IOException exception) {
            failure = exception;
        }
        try {
            channel.close();
        } catch (final IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        try {
            Files.deleteIfExists(file);
        } catch (final IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
