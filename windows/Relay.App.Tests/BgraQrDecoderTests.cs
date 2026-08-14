using Relay.Core;
using Xunit;
using ZXing;
using ZXing.Common;
using ZXing.QrCode;

namespace Relay.App.Tests;

/// <summary>
/// The test that did not exist.
///
/// The Windows QR scanner could not read a phone in any shipped build, because
/// the decode call described its BGRA8 frames as RGB24 and ZXing believed it.
/// Nothing caught it: the decode lived inside the WinUI project, which a test
/// assembly cannot reference, so the only way to find out whether scanning
/// worked was to hold a phone up to a webcam. It was reported twice from the
/// field before the cause was found, and misdiagnosed twice.
///
/// These tests render a real QR to a real BGRA8 buffer — the exact shape a
/// MediaFrameReader hands over — and demand the payload back.
/// </summary>
public class BgraQrDecoderTests
{
    private const string Payload = """{"v":1,"mode":"socks5","host":"192.168.43.1","port":1080}""";

    /// <summary>Renders text as a QR into a tightly packed BGRA8 buffer.</summary>
    private static (byte[] Bgra, int Width, int Height) RenderQr(
        string text, int size = 360, bool inverted = false)
    {
        var matrix = new QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size,
            new Dictionary<EncodeHintType, object>
            {
                [EncodeHintType.MARGIN] = 4,
                [EncodeHintType.CHARACTER_SET] = "UTF-8",
            });

        var width = matrix.Width;
        var height = matrix.Height;
        var bgra = new byte[width * height * BgraQrDecoder.BytesPerPixel];
        for (var y = 0; y < height; y++)
        {
            for (var x = 0; x < width; x++)
            {
                var dark = matrix[x, y];
                if (inverted) dark = !dark;
                var value = (byte)(dark ? 0 : 255);
                var i = ((y * width) + x) * BgraQrDecoder.BytesPerPixel;
                bgra[i] = value;      // B
                bgra[i + 1] = value;  // G
                bgra[i + 2] = value;  // R
                bgra[i + 3] = 255;    // A — opaque, as every camera frame is
            }
        }
        return (bgra, width, height);
    }

    [Fact]
    public void Reads_a_qr_out_of_a_bgra_frame()
    {
        var (bgra, width, height) = RenderQr(Payload);

        Assert.Equal(Payload, new BgraQrDecoder().Decode(bgra, width, height));
    }

    /// <summary>
    /// A phone in dark mode draws the QR light-on-dark. That is a valid QR and
    /// dark mode is the common case, so TryInverted has to stay on.
    /// </summary>
    [Fact]
    public void Reads_a_dark_mode_qr()
    {
        var (bgra, width, height) = RenderQr(Payload, inverted: true);

        Assert.Equal(Payload, new BgraQrDecoder().Decode(bgra, width, height));
    }

    /// <summary>
    /// The regression itself, stated as an invariant: four bytes per pixel.
    ///
    /// A decoder that reads three bytes per pixel consumes only the first 75% of
    /// this buffer and drifts a byte of phase every third sample, so it cannot
    /// return the payload — which is exactly what shipped. Trimming the buffer
    /// to what an RGB24 reading would need must therefore fail, and the full
    /// buffer must succeed.
    /// </summary>
    [Fact]
    public void Treats_the_frame_as_four_bytes_per_pixel()
    {
        var (bgra, width, height) = RenderQr(Payload);
        var decoder = new BgraQrDecoder();

        Assert.NotNull(decoder.Decode(bgra, width, height));

        var threeQuarters = new byte[width * height * 3];
        Array.Copy(bgra, threeQuarters, threeQuarters.Length);
        // Short buffer: refused outright rather than read past the end.
        Assert.Null(decoder.Decode(threeQuarters, width, height));
    }

    [Fact]
    public void Returns_null_rather_than_throwing_on_junk()
    {
        var decoder = new BgraQrDecoder();

        Assert.Null(decoder.Decode(new byte[16 * 16 * 4], 16, 16)); // blank frame
        Assert.Null(decoder.Decode([], 0, 0));
        Assert.Null(decoder.Decode(new byte[4], -1, 10));
    }
}
