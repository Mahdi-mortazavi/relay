using ZXing;

namespace Relay.Core;

/// <summary>
/// Decodes a QR code out of a raw BGRA8 frame.
///
/// This lives in Core rather than beside the camera because of what it cost to
/// leave it there. The decode call sat inside a WinUI project, which a plain
/// test assembly cannot reference, so nothing could test it — and for seven
/// releases it handed ZXing a 4-bytes-per-pixel buffer while telling it, by
/// omission, that the buffer was 3-bytes-per-pixel RGB24. (ZXing's three-
/// argument <c>RGBLuminanceSource</c> constructor does not sniff the format;
/// it hard-codes RGB24, and only <c>BitmapFormat.Unknown</c> triggers
/// detection.) Every frame came out squeezed by 4/3 with the alpha byte folded
/// in as a bright comb, no finder pattern survived, and the Windows scanner
/// failed to read a phone at any distance in any build. Two separate users
/// reported it as "the camera doesn't work" and it was twice misdiagnosed —
/// once as a resolution problem, once as the mirrored preview.
///
/// The camera plumbing stays in the app. The pixels-to-text step is here,
/// where <c>BgraQrDecoderTests</c> can put a known QR in and demand the text
/// back out.
/// </summary>
public sealed class BgraQrDecoder
{
    /// <summary>Bytes per pixel in the frames this decoder accepts.</summary>
    public const int BytesPerPixel = 4;

    private readonly BarcodeReaderGeneric _reader = new()
    {
        AutoRotate = true,
        Options =
        {
            PossibleFormats = [BarcodeFormat.QR_CODE],
            TryHarder = true,
            // A phone in dark mode renders the QR light-on-dark: a valid QR that
            // a plain decoder will not see, and dark mode is the default on most
            // of the phones this app is pointed at.
            TryInverted = true,
        },
    };

    /// <summary>
    /// Returns the decoded text, or null when the frame holds no readable QR.
    /// </summary>
    /// <param name="bgra">Tightly packed BGRA8, <c>width * height * 4</c> bytes.</param>
    public string? Decode(byte[] bgra, int width, int height)
    {
        if (bgra is null || width <= 0 || height <= 0) return null;
        // A short buffer would make ZXing read past the frame; a long one is
        // fine (a stride-padded capture), it just reads the part it needs.
        if (bgra.Length < (long)width * height * BytesPerPixel) return null;

        try
        {
            // The format is stated deliberately and must stay stated. See the
            // class remarks for what the defaulting constructor did here.
            var luminance = new RGBLuminanceSource(
                bgra, width, height, RGBLuminanceSource.BitmapFormat.BGRA32);
            return _reader.Decode(luminance)?.Text;
        }
        catch (Exception)
        {
            // A malformed frame must never take the scanner down with it.
            return null;
        }
    }
}
