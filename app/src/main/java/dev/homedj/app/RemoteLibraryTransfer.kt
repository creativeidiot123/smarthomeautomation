package dev.homedj.app

import org.json.JSONArray
import org.json.JSONObject

/** Fill the existing reply, leaving room below the 64-KiB encrypted frame bound. */
internal fun <T> remoteLibraryPage(items: List<T>, offset: Int, make: (T) -> JSONObject): JSONObject {
    val array = JSONArray()
    val reply = JSONObject().put("ok", true).put("items", array).put("total", items.size).put("offset", offset)
    var bytes = reply.toString().toByteArray(Charsets.UTF_8).size
    for (index in offset until items.size) {
        val item = make(items[index])
        val size = item.toString().toByteArray(Charsets.UTF_8).size + if (array.length() == 0) 0 else 1
        if (bytes + size > 60_000) {
            if (array.length() == 0) return JSONObject().put("ok", false).put("error", "Library item too large")
            break
        }
        array.put(item)
        bytes += size
    }
    return reply
}
