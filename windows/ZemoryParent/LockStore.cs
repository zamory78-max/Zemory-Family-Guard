using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;

namespace ZemoryParent;

/// <summary>Stores the app-entry password as a salted PBKDF2 hash (never the password itself).</summary>
internal static class LockStore
{
    private sealed class Data
    {
        public string Salt { get; set; } = "";
        public string Hash { get; set; } = "";
        public int Iterations { get; set; } = 150_000;
        public bool Asked { get; set; }
        public int IdleMinutes { get; set; } = 5;
    }

    private static string FilePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ZemoryParent", "lock.json");

    private static Data Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<Data>(File.ReadAllText(FilePath)) ?? new Data();
        }
        catch { }
        return new Data();
    }

    private static void Save(Data d)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(d));
    }

    private static byte[] Derive(string password, byte[] salt, int iterations) =>
        Rfc2898DeriveBytes.Pbkdf2(password, salt, iterations, HashAlgorithmName.SHA256, 32);

    public static bool HasPassword => !string.IsNullOrEmpty(Load().Hash);

    public static bool AlreadyAsked => Load().Asked;

    public static void MarkAsked()
    {
        var d = Load();
        d.Asked = true;
        Save(d);
    }

    public static int IdleMinutes => Math.Max(1, Load().IdleMinutes);

    public static void SetIdleMinutes(int m)
    {
        var d = Load();
        d.IdleMinutes = Math.Clamp(m, 1, 120);
        Save(d);
    }

    public static void SetPassword(string password)
    {
        var d = Load();
        var salt = RandomNumberGenerator.GetBytes(16);
        d.Salt = Convert.ToBase64String(salt);
        d.Iterations = 150_000;
        d.Hash = Convert.ToBase64String(Derive(password, salt, d.Iterations));
        d.Asked = true;
        Save(d);
    }

    public static bool Verify(string password)
    {
        var d = Load();
        if (string.IsNullOrEmpty(d.Hash)) return true;
        try
        {
            var calc = Derive(password, Convert.FromBase64String(d.Salt), d.Iterations);
            return CryptographicOperations.FixedTimeEquals(calc, Convert.FromBase64String(d.Hash));
        }
        catch { return false; }
    }

    public static void Remove()
    {
        var d = Load();
        d.Salt = "";
        d.Hash = "";
        d.Asked = true;
        Save(d);
    }

    // ---- system-wide idle time (keyboard / mouse) ----
    [StructLayout(LayoutKind.Sequential)]
    private struct LASTINPUTINFO
    {
        public uint cbSize;
        public uint dwTime;
    }

    [DllImport("user32.dll")]
    private static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);

    public static TimeSpan IdleTime()
    {
        var info = new LASTINPUTINFO { cbSize = (uint)Marshal.SizeOf<LASTINPUTINFO>() };
        if (!GetLastInputInfo(ref info)) return TimeSpan.Zero;
        var ms = unchecked((uint)Environment.TickCount - info.dwTime);
        return TimeSpan.FromMilliseconds(ms);
    }
}
