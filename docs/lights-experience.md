# Lights experience contract

## Product and ownership

Both phones expose Lights, Schedule and Automation within the existing Lights tab.
Samsung's `Lights` remains the only settings/mutation owner and `MatterLights` the only
Matter controller. The OnePlus renders Samsung snapshots and sends authenticated commands.
Compose keeps only navigation and primitive editor drafts. No dependency, service, listener
port, permission, player, database, navigation framework or background polling loop is added.

The UI interactions are independently implemented in Compose, informed by Home Assistant,
Mushroom, Scheduler Card and openHAB. No upstream implementation or assets are copied.
Solar calculations implement NOAA's published fractional-year equations:
https://gml.noaa.gov/grad/solcalc/solareqns.PDF . Solar times are approximate; absent polar
events are skipped and the existing alarm reconsiders them the next day.

## Journeys and acceptance

- A bulb card shows its last acknowledged target or explicit observation. Controls open a
  scrollable editor. Brightness/hue/saturation/white sliders send only when released.
  Failed commands keep the prior acknowledged record. A fade acknowledgement confirms its
  target, not physical completion. Latest change wins: a group change clears its
  members' individual power/brightness/colour and an all-light change clears every bulb's.
- Check light reads one bulb's OnOff, level, color mode and available capabilities. It updates
  only acknowledged/read state, never saved intent. A failed required power read cannot
  produce a fabricated state. It is an explicit dated read, not continuous monitoring or
  an online-status guarantee. Missing optional capabilities remain unknown.
- Scenes have stable IDs, names and fixed bulb/settings maps (at most 16 scenes × 16 bulbs).
  Create/edit does not affect hardware. Apply uses the same individual-intent path as direct
  controls. Each acknowledged bulb is committed separately; partial failures remain visible.
  Capture uses HomeDJ's recorded settings, labelled accordingly. Unknown settings must not
  be fabricated. Deleting a scene confirms deletion of its schedules and retains bulb settings.
- A schedule can target all lights, a current group, selected bulbs or a saved scene.
  Name, enable/disable, duplicate-to-draft, Run now, weekday shortcuts, time selection,
  upcoming list and day-by-day weekly agenda use the same Samsung rules (maximum 20).
  Duplicate does nothing until Save. Independent rule edits cannot replace unrelated rules.
- Clock rules use Samsung's zone, including DST. Solar rules use an explicitly entered
  home latitude/longitude, optional ±120-minute offset, no GPS permission/network lookup.
  Coordinates are private app settings included only in the existing paired snapshot.
  Location removal is blocked while any solar rule uses it. Group deletion removes only its
  own rules. Missing scene/group targets never become all lights.
- New/edited/re-enabled rules start strictly after Save. Existing rules still run only once,
  in the existing all/group/individual order, within the existing 15-minute lateness window.
  An alarm records its checkpoint before applying; crash/cancellation does not retry a partial
  physical operation. Enabling/editing another rule must not erase existing due work.
- Bulb-native fades are 0–3600 seconds. Off with a fade uses MoveToLevelWithOnOff to zero;
  the phone does not run per-frame commands. Hardware may vary in supported timing/behavior.
- Evening, Bedtime and Wake up templates open ordinary schedule drafts. The optional
  Only if Home condition requires a recent authenticated Home vote (≤3 minutes) and the
  running Samsung listener. BLE proximity alone cannot satisfy it. Unknown/away is skipped,
  with no deferred retry. Existing presence off/restore policy remains independently controlled.
- Recent schedule results retain at most 12 records, including explicit skips and failures.
  Run now respects conditions but does not enable or alter a schedule's next occurrence.

- Variation makes lights feel alive: one switch with 0–100 % Hue/Saturation/Brightness sliders
  (hue means warmth for white light; 0 % keeps it still, at least one stays above 0) and a Speed
  slider, sent once on release, in All lights, each
  light's controls (own value or "follows all lights"), each scene and each light inside a scene.
  The all-light value clears per-light values; a scene sets its lights' values when applied.
  Steps vary only bulbs recorded as on with known settings and supported capabilities, never
  switch power, never fabricate a value and are never saved; turning variation off restores the
  recorded settings once. A running fade suspends variation for that bulb until it ends. It runs
  on Samsung only while its remote listener service is on; stopping it leaves lights at their
  last step. Unreachable bulbs back off up to 8 minutes. Each step walks on from the last (at most
  half the range), so the bulb's transitions join into a smooth drift. Matter traffic is one short
  command pair per varied bulb every ~2 s (fastest) to ~20 s (slowest).
  Groups have the same switch in their control sheet: a light follows its own value, else its
  group's, else all lights'. Setting a group's variation resets its lights' own values;
  joining a group does too.

## Lifecycle, failures and compatibility

ViewModel taps are first-wins; owner mutations and Matter operations are serialized by their
existing mutexes. Role is rechecked at execution and between bulb commands. Leaving an editor
does not invent a completed write. A LAN write timeout remains an unknown outcome followed by
a snapshot refresh, with no automatic mutation retry. Disk commits precede publication.
The existing exact/inexact alarm, receiver and unique LightsWorker paths survive screens,
boot and clock changes. No new WorkManager names or input keys are used.

Persistence is additive in `homedj_lights`: new optional `experience` JSON stores scenes,
location, bounded history and zone. `automation.rules` gains optional `name`, `enabled`,
`time`, `offset`, `sceneId`, `activeAfter`, `onlyHome`; changes gain optional `fadeSeconds`.
Bulbs gain optional dated `reading`. Old records decode with their prior semantics.
Legacy Controllers keep their original commands; old whole-automation writes preserve advanced
fields for existing rule IDs. New rule edits use distinct commands, so older Servers return
Unknown command instead of silently interpreting an advanced rule as a fixed-time rule.
Advanced features require matching updated builds; downgrading Samsung is not supported for
their execution because older binaries cannot enforce their conditions or solar semantics.

New commands: `lightsSceneSave`, `lightsSceneDelete`, `lightsSceneApply`, `lightsRuleSave`,
`lightsRuleDelete`, `lightsRuleEnabled`, `lightsRuleRun`, `lightsLocation`, `lightsPresence`,
`lightsRead`. Optional reply `lightsVersion=1`; snapshots gain optional `experience`.
Variation adds `lightsVariation` (integer `variation`, optional `id`, `clear`), optional reply
`lightsVariation: true`, `experience.variation` and scene `variation`/entry `v` 6-bit integers;
slider amounts other than the old presets add the full integer under the key + `2` (older builds
ignore it and use the 6-bit value). LAN frames may now be up to 131,072 bytes.
Juggle adds scene `j` (seconds: 0 off, 10..3600; absent from older Controllers keeps the saved
value), `experience.variation.juggle` (seconds) with per-light `j: 1` on that light's own `individual`
entry, and optional reply `lightsJuggle: true`, which gates the OnePlus control.
Group variation adds `lightsGroupVariation` (`groupId`, integer `variation` or `clear`), optional
reply `lightsGroupVariation: true` and group `v`.
Scene editor preview adds `lightsPreview` (`changes`: 1..16 distinct paired bulbs in the scene
change format, no `fadeSeconds`); Samsung sends them as given and records only bulb state, never
preferences, groups, presence or variation. Older Samsungs answer Unknown command. Not recorded
in the activity feed.
Scene music adds scene `playlists` (0..10 distinct saved-playlist IDs; absent from older builds keeps
the saved links, `[]` unlinks) and optional reply `lightsScenePlaylists: true`, which gates the
OnePlus picker. No new command: applying the scene (any trigger) starts the music on Samsung.
Existing frames/port/authentication, pairing/fabric records and command meanings are retained.

## Verification scope

Risky change. Deterministic tests cover rule boundaries/enable transitions, scene identity and
deletion, legacy decoding and feature preservation, new record round trips, solar dates/polar
events/offsets, native fade TLV, explicit state parsing, Home freshness, bounded history and
maximum encrypted-snapshot size. Existing light/group/presence tests remain required.
Real SharedPreferences/process death, callbacks against physical bulbs, two-phone flows,
OEM reboot/alarms, large-font/TalkBack and fade behavior require device evidence; JVM codec
tests do not substitute for those checks. Build gate: testDebugUnitTest and assembleDebug.

## Focused post-change audit

- Live wiring: Activity actions → LightsViewModel → Lights mutex → existing Matter controller
  and SharedPreferences commit → StateFlow/LAN snapshot → Compose. Old inline editor and
  ViewModel whole-rule-set mutation paths have no callers. Legacy LAN handling stays supported.
- Authority, logic, race and resource review: one owner, stable scene/rule IDs, independent rule
  edits, first-wins UI work and serialized hardware I/O. Reads have a finite SDK/deadline and
  release the connected pointer in finally; no permanent callbacks or polling scopes are added.
- Corrections found during review: first-rule saves initialize the checkpoint; repeated enable
  commands retain its boundary; the exact 15-minute lateness boundary remains inclusive;
  missing scene members count as partial failure; nonzero Matter command status cannot be
  reported as success; Home votes update the projection immediately, and stop resets it.
- Persistence/wire are additive and bounded. Maximum configured snapshot fits the existing
  frame. Device role, pairing/fabric, backup, manifest, workers and dependency versions stay
  unchanged. Downgrade cannot enforce new automation semantics; keep matching updated builds.
- No-overengineering: ordinary data classes/functions, the existing owner/locks/worker/alarm,
  one reusable editor container/color selector; no new layer, module or runtime subsystem.
- Matrix: core logic and enable/save transitions COVERED by deterministic tests; JSON disk/wire
  restoration COVERED at codec boundary, real SharedPreferences/process death UNVERIFIED;
  failure/recovery and duplicates COVERED for pure policy/parsing, actual callbacks/network
  cancellation UNVERIFIED; group/scene/presence interactions COVERED at pure policy boundary;
  UI/TalkBack/recreation, full two-phone E2E, reboot/OEM alarm and actual bulb fade/read behavior
  UNVERIFIED (no APK was installed or real bulb settings changed during this task).
- Regression evidence: first-class tests cover inclusive lateness, scene deletion isolation,
  independent edits, repeated enabling, malformed reads, legacy fields and bounded history/frame.
- Executed gate: Gradle 8.14.5 `:app:testDebugUnitTest :app:assembleDebug --no-daemon
  --console=plain -Pkotlin.compiler.execution.strategy=in-process -Pkotlin.incremental=false`:
  323 tests, zero failures/errors/skips; debug APK assembled (18 new LightExperience tests).
  The final gate also compiled concurrent remote-setup changes; its JSONObject field assertion
  uses Android-compatible keys() iteration without changing the assertion. The one-run Kotlin flags avoid
  a local daemon incremental-cache registration failure; repository build settings are untouched.
- Residual risk: hardware capability/transition variation, UI/device lifecycle and Samsung/OnePlus
  background scheduling still need physical validation. No independent second-agent review was
  requested or used. Verdict: PASS WITH RESIDUAL RISK for the audited code/JVM/build scope.
