using TIREXNET.Core;
using TIREXNET.Core.Parsing;
using Xunit;

namespace TIREXNET.Tests;

/// <summary>Nothing secret may reach the log, the diagnostics report or an error dialog.</summary>
public class RedactorSecurityTests : IDisposable
{
    public RedactorSecurityTests() => Redactor.Clear();
    public void Dispose() => Redactor.Clear();

    [Theory]
    [InlineData("vless")]
    [InlineData("vmess")]
    [InlineData("trojan")]
    [InlineData("ss")]
    [InlineData("hysteria2")]
    [InlineData("wireguard")]
    [InlineData("slipnet")]
    [InlineData("slipnet-enc")]
    [InlineData("slipnet-bundle-enc")]
    [InlineData("tirexnet")]
    [InlineData("masterdns")]
    [InlineData("cottendns")]
    [InlineData("stormdns")]
    [InlineData("snispoof")]
    [InlineData("mhrv-rs")]
    [InlineData("dnschanger")]
    [InlineData("soar")]
    public void EveryLinkScheme_IsMaskedCompletely(string scheme)
    {
        var text = Redactor.Redact($"importing {scheme}://TOPSECRETPAYLOAD123/abc?x=y#name done");
        Assert.DoesNotContain("TOPSECRETPAYLOAD123", text);
        Assert.DoesNotContain("x=y", text);
        Assert.Contains($"{scheme}://<redacted>", text);
    }

    [Theory]
    [InlineData("Authorization: Basic dXNlcjpwYXNz", "dXNlcjpwYXNz")]
    [InlineData("Cookie: session=abcdef123456", "abcdef123456")]
    [InlineData("api_key=sk_live_123456789", "sk_live_123456789")]
    [InlineData("password: hunter2hunter2", "hunter2hunter2")]
    [InlineData("PresharedKey = abcdefghijklmnop", "abcdefghijklmnop")]
    [InlineData("psk=abcdefghijklmnop", "abcdefghijklmnop")]
    [InlineData("token=ghp_abcdefghijklmnopqrstuv", "ghp_abcdefghijklmnopqrstuv")]
    public void KeyValueSecrets_AreMasked(string line, string secret) =>
        Assert.DoesNotContain(secret, Redactor.Redact(line));

    [Fact]
    public void BearerTokens_AreMasked() =>
        Assert.DoesNotContain("abc.def.ghi", Redactor.Redact("GET /api Authorization: Bearer abc.def.ghi"));

    [Fact]
    public void PemBlocks_AndOpenVpnInlineKeys_AreMasked()
    {
        var text = Redactor.Redact("cfg:\n-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0B\n-----END PRIVATE KEY-----\n<key>\nSECRETKEYBODY\n</key>\n<tls-crypt>\nTLSCRYPTBODY\n</tls-crypt>\nremote host 1194");
        Assert.DoesNotContain("MIIEvQIBADANBgkqhkiG9w0B", text);
        Assert.DoesNotContain("SECRETKEYBODY", text);
        Assert.DoesNotContain("TLSCRYPTBODY", text);
        Assert.Contains("remote host 1194", text);        // ordinary text survives
    }

    [Fact]
    public void UuidsAreMasked_ButOrdinaryTextIsNot()
    {
        var text = Redactor.Redact("user 11111111-2222-3333-4444-555555555555 connected to example.com");
        Assert.DoesNotContain("11111111-2222", text);
        Assert.Contains("connected to example.com", text);
    }

    [Fact]
    public void RegisteredSecrets_AreMaskedWhereverTheyAppear_AndShortOnesAreIgnored()
    {
        Redactor.AddSecret("my-registered-secret");
        Redactor.AddSecret("ab");                  // too short to be a meaningful secret
        Assert.Equal("x *** y ab", Redactor.Redact("x my-registered-secret y ab"));
        Redactor.Clear();
        Assert.Equal("x my-registered-secret", Redactor.Redact("x my-registered-secret"));
    }

    [Fact]
    public void RedactorHandlesEmptyInput()
    {
        Assert.Equal("", Redactor.Redact(""));
        Assert.Null(Redactor.Redact(null!));
    }

    [Fact]
    public void LogLines_NeverContainSecrets()
    {
        Redactor.AddSecret("log-registered-secret");
        string? seen = null;
        void Handler(string l) => seen = l;
        Log.LineAdded += Handler;
        try
        {
            Log.Info("connecting vless://11111111-2222-3333-4444-555555555555@203.0.113.10:443 using log-registered-secret and password=hunter2hunter2");
        }
        finally { Log.LineAdded -= Handler; }
        Assert.NotNull(seen);
        Assert.DoesNotContain("203.0.113.10", seen);
        Assert.DoesNotContain("log-registered-secret", seen);
        Assert.DoesNotContain("hunter2hunter2", seen);
        Assert.DoesNotContain("11111111-2222", seen);
        Assert.DoesNotContain("log-registered-secret", string.Join("\n", Log.Snapshot()));
    }

    [Fact]
    public void ImportingAShareLink_RegistersItsConfigurationAsASecret()
    {
        var raw = "vless://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee@198.51.100.7:443?type=tcp&security=tls&sni=secret-sni.example#Home";
        var link = TirexShare.Encode(new TirexShare.Payload { Name = "n", Engine = "XRAY", Raw = raw });
        var result = ProfileParser.ParseText(link);
        Assert.Single(result.Profiles);
        var redacted = Redactor.Redact("failed: " + raw + " / " + link);
        Assert.DoesNotContain("198.51.100.7", redacted);
        Assert.DoesNotContain("secret-sni.example", redacted);
        Assert.DoesNotContain(link[20..60], redacted);
    }

    [Fact]
    public void ErrorMessagesFromBadShares_DoNotEchoThePayload()
    {
        var ex = Assert.Throws<ShareException>(() => TirexShare.Decode("tirexnet://share/v1/QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5"));
        Assert.DoesNotContain("QUJDREVG", ex.Message);
        var r = ProfileParser.ParseText("tirexnet://share/v9/QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5");
        Assert.All(r.Errors, e => Assert.DoesNotContain("QUJDREVG", e));
    }
}
