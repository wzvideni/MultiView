package com.wzvideni.multiview.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class NoOpAudioRendererTest {
    private val formats = arrayOf(Format.Builder().setSampleMimeType("audio/opus").build())
    private val periodId = MediaSource.MediaPeriodId(Any())

    @Test(timeout = 1000)
    fun finalEndOfStreamReturnsAndAllowsRendererShutdown() {
        val stream = EndingStream(3)
        val renderer = startedRenderer(stream)
        renderer.setCurrentStreamFinal()

        renderer.render(0, 0)
        assertTrue(renderer.isEnded)
        assertEquals(4, stream.readCount)
        renderer.render(0, 0)
        assertEquals(4, stream.readCount)
        renderer.stop()
        renderer.disable()
        renderer.reset()
    }

    @Test(timeout = 1000)
    fun nonFinalEndOfStreamWaitsForFinalFlag() {
        val stream = EndingStream(0)
        val renderer = startedRenderer(stream)

        renderer.render(0, 0)
        assertFalse(renderer.isEnded)
        assertEquals(1, stream.readCount)
        renderer.setCurrentStreamFinal()
        renderer.render(0, 0)
        assertTrue(renderer.isEnded)
        assertEquals(2, stream.readCount)
    }

    @Test(timeout = 1000)
    fun resetPositionClearsEndState() {
        val renderer = startedRenderer(EndingStream(0))
        renderer.setCurrentStreamFinal()
        renderer.render(0, 0)
        assertTrue(renderer.isEnded)

        renderer.resetPosition(0)
        assertFalse(renderer.isEnded)
        renderer.setCurrentStreamFinal()
        renderer.render(0, 0)
        assertTrue(renderer.isEnded)
    }

    @Test(timeout = 1000)
    fun rendererCanReadNewStreamAfterBeingDisabledAndEnabled() {
        val renderer = startedRenderer(EndingStream(0))
        renderer.setCurrentStreamFinal()
        renderer.render(0, 0)
        val replacement = EndingStream(2)

        renderer.stop()
        renderer.disable()
        renderer.enable(RendererConfiguration.DEFAULT, formats, replacement, 0, false, true, 0, 0, periodId)
        renderer.start()
        assertFalse(renderer.isEnded)
        renderer.setCurrentStreamFinal()
        renderer.render(0, 0)
        assertTrue(renderer.isEnded)
        assertEquals(3, replacement.readCount)
    }

    private fun startedRenderer(stream: SampleStream): NoOpAudioRenderer {
        return NoOpAudioRenderer().apply {
            enable(RendererConfiguration.DEFAULT, formats, stream, 0, false, true, 0, 0, periodId)
            start()
        }
    }

    private class EndingStream(private val samples: Int) : SampleStream {
        var readCount = 0
            private set

        override fun isReady() = true
        override fun maybeThrowError() = Unit
        override fun skipData(positionUs: Long) = 0

        override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
            check(readCount < samples + 2) { "Renderer repeatedly read EOS instead of returning" }
            if (readCount >= samples) buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
            readCount++
            return C.RESULT_BUFFER_READ
        }
    }
}
