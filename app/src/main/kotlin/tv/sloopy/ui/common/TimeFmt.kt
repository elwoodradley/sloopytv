package tv.sloopy.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Every time shown in the app comes through here, so the 12/24-hour setting applies everywhere. */
object TimeFmt {
    var twelveHour: Boolean by mutableStateOf(false)

    private val h24 = DateTimeFormatter.ofPattern("HH:mm")
    private val h12 = DateTimeFormatter.ofPattern("h:mm a")
    private val d24 = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")
    private val d12 = DateTimeFormatter.ofPattern("EEE d MMM · h:mm a")

    fun clock(t: LocalTime): String = t.format(if (twelveHour) h12 else h24).replace(" AM", " am").replace(" PM", " pm")
    fun hhmm(epoch: Long): String = Instant.ofEpochSecond(epoch).atZone(ZoneId.systemDefault()).format(if (twelveHour) h12 else h24).replace(" AM", "a").replace(" PM", "p")
    fun dateTime(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(if (twelveHour) d12 else d24).replace(" AM", " am").replace(" PM", " pm")
}
