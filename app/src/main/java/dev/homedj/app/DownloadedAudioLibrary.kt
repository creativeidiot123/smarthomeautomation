package dev.homedj.app

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore

/**
 * MediaStore is the authority for discoverable shared-storage audio. Never construct file://
 * URIs or access Termux's private directory. This returns only indexed Music/HomeDJ audio.
 */
internal object DownloadedAudioLibrary {
    fun scan(context: Context): List<LocalAudio> {
        val columns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.RELATIVE_PATH,
        )
        val items = mutableListOf<LocalAudio>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            columns,
            MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?",
            arrayOf("Music/HomeDJ/%"),
            MediaStore.Audio.Media.RELATIVE_PATH + " ASC, " + MediaStore.Audio.Media.DISPLAY_NAME + " ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            while (cursor.moveToNext() && items.size < 10_000) {
                val path = cursor.getString(pathColumn).orEmpty()
                if (!path.startsWith("Music/HomeDJ/")) continue
                val uri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn),
                )
                items.add(LocalAudio(uri, cursor.getString(titleColumn).orEmpty().ifBlank { "Audio" }))
            }
        }
        return items
    }
}
