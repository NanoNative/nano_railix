package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.CompiledFlow;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.runtime.RunResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FileStepsFlowE2eTest {
    @Test
    void defaultJsonReadsAndNormalizesAnObject(@TempDir final Path directory) throws Exception {
        final Path file = write(directory.resolve("value.json"), "{\"b\":2,\"a\":1}");

        assertThat(run(file.toString(), "")).isEqualTo(succeeded(
                RailixValue.object(Map.of(
                        "a", RailixValue.number(1),
                        "b", RailixValue.number(2)
                )),
                "read"
        ));
    }

    @Test
    void configuredYamlReadsAndNormalizesAnObject(@TempDir final Path directory) throws Exception {
        final Path file = write(directory.resolve("value.yaml"), "text: \"Railix\"\nactive: true\n");

        assertThat(run(file.toString(), "\"format\":\"yaml\"")).isEqualTo(succeeded(
                RailixValue.object(Map.of(
                        "active", RailixValue.bool(true),
                        "text", RailixValue.string("Railix")
                )),
                "read"
        ));
    }

    @Test
    void configuredXmlReadsAndNormalizesAnObject(@TempDir final Path directory) throws Exception {
        final Path file = write(
                directory.resolve("value.xml"),
                "<object><field name=\"text\"><string>Railix</string></field></object>"
        );

        assertThat(run(file.toString(), "\"format\":\"xml\"")).isEqualTo(succeeded(
                RailixValue.object(Map.of("text", RailixValue.string("Railix"))),
                "read"
        ));
    }

    @Test
    void missingFileRoutesMissingWithNullOutput(@TempDir final Path directory) {
        assertThat(run(directory.resolve("missing.json").toString(), ""))
                .isEqualTo(succeeded(RailixValue.nullValue(), "missing"));
    }

    @Test
    void directoryRoutesRejectedWithNullOutput(@TempDir final Path directory) {
        assertThat(run(directory.toString(), ""))
                .isEqualTo(succeeded(RailixValue.nullValue(), "rejected"));
    }

    @Test
    void fifoRoutesRejectedWithoutOpeningABlockingStream(@TempDir final Path directory) throws Exception {
        final Path fifo = directory.resolve("event.json");
        fifo(fifo);

        final AtomicReference<RunResult> result = new AtomicReference<>();
        final Thread execution = Thread.ofVirtual().start(() -> result.set(run(fifo.toString(), "")));
        final boolean completed = execution.join(Duration.ofSeconds(1));
        if (!completed) {
            execution.interrupt();
            if (!execution.join(Duration.ofMillis(100))) {
                try (var writer = Files.newOutputStream(fifo)) {
                    writer.write("{}".getBytes(StandardCharsets.UTF_8));
                }
                execution.join();
            }
        }

        assertThat(completed).isTrue();
        assertThat(execution.isAlive()).isFalse();
        assertThat(result.get()).isEqualTo(succeeded(RailixValue.nullValue(), "rejected"));
    }

    @Test
    void finalSymbolicLinkRoutesRejectedWithoutReadingItsTarget(@TempDir final Path directory) throws Exception {
        final Path target = write(directory.resolve("target.json"), "\"target\"");
        final Path link = Files.createSymbolicLink(directory.resolve("link.json"), target.getFileName());

        assertThat(run(link.toString(), ""))
                .isEqualTo(succeeded(RailixValue.nullValue(), "rejected"));
    }

    @Test
    void missingParentRoutesMissing(@TempDir final Path directory) {
        assertThat(run(directory.resolve("missing/value.json").toString(), ""))
                .isEqualTo(succeeded(RailixValue.nullValue(), "missing"));
    }

    @Test
    void emptyPathRoutesRejected() {
        assertThat(run("", ""))
                .isEqualTo(succeeded(RailixValue.nullValue(), "rejected"));
    }

    @Test
    void pathOver4096Utf8BytesRoutesRejected() {
        assertThat(run("\u00e9".repeat(2_049), ""))
                .isEqualTo(succeeded(RailixValue.nullValue(), "rejected"));
    }

    @Test
    void filesystemRootRoutesRejected() {
        assertThat(run(root(), ""))
                .isEqualTo(succeeded(RailixValue.nullValue(), "rejected"));
    }

    @Test
    void unreadableRegularFileRoutesRejected(@TempDir final Path directory) throws Exception {
        final Path file = write(directory.resolve("value.json"), "{}");
        final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
        try {
            Files.setPosixFilePermissions(file, Set.of());

            assertThat(run(file.toString(), ""))
                    .isEqualTo(succeeded(RailixValue.nullValue(), "rejected"));
        } finally {
            Files.setPosixFilePermissions(file, permissions);
        }
    }

    @Test
    void invalidPathRoutesRejectedWithNullOutput() {
        assertThat(run("\0", ""))
                .isEqualTo(succeeded(RailixValue.nullValue(), "rejected"));
    }

    @Test
    void emptyFileRoutesRejected(@TempDir final Path directory) throws Exception {
        final Path file = Files.createFile(directory.resolve("empty.json"));

        assertThat(run(file.toString(), ""))
                .isEqualTo(succeeded(RailixValue.nullValue(), "rejected"));
    }

    @Test
    void malformedSelectedFormatRoutesRejected(@TempDir final Path directory) throws Exception {
        final Path file = write(directory.resolve("broken.json"), "{");

        assertThat(run(file.toString(), ""))
                .isEqualTo(succeeded(RailixValue.nullValue(), "rejected"));
    }

    @Test
    void invalidUtf8RoutesRejected(@TempDir final Path directory) throws Exception {
        final Path file = directory.resolve("invalid.json");
        Files.write(file, new byte[]{'{', '"', (byte) 0xc3, '(', '"', ':', '1', '}'});

        assertThat(run(file.toString(), ""))
                .isEqualTo(succeeded(RailixValue.nullValue(), "rejected"));
    }

    @Test
    void utf8BomRoutesRejected(@TempDir final Path directory) throws Exception {
        final Path file = directory.resolve("bom.json");
        Files.write(file, new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'});

        assertThat(run(file.toString(), ""))
                .isEqualTo(succeeded(RailixValue.nullValue(), "rejected"));
    }

    @Test
    void overDepthDocumentRoutesRejected(@TempDir final Path directory) throws Exception {
        final Path file = write(
                directory.resolve("deep.json"),
                "[".repeat(65) + "0" + "]".repeat(65)
        );

        assertThat(run(file.toString(), ""))
                .isEqualTo(succeeded(RailixValue.nullValue(), "rejected"));
    }

    @Test
    void overLimitNumberRoutesRejected(@TempDir final Path directory) throws Exception {
        final Path file = write(
                directory.resolve("number.json"),
                "1" + "0".repeat(RailixData.MAX_CANONICAL_NUMBER_CHARACTERS)
        );

        assertThat(run(file.toString(), ""))
                .isEqualTo(succeeded(RailixValue.nullValue(), "rejected"));
    }

    @Test
    void exactMaximumFileIsAccepted(@TempDir final Path directory) throws Exception {
        final byte[] prefix = "{\"accepted\":true}".getBytes(StandardCharsets.UTF_8);
        final byte[] source = new byte[RailixData.DEFAULT_MAX_SOURCE_BYTES];
        System.arraycopy(prefix, 0, source, 0, prefix.length);
        Arrays.fill(source, prefix.length, source.length, (byte) ' ');
        final Path file = directory.resolve("maximum.json");
        Files.write(file, source);

        assertThat(run(file.toString(), "")).isEqualTo(succeeded(
                RailixValue.object(Map.of("accepted", RailixValue.bool(true))),
                "read"
        ));
    }

    @Test
    void oversizedFileRoutesRejectedAfterOneProbeByte(@TempDir final Path directory) throws Exception {
        final byte[] source = new byte[RailixData.DEFAULT_MAX_SOURCE_BYTES + 1];
        Arrays.fill(source, (byte) ' ');
        final Path file = directory.resolve("oversized.json");
        Files.write(file, source);

        assertThat(run(file.toString(), ""))
                .isEqualTo(succeeded(RailixValue.nullValue(), "rejected"));
    }

    @Test
    void unsupportedFormatIsRejectedByTheCompiler() {
        assertDiagnostic(
                "\"format\":\"toml\"",
                "FLOW_STEP_CONFIG_FORMAT_MISMATCH",
                "Step configuration format requires format data-format."
        );
    }

    @Test
    void formatMustBeAString() {
        assertDiagnostic(
                "\"format\":7",
                "FLOW_STEP_CONFIG_TYPE_MISMATCH",
                "Step configuration format requires STRING but received NUMBER."
        );
    }

    @Test
    void repeatedReadsObserveCurrentFileWithoutRetainingPriorValue(@TempDir final Path directory) throws Exception {
        final Path file = write(directory.resolve("changing.json"), "\"first\"");
        final RunResult first = run(file.toString(), "");
        write(file, "\"second\"");

        assertThat(List.of(first, run(file.toString(), ""))).containsExactly(
                succeeded(RailixValue.string("first"), "read"),
                succeeded(RailixValue.string("second"), "read")
        );
    }

    @Test
    void concurrentReadsRemainIsolated(@TempDir final Path directory) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<RunResult>> reads = new java.util.ArrayList<>();
            for (int index = 0; index < 32; index++) {
                final Path file = write(directory.resolve(index + ".json"), "\"value-" + index + "\"");
                reads.add(executor.submit(() -> run(file.toString(), "")));
            }
            for (int index = 0; index < reads.size(); index++) {
                assertThat(reads.get(index).get()).isEqualTo(
                        succeeded(RailixValue.string("value-" + index), "read")
                );
            }
        }
    }

    @Test
    void writeCreatesCanonicalJsonWithoutANewline(@TempDir final Path directory) throws Exception {
        final Path file = directory.resolve("value.json");
        final Map<String, RailixValue> fields = new LinkedHashMap<>();
        fields.put("b", RailixValue.number(2));
        fields.put("a", RailixValue.number(1));

        assertThat(runWrite(file.toString(), RailixValue.object(fields), ""))
                .isEqualTo(succeeded("writer", "written"));
        assertThat(Files.readString(file)).isEqualTo("{\"a\":1,\"b\":2}");
        assertNoTemporaryFiles(directory);
    }

    @Test
    void writePersistsExplicitJsonNull(@TempDir final Path directory) throws Exception {
        final Path file = directory.resolve("null.json");

        assertThat(runWrite(file.toString(), RailixValue.nullValue(), ""))
                .isEqualTo(succeeded("writer", "written"));
        assertThat(Files.readString(file)).isEqualTo("null");
    }

    @Test
    void writeDefaultsToConflictWithoutChangingAnExistingFile(@TempDir final Path directory) throws Exception {
        final Path file = write(directory.resolve("value.json"), "\"original\"");

        assertThat(runWrite(file.toString(), RailixValue.string("replacement"), ""))
                .isEqualTo(succeeded("writer", "conflict"));
        assertThat(Files.readString(file)).isEqualTo("\"original\"");
        assertNoTemporaryFiles(directory);
    }

    @Test
    void writeOverwriteAtomicallyReplacesARegularFile(@TempDir final Path directory) throws Exception {
        final Path file = write(directory.resolve("value.json"), "\"original\"");

        assertThat(runWrite(file.toString(), RailixValue.string("replacement"), "\"overwrite\":true"))
                .isEqualTo(succeeded("writer", "written"));
        assertThat(Files.readString(file)).isEqualTo("\"replacement\"");
        assertNoTemporaryFiles(directory);
    }

    @Test
    void writeOverwriteAlsoCreatesAMissingFile(@TempDir final Path directory) throws Exception {
        final Path file = directory.resolve("value.json");

        assertThat(runWrite(file.toString(), RailixValue.bool(true), "\"overwrite\":true"))
                .isEqualTo(succeeded("writer", "written"));
        assertThat(Files.readString(file)).isEqualTo("true");
    }

    @Test
    void writeAcceptsExactlyOneMebibyteOfCanonicalJson(@TempDir final Path directory) throws Exception {
        final Path file = directory.resolve("maximum.json");
        final RailixValue value = RailixValue.string(
                "x".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - 2)
        );

        assertThat(runWrite(file.toString(), value, ""))
                .isEqualTo(succeeded("writer", "written"));
        assertThat(Files.size(file)).isEqualTo(RailixData.DEFAULT_MAX_SOURCE_BYTES);
    }

    @Test
    void writeRejectsCanonicalJsonOverOneMebibyteWithoutCreatingAFile(
            @TempDir final Path directory
    ) throws Exception {
        final Path file = directory.resolve("oversized.json");
        final RailixValue value = RailixValue.string(
                "x".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - 1)
        );

        assertThat(runWrite(file.toString(), value, ""))
                .isEqualTo(succeeded("writer", "rejected"));
        assertThat(file).doesNotExist();
        assertNoTemporaryFiles(directory);
    }

    @Test
    void writeRejectsAnOverDepthCanonicalValue(@TempDir final Path directory) {
        RailixValue value = RailixValue.nullValue();
        for (int depth = 0; depth < 65; depth++) {
            value = RailixValue.array(List.of(value));
        }

        assertThat(runWrite(directory.resolve("deep.json").toString(), value, ""))
                .isEqualTo(succeeded("writer", "rejected"));
    }

    @Test
    void writeRejectsAnUnpairedUnicodeSurrogate(@TempDir final Path directory) {
        assertThat(runWrite(
                directory.resolve("invalid.json").toString(),
                RailixValue.string("\ud800"),
                ""
        )).isEqualTo(succeeded("writer", "rejected"));
    }

    @Test
    void writeRejectsAnOverlongCanonicalNumber(@TempDir final Path directory) {
        assertThat(runWrite(
                directory.resolve("number.json").toString(),
                RailixValue.number(new BigDecimal(
                        "1" + "0".repeat(RailixData.MAX_CANONICAL_NUMBER_CHARACTERS)
                )),
                ""
        )).isEqualTo(succeeded("writer", "rejected"));
    }

    @Test
    void writeRejectsADirectoryWithoutChangingIt(@TempDir final Path directory) {
        assertThat(runWrite(directory.toString(), RailixValue.string("value"), "\"overwrite\":true"))
                .isEqualTo(succeeded("writer", "rejected"));
        assertThat(directory).isDirectory();
    }

    @Test
    void writeRejectsAFinalSymbolicLinkWithoutChangingItsTarget(
            @TempDir final Path directory
    ) throws Exception {
        final Path target = write(directory.resolve("target.json"), "\"target\"");
        final Path link = Files.createSymbolicLink(directory.resolve("link.json"), target.getFileName());

        assertThat(runWrite(link.toString(), RailixValue.string("replacement"), "\"overwrite\":true"))
                .isEqualTo(succeeded("writer", "rejected"));
        assertThat(Files.readString(target)).isEqualTo("\"target\"");
        assertThat(link).isSymbolicLink();
    }

    @Test
    void writeRejectsAFifoWithoutOpeningABlockingStream(@TempDir final Path directory) throws Exception {
        final Path pipe = fifo(directory.resolve("value.json"));
        final AtomicReference<RunResult> result = new AtomicReference<>();
        final Thread execution = Thread.ofVirtual().start(() -> result.set(runWrite(
                pipe.toString(),
                RailixValue.string("value"),
                "\"overwrite\":true"
        )));
        final boolean completed = execution.join(Duration.ofSeconds(1));
        if (!completed) {
            execution.interrupt();
            if (!execution.join(Duration.ofMillis(100))) {
                try (var reader = Files.newInputStream(pipe)) {
                    reader.read();
                }
                execution.join();
            }
        }

        assertThat(completed).isTrue();
        assertThat(execution.isAlive()).isFalse();
        assertThat(result.get()).isEqualTo(succeeded("writer", "rejected"));
    }

    @Test
    void writeRejectsAMissingParentWithoutCreatingIt(@TempDir final Path directory) {
        final Path parent = directory.resolve("missing");

        assertThat(runWrite(
                parent.resolve("value.json").toString(),
                RailixValue.string("value"),
                ""
        )).isEqualTo(succeeded("writer", "rejected"));
        assertThat(parent).doesNotExist();
    }

    @Test
    void writeRejectsAnInvalidPath() {
        assertThat(runWrite("\0", RailixValue.string("value"), ""))
                .isEqualTo(succeeded("writer", "rejected"));
    }

    @Test
    void writeRejectsAnEmptyPath() {
        assertThat(runWrite("", RailixValue.string("value"), ""))
                .isEqualTo(succeeded("writer", "rejected"));
    }

    @Test
    void writeRejectsAPathOver4096Utf8Bytes() {
        assertThat(runWrite("\u00e9".repeat(2_049), RailixValue.string("value"), ""))
                .isEqualTo(succeeded("writer", "rejected"));
    }

    @Test
    void writeRejectsTheFilesystemRoot() {
        assertThat(runWrite(root(), RailixValue.string("value"), ""))
                .isEqualTo(succeeded("writer", "rejected"));
    }

    @Test
    void writeRejectsAPathInsideAnUnreadableDirectory(@TempDir final Path directory)
            throws Exception {
        final Path parent = Files.createDirectory(directory.resolve("locked"));
        final Path file = write(parent.resolve("value.json"), "\"original\"");
        final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(parent);
        try {
            Files.setPosixFilePermissions(parent, Set.of());

            assertThat(runWrite(
                    file.toString(),
                    RailixValue.string("replacement"),
                    "\"overwrite\":true"
            )).isEqualTo(succeeded("writer", "rejected"));
        } finally {
            Files.setPosixFilePermissions(parent, permissions);
        }
        assertThat(Files.readString(file)).isEqualTo("\"original\"");
    }

    @Test
    void writeThroughAParentSymbolicLinkUsesTheResolvedDirectory(
            @TempDir final Path directory
    ) throws Exception {
        final Path actual = Files.createDirectory(directory.resolve("actual"));
        final Path alias = Files.createSymbolicLink(directory.resolve("alias"), actual.getFileName());

        assertThat(runWrite(alias.resolve("value.json").toString(), RailixValue.number(7), ""))
                .isEqualTo(succeeded("writer", "written"));
        assertThat(Files.readString(actual.resolve("value.json"))).isEqualTo("7");
    }

    @Test
    void repeatedOverwriteLeavesOnlyTheLatestCompleteValue(@TempDir final Path directory) throws Exception {
        final Path file = directory.resolve("value.json");

        assertThat(List.of(
                runWrite(file.toString(), RailixValue.string("first"), "\"overwrite\":true"),
                runWrite(file.toString(), RailixValue.string("second"), "\"overwrite\":true")
        )).containsExactly(
                succeeded("writer", "written"),
                succeeded("writer", "written")
        );
        assertThat(Files.readString(file)).isEqualTo("\"second\"");
        assertNoTemporaryFiles(directory);
    }

    @Test
    void concurrentNoOverwriteWritesProduceOneWinnerAndExplicitConflicts(
            @TempDir final Path directory
    ) throws Exception {
        final Path file = directory.resolve("value.json");
        final CountDownLatch start = new CountDownLatch(1);
        final List<RunResult> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<RunResult>> writes = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                final String value = "value-" + index;
                writes.add(executor.submit(() -> {
                    start.await();
                    return runWrite(file.toString(), RailixValue.string(value), "");
                }));
            }
            start.countDown();
            for (final Future<RunResult> write : writes) {
                results.add(write.get());
            }
        }

        assertThat(results.stream().filter(succeeded("writer", "written")::equals).count())
                .isEqualTo(1);
        assertThat(results.stream().filter(succeeded("writer", "conflict")::equals).count())
                .isEqualTo(31);
        assertThat(Files.readString(file)).matches("\"value-[0-9]+\"");
        assertNoTemporaryFiles(directory);
    }

    @Test
    void concurrentOverwritesLeaveOneCompleteCanonicalValue(@TempDir final Path directory)
            throws Exception {
        final Path file = write(directory.resolve("value.json"), "\"initial\"");
        final CountDownLatch start = new CountDownLatch(1);
        final List<RunResult> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<RunResult>> writes = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                final String value = "value-" + index;
                writes.add(executor.submit(() -> {
                    start.await();
                    return runWrite(
                            file.toString(),
                            RailixValue.string(value),
                            "\"overwrite\":true"
                    );
                }));
            }
            start.countDown();
            for (final Future<RunResult> write : writes) {
                results.add(write.get());
            }
        }

        assertThat(results).containsOnly(succeeded("writer", "written"));
        assertThat(Files.readString(file)).matches("\"value-[0-9]+\"");
        assertNoTemporaryFiles(directory);
    }

    @Test
    void concurrentWritesToIndependentPathsRemainIsolated(@TempDir final Path directory)
            throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<RunResult>> writes = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                final Path file = directory.resolve(index + ".json");
                final String value = "value-" + index;
                writes.add(executor.submit(() -> runWrite(
                        file.toString(),
                        RailixValue.string(value),
                        ""
                )));
            }
            for (int index = 0; index < writes.size(); index++) {
                assertThat(writes.get(index).get()).isEqualTo(succeeded("writer", "written"));
                assertThat(Files.readString(directory.resolve(index + ".json")))
                        .isEqualTo("\"value-" + index + "\"");
            }
        }
        assertNoTemporaryFiles(directory);
    }

    @Test
    void cancelledContendedWritesReleaseTheLockAndTemporaryFiles(
            @TempDir final Path directory
    ) throws Exception {
        final Path file = directory.resolve("value.json");
        final CompiledFlow flow = compiled(writeFlow("\"overwrite\":true"));
        final RailixValue.ObjectValue event = RailixValue.object(Map.of(
                "path",
                RailixValue.string(file.toString()),
                "value",
                RailixValue.string("x".repeat(RailixData.DEFAULT_MAX_SOURCE_BYTES - 2))
        ));
        final CountDownLatch ready = new CountDownLatch(64);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(64);
        final List<AtomicReference<RunResult>> results = new ArrayList<>();
        final List<Thread> executions = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            final AtomicReference<RunResult> result = new AtomicReference<>();
            results.add(result);
            executions.add(Thread.ofVirtual().unstarted(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (final InterruptedException exception) {
                    throw new AssertionError(exception);
                }
                entered.countDown();
                result.set(flow.run(event));
            }));
        }
        executions.forEach(Thread::start);
        boolean allJoined = true;
        try {
            ready.await();
            start.countDown();
            entered.await();
            final long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            List<String> observed = List.of();
            while (observed.isEmpty() && System.nanoTime() < deadline) {
                observed = temporaryFiles(directory);
                Thread.onSpinWait();
            }
            assertThat(observed).isNotEmpty();
        } finally {
            start.countDown();
            executions.forEach(Thread::interrupt);
            for (final Thread execution : executions) {
                allJoined &= execution.join(Duration.ofSeconds(5));
            }
        }
        assertThat(allJoined).isTrue();

        assertThat(results.stream().map(AtomicReference::get).toList())
                .contains(new RunResult.Cancelled(List.of()));
        assertNoTemporaryFiles(directory);
        assertThat(runWrite(file.toString(), RailixValue.string("after"), "\"overwrite\":true"))
                .isEqualTo(succeeded("writer", "written"));
        assertThat(Files.readString(file)).isEqualTo("\"after\"");
    }

    @Test
    void overwriteConfigurationMustBeBoolean() {
        assertDiagnostic(
                writeFlow("\"overwrite\":\"yes\""),
                "FLOW_STEP_CONFIG_TYPE_MISMATCH",
                "Step configuration overwrite requires BOOLEAN but received STRING.",
                "steps.writer.config.overwrite"
        );
    }

    @Test
    void unknownWriteConfigurationIsRejectedByTheCompiler() {
        assertDiagnostic(
                writeFlow("\"retry\":true"),
                "FLOW_STEP_CONFIG_UNKNOWN",
                "Unknown Step configuration: retry",
                "steps.writer.config.retry"
        );
    }

    @Test
    void deleteRemovesOneRegularFile(@TempDir final Path directory) throws Exception {
        final Path file = write(directory.resolve("value.json"), "{}");

        assertThat(runDelete(file.toString())).isEqualTo(succeeded("deleter", "deleted"));
        assertThat(file).doesNotExist();
    }

    @Test
    void deleteRoutesMissingForAnAbsentFile(@TempDir final Path directory) {
        assertThat(runDelete(directory.resolve("missing.json").toString()))
                .isEqualTo(succeeded("deleter", "missing"));
    }

    @Test
    void repeatedDeleteRoutesDeletedThenMissing(@TempDir final Path directory) throws Exception {
        final Path file = write(directory.resolve("value.json"), "{}");

        assertThat(List.of(runDelete(file.toString()), runDelete(file.toString()))).containsExactly(
                succeeded("deleter", "deleted"),
                succeeded("deleter", "missing")
        );
    }

    @Test
    void deleteRejectsADirectoryWithoutRemovingIt(@TempDir final Path directory) {
        assertThat(runDelete(directory.toString())).isEqualTo(succeeded("deleter", "rejected"));
        assertThat(directory).isDirectory();
    }

    @Test
    void deleteRejectsAFinalSymbolicLinkWithoutRemovingItOrItsTarget(
            @TempDir final Path directory
    ) throws Exception {
        final Path target = write(directory.resolve("target.json"), "{}");
        final Path link = Files.createSymbolicLink(directory.resolve("link.json"), target.getFileName());

        assertThat(runDelete(link.toString())).isEqualTo(succeeded("deleter", "rejected"));
        assertThat(link).isSymbolicLink();
        assertThat(target).exists();
    }

    @Test
    void deleteRejectsAFifoWithoutRemovingIt(@TempDir final Path directory) throws Exception {
        final Path pipe = fifo(directory.resolve("value.json"));

        assertThat(runDelete(pipe.toString())).isEqualTo(succeeded("deleter", "rejected"));
        assertThat(pipe).exists();
    }

    @Test
    void deleteRoutesMissingForAMissingParent(@TempDir final Path directory) {
        assertThat(runDelete(directory.resolve("missing/value.json").toString()))
                .isEqualTo(succeeded("deleter", "missing"));
    }

    @Test
    void deleteRejectsAnInvalidPath() {
        assertThat(runDelete("\0")).isEqualTo(succeeded("deleter", "rejected"));
    }

    @Test
    void deleteRejectsAnEmptyPath() {
        assertThat(runDelete("")).isEqualTo(succeeded("deleter", "rejected"));
    }

    @Test
    void deleteRejectsAPathOver4096Utf8Bytes() {
        assertThat(runDelete("\u00e9".repeat(2_049))).isEqualTo(succeeded("deleter", "rejected"));
    }

    @Test
    void deleteRejectsTheFilesystemRoot() {
        assertThat(runDelete(root())).isEqualTo(succeeded("deleter", "rejected"));
    }

    @Test
    void deleteRejectsWhenTheParentDirectoryCannotBeModified(
            @TempDir final Path directory
    ) throws Exception {
        final Path parent = Files.createDirectory(directory.resolve("locked"));
        final Path file = write(parent.resolve("value.json"), "{}");
        final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(parent);
        try {
            Files.setPosixFilePermissions(parent, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_EXECUTE
            ));

            assertThat(runDelete(file.toString())).isEqualTo(succeeded("deleter", "rejected"));
        } finally {
            Files.setPosixFilePermissions(parent, permissions);
        }
        assertThat(file).exists();
    }

    @Test
    void deleteThroughAParentSymbolicLinkRemovesTheResolvedFile(
            @TempDir final Path directory
    ) throws Exception {
        final Path actual = Files.createDirectory(directory.resolve("actual"));
        final Path file = write(actual.resolve("value.json"), "{}");
        final Path alias = Files.createSymbolicLink(directory.resolve("alias"), actual.getFileName());

        assertThat(runDelete(alias.resolve("value.json").toString()))
                .isEqualTo(succeeded("deleter", "deleted"));
        assertThat(file).doesNotExist();
    }

    @Test
    void concurrentDeletesProduceOneDeletionAndExplicitMisses(
            @TempDir final Path directory
    ) throws Exception {
        final Path file = write(directory.resolve("value.json"), "{}");
        final CountDownLatch start = new CountDownLatch(1);
        final List<RunResult> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Future<RunResult>> deletes = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                deletes.add(executor.submit(() -> {
                    start.await();
                    return runDelete(file.toString());
                }));
            }
            start.countDown();
            for (final Future<RunResult> delete : deletes) {
                results.add(delete.get());
            }
        }

        assertThat(results.stream().filter(succeeded("deleter", "deleted")::equals).count())
                .isEqualTo(1);
        assertThat(results.stream().filter(succeeded("deleter", "missing")::equals).count())
                .isEqualTo(31);
        assertThat(file).doesNotExist();
    }

    @Test
    void unknownDeleteConfigurationIsRejectedByTheCompiler() {
        assertDiagnostic(
                deleteFlow("\"recursive\":true"),
                "FLOW_STEP_CONFIG_UNKNOWN",
                "Unknown Step configuration: recursive",
                "steps.deleter.config.recursive"
        );
    }

    private static RunResult run(final String path, final String config) {
        return execute(flow(config), RailixValue.object(Map.of(
                "path",
                RailixValue.string(path)
        )));
    }

    private static RunResult runWrite(
            final String path,
            final RailixValue value,
            final String config
    ) {
        return execute(writeFlow(config), RailixValue.object(Map.of(
                "path",
                RailixValue.string(path),
                "value",
                value
        )));
    }

    private static RunResult runDelete(final String path) {
        return execute(deleteFlow(""), RailixValue.object(Map.of(
                "path",
                RailixValue.string(path)
        )));
    }

    private static RunResult execute(
            final String source,
            final RailixValue.ObjectValue event
    ) {
        return compiled(source).run(event);
    }

    private static CompiledFlow compiled(final String source) {
        final CompileResult compilation = FlowCompiler.compile(source, StandardLibrary.catalog());
        if (!(compilation instanceof CompileResult.Compiled compiled)) {
            throw new AssertionError("File flow did not compile: " + compilation);
        }
        return compiled.flow();
    }

    private static void assertDiagnostic(
            final String config,
            final String code,
            final String message
    ) {
        assertDiagnostic(flow(config), code, message, "steps.reader.config.format");
    }

    private static void assertDiagnostic(
            final String source,
            final String code,
            final String message,
            final String path
    ) {
        assertThat(FlowCompiler.compile(source, StandardLibrary.catalog()))
                .isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                        code,
                        message,
                        path
                ))));
    }

    private static RunResult.Succeeded succeeded(
            final RailixValue value,
            final String outcome
    ) {
        return new RunResult.Succeeded(
                RailixValue.object(Map.of("value", value)),
                List.of(new RunResult.StepExecution("reader", outcome))
        );
    }

    private static RunResult.Succeeded succeeded(final String step, final String outcome) {
        return new RunResult.Succeeded(
                RailixValue.object(Map.of()),
                List.of(new RunResult.StepExecution(step, outcome))
        );
    }

    private static String flow(final String config) {
        return """
                {
                  "id":"file-read-flow",
                  "triggers":[],
                  "entry":"reader",
                  "inputs":{"path":"string"},
                  "outputs":{"value":"any"},
                  "steps":[
                    {"id":"reader","use":"file.read","config":{%s},"on":{
                      "read":"end","missing":"end","rejected":"end"
                    }}
                  ],
                  "connections":[
                    {"from":"input.path","to":"reader.path"},
                    {"from":"reader.value","to":"output.value"}
                  ]
                }
                """.formatted(config);
    }

    private static String writeFlow(final String config) {
        return """
                {
                  "id":"file-write-flow",
                  "triggers":[],
                  "entry":"writer",
                  "inputs":{"path":"string","value":"any"},
                  "outputs":{},
                  "steps":[
                    {"id":"writer","use":"file.write","config":{%s},"on":{
                      "written":"end","conflict":"end","rejected":"end"
                    }}
                  ],
                  "connections":[
                    {"from":"input.path","to":"writer.path"},
                    {"from":"input.value","to":"writer.value"}
                  ]
                }
                """.formatted(config);
    }

    private static String deleteFlow(final String config) {
        return """
                {
                  "id":"file-delete-flow",
                  "triggers":[],
                  "entry":"deleter",
                  "inputs":{"path":"string"},
                  "outputs":{},
                  "steps":[
                    {"id":"deleter","use":"file.delete","config":{%s},"on":{
                      "deleted":"end","missing":"end","rejected":"end"
                    }}
                  ],
                  "connections":[
                    {"from":"input.path","to":"deleter.path"}
                  ]
                }
                """.formatted(config);
    }

    private static Path fifo(final Path path) throws Exception {
        final Process creation = new ProcessBuilder("mkfifo", path.toString()).start();
        assertThat(creation.waitFor()).isZero();
        return path;
    }

    private static String root() {
        return Path.of("").toAbsolutePath().getRoot().toString();
    }

    private static void assertNoTemporaryFiles(final Path directory) throws Exception {
        assertThat(temporaryFiles(directory)).isEmpty();
    }

    private static List<String> temporaryFiles(final Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.contains(".railix-"))
                    .sorted()
                    .toList();
        }
    }

    private static Path write(final Path path, final String source) throws Exception {
        return Files.writeString(path, source, StandardCharsets.UTF_8);
    }
}
