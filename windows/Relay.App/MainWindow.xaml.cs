using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media.Imaging;
using Relay.App.Services;
using Relay.Core;
using Windows.Graphics.Imaging;

namespace Relay.App;

/// <summary>
/// The compact glass popup near the tray. UI is a pure projection of
/// AppController state plus a local "input mode" (scanning / code entry).
/// </summary>
public sealed partial class MainWindow : Window
{
    private const int PopupWidth = 400;
    private const int PopupHeight = 640;

    private readonly AppController _controller = AppController.Instance;
    private CameraQrScanner? _scanner;
    private long _lastPreviewTicks;
    private enum InputMode { None, Scanning, Code }
    private InputMode _mode = InputMode.None;

    public MainWindow()
    {
        InitializeComponent();
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
        AppWindow.Resize(new Windows.Graphics.SizeInt32(PopupWidth, PopupHeight));
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.IsResizable = false;
            presenter.IsMaximizable = false;
            presenter.IsMinimizable = false;
            presenter.SetBorderAndTitleBar(true, false);
        }
        AppWindow.IsShownInSwitchers = false;

        // Closing the popup hides it; the app lives in the tray.
        AppWindow.Closing += (_, args) =>
        {
            args.Cancel = true;
            StopScanning();
            AppWindow.Hide();
        };

        if (System.Globalization.CultureInfo.CurrentUICulture.TextInfo.IsRightToLeft)
        {
            Root.FlowDirection = FlowDirection.RightToLeft;
        }
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

    /// <summary>Resolves a theme-dictionary brush by key for the effective theme.</summary>
    private Microsoft.UI.Xaml.Media.Brush ThemeBrush(string key)
    {
        var themeKey = Root.ActualTheme == ElementTheme.Light ? "Light" : "Default";
        var dict = (ResourceDictionary)Root.Resources.ThemeDictionaries[themeKey];
        return (Microsoft.UI.Xaml.Media.Brush)dict[key];
    }

    private void RefreshLogs()
    {
        var logs = LocalLog.Snapshot();
        LogText.Text = logs.Count == 0
            ? Strings.Get("AdvancedLogsEmpty")
            : string.Join("\n", logs.Reverse().Select(e =>
                $"{e.ElapsedSeconds,7:F1}s  {e.Message}"));
    }

    private void OnClearLogsClick(object sender, RoutedEventArgs e) => LocalLog.Clear();

    /// <summary>Positions the popup just above the taskbar corner and shows it.</summary>
    public void ShowNearTray()
    {
        var workArea = DisplayArea.Primary.WorkArea;
        AppWindow.Move(new Windows.Graphics.PointInt32(
            workArea.X + workArea.Width - PopupWidth - 12,
            workArea.Y + workArea.Height - PopupHeight - 12));
        AppWindow.Show();
        Activate();
    }

    // --- state projection ----------------------------------------------------

    private void Render()
    {
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
            ConnectedText.Text = string.Format(
                Strings.Get("ConnectedVia"), payload.Name ?? payload.Host);
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
        }

        // Advanced address reflects the active session, if any.
        AdvancedAddressValue.Text = _controller.Payload is { } p
            ? $"{p.Host}:{p.Port}"
            : "—";
    }

    private static Visibility Show(bool visible) =>
        visible ? Visibility.Visible : Visibility.Collapsed;

    // --- scanning -------------------------------------------------------------

    private async void OnScanClick(object sender, RoutedEventArgs e)
    {
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
                var source = new SoftwareBitmapSource();
                await source.SetBitmapAsync(bitmap);
                PreviewImage.Source = source;
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
                ShowLocalError(decoded.Reason == "unknown-version"
                    ? "ERR_QR_NEWER_VERSION"
                    : "ERR_QR_INVALID");
                return;
            }
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

    private void OnDismissClick(object sender, RoutedEventArgs e)
    {
        _localError = null;
        _controller.DismissError();
        Render();
    }

    // Input-validation errors (bad scan/typed code) never touched the system,
    // so they render locally without entering the shared state machine.
    private string? _localError;

    private void ShowLocalError(string code)
    {
        _localError = code;
        _mode = InputMode.None;
        IdlePanel.Visibility = Visibility.Collapsed;
        ScanPanel.Visibility = Visibility.Collapsed;
        CodePanel.Visibility = Visibility.Collapsed;
        ErrorPanel.Visibility = Visibility.Visible;
        ErrorText.Text = Strings.Get(code switch
        {
            "ERR_QR_NEWER_VERSION" => "ErrQrNewer",
            "ERR_QR_INVALID" => "ErrQrInvalid",
            "ERR_CODE_INVALID" => "ErrCodeInvalid",
            "ERR_CAMERA_DENIED" => "ErrCameraDenied",
            _ => "ErrQrInvalid",
        });
    }
}
