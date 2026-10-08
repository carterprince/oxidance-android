package io.github.oxidance.core

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class LibraryException(message: String) : Exception(message)

@Serializable
data class Playlist(
    val id: Long,
    val name: String,
    @SerialName("modified_at") val modifiedAt: Long,
    val songs: List<Song>,
)

/**
 * Liked songs and playlists. Each change returns a new library; the format and
 * merge rules match the desktop app's `library.rs`.
 */
@Serializable
data class Library(
    val liked: List<Song> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val revision: Long = 0,
) {
    fun save(file: File) {
        val parent = file.parentFile ?: throw IOException("Missing library directory")
        parent.mkdirs()
        // Replace atomically so a partial write does not destroy the saved library.
        val temporary = File(parent, file.name + ".tmp")
        temporary.outputStream().use { stream ->
            stream.write(LibraryJson.encodeToString(serializer(), this).toByteArray())
            stream.fd.sync()
        }
        if (!temporary.renameTo(file)) throw IOException("Could not replace ${file.name}")
    }

    private fun tick(): Library = copy(revision = maxOf(System.currentTimeMillis(), revision + 1))

    fun isLiked(song: Song) = liked.any { it.videoId == song.videoId }

    fun toggleLike(song: Song): Library =
        if (isLiked(song)) copy(liked = liked.filter { it.videoId != song.videoId })
        else copy(liked = listOf(song) + liked)

    private fun index(id: Long): Int =
        playlists.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: throw LibraryException("Playlist no longer exists.")

    private fun replace(index: Int, playlist: Playlist) = playlists.toMutableList().also { it[index] = playlist }

    /** Returns the new library and the playlist's ID. */
    fun createPlaylist(name: String): Pair<Library, Long> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LibraryException("Enter a playlist name.")
        if (playlists.any { it.name.lowercase() == trimmed.lowercase() }) throw LibraryException("A playlist with that name already exists.")
        val next = tick()
        val id = next.revision
        return next.copy(playlists = playlists + Playlist(id, trimmed, id, emptyList())) to id
    }

    /** Returns null when the song is already in the playlist. */
    fun addSong(playlistId: Long, song: Song): Library? {
        val index = index(playlistId)
        val playlist = playlists[index]
        if (playlist.songs.any { it.videoId == song.videoId }) return null
        val next = tick()
        return next.copy(playlists = replace(index, playlist.copy(songs = playlist.songs + song, modifiedAt = next.revision)))
    }

    fun removeSong(playlistId: Long, song: Song): Library {
        val index = index(playlistId)
        val playlist = playlists[index]
        if (playlist.songs.none { it.videoId == song.videoId }) return this
        val next = tick()
        return next.copy(playlists = replace(index, playlist.copy(songs = playlist.songs.filter { it.videoId != song.videoId }, modifiedAt = next.revision)))
    }

    fun renamePlaylist(id: Long, name: String): Library {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw LibraryException("Enter a playlist name.")
        if (playlists.any { it.id != id && it.name.lowercase() == trimmed.lowercase() }) throw LibraryException("A playlist with that name already exists.")
        val index = index(id)
        if (playlists[index].name == trimmed) return this
        val next = tick()
        return next.copy(playlists = replace(index, playlists[index].copy(name = trimmed, modifiedAt = next.revision)))
    }

    fun deletePlaylist(id: Long): Library {
        val index = index(id)
        return copy(playlists = playlists.toMutableList().also { it.removeAt(index) })
    }

    fun sortedPlaylists(): List<Playlist> = playlists.sortedByDescending { it.modifiedAt }

    fun isEmpty() = liked.isEmpty() && playlists.isEmpty()

    /** Liked and playlist songs, each once. */
    fun savedSongs(): List<Song> = (liked + playlists.flatMap { it.songs }).distinctBy { it.videoId }

    companion object {
        fun load(file: File): Library = try {
            LibraryJson.decodeFromString(serializer(), file.readText())
        } catch (_: FileNotFoundException) {
            Library()
        }

        /**
         * Three-way merge of two libraries that diverged from [base], the last synced state.
         * Without a base, both sides are combined and nothing is treated as deleted.
         * Removals on either side win over unchanged entries; additions on both sides are kept.
         */
        fun merge(base: Library?, local: Library, remote: Library): Library {
            val origin = base ?: Library()
            val merged = mutableListOf<Playlist>()
            for (id in (local.playlists + remote.playlists).map { it.id }) {
                if (merged.any { it.id == id }) continue
                val b = origin.playlists.find { it.id == id }
                val l = local.playlists.find { it.id == id }
                val r = remote.playlists.find { it.id == id }
                when {
                    b != null && (l == null || r == null) -> {} // Deleted on one side.
                    l != null && r == null -> merged += l
                    l == null && r != null -> merged += r
                    l != null && r != null -> {
                        val localRenamed = b == null || b.name != l.name
                        val remoteRenamed = b == null || b.name != r.name
                        val name = if (remoteRenamed && (!localRenamed || r.modifiedAt > l.modifiedAt)) r.name else l.name
                        merged += Playlist(id, name, maxOf(l.modifiedAt, r.modifiedAt), mergeSongs(b?.songs ?: emptyList(), l.songs, r.songs))
                    }
                }
            }
            // Playlists created separately on two devices can share a name; keep names unique.
            merged.sortBy { it.id }
            for (index in merged.indices) {
                fun taken(name: String, among: List<Playlist>) = among.any { it.name.lowercase() == name.lowercase() }
                if (!taken(merged[index].name, merged.subList(0, index))) continue
                val original = merged[index].name
                val name = generateSequence(2) { it + 1 }.map { "$original ($it)" }.first { !taken(it, merged) }
                merged[index] = merged[index].copy(name = name)
            }
            return Library(mergeSongs(origin.liked, local.liked, remote.liked), merged, maxOf(local.revision, remote.revision))
        }

        private fun mergeSongs(base: List<Song>, local: List<Song>, remote: List<Song>): List<Song> {
            fun contains(list: List<Song>, id: String) = list.any { it.videoId == id }
            fun keep(song: Song) = !contains(base, song.videoId) || contains(local, song.videoId) && contains(remote, song.videoId)
            val merged = remote.filter(::keep).map { song -> local.find { it.videoId == song.videoId } ?: song }.toMutableList()
            // Place local additions next to their nearest local neighbour that is kept.
            for ((index, song) in local.withIndex()) {
                if (contains(merged, song.videoId) || !keep(song)) continue
                fun find(other: Song) = merged.indexOfFirst { it.videoId == other.videoId }.takeIf { it >= 0 }
                val position = local.subList(0, index).asReversed().firstNotNullOfOrNull(::find)?.plus(1)
                    ?: local.subList(index + 1, local.size).firstNotNullOfOrNull(::find)
                    ?: merged.size
                merged.add(position, song)
            }
            return merged
        }
    }
}
