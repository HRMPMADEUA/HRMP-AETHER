using System.Text;
using TIREXNET.Core.Engines;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;
using Xunit;

namespace TIREXNET.Tests;

/// <summary>Parsers, validation, config generation and engine selection for AmneziaWG, OpenVPN and SlipNet.</summary>
public class EngineTests
{
    private static string AwgConf(string extra = "Jc = 4\nJmin = 40\nJmax = 70\nS1 = 15\nS2 = 20\nH1 = 1\nH2 = 2\nH3 = 3\nH4 = 4") =>
        ProfileParserTests.WgConf.Replace("MTU = 1380", "MTU = 1380\n" + extra);

    private static Profile Awg(string raw) => new() { Name = "t", Engine = EngineKind.AmneziaWG, Protocol = "amneziawg", Raw = raw };

    // ------------------------------------------------------------------ AmneziaWG

    [Fact]
    public void Amnezia_Conf_IsDetectedAndValid()
    {
        var r = ProfileParser.ParseText(AwgConf(), "awg.conf");
        var p = Assert.Single(r.Profiles);
        Assert.Equal(EngineKind.AmneziaWG, p.Engine);
        Assert.Equal("AW", p.ProtocolCode);
        Assert.Equal("AmneziaWG", p.Badge);
        Assert.Equal("vpn.example.com", p.Server);
        Assert.Empty(ConfigValidator.Validate(p));
    }

    [Fact]
    public void PlainWireGuard_StaysWireGuard()
    {
        var p = Assert.Single(ProfileParser.ParseText(ProfileParserTests.WgConf).Profiles);
        Assert.Equal(EngineKind.WireGuard, p.Engine);
    }

    [Fact]
    public void Amnezia_EngineWithoutObfuscation_IsRejected()
    {
        Assert.Contains(ConfigValidator.Validate(Awg(ProfileParserTests.WgConf)), e => e.Contains("no AmneziaWG obfuscation"));
    }

    [Fact]
    public void Amnezia_BadParameters_AreReported()
    {
        Assert.Contains(ConfigValidator.Validate(Awg(AwgConf("Jc = 4\nJmin = 90\nJmax = 70"))), e => e.Contains("Jmin"));
        Assert.Contains(ConfigValidator.Validate(Awg(AwgConf("Jc = 4\nS1 = 10\nS2 = 66"))), e => e.Contains("S1 + 56"));
        Assert.Contains(ConfigValidator.Validate(Awg(AwgConf("Jc = 4\nH1 = 5\nH2 = 5"))), e => e.Contains("different"));
        Assert.Contains(ConfigValidator.Validate(Awg(AwgConf("Jc = 999"))), e => e.Contains("Jc"));
        Assert.Contains(ConfigValidator.Validate(Awg(AwgConf("Jc = 4\nH1 = abc"))), e => e.Contains("H1"));
    }

    [Fact]
    public void Amnezia_HeaderRanges_AreAccepted()
    {
        Assert.Empty(ConfigValidator.Validate(Awg(AwgConf("Jc = 4\nH1 = 100-200\nH2 = 300-400\nH3 = 500-600\nH4 = 700-800"))));
    }

    [Fact]
    public void Amnezia_DisabledSignatureLines_AreDropped_ValidOnesKept()
    {
        var raw = AwgConf("Jc = 4\nI1 = 0\nI2 = <b 0x1234><r 16>\nI3 =");
        var clean = AmneziaConfig.Sanitize(raw, out var notes);
        Assert.DoesNotContain("I1 = 0", clean);
        Assert.DoesNotContain("I3 =", clean);
        Assert.Contains("I2 = <b 0x1234><r 16>", clean);
        Assert.Equal(2, notes.Count);
        Assert.Empty(ConfigValidator.Validate(Awg(clean)));
    }

    [Fact]
    public void Amnezia_Scripts_AreRejected()
    {
        Assert.Contains(ConfigValidator.Validate(Awg(AwgConf("Jc = 4\nPostUp = calc.exe"))), e => e.Contains("PostUp"));
    }

    [Fact]
    public void Amnezia_KeysAreSecrets()
    {
        Assert.Contains("YAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=", ProfileParser.CollectSecrets(Awg(AwgConf())));
    }

    // ------------------------------------------------------------------ OpenVPN

    private const string Ovpn =
        "client\ndev tun\nproto udp\nremote vpn.example.com 1194\nresolv-retry infinite\nnobind\n" +
        "<ca>\n-----BEGIN CERTIFICATE-----\nMIIBAAAA\n-----END CERTIFICATE-----\n</ca>\n" +
        "<cert>\n-----BEGIN CERTIFICATE-----\nMIICAAAA\n-----END CERTIFICATE-----\n</cert>\n" +
        "<key>\n-----BEGIN PRIVATE KEY-----\nSECRETKEYMATERIAL1234567890\n-----END PRIVATE KEY-----\n</key>\n";

    private static Profile Ov(string raw) => new() { Name = "o", Engine = EngineKind.OpenVpn, Protocol = "openvpn", Raw = raw };

    [Fact]
    public void OpenVpn_IsDetectedParsedAndValid()
    {
        var r = ProfileParser.ParseText(Ovpn, "work.ovpn");
        var p = Assert.Single(r.Profiles);
        Assert.Equal(EngineKind.OpenVpn, p.Engine);
        Assert.Equal("OV", p.ProtocolCode);
        Assert.Equal("work", p.Name);
        Assert.Equal("vpn.example.com", p.Server);
        Assert.Equal(1194, p.Port);
        Assert.Empty(ConfigValidator.Validate(p));
    }

    [Fact]
    public void OpenVpn_DefaultPortAndProto_AreApplied()
    {
        var remotes = OpenVpnConfig.Remotes("client\nproto tcp\nport 443\nremote a.example.com\nremote b.example.com 8443 udp\n");
        Assert.Equal(new OpenVpnConfig.Remote("a.example.com", 443, "tcp"), remotes[0]);
        Assert.Equal(new OpenVpnConfig.Remote("b.example.com", 8443, "udp"), remotes[1]);
    }

    [Theory]
    [InlineData("up \"cmd.exe /c calc\"")]
    [InlineData("route-up c:\\x.bat")]
    [InlineData("plugin foo.dll")]
    [InlineData("script-security 2")]
    [InlineData("log C:\\x.txt")]
    [InlineData("management 127.0.0.1 7505")]
    [InlineData("config other.ovpn")]
    public void OpenVpn_DangerousDirectives_AreRejected(string line)
    {
        var errors = ConfigValidator.Validate(Ov(Ovpn + line + "\n"));
        Assert.Contains(errors, e => e.Contains("not allowed"));
    }

    [Fact]
    public void OpenVpn_ExternalFiles_AreRejected()
    {
        var raw = Ovpn.Replace("<ca>", "ca ca.crt\n<xx>").Replace("</ca>", "</xx>");
        Assert.Contains(ConfigValidator.Validate(Ov(raw)), e => e.Contains("external file"));
    }

    [Fact]
    public void OpenVpn_MissingCaOrRemote_IsRejected()
    {
        Assert.Contains(ConfigValidator.Validate(Ov("client\ndev tun\nremote vpn.example.com 1194\n")), e => e.Contains("<ca>"));
        Assert.Contains(ConfigValidator.Validate(Ov("client\n<ca>\nx\n</ca>\n")), e => e.Contains("remote"));
    }

    [Fact]
    public void OpenVpn_Credentials_AreRequiredAndExtracted()
    {
        var needs = Ovpn + "auth-user-pass\n";
        Assert.Contains(ConfigValidator.Validate(Ov(needs)), e => e.Contains("username and password"));
        var withBlock = Ovpn + "auth-user-pass\n<auth-user-pass>\nalice\ns3cret-pass\n</auth-user-pass>\n";
        Assert.Empty(ConfigValidator.Validate(Ov(withBlock)));
        var cred = OpenVpnConfig.ExtractCredentials(withBlock);
        Assert.NotNull(cred);
        Assert.Equal("alice", cred!.Value.User);
        Assert.Equal("s3cret-pass", cred.Value.Pass);
        Assert.Contains("s3cret-pass", OpenVpnConfig.Secrets(withBlock));
        Assert.Contains("SECRETKEYMATERIAL1234567890", OpenVpnConfig.Secrets(withBlock));
    }

    [Fact]
    public void OpenVpn_AuthFileReference_IsRejected()
    {
        Assert.Contains(ConfigValidator.Validate(Ov(Ovpn + "auth-user-pass pass.txt\n")), e => e.Contains("must not point to a file"));
    }

    [Fact]
    public void OpenVpn_RuntimeConfig_HoldsManagedOptions_AndNoCredentials()
    {
        var raw = Ovpn + "auth-user-pass\n<auth-user-pass>\nalice\ns3cret-pass\n</auth-user-pass>\nwindows-driver tap-windows6\nverb 9\nredirect-gateway def1\n";
        var s = new AppSettings { Dns = "1.1.1.1, 9.9.9.9", Mtu = 1400, BypassNetworks = "192.168.0.0/16" };
        var text = OpenVpnConfig.BuildRuntimeConfig(raw, s, 5555);
        Assert.DoesNotContain("s3cret-pass", text);
        Assert.DoesNotContain("alice", text);
        Assert.DoesNotContain("tap-windows6", text);
        Assert.DoesNotContain("verb 9", text);
        Assert.Contains("windows-driver wintun", text);
        Assert.Contains("management 127.0.0.1 5555 stdin", text);
        Assert.Contains("management-query-passwords", text);
        Assert.Contains("tun-mtu 1400", text);
        Assert.Contains("dhcp-option DNS 1.1.1.1", text);
        Assert.Contains("dhcp-option DNS 9.9.9.9", text);
        Assert.Contains("block-outside-dns", text);
        Assert.Contains("route 192.168.0.0 255.255.0.0 net_gateway", text);
        Assert.Contains("SECRETKEYMATERIAL1234567890", text); // keys stay: openvpn.exe needs them
    }

    [Fact]
    public void OpenVpn_BareAuthWithoutBlock_GetsNoDuplicateDirective()
    {
        var raw = Ovpn + "<auth-user-pass>\nu\np\n</auth-user-pass>\n";
        var text = OpenVpnConfig.BuildRuntimeConfig(raw, new AppSettings(), 1);
        Assert.Single(text.Split('\n').Where(l => l.Trim() == "auth-user-pass"));
    }

    // ------------------------------------------------------------------ SlipNet

    private static string Link(string tunnel, string extra = "")
    {
        var fields = $"18|{tunnel}|My SlipNet|t.example.com|8.8.8.8:53:0,1.1.1.1:53:0|0|0|bbr|10880|127.0.0.1|0|PUBKEYPUBKEY|||||||{extra}";
        return "slipnet://" + Convert.ToBase64String(Encoding.UTF8.GetBytes(fields));
    }

    [Fact]
    public void SlipNet_LinkIsParsed()
    {
        var link = Link("dnstt");
        var r = ProfileParser.ParseText(link);
        Assert.Empty(r.Errors);
        var p = Assert.Single(r.Profiles);
        Assert.Equal(EngineKind.SlipNet, p.Engine);
        Assert.Equal("My SlipNet", p.Name);
        Assert.Equal("SN", p.ProtocolCode);
        Assert.Equal("dnstt", p.Protocol);
        Assert.Equal("8.8.8.8", p.Server);
        Assert.Empty(ConfigValidator.Validate(p));
        Assert.Equal(new[] { "8.8.8.8", "1.1.1.1" }, SlipNetProfile.ServerHosts(link));
    }

    [Theory]
    [InlineData("dnstt", true)]
    [InlineData("vaydns_ssh", true)]
    [InlineData("sayedns", true)]
    [InlineData("vless", true)]
    [InlineData("doh", true)]
    [InlineData("ss", false)]
    [InlineData("slipstream_ssh", false)]
    [InlineData("snowflake", false)]
    [InlineData("naive", false)]
    public void SlipNet_TunnelTypeSupport_MatchesTheRuntime(string type, bool supported)
    {
        Assert.Equal(supported, SlipNetProfile.IsSupportedTunnel(type));
        var p = Assert.Single(ProfileParser.ParseText(Link(type)).Profiles);
        var errors = ConfigValidator.Validate(p);
        Assert.Equal(supported, errors.Count == 0);
        if (!supported) Assert.Contains("not available", errors[0]);
    }

    [Fact]
    public void SlipNet_BadInput_IsRejected()
    {
        Assert.NotEmpty(ProfileParser.ParseText("slipnet://@@@").Errors);
        var shortLink = "slipnet://" + Convert.ToBase64String(Encoding.UTF8.GetBytes("18|dnstt|x"));
        Assert.Contains(ProfileParser.ParseText(shortLink).Errors, e => e.Contains("too few"));
    }

    [Fact]
    public void SlipNet_EncryptedLink_IsImportedButNotRunnable()
    {
        var p = Assert.Single(ProfileParser.ParseText("slipnet-enc://AAAA").Profiles);
        Assert.Contains(ConfigValidator.Validate(p), e => e.Contains("encrypted"));
    }

    [Fact]
    public void SlipNet_LinkIsASecret_AndArgumentsAreBuilt()
    {
        var link = Link("dnstt");
        Assert.Contains(link, SlipNetProfile.Secrets(link));
        var args = SlipNetEngine.BuildArgs(link, 10880);
        Assert.Equal(new[] { "--host", "127.0.0.1", "--port", "10880", link }, args);
    }

    // ------------------------------------------------------------------ engine selection

    [Fact]
    public void Catalog_DescribesEveryEngine()
    {
        foreach (var kind in Enum.GetValues<EngineKind>())
        {
            var info = EngineCatalog.Get(kind);
            Assert.False(string.IsNullOrWhiteSpace(info.Runtime));
            Assert.NotNull(EngineCatalog.Create(kind));
        }
        Assert.IsType<AmneziaWgEngine>(EngineCatalog.Create(EngineKind.AmneziaWG));
        Assert.IsType<OpenVpnEngine>(EngineCatalog.Create(EngineKind.OpenVpn));
        Assert.IsType<SlipNetEngine>(EngineCatalog.Create(EngineKind.SlipNet));
        Assert.IsType<XrayEngine>(EngineCatalog.Create(EngineKind.Xray));
        Assert.IsType<WireGuardEngine>(EngineCatalog.Create(EngineKind.WireGuard));
    }

    [Theory]
    [InlineData(EngineKind.Xray, ConnectionMode.Proxy, ConnectionMode.Proxy)]
    [InlineData(EngineKind.Xray, ConnectionMode.Vpn, ConnectionMode.Vpn)]
    [InlineData(EngineKind.SlipNet, ConnectionMode.Proxy, ConnectionMode.Proxy)]
    [InlineData(EngineKind.SlipNet, ConnectionMode.Vpn, ConnectionMode.Vpn)]
    [InlineData(EngineKind.WireGuard, ConnectionMode.Proxy, ConnectionMode.Vpn)]
    [InlineData(EngineKind.AmneziaWG, ConnectionMode.Proxy, ConnectionMode.Vpn)]
    [InlineData(EngineKind.OpenVpn, ConnectionMode.Proxy, ConnectionMode.Vpn)]
    public void EffectiveMode_NativeTunnelsAlwaysVpn(EngineKind kind, ConnectionMode selected, ConnectionMode expected)
    {
        var p = new Profile { Engine = kind };
        var s = new AppSettings { Mode = selected };
        Assert.Equal(expected, EngineCatalog.EffectiveMode(p, s));
        Assert.Equal(expected == ConnectionMode.Vpn, EngineCatalog.NeedsAdmin(p, s));
    }

    [Fact]
    public void EngineKind_NumericValues_AreStable()
    {
        Assert.Equal(0, (int)EngineKind.Xray);
        Assert.Equal(1, (int)EngineKind.WireGuard);
        Assert.Equal(2, (int)EngineKind.AmneziaWG);
        Assert.Equal(3, (int)EngineKind.OpenVpn);
        Assert.Equal(4, (int)EngineKind.SlipNet);
    }

    [Fact]
    public async Task Connect_WithMissingRuntime_FailsCleanlyWithoutTouchingTheSystem()
    {
        // The test host has no engines folder, so every runtime is "not installed".
        using var manager = new ConnectionManager();
        var profile = Assert.Single(ProfileParser.ParseText(Link("dnstt")).Profiles);
        await manager.ConnectAsync(profile, new AppSettings());
        Assert.Equal(ConnectionState.Error, manager.State);
        Assert.Contains("not installed", manager.Message);
        await manager.DisconnectAsync();
        Assert.Equal(ConnectionState.Disconnected, manager.State);
    }

    [Fact]
    public void RunState_NewFields_RoundTrip()
    {
        var data = new RunStateData { AmneziaTunnel = "tirexnet-awg", OpenVpnPid = 42, OpenVpnStartTicks = 123 };
        var json = System.Text.Json.JsonSerializer.Serialize(data);
        var back = System.Text.Json.JsonSerializer.Deserialize<RunStateData>(json)!;
        Assert.Equal("tirexnet-awg", back.AmneziaTunnel);
        Assert.Equal(42, back.OpenVpnPid);
        Assert.Equal(123, back.OpenVpnStartTicks);
    }

    // ------------------------------------------------------------------ ping / Smart Connect

    [Fact]
    public async Task Ping_Tcp_MeasuresARealLoopbackListener()
    {
        var listener = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0);
        listener.Start();
        try
        {
            var port = ((System.Net.IPEndPoint)listener.LocalEndpoint).Port;
            var p = new Profile { Engine = EngineKind.Xray, Protocol = "vless", Server = "127.0.0.1", Port = port };
            var r = await TIREXNET.Core.Services.PingService.TestAsync(p);
            Assert.Equal(TIREXNET.Core.Services.PingKind.Ok, r.Kind);
            Assert.Equal("tcp", r.Method);
            Assert.True(r.Ms >= 0);
        }
        finally { listener.Stop(); }
    }

    [Fact]
    public async Task Ping_ClosedPort_IsNotOk_AndMissingPortIsUnsupported()
    {
        var closed = new Profile { Engine = EngineKind.Xray, Server = "127.0.0.1", Port = NetUtil_FreePort() };
        var r = await TIREXNET.Core.Services.PingService.TestAsync(closed);
        Assert.NotEqual(TIREXNET.Core.Services.PingKind.Ok, r.Kind);
        var none = new Profile { Engine = EngineKind.Xray, Server = "127.0.0.1", Port = 0 };
        Assert.Equal(TIREXNET.Core.Services.PingKind.Unsupported, (await TIREXNET.Core.Services.PingService.TestAsync(none)).Kind);
    }

    private static int NetUtil_FreePort() => TIREXNET.Core.NetUtil.FindFreePort();

    [Fact]
    public void Ping_Method_FollowsTheTransport()
    {
        Assert.True(TIREXNET.Core.Services.PingService.UsesIcmp(new Profile { Engine = EngineKind.WireGuard }));
        Assert.True(TIREXNET.Core.Services.PingService.UsesIcmp(new Profile { Engine = EngineKind.AmneziaWG }));
        Assert.False(TIREXNET.Core.Services.PingService.UsesIcmp(new Profile { Engine = EngineKind.Xray }));
        Assert.True(TIREXNET.Core.Services.PingService.UsesIcmp(Ov(Ovpn)));
        Assert.False(TIREXNET.Core.Services.PingService.UsesIcmp(Ov(Ovpn.Replace("proto udp", "proto tcp").Replace("1194", "443"))));
    }

    [Fact]
    public void SmartConnect_PicksLowestPing_AmongConnectable()
    {
        var settings = new AppSettings();
        var wg = Assert.Single(ProfileParser.ParseText(ProfileParserTests.WgConf).Profiles); // runtime missing in the test host
        var a = Assert.Single(ProfileParser.ParseText("vless://11111111-2222-3333-4444-555555555555@10.0.0.5:443?type=tcp&security=none#A").Profiles);
        var results = new List<(Profile, TIREXNET.Core.Services.PingResult)>
        {
            (wg, new TIREXNET.Core.Services.PingResult(TIREXNET.Core.Services.PingKind.Ok, 5, "icmp")),
            (a, new TIREXNET.Core.Services.PingResult(TIREXNET.Core.Services.PingKind.Timeout, -1, "tcp")),
        };
        // WireGuard is fastest but its runtime is not installed here; the other timed out: nothing is connectable.
        Assert.Null(TIREXNET.Core.Services.PingService.Best(results, settings));
    }

    [Fact]
    public void ProfileSummary_ShowsPing()
    {
        var p = new Profile { Engine = EngineKind.Xray, Protocol = "vless", PingMs = 42 };
        Assert.Contains("42 ms", p.Summary);
        p.PingMs = -2;
        Assert.Contains("timeout", p.Summary);
    }
}
