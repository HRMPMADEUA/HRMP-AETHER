using System.Globalization;
using System.Text.RegularExpressions;

namespace TIREXNET.Core.Parsing;

/// <summary>
/// AmneziaWG = WireGuard plus traffic obfuscation (junk packets Jc/Jmin/Jmax, padding S1-S4, custom message types H1-H4 and
/// signature-packet chains I1-I5). The parameters live in the [Interface] section of an awg-quick style file.
/// </summary>
public static class AmneziaConfig
{
    private static readonly Regex SignatureKey = new("^I[1-5]$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    // Chain of tags: <b 0xHEX> <c> <t> <r N> <rc N> <rd N>  (same grammar the Android app accepts).
    private static readonly Regex SignatureValue = new(@"^(<(b\s+0x[0-9a-f]+|c|t|r\s+\d+|rc\s+\d+|rd\s+\d+)>)+$",
        RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);

    private static readonly Regex HeaderValue = new(@"^\d{1,10}(-\d{1,10})?$", RegexOptions.CultureInvariant);

    /// <summary>True when the [Interface] section carries at least one AmneziaWG parameter.</summary>
    public static bool HasObfuscation(WgConfig cfg) =>
        WgConfig.AmneziaKeys.Any(k => cfg.Interface.Any(kv => kv.Key.Equals(k, StringComparison.OrdinalIgnoreCase)));

    public static bool HasObfuscation(string text) =>
        WgConfig.TryParse(text, out var cfg, out _) && HasObfuscation(cfg);

    /// <summary>
    /// Drops I1..I5 lines that are not a valid signature chain. Some panels write "I1 = 0" or an empty value to mean "disabled";
    /// the engine would reject that literal value, and dropping the line keeps the feature disabled. Returns the notes for the log.
    /// </summary>
    public static string Sanitize(string raw, out List<string> notes)
    {
        notes = new List<string>();
        var inInterface = false;
        var kept = new List<string>();
        foreach (var line in raw.Replace("\r\n", "\n").Replace('\r', '\n').Split('\n'))
        {
            var t = line.Trim();
            if (t.StartsWith('[')) inInterface = t.Equals("[Interface]", StringComparison.OrdinalIgnoreCase);
            if (inInterface && t.Contains('=') && !t.StartsWith('#') && !t.StartsWith(';'))
            {
                var idx = t.IndexOf('=');
                var key = t[..idx].Trim();
                var value = t[(idx + 1)..].Trim();
                if (SignatureKey.IsMatch(key) && !SignatureValue.IsMatch(value))
                {
                    notes.Add($"{key} ignored: value is not a valid signature packet (empty or 0 means disabled).");
                    continue;
                }
            }
            kept.Add(line);
        }
        return string.Join("\n", kept);
    }

    /// <summary>Validates the obfuscation parameters. Standard WireGuard rules are checked by the caller.</summary>
    public static List<string> Validate(WgConfig cfg)
    {
        var errors = new List<string>();
        if (!HasObfuscation(cfg))
        {
            errors.Add("This profile has no AmneziaWG obfuscation parameters (Jc, Jmin, Jmax, S1, S2, H1-H4). Use the WireGuard engine for plain WireGuard.");
            return errors;
        }

        long? Num(string key, long min, long max)
        {
            var v = cfg.GetInterface(key);
            if (v == null) return null;
            if (!long.TryParse(v.Trim(), NumberStyles.None, CultureInfo.InvariantCulture, out var n) || n < min || n > max)
            {
                errors.Add($"{key} must be a whole number between {min} and {max}.");
                return null;
            }
            return n;
        }

        var jc = Num("Jc", 0, 128);
        var jmin = Num("Jmin", 0, 1280);
        var jmax = Num("Jmax", 0, 1280);
        if (jmin.HasValue && jmax.HasValue && jmin > jmax) errors.Add("Jmin must not be larger than Jmax.");
        if (jc is > 0 && !(jmin.HasValue && jmax.HasValue)) { /* the engine applies its own defaults */ }

        var s1 = Num("S1", 0, 1132);
        var s2 = Num("S2", 0, 1188);
        Num("S3", 0, 1280);
        Num("S4", 0, 1280);
        if (s1.HasValue && s2.HasValue && s1 + 56 == s2) errors.Add("S1 + 56 must not equal S2 (the packet sizes would be indistinguishable).");

        var headers = new List<string>();
        foreach (var k in new[] { "H1", "H2", "H3", "H4" })
        {
            var v = cfg.GetInterface(k);
            if (v == null) continue;
            v = v.Trim();
            if (!HeaderValue.IsMatch(v)) { errors.Add($"{k} must be a number or a range like 100-200."); continue; }
            var ok = true;
            foreach (var part in v.Split('-'))
                if (!uint.TryParse(part, NumberStyles.None, CultureInfo.InvariantCulture, out _)) ok = false;
            if (!ok) { errors.Add($"{k} is out of range (0 to 4294967295)."); continue; }
            headers.Add(v);
        }
        if (headers.Count != headers.Distinct().Count()) errors.Add("H1-H4 must all be different.");

        foreach (var kv in cfg.Interface)
        {
            if (SignatureKey.IsMatch(kv.Key) && !SignatureValue.IsMatch(kv.Value.Trim()))
                errors.Add($"{kv.Key} is not a valid signature chain such as <b 0x1234><r 16>. Remove the line or fix it.");
        }
        return errors;
    }
}
