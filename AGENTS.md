# Railix

Read the relevant skills before changes; open the linked files directly if your tool does not discover them:

- [Specification](.agents/skills/product-specification/SKILL.md): collaborative requirements, ADRs and roadmap before implementation.
- [Java](.agents/skills/java-engineering/SKILL.md): contracts, compiler, runtime, backend.
- [CSS/UI](.agents/skills/css-canvas-scenes/SKILL.md): factory visuals, HUD, Canvas, performance.
- [Assets](.agents/skills/asset-overrides/SKILL.md): discovery, file editors, installation, Settings.
- [Audio](.agents/skills/procedural-audio/SKILL.md): music composition, sound effects, synthesis, mixing.
- [Verification](.agents/skills/implementation-verification/SKILL.md): tests, reviews, profiling, packaging.

Skills contain project-independent methods; Railix behavior and decisions belong in specs and ADRs.
Run commands from repository root. [README](README.md#documentation-map) maps [specs](docs/specs/)
(behavior), [ADRs](docs/adr/) (decisions), and [roadmap](docs/roadmap.md) (sequence/status).
Consult the relevant owner, not a mandatory reading chain.

## Working Rules

- Preserve unrelated work. Resolve repository facts yourself; ask before material design or scope changes.
- Make the smallest working change through the owning public boundary. Report actual verification and gaps.
- For nontrivial Creator changes, exercise a saved, branch-heavy project in the UI with examples and reuse; inspect desktop and narrow layouts, and show a screenshot.
- Stop task-owned helper processes when finished; leave unrelated processes alone.
- Review the intended diff before publication. Commit, push, and PR creation each need explicit, single-use permission.
