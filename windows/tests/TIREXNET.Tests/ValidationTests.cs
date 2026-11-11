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

    /// <summary>
    /// Removes every line whose trimmed text starts with <paramref name="prefix"/>. Works line by line, so it does not
    /// depend on LF / CRLF, and re-joins the remaining lines with <see cref="Environment.NewLine"/>.
    /// </summary>
    private static string RemoveLine(string text, string prefix)
    {
        var kept = text.Split(new[] { "\r\n", "\n" }, StringSplitOptions.None)
            .Where(l => !l.Trim().StartsWith(prefix, StringComparison.OrdinalIgnoreCase));
        return string.Join(Environment.NewLine, kept);
    }

    [Fact]
    public void WireGuard_MissingPrivateKey_IsInvalid()
    {
        // Sanity check: the unmodified configuration is valid, so the failure below is caused by the missing key only.
        Assert.Empty(ConfigValidator.Validate(Wg(ProfileParserTests.WgConf)));

        var withoutKey = RemoveLine(ProfileParserTests.WgConf, "PrivateKey");
        Assert.DoesNotContain("PrivateKey", withoutKey);

        // Same result with the platform line ending, LF and CRLF.
        foreach (var conf in new[] { withoutKey, withoutKey.Replace("\r\n", "\n").Replace("\n", "\r\n"), withoutKey.Replace("\r\n", "\n") })
        {
            var errors = ConfigValidator.Validate(Wg(conf));
            Assert.Contains(errors, e => e.Contains("PrivateKey"));
        }
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

    [Fact]
    public void WireGuard_InvalidAddress_And_Dns_AreRejected()
    {
        var bad = ProfileParserTests.WgConf.Replace("Address = 10.0.0.2/32, fd00::2/128", "Address = 10.0.0.2/99").Replace("DNS = 1.1.1.1, 1.0.0.1", "DNS = bad dns!");
        var errors = ConfigValidator.Validate(Wg(bad));
        Assert.Contains(errors, e => e.Contains("address"));
        Assert.Contains(errors, e => e.Contains("DNS"));
    }

    [Fact]
    public void WireGuard_MissingAddress_AndAllowedIps_AreRejected()
    {
        var conf = RemoveLine(RemoveLine(ProfileParserTests.WgConf, "Address"), "AllowedIPs");
        var errors = ConfigValidator.Validate(Wg(conf));
        Assert.Contains(errors, e => e.Contains("Address"));
        Assert.Contains(errors, e => e.Contains("AllowedIPs"));
    }

    [Fact]
    public void WireGuard_WithoutPeerSection_IsNotImported()
    {
        var r = ProfileParser.ParseText("[Interface]\nPrivateKey = YAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\nAddress = 10.0.0.2/32\n[Peer");
        Assert.Empty(r.Profiles);
    }

    [Fact]
    public void XrayJson_WithOnlyDirectOutbound_IsInvalid()
    {
        var p = ProfileParser.ParseText("{\"outbounds\":[{\"protocol\":\"freedom\"}]}").Profiles.Single();
        Assert.Contains(ConfigValidator.Validate(p), e => e.Contains("server address"));
    }

    [Fact]
    public void Profile_WithoutRawData_Or_Name_IsInvalid()
    {
        Assert.NotEmpty(ConfigValidator.Validate(new Profile { Name = "x", Raw = "" }));
        var p = ProfileParser.ParseText("trojan://pw@t.example.com:443#t").Profiles.Single();
        p.Name = "  ";
        Assert.Contains(ConfigValidator.Validate(p), e => e.Contains("name"));
    }

    [Fact]
    public void SettingsStore_Roundtrips_And_RecoversFromCorruptFile()
    {
        var dir = Path.Combine(Path.GetTempPath(), "tirex-set-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            var store = new TIREXNET.Core.Services.SettingsStore(dir);
            Assert.Equal(10880, store.Current.SocksPort);
            store.Save(new AppSettings { SocksPort = 12000, Dns = "9.9.9.9", Mode = ConnectionMode.Vpn });
            var again = new TIREXNET.Core.Services.SettingsStore(dir);
            Assert.Equal(12000, again.Current.SocksPort);
            Assert.Equal("9.9.9.9", again.Current.Dns);
            Assert.Equal(ConnectionMode.Vpn, again.Current.Mode);

            File.WriteAllText(Path.Combine(dir, "settings.json"), "{ this is not json");
            Assert.Equal(10880, new TIREXNET.Core.Services.SettingsStore(dir).Current.SocksPort);
        }
        finally
        {
            Directory.Delete(dir, true);
        }
    }

    [Fact]
    public void ProfileStore_UndecryptableFile_IsMovedAside_NotCrashing()
    {
        var dir = Path.Combine(Path.GetTempPath(), "tirex-bad-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            File.WriteAllBytes(Path.Combine(dir, "profiles.dat"), new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
            var store = new TIREXNET.Core.Services.ProfileStore(dir);
            Assert.True(store.LoadFailed);
            Assert.Empty(store.All());
            Assert.True(File.Exists(Path.Combine(dir, "profiles.dat.unreadable")));
        }
        finally
        {
            Directory.Delete(dir, true);
        }
    }

    [Fact]
    public void ExportFileName_IsSafe_ForWindows()
    {
        var p = new Profile { Name = "a/b:c*?\"<>|d", Engine = EngineKind.WireGuard };
        var name = TIREXNET.Core.Services.ProfileStore.ExportFileName(p);
        Assert.EndsWith(".conf", name);
        Assert.Empty(name.Intersect(Path.GetInvalidFileNameChars()));
    }

    [Fact]
    public async Task ApiClient_ParsesLoginMeAndConfigs_FromRealContractShapes()
    {
        var handler = new FakeHandler(req => req.RequestUri!.AbsolutePath switch
        {
            "/api/login" => (200, "{\"token\":\"tok-123456\"}"),
            "/api/me" => (200, "{\"username\":\"u\",\"subscription\":{\"username\":\"pu\",\"status\":\"active\",\"usedTraffic\":10,\"dataLimit\":100,\"expire\":null}}"),
            "/api/me/configs" => (200, "{\"configs\":[\"trojan://pw@t.example.com:443#t\",{\"config\":\"vless://11111111-2222-3333-4444-555555555555@h.example.com:443#v\",\"name\":\"Named\"},{\"config\":\"\"}]}"),
            "/api/logout" => (200, "{}"),
            _ => (404, "{}"),
        });
        using var api = new TIREXNET.Core.Services.ApiClient(handler);
        var token = await api.LoginAsync("u", "p", "pasarguard");
        Assert.Equal("tok-123456", token);
        Assert.Null(handler.LastAuthScheme); // the login request carries no bearer token
        var me = await api.GetAccountAsync(token);
        Assert.Equal(100, me.LimitBytes);
        Assert.Equal("", me.Expire); // "expire": null means no expiry date
        Assert.Equal("Bearer", handler.LastAuthScheme);
        var cfgs = await api.GetConfigsAsync(token, "pasarguard");
        Assert.Equal(2, cfgs.Count);
        Assert.Equal("Bearer", handler.LastAuthScheme);
        await api.LogoutAsync(token);
    }

    [Fact]
    public async Task ApiClient_Http401_IsReportedWithStatusCode()
    {
        var handler = new FakeHandler(_ => (401, "{\"error\":\"bad\"}"));
        using var api = new TIREXNET.Core.Services.ApiClient(handler);
        var ex = await Assert.ThrowsAsync<TIREXNET.Core.Services.ApiException>(() => api.LoginAsync("u", "p", "pasarguard"));
        Assert.Equal(401, ex.StatusCode);
    }

    [Fact]
    public async Task ApiClient_LoginWithoutToken_Fails()
    {
        var handler = new FakeHandler(_ => (200, "{\"error\":\"nope\"}"));
        using var api = new TIREXNET.Core.Services.ApiClient(handler);
        await Assert.ThrowsAsync<TIREXNET.Core.Services.ApiException>(() => api.LoginAsync("u", "p", "pasarguard"));
    }

    private sealed class FakeHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, (int Status, string Body)> _respond;
        public string? LastAuthScheme { get; private set; }

        public FakeHandler(Func<HttpRequestMessage, (int, string)> respond) => _respond = respond;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            LastAuthScheme = request.Headers.Authorization?.Scheme;
            var (status, body) = _respond(request);
            return Task.FromResult(new HttpResponseMessage((System.Net.HttpStatusCode)status)
            {
                Content = new StringContent(body, Encoding.UTF8, "application/json"),
            });
        }
    }

    [Fact]
    public void Tun2Socks_LogLevel_IsAValueTun2SocksAccepts()
    {
        // tun2socks v2.x rejects "warn" with: not a valid logrus Level. Accepted: debug|info|warning|error|silent.
        var args = TIREXNET.Core.Engines.VpnTunnel.BuildTun2SocksArgs(10880, 1500);
        var level = args[Array.IndexOf(args, "-loglevel") + 1];
        Assert.Contains(level, new[] { "debug", "info", "warning", "error", "silent" });
        Assert.Equal("tun://TIREXNET", args[Array.IndexOf(args, "-device") + 1]);
        Assert.Equal("socks5://127.0.0.1:10880", args[Array.IndexOf(args, "-proxy") + 1]);
        Assert.Equal("1500", args[Array.IndexOf(args, "-mtu") + 1]);
    }

    [Fact]
    public void Xhttp_Vless_Link_BuildsXhttpSettings_WithModeAndExtra()
    {
        var extra = Uri.EscapeDataString("{\"xPaddingBytes\":\"100-1000\"}");
        var cfg = Build("vless://11111111-2222-3333-4444-555555555555@x.example.com:443?type=xhttp&security=tls&path=%2Fx&host=x.example.com&mode=auto&extra=" + extra + "#xh");
        var ss = cfg["outbounds"]![0]!["streamSettings"]!;
        Assert.Equal("xhttp", ss["network"]!.GetValue<string>());
        Assert.Equal("/x", ss["xhttpSettings"]!["path"]!.GetValue<string>());
        Assert.Equal("auto", ss["xhttpSettings"]!["mode"]!.GetValue<string>());
        Assert.Equal("100-1000", ss["xhttpSettings"]!["extra"]!["xPaddingBytes"]!.GetValue<string>());
    }

    [Fact]
    public void Xhttp_Vmess_Base64_IsAccepted()
    {
        var json = "{\"v\":\"2\",\"ps\":\"xh\",\"add\":\"x.example.com\",\"port\":\"443\",\"id\":\"11111111-2222-3333-4444-555555555555\",\"aid\":\"0\",\"net\":\"xhttp\",\"host\":\"x.example.com\",\"path\":\"/p\",\"tls\":\"tls\"}";
        var p = ProfileParser.ParseText("vmess://" + Convert.ToBase64String(Encoding.UTF8.GetBytes(json))).Profiles.Single();
        Assert.Empty(ConfigValidator.Validate(p));
        var cfg = JsonNode.Parse(XrayConfigBuilder.Build(p, new AppSettings(), 12345))!;
        Assert.Equal("xhttp", cfg["outbounds"]![0]!["streamSettings"]!["network"]!.GetValue<string>());
    }

    [Fact]
    public void WpfApp_MustNotUse_InvariantGlobalization()
    {
        // InvariantGlobalization=true makes WPF text boxes crash with "1033 (0x0409) is an invalid culture identifier".
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        string? csproj = null;
        while (dir != null && csproj == null)
        {
            var candidate = Path.Combine(dir.FullName, "src", "TIREXNET.App", "TIREXNET.App.csproj");
            if (File.Exists(candidate)) csproj = candidate;
            dir = dir.Parent;
        }
        Assert.NotNull(csproj);
        var text = File.ReadAllText(csproj!);
        Assert.DoesNotContain("<InvariantGlobalization>true</InvariantGlobalization>", text);
    }

    private const string XhId = "11111111-2222-3333-4444-555555555555";

    [Fact]
    public void Xhttp_InvalidExtra_IsRejected_NotSilentlyDropped()
    {
        var bad = "vless://" + XhId + "@x.example.com:443?type=xhttp&security=tls&extra=" + Uri.EscapeDataString("{not json") + "#x";
        var p = ProfileParser.ParseText(bad).Profiles.Single();
        Assert.Contains(ConfigValidator.Validate(p), e => e.Contains("extra"));
        Assert.Throws<EngineException>(() => XrayConfigBuilder.Build(p, new AppSettings(), 12345));

        var notObject = "vless://" + XhId + "@x.example.com:443?type=xhttp&security=tls&extra=" + Uri.EscapeDataString("[1,2]") + "#x";
        Assert.Contains(ConfigValidator.Validate(ProfileParser.ParseText(notObject).Profiles.Single()), e => e.Contains("extra"));
    }

    [Fact]
    public void Xhttp_UnknownMode_IsRejected()
    {
        var p = ProfileParser.ParseText("vless://" + XhId + "@x.example.com:443?type=xhttp&mode=bogus#x").Profiles.Single();
        Assert.Contains(ConfigValidator.Validate(p), e => e.Contains("mode"));
    }

    [Fact]
    public void Xhttp_QueryValues_ArePercentDecoded_AndPlusIsKept()
    {
        var cfg = Build("vless://" + XhId + "@x.example.com:443?type=splithttp&security=tls&path=%2Fa%2Bb%20c&host=h.example.com&mode=stream-up#x");
        var xh = cfg["outbounds"]![0]!["streamSettings"]!["xhttpSettings"]!;
        Assert.Equal("/a+b c", xh["path"]!.GetValue<string>());
        Assert.Equal("h.example.com", xh["host"]!.GetValue<string>());
        Assert.Equal("stream-up", xh["mode"]!.GetValue<string>());
    }

    [Fact]
    public void Xhttp_Reality_KeepsRealitySettings()
    {
        var cfg = Build("vless://" + XhId + "@x.example.com:443?type=xhttp&security=reality&pbk=abc&sid=1a2b&sni=s.example.com&fp=firefox&path=%2F#x");
        var ss = cfg["outbounds"]![0]!["streamSettings"]!;
        Assert.Equal("reality", ss["security"]!.GetValue<string>());
        Assert.Equal("abc", ss["realitySettings"]!["publicKey"]!.GetValue<string>());
        Assert.NotNull(ss["xhttpSettings"]);
    }

    [Fact]
    public void Xhttp_Vmess_ModeAndExtra_Preserved()
    {
        var json = "{\"v\":\"2\",\"ps\":\"xh\",\"add\":\"x.example.com\",\"port\":\"443\",\"id\":\"" + XhId + "\",\"aid\":\"0\",\"net\":\"xhttp\",\"type\":\"packet-up\",\"host\":\"x.example.com\",\"path\":\"/p\",\"tls\":\"tls\",\"extra\":{\"xPaddingBytes\":\"10-20\"}}";
        var p = ProfileParser.ParseText("vmess://" + Convert.ToBase64String(Encoding.UTF8.GetBytes(json))).Profiles.Single();
        Assert.Empty(ConfigValidator.Validate(p));
        var xh = JsonNode.Parse(XrayConfigBuilder.Build(p, new AppSettings(), 12345))!["outbounds"]![0]!["streamSettings"]!["xhttpSettings"]!;
        Assert.Equal("packet-up", xh["mode"]!.GetValue<string>());
        Assert.Equal("10-20", xh["extra"]!["xPaddingBytes"]!.GetValue<string>());
    }

    [Fact]
    public void EnginesLock_PinsXrayThatSupportsXhttp()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        string? lockFile = null;
        while (dir != null && lockFile == null)
        {
            var c = Path.Combine(dir.FullName, "scripts", "engines.lock.json");
            if (File.Exists(c)) lockFile = c;
            dir = dir.Parent;
        }
        Assert.NotNull(lockFile);
        var xray = JsonNode.Parse(File.ReadAllText(lockFile!))!["xray"]!;
        var v = xray["version"]!.GetValue<string>().TrimStart('v').Split('.');
        Assert.True(int.Parse(v[0]) >= 24, "Xray must be a release with xhttp support (calendar versions, 24+).");
        Assert.NotEqual("v1.8.24", xray["version"]!.GetValue<string>());
        Assert.Matches("^[0-9a-f]{64}$", xray["sha256"]!.GetValue<string>());
    }

    private static string FindUp(params string[] parts)
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null)
        {
            var c = Path.Combine(new[] { dir.FullName }.Concat(parts).ToArray());
            if (File.Exists(c)) return c;
            dir = dir.Parent;
        }
        throw new FileNotFoundException(string.Join("/", parts));
    }

    [Fact]
    public async Task Diagnostics_RunAsync_NeverThrows_AndReturnsReadableReport()
    {
        var report = await TIREXNET.Core.Services.Diagnostics.RunAsync().WaitAsync(TimeSpan.FromSeconds(60));
        Assert.StartsWith("TIREXNET diagnostics", report);
        Assert.Contains("Xray runtime", report);
    }

    [Fact]
    public async Task Diagnostics_Report_IsRedacted()
    {
        Redactor.AddSecret("s3cr3t-diag-token");
        try
        {
            var report = await TIREXNET.Core.Services.Diagnostics.RunAsync().WaitAsync(TimeSpan.FromSeconds(60));
            Assert.DoesNotContain("s3cr3t-diag-token", report);
            Assert.Equal("a ***", Redactor.Redact("a s3cr3t-diag-token"));
        }
        finally { Redactor.Clear(); }
    }

    [Fact]
    public void ProfileLists_UseExplicitReadableForeground()
    {
        // A ListBoxItem falls back to the default black foreground: unreadable on the dark surfaces.
        var xaml = File.ReadAllText(FindUp("src", "TIREXNET.App", "App.xaml"));
        var item = xaml.Substring(xaml.IndexOf("x:Key=\"DarkItem\"", StringComparison.Ordinal));
        item = item.Substring(0, item.IndexOf("</Style>", StringComparison.Ordinal));
        Assert.Contains("<Setter Property=\"Foreground\" Value=\"{StaticResource TextBrush}\" />", item);
        Assert.Contains("TextElement.Foreground=\"{StaticResource TextBrush}\"", item);

        var tpl = xaml.Substring(xaml.IndexOf("x:Key=\"ProfileTemplate\"", StringComparison.Ordinal));
        foreach (var field in new[] { "{Binding Name}", "{Binding Summary}", "{Binding Endpoint}" })
        {
            var i = tpl.IndexOf(field, StringComparison.Ordinal);
            Assert.True(i > 0, field);
            var tag = tpl.Substring(i, tpl.IndexOf("/>", i, StringComparison.Ordinal) - i);
            Assert.Contains("Foreground=", tag);
        }

        // Both lists (Dashboard and Profiles) share the implicit ListBox style, so neither can be left unreadable.
        var win = File.ReadAllText(FindUp("src", "TIREXNET.App", "MainWindow.xaml"));
        Assert.Contains("x:Name=\"DashProfileList\" ItemTemplate=\"{StaticResource ProfileTemplate}\"", win);
        Assert.Contains("x:Name=\"ProfilesList\" ItemTemplate=\"{StaticResource ProfileTemplate}\"", win);
        Assert.DoesNotContain("ItemContainerStyle", win);
    }

    [Fact]
    public void Profile_ProtocolCode_CoversEveryProtocol()
    {
        Assert.Equal("VL", new Profile { Protocol = "vless" }.ProtocolCode);
        Assert.Equal("VM", new Profile { Protocol = "vmess" }.ProtocolCode);
        Assert.Equal("TR", new Profile { Protocol = "trojan" }.ProtocolCode);
        Assert.Equal("SS", new Profile { Protocol = "shadowsocks" }.ProtocolCode);
        Assert.Equal("XJ", new Profile { Protocol = "xray-json" }.ProtocolCode);
        Assert.Equal("WG", new Profile { Engine = EngineKind.WireGuard, Protocol = "wireguard" }.ProtocolCode);
    }

    [Fact]
    public void Tun2Socks_Device_UsesTunDriver_NotWintunScheme()
    {
        var args = VpnTunnel.BuildTun2SocksArgs(10880, 1500);
        var device = args[Array.IndexOf(args, "-device") + 1];
        Assert.StartsWith("tun://", device);          // "wintun://" => "unsupported driver: wintun"
        Assert.DoesNotContain("wintun", device);
    }

    [Theory]
    [InlineData("vless://11111111-2222-3333-4444-555555555555@example.com:80?type=tcp&security=none#x", true)]
    [InlineData("vless://11111111-2222-3333-4444-555555555555@203.0.113.9:80?type=ws&security=none#x", true)]
    [InlineData("trojan://pw@example.com:80?security=none#x", true)]
    [InlineData("vless://11111111-2222-3333-4444-555555555555@10.0.0.5:80?type=tcp&security=none#x", false)]
    [InlineData("vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=tls#x", false)]
    [InlineData("vless://11111111-2222-3333-4444-555555555555@example.com:443?type=tcp&security=reality&pbk=abc&sid=1a#x", false)]
    [InlineData("vmess://eyJ2IjoiMiJ9", false)]
    public void PlaintextVless_IsExplainedBeforeXrayIsStarted(string uri, bool rejected)
    {
        var p = ProfileParser.ParseText(uri).Profiles.FirstOrDefault();
        if (p == null) { Assert.False(rejected); return; }
        Assert.Equal(rejected, ConfigValidator.PlaintextRejectedByXray(p) != null);
    }

    [Fact]
    public void ListBoxStyle_HasOwnTemplate_SoDisabledListIsNotWhite()
    {
        var xaml = File.ReadAllText(FindUp("src", "TIREXNET.App", "App.xaml"));
        var i = xaml.IndexOf("<Style TargetType=\"ListBox\">", StringComparison.Ordinal);
        Assert.True(i > 0);
        var style = xaml.Substring(i, xaml.IndexOf("</Style>", i, StringComparison.Ordinal) - i);
        Assert.Contains("<ControlTemplate TargetType=\"ListBox\">", style);
        Assert.Contains("<ScrollViewer", style);
    }
}
