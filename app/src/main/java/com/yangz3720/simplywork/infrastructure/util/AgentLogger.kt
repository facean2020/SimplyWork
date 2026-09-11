package com.yangz3720.simplywork.infrastructure.util

import android.util.Log
import com.yangz3720.simplywork.BuildConfig

/**
 * Development-time logger for Agent infrastructure.
 * Enabled only in DEBUG builds by default, with zero overhead in RELEASE builds.
 */
object AgentLogger {

    var isEnabled: Boolean = BuildConfig.DEBUG

    /** Optional custom printer (useful for unit tests or custom sinks) */
    var customLogPrinter: ((priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit)? = null

    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    inline fun v(tag: String, message: () -> String) {
        if (isEnabled) log(VERBOSE, tag, message(), null)
    }

    inline fun d(tag: String, message: () -> String) {
        if (isEnabled) log(DEBUG, tag, message(), null)
    }

    inline fun i(tag: String, message: () -> String) {
        if (isEnabled) log(INFO, tag, message(), null)
    }

    inline fun w(tag: String, throwable: Throwable? = null, message: () -> String) {
        if (isEnabled) log(WARN, tag, message(), throwable)
    }

    inline fun e(tag: String, throwable: Throwable? = null, message: () -> String) {
        if (isEnabled) log(ERROR, tag, message(), throwable)
    }

    @PublishedApi
    internal fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
        val printer = customLogPrinter
        if (printer != null) {
            printer(priority, tag, message, throwable)
            return
        }

        try {
            when (priority) {
                VERBOSE -> Log.v(tag, message, throwable)
                DEBUG -> Log.d(tag, message, throwable)
                INFO -> Log.i(tag, message, throwable)
                WARN -> Log.w(tag, message, throwable)
                ERROR -> Log.e(tag, message, throwable)
            }
        } catch (_: RuntimeException) {
            // Fallback for JVM unit test environments where android.util.Log is not mocked
            val errSuffix = throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
            println("[$tag] $message$errSuffix")
        }
    }
}
