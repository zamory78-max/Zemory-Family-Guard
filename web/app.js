// FamilyGuard parent dashboard (works in the browser and inside the parent Android app).
import { initializeApp } from "https://www.gstatic.com/firebasejs/10.14.1/firebase-app.js";
import {
  getAuth, onAuthStateChanged, signInWithEmailAndPassword, createUserWithEmailAndPassword,
  signOut, sendPasswordResetEmail,
} from "https://www.gstatic.com/firebasejs/10.14.1/firebase-auth.js";
import {
  getFirestore, collection, query, where, orderBy, limit, onSnapshot, doc, updateDoc, addDoc,
  deleteDoc, serverTimestamp, arrayUnion, arrayRemove, deleteField, FieldPath, getDocs,
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

// In-page confirmation (window.confirm is silently ignored inside the Android parent app)
function askConfirm(message, okText = "تأكيد", danger = false) {
  return new Promise((resolve) => {
    openModal(`<h2>تأكيد</h2><p style="white-space:pre-line;margin:0">${esc(message)}</p>
      <div class="row"><span class="spacer"></span>
        <button class="btn" id="cfNo">إلغاء</button>
        <button class="btn ${danger ? "danger" : "primary"}" id="cfYes">${esc(okText)}</button></div>`);
    document.getElementById("cfNo").onclick = () => { closeModal(); resolve(false); };
    document.getElementById("cfYes").onclick = () => { closeModal(); resolve(true); };
  });
}
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

// Used by the Android parent app ("forgot app PIN" signs the owner out)
window.fgSignOut = () => { try { signOut(auth); } catch (_) {} };

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
  events: [],
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
  $user.innerHTML = `<span>${esc(u.email)}</span>${window.ZemoryApp ? `<button class="btn sm ghost" id="appLock" title="قفل التطبيق">🔒</button>` : ""}<button class="btn sm ghost" id="logout">خروج</button>`;
  document.getElementById("logout").onclick = () => signOut(auth);
  const lockBtn = document.getElementById("appLock");
  if (lockBtn) lockBtn.onclick = () => window.ZemoryApp.openSecurity();
  S.unsubDevices = onSnapshot(
    query(collection(db, "devices"), where("ownerUid", "==", u.uid)),
    (qs) => {
      S.devices = new Map();
      qs.forEach((d) => { const v = d.data(); if (!v.unlinked) S.devices.set(d.id, { id: d.id, ...v }); });
      if (S.deviceId && !S.devices.has(S.deviceId)) { stopDetail(); S.deviceId = null; }
      if (BG) watchRequests();
      render();
    },
    (e) => { console.error(e); toast("تعذّر قراءة الأجهزة: " + e.code); },
  );
  render();
});

// ---------- phone notifications for the child's requests (runs only in the app's hidden copy) ----------
const reqWatch = new Map();
function watchRequests() {
  for (const [id, un] of reqWatch) if (!S.devices.has(id)) { un(); reqWatch.delete(id); }
  for (const id of S.devices.keys()) {
    if (reqWatch.has(id)) continue;
    reqWatch.set(id, onSnapshot(collection(db, "devices", id, "requests"), (qs) => {
      let seen = [];
      try { seen = JSON.parse(localStorage.getItem("fgNotified") || "[]"); } catch {}
      qs.docs.forEach((d) => {
        const q = d.data();
        const t = tsMs(q.ts);
        if (q.status !== "pending" || !t || Date.now() - t > 24 * 3600 * 1000) return;
        const key = `${id}/${d.id}/${t}`;
        if (seen.includes(key)) return;
        seen.push(key);
        const name = (S.devices.get(id) || {}).name || "جهاز";
        const body = q.kind === "app"
          ? `يطلب فتح تطبيق «${q.label || q.pkg}»${WHY[q.why] ? " — " + WHY[q.why] : ""}`
          : `يطلب فتح الموقع ${q.domain || ""}`;
        try { window.ZemoryApp && window.ZemoryApp.notify && window.ZemoryApp.notify(`📨 طلب جديد من ${name}`, body, key); } catch {}
      });
      try { localStorage.setItem("fgNotified", JSON.stringify(seen.slice(-300))); } catch {}
    }));
  }
}

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
const BG = new URLSearchParams(location.search).get("bg") === "1"; // hidden copy used for phone notifications
function render() {
  if (!S.user || BG) return;
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
  S.events = [];
  S.unsubDetail.push(onSnapshot(
    query(collection(db, "devices", id, "events"), orderBy("t", "desc"), limit(300)),
    (qs) => { S.events = qs.docs.map((d) => ({ id: d.id, ...d.data() })); if (S.tab === "reports") renderDevice(); },
  ));
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
    if (st.media === false) out.push(["سجل الفيديوهات غير مفعّل", "warn"]);
  }
  if (st.vpnNeeded && st.vpn === false) out.push(["فلتر المواقع متوقف", "danger"]);
  if (!st.deviceOwner && st.admin === false) out.push(["الحماية من الحذف غير مفعّلة", "warn"]);
  if (st.battery === false && d.type !== "tv") out.push(["قد يوقفه توفير البطارية", "warn"]);
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
          <span class="badge ${on ? "ok" : ""}"><span class="dot"></span>${on ? "متصل" : "غير متصل"}</span>
          <button class="btn sm ghost" data-del="${esc(d.id)}" title="حذف الجهاز">🗑</button></div>
        <b>${esc(d.name || "جهاز")}</b>
        <span class="muted small">${esc(d.model || "")} · ${esc(ago(tsMs(d.lastSeen)))}</span>
        <div class="row">${modeBadge(d)}${issueBadges(d)}</div>
      </div>`;
    }).join("")}
    <div class="card device-card add" id="addDevice"><span style="font-size:28px">＋</span><span>إضافة جهاز</span></div>
  </div>`;
  $app.querySelectorAll("[data-open]").forEach((el) => (el.onclick = (e) => {
    const del = e.target.closest("[data-del]");
    if (del) { e.stopPropagation(); confirmDeleteDevice(del.dataset.del); return; }
    openDevice(el.dataset.open);
  }));
  document.getElementById("addDevice").onclick = showAddDevice;
}

// ---------- add device (child account) ----------
const DEFAULT_POLICY = {
  blocked: [], hidden: [], pinLocked: [], limits: {}, pinHash: "",
  restrictions: {
    no_factory_reset: true, no_add_user: true, no_safe_boot: true, no_install_unknown_sources: true,
    no_config_date_time: true, no_control_apps: true, lock_settings: true, lock_youtube_settings: true,
  },
  web: { filter: false, log: true, safeSearch: false, blockYoutube: false, blockAds: false, blocked: [], allowOnly: false, allowed: [], blockedPaths: [] },
  bedtime: { enabled: false, start: "21:00", end: "07:00", allowed: [] },
  activity: { enabled: false, apps: [] },
  screen: { enabled: false, mode: "allowed", allowed: [], days: Object.fromEntries([0, 1, 2, 3, 4, 5, 6].map((i) => [String(i), { on: true, start: "07:00", end: "21:00" }])) },
  pauseAll: false, lockNow: false, overrideMinutes: 30, unlockMinutes: 10, maxAttempts: 5, lockoutMinutes: 5,
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
  ["screen", "الشاشة"],
  ["web", "المواقع"],
  ["requests", "طلبات الطفل"],
  ["reports", "التقارير"],
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
  <div class="row" style="margin-top:12px"><h1 style="margin:0">${typeIcon(d.type)} ${esc(d.name || "جهاز")}</h1><span class="spacer"></span>
    <button class="btn sm danger" id="delTop">🗑 حذف الجهاز</button></div>
  <div class="row small muted">${esc(d.model || "")} · ${isPc(d) ? "Windows build" : "Android SDK"} ${esc(d.sdk ?? "?")} · التطبيق ${esc(d.appVersion || "?")}</div>
  <div class="row" style="margin-top:8px">${modeBadge(d)}${issueBadges(d)}</div>
  <nav class="tabs">${TABS.map(([k, t]) => `<button class="tab ${S.tab === k ? "active" : ""}" data-tab="${k}">${t}${k === "requests" && S.requests.length ? ` <span class="count">${S.requests.length}</span>` : ""}</button>`).join("")}</nav>
  <section id="tabBody"></section>`;
  document.getElementById("back").onclick = () => { stopDetail(); S.deviceId = null; renderDevices(); };
  document.getElementById("delTop").onclick = () => confirmDeleteDevice(d.id);
  $app.querySelectorAll("[data-tab]").forEach((b) => (b.onclick = () => { S.tab = b.dataset.tab; renderDevice(); }));
  const body = document.getElementById("tabBody");
  ({ apps: tabApps, time: tabTime, screen: tabScreen, web: tabWeb, requests: tabRequests, reports: tabReports, rules: tabRules, commands: tabCommands }[S.tab])(body);

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
      askConfirm(`حذف «${label}» من الجهاز؟\nلتطبيقات النظام استخدم «إخفاء» بدلاً من الحذف.`, "حذف", true)
        .then((ok) => ok && sendCommand({ type: "uninstall", pkg }, "تم إرسال أمر الحذف"));
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
          <div class="txt"><b>🔒 قفل الشاشة بالكامل الآن</b><span class="small muted">يقفل شاشة الجهاز فوراً حتى تلغيه، ولا يستطيع الطفل فتح أي شيء.</span></div>
          <label class="switch"><input type="checkbox" id="lockNow" ${p.lockNow ? "checked" : ""}></label>
        </div>
        <div class="setting">
          <div class="txt"><b>⏸ إيقاف كل التطبيقات الآن</b><span class="small muted">يوقف كل التطبيقات (ما عدا المسموح بها أدناه)، لكن الشاشة تبقى تعمل.</span></div>
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
  document.getElementById("lockNow").onchange = (e) => run(updateDoc(devRef(), { "policy.lockNow": e.target.checked }), e.target.checked ? "تم قفل الشاشة" : "تم رفع القفل");
  document.getElementById("pauseAll").onchange = (e) => run(updateDoc(devRef(), { "policy.pauseAll": e.target.checked }), e.target.checked ? "تم إيقاف التطبيقات" : "تم الاستئناف");
  document.getElementById("bedOn").onchange = (e) => run(updateDoc(devRef(), { "policy.bedtime.enabled": e.target.checked }), "تم الحفظ");
  document.getElementById("bedStart").onchange = (e) => run(updateDoc(devRef(), { "policy.bedtime.start": e.target.value }), "تم الحفظ");
  document.getElementById("bedEnd").onchange = (e) => run(updateDoc(devRef(), { "policy.bedtime.end": e.target.value }), "تم الحفظ");
  document.getElementById("allowed").onchange = (e) => {
    const pkg = e.target.value;
    run(updateDoc(devRef(), { "policy.bedtime.allowed": e.target.checked ? arrayUnion(pkg) : arrayRemove(pkg) }), "تم الحفظ");
  };
}

// ----- tab: screen (weekly schedule) -----
const WEEKDAYS = ["الأحد", "الإثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"];
const SCREEN_DAY = { on: true, start: "07:00", end: "21:00" };

/** The saved screen schedule with defaults filled in for anything missing. */
function screenOf(p) {
  const sc = p.screen || {};
  const days = {};
  for (let i = 0; i < 7; i++) days[i] = { ...SCREEN_DAY, ...((sc.days || {})[i] || {}) };
  return { enabled: !!sc.enabled, mode: sc.mode === "lock" ? "lock" : "allowed", allowed: sc.allowed || [], days };
}
const hhmm = (t) => { const [h, m] = String(t || "").split(":").map(Number); return Number.isFinite(h) && Number.isFinite(m) ? h * 60 + m : null; };
const dayValid = (d) => !d.on || (hhmm(d.start) !== null && hhmm(d.end) !== null && hhmm(d.start) < hhmm(d.end));

/** "Open now / closed, opens at ..." using this browser's clock (same time zone as the device in a family). */
function screenStatus(sc) {
  if (!sc.enabled) return "الجدول غير مفعّل: لا يوجد قيد على الشاشة.";
  const n = new Date();
  const d = sc.days[n.getDay()];
  const now = n.getHours() * 60 + n.getMinutes();
  if (!d.on || !dayValid(d)) return "اليوم بلا قيد: الشاشة مفتوحة طوال اليوم.";
  if (now < hhmm(d.start)) return `🔴 الشاشة مغلقة الآن، تُفتح الساعة ${d.start}.`;
  if (now >= hhmm(d.end)) return `🔴 الشاشة مغلقة الآن (انتهى الوقت ${d.end}).`;
  return `🟢 الشاشة مفتوحة الآن حتى ${d.end}.`;
}

function tabScreen(el) {
  const p = pol();
  const sc = screenOf(p);
  const allowed = new Set(sc.allowed);
  const apps = appList().filter((a) => a.launchable).sort((a, b) => a.label.localeCompare(b.label, "ar"));
  const q = (S.screenQ || "").trim().toLowerCase();
  const shown = q ? apps.filter((a) => a.label.toLowerCase().includes(q) || a.pkg.toLowerCase().includes(q)) : apps;
  el.innerHTML = `
  <div class="cols">
    <div class="card stack">
      <div class="setting">
        <div class="txt"><b>🕒 جدول الشاشة الأسبوعي</b><span class="small muted">حدّد لكل يوم وقت فتح الشاشة (من – إلى). خارج هذا الوقت يُطبَّق الخيار الذي تختاره أدناه. اليوم المُعطَّل بلا قيد.</span></div>
        <label class="switch"><input type="checkbox" id="scOn" ${sc.enabled ? "checked" : ""}></label>
      </div>
      <div class="notice" id="scStatus">${esc(screenStatus(sc))}</div>
      <div class="list">
        ${WEEKDAYS.map((name, i) => { const d = sc.days[i]; return `
        <div class="item" data-day="${i}">
          <div class="row" style="gap:8px;flex-wrap:wrap">
            <label class="switch" style="min-width:96px"><input type="checkbox" data-f="on" ${d.on ? "checked" : ""}> <b>${name}</b></label>
            <label class="field" style="flex:1;min-width:104px"><span>من</span><input class="input" type="time" data-f="start" value="${esc(d.start)}" ${d.on ? "" : "disabled"}></label>
            <label class="field" style="flex:1;min-width:104px"><span>إلى</span><input class="input" type="time" data-f="end" value="${esc(d.end)}" ${d.on ? "" : "disabled"}></label>
            <button class="btn sm" data-copy="${i}" title="نسخ أوقات هذا اليوم إلى كل الأيام">نسخ للكل</button>
          </div>
          ${d.on ? "" : `<div class="small muted">بلا قيد في هذا اليوم</div>`}
        </div>`; }).join("")}
      </div>
      <p class="small muted" style="margin:0">وقت البداية يجب أن يسبق النهاية في اليوم نفسه. قبل البداية وبعد النهاية تُعدّ الشاشة مغلقة.</p>
    </div>
    <div class="card stack">
      <h2>ماذا يحدث بعد انتهاء الوقت؟</h2>
      <label class="setting" style="cursor:pointer">
        <input type="radio" name="scMode" value="allowed" ${sc.mode === "allowed" ? "checked" : ""}>
        <div class="txt"><b>تعمل التطبيقات المسموحة فقط</b><span class="small muted">تُوقف كل التطبيقات ما عدا القائمة أدناه. الشاشة تبقى تعمل.</span></div>
      </label>
      <label class="setting" style="cursor:pointer">
        <input type="radio" name="scMode" value="lock" ${sc.mode === "lock" ? "checked" : ""}>
        <div class="txt"><b>قفل الشاشة بالكامل</b><span class="small muted">لا يستطيع الطفل فتح أي شيء حتى يبدأ الوقت التالي. يمكنك السماح مؤقتاً برمز المالك من الجهاز.</span></div>
      </label>
      <div ${sc.mode === "allowed" ? "" : 'style="opacity:.5"'}>
        <h3>تطبيقات مسموحة بعد انتهاء الوقت <span class="muted small">(${allowed.size})</span></h3>
        <input class="input" id="scQ" placeholder="ابحث عن تطبيق…" value="${esc(S.screenQ || "")}" style="margin-bottom:8px">
        <div class="chips" id="scAllowed">
          ${shown.map((a) => `<label class="chip" style="padding:4px 10px"><input type="checkbox" value="${esc(a.pkg)}" ${allowed.has(a.pkg) ? "checked" : ""}> ${esc(a.label)}</label>`).join("") || `<span class="muted small">${apps.length ? "لا نتائج" : "لا توجد قائمة تطبيقات بعد"}</span>`}
        </div>
        <p class="small muted" style="margin:8px 0 0">يُنصح بإضافة الاتصال والمدرسة والتطبيقات التعليمية.</p>
      </div>
    </div>
  </div>`;

  const saveAll = (next, msg = "تم الحفظ") => run(updateDoc(devRef(), { "policy.screen": next }), msg);
  const rowDay = (row) => ({
    on: row.querySelector('[data-f="on"]').checked,
    start: row.querySelector('[data-f="start"]').value || SCREEN_DAY.start,
    end: row.querySelector('[data-f="end"]').value || SCREEN_DAY.end,
  });

  document.getElementById("scOn").onchange = (e) => saveAll({ ...sc, enabled: e.target.checked });
  el.querySelectorAll('input[name="scMode"]').forEach((r) => (r.onchange = () => saveAll({ ...sc, mode: r.value })));
  el.querySelectorAll("[data-day]").forEach((row) => {
    const i = Number(row.dataset.day);
    row.querySelectorAll("[data-f]").forEach((inp) => (inp.onchange = () => {
      const d = rowDay(row);
      if (!dayValid(d)) { toast("وقت البداية يجب أن يسبق وقت النهاية"); renderDevice(); return; }
      saveAll({ ...sc, days: { ...sc.days, [i]: d } });
    }));
    row.querySelector("[data-copy]").onclick = () => {
      const d = rowDay(row);
      if (!dayValid(d)) { toast("وقت البداية يجب أن يسبق وقت النهاية"); return; }
      saveAll({ ...sc, days: Object.fromEntries([0, 1, 2, 3, 4, 5, 6].map((k) => [k, { ...d }])) }, "تم النسخ إلى كل الأيام");
    };
  });
  document.getElementById("scQ").oninput = (e) => {
    S.screenQ = e.target.value;
    const pos = e.target.selectionStart;
    renderDevice();
    const inp = document.getElementById("scQ");
    if (inp) { inp.focus(); inp.setSelectionRange(pos, pos); }
  };
  document.getElementById("scAllowed").onchange = (e) => {
    const pkg = e.target.value;
    // make sure the schedule exists on the device, then add / remove the app
    run((p.screen ? Promise.resolve() : updateDoc(devRef(), { "policy.screen": sc }))
      .then(() => updateDoc(devRef(), { "policy.screen.allowed": e.target.checked ? arrayUnion(pkg) : arrayRemove(pkg) })), "تم الحفظ");
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

// favicon via DuckDuckGo (privacy-friendly, no tracking of the child)
const favicon = (host) => `https://icons.duckduckgo.com/ip3/${encodeURIComponent(host)}.ico`;
const favImg = (host) => host ? `<img class="fav" src="${esc(favicon(host))}" alt="" loading="lazy" onerror="this.style.visibility='hidden'">` : "";
const hostOfUrl = (u) => { try { return new URL(/^[a-z]+:\/\//i.test(u) ? u : "https://" + u).hostname.replace(/^www\./, ""); } catch (_) { return normDomain(u) || ""; } }
const pageOfUrl = (u) => u.replace(/^[a-z]+:\/\//i, "").replace(/^www\./, "").replace(/\/+$/, "");

// shared block / allow actions (used by the web tab and the requests tab)
const blockSite = (host) => { const d = normDomain(host); if (d) run(updateDoc(devRef(), { "policy.web.blocked": arrayUnion(d), "policy.web.allowed": arrayRemove(d) }), `تم حظر ${d}`); };
const allowSite = (host) => { const d = normDomain(host); if (d) run(updateDoc(devRef(), { "policy.web.allowed": arrayUnion(d), "policy.web.blocked": arrayRemove(d) }), `تم السماح بـ ${d}`); };
const blockPage = (url) => { const pg = pageOfUrl(url); if (pg) run(updateDoc(devRef(), { "policy.web.blockedPaths": arrayUnion(pg) }), "تم حظر هذه الصفحة"); };
function unblockSite(d, blocked) {
  const toRemove = [...blocked].filter((x) => x === d || isBlockedDomain(d, new Set([x])));
  run(updateDoc(devRef(), { "policy.web.blocked": arrayRemove(...(toRemove.length ? toRemove : [d])) }), "تم إلغاء الحظر");
}
function approveRequest(id) {
  const q = S.requests.find((x) => x.id === id);
  const d = q && normDomain(q.domain);
  if (!d) return;
  run(Promise.all([
    updateDoc(devRef(), { "policy.web.allowed": arrayUnion(d), "policy.web.blocked": arrayRemove(d) }),
    updateDoc(doc(db, "devices", S.deviceId, "requests", q.id), { status: "approved", doneAt: serverTimestamp() }),
  ]), `تم السماح بـ ${d}`);
}
const rejectRequest = (id) =>
  run(updateDoc(doc(db, "devices", S.deviceId, "requests", id), { status: "rejected", doneAt: serverTimestamp() }), "تم الرفض");

// ----- app requests (the child asks to open a blocked app) -----
const WHY = {
  blocked: "التطبيق محظور", pin: "التطبيق مقفل برمز", limit: "انتهى الحد اليومي", bedtime: "وقت النوم", paused: "الأجهزة متوقفة", screen: "انتهى وقت الشاشة",
};
const reqRef = (id) => doc(db, "devices", S.deviceId, "requests", id);
const markRequest = (id, status, extra = {}) => updateDoc(reqRef(id), { status, doneAt: serverTimestamp(), ...extra });

/** Allow the app for a limited time (the device applies it right away through a command). */
function approveAppTemp(id) {
  const q = S.requests.find((x) => x.id === id);
  if (!q || !q.pkg) return;
  const minutes = Math.max(1, Number(pol().overrideMinutes) || 30);
  run(Promise.all([
    addDoc(collection(db, "devices", S.deviceId, "commands"), { type: "grant_app", pkg: q.pkg, minutes, status: "pending", createdAt: serverTimestamp() }),
    markRequest(id, "approved", { grant: "temp" }),
  ]), `تم السماح بـ ${q.label || q.pkg} لمدة ${mins(minutes)}`);
}

/** Allow the app for good: lift whatever blocked it (block, daily limit, bedtime or screen schedule). */
function approveAppForever(id) {
  const q = S.requests.find((x) => x.id === id);
  if (!q || !q.pkg) return;
  const pkg = q.pkg;
  const ops = [];
  if (q.why === "pin") ops.push(updateDoc(devRef(), { "policy.pinLocked": arrayRemove(pkg) }));
  else if (q.why === "limit") ops.push(updateDoc(devRef(), new FieldPath("policy", "limits", pkg), deleteField()));
  else if (q.why === "bedtime" || q.why === "paused") ops.push(updateDoc(devRef(), { "policy.bedtime.allowed": arrayUnion(pkg) }));
  else if (q.why === "screen") {
    const sc = screenOf(pol());
    ops.push((pol().screen ? Promise.resolve() : updateDoc(devRef(), { "policy.screen": sc }))
      .then(() => updateDoc(devRef(), { "policy.screen.allowed": arrayUnion(pkg) })));
  } else ops.push(updateDoc(devRef(), { "policy.blocked": arrayRemove(pkg) }));
  ops.push(markRequest(id, "approved", { grant: "forever" }));
  run(Promise.all(ops), `تم السماح بـ ${q.label || pkg} بشكل دائم`);
}

const permLabel = (why) => ({
  pin: "إزالة القفل برمز", limit: "إلغاء الحد اليومي", bedtime: "سماح دائم وقت النوم", paused: "سماح دائم وقت النوم", screen: "سماح دائم بعد انتهاء الشاشة",
}[why] || "رفع الحظر");

// ----- tab: requests -----
function tabRequests(el) {
  const link = (u) => (/^[a-z]+:\/\//i.test(u) ? u : "https://" + u);
  const when = (q) => (q.ts ? esc(fmtDate.format(new Date(tsMs(q.ts)))) + " · " + esc(fmtTime.format(new Date(tsMs(q.ts)))) : "");
  const isPcDev = isPc(dev());
  const minutes = Math.max(1, Number(pol().overrideMinutes) || 30);
  const row = (q) => q.kind === "app" ? `
      <div class="item">
        <div class="row" style="gap:8px;flex-wrap:wrap">
          <span style="min-width:0;flex:1">
            <b>${isPcDev ? "🖥️" : "📱"} ${esc(q.label || q.pkg)}</b> <span class="muted small">يطلب فتح التطبيق</span>
            <div class="muted small"><span class="mono">${esc(q.pkg || "")}</span> · ${esc(WHY[q.why] || "محجوب")} · ${when(q)}</div>
          </span>
          <button class="btn sm primary" data-temp="${esc(q.id)}">سماح ${esc(mins(minutes))}</button>
          <button class="btn sm" data-forever="${esc(q.id)}" data-why="${esc(q.why || "")}">${esc(permLabel(q.why))}</button>
          <button class="btn sm" data-reject="${esc(q.id)}">رفض</button>
        </div>
      </div>` : `
      <div class="item domain-row">
        <span style="min-width:0;display:flex;align-items:center;gap:8px">${favImg(q.domain)}
          <span style="min-width:0"><a class="mono" href="${esc(link(q.url || q.domain))}" target="_blank" rel="noopener" title="${esc(q.url || "")}">${esc(q.domain)}</a>
          <div class="muted small">🌐 يطلب فتح موقع · ${when(q)}</div></span></span>
        <button class="btn sm primary" data-approve="${esc(q.id)}">سماح</button>
        <button class="btn sm" data-reject="${esc(q.id)}">رفض</button>
      </div>`;
  el.innerHTML = `
  <div class="card stack">
    <h2>📨 طلبات الطفل</h2>
    <p class="small muted" style="margin:0">يستطيع الطفل أن يطلب من جهازه <b>فتح موقع</b> محظور أو <b>فتح تطبيق</b> محظور (محظور، أو انتهى حدّه اليومي، أو وقت النوم، أو انتهاء وقت الشاشة).
      موافقة الموقع تضيفه إلى «المواقع المسموحة». وموافقة التطبيق إما سماح مؤقت ${esc(mins(minutes))} أو سماح دائم برفع ما منعه.</p>
    ${S.requests.length ? `<div class="list">${S.requests.map(row).join("")}</div>` : `<div class="empty">لا توجد طلبات حالياً</div>`}
  </div>`;
  el.querySelectorAll("[data-approve]").forEach((b) => (b.onclick = () => approveRequest(b.dataset.approve)));
  el.querySelectorAll("[data-temp]").forEach((b) => (b.onclick = () => approveAppTemp(b.dataset.temp)));
  el.querySelectorAll("[data-forever]").forEach((b) => (b.onclick = () => approveAppForever(b.dataset.forever)));
  el.querySelectorAll("[data-reject]").forEach((b) => (b.onclick = () => rejectRequest(b.dataset.reject)));
}

// ----- tab: reports -----
const EV = {
  activity: ["🧭", "نشاط داخل تطبيق"], block: ["⛔", "حظر تطبيق"], web: ["🚫", "موقع محظور"], unlock: ["🔓", "سماح برمز المالك"],
  tamper: ["⚠️", "تغيّر في الحماية"], safemode: ["🟠", "الوضع الآمن"], boot: ["🔄", "تشغيل الجهاز"], hold: ["🔒", "قفل الشاشة"], lockout: ["⛔", "محاولات رمز خاطئة كثيرة"],
};
function tabReports(el) {
  const d = dev();
  const usage = usageIsToday() ? appList().filter((a) => a.usage > 0).sort((a, b) => b.usage - a.usage) : [];
  const total = usage.reduce((s, a) => s + a.usage, 0);
  const data = S.web || {};
  let doms = Object.entries(data.domains || {}).map(([name, v]) => ({ name, c: v.c || 0, b: !!v.b }));
  doms = doms.filter((x) => !SYSTEM_DOMAIN.test(x.name));
  const blockedHits = doms.filter((x) => x.b);
  const allEvs = S.events || [];
  const evs = allEvs.filter((e) => e.type !== "activity");
  const acts = allEvs.filter((e) => e.type === "activity");
  const safeCount = evs.filter((e) => e.type === "safemode").length;
  const tamperCount = evs.filter((e) => e.type === "tamper" && e.detail === "off").length;

  const act = (pol().activity) || { enabled: false, apps: [] };
  const actApps = new Set(act.apps || []);
  const FB = ["com.facebook.katana", "com.facebook.lite"];
  const actChoices = appList().filter((x) => x.launchable && (FB.includes(x.pkg) || actApps.has(x.pkg) || /facebook|instagram|tiktok|snapchat|messenger|twitter|whatsapp|telegram/i.test(x.pkg)));
  const actCard = isPc(d) ? "" : `
    <div class="card stack">
      <div class="setting">
        <div class="txt"><b>🧭 نشاط الطفل داخل التطبيقات</b><span class="small muted">تقريبي: يسجّل عناوين الصفحات/المجموعات/الحسابات التي يفتحها ونص البحث داخل التطبيق المختار (مثل فيسبوك). لا يسجّل محتوى المحادثات ولا كلمات المرور.</span></div>
        <label class="switch"><input type="checkbox" id="actOn" ${act.enabled ? "checked" : ""}></label>
      </div>
      <div class="chips" id="actApps">
        ${actChoices.map((x) => `<label class="chip" style="padding:4px 10px"><input type="checkbox" value="${esc(x.pkg)}" ${actApps.has(x.pkg) ? "checked" : ""}> ${esc(x.label)}</label>`).join("") || `<span class="muted small">لا توجد تطبيقات مناسبة بعد (يظهر فيسبوك هنا عند تثبيته)</span>`}
      </div>
      ${acts.length ? `<div class="list">${acts.slice(0, 80).map((e) => `<div class="item"><div class="row"><b style="margin:0 6px">${esc(e.label || "")}</b><span class="small">${esc(e.detail || "")}</span><span class="spacer"></span><span class="muted small">${e.t ? esc(fmtTime.format(new Date(e.t))) : ""}</span></div></div>`).join("")}</div>` : `<div class="empty">لا يوجد نشاط مسجّل بعد</div>`}
      <p class="small muted" style="margin:0">يعمل على أندرويد فقط، ويصل السجل بعد عدة دقائق. الدقة تتفاوت حسب إصدار التطبيق.</p>
    </div>`;

  el.innerHTML = `
  <div class="cols">
    <div class="card stack">
      <h2>ملخص اليوم</h2>
      <div class="bar-row"><span>وقت الاستخدام الكلي</span><div></div><span class="usage">${mins(total)}</span></div>
      <div class="bar-row"><span>مواقع مميّزة زارها</span><div></div><span class="usage">${doms.length}</span></div>
      <div class="bar-row"><span>محاولات مواقع محظورة</span><div></div><span class="usage">${blockedHits.length}</span></div>
      ${safeCount ? `<div class="notice" style="background:var(--warn-soft);color:var(--warn)">🟠 دخل الوضع الآمن ${safeCount} مرة — راجع السجل أدناه.</div>` : ""}
      ${tamperCount ? `<div class="notice">⚠️ تعطّلت صلاحيات حماية ${tamperCount} مرة.</div>` : ""}
      <h3>أكثر التطبيقات استخداماً اليوم</h3>
      ${usage.length ? usage.slice(0, 8).map((a) => `<div class="bar-row"><span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(a.label)}</span><div></div><span class="usage">${mins(a.usage)}</span></div>`).join("") : `<div class="empty">لا توجد بيانات بعد</div>`}
    </div>
    <div class="card stack">
      <h2>سجل الأحداث</h2>
      <p class="small muted" style="margin:0">آخر ما حدث على الجهاز: المنع، السماح، تعطّل الحماية، الوضع الآمن، التشغيل.</p>
      ${evs.length ? `<div class="list">${evs.slice(0, 120).map((e) => {
        const [ic, def] = EV[e.type] || ["•", e.type];
        const extra = e.type === "tamper" ? (e.detail === "off" ? " — توقّفت" : " — عادت") : (e.type === "unlock" ? " (" + esc(e.detail || "") + ")" : "");
        return `<div class="item"><div class="row"><span>${ic}</span><b style="margin:0 6px">${esc(def)}</b><span class="muted small">${esc(e.label || "")}${extra}</span><span class="spacer"></span><span class="muted small">${e.t ? esc(fmtTime.format(new Date(e.t))) : ""}</span></div></div>`;
      }).join("")}</div>` : `<div class="empty">لا توجد أحداث بعد</div>`}
    </div>
    ${actCard}
  </div>`;
  const aOn = document.getElementById("actOn");
  if (aOn) {
    const save = (next) => run(updateDoc(devRef(), { "policy.activity": next }), "تم الحفظ");
    aOn.onchange = () => save({ enabled: aOn.checked, apps: [...actApps] });
    el.querySelectorAll("#actApps input").forEach((c) => (c.onchange = () => {
      c.checked ? actApps.add(c.value) : actApps.delete(c.value);
      save({ enabled: aOn.checked, apps: [...actApps] });
    }));
  }
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
  const media = Object.values(data.media || {}).sort((a, b) => (b.t || 0) - (a.t || 0));
  const ytLink = (m) => "https://www.youtube.com/results?search_query=" + encodeURIComponent(`${m.ti || ""} ${m.ch || ""}`.trim());
  const days = [...Array(7)].map((_, i) => { const d = new Date(); d.setDate(d.getDate() - i); return d; });

  const link = (u) => (/^[a-z]+:\/\//i.test(u) ? u : "https://" + u);
  el.innerHTML = `
  ${S.requests.length ? `<div class="alert" style="background:var(--primary-soft);color:var(--primary)">📨 يوجد ${S.requests.length} طلب من الطفل — افتح تبويب «طلبات الطفل».</div>` : ""}
  <div class="cols">
    <div class="card">
      <h2>إعدادات المواقع</h2>
      <div class="setting"><div class="txt"><b>تسجيل المواقع التي تتم زيارتها</b><span class="small muted">كل التطبيقات والمتصفحات، عبر فلتر DNS على الجهاز.</span></div>
        <label class="switch"><input type="checkbox" data-web="log" ${web.log !== false ? "checked" : ""}></label></div>
      <div class="setting"><div class="txt"><b>منع المواقع المحظورة</b><span class="small muted">يمنع فتح المواقع في القائمة على كل التطبيقات، ويمنع تغيير VPN و DNS الخاص.</span></div>
        <label class="switch"><input type="checkbox" data-web="filter" ${web.filter ? "checked" : ""}></label></div>
      <div class="setting"><div class="txt"><b>السماح فقط بالمواقع المسموحة</b><span class="small muted">يمنع كل المواقع في المتصفحات ما عدا قائمة «المواقع المسموحة». يستطيع الطفل إرسال طلب للسماح بموقع. المتصفحات غير المدعومة تُمنع.</span></div>
        <label class="switch"><input type="checkbox" data-web="allowOnly" ${web.allowOnly ? "checked" : ""}></label></div>
      <div class="setting"><div class="txt"><b>حظر يوتيوب بالكامل (حتى داخل المواقع)</b><span class="small muted">يمنع تشغيل فيديوهات يوتيوب المضمّنة في أي موقع أو متصفح. يحتاج تفعيل «تسجيل المواقع» أو أي فلتر ليعمل.</span></div>
        <label class="switch"><input type="checkbox" data-web="blockYoutube" ${web.blockYoutube ? "checked" : ""}></label></div>
      <div class="setting"><div class="txt"><b>حجب الإعلانات (غوغل وغيرها)</b><span class="small muted">يحجب شبكات الإعلانات والتتبع (Google Ads و AdMob وغيرها) في المتصفحات والألعاب. إعلانات يوتيوب نفسها لا يمكن حجبها لأنها تأتي من نفس خوادم الفيديو.</span></div>
        <label class="switch"><input type="checkbox" data-web="blockAds" ${web.blockAds ? "checked" : ""}></label></div>
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
      <div class="chips">${[...blocked].sort().map((d) => `<span class="chip">${favImg(d)}<span class="mono">${esc(d)}</span><button title="إلغاء الحظر" data-unblock="${esc(d)}">×</button></span>`).join("") || `<span class="muted small">لا توجد مواقع محظورة</span>`}</div>
      ${(web.blockedPaths || []).length ? `<h3 style="margin:8px 0 0">صفحات محظورة</h3><div class="chips">${(web.blockedPaths || []).sort().map((pg) => `<span class="chip">${favImg(hostOfUrl(pg))}<span class="mono" style="max-width:240px;overflow:hidden;text-overflow:ellipsis">${esc(pg)}</span><button title="إلغاء" data-unblockpage="${esc(pg)}">×</button></span>`).join("")}</div>` : ""}
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
    ${allowed.size > 12 ? `<input class="input" id="allowedFilter" placeholder="ابحث في ${allowed.size} موقعاً مسموحاً…" value="${esc(S.allowedQ || "")}">` : ""}
    <div class="chips" id="allowedChips">${(() => {
      const q = (S.allowedQ || "").toLowerCase();
      const all = [...allowed].sort().filter((d) => !q || d.includes(q));
      const shown = S.allowedAll || q ? all : all.slice(0, 24);
      return shown.map((d) => `<span class="chip">${favImg(d)}<a class="mono" href="https://${esc(d)}" target="_blank" rel="noopener">${esc(d)}</a><button title="إزالة" data-disallow="${esc(d)}">×</button></span>`).join("") || `<span class="muted small">لا توجد مواقع مسموحة${q ? " مطابقة" : " بعد"}</span>`;
    })()}</div>
    ${allowed.size > 24 && !S.allowedQ ? `<button class="btn sm" id="allowedMore">${S.allowedAll ? "عرض أقل" : `عرض الكل (${allowed.size})`}</button>` : ""}
  </div>
  <div class="card stack" style="margin-top:12px">
    <div class="row">
      <h2 style="margin:0">سجل التصفح</h2>
      <span class="spacer"></span>
      <select class="input" id="webDay" style="width:auto">${days.map((d) => `<option value="${dayKey(d)}" ${dayKey(d) === S.webDay ? "selected" : ""}>${esc(fmtDate.format(d))}</option>`).join("")}</select>
      <label class="switch"><input type="checkbox" id="hideSys" ${S.hideSystemDomains ? "checked" : ""}>إخفاء نطاقات النظام</label>
    </div>
    ${S.web === null ? `<div class="empty">جارٍ التحميل…</div>` : ""}
    ${media.length ? `<h3>🎬 فيديوهات ووسائط شوهدت (${media.length})</h3><div class="list">${media.slice(0, 200).map((m) => `
      <div class="item domain-row"><span style="min-width:0;display:flex;align-items:center;gap:8px">${favImg(/youtube/i.test(m.p || "") ? "youtube.com" : "")}
          <span style="min-width:0"><a href="${esc(/youtube/i.test(m.p || "") ? ytLink(m) : "https://www.google.com/search?q=" + encodeURIComponent(m.ti || ""))}" target="_blank" rel="noopener"><b>${esc(m.ti)}</b></a>
          <div class="muted small">${esc(m.ch || "")}${m.ch ? " · " : ""}${esc(m.a || m.p || "")}</div></span></span>
        <span class="muted small">${esc(fmtTime.format(new Date(m.t || 0)))}</span><span></span></div>`).join("")}</div>` : ""}
    ${urls.length ? `<h3>صفحات من المتصفح</h3><div class="list">${urls.slice(0, 100).map((u) => {
      const host = hostOfUrl(u.u);
      const pageB = (p.web && (p.web.blockedPaths || []).some((x) => pageOfUrl(u.u) === pageOfUrl(x) || pageOfUrl(u.u).startsWith(pageOfUrl(x) + "/")));
      return `<div class="item domain-row"><span style="min-width:0;display:flex;align-items:center;gap:8px">${favImg(host)}
        <span style="min-width:0">${u.ti ? `<div style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${esc(u.ti)}</div>` : ""}<a class="mono small" style="display:block" href="${esc(link(u.u))}" target="_blank" rel="noopener" title="${esc(u.u)}">${esc(u.u)}</a></span></span>
        <span class="muted small">${esc(fmtTime.format(new Date(u.t || 0)))}</span>
        <span class="row" style="gap:4px">${pageB ? `<span class="badge danger">محظورة</span>` : `<button class="btn sm" data-blockpage="${esc(u.u)}" title="حظر هذه الصفحة فقط">حظر الصفحة</button>`}<button class="btn sm danger" data-block="${esc(host)}" title="حظر الموقع كله">حظر الموقع</button></span></div>`;
    }).join("")}</div>` : ""}
    <h3>النطاقات (${domains.length})</h3>
    <div class="list">${domains.slice(0, 400).map((d) => {
      const isB = isBlockedDomain(d.name, blocked);
      const isA = isBlockedDomain(d.name, allowed);
      return `<div class="item domain-row">
        <span style="min-width:0;display:flex;align-items:center;gap:8px">${favImg(d.name)}<a class="mono" href="https://${esc(d.name)}" target="_blank" rel="noopener" title="${esc(d.name)}">${d.b ? "🚫 " : ""}${esc(d.name)}</a></span>
        <span class="muted small">${esc(fmtTime.format(new Date(d.t)))} · ${d.c}×</span>
        <span class="row" style="gap:4px">
          ${isB ? `<button class="btn sm" data-unblock="${esc(normDomain(d.name) || d.name)}">إلغاء الحظر</button>` : `<button class="btn sm danger" data-block="${esc(d.name)}">حظر</button>`}
          ${isA ? `<button class="btn sm" data-disallow="${esc(normDomain(d.name) || d.name)}">إزالة السماح</button>` : `<button class="btn sm" data-allow="${esc(d.name)}">سماح</button>`}
        </span>
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
  const af = document.getElementById("allowedFilter");
  if (af) af.oninput = () => { S.allowedQ = af.value.trim(); const pos = af.selectionStart; tabWeb(el); const n = document.getElementById("allowedFilter"); if (n) { n.focus(); n.setSelectionRange(pos, pos); } };
  const am = document.getElementById("allowedMore");
  if (am) am.onclick = () => { S.allowedAll = !S.allowedAll; tabWeb(el); };
  el.querySelectorAll("[data-preset]").forEach((b) => (b.onclick = () => run(updateDoc(devRef(), { "policy.web.blocked": arrayUnion(...PRESETS[b.dataset.preset]) }), "تمت الإضافة")));
  el.querySelectorAll("[data-unblock]").forEach((b) => (b.onclick = () => unblockSite(b.dataset.unblock, blocked)));
  el.querySelectorAll("[data-block]").forEach((b) => (b.onclick = () => blockSite(b.dataset.block)));
  el.querySelectorAll("[data-allow]").forEach((b) => (b.onclick = () => allowSite(b.dataset.allow)));
  el.querySelectorAll("[data-blockpage]").forEach((b) => (b.onclick = () => blockPage(b.dataset.blockpage)));
  el.querySelectorAll("[data-unblockpage]").forEach((b) => (b.onclick = () =>
    run(updateDoc(devRef(), { "policy.web.blockedPaths": arrayRemove(b.dataset.unblockpage) }), "تم إلغاء الحظر")));
  document.getElementById("addAllowed").onsubmit = (e) => {
    e.preventDefault();
    const d = normDomain(document.getElementById("allowedDomain").value);
    if (!d) return toast("اكتب نطاقاً صحيحاً مثل example.com");
    allowSite(d);
  };
  el.querySelectorAll("[data-disallow]").forEach((b) => (b.onclick = () =>
    run(updateDoc(devRef(), { "policy.web.allowed": arrayRemove(b.dataset.disallow) }), "تمت الإزالة")));
  document.getElementById("webDay").onchange = (e) => { S.webDay = e.target.value; listenWeb(); renderDevice(); };
  document.getElementById("hideSys").onchange = (e) => { S.hideSystemDomains = e.target.checked; renderDevice(); };
}

// ----- tab: rules -----
// Rules that also work in "normal protection" (enforced with the owner PIN); the rest need Device Owner
const RULES_NORMAL = new Set(["lock_settings", "lock_youtube_settings", "no_install_apps", "no_uninstall_apps", "no_install_unknown_sources"]);
const RULES = [
  ["no_install_apps", "منع تثبيت التطبيقات", "يمنع التثبيت من المتجر (Play Store) ومن الملفات. في الحماية العادية: يُقفل المتجر برمز المالك."],
  ["no_uninstall_apps", "منع حذف التطبيقات", "في الحماية العادية: تُقفل شاشة الحذف برمز المالك."],
  ["no_install_unknown_sources", "منع التثبيت من مصادر غير معروفة", "ملفات APK من خارج المتجر (واتساب، المتصفح…). لا يمنع Play Store."],
  ["no_control_apps", "منع إيقاف التطبيقات أو مسح بياناتها", "يحمي تطبيق المراقبة من الإيقاف."],
  ["lock_settings", "قفل تطبيق الإعدادات برمز المالك", "يحتاج خدمة إمكانية الوصول."],
  ["lock_youtube_settings", "قفل إعدادات يوتيوب وسجلّه برمز المالك", "يقفل إعدادات يوتيوب وصفحة سجل المشاهدة (حذف/إيقاف السجل) في التطبيق، وصفحات myactivity.google.com و youtube.com/feed/history في المتصفح. يعمل على YouTube و YouTube Kids."],
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
      <h3 style="margin-top:8px">المدد الزمنية</h3>
      <label class="field"><span>مدة السماح المؤقت للتطبيقات والمواقع المحظورة (دقائق)</span>
        <input class="input" type="number" min="1" max="1440" id="overrideMin" value="${esc(p.overrideMinutes || 30)}"></label>
      <label class="field"><span>مدة فتح التطبيق المقفل برمز بعد إدخال الرمز (دقائق)</span>
        <input class="input" type="number" min="1" max="1440" id="unlockMin" value="${esc(p.unlockMinutes || 10)}"></label>
      <h3 style="margin-top:8px">محاولات إدخال الرمز</h3>
      <label class="field"><span>عدد المحاولات الخاطئة المسموحة (0 = بلا حد)</span>
        <input class="input" type="number" min="0" max="50" id="maxAtt" value="${esc(p.maxAttempts ?? 5)}"></label>
      <label class="field"><span>مدة المنع بعد تجاوزها (دقائق)</span>
        <input class="input" type="number" min="1" max="1440" id="lockMin" value="${esc(p.lockoutMinutes || 5)}"></label>
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
  const saveMin = (id, field) => {
    const el2 = document.getElementById(id);
    if (el2) el2.onchange = () => {
      const v = Math.max(1, Math.min(1440, parseInt(el2.value, 10) || 1));
      el2.value = v;
      run(updateDoc(devRef(), { [`policy.${field}`]: v }), "تم الحفظ");
    };
  };
  saveMin("overrideMin", "overrideMinutes");
  saveMin("unlockMin", "unlockMinutes");
  saveMin("lockMin", "lockoutMinutes");
  const ma = document.getElementById("maxAtt");
  if (ma) ma.onchange = () => { const v = Math.max(0, Math.min(50, parseInt(ma.value, 10) || 0)); ma.value = v; run(updateDoc(devRef(), { "policy.maxAttempts": v }), "تم الحفظ"); };
  el.querySelectorAll("[data-rule]").forEach((i) => (i.onchange = () => {
    const save = () => run(updateDoc(devRef(), new FieldPath("policy", "restrictions", i.dataset.rule), i.checked), "تم الحفظ");
    if (i.dataset.rule === "no_debugging_features" && i.checked) {
      askConfirm("سيتم تعطيل adb على الجهاز. متابعة؟", "متابعة", true).then((ok) => { if (ok) save(); else i.checked = false; });
      return;
    }
    save();
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
    if (b.dataset.cmd === "reboot") {
      askConfirm("إعادة تشغيل الجهاز؟", "إعادة التشغيل").then((ok) => ok && sendCommand({ type: "reboot" }, "تم إرسال الأمر"));
      return;
    }
    sendCommand({ type: b.dataset.cmd }, "تم إرسال الأمر");
  }));
  document.getElementById("renameForm").onsubmit = (e) => {
    e.preventDefault();
    run(updateDoc(devRef(), { name: document.getElementById("devName").value.trim(), type: document.getElementById("devType").value }), "تم الحفظ");
  };
  document.getElementById("delDevice").onclick = () => confirmDeleteDevice(S.deviceId);
}

// Delete a device and its data (commands, apps, history, requests)
async function confirmDeleteDevice(id) {
  const d = S.devices.get(id);
  if (!d) return;
  const ok = await askConfirm(
    `حذف «${d.name || "الجهاز"}» وكل بياناته من القائمة؟\nلإزالة الحماية من الجهاز نفسه استخدم «إزالة الحماية» داخل التطبيق على الجهاز.`,
    "حذف الجهاز", true);
  if (!ok) return;
  if (S.deviceId === id) { stopDetail(); S.deviceId = null; }
  toast("جارٍ الحذف…");
  try {
    for (const sub of ["commands", "meta", "web", "requests"]) {
      const qs = await getDocs(collection(db, "devices", id, sub)).catch(() => null);
      if (qs) await Promise.all(qs.docs.map((x) => deleteDoc(x.ref).catch(() => {})));
    }
    await deleteDoc(doc(db, "devices", id));
    toast("تم حذف الجهاز");
  } catch (e) {
    console.error(e);
    toast("خطأ: " + (e.code || e.message));
  }
  render();
}

// refresh "online" badges and relative times
setInterval(() => { if (S.user && !document.activeElement?.matches("input,select,textarea")) render(); }, 60_000);
