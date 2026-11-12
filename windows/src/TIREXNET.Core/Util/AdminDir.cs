using System.Security.AccessControl;
using System.Security.Principal;

namespace TIREXNET.Core;

/// <summary>Creates folders that only SYSTEM and Administrators can read, for short-lived files that contain keys.</summary>
public static class AdminDir
{
    public static void Prepare(string dir)
    {
        Directory.CreateDirectory(dir);
        var security = new DirectorySecurity();
        security.SetAccessRuleProtection(true, false); // drop inherited permissions
        var inherit = InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit;
        foreach (var sid in new[] { WellKnownSidType.LocalSystemSid, WellKnownSidType.BuiltinAdministratorsSid })
        {
            security.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(sid, null), FileSystemRights.FullControl, inherit, PropagationFlags.None, AccessControlType.Allow));
        }
        new DirectoryInfo(dir).SetAccessControl(security);
    }

    public static void TryDelete(string file)
    {
        try { if (File.Exists(file)) File.Delete(file); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { Log.Warn("Could not delete a temporary file: " + ex.Message); }
    }
}
