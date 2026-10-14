---
name: android-google-play-developer
description: "Use for Google Play publishing/policy/operations: Play Console, AAB/signing, tracks/rollouts, review/declarations, vitals, Play Core, store listing, SDK governance, Developer API. Not billing internals."
---

# Google Play Developer, Distribution, and Policy

Use this skill for work whose correctness depends on Google Play rather than only the Android runtime: Play Console setup, app review, policy classification, declarations, app bundles and signing, testing tracks, rollout control, store presence, Android vitals, Play Core, SDK compliance, and Play Developer APIs.

This skill is a release and compliance owner, not a substitute for legal advice or the full current Google Play policy text. Google Play requirements, deadlines, declarations, console navigation, supported API levels, library requirements, and enforcement practices change. Verify current official documentation before making a compliance claim or changing release behavior.

For overlapping work also read:

- `android-play-billing-entitlements` for purchases, subscriptions, acknowledgement, RTDN, and entitlement reconciliation;
- `android-security-privacy-identity` for Play Integrity request binding, server verification, identity, permissions, and security boundaries;
- `android-gradle-di-release` for Gradle, variants, signing configuration, R8, CI, and artifact creation;
- `android-performance-release` for app performance diagnosis and release optimization;
- `android-testing-debugging` for test design and failure reproduction;
- `android-post-change-audit` before declaring the release or fix complete.

## Freshness contract

Before implementation, submission, or policy advice:

```text
Official documentation checked on:
Play policy effective date checked:
Policy deadlines checked:
Target API requirement checked:
Required Play SDK/library versions or deadlines checked:
Play Console notices checked:
Affected countries/form factors/tracks:
Source URLs recorded:
```

Rules:

- Use current official `support.google.com/googleplay/android-developer`, `developer.android.com`, `developers.google.com/android-publisher`, and `developers.google.com/play/developer/reporting` documentation as authority.
- Treat blog posts, community replies, screenshots, old rejection examples, and remembered Console navigation as secondary evidence.
- Never hardcode a target API level, policy deadline, Billing Library deadline, bad-behavior threshold, review duration, tester requirement, quota, or Console menu path without checking the current official source.
- Record the date of the verification. A previously correct release checklist may become stale.
- Policy summaries identify where to inspect; they do not replace reading the full policies relevant to the app.
- Play review or a clean pre-launch report is not proof that the app is policy-compliant, secure, accessible, or defect-free.

## Name the Play owners

Before changing Play-related behavior, name:

```text
Developer account owner:
Organization/legal entity owner:
Play Console admin and access owner:
Package/application ID owner:
App signing key owner:
Upload key owner:
Release artifact owner:
Track and rollout owner:
Managed publishing owner:
Store listing/metadata owner:
App content declaration owner:
Privacy policy owner:
Data inventory/Data safety owner:
Permissions declaration owner:
Target audience/content rating owner:
SDK inventory and compliance owner:
Billing catalog and entitlement owner:
Play Integrity backend owner:
Quality/vitals owner:
Policy incident and appeal owner:
Play Developer API/service account owner:
```

Do not let a client app, CI script, release plugin, or SDK silently become the authority for legal declarations, entitlement, authorization, account ownership, or production rollout.

## Inspect and classify before changing anything

Inspect repository and release evidence:

- application IDs, namespaces, version codes/names, min/target/compile SDKs;
- all release variants, manifests, permissions, exported components, features, ABIs, native libraries, screen and device requirements;
- AAB generation, Play App Signing state, upload key handling, mapping files, native symbols, baseline profiles, asset/feature modules;
- dependency graph and third-party SDK versions;
- all network endpoints, WebViews, dynamic code, file downloads, and SDK initialization;
- account creation, login, deletion, data export, retention, and logout behavior;
- data collection, processing, sharing, advertising, analytics, diagnostics, location, contacts, media, health, financial, identifiers, and SDK behavior;
- monetization: digital products, subscriptions, ads, physical goods/services, financial transfers, donations, or external offers;
- user-generated content, social interaction, messaging, dating, generative AI, health, government, news, finance, gambling, blockchain, VPN/security, children, and age-restricted features;
- store listing, screenshots, privacy policy, support contact, test credentials, declarations, countries, device exclusions, and existing policy notices when available;
- current Play Console app dashboard, Publishing overview, Policy status, App content, App integrity, Android vitals, device catalog, and release tracks when access exists.

Then classify:

```text
App or game:
Personal or organization developer account:
New or existing package:
Phone/tablet/TV/Wear/Automotive/ChromeOS/XR form factors:
Public, private, preloaded, or managed distribution:
Countries/regions:
Primary audience and any child audience:
Account creation offered:
Data collected/shared and SDK collection:
Ads or paid promotion:
Digital goods/subscriptions:
Physical goods/services or other payment processing:
UGC/social/messaging/dating:
AI-generated content:
Health/medical:
Financial/loan/wallet/crypto:
Gambling/contest:
Government/news:
Sensitive permissions/APIs:
Dynamic feature/asset delivery:
In-app update/review/referrer/licensing use:
Play Integrity use:
```

Every positive classification must map to current policies, declarations, implementation requirements, tests, and release evidence.

## Developer account governance

- Choose the account type that matches the actual publisher: personal for an individual publisher, organization for an organization. Do not publish a client's app under a contractor's personal account merely for convenience.
- Complete current identity, contact, organization, device, and payment-profile verification requirements accurately.
- Keep legal entity, developer profile, support contacts, merchant profile, privacy policy, store listing, and app identity consistent.
- Use individual named accounts. Do not share a primary Google account or credentials across a team.
- Grant least-privilege Play Console permissions by role and app; use permission groups and access expiry where useful.
- Keep at least two controlled administrators where organizational continuity requires it, but minimize broad account-level privileges.
- Remove departing users and stale service accounts promptly. Periodically export/review access.
- Protect account recovery channels, signing material, payment profiles, and administrator sessions.
- Treat developer-account ownership transfer, app transfer, and organization changes as controlled migrations. Verify payment profiles, API identifiers, service accounts, certificates, subscriptions, integrations, and backend package/account mappings after transfer.
- Do not create multiple developer accounts to evade enforcement, testing requirements, suspensions, or policy history.
- Preserve evidence for entity verification and app ownership without checking secrets or identity documents into the repository.

## App identity and compatibility contract

- Treat `applicationId`/package name as a durable public identity once uploaded. Do not change it to simulate an update or escape policy/review history.
- Keep version codes unique and monotonically increasing for every artifact delivered under the package. Do not reuse a version code after upload.
- Keep version name user-meaningful and release name operationally traceable; neither replaces version-code identity.
- Confirm package name consistency across app bundle, Play Console, Digital Asset Links, OAuth clients, Firebase/Google services, Billing, Play Integrity, backend allowlists, and signing certificates.
- Review min SDK, target SDK, required hardware features, screen support, ABI/native libraries, OpenGL/Vulkan requirements, permissions, country targeting, and form-factor declarations for unintended device exclusion.
- Inspect the generated delivery output, not only the source manifest. Manifest merging, dynamic features, dependencies, and splits can change delivered behavior.
- Use the device catalog and reach/device reports to understand exclusions and quality concentrations. Exclude devices only with an evidence-backed compatibility or safety reason.
- Keep target API compliance current. Targeting a new SDK is a behavior migration requiring compatibility testing, not a numeric release unblock.

## Android App Bundle and Play App Signing

- Publish new Play apps using a signed Android App Bundle where required by current Play rules.
- Distinguish:
  - app signing key: signs APKs delivered to users and is the long-lived application identity;
  - upload key: authenticates uploads to Play and can generally be reset under Play App Signing;
  - optional code-transparency key: signs a transparency file and is not the APK signing key.
- Never store production keystores, passwords, private keys, service account JSON, or signing environment output in source control or app resources.
- Restrict signing and upload credentials to controlled release infrastructure. Log artifact identity and provenance, never secret values.
- Enroll/configure Play App Signing intentionally. Before changing or upgrading signing keys, verify Android-version compatibility, trusted certificate allowlists, APIs/OAuth configuration, App Links, device-to-device update behavior, and every external system that pins the certificate.
- Reset a lost upload key through the supported Play process; do not confuse upload-key reset with replacing the app signing identity.
- Build release artifacts from reproducible, reviewed commits. Keep version code, commit, CI run, artifact hash, mapping file, native symbols, and rollout linked.
- Upload R8/ProGuard mapping files and native debug symbols for the exact artifact. Verify deobfuscation on release crashes.
- Inspect the App bundle explorer/latest bundles for generated APKs, manifest, permissions, supported devices, download size, assets, and delivery behavior.
- Test AAB delivery with `bundletool` when split APKs, languages, densities, ABIs, device targeting, feature modules, or asset packs can affect behavior.
- Do not validate only a universal/debug APK when users receive Play-generated split APKs.
- Use release audience restrictions only as an additional guard against accidental distribution; do not replace track permissions and rollout controls.
- Use code transparency only when its independent signing, key custody, verification, and operational value are understood. It does not replace Play App Signing or runtime integrity checks.

## App content, declarations, and policy

For privacy policy, Data safety, ads, app access, target audience/Families, content rating, account deletion, sensitive permissions/APIs, restricted content, device abuse, monetization policy, store-listing policy, or UGC/AI-generated-content review requirements, read [references/play-declarations-and-policy.md](references/play-declarations-and-policy.md) before changing implementation or Console declarations.

## SDK and dependency governance

The publisher is accountable for SDK behavior.

Maintain an SDK register:

```text
SDK/Maven coordinates and version:
Owner/business purpose:
Initialization point and process:
Permissions/components added:
Data collected/shared and destinations:
Consent/age/account configuration:
Network endpoints:
Data safety guidance reviewed:
Play SDK Index status/notices:
Known policy/security issues:
Upgrade/removal plan:
Release evidence:
```

Rules:

- Inspect the merged manifest and release binary; dependency declarations alone are incomplete.
- Use Play SDK Index and Play Console notices as inputs, not guarantees of compliance.
- Know what every SDK collects, why, when, under which consent/age/account state, and how it can be disabled or deleted.
- Initialize consent-sensitive SDKs only after required user choice and pass the choice correctly to the SDK.
- Remove or replace noncompliant, abandoned, vulnerable, or policy-blocked SDK versions.
- Do not add an SDK solely for a small utility when a platform or existing implementation suffices.
- Reconcile SDK updates with permissions, privacy policy, Data safety, network security, R8 rules, startup, size, and quality metrics.
- Avoid dynamic dependency versions and unreviewed repositories. Preserve dependency verification/locking policy.
- Treat SDK consoles and vendor declarations as supporting evidence; verify actual app configuration and behavior.

## Store listing and acquisition surfaces

- The listing must describe the current downloadable app, not roadmap features, hidden web functionality, or a different platform.
- Use clear, accurate, non-excessive metadata. Avoid unsupported superlatives, rankings, awards, price claims, testimonials, affiliation, guarantees, or urgency.
- Screenshots should show genuine in-app experience on supported form factors. Do not conceal required hardware, subscription, login, region, or major limitations.
- Ensure icon, graphics, title, developer name, and screenshots do not infringe or impersonate.
- Localize meaning, disclosures, screenshots, and support paths—not only strings. Review every active translation for policy and factual accuracy.
- Keep support email/site, privacy URL, and developer profile operational.
- Use custom store listings and experiments only with truthful variants. Track which audience sees which claims.
- Treat store listing changes as release changes: review, approve, submit, and verify the live result.
- Do not buy, fabricate, gate, suppress, or incentivize ratings/reviews in prohibited ways.

## Testing tracks and Play-installed verification

Use the smallest track that proves the needed behavior:

- internal testing for rapid controlled distribution;
- closed testing for selected cohorts and longer validation;
- open testing for broader opt-in feedback;
- production only after release gates pass.

Rules:

- Verify current testing requirements for the developer account, especially new personal accounts. Do not assume old tester counts or duration requirements.
- Test from a Play-installed build for APIs whose behavior depends on Play ownership, signing, licensing, track membership, or Store state.
- Internal app sharing is useful for rapid artifact distribution but can bypass or differ from normal track review and some production behavior. It is not a production-equivalence claim.
- Keep test accounts and tester lists controlled. Do not expose production secrets or customer data.
- Test upgrade paths from materially active production versions, not only clean installs.
- Test rollback/forward-fix compatibility: database schema, serialized data, server API, WorkManager, deep links, files, and feature flags.
- Test process death, offline behavior, account change, permission revocation, locale, accessibility, form factors, low storage, old supported Android versions, and representative devices.
- Verify that debug/test menus, endpoints, credentials, bypasses, and components are absent or disabled in release.

### Pre-launch reports and pre-review checks

- Review pre-review errors and warnings before submission; fix blocking issues and explain consciously accepted warnings.
- Review pre-launch report stability, compatibility, performance, accessibility, security/privacy findings, screenshots/video, and test coverage when generated.
- Pre-launch reports are subject to device-lab capacity and automated exploration limits. No report or “no issues” result is not evidence that a workflow is safe.
- Supply valid test credentials and environment state so automated/reviewer flows can reach protected functionality.
- Reproduce findings locally or on equivalent devices where possible; do not suppress a real crash merely because automation used an unusual sequence.

## Release preparation and rollout

Before creating a release:

```text
Artifact/version code/hash:
Source commit and CI run:
Signing identity verified:
Mapping/native symbols retained:
App content declarations reviewed:
Store listing/release notes reviewed:
Target countries/devices/form factors reviewed:
Test-track evidence:
Pre-review/pre-launch findings disposition:
Android vitals baseline:
Backend/API/config compatibility:
Migration and rollback/forward-fix plan:
Rollout owner and stop criteria:
Monitoring dashboard and alert owner:
```

Rules:

- Upload the intended AAB and inspect Play's generated delivery. Do not infer the uploaded artifact from a filename.
- Use meaningful internal release names and accurate localized release notes.
- Resolve Play Console errors before rollout and triage warnings rather than blindly accepting them.
- Use managed publishing when review approval and go-live time must be separated. Know which changes are and are not held by managed publishing under current Console behavior.
- Use staged rollout for changes with material runtime, migration, SDK, auth, billing, networking, or device risk unless the risk model justifies full release.
- Define rollout cohorts/percentage progression, observation windows, metrics, and halt thresholds before starting.
- Compare release quality by version, device, Android version, country, and user journey—not only global averages.
- Halting a rollout prevents further delivery but does not remove the version from users who already installed it. Prepare a forward fix and server-side mitigation.
- Understand current full-rollout halt behavior and limitations; do not assume the first release or every track can be reverted.
- Do not depend on review finishing at a fixed time. Submit with schedule margin and avoid unnecessary changes that restart or expand review.
- Verify the live store listing, package, version availability, countries, device eligibility, subscriptions/products, and critical server config after publishing.

## Android vitals and production quality

Treat Play quality as a release signal and an ongoing operational obligation.

Inspect:

- user-perceived crash rate and crash clusters;
- user-perceived ANR rate and ANR clusters;
- excessive partial wake locks and other battery/background behavior surfaced by Play;
- startup, rendering, memory/LMK, background network, permissions, and other available metrics;
- overall and per-device/model thresholds;
- affected version codes, Android versions, countries, RAM/SoC/ABI, and form factors;
- bad-behavior warnings and store visibility impact;
- deobfuscation and native-symbol quality.

Rules:

- Check current threshold definitions in Android vitals; do not preserve numeric thresholds in long-lived instructions.
- Prefer user-perceived metrics and affected-user counts over raw event counts alone.
- Investigate per-device regressions even when the global aggregate is healthy.
- Compare against the previous production version and rollout cohort before increasing exposure.
- Link every material regression to an owner, incident, release decision, fix version, and verification.
- Use the Play Developer Reporting API when automated monitoring or cross-app reporting materially improves response; preserve least privilege and data freshness semantics.
- Do not upload or expose sensitive user data in diagnostics. Retain enough release metadata to map traces to source.

## Play Core, Integrity, APIs, reviews, and lifecycle operations

Read [references/play-core-integrity-apis-operations.md](references/play-core-integrity-apis-operations.md) when the task touches in-app updates/reviews, feature or asset delivery, Install Referrer, licensing, Play Integrity/automatic protection, Billing routing, Play Developer APIs/automation, ratings/reviews, policy rejection/remediation, app transfer, unpublishing, or end of life.

## Release evidence checklist

A Play release is not complete without evidence proportional to risk:

```text
[ ] Current Play policies/deadlines/target API checked and dated
[ ] Developer account verification and access reviewed
[ ] Package/version/signing certificates verified
[ ] Release AAB built from the intended commit
[ ] App bundle explorer/generated APK delivery inspected
[ ] Mapping and native symbols retained/uploaded
[ ] Manifest permissions/components/features reviewed in release
[ ] SDK inventory and Play SDK notices reviewed
[ ] Privacy policy accurate and accessible
[ ] Data safety reconciled with app/backend/SDK behavior
[ ] Ads, app access, target audience, content rating, and required declarations current
[ ] Account deletion works in app and web when required
[ ] Store listing and all active locales match the app
[ ] Internal/closed testing and upgrade paths completed
[ ] Play-dependent APIs tested from an eligible Play-installed build
[ ] Pre-review checks and available pre-launch findings dispositioned
[ ] Android vitals baseline captured
[ ] Backend, Billing, Integrity, links, notifications, and feature flags compatible
[ ] Migration, rollback/forward-fix, halt, and server mitigation prepared
[ ] Managed publishing and rollout semantics confirmed
[ ] Monitoring owner, metrics, alerting, and stop criteria assigned
[ ] Live post-publish verification completed
```

## Tests to require when relevant

- AAB/split install across ABI, density, locale, SDK, and form factor;
- clean install and upgrade from active production versions;
- signing certificate and OAuth/App Links/backend allowlist verification;
- reviewer credentials from clean device/account;
- privacy/account deletion/data export and backend propagation;
- restricted-permission denial, revocation, degraded behavior, and declaration consistency;
- child/unknown-age paths, ad configuration, content moderation, and purchase controls;
- UGC report/block/remove/appeal and AI report/filter behavior;
- Play Billing purchase, pending, restore, duplicate, refund, expiry, RTDN, and account change;
- Integrity genuine/failure/unavailable/replay/request-mismatch and staged enforcement;
- in-app update flexible/immediate cancellation, recreation, low storage, and no-loop behavior;
- in-app review eligibility path without assuming the dialog appears;
- dynamic feature/asset delivery absent/downloading/cancelled/failed/recreated/offline;
- internal, closed, staged, halted, and forward-fix release paths;
- minified release crashes/ANRs with correct deobfuscation;
- Play Developer API edit conflict, wrong-track prevention, least privilege, and audit trail.

## Anti-patterns

- claim “Play compliant” without current policy review;
- publish a client app from a contractor-owned personal account;
- shared administrator credentials or repository-stored Play/service-account secrets;
- confuse upload key with app signing key;
- test only a debug/universal APK instead of Play-generated delivery;
- reuse version code or change package to evade an update/rejection;
- privacy policy, Data safety, declarations, and runtime behavior disagree;
- omit SDK data/permissions from declarations;
- reviewer credentials expire, require inaccessible MFA, or cannot reach paid/restricted features;
- select a child audience accidentally or use noneligible ad SDKs for children;
- request restricted permissions for convenience or undisclosed future features;
- use a clean pre-launch report as proof of correctness;
- full production rollout without defined monitoring or halt criteria;
- assume halting removes a bad version from installed devices;
- force immediate in-app updates for ordinary releases or create launch loops;
- promise an in-app review dialog or pre-screen users by sentiment;
- dynamic feature navigation before confirmed installation;
- client-side Play Integrity or Billing verdict treated as authority;
- CI service account has owner-level permissions or can publish production without a gate;
- superficial appeal/resubmission without fixing all active artifacts and SDK behavior;
- evade enforcement through new accounts, packages, misleading metadata, or hidden behavior.

## Completion report

When finishing Play-related work, report:

```text
Play scope and classifications:
Owners:
Official sources and verification date:
Policies/declarations affected:
Artifact/package/version/signing evidence:
Tracks and rollout behavior:
Store listing/App content changes:
SDK/data/permission findings:
Tests and Play-installed verification:
Pre-review/pre-launch/vitals findings:
Rollback/forward-fix and monitoring:
Residual assumptions or policy/legal decisions:
```

Do not say “approved,” “policy compliant,” “safe for production,” or “will pass review” unless the statement is narrowly scoped and supported by current evidence. Google retains review and enforcement authority, policies evolve, and app behavior can vary by distributed artifact, SDK, backend, account, region, and device.

## Official source map

Re-check the current version of these official source families rather than relying on this list as frozen policy:

- Google Play Developer Program Policy: https://support.google.com/googleplay/android-developer/answer/17105854
- Developer account types: https://support.google.com/googleplay/android-developer/answer/13634885
- Developer account information and verification: https://support.google.com/googleplay/android-developer/answer/13628312
- Verify developer identity: https://support.google.com/googleplay/android-developer/answer/10841920
- Users and permissions: https://support.google.com/googleplay/android-developer/answer/9844686
- New personal-account testing requirements: https://support.google.com/googleplay/android-developer/answer/14151465
- App transfer and account ownership changes: https://support.google.com/googleplay/android-developer/answer/16909862
- Central Play Resource/policy index: https://support.google.com/googleplay/android-developer/answer/15759508
- Policy announcements and deadlines: https://support.google.com/googleplay/android-developer/announcements/13412212
- Create and set up an app: https://support.google.com/googleplay/android-developer/answer/9859152
- Prepare an app for review/App content: https://support.google.com/googleplay/android-developer/answer/9859455
- Data safety: https://support.google.com/googleplay/android-developer/answer/10787469
- User Data/account deletion: https://support.google.com/googleplay/android-developer/answer/10144311
- Account deletion guidance: https://support.google.com/googleplay/android-developer/answer/13327111
- App access/reviewer credentials: https://support.google.com/googleplay/android-developer/answer/15748846
- Target audience: https://support.google.com/googleplay/android-developer/answer/9867159
- Content ratings: https://support.google.com/googleplay/android-developer/answer/9859655
- Sensitive permissions/APIs: https://support.google.com/googleplay/android-developer/answer/16558241
- Financial features declaration: https://support.google.com/googleplay/android-developer/answer/13849271
- Health content and services: https://support.google.com/googleplay/android-developer/answer/16679511
- UGC policy: https://support.google.com/googleplay/android-developer/answer/9876937
- AI-generated content policy: https://support.google.com/googleplay/android-developer/answer/13985936
- SDK safety and requirements: https://support.google.com/googleplay/android-developer/answer/13326895
- SDK Index guidance: https://support.google.com/googleplay/android-developer/answer/12034434
- Metadata policy: https://support.google.com/googleplay/android-developer/answer/9898842
- Store listing best practices: https://support.google.com/googleplay/android-developer/answer/13393723
- Preview assets: https://support.google.com/googleplay/android-developer/answer/9866151
- Testing tracks: https://support.google.com/googleplay/android-developer/answer/9845334
- Prepare and roll out a release: https://support.google.com/googleplay/android-developer/answer/9859348
- Managed publishing: https://support.google.com/googleplay/android-developer/answer/9859654
- Staged rollout and halt: https://support.google.com/googleplay/android-developer/answer/6346149
- Full rollout halt: https://support.google.com/googleplay/android-developer/answer/16285429
- Pre-review checks: https://support.google.com/googleplay/android-developer/answer/14807773
- Pre-launch reports: https://support.google.com/googleplay/android-developer/answer/9842757
- Android vitals: https://developer.android.com/topic/performance/vitals
- Device catalog: https://support.google.com/googleplay/android-developer/answer/7353455
- Target API requirements: https://support.google.com/googleplay/android-developer/answer/11926878
- App bundles: https://developer.android.com/guide/app-bundle
- Build/test app bundles: https://developer.android.com/guide/app-bundle/test
- Code transparency: https://developer.android.com/guide/app-bundle/code-transparency
- Upload to Play: https://developer.android.com/studio/publish/upload-bundle
- Play App Signing: https://support.google.com/googleplay/android-developer/answer/9842756
- Play Core overview: https://developer.android.com/guide/playcore
- In-app updates: https://developer.android.com/guide/playcore/in-app-updates
- In-app reviews: https://developer.android.com/guide/playcore/in-app-review
- Feature Delivery: https://developer.android.com/guide/playcore/feature-delivery
- Asset Delivery: https://developer.android.com/guide/playcore/asset-delivery
- Install Referrer: https://developer.android.com/google/play/installreferrer/library
- App Licensing: https://developer.android.com/google/play/licensing
- Play Integrity: https://developer.android.com/google/play/integrity
- Google Play Developer APIs: https://developer.android.com/google/play/developer-api
- Android Publisher API: https://developers.google.com/android-publisher
- Play Developer Reporting API: https://developers.google.com/play/developer/reporting
