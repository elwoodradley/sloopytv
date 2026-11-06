package tv.sloopy.data

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.system.ErrnoException
import android.system.OsConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tv.sloopy.TLog

/**
 * The box's storage: how much is free, and the one failure that must never close the app, a
 * full disk. A Chromecast or a Fire Stick has 8 GB in all with half of it free, so a write that
 * fails for lack of space is a state to show, not a crash (the buddy's box, 2026-11-05).
 */
object Storage {
    private val _full = MutableStateFlow<String?>(null)
    /** Set when a write failed for lack of space; cleared once a later check finds room again. */
    val full: StateFlow<String?> = _full

    fun freeBytes(path: String): Long = runCatching { StatFs(path).availableBytes }.getOrDefault(-1L)
    fun totalBytes(path: String): Long = runCatching { StatFs(path).totalBytes }.getOrDefault(-1L)
    fun mb(b: Long): Long = b / 1_000_000

    /** True for ENOSPC in any form: the errno, SQLite's "database or disk is full", or the message. */
    fun isNoSpace(t: Throwable?): Boolean {
        var c = t; var depth = 0
        while (c != null && depth++ < 8) {
            if (c is android.database.sqlite.SQLiteFullException) return true
            if (c is ErrnoException && c.errno == OsConstants.ENOSPC) return true
            val m = c.message ?: ""
            if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true) || m.contains("disk is full", ignoreCase = true)) return true
            c = c.cause
        }
        return false
    }

    fun noteFull(t: Throwable) {
        TLog.w { "storage full: ${TLog.userMessage(t)}" }
        _full.value = "Storage is full on this box · settings and the guide cannot be saved until space is freed"
    }

    fun clearIfRoom(path: String) { if (_full.value != null && freeBytes(path) > 64L * 1024 * 1024) _full.value = null }

    /** "NVIDIA SHIELD Android TV · Android 11 · 2.1 GB free of 16 GB · 3.0 GB memory", for About. */
    fun deviceLine(context: Context): String {
        val path = context.filesDir.path
        val free = freeBytes(path); val total = totalBytes(path)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${"%.1f".format(free / 1e9)} GB free of ${"%.0f".format(total / 1e9)} GB · ${"%.1f".format(mi.totalMem / 1e9)} GB memory"
    }
}
