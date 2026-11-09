using System.Text;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;
using Xunit;

namespace TIREXNET.Tests;

public class ProfileParserTests
{
    private const string Uuid = "11111111-2222-3333-4444-555555555555";

    [Fact]
    public void Vless_Reality_IsParsed()
    {
        var uri = $"vless://{Uuid}@example.com:443?type=tcp&security=reality&sni=www.microsoft.com&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=6ba85179e30d4fc2&flow=xtls-rprx-vision#My%20Server";
        var r = ProfileParser.ParseText(uri);
        Assert.Empty(r.Errors);
        var p = Assert.Single(r.Profiles);
        Assert.Equal("My Server", p.Name);
        Assert.Equal("vless", p.Protocol);
        Assert.Equal("example.com", p.Server);
        Assert.Equal(443, p.Port);
        Assert.True(ProfileParser.TryParseEndpoint(uri, out var ep, out _));
        Assert.Equal("reality", ep!.Security);
        Assert.Equal("xtls-rprx-vision", ep.Flow);
        Assert.Equal("6ba85179e30d4fc2", ep.ShortId);
        Assert.Empty(ConfigValidator.Validate(p));
    }

    [Fact]
    public void Vless_WebSocketTls_IsParsed()
    {
        var uri = $"vless://{Uuid}@cdn.example.com:443?type=ws&security=tls&path=%2Fws&host=cdn.example.com&sni=cdn.example.com#ws";
        Assert.True(ProfileParser.TryParseEndpoint(uri, out var ep, out var err), err);
        Assert.Equal("ws", ep!.Network);
        Assert.Equal("/ws", ep.Path);
        Assert.Equal("tls", ep.Security);
    }

    [Fact]
    public void Vmess_Base64Json_IsParsed()
    {
        var json = $"{{\"v\":\"2\",\"ps\":\"vm-test\",\"add\":\"vm.example.com\",\"port\":\"8443\",\"id\":\"{Uuid}\",\"aid\":\"0\",\"net\":\"ws\",\"type\":\"none\",\"host\":\"vm.example.com\",\"path\":\"/p\",\"tls\":\"tls\",\"sni\":\"vm.example.com\"}}";
        var uri = "vmess://" + Convert.ToBase64String(Encoding.UTF8.GetBytes(json));
        var r = ProfileParser.ParseText(uri);
        var p = Assert.Single(r.Profiles);
        Assert.Equal("vm-test", p.Name);
        Assert.Equal(8443, p.Port);
        Assert.True(ProfileParser.TryParseEndpoint(uri, out var ep, out _));
        Assert.Equal("ws", ep!.Network);
        Assert.Equal("tls", ep.Security);
        Assert.Empty(ConfigValidator.Validate(p));
    }

    [Fact]
    public void Trojan_IsParsed_WithTlsByDefault()
    {
        var r = ProfileParser.ParseText("trojan://s3cret-pass@t.example.com:443?sni=t.example.com#tj");
        var p = Assert.Single(r.Profiles);
        Assert.Equal("trojan", p.Protocol);
        Assert.True(ProfileParser.TryParseEndpoint(p.Raw, out var ep, out _));
        Assert.Equal("tls", ep!.Security);
        Assert.Equal("s3cret-pass", ep.Id);
    }

    [Fact]
    public void Shadowsocks_Sip002_IsParsed()
    {
        var cred = Convert.ToBase64String(Encoding.UTF8.GetBytes("aes-256-gcm:pass123")).TrimEnd('=');
        var r = ProfileParser.ParseText($"ss://{cred}@ss.example.com:8388#ss");
        var p = Assert.Single(r.Profiles);
        Assert.True(ProfileParser.TryParseEndpoint(p.Raw, out var ep, out _));
        Assert.Equal("aes-256-gcm", ep!.Method);
        Assert.Equal("pass123", ep.Id);
        Assert.Empty(ConfigValidator.Validate(p));
    }

    [Fact]
    public void Shadowsocks_LegacyFullBase64_IsParsed()
    {
        var all = Convert.ToBase64String(Encoding.UTF8.GetBytes("chacha20-ietf-poly1305:pw@1.2.3.4:443"));
        var r = ProfileParser.ParseText("ss://" + all + "#legacy");
        var p = Assert.Single(r.Profiles);
        Assert.Equal("1.2.3.4", p.Server);
        Assert.Equal(443, p.Port);
    }

    [Fact]
    public void Shadowsocks_WithPlugin_IsRejected()
    {
        var cred = Convert.ToBase64String(Encoding.UTF8.GetBytes("aes-256-gcm:pw"));
        var r = ProfileParser.ParseText($"ss://{cred}@h.example.com:1?plugin=obfs-local");
        Assert.Empty(r.Profiles);
        Assert.NotEmpty(r.Errors);
    }

    [Fact]
    public void SubscriptionBody_Base64_YieldsAllLinks()
    {
        var body = $"vless://{Uuid}@a.example.com:443?security=tls#A\ntrojan://pw@b.example.com:443#B\n";
        var encoded = Convert.ToBase64String(Encoding.UTF8.GetBytes(body));
        var r = ProfileParser.ParseText(encoded);
        Assert.Equal(2, r.Profiles.Count);
    }

    [Fact]
    public void MultipleLinks_WithGarbage_ReportsErrors()
    {
        var r = ProfileParser.ParseText($"vless://{Uuid}@a.example.com:443#ok\nhttp://not-a-profile");
        Assert.Single(r.Profiles);
        Assert.Single(r.Errors);
    }

    [Fact]
    public void Empty_And_Garbage_Input_Fail_Cleanly()
    {
        Assert.NotEmpty(ProfileParser.ParseText("").Errors);
        Assert.NotEmpty(ProfileParser.ParseText("hello world").Errors);
        Assert.Empty(ProfileParser.ParseText("vless://broken").Profiles);
        Assert.Empty(ProfileParser.ParseText("vless://id@host:notaport").Profiles);
    }

    [Fact]
    public void XrayJson_IsImported()
    {
        var json = "{\"remarks\":\"json one\",\"outbounds\":[{\"protocol\":\"vless\",\"settings\":{\"vnext\":[{\"address\":\"j.example.com\",\"port\":443,\"users\":[{\"id\":\"" + Uuid + "\",\"encryption\":\"none\"}]}]}},{\"protocol\":\"freedom\",\"tag\":\"direct\"}]}";
        var r = ProfileParser.ParseText(json, "x.json");
        var p = Assert.Single(r.Profiles);
        Assert.Equal("json one", p.Name);
        Assert.Equal("xray-json", p.Protocol);
        Assert.Equal("j.example.com", p.Server);
        Assert.Equal(443, p.Port);
        Assert.Empty(ConfigValidator.Validate(p));
    }

    [Fact]
    public void XrayJson_WithoutOutbounds_IsRejected()
    {
        var r = ProfileParser.ParseText("{\"foo\":1}");
        Assert.Empty(r.Profiles);
        Assert.NotEmpty(r.Errors);
    }

    /// <summary>Always LF-joined, so tests behave the same with LF or CRLF checkouts.</summary>
    internal static string WgConf { get; } = string.Join("\n", new[]
    {
        "[Interface]",
        "PrivateKey = YAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=",
        "Address = 10.0.0.2/32, fd00::2/128",
        "DNS = 1.1.1.1, 1.0.0.1",
        "MTU = 1380",
        "",
        "[Peer]",
        "PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=",
        "AllowedIPs = 0.0.0.0/0, ::/0",
        "Endpoint = vpn.example.com:51820",
        "PersistentKeepalive = 25",
        "",
    });

    [Fact]
    public void WireGuard_Conf_IsParsed()
    {
        var r = ProfileParser.ParseText(WgConf, "office.conf");
        var p = Assert.Single(r.Profiles);
        Assert.Equal(EngineKind.WireGuard, p.Engine);
        Assert.Equal("office", p.Name);
        Assert.Equal("vpn.example.com", p.Server);
        Assert.Equal(51820, p.Port);
        Assert.Empty(ConfigValidator.Validate(p));
    }

    [Fact]
    public void WireGuard_NameComment_IsUsed()
    {
        var r = ProfileParser.ParseText("# Name = Germany 1\n" + WgConf);
        Assert.Equal("Germany 1", Assert.Single(r.Profiles).Name);
    }

    [Fact]
    public void ApiClient_AliPanelEntries_BecomeReadOnlyWireGuard()
    {
        var configs = new[] { new TIREXNET.Core.Services.RemoteConfig { Raw = WgConf, Name = "DE-1", PanelId = "ali" } };
        var skipped = new List<string>();
        var list = TIREXNET.Core.Services.ApiClient.ToProfiles(configs, "alipanel", skipped);
        var p = Assert.Single(list);
        Assert.Equal(EngineKind.WireGuard, p.Engine);
        Assert.True(p.ReadOnly);
        Assert.Equal("alipanel", p.Source);
        Assert.Equal("DE-1", p.Name);
        Assert.Empty(skipped);
    }

    [Fact]
    public void ApiClient_AmneziaEntries_AreSkippedWithReason()
    {
        var configs = new[] { new TIREXNET.Core.Services.RemoteConfig { Raw = WgConf, Name = "AWG", Engine = "amneziawg" } };
        var skipped = new List<string>();
        var list = TIREXNET.Core.Services.ApiClient.ToProfiles(configs, "alipanel", skipped);
        Assert.Empty(list);
        Assert.Single(skipped);
    }

    [Fact]
    public void WireGuard_Conf_WithCrLf_IsParsed()
    {
        var crlf = WgConf.Replace("\n", "\r\n");
        var p = Assert.Single(ProfileParser.ParseText(crlf, "crlf.conf").Profiles);
        Assert.Equal("vpn.example.com", p.Server);
        Assert.Empty(ConfigValidator.Validate(p));
    }

    [Theory]
    [InlineData("vless://11111111-2222-3333-4444-555555555555@h.example.com:70000#x")]
    [InlineData("vless://11111111-2222-3333-4444-555555555555@h.example.com:0#x")]
    [InlineData("vless://11111111-2222-3333-4444-555555555555@h.example.com#x")]
    [InlineData("vless://@h.example.com:443#x")]
    [InlineData("trojan://h.example.com:443")]
    [InlineData("vmess://!!!not-base64!!!")]
    [InlineData("ss://")]
    public void Malformed_Links_AreRejected(string uri)
    {
        var r = ProfileParser.ParseText(uri);
        Assert.Empty(r.Profiles);
        Assert.NotEmpty(r.Errors);
    }

    [Fact]
    public void Vless_WithIPv6Server_IsParsed()
    {
        var p = Assert.Single(ProfileParser.ParseText("vless://11111111-2222-3333-4444-555555555555@[2001:db8::1]:443?security=tls#v6").Profiles);
        Assert.Equal("2001:db8::1", p.Server);
        Assert.Equal(443, p.Port);
        Assert.Empty(ConfigValidator.Validate(p));
    }

    [Fact]
    public void Unsupported_Transport_IsReportedNotImported()
    {
        var r = ProfileParser.ParseText("vless://11111111-2222-3333-4444-555555555555@h.example.com:443?type=quic#q");
        Assert.Empty(r.Profiles);
        Assert.Contains(r.Errors, e => e.Contains("quic"));
    }

    [Fact]
    public void XrayJsonArray_YieldsOneProfilePerObject()
    {
        const string one = "{\"outbounds\":[{\"protocol\":\"trojan\",\"settings\":{\"servers\":[{\"address\":\"a.example.com\",\"port\":443,\"password\":\"pw\"}]}}]}";
        var r = ProfileParser.ParseText("[" + one + "," + one.Replace("a.example", "b.example") + "]", "multi.json");
        Assert.Equal(2, r.Profiles.Count);
        Assert.Equal(new[] { "a.example.com", "b.example.com" }, r.Profiles.Select(p => p.Server).ToArray());
    }
}
