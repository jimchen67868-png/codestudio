package com.example.aideclone

import android.app.Application
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registers a global uncaught-exception handler that writes the full
 * stack trace to a file BEFORE delegating to the system's default
 * handler (so normal crash behavior — the app still visibly stops —
 * is unchanged). The file goes under getExternalFilesDir(null), which
 * needs no special runtime permission and (unlike internal filesDir)
 * is generally reachable from Termux via its `~/storage/shared` symlink
 * (from `termux-setup-storage`), at a path like:
 *   /storage/emulated/0/Android/data/com.example.aideclone/files/crash_log.txt
 *
 * This exists specifically because a minification-related crash on app
 * launch previously had zero diagnostic visibility — no logcat access
 * without root, no in-app dialog possible since the crash happens before
 * any of our own try/catch code runs. This is the fix for that blind spot.
 */
class AIDECloneApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val logFile = File(getExternalFilesDir(null), "crash_log.txt")
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val entry = buildString {
                    appendLine("=== Crash at $timestamp on thread ${thread.name} ===")
                    appendLine(throwable.stackTraceToString())
                    appendLine()
                }
                logFile.appendText(entry)
            } catch (_: Throwable) {
                // If even crash logging fails, don't let that mask the
                // original crash — just fall through to the default
                // handler below regardless.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
