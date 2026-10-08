package io.github.oxidance

import io.github.oxidance.core.Dav
import io.github.oxidance.core.FileIndex
import io.github.oxidance.core.Library
import io.github.oxidance.core.LibraryJson
import io.github.oxidance.core.SearchItem
import io.github.oxidance.core.Song
import io.github.oxidance.core.YtMusic
import io.github.oxidance.core.highQualityArtUrl
import io.github.oxidance.core.localName
import io.github.oxidance.core.matchScore
import io.github.oxidance.core.searchArtUrl
import io.github.oxidance.core.withRetries
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of the desktop app's unit tests, so both implementations agree. */
class CoreTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun song(id: String) = Song(id, "Same title")
    private fun ids(songs: List<Song>) = songs.map { it.videoId }

    private val songRenderer = """{
        "playlistItemData": {"videoId": "example"},
        "flexColumns": [
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Song & title"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [
                {"text": "Artist A", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCa"}}},
                {"text": " & "},
                {"text": "Artist B", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCb"}}},
                {"text": "Album", "navigationEndpoint": {"browseEndpoint": {"browseId": "MPREalbum"}}},
                {"text": "3:45"}
            ]}}}
        ],
        "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
            {"url": "https://example.com/small.jpg", "width": 60, "height": 60},
            {"url": "https://example.com/large.jpg", "width": 120, "height": 120}
        ]}}}
    }"""

    @Test fun matchScoreCountsQueryWordsInTheText() {
        val query = "haruka nakamura let go"
        assertEquals(4, matchScore(query, "let go (feat. Nujabes) haruka nakamura"))
        assertEquals(2, matchScore(query, "haruka nakamura"))
        assertEquals(2, matchScore(query, "MELODICA Album • haruka nakamura • 2013"))
        assertEquals("the last word matches as a prefix", 2, matchScore("piero pi", "Piero Piccioni"))
        assertEquals("earlier words must be complete", 0, matchScore("pi piero", "Piccioni"))
        assertEquals("punctuation is ignored, accents are not", 2, matchScore("Café, del-mar!", "cafe del mar"))
        assertEquals(0, matchScore("", "anything"))
    }

    @Test fun resizedImagesKeepTheirCropOption() {
        val artist = "https://lh3.googleusercontent.com/photo=w120-h120-p-l90-rj"
        assertEquals("https://lh3.googleusercontent.com/photo=w240-h240-p-l90-rj", searchArtUrl(artist))
        assertEquals("https://lh3.googleusercontent.com/photo=w1200-h1200-p-l90-rj", highQualityArtUrl(artist))
        assertEquals("https://lh3.googleusercontent.com/cover=w240-h240-l90-rj", searchArtUrl("https://lh3.googleusercontent.com/cover=w120-h120-l90-rj"))
        assertEquals("https://yt3.ggpht.com/plain=w1200-h1200-l90-rj", highQualityArtUrl("https://yt3.ggpht.com/plain=s88"))
        assertEquals("https://yt3.googleusercontent.com/bare=w240-h240-l90-rj", searchArtUrl("https://yt3.googleusercontent.com/bare"))
        assertEquals("https://i.ytimg.com/vi/abc/maxresdefault.jpg", highQualityArtUrl("https://i.ytimg.com/vi/abc/hqdefault.jpg"))
    }

    @Test fun artistNamesExcludeAlbumAndDurationAndChooseLargestArt() {
        val song = YtMusic.parseSong(json(songRenderer))!!
        assertEquals("Song & title", song.title)
        assertEquals("Artist A, Artist B", song.artist)
        assertEquals("https://example.com/large.jpg", song.albumArtUrl)
        assertEquals(listOf("UCa", "UCb"), song.artists.map { it.id })
    }

    @Test fun searchResultsPreserveOrderAndContinuations() {
        val entries = (0 until 7).joinToString(",") { """{"musicResponsiveListItemRenderer": ${songRenderer.replace("Song & title", "Song $it")}}""" }
        val (results, _) = YtMusic.parsePage(json("""{"contents": {"sectionListRenderer": {"contents": [
            {"musicShelfRenderer": {"title": {"runs": [{"text": "Songs"}]}, "contents": [$entries]}}]}}}"""))
        assertEquals(7, results.size)
        assertEquals("Song 0", results[0].title)
        assertEquals("Song 4", results[4].title)
        val (songs, next) = YtMusic.parsePage(json("""{"continuationContents": {"musicShelfContinuation": {
            "contents": [{"musicResponsiveListItemRenderer": $songRenderer}],
            "continuations": [{"nextContinuationData": {"continuation": "next-token"}}]}}}"""))
        assertEquals(1, songs.size)
        assertEquals("next-token", next)
    }

    @Test fun missingMetadataKeepsSongAndNonSongEntriesAreExcluded() {
        val minimal = json("""{"playlistItemData": {"videoId": "x"}, "flexColumns": [
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Only title"}]}}}]}""")
        val song = YtMusic.parseSong(minimal)!!
        assertNull(song.artist)
        assertNull(song.albumArtUrl)
        assertNull(YtMusic.parseSong(json("""{"flexColumns": [
            {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "No id"}]}}}]}""")))
    }

    @Test fun bufferedResultsAreNotLostBetweenBatches() {
        val cursor = io.github.oxidance.core.SearchCursor(pending = (0 until 60).map { song(it.toString()) }, initialized = true)
        val client = YtMusic()
        val first = client.searchPage("query", cursor)
        assertEquals(25, first.songs.size)
        assertEquals("24", first.songs[24].videoId)
        val second = client.searchPage("query", first.next)
        assertEquals("25", second.songs[0].videoId)
        val last = client.searchPage("query", second.next)
        assertEquals(10, last.songs.size)
        assertNull(last.next)
    }

    @Test fun profilePrefersSquarePortraits() {
        assertEquals("https://example.com/square.jpg", YtMusic.profileImageUrl(json("""[
            {"url": "https://example.com/square.jpg", "width": 320, "height": 320},
            {"url": "https://example.com/banner.jpg", "width": 2880, "height": 1200}]""")))
        assertEquals("https://yt3.googleusercontent.com/portrait=w320-h320-p-l90-rj", YtMusic.profileImageUrl(json("""[
            {"url": "https://yt3.googleusercontent.com/portrait=w2880-h1200-p-l90-rj", "width": 2880, "height": 1200}]""")))
    }

    @Test fun artistResultsKeepYoutubeOrderWithoutDuplicates() {
        fun row(id: String, name: String) = """{"musicResponsiveListItemRenderer": {
            "navigationEndpoint": {"browseEndpoint": {"browseId": "$id"}},
            "flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "$name"}]}}}]}}"""
        val artists = YtMusic.parseArtists(json("""{"contents": [${row("UCother", "Other")}, ${row("UCnujabes", "Nujabes")},
            ${row("UCnujabes", "Nujabes")}, ${row("MPREalbum", "Album")}]}"""))
        assertEquals(listOf("Other", "Nujabes"), artists.map { it.link.name })
    }

    @Test fun suggestionsKeepArtistsAlbumsAndSongs() {
        fun row(title: String, subtitle: String, navigation: String, videoId: String? = null) = """{"musicResponsiveListItemRenderer": {
            "navigationEndpoint": $navigation, ${videoId?.let { "\"playlistItemData\": {\"videoId\": \"$it\"}," } ?: ""}
            "flexColumns": [
                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "$title"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": $subtitle}}}]}}"""
        fun browse(id: String, page: String) = """{"browseEndpoint": {"browseId": "$id", "browseEndpointContextSupportedConfigs": {"browseEndpointContextMusicConfig": {"pageType": "$page"}}}}"""
        fun watch(id: String, kind: String) = """{"watchEndpoint": {"videoId": "$id", "watchEndpointMusicSupportedConfigs": {"watchEndpointMusicConfig": {"musicVideoType": "$kind"}}}}"""
        val artistRun = """[{"text": "Song"}, {"text": " • "}, {"text": "Piero Piccioni", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCpiero"}}}]"""
        val suggestions = YtMusic.parseSuggestions(json("""{"contents": [{"searchSuggestionsSectionRenderer": {"contents": [
            ${row("Piero Piccioni", """[{"text": "101M monthly audience"}]""", browse("UCpiero", "MUSIC_PAGE_TYPE_ARTIST"))},
            ${row("Greatest Hits", """[{"text": "Album • Piero Piccioni • 2022"}]""", browse("MPREb_hits", "MUSIC_PAGE_TYPE_ALBUM"))},
            ${row("Easy Lovers", artistRun, watch("easy", "MUSIC_VIDEO_TYPE_ATV"), "easy")},
            ${row("Live video", """[{"text": "Video • 1M views"}]""", watch("video", "MUSIC_VIDEO_TYPE_OMV"), "video")},
            ${row("Mix", """[{"text": "Playlist • YouTube Music"}]""", browse("VLmix", "MUSIC_PAGE_TYPE_PLAYLIST"))}
        ]}}]}"""))
        assertEquals("music videos and playlists are skipped", 3, suggestions.size)
        assertEquals("UCpiero", (suggestions[0] as SearchItem.ArtistItem).artist.link.id)
        assertEquals("Album • Piero Piccioni • 2022", (suggestions[1] as SearchItem.AlbumItem).album.subtitle)
        assertEquals("Piero Piccioni", (suggestions[2] as SearchItem.SongItem).song.artists[0].name)
    }

    @Test fun albumPageFillsTrackArtistsAndArtwork() {
        fun track(id: String, title: String, artists: String) = """{"musicResponsiveListItemRenderer": {
            "playlistItemData": {"videoId": "$id"},
            "flexColumns": [
                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "$title"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": $artists}}}]}}"""
        val page = YtMusic.parseAlbumPage(json("""{"contents": {"twoColumnBrowseResultsRenderer": {
            "tabs": [{"content": {"musicResponsiveHeaderRenderer": {
                "title": {"runs": [{"text": "Greatest Hits"}]},
                "subtitle": {"runs": [{"text": "Album • 2022"}]},
                "straplineTextOne": {"runs": [{"text": "Piero Piccioni", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCpiero"}}}]},
                "secondSubtitle": {"runs": [{"text": "2 songs • 7 minutes"}]},
                "thumbnail": {"musicThumbnailRenderer": {"thumbnail": {"thumbnails": [
                    {"url": "https://example.com/small.jpg", "width": 60, "height": 60},
                    {"url": "https://example.com/large.jpg", "width": 544, "height": 544}]}}}}}}],
            "secondaryContents": {"sectionListRenderer": {"contents": [{"musicShelfRenderer": {"contents": [
                ${track("one", "Easy Lovers", "[]")},
                {"musicResponsiveListItemRenderer": {"flexColumns": [{"musicResponsiveListItemFlexColumnRenderer": {"text": {"runs": [{"text": "Gone"}]}}}]}},
                ${track("two", "Duet", """[{"text": "Guest", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCguest"}}}]""")}
            ]}}]}}}}}"""), "MPREb_hits")
        assertEquals("Greatest Hits", page.album.title)
        assertEquals("Album • Piero Piccioni", page.album.subtitle)
        assertEquals("Album • 2022 · 2 songs • 7 minutes", page.details)
        assertEquals("https://example.com/large.jpg", page.album.imageUrl)
        assertEquals("unavailable tracks are skipped", 2, page.songs.size)
        assertEquals("Piero Piccioni", page.songs[0].artist)
        assertEquals("UCpiero", page.songs[0].artists[0].id)
        assertEquals("Guest", page.songs[1].artist)
        assertTrue(page.songs.all { it.albumArtUrl == "https://example.com/large.jpg" })
    }

    @Test fun likesAndPlaylistMembershipUseVideoId() {
        var library = Library().toggleLike(song("one")).toggleLike(song("two")).toggleLike(song("one"))
        assertFalse(library.isLiked(song("one")))
        assertTrue(library.isLiked(song("two")))
        val (created, id) = library.createPlaylist(" Mix ")
        library = created.addSong(id, song("one"))!!
        assertNull(library.addSong(id, song("one")))
        library = library.addSong(id, song("two"))!!
        assertEquals(2, library.playlists[0].songs.size)
        assertThrows(Exception::class.java) { library.createPlaylist("mix") }
        assertThrows(Exception::class.java) { library.createPlaylist(" ") }
    }

    @Test fun actualModificationsReorderPlaylists() {
        var (library, first) = Library().createPlaylist("First")
        val (withSecond, second) = library.createPlaylist("Second")
        library = withSecond
        assertEquals(second, library.sortedPlaylists()[0].id)
        library = library.addSong(first, song("one"))!!
        assertEquals(first, library.sortedPlaylists()[0].id)
        library = library.addSong(second, song("two"))!!
        assertNull("duplicate adds must not modify ordering", library.addSong(first, song("one")))
        assertEquals(second, library.sortedPlaylists()[0].id)
        library = library.removeSong(first, song("one"))
        assertEquals(first, library.sortedPlaylists()[0].id)
    }

    @Test fun renameAndDeletePreserveOtherCollections() {
        var (library, first) = Library().createPlaylist("First")
        val (withSecond, second) = library.createPlaylist("Second")
        library = withSecond.addSong(first, song("one"))!!.toggleLike(song("one"))
        assertThrows(Exception::class.java) { library.renamePlaylist(first, "second") }
        assertThrows(Exception::class.java) { library.renamePlaylist(first, " ") }
        library = library.renamePlaylist(first, " Renamed ")
        assertEquals("Renamed", library.sortedPlaylists()[0].name)
        library = library.deletePlaylist(first)
        assertEquals(listOf(second), library.playlists.map { it.id })
        assertTrue(library.isLiked(song("one")))
        assertThrows(Exception::class.java) { library.deletePlaylist(first) }
    }

    @Test fun mergeWithoutBaseCombinesBothSides() {
        val local = Library().toggleLike(song("a")).toggleLike(song("b"))
        val remote = Library().toggleLike(song("c")).toggleLike(song("b"))
        val merged = Library.merge(null, local, remote)
        assertEquals(listOf("b", "a", "c"), ids(merged.liked))
        assertEquals(merged, Library.merge(null, merged, Library()))
    }

    @Test fun mergeAppliesRemovalsAndAdditionsFromBothSides() {
        var base = Library()
        for (id in listOf("a", "b", "c")) base = base.toggleLike(song(id))
        val local = base.toggleLike(song("a")).toggleLike(song("new-local"))
        val remote = base.toggleLike(song("c")).toggleLike(song("new-remote"))
        val merged = Library.merge(base, local, remote)
        assertEquals(listOf("new-remote", "new-local", "b"), ids(merged.liked))
        assertEquals(merged, Library.merge(merged, merged, merged))
    }

    @Test fun mergePlaylistsById() {
        var (base, kept) = Library().createPlaylist("Kept")
        val (withDeleted, deleted) = base.createPlaylist("Deleted")
        base = withDeleted.addSong(kept, song("a"))!!.addSong(deleted, song("a"))!!
        var local = base.addSong(kept, song("local"))!!.addSong(deleted, song("local"))!!.renamePlaylist(kept, "Renamed")
        val (withLocalOnly, localOnly) = local.createPlaylist("Local only")
        local = withLocalOnly
        val remote = base.addSong(kept, song("remote"))!!.removeSong(kept, song("a")).deletePlaylist(deleted)
        val merged = Library.merge(base, local, remote)
        assertEquals("deletion wins over additions elsewhere", 2, merged.playlists.size)
        val playlist = merged.playlists.first { it.id == kept }
        assertEquals("Renamed", playlist.name)
        assertEquals(listOf("remote", "local"), ids(playlist.songs))
        assertTrue(merged.playlists.any { it.id == localOnly })
    }

    @Test fun mergeResolvesConcurrentRenamesAndDuplicateNames() {
        val (base, id) = Library().createPlaylist("Mix")
        val local = base.renamePlaylist(id, "Local name")
        var remote = base.renamePlaylist(id, "Remote name")
        remote = remote.copy(playlists = listOf(remote.playlists[0].copy(modifiedAt = local.playlists[0].modifiedAt + 1)))
        assertEquals("the later rename wins", "Remote name", Library.merge(base, local, remote).playlists[0].name)
        val first = Library().createPlaylist("Road trip").first
        var second = Library().createPlaylist("road trip").first
        second = second.copy(playlists = listOf(second.playlists[0].copy(id = second.playlists[0].id + 1)))
        assertEquals(listOf("Road trip", "road trip (2)"), Library.merge(null, first, second).playlists.map { it.name }.sorted())
    }

    @Test fun libraryRoundTripAndDesktopFormat() {
        val directory = Files.createTempDirectory("oxidance-library").toFile()
        val file = File(directory, "library.json")
        assertEquals(Library(), Library.load(file))
        var (library, id) = Library().toggleLike(song("one")).createPlaylist("Saved")
        library = library.addSong(id, song("one"))!!
        library.save(file)
        assertEquals(library, Library.load(file))
        val text = file.readText()
        assertTrue("nulls are written like serde", "\"artist\": null" in text)
        assertFalse("empty artist links are omitted like serde", "\"artists\"" in text)
        // A library written by the desktop app, including an old song without artist links.
        val desktop = """{"liked":[{"video_id":"old","title":"Old","artist":"Artist","album_art_url":null},
            {"video_id":"new","title":"New","artist":"A","album_art_url":"https://x/y.jpg","artists":[{"id":"UCa","name":"A"}]}],
            "playlists":[{"id":5,"name":"Mix","modified_at":7,"songs":[]}],"revision":7}"""
        val parsed = LibraryJson.decodeFromString(Library.serializer(), desktop)
        assertTrue(parsed.liked[0].artists.isEmpty())
        assertEquals("UCa", parsed.liked[1].artists[0].id)
        assertEquals(7L, parsed.revision)
        file.writeText("invalid json")
        assertThrows(Exception::class.java) { Library.load(file) }
        directory.deleteRecursively()
    }

    @Test fun urlsAreNormalized() {
        assertEquals("https://example.org/oxidance/", Dav.normalizeUrl("example.org/oxidance").toString())
        assertEquals("http://127.0.0.1:8081/", Dav.normalizeUrl("http://127.0.0.1:8081/").toString())
        assertThrows(IOException::class.java) { Dav.normalizeUrl("ftp://example.org") }
        assertThrows(IOException::class.java) { Dav.normalizeUrl("https://user:secret@example.org") }
    }

    @Test fun listingSkipsCollections() {
        val listing = Dav.parseListing("""<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:">
<D:response><D:href>/oxidance/songs/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
<D:response><D:href>/oxidance/songs/abc-_1.webm</D:href><D:propstat><D:prop><D:resourcetype></D:resourcetype><D:getcontentlength>1234</D:getcontentlength></D:prop></D:propstat></D:response>
<D:response><D:href>/oxidance/songs/abc-_1.cover</D:href><D:propstat><D:prop><D:resourcetype/><D:getcontentlength>5</D:getcontentlength></D:prop></D:propstat></D:response>
</D:multistatus>""".toByteArray())
        assertEquals(mapOf("abc-_1.webm" to 1234L, "abc-_1.cover" to 5L), listing)
    }

    @Test fun indexMapsFinishedAudioFilesById() {
        val directory = Files.createTempDirectory("oxidance-index").toFile()
        for ((name, contents) in listOf("A - Song [one].webm" to "audio", "A - Song [one].cover" to "art", "B [two].m4a.part" to "partial",
            "C [three].mp3" to "", "Other [x] song.mp3" to "audio", "Brackets [in] title [four].opus" to "audio", "Plain.mp3" to "audio")) {
            File(directory, name).writeText(contents)
        }
        val index = FileIndex.scan(directory)
        assertEquals(File(directory, "A - Song [one].webm"), index.file("one"))
        assertEquals(File(directory, "A - Song [one].cover"), index.art("one"))
        assertEquals(File(directory, "Brackets [in] title [four].opus"), index.file("four"))
        for (missing in listOf("two", "three", "x", "in")) assertNull(missing, index.file(missing))
        assertNull(FileIndex.scan(File(directory, "missing")).file("one"))
        directory.deleteRecursively()
    }

    @Test fun localNamesMatchYtDlp() {
        assertEquals("AC⧸DC - Back [id].webm", localName(Song("id", "Back", "AC/DC"), "webm"))
        assertEquals("Untitled [id].m4a", localName(Song("id", "Untitled"), "m4a"))
    }

    @Test fun failedAttemptsAreRetriedUntilTheLimit() {
        val cancelled = AtomicBoolean(false)
        var calls = 0
        assertEquals(3, withRetries(3, 0, cancelled) { calls++; if (calls < 3) throw IOException("HTTP 403") else calls })
        calls = 0
        val error = assertThrows(IOException::class.java) { withRetries(3, 0, cancelled) { calls++; throw IOException("failure $calls") } }
        assertEquals("failure 3", error.message)
        cancelled.set(true)
        calls = 0
        assertThrows(IOException::class.java) { withRetries(3, 0, cancelled) { calls++; throw IOException("cancelled") } }
        assertEquals("cancellation stops retries", 1, calls)
    }
}
