namespace Relay.Core;

/// <summary>
/// 8-character typed-code fallback. Contract: /shared/typed-code.md — any
/// change there first. Covers socks5 payloads with 192.168.0.0/16 hosts only.
/// </summary>
public static class TypedCode
{
    public const string Alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public const int Length = 8;

    /// <summary>Returns null when host/port are outside the encodable range.</summary>
    public static string? Encode(string host, int port)
    {
        var octets = host.Split('.');
        if (octets is not ["192", "168", var o3Text, var o4Text]) return null;
        if (!byte.TryParse(o3Text, out var o3) || !byte.TryParse(o4Text, out var o4)) return null;
        if (port is < 1 or > 65535) return null;

        byte[] bytes = [o3, o4, (byte)(port >> 8), (byte)(port & 0xFF)];
        var check = Crc8(bytes) & 0x1F;
        var value = 0L;
        foreach (var b in bytes) value = (value << 8) | b;
        value = (value << 8) | (long)(check << 3);

        Span<char> code = stackalloc char[Length];
        for (var i = 0; i < Length; i++)
        {
            code[i] = Alphabet[(int)((value >> (35 - 5 * i)) & 31)];
        }
        return new string(code);
    }

    /// <summary>Decodes user input (case-insensitive, separators tolerated); null on any failure.</summary>
    public static (string Host, int Port)? Decode(string input)
    {
        var clean = new string(input.ToUpperInvariant()
            .Where(c => c != '-' && !char.IsWhiteSpace(c)).ToArray());
        if (clean.Length != Length) return null;

        var value = 0L;
        foreach (var c in clean)
        {
            var index = Alphabet.IndexOf(c);
            if (index < 0) return null;
            value = (value << 5) | (uint)index;
        }
        if ((value & 0b111) != 0) return null; // padding bits must be zero

        byte[] bytes =
        [
            (byte)((value >> 32) & 0xFF),
            (byte)((value >> 24) & 0xFF),
            (byte)((value >> 16) & 0xFF),
            (byte)((value >> 8) & 0xFF),
        ];
        var check = (int)((value >> 3) & 0x1F);
        if ((Crc8(bytes) & 0x1F) != check) return null;

        var port = (bytes[2] << 8) | bytes[3];
        if (port == 0) return null;
        return ($"192.168.{bytes[0]}.{bytes[1]}", port);
    }

    /// <summary>CRC-8: poly 0x07, init 0x00, no reflection, xorout 0x00.</summary>
    private static int Crc8(ReadOnlySpan<byte> data)
    {
        var crc = 0;
        foreach (var b in data)
        {
            crc ^= b;
            for (var i = 0; i < 8; i++)
            {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return crc;
    }
}
