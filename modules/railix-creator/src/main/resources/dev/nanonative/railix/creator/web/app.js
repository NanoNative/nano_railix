"use strict";

const failedRunStates = new Set(['failed','rejected','cancelled','timed-out']);
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
  history: {undo:false, redo:false},
  selectedSteps: new Set(),
  actionPending: false,
  editorRequest: 0,
  editorController: null,
  removedFlows: [],
  writePromise: Promise.resolve(),
  creatorVersion: 0,
  creator: { format: 2, groups: [], steps: {} },
  catalog: [],
  definitions: new Map(),
  iconUrls: new Map(),
  icons: null,
  iconController: null,
  iconError: '',
  themes: [],
  settings: {},
  settingsRevision: null,
  settingsVersion: 0,
  savedSettingsVersion: 0,
  settingsTimer: 0,
  settingsWriting: false,
  themesDirectory: "",
  themeError: "",
  themeController: null,
  application: {},
  audio: null,
  workspace: {},
  selection: { type: "app", id: "app" },
  regionSelection: null,
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
  exampleScope: null,
  exampleDraft: null,
  exampleIds: new Map(),
  exampleInventoryKey: "",
  exampleFailures: [],
  attentionOffset: 0,
  attentionRequest: null,
  attentionError: "",
  exampleCoverageKey: "",
  revision: 0,
  saveTimer: 0,
  writeActive: false,
  pendingWrite: null,
  saveFailure: null,
  projectImport: {reader:null, preview:null, request:null, reload:false},
  runResult: "",
  preview: null,
  previewCases: [],
  traceCases: [],
  traceCasesKey: "",
  traceCasesPid: 0,
  traceContext: "",
  traceSummary: null,
  traceSummaryKey: "",
  traceReadError: null,
  traceStep: null,
  traceKey: "",
  traceController: null,
  regionTrace: null,
  traceChanges: null,
  evidenceView: null,
  coverageBytes: new Uint8Array(),
  optionsPending: false,
  applicationPollTimer: 0,
  applicationRefreshing: false,
  applicationController: null,
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
    document.querySelector('#workbench-actions').append(document.querySelector('.topbar'));
    if (!exactJsonSupported()) {
      throw new Error("Browser does not support exact JSON numbers.");
    }
    const [projectResponse, catalogResponse] = await Promise.all([
      fetch("/api/editor"),
      fetch("/api/catalog")
    ]).catch(() => {
      throw new Error("Check that the Creator is running, then retry.", {cause:"connection"});
    });
    if (projectResponse.status === 401 || catalogResponse.status === 401) {
      throw new Error("This link cannot access this session. Use the current link shown by the running Creator.", {cause:"session"});
    }
    if (!projectResponse.ok || !catalogResponse.ok) {
      throw new Error("Creator could not open the project.");
    }
    const [project, catalog] = await Promise.all([
      projectResponse.text().then(parseExact),
      catalogResponse.text().then(parseExact)
    ]);
    state.project = project.project;
    state.editor = project.editor;
    state.history = project.history;
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
      editingBusy: () => state.actionPending,
      outputPorts: item => state.editor.full.includes(item.id) ? displayOutcomes(node(item.id)).map(outcome =>
        ({id:item.id+'.'+outcome,name:outcomeLabel(node(item.id),outcome)})) : [],
      connect: (from,to) => { void editorAction({kind:'connect',from,to}).then(result => { if (!result) state.world.connection(null); }); },
      insert: openBeltPicker,
      deselect: clearSelection,
      selectGroup: selectWorldGroup,
      appearance: worldAppearance,
      selectedId: () => state.regionSelection?.id || state.selection.id,
      selectedStation: () => !state.regionSelection && ['step','trigger'].includes(state.selection.type) ? selectedOperation() : null,
      linkAppearance: worldLinkAppearance,
      powered: () => state.application.state === "running",
      motionActive: worldMotionActive,
      reducedMotion: () => state.settings.reduced_motion === true,
      parentName: () => state.project?.id || 'System',
      onGroup: (group, metadataOnly = false) => {
        if (!metadataOnly) clearSelection();
        renderWorldNavigation();
        if (exampleScopeId() && state.exampleScope?.key !== exampleScopeKey(exampleScopeId())) scheduleApplicationPoll(0);
      },
      onActivity: level => state.audio.near(level),
      onTiming: renderTimeScale,
      linkLabel: link => {
        const operation = state.worldNodes.get(link.from);
        const label = outcomeLabel(operation, link.outcome);
        const connection = operation && definitionFor(operation)?.kind !== "app" && state.editor.full.includes(link.from)
          ? outcomeConnection(operation, link.outcome) : null;
        return connection?.invalid ? `${label}: ${connection.label}` : label;
      },
      onScene: scene => {
        if (scene.target?.kind === 'region' && scene.target.group
          && state.inspectorMode === 'groups' && scene.target.group === state.managedGroup) {
          state.regionSelection = scene.nodes.find(node => node.id === scene.target.id) || scene.target;
          renderDock();
        }
        updateWorldMarks(scene);
        releaseUnusedIconUrls();
        const message = document.querySelector("#world-error");
        if (!document.querySelector('#graph').dataset.sceneError) {
          message.hidden = !scene.limited;
          message.dataset.severity = "warning";
          message.textContent = scene.limited ? "Zoom in for finer detail." : "";
        }
        renderWorldStatus();
        scheduleWorldObservations(180);
        if (exampleScopeId() && state.exampleScope?.key !== exampleScopeKey(exampleScopeId())) scheduleApplicationPoll(0);
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
    document.querySelector('#creator-startup-title').textContent = error.cause === 'session'
      ? 'Open the current Creator link' : error.cause === 'connection' ? 'Creator is not reachable' : 'Could not open the project';
    document.querySelector('#creator-startup-detail').textContent = error instanceof Error ? error.message : 'Please try again.';
    document.querySelector('#creator-retry').hidden = error.cause === 'session';
    document.querySelector('#creator-startup').hidden = false;
  }
}

function render() {
  if (!state.project) {
    return;
  }
  document.title = `${state.project.id} - Railix Creator`;
  renderBuildStatus();
  renderWorldNavigation();
  renderExampleContext();
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
  const content = document.querySelector('#inspector-content');
  const context = `${state.regionSelection?.id || state.selection.id}:${state.inspectorMode}`;
  const active = content.dataset.context === context && content.contains(document.activeElement)
    && document.activeElement.matches('input:not([type=checkbox]):not([type=radio]):not([type=file]), textarea')
    ? document.activeElement : null;
  const draft = active?.id ? {id:active.id, value:active.value, start:active.selectionStart,
    end:active.selectionEnd, direction:active.selectionDirection, scroll:active.scrollTop} : null;
  state.iconController?.abort();
  content.innerHTML = inspector();
  if (content.dataset.context !== context) content.scrollTop = 0;
  content.dataset.context = context;
  const navigation = document.querySelector("#inspector-content .inspector-tabs");
  const tabs = document.querySelector('#inspector-nav');
  if (navigation && tabs.firstElementChild?.outerHTML === navigation.outerHTML) navigation.remove();
  else tabs.replaceChildren(...(navigation ? [navigation] : []));
  inspectorElement.dataset.selection = state.regionSelection?.id || state.selection.id;
  renderPreview();
  openDetails.forEach(id => {
    const detail = document.getElementById(id);
    if (detail) detail.open = true;
  });
  const editing = draft && document.getElementById(draft.id);
  if (editing && content.contains(editing) && !editing.disabled) {
    editing.value = draft.value;
    editing.focus({preventScroll:true});
    if (draft.start !== null) editing.setSelectionRange(draft.start, draft.end, draft.direction);
    editing.scrollTop = draft.scroll;
  }
  document.querySelector("#overlay").innerHTML = picker() + groupPicker();
  document.querySelector('#group-dialog')?.showModal();
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
  const issues = allDiagnostics();
  for (const example of state.exampleFailures) if (example.id !== selectedExampleId()) {
    issues.push(...(example.result?.diagnostics || []).filter(issue=>issue.node).map(issue=>({...issue,exampleName:example.name,exampleId:example.id})));
  }
  for (const issue of issues) {
    const owner = diagnosticOwner(issue);
    const issues = state.worldIssues.get(owner) || [];
    issues.push(issue);
    state.worldIssues.set(owner, issues);
    if (Array.isArray(issue.regions)) state.worldMarkedRegions.set(owner, issue.regions);
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
  const presentation = region ? state.creator.groups.find(group=>group.id===region.group) || {} : stepPresentation(operation.id);
  const name = region ? state.worldGroups.get(region.group)?.name || region.name
    : app ? state.project.id : presentation.name || stepName(definition);
  const actions = region ? '' : app
    ? `<button id="add-trigger" type="button" ${availableTriggers().length ? "" : "disabled"}><i class="hud-symbol" data-symbol="plus" aria-hidden="true"></i>Add Trigger</button>`
    : nextStepControls(operation);
  const navigation = `<button type="button" id="dock-focus" title="Focus (F)" aria-label="Focus"><i class="hud-symbol" data-symbol="locate" aria-hidden="true"></i></button>
    ${region && region.id !== state.world?.navigation.at(-1)?.id ? '<button type="button" id="enter-region" title="Enter group (Enter)" aria-label="Enter group"><i class="hud-symbol" data-symbol="enter" aria-hidden="true"></i></button>' : ''}
    ${state.world?.navigation.length ? '<button type="button" data-leave-group title="Leave group" aria-label="Leave group"><i class="hud-symbol" data-symbol="back" aria-hidden="true"></i></button>' : ''}`;
  renderDockMarkup(document.querySelector('#inspector-navigation'), navigation);
  const deletion = state.groupDraft && state.inspectorMode === 'overview' || state.inspectorMode === 'groups' || app ? '' : region
    ? region.group ? `data-delete-region="${html(region.group)}"` : ''
    : `id="delete-step" ${trigger || removableStep(operation) ? '' : 'disabled'}`;
  const footer = document.querySelector('#inspector-footer');
  renderDockMarkup(footer, deletion ? `<button type="button" ${deletion} class="danger" title="Delete ${html(name)}" aria-label="Delete ${html(name)}"><i class="hud-symbol" data-symbol="trash" aria-hidden="true"></i><span>Delete</span></button>` : '');
  footer.hidden = !deletion;
  const type = region ? 'Group' : app ? 'Application' : trigger ? 'Trigger'
    : definition.outcomes?.length > 1 ? stepName(definition) : 'Step';
  const identity = document.querySelector('#inspector-identity');
  renderDockMarkup(identity, `<div class="selection-portrait" aria-hidden="true"><div></div></div><div><small>${html(type)}${region ? ` / ${html(count(region.count,'Step'))}` : ''}</small><h2 title="${html(region?.id || operation.id)}">${html(name)}</h2></div>`);
  const portrait=identity.querySelector('.selection-portrait > div');
  if(presentation.icon) {
    const image=document.createElement('img');image.src=iconUrl(presentation.icon);image.alt='';image.className='portrait-icon';
    if(portrait.querySelector('img')?.src!==image.src)portrait.replaceChildren(image);
  } else state.world?.portrait(region || {id:operation.id,use:operation.use,kind:definition.kind}, portrait);
  const linkedUses = state.editor.reuse?.count > 1 ? `<label class="linked-uses">Linked use <select data-linked-use aria-label="Linked uses">${state.editor.reuse.uses.map(use =>
    `<option value="${html(use.node)}" ${(region ? use.group === region.group : use.node === state.selection.id) ? 'selected' : ''}>${html(use.label || use.name)}</option>`).join('')}</select></label>` : '';
  const source = state.groupDraft
    ? `<form id="group-draft" class="group-draft"><label>Name<input name="name" value="Group" required maxlength="128"></label>
      <output>${html(count(state.groupDraft.nodes.length, 'Step'))}</output>${state.selectedSteps.size === 2 ? '<button type="button" data-group-range>Include path</button>' : ''}<button type="submit">Create group</button><button type="button" data-cancel-group>Cancel</button></form>`
    : `${linkedUses}
    <div id="dock-observation"><div class="dock-issues"></div><div class="dock-values"></div><div class="dock-counters"></div></div>
    ${actions ? `<nav class="dock-actions" aria-label="Selection actions">${actions}</nav>` : ''}
    ${!app && !trigger ? `<nav class="dock-actions" aria-label="Reuse and grouping">
      ${!region ? `<button type="button" data-group-selection><i class="hud-symbol" data-symbol="group" aria-hidden="true"></i>Create group${state.selectedSteps.size ? ` (${state.selectedSteps.size})` : ''}</button>` : ''}
      ${state.editor.reuse?.count > 1 ? `<button type="button" data-detach-use>Detach${state.editor.reuse.group ? ' group' : ''} (${state.editor.reuse.count})</button>` : ''}</nav>` : ''}
    ${(state.editor.sources?.length || 0) > 1 && !region && !selectedExampleId() ? `<label class="observation-source">Example source <select data-observation-source aria-label="Example source">${state.editor.sources.map(source =>
      `<option value="${html(source.id)}" ${source.id === triggerFor(operation.id)?.id ? 'selected' : ''}>${html(source.name)}</option>`).join('')}</select></label>` : ''}
    `;
  // Polling updates observations only; it must not replace a focused control.
  if (dock) {
    renderDockMarkup(dock, source);
  }
  renderDockObservation();
}

function exampleStatus(example) {
  const status = state.build !== 'Built' || state.pendingProject ? 'updating' : example.status || 'updating';
  return {status, mark:status === 'succeeded' ? '\u2713' : failedRunStates.has(status) ? '\u00d7' : '\u25cc',
    label:status === 'succeeded' ? 'Passed' : ['failed','rejected'].includes(status) ? 'Failed' : inputLabel(status)};
}

function renderExampleContext() {
  const host = document.querySelector('#example-session');
  const id = selectedExampleId(), example = selectedTraceCase();
  renderTimeScale();
  const scope = exampleScopeId();
  const scoped = scope ? state.exampleScope : null;
  const author = !state.regionSelection && state.selection.type === 'trigger' && Array.isArray(selectedOperation()?.examples) ? selectedOperation() : null;
  const ready = Boolean(author) || !scope || scoped?.id === scope && scoped.key === exampleScopeKey(scope) && !scoped.error;
  const recorded = [...state.exampleIds.values()].flatMap(choices => [...choices.values()]);
  const inventory = author ? (author.examples || []).map((example,index)=>({...example,
    ...recorded.find(candidate=>candidate.trigger===author.id && Number(candidate.index)===index),
    name:example.name,id:author.id+':'+index,trigger:author.id,index})) : recorded;
  const current = inventory.find(candidate => candidate.id === id);
  const cases = inventory
    .filter(candidate => author || !scope || ready && scoped.ids.has(candidate.id));
  const trigger = node(state.exampleTrigger) || (state.selection.type === 'trigger' ? selectedOperation() : null);
  host.hidden = !state.exampleIds.size && !trigger;
  if (host.hidden) return;
  const readError = example && state.traceReadError?.key === exampleSummaryKey(example) ? state.traceReadError.message : '';
  const failure = selectedExampleDiagnostics().find(diagnostic => diagnostic.node);
  renderDockMarkup(host, `<span class="example-context-label">Example</span>
    <select id="visible-example" aria-label="Visible Example" ${!ready || !cases.length ? 'disabled' : ''}>
      <button type="button"><selectedcontent></selectedcontent></button>
      ${current && !cases.some(candidate => candidate.id === id) ? `<option value="${html(id)}" selected disabled>${html(current.name)}</option>` : ''}
      <option value="" ${!id ? 'selected' : ''}>${!ready ? scoped?.error ? 'Examples unavailable' : 'Loading Examples' : !cases.length ? 'No Examples here' : 'Choose Example'}</option>
      ${cases.map(candidate => { const progress=exampleStatus(candidate); return `<option value="${html(candidate.id)}" data-trigger="${html(candidate.trigger)}" data-index="${html(candidate.index)}" data-status="${progress.status}" aria-label="${html(progress.label+': '+candidate.name)}" ${candidate.id === id ? 'selected' : ''}><span class="example-mark" data-status="${progress.status}" aria-hidden="true">${progress.mark}</span> <span>${html(candidate.name)}${cases.some(other => other.id !== candidate.id && other.name === candidate.name) ? ` (${html(node(candidate.trigger)?.name || candidate.trigger)})` : ''}</span></option>`; }).join('')}</select>
    ${trigger ? `<button id="edit-example" type="button" data-edit-trigger="${html(trigger.id)}" aria-label="Edit Examples" title="Edit Example inputs"><i class="hud-symbol" data-symbol="edit" aria-hidden="true"></i></button>` : ''}
    <div class="example-context-state" role="status">${readError ? observationFeedback({kind:'unavailable',status:readError},'','read') : ''}</div>
    ${scoped?.id === scope && scoped.error ? '<button type="button" data-retry-example-scope aria-label="Retry Example list">Retry</button>' : ''}
    ${readError ? '<button type="button" data-retry-example aria-label="Retry Example values" title="Retry Example values">Retry</button>' : ''}
    ${failure ? `<button type="button" data-evidence-step="${html(failure.node)}" class="example-failure" aria-label="Show failing Step" title="${html(failure.message)}"><strong aria-hidden="true">!</strong></button>` : ''}
    ${id ? `<button type="button" data-locate-trigger="${html(state.exampleTrigger)}" aria-label="Locate Example trigger" title="Locate Example trigger"><i class="hud-symbol" data-symbol="locate" aria-hidden="true"></i></button>
    <button type="button" data-clear-example aria-label="Clear Example" title="Clear Example"><i class="hud-symbol" data-symbol="close" aria-hidden="true"></i></button>` : ''}`);
  const output = host.querySelector('.example-context-state');
  output.title = readError;
  output.dataset.status = example?.status || '';
}

function renderTimeScale() {
  const host=document.querySelector('#example-time-scale'), values=state.world?.timeScale || [];
  host.hidden=!values.length || document.querySelector('#graph').dataset.renderer!=='canvas';
  if(host.hidden)return;
  renderDockMarkup(host,`<span>Visible avg</span><span class="time-scale" data-single="${values.length===1}"><i aria-hidden="true"></i><span>${values.map(value=>
    `<span>${html(formatNanos(value).replace(/\.0+(?= )/,''))}</span>`).join('')}</span></span><span class="time-missing"><i aria-hidden="true"></i>No data</span>`);
  host.setAttribute('aria-label',`Visible sampled Step times: ${values.map(formatNanos).join(' to ')}. Groups use their slowest measured Step.`);
}

function exampleScopeId() {
  const scene = state.world?.scene;
  const selected = state.regionSelection?.id || state.selection.id;
  if (selected && selected !== 'app' && scene?.nodes.some(item => item.id === selected && !item.expanded)) return selected;
  return state.world?.navigation.at(-1)?.id || '';
}

function exampleScopeKey(id) {
  return JSON.stringify([id, state.world?.scene?.revision, state.application.pid, state.exampleInventoryKey]);
}

async function refreshExampleScope(application, signal, examples) {
  const id = exampleScopeId(), key = exampleScopeKey(id);
  if (!id || examples.state !== 'completed' || state.exampleScope?.key === key) return;
  let response;
  try {
    response = await fetch(`/api/scene/observations?${new URLSearchParams({revision:state.world.scene.revision,cases:id})}`, {signal});
  } catch (error) {
    if (signal.aborted) throw error;
  }
  if (!response?.ok) {
    if (key === exampleScopeKey(exampleScopeId())) { state.exampleScope={id,key,error:true}; renderExampleContext(); }
    return;
  }
  const result = parseExact(await response.text());
  if (!currentApplication(application) || key !== exampleScopeKey(exampleScopeId()) || Number(result.application_pid) !== Number(application.pid)) return;
  state.exampleScope = {id,key,ids:new Set(result.case_ids)};
  renderExampleContext();
}

function renderDockMarkup(element, source) {
  if (element.railixMarkup !== source) {
    const focused = element.contains(document.activeElement) ? document.activeElement.id : "";
    const feedbackOpen=[...element.querySelectorAll('.observation-feedback[open]')].map(detail=>detail.id);
    const select=element.id==='example-session' && element.querySelector('#visible-example');
    if(select) {
      // Detaching the select closes its native popup during progress updates.
      const template=document.createElement('template');template.innerHTML=source;
      const next=template.content.querySelector('#visible-example');
      const options=new Map([...select.options].map(option=>[option.value,option]));
      const retained=new Set(), ordered=[];
      for(const option of next.options) {
        let current=options.get(option.value);
        if(!current){current=option.cloneNode(true);select.append(current);}
        else {
          if(current.innerHTML!==option.innerHTML)current.innerHTML=option.innerHTML;
          for(const attribute of [...current.attributes])if(!option.hasAttribute(attribute.name))current.removeAttribute(attribute.name);
          for(const attribute of option.attributes)if(current.getAttribute(attribute.name)!==attribute.value)current.setAttribute(attribute.name,attribute.value);
        }
        retained.add(current);ordered.push(current);
      }
      for(const option of [...select.options])if(!retained.has(option))option.remove();
      ordered.forEach((option,index)=>{if(select.options[index]!==option)select.insertBefore(option,select.options[index] || null);});
      select.disabled=next.disabled;select.value=next.value;
      for(const child of [...element.childNodes])if(child!==select)child.remove();
      let after=false;
      for(const child of [...template.content.childNodes]) {
        if(child===next){after=true;continue;}
        if(after)element.append(child);else element.insertBefore(child,select);
      }
      element.railixMarkup=source;
      for(const id of feedbackOpen){const detail=document.getElementById(id);if(detail && element.contains(detail))detail.open=true;}
      if(focused && focused!=='visible-example')document.getElementById(focused)?.focus({preventScroll:true});
      return;
    }
    const evidence=element.querySelector('#selection-evidence');
    const open=[...(evidence?.querySelectorAll('details[open][id]') || [])].map(detail=>detail.id);
    element.innerHTML = source;
    element.railixMarkup = source;
    if(evidence && evidence.dataset.evidenceKey===element.querySelector('#selection-evidence')?.dataset.evidenceKey)
      for(const id of open){const detail=document.getElementById(id);if(detail)detail.open=true;}
    for(const id of feedbackOpen){const detail=document.getElementById(id);if(detail && element.contains(detail))detail.open=true;}
    if (focused) document.getElementById(focused)?.focus({ preventScroll: true });
  }
}

function renderDockObservation() {
  refreshInspectorTabs();
  if (state.groupDraft) return;
  const slot = document.querySelector("#dock-observation");
  const operation = selectedOperation();
  const region = state.regionSelection;
  if (!slot || !region && (!operation || state.editor.nodes[operation.id] && !state.editor.full.includes(operation.id))) return;
  const item = currentWorldObservations()?.nodes.get(region?.id || operation.id);
  const measured = item?.metrics;
  const aggregate = ['app','trigger'].includes(state.selection.type);
  const counters = !region && measured && Object.hasOwn(measured, "executions") ? `<span>${formatInteger(measured.executions)} runs</span>
    ${worldMotionActive() && Number.isFinite(item.rate) ? `<span>${formatRate(item.rate)}/s</span>` : ""}
    ${metricNumber(measured.duration_samples) ? `<span>${averageNanos(measured)} avg</span>` : ""}
    ${metricNumber(measured.errors) ? `<span ${aggregate?'':'class="dock-error"'}>${formatInteger(measured.errors)} ${aggregate?'failed runs':'errors'}</span>` : ""}` : "";
  const example = selectedTraceCase();
  const issues = (state.worldIssues.get(region?.id || operation.id) || []).filter(issue =>
    region || issue.exampleName !== example?.name || !failedRunStates.has(state.traceStep?.status));
  let values = "";
  if (example && region) {
    const trace = state.regionTrace;
    const current = trace?.region === region.id && trace.example === example.id;
    const stopped=current && trace.changes && !Object.hasOwn(trace.changes,'after');
    const feedback = state.build !== 'Built' || state.pendingProject ? {kind:'pending',status:'Updating Example'}
      : current ? trace : {kind:'pending',status:'Loading Example'};
    values = selectionEvidence(example, current ? trace.changes : null, feedback,
      current && trace.passages?.length ? `<nav class="evidence-boundary" aria-label="Observed section passage">
        <button type="button" data-evidence-step="${html(trace.passages[trace.index].entry.id)}" title="Section input">In: ${html(trace.passages[trace.index].entry.name)}</button>
        <span aria-hidden="true">&gt;</span><button type="button" data-evidence-step="${html(trace.passages[trace.index].exit.id)}" title="${stopped ? 'Stopped Step' : 'Section output'}">${stopped ? 'Stopped' : 'Out'}: ${html(trace.passages[trace.index].exit.name)}</button>
        </nav>` : '');
  } else if (example && !region && state.selection.type !== "app" && definitionFor(operation)?.kind !== 'trigger') {
    const observed = observedExampleContext(operation);
    const failed = failedRunStates.has(state.traceStep?.status);
    const outcome=state.traceStep?.id===operation.id ? state.traceStep.outcome : '';
    const route=!observed.status && !failed && outcome && displayOutcomes(operation).length>1
      ? `<nav class="observed-route" aria-label="Observed route" data-observed-outcome="${html(outcome)}"><strong>${html(outcomeLabel(operation,outcome))}</strong>${stepDestination(outcomeConnection(operation,outcome))}</nav>` : '';
    values = selectionEvidence(example, observed.status ? null : failed ? {before:observed.root.context} : state.traceChanges,
      observed.status ? observed : {kind:failed ? 'failed' : 'succeeded',status:failed ? 'Stopped here' : 'Observed changes'}, failed
        ? `<div class="evidence-failure">${nodeIssues(operation.id).length ? issueList(operation.id,false)
          : `<p>${html(state.traceStep.message || inputLabel(state.traceStep.status))}</p>`}</div>${
          state.traceStep.exception ? `<details id="step-exception"><summary id="step-exception-summary">Exception details</summary><pre>${html(
            [state.traceStep.exception,...(state.traceStep.exception.causes || [])]
              .map(cause => [cause.type,...(cause.frames || [])].join('\n')).join('\nCaused by: '))}</pre></details>` : ''}` : route);
  } else if (example && !region && state.selection.type !== "app") {
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
          ? observationFeedback(observed,'',after ? 'output' : 'input')
          : `<output>${html(summary)}</output>`}</div>`;
    }).join("");
  }
  const failures=selectionFailures().filter(candidate=>candidate.id!==selectedExampleId());
  for (const [part, source] of [["issues", issues.length
    ? `<button type="button" class="dock-error" ${issues[0].exampleId ? `data-example-issue="${html(issues[0].exampleId)}"` : region && issues[0].node ? `data-evidence-step="${html(issues[0].node)}"` : 'data-open-panel="inspect"'}>${
      issues[0].exampleName && issues[0].exampleName!==example?.name ? `Example: ${html(issues[0].exampleName)}. ` : ""}${
      region && issues[0].node_name ? html(issues[0].node_name)+': ' : ''}${html(diagnosticMessage(issues[0]))}</button>`
      : failures.length ? `<div class="failed-examples"><small>Failed Examples</small>${failures.slice(0,5).map(failure=>
        `<button type="button" data-example-issue="${html(failure.id)}"><span class="example-mark" data-status="failed" aria-hidden="true">\u00d7</span>${html(failure.name)}<i class="hud-symbol" data-symbol="enter" aria-hidden="true"></i></button>`).join('')}
        ${failures.length>5?'<button type="button" data-open-issues>Show all problems</button>':''}</div>` : ""],
    ["values", values], ["counters", counters]]) {
    const element = slot.querySelector(`.dock-${part}`);
    renderDockMarkup(element, source);
    element.hidden = !source;
  }
}

function contextChanges(before, after) {
  const changed = new Map(), branches = new Set(), pending = [{path: [], before, after}];
  while (pending.length) {
    const item = pending.pop(), a = item.before, b = item.after;
    if (a === b) continue;
    const container = value => plainObject(value) || Array.isArray(value);
    if ((container(a) || a === undefined) && (container(b) || b === undefined)
      && (a === undefined || b === undefined || Array.isArray(a) === Array.isArray(b))
      && Object.keys(a || {}).length + Object.keys(b || {}).length) {
      const keys = [...new Set([...Object.keys(a || {}), ...Object.keys(b || {})])];
      for (let i = keys.length - 1; i >= 0; i--) {
        const key = Array.isArray(b ?? a) ? Number(keys[i]) : keys[i];
        pending.push({path:[...item.path, key], before:valueAt(a,[key]), after:valueAt(b,[key])});
      }
      continue;
    }
    if (JSON.stringify(a) === JSON.stringify(b)) continue;
    changed.set(JSON.stringify(item.path), a === undefined ? 'Added' : b === undefined ? 'Removed' : 'Changed');
    for (let length = 0; length < item.path.length; length++) branches.add(JSON.stringify(item.path.slice(0, length)));
  }
  return {before, after, changed, branches, count:changed.size};
}

function contextValue(value, path, budget, side = 'after') {
  if (value === undefined) return '';
  if ((plainObject(value) || Array.isArray(value)) && Object.keys(value).length) {
    const token = JSON.stringify(path), open = state.evidenceView.open.get('before:' + token);
    return `<details class="context-old" data-context-branch="${html(token)}" data-context-snapshot ${open ? 'open' : ''}>
      <summary id="context-before-${encodeURIComponent(token)}">Before ${Array.isArray(value) ? '[...]' : '{...}'}</summary><div class="context-children">${open ? contextRows(state.evidenceView.delta,path,budget,true) : ''}</div></details>`;
  }
  const source = previewValue(value);
  if (source.length <= 160) return `<output>${html(source)}</output>`;
  const token = JSON.stringify(path), open = state.evidenceView.open.get(`value:${side}:` + token);
  return `<details class="example-value" data-context-value="${html(token)}" data-context-side="${side}" ${open ? 'open' : ''}>
    <summary id="context-value-${side}-${encodeURIComponent(token)}">${html(source.slice(0,77))}...</summary>${open ? `<output>${html(source)}</output>` : ''}</details>`;
}

function contextRows(delta, path = [],
  budget = {left:200 + [...state.evidenceView.limits.values()].reduce((sum,limit)=>sum+limit,0)}, snapshot = false, replacement = '') {
  const original = snapshot ? undefined : valueAt(delta.before, path);
  const b = valueAt(snapshot ? delta.before : delta.after, path);
  const keysOf = value => plainObject(value) || Array.isArray(value) ? Object.keys(value) : [];
  const inherited = snapshot ? '' : delta.changed.get(JSON.stringify(path)) || replacement;
  const a = inherited && b !== undefined ? undefined : original;
  const all = snapshot || state.evidenceView.all || inherited;
  const keys = [...new Set([...keysOf(a), ...keysOf(b)])].map(key => Array.isArray(b ?? a) ? Number(key) : key)
    .filter(key => all || delta.changed.has(JSON.stringify([...path,key])) || delta.branches.has(JSON.stringify([...path,key])));
  const rows = [];
  const branchKey = (snapshot ? 'before:' : '') + JSON.stringify(path);
  const limit = Math.max(80, state.evidenceView.limits.get(branchKey) || 0);
  let index = 0;
  while (index < keys.length && index < limit && budget.left > 0) {
    budget.left--;
    const key = keys[index++], locator = [...path,key], token = JSON.stringify(locator);
    const before = valueAt(a,[key]), after = valueAt(b,[key]), value = after === undefined ? before : after;
    const kind = snapshot ? '' : delta.changed.get(token) || (inherited
      ? before === undefined ? 'Added' : after === undefined ? 'Removed' : 'Changed' : '');
    const changed = Boolean(kind || !snapshot && delta.branches.has(token));
    const label = typeof key === 'number' ? String(key) : JSON.stringify(key);
    const attributes = `data-context-change="${html(displayPath(locator))}" data-change="${kind.toLowerCase() || (changed ? 'changed' : 'unchanged')}"
      aria-label="${snapshot ? 'Before' : kind || (changed ? 'Changed' : 'Unchanged')} ${html(displayPath(locator))}"`;
    const container = keysOf(before).length + keysOf(after).length > 0 && (plainObject(value) || Array.isArray(value));
    if (container) {
      const open = state.evidenceView.open.get((snapshot ? 'before:' : '') + token) ?? (changed && !snapshot);
      const brace = Array.isArray(value) ? ['[',']'] : ['{','}'];
      const old = before !== undefined && (after !== undefined && Array.isArray(before) !== Array.isArray(after)
        || !plainObject(before) && !Array.isArray(before));
      rows.push(`<details class="context-branch" data-context-branch="${html(token)}" ${snapshot ? 'data-context-snapshot' : ''}
        data-context-default-open="${changed && !snapshot}"
        ${attributes} ${open ? 'open' : ''}><summary id="context-${snapshot ? 'before-branch-' : 'branch-'}${encodeURIComponent(token)}"><code>${html(label)}: ${brace[0]}</code></summary>${old ? `<del>${contextValue(before,locator,budget,'before')}</del>` : ''}
        <div class="context-children">${open ? contextRows(delta,locator,budget,snapshot,kind) : ''}</div><span class="context-close">${brace[1]}</span></details>`);
    } else {
      rows.push(`<div class="context-leaf" ${attributes}><code>${html(label)}:</code><span class="context-values">
        ${kind && before !== undefined ? `<del>${contextValue(before,locator,budget,'before')}</del>` : ''}
        ${after !== undefined ? kind ? `<ins>${contextValue(after,locator,budget)}</ins>` : contextValue(after,locator,budget,snapshot ? 'before' : 'after') : ''}</span></div>`);
    }
  }
  if (index < keys.length) rows.push(`<button type="button" class="context-more" data-context-more="${html(JSON.stringify(path))}"
    id="context-more-${encodeURIComponent(branchKey)}" data-context-offset="${index}" ${snapshot ? 'data-context-snapshot' : ''}>Show more fields (${keys.length-index})</button>`);
  return rows.join('');
}

function observationFeedback(observation, count = '', scope = 'selection') {
  const mark = {pending:'\u25cc',unreached:'\u25cb',unavailable:'!',failed:'\u00d7',succeeded:'\u2713'}[observation.kind];
  const id='observation-'+encodeURIComponent(JSON.stringify([scope,state.application.pid,selectedExampleId(),state.regionSelection?.id || state.selection.id,observation.kind]));
  return mark ? `<details id="${id}" class="observation-feedback" data-state="${observation.kind}"><summary id="${id}-summary" title="${html(observation.status)}"><span role="img" data-state="${observation.kind}"
    aria-label="${html(observation.status)}"><span aria-hidden="true">${mark}</span>${count !== '' ? `<small aria-hidden="true">${html(count)}</small>` : ''}</span></summary><span class="feedback-description">${html(observation.status)}</span></details>` : '';
}

function selectionEvidence(example, delta, feedback, navigation = '') {
  const key = JSON.stringify([state.application.pid, example.id, state.regionSelection?.id || state.selection.id]);
  if (state.evidenceView?.key !== key) state.evidenceView = {key, all:false, open:new Map(), limits:new Map()};
  state.evidenceView.delta = delta;
  const snapshot=delta && !Object.hasOwn(delta,'after');
  const all = state.evidenceView.all, label = snapshot ? all ? 'Hide input context' : 'Show input context'
    : all ? 'Show changed fields only' : 'Show all fields';
  const tree=delta && (snapshot ? all : delta.count || all)
    ? `<div class="context-tree" aria-label="${snapshot ? 'Recorded input context' : 'Context JSON'}"><span>{</span><div class="context-children">${contextRows(delta,[],undefined,snapshot)}</div><span>}</span></div>` : '';
  const badge = delta && !snapshot && !feedback.progress
    ? {...feedback,status:delta.count ? `${count(delta.count, 'field')} changed` : 'No fields changed'} : feedback;
  return `<section id="selection-evidence" data-evidence-key="${html(key)}" aria-label="${snapshot ? 'Example input' : 'Example changes'}"><header><strong>${html(example.name)}</strong>
    ${observationFeedback(badge,feedback.progress || (delta?.count || ''))}${delta ? `<button type="button" id="evidence-fields" data-evidence-fields aria-label="${label}" title="${label}" aria-pressed="${all}">
      <i class="hud-symbol" data-symbol="eye" aria-hidden="true"></i></button>` : ''}</header>
    ${navigation}${tree}</section>`;
}

function focusGroup(groupId) {
  if (window.matchMedia('(max-width: 650px)').matches) showInspector(false);
  state.world?.focus("group:" + groupId);
}

function focusRegion(regionId) {
  void state.world?.enter(regionId);
}

function renderBuildStatus() {
  document.querySelector("#build-state").textContent = state.build === "Built"
    ? inputLabel(state.application.state || "running") : state.build;
  document.body.dataset.build = state.build.toLowerCase().replace(" ", "-");
  for (const kind of ['undo','redo']) document.querySelector(`#editor-${kind}`).disabled = state.actionPending || state.writeActive || Boolean(state.saveFailure?.reload) || !state.history?.[kind];
  for (const selector of ['#inspector','#overlay','.world-ports']) {
    const element=document.querySelector(selector);
    if(element) element.inert=state.actionPending && !(selector==='#inspector' && state.projectImport.reload
      && !document.querySelector('#project-import-panel').hidden);
  }
  document.querySelector('#project-menu').disabled=!state.project || state.actionPending && !state.projectImport.reload;
  document.querySelector('#open-settings').disabled=state.actionPending;
  const selecting = Boolean(state.editorController && !state.editorController.signal.aborted
    && state.editorController.node && state.editorController.node !== (state.regionSelection ? 'app' : state.selection.id));
  document.querySelector('#inspector').setAttribute('aria-busy',String(selecting));
  for (const selector of ['#inspector-content','#inspector-nav','#inspector-navigation','#inspector-connections','#inspector-footer']) {
    document.querySelector(selector).inert = selecting;
  }
  renderAttention();
}

function renderWorldNavigation() {
  const locations = state.world?.navigation || [];
  const host = document.querySelector('#group-navigation');
  host.hidden = false;
  document.querySelector('#leave-group').hidden = !locations.length;
  const path = [{name:state.project?.id || 'Application'}, ...locations];
  renderDockMarkup(document.querySelector('#world-location'), `<ol aria-label="Location">${path.map((item,index) => {
    const parent = path[index-1]?.name;
    const name = parent && item.name.startsWith(parent+': ') ? item.name.slice(parent.length+2) : item.name;
    return `<li>${index < locations.length ? `<button type="button" data-world-level="${index}" title="${html(item.name)}" aria-label="Back to ${html(item.name)}">${html(name)}</button>`
      : index ? `<button type="button" data-inspect-region="${html(item.id)}" aria-current="location" title="Inspect ${html(item.name)}" aria-label="Inspect ${html(name)}">${html(name)} <small>(${count(item.count,'Step')})</small></button>`
        : `<span aria-current="location" title="${html(item.name)}">${html(name)}</span>`}</li>`;
  }).join('')}</ol>`);
}

function attentionItems() {
  const diagnostics = allDiagnostics().filter(issue => !Object.hasOwn(issue,'exampleName')).map(issue => ({
    key:JSON.stringify([diagnosticOwner(issue),issue.code,issue.path,issue.severity,issue.message]),
    node:diagnosticOwner(issue), path:issue.path, title:issue.node_name || node(diagnosticOwner(issue))?.id || 'Project',
    detail:issue.message, source:issue.severity === 'warning' ? 'Warning' : 'Build'
  }));
  const issues = [...new Map(diagnostics.map(issue => [issue.key,issue])).values()];
  if (state.build !== 'Built' || state.pendingProject || !state.exampleInventoryKey) return issues;
  return [...issues, ...state.exampleFailures.map(example => {
    const problem=example.result?.diagnostics?.[0];
    return {example:example.id,node:problem?.node,path:problem?.path,
      title:example.name,step:problem?.node_name,
      detail:problem ? diagnosticMessage(problem) : example.message || inputLabel(example.status),
      source:state.exampleFailures.some(other=>other.id!==example.id&&other.name===example.name)
        ? `Example / ${node(example.trigger)?.name || example.trigger}` : 'Example'};
  })];
}

function renderAttention() {
  const items = attentionItems(), badge = document.querySelector('#issue-count');
  badge.hidden = !items.length;
  const label = count(items.length,'issue');
  if (badge.textContent !== String(items.length)) badge.textContent = String(items.length);
  badge.title = label;
  badge.setAttribute('aria-label',`Show ${label}`);
  const control = document.querySelector('.build-indicator');
  const name = `Application build and runtime details: ${document.querySelector('#build-state').textContent}`;
  if (control.getAttribute('aria-label') !== name) control.setAttribute('aria-label',name);
  const host = document.querySelector('#attention-list');
  if (!host || !document.querySelector('#application-status').matches(':popover-open')) return;
  const ready = state.build === 'Built' && !state.pendingProject
    && state.application.examples?.state === 'completed' && Boolean(state.exampleInventoryKey);
  const offset = state.attentionOffset = Math.min(state.attentionOffset, Math.max(0,Math.floor((items.length-1)/20)*20));
  const content = `${state.attentionError ? `<p role="alert" class="attention-error">${html(state.attentionError)}</p>` : ''}
    ${items.length ? `<ol>${items.slice(offset,offset+20).map(item => `<li data-severity="${item.source==='Warning'?'warning':'error'}"><button type="button"
      id="attention-${item.example ? 'example-' : 'diagnostic-'}${html(encodeURIComponent(item.example || item.key))}"
      ${item.example ? `data-example-issue="${html(item.example)}" aria-label="Inspect Example: ${html(item.title)}" aria-describedby="attention-source-${html(encodeURIComponent(item.example))} attention-detail-${html(encodeURIComponent(item.example))}"`
        : `data-evidence-step="${html(item.node || 'app')}" data-diagnostic-path="${html(item.path || '')}"`}
      ${state.attentionRequest ? 'disabled' : ''} title="${html(item.detail)}">
      <small ${item.example?`id="attention-source-${html(encodeURIComponent(item.example))}"`:''}>${html(item.source)}</small><strong><span class="problem-cross" aria-hidden="true">${item.source==='Warning'?'!':'\u00d7'}</span>${html(item.title)}</strong><span class="problem-detail" ${item.example?`id="attention-detail-${html(encodeURIComponent(item.example))}"`:''}>${item.step?`<b>${html(item.step)}</b>`:''}<span>${html(item.detail)}</span></span>
      </button></li>`).join('')}</ol>` : `<p>${ready ? 'No build problems or failing Examples' : state.application.examples?.state === 'completed' ? 'Example results unavailable' : 'Checking Examples'}</p>`}
    ${state.attentionRequest ? '<p role="status">Opening Step...</p>' : ''}
    ${items.length > 20 ? `<nav aria-label="Issue pages"><button id="attention-previous" type="button" data-attention-page="${Math.max(0,offset-20)}" ${offset ? '' : 'disabled'}>Previous</button>
      <span>${offset+1}-${Math.min(items.length,offset+20)} of ${items.length}</span>
      <button id="attention-next" type="button" data-attention-page="${offset+20}" ${offset+20 < items.length ? '' : 'disabled'}>Next</button></nav>` : ''}`;
  renderDockMarkup(host,content);
}

function selectionFailures() {
  if (state.build !== 'Built' || state.pendingProject || state.application.examples?.state !== 'completed') return [];
  const operation=selectedOperation(), scope=exampleScopeId();
  const scoped=state.exampleScope?.key === exampleScopeKey(scope) ? state.exampleScope.ids : null;
  return state.exampleFailures.filter(example => !state.regionSelection && definitionFor(operation)?.kind === 'trigger'
    ? example.trigger === operation.id : scoped?.has(example.id));
}

function inspectorTabs(modes) {
  const issues=nodeIssues(state.selection.id), failures=selectionFailures();
  const otherIssues=(state.worldIssues.get(state.selection.id)||[]).filter(issue=>issue.exampleId&&issue.exampleId!==selectedExampleId());
  const symbols={inspect:'configure',overview:'data',appearance:'appearance',groups:'groups'};
  return `<nav class="inspector-tabs" aria-label="Inspector mode" style="--tab-count:${modes.length}">${modes.map(([mode,label])=>{
    const count=mode==='inspect' ? issues.length+otherIssues.length : mode==='overview' ? failures.length : 0;
    return `<button type="button" data-inspector-mode="${mode}" aria-label="${label}" title="${label}${count ? `: ${count} issue${count===1?'':'s'}` : ''}"
      data-has-error="${count>0}" class="${state.inspectorMode===mode?'active':''}"><i class="hud-symbol" data-symbol="${symbols[mode]}" aria-hidden="true"></i><span>${label}</span>${count?`<small class="tab-issue" aria-hidden="true">${count}</small>`:''}</button>`;
  }).join('')}</nav>`;
}

function refreshInspectorTabs() {
  const counts={inspect:nodeIssues(state.selection.id).length+(state.worldIssues.get(state.selection.id)||[])
    .filter(issue=>issue.exampleId&&issue.exampleId!==selectedExampleId()).length,overview:selectionFailures().length};
  for(const button of document.querySelectorAll('#inspector-nav [data-inspector-mode]')) {
    const count=counts[button.dataset.inspectorMode] || 0;
    button.dataset.hasError=String(count>0);
    let badge=button.querySelector('.tab-issue');
    if(count && !badge){badge=document.createElement('small');badge.className='tab-issue';badge.setAttribute('aria-hidden','true');button.append(badge);}
    if(badge){badge.hidden=!count;if(badge.textContent!==String(count))badge.textContent=String(count);}
    button.title=button.getAttribute('aria-label')+(count?`: ${count} issue${count===1?'':'s'}`:'');
  }
}

function cancelAttention() {
  const request = state.attentionRequest;
  state.attentionRequest = null;
  if (request?.editor && request.editor === state.editorController) {
    state.editorRequest++;
    request.editor.abort();
  }
}

async function openExampleIssue(id) {
  cancelAttention();
  const example = state.exampleFailures.find(example => example.id === id);
  if (!example || state.build !== 'Built' || state.pendingProject) return false;
  state.attentionError = '';
  const pid = state.application.pid;
  void selectExample(Number(example.index), {id:example.trigger}, {deferObservation:true});
  const request = state.attentionRequest = {example:id};
  renderAttention();
  try {
    const failure = example.result?.diagnostics?.find(issue=>issue.node);
    const destination=failure?.node || example.trigger;
    const selected = await selectWorldNode(destination, undefined, request);
    if (state.attentionRequest !== request || state.application.pid !== pid || selectedExampleId() !== id) return false;
    if (selected) {
      document.querySelector('#application-status').hidePopover();
      document.querySelector('#inspector-content').scrollTop = 0;
      state.world.focus(destination,.5);
      if (!failure || !focusDiagnosticPath(failure.path)) document.querySelector('#close-inspector').focus({preventScroll:true});
    }
    return selected;
  } catch (error) {
    if (state.attentionRequest === request) state.attentionError = error.message;
    return false;
  } finally {
    if (state.attentionRequest === request) {
      state.attentionRequest = null;
      if(state.attentionError){
        document.querySelector('#application-status').showPopover();
      }
      renderAttention();
      if (state.attentionError) document.querySelector(`#attention-list [data-example-issue="${CSS.escape(id)}"]`)?.focus({preventScroll:true});
    }
  }
}

async function flushEdits() {
  clearTimeout(state.saveTimer);
  state.saveTimer=0;
  if (Object.keys(documentChanges(state.builtProject, state.project)).length
      || Object.keys(documentChanges(state.savedCreator, state.creator)).length || state.removedFlows.length) {
    const functional = state.pendingProject || Object.keys(documentChanges(state.builtProject, state.project)).length > 0;
    state.pendingProject = false;
    enqueueWrite({revision:state.revision, projectChanged:functional,
      projectSource:JSON.stringify(state.project), creatorSource:JSON.stringify(state.creator)});
  }
  await state.writePromise;
  return state.build === 'Built';
}

async function editorAction(command, preview = false) {
  cancelAttention();
  if (state.actionPending) return null;
  state.actionPending = true;
  renderBuildStatus();
  let requestSent=false;
  try {
    if(!await flushEdits()) return null;
    requestSent=true;
    const response = await fetch('/api/editor', {method:'POST', headers:mutationHeaders(),
      body:JSON.stringify({revision:state.projectVersion, creator_revision:state.creatorVersion, command, preview})});
    const payload = parseExact(await response.text());
    if (!response.ok) {
      state.localDiagnostics = payload.diagnostics || [{code:'CREATOR_EDIT_REJECTED', message:payload.message || 'The change was not applied.', node:command.node || state.selection.id}];
      render(); return null;
    }
    if (preview) return payload;
    state.projectVersion = payload.revision;
    state.creatorVersion = payload.creator_revision;
    state.history = payload.history;
    state.workspace = payload.workspace;
    replaceApplication(payload.application);
    state.localDiagnostics = [];
    if (command.kind === 'delete' && state.jsonDraft?.node === command.node) state.jsonDraft=null;
    state.diagnostics = payload.diagnostics || [];
    state.picker = null;
    state.selectedSteps.clear();
    state.world?.connection(null);
    // The server may have changed distant linked uses. Discard the bounded read view before reloading it.
    state.project = clone(state.builtProject);
    state.creator = clone(state.savedCreator);
    const id = payload.selection || (state.regionSelection ? 'app' : state.selection.id) || 'app';
    let loaded = await loadEditor(id);
    if (loaded === 'unavailable' && id !== 'app') {
      loaded = await loadEditor('app');
      if (loaded === 'loaded') state.localDiagnostics = [];
    }
    if (loaded !== 'loaded') return payload;
    if (payload.selection && node(payload.selection)) state.selection = {type:definitionFor(node(payload.selection)).kind, id:payload.selection};
    else if (!node(state.selection.id)) state.selection = {type:'app',id:'app'};
    state.regionSelection = null;
    state.sceneDirty = true;
    render();
    await state.world?.refresh();
    return payload;
  } catch (error) {
    if(requestSent && !preview) {
      state.saveFailure={message:'Editor action not confirmed.',retryable:true,reload:true};
      state.build='Not built';
    } else state.localDiagnostics = [{code:'CREATOR_EDIT_UNAVAILABLE', message:'The editor action could not be completed.', node:state.selection.id}];
    render(); return null;
  } finally { state.actionPending = false; renderBuildStatus(); }
}

async function selectWorldNode(id, event, request) {
  if (state.actionPending) return false;
  closeProjectImport(false);
  if (!request || state.attentionRequest !== request) cancelAttention();
  if (event?.shiftKey) {
    state.groupDraft = null;
    const item = state.world?.scene?.nodes.find(node => node.id === id);
    if (item?.kind !== 'step') return false;
    if (!state.selectedSteps.size && state.selection.type === 'step') state.selectedSteps.add(state.selection.id);
    state.selectedSteps.has(id) ? state.selectedSteps.delete(id) : state.selectedSteps.add(id);
    closeSettings(false);renderDock(); showInspector(true); state.world.repaint();
    return true;
  }
  state.selectedSteps.clear();
  if (state.groupDraft) {
    state.groupDraft = null;
    renderDock();
    state.world?.repaint();
  }
  const item = state.world?.scene?.nodes.find(node => node.id === id);
  if (item) state.audio?.cue(worldAppearance(item));
  state.regionSelection = null;
  state.picker = null;
  state.revealNode = "";
  state.world?.cancelFocus();
  const loaded = await loadEditor(id, state.managedGroup, state.groupQuery, 0, request);
  if (request && state.attentionRequest !== request) return false;
  if (loaded !== "loaded" && !(loaded === "conflict" && state.editor.full.includes(id))) return false;
  const operation = node(id);
  if (!operation) return false;
  closeSettings(false);
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

function selectWorldGroup(group, region) {
  if(state.actionPending)return;
  closeProjectImport(false);
  closeSettings(false);
  cancelAttention();
  state.groupDraft = null;
  state.editorRequest++;
  state.editorController?.abort();
  state.editor = {...state.editor, reuse:null};
  if (state.world.navigation.at(-1)?.id !== region) state.world.cancelFocus();
  state.regionSelection = state.world.scene.nodes.find(item => item.id === region);
  state.audio.cue(worldAppearance(state.regionSelection), true);
  state.inspectorMode = "inspect";
  state.picker = null;
  resetMetrics(true);
  render();
  showInspector(true);
  state.world.repaint();
  if (group) void loadEditor('app', group).then(() => { renderDock(); state.world.repaint(); void requestSelectedTrace(); });
  else void requestSelectedTrace();
}

async function loadEditor(id, group = state.managedGroup, query = state.groupQuery, offset = 0, owner) {
  if (owner && state.attentionRequest !== owner) return 'superseded';
  const request = ++state.editorRequest;
  state.editorController?.abort();
  const controller = new AbortController();
  controller.node = id;
  state.editorController = controller;
  renderBuildStatus();
  if (owner) owner.editor = controller;
  try {
    await state.writePromise;
    if (request !== state.editorRequest) return "superseded";
    if (state.saveFailure?.reload) return 'conflict';
    const writer = state.writePromise;
    const version = state.projectVersion;
    const creatorVersion = state.creatorVersion;
    const trigger = state.exampleTrigger;
    const response = await fetch(`/api/editor?${new URLSearchParams({ ...(id ? {node: id} : {}), group, q: query, offset,
      ...(trigger ? {trigger} : {}) })}`, { signal: controller.signal });
    const payload = parseExact(await response.text());
    if (request !== state.editorRequest) return "superseded";
    if (writer !== state.writePromise || state.writeActive
        || version !== state.projectVersion || creatorVersion !== state.creatorVersion || trigger !== state.exampleTrigger) {
      return loadEditor(id, group, query, offset, owner);
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
    state.history = payload.history;
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
    renderBuildStatus();
  }
}

function worldAppearance(item) {
  const operation = state.worldNodes.get(item.id);
  const group = item.kind === "region" ? state.worldGroups.get(item.group) : null;
  const presentation = group || stepPresentation(item.id);
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
  const mean = sampled ? timing ? timing.maximum : metricNumber(live.duration_nanos_total) / metricNumber(live.duration_samples) : 0;
  const activity = item.kind === 'app' ? state.application.state === 'running' ? 'active' : 'idle'
    : observation && Number(observation.count) > 0
    && Number(observation.disabled_count) === Number(observation.count) ? "disabled"
    : live ? metricNumber(live.executions) > 0 ? "active" : "idle" : "";
  const detail = activity === "disabled" ? "Metrics off"
    : live ? `${formatInteger(live.executions)} executions${observation.rate === undefined ? "" : ` · ${formatRate(observation.rate)}/s`}${sampled ? ` · ${formatNanos(mean)} sampled` : ""}`
    : observation && Object.hasOwn(observation, "covered_count") && item.kind === "region"
      ? `${observation.covered_count}/${observation.count} reached${Number(observation.selected_count) ? ` · ${observation.selected_count} selected` : ""}` : "";
  const evidence = coverage === 'selected' ? 'Reached by selected Example'
    : coverage === 'covered' ? 'Reached by other Examples'
    : coverage === 'uncovered' ? 'Not reached by any Example' : '';
  return {
    selected: (state.regionSelection?.id || state.selection.id) === item.id || Boolean(group
      && state.inspectorMode === "groups" && state.managedGroup === group.id) || state.selectedSteps.has(item.id),
    related: Boolean(state.world?.related(item)),
    editing: state.groupDraft?.nodes.includes(item.id) || false,
    changed: state.worldChanges.has(item.id),
    error: issues.some(issue => issue.severity !== "warning") || !selectedExampleId() && !['app','trigger','region'].includes(item.kind)
      && Boolean(live && metricNumber(live.errors) > 0),
    warning: issues.some(issue => issue.severity === "warning") || item.kind === "app" && Boolean(state.themeError),
    never: item.kind !== "app" && item.kind !== "end" && Boolean(live && metricNumber(live.executions) === 0)
      && Number(observation?.disabled_count || 0) === 0,
    coverage,
    evidence,
    evidenceLabel: item.kind === 'region' && observation && Object.hasOwn(observation, 'covered_count')
      ? `${selectedExampleId() ? observation.selected_count || 0 : observation.covered_count}/${observation.count} ${selectedExampleId() ? 'reached' : 'covered'}` : '',
    roof: item.roof?.map(member => ({...member,
      error:state.worldIssues.get(member.id)?.some(issue => issue.severity !== 'warning'),
      coverage:state.build !== 'Built' || state.pendingProject ? ''
        : state.traceSummary?.nodes?.has(member.node) && selectedTraceCase() ? 'selected'
          : state.coverageBytes[member.node >> 3] & (1 << (member.node & 7)) ? 'covered'
            : state.application.examples?.state === 'completed' ? 'uncovered' : ''})),
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
    symbol: item.kind === "step" ? (definition?.outcomes?.length > 1
      || definition?.inputs?.some(input => input.authored_outcomes)
      || operation && outcomes(operation).length > 1) ? "branch"
      : definition?.returns[0]?.shape || "step" : item.kind,
    label: presentation.name || (item.kind === "app" && operation && stepName(definitionFor(operation))) || item.name,
    description: activity === "disabled" ? detail : live ? `${detail}. ${formatInteger(live.errors)} ${['app','trigger'].includes(item.kind) ? 'failed runs' : item.kind === 'region' ? 'Step errors' : 'errors'}; ${formatInteger(live.cancelled)} cancellations. ${
      item.kind === "region" ? timing ? `Slowest mean from ${timing.sampled}/${observation.count} sampled Steps; not passage latency. `
        : "Group timing summary unavailable. " : sampled
        ? `${formatInteger(live.duration_samples)} duration samples, including initial executions. ` : ""}${sampled ? "Sampled execution time, not utilization or the selected Example's duration." : "No duration inferred."}` : detail,
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
  return value?.published || null;
}

function sampledStepTime(id) {
  const observation = currentWorldObservations()?.nodes.get(id);
  if (!observation) return null;
  if (state.world.scene.nodes.find(node => node.id === id)?.kind === 'region') {
    const group = groupMean(id);
    return group?.sampled > 0 ? group.maximum : null;
  }
  const metrics = observation.metrics, samples = metricNumber(metrics?.duration_samples);
  const mean = samples > 0 ? metricNumber(metrics.duration_nanos_total) / samples : null;
  return Number.isFinite(mean) && mean >= 0 ? mean : null;
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
  for (const id of visible) if (!state.groupMeans.has(id)) state.groupMeans.set(id, {offset: 0, sum: 0, maximum: 0, sampled: 0, at: 0});
  const now = performance.now();
  const candidate = [...state.groupMeans].filter(([, value]) => now >= value.at)
    .sort((a, b) => a[1].at - b[1].at)[0];
  if (!candidate) {
    state.groupMeanTimer = setTimeout(refreshGroupMeans, 1_000);
    return;
  }
  const [id, value] = candidate;
  if (value.offset === -1) Object.assign(value, {offset: 0, sum: 0, maximum: 0, sampled: 0});
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
      if (samples > 0) {
        const mean = metricNumber(metrics.duration_nanos_total) / samples;
        value.sum += mean;value.maximum = Math.max(value.maximum,mean);value.sampled++;
      }
    }
    value.offset = Number(page.next);
    if (value.offset === -1) value.published = {sum:value.sum,maximum:value.maximum,sampled:value.sampled};
    value.at = performance.now() + (value.offset === -1 ? 10_000 : 100);
    state.world.repaint();
    renderDockObservation();
  } catch (_error) {
    Object.assign(value, {offset: 0, sum: 0, maximum: 0, sampled: 0, published:null, at: performance.now() + 5_000});
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
    durationNanos: sampledStepTime(link.from),
    editing: Boolean(state.picker?.arrival?.from===link.from && (state.picker.arrival.outcome===link.outcome
      || link.outcomes?.includes(state.picker.arrival.outcome)))
      || Boolean(state.groupDraft && (state.groupDraft.nodes.includes(link.from) || state.groupDraft.nodes.includes(link.to))),
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
  coverage.dataset.percent = `${Math.round(covered / Math.max(1, total) * 100)}%`;
  coverage.textContent = `${coverage.dataset.percent} coverage`;
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
  if (!observationMayReplace(current)) {
    if (!desired.querySelector('#apply-path').disabled) current.querySelector('#apply-path').disabled = false;
    if (!current.querySelector(':active')) {
      const additions = [...desiredChoices.querySelectorAll('.path-choice')]
        .filter(choice => !existing.has(choice.dataset.pathDraftJson));
      if (additions.length) {
        const builder=current.querySelector('.path-builder');
        builder.style.height ||= builder.getBoundingClientRect().height+'px';
        if (!existing.size) {
          currentChoices.querySelectorAll('.path-hint, .path-choices-label').forEach(element => element.remove());
          const label = desiredChoices.querySelector('.path-choices-label');
          if (label) currentChoices.prepend(label);
        }
        currentChoices.append(...additions);
      }
    }
    return true;
  }
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
  const createMode = element => ["#new-path-field", "#new-path-index"]
    .filter(selector => element?.querySelector(selector)).join(",");
  if (createMode(currentCreate) !== createMode(desiredCreate)) {
    if (currentCreate && desiredCreate) {
      currentCreate.replaceWith(desiredCreate);
    } else if (currentCreate) {
      currentCreate.remove();
    } else if (desiredCreate) {
      current.querySelector(".path-preview").before(desiredCreate);
    }
  }
  const verification = current.querySelector("[data-path-verification]");
  const desiredVerification = desired.querySelector("[data-path-verification]");
  verification.textContent = desiredVerification.textContent;
  verification.hidden = desiredVerification.hidden;
  refreshPathObservations(operation);
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
    const modes = [["overview", "Data"], ...(group ? [["appearance", "Appearance"]] : []), ['groups','Grouping']];
    const tabs = inspectorTabs(modes);
    return tabs + changeStatus() + (state.inspectorMode === 'groups' ? manageGroupsInspector() : state.inspectorMode === "appearance" && group
      ? presentationEditor(group, {name: region.name}, "group:" + group.id)
      : `<section id="selection-overview"></section><section class="inspector-section">${issueList(region.id)}</section>`
        + metricFacts("Group metrics", [...metricRows(currentWorldObservations()?.nodes.get(region.id)?.metrics),
          ...(groupMean(region.id)?.sampled ? [['Sum of Step averages',formatNanos(groupMean(region.id).sum)]] : [])]));
  }
  const modes = [
    ["inspect", "Configure"],
    ["overview", "Data"],
    ["appearance", "Appearance"],
    ["groups", "Grouping"]
  ];
  if (!(state.inspectorMode === 'examples' && state.selection.type === 'trigger') && !modes.some(([mode]) => mode === state.inspectorMode)) {
    state.inspectorMode = "inspect";
  }
  const tabs = inspectorTabs(modes);
  const panel = tabs + changeStatus();
  if (state.inspectorMode === "overview") return panel + '<section id="selection-overview"></section>';
  if (state.inspectorMode === 'groups') return panel + manageGroupsInspector();
  if (state.inspectorMode === "examples") {
    return panel + examplesInspector(node(state.selection.id));
  }
  if (state.inspectorMode === "appearance") {
    return panel + appearanceInspector();
  }
  if (state.selection.type === "trigger") {
    const trigger = node(state.selection.id);
    return panel + (trigger
      ? triggerInspector(trigger, issueList(trigger.id))
      : appInspector(issueList("app")));
  }
  if (state.selection.type === "step") {
    const operation = node(state.selection.id);
    return panel + (operation
      ? stepInspector(operation, issueList(operation.id))
      : appInspector(issueList("app")));
  }
  return panel + appInspector(issueList("app"));
}

function changeStatus() {
  if (!["Building", "Not built"].includes(state.build)) return "";
  const building = state.build === "Building";
  const required = allDiagnostics().some(issue => issue.code === "PROJECT_INPUT_REQUIRED");
  const title = building ? "Applying changes" : required ? "Complete required inputs" : "Changes not applied";
  const message = !building && state.saveFailure?.message;
  return `<section id="change-status" class="change-status" role="status" data-state="${building ? "pending" : "failed"}">
    <strong>${title}</strong>${message ? `<p>${html(message)}</p>` : ''}
    ${!building && state.saveFailure?.retryable && (state.saveFailure.reload || !state.jsonDraft && !state.exampleDraft)
      ? `<button type="button" id="retry-save">${state.saveFailure.reload ? 'Reload project' : 'Retry changes'}</button>` : ""}
  </section>`;
}

function appearanceInspector() {
  const operation = selectedOperation();
  const definition = definitionFor(operation);
  if (definition?.kind === "app") return `
    <section class="inspector-section">
      ${state.creator.created_at !== undefined ? `<p>Created ${html(new Date(Number(numberText(state.creator.created_at))).toLocaleString())}</p>` : ""}
    </section>${presentationEditor(stepPresentation(operation.id), {name: stepName(definition)}, "step:" + operation.id)}`;
  return operation && definition
    ? `${presentationEditor(
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
  const revision = state.settingsRevision;
  try {
    const response = await fetch('/api/settings', {cache:'no-store', signal:AbortSignal.timeout(5000)});
    const payload = await response.json();
    if (!response.ok) throw Error(payload.message || 'Settings could not be read.');
    if (revision !== state.settingsRevision || version !== state.settingsVersion && revision !== null) return;
    const edited = revision === null && state.settingsVersion !== state.savedSettingsVersion;
    state.settings = edited ? {...payload.values, ...state.settings,
      sounds:{...payload.values.sounds, ...state.settings.sounds}} : payload.values;
    state.settingsRevision = payload.revision;
    if (!edited) state.savedSettingsVersion = version;
    applySettings();
    document.querySelector('#settings-status').textContent = payload.diagnostics.map(item => item.message).join(' ');
    if (edited && !state.settingsTimer) void saveSettings();
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

function openSettings() {
  if (state.actionPending) return;
  closeProjectImport(false);
  const panel=document.querySelector('#audio-panel'), inspector=document.querySelector('#inspector');
  if(!panel.hidden){closeSettings();return;}
  panel.dataset.returnInspector=String(!inspector.hidden);
  panel.hidden=false;
  inspector.setAttribute('aria-label','Settings');
  document.querySelector('#open-settings').setAttribute('aria-expanded','true');
  state.iconController?.abort();
  showInspector(true);
  selectSettingsTab('appearance');
  document.querySelector('#settings-appearance-tab').focus({preventScroll:true});
  void refreshSettings();
  void state.audio?.catalog();
}

function closeSettings(restore=true) {
  const panel=document.querySelector('#audio-panel');
  if(panel.hidden)return;
  panel.hidden=true;
  document.querySelector('#inspector').setAttribute('aria-label','Inspector');
  document.querySelector('#open-settings').setAttribute('aria-expanded','false');
  if(restore){
    showInspector(panel.dataset.returnInspector==='true');
    document.querySelector('#open-settings').focus({preventScroll:true});
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
    state.savedSettingsVersion = version;
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
    const variant = theme.variants?.find(item => item.id === variantId)
      || theme.variants?.find(item => item.id === theme.defaultVariant);
    const missingVariant = variantId && variant?.id !== variantId;
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
    themeStatus(missingVariant ? `Renderer ${variantId} is unavailable. Using ${variant?.name || 'Canvas'}.` : '');
    state.world?.repaint();
    window.dispatchEvent(new Event("resize"));
  } catch (error) {
    if (error.name === "AbortError") return;
    if (!previous.length) {
      try { await state.world?.renderer(); }
      catch (fallback) {
        if (fallback.name === 'AbortError') return;
        themeStatus(`${error.message} ${fallback.message}`);
        return;
      }
    }
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
    || null;
  if (selected) {
    state.managedGroup = selected.id;
  }
  const members = selected ? inventory.get(selected.id) || 0 : 0;
  const regions = Number(state.editor.groups[selected?.id]?.regions || 0);
  return `
    ${!state.regionSelection && selectedOperation() && definitionFor(selectedOperation())?.kind === 'step' ? groupAssignment(selectedOperation()) : ''}
    ${selected ? `<header class="manager-heading">${inspectorHeader("Group", groupName(selected), "")}</header>` : ''}
    <details class="inspector-section" id="group-library" ${selected || state.selection.type !== 'app' && !state.regionSelection ? "" : "open"}>
      <summary>Browse groups (${state.editor.group_count})</summary>
      <input id="group-search" type="search" value="${html(state.groupQuery)}"
             placeholder="Search groups" aria-label="Search groups" autocomplete="off">
      <div id="group-results">${groupListMarkup(
        available,
        selected?.id || "",
        inventory
      )}</div>
      <button class="button primary wide" type="button" id="new-group">New Group</button>
    </details>
    ${selected ? `<div data-managed-group-details ${query && !available.some(group => group.id === selected.id) ? 'hidden' : ''}>
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
      </footer></div>` : ""}`;
}

function groupListMarkup(groups, selected, inventory = groupInventory()) {
  return `<div id="group-list" class="group-list">${groups.map(group => `
    <button type="button" data-manage-group="${html(group.id)}"
            class="${selected === group.id ? "active" : ""}"
            style="--group-color:${html(group.color || "#147982")}">
      <strong>${html(groupName(group))}<small>${html((state.editor.groups[group.id]?.routes || []).join(' / '))}</small></strong>
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
      <details id="presentation-symbols" class="symbol-chooser" data-presentation-target="${html(target)}"><summary>${presentation.icon?`<img src="${html(iconUrl(presentation.icon))}" alt="">`:''}Symbol</summary>
        <input id="symbol-search" type="search" aria-label="Search symbols" placeholder="Search symbols" autocomplete="off">
        <div id="symbol-options" class="symbol-options"></div>
        <button type="button" data-reset-presentation="icon" data-presentation-target="${html(target)}" ${presentation.icon?'':'disabled'}>Use default symbol</button>
      </details>
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

async function loadIcons() {
  state.iconController?.abort();
  const controller=state.iconController=new AbortController();
  state.iconError='';renderIconOptions();
  const timeout=setTimeout(()=>controller.abort(),5000);
  try {
    const response=await fetch('/api/icons',{cache:'no-store',signal:controller.signal});
    if(!response.ok)throw Error('Symbols unavailable.');
    const catalog=await response.json();
    if(state.iconController!==controller)return;
    state.icons=catalog.icons;state.iconError='';
  } catch(error) {
    if(state.iconController===controller)state.iconError=error.name==='AbortError'?'Symbol loading cancelled.':error.message;
  } finally {
    clearTimeout(timeout);
    if(state.iconController===controller){state.iconController=null;renderIconOptions();}
  }
}

function renderIconOptions() {
  const host=document.querySelector('#symbol-options');if(!host)return;
  const query=(document.querySelector('#symbol-search')?.value || '').trim().toLowerCase();
  host.innerHTML=(state.icons || []).filter(icon=>(icon.name+' '+icon.id).toLowerCase().includes(query)).map(icon=>
    `<button type="button" data-select-icon="${html(icon.id)}"><img src="${html(iconUrl(icon))}" alt=""><span>${html(icon.name)}</span></button>`).join('')
    || (state.iconError ? `<p role="status">${html(state.iconError)}</p><button type="button" data-retry-icons>Retry</button>`
      : `<p role="status">${state.iconController?'Loading symbols':'No matching symbols'}</p>`);
}

function inspectorHeader(kicker, title, subtitle) {
  return `
    <header class="inspector-heading">
      <span class="eyebrow">${html(kicker)}</span>
      <h2>${html(title)}</h2>
      ${subtitle ? `<p>${html(subtitle)}</p>` : ""}
    </header>`;
}

function diagnosticMessage(diagnostic) {
  const original=diagnostic.message || 'Project is not buildable.';
  const message=diagnostic.node_name && original.startsWith(diagnostic.node_name+': ')
    ? original.slice(diagnostic.node_name.length+2) : original;
  const operation=state.catalog.find(definition=>message.startsWith(`Nested Step ${definition.id} `));
  return operation ? stepName(operation)+message.slice(('Nested Step '+operation.id).length) : message;
}

function diagnosticHint(diagnostic, operation) {
  const locator=[...(diagnostic.path || '').replace(/^nodes\[\d+]/,'').matchAll(/\.([^.[\]]+)|\[(\d+)]/g)]
    .map(part=>part[1] ?? Number(part[2]));
  const nested=valueAt(operation,locator), definition=nested?.use && definitionOf(nested.use);
  const expected=definition?.receives?.length===1 ? definition.receives[0].shape
    : locator[0]==='receives' ? definitionFor(operation)?.receives.find(port=>port.name===locator[1])?.shape : null;
  const input=definitionFor(operation)?.inputs.find(field=>field.type==='steps' && field.value_source
    && diagnostic.path===inputDiagnosticPath(operation.id,['inputs',field.name,0]))?.value_source.input;
  const values=previewInputs(operation);
  const actual=input && values.has(input) ? previewValue(values.get(input)) : '';
  const mismatch=expected && expected!=='any' && input && values.has(input) && valueShape(values.get(input))!==expected;
  return {expected, input:mismatch ? input : undefined, text:diagnostic.code==='RUN_STEP_RECEIVE_REQUIRED' ? 'Missing field'
    : mismatch ? `${actual ? actual+' (expected ' : 'Expected '}${inputLabel(expected)}${actual?')':''}`
      : diagnosticMessage(diagnostic)};
}

function focusDiagnosticPath(path) {
  const fields=[...document.querySelectorAll('#inspector-content [data-setting-path]')];
  const field=fields.find(field=>field.dataset.settingPath===path)
    || fields.filter(field=>path?.startsWith(field.dataset.settingPath+'.') || path?.startsWith(field.dataset.settingPath+'['))
      .sort((a,b)=>b.dataset.settingPath.length-a.dataset.settingPath.length)[0];
  if(!field)return false;
  for(let parent=field.parentElement;parent;parent=parent.parentElement)if(parent.tagName==='DETAILS')parent.open=true;
  field.scrollIntoView({block:'center'});field.focus({preventScroll:true});return true;
}

function renderFieldDiagnostics(operation) {
  const retained=new Set();
  document.querySelectorAll('[data-value-error]').forEach(value=>value.removeAttribute('data-value-error'));
  for(const issue of nodeIssues(operation.id)) {
    const fields=[...document.querySelectorAll('#inspector-content [data-setting-path]')];
    const field=fields.find(field=>field.dataset.settingPath===issue.path);
    if(!field)continue;
    retained.add(field);field.dataset.hasError='true';
    const hint=diagnosticHint(issue,operation);
    let message=field.querySelector(':scope > [data-field-error]');
    if(!message){message=document.createElement('output');message.dataset.fieldError='';message.className='field-error';field.append(message);}
    if(message.textContent!==hint.text)message.textContent=hint.text;
    const input=field.querySelector('input,textarea,select,.path-button');
    if(input)input.setAttribute('aria-invalid','true');
    field.querySelectorAll('[data-path-value], [data-preview-stage]').forEach(value=>value.dataset.valueError='true');
    if(hint.input){
      document.querySelector(`[data-preview-input-value="${CSS.escape(hint.input)}"]`)?.setAttribute('data-value-error','true');
      document.querySelectorAll(`[data-input-name="${CSS.escape(hint.input)}"] [data-path-value]`).forEach(value=>value.dataset.valueError='true');
    }
  }
  for(const field of document.querySelectorAll('#inspector-content [data-setting-path][data-has-error]'))if(!retained.has(field)){
    delete field.dataset.hasError;field.querySelector(':scope > [data-field-error]')?.remove();
    field.querySelector('[aria-invalid]')?.removeAttribute('aria-invalid');
  }
  for(const slot of document.querySelectorAll('#inspector-content > [data-issues-owner]')) {
    for(const article of slot.querySelectorAll('article')) {
      const path=article.querySelector('[data-show-setting]')?.dataset.showSetting;
      article.hidden=Boolean(path && [...retained].some(field=>field.dataset.settingPath===path));
    }
    slot.hidden=![...slot.children].some(child=>!child.hidden);
  }
}

function issueList(owner, includeExample = true) {
  const diagnostics = [...new Map(allDiagnostics().filter(diagnostic => diagnosticOwner(diagnostic) === owner)
    .map(diagnostic=>[JSON.stringify([diagnostic.code,diagnostic.path,diagnostic.exampleName,diagnostic.message]),diagnostic])).values()];
  const otherExamples=includeExample ? [...new Map((state.worldIssues.get(owner)||[])
    .filter(issue=>issue.exampleId&&issue.exampleId!==selectedExampleId()).map(issue=>[issue.exampleId,issue])).values()] : [];
  const visible=diagnostics.length||otherExamples.length;
  return `
    <section data-issues-owner="${html(owner)}" class="${visible ? "issues" : ""}"
             aria-label="Errors and warnings" ${visible ? "" : "hidden"}>
      ${otherExamples.slice(0,5).map(issue=>`<button type="button" class="persistent-issue" data-example-issue="${html(issue.exampleId)}">
        <strong><span aria-hidden="true">\u00d7</span> ${html(issue.exampleName)}</strong><span>${html(diagnosticMessage(issue))}</span></button>`).join('')}
      ${otherExamples.length>5?'<button type="button" data-open-issues>Show all problems</button>':''}
      ${diagnostics.map(diagnostic => {
        const original=diagnostic.message || 'Project is not buildable.';
        const detailId='issue-'+encodeURIComponent(JSON.stringify([owner,diagnostic.code,diagnostic.path,diagnostic.exampleName,original]));
        const selected=node(owner);
        const setting=selected && (definitionFor(selected)?.inputs || []).some(input=>{
          const path=inputDiagnosticPath(owner,['inputs',input.name]);
          return diagnostic.path===path || diagnostic.path?.startsWith(path+'.') || diagnostic.path?.startsWith(path+'[');
        });
        return `
        <article class="${setting?'field-issue':''}" data-severity="${html(diagnostic.severity || "error")}">
          ${includeExample && diagnostic.exampleName ? `<small class="issue-context">${html(diagnostic.exampleName)}</small>` : ""}
          <p class="issue-message">${html(setting ? diagnosticHint(diagnostic,selected).text : diagnosticMessage(diagnostic))}</p>
          ${setting ? `<button type="button" class="issue-setting" data-show-setting="${html(diagnostic.path)}">Show setting</button>` : ''}
          <details id="${html(detailId)}"><summary id="${html(detailId)}-summary">Technical details</summary><code>${html(diagnostic.code || "CREATOR_ERROR")}</code>
            <p>${html(original)}</p>
            ${diagnostic.path ? `<p><code>${html(diagnostic.path)}</code></p>` : ""}</details>
        </article>`;
      }).join("")}
    </section>`;
}

function allDiagnostics() {
  const diagnostics = [
    ...state.localDiagnostics,
    ...state.diagnostics,
    ...selectedExampleDiagnostics().filter(issue=>issue.node)
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

function selectedExampleDiagnostics() {
  const example = state.application.example;
  const result = state.traceSummaryKey === exampleSummaryKey(example || {}) ? state.traceSummary?.result : example?.result;
  return state.build === "Built" && !state.pendingProject && example
      && example.id === selectedExampleId()
    ? (result?.diagnostics || []).map(diagnostic => {
      const receive = /\.receives\.([^.]+)$/.exec(diagnostic.path || '');
      const path = receive && node(diagnosticOwner(diagnostic))?.receives?.[receive[1]];
      return {...diagnostic, exampleName:example.name,
        message:path ? diagnostic.code === 'RUN_STEP_RECEIVE_REQUIRED' ? `Missing field: ${displayPath(path)}.`
          : `${diagnostic.message} (${displayPath(path)})`
          : diagnostic.node_name ? `${diagnostic.node_name}: ${diagnostic.message}` : diagnostic.message};
    })
    : [];
}

function exampleSummaryKey(example) {
  return [state.application.fingerprint || "", state.application.pid || 0,
    example.id, example.status, example.events, state.world?.scene?.revision || ''].join(":");
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
    <details><summary>Build details</summary>
    <section id="workspace-details" class="facts">
      <dl>
        <div><dt>Project path</dt><dd id="project-path">${html(state.workspace.project_path || "")}</dd></div>
        <div><dt>Build path</dt><dd id="build-path">${html(state.application.build_path || "")}</dd></div>
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
    </section></details>
    `;
}

function openProjectImport() {
  if (state.actionPending && !state.projectImport.reload) return;
  document.querySelector('#project-actions').hidePopover();
  closeSettings(false);
  const panel=document.querySelector('#project-import-panel'), inspector=document.querySelector('#inspector');
  panel.dataset.returnInspector=String(!inspector.hidden);
  panel.hidden=false;
  inspector.setAttribute('aria-label','Import project');
  showInspector(true);
  renderProjectImport();
  renderBuildStatus();
  document.querySelector(state.projectImport.reload ? '#project-import-reload' : '#project-import-file').focus({preventScroll:true});
}

function closeProjectImport(restore=true) {
  const panel=document.querySelector('#project-import-panel');
  if(panel.hidden)return;
  panel.hidden=true;
  document.querySelector('#inspector').setAttribute('aria-label','Inspector');
  if(restore){
    showInspector(panel.dataset.returnInspector==='true');
    document.querySelector('#project-menu').focus({preventScroll:true});
  }
  renderBuildStatus();
}

function renderProjectImport() {
  const draft=state.projectImport, preview=document.querySelector('#project-import-preview');
  const busy=state.actionPending && !draft.reload;
  const source=document.querySelector('#project-import-source');
  document.querySelector('#project-import-panel').setAttribute('aria-busy',String(busy || Boolean(draft.reader)));
  const file=document.querySelector('#project-import-file');
  file.disabled=busy || draft.reload;
  source.disabled=busy || draft.reload;
  document.querySelector('#project-import-submit').disabled=busy || draft.reload || Boolean(draft.reader) || !draft.preview;
  document.querySelector('#project-import-clear').disabled=busy || draft.reload || !source.value && !draft.reader && !file.value;
  document.querySelector('#project-import-preview-source').disabled=busy || draft.reload || !source.value;
  document.querySelector('#project-import-reload').hidden=!draft.reload;
  preview.hidden=!draft.preview;
  if(draft.preview)renderDockMarkup(preview,`<strong>${html(draft.preview.id)}</strong><div><span>${count(draft.preview.flows,'flow')}</span><span>${count(draft.preview.steps,'Step')}</span></div>`);
  renderDockMarkup(document.querySelector('#project-import-target'),`<small>Replaces ${html(state.project.id)}</small><code>${html(state.workspace.project_path)}</code><small>Cannot undo.</small>`);
}

function projectImportStatus(message, severity = '', diagnostics = []) {
  const status=document.querySelector('#project-import-status');
  status.textContent=message;
  if(severity)status.dataset.severity=severity;else delete status.dataset.severity;
  for(const id of ['project-import-source','project-import-file']) {
    const input=document.getElementById(id);
    if(severity==='error')input.setAttribute('aria-invalid','true');else input.removeAttribute('aria-invalid');
  }
  const details=document.querySelector('#project-import-diagnostics');
  details.hidden=!diagnostics.length;
  details.querySelector('pre').textContent=diagnostics.map(issue=>[issue.code,issue.path].filter(Boolean).join('\n')).join('\n\n');
}

function clearProjectImport() {
  const reader=state.projectImport.reader;
  state.projectImport.reader=null;
  reader?.abort();
  state.projectImport.preview=null;
  document.querySelector('#project-import-file').value='';
  document.querySelector('#project-import-source').value='';
  document.querySelector('#project-import-paste').open=false;
  projectImportStatus('');
  renderProjectImport();
}

function updateProjectImportPreview() {
  const source=document.querySelector('#project-import-source').value;
  state.projectImport.preview=null;
  projectImportStatus('');
  if(source.trim()) {
    if(source.length > Number(state.workspace.source_limit_bytes)
        || new TextEncoder().encode(source).length > Number(state.workspace.source_limit_bytes)) {
      projectImportStatus(`Project exceeds ${formatBytes(state.workspace.source_limit_bytes)}.`, 'error');
    } else {
      let project;
      try{project=parseExact(source);}catch{projectImportStatus('Invalid JSON.', 'error');}
      if(project!==undefined) {
        if(!plainObject(project) || typeof project.id!=='string' || !project.id.trim()
            || !Array.isArray(project.nodes) || !Array.isArray(project.links)) {
          projectImportStatus('Expected a project with a name, Steps and connections.', 'error');
        } else state.projectImport.preview={id:project.id,steps:project.nodes.length,
          flows:project.nodes.filter(operation=>definitionOf(operation?.use)?.kind==='trigger').length};
      }
    }
  }
  renderProjectImport();
}

function readProjectImportFile(input) {
  const file=input.files?.[0];
  if(!file || state.actionPending)return;
  const previous=state.projectImport.reader;
  state.projectImport.reader=null;
  previous?.abort();
  document.querySelector('#project-import-source').value='';
  state.projectImport.preview=null;
  projectImportStatus('');
  if(file.size > Number(state.workspace.source_limit_bytes)) {
    projectImportStatus(`Project exceeds ${formatBytes(state.workspace.source_limit_bytes)}.`, 'error');
    renderProjectImport();return;
  }
  const reader=state.projectImport.reader=new FileReader();
  reader.onload=()=>{
    if(state.projectImport.reader!==reader)return;
    state.projectImport.reader=null;
    try {
      document.querySelector('#project-import-source').value=new TextDecoder('utf-8',{fatal:true}).decode(reader.result);
    } catch {
      projectImportStatus('File must contain UTF-8 JSON.', 'error');renderProjectImport();return;
    }
    updateProjectImportPreview();
  };
  reader.onerror=()=>{
    if(state.projectImport.reader!==reader)return;
    state.projectImport.reader=null;
    projectImportStatus('Could not read this file.', 'error');renderProjectImport();
  };
  renderProjectImport();
  reader.readAsArrayBuffer(file);
}

async function importProjectJson(reload=false) {
  const draft=state.projectImport;
  if(state.actionPending && !draft.reload || draft.request || draft.reader || draft.reload && !reload
      || !reload && !draft.preview)return;
  const source=document.querySelector('#project-import-source').value;
  const controller=draft.request=new AbortController();
  state.actionPending=true;
  let previousBuild=state.build;
  let committed=reload;
  try {
    projectImportStatus('');
    draft.reload=false;
    renderProjectImport();renderBuildStatus();
    if(!reload) {
      await flushEdits();
      previousBuild=state.build;
      state.build='Building';state.pendingProject=true;
      renderBuildStatus();
      // Once the request leaves the editor, only a response can establish whether replacement committed.
      committed=true;
      const response=await fetch('/api/project',{method:'POST',headers:mutationHeaders(),body:source,signal:controller.signal});
      if(!response.ok) {
        const payload=parseExact(await response.text());
        committed=false;
        state.build=previousBuild;state.pendingProject=false;
        if(payload.application)replaceApplication(payload.application);
        projectImportStatus(payload.diagnostics?.[0]?.message || payload.message || 'Project was not replaced.', 'error',payload.diagnostics || []);
        return;
      }
      clearProjectImport();
      await response.body?.cancel().catch(()=>{});
    }
    const response=await fetch('/api/editor',{cache:'no-store',signal:controller.signal});
    const payload=parseExact(await response.text());
    if(!response.ok) {
      draft.reload=true;
      projectImportStatus('Refresh the project to continue.', 'error');
      return;
    }
    applyImportedProject(payload);
    closeProjectImport(false);
    showInspector(false);
    render();
    scheduleApplicationPoll(0);scheduleMetricsPoll(0);
    void state.world?.focus('app',.5);
  } catch(error) {
    if(error.name==='AbortError')return;
    draft.reload=committed;
    state.build=committed ? 'Loading' : previousBuild;
    state.pendingProject=false;
    projectImportStatus(committed ? 'Replacement not confirmed. Refresh the project.' : 'Project was not replaced.', 'error');
  } finally {
    if(draft.request===controller)draft.request=null;
    state.actionPending=draft.reload;
    renderProjectImport();renderBuildStatus();
  }
}

function applyImportedProject(payload) {
  state.editorRequest++;
  state.editorController?.abort();
  state.editorController = null;
  state.project = payload.project;
  state.editor = payload.editor;
  state.builtProject = clone(payload.project);
  state.creator = payload.creator || { format: 2, groups: [], steps: {} };
  state.savedCreator = clone(state.creator);
  state.projectVersion = payload.revision;
  state.creatorVersion = payload.creator_revision;
  state.history = payload.history;
  state.workspace = payload.workspace;
  state.diagnostics = payload.diagnostics || [];
  state.localDiagnostics = [];
  replaceApplication(payload.application);
  state.build = "Built";
  state.saveFailure = null;
  state.pendingProject = false;
  state.pendingWrite = null;
  state.removedFlows = [];
  state.selection = { type: "app", id: "app" };
  state.regionSelection = null;
  state.groupPointer = null;
  state.inspectorMode = "inspect";
  state.picker = null;
  state.groupPicker = null;
  state.pathPicker = null;
  state.jsonDraft = null;
  state.exampleDraft = null;
  state.exampleTrigger = "";
  state.exampleIndex = 0;
  state.revealNode = "";
  state.revision++;
  clearInputQueries();
  clearPreview(true);
  resetMetrics();
  state.worldChanges.clear();
  state.worldMarkedRegions.clear();
  state.worldIssues.clear();
  state.worldCovered.clear();
  state.worldSelected.clear();
  state.coverageSource = null;
  state.coverageBits = "";
  state.sceneDirty = true;
}

function triggerInspector(trigger, issues) {
  const definition = definitionOf(trigger.use);
  return `
    ${issues}
    ${inputFields(trigger, definition.inputs.map(input => input.name === definition.example_target
      ? { ...input, label: "Store incoming data at" } : input), ["inputs"])}
    <section class="inspector-section">
      <div class="section-heading"><strong>Flow result fields</strong></div>
      <p class="inspector-help">These context fields provide the results when this flow finishes.</p>
      <div class="contract-list">${definition.results.map(result => `
        <span><code>context.${html(result.name)}</code><small>${html(result.shape)}${
          Object.hasOwn(result, "default") ? " · default " + html(JSON.stringify(result.default)) : ""
        }</small></span>`
      ).join("")}</div>
    </section>
    ${configurationNextSteps(trigger)}
    ${stepDetails(trigger, definition)}
    ${metricsSetting(trigger)}
    ${runtimeDetails()}
    <div id="run-result-panel">${runResultPanel()}</div>`;
}

function examplesInspector(trigger) {
  if (!trigger) {
    return appInspector(issueList("app"));
  }
  const example = selectedExample(trigger);
  return `
    ${issueList(trigger.id)}
    <section class="inspector-section">
      <div class="section-heading"><strong>Examples</strong><span>${count(trigger.examples.length, "case")}</span></div>
      <button type="button" id="add-example"><i class="hud-symbol" data-symbol="plus" aria-hidden="true"></i>Add Example</button>
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
  const source = definition.inputs.find(input => input.type === 'candidates');
  const process = definition.inputs.find(input => input.type === 'steps');
  const destination = definition.inputs.find(input => input.type === 'path' && input.access !== 'read');
  const pipeline = definition.inputs.length === 3 && source && process && destination;
  return `
    ${issues}
    <div id="preview-observation" role="status"></div>
    <div id="preview-values">
      ${portMappings(operation, definition, "receives")}
      ${pipeline ? [[source,'Source'],[process,'Process'],[destination,'Destination']].map(([input,label]) =>
        `<section class="pipeline-stage"><h3>${label}</h3>${inputFields(operation,[input],["inputs"],definition.inputs)}</section>`).join('')
        : inputFields(operation, definition.inputs, ["inputs"])}
      ${portMappings(operation, definition, "returns")}
    </div>
    ${configurationNextSteps(operation)}
    ${stepDetails(operation, definition)}
    ${metricsSetting(operation)}
    ${runtimeDetails()}
    <div id="preview-error" role="status"></div>`;
}

function configurationNextSteps(operation) {
  return `<section class="inspector-section configuration-next-steps">
    <div class="section-heading"><strong>Next Steps</strong></div>
    ${nextStepControls(operation)}
  </section>`;
}

function stepDetails(operation, definition) {
  return `<details class="inspector-section" id="step-details"><summary>Technical details</summary>
    <dl class="step-identity"><dt>Step ID</dt><dd>${html(operation.id)}</dd>
      <dt>Definition</dt><dd>${html(definition.id)}</dd>
      <dt>Version</dt><dd>${html(definition.version)}</dd></dl>
  </details>`;
}


function portMappings(operation, definition, direction) {
  const ports = definition[direction];
  return ports.map(port => pathInput(
    operation,
    portPathInput(operation, definition, direction, port),
    [direction, port.name],
    operation[direction]?.[port.name]
  )).join("");
}

function portPathInput(operation, definition, direction, port) {
  const single = definition[direction].length === 1;
  const prefix = direction === "receives" ? "source" : "target";
  const source = operation.receives?.[port.name]
    || (definition.receives.length === 1 ? operation.receives?.[definition.receives[0].name] : undefined);
  const fallback = source || ["context", "payload", port.name];
  return {
    name: single ? prefix : prefix + "_" + port.name,
    label: direction === "receives" ? single ? "Read from" : "Read " + inputLabel(port.name) + " from"
      : "Store " + inputLabel(port.name).toLowerCase() + " at",
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
    const connection = outcomeConnection(operation, declared[0]);
    return `<div class="next-step-controls"><span>Next: ${stepDestination(connection)}</span>
      <button class="button" type="button" id="add-next-step"
              ${insertionAllowed(operation, declared[0]) ? "" : "disabled"}><i class="hud-symbol" data-symbol="plus" aria-hidden="true"></i>Add next Step</button></div>`;
  }
  return `<section class="next-routes" aria-label="Next Steps">
    ${declared.map(outcome => {
      const connection = outcomeConnection(operation, outcome);
      const insertable = insertionAllowed(operation, outcome);
      return `<div>
        <span><strong>${html(outcomeLabel(operation, outcome))}</strong>${stepDestination(connection)}</span>
        <button class="button" type="button" data-add-outcome="${html(outcome)}"
                ${insertable ? "" : "disabled"}>Add Step</button>
      </div>`;
    }).join("")}
  </section>`;
}

function stepDestination(connection) {
  return connection.target ? `<button type="button" class="step-destination" data-navigate-step="${html(connection.target)}">${html(connection.label)}</button>`
    : `<small>${html(connection.label)}</small>`;
}

function outcomeConnection(operation, outcome) {
  const destinations = outcomeDestinations(operation, outcome);
  const destination = destinations.length === 1 ? destinations[0] : undefined;
  const target = node(destination);
  const issue = destinations.length > 1 ? "Multiple links"
    : !target && destination !== "end" ? destination ? "Unknown Step" : "Missing link" : "";
  return { invalid: Boolean(issue), target: !issue && target?.id, label: issue || (target
    ? stepPresentation(target.id).name || stepName(definitionFor(target)) : "End") };
}

function removableStep(operation) {
  return outcomes(operation).slice(1).every(outcome => outcomeTarget(operation, outcome) === "end");
}

function inputFields(operation, inputs, base, scopeInputs = inputs) {
  if (!inputs.length) {
    return "";
  }
  return inputs.map(input => {
    const locator=[...base,input.name];
    const editor = inputEditor(operation, input, locator, scopeInputs, base)
      .replace(/<(section|div|details)\b/,`<$1 data-setting-path="${html(inputDiagnosticPath(operation.id,locator))}" tabindex="-1"`);
    return base.length === 1 && input.type !== "path" && input.type !== "steps"
      ? `<div class="observed-input">${editor}<div class="input-result"
          data-input-result="${html(input.name)}" aria-live="polite"></div></div>` : editor;
  }).join("");
}

function inputEditor(operation, input, locator, scopeInputs, scopeBase) {
  const value = hasAt(operation, locator) ? valueAt(operation, locator) : defaultInput(input);
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
  return `
    <section class="inspector-section" data-input-name="${html(input.name)}">
      <div class="section-heading"><strong>${html(input.label || inputLabel(input.name))}</strong></div>${optional}
      <button class="path-button" type="button" id="${html(id)}-path"
              data-open-path="${locatorToken(locator)}" data-input-meta="${metaToken(input)}" title="${html(input.access.replaceAll('_',' '))} path">
        ${selected?.length ? pathCrumbs(selected) : "Choose path"}
      </button>
      ${locator[0] === "returns" && selected?.length ? `<p class="path-effect">${
        Object.values(operation.receives || {}).some(path => samePath(path, selected))
          ? "Replaces the source field with the result." : "Writes the result to this field."
      }</p>` : ""}
      ${open ? pathBrowser(input, operation, locator) : pathObservation(operation, input, locator)}
    </section>`;
}

function pathObservation(operation, input, locator) {
  return `<div class="path-observation">
    <div class="path-values" data-path-observation="${locatorToken(locator)}"
         data-input-meta="${metaToken(input)}" aria-live="polite">${pathObservationValues(operation, input, locator)}</div>
  </div>`;
}

function pathObservationValues(operation, input, locator) {
  const open = state.pathPicker && samePath(state.pathPicker.locator, locator);
  const configured = hasAt(operation, locator) ? valueAt(operation, locator) : defaultInput(input);
  return pathValues(operation, input, open ? state.pathDraft : configured, open ? [] : locator);
}

function refreshPathObservations(operation) {
  document.querySelectorAll("[data-path-observation]").forEach(slot => {
    const locator = parseToken(slot.dataset.pathObservation);
    const input = parseToken(slot.dataset.inputMeta);
    const content = pathObservationValues(operation, input, locator);
    if (slot.railixMarkup === content) return;
    const expanded = new Set([...slot.querySelectorAll("details[open] [data-path-value]")]
      .map(value => value.dataset.pathValue));
    renderDockMarkup(slot, content);
    slot.querySelectorAll("details").forEach(detail => {
      detail.open = expanded.has(detail.querySelector("[data-path-value]")?.dataset.pathValue);
    });
  });
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
        input.authored_outcomes ? "First matching case" : "First matching value"
      }</span></div>
      ${!input.authored_outcomes && candidates.length > 1
        ? '<p class="inspector-help">Checked from top to bottom. The first present value that passes its comparisons is used.</p>' : ""}
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
      <details class="candidate-add" ${candidates.length ? '' : 'open'}><summary>Add value source</summary>
      <input type="search" id="${html(id)}-candidate-search" value="${html(query)}"
             data-candidate-query="${token}" data-input-meta="${metaToken(input)}"
             placeholder="Search value sources" autocomplete="off">
      <div data-candidate-options="${token}" data-input-meta="${metaToken(input)}">
        ${candidateOptions(input, locator)}
      </div>
      </details>
    </section>`;
}

function matcherGroupsInput(operation, input, locator, groups, scopeInputs, scopeBase) {
  const queryLocator = [...locator, "new-group"];
  const token = locatorToken(locator);
  return `
    <section class="inspector-section matcher-groups" data-input-name="${html(input.name)}">
      <div class="section-heading"><strong>${html(inputLabel(input.name))}</strong></div>
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
      <details class="matcher-group-add" id="${html(inputId(locator))}-alternative" ${groups.length?'':'open'}>
        <summary>Add alternative (OR)</summary>
        <input type="search" value="${html(queryAt(state.candidateQueries, queryLocator))}"
               data-matcher-group-query="${token}" data-input-meta="${metaToken(input)}"
               placeholder="Search value sources" autocomplete="off">
        <div data-matcher-group-options="${token}" data-input-meta="${metaToken(input)}">
          ${matcherGroupOptions(input, locator)}
        </div>
      </details>
    </section>`;
}

function matcherGroupEditor(operation, input, locator, group, index, size, scopeInputs, scopeBase) {
  const groupLocator = [...locator, index];
  const token = locatorToken(groupLocator);
  return `
    <article class="matcher-group" data-matcher-group="${index}">
      <div class="candidate-heading matcher-group-heading">
        ${size>1?`<strong>${index ? 'OR' : 'Match all'}</strong>`:''}
        ${orderedActions('matcher-group', index, size, `data-matcher-groups-locator="${locatorToken(locator)}"`)}
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
      <details class="matcher-add" id="${html(inputId(groupLocator))}-matcher"><summary>Add condition (AND)</summary>
      <input type="search" value="${html(queryAt(state.candidateQueries, groupLocator))}"
             data-candidate-query="${token}" data-input-meta="${metaToken(input)}"
             placeholder="Add AND matcher" autocomplete="off">
      <div data-candidate-options="${token}" data-input-meta="${metaToken(input)}">
        ${candidateOptions(input, groupLocator)}
      </div>
      </details>
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
  const sourceInput = candidateSourceInput(option);
  const optionInputs = option?.inputs.map(field => field === sourceInput ? {
    ...field,
    label: field.type === "path" && field.access === "read" ? "Read from"
      : field.type === "json" ? "Value" : field.label
  } : field);
  const candidateLocator = [...locator, index];
  const path = view.selectable === false ? "" : inputDiagnosticPath(operation.id, locator);
  const selected = Boolean(path) && state.preview?.selected_candidates?.[path] === index;
  const authored = input.authored_outcomes === true;
  const noun = authored ? "Case" : view.noun || "Value choice";
  const removable = size > (view.minimum || 0)
    && (!authored || candidate.outcome && outcomeTarget(operation, candidate.outcome) === "end");
  const predicateName = view.predicateName || input.name + "[" + index + "].when";
  const condition = conditionOf(candidate.when);
  const predicateStatus = !condition.transforms.length && !condition.all.length
    ? "value must be present"
    : condition.all.length
      ? `${condition.all.length} ${condition.all.length === 1 ? "matcher" : "matchers"} must pass`
      : "add comparison";
  return `
    <article class="candidate${!authored && view.selectable !== false && size === 1 ? ' single-candidate' : ''}${view.selectable === false ? ' matcher-row' : ''}${selected ? " selected-candidate" : ""}" data-candidate-index="${index}"
             ${path ? `data-candidate-path="${html(path)}"` : ""}>
      <div class="candidate-heading">
        ${!authored && size === 1 || view.selectable===false && index===0 ? '' : `<strong>${view.selectable === false ? 'AND' : `${html(noun)} ${index + 1}`}</strong>`}
        ${orderedActions('candidate', index, size, `data-candidate-locator="${locatorToken(locator)}" data-input-meta="${metaToken(input)}"`, removable)}
      </div>
      ${authored ? `
        <label for="${html(inputId(candidateLocator))}-label">Label</label>
        <input id="${html(inputId(candidateLocator))}-label" type="text"
               value="${html(outcomeLabel(operation,candidate.outcome,''))}" data-candidate-label="${locatorToken(locator)}"
               data-candidate-index="${index}" autocomplete="off">` : ""}
      <div class="candidate-source${option?.inputs.length===1 && option.inputs[0].type==='path' ? ' compact-source' : ''}">
      <label class="candidate-source-label" for="${html(inputId(candidateLocator))}-option">Source</label>
      <select id="${html(inputId(candidateLocator))}-option"
              data-candidate-option="${locatorToken(locator)}" data-candidate-index="${index}"
              data-input-meta="${metaToken(input)}">
        ${option ? "" : '<option value="" selected disabled>Choose a source</option>'}
        ${input.options.map(available => `
          <option value="${html(available.name)}" ${available.name === option?.name ? "selected" : ""}>
            ${html(candidateSourceLabel(available, input.options))}
          </option>`).join("")}
      </select>
      ${option ? inputFields(operation, optionInputs, [...candidateLocator, "inputs"]) : ""}
      </div>
      <${view.selectable===false?'section':'details'} class="candidate-condition" ${view.selectable!==false&&(condition.all.length || condition.transforms.length) ? 'open' : ''}>
        ${view.selectable===false?'':`<summary>${html(view.condition || "Accept when")}<span data-candidate-status
             data-candidate-default-status="${html(predicateStatus)}">${selected ? "Selected, " : ""}${
          html(predicateStatus)
        }</span></summary>`}
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
      </${view.selectable===false?'section':'details'}>
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
      <summary title="Calculate the comparison value without changing the context">Prepare comparison value${condition.transforms.length
        ? ": " + html(condition.transforms.map(step => stepName(definitionOf(step.use))).join(" / ")) : ""}</summary>
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
        )).join("") || '<p class="value-status">Value exists</p>'}
      </div>
      <details class="condition-add" id="${html(inputId(queryLocator))}-add"><summary>Add comparison (AND)</summary>
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
        <strong>${index ? 'AND' : 'Compare'}</strong>
        ${orderedActions('predicate', index, size, `data-condition-locator="${locatorToken(candidateLocator)}"`)}
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

function candidateSourceInput(option) {
  return option?.value_source?.scope === "owned" && option.inputs.length === 1
    ? option.inputs.find(input => input.name === option.value_source.input) : undefined;
}

function candidateSourceDescription(option) {
  const input = candidateSourceInput(option);
  if (input?.type === "path" && input.access === "read") return "Read a field";
  if (input?.type === "json") return "Enter a fixed value";
  return inputLabel(option.name);
}

function candidateSourceLabel(option, options) {
  const description = candidateSourceDescription(option);
  return options.some(other => other.name !== option.name && candidateSourceDescription(other) === description)
    ? `${inputLabel(option.name)}: ${description}` : description;
}

function candidateOptions(input, locator) {
  const query = queryAt(state.candidateQueries, locator).trim().toLowerCase();
  return input.options
    .filter(option => !query || option.name.toLowerCase().includes(query)
      || candidateSourceLabel(option, input.options).toLowerCase().includes(query))
    .map(option => `
      <button type="button" class="catalog-option compact-option"
              data-add-candidate="${html(option.name)}" data-candidate-locator="${locatorToken(locator)}"
              data-input-meta="${metaToken(input)}">
        <strong>${html(candidateSourceLabel(option, input.options))}</strong>
        <small>${html(option.name)}</small>
      </button>`).join("") || '<p class="empty-options">No value source matches.</p>';
}

function matcherGroupOptions(input, locator) {
  const queryLocator = [...locator, "new-group"];
  const query = queryAt(state.candidateQueries, queryLocator).trim().toLowerCase();
  return input.options
    .filter(option => !query || option.name.toLowerCase().includes(query)
      || candidateSourceLabel(option, input.options).toLowerCase().includes(query))
    .map(option => `
      <button type="button" class="catalog-option compact-option"
              data-add-matcher-group="${html(option.name)}"
              data-matcher-groups-locator="${locatorToken(locator)}"
              data-input-meta="${metaToken(input)}">
        <strong>${html(candidateSourceLabel(option, input.options))}</strong>
        <small>${html(option.name)}</small>
      </button>`).join("") || '<p class="empty-options">No value source matches.</p>';
}

function jsonInput(operation, input, locator, value) {
  const present = hasAt(operation, locator);
  const id = inputId(locator);
  const path = inputDiagnosticPath(operation.id, locator);
  const issues = allDiagnostics().filter(issue => issue.path === path && diagnosticOwner(issue) === operation.id);
  const feedback = issues.length ? `<p id="${html(id)}-error" class="input-error" role="alert">${
    issues.map(issue => html(issue.message)).join(" ")}</p>` : "";
  if (!input.required && !present) {
    return `
      <section class="inspector-section compact" data-input-name="${html(input.name)}">
        <label class="check-line">
          <input id="${html(id)}-present" type="checkbox" data-toggle-input="${locatorToken(locator)}"
                 data-input-meta="${metaToken(input)}">
          <span>${input.inherited ? "Override " : "Use "}${html(inputLabel(input.name).toLowerCase())}</span>
        </label>
        ${feedback}
      </section>`;
  }
  const optional = !input.required ? `
    <label class="check-line" id="${html(id)}-label">
      <input id="${html(id)}-present" type="checkbox" checked
             data-toggle-input="${locatorToken(locator)}" data-input-meta="${metaToken(input)}" aria-label="Use ${html(inputLabel(input.name).toLowerCase())}">
      <span>${html(inputLabel(input.name))}</span>
    </label>` : "";
  const attributes = `id="${html(id)}-value" ${input.required ? 'aria-required="true"' : ''} data-input-json="${locatorToken(locator)}"
                      data-input-meta="${metaToken(input)}"${!input.required?` aria-labelledby="${html(id)}-label"`:''}${issues.length
    ? ` aria-invalid="true" aria-describedby="${html(id)}-error"` : ""}`;
  let control;
  if (input.shape === "string") {
    control = `<input type="text" ${attributes} value="${html(value ?? "")}">`;
  } else if (input.shape === "number") {
    const minimum = Object.hasOwn(input, "minimum")
      ? ` min="${html(numberText(input.minimum))}"` : "";
    const maximum = Object.hasOwn(input, "maximum")
      ? ` max="${html(numberText(input.maximum))}"` : "";
    control = `<input type="number" step="${["Integer", "Long"].includes(input.validation) ? "1" : "any"}" ${attributes}${minimum}${maximum}
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
      ${input.required?`<label for="${html(id)}-value">${html(input.label || inputLabel(input.name))}</label>`:''}
      ${control}
      ${feedback}
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
    <details class="program-add" id="${html(id)}-add"><summary>${predicate && steps.length && shape === 'boolean' ? 'Extend comparison' : action}</summary>
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
    </div></details>`;
}

function nestedStep(operation, input, locator, step, index, size) {
  const definition = definitionOf(step.use);
  const previewInput = programPath(locator);
  const comparison = input.program_role === 'predicate' && size === 1;
  const choices = comparison ? matcherDefinitions(input.program_shape) : [];
  return `
    <div class="nested-step" data-setting-path="${html(inputDiagnosticPath(operation.id,[...locator,index]))}" tabindex="-1">
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
      ${comparison ? '' : orderedActions('nested', index, size, `data-program-locator="${locatorToken(locator)}"`)}
    </div>`;
}

function orderedActions(kind, index, size, scope, removable = true) {
  return `<div class="row-actions">${(size > 1 ? [[-1,'up','Move up'],[1,'down','Move down']] : []).map(([direction,icon,label]) =>
    `<button type="button" data-move-${kind}="${index}" data-direction="${direction}" ${scope}
      aria-label="${label}" title="${label}" ${(direction < 0 ? index === 0 : index === size-1) ? 'disabled' : ''}>
      <i class="hud-symbol" data-symbol="${icon}" aria-hidden="true"></i></button>`).join('')}
    <button type="button" data-remove-${kind}="${index}" ${scope} aria-label="Remove" title="Remove" ${removable ? '' : 'disabled'}>
      <i class="hud-symbol" data-symbol="trash" aria-hidden="true"></i></button></div>`;
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
  const manualRead = !writable && !input.port;
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
  const fallback=defaultInput(input);
  const resettable=input.resettable!==false && fallback!==undefined && hasAt(operation,locator)
    && !samePath(valueAt(operation,locator),fallback);
  const selected = entries.find(entry => samePath(entry.path, draft));
  const usable = writable || manualRead
    ? draft.length >= 2
    : selectable.some(entry => samePath(entry.path, draft));
  const children = entries.filter(entry =>
    entry.path.length === draft.length + 1
    && draft.every((part, index) => part === entry.path[index])
  );
  const canAddField = manualRead || writable && (!selected || selected.shape === "object");
  const canAddIndex = draft.length >= 2
    && (manualRead || writable && (!selected || selected.shape === "array"));
  const unverified = manualRead && usable && !selected;
  const fieldValid = validPathField(state.pathField, draft);
  const indexValid = validPathIndex(state.pathIndex);
  const choicesLabel = selected?.shape === "array"
    ? "Choose one array index"
    : selected?.shape === "object" ? "Choose one field" : "Choose the next path segment";
  return `
    <div class="path-browser" role="group" aria-label="Choose a field path">
      <div class="path-builder">
        <div class="path-crumbs" aria-label="Selected path">
          <small class="path-crumbs-label">Selected path</small>
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
                  entry.examples < entry.total ? ` · observed here in ${entry.examples} of ${entry.total} Examples` : ""
                }</small></span>${pathChoiceValue(operation, entry.path)}
              </button>`;
          }).join("") || `<small class="path-hint">${
            selected ? "No deeper fields observed here." : "No fields observed here."
          }</small>`}
        </div>
        ${canAddField || canAddIndex ? `
          <div class="path-create">
            <small class="path-create-label">${manualRead ? "Enter a path segment" : "Create a destination path"}</small>
            ${canAddField ? `
              <input id="new-path-field" value="${html(state.pathField)}"
                     placeholder="Field name" aria-label="Field name" autocomplete="off">
              <button id="append-path-field" type="button" ${fieldValid ? "" : "disabled"}>${manualRead ? "Append field" : "Add field"}</button>` : ""}
            ${canAddIndex ? `
              <input id="new-path-index" type="number" min="0" max="2147483647" step="1"
                     value="${html(state.pathIndex)}"
                     aria-label="Array index">
              <button id="append-path-index" type="button" ${indexValid ? "" : "disabled"}>${manualRead ? "Append index" : "Use index"}</button>` : ""}
          </div>` : ""}
        <div class="path-preview">
          ${pathObservation(operation, input, locator)}
          <small class="path-hint" data-path-verification ${unverified ? "" : "hidden"}>${unverified
            ? "Path not verified by an Example. You can still use it." : ""}</small>
        </div>
        <div class="path-actions" role="group" aria-label="${html(inputLabel(input.name))} actions">
          <div class="path-action-buttons">
            ${resettable ? `<button class="path-reset" type="button" data-reset-path="${locatorToken(locator)}" data-input-meta="${metaToken(input)}">Reset</button>` : ''}
            <button class="path-cancel" id="cancel-path" type="button">Cancel</button>
            <button id="apply-path" type="button" ${usable ? "" : "disabled"}>Use this path</button>
          </div>
        </div>
      </div>
    </div>`;
}

function picker() {
  if (!state.picker) {
    return "";
  }
  if (state.picker.mode === 'arrival') {
    const arrivals=state.picker.arrivals.map(link=> {
      const source=state.world.scene.nodes.find(item=>item.id===link.from);
      const owner=source?.regions?.map(id=>state.world.scene.nodes.find(item=>item.id===id && (item.group || item.summary==='area')))
        .filter(Boolean).map(item=>item.name).reverse().join(' / ');
      const definition=definitionOf(source?.use || node(link.from)?.use);
      return {...link,name:source?.name || stepPresentation(link.from).name || (definition ? stepName(definition) : 'Incoming route'),
        region:source?.kind==='region',locate:!source,
        context:owner || (definition ? stepName(definition) : '')};
    });
    const choices=arrivals.map((link,index)=> {
      const matching=arrivals.filter(item=>item.name===link.name && item.outcome===link.outcome);
      const repeated=matching.filter(item=>item.context===link.context).length>1;
      const context=matching.length>1 ? [repeated ? `Route ${index+1}` : '',link.context].filter(Boolean).join(' / ') : '';
      const detail=[link.locate ? 'Locate' : '',link.region ? 'Open group' : outcomeLabel({id:link.from},link.outcome),context].filter(Boolean).join(' / ');
      return `<button type="button" class="catalog-option" data-insert-arrival="${html(link.from+'.'+link.outcome)}">
        <strong>${html(link.name)}</strong><small title="${html(detail)}">${html(detail)}</small></button>`;
    }).join('');
    return `
    <div class="construction-palette arrival-palette">
      <section class="step-picker" role="dialog" aria-label="Choose incoming route">
        <header><h2>${arrivals.length>1 ? 'From which route?' : arrivals[0].locate ? 'Locate route' : 'Open group'}</h2><button type="button" data-close-picker aria-label="Close Step chooser">Close</button></header>
        <div class="arrival-options">${choices}</div>
      </section>
    </div>`;
  }
  const isTrigger = state.picker.mode === 'trigger';
  return `
    <div class="construction-palette">
      <section class="step-picker" role="dialog" aria-label="${isTrigger ? 'Add Trigger' : 'Insert Step or Group'}">
        <header><div><span class="eyebrow">${html(stepPresentation(state.picker.anchor).name || stepName(definitionFor(node(state.picker.anchor))))}</span><h2>${isTrigger ? 'Add Trigger' : 'Insert'}</h2></div><button type="button" data-close-picker aria-label="Close Step chooser">Close</button></header>
        <div class="picker-searches">
          <label for="step-search">${isTrigger ? 'Trigger' : 'Step'}<input type="search" id="step-search" value="${html(state.picker.queries.step)}"
                 placeholder="Search ${isTrigger ? 'triggers' : 'steps'}" autocomplete="off" aria-controls="step-options" autofocus></label>
          ${!isTrigger && Number(state.editor.group_count) > 0 ? `<label for="group-insert-search">Group<input type="search" id="group-insert-search"
                 value="${html(state.picker.queries.group)}" placeholder="Search groups" autocomplete="off" aria-controls="step-options"></label>` : ''}
        </div>
        <div id="step-options">${pickerOptions()}</div>
      </section>
    </div>`;
}

function groupPicker() {
  if (!state.groupPicker) {
    return "";
  }
  return `
      <dialog id="group-dialog" class="step-picker group-picker" aria-label="Choose group">
        <header class="picker-heading">
          <div><span class="eyebrow">Creator groups</span><h2>Choose Group</h2></div>
          <button type="button" id="close-group-picker">Cancel</button>
        </header>
        <input type="search" id="group-picker-search" value="${html(state.groupPicker.query)}"
               placeholder="Search groups" autocomplete="off" autofocus>
        <div id="group-picker-options">${groupPickerOptions()}</div>
      </dialog>`;
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
  const picker = state.picker;
  const chosen = picker.chosen;
  if (chosen) return `<div class="reuse-boundaries"><strong>${html(chosen.label || chosen.name)}</strong>${[['entries','Entrance'],['exits','Continuation']].map(([field,label]) => chosen[field].length > 1
    ? `<label>${label}<select data-reuse-boundary="${field}">${chosen[field].map(item => `<option value="${html(item.id)}">${html(item.name)}</option>`).join('')}</select></label>` : '').join('')}
    <div class="reuse-actions"><button type="button" data-apply-reuse="reuse">Use linked</button><button type="button" data-apply-reuse="copy">Place copy</button><button type="button" data-cancel-reuse>Back</button></div></div>`;
  const query = picker.queries[picker.kind].toLowerCase();
  const reusable = (picker.items || []).map(item =>
    `<button type="button" class="catalog-option" data-reuse-item="${html(item.id)}" data-reuse-type="${item.kind}" ${item.entries.length>1?'disabled':''}><strong>${html(item.label || item.name)}</strong><small>${item.entries.length>1 ? `${item.entries.length} entrances, placement not yet supported` : `Existing ${item.kind}`}</small></button>`).join('');
  const pages = picker.matches > picker.pageSize ? `<nav class="picker-pages" aria-label="Reuse pages"><button type="button" data-reuse-page="${Math.max(0,(picker.offset || 0)-picker.pageSize)}" ${picker.offset ? '' : 'disabled'}>Previous</button><button type="button" data-reuse-page="${(picker.offset || 0)+picker.pageSize}" ${(picker.offset || 0)+(picker.items?.length || 0)<picker.matches ? '' : 'disabled'}>Next</button></nav>` : '';
  if (picker.kind === 'group') return (reusable || (picker.loading ? '<p role="status">Loading...</p>' : '<p class="empty-options">No group matches.</p>')) + pages;
  const candidates = state.picker.mode === "trigger"
    ? availableTriggers()
    : addableDefinitions();
  const matching = candidates
    .filter(definition => definitionMatchesQuery(definition, query));
  const options = matching.filter(definition => state.picker.mode === "trigger"
      || automaticBindingsAvailable(state.picker.anchor, definition));
  const catalog = options
    .map(definition => `
      <button type="button" class="catalog-option" data-add-step="${html(definition.id)}" title="${html(definition.id)}">
        <strong>${html(stepName(definition))}</strong>
        <small>${definition.receives.length || definition.returns.length
          ? `${html(definition.receives.map(port => port.shape).join(" + ") || "context")} to ${html(
              definition.returns.map(port => port.shape).join(" + ") || "context"
            )}` : html(inputLabel(definition.kind))}</small>
      </button>`).join("");
  if (picker.mode === 'trigger') return catalog || '<p class="empty-options">No installed Trigger matches.</p>';
  return (catalog ? `<div class="picker-results-label">New Steps</div>${catalog}` : '')
    + (reusable ? `<div class="picker-results-label">Existing Steps</div>${reusable}` : '')
    + (picker.loading ? '<p role="status">Loading...</p>' : '') + pages
    || '<p class="empty-options">No Step matches.</p>';
}

function openPicker(mode, anchor, outcome = "") {
  state.picker = { mode, anchor, outcome, kind: 'step', queries: {step:'',group:''}, returnFocus:document.activeElement };
  state.pathPicker = "";
  state.pathDraft = [];
  render();
  document.querySelector("#step-search")?.focus();
  if (mode !== 'trigger') void loadReusable();
}

async function openBeltPicker(id, outcome, arrivals = []) {
  const source=state.world.scene.nodes.find(item=>item.id===id);
  if (arrivals.length > 1 || arrivals.length===1 && (!source || source.kind==='region')) {
    state.picker?.controller?.abort();
    state.picker = {mode:'arrival',arrivals,returnFocus:document.activeElement};
    render();
    document.querySelector('[data-insert-arrival]')?.focus({preventScroll:true});
    return;
  }
  if (!source || source.kind==='region') {
    closePicker();
    if (source) state.world.enter(source.id);
    else state.world.focus(id);
    return;
  }
  const previous = state.picker;
  if (await loadEditor(id) !== 'loaded' || state.picker !== previous) return;
  const returnFocus=previous?.returnFocus || document.activeElement;
  state.selection={type:definitionFor(node(id)).kind,id};
  state.regionSelection=null;
  openPicker('step',id,outcome);
  state.picker.returnFocus=returnFocus;
  state.picker.arrival={from:id,outcome};
  state.world?.repaint();
}

function closePicker() {
  const previous = state.picker?.returnFocus;
  state.picker?.controller?.abort();
  state.picker = null;
  render();
  (previous?.isConnected ? previous : document.getElementById(previous?.id) || document.querySelector('#add-next-step') || document.querySelector('#graph'))
    ?.focus({preventScroll:true});
}

function replacePickerOptions() {
  const slot = document.querySelector('#step-options');
  if (!slot) return;
  const focused = slot.contains(document.activeElement) ? document.activeElement : null;
  const identity = focused && ['addStep','reuseItem','reusePage','applyReuse','cancelReuse']
    .find(key => Object.hasOwn(focused.dataset,key));
  slot.innerHTML = pickerOptions();
  if (identity) {
    const replacement = [...slot.querySelectorAll('button')]
      .find(button => button.dataset[identity] === focused.dataset[identity]);
    (replacement || document.getElementById(state.picker.kind === 'group' ? 'group-insert-search' : 'step-search'))
      ?.focus({preventScroll:true});
  }
}

async function loadReusable() {
  const picker = state.picker;
  if (!picker || picker.mode === 'trigger') return;
  const kind = picker.kind;
  const request=picker.request=(picker.request || 0)+1;
  picker.controller?.abort();
  picker.controller=new AbortController();
  picker.loading = true;
  try {
    const response = await fetch(`/api/editor?${new URLSearchParams({reuse:'1',reuse_kind:kind,q:picker.queries[kind],offset:picker.offset || 0})}`,{signal:picker.controller.signal});
    const payload = parseExact(await response.text());
    if (state.picker !== picker || request !== picker.request || picker.kind !== kind) return;
    picker.items = response.ok ? payload.editor.reusable : [];
    picker.matches = response.ok ? payload.editor.reuse_matches : 0;
    picker.pageSize = response.ok ? payload.editor.reuse_page_size : 0;
  } catch(error) {
    if(error.name==='AbortError') return;
    picker.items=[];
    picker.matches=0;
    picker.pageSize=0;
  } finally {
    if(state.picker===picker && request===picker.request && picker.kind===kind) {
      picker.loading=false;
      replacePickerOptions();
    }
  }
}

async function placeReuse(placement) {
  const picker = state.picker, item = picker?.chosen;
  if (!item) return;
  const result = await editorAction({kind:placement, [item.kind === 'group' ? 'group' : 'node']:item.id,
    from:picker.anchor + '.' + (picker.outcome || primaryOutcome(node(picker.anchor))), id:opaqueId('use'),
    entry:document.querySelector('[data-reuse-boundary="entries"]')?.value || item.entries[0]?.id || '',
    exit:document.querySelector('[data-reuse-boundary="exits"]')?.value || item.exits[0]?.id || ''});
  if (result?.selection) {
    document.querySelector('#inspector-navigation button')?.focus({preventScroll:true});
  }
}

async function previewGroup(range) {
  const members = state.selectedSteps.size ? [...state.selectedSteps] : [state.selection.id];
  const command = {kind:'group',id:state.groupDraft?.command.id || opaqueId('group'),nodes:members};
  if (range && members.length === 2) { command.start=members[0]; command.end=members[1]; command.nodes=[]; }
  const preview = await editorAction(command,true);
  if (!preview) return;
  state.groupDraft={command,nodes:Object.keys(preview.creator_changes.steps || {})};
  render();
  const form = document.querySelector('#group-draft');
  form?.scrollIntoView({block:'end'});
  form?.querySelector('input')?.focus({preventScroll:true});
  state.world?.repaint();
}

function previewCatalogStep(event) {
  if (state.picker?.mode === 'arrival') {
    const option=event.target.closest('[data-insert-arrival]') || document.activeElement?.closest('[data-insert-arrival]');
    const arrival=state.picker.arrivals.find(link=>link.from+'.'+link.outcome===option?.dataset.insertArrival);
    if (state.picker.arrival !== arrival) {
      state.picker.arrival=arrival;
      state.world?.repaint();
    }
    return;
  }
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
document.addEventListener('focusin', event => {
  const picker = state.picker;
  if (!picker || !['step-search','group-insert-search'].includes(event.target.id)) return;
  const kind = event.target.id === 'step-search' ? 'step' : 'group';
  if (picker.kind !== kind) {
    picker.kind = kind;
    picker.chosen = null;
    picker.items = [];
    picker.matches = 0;
    picker.pageSize = 0;
    picker.offset = 0;
    picker.loading = true;
    replacePickerOptions();
    void loadReusable();
  } else if (picker.chosen) {
    picker.chosen = null;
    replacePickerOptions();
    event.target.select();
  }
});

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
  state.inspectorMode = "inspect";
  dirty(true);
  document.querySelector('#inspector-navigation button')?.focus({preventScroll:true});
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
  const group = stepPresentation(afterId).group;
  if (group && group === stepPresentation(target).group) state.creator.steps[id] = {...stepPresentation(id), group};
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
  if (state.selection.type === 'step') {
    const id=state.selection.id;
    if(state.build === 'Not built' || state.jsonDraft?.node === id) {
      const accepted=state.builtProject.nodes.find(item=>item.id===id);
      if(accepted) {
        state.project.nodes=state.project.nodes.map(item=>item.id===id?clone(accepted):item);
        if(state.jsonDraft?.node===id) state.jsonDraft=null;
        state.build='Built';
        state.pendingProject=false;
      } else {
        const incoming=state.project.links.filter(link=>link.to===id);
        const next=outcomeTarget(node(id),primaryOutcome(node(id)));
        incoming.forEach(link=>link.to=next);
        state.project.links=state.project.links.filter(link=>linkNode(link)!==id);
        state.project.nodes=state.project.nodes.filter(item=>item.id!==id);
        delete state.creator.steps[id]; state.jsonDraft=null;
        state.selection={type:'step',id:incoming.length?linkNode(incoming[0]):'app'};
        dirty(); return;
      }
    }
    void editorAction({kind:'delete',node:id}); return;
  }
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
  } else {
    return;
  }
  state.revealNode = state.selection.id;
  dirty(true);
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
    dirty(true, {keepForm:true});
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
  dirty(true, {keepForm:true});
}

function selectExample(index, trigger = node(state.selection.id), {deferObservation = false} = {}) {
  cancelAttention();
  const sourceChanged = state.exampleTrigger !== trigger.id;
  state.exampleTrigger = trigger.id;
  state.exampleIndex = Math.max(-1, Math.min(index, (trigger.examples?.length ?? state.exampleIds.get(trigger.id)?.size ?? 0) - 1));
  state.application.example = state.exampleIds.get(trigger.id)?.get(state.exampleIndex) || null;
  state.exampleDraft = null;
  state.applicationController?.abort('selection');
  clearPreview(true, false);
  render();
  scheduleApplicationPoll(0);
  if (deferObservation) return Promise.resolve();
  const trace = requestSelectedTrace();
  if (sourceChanged && !state.editorController && !state.regionSelection && state.selection.id
      && state.editor.sources?.some(source => source.id === trigger.id)) {
    const selected = state.selection.id;
    void loadEditor(selected).then(result => {
      if (result === 'loaded' && state.selection.id === selected && !state.regionSelection) {
        render();
        void requestSelectedTrace();
      }
    });
  }
  return trace;
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
  state.pathDraft = clone(valueAt(operation, locator) || defaultInput(input) || ["context"]);
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
  return Boolean(field) && (draft.length > 0 || field === "context")
    && !(draft.length === 1 && field === "runtime" && state.pathPicker.input.access !== "read");
}

function validPathIndex(source) {
  return /^(0|[1-9]\d*)$/.test(source) && Number(source) <= 2147483647;
}

function applyPath() {
  const operation = editableOperation(state.pathPicker.locator);
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
    setAt(editableOperation(locator), locator, clone(fallback));
  } else {
    deleteAt(editableOperation(locator), locator);
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
    setAt(editableOperation(locator), locator, value);
    state.jsonDraft = null;
    state.localDiagnostics = [];
    dirty(true, {keepForm:true});
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
  const operation = editableOperation(locator);
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
  setAt(editableOperation(locator), locator, {
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
  const operation = editableOperation(locator);
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
  let groups = valueAt(editableOperation(locator), locator);
  if (!Array.isArray(groups)) {
    groups = [];
    setAt(editableOperation(locator), locator, groups);
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
  const candidates = valueAt(editableOperation(locator), locator);
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
  creatorDirty({keepForm:true});
}

function moveListItem(locator, index, direction) {
  const items = valueAt(editableOperation(locator), locator);
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
  const items = valueAt(editableOperation(locator), locator);
  if (!Array.isArray(items) || index < 0 || index >= items.length) {
    return;
  }
  if (input.authored_outcomes) {
    const operation = editableOperation(locator);
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
  const steps = programAt(editableOperation(locator), locator);
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
  const candidate = valueAt(editableOperation(locator), locator);
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
  const candidate = valueAt(editableOperation(locator), locator);
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
  const candidate = valueAt(editableOperation(locator), locator);
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
  const steps = programAt(editableOperation(locator), locator);
  const target = index + direction;
  if (target < 0 || target >= steps.length) {
    return;
  }
  state.jsonDraft = null;
  [steps[index], steps[target]] = [steps[target], steps[index]];
  dirty();
}

function removeNested(locator, index) {
  const steps = programAt(editableOperation(locator), locator);
  if (!Array.isArray(steps) || index < 0 || index >= steps.length) {
    return;
  }
  steps.splice(index, 1);
  compactCondition(editableOperation(locator), locator);
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
  state.traceChanges = null;
  state.traceReadError = null;
  state.regionTrace = null;
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
    cancelAttention();
    clearPreview(true);
    state.observationController?.abort();
    state.observations = null;
    document.querySelector("#status-observations").hidden = true;
    scheduleWorldObservations();
    state.exampleIds = new Map();
    state.exampleFailures = [];
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
  const controller = state.applicationController = new AbortController();
  const timeout = setTimeout(() => controller.abort('timeout'), 30_000);
  const signal = controller.signal;
  try {
    const response = await fetch("/api/application", { cache: "no-store", signal });
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
        fetch("/api/examples/status", { cache: "no-store", signal }),
        cachedExampleId
          ? fetch(`/api/examples/${encodeURIComponent(cachedExampleId)}`, { cache: "no-store", signal })
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
      const inventoryReady = await refreshExampleIds(application,signal,examples);
      if (!inventoryReady) {
        nextDelay = observationRetryDelay();
        return;
      }
      await refreshExampleScope(application, signal, examples);
      const coverageReady = await refreshExampleCoverage(application, examples,signal);
      if (!coverageReady) {
        nextDelay = observationRetryDelay();
        return;
      }
      const exampleId = selectedExampleId();
      const selectedResponse = exampleId === cachedExampleId
        ? exampleResponse
        : exampleId
          ? await fetch(`/api/examples/${encodeURIComponent(exampleId)}`, { cache: "no-store", signal })
          : null;
      if (selectedResponse && !selectedResponse.ok) {
        nextDelay = observationRetryDelay();
        return;
      }
      const example = selectedResponse ? parseExact(await selectedResponse.text()) : null;
      if (!currentApplication(application)
          || selectedExampleId() !== exampleId
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
    // A cancelled selection or unavailable snapshot must not hold the next Example.
    nextDelay = signal.reason === 'selection' ? 25 : observationRetryDelay();
  } finally {
    clearTimeout(timeout);
    state.applicationController = null;
    state.applicationRefreshing = false;
    renderAttention();
    if (state.optionsPending) {
      state.optionsPending = false;
      refreshPickerOptions();
      if (state.pathPicker) refreshPathPicker();
      else refreshNestedOptions();
    }
    const running = ["queued", "running"].includes(state.application.examples?.state);
    scheduleApplicationPoll(nextDelay ?? (document.hidden ? 2_000 : running ? 100 : 500));
  }
}

async function refreshExampleIds(application, signal, examples) {
  const key = JSON.stringify([application.fingerprint || "", application.pid || 0,
    examples?.revision, examples?.state]);
  if (state.exampleInventoryKey === key) {
    return true;
  }
  const response = await fetch("/api/examples", { cache: "no-store", signal });
  if (!response.ok) {
    if (currentApplication(application)) {
      state.exampleIds = new Map();
      state.exampleFailures = [];
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
    trigger.set(Number(example.index), example);
    ids.set(example.trigger, trigger);
  });
  state.exampleIds = ids;
  state.exampleFailures = inventory.state === 'completed'
    ? [...ids.values()].flatMap(choices => [...choices.values()]).filter(example => failedRunStates.has(example.status)) : [];
  state.exampleInventoryKey = key;
  return true;
}

async function refreshExampleCoverage(application, examples, signal) {
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
  const response = await fetch("/api/examples/coverage", { cache: "no-store", signal });
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
  return state.exampleIds.get(state.exampleTrigger)?.get(state.exampleIndex)?.id || "";
}

function refreshApplicationFacts() {
  renderExampleContext();
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
    renderDockMarkup(panel, metricsPanel());
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
  if (!facts) return "";
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
  if (state.build === "Building" || !state.application.pid || state.jsonDraft || state.exampleDraft
      || state.editorController && state.editorController.node !== state.selection.id) {
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
  if (state.regionSelection) return requestRegionTrace(example);
  const summaryKey = exampleSummaryKey(example);
  const operation = ["trigger", "step"].includes(state.selection.type)
    ? selectedOperation()
    : null;
  const observation = operation ? observationFor(operation) : null;
  if (!observation) {
    if (state.traceSummaryKey === summaryKey && state.traceSummary || state.traceKey === summaryKey
        && (state.traceController || state.traceReadError?.key === summaryKey)) return;
    state.traceController?.abort();
    const controller = state.traceController = new AbortController();
    state.traceKey = summaryKey;
    try {
      const summary = await readExampleProjection(`/api/examples/${encodeURIComponent(example.id)}/view`, controller.signal);
      if (controller.signal.aborted || state.traceController !== controller || selectedExampleId() !== example.id
          || exampleSummaryKey(selectedTraceCase() || {}) !== summaryKey) return;
      if (Number(summary.application_pid) !== Number(state.application.pid) || !Array.isArray(summary.nodes))
        throw new Error('Example summary is from another application.');
      summary.nodes = new Set(summary.nodes || []);
      state.traceSummary = summary;
      state.traceSummaryKey = summaryKey;
      state.traceReadError = null;
      refreshTraceView();
    } catch (error) {
      if (error.name !== 'AbortError' && state.traceController === controller) {
        state.traceReadError = {key:summaryKey, message:error.message};
        renderExampleContext();
      }
    } finally {
      if (state.traceController === controller) state.traceController = null;
    }
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
  if (state.traceKey === key && (state.traceController || state.traceReadError?.key === summaryKey
      || state.traceSummary && state.traceCasesKey === casesKey)) {
    return;
  }
  state.traceController?.abort();
  const controller = new AbortController();
  state.traceController = controller;
  state.traceKey = key;
  const applicationPid = Number(state.application.pid);
  try {
    const summary = state.traceSummaryKey === summaryKey && state.traceSummary
      ? state.traceSummary
      : await readExampleProjection(
        `/api/examples/${encodeURIComponent(example.id)}/view`,
        controller.signal
      );
    if (controller.signal.aborted || state.traceController !== controller || state.traceKey !== key) return;
    if (Number(summary.application_pid) !== applicationPid
        || !(summary.nodes instanceof Set) && !Array.isArray(summary.nodes)) {
      throw new Error("Example summary is from another application.");
    }
    summary.nodes = summary.nodes instanceof Set
      ? summary.nodes
      : new Set(summary.nodes.filter(Number.isInteger));
    state.traceSummary = summary;
    state.traceSummaryKey = summaryKey;
    const stepCases = state.traceCasesKey === casesKey
      ? {
        application_pid: state.traceCasesPid,
        node: observation.node,
        cases: state.traceCases
      }
      : await readExampleProjection(`/api/examples/steps/${observation.node}`, controller.signal);
    if (!Array.isArray(stepCases.cases)
        || Number(stepCases.application_pid) !== applicationPid
        || Number(stepCases.node) !== observation.node) {
      throw new Error("Built Example projection is from another application process.");
    }
    if (controller.signal.aborted || state.traceController !== controller || state.traceKey !== key) {
      return;
    }
    state.traceReadError = null;
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
    state.traceChanges = state.traceStep && plainObject(state.traceStep.input_context) && plainObject(state.traceStep.context)
      ? contextChanges(state.traceStep.input_context, state.traceStep.context) : null;
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
    state.traceReadError = {key:summaryKey, message:error.message};
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
    state.runResult = exampleRunResult(example);
    refreshTraceView();
  }
}

async function requestRegionTrace(example) {
  const region = state.regionSelection, revision = state.world?.scene?.revision;
  if (!region || !revision) return;
  const summaryKey = exampleSummaryKey(example), key = `${summaryKey}:${revision}:${region.id}`;
  if (state.regionTrace?.key === key) return;
  state.traceController?.abort();
  const controller = state.traceController = new AbortController();
  state.regionTrace = {key, region:region.id, example:example.id, kind:'pending', status:'Loading Example'};
  renderDockObservation();
  const current = () => !controller.signal.aborted && state.traceController === controller
    && state.regionSelection?.id === region.id && selectedExampleId() === example.id
    && exampleSummaryKey(selectedTraceCase() || {}) === summaryKey && state.world?.scene?.revision === revision;
  try {
    const query = new URLSearchParams({revision, region:region.id});
    const summary = await readExampleProjection(`/api/examples/${encodeURIComponent(example.id)}/view?${query}`, controller.signal);
    const section = summary.section;
    if (!current()) return;
    if (Number(summary.application_pid) !== Number(state.application.pid)
        || Number(section?.application_pid) !== Number(state.application.pid) || section.region !== region.id
        || section.revision !== revision || !Array.isArray(section.passages)) throw new Error('Section observation is stale.');
    summary.nodes = summary.nodes instanceof Set ? summary.nodes : new Set(summary.nodes || []);
    state.traceSummary = summary;
    state.traceSummaryKey = summaryKey;
    state.traceReadError = null;
    const visit = section.passages[0];
    if (!visit) {
      state.regionTrace = {key, region:region.id, example:example.id, kind:'unreached', status:'Not reached by this Example'};
    } else {
      const entry = await readExampleProjection(`/api/examples/${encodeURIComponent(example.id)}/steps/${visit.entry.index}`, controller.signal);
      const exit = visit.exit.index === visit.entry.index ? entry
        : await readExampleProjection(`/api/examples/${encodeURIComponent(example.id)}/steps/${visit.exit.index}`, controller.signal);
      if (!current()) return;
      const failed = failedRunStates.has(exit.status);
      if ([entry,exit].some(value => Number(value.application_pid) !== Number(state.application.pid))
          || !plainObject(entry.input_context) || !failed && !plainObject(exit.context)) throw new Error('Section values are unavailable.');
      state.regionTrace = {key, region:region.id, example:example.id, passages:section.passages, index:0,
        kind:failed ? 'failed' : 'succeeded', progress:`${section.reached}/${section.count}`,
        status:failed ? `Stopped at ${visit.exit.name}, ${section.reached}/${section.count} reached` : `${section.reached}/${section.count} reached`,
        changes:failed ? {before:entry.input_context} : contextChanges(entry.input_context, exit.context)};
    }
    refreshTraceView();
  } catch (error) {
    if (current()) {
      state.regionTrace = {key, region:region.id, example:example.id, kind:'unavailable', status:error.message || 'Example data unavailable'};
      state.traceReadError = {key:summaryKey, message:state.regionTrace.status};
      renderExampleContext();
      renderDockObservation();
    }
  } finally {
    if (state.traceController === controller) state.traceController = null;
  }
}

async function readExampleProjection(path, signal) {
  const response = await fetch(path, { cache: "no-store", signal });
  const source = await response.text();
  if (!response.ok) {
    const failure = parseExact(source);
    throw new Error(response.status === 409 ? 'Example changed. Retry after the update.'
      : response.status === 413 ? 'Example values exceed the display limit.'
      : response.status === 503 ? 'Application is updating or busy. Retry when ready.'
      : failure.message || 'Recorded Example values could not be read.');
  }
  const projection = parseExact(source);
  if (!plainObject(projection)) {
    throw new Error("Built example view is invalid.");
  }
  return projection;
}

function selectedTraceCase() {
  if (state.exampleIndex < 0) return null;
  const example = state.application.example;
  return example?.id === selectedExampleId() ? example : null;
}

function selectTracePreview(example = selectedTraceCase()) {
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
  state.runResult = exampleRunResult(example);
  refreshTraceView();
}

function exampleRunResult(example) {
  const summary = state.traceSummaryKey === exampleSummaryKey(example || {}) ? state.traceSummary : null;
  if (plainObject(summary?.result)) return JSON.stringify({example:example?.name, ...summary.result}, null, 2);
  if (example && !["succeeded", "running", "queued"].includes(example.status)) {
    return JSON.stringify({
      example: example.name,
      status: example.status,
      ...(example.message ? { message: example.message } : {})
    }, null, 2);
  }
  return "";
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
  renderExampleContext();
  updateWorldMarks(state.world?.scene);
  applyExampleCoverage();
  if (state.pathPicker) {
    renderBuildStatus();
    refreshPathPicker();
    return;
  }
  for (const slot of document.querySelectorAll("[data-issues-owner]")) {
    const markup = issueList(slot.dataset.issuesOwner);
    const template = document.createElement("template");
    template.innerHTML = markup;
    for (const open of slot.querySelectorAll('details[open][id]')) {
      const matching=[...template.content.querySelectorAll('details[id]')].find(detail=>detail.id===open.id);
      if(matching)matching.open=true;
    }
    const comparable=slot.cloneNode(true);
    for(const article of comparable.querySelectorAll('article[hidden]'))article.removeAttribute('hidden');
    comparable.hidden=!comparable.children.length;
    if (!comparable.isEqualNode(template.content.firstElementChild)) slot.replaceWith(template.content.firstElementChild);
  }
  refreshPickerOptions();
  renderPreview();
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
    state.coverageBytes = bits ? Uint8Array.from(atob(bits), character => character.charCodeAt(0)) : new Uint8Array();
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
  refreshPathObservations(operation);
  const feedback = document.querySelector('#preview-observation');
  if (feedback) {
    const observed = observedExampleContext(operation);
    const content = selectedTraceCase() && observed.status ? observationFeedback(observed,'','configure') : '';
    renderDockMarkup(feedback, content);
  }
  const inputs = previewInputs(operation);
  document.querySelectorAll("[data-input-result]").forEach(slot => {
    const name = slot.dataset.inputResult;
    const content = inputs.has(name)
      ? `<span class="value-status">Value used</span>${exampleValue(inputs.get(name), `data-preview-input-value="${html(name)}"`)}` : "";
    if (slot.innerHTML !== content) slot.innerHTML = content;
  });
  const first = document.querySelector("[data-preview-input-value]");
  if (first && state.selection.type === "step") first.id = "preview-source";
  const error = document.querySelector("#preview-error");
  if (error) {
    const message=state.preview?.message || '';
    error.textContent=nodeIssues(operation.id).some(diagnostic=>diagnostic.message===message
      || diagnostic.node_name && diagnostic.message===diagnostic.node_name+': '+message) ? '' : message;
  }
  document.querySelectorAll("[data-preview-slot]").forEach(slot => {
    slot.innerHTML = previewStage(
      operation,
      slot.dataset.previewInput,
      Number(slot.dataset.previewSlot)
    );
  });
  refreshCandidateSelection();
  renderFieldDiagnostics(operation);
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
  if (state.build !== "Built" || state.pendingProject) return { kind:'pending', status: "Pending build" };
  const selected = selectedTraceCase();
  const example = selected && state.traceCases.find(candidate => candidate.id === selected.id);
  if (!example) return { kind:state.traceController ? 'pending' : 'unavailable', status: state.traceController ? "Loading Example" : "Values unavailable" };
  if (["queued", "running"].includes(example.status)) return { kind:'pending', status: "Example running" };
  const projection = state.traceStep?.id === operation.id ? state.traceStep : example.projection;
  if (state.traceContext === "input" && !plainObject(projection)) return { kind:'unreached', status:'Not reached by this Example' };
  if (after && state.traceContext !== "input"
      && definitionFor(operation)?.kind !== "trigger") return { kind:'pending', status: "Pending build" };
  const context = state.traceContext === "trigger" ? example.initial_context
    : after || state.traceContext === "output" ? projection?.context : projection?.input_context;
  return plainObject(context) ? { root: { context } } : { kind:'unavailable', status: "Values unavailable" };
}

function pathValues(operation, input, path, locator) {
  if (!path?.length) return "";
  const before = observedExampleContext(operation);
  if (before.status) return "";
  const value = valueAt(before.root, path);
  if (definitionFor(operation)?.kind === "trigger") return exampleValue(value, 'data-path-value="after"');
  const received = locator[0] === "receives" ? `data-preview-input-value="${html(locator[1])}"` : "";
  const source = exampleValue(value, `data-path-value="before" ${received}`);
  if (input.access === "read") return source;
  const after = observedExampleContext(operation, true);
  if (after.status) return source;
  const result = exampleValue(valueAt(after.root, path), `data-path-value="after"${
      locator[0] === "returns" ? ` data-preview-output="${html(locator[1])}"` : ""}`);
  return `${source}<span class="value-arrow" aria-label="becomes">&rarr;</span>${result}`;
}

function pathChoiceValue(operation, path) {
  const observation = observedExampleContext(operation);
  return observation.status ? ""
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

function dirty(projectChanged = true, {keepForm = false} = {}) {
  cancelAttention();
  if (!state.saveFailure?.reload && (projectChanged || !Object.keys(documentChanges(state.builtProject, state.project)).length)) {
    state.saveFailure = null;
  }
  if (projectChanged) {
    if (state.selection.type === 'trigger' && state.builtProject
        && !state.builtProject.nodes.some(operation => operation.id === state.selection.id)) {
      state.revealNode = state.selection.id;
    }
    clearPreview(false, state.selection.type === "trigger");
    resetMetrics();
    state.pendingProject = true;
    state.build = state.saveFailure?.reload ? 'Not built' : 'Building';
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
  if (keepForm) {
    renderBuildStatus();
    refreshTraceView();
    refreshInspectorTabs();
  } else render();
}

function creatorDirty(options) {
  dirty(false, options);
}

function enqueueWrite(write) {
  state.pendingWrite = state.pendingWrite
    ? { ...write, projectChanged: state.pendingWrite.projectChanged || write.projectChanged }
    : write;
  if (!state.writeActive && !state.saveFailure?.reload) {
    state.writeActive = true;
    state.writePromise = drainWrites();
  }
}

async function drainWrites() {
  while (state.pendingWrite && !state.saveFailure?.reload) {
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
  let requestSent=false;
  try {
    const project = parseExact(projectSource);
    const creator = parseExact(creatorSource);
    let payload = null;
    const changes = projectChanged ? documentChanges(state.builtProject, project) : {};
    const removedFlows = [...state.removedFlows];
    if (removedFlows.length) changes.remove_flows = removedFlows;
    const metadataChanges = documentChanges(state.savedCreator, creator);
    if (Object.keys(changes).length || Object.keys(metadataChanges).length) {
      requestSent=true;
      const response = await fetch("/api/editor", {
        method: "POST",
        headers: mutationHeaders(),
        body: JSON.stringify({ revision: state.projectVersion, creator_revision:state.creatorVersion,
          command:{kind:"patch", project:changes, creator:metadataChanges, node:state.selection.id} })
      });
      payload = parseExact(await response.text());
      if (!response.ok) {
        if (revision === state.revision || projectSource === JSON.stringify(state.project)) {
          const retainedFailure=state.saveFailure?.retryable && Object.keys(documentChanges(state.builtProject,project)).length>0;
          state.saveFailure={message:payload.message || payload.diagnostics?.[0]?.message || 'Project could not be saved.',
            retryable:response.status>=500 || response.status!==409 && Boolean(retainedFailure)};
        }
        if (revision === state.revision) {
          if (payload.application) {
            replaceApplication(payload.application);
          }
          state.diagnostics = payload.diagnostics || [response.status === 409
            ? { code: "CREATOR_EDIT_CONFLICT", message: "The project changed in another editor. Your unsaved changes are retained. Reload before saving.", path: "" }
            : { code: "CREATOR_SAVE_FAILED", message: payload.message || "Project is not buildable.", path: "" }];
          state.build = "Not built";
          render();
        }
        return false;
      }
      // A stale UI response still advances the transport baseline for the next queued save.
      state.projectVersion = payload.revision;
      state.creatorVersion = payload.creator_revision;
      state.history = payload.history;
      if (projectChanged) state.builtProject = project;
      state.savedCreator = creator;
      state.removedFlows = state.removedFlows.filter(id => !removedFlows.includes(id));
      state.workspace = payload.workspace;
      state.sceneDirty = true;
    }
    if (revision !== state.revision) {
      return true;
    }
    if (payload?.application) {
      replaceApplication(payload.application);
    }
    state.build = Object.keys(documentChanges(state.builtProject, state.project)).length ? "Not built" : "Built";
    if (state.build === "Built") {
      state.saveFailure = null;
      state.diagnostics = payload?.diagnostics || state.diagnostics;
    }
    state.sceneDirty = true;
    if (state.pathPicker) {
      renderBuildStatus();
      const status = document.querySelector("#change-status");
      // Keep the active chooser stationary until its form is rebuilt on close.
      if (status && state.build === 'Built') status.style.visibility = 'hidden';
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
    if(requestSent) {
      state.saveFailure={message:'Save not confirmed.',retryable:true,reload:true};
      state.build='Not built';
      render();
      return false;
    }
    if (revision === state.revision || projectChanged && projectSource === JSON.stringify(state.project)) {
      state.saveFailure = { message: "Creator could not confirm this change. Your draft is kept in the editor.", retryable: true };
    }
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
  if(document.querySelector('#presentation-symbols[open]'))state.icons?.forEach(icon=>live.add(iconKey(icon)));
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

function outcomeLabel(candidate, outcome, fallback = inputLabel(outcome)) {
  const labels=stepPresentation(candidate?.id).outcomes;
  return labels && Object.hasOwn(labels,outcome) && labels[outcome] || fallback;
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
    && definitionFor(target)?.kind === "step");
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
  return Object.hasOwn(state.creator.steps,id) ? state.creator.steps[id] : {};
}

function setOutcomeLabel(id, outcome, label) {
  const presentation = stepPresentation(id);
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
  state.managedGroup = id || state.regionSelection?.group || groupForStep(selected?.id)?.id || "";
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
  const presentation = stepPresentation(stepId);
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
    document.querySelector('#choose-group')?.focus({preventScroll:true});
  }
  return true;
}

function closeGroupPicker() {
  state.groupPicker = null;
  render();
  document.querySelector('#choose-group')?.focus({preventScroll:true});
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

function editableOperation(locator) {
  const operation = selectedOperation();
  let cursor = operation, fields, input, inputSlot = false;
  // Materialize only the edited path. Sibling defaults must stay omitted.
  for (const part of locator) {
    if (!plainObject(cursor) && !Array.isArray(cursor)) break;
    if (inputSlot) {
      input = fields?.find(field => field.name === part);
      if (input && !Object.hasOwn(cursor,part)) {
        const fallback = defaultInput(input);
        if (input.type === 'options' && fallback) fallback.inputs = {};
        if (input.type === 'candidates') fallback.forEach(candidate => candidate.inputs = {});
        if (fallback !== undefined) cursor[part] = fallback;
      }
      inputSlot = false;
    } else if (part === 'inputs') {
      fields = definitionOf(cursor.use)?.inputs || input?.options?.find(option => option.name === cursor.option)?.inputs;
      if (fields && !Object.hasOwn(cursor,part)) cursor[part] = {};
      inputSlot = true;
    } else if (cursor === operation && ['receives','returns'].includes(part) && !Object.hasOwn(cursor,part)) {
      cursor[part] = {};
    }
    cursor = cursor[part];
  }
  return operation;
}

function selectStep(id) {
  clearPreview();
  resetMetrics();
  state.exampleDraft = null;
  state.selection = { type: "step", id };
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
    const presentation = stepPresentation(id);
    state.creator.steps[id] = presentation;
    return presentation;
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
  setPresentation(target, field, parsed === "" || parsed === defaults[field] ? undefined : parsed, {keepForm:true});
}

function setPresentation(target, field, value, options) {
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
  creatorDirty(options);
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
  if(!open){closeSettings(false);closeProjectImport(false);}
  document.querySelector("#inspector").hidden = !open;
  if (!open) {
    state.iconController?.abort();
    document.querySelector("#graph").focus({ preventScroll: true });
  } else if(innerWidth>650) {
    const handle=document.querySelector('#inspector-resize');
    handle.setAttribute('aria-valuenow',String(Math.round(document.querySelector('#inspector').getBoundingClientRect().width)));
    handle.setAttribute('aria-valuemax',String(Math.max(320,innerWidth-240)));
  }
}

function clearSelection() {
  cancelAttention();
  state.selectedSteps.clear();
  state.groupDraft = null;
  state.world?.connection(null);
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

document.addEventListener('toggle', event => {
  const branch = event.target;
  if(branch.id==='presentation-symbols') {
    if(branch.open)void loadIcons();else {state.iconController?.abort();releaseUnusedIconUrls();}
    return;
  }
  if (!branch.matches('#selection-evidence details') || !branch.isConnected) return;
  const view = state.evidenceView, snapshot = branch.hasAttribute('data-context-snapshot');
  const value = branch.dataset.contextValue, token = value || branch.dataset.contextBranch;
  if (!token) return;
  const key = value ? `value:${branch.dataset.contextSide}:` + token : (snapshot ? 'before:' : '') + token;
  const initial = branch.dataset.contextDefaultOpen === 'true';
  if ((view.open.get(key) ?? initial) === branch.open) return;
  view.open.set(key,branch.open);
  renderDockObservation();
}, true);

document.addEventListener("click", event => {
  if (event.target.closest('#creator-retry')) { window.location.reload(); return; }
  const target = event.target;
  const icon=target.closest('[data-select-icon]');
  if(icon) {
    const selected=state.icons?.find(item=>item.id===icon.dataset.selectIcon);
    const owner=presentationOwner(document.querySelector('#presentation-symbols').dataset.presentationTarget);
    if(selected && owner){owner.icon={media_type:selected.media_type,data:selected.data};creatorDirty();}
    return;
  }
  if(target.closest('[data-retry-icons]')){void loadIcons();return;}
  const setting = target.closest('[data-show-setting]');
  if (setting) {
    state.inspectorMode='inspect';
    render();
    focusDiagnosticPath(setting.dataset.showSetting);
    return;
  }
  const inspected = target.closest('[data-inspect-region]');
  if (inspected) {
    const region = state.world.scene.nodes.find(node => node.id === inspected.dataset.inspectRegion);
    if (region) selectWorldGroup(region.group, region.id);
    return;
  }
  const arrival=target.closest('[data-insert-arrival]');
  if (arrival && state.picker?.mode === 'arrival') {
    const link=state.picker.arrivals.find(link=>link.from+'.'+link.outcome===arrival.dataset.insertArrival);
    if (link) void openBeltPicker(link.from,link.outcome);
    return;
  }
  const reuse = target.closest('[data-reuse-item]');
  const reusePage=target.closest('[data-reuse-page]');
  if(reusePage && state.picker) {state.picker.offset=Number(reusePage.dataset.reusePage);void loadReusable();return;}
  if (reuse && state.picker) {
    state.picker.chosen = state.picker.items.find(item => item.id === reuse.dataset.reuseItem && item.kind === reuse.dataset.reuseType);
    const options = document.querySelector('#step-options');
    options.innerHTML = pickerOptions();
    options.scrollTop = 0;
    document.querySelector('[data-apply-reuse]')?.focus({preventScroll:true}); return;
  }
  if (target.closest('[data-apply-reuse]')) { void placeReuse(target.closest('[data-apply-reuse]').dataset.applyReuse); return; }
  if (target.closest('[data-cancel-reuse]') && state.picker) {
    state.picker.chosen = null;
    const options = document.querySelector('#step-options');
    options.innerHTML = pickerOptions();
    options.scrollTop = 0;
    document.getElementById(state.picker.kind === 'group' ? 'group-insert-search' : 'step-search')?.focus({preventScroll:true}); return;
  }
  if (target.closest('[data-group-selection]')) {
    void previewGroup(false); return;
  }
  if (target.closest('[data-group-range]')) { void previewGroup(true); return; }
  if (target.closest('[data-cancel-group]')) { state.groupDraft = null; render(); return; }
  if (target.closest('[data-detach-use]')) {
    void editorAction({kind:'detach', ...(state.editor.reuse?.group ? {group:state.editor.reuse.group} : {node:state.selection.id})}); return;
  }
  const tab = target.closest('[data-settings-tab]');
  if (tab) {
    selectSettingsTab(tab.dataset.settingsTab);
    event.preventDefault();
    return;
  }
  const editTrigger = target.closest('[data-edit-trigger]');
  if (editTrigger) {
    if(state.selection.type==='trigger' && state.selection.id===editTrigger.dataset.editTrigger){
      state.inspectorMode='examples';render();showInspector(true);return;
    }
    void selectWorldNode(editTrigger.dataset.editTrigger).then(selected => {
      if (selected) { state.inspectorMode = 'examples'; render(); showInspector(true); }
    });
    return;
  }
  if (target.closest('button:not(:disabled), summary') && !target.closest('#world-labels, #audio-panel')) {
    state.audio?.action('click');
  }
  if (target.closest(".build-indicator")) {
    document.querySelector('#application-status').dataset.view='runtime';
    document.querySelector('#application-status').setAttribute('aria-label','Application and build');
    document.querySelector('#application-details').innerHTML = buildDetails();
    state.attentionOffset = 0;
    state.attentionError = '';
    queueMicrotask(renderAttention);
    return;
  }
  if(target.closest('#issue-count,[data-open-issues]')) {
    state.attentionOffset=0;
    state.attentionError='';
    document.querySelector('#application-status').dataset.view='issues';
    document.querySelector('#application-status').setAttribute('aria-label','Problems');
    document.querySelector('#application-details').innerHTML='<header><strong>Problems</strong><button type="button" popovertarget="application-status" popovertargetaction="hide" aria-label="Close problems">Close</button></header><section id="attention-list" aria-label="Problems"></section>';
    document.querySelector('#application-status').showPopover();renderAttention();return;
  }
  const exampleIssue = target.closest('[data-example-issue]');
  if (exampleIssue) { void openExampleIssue(exampleIssue.dataset.exampleIssue); return; }
  if (target.closest('[data-retry-example-scope]')) { state.exampleScope=null; renderExampleContext(); scheduleApplicationPoll(0); return; }
  const issuePage = target.closest('[data-attention-page]');
  if (issuePage) {
    state.attentionOffset = Number(issuePage.dataset.attentionPage);
    renderAttention();
    document.querySelector('#attention-list li button')?.focus();
    return;
  }
  const location = target.closest('[data-world-level]');
  if (location) {
    cancelAttention();showInspector(false);
    state.world.leave((state.world.navigation.length || 0)-Number(location.dataset.worldLevel));
    return;
  }
  if (target.closest('[data-clear-example]')) {
    selectExample(-1, {id:state.exampleTrigger});
    return;
  }
  if (target.closest('[data-retry-example]')) { clearPreview(true); void requestSelectedTrace(); renderExampleContext(); return; }
  const evidenceStep = target.closest('[data-evidence-step]');
  if (target.closest('[data-evidence-fields]')) {
    const view = state.evidenceView;
    for (const branch of document.querySelectorAll('#selection-evidence [data-context-branch]'))
      view.open.set((branch.hasAttribute('data-context-snapshot') ? 'before:' : '') + branch.dataset.contextBranch, branch.open);
    view.all = !view.all;
    renderDockObservation();
    document.querySelector('[data-evidence-fields]')?.focus({preventScroll:true});
    return;
  }
  const moreContext = target.closest('[data-context-more]');
  if (moreContext) {
    const key = (moreContext.hasAttribute('data-context-snapshot') ? 'before:' : '') + moreContext.dataset.contextMore;
    const parent = moreContext.parentElement.parentElement;
    state.evidenceView.limits.set(key,Number(moreContext.dataset.contextOffset)+80);
    const focus = moreContext.id, summary = parent.querySelector(':scope > summary')?.id;
    renderDockObservation();
    (document.getElementById(focus) || document.getElementById(summary))?.focus({preventScroll:true});
    return;
  }
  if (evidenceStep) {
    if (evidenceStep.closest('#application-status')) document.querySelector('#application-status').hidePopover();
    const id = evidenceStep.dataset.evidenceStep;
    if(evidenceStep.hasAttribute('data-diagnostic-path'))state.inspectorMode='inspect';
    void selectWorldNode(id).then(selected => { if (selected) {
      state.world.focus(id);
      if(evidenceStep.dataset.diagnosticPath)focusDiagnosticPath(evidenceStep.dataset.diagnosticPath);
    }});
    return;
  }
  if (target.closest("#project-import-submit")) {
    void importProjectJson();
    return;
  }
  if (target.closest('#project-import-preview-source')) { updateProjectImportPreview(); return; }
  if (target.closest("#project-import-reload")) { void importProjectJson(true); return; }
  if (target.closest("#project-import-clear")) {
    clearProjectImport();
    return;
  }
  const exampleTrigger = target.closest("[data-locate-trigger]");
  if (exampleTrigger) {
    const id = exampleTrigger.dataset.locateTrigger;
    void selectWorldNode(id).then(selected => { if (selected) state.world.focus(id,.5); });
    return;
  }
  if (target.closest("#dock-focus")) {
    state.world?.focus(state.regionSelection?.id || state.selection.id, state.regionSelection ? .5 : undefined);
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
  const destination = target.closest("[data-navigate-step]");
  if (destination) {
    void selectWorldNode(destination.dataset.navigateStep);
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
    closePathPicker();
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
  const groupDialog = target.matches('#group-dialog') ? target.getBoundingClientRect() : null;
  if (target.closest("#close-group-picker") || groupDialog && (event.clientX < groupDialog.left
      || event.clientX > groupDialog.right || event.clientY < groupDialog.top || event.clientY > groupDialog.bottom)) {
    closeGroupPicker();
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
  if (target.closest("#retry-save")) {
    if(state.saveFailure?.reload){window.location.reload();return;}
    dirty(Object.keys(documentChanges(state.builtProject, state.project)).length > 0);
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
    closePicker();
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
    const picker=addPredicateButton.closest('details');
    if(picker)picker.open=false;
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
  if (event.target.id === 'symbol-search') { renderIconOptions(); return; }
  if (['step-search','group-insert-search'].includes(event.target.id) && state.picker) {
    const kind = event.target.id === 'step-search' ? 'step' : 'group';
    state.picker.kind = kind;
    state.picker.queries[kind] = event.target.value;
    state.picker.offset = 0;
    state.picker.chosen = null;
    state.picker.items = [];
    state.picker.matches = 0;
    state.picker.pageSize = 0;
    state.picker.loading = true;
    state.world?.preview(null);
    replacePickerOptions();
    void loadReusable();
  } else if (event.target.id === "group-search") {
    state.groupQuery = event.target.value;
    const detail = document.querySelector('[data-managed-group-details]');
    const selected = state.creator.groups.find(group => group.id === state.managedGroup);
    if (detail) detail.hidden = Boolean(state.groupQuery && !groupName(selected || {}).toLowerCase().includes(state.groupQuery.toLowerCase()));
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
  } else if (event.target.id === "project-import-source") {
    const reader=state.projectImport.reader;
    state.projectImport.reader=null;
    reader?.abort();
    state.projectImport.preview=null;
    document.querySelector('#project-import-file').value='';
    projectImportStatus("");
    renderProjectImport();
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
  if (target.id === 'project-import-source') { updateProjectImportPreview(); return; }
  if (target.matches('[data-linked-use]')) { void selectWorldNode(target.value).then(() => state.world.focus(target.value)); return; }
  if (target.matches('[data-observation-source]')) {
    selectExample(0, {id:target.value}); return;
  }
  if (target.id === "theme-select") {
    changeSettings({theme:target.value, theme_variant:''});
    reflectTheme();
    void applyTheme();
    return;
  }
  if (target.id === 'theme-variant') { changeSettings({theme_variant:target.value}); void applyTheme(); return; }
  if (target.id === 'reduced-motion') { changeSettings({reduced_motion:target.checked}); return; }
  if (target.id === "project-import-file") { void readProjectImportFile(target); return; }
  if (target.id === 'visible-example') {
    const example = target.selectedOptions[0];
    if (!target.value) selectExample(-1, {id:state.exampleTrigger});
    else if (example?.dataset.trigger) selectExample(Number(example.dataset.index), node(example.dataset.trigger) || {id:example.dataset.trigger});
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
  if (event.isComposing || event.defaultPrevented || event.altKey) return;
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z'
      && !event.target.isContentEditable && !event.target.closest('input,textarea,select,[role="textbox"]')) {
    event.preventDefault();
    const kind=event.shiftKey?'redo':'undo';
    if (!document.querySelector(`#editor-${kind}`).disabled) void editorAction({kind});
    return;
  }
  if (event.ctrlKey || event.metaKey) return;
  if (event.key === 'Tab' && state.groupPicker) {
    const fields = [...document.querySelectorAll('#group-dialog button:not(:disabled),#group-dialog input:not(:disabled)')]
      .filter(field => field.getClientRects().length);
    if (event.target === (event.shiftKey ? fields[0] : fields.at(-1))) {
      event.preventDefault();
      (event.shiftKey ? fields.at(-1) : fields[0])?.focus();
      return;
    }
  }
  const pickerSearch = ['step-search','group-insert-search'].includes(event.target.id);
  const optionList = pickerSearch ? document.querySelector('#step-options')
    : event.target.matches('input[type="search"]') ? event.target.nextElementSibling
    : event.target.closest('#step-options,[data-step-options],[data-predicate-options]');
  const search = optionList?.id === 'step-options' ? document.getElementById(state.picker?.kind === 'group' ? 'group-insert-search' : 'step-search') : optionList?.previousElementSibling;
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
    if (key==='e' && !state.regionSelection && !state.selection.id && state.world?.navigation.length) {
      event.preventDefault(); document.querySelector('#world-location [data-inspect-region]')?.click();
    } else if (key==='e' && (state.regionSelection || state.selection.id)) {
      event.preventDefault(); showInspector(true);
    } else if (key==='enter' && state.regionSelection) {
      event.preventDefault(); if (!event.repeat) focusRegion(state.regionSelection.id);
    } else if (key==='f' && (state.regionSelection || state.selection.id)) {
      event.preventDefault(); if (!event.repeat) void state.world?.focus(state.regionSelection?.id || state.selection.id);
    }
    return;
  }
  if (document.querySelector(':popover-open')) return;
  if (!document.querySelector('#audio-panel').hidden) {
    event.preventDefault();closeSettings();return;
  }
  if (!document.querySelector('#project-import-panel').hidden) {
    event.preventDefault();closeProjectImport();return;
  }
  if (state.picker || state.groupPicker) {
    if (state.picker) closePicker();
    else closeGroupPicker();
    return;
  }
  if (state.pathPicker) {
    closePathPicker();
    render();
    return;
  }
  if (state.groupDraft) {
    event.preventDefault();
    state.groupDraft = null;
    render();
    document.querySelector('[data-group-selection]')?.focus({preventScroll:true});
    return;
  }
  if (editing) return;
  if (!document.querySelector("#inspector").hidden) showInspector(false);
  else clearSelection();
});

document.addEventListener('cancel', event => {
  if (event.target.id === 'group-dialog') {
    event.preventDefault();
    closeGroupPicker();
  }
}, true);

document.addEventListener('submit', event => {
  if (event.target.id !== 'group-draft' || !state.groupDraft) return;
  event.preventDefault();
  const command={...state.groupDraft.command,name:new FormData(event.target).get('name')};
  void editorAction(command).then(result => {
    if (!result) return;
    state.groupDraft = null;
    const region = state.world?.scene?.nodes.find(item => item.kind === 'region' && item.group === command.id);
    if (region) selectWorldGroup(command.id, region.id);
    else render();
    document.querySelector('#inspector-navigation button')?.focus({preventScroll:true});
  });
});
for (const kind of ['undo','redo']) document.querySelector(`#editor-${kind}`).addEventListener('click', () => void editorAction({kind}));

document.addEventListener("visibilitychange", () => {
  clearTimeout(state.groupMeanTimer);
  state.groupMeanController?.abort();
  scheduleWorldObservations();
  if (!document.hidden) {
    scheduleMetricsPoll(0);
  }
});

document.querySelector("#zoom-out").addEventListener("click", () => state.world?.zoom(1 / 1.4));
document.querySelector('#scope-overview').addEventListener('click', () => state.world?.fit());
document.querySelector('#leave-group').addEventListener('click', () => state.world?.leave());
document.querySelector('#theme-download').addEventListener('click', () => void downloadTheme());
document.querySelector('#toggle-effects').addEventListener('click', () => {
  const effects = state.settings.effects === false;
  state.audio?.applyPreferences({...state.settings, effects});
  void state.audio?.effects(effects);
  changeSettings({effects});
});
document.querySelector('#toggle-music').addEventListener('click', () => void state.audio?.toggleMusic());
document.querySelector('#open-settings').addEventListener('click',openSettings);
document.querySelector('#close-settings').addEventListener('click',()=>closeSettings());
document.querySelector('#open-project-import').addEventListener('click',openProjectImport);
document.querySelector('#close-project-import').addEventListener('click',()=>closeProjectImport());
document.querySelector('#application-status').addEventListener('toggle', event => {
  if (event.newState === 'open') renderAttention();
  else cancelAttention();
});
document.addEventListener('pointerdown', () => {
  if (state.musicGesture || state.settings.music_enabled === false || state.settings.music_volume === 0) return;
  state.musicGesture = true;
  void state.audio?.play();
}, {once:true, capture:true});
document.querySelector("#zoom-in").addEventListener("click", () => state.world?.zoom(1.4));
document.querySelector("#zoom-fit").addEventListener("click", () => { showInspector(false); state.world?.focus('app', .5); });
{
  const handle=document.querySelector('#inspector-resize');
  let drag=null;
  const resize=width=>{
    const maximum=Math.max(320,innerWidth-240), value=Math.round(Math.max(320,Math.min(maximum,width)));
    document.documentElement.style.setProperty('--inspector-width',value+'px');
    handle.setAttribute('aria-valuenow',String(value));handle.setAttribute('aria-valuemax',String(maximum));
  };
  handle.addEventListener('pointerdown',event=>{
    if(event.button!==0)return;
    event.preventDefault();event.stopPropagation();
    drag={id:event.pointerId,x:event.clientX,width:document.querySelector('#inspector').getBoundingClientRect().width};
    handle.setPointerCapture(event.pointerId);
  });
  handle.addEventListener('pointermove',event=>{if(drag?.id===event.pointerId)resize(drag.width+drag.x-event.clientX);});
  handle.addEventListener('pointerup',event=>{drag=null;if(handle.hasPointerCapture(event.pointerId))handle.releasePointerCapture(event.pointerId);});
  handle.addEventListener('pointercancel',()=>drag=null);
  handle.addEventListener('lostpointercapture',()=>drag=null);
  handle.addEventListener('dblclick',()=>resize(420));
  handle.addEventListener('keydown',event=>{
    const width=document.querySelector('#inspector').getBoundingClientRect().width;
    const next={ArrowLeft:width+32,ArrowRight:width-32,Home:320,End:innerWidth-240,Enter:420}[event.key];
    if(next===undefined)return;event.preventDefault();event.stopPropagation();resize(next);
  });
  window.addEventListener('resize',()=>{
    handle.setAttribute('aria-valuemax',String(Math.max(320,innerWidth-240)));
    if(innerWidth>650 && !document.querySelector('#inspector').hidden)handle.setAttribute('aria-valuenow',String(Math.round(document.querySelector('#inspector').getBoundingClientRect().width)));
  });
}
window.addEventListener("beforeunload", event => {
  const active = document.activeElement;
  const typing = active?.matches('#inspector-content input:not([type=checkbox]):not([type=radio]):not([type=file]), #inspector-content textarea')
    && active.value !== active.defaultValue;
  if (state.actionPending || state.writeActive || state.pendingWrite || state.jsonDraft || state.exampleDraft || state.saveFailure?.reload
      || document.querySelector('#project-import-source').value.trim() || state.projectImport.reader
      || typing
      || state.settingsVersion !== state.savedSettingsVersion
      || state.audio?.dirtyEditor || state.audio?.mutation
      || state.builtProject && (Object.keys(documentChanges(state.builtProject, state.project)).length
        || Object.keys(documentChanges(state.savedCreator, state.creator)).length || state.removedFlows.length)) {
    event.preventDefault();
    event.returnValue = true;
    return;
  }
  state.audio?.dispose();
  clearTimeout(state.groupMeanTimer);
  state.groupMeanController?.abort();
  state.themeController?.abort();
  state.iconController?.abort();
  clearTimeout(state.settingsTimer);
  state.world?.dispose();
  clearTimeout(state.applicationPollTimer);
  state.applicationController?.abort();
  clearTimeout(state.metricsPollTimer);
  clearTimeout(state.observationTimer);
  state.observationController?.abort();
  state.metricsController?.abort();
  state.traceController?.abort();
  state.editorController?.abort();
  state.iconUrls.forEach(url => URL.revokeObjectURL(url));
  state.iconUrls.clear();
});
window.addEventListener('pagehide',()=>{
  state.projectImport.reader?.abort();
  state.projectImport.request?.abort();
});
