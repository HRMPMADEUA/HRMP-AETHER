using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;

namespace TIREXNET.Core.Engines;

/// <summary>Runs the official MHRV HTTP and SOCKS5 proxy runtime.</summary>
public sealed class MhrvEngine : IEngine
{
    private ManagedProcess? _process;
    private string? _configPath;
    private bool _stopping;
    private bool _systemProxySet;

    public string Name => "MHRV";
    public event Action<string>? Failed;

    private static string SetNetworkValue(string config, string key, string value)
    {
        var section = Regex.Match(config,
            @"(?ms)(^\s*\[network\]\s*\r?\n)(.*?)(?=^\s*\[|\z)");

        if (!section.Success)
            throw new EngineException("MHRV configuration has no [network] section.");

        var body = section.Groups[2].Value;
        var pattern = @"(?m)^\s*" + Regex.Escape(key) + @"\s*=.*$";
        var replacement = key + " = " + value;

        if (Regex.IsMatch(body, pattern))
            body = Regex.Replace(body, pattern, _ => replacement);
        else
            body = body.TrimEnd() + "\n" + replacement + "\n";

        return config[..section.Groups[2].Index] +
               body +
               config[(section.Groups[2].Index + section.Groups[2].Length)..];
    }

    public async Task StartAsync(Profile profile, AppSettings settings, CancellationToken ct)
    {
        _stopping = false;

        if (!MhrvProfile.TryDecode(profile.Raw, out var data, out var error) || data == null)
            throw new EngineException(error);

        if (!File.Exists(AppPaths.MhrvExe))
            throw new EngineException("MHRV runtime is missing. Reinstall TIREXNET.");

        var settingErrors = ConfigValidator.ValidateSettings(settings);
        if (settingErrors.Count > 0)
            throw new EngineException("Settings are not valid: " + settingErrors[0]);

        if (!NetUtil.IsPortFree(settings.SocksPort))
            throw new EngineException($"SOCKS5 port {settings.SocksPort} is already in use.");

        if (!NetUtil.IsPortFree(settings.HttpPort))
            throw new EngineException($"HTTP port {settings.HttpPort} is already in use.");

        foreach (var secret in MhrvProfile.Secrets(profile.Raw))
            Redactor.AddSecret(secret);

        var config = data.Config;
        config = SetNetworkValue(config, "listen_host", "\"127.0.0.1\"");
        config = SetNetworkValue(config, "listen_port", settings.HttpPort.ToString());
        config = SetNetworkValue(config, "socks5_port", settings.SocksPort.ToString());

        var work = Path.Combine(AppPaths.RunDir, "mhrv");
        Directory.CreateDirectory(work);
        _configPath = Path.Combine(work, "mhrv-" + Guid.NewGuid().ToString("N") + ".toml");

        try
        {
            await File.WriteAllTextAsync(_configPath, config, new UTF8Encoding(false), ct)
                .ConfigureAwait(false);

            _process = ManagedProcess.Start(
                "MHRV",
                AppPaths.MhrvExe,
                new[] { "--config", _configPath },
                AppPaths.MhrvDir);

            _process.OutputLine += line => Log.Info("[mhrv] " + line);
            _process.UnexpectedExit += code =>
            {
                if (!_stopping)
                    Failed?.Invoke(
                        $"MHRV stopped unexpectedly (exit code {code}). {_process?.LastLines()}");
            };

            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(30));

            var httpReady = await NetUtil.WaitForPortAsync(
                settings.HttpPort,
                TimeSpan.FromSeconds(20),
                () => _process?.IsRunning == true,
                timeout.Token).ConfigureAwait(false);

            if (!httpReady)
                throw new EngineException(
                    "MHRV did not open its HTTP proxy port. " + _process?.LastLines());

            var socksReady = await NetUtil.WaitForPortAsync(
                settings.SocksPort,
                TimeSpan.FromSeconds(10),
                () => _process?.IsRunning == true,
                timeout.Token).ConfigureAwait(false);

            if (!socksReady)
                throw new EngineException(
                    "MHRV did not open its SOCKS5 proxy port. " + _process?.LastLines());

            if (settings.SetSystemProxy)
            {
                SystemProxy.Enable(settings.HttpPort);
                _systemProxySet = true;
            }

            Log.Info($"MHRV ready: HTTP 127.0.0.1:{settings.HttpPort}, SOCKS5 127.0.0.1:{settings.SocksPort}.");
        }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested)
        {
            await StopAsync().ConfigureAwait(false);
            throw new EngineException("MHRV startup timed out.");
        }
        catch
        {
            await StopAsync().ConfigureAwait(false);
            throw;
        }
    }

    public async Task StopAsync()
    {
        _stopping = true;

        if (_systemProxySet)
        {
            SystemProxy.RestoreIfNeeded();
            _systemProxySet = false;
        }

        if (_process != null)
        {
            await _process.StopAsync().ConfigureAwait(false);
            _process.Dispose();
            _process = null;
        }

        if (_configPath != null)
        {
            try
            {
                if (File.Exists(_configPath))
                    File.Delete(_configPath);
            }
            catch (IOException ex)
            {
                Log.Warn("MHRV config cleanup: " + ex.Message);
            }
            _configPath = null;
        }
    }

    public Task<(long Upload, long Download)> QueryTotalsAsync() =>
        Task.FromResult((0L, 0L));
}
