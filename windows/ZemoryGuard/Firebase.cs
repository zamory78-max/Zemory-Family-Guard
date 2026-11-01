using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace ZemoryGuard;

/// <summary>Minimal Firebase Auth + Cloud Firestore REST client (no SDK available for this use on Windows).</summary>
internal sealed class Firebase
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(40) };
    private readonly AppConfig _cfg;
    private readonly SemaphoreSlim _tokenLock = new(1, 1);
    private string? _idToken;
    private DateTime _idExpiry = DateTime.MinValue;

    public Firebase(AppConfig cfg) => _cfg = cfg;

    private static string Root => $"projects/{FirebaseConfig.ProjectId}/databases/(default)/documents";
    private static string Base => "https://firestore.googleapis.com/v1/" + Root;
    public static string DocName(string path) => Root + "/" + path;

    // ---------------- Auth ----------------

    public static async Task<(string uid, string refreshToken)> SignInAsync(string email, string password)
    {
        var body = new JsonObject { ["email"] = email, ["password"] = password, ["returnSecureToken"] = true };
        var res = await Http.PostAsync(
            "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=" + FirebaseConfig.ApiKey,
            new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"));
        var text = await res.Content.ReadAsStringAsync();
        if (!res.IsSuccessStatusCode)
        {
            var msg = JsonNode.Parse(text)?["error"]?["message"]?.GetValue<string>() ?? res.StatusCode.ToString();
            throw new Exception(msg.Contains("INVALID") ? "wrong e-mail or password" : msg);
        }
        var j = JsonNode.Parse(text)!;
        return (j["localId"]!.GetValue<string>(), j["refreshToken"]!.GetValue<string>());
    }

    private async Task<string> TokenAsync()
    {
        await _tokenLock.WaitAsync();
        try
        {
            if (_idToken != null && DateTime.UtcNow < _idExpiry) return _idToken;
            var form = new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["grant_type"] = "refresh_token",
                ["refresh_token"] = _cfg.RefreshToken,
            });
            var res = await Http.PostAsync("https://securetoken.googleapis.com/v1/token?key=" + FirebaseConfig.ApiKey, form);
            var text = await res.Content.ReadAsStringAsync();
            if (!res.IsSuccessStatusCode) throw new Exception("token refresh failed: " + text);
            var j = JsonNode.Parse(text)!;
            _idToken = j["id_token"]!.GetValue<string>();
            var expires = int.Parse(j["expires_in"]?.GetValue<string>() ?? "3600");
            _idExpiry = DateTime.UtcNow.AddSeconds(expires - 300);
            var newRefresh = j["refresh_token"]?.GetValue<string>();
            if (!string.IsNullOrEmpty(newRefresh) && newRefresh != _cfg.RefreshToken)
            {
                _cfg.RefreshToken = newRefresh;
                _cfg.Save();
            }
            return _idToken;
        }
        finally
        {
            _tokenLock.Release();
        }
    }

    private async Task<JsonNode?> SendAsync(HttpMethod method, string url, JsonNode? body)
    {
        for (var attempt = 0; attempt < 2; attempt++)
        {
            var req = new HttpRequestMessage(method, url);
            req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", await TokenAsync());
            if (body != null) req.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
            var res = await Http.SendAsync(req);
            var text = await res.Content.ReadAsStringAsync();
            if (res.StatusCode == HttpStatusCode.Unauthorized && attempt == 0)
            {
                _idToken = null;
                continue;
            }
            if (res.StatusCode == HttpStatusCode.NotFound) return null;
            if (!res.IsSuccessStatusCode) throw new Exception($"{method} {url} -> {(int)res.StatusCode}: {text}");
            return string.IsNullOrWhiteSpace(text) ? new JsonObject() : JsonNode.Parse(text);
        }
        return null;
    }

    // ---------------- Documents ----------------

    public async Task<JsonObject?> GetDocAsync(string path)
    {
        var j = await SendAsync(HttpMethod.Get, Base + "/" + path, null);
        if (j == null) return null;
        return FromFields(j["fields"] as JsonObject);
    }

    /// <summary>Documents of a collection (under parentPath, "" for root) where field == value.</summary>
    public async Task<List<(string id, JsonObject data)>> QueryAsync(string parentPath, string collection, string field, string value, int limit)
    {
        var q = new JsonObject
        {
            ["structuredQuery"] = new JsonObject
            {
                ["from"] = new JsonArray(new JsonObject { ["collectionId"] = collection }),
                ["where"] = new JsonObject
                {
                    ["fieldFilter"] = new JsonObject
                    {
                        ["field"] = new JsonObject { ["fieldPath"] = field },
                        ["op"] = "EQUAL",
                        ["value"] = new JsonObject { ["stringValue"] = value },
                    },
                },
                ["limit"] = limit,
            },
        };
        var url = Base + (parentPath.Length > 0 ? "/" + parentPath : "") + ":runQuery";
        var res = await SendAsync(HttpMethod.Post, url, q) as JsonArray;
        var list = new List<(string, JsonObject)>();
        if (res == null) return list;
        foreach (var item in res)
        {
            if (item?["document"] is not JsonObject d) continue;
            var name = d["name"]!.GetValue<string>();
            list.Add((name[(name.LastIndexOf('/') + 1)..], FromFields(d["fields"] as JsonObject)));
        }
        return list;
    }

    public Task CommitAsync(params JsonObject[] writes)
    {
        var arr = new JsonArray();
        foreach (var w in writes) arr.Add(w);
        return SendAsync(HttpMethod.Post, Base + ":commit", new JsonObject { ["writes"] = arr });
    }

    /// <summary>An update write. With a mask only the listed fields change, otherwise the document is replaced.</summary>
    public static JsonObject UpdateWrite(string path, Dictionary<string, object?> fields, IEnumerable<string>? mask = null,
        IEnumerable<JsonObject>? transforms = null)
    {
        var w = new JsonObject
        {
            ["update"] = new JsonObject { ["name"] = DocName(path), ["fields"] = ToFields(fields) },
        };
        if (mask != null)
        {
            var m = new JsonArray();
            foreach (var f in mask) m.Add(f);
            w["updateMask"] = new JsonObject { ["fieldPaths"] = m };
        }
        if (transforms != null)
        {
            var t = new JsonArray();
            foreach (var x in transforms) t.Add(x);
            if (t.Count > 0) w["updateTransforms"] = t;
        }
        return w;
    }

    public static JsonObject ServerTime(string fieldPath) =>
        new() { ["fieldPath"] = fieldPath, ["setToServerValue"] = "REQUEST_TIME" };

    public static JsonObject Increment(string fieldPath, long by) =>
        new() { ["fieldPath"] = fieldPath, ["increment"] = new JsonObject { ["integerValue"] = by.ToString() } };

    /// <summary>Quote a field path segment (map keys such as domain names need backticks).</summary>
    public static string Seg(string s) =>
        Regex.IsMatch(s, "^[A-Za-z_][A-Za-z_0-9]*$") ? s : "`" + s.Replace("\\", "\\\\").Replace("`", "\\`") + "`";

    // ---------------- Helpers used by the agent ----------------

    public Task HeartbeatAsync(string deviceId, Dictionary<string, object?> status, bool unlinkedFalse = false)
    {
        var fields = new Dictionary<string, object?>
        {
            ["status"] = status,
            ["appVersion"] = "win-" + Program.Version,
            ["model"] = Environment.MachineName + " · Windows",
            ["sdk"] = (long)Environment.OSVersion.Version.Build,
        };
        var mask = new List<string> { "status", "appVersion", "model", "sdk" };
        if (unlinkedFalse)
        {
            fields["unlinked"] = false;
            mask.Add("unlinked");
        }
        return CommitAsync(UpdateWrite("devices/" + deviceId, fields, mask, new[] { ServerTime("lastSeen") }));
    }

    public Task CommandResultAsync(string deviceId, string cmdId, string status, string message) =>
        CommitAsync(UpdateWrite($"devices/{deviceId}/commands/{cmdId}",
            new Dictionary<string, object?> { ["status"] = status, ["result"] = message },
            new[] { "status", "result" }, new[] { ServerTime("doneAt") }));

    // ---------------- Value conversion ----------------

    public static JsonObject FromFields(JsonObject? fields)
    {
        var o = new JsonObject();
        if (fields == null) return o;
        foreach (var (k, v) in fields) o[k] = FromValue(v as JsonObject);
        return o;
    }

    private static JsonNode? FromValue(JsonObject? v)
    {
        if (v == null) return null;
        if (v["stringValue"] is JsonNode s) return s.GetValue<string>();
        if (v["booleanValue"] is JsonNode b) return b.GetValue<bool>();
        if (v["integerValue"] is JsonNode i) return long.Parse(i.GetValue<string>());
        if (v["doubleValue"] is JsonNode d) return d.GetValue<double>();
        if (v["timestampValue"] is JsonNode t) return t.GetValue<string>();
        if (v["mapValue"] is JsonObject m) return FromFields(m["fields"] as JsonObject);
        if (v["arrayValue"] is JsonObject a)
        {
            var arr = new JsonArray();
            if (a["values"] is JsonArray vals) foreach (var x in vals) arr.Add(FromValue(x as JsonObject));
            return arr;
        }
        return null;
    }

    public static JsonObject ToFields(Dictionary<string, object?> map)
    {
        var o = new JsonObject();
        foreach (var (k, v) in map) o[k] = ToValue(v);
        return o;
    }

    private static JsonObject ToValue(object? v) => v switch
    {
        null => new JsonObject { ["nullValue"] = null },
        bool b => new JsonObject { ["booleanValue"] = b },
        int i => new JsonObject { ["integerValue"] = i.ToString() },
        long l => new JsonObject { ["integerValue"] = l.ToString() },
        double d => new JsonObject { ["doubleValue"] = d },
        string s => new JsonObject { ["stringValue"] = s },
        Dictionary<string, object?> m => new JsonObject { ["mapValue"] = new JsonObject { ["fields"] = ToFields(m) } },
        IEnumerable<object?> list => new JsonObject
        {
            ["arrayValue"] = new JsonObject { ["values"] = new JsonArray(list.Select(x => (JsonNode)ToValue(x)).ToArray()) },
        },
        _ => new JsonObject { ["stringValue"] = v.ToString() },
    };
}
