using System;
using System.IO;
using System.Text;
using System.Text.Json;

namespace ZemoryGuard;

internal static class Paths
{
    public static readonly string Data =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "ZemoryGuard");

    public static string File(string name)
    {
        Directory.CreateDirectory(Data);
        return Path.Combine(Data, name);
    }
}

/// <summary>Stored in C:\ProgramData\ZemoryGuard (readable only by SYSTEM and Administrators).</summary>
internal sealed class AppConfig
{
    public string Email { get; set; } = "";
    public string RefreshToken { get; set; } = "";
    public string DeviceId { get; set; } = "";
    public string ChildUser { get; set; } = "";

    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    public static AppConfig? Load()
    {
        try
        {
            var p = Paths.File("config.json");
            return System.IO.File.Exists(p) ? JsonSerializer.Deserialize<AppConfig>(System.IO.File.ReadAllText(p)) : null;
        }
        catch (Exception e)
        {
            Log.Write("config load: " + e.Message);
            return null;
        }
    }

    public void Save()
    {
        lock (Json) System.IO.File.WriteAllText(Paths.File("config.json"), JsonSerializer.Serialize(this, Json));
    }
}

internal static class Log
{
    private static readonly object Gate = new();

    public static void Write(string msg)
    {
        try
        {
            lock (Gate)
            {
                var p = Paths.File("log.txt");
                if (System.IO.File.Exists(p) && new FileInfo(p).Length > 1_000_000)
                    System.IO.File.Move(p, Paths.File("log.old.txt"), true);
                System.IO.File.AppendAllText(p, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss ") + msg + Environment.NewLine, Encoding.UTF8);
            }
        }
        catch
        {
            // logging must never crash the agent
        }
    }
}
