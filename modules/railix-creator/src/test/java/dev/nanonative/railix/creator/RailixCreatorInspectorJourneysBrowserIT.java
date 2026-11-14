package dev.nanonative.railix.creator;

import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

final class RailixCreatorInspectorJourneysBrowserIT extends RailixCreatorBrowserSupport {
    @ParameterizedTest
    @ValueSource(ints = {320, 390})
    void shortPhoneLayoutSeparatesRadarFromTheTimingScale(final int width) throws Exception {
        page.setViewportSize(width, 360);
        openProject(CreatorEditorBrowserIT.attentionProject());
        assertThat(page.evaluate("""
                async () => (await fetch('/api/creator', {method:'POST',headers:mutationHeaders(),body:JSON.stringify({
                  format:2,groups:[{id:'work',name:'Work'}],steps:{normalize:{group:'work'},result:{group:'work'}}})})).status
                """)).isEqualTo(200);
        page.reload();
        awaitScene();
        page.locator("#issue-count").click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Example: invalid-name").setExact(true)).click();
        page.locator("#close-inspector").click();
        awaitScene();
        assertThat(page.locator("#example-time-scale")).isVisible();
        final String measured = page.locator("#example-time-scale").getAttribute("aria-label");
        awaitScene();
        assertThat(page.locator("#example-time-scale")).isVisible();
        assertThat(page.locator("#example-time-scale")).hasAttribute("aria-label", measured);
        assertThat(page.locator("#group-return")).isVisible();
        final var radar = page.locator("#world-map").boundingBox();
        final var scale = page.locator("#example-time-scale").boundingBox();
        assertThat(scale.x + scale.width <= radar.x || radar.x + radar.width <= scale.x
                || scale.y + scale.height <= radar.y || radar.y + radar.height <= scale.y)
                .as("both navigation and timing must remain readable in a short viewport").isTrue();
        assertThat(scale.x).isGreaterThanOrEqualTo(0);
        assertThat(scale.x + scale.width).isLessThanOrEqualTo(width);
        assertThat(scale.y + scale.height).isLessThanOrEqualTo(page.locator(".status-rail").boundingBox().y);
        final var back = page.locator("#group-return").boundingBox();
        assertThat(back.x + back.width <= radar.x || radar.x + radar.width <= back.x
                || back.y + back.height <= radar.y || radar.y + radar.height <= back.y).isTrue();
        assertThat(back.x + back.width <= scale.x || scale.x + scale.width <= back.x
                || back.y + back.height <= scale.y || scale.y + scale.height <= back.y).isTrue();
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("hud-radar-short-" + width + ".png")));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void failedExampleAndUnavailableRecordedValuesKeepThePhoneHudInsideItsHeader() throws Exception {
        page.setViewportSize(320, 844);
        openProject(CreatorEditorBrowserIT.attentionProject());
        selectWorldNode("normalize");
        page.route("**/api/examples/*/view**", com.microsoft.playwright.Route::abort);
        page.locator("#visible-example").selectOption("command:1");
        assertThat(page.locator("#example-session [data-retry-example]")).isVisible();
        page.locator("#close-inspector").click();
        final var header = page.locator(".workbench-header").boundingBox();
        final var canvas = page.locator(".canvas-panel").boundingBox();
        for (final var control : page.locator("#example-session > button, #example-session summary").all()) {
            final var bounds = control.boundingBox();
            assertThat(bounds.x).isGreaterThanOrEqualTo(header.x);
            assertThat(bounds.x + bounds.width).isLessThanOrEqualTo(header.x + header.width);
            assertThat(bounds.y + bounds.height).isLessThanOrEqualTo(canvas.y);
        }
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("hud-phone-recorded-error.png")));
        page.unroute("**/api/examples/*/view**");
        page.locator("#example-session [data-retry-example]").click();
        assertThat(page.locator("#example-session [data-retry-example]")).hasCount(0);
        assertThat(page.locator("#visible-example")).hasValue("command:1");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void returningToTheTabKeepsTheLoadedRadarAndItsKeyboardFocus() {
        openProject(choiceProject());
        page.locator("#world-map").focus();
        final var overviewReads = new java.util.concurrent.atomic.AtomicInteger();
        page.onRequest(request -> {
            if (request.url().contains("/api/scene?") && java.util.Arrays.stream(
                    java.net.URI.create(request.url()).getQuery().split("&"))
                    .anyMatch(value -> value.startsWith("scale=") && Double.parseDouble(value.substring(6)) < 1e-9)) {
                overviewReads.incrementAndGet();
            }
        });
        page.evaluate("""
                () => {
                  Object.defineProperty(document, 'hidden', {configurable:true, value:true});
                  document.dispatchEvent(new Event('visibilitychange'));
                  delete document.hidden;
                  document.dispatchEvent(new Event('visibilitychange'));
                }
                """);
        assertThat(page.locator("#world-map")).isVisible();
        assertThat(page.locator("#world-map")).isFocused();
        awaitScene();
        assertThat(overviewReads.get()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void radarCaptionDoesNotMoveTheCameraOutsideTheMap() {
        openProject(deepBranchProject(128));
        final Object before = cameraCenter();
        assertThat(page.locator("#world-map .map-content").evaluate("e => getComputedStyle(e).cursor")).isEqualTo("crosshair");
        assertThat(page.locator("#world-map .map-title").evaluate("e => getComputedStyle(e).cursor")).isEqualTo("default");
        page.locator("#world-map .map-title").click();
        awaitScene();
        assertThat(cameraCenter()).isEqualTo(before);
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pointer", "keyboard"})
    @Tag("responsive")
    void radarNavigationTakesOverFromAnActiveCameraFlight(final String input) {
        page.clock().install();
        openProject(deepBranchProject(128));
        page.locator("#world-map").press("ArrowRight");
        awaitScene();
        // The protocol round-trip must not overtake the virtual pause point under parallel load.
        page.clock().pauseAt(((Number) page.evaluate("Date.now()")).longValue() + 60_000);
        try {
            page.locator("#scope-overview").press("Enter");
            assertThat(page.locator("#graph")).hasAttribute("data-camera-moving", "true");
            page.clock().runFor(32);
            if (input.equals("keyboard")) page.locator("#world-map").press("ArrowRight");
            else {
                final var content = page.locator("#world-map .map-content").boundingBox();
                page.locator("#world-map").click(new com.microsoft.playwright.Locator.ClickOptions()
                        .setForce(true).setPosition(content.width * .75, content.height * .25));
            }
            assertThat(page.locator("#graph")).hasAttribute("data-camera-moving", "false");
            page.clock().runFor(32);
            final String chosen = page.locator("#world-map .map-camera").getAttribute("style");
            page.clock().runFor(240);
            assertThat(page.locator("#world-map .map-camera")).hasAttribute("style", chosen);
            assertThat(pageErrors).isEmpty();
        } finally {
            page.clock().resume();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {320, 390, 823, 1280})
    void nestedLocationKeepsTheCurrentGroupAndEveryParentReachable(final int width) throws Exception {
        page.setViewportSize(width, 844);
        final var fixture = CreatorWorldBrowserIT.systemStressScene();
        openProject(fixture.project().replace("\"commerce-system\"", "\"commerce-system-" + "workspace".repeat(5) + "\""));
        assertThat(page.evaluate("""
                async creator => (await fetch('/api/creator', {
                  method:'POST',headers:mutationHeaders(),body:creator})).status
                """, fixture.creator())).isEqualTo(200);
        page.reload();
        awaitScene();
        page.locator("#issue-count").click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Example: invalid-name").setExact(true)).click();
        page.locator(".nested-step > [data-field-error]").first().waitFor();
        page.locator("#close-inspector").click();
        awaitScene();
        final var current = page.locator("#world-location [aria-current=location]");
        final var currentBounds = current.boundingBox();
        final var overview = page.locator("#scope-overview").boundingBox();
        assertThat(currentBounds.x + currentBounds.width).isLessThanOrEqualTo(overview.x);
        assertThat(currentBounds.width).isGreaterThanOrEqualTo(80);
        assertThat(current).containsText("Path 1 Intake");
        page.locator("#open-location-parents").press("Enter");
        assertThat(page.locator("#location-parents")).isVisible();
        final var origin = page.locator("#open-location-parents").boundingBox();
        final var parents = page.locator("#location-parents").boundingBox();
        assertThat(parents.y).isCloseTo(origin.y + origin.height + 4, within(1.0));
        assertThat(parents.x).isCloseTo(origin.x, within(1.0));
        assertThat(parents.x + parents.width).isLessThanOrEqualTo(width);
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("hud-parents-" + width + ".png")));
        page.locator("#open-location-parents").press("Escape");
        assertThat(page.locator("#location-parents")).isHidden();
        assertThat(page.locator("#open-location-parents")).isFocused();
        page.locator("#open-location-parents").click();
        page.locator("#location-parents [data-world-level='1']").press("Enter");
        assertThat(page.locator("#world-location [aria-current=location]")).containsText("stock");
        assertThat(page.locator("#visible-example")).hasValue("api:3");
        assertThat(page.locator("#location-parents")).isHidden();
        assertThat(page.locator("#graph")).isFocused();
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {320, 390})
    @Tag("browser-diagnostics")
    void failedExampleNamesRemainReadableAlongsideTheirActions(final int width) throws Exception {
        page.setViewportSize(width, 844);
        openProject(CreatorEditorBrowserIT.attentionProject());
        final var chooser = page.locator("#visible-example").elementHandle();
        page.locator("#issue-count").click();
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Inspect Example: invalid-name").setExact(true)).click();
        page.locator(".nested-step > [data-field-error]").first().waitFor();
        assertThat(page.locator("#visible-example").boundingBox().width).isGreaterThanOrEqualTo(200);
        assertThat(page.locator("#visible-example")).hasValue("command:1");
        assertThat(chooser.evaluate("e => e.isConnected && e === document.querySelector('#visible-example')")).isEqualTo(true);
        chooser.dispose();
        assertThat(page.locator("#visible-example").evaluate("e => e.selectedOptions[0].textContent"))
                .as("the selected failing Example stays identifiable").isEqualTo("\u00d7 invalid-name");
        assertThat(page.locator("#example-session > button")).hasCount(4);
        for (final var action : page.locator("#example-session > button").all()) {
            assertThat(action.boundingBox().width).isGreaterThanOrEqualTo(44);
            assertThat(action.boundingBox().height).isGreaterThanOrEqualTo(44);
        }
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("hud-phone-error-" + width + ".png")));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void aDelayedGroupReadCannotReplaceTheTabOrFieldTheUserChoseNext() {
        openProject(branchProject("group-loading", "choice", "[]"));
        selectWorldNode("matched");
        openInspectorTab("appearance");
        final var held = new java.util.ArrayList<com.microsoft.playwright.Route>();
        page.route("**/api/editor?**", held::add);
        page.locator("#inspector-tab-groups").click();
        page.waitForCondition(() -> !held.isEmpty());
        assertThat(page.locator("#inspector-tab-groups")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#inspector")).hasAttribute("aria-busy", "true");
        assertThat(page.locator(".panel-loading")).isVisible();
        page.locator("#inspector-tab-appearance").click();
        page.locator("#presentation-name").focus();
        page.unroute("**/api/editor?**");
        held.removeFirst().resume();
        page.waitForFunction("() => !state.editorController");
        assertThat(page.locator("#inspector-tab-appearance")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#presentation-name")).isFocused();
        assertThat(page.locator(".panel-loading")).hasCount(0);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void aDelayedGroupReadCannotReopenTheClosedInspector() {
        openProject(branchProject("group-closing", "choice", "[]"));
        selectWorldNode("matched");
        final var held = new java.util.ArrayList<com.microsoft.playwright.Route>();
        page.route("**/api/editor?**", held::add);
        page.locator("#inspector-tab-groups").click();
        page.waitForCondition(() -> !held.isEmpty());
        page.locator("#close-inspector").click();
        page.unroute("**/api/editor?**");
        held.removeFirst().resume();
        page.waitForFunction("() => !state.editorController");
        assertThat(page.locator("#inspector")).isHidden();
        assertThat(page.locator("#graph")).isFocused();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void aFailedGroupReadOffersAnExplicitRetryAndKeepsTheApplicationRunning() {
        openProject(branchProject("group-retry", "choice", "[]"));
        selectWorldNode("matched");
        page.route("**/api/editor?**", com.microsoft.playwright.Route::abort);
        page.locator("#inspector-tab-groups").click();
        final var retry = page.locator("#inspector-content").getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new com.microsoft.playwright.Locator.GetByRoleOptions().setName("Retry").setExact(true));
        assertThat(retry).isVisible();
        assertThat(page.locator(".panel-loading")).hasCount(0);
        waitForText("#build-state", "Built");
        page.unroute("**/api/editor?**");
        retry.click();
        assertThat(page.locator("#choose-group")).isVisible();
        assertThat(page.locator("#inspector")).hasAttribute("aria-busy", "false");
        assertThat(retry).hasCount(0);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void exampleEditingKeepsKeyboardNavigationAndItsOwnAccessibleIdentity() {
        page.setViewportSize(390, 844);
        openProject(branchProject("example-editor", "choice", "[]"));
        selectWorldNode("command");
        page.locator("#edit-example").click();
        assertThat(page.locator("#example-name")).isVisible();
        assertThat(page.locator("#inspector-content")).hasAttribute("aria-label", "Examples");
        assertThat(page.locator("#inspector-nav [role=tab][tabindex='0']")).hasCount(1);
        page.locator("#inspector-tab-inspect").press("ArrowRight");
        assertThat(page.locator("#inspector-tab-overview")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#inspector-content")).hasAttribute("aria-labelledby", "inspector-tab-overview");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void literalEditorChangesBetweenShortValuesAndStructuredJsonWithoutLosingData() {
        openProject(branchProject("literal-controls", "choice", """
                [[{"option":"literal","inputs":{"value":true},"when":{"transforms":[],"all":[]}}]]
                """));
        selectWorldNode("choice");
        final var value = page.locator(".candidate-source [data-input-json]").first();
        assertThat(value).hasAttribute("type", "text");
        clickAndWaitForCreatorSave(() -> {
            value.fill("{\"nested\":{\"count\":12},\"text\":\"hello\"}");
            value.press("Tab");
        });
        assertThat(page.locator(".candidate-source textarea")).hasCount(1);
        clickAndWaitForCreatorSave(() -> {
            value.fill("\"a\\nline\"");
            value.press("Tab");
        });
        assertThat(value).hasAttribute("type", "text");
        assertThat(value).hasValue("\"a\\nline\"");
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode("choice");
        assertThat(value).hasValue("\"a\\nline\"");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void groupingOwnsMembershipAndDoesNotRepeatTheCurrentTarget() {
        page.setViewportSize(320, 844);
        openProject(branchProject("group-controls", "choice", "[]"));
        selectWorldNode("matched");
        openInspectorTab("overview");
        page.locator("[data-group-selection]").click();
        page.locator("#group-draft input").fill("Order preparation");
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Create group").setExact(true)).click();
        page.waitForFunction("() => !state.actionPending && state.creator.groups.some(group => group.name === 'Order preparation')");
        selectWorldNode("matched");
        openInspectorTab("groups");
        assertThat(page.locator("#choose-group")).hasText("Order preparation");
        assertThat(page.locator(".manager-heading")).hasCount(0);
        final var color = page.locator("#presentation-color").boundingBox();
        final var reset = page.locator("#reset-color").boundingBox();
        assertThat(color.y).isEqualTo(reset.y);
        openInspectorTab("appearance");
        assertThat(page.locator("#choose-group")).hasCount(0);
        assertThat(page.locator("#presentation-name")).isVisible();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void phoneConnectionAndInsertionReturnToTheirOriginalActions() {
        page.setViewportSize(390, 844);
        openProject(branchProject("phone-connections", "choice", "[]"));
        selectWorldNode("choice");
        chooseVisibleExample("command:0");
        page.locator("[data-output-port='choice.match']").click();
        assertThat(page.locator("#inspector")).isHidden();
        assertThat(page.locator(".canvas-panel")).not().hasAttribute("inert", "");
        page.locator("[data-cancel-connection]").click();
        assertThat(page.locator("#inspector")).isVisible();
        assertThat(page.locator("[data-output-port='choice.match']")).isFocused();
        selectWorldNode("matched");
        openInspectorTab("overview");
        page.locator("#add-next-step").click();
        assertThat(page.locator("#inspector")).isHidden();
        assertThat(page.locator("#step-search")).isFocused();
        page.locator("#step-search").press("Escape");
        assertThat(page.locator("#inspector")).isVisible();
        assertThat(page.locator("#add-next-step")).isFocused();
        page.locator("#open-settings").click();
        assertThat(page.locator("#audio-panel")).isVisible();
        page.locator("#close-settings").click();
        assertThat(page.locator("[data-inspector-mode=overview]")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#open-settings")).isFocused();
        page.locator("[data-locate-trigger]").click();
        assertThat(page.locator("#inspector")).isHidden();
        awaitScene();
        assertThat(page.locator("[data-world-id=command]")).hasAttribute("aria-pressed", "true");
        assertThat(page.locator(".workbench-header #visible-example")).hasValue("command:0");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void largeProjectKeepsTheFourInspectorSurfacesAndSettingsReadable() throws Exception {
        openProject(RailixCreatorBranchBrowserIT.project());
        selectWorldNode("dispatch");
        chooseVisibleExample("command:0");
        assertThat(page.locator(".route .matcher-group-add[open]")).hasCount(0);
        assertThat(page.locator(".route [data-matcher-group-options]").first()).isHidden();
        final Path screenshots = Files.createDirectories(Path.of("target", "screenshots"));
        for (final String mode : new String[]{"inspect", "overview", "appearance", "groups"}) {
            openInspectorTab(mode);
            assertThat(page.locator("#inspector-content").evaluate("e => e.scrollWidth <= e.clientWidth")).isEqualTo(true);
            assertThat(page.locator("#visible-example")).hasValue("command:0");
            page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("inspector-desktop-" + mode + ".png")));
        }
        page.locator("#open-settings").click();
        for (final String tab : new String[]{"appearance", "sound", "music"}) {
            page.locator("#settings-" + tab + "-tab").click();
            assertThat(page.locator("#settings-" + tab)).isVisible();
            assertThat(page.locator("#audio-panel").evaluate("e => e.scrollWidth <= e.clientWidth")).isEqualTo(true);
        }
        page.locator("#close-settings").click();
        assertThat(page.locator("[data-inspector-mode=groups]")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("#open-settings")).isFocused();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void phoneEditingUsesTheScreenAndReturnsToTheSameExampleAndCamera() throws Exception {
        page.setViewportSize(320, 844);
        openProject(branchProject("phone-editor", "choice", """
                [[{"option":"literal","inputs":{"value":true},"when":{"transforms":[],
                  "all":[[{"use":"value.equals","inputs":{"expected":true}}]]}}]]
                """));
        selectWorldNode("choice");
        chooseVisibleExample("command:0");
        final var content = page.locator("#inspector-content").boundingBox();
        assertThat(content.height).as("usable editor height, not a squeezed map/editor split").isGreaterThan(440);
        assertThat(page.locator("#inspector #visible-example")).hasCount(1);
        assertThat(page.locator("#inspector #editor-undo")).hasCount(1);
        assertThat(page.locator("#inspector #editor-redo")).hasCount(1);
        assertThat(page.locator(".condition-predicate [data-input-json]").first()).isInViewport();
        final var expected = page.locator(".condition-predicate [data-input-json]").first().boundingBox();
        assertThat(expected.y + expected.height).isLessThanOrEqualTo(content.y + content.height);
        final var prepare = page.locator(".condition-transforms > summary").first().boundingBox();
        final var comparison = page.locator(".condition-predicate > .candidate-heading strong").first().boundingBox();
        assertThat(comparison.y - prepare.y - prepare.height).as("one spacing boundary, not stacked empty margins").isBetween(0.0, 24.0);
        assertThat(page.locator(".workbench-header")).hasAttribute("inert", "");
        assertThat(page.locator(".canvas-panel")).hasAttribute("inert", "");
        assertThat(page.locator(".world-raster")).hasAttribute("data-animated", "false");
        assertThat(page.locator("#inspector-content").evaluate("e => e.scrollWidth <= e.clientWidth")).isEqualTo(true);
        awaitScene();
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("inspector-phone-configure.png")));
        final Object camera = cameraCenter();
        page.locator("#close-inspector").click();
        assertThat(page.locator("#inspector")).isHidden();
        assertThat(page.locator(".workbench-header #visible-example")).hasValue("command:0");
        assertThat(page.locator(".canvas-panel")).not().hasAttribute("inert", "");
        assertWorldAnimation();
        page.locator("#graph").press("e");
        assertThat(page.locator("#inspector")).isVisible();
        assertThat(page.locator("#visible-example")).hasValue("command:0");
        awaitScene();
        assertThat(cameraCenter()).isEqualTo(camera);
        page.locator("#close-inspector").press("Escape");
        page.locator("#open-settings").click();
        page.locator("#settings-sound-tab").press("End");
        assertThat(page.locator("#settings-music-tab")).hasAttribute("aria-selected", "true");
        page.locator("#close-settings").press("Escape");
        assertThat(page.locator("#inspector")).isHidden();
        assertThat(page.locator("#open-settings")).isFocused();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void inspectorTabsSupportKeyboardAndKeepTheirIdentityAfterViewportChanges() {
        openProject(RailixCreatorBranchBrowserIT.project());
        selectWorldNode("dispatch");
        page.locator("[data-inspector-mode=inspect]").focus();
        page.locator("[data-inspector-mode=inspect]").press("ArrowRight");
        assertThat(page.locator("[data-inspector-mode=overview]")).hasAttribute("aria-selected", "true");
        assertThat(page.locator("[data-inspector-mode=overview]")).isFocused();
        page.locator("[data-inspector-mode=overview]").press("ArrowRight");
        assertThat(page.locator("[data-inspector-mode=appearance]")).hasAttribute("aria-selected", "true");
        page.setViewportSize(390, 844);
        assertThat(page.locator("#inspector #editor-undo")).hasCount(1);
        page.locator("[data-inspector-mode=appearance]").press("End");
        assertThat(page.locator("[data-inspector-mode=groups]")).hasAttribute("aria-selected", "true");
        page.locator("[data-inspector-mode=groups]").press("Home");
        assertThat(page.locator("[data-inspector-mode=inspect]")).hasAttribute("aria-selected", "true");
        page.setViewportSize(1280, 800);
        assertThat(page.locator(".status-rail #editor-undo")).hasCount(1);
        assertThat(page.locator(".workbench-header #visible-example")).hasCount(1);
        assertThat(page.locator(".canvas-panel")).not().hasAttribute("inert", "");
        assertThat(pageErrors).isEmpty();
    }
}
