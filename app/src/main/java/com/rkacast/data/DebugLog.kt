package com.rkacast.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.rkacast.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory ring buffer of what the app is doing (also mirrored to logcat), plus a crash handler that
 * saves the stack trace so it can be copied on the next launch. Secrets are redacted because the
 * user pastes this log into chats/bug reports.
 */
object DebugLog {
    private const val MAX = 2000
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var crashFile: File? = null

    fun init(context: Context) {
        crashFile = File(context.filesDir, "crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                e("CRASH", "Uncaught exception on ${thread.name}", e)
                crashFile?.writeText("RKACast crashed\n\n" + Log.getStackTraceString(e) + "\n\n" + dump())
            }
            previous?.uncaughtException(thread, e)
        }
        i("App", "Started ${header()}")
    }

    fun header() = "RKACast ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) · " +
        "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"

    fun i(tag: String, msg: String) = add("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = add("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = add("E", tag, msg, t)

    private fun add(level: String, tag: String, msg: String, t: Throwable?) {
        val clean = redact(msg) + (t?.let { " | ${it.javaClass.simpleName}: ${redact(it.message ?: "")}" } ?: "")
        val line = "${time.format(Date())} $level/$tag: $clean"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
        // Logging must never throw (e.g. android.util.Log is unavailable in JVM unit tests).
        runCatching {
            when (level) {
                "E" -> Log.e("RKA/$tag", clean, t)
                "W" -> Log.w("RKA/$tag", clean, t)
                else -> Log.i("RKA/$tag", clean)
            }
        }
    }

    fun dump(): String = synchronized(lines) { header() + "\n\n" + lines.joinToString("\n") }

    fun clear() = synchronized(lines) { lines.clear() }

    fun pendingCrash(): String? = crashFile?.takeIf { it.exists() }?.readText()
    fun clearCrash() { crashFile?.delete() }

    private val secretPatterns = listOf(
        Regex("(\"?authKey\"?\\s*[:=]\\s*\"?)[^\"&,\\s}]+", RegexOption.IGNORE_CASE),
        Regex("(\"?password\"?\\s*[:=]\\s*\"?)[^\"&,\\s}]+", RegexOption.IGNORE_CASE),
        Regex("(\"?token\"?\\s*[:=]\\s*\"?)[^\"&,\\s}]+", RegexOption.IGNORE_CASE),
        Regex("(Cookie:\\s*)[^\\n]+", RegexOption.IGNORE_CASE),
        Regex("(apikey|api_key|key)=([^&\\s]+)", RegexOption.IGNORE_CASE),
    )

    fun redact(s: String): String {
        var out = s
        for (p in secretPatterns) out = p.replace(out) { m -> m.groupValues[1] + "***" }
        return out
    }

    /** Only scheme+host, for URLs whose paths may embed private config (e.g. addon/debrid keys). */
    fun hostOnly(url: String): String = runCatching {
        val u = java.net.URI(url)
        "${u.scheme}://${u.host}${if (u.port > 0) ":" + u.port else ""}/…"
    }.getOrDefault("<url>")

    /** Host plus the last path segment's extension, enough to debug format issues without leaking tokens. */
    fun mediaHint(url: String): String = runCatching {
        val u = java.net.URI(url)
        val ext = MediaUtils.extension(url).ifBlank { "?" }
        "${u.scheme}://${u.host}${if (u.port > 0) ":" + u.port else ""}/…(.$ext)"
    }.getOrDefault("<url>")
}
