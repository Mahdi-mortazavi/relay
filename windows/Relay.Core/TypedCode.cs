namespace Relay.Core;

/// <summary>
/// Typed-code fallback for all RFC1918 private LAN addresses. Contract: /shared/typed-code.md.
/// - 8 characters (<c>XXXX-XXXX</c>): <c>192.168.0.0/16</c> (5-bit CRC-8 + 3 zero padding bits)
/// - 10 characters (<c>XXXXX-XXXXX</c>): <c>10.0.0.0/8</c> (tag 1) and <c>172.16.0.0/12</c> (tag 2)
///   with full 8-bit CRC-8 across the 6 payload bytes.
/// </summary>
public static class TypedCode
{
    public const string Alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public const int Length = 8;
    public const int ExtendedLength = 10;

    private const int Tag10 = 1;
    private const int Tag172 = 2;

    /// <summary>Returns null when host/port are outside RFC1918 encodable ranges.</summary>
    public static string? Encode(string host, int port)
    {
        if (port is < 1 or > 65535) return null;
        var octets = host.Split('.');
        if (octets.Length != 4) return null;
        if (!byte.TryParse(octets[0], out var o1) ||
            !byte.TryParse(octets[1], out var o2) ||
            !byte.TryParse(octets[2], out var o3) ||
            !byte.TryParse(octets[3], out var o4))
        {
            return null;
        }

        if (o1 == 192 && o2 == 168)
        {
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

        int tag;
        byte h0;
        if (o1 == 10)
        {
            tag = Tag10;
            h0 = o2;
        }
        else if (o1 == 172 && o2 is >= 16 and <= 31)
        {
            tag = Tag172;
            h0 = (byte)(o2 - 16);
        }
        else
        {
            return null;
        }

        byte[] extBytes = [(byte)tag, h0, o3, o4, (byte)(port >> 8), (byte)(port & 0xFF)];
        var extCheck = Crc8(extBytes);
        var extVal = ((long)tag << 48)
            | ((long)h0 << 40)
            | ((long)o3 << 32)
            | ((long)o4 << 24)
            | ((long)port << 8)
            | (long)extCheck;

        Span<char> extCode = stackalloc char[ExtendedLength];
        for (var i = 0; i < ExtendedLength; i++)
        {
            extCode[i] = Alphabet[(int)((extVal >> (45 - 5 * i)) & 31)];
        }
        return new string(extCode);
    }

    /// <summary>
    /// The contract's input rule (/shared/typed-code.md): upper-case, with
    /// whitespace and <c>-</c> stripped and nothing else.
    ///
    /// Public because anything that validates a code before handing it to
    /// <see cref="Decode"/> has to see exactly the same string the decoder will.
    /// </summary>
    public static string Normalize(string? input) =>
        new((input ?? string.Empty).ToUpperInvariant()
            .Where(c => c != '-' && !char.IsWhiteSpace(c)).ToArray());

    /// <summary>Formats an 8-char code as XXXX-XXXX or a 10-char code as XXXXX-XXXXX.</summary>
    public static string Format(string? input)
    {
        var clean = Normalize(input);
        if (clean.Length == ExtendedLength)
        {
            return $"{clean[..5]}-{clean[5..]}";
        }
        return clean.Length == Length ? $"{clean[..4]}-{clean[4..]}" : clean;
    }

    /// <summary>Decodes user input (case-insensitive, separators tolerated); null on any failure.</summary>
    public static (string Host, int Port)? Decode(string input)
    {
        var clean = Normalize(input);
        return clean.Length switch
        {
            Length => Decode8(clean),
            ExtendedLength => Decode10(clean),
            _ => null,
        };
    }

    private static (string Host, int Port)? Decode8(string clean)
    {
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

    private static (string Host, int Port)? Decode10(string clean)
    {
        var value = 0L;
        foreach (var c in clean)
        {
            var index = Alphabet.IndexOf(c);
            if (index < 0) return null;
            value = (value << 5) | (uint)index;
        }

        var tag = (int)((value >> 48) & 0x3L);
        if (tag is not (Tag10 or Tag172)) return null;

        var h0 = (byte)((value >> 40) & 0xFF);
        var h1 = (byte)((value >> 32) & 0xFF);
        var h2 = (byte)((value >> 24) & 0xFF);
        var port = (int)((value >> 8) & 0xFFFF);
        var check = (int)(value & 0xFF);

        if (port == 0) return null;
        if (tag == Tag172 && h0 > 15) return null;

        byte[] bytes = [(byte)tag, h0, h1, h2, (byte)(port >> 8), (byte)(port & 0xFF)];
        if (Crc8(bytes) != check) return null;

        var host = tag == Tag10
            ? $"10.{h0}.{h1}.{h2}"
            : $"172.{16 + h0}.{h1}.{h2}";
        return (host, port);
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
