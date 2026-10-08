package io.github.oxidance

import io.github.oxidance.core.Downloads
import io.github.oxidance.core.FileIndex
import io.github.oxidance.core.SearchItem
import io.github.oxidance.core.Streams
import io.github.oxidance.core.YtMusic
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Requires live YouTube Music access. */
class LiveStreamTest {
    @Test fun downloadsAudioAndArtworkQuickly() {
        val directory = Files.createTempDirectory("oxidance-download").toFile()
        val song = YtMusic().searchPage("nujabes aruarian dance").songs.first()
        val start = System.currentTimeMillis()
        val audio = Downloads.download(directory, song, null, AtomicBoolean(false))
        val seconds = (System.currentTimeMillis() - start) / 1000.0
        println("downloaded ${audio.name} (${audio.length()} bytes) in $seconds s")
        assertTrue(audio.name.endsWith("[${song.videoId}].webm") || audio.name.endsWith("[${song.videoId}].m4a"))
        assertTrue(audio.length() > 1_000_000)
        assertTrue("bounded ranges avoid YouTube's throttling", seconds < 30)
        assertTrue(FileIndex.scan(directory).art(song.videoId) != null)
        directory.deleteRecursively()
    }

    @Test fun resolvesFreshStreamUrls() {
        val source = Streams.resolve("qYcoJpqCha4")
        assertTrue(source.url.startsWith("https://"))
        assertTrue(source.durationSeconds > 200)
    }

    @Test fun suggestionsAndAlbum() {
        val client = YtMusic()
        val suggestions = client.suggestions("piero pi")
        assertTrue(suggestions.toString(), suggestions.any { it is SearchItem.ArtistItem && it.artist.link.name == "Piero Piccioni" })
        val albums = client.searchAlbums("piero piccioni")
        assertTrue(albums.isNotEmpty())
        val page = client.album(albums[0].id)
        assertTrue(page.songs.isNotEmpty())
        assertTrue(page.songs.all { it.artists.isNotEmpty() && it.albumArtUrl != null })
    }

    @Test fun artistSearchAndProfile() {
        val client = YtMusic()
        val artists = client.searchArtists("nujabes")
        assertEquals("nujabes", artists[0].link.name.lowercase())
        val profile = client.profile(artists[0].link.id)
        assertEquals("Nujabes", profile.artist.link.name)
        assertTrue(profile.artist.imageUrl != null)
        assertTrue(profile.biography != null)
        assertTrue("full top-songs list should extend the five-song preview", profile.songs.size > 5)
        assertTrue(profile.songs.all { it.artists.isNotEmpty() })
    }

    @Test fun searchPagesContinue() {
        val client = YtMusic()
        val first = client.searchPage("jazz")
        assertEquals(25, first.songs.size)
        val second = client.searchPage("jazz", first.next)
        assertTrue(second.songs.isNotEmpty())
        assertTrue("pages do not repeat songs", second.songs.none { song -> first.songs.any { it.videoId == song.videoId } })
    }
}
