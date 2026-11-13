using System.IO.Pipes;
using System.Text;
using TIREXNET.Core.Parsing;

namespace TIREXNET.Core.Services;

/// <summary>Finds a tirexnet:// link in command-line arguments (Windows passes the clicked URL as one argument).</summary>
public static class UriActivation
{
    public const int MaxMessageChars = 200_000;

    public static string? FindLink(IEnumerable<string>? args)
    {
        if (args == null) return null;
        foreach (var a in args)
        {
            if (string.IsNullOrWhiteSpace(a)) continue;
            var v = TirexShare.Unwrap(a);
            if (v.StartsWith("tirexnet:", StringComparison.OrdinalIgnoreCase)) return v;
        }
        return null;
    }

    /// <summary>The value written under HKLM\Software\Classes\tirexnet\shell\open\command by the installer.</summary>
    public static string CommandValue(string exePath) => $"\"{exePath}\" \"%1\"";

    public static string DefaultPipeName()
    {
        var user = new string(Environment.UserName.Where(char.IsLetterOrDigit).ToArray());
        return "TIREXNET.Activation." + (user.Length == 0 ? "user" : user);
    }
}

/// <summary>
/// Single-instance hand-over. A second process sends its link (or a plain "show" request) to the running instance over a
/// current-user-only named pipe and exits; the running instance imports it. No duplicate instance is ever started.
/// </summary>
public sealed class ActivationServer : IDisposable
{
    private readonly string _pipe;
    private readonly Action<string?> _onActivate;
    private readonly CancellationTokenSource _cts = new();
    private Task? _loop;

    public ActivationServer(string pipeName, Action<string?> onActivate)
    {
        _pipe = pipeName;
        _onActivate = onActivate;
    }

    public void Start() => _loop = Task.Run(LoopAsync);

    private async Task LoopAsync()
    {
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                await using var server = new NamedPipeServerStream(_pipe, PipeDirection.In, 4,
                    PipeTransmissionMode.Byte, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await server.WaitForConnectionAsync(_cts.Token).ConfigureAwait(false);
                using var limit = CancellationTokenSource.CreateLinkedTokenSource(_cts.Token);
                limit.CancelAfter(TimeSpan.FromSeconds(5));
                var message = await ReadAllAsync(server, limit.Token).ConfigureAwait(false);
                if (message == null) continue;
                var link = message.StartsWith("OPEN\n", StringComparison.Ordinal) ? message[5..].Trim() : null;
                _onActivate(string.IsNullOrWhiteSpace(link) ? null : link);
            }
            catch (OperationCanceledException) { /* shutdown or slow client */ }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ObjectDisposedException)
            {
                try { await Task.Delay(250, _cts.Token).ConfigureAwait(false); } catch (OperationCanceledException) { }
            }
        }
    }

    private static async Task<string?> ReadAllAsync(Stream s, CancellationToken ct)
    {
        var buf = new byte[8192];
        using var ms = new MemoryStream();
        int n;
        while ((n = await s.ReadAsync(buf, ct).ConfigureAwait(false)) > 0)
        {
            ms.Write(buf, 0, n);
            if (ms.Length > UriActivation.MaxMessageChars * 4L) return null;
        }
        var text = Encoding.UTF8.GetString(ms.ToArray());
        return text.Length > UriActivation.MaxMessageChars ? null : text;
    }

    public void Dispose()
    {
        _cts.Cancel();
        try { _loop?.Wait(1000); } catch (AggregateException) { }
        _cts.Dispose();
    }

    /// <summary>Hands <paramref name="link"/> (or just "show the window" when null) to the running instance.</summary>
    public static bool TrySend(string pipeName, string? link, int timeoutMs = 3000)
    {
        try
        {
            using var client = new NamedPipeClientStream(".", pipeName, PipeDirection.Out, PipeOptions.CurrentUserOnly);
            client.Connect(timeoutMs);
            var bytes = Encoding.UTF8.GetBytes(link == null ? "SHOW\n" : "OPEN\n" + link);
            client.Write(bytes, 0, bytes.Length);
            client.Flush();
            return true;
        }
        catch (Exception ex) when (ex is TimeoutException or IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }
}
