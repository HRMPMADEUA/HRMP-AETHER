using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace TIREXNET.Core.Parsing;

public enum ShareErrorKind { TooLarge, InvalidFormat, DecryptFailed, Tampered, UnsupportedVersion }

public sealed class ShareException : Exception
{
    public ShareErrorKind Kind { get; }
    public ShareException(ShareErrorKind kind, string message) : base(message) { Kind = kind; }
}

/// <summary>
/// The TIREXNET share link, byte-compatible with the Android app (TirexNetShare.kt):
/// <c>tirexnet://share/v1/&lt;URL-safe Base64, no padding, of: version(1) | iv(12) | AES-256-GCM(gzip(JSON)) + tag(16)&gt;</c>.
/// The AES key is SHA-256(salt "tirexnet.share.opaque.v1" + phrase) - the same fixed application key the Android app uses.
/// It only keeps casual readers out; it is not secret against someone who owns the app.
/// </summary>
public static class TirexShare
{
    public const string Scheme = "tirexnet://share/v1/";
    private const string Prefix = "tirexnet://share/";
    public const int MaxUriChars = 150_000;
    private const int MaxToken = 200_000;
    private const int IvBytes = 12;
    private const int TagBytes = 16;

    public sealed class Payload
    {
        public int SchemaVersion { get; init; } = 1;
        public string Name { get; init; } = "Imported profile";
        /// <summary>Android ConfigEngine name: XRAY, WIREGUARD, AMNEZIAWG, OPENVPN, SLIPNET, MASTERDNS, COTTENDNS, DNS_TUNNEL, MHRV, SNI_SPOOF, DNS_CHANGER …</summary>
        public string Engine { get; init; } = "UNKNOWN";
        public string Raw { get; init; } = "";
        public string Origin { get; init; } = "IMPORTED_TIREXNET";
        public List<string> DnsResolvers { get; init; } = new();
        public string SelectedResolver { get; init; } = "";
        public Dictionary<string, string> CustomSettings { get; init; } = new();
        public string Country { get; init; } = "";
        public long CreatedAt { get; init; }
        /// <summary>The creator asked for a locked profile (no edit, copy or re-share). Application-level lock only.</summary>
        public bool Protected { get; init; }
    }

    private static byte[] AppKey()
    {
        var salt = Encoding.UTF8.GetBytes("tirexnet.share.opaque.v1");
        var phrase = Encoding.UTF8.GetBytes("TIREXNET-OPAQUE-SHARE-v1-HRMP-AETHER-2026");
        var all = new byte[salt.Length + phrase.Length];
        salt.CopyTo(all, 0);
        phrase.CopyTo(all, salt.Length);
        return SHA256.HashData(all);
    }

    public static bool IsShare(string? value) =>
        value != null && value.Trim().StartsWith(Prefix, StringComparison.OrdinalIgnoreCase);

    /// <summary>Strips the wrappers chat apps add: &lt;…&gt;, "…" or '…'.</summary>
    internal static string Unwrap(string text)
    {
        var v = text.Trim();
        foreach (var (a, b) in new[] { ('<', '>'), ('"', '"'), ('\'', '\'') })
            if (v.Length >= 2 && v[0] == a && v[^1] == b) v = v[1..^1];
        return v;
    }

    private static byte[]? Base64Flexible(string token)
    {
        var t = new string(token.Where(c => !char.IsWhiteSpace(c)).ToArray()).Replace('-', '+').Replace('_', '/');
        t = t.TrimEnd('=');
        if (t.Length % 4 == 1) return null;
        t = t.PadRight(t.Length + (4 - t.Length % 4) % 4, '=');
        try { return Convert.FromBase64String(t); }
        catch (FormatException) { return null; }
    }

    public static Payload Decode(string uriOrToken)
    {
        var value = Unwrap(uriOrToken);
        // Browsers and shells may percent-encode the link or add a trailing slash.
        if (value.Contains('%')) { try { value = Uri.UnescapeDataString(value); } catch (UriFormatException) { } }
        string token;
        if (value.StartsWith(Scheme, StringComparison.OrdinalIgnoreCase)) token = value[Scheme.Length..];
        else if (value.StartsWith(Prefix, StringComparison.OrdinalIgnoreCase))
        {
            var after = value[Prefix.Length..];
            var slash = after.IndexOf('/');
            var ver = slash < 0 ? after : after[..slash];
            if (ver != "v1") throw new ShareException(ShareErrorKind.UnsupportedVersion, $"Unsupported share version: {Clip(ver)}");
            token = slash < 0 ? "" : after[(slash + 1)..];
        }
        else throw new ShareException(ShareErrorKind.InvalidFormat, "Not a TIREXNET share link.");
        if (string.IsNullOrWhiteSpace(token) || token.Length > MaxToken)
            throw new ShareException(ShareErrorKind.InvalidFormat, "The share link is empty or too large.");

        var packed = Base64Flexible(token) ?? Base64Flexible(token.TrimEnd('/'));
        if (packed == null) throw new ShareException(ShareErrorKind.InvalidFormat, "The share link is not valid Base64.");
        if (packed.Length < 1 + IvBytes + TagBytes) throw new ShareException(ShareErrorKind.InvalidFormat, "The share link is too short.");
        if (packed[0] != 1) throw new ShareException(ShareErrorKind.UnsupportedVersion, $"Binary share version {packed[0]} is not supported.");

        byte[] plain;
        try
        {
            var iv = packed.AsSpan(1, IvBytes);
            var body = packed.AsSpan(1 + IvBytes);
            var ct = body[..^TagBytes];
            var tag = body[^TagBytes..];
            var gz = new byte[ct.Length];
            using (var aes = new AesGcm(AppKey(), TagBytes)) aes.Decrypt(iv, ct, tag, gz);
            using var input = new MemoryStream(gz);
            using var gzip = new GZipStream(input, CompressionMode.Decompress);
            using var output = new MemoryStream();
            gzip.CopyTo(output);
            plain = output.ToArray();
        }
        catch (Exception ex) when (ex is CryptographicException or InvalidDataException or ArgumentException)
        {
            throw new ShareException(ShareErrorKind.DecryptFailed, "The share link failed its integrity check (damaged, edited or not a TIREXNET link).");
        }

        try
        {
            using var doc = JsonDocument.Parse(plain);
            var r = doc.RootElement;
            if (r.ValueKind != JsonValueKind.Object) throw new JsonException();
            var schema = r.TryGetProperty("v", out var vEl) && vEl.ValueKind == JsonValueKind.Number && vEl.TryGetInt32(out var vi) ? vi : 0;
            if (schema < 1 || schema > 1) throw new ShareException(ShareErrorKind.UnsupportedVersion, $"Share schema {schema} is not supported.");
            var raw = Str(r, "raw");
            if (string.IsNullOrWhiteSpace(raw)) throw new ShareException(ShareErrorKind.Tampered, "The share link contains no configuration.");
            var prot = false;
            if (r.TryGetProperty("prot", out var pEl))
            {
                if (pEl.ValueKind is JsonValueKind.True) prot = true;
                else if (pEl.ValueKind is not JsonValueKind.False) throw new ShareException(ShareErrorKind.Tampered, "The share link has invalid protection data.");
            }
            var dns = new List<string>();
            if (r.TryGetProperty("dns", out var dEl) && dEl.ValueKind == JsonValueKind.Array)
                foreach (var e in dEl.EnumerateArray()) { var s = e.ValueKind == JsonValueKind.String ? e.GetString() : e.ToString(); if (!string.IsNullOrWhiteSpace(s)) dns.Add(s!); }
            var custom = new Dictionary<string, string>();
            if (r.TryGetProperty("custom", out var cEl) && cEl.ValueKind == JsonValueKind.Object)
                foreach (var p in cEl.EnumerateObject()) custom[p.Name] = p.Value.ValueKind == JsonValueKind.String ? p.Value.GetString() ?? "" : p.Value.ToString();
            var name = Str(r, "name");
            return new Payload
            {
                SchemaVersion = schema,
                Name = string.IsNullOrWhiteSpace(name) ? "Imported profile" : name,
                Engine = Str(r, "engine") is { Length: > 0 } en ? en : "UNKNOWN",
                Raw = raw,
                Origin = Str(r, "origin") is { Length: > 0 } o ? o : "IMPORTED_TIREXNET",
                DnsResolvers = dns,
                SelectedResolver = Str(r, "selDns"),
                CustomSettings = custom,
                Country = Str(r, "country"),
                CreatedAt = r.TryGetProperty("ts", out var ts) && ts.ValueKind == JsonValueKind.Number && ts.TryGetInt64(out var tl) ? tl : 0,
                Protected = prot,
            };
        }
        catch (JsonException)
        {
            throw new ShareException(ShareErrorKind.Tampered, "The share link contains damaged data.");
        }
    }

    private static string Str(JsonElement e, string name) =>
        e.TryGetProperty(name, out var v) ? (v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : v.ToString()) : "";

    private static string Clip(string s) => s.Length > 12 ? s[..12] : s;

    /// <summary>Creates a link that the Android app (and this one) can open. <paramref name="iv"/> is for tests only.</summary>
    public static string Encode(Payload p, byte[]? iv = null)
    {
        if (string.IsNullOrWhiteSpace(p.Raw)) throw new ArgumentException("empty raw");
        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms))
        {
            w.WriteStartObject();
            w.WriteNumber("v", 1);
            w.WriteString("name", p.Name.Length > 120 ? p.Name[..120] : p.Name);
            w.WriteString("engine", p.Engine);
            w.WriteString("raw", p.Raw);
            w.WriteString("origin", p.Origin);
            w.WriteStartArray("dns");
            foreach (var d in p.DnsResolvers.Where(x => !string.IsNullOrWhiteSpace(x)).Distinct()) w.WriteStringValue(d);
            w.WriteEndArray();
            w.WriteString("selDns", p.SelectedResolver);
            w.WriteStartObject("custom");
            foreach (var kv in p.CustomSettings) w.WriteString(kv.Key, kv.Value);
            w.WriteEndObject();
            w.WriteString("country", p.Country.Length > 40 ? p.Country[..40] : p.Country);
            w.WriteBoolean("prot", p.Protected);
            w.WriteNumber("ts", p.CreatedAt != 0 ? p.CreatedAt : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            w.WriteEndObject();
        }
        byte[] gz;
        using (var o = new MemoryStream())
        {
            using (var g = new GZipStream(o, CompressionLevel.Optimal, true)) g.Write(ms.ToArray());
            gz = o.ToArray();
        }
        iv ??= RandomNumberGenerator.GetBytes(IvBytes);
        var ct = new byte[gz.Length];
        var tag = new byte[TagBytes];
        using (var aes = new AesGcm(AppKey(), TagBytes)) aes.Encrypt(iv, gz, ct, tag);
        var packed = new byte[1 + IvBytes + ct.Length + TagBytes];
        packed[0] = 1;
        iv.CopyTo(packed, 1);
        ct.CopyTo(packed, 1 + IvBytes);
        tag.CopyTo(packed, 1 + IvBytes + ct.Length);
        var uri = Scheme + Convert.ToBase64String(packed).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        if (uri.Length > MaxUriChars) throw new ShareException(ShareErrorKind.TooLarge, $"The share link is too large ({uri.Length} characters).");
        return uri;
    }
}
