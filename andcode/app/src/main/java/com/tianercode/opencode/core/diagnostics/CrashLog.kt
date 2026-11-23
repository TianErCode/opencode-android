package com.tianercode.opencode.core.diagnostics

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale









object CrashLog {
    
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    
    fun read(context: Context): String? =
        runCatching {
            file(context).takeIf(File::exists)?.readText()?.takeIf(String::isNotBlank)
        }.getOrNull()

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun write(
        context: Context,
        thread: Thread,
        error: Throwable,
    ) {
        val stackTrace =
            StringWriter().also { writer ->
                PrintWriter(writer).use(error::printStackTrace)
            }.toString()
        val report =
            buildString {
                appendLine("OpenCode crash")
                appendLine("Time: ${TIMESTAMP.format(Date())}")
                appendLine("Thread: ${thread.name}")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine()
                append(stackTrace.take(MAX_TRACE_CHARS))
            }
        val target = file(context)
        target.parentFile?.mkdirs()
        target.writeText(report)
    }

    private fun file(context: Context): File = File(File(context.filesDir, "diagnostics"), "last-crash.txt")

    private val TIMESTAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private const val MAX_TRACE_CHARS = 12_000
}
