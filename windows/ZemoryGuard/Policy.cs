using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace ZemoryGuard;

/// <summary>Same rules as the Android app (devices/{id}.policy). App keys on Windows are exe names, e.g. "chrome.exe".</summary>
internal sealed class Policy
{
    private static readonly StringComparer Ci = StringComparer.OrdinalIgnoreCase;

    public HashSet<string> Blocked = new(Ci);
    public HashSet<string> PinLocked = new(Ci);
    public Dictionary<string, int> Limits = new(Ci);
    public string PinHash = "";
    public Dictionary<string, bool> Restrictions = new(Ci);
    public bool WebFilter;
    public bool WebLog = true;
    public bool SafeSearch;
    public bool BlockYoutube;
    public bool BlockAds;
    public HashSet<string> BlockedDomains = new(Ci);
    public bool AllowOnly;
    public HashSet<string> AllowedDomains = new(Ci);
    public bool BedEnabled;
    public string BedStart = "21:00";
    public string BedEnd = "07:00";
    public HashSet<string> BedAllowed = new(Ci);
    public bool PauseAll;
    public bool LockNow;
    public int OverrideMinutes = 30;
    public int UnlockMinutes = 10;
    public HashSet<string> BlockedPaths = new(Ci);
    public bool ScreenEnabled;
    /// <summary>"allowed" = only the allowed apps keep working when the window is over, "lock" = lock the whole screen.</summary>
    public string ScreenMode = "allowed";
    public HashSet<string> ScreenAllowed = new(Ci);
    /// <summary>Per weekday (0 = Sunday ... 6 = Saturday): on, start and end in minutes since midnight.</summary>
    public Dictionary<int, (bool on, int start, int end)> ScreenDays = new();
    public string Raw = "";

    public bool Restricted(string key) => Restrictions.TryGetValue(key, out var v) && v;

    public static Policy From(JsonObject? o)
    {
        o ??= new JsonObject();
        var web = o["web"] as JsonObject ?? new JsonObject();
        var bed = o["bedtime"] as JsonObject ?? new JsonObject();
        var screen = o["screen"] as JsonObject ?? new JsonObject();
        var p = new Policy
        {
            Blocked = Set(o["blocked"]),
            PinLocked = Set(o["pinLocked"]),
            PinHash = Str(o["pinHash"]),
            WebFilter = Bool(web["filter"], false),
            WebLog = Bool(web["log"], true),
            SafeSearch = Bool(web["safeSearch"], false),
            BlockYoutube = Bool(web["blockYoutube"], false),
            BlockAds = Bool(web["blockAds"], false),
            BlockedDomains = new HashSet<string>(Set(web["blocked"]).Select(Domains.Normalize).OfType<string>(), Ci),
            AllowOnly = Bool(web["allowOnly"], false),
            AllowedDomains = new HashSet<string>(Set(web["allowed"]).Select(Domains.Normalize).OfType<string>(), Ci),
            BedEnabled = Bool(bed["enabled"], false),
            BedStart = Str(bed["start"], "21:00"),
            BedEnd = Str(bed["end"], "07:00"),
            BedAllowed = Set(bed["allowed"]),
            PauseAll = Bool(o["pauseAll"], false),
            LockNow = Bool(o["lockNow"], false),
            OverrideMinutes = (int)(Num(o["overrideMinutes"]) ?? 30),
            UnlockMinutes = (int)(Num(o["unlockMinutes"]) ?? 10),
            BlockedPaths = Set(web["blockedPaths"]),
            ScreenEnabled = Bool(screen["enabled"], false),
            ScreenMode = Str(screen["mode"], "allowed") == "lock" ? "lock" : "allowed",
            ScreenAllowed = Set(screen["allowed"]),
            Raw = o.ToJsonString(),
        };
        if (screen["days"] is JsonObject days)
            for (var i = 0; i < 7; i++)
                if (days[i.ToString()] is JsonObject d && Minutes(Str(d["start"])) is { } st && Minutes(Str(d["end"])) is { } en)
                    p.ScreenDays[i] = (Bool(d["on"], true), st, en);
        if (p.BlockYoutube)
        {
            p.WebFilter = true;
            foreach (var d in new[] { "youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com" }) p.BlockedDomains.Add(d);
        }
        if (p.BlockAds)
        {
            p.WebFilter = true;
            foreach (var d in new[] { "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "pagead2.googlesyndication.com", "googletagservices.com", "googletagmanager.com", "google-analytics.com", "admob.com", "adsense.com", "adnxs.com", "taboola.com", "outbrain.com", "popads.net", "propellerads.com", "adcolony.com", "unityads.unity3d.com", "applovin.com", "ads.mopub.com", "moatads.com", "criteo.com", "pubmatic.com", "rubiconproject.com", "openx.net", "amazon-adsystem.com", "ads.tiktok.com", "ads.youtube.com", "static.ads-twitter.com" }) p.BlockedDomains.Add(d);
        }
        if (o["limits"] is JsonObject lim)
            foreach (var (k, v) in lim)
                if (v is JsonValue jv && jv.TryGetValue<long>(out var n) && n > 0) p.Limits[k] = (int)n;
        if (o["restrictions"] is JsonObject r)
            foreach (var (k, v) in r)
                p.Restrictions[k] = v is JsonValue bv && bv.TryGetValue<bool>(out var b) && b;
        return p;
    }

    public bool IsBedtimeNow()
    {
        if (!BedEnabled) return false;
        var s = Minutes(BedStart);
        var e = Minutes(BedEnd);
        if (s == null || e == null || s == e) return false;
        var now = DateTime.Now.Hour * 60 + DateTime.Now.Minute;
        return s < e ? now >= s && now < e : now >= s || now < e;
    }

    /// <summary>True when today's screen window is over (or has not started yet).</summary>
    public bool IsScreenClosedNow()
    {
        if (!ScreenEnabled) return false;
        var n = DateTime.Now;
        if (!ScreenDays.TryGetValue((int)n.DayOfWeek, out var d) || !d.on || d.start >= d.end) return false;
        var now = n.Hour * 60 + n.Minute;
        return now < d.start || now >= d.end;
    }

    /// <summary>The whole screen must be locked now (owner "lock now" is handled separately).</summary>
    public bool ScreenLocksNow() => ScreenMode == "lock" && IsScreenClosedNow();

    /// <summary>
    /// Apps that may still run while apps are shut down (pause, bedtime, or the screen schedule in
    /// "allowed apps only" mode), or null when apps are not shut down. An app must be allowed by every rule that applies.
    /// </summary>
    public HashSet<string>? DownAllowed()
    {
        HashSet<string>? allowed = null;
        if (PauseAll || IsBedtimeNow()) allowed = BedAllowed;
        if (ScreenMode != "lock" && IsScreenClosedNow())
            allowed = allowed == null ? ScreenAllowed : new HashSet<string>(allowed.Intersect(ScreenAllowed, Ci), Ci);
        return allowed;
    }

    public bool CheckPin(string pin)
    {
        if (string.IsNullOrEmpty(PinHash)) return false;
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes("fg:" + pin))).ToLowerInvariant();
        return string.Equals(hash, PinHash, StringComparison.OrdinalIgnoreCase);
    }

    public bool NeedsWebMonitoring => WebLog || WebFilter || SafeSearch || AllowOnly;

    private static int? Minutes(string hhmm)
    {
        var p = hhmm.Split(':');
        return p.Length == 2 && int.TryParse(p[0], out var h) && int.TryParse(p[1], out var m) ? h * 60 + m : null;
    }

    private static HashSet<string> Set(JsonNode? n)
    {
        var set = new HashSet<string>(Ci);
        if (n is JsonArray a)
            foreach (var x in a)
                if (x is JsonValue v && v.TryGetValue<string>(out var s) && !string.IsNullOrWhiteSpace(s))
                    set.Add(s.Trim());
        return set;
    }

    private static string Str(JsonNode? n, string def = "") =>
        n is JsonValue v && v.TryGetValue<string>(out var s) ? s : def;

    private static bool Bool(JsonNode? n, bool def) =>
        n is JsonValue v && v.TryGetValue<bool>(out var b) ? b : def;

    private static long? Num(JsonNode? n) =>
        n is JsonValue v && v.TryGetValue<long>(out var l) ? l : null;
}

internal static class Domains
{
    public static string? Normalize(string raw)
    {
        var s = raw.Trim().ToLowerInvariant();
        var i = s.IndexOf("://", StringComparison.Ordinal);
        if (i >= 0) s = s[(i + 3)..];
        s = s.Split('/', '?', '#', ':')[0].TrimEnd('.');
        if (s.StartsWith("*.")) s = s[2..];
        if (s.StartsWith("www.")) s = s[4..];
        return s.Contains('.') && !s.Contains(' ') ? s : null;
    }

    public static bool Matches(string host, IReadOnlySet<string> list)
    {
        if (list.Count == 0) return false;
        var d = host.ToLowerInvariant().TrimEnd('.');
        while (true)
        {
            if (list.Contains(d)) return true;
            var i = d.IndexOf('.');
            if (i < 0) return false;
            d = d[(i + 1)..];
        }
    }
}

internal static class Status
{
    public static Dictionary<string, object?> Collect(bool helperOk, bool paused = false) => new()
    {
        ["platform"] = "windows",
        ["deviceOwner"] = true,       // the agent runs as a Windows service (full protection)
        ["accessibility"] = helperOk, // session helper: foreground tracking and messages
        ["usageAccess"] = helperOk,
        ["admin"] = true,
        ["battery"] = true,
        ["vpn"] = false,
        ["vpnNeeded"] = false,
        ["paused"] = paused,
    };
}
