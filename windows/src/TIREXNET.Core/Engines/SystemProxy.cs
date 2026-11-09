using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Win32;
using TIREXNET.Core.Models;

namespace TIREXNET.Core.Engines;

/// <summary>Optionally points the Windows system proxy at the local HTTP proxy and always restores the previous values.</summary>
public static class SystemProxy
{
    private const string KeyPath = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";

    private sealed class Backup
    {
        public int? Enable { get; set; }
        public string? Server { get; set; }
        public string? Override { get; set; }
    }

    [DllImport("wininet.dll", SetLastError = true)]
    private static extern bool InternetSetOption(IntPtr hInternet, int option, IntPtr buffer, int length);

    private static string BackupPath => Path.Combine(AppPaths.DataDir, "proxy-backup.json");

    public static void Enable(int httpPort)
    {
        using var key = Registry.CurrentUser.OpenSubKey(KeyPath, true) ?? throw new EngineException("The Windows proxy settings are not accessible.");
        if (!File.Exists(BackupPath))
        {
            var b = new Backup
            {
                Enable = key.GetValue("ProxyEnable") as int?,
                Server = key.GetValue("ProxyServer") as string,
                Override = key.GetValue("ProxyOverride") as string,
            };
            File.WriteAllText(BackupPath, JsonSerializer.Serialize(b));
        }
        key.SetValue("ProxyServer", $"127.0.0.1:{httpPort}", RegistryValueKind.String);
        key.SetValue("ProxyOverride", "localhost;127.*;10.*;172.16.*;192.168.*;<local>", RegistryValueKind.String);
        key.SetValue("ProxyEnable", 1, RegistryValueKind.DWord);
        Notify();
        Log.Info("Windows system proxy set to the local HTTP proxy.");
    }

    public static void RestoreIfNeeded()
    {
        try
        {
            if (!File.Exists(BackupPath)) return;
            var b = JsonSerializer.Deserialize<Backup>(File.ReadAllText(BackupPath)) ?? new Backup();
            using var key = Registry.CurrentUser.OpenSubKey(KeyPath, true);
            if (key != null)
            {
                key.SetValue("ProxyEnable", b.Enable ?? 0, RegistryValueKind.DWord);
                if (b.Server != null) key.SetValue("ProxyServer", b.Server, RegistryValueKind.String);
                else key.DeleteValue("ProxyServer", false);
                if (b.Override != null) key.SetValue("ProxyOverride", b.Override, RegistryValueKind.String);
                else key.DeleteValue("ProxyOverride", false);
                Notify();
            }
            File.Delete(BackupPath);
            Log.Info("Windows system proxy restored.");
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            Log.Warn("Could not restore the system proxy: " + ex.Message);
        }
    }

    private static void Notify()
    {
        InternetSetOption(IntPtr.Zero, 39, IntPtr.Zero, 0); // INTERNET_OPTION_SETTINGS_CHANGED
        InternetSetOption(IntPtr.Zero, 37, IntPtr.Zero, 0); // INTERNET_OPTION_REFRESH
    }
}
