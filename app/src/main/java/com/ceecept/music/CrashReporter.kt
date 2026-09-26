package com.ceecept.music

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal offline crash reporter. Installed first thing in [CeeceptApp.onCreate]:
 * any uncaught exception is written to internal storage before the process dies,
 * and the next launch offers the report with a Copy button. Recoverable startup
 * failures (playback engine, fonts, prefs) are recorded the same way via
 * [recordSoft] so they can be diagnosed too.
 */
object CrashReporter {
    private const val FILE = "crash-last.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                save(app, fatal = true, tag = "uncaught", threadName = thread.name, error = error)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun recordSoft(context: Context, tag: String, error: Throwable) {
        runCatching {
            save(
                context,
                fatal = false,
                tag = tag,
                threadName = Thread.currentThread().name,
                error = error
            )
        }
    }

    fun load(context: Context): String? = runCatching {
        context.filesDir.resolve(FILE).takeIf { it.exists() }?.readText()
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { context.filesDir.resolve(FILE).delete() }
    }

    private fun save(
        context: Context,
        fatal: Boolean,
        tag: String,
        threadName: String,
        error: Throwable
    ) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val logcat = runCatching {
            Runtime.getRuntime()
                .exec(arrayOf("logcat", "-d", "-t", "120"))
                .inputStream.bufferedReader().readText()
        }.getOrNull()?.takeLast(4000) ?: "logcat unavailable"
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val report = buildString {
            appendLine("Ceecept ${if (fatal) "FATAL" else "RECOVERED"} — $tag")
            appendLine("time=$time")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
            appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("thread=$threadName")
            appendLine()
            appendLine(stack.take(8000))
            appendLine("--- logcat (tail) ---")
            appendLine(logcat)
        }.take(16000)
        context.filesDir.resolve(FILE).writeText(report)
    }
}
