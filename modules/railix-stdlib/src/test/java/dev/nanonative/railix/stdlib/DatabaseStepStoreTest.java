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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.CRC32;

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
                    final StepDefinition.Option upsert = operation.options().get(0);
                    assertThat(upsert.inputs()).extracting(StepDefinition.Field::name)
                            .containsExactly("document", "condition", "auto_key", "fields");
                    final StepDefinition.Option find = operation.options().get(2);
                    assertThat(find.inputs()).extracting(StepDefinition.Field::name)
                            .containsExactly("filter", "after", "fields", "defaults", "limit");
                    final StepDefinition.Option delete = operation.options().get(3);
                    assertThat(delete.inputs()).extracting(StepDefinition.Field::name)
                            .containsExactly("key", "condition");
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
            "key_field, '   '",
            "key_field, '$id'",
            "key_field, 'order.id'"
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

    @ParameterizedTest(name = "invalid index path {0} fails configuration validation")
    @CsvSource({
            ".leading",
            "trailing.",
            "customer..tier",
            "customer.$eq"
    })
    void compilerRejectsMalformedNestedIndexFieldPaths(final String badPath) {
        final String project = """
                {
                  "format": 1,
                  "id": "db-index-path-validation",
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
                      "inputs": {"index_fields": [%s]}
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
                """.formatted(RailixJson.write(RailixValue.string(badPath)));

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
    void extendedQueryOperatorsAndNestedDotPathsFilterAndPaginateDeterministically() {
        final Path dataDir = tempDir.resolve("extended-operators");
        try (StoreHarness store = StoreHarness.open(
                dataDir,
                "orders",
                "_id",
                List.of("customer.email"),
                List.of("total", "customer.tier", "created_at")
        )) {
            assertThat(store.upsert(RailixValue.array(List.of(
                    parseJsonObject("""
                            {"_id": 10, "status": "open", "total": 100.00, "created_at": "2026-01-10",
                             "notes": null,
                             "customer": {"id": "c-1", "email": "ada@ex.org", "tier": "gold"},
                             "items": [{"sku": "A-1", "qty": 2}], "meta": {"source": "api"}}"""),
                    parseJsonObject("""
                            {"_id": 20, "status": "closed", "total": 250, "created_at": "2026-03-05",
                             "customer": {"id": "c-2", "email": "grace@ex.org", "tier": "silver"},
                             "items": [{"sku": "B-2", "qty": 1}], "meta": {}}"""),
                    parseJsonObject("""
                            {"_id": 30, "status": "open", "total": "999", "created_at": "2026-06-15",
                             "notes": "priority",
                             "customer": {"id": "c-3", "email": "linus@ex.org", "tier": "gold"},
                             "items": [{"sku": "A-1", "qty": 5}]}"""),
                    parseJsonObject("""
                            {"_id": 40, "total": 175.5, "created_at": "2026-02-20",
                             "customer": {"id": "c-4", "email": "margaret@ex.org", "tier": "bronze"},
                             "items": [{"sku": "C-3", "qty": 4}]}""")
            )), false).outcome()).isEqualTo("ok");

            assertThat(findIds(store, parseJsonObject("""
                    {"total": {"$gte": 100, "$lt": 250}}"""), null)).containsExactly(10, 40);

            assertThat(findIds(store, parseJsonObject("""
                    {"total": {"$gt": 200, "$lt": 150}}"""), null)).isEmpty();
            assertThat(findIds(store, parseJsonObject("""
                    {"total": {"$gt": 10, "$lt": "abc"}}"""), null)).isEmpty();

            assertThat(findIds(store, parseJsonObject("""
                    {"created_at": {"$gte": "2026-02-01", "$lte": "2026-06-01"},
                     "customer.tier": {"$in": ["silver", "bronze"]}}"""), null)).containsExactly(20, 40);

            assertThat(findIds(store, parseJsonObject("""
                    {"notes": {"$exists": true}}"""), null)).containsExactly(10, 30);
            assertThat(findIds(store, parseJsonObject("""
                    {"notes": {"$exists": false}}"""), null)).containsExactly(20, 40);
            assertThat(findIds(store, parseJsonObject("""
                    {"notes": null}"""), null)).containsExactly(10, 20, 40);
            assertThat(findIds(store, parseJsonObject("""
                    {"notes": {"$ne": null}}"""), null)).containsExactly(30);

            assertThat(findIds(store, parseJsonObject("""
                    {"status": {"$ne": "closed"}}"""), null)).containsExactly(10, 30, 40);
            assertThat(findIds(store, parseJsonObject("""
                    {"customer.tier": {"$nin": ["gold", "platinum"]}}"""), null)).containsExactly(20, 40);
            assertThat(findIds(store, parseJsonObject("""
                    {"$and": [
                      {"items.0.sku": "A-1"},
                      {"$or": [{"status": "open"}, {"total": {"$gte": 200}}]}
                    ]}"""), null)).containsExactly(10, 30);
            assertThat(findIds(store, parseJsonObject("""
                    {"meta": {}}"""), null)).containsExactly(20);

            assertThat(findIds(store, parseJsonObject("""
                    {"$or": [{"customer.tier": "silver"}, {"customer.tier": "bronze"}]}"""), null))
                    .containsExactly(20, 40);
            assertThat(findIds(store, parseJsonObject("""
                    {"_id": {"$in": [10, 30, 999]}}"""), null)).containsExactly(10, 30);
            assertThat(findIds(store, parseJsonObject("""
                    {"_id": {"$gt": 10, "$lte": 40}}"""), RailixValue.number(20))).containsExactly(30, 40);

            assertThat(store.upsert(parseJsonObject("""
                    {"_id": 50, "customer": {"email": "ada@ex.org"}}"""), false).outcome()).isEqualTo("invalid");
        }

        try (StoreHarness reopened = StoreHarness.open(
                dataDir,
                "orders",
                "_id",
                List.of("customer.email"),
                List.of("total", "customer.tier", "created_at")
        )) {
            assertThat(findIds(reopened, parseJsonObject("""
                    {"customer.tier": "gold", "total": {"$gt": 50}}"""), null)).containsExactly(10);
        }
    }

    @Test
    void invalidExtendedFilterOperatorsAndPathsReturnInvalid() {
        final Path dataDir = tempDir.resolve("invalid-filters");
        try (StoreHarness store = StoreHarness.open(dataDir, "items", "_id")) {
            for (final String badJson : List.of(
                    """
                    {"$unknown": 1}""",
                    """
                    {"price": {"$regex": "10"}}""",
                    """
                    {"price": {"$eq": 10, "extra": 1}}""",
                    """
                    {"price": {"$gt": true}}""",
                    """
                    {"price": {"$lte": null}}""",
                    """
                    {"status": {"$in": "open"}}""",
                    """
                    {"notes": {"$exists": "yes"}}""",
                    """
                    {"$or": []}""",
                    """
                    {"$and": ["not-an-object"]}""",
                    """
                    {"customer..tier": "gold"}""",
                    """
                    {".tier": "gold"}""",
                    """
                    {"customer.": "gold"}""",
                    """
                    {"customer.$gt": 1}"""
            )) {
                assertThat(store.find(parseJsonObject(badJson), List.of(), null, 10).outcome())
                        .as("filter=%s", badJson)
                        .isEqualTo("invalid");
            }

            RailixValue nested = RailixValue.object(Map.of("a", RailixValue.number(1)));
            for (int d = 0; d <= DatabaseStepHandlers.MAX_FILTER_DEPTH + 1; d++) {
                nested = RailixValue.object(Map.of("$and", RailixValue.array(List.of(nested))));
            }
            assertThat(store.find(nested, List.of(), null, 10).outcome()).isEqualTo("invalid");
            assertThat(store.find(null, List.of(), null, 10).outcome()).isEqualTo("invalid");
            assertThat(store.find(RailixValue.nullValue(), List.of(), null, 10).outcome()).isEqualTo("invalid");
            assertThat(store.find(RailixValue.object(Map.of()), List.of(), null, 10).outcome()).isEqualTo("ok");
        }
    }

    @Test
    void compoundCreateConditionsRespectExplicitAbsentKeyAndOtherPredicates() {
        final Path dataDir = tempDir.resolve("compound-create-conditions");
        try (StoreHarness store = StoreHarness.open(dataDir, "orders", "_id")) {
            assertThat(store.upsert(
                    parseJsonObject("""
                            {"_id": "ord-1", "status": "open", "version": 1}"""),
                    false,
                    parseJsonObject("""
                            {"_id": {"$exists": false}, "status": {"$exists": false}}""")
            ).outcome()).isEqualTo("ok");

            assertThat(store.upsert(
                    parseJsonObject("""
                            {"_id": "ord-2", "status": "open", "version": 1}"""),
                    false,
                    parseJsonObject("""
                            {"_id": {"$exists": false}, "status": {"$ne": "closed"}}""")
            ).outcome()).isEqualTo("ok");

            assertThat(store.upsert(
                    parseJsonObject("""
                            {"_id": "ord-3", "status": "open", "version": 1}"""),
                    false,
                    parseJsonObject("""
                            {"_id": {"$exists": false}, "status": null}""")
            ).outcome()).isEqualTo("ok");

            assertThat(store.upsert(
                    parseJsonObject("""
                            {"_id": "ord-4", "status": "open", "version": 1}"""),
                    false,
                    parseJsonObject("""
                            {"$and": [
                              {"_id": {"$exists": false}},
                              {"$or": [{"status": {"$exists": false}}, {"status": "draft"}]}
                            ]}""")
            ).outcome()).isEqualTo("ok");

            final RailixValue compoundCreateOrV1 = parseJsonObject("""
                    {"$or": [
                      {"_id": {"$exists": false}, "status": {"$exists": false}},
                      {"version": 1}
                    ]}""");
            assertThat(store.upsert(
                    parseJsonObject("""
                            {"_id": "ord-5", "status": "draft", "version": 1}"""),
                    false,
                    compoundCreateOrV1
            ).outcome()).isEqualTo("ok");
            assertThat(store.upsert(
                    parseJsonObject("""
                            {"_id": "ord-5", "status": "paid", "version": 2}"""),
                    false,
                    compoundCreateOrV1
            ).outcome()).isEqualTo("ok");
            assertThat(store.upsert(
                    parseJsonObject("""
                            {"_id": "ord-5", "status": "stale", "version": 3}"""),
                    false,
                    compoundCreateOrV1
            ).outcome()).isEqualTo("not_found");

            assertThat(store.upsert(
                    RailixValue.array(List.of(
                            parseJsonObject("""
                                    {"_id": "ord-6", "status": "open", "version": 1}"""),
                            parseJsonObject("""
                                    {"_id": "ord-7", "status": "open", "version": 1}""")
                    )),
                    false,
                    parseJsonObject("""
                            {"_id": {"$exists": false}, "status": {"$ne": "closed"}}""")
            ).outcome()).isEqualTo("ok");

            for (final String rejectedAbsentCondition : List.of(
                    """
                    {"_id": {"$exists": false}, "version": 1}""",
                    """
                    {"_id": {"$exists": false, "$eq": "ord-missing"}}""",
                    """
                    {"$or": [{"_id": {"$exists": false}, "version": 1}, {"status": null}]}""",
                    """
                    {"$and": [{"_id": {"$exists": false}}, {"$or": [{"version": 1}, {"status": "draft"}]}]}"""
            )) {
                assertThat(store.upsert(
                        parseJsonObject("""
                                {"_id": "ord-missing", "status": "open", "version": 1}"""),
                        false,
                        parseJsonObject(rejectedAbsentCondition)
                ).outcome())
                        .as("condition=%s", rejectedAbsentCondition)
                        .isEqualTo("not_found");
            }
        }

        final Path customKeyDir = tempDir.resolve("compound-create-custom-key");
        try (StoreHarness customStore = StoreHarness.open(customKeyDir, "custom-orders", "order_id")) {
            assertThat(customStore.upsert(
                    parseJsonObject("""
                            {"order_id": "cust-1", "status": "open"}"""),
                    false,
                    parseJsonObject("""
                            {"_id": {"$exists": false}, "status": {"$exists": false}}""")
            ).outcome()).isEqualTo("not_found");

            assertThat(customStore.upsert(
                    parseJsonObject("""
                            {"order_id": "cust-1", "status": "open"}"""),
                    false,
                    parseJsonObject("""
                            {"order_id": {"$exists": false}, "status": {"$ne": "locked"}}""")
            ).outcome()).isEqualTo("ok");
        }
    }

    @Test
    void corruptedNumericKeyInJournalFailsWithCorruptedJournalError() throws Exception {
        final Path dataDir = tempDir.resolve("corrupt-num-key");
        final Path walPath = dataDir.resolve("bad-num/journal.wal");
        try (StoreHarness store = StoreHarness.open(dataDir, "bad-num", "_id")) {
            assertThat(store.upsert(RailixValue.object(Map.of("_id", RailixValue.number(1))), false).outcome())
                    .isEqualTo("ok");
        }

        appendSyntheticNumericFrame(walPath, "not-a-num", "{}");
        final byte[] damagedBytes = Files.readAllBytes(walPath);

        assertThatThrownBy(() -> StoreHarness.open(dataDir, "bad-num", "_id"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATABASE_JOURNAL_CORRUPTED");
        assertThat(Files.readAllBytes(walPath)).isEqualTo(damagedBytes);
    }

    @Test
    void compactionFailureDoesNotLoseCommittedWritesOrBrickStore() throws Exception {
        final Path dataDir = tempDir.resolve("compaction-failure");
        final Path compactDir = dataDir.resolve("compact-fail/journal.wal.compact");
        final Path blocker = compactDir.resolve("blocker");
        final String padding = "x".repeat(2_048);

        try (StoreHarness store = StoreHarness.open(dataDir, "compact-fail", "_id")) {
            Files.createDirectories(compactDir);
            Files.writeString(blocker, "hold");

            for (int version = 1; version <= 45; version++) {
                final StepResult result = store.upsert(RailixValue.object(Map.of(
                        "_id", RailixValue.string("k1"),
                        "version", RailixValue.number(version),
                        "padding", RailixValue.string(padding)
                )), false);
                assertThat(result.outcome()).isEqualTo("ok");
            }
            assertThat(store.instance().lastCompactionPauseNanos()).isZero();
            assertThat(((RailixValue.ObjectValue) store.get(RailixValue.string("k1")).writes().get("target"))
                    .values().get("version")).isEqualTo(RailixValue.number(45));

            Files.deleteIfExists(blocker);
            Files.deleteIfExists(compactDir);
            final long peakSize = store.instance().journalSizeBytes();
            for (int version = 46; version <= 110; version++) {
                assertThat(store.upsert(RailixValue.object(Map.of(
                        "_id", RailixValue.string("k1"),
                        "version", RailixValue.number(version),
                        "padding", RailixValue.string(padding)
                )), false).outcome()).isEqualTo("ok");
            }
            assertThat(store.instance().lastCompactionPauseNanos()).isPositive();
            assertThat(store.instance().journalSizeBytes()).isLessThan(peakSize);
            assertThat(((RailixValue.ObjectValue) store.get(RailixValue.string("k1")).writes().get("target"))
                    .values().get("version")).isEqualTo(RailixValue.number(110));
        }
    }

    @Test
    void interruptedFindAbortsCooperativelyAndReleasesLock() {
        final Path dataDir = tempDir.resolve("interrupt-find");
        try (StoreHarness store = StoreHarness.open(dataDir, "items", "_id")) {
            assertThat(store.upsert(RailixValue.object(Map.of("_id", RailixValue.string("i-1"))), false).outcome())
                    .isEqualTo("ok");

            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> store.findInterruptibly(RailixValue.object(Map.of()), 10))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Thread.interrupted()).isFalse();

            assertThat(store.upsert(RailixValue.object(Map.of("_id", RailixValue.string("i-2"))), false).outcome())
                    .isEqualTo("ok");
            assertThat(store.get(RailixValue.string("i-1")).outcome()).isEqualTo("ok");
        }
    }

    @Test
    void indexedFindStreamsKeyOrderedPagesAcrossAfterCursorWithoutMissingKeys() {
        final Path dataDir = tempDir.resolve("indexed-stream");
        try (StoreHarness store = StoreHarness.open(dataDir, "orders", "_id", List.of(), List.of("status"))) {
            assertThat(store.upsert(RailixValue.array(List.of(
                    RailixValue.object(Map.of("_id", RailixValue.string("zeta"), "status", RailixValue.string("open"))),
                    RailixValue.object(Map.of("_id", RailixValue.number(100), "status", RailixValue.string("open"))),
                    RailixValue.object(Map.of("_id", RailixValue.number(2), "status", RailixValue.string("open"))),
                    RailixValue.object(Map.of(
                            "_id", RailixValue.string("alpha"), "status", RailixValue.string("open")
                    )),
                    RailixValue.object(Map.of("_id", RailixValue.number(10), "status", RailixValue.string("open"))),
                    RailixValue.object(Map.of("_id", RailixValue.string("beta"), "status", RailixValue.string("open"))),
                    RailixValue.object(Map.of("_id", RailixValue.number(5), "status", RailixValue.string("open"))),
                    RailixValue.object(Map.of(
                            "_id", RailixValue.string("skip"), "status", RailixValue.string("closed")
                    ))
            )), false).outcome()).isEqualTo("ok");

            final List<RailixValue> streamed = new ArrayList<>();
            RailixValue after = null;
            while (true) {
                final StepResult page = store.find(
                        RailixValue.object(Map.of("status", RailixValue.string("open"))),
                        List.of(),
                        null,
                        2,
                        after
                );
                assertThat(page.outcome()).isEqualTo("ok");
                final List<RailixValue> items = ((RailixValue.ArrayValue) page.writes().get("target")).values();
                if (items.isEmpty()) {
                    break;
                }
                for (final RailixValue item : items) {
                    streamed.add(((RailixValue.ObjectValue) item).values().get("_id"));
                }
                after = streamed.getLast();
            }
            assertThat(streamed).containsExactly(
                    RailixValue.number(2),
                    RailixValue.number(5),
                    RailixValue.number(10),
                    RailixValue.number(100),
                    RailixValue.string("alpha"),
                    RailixValue.string("beta"),
                    RailixValue.string("zeta")
            );

            final StepResult pkRange = store.find(
                    parseJsonObject("""
                            {"_id": {"$gte": 5, "$lte": 100}}"""),
                    List.of(),
                    null,
                    10,
                    RailixValue.number(5)
            );
            assertThat(((RailixValue.ArrayValue) pkRange.writes().get("target")).values())
                    .extracting(item -> ((RailixValue.ObjectValue) item).values().get("_id"))
                    .containsExactly(RailixValue.number(10), RailixValue.number(100));

            final StepResult emptyStringUpper = store.find(
                    parseJsonObject("""
                            {"_id": {"$lt": ""}}"""),
                    List.of(),
                    null,
                    10
            );
            assertThat(emptyStringUpper.outcome()).isEqualTo("ok");
            assertThat(((RailixValue.ArrayValue) emptyStringUpper.writes().get("target")).values()).isEmpty();
        }
    }

    @Test
    void conditionalUpsertAndDeleteEnforceOptimisticConcurrencyAtomically() {
        final Path dataDir = tempDir.resolve("conditional-writes");
        try (StoreHarness store = StoreHarness.open(dataDir, "orders", "_id", List.of("sku"), List.of("status"))) {
            final RailixValue.ObjectValue v1 = parseJsonObject("""
                    {"_id": "ord-1", "sku": "SKU-1", "status": "draft", "version": 1}""");
            final RailixValue insertIfAbsent = parseJsonObject("""
                    {"_id": {"$exists": false}}""");

            assertThat(store.upsert(v1, false, insertIfAbsent).outcome()).isEqualTo("ok");
            assertThat(store.upsert(v1, false, insertIfAbsent).outcome()).isEqualTo("not_found");

            assertThat(store.upsert(
                    parseJsonObject("""
                            {"_id": "ord-missing", "sku": "SKU-X", "status": null}"""),
                    false,
                    RailixValue.object(Map.of())
            ).outcome()).isEqualTo("not_found");
            assertThat(store.upsert(
                    parseJsonObject("""
                            {"_id": "ord-missing", "sku": "SKU-X", "status": "open"}"""),
                    false,
                    parseJsonObject("""
                            {"status": null}""")
            ).outcome()).isEqualTo("not_found");

            final RailixValue.ObjectValue v2 = parseJsonObject("""
                    {"_id": "ord-1", "sku": "SKU-1", "status": "submitted", "version": 2}""");
            assertThat(store.upsert(v2, false, parseJsonObject("""
                    {"version": 1, "status": "draft"}""")).outcome()).isEqualTo("ok");
            assertThat(store.upsert(
                    parseJsonObject("""
                            {"_id": "ord-1", "sku": "SKU-1", "status": "hacked", "version": 99}"""),
                    false,
                    parseJsonObject("""
                            {"version": 1}""")
            ).outcome()).isEqualTo("not_found");
            assertThat(store.get(RailixValue.string("ord-1")).writes().get("target")).isEqualTo(v2);

            final RailixValue createOrV1 = parseJsonObject("""
                    {"$or": [{"_id": {"$exists": false}}, {"version": 1}]}""");
            assertThat(store.upsert(
                    parseJsonObject("""
                            {"_id": "ord-2", "sku": "SKU-2", "status": "draft", "version": 1}"""),
                    false,
                    createOrV1
            ).outcome()).isEqualTo("ok");
            assertThat(store.upsert(
                    parseJsonObject("""
                            {"_id": "ord-2", "sku": "SKU-2", "status": "paid", "version": 2}"""),
                    false,
                    createOrV1
            ).outcome()).isEqualTo("ok");

            final RailixValue batch = RailixValue.array(List.of(
                    parseJsonObject("""
                            {"_id": "ord-1", "sku": "SKU-1", "status": "done", "version": 3}"""),
                    parseJsonObject("""
                            {"_id": "ord-2", "sku": "SKU-2", "status": "done", "version": 3}""")
            ));
            assertThat(store.upsert(batch, false, parseJsonObject("""
                    {"version": 1}""")).outcome()).isEqualTo("not_found");
            assertThat(store.get(RailixValue.string("ord-1")).writes().get("target")).isEqualTo(v2);

            assertThat(store.delete(RailixValue.string("ord-1"), parseJsonObject("""
                    {"status": "draft"}""")).outcome()).isEqualTo("not_found");
            assertThat(store.get(RailixValue.string("ord-1")).outcome()).isEqualTo("ok");
            assertThat(store.delete(RailixValue.string("ord-1"), parseJsonObject("""
                    {"status": "submitted", "version": {"$gte": 2}}""")).outcome()).isEqualTo("ok");
            assertThat(store.get(RailixValue.string("ord-1")).outcome()).isEqualTo("not_found");

            assertThat(store.upsertConfiguredMissingCondition(
                    parseJsonObject("""
                            {"_id": "ord-2", "sku": "SKU-2", "status": "tampered", "version": 99}""")
            ).outcome()).isEqualTo("invalid");
            assertThat(store.delete(RailixValue.string("ord-2"), RailixValue.nullValue()).outcome())
                    .isEqualTo("invalid");
            assertThat(store.upsert(v1, false, parseJsonObject("""
                    {"$bad": 1}""")).outcome()).isEqualTo("invalid");
        }
    }

    @Test
    void primaryKeyRangeWithOutOfBoundsAndCrossTypeAfterCursorsNeverThrows() {
        final Path dataDir = tempDir.resolve("pk-range-after");
        try (StoreHarness store = StoreHarness.open(dataDir, "orders", "_id", List.of(), List.of("status"))) {
            assertThat(store.upsert(RailixValue.array(List.of(
                    parseJsonObject("{\"_id\": 10, \"status\": \"open\"}"),
                    parseJsonObject("{\"_id\": 20, \"status\": \"open\"}"),
                    parseJsonObject("{\"_id\": 30, \"status\": \"closed\"}"),
                    parseJsonObject("{\"_id\": 40, \"status\": \"open\"}"),
                    parseJsonObject("{\"_id\": \"alpha\", \"status\": \"open\"}"),
                    parseJsonObject("{\"_id\": \"beta\", \"status\": \"open\"}"),
                    parseJsonObject("{\"_id\": \"gamma\", \"status\": \"closed\"}")
            )), false).outcome()).isEqualTo("ok");

            final RailixValue.ObjectValue boundedNum = parseJsonObject("{\"_id\": {\"$gte\": 20, \"$lte\": 40}}");
            assertThat(findRawIds(store, boundedNum, RailixValue.number(5)))
                    .containsExactly(RailixValue.number(20), RailixValue.number(30), RailixValue.number(40));
            assertThat(findRawIds(store, boundedNum, RailixValue.number(10)))
                    .containsExactly(RailixValue.number(20), RailixValue.number(30), RailixValue.number(40));
            assertThat(findRawIds(store, boundedNum, RailixValue.number(20)))
                    .containsExactly(RailixValue.number(30), RailixValue.number(40));
            assertThat(findRawIds(store, boundedNum, RailixValue.number(40))).isEmpty();
            assertThat(findRawIds(store, boundedNum, RailixValue.number(50))).isEmpty();
            assertThat(findRawIds(store, boundedNum, RailixValue.string("alpha"))).isEmpty();

            final RailixValue.ObjectValue halfOpenNum = parseJsonObject("{\"_id\": {\"$gte\": 20, \"$lt\": 40}}");
            assertThat(findRawIds(store, halfOpenNum, RailixValue.number(40))).isEmpty();

            final RailixValue.ObjectValue upperOnlyNum = parseJsonObject("{\"_id\": {\"$lte\": 30}}");
            assertThat(findRawIds(store, upperOnlyNum, RailixValue.number(5)))
                    .containsExactly(RailixValue.number(10), RailixValue.number(20), RailixValue.number(30));
            assertThat(findRawIds(store, upperOnlyNum, RailixValue.number(30))).isEmpty();
            assertThat(findRawIds(store, upperOnlyNum, RailixValue.number(99))).isEmpty();
            assertThat(findRawIds(store, upperOnlyNum, RailixValue.string("alpha"))).isEmpty();

            final RailixValue.ObjectValue lowerOnlyNum = parseJsonObject("{\"_id\": {\"$gt\": 20}}");
            assertThat(findRawIds(store, lowerOnlyNum, RailixValue.number(10)))
                    .containsExactly(RailixValue.number(30), RailixValue.number(40));
            assertThat(findRawIds(store, lowerOnlyNum, RailixValue.number(30)))
                    .containsExactly(RailixValue.number(40));
            assertThat(findRawIds(store, lowerOnlyNum, RailixValue.string("alpha"))).isEmpty();

            final RailixValue.ObjectValue stringRange =
                    parseJsonObject("{\"_id\": {\"$gte\": \"beta\", \"$lte\": \"gamma\"}}");
            assertThat(findRawIds(store, stringRange, RailixValue.number(10)))
                    .containsExactly(RailixValue.string("beta"), RailixValue.string("gamma"));
            assertThat(findRawIds(store, stringRange, RailixValue.string("alpha")))
                    .containsExactly(RailixValue.string("beta"), RailixValue.string("gamma"));
            assertThat(findRawIds(store, stringRange, RailixValue.string("beta")))
                    .containsExactly(RailixValue.string("gamma"));
            assertThat(findRawIds(store, stringRange, RailixValue.string("zeta"))).isEmpty();

            final RailixValue.ObjectValue orCombined = parseJsonObject(
                    "{\"$or\": [{\"_id\": {\"$gte\": 20, \"$lte\": 30}}, {\"status\": \"open\"}]}"
            );
            assertThat(findRawIds(store, orCombined, RailixValue.number(10))).containsExactly(
                    RailixValue.number(20), RailixValue.number(30), RailixValue.number(40),
                    RailixValue.string("alpha"), RailixValue.string("beta")
            );
            assertThat(findRawIds(store, orCombined, RailixValue.number(50)))
                    .containsExactly(RailixValue.string("alpha"), RailixValue.string("beta"));

            final RailixValue.ObjectValue andCombined = parseJsonObject(
                    "{\"$and\": [{\"_id\": {\"$gte\": 20, \"$lte\": 40}}, {\"status\": \"open\"}]}"
            );
            assertThat(findRawIds(store, andCombined, RailixValue.number(5)))
                    .containsExactly(RailixValue.number(20), RailixValue.number(40));
            assertThat(findRawIds(store, andCombined, RailixValue.number(99))).isEmpty();
        }
    }

    @Test
    void readViewPreservesStoredValuesOverDefaultsWhenFieldExcludedByProjection() {
        final Path dataDir = tempDir.resolve("read-view-defaults-projection");
        try (StoreHarness store = StoreHarness.open(dataDir, "items", "_id")) {
            assertThat(store.upsert(parseJsonObject(
                    "{\"_id\": \"p-1\", \"code\": \"A\", \"discount\": 20, \"note\": null, \"secret\": \"xyz\"}"
            ), false).outcome()).isEqualTo("ok");

            final RailixValue.ObjectValue defaults =
                    parseJsonObject("{\"discount\": 5, \"note\": \"n/a\", \"currency\": \"USD\"}");
            final RailixValue.ObjectValue expected = parseJsonObject(
                    "{\"_id\": \"p-1\", \"code\": \"A\", \"discount\": 20, \"note\": \"n/a\", \"currency\": \"USD\"}"
            );

            final StepResult got = store.get(RailixValue.string("p-1"), List.of("code"), defaults);
            assertThat(got.outcome()).isEqualTo("ok");
            assertThat(got.writes().get("target")).isEqualTo(expected);

            final StepResult found = store.find(
                    parseJsonObject("{\"_id\": \"p-1\"}"),
                    List.of("code"),
                    defaults,
                    10
            );
            assertThat(found.outcome()).isEqualTo("ok");
            assertThat(((RailixValue.ArrayValue) found.writes().get("target")).values())
                    .containsExactly(expected);
        }
    }

    @Test
    void filterBoundaryLimitsEmptySetsEmptyStringsAndDeletedAfterCursors() {
        final Path dataDir = tempDir.resolve("filter-boundaries");
        try (StoreHarness store = StoreHarness.open(dataDir, "edges", "_id", List.of(), List.of("tag"))) {
            assertThat(store.upsert(RailixValue.array(List.of(
                    parseJsonObject("{\"_id\": 0, \"tag\": 0}"),
                    parseJsonObject("{\"_id\": 1, \"tag\": \"\", \"items\": [{\"sku\": \"S0\"}, {\"sku\": \"S1\"}]}"),
                    parseJsonObject("{\"_id\": 2, \"tag\": \"alpha\", \"items\": [{\"sku\": \"S0\"}]}"),
                    parseJsonObject("{\"_id\": 3, \"tag\": \"beta\"}"),
                    parseJsonObject("{\"_id\": \"s-1\", \"tag\": \"alpha\"}")
            )), false).outcome()).isEqualTo("ok");

            final List<RailixValue> emptyClauses = new ArrayList<>(130);
            for (int i = 0; i < 130; i++) {
                emptyClauses.add(RailixValue.object(Map.of()));
            }
            assertThat(store.find(
                    RailixValue.object(Map.of("$and", RailixValue.array(emptyClauses))),
                    List.of(), null, 10
            ).outcome()).isEqualTo("invalid");

            final Map<String, RailixValue> exactClauses = new LinkedHashMap<>();
            for (int i = 0; i < 128; i++) {
                exactClauses.put("f" + i, RailixValue.number(0));
            }
            assertThat(store.find(RailixValue.object(exactClauses), List.of(), null, 10).outcome()).isEqualTo("ok");
            exactClauses.put("f128", RailixValue.number(0));
            assertThat(store.find(RailixValue.object(exactClauses), List.of(), null, 10).outcome())
                    .isEqualTo("invalid");

            final List<RailixValue> maxArray = new ArrayList<>(1_000);
            for (int i = 0; i < 1_000; i++) {
                maxArray.add(RailixValue.number(i));
            }
            final RailixValue.ArrayValue atLimit = RailixValue.array(maxArray);
            maxArray.add(RailixValue.number(1_000));
            final RailixValue.ArrayValue overLimit = RailixValue.array(maxArray);
            assertThat(store.find(
                    RailixValue.object(Map.of("_id", RailixValue.object(Map.of("$in", atLimit)))),
                    List.of(), null, 10
            ).outcome()).isEqualTo("ok");
            assertThat(store.find(
                    RailixValue.object(Map.of("tag", RailixValue.object(Map.of("$nin", atLimit)))),
                    List.of(), null, 10
            ).outcome()).isEqualTo("ok");
            assertThat(store.find(
                    RailixValue.object(Map.of("_id", RailixValue.object(Map.of("$in", overLimit)))),
                    List.of(), null, 10
            ).outcome()).isEqualTo("invalid");
            assertThat(store.find(
                    RailixValue.object(Map.of("tag", RailixValue.object(Map.of("$nin", overLimit)))),
                    List.of(), null, 10
            ).outcome()).isEqualTo("invalid");

            final String path16 = String.join(".", java.util.Collections.nCopies(16, "a"));
            final String path17 = path16 + ".a";
            assertThat(store.find(RailixValue.object(Map.of(path16, RailixValue.number(1))), List.of(), null, 10)
                    .outcome()).isEqualTo("ok");
            assertThat(store.find(RailixValue.object(Map.of(path17, RailixValue.number(1))), List.of(), null, 10)
                    .outcome()).isEqualTo("invalid");

            final List<RailixValue> allIds = List.of(
                    RailixValue.number(0), RailixValue.number(1), RailixValue.number(2),
                    RailixValue.number(3), RailixValue.string("s-1")
            );
            final RailixValue emptyIn = RailixValue.object(Map.of("$in", RailixValue.array(List.of())));
            final RailixValue emptyNin = RailixValue.object(Map.of("$nin", RailixValue.array(List.of())));
            for (final String field : List.of("tag", "extra", "_id")) {
                assertThat(findRawIds(store, RailixValue.object(Map.of(field, emptyIn)), null)).isEmpty();
                assertThat(findRawIds(store, RailixValue.object(Map.of(field, emptyNin)), null))
                        .containsExactlyElementsOf(allIds);
            }

            assertThat(findRawIds(store, parseJsonObject("{\"tag\": {\"$lte\": \"\"}}"), null))
                    .containsExactly(RailixValue.number(1));
            assertThat(findRawIds(store, parseJsonObject("{\"tag\": {\"$gte\": \"\"}}"), null))
                    .containsExactly(RailixValue.number(1), RailixValue.number(2),
                            RailixValue.number(3), RailixValue.string("s-1"));

            assertThat(findRawIds(store, parseJsonObject("{\"items.01.sku\": \"S1\"}"), null)).isEmpty();
            assertThat(findRawIds(store, parseJsonObject("{\"items.1.sku\": \"S1\"}"), null))
                    .containsExactly(RailixValue.number(1));

            assertThat(store.delete(RailixValue.number(2)).outcome()).isEqualTo("ok");
            assertThat(findRawIds(store, RailixValue.object(Map.of()), RailixValue.number(2)))
                    .containsExactly(RailixValue.number(3), RailixValue.string("s-1"));
        }
    }

    @Test
    void batchUpsertUniqueSwapDuplicateCreateConditionAndCompileLimits() {
        final Path dataDir = tempDir.resolve("batch-unique-swap");
        try (StoreHarness store = StoreHarness.open(dataDir, "accounts", "_id", List.of("email"), List.of())) {
            assertThat(store.upsert(RailixValue.array(List.of(
                    parseJsonObject("{\"_id\": \"u1\", \"email\": \"a@x.com\"}"),
                    parseJsonObject("{\"_id\": \"u2\", \"email\": \"b@x.com\"}")
            )), false).outcome()).isEqualTo("ok");

            assertThat(store.upsert(RailixValue.array(List.of(
                    parseJsonObject("{\"_id\": \"u1\", \"email\": \"b@x.com\"}"),
                    parseJsonObject("{\"_id\": \"u2\", \"email\": \"a@x.com\"}")
            )), false).outcome()).isEqualTo("ok");
            assertThat(((RailixValue.ObjectValue) store.get(RailixValue.string("u1")).writes().get("target"))
                    .values().get("email")).isEqualTo(RailixValue.string("b@x.com"));
            assertThat(((RailixValue.ObjectValue) store.get(RailixValue.string("u2")).writes().get("target"))
                    .values().get("email")).isEqualTo(RailixValue.string("a@x.com"));

            final StepResult duplicateCreate = store.upsert(RailixValue.array(List.of(
                    parseJsonObject("{\"_id\": \"dup-id\", \"email\": \"c@x.com\"}"),
                    parseJsonObject("{\"_id\": \"dup-id\", \"email\": \"d@x.com\"}")
            )), false, parseJsonObject("{\"_id\": {\"$exists\": false}}"));
            assertThat(duplicateCreate.outcome()).isEqualTo("not_found");
            assertThat(store.get(RailixValue.string("dup-id")).outcome()).isEqualTo("not_found");
        }

        final List<RailixValue> thirtyThreeFields = new ArrayList<>(33);
        for (int i = 0; i < 33; i++) {
            thirtyThreeFields.add(RailixValue.string("f" + i));
        }
        assertRejectedDiagnostic(
                "\"operation\": {\"option\": \"get\", \"inputs\": {\"fields\": "
                        + RailixJson.write(RailixValue.array(thirtyThreeFields)) + "}}",
                "PROJECT_INPUT_VALIDATION_INVALID"
        );
        assertRejectedDiagnostic(
                "\"operation\": {\"option\": \"find\", \"inputs\": {\"limit\": 0}}",
                "PROJECT_INPUT_RANGE_INVALID"
        );
        assertRejectedDiagnostic(
                "\"operation\": {\"option\": \"find\", \"inputs\": {\"limit\": 1001}}",
                "PROJECT_INPUT_RANGE_INVALID"
        );
        assertRejectedDiagnostic(
                "\"operation\": {\"option\": \"find\", \"inputs\": {\"limit\": 1.5}}",
                "PROJECT_INPUT_VALIDATION_TYPE"
        );
    }

    @Test
    void highContentionVirtualThreadOptimisticCasPreservesEveryIncrement() throws Exception {
        final Path dataDir = tempDir.resolve("cas-contention");
        final int writerCount = 16;
        final int incrementsPerWriter = 10;
        final int readerCount = 4;
        try (StoreHarness store = StoreHarness.open(dataDir, "counters", "_id", List.of(), List.of("tag"))) {
            assertThat(store.upsert(parseJsonObject(
                    "{\"_id\": \"counter\", \"version\": 0, \"tag\": \"hot\"}"
            ), false).outcome()).isEqualTo("ok");

            final AtomicBoolean done = new AtomicBoolean(false);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final List<Future<Void>> readerFutures = new ArrayList<>(readerCount);
                for (int r = 0; r < readerCount; r++) {
                    readerFutures.add(executor.submit(() -> {
                        while (!done.get()) {
                            final StepResult found = store.find(
                                    parseJsonObject("{\"tag\": \"hot\"}"),
                                    List.of(), null, 10
                            );
                            assertThat(found.outcome()).isEqualTo("ok");
                            final List<RailixValue> items =
                                    ((RailixValue.ArrayValue) found.writes().get("target")).values();
                            assertThat(items).hasSize(1);
                        }
                        return null;
                    }));
                }

                final List<Future<Void>> writerFutures = new ArrayList<>(writerCount);
                for (int w = 0; w < writerCount; w++) {
                    writerFutures.add(executor.submit(() -> {
                        int completed = 0;
                        while (completed < incrementsPerWriter) {
                            final StepResult current = store.get(RailixValue.string("counter"));
                            assertThat(current.outcome()).isEqualTo("ok");
                            final int observed = ((RailixValue.NumberValue)
                                    ((RailixValue.ObjectValue) current.writes().get("target"))
                                            .values().get("version")).value().intValueExact();
                            final StepResult attempt = store.upsert(
                                    RailixValue.object(Map.of(
                                            "_id", RailixValue.string("counter"),
                                            "version", RailixValue.number(observed + 1),
                                            "tag", RailixValue.string("hot")
                                    )),
                                    false,
                                    RailixValue.object(Map.of("version", RailixValue.number(observed)))
                            );
                            if ("ok".equals(attempt.outcome())) {
                                completed++;
                            } else {
                                assertThat(attempt.outcome()).isEqualTo("not_found");
                            }
                        }
                        return null;
                    }));
                }

                try {
                    for (final Future<Void> future : writerFutures) {
                        future.get();
                    }
                } finally {
                    done.set(true);
                }
                for (final Future<Void> future : readerFutures) {
                    future.get();
                }
            }

            assertThat(((RailixValue.ObjectValue) store.get(RailixValue.string("counter")).writes().get("target"))
                    .values().get("version")).isEqualTo(RailixValue.number(writerCount * incrementsPerWriter));
        }

        try (StoreHarness reopened = StoreHarness.open(dataDir, "counters", "_id")) {
            assertThat(((RailixValue.ObjectValue) reopened.get(RailixValue.string("counter")).writes().get("target"))
                    .values().get("version")).isEqualTo(RailixValue.number(writerCount * incrementsPerWriter));
        }
    }

    @Test
    void nonCanonicalNumericKeyInJournalFailsWithCorruptedJournalError() throws Exception {
        for (int index = 0; index < 2; index++) {
            final String badRawKey = index == 0 ? "+42" : "42.00";
            final Path dataDir = tempDir.resolve("noncanonical-num-" + index);
            final Path walPath = dataDir.resolve("items/journal.wal");
            try (StoreHarness store = StoreHarness.open(dataDir, "items", "_id")) {
                assertThat(store.upsert(RailixValue.object(Map.of("_id", RailixValue.number(1))), false)
                        .outcome()).isEqualTo("ok");
            }
            appendSyntheticNumericFrame(walPath, badRawKey, "{\"_id\":42}");
            final byte[] damagedBytes = Files.readAllBytes(walPath);
            assertThatThrownBy(() -> StoreHarness.open(dataDir, "items", "_id"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DATABASE_JOURNAL_CORRUPTED");
            assertThat(Files.readAllBytes(walPath)).isEqualTo(damagedBytes);
        }
    }

    @Test
    void interruptedGetAndClosedEmptyBatchEnforceCancellationAndLifecycle() {
        final Path dataDir = tempDir.resolve("interrupt-get-and-closed");
        final StoreHarness store = StoreHarness.open(dataDir, "items", "_id");
        final DatabaseStepHandlers closedInstance = store.instance();
        try {
            assertThat(store.upsert(RailixValue.object(Map.of("_id", RailixValue.string("i-1"))), false).outcome())
                    .isEqualTo("ok");

            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> store.getInterruptibly(RailixValue.string("i-1")))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Thread.interrupted()).isFalse();

            assertThat(store.upsert(RailixValue.object(Map.of("_id", RailixValue.string("i-2"))), false).outcome())
                    .isEqualTo("ok");
            assertThat(store.get(RailixValue.string("i-1")).outcome()).isEqualTo("ok");
        } finally {
            store.close();
        }
        assertThatThrownBy(() -> store.upsertOn(closedInstance, RailixValue.array(List.of()), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already closed");
    }

    @Test
    void crossPlatformDirectoryValidationAndUnicodeSurrogateHandling() {
        for (final String badDir : List.of(
                "data\\..\\escape", "data \\sub", "/", "\\", "C:", "C:/", "C:\\",
                "C:relative", "data/con", "data/nul.txt", "data/\u007F", "\uD800", "a".repeat(513)
        )) {
            assertThat(DatabaseStepHandlers.validStorageDirectory(badDir)).as("dir=%s", badDir).isFalse();
        }
        for (final String goodDir : List.of(
                ".railix/data", "/tmp/railix-data", "C:\\Users\\app\\data", "C:/Users/app/data"
        )) {
            assertThat(DatabaseStepHandlers.validStorageDirectory(goodDir)).as("dir=%s", goodDir).isTrue();
        }

        final Path dataDir = tempDir.resolve("unicode-and-key-bounds");
        final String emojiKey = "\uD83D\uDE80-order";
        final RailixValue.ObjectValue emojiDoc = RailixValue.object(Map.of(
                "_id", RailixValue.string(emojiKey),
                "label", RailixValue.string("launch-\uD83D\uDE80")
        ));
        try (StoreHarness store = StoreHarness.open(dataDir, "orders", "_id")) {
            assertThat(store.upsert(emojiDoc, false).outcome()).isEqualTo("ok");
            assertThat(store.get(RailixValue.string(emojiKey)).writes().get("target")).isEqualTo(emojiDoc);

            for (final String broken : List.of("\uD800", "\uDC00", "\uD800a")) {
                final RailixValue brokenStr = RailixValue.string(broken);
                assertThat(store.get(brokenStr).outcome()).isEqualTo("invalid");
                assertThat(store.delete(brokenStr).outcome()).isEqualTo("invalid");
                assertThat(store.upsert(RailixValue.object(Map.of("_id", brokenStr)), false).outcome())
                        .isEqualTo("invalid");
                assertThat(store.upsert(RailixValue.object(Map.of(
                        "_id", RailixValue.string("good"),
                        "bad", brokenStr
                )), false).outcome()).isEqualTo("invalid");
                assertThat(store.find(RailixValue.object(Map.of("bad", brokenStr)), List.of(), null, 10)
                        .outcome()).isEqualTo("invalid");
            }

            final BigDecimal minus1024Digits = new BigDecimal(
                    "-" + "9".repeat(RailixData.MAX_CANONICAL_NUMBER_CHARACTERS)
            );
            final RailixValue key1025Bytes = RailixValue.number(minus1024Digits);
            assertThat(store.upsert(RailixValue.object(Map.of("_id", key1025Bytes)), false).outcome())
                    .isEqualTo("invalid");
            assertThat(store.get(key1025Bytes).outcome()).isEqualTo("invalid");

            final RailixValue oversizedNum = RailixValue.number(new BigDecimal("1E+2000"));
            assertThat(store.upsert(RailixValue.object(Map.of("_id", oversizedNum)), false).outcome())
                    .isEqualTo("invalid");
            assertThat(store.get(oversizedNum).outcome()).isEqualTo("invalid");
        }
        try (StoreHarness reopened = StoreHarness.open(dataDir, "orders", "_id")) {
            assertThat(reopened.get(RailixValue.string(emojiKey)).writes().get("target")).isEqualTo(emojiDoc);
        }
    }

    @Test
    void booleanAndMixedTypeSecondaryIndexesAndStartupUniqueValidation() {
        final Path dataDir = tempDir.resolve("mixed-type-indexes");
        try (StoreHarness store = StoreHarness.open(
                dataDir, "mixed", "_id", List.of("email"), List.of("active", "mixed")
        )) {
            final Map<String, RailixValue> doc1 = new LinkedHashMap<>();
            doc1.put("_id", RailixValue.number(1));
            doc1.put("active", RailixValue.bool(true));
            doc1.put("mixed", RailixValue.bool(false));
            doc1.put("email", RailixValue.nullValue());

            final Map<String, RailixValue> doc7 = new LinkedHashMap<>();
            doc7.put("_id", RailixValue.number(7));
            doc7.put("active", RailixValue.bool(true));
            doc7.put("mixed", RailixValue.nullValue());

            assertThat(store.upsert(RailixValue.array(List.of(
                    RailixValue.object(doc1),
                    parseJsonObject("{\"_id\": 2, \"active\": false, \"mixed\": true}"),
                    parseJsonObject("{\"_id\": 3, \"active\": true, \"mixed\": 0, \"email\": \"a@x.com\"}"),
                    parseJsonObject("{\"_id\": 4, \"active\": false, \"mixed\": 1}"),
                    parseJsonObject("{\"_id\": 5, \"active\": true, \"mixed\": \"\"}"),
                    parseJsonObject("{\"_id\": 6, \"active\": false, \"mixed\": \"a\"}"),
                    RailixValue.object(doc7)
            )), false).outcome()).isEqualTo("ok");

            assertThat(findIds(store, parseJsonObject("{\"active\": true}"), null)).containsExactly(1, 3, 5, 7);
            assertThat(findIds(store, parseJsonObject("{\"active\": false}"), null)).containsExactly(2, 4, 6);
            assertThat(findIds(store, parseJsonObject("{\"active\": {\"$in\": [true, false]}}"), null))
                    .containsExactly(1, 2, 3, 4, 5, 6, 7);
            assertThat(findIds(store, parseJsonObject("{\"mixed\": {\"$lte\": 0}}"), null)).containsExactly(3);
            assertThat(findIds(store, parseJsonObject("{\"mixed\": {\"$gte\": 0}}"), null)).containsExactly(3, 4);
            assertThat(findIds(store, parseJsonObject("{\"mixed\": {\"$in\": [\"a\", null]}}"), null))
                    .containsExactly(6, 7);

            assertThat(store.upsert(parseJsonObject("{\"_id\": 8, \"email\": [\"x\"]}"), false).outcome())
                    .isEqualTo("invalid");
            assertThat(store.upsert(parseJsonObject("{\"_id\": 8, \"email\": {\"a\": 1}}"), false).outcome())
                    .isEqualTo("invalid");
        }

        final Path dupDir = tempDir.resolve("startup-unique-dup");
        try (StoreHarness seed = StoreHarness.open(dupDir, "dup", "_id")) {
            assertThat(seed.upsert(RailixValue.array(List.of(
                    parseJsonObject("{\"_id\": 1, \"email\": \"same@x.com\"}"),
                    parseJsonObject("{\"_id\": 2, \"email\": \"same@x.com\"}")
            )), false).outcome()).isEqualTo("ok");
        }
        assertThatThrownBy(() -> StoreHarness.open(dupDir, "dup", "_id", List.of("email"), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATABASE_UNIQUE_INDEX_VIOLATION");

        final Path arrDir = tempDir.resolve("startup-unique-array");
        try (StoreHarness seed = StoreHarness.open(arrDir, "arr", "_id")) {
            assertThat(seed.upsert(parseJsonObject("{\"_id\": 1, \"email\": [\"x\"]}"), false).outcome())
                    .isEqualTo("ok");
        }
        assertThatThrownBy(() -> StoreHarness.open(arrDir, "arr", "_id", List.of("email"), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATABASE_UNIQUE_INDEX_VIOLATION");
    }

    @Test
    void nestedArraysCanonicalEqualityRangeTighteningPrimaryStringBoundsAndCanonicalDefaults() {
        final Path dataDir = tempDir.resolve("query-edge-cases");
        final List<RailixValue> elevenItems = new ArrayList<>(11);
        for (int i = 0; i < 11; i++) {
            elevenItems.add(RailixValue.object(Map.of("sku", RailixValue.string("s" + i))));
        }
        try (StoreHarness store = StoreHarness.open(dataDir, "nested", "_id", List.of(), List.of("total"))) {
            assertThat(store.upsert(RailixValue.array(List.of(
                    RailixValue.object(Map.of(
                            "_id", RailixValue.number(1),
                            "total", RailixValue.number(100),
                            "status", RailixValue.string("ok"),
                            "arr", RailixValue.array(List.of(
                                    RailixValue.number(10),
                                    RailixValue.object(Map.of("x", RailixValue.number(new BigDecimal("2.5"))))
                            )),
                            "items", RailixValue.array(elevenItems)
                    )),
                    parseJsonObject("{\"_id\": 2, \"total\": 200, \"arr\": [20]}"),
                    parseJsonObject("{\"_id\": \"alpha\", \"total\": 150}"),
                    parseJsonObject("{\"_id\": \"beta\", \"total\": 180}"),
                    parseJsonObject("{\"_id\": \"gamma\", \"total\": 250}")
            )), false).outcome()).isEqualTo("ok");

            assertThat(findRawIds(store, parseJsonObject("{\"items.10.sku\": \"s10\"}"), null))
                    .containsExactly(RailixValue.number(1));
            for (final String badPath : List.of("items.10a.sku", "items.99999999.sku", "status.nested")) {
                assertThat(findRawIds(store, RailixValue.object(Map.of(badPath, RailixValue.string("s10"))), null))
                        .isEmpty();
            }

            assertThat(findRawIds(store, parseJsonObject("{\"arr\": [10.00, {\"x\": 2.50}]}"), null))
                    .containsExactly(RailixValue.number(1));
            assertThat(findRawIds(store, parseJsonObject("{\"arr\": {\"$ne\": [10.00, {\"x\": 2.50}]}}"), null))
                    .containsExactly(
                            RailixValue.number(2), RailixValue.string("alpha"),
                            RailixValue.string("beta"), RailixValue.string("gamma")
                    );

            assertThat(findRawIds(store, parseJsonObject("{\"total\": {\"$gte\": 100, \"$gt\": 100}}"), null))
                    .containsExactly(
                            RailixValue.number(2), RailixValue.string("alpha"),
                            RailixValue.string("beta"), RailixValue.string("gamma")
                    );
            assertThat(findRawIds(store, parseJsonObject("{\"total\": {\"$gt\": 100, \"$gte\": 100}}"), null))
                    .containsExactly(
                            RailixValue.number(2), RailixValue.string("alpha"),
                            RailixValue.string("beta"), RailixValue.string("gamma")
                    );
            assertThat(findRawIds(store, parseJsonObject("{\"total\": {\"$lte\": 200, \"$lt\": 200}}"), null))
                    .containsExactly(RailixValue.number(1), RailixValue.string("alpha"), RailixValue.string("beta"));
            assertThat(findRawIds(store, parseJsonObject("{\"total\": {\"$gte\": 100, \"$lte\": 100}}"), null))
                    .containsExactly(RailixValue.number(1));
            assertThat(findRawIds(store, parseJsonObject("{\"total\": {\"$gt\": 100, \"$lte\": 100}}"), null))
                    .isEmpty();
            assertThat(findRawIds(store, parseJsonObject("{\"total\": {\"$gte\": 100, \"$lt\": 100}}"), null))
                    .isEmpty();

            assertThat(findRawIds(store, parseJsonObject("{\"_id\": {\"$gte\": \"beta\"}}"), null))
                    .containsExactly(RailixValue.string("beta"), RailixValue.string("gamma"));
            assertThat(findRawIds(store, parseJsonObject("{\"_id\": {\"$lte\": \"beta\"}}"), RailixValue.number(10)))
                    .containsExactly(RailixValue.string("alpha"), RailixValue.string("beta"));
            assertThat(findRawIds(store, parseJsonObject("{\"_id\": {\"$lt\": \"beta\"}}"), null))
                    .containsExactly(RailixValue.string("alpha"));
            assertThat(findRawIds(store, parseJsonObject("{\"_id\": {\"$gt\": 30, \"$lt\": 10}}"), null)).isEmpty();
            assertThat(findRawIds(store, parseJsonObject("{\"_id\": {\"$exists\": false}}"), null)).isEmpty();
            assertThat(findRawIds(store, parseJsonObject("{\"_id\": \"missing\"}"), null)).isEmpty();
            assertThat(findRawIds(store, parseJsonObject("{\"_id\": null}"), null)).isEmpty();
            assertThat(findRawIds(store, parseJsonObject("{\"$and\": [{\"total\": 9999}, {\"_id\": 1}]}"), null))
                    .isEmpty();

            final RailixValue.ObjectValue rawDefaults = RailixValue.object(Map.of(
                    "extra", RailixValue.number(new BigDecimal("5.00")),
                    "negzero", RailixValue.number(new BigDecimal("-0.00"))
            ));
            final RailixValue.ObjectValue got = (RailixValue.ObjectValue)
                    store.get(RailixValue.number(2), List.of("total"), rawDefaults).writes().get("target");
            assertThat(got.values().get("extra")).isEqualTo(RailixValue.number(5));
            assertThat(got.values().get("negzero")).isEqualTo(RailixValue.number(0));
        }

        final List<RailixValue> thirtyThree = new ArrayList<>(33);
        final Map<String, RailixValue> thirtyThreeDefaults = new LinkedHashMap<>();
        for (int i = 0; i < 33; i++) {
            thirtyThree.add(RailixValue.string("f" + i));
            thirtyThreeDefaults.put("f" + i, RailixValue.number(i));
        }
        assertThat(DatabaseStepHandlers.fieldListRejection(RailixValue.array(List.of(
                RailixValue.string("a"), RailixValue.string("a")
        )))).isPresent();
        assertThat(DatabaseStepHandlers.fieldListRejection(RailixValue.array(thirtyThree))).isPresent();
        assertThat(DatabaseStepHandlers.fieldListRejection(RailixValue.array(List.of(RailixValue.number(1)))))
                .isPresent();
        assertThat(DatabaseStepHandlers.fieldListRejection(RailixValue.array(List.of(RailixValue.string("a.b")))))
                .isPresent();
        assertThat(DatabaseStepHandlers.defaultsRejection(RailixValue.object(thirtyThreeDefaults))).isPresent();
        assertThat(DatabaseStepHandlers.defaultsRejection(RailixValue.object(Map.of("$bad", RailixValue.number(1)))))
                .isPresent();
        assertThat(DatabaseStepHandlers.defaultsRejection(RailixValue.object(Map.of(
                "x", RailixValue.number(new BigDecimal("1E+2000"))
        )))).isPresent();
    }

    @Test
    void journalCrashRecoveryPromotionStartupCompactionAndAllCorruptionVectors() throws Exception {
        final Path promoteDir = tempDir.resolve("promote-compact");
        final Path walFile = promoteDir.resolve("orders/journal.wal");
        final Path compactFile = promoteDir.resolve("orders/journal.wal.compact");
        try (StoreHarness store = StoreHarness.open(promoteDir, "orders", "_id")) {
            assertThat(store.upsert(parseJsonObject("{\"_id\": \"k1\", \"v\": 1}"), false).outcome())
                    .isEqualTo("ok");
        }
        Files.move(walFile, compactFile, StandardCopyOption.REPLACE_EXISTING);
        try (StoreHarness promoted = StoreHarness.open(promoteDir, "orders", "_id")) {
            assertThat(promoted.get(RailixValue.string("k1")).writes().get("target"))
                    .isEqualTo(parseJsonObject("{\"_id\": \"k1\", \"v\": 1}"));
            assertThat(Files.exists(walFile)).isTrue();
            assertThat(Files.exists(compactFile)).isFalse();
        }
        Files.writeString(compactFile, "stale", StandardCharsets.UTF_8);
        try (StoreHarness cleaned = StoreHarness.open(promoteDir, "orders", "_id")) {
            assertThat(Files.exists(compactFile)).isFalse();
            assertThat(cleaned.get(RailixValue.string("k1")).outcome()).isEqualTo("ok");
        }

        final Path compactDir = tempDir.resolve("startup-compact");
        final Path compactWal = compactDir.resolve("orders/journal.wal");
        try (StoreHarness init = StoreHarness.open(compactDir, "orders", "_id")) {
            assertThat(Files.exists(compactWal)).isTrue();
        }
        final byte[] ghostBytes = "ghost".getBytes(StandardCharsets.UTF_8);
        appendSyntheticFrame(
                compactWal,
                DatabaseJournal.OP_DELETE,
                DatabaseJournal.KEY_STRING,
                (short) DatabaseKey.bucketOf(DatabaseJournal.KEY_STRING, "ghost"),
                ghostBytes,
                new byte[0]
        );
        final byte[] hotKeyBytes = "hot-key".getBytes(StandardCharsets.UTF_8);
        final byte[] hotPayload = ("{\"_id\":\"hot-key\",\"pad\":\"" + "x".repeat(480) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        final short hotBucket = (short) DatabaseKey.bucketOf(DatabaseJournal.KEY_STRING, "hot-key");
        for (int i = 0; i < 200; i++) {
            appendSyntheticFrame(
                    compactWal,
                    DatabaseJournal.OP_UPSERT,
                    DatabaseJournal.KEY_STRING,
                    hotBucket,
                    hotKeyBytes,
                    hotPayload
            );
        }
        try (StoreHarness compacted = StoreHarness.open(compactDir, "orders", "_id")) {
            assertThat(compacted.instance().lastCompactionPauseNanos()).isPositive();
            assertThat(compacted.get(RailixValue.string("hot-key")).outcome()).isEqualTo("ok");
            assertThat(compacted.get(RailixValue.string("ghost")).outcome()).isEqualTo("not_found");
            assertThat(Files.size(compactWal)).isLessThan(2_000L);
        }

        final Path seedDir = tempDir.resolve("corruption-seed");
        try (StoreHarness seed = StoreHarness.open(seedDir, "orders", "_id")) {
            assertThat(seed.upsert(parseJsonObject("{\"_id\": \"k\", \"v\": 1}"), false).outcome())
                    .isEqualTo("ok");
        }
        final byte[] baseWal = Files.readAllBytes(seedDir.resolve("orders/journal.wal"));
        final byte[] validKey = "k".getBytes(StandardCharsets.UTF_8);
        final short validBucket = (short) DatabaseKey.bucketOf(DatabaseJournal.KEY_STRING, "k");

        final List<byte[]> corruptedTails = List.of(
                new byte[]{0x00, 0x00, 0x00},
                new byte[]{0x52, 0x44, 0x42, 0x31, 9},
                new byte[]{0x52, 0x44, 0x42, 0x31, 1, 9},
                new byte[]{0x52, 0x44, 0x42, 0x31, 1, 1, 0x04},
                new byte[]{0x52, 0x44, 0x42, 0x31, 1, 1, 0x04, 0x00},
                new byte[]{0x52, 0x44, 0x42, 0x31, 1, 1, 0x00, 0x00, 0, 0, 0, 0},
                new byte[]{0x52, 0x44, 0x42, 0x31, 2, 1, 0x00, 0x00, 0, 0, 0, 1, 0, 0, 0, 4},
                rawHeader(0x1122_3344, (byte) 1, (byte) 1, (short) 0, 1, 2),
                rawHeader(DatabaseStepHandlers.MAGIC, (byte) 9, (byte) 1, (short) 0, 1, 2),
                rawHeader(DatabaseStepHandlers.MAGIC, (byte) 1, (byte) 9, (short) 0, 1, 2),
                rawHeader(DatabaseStepHandlers.MAGIC, (byte) 1, (byte) 1, (short) 1024, 1, 2),
                rawHeader(DatabaseStepHandlers.MAGIC, (byte) 1, (byte) 1, (short) 0, 0, 2),
                rawHeader(DatabaseStepHandlers.MAGIC, (byte) 2, (byte) 1, (short) 0, 1, 4),
                rawHeader(DatabaseStepHandlers.MAGIC, (byte) 1, (byte) 1, (short) 0, 1, 1),
                buildSyntheticFrame(DatabaseJournal.OP_UPSERT, DatabaseJournal.KEY_STRING, (short) 0,
                        new byte[]{(byte) 0xFF, (byte) 0xFE}, "{}".getBytes(StandardCharsets.UTF_8)),
                buildSyntheticFrame(DatabaseJournal.OP_UPSERT, DatabaseJournal.KEY_STRING,
                        (short) ((validBucket + 1) & DatabaseJournal.BUCKET_MASK),
                        validKey, "{}".getBytes(StandardCharsets.UTF_8)),
                buildSyntheticFrame(DatabaseJournal.OP_UPSERT, DatabaseJournal.KEY_STRING, validBucket,
                        validKey, "not-json".getBytes(StandardCharsets.UTF_8)),
                buildSyntheticFrame(DatabaseJournal.OP_UPSERT, DatabaseJournal.KEY_STRING, validBucket,
                        validKey, "{broken}".getBytes(StandardCharsets.UTF_8))
        );
        for (int idx = 0; idx < corruptedTails.size(); idx++) {
            final Path caseDir = tempDir.resolve("corrupt-vec-" + idx);
            final Path walPath = caseDir.resolve("orders/journal.wal");
            Files.createDirectories(walPath.getParent());
            final byte[] damaged = Arrays.copyOf(baseWal, baseWal.length + corruptedTails.get(idx).length);
            System.arraycopy(corruptedTails.get(idx), 0, damaged, baseWal.length, corruptedTails.get(idx).length);
            Files.write(walPath, damaged);
            assertThatThrownBy(() -> StoreHarness.open(caseDir, "orders", "_id"))
                    .as("corruption case %d", idx)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DATABASE_JOURNAL_CORRUPTED");
            assertThat(Files.readAllBytes(walPath)).isEqualTo(damaged);
        }
    }

    @Test
    void oversizedSingleDocumentAndOversizedBatchReturnInvalidAtomically() {
        final Path dataDir = tempDir.resolve("oversized-payloads");
        try (StoreHarness store = StoreHarness.open(dataDir, "blobs", "_id")) {
            final RailixValue.ObjectValue over1MiB = RailixValue.object(Map.of(
                    "_id", RailixValue.string("huge"),
                    "p", RailixValue.string("x".repeat(DatabaseJournal.MAX_PAYLOAD_BYTES))
            ));
            assertThat(store.upsert(over1MiB, false).outcome()).isEqualTo("invalid");
            assertThat(store.get(RailixValue.string("huge")).outcome()).isEqualTo("not_found");

            final String chunk = "x".repeat(950_000);
            final List<RailixValue> over8MiBBatch = new ArrayList<>(9);
            for (int i = 0; i < 9; i++) {
                over8MiBBatch.add(RailixValue.object(Map.of(
                        "_id", RailixValue.string("b-" + i),
                        "p", RailixValue.string(chunk)
                )));
            }
            assertThat(store.upsert(RailixValue.array(over8MiBBatch), false).outcome()).isEqualTo("invalid");
            assertThat(store.get(RailixValue.string("b-0")).outcome()).isEqualTo("not_found");
        }
    }

    @Test
    void primaryKeyRangeIntersectedWithSelectiveSecondaryIndexReturnsExactMatches() {
        final Path dataDir = tempDir.resolve("pk-range-secondary-intersect");
        try (StoreHarness store = StoreHarness.open(dataDir, "events", "_id", List.of(), List.of("status"))) {
            final List<RailixValue> batch = new ArrayList<>(1_500);
            for (int i = 1; i <= 1_500; i++) {
                final String status = (i == 500 || i == 1_200) ? "rare" : "common";
                batch.add(RailixValue.object(Map.of(
                        "_id", RailixValue.number(i),
                        "status", RailixValue.string(status)
                )));
            }
            assertThat(store.upsert(RailixValue.array(batch), false).outcome()).isEqualTo("ok");

            assertThat(findIds(store, parseJsonObject("{\"_id\": {\"$gte\": 1}, \"status\": \"rare\"}"), null))
                    .containsExactly(500, 1_200);
            assertThat(findIds(store, parseJsonObject("{\"_id\": {\"$gte\": 1}, \"status\": \"rare\"}"),
                    RailixValue.number(500))).containsExactly(1_200);
            assertThat(findIds(store,
                    parseJsonObject("{\"_id\": {\"$gte\": 1, \"$lte\": 2000}, \"status\": \"rare\"}"), null))
                    .containsExactly(500, 1_200);
        }
    }

    @Test
    void concurrentReadInterruptStormDoesNotCloseJournalOrBreakBystanderOperations() throws Exception {
        final Path dataDir = tempDir.resolve("interrupt-storm");
        try (StoreHarness store = StoreHarness.open(dataDir, "storm", "_id", List.of(), List.of("group"))) {
            final String pad = "x".repeat(1_024);
            final List<RailixValue> seed = new ArrayList<>(150);
            for (int i = 1; i <= 150; i++) {
                seed.add(RailixValue.object(Map.of(
                        "_id", RailixValue.number(i),
                        "group", RailixValue.string("hot"),
                        "pad", RailixValue.string(pad)
                )));
            }
            assertThat(store.upsert(RailixValue.array(seed), false).outcome()).isEqualTo("ok");

            final AtomicBoolean stop = new AtomicBoolean(false);
            final AtomicReferenceArray<Thread> targets = new AtomicReferenceArray<>(4);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final List<Future<Void>> futures = new ArrayList<>();
                for (int t = 0; t < 4; t++) {
                    final int idx = t;
                    futures.add(executor.submit(() -> {
                        targets.set(idx, Thread.currentThread());
                        try {
                            while (!stop.get()) {
                                try {
                                    final StepResult res = store.findInterruptibly(
                                            parseJsonObject("{\"group\": \"hot\"}"),
                                            150
                                    );
                                    assertThat(res.outcome()).isEqualTo("ok");
                                } catch (final InterruptedException expected) {
                                    Thread.interrupted();
                                }
                            }
                            return null;
                        } finally {
                            targets.set(idx, null);
                        }
                    }));
                }

                for (int b = 0; b < 4; b++) {
                    final int targetId = (b % 150) + 1;
                    futures.add(executor.submit(() -> {
                        while (!stop.get()) {
                            assertThat(store.get(RailixValue.number(targetId)).outcome()).isEqualTo("ok");
                            assertThat(store.find(
                                    parseJsonObject("{\"group\": \"hot\"}"),
                                    List.of(), null, 20
                            ).outcome()).isEqualTo("ok");
                        }
                        return null;
                    }));
                }

                final List<Future<Void>> writers = new ArrayList<>(2);
                for (int w = 0; w < 2; w++) {
                    final int worker = w;
                    writers.add(executor.submit(() -> {
                        for (int step = 0; step < 25; step++) {
                            final int id = 200 + worker * 100 + step;
                            assertThat(store.upsert(RailixValue.object(Map.of(
                                    "_id", RailixValue.number(id),
                                    "group", RailixValue.string("warm"),
                                    "step", RailixValue.number(step)
                            )), false).outcome()).isEqualTo("ok");
                        }
                        return null;
                    }));
                }

                try {
                    for (int round = 0; round < 40; round++) {
                        for (int t = 0; t < targets.length(); t++) {
                            final Thread target = targets.get(t);
                            if (target != null) {
                                target.interrupt();
                            }
                        }
                        Thread.sleep(2);
                    }
                    for (final Future<Void> writer : writers) {
                        writer.get();
                    }
                } finally {
                    stop.set(true);
                }
                for (final Future<Void> future : futures) {
                    future.get();
                }
            }

            assertThat(store.upsert(RailixValue.object(Map.of(
                    "_id", RailixValue.string("post-storm"),
                    "group", RailixValue.string("hot")
            )), false).outcome()).isEqualTo("ok");
            assertThat(store.get(RailixValue.string("post-storm")).outcome()).isEqualTo("ok");
            assertThat(store.find(parseJsonObject("{\"group\": \"hot\"}"), List.of(), null, 200).outcome())
                    .isEqualTo("ok");
            assertThat(store.delete(RailixValue.string("post-storm")).outcome()).isEqualTo("ok");
        }

        try (StoreHarness reopened = StoreHarness.open(dataDir, "storm", "_id")) {
            assertThat(reopened.get(RailixValue.number(200)).outcome()).isEqualTo("ok");
            assertThat(reopened.get(RailixValue.number(324)).outcome()).isEqualTo("ok");
            assertThat(reopened.get(RailixValue.string("post-storm")).outcome()).isEqualTo("not_found");
        }
    }

    @Test
    void interruptedUpsertDeleteAndWhitespaceDataDirPropertyBehaveCleanly() {
        final Path dataDir = tempDir.resolve("interrupt-write-ops");
        try (StoreHarness store = StoreHarness.open(dataDir, "items", "_id")) {
            assertThat(store.upsert(RailixValue.object(Map.of("_id", RailixValue.string("keep-1"))), false)
                    .outcome()).isEqualTo("ok");

            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> store.upsertInterruptibly(
                    RailixValue.object(Map.of("_id", RailixValue.string("aborted"))), false
            )).isInstanceOf(InterruptedException.class);
            assertThat(Thread.interrupted()).isFalse();
            assertThat(store.get(RailixValue.string("aborted")).outcome()).isEqualTo("not_found");

            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> store.deleteInterruptibly(RailixValue.string("keep-1")))
                    .isInstanceOf(InterruptedException.class);
            assertThat(Thread.interrupted()).isFalse();
            assertThat(store.get(RailixValue.string("keep-1")).outcome()).isEqualTo("ok");
        }

        final Path customBase = tempDir.resolve("trimmed-base");
        final String previousProp = System.getProperty("railix.data.dir");
        try {
            System.setProperty("railix.data.dir", "  " + customBase + "  ");
            final Path resolved = DatabaseStepHandlers.resolveCollectionDirectory("store", "orders");
            assertThat(resolved).isEqualTo(customBase.resolve("store/orders").toAbsolutePath().normalize());
        } finally {
            if (previousProp == null) {
                System.clearProperty("railix.data.dir");
            } else {
                System.setProperty("railix.data.dir", previousProp);
            }
        }
    }

    private static byte[] rawHeader(
            final int magic,
            final byte op,
            final byte keyType,
            final short bucket,
            final int keyLength,
            final int payloadLength
    ) {
        final ByteBuffer header = ByteBuffer.allocate(DatabaseStepHandlers.HEADER_BYTES);
        header.putInt(magic);
        header.put(op);
        header.put(keyType);
        header.putShort(bucket);
        header.putInt(keyLength);
        header.putInt(payloadLength);
        header.putInt(0);
        return header.array();
    }

    private static void appendSyntheticNumericFrame(
            final Path walPath,
            final String rawKey,
            final String payloadJson
    ) throws Exception {
        final byte keyType = DatabaseJournal.KEY_NUMBER;
        appendSyntheticFrame(
                walPath,
                DatabaseJournal.OP_UPSERT,
                keyType,
                (short) DatabaseKey.bucketOf(keyType, rawKey),
                rawKey.getBytes(StandardCharsets.UTF_8),
                payloadJson.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void appendSyntheticFrame(
            final Path walPath,
            final byte op,
            final byte keyType,
            final short bucket,
            final byte[] keyBytes,
            final byte[] payloadBytes
    ) throws Exception {
        Files.write(
                walPath,
                buildSyntheticFrame(op, keyType, bucket, keyBytes, payloadBytes),
                StandardOpenOption.APPEND
        );
    }

    private static byte[] buildSyntheticFrame(
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
        crc.update((keyBytes.length >>> 24) & 0xff);
        crc.update((keyBytes.length >>> 16) & 0xff);
        crc.update((keyBytes.length >>> 8) & 0xff);
        crc.update(keyBytes.length & 0xff);
        crc.update((payloadBytes.length >>> 24) & 0xff);
        crc.update((payloadBytes.length >>> 16) & 0xff);
        crc.update((payloadBytes.length >>> 8) & 0xff);
        crc.update(payloadBytes.length & 0xff);
        crc.update(keyBytes, 0, keyBytes.length);
        crc.update(payloadBytes, 0, payloadBytes.length);

        final ByteBuffer frame = ByteBuffer.allocate(
                DatabaseStepHandlers.HEADER_BYTES + keyBytes.length + payloadBytes.length
        );
        frame.putInt(DatabaseStepHandlers.MAGIC);
        frame.put(op);
        frame.put(keyType);
        frame.putShort(bucket);
        frame.putInt(keyBytes.length);
        frame.putInt(payloadBytes.length);
        frame.putInt((int) crc.getValue());
        frame.put(keyBytes);
        frame.put(payloadBytes);
        return frame.array();
    }

    private static void assertRejectedDiagnostic(final String inputsJson, final String diagnosticCode) {
        final String project = """
                {
                  "format": 1,
                  "id": "db-sqa-limits",
                  "nodes": [
                    {"id": "app", "use": "railix.app", "inputs": {}},
                    {
                      "id": "cli",
                      "use": "railix.trigger.cli",
                      "inputs": {},
                      "examples": [{"name": "ex", "payload": []}]
                    },
                    {"id": "db", "use": "railix.database", "inputs": {%s}}
                  ],
                  "links": [
                    {"from": "app.start", "to": "cli"},
                    {"from": "cli.next", "to": "db"},
                    {"from": "db.ok", "to": "end"},
                    {"from": "db.not_found", "to": "end"},
                    {"from": "db.invalid", "to": "end"}
                  ]
                }
                """.formatted(inputsJson);
        final CompileResult result = ProjectCompiler.compileApplication(
                project,
                PrimitiveStepsCreatorProjectE2eTest.catalog()
        );
        assertThat(result).isInstanceOfSatisfying(CompileResult.Rejected.class, rejected ->
                assertThat(rejected.diagnostics()).extracting(Diagnostic::code).contains(diagnosticCode));
    }

    private static List<RailixValue> findRawIds(
            final StoreHarness store,
            final RailixValue.ObjectValue filter,
            final RailixValue after
    ) {
        final StepResult result = store.find(filter, List.of("_id"), null, 100, after);
        assertThat(result.outcome()).isEqualTo("ok");
        return ((RailixValue.ArrayValue) result.writes().get("target")).values().stream()
                .map(item -> ((RailixValue.ObjectValue) item).values().get("_id"))
                .toList();
    }

    private static RailixValue.ObjectValue parseJsonObject(final String json) {
        final RailixJson.Result parsed = RailixJson.parse(json);
        assertThat(parsed).isInstanceOf(RailixJson.Parsed.class);
        return (RailixValue.ObjectValue) ((RailixJson.Parsed) parsed).value();
    }

    private static List<Integer> findIds(
            final StoreHarness store,
            final RailixValue.ObjectValue filter,
            final RailixValue after
    ) {
        final StepResult result = store.find(filter, List.of("_id"), null, 100, after);
        assertThat(result.outcome()).isEqualTo("ok");
        return ((RailixValue.ArrayValue) result.writes().get("target")).values().stream()
                .map(item -> ((RailixValue.NumberValue) ((RailixValue.ObjectValue) item).values().get("_id"))
                        .value().intValueExact())
                .toList();
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
            return upsert(document, autoKey, List.of(), null);
        }

        StepResult upsert(final RailixValue document, final boolean autoKey, final List<String> fields) {
            return upsert(document, autoKey, fields, null);
        }

        StepResult upsert(final RailixValue document, final boolean autoKey, final RailixValue condition) {
            return upsert(document, autoKey, List.of(), condition);
        }

        StepResult upsert(
                final RailixValue document,
                final boolean autoKey,
                final List<String> fields,
                final RailixValue condition
        ) {
            final Map<String, RailixValue> child = new LinkedHashMap<>();
            child.put("auto_key", RailixValue.bool(autoKey));
            if (!fields.isEmpty()) {
                child.put("fields", RailixValue.array(fields.stream().<RailixValue>map(RailixValue::string).toList()));
            }
            if (document != null) {
                child.put("document", document);
            }
            if (condition != null) {
                child.put("condition", condition);
            }
            return invoke("upsert", child);
        }

        StepResult upsertConfiguredMissingCondition(final RailixValue document) {
            final Map<String, RailixValue> configuredOp = new LinkedHashMap<>();
            configuredOp.put("auto_key", RailixValue.bool(false));
            configuredOp.put("condition", RailixValue.array(List.of(
                    RailixValue.string("context"),
                    RailixValue.string("payload"),
                    RailixValue.string("expected")
            )));
            final StepInput guardedConfig = new StepInput(
                    configuration.values(),
                    Map.of("operation", "upsert"),
                    Map.of(),
                    Map.of("operation", new StepInput(configuredOp, Map.of(), Map.of(), Map.of(), "ok")),
                    "ok"
            );
            final StepInput selected = new StepInput(
                    Map.of("auto_key", RailixValue.bool(false), "document", document),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    "ok"
            );
            final StepInput invocation = StepInput.invocation(
                    Map.of(),
                    Map.of("operation", "upsert"),
                    Map.of(),
                    Map.of("operation", selected),
                    "ok",
                    resources
            );
            return executeProcess(guardedConfig, invocation);
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
            return delete(key, null);
        }

        StepResult delete(final RailixValue key, final RailixValue condition) {
            final Map<String, RailixValue> child = new LinkedHashMap<>();
            if (key != null) {
                child.put("key", key);
            }
            if (condition != null) {
                child.put("condition", condition);
            }
            return invoke("delete", child);
        }

        StepResult getInterruptibly(final RailixValue key) throws InterruptedException {
            final Map<String, RailixValue> child = new LinkedHashMap<>();
            if (key != null) {
                child.put("key", key);
            }
            return DatabaseStepHandlers.process(configuration, buildInvocation("get", child));
        }

        StepResult upsertOn(
                final DatabaseStepHandlers targetStore,
                final RailixValue document,
                final boolean autoKey
        ) {
            final StepInput.Resources directResources = new StepInput.Resources() {
                @Override
                public <T> T get(final String name, final Class<T> type) {
                    return type.cast(targetStore);
                }

                @Override
                public <T> T acquire(
                        final String name,
                        final Class<T> type,
                        final Object sharingKey,
                        final Supplier<? extends T> factory,
                        final Consumer<? super T> close
                ) {
                    throw new UnsupportedOperationException();
                }
            };
            final StepInput selected = new StepInput(
                    Map.of("auto_key", RailixValue.bool(autoKey), "document", document),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    "ok"
            );
            final StepInput invocation = StepInput.invocation(
                    Map.of(),
                    Map.of("operation", "upsert"),
                    Map.of(),
                    Map.of("operation", selected),
                    "ok",
                    directResources
            );
            return executeProcess(configuration, invocation);
        }

        StepResult findInterruptibly(final RailixValue filter, final int limit) throws InterruptedException {
            final Map<String, RailixValue> child = new LinkedHashMap<>();
            if (filter != null) {
                child.put("filter", filter);
            }
            child.put("limit", RailixValue.number(limit));
            return DatabaseStepHandlers.process(configuration, buildInvocation("find", child));
        }

        StepResult upsertInterruptibly(final RailixValue document, final boolean autoKey)
                throws InterruptedException {
            final Map<String, RailixValue> child = new LinkedHashMap<>();
            child.put("auto_key", RailixValue.bool(autoKey));
            if (document != null) {
                child.put("document", document);
            }
            return DatabaseStepHandlers.process(configuration, buildInvocation("upsert", child));
        }

        StepResult deleteInterruptibly(final RailixValue key) throws InterruptedException {
            final Map<String, RailixValue> child = new LinkedHashMap<>();
            if (key != null) {
                child.put("key", key);
            }
            return DatabaseStepHandlers.process(configuration, buildInvocation("delete", child));
        }

        private StepResult invoke(final String operation, final Map<String, RailixValue> selectedValues) {
            return executeProcess(configuration, buildInvocation(operation, selectedValues));
        }

        private StepInput buildInvocation(final String operation, final Map<String, RailixValue> selectedValues) {
            final StepInput selected = new StepInput(selectedValues, Map.of(), Map.of(), Map.of(), "ok");
            return StepInput.invocation(
                    Map.of(),
                    Map.of("operation", operation),
                    Map.of(),
                    Map.of("operation", selected),
                    "ok",
                    resources
            );
        }

        private static StepResult executeProcess(final StepInput config, final StepInput invocation) {
            try {
                return DatabaseStepHandlers.process(config, invocation);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Unexpected interruption during test invocation.", interrupted);
            }
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
