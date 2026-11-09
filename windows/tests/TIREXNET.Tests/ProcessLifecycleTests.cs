using System.Diagnostics;
using TIREXNET.Core.Engines;
using TIREXNET.Core.Models;
using TIREXNET.Core.Parsing;
using Xunit;

namespace TIREXNET.Tests;

/// <summary>Exercises the real process wrapper with built-in Windows tools (no network, no administrator rights).</summary>
public class ProcessLifecycleTests
{
    private static string CmdExe => Path.Combine(Environment.SystemDirectory, "cmd.exe");
    private static string PingExe => Path.Combine(Environment.SystemDirectory, "PING.EXE");
    private static string FindstrExe => Path.Combine(Environment.SystemDirectory, "findstr.exe");

    private static async Task<T> WithTimeout<T>(Task<T> task, int ms = 10000)
    {
        var done = await Task.WhenAny(task, Task.Delay(ms));
        Assert.True(ReferenceEquals(done, task), "Timed out waiting for the process.");
        return await task;
    }

    [Fact]
    public async Task Output_IsCaptured()
    {
        var seen = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        using var p = ManagedProcess.Start("echo", CmdExe, new[] { "/c", "echo hello-tirex" });
        p.OutputLine += l => { if (l.Contains("hello-tirex")) seen.TrySetResult(l); };
        // The line may already have been read before the handler was attached; LastLines covers that case.
        for (var i = 0; i < 50 && !seen.Task.IsCompleted && !p.LastLines().Contains("hello-tirex"); i++) await Task.Delay(100);
        Assert.Contains("hello-tirex", p.LastLines());
    }

    [Fact]
    public async Task StopAsync_KillsProcess_WithoutRaisingUnexpectedExit()
    {
        var unexpected = 0;
        using var p = ManagedProcess.Start("pinger", PingExe, new[] { "-n", "60", "127.0.0.1" });
        p.UnexpectedExit += _ => Interlocked.Increment(ref unexpected);
        Assert.True(p.IsRunning);
        var pid = p.Id;
        await p.StopAsync();
        Assert.False(p.IsRunning);
        await Task.Delay(300);
        Assert.Equal(0, unexpected);
        Assert.ThrowsAny<Exception>(() =>
        {
            using var gone = Process.GetProcessById(pid);
            if (!gone.HasExited) throw new InvalidOperationException("still alive");
        });
    }

    [Fact]
    public async Task UnexpectedExit_IsReported_WithExitCode()
    {
        var tcs = new TaskCompletionSource<int>(TaskCreationOptions.RunContinuationsAsynchronously);
        using var p = ManagedProcess.Start("crasher", CmdExe, new[] { "/c", "exit 7" });
        p.UnexpectedExit += c => tcs.TrySetResult(c);
        // If the process already ended before the handler was attached, ExitCode still tells the truth.
        if (!p.IsRunning && p.ExitCode == 7) tcs.TrySetResult(7);
        Assert.Equal(7, await WithTimeout(tcs.Task, 5000));
    }

    [Fact]
    public async Task Stdin_IsDelivered_ToChild()
    {
        // findstr echoes matching stdin lines: proves the config-over-stdin mechanism used for Xray works.
        var seen = new TaskCompletionSource<string>(TaskCreationOptions.RunContinuationsAsynchronously);
        using var p = ManagedProcess.Start("stdin", FindstrExe, new[] { "tirex" }, null, "alpha\ntirex-config-line\nbeta\n");
        p.OutputLine += l => seen.TrySetResult(l);
        for (var i = 0; i < 50 && !seen.Task.IsCompleted && !p.LastLines().Contains("tirex-config-line"); i++) await Task.Delay(100);
        Assert.Contains("tirex-config-line", p.LastLines());
    }

    [Fact]
    public void MissingBinary_GivesClearError()
    {
        var ex = Assert.Throws<FileNotFoundException>(() => ManagedProcess.Start("xray", @"C:\definitely\missing\xray.exe", Array.Empty<string>()));
        Assert.Contains("runtime not found", ex.Message);
    }

    [Fact]
    public async Task Cmd_RunAsync_MissingExecutable_ReturnsErrorInsteadOfThrowing()
    {
        var (code, output) = await TIREXNET.Core.Engines.Cmd.RunAsync(@"C:\definitely\missing\tool.exe", new[] { "x" });
        Assert.NotEqual(0, code);
        Assert.Contains("Could not start", output);
    }

    [Fact]
    public async Task Cmd_RunAsync_PassesArgumentsLiterally_WithoutShellInterpretation()
    {
        // findstr is a normal executable (no shell). The argument "/C:a&b" must arrive as ONE literal argument.
        // If a shell interpreted '&', findstr would receive "/C:a" and the line "x a" would also match.
        var dir = Path.Combine(Path.GetTempPath(), "tirex-args-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        try
        {
            var file = Path.Combine(dir, "lines with spaces.txt");
            await File.WriteAllTextAsync(file, "first a&b line\nsecond a line\nthird b line\n");

            var (code, output) = await TIREXNET.Core.Engines.Cmd.RunAsync(FindstrExe, new[] { "/C:a&b", file });
            Assert.Equal(0, code);
            Assert.Contains("first a&b line", output);
            Assert.DoesNotContain("second a line", output);
            Assert.DoesNotContain("third b line", output);

            // Paths with spaces are passed intact as well (the file above lives in a directory and has spaces in its name).
            Assert.Contains("lines with spaces", file);
        }
        finally
        {
            Directory.Delete(dir, true);
        }
    }

    [Fact]
    public async Task Cmd_RunAsync_Timeout_KillsTheProcess()
    {
        var (code, output) = await TIREXNET.Core.Engines.Cmd.RunAsync(PingExe, new[] { "-n", "30", "127.0.0.1" }, 800);
        Assert.Equal(-2, code);
        Assert.Contains("Timed out", output);
    }

    [Fact]
    public async Task ConnectionManager_WithMissingRuntime_FailsCleanly_AndStaysDisconnectable()
    {
        var machine = Path.Combine(Path.GetTempPath(), "tirex-m-" + Guid.NewGuid().ToString("N"));
        var previous = Environment.GetEnvironmentVariable("TIREXNET_MACHINE_DIR");
        Environment.SetEnvironmentVariable("TIREXNET_MACHINE_DIR", machine);
        try
        {
            using var m = new ConnectionManager();
            var profile = ProfileParser.ParseText("trojan://pw@t.example.com:443#t").Profiles.Single();
            await m.ConnectAsync(profile, new AppSettings());
            Assert.Equal(ConnectionState.Error, m.State);
            Assert.Contains("Xray runtime is missing", m.Message);
            await m.DisconnectAsync();
            Assert.Equal(ConnectionState.Disconnected, m.State);
            Assert.False(RunState.HasPending());
        }
        finally
        {
            Environment.SetEnvironmentVariable("TIREXNET_MACHINE_DIR", previous);
            if (Directory.Exists(machine)) Directory.Delete(machine, true);
        }
    }

    [Fact]
    public async Task ConnectionManager_WireGuard_WithoutAdmin_Or_Runtime_FailsWithClearMessage()
    {
        using var m = new ConnectionManager();
        var profile = ProfileParser.ParseText(ProfileParserTests.WgConf, "wg.conf").Profiles.Single();
        await m.ConnectAsync(profile, new AppSettings());
        Assert.Equal(ConnectionState.Error, m.State);
        Assert.False(string.IsNullOrWhiteSpace(m.Message));
        Assert.Contains("WireGuard", m.Message);
        await m.DisconnectAsync();
        Assert.Equal(ConnectionState.Disconnected, m.State);
    }

    [Fact]
    public async Task ConnectionManager_InvalidProfile_IsRejectedBeforeAnythingStarts()
    {
        using var m = new ConnectionManager();
        var bad = new Profile { Name = "bad", Engine = EngineKind.Xray, Protocol = "vless", Raw = "vless://not-valid" };
        await m.ConnectAsync(bad, new AppSettings());
        Assert.Equal(ConnectionState.Error, m.State);
        Assert.Contains("not valid", m.Message);
    }
}
