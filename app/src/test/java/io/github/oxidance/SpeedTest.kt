package io.github.oxidance

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.SonicAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of the desktop app's speed test: ExoPlayer applies PlaybackParameters(speed, pitch)
 * with Sonic. With pitch equal to speed, as the app sets it, a tone's frequency must scale
 * with speed (no pitch correction) and its duration must shrink accordingly.
 */
class SpeedTest {
    private fun render(frequency: Double, speed: Float, pitch: Float): FloatArray {
        val rate = 48000
        val sonic = SonicAudioProcessor()
        sonic.setSpeed(speed)
        sonic.setPitch(pitch)
        sonic.configure(AudioFormat(rate, 1, C.ENCODING_PCM_16BIT))
        sonic.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = ByteBuffer.allocateDirect(rate * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until rate) input.putShort((sin(2 * PI * frequency * i / rate) * 16000).toInt().toShort())
        input.flip()
        val output = mutableListOf<Float>()
        fun drain() { val out = sonic.output; while (out.hasRemaining()) output += out.short / 16000f }
        while (input.hasRemaining()) { sonic.queueInput(input); drain() }
        sonic.queueEndOfStream()
        while (!sonic.isEnded) drain()
        return output.toFloatArray()
    }

    private fun frequency(samples: FloatArray): Double {
        val middle = samples.copyOfRange(samples.size / 4, samples.size * 3 / 4)
        val crossings = (1 until middle.size).count { middle[it - 1] <= 0 && middle[it] > 0 }
        return crossings * 48000.0 / middle.size
    }

    @Test fun speedChangesPitchLikeTheDesktopApp() {
        // At 1× Sonic is inactive and ExoPlayer bypasses it.
        for (speed in listOf(0.5f, 0.75f, 1.25f, 1.5f, 2f)) {
            val samples = render(1000.0, speed, pitch = speed)
            val measured = frequency(samples)
            println("speed $speed: ${"%.1f".format(measured)} Hz, ${"%.3f".format(samples.size / 48000.0)} s")
            assertTrue("speed=$speed frequency=$measured", abs(measured - 1000 * speed) < 10)
            assertTrue("speed=$speed duration=${samples.size / 48000.0}", abs(samples.size / 48000.0 - 1 / speed) < 0.05)
        }
        // For contrast: ExoPlayer's default (pitch 1) would keep the tone at 1 kHz.
        val corrected = frequency(render(1000.0, 1.5f, 1f))
        assertTrue("pitch-corrected=$corrected", abs(corrected - 1000) < 10)
    }
}
