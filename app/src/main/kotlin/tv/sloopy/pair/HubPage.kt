package tv.sloopy.pair

/**
 * The lineup hub: a page the stick serves on the Wi-Fi (same server and token as the phone
 * remote) for a laptop or phone browser. Groups down the left, drag to reorder, eye to hide,
 * S for the Sports hub; the selected group's channels in the middle with star and eye; the
 * favourites list on the right. Every change posts to /hub/op and lands in the stick's own
 * store at once. No cloud, no account; it works only on the same Wi-Fi while the app is open.
 */
object HubPage {
    fun html(): String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SloopyTV · Lineup</title>
<style>
  :root{color-scheme:dark;--bg:#0b0b0c;--ink:#ebe9e4;--soft:#b9b6af;--mute:#8d8a84;--dim:#5c5a55;--hair:#232326;--lift:#141416;--amber:#e8a53a}
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--ink);font:17px/1.4 -apple-system,system-ui,Segoe UI,Roboto,sans-serif}
  .wrap{max-width:920px;margin:0 auto;padding:0 16px 80px}
  header{display:flex;align-items:baseline;gap:14px;padding:22px 0 14px}
  h1{font-size:30px;line-height:1;margin:0;text-transform:uppercase;letter-spacing:.02em}
  .k{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;letter-spacing:.18em;text-transform:uppercase;color:var(--mute)}
  #msg{margin-left:auto;color:var(--mute);font-size:14px;white-space:nowrap}
  #msg.on{color:var(--amber)}
  nav{display:flex;gap:8px;border-bottom:1px solid var(--hair);padding-bottom:12px;margin-bottom:6px}
  nav button{flex:1;height:48px;border:1px solid var(--hair);background:none;color:var(--mute);font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px;letter-spacing:.14em;text-transform:uppercase;border-radius:0;cursor:pointer}
  nav button.on{color:var(--ink);border-color:var(--ink)}
  .bar{display:flex;align-items:center;gap:12px;padding:14px 0 8px}
  .bar h2{font-size:22px;margin:0;text-transform:uppercase;font-weight:600;letter-spacing:.02em;flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .back{height:44px;padding:0 16px;border:1px solid var(--hair);background:none;color:var(--ink);font:inherit;font-size:15px;border-radius:0;cursor:pointer}
  .back:hover{border-color:var(--ink)}
  input{background:none;border:0;border-bottom:1px solid #2e2e32;color:var(--ink);font:inherit;font-size:18px;padding:12px 0;outline:none;border-radius:0;width:100%}
  input:focus{border-bottom-color:var(--ink)}
  .row{display:flex;align-items:center;gap:12px;min-height:64px;padding:8px 0;border-bottom:1px solid var(--hair)}
  .row.g{cursor:pointer}
  .row.g:hover{background:var(--lift)}
  .row.hid .t{color:var(--dim);text-decoration:line-through}
  .row .n{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px;color:var(--mute);width:50px;flex:none}
  .row .t{flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:18px}
  .row .c{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;color:var(--dim);flex:none}
  .b{height:42px;padding:0 14px;border:1px solid var(--hair);background:none;color:var(--mute);font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;letter-spacing:.12em;text-transform:uppercase;border-radius:0;cursor:pointer;flex:none;white-space:nowrap}
  .b:hover{border-color:var(--ink);color:var(--ink)}
  .b.on{color:var(--ink);border-color:var(--soft)}
  .b.star.on{color:var(--amber);border-color:var(--amber)}
  .b.mini{padding:0 12px;font-size:14px;letter-spacing:0}
  .open{color:var(--dim);font-size:22px;flex:none;width:18px;text-align:center}
  .empty{padding:28px 0;color:var(--mute)}
  .help{padding:14px 0;color:var(--dim);font-size:13px}
  .drop{outline:1px dashed var(--amber);outline-offset:-6px}
  @media (max-width:600px){.row{flex-wrap:wrap}.row .t{flex-basis:100%}.row .n{display:none}.b{height:40px}}
</style></head><body><div class="wrap">
<header><span class="k">SloopyTV</span><h1>Lineup</h1><span class="k" id="prov"></span><span id="msg"></span></header>
<nav><button data-v="groups" class="on">Groups</button><button data-v="favs">Favourites</button><button data-v="search">Search</button></nav>
<div id="view"></div>
</div>
<script>
(function(){
  var tok = location.hash.replace(/^#/, ""); if (history.replaceState) history.replaceState(null, "", location.pathname + location.search);
  var D = {groups:[], channels:[]}, view = "groups", sel = null, q = "", byGroup = {};
  var msg = document.getElementById("msg"), root = document.getElementById("view");
  function say(t, on){ msg.textContent = t; msg.className = on ? "on" : "" }
  function api(m, p, b){ return fetch(p, {method:m, headers:{"x-token":tok,"content-type":"application/json"}, body:b?JSON.stringify(b):undefined}).then(function(r){ return r.json() }) }
  function esc(s){ return String(s).replace(/[&<>"]/g, function(c){ return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c] }) }
  function op(body, label){ say("Saving…"); return api("POST","/hub/op",body).then(function(j){ if(!j.ok){ say(j.message||"The TV said no", true); return } say(label||"Saved"); setTimeout(load, 400) }).catch(function(){ say("Couldn't reach the TV. Same Wi-Fi? App open?", true) }) }
  function load(){ return api("GET","/hub/data").then(function(j){ D = j; byGroup = {}; D.channels.forEach(function(c){ (byGroup[c.g] = byGroup[c.g] || []).push(c) }); document.getElementById("prov").textContent = D.provider || ""; render() }).catch(function(){ say("Scan the code on the TV again.", true) }) }
  function group(id){ return D.groups.filter(function(x){ return x.id === id })[0] }
  function chan(id){ return D.channels.filter(function(c){ return String(c.id) === String(id) })[0] }
  function chanRow(c){ return '<div class="row'+(c.hid?' hid':'')+'" draggable="true" data-c="'+c.id+'"><span class="n">'+c.n+'</span><span class="t">'+esc(c.name)+'</span>'+
    '<button class="b star'+(c.fav?' on':'')+'" data-act="fav">'+(c.fav?'&#9733; Favourite':'&#9734; Favourite')+'</button><button class="b'+(c.hid?' on':'')+'" data-act="chide">'+(c.hid?'Hidden':'Hide')+'</button></div>' }
  function render(){
    document.querySelectorAll("nav button").forEach(function(b){ b.className = b.dataset.v === view ? "on" : "" });
    var h = "";
    if (view === "groups" && sel === null) {
      var shown = D.groups.filter(function(x){ return !x.hidden }).length;
      h += '<div class="bar"><h2>Groups</h2><span class="k">'+shown+' of '+D.groups.length+' shown</span></div>';
      D.groups.forEach(function(x, i){
        h += '<div class="row g'+(x.hidden?' hid':'')+'" draggable="true" data-g="'+esc(x.id)+'">'+
             '<button class="b mini" data-act="top" title="Move to the top"'+(i===0?' disabled':'')+'>Top</button><button class="b mini" data-act="up" title="Move up"'+(i===0?' disabled':'')+'>&#9650;</button><button class="b mini" data-act="down" title="Move down"'+(i===D.groups.length-1?' disabled':'')+'>&#9660;</button>'+
             '<span class="t">'+esc(x.name)+'</span><span class="c">'+(byGroup[x.id]||[]).length+'</span>'+
             '<button class="b'+(x.sports?' on':'')+'" data-act="sports">'+(x.sports?'Sports on':'Sports off')+'</button>'+
             '<button class="b'+(x.hidden?' on':'')+'" data-act="ghide">'+(x.hidden?'Hidden':'Hide')+'</button><span class="open">&#8250;</span></div>';
      });
      if (!D.groups.length) h += '<div class="empty">No groups yet. Is a service set up on the TV?</div>';
      h += '<div class="help">Tap a group to see its channels. Top sends a group to the top of the list, the arrows nudge it one place, or drag it on a computer. Hide takes a group off the TV everywhere. Sports on lets the Sports hub pick game channels from it.</div>';
    } else if (view === "groups") {
      var gx = group(sel) || {name:""}; var list = byGroup[sel] || [];
      h += '<div class="bar"><button class="back" data-act="back">&#8249; Groups</button><h2>'+esc(gx.name)+'</h2><span class="k">'+list.length+'</span></div>';
      h += list.map(chanRow).join("") || '<div class="empty">No channels in this group.</div>';
      h += '<div class="help">Favourite puts the channel in your Favourites on the TV. Hide takes it out of the guide.</div>';
    } else if (view === "favs") {
      var favs = D.channels.filter(function(c){ return c.fav });
      h += '<div class="bar"><h2>Favourites</h2><span class="k">'+favs.length+'</span></div>';
      h += favs.map(chanRow).join("") || '<div class="empty">Nothing yet. Open a group, or Search, and press Favourite on a channel.</div>';
    } else {
      h += '<div class="bar"><input id="q" placeholder="Search every channel" value="'+esc(q)+'" autofocus></div>';
      var hits = q ? D.channels.filter(function(c){ return c.name.toLowerCase().indexOf(q) >= 0 }).slice(0, 200) : [];
      h += hits.map(chanRow).join("") || '<div class="empty">'+(q ? "Nothing on." : "Type a channel name.")+'</div>';
    }
    root.innerHTML = h;
    var qi = document.getElementById("q"); if (qi) { qi.focus(); qi.setSelectionRange(qi.value.length, qi.value.length); qi.addEventListener("input", function(e){ q = e.target.value.trim().toLowerCase(); render() }) }
  }
  document.querySelector("nav").addEventListener("click", function(e){ var b = e.target.closest("button[data-v]"); if (!b) return; view = b.dataset.v; sel = null; render() });
  root.addEventListener("click", function(e){
    var b = e.target.closest("button[data-act]"); var r = e.target.closest(".row");
    if (b) {
      var act = b.dataset.act;
      if (act === "back") { sel = null; render(); return }
      if (!r) return;
      if (act === "fav") { var c = chan(r.dataset.c); op({op:"favourite", id:c.id, on: !c.fav}, c.fav ? "Removed from favourites" : "Added to favourites") }
      else if (act === "chide") { var c2 = chan(r.dataset.c); op({op:"channelHidden", id:c2.id, hidden: !c2.hid}, c2.hid ? "Shown again" : "Hidden") }
      else if (act === "ghide") { var g1 = group(r.dataset.g); op({op:"groupHidden", id:g1.id, hidden: !g1.hidden}, g1.hidden ? "Group shown" : "Group hidden") }
      else if (act === "sports") { var g2 = group(r.dataset.g); op({op:"sportsGroup", id:g2.id, off: !!g2.sports}, g2.sports ? "Sports hub skips this group" : "Sports hub may pick from this group") }
      else if (act === "up" || act === "down" || act === "top") { var ids = D.groups.map(function(x){ return x.id }); var i = ids.indexOf(r.dataset.g), j = act === "top" ? 0 : act === "up" ? i - 1 : i + 1; if (j < 0 || j >= ids.length || j === i) return; ids.splice(i, 1); ids.splice(j, 0, r.dataset.g); reorder(ids); if (act === "top") window.scrollTo(0, 0) }
      e.stopPropagation(); return;
    }
    if (r && r.dataset.g !== undefined) { sel = r.dataset.g; render(); window.scrollTo(0, 0) }
  });
  function reorder(ids){ var map = {}; D.groups.forEach(function(x){ map[x.id] = x }); D.groups = ids.map(function(i){ return map[i] }); render(); op({op:"groupOrder", ids: ids}, "Order saved") }
  var dragG = null;
  root.addEventListener("dragstart", function(e){ var r = e.target.closest(".row"); if (!r || r.dataset.g === undefined) return; dragG = r.dataset.g; e.dataTransfer.effectAllowed = "move"; try { e.dataTransfer.setData("text/plain", "x") } catch (x) {} });
  root.addEventListener("dragover", function(e){ var r = e.target.closest(".row"); if (dragG && r && r.dataset.g !== undefined) { e.preventDefault(); document.querySelectorAll(".drop").forEach(function(el){ el.classList.remove("drop") }); r.classList.add("drop") } });
  root.addEventListener("drop", function(e){ var r = e.target.closest(".row"); if (!dragG || !r || r.dataset.g === undefined || r.dataset.g === dragG) return; e.preventDefault(); var ids = D.groups.map(function(x){ return x.id }); var from = ids.indexOf(dragG), to = ids.indexOf(r.dataset.g); ids.splice(from, 1); ids.splice(to, 0, dragG); reorder(ids) });
  root.addEventListener("dragend", function(){ document.querySelectorAll(".drop").forEach(function(el){ el.classList.remove("drop") }); dragG = null });
  load(); setInterval(function(){ if (view !== "search") load() }, 20000);
})();
</script></body></html>"""
}
