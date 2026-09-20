"use strict";

const creatorToken = new URLSearchParams(window.location.hash.slice(1)).get("token") || "";
const browserFetch = window.fetch.bind(window);
window.fetch = (input, options = {}) => {
  const url = new URL(input instanceof Request ? input.url : input, window.location.href);
  if (url.origin !== window.location.origin || !url.pathname.startsWith("/api/")) {
    return browserFetch(input, options);
  }
  const headers = new Headers(input instanceof Request ? input.headers : options.headers);
  new Headers(options.headers).forEach((value, name) => headers.set(name, value));
  headers.set("X-Railix-Creator-Token", creatorToken);
  return browserFetch(input, { ...options, headers });
};

const state = {
  project: null,
  builtProject: null,
  savedCreator: null,
  projectVersion: 0,
  editor: { nodes: {}, full: [], groups: {}, used: {} },
  editorRequest: 0,
  editorController: null,
  removedFlows: [],
  pendingPrune: false,
  writePromise: Promise.resolve(),
  creatorVersion: 0,
  creator: { format: 2, groups: [], steps: {} },
  catalog: [],
  definitions: new Map(),
  iconUrls: new Map(),
  themes: [],
  settings: {},
  settingsRevision: null,
  settingsVersion: 0,
  settingsTimer: 0,
  settingsWriting: false,
  hoverTrigger: null,
  hoverTimer: 0,
  hoverController: null,
  themesDirectory: "",
  themeError: "",
  themeController: null,
  application: {},
  audio: null,
  workspace: {},
  selection: { type: "app", id: "app" },
  regionSelection: null,
  groupPointer: null,
  diagnostics: [],
  localDiagnostics: [],
  build: "Loading",
  picker: null,
  pathPicker: null,
  pathDraft: [],
  pathField: "",
  pathIndex: "0",
  jsonDraft: null,
  stepQueries: {},
  candidateQueries: {},
  exampleIndex: 0,
  exampleTrigger: "",
  exampleDraft: null,
  exampleIds: new Map(),
  exampleInventoryKey: "",
  exampleCoverageKey: "",
  revision: 0,
  saveTimer: 0,
  writeActive: false,
  pendingWrite: null,
  runResult: "",
  preview: null,
  previewCases: [],
  traceCases: [],
  traceCasesKey: "",
  traceCasesPid: 0,
  traceContext: "",
  traceSummary: null,
  traceSummaryKey: "",
  traceStep: null,
  traceKey: "",
  traceController: null,
  optionsPending: false,
  applicationPollTimer: 0,
  applicationRefreshing: false,
  metrics: null,
  metricCatalog: null,
  metricsNode: "",
  metricsController: null,
  metricsPollTimer: 0,
  inspectorMode: "inspect",
  groupPicker: null,
  groupQuery: "",
  managedGroup: "",
  world: null,
  sceneDirty: true,
  revealNode: "",
  observations: null,
  groupMeans: new Map(),
  groupMeanKey: "",
  groupMeanTimer: 0,
  groupMeanController: null,
  observationTimer: 0,
  observationController: null,
  worldNodes: new Map(),
  worldGroups: new Map(),
  worldChanges: new Set(),
  worldMarkedRegions: new Map(),
  worldIssues: new Map(),
  worldCovered: new Set(),
  worldSelected: new Set(),
  coverageSource: null,
  coverageBits: "",
  pendingProject: false
};

const adjectives = [
  "atomic", "brisk", "calm", "exact", "iron", "latent",
  "lunar", "neon", "plain", "quiet", "rapid", "tiny"
];
const nouns = [
  "byte", "circuit", "forge", "kernel", "logic", "orbit",
  "quark", "relay", "signal", "thread", "vault", "vector"
];
boot();

function mutationHeaders() {
  return {
    "Content-Type": "application/json",
    "X-Railix-Creator-Token": creatorToken
  };
}

async function boot() {
  try {
    if (!exactJsonSupported()) {
      throw new Error("Browser does not support exact JSON numbers.");
    }
    const [projectResponse, catalogResponse] = await Promise.all([
      fetch("/api/editor"),
      fetch("/api/catalog")
    ]);
    if (!projectResponse.ok || !catalogResponse.ok) {
      throw new Error("Creator could not open the project.");
    }
    const [project, catalog] = await Promise.all([
      projectResponse.text().then(parseExact),
      catalogResponse.text().then(parseExact)
    ]);
    state.project = project.project;
    state.editor = project.editor;
    state.builtProject = clone(project.project);
    state.creator = project.creator || { format: 2, groups: [], steps: {} };
    state.savedCreator = clone(state.creator);
    state.projectVersion = project.revision;
    state.creatorVersion = project.creator_revision;
    state.application = project.application;
    state.workspace = project.workspace;
    state.diagnostics = project.diagnostics || [];
    state.catalog = catalog.steps;
    state.definitions = new Map(state.catalog.map(definition => [definition.id, definition]));
    state.build = "Built";
    state.audio = new RailixAudio(document.querySelector('#audio-panel'), changeSettings);
    state.world = new RailixWorld(document.querySelector("#graph"), {
      selectNode: selectWorldNode,
      deselect: clearSelection,
      selectGroup: (group, region, event) => {
        state.groupPointer = event?.detail === 1 ? {id:region, x:event.clientX, y:event.clientY} : null;
        state.editorRequest++;
        state.editorController?.abort();
        state.world.cancelFocus();
        state.regionSelection = state.world.scene.nodes.find(item => item.id === region);
        state.audio.cue(worldAppearance(state.regionSelection), true);
        state.inspectorMode = "inspect";
        state.picker = null;
        resetMetrics(true);
        render();
        showInspector(true);
        state.world.repaint();
      },
      appearance: worldAppearance,
      selectedId: () => state.regionSelection?.id || state.selection.id,
      linkAppearance: worldLinkAppearance,
      seed: () => numberText(state.creator.created_at ?? state.project.id),
      powered: () => state.application.state === "running",
      motionActive: worldMotionActive,
      reducedMotion: () => state.settings.reduced_motion === true,
      hoverTrigger,
      onGroup: group => {
        showInspector(false);
        const navigation = document.querySelector('#group-navigation');
        navigation.hidden = !group;
        navigation.querySelector('span').textContent = group ? `${group.name} (${count(group.count, 'Step')})` : '';
      },
      onActivity: level => state.audio.near(level),
      linkLabel: link => {
        const operation = state.worldNodes.get(link.from);
        const label = outcomeLabel(operation, link.outcome);
        const connection = operation && definitionFor(operation)?.kind !== "app" && state.editor.full.includes(link.from)
          ? outcomeConnection(operation, link.outcome) : null;
        return connection?.invalid ? `${label}: ${connection.label}` : label;
      },
      onScene: scene => {
        updateWorldMarks(scene);
        releaseUnusedIconUrls();
        const message = document.querySelector("#world-error");
        message.hidden = !scene.limited;
        message.dataset.severity = "warning";
        message.textContent = scene.limited ? "Zoom in for finer detail." : "";
        renderWorldStatus();
        scheduleWorldObservations(180);
      },
      onError: message => {
        const error = document.querySelector("#world-error");
        error.dataset.severity = "error";
        error.textContent = message;
        error.hidden = false;
      }
    });
    render();
    void refreshSettings();
    scheduleApplicationPoll(0);
    scheduleMetricsPoll(0);
  } catch (error) {
    state.build = "Unavailable";
    renderBuildStatus();
    const message = document.querySelector("#world-error");
    message.dataset.severity = "error";
    message.textContent = error instanceof Error ? error.message : "Creator could not open the project.";
    message.hidden = false;
  }
}

function render() {
  if (!state.project) {
    return;
  }
  document.title = `${state.project.id} - Railix Creator`;
  renderBuildStatus();
  const flows = triggerNodes();
  document.querySelector("#flow-count").textContent = count(
    workspaceCount("flow_count", flows.length),
    "flow"
  );
  document.querySelector("#step-count").textContent = count(
    workspaceCount("step_count", state.project.nodes.length),
    "step"
  );
  const builtAt = Number(state.application.built_at || 0);
  document.querySelector("#last-build").textContent = builtAt
    ? new Date(builtAt).toLocaleString()
    : "Not built";
  state.worldNodes = new Map(state.project.nodes.map(operation => [operation.id, operation]));
  state.worldGroups = new Map(state.creator.groups.map(group => [group.id, group]));
  updateWorldMarks(state.world?.scene);
  const inspectorElement = document.querySelector("#inspector");
  state.optionsPending = false;
  const openDetails = inspectorElement.dataset.selection === state.selection.id
    ? [...inspectorElement.querySelectorAll("details[open][id]")].map(detail => detail.id) : [];
  document.querySelector("#inspector-content").innerHTML = inspector();
  const navigation = document.querySelector("#inspector-content .inspector-tabs");
  document.querySelector("#inspector-nav").replaceChildren(...(navigation ? [navigation] : []));
  inspectorElement.dataset.selection = state.regionSelection?.id || state.selection.id;
  renderPreview();
  openDetails.forEach(id => {
    const detail = document.getElementById(id);
    if (detail) detail.open = true;
  });
  document.querySelector("#overlay").innerHTML = picker() + groupPicker();
  state.world?.preview(null);
  releaseUnusedIconUrls();
  applyExampleCoverage();
  if (state.sceneDirty && state.world) {
    state.sceneDirty = false;
    const reveal = state.revealNode;
    state.revealNode = "";
    if (reveal && reveal === state.selection.id) state.world.focus(reveal);
    else void state.world.refresh();
  }
  renderWorldStatus();
}

function updateWorldMarks(scene) {
  state.worldChanges = changedIds();
  state.worldIssues = new Map();
  for (const issue of allDiagnostics()) {
    const owner = diagnosticOwner(issue);
    const issues = state.worldIssues.get(owner) || [];
    issues.push(issue);
    state.worldIssues.set(owner, issues);
  }
  const marked = new Set([...state.worldChanges, ...state.worldIssues.keys()]);
  for (const id of state.worldMarkedRegions.keys()) if (!marked.has(id)) state.worldMarkedRegions.delete(id);
  for (const item of scene?.nodes || []) if (marked.has(item.id)) state.worldMarkedRegions.set(item.id, item.regions || []);
  for (const id of marked) for (const region of state.worldMarkedRegions.get(id) || []) {
    if (state.worldChanges.has(id)) state.worldChanges.add(region);
    if (state.worldIssues.has(id)) state.worldIssues.set(region, [...(state.worldIssues.get(region) || []), ...state.worldIssues.get(id)]);
  }
}

function renderDock() {
  renderTriggerChooser();
  const operation = selectedOperation();
  const region = state.regionSelection;
  const dock = document.querySelector("#selection-overview");
  if (!operation && !region) {
    return;
  }
  // Neighbors and retained drafts are not a loaded selection; new local Steps have no editor entry yet.
  if (!region && state.editor.nodes[operation.id] && !state.editor.full.includes(operation.id)) return;
  const definition = region ? {} : definitionFor(operation);
  const app = !region && state.selection.type === "app";
  const trigger = definition.kind === "trigger";
  const presentation = region ? {} : stepPresentation(operation.id);
  const name = region ? state.worldGroups.get(region.group)?.name || region.name
    : app ? state.project.id : presentation.name || stepName(definition);
  const actions = region ? '' : app
    ? `<button id="add-trigger" type="button" ${availableTriggers().length ? "" : "disabled"}><i class="hud-symbol" data-symbol="plus" aria-hidden="true"></i>Add Trigger</button>`
    : nextStepControls(operation);
  const navigation = `<button type="button" id="dock-focus" title="Focus (F)" aria-label="Focus"><i class="hud-symbol" data-symbol="locate" aria-hidden="true"></i></button>
    ${region ? '<button type="button" id="enter-region" title="Enter group (Enter)" aria-label="Enter group"><i class="hud-symbol" data-symbol="enter" aria-hidden="true"></i></button>' : ''}
    ${!document.querySelector('#group-navigation').hidden ? '<button type="button" data-leave-group title="Leave group" aria-label="Leave group"><i class="hud-symbol" data-symbol="back" aria-hidden="true"></i></button>' : ''}`;
  renderDockMarkup(document.querySelector('#inspector-navigation'), navigation);
  const deletion = state.inspectorMode === 'groups' || app ? '' : region
    ? region.group ? `data-delete-region="${html(region.group)}"` : ''
    : `id="delete-step" ${trigger || removableStep(operation) ? '' : 'disabled'}`;
  const footer = document.querySelector('#inspector-footer');
  renderDockMarkup(footer, deletion ? `<button type="button" ${deletion} class="danger" title="Delete ${html(name)}" aria-label="Delete ${html(name)}"><i class="hud-symbol" data-symbol="trash" aria-hidden="true"></i></button>` : '');
  footer.hidden = !deletion;
  const type = region ? 'Group' : app ? 'Application' : trigger ? 'Trigger'
    : definition.outcomes?.length > 1 ? stepName(definition) : 'Step';
  const source = `<header class="dock-heading"><div><small>${html(type)}</small>
      <h2>${html(name)}</h2>${region ? `<span>${html(count(region.count, 'Step'))}${region.group ? '' : ' · Automatic'}</span>` : ''}</div>
      <div class="selection-portrait" role="img" aria-label="${html(name)} building"><div></div></div></header>
    <div id="dock-observation"><div class="dock-issues"></div><div class="dock-values"></div><div class="dock-counters"></div></div>
    ${actions ? `<nav class="dock-actions" aria-label="Selection actions">${actions}</nav>` : ''}`;
  // Polling updates observations only; it must not replace a focused control.
  if (dock) {
    renderDockMarkup(dock, source);
    state.world?.portrait(region || {id:operation.id,use:operation.use,kind:definition.kind}, dock.querySelector('.selection-portrait > div'));
  }
  renderDockObservation();
}

function renderTriggerChooser() {
  const selected = selectedOperation();
  const trigger = state.hoverTrigger || (!state.regionSelection && definitionFor(selected)?.kind === 'trigger' ? selected : null);
  const chooser = document.querySelector('#trigger-example');
  const examples = trigger?.examples || [];
  renderDockMarkup(chooser, trigger ? `<label class="dock-example">Example
    <select id="dock-example" data-trigger="${html(trigger.id)}" aria-label="Selected Example"><option value="-1" ${state.exampleIndex < 0 ? 'selected' : ''}>None</option>${examples.map((example,index) =>
      `<option value="${index}" ${index === state.exampleIndex ? 'selected' : ''}>${html(example.name)}</option>`).join('')}</select>
    <button type="button" data-edit-trigger="${html(trigger.id)}">Edit</button></label>` : '');
  state.world?.anchor(trigger && !state.picker ? trigger.id : '', chooser);
}

function hoverTrigger(id) {
  clearTimeout(state.hoverTimer);
  state.hoverController?.abort();
  state.hoverTimer = setTimeout(async () => {
    if (!id) {
      const chooser = document.querySelector('#trigger-example');
      // Native select popups leave the hover tree without relinquishing keyboard focus.
      if (chooser.contains(document.activeElement) || chooser.matches(':hover')) return;
      state.hoverTrigger = null;
      renderTriggerChooser();
      return;
    }
    if (state.hoverTrigger?.id === id) return;
    const controller = state.hoverController = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 5000);
    try {
      const existing = state.editor.full.includes(id) ? node(id) : null;
      const response = existing ? null : await fetch(`/api/editor?${new URLSearchParams({node:id})}`, {signal:controller.signal});
      if (response && !response.ok) return;
      const trigger = existing || parseExact(await response.text()).project.nodes.find(item => item.id === id);
      if (!controller.signal.aborted) { state.hoverTrigger = trigger; renderTriggerChooser(); }
    } catch (error) {
      if (error.name !== 'AbortError') document.querySelector('#world-error').textContent = 'Trigger examples could not be read.';
    } finally { clearTimeout(timeout); }
  }, id ? 120 : 450);
}

function renderDockMarkup(element, source) {
  if (element.railixMarkup !== source) {
    const focused = element.contains(document.activeElement) ? document.activeElement.id : "";
    element.innerHTML = source;
    element.railixMarkup = source;
    if (focused) document.getElementById(focused)?.focus({ preventScroll: true });
  }
}

function renderDockObservation() {
  const slot = document.querySelector("#dock-observation");
  const operation = selectedOperation();
  const region = state.regionSelection;
  if (!slot || !region && (!operation || state.editor.nodes[operation.id] && !state.editor.full.includes(operation.id))) return;
  const item = currentWorldObservations()?.nodes.get(region?.id || operation.id);
  const measured = item?.metrics;
  const counters = !region && measured && Object.hasOwn(measured, "executions") ? `<span>${formatInteger(measured.executions)} runs</span>
    ${worldMotionActive() && Number.isFinite(item.rate) ? `<span>${formatRate(item.rate)}/s</span>` : ""}
    ${metricNumber(measured.duration_samples) ? `<span>${averageNanos(measured)} avg</span>` : ""}
    ${metricNumber(measured.errors) ? `<span class="dock-error">${formatInteger(measured.errors)} errors</span>` : ""}` : "";
  const issues = state.worldIssues.get(region?.id || operation.id) || [];
  const example = selectedTraceCase();
  let values = "";
  if (example && !region && state.selection.type !== "app") {
    const definition = definitionFor(operation);
    values = `${definition.kind === "trigger" ? "" : `<button type="button" id="dock-case" class="dock-case"
      data-locate-trigger="${html(triggerFor(operation.id).id)}" title="Choose an Example at its Trigger">${html(example.name)}</button>`}` + [false, true].filter(after =>
      after || definition.kind !== "trigger").map(after => {
      const observed = observedExampleContext(operation, after);
      const ports = after ? definition.returns : definition.receives;
      const field = definition.inputs.find(input => input.type === "path" && input.access !== "read")
        || definition.inputs.find(input => input.type === "path");
      const path = operation[after ? "returns" : "receives"]?.[ports[0]?.name]
        || operation.inputs?.[field?.name] || (field && defaultInput(field)) || ["context"];
      const value = observed.status ? undefined : valueAt(observed.root, path);
      const summary = Array.isArray(value) ? `Array (${value.length})`
        : plainObject(value) && !exactNumber(value) ? "Object"
        : typeof value === "string" ? JSON.stringify(value.slice(0, 80)) + (value.length > 80 ? "..." : "")
        : value === undefined ? "Missing" : previewValue(value);
      const label = ports.length || definition.kind === "trigger" ? after ? "Out" : "In" : after ? "After" : "Before";
      return `<div class="dock-value" data-dock-value="${after ? "output" : "input"}" title="${html(displayPath(path))}">
        <small>${label}</small>${observed.status
          ? `<span class="value-status">${html(observed.status)}</span>`
          : `<output>${html(summary)}</output>`}</div>`;
    }).join("");
  }
  for (const [part, source] of [["issues", issues.length
    ? `<button type="button" class="dock-error" data-open-panel="inspect">${html(issues[0].message)}</button>` : ""],
    ["values", values], ["counters", counters]]) {
    const element = slot.querySelector(`.dock-${part}`);
    renderDockMarkup(element, source);
    element.hidden = !source;
  }
}

function focusGroup(groupId) {
  void state.world?.focus("group:" + groupId);
}

function focusRegion(regionId) {
  void state.world?.enter(regionId);
}

function renderBuildStatus() {
  document.querySelector("#build-state").textContent = state.build === "Built"
    ? inputLabel(state.application.state || "running") : state.build;
  document.body.dataset.build = state.build.toLowerCase().replace(" ", "-");
}

async function selectWorldNode(id) {
  const item = state.world?.scene?.nodes.find(node => node.id === id);
  if (item) state.audio?.cue(worldAppearance(item));
  state.regionSelection = null;
  state.picker = null;
  state.revealNode = "";
  state.world?.cancelFocus();
  const loaded = await loadEditor(id);
  if (loaded !== "loaded" && !(loaded === "conflict" && state.editor.full.includes(id))) return false;
  const operation = node(id);
  if (!operation) return false;
  if (definitionOf(operation.use)?.kind === "step") {
    selectStep(id);
    showInspector(true);
    return true;
  }
  clearPreview();
  resetMetrics();
  state.exampleDraft = null;
  state.selection = { type: id === "app" ? "app" : "trigger", id };
  state.inspectorMode = "inspect";
  if (id !== "app" && state.exampleTrigger !== id) {
    state.exampleTrigger = id;
    state.exampleIndex = 0;
  }
  state.pathPicker = null;
  clearInputQueries();
  render();
  showInspector(true);
  scheduleMetricsPoll(0);
  if (id !== "app") requestSelectedTrace();
  return true;
}

async function loadEditor(id, group = state.managedGroup, query = state.groupQuery, offset = 0) {
  const request = ++state.editorRequest;
  state.editorController?.abort();
  const controller = new AbortController();
  state.editorController = controller;
  try {
    await state.writePromise;
    if (request !== state.editorRequest) return "superseded";
    const writer = state.writePromise;
    const version = state.projectVersion;
    const creatorVersion = state.creatorVersion;
    const response = await fetch(`/api/editor?${new URLSearchParams({ ...(id ? {node: id} : {}), group, q: query, offset })}`, { signal: controller.signal });
    const payload = parseExact(await response.text());
    if (request !== state.editorRequest) return "superseded";
    if (writer !== state.writePromise || state.writeActive
        || version !== state.projectVersion || creatorVersion !== state.creatorVersion) {
      return loadEditor(id, group, query, offset);
    }
    if (!response.ok) {
      state.localDiagnostics = [{ code: "CREATOR_EDITOR_UNAVAILABLE", message: payload.message || "Step could not be loaded.", node: id }];
      render();
      return "unavailable";
    }
    const projectChanges = documentChanges(state.builtProject, state.project);
    const creatorChanges = documentChanges(state.savedCreator, state.creator);
    if ((Object.keys(projectChanges).length || Object.keys(creatorChanges).length)
        && (Number(payload.revision) !== Number(state.projectVersion)
          || Number(payload.creator_revision) !== Number(state.creatorVersion))) {
      state.localDiagnostics = [{ code: "CREATOR_EDIT_CONFLICT", message: "The project changed in another editor. Your unsaved changes are retained; reload before saving.", node: id }];
      render();
      return "conflict";
    }
    // Retain only unsaved entries outside the selected neighborhood, never a navigation history.
    const retain = (loaded, before, changes) => {
      for (const key of ["nodes", "links", "groups", "steps"]) {
        if (!changes[key]) continue;
        if (key === "steps") {
          for (const id of Object.keys(changes[key])) {
            if (Object.hasOwn(before.steps, id)) loaded.steps[id] = before.steps[id];
          }
        } else if (loaded[key]) {
          const identity = key === "links" ? "from" : "id";
          const existing = new Set(loaded[key].map(entry => entry[identity]));
          loaded[key].push(...before[key].filter(entry => Object.hasOwn(changes[key], entry[identity]) && !existing.has(entry[identity])));
        }
      }
      return loaded;
    };
    const apply = (before, changes) => {
      const result = clone(before);
      for (const [key, values] of Object.entries(changes)) {
        if (["nodes", "links", "groups"].includes(key)) {
          const identity = key === "links" ? "from" : "id";
          result[key] = result[key].filter(entry => !Object.hasOwn(values, entry[identity]));
          result[key].push(...Object.values(values).filter(value => value !== null).flat());
        } else if (key === "steps") {
          Object.entries(values).forEach(([id, value]) => value === null ? delete result.steps[id] : result.steps[id] = value);
        } else result[key] = values;
      }
      return result;
    };
    state.builtProject = retain(payload.project, state.builtProject, projectChanges);
    const draft = state.jsonDraft && node(state.jsonDraft.node);
    if (draft && !state.builtProject.nodes.some(node => node.id === draft.id)) {
      state.builtProject.nodes.push(clone(draft));
      if (state.editor.nodes[draft.id]) payload.editor.nodes[draft.id] = state.editor.nodes[draft.id];
    }
    state.savedCreator = retain(payload.creator, state.savedCreator, creatorChanges);
    state.project = apply(state.builtProject, projectChanges);
    state.creator = apply(state.savedCreator, creatorChanges);
    state.editor = payload.editor;
    state.projectVersion = payload.revision;
    state.creatorVersion = payload.creator_revision;
    state.workspace = payload.workspace;
    if (!currentApplication(payload.application)) {
      replaceApplication(payload.application);
      resetMetrics();
    }
    return "loaded";
  } catch (error) {
    if (error.name !== "AbortError" && request === state.editorRequest) {
      state.localDiagnostics = [{ code: "CREATOR_EDITOR_UNAVAILABLE", message: "Step could not be loaded. Select it to retry.", node: id }];
      render();
    }
    return error.name === "AbortError" || request !== state.editorRequest ? "superseded" : "unavailable";
  } finally {
    if (state.editorController === controller) state.editorController = null;
  }
}

function worldAppearance(item) {
  const operation = state.worldNodes.get(item.id);
  const group = item.kind === "region" ? state.worldGroups.get(item.group) : null;
  const presentation = group || state.creator.steps[item.id] || {};
  const definition = definitionOf(item.use);
  const issues = state.worldIssues.get(item.id) || [];
  const observation = currentWorldObservations()?.nodes.get(item.id);
  const incoming = item.kind === "end" ? state.world.scene.links.find(link => link.to === item.id) : null;
  const arrival = incoming && worldLinkAppearance(incoming);
  const coverage = arrival?.selected ? "selected" : item.kind === "app" ? ""
    : observation && Object.hasOwn(observation, "covered_count") ? Number(observation.selected_count) > 0 ? "selected"
      : Number(observation.covered_count) > 0 ? "covered" : Number(observation.count) > 0 ? "uncovered" : ""
    : state.worldSelected.has(item.id) ? "selected"
    : state.worldCovered.has(item.id) ? "covered" : "";
  const live = item.kind !== "end" && observation?.metrics && Object.hasOwn(observation.metrics, "executions")
    ? observation.metrics : null;
  const timing = item.kind === "region" ? groupMean(item.id) : null;
  const sampled = item.kind === "region" ? timing?.sampled > 0 : live && metricNumber(live.duration_samples) > 0;
  const mean = sampled ? timing ? timing.sum : metricNumber(live.duration_nanos_total) / metricNumber(live.duration_samples) : 0;
  const activity = observation && Number(observation.count) > 0
    && Number(observation.disabled_count) === Number(observation.count) ? "disabled"
    : live ? metricNumber(live.executions) > 0 ? "active" : "idle" : "";
  const detail = activity === "disabled" ? "Metrics off"
    : live ? `${formatInteger(live.executions)} executions${observation.rate === undefined ? "" : ` · ${formatRate(observation.rate)}/s`}${sampled ? ` · ${formatNanos(mean)} sampled` : ""}`
    : observation && Object.hasOwn(observation, "covered_count") && item.kind === "region"
      ? `${observation.covered_count}/${observation.count} reached${Number(observation.selected_count) ? ` · ${observation.selected_count} selected` : ""}` : "";
  return {
    selected: (state.regionSelection?.id || state.selection.id) === item.id || Boolean(group
      && state.inspectorMode === "groups" && state.managedGroup === group.id),
    changed: state.worldChanges.has(item.id),
    error: issues.some(issue => issue.severity !== "warning") || Boolean(live && metricNumber(live.errors) > 0),
    warning: issues.some(issue => issue.severity === "warning") || item.kind === "app" && Boolean(state.themeError),
    never: item.kind !== "app" && item.kind !== "end" && Boolean(live && metricNumber(live.executions) === 0)
      && Number(observation?.disabled_count || 0) === 0,
    coverage,
    activity,
    rate: worldMotionActive() ? arrival?.rate || observation?.rate || 0 : 0,
    duration: sampled ? formatNanos(mean) : "",
    durationNanos: sampled ? mean : null,
    color: presentation.color || item.color,
    shape: presentation.shape || item.shape,
    aspect: Number(numberText(presentation.aspect ?? item.aspect ?? RailixWorld.appearance.aspect)),
    roundness: Number(numberText(presentation.roundness ?? item.roundness ?? RailixWorld.appearance.roundness)),
    boundary: presentation.boundary || item.boundary || (coverage === "uncovered" ? "dashed" : "solid"),
    iconUrl: iconUrl(presentation.icon || state.world?.scene?.icons?.[item.icon_ref]),
    showIcon: Boolean(presentation.icon),
    symbol: item.kind === "step" ? definition?.outcomes.length > 1 ? "branch"
      : definition?.returns[0]?.shape || "step" : item.kind,
    label: presentation.name || (item.kind === "app" && operation && stepName(definitionFor(operation))) || item.name,
    description: activity === "disabled" ? detail : live ? `${detail}. ${formatInteger(live.errors)} errors; ${formatInteger(live.cancelled)} cancellations. ${
      item.kind === "region" ? `Sum of means from ${timing?.sampled || 0}/${observation.count} sampled Steps; not passage latency. ` : ""}${sampled ? "Sampled execution time, not utilization." : "No duration inferred."}` : detail,
    detail: issues.length ? issues[0].message
      : item.kind === "trigger" ? `${count(operation?.examples?.length ?? item.example_count ?? 0, "example")}${
        activity === "disabled" ? " · Metrics off" : live ? ` · ${formatInteger(live.executions)} runs` : ""}`
      : detail || (operation && item.kind === "step" && state.editor.full.includes(item.id) ? operationSummary(operation).primary : "")
  };
}

function currentWorldObservations() {
  // Stable identities retain their last observation while the next viewport read is pending.
  // One bounded snapshot only; edits, deployments and Example changes still invalidate it.
  return state.observations && state.observations.revision === state.world?.scene?.revision
    && Number(state.observations.application_revision) === Number(state.projectVersion)
    && state.observations.requestedExample === selectedExampleId()
    && Number(state.observations.application_pid) === Number(state.application.pid)
    && state.application.state === "running" && state.build !== "Building" && !state.pendingProject ? state.observations : null;
}

function groupMeanKey() {
  return `${state.world?.scene?.revision}:${state.application.pid}:${state.projectVersion}`;
}

function groupMean(id) {
  const value = state.groupMeanKey === groupMeanKey() && currentWorldObservations() ? state.groupMeans.get(id) : null;
  return value?.offset === -1 ? value : null;
}

async function refreshGroupMeans() {
  clearTimeout(state.groupMeanTimer);
  if (state.groupMeanController || document.hidden || !currentWorldObservations()) return;
  const key = groupMeanKey();
  if (key !== state.groupMeanKey) {
    state.groupMeans.clear();
    state.groupMeanKey = key;
  }
  const visible = new Set(state.world.scene.nodes.filter(node => node.kind === "region" && !node.expanded).map(node => node.id));
  for (const id of state.groupMeans.keys()) if (!visible.has(id)) state.groupMeans.delete(id);
  for (const id of visible) if (!state.groupMeans.has(id)) state.groupMeans.set(id, {offset: 0, sum: 0, sampled: 0, at: 0});
  const now = performance.now();
  const candidate = [...state.groupMeans].filter(([, value]) => now >= value.at)
    .sort((a, b) => a[1].at - b[1].at)[0];
  if (!candidate) {
    state.groupMeanTimer = setTimeout(refreshGroupMeans, 1_000);
    return;
  }
  const [id, value] = candidate;
  if (value.offset === -1) Object.assign(value, {offset: 0, sum: 0, sampled: 0});
  const controller = new AbortController();
  state.groupMeanController = controller;
  const timeout = setTimeout(() => controller.abort(), 6_000);
  try {
    const parameters = new URLSearchParams({revision: state.world.scene.revision, members: id,
      cursor: String(value.offset), metrics: "duration_nanos_total,duration_samples"});
    const response = await fetch(`/api/scene/observations?${parameters}`, {signal: controller.signal, cache: "no-store"});
    if (!response.ok) throw new Error("Group counters unavailable");
    const page = parseExact(await response.text());
    if (key !== groupMeanKey() || Number(page.application_pid) !== Number(state.application.pid)
        || !state.world.scene.nodes.some(node => node.id === id && !node.expanded)) return;
    for (const metrics of Object.values(page.groups)) {
      const samples = metricNumber(metrics.duration_samples);
      if (samples > 0) { value.sum += metricNumber(metrics.duration_nanos_total) / samples; value.sampled++; }
    }
    value.offset = Number(page.next);
    value.at = performance.now() + (value.offset === -1 ? 10_000 : 100);
    state.world.repaint();
    renderDockObservation();
  } catch (_error) {
    Object.assign(value, {offset: 0, sum: 0, sampled: 0, at: performance.now() + 5_000});
  } finally {
    clearTimeout(timeout);
    state.groupMeanController = null;
    state.groupMeanTimer = setTimeout(refreshGroupMeans, 100);
  }
}

function worldLinkAppearance(link) {
  const readings = currentWorldObservations();
  const observation = readings?.links.get(link.id);
  // End markers have no counters. Only infer an unambiguous successful Example exit.
  const ended = observation?.selection === "unknown" && state.application.example?.id === selectedExampleId()
    && state.application.example?.status === "succeeded" && Number(readings?.nodes.get(link.from)?.selected_count) > 0
    && state.world.scene.nodes.some(node => node.id === link.to && node.kind === "end")
    && state.world.scene.links.filter(route => route.from === link.from).length === 1;
  const selected = observation?.selection === "reached" || ended;
  const rate = worldMotionActive() && Number.isFinite(observation?.rate) ? observation.rate : 0;
  return {
    selected,
    coverage: selected ? "reached" : observation?.selection || "unknown",
    rate
  };
}

function worldMotionActive() {
  const observation = currentWorldObservations();
  return Boolean(observation && performance.now() - observation.observedAt < 3_000);
}

function formatRate(rate) {
  return rate >= 100 ? Math.round(rate).toLocaleString() : rate.toFixed(rate >= 10 ? 1 : 2);
}

function scheduleWorldObservations(delay = 0) {
  clearTimeout(state.observationTimer);
  state.observationTimer = window.setTimeout(refreshWorldObservations, delay);
}

async function refreshWorldObservations() {
  state.observationTimer = 0;
  state.observationController?.abort();
  const status = document.querySelector("#status-observations");
  if (document.hidden || state.build === "Building" || state.pendingProject || state.application.state !== "running" || !state.world?.scene) {
    state.observations = null;
    state.world?.repaint();
    status.hidden = true;
    return;
  }
  const example = selectedExampleId();
  const query = state.world.query;
  const revision = state.world.scene.revision;
  const pid = state.application.pid;
  const controller = new AbortController();
  state.observationController = controller;
  const parameters = new URLSearchParams(query);
  parameters.set("revision", revision);
  if (example) parameters.set("example", example);
  let repaint = true;
  try {
    const response = await fetch(`/api/scene/observations?${parameters}`, { signal: controller.signal, cache: "no-store" });
    if (!response.ok) throw new Error("Observations unavailable");
    const value = parseExact(await response.text());
    if (state.observationController !== controller || selectedExampleId() !== example || state.world.query !== query
        || state.world.scene.revision !== revision || Number(state.application.pid) !== Number(pid)
        || Number(value.application_pid) !== Number(pid) || value.revision !== revision) return;
    const nodes = new Map(value.nodes.map(node => [node.id, node]));
    const links = new Map(value.links.map(link => [link.id, link]));
    const previous = currentWorldObservations();
    const observedAt = performance.now();
    const elapsed = previous?.elapsed_nanos !== undefined && value.elapsed_nanos !== undefined
      ? Number(BigInt(numberText(value.elapsed_nanos)) - BigInt(numberText(previous.elapsed_nanos))) / 1e9 : 0;
    for (const [current, before] of [[nodes, previous?.nodes], [links, previous?.links]]) {
      if (elapsed <= 0) continue;
      for (const [id, item] of current) {
        const prior = before.get(id);
        if (item.metrics?.executions === undefined || prior?.metrics?.executions === undefined) continue;
        const delta = BigInt(numberText(item.metrics.executions)) - BigInt(numberText(prior.metrics.executions));
        if (delta >= 0n) item.rate = Number(delta) / elapsed;
      }
    }
    repaint = !previous || [[nodes, previous.nodes], [links, previous.links]].some(([current, before]) =>
      current.size !== before.size || [...current].some(([id, item]) => {
        const prior = before.get(id);
        return !prior || Object.keys(item).length !== Object.keys(prior).length
          || Object.keys(item).some(key => JSON.stringify(item[key]) !== JSON.stringify(prior[key]));
      }));
    state.observations = { ...value, requestedExample: example, query, nodes, links, observedAt };
    if (!state.groupMeanController) {
      clearTimeout(state.groupMeanTimer);
      state.groupMeanTimer = setTimeout(refreshGroupMeans, 100);
    }
    const metricsAvailable = value.nodes.some(node => Object.hasOwn(node, "metrics"));
    const examplesAvailable = Object.hasOwn(value, "coverage_revision");
    status.hidden = metricsAvailable || examplesAvailable;
    status.textContent = status.hidden ? '' : 'Observations unavailable';
  } catch (error) {
    if (controller.signal.aborted || state.observationController !== controller) return;
    state.observations = null;
    status.hidden = false;
    status.textContent = "Observations unavailable";
  } finally {
    if (state.observationController === controller) {
      state.observationController = null;
      if (repaint) state.world?.repaint();
      renderWorldStatus();
      scheduleWorldObservations(1_000);
    }
  }
}

function renderWorldStatus() {
  if (!state.project) return;
  renderBuildStatus();
  const metrics = Number(state.metrics?.application_pid) === Number(state.application.pid)
    && state.application.state === "running" ? state.metrics : null;
  const process = metrics?.process;
  const values = {
    "status-pid": state.application.state === "running" ? `PID ${state.application.pid}` : "",
    "status-uptime": process ? `Up ${formatMillis(process.uptime_millis)}` : "",
    "status-memory": process ? `Heap ${formatBytes(process.heap_used_bytes)}` : "",
    "status-cpu": process?.process_cpu_load_ppm !== undefined
      ? `CPU ${formatPercentPpm(process.process_cpu_load_ppm)}` : "",
    "status-executions": metrics ? `${formatInteger(metrics.application.metrics.executions)} executions` : ""
  };
  for (const [id, value] of Object.entries(values)) {
    const element = document.getElementById(id);
    element.textContent = value;
    element.hidden = !value;
  }
  const examples = state.application.examples;
  const measurable = examples?.state === "completed" && state.build === "Built";
  const total = Number(state.workspace.step_count) - 1;
  const covered = Number(examples?.covered_steps || 0);
  const coverage = document.querySelector("#status-coverage");
  coverage.hidden = !measurable || total === 0;
  coverage.textContent = `${Math.round(covered / Math.max(1, total) * 100)}% coverage`;
  coverage.title = `${covered} of ${total} executable Steps reached by completed Examples`;
  renderDock();
}

function refreshPathPicker() {
  const current = document.querySelector(".path-browser");
  const operation = selectedOperation();
  if (!current || !operation || !state.pathPicker) {
    return false;
  }
  const template = document.createElement("template");
  template.innerHTML = pathBrowser(
    state.pathPicker.input,
    operation,
    state.pathPicker.locator
  ).trim();
  const desired = template.content.firstElementChild;
  const currentChoices = current.querySelector(".path-choices");
  const desiredChoices = desired?.querySelector(".path-choices");
  if (!currentChoices || !desiredChoices) {
    return false;
  }
  const existing = new Map([...currentChoices.querySelectorAll(".path-choice")]
    .map(choice => [choice.dataset.pathDraftJson, choice]));
  const retained = new Set();
  desiredChoices.querySelectorAll(".path-choice").forEach(choice => {
    const key = choice.dataset.pathDraftJson;
    const rendered = existing.get(key) || choice;
    rendered.innerHTML = choice.innerHTML;
    currentChoices.append(rendered);
    retained.add(key);
  });
  existing.forEach((choice, key) => {
    if (!retained.has(key)) {
      choice.remove();
    }
  });
  currentChoices.querySelectorAll(".path-choices-label, .path-hint").forEach(element => element.remove());
  const label = desiredChoices.querySelector(".path-choices-label");
  const hint = desiredChoices.querySelector(".path-hint");
  if (label) {
    currentChoices.prepend(label);
  }
  if (hint) {
    currentChoices.append(hint);
  }
  const currentCreate = current.querySelector(".path-create");
  const desiredCreate = desired.querySelector(".path-create");
  const createMode = element => element?.querySelector("#new-path-index") ? "index"
    : element ? "field" : "";
  if (createMode(currentCreate) !== createMode(desiredCreate)) {
    if (currentCreate && desiredCreate) {
      currentCreate.replaceWith(desiredCreate);
    } else if (currentCreate) {
      currentCreate.remove();
    } else if (desiredCreate) {
      current.querySelector(".path-actions").before(desiredCreate);
    }
  }
  current.querySelector("#apply-path").disabled = desired.querySelector("#apply-path").disabled;
  return true;
}

function operationSummary(operation) {
  const definition = definitionFor(operation);
  const summary = configuredSummary(operation, definition.inputs, ["inputs"]);
  return {
    primary: summary.paths.length ? summary.paths.join(" → ") : operation.id,
    secondary: [...summary.options, ...summary.steps].join(" / ") || definition.id
  };
}

function configuredSummary(operation, inputs, base) {
  const summary = { paths: [], options: [], steps: [] };
  inputs.forEach(input => {
    const locator = [...base, input.name];
    const value = valueAt(operation, locator);
    if (input.type === "path" && Array.isArray(value)) {
      summary.paths.push(displayPath(value));
    } else if (input.type === "options") {
      const option = input.options.find(candidate => candidate.name === value?.option);
      if (option) {
        summary.options.push(inputLabel(option.name));
        mergeSummary(summary, configuredSummary(operation, option.inputs, [...locator, "inputs"]));
      }
    } else if (input.type === "candidates") {
      (value || []).forEach((candidate, index) => {
        const option = input.options.find(available => available.name === candidate.option);
        if (!option) {
          return;
        }
        summary.options.push(inputLabel(option.name));
        mergeSummary(summary, configuredSummary(operation, option.inputs, [...locator, index, "inputs"]));
        summary.steps.push(...conditionSteps(candidate.when).map(step => stepName(definitionOf(step.use))));
      });
    } else if (input.type === "matcher_groups") {
      (value || []).forEach((group, groupIndex) => group.forEach((matcher, matcherIndex) => {
        const option = input.options.find(available => available.name === matcher.option);
        if (!option) {
          return;
        }
        summary.options.push(inputLabel(option.name));
        mergeSummary(summary, configuredSummary(
          operation,
          option.inputs,
          [...locator, groupIndex, matcherIndex, "inputs"]
        ));
        summary.steps.push(...conditionSteps(matcher.when).map(step => stepName(definitionOf(step.use))));
      }));
    } else if (input.type === "steps") {
      summary.steps.push(...(value || []).map(step => stepName(definitionOf(step.use))));
    }
  });
  return summary;
}

function conditionSteps(value) {
  const condition = conditionOf(value);
  return [...condition.transforms, ...condition.all.flat()];
}

function mergeSummary(target, source) {
  target.paths.push(...source.paths);
  target.options.push(...source.options);
  target.steps.push(...source.steps);
}

function inspector() {
  if (!state.selection.id && !state.regionSelection) return "";
  if (state.regionSelection) {
    const region = state.regionSelection, group = state.creator.groups.find(group => group.id === region.group);
    if (!['appearance','groups'].includes(state.inspectorMode)) state.inspectorMode = "overview";
    const modes = [["overview", "Overview"], ...(group ? [["appearance", "Appearance"]] : []), ['groups','Groups']];
    const tabs = `<nav class="inspector-tabs" aria-label="Inspector mode" style="--tab-count:${modes.length}">${modes.map(([id, label]) =>
      `<button type="button" data-inspector-mode="${id}" class="${state.inspectorMode === id ? "active" : ""}">${label}</button>`).join("")}</nav>`;
    return tabs + (state.inspectorMode === 'groups' ? manageGroupsInspector() : state.inspectorMode === "appearance" && group
      ? presentationEditor(group, {name: region.name}, "group:" + group.id)
      : `<section id="selection-overview"></section><section class="inspector-section">${issueList(region.id)}</section>`
        + metricFacts("Group metrics", [...metricRows(currentWorldObservations()?.nodes.get(region.id)?.metrics),
          ...(groupMean(region.id)?.sampled ? [['Sum of Step averages',formatNanos(groupMean(region.id).sum)]] : [])]));
  }
  const modes = [
    ["overview", "Overview"],
    ["inspect", "Inputs"],
    ["appearance", "Appearance"],
    ["groups", "Groups"],
    ...(state.selection.type === "trigger" ? [["examples", "Examples"]] : [])
  ];
  if (!modes.some(([mode]) => mode === state.inspectorMode)) {
    state.inspectorMode = "inspect";
  }
  const tabs = `<nav class="inspector-tabs" aria-label="Inspector mode"
                     style="--tab-count:${modes.length}">${modes.map(([mode, label]) => `
    <button type="button" data-inspector-mode="${mode}" class="${
      state.inspectorMode === mode ? "active" : ""
    }">${label}</button>`).join("")}</nav>`;
  if (state.inspectorMode === "overview") return tabs + '<section id="selection-overview"></section>';
  if (state.inspectorMode === 'groups') return tabs + manageGroupsInspector();
  if (state.inspectorMode === "examples") {
    return tabs + examplesInspector(node(state.selection.id));
  }
  if (state.inspectorMode === "appearance") {
    return tabs + appearanceInspector();
  }
  if (state.selection.type === "trigger") {
    const trigger = node(state.selection.id);
    return tabs + (trigger
      ? triggerInspector(trigger, issueList(trigger.id))
      : appInspector(issueList("app")));
  }
  if (state.selection.type === "step") {
    const operation = node(state.selection.id);
    return tabs + (operation
      ? stepInspector(operation, issueList(operation.id))
      : appInspector(issueList("app")));
  }
  return tabs + appInspector(issueList("app"));
}

function appearanceInspector() {
  const operation = selectedOperation();
  const definition = definitionFor(operation);
  if (definition?.kind === "app") return `${inspectorHeader("Application", state.project.id, "")}
    <section class="inspector-section">
      ${state.creator.created_at !== undefined ? `<p>Created ${html(new Date(Number(numberText(state.creator.created_at))).toLocaleString())}</p>` : ""}
    </section>${presentationEditor(stepPresentation(operation.id), {name: stepName(definition)}, "step:" + operation.id)}`;
  return operation && definition
    ? `${inspectorHeader(
        definition.kind === "trigger" ? "Trigger" : "Step",
        stepPresentation(operation.id).name || stepName(definition),
        operation.id
      )}${presentationEditor(
        stepPresentation(operation.id),
        { name: stepName(definition) },
        "step:" + operation.id
      )}${definition.kind === "step" ? groupAssignment(operation) : ""}`
    : appInspector(issueList("app"));
}

function selectedTheme() {
  const selected = state.settings.theme || '';
  return state.themes.find(theme => theme.id === selected)
    || state.themes.find(theme => theme.stylesheet === selected);
}

function themeOptions() {
  const selected = selectedTheme()?.id ?? state.settings.theme ?? '';
  const themes = state.themes.some(theme => theme.id === selected) || !selected ? state.themes
    : [...state.themes, {id:selected, name:selected + " (unavailable)"}];
  return themes.map(theme =>
    `<option value="${html(theme.id)}" ${theme.id === selected ? "selected" : ""}>${html(theme.name)}</option>`).join("");
}

function reflectTheme() {
  const theme = selectedTheme();
  const variants = theme?.variants || [], selected = state.settings.theme_variant || theme?.defaultVariant || '';
  const available = variants.some(item => item.id === selected);
  document.querySelector('#theme-select').innerHTML = themeOptions();
  document.querySelector('#theme-variant-field').hidden = !variants.length && !selected;
  document.querySelector('#theme-variant').innerHTML = [
    ...(!available ? [{id:selected, name:selected ? `${selected} (unavailable)` : 'Default'}] : []), ...variants
  ].map(item => `<option value="${html(item.id)}" ${item.id === selected ? 'selected' : ''}>${html(item.name)}</option>`).join('');
  document.querySelector('#theme-description').textContent = variants.find(item => item.id === selected)?.description || '';
  document.querySelector('#theme-origin').textContent = theme?.builtin ? 'Built in. No local files required.' : 'Local stylesheet over the embedded default.';
  document.querySelector('#theme-download').disabled = !theme?.installable;
  const file = theme?.stylesheet || theme?.id || '';
  document.querySelector('#theme-structure').textContent = `${file}\n${file.replace(/\.css$/, '.json')} (optional names, variants and assets)`;
}

async function refreshSettings() {
  if (state.settingsWriting || state.settingsTimer) return;
  const version = state.settingsVersion;
  try {
    const response = await fetch('/api/settings', {cache:'no-store', signal:AbortSignal.timeout(5000)});
    const payload = await response.json();
    if (!response.ok) throw Error(payload.message || 'Settings could not be read.');
    if (version !== state.settingsVersion) return;
    state.settings = payload.values;
    state.settingsRevision = payload.revision;
    applySettings();
    document.querySelector('#settings-status').textContent = payload.diagnostics.map(item => item.message).join(' ');
    await refreshThemes();
  } catch (error) { document.querySelector('#settings-status').textContent = error.message; }
}

function applySettings() {
  reflectSettings();
  document.querySelector('#reduced-motion').checked = state.settings.reduced_motion === true;
  state.audio?.applyPreferences(state.settings);
}

function reflectSettings() {
  const motion = String(state.settings.reduced_motion === true);
  if (document.body.dataset.reducedMotion !== motion) {
    document.body.dataset.reducedMotion = motion;
    state.world?.repaint();
  }
  for (const button of document.querySelectorAll('[data-audio-preference]')) {
    button.setAttribute('aria-pressed', String(state.settings[button.dataset.audioPreference] !== false));
  }
}

function selectSettingsTab(tab) {
  for (const name of ['appearance', 'sound', 'music']) {
    const selected = name === tab;
    document.querySelector(`#settings-${name}-tab`).setAttribute('aria-selected', String(selected));
    document.querySelector(`#settings-${name}`).hidden = !selected;
  }
  if (tab === 'sound' || tab === 'music') state.audio?.setEditorKind(tab);
}

document.querySelector('.settings-tabs').addEventListener('keydown', event => {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
  const tabs = [...event.currentTarget.querySelectorAll('[role=tab]')];
  const current = tabs.indexOf(document.activeElement);
  const index = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
    : (current + (event.key === 'ArrowRight' ? 1 : tabs.length - 1)) % tabs.length;
  tabs[index].focus();
  selectSettingsTab(tabs[index].dataset.settingsTab);
  event.preventDefault();
});

function changeSettings(patch) {
  if (Object.entries(patch).every(([key,value]) => JSON.stringify(state.settings[key]) === JSON.stringify(value))) return;
  Object.assign(state.settings, patch);
  state.settingsVersion++;
  reflectSettings();
  clearTimeout(state.settingsTimer);
  state.settingsTimer = setTimeout(() => { state.settingsTimer = 0; void saveSettings(); }, 150);
}

async function saveSettings() {
  if (state.settingsWriting) return;
  if (state.settingsRevision === null) {
    document.querySelector('#settings-status').textContent = 'Settings are not loaded. Reopen Settings before saving.';
    return;
  }
  state.settingsWriting = true;
  const version = state.settingsVersion;
  try {
    const response = await fetch('/api/settings', {method:'POST', headers:mutationHeaders(),
      body:JSON.stringify({revision:state.settingsRevision, values:state.settings}), signal:AbortSignal.timeout(5000)});
    const result = await response.json();
    if (!response.ok) throw Error(result.message || 'Settings could not be saved.');
    state.settingsRevision = result.revision;
    document.querySelector('#settings-status').textContent = '';
  } catch (error) {
    document.querySelector('#settings-status').textContent = error.message + ' Reopen Settings to reload saved preferences.';
    return;
  } finally { state.settingsWriting = false; }
  if (version !== state.settingsVersion) void saveSettings();
}

async function downloadTheme() {
  const theme = selectedTheme();
  if (!theme) { themeStatus('Selected theme is unavailable.'); return; }
  try {
    const copied = await fetch('/api/themes', {method:'POST', headers:mutationHeaders(),
      body:JSON.stringify({action:'install', id:theme.id}), signal:AbortSignal.timeout(5000)});
    const result = await copied.json();
    if (!copied.ok) throw Error(result.message || 'Theme copy was rejected.');
    await refreshThemes();
    themeStatus(`Installed ${result.installed} files; kept ${result.skipped} existing files.`, false);
  } catch (error) { themeStatus(error.message); }
}

async function refreshThemes() {
  state.themeController?.abort();
  const controller = new AbortController();
  state.themeController = controller;
  try {
    const response = await fetch("/api/themes", {signal:controller.signal, cache:"no-store"});
    if (!response.ok) throw new Error("Themes could not be read.");
    const catalog = await response.json();
    if (controller.signal.aborted) return;
    state.themes = catalog.themes;
    state.themesDirectory = catalog.directory;
    reflectTheme();
    const folder = document.querySelector(".theme-folder");
    if (folder) folder.textContent = catalog.directory;
    await applyTheme();
    if (catalog.diagnostics?.length) themeStatus(catalog.diagnostics.map(item => item.message).join(' '));
  } catch (error) {
    if (error.name !== "AbortError") themeStatus(error.message);
  }
}

async function applyTheme() {
  state.themeController?.abort();
  const controller = new AbortController();
  state.themeController = controller;
  const selected = state.settings.theme;
  const pending = [], anchor = document.querySelector('#theme-style');
  const previous = [...document.querySelectorAll('link[data-theme-loaded]')];
  try {
    const theme = selectedTheme();
    if (!theme) throw Error(`Theme ${selected} is unavailable. Railix styling is shown.`);
    const variantId = state.settings.theme_variant || theme.defaultVariant || '';
    const variant = theme.variants?.find(item => item.id === variantId);
    if (variantId && !variant) throw Error(`Variant ${variantId} is unavailable. Railix styling is shown.`);
    // Stage real stylesheets without applying them, so relative assets keep their native URL base.
    for (const url of [theme.url, ...(variant ? [variant.url] : [])]) {
      await new Promise((resolve, reject) => {
        const link = document.createElement('link');
        link.rel = 'stylesheet'; link.media = 'not all'; link.href = url; pending.push(link);
        const abort = () => finish(new DOMException('Theme loading cancelled', 'AbortError'));
        const timer = setTimeout(() => finish(Error('Theme loading timed out. Railix styling is shown.')), 5000);
        const finish = error => {
          clearTimeout(timer); controller.signal.removeEventListener('abort', abort);
          link.onload = link.onerror = null;
          error ? reject(error) : resolve();
        };
        link.onload = () => finish();
        link.onerror = () => finish(Error(`Theme ${selected || theme.name} is unavailable. Railix styling is shown.`));
        controller.signal.addEventListener('abort', abort, {once:true});
        if (controller.signal.aborted) abort(); else anchor.before(link);
      });
    }
    if (controller.signal.aborted) return;
    await state.world?.renderer(variant || {}, controller.signal, () => {
      for (const link of pending) { link.media = 'all'; link.dataset.themeLoaded = ''; }
      previous.forEach(link => link.remove());
    });
    pending.length = 0;
    reflectTheme();
    themeStatus("");
    state.world?.repaint();
    window.dispatchEvent(new Event("resize"));
  } catch (error) {
    if (error.name === "AbortError") return;
    previous.forEach(link => link.remove());
    await state.world?.renderer();
    themeStatus(error.message);
    window.dispatchEvent(new Event("resize"));
  } finally { pending.forEach(link => link.remove()); }
}

function themeStatus(message, error = true) {
  state.themeError = error ? message : '';
  const status = document.querySelector("#theme-status");
  if (status) status.textContent = message;
  state.world?.repaint();
}

function manageGroupsInspector() {
  const query = state.groupQuery.trim().toLowerCase();
  const available = state.creator.groups.filter(group => groupName(group).toLowerCase().includes(query));
  const inventory = groupInventory();
  const selected = state.creator.groups.find(group => group.id === state.managedGroup)
    || state.creator.groups[0] || null;
  if (selected) {
    state.managedGroup = selected.id;
  }
  const members = selected ? inventory.get(selected.id) || 0 : 0;
  const regions = Number(state.editor.groups[selected?.id]?.regions || 0);
  return `
    <header class="manager-heading">
      ${inspectorHeader("Creator", "Group Manager", "Presentation only")}
    </header>
    <section class="inspector-section">
      <div class="section-heading"><strong>Groups</strong><span>${state.editor.group_count}</span></div>
      <input id="group-search" type="search" value="${html(state.groupQuery)}"
             placeholder="Search groups" autocomplete="off">
      <div id="group-results">${groupListMarkup(
        available,
        selected?.id || "",
        inventory
      )}</div>
      <button class="button primary wide" type="button" id="new-group">New Group</button>
    </section>
    ${selected ? `
      ${presentationEditor(selected, { name: "Group", color: "#147982" }, "group:" + selected.id)}
      <section class="inspector-section">
        <label for="group-boundary">Boundary</label>
        <select id="group-boundary">
          ${["solid", "dashed", "dotted"].map(boundary => `<option value="${boundary}" ${
            (selected.boundary || "solid") === boundary ? "selected" : ""
          }>${inputLabel(boundary)}</option>`).join("")}
        </select>
      </section>
      <section class="inspector-section facts">
        <dl>
          <div><dt>Steps</dt><dd>${members}</dd></div>
          <div><dt>Regions</dt><dd>${regions}</dd></div>
        </dl>
      </section>
      <footer class="inspector-actions">
        <button class="button" type="button" id="focus-group" ${regions ? "" : "disabled"}>Show</button>
        <button class="button danger" type="button" id="delete-group" title="Delete ${html(groupName(selected))}" aria-label="Delete ${html(groupName(selected))}"><i class="hud-symbol" data-symbol="trash" aria-hidden="true"></i></button>
      </footer>` : ""}`;
}

function groupListMarkup(groups, selected, inventory = groupInventory()) {
  return `<div id="group-list" class="group-list">${groups.map(group => `
    <button type="button" data-manage-group="${html(group.id)}"
            class="${selected === group.id ? "active" : ""}"
            style="--group-color:${html(group.color || "#147982")}">
      <strong>${html(groupName(group))}</strong>
      <span>${count(inventory.get(group.id) || 0, "Step")}</span>
    </button>`).join("") || '<p class="empty-options">No matching groups.</p>'}</div>${groupPages()}`;
}

function groupAssignment(operation) {
  const group = groupForStep(operation.id);
  return `<section class="inspector-section">
    <label>Group</label>
    <div class="group-assignment">
      <button class="button" type="button" id="choose-group">${html(group ? groupName(group) : "No group")}</button>
    </div>
  </section>`;
}

function presentationEditor(presentation = {}, defaults = {}, target) {
  const inheritedColor = defaults.color || "#147982";
  const color = /^#[0-9a-fA-F]{6}$/.test(presentation.color || inheritedColor)
    ? (presentation.color || inheritedColor).toUpperCase()
    : "#147982";
  return `
    <section class="inspector-section presentation-editor">
      <div class="section-heading"><strong>Appearance</strong><span>Creator only</span></div>
      <label for="presentation-name">Name</label>
      <input id="presentation-name" data-presentation="name" data-presentation-target="${target}"
             maxlength="128" value="${html(presentation.name || "")}" placeholder="${html(defaults.name || "Automatic")}">
      <label for="presentation-color">Color</label>
      <div class="color-editor">
        <input id="presentation-color-picker" type="color" value="${html(color.toLowerCase())}"
               data-color-picker="${target}" aria-label="Choose color">
        <input id="presentation-color" data-presentation="color" data-presentation-target="${target}"
               maxlength="7" value="${html(presentation.color || "")}"
               placeholder="${html(defaults.color || "#RRGGBB")}" autocomplete="off">
        <button id="reset-color" type="button" data-reset-presentation="color"
                data-presentation-target="${target}" ${presentation.color ? "" : "disabled"}>Reset</button>
      </div>
      <label for="presentation-shape">Shape</label>
      <select id="presentation-shape" data-presentation="shape" data-presentation-target="${target}">
        ${["rectangle", "ellipse", "triangle", "diamond", "hexagon", "event", "storage", "subsystem"].map(shape => `<option value="${shape}" ${
          shape === (presentation.shape || "rectangle") ? "selected" : ""}>${inputLabel(shape)}</option>`).join("")}
      </select>
      ${[["aspect", "Width / height", .5, 4], ["roundness", "Corner rounding (%)", 0, 50]]
        .filter(([field]) => field !== "roundness" || !presentation.shape || presentation.shape === "rectangle")
        .map(([field, label, min, max]) => `<label for="presentation-${field}">${label}</label>
          <div class="dimension-editor"><input id="presentation-${field}" type="number" min="${min}" max="${max}" step="any"
            data-presentation="${field}" data-presentation-target="${target}" value="${numberText(presentation[field] ?? RailixWorld.appearance[field])}">
            <button type="button" data-reset-presentation="${field}" data-presentation-target="${target}"
              ${presentation[field] === undefined ? "disabled" : ""}>Reset</button></div>`).join("")}
    </section>`;
}

function inspectorHeader(kicker, title, subtitle) {
  return `
    <header class="inspector-heading">
      <span class="eyebrow">${html(kicker)}</span>
      <h2>${html(title)}</h2>
      <p>${html(subtitle)}</p>
    </header>`;
}

function issueList(owner) {
  const diagnostics = allDiagnostics().filter(diagnostic => diagnosticOwner(diagnostic) === owner);
  if (!diagnostics.length) {
    return "";
  }
  return `
    <section class="issues" aria-label="Errors and warnings">
      ${diagnostics.map(diagnostic => `
        <article data-severity="${html(diagnostic.severity || "error")}">
          <strong>${html(diagnostic.code || "CREATOR_ERROR")}</strong>
          <p>${html(diagnostic.message || "Project is not buildable.")}</p>
          ${diagnostic.path ? `<code>${html(diagnostic.path)}</code>` : ""}
        </article>`).join("")}
    </section>`;
}

function allDiagnostics() {
  const diagnostics = [
    ...state.localDiagnostics,
    ...state.diagnostics
  ];
  const draft = state.jsonDraft;
  if (draft && node(draft.node)) {
    diagnostics.unshift({
      code: "PROJECT_JSON_VALUE_INVALID",
      message: "Value must be valid JSON.",
      path: draft.path || inputDiagnosticPath(draft.node, draft.locator),
      node: draft.node,
      flow: draft.flow
    });
  }
  return diagnostics;
}

function diagnosticOwner(diagnostic) {
  if (diagnostic.node) {
    return diagnostic.node;
  }
  const creatorStep = /^steps\.([^.]+)/.exec(diagnostic.path || "");
  if (creatorStep && node(creatorStep[1])) {
    return creatorStep[1];
  }
  const nodePath = /^nodes\[(\d+)]/.exec(diagnostic.path || "");
  if (nodePath) {
    return Object.entries(state.editor.nodes).find(([, value]) => Number(value.index) === Number(nodePath[1]))?.[0] || "app";
  }
  const linkPath = /^links\[(\d+)]/.exec(diagnostic.path || "");
  if (linkPath) {
    const link = state.project.links[Number(linkPath[1])];
    return link ? linkNode(link) : "app";
  }
  const triggers = triggerNodes();
  return (diagnostic.path || "").startsWith("context.") && triggers.length === 1
    ? triggers[0].id
    : "app";
}

function nodeIssues(id) {
  return allDiagnostics().filter(diagnostic => diagnosticOwner(diagnostic) === id);
}

function appInspector(issues) {
  return `
    ${inspectorHeader("Application Step", "Application", "Project settings")}
    ${issues}
    <section class="inspector-section">
      <label for="project-id">Project name</label>
      <input id="project-id" value="${html(state.project.id)}" autocomplete="off">
    </section>
    ${runtimeDetails()}`;
}

function buildDetails() {
  const builtAt = Number(state.application.built_at || 0);
  return `<header><strong>Application and build</strong>
      <button type="button" popovertarget="application-status" popovertargetaction="hide" aria-label="Close build details">Close</button></header>
    <section id="workspace-details" class="facts">
      <dl>
        <div><dt>Project path</dt><dd id="project-path">${html(state.workspace.project_path || "")}</dd></div>
        <div><dt>Build path</dt><dd id="build-path">${html(state.application.build_path || "")}</dd></div>
        <div><dt>PID</dt><dd id="application-pid">${html(state.application.pid || "")}</dd></div>
        <div><dt>Build state</dt><dd id="application-build-state">${html(state.build)}</dd></div>
        <div><dt>Run state</dt><dd id="application-run-state">${html(
          inputLabel(state.application.state || "unavailable")
        )}</dd></div>
        <div><dt>Graph</dt><dd>${count(workspaceCount("flow_count", triggerNodes().length), "flow")} / ${
          count(workspaceCount("step_count", state.project.nodes.length), "step")
        }</dd></div>
        <div><dt>Examples</dt><dd id="example-suite-progress">${html(exampleProgress())}</dd></div>
        <div><dt>Example state</dt><dd id="example-suite-state">${html(
          inputLabel(state.application.examples?.state || "unavailable")
        )}</dd></div>
        <div><dt>Trace storage</dt><dd id="example-trace-storage">${html(
          formatBytes(state.application.examples?.storage_bytes)
        )}</dd></div>
        <div><dt>Last build</dt><dd id="application-last-build">${
          builtAt ? html(new Date(builtAt).toLocaleString()) : "Not built"
        }</dd></div>
      </dl>
    </section>`;
}

function triggerInspector(trigger, issues) {
  const definition = definitionOf(trigger.use);
  return `
    ${inspectorHeader("Trigger", stepPresentation(trigger.id).name || stepName(definition), trigger.use)}
    ${issues}
    ${inputFields(trigger, definition.inputs, ["inputs"])}
    ${metricsSetting(trigger)}
    ${runtimeDetails()}
    <section class="inspector-section">
      <div class="section-heading"><strong>Expected results</strong><span>Trigger contract</span></div>
      <div class="contract-list">${definition.results.map(result => `
        <span><code>context.${html(result.name)}</code><small>${html(result.shape)}${
          Object.hasOwn(result, "default") ? " · default " + html(JSON.stringify(result.default)) : ""
        }</small></span>`
      ).join("")}</div>
    </section>
    <div id="run-result-panel">${runResultPanel()}</div>`;
}

function examplesInspector(trigger) {
  if (!trigger) {
    return appInspector(issueList("app"));
  }
  const example = selectedExample(trigger);
  return `
    ${inspectorHeader("Trigger", stepPresentation(trigger.id).name || stepName(definitionFor(trigger)), trigger.id)}
    ${issueList(trigger.id)}
    <section class="inspector-section">
      <div class="section-heading"><strong>Examples</strong><span>${count(trigger.examples.length, "case")}</span></div>
      <div class="example-tabs">
        <button type="button" data-select-example="-1" class="${example ? "" : "active"}">None</button>
        ${trigger.examples.map((candidate, index) => `
          <button type="button" class="${candidate === example ? "active" : ""}"
                  data-select-example="${index}">${html(candidate.name)}</button>`).join("")}
        <button type="button" id="add-example">Add</button>
      </div>
      ${example ? `<label for="example-name">Name</label>
      <input id="example-name" value="${html(example.name)}" autocomplete="off">
      <label for="example-payload">Payload</label>
      <textarea id="example-payload" rows="7" spellcheck="false">${html(
        exampleEditorValue(trigger, "payload", JSON.stringify(example.payload, null, 2))
      )}</textarea>
      <label for="example-context">Context <small>Optional</small></label>
      <textarea id="example-context" rows="9" spellcheck="false" placeholder="{}">${html(
        exampleEditorValue(
          trigger,
          "context",
          plainObject(example.context) && Object.keys(example.context).length
            ? JSON.stringify(example.context, null, 2) : ""
        )
      )}</textarea>
      ${trigger.examples.length > 1
        ? `<button class="button danger" type="button" id="delete-example">Delete example</button>`
        : ""}` : ""}
    </section>`;
}

function stepInspector(operation, issues) {
  const definition = definitionOf(operation.use);
  return `
    ${inspectorHeader("Step", stepPresentation(operation.id).name || stepName(definition), operation.id)}
    ${issues}
    <div id="preview-values">
      ${portMappings(operation, definition)}
      ${inputFields(operation, definition.inputs, ["inputs"])}
    </div>
    ${metricsSetting(operation)}
    ${runtimeDetails()}
    <div id="preview-error" role="status"></div>`;
}

function portMappings(operation, definition) {
  const receiveFields = definition.receives.map(port => pathInput(
    operation,
    portPathInput(operation, definition, "receives", port),
    ["receives", port.name],
    operation.receives?.[port.name]
  ));
  const returnFields = definition.returns.map(port => pathInput(
    operation,
    portPathInput(operation, definition, "returns", port),
    ["returns", port.name],
    operation.returns?.[port.name]
  ));
  return receiveFields.join("") + returnFields.join("");
}

function portPathInput(operation, definition, direction, port) {
  const single = definition[direction].length === 1;
  const prefix = direction === "receives" ? "source" : "target";
  const source = operation.receives?.[port.name]
    || (definition.receives.length === 1 ? operation.receives?.[definition.receives[0].name] : undefined);
  const fallback = source || ["context", "payload", port.name];
  return {
    name: single ? prefix : prefix + "_" + port.name,
    type: "path",
    access: direction === "receives" ? "read" : "write",
    required: true,
    ...(direction === "returns" ? { default: clone(fallback) } : {}),
    port
  };
}

function nextStepControls(operation) {
  const declared = displayOutcomes(operation);
  if (declared.length === 1) {
    return `<button class="button" type="button" id="add-next-step"
                    ${insertionAllowed(operation, declared[0]) ? "" : "disabled"}><i class="hud-symbol" data-symbol="plus" aria-hidden="true"></i>Add next Step</button>`;
  }
  return `<section class="next-routes" aria-label="Next Steps">
    ${declared.map(outcome => {
      const connection = outcomeConnection(operation, outcome);
      const insertable = insertionAllowed(operation, outcome);
      return `<div>
        <span><strong>${html(outcomeLabel(operation, outcome))}</strong><small>${html(connection.label)}</small></span>
        <button class="button" type="button" data-add-outcome="${html(outcome)}"
                ${insertable ? "" : "disabled"}>Add Step</button>
      </div>`;
    }).join("")}
  </section>`;
}

function outcomeConnection(operation, outcome) {
  const destinations = outcomeDestinations(operation, outcome);
  const destination = destinations.length === 1 ? destinations[0] : undefined;
  const target = node(destination);
  const issue = destinations.length > 1 ? "Multiple links"
    : target && state.project.links.filter(link => link.to === target.id).length !== 1 ? "Repeated Step"
    : !target && destination !== "end" ? destination ? "Unknown Step" : "Missing link" : "";
  return { invalid: Boolean(issue), label: issue || (target
    ? stepPresentation(target.id).name || stepName(definitionFor(target)) : "End") };
}

function removableStep(operation) {
  return outcomes(operation).slice(1).every(outcome => outcomeTarget(operation, outcome) === "end");
}

function inputFields(operation, inputs, base) {
  if (!inputs.length) {
    return "";
  }
  return inputs.map(input => {
    const editor = inputEditor(operation, input, [...base, input.name], inputs, base);
    return base.length === 1 && input.type !== "path" && input.type !== "steps"
      ? `<div class="observed-input">${editor}<div class="input-result"
          data-input-result="${html(input.name)}" aria-live="polite"></div></div>` : editor;
  }).join("");
}

function inputEditor(operation, input, locator, scopeInputs, scopeBase) {
  const value = valueAt(operation, locator);
  if (input.type === "path") {
    return pathInput(operation, input, locator, value);
  }
  if (input.type === "options") {
    return optionsInput(operation, input, locator, value);
  }
  if (input.type === "candidates") {
    return candidatesInput(operation, input, locator, value || [], scopeInputs, scopeBase);
  }
  if (input.type === "matcher_groups") {
    return matcherGroupsInput(operation, input, locator, value || [], scopeInputs, scopeBase);
  }
  if (input.type === "steps") {
    return stepsInput(operation, input, locator, value || [], scopeInputs, scopeBase);
  }
  return jsonInput(operation, input, locator, value);
}

function pathInput(operation, input, locator, value) {
  const id = inputId(locator);
  const open = state.pathPicker && samePath(state.pathPicker.locator, locator);
  const present = hasAt(operation, locator);
  const fallback = defaultInput(input);
  const hasFallback = fallback !== undefined;
  const resettable = hasFallback && input.resettable !== false;
  const selected = present ? value : fallback;
  if (!input.required && !present && !hasFallback) {
    return `
      <section class="inspector-section compact" data-input-name="${html(input.name)}">
        <label class="check-line">
          <input id="${html(id)}-present" type="checkbox" data-toggle-input="${locatorToken(locator)}"
                 data-input-meta="${metaToken(input)}">
          <span>${input.inherited ? "Override " : "Use "}${html(inputLabel(input.name).toLowerCase())}</span>
        </label>
      </section>`;
  }
  const optional = !input.required && !hasFallback ? `
    <label class="check-line">
      <input id="${html(id)}-present" type="checkbox" checked
             data-toggle-input="${locatorToken(locator)}" data-input-meta="${metaToken(input)}">
      <span>Override ${html(inputLabel(input.name).toLowerCase())}</span>
    </label>` : "";
  const reset = resettable ? `
    <button class="path-reset" type="button" data-reset-path="${locatorToken(locator)}"
            data-input-meta="${metaToken(input)}" ${
              !present || samePath(selected, fallback) ? "disabled" : ""
            }>Reset</button>` : "";
  return `
    <section class="inspector-section" data-input-name="${html(input.name)}">
      <div class="section-heading"><strong>${html(inputLabel(input.name))}</strong>
        <div class="path-heading-actions"><span>${html(input.access.replace("_", " "))} path</span>${reset}</div>
      </div>${optional}
      <button class="path-button" type="button" id="${html(id)}-path"
              data-open-path="${locatorToken(locator)}" data-input-meta="${metaToken(input)}">
        ${selected?.length ? pathCrumbs(selected) : "Choose path"}
      </button>
      <div class="path-values" data-path-observation="${locatorToken(locator)}"
           data-input-meta="${metaToken(input)}" aria-live="polite">${pathValues(operation, input, selected, locator)}</div>
      ${open ? pathBrowser(input, operation, locator) : ""}
    </section>`;
}

function optionsInput(operation, input, locator, value) {
  const choice = value || {};
  const selected = input.options.find(option => option.name === choice.option);
  return `
    <section class="inspector-section" data-input-name="${html(input.name)}">
      <label for="${html(inputId(locator))}-option">${html(inputLabel(input.name))}</label>
      <select id="${html(inputId(locator))}-option" data-input-option="${locatorToken(locator)}"
              data-input-meta="${metaToken(input)}">
        ${selected ? "" : "<option value=\"\" selected disabled>Choose an option</option>"}
        ${input.options.map(option => `
          <option value="${html(option.name)}" ${option.name === selected?.name ? "selected" : ""}>
            ${html(inputLabel(option.name))}
          </option>`).join("")}
      </select>
      ${selected ? inputFields(operation, selected.inputs, [...locator, "inputs"]) : ""}
    </section>`;
}

function candidatesInput(operation, input, locator, candidates, scopeInputs, scopeBase) {
  const id = inputId(locator);
  const token = locatorToken(locator);
  const query = queryAt(state.candidateQueries, locator);
  return `
    <section class="inspector-section candidate-input" data-input-name="${html(input.name)}">
      <div class="section-heading"><strong>${html(inputLabel(input.name))}</strong><span>${
        input.authored_outcomes ? "First matching case" : "First accepted value"
      }</span></div>
      <div class="candidate-list">
        ${candidates.map((candidate, index) => candidateEditor(
          operation,
          input,
          locator,
          candidate,
          index,
          candidates.length,
          scopeInputs,
          scopeBase
        )).join("") || '<p class="empty-options">No candidate configured.</p>'}
      </div>
      <input type="search" id="${html(id)}-candidate-search" value="${html(query)}"
             data-candidate-query="${token}" data-input-meta="${metaToken(input)}"
             placeholder="Search value sources" autocomplete="off">
      <div data-candidate-options="${token}" data-input-meta="${metaToken(input)}">
        ${candidateOptions(input, locator)}
      </div>
    </section>`;
}

function matcherGroupsInput(operation, input, locator, groups, scopeInputs, scopeBase) {
  const queryLocator = [...locator, "new-group"];
  const token = locatorToken(locator);
  return `
    <section class="inspector-section matcher-groups" data-input-name="${html(input.name)}">
      <div class="section-heading"><strong>${html(inputLabel(input.name))}</strong><span>Any group may match</span></div>
      <div class="matcher-group-list">
        ${groups.map((group, index) => matcherGroupEditor(
          operation,
          input,
          locator,
          group,
          index,
          groups.length,
          scopeInputs,
          scopeBase
        )).join("") || '<p class="empty-options">No condition group configured.</p>'}
      </div>
      <div class="matcher-group-add">
        <div class="section-heading"><strong>Add OR group</strong><span>Choose its first matcher</span></div>
        <input type="search" value="${html(queryAt(state.candidateQueries, queryLocator))}"
               data-matcher-group-query="${token}" data-input-meta="${metaToken(input)}"
               placeholder="Search value sources" autocomplete="off">
        <div data-matcher-group-options="${token}" data-input-meta="${metaToken(input)}">
          ${matcherGroupOptions(input, locator)}
        </div>
      </div>
    </section>`;
}

function matcherGroupEditor(operation, input, locator, group, index, size, scopeInputs, scopeBase) {
  const groupLocator = [...locator, index];
  const token = locatorToken(groupLocator);
  return `
    <article class="matcher-group" data-matcher-group="${index}">
      <div class="candidate-heading matcher-group-heading">
        <strong>${index ? 'OR: all of' : 'All of'}</strong>
        <div>
          <button type="button" data-move-matcher-group="${index}" data-direction="-1"
                  data-matcher-groups-locator="${locatorToken(locator)}" ${index === 0 ? "disabled" : ""}>Up</button>
          <button type="button" data-move-matcher-group="${index}" data-direction="1"
                  data-matcher-groups-locator="${locatorToken(locator)}" ${index === size - 1 ? "disabled" : ""}>Down</button>
          <button type="button" data-remove-matcher-group="${index}"
                  data-matcher-groups-locator="${locatorToken(locator)}">Remove</button>
        </div>
      </div>
      <div class="candidate-list">
        ${group.map((matcher, matcherIndex) => candidateEditor(
          operation,
          input,
          groupLocator,
          matcher,
          matcherIndex,
          group.length,
          scopeInputs,
          scopeBase,
          {
            noun: "Matcher",
            condition: "Match when",
            minimum: 1,
            predicateName: input.name + "[" + index + "][" + matcherIndex + "].when",
            selectable: false
          }
        )).join("") || '<p class="empty-options">Add a matcher to repair this group.</p>'}
      </div>
      <input type="search" value="${html(queryAt(state.candidateQueries, groupLocator))}"
             data-candidate-query="${token}" data-input-meta="${metaToken(input)}"
             placeholder="Add AND matcher" autocomplete="off">
      <div data-candidate-options="${token}" data-input-meta="${metaToken(input)}">
        ${candidateOptions(input, groupLocator)}
      </div>
    </article>`;
}

function candidateEditor(
  operation,
  input,
  locator,
  candidate,
  index,
  size,
  scopeInputs,
  scopeBase,
  view = {}
) {
  const option = input.options.find(available => available.name === candidate.option);
  const candidateLocator = [...locator, index];
  const path = view.selectable === false ? "" : inputDiagnosticPath(operation.id, locator);
  const selected = Boolean(path) && state.preview?.selected_candidates?.[path] === index;
  const authored = input.authored_outcomes === true;
  const noun = authored ? "Case" : view.noun || "Candidate";
  const removable = size > (view.minimum || 0)
    && (!authored || candidate.outcome && outcomeTarget(operation, candidate.outcome) === "end");
  const predicateName = view.predicateName || input.name + "[" + index + "].when";
  const condition = conditionOf(candidate.when);
  const predicateStatus = !condition.transforms.length && !condition.all.length
    ? "value must exist"
    : condition.all.length
      ? `${condition.all.length} ${condition.all.length === 1 ? "matcher" : "matchers"} must pass`
      : "add matcher";
  return `
    <article class="candidate${view.selectable === false ? ' matcher-row' : ''}${selected ? " selected-candidate" : ""}" data-candidate-index="${index}"
             ${path ? `data-candidate-path="${html(path)}"` : ""}>
      <div class="candidate-heading">
        <strong>${view.selectable === false ? index ? 'AND' : 'Where' : `${html(noun)} ${index + 1}`}</strong>
        <div>
          <button type="button" data-move-candidate="${index}" data-direction="-1"
                  data-candidate-locator="${locatorToken(locator)}" data-input-meta="${metaToken(input)}"
                  ${index === 0 ? "disabled" : ""}>Up</button>
          <button type="button" data-move-candidate="${index}" data-direction="1"
                  data-candidate-locator="${locatorToken(locator)}" data-input-meta="${metaToken(input)}"
                  ${index === size - 1 ? "disabled" : ""}>Down</button>
          <button type="button" data-remove-candidate="${index}"
                  data-candidate-locator="${locatorToken(locator)}" data-input-meta="${metaToken(input)}"
                  ${removable ? "" : "disabled"}>Remove</button>
        </div>
      </div>
      ${authored ? `
        <label for="${html(inputId(candidateLocator))}-label">Label</label>
        <input id="${html(inputId(candidateLocator))}-label" type="text"
               value="${html(stepPresentation(operation.id).outcomes?.[candidate.outcome] || "")}" data-candidate-label="${locatorToken(locator)}"
               data-candidate-index="${index}" autocomplete="off">` : ""}
      <div class="candidate-source">
      <label class="candidate-source-label" for="${html(inputId(candidateLocator))}-option">Source</label>
      <select id="${html(inputId(candidateLocator))}-option"
              data-candidate-option="${locatorToken(locator)}" data-candidate-index="${index}"
              data-input-meta="${metaToken(input)}">
        ${option ? "" : '<option value="" selected disabled>Choose a source</option>'}
        ${input.options.map(available => `
          <option value="${html(available.name)}" ${available.name === option?.name ? "selected" : ""}>
            ${html(inputLabel(available.name))}
          </option>`).join("")}
      </select>
      ${option ? inputFields(operation, option.inputs, [...candidateLocator, "inputs"]) : ""}
      </div>
      <div class="candidate-condition">
        <div class="section-heading"><strong>${html(view.condition || "Accept when")}</strong><span data-candidate-status
             data-candidate-default-status="${html(predicateStatus)}">${selected ? "Selected, " : ""}${
          html(predicateStatus)
        }</span></div>
        ${conditionEditor(
          operation,
          input,
          option,
          candidateLocator,
          condition,
          predicateName,
          scopeInputs,
          scopeBase
        )}
      </div>
    </article>`;
}

function conditionOf(value) {
  if (value && !Array.isArray(value)) {
    return {
      transforms: Array.isArray(value.transforms) ? value.transforms : [],
      all: Array.isArray(value.all) ? value.all.filter(Array.isArray) : []
    };
  }
  return { transforms: [], all: [] };
}

function conditionEditor(operation, input, option, candidateLocator, condition, name, scopeInputs, scopeBase) {
  const source = { option, locator: candidateLocator };
  const transforms = {
    name: name + ".transforms",
    type: "steps",
    candidate_source: source,
    program_role: "transform"
  };
  const transformLocator = [...candidateLocator, "when", "transforms"];
  const preparedShape = programValueShape(
    operation,
    transforms,
    transformLocator,
    scopeInputs,
    scopeBase
  );
  const queryLocator = [...candidateLocator, "when", "new-predicate"];
  const token = locatorToken(candidateLocator);
  return `
    <details class="condition-transforms">
      <summary title="Calculate the comparison value without changing the context">${condition.transforms.length
        ? html(condition.transforms.map(step => stepName(definitionOf(step.use))).join(" / "))
        : 'Calculate value'}</summary>
      ${programEditor(operation, transforms, transformLocator, condition.transforms, scopeInputs, scopeBase)}
    </details>
    <section class="condition-predicates">
      <div class="condition-predicate-list">
        ${condition.all.map((steps, index) => predicateEditor(
          operation,
          source,
          candidateLocator,
          steps,
          index,
          condition.all.length,
          name,
          preparedShape,
          scopeInputs,
          scopeBase
        )).join("") || '<p class="empty-options">No matcher configured.</p>'}
      </div>
      <details class="condition-add" ${condition.all.length ? '' : 'open'}><summary>Add comparison (AND)</summary>
      <input type="search" id="${html(inputId(queryLocator))}-search"
             value="${html(queryAt(state.candidateQueries, queryLocator))}"
             data-predicate-query="${token}" data-input-meta="${metaToken(input)}"
             data-input-scope="${metaToken({ inputs: scopeInputs, base: scopeBase })}"
             placeholder="Search matchers" autocomplete="off">
      <div data-predicate-options="${token}" data-input-meta="${metaToken(input)}"
           data-input-scope="${metaToken({ inputs: scopeInputs, base: scopeBase })}">
        ${predicateOptions(candidateLocator, preparedShape)}
      </div>
      </details>
    </section>`;
}

function predicateEditor(
  operation,
  source,
  candidateLocator,
  steps,
  index,
  size,
  name,
  preparedShape,
  scopeInputs,
  scopeBase
) {
  const locator = [...candidateLocator, "when", "all", index];
  const predicate = {
    name: name + ".all[" + index + "]",
    type: "steps",
    candidate_source: source,
    program_role: "predicate",
    program_shape: preparedShape
  };
  return `
    <article class="condition-predicate" data-condition-predicate="${index}">
      <div class="candidate-heading">
        <strong>${index ? 'AND' : ''}</strong>
        <div>
          <button type="button" data-move-predicate="${index}" data-direction="-1"
                  data-condition-locator="${locatorToken(candidateLocator)}"
                  ${index === 0 ? "disabled" : ""}>Up</button>
          <button type="button" data-move-predicate="${index}" data-direction="1"
                  data-condition-locator="${locatorToken(candidateLocator)}"
                  ${index === size - 1 ? "disabled" : ""}>Down</button>
          <button type="button" data-remove-predicate="${index}"
                  data-condition-locator="${locatorToken(candidateLocator)}">Remove</button>
        </div>
      </div>
      ${programEditor(operation, predicate, locator, steps, scopeInputs, scopeBase)}
    </article>`;
}

function predicateOptions(candidateLocator, shape) {
  const queryLocator = [...candidateLocator, "when", "new-predicate"];
  const query = queryAt(state.candidateQueries, queryLocator).trim().toLowerCase();
  return matcherDefinitions(shape)
    .filter(definition => definitionMatchesQuery(definition, query))
    .map(definition => `
      <button type="button" class="catalog-option compact-option program-option"
              data-add-predicate="${html(definition.id)}"
              data-condition-locator="${locatorToken(candidateLocator)}">
        <strong>${html(stepName(definition))}</strong>
      </button>`).join("") || '<p class="empty-options">No compatible matcher found.</p>';
}

function matcherDefinitions(shape) {
  return state.catalog
    .filter(definition => definition.kind === "step")
    .filter(definition => !authoredOutcomeInput(definition))
    .filter(definition => definition.receives.length === 1 && definition.returns.length === 1)
    .filter(definition => definition.outcomes.length === 1 && definition.returns[0].shape === "boolean")
    .filter(definition => portAcceptsValue(definition.receives[0], shape, []));
}

function predicateOptionsFor(operation, input, candidateLocator, scopeInputs, scopeBase) {
  const candidate = valueAt(operation, candidateLocator);
  const option = input.options.find(available => available.name === candidate?.option);
  if (!candidate || !option) {
    return '<p class="empty-options">No compatible matcher found.</p>';
  }
  const source = { option, locator: candidateLocator };
  const transforms = {
    name: input.name + ".when.transforms",
    type: "steps",
    candidate_source: source,
    program_role: "transform"
  };
  const shape = programValueShape(
    operation,
    transforms,
    [...candidateLocator, "when", "transforms"],
    scopeInputs,
    scopeBase
  );
  return predicateOptions(candidateLocator, shape);
}

function candidateOptions(input, locator) {
  const query = queryAt(state.candidateQueries, locator).trim().toLowerCase();
  return input.options
    .filter(option => !query || option.name.toLowerCase().includes(query)
      || inputLabel(option.name).toLowerCase().includes(query))
    .map(option => `
      <button type="button" class="catalog-option compact-option"
              data-add-candidate="${html(option.name)}" data-candidate-locator="${locatorToken(locator)}"
              data-input-meta="${metaToken(input)}">
        <strong>${html(inputLabel(option.name))}</strong>
        <small>${html(option.name)}</small>
      </button>`).join("") || '<p class="empty-options">No value source matches.</p>';
}

function matcherGroupOptions(input, locator) {
  const queryLocator = [...locator, "new-group"];
  const query = queryAt(state.candidateQueries, queryLocator).trim().toLowerCase();
  return input.options
    .filter(option => !query || option.name.toLowerCase().includes(query)
      || inputLabel(option.name).toLowerCase().includes(query))
    .map(option => `
      <button type="button" class="catalog-option compact-option"
              data-add-matcher-group="${html(option.name)}"
              data-matcher-groups-locator="${locatorToken(locator)}"
              data-input-meta="${metaToken(input)}">
        <strong>${html(inputLabel(option.name))}</strong>
        <small>${html(option.name)}</small>
      </button>`).join("") || '<p class="empty-options">No value source matches.</p>';
}

function jsonInput(operation, input, locator, value) {
  const present = hasAt(operation, locator);
  const id = inputId(locator);
  if (!input.required && !present) {
    return `
      <section class="inspector-section compact" data-input-name="${html(input.name)}">
        <label class="check-line">
          <input id="${html(id)}-present" type="checkbox" data-toggle-input="${locatorToken(locator)}"
                 data-input-meta="${metaToken(input)}">
          <span>${input.inherited ? "Override " : "Use "}${html(inputLabel(input.name).toLowerCase())}</span>
        </label>
      </section>`;
  }
  const optional = !input.required ? `
    <label class="check-line">
      <input id="${html(id)}-present" type="checkbox" checked
             data-toggle-input="${locatorToken(locator)}" data-input-meta="${metaToken(input)}">
      <span>Use ${html(inputLabel(input.name).toLowerCase())}</span>
    </label>` : "";
  const attributes = `id="${html(id)}-value" data-input-json="${locatorToken(locator)}"
                      data-input-meta="${metaToken(input)}"`;
  let control;
  if (input.shape === "string") {
    control = `<input type="text" ${attributes} value="${html(value ?? "")}">`;
  } else if (input.shape === "number") {
    const minimum = Object.hasOwn(input, "minimum")
      ? ` min="${html(numberText(input.minimum))}"` : "";
    const maximum = Object.hasOwn(input, "maximum")
      ? ` max="${html(numberText(input.maximum))}"` : "";
    control = `<input type="number" step="any" ${attributes}${minimum}${maximum}
                      value="${value === undefined ? "" : html(numberText(value))}">`;
  } else if (input.shape === "boolean") {
    control = `<select ${attributes}>
      ${value === undefined ? "<option value=\"\" selected disabled>Required</option>" : ""}
      <option value="true" ${value === true ? "selected" : ""}>true</option>
      <option value="false" ${value === false ? "selected" : ""}>false</option>
    </select>`;
  } else {
    control = `<textarea rows="${input.shape === "any" ? 4 : 3}" spellcheck="false" ${attributes}>${html(
      value === undefined ? "" : jsonEditorValue(locator, value)
    )}</textarea>`;
  }
  return `
    <section class="inspector-section${input.required ? "" : " compact"}"
             data-input-name="${html(input.name)}">
      ${optional}
      <label for="${html(id)}-value">${html(inputLabel(input.name))}</label>
      ${control}
      ${input.required && input.shape === "string" && !present ? `
        <button type="button" id="${html(id)}-empty"
                data-set-empty-json="${locatorToken(locator)}"
                data-input-meta="${metaToken(input)}">Use empty string</button>` : ""}
    </section>`;
}

function stepsInput(operation, input, locator, steps, scopeInputs, scopeBase) {
  return `
    <section class="inspector-section" data-input-name="${html(input.name)}">
      <div class="section-heading"><strong>${html(inputLabel(input.name))}</strong><span>Run in order</span></div>
      ${programEditor(operation, input, locator, steps, scopeInputs, scopeBase)}
    </section>`;
}

function programEditor(operation, input, locator, steps, scopeInputs, scopeBase) {
  const id = inputId(locator);
  const scope = metaToken({ inputs: scopeInputs, base: scopeBase });
  const predicate = input.program_role === "predicate";
  const shape = programValueShape(operation, input, locator, scopeInputs, scopeBase);
  const status = !predicate
    ? "Compatible with current value"
    : !steps.length
      ? "No matcher: value must exist"
      : shape === "boolean"
        ? "Ready: returns boolean"
        : "Add matcher: current result is " + (shape || "unknown");
  const action = input.program_role === "transform"
    ? "Add transform"
    : predicate ? "Continue matcher" : "Add value Step";
  return `
    <div class="program-list">
      ${steps.map((step, index) => nestedStep(operation, input, locator, step, index, steps.length)).join("")}
    </div>
    ${predicate && steps.length && shape === 'boolean' ? '<details class="condition-chain"><summary>Extend comparison</summary>' : ''}
    <label class="program-search-label" for="${html(id)}-search">
      <strong>${action}</strong>
      <span>${html(status)}</span>
    </label>
    <input type="search" id="${html(id)}-search" value="${html(queryAt(state.stepQueries, locator))}"
           data-step-query="${locatorToken(locator)}" data-input-meta="${metaToken(input)}"
           data-input-scope="${scope}"
           placeholder="Search Steps" autocomplete="off">
    <div id="${html(id)}-options" data-step-options="${locatorToken(locator)}"
         data-input-meta="${metaToken(input)}" data-input-scope="${scope}">
      ${nestedOptions(operation, input, locator, scopeInputs, scopeBase)}
    </div>${predicate && steps.length && shape === 'boolean' ? '</details>' : ''}`;
}

function nestedStep(operation, input, locator, step, index, size) {
  const definition = definitionOf(step.use);
  const previewInput = programPath(locator);
  const comparison = input.program_role === 'predicate' && size === 1;
  const choices = comparison ? matcherDefinitions(input.program_shape) : [];
  return `
    <div class="nested-step">
      <div class="nested-step-summary">
        ${comparison ? `<select aria-label="Comparison" data-replace-comparison="${locatorToken(locator)}">
          ${choices.some(item => item.id === step.use) ? '' : `<option value="${html(step.use)}">${html(stepName(definition))}</option>`}
          ${choices.map(item => `<option value="${html(item.id)}" ${item.id === step.use ? 'selected' : ''}>${html(stepName(item))}</option>`).join('')}
        </select>` : `<span>${html(stepName(definition))}</span>`}
        <span data-preview-input="${html(previewInput)}" data-preview-slot="${index}">${
          previewStage(operation, previewInput, index)
        }</span>
        ${inputFields(operation, definition.inputs, [...locator, index, "inputs"])}
      </div>
      ${comparison ? '' : `<div>
        <button type="button" data-move-nested="${index}" data-direction="-1"
                data-program-locator="${locatorToken(locator)}" ${index === 0 ? "disabled" : ""}>Up</button>
        <button type="button" data-move-nested="${index}" data-direction="1"
                data-program-locator="${locatorToken(locator)}" ${index === size - 1 ? "disabled" : ""}>Down</button>
        <button type="button" data-remove-nested="${index}"
                data-program-locator="${locatorToken(locator)}">Remove</button>
      </div>`}
    </div>`;
}

function nestedOptions(operation, input, locator, scopeInputs, scopeBase) {
  const values = programValues(operation, input, locator, scopeInputs, scopeBase);
  const predicate = input.program_role === "predicate";
  const configuredShape = programValueShape(operation, input, locator, scopeInputs, scopeBase);
  const shapes = new Set(values.map(valueShape));
  const observedShape = shapes.size === 1 ? [...shapes][0] : shapes.size ? "mixed" : "";
  const shape = configuredShape === "mixed" && observedShape
    ? observedShape
    : configuredShape || observedShape;
  const stats = values.map(valueStats);
  const query = queryAt(state.stepQueries, locator).trim().toLowerCase();
  const options = state.catalog
    .filter(definition => definition.kind === "step")
    .filter(definition => !authoredOutcomeInput(definition))
    .filter(definition => definition.receives.length === 1 && definition.returns.length === 1)
    .filter(definition => !predicate || definition.outcomes.length === 1)
    .filter(definition => input.program_role !== "transform" || definition.returns[0]?.shape !== "boolean")
    .filter(definition => portAcceptsValue(definition.receives[0], shape, stats))
    .filter(definition => definitionMatchesQuery(definition, query));
  if (predicate) {
    options.sort((left, right) => Number(right.returns[0]?.shape === "boolean")
      - Number(left.returns[0]?.shape === "boolean"));
  }
  return options
    .map(definition => {
      const role = definition.returns[0]?.shape === "boolean" ? "Matcher" : "Transform";
      return `
      <button type="button" class="catalog-option compact-option program-option"
              data-add-nested="${html(definition.id)}" data-program-locator="${locatorToken(locator)}"
              data-program-role="${input.program_role || "step"}">
        <strong>${html(stepName(definition))}</strong>
        ${predicate ? `<span class="program-role">${role}</span>` : ""}
        <span class="program-shape">${html(definition.receives[0]?.shape || "any")} to ${html(
          definition.returns[0]?.shape || "any"
        )}</span>
        <small>${html(definition.id)}${definition.search_terms?.length
          ? ` · ${html(definition.search_terms.join(", "))}` : ""}</small>
      </button>`;
    }).join("") || `<p class="empty-options">No compatible Step matches.</p>`;
}

function queryAt(queries, locator) {
  return queries[locatorToken(locator)] || "";
}

function clearInputQueries() {
  state.stepQueries = {};
  state.candidateQueries = {};
}

function pathBrowser(input, operation, locator) {
  const writable = input.access !== "read";
  const available = availablePaths(operation).filter(entry => !writable
    || entry.path[0] !== "context"
    || entry.path[1] !== "runtime");
  const selectable = available.filter(entry =>
    writable || !input.port
      || entry.examples === entry.total && portAcceptsValue(input.port, entry.shape, []));
  const entries = writable ? selectable : available.filter(entry => selectable.some(candidate =>
    entry.path.length <= candidate.path.length
    && entry.path.every((part, index) => part === candidate.path[index])
  ));
  if (writable) {
    definitionOf(triggerFor(operation.id).use).results
      .filter(result => !entries.some(entry => samePath(entry.path, ["context", result.name])))
      .forEach(result => entries.push({
        path: ["context", result.name],
        shape: result.shape,
        examples: triggerFor(operation.id).examples.length,
        total: triggerFor(operation.id).examples.length
      }));
  }
  const draft = state.pathDraft;
  const selected = entries.find(entry => samePath(entry.path, draft));
  const usable = writable
    ? draft.length >= 2
    : selectable.some(entry => samePath(entry.path, draft));
  const children = entries.filter(entry =>
    entry.path.length === draft.length + 1
    && draft.every((part, index) => part === entry.path[index])
  );
  const canAddField = writable && (!selected || selected.shape === "object");
  const canAddIndex = writable && (!selected || selected.shape === "array");
  const fieldValid = validPathField(state.pathField, draft);
  const indexValid = validPathIndex(state.pathIndex);
  const choicesLabel = selected?.shape === "array"
    ? "Choose one array index"
    : selected?.shape === "object" ? "Choose one field" : "Choose the next path segment";
  return `
    <div class="path-browser" role="listbox">
      <div class="path-builder">
        <div class="path-crumbs" aria-label="Selected path">
          ${draft.map((part, index) => `
            ${index ? "<i>&rsaquo;</i>" : ""}
            <button type="button" class="path-crumb" data-path-depth="${index}"
                    data-path-draft-json="${encodeURIComponent(JSON.stringify(draft.slice(0, index + 1)))}">
              ${html(pathPart(part))}
            </button>`).join("")}
        </div>
        <div class="path-choices">
          ${children.length ? `<small class="path-choices-label">${choicesLabel}</small>` : ""}
          ${children.map(entry => {
            const part = entry.path.at(-1);
            return `
              <button type="button" class="path-choice" data-path-part="${html(pathPart(part))}"
                      data-path-draft-json="${encodeURIComponent(JSON.stringify(entry.path))}">
                <span><strong>${html(pathPart(part))}</strong><small>${entry.shape}${
                  entry.examples < entry.total ? ` · ${entry.examples}/${entry.total} examples` : ""
                }</small></span>${pathChoiceValue(operation, entry.path)}
              </button>`;
          }).join("") || `<small class="path-hint">${
            selected ? "Selected " + selected.shape + " value." : "New path."
          }</small>`}
        </div>
        ${canAddField || canAddIndex ? `
          <div class="path-create">
            ${canAddField ? `
              <input id="new-path-field" value="${html(state.pathField)}"
                     placeholder="New field name" autocomplete="off">
              <button id="append-path-field" type="button" ${fieldValid ? "" : "disabled"}>Add field</button>` : ""}
            ${canAddIndex ? `
              <input id="new-path-index" type="number" min="0" step="1"
                     value="${html(state.pathIndex)}"
                     aria-label="New array index">
              <button id="append-path-index" type="button" ${indexValid ? "" : "disabled"}>Use index</button>` : ""}
          </div>` : ""}
        <div class="path-actions">
          <small>${html(inputLabel(input.name))}</small>
          <div class="path-action-buttons">
            <button class="path-cancel" id="cancel-path" type="button">Cancel</button>
            <button id="apply-path" type="button" ${usable ? "" : "disabled"}>Use path</button>
          </div>
        </div>
      </div>
    </div>`;
}

function picker() {
  if (!state.picker) {
    return "";
  }
  return `
    <div class="construction-palette">
      <section class="step-picker" role="dialog" aria-label="Add Step">
        <header><div><span class="eyebrow">${html(stepPresentation(state.picker.anchor).name || stepName(definitionFor(node(state.picker.anchor))))}</span><h2>Add ${state.picker.mode === "trigger"
          ? "Trigger" : "Step"}</h2></div><button type="button" data-close-picker aria-label="Close Step chooser">Close</button></header>
        <input type="search" id="step-search" value="${html(state.picker.query)}"
               placeholder="Search by name or id" autocomplete="off" autofocus>
        <div id="step-options">${pickerOptions()}</div>
      </section>
    </div>`;
}

function groupPicker() {
  if (!state.groupPicker) {
    return "";
  }
  return `
    <div class="picker-backdrop group-picker-backdrop">
      <section class="step-picker group-picker" role="dialog" aria-modal="true" aria-label="Choose group">
        <header class="picker-heading">
          <div><span class="eyebrow">Creator groups</span><h2>Choose Group</h2></div>
          <button type="button" id="close-group-picker">Cancel</button>
        </header>
        <input type="search" id="group-picker-search" value="${html(state.groupPicker.query)}"
               placeholder="Search groups" autocomplete="off" autofocus>
        <div id="group-picker-options">${groupPickerOptions()}</div>
      </section>
    </div>`;
}

function groupPickerOptions() {
  const query = state.groupPicker?.query.toLowerCase() || "";
  const groups = state.creator.groups.filter(group => groupName(group).toLowerCase().includes(query));
  const inventory = groupInventory();
  return `
    <button type="button" class="catalog-option" data-assign-group="">
      <strong>No group</strong><small>Remove the visual assignment</small>
    </button>
    ${groups.map(group => `
      <button type="button" class="catalog-option group-option" data-assign-group="${html(group.id)}"
              style="--group-color:${html(group.color || "#147982")}">
        <strong>${html(groupName(group))}</strong>
        <small>${count(inventory.get(group.id) || 0, "Step")}</small>
      </button>`).join("") || '<p class="empty-options">No group matches.</p>'}${groupPages()}`;
}

function pickerOptions() {
  if (!state.picker) {
    return "";
  }
  const query = state.picker.query.toLowerCase();
  const candidates = state.picker.mode === "trigger"
    ? availableTriggers()
    : addableDefinitions();
  const matching = candidates
    .filter(definition => definitionMatchesQuery(definition, query));
  const options = matching.filter(definition => state.picker.mode === "trigger"
      || automaticBindingsAvailable(state.picker.anchor, definition));
  return options
    .map(definition => `
      <button type="button" class="catalog-option" data-add-step="${html(definition.id)}">
        <strong>${html(stepName(definition))}</strong>
        <small>${html(definition.id)}${definition.receives.length || definition.returns.length
          ? ` · ${html(definition.receives.map(port => port.shape).join(" + ") || "context")} to ${html(
              definition.returns.map(port => port.shape).join(" + ") || "context"
            )}` : ""}</small>
      </button>`).join("") || '<p class="empty-options">No installed Step matches.</p>';
}

function openPicker(mode, anchor, outcome = "") {
  state.picker = { mode, anchor, outcome, query: "" };
  state.pathPicker = "";
  state.pathDraft = [];
  render();
  document.querySelector("#step-search")?.focus();
}

function previewCatalogStep(event) {
  const hovered = event.target.closest("[data-add-step]");
  const option = hovered || document.activeElement?.closest("[data-add-step]");
  if (!state.picker || state.picker.mode === "trigger") return;
  if (!option) {
    state.world?.preview(null);
    return;
  }
  if (hovered?.contains(event.relatedTarget)) return;
  const definition = definitionOf(option.dataset.addStep);
  if (definition) state.world?.preview({after: state.picker.anchor,
    outcome: state.picker.outcome || primaryOutcome(node(state.picker.anchor)), name: stepName(definition)});
}

document.addEventListener("focusin", previewCatalogStep);
document.addEventListener("pointerover", previewCatalogStep);

function addCatalogStep(id) {
  const definition = definitionOf(id);
  if (!definition || !state.picker) {
    return;
  }
  if (state.picker.mode === "trigger") {
    if (!definition.examples?.length) {
      return;
    }
    const triggerId = opaqueId("trigger");
    state.project.nodes.push({
      id: triggerId,
      use: definition.id,
      inputs: defaultInputs(definition.inputs),
      examples: clone(definition.examples)
    });
    state.project.links.push(
      { from: "app." + primaryOutcome(node("app")), to: triggerId },
      { from: triggerId + "." + primaryOutcome(definition), to: "end" }
    );
    state.creator.steps[triggerId] = { name: generatedName() };
    state.selection = { type: "trigger", id: triggerId };
    state.exampleTrigger = triggerId;
    state.exampleIndex = 0;
  } else if (!insertStep(state.picker.anchor, definition, state.picker.outcome)) {
    return;
  }
  state.picker = null;
  state.pathPicker = null;
  state.pathDraft = [];
  dirty(true);
}

function insertStep(afterId, definition, selectedOutcome = "") {
  const after = node(afterId);
  if (!after || definition.kind !== "step") {
    return false;
  }
  const outcome = selectedOutcome || primaryOutcome(after);
  if (!outcomes(after).includes(outcome)) {
    return false;
  }
  if (!insertionAllowed(after, outcome)) {
    return false;
  }
  const bindings = graphBindings(after, definition);
  if (bindings === null) {
    return false;
  }
  const inserted = opaqueId("step");
  insertFlatStep(afterId, definition, inserted, outcome, bindings);
  state.selection = { type: "step", id: inserted };
  return true;
}

function insertFlatStep(afterId, definition, id, outcome, bindings) {
  const after = node(afterId);
  const source = after.id + "." + outcome;
  const outgoing = state.project.links.find(link => link.from === source);
  const target = outgoing.to;
  outgoing.to = id;
  definition.outcomes.forEach(candidate => state.project.links.push({
    from: id + "." + candidate,
    to: candidate === primaryOutcome(definition) ? target : "end"
  }));
  state.project.nodes.push({
    id,
    use: definition.id,
    inputs: defaultInputs(definition.inputs),
    ...(Object.keys(bindings.receives).length ? { receives: bindings.receives } : {}),
    ...(Object.keys(bindings.returns).length ? { returns: bindings.returns } : {})
  });
}

function graphBindings(after, definition) {
  if (state.build !== "Built" && definition.receives.length) {
    return null;
  }
  const context = definitionFor(after)?.kind === "trigger" ? "trigger" : "output";
  const available = availablePaths(after, context)
    .filter(entry => entry.path[1] !== "runtime" && entry.examples === entry.total);
  const receives = {};
  for (const port of definition.receives) {
    const compatible = available.filter(entry => portAcceptsValue(port, entry.shape, []));
    const selected = compatible.find(entry => entry.path[1] === "payload") || compatible[0];
    if (!selected) {
      return null;
    }
    receives[port.name] = clone(selected.path);
  }
  const returns = {};
  definition.returns.forEach(port => {
    const source = receives[port.name]
      || (definition.receives.length === 1 ? receives[definition.receives[0].name] : undefined);
    returns[port.name] = clone(source || ["context", "payload", port.name]);
  });
  return { receives, returns };
}

function automaticBindingsAvailable(afterId, definition) {
  const after = node(afterId);
  return Boolean(after) && graphBindings(after, definition) !== null;
}

function deleteSelection() {
  if (state.selection.type === "trigger") {
    const trigger = node(state.selection.id);
    state.removedFlows.push(trigger.id);
    const ids = new Set([trigger.id, ...reachableSteps(trigger).map(candidate => candidate.id)]);
    if (state.jsonDraft && (ids.has(state.jsonDraft.node) || state.editor.nodes[state.jsonDraft.node]?.trigger === trigger.id)) state.jsonDraft = null;
    state.project.nodes = state.project.nodes.filter(candidate => !ids.has(candidate.id));
    state.project.links = state.project.links.filter(link =>
      !ids.has(link.from.split(".")[0]) && !ids.has(link.to)
    );
    removeCreatorReferences(ids);
    state.selection = { type: "app", id: "app" };
  } else if (state.selection.type === "step" && removableStep(node(state.selection.id))) {
    removeStep(state.selection.id);
  } else {
    return;
  }
  state.revealNode = state.selection.id;
  dirty(true);
}

function removeStep(id) {
  const selected = node(id);
  const removed = new Set([id]);
  const predecessor = state.project.links.find(link => link.to === selected.id);
  const predecessorId = predecessor ? linkNode(predecessor) : "";
  removeFlatStep(id);
  removeCreatorReferences(removed);
  if (state.jsonDraft?.node === id) state.jsonDraft = null;
  const previous = node(predecessorId);
  state.selection = definitionOf(previous?.use)?.kind === "trigger"
    ? { type: "trigger", id: previous.id }
    : { type: "step", id: previous?.id || "" };
}

function removeFlatStep(id) {
  const selected = node(id);
  if (!selected) {
    return;
  }
  const incoming = state.project.links.find(link => link.to === selected.id);
  const outgoing = state.project.links.find(link =>
    link.from === selected.id + "." + primaryOutcome(selected)
  );
  if (incoming && outgoing) {
    incoming.to = outgoing.to;
  }
  state.project.links = state.project.links.filter(link =>
    linkNode(link) !== selected.id && link.to !== selected.id
  );
  state.project.nodes = state.project.nodes.filter(candidate => candidate.id !== selected.id);
}

function updateExample(field, source) {
  const trigger = node(state.selection.id);
  try {
    const example = selectedExample(trigger);
    if (field === "context" && !source.trim()) {
      delete example.context;
    } else {
      const value = parseExact(source);
      if (field === "context" && (!plainObject(value) || Object.hasOwn(value, "runtime"))) {
        throw new Error("Context must be an object without context.runtime.");
      }
      example[field] = value;
    }
    state.exampleDraft = null;
    state.localDiagnostics = [];
    dirty();
  } catch (error) {
    state.exampleDraft = { trigger: trigger.id, example: state.exampleIndex, field, source };
    invalidateDraft();
    state.localDiagnostics = [{
      code: "PROJECT_TRIGGER_EXAMPLE_" + field.toUpperCase() + "_INVALID",
      message: error instanceof Error ? error.message : "Example value is invalid.",
      path: "nodes.examples." + field,
      node: trigger.id
    }];
    render();
  }
}

function exampleEditorValue(trigger, field, fallback) {
  const draft = state.exampleDraft;
  return draft?.trigger === trigger.id && draft.example === state.exampleIndex && draft.field === field
    ? draft.source
    : fallback;
}

function updateExampleName(value) {
  const trigger = node(state.selection.id);
  const example = selectedExample(trigger);
  if (value === example.name) {
    return;
  }
  if (!value.trim() || trigger.examples.some(candidate => candidate !== example && candidate.name === value)) {
    state.localDiagnostics = [{
      code: value.trim() ? "PROJECT_TRIGGER_EXAMPLE_NAME_DUPLICATE" : "PROJECT_TRIGGER_EXAMPLE_NAME_REQUIRED",
      message: value.trim() ? "Example name must be unique." : "Example name must not be blank.",
      path: "nodes.examples.name",
      node: trigger.id
    }];
    render();
    return;
  }
  example.name = value;
  dirty();
}

function selectExample(index, trigger = node(state.selection.id)) {
  state.exampleTrigger = trigger.id;
  state.exampleIndex = Math.max(-1, Math.min(index, trigger.examples.length - 1));
  state.exampleDraft = null;
  clearPreview(true, false);
  render();
  requestSelectedTrace();
}

function addExample() {
  const trigger = node(state.selection.id);
  const template = selectedExample(trigger) || trigger.examples[0];
  const used = new Set(trigger.examples.map(example => example.name));
  let index = trigger.examples.length + 1;
  while (used.has("example-" + index)) {
    index++;
  }
  trigger.examples.push({
    name: "example-" + index,
    payload: clone(template.payload),
    ...(plainObject(template.context) ? { context: clone(template.context) } : {})
  });
  state.exampleIndex = trigger.examples.length - 1;
  state.exampleDraft = null;
  dirty();
}

function deleteExample() {
  const trigger = node(state.selection.id);
  if (trigger.examples.length === 1) {
    return;
  }
  trigger.examples.splice(state.exampleIndex, 1);
  state.exampleIndex = Math.min(state.exampleIndex, trigger.examples.length - 1);
  state.exampleDraft = null;
  dirty();
}

function selectedExample(trigger) {
  if (state.exampleTrigger !== trigger.id) {
    state.exampleTrigger = trigger.id;
    state.exampleIndex = 0;
  }
  state.exampleIndex = Math.max(-1, Math.min(state.exampleIndex, trigger.examples.length - 1));
  return trigger.examples[state.exampleIndex] || null;
}

function openPathPicker(locatorSource, inputSource) {
  const operation = selectedOperation();
  const locator = parseToken(locatorSource);
  const input = parseToken(inputSource);
  state.pathPicker = { locator, input };
  state.pathDraft = clone(valueAt(operation, locator) || defaultInput(input) || []);
  state.pathField = "";
  state.pathIndex = "0";
  render();
}

function setPathDraft(encoded) {
  state.pathDraft = JSON.parse(decodeURIComponent(encoded));
  state.pathField = "";
  state.pathIndex = "0";
  render();
}

function appendPathField(source) {
  const field = source.trim();
  if (validPathField(field, state.pathDraft)) {
    state.pathDraft.push(field);
    state.pathField = "";
    render();
  }
}

function appendPathIndex(source) {
  if (validPathIndex(source)) {
    state.pathDraft.push(Number(source));
    state.pathIndex = "0";
    render();
  }
}

function validPathField(source, draft) {
  const field = source.trim();
  return Boolean(field) && !(draft.length === 1 && field === "runtime");
}

function validPathIndex(source) {
  return /^(0|[1-9]\d*)$/.test(source);
}

function applyPath() {
  const operation = selectedOperation();
  setAt(operation, state.pathPicker.locator, clone(state.pathDraft));
  closePathPicker();
  dirty();
}

function cancelPath() {
  closePathPicker();
  render();
}

function resetPath(locator, input) {
  const fallback = defaultInput(input);
  if (fallback === undefined) {
    return;
  }
  if (input.required) {
    setAt(selectedOperation(), locator, clone(fallback));
  } else {
    deleteAt(selectedOperation(), locator);
  }
  closePathPicker();
  dirty();
}

function closePathPicker() {
  state.pathPicker = null;
  state.pathDraft = [];
  state.pathField = "";
  state.pathIndex = "0";
}

function setJsonValue(locator, input, source) {
  try {
    const value = input.shape === "string" ? source
      : input.shape === "boolean" ? source === "true"
        : parseExact(source);
    setAt(selectedOperation(), locator, value);
    state.jsonDraft = null;
    state.localDiagnostics = [];
    dirty();
  } catch (error) {
    state.jsonDraft = { node: state.selection.id, locator, source };
    invalidateDraft();
    state.build = "Not built";
    state.localDiagnostics = [];
    render();
  }
}

function invalidateDraft() {
  clearTimeout(state.saveTimer);
  state.pendingProject = false;
  state.revision++;
  clearPreview(true, false);
}

function jsonEditorValue(locator, value) {
  const draft = state.jsonDraft;
  return draft?.node === state.selection.id && samePath(draft.locator, locator)
    ? draft.source
    : JSON.stringify(value, null, 2);
}

function toggleInput(locator, input, present) {
  state.jsonDraft = null;
  const operation = selectedOperation();
  if (present) {
    if (locator[0] === "inputs" && !plainObject(operation.inputs)) {
      operation.inputs = {};
    }
    const configured = defaultInput(input);
    setAt(operation, locator, configured === undefined ? initialValue(input) : clone(configured));
  } else {
    deleteAt(operation, locator);
  }
  dirty();
}

function selectOption(locator, input, optionName) {
  const option = input.options.find(candidate => candidate.name === optionName);
  if (!option) {
    return;
  }
  state.jsonDraft = null;
  setAt(selectedOperation(), locator, {
    option: option.name,
    inputs: defaultInputs(option.inputs)
  });
  dirty();
}

function addCandidate(locator, input, optionName) {
  const option = input.options.find(candidate => candidate.name === optionName);
  if (!option) {
    return;
  }
  const operation = selectedOperation();
  const label = input.authored_outcomes ? nextCaseLabel(operation) : "";
  let candidates = valueAt(operation, locator);
  if (!Array.isArray(candidates)) {
    candidates = [];
    setAt(operation, locator, candidates);
  }
  const candidate = authoredCandidate(option, input);
  candidates.push(candidate);
  if (input.authored_outcomes) {
    state.project.links.push({ from: operation.id + "." + candidate.outcome, to: "end" });
    setOutcomeLabel(operation.id, candidate.outcome, label);
  }
  delete state.candidateQueries[locatorToken(locator)];
  dirty();
}

function addMatcherGroup(locator, input, optionName) {
  const option = input.options.find(candidate => candidate.name === optionName);
  if (!option) {
    return;
  }
  let groups = valueAt(selectedOperation(), locator);
  if (!Array.isArray(groups)) {
    groups = [];
    setAt(selectedOperation(), locator, groups);
  }
  groups.push([authoredCandidate(option)]);
  delete state.candidateQueries[locatorToken([...locator, "new-group"])];
  dirty();
}

function authoredCandidate(option, input = {}) {
  return {
    ...(input.authored_outcomes ? {
      outcome: opaqueId("case")
    } : {}),
    option: option.name,
    inputs: defaultInputs(option.inputs),
    when: conditionOf(null)
  };
}

function nextCaseLabel(operation) {
  const labels = new Set(Object.values(stepPresentation(operation.id).outcomes || {}));
  for (let index = 1; ; index++) {
    const label = "Case " + index;
    if (!labels.has(label)) {
      return label;
    }
  }
}

function selectCandidate(locator, input, index, optionName) {
  const option = input.options.find(candidate => candidate.name === optionName);
  const candidates = valueAt(selectedOperation(), locator);
  if (!option || !Array.isArray(candidates) || !candidates[index]) {
    return;
  }
  state.jsonDraft = null;
  candidates[index] = {
    ...candidates[index],
    option: option.name,
    inputs: defaultInputs(option.inputs),
    when: conditionOf(candidates[index].when)
  };
  dirty();
}

function updateCandidateLabel(locator, index, value) {
  const candidates = valueAt(selectedOperation(), locator);
  const label = value.trim();
  if (!Array.isArray(candidates) || !candidates[index] || !label || label.length > 128) {
    render();
    return;
  }
  setOutcomeLabel(selectedOperation().id, candidates[index].outcome, label);
  creatorDirty();
}

function moveListItem(locator, index, direction) {
  const items = valueAt(selectedOperation(), locator);
  const target = index + direction;
  if (!Array.isArray(items) || target < 0 || target >= items.length) {
    return;
  }
  moveDraft(locator, index, target);
  [items[index], items[target]] = [items[target], items[index]];
  clearInputQueries();
  dirty();
}

function moveDraft(locator, index, target) {
  const draft = state.jsonDraft;
  if (!draft || draft.node !== state.selection.id
      || !samePath(draft.locator.slice(0, locator.length), locator)) {
    return;
  }
  if (draft.locator[locator.length] === index) {
    draft.locator[locator.length] = target;
  } else if (draft.locator[locator.length] === target) {
    draft.locator[locator.length] = index;
  }
}

function removeListItem(locator, index, input = {}) {
  const items = valueAt(selectedOperation(), locator);
  if (!Array.isArray(items) || index < 0 || index >= items.length) {
    return;
  }
  if (input.authored_outcomes) {
    const operation = selectedOperation();
    const candidate = items[index];
    if (!candidate?.outcome || outcomeTarget(operation, candidate.outcome) !== "end") {
      return;
    }
    const route = operation.id + "." + candidate.outcome;
    state.project.links = state.project.links.filter(link => link.from !== route);
    setOutcomeLabel(operation.id, candidate.outcome);
    items.splice(index, 1);
  } else {
    items.splice(index, 1);
  }
  state.jsonDraft = null;
  clearInputQueries();
  dirty();
}

function addNested(locator, id) {
  const definition = definitionOf(id);
  const steps = programAt(selectedOperation(), locator);
  if (!definition || !Array.isArray(steps)) {
    return;
  }
  steps.push({ use: id, inputs: defaultInputs(definition.inputs) });
  delete state.stepQueries[locatorToken(locator)];
  dirty();
}

function programAt(operation, locator) {
  const configured = valueAt(operation, locator);
  if (Array.isArray(configured)) {
    return configured;
  }
  const whenIndex = locator.lastIndexOf("when");
  if (whenIndex < 0) {
    return null;
  }
  const candidate = valueAt(operation, locator.slice(0, whenIndex));
  if (!candidate) {
    return null;
  }
  const condition = conditionOf(candidate.when);
  candidate.when = condition;
  if (locator[whenIndex + 1] === "transforms") {
    return condition.transforms;
  }
  if (locator[whenIndex + 1] === "all") {
    return condition.all[Number(locator[whenIndex + 2])] || null;
  }
  return null;
}

function compactCondition(operation, locator) {
  const whenIndex = locator.lastIndexOf("when");
  if (whenIndex < 0) {
    return;
  }
  const candidate = valueAt(operation, locator.slice(0, whenIndex));
  if (!candidate || Array.isArray(candidate.when)) {
    return;
  }
  const predicateIndex = locator[whenIndex + 1] === "all" ? Number(locator[whenIndex + 2]) : -1;
  if (predicateIndex >= 0 && candidate.when.all[predicateIndex]?.length === 0) {
    candidate.when.all.splice(predicateIndex, 1);
  }
  if (!candidate.when.transforms.length && !candidate.when.all.length) {
    candidate.when = conditionOf(null);
  }
}

function addPredicate(locator, id) {
  const definition = definitionOf(id);
  const candidate = valueAt(selectedOperation(), locator);
  if (!definition || !candidate) {
    return;
  }
  const condition = conditionOf(candidate.when);
  candidate.when = condition;
  condition.all.push([{ use: id, inputs: defaultInputs(definition.inputs) }]);
  delete state.candidateQueries[locatorToken([...locator, "when", "new-predicate"])];
  dirty();
}

function movePredicate(locator, index, direction) {
  const candidate = valueAt(selectedOperation(), locator);
  if (candidate && Array.isArray(candidate.when)) {
    candidate.when = conditionOf(candidate.when);
  }
  const predicates = candidate?.when?.all || [];
  const target = index + direction;
  if (target < 0 || target >= predicates.length) {
    return;
  }
  [predicates[index], predicates[target]] = [predicates[target], predicates[index]];
  clearInputQueries();
  dirty();
}

function removePredicate(locator, index) {
  const candidate = valueAt(selectedOperation(), locator);
  if (candidate && Array.isArray(candidate.when)) {
    candidate.when = conditionOf(candidate.when);
  }
  const predicates = candidate?.when?.all || [];
  if (index < 0 || index >= predicates.length) {
    return;
  }
  predicates.splice(index, 1);
  if (!candidate.when.transforms.length && !predicates.length) {
    candidate.when = conditionOf(null);
  }
  clearInputQueries();
  dirty();
}

function moveNested(locator, index, direction) {
  const steps = programAt(selectedOperation(), locator);
  const target = index + direction;
  if (target < 0 || target >= steps.length) {
    return;
  }
  state.jsonDraft = null;
  [steps[index], steps[target]] = [steps[target], steps[index]];
  dirty();
}

function removeNested(locator, index) {
  const steps = programAt(selectedOperation(), locator);
  if (!Array.isArray(steps) || index < 0 || index >= steps.length) {
    return;
  }
  steps.splice(index, 1);
  compactCondition(selectedOperation(), locator);
  state.jsonDraft = null;
  delete state.stepQueries[locatorToken(locator)];
  dirty();
}

function defaultInputs(inputs) {
  const values = {};
  inputs.forEach(input => {
    const value = defaultInput(input);
    if (value !== undefined) {
      values[input.name] = value;
    }
  });
  return values;
}

function defaultInput(input) {
  if (input.type === "steps") {
    return [];
  }
  if (input.type === "options") {
    const option = input.options.find(candidate => candidate.name === input.default);
    return option ? { option: option.name, inputs: defaultInputs(option.inputs) } : undefined;
  }
  if (input.type === "candidates") {
    const option = input.options.find(candidate => candidate.name === input.default);
    return option
      ? [{ option: option.name, inputs: defaultInputs(option.inputs), when: conditionOf(null) }]
      : [];
  }
  if (input.type === "matcher_groups") {
    return [];
  }
  if (Object.hasOwn(input, "default")) {
    return clone(input.default);
  }
  return undefined;
}

function initialValue(input) {
  switch (input.shape) {
    case "string": return "";
    case "number": return 0;
    case "boolean": return false;
    case "array": return [];
    case "object": return {};
    default: return null;
  }
}

function programInitialShape(operation, input, scopeInputs, scopeBase) {
  if (input.program_shape) {
    return input.program_shape;
  }
  let shape = input.candidate_source
    ? candidateSourceShape(operation, input.candidate_source, scopeInputs, scopeBase)
    : "";
  if (!input.candidate_source) {
    for (const sourceName of programSourceNames(input)) {
      const source = scopeInputs.find(candidate => candidate.name === sourceName);
      if (source) {
        shape = configuredInputShape(
          operation,
          source,
          [...scopeBase, source.name],
          scopeInputs,
          scopeBase
        );
        if (shape) {
          break;
        }
      }
    }
  }
  return shape;
}

function programValueShape(operation, input, locator, scopeInputs, scopeBase) {
  const shape = programInitialShape(operation, input, scopeInputs, scopeBase);
  return configuredProgram(operation, locator).reduce(
    (current, step) => definitionOf(step.use)?.returns[0]?.shape || current,
    shape
  );
}

function configuredProgram(operation, locator) {
  const configured = valueAt(operation, locator);
  if (Array.isArray(configured)) {
    return configured;
  }
  const whenIndex = locator.lastIndexOf("when");
  if (whenIndex < 0) {
    return [];
  }
  const candidate = valueAt(operation, locator.slice(0, whenIndex));
  if (!candidate) {
    return [];
  }
  const condition = conditionOf(candidate.when);
  if (locator[whenIndex + 1] === "transforms") {
    return condition.transforms;
  }
  if (locator[whenIndex + 1] === "all") {
    return condition.all[Number(locator[whenIndex + 2])] || [];
  }
  return [];
}

function programValues(operation, input, locator, scopeInputs, scopeBase) {
  if (input.candidate_source) {
    return candidateProgramValues(operation, input, locator, scopeInputs, scopeBase);
  }
  const steps = configuredProgram(operation, locator);
  const previews = state.previewCases.filter(preview => preview.step === operation.id);
  if (previews.length) {
    return previews.flatMap(preview => {
      const inputs = preview.inputs || {};
      const sourceName = programSourceNames(input)
        .find(name => Object.hasOwn(inputs, name));
      if (!sourceName) {
        return [];
      }
      let value = inputs[sourceName];
      const stages = preview.stages.filter(stage => stage.input === programPath(locator));
      for (let index = 0; index < steps.length; index++) {
        if (!stages[index] || !Object.hasOwn(stages[index], "value")) {
          return [];
        }
        value = stages[index].value;
      }
      return [value];
    });
  }
  const count = observedRoots(operation).length || 1;
  const values = Array(count).fill(undefined);
  for (const sourceName of programSourceNames(input)) {
    const source = scopeInputs.find(candidate => candidate.name === sourceName);
    if (!source) {
      continue;
    }
    const candidates = configuredInputValues(
      operation,
      source,
      [...scopeBase, source.name],
      scopeInputs,
      scopeBase
    );
    for (let index = 0; index < values.length; index++) {
      if (values[index] === undefined && candidates[index] !== undefined) {
        values[index] = candidates[index];
      }
    }
  }
  return steps.length ? [] : values.filter(value => value !== undefined);
}

function programSourceNames(input) {
  const source = input.value_source || {};
  return [source.input].filter(Boolean);
}

function candidateProgramValues(operation, input, locator, scopeInputs, scopeBase) {
  const steps = configuredProgram(operation, locator);
  const previews = state.previewCases.filter(preview => preview.step === operation.id);
  if (steps.length && previews.length) {
    return previews.flatMap(preview => {
      const stages = preview.stages.filter(stage => stage.input === programPath(locator));
      const finalStage = stages[steps.length - 1];
      return finalStage && Object.hasOwn(finalStage, "value") ? [finalStage.value] : [];
    });
  }
  return steps.length
    ? []
    : candidateSourceValues(operation, input.candidate_source, scopeInputs, scopeBase)
      .filter(value => value !== undefined);
}


function portAcceptsValue(port, shape, stats) {
  if (!port) {
    return true;
  }
  if (shape && port.shape !== "any" && port.shape !== shape) {
    return false;
  }
  if (!stats.length) {
    return true;
  }
  if (port.canonical && stats.some(value => !value.canonical || value.depth > 64)) {
    return false;
  }
  if (port.max_depth && stats.some(value => value.depth > Number(port.max_depth))) {
    return false;
  }
  if (port.max_json_bytes && stats.some(value => value.jsonBytes > Number(port.max_json_bytes))) {
    return false;
  }
  return true;
}

function valueStats(root) {
  let canonical = true;
  let depth = 0;
  let jsonBytes = 0;
  let value = root;
  let key = null;
  let parentDepth = 0;
  const pending = [];
  while (true) {
    if (key !== null) {
      canonical &&= !invalidSurrogate(key);
      jsonBytes += jsonStringBytes(key) + 1;
    }
    if (exactNumber(value) || typeof value === "number") {
      const number = canonicalNumber(exactNumber(value) ? value.rawJSON : String(value));
      canonical &&= number.canonical;
      jsonBytes += number.bytes;
    } else if (typeof value === "string") {
      canonical &&= !invalidSurrogate(value);
      jsonBytes += jsonStringBytes(value);
    } else if (value === null) {
      jsonBytes += 4;
    } else if (typeof value === "boolean") {
      jsonBytes += value ? 4 : 5;
    } else if (Array.isArray(value) || plainObject(value)) {
      const valueDepth = parentDepth + 1;
      depth = Math.max(depth, valueDepth);
      let size = Array.isArray(value) ? value.length : 0;
      if (!Array.isArray(value)) {
        for (const field in value) {
          size += Object.hasOwn(value, field) ? 1 : 0;
        }
      }
      jsonBytes += 2 + Math.max(0, size - 1);
      const children = childValues(value);
      const first = children.next();
      if (!first.done) {
        pending.push({ children, depth: valueDepth });
        ({ value, key } = first.value);
        parentDepth = valueDepth;
        continue;
      }
    }
    let next;
    do {
      if (!pending.length) {
        return { canonical, depth, jsonBytes };
      }
      const frame = pending.at(-1);
      next = frame.children.next();
      if (next.done) {
        pending.pop();
      } else {
        ({ value, key } = next.value);
        parentDepth = frame.depth;
      }
    } while (next.done);
  }
}

function* childValues(value) {
  if (Array.isArray(value)) {
    for (const child of value) {
      yield { value: child, key: null };
    }
    return;
  }
  for (const key in value) {
    if (Object.hasOwn(value, key)) {
      yield { value: value[key], key };
    }
  }
}

function canonicalNumber(source) {
  if (source.length - (source.startsWith("-") ? 1 : 0) > 1_024) {
    return { canonical: false, bytes: 0 };
  }
  const match = /^-?(0|[1-9]\d*)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(source);
  if (!match) {
    return { canonical: false, bytes: 0 };
  }
  const fraction = match[2] || "";
  const digits = match[1] + fraction;
  const first = digits.search(/[1-9]/);
  if (first < 0) {
    return { canonical: true, bytes: 1 };
  }
  let last = first;
  for (let index = first + 1; index < digits.length; index++) {
    if (digits[index] !== "0") {
      last = index;
    }
  }
  const precision = BigInt(last - first + 1);
  const trailing = BigInt(digits.length - last - 1);
  const scale = BigInt(fraction.length) - BigInt(match[3] || "0") - trailing;
  const characters = scale <= 0
    ? precision - scale
    : scale >= precision ? scale + 2n : precision + 1n;
  const canonical = characters <= 1_024n;
  return { canonical, bytes: canonical ? Number(characters) + (source.startsWith("-") ? 1 : 0) : 0 };
}

function jsonStringBytes(value) {
  let bytes = 2;
  for (let index = 0; index < value.length; index++) {
    const character = value[index];
    if (character === "\"" || character === "\\" || character === "\b"
        || character === "\f" || character === "\n" || character === "\r" || character === "\t") {
      bytes += 2;
      continue;
    }
    const codePoint = value.codePointAt(index);
    if (codePoint < 0x20) {
      bytes += 6;
    } else if (codePoint <= 0x7f) {
      bytes++;
    } else if (codePoint <= 0x7ff) {
      bytes += 2;
    } else if (codePoint <= 0xffff) {
      bytes += 3;
    } else {
      bytes += 4;
      index++;
    }
  }
  return bytes;
}

function invalidSurrogate(value) {
  for (let index = 0; index < value.length; index++) {
    const current = value.charCodeAt(index);
    if (current >= 0xd800 && current <= 0xdbff) {
      if (++index >= value.length) {
        return true;
      }
      const next = value.charCodeAt(index);
      if (next < 0xdc00 || next > 0xdfff) {
        return true;
      }
    } else if (current >= 0xdc00 && current <= 0xdfff) {
      return true;
    }
  }
  return false;
}

function configuredInputShape(operation, input, locator, scopeInputs, scopeBase) {
  const value = valueAt(operation, locator);
  if (input.type === "json") {
    return value === undefined ? "" : valueShape(value);
  }
  if (input.type === "path") {
    return availablePaths(operation).find(entry => samePath(entry.path, value))?.shape
      || (Array.isArray(value) ? "mixed" : "");
  }
  if (input.type === "options") {
    const option = input.options.find(candidate => candidate.name === value?.option);
    return sourceShape(operation, option, locator, scopeInputs, scopeBase);
  }
  if (input.type === "candidates") {
    const shapes = new Set((value || [])
      .map((candidate, index) => {
        const option = input.options.find(available => available.name === candidate.option);
        return sourceShape(operation, option, [...locator, index], scopeInputs, scopeBase);
      })
      .filter(Boolean));
    return shapes.size === 1 ? [...shapes][0] : shapes.size ? "mixed" : "";
  }
  if (input.type === "matcher_groups") {
    return "boolean";
  }
  return "";
}

function configuredInputValues(operation, input, locator, scopeInputs, scopeBase) {
  const count = observedRoots(operation).length || 1;
  const value = valueAt(operation, locator);
  if (input.type === "json") {
    return Array(count).fill(value);
  }
  if (input.type === "path") {
    return observedRoots(operation).map(root => Array.isArray(value)
      ? valueAt(root, value)
      : undefined);
  }
  if (input.type === "options") {
    const option = input.options.find(candidate => candidate.name === value?.option);
    return sourceValues(operation, option, locator, scopeInputs, scopeBase);
  }
  if (input.type === "candidates") {
    const previews = state.previewCases.filter(preview => preview.step === operation.id);
    if (previews.length === count) {
      return previews.map(preview => Object.hasOwn(preview.inputs || {}, input.name)
        ? preview.inputs[input.name]
        : undefined);
    }
    const resolved = Array(count).fill(undefined);
    const uncertain = Array(count).fill(false);
    (value || []).forEach((candidate, index) => {
      const option = input.options.find(available => available.name === candidate.option);
      const values = sourceValues(operation, option, [...locator, index], scopeInputs, scopeBase);
      values.forEach((candidateValue, example) => {
        if (resolved[example] !== undefined || uncertain[example] || candidateValue === undefined) {
          return;
        }
        if (conditionSteps(candidate.when).length) {
          uncertain[example] = true;
        } else {
          resolved[example] = candidateValue;
        }
      });
    });
    return resolved;
  }
  if (input.type === "matcher_groups") {
    const previews = state.previewCases.filter(preview => preview.step === operation.id);
    return previews.length === count
      ? previews.map(preview => Object.hasOwn(preview.inputs || {}, input.name)
        ? preview.inputs[input.name]
        : undefined)
      : Array(count).fill(undefined);
  }
  return Array(count).fill(undefined);
}

function candidateSourceShape(operation, source, scopeInputs, scopeBase) {
  return sourceShape(operation, source.option, source.locator, scopeInputs, scopeBase);
}

function candidateSourceValues(operation, source, scopeInputs, scopeBase) {
  return sourceValues(operation, source.option, source.locator, scopeInputs, scopeBase);
}

function sourceShape(operation, option, ownerLocator, scopeInputs, scopeBase) {
  const reference = sourceReference(option, ownerLocator, scopeInputs, scopeBase);
  return reference ? configuredInputShape(
    operation,
    reference.input,
    reference.locator,
    reference.inputs,
    reference.base
  ) : "";
}

function sourceValues(operation, option, ownerLocator, scopeInputs, scopeBase) {
  const reference = sourceReference(option, ownerLocator, scopeInputs, scopeBase);
  const count = observedRoots(operation).length || 1;
  return reference ? configuredInputValues(
    operation,
    reference.input,
    reference.locator,
    reference.inputs,
    reference.base
  ) : Array(count).fill(undefined);
}

function sourceReference(option, ownerLocator, scopeInputs, scopeBase) {
  const source = option?.value_source;
  if (!source) {
    return null;
  }
  if (source.scope === "owned") {
    const input = option.inputs.find(candidate => candidate.name === source.input);
    const base = [...ownerLocator, "inputs"];
    return input ? { input, locator: [...base, input.name], inputs: option.inputs, base } : null;
  }
  const input = scopeInputs.find(candidate => candidate.name === source.input);
  return input ? { input, locator: [...scopeBase, input.name], inputs: scopeInputs, base: scopeBase } : null;
}

function valueAt(root, locator) {
  return locator.reduce((value, part) => value !== null && value !== undefined && Object.hasOwn(value, part)
    ? value[part] : undefined, root);
}

function hasAt(root, locator) {
  const owner = valueAt(root, locator.slice(0, -1));
  return owner !== null && owner !== undefined && Object.hasOwn(owner, locator.at(-1));
}

function setAt(root, locator, value) {
  const owner = valueAt(root, locator.slice(0, -1));
  owner[locator.at(-1)] = value;
}

function deleteAt(root, locator) {
  const owner = valueAt(root, locator.slice(0, -1));
  if (owner !== null && owner !== undefined) {
    delete owner[locator.at(-1)];
  }
}

function locatorToken(locator) {
  return encodeURIComponent(JSON.stringify(locator));
}

function metaToken(input) {
  return encodeURIComponent(JSON.stringify(input));
}

function parseToken(value) {
  return parseExact(decodeURIComponent(value));
}

function inputId(locator) {
  return locator
    .filter(part => part !== "inputs")
    .map(part => String(part).replaceAll("_", "-"))
    .join("-");
}

function inputLabel(value) {
  const label = String(value).replaceAll(/[_.-]+/g, " ");
  return label.charAt(0).toUpperCase() + label.slice(1);
}

function inputDiagnosticPath(nodeId, locator) {
  const index = Number(state.editor.nodes[nodeId]?.index ?? -1);
  return "nodes[" + index + "]" + locator.reduce((path, part) => typeof part === "number"
    ? path + "[" + part + "]"
    : path + "." + part, "");
}

function clearPreview(clearSummary = false, clearCases = true) {
  state.traceController?.abort();
  state.traceController = null;
  state.traceStep = null;
  state.traceKey = "";
  if (clearCases) {
    state.traceCases = [];
    state.traceCasesKey = "";
    state.traceCasesPid = 0;
    state.traceContext = "";
  }
  if (clearSummary) {
    state.traceSummary = null;
    state.traceSummaryKey = "";
  }
  state.preview = null;
  state.previewCases = [];
  state.runResult = "";
}

function replaceApplication(application) {
  const changed = !currentApplication(application);
  if (changed) {
    clearPreview(true);
    state.observationController?.abort();
    state.observations = null;
    document.querySelector("#status-observations").hidden = true;
    scheduleWorldObservations();
    state.exampleIds = new Map();
    state.exampleInventoryKey = "";
    state.exampleCoverageKey = "";
  }
  state.application = changed ? application : { ...state.application, ...application };
  return changed;
}

function currentApplication(application) {
  return state.application.fingerprint === application.fingerprint
    && state.application.pid === application.pid
    && state.application.state === application.state;
}

function scheduleApplicationPoll(delay = 250) {
  clearTimeout(state.applicationPollTimer);
  state.applicationPollTimer = window.setTimeout(refreshApplication, delay);
}

function observationRetryDelay() {
  return document.hidden ? 2_000 : 500;
}

async function refreshApplication() {
  state.applicationPollTimer = 0;
  if (state.applicationRefreshing) {
    return;
  }
  let nextDelay;
  state.applicationRefreshing = true;
  try {
    const response = await fetch("/api/application", { cache: "no-store" });
    if (response.ok) {
      const application = parseExact(await response.text());
      const processChanged = replaceApplication(application);
      if (processChanged) {
        resetMetrics(true);
        refreshApplicationFacts();
        refreshTraceView();
      }
      if (application.state !== "running") {
        return;
      }
      const cachedExampleId = selectedExampleId();
      const [examplesResponse, exampleResponse] = await Promise.all([
        fetch("/api/examples/status", { cache: "no-store" }),
        cachedExampleId
          ? fetch(`/api/examples/${encodeURIComponent(cachedExampleId)}`, { cache: "no-store" })
          : Promise.resolve(null)
      ]);
      if (!currentApplication(application)) {
        nextDelay = 25;
        return;
      }
      const examples = examplesResponse.ok
        ? parseExact(await examplesResponse.text())
        : { state: "unavailable" };
      if (examples.application_pid !== undefined
          && Number(examples.application_pid) !== Number(application.pid)) {
        nextDelay = 25;
        return;
      }
      const inventoryReady = await refreshExampleIds(application);
      if (!inventoryReady) {
        nextDelay = observationRetryDelay();
        return;
      }
      const coverageReady = await refreshExampleCoverage(application, examples);
      if (!coverageReady) {
        nextDelay = observationRetryDelay();
        return;
      }
      const exampleId = selectedExampleId();
      const selectedResponse = exampleId === cachedExampleId
        ? exampleResponse
        : exampleId
          ? await fetch(`/api/examples/${encodeURIComponent(exampleId)}`, { cache: "no-store" })
          : null;
      if (selectedResponse && !selectedResponse.ok) {
        nextDelay = observationRetryDelay();
        return;
      }
      const example = selectedResponse ? parseExact(await selectedResponse.text()) : null;
      if (!currentApplication(application)
          || (example?.application_pid !== undefined
          && Number(example.application_pid) !== Number(application.pid))) {
        nextDelay = 25;
        return;
      }
      application.examples = examples;
      application.example = example;
      const currentExamples = state.application.examples || {};
      const currentExample = state.application.example || {};
      const suiteChanged = currentExamples.application_pid !== examples.application_pid
        || currentExamples.revision !== examples.revision
        || currentExamples.state !== examples.state
        || currentExample.id !== example?.id
        || currentExample.status !== example?.status
        || currentExample.events !== example?.events;
      replaceApplication(application);
      if (suiteChanged || processChanged) {
        refreshApplicationFacts();
        applyExampleCoverage();
      }
      await requestSelectedTrace();
    } else {
      nextDelay = observationRetryDelay();
    }
  } catch (_error) {
    // The current build state remains visible while the rolling application restarts.
    nextDelay = observationRetryDelay();
  } finally {
    state.applicationRefreshing = false;
    if (state.optionsPending) {
      state.optionsPending = false;
      refreshPickerOptions();
      if (!state.pathPicker) refreshNestedOptions();
    }
    const running = ["queued", "running"].includes(state.application.examples?.state);
    scheduleApplicationPoll(nextDelay ?? (document.hidden ? 2_000 : running ? 100 : 500));
  }
}

async function refreshExampleIds(application) {
  const key = JSON.stringify([application.fingerprint || "", application.pid || 0]);
  if (state.exampleInventoryKey === key) {
    return true;
  }
  const response = await fetch("/api/examples", { cache: "no-store" });
  if (!response.ok) {
    if (currentApplication(application)) {
      state.exampleIds = new Map();
      state.exampleInventoryKey = "";
    }
    return false;
  }
  const inventory = parseExact(await response.text());
  if (!currentApplication(application)
      || !Array.isArray(inventory.cases)
      || Number(inventory.application_pid) !== Number(application.pid)) {
    return false;
  }
  const ids = new Map();
  inventory.cases.forEach(example => {
    if (!plainObject(example) || typeof example.trigger !== "string"
        || !Number.isInteger(Number(example.index)) || typeof example.id !== "string") {
      return;
    }
    const trigger = ids.get(example.trigger) || new Map();
    trigger.set(Number(example.index), example.id);
    ids.set(example.trigger, trigger);
  });
  state.exampleIds = ids;
  state.exampleInventoryKey = key;
  return true;
}

async function refreshExampleCoverage(application, examples) {
  if (examples.state !== "completed") {
    state.exampleCoverageKey = "";
    return true;
  }
  const key = JSON.stringify([
    application.fingerprint || "",
    application.pid || 0,
    examples.revision || 0
  ]);
  const previous = state.application.examples || {};
  if (state.exampleCoverageKey === key && previous.coverage_bits !== undefined) {
    examples.coverage_bits = previous.coverage_bits;
    examples.covered_steps = previous.covered_steps;
    return true;
  }
  const response = await fetch("/api/examples/coverage", { cache: "no-store" });
  if (!response.ok) {
    return false;
  }
  const coverage = parseExact(await response.text());
  if (!currentApplication(application)
      || Number(coverage.application_pid) !== Number(application.pid)
      || Number(coverage.revision) !== Number(examples.revision)
      || typeof coverage.coverage_bits !== "string") {
    return false;
  }
  examples.coverage_bits = coverage.coverage_bits;
  examples.covered_steps = coverage.covered_steps;
  state.exampleCoverageKey = key;
  return true;
}

function selectedExampleId() {
  return state.exampleIds.get(state.exampleTrigger)?.get(state.exampleIndex) || "";
}

function refreshApplicationFacts() {
  const builtAt = Number(state.application.built_at || 0);
  const values = {
    "application-pid": state.application.pid || "",
    "build-path": state.application.build_path || "",
    "application-build-state": state.build,
    "application-run-state": inputLabel(state.application.state || "unavailable"),
    "last-build": builtAt ? new Date(builtAt).toLocaleString() : "Not built",
    "application-last-build": builtAt ? new Date(builtAt).toLocaleString() : "Not built",
    "example-suite-state": inputLabel(state.application.examples?.state || "unavailable"),
    "example-suite-progress": exampleProgress(),
    "example-trace-storage": formatBytes(state.application.examples?.storage_bytes)
  };
  Object.entries(values).forEach(([id, value]) => {
    const target = document.getElementById(id);
    if (target) {
      target.textContent = value;
    }
  });
  renderWorldStatus();
  scheduleWorldObservations(180);
}

function exampleProgress() {
  const examples = state.application.examples;
  return examples?.total === undefined
    ? "Unavailable"
    : `${examples.completed || 0} / ${examples.total}`;
}

function runtimeDetails() {
  return `<details id="runtime-details" class="inspector-section">
    <summary>Runtime metrics</summary>
    <div id="metrics-panel">${metricsPanel()}</div>
  </details>`;
}

function metricsSetting(operation) {
  return `
    <section class="inspector-section metric-setting">
      <label class="check-line" for="node-metrics">
        <span>Measure this Step</span>
        <input id="node-metrics" type="checkbox" data-node-metrics
               ${operation.metrics === false ? "" : "checked"}>
      </label>
    </section>`;
}

function metricTarget() {
  return !state.regionSelection && state.inspectorMode === "inspect" && ["trigger", "step"].includes(state.selection.type)
    ? state.selection.id : "app";
}

function scheduleMetricsPoll(delay = 1_000) {
  clearTimeout(state.metricsPollTimer);
  state.metricsPollTimer = window.setTimeout(refreshMetrics, delay);
}

function resetMetrics(poll = false) {
  clearTimeout(state.metricsPollTimer);
  state.metricsPollTimer = 0;
  state.metricsController?.abort();
  state.metricsController = null;
  state.metricsNode = "";
  renderMetrics();
  if (poll) {
    scheduleMetricsPoll(0);
  }
}

async function refreshMetrics() {
  state.metricsPollTimer = 0;
  const target = metricTarget();
  if (document.hidden || state.build !== "Built" || state.pendingProject) {
    scheduleMetricsPoll(500);
    return;
  }
  state.metricsController?.abort();
  const controller = new AbortController();
  const application = {
    fingerprint: state.application.fingerprint,
    pid: state.application.pid,
    state: state.application.state
  };
  state.metricsController = controller;
  try {
    const path = target === "app"
      ? "/api/metrics"
      : "/api/metrics/nodes/" + encodeURIComponent(target);
    const response = await fetch(path, { cache: "no-store", signal: controller.signal });
    if (!response.ok) {
      throw new Error("Runtime metrics are unavailable.");
    }
    const metrics = parseExact(await response.text());
    let catalog = state.metricCatalog;
    if (Number(catalog?.application_pid) !== Number(application.pid) || catalog?.fingerprint !== application.fingerprint) {
      const response = await fetch("/api/metrics/catalog", { cache: "no-store", signal: controller.signal });
      if (!response.ok) throw new Error("Metric definitions are unavailable.");
      catalog = parseExact(await response.text());
    }
    if (state.metricsController !== controller
        || metricTarget() !== target
        || !currentApplication(application)
        || Number(metrics.application_pid) !== Number(application.pid)
        || Number(catalog.application_pid) !== Number(application.pid)) {
      return;
    }
    state.metrics = metrics;
    state.metricCatalog = { ...catalog, fingerprint: application.fingerprint };
    state.metricsNode = target;
  } catch (_error) {
    if (state.metricsController !== controller) {
      return;
    }
    state.metrics = null;
    state.metricsNode = "";
  } finally {
    if (state.metricsController === controller) {
      state.metricsController = null;
      renderMetrics();
      scheduleMetricsPoll(1_000);
    }
  }
}

function renderMetrics() {
  const panel = document.querySelector("#metrics-panel");
  if (panel) {
    panel.innerHTML = metricsPanel();
  }
  renderWorldStatus();
}

function metricsPanel() {
  const target = metricTarget();
  if (state.metricsNode !== target || !plainObject(state.metrics)) {
    return "";
  }
  if (target === "app") {
    const process = metricRows(state.metrics.process);
    const columns = [
      [...metricRows(state.metrics.application?.metrics), ...metricRows(state.metrics), ...process.filter(([, , id]) => id.startsWith("metric_"))],
      process.filter(([, , id]) => !id.startsWith("system_") && !id.startsWith("metric_")),
      process.filter(([, , id]) => id.startsWith("system_"))
    ];
    const measurements = new Map();
    columns.forEach((column, index) => column.forEach(metric => {
      const [label, , id] = metric;
      const name = id.replace(/^(?:app|application|process|jvm|system)_/, "");
      const key = JSON.stringify([name, state.metricCatalog.metrics[id].unit || ""]);
      if (!measurements.has(key)) measurements.set(key, []);
      const matches = measurements.get(key);
      let row = matches.find(candidate => !candidate.values[index]);
      if (!row) {
        row = {label: name === id ? label : label.replace(/^(?:App|Application|Process|JVM|System)\s+/i, ""),
          values: Array(3).fill(null)};
        matches.push(row);
      }
      row.values[index] = metric;
    }));
    return `<table class="metric-systems" aria-label="Application, JVM and System metrics"><thead><tr>${["Metric", "App", "JVM", "System"].map(name => `<th scope="col">${name}</th>`).join("")}</tr></thead><tbody>${
      [...measurements.values()].flat().map(row => `<tr><th scope="row">${html(row.label)}</th>${row.values.map(metric =>
        metric ? `<td data-metric-id="${html(metric[2])}" title="${html(metric[0])}"><output>${html(metric[1])}</output></td>` : "<td></td>"
      ).join("")}</tr>`).join("")}</tbody></table>`;
  }
  const steps = Array.isArray(state.metrics.steps) ? state.metrics.steps : [];
  const flows = Array.isArray(state.metrics.flows) ? state.metrics.flows : [];
  const step = steps.find(candidate => candidate.id === target)?.metrics;
  const flow = flows.find(candidate => candidate.id === target)?.metrics;
  if (!step && !flow) {
    return "";
  }
  const rows = [];
  if (step) {
    rows.push(...metricRows(step, flow ? "Step" : ""));
    rows.push(
      [flow ? "Step sampled average" : "Sampled average", averageNanos(step)]
    );
  }
  if (flow) {
    rows.push(...metricRows(flow, "Flow"));
    rows.push(
      ["Flow average", averageNanos(flow)]
    );
  }
  return metricFacts("Step metrics", rows);
}

function metricRows(values, prefix = "") {
  const definitions = state.metricCatalog?.metrics || {};
  return Object.entries(values || {}).filter(([id]) => Object.hasOwn(definitions, id)).map(([id, value]) => {
    const definition = definitions[id];
    const label = definition.label || id;
    const formatted = definition.sample_count && !metricNumber(values[definition.sample_count]) ? "No sample"
      : definition.unit === "bytes" ? formatBytes(value)
      : definition.unit === "ns" ? formatNanos(value)
      : definition.unit === "ms" ? formatMillis(value)
      : definition.unit === "ppm" ? formatPercentPpm(value)
      : definition.unit === "count" ? formatInteger(value) : `${numberText(value)} ${definition.unit || ""}`.trim();
    return [prefix ? `${prefix} ${label[0].toLowerCase()}${label.slice(1)}` : label, formatted, id];
  });
}

function metricFacts(title, rows) {
  const facts = rows
    .filter(([_label, value]) => value !== null && value !== undefined)
    .map(([label, value, id]) => `<div${id ? ` data-metric-id="${html(id)}"` : ""}><dt>${html(label)}</dt><dd>${html(value)}</dd></div>`)
    .join("");
  return `<section class="inspector-section facts runtime-metrics">
    <div class="section-heading"><strong>${html(title)}</strong></div>
    <dl>${facts}</dl>
  </section>`;
}

function averageNanos(metrics) {
  const samples = metricNumber(metrics.duration_samples);
  return samples ? formatNanos(metricNumber(metrics.duration_nanos_total) / samples) : "No sample";
}

function metricNumber(value) {
  const number = Number(numberText(value === undefined ? 0 : value));
  return Number.isFinite(number) ? number : 0;
}

function formatInteger(value) {
  const source = String(numberText(value === undefined ? 0 : value));
  const sign = source.startsWith("-") ? "-" : "";
  const digits = sign ? source.slice(1) : source;
  return sign + digits.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

function formatBytes(value) {
  const bytes = Math.max(0, metricNumber(value));
  const units = ["B", "KiB", "MiB", "GiB", "TiB"];
  let amount = bytes;
  let unit = 0;
  while (amount >= 1_024 && unit < units.length - 1) {
    amount /= 1_024;
    unit++;
  }
  return `${unit ? amount.toFixed(amount >= 10 ? 0 : 1) : formatInteger(Math.round(amount))} ${units[unit]}`;
}

function formatMillis(value) {
  const millis = Math.max(0, metricNumber(value));
  if (millis < 1_000) {
    return `${formatInteger(Math.round(millis))} ms`;
  }
  if (millis < 60_000) {
    return `${(millis / 1_000).toFixed(1)} s`;
  }
  if (millis < 3_600_000) {
    return `${Math.floor(millis / 60_000)}m ${Math.floor(millis % 60_000 / 1_000)}s`;
  }
  if (millis < 86_400_000) {
    return `${Math.floor(millis / 3_600_000)}h ${Math.floor(millis % 3_600_000 / 60_000)}m`;
  }
  return `${Math.floor(millis / 86_400_000)}d ${Math.floor(millis % 86_400_000 / 3_600_000)}h`;
}

function formatNanos(value) {
  const nanos = Math.max(0, metricNumber(value));
  if (nanos < 1_000) {
    return `${Math.round(nanos)} ns`;
  }
  if (nanos < 1_000_000) {
    return `${(nanos / 1_000).toFixed(nanos >= 10_000 ? 0 : 1)} us`;
  }
  if (nanos < 1_000_000_000) {
    return `${(nanos / 1_000_000).toFixed(nanos >= 10_000_000 ? 0 : 1)} ms`;
  }
  return `${(nanos / 1_000_000_000).toFixed(2)} s`;
}

function formatPercentPpm(value) {
  return `${(metricNumber(value) / 10_000).toFixed(1)}%`;
}

async function requestSelectedTrace() {
  if (state.build === "Building" || !state.application.pid || state.jsonDraft || state.exampleDraft) {
    return;
  }
  const example = selectedTraceCase();
  if (!example || ["queued", "running"].includes(example.status)) {
    if (state.traceKey || state.traceSummaryKey) {
      clearPreview(true);
      renderPreview();
      renderRunResult();
    }
    return;
  }
  const summaryKey = [
    state.application.fingerprint || "",
    state.application.pid || 0,
    example.id,
    example.status,
    example.events
  ].join(":");
  const operation = ["trigger", "step"].includes(state.selection.type)
    ? selectedOperation()
    : null;
  const observation = operation ? observationFor(operation) : null;
  if (!observation) {
    return;
  }
  const casesKey = [
    state.application.fingerprint || "",
    state.application.pid || 0,
    state.application.examples?.state === "completed"
      ? "complete"
      : `selected:${example.id}:${example.status}:${example.events}`,
    observation.node,
    observation.context,
    operation.id
  ].join(":");
  const key = summaryKey + ":" + casesKey;
  if (state.traceKey === key && (state.traceController || state.traceSummary && state.traceCasesKey === casesKey)) {
    return;
  }
  state.traceController?.abort();
  const controller = new AbortController();
  state.traceController = controller;
  state.traceKey = key;
  const applicationPid = Number(state.application.pid);
  try {
    const summaryRequest = state.traceSummaryKey === summaryKey && state.traceSummary
      ? Promise.resolve(state.traceSummary)
      : readExampleProjection(
        `/api/examples/${encodeURIComponent(example.id)}/view`,
        controller.signal
      );
    const casesRequest = state.traceCasesKey === casesKey
      ? Promise.resolve({
        application_pid: state.traceCasesPid,
        node: observation.node,
        cases: state.traceCases
      })
      : readExampleProjection(`/api/examples/steps/${observation.node}`, controller.signal);
    const [summary, stepCases] = await Promise.all([summaryRequest, casesRequest]);
    summary.nodes = summary.nodes instanceof Set
      ? summary.nodes
      : new Set((summary.nodes || []).filter(Number.isInteger));
    if (Number(summary.application_pid) !== applicationPid
        || !Array.isArray(stepCases.cases)
        || Number(stepCases.application_pid) !== applicationPid
        || Number(stepCases.node) !== observation.node) {
      throw new Error("Built Example projection is from another application process.");
    }
    if (controller.signal.aborted || state.traceController !== controller || state.traceKey !== key) {
      return;
    }
    state.traceSummary = summary;
    state.traceSummaryKey = summaryKey;
    state.traceCases = stepCases.cases.filter(plainObject);
    state.traceCasesKey = casesKey;
    state.traceCasesPid = Number(stepCases.application_pid);
    state.traceContext = observation.context;
    const selected = state.traceCases.find(candidate => candidate.id === example.id);
    const matchesBuilt = JSON.stringify(operation) === JSON.stringify(
      state.builtProject?.nodes.find(candidate => candidate.id === operation.id));
    state.traceStep = state.selection.type === "step"
        && matchesBuilt
        && observation.context === "input"
        && plainObject(selected?.projection)
      ? selected.projection
      : null;
    state.previewCases = matchesBuilt && observation.context === "input"
      ? state.traceCases.flatMap(candidate => plainObject(candidate.projection)
      ? [{
        ...candidate.projection,
        step: candidate.projection.id,
        trigger: candidate.trigger,
        example: candidate.index,
        example_name: candidate.name
      }]
      : [])
      : [];
    state.traceController = null;
    selectTracePreview(example);
  } catch (error) {
    if (controller.signal.aborted || state.traceController !== controller) {
      return;
    }
    state.traceController = null;
    state.traceStep = null;
    state.traceCases = [];
    state.traceCasesKey = "";
    state.traceCasesPid = 0;
    state.traceContext = "";
    state.previewCases = [];
    state.preview = {
      step: state.selection.id,
      status: "unavailable",
      inputs: {},
      stages: [],
      message: error instanceof Error ? error.message : "Built example trace is unavailable."
    };
    state.runResult = "";
    refreshTraceView();
  }
}

async function readExampleProjection(path, signal) {
  const response = await fetch(path, { cache: "no-store", signal });
  const source = await response.text();
  if (!response.ok) {
    const failure = parseExact(source);
    throw new Error(failure.message || "Built example trace is unavailable.");
  }
  const projection = parseExact(source);
  if (!plainObject(projection)) {
    throw new Error("Built example view is invalid.");
  }
  return projection;
}

function selectedTraceCase() {
  if (state.exampleIndex < 0) return null;
  const operation = state.selection.type === "trigger" || state.selection.type === "step"
    ? node(state.selection.id)
    : null;
  const trigger = operation ? triggerFor(operation.id) : null;
  if (!trigger) {
    return null;
  }
  const index = Math.max(0, Math.min(state.exampleIndex, trigger.examples.length - 1));
  const example = state.application.example;
  return example?.trigger === trigger.id && Number(example.index) === index ? example : null;
}

function selectTracePreview(example = selectedTraceCase()) {
  const summary = state.traceSummary || {};
  const operation = state.selection.type === "step" ? selectedOperation() : null;
  const reached = operation && plainObject(state.traceStep) ? state.traceStep : null;
  if (reached) {
    state.preview = {
      ...reached,
      step: reached.id,
      selected_candidates: selectedCandidates(operation, reached.options || {}, reached.outcome)
    };
  } else {
    state.preview = null;
  }
  if (plainObject(summary.result)) {
    const result = { example: example?.name, ...summary.result };
    state.runResult = JSON.stringify(result, null, 2);
  } else if (example && !["succeeded", "running", "queued"].includes(example.status)) {
    state.runResult = JSON.stringify({
      example: example.name,
      status: example.status,
      ...(example.message ? { message: example.message } : {})
    }, null, 2);
  } else {
    state.runResult = "";
  }
  refreshTraceView();
}

function selectedCandidates(operation, options, outcome) {
  const selected = {};
  const definition = definitionFor(operation);
  definition?.inputs.filter(input => input.type === "candidates").forEach(input => {
    const candidates = operation.inputs?.[input.name] || [];
    const matches = input.authored_outcomes ? candidate => candidate.outcome === outcome
      : candidate => candidate.option === options[input.name];
    const index = candidates.findIndex(matches);
    if (index >= 0 && index === candidates.findLastIndex(matches)) {
      selected[inputDiagnosticPath(operation.id, ["inputs", input.name])] = index;
    }
  });
  return selected;
}

function refreshTraceView() {
  applyExampleCoverage();
  refreshPickerOptions();
  renderPreview();
  if (state.pathPicker) {
    renderBuildStatus();
    refreshPathPicker();
    return;
  }
  refreshNestedOptions();
  renderRunResult();
}

function refreshPickerOptions() {
  const options = document.querySelector("#step-options");
  if (options && state.picker && observationMayReplace(options)) {
    options.innerHTML = pickerOptions();
  }
}

function observationMayReplace(container) {
  if (!container.querySelector("button")) {
    return true;
  }
  const active = document.activeElement;
  const replace = !container.matches(":hover") && !(active instanceof Element && container.contains(active));
  state.optionsPending ||= !replace;
  return replace;
}

function applyExampleCoverage() {
  const examples = state.application.examples;
  if (!state.project) return;
  const deployed = state.builtProject || state.project;
  const bits = state.application.state === "running" ? examples?.coverage_bits || "" : "";
  if (state.coverageSource !== deployed || state.coverageBits !== bits) {
    state.worldCovered = exampleCoverage(bits, deployed);
    state.worldCovered.delete("app");
    state.coverageSource = deployed;
    state.coverageBits = bits;
  }
  const selectedIndexes = new Set(selectedTraceCase() ? [...(state.traceSummary?.nodes || [])].map(Number) : []);
  state.worldSelected = new Set(Object.entries(state.editor.nodes)
    .filter(([, value]) => selectedIndexes.has(Number(value.index))).map(([id]) => id));
  if (selectedTraceCase()?.trigger) {
    state.worldSelected.add(selectedTraceCase().trigger);
  }
  state.world?.repaint();
  renderWorldStatus();
  scheduleWorldObservations(180);
}

function exampleCoverage(encoded, project) {
  const bytes = encoded
    ? Uint8Array.from(atob(encoded), character => character.charCodeAt(0))
    : new Uint8Array();
  return new Set(Object.entries(state.editor.nodes).filter(([, value]) => {
    const index = Number(value.index);
    return (bytes[index >> 3] & (1 << (index & 7))) !== 0;
  }).map(([id]) => id));
}

function renderPreview() {
  renderDockObservation();
  const operation = selectedOperation();
  if (!operation) return;
  document.querySelectorAll("[data-path-observation]").forEach(slot => {
    const locator = parseToken(slot.dataset.pathObservation);
    const input = parseToken(slot.dataset.inputMeta);
    const path = hasAt(operation, locator) ? valueAt(operation, locator) : defaultInput(input);
    const content = pathValues(operation, input, path, locator);
    if (slot.innerHTML !== content) slot.innerHTML = content;
  });
  const inputs = previewInputs(operation);
  document.querySelectorAll("[data-input-result]").forEach(slot => {
    const name = slot.dataset.inputResult;
    const content = inputs.has(name)
      ? exampleValue(inputs.get(name), `data-preview-input-value="${html(name)}"`) : "";
    if (slot.innerHTML !== content) slot.innerHTML = content;
  });
  const first = document.querySelector("[data-preview-input-value]");
  if (first && state.selection.type === "step") first.id = "preview-source";
  const error = document.querySelector("#preview-error");
  if (error) error.textContent = state.preview?.message || "";
  document.querySelectorAll("[data-preview-slot]").forEach(slot => {
    slot.innerHTML = previewStage(
      operation,
      slot.dataset.previewInput,
      Number(slot.dataset.previewSlot)
    );
  });
  refreshCandidateSelection();
}

function refreshCandidateSelection() {
  document.querySelectorAll(".candidate[data-candidate-path]").forEach(candidate => {
    const selected = state.preview?.selected_candidates?.[candidate.dataset.candidatePath]
      === Number(candidate.dataset.candidateIndex);
    candidate.classList.toggle("selected-candidate", selected);
    const status = candidate.querySelector("[data-candidate-status]");
    if (status) {
      status.textContent = (selected ? "Selected, " : "")
        + status.dataset.candidateDefaultStatus;
    }
  });
}

function refreshNestedOptions() {
  const operation = selectedOperation();
  document.querySelectorAll("[data-step-options]").forEach(options => {
    if (!observationMayReplace(options)) {
      return;
    }
    const scope = parseToken(options.dataset.inputScope);
    options.innerHTML = nestedOptions(
      operation,
      parseToken(options.dataset.inputMeta),
      parseToken(options.dataset.stepOptions),
      scope.inputs,
      scope.base
    );
  });
  document.querySelectorAll("[data-predicate-options]").forEach(options => {
    if (!observationMayReplace(options)) {
      return;
    }
    const scope = parseToken(options.dataset.inputScope);
    options.innerHTML = predicateOptionsFor(
      operation,
      parseToken(options.dataset.inputMeta),
      parseToken(options.dataset.predicateOptions),
      scope.inputs,
      scope.base
    );
  });
}

function previewInputs(operation) {
  const preview = state.preview?.step === operation.id ? state.preview : null;
  if (!preview) {
    return new Map();
  }
  const definition = definitionOf(operation.use);
  const previewInputs = preview.inputs || {};
  const programs = definition.inputs.filter(input => input.type === "steps" && input.value_source);
  const consumed = new Set(programs.flatMap(input => [
    input.name,
    input.value_source.input
  ].filter(Boolean)));
  const values = definition.receives
    .filter(port => Object.hasOwn(previewInputs, port.name))
    .map(port => [port.name, previewInputs[port.name]]);
  values.push(...programs.flatMap(input => {
    const source = input.value_source;
    const value = Object.hasOwn(previewInputs, input.name)
      ? previewInputs[input.name]
      : Object.hasOwn(previewInputs, source.input)
        ? previewInputs[source.input]
        : undefined;
    return value === undefined ? [] : [[source.input, value]];
  }));
  definition.inputs
    .filter(input => input.type !== "path" && input.type !== "steps" && !consumed.has(input.name))
    .filter(input => Object.hasOwn(previewInputs, input.name))
    .forEach(input => values.push([input.name, previewInputs[input.name]]));
  return new Map(values);
}

function observedExampleContext(operation, after = false) {
  if (state.build !== "Built" || state.pendingProject) return { status: "Pending build" };
  const selected = selectedTraceCase();
  const example = selected && state.traceCases.find(candidate => candidate.id === selected.id);
  if (!example) return { status: state.traceController ? "Loading example" : "Unavailable" };
  if (["queued", "running"].includes(example.status)) return { status: "Example running" };
  const projection = example.projection;
  if (state.traceContext === "input" && !plainObject(projection)) return { status: "Not reached" };
  if (after && state.traceContext !== "input"
      && definitionFor(operation)?.kind !== "trigger") return { status: "Pending build" };
  const context = state.traceContext === "trigger" ? example.initial_context
    : after || state.traceContext === "output" ? projection?.context : projection?.input_context;
  return plainObject(context) ? { root: { context } } : { status: "Unavailable" };
}

function pathValues(operation, input, path, locator) {
  if (!path?.length) return "";
  const before = observedExampleContext(operation);
  if (before.status) return `<span class="value-status">${before.status}</span>`;
  const value = valueAt(before.root, path);
  if (definitionFor(operation)?.kind === "trigger") return exampleValue(value, 'data-path-value="after"');
  const received = locator[0] === "receives" ? `data-preview-input-value="${html(locator[1])}"` : "";
  const source = exampleValue(value, `data-path-value="before" ${received}`);
  if (input.access === "read") return source;
  const after = observedExampleContext(operation, true);
  const result = after.status ? `<span class="value-status">${after.status}</span>`
    : exampleValue(valueAt(after.root, path), `data-path-value="after"${
      locator[0] === "returns" ? ` data-preview-output="${html(locator[1])}"` : ""}`);
  return `${source}<span class="value-arrow" aria-label="becomes">&rarr;</span>${result}`;
}

function pathChoiceValue(operation, path) {
  const observation = observedExampleContext(operation);
  return observation.status ? `<span class="value-status">${observation.status}</span>`
    : exampleValue(valueAt(observation.root, path), "", false);
}

function exampleValue(value, attributes = "", expandable = true) {
  const source = value === undefined ? "Missing" : previewValue(value);
  if (source.length <= 80) return `<output ${attributes}>${html(source)}</output>`;
  const summary = Array.isArray(value) ? `Array (${value.length})`
    : plainObject(value) && !exactNumber(value) ? `Object (${Object.keys(value).length} fields)`
    : source.slice(0, 77) + "...";
  return expandable ? `<details class="example-value"><summary>${html(summary)}</summary>
      <output ${attributes}>${html(source)}</output></details>` : `<output>${html(summary)}</output>`;
}

function previewStage(operation, input, index) {
  const preview = state.preview?.step === operation.id ? state.preview : null;
  const stage = preview?.stages.filter(candidate => candidate.input === input)[index];
  if (!stage) {
    return "";
  }
  const value = Object.hasOwn(stage, "value") ? previewValue(stage.value) : stage.status;
  return `<output data-preview-stage="${index}" data-preview-invocation="${html(stage.invocation)}"
                  data-preview-status="${html(stage.status)}">${html(value)}</output>`;
}

function previewValue(value) {
  const source = JSON.stringify(value);
  return source === undefined ? "" : source;
}

function runResultPanel() {
  return state.runResult ? `<section class="inspector-section">
    <div class="section-heading"><strong>Latest example result</strong><span>Automatic</span></div>
    <pre class="run-result">${html(state.runResult)}</pre>
  </section>` : "";
}

function renderRunResult() {
  const panel = document.querySelector("#run-result-panel");
  if (panel) {
    panel.innerHTML = runResultPanel();
  }
}

function dirty(projectChanged = true) {
  if (projectChanged) {
    if (state.builtProject && !state.builtProject.nodes.some(operation => operation.id === state.selection.id)) {
      state.revealNode = state.selection.id;
    }
    clearPreview(false, state.selection.type === "trigger");
    resetMetrics();
    state.pendingProject = true;
    state.build = "Building";
    state.runResult = "";
  }
  state.revision++;
  if (projectChanged) {
    state.diagnostics = state.diagnostics.filter(diagnostic => diagnostic.code.startsWith("CREATOR_"));
  }
  state.localDiagnostics = [];
  clearTimeout(state.saveTimer);
  state.saveTimer = window.setTimeout(() => {
    const revision = state.revision;
    const functional = state.pendingProject;
    state.pendingProject = false;
    const projectSource = JSON.stringify(state.project);
    const creatorSource = JSON.stringify(state.creator);
    enqueueWrite({ revision, projectChanged: functional, projectSource, creatorSource });
  }, 120);
  render();
}

function creatorDirty() {
  dirty(false);
}

function enqueueWrite(write) {
  state.pendingWrite = state.pendingWrite
    ? { ...write, projectChanged: state.pendingWrite.projectChanged || write.projectChanged }
    : write;
  if (!state.writeActive) {
    state.writeActive = true;
    state.writePromise = drainWrites();
  }
}

async function drainWrites() {
  while (state.pendingWrite) {
    const write = state.pendingWrite;
    state.pendingWrite = null;
    await save(write.revision, write.projectChanged, write.projectSource, write.creatorSource);
  }
  state.writeActive = false;
  window.setTimeout(() => {
    if (state.selection.id && state.build === "Built" && !state.pendingProject && !state.writeActive && !state.editorController) {
      void loadEditor(state.selection.id).then(loaded => {
        if (loaded !== "loaded") return;
        state.worldNodes = new Map(state.project.nodes.map(operation => [operation.id, operation]));
        state.worldGroups = new Map(state.creator.groups.map(group => [group.id, group]));
        if (state.inspectorMode === "groups") render();
        else applyExampleCoverage();
        void requestSelectedTrace();
      });
    }
  }, 0);
}

async function save(revision, projectChanged, projectSource, creatorSource) {
  if (revision !== state.revision) {
    state.pendingProject ||= projectChanged;
    return false;
  }
  try {
    const project = parseExact(projectSource);
    const creator = parseExact(creatorSource);
    let payload = null;
    const changes = projectChanged ? documentChanges(state.builtProject, project) : {};
    const removedFlows = [...state.removedFlows];
    if (removedFlows.length) changes.remove_flows = removedFlows;
    if (Object.keys(changes).length) {
      const projectResponse = await fetch("/api/project", {
        method: "PATCH",
        headers: mutationHeaders(),
        body: JSON.stringify({ revision: state.projectVersion, changes })
      });
      payload = parseExact(await projectResponse.text());
      if (!projectResponse.ok) {
        if (revision === state.revision) {
          if (payload.application) {
            replaceApplication(payload.application);
          }
          state.diagnostics = payload.diagnostics || [{
            code: "CREATOR_SAVE_FAILED",
            message: payload.message || "Project is not buildable.",
            path: ""
          }];
          state.build = "Not built";
          render();
        }
        return false;
      }
      // A stale UI response still advances the transport baseline for the next queued save.
      state.projectVersion = payload.revision;
      state.builtProject = project;
      state.removedFlows = state.removedFlows.filter(id => !removedFlows.includes(id));
      state.pendingPrune ||= removedFlows.length > 0;
      state.workspace = payload.workspace;
      state.sceneDirty = true;
    }
    const metadataChanges = documentChanges(state.savedCreator, creator);
    if (state.pendingPrune) metadataChanges.prune_removed_steps = true;
    if (Object.keys(metadataChanges).length) {
      const creatorResponse = await fetch("/api/creator", {
        method: "PATCH",
        headers: mutationHeaders(),
        body: JSON.stringify({ revision: state.creatorVersion, changes: metadataChanges })
      });
      payload = parseExact(await creatorResponse.text());
      if (!creatorResponse.ok) {
        if (revision === state.revision) {
          state.diagnostics = payload.diagnostics || [{
            code: "CREATOR_METADATA_SAVE_FAILED",
            message: payload.message || "Creator metadata could not be saved.",
            path: ""
          }];
          state.build = "Not saved";
          render();
        }
        return false;
      }
      state.creatorVersion = payload.creator_revision;
      state.savedCreator = creator;
      state.pendingPrune = false;
    }
    if (revision !== state.revision) {
      return true;
    }
    if (payload?.application) {
      replaceApplication(payload.application);
    }
    state.build = Object.keys(documentChanges(state.builtProject, state.project)).length ? "Not built" : "Built";
    if (state.build === "Built") {
      state.diagnostics = payload?.diagnostics || state.diagnostics;
    }
    state.sceneDirty = true;
    if (state.pathPicker) {
      renderBuildStatus();
      if (!refreshPathPicker()) {
        render();
      }
    } else {
      render();
    }
    if (projectChanged) {
      scheduleApplicationPoll(0);
      scheduleMetricsPoll(0);
    }
    return true;
  } catch (error) {
    if (revision !== state.revision) {
      return false;
    }
    state.build = state.pendingProject ? "Building" : "Not built";
    state.diagnostics = [{
      code: "CREATOR_UNAVAILABLE",
      message: "Creator could not persist this change.",
      path: ""
    }];
    render();
    return false;
  }
}

function documentChanges(before, after) {
  const entries = value => Object.fromEntries(value.map(entry => [entry.id, entry]));
  const connections = value => Object.groupBy(value, link => link.from);
  const changed = (left, right) => Object.fromEntries(
    [...new Set([...Object.keys(left), ...Object.keys(right)])]
      .filter(key => JSON.stringify(left[key]) !== JSON.stringify(right[key]))
      .map(key => [key, Object.hasOwn(right, key) ? right[key] : null])
  );
  return Object.fromEntries([...new Set([...Object.keys(before), ...Object.keys(after)])].flatMap(key => {
    const left = before[key];
    const right = after[key];
    if (["nodes", "groups", "links", "steps"].includes(key)) {
      const index = key === "steps" ? value => value : key === "links" ? connections : entries;
      const changes = changed(index(left), index(right));
      return Object.keys(changes).length ? [[key, changes]] : [];
    }
    return JSON.stringify(left) === JSON.stringify(right) ? [] : [[key, right ?? null]];
  }));
}

function changedIds() {
  const changed = new Set();
  if (state.build === "Built" || !state.builtProject) {
    return changed;
  }
  const builtNodes = new Map(state.builtProject.nodes.map(candidate => [candidate.id, candidate]));
  state.project.nodes.forEach(candidate => {
    if (JSON.stringify(candidate) !== JSON.stringify(builtNodes.get(candidate.id))) {
      changed.add(candidate.id);
    }
  });
  const builtLinks = new Set(state.builtProject.links.map(link => JSON.stringify(link)));
  state.project.links.forEach(link => {
    if (!builtLinks.has(JSON.stringify(link))) {
      changed.add(link.from.split(".")[0]);
      if (link.to !== "end") {
        changed.add(link.to);
      }
    }
  });
  return changed;
}

function availablePaths(operation, context = state.traceContext) {
  const trigger = triggerFor(operation.id);
  if (!trigger) {
    return [];
  }
  const roots = observedRoots(operation, context);
  const merged = new Map();
  roots.forEach(root => {
    const entries = [];
    collectPaths(root, [], entries);
    entries.forEach(entry => {
      const key = JSON.stringify(entry.path);
      const value = merged.get(key) || { path: entry.path, shapes: new Set(), examples: 0 };
      value.shapes.add(entry.shape);
      value.examples++;
      merged.set(key, value);
    });
  });
  const total = Math.max(1, trigger.examples.length);
  const cases = state.traceCases.filter(example => example.trigger === trigger.id);
  const settled = cases.length === total
    && cases.every(example => !["queued", "running"].includes(example.status));
  const paths = [...merged.values()].map(entry => ({
    path: entry.path,
    shape: settled && entry.shapes.size === 1 ? [...entry.shapes][0] : "mixed",
    examples: entry.examples,
    total
  }));
  definitionOf(trigger.use).results
    .filter(result => Object.hasOwn(result, "default"))
    .forEach(result => {
      const path = ["context", result.name];
      const existing = paths.find(entry => samePath(entry.path, path));
      if (existing) {
        existing.examples = total;
      } else {
        paths.push({ path, shape: result.shape, examples: total, total });
      }
    });
  return paths;
}

function observationFor(operation) {
  const built = state.builtProject?.nodes || [];
  let candidate = operation;
  let context = definitionFor(candidate)?.kind === "trigger" ? "trigger" : "input";
  const visited = new Set();
  while (candidate && visited.add(candidate.id)) {
    const index = built.some(deployed => deployed.id === candidate.id)
      ? Number(state.editor.nodes[candidate.id]?.index ?? -1) : -1;
    if (index >= 0) {
      return { node: index, context };
    }
    const incoming = state.project.links.filter(link => link.to === candidate.id);
    if (incoming.length !== 1) {
      return null;
    }
    candidate = node(linkNode(incoming[0]));
    context = definitionFor(candidate)?.kind === "trigger" ? "trigger" : "output";
  }
  return null;
}

function observedRoots(operation, context = state.traceContext) {
  const trigger = triggerFor(operation.id);
  if (!trigger) {
    return [];
  }
  return state.traceCases
    .filter(example => example.trigger === trigger.id)
    .flatMap(example => {
      const value = context === "trigger"
        ? example.initial_context
        : context === "output"
          ? example.projection?.context
          : example.projection?.input_context;
      return plainObject(value) ? [{ context: value }] : [];
    });
}

function collectPaths(value, path, entries) {
  if (path.length) {
    entries.push({ path, shape: valueShape(value) });
  }
  if (plainObject(value)) {
    Object.entries(value).forEach(([key, child]) => collectPaths(child, [...path, key], entries));
  } else if (Array.isArray(value)) {
    value.forEach((child, index) => collectPaths(child, [...path, index], entries));
  }
}


function addableDefinitions() {
  return state.catalog.filter(definition => definition.kind === "step");
}

function iconUrl(icon) {
  if (!icon?.media_type || !icon?.data) {
    return "";
  }
  const key = iconKey(icon);
  if (state.iconUrls.has(key)) {
    return state.iconUrls.get(key);
  }
  try {
    const source = atob(icon.data);
    const bytes = new Uint8Array(source.length);
    for (let index = 0; index < source.length; index++) {
      bytes[index] = source.charCodeAt(index);
    }
    const url = URL.createObjectURL(new Blob([bytes], { type: icon.media_type }));
    state.iconUrls.set(key, url);
    return url;
  } catch (_ignored) {
    return "";
  }
}

function iconKey(icon) {
  return icon.media_type + "\n" + icon.data;
}

function releaseUnusedIconUrls() {
  const live = new Set();
  Object.values(state.world?.scene?.icons || {}).forEach(icon => live.add(iconKey(icon)));
  state.creator.groups.forEach(group => {
    if (group.icon) {
      live.add(iconKey(group.icon));
    }
  });
  Object.values(state.creator.steps).forEach(presentation => {
    if (presentation.icon) {
      live.add(iconKey(presentation.icon));
    }
  });
  state.iconUrls.forEach((url, key) => {
    if (!live.has(key)) {
      URL.revokeObjectURL(url);
      state.iconUrls.delete(key);
    }
  });
}

function triggerNodes() {
  return state.project.nodes.filter(stepKind("trigger"));
}

function availableTriggers() {
  return state.catalog.filter(definition => {
    if (definition.kind !== "trigger" || !definition.examples?.length) {
      return false;
    }
    const used = Number(state.editor.used[definition.id] || 0)
      + state.project.nodes.filter(candidate => candidate.use === definition.id && !state.builtProject.nodes.some(old => old.id === candidate.id)).length;
    const sourceUsed = definition.source && state.catalog.some(candidate =>
      candidate.source?.name === definition.source.name && Number(state.editor.used[candidate.id] || 0) > 0);
    return used < Number(definition.maximum_instances) && !sourceUsed;
  });
}

function outcomes(candidate) {
  return [
    ...(definitionFor(candidate)?.outcomes || []),
    ...authoredOutcomes(candidate).map(item => item.outcome)
  ];
}

function displayOutcomes(candidate) {
  const authored = authoredOutcomes(candidate).map(item => item.outcome);
  const declared = definitionFor(candidate)?.outcomes || [];
  return authored.length ? [...authored, ...declared] : declared;
}

function authoredOutcomes(candidate) {
  const definition = definitionFor(candidate);
  if (!candidate || !definition) {
    return [];
  }
  const input = authoredOutcomeInput(definition);
  const configured = input && candidate.inputs?.[input.name];
  return Array.isArray(configured)
    ? configured.filter(item => typeof item.outcome === "string" && item.outcome)
    : [];
}

function authoredOutcomeInput(definition) {
  return (definition?.inputs || [])
    .find(field => field.type === "candidates" && field.authored_outcomes);
}

function outcomeLabel(candidate, outcome) {
  return stepPresentation(candidate?.id).outcomes?.[outcome] || inputLabel(outcome);
}

function outcomeTarget(candidate, outcome) {
  let destination;
  for (const link of state.project.links) {
    if (link.from !== candidate.id + "." + outcome) {
      continue;
    }
    if (destination !== undefined) {
      return undefined;
    }
    destination = link.to;
  }
  return destination;
}

function outcomeDestinations(candidate, outcome) {
  return state.project.links
    .filter(link => link.from === candidate.id + "." + outcome)
    .map(link => link.to);
}

function insertionAllowed(candidate, outcome) {
  if (!candidate || !outcomes(candidate).includes(outcome)) {
    return false;
  }
  const destinations = outcomeDestinations(candidate, outcome);
  if (destinations.length !== 1) {
    return false;
  }
  const destination = destinations[0];
  const target = node(destination);
  return destination === "end" || Boolean(target
    && definitionFor(target)?.kind === "step"
    && state.project.links.filter(link => link.to === destination).length === 1);
}

function reachableSteps(trigger) {
  const values = [];
  const pending = [outcomeTarget(trigger, primaryOutcome(trigger))];
  const seen = new Set();
  for (let index = 0; index < pending.length; index++) {
    const id = pending[index];
    if (!id || id === "end" || seen.has(id)) {
      continue;
    }
    seen.add(id);
    const operation = node(id);
    if (!operation) {
      continue;
    }
    values.push(operation);
    outcomes(operation).forEach(outcome => pending.push(outcomeTarget(operation, outcome)));
  }
  return values;
}

function groupForStep(id) {
  const groupId = stepPresentation(id).group;
  return state.creator.groups.find(group => group.id === groupId) || null;
}

function groupName(group) {
  return group?.name || "Group";
}

function groupInventory() {
  return new Map(Object.entries(state.editor.groups).map(([id, counts]) => [id, Number(counts.steps)]));
}

function groupPages() {
  const offset = Number(state.editor.offset);
  const more = offset + 64 < Number(state.editor.group_matches);
  return offset || more ? `<div class="inspector-actions">
    <button class="button" data-group-page="${Math.max(0, offset - 64)}" ${offset ? "" : "disabled"}>Previous</button>
    <button class="button" data-group-page="${offset + 64}" ${more ? "" : "disabled"}>Next</button>
  </div>` : "";
}

function stepPresentation(id) {
  return state.creator.steps[id] || {};
}

function setOutcomeLabel(id, outcome, label) {
  const presentation = state.creator.steps[id] || {};
  const outcomes = presentation.outcomes || {};
  if (label === undefined) {
    delete outcomes[outcome];
  } else {
    outcomes[outcome] = label;
  }
  if (Object.keys(outcomes).length) {
    presentation.outcomes = outcomes;
    state.creator.steps[id] = presentation;
  } else {
    delete presentation.outcomes;
    if (!Object.keys(presentation).length) {
      delete state.creator.steps[id];
    }
  }
}

function reachable(start) {
  const result = new Set([start]);
  const pending = [start];
  for (let index = 0; index < pending.length; index++) {
    const current = pending[index];
    const next = state.project.links
      .filter(link => linkNode(link) === current)
      .map(link => link.to)
      .filter(id => id !== "end");
    next.forEach(id => {
      if (!result.has(id)) {
        result.add(id);
        pending.push(id);
      }
    });
  }
  return result;
}

function flowTrigger(operation) {
  if (!operation) {
    return null;
  }
  return node(state.editor.nodes[operation.id]?.trigger)
    || triggerNodes().find(trigger => reachable(trigger.id).has(operation.id)) || null;
}

async function openGroupManager(id = "") {
  const selected = selectedOperation();
  state.managedGroup = id || state.regionSelection?.group || groupForStep(selected?.id)?.id || state.creator.groups[0]?.id || "";
  state.groupQuery = "";
  state.groupPicker = null;
  state.inspectorMode = "groups";
  if (await loadEditor(state.selection.id, state.managedGroup, "") !== "loaded") return;
  showInspector(true);
  render();
}

function createGroup() {
  let index = state.creator.groups.length + 1;
  const names = new Set(state.creator.groups.map(group => groupName(group)));
  while (names.has("Group " + index)) {
    index++;
  }
  const group = { id: opaqueId("group"), name: "Group " + index };
  state.creator.groups.push(group);
  state.managedGroup = group.id;
  creatorDirty();
}

function setStepGroup(stepId, groupId, save = true) {
  const operation = node(stepId);
  if (!operation || definitionFor(operation)?.kind !== "step"
      || (groupId && !state.creator.groups.some(group => group.id === groupId))) {
    return false;
  }
  const presentation = state.creator.steps[stepId] || {};
  if (groupId) {
    presentation.group = groupId;
    state.creator.steps[stepId] = presentation;
  } else {
    delete presentation.group;
    if (Object.keys(presentation).length) {
      state.creator.steps[stepId] = presentation;
    } else {
      delete state.creator.steps[stepId];
    }
  }
  state.groupPicker = null;
  if (save) {
    state.revealNode = stepId;
    creatorDirty();
  }
  return true;
}

function deleteGroup(id) {
  if (!state.creator.groups.some(group => group.id === id)) {
    return;
  }
  Object.keys(state.creator.steps).forEach(stepId => {
    if (state.creator.steps[stepId].group === id) {
      setStepGroup(stepId, "", false);
    }
  });
  state.creator.groups = state.creator.groups.filter(group => group.id !== id);
  if (state.regionSelection?.group === id) state.regionSelection = null;
  state.managedGroup = state.creator.groups[0]?.id || "";
  state.revealNode = state.selection.id;
  creatorDirty();
}

function removeCreatorReferences(ids) {
  ids.forEach(id => delete state.creator.steps[id]);
}

function linkNode(link) {
  return link.from.substring(0, link.from.lastIndexOf("."));
}

function triggerFor(id) {
  const candidate = node(id);
  if (candidate && definitionOf(candidate.use)?.kind === "trigger") {
    return candidate;
  }
  return flowTrigger(candidate);
}

function selectedOperation() {
  return node(state.selection.id);
}

function selectStep(id) {
  clearPreview();
  resetMetrics();
  state.exampleDraft = null;
  state.selection = { type: "step", id };
  const trigger = triggerFor(id);
  if (trigger && state.exampleTrigger !== trigger.id) {
    state.exampleTrigger = trigger.id;
    state.exampleIndex = 0;
  }
  state.inspectorMode = "inspect";
  state.pathPicker = null;
  clearInputQueries();
  render();
  scheduleMetricsPoll(0);
  if (definitionFor(node(id))?.kind === "step") {
    requestSelectedTrace();
  }
}

function presentationOwner(target) {
  const [kind, id] = target.split(":", 2);
  if (kind === "step" && node(id)) {
    state.creator.steps[id] = state.creator.steps[id] || {};
    return state.creator.steps[id];
  }
  if (kind === "group") {
    return state.creator.groups.find(group => group.id === id) || null;
  }
  return null;
}

function updatePresentation(target, field, source) {
  if (!["name", "color", "shape", "aspect", "roundness"].includes(field)) {
    return;
  }
  const trimmed = source.trim();
  const value = field === "color" && /^#[0-9a-fA-F]{6}$/.test(trimmed)
    ? trimmed.toUpperCase()
    : trimmed;
  const defaults = RailixWorld.appearance;
  const parsed = ["aspect", "roundness"].includes(field) && value !== "" ? Number(value) : value;
  setPresentation(target, field, parsed === "" || parsed === defaults[field] ? undefined : parsed);
}

function setPresentation(target, field, value) {
  const owner = presentationOwner(target);
  if (!owner || !["name", "color", "icon", "shape", "aspect", "roundness"].includes(field)) {
    return;
  }
  if (value !== undefined) {
    owner[field] = value;
  } else {
    delete owner[field];
  }
  const [kind, id] = target.split(":", 2);
  if (kind === "step" && !Object.keys(owner).length) {
    delete state.creator.steps[id];
  }
  creatorDirty();
}

function node(id) {
  return state.project.nodes.find(candidate => candidate.id === id);
}

function definitionOf(id) {
  return state.definitions.get(id);
}

function stepKind(kind) {
  return candidate => definitionOf(candidate.use)?.kind === kind;
}

function definitionFor(candidate) {
  if (candidate?.primary_outcome) {
    return candidate;
  }
  return definitionOf(candidate?.use);
}

function primaryOutcome(candidate) {
  return definitionFor(candidate)?.primary_outcome || "";
}

function generatedName() {
  const seed = hash(state.project.id + ":" + state.project.nodes.length);
  const used = new Set(Object.values(state.creator.steps).map(presentation => presentation.name));
  for (let offset = 0; offset < adjectives.length * nouns.length; offset++) {
    const candidate = adjectives[(seed + offset) % adjectives.length]
      + "-" + nouns[(Math.floor(seed / adjectives.length) + offset) % nouns.length];
    if (!used.has(candidate)) {
      return candidate;
    }
  }
  return "flow-" + crypto.randomUUID().slice(0, 8);
}

function opaqueId(prefix) {
  return prefix + "-" + crypto.randomUUID();
}

function hash(value) {
  let result = 0;
  for (let index = 0; index < value.length; index++) {
    result = Math.imul(31, result) + value.charCodeAt(index) | 0;
  }
  return Math.abs(result);
}

function stepName(definition) {
  if (!definition) {
    return "Unknown Step";
  }
  return definition.display_name;
}

function definitionMatchesQuery(definition, query) {
  return !query || [definition.id, stepName(definition), ...(definition.search_terms || [])]
    .some(value => value.toLowerCase().includes(query));
}

function pathCrumbs(path) {
  return path.map(part => `<span>${html(pathPart(part))}</span>`).join("<i>&rsaquo;</i>");
}

function pathPart(part) {
  return typeof part === "number" ? "[" + part + "]" : part;
}

function programPath(locator) {
  return displayPath(locator[0] === "inputs" ? locator.slice(1) : locator);
}

function displayPath(path) {
  return path.reduce((value, part) => typeof part === "number"
    ? value + "[" + part + "]"
    : value + (value ? "." : "") + part, "");
}

function valueShape(value) {
  if (exactNumber(value)) return "number";
  if (value === null) return "null";
  if (Array.isArray(value)) return "array";
  if (plainObject(value)) return "object";
  return typeof value === "number" ? "number" : typeof value;
}

function samePath(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function plainObject(value) {
  return value !== null
    && typeof value === "object"
    && !Array.isArray(value)
    && !exactNumber(value);
}

function count(value, noun) {
  return value + " " + noun + (value === 1 ? "" : "s");
}

function workspaceCount(field, fallback) {
  const value = Number(state.workspace[field]);
  return Number.isSafeInteger(value) && value >= 0 ? value : fallback;
}

function clone(value) {
  return parseExact(JSON.stringify(value));
}

function exactJsonSupported() {
  if (typeof JSON.rawJSON !== "function" || typeof JSON.isRawJSON !== "function") {
    return false;
  }
  let source = "";
  JSON.parse("0.1", (_key, value, context) => {
    if (typeof value === "number") {
      source = context?.source || "";
    }
    return value;
  });
  return source === "0.1" && JSON.stringify(JSON.rawJSON(source)) === source;
}

function parseExact(source) {
  return JSON.parse(source, (_key, value, context) => {
    if (typeof value !== "number"
        || (Number.isSafeInteger(value) && String(value) === context.source)) {
      return value;
    }
    return JSON.rawJSON(context.source);
  });
}

function exactNumber(value) {
  return JSON.isRawJSON(value);
}

function numberText(value) {
  return exactNumber(value) ? value.rawJSON : value;
}

function html(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function showInspector(open) {
  document.querySelector("#inspector").hidden = !open;
  if (!open) document.querySelector("#graph").focus({ preventScroll: true });
}

function clearSelection() {
  state.editorRequest++;
  state.editorController?.abort();
  state.world?.cancelFocus();
  state.selection = {type: "none", id: ""};
  state.regionSelection = null;
  state.revealNode = "";
  state.managedGroup = "";
  state.inspectorMode = "inspect";
  state.picker = state.groupPicker = state.pathPicker = null;
  clearPreview();
  resetMetrics(true);
  showInspector(false);
  render();
}

document.addEventListener("click", event => {
  const groupPointer = state.groupPointer;
  state.groupPointer = null;
  // An Inspector opening beneath the pointer must not steal the second group click.
  if (event.detail === 2 && groupPointer && groupPointer.id === state.regionSelection?.id
    && Math.hypot(event.clientX - groupPointer.x, event.clientY - groupPointer.y) <= 4) {
    event.preventDefault();
    focusRegion(groupPointer.id);
    return;
  }
  const target = event.target;
  const tab = target.closest('[data-settings-tab]');
  if (tab) {
    selectSettingsTab(tab.dataset.settingsTab);
    event.preventDefault();
    return;
  }
  const editTrigger = target.closest('[data-edit-trigger]');
  if (editTrigger) {
    void selectWorldNode(editTrigger.dataset.editTrigger).then(selected => {
      if (selected) { state.inspectorMode = 'examples'; render(); showInspector(true); }
    });
    return;
  }
  if (target.closest('button:not(:disabled), summary') && !target.closest('#world-labels, #audio-panel')) {
    state.audio?.action('click');
  }
  if (target.closest(".build-indicator")) {
    document.querySelector('#application-status').innerHTML = buildDetails();
    return;
  }
  const exampleTrigger = target.closest("[data-locate-trigger]");
  if (exampleTrigger) {
    const id = exampleTrigger.dataset.locateTrigger;
    void selectWorldNode(id).then(selected => { if (selected) state.world.focus(id); });
    return;
  }
  if (target.closest("#dock-focus")) {
    showInspector(false);
    void state.world?.focus(state.regionSelection?.id || state.selection.id);
    return;
  }
  if (target.closest("#enter-region") && state.regionSelection) {
    focusRegion(state.regionSelection.id);
    return;
  }
  const regionDelete = target.closest("[data-delete-region]");
  if (regionDelete) {
    deleteGroup(regionDelete.dataset.deleteRegion);
    return;
  }
  const panel = target.closest("[data-open-panel]");
  if (panel) {
    state.inspectorMode = panel.dataset.openPanel;
    render();
    showInspector(true);
    document.querySelector("#close-inspector").focus({ preventScroll: true });
    return;
  }
  if (target.closest("#close-inspector")) {
    showInspector(false);
    return;
  }
  if (target.closest('[data-leave-group]')) {
    showInspector(false);
    state.world?.leave();
    return;
  }
  const inspectorMode = target.closest("[data-inspector-mode]");
  if (inspectorMode) {
    if (inspectorMode.dataset.inspectorMode === 'groups') { void openGroupManager(); return; }
    resetMetrics();
    state.inspectorMode = inspectorMode.dataset.inspectorMode;
    render();
    scheduleMetricsPoll(0);
    return;
  }
  if (target.closest("#new-group")) {
    createGroup();
    return;
  }
  const managed = target.closest("[data-manage-group]");
  if (managed) {
    state.managedGroup = managed.dataset.manageGroup;
    render();
    return;
  }
  const groupPage = target.closest("[data-group-page]");
  if (groupPage) {
    void loadEditor(state.selection.id, state.managedGroup, state.groupPicker?.query || state.groupQuery,
      Number(groupPage.dataset.groupPage)).then(loaded => { if (loaded === "loaded") render(); });
    return;
  }
  if (target.closest("#choose-group") && state.selection.type === "step") {
    state.groupPicker = { step: state.selection.id, query: "" };
    render();
    document.querySelector("#group-picker-search")?.focus();
    return;
  }
  const assignGroup = target.closest("[data-assign-group]");
  if (assignGroup && state.groupPicker) {
    setStepGroup(state.groupPicker.step, assignGroup.dataset.assignGroup);
    return;
  }
  if (target.closest("#close-group-picker") || target.matches(".group-picker-backdrop")) {
    state.groupPicker = null;
    render();
    return;
  }
  if (target.closest("#focus-group")) {
    focusGroup(state.managedGroup);
    return;
  }
  if (target.closest("#delete-group")) {
    deleteGroup(state.managedGroup);
    return;
  }
  if (target.closest("#add-trigger") || target.closest("[data-open-picker='trigger']")) {
    openPicker("trigger", "app");
    return;
  }
  if (target.closest("#add-next-step")) {
    openPicker("step", state.selection.id);
    return;
  }
  const addOutcome = target.closest("[data-add-outcome]");
  if (addOutcome) {
    openPicker("step", state.selection.id, addOutcome.dataset.addOutcome);
    return;
  }
  const addStep = target.closest("[data-add-step]");
  if (addStep) {
    addCatalogStep(addStep.dataset.addStep);
    return;
  }
  const resetPresentation = target.closest("[data-reset-presentation]");
  if (resetPresentation) {
    setPresentation(
      resetPresentation.dataset.presentationTarget,
      resetPresentation.dataset.resetPresentation,
      undefined
    );
    return;
  }
  if (target.matches(".picker-backdrop") || target.closest("[data-close-picker]")) {
    state.picker = null;
    render();
    return;
  }
  const pathDraft = target.closest("[data-path-draft-json]");
  if (pathDraft) {
    setPathDraft(pathDraft.dataset.pathDraftJson);
    return;
  }
  if (target.closest("#append-path-field")) {
    appendPathField(document.querySelector("#new-path-field").value);
    return;
  }
  if (target.closest("#append-path-index")) {
    appendPathIndex(document.querySelector("#new-path-index").value);
    return;
  }
  if (target.closest("#apply-path")) {
    applyPath();
    return;
  }
  if (target.closest("#cancel-path")) {
    cancelPath();
    return;
  }
  const openPath = target.closest("[data-open-path]");
  if (openPath) {
    openPathPicker(openPath.dataset.openPath, openPath.dataset.inputMeta);
    return;
  }
  const resetPathButton = target.closest("[data-reset-path]");
  if (resetPathButton) {
    resetPath(
      parseToken(resetPathButton.dataset.resetPath),
      parseToken(resetPathButton.dataset.inputMeta)
    );
    return;
  }
  const emptyJson = target.closest("[data-set-empty-json]");
  if (emptyJson) {
    setJsonValue(
      parseToken(emptyJson.dataset.setEmptyJson),
      parseToken(emptyJson.dataset.inputMeta),
      ""
    );
    return;
  }
  const addCandidateButton = target.closest("[data-add-candidate]");
  if (addCandidateButton) {
    addCandidate(
      parseToken(addCandidateButton.dataset.candidateLocator),
      parseToken(addCandidateButton.dataset.inputMeta),
      addCandidateButton.dataset.addCandidate
    );
    return;
  }
  const addMatcherGroupButton = target.closest("[data-add-matcher-group]");
  if (addMatcherGroupButton) {
    addMatcherGroup(
      parseToken(addMatcherGroupButton.dataset.matcherGroupsLocator),
      parseToken(addMatcherGroupButton.dataset.inputMeta),
      addMatcherGroupButton.dataset.addMatcherGroup
    );
    return;
  }
  const removeMatcherGroupButton = target.closest("[data-remove-matcher-group]");
  if (removeMatcherGroupButton) {
    removeListItem(
      parseToken(removeMatcherGroupButton.dataset.matcherGroupsLocator),
      Number(removeMatcherGroupButton.dataset.removeMatcherGroup)
    );
    return;
  }
  const moveMatcherGroupButton = target.closest("[data-move-matcher-group]");
  if (moveMatcherGroupButton) {
    moveListItem(
      parseToken(moveMatcherGroupButton.dataset.matcherGroupsLocator),
      Number(moveMatcherGroupButton.dataset.moveMatcherGroup),
      Number(moveMatcherGroupButton.dataset.direction)
    );
    return;
  }
  const removeCandidateButton = target.closest("[data-remove-candidate]");
  if (removeCandidateButton) {
    removeListItem(
      parseToken(removeCandidateButton.dataset.candidateLocator),
      Number(removeCandidateButton.dataset.removeCandidate),
      parseToken(removeCandidateButton.dataset.inputMeta)
    );
    return;
  }
  const moveCandidateButton = target.closest("[data-move-candidate]");
  if (moveCandidateButton) {
    moveListItem(
      parseToken(moveCandidateButton.dataset.candidateLocator),
      Number(moveCandidateButton.dataset.moveCandidate),
      Number(moveCandidateButton.dataset.direction)
    );
    return;
  }
  const addPredicateButton = target.closest("[data-add-predicate]");
  if (addPredicateButton) {
    addPredicate(
      parseToken(addPredicateButton.dataset.conditionLocator),
      addPredicateButton.dataset.addPredicate
    );
    return;
  }
  const movePredicateButton = target.closest("[data-move-predicate]");
  if (movePredicateButton) {
    movePredicate(
      parseToken(movePredicateButton.dataset.conditionLocator),
      Number(movePredicateButton.dataset.movePredicate),
      Number(movePredicateButton.dataset.direction)
    );
    return;
  }
  const removePredicateButton = target.closest("[data-remove-predicate]");
  if (removePredicateButton) {
    removePredicate(
      parseToken(removePredicateButton.dataset.conditionLocator),
      Number(removePredicateButton.dataset.removePredicate)
    );
    return;
  }
  const addNestedButton = target.closest("[data-add-nested]");
  if (addNestedButton) {
    addNested(parseToken(addNestedButton.dataset.programLocator), addNestedButton.dataset.addNested);
    return;
  }
  const removeNestedButton = target.closest("[data-remove-nested]");
  if (removeNestedButton) {
    removeNested(
      parseToken(removeNestedButton.dataset.programLocator),
      Number(removeNestedButton.dataset.removeNested)
    );
    return;
  }
  const moveNestedButton = target.closest("[data-move-nested]");
  if (moveNestedButton) {
    moveNested(
      parseToken(moveNestedButton.dataset.programLocator),
      Number(moveNestedButton.dataset.moveNested),
      Number(moveNestedButton.dataset.direction)
    );
    return;
  }
  if (target.closest("#delete-step")) {
    deleteSelection();
    return;
  }
  const example = target.closest("[data-select-example]");
  if (example) {
    selectExample(Number(example.dataset.selectExample));
    return;
  }
  if (target.closest("#add-example")) {
    addExample();
    return;
  }
  if (target.closest("#delete-example")) {
    deleteExample();
    return;
  }
});

document.addEventListener("input", event => {
  if (event.target.id === "step-search" && state.picker) {
    state.picker.query = event.target.value;
    state.world?.preview(null);
    document.querySelector("#step-options").innerHTML = pickerOptions();
  } else if (event.target.id === "group-search") {
    state.groupQuery = event.target.value;
    void loadEditor(state.selection.id).then(loaded => {
      const list = document.querySelector("#group-results");
      if (loaded === "loaded" && list) list.innerHTML = groupListMarkup(state.creator.groups.filter(group =>
        groupName(group).toLowerCase().includes(state.groupQuery.toLowerCase())), state.managedGroup);
    });
  } else if (event.target.id === "group-picker-search" && state.groupPicker) {
    state.groupPicker.query = event.target.value;
    void loadEditor(state.selection.id, state.managedGroup, state.groupPicker.query).then(loaded => {
      const list = document.querySelector("#group-picker-options");
      if (loaded === "loaded" && list) list.innerHTML = groupPickerOptions();
    });
  } else if (event.target.matches("[data-step-query]")) {
    state.stepQueries[event.target.dataset.stepQuery] = event.target.value;
    const locator = parseToken(event.target.dataset.stepQuery);
    const input = parseToken(event.target.dataset.inputMeta);
    const scope = parseToken(event.target.dataset.inputScope);
    document.querySelector(`[data-step-options='${event.target.dataset.stepQuery}']`).innerHTML =
      nestedOptions(selectedOperation(), input, locator, scope.inputs, scope.base);
  } else if (event.target.matches("[data-candidate-query]")) {
    state.candidateQueries[event.target.dataset.candidateQuery] = event.target.value;
    const input = parseToken(event.target.dataset.inputMeta);
    const locator = parseToken(event.target.dataset.candidateQuery);
    document.querySelector(`[data-candidate-options='${event.target.dataset.candidateQuery}']`).innerHTML =
      candidateOptions(input, locator);
  } else if (event.target.matches("[data-matcher-group-query]")) {
    const locator = parseToken(event.target.dataset.matcherGroupQuery);
    state.candidateQueries[locatorToken([...locator, "new-group"])] = event.target.value;
    const input = parseToken(event.target.dataset.inputMeta);
    document.querySelector(`[data-matcher-group-options='${event.target.dataset.matcherGroupQuery}']`).innerHTML =
      matcherGroupOptions(input, locator);
  } else if (event.target.matches("[data-predicate-query]")) {
    const locator = parseToken(event.target.dataset.predicateQuery);
    state.candidateQueries[locatorToken([...locator, "when", "new-predicate"])] = event.target.value;
    const input = parseToken(event.target.dataset.inputMeta);
    const scope = parseToken(event.target.dataset.inputScope);
    document.querySelector(`[data-predicate-options='${event.target.dataset.predicateQuery}']`).innerHTML =
      predicateOptionsFor(selectedOperation(), input, locator, scope.inputs, scope.base);
  } else if (event.target.id === "new-path-field") {
    state.pathField = event.target.value;
    document.querySelector("#append-path-field").disabled =
      !validPathField(state.pathField, state.pathDraft);
  } else if (event.target.id === "new-path-index") {
    state.pathIndex = event.target.value;
    document.querySelector("#append-path-index").disabled =
      !validPathIndex(state.pathIndex);
  }
});

document.addEventListener("change", event => {
  const comparison = event.target.closest('[data-replace-comparison]');
  if (comparison) {
    const steps = programAt(selectedOperation(), parseToken(comparison.dataset.replaceComparison));
    const definition = definitionOf(comparison.value);
    if (!steps || steps.length !== 1 || !definition) return;
    const previous = definitionOf(steps[0].use), inputs = defaultInputs(definition.inputs);
    for (const input of definition.inputs) if (previous.inputs.some(field => field.name === input.name
        && field.type === input.type && field.shape === input.shape) && Object.hasOwn(steps[0].inputs, input.name))
      inputs[input.name] = steps[0].inputs[input.name];
    steps[0] = {use: definition.id, inputs};
    dirty();
    return;
  }
  const target = event.target;
  if (target.id === "theme-select") {
    changeSettings({theme:target.value, theme_variant:''});
    reflectTheme();
    void applyTheme();
    return;
  }
  if (target.id === 'theme-variant') { changeSettings({theme_variant:target.value}); void applyTheme(); return; }
  if (target.id === 'reduced-motion') { changeSettings({reduced_motion:target.checked}); return; }
  if (target.id === "dock-example") {
    selectExample(Number(target.value), state.hoverTrigger?.id === target.dataset.trigger ? state.hoverTrigger : node(target.dataset.trigger));
    return;
  }
  if (target.matches("[data-input-json]")) {
    setJsonValue(
      parseToken(target.dataset.inputJson),
      parseToken(target.dataset.inputMeta),
      target.value
    );
  } else if (target.matches("[data-toggle-input]")) {
    toggleInput(
      parseToken(target.dataset.toggleInput),
      parseToken(target.dataset.inputMeta),
      target.checked
    );
  } else if (target.matches("[data-input-option]")) {
    selectOption(
      parseToken(target.dataset.inputOption),
      parseToken(target.dataset.inputMeta),
      target.value
    );
  } else if (target.matches("[data-candidate-option]")) {
    selectCandidate(
      parseToken(target.dataset.candidateOption),
      parseToken(target.dataset.inputMeta),
      Number(target.dataset.candidateIndex),
      target.value
    );
  } else if (target.matches("[data-candidate-label]")) {
    updateCandidateLabel(
      parseToken(target.dataset.candidateLabel),
      Number(target.dataset.candidateIndex),
      target.value
    );
  } else if (target.matches("[data-node-metrics]")) {
    const operation = selectedOperation();
    if (operation) {
      if (target.checked) {
        delete operation.metrics;
      } else {
        operation.metrics = false;
      }
      dirty();
    }
  } else if (target.id === "project-id") {
    state.project.id = target.value;
    dirty();
  } else if (target.id === "example-name") {
    updateExampleName(target.value);
  } else if (target.id === "example-payload") {
    updateExample("payload", target.value);
  } else if (target.id === "example-context") {
    updateExample("context", target.value);
  } else if (target.matches("[data-color-picker]")) {
    updatePresentation(target.dataset.colorPicker, "color", target.value);
  } else if (target.matches("[data-presentation]")) {
    if (target.type === "number" && !target.validity.valid) {
      target.reportValidity();
      return;
    }
    updatePresentation(target.dataset.presentationTarget, target.dataset.presentation, target.value);
  } else if (target.id === "group-boundary") {
    const group = state.creator.groups.find(candidate => candidate.id === state.managedGroup);
    if (group) {
      group.boundary = target.value === "solid" ? undefined : target.value;
      if (group.boundary === undefined) {
        delete group.boundary;
      }
      creatorDirty();
    }
  }
});

document.addEventListener("keydown", event => {
  if (event.isComposing || event.defaultPrevented || event.ctrlKey || event.metaKey || event.altKey) return;
  const optionList = event.target.matches('input[type="search"]') ? event.target.nextElementSibling
    : event.target.closest('#step-options,[data-step-options],[data-predicate-options]');
  const search = optionList?.previousElementSibling;
  if (search?.matches('input[type="search"]') && ['ArrowDown','ArrowUp','Escape'].includes(event.key)) {
    const options = [...optionList.querySelectorAll('button:not(:disabled)')].filter(button => button.getClientRects().length);
    const index = options.indexOf(event.target);
    const target = event.key === 'Escape' ? (index >= 0 ? search : null)
      : event.key === 'ArrowDown' ? options[Math.min(index+1,options.length-1)] : index >= 0 ? options[index-1] || search : null;
    if (target) { event.preventDefault(); target.focus(); return; }
  }
  const editing = event.target.isContentEditable || event.target.closest('input,textarea,select,[role="textbox"]');
  if (event.key !== "Escape") {
    if (editing || event.target.closest('button:not([data-world-id]),[data-world-overlay],#inspector,#overlay')) return;
    const key=event.key.toLowerCase();
    if (key==='e' && (state.regionSelection || state.selection.id)) {
      event.preventDefault(); showInspector(true);
    } else if (key==='enter' && state.regionSelection) {
      event.preventDefault(); if (!event.repeat) focusRegion(state.regionSelection.id);
    } else if (key==='f' && (state.regionSelection || state.selection.id)) {
      event.preventDefault(); if (!event.repeat) void state.world?.focus(state.regionSelection?.id || state.selection.id);
    }
    return;
  }
  if (document.querySelector(':popover-open')) return;
  if (state.picker || state.groupPicker) {
    state.picker = null;
    state.groupPicker = null;
    render();
    return;
  }
  if (state.pathPicker) {
    closePathPicker();
    render();
    return;
  }
  if (editing) return;
  if (!document.querySelector("#inspector").hidden) showInspector(false);
  else clearSelection();
});

document.addEventListener("visibilitychange", () => {
  clearTimeout(state.groupMeanTimer);
  state.groupMeanController?.abort();
  scheduleWorldObservations();
  if (!document.hidden) {
    scheduleMetricsPoll(0);
  }
});

document.querySelector("#zoom-out").addEventListener("click", () => state.world?.zoom(1 / 1.4));
document.querySelector('#leave-group').addEventListener('click', () => state.world?.leave());
document.querySelector('#theme-download').addEventListener('click', () => void downloadTheme());
document.querySelector('#toggle-effects').addEventListener('click', () => {
  const effects = state.settings.effects !== true;
  state.audio?.applyPreferences({...state.settings, effects});
  void state.audio?.effects(effects);
  changeSettings({effects});
});
document.querySelector('#toggle-music').addEventListener('click', () => void state.audio?.toggleMusic());
document.querySelector('#audio-panel').addEventListener('toggle', event => { if (event.newState === 'open') void refreshSettings(); });
document.querySelector('#audio-panel').addEventListener('toggle', event => { if (event.newState === 'open') selectSettingsTab('appearance'); });
document.addEventListener('pointerdown', () => {
  if (state.musicGesture || state.settings.music_enabled === false || state.settings.music_volume === 0) return;
  state.musicGesture = true;
  void state.audio?.play();
}, {once:true, capture:true});
document.querySelector('#trigger-example').addEventListener('pointerenter', () => clearTimeout(state.hoverTimer));
document.querySelector('#trigger-example').addEventListener('pointerleave', () => hoverTrigger(''));
document.querySelector('#trigger-example').addEventListener('focusin', () => clearTimeout(state.hoverTimer));
document.querySelector('#trigger-example').addEventListener('focusout', () => hoverTrigger(''));
document.querySelector("#zoom-in").addEventListener("click", () => state.world?.zoom(1.4));
document.querySelector("#zoom-fit").addEventListener("click", () => { showInspector(false); state.world?.focus('app', .5); });
window.addEventListener("beforeunload", () => {
  state.audio?.dispose();
  clearTimeout(state.groupMeanTimer);
  state.groupMeanController?.abort();
  state.themeController?.abort();
  state.hoverController?.abort();
  clearTimeout(state.hoverTimer);
  clearTimeout(state.settingsTimer);
  state.world?.dispose();
  clearTimeout(state.applicationPollTimer);
  clearTimeout(state.metricsPollTimer);
  clearTimeout(state.observationTimer);
  state.observationController?.abort();
  state.metricsController?.abort();
  state.traceController?.abort();
  state.editorController?.abort();
  state.iconUrls.forEach(url => URL.revokeObjectURL(url));
  state.iconUrls.clear();
});
