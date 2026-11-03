using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace ZemoryGuard;

/// <summary>
/// Runs in the child's desktop session (started by the service as that user):
/// tracks the foreground app, shows block messages with the owner PIN, locks the screen at bedtime,
/// and lets the child request a website.
/// </summary>
internal static class HelperApp
{
    public static void Run()
    {
        using var mutex = new Mutex(true, @"Local\ZemoryGuardHelper", out var created);
        if (!created) return;
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.SetHighDpiMode(HighDpiMode.SystemAware);
        Application.Run(new HelperContext());
    }
}

internal sealed class HelperContext : ApplicationContext
{
    private readonly HttpClient _http = new()
    {
        BaseAddress = new Uri($"http://127.0.0.1:{AgentService.ApiPort}/"),
        Timeout = TimeSpan.FromSeconds(8),
    };
    private readonly uint _session = (uint)Process.GetCurrentProcess().SessionId;
    private readonly NotifyIcon _tray;
    private readonly System.Windows.Forms.Timer _fgTimer = new() { Interval = 5000 };
    private readonly System.Windows.Forms.Timer _sendTimer = new() { Interval = 30000 };
    private readonly System.Windows.Forms.Timer _pollTimer = new() { Interval = 3000 };
    private readonly Dictionary<string, (double s, string path, string label, string version)> _pending = new();
    private readonly Dictionary<int, (string exe, string path, string label, string version)> _procCache = new();
    private BlockForm? _blockForm;
    private bool _sending, _polling;

    public HelperContext()
    {
        var menu = new ContextMenuStrip { RightToLeft = RightToLeft.Yes };
        menu.Items.Add("طلب السماح بموقع…", null, (_, _) => new RequestForm(_http, _session).Show());
        _tray = new NotifyIcon
        {
            Icon = SystemIcons.Shield,
            Text = "Zemory Family Guard",
            Visible = true,
            ContextMenuStrip = menu,
        };
        _tray.DoubleClick += (_, _) => new RequestForm(_http, _session).Show();

        _fgTimer.Tick += (_, _) => TrackForeground();
        _sendTimer.Tick += async (_, _) => await SendUsageAsync();
        _pollTimer.Tick += async (_, _) => await PollAsync();
        _fgTimer.Start();
        _sendTimer.Start();
        _pollTimer.Start();
    }

    private void TrackForeground()
    {
        try
        {
            if (Native.IdleMs() > 5 * 60 * 1000) return; // nobody is using the computer
            var hwnd = Native.GetForegroundWindow();
            if (hwnd == IntPtr.Zero) return;
            Native.GetWindowThreadProcessId(hwnd, out var pid);
            if (pid == 0) return;
            if (!_procCache.TryGetValue((int)pid, out var info))
            {
                using var p = Process.GetProcessById((int)pid);
                var exe = p.ProcessName.ToLowerInvariant() + ".exe";
                string path = "", label = "", version = "";
                try
                {
                    path = p.MainModule?.FileName ?? "";
                    if (path.Length > 0)
                    {
                        var vi = FileVersionInfo.GetVersionInfo(path);
                        label = (vi.FileDescription ?? "").Trim();
                        if (label.Length == 0) label = (vi.ProductName ?? "").Trim();
                        version = vi.ProductVersion ?? "";
                    }
                }
                catch
                {
                    // access denied for some processes
                }
                info = (exe, path, label, version);
                if (_procCache.Count > 300) _procCache.Clear();
                _procCache[(int)pid] = info;
            }
            if (info.exe is "explorer.exe" or "lockapp.exe" or "zemoryguard.exe" or "searchhost.exe" or "startmenuexperiencehost.exe") return;
            var cur = _pending.TryGetValue(info.exe, out var c) ? c.s : 0;
            _pending[info.exe] = (cur + 5, info.path, info.label, info.version);
        }
        catch
        {
            // the window closed in the meantime
        }
    }

    private async Task SendUsageAsync()
    {
        if (_sending) return;
        _sending = true;
        try
        {
            var items = new JsonObject();
            foreach (var (exe, v) in _pending)
                items[exe] = new JsonObject { ["s"] = v.s, ["p"] = v.path, ["l"] = v.label, ["v"] = v.version };
            var body = new JsonObject { ["items"] = items };
            var res = await _http.PostAsync($"usage?s={_session}", new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"));
            if (res.IsSuccessStatusCode) _pending.Clear();
        }
        catch
        {
            // service not reachable: keep the data for the next try
        }
        finally
        {
            _sending = false;
        }
    }

    private async Task PollAsync()
    {
        if (_polling) return;
        _polling = true;
        try
        {
            var text = await _http.GetStringAsync($"events?s={_session}");
            if (JsonNode.Parse(text)?["events"] is not JsonArray events) return;
            foreach (var ev in events.OfType<JsonObject>())
            {
                var type = ev["type"]?.GetValue<string>();
                if (type == "lock")
                {
                    Native.LockWorkStation();
                }
                else if (type == "blocked")
                {
                    var exe = ev["exe"]?.GetValue<string>() ?? "";
                    var reason = ev["reason"]?.GetValue<string>() ?? "blocked";
                    var label = ev["label"]?.GetValue<string>() ?? exe;
                    if (_blockForm == null || _blockForm.IsDisposed)
                    {
                        _blockForm = new BlockForm(_http, _session);
                    }
                    _blockForm.ShowFor(exe, label, reason);
                }
            }
        }
        catch
        {
            // ignore, try again in 3 seconds
        }
        finally
        {
            _polling = false;
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _tray.Visible = false;
            _tray.Dispose();
        }
        base.Dispose(disposing);
    }
}

/// <summary>Shared look for the helper windows (Arabic, right-to-left, always on top).</summary>
internal class HelperForm : Form
{
    protected HelperForm()
    {
        RightToLeft = RightToLeft.Yes;
        RightToLeftLayout = true;
        TopMost = true;
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        ShowInTaskbar = true;
        Font = new Font("Segoe UI", 11f);
        Text = "Zemory Family Guard";
        ClientSize = new Size(460, 300);
        Padding = new Padding(16);
        BackColor = Color.White;
    }

    protected static Label MakeLabel(string text, float size, bool bold = false) => new()
    {
        Text = text,
        AutoSize = false,
        Dock = DockStyle.Top,
        Height = size > 14 ? 44 : 30,
        TextAlign = ContentAlignment.MiddleCenter,
        Font = new Font("Segoe UI", size, bold ? FontStyle.Bold : FontStyle.Regular),
    };
}

internal sealed class BlockForm : HelperForm
{
    private readonly HttpClient _http;
    private readonly uint _session;
    private readonly Label _title = MakeLabel("", 16, true);
    private readonly Label _app = MakeLabel("", 11);
    private readonly Label _msg = MakeLabel("", 10);
    private readonly TextBox _pin = new() { UseSystemPasswordChar = true, Width = 160, TextAlign = HorizontalAlignment.Center };
    private readonly Button _ask = new() { Text = "📨 اطلب من المالك", AutoSize = true };
    private string _exe = "";
    private string _label = "";
    private string _reason = "";

    public BlockForm(HttpClient http, uint session)
    {
        _http = http;
        _session = session;
        ClientSize = new Size(580, 300); // wide enough for the PIN box and three buttons on one row
        var allow = new Button { Text = "سماح برمز المالك", AutoSize = true };
        var close = new Button { Text = "إغلاق", AutoSize = true };
        allow.Click += async (_, _) => await CheckPinAsync();
        _ask.Click += async (_, _) => await AskOwnerAsync();
        close.Click += (_, _) => Hide();
        AcceptButton = allow;
        var row = new FlowLayoutPanel
        {
            Dock = DockStyle.Top,
            Height = 48,
            FlowDirection = FlowDirection.RightToLeft,
            Padding = new Padding(16, 8, 16, 0),
        };
        row.Controls.Add(_pin);
        row.Controls.Add(allow);
        row.Controls.Add(_ask);
        row.Controls.Add(close);
        _msg.ForeColor = Color.DimGray;
        Controls.Add(row);
        Controls.Add(_msg);
        Controls.Add(MakeLabel("رمز المالك:", 10));
        Controls.Add(_app);
        Controls.Add(_title);
        FormClosing += (_, e) =>
        {
            if (e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
            }
        };
    }

    public void ShowFor(string exe, string label, string reason)
    {
        _exe = exe;
        _label = label;
        _reason = reason;
        _ask.Visible = reason != "pin" && reason != "browser";
        _ask.Enabled = true;
        _title.Text = reason switch
        {
            "pin" => "🔒 هذا البرنامج مقفل",
            "limit" => "⏳ انتهى وقت الاستخدام لهذا اليوم",
            "bedtime" => "🌙 حان وقت النوم",
            "paused" => "⏸ الأجهزة متوقفة مؤقتاً",
            "screen" => "🕒 انتهى وقت الشاشة",
            "browser" => "🌐 هذا المتصفح غير مسموح",
            _ => "🚫 هذا البرنامج محظور",
        };
        _app.Text = label;
        _msg.Text = reason == "pin" ? "أدخل رمز المالك لفتحه 10 دقائق" : "يمكن للمالك السماح مؤقتاً لمدة 30 دقيقة";
        _msg.ForeColor = Color.DimGray;
        _pin.Text = "";
        if (!Visible) Show();
        Activate();
        _pin.Focus();
    }

    /// <summary>Sends a request to the owner (parent app) to allow this program.</summary>
    private async Task AskOwnerAsync()
    {
        _ask.Enabled = false;
        try
        {
            var body = new JsonObject { ["kind"] = "app", ["exe"] = _exe, ["label"] = _label, ["reason"] = _reason };
            var res = await _http.PostAsync($"request?s={_session}", new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"));
            var ok = JsonNode.Parse(await res.Content.ReadAsStringAsync())?["ok"]?.GetValue<bool>() == true;
            _msg.Text = ok ? "✅ تم إرسال الطلب إلى المالك. حاول فتح البرنامج بعد موافقته." : "تعذّر إرسال الطلب";
            _msg.ForeColor = ok ? Color.SeaGreen : Color.Firebrick;
            if (!ok) _ask.Enabled = true;
        }
        catch
        {
            _msg.Text = "تعذّر الاتصال بخدمة الحماية";
            _msg.ForeColor = Color.Firebrick;
            _ask.Enabled = true;
        }
    }

    private async Task CheckPinAsync()
    {
        try
        {
            var body = new JsonObject { ["exe"] = _exe, ["reason"] = _reason, ["pin"] = _pin.Text.Trim() };
            var res = await _http.PostAsync($"pin?s={_session}", new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"));
            var j = JsonNode.Parse(await res.Content.ReadAsStringAsync());
            if (j?["ok"]?.GetValue<bool>() == true)
            {
                Hide();
                MessageBox.Show("تم السماح. أعد فتح البرنامج.", "Zemory Family Guard", MessageBoxButtons.OK,
                    MessageBoxIcon.Information, MessageBoxDefaultButton.Button1, MessageBoxOptions.RightAlign | MessageBoxOptions.RtlReading);
                return;
            }
            _msg.Text = j?["pinSet"]?.GetValue<bool>() == false ? "لم يتم تعيين رمز المالك" : "رمز غير صحيح";
            _msg.ForeColor = Color.Firebrick;
            _pin.SelectAll();
        }
        catch
        {
            _msg.Text = "تعذّر الاتصال بخدمة الحماية";
            _msg.ForeColor = Color.Firebrick;
        }
    }
}

internal sealed class RequestForm : HelperForm
{
    public RequestForm(HttpClient http, uint session)
    {
        ClientSize = new Size(480, 240);
        var url = new TextBox { Dock = DockStyle.Top, RightToLeft = RightToLeft.No, PlaceholderText = "https://example.com" };
        var status = MakeLabel("", 10);
        var send = new Button { Text = "📨 إرسال الطلب", Dock = DockStyle.Top, Height = 40 };
        send.Click += async (_, _) =>
        {
            if (Domains.Normalize(url.Text) == null)
            {
                status.Text = "اكتب رابطاً صحيحاً";
                status.ForeColor = Color.Firebrick;
                return;
            }
            send.Enabled = false;
            try
            {
                var body = new JsonObject { ["url"] = url.Text.Trim() };
                var res = await http.PostAsync($"request?s={session}", new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json"));
                var ok = JsonNode.Parse(await res.Content.ReadAsStringAsync())?["ok"]?.GetValue<bool>() == true;
                status.Text = ok ? "✅ تم إرسال الطلب إلى المالك" : "تعذّر الإرسال";
                status.ForeColor = ok ? Color.SeaGreen : Color.Firebrick;
            }
            catch
            {
                status.Text = "تعذّر الاتصال بخدمة الحماية";
                status.ForeColor = Color.Firebrick;
            }
            send.Enabled = true;
        };
        AcceptButton = send;
        Controls.Add(status);
        Controls.Add(send);
        Controls.Add(new Panel { Dock = DockStyle.Top, Height = 10 });
        Controls.Add(url);
        Controls.Add(MakeLabel("اكتب رابط الموقع الذي تريد زيارته:", 11));
        Controls.Add(MakeLabel("📨 طلب السماح بموقع", 15, true));
    }
}
