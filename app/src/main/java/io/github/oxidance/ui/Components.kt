package io.github.oxidance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import io.github.oxidance.App
import io.github.oxidance.core.ArtistLink
import io.github.oxidance.core.FileIndex
import io.github.oxidance.core.Song
import io.github.oxidance.core.searchArtUrl

/** Album art: the saved cover when the song is downloaded, otherwise the web image. */
@Composable
fun Artwork(url: String?, size: Dp?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(6.dp),
            placeholder: ImageVector = Icons.Default.MusicNote, model: Any? = url) {
    val sized = if (size != null) modifier.size(size) else modifier
    Box(sized.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (model == null) {
            Icon(placeholder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxSize(0.5f))
        } else {
            SubcomposeAsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                error = { Icon(placeholder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxSize(0.5f)) })
        }
    }
}

/** The best artwork source for a song: its saved cover or a URL suited to `large` or list sizes. */
fun songArt(song: Song, files: FileIndex, large: Boolean = false): Any? =
    files.art(song.videoId) ?: song.albumArtUrl?.let { if (large) io.github.oxidance.core.highQualityArtUrl(it) else searchArtUrl(it) }

/** The song actions every list offers. */
class SongActions(
    val play: (Song) -> Unit,
    val toggleLike: (Song) -> Unit,
    val addToPlaylist: (Song) -> Unit,
    val openArtist: (ArtistLink) -> Unit,
    val playNext: (Song) -> Unit,
    val addToQueue: (Song) -> Unit,
    /** Present on playlist pages. */
    val removeFromPlaylist: ((Song) -> Unit)? = null,
)

@Composable
fun SongRow(song: Song, actions: SongActions, liked: Boolean, playing: Boolean, files: FileIndex, pending: Set<String>) {
    val saved = files.file(song.videoId) != null
    ListItem(
        modifier = Modifier.clickable { actions.play(song) }.semantics { contentDescription = "Song ${song.title}" },
        leadingContent = { Artwork(null, 52.dp, model = songArt(song, files)) },
        headlineContent = {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (playing) FontWeight.SemiBold else null)
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    song.videoId in pending -> {
                        CircularProgressIndicator(Modifier.size(12.dp).semantics { contentDescription = "Downloading" }, strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    saved -> {
                        Icon(Icons.Default.CheckCircle, "Downloaded", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Text(song.artist ?: "Unknown artist", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { actions.toggleLike(song) }) {
                    Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (liked) "Unlike" else "Like",
                        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SongMenu(song, actions)
            }
        },
    )
}

@Composable
fun SongMenu(song: Song, actions: SongActions, includeQueue: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, "More options for ${song.title}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (includeQueue) {
                DropdownMenuItem(text = { Text("Play next") }, leadingIcon = { Icon(Icons.Default.SkipNext, null) },
                    onClick = { open = false; actions.playNext(song) })
                DropdownMenuItem(text = { Text("Add to queue") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                    onClick = { open = false; actions.addToQueue(song) })
            }
            DropdownMenuItem(text = { Text("Add to playlist…") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                onClick = { open = false; actions.addToPlaylist(song) })
            for (artist in song.artists) {
                DropdownMenuItem(text = { Text("Go to ${artist.name}") }, leadingIcon = { Icon(Icons.Default.Person, null) },
                    onClick = { open = false; actions.openArtist(artist) })
            }
            actions.removeFromPlaylist?.let { remove ->
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Remove from playlist") }, leadingIcon = { Icon(Icons.Default.PlaylistRemove, null) },
                    onClick = { open = false; remove(song) })
            }
        }
    }
}

/** Chooses a playlist for a song, or creates one. */
@Composable
fun PlaylistPicker(song: Song, viewModel: MainViewModel, onDismiss: () -> Unit) {
    val library by App.instance.library.flow.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog("New playlist", "Create", "", onDismiss = onDismiss) { name -> viewModel.createPlaylist(name, song) }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn {
                item {
                    ListItem(headlineContent = { Text("New playlist…") }, leadingContent = { Icon(Icons.Default.Add, null) },
                        modifier = Modifier.clickable { creating = true })
                }
                items(library.sortedPlaylists(), key = { it.id }) { playlist ->
                    val contains = playlist.songs.any { it.videoId == song.videoId }
                    ListItem(headlineContent = { Text(playlist.name) },
                        supportingContent = { Text(if (contains) "Already added" else songCount(playlist.songs.size)) },
                        modifier = Modifier.clickable(enabled = !contains) { viewModel.addToPlaylist(playlist.id, song); onDismiss() })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks for a playlist name; `submit` returns an error to show, or null when done. */
@Composable
fun NameDialog(title: String, action: String, initial: String, onDismiss: () -> Unit, submit: (String) -> String?) {
    // The current name starts selected, so typing replaces it.
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun confirm() { error = submit(name.text); if (error == null) onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it; error = null }, label = { Text("Name") }, singleLine = true,
                isError = error != null, supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
        },
        confirmButton = { TextButton(onClick = ::confirm) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun songCount(count: Int) = if (count == 1) "1 song" else "$count songs"

@Composable
fun EmptyState(icon: ImageVector, message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

