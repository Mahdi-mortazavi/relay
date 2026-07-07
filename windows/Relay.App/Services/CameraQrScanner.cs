using Windows.Graphics.Imaging;
using Windows.Media.Capture;
using Windows.Media.Capture.Frames;
using Windows.Storage.Streams;
using ZXing;

namespace Relay.App.Services;

/// <summary>
/// Webcam QR scanning: MediaFrameReader (BGRA8, CPU memory) + ZXing.Net.
/// Emits preview frames for the UI and the first successfully decoded text.
/// </summary>
public sealed class CameraQrScanner : IDisposable
{
    private MediaCapture? _capture;
    private MediaFrameReader? _reader;
    private long _lastDecodeTicks;
    private int _disposed;

    private readonly BarcodeReaderGeneric _decoder = new()
    {
        AutoRotate = true,
        Options =
        {
            PossibleFormats = [BarcodeFormat.QR_CODE],
            TryHarder = true,
        },
    };

    /// <summary>BGRA8 premultiplied preview frame, ready for SoftwareBitmapSource.</summary>
    public event Action<SoftwareBitmap>? PreviewFrame;

    /// <summary>Raw decoded QR text (fired once; scanning stops).</summary>
    public event Action<string>? Decoded;

    /// <summary>Thrown reasons map to ERR_CAMERA_DENIED / ERR_CAMERA_MISSING in the UI.</summary>
    public async Task StartAsync()
    {
        var groups = await MediaFrameSourceGroup.FindAllAsync();
        var pick = groups
            .SelectMany(g => g.SourceInfos, (g, info) => (Group: g, Info: info))
            .FirstOrDefault(p =>
                p.Info.MediaStreamType is MediaStreamType.VideoPreview or MediaStreamType.VideoRecord
                && p.Info.SourceKind == MediaFrameSourceKind.Color);
        if (pick.Group is null) throw new InvalidOperationException("no-camera");

        _capture = new MediaCapture();
        await _capture.InitializeAsync(new MediaCaptureInitializationSettings
        {
            SourceGroup = pick.Group,
            SharingMode = MediaCaptureSharingMode.ExclusiveControl,
            StreamingCaptureMode = StreamingCaptureMode.Video,
            MemoryPreference = MediaCaptureMemoryPreference.Cpu,
        });

        var source = _capture.FrameSources[pick.Info.Id];
        _reader = await _capture.CreateFrameReaderAsync(
            source, Windows.Media.MediaProperties.MediaEncodingSubtypes.Bgra8);
        _reader.FrameArrived += OnFrame;
        await _reader.StartAsync();
    }

    private void OnFrame(MediaFrameReader sender, MediaFrameArrivedEventArgs args)
    {
        using var frame = sender.TryAcquireLatestFrame();
        var bitmap = frame?.VideoMediaFrame?.SoftwareBitmap;
        if (bitmap is null || _disposed != 0) return;

        var converted = SoftwareBitmap.Convert(
            bitmap, BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied);
        PreviewFrame?.Invoke(converted); // consumer owns + disposes

        // Decode at most ~6x per second — plenty for a static QR.
        var now = Environment.TickCount64;
        if (now - Interlocked.Read(ref _lastDecodeTicks) < 160) return;
        Interlocked.Exchange(ref _lastDecodeTicks, now);

        var text = TryDecode(converted);
        if (text is not null)
        {
            Decoded?.Invoke(text);
        }
    }

    private string? TryDecode(SoftwareBitmap bitmap)
    {
        try
        {
            var width = bitmap.PixelWidth;
            var height = bitmap.PixelHeight;
            var buffer = new Windows.Storage.Streams.Buffer((uint)(width * height * 4));
            bitmap.CopyToBuffer(buffer);
            var bytes = new byte[buffer.Length];
            using (var reader = DataReader.FromBuffer(buffer))
            {
                reader.ReadBytes(bytes);
            }
            // 3-arg ctor auto-detects 4 bytes/pixel; channel order (BGRA vs RGBA)
            // is irrelevant for a black/white QR's luminance.
            var luminance = new RGBLuminanceSource(bytes, width, height);
            return _decoder.Decode(luminance)?.Text;
        }
        catch (Exception)
        {
            return null;
        }
    }

    public void Dispose()
    {
        if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
        try
        {
            if (_reader is not null)
            {
                _reader.FrameArrived -= OnFrame;
                _reader.StopAsync().AsTask().Wait(TimeSpan.FromSeconds(2));
                _reader.Dispose();
            }
        }
        catch (Exception) { /* teardown must never throw */ }
        _capture?.Dispose();
        _capture = null;
        _reader = null;
    }
}
