using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;

namespace TIREXNET.Core.Engines;

/// <summary>Runs MasterDNS, CottenDNS or StormDNS as the application's configured loopback SOCKS5 proxy.</summary>
public sealed class DnsTunnelEngine : IEngine
{
    private readonly EngineKind _kind;
    private ManagedProcess? _process;
    private string? _configPath;
    private string? _resolverPath;
    private bool _stopping;

    public DnsTunnelEngine(EngineKind kind) => _kind = kind;

    public string Name => _kind switch
    {
        EngineKind.MasterDns => "MasterDNS",
        EngineKind.CottenDns => "CottenDNS",
        _ => "StormDNS"
    };

    public event Action<string>? Failed;

    public async Task StartAsync(Profile profile, AppSettings settings, CancellationToken ct)
    {
        _stopping = false;

        if (!DnsTunnelProfile.TryDecode(profile.Raw, out var data, out var error) || data == null)
            throw new EngineException(error);

        if (!File.Exists(data.Executable))
            throw new EngineException($"{Name} runtime is missing. Reinstall TIREXNET.");

        // All proxy engines must use the same port expected by the app's
        // proxy controller and system-proxy integration.
        if (settings.SocksPort is < 1024 or > 65535)
            throw new EngineException("The configured SOCKS5 port must be between 1024 and 65535.");

        var portMatches = Regex.Matches(
            data.Config,
            @"(?im)^\s*LISTEN_PORT\s*=\s*\d+\s*$");

        if (portMatches.Count != 1)
            throw new EngineException($"{Name} configuration must contain exactly one LISTEN_PORT setting.");

        var appConfig = Regex.Replace(
            data.Config,
            @"(?im)^\s*LISTEN_PORT\s*=\s*\d+\s*$",
            "LISTEN_PORT = " + settings.SocksPort);

        data = data with
        {
            Config = appConfig,
            Port = settings.SocksPort
        };

        var work = Path.Combine(
            TIREXNET.Core.AppPaths.RunDir,
            _kind.ToString().ToLowerInvariant());

        Directory.CreateDirectory(work);

        _configPath = Path.Combine(work, "client_config.toml");
        _resolverPath = Path.Combine(work, "client_resolvers.txt");

        try
        {
            await File.WriteAllTextAsync(
                _configPath, data.Config, new UTF8Encoding(false), ct);

            await File.WriteAllTextAsync(
                _resolverPath, data.Resolvers.Trim() + "\n",
                new UTF8Encoding(false), ct);

            // These flags are supported by the official MasterDNS,
            // CottenDNS and StormDNS Windows clients.
            _process = ManagedProcess.Start(
                Name,
                data.Executable,
                new[] { "-config", _configPath, "-resolvers", _resolverPath },
                data.WorkingDirectory);

            _process.UnexpectedExit += code =>
            {
                if (!_stopping)
                    Failed?.Invoke(
                        $"{Name} stopped unexpectedly (exit code {code}). " +
                        $"{_process?.LastLines()}");
            };

            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(25));

            while (true)
            {
                timeout.Token.ThrowIfCancellationRequested();

                if (_process == null || !_process.IsRunning)
                    throw new EngineException(
                        $"{Name} exited during startup. {_process?.LastLines()}");

                try
                {
                    using var client = new TcpClient();
                    await client.ConnectAsync(
                        IPAddress.Loopback, data.Port, timeout.Token);

                    Log.Info(
                        $"{Name} SOCKS5 is ready on 127.0.0.1:{data.Port}.");

                    return;
                }
                catch (SocketException)
                {
                    // The client is still starting; retry until timeout.
                }

                await Task.Delay(200, timeout.Token);
            }
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            await StopAsync();
            throw new EngineException(
                $"{Name} did not open SOCKS5 on 127.0.0.1:{data.Port} within 25 seconds.");
        }
        catch
        {
            await StopAsync();
            throw;
        }
    }

    public async Task StopAsync()
    {
        _stopping = true;

        if (_process != null)
        {
            await _process.StopAsync();
            _process.Dispose();
            _process = null;
        }

        foreach (var path in new[] { _configPath, _resolverPath })
        {
            if (path == null) continue;

            try
            {
                if (File.Exists(path))
                    File.Delete(path);
            }
            catch (IOException ex)
            {
                Log.Warn($"{Name} temporary file cleanup: {ex.Message}");
            }
        }

        _configPath = null;
        _resolverPath = null;
    }

    public Task<(long Upload, long Download)> QueryTotalsAsync() =>
        Task.FromResult((0L, 0L));
}
