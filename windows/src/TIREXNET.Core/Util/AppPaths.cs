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
