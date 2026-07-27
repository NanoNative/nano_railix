package example.railix.welcome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedGreetingBuildIT {
    private static final Path REPOSITORY = Path.of("../..").toAbsolutePath().normalize();
    private static final Path ARTIFACT = Path.of(
            "examples/trusted-step-monolith/target/trusted-step-monolith.jar"
    );
    private static final Path LOCK = Path.of(
            "examples/trusted-step-monolith/src/main/resources/railix.lock.json"
    );
    private static final List<Path> BUILD_INPUTS = List.of(
            Path.of("pom.xml"),
            Path.of("modules/railix-core/pom.xml"),
            Path.of("modules/railix-core/src/main"),
            Path.of("modules/railix-stdlib/pom.xml"),
            Path.of("modules/railix-stdlib/src/main"),
            Path.of("modules/railix-creator/pom.xml"),
            Path.of("modules/railix-creator/src/main"),
            Path.of("examples/trusted-step-monolith/pom.xml"),
            Path.of("examples/trusted-step-monolith/src/main")
    );

    @Test
    void staleDependencyLockStopsTheRealBuildBeforeShading(@TempDir final Path temporary) throws Exception {
        final Path project = copyProject(temporary);
        Files.writeString(project.resolve(LOCK), staleLock());

        final BuildResult result = build(project, false);

        assertThat(result).satisfies(build -> {
            assertThat(build.exitCode()).isNotZero();
            assertThat(build.output()).contains("STEP_LOCK_FLOW_MISMATCH", "verify-step-lock");
            assertThat(project.resolve("examples/trusted-step-monolith/target/trusted-step-monolith.jar"))
                    .doesNotExist();
        });
    }

    @Test
    void missingDependencyLockStopsTheRealBuildBeforeShading(@TempDir final Path temporary) throws Exception {
        final Path project = copyProject(temporary);
        Files.delete(project.resolve(LOCK));

        final BuildResult result = build(project, false);

        assertThat(result).satisfies(build -> {
            assertThat(build.exitCode()).isNotZero();
            assertThat(build.output()).contains("PACKAGED_LOCK_MISSING", "verify-step-lock");
            assertThat(project.resolve(ARTIFACT)).doesNotExist();
        });
    }

    @Test
    void malformedDependencyLockStopsTheRealBuildBeforeShading(@TempDir final Path temporary) throws Exception {
        final Path project = copyProject(temporary);
        Files.writeString(project.resolve(LOCK), "{");

        final BuildResult result = build(project, false);

        assertThat(result).satisfies(build -> {
            assertThat(build.exitCode()).isNotZero();
            assertThat(build.output()).contains("STEP_LOCK_JSON_INVALID", "verify-step-lock");
            assertThat(project.resolve(ARTIFACT)).doesNotExist();
        });
    }

    @Test
    void refreshProfileRewritesTheLockAndBuildsTheRealArtifact(@TempDir final Path temporary) throws Exception {
        final Path project = copyProject(temporary);
        Files.writeString(project.resolve(LOCK), staleLock());

        final BuildResult result = build(project, true);
        final String expected = exactLock();

        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(Files.readString(project.resolve(LOCK))).isEqualTo(expected);
        assertThat(Files.readString(project.resolve(
                "examples/trusted-step-monolith/target/classes/railix.lock.json"
        ))).isEqualTo(expected);
        try (JarFile artifact = new JarFile(project.resolve(
                "examples/trusted-step-monolith/target/trusted-step-monolith.jar"
        ).toFile()); InputStream lock = artifact.getInputStream(artifact.getJarEntry("railix.lock.json"))) {
            assertThat(new String(lock.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(expected);
        }
    }

    @Test
    void cleanBuildsProduceByteIdenticalWholeFlowArtifacts(@TempDir final Path temporary) throws Exception {
        final Path firstProject = copyProject(temporary, "first-project");
        final Path secondProject = copyProject(temporary, "second-project");

        final BuildResult firstBuild = build(firstProject, false);
        Thread.sleep(2_100);
        final BuildResult secondBuild = build(secondProject, false);

        assertThat(firstBuild.exitCode()).as(firstBuild.output()).isZero();
        assertThat(secondBuild.exitCode()).as(secondBuild.output()).isZero();
        assertThat(Files.mismatch(firstProject.resolve(ARTIFACT), secondProject.resolve(ARTIFACT)))
                .isEqualTo(-1L);
    }

    @Test
    void cleanWholeFlowArtifactRunsFromAnIsolatedJavaOnlyDirectory(
            @TempDir final Path temporary
    ) throws Exception {
        final Path project = copyProject(temporary);
        final BuildResult build = build(project, false);
        final Path runtime = Files.createDirectory(temporary.resolve("runtime"));
        final Path artifact = Files.copy(project.resolve(ARTIFACT), runtime.resolve("application.jar"));
        final Path input = Files.writeString(runtime.resolve("input.json"), "{\"name\":\"RAILIX\"}");

        assertThat(build.exitCode()).as(build.output()).isZero();
        assertThat(run(artifact, runtime, input)).isEqualTo(new BuildResult(
                0,
                Files.readString(REPOSITORY.resolve(
                        "examples/trusted-step-monolith/expected-output.json"
                )).strip() + "\n"
        ));
    }

    private static Path copyProject(final Path temporary) throws IOException {
        return copyProject(temporary, "project");
    }

    private static Path copyProject(final Path temporary, final String name) throws IOException {
        final Path target = temporary.resolve(name);
        for (final Path input : BUILD_INPUTS) {
            copy(REPOSITORY.resolve(input), target.resolve(input));
        }
        return target;
    }

    private static Path copy(final Path source, final Path target) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.createDirectories(target.getParent());
            return Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
        try (Stream<Path> files = Files.walk(source)) {
            for (final Path file : files.toList()) {
                final Path destination = target.resolve(source.relativize(file));
                if (Files.isDirectory(file)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return target;
    }

    private static BuildResult build(final Path project, final boolean refresh) throws Exception {
        final String profiles = refresh
                ? "-Ptrusted-step-monolith,refresh-step-lock"
                : "-Ptrusted-step-monolith";
        return observe(new ProcessBuilder(
                "mvn",
                "-q",
                profiles,
                "-Dmaven.test.skip=true",
                "-Djacoco.skip=true",
                "package"
        ).directory(project.toFile()).redirectErrorStream(true).start(), 90, "Nested Maven build");
    }

    private static BuildResult run(
            final Path artifact,
            final Path directory,
            final Path input
    ) throws Exception {
        final ProcessBuilder process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar",
                artifact.toString(),
                "cli",
                "command"
        ).directory(directory.toFile()).redirectErrorStream(true);
        process.environment().clear();
        final Process application = process.start();
        try {
            try (var stdin = application.getOutputStream()) {
                stdin.write(Files.readAllBytes(input));
            }
            return observe(application, 10, "Custom monolith");
        } finally {
            stop(application);
        }
    }

    private static BuildResult observe(
            final Process process,
            final long timeoutSeconds,
            final String processName
    ) throws Exception {
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            final Future<String> output = readers.submit(() -> new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                stop(process);
                throw new AssertionError(processName + " did not terminate within " + timeoutSeconds + " seconds.");
            }
            return new BuildResult(process.exitValue(), output.get(5, TimeUnit.SECONDS));
        } finally {
            stop(process);
        }
    }

    private static Process stop(final Process process) {
        if (!process.isAlive()) {
            return process;
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        boolean interrupted = false;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (process.isAlive() && System.nanoTime() < deadline) {
            try {
                process.waitFor(100, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (process.isAlive()) {
            throw new AssertionError("Nested Maven build survived forced termination.");
        }
        return process;
    }

    private static String staleLock() throws IOException {
        final String lock = exactLock();
        final String prefix = "\"flow\":\"sha256:";
        final int digit = lock.indexOf(prefix) + prefix.length();
        return lock.substring(0, digit)
                + (lock.charAt(digit) == '0' ? '1' : '0')
                + lock.substring(digit + 1);
    }

    private static String exactLock() throws IOException {
        return Files.readString(REPOSITORY.resolve(LOCK));
    }

    private record BuildResult(int exitCode, String output) {
    }
}
