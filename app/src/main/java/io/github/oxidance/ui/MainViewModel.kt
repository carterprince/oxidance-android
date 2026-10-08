package io.github.oxidance.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.oxidance.App
import io.github.oxidance.core.Album
import io.github.oxidance.core.AlbumPage
import io.github.oxidance.core.Artist
import io.github.oxidance.core.ArtistLink
import io.github.oxidance.core.Profile
import io.github.oxidance.core.SearchCursor
import io.github.oxidance.core.SearchItem
import io.github.oxidance.core.Song
import io.github.oxidance.core.matchScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Search : Screen
    data object Library : Screen
    data object Liked : Screen
    data class Playlist(val id: Long) : Screen
    data class ArtistPage(val link: ArtistLink) : Screen
    data class AlbumScreen(val album: Album) : Screen
    data object Settings : Screen
}

enum class Tab { Search, Library }

/** A page that loads from YouTube Music. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Loaded<T>(val value: T) : Loadable<T>
    data class Failed(val message: String) : Loadable<Nothing>
}

class MainViewModel : ViewModel() {
    private val app = App.instance

    // Navigation: each tab keeps its own back stack.
    var tab by mutableStateOf(Tab.Library)
    val stacks = mapOf(Tab.Search to mutableStateListOf<Screen>(Screen.Search), Tab.Library to mutableStateListOf<Screen>(Screen.Library))
    val screen: Screen get() = stacks.getValue(tab).last()
    val canGoBack: Boolean get() = stacks.getValue(tab).size > 1 || tab != Tab.Library

    fun open(screen: Screen) { stacks.getValue(tab).add(screen) }

    fun selectTab(selected: Tab) {
        // Selecting the current tab again returns to its first page.
        if (selected == tab) stacks.getValue(tab).let { while (it.size > 1) it.removeAt(it.lastIndex) }
        tab = selected
    }

    fun back() {
        val stack = stacks.getValue(tab)
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else if (tab != Tab.Library) tab = Tab.Library
    }

    // Search, following the desktop app: suggestions as you type, then full results.
    var query by mutableStateOf("")
        private set
    var suggestions by mutableStateOf<List<SearchItem>>(emptyList())
        private set
    var ranked by mutableStateOf<List<SearchItem>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var searchError by mutableStateOf<String?>(null)
        private set
    /** The query whose full results are shown, if any. */
    private var searchedQuery: String? = null
    private var cursor: SearchCursor? = null
    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    val results: List<SearchItem> get() {
        val shown = suggestions.map { it.key }.toSet()
        return suggestions + ranked.filter { it.key !in shown }
    }

    val hasMore: Boolean get() = cursor != null && searchError == null

    fun updateQuery(text: String) {
        query = text
        suggestJob?.cancel()
        searchJob?.cancel()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            suggestions = emptyList(); ranked = emptyList(); cursor = null; searchedQuery = null; searching = false; searchError = null
            return
        }
        suggestJob = viewModelScope.launch {
            delay(200)
            val items = withContext(Dispatchers.IO) { runCatching { app.youtube.suggestions(trimmed) } }
            items.onSuccess { suggestions = it }
        }
        searchJob = viewModelScope.launch {
            delay(1000)
            search(trimmed)
        }
    }

    /** Runs the full search now, as when pressing Enter. */
    fun submit() {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed == searchedQuery && searchError == null) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(trimmed) }
    }

    fun retrySearch() {
        searchError = null
        val trimmed = query.trim()
        searchJob?.cancel()
        searchJob = viewModelScope.launch { if (trimmed == searchedQuery && cursor != null) more() else search(trimmed) }
    }

    private suspend fun search(text: String) {
        searching = true
        searchError = null
        try {
            val (artists, albums, page) = withContext(Dispatchers.IO) {
                val artists = async { runCatching { app.youtube.searchArtists(text) }.getOrDefault(emptyList()) }
                val albums = async { runCatching { app.youtube.searchAlbums(text) }.getOrDefault(emptyList()) }
                val page = runCatching { app.youtube.searchPage(text) }
                Triple(artists.await(), albums.await(), page)
            }
            val result = page.getOrElse {
                searchError = "Could not fetch songs: ${it.message}"
                return
            }
            searchedQuery = text
            cursor = result.next
            val items = artists.take(5).map(SearchItem::ArtistItem) + albums.take(3).map(SearchItem::AlbumItem) +
                rankSongs(text, result.songs.map(SearchItem::SongItem))
            // Best matches first; ties keep YouTube Music's order.
            ranked = items.sortedByDescending { matchScore(text, it.text()) }
        } finally {
            searching = false
        }
    }

    private fun rankSongs(text: String, items: List<SearchItem>) = items.sortedByDescending { matchScore(text, it.text()) }

    fun loadMore() {
        if (loadingMore || searching || cursor == null || searchError != null || query.trim() != searchedQuery) return
        viewModelScope.launch { more() }
    }

    private suspend fun more() {
        val text = searchedQuery ?: return
        loadingMore = true
        try {
            val page = withContext(Dispatchers.IO) { runCatching { app.youtube.searchPage(text, cursor) } }
            page.onSuccess { result ->
                if (searchedQuery != text) return
                cursor = result.next
                val shown = results.map { it.key }.toSet()
                ranked = ranked + rankSongs(text, result.songs.map(SearchItem::SongItem).filter { it.key !in shown })
            }.onFailure { searchError = "Could not load more songs: ${it.message}" }
        } finally {
            loadingMore = false
        }
    }

    val searchSongs: List<Song> get() = results.mapNotNull { (it as? SearchItem.SongItem)?.song }

    fun searchStatus(): String {
        val items = results
        val artists = items.count { it is SearchItem.ArtistItem }
        val albums = items.count { it is SearchItem.AlbumItem }
        val songs = items.count { it is SearchItem.SongItem }
        if (artists + albums + songs == 0) return "No results found. Try another query."
        fun label(number: Int, one: String, many: String) = "$number ${if (number == 1) one else many}"
        val parts = mutableListOf<String>()
        if (artists > 0) parts += label(artists, "artist", "artists")
        if (albums > 0) parts += label(albums, "album", "albums")
        parts += label(songs, "song", "songs")
        if (searchedQuery != null && cursor == null && !loadingMore && !searching) parts += "End of results"
        return parts.joinToString(" · ")
    }

    // Artist and album pages, cached while the app runs.
    val profiles = mutableStateMapOf<String, Loadable<Profile>>()
    val albums = mutableStateMapOf<String, Loadable<AlbumPage>>()

    fun loadProfile(link: ArtistLink, retry: Boolean = false) {
        if (!retry && profiles[link.id].let { it is Loadable.Loaded || it is Loadable.Loading }) return
        profiles[link.id] = Loadable.Loading
        viewModelScope.launch {
            profiles[link.id] = withContext(Dispatchers.IO) { runCatching { app.youtube.profile(link.id) } }
                .fold({ Loadable.Loaded(it) }, { Loadable.Failed("Could not load this artist: ${it.message}") })
        }
    }

    fun loadAlbum(album: Album, retry: Boolean = false) {
        if (!retry && albums[album.id].let { it is Loadable.Loaded || it is Loadable.Loading }) return
        albums[album.id] = Loadable.Loading
        viewModelScope.launch {
            albums[album.id] = withContext(Dispatchers.IO) { runCatching { app.youtube.album(album.id) } }
                .fold({ Loadable.Loaded(it) }, { Loadable.Failed("Could not load this album: ${it.message}") })
        }
    }

    fun openArtist(artist: Artist) = open(Screen.ArtistPage(artist.link))

    // Library changes.
    fun toggleLike(song: Song) = app.library.change { it.toggleLike(song) }

    fun addToPlaylist(id: Long, song: Song) {
        val name = app.library.value.playlists.firstOrNull { it.id == id }?.name ?: return
        val changed = app.library.change { it.addSong(id, song) }
        app.toast(if (changed) "Added to $name." else "Already in $name.")
    }

    /** Creates a playlist, optionally adding a song to it; returns an error message or null. */
    fun createPlaylist(name: String, song: Song?): String? {
        var error: String? = null
        app.library.change { library ->
            try {
                val (created, id) = library.createPlaylist(name)
                if (song != null) created.addSong(id, song) else created
            } catch (failure: io.github.oxidance.core.LibraryException) { error = failure.message; null }
        }
        if (error == null && song != null) app.toast("Added to ${name.trim()}.")
        return error
    }

    fun renamePlaylist(id: Long, name: String): String? {
        var error: String? = null
        app.library.change { library ->
            try { library.renamePlaylist(id, name) } catch (failure: io.github.oxidance.core.LibraryException) { error = failure.message; null }
        }
        return error
    }

    fun deletePlaylist(id: Long) {
        if (app.library.change { it.deletePlaylist(id) }) {
            stacks.values.forEach { stack -> stack.removeAll { it == Screen.Playlist(id) } }
        }
    }

    fun removeFromPlaylist(id: Long, song: Song) = app.library.change { it.removeSong(id, song) }
}
