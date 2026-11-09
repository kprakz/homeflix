package com.homeflix.app.player

import android.media.MediaCodecList

object CodecSupport {
    /** Hardware HEVC support varies wildly on Android; ask the platform instead of guessing. */
    fun canPlayHevc(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            !codec.isEncoder && codec.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
        }
    }.getOrDefault(false)
}
