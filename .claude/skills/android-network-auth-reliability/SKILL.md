---
name: android-network-auth-reliability
description: "Use for Retrofit/OkHttp/Ktor/API/auth reliability: DTOs/serialization, contracts, token refresh, retries, idempotency, pagination, timeouts, errors, fake-server tests. Not Room/cache ownership."
---

# Network, Retrofit, Ktor, and Authentication

## Inspect the boundary

Identify:

- client stack and versions;
- client lifetime/DI owner;
- base URL and environment selection;
- serialization/content negotiation;
- auth and token refresh owner;
- timeout/retry policy;
- DTO/error model and mapping boundary;
- logging/redaction;
- pagination/upload/download behavior;
- certificate/network security configuration;
- test tools already present.

Do not add Retrofit to Ktor code, Ktor to Retrofit code, a second OkHttpClient, or a new serialization library without a concrete need.

## Client ownership

- Construct clients in DI/platform data configuration, not UI/ViewModel.
- Reuse a properly configured client; avoid per-request client construction and connection-pool loss.
- Scope authenticated and unauthenticated clients intentionally.
- Close Ktor/other closeable clients at their owning application/test lifecycle.
- Keep base URL/environment controlled by build/configuration, not arbitrary user input unless the product is explicitly a client for custom servers.
- Avoid mutable global interceptors or token variables.

## Service/API shape

- Service methods return wire DTOs or a boundary response type, not UI/domain objects.
- Use suspend for one-shot requests; streams/WebSockets expose an owned Flow abstraction.
- Encode path/query/body/header semantics explicitly.
- Do not pass auth token manually from every caller; centralize it.
- Keep optional fields and API defaults distinguishable.
- Model pagination cursors/tokens as opaque values; do not parse unless contract requires it.
- Streaming/upload/download APIs expose progress, cancellation, size limits, and destination ownership.

## Main safety and dispatchers

Normal Retrofit suspend calls and Ktor suspend requests are asynchronous/main-safe. Do not wrap every call in `withContext(IO)`. Use IO for truly blocking legacy clients/file streams. Use Default for heavy decoding/transformation only when needed and measured.

Cancellation must cancel the underlying request where supported. Never map cancellation to “no internet.”

## DTO and serialization

- Match wire names through annotations/configuration, not domain naming hacks.
- Define behavior for unknown enum values and unknown fields.
- Distinguish missing, explicit null, empty, and default.
- Validate required business fields before mapping inward.
- Treat server timestamps/time zones consistently.
- Avoid permissive coercion that hides malformed security/payment data.
- Do not serialize secrets into logs, analytics, saved state, or exception messages.
- Keep polymorphic serialization registration narrow and tested; account for R8/reflection if used.

## Error mapping

At the repository/network boundary classify, as relevant:

- cancellation;
- no connection/DNS/connectivity;
- timeout;
- TLS/certificate;
- authentication/authorization;
- rate limited/retry-after;
- validation/conflict/not found;
- server failure;
- protocol/serialization failure;
- local file/storage failure for transfer;
- unknown internal failure.

Preserve status/request ID/safe cause internally, but expose a stable domain failure. Do not show raw server body, exception, URL with secrets, or stack trace to users.

`Response<T>` is useful only when status/headers/body control is needed. Prefer decoded body methods when non-2xx can be handled centrally. Do not let Retrofit/OkHttp/Ktor exception types leak into ViewModel.

Avoid `catch (Exception) { emptyList() }`; empty is valid content, not an error encoding.

## Authentication and token refresh

Centralize token retrieval/storage and header injection.

A safe refresh design:

- refresh only on the expected auth failure;
- single-flight concurrent refresh through Mutex/deferred/authenticator support;
- use a refresh client/path that cannot recursively invoke the same authenticator;
- compare the failed request token with current token before refreshing;
- cap attempts and fail/log out according to product policy;
- atomically store token set and expiry metadata;
- clear account-scoped state/work on terminal auth failure;
- do not block main;
- redact every token/cookie/header;
- account for clock skew and server expiry.

Do not retry a request indefinitely after 401. Do not refresh from UI. Do not place long suspend work in an interceptor API that expects synchronous behavior unless the library’s authenticator design explicitly supports it and threads are controlled.

## Retry, timeout, and idempotency

- Set connect/read/write/call timeouts based on endpoint behavior, not one huge global value.
- Retry transient failures only with a bounded policy.
- GET/read may be retried when safe; mutation retry requires idempotency key or known idempotent contract.
- Respect `Retry-After` and backoff with jitter.
- Do not retry auth/validation/not-found/permanent serialization failures blindly.
- Upload retry must account for repeatable request body and server partial state.
- WorkManager owns durable retry if the operation must survive process death.
- Avoid stacking interceptor retry + repository retry + WorkManager retry without a combined bound.

## Pagination

- Keep cursor/page key opaque and scoped to query/account/filter.
- Handle duplicates, reordered pages, deleted items, and end-of-list.
- De-duplicate concurrent page loads.
- Do not use page number assumptions for cursor APIs.
- Preserve old content on refresh failure when product wants stale data.
- Paging library integration remains in data skill; API layer only supplies boundary operations.

## Retrofit/OkHttp specifics

- Use the project’s converter and call adapter.
- Interceptors: application interceptor for app concerns; network interceptor only when actual network-chain semantics are needed.
- Interceptor order is deliberate (auth, retry, logging, caching).
- Logging is disabled or redacted in release and never logs sensitive bodies.
- Cache headers/OkHttp cache do not replace repository truth semantics.
- Avoid `runBlocking` in interceptors. If a synchronous authenticator must obtain a token, use an architecture designed for that boundary and test contention/deadlock.
- One OkHttpClient may be derived with `newBuilder` for compatible variants; avoid uncontrolled pools.

## Ktor/KMP specifics

- Provide an explicit engine per platform (Android/OkHttp/CIO, Darwin, etc.) according to project support.
- Configure `ContentNegotiation`, default request, timeouts, auth, response validation, and logging centrally.
- Do not reference Android engine/types from commonMain.
- Bearer refresh uses Ktor auth plugin or repository strategy with single-flight and loop protection.
- `expectSuccess`/response validation behavior is explicit; map exceptions at boundary.
- Use `MockEngine` for deterministic common tests when already available or justified.
- Close test clients.

## TLS and network security

- Use HTTPS and the repository Network Security Config.
- Cleartext exceptions are narrow, debug/local-only when possible.
- Certificate pinning requires operational ownership, backup pins/rotation, expiry monitoring, and recovery; do not add as a generic hardening step.
- Never implement custom trust-all hostname verifier/TrustManager.
- Proxy/user-installed CA behavior must match product threat model.
- Do not embed private keys/client secrets in the app.

## WebSockets/SSE and streams

- Define reconnect/backoff/heartbeat policy;
- avoid duplicate subscriptions on lifecycle recreation;
- close connection at owner lifetime;
- parse messages off hot UI path if heavy;
- preserve ordering and de-duplicate IDs;
- authenticate renewal safely;
- expose connection/stale/error state;
- do not treat stream as durable truth without persistence/reconciliation.

## Testing

Use existing tools: MockWebServer for OkHttp/Retrofit, Ktor MockEngine, fake service/repository, fixture parser tests.

Cover:

- request path/query/header/body;
- unknown/missing/null fields;
- status/error body mapping;
- cancellation and timeout;
- concurrent 401 single refresh;
- refresh failure/loop prevention;
- retry bounds/idempotency key;
- pagination duplicates/end;
- redaction/no sensitive logging;
- R8/serialization release behavior if reflection/codegen is involved.

## Anti-patterns

- API/service/client constructed in UI/ViewModel;
- token passed from each screen;
- raw network exception/DTO exposed to UI;
- `withContext(IO)` around every suspend request;
- broad catch returns empty/fake success;
- recursive or parallel token refresh storm;
- unlimited retries;
- retrying non-idempotent write without protection;
- release body logging;
- trust-all TLS/certificate code;
- custom-server URL accepted without validation/product intent;
- Ktor client without platform engine;
- per-request client creation;
- multiple uncoordinated retry layers.


## Deep implementation protocol

### Endpoint contract

For every changed call, record method/path, authentication, request identity/idempotency, timeout, retry eligibility, pagination, serialization/nullability, success codes, structured error codes, caching headers, cancellation, and server/client clock assumptions.

Map transport to stable domain outcomes at the boundary. Preserve enough diagnostic classification for retry/telemetry without exposing raw bodies or sensitive data to UI.

### Authentication state machine

Token refresh must have one coordinator and explicit states: valid, refreshing, refreshed, unauthenticated, terminal failure. Concurrent callers either await the same refresh or fail by policy. The refresh request must not recursively trigger itself. Logout/account switch invalidates tokens, in-flight authenticated requests, caches, and persisted work by account policy.

### Retry discipline

A retry policy specifies eligible errors/statuses, idempotency protection, maximum attempts, backoff/jitter, server hints, connectivity ownership, cancellation, and final error. UI retry and client automatic retry must not multiply attempts unexpectedly.

## AI-generated code hazards

- catch-all mapping every exception to “No internet,” including cancellation, parsing, TLS, and server errors.
- interceptor performs blocking refresh with recursive client call or multiple simultaneous refreshes.
- retries all methods, duplicating POST/payment/upload mutations.
- token persisted/logged in plain preferences or added to URLs/query logs.
- DTO used directly as UI/domain state with untrusted nulls/enums.
- default values hide missing required server fields and create fake success.
- raw server error body/exception shown to user or logged with credentials/PII.
- creating a new OkHttp/Ktor client per request or screen.
- adding certificate pinning without rotation/recovery design.
- ignoring response body close/stream cancellation or WebSocket/SSE lifecycle.
- pagination appends duplicate/out-of-order pages or trusts client page state after refresh.
- tests mock service interface only and never validate actual serialization/interceptor behavior.

## Post-change audit

### Boundary tests

Use a fake server or equivalent real serialization boundary for applicable cases:

- expected request method/path/headers/body and redaction;
- success with optional/unknown/reordered fields;
- malformed body, empty body, wrong content type, and unknown enum;
- each mapped status/error family;
- timeout, disconnect, cancellation, and retry exhaustion;
- two concurrent 401 responses causing one refresh;
- refresh failure/logged-out transition and no recursion;
- duplicate mutation with idempotency key or explicit no-retry policy;
- pagination refresh, duplicate page, empty final page, and stale cursor.

### Runtime/static checks

- Inspect interceptors/authenticators order and client reuse.
- Search logs and analytics for headers, bodies, tokens, cookies, IDs, and URLs containing secrets.
- Confirm account switch cancels/isolates in-flight and cached data.
- Verify TLS/network-security config and cleartext policy for every relevant variant.
- Assert cancellation is not converted to user-visible network failure.

### Evidence record

```text
Endpoint contract changed:
Serialization/error cases tested:
Auth single-flight result:
Retry/idempotency result:
Cancellation/stream cleanup:
Logging/redaction audit:
Account-switch behavior:
Unverified server assumption:
```
