using System.Diagnostics;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Win32;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;

namespace TIREXNET.Core.Engines;

/// <summary>
/// Genuine OpenVPN: TIREXNET launches and supervises the official, Authenticode-signed openvpn.exe (OpenVPN Community build with
/// the Wintun / ovpn-dco drivers) and talks to it through OpenVPN's own management interface (state, byte counters, credentials,
/// graceful shutdown). Routes, DNS and the adapter are created by OpenVPN from the profile and the server's pushed options.
/// </summary>
public sealed class OpenVpnEngine : IEngine
{
    private const string RequiredSigner = "OpenVPN";
    private ManagedProcess? _proc;
    private TcpClient? _client;
    private NetworkStream? _stream;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private CancellationTokenSource? _cts;
    private bool _stopping;
    private bool _connected;
    private long _in, _out;
    private (string User, string Pass)? _cred;
    private TaskCompletionSource<bool>? _tcs;

    public string Name => "OpenVPN";
    public event Action<string>? Failed;

    private static string RunConfDir => Path.Combine(AppPaths.MachineDir, "run");
    private static string RunConfPath => Path.Combine(RunConfDir, "tirexnet-ovpn.ovpn");

    public static bool IsRuntimeAvailable => FindExe() != null;

    /// <summary>The bundled installation first, then the one registered by the official OpenVPN installer.</summary>
    public static string? FindExe()
    {
        if (File.Exists(AppPaths.OpenVpnBundledExe)) return AppPaths.OpenVpnBundledExe;
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\OpenVPN");
            if (key?.GetValue("exe_path") is string p && File.Exists(p)) return p;
        }
        catch (Exception ex) when (ex is System.Security.SecurityException or UnauthorizedAccessException or IOException) { }
        var pf = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "OpenVPN", "bin", "openvpn.exe");
        return File.Exists(pf) ? pf : null;
    }

    public async Task StartAsync(Profile profile, AppSettings settings, CancellationToken ct)
    {
        _stopping = false;
        _connected = false;
        _in = _out = 0;
        var problems = ConfigValidator.Validate(profile);
        if (problems.Count > 0) throw new EngineException("The OpenVPN profile is not valid: " + problems[0]);
        var settingProblems = ConfigValidator.ValidateSettings(settings);
        if (settingProblems.Count > 0) throw new EngineException("Settings are not valid: " + settingProblems[0]);
        var exe = FindExe() ?? throw new EngineException("OpenVPN is not installed. Install the official OpenVPN Community build (the TIREXNET installer offers it) and try again.");
        if (!Elevation.IsAdministrator())
            throw new ElevationRequiredException("OpenVPN needs administrator rights to create its network adapter and routes.");
        if (!Authenticode.IsTrusted(exe, out var signer) || signer.IndexOf(RequiredSigner, StringComparison.OrdinalIgnoreCase) < 0)
            throw new EngineException("openvpn.exe does not carry a valid OpenVPN signature, so TIREXNET refuses to run it. Reinstall the official OpenVPN Community build.");
        foreach (var s in OpenVpnConfig.Secrets(profile.Raw)) Redactor.AddSecret(s);
        _cred = OpenVpnConfig.ExtractCredentials(profile.Raw);

        // A previous run that crashed would hold the adapter and routes.
        KillRecorded(RunState.Load());

        var port = NetUtil.FindFreePort();
        var password = Convert.ToHexString(RandomNumberGenerator.GetBytes(16));
        Redactor.AddSecret(password);
        var text = OpenVpnConfig.BuildRuntimeConfig(profile.Raw, settings, port);

        _cts = new CancellationTokenSource();
        _tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        try
        {
            AdminDir.Prepare(RunConfDir);
            File.WriteAllText(RunConfPath, text);
            Log.Info($"Starting OpenVPN ({Path.GetFileName(exe)}, signer verified).");
            var proc = ManagedProcess.Start("OpenVPN", exe, new[] { "--config", RunConfPath }, Path.GetDirectoryName(exe), password + "\n");
            _proc = proc;
            proc.OutputLine += l => Log.Info("[openvpn] " + l);
            proc.UnexpectedExit += code =>
            {
                var msg = $"OpenVPN stopped unexpectedly (exit code {code}). {proc.LastLines()}";
                if (!_tcs.TrySetException(new EngineException(msg))) RaiseFailed(msg);
            };
            try
            {
                using var p = Process.GetProcessById(proc.Id);
                var journal = RunState.Load();
                journal.OpenVpnPid = proc.Id;
                journal.OpenVpnStartTicks = p.StartTime.Ticks;
                RunState.Save(journal);
            }
            catch (Exception ex) when (ex is ArgumentException or InvalidOperationException or System.ComponentModel.Win32Exception) { }

            _ = Task.Run(() => RunManagementAsync(port, password, _cts.Token));
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(60));
            using var reg = ct.Register(() => _tcs.TrySetCanceled());
            using var treg = timeout.Token.Register(() => _tcs.TrySetException(new EngineException("OpenVPN did not connect within 60 seconds. Check the server address, port and protocol.")));
            await _tcs.Task.ConfigureAwait(false);
            _connected = true;
            Log.Info($"OpenVPN tunnel \"{profile.Name}\" is up.");
        }
        catch
        {
            await StopAsync().ConfigureAwait(false);
            throw;
        }
        finally
        {
            DeleteRuntimeConfig(); // the keys inside are already loaded by openvpn.exe
        }
    }

    private async Task RunManagementAsync(int port, string password, CancellationToken ct)
    {
        try
        {
            var client = new TcpClient();
            var deadline = DateTime.UtcNow.AddSeconds(15);
            while (true)
            {
                try { await client.ConnectAsync("127.0.0.1", port, ct).ConfigureAwait(false); break; }
                catch (SocketException) when (DateTime.UtcNow < deadline && _proc is { IsRunning: true })
                {
                    client.Dispose();
                    client = new TcpClient();
                    await Task.Delay(250, ct).ConfigureAwait(false);
                }
            }
            _client = client;
            _stream = client.GetStream();
            var buf = new byte[4096];
            var sb = new StringBuilder();
            var sentPassword = false;
            while (!ct.IsCancellationRequested)
            {
                var n = await _stream.ReadAsync(buf, ct).ConfigureAwait(false);
                if (n == 0) break;
                sb.Append(Encoding.UTF8.GetString(buf, 0, n));
                if (!sentPassword && sb.ToString().Contains("ENTER PASSWORD:", StringComparison.Ordinal))
                {
                    sentPassword = true;
                    sb.Clear();
                    await SendAsync(password).ConfigureAwait(false);
                    continue;
                }
                string all;
                while ((all = sb.ToString()).IndexOf('\n') is var idx && idx >= 0)
                {
                    var line = all[..idx].Trim();
                    sb.Remove(0, idx + 1);
                    if (line.Length > 0) await HandleLineAsync(line).ConfigureAwait(false);
                }
            }
        }
        catch (OperationCanceledException) { }
        catch (Exception ex) when (ex is IOException or SocketException or ObjectDisposedException or InvalidOperationException)
        {
            if (!_stopping) _tcs?.TrySetException(new EngineException("Lost the connection to the OpenVPN management channel: " + ex.Message));
        }
    }

    private async Task HandleLineAsync(string line)
    {
        if (line.StartsWith("SUCCESS: password is correct", StringComparison.Ordinal))
        {
            await SendAsync("state on").ConfigureAwait(false);
            await SendAsync("bytecount 2").ConfigureAwait(false);
            await SendAsync("hold release").ConfigureAwait(false);
        }
        else if (line.StartsWith(">PASSWORD:Need 'Auth'", StringComparison.Ordinal))
        {
            if (_cred is { } c)
            {
                await SendAsync($"username \"Auth\" \"{Esc(c.User)}\"").ConfigureAwait(false);
                await SendAsync($"password \"Auth\" \"{Esc(c.Pass)}\"").ConfigureAwait(false);
            }
            else _tcs?.TrySetException(new EngineException("The server asks for a username and password, but the profile has none."));
        }
        else if (line.StartsWith(">PASSWORD:Verification Failed", StringComparison.Ordinal))
        {
            var e = new EngineException("The server rejected the username or password.");
            if (!_tcs!.TrySetException(e)) RaiseFailed(e.Message);
        }
        else if (line.StartsWith(">STATE:", StringComparison.Ordinal))
        {
            var parts = line[7..].Split(',');
            var state = parts.Length > 1 ? parts[1] : "";
            Log.Info("OpenVPN state: " + state + (parts.Length > 2 && parts[2].Length > 0 ? " (" + parts[2] + ")" : ""));
            if (state == "CONNECTED") _tcs?.TrySetResult(true);
        }
        else if (line.StartsWith(">BYTECOUNT:", StringComparison.Ordinal))
        {
            var parts = line[11..].Split(',');
            if (parts.Length == 2 && long.TryParse(parts[0], out var i) && long.TryParse(parts[1], out var o)) { _in = i; _out = o; }
        }
        else if (line.StartsWith(">FATAL:", StringComparison.Ordinal))
        {
            var msg = "OpenVPN reported a fatal error: " + line[7..];
            if (!_tcs!.TrySetException(new EngineException(msg))) RaiseFailed(msg);
        }
    }

    private static string Esc(string s) => s.Replace("\\", "\\\\").Replace("\"", "\\\"");

    private async Task SendAsync(string command)
    {
        var s = _stream;
        if (s == null) return;
        await _writeLock.WaitAsync().ConfigureAwait(false);
        try { await s.WriteAsync(Encoding.UTF8.GetBytes(command + "\n")).ConfigureAwait(false); }
        catch (Exception ex) when (ex is IOException or ObjectDisposedException) { }
        finally { _writeLock.Release(); }
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
            // Ask OpenVPN to leave cleanly so that it removes its routes, DNS settings and adapter itself.
            if (_proc is { IsRunning: true } && _stream != null)
            {
                await SendAsync("signal SIGTERM").ConfigureAwait(false);
                for (var i = 0; i < 25 && _proc.IsRunning; i++) await Task.Delay(200).ConfigureAwait(false);
            }
        }
        finally
        {
            _cts?.Cancel();
            try { _client?.Dispose(); } catch (ObjectDisposedException) { }
            _client = null;
            _stream = null;
            if (_proc != null)
            {
                await _proc.StopAsync().ConfigureAwait(false);
                _proc.Dispose();
                _proc = null;
            }
            DeleteRuntimeConfig();
            _connected = false;
            var journal = RunState.Load();
            journal.OpenVpnPid = 0;
            journal.OpenVpnStartTicks = 0;
            if (journal.Routes.Count == 0 && !journal.TunActive && string.IsNullOrEmpty(journal.WireGuardTunnel) && string.IsNullOrEmpty(journal.AmneziaTunnel)) RunState.Clear();
            else RunState.Save(journal);
        }
    }

    public Task<(long Upload, long Download)> QueryTotalsAsync() => Task.FromResult((_out, _in));

    public static void DeleteRuntimeConfig() => AdminDir.TryDelete(RunConfPath);

    /// <summary>Stops the openvpn.exe recorded in the journal, but only if it is still the very same process (PID and start time).</summary>
    public static bool KillRecorded(RunStateData state)
    {
        if (state.OpenVpnPid == 0) return false;
        try
        {
            using var p = Process.GetProcessById(state.OpenVpnPid);
            if (!p.ProcessName.Equals("openvpn", StringComparison.OrdinalIgnoreCase) || p.StartTime.Ticks != state.OpenVpnStartTicks) return false;
            p.Kill(true);
            p.WaitForExit(4000);
            return true;
        }
        catch (Exception ex) when (ex is ArgumentException or InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            return false;
        }
    }
}
