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

    public MainForm()
    {
        Text = "Zemory Family Guard";
        Icon = SystemIcons.Shield;
        Size = new Size(1180, 820);
        MinimumSize = new Size(420, 600);
        StartPosition = FormStartPosition.CenterScreen;
        Controls.Add(web);

        var menu = new ContextMenuStrip { RightToLeft = RightToLeft.Yes };
        menu.Items.Add("فتح", null, (_, _) => ShowWindow());
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
    }

    public void ShowWindow()
    {
        Show();
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        Activate();
        BringToFront();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // Closing the window keeps the app in the tray so request notifications keep arriving.
        if (!reallyExit && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
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
