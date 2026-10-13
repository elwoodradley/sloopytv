package tv.sloopy.pair

/** The phone-side page. Plain HTML, no external resources: only the TV serves it. */
object PairPage {
    fun html(saltHex: String, expired: Boolean): String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SloopyTV · Sign in</title>
<style>
  :root{color-scheme:dark}
  body{margin:0;background:#0b0b0c;color:#ebe9e4;font:17px/1.4 -apple-system,system-ui,Segoe UI,Roboto,sans-serif}
  main{max-width:520px;margin:0 auto;padding:28px 20px 60px}
  h1{font-size:30px;line-height:1;margin:0 0 6px;letter-spacing:.01em;text-transform:uppercase}
  .k{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;letter-spacing:.18em;text-transform:uppercase;color:#8d8a84}
  label{display:block;margin-top:22px}
  input{width:100%;box-sizing:border-box;background:none;border:0;border-bottom:1px solid #2e2e32;color:#ebe9e4;font:inherit;font-size:19px;padding:10px 0;outline:none;border-radius:0}
  input:focus{border-bottom:2px solid #ebe9e4}
  input.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:17px}
  .hint{color:#8d8a84;font-size:14px;margin-top:8px}
  button{margin-top:28px;width:100%;height:50px;border:0;background:#ebe9e4;color:#0b0b0c;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px;letter-spacing:.16em;text-transform:uppercase;border-radius:0}
  button:disabled{opacity:.4}
  .msg{margin-top:18px;font-size:15px;color:#e8a53a;min-height:22px}
  .ok{color:#ebe9e4}
  .lock{display:flex;gap:10px;align-items:flex-start;margin-top:26px;color:#8d8a84;font-size:14px}
</style></head>
<body><main>
  <div class="k">SloopyTV · Sign in from your phone</div>
  <h1>Add a provider</h1>
  ${if (expired) """<p class="msg">This code has expired. Open the Add Provider screen on the TV again.</p>""" else ""}
  <form id="f" autocomplete="on">
    <label id="codeRow" style="display:none"><span class="k">Code shown on the TV</span><input id="code" class="mono" inputmode="numeric" pattern="[0-9]*" maxlength="6" autocomplete="one-time-code"></label>
    <label><span class="k">Server address, or paste the whole link</span><input id="url" class="mono" type="url" inputmode="url" autocapitalize="off" autocorrect="off" placeholder="http://host:8080  or a get.php / m3u link"></label>
    <div class="hint">A pasted get.php or M3U link fills the username and password for you.</div>
    <label><span class="k">Username</span><input id="user" class="mono" autocapitalize="off" autocorrect="off" autocomplete="username"></label>
    <label><span class="k">Password</span><input id="pass" class="mono" type="password" autocomplete="current-password"></label>
    <label><span class="k">Name (optional)</span><input id="name" placeholder="Home"></label>
    <button id="go" type="submit">Send to TV</button>
    <div id="msg" class="msg"></div>
  </form>
  <div class="lock"><span>&#128274;</span><span>Encrypted on this phone before it leaves. Only the TV can read it, and it is stored only in the TV's secure keystore.</span></div>
</main>
<script src="/nacl.js"></script>
<script>
(function(){
  var SALT = "$saltHex";
  var hash = location.hash.replace(/^#/, "");
  var parts = hash.split(".");
  var qrCode = parts.length === 2 && /^\d{6}$/.test(parts[0]) ? parts[0] : null;
  var qrKey = null;
  if (qrCode) { try { qrKey = b64urlDecode(parts[1]); if (qrKey.length !== 32) qrKey = null; } catch (e) { qrKey = null; } }
  if (!qrKey) document.getElementById("codeRow").style.display = "block";
  // Keep the fragment out of history/share sheets once read.
  if (hash && history.replaceState) history.replaceState(null, "", location.pathname);

  function b64urlDecode(s) { s = s.replace(/-/g, "+").replace(/_/g, "/"); while (s.length % 4) s += "="; var bin = atob(s); var u = new Uint8Array(bin.length); for (var i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i); return u; }
  function b64(u) { var s = ""; for (var i = 0; i < u.length; i++) s += String.fromCharCode(u[i]); return btoa(s); }
  function hexBytes(h) { var u = new Uint8Array(h.length / 2); for (var i = 0; i < u.length; i++) u[i] = parseInt(h.substr(i * 2, 2), 16); return u; }
  function utf8(s) { return new TextEncoder().encode(s); }
  function concat(a, b) { var u = new Uint8Array(a.length + b.length); u.set(a, 0); u.set(b, a.length); return u; }
  function deriveKey(code) { var salt = hexBytes(SALT); var h = nacl.hash(concat(utf8(code), salt)); for (var i = 0; i < 20000; i++) h = nacl.hash(concat(h, salt)); return h.slice(0, 32); }

  var f = document.getElementById("f"), msg = document.getElementById("msg"), go = document.getElementById("go");
  f.addEventListener("submit", function (ev) {
    ev.preventDefault();
    msg.className = "msg"; msg.textContent = "";
    var code = qrCode || document.getElementById("code").value.trim();
    if (!/^\d{6}$/.test(code)) { msg.textContent = "Enter the six-digit code shown on the TV."; return; }
    var payload = { url: document.getElementById("url").value.trim(), user: document.getElementById("user").value.trim(), pass: document.getElementById("pass").value, name: document.getElementById("name").value.trim() };
    if (!payload.url) { msg.textContent = "The server address is empty."; return; }
    go.disabled = true; msg.textContent = "Encrypting…";
    setTimeout(function () {
      try {
        var key = qrKey || deriveKey(code);
        var nonce = nacl.randomBytes(24);
        var box = nacl.secretbox(utf8(JSON.stringify(payload)), nonce, key);
        msg.textContent = "Sending…";
        fetch("/pair", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code: code, mode: qrKey ? "qr" : "code", nonce: b64(nonce), box: b64(box) }) })
          .then(function (r) { return r.json(); })
          .then(function (j) { msg.className = j.ok ? "msg ok" : "msg"; msg.textContent = j.message; go.disabled = !j.ok ? false : true; if (j.ok) { document.getElementById("pass").value = ""; } })
          .catch(function () { msg.textContent = "Couldn't reach the TV. Same Wi-Fi?"; go.disabled = false; });
      } catch (e) { msg.textContent = "Couldn't encrypt in this browser."; go.disabled = false; }
    }, 30);
  });
})();
</script>
</body></html>"""
}
