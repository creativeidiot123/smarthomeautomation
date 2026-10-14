# Upstream source and media policy

This skill is vendored from [abhixv/m3-expressive-design-skill](https://github.com/abhixv/m3-expressive-design-skill) at commit
[`70ff9dcecd2cf04660109b4e4a89205a2dacd290`](https://github.com/abhixv/m3-expressive-design-skill/commit/70ff9dcecd2cf04660109b4e4a89205a2dacd290).

Destination imports only the text-based skill and Markdown references. It intentionally does **not**
import the upstream `visuals/` media tree, `visuals/INDEX.tsv`, or `scripts/` refresh tooling.

External visual/example references:

- Visual library: https://github.com/abhixv/m3-expressive-design-skill/tree/70ff9dcecd2cf04660109b4e4a89205a2dacd290/skills/m3-expressive/visuals
- Visual caption index: https://github.com/abhixv/m3-expressive-design-skill/blob/70ff9dcecd2cf04660109b4e4a89205a2dacd290/skills/m3-expressive/visuals/INDEX.tsv
- Canonical layout examples: https://github.com/abhixv/m3-expressive-design-skill/tree/70ff9dcecd2cf04660109b4e4a89205a2dacd290/skills/m3-expressive/visuals/foundations_layout_canonical-examples
- Layout overview examples: https://github.com/abhixv/m3-expressive-design-skill/tree/70ff9dcecd2cf04660109b4e4a89205a2dacd290/skills/m3-expressive/visuals/foundations_layout_layout-overview
- Upstream refresh scripts: https://github.com/abhixv/m3-expressive-design-skill/tree/70ff9dcecd2cf04660109b4e4a89205a2dacd290/skills/m3-expressive/scripts

When updating this skill, refresh the text snapshot and both Destination copies together:
`.agents/skills/m3-expressive` and `.codex/.agents/skills/m3-expressive`. Keep the media external.
