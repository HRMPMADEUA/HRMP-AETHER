using System.Text;

namespace TIREXNET.Core.Parsing;

/// <summary>Minimal, order-preserving model of a WireGuard .conf file.</summary>
public sealed class WgConfig
{
    public List<(string Key, string Value)> Interface { get; } = new();
    public List<List<(string Key, string Value)>> Peers { get; } = new();
    public string? Name { get; set; }

    public static readonly string[] AmneziaKeys =
        { "Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4", "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5" };

    public static readonly string[] ScriptKeys = { "PreUp", "PostUp", "PreDown", "PostDown" };

    public static bool LooksLikeWireGuard(string text)
    {
        return text.Contains("[Interface]", StringComparison.OrdinalIgnoreCase)
            && text.Contains("[Peer]", StringComparison.OrdinalIgnoreCase);
    }

    public static bool TryParse(string text, out WgConfig config, out string error)
    {
        config = new WgConfig();
        error = "";
        List<(string Key, string Value)>? current = null;
        var lineNo = 0;
        foreach (var rawLine in text.Replace("\r", "").Split('\n'))
        {
            lineNo++;
            var line = rawLine.Trim().TrimStart('﻿');
            if (line.Length == 0) continue;
            if (line.StartsWith('#') || line.StartsWith(';'))
            {
                var c = line.TrimStart('#', ';').Trim();
                if (c.StartsWith("Name", StringComparison.OrdinalIgnoreCase))
                {
                    var eq = c.IndexOf('=');
                    if (eq > 0 && c[..eq].Trim().Equals("Name", StringComparison.OrdinalIgnoreCase))
                        config.Name = c[(eq + 1)..].Trim();
                }
                continue;
            }
            if (line.StartsWith('['))
            {
                var section = line.Trim('[', ']').Trim();
                if (section.Equals("Interface", StringComparison.OrdinalIgnoreCase)) current = config.Interface;
                else if (section.Equals("Peer", StringComparison.OrdinalIgnoreCase))
                {
                    current = new List<(string, string)>();
                    config.Peers.Add(current);
                }
                else
                {
                    error = $"Line {lineNo}: unknown section [{section}].";
                    return false;
                }
                continue;
            }
            var idx = line.IndexOf('=');
            if (idx <= 0 || current == null)
            {
                error = $"Line {lineNo}: expected \"Key = Value\" inside a section.";
                return false;
            }
            var key = line[..idx].Trim();
            var value = line[(idx + 1)..].Trim();
            // Inline comments are not valid in wg-quick values, but tolerate "  # ..." suffixes.
            var hash = value.IndexOf(" #", StringComparison.Ordinal);
            if (hash >= 0) value = value[..hash].Trim();
            current.Add((key, value));
        }
        if (config.Interface.Count == 0) { error = "Missing [Interface] section."; return false; }
        if (config.Peers.Count == 0) { error = "Missing [Peer] section."; return false; }
        return true;
    }

    public static string? Get(List<(string Key, string Value)> section, string key)
    {
        var values = section.Where(kv => kv.Key.Equals(key, StringComparison.OrdinalIgnoreCase)).Select(kv => kv.Value).ToList();
        return values.Count == 0 ? null : string.Join(", ", values);
    }

    public string? GetInterface(string key) => Get(Interface, key);

    public static void Set(List<(string Key, string Value)> section, string key, string value)
    {
        section.RemoveAll(kv => kv.Key.Equals(key, StringComparison.OrdinalIgnoreCase));
        section.Add((key, value));
    }

    public static void Remove(List<(string Key, string Value)> section, string key)
    {
        section.RemoveAll(kv => kv.Key.Equals(key, StringComparison.OrdinalIgnoreCase));
    }

    public string Serialize()
    {
        var sb = new StringBuilder();
        sb.Append("[Interface]\n");
        foreach (var (k, v) in Interface) sb.Append(k).Append(" = ").Append(v).Append('\n');
        foreach (var peer in Peers)
        {
            sb.Append("\n[Peer]\n");
            foreach (var (k, v) in peer) sb.Append(k).Append(" = ").Append(v).Append('\n');
        }
        return sb.ToString();
    }

    /// <summary>Host and port of the first peer endpoint, if any.</summary>
    public bool TryGetEndpoint(out string host, out int port)
    {
        host = ""; port = 0;
        var ep = Peers.Select(p => Get(p, "Endpoint")).FirstOrDefault(e => !string.IsNullOrEmpty(e));
        return ep != null && ProfileParser.TryHostPort(ep.Split(',')[0].Trim(), out host, out port);
    }
}
