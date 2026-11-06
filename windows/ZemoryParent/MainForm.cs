using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Text.Json;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using Microsoft.Win32;

namespace ZemoryParent;

/// <summary>
/// Windows version of the parent app: hosts the same dashboard (./web) in a WebView2 window,
/// signs in with the same account as the Android parent app, and shows Windows notifications
/// for children's requests while it sits in the tray.
/// </summary>
internal sealed class MainForm : Form
{
    private const string Host = "zemory.parent";
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunName = "ZemoryParent";

    private readonly WebView2 web = new() { Dock = DockStyle.Fill };
    private readonly NotifyIcon tray = new();
    private readonly ToolStripMenuItem autoStart = new("تشغيل مع ويندوز");
    private bool reallyExit;

    // ---- entry password lock ----
    private readonly Panel lockPanel = new() { Dock = DockStyle.Fill, Visible = false, BackColor = Color.FromArgb(244, 246, 245) };
    private readonly TextBox pwBox = new() { UseSystemPasswordChar = true, Width = 280, RightToLeft = RightToLeft.Yes, Font = new Font("Segoe UI", 12f) };
    private readonly Label lockMsg = new() { AutoSize = false, Width = 280, Height = 24, ForeColor = Color.Firebrick, TextAlign = ContentAlignment.MiddleCenter, RightToLeft = RightToLeft.Yes };
    private readonly Panel lockBox = new() { Width = 320, Height = 230 };
    private readonly System.Windows.Forms.Timer idleTimer = new() { Interval = 15_000 };
    private bool locked;
    private int fails;
    private DateTime blockedUntil = DateTime.MinValue;

    public MainForm()
    {
        Text = "Zemory Family Guard";
        Icon = SystemIcons.Shield;
        Size = new Size(1180, 820);
        MinimumSize = new Size(420, 600);
        StartPosition = FormStartPosition.CenterScreen;
        Controls.Add(web);
        BuildLockPanel();
        Controls.Add(lockPanel);

        var menu = new ContextMenuStrip { RightToLeft = RightToLeft.Yes };
        menu.Items.Add("فتح", null, (_, _) => ShowWindow());
        menu.Items.Add("🔒 قفل الآن", null, (_, _) => { if (LockStore.HasPassword) ApplyLock(); else SetPasswordFlow(); });
        menu.Items.Add("كلمة مرور الدخول (تعيين / تغيير)…", null, (_, _) => SetPasswordFlow());
        menu.Items.Add("إزالة كلمة المرور…", null, (_, _) => RemovePasswordFlow());
        var idleMenu = new ToolStripMenuItem("القفل التلقائي عند عدم النشاط");
        foreach (var m in new[] { 1, 5, 15, 30, 60 })
        {
            var mm = m;
            var it = new ToolStripMenuItem(mm + " دقيقة") { CheckOnClick = false, Checked = LockStore.IdleMinutes == mm };
            it.Click += (_, _) =>
            {
                LockStore.SetIdleMinutes(mm);
                foreach (ToolStripMenuItem x in idleMenu.DropDownItems) x.Checked = ReferenceEquals(x, it);
            };
            idleMenu.DropDownItems.Add(it);
        }
        menu.Items.Add(idleMenu);
        menu.Items.Add(new ToolStripSeparator());
        autoStart.CheckOnClick = true;
        autoStart.Checked = IsAutoStart();
        autoStart.Click += (_, _) => SetAutoStart(autoStart.Checked);
        menu.Items.Add(autoStart);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("إغلاق نهائي", null, (_, _) => { reallyExit = true; Close(); });

        tray.Icon = SystemIcons.Shield;
        tray.Text = "Zemory Family Guard";
        tray.ContextMenuStrip = menu;
        tray.Visible = true;
        tray.DoubleClick += (_, _) => ShowWindow();
        tray.BalloonTipClicked += (_, _) => ShowWindow();

        Load += async (_, _) => await InitWebAsync();

        // the window starts locked when a password exists; otherwise offer to create one (once)
        if (LockStore.HasPassword) ApplyLock();
        Shown += (_, _) =>
        {
            if (!LockStore.HasPassword && !LockStore.AlreadyAsked)
            {
                var pw = PasswordDialog.AskNew(this, "حماية الدخول",
                    "يمكنك حماية تطبيق الوالد بكلمة مرور تُطلب عند كل فتح (وعند الخمول أو الإغلاق إلى شريط المهام).",
                    "ليس الآن");
                if (pw != null) LockStore.SetPassword(pw); else LockStore.MarkAsked();
            }
        };
        idleTimer.Tick += (_, _) =>
        {
            if (!locked && LockStore.HasPassword && Visible && LockStore.IdleTime() >= TimeSpan.FromMinutes(LockStore.IdleMinutes))
                ApplyLock();
        };
        idleTimer.Start();
        Resize += (_, _) =>
        {
            if (WindowState == FormWindowState.Minimized && LockStore.HasPassword) ApplyLock();
        };
    }

    private void BuildLockPanel()
    {
        var title = new Label { Text = "🔒 Zemory Family Guard", AutoSize = false, Width = 320, Height = 44, Top = 0, TextAlign = ContentAlignment.MiddleCenter, Font = new Font("Segoe UI", 15f, FontStyle.Bold) };
        var sub = new Label { Text = "أدخل كلمة مرور الدخول", AutoSize = false, Width = 320, Height = 26, Top = 48, TextAlign = ContentAlignment.MiddleCenter, RightToLeft = RightToLeft.Yes };
        pwBox.Left = 20; pwBox.Top = 88;
        lockMsg.Left = 20; lockMsg.Top = 126;
        var go = new Button { Text = "دخول", Width = 280, Height = 36, Left = 20, Top = 158, FlatStyle = FlatStyle.System };
        go.Click += (_, _) => TryUnlock();
        pwBox.KeyDown += (_, e) => { if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; TryUnlock(); } };
        lockBox.Controls.AddRange(new Control[] { title, sub, pwBox, lockMsg, go });
        lockPanel.Controls.Add(lockBox);
        lockPanel.Resize += (_, _) => CenterLockBox();
        CenterLockBox();
    }

    private void CenterLockBox()
    {
        lockBox.Left = Math.Max(0, (lockPanel.ClientSize.Width - lockBox.Width) / 2);
        lockBox.Top = Math.Max(0, (lockPanel.ClientSize.Height - lockBox.Height) / 2 - 20);
    }

    private void ApplyLock()
    {
        locked = true;
        web.Visible = false;
        lockPanel.Visible = true;
        lockPanel.BringToFront();
        CenterLockBox();
        pwBox.Clear();
        lockMsg.Text = "";
        if (Visible) pwBox.Focus();
    }

    private void TryUnlock()
    {
        var wait = blockedUntil - DateTime.Now;
        if (wait > TimeSpan.Zero)
        {
            lockMsg.Text = $"محاولات كثيرة. انتظر {Math.Ceiling(wait.TotalSeconds)} ثانية.";
            return;
        }
        if (LockStore.Verify(pwBox.Text))
        {
            fails = 0;
            locked = false;
            lockPanel.Visible = false;
            web.Visible = true;
            return;
        }
        fails++;
        pwBox.SelectAll();
        if (fails >= 5)
        {
            var secs = Math.Min(300, 15 * (1 << Math.Min(5, fails - 5)));
            blockedUntil = DateTime.Now.AddSeconds(secs);
            lockMsg.Text = $"محاولات كثيرة. انتظر {secs} ثانية.";
        }
        else lockMsg.Text = "كلمة المرور غير صحيحة.";
    }

    private void SetPasswordFlow()
    {
        if (LockStore.HasPassword && !PasswordDialog.Confirm(this, "تغيير كلمة المرور")) return;
        var pw = PasswordDialog.AskNew(this, "كلمة مرور الدخول", "اكتب كلمة المرور الجديدة التي تُطلب لفتح التطبيق.", "إلغاء");
        if (pw == null) return;
        LockStore.SetPassword(pw);
        tray.ShowBalloonTip(3000, "Zemory Family Guard", "تم حفظ كلمة مرور الدخول.", ToolTipIcon.Info);
    }

    private void RemovePasswordFlow()
    {
        if (!LockStore.HasPassword) return;
        if (!PasswordDialog.Confirm(this, "إزالة كلمة المرور")) return;
        LockStore.Remove();
        tray.ShowBalloonTip(3000, "Zemory Family Guard", "أُزيلت كلمة مرور الدخول.", ToolTipIcon.Info);
    }

    public void ShowWindow()
    {
        Show();
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        Activate();
        BringToFront();
        if (locked) pwBox.Focus();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // Closing the window keeps the app in the tray so request notifications keep arriving.
        if (!reallyExit && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            if (LockStore.HasPassword) ApplyLock();
            Hide();
            return;
        }
        tray.Visible = false;
        base.OnFormClosing(e);
    }

    private async System.Threading.Tasks.Task InitWebAsync()
    {
        try
        {
            var dataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ZemoryParent", "webview");
            var env = await CoreWebView2Environment.CreateAsync(null, dataDir);
            await web.EnsureCoreWebView2Async(env);
            var core = web.CoreWebView2;

            var exeDir = Path.GetDirectoryName(Environment.ProcessPath) ?? AppContext.BaseDirectory;
            var webDir = Path.Combine(exeDir, "web");
            if (!File.Exists(Path.Combine(webDir, "index.html")))
            {
                MessageBox.Show("مجلد web غير موجود بجانب البرنامج. فك ضغط الملف كاملاً ثم شغّله.", "Zemory Family Guard");
                reallyExit = true;
                Close();
                return;
            }
            core.SetVirtualHostNameToFolderMapping(Host, webDir, CoreWebView2HostResourceAccessKind.Allow);
            core.Settings.AreDevToolsEnabled = false;
            core.Settings.AreDefaultContextMenusEnabled = false;

            // Same bridge the Android parent app offers: notify(title, body, key)
            await core.AddScriptToExecuteOnDocumentCreatedAsync(
                "window.ZemoryDesktop=true;window.ZemoryApp={notify:function(t,b,k){window.chrome.webview.postMessage(JSON.stringify({t:t,b:b,k:k}));}};");
            core.WebMessageReceived += (_, e) =>
            {
                try
                {
                    using var doc = JsonDocument.Parse(e.TryGetWebMessageAsString());
                    var t = doc.RootElement.GetProperty("t").GetString() ?? "";
                    var b = doc.RootElement.GetProperty("b").GetString() ?? "";
                    tray.ShowBalloonTip(15000, t, b, ToolTipIcon.Info);
                }
                catch { }
            };
            core.NewWindowRequested += (_, e) =>
            {
                e.Handled = true;
                try { Process.Start(new ProcessStartInfo(e.Uri) { UseShellExecute = true }); } catch { }
            };
            core.NavigationStarting += (_, e) =>
            {
                // keep the app on the dashboard; send any other site to the default browser
                if (!e.Uri.StartsWith($"https://{Host}/", StringComparison.OrdinalIgnoreCase) &&
                    !e.Uri.StartsWith("about:", StringComparison.OrdinalIgnoreCase) &&
                    !e.Uri.StartsWith("data:", StringComparison.OrdinalIgnoreCase) &&
                    !e.Uri.Contains("firebaseapp.com") && !e.Uri.Contains("googleapis.com"))
                {
                    e.Cancel = true;
                    try { Process.Start(new ProcessStartInfo(e.Uri) { UseShellExecute = true }); } catch { }
                }
            };
            web.Source = new Uri($"https://{Host}/index.html");
        }
        catch (Exception ex)
        {
            var r = MessageBox.Show(
                "تعذّر تشغيل مكوّن العرض (WebView2). حمّله من موقع مايكروسوفت ثم أعد التشغيل.\n\nفتح صفحة التحميل الآن؟\n\n" + ex.Message,
                "Zemory Family Guard", MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
            if (r == DialogResult.Yes)
            {
                try { Process.Start(new ProcessStartInfo("https://go.microsoft.com/fwlink/p/?LinkId=2124703") { UseShellExecute = true }); } catch { }
            }
            reallyExit = true;
            Close();
        }
    }

    private static bool IsAutoStart()
    {
        using var k = Registry.CurrentUser.OpenSubKey(RunKey);
        return k?.GetValue(RunName) != null;
    }

    private static void SetAutoStart(bool on)
    {
        using var k = Registry.CurrentUser.CreateSubKey(RunKey);
        if (on) k.SetValue(RunName, "\"" + Environment.ProcessPath + "\"");
        else k.DeleteValue(RunName, false);
    }
}
