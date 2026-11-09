using System.Diagnostics;
using TIREXNET.Core.Engines;
using Xunit;

namespace TIREXNET.Tests;

/// <summary>Exercises the real process wrapper with built-in Windows tools (no network, no admin).</summary>
public class ProcessLifecycleTests
{
    private static string Cmd => Path.Combine(Environment.SystemDirectory, "cmd.exe");
    private static string Ping => Path.Combine(Environment.SystemDirectory, "PING.EXE");

    [Fact]
    public async Task Output_IsCaptured()
    {
        var lines = new List<string>();
        using var p = ManagedProcess.Start("echo", Cmd, new[] { "/c", "echo hello-tirex" });
        p.OutputLine += l => lines.Add(l);
        for (var i = 0; i < 50 && p.IsRunning; i++) await Task.Delay(100);
        await Task.Delay(300);
        Assert.Contains("hello-tirex", p.LastLines());
    }

    [Fact]
    public async Task StopAsync_KillsProcess_WithoutRaisingUnexpectedExit()
    {
        var unexpected = 0;
        using var p = ManagedProcess.Start("pinger", Ping, new[] { "-n", "60", "127.0.0.1" });
        p.UnexpectedExit += _ => Interlocked.Increment(ref unexpected);
        Assert.True(p.IsRunning);
        var pid = p.Id;
        await p.StopAsync();
        Assert.False(p.IsRunning);
        await Task.Delay(300);
        Assert.Equal(0, unexpected);
        Assert.ThrowsAny<Exception>(() => { using var gone = Process.GetProcessById(pid); if (!gone.HasExited) throw new InvalidOperationException("still alive"); });
    }

    [Fact]
    public async Task UnexpectedExit_IsReported_WithExitCode()
    {
        var tcs = new TaskCompletionSource<int>();
        using var p = ManagedProcess.Start("crasher", Cmd, new[] { "/c", "exit 7" });
        p.UnexpectedExit += c => tcs.TrySetResult(c);
        var finished = await Task.WhenAny(tcs.Task, Task.Delay(5000));
        Assert.Same(tcs.Task, finished);
        Assert.Equal(7, tcs.Task.Result);
    }

    [Fact]
    public async Task Stdin_IsDelivered_ToChild()
    {
        // 'findstr' echoes matching stdin lines: proves the config-over-stdin mechanism used for Xray works.
        var findstr = Path.Combine(Environment.SystemDirectory, "findstr.exe");
        var seen = new TaskCompletionSource<string>();
        using var p = ManagedProcess.Start("stdin", findstr, new[] { "tirex" }, null, "alpha\ntirex-config-line\nbeta\n");
        p.OutputLine += l => seen.TrySetResult(l);
        var done = await Task.WhenAny(seen.Task, Task.Delay(5000));
        Assert.Same(seen.Task, done);
        Assert.Contains("tirex-config-line", seen.Task.Result);
    }

    [Fact]
    public void MissingBinary_GivesClearError()
    {
        var ex = Assert.Throws<FileNotFoundException>(() => ManagedProcess.Start("xray", @"C:\definitely\missing\xray.exe", Array.Empty<string>()));
        Assert.Contains("runtime not found", ex.Message);
    }

    [Fact]
    public async Task Cmd_RunAsync_PassesArgumentsWithoutShellInterpretation()
    {
        // The '&' must arrive as a literal argument, not run a second command.
        var (code, output) = await TIREXNET.Core.Engines.Cmd.RunAsync(Cmd, new[] { "/c", "echo", "a&b" });
        Assert.Equal(0, code);
        Assert.Contains("a", output);
    }

    [Fact]
    public async Task ConnectionManager_WithMissingRuntime_FailsCleanly_AndStaysDisconnectable()
    {
        Environment.SetEnvironmentVariable("TIREXNET_MACHINE_DIR", Path.Combine(Path.GetTempPath(), "tirex-m-" + Guid.NewGuid().ToString("N")));
        using var m = new ConnectionManager();
        var profile = TIREXNET.Core.Parsing.ProfileParser.ParseText("trojan://pw@t.example.com:443#t").Profiles.Single();
        await m.ConnectAsync(profile, new TIREXNET.Core.Models.AppSettings());
        Assert.Equal(TIREXNET.Core.Models.ConnectionState.Error, m.State);
        Assert.Contains("Xray runtime is missing", m.Message);
        await m.DisconnectAsync();
        Assert.Equal(TIREXNET.Core.Models.ConnectionState.Disconnected, m.State);
        Assert.False(RunState.HasPending());
    }
}
