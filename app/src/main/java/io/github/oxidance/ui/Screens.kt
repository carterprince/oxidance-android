package io.github.oxidance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.oxidance.App
import io.github.oxidance.BuildConfig
import io.github.oxidance.core.Album
import io.github.oxidance.core.FileIndex
import io.github.oxidance.core.Library
import io.github.oxidance.core.SearchItem
import io.github.oxidance.core.Song
import io.github.oxidance.core.highQualityArtUrl
import io.github.oxidance.core.searchArtUrl
import kotlinx.coroutines.launch

/** What every page needs to show songs. */
class Lists(
    val library: Library,
    val files: FileIndex,
    val pending: Set<String>,
    val playing: String?,
    val actions: SongActions,
)

fun LazyListScope.songItems(songs: List<Song>, lists: Lists, keyPrefix: String = "song") {
    items(songs, key = { "$keyPrefix:${it.videoId}" }) { song ->
        SongRow(song, lists.actions, lists.library.isLiked(song), lists.playing == song.videoId, lists.files, lists.pending)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageBar(title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        actions = { actions() },
    )
}

@Composable
private fun PlayButtons(songs: List<Song>, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onPlay, enabled = songs.isNotEmpty()) {
            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play")
        }
        FilledTonalButton(onClick = onShuffle, enabled = songs.isNotEmpty()) {
            Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Shuffle")
        }
    }
}

// Search

@Composable
fun SearchScreen(viewModel: MainViewModel, lists: Lists, contentPadding: PaddingValues) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { if (viewModel.query.isEmpty()) focus.requestFocus() }
    val results = viewModel.results
    // Load more results as the end of the list comes into view.
    val nearEnd by remember { derivedStateOf {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        last >= listState.layoutInfo.totalItemsCount - 6
    } }
    LaunchedEffect(nearEnd, results.size) { if (nearEnd) viewModel.loadMore() }
    LaunchedEffect(viewModel.query) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = viewModel.query, onValueChange = viewModel::updateQuery,
            placeholder = { Text("Songs, artists, or albums") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (viewModel.query.isNotEmpty()) IconButton(onClick = { viewModel.updateQuery(""); focus.requestFocus() }) { Icon(Icons.Default.Clear, "Clear search") } },
            singleLine = true, shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.submit(); keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus).semantics { contentDescription = "Search" },
        )
        if (viewModel.query.isBlank()) {
            EmptyState(Icons.Default.Search, "Search for songs to start your library.")
            return@Column
        }
        LazyColumn(state = listState, contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            for (item in results) {
                when (item) {
                    is SearchItem.ArtistItem -> item(key = item.key) {
                        ListItem(headlineContent = { Text(item.artist.link.name) }, supportingContent = { Text("Artist") },
                            leadingContent = { Artwork(null, 52.dp, shape = CircleShape, placeholder = Icons.Default.Person, model = item.artist.imageUrl?.let(::searchArtUrl)) },
                            modifier = Modifier.clickable { keyboard?.hide(); viewModel.openArtist(item.artist) })
                    }
                    is SearchItem.AlbumItem -> item(key = item.key) {
                        ListItem(headlineContent = { Text(item.album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(item.album.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Artwork(null, 52.dp, placeholder = Icons.Default.Album, model = item.album.imageUrl?.let(::searchArtUrl)) },
                            modifier = Modifier.clickable { keyboard?.hide(); viewModel.open(Screen.AlbumScreen(item.album)) })
                    }
                    is SearchItem.SongItem -> item(key = item.key) {
                        SongRow(item.song, lists.actions, lists.library.isLiked(item.song), lists.playing == item.song.videoId, lists.files, lists.pending)
                    }
                }
            }
            item(key = "status") {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val error = viewModel.searchError
                    when {
                        error != null -> {
                            Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                            TextButton(onClick = viewModel::retrySearch) { Text("Retry") }
                        }
                        viewModel.searching || viewModel.loadingMore -> CircularProgressIndicator(Modifier.size(28.dp))
                        results.isNotEmpty() || !viewModel.hasMore -> Text(viewModel.searchStatus(), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// Library

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: MainViewModel, lists: Lists, contentPadding: PaddingValues) {
    val sync by App.instance.sync.status.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Library") }, actions = {
            IconButton(onClick = { viewModel.open(Screen.Settings) }) {
                when {
                    sync.running -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    sync.failed -> Icon(Icons.Default.Error, "Sync problem", tint = MaterialTheme.colorScheme.error)
                    sync.connected -> Icon(Icons.Default.CloudDone, "Synced")
                    else -> Icon(Icons.Default.CloudOff, "Sync is off")
                }
            }
            IconButton(onClick = { viewModel.open(Screen.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
        })
        LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item(key = "liked") {
                ListItem(
                    headlineContent = { Text("Liked songs", fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(songCount(lists.library.liked.size)) },
                    leadingContent = {
                        Box(Modifier.size(56.dp).background(Brush.linearGradient(listOf(Color(0xFF7E2F12), Color(0xFFEB9A62))), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center) { Icon(Icons.Default.Favorite, null, tint = Color.White) }
                    },
                    modifier = Modifier.clickable { viewModel.open(Screen.Liked) },
                )
            }
            item(key = "new") {
                ListItem(headlineContent = { Text("New playlist") },
                    leadingContent = { Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)),
                        contentAlignment = Alignment.Center) { Icon(Icons.Default.Add, null) } },
                    modifier = Modifier.clickable { creating = true })
            }
            items(lists.library.sortedPlaylists(), key = { "playlist:${it.id}" }) { playlist ->
                ListItem(
                    headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("Playlist · ${songCount(playlist.songs.size)}") },
                    leadingContent = { Artwork(null, 56.dp, placeholder = Icons.AutoMirrored.Filled.QueueMusic,
                        model = playlist.songs.firstOrNull()?.let { songArt(it, lists.files) }) },
                    modifier = Modifier.clickable { viewModel.open(Screen.Playlist(playlist.id)) },
                )
            }
            if (lists.library.isEmpty()) item(key = "empty") {
                EmptyState(Icons.Default.LibraryMusic, "Like songs or make playlists, and they're saved here for offline listening.")
            }
        }
    }
    if (creating) NameDialog("New playlist", "Create", "", { creating = false }) { viewModel.createPlaylist(it, null) }
}

/** Liked songs or a playlist, with a filter like the desktop app's collection search. */
@Composable
fun CollectionScreen(viewModel: MainViewModel, lists: Lists, player: PlayerConnection, playlistId: Long?, contentPadding: PaddingValues) {
    val playlist = playlistId?.let { id -> lists.library.playlists.firstOrNull { it.id == id } }
    if (playlistId != null && playlist == null) { LaunchedEffect(Unit) { viewModel.back() }; return }
    val title = playlist?.name ?: "Liked songs"
    val songs = playlist?.songs ?: lists.library.liked
    var filter by rememberSaveable(playlistId) { mutableStateOf("") }
    var filtering by rememberSaveable(playlistId) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val query = filter.trim().lowercase()
    val shown = if (query.isEmpty()) songs else songs.filter { "${it.title} ${it.artist ?: ""}".lowercase().contains(query) }
    val actions = if (playlist != null) SongActions(
        play = { song -> player.start(shown, shown.indexOf(song), title) },
        toggleLike = lists.actions.toggleLike, addToPlaylist = lists.actions.addToPlaylist, openArtist = lists.actions.openArtist,
        playNext = lists.actions.playNext, addToQueue = lists.actions.addToQueue,
        removeFromPlaylist = { song -> viewModel.removeFromPlaylist(playlist.id, song) },
    ) else SongActions(
        play = { song -> player.start(shown, shown.indexOf(song), title) },
        toggleLike = lists.actions.toggleLike, addToPlaylist = lists.actions.addToPlaylist, openArtist = lists.actions.openArtist,
        playNext = lists.actions.playNext, addToQueue = lists.actions.addToQueue,
    )
    val pageLists = Lists(lists.library, lists.files, lists.pending, lists.playing, actions)
    Column(Modifier.fillMaxSize()) {
        PageBar(title, viewModel::back) {
            IconButton(onClick = { filtering = !filtering; if (!filtering) filter = "" }) { Icon(Icons.Default.FilterList, "Filter songs") }
            if (playlist != null) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Playlist options") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename…") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Delete…") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; deleting = true })
                }
            }
        }
        LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item(key = "header") {
                Column {
                    if (filtering) OutlinedTextField(filter, { filter = it }, singleLine = true,
                        placeholder = { Text(if (playlist == null) "Search liked songs" else "Search this playlist") },
                        leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    Text(songCount(songs.size) + if (songs.isNotEmpty()) " · ${songs.count { lists.files.file(it.videoId) != null }} downloaded" else "",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                    PlayButtons(shown, { player.start(shown, 0, title, shuffle = false) }, { player.shufflePlay(shown, title) })
                }
            }
            songItems(shown, pageLists)
            if (songs.isEmpty()) item(key = "empty") {
                EmptyState(if (playlist == null) Icons.Default.Favorite else Icons.AutoMirrored.Filled.QueueMusic,
                    if (playlist == null) "Tap a song's heart to save it here." else "This playlist is empty. Add songs from a song's menu.")
            } else if (shown.isEmpty()) item(key = "nomatch") {
                EmptyState(Icons.Default.Search, "No matching songs")
            }
        }
    }
    if (renaming && playlist != null) NameDialog("Rename playlist", "Rename", playlist.name, { renaming = false }) { viewModel.renamePlaylist(playlist.id, it) }
    if (deleting && playlist != null) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Delete ${playlist.name}?") },
        text = { Text("The playlist is removed from your library and synced devices. Downloaded songs that are also liked or in other playlists are kept.") },
        confirmButton = { TextButton(onClick = { deleting = false; viewModel.deletePlaylist(playlist.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
    )
}

// Artist and album pages

@Composable
private fun <T> LoadablePage(state: Loadable<T>?, onBack: () -> Unit, onRetry: () -> Unit, title: String, content: @Composable (T) -> Unit) {
    when (state) {
        is Loadable.Loaded -> content(state.value)
        is Loadable.Failed -> Column(Modifier.fillMaxSize()) {
            PageBar(title, onBack)
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
        else -> Column(Modifier.fillMaxSize()) {
            PageBar(title, onBack)
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
fun ArtistScreen(viewModel: MainViewModel, lists: Lists, player: PlayerConnection, link: io.github.oxidance.core.ArtistLink, contentPadding: PaddingValues) {
    LaunchedEffect(link.id) { viewModel.loadProfile(link) }
    LoadablePage(viewModel.profiles[link.id], viewModel::back, { viewModel.loadProfile(link, retry = true) }, link.name) { profile ->
        var expanded by rememberSaveable(link.id) { mutableStateOf(false) }
        val actions = SongActions({ song -> player.start(profile.songs, profile.songs.indexOf(song), profile.artist.link.name) },
            lists.actions.toggleLike, lists.actions.addToPlaylist, lists.actions.openArtist, lists.actions.playNext, lists.actions.addToQueue)
        Column(Modifier.fillMaxSize()) {
            PageBar("", viewModel::back)
            LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
                item(key = "header") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(null, 168.dp, shape = CircleShape, placeholder = Icons.Default.Person, model = profile.artist.imageUrl)
                        Spacer(Modifier.height(12.dp))
                        Text(profile.artist.link.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        profile.biography?.let { biography ->
                            Text(biography, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp).clickable { expanded = !expanded })
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Less" else "More") }
                        }
                    }
                    PlayButtons(profile.songs, { player.start(profile.songs, 0, profile.artist.link.name, shuffle = false) },
                        { player.shufflePlay(profile.songs, profile.artist.link.name) })
                    Text("Top songs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                songItems(profile.songs, Lists(lists.library, lists.files, lists.pending, lists.playing, actions))
                if (profile.songs.isEmpty()) item(key = "empty") { EmptyState(Icons.Default.Person, "This artist has no songs available.") }
            }
        }
    }
}

@Composable
fun AlbumScreen(viewModel: MainViewModel, lists: Lists, player: PlayerConnection, album: Album, contentPadding: PaddingValues) {
    LaunchedEffect(album.id) { viewModel.loadAlbum(album) }
    LoadablePage(viewModel.albums[album.id], viewModel::back, { viewModel.loadAlbum(album, retry = true) }, album.title) { page ->
        val actions = SongActions({ song -> player.start(page.songs, page.songs.indexOf(song), page.album.title) },
            lists.actions.toggleLike, lists.actions.addToPlaylist, lists.actions.openArtist, lists.actions.playNext, lists.actions.addToQueue)
        Column(Modifier.fillMaxSize()) {
            PageBar("", viewModel::back)
            LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
                item(key = "header") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Artwork(null, 220.dp, shape = RoundedCornerShape(10.dp), placeholder = Icons.Default.Album,
                            model = page.album.imageUrl?.let(::highQualityArtUrl))
                        Spacer(Modifier.height(12.dp))
                        Text(page.album.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Row(horizontalArrangement = Arrangement.Center) {
                            for (artist in page.artists) TextButton(onClick = { viewModel.open(Screen.ArtistPage(artist)) }) { Text(artist.name) }
                        }
                        Text(page.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    PlayButtons(page.songs, { player.start(page.songs, 0, page.album.title, shuffle = false) }, { player.shufflePlay(page.songs, page.album.title) })
                }
                songItems(page.songs, Lists(lists.library, lists.files, lists.pending, lists.playing, actions))
            }
        }
    }
}

// Settings

@Composable
fun SettingsScreen(viewModel: MainViewModel, lists: Lists, contentPadding: PaddingValues) {
    val app = App.instance
    val sync by app.sync.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(app.settings.syncUrl) }
    var username by rememberSaveable { mutableStateOf(app.settings.syncUsername) }
    var password by rememberSaveable { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var disconnecting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PageBar("Settings", viewModel::back)
        LazyColumn(contentPadding = contentPadding, modifier = Modifier.fillMaxSize()) {
            item {
                Text("Sync", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Text("Sync your library and saved songs with a WebDAV server, such as rclone serve webdav, and with Oxidance on your computer.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp))
                if (sync.connected) {
                    ListItem(headlineContent = { Text(app.settings.syncUrl) }, supportingContent = { Text("Signed in as ${app.settings.syncUsername}") },
                        leadingContent = { Icon(Icons.Default.Cloud, null) })
                    ListItem(headlineContent = { Text(if (sync.running) "Syncing" else if (sync.failed) "Sync problem" else "Status") },
                        supportingContent = { Text(sync.detail, color = if (sync.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                        leadingContent = { if (sync.running) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else Icon(if (sync.failed) Icons.Default.Error else Icons.Default.CloudDone, null) })
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { app.sync.request() }, enabled = !sync.running) { Text("Sync now") }
                        OutlinedButton(onClick = { disconnecting = true }) { Text("Disconnect") }
                    }
                } else {
                    if (sync.failed) Text(sync.detail, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(url, { url = it; error = null }, label = { Text("Server URL") }, placeholder = { Text("example.org/oxidance/") },
                            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(username, { username = it; error = null }, label = { Text("Username") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(password, { password = it; error = null }, label = { Text("Password") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            modifier = Modifier.fillMaxWidth())
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(enabled = !connecting && url.isNotBlank(), onClick = {
                            connecting = true
                            scope.launch {
                                app.sync.connect(url, username.trim(), password).onSuccess { password = "" }
                                    .onFailure { error = it.message ?: "Could not connect." }
                                connecting = false
                            }
                        }) {
                            if (connecting) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                            Text("Connect")
                        }
                        Text("The password is encrypted with a key kept in Android's keystore.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Text("Storage", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                val saved = lists.library.savedSongs()
                ListItem(headlineContent = { Text("Saved songs") },
                    supportingContent = { Text("${saved.count { lists.files.file(it.videoId) != null }} of ${saved.size} downloaded" +
                        if (lists.pending.isNotEmpty()) " · ${lists.pending.size} in progress" else "") })
                ListItem(headlineContent = { Text("Music folder") }, supportingContent = { Text(app.musicDirectory.path) })
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                ListItem(headlineContent = { Text("Oxidance ${BuildConfig.VERSION_NAME}") },
                    supportingContent = { Text("An unofficial YouTube Music client, not affiliated with YouTube or Google.") })
            }
        }
    }
    if (disconnecting) AlertDialog(
        onDismissRequest = { disconnecting = false },
        title = { Text("Disconnect sync server?") },
        text = { Text("Your library and downloaded songs stay on this device.") },
        confirmButton = { TextButton(onClick = { disconnecting = false; app.sync.disconnect() }) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = { disconnecting = false }) { Text("Cancel") } },
    )
}
