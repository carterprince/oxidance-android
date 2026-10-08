package io.github.oxidance.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Matches the desktop app's serde encoding, so libraries sync between the two. */
@OptIn(ExperimentalSerializationApi::class)
val LibraryJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
    prettyPrint = true
    prettyPrintIndent = "  "
}

@Serializable
data class ArtistLink(val id: String, val name: String)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Song(
    @SerialName("video_id") val videoId: String,
    val title: String,
    val artist: String? = null,
    @SerialName("album_art_url") val albumArtUrl: String? = null,
    // Omitted when empty, like the desktop app; older saved songs have none.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val artists: List<ArtistLink> = emptyList(),
)

data class Artist(val link: ArtistLink, val imageUrl: String?)

data class Album(
    val id: String,
    val title: String,
    /** Kind, artist, and year as YouTube Music shows them, e.g. "Album • Piero Piccioni • 2022". */
    val subtitle: String,
    val imageUrl: String?,
)

data class AlbumPage(
    val album: Album,
    val artists: List<ArtistLink>,
    /** Kind, year, and length, e.g. "Album • 2022 · 40 songs • 2 hours, 6 minutes". */
    val details: String,
    val songs: List<Song>,
)

data class Profile(val artist: Artist, val biography: String?, val songs: List<Song>)

/** One search result: a suggestion or a full search result. */
sealed interface SearchItem {
    data class ArtistItem(val artist: Artist) : SearchItem
    data class AlbumItem(val album: Album) : SearchItem
    data class SongItem(val song: Song) : SearchItem

    /** The words a query is matched against: names, titles, and artists. */
    fun text(): String = when (this) {
        is ArtistItem -> artist.link.name
        is AlbumItem -> "${album.title} ${album.subtitle}"
        is SongItem -> "${song.title} ${song.artist ?: ""}"
    }

    /** Identifies the item across suggestions and full results. */
    val key: String get() = when (this) {
        is ArtistItem -> "artist:${artist.link.id}"
        is AlbumItem -> "album:${album.id}"
        is SongItem -> "song:${song.videoId}"
    }
}
