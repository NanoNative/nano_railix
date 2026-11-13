package dev.nanonative.railix.creator;

import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepContractJson;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import dev.nanonative.railix.core.project.ProjectCompiler;
import dev.nanonative.railix.core.project.CompileResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import thirdparty.production.DynamicClassStep;
import thirdparty.production.PackagingSteps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class ProviderPortabilityE2eTest {
    @TempDir
    Path directory;

    @Test
    void runtimePackagingExcludesAuthoringClassesAndUnusedDefaultHandlers() throws Exception {
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, CreatorProjects.lowercaseCli());
        final var compiled = (CompileResult.Compiled) ProjectFiles.build(project, directory.resolve("home"), false).result();
        final var production = ApplicationBuilder.buildProduction(project, compiled);
        final var development = ApplicationBuilder.build(project, compiled);
        final java.util.Set<String> productionProvider;
        try (var jar = new java.util.jar.JarFile(production.jar().toFile())) {
            final var names = jar.stream().map(JarEntry::getName).toList();
            assertThat(names).noneMatch(name -> name.contains("StandardLibrary") || name.contains("PrimitiveSteps")
                    || name.contains("HttpStepHandlers") || name.contains("StepDefinition"));
            productionProvider = names.stream().filter(name -> name.startsWith("dev/nanonative/railix/stdlib/"))
                    .collect(java.util.stream.Collectors.toSet());
        }
        try (var jar = new java.util.jar.JarFile(development.artifact().jar().toFile())) {
            assertThat(jar.stream().map(JarEntry::getName).filter(name -> name.startsWith("dev/nanonative/railix/stdlib/"))
                    .collect(java.util.stream.Collectors.toSet())).isEqualTo(productionProvider);
        }
        final var run = CreatorFirstExecutionArtifacts.runJar(production.jar(), "INDEPENDENT");
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).isEqualTo("\"independent\"");
    }

    @Test
    void nestedPrivateAccessAndLambdaDependenciesSurviveWithoutUnrelatedNestMembers() throws Exception {
        final var definition = StepDefinition.named("portable.dynamic", "1")
                .receive("class", ValueShape.STRING).returns("value", ValueShape.STRING)
                .implementation(PackagingSteps.Echo.class);
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, List.of(definition),
                PackagingSteps.Echo.class, PackagingSteps.Helper.class, PackagingSteps.Authoring.class);
        final var compiled = (CompileResult.Compiled) ProjectCompiler.compileApplication(dynamicProject("input"), catalog);
        final var artifact = ApplicationBuilder.buildProduction(directory.resolve("railix.project.json"), compiled);
        try (var jar = new java.util.jar.JarFile(artifact.jar().toFile())) {
            assertThat(jar.getJarEntry("thirdparty/production/PackagingSteps$Authoring.class")).isNull();
            assertThat(jar.getJarEntry("thirdparty/production/PackagingSteps$Helper.class")).isNotNull();
        }
        final var run = CreatorFirstExecutionArtifacts.runJar(artifact.jar(), "input");
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).isEqualTo("\"packaged-input-nested\"");
    }

    @Test
    void combinedDefinitionAndProcessClassPackagesWithoutItsUnusedDefinitionMethods() throws Exception {
        final var definition = StepDefinition.named("portable.dynamic", "1")
                .receive("class", ValueShape.STRING).returns("value", ValueShape.STRING)
                .implementation(PackagingSteps.class);
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, List.of(definition),
                PackagingSteps.class, PackagingSteps.Echo.class, PackagingSteps.Helper.class, PackagingSteps.Authoring.class);
        final var compiled = (CompileResult.Compiled) ProjectCompiler.compileApplication(dynamicProject("input"), catalog);
        final var artifact = ApplicationBuilder.buildProduction(directory.resolve("railix.project.json"), compiled);
        try (var jar = new java.util.jar.JarFile(artifact.jar().toFile());
             var bytes = jar.getInputStream(jar.getJarEntry("thirdparty/production/PackagingSteps.class"))) {
            assertThat(java.lang.classfile.ClassFile.of().parse(bytes.readAllBytes()).methods())
                    .noneMatch(method -> method.methodName().equalsString("definitions"));
            assertThat(jar.getJarEntry("thirdparty/production/PackagingSteps$Authoring.class")).isNull();
        }
        final var run = CreatorFirstExecutionArtifacts.runJar(artifact.jar(), "mixed");
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).isEqualTo("\"packaged-mixed-nested\"");
        final Path dependencies = directory.resolve("jdeps.log");
        final Process inspect = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "jdeps").toString(),
                "-q", "--print-module-deps", artifact.jar().toString()).redirectErrorStream(true)
                .redirectOutput(dependencies.toFile()).start();
        try {
            assertThat(inspect.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(inspect.exitValue()).as(Files.readString(dependencies)).isZero();
        } finally {
            if (inspect.isAlive()) { inspect.destroyForcibly(); inspect.waitFor(); }
        }
        final Path rebuilt = Files.createDirectories(directory.resolve("rebuilt")).resolve("railix.project.json");
        assertThat(Files.mismatch(artifact.jar(), ApplicationBuilder.buildProduction(rebuilt, compiled).jar())).isEqualTo(-1);
    }

    @Test
    void serviceProvidersAndResourcesRemainAvailableFromTheStandaloneApplication() throws Exception {
        final var definition = StepDefinition.named("portable.dynamic", "1")
                .receive("class", ValueShape.STRING).returns("value", ValueShape.STRING)
                .implementation(PackagingSteps.Services.class);
        final Path classes = Files.createDirectories(directory.resolve("classes"));
        for (final Class<?> type : List.of(PackagingSteps.class, PackagingSteps.Services.class,
                PackagingSteps.Text.class, PackagingSteps.TextProvider.class, PackagingSteps.Authoring.class)) {
            final String entry = type.getName().replace('.', '/') + ".class";
            final Path target = classes.resolve(entry);
            Files.createDirectories(target.getParent());
            try (var input = type.getResourceAsStream("/" + entry)) { Files.copy(input, target); }
        }
        Files.writeString(classes.resolve("thirdparty/production/banner.txt"), "-resource");
        final Path service = classes.resolve("META-INF/services/" + PackagingSteps.Text.class.getName());
        Files.createDirectories(service.getParent());
        Files.writeString(service, PackagingSteps.TextProvider.class.getName() + "\n");
        final Path bundle = dev.nanonative.railix.core.step.StepBundle.write(classes, directory.resolve("steps.jar"),
                PackagingSteps.Authoring.class, List.of(definition));
        final Path store = directory.resolve("artifacts");
        final var catalog = GeneratedApplicationFixture.catalog().installSource(StepCatalog.bundleLock(bundle, store), store);
        final var compiled = (CompileResult.Compiled) ProjectCompiler.compileApplication(dynamicProject("service"), catalog);
        final var artifact = ApplicationBuilder.buildProduction(directory.resolve("railix.project.json"), compiled);
        final var run = CreatorFirstExecutionArtifacts.runJar(artifact.jar(), "input");
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).isEqualTo("\"service-input-resource\"");
    }

    @Test
    void declaredReflectionRetainsMethodsNotCalledByTheFlow() throws Exception {
        final var definition = StepDefinition.named("portable.dynamic", "1")
                .receive("class", ValueShape.STRING).returns("value", ValueShape.STRING)
                .dynamicClasses(PackagingSteps.Reflection.class).implementation(PackagingSteps.Reflection.class);
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, List.of(definition), PackagingSteps.Reflection.class);
        final var compiled = (CompileResult.Compiled) ProjectCompiler.compileApplication(dynamicProject("input"), catalog);
        final var artifact = ApplicationBuilder.buildProduction(directory.resolve("railix.project.json"), compiled);
        final var run = CreatorFirstExecutionArtifacts.runJar(artifact.jar(), "input");
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).isEqualTo("\"reflected\"");
    }

    @Test
    void completeDynamicClassDoesNotInitializeUnrelatedNestedAuthoringState() throws Exception {
        final var definition = StepDefinition.named("portable.dynamic", "1")
                .receive("class", ValueShape.STRING).returns("value", ValueShape.STRING)
                .dynamicClasses(PackagingSteps.DynamicOuter.class).implementation(PackagingSteps.DynamicOuter.class);
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, List.of(definition),
                PackagingSteps.DynamicOuter.class, PackagingSteps.DynamicOuter.Definitions.class);
        final var compiled = (CompileResult.Compiled) ProjectCompiler.compileApplication(dynamicProject("input"), catalog);
        final var artifact = ApplicationBuilder.buildProduction(directory.resolve("railix.project.json"), compiled);
        try (var jar = new java.util.jar.JarFile(artifact.jar().toFile())) {
            assertThat(jar.getJarEntry(PackagingSteps.DynamicOuter.Definitions.class.getName().replace('.', '/') + ".class")).isNull();
        }
        final var run = CreatorFirstExecutionArtifacts.runJar(artifact.jar(), "input");
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).isEqualTo("\"dynamic-input\"");
    }

    @Test
    void inheritedStaticCallsAndImplicitEnumMethodsRemainFunctional() throws Exception {
        final var definition = StepDefinition.named("portable.dynamic", "1")
                .receive("class", ValueShape.STRING).returns("value", ValueShape.STRING)
                .implementation(PackagingSteps.Inherited.class);
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, List.of(definition),
                PackagingSteps.Inherited.class, PackagingSteps.Base.class, PackagingSteps.Derived.class, PackagingSteps.Mode.class);
        final var compiled = (CompileResult.Compiled) ProjectCompiler.compileApplication(dynamicProject("input"), catalog);
        final var artifact = ApplicationBuilder.buildProduction(directory.resolve("railix.project.json"), compiled);
        final var run = CreatorFirstExecutionArtifacts.runJar(artifact.jar(), "input");
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).isEqualTo("\"inherited-input-ON\"");
    }

    @Test
    void enumImplementationsCanAlsoContainUnusedDefinitionFactories() throws Exception {
        final var definition = StepDefinition.named("portable.dynamic", "1")
                .receive("class", ValueShape.STRING).returns("value", ValueShape.STRING)
                .implementation(PackagingSteps.EnumProvider.class);
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, List.of(definition), PackagingSteps.EnumProvider.class);
        final var compiled = (CompileResult.Compiled) ProjectCompiler.compileApplication(dynamicProject("input"), catalog);
        final var artifact = ApplicationBuilder.buildProduction(directory.resolve("railix.project.json"), compiled);
        final var run = CreatorFirstExecutionArtifacts.runJar(artifact.jar(), "input");
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).isEqualTo("\"ONLY\"");
    }

    @Test
    void requiredAuthoringInitializationIsRejectedInsteadOfSilentlyRemoved() throws Exception {
        final var definition = StepDefinition.named("portable.dynamic", "1")
                .receive("class", ValueShape.STRING).returns("value", ValueShape.STRING)
                .implementation(PackagingSteps.AuthoringState.class);
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, List.of(definition), PackagingSteps.AuthoringState.class);
        final var compiled = (CompileResult.Compiled) ProjectCompiler.compileApplication(dynamicProject("input"), catalog);
        assertThatThrownBy(() -> ApplicationBuilder.buildProduction(directory.resolve("railix.project.json"), compiled))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("STEP_DEPENDENCY_OWNER_MISSING", "StepDefinition");
    }

    @Test
    void realGenericAndAnnotationTypesSurviveWithoutInterpretingAnnotationTextAsClassNames() throws Exception {
        final var definition = StepDefinition.named("portable.dynamic", "1")
                .receive("class", ValueShape.STRING).returns("value", ValueShape.STRING)
                .implementation(PackagingSteps.Metadata.class);
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, List.of(definition),
                PackagingSteps.Metadata.class, PackagingSteps.Marker.class, PackagingSteps.GenericOuter.Inner.class);
        final var compiled = (CompileResult.Compiled) ProjectCompiler.compileApplication(dynamicProject("input"), catalog);
        final var artifact = ApplicationBuilder.buildProduction(directory.resolve("railix.project.json"), compiled);
        final var run = CreatorFirstExecutionArtifacts.runJar(artifact.jar(), "input");
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("Lnot/a/Class;", "GenericOuter", "java.lang.Integer");
    }

    @Test
    void serializableLambdaRetainsTheCompilersImplicitDeserializationEntrypoint() throws Exception {
        final var definition = StepDefinition.named("portable.dynamic", "1")
                .receive("class", ValueShape.STRING).returns("value", ValueShape.STRING)
                .implementation(PackagingSteps.SerializedLambda.class);
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, List.of(definition), PackagingSteps.SerializedLambda.class);
        final var compiled = (CompileResult.Compiled) ProjectCompiler.compileApplication(dynamicProject("input"), catalog);
        final var artifact = ApplicationBuilder.buildProduction(directory.resolve("railix.project.json"), compiled);
        final var run = CreatorFirstExecutionArtifacts.runJar(artifact.jar(), "input");
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).isEqualTo("\"serialized-input\"");
    }

    @Test
    @org.junit.jupiter.api.Timeout(60)
    void largeMixedProviderWithAThousandStaticHelpersBuildsAndExecutes() throws Exception {
        final Path sources = Files.createDirectories(directory.resolve("src/portable"));
        final Path source = sources.resolve("ManyHelpers.java");
        final StringBuilder sourceText = new StringBuilder("""
                package portable;
                import dev.nanonative.railix.core.step.*;
                import dev.nanonative.railix.core.value.*;
                public final class ManyHelpers {
                  public static java.util.List<StepDefinition> definitions() { return java.util.List.of(); }
                  public static StepResult process(StepInput configuration, StepInput input) {
                    return StepResult.outcome(input.primaryOutcome()).output("value", RailixValue.string(m0(input.string("class"))));
                  }
                """);
        for (int method = 0; method < 1024; method++) sourceText.append("static String m").append(method)
                .append("(String value) { return ").append(method == 1023 ? "\"scaled-\" + value" : "m" + (method + 1) + "(value)")
                .append("; }\n");
        Files.writeString(source, sourceText.append("}\n"));
        final Path classes = Files.createDirectories(directory.resolve("classes"));
        final var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertThat(compiler.run(null, null, null, "-proc:none", "-cp", System.getProperty("java.class.path"),
                "-d", classes.toString(), source.toString())).isZero();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, StepDefinition.class.getClassLoader())) {
            final Class<?> type = Class.forName("portable.ManyHelpers", false, loader);
            final var definition = StepDefinition.named("portable.dynamic", "1")
                    .receive("class", ValueShape.STRING).returns("value", ValueShape.STRING).implementation(type);
            final Path bundle = dev.nanonative.railix.core.step.StepBundle.write(classes, directory.resolve("steps.jar"), type, List.of(definition));
            final Path store = directory.resolve("artifacts");
            final var catalog = GeneratedApplicationFixture.catalog().installSource(StepCatalog.bundleLock(bundle, store), store);
            final var compiled = (CompileResult.Compiled) ProjectCompiler.compileApplication(dynamicProject("input"), catalog);
            final long started = System.nanoTime();
            final var artifact = ApplicationBuilder.buildProduction(directory.resolve("railix.project.json"), compiled);
            System.out.printf(java.util.Locale.ROOT, "PROVIDER_STATIC_CLOSURE helpers=1024 build_ms=%.3f%n", (System.nanoTime() - started) / 1_000_000.0);
            final var run = CreatorFirstExecutionArtifacts.runJar(artifact.jar(), "input");
            assertThat(run.exitCode()).as(run.output()).isZero();
            assertThat(run.output()).isEqualTo("\"scaled-input\"");
        }
    }

    @Test
    void embeddedDefaultsUseVerifiedBundleOwnershipIncludingTheStructuralApp() throws Exception {
        final StepCatalog catalog = ProjectFiles.catalog(directory.resolve("railix.project.json"), directory.resolve("home"));

        assertThat(catalog.find("railix.app")).isPresent();
        assertThat(catalog.definitions()).isNotEmpty().allSatisfy(definition -> {
            if (definition.executable()) {
                final var implementation = catalog.implementation(definition.id()).orElseThrow();
                assertThat(implementation.platformOwned()).as(definition.id()).isFalse();
                assertThat(implementation.artifacts()).as(definition.id()).isNotEmpty();
            }
        });
    }

    @Test
    void declaredDynamicClassesExecuteFromTheArtifactAndProduceABooleanCatalogFilter() throws Exception {
        final var definition = StepDefinition.named("portable.dynamic", "1")
                .receive("class", ValueShape.STRING).returns("value", ValueShape.STRING)
                .dynamicClasses(DynamicClassStep.Value.class).implementation(DynamicClassStep.class);
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, List.of(definition),
                DynamicClassStep.class, DynamicClassStep.Value.class);
        assertThat(catalog.find(definition.id()).orElseThrow().nativeStep()).isFalse();
        final String source = dynamicProject(DynamicClassStep.Value.class.getName());
        final var compiled = ProjectCompiler.compileApplication(source, catalog);
        assertThat(compiled).isInstanceOf(CompileResult.Compiled.class);
        final var unmarked = StepDefinition.named(definition.id(), definition.version())
                .receive("class", ValueShape.STRING).returns("value", ValueShape.STRING).implementation(DynamicClassStep.class);
        final var unmarkedCatalog = GeneratedApplicationFixture.installedCatalog(Files.createDirectories(directory.resolve("unmarked")),
                List.of(unmarked), DynamicClassStep.class, DynamicClassStep.Value.class);
        final var unmarkedCompiled = ProjectCompiler.compileApplication(source, unmarkedCatalog);
        assertThat(unmarkedCompiled).isInstanceOf(CompileResult.Compiled.class);
        assertThat(((CompileResult.Compiled) compiled).productionApplicationSource())
                .isEqualTo(((CompileResult.Compiled) unmarkedCompiled).productionApplicationSource());
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, source);
        final var artifact = ApplicationBuilder.buildProduction(project, (CompileResult.Compiled) compiled);
        final var run = CreatorFirstExecutionArtifacts.runJar(artifact.jar(), DynamicClassStep.Value.class.getName());
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).isEqualTo(RailixJson.write(RailixValue.string(DynamicClassStep.Value.class.getName())));

        try (var creator = CreatorServer.start(0, project, directory.resolve("railix-home"))) {
            final var response = CreatorServerE2eSupport.request(creator.baseUri(), "GET", "/api/catalog", "");
            assertThat(response.statusCode()).isEqualTo(200);
            final var body = (RailixValue.ObjectValue) ((RailixJson.Parsed) RailixJson.parse(response.body())).value();
            final var steps = ((RailixValue.ArrayValue) body.values().get("steps")).values();
            assertThat(steps).filteredOn(value -> ((RailixValue.ObjectValue) value).values().get("id").equals(RailixValue.string(definition.id())))
                    .singleElement().satisfies(value -> assertThat(((RailixValue.ObjectValue) value).values())
                            .containsEntry("native", RailixValue.bool(false)));
            assertThat(steps).filteredOn(value -> ((RailixValue.ObjectValue) value).values().get("id").equals(RailixValue.string("text.lowercase")))
                    .singleElement().satisfies(value -> assertThat(((RailixValue.ObjectValue) value).values())
                            .containsEntry("native", RailixValue.bool(true)));
        }
    }

    @Test
    void missingDeclaredDynamicClassRejectsPackagingRatherThanBorrowingTheHostClasspath() throws Exception {
        final var definition = StepDefinition.named("portable.dynamic", "1")
                .receive("class", ValueShape.STRING).returns("value", ValueShape.STRING)
                .dynamicClasses(DynamicClassStep.Value.class).implementation(DynamicClassStep.class);
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, List.of(definition), DynamicClassStep.class);
        final var compiled = ProjectCompiler.compileApplication(dynamicProject(DynamicClassStep.Value.class.getName()), catalog);
        assertThat(compiled).isInstanceOf(CompileResult.Compiled.class);
        assertThatThrownBy(() -> ApplicationBuilder.buildProduction(directory.resolve("railix.project.json"), (CompileResult.Compiled) compiled))
                .isInstanceOf(java.io.IOException.class).hasMessageStartingWith("STEP_DEPENDENCY_OWNER_MISSING:")
                .hasMessageContaining(DynamicClassStep.Value.class.getName());
    }

    @ParameterizedTest
    @MethodSource("jdkDynamicClasses")
    void jdkDynamicClassIsResolvedWithoutBorrowingTheHostApplicationClasspath(final Class<?> type) throws Exception {
        final var definition = StepDefinition.named("portable.dynamic", "1")
                .receive("class", ValueShape.STRING).returns("value", ValueShape.STRING)
                .dynamicClasses(type).implementation(DynamicClassStep.class);
        final var catalog = GeneratedApplicationFixture.installedCatalog(directory, List.of(definition), DynamicClassStep.class);
        final var compiled = ProjectCompiler.compileApplication(dynamicProject(type.getName()), catalog);
        assertThat(compiled).isInstanceOf(CompileResult.Compiled.class);
        final var artifact = ApplicationBuilder.buildProduction(directory.resolve("railix.project.json"), (CompileResult.Compiled) compiled);
        final var run = CreatorFirstExecutionArtifacts.runJar(artifact.jar(), type.getName());
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).isEqualTo(RailixJson.write(RailixValue.string(type.getName())));
    }

    static java.util.stream.Stream<Class<?>> jdkDynamicClasses() {
        return java.util.stream.Stream.of(java.sql.DriverManager.class, com.sun.tools.javac.Main.class);
    }

    private static String dynamicProject(final String className) {
        return """
                {"format":1,"id":"dynamic-provider","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"dynamic","payload":["%s"]
                  }]},
                  {"id":"dynamic","use":"portable.dynamic","inputs":{},
                   "receives":{"class":["context","payload","arguments",0]},
                   "returns":{"value":["context","result"]}}
                ],"links":[{"from":"app.start","to":"command"},
                  {"from":"command.next","to":"dynamic"},{"from":"dynamic.next","to":"end"}]}
                """.formatted(className);
    }

    @Test
    void standardProviderBuildsOutsideTheRepositoryAndTheApplicationRunsAfterItsCheckoutIsRemoved() throws Exception {
        final Path checkout = Files.createDirectories(directory.resolve("external-provider"));
        final Path sources = checkout.resolve("src");
        final Path original = Path.of("../railix-stdlib/src/main/java").toAbsolutePath().normalize();
        final List<Path> javaFiles = new ArrayList<>();
        try (var files = Files.walk(original)) {
            for (final Path source : files.filter(file -> file.toString().endsWith(".java")).toList()) {
                final Path copied = sources.resolve(original.relativize(source));
                Files.createDirectories(copied.getParent());
                Files.copy(source, copied);
                javaFiles.add(copied);
            }
        }
        final Path api = checkout.resolve("railix-core.jar");
        final Path core = Path.of(StepDefinition.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(core)) Files.copy(core, api);
        else {
            try (var jar = new JarOutputStream(Files.newOutputStream(api)); var files = Files.walk(core)) {
                for (final Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    jar.putNextEntry(new JarEntry(core.relativize(file).toString().replace(java.io.File.separatorChar, '/')));
                    Files.copy(file, jar);
                    jar.closeEntry();
                }
            }
        }
        final Path dependency = checkout.resolve("type-map.jar");
        final var identity = berlin.yuna.typemap.logic.TypeConverter.class.getClassLoader()
                .getResource("META-INF/maven/berlin.yuna/type-map/pom.properties");
        assertThat(identity).as("Declared dependency archive").isNotNull();
        final var dependencyUrl = ((java.net.JarURLConnection) identity.openConnection()).getJarFileURL();
        try (var input = dependencyUrl.openStream()) { Files.copy(input, dependency); }
        final Path classes = Files.createDirectories(checkout.resolve("classes"));
        final var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        final var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(diagnostics, java.util.Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            assertThat(compiler.getTask(null, files, diagnostics,
                    List.of("-proc:none", "--release", "25", "-cp", api + java.io.File.pathSeparator + dependency, "-d", classes.toString()),
                    null, files.getJavaFileObjectsFromPaths(javaFiles)).call()).as(diagnostics.getDiagnostics().toString()).isTrue();
        }
        try (var archive = new java.util.jar.JarFile(dependency.toFile())) {
            for (final var entry : archive.stream().filter(entry -> !entry.isDirectory()
                    && entry.getName().startsWith("berlin/yuna/typemap/")).toList()) {
                final Path target = classes.resolve(entry.getName()).normalize();
                assertThat(target.startsWith(classes)).isTrue();
                Files.createDirectories(target.getParent());
                try (var input = archive.getInputStream(entry)) { Files.copy(input, target); }
            }
        }
        final Path bundle = checkout.resolve("steps.jar");
        final Path exportLog = directory.resolve("export.log");
        final Process export = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classes + java.io.File.pathSeparator + api, "dev.nanonative.railix.stdlib.StandardLibrary",
                classes.toString(), bundle.toString()).redirectErrorStream(true).redirectOutput(exportLog.toFile()).start();
        try {
            assertThat(export.waitFor(30, TimeUnit.SECONDS)).as("Provider build completed").isTrue();
            assertThat(export.exitValue()).as(Files.readString(exportLog)).isZero();
        } finally {
            if (export.isAlive()) { export.destroyForcibly(); export.waitFor(); }
        }
        final Path store = checkout.resolve("artifacts");
        final var catalog = StepCatalog.of().installSource(StepCatalog.bundleLock(bundle, store), store);
        final var defaults = ProjectFiles.catalog(directory.resolve("railix.project.json"), directory.resolve("home"));
        assertThat(catalog.definitions()).extracting(StepDefinition::id)
                .containsExactlyElementsOf(defaults.definitions().stream().map(StepDefinition::id).toList());
        for (final var definition : catalog.definitions()) {
            assertThat(StepContractJson.write(definition)).isEqualTo(StepContractJson.write(defaults.find(definition.id()).orElseThrow()));
            if (definition.executable()) assertThat(catalog.implementation(definition.id()).orElseThrow().contractDigest())
                    .isEqualTo(defaults.implementation(definition.id()).orElseThrow().contractDigest());
        }
        final String source = CreatorProjects.lowercaseCli();
        final var result = ProjectCompiler.compileApplication(source, catalog);
        assertThat(result).isInstanceOf(CompileResult.Compiled.class);
        final Path project = directory.resolve("railix.project.json");
        Files.writeString(project, source);
        final var artifact = ApplicationBuilder.buildProduction(project, (CompileResult.Compiled) result);
        final String transformSource = TransformGeneratedE2eTest.cliProject("integer", "0");
        final var transformCompiled = ProjectCompiler.compileApplication(transformSource, catalog);
        assertThat(transformCompiled).isInstanceOf(CompileResult.Compiled.class);
        final var transform = ApplicationBuilder.buildProduction(directory.resolve("transform.json"),
                (CompileResult.Compiled) transformCompiled);
        try (var files = Files.walk(checkout)) {
            for (final Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
        final var run = CreatorFirstExecutionArtifacts.runJar(artifact.jar(), "InDePeNdEnT");
        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).isEqualTo("\"independent\"");
        final var converted = CreatorFirstExecutionArtifacts.runJar(transform.jar(), "42");
        assertThat(converted.exitCode()).as(converted.output()).isZero();
        assertThat(converted.output()).isEqualTo("42");
        final var fallback = CreatorFirstExecutionArtifacts.runJar(transform.jar(), "not-a-number");
        assertThat(fallback.exitCode()).as(fallback.output()).isZero();
        assertThat(fallback.output()).isEqualTo("0");
    }
}
