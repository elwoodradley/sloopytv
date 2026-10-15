package tv.sloopy.record

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import okhttp3.Request
import tv.sloopy.SloopyApp
import tv.sloopy.TLog
import tv.sloopy.core.model.StreamContainer
import tv.sloopy.data.AndroidHttp
import tv.sloopy.data.ProviderKind
import tv.sloopy.data.db.RecordingEntity

/** Where recordings go and how a scheduled one is started on time. */
object Recorder {
    const val ACTION_START = "tv.sloopy.record.START"
    const val ACTION_STOP = "tv.sloopy.record.STOP"
    const val EXTRA_ID = "id"
    const val LEAD_MS = 15_000L   // start a little early so the first seconds are not missed

    fun start(context: Context, id: Long) {
        val i = Intent(context, RecordService::class.java).setAction(ACTION_START).putExtra(EXTRA_ID, id)
        ContextCompat.startForegroundService(context, i)
    }
    fun stop(context: Context, id: Long) {
        context.startService(Intent(context, RecordService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id))
    }

    fun schedule(context: Context, id: Long, startEpoch: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = startEpoch * 1000 - LEAD_MS
        val pi = pending(context, id)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }
    fun unschedule(context: Context, id: Long) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(context, id))
    }
    private fun pending(context: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        context, id.toInt(), Intent(context, RecordReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** A short name for the folder setting: the last path segment of a chosen tree, else the built-in place. */
    fun describeFolder(folder: String?): String {
        if (folder.isNullOrBlank()) return "This stick's own storage (small). Plug in a USB drive and choose it for long recordings."
        return runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(folder)).substringAfter(':').ifBlank { "drive root" } }.getOrDefault("Chosen folder")
    }

    fun openOutput(context: Context, folder: String?, name: String): Pair<Uri, OutputStream> {
        if (!folder.isNullOrBlank() && folder.startsWith("content://")) {
            val tree = Uri.parse(folder)
            val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val uri = DocumentsContract.createDocument(context.contentResolver, dir, "video/mp2t", name) ?: throw java.io.IOException("Could not create a file in the chosen folder")
            return uri to (context.contentResolver.openOutputStream(uri, "w") ?: throw java.io.IOException("Could not open the file for writing"))
        }
        val base = context.getExternalFilesDir("recordings") ?: File(context.filesDir, "recordings")
        base.mkdirs()
        val f = File(base, name)
        return Uri.fromFile(f) to f.outputStream()
    }

    fun delete(context: Context, uri: String?) {
        if (uri.isNullOrBlank()) return
        val u = Uri.parse(uri)
        runCatching {
            if (u.scheme == "content") DocumentsContract.deleteDocument(context.contentResolver, u) else u.path?.let { File(it).delete() }
        }
    }

    fun fileName(channel: String, title: String, startEpoch: Long): String {
        val t = java.time.Instant.ofEpochSecond(startEpoch).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"))
        fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(60)
        return "${clean(channel)} - ${clean(title)} - $t.ts"
    }
}

class RecordReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Recorder.EXTRA_ID, -1); if (id < 0) return
        Recorder.start(context, id)
    }
}

/**
 * Copies the channel's transport stream to a file until the programme ends or the viewer stops
 * it. Foreground so the system keeps it alive with the app in the background. Several can run
 * at once; each is one of the provider's connections.
 */
class RecordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<Long, Job>()
    private val stopped = HashSet<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(Recorder.EXTRA_ID, -1) ?: -1
        when (intent?.action) {
            Recorder.ACTION_START -> if (id >= 0 && !jobs.containsKey(id)) { ensureForeground(); jobs[id] = scope.launch { record(id) } }
            Recorder.ACTION_STOP -> { stopped.add(id); jobs[id]?.cancel() }
        }
        if (jobs.isEmpty()) finishIfIdle()
        return START_NOT_STICKY
    }

    private fun ensureForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("SloopyTV is recording").setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTIF, n)
    }

    private fun finishIfIdle() {
        if (jobs.isNotEmpty()) return
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private suspend fun record(id: Long) {
        val g = SloopyApp.graph(this)
        val dao = g.db.recordings()
        val r = dao.get(id) ?: return
        val provider = g.providers.get(r.providerId)
        val channel = runCatching { g.db.channels().byId(r.providerId, r.channelId) }.getOrNull()
        if (provider == null || channel == null) { dao.upsert(r.copy(status = "failed", error = "Channel is no longer in the list")); done(id); return }
        val url = channel.directUrl ?: if (provider.kind == ProviderKind.XTREAM) provider.xtream().liveUrl(channel.id, StreamContainer.TS) else null
        if (url == null) { dao.upsert(r.copy(status = "failed", error = "No stream for this channel")); done(id); return }
        if (url.contains(".m3u8", ignoreCase = true)) { dao.upsert(r.copy(status = "failed", error = "This channel is an HLS playlist; only transport streams can be recorded")); done(id); return }
        val folder = g.prefs.recordFolder.first()
        var bytes = 0L
        var uri: Uri? = null
        try {
            val (u, out) = Recorder.openOutput(this, folder, Recorder.fileName(channel.name, r.title, r.startEpoch))
            uri = u
            dao.upsert(r.copy(status = "recording", uri = u.toString()))
            out.use { sink ->
                // Watching this channel already? Ride the player's stream instead of opening a second
                // connection (one-stream accounts). Falls through to a direct download if the viewer
                // tunes away before the end.
                if (tv.sloopy.player.RecordTee.playingUrl == url && !tv.sloopy.player.RecordTee.active) {
                    tv.sloopy.player.RecordTee.attach(id, sink, r.stopEpoch)
                    try {
                        while (System.currentTimeMillis() / 1000 < r.stopEpoch && tv.sloopy.player.RecordTee.playingUrl == url) {
                            kotlinx.coroutines.delay(5_000)
                            bytes = tv.sloopy.player.RecordTee.bytes
                            dao.upsert(r.copy(status = "recording", uri = u.toString(), bytes = bytes))
                        }
                    } finally { bytes = tv.sloopy.player.RecordTee.detach() }
                }
                if (System.currentTimeMillis() / 1000 < r.stopEpoch) AndroidHttp.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw java.io.IOException("The provider answered HTTP ${resp.code}")
                    val input = resp.body?.byteStream() ?: throw java.io.IOException("Empty stream")
                    val buf = ByteArray(256 * 1024)
                    var lastWrite = System.currentTimeMillis()
                    while (System.currentTimeMillis() / 1000 < r.stopEpoch) {
                        val n = input.read(buf); if (n < 0) break
                        sink.write(buf, 0, n); bytes += n
                        val now = System.currentTimeMillis()
                        if (now - lastWrite > 5_000) { lastWrite = now; dao.upsert(r.copy(status = "recording", uri = u.toString(), bytes = bytes)) }
                    }
                }
            }
            dao.upsert(r.copy(status = "done", uri = u.toString(), bytes = bytes))
        } catch (e: kotlinx.coroutines.CancellationException) {
            dao.upsert(r.copy(status = if (bytes > 0) "done" else "failed", uri = uri?.toString(), bytes = bytes, error = if (bytes > 0) null else "Stopped"))
        } catch (e: Exception) {
            TLog.w { "recording failed: ${TLog.userMessage(e)}" }
            dao.upsert(r.copy(status = if (bytes > 0) "done" else "failed", uri = uri?.toString(), bytes = bytes, error = TLog.userMessage(e)))
        }
        done(id)
    }

    private fun done(id: Long) {
        android.os.Handler(mainLooper).post { jobs.remove(id); finishIfIdle() }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object { const val CHANNEL = "recording"; const val NOTIF = 41 }
}
