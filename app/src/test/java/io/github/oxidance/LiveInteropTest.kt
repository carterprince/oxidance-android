package io.github.oxidance

import io.github.oxidance.core.Dav
import io.github.oxidance.core.Library
import io.github.oxidance.core.Song
import io.github.oxidance.core.ArtistLink
import io.github.oxidance.core.Sync
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Two halves of a round trip with the desktop app through one server (-PdavUrl=…):
 * Android writes, the desktop reads and changes the library, then Android reads it back.
 */
class LiveInteropTest {
    private val url = System.getProperty("davUrl")
    private val device = File(System.getProperty("java.io.tmpdir"), "oxidance-interop-device")

    private fun dav(): Dav { assumeTrue(url != null); return Dav.connect(url!!, "test", "secret") }

    @Test fun androidWrites() {
        val dav = dav()
        device.deleteRecursively()
        val music = File(device, "Music").apply { mkdirs() }
        val song = Song("android", "From Android", "Artist", "https://lh3.googleusercontent.com/x=w120-h120-l90-rj", listOf(ArtistLink("UCa", "Artist")))
        File(music, "Artist - From Android [android].webm").writeText("audio")
        File(music, "Artist - From Android [android].cover").writeText("cover")
        File(music, "Artist - From Android [android].cover.source").writeText("https://lh3.googleusercontent.com/x=w1200-h1200-l90-rj")
        val (library, id) = Library().toggleLike(song).createPlaylist("Android mix")
        println(Sync.cycle(dav, library.addSong(id, song)!!, File(device, "sync-base.json"), music, {}, {}))
    }

    @Test fun androidReadsDesktopChanges() {
        val dav = dav()
        val base = Sync.loadBase(File(device, "sync-base.json"), dav.url.toString())!!
        Sync.cycle(dav, base, File(device, "sync-base.json"), File(device, "Music"), {}, {})
        val merged = Sync.loadBase(File(device, "sync-base.json"), dav.url.toString())!!
        println("android read: ${merged.liked.map { it.videoId }} ${merged.playlists.map { it.name to it.songs.map { s -> s.videoId } }}")
        assertEquals(listOf("desktop", "android"), merged.liked.map { it.videoId })
        assertTrue(merged.playlists.any { it.name == "Desktop mix" && it.songs.single().videoId == "android" })
        assertTrue(merged.playlists.any { it.name == "Android mix" })
    }
}
