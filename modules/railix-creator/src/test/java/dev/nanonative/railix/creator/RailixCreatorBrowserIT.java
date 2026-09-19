package dev.nanonative.railix.creator;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import dev.nanonative.railix.core.step.StepDefinition;
import dev.nanonative.railix.core.value.RailixData;
import dev.nanonative.railix.core.value.RailixJson;
import dev.nanonative.railix.core.value.RailixValue;
import dev.nanonative.railix.core.value.ValueShape;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import thirdparty.conformance.MatcherConformanceSteps;

import static org.assertj.core.api.Assertions.assertThat;

final class RailixCreatorWorkspaceBrowserIT extends RailixCreatorBrowserSupport {

    @Test
    void newProjectStartsWithOnePermanentCenteredApp() {
        assertThat(page.locator(".graph-stage").textContent())
                .contains("App")
                .doesNotContain("Add Trigger")
                .doesNotContain("CLI Trigger", "Field Manipulation");
        assertThat(page.locator(".app-node").count()).isEqualTo(1);
        assertThat(page.locator(".graph-stage .node button").count()).isZero();
        assertThat(page.locator("#inspector").isVisible()).isFalse();
        openInspectorTab("overview");
        assertThat(page.locator("#selection-overview").textContent()).contains("Add Trigger");
        assertThat(page.locator("#delete-step").count()).isZero();
    }

    @Test
    void emptyProjectAppSelectionOffersATriggerWithoutDiscoveryPrompts() {
        assertThat(page.locator("#world-objective").count()).isZero();

        page.locator(".app-node").click();

        assertThat(page.locator(".app-node").getAttribute("aria-pressed")).isEqualTo("true");
        openInspectorTab("overview");
        assertThat(page.locator("#add-trigger").isVisible()).isTrue();
        assertThat(page.locator("#step-search").count()).isZero();
    }

    @Test
    void triggerSelectionOffersItsNextStepWithoutDiscoveryPrompts() {
        addTrigger();
        waitForText("#build-state", "Built");
        selectWorldNode("app");

        selectTrigger();

        assertThat(page.locator(".trigger-node").getAttribute("aria-pressed")).isEqualTo("true");
        openInspectorTab("overview");
        assertThat(page.locator("#add-next-step").isVisible()).isTrue();
        assertThat(page.locator("#step-search").count()).isZero();
    }

    @Test
    void buildStatusShowsTruthfulWorkspaceFacts() {
        page.locator(".build-indicator").click();

        assertThat(page.locator("#project-path").isVisible()).isTrue();
        assertThat(page.locator("#application-status").textContent()).contains(
                directory.resolve("project.json").toAbsolutePath().normalize().toString(),
                "0 flows",
                "1 step",
                "Last build"
        );
    }

    @Test
    void buildStatusShowsTheRunningBuildPathAndPid() {
        page.locator(".build-indicator").click();

        assertThat(page.locator("#build-path").isVisible()).isTrue();
        assertThat(page.locator("#application-pid").isVisible()).isTrue();
        assertThat(page.locator("#build-path").textContent()).isNotBlank();
        assertThat(page.locator("#application-pid").textContent()).matches("[1-9][0-9]*");
    }

    @Test
    void appInspectorAutomaticallyShowsLiveRuntimeMetrics() {
        openInspectorSection("Runtime metrics");
        page.locator(".metric-systems").waitFor();

        assertThat(page.locator(".metric-systems").textContent())
                .contains("App", "JVM", "System", "Process uptime", "Heap", "Metric counter storage")
                .doesNotContain("Refresh");
    }

    @Test
    void inspectorRendersAvailableMetricDefinitionsWithoutAFrontendFieldList() {
        openInspectorSection("Runtime metrics");
        page.locator(".metric-systems").waitFor();
        final Map<?, ?> result = (Map<?, ?>) page.evaluate("""
                async () => {
                  const catalog = await (await fetch('/api/metrics/catalog')).json();
                  const data = await (await fetch('/api/metrics')).json();
                  const defined = Object.keys(catalog.metrics).filter(id =>
                    Object.hasOwn(data.process, id) || Object.hasOwn(data.application.metrics, id)
                    || Object.hasOwn(data, id));
                  return {defined, rendered: [...document.querySelectorAll('[data-metric-id]')].map(row=>row.dataset.metricId)};
                }
                """);

        assertThat((List<?>) result.get("defined")).isNotEmpty();
        assertThat((List<String>) result.get("rendered")).containsAll((List<String>) result.get("defined"));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void metricCatalogLoadsOnceWhileMetricValuesKeepUpdating() {
        openInspectorSection("Runtime metrics");
        page.locator(".metric-systems").waitFor();
        final List<String> reads = new ArrayList<>();
        page.onRequest(request -> {
            if (request.url().contains("/api/metrics")) reads.add(request.url());
        });
        page.waitForCondition(() -> reads.stream().filter(url -> url.endsWith("/api/metrics")).count() >= 2);

        assertThat(reads).noneMatch(url -> url.contains("/api/metrics/catalog"));
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void applicationStepHasNoOperationalMetricsSwitch() {
        assertThat(page.locator("#node-metrics").count()).isZero();
    }

    @Test
    void executableStepMetricsAreEnabledByDefault() {
        addTrigger();

        assertThat(page.locator("#node-metrics").isChecked()).isTrue();
    }

    @Test
    void disablingExecutableStepMetricsCompilesTheOverride() {
        addTrigger();
        page.locator("#node-metrics").uncheck();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.id !== 'app').metrics
                """)).isEqualTo(false);
    }

    @Test
    void reenablingExecutableStepMetricsRemovesTheOverride() {
        addTrigger();
        page.locator("#node-metrics").uncheck();
        waitForText("#build-state", "Built");
        page.locator("#node-metrics").check();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => !Object.hasOwn((await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.id !== 'app'), 'metrics')
                """)).isEqualTo(true);
    }

    @Test
    void selectingATriggerAutomaticallyShowsItsStepAndFlowMetrics() {
        addTrigger();
        openInspectorSection("Runtime metrics");
        page.locator(".runtime-metrics").waitFor();

        assertThat(page.locator(".runtime-metrics").textContent())
                .contains("Step metrics", "Step executions", "Flow executions");
    }

    @Test
    void selectedStepMetricsIdentifySampledTimingAndOmitUnsupportedInflightCounts() {
        addTrigger();
        openInspectorSection("Runtime metrics");
        page.locator(".runtime-metrics").waitFor();

        assertThat(page.locator(".runtime-metrics").textContent())
                .contains("Step sampled average", "Step duration maximum", "No sample")
                .doesNotContain("Step in-flight runs");
    }

    @Test
    void stoppedApplicationRemovesStaleRuntimeMetrics() {
        openInspectorSection("Runtime metrics");
        page.locator(".metric-systems").waitFor();
        final long pid = ((Number) page.evaluate(
                "async () => (await (await fetch('/api/application')).json()).pid"
        )).longValue();
        stopProcess(pid);

        page.waitForFunction("() => document.querySelector('.metric-systems') === null");

        assertThat(page.locator(".metric-systems").count()).isZero();
    }

    @Test
    void stoppedApplicationDoesNotPollUnavailableExampleEndpoints() {
        page.evaluate("""
                () => {
                  const request = window.fetch.bind(window);
                  window.__stoppedExampleRequests = 0;
                  window.fetch = (input, options = {}) => {
                    const url = typeof input === 'string' ? input : input.url;
                    if (url.startsWith('/api/examples')) {
                      window.__stoppedExampleRequests++;
                    }
                    return request(input, options);
                  };
                }
                """);
        final long pid = ((Number) page.evaluate(
                "async () => (await (await fetch('/api/application')).json()).pid"
        )).longValue();

        stopProcess(pid);
        page.waitForFunction("() => state.application.state === 'stopped'");
        page.evaluate("window.__stoppedExampleRequests = 0");
        page.waitForTimeout(300);

        assertThat(((Number) page.evaluate("window.__stoppedExampleRequests")).intValue())
                .isLessThanOrEqualTo(1);
    }

    @Test
    void metricsFromThePreviousApplicationCannotRenderAfterRollingReplacement() {
        openInspectorSection("Runtime metrics");
        page.locator(".metric-systems").waitFor();
        final String previousPid = applicationPid();
        page.evaluate("""
                pid => {
                  const request = window.fetch.bind(window);
                  const stale = {
                    application_pid: Number(pid),
                    application: {metrics: {executions: 987654}},
                    flows: [],
                    steps: [],
                    process: {}
                  };
                  window.__staleMetricServed = false;
                  window.fetch = (input, options = {}) => {
                    const url = typeof input === 'string' ? input : input.url;
                    if (!window.__staleMetricServed && url.startsWith('/api/metrics')
                        && Number(state.application.pid) !== Number(pid)) {
                      window.__staleMetricServed = true;
                      return Promise.resolve(new Response(JSON.stringify(stale), {
                        status: 200,
                        headers: {'Content-Type': 'application/json'}
                      }));
                    }
                    return request(input, options);
                  };
                }
                """, previousPid);

        page.locator("#project-id").fill("metrics-replacement");
        page.locator("#project-id").press("Tab");
        waitForText("#build-state", "Built");
        page.waitForFunction("pid => Number(state.application.pid) !== Number(pid)", previousPid);
        final String currentPid = applicationPid();
        page.waitForFunction("() => window.__staleMetricServed === true");
        final boolean staleNeverRendered = (Boolean) page.evaluate("""
                pid => new Promise(resolve => {
                  let clean = true;
                  const observer = setInterval(() => {
                    clean = clean && state.metrics?.application?.metrics?.executions !== 987654;
                  }, 1);
                  setTimeout(() => {
                    clearInterval(observer);
                    resolve(clean && Number(state.application.pid) === Number(pid));
                  }, 100);
                })
                """, currentPid);

        assertThat(staleNeverRendered).isTrue();
        page.waitForFunction("pid => Number(state.metrics?.application_pid) === Number(pid)", currentPid);
    }

    @Test
    void exampleProjectionFromThePreviousApplicationCannotRenderAfterRollingReplacement() {
        addTrigger();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();
        final String previousPid = applicationPid();
        page.evaluate("""
                pid => {
                  const request = window.fetch.bind(window);
                  const stale = {
                    application_pid: Number(pid),
                    initial_context: {},
                    nodes: [1],
                    result: {status: 'succeeded', context: {result: 'STALE_APPLICATION_RESULT'}}
                  };
                  window.__staleExampleServed = false;
                  window.fetch = (input, options = {}) => {
                    const url = typeof input === 'string' ? input : input.url;
                    if (url.endsWith('/view') && Number(state.application.pid) !== Number(pid)) {
                      window.__staleExampleServed = true;
                      return Promise.resolve(new Response(JSON.stringify(stale), {
                        status: 200,
                        headers: {'Content-Type': 'application/json'}
                      }));
                    }
                    return request(input, options);
                  };
                }
                """, previousPid);

        selectWorldNode("app");
        page.locator("#project-id").fill("example-replacement");
        page.locator("#project-id").press("Tab");
        waitForText("#build-state", "Built");
        page.waitForFunction("pid => Number(state.application.pid) !== Number(pid)", previousPid);
        selectTrigger();
        page.waitForFunction("() => window.__staleExampleServed === true");
        page.waitForTimeout(100);

        assertThat(page.evaluate("""
                () => state.traceSummary === null
                  && !state.runResult.includes('STALE_APPLICATION_RESULT')
                """)).isEqualTo(true);
    }

    @Test
    void enterSelectsTheApplicationNode() {
        addTrigger();
        waitForText("#build-state", "Built");
        page.evaluate("() => state.world.fit()");
        awaitScene();

        page.locator(".app-node").press("Enter");
        waitForText(".inspector-heading h2", "Application");

        assertThat(page.locator(".inspector-heading h2").textContent()).isEqualTo("Application");
    }

    @Test
    void spaceSelectsATriggerNode() {
        addTrigger();
        final String flowName = page.locator(".inspector-heading h2").textContent();
        selectWorldNode("app");
        page.evaluate("() => state.world.fit()");
        awaitScene();

        page.locator(".trigger-node").press("Space");
        waitForText(".inspector-heading h2", flowName);

        assertThat(page.locator(".inspector-heading h2").textContent()).isEqualTo(flowName);
    }

    @Test
    void rollingBuildCompletionPreservesFocusedGraphNode() {
        addTrigger();
        waitForText("#build-state", "Built");
        delayNextProjectWrite();
        selectWorldNode("app");
        page.evaluate("() => state.world.fit()");
        awaitScene();
        page.locator("#project-id").fill("focused-node-rebuild");
        page.locator("#project-id").press("Tab");
        page.locator(".trigger-node").focus();
        page.waitForFunction("() => window.__railixProjectWriteStarted === true");

        page.evaluate("() => window.__railixReleaseProjectWrite()");
        waitForText("#build-state", "Built");

        assertThat(page.locator(".trigger-node").evaluate("node => node === document.activeElement"))
                .isEqualTo(true);
    }

    @Test
    void queuedRollingBuildSkipsASupersededProjectSnapshot() {
        openInspectorTab("inspect");
        delayFirstProjectWriteAndRecordIds();
        page.locator("#project-id").fill("first-snapshot");
        page.locator("#project-id").press("Tab");
        page.waitForFunction("() => window.__railixProjectWriteStarted === true");

        page.locator("#project-id").fill("superseded-snapshot");
        page.locator("#project-id").press("Tab");
        page.waitForTimeout(250);
        page.locator("#project-id").fill("current-snapshot");
        page.locator("#project-id").press("Tab");
        page.waitForTimeout(250);

        assertThat(page.evaluate("""
                () => ({
                  active: state.writeActive,
                  pending: JSON.parse(state.pendingWrite.projectSource).id
                })
                """)).isEqualTo(java.util.Map.of(
                "active", true,
                "pending", "current-snapshot"
        ));

        page.evaluate("window.__railixReleaseProjectWrite()");
        waitForText("#build-state", "Built");

        assertThat(page.locator("body").evaluate("() => window.__railixProjectWrites"))
                .isEqualTo(List.of("first-snapshot", "current-snapshot"));
    }

    @Test
    void queuedEditCanRestoreAValueAfterAnOlderSaveIsAcknowledged() {
        openInspectorTab("inspect");
        final String original = page.locator("#project-id").inputValue();
        delayFirstProjectWriteAndRecordIds();
        page.locator("#project-id").fill("temporary-name");
        page.locator("#project-id").press("Tab");
        page.waitForFunction("() => window.__railixProjectWriteStarted === true");
        page.locator("#project-id").fill(original);
        page.locator("#project-id").press("Tab");
        page.waitForFunction("() => state.pendingWrite !== null");

        page.evaluate("window.__railixReleaseProjectWrite()");
        waitForText("#build-state", "Built");
        page.reload();
        waitForText("#build-state", "Built");

        openInspectorTab("inspect");
        assertThat(page.locator("#project-id").inputValue()).isEqualTo(original);
    }

    @Test
    @Timeout(180)
    void editingOneOfSixThousandStepsTransfersOnlyThatStep() {
        openProject(deepBranchProject(6_000));
        selectWorldNode("step-3000");
        final List<String> transfers = new ArrayList<>();
        page.onRequest(request -> {
            if (request.url().endsWith("/api/project") || request.url().endsWith("/api/creator")) {
                transfers.add(request.method());
            }
        });

        final var response = page.waitForResponse(candidate -> candidate.url().endsWith("/api/project")
                && candidate.request().method().equals("PATCH"),
                new Page.WaitForResponseOptions().setTimeout(120_000),
                () -> page.locator("#node-metrics").uncheck());
        waitForText("#build-state", "Built");

        final var edit = CreatorServerE2eSupport.object(response.request().postData());
        final var changes = (RailixValue.ObjectValue) edit.values().get("changes");
        assertThat(changes.values()).containsOnlyKeys("nodes");
        assertThat(((RailixValue.ObjectValue) changes.values().get("nodes")).values())
                .containsOnlyKeys("step-3000");
        assertThat(CreatorServerE2eSupport.object(response.text()).values()).doesNotContainKeys("project", "creator");
        assertThat(transfers).containsExactly("PATCH");
        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode("step-3000");
        assertThat(page.locator("#node-metrics").isChecked()).isFalse();
    }

    @Test
    void staleEditorPreservesItsDraftWithoutOverwritingTheOtherEditor() {
        openInspectorTab("inspect");
        page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  project.id = 'other-editor';
                  const response = await fetch('/api/project', {
                    method: 'POST', headers: mutationHeaders(), body: JSON.stringify(project)
                  });
                  if (!response.ok) throw new Error(await response.text());
                }
                """);
        page.locator("#project-id").fill("my-unsaved-draft");
        page.locator("#project-id").press("Tab");
        waitForText("#build-state", "Not built");

        assertThat(page.locator("#inspector").textContent()).contains("changed in another editor", "Reload");
        assertThat(page.locator("#project-id").inputValue()).isEqualTo("my-unsaved-draft");
        assertThat(page.evaluate("async () => (await (await fetch('/api/project')).json()).project.id"))
                .isEqualTo("other-editor");
    }

    @Test
    void metadataSaveDoesNotDiscardARejectedFunctionalDraft() {
        openInspectorTab("inspect");
        final String original = page.locator("#project-id").inputValue();
        page.locator("#project-id").fill("Invalid Name");
        page.locator("#project-id").press("Tab");
        waitForText("#build-state", "Not built");

        page.evaluate("""
                () => {
                  state.creator.steps.app = {name: 'My application'};
                  creatorDirty();
                }
                """);
        page.waitForFunction("() => state.savedCreator.steps.app?.name === 'My application'");

        assertThat(page.locator("#project-id").inputValue()).isEqualTo("Invalid Name");
        assertThat(page.evaluate("async () => (await (await fetch('/api/project')).json()).project.id"))
                .isEqualTo(original);
        assertThat(page.locator("#build-state").textContent()).isEqualTo("Not built");
        assertThat(page.locator("#inspector").textContent()).contains("PROJECT_ID_INVALID");
    }

    @Test
    void functionalSavePreservesTheWarningAndSourceOfInvalidPresentationMetadata() throws Exception {
        creator.close();
        final Path metadata = directory.resolve("railix.creator.json");
        Files.writeString(metadata, "{");
        creator = CreatorServer.start(0, directory.resolve("project.json"), directory.resolve("railix-home"));
        page.navigate(creator.baseUri().toString());
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        assertThat(page.locator("#inspector").textContent()).contains("CREATOR_JSON_INVALID");

        page.locator("#project-id").fill("functional-edit");
        page.locator("#project-id").press("Tab");
        waitForText("#build-state", "Built");

        assertThat(page.locator("#inspector").textContent()).contains("CREATOR_JSON_INVALID");
        assertThat(Files.readString(metadata)).isEqualTo("{");
    }

    @Test
    void metadataFailureDoesNotForgetAnAcceptedFunctionalSave() {
        openInspectorTab("inspect");
        page.evaluate("""
                () => {
                  state.creator.steps.unknown = {name: 'Invalid reference'};
                  state.project.id = 'accepted-project';
                  dirty();
                }
                """);
        page.waitForFunction("() => !state.writeActive && state.build === 'Not saved'");

        page.evaluate("""
                () => {
                  delete state.creator.steps.unknown;
                  state.creator.steps.app = {name: 'Recovered metadata'};
                  creatorDirty();
                }
                """);
        waitForText("#build-state", "Built");
        page.locator("#project-id").fill("next-project");
        page.locator("#project-id").press("Tab");
        waitForText("#build-state", "Built");
        page.reload();
        waitForText("#build-state", "Built");

        openInspectorTab("inspect");
        assertThat(page.locator("#project-id").inputValue()).isEqualTo("next-project");
        assertThat(page.evaluate("() => state.creator.steps.app.name")).isEqualTo("Recovered metadata");
    }

    @Test
    void insertingBeforeAnExistingStepKeepsItsRealExampleProjection() {
        openProject(fourStepProject());
        selectTrigger();
        addManipulationAfterSelected();
        waitForText("#build-state", "Built");
        selectWorldNode("one");

        page.waitForFunction("() => state.traceStep !== null && state.traceController === null");
        assertThat(page.evaluate("() => state.traceStep.id")).isEqualTo("one");
        assertThat(page.locator("#preview-source").textContent()).contains("1");
        assertThat(page.evaluate("""
                async () => {
                  const nodes = (await (await fetch('/api/project')).json()).project.nodes;
                  return state.builtProject.nodes.every(node => nodes[Number(state.editor.nodes[node.id].index)].id === node.id);
                }
                """)).isEqualTo(true);
    }

    @Test
    void metadataDebounceCannotDropAnUnsentFunctionalEdit() {
        openInspectorTab("inspect");
        page.evaluate("""
                () => {
                  const original = window.fetch.bind(window);
                  let delayed = false;
                  window.fetch = async (input, options = {}) => {
                    const response = await original(input, options);
                    if (!delayed && input === '/api/project' && options.method === 'PATCH') {
                      delayed = true;
                      window.__acceptedProjectWrite = true;
                      await new Promise(resolve => { window.__releaseAcceptedWrite = resolve; });
                    }
                    return response;
                  };
                }
                """);
        page.locator("#project-id").fill("first-save");
        page.locator("#project-id").press("Tab");
        page.waitForFunction("() => window.__acceptedProjectWrite === true");
        page.locator("#project-id").fill("queued-save");
        page.locator("#project-id").press("Tab");
        page.waitForFunction("() => state.pendingWrite !== null");

        page.evaluate("""
                () => {
                  state.creator.steps.app = {name: 'Later appearance'};
                  creatorDirty();
                  window.__releaseAcceptedWrite();
                }
                """);
        page.waitForFunction("() => state.savedCreator.steps.app?.name === 'Later appearance' && !state.writeActive");

        assertThat(page.evaluate("async () => (await (await fetch('/api/project')).json()).project.id"))
                .isEqualTo("queued-save");
        assertThat(page.locator("#build-state").textContent()).isEqualTo("Running");
    }

    @Test
    void rejectedMetadataDoesNotHideAnAcceptedTriggerFromTheCanvas() {
        page.evaluate("""
                async () => {
                  const response = await fetch('/api/creator', {
                    method: 'PATCH', headers: mutationHeaders(),
                    body: JSON.stringify({revision: state.creatorVersion,
                      changes: {steps: {app: {name: 'Other editor'}}}})
                  });
                  if (!response.ok) throw new Error(await response.text());
                }
                """);

        addTrigger();
        page.waitForFunction("() => !state.writeActive && state.build === 'Not saved'");

        page.locator(".trigger-node").waitFor();
        selectWorldNode("app");
        assertThat(page.locator("#inspector").textContent()).contains("changed in another editor");
        assertThat(page.evaluate("async () => (await (await fetch('/api/project')).json()).project.nodes.length"))
                .isEqualTo(2);
    }

    @Test
    void enterSelectsTheFieldManipulationNode() {
        createResultJourney();
        selectTrigger();
        page.evaluate("() => state.world.fit()");
        awaitScene();

        page.locator(".step-node").press("Enter");
        waitForText(".inspector-heading h2", "Field Manipulation");

        assertThat(page.locator(".inspector-heading h2").textContent())
                .isEqualTo("Field Manipulation");
    }

    @Test
    void selectedNodeIsVisuallyExclusive() {
        addTrigger();
        waitForText("#build-state", "Built");
        page.evaluate("() => state.world.fit()");
        awaitScene();

        assertThat(page.locator(".trigger-node").getAttribute("class")).contains("selected");
        assertThat(page.locator(".trigger-node").getAttribute("aria-pressed")).isEqualTo("true");
        assertThat(page.locator(".app-node").getAttribute("class")).doesNotContain("selected");

        selectWorldNode("app");
        page.evaluate("() => state.world.fit()");
        awaitScene();

        assertThat(page.locator(".app-node").getAttribute("class")).contains("selected");
        assertThat(page.locator(".app-node").getAttribute("aria-pressed")).isEqualTo("true");
        assertThat(page.locator(".trigger-node").getAttribute("class")).doesNotContain("selected");
    }

}

final class RailixCreatorRoutingBrowserIT extends RailixCreatorBrowserSupport {
    @Test
    void addingAFilterPersistsEveryDeclaredOutcomeConnection() {
        addFilterAfterTrigger();

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const filter = project.nodes.find(node => node.use === 'railix.filter');
                  return project.links.filter(link => link.from.startsWith(filter.id + '.'))
                    .map(link => link.from.substring(filter.id.length) + ':' + link.to)
                    .sort().join('|');
                }
                """)).isEqualTo(".match:end|.otherwise:end");
    }

    @Test
    void filterRendersOneDeterministicLanePerOutcome() {
        addFilterAfterTrigger();

        assertThat(branchOutcomes()).containsExactly("match", "otherwise");
    }

    @Test
    void filterInspectorAddsANormalStepToTheChosenOutcome() {
        addFilterAfterTrigger();

        clickOverview("[data-add-outcome='otherwise']");
        page.locator("#step-search").fill("field");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const filter = project.nodes.find(node => node.use === 'railix.filter');
                  const branch = project.links.find(link => link.from === filter.id + '.otherwise');
                  const inserted = project.nodes.find(node => node.id === branch.to);
                  const terminal = project.links.find(link => link.from === inserted.id + '.next');
                  return inserted.use + ':' + terminal.to;
                }
                """)).isEqualTo("railix.field-manipulation:end");
    }

    @Test
    void branchLayoutIsStableAcrossReload() {
        addFilterAfterTrigger();
        page.evaluate("() => state.world.fit()");
        final String before = positions();

        page.reload();
        waitForText("#build-state", "Built");
        page.evaluate("() => state.world.fit()");

        assertThat(positions()).isEqualTo(before);
    }

    @Test
    void nestedFilterLayoutIsStableAcrossReload() {
        addNestedFilterToMatchRoute();
        page.evaluate("() => state.world.fit()");
        final String before = positions();

        page.reload();
        waitForText("#build-state", "Built");
        page.evaluate("() => state.world.fit()");

        assertThat(branchOutcomes()).hasSize(4);
        assertThat(positions()).isEqualTo(before);
    }

    @Test
    void nestedFiltersKeepEveryDeclaredOutcomeInTheScene() {
        addNestedFilterToMatchRoute();

        assertThat(branchOutcomes()).containsExactlyInAnyOrder("match", "match", "otherwise", "otherwise");
    }

    @Test
    void semanticZoomReplacesAnAggregateWithItsRealChildren() throws Exception {
        openProject(deepBranchProject(96));
        final String project = Files.readString(directory.resolve("project.json"));
        final String metadata = creatorMetadata();

        assertThat(page.locator("#world-plane").count()).isEqualTo(1);
        page.waitForFunction("() => state.world?.scene?.nodes.some(node => node.kind === 'region' && !node.expanded)");
        final Object region = page.evaluate("""
                () => state.world.scene.nodes.find(node => node.kind === 'region' && !node.expanded)
                """);
        final Path screenshots = Files.createDirectories(Path.of("target", "screenshots"));
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("semantic-zoom-overview.png")));
        page.evaluate("region => void state.world.focus(region.id)", region);
        page.waitForFunction("""
                region => !state.world.scene.nodes.some(node => node.id === region.id && !node.expanded)
                  && state.world.scene.nodes.some(node => node.id !== region.id && node.count < region.count
                    && node.x >= region.x && node.y >= region.y
                    && node.x + node.width <= region.x + region.width
                    && node.y + node.height <= region.y + region.height)
                """, region);

        assertThat(page.locator("#world-labels > *").count()).isGreaterThan(0);
        assertThat(page.locator("#world-labels > *").count()).isLessThanOrEqualTo(256);
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(project);
        assertThat(creatorMetadata()).isEqualTo(metadata);
        assertThat(pageErrors).isEmpty();
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("semantic-zoom-region.png")));
        selectWorldNode("step-48");
        assertThat(page.locator("[data-node-id='step-48']").getAttribute("aria-pressed")).isEqualTo("true");
        page.screenshot(new Page.ScreenshotOptions().setPath(screenshots.resolve("semantic-zoom-detail.png")));
    }

    @Test
    void missingSceneFocusDoesNotTrapLaterNavigation() {
        page.evaluate("() => void state.world.focus('missing-step')");
        page.locator("#world-error").waitFor();

        page.locator("#zoom-in").click();

        page.waitForFunction("() => document.querySelector('#world-error').hidden");
        assertThat(page.locator(".app-node").count()).isEqualTo(1);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void explicitZoomSupersedesAnUnfinishedFocusRequest() {
        openProject(deepBranchProject(96));
        final String expected = (String) page.evaluate("""
                () => {
                  state.world.focus('step-48');
                  state.world.zoom(1.1);
                  return document.querySelector('#graph').dataset.sceneScale;
                }
                """);
        page.waitForResponse(response -> response.url().contains("/api/scene?")
                && !response.url().contains("focus="), () -> page.locator("#graph").press("ArrowRight"));

        assertThat(page.locator("#graph").getAttribute("data-scene-scale")).isEqualTo(expected);
        assertThat(page.locator("#world-error").isVisible()).isFalse();
    }

    @Test
    void cssWorldRemainsEditableWithoutCanvasSupport() {
        page.addInitScript("HTMLCanvasElement.prototype.getContext = () => { throw new Error('Canvas unavailable'); }");
        page.reload();
        awaitScene();
        selectWorldNode("app");
        assertThat(page.locator("#project-id").isVisible()).isTrue();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void disposingTheWorldReleasesItsSceneAndSurfaces() {
        page.evaluate("() => void state.world.dispose()");

        assertThat(page.locator("#world-labels > *").count()).isZero();
        assertThat(page.evaluate("() => state.world.scene === null")).isEqualTo(true);
        assertThat(page.locator("#world-plane > *").count()).isZero();
        page.locator("#zoom-in").click();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void deletingAFocusedStepRevealsItsSurvivingPredecessor() {
        openProject(fourStepProject());
        selectWorldNode("two");

        clickOverview("#delete-step");
        waitForText("#build-state", "Built");

        page.locator("[data-node-id='one'][aria-pressed='true']").waitFor();
        assertThat(stepIds()).containsExactly("one", "three", "four");
    }

    @Test
    void deletingAFocusedTriggerRevealsTheApplication() {
        openProject(fourStepProject());
        selectWorldNode("command");

        clickOverview("#delete-step");
        waitForText("#build-state", "Built");

        page.locator("[data-node-id='app'][aria-pressed='true']").waitFor();
        assertThat(stepIds()).isEmpty();
    }

    @Test
    void largeCorridorKeepsBothBranchOutcomeLabelsReadable() {
        openProject(deepBranchProject(96));
        awaitScene();

        assertThat(page.locator(".world-link-label strong").allTextContents())
                .containsExactlyInAnyOrder("Match", "Otherwise");
    }

    @Test
    void largeCorridorKeepsItsBranchAndTerminalStationsReadable() {
        openProject(deepBranchProject(96));
        awaitScene();

        assertThat(page.locator("[data-node-id='filter']").isVisible()).isTrue();
        assertThat(page.locator(".end-node").count()).isEqualTo(2);
        assertThat(page.evaluate("""
                () => state.world.scene.nodes.filter(node => node.id === 'filter' || node.kind === 'end')
                  .every(node => node.width * Number(document.querySelector('#graph').dataset.sceneScale) >= 60)
                """)).isEqualTo(true);
    }

    @Test
    void branchRenderingHandlesSixThousandLinearStepsWithoutCallStackGrowth() {
        openProject(deepBranchProject(6_000));

        page.evaluate("() => state.world.fit()");
        awaitScene();
        assertThat(page.locator("#world-labels > *").count()).isLessThanOrEqualTo(256);
        assertThat(page.locator(".step-node").count()).isLessThan(6_001);
        assertThat(page.evaluate("""
                () => {
                  const scene = state.world.scene;
                  const stage = document.querySelector('#graph');
                  return scene.nodes.length <= 2048
                    && scene.links.reduce((sum, link) => sum + link.points.length - 1, 0) <= 4096
                    && Number(stage.dataset.sceneNodeCount) === scene.nodes.length
                    && Number(stage.dataset.sceneLinkCount) === scene.links.length
                    && scene.nodes.some(node => node.kind === 'region' && node.count > 1);
                }
                """)).isEqualTo(true);
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void nestedFilterAddsANormalStepOnlyToItsSelectedRoute() {
        addNestedFilterToMatchRoute();

        clickOverview("[data-add-outcome='otherwise']");
        page.locator("#step-search").fill("field");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const filters = project.nodes.filter(node => node.use === 'railix.filter');
                  const outer = filters.find(filter => project.links.some(link =>
                    link.from === filter.id + '.match' && filters.some(inner => inner.id === link.to)));
                  const inner = filters.find(filter => filter.id !== outer.id);
                  const inserted = project.links.find(link => link.from === inner.id + '.otherwise')?.to;
                  return project.nodes.find(node => node.id === inserted)?.use + '|'
                    + project.links.find(link => link.from === inner.id + '.match')?.to + '|'
                    + project.links.find(link => link.from === outer.id + '.otherwise')?.to;
                }
                """)).isEqualTo("railix.field-manipulation|end|end");
    }

    @Test
    void deletingANestedBranchLeafRestoresOnlyTheNestedOutcome() {
        addNestedFilterToMatchRoute();
        clickOverview("[data-add-outcome='otherwise']");
        page.locator("#step-search").fill("field");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");

        clickOverview("#delete-step");
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const filters = project.nodes.filter(node => node.use === 'railix.filter');
                  const outer = filters.find(filter => project.links.some(link =>
                    link.from === filter.id + '.match' && filters.some(inner => inner.id === link.to)));
                  const inner = filters.find(filter => filter.id !== outer.id);
                  return project.links.find(link => link.from === inner.id + '.otherwise')?.to + '|'
                    + project.links.find(link => link.from === outer.id + '.match')?.to;
                }
                """)).asString().startsWith("end|step-");
    }

    @Test
    void rollingBuiltApplicationExecutesTheMatchingFilterExample() {
        openProject(filterProject());
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(runResult(0)).isEqualTo(RailixValue.string("matched"));
    }

    @Test
    void rollingBuiltApplicationExecutesTheOtherwiseFilterExample() {
        openProject(filterProject());
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(runResult(1)).isEqualTo(RailixValue.string("otherwise"));
    }

    @Test
    void addingAChoicePersistsEveryDeclaredOutcomeConnection() {
        addChoiceAfterTrigger();

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const choice = project.nodes.find(node => node.use === 'railix.choice');
                  return project.links.filter(link => link.from.startsWith(choice.id + '.'))
                    .map(link => link.from.substring(choice.id.length) + ':' + link.to)
                    .sort().join('|');
                }
                """)).isEqualTo(".match:end|.otherwise:end");
    }

    @Test
    void addingASwitchPersistsFlatCatalogDerivedRoutes() {
        addSwitchAfterTrigger();
        page.locator("[data-add-candidate='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-add-candidate='literal']").click();
        waitForText("#build-state", "Built");
        page.evaluate("() => state.world.fit()");
        awaitScene();

        assertThat(page.evaluate("""
                async () => {
                  const workspace = await (await fetch('/api/project')).json();
                  const project = workspace.project;
                  const step = project.nodes.find(node => node.use === 'railix.switch');
                  const cases = step.inputs.cases;
                  const routes = project.links.filter(link => link.from.startsWith(step.id + '.'));
                  const labels = workspace.creator.steps[step.id].outcomes;
                  return cases.length + '|' + cases.map(item => labels[item.outcome]).join(',') + '|'
                    + cases.every(item => !Object.hasOwn(item, 'label')) + '|'
                    + cases.every(item => /^case-[0-9a-f-]{36}$/.test(item.outcome)) + '|'
                    + routes.length + '|' + routes.every(link => link.to === 'end');
                }
                """)).isEqualTo("2|Case 1,Case 2|true|true|3|true");
        assertThat(branchOutcomes()).hasSize(3).contains("otherwise");
        assertThat(page.locator("#world-labels").textContent()).contains("Case 1", "Case 2", "Otherwise");
    }

    @Test
    void renamingAndReorderingSwitchCasesPreservesOutcomeIdsAcrossReload() {
        addSwitchAfterTrigger();
        page.locator("[data-add-candidate='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-add-candidate='literal']").click();
        waitForText("#build-state", "Built");
        final String before = String.valueOf(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  return project.nodes.find(node => node.use === 'railix.switch').inputs.cases
                    .map(item => item.outcome).join('|');
                }
                """));

        page.locator("[data-candidate-label]").first().fill("Alpha");
        page.locator("[data-candidate-label]").first().press("Tab");
        waitForText("#build-state", "Built");
        page.locator("[data-move-candidate='1'][data-direction='-1']").click();
        waitForText("#build-state", "Built");
        page.reload();
        waitForText("#build-state", "Built");
        final String id = String.valueOf(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.use === 'railix.switch').id
                """));
        selectWorldNode(id);

        assertThat(page.evaluate("""
                async () => {
                  const workspace = await (await fetch('/api/project')).json();
                  const step = workspace.project.nodes.find(node => node.use === 'railix.switch');
                  const labels = workspace.creator.steps[step.id].outcomes;
                  return step.inputs.cases.map(item => item.outcome + ':' + labels[item.outcome]).join('|');
                }
                """)).isEqualTo(before.split("\\|")[1] + ":Case 2|" + before.split("\\|")[0] + ":Alpha");
    }

    @Test
    void changingSwitchCaseSourcePreservesItsOutcomeRoute() {
        addSwitchAfterTrigger();
        page.locator("[data-add-candidate='field']").click();
        waitForText("#build-state", "Built");
        final String outcome = String.valueOf(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  return project.nodes.find(node => node.use === 'railix.switch').inputs.cases[0].outcome;
                }
                """));

        page.locator("[data-candidate-option]").selectOption("literal");
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async outcome => {
                  const workspace = await (await fetch('/api/project')).json();
                  const step = workspace.project.nodes.find(node => node.use === 'railix.switch');
                  const candidate = step.inputs.cases[0];
                  const route = workspace.project.links.find(link => link.from === step.id + '.' + outcome);
                  return [candidate.outcome, candidate.option, route?.to,
                    workspace.creator.steps[step.id].outcomes[outcome]].join('|');
                }
                """, outcome)).isEqualTo(outcome + "|literal|end|Case 1");
    }

    @Test
    void removingATerminalSwitchCaseRemovesOnlyItsRoute() {
        addSwitchAfterTrigger();
        page.locator("[data-add-candidate='field']").click();
        waitForText("#build-state", "Built");

        page.locator("[data-remove-candidate='0']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const workspace = await (await fetch('/api/project')).json();
                  const project = workspace.project;
                  const step = project.nodes.find(node => node.use === 'railix.switch');
                  return step.inputs.cases.length + ':' + project.links
                    .filter(link => link.from.startsWith(step.id + '.'))
                    .map(link => link.from.substring(step.id.length + 1)).join('|') + ':'
                    + Object.keys(workspace.creator.steps[step.id]?.outcomes || {}).length;
                }
                """)).isEqualTo("0:otherwise:0");
    }

    @Test
    void connectedSwitchCaseCannotBeRemoved() {
        addSwitchAfterTrigger();
        page.locator("[data-add-candidate='field']").click();
        waitForText("#build-state", "Built");
        final Map<?, ?> route = (Map<?, ?>) page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const step = project.nodes.find(node => node.use === 'railix.switch');
                  return {id: step.id, outcome: step.inputs.cases[0].outcome};
                }
                """);

        clickOverview("[data-add-outcome='" + route.get("outcome") + "']");
        page.locator("#step-search").fill("field manipulation");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");
        selectWorldNode((String) route.get("id"));

        assertThat(page.locator("[data-remove-candidate='0']").isDisabled()).isTrue();
        assertThat(page.evaluate("""
                async route => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const link = project.links.find(item => item.from === route.id + '.' + route.outcome);
                  return project.nodes.find(node => node.id === link?.to)?.use;
                }
                """, route)).isEqualTo("railix.field-manipulation");
    }

    @Test
    void authoredRouteStepsAreNotOfferedInsideNestedPrograms() {
        preparePrimitiveSearch("{\"payload\":{\"value\":\"Railix\"}}", "switch");

        assertThat(page.locator("#steps-options [data-add-nested='railix.switch']").count()).isZero();
        assertThat(page.locator("#steps-options").textContent()).contains("No compatible Step matches.");
    }

    @Test
    void authoredRouteStepsAreNotOfferedAsConditionPredicates() throws Exception {
        creator.close();
        GeneratedApplicationFixture.installedCatalog(
                directory,
                List.of(authoredBooleanStep()),
                MatcherConformanceSteps.AuthoredTrue.class
        );
        creator = CreatorServer.start(0, directory.resolve("project.json"), directory.resolve("railix-home"));
        page.navigate(creator.baseUri().toString());
        waitForText("#build-state", "Built");
        prepareSizeChoiceMatcher(2);

        final Locator search = page.locator("[data-matcher-group='0'] [data-predicate-query]");
        search.fill("authored routes");

        assertThat(page.locator("[data-add-predicate='test.authored-boolean']").count()).isZero();
        assertThat(page.locator("[data-matcher-group='0'] [data-predicate-options]").textContent())
                .contains("No compatible matcher found.");
    }

    @Test
    void choiceAddsACompleteOrGroupAndPersistsItAcrossReload() {
        addChoiceAfterTrigger();

        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.reload();
        waitForText("#build-state", "Built");
        final String choice = String.valueOf(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.use === 'railix.choice').id
                """));
        selectWorldNode(choice);

        assertThat(page.locator("[data-matcher-group='0']").count()).isEqualTo(1);
        assertThat(page.locator("[data-matcher-group='0'] [data-candidate-index='0'] select").inputValue())
                .isEqualTo("field");
        assertThat(page.locator("[data-matcher-group='0'] [data-remove-candidate='0']").isDisabled())
                .isTrue();
        assertThat(page.locator("[data-matcher-group='0'] [data-add-candidate='field']").count())
                .isEqualTo(1);
    }

    @Test
    void choiceAddsAnAndMatcherToAnExistingGroup() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");

        page.locator("[data-matcher-group='0'] [data-add-candidate='field']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  return project.nodes.find(node => node.use === 'railix.choice').inputs.conditions[0].length;
                }
                """)).isEqualTo(2);
    }

    @Test
    void choiceReordersAndMatchersInsideTheirGroup() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-matcher-group='0'] [data-add-candidate='literal']").click();
        final Locator literal = page.locator("[data-matcher-group='0'] [data-input-json]");
        literal.fill("\"constant\"");
        literal.press("Tab");
        waitForText("#build-state", "Built");

        page.locator("[data-matcher-group='0'] [data-move-candidate='1'][data-direction='-1']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const first = project.nodes.find(node => node.use === 'railix.choice').inputs.conditions[0][0];
                  return first.option + ':' + first.inputs.value;
                }
                """)).isEqualTo("literal:constant");
    }

    @Test
    void reorderingChoiceMatchersClearsPositionBoundStepSearch() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-matcher-group='0'] [data-add-candidate='field']").click();
        waitForText("#build-state", "Built");
        fillStepSearch("[data-matcher-group='0'] [data-candidate-index='0'] [data-step-query]", "equals");

        page.locator("[data-matcher-group='0'] [data-move-candidate='0'][data-direction='1']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-matcher-group='0'] [data-candidate-index='0'] [data-step-query]")
                .inputValue()).isEmpty();
        assertThat(page.locator("[data-matcher-group='0'] [data-candidate-index='1'] [data-step-query]")
                .inputValue()).isEmpty();
    }

    @Test
    void choiceRemovesOneAndMatcherWithoutRemovingItsGroup() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-matcher-group='0'] [data-add-candidate='field']").click();
        waitForText("#build-state", "Built");

        page.locator("[data-matcher-group='0'] [data-remove-candidate='1']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const conditions = project.nodes.find(node => node.use === 'railix.choice').inputs.conditions;
                  return conditions.length + ':' + conditions[0].length;
                }
                """)).isEqualTo("1:1");
    }

    @Test
    void replacingARemovedChoiceMatcherDoesNotRestoreItsStepSearch() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-matcher-group='0'] [data-add-candidate='field']").click();
        waitForText("#build-state", "Built");
        fillStepSearch("[data-matcher-group='0'] [data-candidate-index='1'] [data-step-query]", "equals");

        page.locator("[data-matcher-group='0'] [data-remove-candidate='1']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-matcher-group='0'] [data-add-candidate='field']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-matcher-group='0'] [data-candidate-index='1'] [data-step-query]")
                .inputValue()).isEmpty();
    }

    @Test
    void choiceReordersWholeOrGroupsWithoutChangingTheirMatchers() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-add-matcher-group='literal']").click();
        final Locator literal = page.locator("[data-matcher-group='1'] [data-input-json]");
        literal.fill("\"constant\"");
        literal.press("Tab");
        waitForText("#build-state", "Built");

        page.locator("[data-move-matcher-group='1'][data-direction='-1']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const first = project.nodes.find(node => node.use === 'railix.choice').inputs.conditions[0][0];
                  return first.option + ':' + first.inputs.value;
                }
                """)).isEqualTo("literal:constant");
    }

    @Test
    void reorderingChoiceGroupsClearsPositionBoundStepSearch() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        fillStepSearch("[data-matcher-group='0'] [data-step-query]", "equals");

        page.locator("[data-move-matcher-group='0'][data-direction='1']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-matcher-group='0'] [data-step-query]").inputValue()).isEmpty();
        assertThat(page.locator("[data-matcher-group='1'] [data-step-query]").inputValue()).isEmpty();
    }

    @Test
    void choiceRemovesOneWholeOrGroup() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");

        page.locator("[data-remove-matcher-group='0']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  return project.nodes.find(node => node.use === 'railix.choice').inputs.conditions.length;
                }
                """)).isEqualTo(1);
    }

    @Test
    void replacingARemovedChoiceGroupDoesNotRestoreItsStepSearch() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");
        fillStepSearch("[data-matcher-group='1'] [data-step-query]", "equals");

        page.locator("[data-remove-matcher-group='1']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-matcher-group='1'] [data-step-query]").inputValue()).isEmpty();
    }

    @Test
    void choiceDoesNotAllowAnEmptyAndGroup() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-matcher-group='0'] [data-remove-candidate='0']").isDisabled())
                .isTrue();
    }

    @Test
    void choiceMatcherOffersCompatibleOrdinaryBooleanSteps() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");

        final Locator search = page.locator("[data-matcher-group='0'] [data-predicate-query]");
        search.fill("equals");

        assertThat(page.locator("[data-matcher-group='0'] [data-add-predicate='value.equals']").count())
                .isEqualTo(1);
    }

    @Test
    void choiceProgramDistinguishesMatchersFromTransformsByContract() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-matcher-group='0'] .condition-predicates"
                + " [data-add-predicate='value.equals']").count()).isEqualTo(1);
        assertThat(page.locator("[data-matcher-group='0'] .condition-transforms"
                + " [data-add-nested='value.to-json']").count()).isEqualTo(1);
        assertThat(page.locator("[data-matcher-group='0'] .condition-transforms"
                + " [data-add-nested='value.equals']").count()).isZero();
    }

    @Test
    void choiceProgramMakesItsStepSearchPurposeExplicit() {
        addChoiceAfterTrigger();
        page.locator("[data-add-matcher-group='field']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-matcher-group='0'] .condition-transforms").textContent())
                .contains("Calculate value", "Add transform");
        assertThat(page.locator("[data-matcher-group='0'] .condition-predicates").textContent())
                .contains("No matcher configured.", "Add comparison (AND)");
        assertThat(page.locator("[data-matcher-group='0'] [data-predicate-query]")
                .getAttribute("placeholder")).isEqualTo("Search matchers");
    }

    @Test
    void greaterOrEqualAliasIsDiscoverableAfterANumericTransform() {
        prepareSizeChoiceMatcher();

        final Locator search = page.locator("[data-matcher-group='0'] [data-predicate-query]");
        search.fill("gte");

        assertThat(page.locator("[data-matcher-group='0']"
                + " [data-add-predicate='number.greater-or-equal']").count())
                .isEqualTo(1);
    }

    @Test
    void newGreaterOrEqualMatcherHasAnImmediatelyBuildableDefault() {
        prepareSizeChoiceMatcher();
        final Locator search = page.locator("[data-matcher-group='0'] [data-predicate-query]");
        search.fill("gte");
        page.locator("[data-matcher-group='0'] [data-add-predicate='number.greater-or-equal']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.use === 'railix.choice').inputs.conditions[0][0]
                  .when.all[0][0].inputs.than
                """)).isEqualTo(0);
    }

    @Test
    void numericMatcherRemainsAvailableAfterAnotherNumericMatcher() {
        prepareSizeChoiceMatcher();
        final Locator search = page.locator("[data-matcher-group='0'] [data-predicate-query]");
        search.fill("gt");
        page.locator("[data-matcher-group='0'] [data-add-predicate='number.greater-than']").click();
        waitForText("#build-state", "Built");

        page.locator(".condition-add summary").first().click();
        search.fill("lt");

        assertThat(page.locator("[data-matcher-group='0'] [data-add-predicate='number.less-than']").count())
                .isEqualTo(1);
    }

    @Test
    void numericMatchersArePersistedAsIndependentAndLanes() {
        addSizeBounds(1);

        assertThat(page.evaluate("""
                async () => {
                  const when = (await (await fetch('/api/project')).json()).project.nodes
                    .find(node => node.use === 'railix.choice').inputs.conditions[0][0].when;
                  return JSON.stringify({
                    transforms: when.transforms.map(step => step.use),
                    predicates: when.all.map(program => [program[0].use, program[0].inputs.than])
                  });
                }
                """)).isEqualTo("{\"transforms\":[\"list.size\"],\"predicates\":["
                + "[\"number.greater-than\",1],[\"number.less-than\",5]]}");
    }

    @Test
    void independentMatchersCanBeReordered() {
        addSizeBounds(1);

        page.locator("[data-condition-predicate='1'] [data-move-predicate='1'][data-direction='-1']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => JSON.stringify(
                  (await (await fetch('/api/project')).json()).project.nodes
                    .find(node => node.use === 'railix.choice').inputs.conditions[0][0]
                    .when.all.map(program => program[0].use)
                )
                """)).isEqualTo("[\"number.less-than\",\"number.greater-than\"]");
    }

    @Test
    void oneIndependentMatcherCanBeRemoved() {
        addSizeBounds(1);

        page.locator("[data-condition-predicate='0'] [data-remove-predicate='0']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => JSON.stringify(
                  (await (await fetch('/api/project')).json()).project.nodes
                    .find(node => node.use === 'railix.choice').inputs.conditions[0][0]
                    .when.all.map(program => program[0].use)
                )
                """)).isEqualTo("[\"number.less-than\"]");
    }

    @Test
    void notEqualsAliasAddsABuildableDefaultThatReloadsPreviewsAndExecutes() {
        openProject(choiceProject());
        selectWorldNode("choice");
        page.locator("[data-matcher-group='0'] [data-remove-predicate='0']").click();
        waitForText("#build-state", "Built");

        final Locator search = page.locator("[data-matcher-group='0'] [data-predicate-query]");
        search.fill("neq");
        page.locator("[data-matcher-group='0'] [data-add-predicate='value.not-equals']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => JSON.stringify(
                  (await (await fetch('/api/project')).json()).project.nodes
                    .find(node => node.use === 'railix.choice').inputs.conditions[0][0]
                    .when.all[0][0].inputs
                )
                """)).isEqualTo("{\"expected\":null}");

        page.reload();
        waitForText("#build-state", "Built");
        selectWorldNode("choice");
        page.locator("[data-matcher-group='0'] [data-preview-slot='0']").waitFor();

        assertThat(page.locator("[data-matcher-group='0'] [data-input-json]").inputValue())
                .isEqualTo("null");
        assertThat(page.locator("[data-matcher-group='0'] [data-preview-slot='0']").textContent())
                .isEqualTo("true");

        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(runResult(0)).isEqualTo(RailixValue.string("matched"));
        assertThat(runResult(1)).isEqualTo(RailixValue.string("matched"));
    }

    @Test
    void builtApplicationExecutesAChoiceMatcherPipelineInOrder() {
        openProject(branchProject("choice-pipeline", "choice", "railix.choice", """
                [[{
                  "option":"literal","inputs":{"value":["one","two"]},
                  "when":{"transforms":[
                    {"use":"list.size","inputs":{}}
                  ],"all":[[
                    {"use":"number.greater-or-equal","inputs":{"than":2}}
                  ]]}
                }]]
                """));
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(runResult(0)).isEqualTo(RailixValue.string("matched"));
    }

    @ParameterizedTest(name = "list size {0} routes to {1}")
    @CsvSource({"1,otherwise", "2,matched", "4,matched", "5,otherwise"})
    void builtApplicationRequiresEveryNumericMatcher(final int size, final String expected) {
        openProject(branchProject("choice-bounds-" + size, "choice", "railix.choice", """
                [[{
                  "option":"literal","inputs":{"value":%s},
                  "when":{
                    "transforms":[{"use":"list.size","inputs":{}}],
                    "all":[
                      [{"use":"number.greater-than","inputs":{"than":1}}],
                      [{"use":"number.less-than","inputs":{"than":5}}]
                    ]
                  }
                }]]
                """.formatted(numberList(size))));
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(runResult(0)).isEqualTo(RailixValue.string(expected));
    }

    @ParameterizedTest(name = "UI-authored list size {0} routes to {1}")
    @CsvSource({"1,otherwise", "2,matched", "4,matched", "5,otherwise"})
    void uiAuthoredNumericMatchersExecuteAfterReload(final int size, final String expected) {
        addSizeBounds(size);

        page.reload();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(runResult(0)).isEqualTo(RailixValue.string(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hq", "canvas"})
    void choiceBranchesExposeDistinctVisibleOutcomeLabels(final String variant) throws Exception {
        openProject(choiceProject());
        page.locator("#open-settings").click();
        page.locator("#theme-variant").selectOption(variant);
        page.waitForFunction("variant => state.settings.theme_variant === variant && !state.themeError"
                + " && document.querySelector('#graph').dataset.renderer === (variant === 'canvas' ? 'canvas' : 'css')", variant);
        page.keyboard().press("Escape");
        page.screenshot(new Page.ScreenshotOptions().setPath(Files.createDirectories(Path.of("target", "screenshots"))
                .resolve("choice-label-" + variant + ".png")));
        assertThat(page.locator("#world-labels").textContent()).contains("Match", "Otherwise");
        assertThat(branchOutcomes()).containsExactly("match", "otherwise");
        assertThat(pageErrors).isEmpty();
    }

    @Test
    void choiceRailsStartAtTheChoiceStationWithoutAGap() {
        openProject(choiceProject());

        assertThat(page.evaluate("""
                () => {
                  const choice = state.world.scene.nodes.find(node => node.id === 'choice');
                  const rails = state.world.scene.links.filter(link => link.from === 'choice');
                  return rails.length === 2 && rails.every(link => {
                    const start = link.points[0];
                    const target = state.world.scene.nodes.find(node => node.id === link.to);
                    const side = target.y < choice.y ? 0 : choice.height;
                    return Math.abs(start[0] - (choice.x + choice.width / 2)) < 0.5
                      && Math.abs(start[1] - (choice.y + side)) < 0.5;
                  });
                }
                """)).isEqualTo(true);
    }

    @Test
    void choiceRailsEndAtTheirOwnBranchStationsWithoutOvershooting() {
        openProject(choiceProject());

        assertThat(page.evaluate("""
                () => {
                  const nodes = new Map(state.world.scene.nodes.map(node => [node.id, node]));
                  const rails = state.world.scene.links.filter(link => link.from === 'choice');
                  return rails.length === 2 && new Set(rails.map(link => link.to)).size === 2
                    && rails.every(link => {
                      const target = nodes.get(link.to);
                      const end = link.points.at(-1);
                      return target && Math.abs(end[0] - target.x) < 0.5
                        && Math.abs(end[1] - (target.y + target.height / 2)) < 0.5;
                    });
                }
                """)).isEqualTo(true);
    }

    @Test
    void nestedBranchRailsAreContinuousOrthogonalPaths() {
        addNestedFilterToMatchRoute();
        awaitScene();

        assertThat(page.evaluate("""
                () => {
                  const rails = state.world.scene.links.filter(link => ['match', 'otherwise'].includes(link.outcome));
                  return rails.length === 4 && rails.every(link => link.points.length >= 2
                    && link.points.every((point, index, points) => {
                      if (index === 0) return true;
                      const previous = points[index - 1];
                      return point.every(Number.isFinite)
                        && (point[0] === previous[0] || point[1] === previous[1]);
                    })
                  );
                }
                """)).isEqualTo(true);
    }

    @Test
    void nestedSwitchRoutesKeepTheirTerminalStationsSeparate() {
        openProject(nestedSwitchProject());

        assertThat(page.evaluate("""
                () => {
                  const scene = state.world.scene;
                  const rails = scene.links.filter(link => link.from === 'switch');
                  const terminals = rails.map(link => scene.nodes.find(node => node.id === link.to));
                  return terminals.length === 5 && terminals.every(node => node?.kind === 'end')
                    && new Set(terminals.map(node => node.id)).size === 5
                    && terminals.every((node, index) => terminals.slice(index + 1).every(other =>
                      node.x + node.width <= other.x || other.x + other.width <= node.x
                      || node.y + node.height <= other.y || other.y + other.height <= node.y
                    ));
                }
                """)).isEqualTo(true);
    }

    @Test
    void rollingBuiltApplicationExecutesTheMatchingChoiceExample() {
        openProject(choiceProject());
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(runResult(0)).isEqualTo(RailixValue.string("matched"));
    }

    @Test
    void rollingBuiltApplicationExecutesTheOtherwiseChoiceExample() {
        openProject(choiceProject());
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(runResult(1)).isEqualTo(RailixValue.string("otherwise"));
    }

    @Test
    void choicePreviewShowsTheBooleanResolvedByTheBuiltApplication() {
        openProject(choiceProject());

        selectWorldNode("choice");
        page.locator("[data-preview-input-value='conditions']").waitFor();

        assertThat(page.locator("[data-preview-input-value='conditions']").first().textContent())
                .isEqualTo("true");
    }

    @Test
    void choicePreviewShowsTheBuiltMatcherPredicateStage() {
        openProject(choiceProject());

        selectWorldNode("choice");
        page.locator("[data-matcher-group='0'] [data-preview-slot='0']").waitFor();

        assertThat(page.locator("[data-matcher-group='0'] [data-preview-slot='0']").textContent())
                .isEqualTo("true");
    }

    @Test
    void insertingAFilterPreservesTheExistingPrimaryRoute() {
        addTrigger();
        addManipulationAfterSelected();
        waitForText("#build-state", "Built");
        selectTrigger();
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("filter");
        page.locator("[data-add-step='railix.filter']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const filter = project.nodes.find(node => node.use === 'railix.filter');
                  const manipulation = project.nodes.find(node => node.use === 'railix.field-manipulation');
                  return project.links.find(link => link.from === filter.id + '.match')?.to === manipulation.id
                    && project.links.find(link => link.from === filter.id + '.otherwise')?.to === 'end';
                }
                """)).isEqualTo(true);
    }

    @Test
    void populatedBranchPreventsDeletingItsFilter() {
        addStepToOtherwiseBranch();
        final String filter = String.valueOf(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.use === 'railix.filter').id
                """));

        selectWorldNode(filter);

        openInspectorTab("overview");
        assertThat(page.locator("#delete-step").isDisabled()).isEqualTo(true);
    }

    @Test
    void deletingABranchLeafRestoresOnlyThatOutcomeToEnd() {
        addStepToOtherwiseBranch();

        clickOverview("#delete-step");
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const filter = project.nodes.find(node => node.use === 'railix.filter');
                  return project.links.find(link => link.from === filter.id + '.otherwise')?.to;
                }
                """)).isEqualTo("end");
    }

    @Test
    void deletingAnEmptyFilterReconnectsItsIncomingRoute() {
        addFilterAfterTrigger();

        clickOverview("#delete-step");
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const trigger = project.nodes.find(node => node.use === 'railix.trigger.cli');
                  return [
                    project.nodes.some(node => node.use === 'railix.filter'),
                    project.links.find(link => link.from === trigger.id + '.next')?.to
                  ].join('|');
                }
                """)).isEqualTo("false|end");
    }

    @Test
    void deletingAFilterWithAPopulatedPrimaryRouteKeepsThatRouteConnected() {
        addFilterAfterTrigger();
        clickOverview("[data-add-outcome='match']");
        page.locator("#step-search").fill("field");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");
        final String filter = String.valueOf(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.use === 'railix.filter').id
                """));
        selectWorldNode(filter);

        clickOverview("#delete-step");
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const trigger = project.nodes.find(node => node.use === 'railix.trigger.cli');
                  const target = project.links.find(link => link.from === trigger.id + '.next')?.to;
                  return project.nodes.some(node => node.use === 'railix.filter') + '|'
                    + project.nodes.find(node => node.id === target)?.use;
                }
                """)).isEqualTo("false|railix.field-manipulation");
    }

    @Test
    void missingOutcomeLinkIsShownAsMissingInsteadOfEnd() {
        addFilterAfterTrigger();
        page.evaluate("() => state.world.fit()");
        awaitScene();

        page.evaluate("""
                () => {
                  const filter = state.project.nodes.find(node => node.use === 'railix.filter');
                  state.project.links = state.project.links.filter(link =>
                    link.from !== filter.id + '.otherwise');
                  dirty(true);
                }
                """);
        waitForText("#build-state", "Not built");

        openInspectorTab("overview");
        assertThat(page.locator(".machine[data-selected=true]").getAttribute("data-error")).isEqualTo("true");
        assertThat(page.locator(".next-routes > div:has([data-add-outcome='otherwise'])").textContent())
                .contains("Missing link").doesNotContain("Trigger result");
        assertThat(page.locator("[data-add-outcome='otherwise']").isDisabled()).isTrue();
    }

    @Test
    void duplicateOutcomeLinksAreShownAsMultipleInsteadOfChoosingOne() {
        addFilterAfterTrigger();
        page.evaluate("() => state.world.fit()");
        awaitScene();

        page.evaluate("""
                () => {
                  const filter = state.project.nodes.find(node => node.use === 'railix.filter');
                  state.project.links.push({from: filter.id + '.otherwise', to: 'end'});
                  dirty(true);
                }
                """);
        waitForText("#build-state", "Not built");

        openInspectorTab("overview");
        assertThat(page.locator(".machine[data-selected=true]").getAttribute("data-error")).isEqualTo("true");
        assertThat(page.locator(".next-routes > div:has([data-add-outcome='otherwise'])").textContent())
                .contains("Multiple links").doesNotContain("Trigger result");
    }

    @Test
    void duplicateOutcomeDisablesStepInsertion() {
        addFilterAfterTrigger();

        page.evaluate("""
                () => {
                  const filter = state.project.nodes.find(node => node.use === 'railix.filter');
                  state.project.links.push({from: filter.id + '.otherwise', to: 'end'});
                  dirty(true);
                }
                """);
        waitForText("#build-state", "Not built");

        openInspectorTab("overview");
        assertThat(page.locator("[data-add-outcome='otherwise']").isDisabled()).isTrue();
    }

    @Test
    void unknownOutcomeTargetIsShownAsUnknownInsteadOfDisappearing() {
        addFilterAfterTrigger();
        page.evaluate("() => state.world.fit()");
        awaitScene();

        page.evaluate("""
                () => {
                  const filter = state.project.nodes.find(node => node.use === 'railix.filter');
                  state.project.links.find(link => link.from === filter.id + '.otherwise').to = 'missing-step';
                  dirty(true);
                }
                """);
        waitForText("#build-state", "Not built");

        openInspectorTab("overview");
        assertThat(page.locator(".machine[data-selected=true]").getAttribute("data-error")).isEqualTo("true");
        assertThat(page.locator(".next-routes > div:has([data-add-outcome='otherwise'])").textContent())
                .contains("Unknown Step").doesNotContain("Trigger result");
        assertThat(page.locator("[data-add-outcome='otherwise']").isDisabled()).isTrue();
    }

    @Test
    void repeatedOutcomeTargetIsShownAsRepeatedInsteadOfRenderedTwice() {
        addFilterAfterTrigger();
        clickOverview("[data-add-outcome='match']");
        page.locator("#step-search").fill("field");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");
        page.evaluate("() => state.world.fit()");
        awaitScene();

        final String filter = String.valueOf(page.evaluate("""
                () => {
                  const filter = state.project.nodes.find(node => node.use === 'railix.filter');
                  const target = state.project.links.find(link => link.from === filter.id + '.match').to;
                  state.project.links.find(link => link.from === filter.id + '.otherwise').to = target;
                  dirty(true);
                  return filter.id;
                }
                """));
        waitForText("#build-state", "Not built");

        selectWorldNode(filter);
        openInspectorTab("overview");
        page.evaluate("() => state.world.fit()");
        awaitScene();
        assertThat(page.locator(".machine[data-error=true]").count()).isPositive();
        assertThat(page.locator(".next-routes > div:has([data-add-outcome='otherwise'])").textContent())
                .contains("Repeated Step").doesNotContain("Field Manipulation");
        assertThat(page.locator("[data-add-outcome='otherwise']").isDisabled()).isTrue();
    }

    @Test
    void deletingAFlowRemovesEveryBranchNode() {
        addStepToOtherwiseBranch();

        selectTrigger();
        clickOverview("#delete-step");
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  return project.nodes.map(node => node.id).join('|') + ':' + project.links.length;
                }
                """)).isEqualTo("app:0");
    }

}

final class RailixCreatorAuthoringBrowserIT extends RailixCreatorBrowserSupport {
    @Test
    void addTriggerSearchUsesInstalledTriggerCatalog() {
        clickOverview("#add-trigger");
        page.locator("#step-search").fill("cli");

        assertThat(page.locator("[data-add-step]").count()).isEqualTo(1);
        assertThat(page.locator("[data-add-step]").textContent()).contains("CLI", "railix.trigger.cli");
    }

    @Test
    void addingTriggerCreatesARealFlowAndKeepsTheLastBuiltApplication() {
        addTrigger();
        waitForText("#build-state", "Built");
        selectTrigger();

        final Locator trigger = page.locator("[data-kind='trigger'][data-node-id]");
        assertThat(trigger.count()).isEqualTo(1);
        final String id = trigger.getAttribute("data-node-id");
        assertThat(trigger.getAttribute("aria-pressed")).isEqualTo("true");
        assertThat(trigger.getAttribute("aria-label")).isNotBlank();
        final Locator end = page.locator("[data-kind='end'][data-world-id='end:" + id + ".next']");
        end.waitFor();
        assertThat(end.getAttribute("aria-label")).isEqualTo("End");
        assertThat(page.evaluate("""
                async id => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  return project.nodes.length === 2
                    && project.nodes.find(node => node.id === id)?.use === 'railix.trigger.cli'
                    && project.links.length === 2
                    && project.links.some(link => link.from === 'app.start' && link.to === id)
                    && project.links.some(link => link.from === id + '.next' && link.to === 'end');
                }
                """, id)).isEqualTo(true);
        assertThat(applicationPid()).matches("[1-9][0-9]*");
        assertThat(page.locator(".graph-stage").textContent()).doesNotContain("stream");
        assertThat(page.locator("#inspector").textContent())
                .doesNotContain("PROJECT_TRIGGER_RESULT_REQUIRED", "Trigger example: example");
    }

    @Test
    void triggerTargetPathCanBeEditedFromItsGenericContract() {
        addTrigger();

        page.locator("#target-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("#new-path-field").fill("command");
        page.locator("#append-path-field").click();
        page.locator("#apply-path").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("#target-path").textContent()).contains("context", "command");
    }

    @Test
    void triggerInspectorSeparatesConfigurationExamplesAndAppearance() {
        addTrigger();

        assertThat(page.locator("[data-inspector-mode]").allTextContents())
                .containsExactly("Overview", "Inputs", "Appearance", "Groups", "Examples");
        assertThat(((String) page.locator(".inspector-tabs").evaluate(
                "tabs => getComputedStyle(tabs).gridTemplateColumns"
        )).split(" ")).hasSize(5);
        assertThat(page.locator("#target-path").count()).isEqualTo(1);
        assertThat(page.locator("#example-payload, #presentation-name").count()).isZero();

        openInspectorTab("examples");

        assertThat(page.locator("#example-payload").count()).isEqualTo(1);
        assertThat(page.locator("#target-path, #presentation-name").count()).isZero();

        openInspectorTab("appearance");

        assertThat(page.locator("#presentation-name").count()).isEqualTo(1);
        assertThat(page.locator("#target-path, #example-payload").count()).isZero();
    }

    @Test
    void triggerTargetIsAlwaysVisibleAndCanResetToItsContractDefault() {
        addTrigger();

        assertThat(page.locator("#target-path").count()).isEqualTo(1);
        assertThat(page.locator("#target-present").count()).isZero();
        assertThat(page.locator("#inspector").textContent())
                .doesNotContain("Use target", "Override target");

        chooseCustomPathFor("target", "command");
        waitForText("#build-state", "Built");
        assertThat(page.locator("#target-path").textContent()).contains("context", "command");
        assertThat(page.locator("[data-reset-path]").isEnabled()).isTrue();

        page.locator("[data-reset-path]").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("#target-path").textContent())
                .contains("context", "payload", "arguments");
        assertThat(page.locator("[data-reset-path]").isEnabled()).isFalse();
    }

    @Test
    void graphTargetResetFollowsTheCurrentSourcePath() {
        addTrigger();
        chooseCustomPathFor("target", "payload");
        waitForText("#build-state", "Built");
        openInspectorTab("examples");
        replaceExamplePayloads(
                "{\"first\":\"A\",\"second\":\"B\"}",
                "{\"first\":\"C\",\"second\":\"D\"}",
                "{\"first\":\"E\",\"second\":\"F\"}"
        );
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");

        choosePath("source", "payload", "second");
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-input-name='target'] .path-button").textContent())
                .contains("context", "payload", "first");
        assertThat(page.locator("[data-input-name='target'] [data-reset-path]").isEnabled()).isTrue();

        page.locator("[data-input-name='target'] [data-reset-path]").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-input-name='target'] .path-button").textContent())
                .contains("context", "payload", "second");
    }

    @Test
    void triggerExampleKeepsPayloadAndOptionalContextSeparate() {
        addTrigger();
        openInspectorTab("examples");

        assertThat(examplePayload().inputValue()).isEqualTo("[]");
        assertThat(exampleContext().inputValue()).isBlank();

        examplePayload().fill("[\"arg1\",\"arg2\",\"arg3\"]");
        examplePayload().press("Tab");
        exampleContext().fill("{\"header\":{\"request\":\"local\"}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => JSON.stringify(
                  (await (await fetch('/api/project')).json()).project.nodes
                    .find(node => node.use === 'railix.trigger.cli').examples[0]
                )
                """)).isEqualTo(
                "{\"context\":{\"header\":{\"request\":\"local\"}},\"name\":\"no-arguments\","
                        + "\"payload\":[\"arg1\",\"arg2\",\"arg3\"]}"
        );
    }

    @Test
    void clearingOptionalExampleContextRemovesItFromTheProject() {
        addTrigger();
        openInspectorTab("examples");
        exampleContext().fill("{\"header\":{\"request\":\"local\"}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");

        exampleContext().fill("");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => Object.hasOwn(
                  (await (await fetch('/api/project')).json()).project.nodes
                    .find(node => node.use === 'railix.trigger.cli').examples[0],
                  'context'
                )
                """)).isEqualTo(false);
    }

    @Test
    void examplesTabRemainsSelectedWhileRollingBuildCompletes() {
        addTrigger();
        openInspectorTab("examples");

        examplePayload().fill("[\"railix\"]");
        examplePayload().press("Tab");
        waitForText("#build-state", "Built");

        assertThat(page.locator("[data-inspector-mode='examples']").getAttribute("class"))
                .contains("active");
        assertThat(page.locator("#example-payload").inputValue()).contains("railix");
    }

    @Test
    void selectingAnotherNodeReturnsToItsInspectorTab() {
        addTrigger();
        openInspectorTab("examples");

        selectWorldNode("app");

        assertThat(page.locator("[data-inspector-mode='inspect']").getAttribute("class"))
                .contains("active");
        assertThat(page.locator("#example-payload").count()).isZero();
    }

    @Test
    void triggerNodeShowsItsExampleCoverageCount() {
        addTrigger();
        openInspectorTab("examples");
        waitForText(".trigger-node .world-detail", "3 examples");

        assertThat(page.locator(".trigger-node").textContent()).contains("3 examples");

        page.locator("#add-example").click();
        waitForText(".trigger-node .world-detail", "4 examples");

        assertThat(page.locator(".trigger-node").textContent()).contains("4 examples");
    }

    @Test
    void buildStatusRefreshesAutomaticExampleTraceStorage() {
        addTrigger();
        selectWorldNode("app");
        page.locator(".build-indicator").click();
        waitForText("#example-suite-state", "Completed");
        page.waitForFunction("""
                () => document.querySelector('#example-trace-storage')?.textContent !== '0 B'
                """);

        assertThat(page.locator("#example-trace-storage").textContent()).matches(".*[1-9].*");
    }

    @Test
    void selectedExampleHighlightsItsPathWhileUnionCoverageKeepsTheOtherReachedBranchNeutral() {
        openProject(filterProject());
        selectTrigger();
        page.locator(".run-result").waitFor();

        waitForCoverage("matched", "selected");
        assertThat(page.locator("[data-node-id='command']").getAttribute("data-coverage")).isEqualTo("selected");
        assertThat(page.locator("[data-node-id='filter']").getAttribute("data-coverage")).isEqualTo("selected");
        assertThat(page.locator("[data-node-id='matched']").getAttribute("data-coverage")).isEqualTo("selected");
        assertThat(page.locator("[data-node-id='otherwise']").getAttribute("data-coverage")).isEqualTo("covered");
    }

    @Test
    void stepReachedByNoExampleIsHighlightedAsUncovered() {
        openProject(filterProject());
        selectTrigger();
        openInspectorTab("examples");
        page.locator("[data-select-example='1']").click();
        page.locator("#delete-example").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        page.locator(".run-result").waitFor();

        waitForCoverage("otherwise", "uncovered");
        assertThat(page.locator("[data-node-id='otherwise']").getAttribute("data-coverage")).isEqualTo("uncovered");
    }

    @Test
    void rejectedReorderedDraftKeepsCoverageBoundToTheRunningApplication() {
        openProject(filterProject());
        selectTrigger();
        openInspectorTab("examples");
        page.locator("[data-select-example='1']").click();
        page.locator("#delete-example").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        page.locator(".run-result").waitFor();

        page.evaluate("""
                () => {
                  state.project.nodes.reverse();
                  state.project.format = 2;
                  dirty(true);
                }
                """);
        waitForText("#build-state", "Not built");
        waitForCoverage("matched", "selected");
        waitForCoverage("otherwise", "uncovered");

        assertThat(page.locator("[data-node-id='matched']").getAttribute("data-coverage")).isEqualTo("selected");
        assertThat(page.locator("[data-node-id='otherwise']").getAttribute("data-coverage")).isEqualTo("uncovered");
    }

    @Test
    void exampleHighlightStaysVisibleWhileInspectingApplicationBuildFacts() {
        openProject(filterProject());
        selectTrigger();
        page.locator(".run-result").waitFor();
        waitForCoverage("matched", "selected");

        selectWorldNode("app");
        page.locator(".build-indicator").click();
        page.evaluate("() => state.world.fit()");
        awaitScene();
        waitForCoverage("matched", "selected");
        assertThat(page.locator("[data-node-id='matched']").getAttribute("data-coverage")).isEqualTo("selected");
        assertThat(page.locator("[data-node-id='otherwise']").getAttribute("data-coverage")).isEqualTo("covered");
    }

    @Test
    void triggerExampleSelectionChangesTheRealRouteWithoutRebuilding() throws Exception {
        openProject(choiceProject());
        final String project = Files.readString(directory.resolve("project.json"));
        final String metadata = creatorMetadata();
        final String pid = applicationPid();
        assertThat(page.locator("#world-example").count()).isZero();

        selectTrigger();
        openInspectorTab("examples");
        page.locator("[data-select-example='0']").click();
        waitForCoverage("matched", "selected");
        page.locator("[data-select-example='1']").click();
        waitForCoverage("otherwise", "selected");

        assertThat(page.locator("[data-node-id='matched']").getAttribute("data-coverage")).isEqualTo("covered");
        assertThat(page.locator("[data-select-example='1']").getAttribute("class")).contains("active");
        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(project);
        assertThat(creatorMetadata()).isEqualTo(metadata);
        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void statusRailShowsCoverageFromTheCompletedApplicationExamples() {
        openProject(filterProject());
        page.waitForFunction("() => document.querySelector('#status-coverage')?.hidden === false");

        assertThat(page.locator("#status-coverage").textContent()).isEqualTo("100% coverage");
        assertThat(page.locator("#status-coverage progress").count()).isZero();
        assertThat(page.locator("#status-coverage").getAttribute("title"))
                .isEqualTo("4 of 4 executable Steps reached by completed Examples");
        assertThat(page.locator("#status-pid").textContent()).isEqualTo("PID " + applicationPid());
    }

    @Test
    void completedExampleCoverageNeedsNoDiscoveryGuidance() {
        openProject(filterProject());
        waitForText("#status-coverage", "100% coverage");

        assertThat(page.locator("#world-objective").count()).isZero();
    }

    @Test
    void uncoveredStepCanBeInspectedWithoutDiscoveryGuidance() {
        openProject(filterProject());
        selectTrigger();
        openInspectorTab("examples");
        page.locator("[data-select-example='1']").click();
        page.locator("#delete-example").click();
        waitForText("#build-state", "Built");
        waitForText("#status-coverage", "75% coverage");
        assertThat(page.locator("#world-objective").count()).isZero();

        page.locator("#close-inspector").click();
        page.locator("[data-select-node='otherwise']").click();

        page.waitForFunction("() => document.querySelector('[data-node-id=otherwise]')?.getAttribute('aria-pressed') === 'true'");
        assertThat(page.locator(".inspector-heading h2").textContent()).isEqualTo("Field Manipulation");
    }

    @Test
    void stoppedApplicationClearsStatusTelemetryWithoutRemovingTheDiagram() {
        page.locator("#status-memory").waitFor();
        final long pid = Long.parseLong(applicationPid());

        stopProcess(pid);
        waitForText("#build-state", "Stopped");

        assertThat(page.locator("#status-pid").isHidden()).isTrue();
        assertThat(page.locator("#status-memory").isHidden()).isTrue();
        assertThat(page.locator("#status-cpu").isHidden()).isTrue();
        assertThat(page.locator("#status-uptime").isHidden()).isTrue();
        assertThat(page.locator(".app-node").isVisible()).isTrue();
    }

    @Test
    void examplePayloadFollowsTheCurrentTriggerTargetWhenTheBuiltApplicationRuns() {
        addTrigger();
        chooseCustomPathFor("target", "payload", "command");
        waitForText("#build-state", "Built");
        openInspectorTab("examples");
        examplePayload().fill("[\"hello\",\"railix\"]");
        examplePayload().press("Tab");
        exampleContext().fill("{\"header\":{\"request\":\"local\"}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");

        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent())
                .contains("\"command\": [", "\"hello\"", "\"railix\"")
                .contains("\"request\": \"local\"");
    }

    @Test
    void primitiveCanBeAddedAsAnOrdinaryMappedGraphStep() {
        prepareTextPayloadTrigger();

        clickOverview("#add-next-step");
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").waitFor();

        assertThat(page.locator("[data-add-step='text.lowercase']").count()).isEqualTo(1);

        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");

        assertThat(page.locator(".step-node").textContent()).contains("Lowercase");
        assertThat(page.locator("[data-input-name='source'] .path-button").textContent())
                .contains("context", "payload", "text");
        assertThat(page.locator("[data-input-name='target'] .path-button").textContent())
                .contains("context", "payload", "text");
        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const step = project.nodes.find(node => node.use === 'text.lowercase');
                  return JSON.stringify({receives: step.receives, returns: step.returns});
                }
                """)).isEqualTo(
                "{\"receives\":{\"value\":[\"context\",\"payload\",\"text\"]},"
                        + "\"returns\":{\"value\":[\"context\",\"payload\",\"text\"]}}"
        );
    }

    @Test
    void primitiveGraphStepExecutesThroughTheBuiltApplication() {
        prepareTextPayloadTrigger();
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");

        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent()).contains("\"text\": \"railix\"");
    }

    @Test
    void primitiveGraphStepPreviewShowsRealBuiltInputAndOutput() {
        addGraphPrimitive("\"RAILIX\"", "lowercase", "text.lowercase");

        page.locator("#preview-source").waitFor();

        assertThat(page.locator("#preview-values").textContent())
                .contains("RAILIX", "railix").doesNotContain("Built example", "Built output");
    }

    @Test
    void browserReadsTheApplicationOwnedTraceWithoutExecutingTheExampleAgain() {
        page.evaluate("""
                () => {
                  const request = window.fetch.bind(window);
                  window.__railixExampleRequests = [];
                  window.__railixExampleIds = [];
                  window.fetch = async (input, options = {}) => {
                    const url = typeof input === 'string' ? input : input.url;
                    window.__railixExampleRequests.push((options.method || 'GET') + ' ' + url);
                    const response = await request(input, options);
                    if (url === '/api/examples' && response.ok) {
                      const inventory = await response.clone().json();
                      window.__railixExampleIds.push(...inventory.cases.map(example => example.id));
                    }
                    return response;
                  };
                }
                """);

        addGraphPrimitive("\"RAILIX\"", "lowercase", "text.lowercase");
        page.locator("#preview-source").waitFor();

        @SuppressWarnings("unchecked")
        final List<String> requests = (List<String>) page.evaluate("() => window.__railixExampleRequests");
        @SuppressWarnings("unchecked")
        final List<String> applicationIds = (List<String>) page.evaluate("() => window.__railixExampleIds");
        assertThat(applicationIds).isNotEmpty();
        assertThat(requests.stream()
                .filter(request -> request.matches("GET /api/examples/[^/]+$"))
                .filter(request -> !request.endsWith("/status") && !request.endsWith("/coverage"))
                .map(request -> java.net.URLDecoder.decode(
                        request.substring("GET /api/examples/".length()), java.nio.charset.StandardCharsets.UTF_8
                )).toList()).isNotEmpty().allMatch(applicationIds::contains);
        assertThat(requests).anyMatch(request -> request.equals("GET /api/application"));
        assertThat(requests).anyMatch(request -> request.equals("GET /api/examples"));
        assertThat(requests).anyMatch(request -> request.equals("GET /api/examples/status"));
        assertThat(requests).anyMatch(request -> request.matches("GET /api/examples/[^/]+$"));
        assertThat(requests).anyMatch(request -> request.matches("GET /api/examples/[^/]+/view"));
        assertThat(requests).anyMatch(request -> request.matches("GET /api/examples/steps/[0-9]+"));
        assertThat(requests).noneMatch(request ->
                request.matches("GET /api/examples/[^/]+/steps/[0-9]+"));
        assertThat(requests).noneMatch(request ->
                request.contains("/api/run/")
                        || request.contains("/api/trace/")
                        || request.contains("/api/preview/"));
    }

    @Test
    void browserBacksOffAndRetriesTransientApplicationExampleInventoryFailure() {
        addTrigger();
        waitForText("#build-state", "Built");
        page.evaluate("""
                () => {
                  const request = window.fetch.bind(window);
                  window.__railixInventoryAttempts = 0;
                  window.__railixInventoryAttemptTimes = [];
                  window.fetch = (input, options = {}) => {
                    const url = typeof input === 'string' ? input : input.url;
                    if (url === '/api/examples') {
                      window.__railixInventoryAttempts++;
                      window.__railixInventoryAttemptTimes.push(performance.now());
                      if (window.__railixInventoryAttempts === 1) {
                        return Promise.resolve(new Response(
                          '{"status":"unavailable"}',
                          {status: 503, headers: {'Content-Type': 'application/json'}}
                        ));
                      }
                    }
                    return request(input, options);
                  };
                }
                """);

        openInspectorTab("examples");
        examplePayload().fill("[\"retry\"]");
        examplePayload().press("Tab");
        waitForText("#build-state", "Built");
        page.waitForFunction("() => window.__railixInventoryAttempts >= 2");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(((Number) page.evaluate("window.__railixInventoryAttempts")).intValue())
                .isGreaterThanOrEqualTo(2);
        assertThat(((Number) page.evaluate(
                "window.__railixInventoryAttemptTimes[1] - window.__railixInventoryAttemptTimes[0]"
        )).doubleValue()).isGreaterThanOrEqualTo(400);
    }

    @Test
    void browserBacksOffAfterTransientApplicationStatusFailure() {
        addTrigger();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();
        page.evaluate("""
                () => {
                  const request = window.fetch.bind(window);
                  state.application.examples = {...state.application.examples, state: 'running'};
                  window.__railixApplicationAttempts = 0;
                  window.__railixApplicationAttemptTimes = [];
                  window.fetch = (input, options = {}) => {
                    const url = typeof input === 'string' ? input : input.url;
                    if (url === '/api/application') {
                      window.__railixApplicationAttempts++;
                      window.__railixApplicationAttemptTimes.push(performance.now());
                      if (window.__railixApplicationAttempts === 1) {
                        return Promise.resolve(new Response(
                          '{"status":"unavailable"}',
                          {status: 503, headers: {'Content-Type': 'application/json'}}
                        ));
                      }
                    }
                    return request(input, options);
                  };
                  scheduleApplicationPoll(0);
                }
                """);

        page.waitForFunction("() => window.__railixApplicationAttempts >= 2");

        assertThat(((Number) page.evaluate(
                "window.__railixApplicationAttemptTimes[1] - window.__railixApplicationAttemptTimes[0]"
        )).doubleValue()).isGreaterThanOrEqualTo(400);
    }

    @Test
    void browserBacksOffAfterTransientSelectedExampleFailure() {
        addTrigger();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();
        page.evaluate("""
                () => {
                  const request = window.fetch.bind(window);
                  const exampleId = selectedExampleId();
                  const running = JSON.stringify({
                    application_pid: Number(state.application.pid),
                    revision: Number(state.application.examples?.revision || 0),
                    state: 'running'
                  });
                  window.__railixSelectedExampleAttempts = 0;
                  window.__railixSelectedExampleAttemptTimes = [];
                  window.fetch = (input, options = {}) => {
                    const url = typeof input === 'string' ? input : input.url;
                    if (url === '/api/examples/status') {
                      return Promise.resolve(new Response(
                        running,
                        {status: 200, headers: {'Content-Type': 'application/json'}}
                      ));
                    }
                    if (url === `/api/examples/${encodeURIComponent(exampleId)}`) {
                      window.__railixSelectedExampleAttempts++;
                      window.__railixSelectedExampleAttemptTimes.push(performance.now());
                      if (window.__railixSelectedExampleAttempts === 1) {
                        return Promise.resolve(new Response(
                          '{"status":"unavailable"}',
                          {status: 503, headers: {'Content-Type': 'application/json'}}
                        ));
                      }
                    }
                    return request(input, options);
                  };
                  state.application.examples = {...state.application.examples, state: 'running'};
                  scheduleApplicationPoll(0);
                }
                """);

        page.waitForFunction("() => window.__railixSelectedExampleAttempts >= 2");

        assertThat(((Number) page.evaluate(
                "window.__railixSelectedExampleAttemptTimes[1]"
                        + " - window.__railixSelectedExampleAttemptTimes[0]"
        )).doubleValue()).isGreaterThanOrEqualTo(400);
    }

    @Test
    void sameArtifactRestartRefetchesEveryApplicationOwnedExampleProjection() {
        addTrigger();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();
        final long previousPid = Long.parseLong(applicationPid());
        final String previousFingerprint = String.valueOf(page.evaluate(
                "async () => (await (await fetch('/api/application')).json()).fingerprint"
        ));
        final String previousPath = String.valueOf(page.evaluate(
                "async () => (await (await fetch('/api/application')).json()).build_path"
        ));
        page.evaluate("""
                () => {
                  const request = window.fetch.bind(window);
                  window.__railixRestartExampleReads = [];
                  window.fetch = async (input, options = {}) => {
                    const url = typeof input === 'string' ? input : input.url;
                    const response = await request(input, options);
                    if ((options.method || 'GET') === 'GET' && url.startsWith('/api/examples')) {
                      const observedPid = Number(state.application.pid || 0);
                      response.clone().json().then(body => {
                        window.__railixRestartExampleReads.push({
                          url,
                          status: response.status,
                          pid: Number(body.application_pid || observedPid)
                        });
                      }).catch(() => {});
                    }
                    return response;
                  };
                }
                """);

        stopProcess(previousPid);
        page.waitForFunction("pid => state.application.state === 'stopped'"
                + " && Number(state.application.pid) === Number(pid)", Long.toString(previousPid));
        final Number restartStatus = (Number) page.evaluate("""
                async () => {
                  const opened = await (await fetch('/api/project', {cache: 'no-store'})).json();
                  return (await fetch('/api/project', {
                    method: 'POST',
                    headers: mutationHeaders(),
                    body: JSON.stringify(opened.project)
                  })).status;
                }
                """);
        assertThat(restartStatus.intValue()).isEqualTo(200);
        page.waitForFunction("pid => Number(state.application.pid) !== Number(pid)"
                + " && state.application.state === 'running'"
                + " && state.application.examples?.state === 'completed'", Long.toString(previousPid));
        page.locator(".run-result").waitFor();
        final long currentPid = ((Number) page.evaluate("Number(state.application.pid)")).longValue();
        page.waitForFunction("""
                pid => {
                  const reads = window.__railixRestartExampleReads
                    .filter(read => read.status === 200 && read.pid === Number(pid))
                    .map(read => read.url);
                  return reads.includes('/api/examples')
                    && reads.includes('/api/examples/coverage')
                    && reads.some(url => url.split('/').length === 4
                      && !url.endsWith('/status') && !url.endsWith('/coverage'))
                    && reads.some(url => url.split('/').length === 5 && url.endsWith('/view'))
                    && reads.some(url => url.split('/').length === 5 && url.startsWith('/api/examples/steps/'));
                }
                """, Long.toString(currentPid));

        assertThat(currentPid).isNotEqualTo(previousPid);
        assertThat(page.evaluate(
                "async () => (await (await fetch('/api/application')).json()).fingerprint"
        )).isEqualTo(previousFingerprint);
        assertThat(page.evaluate(
                "async () => (await (await fetch('/api/application')).json()).build_path"
        )).isEqualTo(previousPath);
    }

    @Test
    void latePreviousApplicationCoverageCannotRestoreItsObservations() {
        addTrigger();
        waitForText("#build-state", "Built");
        page.evaluate("""
                () => {
                  const request = window.fetch.bind(window);
                  let delayed = false;
                  window.__coverageStarted = false;
                  window.__releaseCoverage = null;
                  window.fetch = async (input, options = {}) => {
                    const url = typeof input === 'string' ? input : input.url;
                    const response = await request(input, options);
                    if (!delayed && url === '/api/examples/coverage') {
                      delayed = true;
                      window.__coverageStarted = true;
                      await new Promise(resolve => window.__releaseCoverage = resolve);
                    }
                    return response;
                  };
                }
                """);

        openInspectorTab("examples");
        examplePayload().fill("[\"previous\"]");
        examplePayload().press("Tab");
        waitForText("#build-state", "Built");
        page.waitForFunction("() => window.__coverageStarted === true");
        final String previousPid = applicationPid();

        examplePayload().fill("[\"current\"]");
        examplePayload().press("Tab");
        waitForText("#build-state", "Built");
        page.waitForFunction("pid => Number(state.application.pid) !== Number(pid)", previousPid);
        final String currentPid = applicationPid();
        final boolean currentIdentityRemainedStable = (Boolean) page.evaluate("""
                pid => new Promise(resolve => {
                  let stable = true;
                  const observer = setInterval(() => {
                    stable = stable && Number(state.application.pid) === Number(pid);
                  }, 1);
                  window.__releaseCoverage();
                  setTimeout(() => {
                    clearInterval(observer);
                    resolve(stable && Number(state.application.pid) === Number(pid));
                  }, 100);
                })
                """, currentPid);
        assertThat(currentIdentityRemainedStable).isTrue();
        page.waitForFunction("pid => Number(state.application.pid) === Number(pid)"
                + " && state.application.examples?.state === 'completed'", currentPid);
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(applicationPid()).isEqualTo(currentPid);
        assertThat(page.locator(".run-result").textContent()).contains("current");
    }

    @Test
    void browserDoesNotRequestCreatorTraceExecution() {
        page.evaluate("""
                () => {
                  const request = window.fetch.bind(window);
                  window.__railixTraceExecutionRequests = [];
                  window.fetch = (input, options = {}) => {
                    const url = typeof input === 'string' ? input : input.url;
                    window.__railixTraceExecutionRequests.push((options.method || 'GET') + ' ' + url);
                    return request(input, options);
                  };
                }
                """);

        addGraphPrimitive("\"RAILIX\"", "lowercase", "text.lowercase");
        page.locator("#preview-source").waitFor();

        @SuppressWarnings("unchecked")
        final List<String> requests = (List<String>) page.evaluate(
                "() => window.__railixTraceExecutionRequests"
        );
        assertThat(requests).noneMatch(request -> request.contains("/api/trace/"));
    }

    @Test
    void selectedGraphStepSourcePickerDoesNotOfferItsOwnReturnedPath() {
        prepareTextPayloadTrigger();
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        chooseCustomPathFor("target", "payload", "lower");
        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();

        page.locator("[data-input-name='source'] .path-button").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();

        assertThat(page.locator("[data-path-part='lower']").count()).isZero();
    }

    @Test
    void typeChangingPrimitiveWritesItsNumberToTheMappedTarget() {
        addGraphPrimitive("\"12.9\"", "to number", "text.to-number");

        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent())
                .contains("\"status\": \"succeeded\"")
                .contains("\"value\": 12.9");
    }

    @Test
    void nextStepCompatibilityUsesTheBuiltOutputOfTheCurrentStep() {
        final String conversion = addGraphPrimitive("\"12.9\"", "to number", "text.to-number");

        selectWorldNode(conversion);
        clickOverview("[data-add-outcome='ok']");

        assertThat(page.locator("[data-add-step='number.floor']").count()).isEqualTo(1);
        assertThat(page.locator("[data-add-step='text.lowercase']").count()).isZero();
    }

    @Test
    void falliblePrimitiveUsesItsExplicitInvalidOutcomeWithoutFailingTheRun() {
        addGraphPrimitive("\"not-a-number\"", "to number", "text.to-number");

        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent())
                .contains("\"status\": \"succeeded\"")
                .contains("\"value\": \"not-a-number\"");
    }

    @Test
    void filterFieldPickerUsesTheUnionOfRealPayloadExamples() {
        addTrigger();
        chooseCustomPathFor("target", "payload");
        waitForText("#build-state", "Built");
        openInspectorTab("examples");
        replaceExamplePayloads(
                "{\"route\":\"status\"}",
                "{\"priority\":2}",
                "{}"
        );
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("filter");
        page.locator("[data-add-step='railix.filter']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");

        page.locator("#conditions-0-field-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();

        assertThat(page.locator(".path-choices").textContent()).contains("route", "priority");
    }

    @Test
    void graphPickerDoesNotOfferAValueStepWithoutACompatibleExampleField() {
        addTrigger();

        clickOverview("#add-next-step");
        page.locator("#step-search").fill("lowercase");

        assertThat(page.locator("[data-add-step='text.lowercase']").count()).isZero();
        assertThat(page.locator("#step-options").textContent()).contains("No installed Step matches.");
    }

    @Test
    void graphPickerRequiresAnAutomaticSourceToMatchEveryExample() {
        addTrigger();
        chooseCustomPathFor("target", "payload");
        waitForText("#build-state", "Built");
        openInspectorTab("examples");
        replaceExamplePayloads("\"Railix\"", "7", "\"Railix\"");
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");

        clickOverview("#add-next-step");
        page.locator("#step-search").fill("lowercase");

        assertThat(page.locator("[data-add-step='text.lowercase']").count()).isZero();
    }

    @Test
    void requiredGraphSourcePathCannotUsePartialExampleCoverage() {
        addTrigger();
        chooseCustomPathFor("target", "payload");
        waitForText("#build-state", "Built");
        openInspectorTab("examples");
        replaceExamplePayloads(
                "{\"value\":\"one\",\"other\":\"two\"}",
                "{\"other\":\"three\"}",
                "{\"other\":\"four\"}"
        );
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("lowercase");
        page.locator("[data-add-step='text.lowercase']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");

        page.locator("[data-input-name='source'] .path-button").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();

        assertThat(page.locator("[data-path-part='value']").count()).isZero();
        assertThat(page.locator("[data-path-part='other']").count()).isEqualTo(1);
    }

    @Test
    void prototypeNamedExampleTargetCannotMutateTheBrowserPrototype() {
        addTrigger();

        chooseCustomPathFor("target", "payload", "__proto__", "polluted");
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("Object.hasOwn(Object.prototype, 'polluted')")).isEqualTo(false);
        assertThat(page.evaluate("({}).polluted === undefined")).isEqualTo(true);
    }

    @Test
    void generatedFlowNameIsShortMemorableAndEditable() {
        addTrigger();
        waitForText("#build-state", "Built");
        final String generated = presentationName().inputValue();
        final String edited = "quiet-vector".equals(generated) ? "rapid-quark" : "quiet-vector";
        final String pid = applicationPid();

        presentationName().fill(edited);
        clickAndWaitForCreatorSave(() -> presentationName().press("Tab"));

        assertThat(generated).matches("[a-z]+-[a-z]+");
        assertThat(edited).isNotEqualTo(generated);
        assertThat(page.locator(".trigger-node").textContent()).contains(edited);
        assertThat(page.evaluate("""
                async edited => Object.values((await (await fetch('/api/project')).json()).creator.steps)
                  .filter(step => step.name === edited).length
                """, edited)).isEqualTo(1);
        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void editingAFlowNameNeverChangesItsStableStepId() {
        addTrigger();
        final String id = page.locator(".trigger-node").getAttribute("data-node-id");

        presentationName().fill("sales/eu");
        clickAndWaitForCreatorSave(() -> presentationName().press("Tab"));
        page.reload();
        final String reloadedProject = String.valueOf(page.evaluate("""
                async () => JSON.stringify((await (await fetch('/api/project')).json()).project)
                """));
        page.waitForFunction("""
                () => document.querySelector('#build-state')?.textContent !== 'Loading'
                """);
        final String loadedState = String.valueOf(page.evaluate("""
                () => JSON.stringify({
                  build: state.build,
                  project: state.project,
                  creator: state.creator,
                  diagnostics: state.diagnostics
                })
                """));

        assertThat(reloadedProject).contains("\"id\":\"" + id + "\"");
        assertThat(page.locator("#build-state").textContent())
                .as(loadedState).isEqualTo("Running");
        assertThat(page.locator(".trigger-node").count()).as(loadedState).isEqualTo(1);
        assertThat(page.locator(".trigger-node").getAttribute("data-node-id"))
                .as(loadedState).isEqualTo(id);
    }

    @Test
    void installedSingletonTriggerOptionCannotBeAddedTwice() {
        addTrigger();
        waitForText("#build-state", "Built");
        selectWorldNode("app");
        page.evaluate("() => state.world.fit()");
        awaitScene();

        clickOverview("#add-trigger");
        assertThat(page.locator("[data-add-step='railix.trigger.http']").isEnabled()).isTrue();
        page.locator("#step-search").fill("cli");
        assertThat(page.locator("[data-add-step='railix.trigger.cli']").count()).isZero();
        assertThat(page.locator(".trigger-node").count()).isEqualTo(1);
    }

    @Test
    void compilerDiagnosticAppearsOnlyOnItsOwningNode() {
        addTrigger();
        waitForText("#build-state", "Built");

        exampleContext().fill("{\"runtime\":{}}");
        exampleContext().press("Tab");
        page.evaluate("() => state.world.fit()");
        awaitScene();

        assertThat(page.locator("#build-state").textContent()).isEqualTo("Running");
        assertThat(page.locator(".trigger-node").getAttribute("data-error")).isEqualTo("true");
        assertThat(page.locator(".app-node").getAttribute("data-error")).isEqualTo("false");
        openInspectorTab("inspect");
        assertThat(page.locator("#inspector").textContent())
                .contains("PROJECT_TRIGGER_EXAMPLE_CONTEXT_INVALID")
                .contains("Context must be an object without context.runtime.");

        selectWorldNode("app");

        assertThat(page.locator("#inspector").textContent())
                .doesNotContain("Example context must be an object without context.runtime.");
    }

    @Test
    void fieldManipulationUsesFullContextPathsAndCompletesTheFlow() {
        addTrigger();
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        choosePath("value-0-source", "payload");
        waitForText("#build-state", "Built");

        assertThat(page.locator(".step-node").textContent()).contains("Field Manipulation");
        assertThat(page.locator("#value-0-source-path").textContent()).contains("context", "payload");
        assertThat(page.locator("#field-path").textContent()).contains("context", "result");
    }

    @Test
    void fieldManipulationCanCreateACustomContextField() {
        addTrigger();
        addManipulationAfterSelected();
        chooseCustomField("auth");
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("{\"token\":\"railix\"}");
        page.locator("#value-0-literal-value").press("Tab");
        waitForText("#build-state", "Built");
        awaitScene();

        assertThat(page.locator(".step-node").textContent()).contains("Field Manipulation");
        assertThat(page.locator("#field-path").textContent()).contains("context", "auth");
        assertThat(page.locator("#value-0-option").inputValue()).isEqualTo("literal");
        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(page.locator(".run-result").textContent()).contains("\"auth\": {", "\"token\": \"railix\"");
    }

    @Test
    void orderedCandidateSourcesAndPredicatesExecuteThroughTheBuiltApplication() {
        prepareRejectedCurrentCandidate();
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent()).contains("\"value\": \"fallback\"");
    }

    @Test
    void builtPreviewHighlightsTheCandidateThatSuppliedTheValue() {
        prepareRejectedCurrentCandidate();
        page.locator(".candidate.selected-candidate").waitFor();

        assertThat(page.locator(".candidate.selected-candidate").getAttribute("data-candidate-index"))
                .isEqualTo("1");
    }

    @Test
    void candidatePredicatePickerExcludesFalliblePrimitives() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"value\":\"12\"}}");
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        choosePath("field", "payload", "value");
        page.locator("#value-0-when-new-predicate-search").fill("to number");

        assertThat(page.locator("[data-candidate-index='0'] [data-add-predicate='text.to-number']").count())
                .isZero();
    }

    @Test
    void reorderedCandidatesRemainInOrderAfterReload() {
        addTrigger();
        addManipulationAfterSelected();
        addLiteralCandidate("\"fallback\"");
        page.locator("[data-move-candidate='1'][data-direction='-1']").click();
        waitForText("#build-state", "Built");

        page.reload();
        waitForText("#build-state", "Built");
        page.locator(".step-node").click();

        assertThat(page.locator("#value-0-option").inputValue()).isEqualTo("literal");
    }

    @Test
    void reorderedCandidateKeepsItsInvalidJsonDraftAtTheNewPosition() {
        addTrigger();
        addManipulationAfterSelected();
        addLiteralCandidate("null");
        page.locator("#value-1-literal-value").fill("[");
        page.locator("#value-1-literal-value").press("Tab");

        page.locator("[data-move-candidate='1'][data-direction='-1']").click();

        assertThat(page.locator("#value-0-literal-value").inputValue()).isEqualTo("[");
        assertThat(page.locator("#inspector").textContent()).contains("Value must be valid JSON.");
    }

    @Test
    void invalidLiteralJsonRemainsVisibleForCorrection() {
        addTrigger();
        addManipulationAfterSelected();
        page.locator("#value-0-option").selectOption("literal");
        waitForText("#build-state", "Built");

        page.locator("#value-0-literal-value").fill("{\"unfinished\":");
        page.locator("#value-0-literal-value").press("Tab");

        assertThat(page.locator("#value-0-literal-value").inputValue()).isEqualTo("{\"unfinished\":");
        assertThat(page.locator("#inspector").textContent()).contains("Value must be valid JSON.");
    }

    @Test
    void invalidLaterCandidateJsonRemainsVisibleForCorrection() {
        addTrigger();
        addManipulationAfterSelected();
        page.locator("#value-candidate-search").fill("literal");
        page.locator("[data-add-candidate='literal']").click();
        waitForText("#build-state", "Built");

        page.locator("#value-1-literal-value").fill("[");
        page.locator("#value-1-literal-value").press("Tab");

        assertThat(page.locator("#value-1-literal-value").inputValue()).isEqualTo("[");
        assertThat(page.locator("#inspector").textContent()).contains("Value must be valid JSON.");
    }

    @Test
    void leavingLiteralModeDiscardsItsInvalidDraft() {
        addTrigger();
        addManipulationAfterSelected();
        page.locator("#value-0-option").selectOption("literal");
        waitForText("#build-state", "Built");
        page.locator("#value-0-literal-value").fill("[");
        page.locator("#value-0-literal-value").press("Tab");

        page.locator("#value-0-option").selectOption("current");
        waitForText("#build-state", "Built");
        page.locator("#value-0-option").selectOption("literal");
        waitForText("#build-state", "Built");

        assertThat(page.locator("#value-0-literal-value").inputValue()).isEqualTo("null");
        assertThat(page.locator("#inspector").textContent()).doesNotContain("Value must be valid JSON.");
    }

    @Test
    void removingACandidateDiscardsItsInvalidDraft() {
        addTrigger();
        addManipulationAfterSelected();
        page.locator("#value-candidate-search").fill("literal");
        page.locator("[data-add-candidate='literal']").click();
        waitForText("#build-state", "Built");
        page.locator("#value-1-literal-value").fill("[");
        page.locator("#value-1-literal-value").press("Tab");

        page.locator("[data-remove-candidate='1']").click();
        waitForText("#build-state", "Built");
        page.locator("#value-candidate-search").fill("literal");
        page.locator("[data-add-candidate='literal']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("#value-1-literal-value").inputValue()).isEqualTo("null");
        assertThat(page.locator("#inspector").textContent()).doesNotContain("Value must be valid JSON.");
    }

    @Test
    void invalidLiteralJsonIsNotPersisted() {
        addTrigger();
        addManipulationAfterSelected();
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("\"kept\"");
        page.locator("#value-0-literal-value").press("Tab");
        waitForText("#build-state", "Built");

        page.locator("#value-0-literal-value").fill("[");
        page.locator("#value-0-literal-value").press("Tab");
        page.reload();
        waitForText("#build-state", "Built");
        page.locator(".step-node").first().click();

        assertThat(page.locator("#value-0-literal-value").inputValue()).isEqualTo("\"kept\"");
        assertThat(page.locator("#inspector").textContent()).doesNotContain("Value must be valid JSON.");
    }

    @Test
    void invalidLiteralDraftSurvivesAnotherBuildWithoutPreviewingOldData() {
        addTrigger();
        addManipulationAfterSelected();
        page.locator("#value-0-option").selectOption("literal");
        waitForText("#build-state", "Built");
        page.locator("#value-0-literal-value").fill("[");
        page.locator("#value-0-literal-value").press("Tab");

        choosePath("field", "result");
        waitForText("#build-state", "Built");

        assertThat(page.locator("#value-0-literal-value").inputValue()).isEqualTo("[");
        assertThat(page.locator("#inspector").textContent()).contains("Value must be valid JSON.");
        assertThat(page.locator("#preview-values output").count()).isZero();
    }

    @Test
    void cancellingAFieldPathChoiceKeepsTheCurrentField() {
        addTrigger();
        addManipulationAfterSelected();
        waitForText("#build-state", "Built");
        final String current = page.locator("#field-path").textContent();

        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();
        page.locator("#cancel-path").click();

        assertThat(page.locator(".path-browser").count()).isZero();
        assertThat(page.locator("#field-path").textContent()).isEqualTo(current);
        assertThat(page.locator("#build-state").textContent()).isEqualTo("Running");
    }

    @Test
    void cancellingASourcePathChoiceKeepsTheCurrentSource() {
        addTrigger();
        addManipulationAfterSelected();
        page.locator("#value-0-option").selectOption("field");
        waitForText("#build-state", "Built");
        final String current = page.locator("#value-0-source-path").textContent();

        page.locator("#value-0-source-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();
        page.locator("#cancel-path").click();

        assertThat(page.locator(".path-browser").count()).isZero();
        assertThat(page.locator("#value-0-source-path").textContent()).isEqualTo(current);
        assertThat(page.locator("#build-state").textContent()).isEqualTo("Running");
    }

    @Test
    void laterOperationCanSelectAFieldCreatedByAnEarlierOperation() {
        addTrigger();
        addManipulationAfterSelected();
        chooseCustomField("auth");
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("{\"token\":\"railix\"}");
        page.locator("#value-0-literal-value").press("Tab");
        addManipulationAfterSelected();
        assertThat(page.locator(".path-browser").count()).isZero();
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();
        assertThat(page.locator("[data-path-part='result']").count())
                .as(page.locator("#inspector").textContent()).isEqualTo(1);
        page.locator("[data-path-part='result']").click();
        page.locator("#apply-path").click();
        page.locator("#value-0-option").selectOption("field");
        page.locator("#value-0-source-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='auth']").click();

        assertThat(page.locator("[data-path-part='token']").count()).isEqualTo(1);
    }

}

final class RailixCreatorCompositionBrowserIT extends RailixCreatorBrowserSupport {
    @Test
    void adjacentFieldManipulationsRemainSeparateUntilTheUserGroupsThem() {
        createLowercaseJourney();

        assertThat(stepIds()).hasSize(2);
        assertThat(page.locator("[data-region-group]").count()).isZero();
    }

    @Test
    void creatingAGroupPersistsOnlyFormatTwoCreatorMetadataWithoutRestarting() throws Exception {
        createLowercaseJourney();
        final String project = Files.readString(directory.resolve("project.json"));
        final String pid = applicationPid();
        final List<String> steps = stepIds();

        final String group = createGroup(steps.get(0), steps.get(1));

        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(project);
        assertThat(Files.readString(directory.resolve("railix.creator.json")))
                .contains("\"format\":2", "\"id\":\"" + group + "\"", "\"group\":\"" + group + "\"")
                .doesNotContain("occurrences", "members", "camera");
        assertThat(stepIds()).hasSize(2);
        assertThat(page.locator("[data-region-group]").count()).isEqualTo(1);
        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void groupPickerSearchesExistingGroupsWithoutCreatingThem() {
        createLowercaseJourney();
        final List<String> steps = stepIds();
        createGroup(steps.get(0));

        selectWorldNode(steps.get(1));
        openInspectorTab("appearance");
        page.locator("#choose-group").click();
        page.locator("#group-picker-search").fill("group 1");

        assertThat(page.locator("[data-assign-group] strong").allTextContents())
                .containsExactly("No group", "Group 1");
        assertThat(page.locator("[data-assign-group] small").allTextContents())
                .containsExactly("Remove the visual assignment", "1 Step");
        assertThat(page.locator("#new-group").count()).isZero();
    }

    @Test
    void selectingNoGroupRemovesOnlyTheStepAssignment() {
        createLowercaseJourney();
        final String step = stepIds().get(0);
        createGroup(step);

        selectWorldNode(step);
        openInspectorTab("appearance");
        page.locator("#choose-group").click();
        clickAndWaitForCreatorSave(() -> page.locator("[data-assign-group='']").click());

        assertThat(page.evaluate("""
                async step => {
                  const creator = (await (await fetch('/api/project')).json()).creator;
                  return creator.groups.length + '|' + (creator.steps[step]?.group || '');
                }
                """, step)).isEqualTo("1|");
        assertThat(page.locator("[data-region-group]").count()).isZero();
    }

    @Test
    void oneGroupCreatesOneRegionPerDisconnectedBranchWithoutChangingSteps() {
        openProject(choiceProject());

        createGroup("matched", "otherwise");

        page.locator("[data-region-group]").first().waitFor();
        assertThat(page.locator("[data-region-group]").count()).isEqualTo(2);
        assertThat(stepIds()).containsExactly("choice", "matched", "otherwise");
        assertThat(page.locator("#inspector").textContent()).contains("Steps2", "Regions2");
        assertThat(page.locator(".issues").count()).isZero();
    }

    @Test
    void applicationGroupManagerCreatesAndDeletesAnEmptyReusableIdentity() {
        selectWorldNode("app");
        page.locator("[data-inspector-mode=groups]").click();

        clickAndWaitForCreatorSave(() -> page.locator("#new-group").click());

        assertThat(page.evaluate("""
                async () => {
                  const creator = (await (await fetch('/api/project')).json()).creator;
                  return creator.groups.length + '|' + Object.values(creator.steps)
                    .filter(step => step.group).length;
                }
                """)).isEqualTo("1|0");
        assertThat(page.locator("#inspector").textContent()).contains("Steps0", "Regions0");

        clickAndWaitForCreatorSave(() -> page.locator("#delete-group").click());
        assertThat(page.locator(".manager-heading").textContent()).contains("Group Manager");
    }

    @Test
    void creatingAGroupFromStepAppearanceDoesNotChangeItsAssignment() {
        createLowercaseJourney();
        final String selected = stepIds().get(1);
        openInspectorTab("appearance");
        page.locator("[data-inspector-mode=groups]").click();

        clickAndWaitForCreatorSave(() -> page.locator("#new-group").click());

        assertThat(page.evaluate("""
                async selected => {
                  const creator = (await (await fetch('/api/project')).json()).creator;
                  return creator.groups.length + '|' + (creator.steps[selected]?.group || '');
                }
                """, selected)).isEqualTo("1|");
        assertThat(page.locator("[data-region-group]").count()).isZero();
    }

    @Test
    void selectedRegionOffersFocusedGroupManagement() {
        createLowercaseJourney();
        final String group = createGroup(stepIds().get(0));
        page.locator("[data-inspector-mode=inspect]").click();
        page.locator("#close-inspector").click();
        page.locator("[data-region-group]").waitFor();

        page.locator("[data-region-group='" + group + "']").click();
        openInspectorTab("overview");
        page.locator("[data-inspector-mode=groups]").click();

        assertThat(page.locator(".manager-heading").textContent()).contains("Group Manager");
        assertThat(page.locator("[data-manage-group='" + group + "']").getAttribute("class"))
                .contains("active");
    }

    @Test
    void doubleClickingADisconnectedRegionLabelFocusesOnlyThatRegion() {
        openProject(choiceProject());
        createGroup("matched", "otherwise");
        page.locator("[data-inspector-mode=inspect]").click();
        page.locator("#close-inspector").click();
        page.locator("[data-region-group]").nth(1).waitFor();
        final String before = canvasStyle();
        final String selected = page.locator("[data-group-region-label]").first()
                .getAttribute("data-group-region-label");

        page.locator("[data-group-region-label]").first().dblclick();
        waitForCanvasChange(before);

        page.waitForFunction("""
                id => {
                  const region = state.world.scene.nodes.find(node => node.id === id);
                  return new URLSearchParams(state.world.query).get('inside') === id && region?.expanded
                    && document.querySelector('#graph').dataset.cameraMoving !== 'true'
                    && state.world.scene.nodes.filter(node => node.kind === 'region' && node.group === region.group && node.id !== id)
                      .every(other => !other.expanded);
                }
                """, selected);
        assertThat(page.locator("#zoom-level").textContent()).isEqualTo("50%");
        assertThat(page.locator("#leave-group").isVisible()).isTrue();
    }

    @Test
    void insertingBetweenGroupedStepsDoesNotInheritOrSynchronizeTheGroup() {
        openProject(fourStepProject());
        final String group = createGroup("one", "two");
        selectWorldNode("one");

        clickOverview("#add-next-step");
        page.locator("#step-search").fill("field manipulation");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");

        assertThat(page.evaluate("""
                async () => {
                  const workspace = await (await fetch('/api/project')).json();
                  const inserted = workspace.project.links.find(link => link.from === 'one.next').to;
                  return [
                    workspace.creator.steps.one.group,
                    workspace.creator.steps.two.group,
                    workspace.creator.steps[inserted]?.group || '',
                    workspace.project.links.find(link => link.from === inserted + '.next').to
                  ].join('|');
                }
                """)).isEqualTo(group + "|" + group + "||two");
        page.evaluate("() => state.world.fit()");
        awaitScene();
        assertThat(page.locator("[data-region-group]").count()).isEqualTo(2);
    }

    @Test
    void deletingAGroupPreservesTheFunctionalProjectAndApplication() throws Exception {
        createLowercaseJourney();
        final String project = Files.readString(directory.resolve("project.json"));
        final String pid = applicationPid();
        final List<String> steps = stepIds();
        createGroup(steps.get(0), steps.get(1));
        selectWorldNode(steps.get(0));
        openInspectorTab("appearance");
        page.locator("[data-inspector-mode=groups]").click();

        clickAndWaitForCreatorSave(() -> page.locator("#delete-group").click());

        assertThat(Files.readString(directory.resolve("project.json"))).isEqualTo(project);
        assertThat(page.evaluate("""
                async () => {
                  const creator = (await (await fetch('/api/project')).json()).creator;
                  return creator.groups.length + '|' + Object.values(creator.steps)
                    .filter(step => step.group).length;
                }
                """)).isEqualTo("0|0");
        assertThat(stepIds()).hasSize(2);
        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void groupNamePersistsAsEscapedRegionTextWithoutRestarting() {
        createLowercaseJourney();
        createGroup(stepIds().get(0));
        final String pid = applicationPid();
        final String name = "<img src=x onerror=\"window.__railixInjected=true\">";

        presentationName().fill(name);
        clickAndWaitForCreatorSave(() -> presentationName().press("Tab"));
        page.reload();
        waitForText("#build-state", "Built");
        page.locator("[data-group-region-label]").waitFor();

        assertThat(page.locator("[data-group-region-label] strong").textContent()).isEqualTo(name);
        assertThat(page.locator("[data-group-region-label] img").count()).isZero();
        assertThat(page.evaluate("() => window.__railixInjected === true")).isEqualTo(false);
        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void groupColorPersistsOnEveryDerivedRegionWithoutRestarting() {
        openProject(choiceProject());
        createGroup("matched", "otherwise");
        final String pid = applicationPid();

        page.locator("#presentation-color").fill("#A10F22");
        clickAndWaitForCreatorSave(() -> page.locator("#presentation-color").press("Tab"));
        page.reload();
        waitForText("#build-state", "Built");
        page.locator("[data-region-group]").first().waitFor();

        assertThat(page.locator("[data-region-group]").count()).isEqualTo(2);
        assertThat(page.locator("[data-region-group] .world-detail").allTextContents())
                .containsExactly("1 Step", "1 Step");
        assertThat(page.evaluate("""
                () => state.world.scene.nodes.filter(node => node.group).map(node => node.color)
                """)).isEqualTo(List.of("#A10F22", "#A10F22"));
        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void storedGroupIconRemainsPortableWithoutAnIconChooser() {
        openProject(choiceProject());
        createGroup("matched", "otherwise");
        final String pid = applicationPid();

        assertThat(page.evaluate("""
                async () => {
                  const metadata = (await (await fetch('/api/project')).json()).creator;
                  metadata.groups[0].icon = {media_type:'image/svg+xml',data:'PHN2Zy8+'};
                  return (await fetch('/api/creator',{method:'POST',headers:mutationHeaders(),body:JSON.stringify(metadata)})).status;
                }
                """)).isEqualTo(200);
        page.reload();
        waitForText("#build-state", "Built");
        page.locator("[data-group-region-label]").first().waitFor();
        assertThat(page.locator("#choose-icon,#icon-search").count()).isZero();

        assertThat(page.evaluate("""
                () => {
                  const urls = [...document.querySelectorAll('[data-group-region-label] .flow-icon')]
                    .map(icon => icon.src);
                  return urls.length + '|' + new Set(urls).size + '|' + urls.every(url => url.startsWith('blob:'));
                }
                """)).isEqualTo("2|1|true");
        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).creator.groups[0].icon.data
                """)).isEqualTo("PHN2Zy8+");
        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void groupBoundaryDefaultsToSolidAndPersistsOnlyAnExplicitAlternative() {
        createLowercaseJourney();
        createGroup(stepIds().get(0));
        page.locator("[data-region-group]").waitFor();

        assertThat(page.locator("[data-region-group]").getAttribute("data-boundary")).isEqualTo("solid");
        assertThat(page.evaluate("""
                async () => 'boundary' in (await (await fetch('/api/project')).json()).creator.groups[0]
                """)).isEqualTo(false);

        clickAndWaitForCreatorSave(() -> page.locator("#group-boundary").selectOption("dashed"));
        page.reload();
        waitForText("#build-state", "Built");
        page.locator("[data-region-group]").waitFor();

        assertThat(page.locator("[data-region-group]").getAttribute("data-boundary")).isEqualTo("dashed");
    }

    @Test
    void zoomChangesOnlyTheEphemeralCanvasCamera() {
        openProject(deepBranchProject(4));
        final String metadata = creatorMetadata();
        final String before = canvasStyle();

        page.locator("#zoom-in").click();
        waitForCanvasChange(before);

        assertThat(page.locator("#zoom-level").textContent()).isNotBlank();
        assertThat(creatorMetadata()).isEqualTo(metadata);
    }

    @Test
    void focusingAStationRevealsItsSelectableLabelInsideALargeFlow() {
        openProject(deepBranchProject(96));

        page.evaluate("() => void state.world.focus('step-48')");
        final Locator selected = page.locator("#world-labels [data-node-id='step-48']");
        selected.waitFor();
        selected.click();
        openInspectorTab("overview");
        waitForText("#selection-overview h2", "Field Manipulation");
        awaitScene();

        assertThat(selected.getAttribute("aria-pressed")).isEqualTo("true");
        assertThat(page.locator("#selection-overview h2").textContent()).isEqualTo("Field Manipulation");
        assertThat(page.locator("#world-labels > *").count()).isLessThanOrEqualTo(256);
    }

    @Test
    void fittingTheWorldReplacesDetailedStationsWithAnAggregate() {
        openProject(deepBranchProject(96));
        page.evaluate("() => void state.world.focus('step-48')");
        page.locator("#world-labels [data-node-id='step-48']").waitFor();

        page.evaluate("() => state.world.fit()");
        page.waitForFunction("""
                () => state.world.scene.nodes.some(node => node.kind === 'region' && !node.expanded && node.count > 1)
                  && !state.world.scene.nodes.some(node => node.id === 'step-48')
                """);

        assertThat(page.locator("#world-labels [data-node-id='step-48']").count()).isZero();
        assertThat(page.locator("#world-labels > *").count()).isLessThanOrEqualTo(256);
    }

    @Test
    void panningChangesOnlyTheEphemeralCanvasCamera() {
        openProject(deepBranchProject(4));
        final String metadata = creatorMetadata();
        final String before = canvasStyle();
        final var box = page.locator("#graph").boundingBox();

        page.mouse().move(box.x + 8, box.y + 8);
        page.mouse().down();
        page.mouse().move(box.x + 108, box.y + 58);
        page.mouse().up();

        waitForCanvasChange(before);
        assertThat(creatorMetadata()).isEqualTo(metadata);
    }

    @Test
    void reloadRestoresTheDeterministicFitInsteadOfPersistingTheCamera() {
        openProject(deepBranchProject(4));
        awaitScene();
        final String fitted = (String) page.evaluate("() => state.world.query");
        page.locator("#zoom-in").click();
        page.locator("#zoom-in").click();
        page.waitForFunction("fitted => state.world.query !== fitted", fitted);

        page.reload();
        waitForText("#build-state", "Built");
        awaitScene();
        // Observation labels can change height without changing the camera.
        assertThat(page.evaluate("() => state.world.query")).isEqualTo(fitted);
    }

    @Test
    void largeUngroupedBranchGetsAnEphemeralAutomaticRegion() {
        openProject(deepBranchProject(96));

        assertThat(page.evaluate("""
                () => state.world.scene.nodes.some(node => node.kind === 'region' && !node.group && node.count > 1)
                """)).isEqualTo(true);
        assertThat(creatorMetadata()).doesNotContain("auto:", "region", "camera");
    }

    @Test
    void legacyOccurrenceMetadataMigratesToFlatAssignmentsAndDerivedRegions() {
        openProject(fourStepProject());
        final String metadata = """
                {"format":1,"steps":{},"groups":[{"id":"group-one","name":"Legacy","occurrences":[{
                  "id":"occurrence-one","flow":"command","parent":null,
                  "steps":{"slot-one":"one","slot-two":"two"}
                }]}]}
                """;

        assertThat(page.evaluate("""
                async metadata => (await fetch('/api/creator', {
                  method: 'POST', headers: mutationHeaders(), body: metadata
                })).status
                """, metadata)).isEqualTo(200);
        page.reload();
        waitForText("#build-state", "Built");
        page.locator("[data-region-group]").waitFor();

        assertThat(creatorMetadata())
                .contains("\"format\":2", "\"group\":\"group-one\"")
                .doesNotContain("occurrences", "slot-one");
        assertThat(page.locator("[data-region-group]").count()).isEqualTo(1);
        assertThat(stepIds()).containsExactly("one", "two", "three", "four");
    }

    @Test
    void oneOrdinaryStepDoesNotPretendToBeAGroup() {
        addTrigger();
        addManipulationAfterSelected();

        assertThat(page.locator(".step-node .operation-stack").count()).isZero();
        assertThat(page.locator(".operation-tabs").count()).isZero();
        openInspectorTab("overview");
        assertThat(page.locator("#delete-step").getAttribute("aria-label")).startsWith("Delete ");
    }

    @Test
    void ordinaryStepDoesNotExposeGenericOutcomeRouting() {
        addTrigger();
        addManipulationAfterSelected();

        assertThat(page.locator(".outcome-routes").count()).isZero();
        assertThat(page.locator("#inspector").textContent())
                .doesNotContain("Outcome routes", "Explicit branches", "Ends flow");
        waitForText("#build-state", "Built");
        assertThat(page.locator("#build-state").textContent()).isEqualTo("Running");
    }

    @Test
    void falliblePrimitiveRemainsInsideOneLinearStep() {
        createFallibleNumberJourney("not-a-number");

        assertThat(page.locator(".step-node").count()).isEqualTo(1);
        assertThat(page.locator(".step-node .operation-stack").count()).isZero();
        assertThat(page.locator(".outcome-routes").count()).isZero();
        assertThat(page.locator("#build-state").textContent()).isEqualTo("Running");
    }

    @Test
    void deletingALinearFallibleStepPreservesTheTrigger() {
        createFallibleNumberJourney("12.5");

        clickOverview("#delete-step");
        waitForText("#build-state", "Built");

        PlaywrightAssertions.assertThat(page.locator(".step-node")).hasCount(0);
        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(runResult()).isEqualTo(RailixValue.nullValue());
    }

    @ParameterizedTest(name = "{0} required configuration, preview, and run")
    @CsvSource({
            "number.greater-than, 6, 5, true",
            "number.greater-or-equal, 5.00, 5, true",
            "number.less-than, 6, 5, false",
            "number.less-or-equal, 6, 5, false"
    })
    void numberComparisonCanBeConfiguredPreviewedAndExecuted(
            final String primitive,
            final String value,
            final String than,
            final boolean expected
    ) {
        preparePrimitiveSearch(
                "{\"payload\":{\"value\":" + value + "}}",
                primitive
        );
        page.locator("#steps-options [data-add-nested='" + primitive + "']").click();
        waitForText("#build-state", "Built");

        final var config = page.locator("#steps-0-than-value");
        assertThat(config.inputValue()).isEqualTo("0");

        config.fill(than);
        config.press("Tab");
        waitForText("#build-state", "Built");
        page.locator("[data-preview-stage='0']").waitFor();
        assertThat(page.locator("[data-preview-stage='0']").textContent())
                .isEqualTo(Boolean.toString(expected));

        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(runResult()).isEqualTo(RailixValue.bool(expected));
    }

    @Test
    void hoveredEmptyPrimitiveResultsRefreshAfterTraceCompletes() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"value\":6}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        waitForText("#build-state", "Built");

        page.locator("#value-0-source-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();
        page.locator("[data-path-part='value']").click();
        delayNextTrace();
        page.locator("#apply-path").click();
        page.locator("#steps-search").fill("greater");
        waitForText("#build-state", "Built");
        page.waitForFunction("() => typeof window.__releaseTrace === 'function'");

        final Locator option = page.locator("#steps-options [data-add-nested='number.greater-than']");
        assertThat(option.count()).isZero();
        page.locator("#steps-options").hover();
        page.evaluate("window.__releaseTrace()");
        page.waitForFunction("() => window.__traceCompleted === true");
        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))");
        assertThat(option.count()).isEqualTo(1);
    }

}

final class RailixCreatorStepBrowserIT extends RailixCreatorBrowserSupport {

    @Test
    void emptyPercentileRemainsOneLinearStepWithoutRoutes() {
        createPercentileJourney("[]", "");

        assertThat(page.locator(".step-node").count()).isEqualTo(1);
        assertThat(page.locator(".outcome-routes").count()).isZero();
        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(runResult()).isEqualTo(RailixValue.nullValue());
    }

    @Test
    void selectedStringFieldFiltersAndAddsCompatiblePrimitive() {
        createResultJourney();
        selectTrigger();
        addManipulationAfterSelected();
        choosePath("field", "payload", "text");
        page.locator("#steps-search").fill("lower");

        waitForText("#build-state", "Built");
        final Locator option = page.locator("#steps-options [data-add-nested='text.lowercase']");
        option.waitFor();
        assertThat(page.locator("#steps-options [data-add-nested]").count()).isEqualTo(1);
        option.click();
        waitForText("#build-state", "Built");
        assertThat(page.locator(".program-list .nested-step").textContent()).contains("Lowercase");
    }

    @Test
    void unaryValueStepIsOfferedAsAStandaloneGraphNodeWhenInputIsAvailable() {
        addTrigger();
        waitForText("#build-state", "Built");

        clickOverview("#add-next-step");
        page.locator("#step-search").fill("to json");

        final Locator option = page.locator("[data-add-step='value.to-json']");
        option.waitFor();
        assertThat(option.count()).isEqualTo(1);
    }

    @Test
    void numberFloorCanBeSelectedAndExecuted() {
        createPrimitiveResult("{\"payload\":{\"value\":1.9}}", "floor", "number.floor");

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": 1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("primitivePreviewAndExecutionCases")
    void primitiveCanBePreviewedAndExecuted(
            final String scenario,
            final String example,
            final String search,
            final String primitive,
            final String preview,
            final RailixValue expected
    ) {
        createPrimitivePreviewAndResult(example, search, primitive, preview);

        assertThat(runResult()).isEqualTo(expected);
    }

    static Stream<Arguments> primitivePreviewAndExecutionCases() {
        final String magnitude = "1".repeat(1_024);
        return Stream.of(
                Arguments.of("numberCeilCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":1.2}}", "ceil", "number.ceil", "2",
                        RailixValue.number(2)),
                Arguments.of("numberRoundCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":-1.5}}", "round", "number.round", "-2",
                        RailixValue.number(-2)),
                Arguments.of("numberAbsoluteValueCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":-12.5}}", "abs", "number.abs", "12.5",
                        RailixValue.number(new BigDecimal("12.5"))),
                Arguments.of("numberNegateCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":12.5}}", "negate", "number.negate", "-12.5",
                        RailixValue.number(new BigDecimal("-12.5"))),
                Arguments.of("numberNegateMaximumMagnitudeCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":" + magnitude + "}}", "negate", "number.negate", "-" + magnitude,
                        RailixValue.number(new BigDecimal("-" + magnitude))),
                Arguments.of("numberSignCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":-0.1}}", "sign", "number.sign", "-1",
                        RailixValue.number(-1)),
                Arguments.of("textUppercaseCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":\"stra\\u00dfe\"}}", "uppercase", "text.uppercase", "\"STRASSE\"",
                        RailixValue.string("STRASSE")),
                Arguments.of("textTrimCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":\"\\u2003Railix\\u2003\"}}", "trim", "text.trim", "\"Railix\"",
                        RailixValue.string("Railix")),
                Arguments.of("textNormalizeSpaceCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":\" railix\\t creator \"}}",
                        "normalize space", "text.normalize-space", "\"railix creator\"",
                        RailixValue.string("railix creator")),
                Arguments.of("textNormalizeNfcCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":\"e\\u0301\"}}", "normalize nfc", "text.normalize-nfc", "\"é\"",
                        RailixValue.string("é")),
                Arguments.of("textLengthCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":\"A\\ud83d\\ude80B\"}}", "length", "text.length", "3",
                        RailixValue.number(3)),
                Arguments.of("textIsEmptyCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":\"\"}}", "is empty", "text.is-empty", "true",
                        RailixValue.bool(true)),
                Arguments.of("listIsEmptyCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":[]}}", "is empty", "list.is-empty", "true",
                        RailixValue.bool(true)),
                Arguments.of("booleanToTextCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":true}}", "to text", "boolean.to-text", "\"true\"",
                        RailixValue.string("true")),
                Arguments.of("booleanToNumberCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":true}}", "to number", "boolean.to-number", "1",
                        RailixValue.number(1)),
                Arguments.of("listReverseCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":[1,2]}}", "reverse", "list.reverse", "[2,1]",
                        RailixValue.array(List.of(RailixValue.number(2), RailixValue.number(1)))),
                Arguments.of("numberToTextCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":1.2300}}", "to text", "number.to-text", "\"1.23\"",
                        RailixValue.string("1.23")),
                Arguments.of("valueWrapListCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":null}}", "wrap list", "value.wrap-list", "[null]",
                        RailixValue.array(List.of(RailixValue.nullValue()))),
                Arguments.of("valueToJsonCanBePreviewedAndExecuted",
                        "{\"payload\":{\"value\":{\"z\":2,\"a\":1}}}", "to json", "value.to-json",
                        "\"{\\\"a\\\":1,\\\"z\\\":2}\"", RailixValue.string("{\"a\":1,\"z\":2}"))
        );
    }

    @ParameterizedTest(name = "{0} is hidden for a {1} source")
    @CsvSource(delimiter = '|', textBlock = """
            number.greater-than     | string | {"payload":{"value":"6"}}
            number.greater-or-equal | string | {"payload":{"value":"6"}}
            number.less-than        | string | {"payload":{"value":"6"}}
            number.less-or-equal    | string | {"payload":{"value":"6"}}
            text.starts-with        | number | {"payload":{"value":7}}
            text.ends-with          | number | {"payload":{"value":7}}
            list.is-empty           | object | {"payload":{"value":{}}}
            boolean.to-text         | string | {"payload":{"value":"true"}}
            boolean.to-number       | string | {"payload":{"value":"true"}}
            list.reverse            | object | {"payload":{"value":{}}}
            number.to-text          | string | {"payload":{"value":"1"}}
            """)
    void primitiveIsHiddenForIncompatibleSource(
            final String primitive,
            final String sourceType,
            final String example
    ) {
        preparePrimitiveSearch(example, primitive);

        assertThat(page.locator("#steps-options [data-add-nested='" + primitive + "']").count()).isZero();
    }

    @Test
    void textContainsStringConfigurationCanBeEditedPreviewedAndExecuted() {
        preparePrimitiveSearch("{\"payload\":{\"value\":\"Nano Railix\"}}", "contains");
        page.locator("#steps-options [data-add-nested='text.contains']").click();
        waitForText("#build-state", "Built");

        final var config = page.locator("#steps-0-needle-value");
        assertThat(config.count()).isEqualTo(1);
        assertThat(config.inputValue()).isEmpty();

        config.fill("Rail");
        config.press("Tab");
        waitForText("#build-state", "Built");
        page.locator("[data-preview-stage='0']").waitFor();
        assertThat(page.locator("[data-preview-stage='0']").textContent()).isEqualTo("true");

        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(runResult()).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void textContainsStartsWithItsBuildableEmptyStringDefault() {
        preparePrimitiveSearch("{\"payload\":{\"value\":\"Nano Railix\"}}", "contains");
        page.locator("#steps-options [data-add-nested='text.contains']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-preview-stage='0']").waitFor();

        assertThat(page.locator("#steps-0-needle-value").inputValue()).isEmpty();
        assertThat(page.locator("[data-preview-stage='0']").textContent()).isEqualTo("true");
    }

    @ParameterizedTest(name = "{0} can be configured, previewed, and executed")
    @CsvSource({
            "text.starts-with, prefix, Nano",
            "text.ends-with, suffix, Railix"
    })
    void textBoundaryCanBeConfiguredPreviewedAndExecuted(
            final String primitive,
            final String input,
            final String boundary
    ) {
        preparePrimitiveSearch("{\"payload\":{\"value\":\"Nano Railix\"}}", primitive);
        page.locator("#steps-options [data-add-nested='" + primitive + "']").click();
        waitForText("#build-state", "Built");

        final var config = page.locator("#steps-0-" + input + "-value");
        assertThat(config.inputValue()).isEmpty();

        config.fill(boundary);
        config.press("Tab");
        waitForText("#build-state", "Built");
        page.locator("[data-preview-stage='0']").waitFor();
        assertThat(page.locator("[data-preview-stage='0']").textContent()).isEqualTo("true");

        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(runResult()).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void valueEqualsCanBeConfiguredPreviewedAndExecuted() {
        preparePrimitiveSearch(
                "{\"payload\":{\"value\":{\"answer\":42}}}",
                "equals"
        );
        page.locator("#steps-options [data-add-nested='value.equals']").click();
        waitForText("#build-state", "Built");

        final var expected = page.locator("#steps-0-expected-value");
        assertThat(expected.inputValue()).isEqualTo("null");

        expected.fill("{\"answer\":42}");
        expected.press("Tab");
        waitForText("#build-state", "Built");
        page.locator("[data-preview-stage='0']").waitFor();
        assertThat(page.locator("[data-preview-stage='0']").textContent())
                .isEqualTo("true");

        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(runResult()).isEqualTo(RailixValue.bool(true));
    }

    @Test
    void numberToTextIsOfferedAtTheCanonicalNumberLimit() {
        preparePrimitiveSearch("{\"payload\":{\"value\":1e1023}}", "number.to-text");

        assertThat(page.locator("#steps-options [data-add-nested='number.to-text']").count()).isEqualTo(1);
    }

    @Test
    void numberBeyondTheCanonicalLimitDoesNotReplaceTheRunningApplication() {
        addTrigger();
        waitForText("#build-state", "Built");
        final String pid = applicationPid();
        exampleContext().fill("{\"payload\":{\"value\":1e1024}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Not built");

        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void valueToJsonIsOfferedAtItsCanonicalJsonByteLimit() {
        final String value = canonicalJsonBytes(RailixData.DEFAULT_MAX_SOURCE_BYTES);
        prepareLiteralRefinementSearch(value, "value.to-json");
        awaitRejectedLiteral(value, "value.to-json");
        page.locator("#steps-options [data-add-nested='value.to-json']").waitFor();

        assertThat(page.locator("#steps-options [data-add-nested='value.to-json']").count()).isEqualTo(1);
    }

    @Test
    void valueToJsonIsHiddenBeyondItsCanonicalJsonByteLimit() {
        final String value = canonicalJsonBytes(RailixData.DEFAULT_MAX_SOURCE_BYTES + 1);
        prepareLiteralRefinementSearch(value, "value.to-json");
        awaitRejectedLiteral(value, "value.to-json");
        page.locator("#steps-options .empty-options").waitFor();

        assertThat(page.locator("#steps-options [data-add-nested='value.to-json']").count()).isZero();
    }

    @Test
    void overdeepExampleDoesNotReplaceTheRunningApplication() {
        String value = "null";
        for (int depth = 0; depth < 64; depth++) {
            value = "[" + value + "]";
        }
        addTrigger();
        waitForText("#build-state", "Built");
        final String pid = applicationPid();
        exampleContext().fill("{\"payload\":{\"value\":" + value + "}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Not built");

        assertThat(applicationPid()).isEqualTo(pid);
    }

    @Test
    void rejectedExampleDoesNotBecomeRuntimeFieldEvidence() {
        String deep = "null";
        for (int depth = 0; depth < 64; depth++) {
            deep = "[" + deep + "]";
        }
        addTrigger();
        exampleContext().fill("{\"payload\":{\"value\":true}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");
        page.locator("#add-example").click();
        exampleContext().fill("{\"payload\":{\"value\":" + deep + "}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Not built");
        page.locator("[data-select-example='0']").click();
        openInspectorTab("inspect");
        page.locator(".run-result").waitFor();
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        page.locator("#value-0-source-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();
        page.locator("[data-path-part='value']").click();
        page.locator("#apply-path").click();
        page.locator("#steps-search").fill("wrap list");

        assertThat(page.locator("#steps-options [data-add-nested='value.wrap-list']").count())
                .isEqualTo(1);
    }

    @Test
    void canonicalRefinementKeepsTheUiResponsiveBeyondTheGlobalDepth() {
        String value = "null";
        for (int depth = 0; depth < 65; depth++) {
            value = "[" + value + "]";
        }
        prepareLiteralRefinementSearch(value, "list.reverse");
        page.locator("#steps-options .empty-options").waitFor();

        assertThat(page.locator("#steps-options").isVisible()).isTrue();
        assertThat(page.locator("#steps-options [data-add-nested='list.reverse']").count()).isZero();
    }

    @Test
    void falliblePrimitiveDoesNotCreateAGraphOutcomeOrBlockTheBuild() {
        preparePrimitiveSearch("{\"payload\":{\"value\":\"12.5\"}}", "to number");

        page.locator("#steps-options [data-add-nested='text.to-number']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("#inspector").textContent())
                .doesNotContain("PROJECT_NODE_OUTCOME_CONNECTION_REQUIRED", "Outcome routes");
        assertThat(page.locator("[data-add-outcome]").count()).isZero();
    }

    @Test
    void falliblePrimitiveValidInputFollowsNextAndReturnsANumber() {
        createFallibleNumberJourney("12.5");

        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent())
                .contains("\"result\": 12.5");
    }

    @Test
    void falliblePrimitiveInvalidPreviewLeavesTheTargetUnchangedAndContinues() {
        createFallibleNumberJourney("not-a-number");

        page.locator(".step-node").first().click();
        page.locator("[data-preview-stage='0']").waitFor();

        assertThat(page.locator("[data-preview-stage='0']").textContent()).isEqualTo("invalid");
        assertThat(page.locator("[data-preview-stage='0']").getAttribute("data-preview-status"))
                .isEqualTo("invalid");
        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(page.locator(".run-result").textContent())
                .contains("\"result\": null");
    }

    @Test
    void falliblePrimitivePersistsOnlyThePrimaryGraphRoute() {
        createFallibleNumberJourney("not-a-number");

        assertThat(page.evaluate("""
                async () => {
                  const project = (await (await fetch('/api/project')).json()).project;
                  const convert = project.nodes.find(node =>
                    node.inputs?.steps?.some(step => step.use === 'text.to-number'));
                  return project.links.filter(link => link.from.startsWith(convert.id + '.'))
                    .map(link => link.from.substring(convert.id.length) + ':' + link.to).join('|');
                }
                """)).isEqualTo(".next:end");
    }

    @Test
    void percentileShowsItsDefaultConfigurationWithoutGraphRoutes() {
        preparePrimitiveSearch("{\"payload\":{\"value\":[4,1,3,2]}}", "percentile");

        page.locator("#steps-options [data-add-nested='list.percentile']").click();
        waitForText("#build-state", "Built");

        assertThat(page.locator("#steps-0-percentile-value").inputValue())
                .isEqualTo("95");
        assertThat(page.locator("#steps-0-percentile-value").getAttribute("min"))
                .isEqualTo("0");
        assertThat(page.locator("#steps-0-percentile-value").getAttribute("max"))
                .isEqualTo("100");
        assertThat(page.locator("[data-add-outcome]").count()).isZero();
    }

    @Test
    void percentileConfigurationExecutesACustomNearestRank() {
        createPercentileJourney("[4,1,3,2]", "50");

        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent())
                .contains("\"result\": 2");
    }

    @Test
    void percentileConfigurationPersistsAcrossReload() {
        createPercentileJourney("[4,1,3,2]", "50");

        page.reload();
        waitForText("#build-state", "Built");
        page.locator(".step-node").first().click();

        assertThat(page.locator("#steps-0-percentile-value").inputValue())
                .isEqualTo("50");
    }

    @Test
    void percentileConfigurationPreservesAnExactFractionalRank() {
        final String percentile = "50.0000000000000000001";
        createPercentileJourney("[1,2]", percentile);

        page.reload();
        waitForText("#build-state", "Built");
        page.locator(".step-node").first().click();

        assertThat(page.locator("#steps-0-percentile-value").inputValue())
                .isEqualTo(percentile);
        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(page.locator(".run-result").textContent()).contains("\"result\": 2");
    }

    @Test
    void percentileConfigurationOutsideTheRangeShowsCompilerFeedback() {
        preparePrimitiveSearch("{\"payload\":{\"value\":[1]}}", "percentile");
        page.locator("#steps-options [data-add-nested='list.percentile']").click();

        page.locator("#steps-0-percentile-value").fill("100.1");
        page.locator("#steps-0-percentile-value").press("Tab");
        waitForText("#build-state", "Not built");

        assertThat(page.locator("#inspector").textContent())
                .contains("PROJECT_INPUT_RANGE_INVALID", "from 0 through 100");
    }

    @Test
    void percentileConfigurationMovesWithItsPrimitiveInvocation() {
        preparePrimitiveSearch("{\"payload\":{\"value\":[1,2]}}", "percentile");
        page.locator("#steps-options [data-add-nested='list.percentile']").click();
        page.locator("#steps-0-percentile-value").fill("50.5");
        page.locator("#steps-0-percentile-value").press("Tab");
        page.locator("#steps-search").fill("floor");
        page.locator("#steps-options [data-add-nested='number.floor']").click();

        page.locator("[data-move-nested='0'][data-direction='1']").click();

        assertThat(page.locator("#steps-1-percentile-value").inputValue())
                .isEqualTo("50.5");
    }

    @Test
    void percentileEmptyPreviewLeavesTheTargetUnchangedAndContinues() {
        createPercentileJourney("[]", "");
        page.locator(".step-node").first().click();
        page.locator("[data-preview-stage='0']").waitFor();

        assertThat(page.locator("[data-preview-stage='0']").textContent()).isEqualTo("empty");
        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(page.locator(".run-result").textContent())
                .contains("\"result\": null");
    }

    @Test
    void percentileInvalidPreviewLeavesTheTargetUnchangedAndContinues() {
        createPercentileJourney("[1,\"two\"]", "");
        page.locator(".step-node").first().click();
        page.locator("[data-preview-stage='0']").waitFor();

        assertThat(page.locator("[data-preview-stage='0']").textContent()).isEqualTo("invalid");
        selectTrigger();
        page.locator(".run-result").waitFor();
        assertThat(page.locator(".run-result").textContent())
                .contains("\"result\": null");
    }

    @Test
    void booleanNotCanBeSelectedAndExecuted() {
        createPrimitiveResult("{\"payload\":{\"value\":true}}", "not", "boolean.not");

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": false");
    }

    @Test
    void listSizeCanBeSelectedAndExecuted() {
        createPrimitiveResult("{\"payload\":{\"value\":[1,2]}}", "size", "list.size");

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": 2");
    }

    @Test
    void utcMillisValidationCanBeSelectedAndExecuted() {
        createPrimitiveResult("{\"payload\":{\"value\":0}}", "utc", "date.is-utc-millis");

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": true");
    }

    @Test
    void numberPrimitiveIsHiddenForAStringField() {
        createResultJourney();
        selectTrigger();
        addManipulationAfterSelected();
        choosePath("field", "payload", "text");
        page.locator("#steps-search").fill("floor");

        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();
        assertThat(page.locator("#steps-options [data-add-nested]").count()).isZero();
    }

    @Test
    void primitiveSearchShowsReadableNameAndStableFamilyId() {
        preparePrimitiveSearch("{\"payload\":{\"value\":0}}", "utc");

        assertThat(page.locator("#steps-options [data-add-nested='date.is-utc-millis']").textContent())
                .contains("Is UTC Millis", "date.is-utc-millis");
    }

    @Test
    void primitiveSearchUsesThePreviousPrimitiveOutputShape() {
        preparePrimitiveSearch("{\"payload\":{\"value\":0}}", "utc");
        page.locator("#steps-options [data-add-nested='date.is-utc-millis']").click();
        page.locator("#steps-search").fill("not");

        assertThat(page.locator("#steps-options [data-add-nested='boolean.not']").count()).isEqualTo(1);
    }

    @Test
    void primitiveSearchHidesAnOperationMadeIncompatibleByThePreviousPrimitive() {
        preparePrimitiveSearch("{\"payload\":{\"value\":0}}", "utc");
        page.locator("#steps-options [data-add-nested='date.is-utc-millis']").click();
        page.locator("#steps-search").fill("floor");

        assertThat(page.locator("#steps-options [data-add-nested='number.floor']").count()).isZero();
    }

    @Test
    void compatiblePrimitiveChainExecutesInTheBuiltApplication() {
        preparePrimitiveSearch("{\"payload\":{\"value\":0}}", "utc");
        page.locator("#steps-options [data-add-nested='date.is-utc-millis']").click();
        page.locator("#steps-search").fill("not");
        page.locator("#steps-options [data-add-nested='boolean.not']").click();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": false");
    }

}

final class RailixCreatorDataWorkbenchBrowserIT extends RailixCreatorBrowserSupport {
    @Test
    void selectedFieldOperationAutomaticallyPreviewsItsActualSourceValue() {
        createLowercaseJourney();
        page.locator("#preview-source").waitFor();

        assertThat(page.locator("#preview-source").textContent()).isEqualTo("\"Hello RAILIX\"");
    }

    @Test
    void selectedFieldOperationAutomaticallyPreviewsActualPrimitiveOutputsInOrder() {
        prepareDateNotChain(2);
        page.locator("[data-preview-stage='2']").waitFor();

        assertThat(page.locator("[data-preview-stage]").allTextContents())
                .containsExactly("true", "false", "true");
    }

    @Test
    void selectedFieldOperationPreviewShowsTheFirstAvailableCandidate() {
        prepareMissingResultCandidate();
        page.locator("#steps-search").fill("lower");
        page.locator("#steps-options [data-add-nested='text.lowercase']").click();
        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();

        assertThat(page.locator("#preview-source").textContent()).isEqualTo("\"RAILIX\"");
    }

    @Test
    void selectingALaterFieldOperationPreviewsTheEarlierBuiltOutputAsItsSource() {
        createLowercaseJourney();
        selectWorldNode("result");
        page.locator("#preview-source").waitFor();

        assertThat(page.locator("#preview-source").textContent()).isEqualTo("\"hello railix\"");
    }

    @Test
    void rollingBuildRefreshesTheSelectedFieldOperationPreview() {
        prepareMissingResultCandidate();
        page.locator("#steps-search").fill("lower");
        page.locator("#steps-options [data-add-nested='text.lowercase']").click();
        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();

        page.locator("#value-1-literal-value").fill("\"CREATOR\"");
        page.locator("#value-1-literal-value").press("Tab");
        waitForText("#build-state", "Built");
        waitForText("#preview-source", "\"CREATOR\"");

        assertThat(page.locator("#preview-source").textContent()).isEqualTo("\"CREATOR\"");
    }

    @Test
    void editingTheSelectedOperationImmediatelyClearsItsBuiltPreview() {
        createLowercaseJourney();
        page.locator("#preview-source").waitFor();

        page.locator("[data-remove-nested='0']").click();

        assertThat(page.locator("#preview-source").count()).isZero();
    }

    @Test
    void selectingATriggerWhileTraceLoadsCannotRestoreStaleValues() {
        createLowercaseJourney();
        page.locator("#preview-source").waitFor();

        selectTrigger();
        openInspectorTab("examples");
        examplePayload().fill("[\"Fresh\"]");
        delayNextTrace();
        examplePayload().press("Tab");
        selectWorldNode("lowercase");
        waitForText("#build-state", "Built");
        page.waitForFunction("() => window.__traceStarted === true");
        selectTrigger();
        page.evaluate("window.__releaseTrace()");
        page.evaluate("""
                () => new Promise(resolve =>
                  requestAnimationFrame(() => requestAnimationFrame(resolve))
                )
                """);

        assertThat(page.locator("#preview-source").count()).isZero();
    }

    @Test
    void stoppedBuiltApplicationRemovesItsUnavailableExampleTrace() {
        createLowercaseJourney();
        page.locator("#preview-source").waitFor();
        final long pid = ((Number) page.evaluate(
                "async () => (await (await fetch('/api/application')).json()).pid"
        )).longValue();
        stopProcess(pid);

        page.waitForFunction("() => !document.querySelector('#preview-source')");

        assertThat(page.locator("#preview-source").count()).isZero();
    }

    @Test
    void earlierStepCannotReuseALaterStepsTracePathsAfterTheApplicationStops() {
        addTrigger();
        exampleContext().fill("{\"payload\":{}}");
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        chooseCustomPath("payload", "first");
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("\"one\"");
        page.locator("#value-0-literal-value").press("Tab");
        final String first = page.locator("#inspector").getAttribute("data-selection");
        addManipulationAfterSelected();
        chooseCustomPath("payload", "later");
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("\"two\"");
        page.locator("#value-0-literal-value").press("Tab");
        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();
        final long pid = ((Number) page.evaluate(
                "async () => (await (await fetch('/api/application')).json()).pid"
        )).longValue();
        stopProcess(pid);

        page.waitForFunction("() => !document.querySelector('#preview-source')");
        selectWorldNode(first);
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();

        assertThat(page.locator("[data-path-part='payload']").count()).isZero();
        assertThat(page.locator(".path-choices").textContent()).doesNotContain("later");
    }

    @Test
    void laterOperationOffersPrimitiveForPreviousPrimitiveOutputShape() {
        prepareOperationAfterListSize();
        page.locator("#steps-search").fill("floor");

        assertThat(page.locator("#steps-options [data-add-nested='number.floor']").count()).isEqualTo(1);
    }

    @Test
    void laterOperationExecutesAgainstPreviousPrimitiveOutput() {
        prepareOperationAfterListSize();
        page.locator("#steps-search").fill("floor");
        page.locator("#steps-options [data-add-nested='number.floor']").click();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": 2");
    }

    @Test
    void movingPrimitiveIntoAnIncompatibleOrderShowsTheCompilerDiagnostic() {
        prepareDateNotChain(1);

        page.locator("[data-move-nested='1'][data-direction='-1']").click();
        waitForText("#build-state", "Not built");

        assertThat(page.locator("#inspector").textContent())
                .contains("PROJECT_NESTED_INPUT_INCOMPATIBLE");
    }

    @Test
    void restoringPrimitiveOrderRestoresTheRunningApplication() {
        prepareDateNotChain(1);
        page.locator("[data-move-nested='1'][data-direction='-1']").click();
        waitForText("#build-state", "Not built");

        page.locator("[data-move-nested='1'][data-direction='-1']").click();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": false");
    }

    @Test
    void removingMiddlePrimitivePreservesTheRemainingExecutionOrder() {
        prepareDateNotChain(2);

        page.locator("[data-remove-nested='1']").click();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": false");
    }

    @Test
    void primitiveCanBeRemovedWithoutRemovingItsFieldOperation() {
        createLowercaseJourney();
        page.locator("#preview-source").waitFor();

        page.locator("[data-remove-nested='0']").click();
        waitForText("#build-state", "Built");
        waitForText("#preview-source", "\"Hello RAILIX\"");
        page.mouse().move(0, 0);
        page.locator("#steps-options [data-add-nested='text.lowercase']").waitFor();

        assertThat(page.locator("#steps-options [data-add-nested='text.lowercase']").count()).isEqualTo(1);
        assertThat(page.locator(".program-list .nested-step").count()).isZero();
    }

    @Test
    void deletingSingleResultStepRebuildsWithTheSilentTriggerDefault() {
        createResultJourney();
        page.locator(".step-node").click();

        clickOverview("#delete-step");
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": null");
    }

    @Test
    void laterStringCandidateOffersCompatiblePrimitiveWhenCurrentFieldIsMissing() {
        prepareMissingResultCandidate();
        page.locator("#steps-search").fill("lower");

        assertThat(page.locator("#steps-options [data-add-nested='text.lowercase']").count()).isEqualTo(1);
    }

    @Test
    void laterCandidateExecutesThroughTheRunningApplicationWhenCurrentFieldIsMissing() {
        prepareMissingResultCandidate();
        page.locator("#steps-search").fill("lower");
        page.locator("#steps-options [data-add-nested='text.lowercase']").click();
        waitForText("#build-state", "Built");
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        choosePath("value-0-source", "payload", "value");
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent())
                .contains("\"result\": \"railix\"");
    }

    @Test
    void removingTheLastAvailableCandidateFollowsTheExplicitMissingOutcomeAndTriggerDefault() {
        prepareMissingResultCandidate();
        page.locator("#steps-search").fill("lower");
        page.locator("#steps-options [data-add-nested='text.lowercase']").click();
        waitForText("#build-state", "Built");

        page.locator("[data-remove-candidate='1']").click();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": null");
    }

    @Test
    void deletingTriggerReturnsToBuiltAppOnlyProject() {
        createResultJourney();
        selectTrigger();

        clickOverview("#delete-step");
        waitForText("#build-state", "Built");

        PlaywrightAssertions.assertThat(page.locator(".trigger-node")).hasCount(0);
        assertThat(page.locator("#flow-count").textContent()).isEqualTo("0 flows");
    }

    @Test
    void exampleContextPopulatesTheFirstGuidedFieldChoices() {
        addTrigger();
        exampleContext().fill("""
                {
                  "payload": {"person": {"name": "Ada RAILIX"}},
                  "header": {"authorization": "Bearer token"}
                }
                """);
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");
        addManipulationAfterSelected();
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator(".path-choices [data-path-part='header']").waitFor();

        assertThat(page.locator(".path-choices").textContent()).contains("payload", "header");
    }

    @Test
    void triggerExamplesAreAnEditableList() {
        addTrigger();

        assertThat(examplePayload().inputValue()).isEqualTo("[]");
        assertThat(exampleContext().inputValue()).isBlank();
        page.locator("#add-example").click();

        assertThat(page.locator("[data-select-example]:not([data-select-example='-1'])").count()).isEqualTo(4);
        assertThat(page.locator("#example-name").inputValue()).isEqualTo("example-4");

        page.locator("#example-name").fill("empty-input");
        page.locator("#example-name").press("Tab");
        exampleContext().fill("{\"payload\":{}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");

        page.locator("[data-select-example='0']").click();
        assertThat(page.locator("#example-name").inputValue()).isEqualTo("no-arguments");

        page.locator("[data-select-example='3']").click();
        assertThat(page.locator("#example-name").inputValue()).isEqualTo("empty-input");
        assertThat(exampleContext().inputValue()).contains("\"payload\": {}");
    }

    @Test
    void cliTriggerStartsWithSeveralUsefulExamplePayloads() {
        addTrigger();
        openInspectorTab("examples");

        assertThat(page.locator("[data-select-example]:not([data-select-example='-1'])").allTextContents())
                .containsExactly("no-arguments", "one-argument", "multiple-arguments");
        page.locator("[data-select-example='1']").click();
        assertThat(examplePayload().inputValue()).isEqualTo("[\n  \"railix\"\n]");
        page.locator("[data-select-example='2']").click();
        assertThat(examplePayload().inputValue()).isEqualTo("[\n  \"hello\",\n  \"railix\"\n]");
    }

    @Test
    void guidedFieldChoicesUseTheUnionOfExamplesWithoutMergingTheirValues() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"text\":\"Railix\"}}");
        exampleContext().press("Tab");
        page.locator("#add-example").click();
        exampleContext().fill("{\"payload\":{\"count\":2}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");
        assertThat(exampleContext().inputValue())
                .contains("\"count\": 2")
                .doesNotContain("\"text\"");

        addManipulationAfterSelected();
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();

        assertThat(page.locator(".path-choices").textContent()).contains("text", "count");
    }

    @Test
    void conflictingExampleShapesDoNotCreateSyntheticPrimitiveCompatibility() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"value\":\"Railix\"}}");
        exampleContext().press("Tab");
        page.locator("#add-example").click();
        exampleContext().fill("{\"payload\":{\"value\":7}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");

        addManipulationAfterSelected();
        choosePath("field", "payload", "value");

        assertThat(page.locator("#steps-options [data-add-nested='value.equals']").count())
                .isEqualTo(1);
        assertThat(page.locator("#steps-options [data-add-nested='value.wrap-list']").count())
                .isEqualTo(1);
        assertThat(page.locator("#steps-options [data-add-nested='value.to-json']").count())
                .isEqualTo(1);
        assertThat(page.locator("#steps-options [data-add-nested='text.lowercase']").count())
                .isZero();
        assertThat(page.locator("#steps-options [data-add-nested='number.floor']").count())
                .isZero();
    }

    @Test
    void changingAnExampleShapeInvalidatesPreviousPrimitiveCompatibility() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"value\":\"Railix\"}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");
        addManipulationAfterSelected();
        choosePath("field", "payload", "value");
        page.locator("#steps-options [data-add-nested='text.lowercase']").waitFor();

        assertThat(page.locator("#steps-options [data-add-nested='text.lowercase']").count())
                .isEqualTo(1);

        selectTrigger();
        exampleContext().fill("{\"payload\":{\"value\":7}}");
        exampleContext().press("Tab");

        assertThat(page.locator("#build-state").textContent()).isEqualTo("Building");
        page.locator("#close-inspector").click();
        page.locator(".step-node").click();

        assertThat(page.locator("#steps-options [data-add-nested='text.lowercase']").count())
                .isZero();
        assertThat(page.locator("#steps-options [data-add-nested='number.floor']").count())
                .isZero();

        waitForText("#build-state", "Built");
        page.locator("#steps-options [data-add-nested='number.floor']").waitFor();
        assertThat(page.locator("#steps-options [data-add-nested='text.lowercase']").count())
                .isZero();
    }

    @Test
    void allTriggerExamplesRunAsIndependentCases() {
        addTrigger();
        openInspectorTab("examples");
        page.locator("#add-example").click();
        page.locator("#example-name").fill("second");
        page.locator("#example-name").press("Tab");
        waitForText("#build-state", "Built");

        openInspectorTab("inspect");
        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent())
                .contains("\"example\": \"second\"")
                .contains("\"exit_code\": 0", "\"result\": null");
        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/examples')).json()).cases
                  .map(({name, status}) => `${name}:${status}`).join('|')
                """)).isEqualTo(
                        "no-arguments:succeeded|one-argument:succeeded|multiple-arguments:succeeded|second:succeeded"
                );
    }

    @Test
    void compatiblePrimitiveStepsAreScrollableBeforeSearchAndShrinkWhileTyping() {
        preparePrimitiveSearch("{\"payload\":{\"value\":\" Railix \"}}", "");

        assertThat(page.locator("#steps-options [data-add-nested]").count()).isGreaterThan(1);
        assertThat(page.locator("#steps-options").textContent())
                .contains("Lowercase", "Uppercase", "Trim");

        page.locator("#steps-search").fill("uppercase");

        assertThat(page.locator("#steps-options [data-add-nested]").count()).isEqualTo(1);
        assertThat(page.locator("#steps-options").textContent()).contains("Uppercase");
    }

    @Test
    void exampleContextPreservesAnExactDecimalAcrossReload() {
        preparePrimitiveSearch(
                "{\"payload\":{\"value\":0.10000000000000001}}",
                ""
        );
        waitForText("#build-state", "Built");

        page.reload();
        waitForText("#build-state", "Built");
        selectTrigger();

        assertThat(exampleContext().inputValue())
                .contains("\"value\": 0.10000000000000001");
    }

    @Test
    void previewPreservesAnExactDecimalFromTheBuiltApplication() {
        preparePrimitiveSearch(
                "{\"payload\":{\"value\":0.10000000000000001}}",
                ""
        );
        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();

        assertThat(page.locator("#preview-source").textContent())
                .isEqualTo("0.10000000000000001");
    }

    @Test
    void builtExampleShowsTheEffectiveValueWithoutConfigurationPlumbing() {
        addTrigger();
        addManipulationAfterSelected();
        addLiteralCandidate("\"fallback\"");
        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();

        assertThat(page.locator("[data-input-result='value']").textContent()).isEqualTo("{\"arguments\":[]}");
    }

    @Test
    void guidedPathBuilderRevealsTheSampleOneLevelAtATime() {
        addTrigger();
        exampleContext().fill("""
                {
                  "payload": {"person": {"name": "Ada RAILIX"}},
                  "header": {"authorization": "Bearer token"}
                }
                """);
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();

        assertThat(page.locator(".path-choices").textContent())
                .contains("person")
                .doesNotContain("authorization");
    }

    @Test
    void guidedTargetBuilderCreatesANestedFieldWithoutPathSyntax() {
        addTrigger();
        addManipulationAfterSelected();
        chooseCustomField("auth", "token");
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("\"railix\"");
        page.locator("#value-0-literal-value").press("Tab");

        assertThat(page.locator("#field-path").textContent()).contains("context", "auth", "token");
        page.locator("#preview-source").waitFor();
        assertThat(page.locator("#preview-values").textContent()).contains("\"railix\"");
    }

    @Test
    void guidedArrayIndexPathExecutesInTheBuiltApplication() {
        createLiteralResult(
                "\"Ada\"",
                new Object[]{"payload", "items", 0, "name"},
                "payload", "items", "[0]", "name"
        );

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": \"Ada\"");
    }

    @Test
    void fieldNameContainingADotExecutesAsOneTypedSegment() {
        createLiteralResult(
                "\"Ada\"",
                new Object[]{"payload", "full.name"},
                "payload", "full.name"
        );

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": \"Ada\"");
    }

    @Test
    void numericFieldNameExecutesAsAFieldRatherThanAnArrayIndex() {
        createLiteralResult(
                "\"zero\"",
                new Object[]{"payload", "0"},
                "payload", "0"
        );

        assertThat(page.locator(".run-result").textContent()).contains("\"result\": \"zero\"");
    }

    @Test
    void literalValuePreservesAnExactDecimalInTheBuiltApplication() {
        createLiteralResult(
                "0.10000000000000001",
                new Object[]{"payload", "decimal"},
                "payload", "decimal"
        );

        assertThat(page.locator(".run-result").textContent())
                .contains("\"result\": 0.10000000000000001");
    }

    @Test
    void runtimePrefixFieldIsNotMistakenForReservedRuntime() {
        addTrigger();
        addManipulationAfterSelected();
        chooseCustomField("runtimeX");
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("{}");
        page.locator("#value-0-literal-value").press("Tab");
        addManipulationAfterSelected();
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();

        page.locator("[data-path-part='runtimeX']").waitFor();
        assertThat(page.locator("[data-path-part='runtimeX']").count()).isEqualTo(1);
    }

    @Test
    void objectTargetOffersAFieldSegmentControl() {
        openFieldBuilder("{\"payload\":{\"person\":{\"name\":\"Ada\"}}}", "payload", "person");

        assertThat(page.locator("#append-path-field").count()).isEqualTo(1);
    }

    @Test
    void objectTargetDoesNotOfferAnArrayIndexControl() {
        openFieldBuilder("{\"payload\":{\"person\":{\"name\":\"Ada\"}}}", "payload", "person");

        assertThat(page.locator("#append-path-index").count()).isZero();
    }

    @Test
    void arrayTargetOffersAnArrayIndexControl() {
        openFieldBuilder("{\"payload\":{\"people\":[{\"name\":\"Ada\"}]}}", "payload", "people");

        assertThat(page.locator("#append-path-index").count()).isEqualTo(1);
    }

    @Test
    void arrayPathBuilderPresentsIndexesAsAlternativeSegments() {
        openFieldBuilder("{\"payload\":{\"people\":[null,\"Ada\"]}}", "payload", "people");

        assertThat(page.locator(".path-choices-label").textContent()).isEqualTo("Choose one array index");
        assertThat(page.locator("#append-path-index").textContent()).isEqualTo("Use index");
        assertThat(page.locator(".path-browser").textContent()).doesNotContain("Add item");
    }

    @Test
    void arrayTargetDoesNotOfferAFieldSegmentControl() {
        openFieldBuilder("{\"payload\":{\"people\":[{\"name\":\"Ada\"}]}}", "payload", "people");

        assertThat(page.locator("#append-path-field").count()).isZero();
    }

    @Test
    void writablePathControlsUseTheRunningApplicationsResolvedShape() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"dynamic\":{}}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");
        addManipulationAfterSelected();
        choosePath("field", "payload", "dynamic");
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("[]");
        page.locator("#value-0-literal-value").press("Tab");
        waitForText("#build-state", "Built");
        addManipulationAfterSelected();
        waitForText("#build-state", "Built");

        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();
        page.locator("[data-path-part='dynamic']").click();
        page.locator("#append-path-index").waitFor();

        assertThat(List.of(
                page.locator("#append-path-field").count(),
                page.locator("#append-path-index").count()
        )).containsExactly(0, 1);
    }

    @Test
    void scalarTargetCannotBeExtended() {
        openFieldBuilder("{\"payload\":{\"name\":\"Ada\"}}", "payload", "name");

        assertThat(page.locator(".path-create").count()).isZero();
    }

    @Test
    void sourcePathCannotInventNewSegments() {
        addTrigger();
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        page.locator("#value-0-source-path").click();
        page.locator("[data-path-depth='0']").click();

        assertThat(page.locator(".path-create").count()).isZero();
    }

    @Test
    void defaultedTriggerResultIsSuggestedAsATarget() {
        openFieldBuilder("{\"payload\":{}}");

        assertThat(page.locator("[data-path-part='result']").count()).isEqualTo(1);
    }

    @Test
    void defaultNullTriggerResultIsOfferedAsAnExplicitSource() {
        page.evaluate("""
                () => {
                  const request = window.fetch.bind(window);
                  window.fetch = (input, options = {}) => {
                    const url = typeof input === 'string' ? input : input.url;
                    return url.includes('/api/examples')
                      ? Promise.resolve(new Response('', {status: 503}))
                      : request(input, options);
                  };
                }
                """);
        addTrigger();
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        page.locator("#value-0-source-path").click();
        page.locator("[data-path-depth='0']").click();

        assertThat(page.locator("[data-path-part='result']").count()).isEqualTo(1);
    }

    @Test
    void explicitNullDoesNotAdvanceToALaterCandidateForTypeFiltering() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"value\":null}}");
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        choosePath("field", "payload", "value");
        addLiteralCandidate("\"RAILIX\"");
        page.locator("#steps-search").fill("lower");

        assertThat(page.locator("#steps-options [data-add-nested='text.lowercase']").count()).isZero();
    }

    @Test
    void blankTargetFieldCannotBeAdded() {
        openFieldBuilder("{\"payload\":{}}");

        assertThat(page.locator("#append-path-field").isDisabled()).isTrue();
    }

    @Test
    void negativeArrayIndexCannotBeAdded() {
        openFieldBuilder("{\"payload\":{\"people\":[]}}", "payload", "people");
        page.locator("#new-path-index").fill("-1");

        assertThat(page.locator("#append-path-index").isDisabled()).isTrue();
    }

    @Test
    void reservedRuntimeTargetCannotBeAdded() {
        openFieldBuilder("{\"payload\":{}}");
        page.locator("#new-path-field").fill("runtime");

        assertThat(page.locator("#append-path-field").isDisabled()).isTrue();
    }

    @Test
    void sparseArrayPreviewIncludesRuntimeFilledIndexes() {
        openSourceAfterSparseArrayWrite();

        assertThat(page.locator(".path-choice").count()).isEqualTo(3);
    }

    @Test
    void sparseArrayPreviewTypesRuntimeFilledIndexAsNull() {
        openSourceAfterSparseArrayWrite();

        assertThat(page.locator("[data-path-part='[0]']").textContent()).contains("null");
    }

    @Test
    void automaticExampleRunUsesTheBuiltChildAndShowsTheResultContext() {
        createResultJourney();
        assertThat(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .find(node => node.inputs?.field?.[1] === 'result').inputs.value[0].inputs.source
                """)).isEqualTo(List.of("context", "payload", "text"));
        selectTrigger();
        addManipulationAfterSelected();
        choosePath("field", "payload", "text");
        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();
        page.locator("#steps-search").fill("lower");
        page.locator("#steps-options [data-add-nested='text.lowercase']").click();
        waitForText("#build-state", "Built");
        selectTrigger();

        page.locator(".run-result").waitFor();

        assertThat(page.locator(".run-result").textContent())
                .contains("\"status\": \"succeeded\"", "\"result\": \"hello railix\"", "\"exit_code\": 0");
    }

    @Test
    void completedTraceCannotOverwriteANewerDraft() {
        createLowercaseJourney();
        selectTrigger();
        delayNextTrace();

        exampleContext().fill("{\"payload\":{\"text\":\"Delayed\"}}");
        exampleContext().press("Tab");
        page.waitForFunction("() => typeof window.__releaseTrace === 'function'");
        presentationName().fill("newer-draft");
        presentationName().press("Tab");
        page.evaluate("window.__releaseTrace()");

        assertThat(page.locator(".run-result").count()).isZero();
    }

    @Test
    void completedExampleTracePreservesTheOpenStepPicker() {
        addTrigger();
        waitForText("#build-state", "Built");
        page.waitForFunction("() => state.application.examples?.state === 'completed' && state.traceController === null");
        final String previousPid = applicationPid();
        openInspectorTab("examples");
        examplePayload().fill("[\"delayed\"]");
        examplePayload().press("Tab");
        page.waitForFunction("previousPid => String(state.application.pid) !== previousPid"
                + " && state.application.examples?.state === 'completed' && state.traceController === null", previousPid);
        page.locator("[data-select-example='-1']").click();
        delayNextTrace();
        page.locator("[data-select-example='0']").click();
        page.waitForFunction("() => typeof window.__releaseTrace === 'function'");
        openInspectorTab("inspect");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("json");
        page.locator("[data-add-step='value.to-json']").waitFor();
        assertThat(page.locator("[data-add-step='value.to-json']").count()).isEqualTo(1);

        assertThat(page.evaluate("() => window.railixTraceAborted"))
                .as("Opening the picker must not cancel the pending example trace")
                .isEqualTo(false);
        page.evaluate("window.__releaseTrace()");
        page.waitForFunction("() => window.__traceCompleted === true");
        page.evaluate("""
                () => new Promise(resolve =>
                  requestAnimationFrame(() => requestAnimationFrame(resolve))
                )
                """);

        assertThat(page.locator("#step-search").inputValue()).isEqualTo("json");
        assertThat(page.locator("#step-search").evaluate("input => input === document.activeElement"))
                .isEqualTo(true);
        page.locator("[data-add-step='value.to-json']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        page.locator("#preview-source").waitFor();
        assertThat(page.locator("#preview-source").textContent())
                .isEqualTo("{\"arguments\":[\"delayed\"]}");
    }

    @Test
    void invalidExampleDraftCannotPublishAnOlderTrace() {
        addTrigger();
        waitForText("#build-state", "Built");
        delayNextTrace();

        examplePayload().fill("[\"Delayed\"]");
        examplePayload().press("Tab");
        page.waitForFunction("() => typeof window.__releaseTrace === 'function'");
        examplePayload().fill("[");
        examplePayload().press("Tab");
        page.evaluate("window.__releaseTrace()");
        openInspectorTab("inspect");
        page.evaluate("""
                () => new Promise(resolve =>
                  requestAnimationFrame(() => requestAnimationFrame(resolve))
                )
                """);

        assertThat(page.locator(".run-result").count()).isZero();
    }

    @Test
    void invalidExampleDraftAbortsTheOlderTraceRequest() {
        addTrigger();
        waitForText("#build-state", "Built");
        delayNextTrace();
        examplePayload().fill("[\"Delayed\"]");
        examplePayload().press("Tab");
        page.waitForFunction("() => typeof window.__releaseTrace === 'function'");

        examplePayload().fill("[");
        examplePayload().press("Tab");
        page.waitForFunction("() => window.railixTraceAborted === true");

        assertThat(page.evaluate("window.railixTraceAborted")).isEqualTo(true);
    }

    @Test
    void obsoletePanelsLifecycleButtonsAndTransportRoutesAreAbsent() {
        assertThat(page.locator(
                ".palette, .diagnostics, #validate, #start, #run, #build, #run-example, #run-all-examples"
        ).count()).isZero();
        assertThat(page.locator("body").textContent())
                .doesNotContain("/api/run", "/v1/run", "instance ", "Changes compile");
    }

    @Test
    void graphLayoutIsDeterministicAcrossReload() {
        createResultJourney();
        page.evaluate("() => state.world.fit()");
        final String before = positions();

        page.reload();
        page.locator(".trigger-node").waitFor();
        waitForText("#build-state", "Built");

        assertThat(positions()).isEqualTo(before);
    }

    @Test
    @Tag("responsive")
    void inspectorFitsDesktopAndMobileViewport() {
        openInspectorTab("overview");
        final double viewport = ((Number) page.evaluate("window.innerWidth")).doubleValue();
        final var box = page.locator("#inspector").boundingBox();

        assertThat(box).isNotNull();
        assertThat(box.x).isGreaterThanOrEqualTo(0);
        assertThat(box.x + box.width).isLessThanOrEqualTo(viewport + 0.5);
    }

    @Test
    @Tag("responsive")
    void canvasZoomControlsFitAndOperateInTheMobileViewport() {
        final double viewport = ((Number) page.evaluate("window.innerWidth")).doubleValue();
        final var box = page.locator(".canvas-tools").boundingBox();
        final String before = page.locator("#zoom-level").textContent();

        page.locator("#zoom-in").click();
        page.waitForFunction("before => document.querySelector('#zoom-level').textContent !== before", before);

        assertThat(box).isNotNull();
        assertThat(box.x).isGreaterThanOrEqualTo(0);
        assertThat(box.x + box.width).isLessThanOrEqualTo(viewport + 0.5);
    }

}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
abstract class RailixCreatorBrowserSupport {
    private static final int VIEWPORT_WIDTH = Integer.getInteger("railix.browser.viewport.width", 1_280);
    private static final String CANVAS_GEOMETRY = """
            JSON.stringify([...document.querySelectorAll('#world-labels > *')].map(element => {
              const box = element.getBoundingClientRect();
              return [element.dataset.nodeId || element.dataset.groupRegionLabel || element.textContent,
                box.x, box.y, box.width, box.height];
            }))
            """;

    @TempDir
    Path directory;

    CreatorServer creator;
    BrowserContext context;
    Page page;
    final List<String> pageErrors = new ArrayList<>();

    private Path template;
    private String templateProject;
    private String templateCreator;
    private Playwright playwright;
    private Browser browser;
    private List<Long> browserBaseline = List.of();
    private final Map<Long, ProcessHandle> playwrightProcesses = new LinkedHashMap<>();

    @BeforeAll
    final void startBrowser(@TempDir final Path template) throws Exception {
        this.template = template;
        final ProcessHandle testProcess = ProcessHandle.current();
        browserBaseline = testProcess.children().map(ProcessHandle::pid).toList();
        try {
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of(
                    "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD",
                    "1"
            )));
            final List<ProcessHandle> drivers = testProcess.children()
                    .filter(process -> !browserBaseline.contains(process.pid()))
                    .toList();
            drivers.forEach(process -> playwrightProcesses.putIfAbsent(process.pid(), process));
            assertThat(drivers).singleElement();
            capturePlaywrightProcesses();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setChannel(System.getenv().getOrDefault("RAILIX_BROWSER_CHANNEL", "chrome"))
                    .setHeadless(true));
            capturePlaywrightProcesses();
            try (CreatorServer ignored = CreatorServer.start(
                    0,
                    template.resolve("project.json"),
                    template.resolve("railix-home")
            )) {
                templateProject = Files.readString(template.resolve("project.json"));
                templateCreator = Files.readString(template.resolve("railix.creator.json"));
            }
        } catch (final Exception | Error failure) {
            try {
                stopBrowser();
            } catch (final RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @AfterAll
    final void stopBrowser() {
        capturePlaywrightProcesses();
        if (playwright == null && playwrightProcesses.isEmpty()) {
            return;
        }
        boolean interrupted = Thread.interrupted();
        final AtomicReference<RuntimeException> failure = new AtomicReference<>();
        final Thread closer = playwright == null
                ? null
                : Thread.ofPlatform().daemon().name("railix-playwright-close").start(() -> {
                    try {
                        playwright.close();
                    } catch (final RuntimeException exception) {
                        failure.set(exception);
                    }
                });
        interrupted |= joinThread(closer, 5);
        BrowserWait processWait = awaitPlaywrightExit(2);
        interrupted |= processWait.interrupted();
        if (!processWait.complete()) {
            processWait = terminatePlaywrightProcesses();
            interrupted |= processWait.interrupted();
        }
        if (closer != null && closer.isAlive()) {
            interrupted |= joinThread(closer, 5);
        }
        capturePlaywrightProcesses();
        RuntimeException cleanup = failure.get();
        if (closer != null && closer.isAlive()) {
            cleanup = merge(cleanup, new IllegalStateException("Playwright close thread did not terminate."));
        }
        if (!processWait.complete() || playwrightProcesses.values().stream().anyMatch(ProcessHandle::isAlive)) {
            cleanup = merge(cleanup, new IllegalStateException("Playwright processes did not terminate."));
        }
        if (cleanup == null) {
            playwright = null;
            browser = null;
            playwrightProcesses.clear();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (cleanup != null) {
            throw cleanup;
        }
    }

    private void capturePlaywrightProcesses() {
        ProcessHandle.current().children()
                .filter(process -> !browserBaseline.contains(process.pid()))
                .forEach(process -> playwrightProcesses.putIfAbsent(process.pid(), process));
        List.copyOf(playwrightProcesses.values()).forEach(process ->
                process.descendants().forEach(descendant ->
                        playwrightProcesses.putIfAbsent(descendant.pid(), descendant)
                )
        );
    }

    private BrowserWait terminatePlaywrightProcesses() {
        boolean interrupted = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            capturePlaywrightProcesses();
            final boolean force = attempt > 0;
            playwrightProcesses.values().stream()
                    .filter(ProcessHandle::isAlive)
                    .toList()
                    .reversed()
                    .forEach(process -> {
                        if (force) {
                            process.destroyForcibly();
                        } else {
                            process.destroy();
                        }
                    });
            final BrowserWait wait = awaitPlaywrightExit(2);
            interrupted |= wait.interrupted();
            if (wait.complete()) {
                return new BrowserWait(true, interrupted);
            }
        }
        return new BrowserWait(false, interrupted);
    }

    private BrowserWait awaitPlaywrightExit(final int seconds) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        boolean interrupted = false;
        capturePlaywrightProcesses();
        while (playwrightProcesses.values().stream().anyMatch(ProcessHandle::isAlive)
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (final InterruptedException ignored) {
                interrupted = true;
            }
            capturePlaywrightProcesses();
        }
        return new BrowserWait(
                playwrightProcesses.values().stream().noneMatch(ProcessHandle::isAlive),
                interrupted
        );
    }

    private static boolean joinThread(final Thread thread, final int seconds) {
        if (thread == null) {
            return false;
        }
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        boolean interrupted = false;
        while (thread.isAlive() && System.nanoTime() < deadline) {
            try {
                TimeUnit.NANOSECONDS.timedJoin(thread, Math.max(1, deadline - System.nanoTime()));
            } catch (final InterruptedException ignored) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    private static RuntimeException merge(
            final RuntimeException failure,
            final RuntimeException next
    ) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    private record BrowserWait(boolean complete, boolean interrupted) {
    }

    @BeforeEach
    final void openCreator() throws Exception {
        pageErrors.clear();
        Files.createDirectories(directory.resolve("railix-home/icons"));
        // CSS-specific DOM assertions use an explicit renderer. Fresh-settings tests cover the Canvas default.
        Files.writeString(directory.resolve("railix-home/creator.settings.json"), """
                {"theme":"","theme_variant":"hq","reduced_motion":false,"effects":true,
                 "effects_volume":0.35,"music_volume":0.25,"music_enabled":true,"music":"","sounds":{}}
                """);
        Files.writeString(directory.resolve("railix-home/icons/bolt.svg"), "<svg/>");
        Files.writeString(directory.resolve("project.json"), templateProject);
        Files.writeString(directory.resolve("railix.creator.json"), templateCreator);
        copyTree(template.resolve(".railix/build"), directory.resolve(".railix/build"));
        creator = CreatorServer.start(0, directory.resolve("project.json"), directory.resolve("railix-home"));
        context = browser.newContext(new Browser.NewContextOptions().setDeviceScaleFactor(
                Double.parseDouble(System.getProperty("railix.browser.dpr", "1"))).setViewportSize(
                VIEWPORT_WIDTH,
                VIEWPORT_WIDTH <= 560 ? 720 : 800
        ));
        page = context.newPage();
        page.onPageError(error -> pageErrors.add(error));
        page.navigate(creator.baseUri().toString());
        waitForText("#build-state", "Built");
    }

    @AfterEach
    final void closeCreator() {
        try {
            if (context != null) {
                context.close();
            }
        } finally {
            if (creator != null) {
                creator.close();
            }
        }
    }

    void createResultJourney() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"text\":\"Hello RAILIX\"}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        choosePath("value-0-source", "payload", "text");
        waitForText("#build-state", "Built");
    }

    void createLowercaseJourney() {
        openProject("""
                {"format":1,"id":"lowercase-journey","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"no-arguments","payload":[],"context":{"payload":{"text":"Hello RAILIX"}}},
                    {"name":"one-argument","payload":["railix"],"context":{}},
                    {"name":"multiple-arguments","payload":["hello","railix"],"context":{}}]},
                  {"id":"result","use":"railix.field-manipulation","inputs":{"field":["context","result"],
                    "value":[{"option":"field","inputs":{"source":["context","payload","text"]}}],"steps":[]}},
                  {"id":"lowercase","use":"railix.field-manipulation","inputs":{"field":["context","payload","text"],
                    "value":[{"option":"current","inputs":{}}],"steps":[{"use":"text.lowercase","inputs":{}}]}}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"lowercase"},
                  {"from":"lowercase.next","to":"result"},{"from":"result.next","to":"end"}]}
                """);
        selectWorldNode("lowercase");
    }

    void createPrimitiveResult(
            final String example,
            final String search,
            final String primitive
    ) {
        preparePrimitiveSearch(example, search);
        page.locator("#steps-options [data-add-nested='" + primitive + "']").click();
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();
    }

    void createPrimitivePreviewAndResult(
            final String example,
            final String search,
            final String primitive,
            final String preview
    ) {
        preparePrimitiveSearch(example, search);
        page.locator("#steps-options [data-add-nested='" + primitive + "']").click();
        waitForText("#build-state", "Built");
        page.locator("[data-preview-stage='0']").waitFor();
        assertThat(page.locator("[data-preview-stage='0']").textContent()).isEqualTo(preview);
    }

    RailixValue runResult() {
        return runResult(0);
    }

    RailixValue runResult(final int index) {
        selectTrigger();
        openInspectorTab("examples");
        page.locator("[data-select-example='" + index + "']").click();
        openInspectorTab("inspect");
        page.locator(".run-result").waitFor();
        final RailixValue.ObjectValue response = (RailixValue.ObjectValue) (
                (RailixJson.Parsed) RailixJson.parse(page.locator(".run-result").textContent())
        ).value();
        return ((RailixValue.ObjectValue) response.values().get("context"))
                .values()
                .get("result");
    }

    void createFallibleNumberJourney(final String value) {
        preparePrimitiveSearch(
                "{\"payload\":{\"value\":\"" + value + "\"}}",
                "to number"
        );
        page.locator("#steps-options [data-add-nested='text.to-number']").click();
        waitForText("#build-state", "Built");
    }

    void createPercentileJourney(final String values, final String percentile) {
        preparePrimitiveSearch(
                "{\"payload\":{\"value\":" + values + "}}",
                "percentile"
        );
        page.locator("#steps-options [data-add-nested='list.percentile']").click();
        waitForText("#build-state", "Built");
        if (!percentile.isEmpty()) {
            page.locator("#steps-0-percentile-value").fill(percentile);
            page.locator("#steps-0-percentile-value").press("Tab");
        }
        waitForText("#build-state", "Built");
    }

    void preparePrimitiveSearch(final String example, final String search) {
        openProject("""
                {"format":1,"id":"primitive-search","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"no-arguments","payload":[],"context":%s},
                    {"name":"one-argument","payload":["railix"],"context":{}},
                    {"name":"multiple-arguments","payload":["hello","railix"],"context":{}}
                  ]},
                  {"id":"result","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],
                    "value":[{"option":"field","inputs":{"source":["context","payload","value"]}}],
                    "steps":[]
                  }}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"result"},
                  {"from":"result.next","to":"end"}
                ]}
                """.formatted(example));
        selectWorldNode("result");
        page.waitForFunction("() => Boolean(document.querySelector('#preview-source'))");
        final Locator summary = page.locator(".example-value:has(#preview-source) > summary");
        if (summary.count() > 0) summary.click();
        page.locator("#preview-source").waitFor();
        page.locator("#steps-search").fill(search);
    }

    void prepareLiteralRefinementSearch(final String value, final String search) {
        addTrigger();
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("literal");
        page.waitForFunction("() => state.build === 'Built' && !state.pendingProject && !state.writeActive && !state.editorController");
        page.locator("#value-0-literal-value").fill(value);
        assertThat(page.locator("#value-0-literal-value").inputValue()).isEqualTo(value);
        page.locator("#value-0-literal-value").press("Tab");
        page.locator("#steps-search").fill(search);
    }

    void awaitRejectedLiteral(final String value, final String search) {
        // Megabyte literals exceed generated Step code size; compatibility must still use the retained draft.
        page.waitForFunction("""
                () => state.build === 'Not built' && !state.pendingProject && !state.writeActive
                  && state.diagnostics.some(item => item.code === 'PROJECT_APPLICATION_STEP_LIMIT')
                """);
        selectWorldNode(page.locator("#inspector").getAttribute("data-selection"));
        page.waitForFunction("() => state.traceCases.length > 0 && !state.traceController");
        assertThat(page.locator("#value-0-literal-value").evaluate("""
                (field, expected) => JSON.stringify(parseExact(field.value)) === JSON.stringify(parseExact(expected))
                """, value)).as("Rejected literal remains intact in the editor draft").isEqualTo(true);
        page.locator("#steps-search").fill(search);
    }

    static String canonicalJsonBytes(final int bytes) {
        final int fixedBytes = 1_048;
        return "{\"number\":1e1023,\"padding\":\""
                + "a".repeat(bytes - fixedBytes)
                + "\"}";
    }

    void prepareOperationAfterListSize() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"value\":[1,2]}}");
        exampleContext().press("Tab");
        waitForText("#build-state", "Built");
        addManipulationAfterSelected();
        chooseCustomField("payload", "size");
        page.locator("#value-0-option").selectOption("field");
        choosePath("value-0-source", "payload", "value");
        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();
        page.locator("#steps-search").fill("size");
        page.locator("#steps-options [data-add-nested='list.size']").click();
        waitForText("#build-state", "Built");
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        choosePath("value-0-source", "payload", "size");
        waitForText("#build-state", "Built");
        page.locator("#preview-source").waitFor();
    }

    void prepareDateNotChain(final int notCount) {
        preparePrimitiveSearch("{\"payload\":{\"value\":0}}", "utc");
        page.locator("#steps-options [data-add-nested='date.is-utc-millis']").click();
        for (int index = 0; index < notCount; index++) {
            page.locator("#steps-search").fill("not");
            page.locator("#steps-options [data-add-nested='boolean.not']").click();
        }
        waitForText("#build-state", "Built");
    }

    void prepareRejectedCurrentCandidate() {
        addTrigger();
        exampleContext().fill("{\"payload\":{\"value\":\"\"}}");
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        choosePath("field", "payload", "value");
        page.locator("#value-0-when-new-predicate-search").fill("equals");
        page.locator("[data-candidate-index='0'] [data-add-predicate='value.equals']").click();
        page.locator("#value-0-when-all-0-0-expected-value").fill("\"\"");
        page.locator("#value-0-when-all-0-0-expected-value").press("Tab");
        fillStepSearch("#value-0-when-all-0-search", "not");
        page.locator("#value-0-when-all-0-options [data-add-nested='boolean.not']").click();
        addLiteralCandidate("\"fallback\"");
        waitForText("#build-state", "Built");
    }

    void addLiteralCandidate(final String value) {
        page.locator("#value-candidate-search").fill("literal");
        page.locator("[data-add-candidate='literal']").click();
        final int index = page.locator(".candidate[data-candidate-index]").count() - 1;
        page.locator("#value-" + index + "-literal-value").fill(value);
        page.locator("#value-" + index + "-literal-value").press("Tab");
    }

    void prepareMissingResultCandidate() {
        addTrigger();
        exampleContext().fill("{\"payload\":{}}");
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();
        page.locator("#new-path-field").fill("value");
        page.locator("#append-path-field").click();
        page.locator("#apply-path").click();
        addLiteralCandidate("\"RAILIX\"");
    }

    void openSourceAfterSparseArrayWrite() {
        addTrigger();
        exampleContext().fill("{\"payload\":{}}");
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        chooseCustomPath("payload", "people", 2);
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill("\"Ada\"");
        page.locator("#value-0-literal-value").press("Tab");
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        page.locator("#value-0-source-path").click();
        page.locator("[data-path-depth='0']").click();
        page.locator("[data-path-part='payload']").click();
        page.locator("[data-path-part='people']").click();
    }

    void openFieldBuilder(final String example, final String... parts) {
        addTrigger();
        exampleContext().fill(example);
        exampleContext().press("Tab");
        addManipulationAfterSelected();
        page.locator("#field-path").click();
        page.locator("[data-path-depth='0']").click();
        for (final String part : parts) {
            page.locator("[data-path-part='" + part + "']").click();
        }
    }

    void createLiteralResult(
            final String literal,
            final Object[] target,
            final String... source
    ) {
        addTrigger();
        addManipulationAfterSelected();
        chooseCustomPath(target);
        page.locator("#value-0-option").selectOption("literal");
        page.locator("#value-0-literal-value").fill(literal);
        page.locator("#value-0-literal-value").press("Tab");
        addManipulationAfterSelected();
        choosePath("field", "result");
        page.locator("#value-0-option").selectOption("field");
        choosePath("value-0-source", source);
        waitForText("#build-state", "Built");
        selectTrigger();
        page.locator(".run-result").waitFor();
    }

    @SuppressWarnings("unchecked")
    List<String> stepIds() {
        return (List<String>) page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).project.nodes
                  .filter(node => state.definitions.get(node.use)?.kind === 'step').map(node => node.id)
                """);
    }

    String createGroup(final String... steps) {
        assertThat(steps).isNotEmpty();
        selectWorldNode(steps[0]);
        openInspectorTab("appearance");
        page.locator("[data-inspector-mode=groups]").click();
        clickAndWaitForCreatorSave(() -> page.locator("#new-group").click());
        final String group = String.valueOf(page.evaluate("""
                async () => (await (await fetch('/api/project')).json()).creator.groups.at(-1).id
                """));
        page.locator("[data-inspector-mode=inspect]").click();
        openInspectorTab("appearance");
        page.locator("#choose-group").click();
        clickAndWaitForCreatorSave(() -> page.locator("[data-assign-group='" + group + "']").click());
        for (int index = 1; index < steps.length; index++) {
            final String step = steps[index];
            selectWorldNode(step);
            openInspectorTab("appearance");
            page.locator("#choose-group").click();
            clickAndWaitForCreatorSave(() -> page.locator("[data-assign-group='" + group + "']").click());
        }
        page.locator("[data-inspector-mode=groups]").click();
        page.evaluate("() => state.world.fit()");
        awaitScene();
        return group;
    }

    void clickAndWaitForCreatorSave(final Runnable click) {
        final String revision = page.locator("#graph").getAttribute("data-scene-revision");
        final var response = page.waitForResponse(candidate ->
                candidate.url().endsWith("/api/creator")
                        && "PATCH".equals(candidate.request().method()), click);
        assertThat(response.status()).isEqualTo(200);
        page.waitForFunction("revision => document.querySelector('#graph').dataset.sceneRevision !== revision", revision);
        awaitScene();
    }

    void selectWorldNode(final String id) {
        if (page.locator("#inspector").isVisible()) page.locator("#close-inspector").click();
        page.evaluate("id => void state.world.focus(id)", id);
        awaitScene();
        selectWorldNode(page.locator("[data-select-node='" + id + "']"));
        assertThat(page.locator("#inspector").isVisible()).isTrue();
        page.locator("[data-inspector-mode='inspect']").click();
    }

    private void selectWorldNode(final Locator target) {
        final String id = target.getAttribute("data-select-node");
        final String selection = "node=" + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8);
        try {
            final var response = page.waitForResponse(candidate -> {
                final var uri = java.net.URI.create(candidate.url());
                return "GET".equals(candidate.request().method()) && "/api/editor".equals(uri.getPath())
                        && uri.getRawQuery() != null && List.of(uri.getRawQuery().split("&")).contains(selection);
            }, target::click);
            assertThat(response.status()).as("Editor selection for %s", id).isEqualTo(200);
            response.finished();
            // Reselecting the same node must not match the previous Inspector while its editor request is pending.
            page.waitForFunction("""
                    id => !state.editorController
                      && document.querySelector('#inspector')?.dataset.selection === id
                      && document.querySelector('#inspector [data-inspector-mode="inspect"]')?.classList.contains('active')
                      && document.querySelector(`[data-select-node="${CSS.escape(id)}"]`)?.getAttribute('aria-pressed') === 'true'
                    """, id);
        } catch (final TimeoutError timeout) {
            String diagnostic = "<browser snapshot unavailable>";
            try {
                diagnostic = String.valueOf(page.evaluate("""
                        () => JSON.stringify({
                          selection: state.selection,
                          inspectorSelection: document.querySelector('#inspector')?.dataset.selection,
                          inspectorMode: state.inspectorMode,
                          build: state.build,
                          buildText: document.querySelector('#build-state')?.textContent,
                          projectVersion: state.projectVersion,
                          creatorVersion: state.creatorVersion,
                          editorController: {
                            active: Boolean(state.editorController),
                            aborted: state.editorController?.signal.aborted ?? null,
                            request: state.editorRequest
                          },
                          writeActive: state.writeActive,
                          pendingWrite: Boolean(state.pendingWrite),
                          pendingProject: Boolean(state.pendingProject),
                          localDiagnostics: JSON.stringify(state.localDiagnostics).slice(0, 1000),
                          projectChanges: JSON.stringify(documentChanges(state.builtProject, state.project)).slice(0, 1500)
                        })
                        """));
            } catch (final RuntimeException snapshotFailure) {
                timeout.addSuppressed(snapshotFailure);
            }
            throw new AssertionError("Editor selection timed out for '" + id + "': " + diagnostic
                    + "; browser errors: " + pageErrors, timeout);
        }
    }

    void waitForCoverage(final String id, final String coverage) {
        page.waitForFunction("""
                expected => document.querySelector(`[data-node-id='${expected.id}']`)?.dataset.coverage === expected.coverage
                """, Map.of("id", id, "coverage", coverage));
    }

    String creatorMetadata() {
        return String.valueOf(page.evaluate("""
                async () => JSON.stringify((await (await fetch('/api/project')).json()).creator)
                """));
    }

    static String filterProject() {
        return branchProject("filter-browser", "filter", "railix.filter", """
                [{
                  "option":"field","inputs":{"field":["context","payload","value"]},
                  "when":{"transforms":[],"all":[[
                    {"use":"value.equals","inputs":{"expected":"allow"}}
                  ]]}
                }]
                """);
    }

    static String choiceProject() {
        return branchProject("choice-browser", "choice", "railix.choice", """
                [[{
                  "option":"field","inputs":{"field":["context","payload","value"]},
                  "when":{"transforms":[],"all":[[
                    {"use":"value.equals","inputs":{"expected":"allow"}}
                  ]]}
                }]]
                """);
    }

    static String branchProject(
            final String projectId,
            final String branchId,
            final String branchStep,
            final String conditions
    ) {
        return """
                {"format":1,"id":"%s","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"match","payload":[],"context":{"payload":{"value":"allow"}}},
                    {"name":"otherwise","payload":[],"context":{"payload":{"value":"deny"}}}
                  ]},
                  {"id":"%s","use":"%s","inputs":{"conditions":%s}},
                  {"id":"matched","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],
                    "value":[{"option":"literal","inputs":{"literal":"matched"}}],"steps":[]}},
                  {"id":"otherwise","use":"railix.field-manipulation","inputs":{
                    "field":["context","result"],
                    "value":[{"option":"literal","inputs":{"literal":"otherwise"}}],"steps":[]}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"%s"},
                  {"from":"%s.match","to":"matched"},
                  {"from":"%s.otherwise","to":"otherwise"},
                  {"from":"matched.next","to":"end"},
                  {"from":"otherwise.next","to":"end"}
                ]}
                """.formatted(
                        projectId,
                        branchId,
                        branchStep,
                        conditions,
                        branchId,
                        branchId,
                        branchId
                );
    }

    static String deepBranchProject(final int steps) {
        final StringBuilder nodes = new StringBuilder("""
                {"format":1,"id":"deep-branch","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[
                    {"name":"example","payload":[],"context":{"payload":{}}}]},
                  {"id":"filter","use":"railix.filter","inputs":{"conditions":[]}}
                """);
        final StringBuilder links = new StringBuilder("""
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"filter"},
                  {"from":"filter.match","to":"step-0"},
                  {"from":"filter.otherwise","to":"end"}
                """);
        for (int index = 0; index < steps; index++) {
            nodes.append(",{\"id\":\"step-").append(index)
                    .append("\",\"use\":\"railix.field-manipulation\",\"inputs\":{}}");
            links.append(",{\"from\":\"step-").append(index).append(".next\",\"to\":\"")
                    .append(index + 1 < steps ? "step-" + (index + 1) : "end")
                    .append("\"}");
        }
        return nodes.append(links).append("]}").toString();
    }

    static String fourStepProject() {
        return """
                {"format":1,"id":"four-steps","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"example","payload":[],"context":{"payload":{}}
                  }]},
                  {"id":"one","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","one"],
                    "value":[{"option":"literal","inputs":{"literal":1}}],"steps":[]}},
                  {"id":"two","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","two"],
                    "value":[{"option":"literal","inputs":{"literal":2}}],"steps":[]}},
                  {"id":"three","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","one"],
                    "value":[{"option":"literal","inputs":{"literal":1}}],"steps":[]}},
                  {"id":"four","use":"railix.field-manipulation","inputs":{
                    "field":["context","payload","two"],
                    "value":[{"option":"literal","inputs":{"literal":2}}],"steps":[]}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"one"},
                  {"from":"one.next","to":"two"},
                  {"from":"two.next","to":"three"},
                  {"from":"three.next","to":"four"},
                  {"from":"four.next","to":"end"}
                ]}
                """;
    }

    static String nestedSwitchProject() {
        return """
                {"format":1,"id":"nested-switch","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli","inputs":{},"examples":[{
                    "name":"example","payload":[],"context":{"payload":{"value":"allow"}}
                  }]},
                  {"id":"choice","use":"railix.choice","inputs":{"conditions":[[{
                    "option":"field","inputs":{"field":["context","payload","value"]},
                    "when":{"transforms":[],"all":[[{
                      "use":"value.equals","inputs":{"expected":"allow"}
                    }]]}
                  }]]}},
                  {"id":"switch","use":"railix.switch","inputs":{"cases":[
                    {"outcome":"one","option":"literal","inputs":{"value":1},"when":{"transforms":[],"all":[]}},
                    {"outcome":"two","option":"literal","inputs":{"value":2},"when":{"transforms":[],"all":[]}},
                    {"outcome":"three","option":"literal","inputs":{"value":3},"when":{"transforms":[],"all":[]}},
                    {"outcome":"four","option":"literal","inputs":{"value":4},"when":{"transforms":[],"all":[]}}
                  ]}}
                ],"links":[
                  {"from":"app.start","to":"command"},
                  {"from":"command.next","to":"choice"},
                  {"from":"choice.match","to":"switch"},
                  {"from":"choice.otherwise","to":"end"},
                  {"from":"switch.one","to":"end"},
                  {"from":"switch.two","to":"end"},
                  {"from":"switch.three","to":"end"},
                  {"from":"switch.four","to":"end"},
                  {"from":"switch.otherwise","to":"end"}
                ]}
                """;
    }

    static StepDefinition authoredBooleanStep() {
        return StepDefinition.named("test.authored-boolean", "1")
                .searchTerms("authored", "routes")
                .receive("value", ValueShape.ANY)
                .returns("value", ValueShape.BOOLEAN)
                .input("cases", StepDefinition.Input.candidates(
                        StepDefinition.Input.option("literal")
                                .input("value", StepDefinition.Input.json(ValueShape.ANY)
                                        .defaultValue(RailixValue.nullValue()))
                                .fromOwned("value")
                ).withAuthoredOutcomes())
                .run(MatcherConformanceSteps.AuthoredTrue.class);
    }

    void addTrigger() {
        clickOverview("#add-trigger");
        page.locator("#step-search").fill("cli");
        page.locator("[data-add-step='railix.trigger.cli']").click();
        openInspectorTab("inspect");
    }

    void addFilterAfterTrigger() {
        addTrigger();
        openInspectorTab("inspect");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("filter");
        page.locator("[data-add-step='railix.filter']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
    }

    void addChoiceAfterTrigger() {
        addTrigger();
        openInspectorTab("inspect");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("choice");
        page.locator("[data-add-step='railix.choice']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
    }

    void addSwitchAfterTrigger() {
        addTrigger();
        openInspectorTab("inspect");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("switch");
        page.locator("[data-add-step='railix.switch']").click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
    }

    void prepareSizeChoiceMatcher() {
        prepareSizeChoiceMatcher(1);
    }

    void prepareSizeChoiceMatcher(final int size) {
        openProject(branchProject("choice-size", "choice", "railix.choice", """
                [[{
                  "option":"literal","inputs":{"value":%s},
                  "when":{"transforms":[],"all":[]}
                }]]
        """.formatted(numberList(size))));
        selectWorldNode("choice");
        page.locator(".condition-transforms summary").first().click();
        final Locator search = page.locator("[data-matcher-group='0'] .condition-transforms [data-step-query]");
        search.fill("size");
        page.locator("[data-matcher-group='0'] .condition-transforms [data-add-nested='list.size']").click();
        waitForText("#build-state", "Not built");
    }

    void addSizeBounds(final int size) {
        prepareSizeChoiceMatcher(size);
        final Locator search = page.locator("[data-matcher-group='0'] [data-predicate-query]");
        search.fill("gt");
        page.locator("[data-matcher-group='0'] [data-add-predicate='number.greater-than']").click();
        page.locator("[data-condition-predicate='0'] [data-input-json]").fill("1");
        page.locator("[data-condition-predicate='0'] [data-input-json]").press("Tab");
        page.locator(".condition-add:not([open]) summary").first().click();
        search.fill("lt");
        page.locator("[data-matcher-group='0'] [data-add-predicate='number.less-than']").click();
        page.locator("[data-condition-predicate='1'] [data-input-json]").fill("5");
        page.locator("[data-condition-predicate='1'] [data-input-json]").press("Tab");
        waitForText("#build-state", "Built");
    }

    static String numberList(final int size) {
        return size == 0 ? "[]" : "[" + "0,".repeat(size - 1) + "0]";
    }

    void addNestedFilterToMatchRoute() {
        addFilterAfterTrigger();
        clickOverview("[data-add-outcome='match']");
        page.locator("#step-search").fill("filter");
        page.locator("[data-add-step='railix.filter']").click();
        waitForText("#build-state", "Built");
    }

    void addStepToOtherwiseBranch() {
        addFilterAfterTrigger();
        clickOverview("[data-add-outcome='otherwise']");
        page.locator("#step-search").fill("field");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        waitForText("#build-state", "Built");
    }


    void openProject(final String source) {
        final Number status = (Number) page.evaluate("""
                source => fetch('/api/project', {
                  method: 'POST',
                  headers: mutationHeaders(),
                  body: source
                }).then(response => response.status)
                """, source);
        assertThat(status.intValue()).isEqualTo(200);
        page.reload();
        page.waitForFunction("""
                () => document.querySelector('#build-state')?.textContent !== 'Loading'
                """);
        assertThat(page.locator("#build-state").textContent())
                .as("Creator state; browser errors: %s; inspector: %s", pageErrors, page.locator("#inspector").textContent())
                .isEqualTo("Running");
    }

    static void copyTree(final Path source, final Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (final Path path : paths.toList()) {
                final Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination);
                }
            }
        }
    }

    void delayNextProjectWrite() {
        page.evaluate("""
                () => {
                  const original = window.fetch.bind(window);
                  let delayed = false;
                  let release = null;
                  window.__railixProjectWriteStarted = false;
                  window.__railixReleaseProjectWrite = () => {
                    const current = release;
                    release = null;
                    current?.();
                  };
                  window.fetch = (input, options = {}) => {
                    const url = typeof input === 'string' ? input : input.url;
                    if (!delayed && url.endsWith('/api/project') && options.method === 'PATCH') {
                      delayed = true;
                      window.__railixProjectWriteStarted = true;
                      return new Promise((resolve, reject) => {
                        release = () => original(input, options).then(resolve, reject);
                      });
                    }
                    return original(input, options);
                  };
                }
                """);
    }

    void delayFirstProjectWriteAndRecordIds() {
        page.evaluate("""
                () => {
                  const request = window.fetch.bind(window);
                  window.__railixProjectWrites = [];
                  window.__railixProjectWriteStarted = false;
                  window.fetch = (input, options = {}) => {
                    const url = typeof input === 'string' ? input : input.url;
                    if (!url.endsWith('/api/project') || options.method !== 'PATCH') {
                      return request(input, options);
                    }
                    window.__railixProjectWrites.push(JSON.parse(options.body).changes.id);
                    if (window.__railixProjectWrites.length > 1) {
                      return request(input, options);
                    }
                    window.__railixProjectWriteStarted = true;
                    return new Promise((resolve, reject) => {
                      window.__railixReleaseProjectWrite = () =>
                        request(input, options).then(resolve, reject);
                    });
                  };
                }
                """);
    }

    void delayNextTrace() {
        page.evaluate("""
                () => {
                  const request = window.fetch.bind(window);
                  let delayed = false;
                  window.__traceStarted = false;
                  window.__traceCompleted = false;
                  window.railixTraceAborted = false;
                  window.__releaseTrace = null;
                  window.fetch = (input, options = {}) => {
                    const url = typeof input === 'string' ? input : input.url;
                    if (delayed || !(url.endsWith('/view') || url.includes('/steps/'))) {
                      return request(input, options);
                    }
                    delayed = true;
                    window.__traceStarted = true;
                    return new Promise((resolve, reject) => {
                      const abort = () => {
                        window.railixTraceAborted = true;
                        window.__releaseTrace = () => {};
                        reject(new DOMException('Aborted', 'AbortError'));
                      };
                      if (options.signal?.aborted) {
                        abort();
                        return;
                      }
                      options.signal?.addEventListener('abort', abort, {once: true});
                      window.__releaseTrace = () => {
                        options.signal?.removeEventListener('abort', abort);
                        request(input, options).then(response => {
                          window.__traceCompleted = true;
                          resolve(response);
                        }, reject);
                      };
                    });
                  };
                }
                """);
    }

    String applicationPid() {
        return String.valueOf(((Number) page.evaluate(
                "async () => (await (await fetch('/api/application')).json()).pid"
        )).longValue());
    }

    void addManipulationAfterSelected() {
        openInspectorTab("inspect");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill("field");
        page.locator("[data-add-step='railix.field-manipulation']").click();
        openInspectorTab("inspect");
    }

    void selectTrigger() {
        if (page.locator("#inspector").isVisible()) page.locator("#close-inspector").click();
        page.evaluate("() => state.world.fit()");
        awaitScene();
        final String id = String.valueOf(page.evaluate(
                "() => state.world.scene.nodes.find(node => node.kind === 'trigger').id"));
        final Locator trigger = page.locator("[data-select-node='" + id + "']");
        if (trigger.count() == 0) {
            selectWorldNode(id);
        } else {
            selectWorldNode(trigger);
            openInspectorTab("overview");
            page.locator("[data-inspector-mode=inspect]").click();
        }
    }

    void choosePath(final String target, final String... parts) {
        final Locator identified = page.locator("#" + target + "-path");
        final Locator picker = identified.count() == 1
                ? identified
                : page.locator("[data-input-name='" + target + "'] .path-button");
        picker.click();
        page.locator("[data-path-depth='0']").click();
        for (final String part : parts) {
            page.locator("[data-path-part='" + part + "']").click();
        }
        page.locator("#apply-path").click();
    }

    void chooseCustomField(final String... fields) {
        chooseCustomPath((Object[]) fields);
    }

    void chooseCustomPath(final Object... parts) {
        chooseCustomPathFor("field", parts);
    }

    void chooseCustomPathFor(final String target, final Object... parts) {
        final Locator identified = page.locator("#" + target + "-path");
        final Locator picker = identified.count() == 1
                ? identified
                : page.locator("[data-input-name='" + target + "'] .path-button");
        picker.click();
        page.locator("[data-path-depth='0']").click();
        for (final Object part : parts) {
            if (part instanceof Number number) {
                page.locator("#new-path-index").fill(number.toString());
                page.locator("#append-path-index").click();
            } else {
                page.locator("#new-path-field").fill(part.toString());
                page.locator("#append-path-field").click();
            }
        }
        page.locator("#apply-path").click();
    }

    void openInspectorTab(final String mode) {
        if (!page.locator("#inspector").isVisible()) page.locator("#graph").press("e");
        page.locator("[data-inspector-mode='" + mode + "']").click();
    }

    void clickOverview(final String selector) {
        openInspectorTab("overview");
        page.locator(selector).click();
    }

    void clearWorldSelection() {
        if (page.locator("#inspector").isVisible()) page.locator("#graph").press("Escape");
        page.locator("#graph").press("Escape");
    }

    void fillStepSearch(final String selector, final String text) {
        final Locator field = page.locator(selector);
        for (final Locator details : field.locator("xpath=ancestor::details").all()) {
            if (details.getAttribute("open") == null) details.locator(":scope > summary").click();
        }
        field.fill(text);
    }

    void openInspectorSection(final String summary) {
        openInspectorTab("inspect");
        final Locator section = page.locator("#inspector details:has(> summary:text-is('" + summary + "'))");
        section.waitFor();
        if (section.getAttribute("open") == null) {
            section.locator(":scope > summary").click();
        }
    }

    Locator examplePayload() {
        if (page.locator("#example-payload").count() == 0) {
            openInspectorTab("examples");
        }
        return page.locator("#example-payload");
    }

    Locator exampleContext() {
        if (page.locator("#example-context").count() == 0) {
            openInspectorTab("examples");
        }
        return page.locator("#example-context");
    }

    Locator presentationName() {
        if (page.locator("#presentation-name").count() == 0) {
            openInspectorTab("appearance");
        }
        return page.locator("#presentation-name");
    }

    void prepareTextPayloadTrigger() {
        openProject("""
                {"format":1,"id":"text-payload","nodes":[
                  {"id":"app","use":"railix.app","inputs":{}},
                  {"id":"command","use":"railix.trigger.cli",
                    "inputs":{"target":["context","payload","text"]},"examples":[
                      {"name":"uppercase","payload":"RAILIX","context":{}},
                      {"name":"mixed-case","payload":"Railix","context":{}},
                      {"name":"lowercase","payload":"railix","context":{}}
                    ]}
                ],"links":[{"from":"app.start","to":"command"},{"from":"command.next","to":"end"}]}
                """);
        selectWorldNode("command");
    }

    String addGraphPrimitive(final String payload, final String query, final String id) {
        addTrigger();
        chooseCustomPathFor("target", "payload", "value");
        waitForText("#build-state", "Built");
        openInspectorTab("examples");
        replaceExamplePayloads(payload, payload, payload);
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        clickOverview("#add-next-step");
        page.locator("#step-search").fill(query);
        final Locator option = page.locator("[data-add-step='" + id + "']");
        option.waitFor();
        assertThat(option.count())
                .as("Catalog options: %s; browser errors: %s", page.locator("#step-options").textContent(), pageErrors)
                .isEqualTo(1);
        option.click();
        waitForText("#build-state", "Built");
        openInspectorTab("inspect");
        return page.locator(".step-node.selected").getAttribute("data-node-id");
    }

    void replaceExamplePayloads(final String... payloads) {
        assertThat(page.locator("[data-select-example]:not([data-select-example='-1'])").count()).isEqualTo(payloads.length);
        for (int index = 0; index < payloads.length; index++) {
            page.locator("[data-select-example='" + index + "']").click();
            examplePayload().fill(payloads[index]);
            examplePayload().press("Tab");
        }
    }

    String positions() {
        awaitScene();
        return (String) page.evaluate("""
                () => JSON.stringify({
                  nodes: state.world.scene.nodes.map(node => [node.id, node.x, node.y, node.width, node.height]),
                  links: state.world.scene.links.map(link => [link.id, link.from, link.to, link.points])
                })
                """);
    }

    String canvasStyle() {
        awaitScene();
        return (String) page.evaluate("() => " + CANVAS_GEOMETRY);
    }

    void waitForCanvasChange(final String before) {
        page.waitForFunction("before => " + CANVAS_GEOMETRY + " !== before", before);
    }

    void awaitScene() {
        page.waitForFunction("() => state.world && document.querySelector('#graph').dataset.cameraMoving !== 'true'");
        page.evaluate("""
                async () => {
                  await state.world.refresh();
                  await new Promise(resolve => requestAnimationFrame(resolve));
                }
                """);
        page.waitForFunction("""
                () => state.world?.scene?.nodes.length > 0
                  && document.querySelector('#graph').dataset.cameraMoving !== 'true'
                  && document.querySelector('#graph').dataset.sceneRevision === String(state.world.scene.revision)
                  && document.querySelectorAll('#world-labels > *').length > 0
                """);
        // A focus refresh can start a camera flight; fetch its final viewport before measuring it.
        page.evaluate("""
                async () => {
                  await state.world.refresh();
                  await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
                }
                """);
    }

    @SuppressWarnings("unchecked")
    List<String> branchOutcomes() {
        awaitScene();
        return (List<String>) page.evaluate("""
                () => state.world.scene.links.map(link => link.outcome)
                  .filter(outcome => outcome && !['start', 'next'].includes(outcome))
                """);
    }

    void waitForText(final String selector, final String text) {
        final String expected = "#build-state".equals(selector) && "Built".equals(text) ? "Running" : text;
        try {
            page.waitForFunction(
                    """
                    expected => {
                      const actual = document.querySelector(expected.selector)?.textContent;
                      return actual === expected.text || expected.selector === '#build-state' && actual === 'Unavailable';
                    }
                    """,
                    Map.of("selector", selector, "text", expected)
            );
            final String actual = page.locator(selector).textContent();
            if (!expected.equals(actual)) {
                throw new AssertionError("Creator cannot become " + expected + ": " + actual
                        + ". Browser errors: " + pageErrors
                        + ". Inspector: " + page.locator("#inspector").textContent());
            }
        } catch (final TimeoutError timeout) {
            final String actual = page.locator(selector).count() == 0
                    ? "<missing>"
                    : page.locator(selector).textContent();
            throw new AssertionError(
                    "Expected " + selector + " to contain '" + expected + "' but was '" + actual
                            + "'. Browser errors: " + pageErrors
                            + ". Inspector: " + page.locator("#inspector").textContent(),
                    timeout
            );
        }
    }

    static void stopProcess(final long pid) {
        final ProcessHandle process = ProcessHandle.of(pid).orElseThrow();
        process.destroyForcibly();
        process.onExit().orTimeout(2, java.util.concurrent.TimeUnit.SECONDS).join();
    }
}
