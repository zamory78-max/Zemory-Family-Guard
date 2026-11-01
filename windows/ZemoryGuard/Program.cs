using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading.Tasks;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace ZemoryGuard;

/// <summary>
/// ZemoryGuard.exe service   -> Windows service (LocalSystem): sync with Firebase and enforce rules
/// ZemoryGuard.exe helper    -> runs in the child's desktop session (started by the service)
/// ZemoryGuard.exe link      -> one-time linking with the device account (run from install.bat)
/// ZemoryGuard.exe cleanup   -> removes all policies (run from uninstall.bat)
/// </summary>
internal static class Program
{
    public const string Version = "1.0.0";

    [STAThread]
    private static int Main(string[] args)
    {
        var mode = args.FirstOrDefault()?.ToLowerInvariant() ?? "";
        try
        {
            switch (mode)
            {
                case "service":
                    var builder = Host.CreateApplicationBuilder(args);
                    builder.Services.AddWindowsService(o => o.ServiceName = "ZemoryGuard");
                    builder.Services.AddHostedService<AgentService>();
                    builder.Build().Run();
                    return 0;
                case "helper":
                    HelperApp.Run();
                    return 0;
                case "link":
                    return LinkAsync().GetAwaiter().GetResult();
                case "cleanup":
                    Cleanup();
                    return 0;
                default:
                    Console.WriteLine("Zemory Family Guard " + Version);
                    Console.WriteLine("Run install.bat as Administrator to install.");
                    return 0;
            }
        }
        catch (Exception e)
        {
            Log.Write("fatal " + mode + ": " + e);
            if (mode == "link")
            {
                Console.WriteLine("Error: " + e.Message);
                return 1;
            }
            throw;
        }
    }

    private static string ReadPassword()
    {
        var sb = new StringBuilder();
        while (true)
        {
            var k = Console.ReadKey(true);
            if (k.Key == ConsoleKey.Enter) break;
            if (k.Key == ConsoleKey.Backspace)
            {
                if (sb.Length > 0) { sb.Length--; Console.Write("\b \b"); }
                continue;
            }
            if (!char.IsControl(k.KeyChar)) { sb.Append(k.KeyChar); Console.Write('*'); }
        }
        Console.WriteLine();
        return sb.ToString();
    }

    private static async Task<int> LinkAsync()
    {
        Console.WriteLine("=== Zemory Family Guard - link this computer ===");
        Console.WriteLine("Use the device e-mail and password created in the parent app (Add device -> Windows computer).");
        Console.WriteLine();
        if (string.IsNullOrEmpty(FirebaseConfig.ApiKey))
        {
            Console.WriteLine("This build has no Firebase configuration.");
            return 1;
        }

        for (var attempt = 0; attempt < 3; attempt++)
        {
            Console.Write("Device e-mail: ");
            var email = (Console.ReadLine() ?? "").Trim();
            Console.Write("Password: ");
            var password = ReadPassword();

            try
            {
                var (uid, refresh) = await Firebase.SignInAsync(email, password);
                var cfg = new AppConfig { Email = email, RefreshToken = refresh };
                var fb = new Firebase(cfg);
                var found = await fb.QueryAsync("", "devices", "deviceUid", uid, 1);
                if (found.Count == 0)
                {
                    Console.WriteLine("This account is not linked to any device. Add it first in the parent app.");
                    continue;
                }
                cfg.DeviceId = found[0].id;

                var users = Directory.GetDirectories(@"C:\Users")
                    .Select(Path.GetFileName)
                    .Where(n => n != null && !new[] { "Public", "Default", "Default User", "All Users", "defaultuser0" }.Contains(n))
                    .ToList();
                Console.WriteLine();
                Console.WriteLine("Windows accounts on this computer: " + string.Join(", ", users));
                Console.WriteLine("Type the Windows user name of the child (rules apply only to that account),");
                Console.Write("or leave empty to apply the rules to every account: ");
                cfg.ChildUser = (Console.ReadLine() ?? "").Trim();

                cfg.Save();
                await fb.HeartbeatAsync(cfg.DeviceId, Status.Collect(helperOk: true), unlinkedFalse: true);
                Console.WriteLine();
                Console.WriteLine("Linked successfully to device " + cfg.DeviceId + ".");
                return 0;
            }
            catch (Exception e)
            {
                Console.WriteLine("Sign-in failed: " + e.Message);
            }
        }
        return 1;
    }

    private static void Cleanup()
    {
        var empty = Policy.From(new JsonObject());
        WebControl.ApplyBrowserPolicies(empty, force: true);
        WebControl.ApplyHosts(empty, force: true);
        var cfg = AppConfig.Load();
        WinRestrictions.Apply(empty, cfg?.ChildUser ?? "", force: true);
        Console.WriteLine("All Zemory Family Guard policies were removed.");
    }
}
