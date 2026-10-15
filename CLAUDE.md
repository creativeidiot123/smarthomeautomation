# CLAUDE.md

This file exists so Claude Code loads this repo's contract even in configurations
that don't fall back to `AGENTS.md` automatically. It intentionally has no content
of its own — the single source of truth is `AGENTS.md` at the repo root, which
Codex and Claude Code both read via the import below. Do not fork instructions
into this file; edit `AGENTS.md` and both tools stay in sync automatically.

Claude Code auto-loads the HomeDJ skills in `.claude/skills/` by their descriptions;
`AGENTS.md` §2 routes work to them and explains how the `.agents/` and `.codex/`
mirrors are kept identical (enforced by `HomeDjGuardrailTest`).

@AGENTS.md
