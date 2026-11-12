using System.Net.NetworkInformation;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;

namespace TIREXNET.Core.Engines;

/// <summary>
/// Genuine AmneziaWG (WireGuard with junk packets, padding, custom headers and signature chains) through the official
/// amneziawg-windows-client tunnel service: amneziawg.exe /installtunnelservice creates the service "AmneziaWGTunnel$name"
/// and a Wintun adapter. Lifecycle mirrors <see cref="WireGuardEngine"/>.
/// </summary>
public sealed class AmneziaWgEngine : IEngine
{
    public const string TunnelName = "tirexnet-awg";
    public const string ServiceName = "AmneziaWGTunnel$" + TunnelName;
    private System.Threading.Timer? _watch;
    private bool _stopping;
    private bool _installed;

    public string Name => "AmneziaWG";
    public event Action<string>? Failed;

    public static bool IsRuntimeAvailable => File.Exists(AppPaths.AmneziaWgExe) && File.Exists(Path.Combine(AppPaths.AmneziaWgDir, "wintun.dll"));

    private static string RunConfDir => Path.Combine(AppPaths.MachineDir, "run");
    private static string RunConfPath => Path.Combine(RunConfDir, TunnelName + ".conf");

    public async Task StartAsync(Profile profile, AppSettings settings, CancellationToken ct)
    {
        _stopping = false;
        var sanitized = AmneziaConfig.Sanitize(profile.Raw, out var notes);
        foreach (var n in notes) Log.Warn("AmneziaWG: " + n);
        var checkedProfile = new Profile { Name = profile.Name, Engine = EngineKind.AmneziaWG, Raw = sanitized };
        var problems = ConfigValidator.Validate(checkedProfile);
        if (problems.Count > 0) throw new EngineException("The AmneziaWG profile is not valid: " + problems[0]);
        if (!IsRuntimeAvailable) throw new EngineException("The AmneziaWG runtime is missing from this installation. Reinstall TIREXNET.");
        if (!Elevation.IsAdministrator())
            throw new ElevationRequiredException("AmneziaWG needs administrator rights to install its tunnel service and network adapter.");
        foreach (var s in ProfileParser.CollectSecrets(checkedProfile)) Redactor.AddSecret(s);

        WgConfig.TryParse(sanitized, out var cfg, out _);
        foreach (var k in new[] { "FwMark", "Table", "SaveConfig" }) WgConfig.Remove(cfg.Interface, k);
        var dns = ConfigValidator.SplitList(settings.Dns);
        if (dns.Count > 0) WgConfig.Set(cfg.Interface, "DNS", string.Join(", ", dns));
        if (settings.Mtu > 0) WgConfig.Set(cfg.Interface, "MTU", settings.Mtu.ToString(System.Globalization.CultureInfo.InvariantCulture));

        await RemoveTunnelServiceAsync().ConfigureAwait(false);
        try
        {
            AdminDir.Prepare(RunConfDir);
            File.WriteAllText(RunConfPath, cfg.Serialize());
            var journal = RunState.Load();
            journal.AmneziaTunnel = TunnelName;
            RunState.Save(journal);
            Log.Info("Installing the AmneziaWG tunnel service…");
            var (code, output) = await Cmd.RunAsync(AppPaths.AmneziaWgExe, new[] { "/installtunnelservice", RunConfPath }, 30000).ConfigureAwait(false);
            if (code != 0) throw new EngineException($"AmneziaWG could not start the tunnel: {Trim(output)}");
            _installed = true;
            var nic = await WaitForAdapterAsync(TimeSpan.FromSeconds(20), ct).ConfigureAwait(false);
            if (nic == null) throw new EngineException("The AmneziaWG adapter did not come up. Check the endpoint address, the obfuscation parameters and your internet connection.");
            Log.Info($"AmneziaWG tunnel \"{profile.Name}\" is up.");
        }
        catch
        {
            await StopAsync().ConfigureAwait(false);
            throw;
        }
        finally
        {
            DeleteRuntimeConfig();
        }
        _watch = new System.Threading.Timer(_ => CheckAlive(), null, 2000, 2000);
    }

    private static string Trim(string s) => s.Length > 300 ? s[..300] : s;

    private static NetworkInterface? FindAdapter() =>
        NetworkInterface.GetAllNetworkInterfaces().FirstOrDefault(n => n.Name.Equals(TunnelName, StringComparison.OrdinalIgnoreCase));

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

    private void CheckAlive()
    {
        if (_stopping || !_installed) return;
        var nic = FindAdapter();
        if (nic == null || nic.OperationalStatus == OperationalStatus.Down)
        {
            _watch?.Dispose();
            _watch = null;
            if (!_stopping) Failed?.Invoke("The AmneziaWG tunnel was closed unexpectedly.");
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
        var journal = RunState.Load();
        journal.AmneziaTunnel = "";
        if (journal.Routes.Count == 0 && !journal.TunActive && string.IsNullOrEmpty(journal.WireGuardTunnel) && journal.OpenVpnPid == 0) RunState.Clear();
        else RunState.Save(journal);
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

    public static async Task RemoveTunnelServiceAsync()
    {
        if (!File.Exists(AppPaths.AmneziaWgExe)) return;
        var q = await Cmd.RunAsync(Cmd.SystemExe("sc.exe"), new[] { "query", ServiceName }, 8000).ConfigureAwait(false);
        if (q.ExitCode != 0) return;
        Log.Info("Removing the AmneziaWG tunnel service…");
        await Cmd.RunAsync(AppPaths.AmneziaWgExe, new[] { "/uninstalltunnelservice", TunnelName }, 30000).ConfigureAwait(false);
        for (var i = 0; i < 25; i++)
        {
            var again = await Cmd.RunAsync(Cmd.SystemExe("sc.exe"), new[] { "query", ServiceName }, 8000).ConfigureAwait(false);
            if (again.ExitCode != 0) break;
            await Task.Delay(400).ConfigureAwait(false);
        }
    }

    public static void DeleteRuntimeConfig() => AdminDir.TryDelete(RunConfPath);
}
