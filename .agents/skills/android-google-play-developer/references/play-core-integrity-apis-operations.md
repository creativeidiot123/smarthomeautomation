## Play Core libraries

Use Play Core only when the product requires a Play-delivered capability. The libraries are a runtime interface to the Play Store and have availability, lifecycle, account, signing, and testing constraints.

- Use the current per-feature libraries rather than the obsolete monolithic Play Core dependency.
- Keep Activity/UI ownership for system/store UI launchers; keep policy and durable state in ViewModel/repository owners.
- Model unavailable Play Store, unsupported device, sideloaded build, API error, user cancellation, process recreation, and duplicate callback.
- Register listeners only for the lifecycle that needs them and always unregister.
- Do not construct Play clients repeatedly in composition or leak Activity references.

### In-app updates

- Choose flexible versus immediate flow from explicit product risk and update criticality, not developer convenience.
- Check update availability, allowed update type, staleness, priority, and in-progress state using current APIs.
- Flexible update: monitor download, preserve normal app use, surface completion intentionally, and resume/complete after lifecycle recreation.
- Immediate update: use only when continued use is unsafe or incompatible; handle cancellation and failure without a launch loop.
- Do not prompt on every launch, block users for a noncritical version, or create an update-dialog loop.
- Test through an eligible Play track/build and cover low storage, loss of network, background/foreground, cancellation, and restart.

### In-app reviews

- Ask after the user has experienced meaningful value, not immediately after install, login, failure, or payment.
- Do not pre-screen sentiment, ask for a five-star rating, gate rewards/features, or manipulate the Play review card.
- Treat launch success as “the request completed,” not proof that a dialog was shown or a review was submitted; quotas and Store policy control presentation.
- Do not expose a persistent button whose promised behavior depends on quota. Use a Play Store listing link for an explicit “write a review” action when appropriate.
- Test with supported Play track methods and fake managers only for app-side branching; fake managers do not simulate the real UI or quota.

### Play Feature Delivery

- Add dynamic feature modules only when install size or conditional/on-demand delivery produces material value; do not modularize a small app ceremonially.
- Keep the base module functional while a feature is absent, downloading, cancelled, failed, or removed.
- Model install session IDs and state transitions; suppress stale sessions and duplicate requests.
- Handle user confirmation, insufficient storage, network failure, cancellation, retry, module availability after process recreation, and uninstall/removal.
- Do not navigate into feature code until installation and access are confirmed.
- Test local/bundletool behavior and Play-delivered behavior for all targeted configurations.

### Play Asset Delivery

- Use asset packs for large non-code game/app assets when the delivery model fits; never place executable code in asset packs.
- Choose install-time, fast-follow, or on-demand per user journey and offline requirements.
- Check asset-pack state at each relevant launch; fast-follow content may not yet be ready.
- Monitor download per pack, unregister listeners, handle waiting-for-Wi-Fi/user-confirmation, cancellation, paused state, retry, updates, removal, and storage pressure.
- Never assume all requested packs complete together or that a file remains at a stable path across versions.
- Test with bundletool local testing and a Play track.

### Install Referrer

- Use only for legitimate attribution/fraud analysis under current privacy and ads rules.
- Connect/disconnect according to the API lifecycle; handle unavailable Store, errors, duplicate reads, reinstall, and delayed processing.
- Validate and normalize referrer input before use. Treat it as untrusted attribution data, not authentication, authorization, payment, or entitlement proof.
- Avoid retaining raw identifiers longer than necessary and reflect relevant processing in privacy/Data safety declarations.

### App licensing

- Prefer current Play Integrity/licensing architecture where applicable; use legacy licensing only when an existing supported product requires it.
- Licensing signals do not replace account entitlement, backend authorization, or offline policy.
- Define offline grace, unavailable Play Store, account change, restore, and clock/replay behavior explicitly.

## Play Integrity and automatic protection

Use `android-security-privacy-identity` for request/response architecture.

Play-wide rules:

- Configure the correct Play Console/Cloud project, package, quota, test responses, and optional verdicts intentionally.
- Bind Standard/Classic requests to the exact sensitive operation using the supported request hash/nonce model.
- Decode and evaluate tokens on a trusted server. The client does not grant privilege from its own verdict.
- Combine integrity with authentication, authorization, transaction risk, rate limits, abuse history, and recovery paths.
- Roll out enforcement gradually, measure false positives, and provide remediation for legitimate users when appropriate.
- Handle unavailable, quota, transient, sideloaded, unlicensed, device, app-recognition, and account states without permanent accidental lockout.
- Keep verdict policy server-configurable and versioned. Do not ship a brittle client-side allowlist.
- Treat automatic protection and Play remediation dialogs as additional controls, not substitutes for secure design.

## Billing and monetization routing

For digital products and subscriptions, use `android-play-billing-entitlements` and verify current Play Payments policy.

Play-wide checks:

- classify the sold item correctly: digital good/service, physical good/service, donation, financial transaction, ad-funded access, or another category;
- verify whether Play Billing is required, prohibited, optional, or subject to an approved program/region under current policy;
- keep store listing, paywall, product catalog, base plans/offers, pricing, trials, renewal, cancellation, and actual entitlement consistent;
- provide reviewer access to paid functionality where required;
- do not direct users around Play Billing in a prohibited way;
- do not treat purchase UI success as entitlement authority;
- reconcile refunds, revocations, chargebacks, account hold, expiry, grace, and voided purchases server-side.

## Play Developer APIs and automation

Use APIs only when automation materially improves safety, scale, or repeatability.

- Run publishing/reporting automation from controlled server or CI infrastructure, never from the Android client.
- Prefer service accounts for server-to-server access and grant only the Play Console permissions required for the task.
- Store credentials in the CI/secret manager; rotate and audit them. Never place service account keys in the repository or app.
- Publishing API edits are draft transactions: create an edit, make intended changes, validate, and commit. Handle edit expiry/conflict and concurrent publishers explicitly.
- Serialize or coordinate production publishing so two automations/humans cannot overwrite tracks, listings, or rollout state unexpectedly.
- Validate package, artifact/version, track, country, rollout fraction, release status, and listing changes before commit.
- Use dry-run/validation and test tracks where supported; require explicit production authorization for high-risk rollouts.
- Automation does not bypass Play review, declarations, policy, or managed publishing semantics.
- Use Reporting API metrics with documented freshness, dimensions, aggregation, and permissions. Do not sum distinct-user counts incorrectly across overlapping dimensions.
- Reply-to-reviews automation must preserve privacy, tone, localization, human escalation, and current API limitations.
- Permissions API automation must enforce offboarding and least privilege, not create permanent broad access.
- After app/developer-account transfer, update Developer IDs, service-account access, callbacks, package ownership mappings, and automation configuration.

## Reviews, ratings, and user feedback

- Do not buy, fabricate, incentivize, gate, or selectively suppress reviews in violation of current policy.
- Do not ask only satisfied users to rate while diverting dissatisfied users away from Play.
- Respond to reviews without disclosing account, order, health, location, or other personal data.
- Move account-specific support to a secure channel and never request secrets in a public review response.
- Use review trends as product evidence but confirm technical issues through diagnostics and affected versions.
- Keep automated replies bounded and human-reviewed for sensitive, legal, safety, billing, or high-severity complaints.

## Policy submission, rejection, and remediation

When a warning, rejection, removal, suspension, or SDK notice occurs:

```text
Exact notice and policy section:
Package, track, version code, countries, and date:
Affected artifact/listing/declaration/SDK/backend behavior:
Reproduction or reviewer path:
Current official policy text checked:
Root cause:
All distributed versions/variants affected:
User-data/security impact:
Corrective change:
Declaration/listing/privacy changes:
Verification evidence:
Resubmission/appeal owner:
```

Rules:

- Preserve the notice and screenshots privately. Do not rely on paraphrased memory.
- Read the linked full policy and current help guidance before editing.
- Identify whether the defect is binary behavior, SDK behavior, backend behavior, metadata, declaration, reviewer access, account state, or distribution configuration.
- Fix the root cause across every active artifact and declaration; do not submit superficial wording changes when runtime behavior remains noncompliant.
- Remove noncompliant SDK versions and any transitive behavior they introduce.
- Submit a coherent correction with exact navigation/reproduction notes when useful.
- Appeal only when there is specific evidence of factual error or policy-compliant behavior. Be concise and factual; do not threaten, spam repeated submissions, or create replacement accounts/packages to evade enforcement.
- Treat a successful resubmission as a release event and monitor quality, data, and user impact.

## App transfer, unpublishing, and end of life

- Before transfer, inventory signing certificates, OAuth/Firebase/Cloud projects, Play Developer API access, Billing/RTDN, Integrity, App Links, support/privacy domains, subscriptions, tax/payment profiles, store assets, testers, and backend package/account ownership.
- After transfer, verify every integration using the new developer account/Developer ID where applicable.
- Unpublishing stops new discovery/installs under current behavior but does not uninstall the app or end obligations to existing users.
- Preserve security fixes, account deletion, data export/deletion, subscription management, backend compatibility, and support for installed users according to product/legal obligations.
- For shutdown, communicate dates, stop new purchases, reconcile entitlements/refunds, provide data handling, and avoid leaving a broken login or destructive migration.
- Do not abandon signing keys or release infrastructure while users still depend on updates.
