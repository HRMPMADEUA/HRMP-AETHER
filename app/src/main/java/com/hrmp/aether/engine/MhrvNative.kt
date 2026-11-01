package com.therealaleph.mhrv

/** JNI declarations implemented by the upstream MIT-licensed mhrv-rs Android library. */
object Native {
    init { System.loadLibrary("mhrv_rs") }
    external fun setDataDir(path: String)
    external fun startProxy(configJson: String): Long
    external fun stopProxy(handle: Long): Boolean
    external fun exportCa(destPath: String): Boolean
    external fun version(): String
    external fun drainLogs(): String
}
