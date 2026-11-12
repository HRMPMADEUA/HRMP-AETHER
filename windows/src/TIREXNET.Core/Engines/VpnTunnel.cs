using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;

namespace TIREXNET.Core.Engines;

/// <summary>
/// Full-device tunnel for Xray: tun2socks creates a Wintun adapter and forwards its traffic to Xray's local SOCKS5 port.
/// Routes: server addresses and user bypass networks keep using the physical gateway; everything else goes through the adapter
/// via two /1 routes (so the original default route is never modified). Every change is journaled for crash recovery.
/// </summary>
internal sealed class VpnTunnel
{
    public const string AdapterName = "TIREXNET";
    private const string TunIp = "10.77.0.2";
    private const string TunGateway = "10.77.0.1";
    private const string TunMask = "255.255.255.0";

    private sealed record Physical(int IfIndex, string Gateway, string Name);

    private ManagedProcess? _tun;
    private readonly List<RouteEntry> _routes = new();
    private List<(uint Net, int Prefix)> _bypassCidrs = new();
    private List<IPAddress> _serverIps = new();
    private Physical? _physical;
    private int _tunIfIndex;
    private bool _stopping;
    private System.Threading.Timer? _debounce;

    public event Action<string>? Failed;

    /// <summary>
    /// tun2socks accepts: debug | info | warning | error | silent  ("warn" is rejected: "not a valid logrus Level").
    /// </summary>
    internal const string Tun2SocksLogLevel = "warning";

    /// <summary>
    /// tun2socks v2.5.x device driver. The accepted drivers are "tun" (on Windows this is implemented with Wintun) and "fd";
    /// "wintun://" is rejected with "unsupported driver: wintun".
    /// </summary>
    internal const string Tun2SocksDriver = "tun";

    internal static string[] BuildTun2SocksArgs(int socksPort, int mtu) => new[]
    {
        "-device", $"{Tun2SocksDriver}://{AdapterName}",
        "-proxy", $"socks5://127.0.0.1:{socksPort}",
        "-loglevel", Tun2SocksLogLevel,
        "-mtu", mtu.ToString(System.Globalization.CultureInfo.InvariantCulture),
    };

    [DllImport("iphlpapi.dll")]
    private static extern int GetBestInterface(uint destAddr, out uint bestIfIndex);

    public async Task StartAsync(List<IPAddress> serverIps, AppSettings settings, CancellationToken ct)
    {
        _stopping = false;
        _serverIps = serverIps;
        _bypassCidrs = ConfigValidator.SplitList(settings.BypassNetworks)
            .Select(b => NetUtil.TryParseCidrV4(b, out var n, out var p) ? (n, p) : (0u, -1))
            .Where(x => x.Item2 >= 0).ToList();

        _physical = FindPhysical(excludeName: AdapterName, preferIf: 0)
            ?? throw new EngineException("No active network connection with a gateway was found. Connect to the internet first.");
        Log.Info($"Physical network: {_physical.Name} via {_physical.Gateway}.");

        var journal = new RunStateData { TunActive = true };
        RunState.Save(journal);

        try
        {
            var mtu = settings.Mtu > 0 ? settings.Mtu : 1500;
            _tun = ManagedProcess.Start("tun2socks", AppPaths.Tun2SocksExe, BuildTun2SocksArgs(settings.SocksPort, mtu), AppPaths.Tun2SocksDir);
            _tun.OutputLine += l => Log.Info("[tun2socks] " + l);
            _tun.UnexpectedExit += code => RaiseFailed($"The tunnel process stopped unexpectedly (exit code {code}). {_tun?.LastLines()}");

            var nic = await WaitForAdapterAsync(TimeSpan.FromSeconds(12), ct).ConfigureAwait(false)
                ?? throw new EngineException("The Wintun network adapter did not appear. Make sure no other VPN is blocking adapter creation.");
            _tunIfIndex = nic.GetIPProperties().GetIPv4Properties().Index;

            await RunChecked("netsh.exe", new[] { "interface", "ipv4", "set", "address", $"name={AdapterName}", "source=static", $"address={TunIp}", $"mask={TunMask}" }, "set the adapter address", retries: 6, ct).ConfigureAwait(false);
            await RunChecked("netsh.exe", new[] { "interface", "ipv4", "set", "interface", $"interface={AdapterName}", "metric=3" }, "set the adapter metric", retries: 1, ct, optional: true).ConfigureAwait(false);
            await RunChecked("netsh.exe", new[] { "interface", "ipv4", "set", "subinterface", $"interface={AdapterName}", $"mtu={mtu}", "store=active" }, "set the adapter MTU", retries: 1, ct, optional: true).ConfigureAwait(false);

            var dns = ConfigValidator.SplitList(settings.Dns).Where(d => NetUtil.TryParseIp(d, out var ip) && ip.AddressFamily == AddressFamily.InterNetwork).ToList();
            if (dns.Count == 0) dns = new List<string> { "1.1.1.1", "1.0.0.1" };
            await RunChecked("netsh.exe", new[] { "interface", "ipv4", "set", "dnsservers", $"name={AdapterName}", "static", dns[0], "primary", "validate=no" }, "set DNS", retries: 2, ct).ConfigureAwait(false);
            for (var i = 1; i < dns.Count; i++)
                await RunChecked("netsh.exe", new[] { "interface", "ipv4", "add", "dnsservers", $"name={AdapterName}", dns[i], $"index={i + 1}", "validate=no" }, "add DNS", retries: 1, ct, optional: true).ConfigureAwait(false);

            // Bypass routes first, so the proxy connection to the server never loops into the tunnel.
            await ApplyBypassRoutesAsync().ConfigureAwait(false);
            await AddRouteAsync("0.0.0.0", "128.0.0.0", TunGateway, _tunIfIndex, 3).ConfigureAwait(false);
            await AddRouteAsync("128.0.0.0", "128.0.0.0", TunGateway, _tunIfIndex, 3).ConfigureAwait(false);

            NetworkChange.NetworkAddressChanged += OnNetworkChanged;
            Log.Info("VPN tunnel is up. All IPv4 traffic is routed through TIREXNET.");
        }
        catch
        {
            await StopAsync().ConfigureAwait(false);
            throw;
        }
    }

    private async Task<NetworkInterface?> WaitForAdapterAsync(TimeSpan timeout, CancellationToken ct)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            ct.ThrowIfCancellationRequested();
            if (_tun is { IsRunning: false }) throw new EngineException("The tunnel process exited during startup. " + _tun.LastLines());
            var nic = NetworkInterface.GetAllNetworkInterfaces().FirstOrDefault(n => n.Name == AdapterName);
            if (nic != null)
            {
                try
                {
                    _ = nic.GetIPProperties().GetIPv4Properties().Index;
                    return nic;
                }
                catch (NetworkInformationException)
                {
                    // IPv4 properties not ready yet
                }
            }
            await Task.Delay(300, ct).ConfigureAwait(false);
        }
        return null;
    }

    private static async Task RunChecked(string exe, string[] args, string what, int retries, CancellationToken ct, bool optional = false)
    {
        (int ExitCode, string Output) r = (0, "");
        for (var i = 0; i < Math.Max(1, retries); i++)
        {
            ct.ThrowIfCancellationRequested();
            r = await Cmd.RunAsync(Cmd.SystemExe(exe), args).ConfigureAwait(false);
            if (r.ExitCode == 0) return;
            await Task.Delay(500, ct).ConfigureAwait(false);
        }
        if (optional) { Log.Warn($"Could not {what}: {r.Output}"); return; }
        throw new EngineException($"Could not {what}: {r.Output}");
    }

    private async Task ApplyBypassRoutesAsync()
    {
        if (_physical == null) return;
        foreach (var ip in _serverIps)
            await AddRouteAsync(ip.ToString(), "255.255.255.255", _physical.Gateway, _physical.IfIndex, 2).ConfigureAwait(false);
        foreach (var (net, prefix) in _bypassCidrs)
            await AddRouteAsync(NetUtil.ToDotted(net), NetUtil.ToDotted(NetUtil.PrefixToMask(prefix)), _physical.Gateway, _physical.IfIndex, 2).ConfigureAwait(false);
    }

    private async Task AddRouteAsync(string dest, string mask, string gateway, int ifIndex, int metric)
    {
        var entry = new RouteEntry { Dest = dest, Mask = mask, Gateway = gateway, IfIndex = ifIndex };
        // Journal first: if we crash between adding and saving, recovery still removes it.
        _routes.Add(entry);
        RunState.Save(new RunStateData { Routes = _routes.ToList(), TunActive = true });
        var r = await Cmd.RunAsync(Cmd.SystemExe("route.exe"), new[] { "add", dest, "mask", mask, gateway, "metric", metric.ToString(), "if", ifIndex.ToString() }).ConfigureAwait(false);
        if (r.ExitCode != 0)
        {
            _routes.Remove(entry);
            RunState.Save(new RunStateData { Routes = _routes.ToList(), TunActive = true });
            throw new EngineException($"Could not add route {dest}/{mask}: {r.Output}");
        }
    }

    private async Task RemoveRoutesAsync(Func<RouteEntry, bool> predicate)
    {
        foreach (var r in _routes.Where(predicate).Reverse().ToList())
        {
            await Cmd.RunAsync(Cmd.SystemExe("route.exe"), new[] { "delete", r.Dest, "mask", r.Mask, r.Gateway }).ConfigureAwait(false);
            _routes.Remove(r);
        }
        RunState.Save(new RunStateData { Routes = _routes.ToList(), TunActive = _tun != null });
    }

    // --------------------------------------------------------- network monitoring

    private void OnNetworkChanged(object? sender, EventArgs e)
    {
        if (_stopping) return;
        _debounce?.Dispose();
        _debounce = new System.Threading.Timer(async _ => await HandleNetworkChangeAsync().ConfigureAwait(false), null, 2000, Timeout.Infinite);
    }

    private async Task HandleNetworkChangeAsync()
    {
        if (_stopping || _physical == null) return;
        try
        {
            var now = FindPhysical(AdapterName, _physical.IfIndex);
            if (now == null)
            {
                Log.Warn("Network connection lost. Waiting for it to return…");
                return;
            }
            if (now.IfIndex == _physical.IfIndex && now.Gateway == _physical.Gateway) return;
            Log.Info($"Network changed to {now.Name} via {now.Gateway}. Updating bypass routes.");
            await RemoveRoutesAsync(r => r.Gateway == _physical.Gateway && r.IfIndex == _physical.IfIndex).ConfigureAwait(false);
            _physical = now;
            await ApplyBypassRoutesAsync().ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is EngineException or InvalidOperationException)
        {
            Log.Error("Could not adapt to the network change: " + ex.Message);
            RaiseFailed("The network changed and the tunnel routes could not be updated. " + ex.Message);
        }
    }

    private static Physical? FindPhysical(string excludeName, int preferIf)
    {
        var nics = NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up
                && n.NetworkInterfaceType is not (NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
                && n.Name != excludeName && n.Name != WireGuardEngine.TunnelName && n.Name != AmneziaWgEngine.TunnelName)
            .ToList();

        Physical? Make(NetworkInterface n)
        {
            try
            {
                var gw = n.GetIPProperties().GatewayAddresses
                    .FirstOrDefault(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any));
                if (gw == null) return null;
                return new Physical(n.GetIPProperties().GetIPv4Properties().Index, gw.Address.ToString(), n.Name);
            }
            catch (NetworkInformationException)
            {
                return null;
            }
        }

        if (preferIf != 0)
        {
            foreach (var n in nics)
            {
                var m = Make(n);
                if (m != null && m.IfIndex == preferIf) return m;
            }
        }
        else
        {
            var probe = BitConverter.ToUInt32(IPAddress.Parse("8.8.8.8").GetAddressBytes(), 0);
            if (GetBestInterface(probe, out var best) == 0)
            {
                foreach (var n in nics)
                {
                    var m = Make(n);
                    if (m != null && (uint)m.IfIndex == best) return m;
                }
            }
        }
        return nics.Select(Make).FirstOrDefault(m => m != null);
    }

    private void RaiseFailed(string message)
    {
        if (_stopping) return;
        Failed?.Invoke(message);
    }

    public async Task StopAsync()
    {
        _stopping = true;
        NetworkChange.NetworkAddressChanged -= OnNetworkChanged;
        _debounce?.Dispose();
        _debounce = null;
        try
        {
            await RemoveRoutesAsync(_ => true).ConfigureAwait(false);
        }
        finally
        {
            if (_tun != null)
            {
                await _tun.StopAsync().ConfigureAwait(false);
                _tun.Dispose();
                _tun = null;
                // Wintun removes the adapter when the process ends; give it a moment.
                for (var i = 0; i < 10 && NetworkInterface.GetAllNetworkInterfaces().Any(n => n.Name == AdapterName); i++)
                    await Task.Delay(300).ConfigureAwait(false);
            }
            RunState.Clear();
        }
    }
}
