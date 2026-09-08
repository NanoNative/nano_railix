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
        page.locator("#presentation-aspect").fill("1");
        page.locator("#presentation-aspect").press("Tab");
        page.waitForFunction("() => !state.writeActive && state.savedCreator.steps.one?.aspect === 1");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(functional);
        assertThat(applicationPid()).isEqualTo(pid);
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode("one");
        openInspectorTab("appearance");
        assertThat(page.locator("#presentation-shape").inputValue()).isEqualTo(shape);
        assertThat(page.locator("#presentation-aspect").inputValue()).isEqualTo("1");
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
    void clickingTheSelectedNodeReopensTheInspector() {
        openProject(choiceProject());
        selectTrigger();
        page.locator("#close-inspector").click();
        page.locator("[data-node-id='command']").click();

        page.waitForFunction("() => !document.querySelector('#inspector').hidden");
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
            assertThat(page.locator("#project-id").isVisible()).isTrue();
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
    void fittingWhileStepEditorLoadsRetainsTheFittedCamera() {
        openProject(filterProject());
        selectTrigger();
        openInspectorTab("examples");
        page.locator("[data-select-example='1']").click();
        page.locator("#delete-example").click();
        waitForText("#build-state", "Built");
        waitForText("#status-coverage span", "75% example coverage");
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

        assertThat(page.locator("[data-node-id='matched'] .world-detail").textContent()).isEqualTo("Metrics off");
        assertThat(page.locator("[data-node-id='otherwise']").getAttribute("data-activity")).isEqualTo("idle");
        assertThat(page.locator("[data-node-id='otherwise'] .world-detail").textContent())
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
        final ProcessHandle child = ProcessHandle.of(Long.parseLong(applicationPid())).orElseThrow();

        assertThat(child.destroy()).isTrue();
        page.waitForFunction("() => document.querySelector('[data-node-id=otherwise]')?.dataset.activity === ''");

        assertThat(page.locator("[data-node-id='otherwise'] .world-detail").textContent()).doesNotContain("executions");
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
