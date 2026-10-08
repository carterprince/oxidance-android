package io.github.oxidance.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.oxidance.App
import io.github.oxidance.core.Song
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun AppScreen(viewModel: MainViewModel, player: PlayerConnection, openPlayerRequests: Int) {
    val app = App.instance
    val context = LocalContext.current
    val library by app.library.flow.collectAsStateWithLifecycle()
    val files by app.downloads.files.collectAsStateWithLifecycle()
    val pending by app.downloads.pending.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { app.messages.collect { snackbar.showSnackbar(it) } }
    var picking by remember { mutableStateOf<Song?>(null) }
    val scope = rememberCoroutineScope()

    // The full player's position: 0 when open, 1 when closed.
    val closed = remember { Animatable(1f) }
    fun openPlayer() = scope.launch { closed.animateTo(0f, tween(300)) }
    fun closePlayer() = scope.launch { closed.animateTo(1f, tween(250)) }
    LaunchedEffect(openPlayerRequests) { if (openPlayerRequests > 0 && player.current != null) openPlayer() }
    val song = player.current
    LaunchedEffect(song == null) { if (song == null) closed.snapTo(1f) }

    // Media notifications need permission on Android 13 and later; ask the first time something plays.
    var askedNotifications by remember { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askForNotifications() {
        if (askedNotifications || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        askedNotifications = true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val actions = SongActions(
        play = { picked ->
            val songs = viewModel.searchSongs.takeIf { list -> list.any { it.videoId == picked.videoId } } ?: listOf(picked)
            player.start(songs, songs.indexOfFirst { it.videoId == picked.videoId }, "Search: ${viewModel.query.trim()}")
        },
        toggleLike = { viewModel.toggleLike(it) },
        addToPlaylist = { picking = it },
        openArtist = { link -> closePlayer(); viewModel.open(Screen.ArtistPage(link)) },
        playNext = { player.playNext(it) },
        addToQueue = { player.addToQueue(it) },
    )
    val lists = Lists(library, files, pending, song?.videoId, actions)
    // Every page's playback asks for notification permission first.
    SideEffect { player.beforeStart = { askForNotifications() } }

    BackHandler(enabled = closed.value < 1f) { closePlayer() }
    BackHandler(enabled = closed.value >= 1f && viewModel.canGoBack) { viewModel.back() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val height = constraints.maxHeight.toFloat()
        val drag = rememberDraggableState { delta -> scope.launch { closed.snapTo((closed.value + delta / height).coerceIn(0f, 1f)) } }
        fun settle(velocity: Float) {
            if (velocity > 1500f || velocity > -1500f && closed.value > 0.3f) closePlayer() else openPlayer()
        }
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    if (song != null) MiniPlayer(player, song, files, onExpand = { openPlayer() },
                        modifier = Modifier.draggable(drag, Orientation.Vertical, onDragStopped = { velocity -> settle(velocity) }))
                    NavigationBar {
                        NavigationBarItem(selected = viewModel.tab == Tab.Search, onClick = { viewModel.selectTab(Tab.Search) },
                            icon = { Icon(Icons.Default.Search, null) }, label = { Text("Search") })
                        NavigationBarItem(selected = viewModel.tab == Tab.Library, onClick = { viewModel.selectTab(Tab.Library) },
                            icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Library") })
                    }
                }
            },
        ) { padding ->
            val content = PaddingValues(bottom = 16.dp)
            Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                when (val screen = viewModel.screen) {
                    Screen.Search -> Box(Modifier.statusBarsPadding()) { SearchScreen(viewModel, lists, content) }
                    Screen.Library -> LibraryScreen(viewModel, lists, content)
                    Screen.Liked -> CollectionScreen(viewModel, lists, player, null, content)
                    is Screen.Playlist -> CollectionScreen(viewModel, lists, player, screen.id, content)
                    is Screen.ArtistPage -> ArtistScreen(viewModel, lists, player, screen.link, content)
                    is Screen.AlbumScreen -> AlbumScreen(viewModel, lists, player, screen.album, content)
                    Screen.Settings -> SettingsScreen(viewModel, lists, content)
                }
            }
        }
        if (song != null && closed.value < 1f) {
            NowPlaying(player, song, library, files, actions, onCollapse = { closePlayer() },
                modifier = Modifier.offset { IntOffset(0, (closed.value * height).roundToInt()) }
                    .draggable(drag, Orientation.Vertical, onDragStopped = { velocity -> settle(velocity) }))
        }
    }
    picking?.let { PlaylistPicker(it, viewModel) { picking = null } }
}
