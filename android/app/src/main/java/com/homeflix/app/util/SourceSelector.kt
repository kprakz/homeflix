package com.homeflix.app.util

import com.homeflix.app.data.InfoResponse

enum class PlaySource { DIRECT, REMUX, HLS }

/**
 * Mirrors playMode()/pickSource() in server.js and public/index.html:
 *  - direct: the original file plays as-is
 *  - remux:  the laptop copies it into an MP4 once (fast)
 *  - hls:    the laptop converts it to H.264 while it plays
 */
object SourceSelector {
    fun choose(info: InfoResponse, canPlayHevc: Boolean, hevcBlocked: Boolean): PlaySource {
        if (info.mode == "transcode") return PlaySource.HLS
        if (info.videoCodec.equals("hevc", ignoreCase = true) && (!canPlayHevc || hevcBlocked)) {
            return PlaySource.HLS
        }
        return if (info.mode == "direct") PlaySource.DIRECT else PlaySource.REMUX
    }
}
