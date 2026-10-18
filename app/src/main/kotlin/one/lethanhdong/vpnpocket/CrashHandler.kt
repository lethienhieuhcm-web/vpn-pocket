package one.lethanhdong.vpnpocket

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bắt crash (Java + signal từ Go panic) và ghi stack trace ra file.
 * File: <files>/crash-<timestamp>.txt — xem ở tab Cài đặt.
 */
object CrashHandler {
    private const val TAG = "CrashHandler"

    fun init(ctx: Context) {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(ctx.filesDir, "crash-$ts.txt")
                file.writeText(buildString {
                    append("Time: $ts\n")
                    append("Thread: ${t.name}\n")
                    append("Exception: ${e::class.java.name}: ${e.message}\n")
                    append("Stack:\n")
                    append(e.stackTraceToString())
                    e.cause?.let { append("\nCaused by: ${it.stackTraceToString()}") }
                })
                android.util.Log.e(TAG, "crash saved to ${file.absolutePath}", e)
            } catch (_: Exception) { }
            default?.uncaughtException(t, e)
        }
    }

    fun latestCrash(ctx: Context): File? =
        ctx.filesDir.listFiles { f -> f.name.startsWith("crash-") }
            ?.maxByOrNull { it.lastModified() }

    fun readCrash(ctx: Context): String? =
        latestCrash(ctx)?.readText()
}
