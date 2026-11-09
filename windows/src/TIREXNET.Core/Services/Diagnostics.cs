using System.Runtime.InteropServices;
using System.Text;
using TIREXNET.Core.Engines;

namespace TIREXNET.Core.Services;

public static class Diagnostics
{
    public static async Task<string> RunAsync()
    {
        var sb = new StringBuilder();
        sb.AppendLine("TIREXNET diagnostics");
        sb.AppendLine($"Version: {typeof(Diagnostics).Assembly.GetName().Version}");
        sb.AppendLine($"Windows: {RuntimeInformation.OSDescription} ({RuntimeInformation.OSArchitecture})");
        sb.AppendLine($"Administrator: {(Elevation.IsAdministrator() ? "yes" : "no")}");
        sb.AppendLine($"Xray runtime: {Mark(XrayEngine.IsRuntimeAvailable)}");
        if (XrayEngine.IsRuntimeAvailable)
        {
            var (code, output) = await Cmd.RunAsync(AppPaths.XrayExe, new[] { "version" }, 5000).ConfigureAwait(false);
            sb.AppendLine("  " + (code == 0 ? output.Split('\n').FirstOrDefault()?.Trim() : "could not run: " + output));
        }
        sb.AppendLine($"VPN mode runtime (tun2socks + Wintun): {Mark(XrayEngine.IsVpnRuntimeAvailable)}");
        sb.AppendLine($"WireGuard runtime: {Mark(WireGuardEngine.IsRuntimeAvailable)}");
        sb.AppendLine($"Pending recovery state: {(RunState.HasPending() ? "YES (run as administrator to clean up)" : "none")}");
        sb.AppendLine($"Log file: {Log.LogFilePath}");
        return sb.ToString();
    }

    private static string Mark(bool ok) => ok ? "available" : "MISSING";

    public static string FormatBytes(double bytes)
    {
        string[] units = { "B", "KB", "MB", "GB", "TB" };
        var i = 0;
        while (bytes >= 1024 && i < units.Length - 1) { bytes /= 1024; i++; }
        return i == 0 ? $"{bytes:0} {units[i]}" : $"{bytes:0.##} {units[i]}";
    }
}
