using System.Globalization;
using System.Text;

namespace TIREXNET.Core.Parsing;

/// <summary>
/// A SlipNet share link: <c>slipnet://BASE64</c> whose payload is a pipe-delimited record (format version 18 and compatible).
/// Only the fields TIREXNET needs are read; the original text is handed unchanged to the SlipNet runtime.
/// </summary>
public sealed class SlipNetProfile
{
    public string Version { get; private init; } = "";
    public string TunnelType { get; private init; } = "";
    public string Name { get; private init; } = "";
    public string Domain { get; private init; } = "";
    public string Resolvers { get; private init; } = "";
    public string SshHost { get; private init; } = "";
    public string DohUrl { get; private init; } = "";
    public string VlessCdnIp { get; private init; } = "";
    public bool Encrypted { get; private init; }
    internal string[] Fields { get; private init; } = Array.Empty<string>();

    /// <summary>Tunnel types the SlipNet command-line runtime (the only SlipNet runtime that exists for Windows) can run.</summary>
    private static readonly HashSet<string> Supported = new(StringComparer.OrdinalIgnoreCase)
    {
        "", "dnstt", "dnstt_ssh", "sayedns", "sayedns_ssh", "vaydns", "vaydns_ssh", "ssh", "direct_ssh", "socks5", "direct_socks", "vless", "doh",
    };

    public static bool IsSupportedTunnel(string type) => Supported.Contains(type ?? "");

    public static bool LooksLikeSlipNet(string text) =>
        text.TrimStart().StartsWith("slipnet://", StringComparison.OrdinalIgnoreCase) || text.TrimStart().StartsWith("slipnet-enc://", StringComparison.OrdinalIgnoreCase);

    public static bool TryParse(string raw, out SlipNetProfile? profile, out string error)
    {
        profile = null;
        error = "";
        var text = (raw ?? "").Trim();
        var encrypted = text.StartsWith("slipnet-enc://", StringComparison.OrdinalIgnoreCase);
        if (!encrypted && !text.StartsWith("slipnet://", StringComparison.OrdinalIgnoreCase))
        {
            error = "Not a SlipNet link (expected slipnet://…).";
            return false;
        }
        if (encrypted)
        {
            profile = new SlipNetProfile { Encrypted = true, Name = "SlipNet (encrypted)" };
            return true;
        }
        var payload = string.Concat(text["slipnet://".Length..].Where(c => !char.IsWhiteSpace(c)));
        string decoded;
        try
        {
            while (payload.Length % 4 != 0) payload += "=";
            decoded = Encoding.UTF8.GetString(Convert.FromBase64String(payload));
        }
        catch (FormatException)
        {
            error = "The SlipNet link is not valid base64.";
            return false;
        }
        var f = decoded.Split('|');
        if (f.Length < 12)
        {
            error = $"The SlipNet link has too few fields ({f.Length}, need at least 12).";
            return false;
        }
        string At(int i) => i < f.Length ? f[i] : "";
        profile = new SlipNetProfile
        {
            Version = f[0],
            TunnelType = f[1],
            Name = string.IsNullOrWhiteSpace(f[2]) ? "SlipNet" : f[2],
            Domain = f[3],
            Resolvers = f[4],
            SshHost = At(19),
            DohUrl = At(21),
            VlessCdnIp = At(64),
            Fields = f,
        };
        return true;
    }

    public static string? UnsupportedReason(SlipNetProfile p)
    {
        if (p.Encrypted)
            return "This is an encrypted slipnet-enc:// profile. The Windows SlipNet runtime is built without the private decryption key, so it cannot open it. Ask for a plain slipnet:// link.";
        if (!IsSupportedTunnel(p.TunnelType))
            return $"The SlipNet tunnel type \"{p.TunnelType}\" is not available in the upstream SlipNet command-line runtime for Windows " +
                   "(supported: DNSTT, NoizDNS, VayDNS, SSH, SOCKS5, VLESS, DoH). Slipstream, Snowflake and NaiveProxy profiles only run in the Android app.";
        return null;
    }

    public static List<string> Validate(string raw)
    {
        var errors = new List<string>();
        if (!TryParse(raw, out var p, out var err) || p == null) { errors.Add(err); return errors; }
        var why = UnsupportedReason(p);
        if (why != null) errors.Add(why);
        return errors;
    }

    /// <summary>Hosts the runtime connects to directly (kept outside the tunnel in VPN mode).</summary>
    public static List<string> ServerHosts(string raw)
    {
        var hosts = new List<string>();
        if (!TryParse(raw, out var p, out _) || p == null) return hosts;
        foreach (var r in p.Resolvers.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var host = r.Split(':')[0];
            if (host.Length > 0) hosts.Add(host);
        }
        if (!string.IsNullOrWhiteSpace(p.SshHost)) hosts.Add(p.SshHost.Trim());
        if (Uri.TryCreate(p.DohUrl, UriKind.Absolute, out var u)) hosts.Add(u.Host);
        if (!string.IsNullOrWhiteSpace(p.VlessCdnIp)) hosts.Add(p.VlessCdnIp.Trim());
        if (hosts.Count == 0 && NetUtil.IsValidHostName(p.Domain)) hosts.Add(p.Domain);
        return hosts.Distinct(StringComparer.OrdinalIgnoreCase).ToList();
    }

    /// <summary>Secrets inside the record (SOCKS/SSH passwords, VLESS id) plus the whole link.</summary>
    public static IEnumerable<string> Secrets(string raw)
    {
        yield return raw.Trim();
        if (!TryParse(raw, out var p, out _) || p == null) yield break;
        foreach (var i in new[] { 13, 16, 62 })
            if (i < p.Fields.Length && p.Fields[i].Length >= 4) yield return p.Fields[i];
    }
}
