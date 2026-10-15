package tv.sloopy.data

import tv.sloopy.core.model.Programme
import tv.sloopy.data.db.CategoryEntity
import tv.sloopy.data.db.ChannelEntity

/**
 * Demo mode: a handful of public test streams that their publishers put up precisely so
 * that video players can be tested against them. No broadcaster content, nothing
 * licensed. The guide is synthetic so the grid has something to show.
 */
object Demo {
    const val PROVIDER_ID = "demo"

    val record = ProviderRecord(
        id = PROVIDER_ID,
        kind = ProviderKind.DEMO,
        name = "Demo streams",
        baseUrl = "https://demo.local",
    )

    private class Stream(val id: Long, val name: String, val url: String, val group: String, val note: String)

    private val streams = listOf(
        Stream(1, "Apple HLS test", "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8", "Test streams", "Apple's reference stream with alternate audio and subtitles"),
        Stream(2, "Big Buck Bunny", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", "Open films", "Blender Foundation, Creative Commons"),
        Stream(3, "Tears of Steel", "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8", "Open films", "Blender Foundation, Creative Commons"),
        Stream(4, "Sintel", "https://bitmovin-a.akamaihd.net/content/sintel/hls/playlist.m3u8", "Open films", "Blender Foundation, Creative Commons"),
        Stream(5, "Akamai live test", "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8", "Test streams", "A live test loop"),
        Stream(6, "Apple advanced HLS", "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8", "Test streams", "fMP4 segments, several bitrates"),
        // A second copy of the same stream, as providers carry them, so duplicate collapsing and
        // self-healing can be tried without a real account.
        Stream(7, "Big Buck Bunny FHD", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", "Open films", "Blender Foundation, Creative Commons"),
    )

    fun categories(): List<CategoryEntity> = streams.map { it.group }.distinct().mapIndexed { i, g ->
        CategoryEntity(PROVIDER_ID, "live", g, g, null, i)
    }

    fun channels(): List<ChannelEntity> = streams.mapIndexed { i, s ->
        ChannelEntity(
            providerId = PROVIDER_ID, id = s.id, number = i + 1, name = s.name, logo = null,
            categoryId = s.group, epgId = "demo.${s.id}", archive = false, archiveDays = 0,
            directUrl = s.url, sortKey = i,
        )
    }

    /** 48 hours of half-hour blocks around now. */
    fun programmes(nowEpoch: Long): List<Programme> {
        val out = ArrayList<Programme>()
        val start = (nowEpoch / 1800 - 48) * 1800
        for (s in streams) {
            var t = start
            var n = 1
            while (t < start + 96 * 1800) {
                val dur = if ((n + s.id) % 3 == 0L) 3600 else 1800
                out.add(Programme("demo.${s.id}", t, t + dur, "${s.name} loop $n", s.note, "Demo", null, null))
                t += dur
                n++
            }
        }
        return out
    }
}
