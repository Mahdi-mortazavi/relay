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
        await SelectScanFormatAsync(source);
        _reader = await _capture.CreateFrameReaderAsync(
            source, Windows.Media.MediaProperties.MediaEncodingSubtypes.Bgra8);
        _reader.FrameArrived += OnFrame;
        await _reader.StartAsync();
    }

    /// <summary>
    /// Raises capture resolution before the reader is created. Without this the
    /// source keeps its default format — commonly 640x480 — and a ~49-module QR
    /// held at arm's length covers maybe a third of the frame, leaving ~4px per
    /// module. That is under what ZXing needs once webcam softness and screen
    /// glare are in play, so scanning a phone simply fails (issue #18).
    /// Best-effort: a camera that rejects the format keeps its default.
    /// </summary>
    // ponytail: capped at 1280x720 to bound the per-frame BGRA copy + decode cost
    // (1080p is a 8MB buffer 6x/sec). Raise the cap if a QR still won't scan.
    private static async Task SelectScanFormatAsync(MediaFrameSource source)
    {
        static long Pixels(MediaFrameFormat f) => (long)f.VideoFormat.Width * f.VideoFormat.Height;

        var best = source.SupportedFormats
            .Where(f => f.VideoFormat.Width <= 1280 && f.VideoFormat.Height <= 720)
            .OrderByDescending(Pixels)
            .ThenByDescending(f => f.FrameRate.Denominator == 0
                ? 0d
                : f.FrameRate.Numerator / (double)f.FrameRate.Denominator)
            .FirstOrDefault();

        // Only ever move up: a camera already defaulting above the cap (some do)
        // must not be dragged down to 720p by this.
        var current = source.CurrentFormat;
        if (best is null || (current is not null && Pixels(best) <= Pixels(current))) return;
        try { await source.SetFormatAsync(best); } catch (Exception) { }
    }

    private void OnFrame(MediaFrameReader sender, MediaFrameArrivedEventArgs args)
    {
        using var frame = sender.TryAcquireLatestFrame();
        var bitmap = frame?.VideoMediaFrame?.SoftwareBitmap;
        if (bitmap is null || _disposed != 0) return;

        var converted = SoftwareBitmap.Convert(
            bitmap, BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied);

        // Decode BEFORE handing the bitmap off. PreviewFrame's consumer owns and
        // disposes it (possibly on the UI thread), so touching `converted` after
        // the invoke is a use-after-dispose / cross-thread race on a non-thread-
        // safe COM object. Decode at most ~6x/sec — plenty for a static QR.
        var now = Environment.TickCount64;
        if (now - Interlocked.Read(ref _lastDecodeTicks) >= 160)
        {
            Interlocked.Exchange(ref _lastDecodeTicks, now);
            var text = TryDecode(converted);
            if (text is not null) Decoded?.Invoke(text);
        }

        PreviewFrame?.Invoke(converted); // hand off ownership LAST
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
        var reader = _reader;
        var capture = _capture;
        _reader = null;
        _capture = null;
        // Unsubscribe synchronously so no more frames reach us, then tear the
        // camera down on a background thread — StopAsync can take ~a second and
        // Dispose() is called from the UI thread (Cancel / window close).
        if (reader is not null) reader.FrameArrived -= OnFrame;
        Task.Run(() =>
        {
            try { reader?.StopAsync().AsTask().Wait(TimeSpan.FromSeconds(2)); } catch { }
            try { reader?.Dispose(); } catch { }
            try { capture?.Dispose(); } catch { }
        });
    }
}
