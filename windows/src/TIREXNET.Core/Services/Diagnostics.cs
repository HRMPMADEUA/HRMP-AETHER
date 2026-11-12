using System.Runtime.InteropServices;
using System.Text;
using TIREXNET.Core.Engines;

namespace TIREXNET.Core.Services;

public static class Diagnostics
{
    /// <summary>
    /// Builds the diagnostics report. Never throws: every section is isolated, expected failures (missing or blocked
    /// executables, timeouts, access problems) become readable lines, and the result is passed through the redactor.
    /// </summary>
    public static async Task<string> RunAsync()
    {
        var sb = new StringBuilder();
        void Line(string s) => sb.AppendLine(s);
        async Task Section(string name, Func<Task> body)
        {
            try { await body().ConfigureAwait(false); }
            catch (Exception ex)
            {
                Log.Error($"Diagnostics section \"{name}\" failed: {ex}");
                Line($"{name}: could not be checked ({ex.GetType().Name}: {ex.Message})");
            }
        }

        Line("TIREXNET diagnostics");
        await Section("Application", () =>
        {
            Line($"Version: {typeof(Diagnostics).Assembly.GetName().Version}");
            Line($"Windows: {RuntimeInformation.OSDescription} ({RuntimeInformation.OSArchitecture})");
            Line($"Administrator: {(Elevation.IsAdministrator() ? "yes" : "no")}");
            return Task.CompletedTask;
        }).ConfigureAwait(false);

        await Section("Xray runtime", async () =>
        {
            Line($"Xray runtime: {Mark(XrayEngine.IsRuntimeAvailable)}");
            if (!XrayEngine.IsRuntimeAvailable) return;
            var (code, output) = await Cmd.RunAsync(AppPaths.XrayExe, new[] { "version" }, 5000).ConfigureAwait(false);
            var first = (output ?? "").Split('\n').FirstOrDefault()?.Trim() ?? "";
            Line("  " + (code == 0 ? first : "could not run (" + code + "): " + first));
        }).ConfigureAwait(false);

        await Section("VPN runtime", () =>
        {
            Line($"VPN mode runtime (tun2socks + Wintun): {Mark(XrayEngine.IsVpnRuntimeAvailable)}");
            foreach (var l in EngineCatalog.StatusLines()) Line(l.TrimStart('•', ' '));
            var ovpn = OpenVpnEngine.FindExe();
            Line("OpenVPN executable: " + (ovpn == null ? "not found" : Path.GetFileName(Path.GetDirectoryName(ovpn)) + "\\" + Path.GetFileName(ovpn)));
            return Task.CompletedTask;
        }).ConfigureAwait(false);

        await Section("Recovery state", () =>
        {
            Line($"Pending recovery state: {(RunState.HasPending() ? "YES (run as administrator to clean up)" : "none")}");
            return Task.CompletedTask;
        }).ConfigureAwait(false);

        await Section("Log file", () =>
        {
            Line($"Log file: {Log.LogFilePath}");
            return Task.CompletedTask;
        }).ConfigureAwait(false);

        return Redactor.Redact(sb.ToString());
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
