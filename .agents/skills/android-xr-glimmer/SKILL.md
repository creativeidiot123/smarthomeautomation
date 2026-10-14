---
name: android-xr-glimmer
description: "Use only for Android XR or Compose Glimmer: spatial/projected UI, XR capabilities, input, permissions, lifecycle/resources, thermal limits, hardware behavior/testing. Verify current APIs."
---

# Android XR and Glimmer

## Compatibility gate

XR and Glimmer APIs may be experimental or release-sensitive. Inspect compile/target SDK, installed artifacts, opt-ins, device capability checks, manifest declarations, and official current documentation before writing API names. Never upgrade the project or add experimental dependencies merely to match a sample.

Identify the surface:

```text
Projected display / spatial panel / immersive content / companion control
Session and capability owner:
Lifecycle and resource owner:
Input modes: focus / touchpad / gesture / controller / voice
Fallback when hardware or capability is absent:
Permission and privacy boundary:
Rendering/performance budget:
```

## Glimmer projected-display UI

- Use Glimmer components/theme only inside the Glimmer surface supported by the installed library. Do not mix Material components/tokens into a projected-display surface unless the API explicitly supports it.
- Favor one primary piece of information or action, minimal hierarchy, short readable text, and strong focus indication. Projected displays are not phone screens floating in space.
- Preserve the display's optical constraints. A dark/black root, high legibility, restrained color, and bottom/comfortable placement may be appropriate, but use the product/device guidance rather than universal hardcoded values.
- Avoid thin type, low contrast, dense lists, edge-clipped controls, and continuous decorative animation.
- Make focus order deterministic and ensure every action works with the actual supported input mode. Do not assume touch.
- Use installed Glimmer spacing, depth, shape, and interaction tokens. Do not substitute Material elevation semantics mechanically.
- A launch affordance, card stack, list transformation, or floating action is a product decision, not a mandatory template.

## XR lifecycle and resources

- Create and retain session/runtime objects only at the lifecycle owner specified by the installed APIs. Close sessions, renderers, listeners, surfaces, image resources, and native handles deterministically.
- Keep Activity/Composable references out of app-scoped XR services. Bridge callbacks into lifecycle-safe state with cancellation.
- Re-check capabilities on resume/reconnect where hardware can disappear or change.
- Handle denied permissions, unavailable sensors/cameras, thermal throttling, focus loss, session interruption, and process recreation.
- Keep expensive decoding, geometry work, or model preparation off the main thread; confine rendering mutations to the required thread.
- Avoid allocations and state writes in high-frequency frame callbacks. Sample/aggregate telemetry rather than logging each frame.

## Input, safety, and privacy

- Validate focus, back/dismiss, accidental activation, repeated input, and confirmation for destructive or costly actions.
- Never imply a control is selected solely by subtle color. Provide visible focus/selection semantics.
- Request only required spatial/camera/audio permissions at the point of use. Explain purpose and provide a non-XR fallback where the product supports one.
- Treat camera, environment, gaze, hand, spatial-map, and microphone data as sensitive. Minimize retention/transmission and never log raw sensitive streams.
- Do not expose hidden activities, broad intent filters, or exported components to make device launching easier.

## Architecture

- UI renders typed XR state and sends actions. A ViewModel/controller may coordinate screen/session state, while a repository/service owns persistent data or external device/runtime boundaries.
- Keep SDK types at the integration boundary when they are unstable, thread-affine, or difficult to test.
- Persist only durable product state, not native/session handles.
- Make commands idempotent or gated when rapid input can launch duplicate sessions/actions.

## Anti-patterns

Reject:

- hardcoded upgrade to an alpha SDK;
- Material phone UI copied into Glimmer;
- assuming a capability because the build compiled;
- retaining Activity/Composable or native handles beyond their lifecycle;
- frame-loop I/O, logging, allocation, or repository mutation;
- invisible focus and gesture-only critical actions;
- raw camera/gaze/environment data in logs;
- no fallback for unavailable/disconnected hardware;
- sample-only manifest/exported settings copied into production.

## Verification

Verify compile and opt-in compatibility, capability fallback, session interruption/recreation, all supported input modes, focus/semantics, large text/contrast, thermal/frame behavior, resource release, permission denial, and real hardware behavior for optics and input. Mark emulator-only verification as incomplete when hardware determines correctness.


## Deep implementation protocol

### Compatibility and fallback contract

Identify required XR/Glimmer runtime, device capability, projected/display context, input methods, spatial/window lifecycle, permission/privacy implications, thermal/performance budget, and non-XR fallback. Do not make core app functionality depend on unsupported hardware unless the product requires it.

### Interaction ownership

Use XR/Glimmer design primitives and focus/input semantics appropriate to the target. Keep application truth and mutations in existing ViewModel/repository owners; the spatial/projected UI is a presentation surface, not a separate business architecture.

### Safety and privacy

Minimize sensor/environment/pose data, avoid retaining or transmitting it without explicit policy, and ensure content placement/readability does not obstruct safety-critical real-world awareness. Provide deterministic exit/recovery and avoid surprise immersive transitions.

## AI-generated code hazards

- assuming experimental/current APIs exist in the repository version without compatibility check.
- mixing Material phone assumptions with Glimmer/projected display components.
- hard dependency on XR classes loaded on unsupported devices, causing startup/class-loading failure.
- spatial UI creates a second state owner or bypasses navigation/security policy.
- gaze/gesture/controller input treated as ordinary click with no focus, dwell, cancellation, or accidental-activation protection.
- infinite animation/heavy rendering ignores thermal/battery/frame budget.
- environment/pose/sensor data logged or persisted without need.
- lifecycle/resource session not closed on pause/device removal/window change.
- only simulator/happy path checked; fallback and loss-of-capability omitted.

## Post-change audit

### XR matrix

- supported device/runtime and unsupported-device fallback;
- capability absent/revoked/lost during session;
- controller, gaze, gesture, keyboard/alternate input as applicable;
- focus visibility, accidental activation, cancel/back/exit;
- lifecycle pause/resume, window/session recreation, device disconnect;
- text/readability/contrast at intended distance and lighting;
- performance/thermal over representative duration;
- privacy/log/storage review for spatial and sensor data;
- parity of business state/navigation/security with non-XR surface.

### Evidence record

```text
Runtime/capability gate:
Fallback behavior:
Input/focus/safety result:
Lifecycle/resource cleanup:
Business-state parity:
Performance/thermal evidence:
Spatial-data privacy audit:
Hardware/simulator limitation:
```
