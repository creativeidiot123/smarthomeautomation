---
name: android-play-billing-entitlements
description: "Use for Play Billing, subscriptions, purchases, or entitlements: acknowledgement, pending purchases, restore, server verification, billing migrations, Engage publishing. Client state is not entitlement authority."
---

# Play Billing and Engage

## Scope and first inspection

Determine which product surface is involved, inspect the installed library versions and existing backend contract, and consult the current official release notes before changing version-sensitive APIs. Do not migrate Billing, add an entitlement server, or introduce Engage unless the task requires it.

For Billing, identify:

```text
Product type: one-time / consumable / non-consumable / subscription
Catalog owner: Play Console / backend / local fixture
Purchase launch owner: Activity or Compose UI edge
Purchase update owner: application-scoped billing coordinator or existing equivalent
Entitlement truth: backend / Room / DataStore / in-memory session
Verification: backend / existing trusted service / none
Acknowledgement or consumption owner:
Reconnect and restore behavior:
Account/profile binding:
Test products and environments:
```

For Engage, identify the vertical, cluster types, publishing trigger, deletion/expiry behavior, continuation tokens, stable entity IDs, freshness requirement, and whether the target is phone, tablet, TV, or another supported surface.

## Billing architecture

- Keep billing-client mechanics behind a narrow repository/coordinator boundary. UI launches the billing flow and renders states; it does not own connection recovery, entitlement truth, acknowledgement, or token storage.
- Keep the `BillingClient` lifetime consistent with the existing app architecture. Avoid recreating it per recomposition or click. Release resources when its owner ends.
- Treat `PurchasesUpdatedListener` as one input, not the sole truth. Re-query owned purchases after reconnect, relevant foreground/resume transitions, and explicit restore when required by the product.
- Model connection, catalog, launch, pending, purchased, canceled, already-owned, unavailable, verification, acknowledgement/consumption, and restore outcomes explicitly.
- Disable or de-duplicate purchase launch while a flow is active. Purchase and entitlement processing must be idempotent because callbacks, retries, reconnects, and backend notifications can repeat.
- Launch the billing UI only from an eligible foreground UI owner using the supported API. Do not retain an Activity in a long-lived client.

## Trust and entitlement rules

- A successful client callback is not sufficient proof for valuable entitlement. Prefer server-side verification using the purchase token, package/product identity, and the backend's authenticated account when the product has a server.
- Bind purchases to the correct signed-in account/profile using supported obfuscated identifiers where appropriate; never put email, raw account IDs, or other PII in those fields.
- Grant entitlement from one authoritative state. If the backend is truth, cache only the minimum state required for offline product behavior and define expiry/reconciliation.
- Acknowledge eligible non-consumables/subscriptions and consume consumables through the designated owner after verification/business processing. Make the sequence retryable and idempotent.
- Pending purchases are not entitled until they become purchased and pass verification. Cancellation is a normal outcome, not an exception or error dialog by default.
- Do not log purchase tokens, signed payloads, account identifiers, or full billing responses. Redact diagnostic output.
- Do not trust local booleans, SharedPreferences, or a mutable singleton as durable entitlement truth.

## Subscription behavior

- Preserve base plan, offer, upgrade/downgrade, replacement mode, proration, grace period, account hold, pause, resubscribe, and prepaid behavior already supported by the product.
- Do not infer subscription status from a single historical purchase. Reconcile with the trusted backend or current owned-purchase state and handle revocation/expiry.
- Backend real-time developer notifications are signals to re-query trusted state, not complete entitlement records by themselves.
- Expose user-safe recovery for unavailable products, stale catalog, unsupported device/account, and transient Play errors.

## Billing migration

- Read the installed version, target version, official migration guide, and release notes. Migrate sequentially when major-version removals require it.
- Search for removed/deprecated query, product-details, pending-purchase, alternative-billing, listener, and subscription APIs before editing.
- Do not copy a sample tied to another major version. Keep existing backend payload compatibility unless coordinated.
- Verify debug/test purchases, pending paths, restore, acknowledgement/consumption, process recreation, offline/reconnect, and a minified build when reflection/serialization is involved.

## Engage publishing

- Keep Engage SDK models at the integration boundary. Map stable domain data into the currently installed SDK types.
- Publish only eligible, current, user-appropriate entities. Use stable content IDs, valid images/URIs, localized text, and continuation metadata that opens a valid destination.
- Define update, replacement, deletion, expiry, logout, account-switch, and consent-revocation behavior. Stale recommendations are a correctness/privacy defect.
- De-duplicate publishing and avoid rebuilding or transmitting unchanged clusters repeatedly.
- Use the receiver/service/worker mechanism required by the installed SDK and current platform documentation. Do not register broad exported components or background work merely because an old sample does.
- Validate deep links and authorization at the destination. Publishing a continuation does not bypass app access controls.
- Keep TV/mobile or vertical-specific rules separate when the APIs or product semantics differ.

## Anti-patterns

Reject:

- granting entitlement immediately from an unverified client callback;
- purchase launch from a ViewModel or retained Activity reference;
- one-shot listener processing with no restore/re-query path;
- duplicate acknowledgement, consumption, or entitlement mutation without idempotency;
- treating pending/canceled as purchased/error respectively;
- hardcoded product catalog as truth when a backend/Play catalog already owns it;
- raw purchase token logging;
- unconditional migration to the newest sample architecture;
- Engage clusters with unstable IDs, no delete path, expired data, or invalid continuation links.

## Verification checklist

```text
[ ] Installed SDK and backend contract were inspected.
[ ] Purchase launch, updates, verification, entitlement, and acknowledgement owners are explicit.
[ ] Duplicate callbacks/retries cannot double-grant or double-consume.
[ ] Pending, cancel, reconnect, restore, already-owned, and process recreation are handled.
[ ] Account switch/logout reconciles cached entitlement and Engage content.
[ ] No sensitive billing identifiers are logged or exposed.
[ ] Version migration follows current official APIs and tests the minified/release path.
[ ] Engage update/delete/freshness and destination validation are defined.
```


## Deep implementation protocol

### Entitlement state machine

Separate billing-client connection, product catalog, purchase transaction, backend verification, acknowledgement/consumption, and user entitlement. The Play response is evidence, not the sole authority for server-backed entitlement.

Model at least: unavailable/disconnected, loading products, purchasable, purchase launched, pending, purchased-unverified, verified-entitled, rejected, cancelled, error/retry, restored, revoked/expired where applicable.

### Idempotency and recovery

Purchase tokens are processed idempotently. Reconnection and query-on-start restore unfinished purchases. Acknowledgement/consumption occurs only under the product policy and is safe to retry. Account identity and obfuscated identifiers must not grant entitlement by client assertion alone.

### Engage publishing contract

Publishing is derived from authoritative current data, deduplicated, bounded, privacy-safe, and removed/updated when content or account state changes. Publishing failure must not corrupt primary app data or entitlement.

## AI-generated code hazards

- entitlement granted immediately from purchase callback without verification/persistence policy.
- pending purchase treated as success.
- purchase token logged, exposed in analytics, or used as a UI identifier.
- client-side flag/preferences used as authoritative premium state.
- acknowledgement/consumption omitted, duplicated, or applied to wrong product type.
- reconnection or restoration not handled after process death/client disconnect.
- multiple BillingClient instances/listeners causing duplicate processing.
- product IDs/pricing hard-coded into entitlement logic or UI assumptions.
- cancellation mapped to alarming error or retried automatically.
- backend retry duplicates fulfillment because token/idempotency is absent.
- test only covers successful test purchase, not pending/reconnect/revoke/account mismatch.
- Engage content leaks private/account-specific data or is never removed on logout.

## Post-change audit

### Billing matrix

Verify applicable flows with the repository's test environment:

- product unavailable/empty catalog and client disconnected;
- user cancellation;
- pending then completed purchase;
- completed purchase with verification delay/failure;
- duplicate callback/query returns same token;
- process death/relaunch before acknowledgement or verification;
- restore on another installation/session under correct account policy;
- revoked/expired/refunded state where backend supports it;
- acknowledgement/consumption retry;
- account switch/logout isolation.

Inspect persisted entitlement and backend/UI transitions independently. Confirm UI cannot unlock from a transient callback alone.

### Engage checks

Verify publish/update/remove, deduplication, limits, logout/account change, stale content, privacy classification, and failure isolation.

### Evidence record

```text
Entitlement authority:
Purchase states exercised:
Verification/idempotency proof:
Acknowledgement/consumption proof:
Reconnect/process-recovery proof:
Account isolation:
Engage publish/remove proof:
Environment limitations:
```
