package dev.nanonative.railix.creator;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.FileChooser;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.BoundingBox;
import dev.nanonative.railix.core.step.StepCatalog;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.step.StepResult;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RailixCreatorBrowserIT {
    private static final Path REPOSITORY = Path.of("..", "..").toAbsolutePath().normalize();
    private static final int VIEWPORT_WIDTH = Integer.getInteger("railix.browser.viewport.width", 1280);
    private static final String TURKISH_FLOW = "{\"connections\":[{\"from\":\"input.text\","
            + "\"to\":\"lowercase.text\"},{\"from\":\"lowercase.text\",\"to\":\"output.text\"}],"
            + "\"entry\":\"lowercase\",\"id\":\"lowercase-app\",\"inputs\":{\"text\":\"string\"},"
            + "\"outputs\":{\"text\":\"string\"},\"steps\":[{\"config\":{\"languageTag\":\"tr\"},"
            + "\"id\":\"lowercase\",\"on\":{\"ok\":\"end\"},\"use\":\"text.lowercase\"}],"
            + "\"triggers\":[{\"config\":{\"stdin\":true},\"id\":\"command\",\"type\":\"cli\"}]}\n";
    private static final String NESTED_FLOW = "{\"connections\":[{\"from\":\"input.payload\","
            + "\"sourcePath\":[\"person\",\"name\"],\"to\":\"lowercase.text\"},"
            + "{\"from\":\"lowercase.text\",\"targetPath\":[\"person\",\"name\"],"
            + "\"to\":\"output.response\"}],\"entry\":\"lowercase\",\"id\":\"nested-lowercase\","
            + "\"inputs\":{\"payload\":\"object\"},\"outputs\":{\"response\":\"object\"},"
            + "\"steps\":[{\"config\":{},\"id\":\"lowercase\",\"on\":{\"ok\":\"end\"},"
            + "\"use\":\"text.lowercase\"}],\"triggers\":[]}\n";
    private static final String CONVERSION_FLOW = "{\"connections\":[{\"from\":\"input.text\","
            + "\"to\":\"lowercase.text\"},{\"convert\":\"string-to-number\","
            + "\"from\":\"lowercase.text\",\"to\":\"output.number\"}],\"entry\":\"lowercase\","
            + "\"id\":\"conversion-flow\",\"inputs\":{\"text\":\"string\"},"
            + "\"outputs\":{\"number\":\"number\"},\"steps\":[{\"config\":{},"
            + "\"id\":\"lowercase\",\"on\":{\"ok\":\"end\"},\"use\":\"text.lowercase\"}],"
            + "\"triggers\":[]}\n";
    private static final String MULTI_MAPPING_FLOW = "{\"connections\":[{\"from\":\"input.payload\","
            + "\"sourcePath\":[\"person\",\"name\"],\"to\":\"lowercase.text\"},"
            + "{\"from\":\"lowercase.text\",\"targetPath\":[\"person\",\"name\"],"
            + "\"to\":\"output.response\"},{\"from\":\"input.payload\","
            + "\"sourcePath\":[\"person\",\"age\"],\"targetPath\":[\"person\",\"age\"],"
            + "\"to\":\"output.response\"}],\"entry\":\"lowercase\",\"id\":\"nested-lowercase\","
            + "\"inputs\":{\"payload\":\"object\"},\"outputs\":{\"response\":\"object\"},"
            + "\"steps\":[{\"config\":{},\"id\":\"lowercase\",\"on\":{\"ok\":\"end\"},"
            + "\"use\":\"text.lowercase\"}],\"triggers\":[]}\n";
    private static Process creator;
    private static BufferedReader creatorOutput;
    private static Playwright playwright;
    private static Browser browser;
    private static String creatorUrl;

    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void startCreatorAndBrowser() throws Exception {
        try {
            viewportHeight();
            final int port = availablePort();
            creator = new ProcessBuilder(
                    REPOSITORY.resolve("railix").toString(),
                    "creator",
                    "--port",
                    Integer.toString(port)
            ).directory(REPOSITORY.toFile()).start();
            creatorOutput = new BufferedReader(new InputStreamReader(
                    creator.getInputStream(),
                    StandardCharsets.UTF_8
            ));
            final FutureTask<String> readiness = new FutureTask<>(creatorOutput::readLine);
            Thread.startVirtualThread(readiness);
            final String readyLine = readiness.get(5, TimeUnit.SECONDS);
            if (readyLine == null || !readyLine.contains("\"status\":\"creator-ready\"")) {
                throw new AssertionError("Packaged Creator did not report readiness: " + readyLine);
            }
            creatorUrl = "http://127.0.0.1:" + port + "/";

            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of(
                    "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"
            )));
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setChannel(System.getenv().getOrDefault("RAILIX_BROWSER_CHANNEL", "chrome"))
                    .setHeadless(true));
        } catch (Exception | Error failure) {
            try {
                stopCreatorAndBrowser();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @AfterAll
    @Timeout(40)
    static void stopCreatorAndBrowser() throws Exception {
        try {
            if (playwright != null) {
                playwright.close();
            }
        } finally {
            try {
                if (creator != null && creator.isAlive()) {
                    creator.destroy();
                    if (!creator.waitFor(5, TimeUnit.SECONDS)) {
                        creator.destroyForcibly();
                        if (!creator.waitFor(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("Packaged Creator did not stop");
                        }
                    }
                }
            } finally {
                try {
                    if (creatorOutput != null) {
                        creatorOutput.close();
                    }
                } finally {
                    assertNoChildProcesses();
                }
            }
        }
    }

    @Test
    void referenceFlowRunsThroughTheBrowser() {
        try (Page page = openPage()) {
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(new RunObservation(
                    text(page, "#compile-state"),
                    text(page, "#console-output").contains("\"text\": \"hello railix\"")
            )).isEqualTo(new RunObservation("Run passed", true));
        }
    }

    @Test
    void applicationAndDefaultCliTriggerAnchorTheCanvas() {
        try (Page page = openPage()) {
            final BoundingBox trigger = page.getByRole(
                    AriaRole.ARTICLE,
                    new Page.GetByRoleOptions().setName("Trigger command").setExact(true)
            ).boundingBox();
            final BoundingBox application = page.getByRole(
                    AriaRole.ARTICLE,
                    new Page.GetByRoleOptions().setName("Application lowercase-app").setExact(true)
            ).boundingBox();

            assertThat(List.of(
                    trigger != null,
                    application != null,
                    trigger != null && application != null && trigger.x < application.x,
                    page.locator(".trigger-wire").count()
            )).containsExactly(true, true, true, 1);
        }
    }

    @Test
    void defaultCliTriggerIsSavedAsCanonicalFlowData() throws Exception {
        try (Page page = openPage()) {
            final String source = Files.readString(saveFlow(page, "default-cli.flow.json").path());

            assertThat(source).contains(
                    "\"triggers\":[{\"config\":{\"stdin\":true},\"id\":\"command\",\"type\":\"cli\"}]"
            );
        }
    }

    @Test
    void newFlowGetsAShortMemorableGeneratedName() {
        try (Page page = openPage()) {
            click(page, "New flow");

            assertThat(label(page, "Application name").inputValue())
                    .matches("[a-z]+-[a-z]+-[a-z]+");
        }
    }

    @Test
    void generatedFlowNamesDoNotRepeatWithinTheSession() {
        try (Page page = openPage()) {
            click(page, "New flow");
            final String first = label(page, "Application name").inputValue();
            click(page, "New flow");

            assertThat(label(page, "Application name").inputValue()).isNotEqualTo(first);
        }
    }

    @Test
    void applicationNameRejectsAnEarlierSessionName() {
        try (Page page = openPage()) {
            click(page, "New flow");
            final String first = label(page, "Application name").inputValue();
            click(page, "New flow");
            final String second = label(page, "Application name").inputValue();
            label(page, "Application name").fill(first);
            label(page, "Application name").press("Tab");

            assertThat(List.of(
                    label(page, "Application name").inputValue(),
                    text(page, "#console-output").contains("FLOW_ID_SESSION_DUPLICATE")
            )).containsExactly(second, true);
        }
    }

    @Test
    void applicationNameRejectsNonUrlSafeText() {
        try (Page page = openPage()) {
            final String current = label(page, "Application name").inputValue();
            label(page, "Application name").fill("Bad Flow Name");
            label(page, "Application name").press("Tab");

            assertThat(List.of(
                    label(page, "Application name").inputValue(),
                    text(page, "#console-output").contains("FLOW_ID_URL_SAFE_REQUIRED")
            )).containsExactly(current, true);
        }
    }

    @Test
    void applicationNameEditsTheCanonicalFlowIdentity() {
        try (Page page = openPage()) {
            click(page, "New flow");
            label(page, "Application name").fill("atomic-quark-relay");
            label(page, "Application name").press("Tab");

            assertThat(List.of(
                    text(page, "#project-name"),
                    page.getByRole(
                            AriaRole.ARTICLE,
                            new Page.GetByRoleOptions()
                                    .setName("Application atomic-quark-relay")
                                    .setExact(true)
                    ).count()
            )).containsExactly("atomic-quark-relay", 1);
        }
    }

    @Test
    void renamedApplicationNameIsNotReusedByTheGenerator() {
        try (Page page = openPageWithNameSeed()) {
            label(page, "Application name").fill("atomic-byte-array");
            label(page, "Application name").press("Tab");
            click(page, "New flow");

            assertThat(label(page, "Application name").inputValue())
                    .isNotEqualTo("atomic-byte-array");
        }
    }

    @Test
    void openedApplicationNameIsNotReusedByTheGenerator() throws Exception {
        try (Page page = openPageWithNameSeed()) {
            openFlow(page, flowFile(
                    "atomic-byte-array.flow.json",
                    TURKISH_FLOW.replace("\"id\":\"lowercase-app\"", "\"id\":\"atomic-byte-array\"")
            ));
            click(page, "New flow");

            assertThat(label(page, "Application name").inputValue())
                    .isNotEqualTo("atomic-byte-array");
        }
    }

    @Test
    void triggerAddNextOpensACompatibleStepChooser() {
        try (Page page = openPage()) {
            click(page, "New flow");
            click(page, "Add next Step after command");

            assertThat(List.of(
                    text(page, "#catalog-title"),
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Choose text.lowercase").setExact(true)
                    ).count(),
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Choose http.post").setExact(true)
                    ).count()
            )).containsExactly("Choose next Step", 1, 0);
        }
    }

    @Test
    void cancellingAddNextDoesNotMutateTheFlow() {
        try (Page page = openPage()) {
            click(page, "New flow");
            click(page, "Add next Step after command");
            click(page, "Cancel Step choice");

            assertThat(List.of(
                    text(page, "#flow-summary"),
                    page.locator("article.step-node").count()
            )).containsExactly("0 Steps / 0 data / 0 routes", 0);
        }
    }

    @Test
    void triggerAddNextSetsOnlyTheEntryStep() {
        try (Page page = openPage()) {
            click(page, "New flow");
            click(page, "Add next Step after command");
            click(page, "Choose text.lowercase");

            assertThat(List.of(
                    text(page, "#flow-summary"),
                    text(page, "#console-output").contains("\"entrySet\": true"),
                    text(page, "#console-output").contains("\"mappingsAdded\": 0"),
                    selectedRoute(page, "lowercase.ok")
            )).containsExactly("1 Step / 0 data / 0 routes", true, true, "");
        }
    }

    @Test
    void stepAddNextCreatesOneExplicitOutcomeRoute() {
        try (Page page = openPage()) {
            route(page, "lowercase.ok", "");
            click(page, "Add next Step after lowercase.ok");
            click(page, "Choose text.nonblank");

            assertThat(List.of(
                    text(page, "#flow-summary"),
                    selectedRoute(page, "lowercase.ok"),
                    selectedRoute(page, "nonblank.valid"),
                    selectedRoute(page, "nonblank.invalid"),
                    text(page, "#console-output").contains("\"mappingsAdded\": 0")
            )).containsExactly("2 Steps / 2 data / 1 route", "nonblank", "", "", true);
        }
    }

    @Test
    void humanEndpointLabelsReplaceRawIdentifiersInTheBasicMappingSurface() {
        try (Page page = openPage()) {
            page.getByRole(
                    AriaRole.ARTICLE,
                    new Page.GetByRoleOptions().setName("Step lowercase").setExact(true)
            ).click();

            assertThat(text(page, "#data-mappings"))
                    .contains("App input", "Text", "Lowercase", "App output")
                    .doesNotContain("input.text", "lowercase.text", "output.text");
        }
    }

    @Test
    void advancedMappingControlsAreCollapsedByDefault() {
        try (Page page = openPage()) {
            page.getByRole(
                    AriaRole.ARTICLE,
                    new Page.GetByRoleOptions().setName("Step lowercase").setExact(true)
            ).click();

            assertThat(List.of(
                    page.locator("details.advanced-mapping").count(),
                    page.getByRole(
                            AriaRole.TEXTBOX,
                            new Page.GetByRoleOptions().setName("Source path lowercase.text").setExact(true)
                    ).isVisible()
            )).containsExactly(2, false);
        }
    }

    @Test
    void visibleDisconnectRemovesExactlyOneDataConnection() {
        try (Page page = openPage()) {
            page.getByRole(
                    AriaRole.ARTICLE,
                    new Page.GetByRoleOptions().setName("Step lowercase").setExact(true)
            ).click();
            click(page, "Disconnect App input Text from Lowercase Text input");

            assertThat(List.of(
                    text(page, "#flow-summary"),
                    wireCount(page, "lowercase.text"),
                    selectedData(page, "lowercase.text")
            )).containsExactly("1 Step / 1 data / 1 route", 0, "");
        }
    }

    @Test
    void stepOutputOffersOnlyCompatibleDataConsumers() throws Exception {
        try (CreatorServer server = CreatorServer.start(0, typeFilteringCatalog());
             Page page = openPage(server)) {
            click(page, "New flow");
            click(page, "Add next Step after command");
            click(page, "Choose example.text.prefix");
            click(page, "Add data consumer for Prefix Text output");

            assertThat(List.of(
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Choose example.text.prefix").setExact(true)
                    ).count(),
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Choose example.number.accept").setExact(true)
                    ).count()
            )).containsExactly(1, 0);
        }
    }

    @Test
    void choosingADataConsumerCreatesOnlyTheExplicitDataConnection() {
        try (Page page = openPage()) {
            page.getByRole(
                    AriaRole.ARTICLE,
                    new Page.GetByRoleOptions().setName("Step lowercase").setExact(true)
            ).click();
            click(page, "Disconnect Lowercase Text from App output Text");
            click(page, "Add data consumer for Lowercase Text output");
            click(page, "Choose text.nonblank");

            assertThat(List.of(
                    text(page, "#flow-summary"),
                    selectedData(page, "nonblank.text"),
                    selectedRoute(page, "lowercase.ok"),
                    text(page, "#console-output").contains("\"mappingsAdded\": 1"),
                    text(page, "#console-output").contains("\"outcomesConnected\": 0")
            )).containsExactly("2 Steps / 2 data / 1 route", "lowercase.text", "end", true, true);
        }
    }

    @Test
    void stepRemovalIsAnExplicitCanvasAction() {
        try (Page page = openPage()) {
            click(page, "Remove Step lowercase");

            assertThat(text(page, "#flow-summary")).isEqualTo("0 Steps / 0 data / 0 routes");
        }
    }

    @Test
    void addedHttpTriggerAttachesToTheApplication() {
        try (Page page = openPage()) {
            click(page, "Add trigger");
            click(page, "Add HTTP trigger");

            assertThat(List.of(
                    page.getByRole(
                            AriaRole.ARTICLE,
                            new Page.GetByRoleOptions().setName("Trigger http-route").setExact(true)
                    ).count(),
                    page.locator(".trigger-wire").count()
            )).containsExactly(1, 2);
        }
    }

    @Test
    void triggerRemovalIsAnExplicitCanvasAction() {
        try (Page page = openPage()) {
            addTrigger(page, "HTTP");
            click(page, "Remove Trigger http-route");

            assertThat(List.of(
                    page.getByRole(
                            AriaRole.ARTICLE,
                            new Page.GetByRoleOptions().setName("Trigger command").setExact(true)
                    ).count(),
                    page.getByRole(
                            AriaRole.ARTICLE,
                            new Page.GetByRoleOptions().setName("Trigger http-route").setExact(true)
                    ).count(),
                    page.locator(".trigger-wire").count()
            )).containsExactly(1, 0, 1);
        }
    }

    @Test
    void graphLayoutIsStableAcrossReloads() {
        try (Page page = openPage()) {
            final String before = page.locator(".flow-canvas").getAttribute("data-layout");
            page.reload();

            assertThat(page.locator(".flow-canvas").getAttribute("data-layout")).isEqualTo(before);
        }
    }

    @Test
    void guidedFlowAuthoringProducesDesktopAndMobileVisualProof() throws Exception {
        try (Page page = openPage()) {
            final String suffix = VIEWPORT_WIDTH == 320 ? "-mobile" : "";
            final Path proof = REPOSITORY.resolve(
                    "output/playwright/railix-creator-guided-flow-authoring" + suffix + ".png"
            );
            Files.createDirectories(proof.getParent());
            page.screenshot(new Page.ScreenshotOptions().setPath(proof).setFullPage(true));

            assertThat(Files.size(proof)).isGreaterThan(10_000);
        }
    }

    @Test
    void catalogAddsAnUnconnectedStepWithoutInventingMappings() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");

            assertThat(new AddObservation(
                    text(page, "#flow-summary"),
                    page.locator("article.step-node").count(),
                    text(page, "#console-output").contains("\"step\": \"lowercase2\""),
                    text(page, "#console-output").contains("\"mappingsAdded\": 0"),
                    text(page, "#console-output").contains("\"outcomesConnected\": 0")
            )).isEqualTo(new AddObservation("2 Steps / 2 data / 1 route", 2, true, true, true));
        }
    }

    @Test
    void lightweightValidatorExposesItsDefaultAndOutcomes() {
        try (Page page = openPage()) {
            click(page, "Add text.nonblank");

            assertThat(List.of(
                    text(page, "#inspector-step-id"),
                    configControl(page, "nonblank.trimBeforeCheck").inputValue(),
                    selectedRoute(page, "nonblank.valid"),
                    selectedRoute(page, "nonblank.invalid"),
                    text(page, "#flow-summary")
            )).containsExactly(
                    "Nonblank",
                    "true",
                    "",
                    "",
                    "2 Steps / 2 data / 1 route"
            );
        }
    }

    @Test
    void normalizerUsesCompactRenderingAndItsOwnMark() {
        try (Page page = openPage()) {
            assertThat(nodePresentation(page, "lowercase"))
                    .isEqualTo(new NodePresentation(true, "kind-normalizer", "N"));
        }
    }

    @Test
    void validatorUsesCompactRenderingAndItsOwnMark() {
        try (Page page = openPage()) {
            click(page, "Add text.nonblank");

            assertThat(nodePresentation(page, "nonblank"))
                    .isEqualTo(new NodePresentation(true, "kind-validator", "V"));
        }
    }

    @Test
    void mapperUsesCompactRenderingAndItsOwnMark() {
        try (Page page = openPage()) {
            click(page, "Add value.default-if-null");

            assertThat(nodePresentation(page, "default-if-null"))
                    .isEqualTo(new NodePresentation(true, "kind-mapper", "M"));
        }
    }

    @Test
    void translatorUsesCompactRenderingAndItsOwnMark() {
        try (Page page = openPage()) {
            click(page, "Add text.translate-exact");

            assertThat(nodePresentation(page, "translate-exact"))
                    .isEqualTo(new NodePresentation(true, "kind-translator", "T"));
        }
    }

    @Test
    void ordinaryStepRetainsFullRenderingAndFunctionMark() throws Exception {
        try (CreatorServer server = CreatorServer.start(0, ordinaryStepCatalog());
             Page page = newViewportPage()) {
            page.navigate(server.baseUri().toString());
            click(page, "Add example.text.prefix");

            assertThat(nodePresentation(page, "prefix"))
                    .isEqualTo(new NodePresentation(false, "kind-step", "Fn"));
        }
    }

    @Test
    void fileReadUsesFullStepRenderingAndShowsItsFormatDefault() {
        try (Page page = openPage()) {
            click(page, "Add file.read");

            assertThat(List.of(
                    nodePresentation(page, "read"),
                    configControl(page, "read.format").inputValue(),
                    text(page, "#step-config").contains("string · data-format"),
                    text(page, "#step-config").contains("Default: \"json\"")
            )).containsExactly(
                    new NodePresentation(false, "kind-step", "Fn"),
                    "json",
                    true,
                    true
            );
        }
    }

    @Test
    void invalidFileFormatShowsCompilerOwnedFeedback() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("file-read.flow.json", fileReadFlow("json")));
            configControl(page, "reader.format").fill("toml");
            configControl(page, "reader.format").press("Tab");
            waitForDraft(page, "1 issue");

            assertThat(text(page, "#draft-diagnostics")).contains(
                    "FLOW_STEP_CONFIG_FORMAT_MISMATCH",
                    "steps.reader.config.format",
                    "requires format data-format"
            );
        }
    }

    @Test
    void fileReadRunsInsideThePackagedCreator() throws Exception {
        final Path data = flowFile("browser-value.yaml", "text: \"Railix\"\nactive: true\n");
        try (Page page = openPage()) {
            openFlow(page, flowFile("file-read.flow.json", fileReadFlow("yaml")));
            sample(page, RailixJson.write(RailixValue.object(Map.of(
                    "path",
                    RailixValue.string(data.toString())
            ))));
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(List.of(
                    text(page, "#compile-state"),
                    text(page, "#console-output").contains("\"active\": true"),
                    text(page, "#console-output").contains("\"text\": \"Railix\""),
                    text(page, "#console-output").contains("\"outcome\": \"read\"")
            )).containsExactly("Run passed", true, true, true);
        }
    }

    @Test
    void fileWriteUsesFullStepRenderingAndShowsItsOverwriteDefault() {
        try (Page page = openPage()) {
            click(page, "Add file.write");

            assertThat(List.of(
                    nodePresentation(page, "write"),
                    configControl(page, "write.overwrite").inputValue(),
                    text(page, "#step-config").contains("boolean"),
                    text(page, "#step-config").contains("Default: false")
            )).containsExactly(
                    new NodePresentation(false, "kind-step", "Fn"),
                    "false",
                    true,
                    true
            );
        }
    }

    @Test
    void fileDeleteUsesFullStepRenderingWithoutInventingConfiguration() {
        try (Page page = openPage()) {
            click(page, "Add file.delete");

            assertThat(List.of(
                    nodePresentation(page, "delete"),
                    text(page, "#step-config"),
                    selectedRoute(page, "delete.deleted"),
                    selectedRoute(page, "delete.missing"),
                    selectedRoute(page, "delete.rejected")
            )).containsExactly(
                    new NodePresentation(false, "kind-step", "Fn"),
                    "None",
                    "",
                    "",
                    ""
            );
        }
    }

    @Test
    void invalidFileOverwriteShowsCompilerOwnedFeedback() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("file-write.flow.json", fileWriteFlow("false")));
            configControl(page, "writer.overwrite").fill("\"yes\"");
            configControl(page, "writer.overwrite").press("Tab");
            waitForDraft(page, "1 issue");

            assertThat(text(page, "#draft-diagnostics")).contains(
                    "FLOW_STEP_CONFIG_TYPE_MISMATCH",
                    "steps.writer.config.overwrite",
                    "requires BOOLEAN"
            );
        }
    }

    @Test
    void filePersistenceRunsInsideThePackagedCreatorWithoutResidue() throws Exception {
        final Path data = temporaryDirectory.resolve("creator-persistence.json");
        Files.deleteIfExists(data);
        try (Page page = openPage()) {
            openFlow(page, flowFile("file-persistence.flow.json", filePersistenceFlow()));
            sample(page, RailixJson.write(RailixValue.object(Map.of(
                    "path",
                    RailixValue.string(data.toString()),
                    "value",
                    RailixValue.object(Map.of(
                            "active",
                            RailixValue.bool(true),
                            "name",
                            RailixValue.string("Railix")
                    ))
            ))));
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(List.of(
                    text(page, "#compile-state"),
                    text(page, "#console-output").contains("\"outcome\": \"written\""),
                    text(page, "#console-output").contains("\"outcome\": \"read\""),
                    text(page, "#console-output").contains("\"outcome\": \"deleted\""),
                    text(page, "#console-output").contains("\"active\": true"),
                    Files.exists(data),
                    temporaryFiles()
            )).containsExactly(
                    "Run passed",
                    true,
                    true,
                    true,
                    true,
                    false,
                    List.of()
            );
        } finally {
            Files.deleteIfExists(data);
        }
    }

    @Test
    void httpPostUsesFullStepRenderingAndShowsItsDefaults() {
        try (Page page = openPage()) {
            click(page, "Add http.post");

            assertThat(List.of(
                    nodePresentation(page, "post"),
                    configControl(page, "post.format").inputValue(),
                    configControl(page, "post.timeoutMillis").inputValue(),
                    text(page, "#step-config").contains("string · data-format"),
                    text(page, "#step-config").contains("number · timeout-millis")
            )).containsExactly(
                    new NodePresentation(false, "kind-step", "Fn"),
                    "json",
                    "30000",
                    true,
                    true
            );
        }
    }

    @Test
    void invalidHttpTimeoutShowsCompilerOwnedFeedback() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("http-get.flow.json", httpGetFlow(5_000)));
            configControl(page, "request.timeoutMillis").fill("0");
            configControl(page, "request.timeoutMillis").press("Tab");
            waitForDraft(page, "1 issue");

            assertThat(text(page, "#draft-diagnostics")).contains(
                    "FLOW_STEP_CONFIG_FORMAT_MISMATCH",
                    "steps.request.config.timeoutMillis",
                    "requires format timeout-millis"
            );
        }
    }

    @Test
    void outboundHttpRunsInsideThePackagedCreator() throws Exception {
        try (LocalJsonHttpServer endpoint =
                     new LocalJsonHttpServer("{\"active\":true,\"name\":\"Railix\"}");
             Page page = openPage()) {
            openFlow(page, flowFile("http-get.flow.json", httpGetFlow(5_000)));
            sample(page, RailixJson.write(RailixValue.object(Map.of(
                    "url", RailixValue.string(endpoint.url("/value")),
                    "headers", RailixValue.object(Map.of())
            ))));
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(List.of(
                    text(page, "#compile-state"),
                    text(page, "#console-output").contains("\"status\": 200"),
                    text(page, "#console-output").contains("\"name\": \"Railix\""),
                    text(page, "#console-output").contains("\"outcome\": \"success\"")
            )).containsExactly("Run passed", true, true, true);
        }
    }

    @Test
    void addedStepShowsCompilerOwnedDiagnostics() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            click(page, "Validate");
            waitForState(page, "Invalid");
            final String diagnostics = text(page, "#console-output");

            assertThat(new DiagnosticObservation(
                    text(page, "#compile-state"),
                    diagnostics.contains("FLOW_OUTCOME_UNHANDLED"),
                    diagnostics.contains("FLOW_STEP_UNREACHABLE"),
                    diagnostics.contains("steps.lowercase2.on.ok")
            )).isEqualTo(new DiagnosticObservation("Invalid", true, true, true));
        }
    }

    @Test
    void stepInputCanBeDisconnectedExplicitly() {
        try (Page page = openPage()) {
            data(page, "lowercase.text", "");

            assertThat(new DataEditObservation(
                    text(page, "#flow-summary"),
                    selectedData(page, "lowercase.text"),
                    text(page, "#compile-state"),
                    text(page, "#console-output"),
                    wireCount(page, "lowercase.text")
            )).isEqualTo(new DataEditObservation(
                    "1 Step / 1 data / 1 route",
                    "",
                    "Not validated",
                    dataEvent("data-disconnected", "lowercase.text", "input.text", "unmapped"),
                    0
            ));
        }
    }

    @Test
    void disconnectedStepInputShowsCompilerOwnedDiagnostic() {
        try (Page page = openPage()) {
            data(page, "lowercase.text", "");
            click(page, "Validate");
            waitForState(page, "Invalid");
            final String diagnostics = text(page, "#console-output");

            assertThat(new DataDiagnosticObservation(
                    text(page, "#compile-state"),
                    diagnostics.contains("FLOW_REQUIRED_INPUT_UNMAPPED"),
                    diagnostics.contains("steps.lowercase.inputs.text")
            )).isEqualTo(new DataDiagnosticObservation("Invalid", true, true));
        }
    }

    @Test
    void stepInputCanReconnectAndRun() {
        try (Page page = openPage()) {
            data(page, "lowercase.text", "");
            data(page, "lowercase.text", "input.text");
            final String event = text(page, "#console-output");
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(new DataReconnectObservation(
                    text(page, "#flow-summary"),
                    selectedData(page, "lowercase.text"),
                    event,
                    text(page, "#compile-state"),
                    text(page, "#console-output").contains("\"text\": \"hello railix\"")
            )).isEqualTo(new DataReconnectObservation(
                    "1 Step / 2 data / 1 route",
                    "input.text",
                    dataEvent("data-connected", "lowercase.text", "unmapped", "input.text"),
                    "Run passed",
                    true
            ));
        }
    }

    @Test
    void flowOutputCanBeDisconnectedExplicitly() {
        try (Page page = openPage()) {
            data(page, "output.text", "");

            assertThat(new DataEditObservation(
                    text(page, "#flow-summary"),
                    selectedData(page, "output.text"),
                    text(page, "#compile-state"),
                    text(page, "#console-output"),
                    wireCount(page, "output.text")
            )).isEqualTo(new DataEditObservation(
                    "1 Step / 1 data / 1 route",
                    "",
                    "Not validated",
                    dataEvent("data-disconnected", "output.text", "lowercase.text", "unmapped"),
                    0
            ));
        }
    }

    @Test
    void disconnectedFlowOutputShowsCompilerOwnedDiagnostic() {
        try (Page page = openPage()) {
            data(page, "output.text", "");
            click(page, "Validate");
            waitForState(page, "Invalid");
            final String diagnostics = text(page, "#console-output");

            assertThat(new DataDiagnosticObservation(
                    text(page, "#compile-state"),
                    diagnostics.contains("FLOW_OUTPUT_UNMAPPED"),
                    diagnostics.contains("outputs.text")
            )).isEqualTo(new DataDiagnosticObservation("Invalid", true, true));
        }
    }

    @Test
    void stepOutputCanFeedAnotherStepAndReplaceTheFlowOutput() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            route(page, "lowercase.ok", "lowercase2");
            route(page, "lowercase2.ok", "end");
            data(page, "output.text", "");
            data(page, "lowercase2.text", "lowercase.text");
            data(page, "output.text", "lowercase2.text");
            final String event = text(page, "#console-output");
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(new PipelineDataObservation(
                    text(page, "#flow-summary"),
                    selectedData(page, "lowercase2.text"),
                    selectedData(page, "output.text"),
                    wireSource(page, "output.text"),
                    page.locator(".data-wire").count(),
                    event,
                    text(page, "#compile-state"),
                    text(page, "#console-output").contains("\"text\": \"hello railix\"")
            )).isEqualTo(new PipelineDataObservation(
                    "2 Steps / 3 data / 2 routes",
                    "lowercase.text",
                    "lowercase2.text",
                    "lowercase2.text",
                    3,
                    dataEvent("data-connected", "output.text", "unmapped", "lowercase2.text"),
                    "Run passed",
                    true
            ));
        }
    }

    @Test
    void futureStepOutputIsNotOfferedForAnEarlierInput() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            route(page, "lowercase.ok", "lowercase2");
            route(page, "lowercase2.ok", "end");
            data(page, "output.text", "");
            data(page, "lowercase2.text", "lowercase.text");
            data(page, "output.text", "lowercase2.text");

            assertThat(dataOptions(page, "lowercase.text"))
                    .containsExactly("Unmapped", "App input · Text · string")
                    .doesNotContain("Lowercase 2 · Text · string");
        }
    }

    @Test
    void removingDataConnectedStepDeletesOnlyItsBindings() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            route(page, "lowercase.ok", "lowercase2");
            route(page, "lowercase2.ok", "end");
            data(page, "output.text", "");
            data(page, "lowercase2.text", "lowercase.text");
            data(page, "output.text", "lowercase2.text");
            click(page, "Remove Step lowercase2");
            final String removal = text(page, "#console-output");

            assertThat(new DataRemovalObservation(
                    text(page, "#flow-summary"),
                    selectedData(page, "lowercase.text"),
                    selectedData(page, "output.text"),
                    removal.contains("\"connectionsRemoved\": 2"),
                    page.locator(".data-wire").count()
            )).isEqualTo(new DataRemovalObservation(
                    "1 Step / 1 data / 0 routes",
                    "input.text",
                    "",
                    true,
                    1
            ));
        }
    }

    @Test
    void dataMappingsRemainAvailableAtSupportedWidths() {
        try (Page page = openPage()) {
            data(page, "lowercase.text", "");
            data(page, "lowercase.text", "input.text");

            assertThat(new ResponsiveDataObservation(
                    page.viewportSize().width,
                    dataVisible(page, "lowercase.text"),
                    dataInsideInspector(page, "lowercase.text"),
                    selectedData(page, "lowercase.text"),
                    text(page, "#flow-summary")
            )).isEqualTo(new ResponsiveDataObservation(
                    VIEWPORT_WIDTH,
                    true,
                    true,
                    "input.text",
                    "1 Step / 2 data / 1 route"
            ));
        }
    }

    @Test
    void dataMappingsStayUsableAtSupportedHeights() {
        try (Page page = openPage()) {

            assertThat(new DesktopDataObservation(
                    page.viewportSize().height,
                    dataVisible(page, "lowercase.text"),
                    dataInsideInspector(page, "lowercase.text"),
                    dataVisible(page, "output.text"),
                    dataInsideInspector(page, "output.text")
            )).isEqualTo(new DesktopDataObservation(viewportHeight(), true, true, true, true));
        }
    }

    @Test
    void dataMappingsExplainCompatibleSourceShapes() {
        try (Page page = openPage()) {
            assertThat(new CompatibleSourceObservation(
                    dataOptions(page, "lowercase.text"),
                    dataOptions(page, "output.text")
            )).isEqualTo(new CompatibleSourceObservation(
                    List.of("Unmapped", "App input · Text · string"),
                    List.of("Unmapped", "Lowercase · Text · string")
            ));
        }
    }

    @Test
    void initialFlowShowsCompilerOwnedDraftValidity() {
        try (Page page = openPage()) {
            waitForDraft(page, "Draft valid");

            assertThat(new DraftObservation(
                    text(page, "#draft-state"),
                    page.locator(".draft-diagnostic").count(),
                    text(page, "#console-output")
            )).isEqualTo(new DraftObservation(
                    "Draft valid",
                    0,
                    "Validate or run the flow to see compiler and execution output."
            ));
        }
    }

    @Test
    void disconnectedInputShowsCompilerFeedbackWithoutValidate() {
        try (Page page = openPage()) {
            data(page, "lowercase.text", "");
            waitForDraft(page, "1 issue");
            final String diagnostics = text(page, "#draft-diagnostics");

            assertThat(new LiveDiagnosticObservation(
                    text(page, "#draft-state"),
                    page.locator(".draft-diagnostic").count(),
                    diagnostics.contains("FLOW_REQUIRED_INPUT_UNMAPPED"),
                    diagnostics.contains("steps.lowercase.inputs.text"),
                    text(page, "#console-output")
            )).isEqualTo(new LiveDiagnosticObservation(
                    "1 issue",
                    1,
                    true,
                    true,
                    dataEvent("data-disconnected", "lowercase.text", "input.text", "unmapped")
            ));
        }
    }

    @Test
    void latestRapidRepairClearsStaleCompilerFeedback() {
        try (Page page = openPage()) {
            data(page, "lowercase.text", "");
            data(page, "lowercase.text", "input.text");
            waitForDraft(page, "Draft valid");
            page.waitForTimeout(200);

            assertThat(new DraftRepairObservation(
                    text(page, "#draft-state"),
                    page.locator(".draft-diagnostic").count(),
                    selectedData(page, "lowercase.text"),
                    text(page, "#console-output")
            )).isEqualTo(new DraftRepairObservation(
                    "Draft valid",
                    0,
                    "input.text",
                    dataEvent("data-connected", "lowercase.text", "unmapped", "input.text")
            ));
        }
    }

    @Test
    void newerDraftCancelsTheSupersededCompilerRequest() {
        try (Page page = openPageWithDraftAbortProbe()) {
            waitForDraft(page, "Draft valid");
            page.evaluate("window.railixDraftAbortProbe.armed = true");
            data(page, "lowercase.text", "");
            page.waitForFunction("window.railixDraftAbortProbe.waiting");
            data(page, "lowercase.text", "input.text");
            waitForDraft(page, "Draft valid");

            assertThat(page.evaluate("""
                    () => [
                      window.railixDraftAbortProbe.aborted,
                      window.railixDraftAbortProbe.waiting
                    ]
                    """)).isEqualTo(List.of(1, false));
        }
    }

    @Test
    void newerEditWinsOverStaleValidationResponse() {
        try (Page page = openPageWithResponseHold()) {
            waitForDraft(page, "Draft valid");
            holdResponse(page, "/api/compile");
            click(page, "Validate");
            waitForHeldResponse(page);

            data(page, "output.text", "");
            waitForDraft(page, "1 issue");
            releaseHeldResponse(page);

            assertThat(List.of(
                    text(page, "#compile-state"),
                    text(page, "#draft-state"),
                    text(page, "#console-output")
            )).containsExactly(
                    "Not validated",
                    "1 issue",
                    dataEvent("data-disconnected", "output.text", "lowercase.text", "unmapped")
            );
        }
    }

    @Test
    void newerEditWinsOverStaleRunResponse() {
        try (Page page = openPageWithResponseHold()) {
            waitForDraft(page, "Draft valid");
            holdResponse(page, "/api/run");
            click(page, "Run");
            waitForHeldResponse(page);

            data(page, "output.text", "");
            waitForDraft(page, "1 issue");
            releaseHeldResponse(page);

            assertThat(List.of(
                    text(page, "#compile-state"),
                    text(page, "#draft-state"),
                    text(page, "#console-output")
            )).containsExactly(
                    "Not validated",
                    "1 issue",
                    dataEvent("data-disconnected", "output.text", "lowercase.text", "unmapped")
            );
        }
    }

    @Test
    void addedStepShowsEveryCompilerDiagnosticWhileEditing() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            waitForDraft(page, "2 issues");
            final String diagnostics = text(page, "#draft-diagnostics");

            assertThat(new MultipleDraftObservation(
                    text(page, "#draft-state"),
                    page.locator(".draft-diagnostic").count(),
                    diagnostics.contains("FLOW_OUTCOME_UNHANDLED"),
                    diagnostics.contains("FLOW_STEP_UNREACHABLE"),
                    diagnostics.contains("steps.lowercase2.on.ok"),
                    text(page, "#console-output").contains("\"action\": \"step-added\"")
            )).isEqualTo(new MultipleDraftObservation("2 issues", 2, true, true, true, true));
        }
    }

    @Test
    void unavailableFutureSourceIsFilteredWhileEditing() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            route(page, "lowercase.ok", "lowercase2");
            route(page, "lowercase2.ok", "end");
            data(page, "output.text", "");
            data(page, "lowercase2.text", "lowercase.text");
            data(page, "output.text", "lowercase2.text");
            waitForDraft(page, "Draft valid");

            assertThat(List.of(
                    text(page, "#draft-state"),
                    dataOptions(page, "lowercase.text").contains("Lowercase 2 · Text · string")
            )).containsExactly("Draft valid", false);
        }
    }

    @Test
    void compilerFeedbackRemainsAvailableAtSupportedWidths() {
        try (Page page = openPage()) {
            data(page, "output.text", "");
            waitForDraft(page, "1 issue");
            page.locator("#draft-state").scrollIntoViewIfNeeded();

            assertThat(new MobileDraftObservation(
                    page.viewportSize().width,
                    page.locator("#draft-state").isVisible(),
                    insideInspector(page, "#draft-state"),
                    text(page, "#draft-diagnostics").contains("FLOW_OUTPUT_UNMAPPED"),
                    text(page, "#draft-diagnostics").contains("outputs.text")
            )).isEqualTo(new MobileDraftObservation(VIEWPORT_WIDTH, true, true, true, true));
        }
    }

    @Test
    void stepConfigurationDefaultIsVisible() {
        try (Page page = openPage()) {
            assertThat(new ConfigDefaultObservation(
                    configControl(page, "lowercase.languageTag").inputValue(),
                    text(page, "#step-config"),
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Reset lowercase.languageTag").setExact(true)
                    ).isDisabled()
            )).isEqualTo(new ConfigDefaultObservation(
                    "und",
                    "languageTagstring · language-tagDefault: \"und\"Reset",
                    true
            ));
        }
    }

    @Test
    void malformedLanguageTagShowsCompilerOwnedFeedbackWhileEditing() {
        try (Page page = openPage()) {
            configControl(page, "lowercase.languageTag").fill("not_a_tag");
            configControl(page, "lowercase.languageTag").press("Tab");
            waitForDraft(page, "1 issue");
            final String diagnostics = text(page, "#draft-diagnostics");

            assertThat(List.of(
                    text(page, "#draft-state"),
                    diagnostics.contains("FLOW_STEP_CONFIG_FORMAT_MISMATCH"),
                    diagnostics.contains("steps.lowercase.config.languageTag"),
                    diagnostics.contains("requires format language-tag")
            )).containsExactly("1 issue", true, true, true);
        }
    }

    @Test
    void stepConfigurationOverrideRunsThroughTheRealFlow() {
        try (Page page = openPage()) {
            configure(page, "lowercase.languageTag", "tr");
            sample(page, "{\"text\":\"I\"}");
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(new ConfigRunObservation(
                    text(page, "#compile-state"),
                    text(page, "#step-config").contains("Override"),
                    text(page, "#console-output").contains("\"text\": \"ı\"")
            )).isEqualTo(new ConfigRunObservation("Run passed", true, true));
        }
    }

    @Test
    void stringConfigurationBecomesAnOverrideWhileTyping() {
        try (Page page = openPage()) {
            configControl(page, "lowercase.languageTag").fill("tr");

            assertThat(new ConfigInputObservation(
                    text(page, "#step-config").contains("Override"),
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Reset lowercase.languageTag").setExact(true)
                    ).isEnabled()
            )).isEqualTo(new ConfigInputObservation(true, true));
        }
    }

    @Test
    void stepConfigurationResetRestoresTheVisibleDefault() {
        try (Page page = openPage()) {
            configure(page, "lowercase.languageTag", "tr");
            click(page, "Reset lowercase.languageTag");
            sample(page, "{\"text\":\"I\"}");
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(new ConfigResetObservation(
                    configControl(page, "lowercase.languageTag").inputValue(),
                    text(page, "#step-config").contains("Default"),
                    text(page, "#console-output").contains("\"text\": \"i\"")
            )).isEqualTo(new ConfigResetObservation("und", true, true));
        }
    }

    @Test
    void flowEventIngressCanBeEnabledAndSavedFromTheBrowser() throws Exception {
        try (Page page = openPage()) {
            addTrigger(page, "HTTP");
            page.getByLabel("Enable flow event", new Page.GetByLabelOptions().setExact(true)).check();
            waitForDraft(page, "Draft valid");
            final String source = Files.readString(saveFlow(page, "flow-event.flow.json").path());

            assertThat(List.of(
                    page.getByLabel("HTTP port", new Page.GetByLabelOptions().setExact(true)).inputValue(),
                    text(page, "#flow-event-endpoint"),
                    source.contains("\"config\":{\"flow\":true,\"port\":8080}"),
                    source.contains("\"id\":\"http-flow-events\"")
            )).containsExactly(
                    "8080",
                    "/v1/flows/lowercase-app/events",
                    true,
                    true
            );
        }
    }

    @Test
    void selectedStepEventIngressCanBeEnabledAndSavedFromTheBrowser() throws Exception {
        try (Page page = openPage()) {
            addTrigger(page, "HTTP");
            page.getByLabel(
                    "Enable Step event lowercase",
                    new Page.GetByLabelOptions().setExact(true)
            ).check();
            waitForDraft(page, "Draft valid");
            final String source = Files.readString(saveFlow(page, "step-event.flow.json").path());

            assertThat(List.of(
                    text(page, "#step-event-endpoint"),
                    source.contains("\"config\":{\"port\":8080,\"step\":\"lowercase\"}"),
                    source.contains("\"id\":\"http-lowercase-events\"")
            )).containsExactly(
                    "/v1/flows/lowercase-app/steps/lowercase/events",
                    true,
                    true
            );
        }
    }

    @Test
    void oneHttpPortEditUpdatesEveryEnabledIngress() throws Exception {
        try (Page page = openPage()) {
            addTrigger(page, "HTTP");
            page.getByLabel("New HTTP path", new Page.GetByLabelOptions().setExact(true)).fill("/orders");
            click(page, "Add HTTP route");
            page.getByLabel("Enable flow event", new Page.GetByLabelOptions().setExact(true)).check();
            page.getByLabel(
                    "Enable Step event lowercase",
                    new Page.GetByLabelOptions().setExact(true)
            ).check();
            final var port = page.getByLabel("HTTP port", new Page.GetByLabelOptions().setExact(true));
            port.fill("9090");
            port.press("Tab");
            waitForDraft(page, "Draft valid");
            final String source = Files.readString(saveFlow(page, "http-port.flow.json").path());

            assertThat(List.of(
                    port.inputValue(),
                    source.split("\"port\":9090", -1).length - 1,
                    source.contains("\"port\":8080")
            )).containsExactly("9090", 4, false);
        }
    }

    @Test
    void disablingIngressRemovesOnlyItsTrigger() throws Exception {
        try (Page page = openPage()) {
            addTrigger(page, "HTTP");
            final var flowEvent = page.getByLabel(
                    "Enable flow event",
                    new Page.GetByLabelOptions().setExact(true)
            );
            final var stepEvent = page.getByLabel(
                    "Enable Step event lowercase",
                    new Page.GetByLabelOptions().setExact(true)
            );
            flowEvent.check();
            stepEvent.check();
            flowEvent.click();
            waitForDraft(page, "Draft valid");
            final String source = Files.readString(saveFlow(page, "step-only-event.flow.json").path());

            assertThat(List.of(
                    source.contains("\"flow\":true"),
                    source.contains("\"step\":\"lowercase\""),
                    page.getByLabel("HTTP port", new Page.GetByLabelOptions().setExact(true)).isEnabled()
            )).containsExactly(false, true, true);
        }
    }

    @Test
    void disablingTheLastIngressDisablesItsUnusedPort() {
        try (Page page = openPage()) {
            addTrigger(page, "HTTP");
            page.getByLabel(
                    "Enable flow event",
                    new Page.GetByLabelOptions().setExact(true)
            ).check();
            click(page, "Remove HTTP route /event");
            selectTriggerForInspector(page, "http-flow-events");
            page.getByLabel(
                    "Enable flow event",
                    new Page.GetByLabelOptions().setExact(true)
            ).click();
            waitForDraft(page, "Draft valid");

            assertThat(page.getByLabel(
                    "HTTP port",
                    new Page.GetByLabelOptions().setExact(true)
            ).isDisabled()).isTrue();
        }
    }

    @Test
    void invalidHttpPortUsesLiveCompilerFeedback() {
        try (Page page = openPage()) {
            addTrigger(page, "HTTP");
            final var port = page.getByLabel("HTTP port", new Page.GetByLabelOptions().setExact(true));
            port.fill("0");
            port.press("Tab");
            waitForDraft(page, "1 issue");

            assertThat(text(page, "#draft-diagnostics"))
                    .contains("FLOW_TRIGGER_HTTP_PORT_OUT_OF_RANGE", "triggers[1].config.port");
        }
    }

    @Test
    void openingHttpIngressHydratesItsControls() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("http-open.flow.json", httpFlow()));
            selectTriggerForInspector(page, "custom");

            assertThat(List.of(
                    page.getByLabel("HTTP port", new Page.GetByLabelOptions().setExact(true)).inputValue(),
                    page.getByLabel(
                            "Enable flow event",
                            new Page.GetByLabelOptions().setExact(true)
                    ).isChecked(),
                    page.getByLabel(
                            "Enable Step event lowercase",
                            new Page.GetByLabelOptions().setExact(true)
                    ).isChecked(),
                    page.getByLabel(
                            "HTTP path /custom",
                            new Page.GetByLabelOptions().setExact(true)
                    ).inputValue()
            )).containsExactly("18081", true, true, "/custom");
        }
    }

    @Test
    void editingLoadedIngressPreservesItsCustomRoute() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("http-custom.flow.json", httpFlow()));
            selectTriggerForInspector(page, "flow-events");
            page.getByLabel(
                    "Enable flow event",
                    new Page.GetByLabelOptions().setExact(true)
            ).click();
            waitForDraft(page, "Draft valid");
            final String source = Files.readString(saveFlow(page, "http-custom-saved.flow.json").path());

            assertThat(List.of(
                    source.contains("\"path\":\"/custom\""),
                    source.contains("\"step\":\"lowercase\""),
                    source.contains("\"flow\":true")
            )).containsExactly(true, true, false);
        }
    }

    @Test
    void generatedHttpTriggerIdAvoidsAnExistingTriggerId() throws Exception {
        try (Page page = openPage()) {
            final String existing = withTriggers(
                    TURKISH_FLOW,
                    "{\"config\":{\"stdin\":true},"
                            + "\"id\":\"http-flow-events\",\"type\":\"cli\"}"
            );
            openFlow(page, flowFile("http-id-collision.flow.json", existing));
            addTrigger(page, "HTTP");
            page.getByLabel("Enable flow event", new Page.GetByLabelOptions().setExact(true)).check();
            waitForDraft(page, "Draft valid");
            final String source = Files.readString(saveFlow(page, "http-id.flow.json").path());

            assertThat(source).contains(
                    "\"id\":\"http-flow-events\"",
                    "\"id\":\"http-flow-events-2\""
            );
        }
    }

    @Test
    void customHttpRouteCanBeAddedAndSavedFromTheBrowser() throws Exception {
        try (Page page = openPage()) {
            addTrigger(page, "HTTP");
            page.getByLabel("New HTTP path", new Page.GetByLabelOptions().setExact(true)).fill("/orders");
            click(page, "Add HTTP route");
            waitForDraft(page, "Draft valid");
            final String source = Files.readString(saveFlow(page, "http-route.flow.json").path());

            assertThat(List.of(
                    page.getByLabel(
                            "New HTTP path",
                            new Page.GetByLabelOptions().setExact(true)
                    ).inputValue(),
                    source.contains("\"config\":{\"path\":\"/orders\",\"port\":8080}"),
                    source.contains("\"id\":\"http-route-2\"")
            )).containsExactly("", true, true);
        }
    }

    @Test
    void customHttpRouteCanBeEditedAndSavedFromTheBrowser() throws Exception {
        try (Page page = openPage()) {
            addTrigger(page, "HTTP");
            page.getByLabel("New HTTP path", new Page.GetByLabelOptions().setExact(true)).fill("/orders");
            click(page, "Add HTTP route");
            final var route = page.getByLabel(
                    "HTTP path /orders",
                    new Page.GetByLabelOptions().setExact(true)
            );
            route.fill("/customers");
            route.press("Tab");
            waitForDraft(page, "Draft valid");
            final String source = Files.readString(saveFlow(page, "http-route-edited.flow.json").path());

            assertThat(source).contains("\"path\":\"/customers\"").doesNotContain("\"path\":\"/orders\"");
        }
    }

    @Test
    void removingTheOnlyCustomHttpRouteDisablesItsPort() throws Exception {
        try (Page page = openPage()) {
            addTrigger(page, "HTTP");
            click(page, "Remove HTTP route /event");
            waitForDraft(page, "Draft valid");
            final String source = Files.readString(saveFlow(page, "http-route-removed.flow.json").path());

            assertThat(List.of(
                    source.contains("\"type\":\"http\""),
                    page.getByLabel(
                            "HTTP port",
                            new Page.GetByLabelOptions().setExact(true)
                    ).isDisabled()
            )).containsExactly(false, true);
        }
    }

    @Test
    void invalidCustomHttpPathUsesLiveCompilerFeedback() {
        try (Page page = openPage()) {
            addTrigger(page, "HTTP");
            final Locator route = page.getByLabel(
                    "HTTP path /event",
                    new Page.GetByLabelOptions().setExact(true)
            );
            route.fill("orders");
            route.press("Tab");
            waitForDraft(page, "1 issue");

            assertThat(text(page, "#draft-diagnostics"))
                    .contains("FLOW_TRIGGER_HTTP_PATH_INVALID", "triggers[1].config.path");
        }
    }

    @Test
    void repeatedCustomHttpRoutesUseDeterministicTriggerIds() throws Exception {
        try (Page page = openPage()) {
            addTrigger(page, "HTTP");
            page.getByLabel("New HTTP path", new Page.GetByLabelOptions().setExact(true)).fill("/orders");
            click(page, "Add HTTP route");
            page.getByLabel("New HTTP path", new Page.GetByLabelOptions().setExact(true)).fill("/users");
            click(page, "Add HTTP route");
            waitForDraft(page, "Draft valid");
            final String source = Files.readString(saveFlow(page, "http-routes.flow.json").path());

            assertThat(List.of(
                    source.contains("\"id\":\"http-route\""),
                    source.contains("\"id\":\"http-route-2\""),
                    source.contains("\"id\":\"http-route-3\""),
                    source.contains("\"path\":\"/orders\""),
                    source.contains("\"path\":\"/users\""),
                    source.split("\"port\":8080", -1).length - 1
            )).containsExactly(true, true, true, true, true, 3);
        }
    }

    @Test
    void httpIngressProducesDesktopAndMobileVisualProof() throws Exception {
        try (Page page = openPage()) {
            addTrigger(page, "HTTP");
            page.getByLabel("New HTTP path", new Page.GetByLabelOptions().setExact(true)).fill("/greet");
            click(page, "Add HTTP route");
            page.getByLabel("Enable flow event", new Page.GetByLabelOptions().setExact(true)).check();
            page.getByLabel(
                    "Enable Step event lowercase",
                    new Page.GetByLabelOptions().setExact(true)
            ).check();
            waitForDraft(page, "Draft valid");
            page.getByLabel("HTTP ingress", new Page.GetByLabelOptions().setExact(true))
                    .scrollIntoViewIfNeeded();
            final String suffix = VIEWPORT_WIDTH == 320 ? "-mobile" : "";
            final Path proof = REPOSITORY.resolve(
                    "output/playwright/railix-creator-http-ingress" + suffix + ".png"
            );
            Files.createDirectories(proof.getParent());
            page.screenshot(new Page.ScreenshotOptions().setPath(proof).setFullPage(true));

            assertThat(Files.size(proof)).isGreaterThan(10_000);
        }
    }

    @Test
    void socketIngressCanBeEnabledAndSavedFromTheBrowser() throws Exception {
        try (Page page = openPage()) {
            addTrigger(page, "Socket");
            label(page, "Enable socket ingress").check();
            waitForDraft(page, "Draft valid");
            final String source = Files.readString(saveFlow(page, "socket.flow.json").path());

            assertThat(List.of(
                    label(page, "Socket port").inputValue(),
                    label(page, "Socket timeout milliseconds").inputValue(),
                    label(page, "Socket maximum connections").inputValue(),
                    text(page, "#socket-endpoint"),
                    source.contains("\"id\":\"socket-events\""),
                    source.contains("\"maxConnections\":32"),
                    source.contains("\"port\":17000"),
                    source.contains("\"timeoutMillis\":30000")
            )).containsExactly(
                    "17000",
                    "30000",
                    "32",
                    "127.0.0.1:17000 · 4-byte length + JSON",
                    true,
                    true,
                    true,
                    true
            );
        }
    }

    @Test
    void socketPortCanBeEditedFromTheBrowser() throws Exception {
        try (Page page = openPage()) {
            addTrigger(page, "Socket");
            label(page, "Enable socket ingress").check();
            label(page, "Socket port").fill("17001");
            label(page, "Socket port").press("Tab");
            waitForDraft(page, "Draft valid");
            final String source = Files.readString(saveFlow(page, "socket-port.flow.json").path());

            assertThat(List.of(
                    text(page, "#socket-endpoint"),
                    source.contains("\"port\":17001")
            )).containsExactly("127.0.0.1:17001 · 4-byte length + JSON", true);
        }
    }

    @Test
    void socketTimeoutCanBeEditedFromTheBrowser() throws Exception {
        try (Page page = openPage()) {
            addTrigger(page, "Socket");
            label(page, "Enable socket ingress").check();
            label(page, "Socket timeout milliseconds").fill("45000");
            label(page, "Socket timeout milliseconds").press("Tab");
            waitForDraft(page, "Draft valid");

            assertThat(Files.readString(saveFlow(page, "socket-timeout.flow.json").path()))
                    .contains("\"timeoutMillis\":45000");
        }
    }

    @Test
    void socketConnectionBoundCanBeEditedFromTheBrowser() throws Exception {
        try (Page page = openPage()) {
            addTrigger(page, "Socket");
            label(page, "Enable socket ingress").check();
            label(page, "Socket maximum connections").fill("64");
            label(page, "Socket maximum connections").press("Tab");
            waitForDraft(page, "Draft valid");

            assertThat(Files.readString(saveFlow(page, "socket-connections.flow.json").path()))
                    .contains("\"maxConnections\":64");
        }
    }

    @Test
    void openingSocketIngressHydratesItsControls() throws Exception {
        try (Page page = openPage()) {
            final String source = withTriggers(
                    TURKISH_FLOW,
                    "{\"config\":{\"maxConnections\":17,\"port\":18082,"
                            + "\"timeoutMillis\":12000},\"id\":\"events\",\"type\":\"socket\"}"
            );
            openFlow(page, flowFile("socket-open.flow.json", source));
            selectTriggerForInspector(page, "events");

            assertThat(List.of(
                    label(page, "Enable socket ingress").isChecked(),
                    label(page, "Socket port").inputValue(),
                    label(page, "Socket timeout milliseconds").inputValue(),
                    label(page, "Socket maximum connections").inputValue(),
                    text(page, "#socket-endpoint")
            )).containsExactly(
                    true,
                    "18082",
                    "12000",
                    "17",
                    "127.0.0.1:18082 · 4-byte length + JSON"
            );
        }
    }

    @Test
    void disablingSocketIngressRemovesOnlyItsTrigger() throws Exception {
        try (Page page = openPage()) {
            addTrigger(page, "HTTP");
            label(page, "Enable flow event").check();
            addTrigger(page, "Socket");
            label(page, "Enable socket ingress").check();
            label(page, "Enable socket ingress").click();
            waitForDraft(page, "Draft valid");
            final String source = Files.readString(saveFlow(page, "socket-removed.flow.json").path());

            assertThat(List.of(
                    source.contains("\"type\":\"socket\""),
                    source.contains("\"type\":\"http\""),
                    page.getByLabel(
                            "Socket port",
                            new Page.GetByLabelOptions().setExact(true)
                    ).isDisabled()
            )).containsExactly(false, true, true);
        }
    }

    @Test
    void generatedSocketTriggerIdAvoidsAnExistingTriggerId() throws Exception {
        try (Page page = openPage()) {
            final String source = withTriggers(
                    TURKISH_FLOW,
                    "{\"config\":{\"stdin\":true},"
                            + "\"id\":\"socket-events\",\"type\":\"cli\"}"
            );
            openFlow(page, flowFile("socket-id-collision.flow.json", source));
            addTrigger(page, "Socket");
            label(page, "Enable socket ingress").check();
            waitForDraft(page, "Draft valid");

            assertThat(Files.readString(saveFlow(page, "socket-id.flow.json").path()))
                    .contains("\"id\":\"socket-events\"", "\"id\":\"socket-events-2\"");
        }
    }

    @Test
    void invalidSocketPortUsesLiveCompilerFeedback() {
        try (Page page = openPage()) {
            addTrigger(page, "Socket");
            label(page, "Enable socket ingress").check();
            label(page, "Socket port").fill("0");
            label(page, "Socket port").press("Tab");
            waitForDraft(page, "1 issue");

            assertThat(text(page, "#draft-diagnostics"))
                    .contains("FLOW_TRIGGER_SOCKET_PORT_OUT_OF_RANGE", "triggers[1].config.port");
        }
    }

    @Test
    void invalidSocketTimeoutUsesLiveCompilerFeedback() {
        try (Page page = openPage()) {
            addTrigger(page, "Socket");
            label(page, "Enable socket ingress").check();
            label(page, "Socket timeout milliseconds").fill("0");
            label(page, "Socket timeout milliseconds").press("Tab");
            waitForDraft(page, "1 issue");

            assertThat(text(page, "#draft-diagnostics"))
                    .contains("FLOW_TRIGGER_SOCKET_TIMEOUT_OUT_OF_RANGE", "triggers[1].config.timeoutMillis");
        }
    }

    @Test
    void invalidSocketConnectionBoundUsesLiveCompilerFeedback() {
        try (Page page = openPage()) {
            addTrigger(page, "Socket");
            label(page, "Enable socket ingress").check();
            label(page, "Socket maximum connections").fill("0");
            label(page, "Socket maximum connections").press("Tab");
            waitForDraft(page, "1 issue");

            assertThat(text(page, "#draft-diagnostics")).contains(
                    "FLOW_TRIGGER_SOCKET_MAX_CONNECTIONS_OUT_OF_RANGE",
                    "triggers[1].config.maxConnections"
            );
        }
    }

    @Test
    void socketAndHttpPortConflictUsesLiveCompilerFeedback() {
        try (Page page = openPage()) {
            addTrigger(page, "HTTP");
            addTrigger(page, "Socket");
            label(page, "Enable socket ingress").check();
            label(page, "Socket port").fill("8080");
            label(page, "Socket port").press("Tab");
            waitForDraft(page, "1 issue");

            assertThat(text(page, "#draft-diagnostics"))
                    .contains("FLOW_TRIGGER_NETWORK_PORT_CONFLICT", "triggers[2].config.port");
        }
    }

    @Test
    void scheduleCanBeAddedAndSavedForAnInputlessFlow() throws Exception {
        try (CreatorServer server = scheduledCreator(); Page page = openPage(server)) {
            openFlow(page, flowFile("inputless.flow.json", scheduledFlow()));
            addTrigger(page, "Scheduled");
            waitForDraft(page, "Draft valid");
            final String source = Files.readString(saveFlow(page, "scheduled.flow.json").path());

            assertThat(List.of(
                    label(page, "Schedule schedule interval milliseconds").inputValue(),
                    label(page, "Schedule schedule initial delay milliseconds").inputValue(),
                    label(page, "Schedule schedule maximum concurrent runs").inputValue(),
                    source.contains("\"id\":\"schedule\""),
                    source.contains("\"intervalMillis\":60000"),
                    source.contains("\"initialDelayMillis\":0"),
                    source.contains("\"maxConcurrentRuns\":1")
            )).containsExactly("60000", "0", "1", true, true, true, true);
        }
    }

    @Test
    void repeatedSchedulesUseDeterministicTriggerIds() throws Exception {
        try (CreatorServer server = scheduledCreator(); Page page = openPage(server)) {
            openFlow(page, flowFile("inputless.flow.json", scheduledFlow()));
            addTrigger(page, "Scheduled");
            click(page, "Add schedule");
            waitForDraft(page, "Draft valid");
            final String source = Files.readString(saveFlow(page, "schedules.flow.json").path());

            assertThat(source).contains("\"id\":\"schedule\"", "\"id\":\"schedule-2\"");
        }
    }

    @Test
    void scheduleIntervalCanBeEditedFromTheBrowser() throws Exception {
        try (CreatorServer server = scheduledCreator(); Page page = openPage(server)) {
            openFlow(page, flowFile("inputless.flow.json", scheduledFlow()));
            addTrigger(page, "Scheduled");
            label(page, "Schedule schedule interval milliseconds").fill("5000");
            label(page, "Schedule schedule interval milliseconds").press("Tab");
            waitForDraft(page, "Draft valid");

            assertThat(Files.readString(saveFlow(page, "schedule-interval.flow.json").path()))
                    .contains("\"intervalMillis\":5000");
        }
    }

    @Test
    void scheduleInitialDelayCanBeEditedFromTheBrowser() throws Exception {
        try (CreatorServer server = scheduledCreator(); Page page = openPage(server)) {
            openFlow(page, flowFile("inputless.flow.json", scheduledFlow()));
            addTrigger(page, "Scheduled");
            label(page, "Schedule schedule initial delay milliseconds").fill("250");
            label(page, "Schedule schedule initial delay milliseconds").press("Tab");
            waitForDraft(page, "Draft valid");

            assertThat(Files.readString(saveFlow(page, "schedule-delay.flow.json").path()))
                    .contains("\"initialDelayMillis\":250");
        }
    }

    @Test
    void scheduleConcurrencyBoundCanBeEditedFromTheBrowser() throws Exception {
        try (CreatorServer server = scheduledCreator(); Page page = openPage(server)) {
            openFlow(page, flowFile("inputless.flow.json", scheduledFlow()));
            addTrigger(page, "Scheduled");
            label(page, "Schedule schedule maximum concurrent runs").fill("4");
            label(page, "Schedule schedule maximum concurrent runs").press("Tab");
            waitForDraft(page, "Draft valid");

            assertThat(Files.readString(saveFlow(page, "schedule-concurrency.flow.json").path()))
                    .contains("\"maxConcurrentRuns\":4");
        }
    }

    @Test
    void invalidScheduleIntervalUsesLiveCompilerFeedback() throws Exception {
        try (CreatorServer server = scheduledCreator(); Page page = openPage(server)) {
            openFlow(page, flowFile("inputless.flow.json", scheduledFlow()));
            addTrigger(page, "Scheduled");
            label(page, "Schedule schedule interval milliseconds").fill("0");
            label(page, "Schedule schedule interval milliseconds").press("Tab");
            waitForDraft(page, "1 issue");

            assertThat(text(page, "#draft-diagnostics")).contains(
                    "FLOW_TRIGGER_SCHEDULED_INTERVAL_OUT_OF_RANGE",
                    "triggers[0].config.intervalMillis"
            );
        }
    }

    @Test
    void invalidScheduleInitialDelayUsesLiveCompilerFeedback() throws Exception {
        try (CreatorServer server = scheduledCreator(); Page page = openPage(server)) {
            openFlow(page, flowFile("inputless.flow.json", scheduledFlow()));
            addTrigger(page, "Scheduled");
            label(page, "Schedule schedule initial delay milliseconds").fill("-1");
            label(page, "Schedule schedule initial delay milliseconds").press("Tab");
            waitForDraft(page, "1 issue");

            assertThat(text(page, "#draft-diagnostics")).contains(
                    "FLOW_TRIGGER_SCHEDULED_INITIAL_DELAY_OUT_OF_RANGE",
                    "triggers[0].config.initialDelayMillis"
            );
        }
    }

    @Test
    void invalidScheduleConcurrencyBoundUsesLiveCompilerFeedback() throws Exception {
        try (CreatorServer server = scheduledCreator(); Page page = openPage(server)) {
            openFlow(page, flowFile("inputless.flow.json", scheduledFlow()));
            addTrigger(page, "Scheduled");
            label(page, "Schedule schedule maximum concurrent runs").fill("0");
            label(page, "Schedule schedule maximum concurrent runs").press("Tab");
            waitForDraft(page, "1 issue");

            assertThat(text(page, "#draft-diagnostics")).contains(
                    "FLOW_TRIGGER_SCHEDULED_MAX_CONCURRENT_RUNS_OUT_OF_RANGE",
                    "triggers[0].config.maxConcurrentRuns"
            );
        }
    }

    @Test
    void removingOneSchedulePreservesTheOther() throws Exception {
        try (CreatorServer server = scheduledCreator(); Page page = openPage(server)) {
            openFlow(page, flowFile("inputless.flow.json", scheduledFlow()));
            addTrigger(page, "Scheduled");
            click(page, "Add schedule");
            click(page, "Remove schedule schedule");
            waitForDraft(page, "Draft valid");
            final String source = Files.readString(saveFlow(page, "schedule-removed.flow.json").path());

            assertThat(List.of(
                    source.contains("\"id\":\"schedule\""),
                    source.contains("\"id\":\"schedule-2\""),
                    label(page, "Schedule schedule-2 interval milliseconds").isVisible()
            )).containsExactly(false, true, true);
        }
    }

    @Test
    void openingSchedulesHydratesTheirControls() throws Exception {
        try (CreatorServer server = scheduledCreator(); Page page = openPage(server)) {
            final String source = scheduledFlow().replace(
                    "\"triggers\":[]",
                    "\"triggers\":[{\"config\":{\"initialDelayMillis\":500,"
                            + "\"intervalMillis\":10000,\"maxConcurrentRuns\":3},"
                            + "\"id\":\"nightly\",\"type\":\"scheduled\"}]"
            );
            openFlow(page, flowFile("schedule-open.flow.json", source));
            selectTriggerForInspector(page, "nightly");

            assertThat(List.of(
                    label(page, "Schedule nightly interval milliseconds").inputValue(),
                    label(page, "Schedule nightly initial delay milliseconds").inputValue(),
                    label(page, "Schedule nightly maximum concurrent runs").inputValue()
            )).containsExactly("10000", "500", "3");
        }
    }

    @Test
    void scheduleRefusesToInventInputForAnInputFlow() {
        try (Page page = openPage()) {
            addTrigger(page, "Scheduled");
            waitForDraft(page, "1 issue");

            assertThat(text(page, "#draft-diagnostics"))
                    .contains("FLOW_TRIGGER_SCHEDULED_INPUTS_UNSUPPORTED", "triggers[1].config");
        }
    }

    @Test
    void applicationIngressProducesDesktopAndMobileVisualProof() throws Exception {
        try (CreatorServer server = scheduledCreator(); Page page = openPage(server)) {
            openFlow(page, flowFile("inputless.flow.json", scheduledFlow()));
            addTrigger(page, "Socket");
            label(page, "Enable socket ingress").check();
            addTrigger(page, "Scheduled");
            waitForDraft(page, "Draft valid");
            selectTriggerForInspector(page, "socket-events");
            label(page, "Socket ingress").scrollIntoViewIfNeeded();
            final String suffix = VIEWPORT_WIDTH == 320 ? "-mobile" : "";
            final Path proof = REPOSITORY.resolve(
                    "output/playwright/railix-creator-application-ingress" + suffix + ".png"
            );
            Files.createDirectories(proof.getParent());
            page.screenshot(new Page.ScreenshotOptions().setPath(proof).setFullPage(true));

            assertThat(Files.size(proof)).isGreaterThan(10_000);
        }
    }

    @Test
    void flowInputCanBeAddedWithoutInventingSampleData() {
        try (Page page = openPage()) {
            click(page, "Add flow input");
            waitForDraft(page, "Draft valid");
            click(page, "Run");
            waitForState(page, "Run failed");

            assertThat(new FlowInputAddObservation(
                    flowPortName(page, "input", "input").inputValue(),
                    flowPortShape(page, "input", "input").inputValue(),
                    sampleValue(page),
                    text(page, "#console-output").contains("FLOW_INPUT_REQUIRED"),
                    text(page, "#console-output").contains("inputs.input")
            )).isEqualTo(new FlowInputAddObservation(
                    "input", "string", "{\"text\":\"Hello RAILIX\"}", true, true
            ));
        }
    }

    @Test
    void flowInputRenameUpdatesOnlyItsCanonicalConnection() {
        try (Page page = openPage()) {
            sample(page, "{\"text\":\"json unchanged\"}");
            eventFormat(page, "YAML");
            sample(page, "text: \"yaml unchanged\"");
            eventFormat(page, "XML");
            sample(page, "<object><field name=\"text\"><string>xml unchanged</string></field></object>");
            eventFormat(page, "JSON");
            flowPortName(page, "input", "text").fill("message");
            flowPortName(page, "input", "text").press("Tab");
            waitForDraft(page, "Draft valid");

            final String json = sampleValue(page);
            eventFormat(page, "YAML");
            final String yaml = sampleValue(page);
            eventFormat(page, "XML");
            final String xml = sampleValue(page);
            final String authoring = text(page, "#console-output");

            assertThat(List.of(
                    selectedData(page, "lowercase.text"),
                    json,
                    yaml,
                    xml,
                    authoring.contains("\"connectionsUpdated\": 1"),
                    authoring.contains("sampleRenamed")
            )).containsExactly(
                    "input.message",
                    "{\"text\":\"json unchanged\"}",
                    "text: \"yaml unchanged\"",
                    "<object><field name=\"text\"><string>xml unchanged</string></field></object>",
                    true,
                    false
            );
        }
    }

    @Test
    void openingAFlowPreservesEveryEventFormatDraft() throws Exception {
        try (Page page = openPage()) {
            sample(page, "{\"text\":\"json kept\"}");
            eventFormat(page, "YAML");
            sample(page, "text: \"yaml kept\"");
            eventFormat(page, "XML");
            sample(page, "<object><field name=\"text\"><string>xml kept</string></field></object>");
            openFlow(page, flowFile("event-drafts-open.flow.json", TURKISH_FLOW));

            final String xml = sampleValue(page);
            eventFormat(page, "JSON");
            final String json = sampleValue(page);
            eventFormat(page, "YAML");

            assertThat(List.of(xml, json, sampleValue(page))).containsExactly(
                    "<object><field name=\"text\"><string>xml kept</string></field></object>",
                    "{\"text\":\"json kept\"}",
                    "text: \"yaml kept\""
            );
        }
    }

    @Test
    void oversizedEventSourceIsNotRetainedOverTheLastAcceptedDraft() {
        try (Page page = openPage()) {
            sample(page, "{\"text\":\"kept\"}");
            sample(page, "\u20ac".repeat((1_048_576 / 3) + 1));

            assertThat(List.of(
                    sampleValue(page),
                    text(page, "#console-output").contains("EVENT_SOURCE_TOO_LARGE"),
                    text(page, "#console-output").contains("event.source")
            )).containsExactly("{\"text\":\"kept\"}", true, true);
        }
    }

    @Test
    void renamedFlowRejectsAStaleEventWithExplicitInputDiagnostics() {
        try (Page page = openPage()) {
            flowPortName(page, "input", "text").fill("message");
            flowPortName(page, "input", "text").press("Tab");
            waitForDraft(page, "Draft valid");
            click(page, "Run");
            waitForState(page, "Run failed");

            assertThat(text(page, "#console-output"))
                    .contains("FLOW_INPUT_UNKNOWN", "inputs.text", "FLOW_INPUT_REQUIRED", "inputs.message");
        }
    }

    @Test
    void flowInputShapeChangeUsesCompilerDiagnostics() {
        try (Page page = openPage()) {
            flowPortShape(page, "input", "text").selectOption("number");
            waitForDraft(page, "1 issue");

            assertThat(new FlowShapeObservation(
                    flowPortShape(page, "input", "text").inputValue(),
                    text(page, "#draft-diagnostics").contains("FLOW_CONNECTION_TYPE_MISMATCH"),
                    text(page, "#draft-diagnostics").contains("connections[0]")
            )).isEqualTo(new FlowShapeObservation("number", true, true));
        }
    }

    @Test
    void flowOutputCanBeAddedWithoutInventingAMapping() {
        try (Page page = openPage()) {
            click(page, "Add flow output");
            waitForDraft(page, "1 issue");

            assertThat(new FlowOutputAddObservation(
                    flowPortName(page, "output", "output").inputValue(),
                    selectedData(page, "output.output"),
                    text(page, "#draft-diagnostics").contains("FLOW_OUTPUT_UNMAPPED"),
                    text(page, "#draft-diagnostics").contains("outputs.output")
            )).isEqualTo(new FlowOutputAddObservation("output", "", true, true));
        }
    }

    @Test
    void flowOutputRemovalDeletesItsAffectedConnection() {
        try (Page page = openPage()) {
            click(page, "Remove flow output text");
            waitForDraft(page, "Draft valid");

            assertThat(new FlowOutputRemoveObservation(
                    text(page, "#flow-summary"),
                    wireCount(page, "output.text"),
                    text(page, "#console-output").contains("\"connectionsRemoved\": 1")
            )).isEqualTo(new FlowOutputRemoveObservation("1 Step / 1 data / 1 route", 0, true));
        }
    }

    @Test
    void rawSampleJsonRunsWithoutHiddenFieldConstruction() {
        try (Page page = openPage()) {
            sample(page, "{\"text\":\"MiXeD\"}");
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(new SampleRunObservation(
                    sampleValue(page),
                    text(page, "#console-output").contains("\"text\": \"mixed\"")
            )).isEqualTo(new SampleRunObservation("{\"text\":\"MiXeD\"}", true));
        }
    }

    @Test
    void rawYamlEventRunsThroughTheJavaNormalizer() {
        try (Page page = openPage()) {
            eventFormat(page, "YAML");
            sample(page, "text: \"MiXeD\"");
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(text(page, "#console-output")).contains("\"text\": \"mixed\"");
        }
    }

    @Test
    void rawXmlEventRunsThroughTheJavaNormalizer() {
        try (Page page = openPage()) {
            eventFormat(page, "XML");
            sample(page, "<object><field name=\"text\"><string>MiXeD</string></field></object>");
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(text(page, "#console-output")).contains("\"text\": \"mixed\"");
        }
    }

    @Test
    void eventFormatsKeepIndependentSessionDrafts() {
        try (Page page = openPage()) {
            sample(page, "{\"text\":\"json draft\"}");
            eventFormat(page, "YAML");
            sample(page, "text: \"yaml draft\"");
            eventFormat(page, "XML");
            sample(page, "<object><field name=\"text\"><string>xml draft</string></field></object>");

            eventFormat(page, "JSON");
            final String json = sampleValue(page);
            eventFormat(page, "YAML");
            final String yaml = sampleValue(page);
            eventFormat(page, "XML");
            final String xml = sampleValue(page);

            assertThat(List.of(json, yaml, xml)).containsExactly(
                    "{\"text\":\"json draft\"}",
                    "text: \"yaml draft\"",
                    "<object><field name=\"text\"><string>xml draft</string></field></object>"
            );
        }
    }

    @Test
    void malformedJsonEventReturnsTheJavaNormalizerDiagnostic() {
        try (Page page = openPage()) {
            sample(page, "{\"x\":}");
            click(page, "Run");
            waitForState(page, "Run failed");

            assertThat(text(page, "#console-output"))
                    .contains("DATA_JSON_INVALID", "Expected a JSON value.", "\"line\": 1", "\"column\": 6");
        }
    }

    @Test
    void contractConfigurationAndSampleRemainAvailableAtSupportedWidths() {
        try (Page page = openPage()) {
            final boolean configurationVisible =
                    configControl(page, "lowercase.languageTag").isVisible();
            selectApplicationForInspector(page);

            assertThat(new MobileAuthoringObservation(
                    page.viewportSize().width,
                    configurationVisible,
                    page.getByRole(
                            AriaRole.BUTTON,
                            new Page.GetByRoleOptions().setName("Add flow input").setExact(true)
                    ).isVisible(),
                    page.locator("#sample-input").isVisible()
            )).isEqualTo(new MobileAuthoringObservation(VIEWPORT_WIDTH, true, true, true));
        }
    }

    @Test
    void saveDownloadsCanonicalFlowWithoutSampleInput() throws Exception {
        try (Page page = openPage()) {
            configure(page, "lowercase.languageTag", "tr");
            sample(page, "{\"text\":\"must-not-persist\"}");
            final SavedDownload saved = saveFlow(page, "saved.flow.json");

            assertThat(new SavedFlowObservation(saved.suggestedName(), Files.readString(saved.path())))
                    .isEqualTo(new SavedFlowObservation("railix.flow.json", TURKISH_FLOW));
        }
    }

    @Test
    void downloadedFlowReopensInAFreshPageAndRunsWithoutSemanticDrift() throws Exception {
        final Path saved;
        try (Page page = openPage()) {
            configure(page, "lowercase.languageTag", "tr");
            saved = saveFlow(page, "fresh-page.flow.json").path();
        }

        try (Page page = openPage()) {
            openFlow(page, saved);
            sample(page, "{\"text\":\"I\"}");
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(new ReopenedFlowObservation(
                    text(page, "#project-name"),
                    configControl(page, "lowercase.languageTag").inputValue(),
                    selectedData(page, "lowercase.text"),
                    selectedData(page, "output.text"),
                    selectedRoute(page, "lowercase.ok"),
                    text(page, "#flow-summary"),
                    text(page, "#console-output").contains("\"text\": \"ı\"")
            )).isEqualTo(new ReopenedFlowObservation(
                    "lowercase-app", "tr", "input.text", "lowercase.text", "end",
                    "1 Step / 2 data / 1 route", true
            ));
        }
    }

    @Test
    void reopenedFlowResavesByteForByte() throws Exception {
        final Path first;
        try (Page page = openPage()) {
            configure(page, "lowercase.languageTag", "tr");
            first = saveFlow(page, "first.flow.json").path();
        }

        try (Page page = openPage()) {
            openFlow(page, first);
            final Path second = saveFlow(page, "second.flow.json").path();

            assertThat(second).hasSameBinaryContentAs(first);
        }
    }

    @Test
    void nestedMappingFlowRunsThroughThePackagedBrowser() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("nested-run.flow.json", NESTED_FLOW));
            sample(page, "{\"payload\":{\"person\":{\"name\":\"HELLO RAILIX\"}}}");
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(text(page, "#console-output"))
                    .contains("\"response\"", "\"person\"", "\"name\": \"hello railix\"");
        }
    }

    @Test
    void dataWorkbenchProducesDesktopAndMobileVisualProof() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("data-workbench-proof.flow.json", MULTI_MAPPING_FLOW));
            selectStepForInspector(page, "lowercase");
            openMappingFields(page, "output.response", 2);
            page.locator("#data-mappings").scrollIntoViewIfNeeded();
            final String suffix = VIEWPORT_WIDTH == 320 ? "-mobile" : "";
            final Path proof = REPOSITORY.resolve(
                    "output/playwright/railix-creator-data-workbench" + suffix + ".png"
            );
            Files.createDirectories(proof.getParent());
            page.screenshot(new Page.ScreenshotOptions().setPath(proof).setFullPage(true));

            assertThat(Files.size(proof)).isGreaterThan(10_000);
        }
    }

    @Test
    void openedSourcePathCanBeEditedAndRunWithoutFlattening() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("nested-source-path.flow.json", NESTED_FLOW));
            replacePath(page, "Source", "lowercase.text", List.of("account", "name"));
            waitForDraft(page, "Draft valid");
            sample(page, "{\"payload\":{\"account\":{\"name\":\"HELLO RAILIX\"}}}");
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(text(page, "#console-output"))
                    .contains("\"person\"", "\"name\": \"hello railix\"");
        }
    }

    @Test
    void openedTargetPathCanBeEditedAndRunWithoutFlattening() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("nested-target-path.flow.json", NESTED_FLOW));
            replacePath(page, "Target", "output.response", List.of("profile", "name"));
            waitForDraft(page, "Draft valid");
            sample(page, "{\"payload\":{\"person\":{\"name\":\"HELLO RAILIX\"}}}");
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(text(page, "#console-output"))
                    .contains("\"profile\"", "\"name\": \"hello railix\"")
                    .doesNotContain("\"person\"");
        }
    }

    @Test
    void changingAMappingSourcePreservesItsExplicitFields() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile(
                    "nested-source-change.flow.json",
                    NESTED_FLOW.replace(
                            "\"inputs\":{\"payload\":\"object\"}",
                            "\"inputs\":{\"backup\":\"object\",\"payload\":\"object\"}"
                    )
            ));
            mappingAdvancedSource(page, "lowercase.text").selectOption("input.backup");

            assertThat(pathBreadcrumb(page, "Source", "lowercase.text"))
                    .isEqualTo("Person › Name");
        }
    }

    @Test
    void nestedMappingShowsTheCanonicalExpressionPreview() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("nested-preview.flow.json", NESTED_FLOW));

            assertThat(mappingPreview(page, "lowercase.text"))
                    .isEqualTo("App input · Payload · Person › Name → Lowercase · Text");
        }
    }

    @Test
    void conversionCanBeRemovedRepairedAndRun() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("conversion-authoring.flow.json", CONVERSION_FLOW));
            mappingConversion(page, "output.number").selectOption("");
            waitForDraft(page, "1 issue");
            mappingConversion(page, "output.number").selectOption("string-to-number");
            waitForDraft(page, "Draft valid");
            sample(page, "{\"text\":\"42\"}");
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(text(page, "#console-output")).contains("\"number\": 42");
        }
    }

    @Test
    void explicitNullDefaultRemainsDistinctFromNoDefault() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("nested-null-default.flow.json", NESTED_FLOW));
            mappingDefault(page, "lowercase.text").click();
            final String present = mappingPreview(page, "lowercase.text");
            mappingDefault(page, "lowercase.text").click();
            final String absent = mappingPreview(page, "lowercase.text");

            assertThat(List.of(present.contains("fallback null"), absent.contains("fallback null")))
                    .containsExactly(true, false);
        }
    }

    @Test
    void authoredDefaultRunsWhenTheNestedSourceIsMissing() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("nested-default.flow.json", NESTED_FLOW));
            mappingDefault(page, "lowercase.text").click();
            mappingField(page, "Default value", "lowercase.text").fill("\"UNKNOWN\"");
            mappingField(page, "Default value", "lowercase.text").press("Tab");
            waitForDraft(page, "Draft valid");
            sample(page, "{\"payload\":{\"person\":{}}}");
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(text(page, "#console-output")).contains("\"name\": \"unknown\"");
        }
    }

    @Test
    void ordinaryTargetDoesNotOfferASecondSource() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("nested-single-source.flow.json", NESTED_FLOW));
            dataControl(page, "output.response");

            assertThat(page.getByRole(
                    AriaRole.COMBOBOX,
                    new Page.GetByRoleOptions().setName("Add source output.response").setExact(true)
            ).count()).isZero();
        }
    }

    @Test
    void removingOneSourcePreservesTheOtherNestedMapping() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("nested-remove-source.flow.json", MULTI_MAPPING_FLOW));
            dataControl(page, "output.response");
            click(page, "Disconnect App input Payload from App output Response");
            waitForDraft(page, "Draft valid");

            assertThat(List.of(
                    page.getByRole(AriaRole.COMBOBOX, new Page.GetByRoleOptions()
                            .setName("Map output.response").setExact(true)).count(),
                    page.getByRole(AriaRole.COMBOBOX, new Page.GetByRoleOptions()
                            .setName("Map output.response 2").setExact(true)).count(),
                    text(page, "#data-mappings").contains("Advanced topology")
            )).containsExactly(1, 0, false);
        }
    }

    @Test
    void invalidSourceArrayIndexStaysLocalAndDoesNotMutateTheFlow() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("nested-invalid-editor.flow.json", NESTED_FLOW));
            addPathSegment(page, "Source", "lowercase.text", "index", "-1");

            assertThat(List.of(
                    text(page, "#console-output").contains("MAPPING_PATH_SEGMENT_INVALID"),
                    pathBreadcrumb(page, "Source", "lowercase.text")
            )).containsExactly(
                    true,
                    "Person › Name"
            );
        }
    }

    @Test
    void emptyTargetFieldStaysLocalAndDoesNotMutateTheFlow() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("nested-invalid-target.flow.json", NESTED_FLOW));
            addPathSegment(page, "Target", "output.response", "field", "");

            assertThat(List.of(
                    text(page, "#console-output").contains("MAPPING_PATH_SEGMENT_INVALID"),
                    pathBreadcrumb(page, "Target", "output.response")
            )).containsExactly(
                    true,
                    "Person › Name"
            );
        }
    }

    @Test
    void invalidDefaultJsonStaysLocalAndPreservesTheAcceptedDefault() {
        try (Page page = openPage()) {
            mappingDefault(page, "lowercase.text").click();
            mappingField(page, "Default value", "lowercase.text").fill("\"UNKNOWN\"");
            mappingField(page, "Default value", "lowercase.text").press("Tab");
            mappingField(page, "Default value", "lowercase.text").fill("{");
            mappingField(page, "Default value", "lowercase.text").press("Tab");

            assertThat(List.of(
                    text(page, ".mapping-field-error").contains("valid JSON"),
                    mappingPreview(page, "lowercase.text")
            )).containsExactly(
                    true,
                    "App input · Text · fallback \"UNKNOWN\" → Lowercase · Text"
            );
        }
    }

    @Test
    void clearedSourcePathIsAbsentFromTheSavedRunnableFlow() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("clear-source-path.flow.json", TURKISH_FLOW));
            addPathSegment(page, "Source", "lowercase.text", "field", "person");
            removePathSegment(page, "Source", "lowercase.text", 1);
            click(page, "Run");
            waitForState(page, "Run passed");
            final String saved = Files.readString(
                    saveFlow(page, "cleared-source-path.flow.json").path(),
                    StandardCharsets.UTF_8
            );

            assertThat(List.of(
                    pathBreadcrumb(page, "Source", "lowercase.text"),
                    saved.contains("sourcePath"),
                    text(page, "#compile-state")
            )).containsExactly("Whole value", false, "Flow saved");
        }
    }

    @Test
    void clearedTargetPathIsAbsentFromTheSavedRunnableFlow() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("clear-target-path.flow.json", TURKISH_FLOW));
            addPathSegment(page, "Target", "output.text", "field", "person");
            removePathSegment(page, "Target", "output.text", 1);
            click(page, "Run");
            waitForState(page, "Run passed");
            final String saved = Files.readString(
                    saveFlow(page, "cleared-target-path.flow.json").path(),
                    StandardCharsets.UTF_8
            );

            assertThat(List.of(
                    pathBreadcrumb(page, "Target", "output.text"),
                    saved.contains("targetPath"),
                    text(page, "#compile-state")
            )).containsExactly("Whole value", false, "Flow saved");
        }
    }

    @Test
    void arrayIndexPathCanBeAddedAndRemoved() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("nested-array-index.flow.json", NESTED_FLOW));
            addPathSegment(page, "Source", "lowercase.text", "index", "0");
            final String added = pathBreadcrumb(page, "Source", "lowercase.text");
            removePathSegment(page, "Source", "lowercase.text", 3);

            assertThat(List.of(
                    added,
                    pathBreadcrumb(page, "Source", "lowercase.text")
            )).containsExactly("Person › Name › [0]", "Person › Name");
        }
    }

    @Test
    void pathControlsEditTheExactImportedMappingRow() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("nested-row-editor.flow.json", MULTI_MAPPING_FLOW));
            addPathSegment(page, "Source", "output.response", 2, "field", "unit");

            assertThat(List.of(
                    pathBreadcrumb(page, "Source", "output.response"),
                    pathBreadcrumb(page, "Source", "output.response", 2)
            )).containsExactly("Whole value", "Person › Age › Unit");
        }
    }

    @Test
    void mismatchedSourcesRemainAvailableForExplicitConversion() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("conversion-sources.flow.json", CONVERSION_FLOW));

            assertThat(List.of(
                    dataOptions(page, "output.number"),
                    mappingAdvancedSource(page, "output.number").locator("option").allTextContents()
            )).containsExactly(
                    List.of("Unmapped", "Lowercase · Text · string"),
                    List.of("Lowercase · Text · string")
            );
        }
    }

    @Test
    void conversionEditorShowsExactlyTheCompilerOwnedConversions() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, flowFile("conversion-options.flow.json", CONVERSION_FLOW));

            assertThat(mappingConversion(page, "output.number").locator("option").allTextContents())
                    .containsExactly(
                            "None",
                            "string-to-number",
                            "number-to-string",
                            "string-to-boolean",
                            "boolean-to-string"
                    );
        }
    }

    @Test
    void nestedMappingFlowOpenAndSaveIsByteForByteStable() throws Exception {
        try (Page page = openPage()) {
            final Path opened = flowFile("nested-save.flow.json", NESTED_FLOW);
            openFlow(page, opened);
            final Path saved = saveFlow(page, "nested-saved.flow.json").path();

            assertThat(saved).hasSameBinaryContentAs(opened);
        }
    }

    @Test
    void repeatedSaveProducesIdenticalFilesWithoutChangingTheDraft() throws Exception {
        try (Page page = openPage()) {
            configure(page, "lowercase.languageTag", "tr");
            final Path first = saveFlow(page, "repeat-first.flow.json").path();
            final Path second = saveFlow(page, "repeat-second.flow.json").path();

            assertThat(new RepeatedSaveObservation(
                    Files.mismatch(first, second),
                    configControl(page, "lowercase.languageTag").inputValue(),
                    text(page, "#flow-summary")
            )).isEqualTo(new RepeatedSaveObservation(-1, "tr", "1 Step / 2 data / 1 route"));
        }
    }

    @Test
    void malformedOpenPreservesTheCurrentFlow() throws Exception {
        try (Page page = openPage()) {
            configure(page, "lowercase.languageTag", "tr");
            chooseFlow(page, flowFile("malformed.flow.json", "{"));
            waitForState(page, "Open failed");

            assertThat(openRejection(page, "FLOW_JSON_INVALID"))
                    .isEqualTo(new OpenRejectionObservation("Open failed", "tr", true, true));
        }
    }

    @Test
    void structurallyInvalidOpenPreservesTheCurrentFlow() throws Exception {
        try (Page page = openPage()) {
            configure(page, "lowercase.languageTag", "tr");
            chooseFlow(page, flowFile("array.flow.json", "[]"));
            waitForState(page, "Open failed");

            assertThat(openRejection(page, "FLOW_DOCUMENT_OBJECT_REQUIRED"))
                    .isEqualTo(new OpenRejectionObservation("Open failed", "tr", true, true));
        }
    }

    @Test
    void unsupportedOpenPreservesTheCurrentFlow() throws Exception {
        try (Page page = openPage()) {
            configure(page, "lowercase.languageTag", "tr");
            final String unknownStep = Files.readString(referenceFlow())
                    .replace("\"use\": \"text.lowercase\"", "\"use\": \"text.unknown\"");
            chooseFlow(page, flowFile("unsupported.flow.json", unknownStep));
            waitForState(page, "Open failed");

            assertThat(openRejection(page, "FLOW_STEP_UNKNOWN"))
                    .isEqualTo(new OpenRejectionObservation("Open failed", "tr", true, true));
        }
    }

    @Test
    void invalidUtf8OpenPreservesTheCurrentFlow() throws Exception {
        try (Page page = openPage()) {
            configure(page, "lowercase.languageTag", "tr");
            chooseFlow(page, flowFile("invalid-utf8.flow.json", new byte[]{'"', (byte) 0xc3, '(', '"'}));
            waitForState(page, "Open failed");

            assertThat(openRejection(page, "REQUEST_UTF8_INVALID"))
                    .isEqualTo(new OpenRejectionObservation("Open failed", "tr", true, true));
        }
    }

    @Test
    void loneHighSurrogateOpenPreservesTheCurrentFlow() throws Exception {
        try (Page page = openPage()) {
            configure(page, "lowercase.languageTag", "tr");
            chooseFlow(page, flowFile("high-surrogate.flow.json", "{\"id\":\"\\uD800\"}"));
            waitForState(page, "Open failed");

            assertThat(openRejection(page, "FLOW_JSON_INVALID"))
                    .isEqualTo(new OpenRejectionObservation("Open failed", "tr", true, true));
        }
    }

    @Test
    void loneLowSurrogateOpenPreservesTheCurrentFlow() throws Exception {
        try (Page page = openPage()) {
            configure(page, "lowercase.languageTag", "tr");
            chooseFlow(page, flowFile("low-surrogate.flow.json", "{\"id\":\"\\uDC00\"}"));
            waitForState(page, "Open failed");

            assertThat(openRejection(page, "FLOW_JSON_INVALID"))
                    .isEqualTo(new OpenRejectionObservation("Open failed", "tr", true, true));
        }
    }

    @Test
    void duplicateJsonFieldOpenPreservesTheCurrentFlow() throws Exception {
        try (Page page = openPage()) {
            configure(page, "lowercase.languageTag", "tr");
            chooseFlow(page, flowFile("duplicate.flow.json", "{\"id\":\"first\",\"id\":\"second\"}"));
            waitForState(page, "Open failed");

            assertThat(openRejection(page, "FLOW_JSON_INVALID"))
                    .isEqualTo(new OpenRejectionObservation("Open failed", "tr", true, true));
        }
    }

    @Test
    void oversizedOpenPreservesTheCurrentFlow() throws Exception {
        try (Page page = openPage()) {
            configure(page, "lowercase.languageTag", "tr");
            chooseFlow(page, flowFile("oversized.flow.json", new byte[1_048_577]));
            waitForState(page, "Open failed");

            assertThat(openRejection(page, "REQUEST_TOO_LARGE"))
                    .isEqualTo(new OpenRejectionObservation("Open failed", "tr", true, true));
        }
    }

    @Test
    void cancellingOpenLeavesTheCurrentFlowUntouched() {
        try (Page page = openPage()) {
            configure(page, "lowercase.languageTag", "tr");
            final FileChooser chooser = page.waitForFileChooser(() -> click(page, "Open flow"));
            chooser.setFiles(new Path[0]);

            assertThat(List.of(
                    text(page, "#compile-state"),
                    configControl(page, "lowercase.languageTag").inputValue(),
                    text(page, "#flow-summary")
            )).containsExactly("Not validated", "tr", "1 Step / 2 data / 1 route");
        }
    }

    @Test
    void validFlowContentOpensRegardlessOfFilenameExtension() throws Exception {
        try (Page page = openPage()) {
            final Path file = flowFile("lowercase.txt", Files.readString(referenceFlow()));
            openFlow(page, file);

            assertThat(List.of(
                    text(page, "#compile-state"),
                    configControl(page, "lowercase.languageTag").inputValue(),
                    text(page, "#flow-summary")
            )).containsExactly("Flow opened", "und", "1 Step / 2 data / 1 route");
        }
    }

    @Test
    void invalidDraftDoesNotDownload() {
        try (Page page = openPage()) {
            final List<Download> downloads = new ArrayList<>();
            page.onDownload(downloads::add);
            data(page, "lowercase.text", "");
            waitForDraft(page, "1 issue");
            click(page, "Save flow");
            waitForState(page, "Save failed");

            assertThat(new SaveRejectionObservation(
                    downloads.size(),
                    text(page, "#console-output").contains("FLOW_REQUIRED_INPUT_UNMAPPED"),
                    selectedData(page, "lowercase.text")
            )).isEqualTo(new SaveRejectionObservation(0, true, ""));
        }
    }

    @Test
    void openLeavesSampleInputUnchanged() throws Exception {
        try (Page page = openPage()) {
            sample(page, "{\"text\":\"session-only\"}");
            openFlow(page, referenceFlow());

            assertThat(sampleValue(page)).isEqualTo("{\"text\":\"session-only\"}");
        }
    }

    @Test
    void reopeningTheSameFileTwiceDoesNotDuplicateFlowContent() throws Exception {
        try (Page page = openPage()) {
            openFlow(page, referenceFlow());
            openFlow(page, referenceFlow());

            assertThat(new RepeatedOpenObservation(
                    page.locator("article.step-node").count(),
                    text(page, "#flow-summary"),
                    selectedData(page, "lowercase.text"),
                    selectedRoute(page, "lowercase.ok")
            )).isEqualTo(new RepeatedOpenObservation(
                    1, "1 Step / 2 data / 1 route", "input.text", "end"
            ));
        }
    }

    @Test
    void persistenceControlsPerformSaveAndOpenAtSupportedWidths() throws Exception {
        try (Page page = openPage()) {
            final Path saved = saveFlow(page, "mobile.flow.json").path();
            configure(page, "lowercase.languageTag", "tr");
            openFlow(page, saved);

            assertThat(new MobilePersistenceObservation(
                    page.viewportSize().width,
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Open flow")).isVisible(),
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Save flow")).isVisible(),
                    text(page, "#compile-state"),
                    configControl(page, "lowercase.languageTag").inputValue()
            )).isEqualTo(new MobilePersistenceObservation(
                    VIEWPORT_WIDTH, true, true, "Flow opened", "und"
            ));
        }
    }

    @Test
    void outcomeRouteCanBeDisconnectedExplicitly() {
        try (Page page = openPage()) {
            route(page, "lowercase.ok", "");

            assertThat(new RouteObservation(
                    text(page, "#flow-summary"),
                    selectedRoute(page, "lowercase.ok"),
                    text(page, "#compile-state"),
                    text(page, "#console-output")
            )).isEqualTo(new RouteObservation(
                    "1 Step / 2 data / 0 routes",
                    "",
                    "Not validated",
                    routeEvent("outcome-disconnected", "lowercase.ok", "end", "unconnected")
            ));
        }
    }

    @Test
    void disconnectedOutcomeShowsCompilerOwnedDiagnostic() {
        try (Page page = openPage()) {
            route(page, "lowercase.ok", "");
            click(page, "Validate");
            waitForState(page, "Invalid");
            final String diagnostics = text(page, "#console-output");

            assertThat(new OutcomeDiagnosticObservation(
                    text(page, "#compile-state"),
                    diagnostics.contains("FLOW_OUTCOME_UNHANDLED"),
                    diagnostics.contains("steps.lowercase.on.ok")
            )).isEqualTo(new OutcomeDiagnosticObservation("Invalid", true, true));
        }
    }

    @Test
    void outcomeCanReconnectToEndAndRun() {
        try (Page page = openPage()) {
            route(page, "lowercase.ok", "");
            route(page, "lowercase.ok", "end");
            final String routeEvent = text(page, "#console-output");
            click(page, "Run");
            waitForState(page, "Run passed");

            assertThat(new ReconnectObservation(
                    text(page, "#flow-summary"),
                    selectedRoute(page, "lowercase.ok"),
                    routeEvent,
                    text(page, "#compile-state"),
                    text(page, "#console-output").contains("\"text\": \"hello railix\"")
            )).isEqualTo(new ReconnectObservation(
                    "1 Step / 2 data / 1 route",
                    "end",
                    routeEvent("outcome-connected", "lowercase.ok", "unconnected", "end"),
                    "Run passed",
                    true
            ));
        }
    }

    @Test
    void outcomeCanTargetAnExistingStepWithoutInventingItsRoute() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            route(page, "lowercase.ok", "lowercase2");

            assertThat(new TargetObservation(
                    text(page, "#flow-summary"),
                    selectedRoute(page, "lowercase.ok"),
                    selectedRoute(page, "lowercase2.ok"),
                    text(page, "#console-output")
            )).isEqualTo(new TargetObservation(
                    "2 Steps / 2 data / 1 route",
                    "lowercase2",
                    "",
                    routeEvent("outcome-rerouted", "lowercase.ok", "end", "lowercase2")
            ));
        }
    }

    @Test
    void outcomeRerouteKeepsEveryStepVisible() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            route(page, "lowercase.ok", "lowercase2");

            assertThat(new NodeVisibilityObservation(
                    nodeOpacity(page, "lowercase"),
                    nodeOpacity(page, "lowercase2")
            )).isEqualTo(new NodeVisibilityObservation("1", "1"));
        }
    }

    @Test
    void explicitlyRoutedStepBecomesReachableToTheCompiler() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            route(page, "lowercase.ok", "lowercase2");
            route(page, "lowercase2.ok", "end");
            click(page, "Validate");
            waitForState(page, "Invalid");
            final String diagnostics = text(page, "#console-output");

            assertThat(new ReachabilityObservation(
                    text(page, "#compile-state"),
                    diagnostics.contains("FLOW_REQUIRED_INPUT_UNMAPPED"),
                    diagnostics.contains("steps.lowercase2.inputs.text"),
                    diagnostics.contains("FLOW_STEP_UNREACHABLE"),
                    diagnostics.contains("FLOW_OUTCOME_UNHANDLED")
            )).isEqualTo(new ReachabilityObservation("Invalid", true, true, false, false));
        }
    }

    @Test
    void ordinaryOutcomeCannotCreateAControlCycle() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            route(page, "lowercase.ok", "lowercase2");

            assertThat(routeTargetAvailable(page, "lowercase2.ok", "lowercase")).isFalse();
        }
    }

    @Test
    void ordinaryOutcomeCannotCreateImplicitFanIn() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            click(page, "Add text.nonblank");
            route(page, "lowercase.ok", "nonblank");

            assertThat(routeTargetAvailable(page, "lowercase2.ok", "nonblank")).isFalse();
        }
    }

    @Test
    void mutuallyExclusiveOutcomesMayShareTheSameSuccessor() {
        try (Page page = openPage()) {
            click(page, "Add file.write");
            click(page, "Add file.delete");
            route(page, "write.conflict", "delete");

            assertThat(routeTargetAvailable(page, "write.rejected", "delete")).isTrue();

            route(page, "write.rejected", "delete");
            assertThat(List.of(
                    selectedRoute(page, "write.conflict"),
                    selectedRoute(page, "write.rejected")
            )).containsExactly("delete", "delete");
        }
    }

    @Test
    void outcomeRoutesRemainAvailableAtSupportedWidths() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            route(page, "lowercase.ok", "lowercase2");
            route(page, "lowercase2.ok", "end");

            assertThat(new MobileRouteObservation(
                    page.viewportSize().width,
                    routeInsideCanvasPanel(page, "lowercase.ok"),
                    routeInsideCanvasPanel(page, "lowercase2.ok"),
                    selectedRoute(page, "lowercase.ok"),
                    selectedRoute(page, "lowercase2.ok"),
                    text(page, "#flow-summary")
            )).isEqualTo(new MobileRouteObservation(
                    VIEWPORT_WIDTH,
                    true,
                    true,
                    "lowercase2",
                    "end",
                    "2 Steps / 2 data / 2 routes"
            ));
        }
    }

    @Test
    void outcomeRoutesStayAboveDiagnosticsAtSupportedHeights() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            route(page, "lowercase.ok", "lowercase2");
            route(page, "lowercase2.ok", "end");

            assertThat(new DesktopRouteObservation(
                    page.viewportSize().height,
                    routeInsideCanvasPanel(page, "lowercase.ok"),
                    routeInsideCanvasPanel(page, "lowercase2.ok")
            )).isEqualTo(new DesktopRouteObservation(viewportHeight(), true, true));
        }
    }

    @Test
    void removingUnconnectedStepRestoresTheOriginalFlow() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            click(page, "Remove Step lowercase2");
            final String removal = text(page, "#console-output");
            click(page, "Validate");
            waitForState(page, "Valid");

            assertThat(new RemovalObservation(
                    text(page, "#flow-summary"),
                    removal.contains("\"connectionsRemoved\": 0"),
                    removal.contains("\"transitionsRemoved\": 0"),
                    removal.contains("\"entryCleared\": false"),
                    text(page, "#compile-state")
            )).isEqualTo(new RemovalObservation("1 Step / 2 data / 1 route", true, true, true, "Valid"));
        }
    }

    @Test
    void removingConnectedStepDeletesEveryAffectedRoute() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            route(page, "lowercase.ok", "lowercase2");
            route(page, "lowercase2.ok", "end");
            click(page, "Remove Step lowercase2");
            final String removal = text(page, "#console-output");
            click(page, "Validate");
            waitForState(page, "Invalid");
            final String diagnostics = text(page, "#console-output");

            assertThat(new ConnectedRemovalObservation(
                    text(page, "#flow-summary"),
                    removal.contains("\"transitionsRemoved\": 2"),
                    selectedRoute(page, "lowercase.ok"),
                    diagnostics.contains("FLOW_OUTCOME_UNHANDLED"),
                    diagnostics.contains("steps.lowercase.on.ok")
            )).isEqualTo(new ConnectedRemovalObservation(
                    "1 Step / 2 data / 0 routes",
                    true,
                    "",
                    true,
                    true
            ));
        }
    }

    @Test
    void removingEntryReportsEveryDeletedBinding() {
        try (Page page = openPage()) {
            click(page, "Remove Step lowercase");
            final String removal = text(page, "#console-output");

            assertThat(new EntryRemovalObservation(
                    text(page, "#flow-summary"),
                    text(page, "#inspector-step-id"),
                    removal.contains("\"connectionsRemoved\": 2"),
                    removal.contains("\"transitionsRemoved\": 1"),
                    removal.contains("\"entryCleared\": true")
            )).isEqualTo(new EntryRemovalObservation(
                    "0 Steps / 0 data / 0 routes",
                    "Application",
                    true,
                    true,
                    true
            ));
        }
    }

    @Test
    void repeatedAddsUseDeterministicUniqueIds() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            click(page, "Add text.lowercase");

            assertThat(new RepeatedAddObservation(
                    text(page, "#flow-summary"),
                    page.getByRole(AriaRole.ARTICLE, new Page.GetByRoleOptions().setName("Step lowercase2")).count(),
                    page.getByRole(AriaRole.ARTICLE, new Page.GetByRoleOptions().setName("Step lowercase3")).count()
            )).isEqualTo(new RepeatedAddObservation("3 Steps / 2 data / 1 route", 1, 1));
        }
    }

    @Test
    void addAndRemoveRemainAvailableAtSupportedWidths() {
        try (Page page = openPage()) {
            click(page, "Add text.lowercase");
            click(page, "Remove Step lowercase2");

            assertThat(new MobileObservation(
                    page.viewportSize().width,
                    text(page, "#flow-summary"),
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add text.lowercase")).isVisible()
            )).isEqualTo(new MobileObservation(VIEWPORT_WIDTH, "1 Step / 2 data / 1 route", true));
        }
    }

    private static Page openPage() {
        final Page page = newViewportPage();
        page.navigate(creatorUrl);
        return page;
    }

    private static Page openPageWithNameSeed() {
        final Page page = newViewportPage();
        page.addInitScript("Date.now = () => 0");
        page.navigate(creatorUrl);
        return page;
    }

    private static Page openPage(final CreatorServer server) {
        final Page page = newViewportPage();
        page.navigate(server.baseUri().toString());
        return page;
    }

    private static Page openPageWithResponseHold() {
        final Page page = newViewportPage();
        page.addInitScript("""
                window.railixResponseHold = { path: "", waiting: false, release: () => {} };
                const railixFetch = window.fetch.bind(window);
                window.fetch = async (...arguments_) => {
                  const response = await railixFetch(...arguments_);
                  const path = new URL(arguments_[0], window.location.href).pathname;
                  if (window.railixResponseHold.path === path) {
                    window.railixResponseHold.path = "";
                    window.railixResponseHold.waiting = true;
                    await new Promise((resolve) => {
                      window.railixResponseHold.release = () => {
                        window.railixResponseHold.waiting = false;
                        resolve();
                      };
                    });
                  }
                  return response;
                };
                """);
        page.navigate(creatorUrl);
        return page;
    }

    private static Page openPageWithDraftAbortProbe() {
        final Page page = newViewportPage();
        page.addInitScript("""
                window.railixDraftAbortProbe = { aborted: 0, armed: false, waiting: false };
                const railixFetch = window.fetch.bind(window);
                window.fetch = (...arguments_) => {
                  const path = new URL(arguments_[0], window.location.href).pathname;
                  if (path === "/api/compile" && window.railixDraftAbortProbe.armed) {
                    window.railixDraftAbortProbe.armed = false;
                    window.railixDraftAbortProbe.waiting = true;
                    return new Promise((resolve, reject) => {
                      arguments_[1]?.signal?.addEventListener("abort", () => {
                        window.railixDraftAbortProbe.aborted++;
                        window.railixDraftAbortProbe.waiting = false;
                        reject(new DOMException("Superseded", "AbortError"));
                      }, { once: true });
                    });
                  }
                  return railixFetch(...arguments_);
                };
                """);
        page.navigate(creatorUrl);
        return page;
    }

    private static Page newViewportPage() {
        return browser.newPage(new Browser.NewPageOptions().setViewportSize(
                VIEWPORT_WIDTH,
                viewportHeight()
        ));
    }

    private static int viewportHeight() {
        return switch (VIEWPORT_WIDTH) {
            case 1280 -> 720;
            case 320 -> 700;
            default -> throw new AssertionError(
                    "Browser viewport width must be 1280 or 320: " + VIEWPORT_WIDTH
            );
        };
    }

    private static void assertNoChildProcesses() {
        final List<Long> children = ProcessHandle.current().descendants()
                .filter(ProcessHandle::isAlive)
                .map(ProcessHandle::pid)
                .toList();
        if (!children.isEmpty()) {
            throw new AssertionError("Browser suite leaked child processes: " + children);
        }
    }

    private static void holdResponse(final Page page, final String path) {
        page.evaluate("path => window.railixResponseHold.path = path", path);
    }

    private static void waitForHeldResponse(final Page page) {
        page.waitForFunction("window.railixResponseHold.waiting");
    }

    private static void releaseHeldResponse(final Page page) {
        page.evaluate("window.railixResponseHold.release()");
        page.waitForTimeout(100);
    }

    private SavedDownload saveFlow(final Page page, final String localName) {
        final Download download = page.waitForDownload(() -> click(page, "Save flow"));
        final Path path = temporaryDirectory.resolve(localName);
        download.saveAs(path);
        return new SavedDownload(download.suggestedFilename(), path);
    }

    private static void openFlow(final Page page, final Path file) {
        chooseFlow(page, file);
        waitForState(page, "Flow opened");
    }

    private static void chooseFlow(final Page page, final Path file) {
        final FileChooser chooser = page.waitForFileChooser(() -> click(page, "Open flow"));
        chooser.setFiles(file);
    }

    private Path flowFile(final String name, final String source) throws IOException {
        return Files.writeString(temporaryDirectory.resolve(name), source, StandardCharsets.UTF_8);
    }

    private Path flowFile(final String name, final byte[] source) throws IOException {
        return Files.write(temporaryDirectory.resolve(name), source);
    }

    private List<String> temporaryFiles() throws IOException {
        try (var files = Files.list(temporaryDirectory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.contains(".railix-"))
                    .sorted()
                    .toList();
        }
    }

    private static Path referenceFlow() {
        return REPOSITORY.resolve("examples/lowercase-app/railix.flow.json");
    }

    private static String httpFlow() {
        return withTriggers(
                TURKISH_FLOW,
                "{\"config\":{\"path\":\"/custom\",\"port\":18081},"
                        + "\"id\":\"custom\",\"type\":\"http\"},"
                        + "{\"config\":{\"flow\":true,\"port\":18081},"
                        + "\"id\":\"flow-events\",\"type\":\"http\"},"
                        + "{\"config\":{\"port\":18081,\"step\":\"lowercase\"},"
                        + "\"id\":\"step-events\",\"type\":\"http\"}"
        );
    }

    private static String withTriggers(final String source, final String triggers) {
        final int start = source.lastIndexOf("\"triggers\":");
        if (start < 0) {
            throw new AssertionError("Test flow has no trigger field.");
        }
        return source.substring(0, start) + "\"triggers\":[" + triggers + "]}\n";
    }

    private static String fileReadFlow(final String format) {
        return """
                {"connections":[{"from":"input.path","to":"reader.path"},\
                {"from":"reader.value","to":"output.value"}],"entry":"reader",\
                "id":"file-read-app","inputs":{"path":"string"},"outputs":{"value":"any"},\
                "steps":[{"config":{"format":"%s"},"id":"reader",\
                "on":{"missing":"end","read":"end","rejected":"end"},"use":"file.read"}],\
                "triggers":[]}
                """.formatted(format);
    }

    private static String fileWriteFlow(final String overwrite) {
        return """
                {"connections":[{"from":"input.path","to":"writer.path"},\
                {"from":"input.value","to":"writer.value"}],"entry":"writer",\
                "id":"file-write-app","inputs":{"path":"string","value":"any"},"outputs":{},\
                "steps":[{"config":{"overwrite":%s},"id":"writer",\
                "on":{"conflict":"end","rejected":"end","written":"end"},"use":"file.write"}],\
                "triggers":[]}
                """.formatted(overwrite);
    }

    private static String filePersistenceFlow() {
        return """
                {"connections":[{"from":"input.path","to":"writer.path"},\
                {"from":"input.value","to":"writer.value"},\
                {"from":"input.path","to":"reader.path"},\
                {"from":"reader.value","to":"output.value"},\
                {"from":"input.path","to":"deleter.path"}],"entry":"writer",\
                "id":"file-persistence-app","inputs":{"path":"string","value":"any"},\
                "outputs":{"value":"any"},"steps":[\
                {"config":{"overwrite":true},"id":"writer",\
                "on":{"conflict":"reader","rejected":"reader","written":"reader"},"use":"file.write"},\
                {"config":{"format":"json"},"id":"reader",\
                "on":{"missing":"deleter","read":"deleter","rejected":"deleter"},"use":"file.read"},\
                {"config":{},"id":"deleter",\
                "on":{"deleted":"end","missing":"end","rejected":"end"},"use":"file.delete"}],\
                "triggers":[]}
                """;
    }

    private static String httpGetFlow(final long timeoutMillis) {
        return """
                {"connections":[{"from":"input.url","to":"request.url"},\
                {"from":"input.headers","to":"request.headers"},\
                {"from":"request.status","to":"output.status"},\
                {"from":"request.body","to":"output.body"}],"entry":"request",\
                "id":"http-get-app","inputs":{"url":"string","headers":"object"},\
                "outputs":{"status":"number","body":"any"},"steps":[\
                {"config":{"format":"json","timeoutMillis":%d},"id":"request",\
                "on":{"success":"end","redirect":"end","client-error":"end",\
                "server-error":"end","other":"end","rejected":"end"},"use":"http.get"}],\
                "triggers":[]}
                """.formatted(timeoutMillis);
    }

    private static String scheduledFlow() {
        return "{\"connections\":[],\"entry\":\"source\",\"id\":\"scheduled-app\","
                + "\"inputs\":{},\"outputs\":{},\"steps\":[{\"config\":{},\"id\":\"source\","
                + "\"on\":{\"ok\":\"end\"},\"use\":\"example.source\"}],\"triggers\":[]}\n";
    }

    private static OpenRejectionObservation openRejection(final Page page, final String code) {
        return new OpenRejectionObservation(
                text(page, "#compile-state"),
                configControl(page, "lowercase.languageTag").inputValue(),
                text(page, "#console-output").contains(code),
                text(page, "#flow-summary").equals("1 Step / 2 data / 1 route")
        );
    }

    private static void click(final Page page, final String name) {
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name).setExact(true)).click();
    }

    private static Locator label(final Page page, final String name) {
        final Locator control = page.getByLabel(name, new Page.GetByLabelOptions().setExact(true));
        if (control.count() == 1 && control.isHidden()) {
            final String type = name.startsWith("Socket") || name.equals("Enable socket ingress")
                    ? "Socket"
                    : name.startsWith("Schedule ") ? "Scheduled" : "";
            if (!type.isEmpty()) {
                page.locator("article.trigger-node")
                        .filter(new Locator.FilterOptions().setHasText(type))
                        .first()
                        .click();
            }
        }
        return control;
    }

    private static void addTrigger(final Page page, final String type) {
        selectApplicationForInspector(page);
        click(page, "Add trigger");
        click(page, "Add " + type + " trigger");
    }

    private static void selectTriggerForInspector(final Page page, final String triggerId) {
        page.getByRole(
                AriaRole.ARTICLE,
                new Page.GetByRoleOptions().setName("Trigger " + triggerId).setExact(true)
        ).click();
    }

    private static void route(final Page page, final String outcome, final String target) {
        page.getByRole(
                AriaRole.COMBOBOX,
                new Page.GetByRoleOptions().setName("Route " + outcome).setExact(true)
        ).selectOption(target);
    }

    private static String selectedRoute(final Page page, final String outcome) {
        return page.getByRole(
                AriaRole.COMBOBOX,
                new Page.GetByRoleOptions().setName("Route " + outcome).setExact(true)
        ).inputValue();
    }

    private static boolean routeTargetAvailable(
            final Page page,
            final String outcome,
            final String target
    ) {
        return page.getByRole(
                        AriaRole.COMBOBOX,
                        new Page.GetByRoleOptions().setName("Route " + outcome).setExact(true)
                )
                .locator("option[value='" + target + "']")
                .count() == 1;
    }

    private static void data(final Page page, final String target, final String source) {
        dataControl(page, target).selectOption(source);
    }

    private static com.microsoft.playwright.Locator mappingField(
            final Page page,
            final String field,
            final String target
    ) {
        return mappingField(page, field, target, 1);
    }

    private static com.microsoft.playwright.Locator mappingField(
            final Page page,
            final String field,
            final String target,
            final int ordinal
    ) {
        openMappingFields(page, target, ordinal);
        return page.getByRole(
                AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName(
                        mappingLabel(field, target, ordinal)
                ).setExact(true)
        );
    }

    private static com.microsoft.playwright.Locator mappingDefault(
            final Page page,
            final String target
    ) {
        openMappingFields(page, target, 1);
        return page.getByRole(
                AriaRole.CHECKBOX,
                new Page.GetByRoleOptions().setName("Use default " + target).setExact(true)
        );
    }

    private static com.microsoft.playwright.Locator mappingConversion(
            final Page page,
            final String target
    ) {
        openMappingFields(page, target, 1);
        return page.getByRole(
                AriaRole.COMBOBOX,
                new Page.GetByRoleOptions().setName("Convert " + target).setExact(true)
        );
    }

    private static Locator mappingAdvancedSource(
            final Page page,
            final String target
    ) {
        openMappingFields(page, target, 1);
        return page.getByRole(
                AriaRole.COMBOBOX,
                new Page.GetByRoleOptions().setName("Choose advanced source " + target).setExact(true)
        );
    }

    private static void replacePath(
            final Page page,
            final String direction,
            final String target,
            final List<String> segments
    ) {
        while (pathRemoveButton(page, direction, target, 1, 1).count() == 1) {
            pathRemoveButton(page, direction, target, 1, 1).click();
        }
        for (final String segment : segments) {
            addPathSegment(page, direction, target, "field", segment);
        }
    }

    private static void addPathSegment(
            final Page page,
            final String direction,
            final String target,
            final String kind,
            final String value
    ) {
        addPathSegment(page, direction, target, 1, kind, value);
    }

    private static void addPathSegment(
            final Page page,
            final String direction,
            final String target,
            final int ordinal,
            final String kind,
            final String value
    ) {
        openMappingFields(page, target, ordinal);
        page.getByRole(
                AriaRole.COMBOBOX,
                new Page.GetByRoleOptions().setName(
                        mappingLabel(direction + " path segment kind", target, ordinal)
                ).setExact(true)
        ).selectOption(kind);
        page.getByRole(
                AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName(
                        mappingLabel(direction + " path segment", target, ordinal)
                ).setExact(true)
        ).fill(value);
        click(page, mappingLabel("Add " + direction + " path segment", target, ordinal));
    }

    private static void removePathSegment(
            final Page page,
            final String direction,
            final String target,
            final int index
    ) {
        pathRemoveButton(page, direction, target, 1, index).click();
    }

    private static Locator pathRemoveButton(
            final Page page,
            final String direction,
            final String target,
            final int ordinal,
            final int index
    ) {
        openMappingFields(page, target, ordinal);
        return page.getByRole(
                AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(
                        mappingLabel(
                                "Remove " + direction + " path segment " + index,
                                target,
                                ordinal
                        )
                ).setExact(true)
        );
    }

    private static String pathBreadcrumb(
            final Page page,
            final String direction,
            final String target
    ) {
        return pathBreadcrumb(page, direction, target, 1);
    }

    private static String pathBreadcrumb(
            final Page page,
            final String direction,
            final String target,
            final int ordinal
    ) {
        openMappingFields(page, target, ordinal);
        return page.getByLabel(
                mappingLabel(direction + " path", target, ordinal),
                new Page.GetByLabelOptions().setExact(true)
        ).textContent();
    }

    private static String mappingPreview(final Page page, final String target) {
        dataControl(page, target);
        return page.getByLabel("Preview " + target, new Page.GetByLabelOptions().setExact(true)).textContent();
    }

    private static void openMappingFields(
            final Page page,
            final String target,
            final int ordinal
    ) {
        dataControl(page, target);
        final Locator details = page.getByRole(
                        AriaRole.COMBOBOX,
                        new Page.GetByRoleOptions().setName(
                                mappingLabel("Map", target, ordinal)
                        ).setExact(true)
                )
                .locator("xpath=..")
                .locator("details.advanced-mapping");
        if (details.getAttribute("open") == null) {
            details.locator("summary").click();
        }
    }

    private static String mappingLabel(
            final String field,
            final String target,
            final int ordinal
    ) {
        return field + " " + target + (ordinal == 1 ? "" : " " + ordinal);
    }

    private static void eventFormat(final Page page, final String format) {
        click(page, format + " event format");
    }

    private static NodePresentation nodePresentation(final Page page, final String stepId) {
        final var node = page.getByRole(
                AriaRole.ARTICLE,
                new Page.GetByRoleOptions().setName("Step " + stepId).setExact(true)
        );
        final String classes = node.getAttribute("class");
        String kind = "";
        for (final String name : classes.split(" ")) {
            if (name.startsWith("kind-")) {
                kind = name;
                break;
            }
        }
        return new NodePresentation(
                classes.split(" ").length > 0 && List.of(classes.split(" ")).contains("lightweight"),
                kind,
                node.locator(".step-mark").first().textContent()
        );
    }

    private static com.microsoft.playwright.Locator configControl(final Page page, final String name) {
        selectStepForInspector(page, name.substring(0, name.indexOf('.')));
        return page.getByRole(
                AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Configure " + name).setExact(true)
        );
    }

    private static void configure(final Page page, final String name, final String value) {
        configControl(page, name).fill(value);
        configControl(page, name).press("Tab");
        waitForDraft(page, "Draft valid");
    }

    private static com.microsoft.playwright.Locator flowPortName(
            final Page page,
            final String direction,
            final String name
    ) {
        selectApplicationForInspector(page);
        return page.getByRole(
                AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName(
                        "Rename flow " + direction + " " + name
                ).setExact(true)
        );
    }

    private static com.microsoft.playwright.Locator flowPortShape(
            final Page page,
            final String direction,
            final String name
    ) {
        selectApplicationForInspector(page);
        return page.getByRole(
                AriaRole.COMBOBOX,
                new Page.GetByRoleOptions().setName(
                        "Shape flow " + direction + " " + name
                ).setExact(true)
        );
    }

    private static void sample(final Page page, final String value) {
        page.locator("#sample-input").fill(value);
    }

    private static String sampleValue(final Page page) {
        return page.locator("#sample-input").inputValue();
    }

    private static String selectedData(final Page page, final String target) {
        return dataControl(page, target).inputValue();
    }

    private static List<String> dataOptions(final Page page, final String target) {
        return dataControl(page, target).locator("option").allTextContents();
    }

    private static boolean dataVisible(final Page page, final String target) {
        return dataControl(page, target).isVisible();
    }

    private static com.microsoft.playwright.Locator dataControl(final Page page, final String target) {
        final String owner = target.substring(0, target.indexOf('.'));
        if (!owner.equals("output") && !owner.equals("input")) {
            selectStepForInspector(page, owner);
        } else if (page.locator("#data-mappings").isHidden()) {
            page.locator("article.step-node").first().click();
        }
        return page.getByRole(
                AriaRole.COMBOBOX,
                new Page.GetByRoleOptions().setName("Map " + target).setExact(true)
        );
    }

    private static void selectApplicationForInspector(final Page page) {
        if (page.locator("#application-settings").isHidden()) {
            page.locator("#application-node").click();
        }
    }

    private static void selectStepForInspector(final Page page, final String stepId) {
        final Locator node = page.getByRole(
                AriaRole.ARTICLE,
                new Page.GetByRoleOptions().setName("Step " + stepId).setExact(true)
        );
        if (!node.getAttribute("class").contains("selected")) {
            node.click();
        }
    }

    private static boolean dataInsideInspector(final Page page, final String target) {
        final BoundingBox mapping = dataControl(page, target).boundingBox();
        return insideInspector(page, mapping);
    }

    private static boolean insideInspector(final Page page, final String selector) {
        return insideInspector(page, page.locator(selector).boundingBox());
    }

    private static boolean insideInspector(final Page page, final BoundingBox element) {
        final BoundingBox inspector = page.locator(".inspector").boundingBox();
        return element != null
                && inspector != null
                && element.x >= inspector.x
                && element.x + element.width <= inspector.x + inspector.width + 0.5
                && element.y >= inspector.y
                && element.y + element.height <= inspector.y + inspector.height + 0.5;
    }

    private static int wireCount(final Page page, final String target) {
        return page.locator(".wire[data-to='" + target + "']").count();
    }

    private static String wireSource(final Page page, final String target) {
        return page.locator(".wire[data-to='" + target + "']").getAttribute("data-from");
    }

    private static boolean routeInsideCanvasPanel(final Page page, final String outcome) {
        final BoundingBox route = page.getByRole(
                AriaRole.COMBOBOX,
                new Page.GetByRoleOptions().setName("Route " + outcome).setExact(true)
        ).boundingBox();
        final BoundingBox canvas = page.locator(".canvas-panel").boundingBox();
        return route != null
                && canvas != null
                && route.y >= canvas.y
                && route.y + route.height <= canvas.y + canvas.height + 0.5;
    }

    private static String nodeOpacity(final Page page, final String stepId) {
        return page.getByRole(
                AriaRole.ARTICLE,
                new Page.GetByRoleOptions().setName("Step " + stepId).setExact(true)
        ).evaluate("element => getComputedStyle(element).opacity").toString();
    }

    private static String text(final Page page, final String selector) {
        return page.locator(selector).textContent();
    }

    private static void waitForState(final Page page, final String state) {
        page.waitForFunction(
                "state => document.querySelector('#compile-state').textContent === state",
                state
        );
    }

    private static void waitForDraft(final Page page, final String state) {
        page.waitForFunction(
                "state => document.querySelector('#draft-state')?.textContent === state",
                state
        );
    }

    private static String routeEvent(
            final String action,
            final String outcome,
            final String previousTarget,
            final String target
    ) {
        return """
                {
                  "status": "authoring",
                  "action": "%s",
                  "outcome": "%s",
                  "previousTarget": "%s",
                  "target": "%s"
                }""".formatted(action, outcome, previousTarget, target);
    }

    private static String dataEvent(
            final String action,
            final String target,
            final String previousSource,
            final String source
    ) {
        return """
                {
                  "status": "authoring",
                  "action": "%s",
                  "target": "%s",
                  "previousSource": "%s",
                  "source": "%s"
                }""".formatted(action, target, previousSource, source);
    }

    private static int availablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            return socket.getLocalPort();
        }
    }

    private static StepCatalog ordinaryStepCatalog() {
        return StepCatalog.of(prefixStep());
    }

    private static StepCatalog typeFilteringCatalog() {
        return StepCatalog.of(
                prefixStep(),
                StepDefinition.named("example.number.accept", "1.0.0")
                        .input("number", ValueShape.NUMBER)
                        .outcome("ok")
                        .run(input -> StepResult.outcome("ok"))
        );
    }

    private static StepDefinition prefixStep() {
        return StepDefinition.named("example.text.prefix", "1.0.0")
                .config("prefix", ValueShape.string(), RailixValue.string("Hello, "))
                .input("text", ValueShape.string())
                .output("text", ValueShape.string())
                .outcome("ok")
                .run(input -> StepResult.outcome("ok").output("text", RailixValue.string(
                        input.configString("prefix") + input.string("text")
                )));
    }

    private static CreatorServer scheduledCreator() throws IOException {
        return CreatorServer.start(0, StepCatalog.of(
                StepDefinition.named("example.source", "1.0.0")
                        .outcome("ok")
                        .run(input -> StepResult.outcome("ok"))
        ));
    }

    private record RunObservation(String state, boolean outputPresent) {
    }

    private record NodePresentation(boolean lightweight, String kind, String mark) {
    }

    private record AddObservation(
            String summary,
            int nodes,
            boolean secondStepPresent,
            boolean noMappingsInvented,
            boolean noOutcomesInvented
    ) {
    }

    private record DiagnosticObservation(
            String state,
            boolean unhandledOutcome,
            boolean unreachableStep,
            boolean exactPath
    ) {
    }

    private record DataEditObservation(String summary, String source, String state, String event, int wires) {
    }

    private record DataDiagnosticObservation(String state, boolean diagnostic, boolean exactPath) {
    }

    private record DataReconnectObservation(
            String summary,
            String source,
            String event,
            String state,
            boolean outputPresent
    ) {
    }

    private record PipelineDataObservation(
            String summary,
            String stepSource,
            String outputSource,
            String outputWireSource,
            int wires,
            String event,
            String state,
            boolean outputPresent
    ) {
    }

    private record DataRemovalObservation(
            String summary,
            String stepSource,
            String outputSource,
            boolean connectionsReported,
            int wires
    ) {
    }

    private record ResponsiveDataObservation(
            int width,
            boolean visible,
            boolean insideInspector,
            String source,
            String summary
    ) {
    }

    private record DesktopDataObservation(
            int height,
            boolean stepInputVisible,
            boolean stepInputInsideInspector,
            boolean flowOutputVisible,
            boolean flowOutputInsideInspector
    ) {
    }

    private record CompatibleSourceObservation(List<String> stepInput, List<String> flowOutput) {
    }

    private record DraftObservation(String state, int diagnostics, String console) {
    }

    private record LiveDiagnosticObservation(
            String state,
            int diagnostics,
            boolean code,
            boolean path,
            String event
    ) {
    }

    private record DraftRepairObservation(String state, int diagnostics, String source, String event) {
    }

    private record MultipleDraftObservation(
            String state,
            int diagnostics,
            boolean unhandledOutcome,
            boolean unreachableStep,
            boolean path,
            boolean eventPreserved
    ) {
    }

    private record MobileDraftObservation(
            int width,
            boolean visible,
            boolean insideInspector,
            boolean diagnostic,
            boolean path
    ) {
    }

    private record ConfigDefaultObservation(String value, String description, boolean resetDisabled) {
    }

    private record ConfigRunObservation(String state, boolean overrideVisible, boolean outputPresent) {
    }

    private record ConfigInputObservation(boolean overrideVisible, boolean resetEnabled) {
    }

    private record ConfigResetObservation(String value, boolean defaultVisible, boolean outputPresent) {
    }

    private record FlowInputAddObservation(
            String name,
            String shape,
            String sample,
            boolean missingInput,
            boolean exactPath
    ) {
    }

    private record FlowShapeObservation(String shape, boolean mismatch, boolean exactPath) {
    }

    private record FlowOutputAddObservation(String name, String source, boolean unmapped, boolean exactPath) {
    }

    private record FlowOutputRemoveObservation(String summary, int wires, boolean removalReported) {
    }

    private record SampleRunObservation(String sample, boolean outputPresent) {
    }

    private record MobileAuthoringObservation(
            int width,
            boolean configVisible,
            boolean contractVisible,
            boolean sampleVisible
    ) {
    }

    private record SavedDownload(String suggestedName, Path path) {
    }

    private record SavedFlowObservation(String filename, String source) {
    }

    private record ReopenedFlowObservation(
            String project,
            String config,
            String stepInput,
            String flowOutput,
            String route,
            String summary,
            boolean outputPresent
    ) {
    }

    private record RepeatedSaveObservation(long mismatch, String config, String summary) {
    }

    private record OpenRejectionObservation(String state, String config, boolean diagnostic, boolean flowPreserved) {
    }

    private record SaveRejectionObservation(int downloads, boolean diagnostic, String mapping) {
    }

    private record RepeatedOpenObservation(int nodes, String summary, String mapping, String route) {
    }

    private record MobilePersistenceObservation(
            int width,
            boolean openVisible,
            boolean saveVisible,
            String state,
            String config
    ) {
    }

    private record RouteObservation(String summary, String target, String state, String event) {
    }

    private record OutcomeDiagnosticObservation(String state, boolean unhandledOutcome, boolean exactPath) {
    }

    private record ReconnectObservation(
            String summary,
            String target,
            String event,
            String state,
            boolean outputPresent
    ) {
    }

    private record TargetObservation(String summary, String sourceTarget, String targetTarget, String event) {
    }

    private record NodeVisibilityObservation(String sourceOpacity, String targetOpacity) {
    }

    private record ReachabilityObservation(
            String state,
            boolean requiredInput,
            boolean exactPath,
            boolean unreachable,
            boolean unhandledOutcome
    ) {
    }

    private record MobileRouteObservation(
            int width,
            boolean sourceInsideCanvas,
            boolean targetInsideCanvas,
            String sourceTarget,
            String targetTarget,
            String summary
    ) {
    }

    private record DesktopRouteObservation(int height, boolean sourceInsideCanvas, boolean targetInsideCanvas) {
    }

    private record RemovalObservation(
            String summary,
            boolean noConnectionsRemoved,
            boolean noTransitionsRemoved,
            boolean entryPreserved,
            String compileState
    ) {
    }

    private record ConnectedRemovalObservation(
            String summary,
            boolean bothRoutesReported,
            String remainingTarget,
            boolean unhandledOutcome,
            boolean exactPath
    ) {
    }

    private record EntryRemovalObservation(
            String summary,
            String inspector,
            boolean connectionsReported,
            boolean transitionsReported,
            boolean entryReported
    ) {
    }

    private record RepeatedAddObservation(String summary, int secondStepCount, int thirdStepCount) {
    }

    private record MobileObservation(int width, String summary, boolean addVisible) {
    }
}
