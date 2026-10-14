---
name: android-accessibility-adaptive-ui
description: "Use for Android accessibility or adaptive UI: TalkBack, focus/keyboard, touch targets, font scale, RTL/localization, edge-to-edge/insets, responsive layouts. Not for state/data ownership."
---

# UX, Accessibility, Adaptive UI, and Edge-to-Edge

## Preserve the product system

Inspect the app’s design system, Material generation, typography, spacing, component library, navigation patterns, and target devices. Do not overwrite brand tokens with stock Material values or force experimental APIs.

The goal is usable, accessible, adaptive behavior—not merely pixel similarity.

## Interaction

- Interactive targets should normally provide at least the platform-recommended effective touch area (commonly 48dp on phone/tablet); preserve wearable/XR-specific guidance in their skills.
- A smaller visual icon can have a larger hit target.
- Adjacent targets need enough separation to prevent mis-taps.
- Every action provides timely visual/state feedback; do not suppress indication/ripple without an intentional replacement.
- Avoid gesture-only functionality; provide a visible/accessibility action.
- Resolve gesture conflicts with system back/home/notification gestures and nested scrolling.
- Destructive/irreversible actions require confirmation or an undo policy according to product risk.
- Loading disables only actions that truly cannot proceed; do not freeze unrelated UI.
- Rapid taps must not duplicate navigation or mutation.
- Haptics are meaningful, not noisy, and respect system settings/APIs.

## Accessibility semantics

Determine whether an element is:

- decorative: no separate announcement;
- informative: meaningful localized description;
- interactive: role, label, state, action;
- grouped: merge descendants when one conceptual control;
- custom: expose semantics equivalent to the visual interaction.

Rules:

- `contentDescription = null` is correct for decorative/redundant imagery; it is wrong for actionable/informative icon-only controls.
- Do not make both container and child announce the same text.
- Use state descriptions for checked/expanded/progress/custom state.
- Headings, pane titles, traversal order, live regions, and errors are applied only where they improve navigation.
- Clear semantics only when replacing them with a complete accessible alternative.
- Custom gestures have custom accessibility actions.
- Do not rely on color alone to convey state.
- Test TalkBack/Voice Access/switch access or the relevant automated semantics checks for critical flows.

## Text and visual readability

- Use `sp` and allow system font scaling; avoid clamping important text to fixed physical size.
- Support large font without clipping, overlap, missing controls, or horizontal scrolling.
- Use the design system’s semantic typography/color roles.
- Meet project/WCAG contrast requirements, including disabled/error/focus states and dark mode.
- Avoid ultra-thin text and low-opacity critical content.
- Use line limits/ellipsis only when information loss is acceptable and full content remains accessible.
- Do not encode critical text into images.

## Forms and input

- Labels remain visible or recoverable; placeholder is not the only label.
- Use correct keyboard type, IME action, capitalization, autofill/content type, and secure input behavior.
- Validation indicates field and summary/error action when needed; do not show raw backend text.
- Preserve cursor/selection and user input across harmless recomposition/configuration.
- Avoid validation on every keystroke when it causes distracting error churn; distinguish syntactic feedback from submit validation.
- Focus order is logical; move focus/keyboard only from explicit events/effects.
- Password/PIN/financial/health fields avoid screenshots/clipboard/autofill as required by threat model.
- Confirm or recover from destructive form exit according to unsaved-data policy.

## Navigation UX

- Choose top-level navigation by destination count, importance, and window size; preserve the app’s established pattern.
- Back always has a coherent destination; do not trap the user.
- Predictive back/gesture behavior remains consistent.
- Dialogs/sheets expose dismiss behavior appropriate to risk; do not make an irreversible form accidentally dismissible.
- Deep navigation restores title/context and state.
- Expanded list-detail/supporting-pane layouts remove redundant back arrows where both panes are visible and restore them when compact.
- Multiple back stacks preserve expected state.

## Edge-to-edge and insets

Inspect current target SDK, Activity setup, Scaffold/container ownership, and existing helpers.

- Use `enableEdgeToEdge`/platform APIs appropriate to installed versions; do not add deprecated system-UI controller dependencies.
- Apply insets at one deliberate boundary. Avoid double-padding from Activity + Scaffold + child.
- Do not hardcode status/navigation bar heights.
- Account for status bars, navigation bars, display cutouts, caption bars, gesture exclusion, and IME where relevant.
- Different UI regions may consume different insets; do not blindly apply `safeDrawing` everywhere.
- Test three-button and gesture navigation, portrait/landscape, cutout, transparent bars, light/dark icons, and keyboard.
- Scroll content may extend behind bars while controls receive safe padding.
- Do not consume insets before descendants that still need them.

## Adaptive and large-screen layout

Use actual available window size/posture/input capability—not phone/tablet model checks.

- Preserve state during resize, multi-window, fold/unfold, desktop window change.
- Avoid fixed phone widths and forced portrait unless product/hardware requires it.
- Choose one-pane, list-detail, supporting pane, grid/flex/wrap, navigation bar/rail/drawer according to available width and content.
- Critical content/actions never cross a hinge/occlusion.
- Detail full-screen behavior on compact and pane behavior on expanded are both tested.
- Mouse hover, keyboard navigation, focus, scroll wheel, and right-click/context behavior are considered on desktop/ChromeOS/tablet where supported.
- Avoid making all content stretch to unreadable line lengths; use max widths and alignment from design system.
- Screenshot/previews cover compact, medium, expanded, portrait/landscape, fold posture, and large text as relevant.

Do not require Navigation 3, experimental MediaQuery/Grid/FlexBox, or a specific adaptive library merely to support large screens. Use the stack already installed; adopt experimental APIs only by explicit request with opt-in/version review.

## Localization and RTL

- All user-visible strings use resources/localization system, including accessibility labels and errors.
- Avoid concatenation and English word-order assumptions; use formatted resources.
- Use plurals for quantities.
- Use locale-aware date/time/number/currency/list formatting.
- Do not use locale-sensitive formatting for protocol/storage IDs.
- Use start/end rather than left/right for directional layout, except physical direction concepts.
- Mirror directional icons when semantics require it; do not mirror logos/media controls blindly.
- Test pseudolocale, long German-like text, RTL, CJK, and plural edge cases when relevant.

## Theme and design tokens

- Use semantic color roles, typography, shapes, spacing, elevation/depth from existing theme.
- Dynamic color is a product choice; maintain brand/readability fallback.
- When nesting/overriding a color scheme, derive from the outer scheme rather than reconstructing incomplete tokens.
- Avoid deprecated roles when the project has migrated, but do not churn unaffected code solely for nomenclature.
- Keep contrast/disabled/error/focus/pressed states coherent.
- Custom components expose a consistent style API only when reuse justifies it.
- Experimental Compose Styles API requires explicit user request, compatible alpha versions/SDK, opt-in, screenshot coverage, and isolation. Never migrate stable components to it incidentally.

## Motion

- Use motion to explain state/spatial relationship, not decorate every action.
- Animations are interruptible and preserve input.
- Avoid long sequential animations before content becomes usable.
- Respect system animator scale/reduced motion where applicable.
- Avoid flashing, excessive parallax, or motion that causes vestibular/accessibility problems.
- Progress indicators represent determinate/indeterminate state honestly.

## UX audit rubric

Review:

1. hierarchy and primary action;
2. touch/gesture feedback;
3. navigation/back/dismiss;
4. loading/empty/error/offline/disabled;
5. semantics and screen-reader order;
6. contrast/font scale/localization/RTL;
7. keyboard/focus/IME/autofill;
8. edge-to-edge/insets;
9. adaptive/fold/multi-window/input devices;
10. design token consistency and motion.

Report issues by severity and user impact. Distinguish factual accessibility/platform defects from subjective design preferences.

## Anti-patterns

- tiny icon-only target with no expanded hit area/label;
- interactive icon with null semantics;
- every image given a redundant description;
- color-only error/state;
- hardcoded English/concatenated strings/currency interpolation;
- fixed dp text or clipped large font;
- fixed status-bar padding/double insets;
- phone-only fixed width on large screens;
- content over fold hinge;
- gesture-only destructive action;
- disabled ripple/feedback without replacement;
- Material defaults replacing established brand system;
- experimental adaptive/styles API forced into stable app;
- screenshot baseline updated to hide unintended regression.


## Deep implementation protocol

### Interaction-state matrix

For each interactive surface, inspect enabled/disabled, focused, pressed, selected, loading, error, empty, offline/stale, keyboard open, large font, screen reader, switch/keyboard/rotary input where applicable, compact/expanded window, landscape, RTL, and edge-to-edge/system-bar overlap.

Accessibility is part of the component contract, not a final content description. Define role, accessible name, state/value, action, traversal/grouping, live-region behavior, focus restoration, touch target, and non-color cue.

### Adaptive ownership

Adapt layout from available window/posture and content constraints, not device-name checks. Preserve one navigation/state owner across panes; do not duplicate ViewModels or mutation paths when a detail pane appears.

### Input and error behavior

Forms need stable field identity, keyboard/IME actions, validation timing, error association, focus movement, autofill/privacy policy, state restoration, and submission duplicate protection. Errors must explain recovery without exposing internals.

## AI-generated code hazards

- adding `contentDescription` to every element, causing duplicate/noisy semantics.
- clickable container with nested clickable controls and ambiguous actions.
- icon-only action without accessible name or adequate target.
- disabled control with no explanation/recovery, or loading control still clickable.
- color alone conveys status; hard-coded colors/sizes ignore theme/contrast/font scale.
- fixed dimensions/absolute positioning clip at 200% font or small window.
- `fillMaxSize`/padding/insets copied blindly, causing system-bar or IME overlap.
- desktop/tablet layout creates second state owner or different business behavior.
- focus requested every recomposition; focus lost on validation/navigation/recreation.
- string concatenation, unlocalized text, wrong plural/date/number, or LTR assumptions.
- animations ignore reduced-motion/accessibility context or block interaction.
- screenshot looks correct at one device while semantics, keyboard, RTL, and resize fail.

## Post-change audit

### Accessibility/UX matrix

Use semantics tests and manual assistive-technology checks as appropriate:

- TalkBack/screen reader traversal, labels, roles, states, actions, announcements;
- keyboard/D-pad/switch/rotary focus order and visible focus;
- 200% font scale and display-size changes;
- contrast and non-color status cues;
- 48dp-equivalent target guidance unless platform/domain intentionally differs;
- IME open/close, next/done, validation, autofill, and back behavior;
- RTL plus long/pseudo-localized strings;
- compact/medium/expanded, portrait/landscape, split-screen, resize;
- edge-to-edge with status/navigation bars, cutouts, gesture insets, and IME;
- loading/empty/error/offline/retry/disabled states and rapid repeated actions.

### Regression proof

Compare semantics tree and key screenshots for intended changes only. Verify analytics/navigation/business actions fire once regardless of input method. Confirm adaptive panes share the same source of truth and back behavior.

### Evidence record

```text
Interaction states exercised:
Semantics/screen-reader result:
Keyboard/focus/IME result:
Font scale/contrast result:
RTL/localization result:
Window-size/insets result:
Duplicate-action/business parity:
Device/accessibility checks not run:
```
