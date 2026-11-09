using System.ComponentModel;
using System.Diagnostics;
using System.Security.Principal;

namespace TIREXNET.Core;

public static class Elevation
{
    public static bool IsAdministrator()
    {
        try
        {
            using var id = WindowsIdentity.GetCurrent();
            return new WindowsPrincipal(id).IsInRole(WindowsBuiltInRole.Administrator);
        }
        catch
        {
            return false;
        }
    }

    /// <summary>Starts a new elevated instance (UAC prompt). Returns false when the user declines.</summary>
    public static bool RestartElevated(string arguments)
    {
        try
        {
            var exe = Environment.ProcessPath;
            if (string.IsNullOrEmpty(exe)) return false;
            var psi = new ProcessStartInfo(exe, arguments)
            {
                UseShellExecute = true,
                Verb = "runas",
                WorkingDirectory = AppContext.BaseDirectory,
            };
            Process.Start(psi);
            return true;
        }
        catch (Win32Exception)
        {
            return false; // UAC declined
        }
    }
}
