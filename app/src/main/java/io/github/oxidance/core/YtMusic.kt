package io.github.oxidance.core

import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Follows a slash-separated path of object keys and array indices, like serde's `pointer`. */
fun JsonElement?.at(path: String): JsonElement? {
    var value = this
    for (part in path.split('/').filter { it.isNotEmpty() }) {
        value = when (value) {
            is JsonObject -> value[part]
            is JsonArray -> part.toIntOrNull()?.let { value.getOrNull(it) }
            else -> null
        } ?: return null
    }
    return value
}

val JsonElement?.string: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
val JsonElement?.array: JsonArray? get() = this as? JsonArray
private val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.longOrNull

/** The concatenated text of a `runs` value. */
fun text(value: JsonElement?): String = value.at("runs").array?.mapNotNull { it.at("text").string }?.joinToString("") ?: ""

/** Lowercase words, ignoring punctuation and other symbols. */
private fun words(text: String): List<String> =
    text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

/** How many of the query's words appear in `text`. The last word may be incomplete, so it also matches as a prefix. */
fun matchScore(query: String, text: String): Int {
    val queryWords = words(query)
    val textWords = words(text)
    return queryWords.withIndex().count { (index, word) ->
        textWords.any { it == word || index == queryWords.size - 1 && it.startsWith(word) }
    }
}

private fun host(url: String): String? = runCatching { URI(url).host }.getOrNull()
private fun path(url: String): String = runCatching { URI(url).path }.getOrNull() ?: ""
private val GOOGLE_IMAGE_HOSTS = setOf("yt3.googleusercontent.com", "lh3.googleusercontent.com", "yt3.ggpht.com")

/** Requests a Google-hosted image at `size` pixels, keeping its other options. In particular, `p` crops to a square. */
fun resizeGoogleImage(url: String, size: Int): String {
    val base = url.substringBefore('=')
    val options = if ('=' in url) url.substringAfter('=') else ""
    fun isSize(option: String) = option.length > 1 && option[0] in "whs" && option.substring(1).all(Char::isDigit)
    val parts = mutableListOf("w$size", "h$size")
    parts += options.split('-').filter { it.isNotEmpty() && !isSize(it) }
    for (default in listOf("l90", "rj")) if (default !in parts) parts += default
    return "$base=${parts.joinToString("-")}"
}

fun searchArtUrl(url: String): String = when {
    host(url) in GOOGLE_IMAGE_HOSTS -> resizeGoogleImage(url, 240)
    host(url) == "i.ytimg.com" && path(url).startsWith("/vi/") -> url.substringBeforeLast('/') + "/mqdefault.jpg"
    host(url) == "i.scdn.co" -> url.replace("ab67616d0000b273", "ab67616d00001e02")
    else -> url
}

fun highQualityArtUrl(url: String): String = when {
    host(url) in GOOGLE_IMAGE_HOSTS -> resizeGoogleImage(url, 1200)
    host(url) == "i.ytimg.com" && path(url).startsWith("/vi/") -> url.substringBeforeLast('/') + "/maxresdefault.jpg"
    else -> url
}

/** Search state carried between pages: buffered songs and the continuation token. */
data class SearchCursor(
    val pending: List<Song> = emptyList(),
    val continuation: String? = null,
    val visitorData: String? = null,
    val seen: Set<String> = emptySet(),
    val usedTokens: Set<String> = emptySet(),
    val initialized: Boolean = false,
)

data class SearchPage(val songs: List<Song>, val next: SearchCursor?)

/** The unofficial YouTube Music API, as used by the desktop app. */
class YtMusic(private val client: OkHttpClient = defaultClient) {
    companion object {
        const val PAGE_SIZE = 25
        val defaultClient: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        private val parser = Json { ignoreUnknownKeys = true }

        fun parseSong(renderer: JsonElement?): Song? {
            // Exclude albums, playlists, and artists, even if another shelf is returned.
            val videoId = renderer.at("playlistItemData/videoId").string
                ?: renderer.at("navigationEndpoint/watchEndpoint/videoId").string
                ?: renderer.at("overlay/musicItemThumbnailOverlayRenderer/content/musicPlayButtonRenderer/playNavigationEndpoint/watchEndpoint/videoId").string
                ?: return null
            val columns = renderer.at("flexColumns").array ?: return null
            val title = text(columns.firstOrNull().at("musicResponsiveListItemFlexColumnRenderer/text") ?: return null)
            if (title.isEmpty()) return null
            val names = mutableListOf<String>()
            val links = mutableListOf<ArtistLink>()
            for (column in columns.drop(1)) {
                for (run in column.at("musicResponsiveListItemFlexColumnRenderer/text/runs").array ?: continue) {
                    val id = run.at("navigationEndpoint/browseEndpoint/browseId").string ?: continue
                    val name = run.at("text").string ?: continue
                    if (!id.startsWith("UC") && !id.startsWith("MPLA")) continue
                    if (name !in names) names += name
                    val link = ArtistLink(id, name)
                    if (link !in links) links += link
                }
            }
            val art = largestImage(renderer.at("thumbnail/musicThumbnailRenderer/thumbnail/thumbnails"))
            return Song(videoId, title, names.takeIf { it.isNotEmpty() }?.joinToString(", "), art, links)
        }

        fun largestImage(images: JsonElement?): String? = images.array
            ?.filter { it.at("url").string != null }
            ?.maxByOrNull { (it.at("width").long ?: 0) * (it.at("height").long ?: 0) }
            ?.at("url").string

        private fun continuation(value: JsonElement?): String? =
            (value.at("continuations/0/nextContinuationData/continuation")
                ?: value.at("continuations/0/reloadContinuationData/continuation")
                ?: value.at("continuationItemRenderer/continuationEndpoint/continuationCommand/token")).string

        fun parsePage(response: JsonElement): Pair<List<Song>, String?> {
            val shelves = mutableListOf<JsonElement>()
            var appended: JsonArray? = null
            val continuationShelf = response.at("continuationContents/musicShelfContinuation")
            val commands = response.at("onResponseReceivedCommands").array
            if (continuationShelf != null) {
                shelves += continuationShelf
            } else if (commands != null) {
                appended = commands.firstNotNullOfOrNull { it.at("appendContinuationItemsAction/continuationItems").array }
            } else {
                val contents = response.at("contents/tabbedSearchResultsRenderer/tabs/0/tabRenderer/content")
                    ?: response.at("contents") ?: throw IOException("YouTube Music returned no search contents")
                val sections = contents.at("sectionListRenderer/contents").array
                    ?: throw IOException("Unrecognized YouTube Music response; the unofficial API may have changed")
                for (section in sections) {
                    val shelf = section.at("musicShelfRenderer") ?: continue
                    val category = text(shelf.at("title"))
                    if (category.isEmpty() || category == "Songs") shelves += shelf
                }
            }
            var next: String? = null
            for (shelf in shelves) next = continuation(shelf) ?: next
            val songs = mutableListOf<Song>()
            for (entry in shelves.flatMap { it.at("contents").array ?: emptyList() } + (appended ?: emptyList())) {
                parseSong(entry.at("musicResponsiveListItemRenderer"))?.let { songs += it }
                next = continuation(entry) ?: next
            }
            return songs to next
        }

        /** Every value stored under `key`, anywhere in `value`, in document order. */
        fun collect(value: JsonElement?, key: String, output: MutableList<JsonElement> = mutableListOf()): MutableList<JsonElement> {
            when (value) {
                is JsonObject -> {
                    value[key]?.let { output += it }
                    for (child in value.values) collect(child, key, output)
                }
                is JsonArray -> for (child in value) collect(child, key, output)
                else -> {}
            }
            return output
        }

        private fun pageType(row: JsonElement) =
            row.at("navigationEndpoint/browseEndpoint/browseEndpointContextSupportedConfigs/browseEndpointContextMusicConfig/pageType").string

        private fun column(row: JsonElement, index: Int) = text(row.at("flexColumns/$index/musicResponsiveListItemFlexColumnRenderer/text"))

        private fun thumbnails(row: JsonElement) = largestImage(row.at("thumbnail/musicThumbnailRenderer/thumbnail/thumbnails"))

        fun parseAlbum(row: JsonElement): Album? {
            val id = row.at("navigationEndpoint/browseEndpoint/browseId").string ?: return null
            val title = column(row, 0)
            if (!id.startsWith("MPRE") || title.isEmpty()) return null
            return Album(id, title, column(row, 1), thumbnails(row))
        }

        fun parseArtists(response: JsonElement): List<Artist> {
            val seen = mutableSetOf<String>()
            return collect(response.at("contents"), "musicResponsiveListItemRenderer").mapNotNull { row ->
                val id = row.at("navigationEndpoint/browseEndpoint/browseId").string ?: return@mapNotNull null
                if (!(id.startsWith("UC") || id.startsWith("MPLA")) || !seen.add(id)) return@mapNotNull null
                val name = column(row, 0).ifEmpty { return@mapNotNull null }
                Artist(ArtistLink(id, name), thumbnails(row))
            }.take(5)
        }

        /** Suggestions in YouTube Music's order, keeping artists, albums, and songs. */
        fun parseSuggestions(response: JsonElement): List<SearchItem> =
            collect(response.at("contents"), "musicResponsiveListItemRenderer").mapNotNull { row ->
                when (pageType(row)) {
                    "MUSIC_PAGE_TYPE_ARTIST" -> {
                        val id = row.at("navigationEndpoint/browseEndpoint/browseId").string
                        val name = column(row, 0)
                        if (id != null && id.startsWith("UC") && name.isNotEmpty()) SearchItem.ArtistItem(Artist(ArtistLink(id, name), thumbnails(row))) else null
                    }
                    "MUSIC_PAGE_TYPE_ALBUM" -> parseAlbum(row)?.let(SearchItem::AlbumItem)
                    // Audio tracks only, matching the Songs search filter; skip music videos and uploads.
                    else -> if (row.at("navigationEndpoint/watchEndpoint/watchEndpointMusicSupportedConfigs/watchEndpointMusicConfig/musicVideoType").string == "MUSIC_VIDEO_TYPE_ATV")
                        parseSong(row)?.let(SearchItem::SongItem) else null
                }
            }

        fun parseAlbumPage(response: JsonElement, id: String): AlbumPage {
            val header = collect(response.at("contents"), "musicResponsiveHeaderRenderer").firstOrNull()
                ?: throw IOException("Album is unavailable or has an unsupported layout")
            val title = text(header.at("title")).ifEmpty { throw IOException("Album has no title") }
            val artists = header.at("straplineTextOne/runs").array.orEmpty().mapNotNull { run ->
                val artistId = run.at("navigationEndpoint/browseEndpoint/browseId").string ?: return@mapNotNull null
                ArtistLink(artistId, run.at("text").string ?: return@mapNotNull null)
            }
            val artistNames = if (artists.isEmpty()) text(header.at("straplineTextOne")) else artists.joinToString(", ") { it.name }
            val kind = text(header.at("subtitle"))
            val details = listOf(kind, text(header.at("secondSubtitle"))).filter { it.isNotEmpty() }.joinToString(" · ")
            val image = collect(header.at("thumbnail"), "thumbnails").firstOrNull()?.let(::largestImage)
            val album = Album(id, title, listOf(kind.split(" • ").first(), artistNames).filter { it.isNotEmpty() }.joinToString(" • "), image)
            val songs = mutableListOf<Song>()
            for (row in collect(response.at("contents"), "musicShelfRenderer").flatMap { it.at("contents").array.orEmpty() }) {
                // Unavailable tracks have no video and are skipped.
                var song = parseSong(row.at("musicResponsiveListItemRenderer")) ?: continue
                if (song.artists.isEmpty() && artists.isNotEmpty()) song = song.copy(artist = artistNames, artists = artists)
                // Album tracks have no artwork of their own.
                if (song.albumArtUrl == null) song = song.copy(albumArtUrl = image)
                if (songs.none { it.videoId == song.videoId }) songs += song
            }
            return AlbumPage(album, artists, details, songs)
        }

        fun profileImageUrl(images: JsonElement?): String? {
            val square = images.array.orEmpty().filter {
                val width = it.at("width").long
                width != null && width > 0 && width == it.at("height").long
            }
            if (square.isNotEmpty()) return largestImage(JsonArray(square))
            val url = largestImage(images) ?: return null
            val google = host(url)?.let { it.endsWith(".googleusercontent.com") || it.endsWith(".ggpht.com") } == true
            return if (google && '=' in url) resizeGoogleImage(url, 320) else url
        }

        private fun topSongsShelves(response: JsonElement) =
            collect(response.at("contents"), "musicShelfRenderer").filter { text(it.at("title")) in setOf("Top songs", "Songs") }

        fun parseProfile(response: JsonElement, id: String): Profile {
            val header = response.at("header/musicImmersiveHeaderRenderer") ?: response.at("header/musicVisualHeaderRenderer")
                ?: response.at("header/musicHeaderRenderer") ?: throw IOException("Artist profile is unavailable or has an unsupported layout")
            val name = text(header.at("title")).ifEmpty { throw IOException("Artist profile has no name") }
            val artist = Artist(ArtistLink(id, name), profileImageUrl(header.at("thumbnail/musicThumbnailRenderer/thumbnail/thumbnails")))
            val biography = collect(response.at("contents"), "musicDescriptionShelfRenderer")
                .map { text(it.at("description")) }.firstOrNull { it.isNotBlank() }
            val seen = mutableSetOf<String>()
            val songs = topSongsShelves(response).flatMap { it.at("contents").array.orEmpty() }.mapNotNull { row ->
                parseSong(row.at("musicResponsiveListItemRenderer"))?.takeIf { seen.add(it.videoId) }?.withArtist(artist.link)
            }
            return Profile(artist, biography, songs)
        }

        private fun Song.withArtist(link: ArtistLink) = if (artists.isEmpty()) copy(artist = link.name, artists = listOf(link)) else this
    }

    private fun context(visitorData: String? = null) = buildJsonObject {
        putJsonObject("client") {
            put("clientName", "WEB_REMIX")
            put("clientVersion", "1.20261005.01.00")
            put("hl", "en")
            put("gl", "US")
            visitorData?.let { put("visitorData", it) }
        }
    }

    private fun request(endpoint: String, body: JsonObject): JsonElement {
        val request = Request.Builder().url("https://music.youtube.com/youtubei/v1/$endpoint")
            .header("Origin", "https://music.youtube.com").header("User-Agent", "Mozilla/5.0")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val response = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("YouTube Music returned HTTP ${response.code}")
            parser.parseToJsonElement(response.body.string())
        }
        response.at("error")?.let { throw IOException("YouTube Music error: $it") }
        return response
    }

    private fun request(endpoint: String, vararg fields: Pair<String, String>) = request(endpoint, buildJsonObject {
        put("context", context())
        for ((key, value) in fields) put(key, value)
    })

    /** Fetches 25 unique songs, retaining overflow and continuation state for the next batch. */
    fun searchPage(query: String, start: SearchCursor? = null): SearchPage {
        if (query.isBlank()) throw IOException("Enter a search query")
        var cursor = start ?: SearchCursor()
        for (attempt in 0 until 10) {
            if (cursor.pending.size >= PAGE_SIZE || (cursor.initialized && cursor.continuation == null)) break
            val token = cursor.continuation
            cursor = cursor.copy(continuation = null)
            if (token != null && token in cursor.usedTokens) break
            cursor = cursor.copy(usedTokens = if (token != null) cursor.usedTokens + token else cursor.usedTokens)
            val response = request("search", buildJsonObject {
                put("context", context(cursor.visitorData))
                put("query", query)
                put("params", "EgWKAQIIAWoKEAoQAxAEEAkQBQ==")
                token?.let { put("continuation", it) }
            })
            response.at("responseContext/visitorData").string?.let { cursor = cursor.copy(visitorData = it) }
            val (songs, next) = parsePage(response)
            val seen = cursor.seen.toMutableSet()
            val fresh = songs.filter { seen.add(it.videoId) }
            cursor = cursor.copy(initialized = true, continuation = next?.takeIf { it !in cursor.usedTokens },
                seen = seen, pending = cursor.pending + fresh)
        }
        val songs = cursor.pending.take(PAGE_SIZE)
        val rest = cursor.copy(pending = cursor.pending.drop(PAGE_SIZE))
        val hasMore = rest.pending.isNotEmpty() || rest.continuation != null
        return SearchPage(songs, rest.takeIf { hasMore })
    }

    fun suggestions(query: String): List<SearchItem> = parseSuggestions(request("music/get_search_suggestions", "input" to query))

    fun searchAlbums(query: String): List<Album> =
        collect(request("search", "query" to query, "params" to "EgWKAQIYAWoMEA4QChADEAQQCRAF").at("contents"), "musicResponsiveListItemRenderer")
            .mapNotNull(::parseAlbum).distinctBy { it.id }.take(3)

    fun searchArtists(query: String): List<Artist> =
        parseArtists(request("search", "query" to query, "params" to "EgWKAQIgAWoMEA4QChADEAQQCRAF"))

    fun album(id: String): AlbumPage = parseAlbumPage(request("browse", "browseId" to id), id)

    fun profile(id: String): Profile {
        val response = request("browse", "browseId" to id.removePrefix("MPLA"))
        val profile = parseProfile(response, id)
        val endpoint = topSongsShelves(response).firstNotNullOfOrNull { it.at("title/runs/0/navigationEndpoint/browseEndpoint") }
            ?: return profile
        val browseId = endpoint.at("browseId").string ?: return profile
        // A failed full-list request still leaves the artist's preview available.
        val full = runCatching {
            request("browse", buildJsonObject {
                put("context", context())
                put("browseId", browseId)
                endpoint.at("params").string?.let { put("params", it) }
            })
        }.getOrNull() ?: return profile
        val songs = profile.songs.toMutableList()
        val seen = songs.map { it.videoId }.toMutableSet()
        val shelves = collect(full.at("contents"), "musicPlaylistShelfRenderer") + collect(full.at("contents"), "musicShelfRenderer")
        for (entry in shelves.flatMap { it.at("contents").array.orEmpty() }) {
            if (songs.size >= 100) break
            val song = parseSong(entry.at("musicResponsiveListItemRenderer")) ?: continue
            if (seen.add(song.videoId)) songs += song.withArtist(profile.artist.link)
        }
        return profile.copy(songs = songs)
    }
}
