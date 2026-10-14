---
name: android-platform-camera-media
description: "Use for Android platform/hardware integrations: CameraX, Media3, audio/video, notifications, foreground services, PiP, pickers/sharing, sensors, permissions, callbacks/resource lifecycle."
---

# Camera, Media, Notifications, Graphics, and Internationalization

## Platform-first audit

Identify API levels, target SDK behavior, device capabilities, permissions, lifecycle owner, background restrictions, storage/URI policy, current library versions, and fallback UX. Hardware and OEM behavior vary; do not assume emulator or one device represents all devices.

## CameraX and camera

Prefer lifecycle-aware CameraX when the project uses/supports it. Preserve Camera2/raw path when specialized control requires it and migration is not requested.

### Use-case ownership

- bind Preview/ImageCapture/ImageAnalysis/VideoCapture to LifecycleOwner through ProcessCameraProvider;
- camera screen/host owns binding and surface; ViewModel owns business state, not Camera/View/Context;
- unbind/rebind deliberately when lens/use-case/config changes;
- handle provider/camera unavailable, permission revocation, backgrounding, interruption, concurrent camera limits.

### Preview and Compose

- use the API supported by installed CameraX version (PreviewView interop or Compose viewfinder);
- do not declare AndroidView inherently wrong; it is a valid compatibility path when lifecycle/measurement/update are correct;
- account for aspect ratio, crop, rotation, mirroring, display resize, fold/window changes;
- tap-to-focus uses metering-point factory/coordinate transform APIs;
- show focus/capture/progress/error state without blocking preview unnecessarily.

### Analysis

- choose backpressure strategy (often keep only latest for real-time analysis);
- run analysis off main on bounded executor/dispatcher;
- always close `ImageProxy` in `finally`, including error/cancellation paths;
- avoid copying full frames when plane/buffer access suffices;
- account for image format, rotation, crop rect, lens mirroring;
- throttle/debounce expensive ML/QR work and avoid duplicate results;
- stop analyzer/release resources with lifecycle;
- do not retain frame/Bitmap beyond ownership without copying safely.

### Capture/storage

- model capture request/in-flight/success/error/cancel;
- prevent double capture and filename collision;
- use MediaStore/scoped storage/FileProvider according to destination;
- preserve EXIF orientation/location privacy policy;
- close streams and handle low storage/permission/IO failure;
- do not expose raw filesystem paths;
- test rapid capture, rotation, app background, process death after pending save, front/back switch.

### ML Kit

- use current supported detector/client and close it;
- match input image rotation and lifecycle;
- avoid processing every frame when detector is busy;
- handle model download/unavailable states;
- keep biometric/face/image data privacy and retention explicit;
- do not treat client detection as identity verification.

## Media3/audio/video

Preserve existing Media3/ExoPlayer/service architecture.

- player has one owner/lifecycle and is released;
- UI observes player state; ViewModel does not retain Surface/View/Activity;
- background playback uses the supported media session/service pattern and policy-compliant notification;
- manage audio focus, noisy route, headset/Bluetooth, interruptions, duck/pause, and resume policy;
- handle buffering/error/retry/end/live state;
- avoid autoplay with sound without product/user expectation;
- DRM/license/security handled by supported APIs; never log licenses/tokens;
- preloading/cache is bounded and respects metered/storage/account/content policy;
- lifecycle/PiP/background transitions tested;
- subtitles/captions, accessibility, playback speed, controls, and orientation considered.

Recording:

- request mic/camera at point of use;
- show clear recording state and foreground indication where required;
- stop/release on lifecycle/error;
- handle interruption, low storage, duration/size limits;
- store/share through safe URI;
- protect sensitive recordings and consent.

## Notifications

### Channels and permission

- create stable channels with user-meaningful name/description/importance;
- channel importance cannot be silently changed after creation; version/new channel only with product rationale;
- request notification permission at a contextual time where required;
- app still functions/degrades when denied;
- do not repeatedly prompt.

### Content and actions

- privacy-conscious lock-screen visibility and content;
- small icon/color/category/grouping/priority appropriate;
- PendingIntent explicit, immutable by default, unique where identity matters;
- destination/action revalidates auth and current object state;
- actions are idempotent and handle replay/duplicate taps;
- update/cancel deterministic by stable notification ID;
- deep links/back stack use supported stack builder/navigation integration;
- avoid notification spam; group/summarize/rate-limit.

### Foreground services

- use only for allowed ongoing user-visible work;
- declare correct service type/permission and start timing for target SDK;
- post notification immediately within platform deadline;
- stop service/foreground when work ends;
- do not start from restricted background path without supported exemption;
- WorkManager foreground mode used only when durable worker needs it;
- handle user stop/revocation and system restrictions.

### Media/PiP

- MediaStyle session actions reflect real player state;
- PiP actions are safe and update;
- enter PiP only when appropriate and preserve aspect ratio/source rect;
- do not expose private media/title on lock screen without policy.

## File/media selection and sharing

- prefer system Photo Picker/document picker/SAF over broad storage permission;
- handle canceled/missing provider and persisted URI grants;
- validate MIME/size/content and close streams;
- copy to app storage only if continued access is required and policy permits;
- use FileProvider/content URI with temporary grants for sharing;
- Sharesheet intent has explicit type/ClipData/grants and no private extras;
- do not trust file extension or convert content URI to unsafe raw path;
- clean temporary files with an owner/policy.

## Images and graphics

- request/decode at target size off main;
- handle EXIF, color space, density, wide color/HDR only as required;
- reuse/bound caches; avoid full-resolution bitmaps for thumbnails;
- vector/adaptive icons obey safe zone/mask/monochrome requirements for supported launchers;
- custom Canvas allocates/cache objects carefully and respects density/layout direction;
- animations/drawables released/stopped with lifecycle;
- screenshots/recents and image cache consider sensitive content;
- accessibility alternative exists for meaningful graphics/charts.

## Sensors and location

- verify hardware/capability/provider availability;
- register/unregister at lifecycle owner;
- choose sampling rate/batching based on need and battery;
- process off main if heavy;
- permissions/background access minimized;
- location accuracy/age/mock/provider/error represented;
- do not treat last known as fresh;
- avoid permanent listeners in singleton without explicit app feature;
- sensor data privacy/retention/analytics controlled.

## Internationalization

- all user-visible text/content descriptions/errors in resources/localization system;
- use plurals for quantity and locale formatters for number/date/time/currency/list;
- avoid concatenation and English word order;
- use start/end layout and bidi-safe formatting for mixed-direction identifiers;
- preserve protocol/storage formatting with stable locale (`Locale.ROOT`) where needed;
- handle time zone/calendar/12-24 hour correctly;
- test RTL, pseudolocale, long translations, large font, CJK line breaking;
- translatable=false only for true brand/protocol strings;
- server text is not assumed localized/trusted;
- notification/channel/widget strings also localized.

## Platform API versioning

- guard APIs by SDK/version/capability;
- use AndroidX compatibility API when existing/appropriate;
- do not suppress NewApi without a real guard/minSdk proof;
- account for target SDK behavior changes separately from runtime API;
- use current official docs/source for niche/version-sensitive APIs;
- do not hardcode A’s future API 37 or alpha library requirement unless explicitly targeted.

## Testing

- fake platform interfaces for ViewModel/domain;
- instrumentation/device tests for permissions, camera/media lifecycle, notifications/PendingIntent, URI grants, PiP/FGS as needed;
- camera analyzer tests use fixture images and ensure close path;
- media tests cover focus/noisy/buffer/error/background;
- notification tests inspect channel/content/action privacy;
- i18n tests/pseudolocale/RTL screenshots;
- test unavailable hardware and denial, not only happy path.

## Anti-patterns

- camera manually opened/closed in lifecycle callbacks while CameraX binding also owns it;
- `ImageProxy` not closed;
- analyzer queue unbounded/main-thread;
- full-resolution bitmap thumbnail;
- player/camera/recorder retained by ViewModel/singleton;
- notification action opens protected content without auth;
- mutable implicit PendingIntent;
- broad storage permission instead of picker;
- raw file paths/shared world-readable file;
- FGS without correct type/notification/stop;
- sensor listener never unregistered;
- English concatenation/date/currency interpolation;
- API availability assumed from compileSdk;
- client-side face detection treated as identity proof.


## Deep implementation protocol

### Platform contract matrix

For each affected API, identify minimum/target/current API behavior, required permission, lifecycle owner, foreground/background restriction, resource ownership, callback thread, process-death behavior, OEM variance, and fallback/degraded UX.

### Resource and callback ownership

Camera frames, analyzers, codecs, players, cursors, file descriptors, streams, sensors, listeners, receivers, and callbacks must have one close/unregister owner. Every success, failure, cancellation, rebind, and lifecycle-stop path must release them.

### User-visible delivery contract

Notifications/media/camera/file/location changes require explicit handling of permission denial, no suitable handler/app, storage failure, interruption, rotation, background restriction, and stale callback after the UI owner is gone.

## AI-generated code hazards

- assuming one API-level behavior across all supported devices.
- requesting broad storage/location/notification permission when a picker or scoped API suffices.
- camera analyzer forgets to close `ImageProxy` on one branch.
- binding/rebinding CameraX repeatedly from recomposition or retaining preview/view references.
- blocking image analysis on main or using unbounded analyzer backlog.
- Media3/player/callback not released with owner lifecycle, or audio focus/noisy route ignored.
- notification channel recreated with expectation that immutable user settings will change.
- foreground service started without valid type, notification timing, or user-initiated policy.
- PendingIntent/request code collisions or wrong mutability.
- raw file paths shared instead of content URI grants.
- localization built by string concatenation, wrong plural/date/time/RTL handling.
- callback updates stale screen after rotation/navigation.
- only emulator/latest API tested for hardware/OEM-sensitive code.

## Post-change audit

### Scenario matrix

Test applicable combinations:

- minimum, representative middle, and target/current API;
- permission granted, denied, permanently denied, revoked in settings;
- rotate/recreate, background/foreground, navigate away during callback;
- hardware unavailable/busy, capture/analysis failure, low storage;
- incoming call/audio route change/headphones unplugged for media;
- notification disabled/channel altered/app killed/action tapped;
- picker cancelled/provider returns unusual URI/large file;
- locale, RTL, 12/24-hour, plural, font scale, and time-zone change.

### Resource proof

Use logs/tests/profiling as appropriate to prove listeners/resources are closed once, no duplicate binding exists, no frame backlog grows, and stale callbacks are ignored. Confirm permission and manifest declarations are minimal and merged as expected.

### Evidence record

```text
API/device matrix exercised:
Permission/degraded behavior:
Lifecycle/stale-callback behavior:
Resource close/unregister proof:
Background/foreground restriction check:
Notification/media/camera edge cases:
Localization/RTL check:
Hardware/OEM risk not covered:
```
