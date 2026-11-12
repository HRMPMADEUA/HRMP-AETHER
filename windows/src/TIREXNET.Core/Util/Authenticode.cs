using System.Runtime.InteropServices;
using System.Security.Cryptography.X509Certificates;

namespace TIREXNET.Core;

/// <summary>
/// Checks the embedded Authenticode signature of an executable with WinVerifyTrust (the same check Windows itself uses)
/// and reads the signer. Used before TIREXNET starts third-party engines that it did not build.
/// </summary>
public static class Authenticode
{
    private static readonly Guid GenericVerifyV2 = new("00AAC56B-CD44-11d0-8CC2-00C04FC295EE");

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WintrustFileInfo
    {
        public uint cbStruct;
        [MarshalAs(UnmanagedType.LPWStr)] public string pcwszFilePath;
        public IntPtr hFile;
        public IntPtr pgKnownSubject;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct WintrustData
    {
        public uint cbStruct;
        public IntPtr pPolicyCallbackData;
        public IntPtr pSIPClientData;
        public uint dwUIChoice;
        public uint fdwRevocationChecks;
        public uint dwUnionChoice;
        public IntPtr pFile;
        public uint dwStateAction;
        public IntPtr hWVTStateData;
        public IntPtr pwszURLReference;
        public uint dwProvFlags;
        public uint dwUIContext;
        public IntPtr pSignatureSettings;
    }

    [DllImport("wintrust.dll", ExactSpelling = true, SetLastError = false, CharSet = CharSet.Unicode)]
    private static extern int WinVerifyTrust(IntPtr hwnd, [MarshalAs(UnmanagedType.LPStruct)] Guid action, ref WintrustData data);

    /// <summary>True when the file has a valid, trusted signature. <paramref name="signer"/> is the subject of the signing certificate.</summary>
    public static bool IsTrusted(string path, out string signer)
    {
        signer = "";
        if (!File.Exists(path)) return false;
        var fileInfo = new WintrustFileInfo { pcwszFilePath = path };
        fileInfo.cbStruct = (uint)Marshal.SizeOf(fileInfo);
        var pFile = Marshal.AllocHGlobal(Marshal.SizeOf<WintrustFileInfo>());
        try
        {
            Marshal.StructureToPtr(fileInfo, pFile, false);
            var data = new WintrustData
            {
                dwUIChoice = 2,            // WTD_UI_NONE
                fdwRevocationChecks = 0,   // WTD_REVOKE_NONE (works offline; the chain itself is still validated)
                dwUnionChoice = 1,         // WTD_CHOICE_FILE
                pFile = pFile,
                dwStateAction = 0,         // WTD_STATEACTION_IGNORE
                dwProvFlags = 0x10,        // WTD_CACHE_ONLY_URL_RETRIEVAL
            };
            data.cbStruct = (uint)Marshal.SizeOf(data);
            var result = WinVerifyTrust(new IntPtr(-1), GenericVerifyV2, ref data);
            if (result != 0) return false;
        }
        finally
        {
            Marshal.FreeHGlobal(pFile);
        }
        try
        {
#pragma warning disable SYSLIB0057
            using var cert = new X509Certificate2(X509Certificate.CreateFromSignedFile(path));
#pragma warning restore SYSLIB0057
            signer = cert.Subject;
        }
        catch (Exception ex) when (ex is System.Security.Cryptography.CryptographicException or IOException)
        {
            return false;
        }
        return true;
    }
}
