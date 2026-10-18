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
  :root{color-scheme:dark;--bg:#0b0b0c;--ink:#ebe9e4;--soft:#b9b6af;--mute:#8d8a84;--dim:#5c5a55;--hair:#1f1f22;--lift:#141416;--amber:#e8a53a}
  *{box-sizing:border-box}
  body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.4 -apple-system,system-ui,Segoe UI,Roboto,sans-serif}
  header{display:flex;align-items:baseline;gap:18px;padding:18px 24px 12px;border-bottom:1px solid var(--hair)}
  h1{font-size:26px;line-height:1;margin:0;text-transform:uppercase;letter-spacing:.02em}
  .k{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;letter-spacing:.18em;text-transform:uppercase;color:var(--mute)}
  #msg{margin-left:auto;color:var(--mute);font-size:13px;min-width:120px;text-align:right}
  #msg.on{color:var(--amber)}
  main{display:grid;grid-template-columns:300px 1fr 320px;height:calc(100vh - 61px)}
  section{display:flex;flex-direction:column;min-height:0;border-right:1px solid var(--hair)}
  section:last-child{border-right:0}
  .head{padding:12px 16px 8px;display:flex;align-items:center;gap:10px}
  .head .k{flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .list{overflow:auto;flex:1;padding-bottom:40px}
  .row{display:flex;align-items:center;gap:10px;padding:0 16px;height:44px;border-bottom:1px solid var(--hair);cursor:default;user-select:none}
  .row:hover{background:var(--lift)}
  .row.sel{background:var(--lift);box-shadow:inset 2px 0 0 var(--ink)}
  .row.drag{opacity:.35}
  .row.over{box-shadow:inset 0 2px 0 var(--amber)}
  .row .n{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;color:var(--mute);width:46px;flex:none}
  .row .t{flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .row.hid .t{color:var(--dim);text-decoration:line-through}
  .row .c{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;color:var(--dim)}
  .grip{color:var(--dim);cursor:grab;font-size:16px;width:14px;flex:none}
  .ic{width:30px;height:30px;border:1px solid transparent;background:none;color:var(--dim);font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;cursor:pointer;flex:none;display:flex;align-items:center;justify-content:center;border-radius:0}
  .ic:hover{border-color:#3a3a3e;color:var(--ink)}
  .ic.on{color:var(--ink)}
  .ic.star.on{color:var(--amber)}
  input{background:none;border:0;border-bottom:1px solid #2e2e32;color:var(--ink);font:inherit;padding:8px 0;outline:none;border-radius:0;width:100%}
  input:focus{border-bottom-color:var(--ink)}
  button.pri{height:34px;padding:0 14px;border:1px solid #3a3a3e;background:none;color:var(--ink);font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;letter-spacing:.14em;text-transform:uppercase;border-radius:0;cursor:pointer}
  button.pri:hover{border-color:var(--ink)}
  .drop{outline:1px dashed var(--amber);outline-offset:-6px}
  .empty{padding:24px 16px;color:var(--mute)}
  .help{padding:10px 16px;color:var(--dim);font-size:12px;border-top:1px solid var(--hair)}
  @media (max-width:900px){main{grid-template-columns:1fr;height:auto}section{border-right:0;border-bottom:1px solid var(--hair);max-height:60vh}}
</style></head><body>
<header><span class="k">SloopyTV</span><h1>Lineup</h1><span class="k" id="prov"></span><span id="msg"></span></header>
<main>
  <section id="gs"><div class="head"><span class="k">Groups</span><span class="k" id="gcount"></span></div><div class="list" id="groups"></div>
    <div class="help">Drag to reorder. Eye hides the group everywhere. S lets the Sports hub pick channels from it.</div></section>
  <section id="cs"><div class="head"><span class="k" id="ctitle">Channels</span><input id="q" placeholder="Search every channel" style="max-width:300px;flex:1"></div><div class="list" id="chans"></div>
    <div class="help">Star adds to favourites, or drag a channel onto Favourites. Eye hides the channel.</div></section>
  <section id="fs"><div class="head"><span class="k">Favourites</span><span class="k" id="fcount"></span></div><div class="list" id="favs"></div>
    <div class="help">Drop channels here. Click a star to remove.</div></section>
</main>
<script>
(function(){
  var tok = location.hash.replace(/^#/, ""); if (history.replaceState) history.replaceState(null, "", location.pathname);
  var D = {groups:[], channels:[]}, sel = null, q = "";
  var msg = document.getElementById("msg");
  function say(t, on){ msg.textContent = t; msg.className = on ? "on" : ""; }
  function api(m, p, b){ return fetch(p, {method:m, headers:{"x-token":tok,"content-type":"application/json"}, body:b?JSON.stringify(b):undefined}).then(function(r){ return r.json() }) }
  function esc(s){ return String(s).replace(/[&<>"]/g, function(c){ return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c] }) }
  function op(body, label){ say("Saving…"); return api("POST","/hub/op",body).then(function(j){ if(!j.ok){ say(j.message||"The TV said no", true); return } say(label||"Saved"); setTimeout(load, 400) }).catch(function(){ say("Couldn't reach the TV. Same Wi-Fi? App open?", true) }) }
  function load(){ return api("GET","/hub/data").then(function(j){ D = j; if (sel === null && D.groups.length) sel = D.groups[0].id; render() }).catch(function(){ say("Scan the code on the TV again.", true) }) }
  var byGroup = {};
  function render(){
    byGroup = {}; D.channels.forEach(function(c){ (byGroup[c.g] = byGroup[c.g] || []).push(c) });
    document.getElementById("prov").textContent = D.provider || "";
    var g = document.getElementById("groups"), h = "";
    D.groups.forEach(function(x){
      h += '<div class="row'+(x.id===sel?' sel':'')+(x.hidden?' hid':'')+'" draggable="true" data-g="'+esc(x.id)+'"><span class="grip">⋮</span><span class="t">'+esc(x.name)+'</span><span class="c">'+(byGroup[x.id]||[]).length+'</span>'+
           '<button class="ic'+(x.sports?' on':'')+'" data-act="sports" title="Sports hub may pick from this group">S</button><button class="ic'+(x.hidden?'':' on')+'" data-act="ghide" title="'+(x.hidden?'Hidden':'Shown')+'">'+(x.hidden?'&#9711;':'&#9679;')+'</button></div>';
    });
    g.innerHTML = h || '<div class="empty">No groups yet. Is a service set up on the TV?</div>';
    document.getElementById("gcount").textContent = D.groups.length ? (D.groups.length - D.groups.filter(function(x){return x.hidden}).length) + " of " + D.groups.length : "";
    var list = q ? D.channels.filter(function(c){ return c.name.toLowerCase().indexOf(q) >= 0 }).slice(0, 300) : (byGroup[sel] || []);
    var gname = (D.groups.filter(function(x){ return x.id === sel })[0] || {}).name || "";
    document.getElementById("ctitle").textContent = q ? "Search · " + list.length : (gname || "Channels") + " · " + list.length;
    document.getElementById("chans").innerHTML = list.map(row).join("") || '<div class="empty">'+(q?"Nothing on.":"No channels in this group.")+'</div>';
    var favs = D.channels.filter(function(c){ return c.fav });
    document.getElementById("fcount").textContent = favs.length ? String(favs.length) : "";
    document.getElementById("favs").innerHTML = favs.map(row).join("") || '<div class="empty">Nothing yet. Drag channels here, or press a star.</div>';
  }
  function row(c){ return '<div class="row'+(c.hid?' hid':'')+'" draggable="true" data-c="'+c.id+'"><span class="n">'+c.n+'</span><span class="t">'+esc(c.name)+'</span>'+
    '<button class="ic star'+(c.fav?' on':'')+'" data-act="fav" title="Favourite">&#9733;</button><button class="ic'+(c.hid?'':' on')+'" data-act="chide" title="'+(c.hid?'Hidden':'Shown')+'">'+(c.hid?'&#9711;':'&#9679;')+'</button></div>' }
  document.addEventListener("click", function(e){
    var b = e.target.closest("button[data-act]"); var r = e.target.closest(".row");
    if (b && r) {
      var act = b.dataset.act;
      if (act === "fav") { var c = find(r.dataset.c); op({op:"favourite", id:c.id, on: !c.fav}, c.fav ? "Removed from favourites" : "Added to favourites") }
      else if (act === "chide") { var c2 = find(r.dataset.c); op({op:"channelHidden", id:c2.id, hidden: !c2.hid}, c2.hid ? "Shown" : "Hidden") }
      else if (act === "ghide") { var gx = D.groups.filter(function(x){ return x.id === r.dataset.g })[0]; op({op:"groupHidden", id:gx.id, hidden: !gx.hidden}, gx.hidden ? "Group shown" : "Group hidden") }
      else if (act === "sports") { var gy = D.groups.filter(function(x){ return x.id === r.dataset.g })[0]; op({op:"sportsGroup", id:gy.id, off: !!gy.sports}, gy.sports ? "Sports hub skips this group" : "Sports hub may pick from this group") }
      e.stopPropagation(); return;
    }
    if (r && r.dataset.g !== undefined) { sel = r.dataset.g; q = ""; document.getElementById("q").value = ""; render() }
  });
  function find(id){ return D.channels.filter(function(c){ return String(c.id) === String(id) })[0] }
  document.getElementById("q").addEventListener("input", function(e){ q = e.target.value.trim().toLowerCase(); render() });
  // Drag: a group row onto another reorders; a channel row onto Favourites adds it.
  var dragG = null, dragC = null;
  document.addEventListener("dragstart", function(e){ var r = e.target.closest(".row"); if (!r) return; if (r.dataset.g !== undefined) { dragG = r.dataset.g; r.classList.add("drag") } else { dragC = r.dataset.c } e.dataTransfer.effectAllowed = "move"; try { e.dataTransfer.setData("text/plain", "x") } catch (x) {} });
  document.addEventListener("dragend", function(){ document.querySelectorAll(".drag,.over,.drop").forEach(function(el){ el.classList.remove("drag","over","drop") }); dragG = null; dragC = null });
  document.addEventListener("dragover", function(e){
    var r = e.target.closest(".row"); var fs = e.target.closest("#fs");
    if (dragG && r && r.dataset.g !== undefined) { e.preventDefault(); document.querySelectorAll(".over").forEach(function(el){ el.classList.remove("over") }); r.classList.add("over") }
    else if (dragC && fs) { e.preventDefault(); fs.classList.add("drop") }
  });
  document.addEventListener("dragleave", function(e){ var fs = e.target.closest("#fs"); if (fs && !fs.contains(e.relatedTarget)) fs.classList.remove("drop") });
  document.addEventListener("drop", function(e){
    var r = e.target.closest(".row"); var fs = e.target.closest("#fs");
    if (dragG && r && r.dataset.g !== undefined && r.dataset.g !== dragG) {
      e.preventDefault();
      var ids = D.groups.map(function(x){ return x.id }); var from = ids.indexOf(dragG), to = ids.indexOf(r.dataset.g);
      ids.splice(from, 1); ids.splice(to, 0, dragG);
      var map = {}; D.groups.forEach(function(x){ map[x.id] = x }); D.groups = ids.map(function(i){ return map[i] }); render();
      op({op:"groupOrder", ids: ids}, "Order saved");
    } else if (dragC && fs) { e.preventDefault(); var c = find(dragC); if (c && !c.fav) op({op:"favourite", id:c.id, on:true}, "Added to favourites") }
  });
  load(); setInterval(load, 15000);
})();
</script></body></html>"""
}
