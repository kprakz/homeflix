package com.homeflix.app.util

import com.homeflix.app.data.InfoResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceSelectorTest {
    @Test
    fun `h264 direct file plays as-is`() {
        val info = InfoResponse(id = "1", mode = "direct", videoCodec = "h264")
        assertEquals(PlaySource.DIRECT, SourceSelector.choose(info, canPlayHevc = false, hevcBlocked = false))
    }

    @Test
    fun `remux uses the cached copy`() {
        val info = InfoResponse(id = "1", mode = "remux", videoCodec = "h264")
        assertEquals(PlaySource.REMUX, SourceSelector.choose(info, canPlayHevc = true, hevcBlocked = false))
    }

    @Test
    fun `transcode always uses hls`() {
        val info = InfoResponse(id = "1", mode = "transcode", videoCodec = "h264")
        assertEquals(PlaySource.HLS, SourceSelector.choose(info, canPlayHevc = true, hevcBlocked = false))
    }

    @Test
    fun `hevc without a decoder uses hls`() {
        val info = InfoResponse(id = "1", mode = "direct", videoCodec = "hevc")
        assertEquals(PlaySource.HLS, SourceSelector.choose(info, canPlayHevc = false, hevcBlocked = false))
    }

    @Test
    fun `blocked hevc uses hls even when the device could play it`() {
        val info = InfoResponse(id = "1", mode = "direct", videoCodec = "HEVC")
        assertEquals(PlaySource.HLS, SourceSelector.choose(info, canPlayHevc = true, hevcBlocked = true))
    }
}
