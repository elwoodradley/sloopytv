package tv.tube.core.m3u

import tv.tube.core.model.Channel
import tv.tube.core.net.Limits
import tv.tube.core.net.LimitedInputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream

class M3uEntry(
    val name: String,
    val url: String,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val group: String?,
    val catchupDays: Int,
)

class M3uException(message: String) : IOException(message)

/**
 * Tolerant M3U / M3U8 playlist parser for IPTV style playlists.
 * Caps total bytes, line length and entry count. Only http(s) stream URLs are accepted.
 */
object M3uParser {
    private val attr = Regex("""([A-Za-z0-9_-]+)\s*=\s*"([^"]*)"|([A-Za-z0-9_-]+)\s*=\s*([^\s,]+)""")

    fun parse(input: InputStream, maxBytes: Long = Limits.M3U_BYTES): List<M3uEntry> {
        val reader = BufferedReader(LimitedInputStream(input, maxBytes).reader(Charsets.UTF_8), 64 * 1024)
        val out = ArrayList<M3uEntry>()
        var pendingInf: String? = null
        var pendingGroup: String? = null
        var lineNo = 0
        var sawHeader = false
        while (true) {
            val rawLine = readLineCapped(reader) ?: break
            lineNo++
            val line = rawLine.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            if (lineNo == 1 && line.startsWith("#EXTM3U")) { sawHeader = true; continue }
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> pendingInf = line
                line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substringAfter(':').trim().take(200)
                line.startsWith("#") -> { /* #EXTVLCOPT, #KODIPROP and friends: intentionally ignored */ }
                else -> {
                    val url = line.take(4096)
                    if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                        out.add(entryFrom(pendingInf, pendingGroup, url, out.size))
                        if (out.size > Limits.MAX_CHANNELS) throw M3uException("Playlist has more entries than the app allows")
                    }
                    pendingInf = null
                    pendingGroup = null
                }
            }
        }
        if (!sawHeader && out.isEmpty()) throw M3uException("Not an M3U playlist")
        return out
    }

    private fun readLineCapped(reader: BufferedReader): String? {
        val sb = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c != '\r'.code && sb.length < 8192) sb.append(c.toChar())
        }
    }

    private fun entryFrom(inf: String?, group: String?, url: String, index: Int): M3uEntry {
        if (inf == null) return M3uEntry("Stream ${index + 1}", url, null, null, null, group, 0)
        val afterColon = inf.substringAfter(':', "")
        // The name follows the first comma that is outside quotes; attribute values may contain commas.
        var comma = -1
        var inQuote = false
        for (i in afterColon.indices) {
            val ch = afterColon[i]
            if (ch == '"') inQuote = !inQuote
            else if (ch == ',' && !inQuote) { comma = i; break }
        }
        val attrsPart = if (comma >= 0) afterColon.substring(0, comma) else afterColon
        val name = (if (comma >= 0) afterColon.substring(comma + 1) else "").trim().take(200)
        val attrs = HashMap<String, String>()
        attr.findAll(attrsPart).forEach { m ->
            val k = (m.groupValues[1].ifEmpty { m.groupValues[3] }).lowercase()
            val v = m.groupValues[2].ifEmpty { m.groupValues[4] }
            attrs[k] = v.take(500)
        }
        val logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        return M3uEntry(
            name = name.ifEmpty { attrs["tvg-name"] ?: "Stream ${index + 1}" },
            url = url,
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
            tvgName = attrs["tvg-name"],
            logo = logo,
            group = attrs["group-title"] ?: group,
            catchupDays = attrs["catchup-days"]?.toIntOrNull() ?: 0,
        )
    }

    fun toChannels(entries: List<M3uEntry>): List<Channel> = entries.mapIndexed { i, e ->
        Channel(
            id = stableId(e.url),
            number = i + 1,
            name = e.name,
            logo = e.logo,
            categoryId = e.group,
            epgId = e.tvgId,
            archive = e.catchupDays > 0,
            archiveDays = e.catchupDays,
            directUrl = e.url,
        )
    }

    /** 63-bit FNV-1a of the URL so M3U channels keep a stable id across refreshes. */
    private fun stableId(s: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (ch in s) { h = h xor ch.code.toLong(); h *= 0x100000001b3L }
        return h and Long.MAX_VALUE
    }
}
