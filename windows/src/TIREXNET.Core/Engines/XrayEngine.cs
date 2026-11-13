using System.Net;
using System.Text.Json;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;

namespace TIREXNET.Core.Engines;

/// <summary>Runs the official Xray-core binary and (in VPN mode) tun2socks + Wintun on top of it.</summary>
public sealed class XrayEngine : IEngine
{
    private ManagedProcess? _xray;
    private ManagedProcess? _snispf;
    private string? _snispfConfigPath;
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
        SniSpoofData? sniProfile = null;
        var effectiveProfile = profile;
        if (profile.Protocol == "snispoof")
        {
            if (!SniSpoofProfile.TryDecode(profile.Raw, out sniProfile, out var sniError) || sniProfile == null)
                throw new EngineException(sniError);
            if (!SniSpoofProfile.TryGetBaseProfile(sniProfile, out var baseProfile, out sniError) || baseProfile == null)
                throw new EngineException(sniError);
            effectiveProfile = baseProfile;
        }

        var plaintext = ConfigValidator.PlaintextRejectedByXray(effectiveProfile);
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

        foreach (var s in ProfileParser.CollectSecrets(effectiveProfile)) Redactor.AddSecret(s);
        _apiPort = NetUtil.FindFreePort();

        // Resolve the real upstream before VPN routes are changed. SNISPF itself
        // dials CONNECT_IP, so that endpoint must also bypass the local tunnel.
        List<IPAddress> serverIps = new();
        Dictionary<string, string>? hostMap = null;
        if (vpn)
        {
            serverIps = await ResolveServersAsync(effectiveProfile, ct).ConfigureAwait(false);
            if (sniProfile != null && NetUtil.TryParseIp(sniProfile.ConnectIp, out var sniIp))
                serverIps.Add(sniIp);
            serverIps = serverIps.Distinct().ToList();
            hostMap = new Dictionary<string, string>();
            foreach (var host in XrayConfigBuilder.CollectServerHosts(effectiveProfile))
            {
                if (NetUtil.TryParseIp(host, out _)) continue;
                var ip = (await Dns.GetHostAddressesAsync(host, ct).ConfigureAwait(false))
                    .FirstOrDefault(a => a.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork);
                if (ip != null) hostMap[host] = ip.ToString();
            }
        }
        var json = XrayConfigBuilder.Build(effectiveProfile, settings, _apiPort, hostMap);
        if (sniProfile != null)
        {
            var relayPort = NetUtil.FindFreePort();
            json = BuildSniSpoofXrayConfig(json, sniProfile, relayPort);
            await StartSniSpoofAsync(sniProfile, relayPort, ct).ConfigureAwait(false);
        }

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

    private static string BuildSniSpoofXrayConfig(string json, SniSpoofData profile, int relayPort)
    {
        using var doc = JsonDocument.Parse(json);
        var root = System.Text.Json.Nodes.JsonNode.Parse(json) as System.Text.Json.Nodes.JsonObject
            ?? throw new EngineException("Could not read the generated Xray configuration.");
        var outbounds = root["outbounds"] as System.Text.Json.Nodes.JsonArray
            ?? throw new EngineException("The Xray profile has no outbounds.");

        bool changed = false;
        foreach (var item in outbounds)
        {
            if (item is not System.Text.Json.Nodes.JsonObject outbound) continue;
            var tag = outbound["tag"]?.ToString() ?? "";
            var protocol = outbound["protocol"]?.ToString() ?? "";
            if (tag.Equals("direct", StringComparison.OrdinalIgnoreCase) ||
                tag.Equals("block", StringComparison.OrdinalIgnoreCase) ||
                protocol.Equals("freedom", StringComparison.OrdinalIgnoreCase) ||
                protocol.Equals("blackhole", StringComparison.OrdinalIgnoreCase))
                continue;

            var stream = outbound["streamSettings"] as System.Text.Json.Nodes.JsonObject;
            var security = stream?["security"]?.ToString() ?? "none";
            if (!security.Equals("tls", StringComparison.OrdinalIgnoreCase) &&
                !security.Equals("reality", StringComparison.OrdinalIgnoreCase))
                continue;

            var settings = outbound["settings"] as System.Text.Json.Nodes.JsonObject;
            var vnext = settings?["vnext"] as System.Text.Json.Nodes.JsonArray;
            var servers = settings?["servers"] as System.Text.Json.Nodes.JsonArray;
            var server = vnext?.FirstOrDefault() as System.Text.Json.Nodes.JsonObject
                ?? servers?.FirstOrDefault() as System.Text.Json.Nodes.JsonObject;
            if (server == null) continue;

            server["address"] = "127.0.0.1";
            server["port"] = relayPort;
            var tlsKey = security.Equals("reality", StringComparison.OrdinalIgnoreCase)
                ? "realitySettings" : "tlsSettings";
            var tls = stream?[tlsKey] as System.Text.Json.Nodes.JsonObject;
            if (tls == null)
            {
                tls = new System.Text.Json.Nodes.JsonObject();
                stream![tlsKey] = tls;
            }
            tls["serverName"] = profile.FakeSni;
            changed = true;
            break;
        }

        if (!changed)
            throw new EngineException("SNISPF needs a TLS or REALITY proxy outbound in the base Xray profile.");

        return root.ToJsonString();
    }

    private async Task StartSniSpoofAsync(SniSpoofData profile, int relayPort, CancellationToken ct)
    {
        if (!File.Exists(AppPaths.SniSpoofExe))
            throw new EngineException("SNISPF runtime is missing. Reinstall TIREXNET.");

        var config = new
        {
            LISTEN_HOST = "127.0.0.1",
            LISTEN_PORT = relayPort,
            LOG_LEVEL = "info",
            CONNECT_IP = profile.ConnectIp,
            CONNECT_PORT = profile.ConnectPort,
            FAKE_SNI = profile.FakeSni,
            BYPASS_METHOD = "fragment",
            FRAGMENT_STRATEGY = "sni_split",
            FRAGMENT_DELAY = 0.05,
            USE_TTL_TRICK = false,
            FAKE_SNI_METHOD = "prefix_fake",
            ENDPOINT_PROBE = false,
            AUTO_FAILOVER = false
        };

        _snispfConfigPath = Path.Combine(AppPaths.RunDir, "snispf-" + Guid.NewGuid().ToString("N") + ".json");
        await File.WriteAllTextAsync(
            _snispfConfigPath,
            JsonSerializer.Serialize(config),
            new System.Text.UTF8Encoding(false),
            ct).ConfigureAwait(false);

        _snispf = ManagedProcess.Start(
            "SNISPF",
            AppPaths.SniSpoofExe,
            new[] { "--config", _snispfConfigPath },
            AppPaths.SniSpoofDir);

        _snispf.OutputLine += line => Log.Info("[snispf] " + line);
        _snispf.UnexpectedExit += code =>
        {
            if (!_stopping) RaiseFailed(
                $"SNISPF stopped unexpectedly (exit code {code}). {_snispf?.LastLines()}");
        };

        var ready = await NetUtil.WaitForPortAsync(
            relayPort,
            TimeSpan.FromSeconds(12),
            () => _snispf?.IsRunning == true,
            ct).ConfigureAwait(false);

        if (!ready)
            throw new EngineException(
                "SNISPF did not open its local relay port. " + _snispf?.LastLines());

        Log.Info($"SNISPF relay is ready on 127.0.0.1:{relayPort}.");
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

            if (_snispf != null)
            {
                await _snispf.StopAsync().ConfigureAwait(false);
                _snispf.Dispose();
                _snispf = null;
            }

            if (_snispfConfigPath != null)
            {
                try { if (File.Exists(_snispfConfigPath)) File.Delete(_snispfConfigPath); }
                catch (IOException ex) { Log.Warn("SNISPF config cleanup: " + ex.Message); }
                _snispfConfigPath = null;
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
