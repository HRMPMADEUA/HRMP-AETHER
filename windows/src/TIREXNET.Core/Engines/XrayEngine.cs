using System.Net;
using System.Text.Json;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;

namespace TIREXNET.Core.Engines;

/// <summary>Runs the official Xray-core binary and (in VPN mode) tun2socks + Wintun on top of it.</summary>
public sealed class XrayEngine : IEngine
{
    private ManagedProcess? _xray;
    private VpnTunnel? _tunnel;
    private bool _systemProxySet;
    private bool _stopping;
    private int _apiPort;
    private (long Up, long Down) _last;
    private DateTime _lastQuery = DateTime.MinValue;

    public string Name => "Xray";
    public event Action<string>? Failed;

    public static bool IsRuntimeAvailable => File.Exists(AppPaths.XrayExe);
    public static bool IsVpnRuntimeAvailable => IsRuntimeAvailable && File.Exists(AppPaths.Tun2SocksExe) && File.Exists(AppPaths.WintunDll);

    public async Task StartAsync(Profile profile, AppSettings settings, CancellationToken ct)
    {
        _stopping = false;
        var problems = ConfigValidator.Validate(profile);
        if (problems.Count > 0) throw new EngineException("The profile is not valid: " + problems[0]);
        var plaintext = ConfigValidator.PlaintextRejectedByXray(profile);
        if (plaintext != null) throw new EngineException(plaintext);
        var settingProblems = ConfigValidator.ValidateSettings(settings);
        if (settingProblems.Count > 0) throw new EngineException("Settings are not valid: " + settingProblems[0]);

        if (!IsRuntimeAvailable) throw new EngineException("The Xray runtime is missing from this installation. Reinstall TIREXNET.");
        var vpn = settings.Mode == ConnectionMode.Vpn;
        if (vpn)
        {
            if (!IsVpnRuntimeAvailable) throw new EngineException("VPN mode needs tun2socks and Wintun, which are missing from this installation.");
            if (!Elevation.IsAdministrator()) throw new ElevationRequiredException("VPN mode needs administrator rights to create the network adapter and routes.");
        }
        if (!NetUtil.IsPortFree(settings.SocksPort)) throw new EngineException($"Port {settings.SocksPort} is already in use. Pick another SOCKS5 port in Settings.");
        if (settings.EnableHttpProxy && !NetUtil.IsPortFree(settings.HttpPort)) throw new EngineException($"Port {settings.HttpPort} is already in use. Pick another HTTP port in Settings.");

        foreach (var s in ProfileParser.CollectSecrets(profile)) Redactor.AddSecret(s);
        _apiPort = NetUtil.FindFreePort();

        // Resolve server addresses BEFORE any route changes (needed for the VPN bypass routes).
        List<IPAddress> serverIps = new();
        Dictionary<string, string>? hostMap = null;
        if (vpn)
        {
            serverIps = await ResolveServersAsync(profile, ct).ConfigureAwait(false);
            hostMap = new Dictionary<string, string>();
            foreach (var host in XrayConfigBuilder.CollectServerHosts(profile))
            {
                if (NetUtil.TryParseIp(host, out _)) continue;
                var ip = (await Dns.GetHostAddressesAsync(host, ct).ConfigureAwait(false))
                    .FirstOrDefault(a => a.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork);
                if (ip != null) hostMap[host] = ip.ToString();
            }
        }
        var json = XrayConfigBuilder.Build(profile, settings, _apiPort, hostMap);

        Log.Info($"Starting Xray ({(vpn ? "VPN" : "proxy")} mode).");
        await StartXrayAsync(json, settings, ct).ConfigureAwait(false);

        if (vpn)
        {
            _tunnel = new VpnTunnel();
            _tunnel.Failed += msg => RaiseFailed(msg);
            await _tunnel.StartAsync(serverIps, settings, ct).ConfigureAwait(false);
        }
        else if (settings.SetSystemProxy)
        {
            SystemProxy.Enable(settings.HttpPort);
            _systemProxySet = true;
        }
    }

    private async Task StartXrayAsync(string json, AppSettings settings, CancellationToken ct)
    {
        var env = new Dictionary<string, string> { ["XRAY_LOCATION_ASSET"] = AppPaths.XrayDir };
        // The configuration is passed on stdin so it never exists as a file on disk.
        var proc = ManagedProcess.Start("Xray", AppPaths.XrayExe, new[] { "run", "-config", "stdin:" }, AppPaths.XrayDir, json, env);
        proc.OutputLine += l => Log.Info("[xray] " + l);
        proc.UnexpectedExit += code => RaiseFailed($"Xray stopped unexpectedly (exit code {code}). {proc.LastLines()}");
        _xray = proc;

        var ready = await NetUtil.WaitForPortAsync(settings.SocksPort, TimeSpan.FromSeconds(10), () => proc.IsRunning, ct).ConfigureAwait(false);
        if (!ready)
        {
            var detail = proc.LastLines();
            await StopAsync().ConfigureAwait(false);
            throw new EngineException("Xray did not start" + (string.IsNullOrEmpty(detail) ? "." : ": " + detail));
        }
        Log.Info($"Xray is listening on 127.0.0.1:{settings.SocksPort}" + (settings.EnableHttpProxy ? $" (SOCKS5) and 127.0.0.1:{settings.HttpPort} (HTTP)." : " (SOCKS5)."));
    }

    private static async Task<List<IPAddress>> ResolveServersAsync(Profile profile, CancellationToken ct)
    {
        var ips = new List<IPAddress>();
        foreach (var host in XrayConfigBuilder.CollectServerHosts(profile))
        {
            if (NetUtil.TryParseIp(host, out var ip))
            {
                if (ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork) ips.Add(ip);
                continue;
            }
            try
            {
                var addrs = await Dns.GetHostAddressesAsync(host, ct).ConfigureAwait(false);
                ips.AddRange(addrs.Where(a => a.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork));
            }
            catch (System.Net.Sockets.SocketException ex)
            {
                throw new EngineException($"Could not resolve an IPv4 address for \"{host}\" ({ex.Message.TrimEnd('.')}). The name may be IPv6-only or your DNS is not answering. VPN mode supports IPv4 only: use Proxy mode, another server, or check your connection.");
            }
        }
        if (ips.Count == 0) throw new EngineException("VPN mode needs an IPv4 server address; this server has none (it may be IPv6-only). Use Proxy mode or another server.");
        return ips.Distinct().ToList();
    }

    private void RaiseFailed(string message)
    {
        if (_stopping) return;
        Failed?.Invoke(message);
    }

    public async Task StopAsync()
    {
        _stopping = true;
        try
        {
            if (_tunnel != null)
            {
                await _tunnel.StopAsync().ConfigureAwait(false);
                _tunnel = null;
            }
            if (_systemProxySet)
            {
                SystemProxy.RestoreIfNeeded();
                _systemProxySet = false;
            }
        }
        finally
        {
            if (_xray != null)
            {
                await _xray.StopAsync().ConfigureAwait(false);
                _xray.Dispose();
                _xray = null;
            }
        }
    }

    public async Task<(long Upload, long Download)> QueryTotalsAsync()
    {
        if (_xray == null || !_xray.IsRunning || _apiPort == 0) return _last;
        if ((DateTime.UtcNow - _lastQuery).TotalMilliseconds < 1800) return _last;
        _lastQuery = DateTime.UtcNow;
        var (code, output) = await Cmd.RunAsync(AppPaths.XrayExe, new[] { "api", "statsquery", $"--server=127.0.0.1:{_apiPort}", "-pattern", "outbound>>>" }, 4000).ConfigureAwait(false);
        if (code != 0) return _last;
        _last = ParseStats(output);
        return _last;
    }

    /// <summary>Sums uplink/downlink of every outbound except the internal ones. Accepts string or numeric values.</summary>
    public static (long Up, long Down) ParseStats(string json)
    {
        long up = 0, down = 0;
        try
        {
            using var doc = JsonDocument.Parse(json);
            if (!doc.RootElement.TryGetProperty("stat", out var stats) || stats.ValueKind != JsonValueKind.Array) return (0, 0);
            foreach (var s in stats.EnumerateArray())
            {
                var name = s.TryGetProperty("name", out var n) ? n.GetString() ?? "" : "";
                if (!name.StartsWith("outbound>>>", StringComparison.Ordinal)) continue;
                var parts = name.Split(">>>");
                if (parts.Length < 4) continue;
                if (parts[1] is "api" or "block" or "blackhole") continue;
                long value = 0;
                if (s.TryGetProperty("value", out var v))
                {
                    if (v.ValueKind == JsonValueKind.Number) value = v.GetInt64();
                    else if (v.ValueKind == JsonValueKind.String) long.TryParse(v.GetString(), out value);
                }
                if (parts[3] == "uplink") up += value;
                else if (parts[3] == "downlink") down += value;
            }
        }
        catch (JsonException)
        {
            return (0, 0);
        }
        return (up, down);
    }
}
