package io.github.oxidance

import android.util.Log
import io.github.oxidance.core.DISCORD_APPLICATION_ID
import io.github.oxidance.core.DiscordActivity
import io.github.oxidance.core.discordPresence
import io.github.oxidance.core.discordStatus
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Discord rich presence sent straight to Discord's gateway as the user, so the Discord app is not needed,
 * like the desktop app's `discord.rs`. Connects while a song plays and signs out after a short pause.
 */
class DiscordPresence(private val app: App) {
    enum class Token { MISSING, SAVED, REJECTED }

    private val tokenState = MutableStateFlow(Token.MISSING)
    val token: StateFlow<Token> = tokenState.asStateFlow()
    private val secrets by lazy { Secrets(app) }

    /** All connection state below is only touched on this one thread. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private var current: String? = null
    private var activity: DiscordActivity? = null
    private var socket: WebSocket? = null
    /** Increases with each connection, so callbacks from an earlier one are ignored. */
    private var connection = 0
    private var ready = false
    private var sequence: Long? = null
    private var acknowledged = true
    private var heartbeat: Job? = null
    private var idle: Job? = null
    private var retry: Job? = null
    private var backoff = FIRST_BACKOFF
    /** The user's status from Discord, sent back unchanged so the phone never overrides it. */
    private var status = "online"
    private var since = 0L

    /** The media proxy path of the current song's art, looked up off the gateway thread. */
    private var art: String? = null
    private var artFor: List<String>? = null
    private var artPending = false
    private val artPaths = ConcurrentHashMap<String, String>()
    private val artLoads = ConcurrentHashMap<String, Boolean>()

    fun restore() {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { secrets.get(KEY) } ?: return@launch
            tokenState.value = Token.SAVED
            use(saved)
        }
    }

    /** Stores the token encrypted, or forgets it when blank, and signs in with it. */
    suspend fun save(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        val trimmed = token.trim()
        runCatching {
            if (trimmed.isEmpty()) secrets.remove(KEY) else secrets.put(KEY, trimmed)
            tokenState.value = if (trimmed.isEmpty()) Token.MISSING else Token.SAVED
            scope.launch { use(trimmed.ifEmpty { null }) }
            Unit
        }
    }

    /** Shows `next` on Discord, or nothing while paused. Ignores timeline jitter so it is cheap to call often. */
    fun update(next: DiscordActivity?) {
        scope.launch {
            val last = activity
            if (next == null && last == null || next != null && last != null && next.same(last)) return@launch
            activity = next
            changed()
        }
    }

    /** Clears the song and signs out now, for when playback stops for good. */
    fun signOut() {
        scope.launch {
            activity = null
            disconnect(clear = true)
        }
    }

    private fun use(token: String?) {
        if (token == current) return
        disconnect(clear = true)
        current = token
        backoff = FIRST_BACKOFF
        changed()
    }

    private fun changed() {
        val token = current ?: return
        val playing = activity
        if (playing == null) {
            if (ready) send(presence())
            // Stay connected for a while, so a short pause does not flicker your status.
            if (socket != null && idle == null) idle = scope.launch {
                delay(LINGER_MS)
                idle = null
                backoff = FIRST_BACKOFF
                disconnect(clear = true)
            }
            return
        }
        idle?.cancel()
        idle = null
        if (playing.artUrls != artFor) lookUpArt(token, playing.artUrls)
        if (socket == null) { if (retry == null) connect(token) }
        else if (ready && !artPending) send(presence())
    }

    /** A presence update for the gateway, showing `shown`: the current song unless given. */
    private fun presence(shown: DiscordActivity? = activity) = buildJsonObject { put("op", 3); put("d", discordPresence(shown, art, status, since)) }

    private fun send(message: JsonObject) { socket?.send(message.toString()) }

    private fun connect(token: String) {
        val id = ++connection
        ready = false
        sequence = null
        acknowledged = true
        since = System.currentTimeMillis()
        socket = client.newWebSocket(Request.Builder().url(GATEWAY).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { if (id == connection) receive(token, text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                scope.launch { if (id == connection) { if (code == 4004) rejected() else failed("closed with code $code") } }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { if (id == connection) failed(t.message ?: t.toString()) }
            }
        })
    }

    private fun receive(token: String, text: String) {
        val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        message["s"]?.jsonPrimitive?.longOrNull?.let { sequence = it }
        when (message["op"]?.jsonPrimitive?.intOrNull) {
            10 -> {
                val interval = message["d"]?.jsonObject?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull ?: 41_250
                heartbeat?.cancel()
                heartbeat = scope.launch {
                    // Discord asks for the first heartbeat after a random fraction of the interval.
                    delay((interval * Random.nextDouble()).toLong())
                    while (isActive) { beat(); delay(interval) }
                }
                // Identify with "unknown" so Discord keeps the user's status; the song follows once READY reports it.
                send(buildJsonObject {
                    put("op", 2)
                    putJsonObject("d") {
                        put("token", token)
                        put("capabilities", 0)
                        put("compress", false)
                        putJsonObject("properties") { put("os", "Android"); put("browser", "Chrome"); put("device", "") }
                        put("presence", discordPresence(null, null, "unknown", since))
                    }
                })
            }
            1 -> send(heartbeatMessage())
            11 -> acknowledged = true
            7 -> failed("Discord asked to reconnect")
            9 -> failed("Discord invalidated the session")
            0 -> when (message["t"]?.jsonPrimitive?.contentOrNull) {
                "READY" -> {
                    // Backoff is not reset here, so a session that fails right after signing in doesn't reconnect in a tight loop.
                    ready = true
                    status = discordStatus(sessions(message["d"]?.jsonObject?.get("sessions"))) ?: "online"
                    changed()
                }
                // Picked up for the next update; resending now could echo back and forth with Discord.
                "SESSIONS_REPLACE" -> discordStatus(sessions(message["d"]))?.let { status = it }
            }
        }
    }

    private fun sessions(element: JsonElement?) = (element as? JsonArray).orEmpty()

    private fun heartbeatMessage() = buildJsonObject { put("op", 1); put("d", sequence) }

    private fun beat() {
        if (!acknowledged) { failed("Discord stopped acknowledging heartbeats"); return }
        acknowledged = false
        send(heartbeatMessage())
    }

    private fun rejected() {
        disconnect(clear = false)
        current = null
        tokenState.value = Token.REJECTED
        app.toast("Discord rejected the token. Enter a new one in Settings.")
    }

    private fun failed(reason: String) {
        Log.w("Oxidance", "Discord presence disconnected: $reason")
        disconnect(clear = false)
        if (current == null || activity == null) return
        retry = scope.launch {
            delay(backoff)
            retry = null
            changed()
        }
        backoff = (backoff * 2).coerceAtMost(LONGEST_BACKOFF)
    }

    private fun disconnect(clear: Boolean) {
        heartbeat?.cancel()
        heartbeat = null
        idle?.cancel()
        idle = null
        retry?.cancel()
        retry = null
        val closing = socket ?: return
        socket = null
        connection++
        // Clear the song and close normally, so Discord ends the session now instead of keeping it resumable.
        if (clear && ready) closing.send(presence(null).toString())
        ready = false
        if (clear) closing.close(1000, null) else closing.cancel()
    }

    /** Finds the first art URL that loads and registers it with Discord's media proxy, then shows it. */
    private fun lookUpArt(token: String, urls: List<String>) {
        artFor = urls
        art = null
        artPending = urls.isNotEmpty()
        if (urls.isEmpty()) return
        scope.launch {
            val path = withContext(Dispatchers.IO) { urls.firstOrNull { loads(it) }?.let { proxyPath(token, it) } }
            if (artFor != urls) return@launch
            art = path
            artPending = false
            if (ready && activity != null) send(presence())
        }
    }

    /** Whether `url` loads. Discord shows a broken image for a missing one, such as a YouTube video without a high-resolution thumbnail. */
    private fun loads(url: String): Boolean {
        artLoads[url]?.let { return it }
        val code = runCatching { artClient.newCall(Request.Builder().url(url).head().build()).execute().use { it.code } }.getOrNull()
            ?: return false
        // Only real answers are remembered, so a network hiccup does not hide the art until restart.
        // Servers that refuse HEAD requests usually still serve the image.
        return (code in 200..299 || code == 405).also { artLoads[url] = it }
    }

    private fun proxyPath(token: String, url: String): String? {
        artPaths[url]?.let { return it }
        val body = buildJsonObject { putJsonArray("urls") { add(url) } }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("https://discord.com/api/v9/applications/$DISCORD_APPLICATION_ID/external-assets")
            .header("Authorization", token).post(body).build()
        return runCatching {
            artClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                Json.parseToJsonElement(response.body.string()).jsonArray[0].jsonObject["external_asset_path"]!!.jsonPrimitive.content
            }
        }.onSuccess { artPaths[url] = it }.onFailure { Log.w("Oxidance", "Discord could not load album art", it) }.getOrNull()
    }

    private companion object {
        const val KEY = "discord-token"
        const val GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json"
        /** Shorter than the desktop's five minutes, to spare the battery. */
        const val LINGER_MS = 120_000L
        const val FIRST_BACKOFF = 1000L
        const val LONGEST_BACKOFF = 60_000L
        /** Heartbeats detect a dead connection, so reads never time out. */
        val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
        val artClient: OkHttpClient = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    }
}
