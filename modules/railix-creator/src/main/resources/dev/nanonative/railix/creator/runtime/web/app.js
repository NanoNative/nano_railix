let refreshTimer = 0;
let latestWorkspaceSnapshot = null;
let latestHistoricalRunsQuery = null;
let selectedHistoricalRunKey = "";
let selectedHistoricalRunDetails = null;
let selectedAuthoringSpecPath = "";
let latestAuthoringSpec = null;
let authoringSpecDirty = false;
let historicalRunQuery = {
  appId: "",
  flowId: "",
  outcome: "",
  summaryReadStatus: "",
  limit: "5"
};

async function loadWorkspace() {
  const [response, remoteRequestResponse] = await Promise.all([
    fetch("/api/workspace"),
    fetch("/api/remote-execution/requests?limit=8")
  ]);
  if (!response.ok) {
    throw new Error("Workspace request failed with status " + response.status);
  }
  if (!remoteRequestResponse.ok) {
    throw new Error("Remote request history failed with status " + remoteRequestResponse.status);
  }
  const workspace = await response.json();
  workspace.remoteExecutionRequests = await remoteRequestResponse.json();
  return workspace;
}

async function startLiveSession(request) {
  const response = await fetch("/api/control/sessions", {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(request)
  });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(body.message || ("Launch request failed with status " + response.status));
  }
  return body;
}

async function loadAuthoringSpec(path) {
  const query = new URLSearchParams({ path });
  const response = await fetch(`/api/authoring-spec?${query.toString()}`);
  const body = await response.json();
  if (!response.ok) {
    throw new Error(body.message || ("Authoring spec request failed with status " + response.status));
  }
  return body;
}

async function saveAuthoringSpec(request) {
  const response = await fetch("/api/authoring-spec", {
    method: "PUT",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(request)
  });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(body.message || ("Authoring spec save failed with status " + response.status));
  }
  return body;
}

async function exportAuthoringSpec(request) {
  const response = await fetch("/api/authoring-spec/export", {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(request)
  });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(body.message || ("Authoring spec export failed with status " + response.status));
  }
  return body;
}

async function createAuthoringSpec(request) {
  const response = await fetch("/api/authoring-spec/create", {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(request)
  });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(body.message || ("Authoring spec create failed with status " + response.status));
  }
  return body;
}

async function loadHistoricalRunDetails(run) {
  const query = new URLSearchParams({
    appId: run.appId,
    flowId: run.flowId,
    runId: run.runId
  });
  const response = await fetch(`/api/runs/detail?${query.toString()}`);
  const body = await response.json();
  if (!response.ok) {
    throw new Error(body.message || ("Run details request failed with status " + response.status));
  }
  return body;
}

async function loadHistoricalRunsQuery(queryState, cursor = "") {
  const query = new URLSearchParams();
  if (queryState.appId) {
    query.set("appId", queryState.appId);
  }
  if (queryState.flowId) {
    query.set("flowId", queryState.flowId);
  }
  if (queryState.outcome) {
    query.set("outcome", queryState.outcome);
  }
  if (queryState.summaryReadStatus) {
    query.set("summaryReadStatus", queryState.summaryReadStatus);
  }
  if (queryState.limit) {
    query.set("limit", queryState.limit);
  }
  if (cursor) {
    query.set("cursor", cursor);
  }
  const response = await fetch(`/api/runs?${query.toString()}`);
  const body = await response.json();
  if (!response.ok) {
    throw new Error(body.message || ("Runs query failed with status " + response.status));
  }
  return body;
}

function fillLaunchForm(candidate) {
  document.getElementById("launch-plan").value = candidate.planLocation || "";
  document.getElementById("launch-envelope").value = candidate.envelopeLocation || "";
}

function authoringSpecs(snapshot) {
  return snapshot && snapshot.launchPrep && Array.isArray(snapshot.launchPrep.authoringSpecs)
    ? snapshot.launchPrep.authoringSpecs
    : [];
}

function selectedAuthoringSpecCandidate(snapshot) {
  return authoringSpecs(snapshot).find((spec) => spec.path === selectedAuthoringSpecPath) || null;
}

function defaultEnvelopeExample(spec) {
  if (!spec) {
    return "";
  }
  if (spec.envelopeLocation) {
    return spec.envelopeLocation;
  }
  return Array.isArray(spec.envelopeExamples) && spec.envelopeExamples.length
    ? spec.envelopeExamples[0]
    : "";
}

async function exportSpecCandidate(spec) {
  const resultBox = document.getElementById("authoring-spec-result");
  if (!spec || !spec.path) {
    resultBox.textContent = "No authoring spec selected.";
    return;
  }
  const envelopePath = defaultEnvelopeExample(spec);
  if (!envelopePath) {
    resultBox.textContent = "No committed envelope example is available for this spec.";
    return;
  }
  selectedAuthoringSpecPath = spec.path;
  resultBox.textContent = "Exporting persisted JSON pair...";
  const exported = await exportAuthoringSpec({
    path: spec.path,
    envelopePath
  });
  resultBox.textContent = JSON.stringify(exported, null, 2);
  await boot();
}

function runKey(run) {
  return `${run.appId}/${run.flowId}/${run.runId}`;
}

function clearHistoricalRunSelection() {
  selectedHistoricalRunKey = "";
  selectedHistoricalRunDetails = null;
}

function selectedHistoricalRunCandidate(snapshot) {
  const queryRuns = latestHistoricalRunsQuery && Array.isArray(latestHistoricalRunsQuery.runs)
    ? latestHistoricalRunsQuery.runs
    : [];
  const workspaceRuns = snapshot && Array.isArray(snapshot.runs)
    ? snapshot.runs
    : [];
  return [...queryRuns, ...workspaceRuns].find((run) => runKey(run) === selectedHistoricalRunKey) || null;
}

async function toggleHistoricalRunSelection(run) {
  if (selectedHistoricalRunKey === runKey(run)) {
    clearHistoricalRunSelection();
    renderSnapshot(latestWorkspaceSnapshot);
    return;
  }
  selectedHistoricalRunKey = runKey(run);
  selectedHistoricalRunDetails = await loadHistoricalRunDetails(run);
  renderSnapshot(latestWorkspaceSnapshot);
}

function renderPacks(snapshot) {
  const packList = document.getElementById("pack-list");
  const stepList = document.getElementById("step-list");
  packList.innerHTML = "";
  stepList.innerHTML = "";

  const firstStep = snapshot.packs.flatMap((pack) => pack.steps).at(0);
  if (firstStep) {
    document.getElementById("inspector-title").textContent = firstStep.displayName;
    document.getElementById("inspector-summary").textContent = firstStep.summary || "No summary provided in pack metadata.";
  }

  for (const pack of snapshot.packs) {
    const item = document.createElement("li");
    item.innerHTML = `<strong>${pack.id}</strong><span>${pack.stepCount} step files, ${pack.panelCount} panels</span>`;
    packList.appendChild(item);
    for (const step of pack.steps.slice(0, 6)) {
      const chip = document.createElement("li");
      chip.textContent = step.displayName;
      stepList.appendChild(chip);
    }
  }
}

function renderRuns(snapshot) {
  const runList = document.getElementById("run-list");
  const querySummary = document.getElementById("run-query-summary");
  const loadMoreButton = document.getElementById("run-query-load-more");
  runList.innerHTML = "";
  const runSource = latestHistoricalRunsQuery || {
    runs: snapshot.runs,
    hasMore: false,
    nextCursor: "",
    cursorApplied: false,
    totalMatchedCount: snapshot.runs.length,
    filters: { appId: "", flowId: "", outcome: "", summaryReadStatus: "" },
    limit: snapshot.runs.length
  };
  const runs = runSource.runs || [];
  document.getElementById("run-count").textContent = String(runs.length);
  const filterParts = [];
  if (runSource.filters && runSource.filters.appId) {
    filterParts.push(`app ${runSource.filters.appId}`);
  }
  if (runSource.filters && runSource.filters.flowId) {
    filterParts.push(`flow ${runSource.filters.flowId}`);
  }
  if (runSource.filters && runSource.filters.outcome) {
    filterParts.push(`outcome ${runSource.filters.outcome}`);
  }
  if (runSource.filters && runSource.filters.summaryReadStatus) {
    filterParts.push(`summary ${runSource.filters.summaryReadStatus}`);
  }
  const totalMatchedCount = Number.isFinite(runSource.totalMatchedCount) ? runSource.totalMatchedCount : runs.length;
  querySummary.textContent = `loaded ${runs.length} of ${totalMatchedCount} matching runs · page size ${runSource.limit || runs.length}${runSource.hasMore ? " · older page available" : ""}${runSource.cursorApplied ? " · paged" : ""}${filterParts.length ? ` · ${filterParts.join(" · ")}` : ""}`;
  loadMoreButton.hidden = !runSource.nextCursor;
  loadMoreButton.disabled = false;
  loadMoreButton.textContent = runSource.hasMore ? "Load Older Runs" : "No Older Runs";
  loadMoreButton.onclick = async () => {
    if (!latestHistoricalRunsQuery || !latestHistoricalRunsQuery.nextCursor) {
      return;
    }
    loadMoreButton.disabled = true;
    loadMoreButton.textContent = "Loading Older Runs...";
    try {
      const nextPage = await loadHistoricalRunsQuery(historicalRunQuery, latestHistoricalRunsQuery.nextCursor);
      latestHistoricalRunsQuery = {
        ...nextPage,
        runs: [...(latestHistoricalRunsQuery.runs || []), ...(nextPage.runs || [])]
      };
      renderSnapshot(latestWorkspaceSnapshot);
    } catch (error) {
      querySummary.textContent = String(error);
      loadMoreButton.disabled = false;
      loadMoreButton.textContent = "Load Older Runs";
    }
  };
  if (runs.length === 0) {
    runList.innerHTML = `<div class="run-card"><strong>No runs discovered</strong><span>Point the shell at a runs root after you have real runtime artifacts.</span></div>`;
    return;
  }
  for (const run of runs) {
    const selected = selectedHistoricalRunKey === runKey(run);
    const summaryStatus = run.summaryReadStatus && run.summaryReadStatus !== "complete"
      ? ` · summary ${run.summaryReadStatus}`
      : "";
    const card = document.createElement("article");
    card.className = "run-card";
    card.innerHTML = `
      <strong>${run.appId} / ${run.flowId}</strong>
      <span>run ${run.runId} · outcome ${run.outcome || "unknown"} · steps ${run.executedSteps} · signals ${run.signalCount}${summaryStatus}</span>
      <span>${run.updatedAt}</span>
      <button class="launch-prep-button" type="button">${selected ? "Follow latest" : "Inspect run"}</button>
    `;
    card.querySelector("button").addEventListener("click", async () => {
      try {
        await toggleHistoricalRunSelection(run);
      } catch (error) {
        document.getElementById("signal-viewer").textContent = String(error);
      }
    });
    runList.appendChild(card);
  }
}

function renderControlSessions(snapshot) {
  const summary = document.getElementById("control-session-summary");
  const list = document.getElementById("control-session-list");
  const controlSessions = snapshot.controlSessions;
  list.innerHTML = "";
  summary.textContent =
    `${controlSessions.activeCount} active · ${controlSessions.trackedCount} tracked · cancel unsupported`;

  if (!controlSessions.sessions.length) {
    list.innerHTML = `<article class="run-card"><strong>No live Creator sessions</strong><span>Start a real run session here to watch Creator-owned live state before artifacts settle into the runs list.</span></article>`;
    return;
  }

  for (const session of controlSessions.sessions.slice(0, 6)) {
    const card = document.createElement("article");
    card.className = "run-card";
    card.innerHTML = `
      <strong>${session.appId || "pending-app"} / ${session.flowId || "pending-flow"}</strong>
      <span>run ${session.runId} · session ${session.sessionId}</span>
      <span>${session.state} · signals ${session.signalCount} · patches ${session.contextDiff.changedPathCount} · last ${session.lastSignalType || "none yet"}</span>
      <span>${session.startedAt || session.submittedAt}</span>
    `;
    list.appendChild(card);
  }
}

function renderInstances(snapshot) {
  const summary = document.getElementById("instance-summary");
  const list = document.getElementById("instance-list");
  const instances = snapshot.instances || { activeCount: 0, staleCount: 0, totalCount: 0, registryRoot: "", entries: [] };
  list.innerHTML = "";
  summary.textContent =
    `${instances.activeCount} active · ${instances.staleCount} stale · ${instances.totalCount} discovered · registry ${instances.registryRoot || "unavailable"}`;

  if (!instances.entries.length) {
    list.innerHTML = `<article class="run-card"><strong>No instance heartbeats discovered</strong><span>The first Slice 9 contract is discovery-only. Start another Creator shell against the same registry root to see a real peer.</span></article>`;
    return;
  }

  for (const instance of instances.entries.slice(0, 6)) {
    const stepProviders = instance.stepProviders || {
      providerCount: 0,
      reportedProviderCount: 0,
      unreportedProviderCount: 0,
      providerModuleCount: 0,
      supportedUseCount: 0,
      supportedResourceRefPatternCount: 0
    };
    const loadInfo = instance.load || { activeControlSessions: 0, maxActiveSessions: 0 };
    const runtimeInfo = instance.runtime || { osName: "", osArch: "", javaVersion: "" };
    const trustInfo = instance.trust || {
      mode: "",
      sharedRegistryDiscovery: false,
      remoteControlSupported: false,
      remoteExecutionSupported: false
    };
    const workspaceAlignment = instance.workspaceRuntimeAlignment || {
      workspacePackCount: 0,
      matchedPackCount: 0,
      runtimeProviderModuleCount: 0,
      fullyAligned: false
    };
    const runtimeIdentity = instance.runtimeIdentity || {
      status: "",
      identitySource: "",
      reportsCompleteUseCatalog: false,
      capabilityDigest: "",
      providerModuleCount: 0,
      supportedUseCount: 0
    };
    const remoteExecutionBoundary = instance.remoteExecutionBoundary || {
      status: "",
      endpointPath: "",
      acceptedExecutionSupported: false,
      deterministicRejectionSupported: false
    };
    const executionModel = instance.executionModel || {
      localStepExecutionSupported: false,
      remoteStepExecutionSupported: false,
      queuedStepExecutionSupported: false,
      crossInstancePermissionSharingSupported: false,
      cacheShareSupported: false
    };
    const distributedSupport = instance.distributedSupport || {
      status: "",
      runtimePackIdentitySupported: false,
      remoteExecutionBoundarySupported: false,
      distributedQueueSupported: false,
      crossInstancePermissionPropagationSupported: false,
      sharedCacheDigestSupported: false
    };
    const capabilitySummaryBase =
      `${stepProviders.providerModuleCount || 0} provider modules · ${stepProviders.supportedUseCount || 0} supported uses · ${stepProviders.supportedResourceRefPatternCount || 0} resource ref patterns`;
    const capabilitySummary =
      (stepProviders.unreportedProviderCount || 0) > 0
        ? `${capabilitySummaryBase} · ${stepProviders.unreportedProviderCount} unreported`
        : capabilitySummaryBase;
    const loadSummary = `${loadInfo.activeControlSessions || 0}/${loadInfo.maxActiveSessions || 0} live sessions`;
    const runtimeSummary = [runtimeInfo.osName, runtimeInfo.osArch, runtimeInfo.javaVersion].filter(Boolean).join(" · ");
    const trustSummary = [
      trustInfo.mode || "trust unknown",
      trustInfo.remoteExecutionSupported ? "remote exec on" : "remote exec off",
      trustInfo.remoteControlSupported ? "remote control on" : "remote control off"
    ].join(" · ");
    const alignmentSummary =
      `${workspaceAlignment.matchedPackCount || 0}/${workspaceAlignment.workspacePackCount || 0} workspace packs aligned · ${workspaceAlignment.runtimeProviderModuleCount || 0} runtime modules`;
    const runtimeIdentitySummary = [
      runtimeIdentity.status || "runtime identity unknown",
      runtimeIdentity.reportsCompleteUseCatalog ? "catalog complete" : "catalog incomplete",
      runtimeIdentity.capabilityDigest
        ? `${runtimeIdentity.capabilityDigest.slice(0, 19)}...`
        : "digest unavailable"
    ].join(" · ");
    const remoteExecutionBoundarySummary = [
      remoteExecutionBoundary.status || "remote boundary unknown",
      remoteExecutionBoundary.acceptedExecutionSupported ? "accept on" : "accept off",
      remoteExecutionBoundary.deterministicRejectionSupported ? "reject on" : "reject off"
    ].join(" · ");
    const executionSummary = [
      executionModel.localStepExecutionSupported ? "local exec on" : "local exec off",
      executionModel.remoteStepExecutionSupported ? "remote exec on" : "remote exec off",
      executionModel.queuedStepExecutionSupported ? "queue on" : "queue off"
    ].join(" · ");
    const distributedSummary = [
      distributedSupport.status || "distribution unknown",
      distributedSupport.runtimePackIdentitySupported ? "pack identity on" : "pack identity off",
      distributedSupport.sharedCacheDigestSupported ? "shared cache on" : "shared cache off"
    ].join(" · ");
    const card = document.createElement("article");
    card.className = "run-card";
    card.innerHTML = `
      <strong>${instance.currentInstance ? "Current shell" : instance.instanceId}</strong>
      <span>${instance.state} · ${instance.creatorState || "creator shell"} · ttl ${instance.ttlSeconds || 0}s</span>
      <span>${capabilitySummary} · ${loadSummary}</span>
      <span>${trustSummary}</span>
      <span>${alignmentSummary}</span>
      <span>${runtimeIdentitySummary}</span>
      <span>${remoteExecutionBoundarySummary}</span>
      <span>${executionSummary}</span>
      <span>${distributedSummary}</span>
      <span>${runtimeSummary || "runtime unknown"}</span>
      <span>${instance.repoRoot || "unknown repo"}</span>
      <span>${instance.loopbackControlUrl || "loopback only"}</span>
    `;
    list.appendChild(card);
  }
}

function preferredRunSource(snapshot) {
  if (selectedHistoricalRunKey && selectedHistoricalRunDetails) {
    return selectedHistoricalRunDetails;
  }
  const liveSource = snapshot.controlSessions.sessions.find((session) =>
    session.active && (session.timeline.length || session.latestSignals.length || session.contextDiff.available)
  );
  if (liveSource) {
    return liveSource;
  }
  return snapshot.latestRunDetails && Object.keys(snapshot.latestRunDetails).length
    ? snapshot.latestRunDetails
    : null;
}

function preferredRunSourceLabel(source) {
  if (!source) {
    return "No run evidence selected";
  }
  if (source.sourceKind === "historical-persisted-run") {
    return `Historical persisted run · ${source.runId}`;
  }
  return source.sessionId
    ? `Live Creator session · ${source.runId}`
    : `Latest persisted run · ${source.runId}`;
}

function renderGraph(snapshot) {
  const source = preferredRunSource(snapshot);
  const graphNote = document.getElementById("graph-shell-note");
  const defaultNodes = new Map([
    ["trigger", { label: "Run started", state: "pending", detail: "Waiting for a real run.started signal." }],
    ["step", { label: "Step activity", state: "pending", detail: "Waiting for a real step.started signal." }],
    ["patch", { label: "Context patched", state: "pending", detail: "Waiting for a real context.patched signal." }],
    ["reply", { label: "Reply produced", state: "pending", detail: "Waiting for a real reply.produced signal." }],
    ["finish", { label: "Run finished", state: "pending", detail: "Waiting for a real run.finished signal." }]
  ]);
  const graphNodes = source && source.graphActivity && source.graphActivity.available
    ? new Map((source.graphActivity.nodes || []).map((node) => [node.id, node]))
    : defaultNodes;

  for (const [id, fallback] of defaultNodes.entries()) {
    const node = graphNodes.get(id) || fallback;
    const element = document.getElementById(`graph-node-${id}`);
    if (!element) {
      continue;
    }
    element.dataset.state = node.state || "pending";
    element.querySelector("strong").textContent = node.label || fallback.label;
    element.querySelector("span").textContent = node.detail || fallback.detail;
  }

  if (!graphNote) {
    return;
  }
  if (!source || !source.graphActivity || !source.graphActivity.available) {
    graphNote.textContent = "Read-only graph activity from bounded timeline evidence. No fake drag-and-drop mythology today.";
    return;
  }
  graphNote.textContent = `${preferredRunSourceLabel(source)} · ${source.graphActivity.statusSummary}`;
}

function renderTimeline(snapshot) {
  const summary = document.getElementById("timeline-summary");
  const list = document.getElementById("timeline-events");
  const source = preferredRunSource(snapshot);
  list.innerHTML = "";

  if (!source || !source.timeline.length) {
    summary.textContent = "No timeline evidence discovered yet.";
    list.innerHTML = `<article class="timeline-entry"><strong>No timeline yet</strong><span>Start a Creator session or inspect a persisted run with real signals.</span></article>`;
    return;
  }

  summary.textContent =
    `${preferredRunSourceLabel(source)} · ${source.timeline.length} bounded events · signal stream ${source.signalStreamStatus || "complete"}`;
  if (source.summaryReadStatus && source.summaryReadStatus !== "complete") {
    summary.textContent += ` · summary ${source.summaryReadStatus}`;
  }
  if (source.signalReadError) {
    summary.textContent += ` · ${source.signalReadError}`;
  }
  if (source.summaryReadError) {
    summary.textContent += ` · ${source.summaryReadError}`;
  }

  for (const event of source.timeline) {
    const detailParts = [];
    if (event.stepId) {
      detailParts.push(event.stepId);
    }
    if (event.attempt) {
      detailParts.push(`attempt ${event.attempt}`);
    }
    if (event.outcome) {
      detailParts.push(`outcome ${event.outcome}`);
    }
    if (event.replyMode) {
      detailParts.push(`reply ${event.replyMode}`);
    }
    if (event.durationMs) {
      detailParts.push(`${event.durationMs} ms`);
    }
    if (event.changedPathCount) {
      detailParts.push(`${event.changedPathCount} changed paths`);
    }
    if (event.patchCount) {
      detailParts.push(`${event.patchCount} patch ops`);
    }
    if (event.error) {
      detailParts.push(event.error);
    }
    const card = document.createElement("article");
    card.className = "timeline-entry";
    card.innerHTML = `
      <strong>${event.type}</strong>
      <span>${event.summary}</span>
      <span>${detailParts.join(" · ") || "No extra event details"}</span>
      <span>${event.timestamp || "Timestamp unavailable"}</span>
    `;
    list.appendChild(card);
  }
}

function renderContextDiff(snapshot) {
  const summary = document.getElementById("context-diff-summary");
  const list = document.getElementById("context-diff-list");
  const source = preferredRunSource(snapshot);
  list.innerHTML = "";

  if (!source || !source.contextDiff.available) {
    summary.textContent = "No context patch evidence discovered yet.";
    list.innerHTML = `<article class="timeline-entry"><strong>No context diff yet</strong><span>Creator only shows changed-path evidence when persisted signals include a real <code>context.patched</code> event.</span></article>`;
    return;
  }

  summary.textContent =
    `${preferredRunSourceLabel(source)} · ${source.contextDiff.changedPathCount} changed paths · ${source.contextDiff.stepId || "unknown step"}`;
  for (const changedPath of source.contextDiff.changedPaths) {
    const item = document.createElement("li");
    item.className = "diff-path";
    item.textContent = changedPath;
    list.appendChild(item);
  }
}

function renderMetrics(snapshot) {
  const summary = document.getElementById("metrics-summary");
  const list = document.getElementById("metrics-list");
  const source = preferredRunSource(snapshot);
  list.innerHTML = "";

  if (!source || !source.metrics || !source.metrics.count) {
    summary.textContent = "No metric evidence discovered yet.";
    list.innerHTML = `<article class="timeline-entry"><strong>No metrics yet</strong><span>Creator only shows metric evidence when persisted signals include real <code>metric.emitted</code> events.</span></article>`;
    return;
  }

  summary.textContent = `${preferredRunSourceLabel(source)} · ${source.metrics.count} metric events`;
  for (const series of source.metrics.series) {
    const card = document.createElement("article");
    card.className = "timeline-entry";
    card.innerHTML = `
      <strong>${series.name}</strong>
      <span>${series.count} events · unit ${series.unit || "none"} · ${series.latestStepId || "unknown step"}</span>
      <span>${series.valueKind} · latest ${series.latestValuePreview || "no value preview"} · ${series.labelCount} labels${series.labelKeys.length ? ` (${series.labelKeys.join(", ")})` : ""}</span>
      <span>${series.latestTimestamp || "Timestamp unavailable"}</span>
    `;
    list.appendChild(card);
  }
}

function renderAudits(snapshot) {
  const summary = document.getElementById("audits-summary");
  const list = document.getElementById("audits-list");
  const source = preferredRunSource(snapshot);
  list.innerHTML = "";

  if (!source || !source.audits || !source.audits.count) {
    summary.textContent = "No audit evidence discovered yet.";
    list.innerHTML = `<article class="timeline-entry"><strong>No audits yet</strong><span>Creator only shows audit evidence when persisted signals include real <code>audit.emitted</code> events.</span></article>`;
    return;
  }

  summary.textContent = `${preferredRunSourceLabel(source)} · ${source.audits.count} audit events`;
  for (const event of source.audits.events) {
    const card = document.createElement("article");
    card.className = "timeline-entry";
    card.innerHTML = `
      <strong>${event.eventName}</strong>
      <span>${event.count} events · ${event.latestStepId || "unknown step"} · ${event.topLevelKeyCount} keys${event.dataKeys.length ? ` (${event.dataKeys.join(", ")})` : ""}</span>
      <span>${event.latestDataPreview || "No audit payload preview"}</span>
      <span>${event.latestTimestamp || "Timestamp unavailable"}</span>
    `;
    list.appendChild(card);
  }
}

function renderSignals(snapshot) {
  const viewer = document.getElementById("signal-viewer");
  const source = preferredRunSource(snapshot);
  const signals = source && Array.isArray(source.latestSignals) ? source.latestSignals : snapshot.latestSignals;
  const sections = [];
  if (source && source.failureSummary) {
    sections.push(`${preferredRunSourceLabel(source)}\nTerminal failure summary:\n${source.failureSummary}`);
  }
  if (!signals.length) {
    if (sections.length) {
      viewer.textContent = `${sections.join("\n\n")}\n\nNo signals discovered yet.`;
      return;
    }
    viewer.textContent = "No signals discovered yet.";
    return;
  }
  sections.push(JSON.stringify(signals, null, 2));
  viewer.textContent = sections.join("\n\n");
}

function renderTraffic(snapshot) {
  const summary = document.getElementById("traffic-summary");
  const list = document.getElementById("traffic-list");
  const traffic = snapshot.httpTraffic;
  list.innerHTML = "";

  if (traffic.error) {
    summary.textContent = "Traffic artifacts were discovered, but at least one capture could not be decoded.";
    list.innerHTML = `<article class="traffic-card traffic-card-error"><strong>Traffic evidence unavailable</strong><span>${traffic.error}</span></article>`;
    return;
  }

  summary.textContent =
    `${traffic.totalRows} rows · ${traffic.handledRows} handled · ${traffic.rejectedRows} rejected · ${traffic.failedRows} failed`;
  if (!traffic.rows.length) {
    list.innerHTML = `<article class="traffic-card"><strong>No HTTP captures discovered</strong><span>Real traffic rows appear here when runs persist <code>http-capture.json</code> artifacts.</span></article>`;
    return;
  }

  for (const row of traffic.rows.slice(0, 6)) {
    const card = document.createElement("article");
    card.className = "traffic-card";
    card.innerHTML = `
      <div class="traffic-card-header">
        <strong>${row.method} ${row.path}</strong>
        <span class="traffic-kind">${row.captureKind}</span>
      </div>
      <span>${row.appId} / ${row.flowId} / ${row.runId}</span>
      <span>status ${row.status} · ${row.durationMs} ms · outcome ${row.outcome}</span>
      <span>${row.timestamp}</span>
    `;
    list.appendChild(card);
  }
}

function renderRemoteRequests(snapshot) {
  const summary = document.getElementById("remote-request-summary");
  const list = document.getElementById("remote-request-list");
  const remoteRequests = snapshot.remoteExecutionRequests || {
    error: "",
    totalCount: 0,
    acceptedCount: 0,
    rejectedCount: 0,
    unsupportedCount: 0,
    queuedCount: 0,
    executedCount: 0,
    failedCount: 0,
    interruptedCount: 0,
    requests: []
  };
  list.innerHTML = "";

  if (remoteRequests.error) {
    summary.textContent = "Remote request artifacts were discovered, but at least one record could not be decoded.";
    list.innerHTML = `<article class="run-card"><strong>Remote request history unavailable</strong><span>${remoteRequests.error}</span></article>`;
    return;
  }

  summary.textContent =
    `${remoteRequests.queuedCount || 0} queued · ${remoteRequests.executedCount || 0} executed · ${remoteRequests.failedCount || 0} failed · ${remoteRequests.interruptedCount || 0} interrupted · ${remoteRequests.rejectedCount || 0} rejected · ${remoteRequests.totalCount || 0} recorded`;
  if (!Array.isArray(remoteRequests.requests) || !remoteRequests.requests.length) {
    list.innerHTML = `<article class="run-card"><strong>No remote requests recorded</strong><span>The current Slice 10 boundary only shows rows here after real POST traffic reaches <code>/api/remote-execution/requests</code>.</span></article>`;
    return;
  }

  for (const request of remoteRequests.requests) {
    const statusSummary = [
      request.requestStatus || "unknown",
      request.decision || "decision unknown",
      request.responseStatus ? `http ${request.responseStatus}` : "",
      request.rejectionCode || ""
    ].filter(Boolean).join(" · ");
    const runSummary = request.runId
      ? `${request.appId || "remote app"} / ${request.flowId || "remote flow"} / ${request.runId}`
      : "No linked run artifacts";
    const card = document.createElement("article");
    card.className = "run-card";
    card.innerHTML = `
      <strong>${request.stepUse || "unknown step"}</strong>
      <span>request ${request.requestId || "anonymous"} · execution ${request.executionId || "pending"} · ${statusSummary}</span>
      <span>${runSummary}</span>
      <span>${request.completedAt || request.updatedAt || request.receivedAt || "timestamp unavailable"} · ${request.durationMs || 0} ms</span>
      <span>${request.message || "No boundary message recorded."}</span>
    `;
    if (request.runId && request.appId && request.flowId) {
      const button = document.createElement("button");
      button.className = "launch-prep-button";
      button.type = "button";
      button.textContent = "Inspect linked run";
      button.addEventListener("click", async () => {
        try {
          await toggleHistoricalRunSelection({
            appId: request.appId,
            flowId: request.flowId,
            runId: request.runId
          });
        } catch (error) {
          document.getElementById("signal-viewer").textContent = String(error);
        }
      });
      card.appendChild(button);
    }
    list.appendChild(card);
  }
}

function renderLaunchPrep(snapshot) {
  const prep = snapshot.launchPrep;
  document.getElementById("launch-prep-guidance").textContent = prep.guidance;

  const runnable = document.getElementById("launch-prep-runnable");
  runnable.innerHTML = "";
  if (prep.runnableInputs.length) {
    runnable.innerHTML = `<h3 class="launch-prep-heading">Runnable persisted inputs</h3>`;
    for (const candidate of prep.runnableInputs) {
      const card = document.createElement("article");
      card.className = "launch-prep-card";
      card.innerHTML = `
        <strong>${candidate.label}</strong>
        <span>${candidate.planLocation}</span>
        <span>${candidate.envelopeLocation}</span>
        <button class="launch-prep-button" type="button">Use in launcher</button>
      `;
      card.querySelector("button").addEventListener("click", () => fillLaunchForm(candidate));
      runnable.appendChild(card);
    }
  } else {
    runnable.innerHTML = `<h3 class="launch-prep-heading">Runnable persisted inputs</h3><article class="launch-prep-card"><strong>No runnable persisted input pairs discovered</strong><span>Create or export a real <code>plan.json</code> and <code>envelope.json</code> pair to enable one-click launch setup.</span></article>`;
  }

  const specs = document.getElementById("launch-prep-specs");
  specs.innerHTML = "";
  if (prep.authoringSpecs.length) {
    specs.innerHTML = `<h3 class="launch-prep-heading">Authoring specs</h3>`;
    for (const spec of prep.authoringSpecs.slice(0, 6)) {
      const envelopeHint = spec.envelopeExamples.length
        ? `Envelope examples: ${spec.envelopeExamples.join(", ")}`
        : "No committed envelope example beside this spec.";
      const card = document.createElement("article");
      if (spec.launchable) {
        card.className = "launch-prep-card";
        card.innerHTML = `
          <strong>${spec.appName}</strong>
          <span>${spec.relativePath}</span>
          <span>${spec.planLocation}</span>
          <span>${spec.envelopeLocation}</span>
          <div class="editor-actions">
            <button class="launch-prep-button" data-action="launch" type="button">Use in launcher</button>
            <button class="launch-prep-button" data-action="edit" type="button">Edit YAML</button>
            <button class="launch-prep-button" data-action="export" type="button">Export JSON</button>
          </div>
        `;
        card.querySelector('[data-action="launch"]').addEventListener("click", () => fillLaunchForm(spec));
        card.querySelector('[data-action="edit"]').addEventListener("click", async () => {
          selectedAuthoringSpecPath = spec.path;
          latestAuthoringSpec = await loadAuthoringSpec(spec.path);
          authoringSpecDirty = false;
          renderSnapshot(latestWorkspaceSnapshot);
        });
        card.querySelector('[data-action="export"]').addEventListener("click", async () => {
          try {
            await exportSpecCandidate(spec);
          } catch (error) {
            document.getElementById("authoring-spec-result").textContent = String(error);
          }
        });
      } else {
        card.className = "launch-prep-card launch-prep-card-blocked";
        card.innerHTML = `
          <strong>${spec.appName}</strong>
          <span>${spec.relativePath}</span>
          <span>${spec.blocker}</span>
          <span>${envelopeHint}</span>
          <div class="editor-actions">
            <button class="launch-prep-button" data-action="edit" type="button">Edit YAML</button>
            <button class="launch-prep-button" data-action="export" type="button">Export JSON</button>
          </div>
        `;
        card.querySelector('[data-action="edit"]').addEventListener("click", async () => {
          selectedAuthoringSpecPath = spec.path;
          latestAuthoringSpec = await loadAuthoringSpec(spec.path);
          authoringSpecDirty = false;
          renderSnapshot(latestWorkspaceSnapshot);
        });
        card.querySelector('[data-action="export"]').disabled = !defaultEnvelopeExample(spec);
        card.querySelector('[data-action="export"]').addEventListener("click", async () => {
          try {
            await exportSpecCandidate(spec);
          } catch (error) {
            document.getElementById("authoring-spec-result").textContent = String(error);
          }
        });
      }
      specs.appendChild(card);
    }
  }

  const reports = document.getElementById("launch-prep-reports");
  reports.innerHTML = "";
  if (prep.packageReports.length) {
    reports.innerHTML = `<h3 class="launch-prep-heading">Packaged launcher reports</h3>`;
    for (const report of prep.packageReports.slice(0, 6)) {
      const card = document.createElement("article");
      card.className = "launch-prep-card launch-prep-card-blocked";
      card.innerHTML = `
        <strong>${report.appName || "Packaged launcher"}</strong>
        <span>${report.reportPath}</span>
        <span>${report.launcherPath || "No launcher path found"}</span>
        <span>${report.blocker}</span>
      `;
      reports.appendChild(card);
    }
  }
}

function renderAuthoringSpecEditor(snapshot) {
  const summary = document.getElementById("authoring-spec-summary");
  const pathInput = document.getElementById("authoring-spec-path");
  const contentInput = document.getElementById("authoring-spec-content");
  const reloadButton = document.getElementById("authoring-spec-reload");
  const exportButton = document.getElementById("authoring-spec-export");
  const saveButton = document.getElementById("authoring-spec-save");
  const resultBox = document.getElementById("authoring-spec-result");
  const specs = authoringSpecs(snapshot);

  if (!specs.length) {
    summary.textContent = "No committed railix.app.yaml files are currently discoverable under examples/ .";
    pathInput.value = "";
    contentInput.value = "";
    contentInput.disabled = true;
    reloadButton.disabled = true;
    exportButton.disabled = true;
    saveButton.disabled = true;
    if (!resultBox.textContent.trim()) {
      resultBox.textContent = "No authoring spec selected yet.";
    }
    return;
  }

  contentInput.disabled = false;
  reloadButton.disabled = false;
  saveButton.disabled = false;
  const selectedSpec = selectedAuthoringSpecCandidate(snapshot) || specs[0];
  exportButton.disabled = !defaultEnvelopeExample(selectedSpec);
  pathInput.value = selectedSpec.path;
  if (!latestAuthoringSpec || latestAuthoringSpec.path !== selectedSpec.path) {
    contentInput.value = "";
    summary.textContent = `Select an authoring spec to load it for editing: ${selectedSpec.relativePath}`;
    return;
  }

  if (!authoringSpecDirty || pathInput.value !== latestAuthoringSpec.path) {
    contentInput.value = latestAuthoringSpec.content || "";
  }
  const parseStatus = latestAuthoringSpec.parseStatus || "invalid";
  const statusMessage = parseStatus === "valid"
    ? `parse ${parseStatus} · ${latestAuthoringSpec.appId} / ${latestAuthoringSpec.flowId}`
    : `parse ${parseStatus} · ${latestAuthoringSpec.message || "see launch prep blocker"}`;
  summary.textContent = `${selectedSpec.relativePath} · ${statusMessage}${authoringSpecDirty ? " · unsaved edits" : ""}`;
}

function renderHeader(snapshot) {
  document.getElementById("workspace-root").textContent = snapshot.repoRoot;
  document.getElementById("repo-root").textContent = snapshot.repoRoot;
  document.getElementById("runs-root").textContent = snapshot.runsRoot;
  document.getElementById("control-endpoint-status").textContent = snapshot.controlEndpointReady
    ? `Ready · ${snapshot.controlSessions.activeCount} active`
    : "Pending real control endpoint";
}

async function refreshHistoricalRunsQuery() {
  let refreshed = await loadHistoricalRunsQuery(historicalRunQuery);
  const targetCount = latestHistoricalRunsQuery && latestHistoricalRunsQuery.runs
    ? latestHistoricalRunsQuery.runs.length
    : refreshed.runs.length;
  let loadedRuns = [...(refreshed.runs || [])];
  let nextCursor = refreshed.nextCursor;
  while (nextCursor && loadedRuns.length < targetCount) {
    const nextPage = await loadHistoricalRunsQuery(historicalRunQuery, nextCursor);
    loadedRuns = [...loadedRuns, ...(nextPage.runs || [])];
    refreshed = {
      ...nextPage,
      runs: loadedRuns
    };
    nextCursor = nextPage.nextCursor;
  }
  return {
    ...refreshed,
    runs: loadedRuns
  };
}

function wireLaunchForm() {
  const form = document.getElementById("launch-form");
  const resultBox = document.getElementById("launch-result");
  if (!form || form.dataset.bound === "true") {
    return;
  }
  form.dataset.bound = "true";
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const submitButton = document.getElementById("launch-submit");
    submitButton.disabled = true;
    resultBox.textContent = "Starting live Creator-owned session...";
    try {
      const launched = await startLiveSession({
        planLocation: document.getElementById("launch-plan").value,
        envelopeLocation: document.getElementById("launch-envelope").value,
        settingsLocation: document.getElementById("launch-settings").value,
        profileName: document.getElementById("launch-profile").value,
        runId: document.getElementById("launch-run-id").value
      });
      resultBox.textContent = JSON.stringify(launched, null, 2);
      await boot();
    } catch (error) {
      resultBox.textContent = String(error);
    } finally {
      submitButton.disabled = false;
    }
  });
}

function wireRunQueryForm() {
  const form = document.getElementById("run-query-form");
  if (!form || form.dataset.bound === "true") {
    return;
  }
  form.dataset.bound = "true";
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    historicalRunQuery = {
      appId: document.getElementById("run-query-app-id").value.trim(),
      flowId: document.getElementById("run-query-flow-id").value.trim(),
      outcome: document.getElementById("run-query-outcome").value.trim(),
      summaryReadStatus: document.getElementById("run-query-summary-read-status").value,
      limit: document.getElementById("run-query-limit").value.trim() || "5"
    };
    try {
      latestHistoricalRunsQuery = await loadHistoricalRunsQuery(historicalRunQuery);
      renderSnapshot(latestWorkspaceSnapshot);
    } catch (error) {
      document.getElementById("run-query-summary").textContent = String(error);
    }
  });
}

function wireAuthoringSpecForm() {
  const form = document.getElementById("authoring-spec-form");
  const pathInput = document.getElementById("authoring-spec-path");
  const contentInput = document.getElementById("authoring-spec-content");
  const reloadButton = document.getElementById("authoring-spec-reload");
  const exportButton = document.getElementById("authoring-spec-export");
  const saveButton = document.getElementById("authoring-spec-save");
  const resultBox = document.getElementById("authoring-spec-result");
  if (!form || form.dataset.bound === "true") {
    return;
  }
  form.dataset.bound = "true";
  contentInput.addEventListener("input", () => {
    authoringSpecDirty = !!latestAuthoringSpec && contentInput.value !== latestAuthoringSpec.content;
    renderAuthoringSpecEditor(latestWorkspaceSnapshot);
  });

  reloadButton.addEventListener("click", async () => {
    if (!pathInput.value) {
      resultBox.textContent = "No authoring spec selected.";
      return;
    }
    reloadButton.disabled = true;
    saveButton.disabled = true;
    resultBox.textContent = "Reloading authoring spec...";
    try {
      selectedAuthoringSpecPath = pathInput.value;
      latestAuthoringSpec = await loadAuthoringSpec(pathInput.value);
      authoringSpecDirty = false;
      resultBox.textContent = JSON.stringify(latestAuthoringSpec, null, 2);
      renderSnapshot(latestWorkspaceSnapshot);
    } catch (error) {
      resultBox.textContent = String(error);
    } finally {
      reloadButton.disabled = false;
      exportButton.disabled = !defaultEnvelopeExample(selectedAuthoringSpecCandidate(latestWorkspaceSnapshot) || authoringSpecs(latestWorkspaceSnapshot)[0]);
      saveButton.disabled = false;
    }
  });

  exportButton.addEventListener("click", async () => {
    const selectedSpec = selectedAuthoringSpecCandidate(latestWorkspaceSnapshot) || authoringSpecs(latestWorkspaceSnapshot)[0];
    if (!selectedSpec) {
      resultBox.textContent = "No authoring spec selected.";
      return;
    }
    reloadButton.disabled = true;
    exportButton.disabled = true;
    saveButton.disabled = true;
    try {
      await exportSpecCandidate(selectedSpec);
    } catch (error) {
      resultBox.textContent = String(error);
    } finally {
      reloadButton.disabled = false;
      exportButton.disabled = !defaultEnvelopeExample(selectedAuthoringSpecCandidate(latestWorkspaceSnapshot) || authoringSpecs(latestWorkspaceSnapshot)[0]);
      saveButton.disabled = false;
    }
  });

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (!pathInput.value) {
      resultBox.textContent = "No authoring spec selected.";
      return;
    }
    reloadButton.disabled = true;
    exportButton.disabled = true;
    saveButton.disabled = true;
    resultBox.textContent = "Saving authoring spec...";
    try {
      latestAuthoringSpec = await saveAuthoringSpec({
        path: pathInput.value,
        content: contentInput.value
      });
      selectedAuthoringSpecPath = latestAuthoringSpec.path;
      authoringSpecDirty = false;
      resultBox.textContent = JSON.stringify(latestAuthoringSpec, null, 2);
      await boot();
    } catch (error) {
      resultBox.textContent = String(error);
    } finally {
      reloadButton.disabled = false;
      exportButton.disabled = !defaultEnvelopeExample(selectedAuthoringSpecCandidate(latestWorkspaceSnapshot) || authoringSpecs(latestWorkspaceSnapshot)[0]);
      saveButton.disabled = false;
    }
  });
}

function wireAuthoringSpecCreateForm() {
  const form = document.getElementById("authoring-spec-create-form");
  const submitButton = document.getElementById("authoring-spec-create-submit");
  const resultBox = document.getElementById("authoring-spec-create-result");
  if (!form || form.dataset.bound === "true") {
    return;
  }
  form.dataset.bound = "true";
  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    submitButton.disabled = true;
    resultBox.textContent = "Creating example-scoped authoring spec...";
    try {
      const created = await createAuthoringSpec({
        exampleId: document.getElementById("authoring-spec-create-example-id").value.trim(),
        appId: document.getElementById("authoring-spec-create-app-id").value.trim(),
        appName: document.getElementById("authoring-spec-create-app-name").value.trim(),
        flowId: document.getElementById("authoring-spec-create-flow-id").value.trim()
      });
      selectedAuthoringSpecPath = created.path;
      latestAuthoringSpec = created;
      authoringSpecDirty = false;
      resultBox.textContent = JSON.stringify(created, null, 2);
      await boot();
    } catch (error) {
      resultBox.textContent = String(error);
    } finally {
      submitButton.disabled = false;
    }
  });
}

function scheduleRefresh(snapshot) {
  if (refreshTimer) {
    window.clearTimeout(refreshTimer);
    refreshTimer = 0;
  }
  if (snapshot.controlSessions.activeCount > 0) {
    refreshTimer = window.setTimeout(() => {
      boot();
    }, 1000);
  }
}

function renderSnapshot(snapshot) {
  if (!snapshot) {
    return;
  }
  renderHeader(snapshot);
  renderPacks(snapshot);
  renderGraph(snapshot);
  renderControlSessions(snapshot);
  renderInstances(snapshot);
  renderRuns(snapshot);
  renderTimeline(snapshot);
  renderContextDiff(snapshot);
  renderMetrics(snapshot);
  renderAudits(snapshot);
  renderSignals(snapshot);
  renderLaunchPrep(snapshot);
  renderAuthoringSpecEditor(snapshot);
  renderRemoteRequests(snapshot);
  renderTraffic(snapshot);
  wireLaunchForm();
  wireRunQueryForm();
  wireAuthoringSpecCreateForm();
  wireAuthoringSpecForm();
  scheduleRefresh(snapshot);
}

async function boot() {
  try {
    latestWorkspaceSnapshot = await loadWorkspace();
    latestHistoricalRunsQuery = await refreshHistoricalRunsQuery();
    const specs = authoringSpecs(latestWorkspaceSnapshot);
    if (!specs.length) {
      selectedAuthoringSpecPath = "";
      latestAuthoringSpec = null;
      authoringSpecDirty = false;
    } else if (!latestAuthoringSpec || !selectedAuthoringSpecCandidate(latestWorkspaceSnapshot)) {
      selectedAuthoringSpecPath = (selectedAuthoringSpecCandidate(latestWorkspaceSnapshot) || specs[0]).path;
      latestAuthoringSpec = await loadAuthoringSpec(selectedAuthoringSpecPath);
      authoringSpecDirty = false;
    }
    if (selectedHistoricalRunKey) {
      const selectedRun = selectedHistoricalRunCandidate(latestWorkspaceSnapshot);
      if (selectedRun) {
        selectedHistoricalRunDetails = await loadHistoricalRunDetails(selectedRun);
      } else {
        clearHistoricalRunSelection();
      }
    }
    renderSnapshot(latestWorkspaceSnapshot);
  } catch (error) {
    document.getElementById("signal-viewer").textContent = String(error);
  }
}

boot();
