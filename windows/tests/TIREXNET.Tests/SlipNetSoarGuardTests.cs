using System.Text;
using TIREXNET.Core.Parsing;
using Xunit;

namespace TIREXNET.Tests;

public sealed class SlipNetSoarGuardTests
{
    private static string MakeLink(string tunnelType)
    {
        var fields = Enumerable.Repeat("", 70).ToArray();
        fields[0] = "18";
        fields[1] = tunnelType;
        fields[2] = "Soar regression test";
        fields[3] = "example.com";
        fields[4] = "1.1.1.1:53:0";

        var payload = string.Join("|", fields);
        return "slipnet://" +
            Convert.ToBase64String(Encoding.UTF8.GetBytes(payload));
    }

    [Theory]
    [InlineData("soar")]
    [InlineData("soar_ssh")]
    public void SoarProfilesMustNotBeReportedAsSupported(string tunnelType)
    {
        var link = MakeLink(tunnelType);

        Assert.True(SlipNetProfile.TryParse(link, out var profile, out var error), error);
        Assert.NotNull(profile);
        Assert.True(profile!.IsSoar);
        Assert.False(SlipNetProfile.IsSupportedTunnel(tunnelType));

        var reason = SlipNetProfile.UnsupportedReason(profile);
        Assert.NotNull(reason);
        Assert.Contains("Soar is not yet supported", reason);

        var errors = SlipNetProfile.Validate(link);
        Assert.Contains(errors, x => x.Contains("Soar is not yet supported"));
    }

    [Theory]
    [InlineData("dnstt")]
    [InlineData("sayedns")]
    [InlineData("vaydns")]
    [InlineData("ssh")]
    [InlineData("socks5")]
    [InlineData("vless")]
    [InlineData("doh")]
    public void ExistingCliTunnelTypesRemainSupported(string tunnelType)
    {
        Assert.True(SlipNetProfile.IsSupportedTunnel(tunnelType));
    }
}
