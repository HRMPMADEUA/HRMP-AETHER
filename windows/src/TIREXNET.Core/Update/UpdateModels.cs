using System.Text.Json;
using System.Text.Json.Serialization;

namespace TIREXNET.Core.Update;

/// <summary>Semantic version with optional pre-release tag: 1.2.3 or 1.2.3-beta.4. A release is newer than its own pre-releases.</summary>
public readonly record struct SemVer(int Major, int Minor, int Patch, string Pre) : IComparable<SemVer>
{
    public static bool TryParse(string? text, out SemVer v)
    {
        v = default;
        if (string.IsNullOrWhiteSpace(text)) return false;
        var t = text.Trim().TrimStart('v', 'V');
        var plus = t.IndexOf('+');
        if (plus >= 0) t = t[..plus];
        var pre = "";
        var dash = t.IndexOf('-');
        if (dash >= 0) { pre = t[(dash + 1)..]; t = t[..dash]; }
        var parts = t.Split('.');
        if (parts.Length is < 2 or > 4) return false;
        if (!int.TryParse(parts[0], out var a) || !int.TryParse(parts[1], out var b)) return false;
        var c = 0;
        if (parts.Length >= 3 && !int.TryParse(parts[2], out c)) return false;
        if (a < 0 || b < 0 || c < 0) return false;
        v = new SemVer(a, b, c, pre);
        return true;
    }

    public static SemVer Parse(string text) => TryParse(text, out var v) ? v : throw new FormatException("Invalid version: " + text);

    public bool IsPreRelease => Pre.Length > 0;

    public int CompareTo(SemVer o)
    {
        var c = Major.CompareTo(o.Major);
        if (c != 0) return c;
        c = Minor.CompareTo(o.Minor);
        if (c != 0) return c;
        c = Patch.CompareTo(o.Patch);
        if (c != 0) return c;
        if (Pre.Length == 0 && o.Pre.Length == 0) return 0;
        if (Pre.Length == 0) return 1;
        if (o.Pre.Length == 0) return -1;
        var a = Pre.Split('.');
        var b = o.Pre.Split('.');
        for (var i = 0; i < Math.Min(a.Length, b.Length); i++)
        {
            var an = int.TryParse(a[i], out var x);
            var bn = int.TryParse(b[i], out var y);
            c = an && bn ? x.CompareTo(y) : an ? -1 : bn ? 1 : string.CompareOrdinal(a[i], b[i]);
            if (c != 0) return c;
        }
        return a.Length.CompareTo(b.Length);
    }

    public static bool operator <(SemVer a, SemVer b) => a.CompareTo(b) < 0;
    public static bool operator >(SemVer a, SemVer b) => a.CompareTo(b) > 0;
    public static bool operator <=(SemVer a, SemVer b) => a.CompareTo(b) <= 0;
    public static bool operator >=(SemVer a, SemVer b) => a.CompareTo(b) >= 0;
    public override string ToString() => $"{Major}.{Minor}.{Patch}" + (Pre.Length > 0 ? "-" + Pre : "");
}

public sealed class UpdateRelease
{
    [JsonPropertyName("version")] public string Version { get; set; } = "";
    [JsonPropertyName("url")] public string Url { get; set; } = "";
    [JsonPropertyName("sha256")] public string Sha256 { get; set; } = "";
    [JsonPropertyName("size")] public long Size { get; set; }
    [JsonPropertyName("notes")] public List<string> Notes { get; set; } = new();
    /// <summary>Oldest installed version allowed to update directly to this one.</summary>
    [JsonPropertyName("minVersion")] public string MinVersion { get; set; } = "";
    [JsonPropertyName("published")] public string Published { get; set; } = "";
    /// <summary>When true the installer must carry a valid Authenticode signature, whatever the running build is.</summary>
    [JsonPropertyName("requireSignature")] public bool RequireSignature { get; set; }
}

public sealed class UpdateManifest
{
    [JsonPropertyName("schema")] public int Schema { get; set; } = 1;
    [JsonPropertyName("channels")] public Dictionary<string, UpdateRelease> Channels { get; set; } = new(StringComparer.OrdinalIgnoreCase);
}

public enum UpdateFailure { None, Network, ManifestInvalid, NoChannel, Downgrade, TooOld, Corrupt, SizeMismatch, HashMismatch, SignatureInvalid, SignerChanged, InstallFailed }

public sealed class UpdateException : Exception
{
    public UpdateFailure Failure { get; }
    public UpdateException(UpdateFailure f, string message, Exception? inner = null) : base(message, inner) { Failure = f; }
}

public sealed class UpdateCheckResult
{
    public bool Available { get; init; }
    public SemVer Current { get; init; }
    public UpdateRelease? Release { get; init; }
    public SemVer Latest { get; init; }
    public string Channel { get; init; } = "stable";
}

/// <summary>Checks an installer file's Authenticode signature. Replaceable in tests.</summary>
public interface IUpdateVerifier
{
    bool IsSignedAndTrusted(string path, out string signer);
}

public sealed class AuthenticodeUpdateVerifier : IUpdateVerifier
{
    public bool IsSignedAndTrusted(string path, out string signer) => Authenticode.IsTrusted(path, out signer);
}
