package example.railix.welcome;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.FileChooser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedGreetingCreatorBrowserIT {
    private static final Path PROJECT = Path.of("").toAbsolutePath().normalize();
    private static final Path ARTIFACT = PROJECT.resolve("target/trusted-step-monolith.jar");

    @TempDir
    Path temporary;

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "desktop, 1280, 720, railix-trusted-step-monolith.png",
            "mobile, 320, 700, railix-trusted-step-monolith-mobile.png"
    })
    @Timeout(40)
    void creatorAuthorsRunsAndSavesThePackagedExample(
            final String viewport,
            final int width,
            final int height,
            final String screenshotName
    ) throws Exception {
        final Process creator = new ProcessBuilder(
                javaCommand(),
                "-jar",
                ARTIFACT.toString(),
                "creator"
        ).directory(temporary.toFile()).start();
        try {
            final String creatorUrl = awaitReady(creator);
            try (Playwright playwright = Playwright.create()) {
                final String channel = System.getenv().getOrDefault("RAILIX_BROWSER_CHANNEL", "chrome");
                final Browser browser = playwright.chromium().launch(
                        new BrowserType.LaunchOptions().setChannel(channel).setHeadless(true)
                );
                try (Page page = browser.newPage(new Browser.NewPageOptions().setViewportSize(width, height))) {
                    page.navigate(creatorUrl);
                    final Path packagedFlow = packagedFlow();
                    openFlow(page, packagedFlow);
                    final Path saved = saveFlow(page);
                    page.locator("#sample-input").fill("{\"name\":\"RAILIX\"}");
                    page.getByRole(AriaRole.BUTTON, exact("Run")).click();
                    page.waitForFunction("document.querySelector('#compile-state').textContent === 'Run passed'");
                    final Path screenshot = screenshotPath(screenshotName);
                    Files.createDirectories(screenshot.getParent());
                    page.screenshot(new Page.ScreenshotOptions().setPath(screenshot).setFullPage(true));

                    assertThat(new Observation(
                            page.locator("#flow-summary").textContent(),
                            page.getByRole(AriaRole.BUTTON, exact("Add example.text.prefix")).isVisible(),
                            page.getByRole(AriaRole.ARTICLE, exact("Step lowercase")).count(),
                            page.getByRole(AriaRole.ARTICLE, exact("Step prefix")).count(),
                            page.locator("#console-output").textContent().contains("Welcome, railix"),
                            Files.readString(saved).equals(canonical(packagedFlow) + "\n"),
                            Files.isRegularFile(screenshot)
                    )).isEqualTo(new Observation(
                            "2 Steps / 3 data / 2 routes",
                            true,
                            1,
                            1,
                            true,
                            true,
                            true
                    ));
                }
            }
        } finally {
            stopCreator(creator);
        }
    }

    private static void stopCreator(final Process creator) throws Exception {
        creator.destroy();
        if (!creator.waitFor(5, TimeUnit.SECONDS)) {
            creator.destroyForcibly();
            if (!creator.waitFor(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Custom Creator did not stop within 10 seconds.");
            }
        }
        assertThat(creator.isAlive()).isFalse();
    }

    private Path packagedFlow() throws Exception {
        final Path extracted = temporary.resolve("packaged-railix.flow.json");
        try (JarFile artifact = new JarFile(ARTIFACT.toFile())) {
            Files.copy(artifact.getInputStream(artifact.getJarEntry("railix.flow.json")), extracted);
        }
        return extracted;
    }

    private static String canonical(final Path flow) throws Exception {
        final RailixJson.Result result = RailixJson.parse(Files.readString(flow));
        if (result instanceof RailixJson.Parsed parsed) {
            return RailixJson.write(parsed.value());
        }
        throw new AssertionError("Packaged flow is not valid JSON: " + flow);
    }

    private Path saveFlow(final Page page) {
        final Download download = page.waitForDownload(
                () -> page.getByRole(AriaRole.BUTTON, exact("Save flow")).click()
        );
        final Path saved = temporary.resolve(download.suggestedFilename());
        download.saveAs(saved);
        return saved;
    }

    private static void openFlow(final Page page, final Path flow) {
        final FileChooser chooser = page.waitForFileChooser(
                () -> page.getByRole(AriaRole.BUTTON, exact("Open flow")).click()
        );
        chooser.setFiles(flow);
        page.waitForFunction("document.querySelector('#compile-state').textContent === 'Flow opened'");
    }

    private static String awaitReady(final Process creator) throws Exception {
        final BufferedReader output = new BufferedReader(new InputStreamReader(
                creator.getInputStream(),
                StandardCharsets.UTF_8
        ));
        final FutureTask<String> readiness = new FutureTask<>(output::readLine);
        Thread.startVirtualThread(readiness);
        final String line = readiness.get(5, TimeUnit.SECONDS);
        final RailixJson.Result result = RailixJson.parse(line);
        if (!(result instanceof RailixJson.Parsed parsed)
                || !(parsed.value() instanceof RailixValue.ObjectValue object)
                || !(object.values().get("creatorUrl") instanceof RailixValue.StringValue url)
                || !line.contains("\"status\":\"creator-ready\"")) {
            throw new AssertionError("Custom Creator did not report readiness: " + line);
        }
        return url.value();
    }

    private static Page.GetByRoleOptions exact(final String name) {
        return new Page.GetByRoleOptions().setName(name).setExact(true);
    }

    private static Path screenshotPath(final String name) {
        return PROJECT.resolve("../../output/playwright").resolve(name).normalize();
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private record Observation(
            String summary,
            boolean customStepVisible,
            int standardNodes,
            int customNodes,
            boolean outputVisible,
            boolean canonicalNewline,
            boolean screenshotWritten
    ) {
    }
}
