package io.github.oxidance.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Reads googlevideo streams as a series of bounded ranges. YouTube throttles
 * open-ended requests to about twice real time, which stutters at high speeds,
 * while bounded ranges arrive at full speed.
 */
@UnstableApi
class ChunkedDataSource(private val upstream: DataSource, private val chunkSize: Long = 2L * 1024 * 1024) : DataSource {
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource() = ChunkedDataSource(upstream.createDataSource())
    }

    private var spec: DataSpec? = null
    private var chunked = false
    private var position = 0L
    /** Bytes left in the whole requested range, or C.LENGTH_UNSET while unknown. */
    private var remaining = C.LENGTH_UNSET.toLong()
    private var chunkRemaining = 0L
    private var opened = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        chunked = dataSpec.uri.host?.endsWith(".googlevideo.com") == true
        if (!chunked) { opened = true; return upstream.open(dataSpec) }
        position = dataSpec.position
        val total = openChunk(dataSpec.length)
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else if (total != C.LENGTH_UNSET.toLong()) total - position else C.LENGTH_UNSET.toLong()
        return remaining
    }

    /** Opens the next range and returns the resource's total length when the server reports it. */
    private fun openChunk(requested: Long): Long {
        val length = if (requested == C.LENGTH_UNSET.toLong()) chunkSize else minOf(chunkSize, requested)
        chunkRemaining = upstream.open(spec!!.subrange(position - spec!!.position, length))
        opened = true
        val range = upstream.responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value?.firstOrNull()
        return range?.substringAfter('/')?.toLongOrNull() ?: C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunked) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (chunkRemaining == 0L) {
            upstream.close()
            opened = false
            openChunk(remaining)
            if (chunkRemaining == 0L) return C.RESULT_END_OF_INPUT
        }
        val read = upstream.read(buffer, offset, if (chunkRemaining > 0) minOf(length.toLong(), chunkRemaining).toInt() else length)
        if (read == C.RESULT_END_OF_INPUT) {
            // The range ended early: the resource is shorter than expected.
            if (remaining == C.LENGTH_UNSET.toLong() || chunkRemaining == C.LENGTH_UNSET.toLong()) { remaining = 0; return read }
            chunkRemaining = 0
            return read(buffer, offset, length)
        }
        position += read
        if (chunkRemaining > 0) chunkRemaining -= read
        if (remaining > 0) remaining -= read
        return read
    }

    override fun getUri(): Uri? = spec?.uri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (opened) upstream.close()
        opened = false
        spec = null
    }
}
