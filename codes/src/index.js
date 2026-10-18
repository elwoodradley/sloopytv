/**
 * SloopyTV code server. A Cloudflare Worker with one KV namespace.
 *
 * The operator pastes a customer's login on the admin page and gets a six-digit code.
 * The login is encrypted with a key derived from the code (PBKDF2, 100k rounds) before it
 * is stored, so the stored rows are useless without the codes. The customer types the code
 * into SloopyTV; the app fetches the encrypted row and decrypts it on the TV.
 *
 * Codes are single use and expire 24 hours after they are made. Redeemed codes stay in the
 * admin list for 7 days as a record, with the encrypted login already deleted.
 *
 * Bindings: KV namespace CODES. Secret ADMIN_PASSWORD.
 */

const CODE_TTL = 24 * 3600;        // waiting codes
const USED_TTL = 7 * 24 * 3600;    // record kept after redemption
const RATE_WINDOW = 600;           // seconds
const RATE_MAX = 20;               // redeem attempts per IP per window

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    try {
      if (request.method === "GET" && (path === "/" || path === "/admin")) return html(ADMIN_PAGE);
      if (request.method === "POST" && path === "/redeem") return redeem(request, env);
      if (request.method === "POST" && path === "/sync/put") return syncPut(request, env);
      if (request.method === "GET" && path === "/sync/get") return syncGet(request, env, url);
      if (request.method === "GET" && path === "/config") return json({ ok: true, ...publicConfig((await env.CODES.get("config", "json")) || {}) });
      if (request.method === "POST" && path === "/report") return report(request, env);
      if (request.method === "POST" && path === "/lineup") return lineupPut(request, env);
      if (request.method === "GET" && path === "/poster") return poster(request, env, url);
      if (request.method === "GET" && path === "/trending") return trending(request, env);
      if (request.method === "GET" && path === "/toad/status") return toadStatus(env, url);
      if (request.method === "POST" && path === "/toad/ask") return toadAsk(request, env);
      if (request.method === "POST" && path === "/toad/tags") return toadTags(request, env);
      if (request.method === "POST" && path === "/friends/me") return friendMe(request, env);
      if (request.method === "GET" && path === "/friends/list") return friendList(env, url);
      if (request.method === "POST" && path === "/friends/send") return friendSend(request, env);
      if (request.method === "GET" && path === "/friends/inbox") return friendInbox(env, url);
      if (path.startsWith("/admin/")) {
        if (!(await authed(request, env))) return json({ ok: false, error: "Wrong password" }, 401);
        if (request.method === "POST" && path === "/admin/mint") return mint(request, env);
        if (request.method === "POST" && path === "/admin/config") return setConfig(request, env);
        if (request.method === "GET" && path === "/admin/config") return json({ ok: true, ...adminConfig((await env.CODES.get("config", "json")) || {}) });
        if (request.method === "GET" && path === "/admin/reports") return listReports(env);
        if (request.method === "GET" && path === "/admin/lineups") return lineupList(env);
        if (request.method === "GET" && path === "/admin/lineup") return lineupGet(env, url);
        if (request.method === "GET" && path === "/admin/toad") return toadAdmin(env);
        if (request.method === "DELETE" && path.startsWith("/admin/report/")) { await env.CODES.delete("r:" + path.slice("/admin/report/".length)); return json({ ok: true }); }
        if (request.method === "POST" && path === "/admin/report-test") return reportTest(env);
        if (request.method === "GET" && path === "/admin/list") return list(env);
        if (request.method === "DELETE" && path.startsWith("/admin/code/")) return del(path.slice("/admin/code/".length), env);
      }
      return json({ ok: false, error: "Not found" }, 404);
    } catch (e) {
      return json({ ok: false, error: "Server error" }, 500);
    }
  },
};

// ---- helpers --------------------------------------------------------------------------------

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
const html = (s) => new Response(s, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });

function timingSafeEqual(a, b) {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

async function authed(request, env) {
  const h = request.headers.get("authorization") || "";
  const token = h.startsWith("Bearer ") ? h.slice(7) : "";
  return !!env.ADMIN_PASSWORD && timingSafeEqual(token, env.ADMIN_PASSWORD);
}

const b64 = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf)));
const randomCode = () => String(crypto.getRandomValues(new Uint32Array(1))[0] % 1_000_000).padStart(6, "0");

async function deriveKey(code, salt) {
  const base = await crypto.subtle.importKey("raw", new TextEncoder().encode(code), "PBKDF2", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", salt, iterations: 100_000, hash: "SHA-256" },
    base, { name: "AES-GCM", length: 256 }, false, ["encrypt"]
  );
}

// ---- admin ----------------------------------------------------------------------------------

async function mint(request, env) {
  const body = await request.json().catch(() => null);
  const url = (body?.url || "").trim();
  const user = (body?.user || "").trim();
  const pass = (body?.pass || "").trim();
  const name = (body?.name || "").trim().slice(0, 60);
  const note = (body?.note || "").trim().slice(0, 80);
  if (!url || !/^https?:\/\//i.test(url) && !/^[a-z0-9.-]+(:\d+)?/i.test(url)) return json({ ok: false, error: "Server address looks wrong" }, 400);

  // Pick a code nobody is holding.
  let code;
  for (let i = 0; i < 20; i++) {
    const c = randomCode();
    if (!(await env.CODES.get("c:" + c))) { code = c; break; }
  }
  if (!code) return json({ ok: false, error: "Could not find a free code, try again" }, 500);

  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveKey(code, salt);
  const plain = new TextEncoder().encode(JSON.stringify({ url, user, pass, name }));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, plain);

  const record = { status: "waiting", createdAt: Date.now(), expiresAt: Date.now() + CODE_TTL * 1000, note, salt: b64(salt), iv: b64(iv), ct: b64(ct) };
  await env.CODES.put("c:" + code, JSON.stringify(record), { expirationTtl: CODE_TTL });
  return json({ ok: true, code, expiresAt: record.expiresAt });
}

async function list(env) {
  const out = [];
  let cursor;
  do {
    const page = await env.CODES.list({ prefix: "c:", cursor });
    for (const k of page.keys) {
      const r = await env.CODES.get(k.name, "json");
      if (r) out.push({ code: k.name.slice(2), status: r.status, note: r.note, createdAt: r.createdAt, expiresAt: r.expiresAt, usedAt: r.usedAt || null });
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  out.sort((a, b) => b.createdAt - a.createdAt);
  return json({ ok: true, codes: out });
}

async function del(code, env) {
  if (!/^\d{6}$/.test(code)) return json({ ok: false, error: "Bad code" }, 400);
  await env.CODES.delete("c:" + code);
  return json({ ok: true });
}

// ---- redeem (called by the app) -------------------------------------------------------------

async function redeem(request, env) {
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  if (memLimited("rl:" + ip, RATE_MAX, RATE_WINDOW)) return json({ ok: false, error: "Too many tries. Wait ten minutes." }, 429);

  const body = await request.json().catch(() => null);
  const code = String(body?.code || "").trim();
  if (!/^\d{6}$/.test(code)) return json({ ok: false, error: "A code is six digits" }, 400);

  const r = await env.CODES.get("c:" + code, "json");
  if (!r || r.status !== "waiting") return json({ ok: false, error: "That code isn't valid any more. Ask for a new one." }, 404);
  if (r.expiresAt && Date.now() > r.expiresAt) {
    await env.CODES.delete("c:" + code);
    return json({ ok: false, error: "That code has expired. Ask for a new one." }, 404);
  }

  // Single use: the encrypted login goes, a record stays for the admin list.
  const used = { status: "used", createdAt: r.createdAt, note: r.note, usedAt: Date.now() };
  await env.CODES.put("c:" + code, JSON.stringify(used), { expirationTtl: USED_TTL });
  return json({ ok: true, salt: r.salt, iv: r.iv, ct: r.ct, kdf: "pbkdf2-sha256-100000", cipher: "aes-256-gcm" });
}

// ---- sync (transfer between sticks) --------------------------------------------------------
// The app encrypts its settings with a key derived from a code it never sends; the server
// only ever holds the scrambled blob under an id derived from that code. Kept until replaced.

const SYNC_MAX_CT = 1_200_000;    // base64 chars, ~900 KB
const SYNC_RATE_MAX = 200;        // requests per IP per window
const SYNC_RATE_WINDOW = 600;

// Counts live in the isolate's memory, never in KV: a write per request was most of the free
// plan's daily write budget (one mark per call, ~550 calls a day). Memory resets when the
// isolate does, which only ever makes the limit more forgiving.
const rateMem = new Map();
function memLimited(key, max, windowSec) {
  const now = Date.now();
  const e = rateMem.get(key);
  if (!e || now - e.at > windowSec * 1000) { rateMem.set(key, { at: now, n: 1 }); if (rateMem.size > 5000) rateMem.clear(); return false; }
  e.n++;
  return e.n > max;
}
async function syncLimited(request, env) {
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  return memLimited("srl:" + ip, SYNC_RATE_MAX, SYNC_RATE_WINDOW);
}

async function syncPut(request, env) {
  if (await syncLimited(request, env)) return json({ ok: false, error: "Too many requests. Wait ten minutes." }, 429);
  const body = await request.json().catch(() => null);
  const id = String(body?.id || "");
  if (!/^[0-9a-f]{24}$/.test(id)) return json({ ok: false, error: "Bad id" }, 400);
  const { salt, iv, ct } = body || {};
  if (typeof salt !== "string" || typeof iv !== "string" || typeof ct !== "string" || ct.length > SYNC_MAX_CT || salt.length > 64 || iv.length > 64) return json({ ok: false, error: "Bad backup" }, 400);
  await env.CODES.put("s:" + id, JSON.stringify({ salt, iv, ct, at: Date.now() }));
  return json({ ok: true, at: Date.now() });
}

async function syncGet(request, env, url) {
  if (await syncLimited(request, env)) return json({ ok: false, error: "Too many requests. Wait ten minutes." }, 429);
  const id = url.searchParams.get("id") || "";
  if (!/^[0-9a-f]{24}$/.test(id)) return json({ ok: false, error: "Bad id" }, 400);
  const r = await env.CODES.get("s:" + id, "json");
  if (!r) return json({ ok: false, error: "No backup with that code" }, 404);
  return json({ ok: true, salt: r.salt, iv: r.iv, ct: r.ct, at: r.at });
}

// What every stick may read. Never the bot token or webhook.
function publicConfig(c) {
  return {
    gumroadProduct: c.gumroadProduct || "", buyUrl: c.buyUrl || "", price: c.price || "",
    friendsProduct: c.friendsProduct || "", friendsBuyUrl: c.friendsBuyUrl || "", friendsPrice: c.friendsPrice || "",
    operatorName: c.operatorName || "",
    announcementId: c.announcementId || "", announcementText: c.announcementText || "",
    reportsEnabled: !!c.reportsEnabled,
  };
}
function adminConfig(c) {
  return {
    ...publicConfig(c), telegramToken: c.telegramToken ? "set" : "", telegramChat: c.telegramChat || "", discordWebhook: c.discordWebhook ? "set" : "",
    toadEnabled: !!c.toadEnabled, toadSticks: c.toadSticks || "", toadDailyCap: c.toadDailyCap || TOAD_DEFAULT_CAP, toadModel: c.toadModel || TOAD_DEFAULT_MODEL,
  };
}

async function setConfig(request, env) {
  const body = await request.json().catch(() => null);
  const prev = (await env.CODES.get("config", "json")) || {};
  const cfg = {
    ...prev,
    gumroadProduct: String(body?.gumroadProduct ?? prev.gumroadProduct ?? "").trim().slice(0, 120),
    buyUrl: String(body?.buyUrl ?? prev.buyUrl ?? "").trim().slice(0, 300),
    price: String(body?.price ?? prev.price ?? "").trim().slice(0, 40),
    friendsProduct: String(body?.friendsProduct ?? prev.friendsProduct ?? "").trim().slice(0, 120),
    friendsBuyUrl: String(body?.friendsBuyUrl ?? prev.friendsBuyUrl ?? "").trim().slice(0, 300),
    friendsPrice: String(body?.friendsPrice ?? prev.friendsPrice ?? "").trim().slice(0, 40),
    operatorName: String(body?.operatorName ?? prev.operatorName ?? "").trim().slice(0, 60),
    reportsEnabled: body?.reportsEnabled === undefined ? !!prev.reportsEnabled : !!body.reportsEnabled,
    toadEnabled: body?.toadEnabled === undefined ? !!prev.toadEnabled : !!body.toadEnabled,
    toadSticks: typeof body?.toadSticks === "string" ? body.toadSticks.toUpperCase().replace(/[^A-Z2-9\s,]/g, "").split(/[\s,]+/).filter((x) => FRIEND_RE.test(x)).join(" ") : (prev.toadSticks || ""),
    toadDailyCap: body?.toadDailyCap === undefined ? (prev.toadDailyCap || TOAD_DEFAULT_CAP) : Math.max(1, Math.min(1000, parseInt(body.toadDailyCap, 10) || TOAD_DEFAULT_CAP)),
    toadModel: TOAD_MODELS[body?.toadModel] ? body.toadModel : (TOAD_MODELS[prev.toadModel] ? prev.toadModel : TOAD_DEFAULT_MODEL),
  };
  // Secrets only change when a real value is sent; "set" means leave it alone, "" clears it.
  if (typeof body?.telegramToken === "string" && body.telegramToken !== "set") cfg.telegramToken = body.telegramToken.trim().slice(0, 120);
  if (typeof body?.discordWebhook === "string" && body.discordWebhook !== "set") cfg.discordWebhook = body.discordWebhook.trim().slice(0, 300);
  if (cfg.discordWebhook && !/^https:\/\/(discord\.com|discordapp\.com)\/api\/webhooks\//i.test(cfg.discordWebhook)) return json({ ok: false, error: "That is not a Discord webhook address" }, 400);
  if (typeof body?.announcementText === "string") {
    const t = body.announcementText.trim().slice(0, 300);
    cfg.announcementText = t;
    cfg.announcementId = t ? String(Date.now()) : "";
  }
  if (cfg.buyUrl && !/^https:\/\//i.test(cfg.buyUrl)) return json({ ok: false, error: "Buy link must start with https://" }, 400);
  if (cfg.friendsBuyUrl && !/^https:\/\//i.test(cfg.friendsBuyUrl)) return json({ ok: false, error: "Friends buy link must start with https://" }, 400);
  await env.CODES.put("config", JSON.stringify(cfg));
  return json({ ok: true, ...adminConfig(cfg) });
}

// ---- dead-channel reports (off until the operator turns them on) ---------------------------

const REPORT_TTL = 30 * 24 * 3600;

async function report(request, env) {
  const cfg = (await env.CODES.get("config", "json")) || {};
  if (!cfg.reportsEnabled) return json({ ok: false, error: "Reports are switched off" }, 403);
  if (await syncLimited(request, env)) return json({ ok: false, error: "Too many requests. Wait ten minutes." }, 429);
  const b = await request.json().catch(() => null);
  if (!b) return json({ ok: false, error: "Bad report" }, 400);
  const r = {
    at: Date.now(),
    channel: String(b.channel || "").slice(0, 80), number: String(b.number || "").slice(0, 8), group: String(b.group || "").slice(0, 60),
    error: String(b.error || "").slice(0, 160), device: String(b.device || "").slice(0, 60), version: String(b.version || "").slice(0, 20),
    nick: String(b.nick || "").slice(0, 30), note: String(b.note || "").slice(0, 200),
  };
  const id = String(r.at) + "-" + Math.random().toString(36).slice(2, 6);
  await env.CODES.put("r:" + id, JSON.stringify(r), { expirationTtl: REPORT_TTL });
  const text = `SloopyTV report: ${r.number} ${r.channel}${r.group ? " (" + r.group + ")" : ""}\n${r.error || "no error text"}\n${r.nick || "a viewer"} · ${r.device} · v${r.version}${r.note ? "\n" + r.note : ""}`;
  await notify(cfg, text, env);
  return json({ ok: true });
}

async function notify(cfg, text, env) {
  const jobs = [];
  if (cfg.telegramToken && cfg.telegramChat) {
    jobs.push(fetch(`https://api.telegram.org/bot${cfg.telegramToken}/sendMessage`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ chat_id: cfg.telegramChat, text }) }).catch(() => null));
  }
  if (cfg.discordWebhook) {
    jobs.push(fetch(cfg.discordWebhook, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ content: text.slice(0, 1900) }) }).catch(() => null));
  }
  await Promise.all(jobs);
}

async function reportTest(env) {
  const cfg = (await env.CODES.get("config", "json")) || {};
  if (!cfg.telegramToken && !cfg.discordWebhook) return json({ ok: false, error: "Add a Telegram bot token or a Discord webhook first" }, 400);
  // Telegram: the bot can only message a chat that has written to it; find it from the bot's inbox once.
  if (cfg.telegramToken && !cfg.telegramChat) {
    const r = await fetch(`https://api.telegram.org/bot${cfg.telegramToken}/getUpdates`).then((x) => x.json()).catch(() => null);
    const chat = (r?.result || []).map((u) => u.message?.chat?.id).filter(Boolean).pop();
    if (!chat) return json({ ok: false, error: "Open the bot in Telegram and send it any message, then press Test again" }, 400);
    cfg.telegramChat = String(chat);
    await env.CODES.put("config", JSON.stringify(cfg));
  }
  await notify(cfg, "SloopyTV: test ping. Dead-channel reports will arrive here.", env);
  return json({ ok: true, telegramChat: cfg.telegramChat || "" });
}

async function listReports(env) {
  const out = [];
  let cursor;
  do {
    const page = await env.CODES.list({ prefix: "r:", cursor });
    for (const k of page.keys) { const r = await env.CODES.get(k.name, "json"); if (r) out.push({ id: k.name.slice(2), ...r }); }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  out.sort((a, b) => b.at - a.at);
  return json({ ok: true, reports: out.slice(0, 200) });
}

// ---- posters (one TMDB key for every stick, cached) ------------------------------------------

async function poster(request, env, url) {
  if (!env.TMDB_TOKEN) return json({ ok: true, url: null });
  if (await syncLimited(request, env)) return json({ ok: false, error: "Too many requests" }, 429);
  const title = (url.searchParams.get("title") || "").trim().slice(0, 120);
  const year = (url.searchParams.get("year") || "").trim().slice(0, 4);
  const kind = url.searchParams.get("kind") === "movie" ? "movie" : "tv";
  if (!title) return json({ ok: true, url: null });
  const key = "p:" + kind + ":" + title.toLowerCase() + ":" + year;
  const cached = await env.CODES.get(key);
  if (cached !== null) return json({ ok: true, url: cached || null });
  const q = new URLSearchParams({ query: title, include_adult: "false", language: "en-US" });
  if (year) q.set(kind === "movie" ? "year" : "first_air_date_year", year);
  let found = "";
  try {
    const r = await fetch(`https://api.themoviedb.org/3/search/${kind}?` + q.toString(), { headers: { authorization: "Bearer " + env.TMDB_TOKEN, accept: "application/json" } });
    const j = await r.json();
    const hit = (j.results || []).find((x) => x.poster_path);
    if (hit) found = "https://image.tmdb.org/t/p/w342" + hit.poster_path;
  } catch (e) {}
  await env.CODES.put(key, found, { expirationTtl: 30 * 24 * 3600 }).catch(() => null);
  return json({ ok: true, url: found || null });
}

// What's popular today, from TMDB's trending lists; one fetch for everyone, kept six hours.
// Titles only: the TV matches them against its own guide and library, so nothing is shown
// that the viewer's service does not carry.
async function trending(request, env) {
  if (!env.TMDB_TOKEN) return json({ ok: true, tv: [], movies: [] });
  if (await syncLimited(request, env)) return json({ ok: false, error: "Too many requests" }, 429);
  const cached = await env.CODES.get("trending:v1");
  if (cached) return new Response(cached, { headers: { "content-type": "application/json" } });
  const pull = async (kind) => {
    try {
      const r = await fetch(`https://api.themoviedb.org/3/trending/${kind}/day?language=en-US`, { headers: { authorization: "Bearer " + env.TMDB_TOKEN, accept: "application/json" } });
      const j = await r.json();
      return (j.results || []).slice(0, 40).map((x) => ({
        title: String(x.name || x.title || "").slice(0, 120),
        year: String(x.first_air_date || x.release_date || "").slice(0, 4),
        poster: x.poster_path ? "https://image.tmdb.org/t/p/w342" + x.poster_path : null,
      })).filter((x) => x.title);
    } catch (e) { return []; }
  };
  const body = JSON.stringify({ ok: true, tv: await pull("tv"), movies: await pull("movie") });
  await env.CODES.put("trending:v1", body, { expirationTtl: 6 * 3600 }).catch(() => null);
  return new Response(body, { headers: { "content-type": "application/json" } });
}

// ---- the Toad: one Claude key for every allowed stick, with a daily cap and a spend line ------
// The TV never holds the key. A stick is known by its friend code; the operator lists the codes
// that may use the Toad (empty list = every stick, once it is switched on). Each stick gets a
// daily cap, and every call adds its estimated cost to this month's spend line.

const TOAD_DEFAULT_CAP = 50;
const TOAD_DEFAULT_MODEL = "claude-haiku-5-5";
// Price per million tokens, in and out, for the spend estimate.
const TOAD_MODELS = { "claude-haiku-5-5": [0.10, 0.50], "claude-sonnet-5-5": [2, 10], "claude-opus-5-5": [4, 20] };
const TOAD_TAGS = ["sports", "news", "kids", "movies", "series", "music", "adult", "foreign", "general"];

function toadCode(x) { return String(x || "").toUpperCase().replace(/[^A-Z2-9]/g, "").slice(0, 8); }
function dayKey() { return new Date().toISOString().slice(0, 10); }
function monthKey() { return new Date().toISOString().slice(0, 7); }

async function toadGate(env, code) {
  const cfg = (await env.CODES.get("config", "json")) || {};
  if (!env.ANTHROPIC_KEY || !cfg.toadEnabled) return { ok: false, enabled: false, allowed: false, left: 0, cfg };
  const sticks = (cfg.toadSticks || "").split(" ").filter(Boolean);
  const allowed = FRIEND_RE.test(code) && (sticks.length === 0 || sticks.includes(code));
  const cap = cfg.toadDailyCap || TOAD_DEFAULT_CAP;
  const used = parseInt((await env.CODES.get("toad:use:" + code + ":" + dayKey())) || "0", 10);
  return { ok: allowed && used < cap, enabled: true, allowed, left: Math.max(0, cap - used), used, cap, cfg };
}

async function toadStatus(env, url) {
  const g = await toadGate(env, toadCode(url.searchParams.get("id")));
  return json({ ok: true, enabled: g.enabled, allowed: g.allowed, left: g.left });
}

async function toadSpend(env, code, model, usage) {
  const price = TOAD_MODELS[model] || TOAD_MODELS[TOAD_DEFAULT_MODEL];
  const cost = ((usage?.input_tokens || 0) * price[0] + (usage?.output_tokens || 0) * price[1]) / 1e6;
  const mk = "toad:spend:" + monthKey();
  const cur = (await env.CODES.get(mk, "json")) || { usd: 0, calls: 0 };
  await env.CODES.put(mk, JSON.stringify({ usd: cur.usd + cost, calls: cur.calls + 1 }), { expirationTtl: 400 * 24 * 3600 }).catch(() => null);
  const uk = "toad:use:" + code + ":" + dayKey();
  const used = parseInt((await env.CODES.get(uk)) || "0", 10);
  await env.CODES.put(uk, String(used + 1), { expirationTtl: 2 * 24 * 3600 }).catch(() => null);
}

// One call to Claude. Returns the text and the usage, or throws with a short message.
async function claude(env, model, system, user, maxTokens) {
  const r = await fetch("https://api.anthropic.com/v1/messages", {
    method: "POST",
    headers: { "x-api-key": env.ANTHROPIC_KEY, "anthropic-version": "2023-06-01", "content-type": "application/json" },
    body: JSON.stringify({ model, max_tokens: maxTokens, system, messages: [{ role: "user", content: user }] }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j?.error?.message || ("Claude answered HTTP " + r.status));
  if (j.stop_reason === "refusal") throw new Error("The Toad would rather not answer that.");
  const text = (j.content || []).filter((b) => b.type === "text").map((b) => b.text).join("");
  return { text, usage: j.usage || {} };
}

function jsonFromText(t) {
  const m = String(t || "").replace(/^```(?:json)?\s*/i, "").replace(/```\s*$/, "");
  const a = m.indexOf("{"), b = m.lastIndexOf("}");
  if (a < 0 || b < a) return null;
  try { return JSON.parse(m.slice(a, b + 1)); } catch (e) { return null; }
}

const TOAD_ACTIONS = ["favourite_add", "favourite_remove", "hide_group", "show_group", "hide_channel", "show_channel", "tune", "remind", "record", "search", "nothing"];

const TOAD_SYSTEM = `You are the Toad, the helper inside SloopyTV, a TV app. The viewer typed or spoke one request. Turn it into actions the TV can run. Answer with JSON only, no prose around it:
{"say": "<one short plain sentence, what you are about to do or why you cannot>", "actions": [{"type": "<action>", "names": ["<channel or group names as the viewer said them>"], "title": "<programme or team, for remind/record>", "query": "<search text, for search>"}]}
Actions: favourite_add, favourite_remove (channels), hide_group, show_group (groups), hide_channel, show_channel (channels), tune (one channel), remind (title on a channel, or a team), record (title on a channel, or a team, or the channel that is on now), search (a question about what is on, or anything you cannot do), nothing.
Rules: Use the viewer's own channel names in "names", one entry per channel. For group actions use names from the group list given, and you may use several, for example every group that is foreign-language. "all foreign groups" means every group not in English for an American viewer. Never invent channels. If the request is unclear, use "nothing" and ask in "say". Keep "say" under 20 words. No markdown.`;

async function toadAsk(request, env) {
  if (await syncLimited(request, env)) return json({ ok: false, error: "Too many requests. Wait ten minutes." }, 429);
  const b = await request.json().catch(() => null);
  const code = toadCode(b?.id);
  const g = await toadGate(env, code);
  if (!g.enabled) return json({ ok: false, error: "The Toad is switched off" }, 403);
  if (!g.allowed) return json({ ok: false, error: "This stick is not on the Toad's list" }, 403);
  if (!g.ok) return json({ ok: false, error: "That is all the Toad can do today. Tomorrow it is fresh." }, 429);
  const text = String(b?.text || "").trim().slice(0, 300);
  if (!text) return json({ ok: false, error: "Say something first" }, 400);
  const groups = (Array.isArray(b?.groups) ? b.groups : []).slice(0, 400).map((x) => String(x).slice(0, 60));
  const favourites = (Array.isArray(b?.favourites) ? b.favourites : []).slice(0, 100).map((x) => String(x).slice(0, 60));
  const user = "Viewer's request: " + text
    + "\n\nGroups on this service: " + (groups.length ? groups.join(" | ") : "(none sent)")
    + "\n\nViewer's favourites now: " + (favourites.length ? favourites.join(" | ") : "(none)")
    + (b?.current ? "\n\nOn the TV now: " + String(b.current).slice(0, 80) : "")
    + "\n\nLocal time: " + String(b?.now || "").slice(0, 40);
  const model = g.cfg.toadModel || TOAD_DEFAULT_MODEL;
  try {
    const r = await claude(env, model, TOAD_SYSTEM, user, 600);
    await toadSpend(env, code, model, r.usage);
    const plan = jsonFromText(r.text);
    if (!plan) return json({ ok: false, error: "The Toad mumbled. Try again." }, 502);
    const actions = (Array.isArray(plan.actions) ? plan.actions : []).filter((a) => TOAD_ACTIONS.includes(a?.type)).slice(0, 12).map((a) => ({
      type: a.type,
      names: (Array.isArray(a.names) ? a.names : []).slice(0, 40).map((x) => String(x).slice(0, 60)),
      title: a.title ? String(a.title).slice(0, 80) : "",
      query: a.query ? String(a.query).slice(0, 120) : "",
    }));
    return json({ ok: true, say: String(plan.say || "").slice(0, 200), actions, left: g.left - 1 });
  } catch (e) {
    return json({ ok: false, error: String(e.message || e).slice(0, 160) }, 502);
  }
}

const TAGS_SYSTEM = `You tag channel-group names from an IPTV service. For each group name, pick exactly one tag from: ${TOAD_TAGS.join(", ")}. "foreign" means a non-English-language group for an American viewer (Spanish-language US groups count as foreign). "adult" is XXX or similar. "general" is anything else (entertainment, networks, local). Answer with JSON only: {"tags": {"<group name exactly as given>": "<tag>"}}. Every group given must appear.`;

async function toadTags(request, env) {
  if (await syncLimited(request, env)) return json({ ok: false, error: "Too many requests" }, 429);
  const b = await request.json().catch(() => null);
  const code = toadCode(b?.id);
  const g = await toadGate(env, code);
  if (!g.enabled || !g.allowed) return json({ ok: false, error: "The Toad is off for this stick" }, 403);
  const groups = Array.from(new Set((Array.isArray(b?.groups) ? b.groups : []).map((x) => String(x).trim().slice(0, 60)).filter(Boolean))).slice(0, 400);
  if (!groups.length) return json({ ok: true, tags: {} });
  // Same set of names = same answer, for every stick on that service, for a month.
  const digest = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(groups.slice().sort().join("\n"))))).map((x) => x.toString(16).padStart(2, "0")).join("");
  const key = "toad:tags:" + digest;
  const cached = await env.CODES.get(key, "json");
  if (cached) return json({ ok: true, tags: cached, cached: true });
  if (!g.ok) return json({ ok: false, error: "Daily cap reached" }, 429);
  const model = g.cfg.toadModel || TOAD_DEFAULT_MODEL;
  try {
    const r = await claude(env, model, TAGS_SYSTEM, groups.join("\n"), 4000);
    await toadSpend(env, code, model, r.usage);
    const got = jsonFromText(r.text)?.tags || {};
    const tags = {};
    for (const name of groups) { const t = String(got[name] || "").toLowerCase(); tags[name] = TOAD_TAGS.includes(t) ? t : "general"; }
    await env.CODES.put(key, JSON.stringify(tags), { expirationTtl: 30 * 24 * 3600 }).catch(() => null);
    return json({ ok: true, tags });
  } catch (e) {
    return json({ ok: false, error: String(e.message || e).slice(0, 160) }, 502);
  }
}

async function toadAdmin(env) {
  const m = (await env.CODES.get("toad:spend:" + monthKey(), "json")) || { usd: 0, calls: 0 };
  return json({ ok: true, month: monthKey(), usd: Math.round(m.usd * 10000) / 10000, calls: m.calls, keySet: !!env.ANTHROPIC_KEY });
}

// ---- friends: opt-in presence and send-to-TV --------------------------------------------------
// A friend code identifies a stick. Presence lives three minutes; a sent channel waits a day.

const FRIEND_RE = /^[A-Z2-9]{8}$/;

// Friends is the one feature that costs the operator money (presence writes), so it can sit
// behind its own Gumroad product (`friendsProduct` in config). A stick sends its key as `lic`;
// verified keys are remembered in isolate memory for a day, so Gumroad sees one call per stick
// per day, and KV sees none.
const licMem = new Map();
async function friendsOk(env, lic) {
  const cfg = (await env.CODES.get("config", "json")) || {};
  if (!cfg.friendsProduct) return true;
  lic = String(lic || "").trim(); if (!lic) return false;
  const now = Date.now(); const hit = licMem.get(lic);
  if (hit && now - hit.at < 86400e3) return hit.ok;
  let ok = false;
  try {
    const r = await fetch("https://api.gumroad.com/v2/licenses/verify", { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ product_id: cfg.friendsProduct, license_key: lic, increment_uses_count: "false" }) });
    const j = await r.json().catch(() => null);
    ok = !!(j && j.success && j.purchase && !j.purchase.refunded && !j.purchase.chargebacked);
  } catch (e) { return hit ? hit.ok : false; }
  if (licMem.size > 5000) licMem.clear();
  licMem.set(lic, { ok, at: now });
  return ok;
}
const LOCKED = () => json({ ok: false, locked: true, error: "Friends needs a license key" }, 402);

async function friendMe(request, env) {
  if (await syncLimited(request, env)) return json({ ok: false }, 429);
  const b = await request.json().catch(() => null);
  if (!(await friendsOk(env, b?.lic))) return LOCKED();
  const id = String(b?.id || "");
  if (!FRIEND_RE.test(id)) return json({ ok: false, error: "Bad code" }, 400);
  const nick = String(b?.nick || "").slice(0, 24), channel = String(b?.channel || "").slice(0, 60), at = Math.floor(Date.now() / 1000);
  // A stick reports every 45 s. The note (alive ten minutes) is rewritten when it is over four
  // minutes old, or when the channel changed and the last write is over ninety seconds old, so
  // a flipping stick costs at most forty writes an hour and a quiet one fifteen. KV allows a
  // thousand writes a day for everyone; the 50% warnings of 2026-10-16/17 were this and backups.
  const cur = await env.CODES.get("f:" + id, "json");
  const age = at - (cur?.at || 0);
  const changed = !cur || cur.channel !== channel || cur.nick !== nick;
  if (cur && age < 240 && !(changed && age >= 90)) return json({ ok: true });
  await env.CODES.put("f:" + id, JSON.stringify({ nick, channel, at }), { expirationTtl: 600 }).catch(() => null);
  return json({ ok: true });
}

async function friendList(env, url) {
  if (!(await friendsOk(env, url.searchParams.get("lic")))) return LOCKED();
  const ids = (url.searchParams.get("ids") || "").split(",").filter((x) => FRIEND_RE.test(x)).slice(0, 50);
  const out = [];
  for (const id of ids) { const r = await env.CODES.get("f:" + id, "json"); out.push({ id, nick: r?.nick || "", channel: r?.channel || "", at: r?.at || 0 }); }
  return json({ ok: true, friends: out });
}

async function friendSend(request, env) {
  if (await syncLimited(request, env)) return json({ ok: false }, 429);
  const b = await request.json().catch(() => null);
  if (!(await friendsOk(env, b?.lic))) return LOCKED();
  const to = String(b?.to || ""), from = String(b?.from || "");
  if (!FRIEND_RE.test(to) || !FRIEND_RE.test(from)) return json({ ok: false, error: "Bad code" }, 400);
  const msg = { from, nick: String(b?.nick || "").slice(0, 24), channel: String(b?.channel || "").slice(0, 60), key: String(b?.key || "").slice(0, 80), at: Date.now() };
  await env.CODES.put("m:" + to + ":" + msg.at, JSON.stringify(msg), { expirationTtl: 86400 });
  return json({ ok: true });
}

async function friendInbox(env, url) {
  if (!(await friendsOk(env, url.searchParams.get("lic")))) return LOCKED();
  const id = url.searchParams.get("id") || "";
  if (!FRIEND_RE.test(id)) return json({ ok: false, error: "Bad code" }, 400);
  const page = await env.CODES.list({ prefix: "m:" + id + ":" });
  const out = [];
  for (const k of page.keys.slice(0, 5)) { const m = await env.CODES.get(k.name, "json"); if (m) out.push(m); await env.CODES.delete(k.name); }
  return json({ ok: true, messages: out });
}

// ---- sports lineups ---------------------------------------------------------------------------
// A stick's sports-looking channels (name, group, guide id, next twelve hours of titles), sent
// from Settings › About on request, kept a month under the friend code. One KV write per send.
// Names only: the app never includes stream URLs or logins, and the provider is a host hash.

const LINEUP_TTL = 30 * 24 * 3600;
async function lineupPut(request, env) {
  if (await syncLimited(request, env)) return json({ ok: false }, 429);
  const b = await request.json().catch(() => null);
  const id = String(b?.id || "");
  if (!FRIEND_RE.test(id)) return json({ ok: false, error: "Bad code" }, 400);
  const chans = Array.isArray(b?.channels) ? b.channels.slice(0, 8000).map((c) => ({
    name: String(c?.name || "").slice(0, 80), group: String(c?.group || "").slice(0, 60), epg: String(c?.epg || "").slice(0, 60),
    titles: Array.isArray(c?.titles) ? c.titles.slice(0, 16).map((t) => String(t).slice(0, 80)) : [],
  })) : [];
  if (!chans.length) return json({ ok: false, error: "Nothing to send" }, 400);
  const off = Array.isArray(b?.off) ? b.off.slice(0, 300).map((x) => String(x).slice(0, 60)) : [];
  const rec = { at: Date.now(), host: String(b?.host || "").slice(0, 16), app: String(b?.app || "").slice(0, 16), n: chans.length, off, channels: chans };
  await env.CODES.put("lu:" + id, JSON.stringify(rec), { expirationTtl: LINEUP_TTL });
  return json({ ok: true, n: chans.length });
}
async function lineupList(env) {
  const out = [];
  const page = await env.CODES.list({ prefix: "lu:" });
  for (const k of page.keys) { const r = await env.CODES.get(k.name, "json"); if (r) out.push({ id: k.name.slice(3), at: r.at, host: r.host, app: r.app, n: r.n }); }
  out.sort((a, b) => b.at - a.at);
  return json({ ok: true, lineups: out });
}
async function lineupGet(env, url) {
  const id = url.searchParams.get("id") || "";
  if (!FRIEND_RE.test(id)) return json({ ok: false, error: "Bad code" }, 400);
  const r = await env.CODES.get("lu:" + id, "json");
  return r ? json({ ok: true, ...r }) : json({ ok: false, error: "No lineup for that code" }, 404);
}

// ---- admin page -----------------------------------------------------------------------------

const ADMIN_PAGE = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SloopyTV codes</title>
<style>
:root{color-scheme:dark}
body{margin:0;background:#0b0b0c;color:#ebe9e4;font:16px/1.45 -apple-system,system-ui,Segoe UI,Roboto,sans-serif}
main{max-width:720px;margin:0 auto;padding:28px 20px 80px}
h1{font-size:30px;line-height:1;margin:0 0 4px;text-transform:uppercase;letter-spacing:.01em}
.k{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;letter-spacing:.18em;text-transform:uppercase;color:#8d8a84}
label{display:block;margin-top:18px}
input,select{width:100%;box-sizing:border-box;background:none;border:0;border-bottom:1px solid #2e2e32;color:#ebe9e4;font:inherit;font-size:18px;padding:9px 0;outline:none;border-radius:0}
select option{background:#0b0b0c}
input:focus{border-bottom:2px solid #ebe9e4}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
button{margin-top:22px;height:46px;padding:0 22px;border:1px solid #3a3a3e;background:none;color:#ebe9e4;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px;letter-spacing:.16em;text-transform:uppercase;border-radius:0;cursor:pointer}
button.pri{background:#ebe9e4;color:#0b0b0c;border-color:#ebe9e4}
button.sm{height:32px;padding:0 12px;margin:0;font-size:11px}
.code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:44px;letter-spacing:.12em;color:#e8a53a;margin-top:18px}
.msg{color:#e8a53a;margin-top:14px;min-height:22px}
table{width:100%;border-collapse:collapse;margin-top:28px}
td,th{text-align:left;padding:10px 6px;border-bottom:1px solid #1a1a1c;font-size:14px;vertical-align:top}
th{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:11px;letter-spacing:.18em;text-transform:uppercase;color:#8d8a84;font-weight:normal}
.used{color:#8d8a84}
.wait{color:#e8a53a}
#app{display:none}
.brand{display:flex;align-items:center;gap:14px;font-family:Impact,"Arial Narrow",sans-serif;font-stretch:condensed;font-size:22px;letter-spacing:.06em;color:#ebe9e4;margin-bottom:22px}
.brand .rule{flex:1;height:1px;background:#3a3a3e}
textarea{width:100%;box-sizing:border-box;background:none;border:0;border-bottom:1px solid #2e2e32;color:#ebe9e4;font:inherit;font-size:16px;padding:9px 0;outline:none;border-radius:0;resize:vertical;min-height:64px}
.tog{display:flex;align-items:center;gap:10px;margin-top:18px}
.foot{margin-top:60px;color:#5f5d58;font-size:12px;letter-spacing:.18em;text-transform:uppercase;font-family:ui-monospace,SFMono-Regular,Menlo,monospace}
</style></head><body><main>
<div class="brand"><span class="rule"></span>STONE TOAD<span class="rule"></span></div><div class="k">SloopyTV · Operator</div><h1>Login codes</h1>
<div id="login"><label><span class="k">Admin password</span><input id="pw" type="password" autocomplete="current-password"></label><button class="pri" id="go">Open</button><div class="msg" id="lmsg"></div></div>
<div id="app">
  <label><span class="k">Server address</span><input id="url" class="mono" placeholder="http://host:port"></label>
  <label><span class="k">Username</span><input id="user" class="mono"></label>
  <label><span class="k">Password</span><input id="pass" class="mono"></label>
  <label><span class="k">Name shown in the app (optional)</span><input id="name" placeholder="Home"></label>
  <label><span class="k">Your note (optional, e.g. the customer's name)</span><input id="note"></label>
  <button class="pri" id="mint">Make code</button>
  <div class="code" id="code"></div>
  <div class="msg" id="msg"></div>
  <p class="k" style="margin-top:12px">Codes work once and die after 24 hours. The login is stored scrambled; only the code unlocks it.</p>
  <table><thead><tr><th>Code</th><th>Note</th><th>Status</th><th>Made</th><th></th></tr></thead><tbody id="rows"></tbody></table>
  <h1 style="margin-top:48px">Paywall</h1>
  <p class="k">Leave the product blank and the app is free. Fill it in and the app asks for a Gumroad license key before it opens.</p>
  <label><span class="k">Gumroad product id (from the product's page, "Product ID")</span><input id="gp" class="mono"></label>
  <label><span class="k">Buy link shown on the TV</span><input id="gu" class="mono" placeholder="https://yourname.gumroad.com/l/sloopytv"></label>
  <label><span class="k">Price shown on the TV</span><input id="gpr" placeholder="$2"></label>
  <p class="k" style="margin-top:14px">Friends (see what friends watch, send a channel to their TV) can be a paid extra on its own. Empty = free.</p>
  <label><span class="k">Friends: Gumroad product id</span><input id="fgp" class="mono"></label>
  <label><span class="k">Friends: buy link shown on the TV</span><input id="fgu" class="mono" placeholder="https://yourname.gumroad.com/l/sloopytv-friends"></label>
  <label><span class="k">Friends: price shown on the TV</span><input id="fgpr" placeholder="$3"></label>
  <button class="pri" id="cfg">Save paywall</button>
  <div class="msg" id="cmsg"></div>

  <h1 style="margin-top:48px">Operator</h1>
  <label><span class="k">Service name shown on sticks set up with these codes</span><input id="opname" placeholder="e.g. Mike's TV"></label>
  <button class="pri" id="opsave">Save name</button>
  <div class="msg" id="opmsg"></div>

  <h1 style="margin-top:48px">Announcement</h1>
  <p class="k">Shows once on every stick the next time SloopyTV opens. Empty clears it.</p>
  <label><span class="k">Message</span><textarea id="ann" maxlength="300" placeholder="Server maintenance tonight 2–3am. Channels may drop for a few minutes."></textarea></label>
  <button class="pri" id="annsend">Send to all sticks</button> <button id="annclear">Clear</button>
  <div class="msg" id="annmsg"></div>

  <h1 style="margin-top:48px">Dead-channel reports</h1>
  <p class="k">Off: the app shows no Report option at all. On: viewers can hold OK on a channel and report it; you get a ping.</p>
  <div class="tog"><input type="checkbox" id="repon"><label for="repon" style="margin:0">Reports on</label></div>
  <label><span class="k">Telegram bot token (from BotFather)</span><input id="tgtok" class="mono" placeholder="123456789:AAH…"></label>
  <label><span class="k">Discord webhook (optional, instead of or as well as Telegram)</span><input id="dcweb" class="mono" placeholder="https://discord.com/api/webhooks/…"></label>
  <button class="pri" id="repsave">Save</button> <button id="reptest">Test ping</button>
  <div class="msg" id="repmsg"></div>
  <table><thead><tr><th>When</th><th>Channel</th><th>What happened</th><th>Who</th><th></th></tr></thead><tbody id="reps"></tbody></table>
  <h2 style="margin-top:28px">Sports lineups</h2>
  <p class="k">Sent from a stick's Settings › About › "Send my sports lineup". Channel names, groups, guide ids and the next twelve hours of titles. Kept a month.</p>
  <table><thead><tr><th>Stick</th><th>Sent</th><th>Service</th><th>App</th><th>Channels</th><th></th></tr></thead><tbody id="lus"></tbody></table>
  <pre id="lupre" class="mono" style="white-space:pre-wrap;max-height:600px;overflow:auto;background:#0d0d0d;padding:12px;border:1px solid #333;display:none"></pre>
  <h1 style="margin-top:48px">The Toad</h1>
  <p class="k">The helper on the strip: say or type "put ESPN and AMC in favourites" and it does it. Uses your Claude credit through this server; the key never leaves here.</p>
  <div class="tog"><input type="checkbox" id="toadon"><label for="toadon" style="margin:0">Toad on</label></div>
  <label><span class="k">Sticks allowed (friend codes, from Settings › Friends on each TV; empty = every stick)</span><input id="toadsticks" class="mono" placeholder="EMPTR2WM LQMS5BG8"></label>
  <label><span class="k">Requests per stick per day</span><input id="toadcap" class="mono" placeholder="50" style="max-width:120px"></label>
  <label><span class="k">Model</span><select id="toadmodel"><option value="claude-haiku-5-5">Haiku 5.5 (fast, about a tenth of a cent a request)</option><option value="claude-sonnet-5-5">Sonnet 5.5 (sharper, about a cent)</option><option value="claude-opus-5-5">Opus 5.5 (sharpest, a few cents)</option></select></label>
  <button class="pri" id="toadsave">Save</button>
  <div class="msg" id="toadmsg"></div>
  <p class="k" id="toadspend"></p>
  <div class="foot">A Stone Toad joint</div>
</div></main>
<script>
var pw = sessionStorage.getItem("pw") || "";
function $(id){return document.getElementById(id)}
function api(method, path, body){return fetch(path,{method:method,headers:{"authorization":"Bearer "+pw,"content-type":"application/json"},body:body?JSON.stringify(body):undefined}).then(function(r){return r.json()})}
function fmt(t){if(!t)return "";var d=new Date(t);return d.toLocaleDateString()+" "+d.toLocaleTimeString([], {hour:"2-digit",minute:"2-digit"})}
function refresh(){api("GET","/admin/list").then(function(j){if(!j.ok){show(false);return}var h="";j.codes.forEach(function(c){h+="<tr><td class=mono>"+c.code+"</td><td>"+(c.note||"")+"</td><td class="+(c.status==="used"?"used":"wait")+">"+(c.status==="used"?"used "+fmt(c.usedAt):"waiting")+"</td><td>"+fmt(c.createdAt)+"</td><td><button class=sm data-c="+c.code+">Delete</button></td></tr>"});$("rows").innerHTML=h})}
function show(ok){$("login").style.display=ok?"none":"block";$("app").style.display=ok?"block":"none";if(ok)refresh()}
$("go").onclick=function(){pw=$("pw").value;sessionStorage.setItem("pw",pw);api("GET","/admin/list").then(function(j){if(j.ok)show(true);else $("lmsg").textContent=j.error||"Wrong password"})}
$("mint").onclick=function(){$("code").textContent="";$("msg").textContent="";api("POST","/admin/mint",{url:$("url").value,user:$("user").value,pass:$("pass").value,name:$("name").value,note:$("note").value}).then(function(j){if(j.ok){$("code").textContent=j.code;$("msg").textContent="Send this code. It works once, until "+fmt(j.expiresAt)+".";$("user").value="";$("pass").value="";$("note").value="";refresh()}else $("msg").textContent=j.error})}
$("rows").onclick=function(e){var c=e.target.getAttribute("data-c");if(c&&confirm("Delete code "+c+"?"))api("DELETE","/admin/code/"+c).then(refresh)}
$("cfg").onclick=function(){api("POST","/admin/config",{gumroadProduct:$("gp").value,buyUrl:$("gu").value,price:$("gpr").value,friendsProduct:$("fgp").value,friendsBuyUrl:$("fgu").value,friendsPrice:$("fgpr").value}).then(function(j){$("cmsg").textContent=j.ok?((j.gumroadProduct?"Paywall on.":"Paywall off, the app is free.")+(j.friendsProduct?" Friends is paid.":" Friends is free.")):j.error})}
function loadCfg(){api("GET","/admin/config").then(function(j){if(j.ok){$("gp").value=j.gumroadProduct||"";$("gu").value=j.buyUrl||"";$("gpr").value=j.price||"";$("fgp").value=j.friendsProduct||"";$("fgu").value=j.friendsBuyUrl||"";$("fgpr").value=j.friendsPrice||"";$("opname").value=j.operatorName||"";$("ann").value=j.announcementText||"";$("repon").checked=!!j.reportsEnabled;$("toadon").checked=!!j.toadEnabled;$("toadsticks").value=j.toadSticks||"";$("toadcap").value=j.toadDailyCap||50;$("toadmodel").value=j.toadModel||"claude-haiku-5-5";loadToad();$("tgtok").value=j.telegramToken||"";$("dcweb").value=j.discordWebhook||""}});loadReps();loadLineups()}
function loadLineups(){api("GET","/admin/lineups").then(function(j){if(!j.ok)return;var h="";j.lineups.forEach(function(l){h+="<tr><td class=mono>"+l.id+"</td><td>"+fmt(l.at)+"</td><td class=mono>"+(l.host||"")+"</td><td>"+(l.app||"")+"</td><td>"+l.n+"</td><td><button class=sm data-lu="+l.id+">View</button></td></tr>"});$("lus").innerHTML=h||"<tr><td colspan=6 class=used>No lineups yet.</td></tr>"})}
document.addEventListener("click",function(e){var b=e.target.closest("button[data-lu]");if(!b)return;api("GET","/admin/lineup?id="+b.dataset.lu).then(function(j){if(!j.ok){alert(j.error);return}var lines=[];if(j.off&&j.off.length){lines.push("UNTICKED GROUPS: "+j.off.join(" | "));lines.push("")}j.channels.forEach(function(c){lines.push((c.group||"")+" | "+c.name+" | "+(c.epg||"-")+(c.titles&&c.titles.length?"\n    "+c.titles.join(" / "):""))});var p=$("lupre");p.textContent=lines.join("\n");p.style.display="block";p.scrollIntoView()})});
function loadReps(){api("GET","/admin/reports").then(function(j){if(!j.ok)return;var h="";j.reports.forEach(function(r){h+="<tr><td>"+fmt(r.at)+"</td><td>"+(r.number?r.number+" ":"")+r.channel+(r.group?" <span class=used>("+r.group+")</span>":"")+"</td><td>"+(r.error||"")+(r.note?"<br><span class=used>"+r.note+"</span>":"")+"</td><td>"+(r.nick||"")+"<br><span class=used>"+(r.device||"")+" v"+(r.version||"")+"</span></td><td><button class=sm data-r="+r.id+">Fixed</button></td></tr>"});$("reps").innerHTML=h||"<tr><td colspan=5 class=used>No reports.</td></tr>"})}
$("opsave").onclick=function(){api("POST","/admin/config",{operatorName:$("opname").value}).then(function(j){$("opmsg").textContent=j.ok?"Saved.":j.error})}
$("annsend").onclick=function(){api("POST","/admin/config",{announcementText:$("ann").value}).then(function(j){$("annmsg").textContent=j.ok?(j.announcementText?"Sent. Sticks show it on their next open.":"Cleared."):j.error})}
$("annclear").onclick=function(){$("ann").value="";api("POST","/admin/config",{announcementText:""}).then(function(j){$("annmsg").textContent=j.ok?"Cleared.":j.error})}
function loadToad(){api("GET","/admin/toad").then(function(j){if(j.ok)$("toadspend").textContent=(j.keySet?"":"No Claude key on the server yet. ")+"This month ("+j.month+"): "+j.calls+" requests, about $"+j.usd.toFixed(2)+"."})}
$("toadsave").onclick=function(){api("POST","/admin/config",{toadEnabled:$("toadon").checked,toadSticks:$("toadsticks").value,toadDailyCap:$("toadcap").value,toadModel:$("toadmodel").value}).then(function(j){$("toadmsg").textContent=j.ok?(j.toadEnabled?("Toad on"+(j.toadSticks?" for "+j.toadSticks.split(" ").length+" stick(s).":" for every stick.")):"Toad off."):j.error;if(j.ok)$("toadsticks").value=j.toadSticks||""})}
$("repsave").onclick=function(){api("POST","/admin/config",{reportsEnabled:$("repon").checked,telegramToken:$("tgtok").value,discordWebhook:$("dcweb").value}).then(function(j){$("repmsg").textContent=j.ok?(j.reportsEnabled?"Reports on.":"Reports off."):j.error;if(j.ok){$("tgtok").value=j.telegramToken||"";$("dcweb").value=j.discordWebhook||""}})}
$("reptest").onclick=function(){$("repmsg").textContent="Sending…";api("POST","/admin/report-test").then(function(j){$("repmsg").textContent=j.ok?"Sent. Check the phone.":j.error})}
$("reps").onclick=function(e){var r=e.target.getAttribute("data-r");if(r)api("DELETE","/admin/report/"+r).then(loadReps)}
var _show=show;show=function(ok){_show(ok);if(ok)loadCfg()}
if(pw)api("GET","/admin/list").then(function(j){show(!!j.ok)})
</script></body></html>`;
