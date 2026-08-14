package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import thirdparty.production.ProductionRuntimeBranchSteps;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Exhaustive production-artifact routing acceptance across generated code partitions. */
@Timeout(120)
final class ProductionRuntimeBranchE2eTest {
    private static final int DEPTH = 7;
    private static final int BRANCHES = (1 << DEPTH) - 1;
    private static final int LEAVES = 1 << DEPTH;
    private static final String PROBE_CLASS =
            "dev.nanonative.railix.core.project.ProductionRuntimeBranchProbe";

    @Test
    void everyDepthSevenBinaryRouteReachesItsUniqueLeafWithoutTrace(
            @TempDir final Path workspace
    ) throws Exception {
        final Path application = workspace.resolve("application");
        final Path project = application.resolve("railix.project.json");
        final String source = project();
        Files.createDirectories(application);
        Files.writeString(project, source, StandardCharsets.UTF_8);
        final var catalog = GeneratedApplicationFixture.installedCatalog(
                application,
                definitions(),
                ProductionRuntimeBranchSteps.Branch.class,
                ProductionRuntimeBranchSteps.Leaf.class
        );
        final CompileResult result = ProjectCompiler.compileApplication(source, catalog);
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        final Path jar = ApplicationBuilder.buildProduction(project, (CompileResult.Compiled) result).jar();
        final Path classes = CreatorFirstExecutionArtifacts.compileProbe(
                workspace.resolve("probe"), jar, PROBE_CLASS, probeSource()
        );

        final CreatorFirstExecutionArtifacts.ProcessResult observed =
                CreatorFirstExecutionArtifacts.runProbe(classes, jar, PROBE_CLASS);

        assertThat(observed.exitCode()).as(observed.output()).isZero();
        assertThat(observed.output()).startsWith("BRANCH|");
        assertThat(metric(observed.output(), "calls")).isEqualTo(LEAVES);
        assertThat(metric(observed.output(), "unique")).isEqualTo(LEAVES);
        assertThat(metric(observed.output(), "mismatches")).isZero();
        assertThat(metric(observed.output(), "traced")).isZero();
    }

    private static List<StepDefinition> definitions() {
        return List.of(
                StepDefinition.named("production.branch", "1")
                        .primaryOutcome("zero")
                        .receive("route", ValueShape.STRING)
                        .input("depth", StepDefinition.Input.json(ValueShape.NUMBER)
                                .defaultValue(RailixValue.number(0)))
                        .outcome("one")
                        .run(ProductionRuntimeBranchSteps.Branch.class),
                StepDefinition.named("production.leaf", "1")
                        .primaryOutcome("ok")
                        .receive("route", ValueShape.STRING)
                        .returns("value", ValueShape.STRING)
                        .input("expected", StepDefinition.Input.json(ValueShape.STRING)
                                .defaultValue(RailixValue.string("")))
                        .outcome("invalid")
                        .run(ProductionRuntimeBranchSteps.Leaf.class)
        );
    }

    private static String project() {
        final StringBuilder nodes = new StringBuilder("""
                {"format":1,"id":"exhaustive-branch","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"zero","payload":["0000000"]
                  }]}
                """);
        final StringBuilder links = new StringBuilder("""
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"branch-0"}
                """);
        for (int index = 0; index < BRANCHES; index++) {
            final int depth = Integer.SIZE - Integer.numberOfLeadingZeros(index + 1) - 1;
            nodes.append("""
                    ,{"id":"branch-%d","use":"production.branch","inputs":{"depth":%d},
                      "receives":{"route":["context","payload","arguments",0]}}
                    """.formatted(index, depth));
            final boolean leaves = depth == DEPTH - 1;
            final int offset = index - ((1 << (DEPTH - 1)) - 1);
            links.append("""
                    ,{"from":"branch-%d.zero","to":"%s-%d"},
                    {"from":"branch-%d.one","to":"%s-%d"}
                    """.formatted(
                    index,
                    leaves ? "leaf" : "branch",
                    leaves ? offset * 2 : index * 2 + 1,
                    index,
                    leaves ? "leaf" : "branch",
                    leaves ? offset * 2 + 1 : index * 2 + 2
            ));
        }
        for (int index = 0; index < LEAVES; index++) {
            final String route = route(index);
            nodes.append("""
                    ,{"id":"leaf-%d","use":"production.leaf","inputs":{"expected":"%s"},
                      "receives":{"route":["context","payload","arguments",0]},
                      "returns":{"value":["context","result"]}}
                    """.formatted(index, route));
            links.append("""
                    ,{"from":"leaf-%d.ok","to":"end"},
                    {"from":"leaf-%d.invalid","to":"end"}
                    """.formatted(index, index));
        }
        return nodes.append(links).append("]}").toString();
    }

    private static String route(final int value) {
        return Integer.toBinaryString(LEAVES + value).substring(1);
    }

    private static long metric(final String output, final String name) {
        for (final String part : output.strip().split("\\|")) {
            if (part.startsWith(name + "=")) {
                return Long.parseLong(part.substring(name.length() + 1));
            }
        }
        throw new AssertionError("Missing metric " + name + " in child output: " + output);
    }

    private static String probeSource() {
        return """
                package dev.nanonative.railix.core.project;

                import dev.nanonative.railix.core.runtime.RunResult;
                import dev.nanonative.railix.core.value.RailixValue;
                import java.util.HashSet;
                import java.util.List;
                import java.util.Map;
                import java.util.Set;

                public final class ProductionRuntimeBranchProbe {
                    private static final int ROUTES = 128;

                    private ProductionRuntimeBranchProbe() {
                    }

                    public static void main(final String[] arguments) {
                        final RuntimeApplication application = RailixApplication.runtime();
                        final Set<String> unique = new HashSet<>();
                        long mismatches = 0;
                        long traced = 0;
                        for (int index = 0; index < ROUTES; index++) {
                            final String route = Integer.toBinaryString(ROUTES + index).substring(1);
                            final WorkflowRuntime.SourceResult source = application.runSource(
                                    "application.arguments",
                                    Map.of("arguments", RailixValue.array(List.of(RailixValue.string(route))))
                            );
                            if (source.result() instanceof RunResult.Succeeded succeeded) {
                                final RailixValue expected = RailixValue.string(route);
                                if (expected.equals(source.responses().get("output"))
                                        && RailixValue.number(0).equals(source.responses().get("status"))
                                        && expected.equals(succeeded.context().values().get("result"))) {
                                    unique.add(route);
                                } else {
                                    mismatches++;
                                }
                                if (!succeeded.steps().isEmpty()) {
                                    traced++;
                                }
                            } else {
                                mismatches++;
                            }
                        }
                        System.out.print("BRANCH|calls=" + ROUTES
                                + "|unique=" + unique.size()
                                + "|mismatches=" + mismatches
                                + "|traced=" + traced);
                    }
                }
                """;
    }
}
