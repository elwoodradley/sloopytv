package tv.sloopy.ui.record

import tv.sloopy.data.db.ChannelEntity
import tv.sloopy.data.db.ProgrammeEntity
import tv.sloopy.ui.SloopyViewModel

/** The record choices every hold menu offers. Padding after the end covers late running. */
object RecordOptions {
    val choices = listOf("rec2" to "Record · ends 2 min after", "rec15" to "Record · ends 15 min after", "rec60" to "Record · ends 60 min after")

    /** True when [key] was one of ours and the recording was scheduled. */
    fun handle(vm: SloopyViewModel, key: String, c: ChannelEntity, p: ProgrammeEntity?): Boolean {
        val pad = when (key) { "rec2" -> 120L; "rec15" -> 900L; "rec60" -> 3600L; else -> return false }
        val now = System.currentTimeMillis() / 1000
        if (p == null) vm.scheduleRecording(c, c.name, now, now + 7200, pad)
        else vm.scheduleRecording(c, p.title, p.startEpoch, p.stopEpoch, pad)
        return true
    }
}
