---
name: android-wear-os
description: "Use only for Wear OS: Compose for Wear, rotary/round UI, tiles, complications, Data Layer, health, ambient mode, power/sensors, phone-watch coordination."
---

# Wear OS

## Inspect the product surface

Identify whether the work is an app screen, tile, complication, watch face, foreground exercise, background sensor flow, notification, or phone-watch sync. Inspect the installed Wear Compose/Services/Data Layer versions and existing navigation/scaffold conventions before using sample APIs.

Resolve:

```text
Truth and sync owner:
Screen/tile/complication lifecycle:
Phone dependency and offline behavior:
Round/rectangular and supported sizes:
Rotary, touch, crown, swipe/back behavior:
Ambient and always-on behavior:
Sensor/health permissions and retention:
Battery/network constraints:
```

## Wear-first UX

- Use Wear-specific components and design tokens when the project has them. Do not transplant dense phone layouts, phone navigation bars, or long forms onto the watch.
- Design for glanceability: one primary task, short copy, large targets, clear hierarchy, and minimal steps.
- Support round clipping and edge regions. Important text and controls must remain inside safe readable bounds at all supported font scales.
- Preserve rotary input, touch, hardware buttons, focus, swipe-to-dismiss/back, and accessibility traversal. Do not make a gesture the only way to complete a critical action.
- Use the installed scaffold/list APIs; do not force migration to a newer `ScreenScaffold`, `AppScaffold`, or transforming list solely because a sample uses it.
- Keep list identity stable and avoid expensive work in item composition. Account for edge scaling/transform behavior when it is part of the installed component.
- Confirm destructive actions and avoid tiny icon-only controls without semantics.

## Lifecycle, power, and background work

- Sensors, location, heart rate, exercise clients, wake locks, and callbacks have explicit owners and release paths.
- Use foreground services only when platform policy and the user-visible use case require them. Declare the correct service type and notification behavior.
- Avoid permanent polling, unnecessary phone round trips, high-frequency recomposition, and unbounded sensor sampling.
- Define behavior in ambient/always-on mode: reduced updates, burn-in protection where applicable, and no animation/work that should pause.
- Schedule deferrable work with existing durable scheduling and constraints. Do not rely on a screen/view-model scope for work that must survive process death.
- Handle disconnected phone, airplane mode, low battery, permission revocation, app update, and process restart.

## Data Layer and offline behavior

- Define which device owns truth. Use messages for ephemeral commands and synchronized data/assets for state according to the existing protocol; do not assume delivery semantics that the API does not guarantee.
- Give every command/mutation a stable ID when retries could duplicate it. Persist pending durable work if loss is unacceptable.
- Version payloads and tolerate unknown/older fields across staggered phone/watch updates.
- Scope data by account and clear or reconcile on logout, account switch, uninstall/re-pair where applicable.
- Do not send secrets or unnecessary health data through logs or broad data paths. Encrypt/authenticate at the product boundary when threat model requires it.

## Tiles and complications

- Rendering is fast, deterministic, and based on cached/local state. Network fetching does not block a render callback.
- Define freshness, update triggers, stale fallback, tap destination, and empty/error behavior.
- Keep IDs stable so updates replace the intended content.
- Respect platform size, timeline, resource, and frequency limits of the installed APIs.
- Verify preview/editor and real-device behavior; emulator screenshots alone do not prove complications, ambient mode, sensors, or rotary behavior.

## Health and fitness

- Request the minimum permission at the point of use and explain the user benefit.
- Treat health data as sensitive: minimize collection, retention, transmission, and logging; honor deletion/logout/export requirements.
- Model exercise start/pause/resume/end and process disconnection explicitly. Prevent duplicate sessions and data gaps when reconnecting.
- Use monotonic time for durations and wall-clock time only for user-facing timestamps. Test daylight-saving and time-zone changes where sessions cross them.
- Keep sampling, batching, and sync policies explicit and battery-aware.

## Anti-patterns

Reject:

- phone UI scaled down to a watch;
- controls clipped by round screens or unreadable at large font scale;
- screen-scoped ownership of durable exercise/sync work;
- unbounded sensor listeners or wake locks;
- network calls directly in tile/complication rendering;
- assuming phone connectivity or message delivery;
- mutable singleton session/sensor state;
- sensitive health payloads in logs;
- forced adoption of an experimental Wear component without repository/version evidence.

## Verification

Test representative small/large round devices, font scale, rotary and touch input, swipe/back, ambient mode, disconnected phone, process restart, permission denial/revocation, low-connectivity paths, and battery-sensitive behavior. Use physical hardware for sensors, haptics, ambient, and real interaction when those behaviors are material.


## Deep implementation protocol

### Wear product contract

Identify surface (app, tile, complication, notification, foreground exercise), supported shapes/sizes, rotary/touch input, ambient behavior, phone dependency, offline truth, synchronization, battery/thermal budget, health permission, and process/background constraints.

Keep interactions glanceable and resumable. A watch may be disconnected, low power, ambient, or killed frequently; critical state belongs in durable local/server truth, not a phone callback or screen memory.

### Data Layer discipline

Choose message, data item, capability, channel, or independent network based on delivery/durability. Messages are not durable storage. Key payloads by account/device/version, make processing idempotent, and handle duplicate/out-of-order delivery.

## AI-generated code hazards

- phone Compose UI copied unchanged to small round display.
- Material phone components used where Wear-specific behavior is required.
- long lists/text/input flows with no rotary/glanceability review.
- permanent listeners/sensors/network polling draining battery.
- Data Layer message assumed reliable/durable or ordered exactly once.
- watch UI blocked waiting for phone when offline mode should exist.
- tile/complication performs heavy/network work directly or shows stale private data after logout.
- ambient mode ignored; animations/timers continue unnecessarily.
- health/fitness session ownership, permissions, foreground service, or sensor cleanup incomplete.
- only emulator square device tested; round clipping/chin/real rotary omitted.

## Post-change audit

### Wear matrix

- round/square and smallest supported size;
- touch plus rotary/D-pad where applicable;
- ambient enter/exit, screen off/on, process recreation;
- phone connected/disconnected/reconnected and duplicate/out-of-order Data Layer events;
- offline launch and stale/sync conflict;
- low battery/thermal/background restrictions;
- tile/complication refresh, stale data, logout/account switch;
- health permission denied/revoked and exercise interruption;
- large font, TalkBack, target size, and glance duration.

Use battery/performance evidence for continuous sensors, animations, polling, or sync changes. Confirm all listeners/sensors/session resources close with the correct owner.

### Evidence record

```text
Wear surfaces/devices exercised:
Offline/phone-disconnect behavior:
Data Layer durability/idempotency:
Ambient/power result:
Rotary/accessibility/layout result:
Tile/complication freshness/privacy:
Health/session lifecycle:
Real-device limitation:
```
