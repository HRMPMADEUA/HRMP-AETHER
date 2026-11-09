using System.Text;
using System.Text.Json.Nodes;
using TIREXNET.Core;
using TIREXNET.Core.Engines;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;
using Xunit;

namespace TIREXNET.Tests;

public class ValidationTests
{
    private static Profile Wg(string conf) => ProfileParser.ParseText(conf).Profiles.Single();

    [Fact]
    public void WireGuard_MissingPrivateKey_IsInvalid()
    {
        var conf = ProfileParserTests.WgConf.Replace("PrivateKey = YAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\n", "");
        Assert.Contains(ConfigValidator.Validate(Wg(conf)), e => e.Contains("PrivateKey"));
    }

    [Fact]
    public void WireGuard_ScriptHooks_AreRejected()
    {
        var conf = ProfileParserTests.WgConf.Replace("MTU = 1380", "MTU = 1380\nPostUp = cmd.exe /c calc");
        Assert.Contains(ConfigValidator.Validate(Wg(conf)), e => e.Contains("PostUp"));
    }

    [Fact]
    public void WireGuard_AmneziaParameters_AreRejectedAsUnsupported()
    {
        var conf = ProfileParserTests.WgConf.Replace("MTU = 1380", "MTU = 1380\nJc = 4\nJmin = 40\nJmax = 70");
        Assert.Contains(ConfigValidator.Validate(Wg(conf)), e => e.Contains("AmneziaWG"));
    }

    [Fact]
    public void WireGuard_BadMtu_BadEndpoint_BadKeepalive()
    {
        var bad = ProfileParserTests.WgConf.Replace("MTU = 1380", "MTU = 20").Replace("vpn.example.com:51820", "vpn.example.com").Replace("PersistentKeepalive = 25", "PersistentKeepalive = x");
        var errors = ConfigValidator.Validate(Wg(bad));
        Assert.Contains(errors, e => e.Contains("MTU"));
        Assert.Contains(errors, e => e.Contains("Endpoint"));
        Assert.Contains(errors, e => e.Contains("Keepalive"));
    }

    [Fact]
    public void WireGuard_InvalidPublicKey_IsRejected()
    {
        var bad = ProfileParserTests.WgConf.Replace("xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=", "notakey");
        Assert.Contains(ConfigValidator.Validate(Wg(bad)), e => e.Contains("PublicKey"));
    }

    [Fact]
    public void Reality_WithoutPublicKey_IsInvalid()
    {
        var r = ProfileParser.ParseText("vless://11111111-2222-3333-4444-555555555555@h.example.com:443?security=reality&sni=a.com&sid=ab");
        Assert.Contains(ConfigValidator.Validate(r.Profiles.Single()), e => e.Contains("public key"));
    }

    [Fact]
    public void Shadowsocks_UnknownCipher_IsInvalid()
    {
        var cred = Convert.ToBase64String(Encoding.UTF8.GetBytes("rot13:pw"));
        var p = ProfileParser.ParseText($"ss://{cred}@h.example.com:8388").Profiles.Single();
        Assert.Contains(ConfigValidator.Validate(p), e => e.Contains("cipher"));
    }

    [Fact]
    public void HostNames_WithShellMetacharacters_AreRejected()
    {
        Assert.False(NetUtil.IsValidHostName("evil.com & calc"));
        Assert.False(NetUtil.IsValidHostName("a;b.com"));
        Assert.False(NetUtil.IsValidHostName("a b.com"));
        Assert.True(NetUtil.IsValidHostName("good-host.example.com"));
        Assert.True(NetUtil.IsValidHostName("203.0.113.7"));
    }

    [Fact]
    public void Settings_Validation()
    {
        Assert.Empty(ConfigValidator.ValidateSettings(new AppSettings()));
        Assert.NotEmpty(ConfigValidator.ValidateSettings(new AppSettings { SocksPort = 80 }));
        Assert.NotEmpty(ConfigValidator.ValidateSettings(new AppSettings { SocksPort = 10880, HttpPort = 10880 }));
        Assert.NotEmpty(ConfigValidator.ValidateSettings(new AppSettings { Dns = "not-an-ip" }));
        Assert.NotEmpty(ConfigValidator.ValidateSettings(new AppSettings { Mtu = 100 }));
        Assert.NotEmpty(ConfigValidator.ValidateSettings(new AppSettings { BypassNetworks = "10.0.0.0/40" }));
        Assert.Empty(ConfigValidator.ValidateSettings(new AppSettings { Dns = "1.1.1.1, 9.9.9.9", BypassNetworks = "192.168.0.0/16\n10.0.0.0/8", Mtu = 1400 }));
        Assert.NotEmpty(ConfigValidator.ValidateSettings(new AppSettings { SetSystemProxy = true, EnableHttpProxy = false }));
    }

    [Fact]
    public void Cidr_Parsing_MasksHostBits()
    {
        Assert.True(NetUtil.TryParseCidrV4("192.168.1.77/24", out var net, out var prefix));
        Assert.Equal("192.168.1.0", NetUtil.ToDotted(net));
        Assert.Equal(24, prefix);
        Assert.False(NetUtil.TryParseCidrV4("300.1.1.1/8", out _, out _));
        Assert.False(NetUtil.TryParseCidrV4("1.2.3.4/33", out _, out _));
    }

    // ---- Xray config generation

    private static JsonObject Build(string uri, AppSettings? s = null)
    {
        var p = ProfileParser.ParseText(uri).Profiles.Single();
        return JsonNode.Parse(XrayConfigBuilder.Build(p, s ?? new AppSettings(), 12345))!.AsObject();
    }

    [Fact]
    public void XrayConfig_Listeners_AreLoopbackOnly_WithDefaultPorts()
    {
        var cfg = Build("trojan://pw@t.example.com:443#t");
        var inbounds = cfg["inbounds"]!.AsArray();
        Assert.All(inbounds, i => Assert.Equal("127.0.0.1", i!["listen"]!.GetValue<string>()));
        var socks = inbounds.Single(i => i!["tag"]!.GetValue<string>() == "socks-in")!;
        Assert.Equal(10880, socks["port"]!.GetValue<int>());
        Assert.Equal("socks", socks["protocol"]!.GetValue<string>());
        Assert.Contains(inbounds, i => i!["tag"]!.GetValue<string>() == "http-in");
    }

    [Fact]
    public void XrayConfig_HttpProxy_CanBeDisabled()
    {
        var cfg = Build("trojan://pw@t.example.com:443#t", new AppSettings { EnableHttpProxy = false });
        Assert.DoesNotContain(cfg["inbounds"]!.AsArray(), i => i!["tag"]!.GetValue<string>() == "http-in");
    }

    [Fact]
    public void XrayConfig_UserJson_CannotOpenPublicListeners()
    {
        var json = "{\"inbounds\":[{\"listen\":\"0.0.0.0\",\"port\":1080,\"protocol\":\"socks\"}],\"outbounds\":[{\"protocol\":\"vless\",\"tag\":\"proxy\",\"settings\":{\"vnext\":[{\"address\":\"j.example.com\",\"port\":443,\"users\":[{\"id\":\"11111111-2222-3333-4444-555555555555\"}]}]}}]}";
        var p = ProfileParser.ParseText(json).Profiles.Single();
        var cfg = JsonNode.Parse(XrayConfigBuilder.Build(p, new AppSettings(), 12345))!.AsObject();
        Assert.All(cfg["inbounds"]!.AsArray(), i => Assert.Equal("127.0.0.1", i!["listen"]!.GetValue<string>()));
        Assert.DoesNotContain(cfg["inbounds"]!.AsArray(), i => i!["port"]!.GetValue<int>() == 1080);
    }

    [Fact]
    public void XrayConfig_Reality_Vless_Outbound()
    {
        var cfg = Build("vless://11111111-2222-3333-4444-555555555555@h.example.com:443?type=tcp&security=reality&sni=www.microsoft.com&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=ab12&flow=xtls-rprx-vision");
        var ob = cfg["outbounds"]![0]!;
        Assert.Equal("vless", ob["protocol"]!.GetValue<string>());
        Assert.Equal("reality", ob["streamSettings"]!["security"]!.GetValue<string>());
        Assert.Equal("ab12", ob["streamSettings"]!["realitySettings"]!["shortId"]!.GetValue<string>());
        Assert.Equal("xtls-rprx-vision", ob["settings"]!["vnext"]![0]!["users"]![0]!["flow"]!.GetValue<string>());
    }

    [Fact]
    public void XrayConfig_Contains_StatsApi_On_Loopback()
    {
        var cfg = Build("trojan://pw@t.example.com:443#t");
        Assert.NotNull(cfg["stats"]);
        Assert.Equal("api", cfg["api"]!["tag"]!.GetValue<string>());
        Assert.Contains(cfg["inbounds"]!.AsArray(), i => i!["tag"]!.GetValue<string>() == "api" && i["port"]!.GetValue<int>() == 12345);
    }

    [Fact]
    public void XrayStats_AreSummed()
    {
        var json = "{\"stat\":[{\"name\":\"outbound>>>proxy>>>traffic>>>uplink\",\"value\":\"1000\"},{\"name\":\"outbound>>>proxy>>>traffic>>>downlink\",\"value\":5000},{\"name\":\"outbound>>>direct>>>traffic>>>uplink\",\"value\":10}]}";
        var (up, down) = XrayEngine.ParseStats(json);
        Assert.Equal(1010, up);
        Assert.Equal(5000, down);
        Assert.Equal((0L, 0L), XrayEngine.ParseStats("not json"));
    }

    // ---- redaction

    [Fact]
    public void Redactor_RemovesLinksSecretsAndTokens()
    {
        Redactor.AddSecret("super-secret-pass");
        var text = Redactor.Redact("connecting vless://abc@host:1?x=y with super-secret-pass and Bearer abc.def.ghi and PrivateKey = AAAA1234");
        Assert.DoesNotContain("vless://abc", text);
        Assert.DoesNotContain("super-secret-pass", text);
        Assert.DoesNotContain("abc.def.ghi", text);
        Assert.DoesNotContain("AAAA1234", text);
    }

    // ---- storage

    [Fact]
    public void ProfileStore_Roundtrips_And_DoesNotWritePlainText()
    {
        var dir = Path.Combine(Path.GetTempPath(), "tirex-test-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            var store = new TIREXNET.Core.Services.ProfileStore(dir);
            var p = ProfileParser.ParseText("trojan://very-secret-password@t.example.com:443#t").Profiles.Single();
            Assert.Equal(1, store.Import(new[] { p }));
            Assert.Equal(0, store.Import(new[] { ProfileParser.ParseText("trojan://very-secret-password@t.example.com:443#t").Profiles.Single() }));

            var bytes = File.ReadAllBytes(Path.Combine(dir, "profiles.dat"));
            Assert.DoesNotContain("very-secret-password", Encoding.UTF8.GetString(bytes));

            var again = new TIREXNET.Core.Services.ProfileStore(dir);
            Assert.Equal("t", Assert.Single(again.All()).Name);
            Assert.True(again.Rename(p.Id, "Renamed"));
            again.Remove(p.Id);
            Assert.Empty(new TIREXNET.Core.Services.ProfileStore(dir).All());
        }
        finally
        {
            Directory.Delete(dir, true);
        }
    }

    [Fact]
    public void ApiClient_Rejects_NonHttps_BaseUrl()
    {
        Assert.Throws<ArgumentException>(() => new TIREXNET.Core.Services.ApiClient(null, "http://insecure.example.com"));
    }
}
