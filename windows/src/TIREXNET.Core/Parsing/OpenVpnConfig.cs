using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;
using TIREXNET.Core.Models;

namespace TIREXNET.Core.Parsing;

/// <summary>Reads, validates and prepares .ovpn profiles. A profile is a single self-contained text (all keys inline).</summary>
public static class OpenVpnConfig
{
    public sealed record Remote(string Host, int Port, string Proto);

    private sealed class Parsed
    {
        public List<(string Name, string[] Args, int Line)> Directives { get; } = new();
        public Dictionary<string, string> Blocks { get; } = new(StringComparer.OrdinalIgnoreCase);
        public string? Error { get; set; }
    }

    /// <summary>Directives that run programs, load plugins, write files or take over the management channel. Never allowed.</summary>
    private static readonly HashSet<string> Forbidden = new(StringComparer.OrdinalIgnoreCase)
    {
        "up", "down", "down-pre", "up-restart", "route-up", "route-pre-down", "tls-verify", "ipchange", "client-connect",
        "client-disconnect", "learn-address", "auth-user-pass-verify", "plugin", "script-security", "daemon", "service",
        "log", "log-append", "syslog", "writepid", "status", "status-version", "cd", "chroot", "setcon", "config",
        "management", "management-client", "management-query-passwords", "management-hold", "management-signal",
        "management-forget-disconnect", "management-log-cache", "management-up-down", "management-client-auth",
        "management-client-pf", "management-client-user", "management-client-group", "management-external-key",
        "management-external-cert", "management-query-proxy", "management-query-remote", "management-client-pf",
        "askpass", "tmp-dir", "genkey", "show-adapters", "show-net", "show-net-up", "show-valid-subnets",
    };

    /// <summary>Options TIREXNET sets itself. They are removed from the profile instead of rejecting it.</summary>
    private static readonly HashSet<string> Managed = new(StringComparer.OrdinalIgnoreCase)
    {
        "windows-driver", "disable-dco", "dev-node", "ifconfig-noexec", "route-noexec", "verb", "mute",
    };

    /// <summary>Directives whose value is a file name. Only the inline &lt;block&gt; form is supported.</summary>
    private static readonly HashSet<string> FileOptions = new(StringComparer.OrdinalIgnoreCase)
    {
        "ca", "cert", "key", "dh", "tls-auth", "tls-crypt", "tls-crypt-v2", "secret", "crl-verify", "extra-certs", "pkcs12",
        "capath", "http-proxy-user-pass", "http-proxy-option-file",
    };

    private static readonly Regex ProtoRx = new("^(udp|tcp)(4|6)?(-client)?$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    public static bool LooksLikeOpenVpn(string text)
    {
        return Regex.IsMatch(text, @"(?im)^\s*remote\s+\S+")
            && (Regex.IsMatch(text, @"(?im)^\s*(client|dev\s+tun\d*|proto\s+(udp|tcp))\b") || text.Contains("<ca>", StringComparison.OrdinalIgnoreCase));
    }

    private static Parsed Parse(string text)
    {
        var result = new Parsed();
        string? block = null;
        var sb = new StringBuilder();
        var lineNo = 0;
        foreach (var rawLine in text.Replace("\r", "").Split('\n'))
        {
            lineNo++;
            var line = rawLine.Trim().TrimStart('﻿');
            if (block != null)
            {
                if (line.Equals("</" + block + ">", StringComparison.OrdinalIgnoreCase))
                {
                    result.Blocks[block] = sb.ToString();
                    block = null;
                    sb.Clear();
                }
                else sb.Append(rawLine.TrimEnd()).Append('\n');
                continue;
            }
            if (line.Length == 0 || line.StartsWith('#') || line.StartsWith(';')) continue;
            if (line.StartsWith('<') && line.EndsWith('>') && !line.StartsWith("</"))
            {
                block = line.Trim('<', '>').Trim();
                sb.Clear();
                continue;
            }
            var tokens = Tokenize(line);
            if (tokens.Count == 0) continue;
            var name = tokens[0].TrimStart('-');
            result.Directives.Add((name, tokens.Skip(1).ToArray(), lineNo));
        }
        if (block != null) result.Error = $"The <{block}> block is not closed.";
        return result;
    }

    private static List<string> Tokenize(string line)
    {
        var tokens = new List<string>();
        var cur = new StringBuilder();
        var inQuote = false;
        var has = false;
        foreach (var c in line)
        {
            if (c == '"') { inQuote = !inQuote; has = true; continue; }
            if (!inQuote && (c == '#' || c == ';') && !has && cur.Length == 0) break;
            if (!inQuote && char.IsWhiteSpace(c))
            {
                if (has || cur.Length > 0) { tokens.Add(cur.ToString()); cur.Clear(); has = false; }
                continue;
            }
            cur.Append(c);
            has = true;
        }
        if (has || cur.Length > 0) tokens.Add(cur.ToString());
        return tokens;
    }

    public static List<Remote> Remotes(string raw)
    {
        var p = Parse(raw);
        var defaultProto = "udp";
        var defaultPort = 1194;
        foreach (var (name, args, _) in p.Directives)
        {
            if (name.Equals("proto", StringComparison.OrdinalIgnoreCase) && args.Length > 0) defaultProto = args[0];
            if (name.Equals("port", StringComparison.OrdinalIgnoreCase) && args.Length > 0 && int.TryParse(args[0], NumberStyles.None, CultureInfo.InvariantCulture, out var dp)) defaultPort = dp;
        }
        var list = new List<Remote>();
        foreach (var (name, args, _) in p.Directives)
        {
            if (!name.Equals("remote", StringComparison.OrdinalIgnoreCase) || args.Length == 0) continue;
            var port = defaultPort;
            if (args.Length > 1 && int.TryParse(args[1], NumberStyles.None, CultureInfo.InvariantCulture, out var rp)) port = rp;
            var proto = args.Length > 2 ? args[2] : defaultProto;
            list.Add(new Remote(args[0], port, proto));
        }
        return list;
    }

    public static bool TryGetFirstRemote(string raw, out string host, out int port)
    {
        var r = Remotes(raw).FirstOrDefault();
        host = r?.Host ?? "";
        port = r?.Port ?? 0;
        return r != null;
    }

    /// <summary>Returns human-readable problems. Empty means the profile can be started.</summary>
    public static List<string> Validate(string raw)
    {
        var errors = new List<string>();
        if (string.IsNullOrWhiteSpace(raw)) { errors.Add("The profile has no configuration data."); return errors; }
        var p = Parse(raw);
        if (p.Error != null) { errors.Add(p.Error); return errors; }

        var names = new HashSet<string>(p.Directives.Select(d => d.Name), StringComparer.OrdinalIgnoreCase);

        foreach (var (name, args, line) in p.Directives)
        {
            if (Forbidden.Contains(name))
            {
                errors.Add($"Line {line}: \"{name}\" is not allowed (it runs programs, writes files or is managed by TIREXNET itself).");
                continue;
            }
            if (FileOptions.Contains(name) && args.Length > 0)
                errors.Add($"Line {line}: \"{name}\" refers to the external file \"{args[0]}\". Put its contents inside a <{name}> block in the profile.");
            if (name.Equals("auth-user-pass", StringComparison.OrdinalIgnoreCase) && args.Length > 0)
                errors.Add($"Line {line}: \"auth-user-pass\" must not point to a file. Use an <auth-user-pass> block (username on the first line, password on the second).");
            if (name.Equals("proto", StringComparison.OrdinalIgnoreCase) && (args.Length == 0 || !ProtoRx.IsMatch(args[0])))
                errors.Add($"Line {line}: unsupported protocol \"{(args.Length > 0 ? args[0] : "")}\" (use udp or tcp).");
            if (name.Equals("dev", StringComparison.OrdinalIgnoreCase) && args.Length > 0 && !args[0].StartsWith("tun", StringComparison.OrdinalIgnoreCase))
                errors.Add($"Line {line}: only routed tunnels (dev tun) are supported, not \"{args[0]}\".");
        }
        foreach (var blockName in p.Blocks.Keys)
        {
            if (Forbidden.Contains(blockName)) errors.Add($"The <{blockName}> block is not allowed.");
        }

        var remotes = Remotes(raw);
        if (remotes.Count == 0) errors.Add("The profile has no \"remote\" server line.");
        foreach (var r in remotes)
        {
            if (!NetUtil.IsValidHostName(r.Host)) errors.Add($"Invalid server address in \"remote\": {r.Host}");
            if (r.Port is < 1 or > 65535) errors.Add($"Invalid server port in \"remote\": {r.Port}");
            if (!ProtoRx.IsMatch(r.Proto)) errors.Add($"Unsupported protocol in \"remote\": {r.Proto}");
        }

        if (!names.Contains("client") && !names.Contains("tls-client") && !names.Contains("pull"))
            errors.Add("This is not a client profile (the \"client\" directive is missing).");

        var hasCa = p.Blocks.ContainsKey("ca") || p.Blocks.ContainsKey("pkcs12") || names.Contains("peer-fingerprint") || p.Blocks.ContainsKey("secret");
        if (!hasCa) errors.Add("The profile has no inline <ca> certificate (or peer-fingerprint), so the server cannot be verified.");

        if (names.Contains("auth-user-pass") || p.Blocks.ContainsKey("auth-user-pass"))
        {
            if (!TryGetCredentials(p, out _, out _))
                errors.Add("The profile asks for a username and password. Add an <auth-user-pass> block with the username on the first line and the password on the second.");
        }
        return errors;
    }

    private static bool TryGetCredentials(Parsed p, out string user, out string pass)
    {
        user = pass = "";
        if (!p.Blocks.TryGetValue("auth-user-pass", out var body)) return false;
        var lines = body.Split('\n', StringSplitOptions.RemoveEmptyEntries).Select(l => l.Trim()).Where(l => l.Length > 0).ToArray();
        if (lines.Length < 2) return false;
        user = lines[0];
        pass = lines[1];
        return true;
    }

    /// <summary>Username and password from an inline &lt;auth-user-pass&gt; block, or null.</summary>
    public static (string User, string Pass)? ExtractCredentials(string raw)
    {
        var p = Parse(raw);
        return TryGetCredentials(p, out var u, out var pw) ? (u, pw) : null;
    }

    /// <summary>Values that must never reach logs: credentials and every line of the inline private-key style blocks.</summary>
    public static IEnumerable<string> Secrets(string raw)
    {
        var p = Parse(raw);
        if (TryGetCredentials(p, out var u, out var pw))
        {
            yield return u;
            yield return pw;
        }
        foreach (var name in new[] { "key", "tls-auth", "tls-crypt", "tls-crypt-v2", "secret", "pkcs12" })
        {
            if (!p.Blocks.TryGetValue(name, out var body)) continue;
            foreach (var l in body.Split('\n', StringSplitOptions.RemoveEmptyEntries))
            {
                var t = l.Trim();
                if (t.Length >= 16 && !t.StartsWith("-----", StringComparison.Ordinal)) yield return t;
            }
        }
    }

    /// <summary>
    /// The configuration actually handed to openvpn.exe: the profile (credentials block removed, they travel over the
    /// management channel) plus options owned by TIREXNET. The profile has been validated before.
    /// </summary>
    public static string BuildRuntimeConfig(string raw, AppSettings settings, int managementPort)
    {
        var p = Parse(raw);
        var sb = new StringBuilder();
        var skipBlock = (string?)null;
        foreach (var rawLine in raw.Replace("\r", "").Split('\n'))
        {
            var t = rawLine.Trim();
            if (skipBlock != null)
            {
                if (t.Equals("</" + skipBlock + ">", StringComparison.OrdinalIgnoreCase)) skipBlock = null;
                continue;
            }
            if (t.Equals("<auth-user-pass>", StringComparison.OrdinalIgnoreCase)) { skipBlock = "auth-user-pass"; continue; }
            var first = Tokenize(t).FirstOrDefault()?.TrimStart('-') ?? "";
            if (Managed.Contains(first)) continue;
            // A bare "auth-user-pass" stays: OpenVPN then asks over the management channel, where we answer.
            sb.Append(rawLine.TrimEnd()).Append('\n');
        }

        var names = new HashSet<string>(p.Directives.Select(d => d.Name), StringComparer.OrdinalIgnoreCase);
        var hasAuth = p.Blocks.ContainsKey("auth-user-pass") || names.Contains("auth-user-pass");
        sb.Append("\n# --- options managed by TIREXNET ---\n");
        sb.Append("windows-driver wintun\n");
        sb.Append("management 127.0.0.1 ").Append(managementPort.ToString(CultureInfo.InvariantCulture)).Append(" stdin\n");
        sb.Append("management-hold\n");
        if (hasAuth)
        {
            sb.Append("management-query-passwords\n");
            sb.Append("auth-nocache\n");
            if (!names.Contains("auth-user-pass")) sb.Append("auth-user-pass\n");
        }
        sb.Append("verb 3\n");
        if (settings.Mtu > 0) sb.Append("tun-mtu ").Append(settings.Mtu.ToString(CultureInfo.InvariantCulture)).Append('\n');
        foreach (var d in ConfigValidator.SplitList(settings.Dns))
            if (NetUtil.TryParseIp(d, out _)) sb.Append("dhcp-option DNS ").Append(d).Append('\n');
        if (names.Contains("redirect-gateway") && ConfigValidator.SplitList(settings.Dns).Count > 0) sb.Append("block-outside-dns\n");
        foreach (var b in ConfigValidator.SplitList(settings.BypassNetworks))
        {
            if (NetUtil.TryParseCidrV4(b, out var net, out var prefix))
                sb.Append("route ").Append(NetUtil.ToDotted(net)).Append(' ').Append(NetUtil.ToDotted(NetUtil.PrefixToMask(prefix))).Append(" net_gateway\n");
        }
        return sb.ToString();
    }
}
