package dev.homedj.app

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Device-local truth for the music schedule: app-private, atomically replaced JSON. Only the
 * Samsung schedule editor writes it; PlaybackService reads it whenever it reconciles.
 */
internal class ScheduleStore private constructor(context: Context) {
    companion object {
        private const val MAX_BYTES = 64_000

        @Volatile private var instance: ScheduleStore? = null
        fun shared(context: Context): ScheduleStore =
            instance ?: synchronized(this) {
                instance ?: ScheduleStore(context.applicationContext).also { instance = it }
            }
    }
    private val file = AtomicFile(File(context.applicationContext.filesDir, "music_schedule.json"))
    private val mutex = Mutex()
    private var cache: List<ScheduleSlot>? = null

    suspend fun read(): List<ScheduleSlot> = withContext(Dispatchers.IO) {
        mutex.withLock { snapshot() }
    }

    /** Validates the whole result before committing, so the stored schedule is always consistent. */
    suspend fun mutate(change: (List<ScheduleSlot>) -> List<ScheduleSlot>): List<ScheduleSlot> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val next = change(snapshot()).sortedBy(ScheduleSlot::startMinutes)
                SchedulePolicy.validate(next)?.let { throw IllegalArgumentException(it) }
                val bytes = ScheduleCodec.encode(next).toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_BYTES) throw IOException("Schedule exceeded storage limit")
                val output = file.startWrite()
                try {
                    output.write(bytes)
                    file.finishWrite(output)
                } catch (failure: Exception) {
                    file.failWrite(output)
                    throw failure
                }
                cache = next
                next
            }
        }

    private fun snapshot(): List<ScheduleSlot> {
        cache?.let { return it }
        val stored = try {
            if (!file.baseFile.exists()) emptyList()
            else if (file.baseFile.length() > MAX_BYTES) throw IOException("Oversized schedule")
            else ScheduleCodec.decode(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (failure: Exception) {
            Log.w("HomeDJ", "Schedule unreadable: " + failure.javaClass.simpleName)
            emptyList()
        }
        cache = stored
        return stored
    }
}
