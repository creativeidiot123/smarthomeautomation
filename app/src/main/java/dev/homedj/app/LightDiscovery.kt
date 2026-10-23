package dev.homedj.app

import matter.onboardingpayload.OnboardingPayloadParser

/** Transient discovery projection. No address, credentials or setup code is retained here. */
internal data class MatterLightCandidate(val discriminator: Int, val name: String)

internal fun matchesMatterCandidate(code: String, discriminator: Int): Boolean = try {
    val parser = OnboardingPayloadParser()
    val payload = if (code.startsWith("MT:")) parser.parseQrCode(code) else parser.parseManualPairingCode(code)
    if (payload.hasShortDiscriminator) payload.discriminator == discriminator ushr 8
    else payload.discriminator == discriminator
} catch (_: Exception) {
    false
}
