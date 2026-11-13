using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;

namespace TIREXNET.Core.Engines;

/// <summary>Owns the single active connection: lifecycle, state machine, traffic statistics and failure cleanup.</summary>
public sealed class ConnectionManager : IDisposable
{
    private readonly SemaphoreSlim _gate = new(1, 1);
    private IEngine? _engine;
    private CancellationTokenSource? _connectCts;
    private CancellationTokenSource? _statsCts;

    public ConnectionState State { get; private set; } = ConnectionState.Disconnected;
    public string Message { get; private set; } = "Not connected";
    public Profile? ActiveProfile { get; private set; }
    public ConnectionMode ActiveMode { get; private set; }
    public DateTime? ConnectedSinceUtc { get; private set; }
    public TrafficSnapshot Traffic { get; private set; } = new();
    /// <summary>True when the last failure needs the user to restart as administrator.</summary>
    public bool ElevationNeeded { get; private set; }

    public event Action? Changed;

    private void Set(ConnectionState state, string message)
    {
        State = state;
        Message = message;
        Log.Info($"State: {state} — {message}");
        Changed?.Invoke();
    }

    public async Task ConnectAsync(Profile profile, AppSettings settings)
    {
        if (State is ConnectionState.Connected or ConnectionState.Connecting)
            return;
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            ElevationNeeded = false;
            ActiveProfile = profile;
            ActiveMode = EngineCatalog.EffectiveMode(profile, settings);
            Traffic = new TrafficSnapshot();
            ConnectedSinceUtc = null;
            // Order: 1) profile validation, 2) engine/runtime availability, 3) start. An invalid profile is never masked by a missing runtime.
            var invalid = ConfigValidator.Validate(profile);
            if (invalid.Count > 0)
            {
                _engine = null;
                Set(ConnectionState.Error, "The profile is not valid: " + invalid[0]);
                return;
            }
            var unavailable = EngineCatalog.UnavailableReason(profile, settings);
            if (unavailable != null)
            {
                _engine = null;
                Set(ConnectionState.Error, unavailable);
                return;
            }
            Set(ConnectionState.Connecting, $"Connecting to {profile.Name}…");
            _connectCts = new CancellationTokenSource();
            IEngine engine = EngineCatalog.Create(profile.Engine);
            _engine = engine;
            engine.Failed += msg => _ = OnEngineFailedAsync(engine, msg);
            try
            {
                await engine.StartAsync(profile, settings, _connectCts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                await SafeStopAsync(engine).ConfigureAwait(false);
                _engine = null;
                Set(ConnectionState.Disconnected, "Connection cancelled.");
                return;
            }
            catch (ElevationRequiredException ex)
            {
                await SafeStopAsync(engine).ConfigureAwait(false);
                _engine = null;
                ElevationNeeded = true;
                Set(ConnectionState.Error, ex.Message);
                return;
            }
            catch (Exception ex)
            {
                Log.Error("Connection failed: " + ex.Message);
                await SafeStopAsync(engine).ConfigureAwait(false);
                _engine = null;
                Set(ConnectionState.Error, ex is EngineException ? ex.Message : "Connection failed: " + ex.Message);
                return;
            }
            ConnectedSinceUtc = DateTime.UtcNow;
            var what = EngineCatalog.Describe(profile, settings);
            Set(ConnectionState.Connected, $"{profile.Name} · {what}");
            StartStatsLoop(engine);
        }
        finally
        {
            _gate.Release();
        }
    }

    private static async Task SafeStopAsync(IEngine engine)
    {
        try { await engine.StopAsync().ConfigureAwait(false); }
        catch (Exception ex) { Log.Error($"Cleanup of {engine.Name} reported: {ex.Message}"); }
    }

    private async Task OnEngineFailedAsync(IEngine engine, string message)
    {
        if (!ReferenceEquals(engine, _engine)) return;
        Log.Error("Engine failure: " + message);
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            if (!ReferenceEquals(engine, _engine)) return;
            StopStats();
            await SafeStopAsync(engine).ConfigureAwait(false);
            _engine = null;
            ConnectedSinceUtc = null;
            Set(ConnectionState.Error, message + " Network settings were restored.");
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task DisconnectAsync()
    {
        if (State == ConnectionState.Connecting) _connectCts?.Cancel();
        await _gate.WaitAsync().ConfigureAwait(false);
        try
        {
            var engine = _engine;
            if (engine == null)
            {
                if (State is ConnectionState.Error) Set(ConnectionState.Disconnected, "Not connected");
                return;
            }
            Set(ConnectionState.Disconnecting, "Disconnecting…");
            StopStats();
            await SafeStopAsync(engine).ConfigureAwait(false);
            _engine = null;
            ConnectedSinceUtc = null;
            Traffic = new TrafficSnapshot();
            Set(ConnectionState.Disconnected, "Not connected");
        }
        finally
        {
            _gate.Release();
        }
    }

    /// <summary>Blocking cleanup for application exit / crash handlers.</summary>
    public void ShutdownBlocking()
    {
        try { DisconnectAsync().Wait(20000); }
        catch (Exception ex) { Log.Error("Shutdown cleanup failed: " + ex.Message); }
    }

    private void StartStatsLoop(IEngine engine)
    {
        StopStats();
        _statsCts = new CancellationTokenSource();
        var ct = _statsCts.Token;
        _ = Task.Run(async () =>
        {
            long lastUp = 0, lastDown = 0;
            var last = DateTime.UtcNow;
            var first = true;
            while (!ct.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(1000, ct).ConfigureAwait(false);
                    var (up, down) = await engine.QueryTotalsAsync().ConfigureAwait(false);
                    var now = DateTime.UtcNow;
                    var dt = Math.Max(0.2, (now - last).TotalSeconds);
                    if (first) { lastUp = up; lastDown = down; first = false; }
                    Traffic = new TrafficSnapshot
                    {
                        UploadBytes = up,
                        DownloadBytes = down,
                        UploadBytesPerSecond = Math.Max(0, (up - lastUp) / dt),
                        DownloadBytesPerSecond = Math.Max(0, (down - lastDown) / dt),
                    };
                    lastUp = up; lastDown = down; last = now;
                    Changed?.Invoke();
                }
                catch (OperationCanceledException) { break; }
                catch (Exception ex) { Log.Warn("Statistics error: " + ex.Message); }
            }
        }, ct);
    }

    private void StopStats()
    {
        _statsCts?.Cancel();
        _statsCts = null;
    }

    public void Dispose()
    {
        StopStats();
        _gate.Dispose();
    }
}
