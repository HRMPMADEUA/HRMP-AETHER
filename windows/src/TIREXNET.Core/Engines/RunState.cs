using System.Diagnostics;
using System.Text.Json;

namespace TIREXNET.Core.Engines;

public sealed class RouteEntry
{
    public string Dest { get; set; } = "";
    public string Mask { get; set; } = "";
    public string Gateway { get; set; } = "";
    public int IfIndex { get; set; }
}

public sealed class RunStateData
{
    public List<RouteEntry> Routes { get; set; } = new();
    public string WireGuardTunnel { get; set; } = "";
    public string AmneziaTunnel { get; set; } = "";
    /// <summary>PID and start time (ticks) of the openvpn.exe TIREXNET launched, so recovery never kills somebody else's OpenVPN.</summary>
    public int OpenVpnPid { get; set; }
    public long OpenVpnStartTicks { get; set; }
    public bool TunActive { get; set; }
}

/// <summary>
/// Crash-recovery journal. Everything the app changes on the system (routes, tunnel services) is written here
/// before it is applied, so a later start (or the uninstaller) can undo it after a crash.
/// </summary>
public static class RunState
{
    private static readonly object Gate = new();
    private static string PathFile => Path.Combine(AppPaths.MachineDir, "runstate.json");

    public static RunStateData Load()
    {
        lock (Gate)
        {
            try
            {
                if (File.Exists(PathFile))
                    return JsonSerializer.Deserialize<RunStateData>(File.ReadAllText(PathFile)) ?? new RunStateData();
            }
            catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException) { }
            return new RunStateData();
        }
    }

    public static void Save(RunStateData data)
    {
        lock (Gate)
        {
            try
            {
                Directory.CreateDirectory(AppPaths.MachineDir);
                File.WriteAllText(PathFile, JsonSerializer.Serialize(data));
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                Log.Warn("Could not write the recovery journal: " + ex.Message);
            }
        }
    }

    public static void Clear()
    {
        lock (Gate)
        {
            try { if (File.Exists(PathFile)) File.Delete(PathFile); } catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { }
        }
    }

    public static bool HasPending()
    {
        var s = Load();
        return s.Routes.Count > 0 || s.TunActive || !string.IsNullOrEmpty(s.WireGuardTunnel) || !string.IsNullOrEmpty(s.AmneziaTunnel) || s.OpenVpnPid != 0;
    }
}

/// <summary>Undoes leftovers of a previous session (crash, kill, power loss). Needs administrator rights.</summary>
public static class Recovery
{
    public static async Task<string> CleanupAsync()
    {
        var notes = new List<string>();
        var state = RunState.Load();

        foreach (var r in Enumerable.Reverse(state.Routes))
        {
            await Cmd.RunAsync(Cmd.SystemExe("route.exe"), new[] { "delete", r.Dest, "mask", r.Mask, r.Gateway }).ConfigureAwait(false);
            notes.Add($"Removed route {r.Dest}/{r.Mask}");
        }

        await WireGuardEngine.RemoveTunnelServiceAsync().ConfigureAwait(false);
        WireGuardEngine.DeleteRuntimeConfig();
        await AmneziaWgEngine.RemoveTunnelServiceAsync().ConfigureAwait(false);
        AmneziaWgEngine.DeleteRuntimeConfig();
        if (OpenVpnEngine.KillRecorded(state)) notes.Add("Stopped a leftover OpenVPN process started by TIREXNET");
        OpenVpnEngine.DeleteRuntimeConfig();

        foreach (var name in new[] { "tun2socks", "xray", "slipnet" })
        {
            foreach (var p in Process.GetProcessesByName(name))
            {
                try
                {
                    var path = p.MainModule?.FileName ?? "";
                    if (path.StartsWith(AppPaths.EnginesDir, StringComparison.OrdinalIgnoreCase))
                    {
                        p.Kill(true);
                        notes.Add($"Stopped leftover {name}.exe (pid {p.Id})");
                    }
                }
                catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception) { }
                finally { p.Dispose(); }
            }
        }

        SystemProxy.RestoreIfNeeded();
        RunState.Clear();
        var text = notes.Count == 0 ? "Nothing to clean up." : string.Join("; ", notes);
        Log.Info("Recovery: " + text);
        return text;
    }
}
