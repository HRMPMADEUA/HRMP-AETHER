using System.Text.RegularExpressions;

namespace TIREXNET.Core;

/// <summary>Removes secrets from anything that is written to the log.</summary>
public static class Redactor
{
    private static readonly object Gate = new();
    private static readonly HashSet<string> Secrets = new(StringComparer.Ordinal);

    private static readonly Regex UriRx = new(@"\b(vless|vmess|trojan|ss|wireguard|slipnet-enc|slipnet)://\S+", RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private static readonly Regex BearerRx = new(@"(Bearer\s+)[A-Za-z0-9\-\._~\+/]+=*", RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private static readonly Regex UuidRx = new(@"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b", RegexOptions.Compiled);
    private static readonly Regex KeyRx = new(@"(?i)\b(PrivateKey|PresharedKey|password|passwd|token|secret)(\s*[=:]\s*)\S+", RegexOptions.Compiled);

    public static void AddSecret(string? secret)
    {
        if (string.IsNullOrEmpty(secret) || secret.Length < 4) return;
        lock (Gate) Secrets.Add(secret);
    }

    public static void Clear()
    {
        lock (Gate) Secrets.Clear();
    }

    public static string Redact(string text)
    {
        if (string.IsNullOrEmpty(text)) return text;
        string[] secrets;
        lock (Gate) secrets = Secrets.ToArray();
        foreach (var s in secrets) text = text.Replace(s, "***", StringComparison.Ordinal);
        text = UriRx.Replace(text, "$1://<redacted>");
        text = BearerRx.Replace(text, "$1***");
        text = KeyRx.Replace(text, "$1$2***");
        text = UuidRx.Replace(text, "<id>");
        return text;
    }
}

public static class Log
{
    private const int MaxLines = 2000;
    private static readonly object Gate = new();
    private static readonly LinkedList<string> Buffer = new();
    private static int _writes;

    public static event Action<string>? LineAdded;

    public static string LogFilePath => Path.Combine(AppPaths.LogDir, "tirexnet.log");

    public static void Info(string message) => Write("INFO", message);
    public static void Warn(string message) => Write("WARN", message);
    public static void Error(string message) => Write("ERROR", message);

    public static void Write(string level, string message)
    {
        var line = $"{DateTime.Now:HH:mm:ss} [{level}] {Redactor.Redact(message)}";
        lock (Gate)
        {
            Buffer.AddLast(line);
            while (Buffer.Count > MaxLines) Buffer.RemoveFirst();
            try
            {
                if (++_writes % 200 == 0)
                {
                    var fi = new FileInfo(LogFilePath);
                    if (fi.Exists && fi.Length > 2_000_000)
                    {
                        var old = LogFilePath + ".old";
                        if (File.Exists(old)) File.Delete(old);
                        File.Move(LogFilePath, old);
                    }
                }
                File.AppendAllText(LogFilePath, line + Environment.NewLine);
            }
            catch
            {
                // Logging must never break the app.
            }
        }
        LineAdded?.Invoke(line);
    }

    public static IReadOnlyList<string> Snapshot()
    {
        lock (Gate) return Buffer.ToList();
    }

    public static void Clear()
    {
        lock (Gate) Buffer.Clear();
    }
}
