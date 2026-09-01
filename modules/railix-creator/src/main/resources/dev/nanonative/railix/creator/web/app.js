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
  creator: { format: 1, groups: [], steps: {} },
  catalog: [],
  icons: [],
  iconDiagnostics: [],
  application: {},
  workspace: {},
  selection: { type: "app", id: "app" },
  diagnostics: [],
  localDiagnostics: [],
  build: "Loading",
  picker: null,
  iconPicker: null,
  pathPicker: null,
  pathDraft: [],
  pathField: "",
  pathIndex: "0",
  jsonDraft: null,
  stepQueries: {},
  candidateQueries: {},
  exampleIndex: 0,
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
  applicationPollTimer: 0,
  applicationRefreshing: false,
  metrics: null,
  metricsNode: "",
  metricsController: null,
  metricsPollTimer: 0,
  inspectorMode: "inspect",
  groupDraft: null,
  groupStack: [],
  editScope: null,
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
    const [projectResponse, catalogResponse, iconResponse] = await Promise.all([
      fetch("/api/project"),
      fetch("/api/catalog"),
      fetch("/api/icons")
    ]);
    if (!projectResponse.ok || !catalogResponse.ok || !iconResponse.ok) {
      throw new Error("Creator could not open the project.");
    }
    const [project, catalog, icons] = await Promise.all([
      projectResponse.text().then(parseExact),
      catalogResponse.text().then(parseExact),
      iconResponse.text().then(parseExact)
    ]);
    state.project = project.project;
    state.builtProject = clone(project.project);
    state.creator = project.creator || { format: 1, groups: [], steps: {} };
    state.application = project.application;
    state.workspace = project.workspace;
    state.diagnostics = project.diagnostics || [];
    state.catalog = catalog.steps;
    state.icons = icons.icons;
    state.iconDiagnostics = icons.diagnostics;
    state.build = "Built";
    render();
    scheduleApplicationPoll(0);
    scheduleMetricsPoll(0);
  } catch (error) {
    document.querySelector("#build-state").textContent = "Unavailable";
    document.querySelector("#inspector").innerHTML = `
      <section class="empty-state">
        <strong>Creator unavailable</strong>
        <p>${html(error instanceof Error ? error.message : "Creator could not open the project.")}</p>
      </section>`;
  }
}

function render() {
  if (!state.project) {
    return;
  }
  const focusedNode = document.activeElement?.closest("[data-node-id][tabindex='0']")?.dataset.nodeId;
  document.querySelector("#project-title").textContent = currentGroup()
    ? groupName(currentGroup().group)
    : state.project.id;
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
  document.querySelector("#graph").innerHTML = graph(flows);
  document.querySelector("#inspector").innerHTML = inspector();
  document.querySelector("#overlay").innerHTML = picker() + iconPicker();
  for (const candidate of document.querySelectorAll("[data-node-id][tabindex='0']")) {
    if (candidate.dataset.nodeId === focusedNode) {
      candidate.focus({ preventScroll: true });
      break;
    }
  }
  applyExampleCoverage();
}

function renderBuildStatus() {
  document.querySelector("#build-state").textContent = state.build;
  document.body.dataset.build = state.build.toLowerCase().replace(" ", "-");
}

function refreshPathPicker() {
  const current = document.querySelector(".path-browser");
  const operation = state.selection.type === "step" ? selectedOperation() : null;
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

function graph(flows) {
  const routes = routeView();
  const scoped = currentGroup();
  if (scoped) {
    const trigger = node(scoped.occurrence.flow);
    return `
      <section class="flow-scope">
        <header class="flow-scope-header">
          <button class="button" type="button" id="close-group">Back</button>
          <span>${state.groupStack.map(id => html(groupName(groupOccurrence(id)?.group))).join(" / ")}</span>
        </header>
        ${trigger ? flowLane(trigger, changedIds(), routes) : ""}
      </section>`;
  }
  const changed = changedIds();
  const appClass = nodeClasses(
    state.selection.type === "app",
    changed.has("app"),
    nodeIssues("app")
  );
  const app = `
    <article class="node app-node${appClass}" data-node-id="app" data-select-node="app"
             role="button" tabindex="0" aria-selected="${state.selection.type === "app"}">
      <div class="node-kicker"><span class="node-mark">R</span> Core Step${nodeStatus(nodeIssues("app"))}</div>
      <h2>Application</h2>
      <p>${html(state.project.id)}</p>
    </article>`;
  if (!flows.length) {
    return `<div class="empty-graph">${app}</div>`;
  }
  return `
    <div class="graph-root">${app}<span class="graph-stem"></span></div>
    <div class="flow-grid">${flows.map(trigger => flowLane(trigger, changed, routes)).join("")}</div>`;
}

function flowLane(trigger, changed, routes) {
  const definition = definitionOf(trigger.use);
  const presentation = stepPresentation(trigger.id);
  const triggerSelected = state.selection.type === "trigger" && state.selection.id === trigger.id;
  const triggerIssues = nodeIssues(trigger.id);
  const triggerClass = nodeClasses(triggerSelected, changed.has(trigger.id), triggerIssues);
  const scope = currentGroup();
  const rendered = renderRoutes(trigger, changed, routes, scope);
  const branching = rendered.branching;
  return `
    <section class="flow-lane${branching ? " branching-flow" : ""}" data-flow="${html(trigger.id)}">
      ${scope ? "" : `<span class="lane-connector"></span>
      <article class="node trigger-node${triggerClass}" data-node-id="${html(trigger.id)}"
               ${presentation.color ? `style="--node-accent:${html(presentation.color)}"` : ""}
               data-select-node="${html(trigger.id)}" role="button" tabindex="0"
               aria-selected="${triggerSelected}">
        <div class="node-kicker"><span class="node-mark trigger-mark">${
          presentation.icon ? iconMarkup(presentation.icon) : "T"
        }</span> Trigger${
          nodeStatus(triggerIssues)
        }</div>
        <h2>${html(presentation.name || stepName(definition))}</h2>
        <p>${html(trigger.id)} · ${count(trigger.examples.length, "example")}</p>
      </article>`}
      ${rendered.html}
    </section>`;
}

function routeView() {
  const links = new Map();
  state.project.links.forEach(link => {
    const outgoing = links.get(link.from) || [];
    outgoing.push(link);
    links.set(link.from, outgoing);
  });
  const groups = new Map();
  const parent = currentGroup()?.occurrence.id || null;
  state.creator.groups.forEach(group => group.occurrences
    .filter(occurrence => occurrence.parent === parent)
    .forEach(occurrence => {
      const region = occurrenceRegion(occurrence);
      if (region.entry) {
        groups.set(region.entry.id, { group, occurrence, ...region });
      }
    }));
  return {
    nodes: new Map(state.project.nodes.map(operation => [operation.id, operation])),
    definitions: new Map(state.catalog.map(definition => [definition.id, definition])),
    links,
    groups
  };
}

function renderRoutes(trigger, changed, routes, scope = null) {
  const fragments = [];
  const scopeRegion = scope ? occurrenceRegion(scope.occurrence) : null;
  const allowed = scopeRegion ? new Set(scopeRegion.operations.map(operation => operation.id)) : null;
  const pending = scopeRegion?.entry
    ? [{ target: scopeRegion.entry.id }]
    : [{ source: trigger.id, outcome: primaryOutcome(trigger) }];
  const seen = new Set();
  let branching = false;
  while (pending.length) {
    const frame = pending.pop();
    if (Object.hasOwn(frame, "html")) {
      fragments.push(frame.html);
      continue;
    }
    let target = frame.target;
    if (target === undefined) {
      const route = frame.source + "." + frame.outcome;
      const outgoing = routes.links.get(route) || [];
      if (outgoing.length !== 1) {
        fragments.push(routeErrorNode(
          frame.source,
          frame.outcome,
          outgoing.length ? "Multiple links" : "Missing link"
        ));
        continue;
      }
      target = outgoing[0].to;
    }
    if (allowed && (target === "end" || !allowed.has(target))) {
      fragments.push(groupExitNode(frame.source, frame.outcome, target));
      continue;
    }
    if (target === "end") {
      fragments.push(terminalNode(frame.source + "-" + frame.outcome));
      continue;
    }
    const operation = routes.nodes.get(target);
    if (!operation) {
      fragments.push(routeErrorNode(frame.source, frame.outcome, "Unknown Step"));
      continue;
    }
    if (seen.has(operation.id)) {
      fragments.push(routeErrorNode(frame.source, frame.outcome, "Repeated Step"));
      continue;
    }
    const grouped = routes.groups.get(operation.id);
    if (grouped) {
      const repeated = grouped.operations.find(member => seen.has(member.id));
      if (repeated) {
        fragments.push(routeErrorNode(frame.source, frame.outcome, "Repeated Step"));
        continue;
      }
      grouped.operations.forEach(member => seen.add(member.id));
      fragments.push(groupNode(grouped.group, grouped.occurrence, changed));
      if (grouped.exits.length === 1) {
        pending.push(grouped.exits[0]);
      } else {
        branching = pushBranchRoutes(fragments, pending, grouped.exits) || branching;
      }
      continue;
    }
    seen.add(operation.id);
    fragments.push(stepNode(operation, changed));
    const declared = displayOutcomes(operation);
    if (declared.length === 1) {
      pending.push({ source: operation.id, outcome: declared[0] });
      continue;
    }
    if (!declared.length) {
      fragments.push(routeErrorNode(operation.id, "outcome", "Missing outcome"));
      continue;
    }
    branching = true;
    pushBranchRoutes(fragments, pending, declared.map(outcome => ({ source: operation.id, outcome })));
  }
  return { branching, html: fragments.join("") };
}

function pushBranchRoutes(fragments, pending, routes) {
  if (!routes.length) {
    return false;
  }
  fragments.push(`<div class="branch-routes" style="--branch-count:${routes.length};--branch-start:${50 / routes.length}%">
    <span class="branch-trunk" aria-hidden="true"></span>`);
  pending.push({ html: "</div>" });
  for (let index = routes.length - 1; index >= 0; index--) {
    const route = routes[index];
    pending.push({ html: "</section>" });
    pending.push(route);
    pending.push({ html: `
      <section class="branch-route" data-branch-source="${html(route.source)}"
               data-branch-outcome="${html(route.outcome)}">
        <strong class="branch-route-label">${html(groupRouteLabel(route, routes))}</strong>` });
  }
  return routes.length > 1;
}

function groupRouteLabel(route, routes) {
  if (routes.every(candidate => candidate.source === route.source)) {
    return outcomeLabel(node(route.source), route.outcome);
  }
  const source = node(route.source);
  const name = stepPresentation(route.source).name || stepName(definitionFor(source));
  return name + " · " + outcomeLabel(source, route.outcome);
}

function terminalNode(route) {
  return `<span class="lane-connector short"></span>
    <article class="node end-node" data-node-id="end-${html(route)}">
      <div class="node-kicker">Terminal</div>
      <h2>End</h2>
      <p>Trigger result</p>
    </article>`;
}

function groupExitNode(source, outcome, target) {
  return `<span class="lane-connector short"></span>
    <article class="node end-node group-exit" data-node-id="exit-${html(source)}-${html(outcome)}"
             data-group-exit="${html(source)}.${html(outcome)}">
      <div class="node-kicker">Group exit</div>
      <h2>${html(outcomeLabel(node(source), outcome))}</h2>
      <p>${target === "end" ? "End" : html(target)}</p>
    </article>`;
}

function routeErrorNode(source, outcome, message) {
  return `<span class="lane-connector short"></span>
    <article class="node end-node issue-error route-error"
             data-node-id="route-${html(source)}-${html(outcome)}"
             data-route-source="${html(source)}" data-route-outcome="${html(outcome)}">
      <div class="node-kicker">Error</div>
      <h2>${html(message)}</h2>
      <p>${html(source)}.${html(outcome)}</p>
    </article>`;
}

function stepNode(operation, changed) {
  const definition = definitionFor(operation);
  const issues = nodeIssues(operation.id);
  const selected = (state.selection.type === "step" && operation.id === state.selection.id)
    || state.groupDraft?.start === operation.id || state.groupDraft?.end === operation.id;
  const presentation = stepPresentation(operation.id);
  const classes = nodeClasses(
    selected,
    changed.has(operation.id),
    issues
  );
  return `
    <span class="lane-connector short"></span>
    <article class="node step-node${classes}"
             ${presentation.color ? `style="--node-accent:${html(presentation.color)}"` : ""}
             data-node-id="${html(operation.id)}"
             data-select-step="${html(operation.id)}" role="button" tabindex="0"
             aria-selected="${selected}">
      <div class="node-kicker"><span class="node-mark field-mark">${
        presentation.icon ? iconMarkup(presentation.icon) : "S"
      }</span> ${html(presentation.name || stepName(definition))}${nodeStatus(issues)}</div>
      ${stepSummary(operation)}
    </article>`;
}

function groupNode(group, occurrence, changed) {
  const operations = occurrenceSteps(occurrence);
  const issues = [...nodeIssues(occurrence.id),
    ...operations.flatMap(operation => nodeIssues(operation.id))];
  const selected = state.selection.type === "group" && state.selection.id === occurrence.id;
  const fallback = operations[0] ? stepPresentation(operations[0].id) : {};
  const color = group.color || fallback.color || "#147982";
  const icon = group.icon || fallback.icon || state.icons.find(candidate => candidate.id === "flow");
  return `
    <span class="lane-connector short"></span>
    <article class="node step-node flow-node${nodeClasses(
      selected,
      operations.some(operation => changed.has(operation.id)),
      issues
    )}" style="--node-accent:${html(color)}"
             data-node-id="${html(occurrence.id)}" data-select-group="${html(occurrence.id)}"
             role="button" tabindex="0" aria-selected="${selected}">
      <div class="node-kicker"><span class="node-mark flow-mark">${iconMarkup(icon)}</span>
        Group${nodeStatus(issues)}</div>
      <h2>${html(groupName(group))}</h2>
      <p>${count(operations.length, "Step")}</p>
    </article>`;
}

function stepSummary(operation) {
  const summary = operationSummary(operation);
  return `
    <div class="step-summary">
      <strong>${html(summary.primary)}</strong>
      <small>${html(summary.secondary)}</small>
    </div>`;
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
  const modes = [
    ["inspect", "Inspector"],
    ...(["trigger", "step", "group"].includes(state.selection.type)
      ? [["appearance", "Appearance"]] : []),
    ...(state.selection.type === "trigger" ? [["examples", "Examples"]] : []),
    ["groups", "Groups"]
  ];
  if (!modes.some(([mode]) => mode === state.inspectorMode)) {
    state.inspectorMode = "inspect";
  }
  const tabs = `<nav class="inspector-tabs" aria-label="Inspector mode"
                     style="--tab-count:${modes.length}">${modes.map(([mode, label]) => `
    <button type="button" data-inspector-mode="${mode}" class="${
      state.inspectorMode === mode ? "active" : ""
    }">${label}</button>`).join("")}</nav>`;
  if (state.inspectorMode === "groups") {
    return tabs + manageGroupsInspector();
  }
  if (state.inspectorMode === "examples") {
    return tabs + examplesInspector(node(state.selection.id));
  }
  if (state.inspectorMode === "appearance") {
    return tabs + appearanceInspector();
  }
  if (state.selection.type === "group") {
    return tabs + groupInspector(groupOccurrence(state.selection.id));
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

function groupInspector(item) {
  if (!item) {
    return appInspector(issueList("app"));
  }
  const { group, occurrence } = item;
  return `
    ${inspectorHeader("Group", groupName(group), occurrence.id)}
    ${issueList(occurrence.id)}
    <section class="inspector-section facts">
      <dl>
        <div><dt>Occurrences</dt><dd>${group.occurrences.length}</dd></div>
        <div><dt>Steps here</dt><dd>${Object.keys(occurrence.steps).length}</dd></div>
      </dl>
    </section>
    <footer class="inspector-actions">
      <button class="button primary" type="button" id="open-group">Open Group</button>
      <button class="button danger" type="button" id="delete-group">Delete Group</button>
    </footer>`;
}

function appearanceInspector() {
  if (state.selection.type === "group") {
    const item = groupOccurrence(state.selection.id);
    return item ? `${inspectorHeader("Group", groupName(item.group), item.occurrence.id)}
      ${presentationEditor(item.group, {}, "group:" + item.group.id)}` : appInspector(issueList("app"));
  }
  const operation = selectedOperation();
  const definition = definitionFor(operation);
  return operation && definition
    ? `${inspectorHeader(
        definition.kind === "trigger" ? "Trigger" : "Step",
        stepPresentation(operation.id).name || stepName(definition),
        operation.id
      )}${presentationEditor(
        stepPresentation(operation.id),
        { name: stepName(definition) },
        "step:" + operation.id
      )}`
    : appInspector(issueList("app"));
}

function manageGroupsInspector() {
  const draft = state.groupDraft;
  return `
    ${inspectorHeader("Creator", "Manage Groups", "Groups change only how the graph is displayed")}
    ${draft ? issueList(draft.start || draft.end || "app") : ""}
    ${draft ? `<section class="inspector-section group-draft">
      <div class="section-heading"><strong>${draft.group
        ? "Add " + html(groupName(state.creator.groups.find(group => group.id === draft.group)))
        : "Select a range"}</strong><span>Start then end</span></div>
      <p>Start: <code>${html(draft.start || "Select a Step")}</code></p>
      <p>End: <code>${html(draft.end || "Select a Step")}</code></p>
      <button class="button" type="button" id="cancel-group-draft">Cancel</button>
    </section>` : `<section class="inspector-section">
      <button class="button primary wide" type="button" id="new-group">New Group</button>
    </section>`}
    <section class="inspector-section">
      <div class="section-heading"><strong>Groups</strong><span>${state.creator.groups.length}</span></div>
      <div class="group-list">${state.creator.groups.map(group => `
        <article data-group-list="${html(group.id)}">
          <header><strong>${html(groupName(group))}</strong>
            <small>${count(group.occurrences.length, "occurrence")}</small></header>
          ${group.occurrences.map(occurrence => `
            <button type="button" data-manage-occurrence="${html(occurrence.id)}">
              ${html(occurrence.flow)} · ${count(Object.keys(occurrence.steps).length, "Step")}
            </button>`).join("")}
          <button class="button" type="button" data-add-occurrence="${html(group.id)}">
            Add occurrence
          </button>
        </article>`).join("") || '<p class="empty-options">No groups yet.</p>'}</div>
    </section>`;
}

function presentationEditor(presentation = {}, defaults = {}, target) {
  const inheritedColor = defaults.color || "#147982";
  const color = /^#[0-9a-fA-F]{6}$/.test(presentation.color || inheritedColor)
    ? (presentation.color || inheritedColor).toUpperCase()
    : "#147982";
  const icon = presentation.icon || defaults.icon;
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
      <label>Icon</label>
      <div class="icon-editor">
        <span class="icon-preview">${icon ? iconMarkup(icon) : "Automatic"}</span>
        <button id="choose-icon" type="button" data-open-icon-picker="${target}">Choose</button>
        <button id="reset-icon" type="button" data-reset-presentation="icon"
                data-presentation-target="${target}" ${presentation.icon ? "" : "disabled"}>Reset</button>
      </div>
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
    ...state.diagnostics,
    ...state.iconDiagnostics
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
  const groupPath = /^groups\[(\d+)](?:\.occurrences\[(\d+)])?/.exec(diagnostic.path || "");
  if (groupPath) {
    const group = state.creator.groups[Number(groupPath[1])];
    return group?.occurrences[Number(groupPath[2] || 0)]?.id || "app";
  }
  const nodePath = /^nodes\[(\d+)]/.exec(diagnostic.path || "");
  if (nodePath) {
    return state.project.nodes[Number(nodePath[1])]?.id || "app";
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

function nodeClasses(selected, changed, diagnostics) {
  const severity = diagnostics.some(diagnostic => diagnostic.severity !== "warning")
    ? " issue-error"
    : diagnostics.length ? " issue-warning" : "";
  return `${selected ? " selected" : ""}${changed ? " changed" : ""}${severity}`;
}

function nodeStatus(diagnostics) {
  if (!diagnostics.length) {
    return "";
  }
  const warnings = diagnostics.every(diagnostic => diagnostic.severity === "warning");
  return `<span class="node-status">${diagnostics.length} ${warnings ? "warning" : "error"}${
    diagnostics.length === 1 ? "" : "s"
  }</span>`;
}

function appInspector(issues) {
  const builtAt = Number(state.application.built_at || 0);
  return `
    ${inspectorHeader("Application Step", "Application", "Project settings and build facts")}
    ${issues}
    <section class="inspector-section facts">
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
    </section>
    <div id="metrics-panel">${metricsPanel()}</div>
    <section class="inspector-section">
      <label for="project-id">Project name</label>
      <input id="project-id" value="${html(state.project.id)}" autocomplete="off">
    </section>
    ${availableTriggers().length ? `
      <footer class="inspector-actions">
        <button class="button primary" id="add-trigger" type="button"
                data-open-picker="trigger">Add Trigger</button>
      </footer>` : ""}`;
}

function triggerInspector(trigger, issues) {
  const definition = definitionOf(trigger.use);
  return `
    ${inspectorHeader("Trigger", stepPresentation(trigger.id).name || stepName(definition), trigger.use)}
    ${issues}
    ${inputFields(trigger, definition.inputs, ["inputs"])}
    ${metricsSetting(trigger)}
    <div id="metrics-panel">${metricsPanel()}</div>
    <section class="inspector-section">
      <div class="section-heading"><strong>Expected results</strong><span>Trigger contract</span></div>
      <div class="contract-list">${definition.results.map(result => `
        <span><code>context.${html(result.name)}</code><small>${html(result.shape)}${
          Object.hasOwn(result, "default") ? " · default " + html(JSON.stringify(result.default)) : ""
        }</small></span>`
      ).join("")}</div>
    </section>
    <section class="inspector-section">
      <button class="button wide" type="button" id="add-next-step"
              ${insertionAllowed(trigger, primaryOutcome(trigger)) ? "" : "disabled"}>Add next Step</button>
    </section>
    <div id="run-result-panel">${runResultPanel()}</div>
    <footer class="inspector-actions">
      <button class="button danger" id="delete-step" type="button">Delete flow</button>
    </footer>`;
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
        ${trigger.examples.map((candidate, index) => `
          <button type="button" class="${candidate === example ? "active" : ""}"
                  data-select-example="${index}">${html(candidate.name)}</button>`).join("")}
        <button type="button" id="add-example">Add</button>
      </div>
      <label for="example-name">Name</label>
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
        : ""}
    </section>`;
}

function stepInspector(operation, issues) {
  const definition = definitionOf(operation.use);
  const shared = sharedMembership(operation.id);
  if (shared && !state.editScope) {
    return `
      ${inspectorHeader("Shared Step", stepPresentation(operation.id).name || stepName(definition), operation.id)}
      <section class="inspector-section shared-choice">
        <p>This group occurs ${shared.group.occurrences.length} times. Choose where the next edit applies.</p>
        <button class="button primary" type="button" data-shared-action="all">Update all</button>
        <button class="button" type="button" data-shared-action="detach">Detach this</button>
        <button class="button" type="button" data-shared-action="variant">Create variant</button>
        <button class="button" type="button" data-shared-action="cancel">Cancel</button>
      </section>`;
  }
  return `
    ${inspectorHeader("Step", stepPresentation(operation.id).name || stepName(definition), operation.id)}
    ${issues}
    ${portMappings(operation, definition)}
    ${inputFields(operation, definition.inputs, ["inputs"])}
    ${metricsSetting(operation)}
    <div id="metrics-panel">${metricsPanel()}</div>
    <div id="preview-values" aria-live="polite">${previewSource(operation)}</div>
    <footer class="inspector-actions">
      ${nextStepControls(operation)}
      <button class="button danger" id="delete-step" type="button" ${removableStep(operation)
        ? "" : 'disabled title="Remove branch Steps first"'}>Delete Step</button>
    </footer>`;
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
                    ${insertionAllowed(operation, declared[0]) ? "" : "disabled"}>Add next Step</button>`;
  }
  return `<section class="next-routes" aria-label="Next Steps">
    ${declared.map(outcome => {
      const destinations = outcomeDestinations(operation, outcome);
      const destination = destinations.length === 1 ? destinations[0] : undefined;
      const target = node(destination);
      const repeated = target && state.project.links.filter(link => link.to === target.id).length !== 1;
      const insertable = insertionAllowed(operation, outcome);
      return `<div>
        <span><strong>${html(outcomeLabel(operation, outcome))}</strong><small>${html(
          destinations.length > 1 ? "Multiple links"
            : repeated ? "Repeated Step"
              : target ? stepPresentation(target.id).name || stepName(definitionFor(target))
              : destination === "end" ? "End" : destination ? "Unknown Step" : "Missing link"
        )}</small></span>
        <button class="button" type="button" data-add-outcome="${html(outcome)}"
                ${insertable ? "" : "disabled"}>Add Step</button>
      </div>`;
    }).join("")}
  </section>`;
}

function removableStep(operation) {
  return outcomes(operation).slice(1).every(outcome => outcomeTarget(operation, outcome) === "end");
}

function inputFields(operation, inputs, base) {
  if (!inputs.length) {
    return "";
  }
  return inputs.map(input => inputEditor(operation, input, [...base, input.name], inputs, base)).join("");
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
        <strong>Group ${index + 1} <span>All match</span></strong>
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
    && (!authored || alignedCandidates(operation, index)
      .every(item => item.candidate && outcomeTarget(item.operation, item.candidate.outcome) === "end"));
  const predicateName = view.predicateName || input.name + "[" + index + "].when";
  const condition = conditionOf(candidate.when);
  const predicateStatus = !condition.transforms.length && !condition.all.length
    ? "value must exist"
    : condition.all.length
      ? `${condition.all.length} ${condition.all.length === 1 ? "matcher" : "matchers"} must pass`
      : "add matcher";
  return `
    <article class="candidate${selected ? " selected-candidate" : ""}" data-candidate-index="${index}"
             ${path ? `data-candidate-path="${html(path)}"` : ""}>
      <div class="candidate-heading">
        <strong>${html(noun)} ${index + 1}</strong>
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
      <label for="${html(inputId(candidateLocator))}-option">Source</label>
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
    <section class="condition-transforms">
      <div class="section-heading"><strong>Transform value</strong><span>Run once</span></div>
      ${programEditor(operation, transforms, transformLocator, condition.transforms, scopeInputs, scopeBase)}
    </section>
    <section class="condition-predicates">
      <div class="section-heading"><strong>Matchers</strong><span>All must pass</span></div>
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
      <label class="program-search-label" for="${html(inputId(queryLocator))}-search">
        <strong>Add AND matcher</strong><span>Receives transformed value</span>
      </label>
      <input type="search" id="${html(inputId(queryLocator))}-search"
             value="${html(queryAt(state.candidateQueries, queryLocator))}"
             data-predicate-query="${token}" data-input-meta="${metaToken(input)}"
             data-input-scope="${metaToken({ inputs: scopeInputs, base: scopeBase })}"
             placeholder="Search matchers" autocomplete="off">
      <div data-predicate-options="${token}" data-input-meta="${metaToken(input)}"
           data-input-scope="${metaToken({ inputs: scopeInputs, base: scopeBase })}">
        ${predicateOptions(candidateLocator, preparedShape)}
      </div>
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
        <strong>Matcher ${index + 1}</strong>
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
  return state.catalog
    .filter(definition => definition.kind === "step")
    .filter(definition => !authoredOutcomeInput(definition))
    .filter(definition => definition.receives.length === 1 && definition.returns.length === 1)
    .filter(definition => definition.outcomes.length === 1 && definition.returns[0].shape === "boolean")
    .filter(definition => portAcceptsValue(definition.receives[0], shape, []))
    .filter(definition => definitionMatchesQuery(definition, query))
    .map(definition => `
      <button type="button" class="catalog-option compact-option program-option"
              data-add-predicate="${html(definition.id)}"
              data-condition-locator="${locatorToken(candidateLocator)}">
        <strong>${html(stepName(definition))}</strong>
        <span class="program-role">Matcher</span>
        <span class="program-shape">${html(definition.receives[0].shape)} to boolean</span>
        <small>${html(definition.id)}${definition.search_terms?.length
          ? ` · ${html(definition.search_terms.join(", "))}` : ""}</small>
      </button>`).join("") || '<p class="empty-options">No compatible matcher found.</p>';
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
    </div>`;
}

function nestedStep(operation, input, locator, step, index, size) {
  const definition = definitionOf(step.use);
  const previewInput = programPath(locator);
  return `
    <div class="nested-step">
      <div class="nested-step-summary">
        <span>${html(stepName(definition))}</span>
        <span data-preview-input="${html(previewInput)}" data-preview-slot="${index}">${
          previewStage(operation, previewInput, index)
        }</span>
        ${inputFields(operation, definition.inputs, [...locator, index, "inputs"])}
      </div>
      <div>
        <button type="button" data-move-nested="${index}" data-direction="-1"
                data-program-locator="${locatorToken(locator)}" ${index === 0 ? "disabled" : ""}>Up</button>
        <button type="button" data-move-nested="${index}" data-direction="1"
                data-program-locator="${locatorToken(locator)}" ${index === size - 1 ? "disabled" : ""}>Down</button>
        <button type="button" data-remove-nested="${index}"
                data-program-locator="${locatorToken(locator)}">Remove</button>
      </div>
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
                <strong>${html(pathPart(part))}</strong><small>${entry.shape}${
                  entry.examples < entry.total ? ` · ${entry.examples}/${entry.total} examples` : ""
                }</small>
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
    <div class="picker-backdrop" data-close-picker>
      <section class="step-picker" role="dialog" aria-modal="true" aria-label="Add Step">
        <header><span class="eyebrow">Installed Steps</span><h2>Add ${state.picker.mode === "trigger"
          ? "Trigger" : "Step"}</h2></header>
        <input type="search" id="step-search" value="${html(state.picker.query)}"
               placeholder="Search by name or id" autocomplete="off" autofocus>
        <div id="step-options">${pickerOptions()}</div>
      </section>
    </div>`;
}

function iconPicker() {
  if (!state.iconPicker) {
    return "";
  }
  return `
    <div class="picker-backdrop icon-picker-backdrop">
      <section class="step-picker icon-picker" role="dialog" aria-modal="true" aria-label="Choose icon">
        <header class="icon-picker-heading">
          <div><span class="eyebrow">Portable icons</span><h2>Choose icon</h2></div>
          <button type="button" id="close-icon-picker">Cancel</button>
        </header>
        <input type="search" id="icon-search" value="${html(state.iconPicker.query)}"
               placeholder="Search built-in and custom icons" autocomplete="off" autofocus>
        <div id="icon-options">${iconOptions()}</div>
      </section>
    </div>`;
}

function iconOptions() {
  const query = state.iconPicker?.query.toLowerCase() || "";
  return state.icons
    .filter(icon => !query || icon.id.toLowerCase().includes(query)
      || icon.name.toLowerCase().includes(query))
    .map(icon => `
      <button type="button" class="icon-option" data-select-icon="${html(icon.id)}">
        ${iconMarkup(icon)}
        <span><strong>${html(icon.name)}</strong><small>${html(icon.id)}</small></span>
      </button>`).join("") || '<p class="empty-options">No icon matches.</p>';
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
      || automaticBindingsAvailable(state.picker.anchor, state.picker.outcome, definition));
  return options
    .map(definition => `
      <button type="button" class="catalog-option" data-add-step="${html(definition.id)}">
        <span class="catalog-kind">${html(
          definition.kind.charAt(0).toUpperCase() + definition.kind.slice(1)
        )}</span>
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
  const targets = structuralStepIds(afterId);
  const targetOutcomes = new Map(targets.map(target => [
    target,
    alignedOutcome(after, node(target), outcome)
  ]));
  if (targets.some(target => !insertionAllowed(node(target), targetOutcomes.get(target)))) {
    return false;
  }
  const bindings = new Map(targets.map(target => [target, graphBindings(node(target), definition)]));
  if ([...bindings.values()].some(binding => binding === null)) {
    return false;
  }
  const inserted = new Map(targets.map(target => [target, opaqueId("step")]));
  targets.forEach(target => insertFlatStep(
    target,
    definition,
    inserted.get(target),
    targetOutcomes.get(target),
    bindings.get(target)
  ));
  const slots = new Map();
  targets.forEach(target =>
    occurrenceMemberships(target).forEach(membership => {
    const key = membership.group.id + "\u0000" + membership.slot;
    const slot = slots.get(key) || opaqueId("slot");
    slots.set(key, slot);
    insertOccurrenceStep(membership.occurrence, membership.slot, slot, inserted.get(target));
    }));
  state.selection = { type: "step", id: inserted.get(afterId) };
  state.editScope = null;
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
  const index = state.project.nodes.findIndex(candidate => candidate.id === after.id);
  state.project.nodes.splice(index + 1, 0, {
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

function automaticBindingsAvailable(afterId, selectedOutcome, definition) {
  const after = node(afterId);
  if (!after) {
    return false;
  }
  const outcome = selectedOutcome || primaryOutcome(after);
  const targets = structuralStepIds(afterId);
  return targets.every(target => graphBindings(node(target), definition) !== null);
}

function insertOccurrenceStep(occurrence, afterSlot, slot, id) {
  occurrence.steps = Object.fromEntries(Object.entries(occurrence.steps).flatMap(entry =>
    entry[0] === afterSlot ? [entry, [slot, id]] : [entry]
  ));
}

function deleteSelection() {
  if (state.selection.type === "trigger") {
    const trigger = node(state.selection.id);
    const ids = new Set([trigger.id, ...reachableSteps(trigger).map(candidate => candidate.id)]);
    state.project.nodes = state.project.nodes.filter(candidate => !ids.has(candidate.id));
    state.project.links = state.project.links.filter(link =>
      !ids.has(link.from.split(".")[0]) && !ids.has(link.to)
    );
    removeCreatorReferences(ids);
    state.selection = { type: "app", id: "app" };
  } else if (state.selection.type === "step" && removableStep(node(state.selection.id))) {
    removeStepGroup(state.selection.id);
  } else {
    return;
  }
  dirty(true);
}

function removeStepGroup(id) {
  const selected = node(id);
  const removed = new Set(structuralStepIds(id));
  let predecessor = state.project.links.find(link => link.to === selected.id);
  while (predecessor && removed.has(linkNode(predecessor))) {
    predecessor = state.project.links.find(link => link.to === linkNode(predecessor));
  }
  const predecessorId = predecessor ? linkNode(predecessor) : "";
  removed.forEach(removeFlatStep);
  removeCreatorReferences(removed);
  state.jsonDraft = null;
  state.editScope = null;
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

function selectExample(index) {
  const trigger = node(state.selection.id);
  state.exampleIndex = Math.max(0, Math.min(index, trigger.examples.length - 1));
  state.exampleDraft = null;
  clearPreview(true, false);
  render();
  requestSelectedTrace();
}

function addExample() {
  const trigger = node(state.selection.id);
  const used = new Set(trigger.examples.map(example => example.name));
  let index = trigger.examples.length + 1;
  while (used.has("example-" + index)) {
    index++;
  }
  trigger.examples.push({
    name: "example-" + index,
    payload: clone(selectedExample(trigger).payload),
    ...(plainObject(selectedExample(trigger).context)
      ? { context: clone(selectedExample(trigger).context) } : {})
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
  state.exampleIndex = Math.max(0, Math.min(state.exampleIndex, trigger.examples.length - 1));
  return trigger.examples[state.exampleIndex];
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
  const targets = input.authored_outcomes
    ? structuralStepIds(operation.id).map(node).filter(Boolean)
    : [operation];
  const label = input.authored_outcomes ? nextCaseLabel(operation) : "";
  targets.forEach(target => {
    let candidates = valueAt(target, locator);
    if (!Array.isArray(candidates)) {
      candidates = [];
      setAt(target, locator, candidates);
    }
    const candidate = authoredCandidate(option, input);
    candidates.push(candidate);
    if (input.authored_outcomes) {
      state.project.links.push({ from: target.id + "." + candidate.outcome, to: "end" });
      setOutcomeLabel(target.id, candidate.outcome, label);
    }
  });
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
  alignedCandidates(selectedOperation(), index)
    .forEach(item => setOutcomeLabel(item.operation.id, item.candidate.outcome, label));
  creatorDirty();
}

function moveListItem(locator, index, direction, input = {}) {
  const items = valueAt(selectedOperation(), locator);
  const target = index + direction;
  if (!Array.isArray(items) || target < 0 || target >= items.length) {
    return;
  }
  const lists = input.authored_outcomes
    ? structuralStepIds(selectedOperation().id).map(id => valueAt(node(id), locator))
    : [items];
  if (lists.some(list => !Array.isArray(list) || target >= list.length)) {
    return;
  }
  moveDraft(locator, index, target);
  lists.forEach(list => [list[index], list[target]] = [list[target], list[index]]);
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
    const aligned = alignedCandidates(selectedOperation(), index);
    if (aligned.some(item => !item.candidate
        || outcomeTarget(item.operation, item.candidate.outcome) !== "end")) {
      return;
    }
    const routes = new Set(aligned.map(item => item.operation.id + "." + item.candidate.outcome));
    state.project.links = state.project.links.filter(link => !routes.has(link.from));
    aligned.forEach(item => {
      setOutcomeLabel(item.operation.id, item.candidate.outcome);
      valueAt(item.operation, locator).splice(index, 1);
    });
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
  return locator.reduce((value, part) => value?.[part], root);
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
  const index = state.project.nodes.findIndex(candidate => candidate.id === nodeId);
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
  const operation = ["trigger", "step"].includes(state.selection.type)
    ? node(state.selection.id)
    : null;
  const trigger = operation ? triggerFor(operation.id) : null;
  if (!trigger?.examples.length) {
    return "";
  }
  const index = Math.max(0, Math.min(state.exampleIndex, trigger.examples.length - 1));
  return state.exampleIds.get(trigger.id)?.get(index) || "";
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
}

function exampleProgress() {
  const examples = state.application.examples;
  return examples?.total === undefined
    ? "Unavailable"
    : `${examples.completed || 0} / ${examples.total}`;
}

function metricsSetting(operation) {
  return `
    <section class="inspector-section metric-setting">
      <label class="check-line" for="node-metrics">
        <span>Operational metrics</span>
        <input id="node-metrics" type="checkbox" data-node-metrics
               ${operation.metrics === false ? "" : "checked"}>
      </label>
    </section>`;
}

function metricTarget() {
  if (state.inspectorMode !== "inspect") {
    return "";
  }
  if (state.selection.type === "app") {
    return "app";
  }
  return ["trigger", "step"].includes(state.selection.type) ? state.selection.id : "";
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
  state.metrics = null;
  state.metricsNode = "";
  renderMetrics();
  if (poll && metricTarget()) {
    scheduleMetricsPoll(0);
  }
}

async function refreshMetrics() {
  state.metricsPollTimer = 0;
  const target = metricTarget();
  if (!target) {
    resetMetrics();
    return;
  }
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
    if (state.metricsController !== controller
        || metricTarget() !== target
        || !currentApplication(application)
        || Number(metrics.application_pid) !== Number(application.pid)) {
      return;
    }
    state.metrics = metrics;
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
}

function metricsPanel() {
  const target = metricTarget();
  if (!target || state.metricsNode !== target || !plainObject(state.metrics)) {
    return "";
  }
  if (target === "app") {
    const application = state.metrics.application?.metrics || {};
    const process = state.metrics.process || {};
    return metricFacts("Runtime metrics", [
      ["Uptime", formatMillis(process.uptime_millis)],
      ["Executions", formatInteger(application.executions)],
      ["Errors", formatInteger(application.errors)],
      ["Cancelled", formatInteger(application.cancelled)],
      ["In flight", formatInteger(application.in_flight)],
      ["Heap", process.heap_used_bytes === undefined ? null
        : `${formatBytes(process.heap_used_bytes)} / ${formatBytes(process.heap_committed_bytes)}`],
      ["CPU", process.process_cpu_load_ppm === undefined
        ? null : formatPercentPpm(process.process_cpu_load_ppm)],
      ["Threads", process.live_threads === undefined ? null
        : `${formatInteger(process.live_threads)} / ${formatInteger(process.peak_threads)} peak`],
      ["GC", process.gc_collections === undefined ? null
        : `${formatInteger(process.gc_collections)} / ${formatMillis(process.gc_millis)}`],
      ["Metric counters", formatBytes(state.metrics.metric_counter_bytes)]
    ]);
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
    rows.push(
      [flow ? "Step executions" : "Executions", formatInteger(step.executions)],
      [flow ? "Step errors" : "Errors", formatInteger(step.errors)],
      [flow ? "Step cancelled" : "Cancelled", formatInteger(step.cancelled)],
      [flow ? "Step in flight" : "In flight",
        step.in_flight === undefined ? null : formatInteger(step.in_flight)],
      [flow ? "Step sampled average" : "Sampled average", averageNanos(step)],
      [flow ? "Step sampled maximum" : "Sampled maximum", formatNanos(step.duration_nanos_max)]
    );
  }
  if (flow) {
    rows.push(
      ["Flow executions", formatInteger(flow.executions)],
      ["Flow errors", formatInteger(flow.errors)],
      ["Flow cancelled", formatInteger(flow.cancelled)],
      ["Flow in flight", formatInteger(flow.in_flight)],
      ["Flow average", averageNanos(flow)],
      ["Flow maximum", formatNanos(flow.duration_nanos_max)]
    );
  }
  return metricFacts("Operational metrics", rows);
}

function metricFacts(title, rows) {
  const facts = rows
    .filter(([_label, value]) => value !== null && value !== undefined)
    .map(([label, value]) => `<div><dt>${html(label)}</dt><dd>${html(value)}</dd></div>`)
    .join("");
  return `<section class="inspector-section facts runtime-metrics">
    <div class="section-heading"><strong>${html(title)}</strong><span>Connected</span></div>
    <dl>${facts}</dl>
  </section>`;
}

function averageNanos(metrics) {
  const samples = metricNumber(metrics.duration_samples);
  return formatNanos(samples ? metricNumber(metrics.duration_nanos_total) / samples : 0);
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
  if (state.traceKey === key && state.traceSummary && state.traceCasesKey === casesKey) {
    selectTracePreview(example);
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
    state.traceStep = state.selection.type === "step"
        && observation.context === "input"
        && plainObject(selected?.projection)
      ? selected.projection
      : null;
    state.previewCases = observation.context === "input"
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
      selected_candidates: selectedCandidates(operation, reached.options || {})
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

function selectedCandidates(operation, options) {
  const selected = {};
  const definition = definitionFor(operation);
  definition?.inputs.filter(input => input.type === "candidates").forEach(input => {
    const candidates = operation.inputs?.[input.name] || [];
    const index = candidates.findIndex(candidate => candidate.option === options[input.name]);
    if (index >= 0) {
      selected[inputDiagnosticPath(operation.id, ["inputs", input.name])] = index;
    }
  });
  return selected;
}

function refreshTraceView() {
  applyExampleCoverage();
  refreshPickerOptions();
  if (state.pathPicker) {
    renderBuildStatus();
    refreshPathPicker();
    return;
  }
  refreshNestedOptions();
  renderPreview();
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
  return !container.matches(":hover") && !(active instanceof Element && container.contains(active));
}

function applyExampleCoverage() {
  const examples = state.application.examples;
  if (!state.project || !examples) {
    return;
  }
  const deployed = state.builtProject || state.project;
  const covered = exampleCoverage(examples.coverage_bits, deployed);
  const selected = new Set([...(state.traceSummary?.nodes || [])]
    .map(index => deployed.nodes[index]?.id)
    .filter(Boolean));
  if (selectedTraceCase()?.trigger) {
    selected.add(selectedTraceCase().trigger);
  }
  const complete = examples.state === "completed";
  document.querySelectorAll("[data-node-id]").forEach(element => {
    const id = element.dataset.nodeId;
    element.classList.toggle("example-reached", selected.has(id));
    element.classList.toggle("example-uncovered", complete
      && !["app", selectedTraceCase()?.trigger].includes(id)
      && !covered.has(id));
  });
}

function exampleCoverage(encoded, project) {
  const bytes = encoded
    ? Uint8Array.from(atob(encoded), character => character.charCodeAt(0))
    : new Uint8Array();
  return new Set(project.nodes.filter((_operation, index) =>
    (bytes[index >> 3] & (1 << (index & 7))) !== 0).map(operation => operation.id));
}

function renderPreview() {
  const operation = state.selection.type === "step" ? selectedOperation() : null;
  const values = document.querySelector("#preview-values");
  if (!operation || !values) {
    return;
  }
  values.innerHTML = previewSource(operation);
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

function previewSource(operation) {
  const preview = state.preview?.step === operation.id ? state.preview : null;
  if (!preview) {
    return "";
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
  const returned = preview.returns || {};
  const outputs = definition.returns.flatMap(port => Object.hasOwn(returned, port.name)
    ? [[port.name, returned[port.name]]]
    : []);
  if (!values.length && preview.status !== "succeeded") {
    return `
      <section class="inspector-section preview-error">
        <strong>Preview unavailable</strong>
        <p>${html(preview.message)}</p>
      </section>`;
  }
  return `
    <section class="inspector-section">
      <div class="section-heading"><strong>Built example</strong><span>Resolved inputs</span></div>
      ${values.map(([name, value], index) => `
        <div class="preview-source">
          <span>${html(inputLabel(name))}</span>
          <output ${index === 0 ? 'id="preview-source"' : ""} data-preview-input-value="${html(name)}">${
            html(previewValue(value))
          }</output>
        </div>`).join("")}
    </section>
    ${outputs.length ? `<section class="inspector-section">
      <div class="section-heading"><strong>Built output</strong><span>Actual value</span></div>
      ${outputs.map(([name, value]) => `<div class="preview-source">
        <span>${html(inputLabel(name))}</span><output data-preview-output="${html(name)}">${
          html(previewValue(value))
        }</output>
      </div>`).join("")}
    </section>` : ""}`;
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
    propagateSharedOperation();
    if (state.jsonDraft && !node(state.jsonDraft.node)) {
      state.jsonDraft = null;
    }
    clearPreview(false, state.selection.type === "trigger");
    resetMetrics();
    state.pendingProject = true;
    state.build = "Building";
    state.runResult = "";
  }
  state.revision++;
  state.diagnostics = [];
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
    void drainWrites();
  }
}

async function drainWrites() {
  while (state.pendingWrite) {
    const write = state.pendingWrite;
    state.pendingWrite = null;
    await save(write.revision, write.projectChanged, write.projectSource, write.creatorSource);
  }
  state.writeActive = false;
}

async function save(revision, projectChanged, projectSource, creatorSource) {
  if (revision !== state.revision) {
    return false;
  }
  try {
    let payload = null;
    if (projectChanged) {
      const projectResponse = await fetch("/api/project", {
        method: "POST",
        headers: mutationHeaders(),
        body: projectSource
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
    }
    const creatorResponse = await fetch("/api/creator", {
      method: "POST",
      headers: mutationHeaders(),
      body: creatorSource
    });
    payload = parseExact(await creatorResponse.text());
    if (revision !== state.revision) {
      return creatorResponse.ok;
    }
    if (payload.application) {
      replaceApplication(payload.application);
    }
    if (!creatorResponse.ok) {
      state.diagnostics = payload.diagnostics || [{
        code: "CREATOR_METADATA_SAVE_FAILED",
        message: payload.message || "Creator metadata could not be saved.",
        path: ""
      }];
      state.build = projectChanged ? "Built" : state.build;
      render();
      return false;
    }
    state.project = payload.project;
    state.creator = payload.creator;
    state.builtProject = clone(payload.project);
    state.workspace = payload.workspace;
    state.diagnostics = [];
    state.build = "Built";
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
    const index = built.findIndex(deployed => deployed.id === candidate.id);
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
function iconMarkup(icon) {
  return icon?.media_type && icon?.data
    ? `<img class="flow-icon" src="${html("data:" + icon.media_type + ";base64," + icon.data)}" alt="">`
    : "";
}

function triggerNodes() {
  return state.project.nodes.filter(stepKind("trigger"));
}

function availableTriggers() {
  return state.catalog.filter(definition => {
    if (definition.kind !== "trigger" || !definition.examples?.length) {
      return false;
    }
    const used = state.project.nodes.filter(candidate => candidate.use === definition.id).length;
    const sourceUsed = definition.source && state.project.nodes.some(candidate =>
      definitionOf(candidate.use)?.source?.name === definition.source.name
    );
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

function alignedCandidates(operation, index) {
  return structuralStepIds(operation.id).map(id => {
    const target = node(id);
    return { operation: target, candidate: authoredOutcomes(target)[index] };
  });
}

function alignedOutcome(source, target, outcome) {
  const index = authoredOutcomes(source).findIndex(candidate => candidate.outcome === outcome);
  return index < 0 ? outcome : authoredOutcomes(target)[index]?.outcome || "";
}

function topologyOutcome(operation, outcome) {
  const index = authoredOutcomes(operation).findIndex(candidate => candidate.outcome === outcome);
  return index < 0 ? outcome : "@case[" + index + "]";
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

function groupOccurrence(id) {
  for (const group of state.creator.groups) {
    const occurrence = group.occurrences.find(candidate => candidate.id === id);
    if (occurrence) {
      return { group, occurrence };
    }
  }
  return null;
}

function currentGroup() {
  return groupOccurrence(state.groupStack.at(-1));
}

function occurrenceSteps(occurrence) {
  const ids = new Set(Object.values(occurrence.steps));
  const trigger = node(occurrence.flow);
  return trigger ? reachableSteps(trigger).filter(candidate => ids.has(candidate.id)) : [];
}

function occurrenceRegion(occurrence) {
  const operations = occurrenceSteps(occurrence);
  const ids = new Set(operations.map(operation => operation.id));
  const incoming = state.project.links.filter(link => ids.has(link.to) && !ids.has(linkNode(link)));
  const exits = operations.flatMap(operation => displayOutcomes(operation).flatMap(outcome => {
    const destinations = outcomeDestinations(operation, outcome);
    return destinations.length !== 1 || !ids.has(destinations[0])
      ? [{ source: operation.id, outcome }]
      : [];
  }));
  return {
    operations,
    entry: incoming.length === 1 ? node(incoming[0].to) : null,
    exits
  };
}

function occurrenceTopology(occurrence) {
  const concreteSlots = new Map(Object.entries(occurrence.steps).map(([slot, id]) => [id, slot]));
  const incoming = state.project.links.find(link =>
    concreteSlots.has(link.to) && !concreteSlots.has(linkNode(link))
  );
  return JSON.stringify({
    entry: concreteSlots.get(incoming?.to) || "",
    nodes: Object.keys(occurrence.steps).sort().map(slot => {
      const operation = node(occurrence.steps[slot]);
      return [slot, operation?.use || "", outcomes(operation).map(outcome => [
        topologyOutcome(operation, outcome),
        concreteSlots.get(outcomeTarget(operation, outcome)) || ""
      ])];
    })
  });
}

function occurrenceSlots(occurrence) {
  const slots = new Map(Object.entries(occurrence.steps).map(([slot, id]) => [id, slot]));
  return occurrenceSteps(occurrence).map(step => slots.get(step.id));
}

function groupName(group) {
  if (group?.name) {
    return group.name;
  }
  const first = group?.occurrences[0] && occurrenceSteps(group.occurrences[0])[0];
  return first ? stepPresentation(first.id).name || stepName(definitionFor(first)) : "Group";
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

function startGroupDraft(group = null) {
  if (group && !state.creator.groups.some(candidate => candidate.id === group)) {
    return;
  }
  state.groupDraft = { group, start: null, end: null };
  state.inspectorMode = "groups";
  render();
}

function chooseGroupBoundary(id) {
  if (!state.groupDraft || !node(id) || definitionFor(node(id))?.kind !== "step") {
    return false;
  }
  if (!state.groupDraft.start) {
    state.groupDraft.start = id;
    render();
    return true;
  }
  state.groupDraft.end = id;
  const selection = groupRange(state.groupDraft.start, id);
  if (!selection.length) {
    state.localDiagnostics = [{
      code: "CREATOR_GROUP_PATH_INVALID",
      message: "Group start and end must share one path in one flow.",
      path: "groups",
      node: state.groupDraft.start
    }];
    render();
    return true;
  }
  const trigger = flowTrigger(selection[0]);
  const occupied = new Set(state.creator.groups.flatMap(group => group.occurrences
    .filter(occurrence => occurrence.parent === (currentGroup()?.occurrence.id || null))
    .flatMap(occurrence => Object.values(occurrence.steps))));
  if (selection.some(step => occupied.has(step.id))) {
    state.localDiagnostics = [{
      code: "CREATOR_GROUP_RANGE_OVERLAP",
      message: "A Step can belong to only one group at this level.",
      path: "groups",
      node: selection.find(step => occupied.has(step.id)).id
    }];
    render();
    return true;
  }
  const existing = state.groupDraft.group
    ? state.creator.groups.find(group => group.id === state.groupDraft.group)
    : null;
  const slots = existing ? occurrenceSlots(existing.occurrences[0]) : [];
  if (existing && slots.length !== selection.length) {
    state.localDiagnostics = [{
      code: "CREATOR_GROUP_RANGE_SIZE_MISMATCH",
      message: "This occurrence must contain " + slots.length + " Steps.",
      path: "groups",
      node: selection[0].id
    }];
    render();
    return true;
  }
  const mappedSteps = Object.fromEntries(selection.map((step, index) => [
    slots[index] || opaqueId("slot"),
    step.id
  ]));
  if (existing && occurrenceTopology(existing.occurrences[0]) !== occurrenceTopology({ steps: mappedSteps })) {
    state.localDiagnostics = [{
      code: "CREATOR_GROUP_TOPOLOGY_MISMATCH",
      message: "This occurrence must have the same Steps and routes as the existing group.",
      path: "groups",
      node: selection[0].id
    }];
    render();
    return true;
  }
  const occurrence = {
    id: opaqueId("occurrence"),
    flow: trigger.id,
    parent: currentGroup()?.occurrence.id || null,
    steps: mappedSteps
  };
  if (existing) {
    existing.occurrences.push(occurrence);
  } else {
    state.creator.groups.push({
      id: opaqueId("group"),
      name: selection.length === 1
        ? stepPresentation(selection[0].id).name || stepName(definitionFor(selection[0]))
        : "Step Group",
      occurrences: [occurrence]
    });
  }
  state.groupDraft = null;
  state.selection = { type: "group", id: occurrence.id };
  state.inspectorMode = "inspect";
  creatorDirty();
  return true;
}

function groupRange(startId, endId) {
  const trigger = flowTrigger(node(startId));
  if (!trigger || flowTrigger(node(endId))?.id !== trigger.id) {
    return [];
  }
  const direct = groupPath(startId, endId);
  if (direct.length) {
    return direct;
  }
  return groupPath(endId, startId);
}

function groupPath(startId, endId) {
  const path = [];
  let current = endId;
  const seen = new Set();
  while (current && current !== "end" && seen.add(current)) {
    const operation = node(current);
    if (!operation || definitionFor(operation)?.kind !== "step") {
      return [];
    }
    path.push(operation);
    if (current === startId) {
      return path.reverse();
    }
    const incoming = state.project.links.filter(link => link.to === current);
    current = incoming.length === 1 ? linkNode(incoming[0]) : "";
  }
  return [];
}

function reachable(start, reverse) {
  const result = new Set([start]);
  const pending = [start];
  for (let index = 0; index < pending.length; index++) {
    const current = pending[index];
    const next = state.project.links
      .filter(link => reverse ? link.to === current : linkNode(link) === current)
      .map(link => reverse ? linkNode(link) : link.to)
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
  return triggerNodes().find(trigger => reachable(trigger.id, false).has(operation.id)) || null;
}

function openGroup(id) {
  if (!groupOccurrence(id)) {
    return;
  }
  state.groupStack.push(id);
  state.selection = { type: "group", id };
  state.inspectorMode = "inspect";
  render();
}

function closeGroup() {
  state.groupStack.pop();
  const current = currentGroup();
  state.selection = current
    ? { type: "group", id: current.occurrence.id }
    : { type: "app", id: "app" };
  render();
}

function deleteGroup(id) {
  const item = groupOccurrence(id);
  if (!item) {
    return;
  }
  const removed = new Map(item.group.occurrences.map(occurrence => [occurrence.id, occurrence.parent]));
  state.creator.groups = state.creator.groups.filter(group => group.id !== item.group.id);
  state.creator.groups.forEach(group => group.occurrences.forEach(occurrence => {
    while (removed.has(occurrence.parent)) {
      occurrence.parent = removed.get(occurrence.parent);
    }
  }));
  state.groupStack = state.groupStack.filter(occurrence => groupOccurrence(occurrence));
  state.selection = { type: "app", id: "app" };
  creatorDirty();
}

function occurrenceMemberships(stepId) {
  const memberships = [];
  for (const group of state.creator.groups) {
    for (const occurrence of group.occurrences) {
      const slot = Object.entries(occurrence.steps).find(([_slot, id]) => id === stepId)?.[0];
      if (slot) {
        memberships.push({ group, occurrence, slot });
      }
    }
  }
  return memberships;
}

function sharedMembership(stepId) {
  const memberships = occurrenceMemberships(stepId)
    .filter(membership => membership.group.occurrences.length > 1);
  const current = currentGroup()?.occurrence.id;
  return memberships.find(membership => membership.occurrence.id === current)
    || memberships[0] || null;
}

function structuralStepIds(stepId) {
  const shared = state.editScope === "all" ? sharedMembership(stepId) : null;
  if (!shared) {
    return [stepId];
  }
  const result = new Set(shared.group.occurrences.map(occurrence => occurrence.steps[shared.slot]));
  const pending = [...result];
  for (let index = 0; index < pending.length; index++) {
    occurrenceMemberships(pending[index])
      .filter(membership => membership.group.occurrences.length > 1)
      .forEach(membership => membership.group.occurrences.forEach(occurrence => {
        const target = occurrence.steps[membership.slot];
        if (!result.has(target)) {
          result.add(target);
          pending.push(target);
        }
      }));
  }
  return [...result];
}

function chooseSharedAction(action) {
  const membership = sharedMembership(state.selection.id);
  if (!membership) {
    state.editScope = "this";
    render();
    return;
  }
  if (action === "all") {
    state.editScope = "all";
  } else if (action === "detach" || action === "variant") {
    const variant = clone(membership.group);
    membership.group.occurrences = membership.group.occurrences
      .filter(occurrence => occurrence !== membership.occurrence);
    if (action === "variant") {
      state.creator.groups.push({
        ...variant,
        id: opaqueId("group"),
        name: groupName(membership.group) + " Variant",
        occurrences: [membership.occurrence]
      });
    } else {
      state.creator.groups.forEach(group => group.occurrences.forEach(occurrence => {
        if (occurrence.parent === membership.occurrence.id) {
          occurrence.parent = membership.occurrence.parent;
        }
      }));
      state.groupStack = state.groupStack.filter(id => groupOccurrence(id));
    }
    state.editScope = "this";
    creatorDirty();
  } else {
    state.selection = { type: "group", id: membership.occurrence.id };
    state.editScope = null;
  }
  render();
}

function propagateSharedOperation() {
  if (state.editScope !== "all" || state.selection.type !== "step") {
    return;
  }
  const membership = sharedMembership(state.selection.id);
  const source = node(state.selection.id);
  if (!membership || !source) {
    return;
  }
  membership.group.occurrences.forEach(occurrence => {
    const target = node(occurrence.steps[membership.slot]);
    if (target && target !== source) {
      target.use = source.use;
      target.inputs = sharedInputs(source, target);
    }
  });
}

function sharedInputs(source, target) {
  const inputs = clone(source.inputs);
  const authored = authoredOutcomeInput(definitionFor(source));
  const sourceCandidates = authored && inputs?.[authored.name];
  const targetCandidates = authored && target.inputs?.[authored.name];
  if (Array.isArray(sourceCandidates) && Array.isArray(targetCandidates)
      && sourceCandidates.length === targetCandidates.length) {
    sourceCandidates.forEach((candidate, index) => candidate.outcome = targetCandidates[index].outcome);
  }
  return inputs;
}

function removeCreatorReferences(ids) {
  ids.forEach(id => delete state.creator.steps[id]);
  const removedParents = new Map();
  state.creator.groups.forEach(group => {
    group.occurrences.forEach(occurrence => {
      occurrence.steps = Object.fromEntries(Object.entries(occurrence.steps)
        .filter(([_slot, id]) => !ids.has(id)));
      if (!Object.keys(occurrence.steps).length) {
        removedParents.set(occurrence.id, occurrence.parent);
      }
    });
    group.occurrences = group.occurrences.filter(occurrence => Object.keys(occurrence.steps).length);
  });
  state.creator.groups.forEach(group => group.occurrences.forEach(occurrence => {
    while (removedParents.has(occurrence.parent)) {
      occurrence.parent = removedParents.get(occurrence.parent);
    }
  }));
  state.creator.groups = state.creator.groups.filter(group => group.occurrences.length);
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
  state.inspectorMode = "inspect";
  state.editScope = null;
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
  if (!["name", "color"].includes(field)) {
    return;
  }
  const trimmed = source.trim();
  const value = field === "color" && /^#[0-9a-fA-F]{6}$/.test(trimmed)
    ? trimmed.toUpperCase()
    : trimmed;
  setPresentation(target, field, value || undefined);
}

function setPresentation(target, field, value) {
  const owner = presentationOwner(target);
  if (!owner || !["name", "color", "icon"].includes(field)) {
    return;
  }
  if (value !== undefined) {
    owner[field] = value;
  } else {
    delete owner[field];
  }
  const [kind, id] = target.split(":", 2);
  if (kind === "step" && state.editScope === "all") {
    const membership = sharedMembership(id);
    membership?.group.occurrences.forEach(occurrence => {
      const targetId = occurrence.steps[membership.slot];
      if (targetId === id) {
        return;
      }
      state.creator.steps[targetId] = state.creator.steps[targetId] || {};
      if (value === undefined) {
        delete state.creator.steps[targetId][field];
      } else {
        state.creator.steps[targetId][field] = clone(value);
      }
      if (!Object.keys(state.creator.steps[targetId]).length) {
        delete state.creator.steps[targetId];
      }
    });
  }
  if (kind === "step" && !Object.keys(owner).length) {
    delete state.creator.steps[id];
  }
  creatorDirty();
}

function node(id) {
  return state.project.nodes.find(candidate => candidate.id === id);
}

function definitionOf(id) {
  return state.catalog.find(definition => definition.id === id);
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

document.addEventListener("click", event => {
  const target = event.target;
  if (target.closest("#close-group")) {
    closeGroup();
    return;
  }
  const inspectorMode = target.closest("[data-inspector-mode]");
  if (inspectorMode) {
    resetMetrics();
    state.inspectorMode = inspectorMode.dataset.inspectorMode;
    render();
    scheduleMetricsPoll(0);
    return;
  }
  if (target.closest("#new-group")) {
    startGroupDraft();
    return;
  }
  const addOccurrence = target.closest("[data-add-occurrence]");
  if (addOccurrence) {
    startGroupDraft(addOccurrence.dataset.addOccurrence);
    return;
  }
  if (target.closest("#cancel-group-draft")) {
    state.groupDraft = null;
    render();
    return;
  }
  if (target.closest("#open-group") && state.selection.type === "group") {
    openGroup(state.selection.id);
    return;
  }
  if (target.closest("#delete-group") && state.selection.type === "group") {
    deleteGroup(state.selection.id);
    return;
  }
  const managed = target.closest("[data-manage-occurrence]");
  if (managed) {
    state.selection = { type: "group", id: managed.dataset.manageOccurrence };
    state.inspectorMode = "inspect";
    render();
    return;
  }
  const sharedAction = target.closest("[data-shared-action]");
  if (sharedAction) {
    chooseSharedAction(sharedAction.dataset.sharedAction);
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
  const openIcon = target.closest("[data-open-icon-picker]");
  if (openIcon) {
    state.picker = null;
    state.iconPicker = { target: openIcon.dataset.openIconPicker, query: "" };
    render();
    document.querySelector("#icon-search")?.focus();
    return;
  }
  const selectedIcon = target.closest("[data-select-icon]");
  if (selectedIcon && state.iconPicker) {
    const icon = state.icons.find(candidate => candidate.id === selectedIcon.dataset.selectIcon);
    if (icon) {
      const owner = state.iconPicker.target;
      state.iconPicker = null;
      setPresentation(owner, "icon", { media_type: icon.media_type, data: icon.data });
    }
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
  if (target.closest("#close-icon-picker") || target.matches(".icon-picker-backdrop")) {
    state.iconPicker = null;
    render();
    return;
  }
  if (target.matches(".picker-backdrop")) {
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
      Number(moveCandidateButton.dataset.direction),
      parseToken(moveCandidateButton.dataset.inputMeta)
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
  const step = target.closest("[data-select-step]");
  if (step) {
    if (!chooseGroupBoundary(step.dataset.selectStep)) {
      selectStep(step.dataset.selectStep);
    }
    return;
  }
  const group = target.closest("[data-select-group]");
  if (group) {
    resetMetrics();
    state.selection = { type: "group", id: group.dataset.selectGroup };
    state.inspectorMode = "inspect";
    state.editScope = null;
    render();
    return;
  }
  const selected = target.closest("[data-select-node]");
  if (selected) {
    clearPreview();
    resetMetrics();
    state.exampleDraft = null;
    const id = selected.dataset.selectNode;
    state.selection = id === "app" ? { type: "app", id } : { type: "trigger", id };
    state.inspectorMode = "inspect";
    state.editScope = null;
    if (id !== "app") {
      state.exampleIndex = 0;
    }
    state.pathPicker = null;
    clearInputQueries();
    render();
    scheduleMetricsPoll(0);
    if (id !== "app") {
      requestSelectedTrace();
    }
  }
});

document.addEventListener("input", event => {
  if (event.target.id === "step-search" && state.picker) {
    state.picker.query = event.target.value;
    document.querySelector("#step-options").innerHTML = pickerOptions();
  } else if (event.target.id === "icon-search" && state.iconPicker) {
    state.iconPicker.query = event.target.value;
    document.querySelector("#icon-options").innerHTML = iconOptions();
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
  const target = event.target;
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
    updatePresentation(target.dataset.presentationTarget, target.dataset.presentation, target.value);
  }
});

document.addEventListener("keydown", event => {
  if (event.key === "Escape" && (state.picker || state.iconPicker)) {
    state.picker = null;
    state.iconPicker = null;
    render();
    return;
  }
  if (event.key !== "Enter" && event.key !== " ") {
    return;
  }
  const selectable = event.target.closest("[data-select-node], [data-select-step], [data-select-group]");
  if (!selectable || event.target !== selectable) {
    return;
  }
  event.preventDefault();
  selectable.click();
});

document.addEventListener("visibilitychange", () => {
  if (!document.hidden) {
    scheduleMetricsPoll(0);
  }
});
