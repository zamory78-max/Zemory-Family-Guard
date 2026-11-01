using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Hosting;
using Microsoft.Win32;

namespace ZemoryGuard;

/// <summary>Windows service: syncs with Firebase every few seconds and enforces the rules for the child's account.</summary>
internal sealed class AgentService : BackgroundService
{
    public const int ApiPort = 47831;

    private AppConfig? _cfg;
    private Firebase? _fb;
    private Policy _policy = Policy.From(null);
    private readonly UsageStore _usage = UsageStore.Load();
    private readonly WebBuffer _web = new();

    private readonly ConcurrentDictionary<uint, ConcurrentQueue<JsonObject>> _events = new();
    private readonly ConcurrentDictionary<string, DateTime> _overrideUntil = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, DateTime> _unlockedUntil = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, DateTime> _lastNotified = new();
    private readonly ConcurrentDictionary<uint, DateTime> _helperSeen = new();
    private readonly ConcurrentDictionary<uint, DateTime> _helperStarted = new();
    private readonly ConcurrentDictionary<uint, DateTime> _lastLock = new();
    private readonly ConcurrentDictionary<string, bool> _runningCommands = new();
    private List<(uint id, string user)> _childSessions = new();
    private HttpListener? _api;

    private static readonly HashSet<string> Essential = new(StringComparer.OrdinalIgnoreCase)
    {
        "zemoryguard.exe", "explorer.exe", "dwm.exe", "winlogon.exe", "csrss.exe", "svchost.exe", "sihost.exe",
        "ctfmon.exe", "runtimebroker.exe", "searchhost.exe", "searchapp.exe", "searchui.exe",
        "startmenuexperiencehost.exe", "shellexperiencehost.exe", "textinputhost.exe", "applicationframehost.exe",
        "lockapp.exe", "logonui.exe", "fontdrvhost.exe", "conhost.exe", "dllhost.exe", "taskhostw.exe",
        "smartscreen.exe", "securityhealthsystray.exe", "userinit.exe", "wininit.exe", "audiodg.exe",
        "msedgewebview2.exe", "systemsettings.exe", "widgets.exe", "phoneexperiencehost.exe", "consent.exe",
        "credentialuibroker.exe", "useroobebroker.exe", "shellhost.exe", "taskmgr.exe",
    };

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        Log.Write("service start " + Program.Version);
        LoadCachedPolicy();
        StartApi();

        DateTime lastPolicy = DateTime.MinValue, lastCmd = DateTime.MinValue, lastFull = DateTime.MinValue,
            lastWeb = DateTime.UtcNow, lastHistory = DateTime.MinValue, lastDns = DateTime.MinValue,
            lastRestrictions = DateTime.MinValue;
        var lastStatus = "";

        while (!ct.IsCancellationRequested)
        {
            try
            {
                if (_cfg == null || _fb == null)
                {
                    _cfg = AppConfig.Load();
                    if (_cfg != null && _cfg.DeviceId.Length > 0) _fb = new Firebase(_cfg);
                }
                var now = DateTime.UtcNow;
                _usage.RollDay();
                _childSessions = ChildSessions();
                EnsureHelpers();
                Enforce();

                if (now - lastRestrictions > TimeSpan.FromMinutes(1))
                {
                    WinRestrictions.Apply(_policy, _cfg?.ChildUser ?? "");
                    lastRestrictions = now;
                }

                if (_fb != null && _cfg != null)
                {
                    if (now - lastPolicy > TimeSpan.FromSeconds(30))
                    {
                        await RefreshPolicyAsync();
                        lastPolicy = now;
                    }
                    if (now - lastCmd > TimeSpan.FromSeconds(30))
                    {
                        await HandleCommandsAsync();
                        lastCmd = now;
                    }
                    if (_policy.WebLog && now - lastDns > TimeSpan.FromMinutes(1))
                    {
                        foreach (var name in WebControl.NewDnsNames())
                            _web.Domain(name, _policy.WebFilter && Domains.Matches(name, _policy.BlockedDomains));
                        lastDns = now;
                    }
                    if (_policy.WebLog && now - lastHistory > TimeSpan.FromMinutes(3))
                    {
                        foreach (var v in WebControl.NewVisits(_cfg.ChildUser)) _web.Url(v.Url, v.Title, v.Browser, v.TimeMs);
                        lastHistory = now;
                    }

                    var status = Status.Collect(HelpersOk());
                    var sig = JsonSerializer.Serialize(status);
                    if (now - lastFull > TimeSpan.FromMinutes(15))
                    {
                        await UploadStateAsync(status, force: false);
                        lastFull = now;
                        lastStatus = sig;
                    }
                    else if (sig != lastStatus)
                    {
                        await _fb.HeartbeatAsync(_cfg.DeviceId, status);
                        lastStatus = sig;
                    }
                    if (now - lastWeb > TimeSpan.FromMinutes(10))
                    {
                        await FlushWebAsync();
                        lastWeb = now;
                    }
                }
                _usage.SaveIfDirty();
            }
            catch (Exception e)
            {
                Log.Write("loop: " + e.Message);
            }
            try
            {
                await Task.Delay(10_000, ct);
            }
            catch (TaskCanceledException)
            {
                break;
            }
        }
        _usage.SaveIfDirty();
        _api?.Stop();
        Log.Write("service stop");
    }

    // ---------------- Policy ----------------

    private void LoadCachedPolicy()
    {
        try
        {
            var p = Paths.File("policy.json");
            if (File.Exists(p)) ApplyPolicy(Policy.From(JsonNode.Parse(File.ReadAllText(p)) as JsonObject));
        }
        catch (Exception e)
        {
            Log.Write("cached policy: " + e.Message);
        }
    }

    private async Task RefreshPolicyAsync()
    {
        var doc = await _fb!.GetDocAsync("devices/" + _cfg!.DeviceId);
        if (doc == null) return;
        var raw = doc["policy"] as JsonObject ?? new JsonObject();
        var json = raw.ToJsonString();
        if (json == _policy.Raw) return;
        File.WriteAllText(Paths.File("policy.json"), json);
        ApplyPolicy(Policy.From(JsonNode.Parse(json) as JsonObject));
        Log.Write("policy updated");
    }

    private void ApplyPolicy(Policy p)
    {
        _policy = p;
        WebControl.ApplyBrowserPolicies(p);
        WebControl.ApplyHosts(p);
        WinRestrictions.Apply(p, _cfg?.ChildUser ?? AppConfig.Load()?.ChildUser ?? "");
    }

    // ---------------- Sessions and helper ----------------

    private List<(uint id, string user)> ChildSessions()
    {
        var all = Native.ActiveSessions();
        var child = _cfg?.ChildUser ?? "";
        return child.Length == 0
            ? all
            : all.Where(s => string.Equals(s.user, child, StringComparison.OrdinalIgnoreCase)).ToList();
    }

    private bool IsChildSession(uint id) => _childSessions.Any(s => s.id == id);

    private void EnsureHelpers()
    {
        var exe = Environment.ProcessPath;
        if (exe == null) return;
        var running = new HashSet<int>();
        foreach (var p in Process.GetProcessesByName("ZemoryGuard"))
        {
            using (p) running.Add(p.SessionId);
        }
        foreach (var (id, _) in _childSessions)
        {
            if (running.Contains((int)id)) continue;
            if (_helperStarted.TryGetValue(id, out var t) && DateTime.UtcNow - t < TimeSpan.FromSeconds(20)) continue;
            _helperStarted[id] = DateTime.UtcNow;
            if (!Native.StartInSession(id, exe, "helper")) Log.Write("could not start helper in session " + id);
        }
    }

    private bool HelpersOk() =>
        _childSessions.All(s => _helperSeen.TryGetValue(s.id, out var t) && DateTime.UtcNow - t < TimeSpan.FromMinutes(2));

    // ---------------- Enforcement ----------------

    private string? BlockReason(string exe, bool down)
    {
        var now = DateTime.UtcNow;
        if (_overrideUntil.TryGetValue(exe, out var o) && o > now) return null;
        var p = _policy;
        if (p.Blocked.Contains(exe)) return "blocked";
        if (p.PinLocked.Contains(exe) && p.PinHash.Length > 0 &&
            !(_unlockedUntil.TryGetValue(exe, out var u) && u > now)) return "pin";
        if (p.Limits.TryGetValue(exe, out var lim) && _usage.Minutes(exe) >= lim) return "limit";
        if (down && _usage.Known(exe) && !p.BedAllowed.Contains(exe)) return p.PauseAll ? "paused" : "bedtime";
        if ((p.AllowOnly || p.WebFilter) && WebControl.UnsupportedBrowsers.Contains(exe)) return "browser";
        return null;
    }

    private void Enforce()
    {
        if (_childSessions.Count == 0) return;
        var ids = _childSessions.Select(s => (int)s.id).ToHashSet();
        var down = _policy.PauseAll || _policy.IsBedtimeNow();

        if (down)
            foreach (var (id, _) in _childSessions)
                if (!_lastLock.TryGetValue(id, out var t) || DateTime.UtcNow - t > TimeSpan.FromSeconds(30))
                {
                    _lastLock[id] = DateTime.UtcNow;
                    Enqueue(id, new JsonObject { ["type"] = "lock", ["reason"] = _policy.PauseAll ? "paused" : "bedtime" });
                }

        foreach (var proc in Process.GetProcesses())
        {
            using (proc)
            {
                try
                {
                    if (!ids.Contains(proc.SessionId)) continue;
                    var exe = proc.ProcessName.ToLowerInvariant() + ".exe";
                    if (Essential.Contains(exe)) continue;
                    var reason = BlockReason(exe, down);
                    if (reason == null) continue;
                    proc.Kill(true);
                    Notify((uint)proc.SessionId, exe, reason);
                }
                catch
                {
                    // process exited or is protected
                }
            }
        }
    }

    private void Notify(uint session, string exe, string reason)
    {
        var key = session + "|" + exe;
        if (_lastNotified.TryGetValue(key, out var t) && DateTime.UtcNow - t < TimeSpan.FromSeconds(20)) return;
        _lastNotified[key] = DateTime.UtcNow;
        Enqueue(session, new JsonObject
        {
            ["type"] = "blocked",
            ["exe"] = exe,
            ["reason"] = reason,
            ["label"] = _usage.Label(exe),
        });
    }

    private void Enqueue(uint session, JsonObject ev)
    {
        var q = _events.GetOrAdd(session, _ => new ConcurrentQueue<JsonObject>());
        while (q.Count > 20) q.TryDequeue(out _);
        q.Enqueue(ev);
    }

    // ---------------- Uploads ----------------

    private async Task UploadStateAsync(Dictionary<string, object?> status, bool force)
    {
        await _fb!.HeartbeatAsync(_cfg!.DeviceId, status);
        var apps = new Dictionary<string, object?>();
        foreach (var (exe, a) in _usage.Apps)
        {
            apps[exe] = new Dictionary<string, object?>
            {
                ["l"] = a.Label,
                ["s"] = false,
                ["v"] = a.Version,
                ["u"] = (long)_usage.Minutes(exe),
                ["h"] = false,
                ["i"] = true,
                ["x"] = true,
            };
        }
        var sig = JsonSerializer.Serialize(apps);
        if (!force && sig == _lastAppsSig && DateTime.UtcNow - _lastAppsUpload < TimeSpan.FromHours(1)) return;
        _lastAppsSig = sig;
        _lastAppsUpload = DateTime.UtcNow;
        await _fb.CommitAsync(Firebase.UpdateWrite($"devices/{_cfg.DeviceId}/meta/apps",
            new Dictionary<string, object?> { ["apps"] = apps, ["day"] = UsageStore.Today() },
            null, new[] { Firebase.ServerTime("updatedAt") }));
    }

    private string _lastAppsSig = "";
    private DateTime _lastAppsUpload = DateTime.MinValue;

    private async Task FlushWebAsync()
    {
        var (domains, urls) = _web.Take(200);
        if (domains.Count == 0 && urls.Count == 0) return;
        var day = UsageStore.Today();
        var fields = new Dictionary<string, object?> { ["day"] = day };
        var mask = new List<string> { "day" };
        var transforms = new List<JsonObject>();
        var dm = new Dictionary<string, object?>();
        foreach (var (name, e) in domains)
        {
            var entry = new Dictionary<string, object?> { ["t"] = e.Last };
            mask.Add($"domains.{Firebase.Seg(name)}.t");
            if (e.Blocked)
            {
                entry["b"] = true;
                mask.Add($"domains.{Firebase.Seg(name)}.b");
            }
            dm[name] = entry;
            transforms.Add(Firebase.Increment($"domains.{Firebase.Seg(name)}.c", e.Count));
        }
        if (dm.Count > 0) fields["domains"] = dm;
        var um = new Dictionary<string, object?>();
        foreach (var (key, v) in urls)
        {
            um[key] = new Dictionary<string, object?> { ["u"] = v.Url, ["ti"] = v.Title, ["br"] = v.Browser, ["t"] = v.Time };
            mask.Add($"urls.{Firebase.Seg(key)}");
        }
        if (um.Count > 0) fields["urls"] = um;
        try
        {
            await _fb!.CommitAsync(Firebase.UpdateWrite($"devices/{_cfg!.DeviceId}/web/{day}", fields, mask, transforms));
        }
        catch (Exception e)
        {
            Log.Write("web flush: " + e.Message);
        }
    }

    // ---------------- Commands ----------------

    private async Task HandleCommandsAsync()
    {
        var id = _cfg!.DeviceId;
        var list = await _fb!.QueryAsync("devices/" + id, "commands", "status", "pending", 20);
        foreach (var (cmdId, data) in list)
        {
            if (!_runningCommands.TryAdd(cmdId, true)) continue;
            await _fb.CommitAsync(Firebase.UpdateWrite($"devices/{id}/commands/{cmdId}",
                new Dictionary<string, object?> { ["status"] = "running" }, new[] { "status" }));
            _ = Task.Run(async () =>
            {
                try
                {
                    var msg = await RunCommandAsync(data);
                    await _fb.CommandResultAsync(id, cmdId, "done", msg);
                }
                catch (Exception e)
                {
                    Log.Write("command " + cmdId + ": " + e.Message);
                    try { await _fb.CommandResultAsync(id, cmdId, "error", e.Message); } catch { /* ignore */ }
                }
                finally
                {
                    _runningCommands.TryRemove(cmdId, out _);
                }
            });
        }
    }

    private async Task<string> RunCommandAsync(JsonObject c)
    {
        var type = c["type"]?.GetValue<string>() ?? "";
        switch (type)
        {
            case "lock_now":
                foreach (var (sid, _) in Native.ActiveSessions()) Enqueue(sid, new JsonObject { ["type"] = "lock", ["reason"] = "lock" });
                return "تم";
            case "reboot":
                WebControl.Run("shutdown", "/r /t 15 /c \"Zemory Family Guard\"");
                return "سيُعاد التشغيل خلال 15 ثانية";
            case "sync":
                await UploadStateAsync(Status.Collect(HelpersOk()), force: true);
                await FlushWebAsync();
                return "تم";
            case "install":
                return await InstallAsync(c["url"]?.GetValue<string>() ?? throw new Exception("لا يوجد رابط"));
            case "uninstall":
                return Uninstall(c["pkg"]?.GetValue<string>() ?? throw new Exception("لا يوجد تطبيق"));
            default:
                throw new Exception("أمر غير مدعوم على ويندوز: " + type);
        }
    }

    private static async Task<string> InstallAsync(string url)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        var isMsi = url.Split('?')[0].EndsWith(".msi", StringComparison.OrdinalIgnoreCase);
        var dir = Path.Combine(Paths.Data, "dl");
        Directory.CreateDirectory(dir);
        var file = Path.Combine(dir, "setup" + (isMsi ? ".msi" : ".exe"));
        await using (var s = await http.GetStreamAsync(url))
        await using (var f = File.Create(file))
            await s.CopyToAsync(f);

        var psi = isMsi
            ? new ProcessStartInfo("msiexec.exe", $"/i \"{file}\" /qn /norestart")
            : new ProcessStartInfo(file, "/S");
        psi.UseShellExecute = false;
        psi.CreateNoWindow = true;
        using var p = Process.Start(psi) ?? throw new Exception("تعذّر التشغيل");
        if (!p.WaitForExit(15 * 60 * 1000)) throw new Exception("انتهت المهلة");
        if (p.ExitCode != 0 && p.ExitCode != 3010) throw new Exception("رمز الخروج " + p.ExitCode);
        return isMsi ? "تم التثبيت" : "تم التشغيل بصمت (/S)";
    }

    private string Uninstall(string exe)
    {
        var path = _usage.Apps.TryGetValue(exe, out var a) ? a.Path : "";
        if (string.IsNullOrEmpty(path)) throw new Exception("مسار البرنامج غير معروف");
        var dir = Path.GetDirectoryName(path) ?? "";
        var roots = new List<(RegistryKey root, string key)>
        {
            (Registry.LocalMachine, @"SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall"),
            (Registry.LocalMachine, @"SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall"),
        };
        var sid = WinRestrictions.SidOf(_cfg?.ChildUser ?? "");
        if (sid != null) roots.Add((Registry.Users, sid + @"\Software\Microsoft\Windows\CurrentVersion\Uninstall"));

        foreach (var (root, key) in roots)
        {
            using var k = root.OpenSubKey(key);
            if (k == null) continue;
            foreach (var name in k.GetSubKeyNames())
            {
                using var e = k.OpenSubKey(name);
                if (e == null) continue;
                var loc = (e.GetValue("InstallLocation") as string ?? "").Trim().Trim('"').TrimEnd('\\');
                var icon = e.GetValue("DisplayIcon") as string ?? "";
                var match = (loc.Length > 3 && dir.StartsWith(loc, StringComparison.OrdinalIgnoreCase)) ||
                            icon.Contains(path, StringComparison.OrdinalIgnoreCase);
                if (!match) continue;

                var cmd = e.GetValue("QuietUninstallString") as string;
                var plain = e.GetValue("UninstallString") as string ?? "";
                if (string.IsNullOrEmpty(cmd) && plain.Contains("msiexec", StringComparison.OrdinalIgnoreCase) && name.StartsWith("{"))
                    cmd = $"MsiExec.exe /X{name} /qn /norestart";
                if (string.IsNullOrEmpty(cmd))
                    throw new Exception("هذا البرنامج لا يدعم الحذف الصامت: " + (e.GetValue("DisplayName") ?? exe));

                using var p = Process.Start(new ProcessStartInfo("cmd.exe", "/c \"" + cmd + "\"")
                {
                    UseShellExecute = false,
                    CreateNoWindow = true,
                });
                p?.WaitForExit(10 * 60 * 1000);
                return "تم حذف " + (e.GetValue("DisplayName") ?? exe);
            }
        }
        throw new Exception("لم يُعثر على برنامج الحذف");
    }

    // ---------------- Local API for the session helper ----------------

    private void StartApi()
    {
        try
        {
            _api = new HttpListener();
            _api.Prefixes.Add($"http://127.0.0.1:{ApiPort}/");
            _api.Start();
            _ = Task.Run(async () =>
            {
                while (_api.IsListening)
                {
                    try
                    {
                        var ctx = await _api.GetContextAsync();
                        _ = Task.Run(() => HandleApiAsync(ctx));
                    }
                    catch (Exception e)
                    {
                        if (!_api.IsListening) break;
                        Log.Write("api: " + e.Message);
                    }
                }
            });
        }
        catch (Exception e)
        {
            Log.Write("api start: " + e.Message);
        }
    }

    private async Task HandleApiAsync(HttpListenerContext ctx)
    {
        JsonNode resp = new JsonObject { ["ok"] = true };
        try
        {
            var path = ctx.Request.Url?.AbsolutePath ?? "";
            var session = uint.TryParse(ctx.Request.QueryString["s"], out var s) ? s : 0u;
            JsonObject? body = null;
            if (ctx.Request.HasEntityBody)
            {
                using var sr = new StreamReader(ctx.Request.InputStream, Encoding.UTF8);
                var text = await sr.ReadToEndAsync();
                if (text.Length > 0) body = JsonNode.Parse(text) as JsonObject;
            }
            _helperSeen[session] = DateTime.UtcNow;

            switch (path)
            {
                case "/hello":
                    resp = new JsonObject { ["ok"] = true, ["monitored"] = IsChildSession(session) };
                    break;
                case "/usage":
                    if (IsChildSession(session) && body?["items"] is JsonObject items)
                        foreach (var (exe, v) in items)
                        {
                            if (v is not JsonObject o) continue;
                            _usage.Add(exe.ToLowerInvariant(), o["s"]?.GetValue<double>() ?? 0,
                                o["p"]?.GetValue<string>() ?? "", o["l"]?.GetValue<string>() ?? "", o["v"]?.GetValue<string>() ?? "");
                        }
                    break;
                case "/events":
                    var arr = new JsonArray();
                    if (_events.TryGetValue(session, out var q))
                        while (q.TryDequeue(out var ev)) arr.Add(ev);
                    resp = new JsonObject { ["events"] = arr };
                    break;
                case "/pin":
                    var exeName = body?["exe"]?.GetValue<string>() ?? "";
                    var reason = body?["reason"]?.GetValue<string>() ?? "";
                    var pin = body?["pin"]?.GetValue<string>() ?? "";
                    var ok = _policy.CheckPin(pin);
                    if (ok)
                    {
                        if (reason == "pin") _unlockedUntil[exeName] = DateTime.UtcNow.AddMinutes(10);
                        else if (reason is "bedtime" or "paused") _overrideUntil[exeName] = DateTime.UtcNow.AddMinutes(30);
                        else _overrideUntil[exeName] = DateTime.UtcNow.AddMinutes(30);
                    }
                    resp = new JsonObject { ["ok"] = ok, ["pinSet"] = _policy.PinHash.Length > 0 };
                    break;
                case "/request":
                    var url = body?["url"]?.GetValue<string>() ?? "";
                    var host = Domains.Normalize(url);
                    if (host == null || _fb == null || _cfg == null)
                    {
                        resp = new JsonObject { ["ok"] = false };
                        break;
                    }
                    await _fb.CommitAsync(Firebase.UpdateWrite($"devices/{_cfg.DeviceId}/requests/{host}",
                        new Dictionary<string, object?> { ["domain"] = host, ["url"] = url, ["status"] = "pending" },
                        null, new[] { Firebase.ServerTime("ts") }));
                    break;
                default:
                    ctx.Response.StatusCode = 404;
                    break;
            }
        }
        catch (Exception e)
        {
            Log.Write("api handler: " + e.Message);
            resp = new JsonObject { ["ok"] = false, ["error"] = e.Message };
        }
        try
        {
            var bytes = Encoding.UTF8.GetBytes(resp.ToJsonString());
            ctx.Response.ContentType = "application/json; charset=utf-8";
            ctx.Response.ContentLength64 = bytes.Length;
            await ctx.Response.OutputStream.WriteAsync(bytes);
            ctx.Response.Close();
        }
        catch
        {
            // client went away
        }
    }
}

/// <summary>Foreground time per exe for today, and every app seen (persisted in ProgramData).</summary>
internal sealed class UsageStore
{
    public sealed class AppSeen
    {
        public string Label { get; set; } = "";
        public string Path { get; set; } = "";
        public string Version { get; set; } = "";
    }

    public string Day { get; set; } = Today();
    public Dictionary<string, double> Seconds { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    public Dictionary<string, AppSeen> Apps { get; set; } = new(StringComparer.OrdinalIgnoreCase);
    private bool _dirty;
    private readonly object _gate = new();

    public static string Today() => DateTime.Now.ToString("yyyy-MM-dd");

    public static UsageStore Load()
    {
        try
        {
            var p = Paths.File("usage.json");
            if (File.Exists(p))
            {
                var u = JsonSerializer.Deserialize<UsageStore>(File.ReadAllText(p));
                if (u != null)
                {
                    u.Seconds = new Dictionary<string, double>(u.Seconds, StringComparer.OrdinalIgnoreCase);
                    u.Apps = new Dictionary<string, AppSeen>(u.Apps, StringComparer.OrdinalIgnoreCase);
                    return u;
                }
            }
        }
        catch (Exception e)
        {
            Log.Write("usage load: " + e.Message);
        }
        return new UsageStore();
    }

    public void RollDay()
    {
        lock (_gate)
        {
            if (Day == Today()) return;
            Day = Today();
            Seconds.Clear();
            _dirty = true;
        }
    }

    public void Add(string exe, double seconds, string path, string label, string version)
    {
        lock (_gate)
        {
            Seconds[exe] = (Seconds.TryGetValue(exe, out var s) ? s : 0) + Math.Max(0, Math.Min(seconds, 120));
            if (!Apps.TryGetValue(exe, out var a)) Apps[exe] = a = new AppSeen();
            if (path.Length > 0) a.Path = path;
            if (label.Length > 0) a.Label = label;
            if (version.Length > 0) a.Version = version;
            if (a.Label.Length == 0) a.Label = exe;
            _dirty = true;
        }
    }

    public int Minutes(string exe)
    {
        lock (_gate) return Seconds.TryGetValue(exe, out var s) ? (int)(s / 60) : 0;
    }

    public bool Known(string exe)
    {
        lock (_gate) return Apps.ContainsKey(exe);
    }

    public string Label(string exe)
    {
        lock (_gate) return Apps.TryGetValue(exe, out var a) && a.Label.Length > 0 ? a.Label : exe;
    }

    public void SaveIfDirty()
    {
        lock (_gate)
        {
            if (!_dirty) return;
            _dirty = false;
            try
            {
                File.WriteAllText(Paths.File("usage.json"), JsonSerializer.Serialize(this));
            }
            catch (Exception e)
            {
                Log.Write("usage save: " + e.Message);
            }
        }
    }
}

/// <summary>Visited domains/URLs waiting to be uploaded to devices/{id}/web/{day}.</summary>
internal sealed class WebBuffer
{
    public sealed class Entry
    {
        public long Count;
        public long Last;
        public bool Blocked;
    }

    public sealed record Visit(string Url, string Title, string Browser, long Time);

    private readonly Dictionary<string, Entry> _domains = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, Visit> _urls = new();
    private readonly object _gate = new();

    public void Domain(string name, bool blocked)
    {
        if (name.Length > 200) return;
        lock (_gate)
        {
            if (!_domains.TryGetValue(name, out var e)) _domains[name] = e = new Entry();
            e.Count++;
            e.Last = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            if (blocked) e.Blocked = true;
        }
    }

    public void Url(string url, string title, string browser, long timeMs)
    {
        var u = url.Length > 500 ? url[..500] : url;
        var ti = title.Length > 300 ? title[..300] : title;
        var host = Domains.Normalize(u);
        lock (_gate)
        {
            _urls[((uint)u.GetHashCode()).ToString("x")] = new Visit(u, ti, browser, timeMs);
            if (host != null)
            {
                if (!_domains.TryGetValue(host, out var e)) _domains[host] = e = new Entry();
                e.Count++;
                e.Last = Math.Max(e.Last, timeMs);
            }
        }
    }

    public (List<(string name, Entry e)> domains, List<(string key, Visit v)> urls) Take(int max)
    {
        lock (_gate)
        {
            var d = _domains.Take(max).Select(kv => (kv.Key, kv.Value)).ToList();
            foreach (var (k, _) in d) _domains.Remove(k);
            var u = _urls.Take(max).Select(kv => (kv.Key, kv.Value)).ToList();
            foreach (var (k, _) in u) _urls.Remove(k);
            return (d, u);
        }
    }
}
