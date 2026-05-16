package org.nanonative.railix.processor;

import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

final class RailixProcessorTest {

    @Test
    void process_withValidInterface_shouldGenerateTypedRail() throws Exception {
        final Path dir = Files.createTempDirectory("railix-apt");
        final Path srcDir = dir.resolve("src");
        final Path genDir = dir.resolve("gen");
        final Path outDir = dir.resolve("out");
        Files.createDirectories(srcDir);
        Files.createDirectories(genDir);
        Files.createDirectories(outDir);

        final Path src = srcDir.resolve("demo/App.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
            package demo;

            import org.nanonative.railix.apt.RailField;
            import org.nanonative.railix.apt.RailActor;
            import org.nanonative.railix.apt.Railix;
            import java.net.http.HttpClient;
            import javax.sql.DataSource;

            @Railix
            public interface App {
              @RailActor(DataSource.class) DataSource database();
              @RailActor(value = HttpClient.class, name = "http") HttpClient httpClient();
              @RailField("primary_db") DataSource primary();
              String traceId();
              Integer userId();
              String HTTPServer();
            }
            """, StandardCharsets.UTF_8);

        final CompileResult result = compile(List.of(src), outDir, genDir);
        assertThat(result.ok).withFailMessage(diagnosticsToString(result.diagnostics)).isTrue();

        final Path rail = genDir.resolve("demo/AppRail.java");
        assertThat(Files.exists(rail)).withFailMessage("missing generated %s", rail).isTrue();

        final String source = Files.readString(rail, StandardCharsets.UTF_8);
        assertThat(source).contains(
            "final class AppRail extends Rail",
            "public static AppRail of()",
            "public static final String TRACE_ID = \"trace_id\";",
            "public Ctx ctx()",
            "final class Ctx",
            "public java.lang.String traceId() { return ctxMap().asString(TRACE_ID); }",
            "public Optional<java.lang.String> traceIdOpt() { return Optional.ofNullable(ctxMap().asString(TRACE_ID)); }",
            "public java.lang.Integer userId() { return ctxMap().asInt(USER_ID); }",
            "public AppRail traceId(final java.lang.String value) { AppRail.this.ctxSet(value, TRACE_ID); return AppRail.this; }",
            "public static final String PRIMARY_DB = \"primary_db\";",
            "public Actors actors()",
            "final class Actors extends org.nanonative.railix.Actors",
            "public javax.sql.DataSource database()",
            "public Optional<javax.sql.DataSource> databaseOpt()",
            "public java.net.http.HttpClient httpClient()",
            "public Optional<java.net.http.HttpClient> httpClientOpt()"
        );
    }

    @Test
    void process_withVoidMethod_shouldFailCompilation() throws Exception {
        assertCompilationFails("Bad.java", """
            package demo;
            import org.nanonative.railix.apt.Railix;
            @Railix
            public interface Bad { void nope(); }
            """, "must not return void");
    }

    @Test
    void process_withAnnotatedClass_shouldFailCompilation() throws Exception {
        assertCompilationFails("Bad.java", """
            package demo;
            import org.nanonative.railix.apt.Railix;
            @Railix
            public class Bad { public String nope() { return \"x\"; } }
            """, "must annotate an interface");
    }

    @Test
    void process_withPrivateNestedInterface_shouldFailCompilation() throws Exception {
        assertCompilationFails("Outer.java", """
            package demo;
            import org.nanonative.railix.apt.Railix;
            public final class Outer {
              @Railix
              private interface Hidden { String value(); }
            }
            """, "interfaces must not be private");
    }

    @Test
    void process_withGenericInterface_shouldFailCompilation() throws Exception {
        assertCompilationFails("Bad.java", """
            package demo;
            import org.nanonative.railix.apt.Railix;
            @Railix
            public interface Bad<T> { String value(); }
            """, "must not declare generic type parameters");
    }

    @Test
    void process_withDefaultMethod_shouldFailCompilation() throws Exception {
        assertCompilationFails("Bad.java", """
            package demo;
            import org.nanonative.railix.apt.Railix;
            @Railix
            public interface Bad { default String value() { return \"x\"; } }
            """, "must not be default methods");
    }

    @Test
    void process_withStaticMethod_shouldFailCompilation() throws Exception {
        assertCompilationFails("Bad.java", """
            package demo;
            import org.nanonative.railix.apt.Railix;
            @Railix
            public interface Bad { static String value() { return \"x\"; } }
            """, "must not be static");
    }

    @Test
    void process_withMethodParameters_shouldFailCompilation() throws Exception {
        assertCompilationFails("Bad.java", """
            package demo;
            import org.nanonative.railix.apt.Railix;
            @Railix
            public interface Bad { String value(String input); }
            """, "must not have parameters");
    }

    @Test
    void process_withPrimitiveReturn_shouldFailCompilation() throws Exception {
        assertCompilationFails("Bad.java", """
            package demo;
            import org.nanonative.railix.apt.Railix;
            @Railix
            public interface Bad { int value(); }
            """, "must not return primitive types");
    }

    @Test
    void process_withOptionalReturn_shouldFailCompilation() throws Exception {
        assertCompilationFails("Bad.java", """
            package demo;
            import org.nanonative.railix.apt.Railix;
            import java.util.Optional;
            @Railix
            public interface Bad { Optional<String> value(); }
            """, "must not use generic return types");
    }

    @Test
    void process_withGenericReturn_shouldFailCompilation() throws Exception {
        assertCompilationFails("Bad.java", """
            package demo;
            import org.nanonative.railix.apt.Railix;
            import java.util.List;
            @Railix
            public interface Bad { List<String> value(); }
            """, "must not use generic return types");
    }

    @Test
    void process_withFieldAndActorAnnotationsOnSameMethod_shouldFailCompilation() throws Exception {
        assertCompilationFails("Bad.java", """
            package demo;
            import org.nanonative.railix.apt.RailActor;
            import org.nanonative.railix.apt.RailField;
            import org.nanonative.railix.apt.Railix;
            import javax.sql.DataSource;
            @Railix
            public interface Bad {
              @RailActor(DataSource.class)
              @RailField("db")
              DataSource database();
            }
            """, "must not use @RailField together with @RailActor");
    }

    @Test
    void process_withMismatchedActorType_shouldFailCompilation() throws Exception {
        assertCompilationFails("Bad.java", """
            package demo;
            import org.nanonative.railix.apt.RailActor;
            import org.nanonative.railix.apt.Railix;
            import java.net.http.HttpClient;
            import javax.sql.DataSource;
            @Railix
            public interface Bad {
              @RailActor(HttpClient.class)
              DataSource database();
            }
            """, "return type must match @RailActor(value)");
    }


    @Test
    void process_withUnnamedPackageAndTypedCtxMethods_shouldGenerateTypeSpecificAccessors() throws Exception {
        final Path dir = Files.createTempDirectory("railix-apt-default-pkg");
        final Path srcDir = dir.resolve("src");
        final Path genDir = dir.resolve("gen");
        final Path outDir = dir.resolve("out");
        Files.createDirectories(srcDir);
        Files.createDirectories(genDir);
        Files.createDirectories(outDir);

        final Path src = srcDir.resolve("App.java");
        Files.writeString(src, """
            import org.nanonative.railix.apt.RailField;
            import org.nanonative.railix.apt.Railix;

            @Railix
            public interface App {
              Boolean enabled();
              Long count();
              Double ratio();
              @RailField("HTTP Flag") String httpFlag();
            }
            """, StandardCharsets.UTF_8);

        final CompileResult result = compile(List.of(src), outDir, genDir);
        assertThat(result.ok).withFailMessage(diagnosticsToString(result.diagnostics)).isTrue();

        final String source = Files.readString(genDir.resolve("AppRail.java"), StandardCharsets.UTF_8);
        assertThat(source).contains(
            "ctxMap().asBoolean(ENABLED)",
            "ctxMap().asLong(COUNT)",
            "ctxMap().asDouble(RATIO)",
            "public static final String HTTP_FLAG = \"http_flag\";"
        );
    }

    @Test
    void process_withBlankAnnotationNamesDigitsAndFloat_shouldGenerateFallbacksAndTypedAccessors() throws Exception {
        final Path dir = Files.createTempDirectory("railix-apt-fallbacks");
        final Path srcDir = dir.resolve("src");
        final Path genDir = dir.resolve("gen");
        final Path outDir = dir.resolve("out");
        Files.createDirectories(srcDir);
        Files.createDirectories(genDir);
        Files.createDirectories(outDir);

        final Path src = srcDir.resolve("demo/App.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
            package demo;

            import org.nanonative.railix.apt.RailActor;
            import org.nanonative.railix.apt.RailField;
            import org.nanonative.railix.apt.Railix;
            import java.net.http.HttpClient;

            @Railix
            public interface App {
              @RailField(" ") String traceId();
              @RailField("1 value") String value1();
              Float ratio();
              @RailActor(value = HttpClient.class, name = " ") HttpClient httpClient();
            }
            """, StandardCharsets.UTF_8);

        final CompileResult result = compile(List.of(src), outDir, genDir);
        assertThat(result.ok).withFailMessage(diagnosticsToString(result.diagnostics)).isTrue();

        final String source = Files.readString(genDir.resolve("demo/AppRail.java"), StandardCharsets.UTF_8);
        assertThat(source).contains(
            "public static final String TRACE_ID = \"trace_id\";",
            "public static final String K_1_VALUE = \"1_value\";",
            "ctxMap().asFloat(RATIO)",
            "public java.net.http.HttpClient httpClient() { return get(\"http_client\", java.net.http.HttpClient.class); }"
        );
    }

    @Test
    void process_withMethodTypeParameters_shouldFailCompilation() throws Exception {
        assertCompilationFails("Bad.java", """
            package demo;
            import org.nanonative.railix.apt.Railix;
            @Railix
            public interface Bad { <T> String value(); }
            """, "must not declare type parameters");
    }

    @Test
    void process_withArrayReturn_shouldFailCompilation() throws Exception {
        assertCompilationFails("Bad.java", """
            package demo;
            import org.nanonative.railix.apt.Railix;
            @Railix
            public interface Bad { String[] value(); }
            """, "must return a declared type");
    }

    @Test
    void process_withInterfaceConstants_shouldIgnoreNonMethodMembers() throws Exception {
        final Path dir = Files.createTempDirectory("railix-apt-constants");
        final Path srcDir = dir.resolve("src");
        final Path genDir = dir.resolve("gen");
        final Path outDir = dir.resolve("out");
        Files.createDirectories(srcDir);
        Files.createDirectories(genDir);
        Files.createDirectories(outDir);

        final Path src = srcDir.resolve("demo/App.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
            package demo;

            import org.nanonative.railix.apt.Railix;

            @Railix
            public interface App {
              String KIND = "demo";
              String traceId();
            }
            """, StandardCharsets.UTF_8);

        final CompileResult result = compile(List.of(src), outDir, genDir);
        assertThat(result.ok).withFailMessage(diagnosticsToString(result.diagnostics)).isTrue();

        final String source = Files.readString(genDir.resolve("demo/AppRail.java"), StandardCharsets.UTF_8);
        assertThat(source).contains("public java.lang.String traceId()");
        assertThat(source).doesNotContain("KIND");
    }

    private static void assertCompilationFails(final String fileName, final String source, final String expected) throws Exception {
        final Path dir = Files.createTempDirectory("railix-apt-bad");
        final Path srcDir = dir.resolve("src");
        final Path genDir = dir.resolve("gen");
        final Path outDir = dir.resolve("out");
        Files.createDirectories(srcDir);
        Files.createDirectories(genDir);
        Files.createDirectories(outDir);

        final Path file = srcDir.resolve("demo/").resolve(fileName);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);

        final CompileResult result = compile(List.of(file), outDir, genDir);
        assertThat(result.ok).withFailMessage(diagnosticsToString(result.diagnostics)).isFalse();
        assertThat(diagnosticsToString(result.diagnostics)).contains(expected);
    }

    private static CompileResult compile(final List<Path> sources, final Path outDir, final Path genDir) throws IOException {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler)
            .withFailMessage("No system JavaCompiler available (are you running a JRE instead of a JDK?)")
            .isNotNull();

        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            final Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(
                sources.stream().map(Path::toFile).toList()
            );
            final List<String> options = List.of(
                "--release", "21",
                "-d", outDir.toString(),
                "-s", genDir.toString(),
                "-classpath", System.getProperty("java.class.path"),
                "-processor", "org.nanonative.railix.apt.proc.RailixProcessor"
            );
            final boolean ok = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();
            return new CompileResult(ok, diagnostics.getDiagnostics());
        }
    }

    private static String diagnosticsToString(final List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        final StringBuilder out = new StringBuilder();
        for (final Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
            out.append(diagnostic.getKind()).append(": ").append(diagnostic.getMessage(null)).append("\n");
        }
        return out.toString();
    }

    private record CompileResult(boolean ok, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
    }
}
