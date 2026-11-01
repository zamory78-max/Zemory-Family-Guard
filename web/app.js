// FamilyGuard parent dashboard (works in the browser and inside the parent Android app).
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  getAuth, onAuthStateChanged, signInWithEmailAndPassword, createUserWithEmailAndPassword,
  signOut, sendPasswordResetEmail,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";
import {
  getFirestore, collection, query, where, orderBy, limit, onSnapshot, doc, updateDoc, addDoc,
  deleteDoc, serverTimestamp, arrayUnion, arrayRemove, deleteField, FieldPath,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-firestore.js";

const $app = document.getElementById("app");
const $user = document.getElementById("userbox");
const $modal = document.getElementById("modal");
const $toast = document.getElementById("toast");

// ---------- helpers ----------
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const fmtTime = new Intl.DateTimeFormat("ar-u-nu-latn", { hour: "2-digit", minute: "2-digit" });
const fmtDate = new Intl.DateTimeFormat("ar-u-nu-latn", { weekday: "long", day: "numeric", month: "long" });
const dayKey = (d = new Date()) => {
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
};
const mins = (m) => {
  m = Number(m) || 0;
  if (m < 60) return `${m} د`;
  const h = Math.floor(m / 60), r = m % 60;
  return r ? `${h} س ${r} د` : `${h} س`;
};
const ago = (ms) => {
  if (!ms) return "لم يتصل بعد";
  const s = Math.round((Date.now() - ms) / 1000);
  if (s < 60) return "الآن";
  if (s < 3600) return `منذ ${Math.round(s / 60)} د`;
  if (s < 86400) return `منذ ${Math.round(s / 3600)} س`;
  return `منذ ${Math.round(s / 86400)} يوم`;
};
const tsMs = (t) => (t && typeof t.toMillis === "function" ? t.toMillis() : typeof t === "number" ? t : 0);
let toastTimer;
function toast(msg) {
  $toast.textContent = msg;
  $toast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => ($toast.hidden = true), 2600);
}
function openModal(html) {
  $modal.innerHTML = `<div class="card stack">${html}</div>`;
  $modal.hidden = false;
}
function closeModal() { $modal.hidden = true; $modal.innerHTML = ""; }
$modal.addEventListener("click", (e) => { if (e.target === $modal || e.target.closest("[data-close]")) closeModal(); });
async function sha256(text) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
async function run(promise, okMsg) {
  try { await promise; if (okMsg) toast(okMsg); }
  catch (e) { console.error(e); toast("خطأ: " + (e.code || e.message)); }
}

// ---------- configuration ----------
function loadConfig() {
  if (window.FIREBASE_CONFIG && window.FIREBASE_CONFIG.apiKey) return window.FIREBASE_CONFIG;
  try { const s = localStorage.getItem("fg_config"); if (s) return JSON.parse(s); } catch (_) {}
  return null;
}
function childApkUrl() {
  try { const s = localStorage.getItem("fg_apk"); if (s) return s; } catch (_) {}
  return window.FG_CHILD_APK_URL || "";
}

function renderConfigForm() {
  $user.innerHTML = "";
  $app.innerHTML = `
  <div class="card auth stack">
    <h1>إعداد Firebase</h1>
    <p class="muted small">لم يتم العثور على إعدادات Firebase. الصق كائن <span class="mono">firebaseConfig</span> من
    Firebase Console ← Project settings ← Your apps ← Web app.</p>
    <textarea id="cfg" class="input mono" rows="8" placeholder='{ "apiKey": "...", "authDomain": "...", "projectId": "...", "appId": "..." }'></textarea>
    <button class="btn primary" id="saveCfg">حفظ</button>
  </div>`;
  document.getElementById("saveCfg").onclick = () => {
    const raw = document.getElementById("cfg").value.trim();
    try {
      // accept both JSON and a JS object literal (const firebaseConfig = {...};)
      const body = raw.slice(raw.indexOf("{"), raw.lastIndexOf("}") + 1);
      const obj = JSON.parse(body.replace(/([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)\s*:/g, '$1"$2":').replace(/'/g, '"').replace(/,\s*}/g, "}"));
      if (!obj.apiKey || !obj.projectId) throw new Error("missing");
      localStorage.setItem("fg_config", JSON.stringify(obj));
      location.reload();
    } catch (_) { toast("صيغة غير صحيحة"); }
  };
}

const config = loadConfig();
if (!config) {
  renderConfigForm();
  throw new Error("no firebase config");
}
const fbApp = initializeApp(config);
const auth = getAuth(fbApp);
const db = getFirestore(fbApp);

// ---------- state ----------
const S = {
  user: null,
  devices: new Map(),
  deviceId: null,
  tab: "apps",
  apps: null, // { apps: {pkg: {...}}, day }
  commands: [],
  webDay: dayKey(),
  web: null,
  requests: [],
  search: "",
  appFilter: "user",
  hideSystemDomains: true,
  unsubDevices: null,
  unsubDetail: [],
  unsubWeb: null,
};

const devRef = (id = S.deviceId) => doc(db, "devices", id);
const dev = () => S.devices.get(S.deviceId);
const pol = () => (dev() && dev().policy) || {};
const isOnline = (d) => Date.now() - tsMs(d.lastSeen) < 8 * 60 * 1000;
const typeIcon = (t) => ({ tv: "📺", tablet: "💻", pc: "🖥️" }[t] || "📱");
const isPc = (d) => !!d && (d.type === "pc" || (d.status && d.status.platform === "windows"));

// ---------- auth ----------
onAuthStateChanged(auth, (u) => {
  S.user = u;
  stopDetail();
  if (S.unsubDevices) { S.unsubDevices(); S.unsubDevices = null; }
  if (!u) { renderAuth(); return; }
  $user.innerHTML = `<span>${esc(u.email)}</span><button class="btn sm ghost" id="logout">خروج</button>`;
  document.getElementById("logout").onclick = () => signOut(auth);
  S.unsubDevices = onSnapshot(
    query(collection(db, "devices"), where("ownerUid", "==", u.uid)),
    (qs) => {
      S.devices = new Map();
      qs.forEach((d) => { const v = d.data(); if (!v.unlinked) S.devices.set(d.id, { id: d.id, ...v }); });
      if (S.deviceId && !S.devices.has(S.deviceId)) { stopDetail(); S.deviceId = null; }
      render();
    },
    (e) => { console.error(e); toast("تعذّر قراءة الأجهزة: " + e.code); },
  );
  render();
});

function renderAuth() {
  $user.innerHTML = "";
  $app.innerHTML = `
  <form class="card auth stack" id="authForm">
    <h1>تسجيل الدخول</h1>
    <p class="muted small">استخدم نفس الحساب على تطبيق الأجهزة لربطها بك.</p>
    <label class="field"><span>البريد الإلكتروني</span><input class="input" type="email" id="em" required autocomplete="username" dir="ltr"></label>
    <label class="field"><span>كلمة المرور</span><input class="input" type="password" id="pw" required minlength="6" autocomplete="current-password" dir="ltr"></label>
    <button class="btn primary" type="submit">دخول</button>
    <div class="row">
      <button class="btn ghost sm" type="button" id="register">إنشاء حساب جديد</button>
      <span class="spacer"></span>
      <button class="btn ghost sm" type="button" id="reset">نسيت كلمة المرور</button>
    </div>
  </form>`;
  const em = () => document.getElementById("em").value.trim();
  const pw = () => document.getElementById("pw").value;
  document.getElementById("authForm").onsubmit = (e) => {
    e.preventDefault();
    run(signInWithEmailAndPassword(auth, em(), pw()));
  };
  document.getElementById("register").onclick = () => {
    if (!em() || pw().length < 6) return toast("أدخل البريد وكلمة مرور من 6 أحرف على الأقل");
    run(createUserWithEmailAndPassword(auth, em(), pw()), "تم إنشاء الحساب");
  };
  document.getElementById("reset").onclick = () => {
    if (!em()) return toast("أدخل بريدك أولاً");
    run(sendPasswordResetEmail(auth, em()), "تم إرسال رابط إعادة التعيين");
  };
}

// ---------- routing ----------
function render() {
  if (!S.user) return;
  if (S.deviceId && dev()) renderDevice();
  else renderDevices();
}

function openDevice(id) {
  stopDetail();
  S.deviceId = id;
  S.tab = "apps";
  S.apps = null;
  S.commands = [];
  S.webDay = dayKey();
  S.search = "";
  S.unsubDetail.push(onSnapshot(doc(db, "devices", id, "meta", "apps"), (d) => { S.apps = d.exists() ? d.data() : { apps: {} }; renderDevice(); }));
  S.unsubDetail.push(onSnapshot(
    query(collection(db, "devices", id, "commands"), orderBy("createdAt", "desc"), limit(20)),
    (qs) => { S.commands = qs.docs.map((d) => ({ id: d.id, ...d.data() })); if (S.tab === "commands") renderDevice(); },
  ));
  S.requests = [];
  S.unsubDetail.push(onSnapshot(collection(db, "devices", id, "requests"), (qs) => {
    S.requests = qs.docs.map((d) => ({ id: d.id, ...d.data() }))
      .filter((x) => x.status === "pending")
      .sort((a, b) => tsMs(b.ts) - tsMs(a.ts));
    renderDevice();
  }));
  listenWeb();
  renderDevice();
  window.scrollTo(0, 0);
  autoSync(id);
}

// Ask the device for fresh data when the parent opens it (throttled to save Firebase quota)
function autoSync(id) {
  const d = S.devices.get(id);
  if (!d || !isOnline(d)) return;
  const key = "fg_sync_" + id;
  let last = 0;
  try { last = Number(localStorage.getItem(key) || 0); } catch (_) {}
  if (Date.now() - last < 5 * 60 * 1000 || Date.now() - tsMs(d.lastSeen) < 2 * 60 * 1000) return;
  try { localStorage.setItem(key, String(Date.now())); } catch (_) {}
  addDoc(collection(db, "devices", id, "commands"), { type: "sync", status: "pending", createdAt: serverTimestamp(), auto: true })
    .catch((e) => console.warn("sync", e));
}

function listenWeb() {
  if (S.unsubWeb) S.unsubWeb();
  S.web = null;
  S.unsubWeb = onSnapshot(doc(db, "devices", S.deviceId, "web", S.webDay), (d) => {
    S.web = d.exists() ? d.data() : {};
    if (S.tab === "web") renderDevice();
  });
}

function stopDetail() {
  S.unsubDetail.forEach((u) => u());
  S.unsubDetail = [];
  if (S.unsubWeb) { S.unsubWeb(); S.unsubWeb = null; }
}

// ---------- devices list ----------
const OFFLINE_ALERT_MS = 40 * 60 * 1000; // device reports every 15 min

// Problems the parent must know about: [text, severity]
function deviceIssues(d) {
  const st = d.status || {};
  const out = [];
  const seen = tsMs(d.lastSeen);
  if (seen && Date.now() - seen > OFFLINE_ALERT_MS) out.push([`انقطع ${ago(seen)}`, "danger"]);
  if (isPc(d)) {
    if (st.accessibility === false) out.push(["المراقبة في جلسة الطفل متوقفة", "danger"]);
  } else {
    if (st.accessibility === false) out.push(["إمكانية الوصول معطّلة", "danger"]);
    if (st.usageAccess === false) out.push(["بيانات الاستخدام معطّلة", "danger"]);
  }
  if (st.vpnNeeded && st.vpn === false) out.push(["فلتر المواقع متوقف", "danger"]);
  if (!st.deviceOwner && st.admin === false) out.push(["الحماية من الحذف غير مفعّلة", "warn"]);
  if (st.battery === false) out.push(["قد يوقفه توفير البطارية", "warn"]);
  if (!(d.policy && d.policy.pinHash)) out.push(["لم يُعيَّن رمز المالك", "warn"]);
  if (st.paused) out.push(["متوقف مؤقتاً", "warn"]);
  return out;
}
const deviceWarnings = (d) => deviceIssues(d).map(([t]) => t);
const issueBadges = (d) => deviceIssues(d).map(([t, c]) => `<span class="badge ${c}">${esc(t)}</span>`).join("");
const modeBadge = (d) => isPc(d) ? `<span class="badge ok">خدمة ويندوز</span>` : (d.status && d.status.deviceOwner)
  ? `<span class="badge ok">حماية كاملة</span>` : `<span class="badge">حماية عادية</span>`;

function renderDevices() {
  const list = [...S.devices.values()].sort((a, b) => (a.name || "").localeCompare(b.name || "", "ar"));
  const alerts = list.filter((d) => deviceIssues(d).some(([, c]) => c === "danger"));
  $app.innerHTML = `
  <h1>أجهزتي</h1>
  ${alerts.length ? `<div class="alert">⚠️ ${alerts.length === 1 ? "جهاز يحتاج" : alerts.length + " أجهزة تحتاج"} انتباهك: ${alerts.map((d) => esc(d.name || "جهاز")).join("، ")}</div>` : ""}
  <div class="grid">
    ${list.map((d) => {
      const on = isOnline(d);
      return `<div class="card device-card" data-open="${esc(d.id)}">
        <div class="row"><span class="icon">${typeIcon(d.type)}</span><span class="spacer"></span>
          <span class="badge ${on ? "ok" : ""}"><span class="dot"></span>${on ? "متصل" : "غير متصل"}</span></div>
        <b>${esc(d.name || "جهاز")}</b>
        <span class="muted small">${esc(d.model || "")} · ${esc(ago(tsMs(d.lastSeen)))}</span>
        <div class="row">${modeBadge(d)}${issueBadges(d)}</div>
      </div>`;
    }).join("")}
    <div class="card device-card add" id="addDevice"><span style="font-size:28px">＋</span><span>إضافة جهاز</span></div>
  </div>`;
  $app.querySelectorAll("[data-open]").forEach((el) => (el.onclick = () => openDevice(el.dataset.open)));
  document.getElementById("addDevice").onclick = showAddDevice;
}

// ---------- add device (child account) ----------
const DEFAULT_POLICY = {
  blocked: [], hidden: [], pinLocked: [], limits: {}, pinHash: "",
  restrictions: {
    no_factory_reset: true, no_add_user: true, no_safe_boot: true, no_install_unknown_sources: true,
    no_config_date_time: true, no_control_apps: true, lock_settings: true,
  },
  web: { filter: false, log: true, safeSearch: false, blocked: [] },
  bedtime: { enabled: false, start: "21:00", end: "07:00", allowed: [] },
  pauseAll: false,
};

// A second Firebase app instance so creating the child's account does not sign the parent out
let childAuth = null;
function getChildAuth() {
  if (!childAuth) childAuth = getAuth(initializeApp(config, "child-accounts"));
  return childAuth;
}

async function createChildAccount(email, password) {
  const a = getChildAuth();
  let cred;
  try {
    cred = await createUserWithEmailAndPassword(a, email, password);
  } catch (e) {
    if (e.code !== "auth/email-already-in-use") throw e;
    cred = await signInWithEmailAndPassword(a, email, password); // reuse an existing child account
  }
  const uid = cred.user.uid;
  await signOut(a);
  return uid;
}

function showAddDevice() {
  const apk = childApkUrl();
  openModal(`
    <h2>إضافة جهاز</h2>
    <p class="small muted" style="margin:0">أنشئ حساباً خاصاً لهذا الجهاز. البريد لا يحتاج أن يكون حقيقياً، مثلاً <span class="mono">ahmed.phone@family.dz</span></p>
    <form class="stack" id="addForm">
      <label class="field"><span>اسم الجهاز</span><input class="input" id="dName" required maxlength="40" placeholder="هاتف أحمد"></label>
      <label class="field"><span>النوع</span>
        <select class="input" id="dType"><option value="phone">📱 هاتف</option><option value="tablet">💻 تابلت</option><option value="tv">📺 تلفاز</option><option value="pc">🖥️ كمبيوتر ويندوز</option></select></label>
      <label class="field"><span>بريد الجهاز</span><input class="input mono" id="dEmail" type="email" required dir="ltr" autocomplete="off"></label>
      <label class="field"><span>كلمة مرور الجهاز (6 أحرف على الأقل)</span><input class="input mono" id="dPass" type="text" required minlength="6" dir="ltr" autocomplete="off"></label>
      <div class="row"><span class="spacer"></span><button class="btn" type="button" data-close>إلغاء</button><button class="btn primary" type="submit" id="dSave">إنشاء</button></div>
    </form>`);

  document.getElementById("addForm").onsubmit = async (e) => {
    e.preventDefault();
    const name = document.getElementById("dName").value.trim();
    const type = document.getElementById("dType").value;
    const email = document.getElementById("dEmail").value.trim().toLowerCase();
    const pass = document.getElementById("dPass").value;
    if (email === (S.user.email || "").toLowerCase()) return toast("استعمل بريداً مختلفاً عن بريدك");
    const btn = document.getElementById("dSave");
    btn.disabled = true; btn.textContent = "جارٍ الإنشاء…";
    try {
      const childUid = await createChildAccount(email, pass);
      const existing = [...S.devices.values()].find((d) => d.deviceUid === childUid);
      let id = existing && existing.id;
      if (!id) {
        const ref = await addDoc(collection(db, "devices"), {
          ownerUid: S.user.uid, deviceUid: childUid, deviceEmail: email,
          name, type, createdAt: serverTimestamp(), policy: DEFAULT_POLICY,
        });
        id = ref.id;
      }
      if (type === "pc") {
        openModal(`
        <h2>✅ تم إنشاء «${esc(name)}»</h2>
        <ol class="small" style="padding-inline-start:18px;margin:0">
          <li>على الكمبيوتر: أنشئ للطفل حساب ويندوز <b>عادي (ليس مسؤولاً)</b> إن لم يكن موجوداً.</li>
          <li>من حساب المسؤول نزّل <b>ZemoryGuard-Windows.zip</b> من صفحة الإصدارات وفك الضغط.</li>
          <li>كليك يمين على <b>install.bat</b> ← <b>تشغيل كمسؤول</b>، واكتب:</li>
        </ol>
        <pre class="code">${esc(email)}\n${esc(pass)}</pre>
        <ol class="small" start="4" style="padding-inline-start:18px;margin:0">
          <li>اكتب اسم حساب الطفل في ويندوز عندما يُطلب منك.</li>
          <li>عيّن <b>رمز المالك</b> من صفحة الجهاز ← القيود والإعدادات.</li>
        </ol>
        <div class="row"><span class="spacer"></span><button class="btn primary" data-close>تم</button></div>`);
        return;
      }
      openModal(`
        <h2>✅ تم إنشاء «${esc(name)}»</h2>
        <ol class="small" style="padding-inline-start:18px;margin:0">
          <li>ثبّت <b>Zemory Family Guard</b> (FamilyGuard-Child.apk) على جهاز الطفل${apk ? ` — <a href="${esc(apk)}" target="_blank" rel="noopener">رابط التنزيل</a>` : ""}.</li>
          <li>افتحه واكتب:</li>
        </ol>
        <pre class="code">${esc(email)}\n${esc(pass)}</pre>
        <ol class="small" start="3" style="padding-inline-start:18px;margin:0">
          <li>اتبع خطوات الإعداد على جهاز الطفل حتى «✅ الحماية مكتملة».</li>
          <li>عيّن <b>رمز المالك</b> من صفحة الجهاز ← القيود والإعدادات.</li>
        </ol>
        <p class="small muted">احتفظ بالبريد وكلمة المرور، ستحتاجهما إذا أعدت تثبيت التطبيق على الجهاز.</p>
        <div class="row"><span class="spacer"></span><button class="btn primary" data-close>تم</button></div>`);
    } catch (err) {
      console.error(err);
      const map = {
        "auth/invalid-email": "صيغة البريد غير صحيحة",
        "auth/weak-password": "كلمة المرور ضعيفة (6 أحرف على الأقل)",
        "auth/wrong-password": "هذا البريد مستعمل بكلمة مرور أخرى",
        "auth/invalid-credential": "هذا البريد مستعمل بكلمة مرور أخرى",
      };
      toast(map[err.code] || "خطأ: " + (err.code || err.message));
      btn.disabled = false; btn.textContent = "إنشاء";
    }
  };
}

// ---------- device detail ----------
const TABS = [
  ["apps", "التطبيقات"],
  ["time", "الوقت"],
  ["web", "المواقع"],
  ["rules", "القيود والإعدادات"],
  ["commands", "تثبيت وأوامر"],
];

function renderDevice() {
  const d = dev();
  if (!d) return renderDevices();
  const on = isOnline(d);
  // keep focus/caret of the search box across re-renders
  const active = document.activeElement;
  const keepSearch = active && active.id === "search";
  const caret = keepSearch ? active.selectionStart : 0;

  $app.innerHTML = `
  <div class="row">
    <button class="btn sm" id="back">→ الأجهزة</button>
    <span class="spacer"></span>
    <span class="badge ${on ? "ok" : ""}"><span class="dot"></span>${on ? "متصل" : "غير متصل"} · ${esc(ago(tsMs(d.lastSeen)))}</span>
  </div>
  <h1 style="margin-top:12px">${typeIcon(d.type)} ${esc(d.name || "جهاز")}</h1>
  <div class="row small muted">${esc(d.model || "")} · ${isPc(d) ? "Windows build" : "Android SDK"} ${esc(d.sdk ?? "?")} · التطبيق ${esc(d.appVersion || "?")}</div>
  <div class="row" style="margin-top:8px">${modeBadge(d)}${issueBadges(d)}</div>
  <nav class="tabs">${TABS.map(([k, t]) => `<button class="tab ${S.tab === k ? "active" : ""}" data-tab="${k}">${t}${k === "web" && S.requests.length ? ` <span class="count">${S.requests.length}</span>` : ""}</button>`).join("")}</nav>
  <section id="tabBody"></section>`;
  document.getElementById("back").onclick = () => { stopDetail(); S.deviceId = null; renderDevices(); };
  $app.querySelectorAll("[data-tab]").forEach((b) => (b.onclick = () => { S.tab = b.dataset.tab; renderDevice(); }));
  const body = document.getElementById("tabBody");
  ({ apps: tabApps, time: tabTime, web: tabWeb, rules: tabRules, commands: tabCommands }[S.tab])(body);

  if (keepSearch) {
    const s = document.getElementById("search");
    if (s) { s.focus(); s.setSelectionRange(caret, caret); }
  }
}

function appList() {
  const apps = (S.apps && S.apps.apps) || {};
  return Object.entries(apps).map(([pkg, a]) => ({ pkg, label: a.l || pkg, system: !!a.s, version: a.v || "", usage: a.u || 0, hidden: !!a.h, installed: a.i !== false, launchable: !!a.x }));
}
const usageIsToday = () => S.apps && S.apps.day === dayKey();

// ----- tab: apps -----
function tabApps(el) {
  const p = pol();
  const blocked = new Set(p.blocked || []);
  const pinned = new Set(p.pinLocked || []);
  const hidden = new Set(p.hidden || []);
  const limits = p.limits || {};
  el.innerHTML = `
  <div class="card stack">
    <div class="row">
      <input id="search" class="input" style="flex:1;min-width:180px" placeholder="ابحث عن تطبيق…" value="${esc(S.search)}">
      <select id="filter" class="input" style="width:auto">
        <option value="user" ${S.appFilter === "user" ? "selected" : ""}>تطبيقات المستخدم</option>
        <option value="all" ${S.appFilter === "all" ? "selected" : ""}>كل التطبيقات</option>
        <option value="system" ${S.appFilter === "system" ? "selected" : ""}>تطبيقات النظام</option>
      </select>
    </div>
    ${S.apps === null ? `<div class="empty">جارٍ التحميل…</div>` : ""}
    ${S.apps && !usageIsToday() ? `<div class="notice">بيانات الاستخدام ليست لليوم بعد — يتم تحديثها كل 5 دقائق عندما يكون الجهاز متصلاً.</div>` : ""}
    <div id="appRows" class="list"></div>
  </div>`;

  const rows = () => {
    const q = S.search.trim().toLowerCase();
    let list = appList();
    if (S.appFilter === "user") list = list.filter((a) => !a.system || a.launchable);
    if (S.appFilter === "system") list = list.filter((a) => a.system);
    if (q) list = list.filter((a) => a.label.toLowerCase().includes(q) || a.pkg.toLowerCase().includes(q));
    list.sort((a, b) => (usageIsToday() ? b.usage - a.usage : 0) || a.label.localeCompare(b.label, "ar"));
    const box = document.getElementById("appRows");
    if (!box) return;
    if (S.apps && !list.length) { box.innerHTML = `<div class="empty">لا توجد تطبيقات مطابقة</div>`; return; }
    box.innerHTML = list.map((a) => {
      const u = usageIsToday() ? a.usage : 0;
      const lim = limits[a.pkg] || "";
      const over = lim && u >= lim;
      return `<div class="item app-row" data-pkg="${esc(a.pkg)}">
        <div style="min-width:0">
          <div class="name">${esc(a.label)} ${a.system ? `<span class="badge">نظام</span>` : ""} ${!a.installed ? `<span class="badge danger">محذوف</span>` : ""}</div>
          <div class="pkg mono">${esc(a.pkg)} ${a.version ? "· " + esc(a.version) : ""}</div>
        </div>
        <div class="usage ${over ? "" : ""}" title="الاستخدام اليوم">${over ? "⛔ " : ""}${mins(u)}</div>
        <div class="controls">
          <label class="limit">حد يومي <input class="input" type="number" min="0" step="5" inputmode="numeric" data-act="limit" value="${esc(lim)}" placeholder="—"> د</label>
          <label class="switch"><input type="checkbox" data-act="blocked" ${blocked.has(a.pkg) ? "checked" : ""}>حظر</label>
          <label class="switch"><input type="checkbox" data-act="pinLocked" ${pinned.has(a.pkg) ? "checked" : ""}>قفل برمز</label>
          ${isPc(dev()) ? "" : `<label class="switch"><input type="checkbox" data-act="hidden" ${hidden.has(a.pkg) ? "checked" : ""}>إخفاء</label>`}
          <span class="spacer"></span>
          ${a.installed
            ? `<button class="btn sm danger" data-act="uninstall">حذف</button>`
            : `<button class="btn sm" data-act="enable">إعادة تثبيت</button>`}
        </div>
      </div>`;
    }).join("");
  };
  rows();

  document.getElementById("search").oninput = (e) => { S.search = e.target.value; rows(); };
  document.getElementById("filter").onchange = (e) => { S.appFilter = e.target.value; rows(); };
  const box = document.getElementById("appRows");
  box.addEventListener("change", (e) => {
    const t = e.target;
    const pkg = t.closest("[data-pkg]")?.dataset.pkg;
    if (!pkg) return;
    const act = t.dataset.act;
    if (act === "limit") {
      const v = parseInt(t.value, 10);
      run(updateDoc(devRef(), new FieldPath("policy", "limits", pkg), v > 0 ? v : deleteField()), v > 0 ? `الحد: ${mins(v)} يومياً` : "تم إلغاء الحد");
    } else if (["blocked", "pinLocked", "hidden"].includes(act)) {
      if (act === "pinLocked" && t.checked && !pol().pinHash) toast("تنبيه: عيّن رمز المالك من تبويب القيود");
      run(updateDoc(devRef(), { [`policy.${act}`]: t.checked ? arrayUnion(pkg) : arrayRemove(pkg) }), "تم الحفظ");
    }
  });
  box.addEventListener("click", (e) => {
    const b = e.target.closest("button[data-act]");
    if (!b) return;
    const pkg = b.closest("[data-pkg]").dataset.pkg;
    const label = appList().find((a) => a.pkg === pkg)?.label || pkg;
    if (b.dataset.act === "uninstall") {
      if (confirm(`حذف «${label}» من الجهاز؟\nلتطبيقات النظام استخدم «إخفاء» بدلاً من الحذف.`)) sendCommand({ type: "uninstall", pkg }, "تم إرسال أمر الحذف");
    } else if (b.dataset.act === "enable") {
      sendCommand({ type: "enable_system_app", pkg }, "تم إرسال أمر إعادة التثبيت");
    }
  });
}

function sendCommand(data, msg) {
  return run(addDoc(collection(db, "devices", S.deviceId, "commands"), { ...data, status: "pending", createdAt: serverTimestamp() }), msg);
}

// ----- tab: time -----
function tabTime(el) {
  const p = pol();
  const bed = p.bedtime || {};
  const allowed = new Set(bed.allowed || []);
  const apps = appList().filter((a) => a.launchable).sort((a, b) => a.label.localeCompare(b.label, "ar"));
  const used = usageIsToday() ? appList().filter((a) => a.usage > 0).sort((a, b) => b.usage - a.usage) : [];
  const total = used.reduce((s, a) => s + a.usage, 0);
  const max = used[0]?.usage || 1;
  el.innerHTML = `
  <div class="cols">
    <div class="card stack">
      <h2>استخدام اليوم <span class="muted small">(${mins(total)})</span></h2>
      ${used.length ? used.slice(0, 12).map((a) => `
        <div class="bar-row"><span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(a.label)}</span>
          <div><div class="bar" style="width:${Math.max(2, Math.round((a.usage / max) * 100))}%"></div></div>
          <span class="usage">${mins(a.usage)}</span></div>`).join("") : `<div class="empty">لا توجد بيانات استخدام لليوم بعد</div>`}
      <p class="small muted">لتحديد وقت لكل تطبيق استخدم خانة «حد يومي» في تبويب التطبيقات.</p>
    </div>
    <div class="stack">
      <div class="card">
        <div class="setting">
          <div class="txt"><b>⏸ إيقاف كل التطبيقات الآن</b><span class="small muted">يوقف كل التطبيقات (ما عدا المسموح بها أدناه) حتى تلغي الإيقاف.</span></div>
          <label class="switch"><input type="checkbox" id="pauseAll" ${p.pauseAll ? "checked" : ""}></label>
        </div>
      </div>
      <div class="card stack">
        <div class="setting">
          <div class="txt"><b>🌙 وقت النوم</b><span class="small muted">تتوقف التطبيقات تلقائياً كل يوم في هذه الفترة.</span></div>
          <label class="switch"><input type="checkbox" id="bedOn" ${bed.enabled ? "checked" : ""}></label>
        </div>
        <div class="row">
          <label class="field" style="flex:1"><span>من</span><input class="input" type="time" id="bedStart" value="${esc(bed.start || "21:00")}"></label>
          <label class="field" style="flex:1"><span>إلى</span><input class="input" type="time" id="bedEnd" value="${esc(bed.end || "07:00")}"></label>
        </div>
        <div>
          <h3>تطبيقات مسموحة دائماً</h3>
          <div class="chips" id="allowed">
            ${apps.map((a) => `<label class="chip" style="padding:4px 10px"><input type="checkbox" value="${esc(a.pkg)}" ${allowed.has(a.pkg) ? "checked" : ""}> ${esc(a.label)}</label>`).join("") || `<span class="muted small">لا توجد قائمة تطبيقات بعد</span>`}
          </div>
        </div>
      </div>
    </div>
  </div>`;
  document.getElementById("pauseAll").onchange = (e) => run(updateDoc(devRef(), { "policy.pauseAll": e.target.checked }), e.target.checked ? "تم إيقاف التطبيقات" : "تم الاستئناف");
  document.getElementById("bedOn").onchange = (e) => run(updateDoc(devRef(), { "policy.bedtime.enabled": e.target.checked }), "تم الحفظ");
  document.getElementById("bedStart").onchange = (e) => run(updateDoc(devRef(), { "policy.bedtime.start": e.target.value }), "تم الحفظ");
  document.getElementById("bedEnd").onchange = (e) => run(updateDoc(devRef(), { "policy.bedtime.end": e.target.value }), "تم الحفظ");
  document.getElementById("allowed").onchange = (e) => {
    const pkg = e.target.value;
    run(updateDoc(devRef(), { "policy.bedtime.allowed": e.target.checked ? arrayUnion(pkg) : arrayRemove(pkg) }), "تم الحفظ");
  };
}

// ----- tab: web -----
const PRESETS = {
  "التواصل الاجتماعي": ["facebook.com", "instagram.com", "tiktok.com", "snapchat.com", "x.com", "twitter.com", "threads.net"],
  "الفيديو": ["youtube.com", "youtu.be", "twitch.tv", "kick.com"],
  "الألعاب": ["roblox.com", "epicgames.com", "miniclip.com", "poki.com", "crazygames.com"],
};
const SYSTEM_DOMAIN = /(googleapis|gstatic|googleusercontent|gvt[12]|ggpht|doubleclick|googlesyndication|googleadservices|google-analytics|app-measurement|crashlytics|firebase|1e100|akamai|akadns|cloudfront|fbcdn|edgekey|edgesuite|cdn|xiaomi|miui|samsung|connectivitycheck|ntp\.|time\.|mtalk|clients\d*\.google|android\.com$|amazonaws|azure|msedge|apple\.com$|icloud|digicert|letsencrypt|ocsp|pki\.goog|lencr|unity3d|applovin|adjust\.com|appsflyer|branch\.io|onesignal|sentry)/i;

function normDomain(raw) {
  let s = String(raw || "").trim().toLowerCase();
  s = s.replace(/^[a-z]+:\/\//, "").split(/[/?#:]/)[0].replace(/^\*\./, "").replace(/^www\./, "").replace(/\.$/, "");
  return /^[a-z0-9.-]+\.[a-z0-9-]{2,}$/.test(s) ? s : null;
}
function isBlockedDomain(name, set) {
  let d = name;
  while (d) {
    if (set.has(d)) return true;
    const i = d.indexOf(".");
    if (i < 0) return false;
    d = d.slice(i + 1);
  }
  return false;
}

function tabWeb(el) {
  const p = pol();
  const web = p.web || {};
  const blocked = new Set(web.blocked || []);
  const allowed = new Set(web.allowed || []);
  const data = S.web || {};
  let domains = Object.entries(data.domains || {}).map(([name, v]) => ({ name, c: v.c || 0, t: v.t || 0, b: !!v.b }));
  if (S.hideSystemDomains) domains = domains.filter((d) => !SYSTEM_DOMAIN.test(d.name));
  domains.sort((a, b) => b.t - a.t);
  const urls = Object.values(data.urls || {}).sort((a, b) => (b.t || 0) - (a.t || 0));
  const days = [...Array(7)].map((_, i) => { const d = new Date(); d.setDate(d.getDate() - i); return d; });

  const link = (u) => (/^[a-z]+:\/\//i.test(u) ? u : "https://" + u);
  el.innerHTML = `
  ${S.requests.length ? `<div class="card stack" style="margin-bottom:12px;border-color:var(--primary)">
    <h2>📨 طلبات السماح بمواقع (${S.requests.length})</h2>
    <div class="list">${S.requests.map((q) => `
      <div class="item domain-row">
        <span style="min-width:0"><a class="mono" href="${esc(link(q.url || q.domain))}" target="_blank" rel="noopener" title="${esc(q.url || "")}">${esc(q.domain)}</a>
          <span class="muted small"> · ${q.ts ? esc(fmtTime.format(new Date(tsMs(q.ts)))) : ""}</span></span>
        <button class="btn sm primary" data-approve="${esc(q.id)}">سماح</button>
        <button class="btn sm" data-reject="${esc(q.id)}">رفض</button>
      </div>`).join("")}</div>
  </div>` : ""}
  <div class="cols">
    <div class="card">
      <h2>إعدادات المواقع</h2>
      <div class="setting"><div class="txt"><b>تسجيل المواقع التي تتم زيارتها</b><span class="small muted">كل التطبيقات والمتصفحات، عبر فلتر DNS على الجهاز.</span></div>
        <label class="switch"><input type="checkbox" data-web="log" ${web.log !== false ? "checked" : ""}></label></div>
      <div class="setting"><div class="txt"><b>منع المواقع المحظورة</b><span class="small muted">يمنع فتح المواقع في القائمة على كل التطبيقات، ويمنع تغيير VPN و DNS الخاص.</span></div>
        <label class="switch"><input type="checkbox" data-web="filter" ${web.filter ? "checked" : ""}></label></div>
      <div class="setting"><div class="txt"><b>السماح فقط بالمواقع المسموحة</b><span class="small muted">يمنع كل المواقع في المتصفحات ما عدا قائمة «المواقع المسموحة». يستطيع الطفل إرسال طلب للسماح بموقع. المتصفحات غير المدعومة تُمنع.</span></div>
        <label class="switch"><input type="checkbox" data-web="allowOnly" ${web.allowOnly ? "checked" : ""}></label></div>
      <div class="setting"><div class="txt"><b>البحث الآمن</b><span class="small muted">يفرض البحث الآمن في Google و Bing ووضع يوتيوب المقيّد.</span></div>
        <label class="switch"><input type="checkbox" data-web="safeSearch" ${web.safeSearch ? "checked" : ""}></label></div>
    </div>
    <div class="card stack">
      <h2>المواقع المحظورة</h2>
      <form class="row" id="addDomain">
        <input class="input mono" style="flex:1;min-width:160px" id="domain" placeholder="example.com">
        <button class="btn primary" type="submit">حظر</button>
      </form>
      <div class="row small">${Object.keys(PRESETS).map((k) => `<button class="btn sm" data-preset="${esc(k)}">+ ${esc(k)}</button>`).join("")}</div>
      <div class="chips">${[...blocked].sort().map((d) => `<span class="chip"><span class="mono">${esc(d)}</span><button title="إلغاء الحظر" data-unblock="${esc(d)}">×</button></span>`).join("") || `<span class="muted small">لا توجد مواقع محظورة</span>`}</div>
      ${!web.filter && blocked.size ? `<div class="notice">فعّل «منع المواقع المحظورة» لتطبيق هذه القائمة.</div>` : ""}
    </div>
  </div>
  <div class="card stack" style="margin-top:12px">
    <h2>المواقع المسموحة ${web.allowOnly ? `<span class="badge ok">مفعّل</span>` : `<span class="badge">غير مفعّل</span>`}</h2>
    <p class="small muted" style="margin:0">تُستعمل عند تفعيل «السماح فقط بالمواقع المسموحة». السماح بنطاق يسمح بكل صفحاته الفرعية.</p>
    <form class="row" id="addAllowed">
      <input class="input mono" style="flex:1;min-width:160px" id="allowedDomain" placeholder="wikipedia.org">
      <button class="btn primary" type="submit">سماح</button>
    </form>
    <div class="chips">${[...allowed].sort().map((d) => `<span class="chip"><a class="mono" href="https://${esc(d)}" target="_blank" rel="noopener">${esc(d)}</a><button title="إزالة" data-disallow="${esc(d)}">×</button></span>`).join("") || `<span class="muted small">لا توجد مواقع مسموحة بعد</span>`}</div>
  </div>
  <div class="card stack" style="margin-top:12px">
    <div class="row">
      <h2 style="margin:0">سجل التصفح</h2>
      <span class="spacer"></span>
      <select class="input" id="webDay" style="width:auto">${days.map((d) => `<option value="${dayKey(d)}" ${dayKey(d) === S.webDay ? "selected" : ""}>${esc(fmtDate.format(d))}</option>`).join("")}</select>
      <label class="switch"><input type="checkbox" id="hideSys" ${S.hideSystemDomains ? "checked" : ""}>إخفاء نطاقات النظام</label>
    </div>
    ${S.web === null ? `<div class="empty">جارٍ التحميل…</div>` : ""}
    ${urls.length ? `<h3>صفحات من المتصفح</h3><div class="list">${urls.slice(0, 100).map((u) => `
      <div class="item domain-row"><a class="mono" href="${esc(link(u.u))}" target="_blank" rel="noopener" title="${esc(u.u)}">${esc(u.u)}</a><span class="muted small">${esc(fmtTime.format(new Date(u.t || 0)))}</span><span></span></div>`).join("")}</div>` : ""}
    <h3>النطاقات (${domains.length})</h3>
    <div class="list">${domains.slice(0, 400).map((d) => {
      const isB = isBlockedDomain(d.name, blocked);
      return `<div class="item domain-row">
        <a class="mono" href="https://${esc(d.name)}" target="_blank" rel="noopener" title="${esc(d.name)}">${d.b ? "🚫 " : ""}${esc(d.name)}</a>
        <span class="muted small">${esc(fmtTime.format(new Date(d.t)))} · ${d.c}×</span>
        ${isB ? `<button class="btn sm" data-unblock="${esc(normDomain(d.name) || d.name)}">إلغاء الحظر</button>` : `<button class="btn sm danger" data-block="${esc(d.name)}">حظر</button>`}
      </div>`;
    }).join("") || (S.web !== null ? `<div class="empty">لا يوجد سجل لهذا اليوم</div>` : "")}</div>
  </div>`;

  el.querySelectorAll("[data-web]").forEach((i) => (i.onchange = () => run(updateDoc(devRef(), { [`policy.web.${i.dataset.web}`]: i.checked }), "تم الحفظ")));
  document.getElementById("addDomain").onsubmit = (e) => {
    e.preventDefault();
    const d = normDomain(document.getElementById("domain").value);
    if (!d) return toast("اكتب نطاقاً صحيحاً مثل example.com");
    run(updateDoc(devRef(), { "policy.web.blocked": arrayUnion(d) }), `تم حظر ${d}`);
  };
  el.querySelectorAll("[data-preset]").forEach((b) => (b.onclick = () => run(updateDoc(devRef(), { "policy.web.blocked": arrayUnion(...PRESETS[b.dataset.preset]) }), "تمت الإضافة")));
  el.querySelectorAll("[data-unblock]").forEach((b) => (b.onclick = () => {
    const d = b.dataset.unblock;
    // remove the domain itself and any parent entry that blocks it
    const toRemove = [...blocked].filter((x) => x === d || isBlockedDomain(d, new Set([x])));
    run(updateDoc(devRef(), { "policy.web.blocked": arrayRemove(...(toRemove.length ? toRemove : [d])) }), "تم إلغاء الحظر");
  }));
  el.querySelectorAll("[data-block]").forEach((b) => (b.onclick = () => {
    const d = normDomain(b.dataset.block);
    if (!d) return;
    run(updateDoc(devRef(), { "policy.web.blocked": arrayUnion(d) }), `تم حظر ${d}`);
  }));
  document.getElementById("addAllowed").onsubmit = (e) => {
    e.preventDefault();
    const d = normDomain(document.getElementById("allowedDomain").value);
    if (!d) return toast("اكتب نطاقاً صحيحاً مثل example.com");
    run(updateDoc(devRef(), { "policy.web.allowed": arrayUnion(d) }), `تم السماح بـ ${d}`);
  };
  el.querySelectorAll("[data-disallow]").forEach((b) => (b.onclick = () =>
    run(updateDoc(devRef(), { "policy.web.allowed": arrayRemove(b.dataset.disallow) }), "تمت الإزالة")));
  el.querySelectorAll("[data-approve]").forEach((b) => (b.onclick = () => {
    const q = S.requests.find((x) => x.id === b.dataset.approve);
    const d = q && normDomain(q.domain);
    if (!d) return;
    run(Promise.all([
      updateDoc(devRef(), { "policy.web.allowed": arrayUnion(d), "policy.web.blocked": arrayRemove(d) }),
      updateDoc(doc(db, "devices", S.deviceId, "requests", q.id), { status: "approved", doneAt: serverTimestamp() }),
    ]), `تم السماح بـ ${d}`);
  }));
  el.querySelectorAll("[data-reject]").forEach((b) => (b.onclick = () =>
    run(updateDoc(doc(db, "devices", S.deviceId, "requests", b.dataset.reject), { status: "rejected", doneAt: serverTimestamp() }), "تم الرفض")));
  document.getElementById("webDay").onchange = (e) => { S.webDay = e.target.value; listenWeb(); renderDevice(); };
  document.getElementById("hideSys").onchange = (e) => { S.hideSystemDomains = e.target.checked; renderDevice(); };
}

// ----- tab: rules -----
// Rules that also work in "normal protection" (enforced with the owner PIN); the rest need Device Owner
const RULES_NORMAL = new Set(["lock_settings", "no_install_apps", "no_uninstall_apps", "no_install_unknown_sources"]);
const RULES = [
  ["no_install_apps", "منع تثبيت التطبيقات", "يمنع التثبيت من المتجر (Play Store) ومن الملفات. في الحماية العادية: يُقفل المتجر برمز المالك."],
  ["no_uninstall_apps", "منع حذف التطبيقات", "في الحماية العادية: تُقفل شاشة الحذف برمز المالك."],
  ["no_install_unknown_sources", "منع التثبيت من مصادر غير معروفة", "ملفات APK من خارج المتجر (واتساب، المتصفح…). لا يمنع Play Store."],
  ["no_control_apps", "منع إيقاف التطبيقات أو مسح بياناتها", "يحمي تطبيق المراقبة من الإيقاف."],
  ["lock_settings", "قفل تطبيق الإعدادات برمز المالك", "يحتاج خدمة إمكانية الوصول."],
  ["no_config_date_time", "منع تغيير التاريخ والوقت", "يمنع التحايل على الحدود اليومية."],
  ["no_factory_reset", "منع إعادة ضبط المصنع", ""],
  ["no_safe_boot", "منع الوضع الآمن", ""],
  ["no_add_user", "منع إضافة مستخدمين", ""],
  ["no_modify_accounts", "منع إضافة أو حذف الحسابات", ""],
  ["no_config_wifi", "منع تغيير إعدادات Wi-Fi", ""],
  ["no_config_bluetooth", "منع تغيير إعدادات البلوتوث", ""],
  ["no_config_tethering", "منع نقطة الاتصال", ""],
  ["no_config_vpn", "منع تغيير VPN", "يُفعَّل تلقائياً مع فلترة المواقع."],
  ["no_usb_file_transfer", "منع نقل الملفات عبر USB", ""],
  ["no_share_location", "منع تغيير إعدادات الموقع", ""],
  ["no_outgoing_calls", "منع المكالمات الصادرة", ""],
  ["no_sms", "منع الرسائل القصيرة", ""],
  ["no_adjust_volume", "منع تغيير مستوى الصوت", ""],
  ["camera_disabled", "تعطيل الكاميرا", ""],
  ["screen_capture_disabled", "منع لقطات الشاشة", ""],
  ["no_debugging_features", "منع خيارات المطوّر و adb", "⚠️ بعد تفعيله لن تستطيع استخدام adb على الجهاز حتى تلغيه من هنا."],
];

const WIN_RULES = [
  ["win_no_taskmgr", "منع مدير المهام", "يمنع إغلاق البرامج أو الحماية من مدير المهام."],
  ["win_no_settings", "منع الإعدادات ولوحة التحكم", ""],
  ["win_no_regedit", "منع محرر السجل (regedit)", ""],
  ["win_no_cmd", "منع موجه الأوامر (cmd)", ""],
  ["win_no_run", "منع نافذة «تشغيل» (Win+R)", ""],
  ["win_no_store", "منع متجر مايكروسوفت", "يعمل في إصدارات ويندوز Pro / Enterprise / Education."],
  ["win_no_msi", "منع تثبيت برامج MSI للمستخدم", ""],
  ["win_no_usb_storage", "منع الفلاشات وأقراص USB", "يطبّق على كل الحسابات."],
];

function tabRules(el) {
  const p = pol();
  const r = p.restrictions || {};
  const full = !!(dev().status && dev().status.deviceOwner);
  const pc = isPc(dev());
  el.innerHTML = `
  <div class="cols">
    <div class="card stack">
      <h2>رمز المالك</h2>
      <p class="small muted">يُطلب لفتح التطبيقات المقفلة، والسماح المؤقت بالتطبيقات والمواقع المحظورة، وفتح الإعدادات وتطبيق Zemory Family Guard على الجهاز.</p>
      <div>${p.pinHash ? `<span class="badge ok">✓ تم تعيين الرمز</span>` : `<span class="badge warn">لم يتم تعيين رمز</span>`}</div>
      <form class="row" id="pinForm">
        <input class="input mono" id="pin" type="password" inputmode="numeric" pattern="[0-9]{4,8}" placeholder="4–8 أرقام" style="flex:1;min-width:140px">
        <button class="btn primary" type="submit">${p.pinHash ? "تغيير" : "تعيين"}</button>
      </form>
    </div>
    <div class="card">
      <h2>القيود على الجهاز</h2>
      ${pc ? `<p class="small muted">تُطبَّق على حساب الطفل في ويندوز. يجب أن يكون حساب الطفل «مستخدماً عادياً» وليس مسؤولاً.</p>
      ${WIN_RULES.map(([k, t, h]) => `
        <div class="setting"><div class="txt"><b>${esc(t)}</b>${h ? `<span class="small muted">${esc(h)}</span>` : ""}</div>
          <label class="switch"><input type="checkbox" data-rule="${k}" ${r[k] ? "checked" : ""}></label></div>`).join("")}` : `
      ${full ? `<p class="small muted">الجهاز في «حماية كاملة»: كل القيود تعمل.</p>`
        : `<p class="notice">الجهاز في «حماية عادية»: القيود المعلّمة بـ «تحتاج حماية كاملة» لا يطبّقها أندرويد. فعّل الحماية الكاملة عبر الكمبيوتر لتعمل كلها.</p>`}
      ${RULES.map(([k, t, h]) => `
        <div class="setting"><div class="txt"><b>${esc(t)}</b>${!full && !RULES_NORMAL.has(k) ? ` <span class="badge warn">تحتاج حماية كاملة</span>` : ""}${h ? `<span class="small muted">${esc(h)}</span>` : ""}</div>
          <label class="switch"><input type="checkbox" data-rule="${k}" ${r[k] ? "checked" : ""}></label></div>`).join("")}`}
    </div>
  </div>`;
  document.getElementById("pinForm").onsubmit = async (e) => {
    e.preventDefault();
    const pin = document.getElementById("pin").value.trim();
    if (!/^[0-9]{4,8}$/.test(pin)) return toast("الرمز من 4 إلى 8 أرقام");
    run(updateDoc(devRef(), { "policy.pinHash": await sha256("fg:" + pin) }), "تم حفظ الرمز");
  };
  el.querySelectorAll("[data-rule]").forEach((i) => (i.onchange = () => {
    if (i.dataset.rule === "no_debugging_features" && i.checked && !confirm("سيتم تعطيل adb على الجهاز. متابعة؟")) { i.checked = false; return; }
    run(updateDoc(devRef(), new FieldPath("policy", "restrictions", i.dataset.rule), i.checked), "تم الحفظ");
  }));
}

// ----- tab: commands -----
const CMD_LABEL = { install: "تثبيت", uninstall: "حذف", enable_system_app: "إعادة تثبيت", lock_now: "قفل الشاشة", reboot: "إعادة تشغيل", sync: "مزامنة" };
const STATUS_BADGE = { pending: ["", "بالانتظار"], running: ["warn", "قيد التنفيذ"], waiting: ["warn", "بانتظار تأكيد"], done: ["ok", "تم"], error: ["danger", "فشل"] };

function tabCommands(el) {
  const d = dev();
  const pc = isPc(d);
  el.innerHTML = `
  <div class="cols">
    <div class="stack">
      <div class="card stack">
        <h2>${pc ? "تثبيت برنامج" : "تثبيت تطبيق"}</h2>
        <p class="small muted">${pc ? "ضع رابطاً مباشراً لملف MSI (تثبيت صامت) أو EXE (يُشغَّل بالخيار /S)." : "ضع رابطاً مباشراً لملف APK. يُثبَّت بصمت عندما يكون التطبيق «مالك الجهاز»."}</p>
        <form class="row" id="installForm">
          <input class="input mono" id="apkUrl" type="url" placeholder="${pc ? "https://…/setup.msi" : "https://…/app.apk"}" style="flex:1;min-width:180px" required>
          <button class="btn primary" type="submit">تثبيت</button>
        </form>
      </div>
      ${pc ? "" : `<div class="card stack">
        <h2>تحديث تطبيق الجهاز</h2>
        <form class="row" id="selfForm">
          <input class="input mono" id="selfUrl" type="url" value="${esc(childApkUrl())}" placeholder="رابط FamilyGuard-Child.apk" style="flex:1;min-width:180px" required>
          <button class="btn" type="submit">تحديث</button>
        </form>
      </div>`}
      <div class="card stack">
        <h2>أوامر سريعة</h2>
        <div class="row">
          <button class="btn" data-cmd="lock_now">🔒 قفل الشاشة الآن</button>
          <button class="btn" data-cmd="sync">🔄 مزامنة الآن</button>
          <button class="btn" data-cmd="reboot">⏻ إعادة التشغيل</button>
        </div>
      </div>
      <div class="card stack">
        <h2>الجهاز</h2>
        <form class="row" id="renameForm">
          <input class="input" id="devName" value="${esc(d.name || "")}" style="flex:1;min-width:160px">
          <select class="input" id="devType" style="width:auto">
            <option value="phone" ${d.type === "phone" ? "selected" : ""}>📱 هاتف</option>
            <option value="tablet" ${d.type === "tablet" ? "selected" : ""}>💻 تابلت</option>
            <option value="tv" ${d.type === "tv" ? "selected" : ""}>📺 تلفاز</option>
            <option value="pc" ${d.type === "pc" ? "selected" : ""}>🖥️ كمبيوتر ويندوز</option>
          </select>
          <button class="btn" type="submit">حفظ</button>
        </form>
        <p class="small muted mono">ID: ${esc(d.id)}</p>
        <div><button class="btn danger sm" id="delDevice">حذف الجهاز من القائمة</button></div>
      </div>
    </div>
    <div class="card">
      <h2>آخر الأوامر</h2>
      <div class="list">${S.commands.map((c) => {
        const [cls, txt] = STATUS_BADGE[c.status] || ["", c.status];
        const target = c.pkg || c.url || "";
        return `<div class="item">
          <div class="row"><b>${esc(CMD_LABEL[c.type] || c.type)}</b><span class="badge ${cls}">${esc(txt)}</span><span class="spacer"></span>
          <span class="muted small">${c.createdAt ? esc(fmtTime.format(new Date(tsMs(c.createdAt)))) : ""}</span></div>
          ${target ? `<div class="mono small muted" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(target)}</div>` : ""}
          ${c.result && c.status === "error" ? `<div class="small" style="color:var(--danger)">${esc(c.result)}</div>` : ""}
        </div>`;
      }).join("") || `<div class="empty">لا توجد أوامر بعد</div>`}</div>
    </div>
  </div>`;
  document.getElementById("installForm").onsubmit = (e) => {
    e.preventDefault();
    sendCommand({ type: "install", url: document.getElementById("apkUrl").value.trim() }, "تم إرسال أمر التثبيت");
  };
  const selfForm = document.getElementById("selfForm");
  if (selfForm) selfForm.onsubmit = (e) => {
    e.preventDefault();
    const url = document.getElementById("selfUrl").value.trim();
    try { localStorage.setItem("fg_apk", url); } catch (_) {}
    sendCommand({ type: "install", url }, "تم إرسال أمر التحديث");
  };
  el.querySelectorAll("[data-cmd]").forEach((b) => (b.onclick = () => {
    if (b.dataset.cmd === "reboot" && !confirm("إعادة تشغيل الجهاز؟")) return;
    sendCommand({ type: b.dataset.cmd }, "تم إرسال الأمر");
  }));
  document.getElementById("renameForm").onsubmit = (e) => {
    e.preventDefault();
    run(updateDoc(devRef(), { name: document.getElementById("devName").value.trim(), type: document.getElementById("devType").value }), "تم الحفظ");
  };
  document.getElementById("delDevice").onclick = () => {
    if (!confirm("حذف الجهاز من القائمة؟ لإزالة الحماية فعلياً استخدم «إزالة الحماية» من التطبيق على الجهاز.")) return;
    const id = S.deviceId;
    stopDetail();
    S.deviceId = null;
    run(deleteDoc(doc(db, "devices", id)), "تم الحذف");
  };
}

// refresh "online" badges and relative times
setInterval(() => { if (S.user && !document.activeElement?.matches("input,select,textarea")) render(); }, 60_000);
