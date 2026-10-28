package io.github.oxidance

import android.net.ConnectivityManager
import android.net.Network
import io.github.oxidance.core.Dav
import io.github.oxidance.core.DiscordToken
import io.github.oxidance.core.Library
import io.github.oxidance.core.Sync
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SyncStatus(val connected: Boolean, val running: Boolean, val failed: Boolean, val detail: String)

/** Syncs the library and saved songs with a WebDAV server in the background, like the desktop app. */
class SyncManager(private val app: App) {
    @Volatile var dav: Dav? = null
        private set
    private val state = MutableStateFlow(SyncStatus(false, false, false, NOT_CONNECTED))
    val status: StateFlow<SyncStatus> = state.asStateFlow()
    private var running = false
    private var again = false
    /** Increases on connect and disconnect, so results from an earlier connection are ignored. */
    private var generation = 0
    private var debounce: Job? = null
    private var periodic: Job? = null
    private val baseFile get() = File(app.filesDir, "sync-base.json")

    private val secrets by lazy { Secrets(app) }

    private fun secretKey(url: String, username: String) = "$url\n$username"

    private fun set(running: Boolean, failed: Boolean, detail: String) {
        state.value = SyncStatus(dav != null, running, failed, detail)
    }

    fun restore() {
        // Sync when the network returns.
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { request() }
        })
        val url = app.settings.syncUrl
        val username = app.settings.syncUsername
        if (url.isEmpty()) return
        app.scope.launch(Dispatchers.IO) {
            val password = secrets.get(secretKey(url, username))
            if (password == null) {
                set(false, true, "The sync password is missing. Disconnect and connect again.")
                return@launch
            }
            dav = runCatching { Dav(Dav.normalizeUrl(url), username, password) }.getOrNull()
            request()
        }
    }

    /** Syncs every minute while the app is visible. */
    fun setForeground(visible: Boolean) {
        periodic?.cancel()
        if (!visible) return
        periodic = app.scope.launch {
            request()
            while (isActive) { delay(60_000); request() }
        }
    }

    /** Syncs after a short delay, so a burst of library changes uploads once. */
    fun requestSoon() {
        if (dav == null) return
        debounce?.cancel()
        debounce = app.scope.launch { delay(3000); request() }
    }

    @Synchronized
    fun request() {
        val dav = dav ?: return
        if (running) { again = true; return }
        if (!app.library.writable) { set(false, true, "Sync is paused because the saved library could not be loaded."); return }
        running = true
        again = false
        set(true, false, "Syncing…")
        val snapshot = app.library.value
        val discord = app.discord.saved?.let { DiscordToken(it, app.settings.discordTokenChanged) }
        val current = generation
        app.scope.launch(Dispatchers.IO) {
            val result = runCatching {
                discord?.let { Sync.syncDiscordToken(dav, it) }?.let { newer -> if (generation == current) app.discord.adopt(newer) }
                Sync.cycle(dav, snapshot, baseFile, app.musicDirectory,
                    onMerged = { merged -> if (generation == current) apply(snapshot, merged) },
                    onProgress = { detail -> if (generation == current) set(true, false, detail) })
            }
            synchronized(this@SyncManager) {
                if (generation != current) return@launch
                running = false
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                result.onSuccess { set(false, false, "$it ($time)") }
                    .onFailure { set(false, true, "Sync failed ($time): ${it.message}") }
                if (again) request()
            }
        }
    }

    /** Applies the merged library, keeping local changes made while sync was running. */
    private fun apply(snapshot: Library, merged: Library) {
        val current = app.library.value
        val next = if (current == snapshot) merged else {
            synchronized(this) { again = true }
            Library.merge(snapshot, current, merged)
        }
        app.library.replace(next)
    }

    /** Connects and stores the password encrypted with a key from the Android Keystore. */
    suspend fun connect(url: String, username: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val connected = Dav.connect(url, username, password)
            secrets.put(secretKey(connected.url.toString(), connected.username), password)
            app.settings.syncUrl = connected.url.toString()
            app.settings.syncUsername = connected.username
            synchronized(this@SyncManager) {
                generation++
                running = false
                dav = connected
            }
            request()
        }
    }

    fun disconnect() {
        val previous = dav
        synchronized(this) {
            generation++
            running = false
            dav = null
        }
        if (previous != null) secrets.remove(secretKey(previous.url.toString(), previous.username))
        app.settings.syncUrl = ""
        app.settings.syncUsername = ""
        set(false, false, NOT_CONNECTED)
        app.toast("Disconnected from the sync server. Your library and songs are kept on this device.")
    }

    companion object {
        const val NOT_CONNECTED = "Sync is not connected."
    }
}
