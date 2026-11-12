"use strict";

/** Owns the editor UI; callbacks retain application transport and catalog ownership. */
function initializeCustomStepEditor({canCreate, submit, onCompiled, onClose}) {
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

  function markDraftChanged() {
    if (compiling) return;
    button.disabled = false;
    button.textContent = 'Compile Step';
    status.textContent = 'Not compiled. Compile this draft to add a new Step.';
    diagnostics.hidden = true;
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
    if (compiling || !canCreate()) return;
    const draft = {name:name.value.trim(), source:source.value};
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
      onCompiled(submission);
      status.textContent = `Compiled. ${submission.payload.definition.display_name} is available for compatible inputs in Add Step.`;
      another.hidden = false;
    } catch (failure) {
      showFailure(failure.message || 'Compilation could not be confirmed. Reopen the project to check its catalog before retrying.',
        'Compilation could not be confirmed.');
    } finally {
      setBusy(false);
      button.textContent = compiled ? 'Compiled' : 'Compile Step';
      button.disabled = compiled;
    }
  }

  document.addEventListener('click', event => {
    if (!event.target.closest('[data-create-custom-step]') || !canCreate()) return;
    document.querySelector('#project-actions').hidePopover();
    if (!dialog.open) dialog.showModal();
    name.focus();
  });
  document.querySelector('#close-custom-step').addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', onClose);
  // Keep graph shortcuts outside the modal; native Escape and text editing still work.
  dialog.addEventListener('keydown', event => event.stopPropagation());
  form.addEventListener('input', markDraftChanged);
  form.addEventListener('submit', compileDraft);
  another.addEventListener('click', () => {
    form.reset();
    another.hidden = true;
    markDraftChanged();
    name.focus();
  });
}
