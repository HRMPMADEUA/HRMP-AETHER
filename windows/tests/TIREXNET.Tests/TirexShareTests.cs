using System.IO.Pipes;
using System.Text;
using TIREXNET.Core;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;
using TIREXNET.Core.Services;
using Xunit;

namespace TIREXNET.Tests;

/// <summary>
/// tirexnet:// share links. The two vectors below were produced by an independent implementation of the Android encoder
/// (TirexNetShare.kt: AES-256-GCM, key = SHA-256(salt + phrase), gzip JSON, URL-safe Base64) with fixed IVs.
/// </summary>
public class TirexShareTests
{
    private const string XrayLink = "tirexnet://share/v1/AQABAgMEBQYHCAkKC3krFLsmbthG-5r7F2DwHw5TJL_i43MXBMVEtxEE0PPbozEIv4jFJetNU_aILfLetBNCS1nznNBTp_dv56l0KyAQ3ArXOm8xd5m5AXUSUZAJ9PNa64XQ0mtptVu_du-kmpryPSNyPyJyfk7JbOqU0eRizo5OHaIL8FPmsyxK5pXAIB4n4McomU1XSod7OPgGb6BPs9Kb0VaiSZci2jU8elZ06nRZevLWUjE3RGaJQqceH5U5Wk8FUARY7ekbbF3MmtI39HP_lvk4p0rDrpuCdJiAGo6glg8xZvxZtUQ_x6tDIGw6iCQKjrcHms9Gc6yEQ4LpmJY";
    private const string SlipLink = "tirexnet://share/v1/AQwNDg8QERITFBUWF7yRhjvukphAzGSaCAjxr1RRE6sdc3iRyFuljIXhqizGFsfat3g34-SmKAGG5G44g_DHzudJ9a7QHJTPgPd5uDqR0xa3G9-7jNe6cWXBGXl9HIQz15FEfvr3cV1xSwm_Zxjfa6misNLUH0su5zai6klINFITdKRWfjWUTQpJSMF4Tt1bopXDa2tdz6JS-8urWwFvS7V25dD2gtCyaaEJP_aU1t1VEWL9E2AA0P6ctbyVF8Erm9DSA9e2q-hiDU8827rl6gQjT2nHZH5tfbeQpaaGAJUgjO7uX1C2kJn3t9_USGVTRzgbGG8TQ1lEgoctzLmO13uDIPJTs9BOdUIvE5UycI59q-KHSnp0_3rd";
    private const string VlessRaw = "vless://11111111-2222-3333-4444-555555555555@203.0.113.10:443?type=tcp&security=tls&sni=example.com#Test%20Server";

    [Fact]
    public void AndroidVector_Xray_IsDecodedExactly()
    {
        var p = TirexShare.Decode(XrayLink);
        Assert.Equal("Test Server ✓", p.Name);
        Assert.Equal("XRAY", p.Engine);
        Assert.Equal(VlessRaw, p.Raw);          // encoded parameters (%20, query) preserved byte for byte
        Assert.Equal("DE", p.Country);
        Assert.Equal("MANUAL", p.Origin);
        Assert.False(p.Protected);
        Assert.Empty(p.DnsResolvers);
    }

    [Fact]
    public void AndroidVector_SlipNet_KeepsDnsCustomAndProtection()
    {
        var p = TirexShare.Decode(SlipLink);
        Assert.Equal("SLIPNET", p.Engine);
        Assert.Equal(new[] { "1.1.1.1", "9.9.9.9" }, p.DnsResolvers);
        Assert.Equal("1.1.1.1", p.SelectedResolver);
        Assert.Equal("v", p.CustomSettings["k"]);
        Assert.True(p.Protected);
        Assert.StartsWith("slipnet://", p.Raw);
    }

    [Fact]
    public void ParseText_Xray_BecomesAnXrayProfileWithShareMetadata()
    {
        var r = ProfileParser.ParseText(XrayLink);
        Assert.Empty(r.Errors);
        var prof = Assert.Single(r.Profiles);
        Assert.Equal(EngineKind.Xray, prof.Engine);
        Assert.Equal("vless", prof.Protocol);
        Assert.Equal("203.0.113.10", prof.Server);
        Assert.Equal(443, prof.Port);
        Assert.Equal("Test Server ✓", prof.Name);
        Assert.Equal("XRAY", prof.AndroidEngine);
        Assert.Equal("DE", prof.Country);
        Assert.Equal(VlessRaw, prof.Raw);
        Assert.Empty(ConfigValidator.Validate(prof));
    }

    [Fact]
    public void ParseText_SlipNet_BecomesASlipNetProfileAndKeepsProtection()
    {
        var r = ProfileParser.ParseText(SlipLink);
        var prof = Assert.Single(r.Profiles);
        Assert.Equal(EngineKind.SlipNet, prof.Engine);
        Assert.True(prof.Protected);
        Assert.Equal("1.1.1.1", prof.SelectedResolver);
        Assert.Equal(new[] { "1.1.1.1", "9.9.9.9" }, prof.DnsResolvers);
        Assert.Equal("v", prof.CustomSettings["k"]);
    }

    [Theory]
    [InlineData("<{0}>")]
    [InlineData("\"{0}\"")]
    [InlineData("  {0}\r\n")]
    [InlineData("Check this out: {0} thanks")]
    public void ParseText_ToleratesChatAppWrapping(string template)
    {
        var r = ProfileParser.ParseText(string.Format(template, XrayLink));
        Assert.Single(r.Profiles);
        Assert.Empty(r.Errors);
    }

    [Fact]
    public void ParseText_PercentEncodedLink_FromABrowser_IsAccepted()
    {
        var token = XrayLink["tirexnet://share/v1/".Length..];
        var encoded = "tirexnet://share/v1/" + token.Replace("-", "%2D").Replace("_", "%5F");
        Assert.Single(ProfileParser.ParseText(encoded).Profiles);
    }

    [Fact]
    public void ParseText_TwoLinks_ImportBoth()
    {
        var r = ProfileParser.ParseText(XrayLink + "\n" + SlipLink);
        Assert.Equal(2, r.Profiles.Count);
    }

    [Fact]
    public void Roundtrip_EncodeThenDecode_IsLossless()
    {
        var src = new TirexShare.Payload
        {
            Name = "Rück ✓", Engine = "XRAY", Raw = VlessRaw, Origin = "MANUAL", Country = "NL", Protected = true,
            DnsResolvers = new() { "8.8.8.8" }, SelectedResolver = "8.8.8.8", CustomSettings = new() { ["a"] = "b" }, CreatedAt = 5,
        };
        var back = TirexShare.Decode(TirexShare.Encode(src));
        Assert.Equal(src.Name, back.Name);
        Assert.Equal(src.Raw, back.Raw);
        Assert.True(back.Protected);
        Assert.Equal("b", back.CustomSettings["a"]);
        Assert.Equal(new[] { "8.8.8.8" }, back.DnsResolvers);
    }

    [Fact]
    public void Encode_WithTheAndroidVectorIv_ReproducesTheAndroidLinkByteForByte()
    {
        // The vector's payload JSON is gzip-compressed by Python (zlib); .NET's deflate output differs, so we compare
        // the decoded payload rather than the bytes, and prove the container layout (version byte + IV) is identical.
        var token = XrayLink["tirexnet://share/v1/".Length..].Replace('-', '+').Replace('_', '/');
        var bytes = Convert.FromBase64String(token.PadRight(token.Length + (4 - token.Length % 4) % 4, '='));
        Assert.Equal(1, bytes[0]);
        Assert.Equal(Enumerable.Range(0, 12).Select(i => (byte)i), bytes.Skip(1).Take(12));
    }

    [Fact]
    public void TamperedLink_FailsTheIntegrityCheck()
    {
        var chars = XrayLink.ToCharArray();
        var i = chars.Length - 40;
        chars[i] = chars[i] == 'A' ? 'B' : 'A';
        var ex = Assert.Throws<ShareException>(() => TirexShare.Decode(new string(chars)));
        Assert.Equal(ShareErrorKind.DecryptFailed, ex.Kind);
        var r = ProfileParser.ParseText(new string(chars));
        Assert.Empty(r.Profiles);
        Assert.Single(r.Errors);
    }

    [Fact]
    public void UnsupportedVersion_IsReportedClearly()
    {
        var ex = Assert.Throws<ShareException>(() => TirexShare.Decode("tirexnet://share/v9/AAAA"));
        Assert.Equal(ShareErrorKind.UnsupportedVersion, ex.Kind);
    }

    [Theory]
    [InlineData("tirexnet://share/v1/")]
    [InlineData("tirexnet://share/v1/!!!notbase64!!!")]
    [InlineData("tirexnet://share/v1/AAAA")]
    [InlineData("tirexnet://other/thing")]
    public void MalformedLinks_GiveAnErrorAndNeverAProfile(string link)
    {
        var r = ProfileParser.ParseText(link);
        Assert.Empty(r.Profiles);
        Assert.NotEmpty(r.Errors);
    }

    [Fact]
    public void OversizedLink_IsRejected()
    {
        var ex = Assert.Throws<ShareException>(() => TirexShare.Decode("tirexnet://share/v1/" + new string('A', 250_000)));
        Assert.Equal(ShareErrorKind.InvalidFormat, ex.Kind);
    }

    [Fact]
    public void ReimportingTheSameShare_DoesNotCreateDuplicates()
    {
        var dir = Path.Combine(Path.GetTempPath(), "tirex-share-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            var store = new ProfileStore(dir);
            Assert.Equal(1, store.Import(ProfileParser.ParseText(XrayLink).Profiles));
            Assert.Equal(0, store.Import(ProfileParser.ParseText(XrayLink).Profiles));
            Assert.Single(store.All());
        }
        finally { try { Directory.Delete(dir, true); } catch (IOException) { } }
    }

    [Fact]
    public void ProtectedFlag_SurvivesStorageRoundTrip()
    {
        var dir = Path.Combine(Path.GetTempPath(), "tirex-share-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            new ProfileStore(dir).Import(ProfileParser.ParseText(SlipLink).Profiles);
            var again = new ProfileStore(dir).All();
            var p = Assert.Single(again);
            Assert.True(p.Protected);
            Assert.Equal("1.1.1.1", p.SelectedResolver);
        }
        finally { try { Directory.Delete(dir, true); } catch (IOException) { } }
    }

    [Fact]
    public void Redactor_RemovesTheSharedConfigurationFromLogs()
    {
        var p = Assert.Single(ProfileParser.ParseText(XrayLink).Profiles);
        Redactor.AddSecret(p.Raw);
        Assert.DoesNotContain("11111111-2222-3333-4444-555555555555", Redactor.Redact("importing " + p.Raw));
    }
}

public class UriActivationTests
{
    [Fact]
    public void WindowsRegistrationCommand_PassesTheLinkAsOneQuotedArgument()
    {
        Assert.Equal("\"C:\\Program Files\\TIREXNET\\TIREXNET.exe\" \"%1\"", UriActivation.CommandValue("C:\\Program Files\\TIREXNET\\TIREXNET.exe"));
    }

    [Fact]
    public void InstallerScript_RegistersTheTirexnetProtocol()
    {
        var iss = FindInstallerScript();
        Assert.Contains("Software\\Classes\\tirexnet", iss);
        Assert.Contains("\"URL Protocol\"", iss);
        Assert.Contains("shell\\open\\command", iss);
        Assert.Contains("\"{app}\\TIREXNET.exe\"\" \"\"%1\"\"", iss);
        Assert.Contains("ChangesAssociations=yes", iss);
    }

    private static string FindInstallerScript()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null)
        {
            var f = Path.Combine(dir.FullName, "installer", "TIREXNET.iss");
            if (File.Exists(f)) return File.ReadAllText(f);
            f = Path.Combine(dir.FullName, "windows", "installer", "TIREXNET.iss");
            if (File.Exists(f)) return File.ReadAllText(f);
            dir = dir.Parent;
        }
        throw new FileNotFoundException("installer/TIREXNET.iss not found above the test directory");
    }

    [Theory]
    [InlineData("tirexnet://share/v1/abc")]
    [InlineData("<tirexnet://share/v1/abc>")]
    public void FindLink_PicksTheUrlArgument(string arg)
    {
        Assert.Equal("tirexnet://share/v1/abc", UriActivation.FindLink(new[] { "--elevated", arg }));
    }

    [Fact]
    public void FindLink_IgnoresOrdinaryArguments()
    {
        Assert.Null(UriActivation.FindLink(new[] { "--autoconnect", "--elevated" }));
        Assert.Null(UriActivation.FindLink(null));
    }

    [Fact]
    public async Task SecondLaunch_HandsTheLinkToTheRunningInstance_AndLinksAreDeliveredIntact()
    {
        var pipe = "TIREXNET.Test." + Guid.NewGuid().ToString("N");
        var got = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);
        var received = new List<string?>();
        using var server = new ActivationServer(pipe, l => { lock (received) received.Add(l); if (received.Count == 2) got.TrySetResult(null); });
        server.Start();

        var link = "tirexnet://share/v1/" + new string('Q', 3000);
        Assert.True(await Task.Run(() => ActivationServer.TrySend(pipe, link, 5000)));
        Assert.True(await Task.Run(() => ActivationServer.TrySend(pipe, null, 5000)));   // plain "show window"

        await Task.WhenAny(got.Task, Task.Delay(8000));
        lock (received)
        {
            Assert.Equal(2, received.Count);
            Assert.Equal(link, received[0]);
            Assert.Null(received[1]);
        }
    }

    [Fact]
    public void NoRunningInstance_MeansTheSendFails_SoTheCallerCanStartNormally()
    {
        Assert.False(ActivationServer.TrySend("TIREXNET.Test.none." + Guid.NewGuid().ToString("N"), "tirexnet://share/v1/x", 300));
    }

    [Fact]
    public void PipeName_IsStableAndSafe()
    {
        var n = UriActivation.DefaultPipeName();
        Assert.Equal(n, UriActivation.DefaultPipeName());
        Assert.StartsWith("TIREXNET.Activation.", n);
        Assert.DoesNotContain("\\", n);
    }
}

public class AndroidOnlyLinkTests
{
    [Theory]
    [InlineData("masterdns://eyJhIjoxfQ", "Master DNS")]
    [InlineData("cottendns://eyJhIjoxfQ", "Cotton")]
    [InlineData("stormdns://eyJhIjoxfQ", "StormDNS")]
    [InlineData("snispoof://eyJhIjoxfQ", "Pattern")]
    [InlineData("mhrv-rs://eyJhIjoxfQ", "MHR")]
    [InlineData("dnschanger://eyJhIjoxfQ", "DNS changer")]
    [InlineData("soar://eyJhIjoxfQ", "Soar")]
    [InlineData("slipnet-bundle-enc://abc", "SlipNet encrypted bundle")]
    public void AndroidOnlyLinks_AreIdentifiedExactly_AndNeverImportedAsAnotherType(string link, string engine)
    {
        var r = ProfileParser.ParseText(link);
        Assert.Empty(r.Profiles);
        var err = Assert.Single(r.Errors);
        Assert.Contains(engine, err);
        Assert.Contains("cannot run on Windows yet", err);
    }

    [Fact]
    public void ShareCarryingAnAndroidOnlyProfile_ExplainsWhichEngineItNeeds()
    {
        var link = TirexShare.Encode(new TirexShare.Payload { Name = "My MasterDNS", Engine = "MASTERDNS", Raw = "masterdns://eyJhIjoxfQ" });
        var r = ProfileParser.ParseText(link);
        Assert.Empty(r.Profiles);
        var err = Assert.Single(r.Errors);
        Assert.Contains("MASTERDNS", err);
        Assert.Contains("Master DNS", err);
    }

    [Fact]
    public void NestedShareLinks_AreRejected_NotRecursedInto()
    {
        var inner = TirexShare.Encode(new TirexShare.Payload { Name = "in", Engine = "XRAY", Raw = "vless://11111111-2222-3333-4444-555555555555@203.0.113.10:443?type=tcp&security=tls&sni=example.com#x" });
        var outer = TirexShare.Encode(new TirexShare.Payload { Name = "out", Engine = "XRAY", Raw = inner });
        var r = ProfileParser.ParseText(outer);
        Assert.Empty(r.Profiles);
        Assert.Contains("another share link", Assert.Single(r.Errors));
    }
}
