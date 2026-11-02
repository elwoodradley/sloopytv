package tv.sloopy.record

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import tv.sloopy.SloopyApp
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Recordings on a network share (a NAS, a PC's shared folder) over SMB 2/3. The folder setting
 * holds `smb://host/share/folder`; the user name and password live in the Vault, never in
 * DataStore. This is the one network path in the app outside AndroidHttp: SMB is not HTTP.
 * Connections are cached per host by the client and are never closed here (a recording and a
 * playback may share one); sessions and share handles are opened and closed per use.
 */
object Smb {
    const val PREFIX = "smb://"
    const val VAULT_USER = "smb_user"
    const val VAULT_PASSWORD = "smb_password"

    data class Target(val host: String, val share: String, val path: String)

    fun isSmb(s: String?): Boolean = s != null && s.startsWith(PREFIX)

    /** `smb://host/share/a/b` → host, share, "a/b" (may be empty). Null when it is not an SMB path. */
    fun parse(s: String?): Target? {
        if (s == null || !s.startsWith(PREFIX)) return null
        val parts = s.removePrefix(PREFIX).trim('/').split('/', limit = 3)
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return Target(parts[0], parts[1], parts.getOrNull(2)?.trim('/') ?: "")
    }

    fun url(host: String, share: String, path: String): String =
        PREFIX + host.trim().trim('/', '\\') + "/" + share.trim().trim('/', '\\') + path.trim().replace('\\', '/').trim('/').let { if (it.isBlank()) "" else "/$it" }

    /** Windows-style for the settings line: \\\\nas\\video\\SloopyTV. */
    fun describe(t: Target): String = "\\\\" + t.host + "\\" + t.share + (if (t.path.isBlank()) "" else "\\" + t.path.replace('/', '\\'))

    private val client: SMBClient by lazy {
        SMBClient(SmbConfig.builder().withTimeout(20, TimeUnit.SECONDS).withSoTimeout(60, TimeUnit.SECONDS).withMultiProtocolNegotiate(true).build())
    }

    class Handle(private val session: Session, val share: DiskShare) : Closeable {
        override fun close() { runCatching { share.close() }; runCatching { session.close() } }
    }

    /** Signs in with the Vault's user and password ("DOMAIN\\user" allowed; blank user = anonymous). */
    fun open(context: Context, t: Target): Handle {
        val vault = SloopyApp.graph(context).vault
        val user = vault.getString(VAULT_USER)?.trim() ?: ""
        val pass = vault.getString(VAULT_PASSWORD) ?: ""
        val (domain, name) = user.split('\\', limit = 2).let { if (it.size == 2) it[0] to it[1] else null to it[0] }
        val auth = if (name.isBlank()) AuthenticationContext.anonymous() else AuthenticationContext(name, pass.toCharArray(), domain)
        val conn = client.connect(t.host)
        val session = conn.authenticate(auth)
        val share = session.connectShare(t.share) as? DiskShare ?: run { runCatching { session.close() }; throw IOException("${t.share} is not a folder share") }
        return Handle(session, share)
    }

    private fun smbPath(vararg parts: String): String = parts.filter { it.isNotBlank() }.joinToString("\\") { it.replace('/', '\\').trim('\\') }

    /** Lists the folder once; the count comes back, or the error says what is wrong. */
    fun test(context: Context, folder: String): Int {
        val t = parse(folder) ?: throw IOException("Not an SMB folder")
        open(context, t).use { h ->
            val p = smbPath(t.path)
            if (p.isNotBlank() && !h.share.folderExists(p)) throw IOException("No folder \"${t.path}\" on the share")
            return h.share.list(p).count { it.fileName != "." && it.fileName != ".." }
        }
    }

    /** Creates (or overwrites) `name` in the folder; closing the stream closes the file and the session. */
    fun openOutput(context: Context, folder: String, name: String): Pair<String, OutputStream> {
        val t = parse(folder) ?: throw IOException("Not an SMB folder")
        val h = open(context, t)
        try {
            val dir = smbPath(t.path)
            if (dir.isNotBlank() && !h.share.folderExists(dir)) {
                var sofar = ""
                for (seg in dir.split('\\')) { sofar = if (sofar.isBlank()) seg else "$sofar\\$seg"; if (!h.share.folderExists(sofar)) h.share.mkdir(sofar) }
            }
            val f = h.share.openFile(smbPath(dir, name), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null)
            val out = f.outputStream
            val wrapped = object : OutputStream() {
                override fun write(b: Int) = out.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun flush() = out.flush()
                override fun close() { try { out.close() } finally { runCatching { f.close() }; h.close() } }
            }
            return url(t.host, t.share, t.path + "/" + name) to wrapped
        } catch (e: Exception) { h.close(); throw e }
    }

    /** Opens a file for reading; the caller closes both. */
    fun openInput(context: Context, fileUrl: String): Pair<Handle, File> {
        val t = parse(fileUrl) ?: throw IOException("Not an SMB file")
        val h = open(context, t)
        try {
            return h to h.share.openFile(smbPath(t.path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        } catch (e: Exception) { h.close(); throw e }
    }

    fun delete(context: Context, fileUrl: String) {
        val t = parse(fileUrl) ?: return
        open(context, t).use { h -> h.share.rm(smbPath(t.path)) }
    }
}
