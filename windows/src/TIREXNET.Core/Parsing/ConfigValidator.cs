using System.Text.Json.Nodes;
using TIREXNET.Core.Models;

namespace TIREXNET.Core.Parsing;

public static class ConfigValidator
{
    private static readonly HashSet<string> SsMethods = new(StringComparer.OrdinalIgnoreCase)
    {
        "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "chacha20-poly1305", "xchacha20-ietf-poly1305",
        "xchacha20-poly1305", "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305", "none", "plain",
    };

    private static readonly HashSet<string> XhttpModes = new(StringComparer.OrdinalIgnoreCase) { "auto", "packet-up", "stream-up", "stream-one" };

    private static readonly HashSet<string> VmessSecurities = new(StringComparer.OrdinalIgnoreCase)
    {
        "auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero",
    };

    /// <summary>Returns a list of human-readable problems. Empty means the profile can be started.</summary>
    public static List<string> Validate(Profile p)
    {
        var errors = new List<string>();
        if (string.IsNullOrWhiteSpace(p.Name)) errors.Add("The profile needs a name.");
        if (string.IsNullOrWhiteSpace(p.Raw)) { errors.Add("The profile has no configuration data."); return errors; }
        if (p.Engine == EngineKind.WireGuard) ValidateWireGuard(p.Raw, errors, amnezia: false);
        else if (p.Engine == EngineKind.AmneziaWG) ValidateWireGuard(p.Raw, errors, amnezia: true);
        else if (p.Engine == EngineKind.OpenVpn) errors.AddRange(OpenVpnConfig.Validate(p.Raw));
        else if (p.Engine == EngineKind.SlipNet) errors.AddRange(SlipNetProfile.Validate(p.Raw));
        else if (p.Protocol == "xray-json") ValidateXrayJson(p.Raw, errors);
        else ValidateUri(p.Raw, errors);
        return errors;
    }

    /// <summary>
    /// Xray 26.x refuses to start VLESS or Trojan without TLS/Reality (and without VLESS encryption) towards anything but a
    /// private IP address. Returns a readable explanation, or null when the profile is fine for the engine.
    /// </summary>
    public static string? PlaintextRejectedByXray(Profile p)
    {
        if (p.Engine != EngineKind.Xray || p.Protocol == "xray-json" || string.IsNullOrWhiteSpace(p.Raw)) return null;
        if (!ProfileParser.TryParseEndpoint(p.Raw, out var ep, out _) || ep == null) return null;
        var noSecurity = string.IsNullOrEmpty(ep.Security) || ep.Security == "none";
        var plain = ep.Protocol switch
        {
            "vless" => noSecurity && (string.IsNullOrEmpty(ep.Encryption) || ep.Encryption == "none"),
            "trojan" => noSecurity,
            _ => false,
        };
        if (!plain || IsPrivateAddress(ep.Host)) return null;
        return $"This {ep.Protocol.ToUpperInvariant()} server sends data without any encryption (no TLS and no Reality). " +
               "The bundled Xray engine refuses to run such profiles for safety, so it cannot be connected. " +
               "Use a profile with TLS or Reality, or ask your provider for one.";
    }

    private static bool IsPrivateAddress(string host)
    {
        if (!NetUtil.TryParseIp(host, out var ip)) return false;
        if (System.Net.IPAddress.IsLoopback(ip)) return true;
        if (ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork)
        {
            var b = ip.GetAddressBytes();
            return b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254);
        }
        var v6 = ip.GetAddressBytes();
        return (v6[0] & 0xFE) == 0xFC || (v6[0] == 0xFE && (v6[1] & 0xC0) == 0x80);
    }

    private static void ValidateUri(string raw, List<string> errors)
    {
        if (!ProfileParser.TryParseEndpoint(raw, out var ep, out var err) || ep == null)
        {
            errors.Add(err);
            return;
        }
        if (!NetUtil.IsValidHostName(ep.Host)) errors.Add("The server address is not valid.");
        if (ep.Port is < 1 or > 65535) errors.Add("The server port must be between 1 and 65535.");
        if (string.IsNullOrWhiteSpace(ep.Id) && ep.Method != "none") errors.Add("The profile has no user ID or password.");
        switch (ep.Protocol)
        {
            case "shadowsocks":
                if (!SsMethods.Contains(ep.Method)) errors.Add($"Shadowsocks cipher \"{ep.Method}\" is not supported.");
                break;
            case "vmess":
                if (!VmessSecurities.Contains(ep.VmessSecurity)) errors.Add($"VMess security \"{ep.VmessSecurity}\" is not supported.");
                if (ep.AlterId < 0 || ep.AlterId > 65535) errors.Add("VMess alterId is out of range.");
                break;
        }
        if (ep.Security == "reality")
        {
            if (string.IsNullOrWhiteSpace(ep.PublicKey)) errors.Add("Reality profiles need a public key (pbk).");
            if (ep.ShortId.Length > 16 || ep.ShortId.Length % 2 != 0 || !ep.ShortId.All(Uri.IsHexDigit))
                errors.Add("The Reality short ID must be an even-length hex string of at most 16 characters.");
        }
        if (ep.Network == "xhttp")
        {
            if (!string.IsNullOrEmpty(ep.Mode) && !XhttpModes.Contains(ep.Mode))
                errors.Add($"The xhttp mode \"{ep.Mode}\" is not supported (use auto, packet-up, stream-up or stream-one).");
            if (!string.IsNullOrWhiteSpace(ep.Extra))
            {
                try
                {
                    if (JsonNode.Parse(ep.Extra) is not JsonObject) errors.Add("The xhttp \"extra\" setting must be a JSON object.");
                }
                catch (System.Text.Json.JsonException ex)
                {
                    errors.Add("The xhttp \"extra\" setting is not valid JSON: " + ex.Message);
                }
            }
        }
        if (ep.Network == "grpc" && string.IsNullOrEmpty(ep.ServiceName) && string.IsNullOrEmpty(ep.Path))
        {
            // Allowed by Xray (empty service name) – no error.
        }
    }

    private static void ValidateXrayJson(string raw, List<string> errors)
    {
        JsonNode? node;
        try { node = JsonNode.Parse(raw); }
        catch (System.Text.Json.JsonException ex) { errors.Add($"The Xray JSON is invalid: {ex.Message}"); return; }
        if (node is not JsonObject obj) { errors.Add("The Xray JSON must be an object."); return; }
        if (obj["outbounds"] is not JsonArray outs || outs.Count == 0) { errors.Add("The Xray JSON needs at least one outbound."); return; }
        foreach (var o in outs)
        {
            if (o is not JsonObject oo || string.IsNullOrWhiteSpace(oo["protocol"]?.ToString()))
            {
                errors.Add("Every outbound needs a \"protocol\".");
                break;
            }
        }
        var hosts = ProfileParser.ExtractJsonHosts(obj, out _);
        if (hosts.Count == 0) errors.Add("The Xray JSON has no proxy outbound with a server address.");
        foreach (var h in hosts)
            if (!NetUtil.IsValidHostName(h)) errors.Add($"Invalid server address in the JSON: {h}");
    }

    internal static void ValidateWireGuard(string raw, List<string> errors, bool amnezia)
    {
        if (!WgConfig.TryParse(raw, out var cfg, out var err)) { errors.Add(err); return; }

        if (amnezia)
        {
            errors.AddRange(AmneziaConfig.Validate(cfg));
        }
        else if (AmneziaConfig.HasObfuscation(cfg))
        {
            errors.Add("This configuration contains AmneziaWG obfuscation parameters (Jc, S1, H1 …). Import it as AmneziaWG so that they are applied.");
            return;
        }
        foreach (var key in WgConfig.ScriptKeys)
        {
            if (cfg.Interface.Any(kv => kv.Key.Equals(key, StringComparison.OrdinalIgnoreCase)))
                errors.Add($"The setting {key} (script execution) is not allowed for security reasons.");
        }

        var priv = cfg.GetInterface("PrivateKey");
        if (!IsWgKey(priv)) errors.Add("The Interface PrivateKey is missing or not a valid WireGuard key.");

        var addr = cfg.GetInterface("Address");
        if (string.IsNullOrWhiteSpace(addr)) errors.Add("The Interface needs an Address.");
        else foreach (var a in addr.Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
            if (!NetUtil.IsValidCidrAny(a)) errors.Add($"Invalid interface address: {a}");

        var dns = cfg.GetInterface("DNS");
        if (!string.IsNullOrWhiteSpace(dns))
            foreach (var d in dns.Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
                if (!NetUtil.TryParseIp(d, out _) && !NetUtil.IsValidHostName(d)) errors.Add($"Invalid DNS entry: {d}");

        var mtu = cfg.GetInterface("MTU");
        if (!string.IsNullOrWhiteSpace(mtu) && (!int.TryParse(mtu, out var m) || m < 576 || m > 1500))
            errors.Add("MTU must be a number between 576 and 1500.");

        var lp = cfg.GetInterface("ListenPort");
        if (!string.IsNullOrWhiteSpace(lp) && (!int.TryParse(lp, out var l) || l < 0 || l > 65535))
            errors.Add("ListenPort must be between 0 and 65535.");

        if (cfg.Peers.Count == 0) errors.Add("At least one [Peer] is required.");
        var n = 0;
        foreach (var peer in cfg.Peers)
        {
            n++;
            if (!IsWgKey(WgConfig.Get(peer, "PublicKey"))) errors.Add($"Peer {n}: PublicKey is missing or invalid.");
            var psk = WgConfig.Get(peer, "PresharedKey");
            if (!string.IsNullOrWhiteSpace(psk) && !IsWgKey(psk)) errors.Add($"Peer {n}: PresharedKey is invalid.");
            var allowed = WgConfig.Get(peer, "AllowedIPs");
            if (string.IsNullOrWhiteSpace(allowed)) errors.Add($"Peer {n}: AllowedIPs is required.");
            else foreach (var a in allowed.Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries))
                if (!NetUtil.IsValidCidrAny(a)) errors.Add($"Peer {n}: invalid AllowedIPs entry {a}");
            var ep = WgConfig.Get(peer, "Endpoint");
            if (!string.IsNullOrWhiteSpace(ep))
            {
                if (!ProfileParser.TryHostPort(ep, out var host, out _) || !NetUtil.IsValidHostName(host))
                    errors.Add($"Peer {n}: Endpoint must look like host:port.");
            }
            var ka = WgConfig.Get(peer, "PersistentKeepalive");
            if (!string.IsNullOrWhiteSpace(ka) && !ka.Equals("off", StringComparison.OrdinalIgnoreCase)
                && (!int.TryParse(ka, out var k) || k < 0 || k > 65535))
                errors.Add($"Peer {n}: PersistentKeepalive must be 0-65535 or \"off\".");
        }
    }

    private static bool IsWgKey(string? key)
    {
        if (string.IsNullOrWhiteSpace(key)) return false;
        try { return Convert.FromBase64String(key.Trim()).Length == 32; }
        catch (FormatException) { return false; }
    }

    public static List<string> ValidateSettings(AppSettings s)
    {
        var errors = new List<string>();
        if (s.SocksPort is < 1024 or > 65535) errors.Add("SOCKS5 port must be between 1024 and 65535.");
        if (s.HttpPort is < 1024 or > 65535) errors.Add("HTTP proxy port must be between 1024 and 65535.");
        if (s.EnableHttpProxy && s.SocksPort == s.HttpPort) errors.Add("SOCKS5 and HTTP ports must be different.");
        if (s.SetSystemProxy && !s.EnableHttpProxy) errors.Add("The Windows system proxy needs the HTTP proxy to be enabled.");
        if (s.Mtu != 0 && (s.Mtu < 576 || s.Mtu > 1500)) errors.Add("MTU must be 0 (default) or between 576 and 1500.");
        foreach (var d in SplitList(s.Dns))
            if (!NetUtil.TryParseIp(d, out _)) errors.Add($"Invalid DNS server: {d}");
        foreach (var b in SplitList(s.BypassNetworks))
            if (!NetUtil.TryParseCidrV4(b, out _, out _)) errors.Add($"Invalid bypass network (use IPv4 CIDR): {b}");
        return errors;
    }

    public static List<string> SplitList(string? text) =>
        (text ?? "").Split(new[] { ',', '\n', '\r', ';', ' ' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToList();
}
