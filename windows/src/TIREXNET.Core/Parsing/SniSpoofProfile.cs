using System.Net;
using System.Text;
using System.Text.Json;
using TIREXNET.Core.Models;

namespace TIREXNET.Core.Parsing;

public sealed record SniSpoofData(
    string Name,
    string BaseProfile,
    string ConnectIp,
    int ConnectPort,
    string FakeSni,
    int ListenPort);

public static class SniSpoofProfile
{
    public static bool TryDecode(string raw, out SniSpoofData? data, out string error)
    {
        data = null;
        error = "";
        try
        {
            const string prefix = "snispoof://";
            if (!raw.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
                throw new FormatException("Not a SNISPF profile.");

            var token = raw[prefix.Length..].Trim();
            if (token.Length == 0 || token.Length > 160000)
                throw new FormatException("SNISPF profile data is empty or too large.");

            token = token.Replace('-', '+').Replace('_', '/');
            token = token.PadRight(token.Length + ((4 - token.Length % 4) % 4), '=');
            var json = Encoding.UTF8.GetString(Convert.FromBase64String(token));
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            string GetString(string key, string fallback = "") =>
                root.TryGetProperty(key, out var value) && value.ValueKind == JsonValueKind.String
                    ? value.GetString() ?? fallback : fallback;

            int GetInt(string key, int fallback) =>
                root.TryGetProperty(key, out var value) && value.TryGetInt32(out var n) ? n : fallback;

            var name = GetString("name", "Patterniha SNI").Trim();
            var baseProfile = GetString("base_profile").Trim();
            var ip = GetString("connect_ip").Trim().Trim('[', ']');
            var port = GetInt("connect_port", 443);
            var sni = GetString("fake_sni").Trim().TrimEnd('.').ToLowerInvariant();
            var listenPort = GetInt("listen_port", 40443);

            if (baseProfile.Length == 0 || baseProfile.Length > 150000)
                throw new FormatException("SNISPF base Xray profile is missing or too large.");
            if (!IPAddress.TryParse(ip, out var address) ||
                address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork)
                throw new FormatException("SNISPF connect_ip must be a valid IPv4 address.");
            if (port is < 1 or > 65535 || listenPort is < 1024 or > 65535)
                throw new FormatException("SNISPF port is outside the valid range.");
            if (sni.Length is < 1 or > 253 ||
                sni.Split('.').Any(label => label.Length is < 1 or > 63 ||
                    !System.Text.RegularExpressions.Regex.IsMatch(
                        label, @"(?i)^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")))
                throw new FormatException("SNISPF fake_sni is not a valid hostname.");

            data = new SniSpoofData(
                string.IsNullOrWhiteSpace(name) ? "Patterniha SNI" : name[..Math.Min(80, name.Length)],
                baseProfile, ip, port, sni, listenPort);
            return true;
        }
        catch (Exception ex) when (ex is FormatException or JsonException or ArgumentException)
        {
            error = "Invalid SNISPF profile: " + ex.Message;
            return false;
        }
    }

    public static bool TryGetBaseProfile(SniSpoofData data, out Profile? profile, out string error)
    {
        profile = null;
        var parsed = ProfileParser.ParseText(data.BaseProfile);
        if (parsed.Profiles.Count != 1 || parsed.Profiles[0].Engine != EngineKind.Xray)
        {
            error = "SNISPF requires exactly one valid Xray base profile.";
            if (parsed.Errors.Count > 0) error += " " + parsed.Errors[0];
            return false;
        }
        profile = parsed.Profiles[0];
        error = "";
        return true;
    }
}
