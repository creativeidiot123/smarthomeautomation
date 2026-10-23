package dev.homedj.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.Socket
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAudioCastTest {
    @Test fun castMutesMediaOnlyDuringFirstAcceptedPacket() {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val service = File(repo, "app/src/main/java/dev/homedj/app/LiveAudioCastService.kt").readText()
        val stream = service.substringAfter("RemoteChannel.streamAudio(")
        val firstPacket = stream.substringAfter("if (!started) {").substringBefore("frame.copyOf")
        val mute = ".setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)"
        assertTrue("Only a server-accepted stream's first packet may mute local media",
            firstPacket.contains(mute))
        assertTrue("The mute must complete before publishing the cast as active",
            firstPacket.indexOf(mute) < firstPacket.indexOf("started = true"))
        assertTrue("Manual changes and stopping must never trigger another volume write",
            service.split(".setStreamVolume(").size == 2)
    }

    @Test fun sinkLowersAndroidsStartThresholdBelowItsPacketDropCap() {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val sink = File(repo, "app/src/main/java/dev/homedj/app/LiveAudioSink.kt").readText()
        val setup = sink.substringAfter("track = output").substringBefore("var started = false")
        // A default 500 ms threshold cannot be reached when packets are dropped at the queue cap.
        val packetFrames = LiveAudioFormat.CHUNK_BYTES / LiveAudioFormat.FRAME_BYTES
        val queued = generateSequence(0) { it + packetFrames }
            .first { LiveAudioSink.trimsPacket(it, LiveAudioSink.MIN_TARGET_FRAMES, trimming = false) }
        assertTrue(queued < LiveAudioFormat.SAMPLE_RATE / 2)
        assertTrue("API 31+ must lower the startup threshold before applying the packet cap",
            setup.contains("Build.VERSION.SDK_INT >= 31") &&
                setup.contains("output.setStartThresholdInFrames(MIN_TARGET_FRAMES)"))
        assertTrue("API 29/30 must lower the effective buffer to make startup reachable",
            setup.contains("output.setBufferSizeInFrames(MIN_TARGET_FRAMES)"))
        assertTrue("Reject a platform threshold that cannot be reached under the queue cap",
            setup.contains("threshold !in 1..MIN_TARGET_FRAMES + SLACK_FRAMES"))
    }

    @Test fun notificationOnlyPermissionResultDoesNotBlockPreviouslyGrantedAudioCapture() {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val activity = File(repo, "app/src/main/java/dev/homedj/app/HomeDjActivity.kt").readText()
        val callback = activity.substringAfter("private val castPermission =")
            .substringBefore("private val castConsent =")
        // requestCast requests only missing permissions. When only notifications are missing,
        // RECORD_AUDIO is absent from the result even though Android still grants capture.
        assertTrue("The callback must re-read Android's audio grant, independently of notifications",
            Regex("""checkSelfPermission\(Manifest\.permission\.RECORD_AUDIO\)\s*==\s*PackageManager\.PERMISSION_GRANTED""")
                .containsMatchIn(callback))
        assertFalse("An omitted permission result is not a denial",
            callback.contains("results[Manifest.permission.RECORD_AUDIO]"))
    }

    @Test fun shutdownClosesCurrentSocketAndIsIdempotent() {
        val lease = CastSocketLease()
        val active = Socket()
        lease.attach(active)
        assertFalse(active.isClosed)

        lease.close()
        lease.close()
        assertTrue(active.isClosed)
    }

    @Test fun shutdownBeforeSocketPublicationClosesAndRejectsLateSocket() {
        val lease = CastSocketLease()
        lease.close()
        val late = Socket()
        assertThrows(IOException::class.java) { lease.attach(late) }
        assertTrue(late.isClosed)
    }

    @Test fun sinkKeepsJitterBelowCapAndTrimsBackToTarget() {
        val ms = LiveAudioFormat.SAMPLE_RATE / 1000
        val target = 60 * ms
        assertFalse(LiveAudioSink.trimsPacket(0, target, trimming = false))
        assertFalse("Jitter within 40 ms of the target is kept", LiveAudioSink.trimsPacket(100 * ms, target, false))
        assertTrue(LiveAudioSink.trimsPacket(101 * ms, target, trimming = false))
        assertTrue("Once trimming, packets drop until the target", LiveAudioSink.trimsPacket(80 * ms, target, true))
        assertFalse(LiveAudioSink.trimsPacket(60 * ms, target, trimming = true))
        // The written-minus-played difference stays exact when both counters wrap.
        assertFalse(LiveAudioSink.trimsPacket(Int.MIN_VALUE + 100 - (Int.MAX_VALUE - 100), target, false))
    }

    @Test fun burstAfterWifiStallDoesNotLeaveSpeakersBehind() {
        // Regression: the old 400 ms cap kept a whole 300 ms burst, and the delay never shrank.
        val target = LiveAudioSink.MIN_TARGET_FRAMES
        val packet = LiveAudioFormat.SEND_BYTES / LiveAudioFormat.FRAME_BYTES
        val ms = LiveAudioFormat.SAMPLE_RATE / 1000
        var queued = 0 // The track ran dry during the stall.
        var trimming = false
        fun arrive() {
            trimming = LiveAudioSink.trimsPacket(queued, target, trimming)
            if (!trimming) queued += packet
        }
        repeat(30) { arrive() } // 300 ms of backlog arrives at once.
        assertTrue("A burst may not queue beyond target + slack", queued <= target + 40 * ms + packet)
        repeat(20) { queued -= packet; arrive() } // Real time: one packet played per packet received.
        assertTrue("The queue must settle back at the target", queued <= target + packet)
    }

    @Test fun queueTargetGrowsOnDropoutsAndRelaxesWhenWifiIsCalm() {
        val min = LiveAudioSink.MIN_TARGET_FRAMES
        val ms = LiveAudioFormat.SAMPLE_RATE / 1000
        assertTrue("Good Wi-Fi keeps the low target", LiveAudioSink.adaptedTarget(min, false, 19_999) == min)
        val grown = LiveAudioSink.adaptedTarget(min, underran = true, calmMillis = 0)
        assertTrue("A dropout buys more buffer", grown == min + 20 * ms)
        assertTrue("A calm stretch gives latency back", LiveAudioSink.adaptedTarget(grown, false, 20_000) == min + 10 * ms)
        assertTrue(LiveAudioSink.adaptedTarget(min, false, 60_000) == min)
        val worst = generateSequence(min) { LiveAudioSink.adaptedTarget(it, true, 0) }.elementAt(50)
        assertTrue("Bad Wi-Fi is capped", worst == LiveAudioSink.MAX_TARGET_FRAMES)
        assertTrue(LiveAudioSink.MAX_TARGET_FRAMES + 40 * ms < LiveAudioFormat.SAMPLE_RATE / 2)
    }

    @Test fun onePlusSendsSmallPacketsOlderSamsungBuildsAccept() {
        assertTrue(LiveAudioFormat.validPacket(LiveAudioFormat.SEND_BYTES))
        assertTrue(LiveAudioFormat.SEND_BYTES < LiveAudioFormat.CHUNK_BYTES)
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val stream = File(repo, "app/src/main/java/dev/homedj/app/RemoteChannel.kt").readText()
            .substringAfter("fun streamAudio(").substringBefore("val challenge")
        assertTrue("A fixed send buffer must bound stale PCM before the connection starts",
            stream.indexOf("socket.sendBufferSize = AUDIO_SEND_BUFFER") in 0 until stream.indexOf("socket.connect("))
    }

    @Test fun onlyAlignedBoundedPcmPacketsAreAccepted() {
        assertTrue(LiveAudioFormat.validPacket(4))
        assertTrue(LiveAudioFormat.validPacket(LiveAudioFormat.CHUNK_BYTES))
        listOf(-1, 0, 1, 3, 5, LiveAudioFormat.CHUNK_BYTES + 4)
            .forEach { assertFalse(LiveAudioFormat.validPacket(it)) }
    }

    @Test fun framesAreEncryptedAuthenticatedAndCorruptionIsRejected() {
        val key = RemoteChannel.decodeKey(RemoteChannel.generateKey())!!
        val challenge = ByteArray(16) { (it + 2).toByte() }
        val pcm = ByteArray(LiveAudioFormat.CHUNK_BYTES) { (it * 31).toByte() }
        val out = ByteArrayOutputStream()
        RemoteChannel.sendAudio(DataOutputStream(out), key, challenge, 0, pcm)
        assertArrayEquals(pcm, RemoteChannel.receiveAudio(
            DataInputStream(ByteArrayInputStream(out.toByteArray())), key, challenge, 0
        ))
        val tampered = out.toByteArray().also {
            it[it.lastIndex] = (it.last().toInt() xor 0x40).toByte()
        }
        assertThrows(AEADBadTagException::class.java) {
            RemoteChannel.receiveAudio(DataInputStream(ByteArrayInputStream(tampered)), key, challenge, 0)
        }
        assertThrows(AEADBadTagException::class.java) {
            RemoteChannel.receiveAudio(
                DataInputStream(ByteArrayInputStream(out.toByteArray())), key, ByteArray(16), 0
            )
        }
    }

    @Test fun replayedDroppedOrReorderedFramesAreRejected() {
        val key = RemoteChannel.decodeKey(RemoteChannel.generateKey())!!
        val challenge = ByteArray(16) { it.toByte() }
        val frames = (0L..1L).map { sequence ->
            ByteArrayOutputStream().also {
                RemoteChannel.sendAudio(DataOutputStream(it), key, challenge, sequence, ByteArray(8))
            }.toByteArray()
        }
        val received = DataInputStream(ByteArrayInputStream(frames[0] + frames[1]))
        RemoteChannel.receiveAudio(received, key, challenge, 0)
        RemoteChannel.receiveAudio(received, key, challenge, 1)
        // Frame 0 replayed (or frame 1 arriving first) in position 1 fails authentication.
        assertThrows(AEADBadTagException::class.java) {
            RemoteChannel.receiveAudio(DataInputStream(ByteArrayInputStream(frames[0])), key, challenge, 1)
        }
        assertThrows(AEADBadTagException::class.java) {
            RemoteChannel.receiveAudio(DataInputStream(ByteArrayInputStream(frames[1])), key, challenge, 0)
        }
        // A command frame cannot be spliced into the audio stream.
        val command = ByteArrayOutputStream().also {
            RemoteChannel.send(DataOutputStream(it), key, challenge, org.json.JSONObject().put("a", "bcde"))
        }.toByteArray()
        assertThrows(AEADBadTagException::class.java) {
            RemoteChannel.receiveAudio(DataInputStream(ByteArrayInputStream(command)), key, challenge, 0)
        }
    }

    @Test fun rejectsMalformedAudioFrames() {
        val key = RemoteChannel.decodeKey(RemoteChannel.generateKey())!!
        val challenge = ByteArray(16)
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChannel.sendAudio(DataOutputStream(ByteArrayOutputStream()), key, challenge, 0, ByteArray(7))
        }
        val truncated = ByteArrayOutputStream()
        DataOutputStream(truncated).writeInt(100_000)
        assertThrows(IllegalArgumentException::class.java) {
            RemoteChannel.receiveAudio(
                DataInputStream(ByteArrayInputStream(truncated.toByteArray())), key, challenge, 0
            )
        }
    }
}
