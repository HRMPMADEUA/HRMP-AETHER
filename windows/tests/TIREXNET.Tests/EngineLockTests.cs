using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Xunit;

namespace TIREXNET.Tests;

/// <summary>
/// Regression for the V9.2 CI failure: the SlipNet CLI go.mod replaces github.com/net2share/vaydns with ../vaydns (and noizdns,
/// vaydns-mobile, dnstt likewise). Those are git submodules that a plain clone leaves empty, so the build must initialise them
/// at pinned revisions and verify the replace targets before invoking Go.
/// </summary>
public class EngineLockTests
{
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

    private static JsonNode SlipNet() => JsonNode.Parse(File.ReadAllText(FindUp("scripts", "engines.lock.json")))!["slipnet"]!;

    [Fact]
    public void SlipNetLock_PinsTheCommit_AndEverySubmoduleTheCliNeeds()
    {
        var sn = SlipNet();
        Assert.Matches("^[0-9a-f]{40}$", sn["commit"]!.GetValue<string>());
        var subs = sn["submodules"]!.AsArray();
        var paths = subs.Select(s => s!["path"]!.GetValue<string>()).OrderBy(x => x).ToArray();
        Assert.Equal(new[] { "dnstt", "noizdns", "vaydns", "vaydns-mobile" }, paths);
        foreach (var s in subs)
        {
            Assert.Matches("^[0-9a-f]{40}$", s!["commit"]!.GetValue<string>());
            Assert.StartsWith("https://github.com/anonvector/", s["url"]!.GetValue<string>());
        }
    }

    [Fact]
    public void FetchScript_InitialisesSubmodulesAtPinnedRevisions_AndChecksReplaceTargets()
    {
        var script = File.ReadAllText(FindUp("scripts", "fetch-engines.ps1"));
        Assert.Contains("submodule update --init", script);
        Assert.Contains("$sn.submodules", script);
        Assert.Contains("replaces a module with", script);          // replace-directive preflight with a precise message
        Assert.Contains("slipnet.exe is not a valid Windows executable", script);
        Assert.Contains("SlipNet is a required runtime", script);   // a SlipNet build failure is never silent
        Assert.Contains("$global:LASTEXITCODE = 0", script);         // optional-step exit codes must not fail the step
        Assert.DoesNotMatch(new Regex(@"git\s+clone[^\r\n]*--recurse", RegexOptions.IgnoreCase), script);   // pinned per-submodule init, not a floating recursive clone
    }

    [Fact]
    public void SlipNetDocs_StayInSyncWithTheLockedCommit()
    {
        var commit = SlipNet()["commit"]!.GetValue<string>();
        Assert.Contains(commit, File.ReadAllText(FindUp("THIRD_PARTY_NOTICES.md")));
    }
}
