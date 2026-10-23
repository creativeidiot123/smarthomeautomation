package dev.homedj.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Latest-wins sender for direct light changes made while a control moves: one send at a time,
 * and while one is out only the newest value per [key][offer] waits, in the order last touched.
 * The light follows the finger as fast as it answers and always ends on the released value.
 * Confined to [scope]'s single thread (the ViewModel's Main); [settled] runs once the queue drains.
 */
internal class LightLiveSender<T>(
    private val scope: CoroutineScope,
    private val send: suspend (T) -> Unit,
    private val settled: () -> Unit,
) {
    private val pending = LinkedHashMap<Any, T>()
    private var job: Job? = null

    val busy: Boolean get() = job?.isActive == true

    /** A newer value is still queued, so an older reply is not the owner's final state. */
    val hasPending: Boolean get() = pending.isNotEmpty()

    fun offer(key: Any, value: T) {
        // Re-inserted at the end: the last control touched is applied last ("off" after a drag stays off).
        pending.remove(key)
        pending[key] = value
        if (busy) return
        job = scope.launch {
            while (pending.isNotEmpty()) {
                val next = pending.keys.first()
                val value = pending.getValue(next)
                pending.remove(next)
                send(value)
            }
            settled()
        }
    }

    /** Suspends until every queued value was sent (values offered meanwhile included). */
    suspend fun idle() {
        while (busy) job?.join()
    }

    /** Role change: drop queued values and stop the one in flight. */
    fun cancel() {
        pending.clear()
        job?.cancel()
    }
}
