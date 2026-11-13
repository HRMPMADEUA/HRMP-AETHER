using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using TIREXNET.Core.Models;

namespace TIREXNET.Core.Parsing;

public sealed record DnsTunnelData(
    string Name, string Config, string Resolvers, int Port,
    string Executable, string WorkingDirectory, string Host);

public static class DnsTunnelProfile
{
    public static bool IsDnsScheme(string raw) =>
        raw.TrimStart().StartsWith("masterdns://", StringComparison.OrdinalIgnoreCase) ||
        raw.TrimStart().StartsWith("cottendns://", StringComparison.OrdinalIgnoreCase) ||
        raw.TrimStart().StartsWith("stormdns://", StringComparison.OrdinalIgnoreCase);

    public static bool TryCreate(string raw, out Profile? profile, out string error)
    {
        profile = null;
        if (!TryDecode(raw, out var data, out error) || data == null) return false;

        var engine = raw.TrimStart().StartsWith("masterdns://", StringComparison.OrdinalIgnoreCase)
            ? EngineKind.MasterDns
            : raw.TrimStart().StartsWith("cottendns://", StringComparison.OrdinalIgnoreCase)
                ? EngineKind.CottenDns : EngineKind.StormDns;

        profile = new Profile
        {
            Name = data.Name,
            Engine = engine,
            Protocol = engine switch
            {
                EngineKind.MasterDns => "masterdns",
                EngineKind.CottenDns => "cottendns",
                _ => "stormdns"
            },
            Server = data.Host,
            Raw = raw.Trim()
        };
        return true;
    }

    public static bool TryDecode(string raw, out DnsTunnelData? data, out string error)
    {
        data = null;
        error = "Invalid DNS tunnel profile.";
        try
        {
            raw = raw.Trim();
            string scheme;
            string exe, dir;
            if (raw.StartsWith("masterdns://", StringComparison.OrdinalIgnoreCase))
            {
                scheme = "masterdns://";
                exe = TIREXNET.Core.AppPaths.MasterDnsExe;
                dir = TIREXNET.Core.AppPaths.MasterDnsDir;
            }
            else if (raw.StartsWith("cottendns://", StringComparison.OrdinalIgnoreCase))
            {
                scheme = "cottendns://";
                exe = TIREXNET.Core.AppPaths.CottenDnsExe;
                dir = TIREXNET.Core.AppPaths.CottenDnsDir;
            }
            else if (raw.StartsWith("stormdns://", StringComparison.OrdinalIgnoreCase))
            {
                scheme = "stormdns://";
                exe = TIREXNET.Core.AppPaths.StormDnsExe;
                dir = TIREXNET.Core.AppPaths.StormDnsDir;
            }
            else return false;

            var token = raw[scheme.Length..].Trim();
            if (token.Length == 0 || token.Length > 160000) return false;
            if (!ProfileParser.TryDecodeBase64(token, out var jsonText)) return false;

            using var doc = JsonDocument.Parse(jsonText);
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return false;

            string Get(string key, string fallback = "") =>
                root.TryGetProperty(key, out var v) && v.ValueKind == JsonValueKind.String
                    ? v.GetString() ?? fallback : fallback;
            int Number(string key, int fallback) =>
                root.TryGetProperty(key, out var v) && v.TryGetInt32(out var n) ? n : fallback;

            var name = Get("name", "DNS Tunnel").Trim();
            if (name.Length == 0) name = "DNS Tunnel";
            name = name[..Math.Min(name.Length, 80)];

            string config;
            string resolvers;
            int port;

            if (scheme == "masterdns://")
            {
                var domains = Split(Get("domains"));
                resolvers = CleanResolvers(Get("resolvers"));
                int method = Number("encryption_method", -1);
                int workers = Number("workers", 4);
                int mtu = Number("mtu", 1280);
                port = Number("listen_port", 18000);
                string key = Get("encryption_key").Trim();
                string log = Get("log_level", "WARN").ToUpperInvariant();

                if (domains.Count == 0 || domains.Count > 32 ||
                    domains.Any(x => !ValidDomain(x))) return Fail("MasterDNS tunnel domains are invalid.", out error);
                if (method is < 0 or > 5 || (method != 0 && key.Length == 0) ||
                    key.Length > 1024 || key.Contains('\n'))
                    return Fail("MasterDNS encryption settings are invalid.", out error);
                if (resolvers.Length == 0 || workers is < 1 or > 32 ||
                    mtu is < 576 or > 9000 || port is < 1024 or > 65535)
                    return Fail("MasterDNS resolver, worker, MTU or port settings are invalid.", out error);
                if (log is not ("ERROR" or "WARN" or "INFO" or "DEBUG")) log = "WARN";

                config =
                    "DOMAINS = [" + string.Join(", ", domains.Select(Toml)) + "]\n" +
                    "DATA_ENCRYPTION_METHOD = " + method + "\n" +
                    "ENCRYPTION_KEY = " + Toml(key) + "\n" +
                    "PROTOCOL_TYPE = \"SOCKS5\"\n" +
                    "LISTEN_IP = \"127.0.0.1\"\n" +
                    "LISTEN_PORT = " + port + "\n" +
                    "SOCKS5_AUTH = false\n" +
                    "RX_TX_WORKERS = " + workers + "\n" +
                    "TUNNEL_PROCESS_WORKERS = " + workers + "\n" +
                    "MIN_UPLOAD_MTU = " + mtu + "\nMAX_UPLOAD_MTU = " + mtu + "\n" +
                    "MIN_DOWNLOAD_MTU = " + mtu + "\nMAX_DOWNLOAD_MTU = " + mtu + "\n" +
                    "LOG_LEVEL = \"" + log + "\"\n";
            }
            else
            {
                config = Get("client_config").Trim();
                resolvers = CleanResolvers(Get("resolvers"));
                if (config.Length == 0 || resolvers.Length == 0 ||
                    !Regex.IsMatch(config, @"(?im)^\s*DOMAINS\s*=") ||
                    !Regex.IsMatch(config, @"(?im)^\s*ENCRYPTION_KEY\s*=") ||
                    !Regex.IsMatch(config, @"(?im)^\s*DATA_ENCRYPTION_METHOD\s*="))
                    return Fail("DNS tunnel config or resolver list is incomplete.", out error);

                var match = Regex.Match(config, @"(?im)^\s*LISTEN_PORT\s*=\s*(\d+)");
                port = match.Success && int.TryParse(match.Groups[1].Value, out var parsed) ? parsed : 18000;
                if (port is < 1024 or > 65535) return Fail("DNS tunnel SOCKS port is invalid.", out error);

                config = SetToml(config, "LISTEN_IP", "\"127.0.0.1\"");
                config = SetToml(config, "PROTOCOL_TYPE", "\"SOCKS5\"");
                config = SetToml(config, "STARTUP_MODE", "\"resolvers\"");
            }

            var host = ResolverHost(resolvers.Split('\n')[0]);
            data = new DnsTunnelData(name, config, resolvers, port, exe, dir, host);
            error = "";
            return true;
        }
        catch (Exception ex) when (ex is JsonException or FormatException or ArgumentException)
        {
            error = "DNS tunnel profile could not be decoded: " + ex.Message;
            return false;
        }
    }

    private static bool Fail(string message, out string error)
    {
        error = message;
        return false;
    }

    private static List<string> Split(string value) =>
        value.Split(new[] { '\r', '\n', ',', ';' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Where(x => !x.StartsWith("#")).Distinct(StringComparer.OrdinalIgnoreCase).ToList();

    private static string CleanResolvers(string value)
    {
        var lines = Split(value);
        if (lines.Count == 0 || lines.Count > 100 ||
            lines.Any(x => x.Length > 160 || x.Any(char.IsWhiteSpace)))
            return "";
        return string.Join("\n", lines);
    }

    private static bool ValidDomain(string value) =>
        value.Length is > 0 and <= 253 &&
        !value.Contains('/') && !value.Contains(':') &&
        value.Split('.').All(p => p.Length is > 0 and <= 63 &&
            p[0] != '-' && p[^1] != '-' &&
            p.All(c => char.IsLetterOrDigit(c) || c == '-'));

    private static string ResolverHost(string value)
    {
        value = value.Trim();
        if (value.StartsWith("[")) return value.TrimStart('[').Split(']')[0];
        if (IPAddress.TryParse(value, out _)) return value;
        var slash = value.IndexOf('/');
        if (slash >= 0) value = value[..slash];
        if (value.Count(c => c == ':') > 1) return value;
        return value.Split(':')[0];
    }

    private static string Toml(string value) =>
        "\"" + value.Replace("\\", "\\\\").Replace("\"", "\\\"")
            .Replace("\r", "").Replace("\n", "\\n") + "\"";

    private static string SetToml(string config, string key, string value)
    {
        var rx = new Regex(@"(?im)^\s*" + Regex.Escape(key) + @"\s*=.*$");
        var line = key + " = " + value;
        return rx.IsMatch(config) ? rx.Replace(config, line, 1) : config + "\n" + line + "\n";
    }
}
