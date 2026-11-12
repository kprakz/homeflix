package io.github.kprakz.homeflix

/** Runs Node.js (nodejs-mobile) in this process. Returns when Node exits; once per process. */
object NodeRunner {
    init {
        System.loadLibrary("node")
        System.loadLibrary("homeflix_native")
    }

    @JvmStatic
    external fun startNode(args: Array<String>): Int
}
