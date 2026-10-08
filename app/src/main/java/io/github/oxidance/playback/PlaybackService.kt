package io.github.oxidance.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.oxidance.App
import io.github.oxidance.MainActivity
import io.github.oxidance.core.FileIndex
import io.github.oxidance.core.LibraryJson
import io.github.oxidance.core.Song
import io.github.oxidance.core.Streams
import io.github.oxidance.core.highQualityArtUrl
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Song metadata travels with each queue item, so the UI can like, download, and link it. */
private const val SONG_EXTRA = "song"

fun Song.toMediaItem(): MediaItem {
    val files = App.instance.downloads.files.value
    val art = files.art(videoId)?.let { Uri.fromFile(it) } ?: albumArtUrl?.let { Uri.parse(highQualityArtUrl(it)) }
    return MediaItem.Builder().setMediaId(videoId).setUri("oxidance://song/$videoId")
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist ?: "Unknown artist")
            .setArtworkUri(art).setIsPlayable(true).setIsBrowsable(false)
            .setExtras(Bundle().apply { putString(SONG_EXTRA, LibraryJson.encodeToString(Song.serializer(), this@toMediaItem)) })
            .build())
        .build()
}

val MediaItem.song: Song?
    get() = mediaMetadata.extras?.getString(SONG_EXTRA)?.let { runCatching { LibraryJson.decodeFromString(Song.serializer(), it) }.getOrNull() }

/**
 * Plays the queue in the background with a media notification, lock screen,
 * and headset controls. Speed changes are applied without pitch correction.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    /** Fresh stream URLs by video ID; dropped when a stream fails so retries resolve again. */
    private val resolved = ConcurrentHashMap<String, Pair<Long, String>>()
    private var attempts = 0

    override fun onCreate() {
        super.onCreate()
        val app = application as App
        val network = OkHttpDataSource.Factory(Streams.mediaClient)
        val upstream = DefaultDataSource.Factory(this, ChunkedDataSource.Factory(network))
        val resolving = ResolvingDataSource.Factory(upstream) { spec -> resolve(spec) }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val speed = app.settings.playbackSpeed
        player.playbackParameters = PlaybackParameters(speed, speed)
        player.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(parameters: PlaybackParameters) {
                // Keep pitch tied to speed, like a record played faster, even if a controller sets speed alone.
                Log.i("Oxidance", "Playback speed ${parameters.speed}, pitch ${parameters.pitch}")
                if (parameters.pitch != parameters.speed) player.playbackParameters = PlaybackParameters(parameters.speed, parameters.speed)
                else app.settings.playbackSpeed = parameters.speed
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                attempts = 0
                mediaItem?.song?.let { app.settings.lastSong = it }
                // A new queue: shuffle it with the chosen song first.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && player.shuffleModeEnabled) reshuffle(player)
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                app.settings.shuffle = shuffleModeEnabled
                if (shuffleModeEnabled) reshuffle(player)
            }

            override fun onPlayerError(error: PlaybackException) {
                val id = player.currentMediaItem?.mediaId
                val saved = id != null && app.downloads.files.value.file(id) != null
                // YouTube intermittently rejects stream URLs; retry with fresh ones, resuming where playback stopped.
                if (id != null && !saved && attempts < 2) {
                    attempts++
                    Log.w("Oxidance", "Stream attempt $attempts for $id failed, retrying", error)
                    resolved.remove(id)
                    player.prepare()
                    return
                }
                app.toast("Playback failed: ${error.cause?.message ?: error.message}. Press Play to retry.")
                if (id != null) resolved.remove(id)
            }
        })
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).setCallback(object : MediaSession.Callback {
            // Controllers send items without local configuration; rebuild them from their song metadata.
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(INSERT, Bundle.EMPTY)).build())
                    .build()
            }

            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                val song = args.getString(SONG)?.let { runCatching { LibraryJson.decodeFromString(Song.serializer(), it) }.getOrNull() }
                if (command.customAction == INSERT && song != null) insert(player, song, args.getBoolean(NEXT))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>) =
                Futures.immediateFuture(mediaItems.map { it.song?.toMediaItem() ?: it }.toMutableList())
        }).build()
    }

    /** Shuffles the queue so the current song comes first and every other song follows once. */
    private fun reshuffle(player: ExoPlayer) {
        val current = player.currentMediaItemIndex
        val rest = (0 until player.mediaItemCount).filter { it != current }.shuffled()
        player.setShuffleOrder(DefaultShuffleOrder((listOf(current) + rest).toIntArray(), Random.nextLong()))
    }

    /** Adds `song` after the current song or at the end, in both the queue and the shuffle order. */
    private fun insert(player: ExoPlayer, song: Song, next: Boolean) {
        val existing = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == song.videoId }
        if (existing != null && existing == player.currentMediaItemIndex) return
        // The playback order before the change, as queue indices.
        val timeline = player.currentTimeline
        val order = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(player.shuffleModeEnabled)
        while (index != C.INDEX_UNSET) { order += index; index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled) }
        if (existing != null) {
            player.removeMediaItem(existing)
            order.remove(existing)
            order.replaceAll { if (it > existing) it - 1 else it }
        }
        val current = player.currentMediaItemIndex
        val position = if (next) current + 1 else player.mediaItemCount
        player.addMediaItem(position, song.toMediaItem())
        if (!player.shuffleModeEnabled) return
        order.replaceAll { if (it >= position) it + 1 else it }
        val at = if (next) order.indexOf(current) + 1 else order.size
        order.add(at.coerceIn(0, order.size), position)
        player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
    }

    /** Plays the saved file when there is one, otherwise a freshly resolved YouTube stream. */
    private fun resolve(spec: DataSpec): DataSpec {
        if (spec.uri.scheme != "oxidance") return spec
        val id = spec.uri.lastPathSegment ?: throw IOException("Invalid song")
        FileIndex.scan(App.instance.musicDirectory).file(id)?.let { return spec.withUri(Uri.fromFile(it)) }
        val now = System.currentTimeMillis()
        val cached = resolved[id]?.takeIf { now - it.first < 60 * 60 * 1000 }
        val url = cached?.second ?: Streams.resolve(id).url.also { resolved[id] = now to it }
        return spec.withUri(Uri.parse(url))
    }

    companion object {
        const val INSERT = "io.github.oxidance.INSERT"
        const val SONG = "song"
        const val NEXT = "next"
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
