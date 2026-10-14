---
name: android-kmp-compose-multiplatform
description: "Use only for Kotlin/Compose Multiplatform shared code: source sets, platform boundaries, Ktor, shared state/resources/persistence/concurrency/lifecycle/testing. Do not apply to Android-only code."
---

# Kotlin and Compose Multiplatform

## Inspect the actual target matrix

Identify:

- targets and source-set hierarchy;
- Kotlin/Gradle/Compose versions;
- Android application/library modules;
- iOS framework/export integration;
- desktop/web/Wasm targets if any;
- shared UI versus shared domain/data scope;
- DI, lifecycle, navigation, resources, networking, persistence;
- platform engine/driver implementations;
- test source sets and CI targets.

Do not assume every KMP project should share UI, use Ktor, use Room KMP, use SQLDelight, or move all logic to commonMain.

## Source-set ownership

Put code in the narrowest source set that supports all required APIs.

- `commonMain`: platform-neutral business logic, contracts, models, pure Kotlin, shared UI only when CMP is intentional;
- intermediate source sets: shared Apple/JVM/native behavior when real;
- platform source sets: Android Context/lifecycle/permissions, iOS UIKit/Swift interop, desktop windows/files, engine/driver implementations.

Do not import Android classes into commonMain. Avoid platform checks/string reflection when an interface or expect/actual boundary expresses capability.

## Interface versus expect/actual

Prefer interfaces + dependency injection for replaceable services and test fakes:

```kotlin
interface Clock { fun now(): Instant }
interface SecureStore { suspend fun read(key: Key): ByteArray? }
```

Use `expect/actual` for small platform primitives whose API shape is truly common and not runtime-substitutable, such as platform name, low-level factory, or native handle wrapper.

Avoid large expect/actual service classes and duplicated business logic in actual implementations.

## Architecture

Preserve existing layer/module structure. Shared code should not become a “god common module.”

- UI platform owns platform navigation, permissions, Activity/UIViewController/window lifecycle;
- shared ViewModel/presenter/state holder is valid only when lifecycle and dispatcher ownership are explicit for every platform;
- repositories define source strategy and use platform-provided engines/drivers/stores;
- common domain remains free of Android resources/Context/HTTP/DB implementation details;
- platform-specific feature behavior can remain platform-specific instead of creating awkward abstractions.

## Coroutines and lifecycle

- every scope has a platform/owner lifetime and cancellation path;
- do not create `MainScope()`/global scope in shared object without explicit close;
- Android may use `viewModelScope`; iOS/desktop host supplies a lifecycle-bound scope or calls close/cancel;
- expose closeable state holder when owner is not Android ViewModel;
- never block native/main UI threads;
- keep cancellation semantics across Swift callbacks and Ktor requests;
- use current Kotlin/Native memory model; avoid assuming thread-confinement behavior from old frozen model;
- shared mutable state remains synchronized/immutable by design.

`Dispatchers.Main` availability differs by platform/dependency. Do not hardcode without supported main dispatcher. Inject or platform-provide dispatcher only where required.

## StateFlow to non-Kotlin consumers

StateFlow is convenient in Kotlin UI but Swift/Objective-C interop needs an explicit bridge/wrapper.

- define subscription lifetime and cancellation;
- deliver callbacks on expected UI thread;
- avoid exposing coroutine internals/Job types awkwardly to Swift API;
- map sealed errors/state to interop-friendly stable API if exported;
- do not read `.value` from arbitrary native thread without understanding concurrency/API contract;
- prevent multiple collectors on recreation;
- use project’s SKIE/KMP-NativeCoroutines/custom bridge if already adopted; do not add one casually.

## Networking

Ktor is common but not mandatory.

- common code configures shared plugins/content/error mapping;
- platform source sets provide explicit engines;
- Android engine choice fits OkHttp/network config/interceptors already used;
- Darwin engine/session behavior fits iOS requirements;
- close clients at owner lifetime;
- authentication/token storage boundary is platform-safe;
- no Android Context in common client;
- use MockEngine for common tests if existing/justified;
- do not expose Ktor exceptions/DTOs to shared presentation.

If Android-only module uses Retrofit, do not force Ktor unless code must move to common and migration is requested.

## Persistence

Choose existing technology and target support:

- Room KMP where project/version/targets support and Android-style schema fits;
- SQLDelight for multiplatform SQL where already used/appropriate;
- platform stores behind interface for small settings/secure data;
- DataStore may have target limitations/version-specific setup;
- secure storage requires Keychain/Keystore implementations; no plain common preference for secrets.

Define driver/database lifecycle, migrations, thread/coroutine model, file location, encryption, and tests per target. Do not assume Android Room APIs/packages in common source.

## Compose Multiplatform UI

- isolate common UI from platform integrations;
- use multiplatform resources/design system supported by installed version;
- route platform navigation/window/permission/file picker through callbacks/interfaces;
- use platform-specific composables/source-set implementations when API differs;
- test pointer/keyboard/window resize as well as touch;
- account for missing/experimental APIs by target;
- do not call Android `collectAsStateWithLifecycle` in common code; use platform lifecycle wrapper;
- do not force a phone layout onto desktop/tablet.

## Resources and localization

- use project’s Compose Resources or platform resource strategy;
- avoid Android `R` in commonMain;
- preserve plural/locale/RTL behavior;
- platform-specific assets/fonts and bundle packaging verified;
- do not hardcode file paths/classpath assumptions across targets.

## Navigation

Keep platform navigation at host unless project intentionally uses common navigation.

- common state exposes destination intent/route model independent of Android NavController/UIViewController;
- route serialization is stable across platforms if shared;
- deep links/app links/universal links are validated at each platform edge;
- process restoration exists mainly on Android; iOS scene restoration differs—do not pretend one mechanism covers all.

## DI

- common constructors/interfaces are DI-framework-neutral where possible;
- platform graph supplies engine/driver/context/storage;
- Hilt remains Android-specific;
- Koin/manual DI used only if project already chose it or migration requested;
- avoid service locator singleton in common code;
- close owned clients/scopes/drivers.

## Testing

- common tests for pure business/mappers/repository strategy with fakes/MockEngine;
- platform tests for engine, secure storage, DB driver/migration, lifecycle bridges, permissions, resources;
- Android instrumentation/UI tests remain Android source set;
- iOS tests verify exported API and cancellation/thread delivery;
- desktop tests verify window/input/file behavior;
- do not claim common test proves platform integration.

## Build and publication

- inspect Gradle source-set hierarchy and target declarations;
- avoid Android-only AGP migration recipes in KMP without current compatibility check;
- CocoaPods/SPM/framework linkage and binary compatibility are release contracts;
- exported public types must be interop-compatible;
- avoid exposing unsupported generic/sealed/coroutine types unintentionally;
- version catalog/plugins aligned with Kotlin/Compose/AGP/KSP;
- build all affected targets or report which were not available.

## Anti-patterns

- Android Context/NavController/Room Android type in commonMain;
- all code moved to common “for reuse” with awkward abstractions;
- giant expect/actual service;
- shared global MainScope never cancelled;
- Ktor client without platform engine/close;
- Retrofit replaced even though networking is Android-only;
- plain common storage for secrets;
- Android lifecycle collection copied to common UI;
- StateFlow exported to Swift with no cancellation/thread bridge;
- old Kotlin/Native freezing assumptions;
- Android-only AGP/Room/navigation migration applied to KMP blindly;
- only Android target built after common API change.


## Deep implementation protocol

### Target contract matrix

For each supported target, record compiler/source set, UI owner, lifecycle scope, dispatcher/main-thread model, networking engine, persistence implementation, resource system, exported API, and test task. Shared code is justified only when semantics are truly common.

Prefer common interfaces for capabilities with platform implementations. Use `expect/actual` for small platform facts or APIs where it produces a clearer contract; avoid large actual classes that duplicate feature architecture.

### Boundary compatibility

Shared public models/functions must consider Swift/Objective-C export, nullability, generics, sealed hierarchies, exceptions, suspend/Flow bridging, freezing/threading expectations of supported toolchain, serialization compatibility, and binary/publication stability.

### Shared-state discipline

Do not assume Android ViewModel/lifecycle semantics on iOS/desktop. The platform integration owns observation start/stop and resource closure. Hot Flows in shared singleton scope require a deliberate cross-platform lifetime and replay policy.

## AI-generated code hazards

- moving Android-specific behavior to `commonMain` behind broad wrappers without validating other targets.
- adding `expect/actual` for every dependency instead of a narrow capability interface.
- using Android context/resources/navigation types in common code.
- choosing a Ktor engine or SQL driver unavailable on one target.
- exposing raw `Flow`, exceptions, sealed/generic types to Swift without checking generated API usability.
- assuming `Dispatchers.Main`, filesystem paths, clocks, locale, time zone, crypto, or network security behave identically.
- sharing UI state that captures platform lifecycle objects.
- duplicating DI containers per source set with inconsistent scopes.
- compiling Android only and declaring the shared change complete.
- changing serialized shared models without backward compatibility for stored/wire data.
- forcing Compose Multiplatform UI where native UI ownership is an established product constraint.

## Post-change audit

### Cross-target proof

- Compile every affected target/source set, not only `androidMain`.
- Run common tests and at least the platform tests that exercise actual implementations.
- Inspect generated Apple-facing API or use it from a small Swift call site when exports changed.
- Test cancellation and lifecycle stop/start from each platform integration.
- Verify resource lookup, localization, RTL, density/input differences, and navigation restoration on target UIs.
- Verify network engine, TLS, persistence path/migration, and close/disposal semantics per platform.
- Check published metadata/API compatibility when a library artifact changes.

### Evidence record

```text
Targets/source sets affected:
Common versus platform ownership:
Actual implementations exercised:
Apple/exported API check:
Lifecycle/cancellation by target:
Serialization/storage compatibility:
Build/test tasks run per target:
Unsupported/unverified target risk:
```
