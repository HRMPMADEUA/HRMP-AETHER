namespace TIREXNET.Core.Parsing;

/// <summary>
/// Link types the Android app understands but that have no Windows runtime in this build. They are recognised so the user
/// gets an exact explanation instead of "unrecognized entry" - and are never imported, so nothing is shown as supported
/// that cannot run. Runtime references are from the Android sources (engine/*.kt) and THIRD_PARTY.md.
/// </summary>
public static class AndroidOnlyLinks
{
    private static readonly (string Scheme, string Engine, string Why)[] Table =
    {
        ("masterdns://", "Master DNS", "Master DNS profiles run the MasterDnsVPN client, which this Windows build does not ship yet."),
        ("cottendns://", "Cotton DNS (CottenDNS)", "Cotton DNS profiles run the CottenDNS client, which this Windows build does not ship yet."),
        ("stormdns://", "StormDNS", "StormDNS profiles run the StormDNS client, which this Windows build does not ship yet."),
        ("snispoof://", "Pattern (SNI spoof)", "Pattern profiles need the SNISPF relay combined with an Xray outbound rewrite, which this Windows build does not implement yet."),
        ("mhrv-rs://", "MHR (MHRV)", "MHR profiles run the MHRV Rust core, which this Windows build does not ship yet."),
        ("dnschanger://", "DNS changer", "DNS changer profiles are not implemented in this Windows build."),
        ("soar://", "Soar", "Soar profiles run on the SlipNet engine with the Soar transport, which is not part of the upstream SlipNet command-line runtime for Windows."),
        ("slipnet-bundle-enc://", "SlipNet encrypted bundle", "Encrypted SlipNet bundles are not supported yet; import the individual slipnet:// links."),
    };

    public static bool TryIdentify(string line, out string engine, out string message)
    {
        foreach (var (scheme, name, why) in Table)
            if (line.TrimStart().StartsWith(scheme, StringComparison.OrdinalIgnoreCase))
            {
                engine = name;
                message = $"{name} link recognised, but it cannot run on Windows yet. {why} It was not imported (it works in the Android app).";
                return true;
            }
        engine = "";
        message = "";
        return false;
    }
}
