# Railix

Read the relevant skills before changes; open the linked files directly if your tool does not discover them:

- [Specification](.agents/skills/railix-specification/SKILL.md): collaborative requirements, ADRs and roadmap before implementation.
- [Java](.agents/skills/railix-java/SKILL.md): contracts, compiler, runtime, backend.
- [CSS/UI](.agents/skills/railix-css-ui/SKILL.md): factory visuals, HUD, Canvas, performance.
- [Assets](.agents/skills/railix-creator-assets/SKILL.md): discovery, file editors, installation, Settings.
- [Audio](.agents/skills/railix-audio/SKILL.md): music composition, sound effects, synthesis, mixing.
- [Verification](.agents/skills/railix-verification/SKILL.md): tests, reviews, profiling, packaging.

Each skill contains its own rules and checks; combine skills only when the task spans their scopes.
Run commands from repository root. [README](README.md#documentation-map) maps [specs](docs/specs/)
(behavior), [ADRs](docs/adr/) (decisions), and [roadmap](docs/roadmap.md) (sequence/status).
Consult the relevant owner, not a mandatory reading chain.

## Working Rules

- Preserve unrelated work. Resolve repository facts yourself; ask before material design or scope changes.
- Make the smallest working change through the owning public boundary. Report actual verification and gaps.
- Stop task-owned helper processes when finished; leave unrelated processes alone.
- Review the intended diff before publication. Commit, push, and PR creation each need explicit, single-use permission.
