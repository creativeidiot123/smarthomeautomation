package dev.homedj.app

import kotlin.random.Random
import org.json.JSONObject

/**
 * Random silence between music sessions on Samsung. Minutes except the fade, which is in seconds.
 * The JSON field names are both the persisted format and the LAN wire format: never rename them.
 */
data class MusicBreakSettings(
    val enabled: Boolean = false,
    val musicMin: Int = 15,
    val musicMax: Int = 35,
    val longMusicMin: Int = 45,
    val longMusicMax: Int = 75,
    val longMusicChance: Int = 20,
    val silenceMin: Int = 10,
    val silenceMax: Int = 25,
    val longSilenceMin: Int = 30,
    val longSilenceMax: Int = 60,
    val longSilenceChance: Int = 20,
    val fadeMin: Int = 5,
    val fadeMax: Int = 10,
) {
    fun isValid(): Boolean =
        inRange(musicMin, musicMax, MUSIC) && inRange(longMusicMin, longMusicMax, LONG_MUSIC) &&
            inRange(silenceMin, silenceMax, SILENCE) && inRange(longSilenceMin, longSilenceMax, LONG_SILENCE) &&
            inRange(fadeMin, fadeMax, FADE_SECONDS) && longMusicChance in CHANCE && longSilenceChance in CHANCE

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("musicMin", musicMin).put("musicMax", musicMax)
        .put("longMusicMin", longMusicMin).put("longMusicMax", longMusicMax)
        .put("longMusicChance", longMusicChance)
        .put("silenceMin", silenceMin).put("silenceMax", silenceMax)
        .put("longSilenceMin", longSilenceMin).put("longSilenceMax", longSilenceMax)
        .put("longSilenceChance", longSilenceChance)
        .put("fadeMin", fadeMin).put("fadeMax", fadeMax)

    companion object {
        val MUSIC = 5..120
        val LONG_MUSIC = 15..240
        val SILENCE = 1..120
        val LONG_SILENCE = 5..240
        val FADE_SECONDS = 1..30
        val CHANCE = 0..100

        private fun inRange(min: Int, max: Int, bounds: IntRange) = min in bounds && max in bounds && min <= max

        /** Missing fields keep their defaults so either phone can run an older or newer build. */
        fun fromJson(json: JSONObject): MusicBreakSettings? {
            val default = MusicBreakSettings()
            return MusicBreakSettings(
                enabled = json.optBoolean("enabled", default.enabled),
                musicMin = json.optInt("musicMin", default.musicMin),
                musicMax = json.optInt("musicMax", default.musicMax),
                longMusicMin = json.optInt("longMusicMin", default.longMusicMin),
                longMusicMax = json.optInt("longMusicMax", default.longMusicMax),
                longMusicChance = json.optInt("longMusicChance", default.longMusicChance),
                silenceMin = json.optInt("silenceMin", default.silenceMin),
                silenceMax = json.optInt("silenceMax", default.silenceMax),
                longSilenceMin = json.optInt("longSilenceMin", default.longSilenceMin),
                longSilenceMax = json.optInt("longSilenceMax", default.longSilenceMax),
                longSilenceChance = json.optInt("longSilenceChance", default.longSilenceChance),
                fadeMin = json.optInt("fadeMin", default.fadeMin),
                fadeMax = json.optInt("fadeMax", default.fadeMax),
            ).takeIf(MusicBreakSettings::isValid)
        }
    }
}

/**
 * Music left before the next break, a projection of MusicBreaks: [millis] from [at]
 * (`elapsedRealtime`), counting down only while [counting]. Zero means after the current song.
 */
internal data class NextBreak(val millis: Long, val at: Long, val counting: Boolean)

/** Picks each music session, silence and fade length; [random] is injectable for tests. */
internal object MusicBreakPolicy {
    fun musicMillis(settings: MusicBreakSettings, random: Random): Long =
        if (random.nextInt(100) < settings.longMusicChance) {
            minutes(settings.longMusicMin, settings.longMusicMax, random)
        } else minutes(settings.musicMin, settings.musicMax, random)

    fun silenceMillis(settings: MusicBreakSettings, random: Random): Long =
        if (random.nextInt(100) < settings.longSilenceChance) {
            minutes(settings.longSilenceMin, settings.longSilenceMax, random)
        } else minutes(settings.silenceMin, settings.silenceMax, random)

    fun fadeMillis(settings: MusicBreakSettings, random: Random): Long =
        random.nextLong(settings.fadeMin * 1_000L, settings.fadeMax * 1_000L + 1)

    /** Whole seconds until a break ends, at least 1 while one runs; 0 means no break. */
    fun secondsLeft(resumeAt: Long?, now: Long): Long =
        if (resumeAt == null) 0 else ((resumeAt - now + 999) / 1_000).coerceAtLeast(1)

    /** Whole seconds of music before the next break at [now]; 0 means once the current song ends. */
    fun secondsUntilBreak(next: NextBreak, now: Long): Long {
        val left = if (next.counting) next.millis - (now - next.at) else next.millis
        return (left.coerceAtLeast(0) + 999) / 1_000
    }

    /** A queue tagged with a schedule slot instance resumes only while that same instance is active. */
    fun mayResume(queueSlotKey: String?, activeSlotKey: String?): Boolean =
        queueSlotKey == null || queueSlotKey == activeSlotKey

    private fun minutes(min: Int, max: Int, random: Random): Long =
        random.nextLong(min * 60_000L, max * 60_000L + 1)
}
