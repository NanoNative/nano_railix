package dev.nanonative.railix.core;

import dev.nanonative.railix.core.fixtures.FlowFixtures;
import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepDependencyLockCompilationTest {
    private static final String FLOW_DIGEST =
            "45989f398eba1a81277c34abefaa8d0c29a865069f891438ade3df9c4a577ede";
    private static final String CONTRACT_DIGEST =
            "edfde04fb0151f6e5534bdd6880df0447aaa11b48bef696bbf9e642119728c61";
    private static final String INVALID_UNICODE = Character.toString(Character.MIN_HIGH_SURROGATE);
    private static final String LOCK = "{\"flow\":\"sha256:"
            + FLOW_DIGEST + "\","
            + "\"format\":1,\"steps\":[{\"contract\":\"sha256:"
            + CONTRACT_DIGEST + "\","
            + "\"id\":\"text.lowercase\",\"version\":\"1.0.0\"}]}\n";

    @Test
    void compilerDerivesCanonicalLockFromTheReferencedStepContract() {
        final CompileResult result = FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(lowercase("1.0.0"))
        );

        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        assertThat(((CompileResult.Compiled) result).lock()).isEqualTo(LOCK);
    }

    @Test
    void compilerRejectsAReferencedStepWithoutAVersion() {
        assertThat(FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(StepDefinition.named("text.lowercase", "")
                        .input("text", ValueShape.string())
                        .output("text", ValueShape.string())
                        .outcome("ok")
                        .run(input -> StepResult.outcome("ok")))
        )).isEqualTo(new CompileResult.Rejected(List.of(Diagnostic.atPath(
                "STEP_VERSION_REQUIRED",
                "Step version must be non-blank.",
                "catalog[0].version"
        ))));
    }

    @Test
    void compilerIgnoresUnreferencedCatalogEntries() {
        final CompileResult result = FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(lowercase("1.0.0"), StepDefinition.named("unused", "1.0.0")
                        .outcome("ok")
                        .run(input -> StepResult.outcome("ok")))
        );

        assertThat(((CompileResult.Compiled) result).lock()).isEqualTo(LOCK);
    }

    @Test
    void compilerLocksRepeatedInvocationsOfOneDependencyOnce() {
        final String repeated = """
                {
                  "id": "repeat",
                  "triggers": [],
                  "entry": "first",
                  "inputs": {"text": "string"},
                  "outputs": {"text": "string"},
                  "steps": [
                    {"id": "first", "use": "text.lowercase", "config": {}, "on": {"ok": "second"}},
                    {"id": "second", "use": "text.lowercase", "config": {}, "on": {"ok": "end"}}
                  ],
                  "connections": [
                    {"from": "input.text", "to": "first.text"},
                    {"from": "first.text", "to": "second.text"},
                    {"from": "second.text", "to": "output.text"}
                  ]
                }
                """;

        final CompileResult result = FlowCompiler.compile(repeated, StepCatalog.of(lowercase("1.0.0")));

        assertThat(((CompileResult.Compiled) result).lock())
                .containsOnlyOnce("\"id\":\"text.lowercase\"");
    }

    @Test
    void compilerAcceptsTheExactCanonicalLock() {
        final CompileResult result = FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(lowercase("1.0.0")),
                LOCK
        );

        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        assertThat(((CompileResult.Compiled) result).lock()).isEqualTo(LOCK);
    }

    @Test
    void compilerRejectsAMissingDependencyLock() {
        assertLockDiagnostic(
                null,
                Diagnostic.atPath(
                        "STEP_LOCK_REQUIRED",
                        "A Step dependency lock is required.",
                        "lock"
                )
        );
    }

    @Test
    void compilerRejectsMalformedDependencyLockJson() {
        assertLockDiagnostic("{", new Diagnostic(
                "STEP_LOCK_JSON_INVALID",
                "Expected an object field name.",
                "$",
                1,
                2
        ));
    }

    @Test
    void dependencyLockValidationPreservesFlowCompilationDiagnostics() {
        assertThat(FlowCompiler.compile(
                "{",
                StepCatalog.of(lowercase("1.0.0")),
                LOCK
        )).isEqualTo(new CompileResult.Rejected(List.of(new Diagnostic(
                "FLOW_JSON_INVALID",
                "Expected an object field name.",
                "$",
                1,
                2
        ))));
    }

    @Test
    void compilerRejectsAnUnsupportedDependencyLockFormat() {
        assertLockDiagnostic(
                LOCK.replace("\"format\":1", "\"format\":2"),
                Diagnostic.atPath(
                        "STEP_LOCK_FORMAT_UNSUPPORTED",
                        "Unsupported Step dependency lock format: 2.",
                        "format"
                )
        );
    }

    @Test
    void compilerRejectsALockForAnotherAuthoredFlow() {
        final String changedFlow = FlowFixtures.lowercaseFlow()
                .replace("\"id\": \"lowercase-app\"", "\"id\": \"changed-app\"");

        assertThat(FlowCompiler.compile(
                changedFlow,
                StepCatalog.of(lowercase("1.0.0")),
                LOCK
        )).isEqualTo(rejected(Diagnostic.atPath(
                "STEP_LOCK_FLOW_MISMATCH",
                "Step dependency lock does not match the authored flow.",
                "flow"
        )));
    }

    @Test
    void compilerRejectsADependencyMissingFromTheLock() {
        assertLockDiagnostic(
                LOCK.replaceFirst("\\{\\\"contract\\\"[^}]+}", ""),
                Diagnostic.atPath(
                        "STEP_LOCK_DEPENDENCY_MISSING",
                        "Step dependency is missing from the lock: text.lowercase",
                        "steps.text.lowercase"
                )
        );
    }

    @Test
    void compilerRejectsAnUnexpectedDependencyInTheLock() {
        final String unexpected = LOCK.replace(
                "]}\n",
                ",{\"contract\":\"sha256:0000000000000000000000000000000000000000000000000000000000000000\","
                        + "\"id\":\"unexpected\","
                        + "\"version\":\"1.0.0\"}]}\n"
        );

        assertLockDiagnostic(unexpected, Diagnostic.atPath(
                "STEP_LOCK_DEPENDENCY_UNEXPECTED",
                "Lock contains an unreferenced Step dependency: unexpected",
                "steps.unexpected"
        ));
    }

    @Test
    void compilerRejectsAChangedStepVersion() {
        assertLockDiagnostic(
                LOCK.replace("\"version\":\"1.0.0\"", "\"version\":\"2.0.0\""),
                Diagnostic.atPath(
                        "STEP_LOCK_VERSION_MISMATCH",
                        "Locked Step version does not match: text.lowercase",
                        "steps.text.lowercase.version"
                )
        );
    }

    @Test
    void compilerRejectsAChangedStepContract() {
        assertLockDiagnostic(
                LOCK.replace("sha256:edfd", "sha256:0000"),
                Diagnostic.atPath(
                        "STEP_LOCK_CONTRACT_MISMATCH",
                        "Locked Step contract does not match: text.lowercase",
                        "steps.text.lowercase.contract"
                )
        );
    }

    @Test
    void compilerRejectsANonCanonicalDependencyLock() {
        assertLockDiagnostic(LOCK.strip(), Diagnostic.atPath(
                "STEP_LOCK_NON_CANONICAL",
                "Step dependency lock must use canonical JSON with one final newline.",
                "$"
        ));
    }

    @Test
    void compilerRejectsAMalformedFlowDigest() {
        assertInvalid(LOCK.replace(
                "sha256:" + FLOW_DIGEST,
                "sha256:not-a-digest"
        ), "flow");
    }

    @Test
    void compilerRejectsAMalformedContractDigest() {
        assertInvalid(LOCK.replace(
                "sha256:" + CONTRACT_DIGEST,
                "SHA256:" + CONTRACT_DIGEST
        ), "steps[0].contract");
    }

    @Test
    void compilerRejectsUppercaseContractDigestHex() {
        assertInvalid(LOCK.replace(
                "sha256:" + CONTRACT_DIGEST,
                "sha256:" + CONTRACT_DIGEST.toUpperCase()
        ), "steps[0].contract");
    }

    @Test
    void compilerRejectsABlankDependencyLock() {
        assertLockDiagnostic(" \n", Diagnostic.atPath(
                "STEP_LOCK_REQUIRED",
                "A Step dependency lock is required.",
                "lock"
        ));
    }

    @Test
    void compilerRejectsADependencyLockOverTheCharacterLimit() {
        assertLockDiagnostic(
                " ".repeat(RailixData.MAX_SOURCE_BYTES + 1),
                oversizedLock()
        );
    }

    @Test
    void compilerRejectsADependencyLockOverTheUtf8ByteLimit() {
        assertLockDiagnostic(
                "ä".repeat(RailixData.MAX_SOURCE_BYTES / 2 + 1),
                oversizedLock()
        );
    }

    @Test
    void compilerRejectsANonObjectDependencyLock() {
        assertInvalid("[]\n", "$");
    }

    @Test
    void compilerRejectsAMissingDependencyLockRootField() {
        assertInvalid(LOCK.replace("\"format\":1,", ""), "$");
    }

    @Test
    void compilerRejectsAnUnknownDependencyLockRootField() {
        assertInvalid(LOCK.replace("\"format\":1", "\"extra\":true,\"format\":1"), "$");
    }

    @Test
    void compilerRejectsANonNumericDependencyLockFormat() {
        assertInvalid(LOCK.replace("\"format\":1", "\"format\":\"1\""), "format");
    }

    @Test
    void compilerRejectsABlankDependencyLockFlowDigest() {
        assertInvalid(LOCK.replace("sha256:" + FLOW_DIGEST, ""), "flow");
    }

    @Test
    void compilerRejectsANonStringDependencyLockFlowDigest() {
        assertInvalid(LOCK.replace(
                "\"flow\":\"sha256:" + FLOW_DIGEST + "\"",
                "\"flow\":1"
        ), "flow");
    }

    @Test
    void compilerRejectsANonArrayDependencyLockStepList() {
        assertInvalid(lockWithSteps("{}"), "steps");
    }

    @Test
    void compilerRejectsANonObjectLockedStep() {
        assertInvalid(lockWithSteps("[1]"), "steps[0]");
    }

    @Test
    void compilerRejectsAMissingLockedStepField() {
        assertInvalid(lockWithSteps("[{\"id\":\"text.lowercase\",\"version\":\"1.0.0\"}]"), "steps[0]");
    }

    @Test
    void compilerRejectsAnUnknownLockedStepField() {
        assertInvalid(lockWithSteps("[" + lockedStep("text.lowercase", "1.0.0", "sha256:"
                + CONTRACT_DIGEST).replace("}", ",\"extra\":true}") + "]"), "steps[0]");
    }

    @Test
    void compilerRejectsABlankLockedStepId() {
        assertInvalid(lockWithSteps("[" + lockedStep("", "1.0.0", "sha256:" + CONTRACT_DIGEST) + "]"),
                "steps[0]");
    }

    @Test
    void compilerRejectsANonStringLockedStepId() {
        assertInvalid(lockWithSteps("[{\"contract\":\"sha256:" + CONTRACT_DIGEST
                + "\",\"id\":1,\"version\":\"1.0.0\"}]"), "steps[0]");
    }

    @Test
    void compilerRejectsABlankLockedStepVersion() {
        assertInvalid(lockWithSteps("[" + lockedStep("text.lowercase", "", "sha256:"
                + CONTRACT_DIGEST) + "]"), "steps[0]");
    }

    @Test
    void compilerRejectsANonStringLockedStepVersion() {
        assertInvalid(lockWithSteps("[{\"contract\":\"sha256:" + CONTRACT_DIGEST
                + "\",\"id\":\"text.lowercase\",\"version\":1}]"), "steps[0]");
    }

    @Test
    void compilerRejectsABlankLockedStepContract() {
        assertInvalid(lockWithSteps("[" + lockedStep("text.lowercase", "1.0.0", "") + "]"), "steps[0].contract");
    }

    @Test
    void compilerRejectsANonStringLockedStepContract() {
        assertInvalid(lockWithSteps("[{\"contract\":1,\"id\":\"text.lowercase\","
                + "\"version\":\"1.0.0\"}]"), "steps[0].contract");
    }

    @Test
    void compilerRejectsDigestCharactersBeforeZero() {
        assertInvalid(LOCK.replace(
                "sha256:" + CONTRACT_DIGEST,
                "sha256:/" + CONTRACT_DIGEST.substring(1)
        ), "steps[0].contract");
    }

    @Test
    void compilerRejectsDigestCharactersAfterLowercaseF() {
        assertInvalid(LOCK.replace(
                "sha256:" + CONTRACT_DIGEST,
                "sha256:g" + CONTRACT_DIGEST.substring(1)
        ), "steps[0].contract");
    }

    @Test
    void compilerRejectsADuplicateLockedStepId() {
        final String step = lockedStep("text.lowercase", "1.0.0", "sha256:" + CONTRACT_DIGEST);

        assertInvalid(lockWithSteps("[" + step + "," + step + "]"), "steps[1].id");
    }

    @Test
    void compilerIgnoresDeclarationOrderWhenFingerprintingAContract() {
        final String flow = """
                {
                  "id": "ordered",
                  "triggers": [],
                  "entry": "ordered",
                  "inputs": {"a": "string", "b": "string"},
                  "outputs": {"value": "string"},
                  "steps": [
                    {"id": "ordered", "use": "ordered", "config": {},
                     "on": {"done": "end", "ok": "end"}}
                  ],
                  "connections": [
                    {"from": "input.a", "to": "ordered.a"},
                    {"from": "input.b", "to": "ordered.b"},
                    {"from": "ordered.value", "to": "output.value"}
                  ]
                }
                """;

        final String first = ((CompileResult.Compiled) FlowCompiler.compile(
                flow,
                StepCatalog.of(ordered(false))
        )).lock();
        final String second = ((CompileResult.Compiled) FlowCompiler.compile(
                flow,
                StepCatalog.of(ordered(true))
        )).lock();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void stepKindChangesTheDependencyContractFingerprint() {
        final String stepLock = ((CompileResult.Compiled) FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(lowercase("1.0.0"))
        )).lock();
        final String normalizerLock = ((CompileResult.Compiled) FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(lowercase("1.0.0", StepDefinition.Kind.NORMALIZER))
        )).lock();

        assertThat(normalizerLock).isNotEqualTo(stepLock);
    }

    @Test
    void staleStepKindLockIsRejectedDeterministically() {
        final String staleLock = ((CompileResult.Compiled) FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(lowercase("1.0.0"))
        )).lock();

        assertThat(FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(lowercase("1.0.0", StepDefinition.Kind.NORMALIZER)),
                staleLock
        )).isEqualTo(rejected(Diagnostic.atPath(
                "STEP_LOCK_CONTRACT_MISMATCH",
                "Locked Step contract does not match: text.lowercase",
                "steps.text.lowercase.contract"
        )));
    }

    @Test
    void configurationFormatChangesTheDependencyContractFingerprint() {
        final String unformattedLock = ((CompileResult.Compiled) FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(lowercase("1.0.0"))
        )).lock();
        final String formattedLock = ((CompileResult.Compiled) FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(formattedLowercase())
        )).lock();

        assertThat(formattedLock).isNotEqualTo(unformattedLock);
    }

    @Test
    void staleConfigurationFormatLockIsRejectedDeterministically() {
        final String staleLock = ((CompileResult.Compiled) FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(lowercase("1.0.0"))
        )).lock();

        assertThat(FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(formattedLowercase()),
                staleLock
        )).isEqualTo(rejected(Diagnostic.atPath(
                "STEP_LOCK_CONTRACT_MISMATCH",
                "Locked Step contract does not match: text.lowercase",
                "steps.text.lowercase.contract"
        )));
    }

    @Test
    void compilerRejectsInvalidUnicodeInAStepId() {
        assertDefinitionDiagnostic(
                StepDefinition.named(INVALID_UNICODE, "1.0.0")
                        .outcome("ok")
                        .run(input -> StepResult.outcome("ok")),
                Diagnostic.atPath(
                        "STEP_ID_INVALID",
                        "Step id must contain valid Unicode.",
                        "catalog[0].id"
                )
        );
    }

    @Test
    void compilerRejectsInvalidUnicodeInAStepVersion() {
        assertDefinitionDiagnostic(
                StepDefinition.named("text.lowercase", INVALID_UNICODE)
                        .outcome("ok")
                        .run(input -> StepResult.outcome("ok")),
                Diagnostic.atPath(
                        "STEP_VERSION_INVALID",
                        "Step version must contain valid Unicode.",
                        "catalog[0].version"
                )
        );
    }

    @Test
    void compilerRejectsInvalidUnicodeInAnInputName() {
        assertDefinitionDiagnostic(
                StepDefinition.named("text.lowercase", "1.0.0")
                        .input(INVALID_UNICODE, ValueShape.string())
                        .outcome("ok")
                        .run(input -> StepResult.outcome("ok")),
                Diagnostic.atPath(
                        "STEP_PORT_INVALID",
                        "Step port name must contain valid Unicode.",
                        "catalog[0].inputs"
                )
        );
    }

    @Test
    void compilerRejectsInvalidUnicodeInAnOutputName() {
        assertDefinitionDiagnostic(
                StepDefinition.named("text.lowercase", "1.0.0")
                        .output(INVALID_UNICODE, ValueShape.string())
                        .outcome("ok")
                        .run(input -> StepResult.outcome("ok")),
                Diagnostic.atPath(
                        "STEP_PORT_INVALID",
                        "Step port name must contain valid Unicode.",
                        "catalog[0].outputs"
                )
        );
    }

    @Test
    void compilerRejectsInvalidUnicodeInAConfigurationName() {
        assertDefinitionDiagnostic(
                StepDefinition.named("text.lowercase", "1.0.0")
                        .requiredConfig(INVALID_UNICODE, ValueShape.string())
                        .outcome("ok")
                        .run(input -> StepResult.outcome("ok")),
                Diagnostic.atPath(
                        "STEP_CONFIG_INVALID",
                        "Step configuration name must contain valid Unicode.",
                        "catalog[0].config"
                )
        );
    }

    @Test
    void compilerRejectsInvalidUnicodeNestedInAConfigurationDefault() {
        assertDefinitionDiagnostic(
                StepDefinition.named("text.lowercase", "1.0.0")
                        .config(
                                "value",
                                ValueShape.OBJECT,
                                RailixValue.object(Map.of(INVALID_UNICODE, RailixValue.string("value")))
                        )
                        .outcome("ok")
                        .run(input -> StepResult.outcome("ok")),
                Diagnostic.atPath(
                        "STEP_CONFIG_DEFAULT_INVALID",
                        "Step configuration default must contain valid JSON data.",
                        "catalog[0].config.value.default"
                )
        );
    }

    @Test
    void compilerRejectsInvalidUnicodeInAnOutcome() {
        assertDefinitionDiagnostic(
                StepDefinition.named("text.lowercase", "1.0.0")
                        .outcome(INVALID_UNICODE)
                        .run(input -> StepResult.outcome("ok")),
                Diagnostic.atPath(
                        "STEP_OUTCOME_INVALID",
                        "Step outcome must contain valid Unicode.",
                        "catalog[0].outcomes"
                )
        );
    }

    private static void assertLockDiagnostic(final String lock, final Diagnostic diagnostic) {
        assertThat(FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(lowercase("1.0.0")),
                lock
        )).isEqualTo(rejected(diagnostic));
    }

    private static void assertInvalid(final String lock, final String path) {
        assertLockDiagnostic(lock, Diagnostic.atPath(
                "STEP_LOCK_INVALID",
                "Step dependency lock has an invalid shape.",
                path
        ));
    }

    private static Diagnostic oversizedLock() {
        return Diagnostic.atPath(
                "STEP_LOCK_SOURCE_TOO_LARGE",
                "Step dependency lock exceeds the " + RailixData.MAX_SOURCE_BYTES + "-byte limit.",
                "lock"
        );
    }

    private static void assertDefinitionDiagnostic(
            final StepDefinition definition,
            final Diagnostic diagnostic
    ) {
        assertThat(FlowCompiler.compile(
                FlowFixtures.lowercaseFlow(),
                StepCatalog.of(definition)
        )).isEqualTo(rejected(diagnostic));
    }

    private static CompileResult.Rejected rejected(final Diagnostic diagnostic) {
        return new CompileResult.Rejected(List.of(diagnostic));
    }

    private static String lockWithSteps(final String steps) {
        return "{\"flow\":\"sha256:" + FLOW_DIGEST + "\",\"format\":1,\"steps\":" + steps + "}\n";
    }

    private static String lockedStep(
            final String id,
            final String version,
            final String contract
    ) {
        return "{\"contract\":\"" + contract + "\",\"id\":\"" + id
                + "\",\"version\":\"" + version + "\"}";
    }

    private static StepDefinition ordered(final boolean reverse) {
        final StepDefinition.Builder definition = StepDefinition.named("ordered", "1.0.0");
        if (reverse) {
            definition.config("z", ValueShape.string(), RailixValue.string("z"))
                    .config("a", ValueShape.string(), RailixValue.string("a"))
                    .input("b", ValueShape.string())
                    .input("a", ValueShape.string())
                    .output("value", ValueShape.string())
                    .outcome("done")
                    .outcome("ok");
        } else {
            definition.config("a", ValueShape.string(), RailixValue.string("a"))
                    .config("z", ValueShape.string(), RailixValue.string("z"))
                    .input("a", ValueShape.string())
                    .input("b", ValueShape.string())
                    .output("value", ValueShape.string())
                    .outcome("ok")
                    .outcome("done");
        }
        return definition.run(input -> StepResult.outcome("ok"));
    }

    private static StepDefinition lowercase(final String version) {
        return lowercase(version, StepDefinition.Kind.STEP);
    }

    private static StepDefinition lowercase(
            final String version,
            final StepDefinition.Kind kind
    ) {
        return StepDefinition.named("text.lowercase", version)
                .kind(kind)
                .config("languageTag", ValueShape.string(), RailixValue.string("und"))
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));
    }

    private static StepDefinition formattedLowercase() {
        return StepDefinition.named("text.lowercase", "1.0.0")
                .config(
                        "languageTag",
                        ValueShape.string(),
                        StepDefinition.ConfigFormat.LANGUAGE_TAG,
                        RailixValue.string("und")
                )
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));
    }
}
