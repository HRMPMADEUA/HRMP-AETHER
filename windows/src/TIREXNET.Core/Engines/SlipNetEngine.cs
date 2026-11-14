using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;

namespace TIREXNET.Core.Engines;

/// <summary>
/// SlipNet through its upstream Go command-line client (built for Windows from a pinned commit): the client provides a local
/// SOCKS5 proxy for the DNSTT / NoizDNS / VayDNS / SSH / SOCKS5 / VLESS / DoH tunnels. VPN mode puts tun2socks + Wintun on top.
/// </summary>
public sealed class SlipNetEngine : IEngine
{
    private ManagedProcess? _proc;
    private VpnTunnel? _tunnel;
    private bool _stopping;
    private bool _vpn;

    public string Name => "SlipNet";
    public event Action<string>? Failed;

    public static bool IsSoarRuntimeAvailable => File.Exists(AppPaths.SoarWrapperExe);
    public static bool IsRuntimeAvailable => File.Exists(AppPaths.SlipNetExe) || IsSoarRuntimeAvailable;

    /// <summary>The upstream client prints this when the tunnel is established and the local proxy accepts connections.</summary>
    internal const string ReadyMarker = "Connected! SOCKS5 proxy listening";

    internal static string[] BuildArgs(string link, int port) => new[] { "--host", "127.0.0.1", "--port", port.ToString(System.Globalization.CultureInfo.InvariantCulture), link };

    public async Task StartAsync(Profile profile, AppSettings settings, CancellationToken ct)
    {
        _stopping = false;
        var problems = ConfigValidator.Validate(profile);
        if (problems.Count > 0) throw new EngineException(problems[0]);
        var settingProblems = ConfigValidator.ValidateSettings(settings);
        if (settingProblems.Count > 0) throw new EngineException("Settings are not valid: " + settingProblems[0]);
        if (!SlipNetProfile.TryParse(profile.Raw, out var parsed, out var parseError) || parsed == null)
            throw new EngineException(parseError);
        var isSoar = parsed.IsSoar;
        if (parsed.IsSoarSsh)
            throw new EngineException("Soar + SSH is not supported by this Windows runtime yet.");
        var runtimeExe = isSoar ? AppPaths.SoarWrapperExe : AppPaths.SlipNetExe;
        var runtimeDir = isSoar ? AppPaths.SoarDir : AppPaths.SlipNetDir;
        if (!File.Exists(runtimeExe))
            throw new EngineException(isSoar ? "The Soar Windows runtime is missing. Reinstall TIREXNET." : "The SlipNet runtime is missing from this installation. Reinstall TIREXNET.");
        _vpn = settings.Mode == ConnectionMode.Vpn;
        if (_vpn)
        {
            if (!XrayEngine.IsVpnRuntimeAvailable && !(File.Exists(AppPaths.Tun2SocksExe) && File.Exists(AppPaths.WintunDll)))
                throw new EngineException("VPN mode needs tun2socks and Wintun, which are missing from this installation.");
            if (!Elevation.IsAdministrator()) throw new ElevationRequiredException("VPN mode needs administrator rights to create the network adapter and routes.");
        }
        if (!NetUtil.IsPortFree(settings.SocksPort)) throw new EngineException($"Port {settings.SocksPort} is already in use. Pick another SOCKS5 port in Settings.");
        foreach (var s in SlipNetProfile.Secrets(profile.Raw)) Redactor.AddSecret(s);

        List<IPAddress> serverIps = new();
        if (_vpn) serverIps = await ResolveAsync(SlipNetProfile.ServerHosts(profile.Raw), ct).ConfigureAwait(false);

        Log.Info($"Starting SlipNet ({(_vpn ? "VPN" : "proxy")} mode).");
        var ready = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        var runtimeArgs = isSoar
            ? new[] { "--host", "127.0.0.1", "--port", settings.SocksPort.ToString(System.Globalization.CultureInfo.InvariantCulture), profile.Raw.Trim() }
            : BuildArgs(profile.Raw.Trim(), settings.SocksPort);
        var readyMarker = isSoar ? "SOAR_READY" : ReadyMarker;
        var runtimeName = isSoar ? "Soar" : "SlipNet";
        var proc = ManagedProcess.Start(runtimeName, runtimeExe, runtimeArgs, runtimeDir);
        _proc = proc;
        proc.OutputLine += l =>
        {
            Log.Info("[slipnet] " + l);
            if (l.Contains(readyMarker, StringComparison.Ordinal)) ready.TrySetResult(true);
        };
        proc.UnexpectedExit += code =>
        {
            var msg = $"{runtimeName} stopped unexpectedly (exit code {code}). {proc.LastLines()}";
            if (!ready.TrySetException(new EngineException(msg))) RaiseFailed(msg);
        };
        try
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(60));
            using var r1 = ct.Register(() => ready.TrySetCanceled());
            using var r2 = timeout.Token.Register(() => ready.TrySetException(new EngineException("SlipNet did not establish its tunnel within 60 seconds. " + proc.LastLines())));
            await ready.Task.ConfigureAwait(false);
            await VerifySocks5Async(settings.SocksPort, proc, ct).ConfigureAwait(false);
            Log.Info($"{runtimeName} is listening on 127.0.0.1:{settings.SocksPort} (SOCKS5).");
            if (_vpn)
            {
                _tunnel = new VpnTunnel();
                _tunnel.Failed += RaiseFailed;
                await _tunnel.StartAsync(serverIps, settings, ct).ConfigureAwait(false);
            }
        }
        catch
        {
            await StopAsync().ConfigureAwait(false);
            throw;
        }
    }

    /// <summary>
    /// Confirms that the local SOCKS5 listener accepts a real no-authentication
    /// greeting. A readiness log line alone is not considered sufficient.
    /// </summary>
    private static async Task VerifySocks5Async(
        int port, ManagedProcess process, CancellationToken ct)
    {
        var deadline = DateTime.UtcNow.AddSeconds(12);
        Exception? lastError = null;

        while (DateTime.UtcNow < deadline)
        {
            ct.ThrowIfCancellationRequested();

            if (!process.IsRunning)
                throw new EngineException(
                    "Soar stopped before its SOCKS5 listener passed verification. " +
                    process.LastLines());

            try
            {
                using var client = new TcpClient();
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
                timeout.CancelAfter(TimeSpan.FromSeconds(2));

                await client.ConnectAsync(
                    IPAddress.Loopback, port, timeout.Token).ConfigureAwait(false);

                using NetworkStream stream = client.GetStream();
                stream.ReadTimeout = 2000;
                stream.WriteTimeout = 2000;

                byte[] greeting = { 0x05, 0x01, 0x00 };
                await stream.WriteAsync(greeting, timeout.Token).ConfigureAwait(false);
                await stream.FlushAsync(timeout.Token).ConfigureAwait(false);

                byte[] response = new byte[2];
                int received = 0;

                while (received < response.Length)
                {
                    int count = await stream.ReadAsync(
                        response.AsMemory(received), timeout.Token).ConfigureAwait(false);

                    if (count == 0)
                        throw new IOException("SOCKS5 listener closed during greeting.");

                    received += count;
                }

                if (response[0] != 0x05 || response[1] != 0x00)
                    throw new IOException(
                        $"Unexpected SOCKS5 greeting response: {response[0]:X2} {response[1]:X2}");

                Log.Info($"SOCKS5 protocol verified on 127.0.0.1:{port}.");
                return;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex) when (
                ex is SocketException or IOException or OperationCanceledException or
                ObjectDisposedException)
            {
                lastError = ex;
                await Task.Delay(300, ct).ConfigureAwait(false);
            }
        }

        throw new EngineException(
            $"SOCKS5 listener on 127.0.0.1:{port} did not pass protocol verification. " +
            (lastError?.Message ?? "No valid SOCKS5 response received.") +
            " " + process.LastLines());
    }

    private static async Task<List<IPAddress>> ResolveAsync(List<string> hosts, CancellationToken ct)
    {
        var ips = new List<IPAddress>();
        foreach (var host in hosts)
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
                throw new EngineException($"Could not resolve an IPv4 address for \"{host}\" ({ex.Message.TrimEnd('.')}). Use Proxy mode or check your connection.");
            }
        }
        if (ips.Count == 0) throw new EngineException("VPN mode needs at least one IPv4 server or resolver address in the SlipNet profile. Use Proxy mode instead.");
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
        }
        finally
        {
            if (_proc != null)
            {
                await _proc.StopAsync().ConfigureAwait(false);
                _proc.Dispose();
                _proc = null;
            }
        }
    }

    /// <summary>The SlipNet client has no statistics interface: totals come from the Wintun adapter in VPN mode, and are unavailable in proxy mode.</summary>
    public Task<(long Upload, long Download)> QueryTotalsAsync()
    {
        if (!_vpn) return Task.FromResult((0L, 0L));
        try
        {
            var nic = NetworkInterface.GetAllNetworkInterfaces().FirstOrDefault(n => n.Name == VpnTunnel.AdapterName);
            if (nic == null) return Task.FromResult((0L, 0L));
            var s = nic.GetIPv4Statistics();
            return Task.FromResult((s.BytesSent, s.BytesReceived));
        }
        catch (NetworkInformationException)
        {
            return Task.FromResult((0L, 0L));
        }
    }
}
