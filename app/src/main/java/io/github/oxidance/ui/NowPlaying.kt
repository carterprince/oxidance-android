package io.github.oxidance.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import io.github.oxidance.core.FileIndex
import io.github.oxidance.core.Library
import io.github.oxidance.core.Song
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun formatTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}

fun formatSpeed(speed: Float): String {
    val text = "%.2f".format(speed).trimEnd('0').let { if (it.endsWith('.')) it + "0" else it }
    return "$text×"
}

/** The current playback position, updated a few times a second while visible. */
@Composable
fun rememberPosition(player: PlayerConnection): Long {
    var position by remember { mutableLongStateOf(player.positionMs) }
    LaunchedEffect(player.current, player.playing) {
        while (true) {
            position = player.positionMs
            delay(if (player.playing) 250 else 1000)
        }
    }
    return position
}

/** A muted color from the artwork, for the player's background. */
@Composable
fun rememberArtColor(model: Any?): Color? {
    val context = LocalContext.current
    var color by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(model) {
        color = null
        if (model == null) return@LaunchedEffect
        val result = context.imageLoader.execute(ImageRequest.Builder(context).data(model).allowHardware(false).size(96).build())
        val bitmap = (result as? SuccessResult)?.image?.asDrawable(context.resources)?.toBitmap() ?: return@LaunchedEffect
        val palette = Palette.from(bitmap).generate()
        val argb = palette.mutedSwatch?.rgb ?: palette.dominantSwatch?.rgb ?: return@LaunchedEffect
        color = Color(argb)
    }
    return color
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlaying(
    player: PlayerConnection,
    song: Song,
    library: Library,
    files: FileIndex,
    actions: SongActions,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val art = songArt(song, files, large = true)
    val artColor = rememberArtColor(art)
    val surface = MaterialTheme.colorScheme.surface
    val top = artColor?.let { lerp(surface, it, 0.55f) } ?: MaterialTheme.colorScheme.surfaceContainerHigh
    var showSpeed by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val liked = library.isLiked(song)

    Surface(modifier.fillMaxSize(), color = surface) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, surface)))) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                // Header: collapse, queue source, and song options.
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCollapse) { Icon(Icons.Default.KeyboardArrowDown, "Close player", Modifier.size(32.dp)) }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("PLAYING FROM", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(player.source.ifEmpty { "Queue" }, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SongMenu(song, actions, includeQueue = false)
                }
                Spacer(Modifier.weight(1f))
                AlbumArt(art, player, Modifier.widthIn(max = 480.dp).fillMaxWidth())
                Spacer(Modifier.weight(1f))
                // Title, artist, and like.
                Row(Modifier.fillMaxWidth().widthIn(max = 480.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(song.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            maxLines = 1, modifier = Modifier.basicMarquee())
                        Text(song.artist ?: "Unknown artist", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = song.artists.isNotEmpty()) { actions.openArtist(song.artists.first()) })
                    }
                    IconButton(onClick = { actions.toggleLike(song) }) {
                        Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (liked) "Unlike" else "Like",
                            Modifier.size(28.dp), tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.height(12.dp))
                SeekBar(player)
                Spacer(Modifier.height(8.dp))
                // Transport controls.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { player.changeShuffle(!player.shuffle) }) {
                        Icon(Icons.Default.Shuffle, if (player.shuffle) "Turn shuffle off" else "Turn shuffle on",
                            tint = if (player.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { player.previous() }, Modifier.size(56.dp)) {
                        Icon(Icons.Default.SkipPrevious, "Previous", Modifier.size(40.dp))
                    }
                    PlayButton(player, Modifier.size(76.dp), iconSize = 44)
                    IconButton(onClick = { player.next() }, enabled = player.hasNext, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Default.SkipNext, "Next", Modifier.size(40.dp))
                    }
                    TextButton(onClick = { showSpeed = true }, Modifier.semantics { contentDescription = "Playback speed ${formatSpeed(player.speed)}" }) {
                        Text(formatSpeed(player.speed), fontWeight = FontWeight.SemiBold,
                            color = if (player.speed != 1f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(6.dp))
                        Text(if (files.file(song.videoId) != null) "Playing from your device" else "Streaming",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { showQueue = true }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue") }
                }
            }
        }
    }
    if (showSpeed) SpeedSheet(player) { showSpeed = false }
    if (showQueue) QueueSheet(player, files) { showQueue = false }
}

/** The enlarged album art; swiping it sideways skips to the next or previous song. */
@Composable
private fun AlbumArt(model: Any?, player: PlayerConnection, modifier: Modifier) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val width = constraints.maxWidth.toFloat()
        Box(Modifier.fillMaxSize()
            .offset { IntOffset(offset.value.roundToInt(), 0) }
            .graphicsLayer { alpha = 1f - (abs(offset.value) / width).coerceIn(0f, 0.6f) }
            .pointerInput(player) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            val distance = offset.value
                            when {
                                distance < -width / 4 && player.hasNext -> { offset.animateTo(-width, tween(150)); player.next(); offset.snapTo(width); offset.animateTo(0f) }
                                distance > width / 4 -> { offset.animateTo(width, tween(150)); player.previous(); offset.snapTo(-width); offset.animateTo(0f) }
                                else -> offset.animateTo(0f)
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                ) { change, amount -> change.consume(); scope.launch { offset.snapTo(offset.value + amount) } }
            }
            .shadow(16.dp, RoundedCornerShape(12.dp))) {
            Artwork(null, null, Modifier.fillMaxSize(), RoundedCornerShape(12.dp), model = model)
        }
    }
}

@Composable
fun PlayButton(player: PlayerConnection, modifier: Modifier, iconSize: Int) {
    FilledIconButton(onClick = { player.togglePlayback() }, modifier = modifier, shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface,
            contentColor = MaterialTheme.colorScheme.surface)) {
        if (player.buffering && player.playWhenReady) {
            CircularProgressIndicator(Modifier.size((iconSize * 0.7).dp), color = MaterialTheme.colorScheme.surface, strokeWidth = 3.dp)
        } else {
            Icon(if (player.playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (player.playWhenReady) "Pause" else "Play", Modifier.size(iconSize.dp))
        }
    }
}

@Composable
private fun SeekBar(player: PlayerConnection) {
    val position = rememberPosition(player)
    val duration = player.durationMs
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Slider(value = fraction, onValueChange = { dragging = it }, enabled = duration > 0,
            onValueChangeFinished = { dragging?.let { player.seekTo((it * duration).toLong()) }; dragging = null },
            modifier = Modifier.semantics { contentDescription = "Seek" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = (fraction * duration).toLong()
            Text(formatTime(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (duration > 0) "-" + formatTime(duration - shown) else "--:--", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SpeedSheet(player: PlayerConnection, onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(player.speed) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Playback speed", style = MaterialTheme.typography.titleLarge)
            Text(formatSpeed(value), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp))
            Slider(value = value, onValueChange = { value = (Math.round(it * 20) / 20f) }, valueRange = 0.5f..2f, steps = 29,
                onValueChangeFinished = { player.changeSpeed(value) }, modifier = Modifier.semantics { contentDescription = "Speed" })
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                for (preset in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)) {
                    FilterChip(selected = value == preset, onClick = { value = preset; player.changeSpeed(preset) },
                        label = { Text(formatSpeed(preset).removeSuffix("×"), maxLines = 1) },
                        modifier = Modifier.semantics { contentDescription = formatSpeed(preset) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Pitch changes with speed, like a record played faster or slower.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(player: PlayerConnection, files: FileIndex, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Up next", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        val state = rememberLazyListState()
        LazyColumn(state = state, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(player.upNext, key = { _, (index, song) -> "$index:${song.videoId}" }) { position, (index, song) ->
                val current = position == 0
                ListItem(
                    modifier = Modifier.clickable { player.playQueueItem(index); onDismiss() },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Artwork(null, 44.dp, model = songArt(song, files)) },
                    headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (current) FontWeight.SemiBold else null) },
                    supportingContent = { Text(if (current) "Now playing" else song.artist ?: "Unknown artist", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

/** The bar above the tabs: tap or swipe up to open the full player. */
@Composable
fun MiniPlayer(player: PlayerConnection, song: Song, files: FileIndex, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    val position = rememberPosition(player)
    val progress = if (player.durationMs > 0) (position.toFloat() / player.durationMs).coerceIn(0f, 1f) else 0f
    Surface(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onExpand)
        .semantics { contentDescription = "Open player" },
        color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp)) {
        Column {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(null, 44.dp, model = songArt(song, files))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist ?: "Unknown artist", style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { player.togglePlayback() }) {
                    if (player.buffering && player.playWhenReady) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(if (player.playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow, if (player.playWhenReady) "Pause" else "Play")
                }
                IconButton(onClick = { player.next() }, enabled = player.hasNext) { Icon(Icons.Default.SkipNext, "Next") }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(2.dp).background(MaterialTheme.colorScheme.outlineVariant)) {
                Box(Modifier.fillMaxWidth(progress).height(2.dp).background(MaterialTheme.colorScheme.onSurface))
            }
        }
    }
}
