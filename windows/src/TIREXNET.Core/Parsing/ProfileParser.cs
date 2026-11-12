using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using TIREXNET.Core.Models;

namespace TIREXNET.Core.Parsing;

public sealed class ParseResult
{
    public List<Profile> Profiles { get; } = new();
    public List<string> Errors { get; } = new();
}

public static class ProfileParser
{
    private static readonly string[] UriSchemes = { "vless://", "vmess://", "trojan://", "ss://" };

    /// <summary>Parses pasted text, a subscription body (plain or base64), an Xray JSON document or a WireGuard .conf.</summary>
    public static ParseResult ParseText(string? text, string? fileName = null)
    {
        var result = new ParseResult();
        if (string.IsNullOrWhiteSpace(text))
        {
            result.Errors.Add("Nothing to import: the input is empty.");
            return result;
        }
        text = text.Trim().TrimStart('﻿');
        var stem = string.IsNullOrEmpty(fileName) ? null : Path.GetFileNameWithoutExtension(fileName);

        if (WgConfig.LooksLikeWireGuard(text))
        {
            if (TryParseWireGuard(text, stem, out var wg, out var err)) result.Profiles.Add(wg!);
            else result.Errors.Add(err);
            return result;
        }

        if (OpenVpnConfig.LooksLikeOpenVpn(text))
        {
            if (TryParseOpenVpn(text, stem, out var ovpn, out var err)) result.Profiles.Add(ovpn!);
            else result.Errors.Add(err);
            return result;
        }

        if (text.StartsWith('{') || text.StartsWith('['))
        {
            ParseJsonText(text, stem, result);
            return result;
        }

        var lines = SplitLines(text);
        if (lines.Any(SlipNetProfile.LooksLikeSlipNet))
        {
            foreach (var line in lines)
            {
                if (SlipNetProfile.LooksLikeSlipNet(line))
                {
                    if (TryParseSlipNet(line, out var sn, out var snErr)) result.Profiles.Add(sn!);
                    else result.Errors.Add(snErr);
                }
                else if (HasKnownScheme(line))
                {
                    if (TryParseUri(line, out var up, out var upErr)) result.Profiles.Add(up!);
                    else result.Errors.Add(upErr);
                }
                else result.Errors.Add($"Unsupported or unrecognized entry: {Truncate(line, 40)}");
            }
            return result;
        }
        if (lines.Count == 1 && !HasKnownScheme(lines[0]))
        {
            // Possibly a base64 subscription body.
            if (TryDecodeBase64(lines[0], out var decoded) && !string.IsNullOrWhiteSpace(decoded))
            {
                var inner = SplitLines(decoded);
                if (inner.Any(HasKnownScheme)) lines = inner;
            }
        }

        foreach (var line in lines)
        {
            if (!HasKnownScheme(line))
            {
                result.Errors.Add($"Unsupported or unrecognized entry: {Truncate(line, 40)}");
                continue;
            }
            if (TryParseUri(line, out var profile, out var error)) result.Profiles.Add(profile!);
            else result.Errors.Add(error);
        }
        if (result.Profiles.Count == 0 && result.Errors.Count == 0)
            result.Errors.Add("No supported profile found. Supported: vless://, vmess://, trojan://, ss://, slipnet://, Xray JSON, WireGuard/AmneziaWG .conf and OpenVPN .ovpn.");
        return result;
    }

    private static List<string> SplitLines(string text) =>
        text.Replace("\r", "").Split('\n').Select(l => l.Trim()).Where(l => l.Length > 0 && !l.StartsWith('#')).ToList();

    private static bool HasKnownScheme(string line) =>
        UriSchemes.Any(s => line.StartsWith(s, StringComparison.OrdinalIgnoreCase));

    private static string Truncate(string s, int n) => s.Length <= n ? s : s[..n] + "…";

    // ---------------------------------------------------------------- URIs

    public static bool TryParseUri(string uri, out Profile? profile, out string error)
    {
        profile = null;
        if (!TryParseEndpoint(uri, out var ep, out error) || ep == null) return false;
        profile = new Profile
        {
            Name = string.IsNullOrWhiteSpace(ep.Name) ? $"{ep.Protocol.ToUpperInvariant()} {ep.Host}" : ep.Name.Trim(),
            Engine = EngineKind.Xray,
            Protocol = ep.Protocol,
            Server = ep.Host,
            Port = ep.Port,
            Raw = uri.Trim(),
        };
        return true;
    }

    public static bool TryParseEndpoint(string uri, out XrayEndpoint? endpoint, out string error)
    {
        endpoint = null;
        error = "";
        uri = uri.Trim();
        try
        {
            XrayEndpoint? ep;
            if (uri.StartsWith("vless://", StringComparison.OrdinalIgnoreCase)) ep = ParseStandard(uri[8..], "vless", out error);
            else if (uri.StartsWith("trojan://", StringComparison.OrdinalIgnoreCase)) ep = ParseStandard(uri[9..], "trojan", out error);
            else if (uri.StartsWith("vmess://", StringComparison.OrdinalIgnoreCase)) ep = ParseVmess(uri[8..], out error);
            else if (uri.StartsWith("ss://", StringComparison.OrdinalIgnoreCase)) ep = ParseShadowsocks(uri[5..], out error);
            else { error = "Unsupported link type."; return false; }
            if (ep == null) return false;
            endpoint = ep;
            return true;
        }
        catch (Exception ex) when (ex is FormatException or JsonException or ArgumentException or InvalidOperationException)
        {
            error = $"Invalid link: {ex.Message}";
            return false;
        }
    }

    private static void SplitUri(string rest, out string authority, out Dictionary<string, string> query, out string fragment)
    {
        fragment = "";
        var hash = rest.IndexOf('#');
        if (hash >= 0)
        {
            fragment = Uri.UnescapeDataString(rest[(hash + 1)..]);
            rest = rest[..hash];
        }
        query = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var q = rest.IndexOf('?');
        if (q >= 0)
        {
            foreach (var pair in rest[(q + 1)..].Split('&', StringSplitOptions.RemoveEmptyEntries))
            {
                var eq = pair.IndexOf('=');
                var k = eq < 0 ? pair : pair[..eq];
                var v = eq < 0 ? "" : pair[(eq + 1)..];
                query[Uri.UnescapeDataString(k)] = Uri.UnescapeDataString(v);
            }
            rest = rest[..q];
        }
        var slash = rest.IndexOf('/');
        authority = slash >= 0 ? rest[..slash] : rest;
    }

    public static bool TryHostPort(string hostPort, out string host, out int port)
    {
        host = ""; port = 0;
        hostPort = hostPort.Trim();
        if (hostPort.StartsWith('['))
        {
            var end = hostPort.IndexOf(']');
            if (end < 0 || end + 2 > hostPort.Length || hostPort[end + 1] != ':') return false;
            host = hostPort[1..end];
            return int.TryParse(hostPort[(end + 2)..], out port) && port is > 0 and <= 65535;
        }
        var colon = hostPort.LastIndexOf(':');
        if (colon <= 0) return false;
        host = hostPort[..colon];
        return int.TryParse(hostPort[(colon + 1)..], out port) && port is > 0 and <= 65535;
    }

    private static XrayEndpoint? ParseStandard(string rest, string protocol, out string error)
    {
        error = "";
        SplitUri(rest, out var authority, out var q, out var fragment);
        var at = authority.LastIndexOf('@');
        if (at <= 0) { error = $"Invalid {protocol} link: missing user information."; return null; }
        var user = Uri.UnescapeDataString(authority[..at]);
        if (!TryHostPort(authority[(at + 1)..], out var host, out var port))
        {
            error = $"Invalid {protocol} link: bad server address or port.";
            return null;
        }
        var ep = new XrayEndpoint { Protocol = protocol, Host = host, Port = port, Id = user, Name = fragment };
        ApplyTransportQuery(ep, q, protocol == "trojan" ? "tls" : "none");
        if (protocol == "vless")
        {
            ep.Flow = q.GetValueOrDefault("flow", "");
            ep.Encryption = q.GetValueOrDefault("encryption", "none");
            if (string.IsNullOrEmpty(ep.Encryption)) ep.Encryption = "none";
        }
        if (protocol == "vmess")
        {
            ep.VmessSecurity = q.GetValueOrDefault("encryption", "auto");
            if (string.IsNullOrEmpty(ep.VmessSecurity)) ep.VmessSecurity = "auto";
        }
        return FinishTransport(ep, ref error) ? ep : null;
    }

    private static void ApplyTransportQuery(XrayEndpoint ep, Dictionary<string, string> q, string defaultSecurity)
    {
        ep.Network = NormalizeNetwork(q.GetValueOrDefault("type", "tcp"));
        ep.Security = q.GetValueOrDefault("security", defaultSecurity).ToLowerInvariant();
        if (ep.Security == "") ep.Security = defaultSecurity;
        ep.Sni = q.GetValueOrDefault("sni", q.GetValueOrDefault("peer", ""));
        ep.Alpn = q.GetValueOrDefault("alpn", "");
        ep.Fingerprint = q.GetValueOrDefault("fp", "");
        ep.PublicKey = q.GetValueOrDefault("pbk", "");
        ep.ShortId = q.GetValueOrDefault("sid", "");
        ep.SpiderX = q.GetValueOrDefault("spx", "");
        ep.Path = q.GetValueOrDefault("path", "");
        ep.HostHeader = q.GetValueOrDefault("host", "");
        ep.ServiceName = q.GetValueOrDefault("serviceName", "");
        ep.HeaderType = q.GetValueOrDefault("headerType", "none");
        ep.Mode = q.GetValueOrDefault("mode", "");
        ep.Seed = q.GetValueOrDefault("seed", "");
        ep.Extra = q.GetValueOrDefault("extra", "");
        var insecure = q.GetValueOrDefault("allowInsecure", q.GetValueOrDefault("insecure", ""));
        ep.AllowInsecure = insecure == "1" || insecure.Equals("true", StringComparison.OrdinalIgnoreCase);
    }

    public static string NormalizeNetwork(string n)
    {
        n = (n ?? "tcp").Trim().ToLowerInvariant();
        return n switch
        {
            "" or "raw" => "tcp",
            "h2" or "http" => "http",
            "mkcp" => "kcp",
            "splithttp" => "xhttp",
            _ => n,
        };
    }

    private static bool FinishTransport(XrayEndpoint ep, ref string error)
    {
        string[] okNetworks = { "tcp", "ws", "grpc", "http", "httpupgrade", "xhttp", "kcp" };
        if (!okNetworks.Contains(ep.Network))
        {
            error = $"Transport \"{ep.Network}\" is not supported by this version.";
            return false;
        }
        string[] okSecurity = { "none", "tls", "reality" };
        if (!okSecurity.Contains(ep.Security))
        {
            error = $"Security mode \"{ep.Security}\" is not supported.";
            return false;
        }
        return true;
    }

    private static XrayEndpoint? ParseVmess(string rest, out string error)
    {
        error = "";
        if (rest.Contains('@'))
        {
            // vmess://uuid@host:port?... (standard URI form)
            return ParseStandard(rest, "vmess", out error);
        }
        var hash = rest.IndexOf('#');
        var fragment = hash >= 0 ? Uri.UnescapeDataString(rest[(hash + 1)..]) : "";
        if (hash >= 0) rest = rest[..hash];
        if (!TryDecodeBase64(rest.Trim(), out var json) || string.IsNullOrWhiteSpace(json))
        {
            error = "Invalid vmess link: payload is not valid base64.";
            return null;
        }
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        if (root.ValueKind != JsonValueKind.Object) { error = "Invalid vmess link: payload is not a JSON object."; return null; }

        string S(string name) => root.TryGetProperty(name, out var v)
            ? (v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : v.ToString())
            : "";

        if (!int.TryParse(S("port"), out var port) || port is <= 0 or > 65535) { error = "Invalid vmess link: bad port."; return null; }
        var ep = new XrayEndpoint
        {
            Protocol = "vmess",
            Host = S("add").Trim(),
            Port = port,
            Id = S("id").Trim(),
            Name = !string.IsNullOrWhiteSpace(S("ps")) ? S("ps") : fragment,
            VmessSecurity = string.IsNullOrWhiteSpace(S("scy")) ? "auto" : S("scy"),
            Network = NormalizeNetwork(string.IsNullOrWhiteSpace(S("net")) ? "tcp" : S("net")),
            HeaderType = string.IsNullOrWhiteSpace(S("type")) ? "none" : S("type"),
            HostHeader = S("host"),
            Path = S("path"),
            Sni = S("sni"),
            Alpn = S("alpn"),
            Fingerprint = S("fp"),
        };
        _ = int.TryParse(S("aid"), out var aid);
        ep.AlterId = aid;
        var tls = S("tls").ToLowerInvariant();
        ep.Security = tls is "tls" or "reality" ? tls : "none";
        if (ep.Network == "grpc") ep.ServiceName = ep.Path;
        if (ep.Network == "kcp") ep.Seed = ep.Path;
        if (ep.Network == "xhttp")
        {
            // v2rayN style: "type" carries the xhttp mode, "extra" the extra JSON object.
            var mode = S("mode");
            if (string.IsNullOrWhiteSpace(mode) && !string.Equals(ep.HeaderType, "none", StringComparison.OrdinalIgnoreCase)) mode = ep.HeaderType;
            ep.Mode = mode;
            ep.HeaderType = "none";
            ep.Extra = S("extra");
        }
        return FinishTransport(ep, ref error) ? ep : null;
    }

    private static XrayEndpoint? ParseShadowsocks(string rest, out string error)
    {
        error = "";
        SplitUri(rest, out var authority, out var q, out var fragment);
        var core = rest.Split('#')[0].Split('?')[0];
        if (!authority.Contains('@') && core.Length > authority.Length) authority = core;
        if (q.ContainsKey("plugin"))
        {
            error = "Shadowsocks plugins (obfs, v2ray-plugin, …) are not supported.";
            return null;
        }
        string method, password, hostPort;
        var at = authority.LastIndexOf('@');
        if (at > 0)
        {
            var userinfo = authority[..at];
            hostPort = authority[(at + 1)..];
            string cred;
            if (userinfo.Contains(':')) cred = Uri.UnescapeDataString(userinfo);
            else if (!TryDecodeBase64(Uri.UnescapeDataString(userinfo), out cred) || !cred.Contains(':'))
            {
                error = "Invalid ss link: credentials are not valid.";
                return null;
            }
            var c = cred.IndexOf(':');
            method = cred[..c]; password = cred[(c + 1)..];
        }
        else
        {
            if (!TryDecodeBase64(authority, out var all) || !all.Contains('@') || !all.Contains(':'))
            {
                error = "Invalid ss link: payload is not valid.";
                return null;
            }
            var a = all.LastIndexOf('@');
            var cred = all[..a];
            hostPort = all[(a + 1)..];
            var c = cred.IndexOf(':');
            if (c < 0) { error = "Invalid ss link: missing cipher."; return null; }
            method = cred[..c]; password = cred[(c + 1)..];
        }
        if (!TryHostPort(hostPort, out var host, out var port))
        {
            error = "Invalid ss link: bad server address or port.";
            return null;
        }
        return new XrayEndpoint { Protocol = "shadowsocks", Host = host, Port = port, Method = method.Trim().ToLowerInvariant(), Id = password, Name = fragment };
    }

    // ---------------------------------------------------------------- Xray JSON

    private static void ParseJsonText(string text, string? stem, ParseResult result)
    {
        JsonNode? node;
        try { node = JsonNode.Parse(text); }
        catch (JsonException ex)
        {
            result.Errors.Add($"Invalid JSON: {ex.Message}");
            return;
        }
        var items = new List<JsonObject>();
        if (node is JsonObject single) items.Add(single);
        else if (node is JsonArray arr) items.AddRange(arr.OfType<JsonObject>());
        if (items.Count == 0)
        {
            result.Errors.Add("The JSON document does not contain an Xray configuration object.");
            return;
        }
        var index = 0;
        foreach (var obj in items)
        {
            index++;
            if (TryCreateJsonProfile(obj, items.Count > 1 && stem != null ? $"{stem} {index}" : stem, out var p, out var err)) result.Profiles.Add(p!);
            else result.Errors.Add(err);
        }
    }

    public static bool TryCreateJsonProfile(JsonObject obj, string? name, out Profile? profile, out string error)
    {
        profile = null; error = "";
        if (obj["outbounds"] is not JsonArray outs || outs.Count == 0)
        {
            error = "Xray JSON must contain a non-empty \"outbounds\" array.";
            return false;
        }
        var hosts = ExtractJsonHosts(obj, out var firstPort);
        var remarks = obj["remarks"]?.ToString();
        profile = new Profile
        {
            Name = !string.IsNullOrWhiteSpace(remarks) ? remarks! : (!string.IsNullOrWhiteSpace(name) ? name! : (hosts.Count > 0 ? $"Xray {hosts[0]}" : "Xray config")),
            Engine = EngineKind.Xray,
            Protocol = "xray-json",
            Server = hosts.FirstOrDefault() ?? "",
            Port = firstPort,
            Raw = obj.ToJsonString(new JsonSerializerOptions { WriteIndented = false }),
        };
        return true;
    }

    /// <summary>Server addresses of every proxy outbound in an Xray JSON document.</summary>
    public static List<string> ExtractJsonHosts(JsonObject root, out int firstPort)
    {
        firstPort = 0;
        var first = 0;
        var hosts = new List<string>();
        if (root["outbounds"] is not JsonArray outs) return hosts;
        foreach (var o in outs.OfType<JsonObject>())
        {
            var proto = o["protocol"]?.GetValue<string>() ?? "";
            if (proto is "freedom" or "blackhole" or "dns" or "loopback") continue;
            var settings = o["settings"] as JsonObject;
            if (settings == null) continue;
            void Add(JsonObject? target)
            {
                if (target == null) return;
                var addr = target["address"]?.ToString();
                if (string.IsNullOrWhiteSpace(addr)) return;
                if (hosts.Count == 0 && target["port"] != null && int.TryParse(target["port"]!.ToString(), out var p)) first = p;
                if (!hosts.Contains(addr!)) hosts.Add(addr!);
            }
            if (settings["vnext"] is JsonArray vnext) foreach (var v in vnext.OfType<JsonObject>()) Add(v);
            if (settings["servers"] is JsonArray servers) foreach (var v in servers.OfType<JsonObject>()) Add(v);
            Add(settings);
        }
        firstPort = first;
        return hosts;
    }

    // ---------------------------------------------------------------- WireGuard

    public static bool TryParseWireGuard(string text, string? stem, out Profile? profile, out string error)
    {
        profile = null;
        if (!WgConfig.TryParse(text, out var cfg, out error)) return false;
        cfg.TryGetEndpoint(out var host, out var port);
        var amnezia = AmneziaConfig.HasObfuscation(cfg);
        var label = amnezia ? "AmneziaWG" : "WireGuard";
        var raw = amnezia ? AmneziaConfig.Sanitize(text.Trim(), out _) : text.Trim();
        profile = new Profile
        {
            Name = !string.IsNullOrWhiteSpace(cfg.Name) ? cfg.Name! : (!string.IsNullOrWhiteSpace(stem) ? stem! : (host.Length > 0 ? $"{label} {host}" : label)),
            Engine = amnezia ? EngineKind.AmneziaWG : EngineKind.WireGuard,
            Protocol = amnezia ? "amneziawg" : "wireguard",
            Server = host,
            Port = port,
            Raw = raw,
        };
        return true;
    }

    // ---------------------------------------------------------------- OpenVPN

    public static bool TryParseOpenVpn(string text, string? stem, out Profile? profile, out string error)
    {
        profile = null;
        error = "";
        if (!OpenVpnConfig.TryGetFirstRemote(text, out var host, out var port))
        {
            error = "The OpenVPN profile has no \"remote\" line.";
            return false;
        }
        profile = new Profile
        {
            Name = !string.IsNullOrWhiteSpace(stem) ? stem! : $"OpenVPN {host}",
            Engine = EngineKind.OpenVpn,
            Protocol = "openvpn",
            Server = host,
            Port = port,
            Raw = text.Trim(),
        };
        return true;
    }

    // ---------------------------------------------------------------- SlipNet

    public static bool TryParseSlipNet(string link, out Profile? profile, out string error)
    {
        profile = null;
        if (!SlipNetProfile.TryParse(link, out var sn, out error) || sn == null) return false;
        var hosts = SlipNetProfile.ServerHosts(link);
        profile = new Profile
        {
            Name = sn.Name,
            Engine = EngineKind.SlipNet,
            Protocol = string.IsNullOrEmpty(sn.TunnelType) ? "slipnet" : sn.TunnelType,
            Server = hosts.FirstOrDefault() ?? "",
            Raw = link.Trim(),
        };
        return true;
    }

    // ---------------------------------------------------------------- helpers

    public static bool TryDecodeBase64(string input, out string decoded)
    {
        decoded = "";
        if (string.IsNullOrWhiteSpace(input)) return false;
        var s = input.Trim().Replace('-', '+').Replace('_', '/').Replace("\n", "").Replace("\r", "");
        s = s.TrimEnd('=');
        switch (s.Length % 4)
        {
            case 1: return false;
            case 2: s += "=="; break;
            case 3: s += "="; break;
        }
        try
        {
            decoded = Encoding.UTF8.GetString(Convert.FromBase64String(s));
            return true;
        }
        catch (FormatException)
        {
            return false;
        }
    }

    /// <summary>Secret strings of a profile that must never reach the logs.</summary>
    public static IEnumerable<string> CollectSecrets(Profile p)
    {
        if (p.Engine == EngineKind.OpenVpn)
        {
            foreach (var x in OpenVpnConfig.Secrets(p.Raw)) yield return x;
            yield break;
        }
        if (p.Engine == EngineKind.SlipNet)
        {
            foreach (var x in SlipNetProfile.Secrets(p.Raw)) yield return x;
            yield break;
        }
        if (p.Engine is EngineKind.WireGuard or EngineKind.AmneziaWG)
        {
            if (WgConfig.TryParse(p.Raw, out var cfg, out _))
            {
                var pk = cfg.GetInterface("PrivateKey");
                if (!string.IsNullOrEmpty(pk)) yield return pk;
                foreach (var peer in cfg.Peers)
                {
                    var psk = WgConfig.Get(peer, "PresharedKey");
                    if (!string.IsNullOrEmpty(psk)) yield return psk;
                }
            }
            yield break;
        }
        if (p.Protocol == "xray-json") yield break;
        if (TryParseEndpoint(p.Raw, out var ep, out _) && ep != null)
        {
            if (!string.IsNullOrEmpty(ep.Id)) yield return ep.Id;
        }
    }
}
