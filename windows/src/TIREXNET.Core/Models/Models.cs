using System.Text.Json.Serialization;

namespace TIREXNET.Core.Models;

/// <summary>Values are persisted as numbers: only ever append.</summary>
public enum EngineKind { Xray = 0, WireGuard = 1, AmneziaWG = 2, OpenVpn = 3, SlipNet = 4, MasterDns = 5, CottenDns = 6, StormDns = 7, Mhrv = 8 }

public enum ConnectionMode { Proxy, Vpn }

public enum ConnectionState { Disconnected, Connecting, Connected, Disconnecting, Error }

/// <summary>A stored connection profile. <see cref="Raw"/> holds the secret material and is only ever persisted encrypted.</summary>
public sealed class Profile
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = "Unnamed";
    public EngineKind Engine { get; set; } = EngineKind.Xray;
    /// <summary>vless, vmess, trojan, shadowsocks, xray-json, wireguard, amneziawg, openvpn or slipnet.</summary>
    public string Protocol { get; set; } = "";
    public string Server { get; set; } = "";
    public int Port { get; set; }
    /// <summary>local, pasarguard or alipanel.</summary>
    public string Source { get; set; } = "local";
    public bool ReadOnly { get; set; }
    public string Raw { get; set; } = "";
    public DateTime CreatedUtc { get; set; } = DateTime.UtcNow;

    // Metadata carried by tirexnet:// share links (Android parity). All optional.
    /// <summary>Android ConfigEngine name from the share link (XRAY, SLIPNET, ...), empty for local imports.</summary>
    public string AndroidEngine { get; set; } = "";
    /// <summary>Custom DNS resolvers shipped with the profile (Android "dns").</summary>
    public List<string> DnsResolvers { get; set; } = new();
    /// <summary>The resolver chosen for this profile (Android "selDns").</summary>
    public string SelectedResolver { get; set; } = "";
    public string Country { get; set; } = "";
    public string Origin { get; set; } = "";
    /// <summary>Per-profile engine settings (Android "custom"); preserved verbatim.</summary>
    public Dictionary<string, string> CustomSettings { get; set; } = new();
    /// <summary>Application-level lock requested by the share's creator: no edit, rename, copy or re-share.</summary>
    public bool Protected { get; set; }

    /// <summary>Two-letter protocol code shown in the round badge of profile lists.</summary>
    [JsonIgnore]
    public string ProtocolCode => (Engine switch
    {
        EngineKind.WireGuard => "wireguard",
        EngineKind.AmneziaWG => "amneziawg",
        EngineKind.OpenVpn => "openvpn",
        EngineKind.SlipNet => "slipnet",
        _ => Protocol,
    }) switch
    {
        "vless" => "VL",
        "vmess" => "VM",
        "trojan" => "TR",
        "shadowsocks" => "SS",
        "xray-json" => "XJ",
        "wireguard" => "WG",
        "amneziawg" => "AW",
        "openvpn" => "OV",
        "slipnet" => "SN",
        "masterdns" => "MD",
        "cottendns" => "CD",
        "stormdns" => "SD",
        "mhrv" => "MH",
        _ => "TX",
    };

    [JsonIgnore]
    public string Badge => Engine switch
    {
        EngineKind.WireGuard => "WireGuard",
        EngineKind.AmneziaWG => "AmneziaWG",
        EngineKind.OpenVpn => "OpenVPN",
        EngineKind.SlipNet => "SlipNet",
        EngineKind.MasterDns => "MasterDNS",
        EngineKind.CottenDns => "CottenDNS",
        EngineKind.StormDns => "StormDNS",
        EngineKind.Mhrv => "MHRV",
        _ => "Xray",
    };

    /// <summary>Last latency measurement (not persisted): -1 unknown, -2 timeout, -3 failed, otherwise milliseconds.</summary>
    [JsonIgnore] public int PingMs { get; set; } = -1;

    [JsonIgnore]
    public string SourceLabel => Source switch
    {
        "pasarguard" => "PasarGuard",
        "alipanel" => "AliPanel",
        _ => "Local",
    };

    [JsonIgnore]
    public string Summary
    {
        get
        {
            var proto = string.IsNullOrEmpty(Protocol) ? "" : Protocol.ToUpperInvariant();
            var text = string.IsNullOrEmpty(proto) ? $"{Badge} · {SourceLabel}" : $"{Badge} · {proto} · {SourceLabel}";
            return PingMs switch
            {
                > 0 => $"{text} · {PingMs} ms",
                -2 => text + " · timeout",
                -3 => text + " · unreachable",
                _ => text,
            };
        }
    }

    [JsonIgnore]
    public string Endpoint => string.IsNullOrEmpty(Server) ? "—" : (Port > 0 ? $"{Server}:{Port}" : Server);
}

public sealed class AppSettings
{
    public ConnectionMode Mode { get; set; } = ConnectionMode.Proxy;
    public int SocksPort { get; set; } = 10880;
    public int HttpPort { get; set; } = 10881;
    public bool EnableHttpProxy { get; set; } = true;
    /// <summary>When true (proxy mode only) the Windows system proxy is pointed at the local HTTP proxy and restored on disconnect.</summary>
    public bool SetSystemProxy { get; set; } = false;
    /// <summary>Comma separated DNS servers. Empty keeps the profile / system default.</summary>
    public string Dns { get; set; } = "";
    /// <summary>0 keeps the engine default.</summary>
    public int Mtu { get; set; } = 0;
    /// <summary>IPv4 networks (CIDR) that bypass the tunnel in VPN mode, one per line or comma separated.</summary>
    public string BypassNetworks { get; set; } = "";
    public bool CloseToTray { get; set; } = true;
    public bool StartMinimized { get; set; } = false;
    public string LastProfileId { get; set; } = "";

    // OTA updates
    public bool UpdateAutoCheck { get; set; } = true;
    public int UpdateIntervalHours { get; set; } = 24;
    /// <summary>stable or beta.</summary>
    public string UpdateChannel { get; set; } = "stable";
    /// <summary>https manifest (see scripts/make-update-manifest.py). Editable in Settings.</summary>
    public string UpdateManifestUrl { get; set; } = "https://github.com/HRMPMADEUA/HRMP-AETHER/releases/latest/download/windows-update.json";
    public string LastUpdateCheckUtc { get; set; } = "";
}

public sealed class TrafficSnapshot
{
    public long UploadBytes { get; init; }
    public long DownloadBytes { get; init; }
    public double UploadBytesPerSecond { get; init; }
    public double DownloadBytesPerSecond { get; init; }
}

public sealed class EngineException : Exception
{
    public EngineException(string message) : base(message) { }
    public EngineException(string message, Exception inner) : base(message, inner) { }
}

/// <summary>Raised when an operation needs administrator rights and the process is not elevated.</summary>
public sealed class ElevationRequiredException : Exception
{
    public ElevationRequiredException(string message) : base(message) { }
}
