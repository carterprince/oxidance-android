package io.github.oxidance

import android.app.Application
import android.content.Context
import android.os.Environment
import android.util.Log
import io.github.oxidance.core.Downloads
import io.github.oxidance.core.FileIndex
import io.github.oxidance.core.Library
import io.github.oxidance.core.LibraryException
import io.github.oxidance.core.LibraryJson
import io.github.oxidance.core.Song
import io.github.oxidance.core.Streams
import io.github.oxidance.core.YtMusic
import io.github.oxidance.core.needsDownload
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** App-wide state shared by the UI and the playback service. */
class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val youtube = YtMusic()
    lateinit var settings: Settings private set
    lateinit var library: LibraryStore private set
    lateinit var downloads: DownloadQueue private set
    lateinit var sync: SyncManager private set
    lateinit var discord: DiscordPresence private set
    /** Short messages for the UI to show as snackbars. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Songs are saved where the desktop app's names apply, in the app's own Music folder. */
    val musicDirectory: File by lazy { getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(filesDir, "Music") }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        library = LibraryStore(File(filesDir, "library.json"), this)
        sync = SyncManager(this)
        downloads = DownloadQueue(this)
        discord = DiscordPresence(this)
        scope.launch(Dispatchers.IO) { Streams.init() }
        downloads.resume()
        sync.restore()
        discord.restore()
    }

    fun toast(message: String) { messages.tryEmit(message) }

    companion object {
        lateinit var instance: App private set
    }
}

class Settings(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var playbackSpeed: Float
        get() = preferences.getFloat("playback-speed", 1f)
        set(value) = preferences.edit().putFloat("playback-speed", value.coerceIn(0.5f, 2f)).apply()
    var shuffle: Boolean
        get() = preferences.getBoolean("shuffle", false)
        set(value) = preferences.edit().putBoolean("shuffle", value).apply()
    var lastSong: Song?
        get() = preferences.getString("last-song", null)?.let { runCatching { LibraryJson.decodeFromString(Song.serializer(), it) }.getOrNull() }
        set(value) = preferences.edit().putString("last-song", value?.let { LibraryJson.encodeToString(Song.serializer(), it) }).apply()
    var syncUrl: String
        get() = preferences.getString("sync-url", "") ?: ""
        set(value) = preferences.edit().putString("sync-url", value).apply()
    var syncUsername: String
        get() = preferences.getString("sync-username", "") ?: ""
        set(value) = preferences.edit().putString("sync-username", value).apply()
    /** When the Discord token last changed, so sync keeps the most recent one across devices. */
    var discordTokenChanged: Long
        get() = preferences.getLong("discord-token-changed", 0)
        set(value) = preferences.edit().putLong("discord-token-changed", value).apply()
}

/** The saved library, persisted on every change like the desktop app. */
class LibraryStore(private val file: File, private val app: App) {
    private val state: MutableStateFlow<Library>
    /** False when the saved library could not be read; changes and sync are disabled so it is not overwritten. */
    val writable: Boolean

    init {
        var loaded = Library()
        var ok = true
        try { loaded = Library.load(file) } catch (error: Exception) {
            Log.e("Oxidance", "Could not load library", error)
            ok = false
        }
        state = MutableStateFlow(loaded)
        writable = ok
    }

    val flow: StateFlow<Library> = state.asStateFlow()
    val value: Library get() = state.value

    /** Applies a change and saves it; reports errors as messages. Returns whether the library changed. */
    fun change(action: (Library) -> Library?): Boolean {
        if (!writable) { app.toast("Your saved library could not be loaded, so changes are disabled."); return false }
        synchronized(this) {
            val current = state.value
            val next = try { action(current) } catch (error: LibraryException) { app.toast(error.message ?: "Could not change the library."); return false }
            if (next == null || next == current) return false
            try { next.save(file) } catch (error: Exception) { app.toast("Could not save the library: ${error.message}"); return false }
            state.value = next
        }
        // Newly saved songs download; sync uploads the change shortly.
        app.downloads.queueSaved()
        app.sync.requestSoon()
        return true
    }

    /** Replaces the library with one merged by sync. */
    fun replace(next: Library) {
        synchronized(this) {
            if (next == state.value) return
            try { next.save(file) } catch (error: Exception) { app.toast("Could not save synced library: ${error.message}"); return }
            state.value = next
        }
        app.downloads.queueSaved()
    }
}

/** Downloads saved songs one at a time, like the desktop app's queue. */
class DownloadQueue(private val app: App) {
    private val queue = Channel<Song>(Channel.UNLIMITED)
    private val state = MutableStateFlow<Set<String>>(emptySet())
    /** Video IDs queued or downloading. */
    val pending: StateFlow<Set<String>> = state.asStateFlow()
    private val index = MutableStateFlow(FileIndex.scan(app.musicDirectory))
    /** Saved files; updated when a download finishes. */
    val files: StateFlow<FileIndex> = index.asStateFlow()
    private val cancelled = AtomicBoolean(false)

    fun resume() {
        app.scope.launch(Dispatchers.IO) {
            for (song in queue) {
                val result = runCatching { Downloads.download(app.musicDirectory, song, app.sync.dav, cancelled) }
                index.value = FileIndex.scan(app.musicDirectory)
                state.update { it - song.videoId }
                result.onFailure {
                    Log.w("Oxidance", "Download of ${song.videoId} failed", it)
                    app.toast("Could not finish saving ${song.title}: ${it.message}. Restart the app to retry.")
                }
            }
        }
        // Resume missing or interrupted downloads for the existing library.
        queueSaved()
    }

    /** Queues saved songs that are missing audio or artwork. */
    fun queueSaved() {
        val files = FileIndex.scan(app.musicDirectory)
        index.value = files
        for (song in app.library.value.savedSongs()) {
            if (!needsDownload(files, song)) continue
            var added = false
            state.update { if (song.videoId in it) it else { added = true; it + song.videoId } }
            if (added) queue.trySend(song)
        }
    }
}
