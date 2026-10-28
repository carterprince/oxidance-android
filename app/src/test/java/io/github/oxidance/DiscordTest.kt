package io.github.oxidance

import io.github.oxidance.core.DiscordActivity
import io.github.oxidance.core.discordPresence
import io.github.oxidance.core.discordStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the desktop app's discord.rs tests. */
class DiscordTest {
    private fun shown(value: JsonObject) = value["activities"]!!.jsonArray[0].jsonObject
    private fun text(value: JsonObject, key: String) = value[key]?.jsonPrimitive?.content

    @Test
    fun presenceShowsTheSongNotThePlayer() {
        val activity = DiscordActivity("Hello, World", "Artist", 1000, 181_000, emptyList(), 1.5f)
        val value = discordPresence(activity, "external/abc/https/example.com/cover.jpg", "dnd", 5)
        val shown = shown(value)
        assertEquals("Artist (1.5× speed)", text(shown, "name"))
        assertEquals("1", text(shown, "status_display_type"))
        assertEquals("Hello, World", text(shown, "details"))
        assertEquals("2", text(shown, "type"))
        assertEquals("Artist", text(shown, "state"))
        assertEquals("181000", text(shown["timestamps"]!!.jsonObject, "end"))
        assertEquals("mp:external/abc/https/example.com/cover.jpg", text(shown["assets"]!!.jsonObject, "large_image"))
        assertEquals("the user's status is kept", "dnd", text(value, "status"))
        assertEquals("away, so the phone still gets push notifications", "true", text(value, "afk"))
        assertFalse(value.toString().lowercase().contains("oxidance"))
        assertEquals(JsonArray(emptyList()), discordPresence(null, null, "online", 0)["activities"])

        val long = DiscordActivity("x".repeat(300), null, 0, null, emptyList(), 1f)
        val longShown = shown(discordPresence(long, null, "online", 0))
        assertEquals("the title stands in for a missing artist", 128, text(longShown, "name")!!.length)
        assertEquals("2", text(longShown, "status_display_type"))
        assertEquals("Song (1× speed)", text(shown(discordPresence(long.copy(title = "Song"), null, "online", 0)), "name"))
        assertNull(longShown["state"])
        assertNull(longShown["timestamps"]!!.jsonObject["end"])
        assertNull("no art, no placeholder image", longShown["assets"])
    }

    @Test
    fun pollingJitterIsNotResent() {
        val activity = DiscordActivity("Song", null, 10_000, 70_000, emptyList(), 1f)
        assertTrue(activity.same(activity.copy(startMs = 10_400, endMs = 70_400)))
        assertFalse("a seek is resent", activity.same(activity.copy(startMs = 40_000, endMs = 100_000)))
        assertFalse(activity.same(activity.copy(title = "Other")))
        assertFalse(activity.same(activity.copy(speed = 1.25f)))
        assertFalse(activity.same(activity.copy(artUrls = listOf("https://example.com/cover.jpg"))))
    }

    @Test
    fun statusComesFromTheCombinedSession() {
        val sessions = Json.parseToJsonElement("""[{"session_id":"abc","status":"online"},{"session_id":"all","status":"invisible"}]""").jsonArray
        assertEquals("invisible", discordStatus(sessions))
        assertNull(discordStatus(Json.parseToJsonElement("""[{"session_id":"abc","status":"online"}]""").jsonArray))
        assertNull(discordStatus(Json.parseToJsonElement("""[{"session_id":"all","status":"unknown"}]""").jsonArray))
    }
}
