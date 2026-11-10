namespace TIREXNET.Core.Parsing;

/// <summary>Protocol-neutral description of a single Xray outbound parsed from a share link.</summary>
public sealed class XrayEndpoint
{
    public string Protocol { get; set; } = "";   // vless | vmess | trojan | shadowsocks
    public string Host { get; set; } = "";
    public int Port { get; set; }
    public string Id { get; set; } = "";         // UUID (vless/vmess) or password (trojan/shadowsocks)
    public string Method { get; set; } = "";     // shadowsocks cipher
    public string Flow { get; set; } = "";
    public string Encryption { get; set; } = "none";
    public string VmessSecurity { get; set; } = "auto";
    public int AlterId { get; set; }
    public string Network { get; set; } = "tcp"; // tcp | ws | grpc | http | httpupgrade | xhttp | kcp
    public string Security { get; set; } = "none"; // none | tls | reality
    public string Sni { get; set; } = "";
    public string Alpn { get; set; } = "";
    public string Fingerprint { get; set; } = "";
    public string PublicKey { get; set; } = "";
    public string ShortId { get; set; } = "";
    public string SpiderX { get; set; } = "";
    public string Path { get; set; } = "";
    public string HostHeader { get; set; } = "";
    public string ServiceName { get; set; } = "";
    public string HeaderType { get; set; } = "none";
    public string Mode { get; set; } = "";
    public string Seed { get; set; } = "";
    /// <summary>xhttp "extra" JSON object from share links (optional).</summary>
    public string Extra { get; set; } = "";
    public bool AllowInsecure { get; set; }
    public string Name { get; set; } = "";
}
