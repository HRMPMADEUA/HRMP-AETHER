using TIREXNET.Core.Models;

namespace TIREXNET.Core.Engines;

/// <summary>What one engine is, what it needs and which modes it supports.</summary>
public sealed record EngineInfo(EngineKind Kind, string Name, string Protocols, bool SupportsProxy, bool SupportsVpn, string Runtime, string Driver);

/// <summary>Single place that answers "which engine, which mode, is it installed, does it need administrator rights".</summary>
public static class EngineCatalog
{
    public static readonly IReadOnlyList<EngineInfo> All = new[]
    {
        new EngineInfo(EngineKind.Xray, "Xray", "VLESS, VMess, Trojan, Shadowsocks, Xray JSON (TCP, WebSocket, gRPC, HTTP/2, xhttp, TLS, Reality)", true, true,
            "Xray-core (XTLS)", "Wintun via tun2socks (VPN mode)"),
        new EngineInfo(EngineKind.WireGuard, "WireGuard", "WireGuard", false, true, "wireguard-windows tunnel service", "Wintun (inside wireguard.exe)"),
        new EngineInfo(EngineKind.AmneziaWG, "AmneziaWG", "AmneziaWG (WireGuard + Jc/Jmin/Jmax, S1-S4, H1-H4, I1-I5)", false, true,
            "amneziawg-windows-client tunnel service", "Wintun (wintun.dll next to amneziawg.exe)"),
        new EngineInfo(EngineKind.OpenVpn, "OpenVPN", "OpenVPN (UDP/TCP, TLS, tls-crypt/tls-auth, user/password)", false, true,
            "Official OpenVPN Community build (signed openvpn.exe)", "Wintun / ovpn-dco installed by the official OpenVPN installer"),
        new EngineInfo(EngineKind.SlipNet, "SlipNet", "SlipNet: DNSTT, NoizDNS, VayDNS, SSH, SOCKS5, VLESS, DoH", true, true,
            "SlipNet command-line client (upstream, built from a pinned commit)", "Wintun via tun2socks (VPN mode)"),
    };

    public static EngineInfo Get(EngineKind kind) => All.First(e => e.Kind == kind);

    public static bool IsRuntimeAvailable(EngineKind kind) => kind switch
    {
        EngineKind.Xray => XrayEngine.IsRuntimeAvailable,
        EngineKind.WireGuard => WireGuardEngine.IsRuntimeAvailable,
        EngineKind.AmneziaWG => AmneziaWgEngine.IsRuntimeAvailable,
        EngineKind.OpenVpn => OpenVpnEngine.IsRuntimeAvailable,
        EngineKind.SlipNet => SlipNetEngine.IsRuntimeAvailable,
        _ => false,
    };

    public static bool IsTun2SocksAvailable => File.Exists(AppPaths.Tun2SocksExe) && File.Exists(AppPaths.WintunDll);

    /// <summary>Engines with a native tunnel always run as a full-device VPN; Xray and SlipNet follow the selected mode.</summary>
    public static ConnectionMode EffectiveMode(Profile p, AppSettings s) =>
        p.Engine is EngineKind.Xray or EngineKind.SlipNet ? s.Mode : ConnectionMode.Vpn;

    public static bool NeedsAdmin(Profile p, AppSettings s) => EffectiveMode(p, s) == ConnectionMode.Vpn;

    /// <summary>A readable reason why this profile cannot be connected with the current installation, or null.</summary>
    public static string? UnavailableReason(Profile p, AppSettings s)
    {
        var info = Get(p.Engine);
        if (!IsRuntimeAvailable(p.Engine))
            return p.Engine == EngineKind.OpenVpn
                ? "OpenVPN runtime is missing. Install the official OpenVPN Community build (offered by the TIREXNET installer)."
                : $"{info.Name} runtime is missing from this installation. Reinstall TIREXNET.";
        if (p.Engine is EngineKind.Xray or EngineKind.SlipNet && s.Mode == ConnectionMode.Vpn && !IsTun2SocksAvailable)
            return "VPN mode runtime is missing: tun2socks and Wintun are not installed in this build.";
        return null;
    }

    public static string Describe(Profile p, AppSettings s)
    {
        var info = Get(p.Engine);
        var mode = EffectiveMode(p, s);
        return mode == ConnectionMode.Vpn ? $"{info.Name} VPN" : $"Proxy mode · SOCKS5 127.0.0.1:{s.SocksPort}";
    }

    public static IEngine Create(EngineKind kind) => kind switch
    {
        EngineKind.WireGuard => new WireGuardEngine(),
        EngineKind.AmneziaWG => new AmneziaWgEngine(),
        EngineKind.OpenVpn => new OpenVpnEngine(),
        EngineKind.SlipNet => new SlipNetEngine(),
        _ => new XrayEngine(),
    };

    /// <summary>One line per engine for the Settings page and the diagnostics report.</summary>
    public static IEnumerable<string> StatusLines()
    {
        foreach (var e in All)
        {
            var ok = IsRuntimeAvailable(e.Kind);
            var modes = e.SupportsProxy && e.SupportsVpn ? "Proxy + VPN" : "VPN";
            yield return $"• {e.Name} ({e.Protocols}) · {modes}: {(ok ? "available" : (e.Kind == EngineKind.OpenVpn ? "OpenVPN not installed" : "not installed"))}";
        }
        yield return $"• VPN mode for Xray and SlipNet (tun2socks + Wintun): {(IsTun2SocksAvailable ? "available" : "not installed")}";
    }
}
