package io.github.oxidance.core

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NewPipeRequest
import org.schabi.newpipe.extractor.downloader.Response as NewPipeResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

/** A playable audio stream for a song, resolved fresh from YouTube. */
data class AudioSource(val url: String, val extension: String, val mimeType: String, val bitrate: Int, val durationSeconds: Long)

/**
 * Resolves YouTube audio with NewPipeExtractor, which solves YouTube's JavaScript
 * challenges itself, so no yt-dlp or JavaScript runtime is needed on the phone.
 */
object Streams {
    /** NewPipe's desktop browser user agent; googlevideo checks it against the client that requested the URL. */
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    /**
     * Makes googlevideo requests match NewPipe's player: a POST with a fixed body,
     * a desktop user agent, and browser headers for web-client URLs.
     */
    val googleVideoInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (!request.url.host.endsWith(".googlevideo.com") || !request.url.encodedPath.startsWith("/videoplayback")) {
            return@Interceptor chain.proceed(request)
        }
        val builder = request.newBuilder().header("User-Agent", USER_AGENT)
            .post(byteArrayOf(0x78, 0).toRequestBody())
        if (request.url.queryParameter("c")?.startsWith("WEB") == true) {
            builder.header("Origin", "https://www.youtube.com").header("Referer", "https://www.youtube.com/")
                .header("Sec-Fetch-Dest", "empty").header("Sec-Fetch-Mode", "cors").header("Sec-Fetch-Site", "cross-site")
        }
        chain.proceed(builder.build())
    }

    val mediaClient: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).addInterceptor(googleVideoInterceptor).build()

    private val extractorClient = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    private object OkHttpDownloader : Downloader() {
        override fun execute(request: NewPipeRequest): NewPipeResponse {
            val body = request.dataToSend()?.toRequestBody()
            val builder = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body)
                .header("User-Agent", USER_AGENT)
            for ((name, values) in request.headers()) {
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            extractorClient.newCall(builder.build()).execute().use { response ->
                if (response.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                return NewPipeResponse(response.code, response.message, response.headers.toMultimap(),
                    response.body.string(), response.request.url.toString())
            }
        }
    }

    @Volatile private var initialized = false

    fun init() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(OkHttpDownloader, Localization("en", "US"))
                initialized = true
            }
        }
    }

    /** Prefers the original-language track, then higher bitrate, then Opus over AAC at equal bitrate. */
    internal fun best(streams: List<AudioStream>): AudioStream? = streams
        .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl && it.format != null }
        .sortedWith(compareByDescending<AudioStream> { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .thenByDescending { it.averageBitrate }
            .thenByDescending { it.format?.suffix == "webm" })
        .firstOrNull()

    fun resolve(videoId: String): AudioSource {
        init()
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        } catch (error: Exception) {
            throw IOException(error.message ?: "YouTube could not resolve this song.", error)
        }
        val stream = best(info.audioStreams) ?: throw IOException("YouTube returned no playable audio for this song.")
        val format = stream.format!!
        // NewPipe calls WebM audio "webma"; save it as .webm like yt-dlp does on desktop.
        val extension = if (format.suffix == "webma") "webm" else format.suffix
        return AudioSource(stream.content, extension, format.mimeType, stream.averageBitrate, info.duration)
    }
}
