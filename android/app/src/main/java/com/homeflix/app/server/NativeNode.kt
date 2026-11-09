package com.homeflix.app.server

object NativeNode {
    init {
        System.loadLibrary("node")
        System.loadLibrary("native-lib")
    }

    external fun startNode(
        arguments: Array<String>,
        workDir: String,
        ffmpegPath: String,
        ffprobePath: String,
    ): Int
}
