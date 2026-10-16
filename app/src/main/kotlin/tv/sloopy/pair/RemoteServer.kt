package tv.sloopy.pair

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import tv.sloopy.TLog
import java.security.SecureRandom

/** A line the phone sent: text to type, or a channel to tune. */
sealed class RemoteEvent {
    data class Type(val text: String, val submit: Boolean) : RemoteEvent()
    data class Tune(val channelId: Long) : RemoteEvent()
    data class Toad(val text: String) : RemoteEvent()
}

/**
 * The phone as a keyboard and a tappable channel list. Runs while the app is open on a port
 * next to the pairing server. Every request carries a token that only the QR on the TV shows,
 * and the token changes each launch, so nothing else on the Wi-Fi can type into the TV.
 */
class RemoteServer(private val context: Context, port: Int, private val channelsJson: () -> String, private val stateJson: () -> String) : NanoHTTPD("0.0.0.0", port) {
    val token: String = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    val events = MutableSharedFlow<RemoteEvent>(extraBufferCapacity = 16)
    private val json = Json { ignoreUnknownKeys = true }

    val address: String get() = "http://${PairingServer.localIp() ?: "?"}:$listeningPort/remote#$token"

    override fun serve(session: IHTTPSession): Response {
        return try {
            val ok = session.headers["x-token"] == token || session.parameters["t"]?.firstOrNull() == token
            when {
                session.method == Method.GET && session.uri == "/remote" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", RemotePage.html())
                !ok -> newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", """{"ok":false,"message":"Scan the code on the TV again."}""")
                session.method == Method.GET && session.uri == "/channels" -> newFixedLengthResponse(Response.Status.OK, "application/json", channelsJson())
                session.method == Method.GET && session.uri == "/state" -> newFixedLengthResponse(Response.Status.OK, "application/json", stateJson())
                session.method == Method.POST && session.uri == "/type" -> { val b = body(session); events.tryEmit(RemoteEvent.Type(b.str("text").orEmpty().take(200), b.str("submit") == "true")); okJson() }
                session.method == Method.POST && session.uri == "/toad" -> { val b = body(session); b.str("text")?.takeIf { it.isNotBlank() }?.let { events.tryEmit(RemoteEvent.Toad(it.take(300))) }; okJson() }
                session.method == Method.POST && session.uri == "/tune" -> { val b = body(session); b.str("id")?.toLongOrNull()?.let { events.tryEmit(RemoteEvent.Tune(it)) }; okJson() }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
            }
        } catch (e: Exception) {
            TLog.w { "remote request failed: ${TLog.userMessage(e)}" }
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"ok":false}""")
        }.also { it.addHeader("Cache-Control", "no-store") }
    }

    private fun body(session: IHTTPSession): JsonObject {
        val len = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0 || len > 16_384) return JsonObject(emptyMap())
        val buf = ByteArray(len); var read = 0
        while (read < len) { val n = session.inputStream.read(buf, read, len - read); if (n < 0) break; read += n }
        return runCatching { json.parseToJsonElement(buf.decodeToString()) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
    }
    private fun okJson() = newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true}""")
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        fun start(context: Context, channelsJson: () -> String, stateJson: () -> String): RemoteServer? {
            for (p in 8781 until 8790) {
                val s = RemoteServer(context, p, channelsJson, stateJson)
                try { s.start(SOCKET_READ_TIMEOUT, false); return s } catch (e: Exception) { runCatching { s.stop() } }
            }
            return null
        }
    }
}

object RemotePage {
    fun html(): String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SloopyTV · Remote</title>
<style>
  :root{color-scheme:dark}
  body{margin:0;background:#0b0b0c;color:#ebe9e4;font:17px/1.4 -apple-system,system-ui,Segoe UI,Roboto,sans-serif}
  main{max-width:560px;margin:0 auto;padding:22px 18px 60px}
  h1{font-size:28px;line-height:1;margin:0 0 4px;text-transform:uppercase}
  .k{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;letter-spacing:.18em;text-transform:uppercase;color:#8d8a84}
  input{width:100%;box-sizing:border-box;background:none;border:0;border-bottom:1px solid #2e2e32;color:#ebe9e4;font:inherit;font-size:20px;padding:12px 0;outline:none;border-radius:0}
  input:focus{border-bottom:2px solid #ebe9e4}
  .row{display:flex;gap:10px;margin-top:10px}
  button{height:46px;padding:0 18px;border:1px solid #3a3a3e;background:none;color:#ebe9e4;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;letter-spacing:.16em;text-transform:uppercase;border-radius:0}
  button.pri{background:#ebe9e4;color:#0b0b0c;border-color:#ebe9e4}
  .now{margin:22px 0 6px;color:#8d8a84;font-size:14px}
  .ch{display:flex;align-items:center;gap:12px;padding:12px 0;border-bottom:1px solid #1a1a1c}
  .ch .n{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;color:#8d8a84;width:44px}
  .ch .t{flex:1}
  .ch.on .t{color:#e8a53a}
  .msg{color:#e8a53a;min-height:20px;margin-top:8px;font-size:14px}
</style></head><body><main>
  <div class="k">SloopyTV · Remote</div><h1>Type here</h1>
  <input id="t" placeholder="Search channels, movies, series" autocapitalize="off" autocorrect="off">
  <div class="row"><button class="pri" id="go">Search</button><button id="clr">Clear</button></div>
  <div class="msg" id="m"></div>
  <div id="toad" style="display:none;margin-top:26px">
    <div class="k">The Toad</div>
    <input id="tt" placeholder="Put ESPN and AMC in my favourites" autocapitalize="sentences">
    <div class="row"><button class="pri" id="tgo">Ask the Toad</button></div>
    <div class="msg" id="tm"></div>
  </div>
  <div class="now" id="now"></div>
  <input id="f" placeholder="Filter channels" style="font-size:16px">
  <div id="list"></div>
</main>
<script>
(function(){
  var tok = location.hash.replace(/^#/, ""); if (history.replaceState) history.replaceState(null, "", location.pathname);
  function api(m, p, b){ return fetch(p, {method:m, headers:{"x-token":tok,"content-type":"application/json"}, body:b?JSON.stringify(b):undefined}).then(function(r){return r.json()}) }
  var t = document.getElementById("t"), m = document.getElementById("m"), all = [], timer;
  function send(submit){ api("POST","/type",{text:t.value, submit:submit?"true":"false"}).then(function(j){ if(!j.ok) m.textContent = j.message||"Not connected" }).catch(function(){ m.textContent="Couldn't reach the TV. Same Wi-Fi?" }) }
  t.addEventListener("input", function(){ clearTimeout(timer); timer = setTimeout(function(){ send(false) }, 250) });
  t.addEventListener("keydown", function(e){ if (e.key === "Enter") { e.preventDefault(); send(true) } });
  document.getElementById("go").onclick = function(){ send(true) };
  document.getElementById("clr").onclick = function(){ t.value = ""; send(false) };
  var f = document.getElementById("f"), list = document.getElementById("list");
  function render(){ var q = f.value.toLowerCase(); var h = ""; all.filter(function(c){ return !q || c.name.toLowerCase().indexOf(q) >= 0 }).slice(0, 300).forEach(function(c){ h += '<div class="ch'+(c.on?' on':'')+'" data-id="'+c.id+'"><span class="n">'+c.number+'</span><span class="t">'+c.name+'</span></div>' }); list.innerHTML = h }
  f.addEventListener("input", render);
  list.onclick = function(e){ var el = e.target.closest(".ch"); if (!el) return; api("POST","/tune",{id: el.getAttribute("data-id")}).then(refresh) };
  var tt = document.getElementById("tt"), tm = document.getElementById("tm");
  function askToad(){ if (!tt.value.trim()) return; api("POST","/toad",{text: tt.value}).then(function(j){ tm.textContent = j.ok ? "Sent. The TV shows what it will do; OK there runs it." : (j.message||"Not connected"); if (j.ok) tt.value = "" }).catch(function(){ tm.textContent = "Couldn't reach the TV. Same Wi-Fi?" }) }
  tt.addEventListener("keydown", function(e){ if (e.key === "Enter") { e.preventDefault(); askToad() } });
  document.getElementById("tgo").onclick = askToad;
  function refresh(){ api("GET","/state").then(function(s){ document.getElementById("now").textContent = s.now ? "On the TV: " + s.now : ""; document.getElementById("toad").style.display = s.toad ? "block" : "none"; all.forEach(function(c){ c.on = (c.id == s.id) }); render() }).catch(function(){}) }
  api("GET","/channels").then(function(j){ all = j.channels || []; render(); refresh() }).catch(function(){ m.textContent = "Scan the code on the TV again." });
  setInterval(refresh, 8000);
})();
</script></body></html>"""
}
