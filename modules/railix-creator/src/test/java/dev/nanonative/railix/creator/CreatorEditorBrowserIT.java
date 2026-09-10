package dev.nanonative.railix.creator;

import com.microsoft.playwright.Route;
import com.microsoft.playwright.Page;
import dev.nanonative.railix.core.value.RailixValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

final class CreatorEditorBrowserIT extends RailixCreatorBrowserSupport {
    @Test
    @Tag("responsive")
    void buildDetailsDoNotReplaceTheSelectedStationOrOpenItsInspector() {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.locator("#close-inspector").click();
        page.locator(".build-indicator").click();
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(page.locator("#selection-dock").getAttribute("data-selection")).isEqualTo("one");
        assertThat(page.locator("#application-status").isVisible()).isTrue();
        assertThat(page.locator("#application-status #build-path").textContent()).isNotBlank();
        page.keyboard().press("Escape");
        assertThat(page.locator("#application-status").isVisible()).isFalse();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void closingBuildDetailsKeepsAnAlreadyOpenInspectorAndItsSelection() {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.locator(".build-indicator").click();
        page.keyboard().press("Escape");
        assertThat(page.locator("#application-status").isVisible()).isFalse();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(page.locator("#selection-dock").getAttribute("data-selection")).isEqualTo("one");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void configureIsTheOnlyGeneralInspectorActionOnTheStationDock() {
        openProject(fourStepProject());
        page.locator("[data-world-id=one]").click();
        assertThat(page.locator(".brand").evaluate("element => element.tagName")).isNotEqualTo("BUTTON");
        assertThat(page.locator("#selection-dock [data-open-panel]").count()).isZero();
        page.locator("#open-inspector").click();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(page.locator("[data-inspector-mode=appearance]").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void factoryHudControlsDoNotOverlapOrOpenTheWrongAction() {
        final var build = page.locator(".build-indicator").boundingBox();
        final var zoom = page.locator(".canvas-tools").boundingBox();
        assertThat(build.x + build.width <= zoom.x || zoom.x + zoom.width <= build.x
                || build.y + build.height <= zoom.y || zoom.y + zoom.height <= build.y).isTrue();
        page.locator("#zoom-fit").click();
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        page.locator(".build-indicator").click();
        page.locator("#application-status #workspace-details").waitFor();
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void exampleChooserIsAtItsTriggerAndKeyboardInputDoesNotPanTheWorld() {
        openProject(choiceProject());
        selectTrigger();
        page.locator("#close-inspector").click();
        page.locator("#dock-focus").click();
        page.waitForFunction("() => Number(new URLSearchParams(state.world.query).get('scale')) >= 1");
        awaitScene();
        page.locator("#trigger-example:not([hidden]) #dock-example").waitFor();
        final String camera = (String) page.evaluate("() => state.world.query");
        final var before = page.locator("[data-world-id='command'] .world-symbol").boundingBox();
        page.locator("#dock-example").focus();
        page.locator("#dock-example").press("ArrowLeft");
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
        assertThat(page.evaluate("() => state.world.query")).isEqualTo(camera);
        page.locator("#dock-example").selectOption("1");
        final var panel = page.locator("#trigger-example").boundingBox();
        final var trigger = page.locator("[data-world-id='command'] .world-symbol").boundingBox();
        assertThat(trigger.x).isEqualTo(before.x);
        assertThat(panel.y + panel.height).isLessThan(trigger.y);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void startupFailureIsVisibleWithoutOpeningTheInspector() {
        page.navigate(creator.baseUri().resolve("/?unauthorized") + "#token=invalid");
        waitForText("#build-state", "Unavailable");
        assertThat(page.locator("#world-error").isVisible()).isTrue();
        assertThat(page.locator("#world-error").textContent()).contains("Creator could not open the project");
        assertThat(page.locator("#selection-dock").isVisible()).isFalse();
    }

    @Test
    @Tag("responsive")
    void theDockShowsRealInputAndOutputWithoutOpeningTheInspector() {
        addGraphPrimitive("\"RAILIX\"", "lowercase", "text.lowercase");
        page.locator("[data-input-name='target'] [data-path-value='after']").waitFor();
        page.locator("#close-inspector").click();
        assertThat(page.locator("[data-dock-value='input'] output").textContent()).isEqualTo("\"RAILIX\"");
        assertThat(page.locator("[data-dock-value='output'] output").textContent()).isEqualTo("\"railix\"");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void switchingTheDockExampleReadsItsRecordedRouteWithoutBuildingOrExecuting() throws Exception {
        openProject(choiceProject());
        selectTrigger();
        page.locator(".run-result").waitFor();
        page.locator("#close-inspector").click();
        final String before = Files.readString(directory.resolve("project.json"));
        final String pid = applicationPid();
        final List<String> writes = new ArrayList<>();
        page.onRequest(request -> {
            if (!List.of("GET", "HEAD").contains(request.method())) writes.add(request.method() + " " + request.url());
        });
        page.locator("#dock-example").focus();
        page.locator("#dock-example").selectOption("1");
        page.waitForFunction("() => document.querySelector('#dock-example')?.selectedOptions[0]?.textContent === 'otherwise' && state.application.example?.name === 'otherwise'");
        assertThat(page.evaluate("() => document.activeElement?.id")).isEqualTo("dock-example");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(applicationPid()).isEqualTo(pid);
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        assertThat(writes).isEmpty();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void observationPollingPreservesTheExampleChooserAndRecordedValues() {
        openProject(choiceProject());
        selectTrigger();
        page.locator(".run-result").waitFor();
        page.locator("#close-inspector").click();
        page.locator("#dock-example").focus();
        final var chooser = page.locator("#dock-example").elementHandle();
        final var value = page.locator("[data-dock-value='output'] output").elementHandle();
        page.waitForResponse(response -> response.url().contains("/api/scene/observations"), () -> { });
        assertThat(chooser.evaluate("element => element === document.activeElement")).isEqualTo(true);
        assertThat(value.evaluate("element => element === document.querySelector('[data-dock-value=output] output')")).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void closingConstructionRestoresTheDockWithoutEditingTheProject() throws Exception {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.locator("#close-inspector").click();
        final String before = Files.readString(directory.resolve("project.json"));
        page.locator("#add-next-step").click();
        assertThat(page.locator("#selection-dock").isVisible()).isFalse();
        page.locator("[data-close-picker]").click();
        assertThat(page.locator("#selection-dock").isVisible()).isTrue();
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(before);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void groupSelectionOffersZoomBeforeGroupEditing() {
        openProject(fourStepProject());
        final String group = createGroup("one", "two");
        page.locator("#close-inspector").click();
        page.locator("[data-region-group='" + group + "']").first().click();
        page.locator("#enter-region").waitFor();
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        page.locator("[data-manage-region]").click();
        page.locator("#group-search").waitFor();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void selectingAGroupDismissesConstructionAndItsPlacementPreview() {
        openProject(fourStepProject());
        final String group = createGroup("one", "two");
        selectTrigger();
        page.locator("#close-inspector").click();
        page.locator("#add-next-step").click();
        page.locator("[data-add-step]").first().focus();
        page.locator(".world-placement").waitFor();
        page.locator("[data-region-group='" + group + "']").first().click();
        assertThat(page.locator(".construction-palette").count()).isZero();
        page.waitForFunction("() => !document.querySelector('.world-placement')");
        assertThat(page.locator("#enter-region").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void selectingAGroupSupersedesADelayedApplicationSelection() {
        openProject(fourStepProject());
        final String group = createGroup("one", "two");
        page.locator("#close-inspector").click();
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  window.fetch = async (...args) => {
                    const response = await fetch.apply(window, args);
                    if (String(args[0]).startsWith('/api/editor?node=app'))
                      await new Promise(resolve => window.__releaseAppEditor = resolve);
                    return response;
                  };
                  window.__restoreAppEditor = () => { window.__releaseAppEditor?.(); window.fetch = fetch; };
                }
                """);
        try {
            page.locator("[data-world-id=app]").click();
            page.waitForFunction("() => Boolean(window.__releaseAppEditor)");
            page.locator("[data-region-group='" + group + "']").first().click();
            page.evaluate("() => window.__releaseAppEditor()");
            page.waitForFunction("() => !state.editorController");
            assertThat(page.locator("#inspector").isVisible()).isFalse();
            assertThat(page.locator("#enter-region").isVisible()).isTrue();
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => window.__restoreAppEditor()");
        }
    }

    @Test
    void deletingTheSelectedGroupRestoresItsStepsConstructionTools() {
        openProject(fourStepProject());
        final String group = createGroup("one", "two");
        page.locator("#close-inspector").click();
        page.locator("[data-region-group='" + group + "']").first().click();
        page.locator("[data-manage-region]").click();
        clickAndWaitForCreatorSave(() -> page.locator("#delete-group").click());
        page.locator("#close-inspector").click();
        assertThat(page.locator("#enter-region").count()).isZero();
        assertThat(page.locator("#selection-dock #add-next-step").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void workspaceStartsWithConstructionToolsInsteadOfAnOpenInspector() {
        page.reload();
        waitForText("#build-state", "Built");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(page.locator("#selection-dock").isVisible()).isTrue();
        assertThat(page.locator("#selection-dock #add-trigger").isVisible()).isTrue();
        page.locator("#open-inspector").click();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        page.keyboard().press("Escape");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void selectingAStationKeepsTheCanvasAndContextualActionsVisible() {
        openProject(fourStepProject());
        page.reload();
        waitForText("#build-state", "Built");
        page.evaluate("() => void state.world.focus('one')");
        awaitScene();
        page.locator("[data-select-node='one']").click();
        page.waitForFunction("() => document.querySelector('#selection-dock')?.dataset.selection === 'one'");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(page.locator("#selection-dock #add-next-step").isVisible()).isTrue();
        assertThat(page.locator("#selection-dock #open-inspector").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void constructingAFlowFromTheDockBuildsTheRealApplication() throws Exception {
        page.reload();
        waitForText("#build-state", "Built");
        page.locator("#selection-dock #add-trigger").click();
        page.locator("[data-add-step='railix.trigger.cli']").click();
        page.locator("#dock-example").waitFor();
        page.locator("#add-next-step").click();
        page.locator("#step-search").fill("field manipulation");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        assertThat(Files.readString(directory.resolve("project.json"))).contains("railix.field-manipulation");
        assertThat(applicationPid()).isNotBlank();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void stationNamesSitOutsideCompactMachineBodies() {
        openProject(fourStepProject());
        selectWorldNode("one");
        final var label = page.locator("[data-node-id='one']");
        label.locator(".world-symbol").waitFor();
        final var symbol = label.locator(".world-symbol").boundingBox();
        final var name = label.locator("strong").boundingBox();
        assertThat(symbol.width).isBetween(20.0, 64.1);
        assertThat(symbol.height).isCloseTo(symbol.width, org.assertj.core.api.Assertions.within(0.1));
        assertThat(name.y).isGreaterThan(symbol.y + symbol.height);
        assertThat(label.locator(".world-detail").textContent()).doesNotContain("executions", "sampled", "context");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void appearanceControlsReflectTheDefaultMachineShape() {
        openProject(fourStepProject());
        selectWorldNode("one");
        openInspectorTab("appearance");
        assertThat(page.locator("#presentation-aspect").inputValue()).isEqualTo("1");
        assertThat(page.locator("#presentation-roundness").inputValue()).isEqualTo("12");
        assertThat(page.locator("#presentation-shape").inputValue()).isEqualTo("rectangle");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void choosingAStepPreviewsItsConnectionWithoutEditingTheProject() throws Exception {
        openProject(fourStepProject());
        selectWorldNode("one");
        final String source = Files.readString(directory.resolve("project.json"));
        page.locator("#add-next-step").click();
        page.locator("[data-add-step]").first().focus();
        page.locator(".world-placement").waitFor();
        assertThat(page.locator(".world-placement").textContent()).isNotBlank();
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        page.keyboard().press("Escape");
        page.waitForFunction("() => !document.querySelector('.world-placement')");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(source);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void filteringAwayAPreviewClearsTheProspectiveStep() {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.locator("#add-next-step").click();
        page.locator("[data-add-step]").first().focus();
        page.locator(".world-placement").waitFor();
        page.locator("#step-search").fill("no-such-step");
        page.waitForFunction("() => !document.querySelector('.world-placement')");
        assertThat(page.locator("[data-add-step]").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void acceptingAPlacementBuildsTheRealInsertedStep() throws Exception {
        openProject(fourStepProject());
        selectWorldNode("one");
        final String before = applicationPid();
        page.locator("#add-next-step").click();
        page.locator("[data-add-step='railix.field-manipulation']").focus();
        page.locator(".world-placement").waitFor();
        page.locator("[data-add-step='railix.field-manipulation']").click();
        page.waitForFunction("() => !document.querySelector('.world-placement')");
        page.waitForFunction("before => Boolean(document.querySelector('#status-pid')?.textContent) && document.querySelector('#status-pid').textContent !== before", "PID " + before);
        waitForText("#build-state", "Built");
        assertThat(applicationPid()).isNotEqualTo(before);
        assertThat(Files.readString(directory.resolve("project.json"))).contains("step-");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void leavingAHoveredOptionRestoresTheKeyboardPlacementPreview() {
        openProject(fourStepProject());
        selectWorldNode("one");
        awaitScene();
        page.locator("#add-next-step").click();
        final var options = page.locator("[data-add-step]");
        final String focused = "Insert " + options.first().locator("strong").textContent();
        final String hovered = "Insert " + options.nth(1).locator("strong").textContent();
        page.locator(".step-picker header").hover();
        options.first().focus();
        page.waitForFunction("focused => document.querySelector('.world-placement strong')?.textContent === focused", focused);
        options.nth(1).hover();
        page.waitForFunction("hovered => document.querySelector('.world-placement strong')?.textContent === hovered", hovered);
        page.locator(".step-picker header").hover();
        page.waitForFunction("focused => document.querySelector('.world-placement strong')?.textContent === focused", focused);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void triggerTargetsShowOnlyTheirKnownExampleOutput() {
        prepareTextPayloadTrigger();
        page.locator("[data-input-name='target'] [data-path-value='after']").waitFor();
        assertThat(page.locator("[data-input-name='target'] [data-path-value='after']").textContent()).isEqualTo("\"RAILIX\"");
        assertThat(page.locator("[data-input-name='target'] [data-path-value='before']").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"aspect", "roundness"})
    void invalidShapeNumbersStayAtTheInputWithoutChangingTheDiagram(final String field) {
        openProject(fourStepProject());
        selectWorldNode("one");
        openInspectorTab("appearance");
        final String metadata = creatorMetadata();
        page.locator("#presentation-" + field).fill("-1");
        page.locator("#presentation-" + field).press("Tab");
        assertThat(page.locator("#presentation-" + field).inputValue()).isEqualTo("-1");
        assertThat(page.locator("#presentation-" + field).evaluate("input => input.validity.rangeUnderflow")).isEqualTo(true);
        assertThat(creatorMetadata()).isEqualTo(metadata);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void stalledObservationsExpireMotionWithoutDiscardingTheSelectedExample() {
        openProject(choiceProject());
        selectWorldNode("matched");
        page.waitForFunction("() => worldMotionActive() && currentWorldObservations()?.links.size > 0");
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  const releases = [];
                  window.fetch = async (...args) => {
                    const response = await fetch.apply(window, args);
                    if (String(args[0]).startsWith('/api/scene/observations?')) await new Promise(resolve => releases.push(resolve));
                    return response;
                  };
                  window.__restoreObservations = () => { window.fetch = fetch; releases.forEach(resolve => resolve()); };
                }
                """);
        try {
            page.waitForFunction("() => !worldMotionActive()");
            assertThat(page.evaluate("() => Boolean(currentWorldObservations())")).isEqualTo(true);
            assertThat(page.locator("[data-node-id='matched']").getAttribute("data-coverage")).isEqualTo("selected");
        } finally {
            page.evaluate("() => window.__restoreObservations()");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rectangle", "ellipse", "triangle", "diamond"})
    void appearanceShapeChangesOnlyMetadataAndSurvivesReload(final String shape) throws Exception {
        openProject(fourStepProject());
        selectWorldNode("one");
        final String functional = Files.readString(directory.resolve("project.json"));
        final String pid = applicationPid();
        openInspectorTab("appearance");
        page.locator("#presentation-shape").selectOption(shape);
        page.locator("#presentation-aspect").fill("2.625");
        page.locator("#presentation-aspect").press("Tab");
        page.waitForFunction("() => !state.writeActive && Number(numberText(state.savedCreator.steps.one?.aspect)) === 2.625");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(functional);
        assertThat(applicationPid()).isEqualTo(pid);
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode("one");
        openInspectorTab("appearance");
        assertThat(page.locator("#presentation-shape").inputValue()).isEqualTo(shape);
        assertThat(page.locator("#presentation-aspect").inputValue()).isEqualTo("2.625");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Tag("responsive")
    void sourceAndTargetShowTheBuiltExamplesValuesAtTheirFields() throws Exception {
        addGraphPrimitive("\"RAILIX\"", "lowercase", "text.lowercase");
        page.locator("[data-input-name='target'] [data-path-value='after']").waitFor();

        assertThat(page.locator("[data-input-name='source'] [data-path-value='before']").textContent())
                .isEqualTo("\"RAILIX\"");
        assertThat(page.locator("[data-input-name='target'] [data-path-value='before']").textContent())
                .isEqualTo("\"RAILIX\"");
        assertThat(page.locator("[data-input-name='target'] [data-path-value='after']").textContent())
                .isEqualTo("\"railix\"");
        assertThat(page.locator("#inspector").textContent()).doesNotContain("Built example", "Built output");
        final Path screenshots = Files.createDirectories(Path.of("target", "screenshots"));
        page.screenshot(new Page.ScreenshotOptions().setFullPage(true)
                .setPath(screenshots.resolve("inspector-field-values-" + page.viewportSize().width + ".png")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "false", "0", "\"\"", "[]", "{}", "0.10000000000000001"})
    void anExistingFieldIsNotConfusedWithMissing(final String value) {
        openProject(fourStepProject().replace("\"context\":{\"payload\":{}}",
                "\"context\":{\"payload\":{\"one\":" + value + "}}"));
        selectWorldNode("one");
        page.locator("[data-input-name='field'] [data-path-value='before']").waitFor();

        assertThat(page.locator("[data-input-name='field'] [data-path-value='before']").textContent())
                .isEqualTo(value);
    }

    @Test
    void closingAndReopeningKeepsAnInvalidJsonDraft() {
        selectWorldNode("app");
        addTrigger();
        openInspectorTab("examples");
        examplePayload().fill("{not finished");
        page.locator("#close-inspector").click();
        assertThat(page.locator("#inspector").isHidden()).isTrue();
        page.locator("#open-inspector").click();

        assertThat(examplePayload().inputValue()).isEqualTo("{not finished");
    }

    @Test
    void pathChoicesShowOnlyTheSelectedExamplesValues() {
        openProject(choiceProject());
        selectTrigger();
        openInspectorTab("examples");
        page.locator("[data-select-example='1']").click();
        selectWorldNode("choice");
        page.waitForFunction("() => state.traceStep?.id === 'choice'");
        page.locator("[data-open-path]").first().click();
        page.locator("[data-path-depth='1']").click();

        assertThat(page.locator("[data-path-part='value'] output").textContent()).isEqualTo("\"deny\"");
        assertThat(page.locator(".path-choices").textContent()).doesNotContain("allow");
    }

    @Test
    void aNewTargetShowsMissingBeforeAndItsRealWrittenValueAfter() {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.locator("[data-input-name='field'] [data-path-value='after']").waitFor();

        assertThat(page.locator("[data-input-name='field'] [data-path-value='before']").textContent())
                .isEqualTo("Missing");
        assertThat(page.locator("[data-input-name='field'] [data-path-value='after']").textContent())
                .isEqualTo("1");
    }

    @Test
    void anUnreachedStepDoesNotDisplayMissingAsIfItRan() {
        openProject(choiceProject());
        selectWorldNode("otherwise");

        waitForText("[data-input-name='field'] .path-values", "Not reached");
        assertThat(page.locator("[data-input-name='field'] [data-path-value]").count()).isZero();
    }

    @Test
    void inspectorClosePreservesSelectionExampleAndCamera() {
        openProject(choiceProject());
        selectTrigger();
        openInspectorTab("examples");
        page.locator("[data-select-example='1']").click();
        awaitScene();
        final Object before = page.evaluate("""
                () => ({selection: state.selection, example: state.exampleIndex,
                  camera: ['x','y','scale'].map(key => new URLSearchParams(state.world.query).get(key)),
                  project: JSON.stringify(state.project), creator: JSON.stringify(state.creator)})
                """);

        page.locator("#close-inspector").click();
        page.waitForFunction("() => document.querySelector('#inspector').hidden");
        awaitScene();

        assertThat(page.evaluate("""
                () => ({selection: state.selection, example: state.exampleIndex,
                  camera: ['x','y','scale'].map(key => new URLSearchParams(state.world.query).get(key)),
                  project: JSON.stringify(state.project), creator: JSON.stringify(state.creator)})
                """)).isEqualTo(before);
        assertThat(page.locator("#graph").boundingBox().width)
                .isGreaterThan(page.viewportSize().width - 40);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void reselectingAStationKeepsItsInspectorClosedUntilConfigureIsRequested() {
        openProject(choiceProject());
        selectTrigger();
        page.locator("#close-inspector").click();
        page.locator("[data-node-id='command']").click();

        page.waitForFunction("() => !state.editorController && document.querySelector('#selection-dock').dataset.selection === 'command'");
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        page.locator("#open-inspector").click();
        assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("command");
    }

    @Test
    void escapeClosesThePathChooserBeforeTheInspectorWithoutChangingThePath() {
        openProject(choiceProject());
        selectTrigger();
        final String before = page.locator("[data-open-path]").first().textContent();
        page.locator("[data-open-path]").first().click();

        page.keyboard().press("Escape");

        assertThat(page.locator(".path-browser").count()).isZero();
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        assertThat(page.locator("[data-open-path]").first().textContent()).isEqualTo(before);
        page.keyboard().press("Escape");
        assertThat(page.locator("#inspector").isHidden()).isTrue();
    }

    @Test
    void diagramNeedsNoViewModeOrDiscoveryControl() {
        openProject(choiceProject());

        assertThat(page.locator("[data-lens], #world-example, #world-objective").count()).isZero();
    }

    @Test
    void triggerExampleSelectionHighlightsItsRouteWithoutChoosingAView() {
        openProject(choiceProject());
        selectTrigger();
        openInspectorTab("examples");
        page.locator("[data-select-example='1']").click();

        waitForCoverage("otherwise", "selected");
        assertThat(page.locator("[data-select-example='1']").getAttribute("class")).contains("active");
    }

    @Test
    void selectedExampleSurvivesAppAndDownstreamInspection() {
        openProject(choiceProject());
        selectTrigger();
        openInspectorTab("examples");
        page.locator("[data-select-example='1']").click();
        selectWorldNode("app");
        selectWorldNode("otherwise");

        waitForCoverage("otherwise", "selected");
        selectTrigger();
        openInspectorTab("examples");
        assertThat(page.locator("[data-select-example='1']").getAttribute("class")).contains("active");
    }

    @Test
    void rejectedTriggerSelectionKeepsTheApplicationSelected() {
        openProject(choiceProject());
        final var editorRequests = Pattern.compile(".*/api/editor\\?.*");
        page.route(editorRequests, route -> {
            final var headers = new HashMap<>(route.request().headers());
            headers.put("x-railix-creator-token", "invalid-token");
            route.resume(new Route.ResumeOptions().setHeaders(headers));
        });
        try {
            final var response = page.waitForResponse(candidate -> editorRequests.matcher(candidate.url()).matches(),
                    () -> page.locator("[data-select-node='command']").click());
            assertThat(response.status()).isEqualTo(401);
            response.finished();
            page.waitForFunction("""
                    () => !state.editorController && state.localDiagnostics.some(issue =>
                      issue.code === 'CREATOR_EDITOR_UNAVAILABLE' && issue.node === 'command')
                    """);
            page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");

            assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("app");
            assertThat(page.locator("#selection-dock").getAttribute("data-selection")).isEqualTo("app");
            assertThat(pageErrors).isEmpty();
        } finally {
            page.unroute(editorRequests);
        }
    }

    @Test
    void supersededTriggerSelectionPreservesTheNewerCanvasSelection() {
        openProject(choiceProject());
        page.locator("#zoom-fit").click();
        awaitScene();
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  const probe = window.__supersededExample = {held:false};
                  window.fetch = async (...args) => {
                    const url = new URL(String(args[0]), location.href);
                    const editor = !probe.held && url.pathname === '/api/editor'
                      && url.searchParams.get('node') === 'command';
                    if (editor) probe.held = true;
                    const response = await fetch.apply(window, args);
                    if (editor) {
                      probe.status = response.status;
                      const text = response.text.bind(response);
                      response.text = async () => {
                        try {
                          return await text();
                        } finally {
                          // Reselection can abort the real body; wait for either consumer path to finish.
                          setTimeout(() => { probe.editorConsumed = true; }, 0);
                        }
                      };
                      await new Promise(resolve => { probe.releaseEditor = resolve; });
                    }
                    return response;
                  };
                  probe.restore = () => { probe.releaseEditor?.(); window.fetch = fetch; };
                }
                """);
        try {
            page.locator("[data-select-node='command']").click();
            page.waitForFunction("() => Boolean(window.__supersededExample.releaseEditor)");
            assertThat(page.evaluate("() => window.__supersededExample.status")).isEqualTo(200);

            final var response = page.waitForResponse(candidate -> candidate.url().contains("/api/editor?")
                            && List.of(java.net.URI.create(candidate.url()).getRawQuery().split("&")).contains("node=app"),
                    () -> page.locator("[data-select-node='app']").click());
            assertThat(response.status()).isEqualTo(200);
            response.finished();
            page.waitForFunction("""
                    () => !state.editorController && document.querySelector('#inspector')?.dataset.selection === 'app'
                      && document.querySelector('[data-select-node=app]')?.getAttribute('aria-pressed') === 'true'
                    """);
            final Object selectedExample = page.evaluate("""
                    () => JSON.stringify({selection:state.selection, index:state.exampleIndex, draft:state.exampleDraft})
                    """);

            page.evaluate("() => window.__supersededExample.releaseEditor()");
            page.waitForFunction("() => window.__supersededExample.editorConsumed === true");
            page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");

            assertThat(page.locator("#inspector").getAttribute("data-selection")).isEqualTo("app");
            assertThat(page.locator("[data-select-node='app']").getAttribute("aria-pressed")).isEqualTo("true");
            assertThat(page.evaluate("""
                    () => JSON.stringify({selection:state.selection, index:state.exampleIndex, draft:state.exampleDraft})
                    """)).isEqualTo(selectedExample);
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => window.__supersededExample.restore()");
        }
    }

    @Test
    void addingStepSupersedesAnUnfinishedTriggerFocus() {
        addTrigger();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.evaluate("""
                () => {
                  const fetch = window.fetch, trigger = state.selection.id;
                  const probe = window.__pendingFocus = {releases:[]};
                  window.fetch = async (...args) => {
                    const response = await fetch.apply(window, args);
                    const url = new URL(String(args[0]), location.href);
                    if (url.pathname === '/api/scene' && url.searchParams.get('focus') === trigger) {
                      const json = response.json.bind(response);
                      response.json = async () => {
                        const scene = await json();
                        if (!probe.released) await new Promise(resolve => probe.releases.push(resolve));
                        return scene;
                      };
                    }
                    return response;
                  };
                  probe.restore = () => {
                    probe.released = true;
                    window.fetch = fetch;
                    probe.releases.splice(0).forEach(release => release());
                  };
                  state.world.focus(trigger);
                }
                """);
        try {
            page.waitForFunction("() => window.__pendingFocus.releases.length > 0");
            addManipulationAfterSelected();
            waitForText("#build-state", "Built");
            page.evaluate("() => window.__pendingFocus.restore()");
            com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat(page.locator(".step-node.selected"))
                    .isVisible();
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => window.__pendingFocus.restore()");
        }
    }

    @Test
    void fittingWhileStepEditorLoadsRetainsTheFittedCamera() {
        openProject(filterProject());
        selectTrigger();
        openInspectorTab("examples");
        page.locator("[data-select-example='1']").click();
        page.locator("#delete-example").click();
        waitForText("#build-state", "Built");
        waitForText("#status-coverage span", "75% example coverage");
        page.locator("#close-inspector").click();
        awaitScene();
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  const probe = window.__unvisitedFit = {held:false, focusAfterFit:[]};
                  window.fetch = async (...args) => {
                    const url = new URL(String(args[0]), location.href);
                    const editor = !probe.held && url.pathname === '/api/editor'
                      && url.searchParams.get('node') === 'otherwise';
                    if (editor) probe.held = true;
                    if (probe.fitted && url.pathname === '/api/scene' && url.searchParams.has('focus')) {
                      probe.focusAfterFit.push(url.searchParams.get('focus'));
                    }
                    const response = await fetch.apply(window, args);
                    if (editor) {
                      probe.status = response.status;
                      await new Promise(resolve => { probe.releaseEditor = resolve; });
                      const text = response.text.bind(response);
                      response.text = async () => {
                        const body = await text();
                        // Cross a task boundary after the editor and selection continuations consume the body.
                        setTimeout(() => { probe.editorConsumed = true; }, 0);
                        return body;
                      };
                    }
                    return response;
                  };
                  probe.restore = () => { probe.releaseEditor?.(); window.fetch = fetch; };
                }
                """);
        try {
            page.locator("[data-select-node='otherwise']").click();
            page.waitForFunction("() => Boolean(window.__unvisitedFit.releaseEditor)");
            assertThat(page.evaluate("() => window.__unvisitedFit.status")).isEqualTo(200);

            page.locator("#zoom-fit").click();
            awaitScene();
            final Object fitted = page.evaluate("() => state.world.query");
            final String fittedZoom = page.locator("#zoom-level").textContent();
            page.evaluate("() => { window.__unvisitedFit.fitted = true; window.__unvisitedFit.releaseEditor(); }");
            page.waitForFunction("() => window.__unvisitedFit.editorConsumed === true && !state.editorController");
            awaitScene();

            assertThat(page.evaluate("() => state.world.query")).isEqualTo(fitted);
            assertThat(page.locator("#zoom-level").textContent()).isEqualTo(fittedZoom);
            assertThat(page.evaluate("() => window.__unvisitedFit.focusAfterFit")).isEqualTo(List.of());
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => window.__unvisitedFit.restore()");
        }
    }

    @Test
    void searchingGroupsFromTheSecondPageResetsPaginationToTheFirstMatchPage() {
        openProject(fourStepProject());
        assertThat(page.evaluate("""
                async () => {
                  const groups = Array.from({length:130}, (_, index) => {
                    const suffix = String(index).padStart(3, '0');
                    return {id:'group-' + suffix, name:(index < 70 ? 'Match ' : 'Other ') + suffix};
                  });
                  return (await fetch('/api/creator', {method:'POST', headers:mutationHeaders(),
                    body:JSON.stringify({format:2, groups, steps:{}})})).status;
                }
                """)).isEqualTo(200);
        page.reload();
        waitForText("#build-state", "Built");
        page.locator("#manage-groups").click();
        page.locator("#group-list [data-manage-group='group-000']").waitFor();
        page.locator("#inspector [data-group-page='64']").click();
        page.locator("#group-list [data-manage-group='group-100']").waitFor();
        // A nonmatching selection is retained by the server but must not add a seventh search result.
        page.locator("#group-list [data-manage-group='group-100']").click();
        assertThat(page.locator("#inspector [data-group-page]").last().getAttribute("data-group-page"))
                .isEqualTo("128");

        final var search = page.waitForResponse(candidate -> candidate.url().contains("/api/editor?")
                        && candidate.url().contains("q=match") && candidate.url().contains("offset=0"),
                () -> page.locator("#group-search").fill("match"));
        assertThat(search.status()).isEqualTo(200);
        search.finished();
        page.waitForFunction("""
                () => {
                  const names = [...document.querySelectorAll('#group-list [data-manage-group] strong')];
                  return names.length === 64 && names.every(name => name.textContent.startsWith('Match '));
                }
                """);

        final var next = page.waitForResponse(candidate -> candidate.url().contains("/api/editor?"),
                () -> page.locator("#inspector [data-group-page]").last().click());
        assertThat(next.status()).isEqualTo(200);
        assertThat(java.net.URI.create(next.url()).getRawQuery().split("&")).contains("q=match", "offset=64");
        next.finished();
        page.waitForFunction("() => document.querySelectorAll('#group-list [data-manage-group]').length === 6");
        assertThat(page.locator("#group-list [data-manage-group] strong").allTextContents())
                .containsExactly("Match 064", "Match 065", "Match 066", "Match 067", "Match 068", "Match 069");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void fittingDuringAPendingSceneRefreshDoesNotRevealAnOlderEdit() {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  window.fetch = async (...args) => {
                    const response = await fetch.apply(window, args);
                    if (String(args[0]).startsWith('/api/scene?') && !window.__releaseScene) {
                      await new Promise(resolve => window.__releaseScene = resolve);
                    }
                    return response;
                  };
                  window.__restoreSceneFetch = () => window.fetch = fetch;
                }
                """);
        try {
            addManipulationAfterSelected();
            page.waitForFunction("() => typeof window.__releaseScene === 'function'");
            page.locator("#zoom-fit").click();
            page.waitForFunction("() => document.querySelector('#graph').dataset.cameraMoving !== 'true'");
            final String fitted = page.locator("#zoom-level").textContent();
            page.evaluate("() => window.__releaseScene()");
            awaitScene();

            assertThat(page.locator("#zoom-level").textContent()).isEqualTo(fitted);
            assertThat(page.locator("[data-select-node='app']").isVisible()).isTrue();
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => { window.__releaseScene?.(); window.__restoreSceneFetch(); }");
        }
    }

    @Test
    void selectingAnotherNodeCancelsADelayedZoomWithoutMovingTheCamera() {
        openProject(fourStepProject());
        selectWorldNode("one");
        page.locator("#zoom-fit").click();
        awaitScene();
        page.evaluate("""
                () => {
                  const fetch = window.fetch;
                  window.fetch = async (...args) => {
                    const response = await fetch.apply(window, args);
                    const url = new URL(String(args[0]), location.href);
                    if (url.pathname === '/api/scene' && url.searchParams.get('focus') === 'one'
                        && !window.__releaseZoom) {
                      await new Promise(resolve => window.__releaseZoom = resolve);
                    }
                    return response;
                  };
                  window.__restoreZoomFetch = () => window.fetch = fetch;
                }
                """);
        try {
            page.locator("[data-select-node='one']").dblclick();
            page.waitForFunction("() => typeof window.__releaseZoom === 'function'");
            page.locator("[data-select-node='app']").click();
            page.waitForFunction("() => document.querySelector('#inspector').dataset.selection === 'app'");
            page.evaluate("() => window.__releaseZoom()");
            awaitScene();

            assertThat(page.locator("[data-select-node='app']").isVisible()).isTrue();
            assertThat(page.locator("[data-select-node='app']").getAttribute("aria-pressed")).isEqualTo("true");
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => { window.__releaseZoom?.(); window.__restoreZoomFetch(); }");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"command", "matched"})
    void nodeMetricsStayBehindADisclosureWithoutHidingTheirSetting(final String id) {
        openProject(choiceProject());
        selectWorldNode(id);

        assertThat(page.locator("#node-metrics").isVisible()).isTrue();
        assertThat(page.locator("#metrics-panel").isHidden()).isTrue();
        openInspectorSection("Runtime metrics");
        waitForText(".runtime-metrics .section-heading span", "Connected");
        assertThat(page.locator(".runtime-metrics").textContent()).contains("Step metrics");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void unchangedApplicationPollsDoNotRedrawTheWorldOrReplaceTheExamplePreview() {
        openProject(choiceProject());
        selectWorldNode("matched");
        awaitScene();
        page.waitForFunction("""
                () => Boolean(state.preview) && state.application.examples?.state === 'completed'
                  && !state.applicationRefreshing && state.observations?.nodes.get('matched')?.rate === 0
                """);
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
        page.evaluate("""
                () => {
                  const draw = WebGL2RenderingContext.prototype.drawArrays;
                  const fetch = window.fetch;
                  const probe = window.__idleWorld = {draws:0, replacements:0, polls:0};
                  const observer = new MutationObserver(records => probe.replacements += records.length);
                  observer.observe(document.querySelector('#preview-values'), {childList:true,subtree:true});
                  WebGL2RenderingContext.prototype.drawArrays = function(...args) {
                    if (this.canvas.id === 'world-canvas') probe.draws++;
                    return draw.apply(this, args);
                  };
                  window.fetch = async (...args) => {
                    const response = await fetch.apply(window, args);
                    if (String(args[0]) === '/api/examples/status') probe.polls++;
                    return response;
                  };
                  probe.restore = () => {
                    observer.disconnect();
                    WebGL2RenderingContext.prototype.drawArrays = draw;
                    window.fetch = fetch;
                  };
                }
                """);
        try {
            page.waitForFunction("() => window.__idleWorld.polls >= 3 && !state.applicationRefreshing");
            page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
            assertThat(page.evaluate("() => window.__idleWorld.draws")).isEqualTo(0);
            assertThat(page.evaluate("() => window.__idleWorld.replacements")).isEqualTo(0);
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("() => { window.__idleWorld.restore(); delete window.__idleWorld; }");
        }
    }

    @Test
    void diagramDistinguishesDisabledMetricsFromIdleStepsWithoutInventingTimings() {
        openProject(choiceProject().replace("\"id\":\"matched\",", "\"id\":\"matched\",\"metrics\":false,")
                .replace("\"value\":\"deny\"", "\"value\":\"allow\""));
        waitForText("#status-observations", "Observations connected");
        page.waitForFunction("() => document.querySelector('[data-node-id=matched]')?.dataset.activity === 'disabled'");

        assertThat(page.locator("[data-node-id='matched']").getAttribute("title")).isEqualTo("Metrics off");
        assertThat(page.locator("[data-node-id='otherwise']").getAttribute("data-activity")).isEqualTo("idle");
        assertThat(page.locator("[data-node-id='otherwise']").getAttribute("title"))
                .startsWith("0 executions").doesNotContain("sampled");
        assertThat(page.locator("[data-node-id='otherwise']").getAttribute("title"))
                .contains("No duration inferred.");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void stoppedApplicationClearsTheLiveSceneInsteadOfRetainingConnectedMetrics() {
        openProject(choiceProject());
        selectWorldNode("otherwise");
        waitForText("#status-observations", "Observations connected");
        page.waitForFunction("() => document.querySelector('[data-node-id=otherwise]')?.dataset.activity === 'active'");
        final ProcessHandle child = ProcessHandle.of(Long.parseLong(applicationPid())).orElseThrow();

        assertThat(child.destroy()).isTrue();
        page.waitForFunction("() => document.querySelector('[data-node-id=otherwise]')?.dataset.activity === ''");

        assertThat(page.locator("[data-node-id='otherwise']").getAttribute("title")).doesNotContain("executions");
        assertThat(page.locator("#status-observations").isHidden()
                || !page.locator("#status-observations").textContent().contains("connected")).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void unloadedGroupUsesItsPortableIconAndApplicationOwnedExampleCoverage() throws Exception {
        openProject(fourStepProject());
        assertThat(page.evaluate("""
                async () => {
                  const icon = state.icons[0];
                  const metadata = {format:2,groups:[{id:'flow-group',name:'Value preparation',
                    icon:{media_type:icon.media_type,data:icon.data}}],
                    steps:Object.fromEntries(['one','two','three','four'].map(id=>[id,{group:'flow-group'}]))};
                  return (await fetch('/api/creator',{method:'POST',headers:mutationHeaders(),body:JSON.stringify(metadata)})).status;
                }
                """)).isEqualTo(200);
        page.reload();
        waitForText("#build-state", "Built");
        page.waitForFunction("() => document.querySelector('[data-region-group=flow-group] img')?.naturalWidth > 0");
        assertThat(page.evaluate("() => state.editor.full")).isEqualTo(List.of("app"));
        selectTrigger();
        openInspectorTab("examples");
        page.locator("[data-select-example='0']").click();
        page.waitForFunction("""
                () => document.querySelector('[data-region-group=flow-group]')?.dataset.coverage === 'selected'
                """);

        assertThat(page.locator("[data-region-group='flow-group'] img").getAttribute("src")).startsWith("blob:");
        assertThat(page.locator("#status-coverage").textContent()).isEqualTo("100% example coverage");
        assertThat(pageErrors).isEmpty();
        page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(
                Files.createDirectories(Path.of("target", "screenshots"))
                        .resolve("world-group-example-" + page.viewportSize().width + ".png")));
    }

    @Test
    void selectingAnUnloadedTriggerLoadsItsExamplesWithoutLoadingTheFlow() {
        final List<String> wholeProjectReads = new ArrayList<>();
        page.onRequest(request -> {
            if (request.method().equals("GET") && request.url().endsWith("/api/project")) wholeProjectReads.add(request.url());
        });
        openProject(choiceProject());
        assertThat(page.evaluate("() => state.project.nodes.every(node => node.id === 'app' || !node.inputs)"))
                .isEqualTo(true);
        selectTrigger();
        try {
            page.waitForFunction("() => state.selection.type === 'trigger' && Boolean(state.runResult)",
                    null, new com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(10_000));
        } catch (final com.microsoft.playwright.TimeoutError failure) {
            throw new AssertionError(page.evaluate("() => JSON.stringify({selection:state.selection,editor:state.editor,project:state.project,errors:state.localDiagnostics,trace:state.traceStep,summary:state.traceSummary,application:state.application,traceCases:state.traceCases})")
                    + " Browser errors: " + pageErrors, failure);
        }
        assertThat(pageErrors).isEmpty();
        assertThat(page.locator("#inspector").textContent()).contains("matched");
        assertThat(wholeProjectReads).isEmpty();
    }

    @Test
    void delayedEditorReadWaitsForANewerWriteBeforeChangingNeighborhoods() {
        openProject(fourStepProject());
        selectWorldNode("four");
        final Object expected = page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  project.nodes.find(node => node.id === 'four').metrics = false;
                  project.nodes.find(node => node.id === 'two').metrics = false;
                  return project;
                }
                """);
        final List<String> writes = new ArrayList<>();
        page.onRequest(request -> {
            if (request.method().equals("PATCH") && request.url().endsWith("/api/project")) {
                writes.add(request.postData());
            }
        });
        page.evaluate("""
                () => {
                  const request = window.fetch.bind(window);
                  let editorHeld = false;
                  let writeHeld = false;
                  window.__editorWriteRace = {};
                  window.fetch = async (input, options = {}) => {
                    const url = new URL(typeof input === 'string' ? input : input.url, location.href);
                    const editor = !editorHeld && url.pathname === '/api/editor'
                      && url.searchParams.get('node') === 'two';
                    const write = !writeHeld && url.pathname === '/api/project' && options.method === 'PATCH';
                    if (editor) editorHeld = true;
                    if (write) writeHeld = true;
                    const response = await request(input, options);
                    if (editor || write) {
                      const payload = await response.clone().json();
                      const key = editor ? 'editor' : 'write';
                      window.__editorWriteRace[key] = {status: response.status, revision: Number(payload.revision)};
                      await new Promise(resolve => {
                        window.__editorWriteRace[editor ? 'releaseEditor' : 'releaseWrite'] = resolve;
                      });
                    }
                    if (editor) {
                      const text = response.text.bind(response);
                      response.text = async () => {
                        const body = await text();
                        // Observe the next task only after loadEditor can consume this real response body.
                        setTimeout(() => { window.__editorWriteRace.editorConsumed = true; }, 0);
                        return body;
                      };
                    }
                    return response;
                  };
                }
                """);
        try {
            page.evaluate("() => void state.world.focus('two')");
            page.locator("[data-select-node='two']").click();
            page.waitForFunction("() => Boolean(window.__editorWriteRace.releaseEditor)");
            page.locator("#node-metrics").uncheck();
            page.waitForFunction("() => Boolean(window.__editorWriteRace.releaseWrite)");
            assertThat(page.evaluate("""
                    () => window.__editorWriteRace.editor.status === 200
                      && window.__editorWriteRace.write.status === 200
                      && window.__editorWriteRace.write.revision > window.__editorWriteRace.editor.revision
                    """)).isEqualTo(true);

            page.evaluate("() => window.__editorWriteRace.releaseEditor()");
            page.waitForFunction("() => window.__editorWriteRace.editorConsumed === true");
            assertThat(page.locator("#inspector").getAttribute("data-selection"))
                    .as("The stale editor read must not replace the Inspector before the newer write is acknowledged")
                    .isEqualTo("four");

            page.evaluate("() => window.__editorWriteRace.releaseWrite()");
            page.waitForFunction("() => document.querySelector('#inspector')?.dataset.selection === 'two'");
            waitForText("#build-state", "Built");
            page.locator("#node-metrics").uncheck();
            waitForText("#build-state", "Built");

            assertThat(writes).hasSize(2);
            for (int index = 0; index < writes.size(); index++) {
                final var edit = CreatorServerE2eSupport.object(writes.get(index));
                final var changes = (RailixValue.ObjectValue) edit.values().get("changes");
                assertThat(changes.values()).containsOnlyKeys("nodes");
                assertThat(((RailixValue.ObjectValue) changes.values().get("nodes")).values())
                        .containsOnlyKeys(List.of("four", "two").get(index));
            }
            assertThat(page.evaluate("async () => (await (await fetch('/api/project')).json()).project"))
                    .isEqualTo(expected);
            assertThat(pageErrors).isEmpty();
        } finally {
            page.evaluate("""
                    () => {
                      window.__editorWriteRace.releaseEditor?.();
                      window.__editorWriteRace.releaseWrite?.();
                    }
                    """);
        }
    }

    @Test
    void invalidLiteralDraftSurvivesNavigationAndAnotherNodesSave() {
        openProject(fourStepProject());
        final Object expected = page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  project.nodes.find(node => node.id === 'two').metrics = false;
                  return project;
                }
                """);
        selectWorldNode("four");
        page.locator("#value-0-literal-value").fill("[");
        page.locator("#value-0-literal-value").press("Tab");
        assertThat(page.locator("#inspector").textContent()).contains("Value must be valid JSON.");

        selectWorldNode("app");
        selectWorldNode("two");
        final var response = page.waitForResponse(candidate -> candidate.url().endsWith("/api/project")
                && candidate.request().method().equals("PATCH"), () -> page.locator("#node-metrics").uncheck());
        assertThat(response.status()).isEqualTo(200);
        waitForText("#build-state", "Built");
        assertThat(page.evaluate("async () => (await (await fetch('/api/project')).json()).project"))
                .isEqualTo(expected);

        selectWorldNode("four");
        assertThat(page.locator("#value-0-literal-value").inputValue()).isEqualTo("[");
        assertThat(page.locator("#inspector").textContent()).contains("Value must be valid JSON.");
        assertThat(page.locator("#preview-values output").count()).isZero();
        assertThat(pageErrors).isEmpty();
    }
}
