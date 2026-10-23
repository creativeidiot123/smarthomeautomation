package dev.homedj.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLightSetupTest {
    private val code = "34970112332"
    private val qr = "MT:Y.K9042C00KA0648G00"

    @Test fun encryptedPairRequestReachesTheOwnerWithNoSetupCodeInTheReply() = runBlocking {
        val owners = Owners()
        val request = encrypted(RemoteLightSetup.request(" Lamp ", "3497-011-2332", 3840))
        val reply = encrypted(owners.handle(request))
        assertTrue(reply.optBoolean("ok"))
        assertEquals(1, owners.pairs)
        assertEquals("Lamp", owners.input?.name)
        assertEquals(code, owners.input?.setupCode)
        assertEquals(3840, owners.input?.discriminator)
        assertFalse(reply.toString().contains(code))
        assertFalse(reply.has("setupCode"))
        assertFalse(owners.input.toString().contains(code))
    }

    @Test fun acceptsPrintedQrOrManualCodeAndChecksTheSelectedDiscriminator() {
        assertEquals(qr, RemoteLightSetup.pairing("", qr, 3840)?.setupCode)
        assertNull(RemoteLightSetup.pairing("Lamp", qr, 3841))
        // Manual codes carry only the high four discriminator bits.
        assertEquals(code, RemoteLightSetup.pairing("Lamp", code, 3850)?.setupCode)
        assertNull(RemoteLightSetup.pairing("Lamp", code, 100))
        assertNull(RemoteLightSetup.pairing("Lamp", code, -1))
        assertNull(RemoteLightSetup.pairing("Lamp", code, 4096))
    }

    @Test fun malformedOrMismatchedPairRequestsNeverReachTheOwner() = runBlocking {
        val requests = listOf(
            JSONObject().put("command", "lightsPair"),
            RemoteLightSetup.request("Lamp", code, null).put("setupCode", JSONObject.NULL),
            RemoteLightSetup.request("Lamp", code, null).put("setupCode", 123),
            RemoteLightSetup.request("Lamp", "34970112331", null),
            RemoteLightSetup.request("Lamp", "MT:" + "A".repeat(510), null),
            RemoteLightSetup.request("x".repeat(41), code, null),
            RemoteLightSetup.request("Lamp", code, null).put("name", false),
            RemoteLightSetup.request("Lamp", code, null).put("discriminator", "3840"),
            RemoteLightSetup.request("Lamp", code, null).put("discriminator", JSONObject.NULL),
            RemoteLightSetup.request("Lamp", qr, 3841),
        )
        val owners = Owners()
        requests.forEach { assertFalse(owners.handle(it).optBoolean("ok")) }
        assertEquals(0, owners.pairs)
        assertEquals(0, owners.forgets)
        assertEquals(0, owners.discoveries)
    }

    @Test fun missingOptionalFieldsStillAllowPairingAndOwnerFailureStaysFailure() = runBlocking {
        val owners = Owners()
        val request = JSONObject().put("command", "lightsPair").put("setupCode", code)
        assertTrue(owners.handle(request).optBoolean("ok"))
        assertEquals("", owners.input?.name)
        assertNull(owners.input?.discriminator)
        owners.pairOutcome = Lights.Outcome(false, "Light limit reached")
        val reply = owners.handle(request)
        assertFalse(reply.optBoolean("ok"))
        assertEquals("Light limit reached", reply.optString("error"))
        assertFalse(reply.has("message"))
    }

    @Test fun discoveryOnlyReturnsBoundedNamesAndDiscriminators() = runBlocking {
        val owners = Owners()
        owners.found = List(20) { MatterLightCandidate(it, "Light $it") }
        val reply = encrypted(owners.handle(JSONObject().put("command", "lightsDiscover")))
        assertEquals(1, owners.discoveries)
        assertEquals(16, RemoteLightSetup.discovered(reply).size)
        val first = reply.getJSONArray("discovered").getJSONObject(0)
        assertEquals(setOf("name", "discriminator"), first.keys().asSequence().toSet())
        assertEquals(0, owners.pairs)
        assertEquals(0, owners.forgets)
    }

    @Test fun malformedDiscoveryResponsesAreBoundedAndNeverTreatedAsValidSelections() {
        val reply = JSONObject().put("discovered", JSONArray().put(JSONObject()
            .put("discriminator", 4095).put("name", "Last discriminator")))
        assertEquals(listOf(MatterLightCandidate(4095, "Last discriminator")), RemoteLightSetup.discovered(reply))
        reply.getJSONArray("discovered").put(JSONObject().put("discriminator", -1).put("name", "Bad"))
            .put(JSONObject().put("discriminator", "1").put("name", "Bad"))
            .put(JSONObject().put("discriminator", 1).put("name", "x".repeat(41)))
        assertEquals(1, RemoteLightSetup.discovered(reply).size)
        reply.put("discovered", JSONArray(List(17) { JSONObject().put("discriminator", it).put("name", "Light") }))
        assertTrue(RemoteLightSetup.discovered(reply).isEmpty())
    }

    @Test fun forgetRequiresAnExactPositiveNodeIdAndOnlyUsesTheExistingOwner() = runBlocking {
        val owners = Owners()
        listOf<Any>(0, -1, "1", 1.0, JSONObject.NULL).forEach { id ->
            assertFalse(owners.handle(JSONObject().put("command", "lightsForget").put("id", id)).optBoolean("ok"))
        }
        assertEquals(0, owners.forgets)
        val reply = owners.handle(JSONObject().put("command", "lightsForget").put("id", Long.MAX_VALUE))
        assertTrue(reply.optBoolean("ok"))
        assertEquals(Long.MAX_VALUE, owners.forgotten)
        assertEquals(1, owners.forgets)
        assertEquals(0, owners.pairs)
    }

    @Test fun expiredBudgetReturnsAnHonestFailureWithoutAutomaticallyRetrying() = runBlocking {
        val owners = Owners()
        val reply = owners.handle(RemoteLightSetup.request("Lamp", code, null), timeoutMillis = 0)
        assertFalse(reply.optBoolean("ok"))
        assertEquals(RemoteLightSetup.PAIR_TIMEOUT_MESSAGE, reply.optString("error"))
        assertEquals(0, owners.pairs)
        assertTrue(RemoteLightSetup.PAIR_REPLY_MILLIS > 135_000)
    }

    @Test fun serviceCancellationCancelsPairingInsteadOfReplyingWithSuccessOrRetrying() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var pairs = 0
        var replies = 0
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            RemoteLightSetup.handle(RemoteLightSetup.request("Lamp", code, null),
                pair = { pairs++; entered.complete(Unit); awaitCancellation() },
                forget = { error("Not a forget command") }, discover = { error("Not discovery") })
            replies++
        }
        entered.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, pairs)
        assertEquals(0, replies)
    }

    @Test fun oldOrUnavailableServerCannotOfferSetupWhileSamsungAlwaysCan() {
        assertFalse(LightsUiState().setupAvailable)
        assertTrue(LightsUiState(mode = HomeDjMode.SERVER).setupAvailable)
        assertFalse(LightsUiState(mode = HomeDjMode.CONTROLLER, loaded = true).setupAvailable)
        assertFalse(LightsUiState(mode = HomeDjMode.CONTROLLER, remoteSetupSupported = true).setupAvailable)
        assertTrue(LightsUiState(mode = HomeDjMode.CONTROLLER, loaded = true, remoteSetupSupported = true).setupAvailable)
    }

    private fun encrypted(value: JSONObject): JSONObject {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val challenge = ByteArray(16) { (it + 1).toByte() }
        val output = ByteArrayOutputStream()
        RemoteChannel.send(DataOutputStream(output), key, challenge, value)
        return RemoteChannel.receive(DataInputStream(ByteArrayInputStream(output.toByteArray())), key, challenge)
    }

    private class Owners {
        var pairs = 0
        var forgets = 0
        var discoveries = 0
        var input: LightPairingInput? = null
        var forgotten: Long? = null
        var found = emptyList<MatterLightCandidate>()
        var pairOutcome = Lights.Outcome(true, "Light paired")

        suspend fun handle(request: JSONObject, timeoutMillis: Long = 5_000): JSONObject =
            RemoteLightSetup.handle(request, pair = { pairs++; input = it; pairOutcome },
                forget = { forgets++; forgotten = it; Lights.Outcome(true, "Light forgotten") },
                discover = { discoveries++; found }, timeoutMillis = timeoutMillis)
    }
}
