package dev.homedj.app

import chip.devicecontroller.ChipDeviceController
import chip.devicecontroller.ReportCallback
import chip.devicecontroller.WriteAttributesCallback
import chip.devicecontroller.model.AttributeWriteRequest
import chip.devicecontroller.model.ChipAttributePath
import chip.devicecontroller.model.ChipEventPath
import chip.devicecontroller.model.NodeState
import chip.devicecontroller.model.Status
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import matter.tlv.AnonymousTag
import matter.tlv.TlvReader
import matter.tlv.TlvWriter

/**
 * A bulb's boot identity from General Diagnostics (endpoint 0, cluster 0x33): RebootCount is
 * mandatory and persistent, UpTime is null where the bulb lacks it. [count] -1: not reported.
 */
internal data class BulbBoot(val count: Int, val upTime: Long?)

/**
 * [millis]: turned off/on, the hold just reached (0 = the moment power came back); off and on,
 * how long it was off; on and off, how long it was on. [HouseScript.matches] compares rules.
 */
internal data class BulbEvent(val trigger: HouseTrigger, val millis: Long)

/** What to ask a watched light this tick. */
internal enum class BulbCheck { WAIT, PING, MATTER }

/** After a ping: nothing new, the light is silent (dark), or ask Matter now. */
internal enum class BulbStep { NOTHING, SILENT, MATTER }

/**
 * One watched bulb, memory only; times are `elapsedRealtime`. [upSince]: power came back in this
 * watch's life (a first answer does not count). [upAtMiss]: how long it had been up at the first
 * missed poll of the current outage. [firedOff]/[firedOn]: holds already reported (milliseconds).
 */
internal data class BulbWatch(
    val boot: BulbBoot? = null,
    val offlineSince: Long? = null,
    val upSince: Long? = null,
    val upAtMiss: Long? = null,
    val firedOff: Set<Long> = emptySet(),
    val firedOn: Set<Long> = emptySet(),
)

/**
 * What a wall switch did, from polls only: no answer = unpowered (or off Wi-Fi). Power is back
 * when the bulb answers with a new boot, or answers at all after a `turned off` hold ran. An
 * answer without either was a Wi-Fi blip and changes nothing. An `on and off` needs the outage to
 * last [CONFIRM_MILLIS], so one missed poll is never read as a switch.
 */
internal object BulbWatchPolicy {
    /** How often the runner looks at each watched light while all are lit. */
    const val TICK_MILLIS = 500L
    /** While a watched light is dark, so its return is noticed within a quarter second. */
    const val DARK_TICK_MILLIS = 250L
    /** Matter asks while a light answers (or while no trusted ping exists for it). */
    const val POLL_MILLIS = 6_000L
    /** A powered bulb on the LAN answers a ping in milliseconds; a slow one is confirmed over Matter. */
    const val PING_MILLIS = 400
    /** Answer budget per poll once the Matter controller is free; queueing behind it is not an outage. */
    const val ANSWER_MILLIS = 3_000L
    const val CONFIRM_MILLIS = 10_000L
    /** A refused or unanswered `starts` write is tried again after this. */
    const val START_RETRY_MILLIS = 60_000L

    /** [boot] null: no answer. [offHolds]/[onHolds]: milliseconds `turned off for`/`turned on for` rules wait. */
    fun next(watch: BulbWatch, boot: BulbBoot?, now: Long, offHolds: Set<Long>, onHolds: Set<Long>): Pair<BulbWatch, List<BulbEvent>> {
        if (boot == null) {
            val since = watch.offlineSince ?: now
            val upAtMiss = if (watch.offlineSince == null) watch.upSince?.let { now - it } else watch.upAtMiss
            val events = mutableListOf<BulbEvent>()
            var next = watch.copy(offlineSince = since, upAtMiss = upAtMiss)
            if (upAtMiss != null && now - since >= CONFIRM_MILLIS) {
                events += BulbEvent(HouseTrigger.ON_AND_OFF, upAtMiss)
                next = next.copy(upSince = null, upAtMiss = null, firedOn = emptySet())
            }
            val due = offHolds.filter { it !in watch.firedOff && now - since >= it }.sorted()
            events += due.map { BulbEvent(HouseTrigger.TURNED_OFF, it) }
            return next.copy(firedOff = watch.firedOff + due) to events
        }
        val previous = watch.boot
        val rebooted = previous != null && (boot.count != previous.count ||
            (boot.upTime != null && previous.upTime != null && boot.upTime < previous.upTime))
        if (rebooted || watch.firedOff.isNotEmpty()) {
            val outage = watch.offlineSince?.let { now - it } ?: 0L
            return BulbWatch(boot, upSince = now) to listOfNotNull(
                BulbEvent(HouseTrigger.TURNED_ON, 0),
                BulbEvent(HouseTrigger.OFF_AND_ON, outage).takeIf { rebooted },
            )
        }
        val due = watch.upSince?.let { up -> onHolds.filter { it !in watch.firedOn && now - up >= it }.sorted() }.orEmpty()
        return watch.copy(boot = boot, offlineSince = null, upAtMiss = null, firedOn = watch.firedOn + due) to
            due.map { BulbEvent(HouseTrigger.TURNED_ON, it) }
    }

    /**
     * A light whose address is known to answer pings is pinged every tick; Matter is asked only
     * when the ping says something changed ([afterPing]). Without a trusted ping, Matter is asked
     * every [POLL_MILLIS].
     */
    fun check(now: Long, matterAt: Long?, pingable: Boolean): BulbCheck = when {
        pingable -> BulbCheck.PING
        matterAt == null || now - matterAt >= POLL_MILLIS -> BulbCheck.MATTER
        else -> BulbCheck.WAIT
    }

    /**
     * What a ping means. Lit and silent: the outage starts now, so `turned off` holds count from
     * the first missed ping (the runner retires the bulb's lost Matter session beside it). Dark
     * and answering: ask Matter at once, it is back. Lit and answering: ask Matter every
     * [POLL_MILLIS], for a flick too short for any ping to miss, but not while [othersDark]: that
     * ask would queue on the one Matter controller in front of the dark light's rules. Dark and
     * silent: still dark; never a Matter handshake, which hangs ~11 s at a dark bulb.
     */
    fun afterPing(watch: BulbWatch, now: Long, matterAt: Long?, answered: Boolean, othersDark: Boolean = false): BulbStep = when {
        watch.offlineSince == null && answered ->
            if (matterAt == null || (!othersDark && now - matterAt >= POLL_MILLIS)) BulbStep.MATTER else BulbStep.NOTHING
        answered -> BulbStep.MATTER
        else -> BulbStep.SILENT
    }

    /** The one switch to report from a poll's [events]: a quick gesture over the plain return it comes with. */
    fun shown(events: List<BulbEvent>): BulbEvent? =
        events.firstOrNull { it.trigger == HouseTrigger.OFF_AND_ON || it.trigger == HouseTrigger.ON_AND_OFF } ?: events.firstOrNull()

    /**
     * How long after the first missed ping to retire a dark bulb's lost session: just after the
     * longest `turned off` hold of 3 s or less, so that rule's steps never wait behind it on the
     * Matter controller. Bulbs need 5 s or more to come back, so the session is gone by then.
     */
    fun retireAfter(offHolds: Set<Long>): Long = offHolds.filter { it <= 3_000 }.maxOrNull()?.plus(DARK_TICK_MILLIS) ?: 0L

    /** Samsung left Wi-Fi: unanswered polls prove nothing, so the outage restarts; holds already run stay run. */
    fun offWifi(watch: BulbWatch): BulbWatch = watch.copy(offlineSince = null, upAtMiss = null)
}

/** Exact SDK v1.6.1.0 readAttributePath boundary for [BulbBoot]; no lasting subscription. */
internal suspend fun readMatterBoot(chip: ChipDeviceController, device: Long, timeoutMillis: Int): BulbBoot =
    suspendCancellableCoroutine { continuation ->
        val values = mutableMapOf<Long, ByteArray>()
        chip.readAttributePath(object : ReportCallback {
            override fun onError(attributePath: ChipAttributePath?, eventPath: ChipEventPath?, e: Exception) {
                // An unsupported UpTime does not invalidate the answer.
                if (attributePath == null && continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onReport(nodeState: NodeState) {
                val cluster = nodeState.getEndpointState(0)?.getClusterState(GENERAL_DIAGNOSTICS) ?: return
                listOf(REBOOT_COUNT, UP_TIME).forEach { attribute ->
                    cluster.getAttributeState(attribute)?.tlv?.let { values[attribute] = it }
                }
            }
            override fun onDone() {
                if (continuation.isActive) continuation.resume(bulbBoot(values[REBOOT_COUNT], values[UP_TIME]))
            }
        }, device, listOf(REBOOT_COUNT, UP_TIME).map { ChipAttributePath.newInstance(0, GENERAL_DIAGNOSTICS, it) },
            timeoutMillis)
    }

/** Exact SDK v1.6.1.0 write boundary for OnOff StartUpOnOff (endpoint 1, 0x0006/0x4003): 0 off, 1 on, null as before. */
internal suspend fun writeMatterStartUp(chip: ChipDeviceController, device: Long, value: Int?, timeoutMillis: Int): Unit =
    suspendCancellableCoroutine { continuation ->
        chip.write(object : WriteAttributesCallback {
            override fun onError(attributePath: ChipAttributePath?, e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(attributePath: ChipAttributePath, status: Status) {
                if (!continuation.isActive) return
                if (status.status == Status.Code.Success) continuation.resume(Unit)
                else continuation.resumeWithException(java.io.IOException("Power-on setting refused: " + status.status))
            }
        }, device, listOf(AttributeWriteRequest.newInstance(1, 6L, START_UP_ON_OFF, startUpTlv(value))), 0, timeoutMillis)
    }

internal fun startUpTlv(value: Int?): ByteArray =
    TlvWriter().let { if (value == null) it.putNull(AnonymousTag) else it.put(AnonymousTag, value.toUByte()) }.getEncoded()

/** Invalid TLV reads as not reported. */
internal fun bulbBoot(count: ByteArray?, upTime: ByteArray?) = BulbBoot(
    count?.let { runCatching { TlvReader(it).getUInt(AnonymousTag).toInt() }.getOrNull() } ?: -1,
    upTime?.let { runCatching { TlvReader(it).getULong(AnonymousTag).toLong() }.getOrNull() },
)

private const val START_UP_ON_OFF = 0x4003L
private const val GENERAL_DIAGNOSTICS = 0x33L
private const val REBOOT_COUNT = 1L
private const val UP_TIME = 2L
