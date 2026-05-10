package dev.openwhoop.android

import android.util.Log

object OpenWhoopLog {
    fun d(tag: String, message: String) {
        runCatching { Log.d(tag, message) }
    }

    fun w(tag: String, message: String) {
        runCatching { Log.w(tag, message) }
    }

    fun e(tag: String, message: String) {
        runCatching { Log.e(tag, message) }
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        runCatching { Log.e(tag, message, throwable) }
    }
}
