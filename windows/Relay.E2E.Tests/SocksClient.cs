using System.Net;
using System.Net.Sockets;
using System.Text;

namespace Relay.E2E.Tests;

/// <summary>
/// A minimal SOCKS5 client, written against RFC 1928 rather than against
/// Relay's server, so the two cannot agree on a shared misreading of the
/// protocol. Talks to a local port that <c>adb forward</c> tunnels to the
/// phone's proxy.
/// </summary>
internal sealed class SocksClient : IDisposable
{
    private readonly TcpClient _client;
    private readonly NetworkStream _stream;

    private SocksClient(TcpClient client)
    {
        _client = client;
        _stream = client.GetStream();
    }

    /// <summary>CONNECT to an IPv4 destination (ATYP 1).</summary>
    public static async Task<SocksClient> ConnectAsync(int proxyPort, string host, int port)
    {
        var socks = await GreetAsync(proxyPort);
        var address = IPAddress.Parse(host).GetAddressBytes();
        await socks.SendRequestAsync(0x01, 0x01, address, port);
        await socks.ExpectSuccessAsync($"{host}:{port}");
        return socks;
    }

    /// <summary>CONNECT to a domain destination (ATYP 3) — the phone resolves it.</summary>
    public static async Task<SocksClient> ConnectByNameAsync(int proxyPort, string name, int port)
    {
        var socks = await GreetAsync(proxyPort);
        var bytes = Encoding.ASCII.GetBytes(name);
        await socks.SendRequestAsync(0x01, 0x03, [(byte)bytes.Length, .. bytes], port);
        await socks.ExpectSuccessAsync($"{name}:{port}");
        return socks;
    }

    /// <summary>Sends a raw request and returns the 10-byte reply, whatever it says.</summary>
    public static async Task<byte[]> RawRequestAsync(
        int proxyPort, byte command, string host, int port, byte addressType = 0x01)
    {
        using var socks = await GreetAsync(proxyPort);
        byte[] address = addressType == 0x01 ? IPAddress.Parse(host).GetAddressBytes() : [0x00];
        await socks.SendRequestAsync(command, addressType, address, port);
        return await socks.ReadExactlyAsync(10);
    }

    /// <summary>Writes [request] and reads until [expected] appears or the peer closes.</summary>
    public async Task<string> RequestAsync(string request, string expected)
    {
        await _stream.WriteAsync(Encoding.ASCII.GetBytes(request));
        await _stream.FlushAsync();

        var text = new StringBuilder();
        var buffer = new byte[8192];
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        while (true)
        {
            int read;
            try { read = await _stream.ReadAsync(buffer, timeout.Token); }
            catch (OperationCanceledException) { break; }
            catch (IOException) { break; }
            if (read <= 0) break;
            text.Append(Encoding.ASCII.GetString(buffer, 0, read));
            if (text.ToString().Contains(expected)) break;
        }
        return text.ToString();
    }

    private static async Task<SocksClient> GreetAsync(int proxyPort)
    {
        var client = new TcpClient { NoDelay = true };
        using (var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15)))
        {
            await client.ConnectAsync(IPAddress.Loopback, proxyPort, timeout.Token);
        }
        var socks = new SocksClient(client);

        await socks._stream.WriteAsync(new byte[] { 0x05, 0x01, 0x00 });
        await socks._stream.FlushAsync();
        var greeting = await socks.ReadExactlyAsync(2);
        if (greeting[0] != 0x05 || greeting[1] != 0x00)
        {
            socks.Dispose();
            throw new IOException(
                $"the phone did not accept a no-auth SOCKS5 greeting (got {greeting[0]:X2} {greeting[1]:X2})");
        }
        return socks;
    }

    private async Task SendRequestAsync(byte command, byte addressType, byte[] address, int port)
    {
        byte[] request = [0x05, command, 0x00, addressType, .. address,
            (byte)(port >> 8), (byte)(port & 0xFF)];
        await _stream.WriteAsync(request);
        await _stream.FlushAsync();
    }

    private async Task ExpectSuccessAsync(string destination)
    {
        var reply = await ReadExactlyAsync(10);
        if (reply[0] != 0x05 || reply[1] != 0x00)
        {
            throw new IOException(
                $"the phone refused CONNECT to {destination}: VER={reply[0]:X2} REP={reply[1]:X2}");
        }
    }

    private async Task<byte[]> ReadExactlyAsync(int count)
    {
        var buffer = new byte[count];
        var offset = 0;
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        while (offset < count)
        {
            var read = await _stream.ReadAsync(buffer.AsMemory(offset, count - offset), timeout.Token);
            if (read <= 0)
            {
                throw new IOException($"the phone closed the connection after {offset} of {count} bytes");
            }
            offset += read;
        }
        return buffer;
    }

    public void Dispose()
    {
        _stream.Dispose();
        _client.Dispose();
    }
}
