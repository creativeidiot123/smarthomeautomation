# Lights setup from either phone

Either phone can scan a Matter QR, enter a setup code, request discovery and explicitly pair a bulb. Either phone can confirm Forget light. Samsung remains the only Matter controller and owns the bulb catalog, fabric credentials, groups, settings and schedules. OnePlus must first connect to Samsung through the existing HomeDJ pairing flow. No second Matter fabric is created on OnePlus.

## Journeys and truth

- In Lights, the user scans a QR or types a code. A scan only prepares the code; Pair light sends the action. For initial Matter setup, the printed code can be used while the bulb's Matter pairing window is open and it is already on Samsung's Wi-Fi. A bulb already commissioned through Matter needs sharing from that controller's app. AiDot connectivity alone does not establish previous Matter commissioning.
- Scan Wi-Fi always discovers from Samsung, including when requested on OnePlus. Its results contain only bounded names and discriminators. Selecting a result checks the manual code's short discriminator or QR code's full discriminator again on Samsung.
- Success appears after Samsung's native commissioning and existing catalog commit succeed. OnePlus renders Samsung's returned snapshot. Other controls, scenes, groups and schedules keep their existing owners and remote paths.
- Forget light requires the existing explicit confirmation on either phone. It invokes the same Samsung owner: HomeDJ removes its fabric when reachable and drops its catalog entry even when the bulb does not answer, reporting that distinction. Other apps' fabrics are not removed. The request only accepts a positive exact integer node ID, and Samsung checks catalog membership.

## Authentication, privacy and compatibility

Three additive commands use the existing challenge-authenticated AES-GCM connection and port 47188: `lightsDiscover`, `lightsPair` and `lightsForget`. Existing commands and framing retain their meaning. The server's existing enabled/Server-role gate runs before these handlers; pairing/forgetting also use the existing Lights role check and mutex. Native discovery rechecks role after acquiring the Matter mutex.

`lightsPair` carries a bounded `setupCode`, optional `name`, and optional integer `discriminator`. Setup codes are deliberately transmitted only from OnePlus to its authenticated Samsung, held in memory for the request, and never echoed into responses, snapshots, activity history, errors or logs. Manual entry is not saved across process death, and scanned codes remain private ViewModel memory until cleared or the role changes. Camera frames stay with the existing Google scanner; no new permission, external QR service or dependency is added. Samsung retains the same SDK-managed fabric credentials and existing bulb records after success. No new persistent key or format is introduced.

`lightsDiscover` returns optional `discovered` entries with only `name` and `discriminator`, at most 16. Replies include an additive `lightsSetup: true` capability. A Controller without that capability shows an update hint and keeps existing supported controls. Unknown commands retain the existing `Unknown command` response, and older Controllers ignore the extra fields.

## Lifetimes, duplicates and unknown outcomes

ViewModel actions remain first-wins while another Lights action is running. Samsung serializes mutations with its existing owner mutex. Concurrent requests from both phones can wait for that owner; this is not a claim of cross-device exactly-once execution. No pairing command is automatically resent.

The remote pairing deadline is 135 seconds, including owner-queue time; its Controller read budget is 150 seconds. Native pairing keeps its existing 120-second deadline and IO startup/attestation/cleanup. Remote discovery and forget actions have 15- and 40-second budgets; normal Controller reads remain 45 seconds. Errors are fixed safe messages and the latest Samsung snapshot accompanies handled replies. A lost confirmation has an unknown outcome: inspect/refresh the Samsung snapshot before retrying. A socket timeout refreshes status and never resends the mutation.

Samsung's existing service scope owns a remote operation. Controller navigation or disconnection does not prove it was cancelled on Samsung. Service destruction or role change cancels service-owned work; the existing native cleanup remains on IO. After process death, reload Samsung's durable catalog; there is no saved setup code or automatic replay of an unfinished request. Pairing does not become a Worker or new background service.

## Observable acceptance

1. Given paired phones on the home network and an open bulb pairing window, a OnePlus QR scan prepares a code, Pair light commissions on Samsung, and the returned catalog includes the bulb. The OnePlus never initializes a Matter controller.
2. Given manual input or a selected discovery result, malformed codes, invalid names/types, and mismatched discriminators are rejected before native pairing. Discovery exposes no addresses or credentials.
3. Given a paired bulb, cancelling the Forget confirmation changes nothing. Confirming calls Samsung's existing forget owner and refreshes the Controller from Samsung's result.
4. Given an older Samsung, setup controls are unavailable with an update hint; existing light controls still work. New request fields remain additive.
5. Given Wi-Fi loss, service cancellation, expiry or unavailable Samsung, the UI never claims unconfirmed success and never retries pairing automatically. A later refresh reloads server truth.
6. Given rapid taps, recreation or role changes, the current ViewModel guard prevents repeated taps, scanned codes are not persisted, and normal role/cancellation boundaries apply. Two-phone concurrent requests remain serialized at Samsung.

Out of scope: provisioning Wi-Fi credentials or BLE from HomeDJ, internet/cloud bulb access, a OnePlus Matter controller, factory reset, changing attestation policy, and moving Samsung-only system permission grants to OnePlus.
