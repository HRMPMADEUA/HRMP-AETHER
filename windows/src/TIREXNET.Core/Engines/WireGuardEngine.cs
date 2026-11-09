using System.Net.NetworkInformation;
using System.Security.AccessControl;
using System.Security.Principal;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;

namespace TIREXNET.Core.Engines;

/// <summary>
/// Genuine WireGuard through the official wireguard-windows tunnel service (wireguard.exe /installtunnelservice).
/// The configuration is written to an administrator-only folder, handed to the service, and deleted once the tunnel is up.
/// </summary>
public sealed class WireGuardEngine : IEngine
{
    public const string TunnelName = "tirexnet-wg";
    private System.Threading.Timer? _watch;
    private bool _stopping;
    private bool _installed;

    public string Name => "WireGuard";
    public event Action<string>? Failed;

    public static bool IsRuntimeAvailable => File.Exists(AppPaths.WireGuardExe);

    private static string RunConfDir => Path.Combine(AppPaths.MachineDir, "run");
    private static string RunConfPath => Path.Combine(RunConfDir, TunnelName + ".conf");

    public async Task StartAsync(Profile profile, AppSettings settings, CancellationToken ct)
    {
        _stopping = false;
        var problems = ConfigValidator.Validate(profile);
        if (problems.Count > 0) throw new EngineException("The WireGuard profile is not valid: " + problems[0]);
        if (!IsRuntimeAvailable) throw new EngineException("The WireGuard runtime is missing from this installation. Reinstall TIREXNET.");
        if (!Elevation.IsAdministrator())
            throw new ElevationRequiredException("WireGuard needs administrator rights to install its tunnel service and network adapter.");
        foreach (var s in ProfileParser.CollectSecrets(profile)) Redactor.AddSecret(s);

        WgConfig.TryParse(profile.Raw, out var cfg, out _);
        foreach (var k in new[] { "FwMark", "Table", "SaveConfig" }) WgConfig.Remove(cfg.Interface, k); // not meaningful on Windows
        var dns = ConfigValidator.SplitList(settings.Dns);
        if (dns.Count > 0) WgConfig.Set(cfg.Interface, "DNS", string.Join(", ", dns));
        if (settings.Mtu > 0) WgConfig.Set(cfg.Interface, "MTU", settings.Mtu.ToString());

        // A leftover tunnel from a previous crash would clash with ours.
        await RemoveTunnelServiceAsync().ConfigureAwait(false);

        try
        {
            WriteRuntimeConfig(cfg.Serialize());
            // Journal first so a crash right after installing still gets cleaned up.
            RunState.Save(new RunStateData { WireGuardTunnel = TunnelName });
            Log.Info("Installing the WireGuard tunnel service…");
            var (code, output) = await Cmd.RunAsync(AppPaths.WireGuardExe, new[] { "/installtunnelservice", RunConfPath }, 30000).ConfigureAwait(false);
            if (code != 0) throw new EngineException($"WireGuard could not start the tunnel: {Trim(output)}");
            _installed = true;

            var nic = await WaitForAdapterAsync(TimeSpan.FromSeconds(20), ct).ConfigureAwait(false);
            if (nic == null) throw new EngineException("The WireGuard adapter did not come up. Check the endpoint address and your internet connection.");
            Log.Info($"WireGuard tunnel \"{profile.Name}\" is up.");
        }
        catch
        {
            await StopAsync().ConfigureAwait(false);
            throw;
        }
        finally
        {
            DeleteRuntimeConfig(); // the service has already read it; do not leave keys on disk
        }
        _watch = new System.Threading.Timer(_ => CheckAlive(), null, 2000, 2000);
    }

    private static string Trim(string s) => s.Length > 300 ? s[..300] : s;

    private static async Task<NetworkInterface?> WaitForAdapterAsync(TimeSpan timeout, CancellationToken ct)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            ct.ThrowIfCancellationRequested();
            var nic = FindAdapter();
            if (nic != null && nic.OperationalStatus == OperationalStatus.Up) return nic;
            await Task.Delay(400, ct).ConfigureAwait(false);
        }
        return null;
    }

    private static NetworkInterface? FindAdapter() =>
        NetworkInterface.GetAllNetworkInterfaces().FirstOrDefault(n => n.Name.Equals(TunnelName, StringComparison.OrdinalIgnoreCase));

    private void CheckAlive()
    {
        if (_stopping || !_installed) return;
        var nic = FindAdapter();
        if (nic == null || nic.OperationalStatus == OperationalStatus.Down)
        {
            _watch?.Dispose();
            _watch = null;
            if (!_stopping) Failed?.Invoke("The WireGuard tunnel was closed unexpectedly.");
        }
    }

    public async Task StopAsync()
    {
        _stopping = true;
        _watch?.Dispose();
        _watch = null;
        await RemoveTunnelServiceAsync().ConfigureAwait(false);
        DeleteRuntimeConfig();
        _installed = false;
        RunState.Clear();
    }

    public Task<(long Upload, long Download)> QueryTotalsAsync()
    {
        var nic = FindAdapter();
        if (nic == null) return Task.FromResult((0L, 0L));
        try
        {
            var s = nic.GetIPv4Statistics();
            return Task.FromResult((s.BytesSent, s.BytesReceived));
        }
        catch (NetworkInformationException)
        {
            return Task.FromResult((0L, 0L));
        }
    }

    // ------------------------------------------------------------ service helpers (also used by recovery / uninstall)

    public static async Task RemoveTunnelServiceAsync()
    {
        if (!File.Exists(AppPaths.WireGuardExe)) return;
        var q = await Cmd.RunAsync(Cmd.SystemExe("sc.exe"), new[] { "query", "WireGuardTunnel$" + TunnelName }, 8000).ConfigureAwait(false);
        if (q.ExitCode != 0) return; // service does not exist
        Log.Info("Removing the WireGuard tunnel service…");
        await Cmd.RunAsync(AppPaths.WireGuardExe, new[] { "/uninstalltunnelservice", TunnelName }, 30000).ConfigureAwait(false);
        for (var i = 0; i < 25; i++)
        {
            var again = await Cmd.RunAsync(Cmd.SystemExe("sc.exe"), new[] { "query", "WireGuardTunnel$" + TunnelName }, 8000).ConfigureAwait(false);
            if (again.ExitCode != 0) break;
            await Task.Delay(400).ConfigureAwait(false);
        }
    }

    private static void WriteRuntimeConfig(string text)
    {
        Directory.CreateDirectory(RunConfDir);
        var security = new DirectorySecurity();
        security.SetAccessRuleProtection(true, false); // drop inherited permissions
        var inherit = InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit;
        foreach (var sid in new[] { WellKnownSidType.LocalSystemSid, WellKnownSidType.BuiltinAdministratorsSid })
        {
            security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(sid, null), FileSystemRights.FullControl, inherit, PropagationFlags.None, AccessControlType.Allow));
        }
        new DirectoryInfo(RunConfDir).SetAccessControl(security);
        File.WriteAllText(RunConfPath, text);
    }

    public static void DeleteRuntimeConfig()
    {
        try { if (File.Exists(RunConfPath)) File.Delete(RunConfPath); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { Log.Warn("Could not delete the temporary WireGuard config: " + ex.Message); }
    }
}
