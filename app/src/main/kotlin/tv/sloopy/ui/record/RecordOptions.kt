package tv.sloopy.ui.record

import androidx.compose.runtime.Composable
import tv.sloopy.data.db.ChannelEntity
import tv.sloopy.data.db.ProgrammeEntity
import tv.sloopy.ui.SloopyViewModel
import tv.sloopy.ui.common.ChoicePrompt

/**
 * Recording from a hold menu. The menu shows one row, [entry]; picking it opens [RecordPrompt]
 * with the three paddings (the viewer, 2026-10-17: fold the three rows into one, like Copies).
 * Padding after the end covers late running.
 */
object RecordOptions {
    val entry = "record" to "Record"
    val choices = listOf("rec2" to "Ends 2 min after", "rec15" to "Ends 15 min after", "rec60" to "Ends 60 min after")

    /** True when [key] was one of ours and the recording was scheduled. */
    fun handle(vm: SloopyViewModel, key: String, c: ChannelEntity, p: ProgrammeEntity?): Boolean {
        val pad = when (key) { "rec2" -> 120L; "rec15" -> 900L; "rec60" -> 3600L; else -> return false }
        val now = System.currentTimeMillis() / 1000
        if (p == null) vm.scheduleRecording(c, c.name, now, now + 7200, pad)
        else vm.scheduleRecording(c, p.title, p.startEpoch, p.stopEpoch, pad)
        return true
    }
}

/** The second step: how much to keep after the listed end. [onDone] runs on a pick or Back. */
@Composable
fun RecordPrompt(vm: SloopyViewModel, c: ChannelEntity, p: ProgrammeEntity?, onDone: () -> Unit) {
    ChoicePrompt("RECORD " + (p?.title ?: c.name).uppercase().take(16), RecordOptions.choices + ("no" to "Not now"), onCancel = onDone, onPick = { k ->
        RecordOptions.handle(vm, k, c, p); onDone()
    })
}
