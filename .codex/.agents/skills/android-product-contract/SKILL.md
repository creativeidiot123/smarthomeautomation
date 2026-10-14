---
name: android-product-contract
description: "Use before coding a non-trivial feature when requirements are incomplete: define journeys, invariants, data lifecycle, offline/retry semantics, security/privacy, platform constraints, acceptance, non-goals."
---

# Android Product Contract from Raw Ideas

## Purpose

A raw idea describes intent but rarely defines correct behavior. Convert it into a small, testable product contract before architecture or implementation. The contract must state what users can observe, which outcomes are forbidden, and which system owns each product fact. It must not prescribe layers or libraries prematurely.

## Ambiguity policy

Classify unresolved decisions before proceeding:

| Risk | Examples | Required action |
|---|---|---|
| Critical | money, identity, authorization, destructive data loss, irreversible migration, legal/privacy consent | obtain an explicit decision; never guess |
| High | conflict resolution, cross-device truth, retention/deletion, public API/deep-link behavior, background execution, entitlement | obtain an explicit decision; when the user explicitly delegates it, select the safest reversible policy and record consequences |
| Moderate | offline fallback, retry timing, notification behavior, process restoration, accessibility behavior | ask when product-significant; otherwise choose a reversible default and record it |
| Low | copy, spacing, minor ordering, reversible UI detail | choose the simplest design-system-compatible default and record only when material |

Never invent server-side idempotency, authorization, atomicity, ordering, availability, retention, conflict resolution, or delivery guarantees.

## Product contract

Resolve this compact contract:

```text
Product/feature name:
Goal:
Primary users and account/tenant context:
Required user journeys:
Entry points, screens, and navigation:
Observable success for each journey:
Empty, loading, error, stale, offline, pending, and conflict behavior:
Canonical product facts and authority for each:
Mutations and commit points:
Duplicate, retry, undo, cancellation, timeout, and unknown-outcome policy:
Data collected, generated, cached, retained, exported, synchronized, and deleted:
Authentication, authorization, entitlement, and session behavior:
Permissions and degraded behavior after denial/revocation:
Configuration recreation and process-restoration behavior:
Cross-device/concurrent-edit behavior:
Accessibility, localization, RTL, large text, keyboard, and form-factor constraints:
Supported API levels/devices and capability fallbacks:
Performance, latency, battery, storage, and network budgets:
Security, privacy, abuse, and logging constraints:
Backend/API/SDK/system contracts and version assumptions:
Analytics/operational signals permitted and prohibited:
Non-goals and out-of-scope behavior:
Forbidden outcomes:
Material assumptions awaiting confirmation:
```

Remove fields that are genuinely irrelevant; do not silently omit a field merely because its policy is difficult.

## Domain facts and invariants

List product facts independently of UI widgets or database columns. For each mutable fact, identify:

```text
Fact:
Authority:
Who may mutate it:
Valid states and transitions:
Invariant that must always hold:
Conflict/ordering rule:
Persistence/restoration requirement:
Privacy/classification:
Observable projection(s):
```

Prefer types and state machines that make illegal combinations unrepresentable. Where the state depends on an external boundary, model invalid, unknown, stale, pending, and partial outcomes rather than asserting impossibility.

## Journey and acceptance scenarios

Write acceptance as observable Given/When/Then scenarios. Cover the paths material to the product:

- normal success;
- empty/first-use;
- validation and denied action;
- offline/stale/reconnect;
- rapid duplicate action;
- older completion after newer intent;
- cancellation/navigation away;
- configuration recreation and process restoration;
- timeout or partial success at the side-effect boundary;
- account/tenant change;
- permission denial/revocation;
- accessibility and adaptive-layout behavior;
- migration/update from supported prior versions;
- backend/SDK unavailable or incompatible.

A scenario must specify the visible result and the protected invariant. “Works,” “fast,” “secure,” and “robust” are not acceptance criteria without measurable observations.

## Data lifecycle contract

For each user or business data class, define:

```text
Collection/source:
Purpose:
Authority/local truth/cache:
Identity/account/tenant key:
Encryption or approved storage:
Retention and deletion trigger:
Logout/account-switch behavior:
Backup/export/screenshot/log/analytics policy:
Offline/sync/conflict behavior:
Migration compatibility:
```

Client storage does not create authorization or entitlement authority. Destructive deletion, migration, or conflict policy requires an explicit product decision.

## External contract gate

For every backend, SDK, OS service, or cross-device dependency, identify:

- authentication and authorization owner;
- request/response and compatibility contract;
- idempotency and duplicate behavior;
- ordering and consistency guarantees;
- timeout and unknown write outcome;
- pagination, clocks, expiry, and versioning;
- rate limits and retry hints;
- callback/delivery and process-death semantics;
- unavailable, malformed, revoked, and deprecated behavior.

When a guarantee is absent, design reconciliation or expose the limitation. Do not simulate a server guarantee with an in-process mutex or UI flag.

## Non-functional constraints

Set only constraints relevant to the product and make them testable:

- supported Android versions, devices, orientations, windows, Wear/XR/KMP targets;
- startup, interaction, frame, memory, network, storage, and battery budgets;
- offline duration and freshness expectations;
- accessibility conformance and input methods;
- localization, time zone, locale, and calendar behavior;
- privacy, security, compliance, and data residency requirements;
- observability, rollout, rollback/disable, and incident response requirements.

Do not add infrastructure merely because a field exists. Reuse project capabilities and state gaps explicitly.

## Scope and simplicity gate

Before architecture, classify each proposed capability as required, optional, or out of scope. Reject speculative flexibility, generalized platforms, premature modules, generic sync engines, custom design systems, and admin/configuration surfaces not required by acceptance.

Choose the simplest reversible product behavior compatible with the stated invariants. Simplicity must not hide partial failure, data loss, weak authorization, or ambiguous mutation outcomes.

## Architecture handoff

Pass this to the owning Android skills:

```text
Accepted product contract/version:
Acceptance scenarios:
Domain facts/invariants:
Authority and local truth:
Mutation/commit/reconciliation policy:
Lifecycle/restoration policy:
Concurrency/duplicate/retry policy:
Data lifecycle/security policy:
Platform/release constraints:
External guarantees and gaps:
Explicit assumptions:
Non-goals:
Test oracles:
```

Then construct the owner map in `android-architecture-ownership` and use the smallest repository-compatible implementation.

## Change control

When implementation reveals a missing requirement:

1. identify the exact ambiguity and affected invariant;
2. classify its risk;
3. update the product contract before encoding policy in code;
4. add or update the corresponding acceptance scenario;
5. trace the change through implementation and verification.

Do not bury product decisions in comments, fallback branches, constants, database defaults, or test expectations.

## AI-generated product hazards

- translating a feature noun directly into a technology choice;
- inventing screens, roles, permissions, sync, or analytics not requested;
- treating UI copy as the business rule;
- declaring a server, Room, or cache authoritative without product policy;
- saying “offline-first” without stale, mutation, conflict, and reconnect behavior;
- treating cancellation or timeout as proof a remote write did not happen;
- using a loading flag as duplicate prevention;
- omitting deletion, logout, account-switch, or process-restoration behavior;
- adding broad abstractions to accommodate hypothetical future features;
- writing tests that formalize an unconfirmed assumption;
- claiming perfect, complete, production-ready, or fully verified behavior without bounded evidence.

## Product-contract audit

Before implementation, verify:

```text
[ ] Every required journey has observable acceptance.
[ ] Every mutable product fact has one authority and valid transitions.
[ ] Destructive, monetary, identity, authorization, privacy, retention, and conflict policies are explicit.
[ ] Offline/stale/retry/duplicate/cancel/timeout/partial outcomes are defined where applicable.
[ ] Recreation, process death, account switch, permission changes, and platform unavailability are defined where applicable.
[ ] External guarantees are evidenced or marked absent; none were invented.
[ ] Non-goals prevent speculative features and architecture.
[ ] Requirements do not mandate implementation mechanisms without product need.
[ ] Assumptions are risk-classified and visible.
[ ] Acceptance scenarios can fail for the defects they are intended to prevent.
[ ] Operational detection and rollback/disable or forward-fix expectations exist for material release risk.
```

### Evidence record

```text
Raw idea received:
Material ambiguities and decisions:
Assumptions selected:
Product facts/invariants:
Acceptance scenarios:
Forbidden outcomes:
External guarantee gaps:
Out-of-scope items:
Implementation handoff completed:
Residual product uncertainty:
```
