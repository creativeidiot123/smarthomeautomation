package dev.homedj.app

import kotlinx.coroutines.withTimeoutOrNull
import matter.onboardingpayload.OnboardingPayloadParser
import org.json.JSONArray
import org.json.JSONObject

/** Deliberately not a data class: default string rendering must not expose the setup code. */
internal class LightPairingInput(val name: String, val setupCode: String, val discriminator: Int?)

/** Validated setup actions over the existing authenticated channel; Samsung remains the owner. */
internal object RemoteLightSetup {
    val COMMANDS = setOf("lightsDiscover", "lightsPair", "lightsForget")
    const val PAIR_REPLY_MILLIS = 150_000
    const val PAIR_TIMEOUT_MESSAGE = "Pairing did not finish in time. Check the lights before trying again. " +
        "Open the light's Matter pairing mode and keep Samsung on the same Wi-Fi."

    fun pairing(name: String, code: String, discriminator: Int? = null): LightPairingInput? {
        if (name.length > LightsPolicy.MAX_NAME || code.length > 512 ||
            (discriminator != null && discriminator !in 0..4095)) return null
        val setupCode = matterSetupCode(code) ?: return null
        return try {
            val parser = OnboardingPayloadParser()
            val payload = if (setupCode.startsWith("MT:")) parser.parseQrCode(setupCode)
                else parser.parseManualPairingCode(setupCode)
            if (discriminator != null && payload.discriminator !=
                if (payload.hasShortDiscriminator) discriminator ushr 8 else discriminator) null
            else LightPairingInput(name.trim(), setupCode, discriminator)
        } catch (_: Exception) {
            null
        }
    }

    fun request(name: String, code: String, discriminator: Int?): JSONObject =
        JSONObject().put("command", "lightsPair").put("name", name).put("setupCode", code)
            .also { request -> discriminator?.let { request.put("discriminator", it) } }

    private fun pairing(request: JSONObject): LightPairingInput? {
        val name = if (request.has("name")) request.opt("name") as? String ?: return null else ""
        val code = request.opt("setupCode") as? String ?: return null
        val discriminator = if (request.has("discriminator")) request.opt("discriminator") as? Int ?: return null else null
        return pairing(name, code, discriminator)
    }

    fun discoveryMessage(found: List<MatterLightCandidate>): String = if (found.isEmpty())
        "No lights found on Samsung's Wi-Fi. Open the light's Matter pairing mode, then scan again."
        else "Choose a light, then scan or enter its Matter code."

    fun discovered(reply: JSONObject): List<MatterLightCandidate> {
        val values = reply.optJSONArray("discovered") ?: return emptyList()
        if (values.length() > LightsPolicy.MAX_BULBS) return emptyList()
        return (0 until values.length()).mapNotNull { index ->
            val value = values.optJSONObject(index) ?: return@mapNotNull null
            val discriminator = value.opt("discriminator") as? Int ?: return@mapNotNull null
            val name = (value.opt("name") as? String)?.let(LightsPolicy::validName) ?: return@mapNotNull null
            if (discriminator in 0..4095) MatterLightCandidate(discriminator, name) else null
        }.distinctBy { it.discriminator to it.name }
    }

    /** The deadline includes waiting for Samsung's existing owner mutex, not just native work. */
    suspend fun handle(
        request: JSONObject,
        pair: suspend (LightPairingInput) -> Lights.Outcome,
        forget: suspend (Long) -> Lights.Outcome,
        discover: suspend () -> List<MatterLightCandidate>,
        timeoutMillis: Long = when (request.optString("command")) {
            "lightsPair" -> 135_000L
            "lightsDiscover" -> 15_000L
            else -> 40_000L
        },
    ): JSONObject {
        val command = request.optString("command")
        val reply = withTimeoutOrNull(timeoutMillis) {
            when (command) {
                "lightsPair" -> {
                    val input = pairing(request) ?: return@withTimeoutOrNull error("Invalid Matter code, name or light selection")
                    outcome(pair(input))
                }
                "lightsForget" -> {
                    val id = (request.opt("id") as? Number)?.takeIf { it is Long || it is Int }?.toLong()
                    if (id == null || id <= 0) error("Invalid light") else outcome(forget(id))
                }
                "lightsDiscover" -> {
                    val found = discover().take(LightsPolicy.MAX_BULBS)
                    JSONObject().put("ok", true).put("message", discoveryMessage(found))
                        .put("discovered", JSONArray().also { values ->
                            found.forEach { candidate -> values.put(JSONObject()
                                .put("name", candidate.name).put("discriminator", candidate.discriminator)) }
                        })
                }
                else -> error("Unknown command")
            }
        }
        return reply ?: error(if (command == "lightsPair") PAIR_TIMEOUT_MESSAGE
            else "The Samsung did not confirm in time. Refresh lights before trying again.")
    }

    private fun outcome(outcome: Lights.Outcome): JSONObject = JSONObject().put("ok", outcome.ok)
        .put(if (outcome.ok) "message" else "error", outcome.message)

    private fun error(message: String): JSONObject = JSONObject().put("ok", false).put("error", message)
}
