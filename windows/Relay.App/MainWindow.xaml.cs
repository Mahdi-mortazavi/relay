using System.Runtime.InteropServices;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
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
    // The window follows its content between these bounds instead of standing
    // at a fixed height with a hole in the middle of it.
    private const int MinPopupHeight = 340;
    private const int MaxPopupHeight = 640;

    private readonly AppController _controller = AppController.Instance;
    private CameraQrScanner? _scanner;
    private long _lastPreviewTicks;
    private enum InputMode { None, Scanning, Code }
    private InputMode _mode = InputMode.None;
    private string? _localError;
    private long _shownAtTick;
    private FrameworkElement? _visiblePanel;
    private string? _errorAction;
    private bool _shown;
    private bool _pulsing;

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

    /// <summary>
    /// Set by the tray's Exit item so the Closing handler lets the close through
    /// instead of hiding to the tray. UI-thread only.
    /// </summary>
    public bool ExitRequested { get; set; }

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
        AttachPressFeedback();
        AttachKeyboard();
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

        // Closing the popover hides it to the tray; it's never destroyed — unless
        // the user picked Exit, in which case cancelling here would swallow the
        // close that Application.Exit() depends on and strand the process with no
        // tray icon and the single-instance mutex still held (issue #18).
        AppWindow.Closing += (_, args) =>
        {
            if (ExitRequested) return;
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
        _shown = true;
        PositionWindow(MinPopupHeight);
        _shownAtTick = Environment.TickCount64;
        AppWindow.Show();
        ForceForeground(Hwnd);
        Activate();
        ResizeToContent();
        FocusPrimary();
    }

    /// <summary>
    /// Puts the popover in the corner the notification area is actually in, at
    /// [heightDip] tall, and never off-screen.
    /// </summary>
    private void PositionWindow(int heightDip)
    {
        var scale = GetDpiForWindow(Hwnd) / 96.0;
        if (scale <= 0) scale = 1.0;
        var w = (int)(PopupWidth * scale);
        var h = (int)(heightDip * scale);
        var margin = (int)(12 * scale);

        var area = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary).WorkArea;

        // Clamp to the work area: at 175% scaling a tall window used to be
        // pushed off the top of the screen, and it is not resizable, so the
        // header simply became unreachable.
        h = Math.Min(h, Math.Max(area.Height - margin * 2, 200));

        // A right-to-left Windows puts the notification area bottom-left. The
        // content was already mirrored; the window itself was not, so it opened
        // in the opposite corner from the icon that summoned it.
        var x = Root.FlowDirection == FlowDirection.RightToLeft
            ? area.X + margin
            : area.X + area.Width - w - margin;

        AppWindow.Resize(new SizeInt32(w, h));
        AppWindow.Move(new PointInt32(x, area.Y + area.Height - h - margin));
    }

    private void HideToTray()
    {
        StopScanning();
        _shown = false;
        AppWindow.Hide();
    }

    /// <summary>
    /// Every button dips under the pointer the instant it goes down, not when
    /// the click completes. Waiting for the release is what makes an interface
    /// feel like it is thinking rather than responding.
    /// </summary>
    private void AttachPressFeedback()
    {
        foreach (var button in new[]
        {
            ScanButton, EnterCodeButton, ScanCancelButton, CodeConnectButton,
            CodeCancelButton, BusyCancelButton, DisconnectButton,
            ErrorPrimaryButton, ErrorDismissButton,
        })
        {
            var target = button;
            target.AddHandler(UIElement.PointerPressedEvent,
                new PointerEventHandler((_, _) => Motion.PressDown(target)), handledEventsToo: true);
            // Released *and* exited: dragging off a control must not leave it
            // stuck in the pressed state.
            target.AddHandler(UIElement.PointerReleasedEvent,
                new PointerEventHandler((_, _) => Motion.PressUp(target)), handledEventsToo: true);
            target.PointerExited += (_, _) => Motion.PressUp(target);
            target.PointerCaptureLost += (_, _) => Motion.PressUp(target);
        }
    }

    /// <summary>
    /// Escape backs out of whatever is open, Enter takes the primary action.
    /// A tray popover that can only be driven with a mouse is a popover half
    /// its users cannot drive.
    /// </summary>
    private void AttachKeyboard()
    {
        // handledEventsToo, because the focused control gets the key first and
        // TextBox marks several of these handled. Escape has to work while the
        // caret is in the code box — that is precisely where a user is most
        // likely to want out.
        Root.AddHandler(UIElement.KeyDownEvent, new KeyEventHandler((_, e) =>
        {
            switch (e.Key)
            {
                case Windows.System.VirtualKey.Escape:
                    if (_mode != InputMode.None || _localError is not null)
                    {
                        OnCancelClick(this, new RoutedEventArgs());
                    }
                    else
                    {
                        HideToTray();
                    }
                    e.Handled = true;
                    break;

                case Windows.System.VirtualKey.Enter:
                    if (ErrorPanel.Visibility == Visibility.Visible &&
                        ErrorPrimaryButton.Visibility == Visibility.Visible)
                    {
                        OnErrorPrimaryClick(this, new RoutedEventArgs());
                        e.Handled = true;
                    }
                    else if (CodePanel.Visibility == Visibility.Visible &&
                             CodeConnectButton.IsEnabled)
                    {
                        OnCodeConnectClick(this, new RoutedEventArgs());
                        e.Handled = true;
                    }
                    else if (IdlePanel.Visibility == Visibility.Visible)
                    {
                        OnScanClick(this, new RoutedEventArgs());
                        e.Handled = true;
                    }
                    break;
            }
        }), handledEventsToo: true);
    }

    /// <summary>
    /// Puts the caret somewhere sensible when the popover appears. Without this
    /// nothing inside holds focus, key events have no element to bubble from,
    /// and a keyboard user opening the popover is simply stuck.
    /// </summary>
    private void FocusPrimary()
    {
        var target = _visiblePanel switch
        {
            _ when ReferenceEquals(_visiblePanel, CodePanel) => (Control)CodeBox,
            _ when ReferenceEquals(_visiblePanel, ConnectedPanel) => DisconnectButton,
            _ when ReferenceEquals(_visiblePanel, ErrorPanel) =>
                ErrorPrimaryButton.Visibility == Visibility.Visible
                    ? ErrorPrimaryButton : ErrorDismissButton,
            _ when ReferenceEquals(_visiblePanel, ScanPanel) => ScanCancelButton,
            _ when ReferenceEquals(_visiblePanel, BusyPanel) => BusyCancelButton,
            _ => ScanButton,
        };
        try { target.Focus(FocusState.Programmatic); } catch { }
    }

    private void ApplyStrings()
    {
        TitleText.Text = Strings.Get("AppName");
        IdleHeadline.Text = Strings.Get("IdleHeadline");
        IdleBody.Text = Strings.Get("IdleBody");
        ScanButton.Content = Strings.Get("ScanQr");
        EnterCodeButton.Content = Strings.Get("EnterCode");
        ScanHintText.Text = Strings.Get("ScanAiming");
        ScanCancelButton.Content = Strings.Get("Cancel");
        CodeHintText.Text = Strings.Get("CodeHint");
        CodeConnectButton.Content = Strings.Get("Connect");
        CodeCancelButton.Content = Strings.Get("Cancel");
        BusyText.Text = Strings.Get("BusyConnecting");
        BusyDetailText.Text = Strings.Get("BusyDetail");
        BusyCancelButton.Content = Strings.Get("Cancel");
        DisconnectButton.Content = Strings.Get("Disconnect");
        ErrorDismissButton.Content = Strings.Get("Dismiss");
        AdvancedHeader.Text = Strings.Get("Advanced");
        AdvancedAddressLabel.Text = Strings.Get("AdvancedAddress");
        AdvancedLogsLabel.Text = Strings.Get("AdvancedLogs");
        AdvancedLogsClear.Content = Strings.Get("AdvancedLogsClear");
    }

    /// <summary>
    /// Resolves a token from the design system, falling back to a literal only
    /// if the dictionary somehow is not loaded — this app is unpackaged and a
    /// missing resource must never be able to fault the window.
    /// </summary>
    private Microsoft.UI.Xaml.Media.Brush ThemeBrush(string key)
    {
        if (Root.Resources.TryGetValue(key, out var local) &&
            local is Microsoft.UI.Xaml.Media.Brush b1) return b1;
        if (Application.Current.Resources.TryGetValue(key, out var app) &&
            app is Microsoft.UI.Xaml.Media.Brush b2) return b2;

        (byte a, byte r, byte g, byte b) = key switch
        {
            "AccentBrush" => ((byte)0xFF, (byte)0x4A, (byte)0xDF, (byte)0xBF),
            "DangerBrush" => ((byte)0xFF, (byte)0xFF, (byte)0x7A, (byte)0x75),
            "WarningBrush" => ((byte)0xFF, (byte)0xF5, (byte)0xB9, (byte)0x5F),
            "LabelSecondary" => ((byte)0xB8, (byte)0xFF, (byte)0xFF, (byte)0xFF),
            _ => ((byte)0x5C, (byte)0xFF, (byte)0xFF, (byte)0xFF),
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
            ApplyError(_localError);
            SetStatusChip("DangerBrush", Strings.Get("StatusIdle"));
            return;
        }

        var state = _controller.StateName;
        var reconnecting = _controller.Reconnecting && state == "Connected";

        ShowOnly(
            state == "Idle" && _mode == InputMode.Scanning ? ScanPanel
            : state == "Idle" && _mode == InputMode.Code ? CodePanel
            : state == "Idle" ? IdlePanel
            : state is "Preparing" or "Advertising" ? BusyPanel
            : state == "Connected" ? ConnectedPanel
            : state == "Error" ? ErrorPanel
            : IdlePanel);

        SetStatusChip(
            reconnecting ? "WarningBrush"
            : state switch
            {
                "Connected" => "AccentBrush",
                "Error" => "DangerBrush",
                "Preparing" or "Advertising" => "LabelSecondary",
                _ => "LabelQuaternary",
            },
            reconnecting ? Strings.Get("Reconnecting")
            : state switch
            {
                "Connected" => Strings.Get("StatusConnected"),
                "Preparing" or "Advertising" => Strings.Get("StatusConnecting"),
                _ => Strings.Get("StatusIdle"),
            });

        if (state == "Connected" && _controller.Payload is { } payload)
        {
            ConnectedText.Text = string.Format(Strings.Get("ConnectedVia"), payload.Name ?? payload.Host);
            ConnectedDetailText.Text = $"{payload.Host}:{payload.Port}";
            ReconnectingText.Text = Strings.Get("Reconnecting");
            ReconnectingBanner.Visibility = Show(reconnecting);
            ConnectedDot.Fill = ThemeBrush(reconnecting ? "WarningBrush" : "AccentBrush");
            // The halo breathes only while the link is healthy; during a
            // reconnect it holds still, so "working on it" and "working" are
            // not signalled by the same movement. Started once on entry rather
            // than on every Render: restarting a looping animation snaps it back
            // to its first keyframe, which reads as a stutter every time an
            // unrelated state notification arrives.
            SetPulsing(!reconnecting);
        }
        if (state == "Error") ApplyError(_controller.ErrorCode ?? "ERR_PROXY_APPLY_FAILED");

        AdvancedAddressValue.Text = _controller.Payload is { } p ? $"{p.Host}:{p.Port}" : "—";
    }

    /// <summary>
    /// Swaps the visible panel, animating the change. The outgoing panel fades
    /// before the incoming one rises, so the two never overlap into a smear —
    /// and when the target is already showing, nothing animates at all, which
    /// is what stops a routine Render (a theme change, a log line) from
    /// re-playing the entrance under the user.
    /// </summary>
    private void ShowOnly(FrameworkElement panel)
    {
        if (ReferenceEquals(_visiblePanel, panel)) return;

        var outgoing = _visiblePanel;
        _visiblePanel = panel;

        foreach (var other in AllPanels)
        {
            if (!ReferenceEquals(other, panel) && !ReferenceEquals(other, outgoing))
            {
                other.Visibility = Visibility.Collapsed;
            }
        }

        if (!ReferenceEquals(panel, ConnectedPanel)) SetPulsing(false);

        // Measure *after* the swap, not alongside it: the outgoing panel is
        // still laid out during its fade, so resizing now would size the window
        // to the screen the user is leaving.
        if (outgoing is not null)
        {
            Motion.ExitPanel(outgoing, () =>
            {
                Motion.EnterPanel(panel);
                ResizeToContent();
            });
        }
        else
        {
            Motion.EnterPanel(panel);
            ResizeToContent();
        }
    }

    private FrameworkElement[] AllPanels => new FrameworkElement[]
        { IdlePanel, ScanPanel, CodePanel, BusyPanel, ConnectedPanel, ErrorPanel };

    /// <summary>
    /// Errors get a name and a next step, not one block of prose. The primary
    /// action is the thing that most often fixes it, so recovering does not
    /// mean going back to the start and working out what to press.
    /// </summary>
    private void ApplyError(string code)
    {
        var (title, body, primary) = code switch
        {
            "ERR_QR_NEWER_VERSION" => ("ErrTitleCode", "ErrQrNewer", (string?)null),
            "ERR_QR_INVALID" => ("ErrTitleCode", "ErrQrInvalid", "ScanQr"),
            "ERR_CODE_INVALID" => ("ErrTitleCode", "ErrCodeInvalid", "EnterCode"),
            "ERR_HOST_UNREACHABLE" => ("ErrTitleNoPhone", "ErrHostUnreachable", "TryAgain"),
            "ERR_WRONG_NETWORK" => ("ErrTitleNetwork", "ErrWrongNetwork", "TryAgain"),
            "ERR_CONNECTION_LOST" => ("ErrTitleLost", "ErrConnectionLost", "TryAgain"),
            "ERR_FIREWALL_BLOCKED" => ("ErrTitleBlocked", "ErrFirewall", "TryAgain"),
            "ERR_PROXY_APPLY_FAILED" => ("ErrTitleProxy", "ErrProxyApply", "TryAgain"),
            "ERR_ROLLBACK_INCOMPLETE" => ("ErrTitleRollback", "ErrRollback", "Disconnect"),
            "ERR_CAMERA_DENIED" => ("ErrTitleCamera", "ErrCameraDenied", "EnterCodeInstead"),
            _ => ("ErrTitleProxy", "ErrProxyApply", "TryAgain"),
        };

        _errorAction = primary;
        ErrorTitle.Text = Strings.Get(title);
        ErrorText.Text = Strings.Get(body);
        ErrorPrimaryButton.Content = primary is null ? string.Empty : Strings.Get(primary);
        ErrorPrimaryButton.Visibility = Show(primary is not null);
        ErrorDismissButton.Content = Strings.Get("Dismiss");
    }

    private void SetPulsing(bool on)
    {
        if (on == _pulsing) return;
        _pulsing = on;
        if (on) Motion.StartPulse(ConnectedHalo, 0.5); else Motion.StopPulse(ConnectedHalo);
    }

    private void SetStatusChip(string brushKey, string label)
    {
        StatusDot.Fill = ThemeBrush(brushKey);
        StatusChipText.Text = label;
    }

    /// <summary>
    /// Sizes the window to whatever is actually on screen. The height used to be
    /// fixed, which left the idle screen more than half empty and made every
    /// state feel like it was floating in a container built for something else.
    /// </summary>
    private void ResizeToContent()
    {
        if (!_shown) return;
        DispatcherQueue.TryEnqueue(Microsoft.UI.Dispatching.DispatcherQueuePriority.Low, () =>
        {
            try
            {
                Root.Measure(new Windows.Foundation.Size(PopupWidth, double.PositiveInfinity));
                var desired = Math.Clamp(Root.DesiredSize.Height, MinPopupHeight, MaxPopupHeight);
                PositionWindow((int)Math.Ceiling(desired));
            }
            catch
            {
                // Measurement is a nicety; never let it take the window down.
            }
        });
    }

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

        // A DispatcherQueueHandler returns void, so this lambda is an async void
        // running ten times a second for the whole scan: SetBitmapAsync is a
        // WinRT call that throws while the XAML core is tearing down, and an
        // escape would reach App.UnhandledException and kill the process
        // mid-scan. The old frame is also disposed *after* the new one is in
        // place, rather than from a stale local that two interleaved callbacks
        // could both read as null (which leaked the unmanaged bitmap).
        var queued = DispatcherQueue.TryEnqueue(async () =>
        {
            try
            {
                using (bitmap)
                {
                    if (_mode != InputMode.Scanning) return;
                    var source = new SoftwareBitmapSource();
                    await source.SetBitmapAsync(bitmap);
                    var previous = PreviewImage.Source as SoftwareBitmapSource;
                    PreviewImage.Source = source;
                    previous?.Dispose();
                }
            }
            catch (Exception ex)
            {
                LocalLog.Add($"Preview frame dropped: {ex.Message}");
            }
        });
        // The queue refuses work once it is shutting down; that frame still owns
        // an unmanaged bitmap nobody else will free.
        if (!queued) bitmap.Dispose();
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

    /// <summary>
    /// Validates as the user types and connects the moment a complete, valid
    /// code is entered. The code carries a checksum, so "is this right" is
    /// answerable immediately — making the user finish typing, read the button,
    /// aim at it and click was a step the app could simply take itself.
    /// </summary>
    private void OnCodeChanged(object sender, TextChangedEventArgs e)
    {
        var raw = CodeBox.Text ?? string.Empty;
        var clean = new string(raw.Where(char.IsLetterOrDigit).ToArray()).ToUpperInvariant();

        if (clean.Length == 0)
        {
            CodeHintText.Text = Strings.Get("CodeHint");
            CodeHintText.Foreground = ThemeBrush("LabelTertiary");
            CodeConnectButton.IsEnabled = false;
            return;
        }

        // Reject characters the alphabet does not contain before blaming the
        // checksum: "that isn't one of the letters on your phone" is a more
        // useful thing to be told than "invalid".
        if (clean.Any(c => !TypedCode.Alphabet.Contains(c)))
        {
            CodeHintText.Text = Strings.Get("CodeBadChars");
            CodeHintText.Foreground = ThemeBrush("WarningBrush");
            CodeConnectButton.IsEnabled = false;
            return;
        }

        if (clean.Length < TypedCode.Length)
        {
            CodeHintText.Text = string.Format(
                Strings.Get("CodeIncomplete"), TypedCode.Length - clean.Length);
            CodeHintText.Foreground = ThemeBrush("LabelTertiary");
            CodeConnectButton.IsEnabled = false;
            return;
        }

        if (TypedCode.Decode(clean) is null)
        {
            CodeHintText.Text = Strings.Get("CodeChecksum");
            CodeHintText.Foreground = ThemeBrush("DangerBrush");
            CodeConnectButton.IsEnabled = false;
            Motion.Reject(CodeBox);
            return;
        }

        CodeHintText.Text = Strings.Get("CodeReady");
        CodeHintText.Foreground = ThemeBrush("AccentBrush");
        CodeConnectButton.IsEnabled = true;
        OnCodeConnectClick(sender, new RoutedEventArgs());
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

    /// <summary>The recovery the error itself suggests, so there is always a way forward.</summary>
    private async void OnErrorPrimaryClick(object sender, RoutedEventArgs e)
    {
        var action = _errorAction;
        _localError = null;
        switch (action)
        {
            case "ScanQr":
                _controller.DismissError();
                OnScanClick(sender, e);
                break;
            case "EnterCode":
            case "EnterCodeInstead":
                _controller.DismissError();
                OnEnterCodeClick(sender, e);
                break;
            case "Disconnect":
                await _controller.DisconnectAsync();
                break;
            default:
                _controller.DismissError();
                _mode = InputMode.None;
                Render();
                break;
        }
    }

    // --- shared handlers -----------------------------------------------------------

    /// <summary>Backs out of scanning, code entry, or a local error — whichever is up.</summary>
    private void OnCancelClick(object sender, RoutedEventArgs e)
    {
        // Escape reaches here from the error surface too, and a cancel that
        // leaves the error still on screen is a key that appears to do nothing.
        _localError = null;
        StopScanning();
    }

    /// <summary>
    /// Cancels an in-flight connection attempt. "stop" is a legal transition out
    /// of both Preparing and Advertising, and ConnectAsync already unwinds a
    /// proxy it applied if the state moved underneath it — so this genuinely
    /// aborts the attempt rather than only hiding the progress ring.
    /// </summary>
    private async void OnBusyCancelClick(object sender, RoutedEventArgs e)
    {
        _localError = null;
        _mode = InputMode.None;
        await _controller.DisconnectAsync();
    }

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
