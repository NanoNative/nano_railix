"use strict";

/** Owns custom-Step creation and management UI; callbacks retain transport and catalog ownership. */
function initializeCustomSteps({canEdit, getDefinition, submit, onChanged, onClose, load, remove}) {
  const dialog = document.querySelector('#custom-step-dialog');
  const form = document.querySelector('#custom-step-form');
  const name = document.querySelector('#custom-step-name');
  const source = document.querySelector('#custom-step-source');
  const button = document.querySelector('#compile-custom-step');
  const another = document.querySelector('#new-custom-step');
  const status = document.querySelector('#custom-step-status');
  const diagnostics = document.querySelector('#custom-step-diagnostics');
  const fields = [...form.querySelectorAll('input,textarea,button')];
  let compiling = false;
  let compiledDefinition = null;
  const manager = document.querySelector('#custom-step-manager');
  const deleteDialog = document.querySelector('#custom-step-delete-dialog');
  const list = document.querySelector('#custom-step-list');
  const managerStatus = document.querySelector('#custom-step-manager-status');
  const refresh = document.querySelector('#refresh-custom-steps');
  let managing = false;
  let managerRequest = 0;

  function confirmDefinitionDeletion(step) {
    document.querySelector('#custom-step-delete-description').textContent =
      `This removes the definition “${step.name}” from this project.`;
    deleteDialog.returnValue = 'cancel';
    return new Promise(resolve => {
      deleteDialog.addEventListener('close', () => resolve(deleteDialog.returnValue === 'delete'), {once:true});
      deleteDialog.showModal();
    });
  }

  async function deleteDefinition(step, revision, request) {
    if (managing || !canEdit()) return;
    managing = true;
    try {
      if (!await confirmDefinitionDeletion(step)) return;
      if (!manager.open || request !== managerRequest || !canEdit()) return;
      list.querySelectorAll('button').forEach(item => item.disabled = true);
      refresh.disabled = true;
      managerStatus.textContent = `Deleting ${step.name}…`;
      const result = await remove(step.id, revision);
      if (!result.ok) throw new Error(result.payload.message || 'The Step could not be deleted. Refresh before trying again.');
      await onChanged(result);
      if (manager.open && request === managerRequest) await refreshDefinitions();
    } catch (failure) {
      if (manager.open && request === managerRequest) managerStatus.textContent = failure.message || 'Deletion could not be confirmed. Refresh the list.';
    } finally {
      managing = false;
      refresh.disabled = false;
    }
  }

  async function refreshDefinitions() {
    const request = ++managerRequest;
    list.replaceChildren();
    refresh.disabled = true;
    managerStatus.textContent = 'Loading custom Steps…';
    try {
      const payload = await load();
      if (!manager.open || request !== managerRequest) return;
      managerStatus.textContent = payload.steps.length ? 'Only unused definitions can be deleted. Creation and deletion can be undone during this Creator session.' : 'This project has no custom Steps.';
      for (const step of payload.steps) {
        const row = document.createElement('li');
        const detail = document.createElement('div');
        const title = document.createElement('strong');
        title.textContent = step.name;
        const usage = document.createElement('p');
        usage.textContent = step.in_use ? 'In use. Remove all placements and nested calls before deleting.' : 'Unused';
        detail.append(title, usage);
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'danger';
        button.textContent = 'Delete';
        button.setAttribute('aria-label', `Delete ${step.name}`);
        button.disabled = step.in_use;
        button.addEventListener('click', () => { void deleteDefinition(step, payload.catalog_revision, request); });
        row.append(detail, button);
        list.append(row);
      }
    } catch (failure) {
      if (manager.open && request === managerRequest) managerStatus.textContent = failure.message || 'Custom Steps could not be loaded. Try again.';
    } finally {
      if (request === managerRequest) refresh.disabled = false;
    }
  }

  function markDraftChanged() {
    if (compiling) return;
    compiledDefinition = null;
    button.disabled = false;
    button.textContent = 'Compile Step';
    status.textContent = 'Not compiled. Compile this draft to add a new Step.';
    diagnostics.hidden = true;
  }

  function refreshCompiledStatus() {
    if (compiling || !compiledDefinition) return;
    const current = getDefinition(compiledDefinition.id);
    const available = Boolean(compiledDefinition.implementation
      && current?.implementation === compiledDefinition.implementation);
    button.disabled = available;
    button.textContent = available ? 'Compiled' : 'Compile Step';
    status.textContent = available
      ? `Compiled. ${current.display_name} is available for compatible inputs in Add Step.`
      : 'Not compiled. This draft is not available in the current project.';
  }

  function setBusy(busy) {
    compiling = busy;
    fields.forEach(field => field.disabled = busy);
    if (busy) form.setAttribute('aria-busy', 'true');
    else form.removeAttribute('aria-busy');
  }

  function diagnosticText(payload) {
    return payload.diagnostics?.map(issue =>
      `${Number(issue.line) > 0 ? `Line ${issue.line}, column ${issue.column}: ` : ''}${issue.message}`
    ).join('\n') || payload.message || 'The Step could not be compiled. Try again.';
  }

  function showFailure(detail, summary) {
    diagnostics.textContent = detail;
    diagnostics.hidden = false;
    status.textContent = summary;
  }

  async function compileDraft(event) {
    event.preventDefault();
    if (compiling || !canEdit()) return;
    const draft = {name:name.value.trim(), source:source.value};
    compiledDefinition = null;
    setBusy(true);
    button.textContent = 'Compiling…';
    status.textContent = 'Compiling Java and checking the Step contract…';
    diagnostics.hidden = true;
    let compiled = false;
    try {
      const submission = await submit(draft);
      if (!submission.ok) {
        showFailure(diagnosticText(submission.payload), 'Not compiled. This draft has not been added to the Step picker.');
        return;
      }
      compiled = true;
      await onChanged(submission);
      const definition = submission.payload.definition;
      compiledDefinition = {id:definition.id, implementation:definition.implementation};
      another.hidden = false;
    } catch (failure) {
      showFailure(failure.message || 'Compilation could not be confirmed. Reopen the project to check its catalog before retrying.',
        'Compilation could not be confirmed.');
    } finally {
      setBusy(false);
      button.textContent = compiled ? 'Compiled' : 'Compile Step';
      button.disabled = compiled;
      refreshCompiledStatus();
    }
  }

  document.addEventListener('click', event => {
    if (event.target.closest('[data-manage-custom-steps]') && canEdit()) {
      document.querySelector('#project-actions').hidePopover();
      if (!manager.open) manager.showModal();
      void refreshDefinitions();
      return;
    }
    if (!event.target.closest('[data-create-custom-step]') || !canEdit()) return;
    document.querySelector('#project-actions').hidePopover();
    refreshCompiledStatus();
    if (!dialog.open) dialog.showModal();
    name.focus();
  });
  document.querySelector('#close-custom-step').addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', onClose);
  // Keep graph shortcuts outside the modal; native Escape and text editing still work.
  dialog.addEventListener('keydown', event => event.stopPropagation());
  manager.addEventListener('keydown', event => event.stopPropagation());
  deleteDialog.addEventListener('keydown', event => event.stopPropagation());
  manager.addEventListener('close', () => {
    managerRequest++;
    if (deleteDialog.open) deleteDialog.close('cancel');
    onClose();
  });
  document.querySelector('#close-custom-step-manager').addEventListener('click', () => manager.close());
  refresh.addEventListener('click', () => { if (!managing) void refreshDefinitions(); });
  form.addEventListener('input', markDraftChanged);
  form.addEventListener('submit', compileDraft);
  another.addEventListener('click', () => {
    form.reset();
    another.hidden = true;
    markDraftChanged();
    name.focus();
  });
  return {refreshCompiledStatus};
}
