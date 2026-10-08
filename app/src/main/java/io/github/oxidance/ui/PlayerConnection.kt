package io.github.oxidance.ui

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import io.github.oxidance.core.LibraryJson
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import io.github.oxidance.App
import io.github.oxidance.core.Song
import io.github.oxidance.playback.PlaybackService
import io.github.oxidance.playback.song
import io.github.oxidance.playback.toMediaItem

/** The playback service's state as Compose state, and the commands the UI sends it. */
class PlayerConnection(context: Context) {
    private var controller: MediaController? = null

    var current by mutableStateOf<Song?>(null); private set
    var playing by mutableStateOf(false); private set
    /** True while the player wants to play: playing, or loading to play. */
    var playWhenReady by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var durationMs by mutableStateOf(0L); private set
    var speed by mutableFloatStateOf(1f); private set
    var shuffle by mutableStateOf(false); private set
    var queue by mutableStateOf<List<Song>>(emptyList()); private set
    /** The queue's songs in playback order, starting with the current one. */
    var upNext by mutableStateOf<List<Pair<Int, Song>>>(emptyList()); private set
    var currentIndex by mutableIntStateOf(0); private set
    var hasPrevious by mutableStateOf(false); private set
    var hasNext by mutableStateOf(false); private set
    /** Where the queue came from, e.g. "Liked songs"; kept by the service as playlist metadata. */
    var source by mutableStateOf(""); private set

    /** Runs before playback starts, e.g. to ask for notification permission. */
    var beforeStart: () -> Unit = {}

    val positionMs: Long get() = controller?.currentPosition ?: 0

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = connected
            android.util.Log.i("Oxidance", "Connected to playback with commands ${connected.availableCommands.size()}")
            connected.addListener(listener)
            if (connected.mediaItemCount == 0) restoreLastSong()
            refresh()
        }, MoreExecutors.directExecutor())
    }

    private fun refresh() {
        val player = controller ?: return
        current = player.currentMediaItem?.song
        source = player.playlistMetadata.title?.toString() ?: ""
        playing = player.isPlaying
        // After an error the player is idle but still "wants" to play; show Play so the user can retry.
        playWhenReady = player.playWhenReady && player.playbackState != Player.STATE_IDLE
        buffering = player.playbackState == Player.STATE_BUFFERING
        durationMs = player.duration.takeIf { it > 0 } ?: 0
        speed = player.playbackParameters.speed
        shuffle = player.shuffleModeEnabled
        queue = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).song }
        currentIndex = player.currentMediaItemIndex
        hasPrevious = player.hasPreviousMediaItem()
        hasNext = player.hasNextMediaItem()
        val order = mutableListOf<Pair<Int, Song>>()
        val timeline = player.currentTimeline
        if (!timeline.isEmpty && queue.size == timeline.windowCount) {
            var index = player.currentMediaItemIndex
            while (index != C_INDEX_UNSET && order.size < queue.size) {
                order += index to queue[index]
                index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            }
        }
        upNext = order
    }

    /** Restores the last song paused, with saved songs as its queue, like the desktop app. */
    private fun restoreLastSong() {
        val app = App.instance
        val song = app.settings.lastSong ?: return
        val songs = app.library.value.savedSongs().let { if (it.any { s -> s.videoId == song.videoId }) it else it + song }
        start(songs, songs.indexOfFirst { it.videoId == song.videoId }, "Saved songs", autoplay = false, shuffle = app.settings.shuffle)
    }

    /**
     * Plays `songs` from `index`. When shuffling, the chosen song plays first and
     * the rest follow in random order.
     */
    fun start(songs: List<Song>, index: Int, source: String, autoplay: Boolean = true, shuffle: Boolean = this.shuffle) {
        val player = controller ?: return
        if (songs.isEmpty()) return
        val start = index.coerceIn(songs.indices)
        // Tapping the current song again toggles playback instead of restarting it.
        if (autoplay && current?.videoId == songs[start].videoId && queue.map { it.videoId } == songs.map { it.videoId }) {
            togglePlayback()
            return
        }
        if (autoplay) beforeStart()
        player.setMediaItems(songs.map { it.toMediaItem() }, start, 0)
        player.playlistMetadata = MediaMetadata.Builder().setTitle(source).build()
        player.shuffleModeEnabled = shuffle
        player.prepare()
        player.playWhenReady = autoplay
    }

    fun shufflePlay(songs: List<Song>, source: String) {
        if (songs.isEmpty()) return
        start(songs, songs.indices.random(), source, shuffle = true)
    }

    fun togglePlayback() {
        val player = controller ?: return
        // Retrying after an error resumes where playback stopped.
        if (player.playbackState == Player.STATE_IDLE) { player.prepare(); player.playWhenReady = true; return }
        if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(player.currentMediaItemIndex)
        player.playWhenReady = !player.playWhenReady
    }

    /** Previous restarts the song after its first few seconds, as music players do. */
    fun previous() = controller?.seekToPrevious()
    fun next() = controller?.seekToNext()
    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)
    fun playQueueItem(index: Int) = controller?.run { seekToDefaultPosition(index); playWhenReady = true }

    fun changeShuffle(enabled: Boolean) {
        controller?.shuffleModeEnabled = enabled
    }

    /** Sets the playback speed; pitch follows speed, so there is no pitch correction. */
    fun changeSpeed(value: Float) {
        val rounded = (Math.round(value * 20) / 20f).coerceIn(0.5f, 2f)
        controller?.playbackParameters = PlaybackParameters(rounded, rounded)
    }

    fun playNext(song: Song) = insert(song, next = true)
    fun addToQueue(song: Song) = insert(song, next = false)

    private fun insert(song: Song, next: Boolean) {
        val player = controller ?: return
        if (player.mediaItemCount == 0) { start(listOf(song), 0, "Queue"); return }
        if (player.currentMediaItem?.mediaId == song.videoId) return
        // The service places the song in both the queue and the shuffle order.
        player.sendCustomCommand(SessionCommand(PlaybackService.INSERT, Bundle.EMPTY), Bundle().apply {
            putString(PlaybackService.SONG, LibraryJson.encodeToString(Song.serializer(), song))
            putBoolean(PlaybackService.NEXT, next)
        })
        App.instance.toast(if (next) "${song.title} plays next." else "Added ${song.title} to the queue.")
    }

    fun release() {
        controller?.release()
        controller = null
    }

    private companion object {
        const val C_INDEX_UNSET = -1
    }
}
