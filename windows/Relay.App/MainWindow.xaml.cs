using System.Runtime.InteropServices;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media.Imaging;
using Relay.App.Services;
using Relay.Core;
using Windows.Graphics;
using Windows.Graphics.Imaging;

namespace Relay.App;

/// <summary>
/// The compact glass popover near the tray. It behaves like a macOS menu-bar
/// popover: the tray icon shows it (brought to the real foreground), and it
/// hides itself when it loses focus. The UI is a projection of AppController
/// state plus a local input mode (scanning / code entry).
/// </summary>
public sealed partial class MainWindow : Window
{
    private const int PopupWidth = 380;
    private const int PopupHeight = 600;

    private readonly AppController _controller = AppController.Instance;
    private CameraQrScanner? _scanner;
    private long _lastPreviewTicks;
    private enum InputMode { None, Scanning, Code }
    private InputMode _mode = InputMode.None;
    private string? _localError;
    private long _shownAtTick;

    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] private static extern uint GetDpiForWindow(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] private static extern bool AttachThreadInput(uint idAttach, uint idAttachTo, bool fAttach);
    [DllImport("user32.dll")] private static extern bool BringWindowToTop(IntPtr hWnd);
    private const int SW_SHOW = 5;

    /// <summary>
    /// Reliably brings the window to the foreground, even from a background
    /// process (tray click, second launch). Windows' foreground lock otherwise
    /// blocks SetForegroundWindow and the popover stays hidden/behind, looking
    /// frozen; attaching to the current foreground thread's input bypasses it.
    /// </summary>
    private static void ForceForeground(IntPtr hwnd)
    {
        var fgThread = GetWindowThreadProcessId(GetForegroundWindow(), out _);
        var thisThread = GetCurrentThreadId();
        var attached = fgThread != thisThread && AttachThreadInput(fgThread, thisThread, true);
        ShowWindow(hwnd, SW_SHOW);
        BringWindowToTop(hwnd);
        SetForegroundWindow(hwnd);
        if (attached) AttachThreadInput(fgThread, thisThread, false);
    }

    private IntPtr Hwnd => WinRT.Interop.WindowNative.GetWindowHandle(this);

    public MainWindow()
    {
        InitializeComponent();
        // Acrylic gives the glass look; set it in code so an unsupported backdrop
        // degrades to the solid scrim instead of failing the XAML load.
        try { SystemBackdrop = new Microsoft.UI.Xaml.Media.DesktopAcrylicBackdrop(); } catch { }
        Title = Strings.Get("AppName");
        ConfigureAppWindow();
        ApplyStrings();

        _controller.StateChanged += () => DispatcherQueue.TryEnqueue(Render);
        LocalLog.Changed += () => DispatcherQueue.TryEnqueue(RefreshLogs);
        Root.ActualThemeChanged += (_, _) => Render();
        RefreshLogs();
        Render();
    }

    private void ConfigureAppWindow()
    {
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.IsResizable = false;
            presenter.IsMaximizable = false;
            presenter.IsMinimizable = false;
            presenter.SetBorderAndTitleBar(true, false);
            presenter.IsAlwaysOnTop = true;
        }
        AppWindow.IsShownInSwitchers = false;

        // Closing the popover hides it to the tray; it's never destroyed.
        AppWindow.Closing += (_, args) =>
        {
            args.Cancel = true;
            HideToTray();
        };

        // macOS-popover behaviour: hide when focus is lost — but never mid-scan,
        // so a system camera prompt can't dismiss the scanner.
        Activated += (_, e) =>
        {
            // Ignore the brief deactivation that can follow a show (if the
            // foreground grab momentarily loses), else the popover flash-hides.
            if (e.WindowActivationState == WindowActivationState.Deactivated
                && _mode != InputMode.Scanning
                && Environment.TickCount64 - _shownAtTick > 400)
            {
                AppWindow.Hide();
            }
        };

        if (System.Globalization.CultureInfo.CurrentUICulture.TextInfo.IsRightToLeft)
        {
            Root.FlowDirection = FlowDirection.RightToLeft;
        }
    }

    /// <summary>Positions the popover above the tray and brings it to the real foreground.</summary>
    public void ShowNearTray()
    {
        var hwnd = Hwnd;
        var scale = GetDpiForWindow(hwnd) / 96.0;
        if (scale <= 0) scale = 1.0;
        var w = (int)(PopupWidth * scale);
        var h = (int)(PopupHeight * scale);
        var margin = (int)(12 * scale);

        AppWindow.Resize(new SizeInt32(w, h));
        var area = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary).WorkArea;
        AppWindow.Move(new PointInt32(
            area.X + area.Width - w - margin,
            area.Y + area.Height - h - margin));

        _shownAtTick = Environment.TickCount64;
        AppWindow.Show();
        ForceForeground(hwnd);
        Activate();
    }

    private void HideToTray()
    {
        StopScanning();
        AppWindow.Hide();
    }

    private void ApplyStrings()
    {
        TitleText.Text = Strings.Get("AppName");
        TaglineText.Text = Strings.Get("Tagline");
        IdleStatusText.Text = Strings.Get("StatusIdle");
        ScanButton.Content = Strings.Get("ScanQr");
        EnterCodeButton.Content = Strings.Get("EnterCode");
        ScanHintText.Text = Strings.Get("ScanHint");
        ScanCancelButton.Content = Strings.Get("Cancel");
        CodeHintText.Text = Strings.Get("CodeHint");
        CodeConnectButton.Content = Strings.Get("Connect");
        CodeCancelButton.Content = Strings.Get("Cancel");
        BusyText.Text = Strings.Get("StatusConnecting");
        DisconnectButton.Content = Strings.Get("Disconnect");
        ErrorDismissButton.Content = Strings.Get("Dismiss");
        AdvancedHeader.Text = Strings.Get("Advanced");
        AdvancedAddressLabel.Text = Strings.Get("AdvancedAddress");
        AdvancedLogsLabel.Text = Strings.Get("AdvancedLogs");
        AdvancedLogsClear.Content = Strings.Get("AdvancedLogsClear");
    }

    /// <summary>Dynamic dot color by key — inline so it needs no XAML resources.</summary>
    private static Microsoft.UI.Xaml.Media.Brush ThemeBrush(string key)
    {
        (byte a, byte r, byte g, byte b) = key switch
        {
            "Accent" => ((byte)0xFF, (byte)0x4A, (byte)0xDF, (byte)0xBF),
            "ErrorBrush" => ((byte)0xFF, (byte)0xFF, (byte)0x6B, (byte)0x66),
            "WarningBrush" => ((byte)0xFF, (byte)0xF0, (byte)0xB4, (byte)0x5E),
            "TextSecondary" => ((byte)0xA8, (byte)0xFF, (byte)0xFF, (byte)0xFF),
            _ => ((byte)0x66, (byte)0xFF, (byte)0xFF, (byte)0xFF),
        };
        return new Microsoft.UI.Xaml.Media.SolidColorBrush(Windows.UI.Color.FromArgb(a, r, g, b));
    }

    private void RefreshLogs()
    {
        var logs = LocalLog.Snapshot();
        LogText.Text = logs.Count == 0
            ? Strings.Get("AdvancedLogsEmpty")
            : string.Join("\n", logs.Reverse().Select(entry =>
                $"{entry.ElapsedSeconds,7:F1}s  {entry.Message}"));
    }

    private void OnClearLogsClick(object sender, RoutedEventArgs e) => LocalLog.Clear();

    // --- state projection ----------------------------------------------------

    private void Render()
    {
        // A local input error (bad scan/code, camera) is a first-class Render
        // state, so a later Render (e.g. from a theme change) can't erase it.
        if (_localError is not null)
        {
            ShowOnly(ErrorPanel);
            ErrorText.Text = LocalErrorMessage(_localError);
            ErrorDismissButton.Content = Strings.Get("Dismiss");
            StatusDot.Fill = ThemeBrush("ErrorBrush");
            return;
        }

        var state = _controller.StateName;
        IdlePanel.Visibility = Show(state == "Idle" && _mode == InputMode.None);
        ScanPanel.Visibility = Show(state == "Idle" && _mode == InputMode.Scanning);
        CodePanel.Visibility = Show(state == "Idle" && _mode == InputMode.Code);
        BusyPanel.Visibility = Show(state is "Preparing" or "Advertising");
        ConnectedPanel.Visibility = Show(state == "Connected");
        ErrorPanel.Visibility = Show(state == "Error");

        var reconnecting = _controller.Reconnecting && state == "Connected";
        StatusDot.Fill = ThemeBrush(
            reconnecting ? "WarningBrush"
            : state switch
            {
                "Connected" => "Accent",
                "Error" => "ErrorBrush",
                "Preparing" or "Advertising" => "TextSecondary",
                _ => "TextTertiary",
            });

        if (state == "Connected" && _controller.Payload is { } payload)
        {
            ConnectedText.Text = string.Format(Strings.Get("ConnectedVia"), payload.Name ?? payload.Host);
            ConnectedDetailText.Text = $"{payload.Host}:{payload.Port}";
            ReconnectingText.Text = Strings.Get("Reconnecting");
            ReconnectingText.Visibility = Show(reconnecting);
            ConnectedDot.Fill = ThemeBrush(reconnecting ? "WarningBrush" : "Accent");
        }
        if (state == "Error")
        {
            ErrorText.Text = Strings.Get(_controller.ErrorCode switch
            {
                "ERR_QR_NEWER_VERSION" => "ErrQrNewer",
                "ERR_QR_INVALID" => "ErrQrInvalid",
                "ERR_CODE_INVALID" => "ErrCodeInvalid",
                "ERR_HOST_UNREACHABLE" => "ErrHostUnreachable",
                "ERR_WRONG_NETWORK" => "ErrWrongNetwork",
                "ERR_CONNECTION_LOST" => "ErrConnectionLost",
                "ERR_FIREWALL_BLOCKED" => "ErrFirewall",
                "ERR_PROXY_APPLY_FAILED" => "ErrProxyApply",
                "ERR_ROLLBACK_INCOMPLETE" => "ErrRollback",
                "ERR_CAMERA_DENIED" => "ErrCameraDenied",
                _ => "ErrProxyApply",
            });
            // The rollback-incomplete error's next action is to retry the disconnect.
            ErrorDismissButton.Content = Strings.Get(
                _controller.ErrorCode == "ERR_ROLLBACK_INCOMPLETE" ? "Disconnect" : "Dismiss");
        }

        AdvancedAddressValue.Text = _controller.Payload is { } p ? $"{p.Host}:{p.Port}" : "—";
    }

    private void ShowOnly(FrameworkElement panel)
    {
        IdlePanel.Visibility = Visibility.Collapsed;
        ScanPanel.Visibility = Visibility.Collapsed;
        CodePanel.Visibility = Visibility.Collapsed;
        BusyPanel.Visibility = Visibility.Collapsed;
        ConnectedPanel.Visibility = Visibility.Collapsed;
        ErrorPanel.Visibility = Visibility.Collapsed;
        panel.Visibility = Visibility.Visible;
    }

    private static string LocalErrorMessage(string code) => Strings.Get(code switch
    {
        "ERR_QR_NEWER_VERSION" => "ErrQrNewer",
        "ERR_QR_INVALID" => "ErrQrInvalid",
        "ERR_CODE_INVALID" => "ErrCodeInvalid",
        "ERR_CAMERA_DENIED" => "ErrCameraDenied",
        _ => "ErrQrInvalid",
    });

    private static Visibility Show(bool visible) => visible ? Visibility.Visible : Visibility.Collapsed;

    // --- scanning -------------------------------------------------------------

    private async void OnScanClick(object sender, RoutedEventArgs e)
    {
        _localError = null;
        _mode = InputMode.Scanning;
        Render();
        _scanner = new CameraQrScanner();
        _scanner.PreviewFrame += OnPreviewFrame;
        _scanner.Decoded += OnQrDecoded;
        try
        {
            await _scanner.StartAsync();
        }
        catch (Exception)
        {
            StopScanning();
            ShowLocalError("ERR_CAMERA_DENIED");
        }
    }

    private void OnPreviewFrame(SoftwareBitmap bitmap)
    {
        // ~10 fps is plenty for framing a QR; drop the rest.
        var now = Environment.TickCount64;
        if (now - _lastPreviewTicks < 100)
        {
            bitmap.Dispose();
            return;
        }
        _lastPreviewTicks = now;

        DispatcherQueue.TryEnqueue(async () =>
        {
            using (bitmap)
            {
                if (_mode != InputMode.Scanning) return;
                var previous = PreviewImage.Source as SoftwareBitmapSource;
                var source = new SoftwareBitmapSource();
                await source.SetBitmapAsync(bitmap);
                PreviewImage.Source = source;
                previous?.Dispose();
            }
        });
    }

    private void OnQrDecoded(string text)
    {
        DispatcherQueue.TryEnqueue(async () =>
        {
            if (_mode != InputMode.Scanning) return;
            StopScanning();

            var decoded = QrPayloadCodec.Decode(text);
            if (!decoded.IsOk)
            {
                ShowLocalError(decoded.Reason == "unknown-version" ? "ERR_QR_NEWER_VERSION" : "ERR_QR_INVALID");
                return;
            }
            _localError = null;
            await _controller.ConnectAsync(decoded.Payload!);
        });
    }

    private void StopScanning()
    {
        _mode = InputMode.None;
        _scanner?.Dispose();
        _scanner = null;
        PreviewImage.Source = null;
        Render();
    }

    // --- manual code ------------------------------------------------------------

    private void OnEnterCodeClick(object sender, RoutedEventArgs e)
    {
        _localError = null;
        _mode = InputMode.Code;
        CodeBox.Text = string.Empty;
        Render();
        CodeBox.Focus(FocusState.Programmatic);
    }

    private async void OnCodeConnectClick(object sender, RoutedEventArgs e)
    {
        var decoded = TypedCode.Decode(CodeBox.Text);
        if (decoded is null)
        {
            ShowLocalError("ERR_CODE_INVALID");
            return;
        }
        _localError = null;
        _mode = InputMode.None;
        await _controller.ConnectAsync(new QrPayload
        {
            V = QrPayloadCodec.SupportedVersion,
            Mode = QrPayload.ModeSocks5,
            Host = decoded.Value.Host,
            Port = decoded.Value.Port,
        });
    }

    // --- shared handlers -----------------------------------------------------------

    private void OnCancelClick(object sender, RoutedEventArgs e) => StopScanning();

    private async void OnDisconnectClick(object sender, RoutedEventArgs e) =>
        await _controller.DisconnectAsync();

    private async void OnDismissClick(object sender, RoutedEventArgs e)
    {
        // For a failed rollback the action is "retry the disconnect", not dismiss.
        if (_localError is null && _controller.ErrorCode == "ERR_ROLLBACK_INCOMPLETE")
        {
            await _controller.DisconnectAsync();
            return;
        }
        _localError = null;
        _controller.DismissError();
        Render();
    }

    private void ShowLocalError(string code)
    {
        _localError = code;
        _mode = InputMode.None;
        Render();
    }
}
