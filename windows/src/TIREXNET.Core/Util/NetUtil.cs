using System.Net;
using System.Net.Sockets;

namespace TIREXNET.Core;

public static class NetUtil
{
    public static bool TryParseIp(string? s, out IPAddress ip)
    {
        ip = IPAddress.None;
        if (string.IsNullOrWhiteSpace(s)) return false;
        s = s.Trim();
        if (s.StartsWith('[') && s.EndsWith(']')) s = s[1..^1];
        // Reject forms like "1" or "1.2" that IPAddress would accept.
        if (!s.Contains(':') && s.Count(c => c == '.') != 3) return false;
        return IPAddress.TryParse(s, out ip!);
    }

    /// <summary>Accepts "10.0.0.0/8" or "10.0.0.1". Prefix is 32 when omitted.</summary>
    public static bool TryParseCidrV4(string? s, out uint network, out int prefix)
    {
        network = 0; prefix = 32;
        if (string.IsNullOrWhiteSpace(s)) return false;
        var parts = s.Trim().Split('/');
        if (parts.Length > 2) return false;
        if (!TryParseIp(parts[0], out var ip) || ip.AddressFamily != AddressFamily.InterNetwork) return false;
        if (parts.Length == 2 && (!int.TryParse(parts[1], out prefix) || prefix < 0 || prefix > 32)) return false;
        var b = ip.GetAddressBytes();
        var value = ((uint)b[0] << 24) | ((uint)b[1] << 16) | ((uint)b[2] << 8) | b[3];
        var mask = PrefixToMask(prefix);
        network = value & mask;
        return true;
    }

    public static uint PrefixToMask(int prefix) => prefix == 0 ? 0u : uint.MaxValue << (32 - prefix);

    public static string ToDotted(uint value) => $"{(value >> 24) & 255}.{(value >> 16) & 255}.{(value >> 8) & 255}.{value & 255}";

    /// <summary>Validates a CIDR / address for either family (used for WireGuard Address and AllowedIPs).</summary>
    public static bool IsValidCidrAny(string? s)
    {
        if (string.IsNullOrWhiteSpace(s)) return false;
        var parts = s.Trim().Split('/');
        if (parts.Length > 2) return false;
        if (!TryParseIp(parts[0], out var ip)) return false;
        if (parts.Length == 1) return true;
        var max = ip.AddressFamily == AddressFamily.InterNetworkV6 ? 128 : 32;
        return int.TryParse(parts[1], out var p) && p >= 0 && p <= max;
    }

    public static bool IsValidHostName(string? host)
    {
        if (string.IsNullOrWhiteSpace(host) || host.Length > 253) return false;
        if (TryParseIp(host, out _)) return true;
        foreach (var label in host.Split('.'))
        {
            if (label.Length == 0 || label.Length > 63) return false;
            foreach (var c in label)
            {
                if (!(char.IsAsciiLetterOrDigit(c) || c == '-' || c == '_')) return false;
            }
        }
        return true;
    }

    public static bool IsPortFree(int port)
    {
        try
        {
            var l = new TcpListener(IPAddress.Loopback, port);
            l.Start();
            l.Stop();
            return true;
        }
        catch (SocketException)
        {
            return false;
        }
    }

    public static int FindFreePort()
    {
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        var port = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        return port;
    }

    public static async Task<bool> WaitForPortAsync(int port, TimeSpan timeout, Func<bool> stillAlive, CancellationToken ct)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            ct.ThrowIfCancellationRequested();
            if (!stillAlive()) return false;
            try
            {
                using var c = new TcpClient();
                var connect = c.ConnectAsync(IPAddress.Loopback, port);
                if (await Task.WhenAny(connect, Task.Delay(400, ct)) == connect && c.Connected) return true;
            }
            catch (SocketException)
            {
                // not listening yet
            }
            await Task.Delay(150, ct);
        }
        return false;
    }
}
