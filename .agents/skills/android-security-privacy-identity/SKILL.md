---
name: android-security-privacy-identity
description: "Use for Android security/privacy/identity or trust boundaries: secrets/logs, intents/deep links, PendingIntent, exported components, URIs/WebView, permissions, credentials, Play Integrity, authorization."
---

# Security, Privacy, Intents, and Identity

## Threat-boundary first

Identify:

```text
Sensitive assets/data:
Trusted and untrusted callers:
Entry points (component, link, API, file, WebView, notification):
Authentication and authorization owner:
Storage/logging/backup exposure:
Server validation:
Replay/idempotency:
Failure and recovery:
```

Do not add “security” mechanisms without a threat model and operational plan. Client code cannot make a server-trusted decision simply by obfuscating or encrypting locally.

## Secrets and sensitive data

Never commit/store credentials, API private keys, signing keys, refresh tokens, passwords, payment data, or personal data in:

- source/Gradle/version catalog/manifest/resources;
- BuildConfig expecting secrecy;
- plain SharedPreferences/DataStore/files/database where approved secure storage is required;
- logs, analytics, breadcrumbs, crash reports, screenshots, clipboard, notifications, route arguments, intents, saved state, or exception text.

Public API identifiers may be non-secret, but still follow provider restrictions. Mobile apps cannot safely hold backend client secrets.

Use the project’s approved keystore/encrypted storage. Define backup/device-transfer behavior, key invalidation, biometric enrollment changes, logout deletion, and recovery. Encryption-at-rest does not replace server authorization or secure transport.

## Logging, analytics, and crash reporting

- structured, minimum necessary fields;
- redact/hash only when the residual identifier is justified and not reversible/linkable beyond need;
- never log request/response body by default;
- sanitize URLs/query strings/headers/cookies;
- avoid user-entered text and document/media paths;
- gate debug logs and disable sensitive interceptors in release;
- crash breadcrumbs and custom keys use allowlisted, bounded metadata; avoid high-cardinality identifiers and user-entered text;
- non-fatal reporting is reserved for actionable failures, rate-limited/de-duplicated, and never used as ordinary logging;
- release mapping files and native symbols are uploaded/archived through the approved pipeline and tied to the exact version;
- respect consent, data residency, retention, deletion, and account-reset policy;
- verify analytics/crash collection is disabled or redirected in tests and non-production variants as intended;
- do not upload logs/traces without authorization.

Do not include secrets in data-class `toString` or exception messages. Crashlytics/Sentry/other SDK names do not change these invariants.

## Manifest and exported components

For each Activity/Service/Receiver/Provider:

- `exported` is explicit and false unless external access is required;
- exported entry point validates action, data, MIME type, extras, caller, and auth;
- protect with signature/custom/system permission when appropriate;
- no sensitive default intent filters;
- service/broadcast work respects background execution limits;
- provider paths/permissions/grants are narrow;
- debug/test components are excluded from release.

Do not trust an Intent because it originated from an app-owned-looking package extra. Caller/package identity can be absent/spoofable depending on mechanism; use supported platform identity/permission checks and server auth.

## Intent input validation

Treat external Intent/deep link/share content as untrusted.

- allowlist actions/schemes/hosts/paths/MIME/types;
- validate lengths, formats, IDs, file sizes, and count;
- ignore/reject unknown extras rather than deserializing arbitrary classes;
- avoid unsafe Serializable/Parcelable from untrusted source;
- canonicalize URIs/paths before authorization;
- prevent open redirect and arbitrary URL loading;
- re-check authentication/authorization at destination;
- do not execute destructive actions directly from unconfirmed external input;
- handle missing target app and malformed data safely.

## PendingIntent

- immutable by default;
- mutable only for APIs that require sender modification, with explicit narrow intent;
- use explicit component/package;
- unique request code/data/action when distinct identity is required;
- avoid base implicit Intent that another app can intercept;
- use one-shot/cancel/update flags intentionally;
- do not put sensitive data in extras unnecessarily;
- destination revalidates current auth/authorization and object state;
- notifications/widgets/alarm actions are idempotent against replay.

## Deep links and App Links

- verify host association where App Links are intended;
- allowlist routes and parameters;
- reject arbitrary nested redirect URLs;
- auth gate protected destinations;
- preserve intended continuation after login without trusting stale payload;
- prevent link-triggered payment/delete/change without confirmation/policy;
- test cold/warm task/back-stack behavior and duplicate links.

## Files, URIs, and ContentProvider

- use `content://` and FileProvider instead of `file://`;
- expose only allowlisted directories/paths;
- grant temporary read/write permission narrowly and revoke when possible;
- validate MIME, size, extension and actual content as relevant;
- do not resolve untrusted URI to raw path and bypass provider permissions;
- persist URI permission only when needed;
- avoid path traversal and symlink confusion;
- provider queries/updates validate selection/columns/row authorization;
- never export an unprotected provider with sensitive rows;
- close streams/descriptors.

## WebView

Default-deny capabilities.

- load only trusted HTTPS origins or validate navigation allowlist;
- disable JavaScript unless required;
- avoid `addJavascriptInterface`; if unavoidable, expose minimal annotated surface only to trusted controlled content and account for old API behavior;
- disable/restrict file/content access, universal access, mixed content, debugging, and DOM storage according to need;
- block intent/custom scheme abuse and external navigation safely;
- validate downloads/uploads/file chooser;
- clear sensitive history/cache/cookies according to session policy;
- Safe Browsing/error pages do not expose secrets;
- do not ignore TLS errors;
- isolate untrusted web content from authenticated app bridges.

## Network security

- HTTPS; no trust-all TrustManager/hostname verifier;
- narrow cleartext debug exceptions;
- security config matches environment/domain;
- certificate pinning only with backup/rotation/monitoring/recovery;
- tokens in Authorization/cookies handled centrally and redacted;
- server authorization for every protected operation;
- avoid relying on package signature/device ID as sole user authorization;
- use replay-resistant nonce/idempotency where protocol requires it.

## Permissions and privacy

- request least privilege at point of feature use;
- use privacy-preserving alternatives (Photo Picker, system picker, scoped APIs) where compatible;
- explain rationale in product language when needed;
- handle denial/permanent denial/settings/revocation/degraded behavior;
- stop sensor/location/camera/mic collection when not needed;
- avoid background collection without clear user benefit, disclosure, policy compliance;
- minimize identifiers and retention;
- do not request broad storage/contacts/location merely for convenience;
- test permission changes while app is running.

## Screens, clipboard, recents, notifications

For sensitive surfaces:

- apply secure-window/screenshot policy where product threat model requires, considering accessibility/support trade-offs;
- hide/redact recents preview;
- avoid clipboard or clear sensitive clip according to supported APIs/policy;
- notification lock-screen visibility and content are privacy-conscious;
- notification actions/PendingIntents revalidate auth;
- do not expose OTP/token/account details in toast/snackbar/log.

## Biometrics and Keystore

- use BiometricPrompt through lifecycle-safe UI owner;
- biometric authenticates local user presence/device credential and may unlock a cryptographic key; it is not server identity proof;
- select allowed authenticators and fallback intentionally;
- handle lockout, cancellation, no enrollment, hardware unavailable, enrollment/key invalidation;
- bind sensitive operation to CryptoObject where required;
- do not store raw biometric data;
- re-check server authorization/session freshness after local unlock for privileged remote actions.

## Credential Manager, passkeys, and verified credentials

- use current supported Credential Manager APIs for the installed versions;
- keep credential requests at platform/UI edge and account workflow in ViewModel/repository;
- handle cancellation/no credential/provider configuration/error safely;
- passkeys require server challenge, origin/RP ID validation, signature verification, replay prevention, and account binding;
- never trust client-parsed credential claims for account creation or privilege;
- verified email/digital credential response and original cryptographically secure unique nonce go to server for full verification;
- server validates issuer, audience/client/RP, signature, holder/presenter binding, nonce/challenge, expiry, and requested claim constraints;
- never reuse nonce;
- minimize requested disclosed attributes;
- WebView credential integration follows official bridge/origin rules and must not expose credential data to untrusted scripts.

## Play Integrity/attestation

- client requests token with request hash/nonce binding to the exact operation as supported;
- server decodes/verifies token, freshness, app/license/device verdicts, request binding, replay, and policy;
- client does not grant privileged outcome from its own parsed verdict;
- handle unavailable/transient/quota/network states without locking legitimate users permanently;
- do not treat integrity as a replacement for auth, fraud controls, or server authorization;
- Classic/Standard flow and cloud/console prerequisites are version/service configuration, not guessed repo changes.

## Payments and entitlement

Security-critical Play Billing details are in Play skill. General rule: server verifies purchase/token/product/account state and idempotently grants entitlement; client UI result alone is insufficient.

## Error handling

Map internal failures to safe categories. User messages never include:

- raw exception/stack;
- HTTP/SQL/provider/file path;
- server body/internal code that exposes architecture;
- token/session/account identifiers;
- cryptographic detail useful to attackers.

Preserve safe correlation/request ID internally. Authentication errors do not reveal whether an account exists unless product explicitly accepts enumeration risk.

## Supply chain/build security

- use trusted repositories/plugins/actions;
- avoid dynamic/unverified dependencies;
- dependency verification/lock policy preserved;
- signing materials protected;
- release non-debuggable;
- exported/test/developer menus reviewed;
- R8 is not a security boundary;
- inspect SDK data collection/permissions before adding analytics/ads/identity SDK;
- do not disable lint/security warnings globally.

## Testing

Cover:

- exported component unauthorized/malformed inputs;
- deep links auth/open redirect;
- PendingIntent mutability/identity/replay;
- URI grant/path traversal/provider row authorization;
- WebView origin/navigation/TLS/bridge;
- log redaction;
- secure storage logout/key invalidation;
- permission denial/revocation;
- credential cancellation/server challenge/nonce handling;
- integrity/payment server verification contracts;
- release manifest/network config/minification behavior.

Do not put real secrets or production user data in fixtures.

## Anti-patterns

- secret in APK/BuildConfig;
- raw body/token/PII logging;
- exported component trusts extras;
- implicit mutable PendingIntent;
- deep link directly performs privileged action;
- raw file path/shared world-readable file;
- trust-all TLS or `onReceivedSslError(...proceed())`;
- broad WebView JavaScript bridge;
- plain DataStore for credentials;
- biometric success treated as backend authentication;
- client-only verification of passkey/digital credential/integrity/payment;
- reused/predictable nonce;
- sensitive notification/clipboard/screenshot exposure;
- permission requested at startup without need;
- raw exception shown to user;
- R8/obfuscation claimed as secret protection.


## Deep implementation protocol

### Threat-boundary worksheet

For each changed entry point or data flow, identify asset, actor/caller, trust level, input channel, authentication, authorization, validation, storage, logging/telemetry, sharing/export, lifetime/deletion, replay risk, and failure behavior.

Security decisions must be enforced at the authoritative boundary. UI hiding, client flags, route arguments, biometric success, Play Integrity verdicts, or possession of an intent are signals—not standalone authorization for server or high-value actions.

### Data classification

Classify affected data as public, internal, personal, sensitive personal, credential/token, payment, health, location, or regulated by product policy. Then verify collection minimization, storage, backup, screenshots/recents, clipboard, notification visibility, logs, analytics, crash reports, export, and deletion.

### Negative design

Define what an unauthenticated, wrong-account, replaying, malicious-intent, rooted/tampered, offline, or stale client can attempt and where it is rejected. Fail closed for privileged actions while preserving safe recovery UX.

## AI-generated code hazards

- authorization performed only by UI visibility, deep-link gate, or client-side Boolean.
- secret/API key moved to BuildConfig/NDK/obfuscation and called secure.
- raw tokens, purchase tokens, credential JSON, URIs, email, location, or server body logged.
- exported component/deep link accepts unvalidated extras/URI and directly performs action.
- mutable PendingIntent or broad URI grant without need.
- WebView JavaScript/file access/bridge enabled globally to make content work.
- trust-all TLS, permissive hostname verifier, or cleartext enabled for broad domain/app.
- biometric prompt interpreted as remote identity proof rather than local key/secret gate.
- encrypted storage used without key invalidation/recovery/logout/deletion policy.
- catch-all error exposes raw stack/body or converts security rejection to success fallback.
- permission requested at startup without purpose/degraded path.
- Play Integrity/credential assertion accepted on client without nonce, audience, signature, replay, and server validation.
- tests cover valid caller only and omit malicious/wrong-account inputs.

## Post-change audit

### Static boundary audit

- Inspect merged manifest for exported components, permissions, providers, backup/data extraction, network security config, intent filters, foreground service types, and debuggable flags.
- Search logs/analytics/crash breadcrumbs and error messages for classified data.
- Search PendingIntent flags, URI grants, WebView settings/bridges, clipboard, screenshot flags, notification content, and file paths.
- Inspect storage location, encryption/key ownership, logout/account-switch deletion, and backup eligibility.
- Inspect dependency/build changes for supply-chain and secret exposure.

### Adversarial tests

Exercise applicable cases:

- missing/malformed/oversized extras and hostile URI schemes/hosts/paths;
- unauthenticated and wrong-account deep link/action;
- replayed nonce/token/request and expired credential;
- caller without permission/signature;
- locked/invalidated biometric key and device credential change;
- revoked permission and permanent denial;
- offline/stale integrity or identity result;
- screenshot/notification/recents/clipboard exposure;
- logout then back-stack/process restore.

### Evidence record

```text
Assets/trust boundaries changed:
Authorization enforcement point:
Input validation cases:
Manifest/exported audit:
Sensitive-data log/storage/share audit:
Replay/account-isolation tests:
Permission/biometric recovery:
Server-side assumption not locally verifiable:
```
