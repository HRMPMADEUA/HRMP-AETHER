namespace TIREXNET.Core;

public static class AppPaths
{
    public static string AppDir => AppContext.BaseDirectory;
    public static string EnginesDir => Path.Combine(AppDir, "engines");
    public static string XrayDir => Path.Combine(EnginesDir, "xray");
    public static string XrayExe => Path.Combine(XrayDir, "xray.exe");
    public static string Tun2SocksDir => Path.Combine(EnginesDir, "tun2socks");
    public static string Tun2SocksExe => Path.Combine(Tun2SocksDir, "tun2socks.exe");
    public static string WintunDll => Path.Combine(Tun2SocksDir, "wintun.dll");
    public static string WireGuardDir => Path.Combine(EnginesDir, "wireguard");
    public static string WireGuardExe => Path.Combine(WireGuardDir, "wireguard.exe");
    public static string AmneziaWgDir => Path.Combine(EnginesDir, "amneziawg");
    public static string AmneziaWgExe => Path.Combine(AmneziaWgDir, "amneziawg.exe");
    public static string AwgCliExe => Path.Combine(AmneziaWgDir, "awg.exe");
    public static string SlipNetDir => Path.Combine(EnginesDir, "slipnet");
    public static string SlipNetExe => Path.Combine(SlipNetDir, "slipnet.exe");
    public static string SoarDir => Path.Combine(EnginesDir, "soar");
    public static string SoarWrapperExe => Path.Combine(SoarDir, "soar-wrapper.exe");
    public static string MasterDnsDir => Path.Combine(EnginesDir, "masterdns");
    public static string MasterDnsExe => Path.Combine(MasterDnsDir, "masterdnsvpn-client.exe");
    public static string CottenDnsDir => Path.Combine(EnginesDir, "cottendns");
    public static string CottenDnsExe => Path.Combine(CottenDnsDir, "cottendns-client.exe");
    public static string StormDnsDir => Path.Combine(EnginesDir, "stormdns");
    public static string StormDnsExe => Path.Combine(StormDnsDir, "stormdns-client.exe");
    public static string SniSpoofDir => Path.Combine(EnginesDir, "snispf");
    public static string SniSpoofExe => Path.Combine(SniSpoofDir, "snispf.exe");
    public static string MhrvDir => Path.Combine(EnginesDir, "mhrv");
    public static string MhrvExe => Path.Combine(MhrvDir, "mhrv-rs.exe");
    /// <summary>Where the TIREXNET installer places the official OpenVPN Community build (INSTALLDIR).</summary>
    public static string OpenVpnBundledExe => Path.Combine(EnginesDir, "openvpn", "bin", "openvpn.exe");

    /// <summary>Per-user settings and encrypted profile data.</summary>
    public static string DataDir => Ensure(Environment.GetEnvironmentVariable("TIREXNET_DATA_DIR")
        ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "TIREXNET"));

    public static string LogDir => Ensure(Path.Combine(
        Environment.GetEnvironmentVariable("TIREXNET_DATA_DIR") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TIREXNET"),
        "logs"));

    /// <summary>Per-user scratch space for short-lived runtime files.</summary>
    public static string RunDir => Ensure(Path.Combine(
        Environment.GetEnvironmentVariable("TIREXNET_DATA_DIR") ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TIREXNET"),
        "run"));

    /// <summary>Machine-wide, administrator-only state (crash-recovery journal and the WireGuard service config).</summary>
    public static string MachineDir => Environment.GetEnvironmentVariable("TIREXNET_MACHINE_DIR")
        ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "TIREXNET");

    private static string Ensure(string path)
    {
        Directory.CreateDirectory(path);
        return path;
    }
}
