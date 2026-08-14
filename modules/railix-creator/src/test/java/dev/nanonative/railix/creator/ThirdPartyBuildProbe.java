package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.stdlib.StandardLibrary;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Separate constrained-heap process used by the bundle packaging E2E. */
public final class ThirdPartyBuildProbe {
    private ThirdPartyBuildProbe() {
    }

    public static void main(final String[] arguments) throws Exception {
        if (arguments.length != 3) {
            throw new IllegalArgumentException("Expected project, lock, and store paths.");
        }
        final Path project = Path.of(arguments[0]);
        final StepCatalog catalog = StandardLibrary.catalog().install(
                Path.of(arguments[1]),
                Path.of(arguments[2])
        );
        final CompileResult result = ProjectCompiler.compileApplication(
                Files.readString(project, StandardCharsets.UTF_8),
                catalog
        );
        if (!(result instanceof CompileResult.Compiled compiled)) {
            throw new IllegalStateException("Probe project did not compile: " + result);
        }
        System.out.println(ApplicationBuilder.buildProduction(project, compiled).jar());
    }
}
