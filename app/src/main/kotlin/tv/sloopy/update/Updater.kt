package tv.sloopy.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import tv.sloopy.BuildConfig
import tv.sloopy.TLog
import tv.sloopy.data.AndroidHttp
import java.io.File
import java.security.MessageDigest

data class UpdateInfo(val version: String, val url: String, val notes: String?, val sizeBytes: Long)

sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data object UpToDate : UpdateState()
    data class Available(val info: UpdateInfo) : UpdateState()
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState()
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState()
    data class Failed(val message: String) : UpdateState()
    /** The box has not yet allowed SloopyTV to install updates; the system page to allow it was opened. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState()
}

/**
 * Self-update from GitHub Releases, so a sideloaded install never needs Downloader again.
 *
 * Safety: the downloaded APK must carry the same package name and be signed with the same
 * certificate as the running app, checked here before the installer is even shown (Android
 * enforces the same rule at install time; this check just gives a readable message instead
 * of a system error). Releases are fetched over HTTPS from the GitHub API; nothing is
 * executed, the system package installer does the install and asks the viewer first.
 */
class Updater(private val context: Context) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    @Volatile private var lastCheck = 0L

    private val json = Json { ignoreUnknownKeys = true }

    /** Where to ask. Debug builds can point at a local server through files/update_api for testing. */
    private fun apiUrl(): String {
        if (BuildConfig.DEBUG) {
            val f = File(context.filesDir, "update_api")
            if (f.exists()) return f.readText().trim()
        }
        return RELEASES_API
    }

    suspend fun check(force: Boolean = false): UpdateState = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 6 * 3600_000L) return@withContext _state.value
        if (_state.value is UpdateState.Downloading) return@withContext _state.value
        lastCheck = now
        _state.value = UpdateState.Checking
        try {
            val req = Request.Builder().url(apiUrl()).header("Accept", "application/vnd.github+json").build()
            val body = AndroidHttp.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("GitHub answered HTTP ${r.code}")
                r.body?.string() ?: ""
            }
            val root = json.parseToJsonElement(body) as? JsonObject ?: throw IllegalStateException("Unexpected reply")
            val tag = root.str("tag_name") ?: throw IllegalStateException("No release found")
            val version = tag.removePrefix("v")
            val assets = root["assets"] as? JsonArray ?: JsonArray(emptyList())
            val apk = assets.mapNotNull { it as? JsonObject }.firstOrNull { it.str("name") == ASSET_NAME }
                ?: throw IllegalStateException("The release has no $ASSET_NAME")
            val info = UpdateInfo(
                version = version,
                url = apk.str("browser_download_url") ?: throw IllegalStateException("No download link"),
                notes = root.str("body")?.take(2000),
                sizeBytes = (apk["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L,
            )
            _state.value = if (isNewer(version, BuildConfig.VERSION_NAME)) UpdateState.Available(info) else UpdateState.UpToDate
        } catch (e: Exception) {
            TLog.w { "update check failed: ${TLog.userMessage(e)}" }
            _state.value = UpdateState.Failed("Couldn't check for updates: ${TLog.userMessage(e)}")
        }
        _state.value
    }

    /** Android 8+ asks each app to be allowed as an install source once. Open that page if needed. */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        if (Build.VERSION.SDK_INT < 26) return
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Downloads, verifies and hands the APK to the system installer. */
    suspend fun install(info: UpdateInfo) = withContext(Dispatchers.IO) {
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission(info)
            openInstallPermission()
            return@withContext
        }
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, ASSET_NAME)
            _state.value = UpdateState.Downloading(info, 0)
            val req = Request.Builder().url(info.url).build()
            AndroidHttp.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("Download failed: HTTP ${r.code}")
                val total = r.body?.contentLength() ?: -1L
                if (total > MAX_APK_BYTES) throw IllegalStateException("Update is unexpectedly large")
                r.body!!.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (done > MAX_APK_BYTES) throw IllegalStateException("Update is unexpectedly large")
                            val pct = if (total > 0) (done * 100 / total).toInt() else 0
                            if (pct != lastPct) { lastPct = pct; _state.value = UpdateState.Downloading(info, pct) }
                        }
                    }
                }
            }
            verify(file)
            _state.value = UpdateState.Ready(info, file)
            launchInstaller(file)
        } catch (e: Exception) {
            TLog.w { "update failed: ${TLog.userMessage(e)}" }
            _state.value = UpdateState.Failed(TLog.userMessage(e))
        }
    }

    private fun verify(file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IllegalStateException("The file is not an app package")
        if (candidate.packageName != context.packageName) throw IllegalStateException("That package is not SloopyTV")
        val mine = pm.getPackageInfo(context.packageName, flags)
        var candidateCerts = certDigests(candidate)
        if (candidateCerts.isEmpty()) {
            // Android 9 to 11 leave signingInfo empty for an APK file read from disk; the old
            // signatures call still works there.
            @Suppress("DEPRECATION")
            val legacy = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            candidateCerts = (legacy?.signatures ?: emptyArray()).map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
        }
        // If the OS cannot report a certificate at all, the system installer still enforces
        // same-signer on install, so defer to it rather than block a legitimate update.
        if (candidateCerts.isNotEmpty() && candidateCerts != certDigests(mine)) throw IllegalStateException("The update is not signed with SloopyTV's key. Refusing to install it.")
        @Suppress("DEPRECATION")
        val candidateCode = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        if (candidateCode <= BuildConfig.VERSION_CODE) throw IllegalStateException("That build is not newer than this one")
    }

    private fun certDigests(info: android.content.pm.PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else @Suppress("DEPRECATION") info.signatures
        return (sigs ?: emptyArray()).map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun launchInstaller(file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** True when [candidate] is a higher semantic version than [current] (0.1.10 > 0.1.9). */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val RELEASES_API = "https://api.github.com/repos/elwoodradley/sloopytv/releases/latest"
        const val ASSET_NAME = "sloopytv.apk"
        const val MAX_APK_BYTES = 60L * 1024 * 1024
    }
}
