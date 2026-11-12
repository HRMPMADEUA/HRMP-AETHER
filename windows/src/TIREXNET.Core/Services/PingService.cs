using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using TIREXNET.Core.Engines;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;

namespace TIREXNET.Core.Services;

public enum PingKind { Ok, Timeout, Failed, Unsupported }

public sealed record PingResult(PingKind Kind, int Ms, string Method)
{
    public int AsProfileValue => Kind switch { PingKind.Ok => Math.Max(1, Ms), PingKind.Timeout => -2, PingKind.Failed => -3, _ => -1 };
}

/// <summary>
/// Real latency measurement, same rules as the Android app: TCP connect time to the server for TCP based profiles
/// (best of two attempts); ICMP echo for UDP based ones (WireGuard, AmneziaWG, SlipNet DNS resolvers, UDP OpenVPN).
/// A TCP connect proves the endpoint is reachable, not that the proxy protocol works. Many servers drop ICMP, so a timeout
/// there can mean "ICMP blocked".
/// </summary>
public static class PingService
{
    public const int TimeoutMs = 3000;

    public static bool UsesIcmp(Profile p)
    {
        switch (p.Engine)
        {
            case EngineKind.WireGuard:
            case EngineKind.AmneziaWG:
            case EngineKind.SlipNet:
                return true;
            case EngineKind.OpenVpn:
                var remote = OpenVpnConfig.Remotes(p.Raw).FirstOrDefault();
                return remote == null || remote.Proto.StartsWith("udp", StringComparison.OrdinalIgnoreCase);
            default:
                return false;
        }
    }

    public static async Task<PingResult> TestAsync(Profile p, CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(p.Server)) return new PingResult(PingKind.Unsupported, -1, "none");
        if (UsesIcmp(p)) return await IcmpAsync(p.Server, ct).ConfigureAwait(false);
        if (p.Port is < 1 or > 65535) return new PingResult(PingKind.Unsupported, -1, "none");
        return await TcpAsync(p.Server, p.Port, ct).ConfigureAwait(false);
    }

    internal static async Task<PingResult> TcpAsync(string host, int port, CancellationToken ct)
    {
        var best = int.MaxValue;
        var last = PingKind.Failed;
        for (var attempt = 0; attempt < 2; attempt++)
        {
            ct.ThrowIfCancellationRequested();
            using var client = new TcpClient();
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeoutMs);
            var sw = Stopwatch.StartNew();
            try
            {
                await client.ConnectAsync(host, port, cts.Token).ConfigureAwait(false);
                best = Math.Min(best, (int)sw.ElapsedMilliseconds);
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested) { last = PingKind.Timeout; }
            catch (SocketException ex) { last = ex.SocketErrorCode == SocketError.TimedOut ? PingKind.Timeout : PingKind.Failed; }
        }
        return best != int.MaxValue ? new PingResult(PingKind.Ok, best, "tcp") : new PingResult(last, -1, "tcp");
    }

    internal static async Task<PingResult> IcmpAsync(string host, CancellationToken ct)
    {
        try
        {
            ct.ThrowIfCancellationRequested();
            using var ping = new Ping();
            var reply = await ping.SendPingAsync(host, TimeoutMs).ConfigureAwait(false);
            return reply.Status switch
            {
                IPStatus.Success => new PingResult(PingKind.Ok, (int)reply.RoundtripTime, "icmp"),
                IPStatus.TimedOut => new PingResult(PingKind.Timeout, -1, "icmp"),
                _ => new PingResult(PingKind.Failed, -1, "icmp"),
            };
        }
        catch (Exception ex) when (ex is PingException or SocketException or ArgumentException)
        {
            return new PingResult(PingKind.Failed, -1, "icmp");
        }
    }

    /// <summary>Tests every profile, at most <paramref name="concurrency"/> at a time, and stores the value on each profile.</summary>
    public static async Task<List<(Profile Profile, PingResult Result)>> TestAllAsync(IEnumerable<Profile> profiles, int concurrency = 3, CancellationToken ct = default)
    {
        var list = profiles.ToList();
        var results = new (Profile, PingResult)[list.Count];
        using var gate = new SemaphoreSlim(Math.Max(1, concurrency));
        var tasks = list.Select(async (p, i) =>
        {
            await gate.WaitAsync(ct).ConfigureAwait(false);
            try
            {
                var r = await TestAsync(p, ct).ConfigureAwait(false);
                p.PingMs = r.AsProfileValue;
                results[i] = (p, r);
            }
            finally { gate.Release(); }
        });
        await Task.WhenAll(tasks).ConfigureAwait(false);
        return results.ToList();
    }

    /// <summary>Lowest measured latency among profiles that are connectable with this installation (Smart Connect).</summary>
    public static Profile? Best(IEnumerable<(Profile Profile, PingResult Result)> results, AppSettings settings) =>
        results.Where(r => r.Result.Kind == PingKind.Ok)
            .Where(r => EngineCatalog.UnavailableReason(r.Profile, settings) == null && ConfigValidator.Validate(r.Profile).Count == 0
                && (r.Profile.Engine != EngineKind.Xray || ConfigValidator.PlaintextRejectedByXray(r.Profile) == null))
            .OrderBy(r => r.Result.Ms)
            .Select(r => r.Profile)
            .FirstOrDefault();
}
