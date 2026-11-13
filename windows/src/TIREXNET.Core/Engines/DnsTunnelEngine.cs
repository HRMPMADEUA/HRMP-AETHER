using System.Net;
using System.Net.Sockets;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;

namespace TIREXNET.Core.Engines;

/// <summary>Runs the official MasterDNS, CottenDNS or StormDNS Windows client as a loopback SOCKS5 tunnel.</summary>
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

        var work = Path.Combine(TIREXNET.Core.AppPaths.RunDir, _kind.ToString().ToLowerInvariant());
        Directory.CreateDirectory(work);
        _configPath = Path.Combine(work, "client_config.toml");
        _resolverPath = Path.Combine(work, "client_resolvers.txt");

        try
        {
            await File.WriteAllTextAsync(_configPath, data.Config, new System.Text.UTF8Encoding(false), ct);
            await File.WriteAllTextAsync(_resolverPath, data.Resolvers + "\n", new System.Text.UTF8Encoding(false), ct);

            _process = ManagedProcess.Start(Name, data.Executable,
                new[] { "--config", _configPath, "--resolvers", _resolverPath },
                data.WorkingDirectory);

            _process.UnexpectedExit += code =>
            {
                if (!_stopping)
                    Failed?.Invoke($"{Name} stopped unexpectedly (exit code {code}). {_process?.LastLines()}");
            };

            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(20));

            while (!timeout.IsCancellationRequested)
            {
                if (_process == null || !_process.IsRunning)
                    throw new EngineException($"{Name} exited during startup. {_process?.LastLines()}");

                try
                {
                    using var client = new TcpClient();
                    await client.ConnectAsync(IPAddress.Loopback, data.Port, timeout.Token);
                    Log.Info($"{Name} SOCKS5 is listening on 127.0.0.1:{data.Port}.");
                    return;
                }
                catch (SocketException) { }
                await Task.Delay(200, timeout.Token);
            }

            throw new EngineException($"{Name} did not open its local SOCKS5 port {data.Port}.");
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
            if (path != null)
            {
                try { if (File.Exists(path)) File.Delete(path); }
                catch (IOException ex) { Log.Warn($"{Name} temporary file cleanup: {ex.Message}"); }
            }
        }
        _configPath = null;
        _resolverPath = null;
    }

    public Task<(long Upload, long Download)> QueryTotalsAsync() =>
        Task.FromResult((0L, 0L));
}
