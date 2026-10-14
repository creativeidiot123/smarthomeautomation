---
name: android-gradle-di-release
description: "Use for Gradle/AGP/Kotlin/KSP/kapt/build/DI/release config: dependencies, catalogs, modules, variants, signing, CI, R8, build failures or upgrades. Not app-architecture redesign."
---

# Gradle, DI, Modules, and Release

## Inspect first

Read:

- `settings.gradle(.kts)`, plugin management, repositories;
- root and affected module build files;
- version catalog/BOMs;
- convention/build-logic plugins;
- Gradle wrapper, AGP, Kotlin, KSP/kapt, JDK toolchain;
- module dependency graph and source sets;
- DI framework/plugins/processors/scopes;
- flavors/build types/signing;
- R8/resource shrinking/baseline profile/benchmark modules;
- CI and release tasks.

Do not paste a greenfield build template into a brownfield repository.

## Minimal build change

- edit the nearest owner: version catalog for versions/aliases, convention plugin for shared policy, module build file for module-only needs;
- do not duplicate configuration already supplied by a plugin/BOM;
- avoid unrelated dependency upgrades;
- keep plugin/library versions compatible;
- preserve repositories, mirrors, dependency verification, and offline/CI constraints;
- run narrow Gradle configuration/build tasks after each risky step.

## Version catalog and dependencies

- use existing aliases/bundles/BOM patterns;
- pin versions or use approved catalogs—avoid dynamic `+`, snapshots, or unreviewed alpha versions in production;
- BOM controls only artifacts in its domain; do not assume all transitive versions are aligned;
- inspect dependency insight for conflicts before forcing versions;
- prefer `implementation` over `api` unless consumers need the type;
- use `compileOnly`, `runtimeOnly`, test configurations correctly;
- avoid duplicate libraries with overlapping responsibility;
- a dependency must have product/maintenance/security value greater than its size/build/API cost.

## Convention plugins and build logic

Use convention plugins when multiple modules share meaningful configuration. Do not create build-logic machinery for one module.

- use lazy Provider APIs;
- avoid `afterEvaluate`, eager task realization, `allprojects/subprojects` mutation, and configuration-time file/network/process work;
- register tasks, do not eagerly create/configure every task;
- keep plugin IDs/types cohesive;
- keep build logic compatible with configuration cache;
- do not depend on app implementation code;
- test complex build logic if repository has TestKit/plugin tests;
- preserve composite build/includeBuild layout.

## Build performance

Measure configuration and execution separately.

- local profile is privacy-safe default; build scan uploads data and requires authorization;
- test warm incremental and clean/cold scenarios separately;
- apply one optimization at a time;
- configuration cache: fix incompatible task/plugin behavior before enabling globally;
- build cache: ensure inputs/outputs/path sensitivity/environment are declared correctly;
- parallel execution is not a cure for dependency serialization or memory pressure;
- tune JVM heap based on evidence, not maximal values;
- reduce annotation processing/code generation and unnecessary module coupling;
- KSP migration is version/library dependent; do not force when processor support is incomplete;
- avoid `clean` in normal CI/investigation unless testing a clean build or invalid state.

## AGP/Kotlin/Gradle/JDK upgrades

Treat as a dedicated migration.

1. Determine current and target versions and whether project is Android-only or KMP.
2. Check official compatibility matrix/release notes for Gradle, JDK, Kotlin, KSP, Compose compiler/plugin, processors, test tools, and third-party plugins.
3. Make a staged plan and preserve a buildable state.
4. Update wrapper/toolchain/plugins/dependencies in compatible order.
5. Apply required DSL/source-set/built-in Kotlin/kapt/KSP/BuildConfig/resource changes.
6. Remove obsolete flags only after verifying replacements.
7. Build configuration/help, compile affected modules, tests, lint, and release variant.
8. Record deprecations and remaining blockers.

Do not hardcode A’s AGP 9, compileSdk 37, Java 17/21, or alpha APIs as universal defaults. Use the requested target and current official compatibility information. KMP upgrades need KMP-specific guidance; Android-only migration recipes may not apply.

Do not add compatibility escape flags that keep deprecated behavior without an explicit temporary plan. Do not run arbitrary repository-wide scripts when direct edits/checks are safer.

## KSP versus kapt

- preserve existing processor until migration requested/needed;
- confirm every processor supports target KSP/Kotlin/AGP;
- migrate one processor/module set at a time;
- remove kapt plugin/config only after no kapt processors remain;
- compare generated source/package behavior;
- run clean-enough generated-source verification without using `clean` reflexively;
- account for Hilt/Room/Moshi/Dagger/test processor specifics;
- do not mix KSP/kapt aliases incorrectly across source sets.

## DI

Use existing DI: Hilt/Dagger/Koin/manual constructor graph/etc.

General:

- constructor injection for normal dependencies;
- DI module binds interfaces to implementations only at actual graph boundary;
- provider for construction requiring configuration/factory/external object;
- scope matches lifetime;
- qualifiers distinguish same-type dependencies;
- avoid injecting Activity/View into singleton;
- avoid Context leaks—application context only when dependency truly needs it;
- no manual `Retrofit.Builder`, database, repository, or service construction in UI/ViewModel;
- avoid service locator/static component access;
- avoid duplicate bindings and cyclic graph;
- test overrides use existing DI test mechanism;
- assisted injection/SavedStateHandle only when runtime input cannot be normal graph dependency.

Do not claim `@Binds` is always superior to `@Provides`; use the construct that matches interface binding versus object construction and framework/version.

## Modules

Add/split modules only for:

- clear ownership/API boundary;
- independent build/reuse/delivery;
- dependency isolation;
- platform/source-set split;
- meaningful build performance/team boundary.

Avoid feature-to-feature implementation dependency and cycles. App/wiring module composes features. Keep domain/core pure only if project architecture uses that pattern. Excess modules can slow configuration, increase API surface, and obscure navigation/DI.

## BuildConfig, resources, and secrets

- enable generated BuildConfig only where needed;
- BuildConfig/resource values are extractable from APK; never store secrets expecting confidentiality;
- environment endpoints/feature flags are not credentials;
- signing keys/passwords/tokens come from secure CI/local secret mechanism;
- avoid committing local.properties secrets;
- resource values/manifest placeholders vary by flavor through existing pattern;
- validate release manifest merging/exported components/network config.

## Flavors and build types

- keep dimension/fallback/matching rules deliberate;
- prevent production service/key/analytics from debug/test;
- `debuggable`, minification, signing, application ID suffix, and endpoints are explicit;
- avoid combinatorial flavor explosion;
- tests target relevant variants;
- release behavior must not rely on debug-only code/resources.

## R8 and release

Coordinate with performance skill.

- release minification/resource shrinking settings preserved;
- consumer rules owned by libraries;
- narrow app keep rules backed by reflection/JNI/serialization evidence;
- build and smoke-test minified variant;
- archive mapping/native symbols and version metadata;
- retrace with exact mapping;
- verify baseline profiles packaged if used;
- do not set broad `-dontwarn`, `-dontoptimize`, or package keep merely to pass;
- test deep links, DI, serialization, Room, WorkManager, notifications, billing, and dynamic features affected by shrinking.

## CI/CD and Play boundaries

Repository automation may build/sign/test/package, but Console/credential/track/rollout operations require explicit access and user intent.

- use least-privilege service accounts and protected secrets;
- do not print credentials/signing material;
- pin trusted actions/plugins where policy requires;
- cache Gradle safely without leaking credentials;
- pin or centrally control JDK, Android SDK, Gradle wrapper, actions/images, locale and time zone where reproducibility depends on them;
- run deterministic lint/static analysis, meaningful unit/instrumentation/screenshot tests, and a release-like build appropriate to the change;
- cache only declared Gradle/download outputs; never cache signing material, mutable credentials, or machine-specific state;
- preserve versionCode/versionName and branch/tag/release provenance policy;
- associate AAB/APK, test reports, SBOM/provenance where required, mappings and native symbols with the exact commit/version;
- use concurrency cancellation/locks so two release jobs cannot publish the same version or race mutable environments;
- keep flaky-test quarantine explicit, owned and time-bounded; do not retry an unknown failing suite until green and call it stable;
- staged rollout/track upload is not simulated by code changes;
- developer verification/policy declarations often require Console work—state this instead of inventing repo changes.


## Operational readiness and controlled rollout

For changes with material data, migration, authentication, payment, sync, backend-compatibility, startup, or release risk, define before shipping:

```text
Shipping variants/tracks:
Compatibility window and minimum backend/app versions:
Staged rollout criteria:
Signals that indicate success or regression:
Alert/decision owner:
Rollback, feature-disable, or forward-fix path:
Data/schema consequences of rollback:
Mapping/symbol/artifact provenance:
```

- Reuse existing crash, ANR, analytics, logging, feature-flag, and release infrastructure; do not add telemetry or a remote-config dependency speculatively.
- Signals must be bounded, privacy-safe, version-attributable, and targeted to the changed risk. Never log sensitive payloads to improve observability.
- Detect relevant stuck workers/outbox rows, migration failures, sync divergence, auth loops, backend incompatibility, or release-only crashes when the product already has an approved signal path.
- A staged rollout reduces blast radius; it is not proof of correctness. State Console/manual operations that remain outside repository changes.
- Rollback is unsafe when an older binary cannot read new schema/data or when a remote contract has advanced. Define backward/forward compatibility or an explicit forward-fix procedure.
- Temporary flags, compatibility adapters, dual-read/write paths, and diagnostics require an owner and deletion condition.

## Verification sequence

Choose tasks from repository. Typical order after build-system change:

1. Gradle configuration/help or affected task listing;
2. compile/assemble affected debug module/variant;
3. unit tests/lint for affected modules;
4. instrumentation/screenshot/benchmark as relevant;
5. minified release bundle/APK and smoke tests;
6. dependency/build scan/profile only when needed.

Do not claim success because IDE sync would probably work.

## Anti-patterns

- dependency/plugin added directly in random module contrary to catalog/convention;
- forced Hilt/KSP/Navigation/AGP migration in feature patch;
- dynamic dependency versions;
- repository-wide `subprojects` hacks;
- configuration-time I/O/exec;
- `afterEvaluate` for normal task wiring;
- `clean` as default fix;
- version mismatch solved by arbitrary force/exclusion;
- secrets in BuildConfig/resources/manifest;
- singleton holds Activity/View;
- service locator/manual construction alongside DI;
- build scan uploaded without approval;
- release validation omitted after R8/serialization/DI change;
- tests/lint/shrinking disabled globally;
- Console/upload/rollout action implied as completed without performing it.


## Deep implementation protocol

### Effective-build inspection

Do not reason from one Gradle file alone. Inspect the effective plugin/version/dependency/variant graph, convention plugins, build logic, source sets, generated sources, manifests, packaging, and CI command. Determine whether the changed configuration affects all modules, one module, one variant, or publication consumers.

For dependency changes, record:

```text
Requested capability:
Existing dependency that may already provide it:
Selected coordinate/version source:
Transitives/conflicts:
Minimum SDK/API/toolchain impact:
KSP/kapt/generated-code impact:
R8/consumer-rule impact:
License/policy impact:
Removal/rollback path:
```

### DI graph discipline

Confirm binding owner, component/scope lifetime, qualifiers, multibinding key, constructor visibility, and replacement/test binding. A scope annotation is not proof that captured objects share a safe lifetime.

### Release parity

Any change involving reflection, serialization, JNI, resources, manifests, providers, generated code, service loading, navigation arguments, database schema, billing, or SDK initialization must be considered under minification and the shipping variant.

## AI-generated code hazards

- incidental AGP/Kotlin/Gradle/JDK/Compose/KSP/dependency upgrade during a feature fix.
- adding repositories globally, `mavenLocal`, dynamic versions, `+`, snapshots, or unverified plugin portals.
- duplicate dependency declarations outside the version catalog/convention used by the repository.
- `clean` prescribed as a fix without root-cause evidence.
- disabling configuration cache, lint, tests, warnings, shrinking, or resource shrinking globally.
- broad `subprojects/allprojects` mutation that fights convention plugins.
- execution/configuration-time file or network I/O in build scripts.
- kapt/KSP migration without checking all processors and generated APIs.
- manual construction of DI-owned dependencies in ViewModel/UI/worker.
- singleton binding captures Activity/View/context of wrong type.
- broad `-keep class ** { *; }` rules that hide missing metadata or reflection contracts.
- secrets placed in BuildConfig/resources/version catalog and described as secure.
- only debug assembled even though release behavior can differ.
- generated files or lockfiles modified accidentally and left unexplained.

## Post-change audit

### Build matrix

Run the narrowest relevant set and record exact variants:

- affected module compile/unit tests;
- lint/static analysis used by repository;
- KSP/kapt generation where changed;
- configuration-cache reuse when build logic changed;
- dependency insight for conflicts when dependency graph changed;
- debug plus minified/release-like build when runtime shrinking/serialization/reflection can differ;
- connected/instrumentation smoke for SDK/provider/manifest integration where required.

### Graph and artifact checks

- Inspect final dependency graph for duplicate versions and unexpected transitive libraries.
- Inspect merged manifest/resources and generated sources for the affected variant.
- Verify DI graph compile and runtime creation at each scope; test overrides still work.
- Inspect APK/AAB contents or mapping/usage/seeds when packaging/shrinking changed.
- Confirm no secret/debug endpoint/logging flag enters release artifacts.
- Compare build timing with warm runs before claiming performance improvement.

### Evidence record

```text
Effective variants/modules affected:
Dependency/plugin delta:
DI scope/binding proof:
Debug checks:
Release/minified checks:
Merged/generated artifact checks:
Configuration-cache/build-performance evidence:
Unverified variant risk:
```
