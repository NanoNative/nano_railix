let flow = {
  id: "lowercase-app",
  triggers: [
    { id: "command", type: "cli", config: { stdin: true } }
  ],
  entry: "lowercase",
  inputs: { text: "string" },
  outputs: { text: "string" },
  steps: [
    { id: "lowercase", use: "text.lowercase", config: {}, on: { ok: "end" } }
  ],
  connections: [
    { from: "input.text", to: "lowercase.text" },
    { from: "lowercase.text", to: "output.text" }
  ]
};

const stateLabel = document.querySelector("#compile-state");
const projectName = document.querySelector("#project-name");
const consoleOutput = document.querySelector("#console-output");
const inputField = document.querySelector("#sample-input");
const eventFormatButtons = document.querySelectorAll("[data-event-format]");
const flowFileInput = document.querySelector("#flow-file");
const stepList = document.querySelector("#step-list");
const stepCount = document.querySelector("#step-count");
const search = document.querySelector("#step-search");
const flowSummary = document.querySelector("#flow-summary");
const flowCanvas = document.querySelector(".flow-canvas");
const flowNodes = document.querySelector("#flow-nodes");
const triggerNodes = document.querySelector("#trigger-nodes");
const applicationNode = document.querySelector("#application-node");
const outputBoundary = document.querySelector(".output-boundary");
const connections = document.querySelector(".connections");
const inspectorStepId = document.querySelector("#inspector-step-id");
const applicationName = document.querySelector("#application-name");
const applicationSettings = document.querySelector("#application-settings");
const stepContractSection = document.querySelector("#step-contract-section");
const mappingSection = document.querySelector("#mapping-section");
const configurationSection = document.querySelector("#configuration-section");
const flowContractSection = document.querySelector("#flow-contract-section");
const triggerSection = document.querySelector("#trigger-section");
const httpSection = document.querySelector("#http-section");
const socketSection = document.querySelector("#socket-section");
const scheduleSection = document.querySelector("#schedule-section");
const triggerMenu = document.querySelector("#trigger-menu");
const triggerHelp = document.querySelector("#trigger-help");
const catalogTitle = document.querySelector("#catalog-title");
const catalogDescription = document.querySelector("#catalog-description");
const cancelStepChoice = document.querySelector("#cancel-step-choice");
const contractTable = document.querySelector("#contract-table");
const dataMappings = document.querySelector("#data-mappings");
const stepConfig = document.querySelector("#step-config");
const flowInputPorts = document.querySelector("#flow-inputs");
const flowOutputPorts = document.querySelector("#flow-outputs");
const httpPort = document.querySelector("#http-port");
const newHttpPath = document.querySelector("#new-http-path");
const httpRoutes = document.querySelector("#http-routes");
const flowEventToggle = document.querySelector("#flow-event-toggle");
const stepEventToggle = document.querySelector("#step-event-toggle");
const flowEventEndpoint = document.querySelector("#flow-event-endpoint");
const stepEventEndpoint = document.querySelector("#step-event-endpoint");
const socketToggle = document.querySelector("#socket-toggle");
const socketPort = document.querySelector("#socket-port");
const socketTimeout = document.querySelector("#socket-timeout");
const socketConnections = document.querySelector("#socket-connections");
const socketEndpoint = document.querySelector("#socket-endpoint");
const schedules = document.querySelector("#schedules");
const noSchedules = document.querySelector("#no-schedules");
const draftState = document.querySelector("#draft-state");
const draftDiagnostics = document.querySelector("#draft-diagnostics");

let availableSteps = [];
let availableShapes = [];
let availableConversions = [];
let maxEventSourceBytes;
let selectedStepId = "lowercase";
let selectedEntity = "application";
let stepChoice;
let draftRevision = 0;
let draftController;
let eventFormat = "json";
let generatedNameIndex = Math.floor(Date.now() / 1000) % 1728;
const generatedNames = new Set();
const nameParts = [
  ["atomic", "brisk", "cosmic", "eager", "lunar", "neon", "quiet", "rapid", "solar", "steady", "tiny", "vivid"],
  ["byte", "cache", "flux", "kernel", "pixel", "quark", "signal", "stack", "thread", "vector", "voxel", "wave"],
  ["array", "forge", "grid", "node", "relay", "rig", "socket", "spark", "switch", "vault", "wire", "yard"]
];
generatedNames.add(flow.id);
const eventExamples = {
  json: inputField.value,
  yaml: "text: \"Hello RAILIX\"",
  xml: "<object><field name=\"text\"><string>Hello RAILIX</string></field></object>"
};
inputField.disabled = true;
const kindMarks = {
  step: "Fn",
  validator: "V",
  normalizer: "N",
  mapper: "M",
  translator: "T"
};

function element(tag, className = "", text = "") {
  const node = document.createElement(tag);
  node.className = className;
  node.textContent = text;
  return node;
}

function renderConsole(value) {
  consoleOutput.textContent = typeof value === "string"
    ? value
    : JSON.stringify(value, null, 2);
}

function setState(label, kind) {
  stateLabel.textContent = label;
  stateLabel.className = kind;
}

function diagnosticItem(diagnostic, className = "draft-diagnostic") {
  const item = element("li", className);
  item.append(
    element("strong", "", diagnostic.code),
    element("code", "", diagnostic.path),
    element("span", "", diagnostic.message)
  );
  return item;
}

function renderDraftFeedback(ok, payload) {
  const diagnostics = ok
    ? []
    : payload.diagnostics || (payload.error ? [{ ...payload.error, path: "$" }] : []);
  draftState.textContent = ok
    ? "Draft valid"
    : `${diagnostics.length} ${diagnostics.length === 1 ? "issue" : "issues"}`;
  draftState.className = `draft-state ${ok ? "valid" : "invalid"}`;
  draftDiagnostics.replaceChildren(...diagnostics.map((diagnostic) => diagnosticItem(diagnostic)));
  for (const local of dataMappings.querySelectorAll(".mapping-diagnostic")) {
    local.replaceChildren();
  }
  for (const diagnostic of diagnostics) {
    const match = /^connections\[(\d+)]/.exec(diagnostic.path);
    const local = match && dataMappings.querySelector(
      `.mapping-editor[data-connection-index="${match[1]}"] .mapping-diagnostic`
    );
    if (local) {
      local.append(diagnosticItem(diagnostic, "mapping-diagnostic-item"));
    }
  }
}

function finishAuthoring(event) {
  setState("Not validated", "");
  renderSteps(search.value);
  renderFlow();
  renderInspector();
  renderConsole(event);
  checkDraft();
}

function finishConfigAuthoring(event) {
  setState("Not validated", "");
  renderConsole(event);
  checkDraft();
}

function titleFor(identifier) {
  const name = identifier.split(".").at(-1) || identifier;
  return humanize(name);
}

function definitionFor(invocation) {
  return availableSteps.find((definition) => definition.id === invocation.use);
}

function kindFor(definition) {
  return definition?.kind || "step";
}

function presentationClasses(base, definition) {
  const kind = kindFor(definition);
  return `${base} kind-${kind}${kind === "step" ? "" : " lightweight"}`;
}

function mappingFor(target) {
  return flow.connections.find((connection) => connection.to === target);
}

function humanize(identifier) {
  return identifier
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .replace(/[-_.]+/g, " ")
    .replace(/(\D)(\d+)$/, "$1 $2")
    .trim()
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function invocationLabel(identifier) {
  const invocation = flow.steps.find((step) => step.id === identifier);
  if (!invocation) {
    return humanize(identifier);
  }
  const base = titleFor(invocation.use);
  const suffix = /(\d+)$/.exec(invocation.id);
  return suffix ? `${base} ${suffix[1]}` : base;
}

function endpointLabel(endpoint, role = "") {
  const [owner, port] = endpoint.split(".");
  if (owner === "input") {
    return `App input · ${humanize(port)}`;
  }
  if (owner === "output") {
    return `App output · ${humanize(port)}`;
  }
  const suffix = role ? ` ${role}` : "";
  return `${invocationLabel(owner)} · ${humanize(port)}${suffix}`;
}

function compatibleShape(source, target) {
  return target === "any" || source === target;
}

function graphLayout() {
  const order = new Map(flow.steps.map((step, index) => [step.id, index]));
  const byId = new Map(flow.steps.map((step) => [step.id, step]));
  const reachable = new Set();
  const pending = byId.has(flow.entry) ? [flow.entry] : [];
  while (pending.length > 0) {
    const id = pending.shift();
    if (reachable.has(id)) {
      continue;
    }
    reachable.add(id);
    for (const target of Object.values(byId.get(id)?.on || {})) {
      if (target !== "end" && byId.has(target)) {
        pending.push(target);
      }
    }
  }

  const indegree = new Map([...reachable].map((id) => [id, 0]));
  for (const id of reachable) {
    for (const target of Object.values(byId.get(id).on)) {
      if (indegree.has(target) && target !== flow.entry) {
        indegree.set(target, indegree.get(target) + 1);
      }
    }
  }
  const queue = flow.entry && reachable.has(flow.entry) ? [flow.entry] : [];
  const depths = new Map(queue.map((id) => [id, 0]));
  const processed = new Set();
  while (queue.length > 0) {
    queue.sort((left, right) => order.get(left) - order.get(right) || left.localeCompare(right));
    const id = queue.shift();
    if (processed.has(id)) {
      continue;
    }
    processed.add(id);
    for (const target of Object.values(byId.get(id).on)) {
      if (!indegree.has(target)) {
        continue;
      }
      depths.set(target, Math.max(depths.get(target) || 0, depths.get(id) + 1));
      indegree.set(target, indegree.get(target) - 1);
      if (indegree.get(target) === 0) {
        queue.push(target);
      }
    }
  }
  let maxDepth = depths.size === 0 ? -1 : Math.max(...depths.values());
  for (const step of flow.steps) {
    if (!processed.has(step.id)) {
      depths.set(step.id, maxDepth + 1);
    }
  }
  maxDepth = depths.size === 0 ? -1 : Math.max(...depths.values());
  const groups = new Map();
  for (const step of flow.steps) {
    const depth = depths.get(step.id) ?? 0;
    if (!groups.has(depth)) {
      groups.set(depth, []);
    }
    groups.get(depth).push(step);
  }
  for (const steps of groups.values()) {
    steps.sort((left, right) => order.get(left.id) - order.get(right.id) || left.id.localeCompare(right.id));
  }
  const maxRows = Math.max(1, ...[...groups.values()].map((steps) => steps.length));
  const maxTriggers = Math.max(1, flow.triggers.length);
  const height = Math.max(560, maxRows * 240 + 160, maxTriggers * 104 + 160);
  const centerY = height / 2;
  const positions = new Map();
  for (const [depth, steps] of groups) {
    const firstY = centerY - (steps.length - 1) * 120;
    steps.forEach((step, row) => positions.set(step.id, {
      x: 650 + depth * 340,
      y: firstY + row * 240,
      depth,
      row
    }));
  }
  const outputX = 650 + Math.max(0, maxDepth + 1) * 340;
  const width = Math.max(1050, outputX + 220);
  const triggers = flow.triggers.map((trigger, row) => ({
    id: trigger.id,
    x: 100,
    y: centerY - (flow.triggers.length - 1) * 52 + row * 104,
    row
  }));
  return {
    width,
    height,
    centerY,
    application: { x: 370, y: centerY },
    output: { x: outputX, y: centerY },
    positions,
    reachable,
    triggers,
    key: JSON.stringify({
      triggers: triggers.map((trigger) => trigger.id),
      steps: flow.steps.map((step) => {
        const position = positions.get(step.id);
        return [step.id, position.depth, position.row];
      })
    })
  };
}

function endpointPosition(endpoint, source, layout) {
  if (endpoint.startsWith("input.")) {
    return [layout.application.x + 145, layout.application.y - 18];
  }
  if (endpoint.startsWith("output.")) {
    return [layout.output.x - 56, layout.output.y];
  }
  const owner = endpoint.slice(0, endpoint.lastIndexOf("."));
  const position = layout.positions.get(owner) || { x: layout.application.x, y: layout.application.y };
  const invocation = flow.steps.find((step) => step.id === owner);
  const halfWidth = kindFor(invocation ? definitionFor(invocation) : undefined) === "step" ? 150 : 120;
  return [position.x + (source ? halfWidth : -halfWidth), position.y];
}

function wire(className, from, to, data = {}) {
  const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
  const bend = Math.max(70, Math.abs(to[0] - from[0]) / 2);
  path.setAttribute("class", className);
  path.setAttribute("d", `M${from[0]} ${from[1]} C${from[0] + bend} ${from[1]} ${to[0] - bend} ${to[1]} ${to[0]} ${to[1]}`);
  for (const [name, value] of Object.entries(data)) {
    path.dataset[name] = value;
  }
  return path;
}

function renderConnections(layout) {
  const rendered = [];
  for (const trigger of layout.triggers) {
    rendered.push(wire(
      "wire trigger-wire",
      [trigger.x + 100, trigger.y],
      [layout.application.x - 145, layout.application.y],
      { trigger: trigger.id }
    ));
  }
  if (layout.positions.has(flow.entry)) {
    const entry = layout.positions.get(flow.entry);
    rendered.push(wire(
      "wire control-wire",
      [layout.application.x + 145, layout.application.y],
      [entry.x - 150, entry.y],
      { control: `entry:${flow.entry}` }
    ));
  }
  for (const invocation of flow.steps) {
    const source = layout.positions.get(invocation.id);
    for (const [outcome, target] of Object.entries(invocation.on)) {
      const destination = target === "end"
        ? [layout.output.x - 56, layout.output.y]
        : layout.positions.has(target)
          ? [layout.positions.get(target).x - 150, layout.positions.get(target).y]
          : [source.x + 180, source.y];
      rendered.push(wire(
        "wire control-wire",
        [source.x + 150, source.y + 54],
        destination,
        { outcome: `${invocation.id}.${outcome}`, target }
      ));
    }
  }
  for (const connection of flow.connections) {
    rendered.push(wire(
      `wire data-wire${connection.to.startsWith("output.") ? " output-wire" : ""}`,
      endpointPosition(connection.from, true, layout),
      endpointPosition(connection.to, false, layout),
      { from: connection.from, to: connection.to }
    ));
  }
  connections.replaceChildren(...rendered);
}

function renderBoundary(boundary, title, ports, output) {
  const rows = element("div", "boundary-ports");
  for (const [name, shape] of Object.entries(ports)) {
    const row = element("div", "boundary-port");
    const connector = element("i", `port ${output ? "port-left" : "port-right"}`);
    if (output) {
      row.append(connector, element("strong", "", name), element("code", "", shape));
    } else {
      row.append(element("strong", "", name), element("code", "", shape), connector);
    }
    rows.append(row);
  }
  if (rows.childElementCount === 0) {
    rows.append(element("strong", "", "None"));
  }
  boundary.replaceChildren(element("span", "", title), rows);
}

function renderSteps(filter = "") {
  const query = filter.trim().toLowerCase();
  const candidates = stepChoice
    ? availableSteps.filter(stepFitsChoice)
    : availableSteps;
  const visible = candidates.filter((step) => [
    step.id,
    step.kind,
    ...step.inputs.flatMap((port) => [port.name, port.shape]),
    ...step.outputs.flatMap((port) => [port.name, port.shape])
  ].join(" ").toLowerCase().includes(query));
  catalogTitle.textContent = stepChoice ? "Choose next Step" : "Steps";
  catalogDescription.textContent = stepChoice
    ? stepChoice.kind === "entry"
      ? "Choose the first Step. Data remains explicitly unconnected."
      : stepChoice.kind === "outcome"
        ? `Choose what follows ${invocationLabel(stepChoice.stepId)} · ${humanize(stepChoice.outcome)}.`
        : `Choose a Step that accepts ${endpointLabel(stepChoice.sourceEndpoint, "output")}.`
    : "Browse trusted Steps or choose Add next on the canvas.";
  cancelStepChoice.hidden = !stepChoice;
  stepCount.textContent = `${candidates.length} compatible`;
  stepList.replaceChildren(...visible.map((step) => {
    const button = element("button", presentationClasses("step-item", step));
    button.type = "button";
    button.setAttribute("aria-label", `${stepChoice ? "Choose" : "Add"} ${step.id}`);
    const mark = element("span", "step-mark", kindMarks[kindFor(step)]);
    const details = element("span");
    const signature = `${step.inputs.map((port) => port.shape).join(" + ") || "event"} → `
      + `${step.outputs.map((port) => port.shape).join(" + ") || "outcome"}`;
    details.append(
      element("strong", "", titleFor(step.id)),
      element("code", "", signature)
    );
    button.append(mark, details);
    button.addEventListener("click", () => stepChoice ? chooseStep(step) : addStep(step));
    return button;
  }));
  if (visible.length === 0) {
    stepList.append(element("p", "catalog-empty", "No compatible Step."));
  }
}

function stepFitsChoice(definition) {
  if (stepChoice?.kind === "output") {
    return definition.inputs.some((input) =>
      compatibleShape(stepChoice.sourceShape, input.shape)
    );
  }
  const pool = [];
  for (const [name, shape] of Object.entries(flow.inputs)) {
    pool.push({ endpoint: `input.${name}`, shape });
  }
  if (stepChoice?.kind === "outcome") {
    const invocation = flow.steps.find((step) => step.id === stepChoice.stepId);
    for (const output of definitionFor(invocation)?.outputs || []) {
      pool.push({ endpoint: `${invocation.id}.${output.name}`, shape: output.shape });
    }
  }
  const unused = [...pool];
  return definition.inputs.every((input) => {
    const match = unused.findIndex((source) => compatibleShape(source.shape, input.shape));
    if (match < 0) {
      return false;
    }
    unused.splice(match, 1);
    return true;
  });
}

function beginStepChoice(choice) {
  stepChoice = choice;
  search.value = "";
  renderSteps();
  search.focus();
}

function cancelChoice() {
  stepChoice = undefined;
  search.value = "";
  renderSteps();
}

function nextStepId(definition) {
  const base = definition.id.split(".").at(-1) || "step";
  const used = new Set(flow.steps.map((step) => step.id));
  let id = base;
  let suffix = 2;
  while (used.has(id)) {
    id = `${base}${suffix++}`;
  }
  return id;
}

function newInvocation(definition) {
  return { id: nextStepId(definition), use: definition.id, config: {}, on: {} };
}

function chooseStep(definition) {
  const choice = stepChoice;
  if (!choice) {
    return;
  }
  const invocation = newInvocation(definition);
  flow.steps.push(invocation);
  let entrySet = false;
  let outcome = "";
  let mappingsAdded = 0;
  if (choice.kind === "entry") {
    flow.entry = invocation.id;
    entrySet = true;
  } else if (choice.kind === "outcome") {
    const source = flow.steps.find((step) => step.id === choice.stepId);
    source.on[choice.outcome] = invocation.id;
    outcome = `${choice.stepId}.${choice.outcome}`;
  } else {
    const target = definition.inputs.find((input) =>
      compatibleShape(choice.sourceShape, input.shape)
    );
    flow.connections.push({
      from: choice.sourceEndpoint,
      to: `${invocation.id}.${target.name}`
    });
    mappingsAdded = 1;
  }
  selectedStepId = invocation.id;
  selectedEntity = `step:${invocation.id}`;
  cancelChoice();
  finishAuthoring({
    status: "authoring",
    action: "step-added-next",
    step: invocation.id,
    entrySet,
    outcome,
    mappingsAdded,
    outcomesConnected: choice.kind === "outcome" ? 1 : 0
  });
}

function renderFlow() {
  projectName.textContent = flow.id;
  const transitionCount = flow.steps.reduce(
    (count, invocation) => count + Object.keys(invocation.on).length,
    0
  );
  const noun = flow.steps.length === 1 ? "Step" : "Steps";
  const routeNoun = transitionCount === 1 ? "route" : "routes";
  flowSummary.textContent = `${flow.steps.length} ${noun} / ${flow.connections.length} data / ${transitionCount} ${routeNoun}`;
  const layout = graphLayout();
  flowCanvas.style.width = `${layout.width}px`;
  flowCanvas.style.height = `${layout.height}px`;
  flowCanvas.dataset.layout = layout.key;
  connections.setAttribute("viewBox", `0 0 ${layout.width} ${layout.height}`);
  renderApplication(layout);
  renderTriggers(layout);
  outputBoundary.style.left = `${layout.output.x}px`;
  outputBoundary.style.top = `${layout.output.y}px`;
  renderBoundary(outputBoundary, "Flow output", flow.outputs, true);
  renderConnections(layout);
  flowNodes.replaceChildren(...flow.steps.map((invocation) =>
    renderNode(invocation, layout.positions.get(invocation.id))
  ));
}

function renderApplication(layout) {
  applicationNode.className = `application-node${selectedEntity === "application" ? " selected" : ""}`;
  applicationNode.style.left = `${layout.application.x}px`;
  applicationNode.style.top = `${layout.application.y}px`;
  applicationNode.setAttribute("aria-label", `Application ${flow.id}`);
  const header = element("header");
  const identity = element("div");
  identity.append(
    element("strong", "", flow.id),
    element("code", "", "Application")
  );
  header.append(element("span", "application-mark", "App"), identity);
  const contract = element("div", "application-contract");
  const input = element("div");
  input.append(element("span", "", "Inputs"));
  for (const [name, shape] of Object.entries(flow.inputs)) {
    input.append(element("strong", "", humanize(name)), element("code", "", shape));
  }
  const output = element("div");
  output.append(element("span", "", "Outputs"));
  for (const [name, shape] of Object.entries(flow.outputs)) {
    output.append(element("strong", "", humanize(name)), element("code", "", shape));
  }
  contract.append(input, output);
  const footer = element("footer");
  footer.append(element(
    "span",
    "",
    flow.entry ? `Starts at ${invocationLabel(flow.entry)}` : "Choose the first Step"
  ));
  const addTriggerButton = element("button", "", "Add trigger");
  addTriggerButton.type = "button";
  addTriggerButton.setAttribute("aria-label", "Add trigger");
  addTriggerButton.addEventListener("click", (event) => {
    event.stopPropagation();
    selectApplication();
    triggerMenu.hidden = false;
  });
  footer.append(addTriggerButton);
  applicationNode.replaceChildren(header, contract, footer);
  applicationNode.onclick = selectApplication;
  applicationNode.onkeydown = (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      selectApplication();
    }
  };
}

function renderTriggers(layout) {
  triggerNodes.replaceChildren(...flow.triggers.map((trigger, index) => {
    const position = layout.triggers[index];
    const selected = selectedEntity === `trigger:${trigger.id}` ? " selected" : "";
    const node = element("article", `trigger-node${selected}`);
    node.tabIndex = 0;
    node.style.left = `${position.x}px`;
    node.style.top = `${position.y}px`;
    node.setAttribute("aria-label", `Trigger ${trigger.id}`);
    const heading = element("header");
    heading.append(
      element("span", "trigger-mark", triggerMark(trigger.type)),
      element("div", "", "")
    );
    heading.lastElementChild.append(
      element("strong", "", humanize(trigger.type)),
      element("code", "", trigger.id)
    );
    const footer = element("footer");
    if (!flow.entry) {
      const add = element("button", "add-next", "Add next Step");
      add.type = "button";
      add.setAttribute("aria-label", `Add next Step after ${trigger.id}`);
      add.addEventListener("click", (event) => {
        event.stopPropagation();
        beginStepChoice({ kind: "entry", triggerId: trigger.id });
      });
      footer.append(add);
    } else {
      footer.append(element("span", "", `→ ${invocationLabel(flow.entry)}`));
    }
    const remove = element("button", "node-remove", "Remove");
    remove.type = "button";
    remove.setAttribute("aria-label", `Remove Trigger ${trigger.id}`);
    remove.addEventListener("click", (event) => {
      event.stopPropagation();
      removeTrigger(trigger);
    });
    footer.append(remove);
    node.append(heading, footer);
    node.addEventListener("click", () => selectTrigger(trigger.id));
    node.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        selectTrigger(trigger.id);
      }
    });
    return node;
  }));
}

function triggerMark(type) {
  return {
    cli: ">_",
    http: "HTTP",
    socket: "TCP",
    scheduled: "T",
    startup: "On"
  }[type] || "In";
}

function renderNode(invocation, position) {
  const definition = definitionFor(invocation);
  const selected = selectedEntity === `step:${invocation.id}` ? " selected" : "";
  const node = element("article", presentationClasses(`step-node${selected}`, definition));
  node.tabIndex = 0;
  node.style.left = `${position.x}px`;
  node.style.top = `${position.y}px`;
  node.setAttribute("aria-label", `Step ${invocation.id}`);
  node.addEventListener("click", () => selectStep(invocation.id));
  node.addEventListener("keydown", (event) => {
    if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      selectStep(invocation.id);
    }
  });

  const header = element("header");
  const identity = element("div");
  identity.append(
    element("strong", "", titleFor(invocation.use)),
    element("code", "", invocation.id)
  );
  header.append(
    element("div", "step-mark", kindMarks[kindFor(definition)]),
    identity,
    element("span", "node-state", nodeState(invocation, definition))
  );

  const contract = element("div", "node-contract");
  contract.append(
    portColumn(invocation, definition?.inputs || [], false),
    portColumn(invocation, definition?.outputs || [], true)
  );

  const footer = element("footer");
  footer.append(outcomeRoutes(invocation, definition));
  const remove = element("button", "node-remove", "Remove");
  remove.type = "button";
  remove.setAttribute("aria-label", `Remove Step ${invocation.id}`);
  remove.addEventListener("click", (event) => {
    event.stopPropagation();
    removeStep(invocation.id);
  });
  footer.append(remove);
  node.append(header, contract, footer);
  return node;
}

function outcomeRoutes(invocation, definition) {
  const routes = element("div", "outcome-routes");
  const outcomes = definition?.outcomes || [];
  if (outcomes.length === 0) {
    routes.append(element("span", "", "Contract unavailable"));
    return routes;
  }

  for (const outcome of outcomes) {
    const label = element("label", "outcome-route");
    label.append(element("span", "", outcome));
    const select = element("select");
    select.setAttribute("aria-label", `Route ${invocation.id}.${outcome}`);
    select.append(routeOption("", "Unconnected"), routeOption("end", "End flow"));
    for (const target of routeTargets(invocation, outcome)) {
      select.append(routeOption(target.id, invocationLabel(target.id)));
    }
    select.value = invocation.on[outcome] || "";
    select.addEventListener("click", (event) => event.stopPropagation());
    select.addEventListener("change", (event) => {
      event.stopPropagation();
      routeOutcome(invocation, outcome, event.currentTarget.value);
    });
    label.append(select);
    if (!invocation.on[outcome]) {
      const add = element("button", "add-next", "Add next");
      add.type = "button";
      add.setAttribute("aria-label", `Add next Step after ${invocation.id}.${outcome}`);
      add.addEventListener("click", (event) => {
        event.stopPropagation();
        beginStepChoice({ kind: "outcome", stepId: invocation.id, outcome });
      });
      label.append(add);
    }
    routes.append(label);
  }
  return routes;
}

function routeTargets(invocation, outcome) {
  const current = invocation.on[outcome];
  return flow.steps.filter((target) =>
    target.id !== invocation.id
      && (target.id === current || controlTargetAvailable(invocation.id, target.id))
  );
}

function controlTargetAvailable(sourceId, targetId) {
  const alreadyReached = flow.steps.some((step) =>
    step.id !== sourceId && Object.values(step.on).includes(targetId)
  );
  return !alreadyReached && !controlReaches(targetId, sourceId);
}

function controlReaches(startId, targetId) {
  const visited = new Set();
  const pending = [startId];
  while (pending.length > 0) {
    const id = pending.pop();
    if (id === targetId) {
      return true;
    }
    if (visited.has(id)) {
      continue;
    }
    visited.add(id);
    const step = flow.steps.find((candidate) => candidate.id === id);
    for (const target of Object.values(step?.on || {})) {
      if (target !== "end") {
        pending.push(target);
      }
    }
  }
  return false;
}

function routeOption(value, text) {
  const option = element("option", "", text);
  option.value = value;
  return option;
}

function routeOutcome(invocation, outcome, target) {
  const previousTarget = invocation.on[outcome] || "";
  if (target) {
    invocation.on[outcome] = target;
  } else {
    delete invocation.on[outcome];
  }
  selectedStepId = invocation.id;
  finishAuthoring({
    status: "authoring",
    action: target
      ? previousTarget ? "outcome-rerouted" : "outcome-connected"
      : "outcome-disconnected",
    outcome: `${invocation.id}.${outcome}`,
    previousTarget: previousTarget || "unconnected",
    target: target || "unconnected"
  });
}

function portColumn(invocation, ports, output) {
  const column = element("div", `contract-column${output ? " output-column" : ""}`);
  if (ports.length === 0) {
    column.append(element("div", `contract-row${output ? " output-row" : ""}`, "None"));
    return column;
  }
  for (const port of ports) {
    const row = element("div", `contract-row${output ? " output-row" : ""}`);
    const connector = element("i", `port ${output ? "port-right" : "port-left"}`);
    const endpoint = `${invocation.id}.${port.name}`;
    const action = element("button", "port-action");
    action.type = "button";
    if (output) {
      const occupied = flow.connections.some((connection) => connection.from === endpoint);
      action.textContent = occupied ? "Connected" : "Add consumer";
      action.disabled = occupied;
      action.setAttribute(
        "aria-label",
        `Add data consumer for ${plainEndpointLabel(endpoint, "output")}`
      );
      action.addEventListener("click", (event) => {
        event.stopPropagation();
        beginStepChoice({
          kind: "output",
          sourceEndpoint: endpoint,
          sourceShape: port.shape,
          stepId: invocation.id
        });
      });
    } else {
      const occupied = Boolean(mappingFor(endpoint));
      action.textContent = occupied ? "Connected" : "Choose source";
      action.setAttribute("aria-label", `Choose source for ${plainEndpointLabel(endpoint, "input")}`);
      action.addEventListener("click", (event) => {
        event.stopPropagation();
        selectStep(invocation.id);
        requestAnimationFrame(() => {
          dataMappings.querySelector(
            `select[aria-label="${CSS.escape(mappingLabel("Map", endpoint, 1))}"]`
          )?.focus();
        });
      });
    }
    if (output) {
      row.append(element("span", "", humanize(port.name)), element("code", "", port.shape), action, connector);
    } else {
      row.append(connector, element("span", "", humanize(port.name)), element("code", "", port.shape), action);
    }
    column.append(row);
  }
  return column;
}

function nodeState(invocation, definition) {
  if (!definition) {
    return "Unknown";
  }
  const inputsMapped = definition.inputs.every((port) => mappingFor(`${invocation.id}.${port.name}`));
  const outcomesMapped = definition.outcomes.every((outcome) => invocation.on[outcome]);
  return inputsMapped && outcomesMapped ? "Ready" : "Unconnected";
}

function selectStep(stepId) {
  selectedStepId = stepId;
  selectedEntity = `step:${stepId}`;
  renderFlow();
  renderInspector();
}

function selectApplication() {
  selectedEntity = "application";
  renderFlow();
  renderInspector();
}

function selectTrigger(triggerId) {
  selectedEntity = `trigger:${triggerId}`;
  renderFlow();
  renderInspector();
}

function renderInspector() {
  renderFlowContract();
  renderHttpIngress();
  renderSocketIngress();
  renderSchedules();
  applicationName.value = flow.id;
  const triggerId = selectedEntity.startsWith("trigger:")
    ? selectedEntity.slice("trigger:".length)
    : "";
  const selectedTrigger = flow.triggers.find((trigger) => trigger.id === triggerId);
  const stepSelected = selectedEntity.startsWith("step:");
  const applicationSelected = selectedEntity === "application";
  applicationSettings.hidden = !applicationSelected;
  flowContractSection.hidden = !applicationSelected;
  triggerSection.hidden = stepSelected;
  stepContractSection.hidden = !stepSelected;
  mappingSection.hidden = !stepSelected;
  configurationSection.hidden = !stepSelected;
  httpSection.hidden = selectedTrigger?.type !== "http";
  socketSection.hidden = selectedTrigger?.type !== "socket";
  scheduleSection.hidden = selectedTrigger?.type !== "scheduled";
  renderTriggerDetail(selectedTrigger);

  const invocation = flow.steps.find((step) => step.id === selectedStepId);
  const definition = invocation ? definitionFor(invocation) : undefined;
  if (selectedTrigger) {
    inspectorStepId.textContent = `${humanize(selectedTrigger.type)} · ${selectedTrigger.id}`;
    contractTable.replaceChildren();
    stepConfig.replaceChildren();
  } else if (!stepSelected) {
    inspectorStepId.textContent = "Application";
    contractTable.replaceChildren();
    stepConfig.replaceChildren();
  } else if (!invocation || !definition) {
    inspectorStepId.textContent = "No Step selected";
    contractTable.replaceChildren();
    stepConfig.replaceChildren(element("p", "empty-value", "No Step selected"));
  } else {
    inspectorStepId.textContent = invocationLabel(invocation.id);
    const entries = [
      ...definition.inputs.map((port) => ["Input", `${port.name} : ${port.shape}`]),
      ...definition.outputs.map((port) => ["Output", `${port.name} : ${port.shape}`]),
      ...definition.outcomes.map((outcome) => ["Outcome", outcome])
    ];
    contractTable.replaceChildren(...entries.map(([kind, value]) => {
      const row = element("div");
      const description = element("dd");
      description.append(element("code", "", value));
      row.append(element("dt", "", kind), description);
      return row;
    }));
    renderStepConfig(invocation, definition);
  }

  if (stepSelected) {
    const targets = [
      ...flow.steps.flatMap((step) => (definitionFor(step)?.inputs || []).map((port) => ({
        endpoint: `${step.id}.${port.name}`,
        shape: port.shape,
        role: "input"
      }))),
      ...Object.entries(flow.outputs).map(([name, shape]) => ({
        endpoint: `output.${name}`,
        shape,
        role: "target"
      }))
    ];
    dataMappings.replaceChildren(...targets.map(dataMapping));
  } else {
    dataMappings.replaceChildren();
  }
}

function renderTriggerDetail(trigger) {
  if (!trigger) {
    triggerHelp.replaceChildren(element(
      "p",
      "empty-value",
      `${flow.triggers.length} attached · every trigger starts the same flow`
    ));
    return;
  }
  if (trigger.type === "cli") {
    const stdin = element("label", "trigger-toggle");
    const checkbox = element("input");
    checkbox.type = "checkbox";
    checkbox.checked = trigger.config.stdin === true;
    checkbox.setAttribute("aria-label", `CLI stdin ${trigger.id}`);
    checkbox.addEventListener("change", (event) => {
      trigger.config.stdin = event.currentTarget.checked;
      finishAuthoring({
        status: "authoring",
        action: "cli-stdin-changed",
        trigger: trigger.id,
        stdin: trigger.config.stdin
      });
    });
    stdin.append(checkbox, element("span", "", "Read one JSON event from stdin"));
    const argumentsLabel = element("label");
    argumentsLabel.append(element("span", "", "Arguments input"));
    const argumentsInput = element("select");
    argumentsInput.setAttribute("aria-label", `CLI arguments input ${trigger.id}`);
    argumentsInput.append(routeOption("", "None"));
    for (const [name, shape] of Object.entries(flow.inputs)) {
      if (shape === "array" || shape === "any") {
        argumentsInput.append(routeOption(name, humanize(name)));
      }
    }
    argumentsInput.value = trigger.config.arguments || "";
    argumentsInput.addEventListener("change", (event) => {
      if (event.currentTarget.value) {
        trigger.config.arguments = event.currentTarget.value;
      } else {
        delete trigger.config.arguments;
      }
      finishAuthoring({
        status: "authoring",
        action: "cli-arguments-changed",
        trigger: trigger.id,
        input: event.currentTarget.value || "none"
      });
    });
    argumentsLabel.append(argumentsInput);
    triggerHelp.replaceChildren(stdin, argumentsLabel);
    return;
  }
  triggerHelp.replaceChildren(element(
    "p",
    "empty-value",
    trigger.type === "startup"
      ? "Runs once when the application starts. Startup flows must not require input."
      : `${humanize(trigger.type)} settings are shown below.`
  ));
}

function generatedFlowName() {
  for (let attempt = 0; attempt < 1728; attempt++) {
    const index = generatedNameIndex++ % 1728;
    const name = [
      nameParts[0][index % 12],
      nameParts[1][Math.floor(index / 12) % 12],
      nameParts[2][Math.floor(index / 144) % 12]
    ].join("-");
    if (!generatedNames.has(name)) {
      generatedNames.add(name);
      return name;
    }
  }
  let name;
  do {
    name = `railix-flow-${(generatedNameIndex++).toString(36)}`;
  } while (generatedNames.has(name));
  generatedNames.add(name);
  return name;
}

function newFlow() {
  flow = {
    id: generatedFlowName(),
    triggers: [{ id: "command", type: "cli", config: { stdin: true } }],
    entry: "",
    inputs: { text: "string" },
    outputs: { text: "string" },
    steps: [],
    connections: []
  };
  selectedStepId = "";
  selectedEntity = "application";
  stepChoice = undefined;
  eventFormat = "json";
  eventExamples.json = "{\"text\":\"Hello RAILIX\"}";
  eventExamples.yaml = "text: \"Hello RAILIX\"";
  eventExamples.xml = "<object><field name=\"text\"><string>Hello RAILIX</string></field></object>";
  inputField.value = eventExamples.json;
  renderSteps();
  finishAuthoring({
    status: "authoring",
    action: "flow-created",
    flow: flow.id,
    trigger: "command"
  });
}

function renameApplication(requestedName) {
  const name = requestedName.trim();
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(name)) {
    applicationName.value = flow.id;
    renderConsole({
      status: "authoring-rejected",
      diagnostics: [{
        code: "FLOW_ID_URL_SAFE_REQUIRED",
        path: "id",
        message: "Application name must use lowercase letters, numbers, and single hyphens."
      }]
    });
    return;
  }
  if (name === flow.id) {
    return;
  }
  if (generatedNames.has(name)) {
    applicationName.value = flow.id;
    renderConsole({
      status: "authoring-rejected",
      diagnostics: [{
        code: "FLOW_ID_SESSION_DUPLICATE",
        path: "id",
        message: `Application name was already used in this Creator session: ${name}`
      }]
    });
    return;
  }
  const previous = flow.id;
  flow.id = name;
  generatedNames.add(name);
  finishAuthoring({
    status: "authoring",
    action: "flow-renamed",
    previous,
    flow: name
  });
}

function addTrigger(type) {
  if (type === "socket" && socketTrigger()) {
    selectTrigger(socketTrigger().id);
    triggerMenu.hidden = true;
    return;
  }
  const base = {
    cli: "command",
    http: "http-route",
    socket: "socket-events",
    scheduled: "schedule",
    startup: "startup"
  }[type];
  const config = {
    cli: { stdin: true },
    http: { port: 8080, path: "/event" },
    socket: { port: 17000, timeoutMillis: 30000, maxConnections: 32 },
    scheduled: { intervalMillis: 60000, initialDelayMillis: 0, maxConcurrentRuns: 1 },
    startup: {}
  }[type];
  const trigger = { id: nextTriggerId(base), type, config };
  flow.triggers.push(trigger);
  selectedEntity = `trigger:${trigger.id}`;
  triggerMenu.hidden = true;
  finishAuthoring({
    status: "authoring",
    action: "trigger-added",
    trigger: trigger.id,
    type
  });
}

function removeTrigger(trigger) {
  flow.triggers = flow.triggers.filter((candidate) => candidate !== trigger);
  selectedEntity = "application";
  finishAuthoring({
    status: "authoring",
    action: "trigger-removed",
    trigger: trigger.id,
    type: trigger.type
  });
}

function httpTriggers() {
  return flow.triggers.filter((trigger) => trigger.type === "http");
}

function hasHttpSelector(selector, value) {
  return httpTriggers().some((trigger) => trigger.config[selector] === value);
}

function renderHttpIngress() {
  const triggers = httpTriggers();
  httpPort.value = triggers.length === 0 ? "8080" : String(triggers[0].config.port);
  httpPort.disabled = triggers.length === 0;
  renderHttpRoutes();
  flowEventToggle.checked = hasHttpSelector("flow", true);
  flowEventEndpoint.textContent = `/v1/flows/${flow.id}/events`;
  const selected = flow.steps.some((step) => step.id === selectedStepId);
  stepEventToggle.disabled = !selected;
  stepEventToggle.checked = selected && hasHttpSelector("step", selectedStepId);
  stepEventToggle.setAttribute(
    "aria-label",
    selected ? `Enable Step event ${selectedStepId}` : "Enable Step event"
  );
  stepEventEndpoint.textContent = selected
    ? `/v1/flows/${flow.id}/steps/${selectedStepId}/events`
    : "Select a Step";
}

function renderHttpRoutes() {
  httpRoutes.replaceChildren(...httpTriggers()
    .filter((trigger) => Object.hasOwn(trigger.config, "path"))
    .map((trigger) => {
      const path = trigger.config.path;
      const row = element("div", "http-route");
      const input = element("input");
      input.type = "text";
      input.value = path;
      input.setAttribute("aria-label", `HTTP path ${path}`);
      input.addEventListener("change", (event) => setHttpPath(trigger, event.currentTarget.value));
      const remove = element("button", "", "Remove");
      remove.type = "button";
      remove.setAttribute("aria-label", `Remove HTTP route ${path}`);
      remove.addEventListener("click", () => removeHttpRoute(trigger));
      row.append(input, remove);
      return row;
    }));
}

function nextTriggerId(base) {
  const ids = new Set(flow.triggers.map((trigger) => trigger.id));
  let id = base;
  let suffix = 2;
  while (ids.has(id)) {
    id = `${base}-${suffix++}`;
  }
  return id;
}

function addHttpRoute() {
  const path = newHttpPath.value;
  flow.triggers.push({
    id: nextTriggerId("http-route"),
    type: "http",
    config: { port: currentHttpPort(), path }
  });
  newHttpPath.value = "";
  finishAuthoring({
    status: "authoring",
    action: "http-route-added",
    path
  });
}

function setHttpPath(trigger, path) {
  const previous = trigger.config.path;
  trigger.config.path = path;
  finishAuthoring({
    status: "authoring",
    action: "http-route-changed",
    previous,
    path
  });
}

function removeHttpRoute(trigger) {
  const path = trigger.config.path;
  flow.triggers = flow.triggers.filter((candidate) => candidate !== trigger);
  finishAuthoring({
    status: "authoring",
    action: "http-route-removed",
    path
  });
}

function currentHttpPort() {
  const trigger = httpTriggers()[0];
  return trigger ? trigger.config.port : Number(httpPort.value);
}

function setHttpIngress(selector, value, enabled) {
  if (enabled) {
    if (!hasHttpSelector(selector, value)) {
      const name = selector === "flow" ? "flow" : value;
      flow.triggers.push({
        id: nextTriggerId(`http-${name}-events`),
        type: "http",
        config: { port: currentHttpPort(), [selector]: value }
      });
    }
  } else {
    flow.triggers = flow.triggers.filter(
      (trigger) => trigger.type !== "http" || trigger.config[selector] !== value
    );
  }
  finishAuthoring({
    status: "authoring",
    action: `${selector}-event-${enabled ? "enabled" : "disabled"}`,
    target: value
  });
}

function setHttpPort(source) {
  const port = source === "" ? "" : Number(source);
  for (const trigger of httpTriggers()) {
    trigger.config.port = port;
  }
  finishAuthoring({
    status: "authoring",
    action: "http-port-changed",
    port
  });
}

function socketTrigger() {
  return flow.triggers.find((trigger) => trigger.type === "socket");
}

function scheduledTriggers() {
  return flow.triggers.filter((trigger) => trigger.type === "scheduled");
}

function renderSocketIngress() {
  const trigger = socketTrigger();
  socketToggle.checked = Boolean(trigger);
  socketPort.disabled = !trigger;
  socketTimeout.disabled = !trigger;
  socketConnections.disabled = !trigger;
  socketPort.value = String(trigger?.config.port ?? 17000);
  socketTimeout.value = String(trigger?.config.timeoutMillis ?? 30000);
  socketConnections.value = String(trigger?.config.maxConnections ?? 32);
  socketEndpoint.textContent = trigger
    ? `127.0.0.1:${trigger.config.port} · 4-byte length + JSON`
    : "Not enabled";
}

function setSocketIngress(enabled) {
  if (enabled && !socketTrigger()) {
    flow.triggers.push({
      id: nextTriggerId("socket-events"),
      type: "socket",
      config: { port: 17000, timeoutMillis: 30000, maxConnections: 32 }
    });
  } else if (!enabled) {
    flow.triggers = flow.triggers.filter((trigger) => trigger.type !== "socket");
  }
  finishAuthoring({
    status: "authoring",
    action: `socket-ingress-${enabled ? "enabled" : "disabled"}`
  });
}

function setSocketConfig(field, source) {
  const trigger = socketTrigger();
  if (!trigger) {
    return;
  }
  trigger.config[field] = source === "" ? "" : Number(source);
  finishAuthoring({
    status: "authoring",
    action: "socket-config-changed",
    field,
    value: trigger.config[field]
  });
}

function renderSchedules() {
  const triggers = scheduledTriggers();
  noSchedules.hidden = triggers.length > 0;
  schedules.replaceChildren(...triggers.map(scheduleEditor));
}

function scheduleEditor(trigger) {
  const row = element("article", "schedule-row");
  const heading = element("div", "schedule-row-heading");
  const remove = element("button", "", "Remove");
  remove.type = "button";
  remove.setAttribute("aria-label", `Remove schedule ${trigger.id}`);
  remove.addEventListener("click", () => removeSchedule(trigger));
  heading.append(element("strong", "", trigger.id), remove);
  row.append(heading);
  for (const [field, title, label] of [
    ["intervalMillis", "Interval · ms", "interval milliseconds"],
    ["initialDelayMillis", "Initial delay · ms", "initial delay milliseconds"],
    ["maxConcurrentRuns", "Concurrent runs", "maximum concurrent runs"]
  ]) {
    const wrapper = element("label", "schedule-field");
    const input = element("input");
    input.type = "number";
    input.inputMode = "numeric";
    input.value = String(trigger.config[field]);
    input.setAttribute("aria-label", `Schedule ${trigger.id} ${label}`);
    input.addEventListener("change", (event) =>
      setScheduleConfig(trigger, field, event.currentTarget.value)
    );
    wrapper.append(element("span", "", title), input);
    row.append(wrapper);
  }
  return row;
}

function addSchedule() {
  const id = nextTriggerId("schedule");
  flow.triggers.push({
    id,
    type: "scheduled",
    config: { intervalMillis: 60000, initialDelayMillis: 0, maxConcurrentRuns: 1 }
  });
  finishAuthoring({ status: "authoring", action: "schedule-added", trigger: id });
}

function removeSchedule(trigger) {
  flow.triggers = flow.triggers.filter((candidate) => candidate !== trigger);
  finishAuthoring({
    status: "authoring",
    action: "schedule-removed",
    trigger: trigger.id
  });
}

function setScheduleConfig(trigger, field, source) {
  trigger.config[field] = source === "" ? "" : Number(source);
  finishAuthoring({
    status: "authoring",
    action: "schedule-config-changed",
    trigger: trigger.id,
    field,
    value: trigger.config[field]
  });
}

function renderStepConfig(invocation, definition) {
  if (definition.config.length === 0) {
    stepConfig.replaceChildren(element("p", "empty-value", "None"));
    return;
  }
  stepConfig.replaceChildren(...definition.config.map((config) => {
    const overridden = Object.hasOwn(invocation.config, config.name);
    const effective = overridden ? invocation.config[config.name] : config.default;
    const row = element("div", "config-editor");
    const label = element("label");
    label.append(
      element("span", "", config.name),
      element("code", "", config.format ? `${config.shape} · ${config.format}` : config.shape)
    );
    const input = element("input");
    input.type = "text";
    input.value = configText(effective, config.shape);
    input.setAttribute("aria-label", `Configure ${invocation.id}.${config.name}`);
    const source = overridden
      ? "Override"
      : config.required
        ? "Required"
        : `Default: ${JSON.stringify(config.default)}`;
    const reset = element("button", "config-reset", "Reset");
    reset.type = "button";
    reset.disabled = !overridden;
    reset.setAttribute("aria-label", `Reset ${invocation.id}.${config.name}`);
    reset.addEventListener("click", () => resetStepConfig(invocation, config));
    const sourceLabel = element("span", "config-source", source);
    input.addEventListener(config.shape === "string" ? "input" : "change", (event) => {
      configureStep(invocation, config, event.currentTarget.value, config.shape === "string");
      if (config.shape === "string") {
        sourceLabel.textContent = "Override";
        reset.disabled = false;
      }
    });
    row.append(label, input, sourceLabel, reset);
    return row;
  }));
}

function configText(value, shape) {
  if (value === undefined) {
    return "";
  }
  return shape === "string" ? value : JSON.stringify(value);
}

function configureStep(invocation, config, source, keepEditor) {
  let value;
  try {
    value = config.shape === "string" ? source : JSON.parse(source);
  } catch {
    renderInspector();
    renderConsole({
      status: "authoring-rejected",
      diagnostics: [{
        code: "CONFIG_VALUE_JSON_INVALID",
        path: `steps.${invocation.id}.config.${config.name}`,
        message: "Configuration value must be valid JSON."
      }]
    });
    return;
  }
  invocation.config[config.name] = value;
  const event = {
    status: "authoring",
    action: "config-overridden",
    step: invocation.id,
    config: config.name,
    value
  };
  if (keepEditor) {
    finishConfigAuthoring(event);
  } else {
    finishAuthoring(event);
  }
}

function resetStepConfig(invocation, config) {
  delete invocation.config[config.name];
  finishAuthoring({
    status: "authoring",
    action: "config-reset",
    step: invocation.id,
    config: config.name,
    source: config.required ? "required" : "default"
  });
}

function renderFlowContract() {
  flowInputPorts.replaceChildren(...Object.entries(flow.inputs).map(
    ([name, shape]) => flowPortEditor("input", name, shape)
  ));
  flowOutputPorts.replaceChildren(...Object.entries(flow.outputs).map(
    ([name, shape]) => flowPortEditor("output", name, shape)
  ));
}

function flowPortEditor(direction, name, shape) {
  const row = element("div", "flow-port-editor");
  const rename = element("input");
  rename.type = "text";
  rename.value = name;
  rename.setAttribute("aria-label", `Rename flow ${direction} ${name}`);
  rename.addEventListener("change", (event) => renameFlowPort(direction, name, event.currentTarget.value));
  const shapeSelect = element("select");
  shapeSelect.setAttribute("aria-label", `Shape flow ${direction} ${name}`);
  shapeSelect.append(...availableShapes.map((value) => routeOption(value, value)));
  shapeSelect.value = shape;
  shapeSelect.addEventListener("change", (event) => changeFlowPortShape(
    direction,
    name,
    event.currentTarget.value
  ));
  const remove = element("button", "flow-port-remove", "Remove");
  remove.type = "button";
  remove.setAttribute("aria-label", `Remove flow ${direction} ${name}`);
  remove.addEventListener("click", () => removeFlowPort(direction, name));
  row.append(rename, shapeSelect, remove);
  return row;
}

function flowPorts(direction) {
  return direction === "input" ? flow.inputs : flow.outputs;
}

function setFlowPorts(direction, ports) {
  if (direction === "input") {
    flow.inputs = ports;
  } else {
    flow.outputs = ports;
  }
}

function addFlowPort(direction) {
  const ports = flowPorts(direction);
  const base = direction;
  let name = base;
  let suffix = 2;
  while (Object.hasOwn(ports, name)) {
    name = `${base}${suffix++}`;
  }
  ports[name] = availableShapes.includes("string") ? "string" : availableShapes[0];
  finishAuthoring({
    status: "authoring",
    action: `flow-${direction}-added`,
    port: name,
    shape: ports[name],
    mappingsAdded: 0,
    sampleAdded: false
  });
}

function renameFlowPort(direction, name, requestedName) {
  const nextName = requestedName.trim();
  const ports = flowPorts(direction);
  if (!nextName || (nextName !== name && Object.hasOwn(ports, nextName))) {
    renderFlowContract();
    renderConsole({
      status: "authoring-rejected",
      diagnostics: [{
        code: nextName ? "FLOW_PORT_DUPLICATE" : "FLOW_PORT_NAME_REQUIRED",
        path: `${direction}s.${nextName || name}`,
        message: nextName ? `Flow ${direction} already exists: ${nextName}` : `Flow ${direction} name is required.`
      }]
    });
    return;
  }
  if (nextName === name) {
    return;
  }
  const renamed = {};
  for (const [port, shape] of Object.entries(ports)) {
    renamed[port === name ? nextName : port] = shape;
  }
  setFlowPorts(direction, renamed);
  const from = `${direction}.${name}`;
  const to = `${direction}.${nextName}`;
  let connectionsUpdated = 0;
  for (const connection of flow.connections) {
    const field = direction === "input" ? "from" : "to";
    if (connection[field] === from) {
      connection[field] = to;
      connectionsUpdated++;
    }
  }
  finishAuthoring({
    status: "authoring",
    action: `flow-${direction}-renamed`,
    previousPort: name,
    port: nextName,
    connectionsUpdated
  });
}

function changeFlowPortShape(direction, name, shape) {
  flowPorts(direction)[name] = shape;
  finishAuthoring({
    status: "authoring",
    action: `flow-${direction}-shape-changed`,
    port: name,
    shape
  });
}

function removeFlowPort(direction, name) {
  const endpoint = `${direction}.${name}`;
  const previousConnectionCount = flow.connections.length;
  flow.connections = flow.connections.filter((connection) =>
    direction === "input" ? connection.from !== endpoint : connection.to !== endpoint
  );
  delete flowPorts(direction)[name];
  finishAuthoring({
    status: "authoring",
    action: `flow-${direction}-removed`,
    port: name,
    connectionsRemoved: previousConnectionCount - flow.connections.length
  });
}

function dataMapping(target) {
  const group = element("section", "mapping-target");
  const heading = element("div", "mapping-target-heading");
  heading.append(
    element("strong", "", endpointLabel(target.endpoint, target.role)),
    element("code", "", target.shape)
  );
  group.append(heading);
  const mappings = flow.connections.filter((connection) => connection.to === target.endpoint);
  if (mappings.length === 0) {
    group.append(mappingEditor(target, undefined, 1));
  } else {
    mappings.forEach((connection, index) => {
      group.append(mappingEditor(target, connection, index + 1));
    });
    if (mappings.length > 1) {
      group.prepend(element(
        "p",
        "advanced-topology",
        "Advanced topology · preserved exactly"
      ));
    }
  }
  return group;
}

function mappingEditor(target, connection, ordinal) {
  const row = element("div", "mapping-editor");
  if (connection) {
    row.append(element(
      "p",
      "mapping-connection",
      `${endpointLabel(connection.from)} → ${endpointLabel(connection.to, target.role)}`
    ));
  }
  const select = element("select");
  select.setAttribute("aria-label", mappingLabel("Map", target.endpoint, ordinal));
  select.append(
    routeOption("", "Unmapped"),
    ...dataSources(connection, target.endpoint)
      .filter((source) =>
        source.endpoint === connection?.from || compatibleShape(source.shape, target.shape)
      )
      .map((source) =>
        routeOption(source.endpoint, `${endpointLabel(source.endpoint)} · ${source.shape}`)
      )
  );
  select.value = connection?.from || "";
  select.addEventListener("change", (event) =>
    mapData(target.endpoint, connection, event.currentTarget.value)
  );
  row.append(select);
  if (!connection) {
    return row;
  }

  const connectionIndex = flow.connections.indexOf(connection);
  row.dataset.connectionIndex = connectionIndex;
  const advanced = element("details", "advanced-mapping");
  const summary = element("summary", "", "Map fields");
  summary.setAttribute("aria-label", mappingLabel("Map fields", target.endpoint, ordinal));
  const fields = element("div", "mapping-fields");
  fields.append(
    mappingSourceField(connection, target.endpoint, ordinal),
    mappingPathField(connection, target.endpoint, ordinal, "sourcePath", "Read nested field"),
    mappingDefaultField(connection, target.endpoint, ordinal),
    mappingConversionField(connection, target.endpoint, ordinal),
    mappingPathField(connection, target.endpoint, ordinal, "targetPath", "Write nested field")
  );
  advanced.append(summary, fields);
  const remove = element("button", "mapping-remove", "Disconnect");
  remove.type = "button";
  remove.setAttribute(
    "aria-label",
    `Disconnect ${plainEndpointLabel(connection.from)} from ${plainEndpointLabel(connection.to, target.role)}`
  );
  remove.addEventListener("click", () => removeMapping(connection));
  const preview = element("code", "mapping-preview", humanMappingExpression(connection));
  preview.setAttribute("aria-label", mappingLabel("Preview", target.endpoint, ordinal));
  row.append(preview, advanced, remove, element("ul", "mapping-diagnostic"));
  return row;
}

function mappingSourceField(connection, target, ordinal) {
  const wrapper = element("label", "mapping-field mapping-source");
  wrapper.append(element("span", "", "Source · advanced"));
  const select = element("select");
  select.setAttribute("aria-label", mappingLabel("Choose advanced source", target, ordinal));
  select.append(...dataSources(connection, target).map((source) =>
    routeOption(source.endpoint, `${endpointLabel(source.endpoint)} · ${source.shape}`)
  ));
  select.value = connection.from;
  select.addEventListener("change", (event) =>
    mapData(target, connection, event.currentTarget.value)
  );
  wrapper.append(select);
  return wrapper;
}

function plainEndpointLabel(endpoint, role = "") {
  return endpointLabel(endpoint, role).replaceAll(" · ", " ");
}

function mappingPathField(connection, target, ordinal, field, label) {
  const wrapper = element("div", "mapping-field mapping-path");
  wrapper.append(element("span", "", label));
  const path = connection[field] || [];
  const breadcrumb = element(
    "div",
    "path-breadcrumb",
    path.length === 0 ? "Whole value" : pathBreadcrumb(path)
  );
  breadcrumb.setAttribute(
    "aria-label",
    mappingLabel(field === "sourcePath" ? "Source path" : "Target path", target, ordinal)
  );
  const segments = element("div", "path-segments");
  path.forEach((part, index) => {
    const segment = element("span", "path-segment");
    segment.append(element("code", "", typeof part === "number" ? `[${part}]` : part));
    const remove = element("button", "", "Remove");
    remove.type = "button";
    remove.setAttribute(
      "aria-label",
      mappingLabel(
        `Remove ${field === "sourcePath" ? "Source" : "Target"} path segment ${index + 1}`,
        target,
        ordinal
      )
    );
    remove.addEventListener("click", () => removePathSegment(connection, field, index));
    segment.append(remove);
    segments.append(segment);
  });
  const controls = element("div", "path-controls");
  const kind = element("select");
  kind.setAttribute(
    "aria-label",
    mappingLabel(
      `${field === "sourcePath" ? "Source" : "Target"} path segment kind`,
      target,
      ordinal
    )
  );
  kind.append(routeOption("field", "Field"), routeOption("index", "Array index"));
  const input = element("input");
  input.type = "text";
  input.placeholder = "person";
  input.setAttribute(
    "aria-label",
    mappingLabel(
      `${field === "sourcePath" ? "Source" : "Target"} path segment`,
      target,
      ordinal
    )
  );
  const add = element("button", "", "Add");
  add.type = "button";
  add.setAttribute(
    "aria-label",
    mappingLabel(
      field === "sourcePath" ? "Add Source path segment" : "Add Target path segment",
      target,
      ordinal
    )
  );
  add.addEventListener("click", () =>
    addPathSegment(connection, field, label, kind.value, input)
  );
  controls.append(kind, input, add);
  wrapper.append(breadcrumb, segments, controls);
  return wrapper;
}

function addPathSegment(connection, field, label, kind, input) {
  const source = input.value.trim();
  const value = kind === "index" ? Number(source) : source;
  const valid = kind === "index"
    ? /^\d+$/.test(source) && Number.isSafeInteger(value)
    : source.length > 0;
  if (!valid) {
    input.setAttribute("aria-invalid", "true");
    renderConsole({
      status: "authoring-rejected",
      diagnostics: [{
        code: "MAPPING_PATH_SEGMENT_INVALID",
        path: `connections[${flow.connections.indexOf(connection)}].${field}`,
        message: `${label} requires a field name or a non-negative array index.`
      }]
    });
    return;
  }
  connection[field] = [...(connection[field] || []), value];
  input.value = "";
  finishAuthoring({
    status: "authoring",
    action: `mapping-${field}-segment-added`,
    connection: flow.connections.indexOf(connection),
    segment: value
  });
}

function removePathSegment(connection, field, index) {
  const path = [...connection[field]];
  const [segment] = path.splice(index, 1);
  if (path.length === 0) {
    delete connection[field];
  } else {
    connection[field] = path;
  }
  finishAuthoring({
    status: "authoring",
    action: `mapping-${field}-segment-removed`,
    connection: flow.connections.indexOf(connection),
    segment
  });
}

function mappingDefaultField(connection, target, ordinal) {
  const wrapper = element("div", "mapping-field mapping-default");
  const toggle = element("label", "mapping-default-toggle");
  const checkbox = element("input");
  checkbox.type = "checkbox";
  checkbox.checked = Object.hasOwn(connection, "default");
  checkbox.setAttribute("aria-label", mappingLabel("Use default", target, ordinal));
  checkbox.addEventListener("change", (event) => {
    if (event.currentTarget.checked) {
      connection.default = null;
    } else {
      delete connection.default;
    }
    finishAuthoring({
      status: "authoring",
      action: event.currentTarget.checked ? "mapping-default-added" : "mapping-default-removed",
      connection: flow.connections.indexOf(connection)
    });
  });
  toggle.append(checkbox, element("span", "", "Default"));
  const value = element("label", "mapping-default-value");
  value.append(element("span", "", "Value"));
  const input = element("input");
  input.type = "text";
  input.disabled = !Object.hasOwn(connection, "default");
  input.value = Object.hasOwn(connection, "default") ? JSON.stringify(connection.default) : "";
  input.setAttribute("aria-label", mappingLabel("Default value", target, ordinal));
  input.addEventListener("change", (event) =>
    editMappingDefault(connection, event.currentTarget)
  );
  value.append(input);
  wrapper.append(toggle, value);
  return wrapper;
}

function mappingConversionField(connection, target, ordinal) {
  const wrapper = element("label", "mapping-field");
  wrapper.append(element("span", "", "Conversion"));
  const select = element("select");
  select.setAttribute("aria-label", mappingLabel("Convert", target, ordinal));
  select.append(
    routeOption("", "None"),
    ...availableConversions.map((conversion) => routeOption(conversion, conversion))
  );
  select.value = connection.convert || "";
  select.addEventListener("change", (event) => {
    if (event.currentTarget.value) {
      connection.convert = event.currentTarget.value;
    } else {
      delete connection.convert;
    }
    finishAuthoring({
      status: "authoring",
      action: "mapping-conversion-changed",
      connection: flow.connections.indexOf(connection),
      conversion: event.currentTarget.value || "none"
    });
  });
  wrapper.append(select);
  return wrapper;
}

function editMappingDefault(connection, input) {
  try {
    connection.default = JSON.parse(input.value);
  } catch {
    input.setAttribute("aria-invalid", "true");
    const error = element("span", "mapping-field-error", "Default value must be valid JSON.");
    input.parentElement.querySelector(".mapping-field-error")?.remove();
    input.after(error);
    renderConsole({
      status: "authoring-rejected",
      diagnostics: [{
        code: "MAPPING_FIELD_JSON_INVALID",
        path: `connections[${flow.connections.indexOf(connection)}].default`,
        message: error.textContent
      }]
    });
    return;
  }
  finishAuthoring({
    status: "authoring",
    action: "mapping-default-changed",
    connection: flow.connections.indexOf(connection)
  });
}

function removeMapping(connection) {
  const index = flow.connections.indexOf(connection);
  if (index < 0) {
    return;
  }
  flow.connections.splice(index, 1);
  finishAuthoring({
    status: "authoring",
    action: "mapping-removed",
    connection: index,
    target: connection.to,
    source: connection.from
  });
}

function mappingLabel(label, target, ordinal) {
  return `${label} ${target}${ordinal === 1 ? "" : ` ${ordinal}`}`;
}

function humanMappingExpression(connection) {
  let source = endpointLabel(connection.from);
  if (connection.sourcePath) {
    source += ` · ${pathBreadcrumb(connection.sourcePath)}`;
  }
  if (Object.hasOwn(connection, "default")) {
    source += ` · fallback ${JSON.stringify(connection.default)}`;
  }
  if (connection.convert) {
    source += ` · ${humanize(connection.convert)}`;
  }
  let target = endpointLabel(connection.to);
  if (connection.targetPath) {
    target += ` · ${pathBreadcrumb(connection.targetPath)}`;
  }
  return `${source} → ${target}`;
}

function pathBreadcrumb(path) {
  return path.map((part) => typeof part === "number" ? `[${part}]` : humanize(part)).join(" › ");
}

function dataSources(currentConnection, targetEndpoint) {
  const targetOwner = targetEndpoint.split(".")[0];
  const layout = graphLayout();
  return [
    ...Object.entries(flow.inputs).map(([name, shape]) => ({ endpoint: `input.${name}`, shape })),
    ...flow.steps.flatMap((step) => (definitionFor(step)?.outputs || []).map(
      (port) => ({ endpoint: `${step.id}.${port.name}`, shape: port.shape })
    ))
  ].filter((source) => {
    if (source.endpoint === currentConnection?.from) {
      return true;
    }
    if (flow.connections.some((connection) => connection.from === source.endpoint)) {
      return false;
    }
    const sourceOwner = source.endpoint.split(".")[0];
    if (sourceOwner === targetOwner) {
      return false;
    }
    if (sourceOwner === "input") {
      return true;
    }
    if (targetOwner === "output") {
      return layout.reachable.has(sourceOwner);
    }
    return (layout.positions.get(sourceOwner)?.depth ?? Number.MAX_SAFE_INTEGER)
      < (layout.positions.get(targetOwner)?.depth ?? -1);
  });
}

function mapData(target, connection, source) {
  const index = connection ? flow.connections.indexOf(connection) : -1;
  const previousSource = index < 0 ? "" : connection.from;
  if (source) {
    if (index < 0) {
      flow.connections.push({ from: source, to: target });
    } else {
      connection.from = source;
    }
  } else if (index >= 0) {
    flow.connections.splice(index, 1);
  }
  finishAuthoring({
    status: "authoring",
    action: source
      ? previousSource ? "data-remapped" : "data-connected"
      : "data-disconnected",
    target,
    previousSource: previousSource || "unmapped",
    source: source || "unmapped"
  });
}

function addStep(definition) {
  const invocation = newInvocation(definition);
  flow.steps.push(invocation);
  selectedStepId = invocation.id;
  selectedEntity = `step:${invocation.id}`;
  finishAuthoring({
    status: "authoring",
    action: "step-added",
    step: invocation.id,
    mappingsAdded: 0,
    outcomesConnected: 0
  });
}

function removeStep(stepId) {
  const previousConnectionCount = flow.connections.length;
  flow.connections = flow.connections.filter(
    (connection) => !connection.from.startsWith(`${stepId}.`) && !connection.to.startsWith(`${stepId}.`)
  );
  let transitionsRemoved = 0;
  for (const invocation of flow.steps) {
    for (const [outcome, target] of Object.entries(invocation.on)) {
      if (invocation.id === stepId || target === stepId) {
        transitionsRemoved++;
        if (invocation.id !== stepId) {
          delete invocation.on[outcome];
        }
      }
    }
  }
  flow.steps = flow.steps.filter((step) => step.id !== stepId);
  const entryCleared = flow.entry === stepId;
  if (entryCleared) {
    flow.entry = "";
  }
  selectedStepId = flow.steps[0]?.id || "";
  selectedEntity = selectedStepId ? `step:${selectedStepId}` : "application";
  finishAuthoring({
    status: "authoring",
    action: "step-removed",
    step: stepId,
    connectionsRemoved: previousConnectionCount - flow.connections.length,
    transitionsRemoved,
    entryCleared
  });
}

async function request(path, options = {}) {
  const response = await fetch(path, options);
  const payload = await response.json();
  return { ok: response.ok, payload };
}

function compileSource(source, signal) {
  return request("/api/compile", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: source,
    signal
  });
}

function compileFlow(signal) {
  return compileSource(JSON.stringify(flow), signal);
}

function cancelDraftCheck() {
  draftController?.abort();
  draftController = undefined;
}

async function checkDraft() {
  cancelDraftCheck();
  const controller = new AbortController();
  draftController = controller;
  const revision = ++draftRevision;
  draftState.textContent = "Checking draft...";
  draftState.className = "draft-state";
  draftDiagnostics.replaceChildren();
  try {
    const result = await compileFlow(controller.signal);
    if (revision === draftRevision) {
      renderDraftFeedback(result.ok, result.payload);
    }
  } catch (error) {
    if (error.name !== "AbortError" && revision === draftRevision) {
      draftState.textContent = "Compiler unavailable";
      draftState.className = "draft-state invalid";
    }
  } finally {
    if (draftController === controller) {
      draftController = undefined;
    }
  }
}

async function loadSteps() {
  try {
    const { ok, payload } = await request("/api/steps");
    availableSteps = ok ? payload.steps : [];
    availableShapes = ok ? payload.shapes : [];
    availableConversions = ok ? payload.conversions : [];
    maxEventSourceBytes = ok ? payload.maxEventSourceBytes : undefined;
    if (maxEventSourceBytes !== undefined) {
      retainEventSource();
      inputField.disabled = false;
    }
    renderSteps();
    renderFlow();
    renderInspector();
    checkDraft();
  } catch {
    draftRevision++;
    availableSteps = [];
    availableShapes = [];
    availableConversions = [];
    maxEventSourceBytes = undefined;
    renderSteps();
    renderFlow();
    renderInspector();
    draftState.textContent = "Compiler unavailable";
    draftState.className = "draft-state invalid";
    draftDiagnostics.replaceChildren();
    renderConsole("Creator server is unavailable.");
  }
}

async function validateFlow() {
  cancelDraftCheck();
  const revision = ++draftRevision;
  setState("Validating...", "");
  try {
    const { ok, payload } = await compileFlow();
    if (revision !== draftRevision) {
      return;
    }
    setState(ok ? "Valid" : "Invalid", ok ? "valid" : "invalid");
    renderDraftFeedback(ok, payload);
    renderConsole(payload);
  } catch {
    if (revision === draftRevision) {
      setState("Unavailable", "invalid");
      renderConsole("Creator server is unavailable.");
    }
  }
}

async function saveFlow() {
  cancelDraftCheck();
  const revision = ++draftRevision;
  setState("Saving...", "");
  try {
    const { ok, payload } = await compileFlow();
    if (revision !== draftRevision) {
      return;
    }
    renderDraftFeedback(ok, payload);
    if (!ok) {
      setState("Save failed", "invalid");
      renderConsole(payload);
      return;
    }
    const url = URL.createObjectURL(new Blob(
      [payload.source],
      { type: "application/json;charset=utf-8" }
    ));
    const download = document.createElement("a");
    download.href = url;
    download.download = "railix.flow.json";
    download.hidden = true;
    document.body.append(download);
    download.click();
    download.remove();
    setTimeout(() => URL.revokeObjectURL(url));
    setState("Flow saved", "valid");
    renderConsole({ status: "flow-saved", flow: flow.id, file: download.download });
  } catch {
    if (revision === draftRevision) {
      setState("Unavailable", "invalid");
      renderConsole("Creator server is unavailable.");
    }
  }
}

async function openFlow(event) {
  const file = event.currentTarget.files[0];
  event.currentTarget.value = "";
  if (!file) {
    return;
  }
  cancelDraftCheck();
  const revision = ++draftRevision;
  setState("Opening...", "");
  try {
    const { ok, payload } = await compileSource(file);
    if (revision !== draftRevision) {
      return;
    }
    renderDraftFeedback(ok, payload);
    if (!ok) {
      setState("Open failed", "invalid");
      renderConsole(payload);
      return;
    }
    flow = JSON.parse(payload.source);
    generatedNames.add(flow.id);
    selectedStepId = flow.steps[0]?.id || "";
    selectedEntity = "application";
    cancelChoice();
    renderFlow();
    renderInspector();
    setState("Flow opened", "valid");
    renderConsole({ status: "flow-opened", flow: flow.id, file: file.name });
  } catch {
    if (revision === draftRevision) {
      setState("Unavailable", "invalid");
      renderConsole("Creator server is unavailable.");
    }
  }
}

function selectEventFormat(nextFormat) {
  retainEventSource();
  eventFormat = nextFormat;
  inputField.value = eventExamples[eventFormat];
  for (const button of eventFormatButtons) {
    const selected = button.dataset.eventFormat === eventFormat;
    button.classList.toggle("selected", selected);
    button.setAttribute("aria-pressed", selected);
  }
  document.querySelector("#event-source-description").textContent =
    `${eventFormat.toUpperCase()} object sent only when Run is pressed`;
}

function retainEventSource() {
  if (maxEventSourceBytes === undefined) {
    return;
  }
  const source = inputField.value;
  if (new TextEncoder().encode(source).length > maxEventSourceBytes) {
    inputField.value = eventExamples[eventFormat];
    renderConsole({
      status: "event-rejected",
      diagnostics: [{
        code: "EVENT_SOURCE_TOO_LARGE",
        path: "event.source",
        message: `Event source exceeds the ${maxEventSourceBytes}-byte limit.`
      }]
    });
    return;
  }
  eventExamples[eventFormat] = source;
}

async function runFlow() {
  cancelDraftCheck();
  const revision = ++draftRevision;
  setState("Running...", "");
  try {
    const { ok, payload } = await request("/api/run", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        flow,
        event: { format: eventFormat, source: inputField.value }
      })
    });
    if (revision !== draftRevision) {
      return;
    }
    setState(ok ? "Run passed" : "Run failed", ok ? "valid" : "invalid");
    renderConsole(payload);
  } catch {
    if (revision === draftRevision) {
      setState("Unavailable", "invalid");
      renderConsole("Creator server is unavailable.");
    }
  }
}

document.querySelector("#new-button").addEventListener("click", newFlow);
document.querySelector("#open-button").addEventListener("click", () => flowFileInput.click());
document.querySelector("#save-button").addEventListener("click", saveFlow);
document.querySelector("#validate-button").addEventListener("click", validateFlow);
document.querySelector("#run-button").addEventListener("click", runFlow);
document.querySelector("#add-trigger").addEventListener("click", () => {
  triggerMenu.hidden = !triggerMenu.hidden;
});
for (const button of triggerMenu.querySelectorAll("[data-trigger-type]")) {
  button.addEventListener("click", () => addTrigger(button.dataset.triggerType));
}
cancelStepChoice.addEventListener("click", cancelChoice);
applicationName.addEventListener("change", (event) =>
  renameApplication(event.currentTarget.value)
);
document.querySelector("#clear-console").addEventListener("click", () => renderConsole(""));
document.querySelector("#add-flow-input").addEventListener("click", () => addFlowPort("input"));
document.querySelector("#add-flow-output").addEventListener("click", () => addFlowPort("output"));
document.querySelector("#add-http-route").addEventListener("click", addHttpRoute);
document.querySelector("#add-schedule").addEventListener("click", addSchedule);
httpPort.addEventListener("change", (event) => setHttpPort(event.currentTarget.value));
flowEventToggle.addEventListener("change", (event) =>
  setHttpIngress("flow", true, event.currentTarget.checked)
);
stepEventToggle.addEventListener("change", (event) =>
  setHttpIngress("step", selectedStepId, event.currentTarget.checked)
);
socketToggle.addEventListener("change", (event) => setSocketIngress(event.currentTarget.checked));
socketPort.addEventListener("change", (event) => setSocketConfig("port", event.currentTarget.value));
socketTimeout.addEventListener("change", (event) =>
  setSocketConfig("timeoutMillis", event.currentTarget.value)
);
socketConnections.addEventListener("change", (event) =>
  setSocketConfig("maxConnections", event.currentTarget.value)
);
search.addEventListener("input", () => renderSteps(search.value));
flowFileInput.addEventListener("change", openFlow);
inputField.addEventListener("input", retainEventSource);
for (const button of eventFormatButtons) {
  button.addEventListener("click", () => selectEventFormat(button.dataset.eventFormat));
}
loadSteps();
