using System.Security.Cryptography;
using System.Text;

namespace TIREXNET.Core;

/// <summary>Windows DPAPI (current user) helpers. Secrets never touch the disk unencrypted.</summary>
public static class Secure
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("TIREXNET.Windows.v1");

    public static byte[] Protect(byte[] plain) => ProtectedData.Protect(plain, Entropy, DataProtectionScope.CurrentUser);

    public static byte[] Unprotect(byte[] cipher) => ProtectedData.Unprotect(cipher, Entropy, DataProtectionScope.CurrentUser);

    public static void WriteEncrypted(string path, string plainText)
    {
        var data = Protect(Encoding.UTF8.GetBytes(plainText));
        var tmp = path + ".tmp";
        File.WriteAllBytes(tmp, data);
        File.Move(tmp, path, true);
    }

    /// <summary>Returns null when the file is missing. A file that cannot be decrypted is moved aside and reported via <paramref name="corrupt"/>.</summary>
    public static string? ReadEncrypted(string path, out bool corrupt)
    {
        corrupt = false;
        if (!File.Exists(path)) return null;
        try
        {
            return Encoding.UTF8.GetString(Unprotect(File.ReadAllBytes(path)));
        }
        catch (Exception ex) when (ex is CryptographicException or IOException)
        {
            corrupt = true;
            try { File.Move(path, path + ".unreadable", true); } catch { /* ignore */ }
            return null;
        }
    }
}
