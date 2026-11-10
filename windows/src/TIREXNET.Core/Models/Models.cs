using System.Text.Json.Serialization;

namespace TIREXNET.Core.Models;

public enum EngineKind { Xray, WireGuard }

public enum ConnectionMode { Proxy, Vpn }

public enum ConnectionState { Disconnected, Connecting, Connected, Disconnecting, Error }

/// <summary>A stored connection profile. <see cref="Raw"/> holds the secret material and is only ever persisted encrypted.</summary>
public sealed class Profile
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = "Unnamed";
    public EngineKind Engine { get; set; } = EngineKind.Xray;
    /// <summary>vless, vmess, trojan, shadowsocks, xray-json or wireguard.</summary>
    public string Protocol { get; set; } = "";
    public string Server { get; set; } = "";
    public int Port { get; set; }
    /// <summary>local, pasarguard or alipanel.</summary>
    public string Source { get; set; } = "local";
    public bool ReadOnly { get; set; }
    public string Raw { get; set; } = "";
    public DateTime CreatedUtc { get; set; } = DateTime.UtcNow;

    /// <summary>Two-letter protocol code shown in the round badge of profile lists.</summary>
    [JsonIgnore]
    public string ProtocolCode => (Engine == EngineKind.WireGuard ? "wireguard" : Protocol) switch
    {
        "vless" => "VL",
        "vmess" => "VM",
        "trojan" => "TR",
        "shadowsocks" => "SS",
        "xray-json" => "XJ",
        "wireguard" => "WG",
        _ => "TX",
    };

    [JsonIgnore] public string Badge => Engine == EngineKind.WireGuard ? "WireGuard" : "Xray";

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
            return string.IsNullOrEmpty(proto) ? $"{Badge} · {SourceLabel}" : $"{Badge} · {proto} · {SourceLabel}";
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
