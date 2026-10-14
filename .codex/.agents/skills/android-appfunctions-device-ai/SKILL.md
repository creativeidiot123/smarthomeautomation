---
name: android-appfunctions-device-ai
description: "Use for Android AppFunctions or on-device/agentic AI: function schemas, tool execution, confirmations, authorization, privacy, prompt injection, malformed model output, lifecycle, evaluation. Verify current APIs."
---

# AppFunctions and Device AI

## Treat AI as an untrusted caller

AppFunctions and agent/model integrations are API and security boundaries. Model output, function input, retrieved text, and external tool results are untrusted even when generated on-device. Inspect the installed SDK, annotations/manifest metadata, schema format, execution contract, permission model, and current platform documentation before using version-sensitive APIs.

Resolve:

```text
Eligible user capability:
Function caller and authentication context:
Read versus mutation:
Truth and mutation owner:
Input/output schema and limits:
Confirmation policy:
Idempotency and duplicate-call policy:
Cancellation/timeout/threading:
Sensitive data policy:
Fallback when AI/service/model is unavailable:
```

## Feature discovery and function design

Choose workflows that are already coherent user capabilities, have a clear owner, can be authorized at execution time, and produce a verifiable result. Avoid exposing every button, CRUD method, debug action, or ambiguous multi-step flow. Rank candidates by user value, safety, determinism, data sensitivity, confirmation needs, and whether the normal app path already supports the operation correctly.

- Expose narrow user-meaningful operations, not generic repository/database/network access.
- Use explicit typed inputs and outputs with documented units, formats, nullability, limits, and error cases. Do not expose internal entities, DTOs, secrets, stack traces, or SDK implementation types.
- Function names and KDoc/descriptions state the action, prerequisites, side effects, confirmation, and returned meaning. Do not write marketing language or imply stronger guarantees than the owner provides.
- Validate every argument at the boundary: length, ranges, enum values, URI/ID ownership, path traversal, account scope, and authorization.
- Default to read-only functions. Mutations, purchases, messages, deletion, sharing, authentication changes, and other consequential actions require explicit product policy and usually user confirmation at the trusted UI edge.
- Do not let model-generated text silently become a SQL query, shell command, Intent, URL, file path, deep link, or network request.
- Keep function descriptions accurate and non-overlapping so callers select the intended capability. Do not promise behavior the implementation cannot guarantee.

## Execution and ownership

- Route function execution through the same ViewModel/repository/service owners as normal app behavior; do not create a privileged bypass around validation, entitlement, or authorization.
- Durable or retryable mutations use stable request IDs and idempotent repository semantics. Treat repeated calls as expected.
- Preserve coroutine cancellation. Apply explicit timeouts only at the boundary that owns the SLA; never catch cancellation and return fake success.
- Keep blocking model/runtime/file work off the main thread, but do not add redundant dispatcher hops around already-suspending Room/network APIs.
- Do not retain Activity, View, Composable, or short-lived Context in a function registry or model runtime.
- Constrain queues, context windows, generated output, retries, parallel requests, and cached artifacts.

## Privacy and security

- Minimize the data included in prompts, embeddings, function responses, logs, analytics, and caches. Redact tokens, credentials, health/financial data, contacts, messages, precise location, and user identifiers unless strictly required and approved.
- Separate system/developer policy, trusted app data, retrieved untrusted content, and user input. Retrieved text must not override authorization or tool policy.
- Enforce permissions and account scope at execution time, not only when the function was discovered.
- Provide deletion/logout/account-switch behavior for prompts, indexes, embeddings, generated files, and cached responses.
- Verify model/download integrity through the platform/library mechanism; do not fetch and execute arbitrary model/code artifacts.
- Keep network disclosure and cloud fallback explicit to the user/product policy. “On-device” must not silently transmit content.

## User control and failure behavior

- Show the user what will happen before a consequential action and make cancel/back effective.
- Report partial, unavailable, permission-denied, unsupported, timed-out, canceled, and validation outcomes without fabricated certainty.
- A model's confident response is not evidence that a function ran. Return structured execution status from the actual mutation owner.
- Preserve an ordinary non-AI route to critical app functionality when product requirements demand accessibility/reliability.

## On-device model integration

- Check device capability, model availability, download/storage state, thermal/memory constraints, and language support.
- Make initialization single-flight and lifecycle-owned. Release native/session resources deterministically.
- Avoid model initialization or inference directly from composition.
- Stream output through bounded, cancellation-aware state. Do not append unbounded token history to immutable screen state at frame rate; batch appropriately.
- Treat generated structured data as invalid until parsed and validated. Define fallback for malformed or truncated output.
- Benchmark representative devices and release builds; debug/emulator latency and memory are not production evidence.

## Anti-patterns

Reject:

- broad “run anything” functions;
- hidden destructive mutations or purchases;
- trusting prompt/retrieved/model text as authorization;
- duplicate mutation with no request ID/idempotency;
- leaking internal data models or exception traces;
- unbounded chat history, output, retries, or model cache;
- Activity/View capture in function/runtime singletons;
- silent cloud fallback or sensitive prompt logging;
- treating generated JSON as valid without schema validation;
- copying experimental annotations or manifest entries without checking the installed version.

## Verification

Test valid and adversarial inputs, authorization/account boundaries, duplicate calls, cancellation, timeout, process restart, permission denial, malformed model output, unavailable/download failure, offline behavior, confirmation/cancel, privacy logging, resource release, and a release/minified build when reflection or generated schemas are involved.


## Deep implementation protocol

### Treat model output and function input as hostile

The model is an untrusted planner/caller. Validate function name, schema version, types, lengths, enum values, IDs, account scope, authorization, current state, rate, and replay/idempotency at the execution boundary. Never execute arbitrary code, SQL, shell, URI, intent, reflection target, or internal class name supplied by a model.

### Capability design

Expose the narrowest deterministic capability with explicit preconditions and structured results. Separate read-only discovery from mutation. High-impact, irreversible, financial, privacy-sensitive, account, communication, or external-sharing actions require product-approved user confirmation at the point of action.

### AI result ownership

Distinguish:

- model suggestion/extraction/classification, which may be wrong;
- verified application data from repository/server;
- requested action;
- authorized committed action;
- user-visible explanation and undo/recovery.

Do not merge these into one “success” object. The app remains authoritative for state and policy.

### Privacy and model lifecycle

Define whether processing is on-device or remote, data minimization/redaction, model/download availability, retention, telemetry, consent, account isolation, cancellation, thermal/battery limits, and fallback when the model is unavailable or low-confidence.

## AI-generated code hazards

- trusting model-generated IDs, recipient, amount, URI, or action without resolving current authoritative data.
- prompt text used as authorization, permission grant, or policy instruction.
- broad function such as `executeAction(type, payload)` exposing arbitrary internal behavior.
- destructive mutation auto-executed because model confidence is high.
- hidden network/model download or private-data upload without product consent/disclosure.
- raw prompt/output/embeddings/user content logged or added to crash analytics.
- hallucinated fields defaulted into plausible values instead of rejected/confirmed.
- retry repeats non-idempotent side effect after timeout/unknown result.
- model callback outlives screen/account and commits stale action.
- no deterministic fallback when model/API unavailable.
- evaluation uses hand-picked happy prompts only and ignores adversarial injection, ambiguity, locale, and malformed structured output.
- AI feature directly mutates ViewModel/database, bypassing repository/security/audit policy.

## Post-change audit

### Adversarial capability tests

Test applicable cases:

- unknown function/schema/field, missing required field, oversized input, invalid enum/ID;
- prompt injection asking to bypass confirmation, reveal private data, or call hidden capability;
- wrong account/tenant and stale object ID;
- ambiguous recipient/entity and low-confidence result;
- duplicate/replayed request and timeout with unknown mutation outcome;
- cancellation, account switch, screen departure, model unload, offline/no model;
- sensitive input/output redaction and telemetry opt-out;
- user rejects confirmation or uses undo/recovery;
- locale/script variation and malformed model structured output.

### Authority and evidence checks

- Trace every capability to existing ViewModel/repository/security owners.
- Confirm mutation is validated again at commit time against current truth.
- Verify allowlist contains only intended functions and no reflective/dynamic escape.
- Confirm no model data reaches logs, analytics, network, or storage beyond declared policy.
- Evaluate with a versioned prompt/input corpus and record false positive/negative and unsafe-action cases; do not claim quality from anecdotal examples.

### Evidence record

```text
Capabilities exposed:
Authoritative validation/authorization point:
Confirmation/undo policy:
Injection/malformed-input results:
Replay/idempotency result:
Privacy/telemetry/storage audit:
Offline/unavailable/cancellation behavior:
Evaluation corpus limitations:
```
