using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;

namespace TIREXNET.Core.Services;

public sealed class ApiException : Exception
{
    public int StatusCode { get; }
    public ApiException(int code, string body) : base($"HTTP {code}: {body}") { StatusCode = code; }
}

public sealed class AccountInfo
{
    public string Username { get; init; } = "";
    public string PanelUsername { get; init; } = "";
    public string Status { get; init; } = "";
    public long UsedBytes { get; init; }
    public long LimitBytes { get; init; }
    public string Expire { get; init; } = "";
}

public sealed class RemoteConfig
{
    public string Raw { get; init; } = "";
    public string Name { get; init; } = "";
    public string PanelId { get; init; } = "";
    public string Engine { get; init; } = "";
}

/// <summary>
/// Client for the existing TIREXNET backend. Only endpoints that the Android app already uses are implemented:
/// POST /api/login, GET /api/me, GET /api/me/configs, POST /api/logout.
/// </summary>
public sealed class ApiClient : IDisposable
{
    public const string DefaultBaseUrl = "https://netherrap.extraservices.ir";
    public const string PanelPasarGuard = "pasarguard";
    public const string PanelAliPanel = "alipanel";

    private readonly HttpClient _http;
    private readonly string _base;

    public ApiClient(HttpMessageHandler? handler = null, string baseUrl = DefaultBaseUrl)
    {
        if (!baseUrl.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("The API base URL must use HTTPS.", nameof(baseUrl));
        _base = baseUrl.TrimEnd('/');
        _http = handler == null ? new HttpClient() : new HttpClient(handler);
        _http.Timeout = TimeSpan.FromSeconds(15);
    }

    private async Task<JsonObject> SendAsync(HttpMethod method, string path, string? bearer, JsonObject? body, CancellationToken ct)
    {
        using var req = new HttpRequestMessage(method, _base + path);
        req.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        if (!string.IsNullOrEmpty(bearer)) req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
        if (body != null) req.Content = new StringContent(body.ToJsonString(), Encoding.UTF8, "application/json");
        using var resp = await _http.SendAsync(req, ct).ConfigureAwait(false);
        var text = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        if (!resp.IsSuccessStatusCode)
        {
            var shown = text.Length > 300 ? text[..300] : text;
            throw new ApiException((int)resp.StatusCode, shown);
        }
        try
        {
            return JsonNode.Parse(text) as JsonObject ?? throw new JsonException("The server returned an unexpected response.");
        }
        catch (JsonException)
        {
            throw new ApiException((int)resp.StatusCode, "The server returned an unreadable response.");
        }
    }

    public async Task<string> LoginAsync(string username, string password, string panel, CancellationToken ct = default)
    {
        var body = new JsonObject { ["username"] = username, ["password"] = password, ["panel"] = panel };
        var resp = await SendAsync(HttpMethod.Post, "/api/login", null, body, ct).ConfigureAwait(false);
        var token = resp["token"]?.ToString() ?? "";
        if (string.IsNullOrWhiteSpace(token))
            throw new ApiException(200, resp["error"]?.ToString() ?? "Login token is missing.");
        Redactor.AddSecret(token);
        return token;
    }

    public async Task<AccountInfo> GetAccountAsync(string token, CancellationToken ct = default)
    {
        var data = await SendAsync(HttpMethod.Get, "/api/me", token, null, ct).ConfigureAwait(false);
        var sub = data["subscription"] as JsonObject ?? new JsonObject();
        return new AccountInfo
        {
            Username = data["username"]?.ToString() ?? "Account",
            PanelUsername = sub["username"]?.ToString() ?? "—",
            Status = sub["status"]?.ToString() ?? "Unknown",
            UsedBytes = ToLong(sub["usedTraffic"]),
            LimitBytes = ToLong(sub["dataLimit"]),
            Expire = sub["expire"] == null ? "" : sub["expire"]!.ToString(),
        };
    }

    private static long ToLong(JsonNode? n)
    {
        if (n == null) return 0;
        return long.TryParse(n.ToString(), System.Globalization.NumberStyles.Any, System.Globalization.CultureInfo.InvariantCulture, out var v)
            ? v
            : (double.TryParse(n.ToString(), System.Globalization.NumberStyles.Any, System.Globalization.CultureInfo.InvariantCulture, out var d) ? (long)d : 0);
    }

    public async Task<List<RemoteConfig>> GetConfigsAsync(string token, string panelId, CancellationToken ct = default)
    {
        var data = await SendAsync(HttpMethod.Get, "/api/me/configs", token, null, ct).ConfigureAwait(false);
        var arr = data["configs"] as JsonArray ?? data["profiles"] as JsonArray ?? data["items"] as JsonArray
            ?? throw new ApiException(200, "The server response has no configs list.");
        var list = new List<RemoteConfig>();
        foreach (var item in arr)
        {
            string raw = "";
            string name = "", engine = "", pid = panelId;
            if (item is JsonValue v) raw = v.ToString();
            else if (item is JsonObject o)
            {
                foreach (var key in new[] { "config", "uri", "raw", "content", "link" })
                {
                    var s = o[key]?.ToString();
                    if (!string.IsNullOrWhiteSpace(s)) { raw = s!; break; }
                }
                foreach (var key in new[] { "name", "remark", "remarks", "title" })
                {
                    var s = o[key]?.ToString();
                    if (!string.IsNullOrWhiteSpace(s)) { name = s!; break; }
                }
                engine = o["engine"]?.ToString() ?? o["protocol"]?.ToString() ?? "";
                var p = o["panelId"]?.ToString() ?? o["panel_id"]?.ToString();
                if (!string.IsNullOrWhiteSpace(p)) pid = p!;
            }
            raw = raw.Trim();
            if (raw.Length == 0) continue;
            list.Add(new RemoteConfig { Raw = raw, Name = name, PanelId = pid, Engine = engine });
        }
        return list;
    }

    public async Task LogoutAsync(string token, CancellationToken ct = default)
    {
        await SendAsync(HttpMethod.Post, "/api/logout", token, new JsonObject(), ct).ConfigureAwait(false);
    }

    /// <summary>
    /// Turns the API list into profiles. AliPanel entries are always WireGuard-family (WireGuard, or AmneziaWG when the config carries obfuscation parameters).
    /// Entries this build cannot parse are reported in <paramref name="skipped"/>.
    /// </summary>
    public static List<Profile> ToProfiles(IEnumerable<RemoteConfig> configs, string panel, List<string> skipped)
    {
        var result = new List<Profile>();
        var index = 0;
        foreach (var c in configs)
        {
            index++;
            var engineHint = c.Engine.Trim().ToUpperInvariant().Replace('-', '_').Replace(' ', '_');
            var asWg = panel == PanelAliPanel || c.PanelId.Equals("ali", StringComparison.OrdinalIgnoreCase)
                || c.PanelId.Equals("alipanel", StringComparison.OrdinalIgnoreCase)
                || engineHint is "WIREGUARD" or "WIRE_GUARD" or "WG" or "WIREGUARD_TUNNEL" or "AMNEZIAWG" or "AMNEZIA_WG" or "AWG"
                || WgConfig.LooksLikeWireGuard(c.Raw);
            var parsed = ProfileParser.ParseText(c.Raw, null);
            if (parsed.Profiles.Count == 0)
            {
                skipped.Add($"{(string.IsNullOrEmpty(c.Name) ? $"Config {index}" : c.Name)}: {parsed.Errors.FirstOrDefault() ?? "unsupported format"}");
                continue;
            }
            foreach (var p in parsed.Profiles)
            {
                if (asWg && p.Engine is not (EngineKind.WireGuard or EngineKind.AmneziaWG))
                {
                    skipped.Add($"{p.Name}: expected a WireGuard configuration.");
                    continue;
                }
                p.Source = panel;
                p.ReadOnly = true;
                if (!string.IsNullOrWhiteSpace(c.Name)) p.Name = c.Name.Trim();
                result.Add(p);
            }
        }
        return result;
    }

    public void Dispose() => _http.Dispose();
}
