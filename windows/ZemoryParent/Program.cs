using System;
using System.Threading;
using System.Windows.Forms;

namespace ZemoryParent;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        using var mutex = new Mutex(true, "Local\\ZemoryParentApp", out var first);
        using var wake = new EventWaitHandle(false, EventResetMode.AutoReset, "Local\\ZemoryParentWake");
        if (!first)
        {
            wake.Set(); // an instance is already running: just bring its window forward
            return;
        }

        ApplicationConfiguration.Initialize();
        var form = new MainForm();
        var t = new Thread(() =>
        {
            while (wake.WaitOne())
            {
                try { form.BeginInvoke(new Action(form.ShowWindow)); } catch { return; }
            }
        }) { IsBackground = true };
        t.Start();
        Application.Run(form);
    }
}
