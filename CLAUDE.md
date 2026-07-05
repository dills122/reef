# CLAUDE

AI guidance for this repo lives in [`AGENTS.md`](AGENTS.md) — read it first, every session.

It is tool-agnostic (written for Codex originally, applies equally here) and indexes:

- `docs/steering/` — canonical architecture/language/repo steering (11 docs)
- `docs/DECISIONS.md`, `docs/ENGINEERING_DELIVERY_POLICY.md`, `docs/PERFORMANCE_LEARNINGS.md`
- `REEF_PROJECT_OVERVIEW.md`, `REEF_TECHNICAL_DESIGN.md`

Do not duplicate that content here. If steering changes, edit `AGENTS.md` / `docs/steering/`, not this file.

## Not relevant to Claude

`.codex/skills/` and most of `.codex/steering/` are symlinks to an external Codex-only template library (`~/Documents/ai-central/`) — generic, not repo-specific, and not usable here. Ignore them. The two real files in `.codex/steering/` (`repository-steering.md`, `testing-quality-gates-steering.md`) are already superseded by `docs/steering/repository.md` and `docs/steering/repository-scope-and-priorities.md`.
