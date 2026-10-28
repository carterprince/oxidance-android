package io.github.oxidance

import io.github.oxidance.core.Dav
import io.github.oxidance.core.DiscordToken
import io.github.oxidance.core.Downloads
import io.github.oxidance.core.Library
import io.github.oxidance.core.Song
import io.github.oxidance.core.Sync
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the desktop app's sync test, against a temporary local rclone WebDAV server. */
class LiveSyncTest {
    @Test
    fun syncMergesLibrariesAndTransfersSongsBetweenDevices() {
        val root = Files.createTempDirectory("oxidance-sync").toFile()
        val served = File(root, "server").apply { mkdirs() }
        val port = ServerSocket(0).use { it.localPort }
        val server = ProcessBuilder("rclone", "serve", "webdav", served.path, "--addr", "127.0.0.1:$port",
            "--baseurl", "/oxidance", "--user", "test", "--pass", "secret").redirectErrorStream(true)
            .redirectOutput(File(root, "rclone.log")).start()
        try {
            // Entering only the host finds the oxidance folder, as on the real server.
            var dav: Dav? = null
            for (attempt in 0 until 50) {
                dav = runCatching { Dav.connect("http://127.0.0.1:$port", "test", "secret") }.getOrNull()
                if (dav != null) break
                Thread.sleep(100)
            }
            dav!!
            val url = dav.url.toString()
            assertEquals("/oxidance/", dav.url.encodedPath)
            assertTrue(runCatching { Dav.connect(url, "test", "wrong") }.exceptionOrNull()!!.message!!.contains("rejected"))
            fun song(id: String) = Song(id, "Title $id", "Artist")
            fun device(name: String) = File(root, name).apply { File(this, "Music").mkdirs() }
            fun cycle(library: Library, device: File) =
                Sync.cycle(dav, library, File(device, "sync-base.json"), File(device, "Music"), {}, {})
            fun base(device: File) = Sync.loadBase(File(device, "sync-base.json"), url)!!
            val first = device("first")
            val second = device("second")
            // First device has a downloaded song and a playlist.
            var (libraryOne, playlist) = Library().toggleLike(song("one")).createPlaylist("Mix")
            libraryOne = libraryOne.addSong(playlist, song("one"))!!
            File(first, "Music/Artist - Title one [one].webm").writeText("audio one")
            File(first, "Music/Artist - Title one [one].cover").writeText("cover one")
            assertEquals("Uploaded 1 song.", cycle(libraryOne, first))
            assertEquals("audio one", File(served, "songs/one.webm").readText())
            assertEquals("cover one", File(served, "songs/one.cover").readText())
            // Second device starts with its own like and receives the first device's library.
            cycle(Library().toggleLike(song("two")), second)
            val merged = base(second)
            assertTrue(merged.isLiked(song("one")) && merged.isLiked(song("two")))
            assertEquals(1, merged.playlists.size)
            // The second device downloads the song from the server, without YouTube.
            val audio = Downloads.download(File(second, "Music"), song("one"), dav, AtomicBoolean(false))
            assertEquals("Artist - Title one [one].webm", audio.name)
            assertEquals("audio one", audio.readText())
            assertArrayEquals("cover one".toByteArray(), File(second, "Music/Artist - Title one [one].cover").readBytes())
            // An unlike on the first device propagates to the second.
            cycle(base(first), first)
            val updated = base(first)
            assertTrue(updated.isLiked(song("two")))
            cycle(updated.toggleLike(song("two")), first)
            cycle(merged, second)
            assertFalse(base(second).isLiked(song("two")))
            // An emptied local library is treated as a fresh device, not a mass deletion.
            cycle(Library(), second)
            assertTrue(base(second).isLiked(song("one")))
            // The most recently changed Discord token wins, including a removal.
            val older = DiscordToken("old", 1)
            val newer = DiscordToken("new", 2)
            assertNull("nothing to share yet", Sync.syncDiscordToken(dav, DiscordToken("", 0)))
            assertFalse(File(served, "discord.json").exists())
            assertNull(Sync.syncDiscordToken(dav, older))
            assertNull(Sync.syncDiscordToken(dav, newer))
            assertEquals(newer, Sync.syncDiscordToken(dav, older))
            assertEquals("a new device receives it", newer, Sync.syncDiscordToken(dav, DiscordToken("", 0)))
            assertNull(Sync.syncDiscordToken(dav, newer))
            val removed = DiscordToken("", 3)
            assertNull(Sync.syncDiscordToken(dav, removed))
            assertEquals(removed, Sync.syncDiscordToken(dav, newer))
            // A brand-new device has no data directory yet.
            val fresh = File(root, "fresh/oxidance")
            Sync.cycle(dav, Library(), File(fresh, "sync-base.json"), File(root, "fresh/Music"), {}, {})
            assertTrue(Sync.loadBase(File(fresh, "sync-base.json"), url)!!.isLiked(song("one")))
            // The lock is released after each pass.
            dav.unlock("library.json", dav.lock("library.json"))
            // Leave the server running for a cross-check with the desktop app when asked.
            System.getProperty("keepServer")?.let { File(it).writeText("$port ${served.path}") ; Thread.sleep(Long.MAX_VALUE) }
        } finally {
            server.destroy()
            server.waitFor()
            root.deleteRecursively()
        }
    }
}
