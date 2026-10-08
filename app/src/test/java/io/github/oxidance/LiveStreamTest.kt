package io.github.oxidance

import io.github.oxidance.core.Streams
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamTest {
    @Test
    fun chunkedDownloadSpeed() {
        val source = Streams.resolve("qYcoJpqCha4")
        for (chunk in listOf(512 * 1024, 2 * 1024 * 1024, 10 * 1024 * 1024)) {
            val start = System.currentTimeMillis()
            var position = 0L
            var total = Long.MAX_VALUE
            while (position < total) {
                val end = minOf(position + chunk, total) - 1
                Streams.mediaClient.newCall(Request.Builder().url(source.url).header("Range", "bytes=$position-$end").build()).execute().use {
                    assertTrue("HTTP ${it.code}", it.code == 206)
                    total = it.header("Content-Range")!!.substringAfter('/').toLong()
                    position += it.body.bytes().size
                }
            }
            println("chunk $chunk: $total bytes in ${(System.currentTimeMillis() - start) / 1000.0} s")
        }
    }
}
