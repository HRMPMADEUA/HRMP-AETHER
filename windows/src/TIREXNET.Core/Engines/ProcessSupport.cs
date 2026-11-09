using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace TIREXNET.Core.Engines;

/// <summary>
/// A Windows Job Object with KILL_ON_JOB_CLOSE. Every engine process is assigned to it, so if TIREXNET itself
/// crashes or is killed, Windows terminates the engines instead of leaving them running in the background.
/// </summary>
internal static class JobObject
{
    private static IntPtr _job = IntPtr.Zero;
    private static readonly object Gate = new();

    [StructLayout(LayoutKind.Sequential)]
    private struct BasicLimit
    {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IoCounters
    {
        public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount;
        public ulong ReadTransferCount, WriteTransferCount, OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ExtendedLimit
    {
        public BasicLimit BasicLimitInformation;
        public IoCounters IoInfo;
        public UIntPtr ProcessMemoryLimit;
        public UIntPtr JobMemoryLimit;
        public UIntPtr PeakProcessMemoryUsed;
        public UIntPtr PeakJobMemoryUsed;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateJobObject(IntPtr attrs, string? name);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetInformationJobObject(IntPtr job, int infoClass, IntPtr info, uint size);

    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);

    private const int JobObjectExtendedLimitInformation = 9;
    private const uint KillOnJobClose = 0x2000;

    private static bool EnsureJob()
    {
        lock (Gate)
        {
            if (_job != IntPtr.Zero) return true;
            var job = CreateJobObject(IntPtr.Zero, null);
            if (job == IntPtr.Zero) return false;
            var info = new ExtendedLimit();
            info.BasicLimitInformation.LimitFlags = KillOnJobClose;
            var size = Marshal.SizeOf<ExtendedLimit>();
            var ptr = Marshal.AllocHGlobal(size);
            try
            {
                Marshal.StructureToPtr(info, ptr, false);
                if (!SetInformationJobObject(job, JobObjectExtendedLimitInformation, ptr, (uint)size)) return false;
            }
            finally
            {
                Marshal.FreeHGlobal(ptr);
            }
            _job = job;
            return true;
        }
    }

    public static void Assign(Process process)
    {
        try
        {
            if (!EnsureJob() || !AssignProcessToJobObject(_job, process.Handle))
                Log.Warn($"Could not attach {process.ProcessName} to the cleanup job (Win32 error {Marshal.GetLastWin32Error()}).");
        }
        catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            // The process may already have exited.
        }
    }
}

/// <summary>Runs a helper tool (netsh, route, …) with safe argument handling: arguments are never joined into a shell string.</summary>
public static class Cmd
{
    public static async Task<(int ExitCode, string Output)> RunAsync(string file, IEnumerable<string> args, int timeoutMs = 20000)
    {
        var psi = new ProcessStartInfo(file)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        using var p = new Process { StartInfo = psi };
        var sb = new StringBuilder();
        p.OutputDataReceived += (_, e) => { if (e.Data != null) lock (sb) sb.AppendLine(e.Data); };
        p.ErrorDataReceived += (_, e) => { if (e.Data != null) lock (sb) sb.AppendLine(e.Data); };
        try
        {
            p.Start();
        }
        catch (System.ComponentModel.Win32Exception ex)
        {
            return (-1, $"Could not start {Path.GetFileName(file)}: {ex.Message}");
        }
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();
        using var cts = new CancellationTokenSource(timeoutMs);
        try
        {
            await p.WaitForExitAsync(cts.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            try { p.Kill(true); } catch { /* ignore */ }
            return (-2, "Timed out waiting for " + Path.GetFileName(file));
        }
        p.WaitForExit(); // flush async readers
        lock (sb) return (p.ExitCode, sb.ToString().Trim());
    }

    public static string SystemExe(string name) => Path.Combine(Environment.SystemDirectory, name);
}

/// <summary>An engine child process with output capture, exit notification and guaranteed termination.</summary>
public sealed class ManagedProcess : IDisposable
{
    private readonly Process _process;
    private readonly ConcurrentQueue<string> _tail = new();
    private volatile bool _stopping;
    private int _exitRaised;

    public string Name { get; }
    public event Action<string>? OutputLine;
    /// <summary>Raised once when the process ends without <see cref="StopAsync"/> having been called.</summary>
    public event Action<int>? UnexpectedExit;

    public int Id => _process.Id;
    public bool IsRunning
    {
        get
        {
            try { return !_process.HasExited; }
            catch (InvalidOperationException) { return false; }
        }
    }

    public int? ExitCode
    {
        get
        {
            try { return _process.HasExited ? _process.ExitCode : null; }
            catch (InvalidOperationException) { return null; }
        }
    }

    private ManagedProcess(string name, Process process)
    {
        Name = name;
        _process = process;
    }

    public static ManagedProcess Start(string name, string exe, IEnumerable<string> args, string? workingDir = null,
        string? stdin = null, IDictionary<string, string>? env = null)
    {
        if (!File.Exists(exe)) throw new FileNotFoundException($"{name} runtime not found: {Path.GetFileName(exe)}");
        var psi = new ProcessStartInfo(exe)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            RedirectStandardInput = stdin != null,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
            WorkingDirectory = workingDir ?? Path.GetDirectoryName(exe) ?? AppContext.BaseDirectory,
        };
        if (stdin != null) psi.StandardInputEncoding = new UTF8Encoding(false);
        foreach (var a in args) psi.ArgumentList.Add(a);
        if (env != null) foreach (var kv in env) psi.Environment[kv.Key] = kv.Value;

        var p = new Process { StartInfo = psi, EnableRaisingEvents = true };
        var mp = new ManagedProcess(name, p);
        p.OutputDataReceived += (_, e) => mp.OnLine(e.Data);
        p.ErrorDataReceived += (_, e) => mp.OnLine(e.Data);
        p.Exited += (_, _) => mp.OnExited();
        p.Start();
        JobObject.Assign(p);
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();
        if (stdin != null)
        {
            try
            {
                p.StandardInput.Write(stdin);
                p.StandardInput.Close();
            }
            catch (IOException)
            {
                // The process died before reading its config; the exit event reports it.
            }
        }
        return mp;
    }

    private void OnLine(string? line)
    {
        if (string.IsNullOrWhiteSpace(line)) return;
        _tail.Enqueue(line);
        while (_tail.Count > 12) _tail.TryDequeue(out _);
        OutputLine?.Invoke(line);
    }

    private void OnExited()
    {
        if (_stopping) return;
        if (Interlocked.Exchange(ref _exitRaised, 1) == 1) return;
        int code;
        try { code = _process.ExitCode; } catch (InvalidOperationException) { code = -1; }
        UnexpectedExit?.Invoke(code);
    }

    public string LastLines(int count = 4) => string.Join(" | ", _tail.Reverse().Take(count).Reverse());

    public async Task StopAsync(int timeoutMs = 4000)
    {
        _stopping = true;
        try
        {
            if (!_process.HasExited)
            {
                _process.Kill(true);
                using var cts = new CancellationTokenSource(timeoutMs);
                try { await _process.WaitForExitAsync(cts.Token).ConfigureAwait(false); }
                catch (OperationCanceledException) { Log.Warn($"{Name} did not exit within {timeoutMs} ms."); }
            }
        }
        catch (InvalidOperationException)
        {
            // already gone
        }
        catch (System.ComponentModel.Win32Exception ex)
        {
            Log.Warn($"Could not stop {Name}: {ex.Message}");
        }
    }

    public void Dispose()
    {
        _stopping = true;
        try { if (!_process.HasExited) _process.Kill(true); } catch { /* ignore */ }
        _process.Dispose();
    }
}
