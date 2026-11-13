using System.Net;
using System.Net.Sockets;
using System.Text;

namespace TIREXNET.Core.Engines;

/// <summary>
/// Exposes a local HTTP proxy and forwards HTTP and CONNECT requests
/// through the DNS engine's local SOCKS5 listener.
/// </summary>
internal sealed class HttpSocks5Bridge : IAsyncDisposable
{
    private readonly int _httpPort;
    private readonly int _socksPort;
    private TcpListener? _listener;
    private CancellationTokenSource? _cts;
    private Task? _acceptTask;

    public HttpSocks5Bridge(int httpPort, int socksPort)
    {
        _httpPort = httpPort;
        _socksPort = socksPort;
    }

    public Task StartAsync(CancellationToken ct)
    {
        ct.ThrowIfCancellationRequested();

        _listener = new TcpListener(IPAddress.Loopback, _httpPort);
        _listener.Start(128);
        _cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _acceptTask = AcceptLoopAsync(_cts.Token);

        Log.Info($"HTTP proxy bridge listening on 127.0.0.1:{_httpPort}.");
        return Task.CompletedTask;
    }

    private async Task AcceptLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener!.AcceptTcpClientAsync(ct);
            }
            catch (OperationCanceledException) { break; }
            catch (ObjectDisposedException) { break; }
            catch (SocketException) when (ct.IsCancellationRequested) { break; }

            _ = HandleClientAsync(client, ct);
        }
    }

    private async Task HandleClientAsync(TcpClient incoming, CancellationToken ct)
    {
        using (incoming)
        {
            TcpClient? upstream = null;
            try
            {
                incoming.NoDelay = true;
                var input = incoming.GetStream();
                var header = await ReadHeaderAsync(input, ct);

                if (header == null)
                    return;

                var text = Encoding.ASCII.GetString(header);
                var lines = text.Split("\r\n");
                if (lines.Length == 0)
                    return;

                var first = lines[0].Split(' ', 3);
                if (first.Length < 3)
                    return;

                var method = first[0].Trim();
                var target = first[1].Trim();
                var version = first[2].Trim();

                string host;
                int port;
                string requestLine;

                if (method.Equals("CONNECT", StringComparison.OrdinalIgnoreCase))
                {
                    if (!TryHostPort(target, 443, out host, out port))
                    {
                        await WriteErrorAsync(input, 400, "Bad Request", ct);
                        return;
                    }

                    requestLine = "";
                }
                else
                {
                    if (!Uri.TryCreate(target, UriKind.Absolute, out var uri) ||
                        (uri.Scheme != "http" && uri.Scheme != "https"))
                    {
                        await WriteErrorAsync(input, 400, "Bad Request", ct);
                        return;
                    }

                    host = uri.Host;
                    port = uri.IsDefaultPort
                        ? (uri.Scheme == "https" ? 443 : 80)
                        : uri.Port;

                    if (uri.Scheme == "https")
                    {
                        await WriteErrorAsync(input, 400, "Use CONNECT for HTTPS", ct);
                        return;
                    }

                    var path = string.IsNullOrEmpty(uri.PathAndQuery)
                        ? "/"
                        : uri.PathAndQuery;

                    requestLine = $"{method} {path} {version}\r\n";
                }

                upstream = new TcpClient();
                upstream.NoDelay = true;
                await upstream.ConnectAsync(IPAddress.Loopback, _socksPort, ct);
                var socks = upstream.GetStream();

                await SocksConnectAsync(socks, host, port, ct);

                if (method.Equals("CONNECT", StringComparison.OrdinalIgnoreCase))
                {
                    await input.WriteAsync(
                        Encoding.ASCII.GetBytes(
                            "HTTP/1.1 200 Connection Established\r\n" +
                            "Proxy-Agent: TIREXNET\r\n\r\n"), ct);
                }
                else
                {
                    var output = new StringBuilder();
                    output.Append(requestLine);

                    for (int i = 1; i < lines.Length; i++)
                    {
                        var line = lines[i];
                        if (line.Length == 0)
                            break;

                        if (line.StartsWith("Proxy-Connection:", StringComparison.OrdinalIgnoreCase))
                            continue;

                        if (line.StartsWith("Connection:", StringComparison.OrdinalIgnoreCase))
                            continue;

                        output.Append(line).Append("\r\n");
                    }

                    output.Append("Connection: close\r\n\r\n");
                    await socks.WriteAsync(
                        Encoding.ASCII.GetBytes(output.ToString()), ct);
                }

                using var relayCts = CancellationTokenSource.CreateLinkedTokenSource(ct);
                var toServer = input.CopyToAsync(socks, relayCts.Token);
                var toClient = socks.CopyToAsync(input, relayCts.Token);

                await Task.WhenAny(toServer, toClient);
                relayCts.Cancel();

                try { await Task.WhenAll(toServer, toClient); }
                catch (OperationCanceledException) { }
                catch (IOException) { }
            }
            catch (OperationCanceledException) { }
            catch (Exception ex) when (
                ex is IOException or SocketException or FormatException or
                InvalidOperationException or ArgumentException)
            {
                Log.Warn("HTTP proxy bridge client: " + ex.Message);
            }
            finally
            {
                upstream?.Dispose();
            }
        }
    }

    private static async Task<byte[]?> ReadHeaderAsync(
        NetworkStream stream, CancellationToken ct)
    {
        using var buffer = new MemoryStream();
        var one = new byte[1];

        while (buffer.Length < 32768)
        {
            int n = await stream.ReadAsync(one, ct);
            if (n == 0)
                return null;

            buffer.WriteByte(one[0]);
            var b = buffer.GetBuffer();
            var len = (int)buffer.Length;

            if (len >= 4 &&
                b[len - 4] == 13 && b[len - 3] == 10 &&
                b[len - 2] == 13 && b[len - 1] == 10)
                return buffer.ToArray();
        }

        throw new IOException("HTTP request headers exceed 32 KB.");
    }

    private async Task SocksConnectAsync(
        NetworkStream stream, string host, int port, CancellationToken ct)
    {
        await stream.WriteAsync(new byte[] { 5, 1, 0 }, ct);

        var greeting = new byte[2];
        await ReadExactAsync(stream, greeting, ct);

        if (greeting[0] != 5 || greeting[1] != 0)
            throw new IOException("SOCKS5 server rejected no-authentication mode.");

        byte[] address;
        byte addressType;

        if (IPAddress.TryParse(host, out var ip))
        {
            address = ip.GetAddressBytes();
            addressType = ip.AddressFamily == AddressFamily.InterNetwork ? (byte)1 : (byte)4;
        }
        else
        {
            address = Encoding.ASCII.GetBytes(host);
            if (address.Length == 0 || address.Length > 255)
                throw new IOException("Invalid SOCKS5 destination hostname.");
            addressType = 3;
        }

        using var request = new MemoryStream();
        request.WriteByte(5);
        request.WriteByte(1);
        request.WriteByte(0);
        request.WriteByte(addressType);

        if (addressType == 3)
            request.WriteByte((byte)address.Length);

        request.Write(address);
        request.WriteByte((byte)(port >> 8));
        request.WriteByte((byte)(port & 255));

        await stream.WriteAsync(request.ToArray(), ct);

        var reply = new byte[4];
        await ReadExactAsync(stream, reply, ct);

        if (reply[0] != 5 || reply[1] != 0)
            throw new IOException($"SOCKS5 CONNECT failed (reply {reply[1]}).");

        int remaining = reply[3] switch
        {
            1 => 4,
            4 => 16,
            3 => await ReadDomainLengthAsync(stream, ct),
            _ => throw new IOException("Invalid SOCKS5 address type.")
        };

        var tail = new byte[remaining + 2];
        await ReadExactAsync(stream, tail, ct);
    }

    private static async Task<int> ReadDomainLengthAsync(
        NetworkStream stream, CancellationToken ct)
    {
        var b = new byte[1];
        await ReadExactAsync(stream, b, ct);
        return b[0];
    }

    private static async Task ReadExactAsync(
        NetworkStream stream, byte[] buffer, CancellationToken ct)
    {
        int offset = 0;
        while (offset < buffer.Length)
        {
            int n = await stream.ReadAsync(buffer.AsMemory(offset), ct);
            if (n == 0)
                throw new EndOfStreamException("SOCKS5 connection closed unexpectedly.");
            offset += n;
        }
    }

    private static bool TryHostPort(
        string value, int defaultPort, out string host, out int port)
    {
        host = "";
        port = defaultPort;

        if (Uri.TryCreate("tcp://" + value, UriKind.Absolute, out var uri) &&
            !string.IsNullOrWhiteSpace(uri.Host))
        {
            host = uri.Host;
            port = uri.IsDefaultPort ? defaultPort : uri.Port;
            return port is > 0 and <= 65535;
        }

        return false;
    }

    private static async Task WriteErrorAsync(
        NetworkStream stream, int code, string message, CancellationToken ct)
    {
        var response =
            $"HTTP/1.1 {code} {message}\r\n" +
            "Connection: close\r\n" +
            "Content-Length: 0\r\n\r\n";

        await stream.WriteAsync(Encoding.ASCII.GetBytes(response), ct);
    }

    public async ValueTask DisposeAsync()
    {
        try { _cts?.Cancel(); } catch { }

        try { _listener?.Stop(); } catch { }

        if (_acceptTask != null)
        {
            try { await _acceptTask; }
            catch (OperationCanceledException) { }
            catch (SocketException) { }
        }

        _cts?.Dispose();
        _cts = null;
        _listener = null;
        _acceptTask = null;

        Log.Info("HTTP proxy bridge stopped.");
    }
}
