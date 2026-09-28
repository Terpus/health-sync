package com.healthsync

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.time.ZonedDateTime

object SyncDiagnostics {
    private const val TAG = "HealthSync"
    private const val FILE_NAME = "health_sync_diagnostics.txt"
    @Volatile private var crashHandlerInstalled = false

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun start(context: Context, mode: String) {
        runCatching {
            file(context).writeText(
                buildString {
                    appendLine("Health Sync diagnostics")
                    appendLine("started=${ZonedDateTime.now()}")
                    appendLine("mode=$mode")
                    appendLine("sdk=${Build.VERSION.SDK_INT}")
                    appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                }
            )
        }
        log(context, "diagnostics started: $mode")
    }
    fun log(context: Context, message: String) {
        Log.i(TAG, message)
        runCatching {
            file(context).appendText(
                "${ZonedDateTime.now()} INFO $message\n"
            )
        }
    }

    fun error(context: Context, stage: String, throwable: Throwable) {
        Log.e(TAG, "$stage: ${throwable.javaClass.name}: ${throwable.message}", throwable)
        runCatching {
            file(context).appendText(
                buildString {
                    appendLine("${ZonedDateTime.now()} ERROR stage=$stage")
                    appendLine("class=${throwable.javaClass.name}")
                    appendLine("message=${throwable.message}")
                    appendLine(throwable.stackTraceToString())
                }
            )
        }
    }

    fun memory(context: Context, stage: String) {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        log(
            context,
            "memory[$stage] used=${used / MIB}MiB " +
                "allocated=${runtime.totalMemory() / MIB}MiB max=${runtime.maxMemory() / MIB}MiB"
        )
    }

    @Synchronized
    fun installCrashHandler(context: Context) {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                error(appContext, "uncaught:${thread.name}", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private const val MIB = 1024L * 1024L
}
