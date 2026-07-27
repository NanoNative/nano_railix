package example.railix.welcome;

import dev.nanonative.railix.core.flow.CompileResult;
import dev.nanonative.railix.core.flow.Diagnostic;
import dev.nanonative.railix.core.flow.FlowCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import dev.nanonative.railix.stdlib.text.LowercaseStep;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StepDependencyBuildIT {
    private static final Path FLOW = Path.of("src/main/resources/railix.flow.json");
    private static final Path LOCK = Path.of("src/main/resources/railix.lock.json");
    private static final String PREFIX = "example.text.prefix";

    @Test
    void buildDerivesTheCommittedLockFromTheCompleteFlow() throws Exception {
        final CompileResult result = FlowCompiler.compile(
                Files.readString(FLOW),
                StepCatalog.of(LowercaseStep.definition(), GreetingStep.definition())
        );

        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        assertThat(((CompileResult.Compiled) result).lock()).isEqualTo(Files.readString(LOCK));
    }

    @Test
    void buildDerivesTheSameLockForReorderedCatalogEntries() throws Exception {
        final CompileResult result = FlowCompiler.compile(
                Files.readString(FLOW),
                StepCatalog.of(GreetingStep.definition(), LowercaseStep.definition())
        );

        assertThat(((CompileResult.Compiled) result).lock()).isEqualTo(Files.readString(LOCK));
    }

    @Test
    void buildRejectsAMissingReferencedDependency() throws Exception {
        assertRejected(
                StepCatalog.of(LowercaseStep.definition()),
                Diagnostic.atPath(
                        "FLOW_STEP_UNKNOWN",
                        "Unknown Step dependency: " + PREFIX,
                        "steps.prefix.use"
                )
        );
    }

    @Test
    void buildRejectsADuplicateDependencyId() throws Exception {
        assertRejected(
                StepCatalog.of(
                        LowercaseStep.definition(),
                        GreetingStep.definition(),
                        GreetingStep.definition()
                ),
                Diagnostic.atPath(
                        "STEP_ID_DUPLICATE",
                        "Duplicate Step dependency: " + PREFIX,
                        "catalog[2].id"
                )
        );
    }

    @Test
    void buildRejectsADependencyWithoutAHandler() throws Exception {
        final StepDefinition dependency = dependency()
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(null);

        assertRejected(
                catalog(dependency),
                Diagnostic.atPath(
                        "STEP_HANDLER_REQUIRED",
                        "Step handler is missing.",
                        "catalog[1].handler"
                )
        );
    }

    @Test
    void buildRejectsADependencyWithItsUsedInputRemoved() throws Exception {
        final StepDefinition dependency = dependency()
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertRejected(
                catalog(dependency),
                Diagnostic.atPath(
                        "FLOW_TARGET_PORT_UNKNOWN",
                        "Unknown Step input: prefix.text",
                        "connections[1].to"
                )
        );
    }

    @Test
    void buildRejectsADependencyWithItsUsedOutputRemoved() throws Exception {
        final StepDefinition dependency = dependency()
                .input("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertRejected(
                catalog(dependency),
                Diagnostic.atPath(
                        "FLOW_SOURCE_PORT_UNKNOWN",
                        "Unknown Step output: prefix.text",
                        "connections[2].from"
                )
        );
    }

    @Test
    void buildRejectsADependencyWithAnIncompatibleUsedShape() throws Exception {
        final StepDefinition dependency = dependency()
                .input("text", ValueShape.number())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertRejected(
                catalog(dependency),
                Diagnostic.atPath(
                        "FLOW_CONNECTION_TYPE_MISMATCH",
                        "Cannot connect STRING to NUMBER.",
                        "connections[1]"
                )
        );
    }

    @Test
    void buildRejectsADependencyWithAnIncompatibleUsedOutputShape() throws Exception {
        final StepDefinition dependency = dependency()
                .input("text", ValueShape.string())
                .output("text", ValueShape.number())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertRejected(
                catalog(dependency),
                Diagnostic.atPath(
                        "FLOW_CONNECTION_TYPE_MISMATCH",
                        "Cannot connect NUMBER to STRING.",
                        "connections[2]"
                )
        );
    }

    @Test
    void buildRejectsADependencyWithItsUsedOutcomeRemoved() throws Exception {
        final StepDefinition dependency = dependency()
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("done")
                .run(input -> StepResult.outcome("done"));

        assertRejected(
                catalog(dependency),
                Diagnostic.atPath(
                        "FLOW_OUTCOME_UNHANDLED",
                        "Outcome must connect to another Step or end: done",
                        "steps.prefix.on.done"
                ),
                Diagnostic.atPath(
                        "FLOW_OUTCOME_UNKNOWN",
                        "Transition uses an undeclared outcome: ok",
                        "steps.prefix.on.ok"
                )
        );
    }

    @Test
    void buildRejectsADependencyWhoseDefaultedConfigurationBecameRequired() throws Exception {
        final StepDefinition dependency = StepDefinition.named(PREFIX, "1.0.0")
                .requiredConfig("prefix", ValueShape.string())
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertRejected(
                catalog(dependency),
                Diagnostic.atPath(
                        "FLOW_STEP_CONFIG_REQUIRED",
                        "Required Step configuration is missing: prefix",
                        "steps.prefix.config.prefix"
                )
        );
    }

    @Test
    void buildRejectsAChangedDependencyVersion() throws Exception {
        final StepDefinition dependency = dependency("2.0.0", "Welcome, ")
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertLockRejected(catalog(dependency), Diagnostic.atPath(
                "STEP_LOCK_VERSION_MISMATCH",
                "Locked Step version does not match: " + PREFIX,
                "steps." + PREFIX + ".version"
        ));
    }

    @Test
    void buildRejectsAChangedDependencyDefault() throws Exception {
        final StepDefinition dependency = dependency("1.0.0", "Hello, ")
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertLockRejected(catalog(dependency), Diagnostic.atPath(
                "STEP_LOCK_CONTRACT_MISMATCH",
                "Locked Step contract does not match: " + PREFIX,
                "steps." + PREFIX + ".contract"
        ));
    }

    @Test
    void buildRejectsAChangedUnusedDependencyContractSurface() throws Exception {
        final StepDefinition dependency = dependency()
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .output("debug", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok"));

        assertLockRejected(catalog(dependency), Diagnostic.atPath(
                "STEP_LOCK_CONTRACT_MISMATCH",
                "Locked Step contract does not match: " + PREFIX,
                "steps." + PREFIX + ".contract"
        ));
    }

    private static StepDefinition.Builder dependency() {
        return dependency("1.0.0", "Welcome, ");
    }

    private static StepDefinition.Builder dependency(final String version, final String prefix) {
        return StepDefinition.named(PREFIX, version)
                .config("prefix", ValueShape.string(), RailixValue.string(prefix));
    }

    private static StepCatalog catalog(final StepDefinition dependency) {
        return StepCatalog.of(LowercaseStep.definition(), dependency);
    }

    private static void assertRejected(
            final StepCatalog catalog,
            final Diagnostic... diagnostics
    ) throws Exception {
        assertThat(FlowCompiler.compile(Files.readString(FLOW), catalog))
                .isEqualTo(new CompileResult.Rejected(List.of(diagnostics)));
    }

    private static void assertLockRejected(
            final StepCatalog catalog,
            final Diagnostic diagnostic
    ) throws Exception {
        assertThat(FlowCompiler.compile(Files.readString(FLOW), catalog, Files.readString(LOCK)))
                .isEqualTo(new CompileResult.Rejected(List.of(diagnostic)));
    }
}
