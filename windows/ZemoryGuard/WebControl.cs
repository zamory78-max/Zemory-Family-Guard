using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using Microsoft.Data.Sqlite;
using Microsoft.Win32;

namespace ZemoryGuard;

/// <summary>
/// Website control on Windows:
///  - browser policies (Chrome, Edge, Brave, Chromium, Firefox): block list, allow-only list, safe search, no DNS-over-HTTPS, no private mode
///  - hosts file: blocks listed domains for every app, forces safe search
///  - history: DNS cache (all apps) + browser history databases (full URLs)
/// </summary>
internal static class WebControl
{
    private static readonly string[] ChromiumKeys =
    {
        @"SOFTWARE\Policies\Google\Chrome",
        @"SOFTWARE\Policies\Microsoft\Edge",
        @"SOFTWARE\Policies\BraveSoftware\Brave",
        @"SOFTWARE\Policies\Chromium",
    };
    private const string FirefoxKey = @"SOFTWARE\Policies\Mozilla\Firefox";

    /// <summary>Browsers that ignore these policies; closed while website rules are on.</summary>
    public static readonly HashSet<string> UnsupportedBrowsers = new(StringComparer.OrdinalIgnoreCase)
    {
        "opera.exe", "opera_gx.exe", "vivaldi.exe", "browser.exe", "waterfox.exe", "librewolf.exe",
        "palemoon.exe", "iexplore.exe", "maxthon.exe", "torch.exe", "comodo_dragon.exe", "seamonkey.exe",
    };

    private static string _lastPolicySig = "";
    private static string _lastHostsSig = "";

    // ---------------- Browser policies ----------------

    public static void ApplyBrowserPolicies(Policy p, bool force = false)
    {
        var sig = $"{p.NeedsWebMonitoring}|{p.WebFilter}|{p.AllowOnly}|{p.SafeSearch}|{string.Join(",", p.BlockedDomains.OrderBy(x => x))}|{string.Join(",", p.AllowedDomains.OrderBy(x => x))}";
        if (!force && sig == _lastPolicySig) return;
        _lastPolicySig = sig;
        var filtering = p.WebFilter || p.AllowOnly;

        foreach (var path in ChromiumKeys)
        {
            try
            {
                using var k = Registry.LocalMachine.CreateSubKey(path, true);
                var edge = path.Contains("Edge");
                SetOrDelete(k, "DnsOverHttpsMode", p.NeedsWebMonitoring ? "off" : null);
                SetOrDelete(k, edge ? "InPrivateModeAvailability" : "IncognitoModeAvailability", filtering ? 1 : null);
                SetOrDelete(k, "BrowserGuestModeEnabled", filtering ? 0 : null);
                SetOrDelete(k, "ForceGoogleSafeSearch", p.SafeSearch ? 1 : null);
                SetOrDelete(k, "ForceYouTubeRestrict", p.SafeSearch ? 2 : null);
                if (edge) SetOrDelete(k, "ForceBingSafeSearch", p.SafeSearch ? 2 : null);

                IEnumerable<string>? block = null, allow = null;
                if (p.AllowOnly)
                {
                    block = new[] { "*" };
                    allow = p.AllowedDomains;
                }
                else if (p.WebFilter && p.BlockedDomains.Count > 0)
                {
                    block = p.BlockedDomains;
                }
                SetList(k, "URLBlocklist", block);
                SetList(k, "URLAllowlist", allow);
            }
            catch (Exception e)
            {
                Log.Write("policy " + path + ": " + e.Message);
            }
        }

        try
        {
            using var f = Registry.LocalMachine.CreateSubKey(FirefoxKey, true);
            SetOrDelete(f, "DisablePrivateBrowsing", filtering ? 1 : null);
            if (p.NeedsWebMonitoring)
            {
                using var doh = f.CreateSubKey("DNSOverHTTPS", true);
                doh.SetValue("Enabled", 0, RegistryValueKind.DWord);
                doh.SetValue("Locked", 1, RegistryValueKind.DWord);
            }
            else f.DeleteSubKeyTree("DNSOverHTTPS", false);

            f.DeleteSubKeyTree("WebsiteFilter", false);
            if (p.AllowOnly || (p.WebFilter && p.BlockedDomains.Count > 0))
            {
                using var wf = f.CreateSubKey("WebsiteFilter", true);
                if (p.AllowOnly)
                {
                    SetList(wf, "Block", new[] { "<all_urls>" });
                    SetList(wf, "Exceptions", p.AllowedDomains.SelectMany(FirefoxPatterns));
                }
                else
                {
                    SetList(wf, "Block", p.BlockedDomains.SelectMany(FirefoxPatterns));
                }
            }
        }
        catch (Exception e)
        {
            Log.Write("policy firefox: " + e.Message);
        }
    }

    private static IEnumerable<string> FirefoxPatterns(string d) => new[] { $"*://{d}/*", $"*://*.{d}/*" };

    private static void SetOrDelete(RegistryKey k, string name, object? value)
    {
        if (value == null)
        {
            if (k.GetValue(name) != null) k.DeleteValue(name, false);
            return;
        }
        k.SetValue(name, value, value is int ? RegistryValueKind.DWord : RegistryValueKind.String);
    }

    private static void SetList(RegistryKey parent, string name, IEnumerable<string>? items)
    {
        parent.DeleteSubKeyTree(name, false);
        if (items == null) return;
        var list = items.ToList();
        if (list.Count == 0) return;
        using var k = parent.CreateSubKey(name, true);
        for (var i = 0; i < list.Count; i++) k.SetValue((i + 1).ToString(), list[i], RegistryValueKind.String);
    }

    // ---------------- hosts file ----------------

    private const string Begin = "# >>> ZemoryGuard";
    private const string End = "# <<< ZemoryGuard";
    private static readonly string HostsPath = Path.Combine(Environment.SystemDirectory, @"drivers\etc\hosts");

    public static void ApplyHosts(Policy p, bool force = false)
    {
        var lines = new List<string>();
        if (p.WebFilter)
            foreach (var d in p.BlockedDomains.OrderBy(x => x))
            {
                lines.Add("0.0.0.0 " + d);
                lines.Add("0.0.0.0 www." + d);
            }
        if (p.SafeSearch)
        {
            void Map(string target, params string[] names)
            {
                var ip = Resolve(target);
                if (ip != null) foreach (var n in names) lines.Add(ip + " " + n);
            }
            Map("forcesafesearch.google.com", "www.google.com", "google.com", "www.google.dz", "google.dz", "www.google.fr");
            Map("restrict.youtube.com", "www.youtube.com", "m.youtube.com", "youtube.com", "youtubei.googleapis.com",
                "youtube.googleapis.com", "www.youtube-nocookie.com");
            Map("strict.bing.com", "www.bing.com", "bing.com");
        }
        var sig = string.Join("\n", lines);
        if (!force && sig == _lastHostsSig) return;
        _lastHostsSig = sig;

        try
        {
            var current = File.Exists(HostsPath) ? File.ReadAllLines(HostsPath).ToList() : new List<string>();
            var output = new List<string>();
            var skipping = false;
            foreach (var l in current)
            {
                if (l.Trim() == Begin) { skipping = true; continue; }
                if (l.Trim() == End) { skipping = false; continue; }
                if (!skipping) output.Add(l);
            }
            if (lines.Count > 0)
            {
                output.Add(Begin);
                output.AddRange(lines);
                output.Add(End);
            }
            File.WriteAllLines(HostsPath, output, new UTF8Encoding(false));
            Run("ipconfig", "/flushdns");
        }
        catch (Exception e)
        {
            Log.Write("hosts: " + e.Message);
        }
    }

    private static string? Resolve(string host)
    {
        try
        {
            return Dns.GetHostAddresses(host).FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork)?.ToString();
        }
        catch
        {
            return null;
        }
    }

    public static string Run(string file, string args, int timeoutMs = 20000)
    {
        try
        {
            using var proc = Process.Start(new ProcessStartInfo(file, args)
            {
                RedirectStandardOutput = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8,
            });
            if (proc == null) return "";
            var text = proc.StandardOutput.ReadToEnd();
            proc.WaitForExit(timeoutMs);
            return text;
        }
        catch (Exception e)
        {
            Log.Write($"run {file}: {e.Message}");
            return "";
        }
    }

    // ---------------- History: DNS cache ----------------

    private static HashSet<string> _prevDns = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Names that appeared in the DNS cache since the last call (works for every app).</summary>
    public static List<string> NewDnsNames()
    {
        var text = Run("ipconfig", "/displaydns");
        var lines = text.Split('\n').Select(l => l.Trim()).ToList();
        var now = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        for (var i = 1; i < lines.Count; i++)
        {
            // each record starts with "<name>" followed by a line of dashes (independent of the Windows language)
            if (lines[i].Length > 5 && lines[i].All(c => c == '-'))
            {
                var name = lines[i - 1].ToLowerInvariant().TrimEnd('.');
                if (name.Contains('.') && !name.Contains(' ') && !name.EndsWith(".arpa") && !name.EndsWith(".local") && name != "localhost")
                    now.Add(name);
            }
        }
        var fresh = now.Where(n => !_prevDns.Contains(n)).ToList();
        _prevDns = now;
        return fresh;
    }

    // ---------------- History: browser databases ----------------

    public sealed record Visit(string Url, string Browser, long TimeMs);

    private static readonly Dictionary<string, long> LastVisit = new();

    private static IEnumerable<(string browser, string db, bool firefox)> HistoryDbs(string? onlyUser)
    {
        foreach (var userDir in Directory.GetDirectories(@"C:\Users"))
        {
            var user = Path.GetFileName(userDir);
            if (!string.IsNullOrEmpty(onlyUser) && !string.Equals(user, onlyUser, StringComparison.OrdinalIgnoreCase)) continue;
            var local = Path.Combine(userDir, @"AppData\Local");
            foreach (var (name, rel) in new[]
                     {
                         ("chrome", @"Google\Chrome\User Data"),
                         ("edge", @"Microsoft\Edge\User Data"),
                         ("brave", @"BraveSoftware\Brave-Browser\User Data"),
                     })
            {
                var root = Path.Combine(local, rel);
                if (!Directory.Exists(root)) continue;
                foreach (var profile in Directory.GetDirectories(root))
                {
                    var db = Path.Combine(profile, "History");
                    if (File.Exists(db)) yield return (name, db, false);
                }
            }
            var ff = Path.Combine(userDir, @"AppData\Roaming\Mozilla\Firefox\Profiles");
            if (Directory.Exists(ff))
                foreach (var profile in Directory.GetDirectories(ff))
                {
                    var db = Path.Combine(profile, "places.sqlite");
                    if (File.Exists(db)) yield return ("firefox", db, true);
                }
        }
    }

    public static List<Visit> NewVisits(string? onlyUser)
    {
        var result = new List<Visit>();
        var tmpDir = Path.Combine(Paths.Data, "tmp");
        Directory.CreateDirectory(tmpDir);
        List<(string browser, string db, bool firefox)> dbs;
        try { dbs = HistoryDbs(onlyUser).ToList(); }
        catch (Exception e) { Log.Write("history scan: " + e.Message); return result; }

        foreach (var (browser, db, firefox) in dbs)
        {
            var copy = Path.Combine(tmpDir, "h.sqlite");
            try
            {
                File.Copy(db, copy, true);
                if (File.Exists(db + "-wal")) File.Copy(db + "-wal", copy + "-wal", true);
                else if (File.Exists(copy + "-wal")) File.Delete(copy + "-wal");

                var nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                var since = LastVisit.TryGetValue(db, out var t) ? t : nowMs - 60 * 60 * 1000;
                long newest = since;
                using (var con = new SqliteConnection($"Data Source={copy};Mode=ReadOnly;Pooling=False"))
                {
                    con.Open();
                    using var cmd = con.CreateCommand();
                    if (firefox)
                    {
                        cmd.CommandText = "SELECT p.url, v.visit_date FROM moz_historyvisits v JOIN moz_places p ON p.id = v.place_id " +
                                          "WHERE v.visit_date > $t ORDER BY v.visit_date DESC LIMIT 300";
                        cmd.Parameters.AddWithValue("$t", since * 1000);
                    }
                    else
                    {
                        cmd.CommandText = "SELECT url, last_visit_time FROM urls WHERE last_visit_time > $t ORDER BY last_visit_time DESC LIMIT 300";
                        cmd.Parameters.AddWithValue("$t", (since + 11644473600000L) * 1000);
                    }
                    using var r = cmd.ExecuteReader();
                    while (r.Read())
                    {
                        var url = r.GetString(0);
                        var raw = r.GetInt64(1);
                        var ms = firefox ? raw / 1000 : raw / 1000 - 11644473600000L;
                        if (ms > newest) newest = ms;
                        if (url.StartsWith("http", StringComparison.OrdinalIgnoreCase)) result.Add(new Visit(url, browser, ms));
                    }
                }
                LastVisit[db] = newest;
            }
            catch (Exception e)
            {
                Log.Write($"history {browser}: {e.Message}");
            }
            finally
            {
                SqliteConnection.ClearAllPools();
            }
        }
        return result;
    }
}
