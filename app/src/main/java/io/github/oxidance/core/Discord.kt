package io.github.oxidance.core

import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A Discord application, needed only so Discord displays album art. Its name is never shown. */
const val DISCORD_APPLICATION_ID = "1559622888431030282"

/** The song Discord should show, like the desktop app's `discord::Activity`. */
data class DiscordActivity(
    val title: String,
    val artist: String?,
    /** Wall-clock milliseconds when the song would have started and will end at the current speed. */
    val startMs: Long,
    val endMs: Long?,
    /** Public image URLs, best first. Discord fetches art through its media proxy, so it is given the first one that loads. */
    val artUrls: List<String>,
    val speed: Float,
) {
    /** Whether Discord already shows this, ignoring small timeline jitter from playback events. */
    fun same(other: DiscordActivity): Boolean {
        fun close(a: Long, b: Long) = abs(a - b) < 2000
        return title == other.title && artist == other.artist && speed == other.speed && artUrls == other.artUrls && close(startMs, other.startMs)
            && (if (endMs != null && other.endMs != null) close(endMs, other.endMs) else endMs == other.endMs)
    }
}

/** Discord rejects activity text longer than 128 characters. */
private fun clip(text: String) = if (text.codePointCount(0, text.length) <= 128) text else text.substring(0, text.offsetByCodePoints(0, 128))

/**
 * The gateway presence for `activity`, or none. `art` is the image's media proxy path. `status` is the user's
 * status as Discord reports it, so the phone never overrides Invisible or Do Not Disturb set elsewhere.
 * The session is marked away, so Discord still sends push notifications to the phone.
 */
fun discordPresence(activity: DiscordActivity?, art: String?, status: String, sinceMs: Long): JsonObject = buildJsonObject {
    put("status", status)
    put("since", sinceMs)
    put("afk", true)
    put("activities", buildJsonArray {
        if (activity == null) return@buildJsonArray
        // The profile shows "Listening to" the name, while status_display_type picks what the member list
        // shows after the music note: the artist (state), which is more recognizable than the song title,
        // as in Discord's Spotify presence, or the title (details) when there is no artist.
        val speed = String.format(Locale.ROOT, "%.2f", activity.speed).trimEnd('0').trimEnd('.')
        add(buildJsonObject {
            put("name", clip("${activity.artist ?: activity.title} (${speed}× speed)"))
            put("type", 2)
            put("details", clip(activity.title))
            put("status_display_type", if (activity.artist != null) 1 else 2)
            activity.artist?.let { put("state", clip(it)) }
            putJsonObject("timestamps") {
                put("start", activity.startMs)
                activity.endMs?.let { put("end", it) }
            }
            put("application_id", DISCORD_APPLICATION_ID)
            art?.let { putJsonObject("assets") { put("large_image", "mp:$it") } }
        })
    })
}

/** The user's overall status from a READY or SESSIONS_REPLACE session list, if Discord included it. */
fun discordStatus(sessions: List<JsonElement>): String? = sessions.filterIsInstance<JsonObject>()
    .firstOrNull { (it["session_id"] as? JsonPrimitive)?.contentOrNull == "all" }
    ?.let { (it["status"] as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotEmpty() && it != "unknown" }
