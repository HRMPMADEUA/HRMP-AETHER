using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace TIREXNET.Core.Parsing;

public sealed record MhrvData(string Name, string Config);

public static class MhrvProfile
{
    public static bool TryDecode(string uri, out MhrvData? data, out string error)
    {
        data = null;
        error = "";
        try
        {
            const string prefix = "mhrv://";
            if (!uri.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            {
                error = "Invalid MHRV profile link.";
                return false;
            }

            var token = uri[prefix.Length..].Trim();
            token = token.Replace('-', '+').Replace('_', '/');
            token = token.PadRight(token.Length + ((4 - token.Length % 4) % 4), '=');

            var json = Encoding.UTF8.GetString(Convert.FromBase64String(token));
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;

            var name = root.TryGetProperty("name", out var n) ? n.GetString() : null;
            var config = root.TryGetProperty("config", out var c) ? c.GetString() : null;

            if (string.IsNullOrWhiteSpace(config))
            {
                error = "MHRV profile has no TOML configuration.";
                return false;
            }

            if (!Regex.IsMatch(config, @"(?m)^\s*\[relay\]\s*$") ||
                !Regex.IsMatch(config, @"(?m)^\s*\[network\]\s*$"))
            {
                error = "MHRV configuration must contain [relay] and [network] sections.";
                return false;
            }

            var mode = Regex.Match(config,
                @"(?ms)^\s*\[relay\]\s*$.*?^\s*mode\s*=\s*""(apps_script|direct|full)""");

            if (!mode.Success)
            {
                error = "MHRV relay mode must be apps_script, direct or full.";
                return false;
            }

            if (mode.Groups[1].Value == "apps_script")
            {
                var relay = Regex.Match(config, @"(?ms)^\s*\[relay\]\s*$.*?(?=^\s*\[|\z)");
                if (!relay.Success ||
                    !Regex.IsMatch(relay.Value, @"(?m)^\s*(script_id|script_ids)\s*=") ||
                    !Regex.IsMatch(relay.Value, @"(?m)^\s*auth_key\s*=\s*""[^""]+"""))
                {
                    error = "MHRV apps_script mode requires script_id/script_ids and auth_key.";
                    return false;
                }
            }

            data = new MhrvData(
                string.IsNullOrWhiteSpace(name) ? "MHRV Profile" : name.Trim(),
                config.Trim() + "\n");
            return true;
        }
        catch (Exception ex) when (
            ex is FormatException or JsonException or InvalidOperationException or ArgumentException)
        {
            error = "Invalid MHRV profile: " + ex.Message;
            return false;
        }
    }

    public static IEnumerable<string> Secrets(string raw)
    {
        if (!TryDecode(raw, out var data, out _) || data == null)
            yield break;

        var auth = Regex.Match(data.Config,
            @"(?m)^\s*auth_key\s*=\s*""([^""]+)""");
        if (auth.Success) yield return auth.Groups[1].Value;

        var ids = Regex.Match(data.Config,
            @"(?m)^\s*script_id\s*=\s*""([^""]+)""");
        if (ids.Success) yield return ids.Groups[1].Value;
    }
}
