package com.claudecodesetup

import android.util.Log

object NodeEngine {
    private const val TAG = "NodeEngine"

    @Volatile private var started = false

    init {
        System.loadLibrary("nodebridge")
    }

    @JvmStatic
    external fun nativeStart(args: Array<String>)

    fun startWithArguments(args: Array<String>) {
        synchronized(this) {
            if (started) {
                Log.w(TAG, "Node.js already started — ignoring duplicate call")
                return
            }
            started = true
        }
        Thread({
            Log.i(TAG, "Node.js engine starting; script=${args.getOrNull(1)}")
            nativeStart(args)
            Log.i(TAG, "Node.js engine exited")
        }, "node-main").start()
    }
}
