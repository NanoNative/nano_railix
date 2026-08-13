package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.project.CompileResult;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepContractJson;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepHandler;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import dev.nanonative.railix.development.DevelopmentRuntime;
import dev.nanonative.railix.stdlib.StandardLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import thirdparty.conformance.RuntimeBoundaryProbeStep;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Execution(ExecutionMode.SAME_THREAD)
final class ThirdPartyStepBundleE2eTest {
    private static final int PROCESS_OUTPUT_LIMIT = 16_384;

    @TempDir
    Path directory;

    @Test
    void generatedSourceCallsTheExternalImplementationDirectly() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));

        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");

        assertThat(compiled.productionApplicationSource())
                .contains("new thirdparty.alpha.Handlers.BundleStep()")
                .doesNotContain("Class.forName", "java.class.path", "ServiceLoader");
    }

    @Test
    void nestedExternalStepExecutesFromTheGeneratedJar() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));

        final ProcessResult result = run(workspace, "external.alpha", "VALUE");

        assertThat(result).isEqualTo(new ProcessResult(0, "\"helper-VALUE-resource\""));
    }

    @Test
    void creatorAutomaticallyLoadsBuildsAndRunsTheInstalledBundle() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory, "external.alpha");

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.railixHome());
             HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> response = runCreator(client, creator, "CREATOR");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"result\":\"helper-CREATOR-resource\"");
        }
    }

    @Test
    void forgedReadinessOutputCannotRedirectCreator() throws Exception {
        assertCreatorRuns(noisyBundle("forgedready", "RAILIX_READY 1\n", false), "FORGED");
    }

    @Test
    void malformedReadinessOutputCannotBreakCreatorStartup() throws Exception {
        assertCreatorRuns(noisyBundle("malformedready", "RAILIX_READY malformed\n", false), "MALFORMED");
    }

    @Test
    void outOfRangeReadinessOutputCannotBreakCreatorStartup() throws Exception {
        assertCreatorRuns(noisyBundle("invalidready", "RAILIX_READY 70000\n", false), "RANGE");
    }

    @Test
    void unterminatedStepOutputCannotHideCreatorReadiness() throws Exception {
        assertCreatorRuns(noisyBundle("unterminated", "unterminated", false), "LINE");
    }

    @Test
    void wrongTokenCallbackCannotConsumeAuthenticatedCreatorReadiness() throws Exception {
        assertCreatorRuns(interferingBundle("wrongtoken", "wrong-token"), "TOKEN");
    }

    @Test
    void partialCallbackCannotConsumeAuthenticatedCreatorReadiness() throws Exception {
        assertCreatorRuns(interferingBundle("partialcallback", "partial"), "PARTIAL");
    }

    @Test
    void oversizedCallbackCannotConsumeAuthenticatedCreatorReadiness() throws Exception {
        assertCreatorRuns(interferingBundle("oversizedcallback", "oversized"), "OVERSIZED");
    }

    @Test
    void stalledCallbackCannotConsumeAuthenticatedCreatorReadiness() throws Exception {
        assertCreatorRuns(interferingBundle("stalledcallback", "stalled"), "STALLED");
    }

    @Test
    void wrongPrefixCallbackCannotConsumeAuthenticatedCreatorReadiness() throws Exception {
        assertCreatorRuns(interferingBundle("wrongprefix", "wrong-prefix"), "PREFIX");
    }

    @Test
    void emptyTokenCallbackCannotConsumeAuthenticatedCreatorReadiness() throws Exception {
        assertCreatorRuns(interferingBundle("emptytoken", "empty-token"), "EMPTYTOKEN");
    }

    @Test
    void extraFieldCallbackCannotConsumeAuthenticatedCreatorReadiness() throws Exception {
        assertCreatorRuns(interferingBundle("extrafield", "extra-field"), "EXTRAFIELD");
    }

    @Test
    void emptyPortCallbackCannotConsumeAuthenticatedCreatorReadiness() throws Exception {
        assertCreatorRuns(interferingBundle("emptyport", "empty-port"), "EMPTYPORT");
    }

    @Test
    void nonNumericPortCallbackCannotConsumeAuthenticatedCreatorReadiness() throws Exception {
        assertCreatorRuns(interferingBundle("nonnumericport", "non-numeric-port"), "NONNUMERIC");
    }

    @Test
    void zeroPortCallbackCannotConsumeAuthenticatedCreatorReadiness() throws Exception {
        assertCreatorRuns(interferingBundle("zeroport", "zero-port"), "ZEROPORT");
    }

    @Test
    void outOfRangePortCallbackCannotConsumeAuthenticatedCreatorReadiness() throws Exception {
        assertCreatorRuns(interferingBundle("highport", "high-port"), "HIGHPORT");
    }

    @Test
    void overflowingPortCallbackCannotConsumeAuthenticatedCreatorReadiness() throws Exception {
        assertCreatorRuns(interferingBundle("overflowport", "overflow-port"), "OVERFLOWPORT");
    }

    @Test
    void controlByteCallbackCannotConsumeAuthenticatedCreatorReadiness() throws Exception {
        assertCreatorRuns(interferingBundle("controlcallback", "control-byte"), "CONTROL");
    }

    @Test
    void nonAsciiCallbackCannotConsumeAuthenticatedCreatorReadiness() throws Exception {
        assertCreatorRuns(interferingBundle("nonasciicallback", "non-ascii"), "NONASCII");
    }

    @Test
    void failedDevelopmentChildStartupDeletesItsBuildAndReleasesTheProjectLease() throws Exception {
        final Bundle bundle = interferingBundle("failedstartup", "fail");
        final Workspace workspace = workspace(List.of(bundle));
        final Path project = project(directory, bundle.definition().id());

        assertThatThrownBy(() -> CreatorServer.start(0, project, workspace.railixHome()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Development application exited before readiness.");
        try (var builds = Files.list(project.getParent().resolve(".railix/build"))) {
            assertThat(builds.toList()).isEmpty();
        }

        Files.writeString(project, CreatorProjects.empty("recovered-startup"), StandardCharsets.UTF_8);
        try (CreatorServer creator = CreatorServer.start(0, project, workspace.railixHome())) {
            assertThat(creator.baseUri().getPort()).isPositive();
        }
    }

    @Test
    void markerShapedRuntimeOutputCannotStopDrainingTheApplicationPipe() throws Exception {
        final Bundle bundle = noisyBundle("runtimeoutput", "", true);
        final Workspace workspace = workspace(List.of(bundle));
        final Path project = project(directory, bundle.definition().id());
        long pid;

        try (CreatorServer creator = CreatorServer.start(0, project, workspace.railixHome());
             HttpClient client = HttpClient.newHttpClient()) {
            pid = applicationPid(client, creator);
            final HttpResponse<String> first = runCreator(client, creator, "FIRST");
            final HttpResponse<String> second = runCreator(client, creator, "SECOND");
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(first.body()).contains("\"result\":\"helper-FIRST-resource\"");
            assertThat(second.statusCode()).isEqualTo(200);
            assertThat(second.body()).contains("\"result\":\"helper-SECOND-resource\"");
        }

        assertThat(awaitStopped(pid)).isTrue();
    }

    @Test
    void generatedJarContainsTheTransitiveHelperClass() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));

        final ApplicationBuilder.Artifact application = build(workspace, "external.alpha", directory.resolve("app"));

        try (JarFile jar = new JarFile(application.jar().toFile())) {
            assertThat(jar.getJarEntry(bundle.helperClass())).isNotNull();
        }
    }

    @Test
    void generatedJarContainsTheBundleResourceUsedByTheStep() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));

        final ApplicationBuilder.Artifact application = build(workspace, "external.alpha", directory.resolve("app"));

        try (JarFile jar = new JarFile(application.jar().toFile())) {
            assertThat(new String(
                    jar.getInputStream(jar.getJarEntry(bundle.resource())).readAllBytes(),
                    StandardCharsets.UTF_8
            )).isEqualTo("-resource");
        }
        assertThat(runJar(application.jar(), "RESOURCE").output()).isEqualTo("\"helper-RESOURCE-resource\"");
    }

    @Test
    void productionBuildRejectsABundleThatProvidesARailixPlatformClass() throws Exception {
        final Bundle bundle = bundle("isolated", "external.isolated", "helper-", "-resource", Map.of(), Map.of());
        rewriteJar(bundle.root(), Map.of(), Map.of(
                "dev/nanonative/railix/development/DevelopmentRuntime.class",
                classBytes(DevelopmentRuntime.class)
        ));
        final Workspace workspace = workspace(List.of(bundle));
        final CompileResult.Compiled compiled = compile(workspace, bundle.definition().id());

        assertThatThrownBy(() -> ApplicationBuilder.buildProduction(
                project(directory.resolve("platform-class"), bundle.definition().id()), compiled
        )).isInstanceOf(IOException.class)
                .hasMessageStartingWith("DEPENDENCY_PLATFORM_CLASS_FORBIDDEN:")
                .hasMessageContaining("dev/nanonative/railix/development/DevelopmentRuntime.class");
    }

    @Test
    void implementationClassMustBelongToTheRootBundleArtifact() throws Exception {
        final Bundle bundle = bundle("misowned", "external.misowned", "helper-", "-resource", Map.of(), Map.of());
        final byte[] implementation;
        try (JarFile root = new JarFile(bundle.root().toFile())) {
            implementation = root.getInputStream(root.getJarEntry(bundle.rootClass())).readAllBytes();
        }
        rewriteJar(bundle.root(), Map.of(), Map.of(), Set.of(bundle.rootClass()));
        rewriteJar(bundle.helper(), Map.of(), Map.of(bundle.rootClass(), implementation));
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_OWNER_MISSING:");
    }

    @Test
    void implementationClassMustMatchItsOwnedRootEntry() throws Exception {
        final Bundle bundle = bundle("mispaired", "external.mispaired", "helper-", "-resource", Map.of(), Map.of());
        final String unrelatedRootEntry = bundle.rootClass().replace("$BundleStep", "");
        replaceManifest(bundle, manifest(bundle.definition(), bundle.implementation(), unrelatedRootEntry));
        final Bundle forged = new Bundle(
                bundle.root(),
                bundle.helper(),
                bundle.definition(),
                bundle.implementation(),
                bundle.contractDigest(),
                unrelatedRootEntry,
                bundle.helperClass(),
                bundle.resource()
        );
        final Workspace workspace = workspace(List.of(forged));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_CONTRACT_INVALID:");
    }

    @Test
    void generatedJarExcludesAnInstalledButUnreachableBundle() throws Exception {
        final Bundle used = bundle("alpha", "external.alpha", "used-", "-resource", Map.of(), Map.of());
        final Bundle unused = bundle("unused", "external.unused", "unused-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(used, unused));

        final ApplicationBuilder.Artifact application = build(workspace, "external.alpha", directory.resolve("app"));

        try (JarFile jar = new JarFile(application.jar().toFile())) {
            assertThat(jar.getJarEntry(used.rootClass())).isNotNull();
            assertThat(jar.getJarEntry(unused.rootClass())).isNull();
            assertThat(jar.getJarEntry(unused.helperClass())).isNull();
            assertThat(jar.getJarEntry(unused.resource())).isNull();
        }
    }

    @Test
    void missingLockedArtifactIsRejectedBeforeCompilation() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        Files.delete(workspace.stored(bundle.helper()));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_MISSING:");
    }

    @Test
    void changedLockedArtifactIsRejectedByDigest() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        Files.write(workspace.stored(bundle.helper()), new byte[]{0x42}, java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_DIGEST_MISMATCH:");
    }

    @Test
    void artifactChangedAfterCatalogInstallationIsRejectedBeforePackaging() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        Files.write(workspace.stored(bundle.helper()), new byte[]{0x42}, java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(() -> ApplicationBuilder.buildProduction(
                project(directory.resolve("changed-after-install"), "external.alpha"), compiled
        )).isInstanceOf(IOException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_DIGEST_MISMATCH:");
    }

    @Test
    void artifactWithPathologicalEntryCountIsRejected() throws Exception {
        final Map<String, String> entries = new LinkedHashMap<>();
        for (int index = 0; index <= 65_536; index++) {
            entries.put("many/entry-" + index, "");
        }
        final Bundle bundle = bundle("many", "external.many", "helper-", "-resource", entries, Map.of());
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_ENTRY_LIMIT:");
    }

    @Test
    void nonCanonicalDependencyLockIsRejected() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        Files.writeString(workspace.lock(), "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("DEPENDENCY_LOCK_NON_CANONICAL:");
    }

    @Test
    void corruptArtifactWithMatchingDigestIsRejectedAsInvalidJar() throws Exception {
        final Path store = directory.resolve("corrupt-store");
        Files.createDirectories(store);
        final byte[] bytes = "not-a-jar".getBytes(StandardCharsets.UTF_8);
        final String digest = sha256(bytes);
        Files.write(store.resolve(digest + ".jar"), bytes);
        final RailixValue lockValue = RailixValue.object(Map.of(
                "format", RailixValue.number(1),
                "artifacts", RailixValue.array(List.of(RailixValue.object(Map.of(
                        "digest", RailixValue.string("sha256:" + digest),
                        "origin", RailixValue.string("file:corrupt.jar"),
                        "size", RailixValue.number(bytes.length)
                )))),
                "bundles", RailixValue.array(List.of(RailixValue.object(Map.of(
                        "artifact", RailixValue.string("sha256:" + digest),
                        "runtime", RailixValue.array(List.of()),
                        "steps", RailixValue.array(List.of())
                ))))
        ));
        final Path lock = directory.resolve("corrupt.lock.json");
        Files.writeString(lock, RailixJson.write(lockValue), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> StandardLibrary.catalog().install(lock, store))
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_INVALID_JAR:");
    }

    @Test
    void artifactEntryOverTheUncompressedLimitIsRejectedBeforeExpansion() throws Exception {
        final Path jar = directory.resolve("oversized-entry.jar");
        jar(jar, Map.of("oversized.bin", new byte[]{0x42}));
        patchCentralDirectorySizes(jar, List.of(StepCatalog.MAX_ARTIFACT_ENTRY_BYTES + 1));
        final Workspace workspace = lockedArtifact(jar, "oversized-entry");

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_ENTRY_SIZE_LIMIT:");
    }

    @Test
    void artifactOverTheTotalUncompressedLimitIsRejectedBeforeExpansion() throws Exception {
        final Path jar = directory.resolve("oversized-artifact.jar");
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        final List<Long> sizes = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            entries.put("part-" + index + ".bin", new byte[]{(byte) index});
            sizes.add(StepCatalog.MAX_ARTIFACT_ENTRY_BYTES);
        }
        jar(jar, entries);
        patchCentralDirectorySizes(jar, sizes);
        final Workspace workspace = lockedArtifact(jar, "oversized-artifact");

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_UNCOMPRESSED_SIZE_LIMIT:");
    }

    @Test
    void lockContractMismatchIsRejected() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        final String source = Files.readString(workspace.lock(), StandardCharsets.UTF_8);
        Files.writeString(
                workspace.lock(),
                source.replace(bundle.contractDigest(), "sha256:" + "0".repeat(64)),
                StandardCharsets.UTF_8
        );

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_CONTRACT_MISMATCH:");
    }

    @Test
    void lockStepMissingFromTheBundleManifestIsRejected() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        replaceLockedStepField(workspace, bundle, "id", RailixValue.string("external.unknown"));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_OWNER_MISSING:");
    }

    @Test
    void lockStepVersionMismatchIsRejected() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        replaceLockedStepField(workspace, bundle, "version", RailixValue.string("2"));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_CONTRACT_MISMATCH:");
    }

    @Test
    void lockStepImplementationClassMismatchIsRejected() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        replaceLockedStepField(workspace, bundle, "implementation", RailixValue.string("thirdparty.alpha.Other"));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_CONTRACT_MISMATCH:");
    }

    @Test
    void lockStepImplementationEntryMismatchIsRejected() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        replaceLockedStepField(
                workspace,
                bundle,
                "implementation_entry",
                RailixValue.string("thirdparty/alpha/Other.class")
        );

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_CONTRACT_MISMATCH:");
    }

    @Test
    void lockMustListEveryStepOwnedByTheBundleManifest() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        replaceLockedSteps(workspace, List.of());

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_OWNER_MISSING:");
    }

    @Test
    void installedBundleCannotReplaceAPlatformStepId() throws Exception {
        final Bundle bundle = bundle("conflict", "railix.trigger.cli", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_ID_CONFLICT:");
    }

    @Test
    void twoInstalledBundlesCannotOwnTheSameStepId() throws Exception {
        final Bundle first = bundle("first", "external.shared", "first-", "-resource", Map.of(), Map.of());
        final Bundle second = bundle("second", "external.shared", "second-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(first, second));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_ID_CONFLICT:");
    }

    @Test
    void bundleWithoutAStepManifestIsRejected() throws Exception {
        final Bundle bundle = bundle("missingmanifest", "external.missing-manifest", "helper-", "-resource", Map.of(), Map.of());
        rewriteJar(bundle.root(), Map.of(), Map.of(), Set.of("META-INF/railix/steps.json"));
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_OWNER_MISSING:");
    }

    @Test
    void bundleManifestFormatMustBeOne() throws Exception {
        final Bundle bundle = bundle("format", "external.format", "helper-", "-resource", Map.of(), Map.of());
        replaceManifest(bundle, manifest(bundle).replace("\"format\":1", "\"format\":2"));
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_MANIFEST_NON_CANONICAL:");
    }

    @Test
    void bundleManifestContractDigestMustMatchItsContract() throws Exception {
        final Bundle bundle = bundle("digest", "external.digest", "helper-", "-resource", Map.of(), Map.of());
        replaceManifest(bundle, manifest(bundle).replace(bundle.contractDigest(), "sha256:" + "0".repeat(64)));
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_CONTRACT_MISMATCH:");
    }

    @Test
    void bundleMustContainItsDeclaredStepImplementation() throws Exception {
        final Bundle bundle = bundle("missingclass", "external.missing-class", "helper-", "-resource", Map.of(), Map.of());
        rewriteJar(bundle.root(), Map.of(), Map.of(), Set.of(bundle.rootClass()));
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(workspace::catalog)
                .isInstanceOf(StepCatalog.DependencyException.class)
                .hasMessageStartingWith("STEP_DEPENDENCY_OWNER_MISSING:");
    }

    @Test
    void differentBytesAtTheSameReachableEntryAreRejected() throws Exception {
        final Bundle bundle = bundle(
                "alpha",
                "external.alpha",
                "helper-",
                "-resource",
                Map.of("thirdparty/shared.txt", "root"),
                Map.of("thirdparty/shared.txt", "helper")
        );
        final Workspace workspace = workspace(List.of(bundle));
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final Path project = project(directory.resolve("conflict"), "external.alpha");

        assertThatThrownBy(() -> ApplicationBuilder.buildProduction(project, compiled))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("DEPENDENCY_ENTRY_CONFLICT:");
    }

    @Test
    void serviceProvidersFromReachableArtifactsAreMergedDeterministically() throws Exception {
        final String service = "META-INF/services/example.Service";
        final Bundle bundle = bundle(
                "services",
                "external.services",
                "helper-",
                "-resource",
                Map.of(service, "thirdparty.services.Root\nthirdparty.services.Shared # duplicate\n"),
                Map.of(service, "# helper providers\nthirdparty.services.Helper\nthirdparty.services.Shared\n")
        );
        final Workspace workspace = workspace(List.of(bundle));

        final ApplicationBuilder.Artifact application = build(
                workspace,
                bundle.definition().id(),
                directory.resolve("services-app")
        );

        try (JarFile jar = new JarFile(application.jar().toFile())) {
            assertThat(new String(jar.getInputStream(jar.getJarEntry(service)).readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("thirdparty.services.Helper\nthirdparty.services.Root\nthirdparty.services.Shared\n");
        }
    }

    @Test
    void malformedUtf8ServiceDescriptorIsRejected() throws Exception {
        final String service = "META-INF/services/example.Service";
        final Bundle bundle = bundle(
                "invalidservice",
                "external.invalid-service",
                "helper-",
                "-resource",
                Map.of(service, "thirdparty.invalid_service.Root\n"),
                Map.of()
        );
        rewriteJar(bundle.helper(), Map.of(), Map.of(service, new byte[]{(byte) 0xc3, 0x28}));
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(() -> build(
                workspace,
                bundle.definition().id(),
                directory.resolve("invalid-service-app")
        )).isInstanceOf(IOException.class)
                .hasMessageStartingWith("DEPENDENCY_ENTRY_CONFLICT: Service descriptor is not UTF-8:");
    }

    @Test
    void oversizedServiceDescriptorIsRejected() throws Exception {
        final String service = "META-INF/services/example.Service";
        final Bundle bundle = bundle(
                "oversizedservice",
                "external.oversized-service",
                "helper-",
                "-resource",
                Map.of(service, "thirdparty.oversized_service.Root\n"),
                Map.of(service, "x".repeat(1024 * 1024 + 1))
        );
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(() -> build(
                workspace,
                bundle.definition().id(),
                directory.resolve("oversized-service-app")
        )).isInstanceOf(IOException.class)
                .hasMessageStartingWith("DEPENDENCY_ENTRY_CONFLICT: Service descriptor exceeds 1048576 bytes.");
    }

    @Test
    void identicalReachableEntriesAreStoredOnce() throws Exception {
        final String shared = "thirdparty/shared.txt";
        final Bundle bundle = bundle(
                "identical",
                "external.identical",
                "helper-",
                "-resource",
                Map.of(shared, "same"),
                Map.of(shared, "same")
        );
        final Workspace workspace = workspace(List.of(bundle));

        final ApplicationBuilder.Artifact application = build(
                workspace,
                bundle.definition().id(),
                directory.resolve("identical-app")
        );

        try (JarFile jar = new JarFile(application.jar().toFile())) {
            assertThat(new String(jar.getInputStream(jar.getJarEntry(shared)).readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("same");
        }
    }

    @Test
    void signedDependencyMetadataIsRejected() throws Exception {
        final Bundle bundle = bundle(
                "signed",
                "external.signed",
                "helper-",
                "-resource",
                Map.of("META-INF/EXAMPLE.SF", "signature"),
                Map.of()
        );
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(() -> build(
                workspace,
                bundle.definition().id(),
                directory.resolve("signed-app")
        )).isInstanceOf(IOException.class)
                .hasMessageStartingWith("DEPENDENCY_ARTIFACT_UNSUPPORTED_METADATA:");
    }

    @Test
    void rsaSignatureMetadataIsRejected() throws Exception {
        assertDependencyEntryRejected("rsa", "META-INF/EXAMPLE.RSA", "DEPENDENCY_ARTIFACT_UNSUPPORTED_METADATA:");
    }

    @Test
    void dsaSignatureMetadataIsRejected() throws Exception {
        assertDependencyEntryRejected("dsa", "META-INF/EXAMPLE.DSA", "DEPENDENCY_ARTIFACT_UNSUPPORTED_METADATA:");
    }

    @Test
    void ecSignatureMetadataIsRejected() throws Exception {
        assertDependencyEntryRejected("ec", "META-INF/EXAMPLE.EC", "DEPENDENCY_ARTIFACT_UNSUPPORTED_METADATA:");
    }

    @Test
    void moduleMetadataIsRejected() throws Exception {
        assertDependencyEntryRejected("module", "module-info.class", "DEPENDENCY_ARTIFACT_UNSUPPORTED_METADATA:");
    }

    @Test
    void absoluteDependencyEntryIsRejected() throws Exception {
        assertDependencyEntryRejected("absolute", "/absolute.txt", "DEPENDENCY_LOCAL_ENTRY_UNSAFE:");
    }

    @Test
    void backslashDependencyEntryIsRejected() throws Exception {
        assertDependencyEntryRejected("backslash", "unsafe\\entry.txt", "DEPENDENCY_LOCAL_ENTRY_UNSAFE:");
    }

    @Test
    void parentTraversalDependencyEntryIsRejected() throws Exception {
        assertDependencyEntryRejected("parent", "unsafe/../entry.txt", "DEPENDENCY_LOCAL_ENTRY_UNSAFE:");
    }

    @Test
    void currentDirectoryDependencyEntryIsRejected() throws Exception {
        assertDependencyEntryRejected("current", "unsafe/./entry.txt", "DEPENDENCY_LOCAL_ENTRY_UNSAFE:");
    }

    @Test
    void emptyPathSegmentDependencyEntryIsRejected() throws Exception {
        assertDependencyEntryRejected("segment", "unsafe//entry.txt", "DEPENDENCY_LOCAL_ENTRY_UNSAFE:");
    }

    @Test
    void equivalentLockedInputsProduceIdenticalApplicationJars() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));

        final ApplicationBuilder.Artifact first = build(workspace, "external.alpha", directory.resolve("first"));
        final ApplicationBuilder.Artifact second = build(workspace, "external.alpha", directory.resolve("second"));

        assertThat(Files.readAllBytes(first.jar())).isEqualTo(Files.readAllBytes(second.jar()));
    }

    @Test
    void existingContentAddressedApplicationIsVerifiedAndReused() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("reuse"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);

        final ApplicationBuilder.Artifact second = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(first.reused()).isFalse();
        assertThat(second.reused()).isTrue();
        assertThat(second.jar()).isEqualTo(first.jar());
        assertThat(second.fingerprint()).isEqualTo(first.fingerprint());
    }

    @Test
    void cachedApplicationWithChangedGeneratedSourceIsRebuilt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("cache-source"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        Files.writeString(first.source(), "corrupt", StandardCharsets.UTF_8);

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(Files.readString(rebuilt.source())).isEqualTo(compiled.productionApplicationSource());
    }

    @Test
    void cachedDevelopmentApplicationWithChangedLauncherSourceIsRebuilt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("cache-development-source"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.build(project, compiled);
        final Path launcher = first.directory().resolve(
                "src/dev/nanonative/railix/core/project/RailixDevelopmentApplication.java"
        );
        Files.writeString(launcher, "corrupt", StandardCharsets.UTF_8);

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.build(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(Files.readString(launcher)).isEqualTo(compiled.developmentLauncherSource());
    }

    @Test
    void cachedApplicationWithMissingGeneratedClassIsRebuilt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("cache-missing-class"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        final Path generatedClass = first.classes().resolve(
                "dev/nanonative/railix/core/project/RailixApplication.class"
        );
        Files.delete(generatedClass);

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(generatedClass).isRegularFile();
    }

    @Test
    void cachedApplicationWithChangedGeneratedClassIsRebuilt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("cache-changed-class"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        final Path generatedClass = first.classes().resolve(
                "dev/nanonative/railix/core/project/RailixApplication.class"
        );
        final byte[] expected = Files.readAllBytes(generatedClass);
        Files.write(generatedClass, new byte[]{0});

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(Files.readAllBytes(generatedClass)).isEqualTo(expected);
    }

    @Test
    void cachedApplicationWithUnexpectedGeneratedClassIsRebuilt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("cache-extra-class"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        final Path unexpected = first.classes().resolve("unexpected/Extra.class");
        Files.createDirectories(unexpected.getParent());
        Files.write(unexpected, new byte[]{0});

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(unexpected).doesNotExist();
    }

    @Test
    void cachedApplicationWithMissingJarIsRebuilt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("cache-missing-jar"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        Files.delete(first.jar());

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(runJar(rebuilt.jar(), "CACHE").output()).isEqualTo("\"helper-CACHE-resource\"");
    }

    @Test
    void cachedApplicationWithAnUnexpectedEntryIsRebuilt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("cache-extra"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        rewriteJar(first.jar(), Map.of(), Map.of("unexpected.txt", "changed".getBytes(StandardCharsets.UTF_8)));

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(rebuilt.fingerprint()).isEqualTo(first.fingerprint());
        try (JarFile jar = new JarFile(rebuilt.jar().toFile())) {
            assertThat(jar.getJarEntry("unexpected.txt")).isNull();
        }
    }

    @Test
    void cachedApplicationWithChangedEntryBytesIsRebuilt() throws Exception {
        final Bundle bundle = bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of());
        final Workspace workspace = workspace(List.of(bundle));
        final Path project = project(directory.resolve("cache-content"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        rewriteJar(first.jar(), Map.of(
                bundle.resource(), "changed".getBytes(StandardCharsets.UTF_8)
        ), Map.of());

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(rebuilt.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(runJar(rebuilt.jar(), "CACHE").output()).isEqualTo("\"helper-CACHE-resource\"");
    }

    @Test
    void cachedApplicationWithChangedManifestIsRebuilt() throws Exception {
        final Workspace workspace = workspace(List.of(bundle("alpha", "external.alpha", "helper-", "-resource", Map.of(), Map.of())));
        final Path project = project(directory.resolve("cache-manifest"), "external.alpha");
        final CompileResult.Compiled compiled = compile(workspace, "external.alpha");
        final ApplicationBuilder.Artifact first = ApplicationBuilder.buildProduction(project, compiled);
        rewriteJar(first.jar(), Map.of(
                JarFile.MANIFEST_NAME,
                "Manifest-Version: 1.0\r\nMain-Class: changed.Main\r\n\r\n".getBytes(StandardCharsets.UTF_8)
        ), Map.of());

        final ApplicationBuilder.Artifact rebuilt = ApplicationBuilder.buildProduction(project, compiled);

        assertThat(rebuilt.reused()).isFalse();
        assertThat(rebuilt.fingerprint()).isEqualTo(first.fingerprint());
        assertThat(runJar(rebuilt.jar(), "CACHE").exitCode()).isZero();
    }

    @Test
    void applicationBuildMemoryDoesNotScaleWithALargeBundleResource() throws Exception {
        final long resourceBytes = 128L * 1024 * 1024;
        final Bundle bundle = largeBundle(resourceBytes);
        final Workspace workspace = workspace(List.of(bundle));
        final Path project = project(directory, bundle.definition().id());
        final LinkedHashSet<Path> classpath = new LinkedHashSet<>(List.of(
                location(ThirdPartyBuildProbe.class),
                location(ApplicationBuilder.class),
                location(ProjectCompiler.class),
                location(StepCatalog.class),
                location(StandardLibrary.class),
                location(DevelopmentRuntime.class)
        ));
        final Process process = RailixPackageIT.instrumentJava(new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms32m",
                "-Xmx96m",
                "-cp",
                classpath.stream().map(Path::toString)
                        .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator)),
                ThirdPartyBuildProbe.class.getName(),
                project.toString(),
                workspace.lock().toString(),
                workspace.store().toString()
        )).redirectErrorStream(true).start();
        final ProcessResult result = awaitProcess(process, Duration.ofSeconds(120));

        assertThat(result.exitCode()).as(result.output()).isZero();
        final Path application = Path.of(result.output().lines().reduce((first, second) -> second).orElseThrow());
        try (JarFile jar = new JarFile(application.toFile())) {
            assertThat(jar.getJarEntry("thirdparty/large-resource.bin").getSize()).isEqualTo(resourceBytes);
        }
    }

    private Workspace workspace(final List<Bundle> bundles) throws Exception {
        final Path store = directory.resolve("railix-home/artifacts");
        Files.createDirectories(store);
        final Map<String, RailixValue> artifacts = new TreeMap<>();
        for (final Bundle bundle : bundles) {
            for (final Path artifact : List.of(bundle.root(), bundle.helper())) {
                final long size = Files.size(artifact);
                final String digest = digest(artifact);
                Files.copy(artifact, store.resolve(digest + ".jar"));
                artifacts.put(digest, RailixValue.object(Map.of(
                        "digest", RailixValue.string("sha256:" + digest),
                        "origin", RailixValue.string("file:" + artifact.getFileName()),
                        "size", RailixValue.number(size)
                )));
            }
        }
        final List<RailixValue> lockedBundles = bundles.stream()
                .sorted(Comparator.comparing(bundle -> digest(bundle.root())))
                .<RailixValue>map(bundle -> RailixValue.object(Map.of(
                        "artifact", RailixValue.string("sha256:" + digest(bundle.root())),
                        "runtime", RailixValue.array(List.of(
                                RailixValue.string("sha256:" + digest(bundle.helper()))
                        )),
                        "steps", RailixValue.array(List.of(lockedStep(bundle)))
                )))
                .toList();
        final RailixValue lockValue = RailixValue.object(Map.of(
                "format", RailixValue.number(1),
                "artifacts", RailixValue.array(new ArrayList<>(artifacts.values())),
                "bundles", RailixValue.array(lockedBundles)
        ));
        final Path lock = directory.resolve("railix.dependencies.lock.json");
        Files.writeString(lock, RailixJson.write(lockValue), StandardCharsets.UTF_8);
        return new Workspace(lock, store);
    }

    private static RailixValue.ObjectValue lockedStep(final Bundle bundle) {
        return RailixValue.object(Map.of(
                "contract", RailixValue.string(bundle.contractDigest()),
                "id", RailixValue.string(bundle.definition().id()),
                "implementation", RailixValue.string(bundle.implementation()),
                "implementation_entry", RailixValue.string(bundle.rootClass()),
                "version", RailixValue.string(bundle.definition().version())
        ));
    }

    private static void replaceLockedStepField(
            final Workspace workspace,
            final Bundle bundle,
            final String field,
            final RailixValue value
    ) throws IOException {
        final Map<String, RailixValue> fields = new LinkedHashMap<>(lockedStep(bundle).values());
        fields.put(field, value);
        replaceLockedSteps(workspace, List.of(RailixValue.object(fields)));
    }

    private static void replaceLockedSteps(
            final Workspace workspace,
            final List<RailixValue> steps
    ) throws IOException {
        final RailixJson.Result parsed = RailixJson.parse(Files.readString(workspace.lock(), StandardCharsets.UTF_8));
        if (!(parsed instanceof RailixJson.Parsed valid)
                || !(valid.value() instanceof RailixValue.ObjectValue root)) {
            throw new IOException("Test dependency lock is invalid.");
        }
        final RailixValue.ArrayValue bundles = (RailixValue.ArrayValue) root.values().get("bundles");
        final RailixValue.ObjectValue bundle = (RailixValue.ObjectValue) bundles.values().getFirst();
        final Map<String, RailixValue> bundleFields = new LinkedHashMap<>(bundle.values());
        bundleFields.put("steps", RailixValue.array(steps));
        final Map<String, RailixValue> rootFields = new LinkedHashMap<>(root.values());
        rootFields.put("bundles", RailixValue.array(List.of(RailixValue.object(bundleFields))));
        Files.writeString(
                workspace.lock(),
                RailixJson.write(RailixValue.object(rootFields)),
                StandardCharsets.UTF_8
        );
    }

    private Workspace lockedArtifact(final Path artifact, final String name) throws IOException {
        final Path home = directory.resolve(name + "-home");
        final Path store = home.resolve("artifacts");
        Files.createDirectories(store);
        final String digest = digest(artifact);
        Files.copy(artifact, store.resolve(digest + ".jar"));
        final RailixValue lockValue = RailixValue.object(Map.of(
                "format", RailixValue.number(1),
                "artifacts", RailixValue.array(List.of(RailixValue.object(Map.of(
                        "digest", RailixValue.string("sha256:" + digest),
                        "origin", RailixValue.string("file:" + artifact.getFileName()),
                        "size", RailixValue.number(Files.size(artifact))
                )))),
                "bundles", RailixValue.array(List.of())
        ));
        final Path lock = directory.resolve(name + ".lock.json");
        Files.writeString(lock, RailixJson.write(lockValue), StandardCharsets.UTF_8);
        return new Workspace(lock, store);
    }

    private Bundle bundle(
            final String name,
            final String stepId,
            final String prefix,
            final String resource,
            final Map<String, String> rootExtras,
            final Map<String, String> helperExtras
    ) throws Exception {
        return bundle(name, stepId, prefix, resource, rootExtras, helperExtras, "", false, "");
    }

    private Bundle noisyBundle(
            final String name,
            final String startupOutput,
            final boolean floodOnRun
    ) throws Exception {
        return bundle(
                name,
                "external." + name,
                "helper-",
                "-resource",
                Map.of(),
                Map.of(),
                startupOutput,
                floodOnRun,
                ""
        );
    }

    private Bundle interferingBundle(final String name, final String interference) throws Exception {
        return bundle(
                name,
                "external." + name,
                "helper-",
                "-resource",
                Map.of(),
                Map.of(),
                "",
                false,
                interference
        );
    }

    private Bundle bundle(
            final String name,
            final String stepId,
            final String prefix,
            final String resource,
            final Map<String, String> rootExtras,
            final Map<String, String> helperExtras,
            final String startupOutput,
            final boolean floodOnRun,
            final String callbackInterference
    ) throws Exception {
        final Path root = directory.resolve("fixture-" + name);
        final String packageName = "thirdparty." + name;
        final String packagePath = packageName.replace('.', '/');
        final Path helperSource = source(root, packageName, "Helper", """
                package %s;
                public final class Helper {
                    private Helper() {}
                    public static String prefix() { return %s; }
                }
                """.formatted(packageName, javaString(prefix)));
        final Path helperClasses = root.resolve("helper-classes");
        compileJava(helperSource, helperClasses, List.of());
        final Path helperJar = root.resolve("helper.jar");
        final Map<String, byte[]> helperEntries = classEntries(helperClasses);
        helperExtras.forEach((entry, value) -> helperEntries.put(entry, value.getBytes(StandardCharsets.UTF_8)));
        jar(helperJar, helperEntries);

        final String implementation = packageName + ".Handlers.BundleStep";
        final String implementationEntry = packagePath + "/Handlers$BundleStep.class";
        final StepDefinition definition = StepDefinition.named(stepId, "1")
                .receive("value", ValueShape.STRING)
                .returns("value", ValueShape.STRING)
                .run(RuntimeBoundaryProbeStep.class);
        final String contractDigest = "sha256:" + sha256(
                StepContractJson.write(definition).getBytes(StandardCharsets.UTF_8)
        );
        final Path stepSource = source(root, packageName, "Handlers", """
                package %s;
                import dev.nanonative.railix.core.step.StepHandler;
                import dev.nanonative.railix.core.step.StepInput;
                import dev.nanonative.railix.core.step.StepResult;
                import dev.nanonative.railix.core.value.RailixValue;
                import java.io.InputStream;
                import java.nio.charset.StandardCharsets;
                public final class Handlers {
                    private Handlers() {}
                    public static final class BundleStep implements StepHandler {
                        static {
                            final String output = %s;
                            if (!output.isEmpty()) {
                                System.out.print(output);
                                System.out.flush();
                            }
                            final String interference = %s;
                            if (!interference.isEmpty()) {
                                interfere(interference);
                            }
                        }
                        private static void interfere(final String interference) {
                            try {
                                final java.io.ByteArrayOutputStream startup = new java.io.ByteArrayOutputStream(128);
                                final java.io.InputStream ownership = System.in;
                                boolean complete = false;
                                for (int index = 0; index < 128; index++) {
                                    final int next = ownership.read();
                                    if (next < 0) {
                                        throw new java.io.IOException("Creator startup frame ended early.");
                                    }
                                    if (next == 10) {
                                        complete = true;
                                        break;
                                    }
                                    startup.write(next);
                                }
                                if (!complete) {
                                    throw new java.io.IOException("Creator startup frame exceeded 128 bytes.");
                                }
                                final String frame = startup.toString(StandardCharsets.UTF_8);
                                System.setIn(new java.io.SequenceInputStream(
                                        new java.io.ByteArrayInputStream(
                                                (frame + (char) 10).getBytes(StandardCharsets.UTF_8)
                                        ),
                                        ownership
                                ));
                                final String[] parts = frame.split(" ", -1);
                                try (java.net.Socket callback = new java.net.Socket(
                                        java.net.InetAddress.getLoopbackAddress(),
                                        Integer.parseInt(parts[2])
                                )) {
                                    final String response = switch (interference) {
                                        case "wrong-token" -> "READY wrong-token 1" + (char) 10;
                                        case "partial" -> "READY partial";
                                        case "oversized" -> "x".repeat(129) + (char) 10;
                                        case "stalled" -> "";
                                        case "wrong-prefix" -> "WRONG " + parts[1] + " 1" + (char) 10;
                                        case "empty-token" -> "READY  1" + (char) 10;
                                        case "extra-field" -> "READY " + parts[1] + " 1 extra" + (char) 10;
                                        case "empty-port" -> "READY " + parts[1] + " " + (char) 10;
                                        case "non-numeric-port" -> "READY " + parts[1] + " one" + (char) 10;
                                        case "zero-port" -> "READY " + parts[1] + " 0" + (char) 10;
                                        case "high-port" -> "READY " + parts[1] + " 65536" + (char) 10;
                                        case "overflow-port" -> "READY " + parts[1]
                                                + " 999999999999999999999" + (char) 10;
                                        case "control-byte" -> "READY " + parts[1] + " " + (char) 0 + (char) 10;
                                        case "non-ascii" -> "READY " + parts[1] + " " + (char) 128 + (char) 10;
                                        default -> throw new IllegalArgumentException(
                                                "Unknown callback interference: " + interference
                                        );
                                    };
                                    callback.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                                    callback.getOutputStream().flush();
                                    if (!"stalled".equals(interference)) {
                                        callback.shutdownOutput();
                                    }
                                    callback.setSoTimeout(5_000);
                                    if (callback.getInputStream().read() >= 0) {
                                        throw new java.io.IOException("Creator wrote to an invalid callback.");
                                    }
                                }
                            } catch (final java.io.IOException | NumberFormatException failure) {
                                throw new ExceptionInInitializerError(failure);
                            }
                        }
                        public BundleStep() {}
                        @Override
                        public StepResult run(final StepInput input) {
                            if (%s) {
                                System.out.println("RAILIX_READY malformed");
                                final byte[] output = new byte[8192];
                                for (int chunk = 0; chunk < 4096; chunk++) {
                                    System.out.write(output, 0, output.length);
                                }
                                System.out.flush();
                            }
                            try (InputStream stream = BundleStep.class.getResourceAsStream(%s)) {
                                if (stream == null) {
                                    throw new IllegalStateException("Bundle resource is missing.");
                                }
                                final String suffix = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                                return StepResult.outcome(input.primaryOutcome()).output(
                                        "value", RailixValue.string(Helper.prefix() + input.string("value") + suffix)
                                );
                            } catch (final java.io.IOException failure) {
                                throw new IllegalStateException("Bundle resource cannot be read.", failure);
                            }
                        }
                    }
                }
                """.formatted(
                        packageName,
                        javaString(startupOutput),
                        javaString(callbackInterference),
                        floodOnRun,
                        javaString("/" + packagePath + "/message.txt")
                ));
        final Path stepClasses = root.resolve("step-classes");
        compileJava(stepSource, stepClasses, List.of(location(StepHandler.class), helperJar));
        final Path rootJar = root.resolve("bundle.jar");
        final Map<String, byte[]> rootEntries = classEntries(stepClasses);
        rootEntries.put(packagePath + "/message.txt", resource.getBytes(StandardCharsets.UTF_8));
        rootEntries.put("META-INF/railix/steps.json",
                manifest(definition, implementation, implementationEntry).getBytes(StandardCharsets.UTF_8));
        rootExtras.forEach((entry, value) -> rootEntries.put(entry, value.getBytes(StandardCharsets.UTF_8)));
        jar(rootJar, rootEntries);
        return new Bundle(
                rootJar,
                helperJar,
                definition,
                implementation,
                contractDigest,
                implementationEntry,
                packagePath + "/Helper.class",
                packagePath + "/message.txt"
        );
    }

    private static String manifest(final Bundle bundle) {
        return manifest(bundle.definition(), bundle.implementation(), bundle.rootClass());
    }

    private static void replaceManifest(final Bundle bundle, final String source) throws IOException {
        rewriteJar(
                bundle.root(),
                Map.of("META-INF/railix/steps.json", source.getBytes(StandardCharsets.UTF_8)),
                Map.of()
        );
    }

    private static String manifest(
            final StepDefinition definition,
            final String implementation,
            final String implementationEntry
    ) {
        final RailixValue.ObjectValue contract = StepContractJson.value(definition);
        final String contractDigest = "sha256:" + sha256(
                RailixJson.write(contract).getBytes(StandardCharsets.UTF_8)
        );
        return RailixJson.write(RailixValue.object(Map.of(
                "format", RailixValue.number(1),
                "steps", RailixValue.array(List.of(RailixValue.object(Map.of(
                        "contract", contract,
                        "contract_digest", RailixValue.string(contractDigest),
                        "implementation", RailixValue.string(implementation),
                        "implementation_entry", RailixValue.string(implementationEntry)
                ))))
        )));
    }

    private Bundle largeBundle(final long bytes) throws Exception {
        final Bundle base = bundle("large", "external.large", "large-", "-resource", Map.of(), Map.of());
        final Path target = base.root().resolveSibling("bundle-large.jar");
        try (JarFile source = new JarFile(base.root().toFile());
             OutputStream output = Files.newOutputStream(target);
             JarOutputStream jar = new JarOutputStream(output)) {
            for (final JarEntry entry : source.stream().filter(item -> !item.isDirectory()).toList()) {
                final JarEntry copy = new JarEntry(entry.getName());
                copy.setTime(0L);
                jar.putNextEntry(copy);
                try (InputStream input = source.getInputStream(entry)) {
                    input.transferTo(jar);
                }
                jar.closeEntry();
            }
            final JarEntry resource = new JarEntry("thirdparty/large-resource.bin");
            resource.setTime(0L);
            jar.putNextEntry(resource);
            final byte[] buffer = new byte[8192];
            long state = 0x4d595df4d0f33173L;
            long remaining = bytes;
            while (remaining > 0) {
                final int length = (int) Math.min(buffer.length, remaining);
                for (int index = 0; index < length; index++) {
                    state ^= state << 13;
                    state ^= state >>> 7;
                    state ^= state << 17;
                    buffer[index] = (byte) state;
                }
                jar.write(buffer, 0, length);
                remaining -= length;
            }
            jar.closeEntry();
        }
        return new Bundle(
                target,
                base.helper(),
                base.definition(),
                base.implementation(),
                base.contractDigest(),
                base.rootClass(),
                base.helperClass(),
                base.resource()
        );
    }

    private CompileResult.Compiled compile(final Workspace workspace, final String stepId) throws Exception {
        final CompileResult result = ProjectCompiler.compileApplication(projectSource(stepId), workspace.catalog());
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        return (CompileResult.Compiled) result;
    }

    private ApplicationBuilder.Artifact build(
            final Workspace workspace,
            final String stepId,
            final Path directory
    ) throws Exception {
        return ApplicationBuilder.buildProduction(project(directory, stepId), compile(workspace, stepId));
    }

    private ProcessResult run(final Workspace workspace, final String stepId, final String argument) throws Exception {
        return runJar(build(workspace, stepId, directory.resolve("run")).jar(), argument);
    }

    private static Path project(final Path directory, final String stepId) throws IOException {
        Files.createDirectories(directory);
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, projectSource(stepId), StandardCharsets.UTF_8);
        return project;
    }

    private static String projectSource(final String stepId) {
        return """
                {"format":1,"id":"external-step-app","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"external","payload":["value"]
                  }]},
                  {"id":"external","use":"%s","inputs":{},
                    "receives":{"value":["context","payload","arguments",0]},
                    "returns":{"value":["context","result"]}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"external"},
                  {"from":"external.next","to":"end"}
                ]}
                """.formatted(stepId);
    }

    private static Path source(
            final Path root,
            final String packageName,
            final String className,
            final String source
    ) throws IOException {
        final Path file = root.resolve("src").resolve(packageName.replace('.', '/')).resolve(className + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
        return file;
    }

    private static void compileJava(
            final Path source,
            final Path classes,
            final List<Path> dependencies
    ) throws IOException {
        Files.createDirectories(classes);
        final var compiler = ToolProvider.getSystemJavaCompiler();
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var files = compiler.getStandardFileManager(diagnostics, java.util.Locale.ROOT, StandardCharsets.UTF_8)) {
            final List<String> options = new ArrayList<>(List.of(
                    "-proc:none", "-g:none", "--release", Integer.toString(Runtime.version().feature()),
                    "-d", classes.toString()
            ));
            if (!dependencies.isEmpty()) {
                options.add("-classpath");
                options.add(dependencies.stream().map(Path::toString)
                        .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator)));
            }
            final boolean success = Boolean.TRUE.equals(compiler.getTask(
                    null, files, diagnostics, options, null, files.getJavaFileObjects(source)
            ).call());
            assertThat(success).as(diagnostics.getDiagnostics().toString()).isTrue();
        }
    }

    private static Map<String, byte[]> classEntries(final Path classes) throws IOException {
        final Map<String, byte[]> entries = new TreeMap<>();
        try (var files = Files.walk(classes)) {
            for (final Path file : files.filter(Files::isRegularFile).toList()) {
                entries.put(classes.relativize(file).toString().replace(java.io.File.separatorChar, '/'),
                        Files.readAllBytes(file));
            }
        }
        return entries;
    }

    private static void jar(final Path target, final Map<String, byte[]> source) throws IOException {
        Files.createDirectories(target.getParent());
        try (OutputStream output = Files.newOutputStream(target); JarOutputStream jar = new JarOutputStream(output)) {
            for (final Map.Entry<String, byte[]> entry : new TreeMap<>(source).entrySet()) {
                final JarEntry item = new JarEntry(entry.getKey());
                item.setTime(0L);
                jar.putNextEntry(item);
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
    }

    private static void patchCentralDirectorySizes(final Path jar, final List<Long> sizes) throws IOException {
        final byte[] bytes = Files.readAllBytes(jar);
        int sizeIndex = 0;
        for (int index = 0; index <= bytes.length - 46; index++) {
            if (littleEndianInt(bytes, index) != 0x02014b50) {
                continue;
            }
            if (sizeIndex >= sizes.size()) {
                throw new IOException("Test JAR has more central-directory entries than expected.");
            }
            final long size = sizes.get(sizeIndex++);
            for (int offset = 0; offset < 4; offset++) {
                bytes[index + 24 + offset] = (byte) (size >>> (offset * 8));
            }
        }
        if (sizeIndex != sizes.size()) {
            throw new IOException("Test JAR central-directory entry count is invalid.");
        }
        Files.write(jar, bytes);
    }

    private static void rewriteJar(
            final Path source,
            final Map<String, byte[]> replacements,
            final Map<String, byte[]> additions
    ) throws IOException {
        rewriteJar(source, replacements, additions, Set.of());
    }

    private static void rewriteJar(
            final Path source,
            final Map<String, byte[]> replacements,
            final Map<String, byte[]> additions,
            final Set<String> removals
    ) throws IOException {
        final Path target = source.resolveSibling(source.getFileName() + ".changed");
        try (JarFile input = new JarFile(source.toFile());
             OutputStream output = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW);
             JarOutputStream jar = new JarOutputStream(output)) {
            final var entries = input.entries();
            while (entries.hasMoreElements()) {
                final JarEntry existing = entries.nextElement();
                if (existing.isDirectory() || removals.contains(existing.getName())) {
                    continue;
                }
                final JarEntry entry = new JarEntry(existing.getName());
                entry.setTime(0L);
                jar.putNextEntry(entry);
                final byte[] replacement = replacements.get(existing.getName());
                if (replacement == null) {
                    try (InputStream content = input.getInputStream(existing)) {
                        content.transferTo(jar);
                    }
                } else {
                    jar.write(replacement);
                }
                jar.closeEntry();
            }
            for (final Map.Entry<String, byte[]> addition : new TreeMap<>(additions).entrySet()) {
                final JarEntry entry = new JarEntry(addition.getKey());
                entry.setTime(0L);
                jar.putNextEntry(entry);
                jar.write(addition.getValue());
                jar.closeEntry();
            }
        }
        Files.move(target, source, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static int littleEndianInt(final byte[] bytes, final int offset) {
        return Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8
                | Byte.toUnsignedInt(bytes[offset + 2]) << 16
                | Byte.toUnsignedInt(bytes[offset + 3]) << 24;
    }

    private static Path location(final Class<?> type) throws IOException {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
        } catch (final URISyntaxException exception) {
            throw new IOException("Test dependency location is invalid.", exception);
        }
    }

    private static ProcessResult runJar(final Path jar, final String argument) throws Exception {
        final Process process = RailixPackageIT.instrumentJava(new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString(), argument
        )).redirectErrorStream(true).start();
        return awaitProcess(process, Duration.ofSeconds(20));
    }

    private static ProcessResult awaitProcess(final Process process, final Duration timeout) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream(PROCESS_OUTPUT_LIMIT);
        final Thread reader = Thread.ofVirtual().name("railix-test-process-output").start(() ->
                drain(process.getInputStream(), output)
        );
        boolean interrupted = Thread.interrupted();
        final Wait execution = waitFor(process, timeout);
        interrupted |= execution.interrupted();
        Wait termination = execution;
        if (!execution.complete()) {
            process.destroyForcibly();
            termination = waitFor(process, Duration.ofSeconds(20));
            interrupted |= termination.interrupted();
        }
        Wait drained = waitFor(reader, Duration.ofSeconds(20));
        interrupted |= drained.interrupted();
        if (!drained.complete()) {
            close(process.getInputStream());
            final Wait forcedDrain = waitFor(reader, Duration.ofSeconds(20));
            interrupted |= forcedDrain.interrupted();
            drained = forcedDrain;
        }
        close(process.getOutputStream());
        close(process.getInputStream());
        close(process.getErrorStream());
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        final String captured;
        synchronized (output) {
            captured = output.toString(StandardCharsets.UTF_8).strip();
        }
        if (!execution.complete()) {
            throw new IOException("Forked process exceeded " + timeout + ". " + captured);
        }
        if (!termination.complete()) {
            throw new IOException("Forked process did not terminate. " + captured);
        }
        if (!drained.complete()) {
            throw new IOException("Forked process output reader did not terminate. " + captured);
        }
        return new ProcessResult(process.exitValue(), captured);
    }

    private static void drain(final InputStream input, final ByteArrayOutputStream output) {
        try (input) {
            final byte[] buffer = new byte[8_192];
            for (int length = input.read(buffer); length >= 0; length = input.read(buffer)) {
                synchronized (output) {
                    final int retained = Math.min(length, PROCESS_OUTPUT_LIMIT - output.size());
                    if (retained > 0) {
                        output.write(buffer, 0, retained);
                    }
                }
            }
        } catch (final IOException ignored) {
            // Process termination remains the lifecycle authority.
        }
    }

    private static Wait waitFor(final Process process, final Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        boolean interrupted = false;
        while (process.isAlive() && System.nanoTime() < deadline) {
            try {
                process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (final InterruptedException ignored) {
                interrupted = true;
            }
        }
        return new Wait(!process.isAlive(), interrupted);
    }

    private static Wait waitFor(final Thread thread, final Duration timeout) {
        final long deadline = System.nanoTime() + timeout.toNanos();
        boolean interrupted = false;
        while (thread.isAlive() && System.nanoTime() < deadline) {
            try {
                thread.join(Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
            } catch (final InterruptedException ignored) {
                interrupted = true;
            }
        }
        return new Wait(!thread.isAlive(), interrupted);
    }

    private static void close(final AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (final Exception ignored) {
            // Process termination remains the lifecycle authority.
        }
    }

    private void assertDependencyEntryRejected(
            final String name,
            final String entry,
            final String diagnostic
    ) throws Exception {
        final Bundle bundle = bundle(
                name,
                "external." + name,
                "helper-",
                "-resource",
                Map.of(entry, "metadata"),
                Map.of()
        );
        final Workspace workspace = workspace(List.of(bundle));

        assertThatThrownBy(() -> build(
                workspace,
                bundle.definition().id(),
                directory.resolve(name + "-app")
        )).isInstanceOf(IOException.class)
                .hasMessageStartingWith(diagnostic);
    }

    private void assertCreatorRuns(final Bundle bundle, final String argument) throws Exception {
        final Workspace workspace = workspace(List.of(bundle));
        final Path project = project(directory, bundle.definition().id());
        try (CreatorServer creator = CreatorServer.start(0, project, workspace.railixHome());
             HttpClient client = HttpClient.newHttpClient()) {
            final HttpResponse<String> response = runCreator(client, creator, argument);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.body()).contains("\"result\":\"helper-" + argument + "-resource\"");
        }
    }

    private static HttpResponse<String> runCreator(
            final HttpClient client,
            final CreatorServer creator,
            final String argument
    ) throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(creator.baseUri().resolve("/api/run/command"))
                        .timeout(Duration.ofSeconds(8))
                        .header("Content-Type", "application/json")
                        .header(
                                "X-Railix-Creator-Token",
                                creator.baseUri().getRawFragment().substring("token=".length())
                        )
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"payload\":{\"arguments\":[\"" + argument + "\"]}}",
                                StandardCharsets.UTF_8
                        ))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
    }

    private static long applicationPid(final HttpClient client, final CreatorServer creator)
            throws IOException, InterruptedException {
        final HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(creator.baseUri().resolve("/api/application"))
                        .timeout(Duration.ofSeconds(8))
                        .header(
                                "X-Railix-Creator-Token",
                                creator.baseUri().getRawFragment().substring("token=".length())
                        )
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
        final RailixJson.Result parsed = RailixJson.parse(response.body());
        assertThat(parsed).isInstanceOf(RailixJson.Parsed.class);
        final RailixValue.ObjectValue value = (RailixValue.ObjectValue) ((RailixJson.Parsed) parsed).value();
        return ((RailixValue.NumberValue) value.values().get("pid")).value().longValueExact();
    }

    private static boolean awaitStopped(final long pid) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true)) {
                return true;
            }
            Thread.sleep(20);
        }
        return ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true);
    }

    private static String digest(final Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[8192];
            for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final IOException exception) {
            throw new IllegalStateException(exception);
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] classBytes(final Class<?> type) throws IOException {
        try (InputStream input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            if (input == null) {
                throw new IOException("Test class bytes are unavailable: " + type.getName() + ".");
            }
            return input.readAllBytes();
        }
    }

    private static String javaString(final String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t") + "\"";
    }

    private record Workspace(Path lock, Path store) {
        StepCatalog catalog() throws StepCatalog.DependencyException {
            return StandardLibrary.catalog().install(lock, store);
        }

        Path stored(final Path artifact) {
            return store.resolve(digest(artifact) + ".jar");
        }

        Path railixHome() {
            return store.getParent();
        }
    }

    private record Bundle(
            Path root,
            Path helper,
            StepDefinition definition,
            String implementation,
            String contractDigest,
            String rootClass,
            String helperClass,
            String resource
    ) {
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private record Wait(boolean complete, boolean interrupted) {
    }
}
