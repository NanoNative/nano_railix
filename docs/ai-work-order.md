# AI Work Order Template

Use this when asking an AI coding agent to implement the repo.

## Task

Implement Slice X from `ROADMAP.md`.

## Constraints

- Follow `IMPLEMENTATION_PROMPT.md`.
- Do not add external libraries unless the task explicitly allows it.
- Preserve the app-plan-first architecture.
- Do not insert an event bus between steps.
- Use Java records/sealed interfaces where useful.
- Keep APIs explicit and small.
- Add tests for path parsing, selector behavior, patch behavior, and settings resolution.

## Expected output

- Code
- Tests
- Updated docs if contracts changed
- Small example under `examples/`
- No hidden architectural rewrites

## Review checklist

- Is Step still the main unit?
- Are config and secrets still one SettingsTree?
- Are nested values handled as RailixValue + Path + Selector + Patch?
- Are run signals observation-only?
- Are unused packs still outside the kernel?
- Can the Creator still edit the canonical plan?
