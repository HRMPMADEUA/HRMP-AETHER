using System.Text.Json;
using System.Text.Json.Nodes;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;

namespace TIREXNET.Core.Engines;

public static class XrayConfigBuilder
{
    public const string OutboundTag = "proxy";

    /// <summary>Builds the complete Xray configuration. Local listeners are always bound to 127.0.0.1.</summary>
    public static string Build(Profile profile, AppSettings settings, int apiPort, IDictionary<string, string>? hostMap = null)
    {
        var root = profile.Protocol == "xray-json" ? BuildFromJson(profile) : BuildFromUri(profile);

        root["log"] = new JsonObject { ["loglevel"] = "warning" };

        var inbounds = new JsonArray
        {
            new JsonObject
            {
                ["tag"] = "socks-in",
                ["listen"] = "127.0.0.1",
                ["port"] = settings.SocksPort,
                ["protocol"] = "socks",
                ["settings"] = new JsonObject { ["auth"] = "noauth", ["udp"] = true, ["ip"] = "127.0.0.1" },
            },
        };
        if (settings.EnableHttpProxy)
        {
            inbounds.Add(new JsonObject
            {
                ["tag"] = "http-in",
                ["listen"] = "127.0.0.1",
                ["port"] = settings.HttpPort,
                ["protocol"] = "http",
                ["settings"] = new JsonObject(),
            });
        }
        inbounds.Add(new JsonObject
        {
            ["tag"] = "api",
            ["listen"] = "127.0.0.1",
            ["port"] = apiPort,
            ["protocol"] = "dokodemo-door",
            ["settings"] = new JsonObject { ["address"] = "127.0.0.1" },
        });
        root["inbounds"] = inbounds;

        // Traffic statistics (read through the local-only API inbound).
        root["stats"] = new JsonObject();
        root["api"] = new JsonObject { ["tag"] = "api", ["services"] = new JsonArray("StatsService") };
        var policy = root["policy"] as JsonObject;
        if (policy == null) { policy = new JsonObject(); root["policy"] = policy; }
        var system = policy["system"] as JsonObject;
        if (system == null) { system = new JsonObject(); policy["system"] = system; }
        system["statsOutboundUplink"] = true;
        system["statsOutboundDownlink"] = true;

        var routing = root["routing"] as JsonObject;
        if (routing == null) { routing = new JsonObject(); root["routing"] = routing; }
        var rules = routing["rules"] as JsonArray;
        if (rules == null) { rules = new JsonArray(); routing["rules"] = rules; }
        rules.Insert(0, new JsonObject { ["type"] = "field", ["inboundTag"] = new JsonArray("api"), ["outboundTag"] = "api" });
        if (routing["domainStrategy"] == null) routing["domainStrategy"] = "AsIs";

        var dnsServers = ConfigValidator.SplitList(settings.Dns);
        var dns = root["dns"] as JsonObject;
        if (dnsServers.Count > 0 && dns == null)
        {
            var arr = new JsonArray();
            foreach (var d in dnsServers) arr.Add(d);
            dns = new JsonObject { ["servers"] = arr };
        }
        if (hostMap != null && hostMap.Count > 0)
        {
            dns ??= new JsonObject { ["servers"] = new JsonArray("1.1.1.1") };
            var hosts = dns["hosts"] as JsonObject;
            var isNew = hosts == null;
            hosts ??= new JsonObject();
            foreach (var kv in hostMap) hosts[kv.Key] = kv.Value;
            if (isNew) dns["hosts"] = hosts;
        }
        if (dns != null && root["dns"] == null) root["dns"] = dns;

        return root.ToJsonString(new JsonSerializerOptions { WriteIndented = false });
    }

    private static JsonObject BuildFromJson(Profile profile)
    {
        var node = JsonNode.Parse(profile.Raw) as JsonObject ?? throw new EngineException("The Xray JSON is not an object.");
        // Remove everything we own so user JSON can never open public listeners or write files.
        node.Remove("inbounds");
        node.Remove("inbound");
        node.Remove("inboundDetour");
        node.Remove("api");
        node.Remove("stats");
        node.Remove("log");
        return node;
    }

    private static JsonObject BuildFromUri(Profile profile)
    {
        if (!ProfileParser.TryParseEndpoint(profile.Raw, out var ep, out var err) || ep == null)
            throw new EngineException(err);
        var outbound = new JsonObject { ["tag"] = OutboundTag, ["protocol"] = ep.Protocol };
        switch (ep.Protocol)
        {
            case "vless":
                outbound["settings"] = new JsonObject
                {
                    ["vnext"] = new JsonArray(new JsonObject
                    {
                        ["address"] = ep.Host,
                        ["port"] = ep.Port,
                        ["users"] = new JsonArray(new JsonObject
                        {
                            ["id"] = ep.Id,
                            ["encryption"] = string.IsNullOrEmpty(ep.Encryption) ? "none" : ep.Encryption,
                            ["flow"] = ep.Flow,
                        }),
                    }),
                };
                break;
            case "vmess":
                outbound["settings"] = new JsonObject
                {
                    ["vnext"] = new JsonArray(new JsonObject
                    {
                        ["address"] = ep.Host,
                        ["port"] = ep.Port,
                        ["users"] = new JsonArray(new JsonObject
                        {
                            ["id"] = ep.Id,
                            ["alterId"] = ep.AlterId,
                            ["security"] = string.IsNullOrEmpty(ep.VmessSecurity) ? "auto" : ep.VmessSecurity,
                        }),
                    }),
                };
                break;
            case "trojan":
                outbound["settings"] = new JsonObject
                {
                    ["servers"] = new JsonArray(new JsonObject { ["address"] = ep.Host, ["port"] = ep.Port, ["password"] = ep.Id }),
                };
                break;
            case "shadowsocks":
                outbound["settings"] = new JsonObject
                {
                    ["servers"] = new JsonArray(new JsonObject { ["address"] = ep.Host, ["port"] = ep.Port, ["method"] = ep.Method, ["password"] = ep.Id }),
                };
                break;
            default:
                throw new EngineException($"Protocol {ep.Protocol} is not supported.");
        }
        if (ep.Protocol != "shadowsocks") outbound["streamSettings"] = BuildStream(ep);

        return new JsonObject
        {
            ["outbounds"] = new JsonArray(outbound),
        };
    }

    private static JsonObject BuildStream(XrayEndpoint ep)
    {
        var ss = new JsonObject { ["network"] = ep.Network };
        switch (ep.Network)
        {
            case "tcp":
                if (ep.HeaderType == "http")
                {
                    ss["tcpSettings"] = new JsonObject
                    {
                        ["header"] = new JsonObject
                        {
                            ["type"] = "http",
                            ["request"] = new JsonObject
                            {
                                ["path"] = new JsonArray(string.IsNullOrEmpty(ep.Path) ? "/" : ep.Path),
                                ["headers"] = new JsonObject { ["Host"] = new JsonArray(string.IsNullOrEmpty(ep.HostHeader) ? ep.Host : ep.HostHeader) },
                            },
                        },
                    };
                }
                break;
            case "ws":
                var ws = new JsonObject { ["path"] = string.IsNullOrEmpty(ep.Path) ? "/" : ep.Path };
                if (!string.IsNullOrEmpty(ep.HostHeader)) ws["headers"] = new JsonObject { ["Host"] = ep.HostHeader };
                ss["wsSettings"] = ws;
                break;
            case "grpc":
                ss["grpcSettings"] = new JsonObject
                {
                    ["serviceName"] = string.IsNullOrEmpty(ep.ServiceName) ? ep.Path : ep.ServiceName,
                    ["multiMode"] = ep.Mode == "multi",
                };
                break;
            case "http":
                var h2 = new JsonObject { ["path"] = string.IsNullOrEmpty(ep.Path) ? "/" : ep.Path };
                if (!string.IsNullOrEmpty(ep.HostHeader)) h2["host"] = new JsonArray(ep.HostHeader.Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries).Select(x => (JsonNode?)x).ToArray());
                ss["httpSettings"] = h2;
                break;
            case "httpupgrade":
                var hu = new JsonObject { ["path"] = string.IsNullOrEmpty(ep.Path) ? "/" : ep.Path };
                if (!string.IsNullOrEmpty(ep.HostHeader)) hu["host"] = ep.HostHeader;
                ss["httpupgradeSettings"] = hu;
                break;
            case "xhttp":
                var xh = new JsonObject { ["path"] = string.IsNullOrEmpty(ep.Path) ? "/" : ep.Path };
                if (!string.IsNullOrEmpty(ep.HostHeader)) xh["host"] = ep.HostHeader;
                if (!string.IsNullOrEmpty(ep.Mode)) xh["mode"] = ep.Mode;
                if (!string.IsNullOrWhiteSpace(ep.Extra))
                {
                    // Invalid data is reported, never silently dropped.
                    try
                    {
                        if (JsonNode.Parse(ep.Extra) is not JsonObject extra)
                            throw new EngineException("The xhttp \"extra\" setting must be a JSON object.");
                        xh["extra"] = extra;
                    }
                    catch (JsonException ex)
                    {
                        throw new EngineException("The xhttp \"extra\" setting is not valid JSON: " + ex.Message, ex);
                    }
                }
                ss["xhttpSettings"] = xh;
                break;
            case "kcp":
                var kcp = new JsonObject { ["header"] = new JsonObject { ["type"] = string.IsNullOrEmpty(ep.HeaderType) ? "none" : ep.HeaderType } };
                if (!string.IsNullOrEmpty(ep.Seed)) kcp["seed"] = ep.Seed;
                ss["kcpSettings"] = kcp;
                break;
        }

        if (ep.Security == "tls")
        {
            ss["security"] = "tls";
            var tls = new JsonObject { ["serverName"] = string.IsNullOrEmpty(ep.Sni) ? (string.IsNullOrEmpty(ep.HostHeader) ? ep.Host : ep.HostHeader) : ep.Sni };
            if (!string.IsNullOrEmpty(ep.Alpn))
                tls["alpn"] = new JsonArray(ep.Alpn.Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries).Select(x => (JsonNode?)x).ToArray());
            if (!string.IsNullOrEmpty(ep.Fingerprint)) tls["fingerprint"] = ep.Fingerprint;
            if (ep.AllowInsecure) tls["allowInsecure"] = true;
            ss["tlsSettings"] = tls;
        }
        else if (ep.Security == "reality")
        {
            ss["security"] = "reality";
            ss["realitySettings"] = new JsonObject
            {
                ["serverName"] = string.IsNullOrEmpty(ep.Sni) ? ep.Host : ep.Sni,
                ["fingerprint"] = string.IsNullOrEmpty(ep.Fingerprint) ? "chrome" : ep.Fingerprint,
                ["publicKey"] = ep.PublicKey,
                ["shortId"] = ep.ShortId,
                ["spiderX"] = string.IsNullOrEmpty(ep.SpiderX) ? "/" : ep.SpiderX,
            };
        }
        return ss;
    }

    /// <summary>Every server host the profile talks to (used to keep tunnel routes from looping).</summary>
    public static List<string> CollectServerHosts(Profile profile)
    {
        if (profile.Protocol == "xray-json")
        {
            return JsonNode.Parse(profile.Raw) is JsonObject o ? ProfileParser.ExtractJsonHosts(o, out _) : new List<string>();
        }
        return string.IsNullOrEmpty(profile.Server) ? new List<string>() : new List<string> { profile.Server };
    }
}
