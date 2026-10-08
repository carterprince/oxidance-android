package io.github.oxidance.core

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The library as last synced with a particular server, used as the merge base. Same file as the desktop app. */
@Serializable
private data class Base(val url: String, val library: Library)

/** Merges the library and transfers songs through a WebDAV server, compatible with the desktop app. */
object Sync {
    private const val LIBRARY = "library.json"
    private val compact = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun loadBase(file: File, url: String): Library? = try {
        compact.decodeFromString(Base.serializer(), file.readText()).takeIf { it.url == url }?.library
    } catch (_: FileNotFoundException) {
        null
    } catch (_: Exception) {
        throw SyncException("Sync history is damaged. Disconnect and reconnect to sync again.")
    }

    fun saveBase(file: File, url: String, library: Library) {
        // A new device has no data directory until its library is first saved.
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(compact.encodeToString(Base.serializer(), Base(url, library)))
        if (!temporary.renameTo(file)) throw IOException("Could not save sync history")
    }

    /**
     * One sync pass: merge the library under a server lock, then upload songs the server lacks.
     * [onMerged] receives the merged library before songs are uploaded; songs the device lacks
     * are fetched by the download queue once it is applied. Returns a summary.
     */
    fun cycle(dav: Dav, local: Library, baseFile: File, music: File, onMerged: (Library) -> Unit, onProgress: (String) -> Unit): String {
        val url = dav.url.toString()
        val base = loadBase(baseFile, url)
        dav.mkcol(Dav.SONGS)
        val token = dav.lock(LIBRARY)
        val merged = try {
            val remote = dav.get(LIBRARY)?.takeIf { it.isNotEmpty() }?.let {
                try { LibraryJson.decodeFromString(Library.serializer(), String(it)) }
                catch (_: Exception) { throw SyncException("The library on the sync server is damaged, so it was left unchanged.") }
            }
            // A missing remote library or an emptied local one must not be read as
            // "everything was deleted"; combine both sides instead.
            val usableBase = base?.takeIf { remote != null && !local.isEmpty() }
            val merged = Library.merge(usableBase, local, remote ?: Library())
            if (remote != merged) dav.put(LIBRARY, LibraryJson.encodeToString(Library.serializer(), merged).toByteArray(), token)
            merged
        } finally {
            runCatching { dav.unlock(LIBRARY, token) }
        }
        saveBase(baseFile, url, merged)
        // Refresh the server's song list first, so downloads queued for the merged
        // library find songs other devices uploaded instead of fetching them from YouTube.
        val listing = dav.songs(true)
        onMerged(merged)
        val songs = merged.savedSongs()
        val files = FileIndex.scan(music)
        var uploaded = 0
        for ((index, song) in songs.withIndex()) {
            onProgress("Checking saved songs (${index + 1} of ${songs.size})…")
            val audio = files.file(song.videoId) ?: continue
            if (Downloads.uploadToServer(dav, audio, song, listing)) uploaded++
        }
        return when (uploaded) {
            0 -> "Library and songs are up to date."
            1 -> "Uploaded 1 song."
            else -> "Uploaded $uploaded songs."
        }
    }
}
