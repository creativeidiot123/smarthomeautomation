---
name: android-architecture-ownership
description: "Use for Android/Kotlin architecture or ownership: state/truth/mutation owners, where a new class/object/function belongs, repositories/stores, layers, models, AndroidViewModel/Worker/Service construction without DI, dependency direction, business-logic placement, file cohesion and the 500-line size gate."
---

# Android Core Architecture and Kotlin

## Goal

Produce the smallest maintainable change that fits the repository. Architecture is the set of ownership and dependency decisions that prevent duplicated truth, lifecycle leaks, hidden effects, data corruption, and untestable coupling. Do not equate architecture with a fixed number of layers or modules.

## Inspect before editing

Read enough code to identify:

- module and source-set graph;
- feature/package boundaries;
- UI technology and navigation owner;
- ViewModel/state/event convention;
- repository/data-source/storage convention;
- DI and object lifetime convention;
- model roles and mapping points;
- error/result types;
- coroutine/dispatcher conventions;
- test and build commands;
- public, serialized, database, route, and manifest contracts.

Use repository search before inventing a helper. Prefer an existing local pattern that is safe over a generic “best practice.”

## Kotlin file size and cohesion gate

Treat ~500 lines as the default ceiling for a handwritten Kotlin source file. Before adding code that would materially exceed it, identify whether a real owner/responsibility can move behind an existing or clean seam. Split by cohesion and ownership, never by arbitrary line ranges. Exceed ~500 only when the file remains one coherent owner and splitting would add coupling, forwarding, lifecycle risk, or needless wiring; record the reason. Generated sources are excluded.

## Owner map

Record or internally resolve:

```text
Truth:
State:
Mutation:
Lifecycle:
Data/cache:
Navigation:
Dependency:
Test:
```

A concern can have collaborators but must have one authoritative owner. If current code has split ownership, repair only the seam needed by the task unless the user requested remediation.

## Dependency direction

Typical:

```text
UI -> ViewModel -> Repository -> API/DAO/DataStore/File
```

Optional domain/use-case layer is justified by reusable business policy, cross-repository orchestration, complex validation/calculation, transaction-like workflows, or a valuable independent test seam. Avoid pass-through use cases.

### Layer checks

**UI/Composable**

- renders state;
- owns transient visual mechanics;
- emits typed actions/callbacks;
- owns semantics/focus/insets/system launchers.

It does not call repositories, DAOs, APIs, DataStore, or construct services.

**Activity/Fragment**

- hosts UI and platform callbacks;
- collects with the correct lifecycle;
- does not become the feature service or business-policy owner;
- clears view references at the view lifecycle boundary.

**ViewModel**

- owns screen state/actions/validation and screen-scoped orchestration;
- exposes immutable observable state;
- does not hold View, Activity, Fragment, NavController, or mutable Context-bound resources;
- does not become the database/network/cache owner.

**Repository**

- owns source strategy, mapping, cache/stale rules, mutations, offline behavior, and boundary errors;
- does not know UI strings, snackbar, routes, or lifecycle objects.

**DAO/API/DataSource**

- implements one boundary;
- does not encode screen policy or navigation.

## Kotlin API design

Prefer:

- immutable `data class` state and `val` properties;
- narrow `internal` visibility;
- explicit return/error types at module boundaries;
- sealed interfaces/classes for exhaustive modes;
- typed IDs when IDs from different domains could be confused;
- pure extension functions near the owned type;
- constructor injection;
- composition/delegation over inheritance for behavior reuse;
- small functions named by business intent;
- exhaustiveness in `when` without broad `else` when future cases must be handled.

Use `Result` only when its semantics fit. A project-specific result type can preserve typed failure/cancellation metadata; do not add a generic wrapper merely to wrap every function.

Avoid:

- `!!`, unchecked casts, platform types leaking inward;
- public setters/mutable collections;
- `lateinit` outside narrow lifecycle/test cases;
- `object`/companion mutable state for sessions or data;
- service locator access from arbitrary code (in HomeDJ there is no composition root: framework-created components such as `AndroidViewModel`s, Workers, and Services build thin owners from application context, e.g. `ModeStore(context)`, `RemotePrefs(context)`, `SavedPlaylistStore.shared(context)`, or call process `object` owners such as `OfflineAudioLibrary`. That is the sanctioned shape. Do not add a DI framework/Application class, construct a second instance of a shared store/player/session, or reach those owners from composables);
- generic `Utils`, `Manager`, `Helper`, or `Base*` classes with unrelated responsibilities;
- hidden blocking/side effects in properties, equality, constructors, mappers, and logging;
- nullable booleans or multiple booleans representing exclusive states;
- stringly typed protocols where a sealed/enum/value type is viable;
- premature abstractions for one implementation and one caller.

## Models and mapping

Use distinct models when contracts differ:

| Role | Concerns |
|---|---|
| Wire DTO | serialization names, optional/unknown fields, API compatibility |
| Entity | schema, indexes, migrations, local IDs, sync metadata |
| Domain | business meaning and invariants |
| UI model/state | localized/formatted display and interaction state |

Do not split models mechanically when all boundaries are genuinely identical and stable. Do split before annotations, nullability, timestamps, enums, IDs, or security-sensitive fields leak across layers.

Mapping rules:

- resolve unknown enum values and malformed optional fields at the boundary;
- do not silently invent domain values from missing required data;
- define time zone/clock conversion once and test edge cases;
- preserve local, remote, temporary, and idempotency IDs as distinct concepts;
- format localized display strings at the presentation/UI boundary;
- do not expose secrets in UI/debug representations or generated `toString` output.

## Modules and packages

- Preserve the current modularization strategy.
- Add a module only for a clear ownership/build/reuse boundary with acceptable dependency cost.
- Prevent cycles and feature-private imports.
- App/wiring modules may compose feature contracts; data modules must not depend on presentation.
- Avoid forced “feature/data/domain/presentation” directories in small modules that do not benefit.
- Prefer cohesive packages over horizontal dumping grounds.
- Keep public API surface minimal and module internals `internal` where possible.

## Design-pattern filter

Before introducing a pattern, answer:

1. What concrete duplication, lifecycle problem, policy, or substitution does it solve?
2. Is there more than one real caller/implementation or a foreseeable stable boundary?
3. Does it reduce the number of owners and states, rather than add indirection?
4. Can the same result be achieved by a function, data class, or direct dependency?
5. Is the pattern already present in the repository?

Reject pattern theater: repositories that merely rename a data source, use cases that forward, factories for constructors DI already owns, observers where Flow already provides observation, and base ViewModels that mix unrelated concerns.

## Living project documentation impact

Treat root `project.md` and `architecture.md` as verified, version-controlled implementation contracts. For every non-trivial patch, decide whether each is impacted and record the reason when unchanged.

Update `project.md` in the same change when modules/packages, SDK/toolchain/dependency versions, variants, entry points, build/test/release commands, Room version/schema history, external integrations, or operational constraints change. Update `architecture.md` when an owner/source of truth, dependency or module boundary, DI/composition root, navigation boundary, persistence strategy, lifecycle/scope, concurrency semantics, background-work owner, or policy/enforcement path changes.

Documentation describes executable behavior, not aspirations. If code and docs disagree, inspect the live path and repair stale prose in the same patch. Keep durable decisions, supported versions, owner maps, constraints, and reproducible commands; exclude secrets, signing values, local paths, transient build status, and speculative plans. Documentation never substitutes for tests or runtime evidence.

## Review checklist

```text
[ ] One authoritative truth and mutation owner.
[ ] Dependency direction is inward toward stable business/data contracts.
[ ] UI and platform hosts are thin.
[ ] State is immutable and externally read-only.
[ ] Models are separated only where contracts differ.
[ ] No new global/service-locator/manual-construction path.
[ ] No module cycle, feature-private dependency, or broad package churn.
[ ] Public/serialized/schema/route contracts were preserved or migrated explicitly.
[ ] Abstractions have a demonstrated purpose and tests target the owner.
```


## Deep implementation protocol

### Establish the architecture delta

Before code, write the **before** and **after** owner/dependency map. The after-map should change only the owner required by the task.

| Concern | Before owner | After owner | Why it changes | Contract preserved |
|---|---|---|---|---|
| Truth | | | | |
| Screen state | | | | |
| Mutation | | | | |
| Lifecycle | | | | |
| Cache/source policy | | | | |
| Navigation | | | | |
| Dependency construction | | | | |

Reject a design when two rows point at competing mutable owners or when the proposed owner cannot outlive the operation it owns.

### Dependency audit

For every new dependency edge, confirm:

- the caller depends on a stable abstraction or concrete type for a real reason, not reflexively;
- lower layers do not import UI/navigation/resources merely to format presentation;
- feature modules do not reach through another feature's internals;
- domain/data models do not acquire Android lifecycle or view references;
- a new interface has at least a boundary, alternate implementation, test seam, or module reason;
- a new mapper exists because contracts diverge, not because every layer must have a duplicate model;
- an application-scoped object contains no Activity, Fragment, View, NavController, or short-lived callback.

### Complexity budget

Every added class, layer, generic wrapper, state holder, cache, and background owner must remove more risk or duplication than it introduces. Prefer a direct repository method over a one-method use case; prefer a pure function over a stateless class; prefer an existing module seam over a new module.

## AI-generated code hazards

AI commonly produces code that looks architecturally formal while weakening ownership. Audit specifically for:

- **ceremony injection:** `UseCase`, `Manager`, `Coordinator`, `Provider`, and interface pairs that only forward calls;
- **architecture replacement:** converting the feature to MVI/Clean Architecture/Hilt/Navigation 3 during a local change;
- **duplicate truth:** repository cache plus Room plus ViewModel list, each independently mutated;
- **leaky boundaries:** DTO/entity/UI model reused everywhere although nullability, lifetime, or trust differs;
- **service locator disguise:** companion object singleton, global object, static factory, or `Application` lookup hidden behind a helper;
- **god repository/ViewModel:** unrelated orchestration accumulated because it is an existing injectable owner;
- **premature modularization:** new module with cyclic or implementation dependencies and no build/test boundary value;
- **generic result inflation:** nested `Result<Resource<DataState<T>>>` wrappers that lose cancellation and domain meaning;
- **public-by-default APIs:** visibility widened to make generated tests compile;
- **comment architecture:** comments claim thread safety, single source of truth, or immutability while types do not enforce it.

## Post-change audit

### Structural proof

- Trace one read and one mutation end-to-end from UI to the authoritative source.
- Search for every write to the affected state/data and confirm there is one policy owner.
- Inspect imports and module dependencies for reverse edges.
- Search for direct construction of repository/client/database/worker dependencies outside the existing DI owner.
- Search for new `object`, `companion object`, public mutable collection/Flow, `lateinit` lifecycle reference, and broad `internal/public` exposure.
- Confirm removed abstractions have no remaining callers and new abstractions have a concrete purpose.

### Behavioral proof

Test the business behavior at the highest cheap stable boundary. Include at least one failure path and, where relevant, duplicate action, cancellation, stale data, recreation, and account switch. A test of a mapper alone does not prove repository or screen ownership.

### Evidence record

```text
Owner map changed:
New dependency edges:
New abstractions and why each is necessary:
Contracts intentionally unchanged:
Architecture hazards searched:
Behavior evidence:
Residual coupling/debt:
```
