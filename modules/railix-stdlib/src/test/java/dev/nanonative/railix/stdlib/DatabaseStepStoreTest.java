package dev.nanonative.railix.stdlib;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.Diagnostic;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepContractJson;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepInput;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseStepStoreTest {
    @TempDir
    private Path tempDir;

    @Test
    void catalogDeclaresDatabaseStepContractAndGenericInputs() {
        final StepDefinition definition = PrimitiveStepsCreatorProjectE2eTest.catalog()
                .find("railix.database")
                .orElseThrow();

        assertThat(definition.version()).isEqualTo("1");
        assertThat(definition.kind()).isEqualTo(StepDefinition.Kind.STEP);
        assertThat(definition.displayName()).isEqualTo("Database");
        assertThat(definition.searchTerms())
                .containsExactly("store", "persist", "collection", "json", "crud", "upsert", "mongodb");
        assertThat(definition.primaryOutcome()).isEqualTo("ok");
        assertThat(definition.outcomes()).containsExactly("ok", "not_found", "invalid");
        assertThat(definition.receives()).isEmpty();
        assertThat(definition.returns()).isEmpty();
        assertThat(definition.executable()).isTrue();
        assertThat(StepContractJson.value(definition).values()).doesNotContainKey("jdk_modules");
        assertThat(definition.inputs()).extracting(StepDefinition.Field::name)
                .containsExactly(
                        "collection", "directory", "key_field", "unique_fields", "index_fields", "operation", "target"
                );
        assertThat(definition.inputs().get(5).input())
                .isInstanceOfSatisfying(StepDefinition.OptionsInput.class, operation -> {
                    assertThat(operation.defaultOption()).contains("upsert");
                    assertThat(operation.options()).extracting(StepDefinition.Option::name)
                            .containsExactly("upsert", "get", "find", "delete");
                    final StepDefinition.Option find = operation.options().get(2);
                    assertThat(find.inputs()).extracting(StepDefinition.Field::name)
                            .containsExactly("filter", "after", "fields", "defaults", "limit");
                });
    }

    @ParameterizedTest(name = "{0} = {1} fails configuration validation")
    @CsvSource({
            "collection, ''",
            "collection, .hidden",
            "collection, ../escape",
            "collection, 'bad name'",
            "collection, Orders",
            "collection, mixedCase",
            "collection, orders.",
            "collection, orders-",
            "collection, orders..v1",
            "collection, con",
            "collection, nul",
            "collection, aux.v1",
            "collection, com1",
            "collection, lpt9.bak",
            "directory, '   '",
            "directory, ../outside",
            "directory, data/../escape",
            "directory, /tmp/railix/../escape",
            "key_field, ''",
            "key_field, '   '"
    })
    void compilerRejectsInvalidDatabaseConfigurationSettings(final String field, final String badValue) {
        final String project = """
                {
                  "format": 1,
                  "id": "db-validation",
                  "nodes": [
                    {"id": "app", "use": "railix.app", "inputs": {}},
                    {
                      "id": "cli",
                      "use": "railix.trigger.cli",
                      "inputs": {},
                      "examples": [{"name": "ex", "payload": []}]
                    },
                    {
                      "id": "db",
                      "use": "railix.database",
                      "inputs": {%s}
                    }
                  ],
                  "links": [
                    {"from": "app.start", "to": "cli"},
                    {"from": "cli.next", "to": "db"},
                    {"from": "db.ok", "to": "end"},
                    {"from": "db.not_found", "to": "end"},
                    {"from": "db.invalid", "to": "end"}
                  ]
                }
                """.formatted(RailixJson.write(RailixValue.string(field)) + ": "
                + RailixJson.write(RailixValue.string(badValue)));

        final CompileResult result = ProjectCompiler.compileApplication(
                project,
                PrimitiveStepsCreatorProjectE2eTest.catalog()
        );

        assertThat(result).isInstanceOfSatisfying(CompileResult.Rejected.class, rejected ->
                assertThat(rejected.diagnostics())
                        .extracting(Diagnostic::code)
                        .contains("PROJECT_INPUT_VALIDATION_INVALID"));
    }

    @Test
    void collectionDirectoryRejectsCollisionWhenLockIdentifiesDifferentLogicalCollection() throws Exception {
        final Path dataDir = tempDir.resolve("collision-check");
        final Path ordersDir = Files.createDirectories(dataDir.resolve("orders"));
        Files.writeString(ordersDir.resolve("collection.lock"), "other-collection\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> StoreHarness.open(dataDir, "orders", "_id"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATABASE_COLLECTION_COLLISION");
    }

    @Test
    void configurableStorageLocationResolvesRelativeAndAbsoluteDirectoriesPredictably() {
        final Path customBase = tempDir.resolve("custom-base-dir");
        final String previousProp = System.getProperty("railix.data.dir");
        try {
            System.setProperty("railix.data.dir", customBase.toString());
            final Path resolvedRelative = DatabaseStepHandlers.resolveCollectionDirectory("store/app-data", "orders");
            assertThat(resolvedRelative).isEqualTo(customBase.resolve("store/app-data/orders").normalize());

            final Path explicitAbsolute = tempDir.resolve("explicit-abs").toAbsolutePath().normalize();
            final Path resolvedAbsolute = DatabaseStepHandlers.resolveCollectionDirectory(
                    explicitAbsolute.toString(),
                    "orders"
            );
            assertThat(resolvedAbsolute).isEqualTo(explicitAbsolute.resolve("orders").normalize());
        } finally {
            if (previousProp == null) {
                System.clearProperty("railix.data.dir");
            } else {
                System.setProperty("railix.data.dir", previousProp);
            }
        }
    }

    @Test
    void upsertGetAndDeletePreserveExactNumbersAndNestedJsonAcrossRestart() {
        final Path dataDir = tempDir.resolve("store-restart");
        final RailixValue.ObjectValue document = RailixValue.object(Map.of(
                "_id", RailixValue.string("order-1001"),
                "amount", RailixValue.number(new BigDecimal("12345678901234567890.0987654321")),
                "items", RailixValue.array(List.of(
                        RailixValue.object(Map.of("sku", RailixValue.string("A-1"), "qty", RailixValue.number(3))),
                        RailixValue.nullValue()
                )),
                "paid", RailixValue.bool(true)
        ));

        try (StoreHarness store = StoreHarness.open(dataDir, "orders", "_id")) {
            final StepResult saved = store.upsert(document, true);
            assertThat(saved.outcome()).isEqualTo("ok");
            assertThat(saved.writes().get("target")).isEqualTo(document);

            final StepResult read = store.get(RailixValue.string("order-1001"));
            assertThat(read.outcome()).isEqualTo("ok");
            assertThat(read.writes().get("target")).isEqualTo(document);
        }

        try (StoreHarness reopened = StoreHarness.open(dataDir, "orders", "_id")) {
            final StepResult readAfterRestart = reopened.get(RailixValue.string("order-1001"));
            assertThat(readAfterRestart.outcome()).isEqualTo("ok");
            assertThat(readAfterRestart.writes().get("target")).isEqualTo(document);

            final StepResult deleted = reopened.delete(RailixValue.string("order-1001"));
            assertThat(deleted.outcome()).isEqualTo("ok");
            assertThat(deleted.writes().get("target")).isEqualTo(document);

            final StepResult missing = reopened.get(RailixValue.string("order-1001"));
            assertThat(missing.outcome()).isEqualTo("not_found");
            assertThat(missing.writes()).isEmpty();

            final StepResult deleteMissing = reopened.delete(RailixValue.string("order-1001"));
            assertThat(deleteMissing.outcome()).isEqualTo("not_found");
            assertThat(deleteMissing.writes()).isEmpty();
        }

        try (StoreHarness afterDeleteRestart = StoreHarness.open(dataDir, "orders", "_id")) {
            assertThat(afterDeleteRestart.get(RailixValue.string("order-1001")).outcome()).isEqualTo("not_found");
        }
    }

    @Test
    void stringAndNumberKeysRemainDistinctWhileEquivalentNumericScalesShareOneKey() throws Exception {
        final Path dataDir = tempDir.resolve("typed-keys");
        final RailixValue.ObjectValue stringKeyed = RailixValue.object(Map.of(
                "code", RailixValue.string("42"),
                "label", RailixValue.string("string-forty-two")
        ));
        final RailixValue.ObjectValue numberKeyed = RailixValue.object(Map.of(
                "code", RailixValue.number(new BigDecimal("42.00")),
                "label", RailixValue.string("numeric-forty-two")
        ));
        final RailixValue.ObjectValue updatedNumber = RailixValue.object(Map.of(
                "code", RailixValue.number(42),
                "label", RailixValue.string("numeric-updated")
        ));

        try (StoreHarness store = StoreHarness.open(dataDir, "items", "code")) {
            assertThat(store.upsert(stringKeyed, false).outcome()).isEqualTo("ok");
            final StepResult firstNumeric = store.upsert(numberKeyed, false);
            assertThat(firstNumeric.outcome()).isEqualTo("ok");
            assertThat(((RailixValue.ObjectValue) firstNumeric.writes().get("target")).values().get("code"))
                    .isEqualTo(RailixValue.number(42));

            assertThat(store.upsert(updatedNumber, false).outcome()).isEqualTo("ok");

            assertThat(store.get(RailixValue.string("42")).writes().get("target")).isEqualTo(stringKeyed);
            assertThat(store.get(RailixValue.number(new BigDecimal("42.000"))).writes().get("target"))
                    .isEqualTo(updatedNumber);
        }

        final byte[] wal = Files.readAllBytes(dataDir.resolve("items/journal.wal"));
        final ByteBuffer header = ByteBuffer.wrap(wal);
        assertThat(header.getInt()).isEqualTo(DatabaseStepHandlers.MAGIC);
        assertThat(header.get()).isEqualTo((byte) 1);
        assertThat(header.get()).isEqualTo((byte) 1);
        final int bucket = Short.toUnsignedInt(header.getShort());
        assertThat(bucket)
                .isBetween(0, DatabaseStepHandlers.BUCKET_COUNT - 1)
                .isEqualTo(DatabaseStepHandlers.bucketForKey(RailixValue.string("42")));
    }

    @Test
    void extremeExponentNumericKeysAndNegativeZeroValuesPersistAcrossReopen() {
        final Path dataDir = tempDir.resolve("extreme-numeric-keys");
        final BigDecimal tinyNegativeKey = new BigDecimal("-1.23456E-1017");
        final BigDecimal hugeNegativeKey = new BigDecimal("-9.8765E+1022");

        try (StoreHarness store = StoreHarness.open(dataDir, "metrics", "_id")) {
            final StepResult savedTiny = store.upsert(RailixValue.object(Map.of(
                    "_id", RailixValue.number(tinyNegativeKey),
                    "delta", RailixValue.number(new BigDecimal("-0.00"))
            )), false);
            assertThat(savedTiny.outcome()).isEqualTo("ok");
            assertThat(((RailixValue.ObjectValue) savedTiny.writes().get("target")).values().get("delta"))
                    .isEqualTo(RailixValue.number(0));

            assertThat(store.upsert(RailixValue.object(Map.of(
                    "_id", RailixValue.number(hugeNegativeKey),
                    "delta", RailixValue.number(new BigDecimal("8E+1"))
            )), false).outcome()).isEqualTo("ok");
        }

        try (StoreHarness reopened = StoreHarness.open(dataDir, "metrics", "_id")) {
            final StepResult fetchedTiny = reopened.get(RailixValue.number(new BigDecimal("-1.2345600E-1017")));
            assertThat(fetchedTiny.outcome()).isEqualTo("ok");
            assertThat(((RailixValue.ObjectValue) fetchedTiny.writes().get("target")).values().get("delta"))
                    .isEqualTo(RailixValue.number(0));

            final StepResult fetchedHuge = reopened.get(RailixValue.number(hugeNegativeKey));
            assertThat(fetchedHuge.outcome()).isEqualTo("ok");
            assertThat(((RailixValue.ObjectValue) fetchedHuge.writes().get("target")).values().get("delta"))
                    .isEqualTo(RailixValue.number(80));
        }
    }

    @Test
    void autoKeyGeneratesUuidWhenMissingOrNullAndRejectsMissingKeyWhenDisabled() {
        final Path dataDir = tempDir.resolve("auto-keys");
        try (StoreHarness store = StoreHarness.open(dataDir, "users", "_id")) {
            final StepResult generatedFromAbsent = store.upsert(RailixValue.object(Map.of(
                    "name", RailixValue.string("Ada")
            )), true);
            assertThat(generatedFromAbsent.outcome()).isEqualTo("ok");
            final RailixValue.ObjectValue savedAda =
                    (RailixValue.ObjectValue) generatedFromAbsent.writes().get("target");
            final String adaId = ((RailixValue.StringValue) savedAda.values().get("_id")).value();
            assertThat(UUID.fromString(adaId)).isNotNull();

            final StepResult generatedFromNull = store.upsert(RailixValue.object(Map.of(
                    "_id", RailixValue.nullValue(),
                    "name", RailixValue.string("Grace")
            )), true);
            assertThat(generatedFromNull.outcome()).isEqualTo("ok");
            final RailixValue.ObjectValue savedGrace =
                    (RailixValue.ObjectValue) generatedFromNull.writes().get("target");
            final String graceId = ((RailixValue.StringValue) savedGrace.values().get("_id")).value();
            assertThat(UUID.fromString(graceId)).isNotEqualTo(UUID.fromString(adaId));

            final StepResult rejectedMissing = store.upsert(RailixValue.object(Map.of(
                    "name", RailixValue.string("NoId")
            )), false);
            assertThat(rejectedMissing.outcome()).isEqualTo("invalid");
            assertThat(rejectedMissing.writes()).isEmpty();

            assertThat(store.get(RailixValue.string(adaId)).writes().get("target")).isEqualTo(savedAda);
            assertThat(store.get(RailixValue.string(graceId)).writes().get("target")).isEqualTo(savedGrace);
        }
    }

    @Test
    void upsertAcceptsSingleDocumentOrBatchArrayAtomicallyAndResolvesDuplicateKeysInBatchOrder() {
        final Path dataDir = tempDir.resolve("bulk");
        try (StoreHarness store = StoreHarness.open(dataDir, "events", "_id")) {
            final StepResult emptyBatch = store.upsert(RailixValue.array(List.of()), true);
            assertThat(emptyBatch.outcome()).isEqualTo("ok");
            assertThat(emptyBatch.writes().get("target")).isEqualTo(RailixValue.array(List.of()));

            final StepResult atomicReject = store.upsert(RailixValue.array(List.of(
                    RailixValue.object(Map.of("_id", RailixValue.string("valid-1"), "v", RailixValue.number(1))),
                    RailixValue.object(Map.of("v", RailixValue.number(2)))
            )), false);
            assertThat(atomicReject.outcome()).isEqualTo("invalid");
            assertThat(atomicReject.writes()).isEmpty();
            assertThat(store.get(RailixValue.string("valid-1")).outcome()).isEqualTo("not_found");

            final StepResult committed = store.upsert(RailixValue.array(List.of(
                    RailixValue.object(Map.of("_id", RailixValue.string("dup"), "seq", RailixValue.number(1))),
                    RailixValue.object(Map.of("_id", RailixValue.string("unique"), "seq", RailixValue.number(2))),
                    RailixValue.object(Map.of("_id", RailixValue.string("dup"), "seq", RailixValue.number(3)))
            )), false);
            assertThat(committed.outcome()).isEqualTo("ok");
            assertThat(((RailixValue.ArrayValue) committed.writes().get("target")).values()).hasSize(3);
            assertThat(store.get(RailixValue.string("dup")).writes().get("target")).isEqualTo(
                    RailixValue.object(Map.of("_id", RailixValue.string("dup"), "seq", RailixValue.number(3)))
            );
            assertThat(store.get(RailixValue.string("unique")).writes().get("target")).isEqualTo(
                    RailixValue.object(Map.of("_id", RailixValue.string("unique"), "seq", RailixValue.number(2)))
            );
        }
    }

    @Test
    void invalidShapesAndOutOfBoundsInputsReturnInvalidWithoutThrowing() {
        final Path dataDir = tempDir.resolve("invalid-inputs");
        final String oversizedKey = "k".repeat(1_025);
        final BigDecimal oversizedNumber = new BigDecimal("9".repeat(RailixData.MAX_CANONICAL_NUMBER_CHARACTERS + 1));

        try (StoreHarness store = StoreHarness.open(dataDir, "strict", "_id")) {
            assertThat(store.upsert(null, true).outcome()).isEqualTo("invalid");
            assertThat(store.upsert(RailixValue.string("not-an-object"), true).outcome()).isEqualTo("invalid");
            assertThat(store.upsert(RailixValue.object(Map.of("_id", RailixValue.string(""))), false).outcome())
                    .isEqualTo("invalid");
            assertThat(store.upsert(RailixValue.object(Map.of("_id", RailixValue.bool(true))), false).outcome())
                    .isEqualTo("invalid");
            final RailixValue oversizedDoc = RailixValue.object(Map.of("_id", RailixValue.string(oversizedKey)));
            assertThat(store.upsert(oversizedDoc, false).outcome())
                    .isEqualTo("invalid");
            assertThat(store.upsert(RailixValue.object(Map.of(
                    "_id", RailixValue.string("n1"),
                    "bad", RailixValue.number(oversizedNumber)
            )), false).outcome()).isEqualTo("invalid");

            assertThat(store.upsert(RailixValue.array(List.of(RailixValue.number(1))), true).outcome())
                    .isEqualTo("invalid");
            assertThat(store.bulkUpsert(null, true).outcome()).isEqualTo("invalid");
            assertThat(store.bulkUpsert(RailixValue.object(Map.of()), true).outcome()).isEqualTo("invalid");

            assertThat(store.get(null).outcome()).isEqualTo("invalid");
            assertThat(store.get(RailixValue.nullValue()).outcome()).isEqualTo("invalid");
            assertThat(store.get(RailixValue.string("")).outcome()).isEqualTo("invalid");
            assertThat(store.get(RailixValue.array(List.of())).outcome()).isEqualTo("invalid");
            assertThat(store.delete(null).outcome()).isEqualTo("invalid");
            assertThat(store.delete(RailixValue.bool(false)).outcome()).isEqualTo("invalid");
        }
    }

    @Test
    void startupTruncatesIncompleteTrailingWriteAtEndOfJournal() throws Exception {
        final Path dataDir = tempDir.resolve("torn-wal");
        final RailixValue.ObjectValue first = RailixValue.object(Map.of(
                "_id", RailixValue.string("keep-1"),
                "value", RailixValue.string("intact")
        ));
        final long validSize;
        try (StoreHarness store = StoreHarness.open(dataDir, "wal", "_id")) {
            assertThat(store.upsert(first, false).outcome()).isEqualTo("ok");
            validSize = store.instance().journalSizeBytes();
        }

        final Path walPath = dataDir.resolve("wal/journal.wal");
        // Case 1: valid 20-byte frame header at EOF whose body was cut short (only 4 of 12 body bytes written).
        final ByteBuffer incompleteBodyFrame = ByteBuffer.allocate(DatabaseStepHandlers.HEADER_BYTES + 4);
        incompleteBodyFrame.putInt(DatabaseStepHandlers.MAGIC);
        incompleteBodyFrame.put((byte) 1);
        incompleteBodyFrame.put((byte) 1);
        incompleteBodyFrame.putShort((short) 0);
        incompleteBodyFrame.putInt(4);
        incompleteBodyFrame.putInt(8);
        incompleteBodyFrame.putInt(0x1234_5678);
        incompleteBodyFrame.put(new byte[]{'k', 'e', 'y', '2'});
        Files.write(walPath, incompleteBodyFrame.array(), StandardOpenOption.APPEND);
        assertThat(Files.size(walPath)).isEqualTo(validSize + DatabaseStepHandlers.HEADER_BYTES + 4);

        try (StoreHarness recovered = StoreHarness.open(dataDir, "wal", "_id")) {
            assertThat(Files.size(walPath)).isEqualTo(validSize);
            assertThat(recovered.get(RailixValue.string("keep-1")).writes().get("target")).isEqualTo(first);
        }

        // Case 2: partial 3-byte header prefix matching MAGIC at EOF.
        Files.write(walPath, new byte[]{0x52, 0x44, 0x42}, StandardOpenOption.APPEND);
        try (StoreHarness recoveredPartialHeader = StoreHarness.open(dataDir, "wal", "_id")) {
            assertThat(Files.size(walPath)).isEqualTo(validSize);
            assertThat(recoveredPartialHeader.get(RailixValue.string("keep-1")).writes().get("target"))
                    .isEqualTo(first);
        }
    }

    @Test
    void startupPreservesDamagedJournalAndFailsOnMidFileOrTrailingChecksumCorruption() throws Exception {
        final Path dataDir = tempDir.resolve("corrupt-wal");
        final RailixValue.ObjectValue rec1 = RailixValue.object(Map.of(
                "_id", RailixValue.string("rec-1"),
                "value", RailixValue.string("first-record")
        ));
        final RailixValue.ObjectValue rec2 = RailixValue.object(Map.of(
                "_id", RailixValue.string("rec-2"),
                "value", RailixValue.string("second-record")
        ));
        final RailixValue.ObjectValue rec3 = RailixValue.object(Map.of(
                "_id", RailixValue.string("rec-3"),
                "value", RailixValue.string("third-record")
        ));

        final long rec1OffsetEnd;
        try (StoreHarness store = StoreHarness.open(dataDir, "wal", "_id")) {
            assertThat(store.upsert(rec1, false).outcome()).isEqualTo("ok");
            rec1OffsetEnd = store.instance().journalSizeBytes();
            assertThat(store.upsert(rec2, false).outcome()).isEqualTo("ok");
            assertThat(store.upsert(rec3, false).outcome()).isEqualTo("ok");
        }

        final Path walPath = dataDir.resolve("wal/journal.wal");
        final byte[] cleanBytes = Files.readAllBytes(walPath);

        // Subcase A: Corrupt one byte inside rec-2's payload in the middle of the file (followed by valid rec-3).
        final byte[] midFileCorrupted = Arrays.copyOf(cleanBytes, cleanBytes.length);
        final int rec2PayloadByteIndex = (int) rec1OffsetEnd + DatabaseStepHandlers.HEADER_BYTES + 6;
        midFileCorrupted[rec2PayloadByteIndex] ^= 0x01;
        Files.write(walPath, midFileCorrupted, StandardOpenOption.TRUNCATE_EXISTING);

        assertThatThrownBy(() -> StoreHarness.open(dataDir, "wal", "_id"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATABASE_JOURNAL_CORRUPTED")
                .hasMessageContaining("offset " + rec1OffsetEnd);
        // Damaged file must be preserved intact on disk without truncating rec-2 or rec-3.
        assertThat(Files.readAllBytes(walPath)).isEqualTo(midFileCorrupted);

        // Subcase B: Restore clean bytes, then append a complete 32-byte frame with a bad CRC32 checksum.
        Files.write(walPath, cleanBytes, StandardOpenOption.TRUNCATE_EXISTING);
        final ByteBuffer corruptTrailingFrame = ByteBuffer.allocate(32);
        corruptTrailingFrame.putInt(DatabaseStepHandlers.MAGIC);
        corruptTrailingFrame.put((byte) 1);
        corruptTrailingFrame.put((byte) 1);
        corruptTrailingFrame.putShort((short) 0);
        corruptTrailingFrame.putInt(4);
        corruptTrailingFrame.putInt(8);
        corruptTrailingFrame.putInt(0xDEAD_BEEF);
        corruptTrailingFrame.put(new byte[12]);
        Files.write(walPath, corruptTrailingFrame.array(), StandardOpenOption.APPEND);
        final byte[] withCorruptTail = Files.readAllBytes(walPath);

        assertThatThrownBy(() -> StoreHarness.open(dataDir, "wal", "_id"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATABASE_JOURNAL_CORRUPTED");
        assertThat(Files.readAllBytes(walPath)).isEqualTo(withCorruptTail);
    }

    @Test
    void repeatedOverwritesCompactDeadJournalSpaceWhilePreservingLiveDocuments() throws Exception {
        final Path dataDir = tempDir.resolve("compaction");
        final String payloadPadding = "x".repeat(2_048);
        try (StoreHarness store = StoreHarness.open(dataDir, "compact", "_id")) {
            long peakSize = 0L;
            boolean compacted = false;
            for (int version = 1; version <= 80; version++) {
                final RailixValue.ObjectValue doc = RailixValue.object(Map.of(
                        "_id", RailixValue.string("hot-key"),
                        "version", RailixValue.number(version),
                        "padding", RailixValue.string(payloadPadding)
                ));
                assertThat(store.upsert(doc, false).outcome()).isEqualTo("ok");
                final long size = store.instance().journalSizeBytes();
                if (size < peakSize) {
                    compacted = true;
                }
                peakSize = Math.max(peakSize, size);
            }
            assertThat(compacted).isTrue();
            assertThat(store.instance().journalSizeBytes()).isLessThan(peakSize);
            assertThat(store.instance().lastCompactionPauseNanos()).isPositive();
            assertThat(((RailixValue.ObjectValue) store.get(RailixValue.string("hot-key")).writes().get("target"))
                    .values().get("version")).isEqualTo(RailixValue.number(80));
        }

        try (StoreHarness reopened = StoreHarness.open(dataDir, "compact", "_id")) {
            assertThat(((RailixValue.ObjectValue) reopened.get(RailixValue.string("hot-key")).writes().get("target"))
                    .values().get("version")).isEqualTo(RailixValue.number(80));
        }
    }

    @Test
    void concurrentVirtualThreadReadsAndWritesMaintainExactState() throws Exception {
        final Path dataDir = tempDir.resolve("concurrency");
        try (StoreHarness store = StoreHarness.open(dataDir, "concurrent", "_id");
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final List<Callable<Void>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 24; worker++) {
                final int id = worker;
                tasks.add(() -> {
                    final String key = "worker-" + id;
                    for (int step = 0; step < 15; step++) {
                        final RailixValue.ObjectValue doc = RailixValue.object(Map.of(
                                "_id", RailixValue.string(key),
                                "step", RailixValue.number(step)
                        ));
                        assertThat(store.upsert(doc, false).outcome()).isEqualTo("ok");
                        final StepResult current = store.get(RailixValue.string(key));
                        assertThat(current.outcome()).isEqualTo("ok");
                    }
                    return null;
                });
            }
            final List<Future<Void>> futures = executor.invokeAll(tasks);
            for (final Future<Void> future : futures) {
                future.get();
            }
        }

        try (StoreHarness verify = StoreHarness.open(dataDir, "concurrent", "_id")) {
            for (int worker = 0; worker < 24; worker++) {
                final StepResult result = verify.get(RailixValue.string("worker-" + worker));
                assertThat(result.outcome()).isEqualTo("ok");
                assertThat(((RailixValue.ObjectValue) result.writes().get("target")).values().get("step"))
                        .isEqualTo(RailixValue.number(14));
            }
        }
    }

    @Test
    void exclusiveProcessLockRejectsSecondIndependentStoreWhileAllowingSharedKeyReuse() {
        final Path dataDir = tempDir.resolve("locking");
        try (StoreHarness primary = StoreHarness.open(dataDir, "locked", "_id")) {
            final DatabaseStepHandlers reused = primary.acquireSecondSharedPlacement();
            assertThat(reused).isSameAs(primary.instance());

            assertThatThrownBy(() -> StoreHarness.open(dataDir, "locked", "_id"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DATABASE_COLLECTION_LOCKED");
        }

        try (StoreHarness reopenedAfterClose = StoreHarness.open(dataDir, "locked", "_id")) {
            assertThat(reopenedAfterClose.upsert(
                    RailixValue.object(Map.of("_id", RailixValue.string("after-unlock"))),
                    false
            ).outcome()).isEqualTo("ok");
        }
    }

    @Test
    void writeAndReadFieldProjectionsFilterPersistedAndReadFields() {
        final Path dataDir = tempDir.resolve("projections");
        try (StoreHarness store = StoreHarness.open(dataDir, "users", "id")) {
            final RailixValue.ObjectValue fullUser = RailixValue.object(Map.of(
                    "id", RailixValue.string("u-1"),
                    "name", RailixValue.string("Alice"),
                    "secret_token", RailixValue.string("pass123"),
                    "tier", RailixValue.string("pro")
            ));

            final StepResult written = store.upsert(fullUser, false, List.of("name", "tier"));
            assertThat(written.outcome()).isEqualTo("ok");
            final RailixValue.ObjectValue saved = (RailixValue.ObjectValue) written.writes().get("target");
            assertThat(saved.values()).containsOnlyKeys("id", "name", "tier");
            assertThat(saved.values()).doesNotContainKey("secret_token");

            final StepResult readFull = store.get(RailixValue.string("u-1"));
            assertThat(readFull.outcome()).isEqualTo("ok");
            final RailixValue.ObjectValue readDoc = (RailixValue.ObjectValue) readFull.writes().get("target");
            assertThat(readDoc.values()).containsOnlyKeys("id", "name", "tier");

            final StepResult readProjected = store.get(RailixValue.string("u-1"), List.of("tier"), null);
            assertThat(readProjected.outcome()).isEqualTo("ok");
            final RailixValue.ObjectValue tierOnly = (RailixValue.ObjectValue) readProjected.writes().get("target");
            assertThat(tierOnly.values()).containsOnlyKeys("id", "tier");
        }
    }

    @Test
    void readViewSuppliesDefaultsForNullOrMissingFieldsWhilePreservingPresentValues() {
        final Path dataDir = tempDir.resolve("defaults");
        try (StoreHarness store = StoreHarness.open(dataDir, "profiles", "_id")) {
            store.upsert(RailixValue.object(Map.of(
                    "_id", RailixValue.string("p-1"),
                    "role", RailixValue.nullValue(),
                    "theme", RailixValue.string("dark")
            )), false);

            final RailixValue.ObjectValue readDefaults = RailixValue.object(Map.of(
                    "role", RailixValue.string("viewer"),
                    "theme", RailixValue.string("light"),
                    "notifications", RailixValue.bool(true)
            ));

            final StepResult read = store.get(RailixValue.string("p-1"), List.of(), readDefaults);
            assertThat(read.outcome()).isEqualTo("ok");
            final RailixValue.ObjectValue result = (RailixValue.ObjectValue) read.writes().get("target");
            assertThat(result.values().get("role")).isEqualTo(RailixValue.string("viewer"));
            assertThat(result.values().get("theme")).isEqualTo(RailixValue.string("dark"));
            assertThat(result.values().get("notifications")).isEqualTo(RailixValue.bool(true));
        }
    }

    @Test
    void uniqueFieldConstraintRejectsDuplicateWritesAtomicallyAndAllowsSelfUpdates() {
        final Path dataDir = tempDir.resolve("unique");
        try (StoreHarness store = StoreHarness.open(
                dataDir, "accounts", "_id", List.of("email"), List.of("role")
        )) {
            final StepResult first = store.upsert(RailixValue.object(Map.of(
                    "_id", RailixValue.string("acc-1"),
                    "email", RailixValue.string("alice@example.com"),
                    "role", RailixValue.string("admin")
            )), false);
            assertThat(first.outcome()).isEqualTo("ok");

            final StepResult duplicate = store.upsert(RailixValue.object(Map.of(
                    "_id", RailixValue.string("acc-2"),
                    "email", RailixValue.string("alice@example.com"),
                    "role", RailixValue.string("member")
            )), false);
            assertThat(duplicate.outcome()).isEqualTo("invalid");

            final StepResult selfUpdate = store.upsert(RailixValue.object(Map.of(
                    "_id", RailixValue.string("acc-1"),
                    "email", RailixValue.string("alice@example.com"),
                    "role", RailixValue.string("owner")
            )), false);
            assertThat(selfUpdate.outcome()).isEqualTo("ok");

            final StepResult batchWithInternalConflict = store.upsert(RailixValue.array(List.of(
                    RailixValue.object(Map.of(
                            "_id", RailixValue.string("b-1"), "email", RailixValue.string("b1@x.com")
                    )),
                    RailixValue.object(Map.of(
                            "_id", RailixValue.string("b-2"), "email", RailixValue.string("b1@x.com")
                    ))
            )), false);
            assertThat(batchWithInternalConflict.outcome()).isEqualTo("invalid");
            assertThat(store.get(RailixValue.string("b-1")).outcome()).isEqualTo("not_found");

            store.delete(RailixValue.string("acc-1"));
            final StepResult claimedAfterDelete = store.upsert(RailixValue.object(Map.of(
                    "_id", RailixValue.string("acc-2"),
                    "email", RailixValue.string("alice@example.com"),
                    "role", RailixValue.string("member")
            )), false);
            assertThat(claimedAfterDelete.outcome()).isEqualTo("ok");
        }
    }

    @Test
    void findOperationFiltersRecordsByFilterCriteriaAcceleratedByIndexAndHonorsLimit() {
        final Path dataDir = tempDir.resolve("find");
        try (StoreHarness store = StoreHarness.open(
                dataDir, "orders", "_id", List.of(), List.of("status", "customer_id")
        )) {
            store.upsert(RailixValue.array(List.of(
                    RailixValue.object(Map.of("_id", RailixValue.string("o-1"), "status", RailixValue.string("open"),
                            "customer_id", RailixValue.string("c-1"), "amount", RailixValue.number(100))),
                    RailixValue.object(Map.of("_id", RailixValue.string("o-2"), "status", RailixValue.string("open"),
                            "customer_id", RailixValue.string("c-2"), "amount", RailixValue.number(200))),
                    RailixValue.object(Map.of("_id", RailixValue.string("o-3"), "status", RailixValue.string("closed"),
                            "customer_id", RailixValue.string("c-1"), "amount", RailixValue.number(150))),
                    RailixValue.object(Map.of("_id", RailixValue.string("o-4"), "status", RailixValue.string("open"),
                            "customer_id", RailixValue.string("c-1"), "amount", RailixValue.number(50)))
            )), false);

            final StepResult findOpenC1 = store.find(
                    RailixValue.object(Map.of(
                            "status", RailixValue.string("open"),
                            "customer_id", RailixValue.string("c-1")
                    )),
                    List.of("amount"),
                    null,
                    10
            );
            assertThat(findOpenC1.outcome()).isEqualTo("ok");
            final RailixValue.ArrayValue results = (RailixValue.ArrayValue) findOpenC1.writes().get("target");
            assertThat(results.values()).hasSize(2);
            assertThat(results.values().stream()
                    .map(item -> ((RailixValue.ObjectValue) item).values().get("_id"))
                    .toList())
                    .containsExactly(RailixValue.string("o-1"), RailixValue.string("o-4"));

            final StepResult findLimited = store.find(RailixValue.object(Map.of()), List.of(), null, 2);
            assertThat(findLimited.outcome()).isEqualTo("ok");
            assertThat(((RailixValue.ArrayValue) findLimited.writes().get("target")).values()).hasSize(2);

            final StepResult noMatch = store.find(
                    RailixValue.object(Map.of("status", RailixValue.string("nonexistent"))),
                    List.of(), null, 10
            );
            assertThat(noMatch.outcome()).isEqualTo("ok");
            assertThat(((RailixValue.ArrayValue) noMatch.writes().get("target")).values()).isEmpty();
        }
    }

    @Test
    void findPaginationWithAfterCursorIteratesBeyondSinglePageCeiling() {
        final Path dataDir = tempDir.resolve("find-pagination");
        final int totalRecords = 1_250;
        try (StoreHarness store = StoreHarness.open(
                dataDir, "ledger", "_id", List.of(), List.of("tenant")
        )) {
            final List<RailixValue> batch = new ArrayList<>(totalRecords);
            for (int i = 1; i <= totalRecords; i++) {
                batch.add(RailixValue.object(Map.of(
                        "_id", RailixValue.number(i),
                        "tenant", RailixValue.string("acme"),
                        "seq", RailixValue.number(i)
                )));
            }
            assertThat(store.upsert(RailixValue.array(batch), false).outcome()).isEqualTo("ok");

            final List<Integer> collectedIds = new ArrayList<>();
            RailixValue afterCursor = null;
            int pages = 0;
            while (true) {
                final StepResult pageResult = store.find(
                        RailixValue.object(Map.of("tenant", RailixValue.string("acme"))),
                        List.of("seq"),
                        null,
                        400,
                        afterCursor
                );
                assertThat(pageResult.outcome()).isEqualTo("ok");
                final List<RailixValue> pageItems =
                        ((RailixValue.ArrayValue) pageResult.writes().get("target")).values();
                if (pageItems.isEmpty()) {
                    break;
                }
                pages++;
                for (final RailixValue item : pageItems) {
                    final RailixValue key = ((RailixValue.ObjectValue) item).values().get("_id");
                    collectedIds.add(((RailixValue.NumberValue) key).value().intValueExact());
                }
                afterCursor = ((RailixValue.ObjectValue) pageItems.getLast()).values().get("_id");
                if (pageItems.size() < 400) {
                    break;
                }
            }

            assertThat(pages).isEqualTo(4);
            assertThat(collectedIds).hasSize(totalRecords);
            assertThat(collectedIds.getFirst()).isEqualTo(1);
            assertThat(collectedIds.getLast()).isEqualTo(totalRecords);
            assertThat(collectedIds).isSorted();

            final StepResult invalidCursor = store.find(
                    RailixValue.object(Map.of()),
                    List.of(),
                    null,
                    10,
                    RailixValue.string("")
            );
            assertThat(invalidCursor.outcome()).isEqualTo("invalid");
        }
    }

    @Test
    void realisticWorkloadMeasuresStartupReplayReadsWritesAndCompactionPauses() {
        final Path dataDir = tempDir.resolve("benchmark");
        final int recordCount = 200;
        final String summaryPayload = "invoice-line-item-description-".repeat(12);
        final List<RailixValue> initialBatch = new ArrayList<>(recordCount);
        for (int i = 0; i < recordCount; i++) {
            initialBatch.add(RailixValue.object(Map.of(
                    "_id", RailixValue.string("inv-" + i),
                    "sku", RailixValue.string("SKU-" + i),
                    "status", RailixValue.string(i % 4 == 0 ? "settled" : "open"),
                    "customer_id", RailixValue.string("cust-" + (i % 25)),
                    "total", RailixValue.number(new BigDecimal(i + ".95")),
                    "notes", RailixValue.string(summaryPayload)
            )));
        }

        final long batchWriteNanos;
        final long singleWriteMedianNanos;
        final long getReadMedianNanos;
        final long findReadMedianNanos;
        final long compactionPauseNanos;

        try (StoreHarness store = StoreHarness.open(
                dataDir,
                "invoices",
                "_id",
                List.of("sku"),
                List.of("status", "customer_id")
        )) {
            final long batchStart = System.nanoTime();
            assertThat(store.upsert(RailixValue.array(initialBatch), false).outcome()).isEqualTo("ok");
            batchWriteNanos = System.nanoTime() - batchStart;

            final long[] getSamples = new long[100];
            for (int i = 0; i < getSamples.length; i++) {
                final long start = System.nanoTime();
                assertThat(store.get(RailixValue.string("inv-" + (i * 2))).outcome()).isEqualTo("ok");
                getSamples[i] = System.nanoTime() - start;
            }
            Arrays.sort(getSamples);
            getReadMedianNanos = getSamples[getSamples.length / 2];

            final long[] findSamples = new long[40];
            for (int i = 0; i < findSamples.length; i++) {
                final long start = System.nanoTime();
                final StepResult found = store.find(
                        RailixValue.object(Map.of(
                                "customer_id", RailixValue.string("cust-" + (i % 25)),
                                "status", RailixValue.string("open")
                        )),
                        List.of("total", "status"),
                        null,
                        50
                );
                assertThat(found.outcome()).isEqualTo("ok");
                findSamples[i] = System.nanoTime() - start;
            }
            Arrays.sort(findSamples);
            findReadMedianNanos = findSamples[findSamples.length / 2];

            final long[] writeSamples = new long[220];
            for (int i = 0; i < writeSamples.length; i++) {
                final int idx = i % recordCount;
                final RailixValue.ObjectValue update = RailixValue.object(Map.of(
                        "_id", RailixValue.string("inv-" + idx),
                        "sku", RailixValue.string("SKU-" + idx),
                        "status", RailixValue.string("settled"),
                        "customer_id", RailixValue.string("cust-" + (idx % 25)),
                        "total", RailixValue.number(new BigDecimal((idx + i) + ".50")),
                        "notes", RailixValue.string(summaryPayload)
                ));
                final long start = System.nanoTime();
                assertThat(store.upsert(update, false).outcome()).isEqualTo("ok");
                writeSamples[i] = System.nanoTime() - start;
            }
            Arrays.sort(writeSamples);
            singleWriteMedianNanos = writeSamples[writeSamples.length / 2];
            compactionPauseNanos = store.instance().lastCompactionPauseNanos();
            assertThat(compactionPauseNanos).isPositive();
        }

        final long replayStart = System.nanoTime();
        try (StoreHarness reopened = StoreHarness.open(
                dataDir,
                "invoices",
                "_id",
                List.of("sku"),
                List.of("status", "customer_id")
        )) {
            final long startupReplayNanos = System.nanoTime() - replayStart;
            assertThat(reopened.get(RailixValue.string("inv-0")).outcome()).isEqualTo("ok");
            System.out.printf(
                    Locale.ROOT,
                    "RAILIX_DATABASE_BENCHMARK advisory=true records=%d batch_write_ms=%.3f single_write_p50_ms=%.3f "
                            + "get_p50_ms=%.3f find_p50_ms=%.3f compaction_pause_ms=%.3f startup_replay_ms=%.3f%n",
                    recordCount,
                    batchWriteNanos / 1_000_000.0,
                    singleWriteMedianNanos / 1_000_000.0,
                    getReadMedianNanos / 1_000_000.0,
                    findReadMedianNanos / 1_000_000.0,
                    compactionPauseNanos / 1_000_000.0,
                    startupReplayNanos / 1_000_000.0
            );
        }
    }

    @Test
    void multiIndexFindIntersectsCandidateSetsAndHandlesNullIndexedFilter() {
        final Path dataDir = tempDir.resolve("multi-index-intersection");
        try (StoreHarness store = StoreHarness.open(
                dataDir,
                "tickets",
                "_id",
                List.of(),
                List.of("status", "region", "assignee")
        )) {
            final Map<String, RailixValue> unassigned = new LinkedHashMap<>();
            unassigned.put("_id", RailixValue.string("t-1"));
            unassigned.put("status", RailixValue.string("open"));
            unassigned.put("region", RailixValue.string("eu"));
            unassigned.put("assignee", RailixValue.nullValue());

            final Map<String, RailixValue> assignedEu = new LinkedHashMap<>();
            assignedEu.put("_id", RailixValue.string("t-2"));
            assignedEu.put("status", RailixValue.string("open"));
            assignedEu.put("region", RailixValue.string("eu"));
            assignedEu.put("assignee", RailixValue.string("alice"));

            final Map<String, RailixValue> assignedUs = new LinkedHashMap<>();
            assignedUs.put("_id", RailixValue.string("t-3"));
            assignedUs.put("status", RailixValue.string("open"));
            assignedUs.put("region", RailixValue.string("us"));
            assignedUs.put("assignee", RailixValue.string("alice"));

            assertThat(store.upsert(
                    RailixValue.array(List.of(
                            RailixValue.object(unassigned),
                            RailixValue.object(assignedEu),
                            RailixValue.object(assignedUs)
                    )),
                    false
            ).outcome()).isEqualTo("ok");

            final Map<String, RailixValue> nullAndIndexedFilter = new LinkedHashMap<>();
            nullAndIndexedFilter.put("assignee", RailixValue.nullValue());
            nullAndIndexedFilter.put("status", RailixValue.string("open"));
            nullAndIndexedFilter.put("region", RailixValue.string("eu"));
            final StepResult nullMatch = store.find(
                    RailixValue.object(nullAndIndexedFilter),
                    List.of("region"),
                    null,
                    10
            );
            assertThat(nullMatch.outcome()).isEqualTo("ok");
            final List<RailixValue> items = ((RailixValue.ArrayValue) nullMatch.writes().get("target")).values();
            assertThat(items).hasSize(1);
            assertThat(((RailixValue.ObjectValue) items.getFirst()).values().get("_id"))
                    .isEqualTo(RailixValue.string("t-1"));
        }
    }

    @Test
    void sharedPlacementRejectsMismatchedKeyFieldAndNonPortableDirectoryCharacters() {
        assertThat(DatabaseStepHandlers.validStorageDirectory("data*bad")).isFalse();
        assertThat(DatabaseStepHandlers.validStorageDirectory("data?bad")).isFalse();
        assertThat(DatabaseStepHandlers.validStorageDirectory("data:bad")).isFalse();
        assertThat(DatabaseStepHandlers.validStorageDirectory("data/trailing.")).isFalse();
        assertThat(DatabaseStepHandlers.validStorageDirectory("data/trailing ")).isFalse();
        assertThat(DatabaseStepHandlers.validStorageDirectory("data/railix-store")).isTrue();

        final Path dataDir = tempDir.resolve("shared-key-mismatch");
        try (StoreHarness store = StoreHarness.open(dataDir, "orders", "_id")) {
            final Map<String, RailixValue> mismatchValues = new LinkedHashMap<>();
            mismatchValues.put("collection", RailixValue.string("orders"));
            mismatchValues.put("directory", RailixValue.string(dataDir.toString()));
            mismatchValues.put("key_field", RailixValue.string("order_id"));
            mismatchValues.put("unique_fields", RailixValue.array(List.of()));
            mismatchValues.put("index_fields", RailixValue.array(List.of()));
            final StepInput mismatchConfig = new StepInput(
                    mismatchValues,
                    Map.of("operation", "get"),
                    Map.of(),
                    Map.of("operation", new StepInput(Map.of(), Map.of(), Map.of(), Map.of(), "ok")),
                    "ok"
            );
            final SharedProbeResources sharedProbe = new SharedProbeResources(store.resources);
            assertThatThrownBy(() -> DatabaseStepHandlers.pre(
                    mismatchConfig,
                    StepInput.lifecycle("ok", null, sharedProbe)
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DATABASE_KEY_FIELD_MISMATCH");
        }
    }

    private static final class StoreHarness implements AutoCloseable {
        private final StepInput configuration;
        private final InMemoryResources resources = new InMemoryResources();

        private StoreHarness(final StepInput configuration) {
            this.configuration = configuration;
        }

        static StoreHarness open(final Path directory, final String collection, final String keyField) {
            return open(directory, collection, keyField, List.of(), List.of());
        }

        static StoreHarness open(
                final Path directory,
                final String collection,
                final String keyField,
                final List<String> uniqueFields,
                final List<String> indexFields
        ) {
            final Map<String, RailixValue> configValues = new LinkedHashMap<>();
            configValues.put("collection", RailixValue.string(collection));
            configValues.put("directory", RailixValue.string(directory.toString()));
            configValues.put("key_field", RailixValue.string(keyField));
            configValues.put("unique_fields",
                    RailixValue.array(uniqueFields.stream().<RailixValue>map(RailixValue::string).toList()));
            configValues.put("index_fields",
                    RailixValue.array(indexFields.stream().<RailixValue>map(RailixValue::string).toList()));
            final StepInput config = new StepInput(
                    configValues,
                    Map.of("operation", "upsert"),
                    Map.of(),
                    Map.of("operation", new StepInput(
                            Map.of("auto_key", RailixValue.bool(true)),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            "ok"
                    )),
                    "ok"
            );
            final StoreHarness harness = new StoreHarness(config);
            final StepResult pre = DatabaseStepHandlers.pre(
                    config,
                    StepInput.lifecycle("ok", null, harness.resources)
            );
            assertThat(pre.outcome()).isEqualTo("ok");
            return harness;
        }

        DatabaseStepHandlers instance() {
            return resources.get("store", DatabaseStepHandlers.class);
        }

        DatabaseStepHandlers acquireSecondSharedPlacement() {
            final SharedProbeResources sharedProbe = new SharedProbeResources(resources);
            DatabaseStepHandlers.pre(configuration, StepInput.lifecycle("ok", null, sharedProbe));
            return sharedProbe.get("store", DatabaseStepHandlers.class);
        }

        StepResult upsert(final RailixValue document, final boolean autoKey) {
            return upsert(document, autoKey, List.of());
        }

        StepResult upsert(final RailixValue document, final boolean autoKey, final List<String> fields) {
            final Map<String, RailixValue> child = new LinkedHashMap<>();
            child.put("auto_key", RailixValue.bool(autoKey));
            if (!fields.isEmpty()) {
                child.put("fields", RailixValue.array(fields.stream().<RailixValue>map(RailixValue::string).toList()));
            }
            if (document != null) {
                child.put("document", document);
            }
            return invoke("upsert", child);
        }

        StepResult bulkUpsert(final RailixValue documents, final boolean autoKey) {
            return bulkUpsert(documents, autoKey, List.of());
        }

        StepResult bulkUpsert(final RailixValue documents, final boolean autoKey, final List<String> fields) {
            final Map<String, RailixValue> child = new LinkedHashMap<>();
            child.put("auto_key", RailixValue.bool(autoKey));
            if (!fields.isEmpty()) {
                child.put("fields", RailixValue.array(fields.stream().<RailixValue>map(RailixValue::string).toList()));
            }
            if (documents != null) {
                child.put("documents", documents);
            }
            return invoke("bulk_upsert", child);
        }

        StepResult get(final RailixValue key) {
            return get(key, List.of(), null);
        }

        StepResult get(final RailixValue key, final List<String> fields, final RailixValue defaults) {
            final Map<String, RailixValue> child = new LinkedHashMap<>();
            if (key != null) {
                child.put("key", key);
            }
            if (!fields.isEmpty()) {
                child.put("fields",
                        RailixValue.array(fields.stream().<RailixValue>map(RailixValue::string).toList()));
            }
            if (defaults != null) {
                child.put("defaults", defaults);
            }
            return invoke("get", child);
        }

        StepResult find(
                final RailixValue filter,
                final List<String> fields,
                final RailixValue defaults,
                final Integer limit
        ) {
            return find(filter, fields, defaults, limit, null);
        }

        StepResult find(
                final RailixValue filter,
                final List<String> fields,
                final RailixValue defaults,
                final Integer limit,
                final RailixValue after
        ) {
            final Map<String, RailixValue> child = new LinkedHashMap<>();
            if (filter != null) {
                child.put("filter", filter);
            }
            if (after != null) {
                child.put("after", after);
            }
            if (!fields.isEmpty()) {
                child.put("fields",
                        RailixValue.array(fields.stream().<RailixValue>map(RailixValue::string).toList()));
            }
            if (defaults != null) {
                child.put("defaults", defaults);
            }
            if (limit != null) {
                child.put("limit", RailixValue.number(limit));
            }
            return invoke("find", child);
        }

        StepResult delete(final RailixValue key) {
            final Map<String, RailixValue> child = key == null ? Map.of() : Map.of("key", key);
            return invoke("delete", child);
        }

        private StepResult invoke(final String operation, final Map<String, RailixValue> selectedValues) {
            final StepInput selected = new StepInput(selectedValues, Map.of(), Map.of(), Map.of(), "ok");
            final StepInput invocation = StepInput.invocation(
                    Map.of(),
                    Map.of("operation", operation),
                    Map.of(),
                    Map.of("operation", selected),
                    "ok",
                    resources
            );
            return DatabaseStepHandlers.process(configuration, invocation);
        }

        @Override
        public void close() {
            resources.closeAll();
        }
    }

    private static final class InMemoryResources implements StepInput.Resources {
        private final Map<String, Object> entries = new LinkedHashMap<>();
        private final Map<Object, Object> sharedByKey = new LinkedHashMap<>();
        private final List<Runnable> closers = new ArrayList<>();

        @Override
        public <T> T get(final String name, final Class<T> type) {
            return type.cast(entries.get(name));
        }

        @Override
        public <T> T acquire(
                final String name,
                final Class<T> type,
                final Object sharingKey,
                final Supplier<? extends T> factory,
                final Consumer<? super T> close
        ) {
            if (sharingKey != null && sharedByKey.containsKey(sharingKey)) {
                final T existing = type.cast(sharedByKey.get(sharingKey));
                entries.put(name, existing);
                return existing;
            }
            final T created = factory.get();
            entries.put(name, created);
            if (sharingKey != null) {
                sharedByKey.put(sharingKey, created);
            }
            closers.add(() -> close.accept(created));
            return created;
        }

        void closeAll() {
            for (int i = closers.size() - 1; i >= 0; i--) {
                closers.get(i).run();
            }
            closers.clear();
            entries.clear();
            sharedByKey.clear();
        }
    }

    private static final class SharedProbeResources implements StepInput.Resources {
        private final InMemoryResources parent;
        private final Map<String, Object> local = new LinkedHashMap<>();

        private SharedProbeResources(final InMemoryResources parent) {
            this.parent = parent;
        }

        @Override
        public <T> T get(final String name, final Class<T> type) {
            return type.cast(local.get(name));
        }

        @Override
        public <T> T acquire(
                final String name,
                final Class<T> type,
                final Object sharingKey,
                final Supplier<? extends T> factory,
                final Consumer<? super T> close
        ) {
            final T shared = parent.acquire(name, type, sharingKey, factory, close);
            local.put(name, shared);
            return shared;
        }
    }
}
