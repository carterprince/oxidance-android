package io.github.oxidance.core

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request

private val EXTENSIONS = setOf("webm", "m4a", "mp4", "ogg", "opus", "mp3", "aac", "flac", "wav")

/** Saved audio files by video ID, from a single scan of the music directory. Names match the desktop app. */
class FileIndex private constructor(private val files: Map<String, File>) {
    companion object {
        fun scan(directory: File): FileIndex {
            val files = mutableMapOf<String, File>()
            for (file in directory.listFiles().orEmpty()) {
                val id = audioId(file.name) ?: continue
                if (id !in files && file.isFile && file.length() > 0) files[id] = file
            }
            return FileIndex(files)
        }

        /** The video ID of a finished audio file named `… [ID].ext`. */
        fun audioId(name: String): String? {
            val extension = name.substringAfterLast('.', "")
            if (extension !in EXTENSIONS) return null
            val stem = name.substringBeforeLast('.').takeIf { it.endsWith("]") }?.dropLast(1) ?: return null
            if ('[' !in stem) return null
            return stem.substringAfterLast('[').ifEmpty { null }
        }
    }

    fun file(id: String): File? = files[id]

    fun art(id: String): File? = file(id)?.let { cover(it) }?.takeIf { it.isFile && it.length() > 0 }

    val ids: Set<String> get() = files.keys
}

/** `Song [id].webm` → `Song [id].cover`, like Rust's `with_extension`. */
fun cover(audio: File) = sibling(audio, "cover")
fun coverSource(audio: File) = sibling(audio, "cover.source")
private fun sibling(audio: File, extension: String) = File(audio.parentFile, audio.name.substringBeforeLast('.') + "." + extension)

fun localName(song: Song, extension: String): String {
    val stem = song.artist?.let { "$it - ${song.title}" } ?: song.title
    // Match yt-dlp's substitution for path separators, and stay well under filename limits.
    var safe = stem.map { if (it == '/' || it == '\u0000') '⧸' else it }.joinToString("")
    while (safe.toByteArray().size > 180) safe = safe.dropLast(1)
    return "$safe [${song.videoId}].$extension"
}

fun needsArtUpgrade(index: FileIndex, song: Song): Boolean {
    val url = song.albumArtUrl ?: return false
    val preferred = highQualityArtUrl(url)
    val audio = index.file(song.videoId) ?: return false
    return preferred != url && runCatching { coverSource(audio).readText() }.getOrNull() != preferred
}

/** Whether a saved song is missing its audio or artwork, or has outdated artwork. */
fun needsDownload(index: FileIndex, song: Song) = index.file(song.videoId) == null
    || song.albumArtUrl != null && index.art(song.videoId) == null || needsArtUpgrade(index, song)

/** Downloads saved songs: from the sync server when it has them, otherwise from YouTube. */
object Downloads {
    private const val CHUNK = 4L * 1024 * 1024
    private val artClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    private fun checkId(id: String) {
        if (id.isEmpty() || !id.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }) throw IOException("Invalid song ID")
    }

    fun download(directory: File, song: Song, remote: Dav?, cancelled: AtomicBoolean): File {
        checkId(song.videoId)
        // Prefer the sync server's copy; YouTube remains the fallback when it is missing or unreachable.
        if (FileIndex.scan(directory).file(song.videoId) == null && remote != null) {
            try { fetchFromServer(remote, directory, song, cancelled) } catch (error: IOException) {
                if (cancelled.get()) throw error
                android.util.Log.w("Oxidance", "Could not download ${song.videoId} from the sync server: ${error.message}")
            }
        }
        val audio = downloadAudio(directory, song, cancelled)
        try { saveArt(audio, song, cancelled) } catch (error: IOException) {
            throw IOException("Audio is saved, but album art failed: ${error.message}")
        }
        // Failures are retried and reported by the next sync.
        if (remote != null) runCatching { uploadToServer(remote, audio, song, remote.songs(false)) }
        return audio
    }

    private fun downloadAudio(directory: File, song: Song, cancelled: AtomicBoolean): File {
        FileIndex.scan(directory).file(song.videoId)?.let { return it }
        directory.mkdirs()
        // YouTube intermittently rejects a download URL (for example, HTTP 403); each attempt resolves fresh URLs.
        return withRetries(3, 2000, cancelled) {
            val source = Streams.resolve(song.videoId)
            val destination = File(directory, localName(song, source.extension))
            val temporary = File(directory, destination.name + ".part")
            try {
                fetchRanges(source.url, temporary, cancelled)
                if (!temporary.renameTo(destination)) throw IOException("Could not save ${destination.name}")
            } catch (error: IOException) {
                temporary.delete()
                throw error
            }
            destination
        }
    }

    /** Fetches in bounded ranges: YouTube throttles open-ended requests to about twice real time. */
    private fun fetchRanges(url: String, destination: File, cancelled: AtomicBoolean) {
        destination.outputStream().use { output ->
            var position = 0L
            var total = Long.MAX_VALUE
            while (position < total) {
                if (cancelled.get()) throw IOException("Download cancelled")
                val end = minOf(position + CHUNK, total) - 1
                val request = Request.Builder().url(url).header("Range", "bytes=$position-$end").build()
                Streams.mediaClient.newCall(request).execute().use { response ->
                    if (response.code != 206 && response.code != 200) throw IOException("YouTube returned HTTP ${response.code}")
                    total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                        ?: response.body.contentLength().takeIf { response.code == 200 && it > 0 } ?: throw IOException("YouTube returned no length")
                    val bytes = response.body.bytes()
                    if (bytes.isEmpty()) throw IOException("YouTube returned an empty response")
                    output.write(bytes)
                    position += bytes.size
                }
            }
            output.fd.sync()
        }
    }

    private fun isImage(bytes: ByteArray): Boolean {
        fun starts(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
        return starts(0xFF, 0xD8, 0xFF) || starts(0x89, 0x50, 0x4E, 0x47) || starts(0x52, 0x49, 0x46, 0x46) && bytes.size > 12
            && String(bytes, 8, 4) == "WEBP" || starts(0x47, 0x49, 0x46)
    }

    private fun saveArt(audio: File, song: Song, cancelled: AtomicBoolean) {
        val url = song.albumArtUrl ?: return
        val path = cover(audio)
        val preferred = highQualityArtUrl(url)
        val source = coverSource(audio)
        val existing = path.length() > 0
        if (existing && (preferred == url || runCatching { source.readText() }.getOrNull() == preferred)) return
        if (cancelled.get()) throw IOException("Download cancelled")
        fun fetch(address: String): ByteArray = artClient.newCall(Request.Builder().url(address).build()).execute().use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            it.body.bytes()
        }
        val bytes = try { runCatching { fetch(preferred) }.getOrElse { fetch(url) } } catch (error: IOException) {
            if (existing) return // Keep the usable offline cover when disconnected.
            throw error
        }
        // Validate the image before making it available to offline playback.
        if (!isImage(bytes)) throw IOException("Album art is not an image")
        if (cancelled.get()) throw IOException("Download cancelled")
        val temporary = sibling(audio, "cover.part")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(path)) throw IOException("Could not save album art")
        source.writeText(preferred)
    }

    private fun remoteAudio(listing: Map<String, Long>, id: String): String? = listing.keys.firstOrNull { name ->
        name.startsWith("$id.") && name.removePrefix("$id.") in EXTENSIONS
    }

    private fun fetchFromServer(dav: Dav, directory: File, song: Song, cancelled: AtomicBoolean) {
        val listing = dav.songs(false)
        val name = remoteAudio(listing, song.videoId) ?: return
        directory.mkdirs()
        val audio = File(directory, localName(song, name.substringAfterLast('.')))
        dav.download("${Dav.SONGS}$name", audio, cancelled)
        for (suffix in listOf("cover", "cover.source")) {
            val remote = "${song.videoId}.$suffix"
            // Artwork is optional here; saveArt fetches it from the web if this fails.
            if (remote in listing) runCatching { dav.download("${Dav.SONGS}$remote", sibling(audio, suffix), cancelled) }
        }
    }

    /** Uploads a saved song's audio and artwork if the server is missing them. Returns whether anything was uploaded. */
    fun uploadToServer(dav: Dav, audio: File, song: Song, listing: Map<String, Long>): Boolean {
        val id = song.videoId
        var uploaded = false
        if (remoteAudio(listing, id) == null) {
            dav.upload("${Dav.SONGS}$id.${audio.name.substringAfterLast('.')}", audio)
            uploaded = true
        }
        val cover = cover(audio)
        val source = coverSource(audio)
        val localSource = runCatching { source.readText() }.getOrNull()
        if (localSource == null) {
            if (cover.isFile && "$id.cover" !in listing) {
                dav.upload("${Dav.SONGS}$id.cover", cover)
                uploaded = true
            }
            return uploaded
        }
        // Replace remote artwork only with the preferred high-quality version, so
        // devices with older artwork do not keep overwriting each other.
        val preferred = song.albumArtUrl?.let { highQualityArtUrl(it) == localSource } == true
        val remoteSource = listing["$id.cover.source"]
        if (cover.isFile && ("$id.cover" !in listing || preferred && remoteSource != localSource.toByteArray().size.toLong())) {
            dav.upload("${Dav.SONGS}$id.cover", cover)
            dav.upload("${Dav.SONGS}$id.cover.source", source)
            uploaded = true
        }
        return uploaded
    }
}

/** Runs `attempt` up to `attempts` times, stopping early on success or cancellation. */
fun <T> withRetries(attempts: Int, delayMs: Long, cancelled: AtomicBoolean, attempt: () -> T): T {
    var tries = 1
    while (true) {
        try { return attempt() } catch (error: IOException) {
            if (tries >= attempts || cancelled.get()) throw error
            android.util.Log.w("Oxidance", "Attempt $tries of $attempts failed, retrying: ${error.message}")
            Thread.sleep(delayMs)
            tries++
        }
    }
}
