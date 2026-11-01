using System;
using System.Security.Principal;
using Microsoft.Win32;

namespace ZemoryGuard;

/// <summary>Windows restrictions toggled from the dashboard (keys start with "win_").</summary>
internal static class WinRestrictions
{
    private sealed record Rule(string Key, bool PerUser, string Path, string Value, int On);

    private static readonly Rule[] Rules =
    {
        new("win_no_taskmgr", true, @"Software\Microsoft\Windows\CurrentVersion\Policies\System", "DisableTaskMgr", 1),
        new("win_no_regedit", true, @"Software\Microsoft\Windows\CurrentVersion\Policies\System", "DisableRegistryTools", 1),
        new("win_no_cmd", true, @"Software\Policies\Microsoft\Windows\System", "DisableCMD", 1),
        new("win_no_settings", true, @"Software\Microsoft\Windows\CurrentVersion\Policies\Explorer", "NoControlPanel", 1),
        new("win_no_run", true, @"Software\Microsoft\Windows\CurrentVersion\Policies\Explorer", "NoRun", 1),
        new("win_no_store", false, @"SOFTWARE\Policies\Microsoft\WindowsStore", "RemoveWindowsStore", 1),
        new("win_no_msi", false, @"SOFTWARE\Policies\Microsoft\Windows\Installer", "DisableUserInstalls", 1),
        new("win_no_usb_storage", false, @"SYSTEM\CurrentControlSet\Services\USBSTOR", "Start", 4),
    };

    private static string _lastSig = "";

    public static string? SidOf(string user)
    {
        if (string.IsNullOrWhiteSpace(user)) return null;
        try
        {
            return ((SecurityIdentifier)new NTAccount(Environment.MachineName, user).Translate(typeof(SecurityIdentifier))).Value;
        }
        catch
        {
            return null;
        }
    }

    public static void Apply(Policy p, string childUser, bool force = false)
    {
        var sid = SidOf(childUser);
        // the child's registry hive is only loaded while they are logged on, so re-check regularly
        var hiveLoaded = sid != null && Registry.Users.OpenSubKey(sid) != null;
        var sig = string.Join(",", Array.ConvertAll(Rules, r => p.Restricted(r.Key) ? "1" : "0")) + "|" + hiveLoaded;
        if (!force && sig == _lastSig) return;
        _lastSig = sig;

        foreach (var r in Rules)
        {
            try
            {
                var on = p.Restricted(r.Key);
                RegistryKey? root;
                string path;
                if (r.PerUser)
                {
                    if (!hiveLoaded) continue; // per-user rules need a specific child account
                    root = Registry.Users;
                    path = sid + "\\" + r.Path;
                }
                else
                {
                    root = Registry.LocalMachine;
                    path = r.Path;
                }

                if (r.Key == "win_no_usb_storage")
                {
                    using var usb = root.OpenSubKey(path, true);
                    usb?.SetValue("Start", on ? 4 : 3, RegistryValueKind.DWord);
                    continue;
                }
                if (on)
                {
                    using var k = root.CreateSubKey(path, true);
                    k.SetValue(r.Value, r.On, RegistryValueKind.DWord);
                }
                else
                {
                    using var k = root.OpenSubKey(path, true);
                    if (k?.GetValue(r.Value) != null) k.DeleteValue(r.Value, false);
                }
            }
            catch (Exception e)
            {
                Log.Write("restriction " + r.Key + ": " + e.Message);
            }
        }
    }
}
