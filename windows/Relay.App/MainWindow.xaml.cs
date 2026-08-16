using System.Linq;
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
///
/// Two departures from that model, both because Windows is not macOS. It appears
/// in Alt-Tab, because Windows 11 hides new tray icons in the overflow and a
/// person who cannot find the icon has no way back at all. And it does not
/// auto-hide while connected, because that is exactly when someone comes looking
/// for it -- to see the state, or to disconnect.
/// </summary>
public sealed partial class MainWindow : Window
{
    private const int PopupWidth = 420;
    // The window follows its content between these bounds instead of standing
    // at a fixed height with a hole in the middle of it.
    private const int MinPopupHeight = 360;
    private const int MaxPopupHeight = 660;

    private readonly AppController _controller = AppController.Instance;

    /// <summary>
    /// Listens for phones announcing themselves, so a two-digit code can be
    /// resolved to an address. Started once and left running: discovery has to
    /// already know about a phone by the time someone finishes typing, and a
    /// listener that starts when the box opens would find nothing for a second.
    /// </summary>
    private readonly LanDiscovery _discovery = new();
    private CameraQrScanner? _scanner;
    private long _lastPreviewTicks;
    private enum InputMode { None, Scanning, Code, Pairing }
    private InputMode _mode = InputMode.None;
    private string? _localError;
    private long _shownAtTick;
    private FrameworkElement? _visiblePanel;
    private string? _errorAction;
    private bool _shown;
    private bool _pulsing;

    /// <summary>Kept so the window can stop floating once it stops being transient.</summary>
    private OverlappedPresenter? _presenter;

    /// <summary>Live counters for the connected screen, read from the adapter.</summary>
    private readonly TunnelStats _stats = new();
    private DispatcherTimer? _statsTimer;
    private TimeSpan? _latency;
    private int _latencyTick;

    /// <summary>
    /// True while the code box is asking for the eight-character fallback
    /// instead of the two digits the phone normally shows.
    /// </summary>
    private bool _longCode;

    /// <summary>Guards the two paths that can resolve a code at the same moment.</summary>
    private bool _connecting;

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
        ApplyMaterial();
        Title = Strings.Get("AppName");
        ConfigureAppWindow();
        ApplyStrings();

        _controller.StateChanged += () => DispatcherQueue.TryEnqueue(Render);
        LocalLog.Changed += () => DispatcherQueue.TryEnqueue(RefreshLogs);
        // Discovery runs on its own socket thread; everything it touches here
        // is UI, so it is marshalled rather than handled where it arrives.
        _discovery.DevicesChanged += _ => DispatcherQueue.TryEnqueue(OnDevicesChanged);
        Root.ActualThemeChanged += (_, _) => Render();
        AttachPressFeedback();
        AttachKeyboard();
        RefreshLogs();

        // Start listening straight away. Someone types two digits in about a
        // second, and a listener that only starts when the code box opens would
        // still be empty when they finish -- they would be told no phone is
        // there while the phone is shouting on the wire.
        try
        {
            _discovery.Start();
        }
        catch (Exception ex)
        {
            // Another program on 47654, or a policy that forbids the bind.
            // Pairing by QR and by the eight-character code both still work, so
            // this is a note in the log rather than a dialog.
            LocalLog.Add($"Device discovery unavailable: {ex.Message}");
        }

        Render();
    }

    /// <summary>
    /// Picks the window's material from the system transparency setting. Acrylic
    /// is the glass; with transparency effects off there is nothing behind the
    /// scrim, and a 25%-alpha gradient over nothing is a window you can see the
    /// desktop through — so the scrim is replaced by a solid surface rather than
    /// left to sit on air.
    ///
    /// Reduced transparency is an accessibility setting, not a performance one:
    /// honouring it is the difference between text over a controlled background
    /// and text over whatever wallpaper happens to be there.
    /// </summary>
    private void ApplyMaterial()
    {
        if (SystemPreferences.TransparencyEnabled)
        {
            // Set in code so an unsupported backdrop degrades to the scrim
            // instead of failing the XAML load.
            try
            {
                SystemBackdrop = new Microsoft.UI.Xaml.Media.DesktopAcrylicBackdrop();
                return;
            }
            catch
            {
                // No acrylic available: fall through to the solid surface, which
                // is the same thing the user would have asked for anyway.
            }
        }
        Root.Background = ThemeBrush("WindowSolidBrush");
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
            _presenter = presenter;
        }
        // Alt-Tab finds it. A tray-only app is a lost app on Windows 11, which
        // hides new notification icons in the overflow by default -- so the one
        // documented way back to Relay is behind a chevron most people never
        // open. Finding the icon took deliberate UI-automation work during
        // hardware testing; a person who just wants to disconnect has less
        // patience than that.
        //
        // This costs nothing while hidden: a window that is not shown is not in
        // the switcher either, so the tray still owns the "put it away" story.
        AppWindow.IsShownInSwitchers = true;

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

        // macOS-popover behaviour: hide when focus is lost — but never while the
        // person is part-way through something.
        //
        // Only scanning used to be protected, on the reasoning that a system
        // camera prompt must not dismiss the scanner. Every other step needed
        // the same protection and did not have it: typing a code lost the digits
        // to any window that took focus, and waiting on the phone's approval
        // hid the window at the worst possible moment — Full Mode's own UAC
        // prompt takes focus, so connecting made the window disappear and left
        // someone watching a tray icon wondering what had happened.
        //
        // Losing a popover you did not dismiss is bad; losing what you typed
        // into it is worse.
        Activated += (_, e) =>
        {
            // Ignore the brief deactivation that can follow a show (if the
            // foreground grab momentarily loses), else the popover flash-hides.
            // Idle is the only state where nothing is under way. The previous
            // guard named the states to protect and missed the ones in the
            // middle: Preparing and Advertising are the seconds while the tunnel
            // is being built, and the window vanished right there -- during the
            // most anxious part of connecting, and while Windows' own elevation
            // prompt was taking focus. Error counts too: an error nobody can
            // read is an error nobody can act on.
            if (e.WindowActivationState == WindowActivationState.Deactivated
                && _mode == InputMode.None
                && _controller.StateName == "Idle"
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

    /// <summary>
    /// Adopts the tray icon into this window's visual tree.
    ///
    /// A TaskbarIcon is a FrameworkElement, and its context flyout needs a
    /// XamlRoot to route input through. Created free-floating — which is how it
    /// was — the menu could open and its items could still never raise Click,
    /// which is one half of "Exit and Disconnect do nothing" (issue #18, and the
    /// August field reports that reopened it). It draws nothing and is pinned to
    /// zero size, so it cannot disturb the layout it now lives in.
    /// </summary>
    public void HostTrayIcon(FrameworkElement trayIcon)
    {
        trayIcon.Width = 0;
        trayIcon.Height = 0;
        trayIcon.HorizontalAlignment = HorizontalAlignment.Left;
        trayIcon.VerticalAlignment = VerticalAlignment.Top;
        // Never let a hosting failure cost the user their tray icon entirely:
        // an unparented icon is degraded, an exception here is no icon at all.
        try { Root.Children.Add(trayIcon); }
        catch (Exception ex) { LocalLog.Add($"Tray hosting failed: {ex.Message}"); }
    }

    /// <summary>Positions the popover above the tray and brings it to the real foreground.</summary>
    public void ShowNearTray()
    {
        _shown = true;
        // Ask the network who is there only while someone is looking. A tray
        // app that broadcasts once a second all day would be a bad neighbour,
        // and discovery is worth nothing when the window is hidden.
        try { _discovery.SetProbing(true); } catch (Exception ex) { LocalLog.Add($"Probe failed: {ex.Message}"); }
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
        _discovery.SetProbing(false);
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
        CodeModeLink.Content = Strings.Get("CodeUseLong");
        FoundHeader.Text = Strings.Get("CodeNearby");
        CodeConnectButton.Content = Strings.Get("Connect");
        CodeCancelButton.Content = Strings.Get("Cancel");
        BusyText.Text = Strings.Get("BusyConnecting");
        BusyDetailText.Text = Strings.Get("BusyDetail");
        BusyCancelButton.Content = Strings.Get("Cancel");
        DisconnectButton.Content = Strings.Get("Disconnect");
        ErrorDismissButton.Content = Strings.Get("Dismiss");
        AdvancedHeader.Text = Strings.Get("Advanced");
        AdvancedVersionLabel.Text = Strings.Get("AdvancedVersion");
        AdvancedVersionValue.Text = AppVersion.Current;
        AdvancedAddressLabel.Text = Strings.Get("AdvancedAddress");
        AdvancedLogsLabel.Text = Strings.Get("AdvancedLogs");
        AdvancedLogsClear.Content = Strings.Get("AdvancedLogsClear");
        AdvancedLogsShare.Content = Strings.Get("AdvancedLogsShare");
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
            // Opaque on purpose: this one is a window background, and the
            // translucent default below would leave the desktop showing through.
            "WindowSolidBrush" => ((byte)0xFF, (byte)0x12, (byte)0x16, (byte)0x1D),
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

    /// <summary>
    /// Puts the report on the clipboard and opens a GitHub issue with it
    /// already in the body. Two steps rather than one because the clipboard
    /// copy is what saves the report if the browser fails to open, and because
    /// a person who would rather send it somewhere else -- Telegram, mail --
    /// now has it in hand without being routed through GitHub first.
    /// </summary>
    private async void OnShareLogsClick(object sender, RoutedEventArgs e)
    {
        var entries = LocalLog.Snapshot()
            .Select(entry => (entry.ElapsedSeconds, entry.Message))
            .ToList();
        var report = DiagnosticReport.Build(StateSummary(), entries, AppVersion.Current);

        var package = new Windows.ApplicationModel.DataTransfer.DataPackage();
        package.SetText(report);
        Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(package);
        LocalLog.Add("Diagnostic report copied to the clipboard");

        await Windows.System.Launcher.LaunchUriAsync(new Uri(DiagnosticReport.IssueUrl(report)));
    }

    /// <summary>One line saying what went wrong, not just which screen is up.</summary>
    private string StateSummary()
    {
        var error = _localError ?? _controller.ErrorCode;
        return error is null ? _controller.StateName : $"Error: {error}";
    }

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

        // Float only while it is behaving like a popover. Once connected it
        // stays put instead of auto-hiding, and a small always-on-top panel
        // parked over everything you do is the kind of thing people close and
        // then cannot find again.
        if (_presenter is not null)
        {
            var transient = state != "Connected";
            if (_presenter.IsAlwaysOnTop != transient) _presenter.IsAlwaysOnTop = transient;
        }

        ShowOnly(
            state == "Idle" && _mode == InputMode.Scanning ? ScanPanel
            : state == "Idle" && _mode == InputMode.Code ? CodePanel
            // Asking the phone happens before the controller has any state
            // of its own, so this is the one busy screen the window owns.
            : state == "Idle" && _mode == InputMode.Pairing ? BusyPanel
            : state == "Idle" ? IdlePanel
            : state is "Preparing" or "Advertising" ? BusyPanel
            : state == "Connected" ? ConnectedPanel
            : state == "Error" ? ErrorPanel
            : IdlePanel);

        // Refresh the phones on the idle screen whenever it is the screen being
        // shown, so it is never a stale list from the last time it was open.
        if (ReferenceEquals(_visiblePanel, IdlePanel)) PopulateIdleList();

        if (state == "Connected") StartStats(); else StopStats();

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
            "ERR_CODE_NOT_FOUND" => ("ErrTitleCode", "ErrCodeNotFound", "EnterCode"),
            "ERR_CODE_AMBIGUOUS" => ("ErrTitleCode", "ErrCodeAmbiguous", "EnterCode"),
            "ERR_FULL_MODE_NEEDS_QR" => ("ErrTitleCode", "ErrFullModeNeedsQr", "ScanQr"),
            "ERR_HOST_UNREACHABLE" => ("ErrTitleNoPhone", "ErrHostUnreachable", "TryAgain"),
            "ERR_WRONG_NETWORK" => ("ErrTitleNetwork", "ErrWrongNetwork", "TryAgain"),
            "ERR_CONNECTION_LOST" => ("ErrTitleLost", "ErrConnectionLost", "TryAgain"),
            "ERR_FIREWALL_BLOCKED" => ("ErrTitleBlocked", "ErrFirewall", "TryAgain"),
            "ERR_PROXY_APPLY_FAILED" => ("ErrTitleProxy", "ErrProxyApply", "TryAgain"),
            "ERR_ROLLBACK_INCOMPLETE" => ("ErrTitleRollback", "ErrRollback", "Disconnect"),
            "ERR_CAMERA_DENIED" => ("ErrTitleCamera", "ErrCameraDenied", "EnterCodeInstead"),
            // Full Mode (ADR-0008). A declined elevation prompt is its own
            // answer: "the tunnel failed" would send someone hunting their
            // network for something they chose two seconds earlier.
            "ERR_WG_ELEVATION_DECLINED" => ("ErrTitleElevation", "ErrWgElevationDeclined", "TryAgain"),
            "ERR_WG_START_FAILED" => ("ErrTitleTunnel", "ErrWgStartFailed", "TryAgain"),
            "ERR_WG_ELEVATION_UNAVAILABLE" =>
                ("ErrTitleElevationBlocked", "ErrWgElevationUnavailable", (string?)null),
            "ERR_WG_NO_HANDSHAKE" => ("ErrTitleTunnel", "ErrWgNoHandshake", "ScanQr"),
            "ERR_PAIRING_DENIED" => ("ErrTitlePairing", "ErrPairingDenied", "EnterCode"),
            "ERR_PAIRING_VERSION" => ("ErrTitlePairing", "ErrPairingVersion", "ScanQr"),
            "ERR_WG_ALREADY_RUNNING" => ("ErrTitleTunnel", "ErrWgStartFailed", "TryAgain"),
            "ERR_WG_STOP_FAILED" => ("ErrTitleTunnel", "ErrWgStopFailed", "TryAgain"),
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
        _longCode = false;
        _connecting = false;
        CodeBox.Text = string.Empty;
        ApplyCodeMode();
        // Ask straight away rather than waiting out a probe tick: the phone can
        // answer before the box has finished animating in, and a list that
        // fills a second after the user starts typing has already lost them.
        try { _discovery.Probe(); } catch (Exception ex) { LocalLog.Add($"Probe failed: {ex.Message}"); }
        PopulateFoundList();
        Render();
        CodeBox.Focus(FocusState.Programmatic);
    }

    /// <summary>
    /// Switches between the two digits the phone normally shows and the eight
    /// characters it falls back to when it could not announce itself at all.
    /// </summary>
    private void OnCodeModeClick(object sender, RoutedEventArgs e)
    {
        _longCode = !_longCode;
        CodeBox.Text = string.Empty;
        ApplyCodeMode();
        CodeBox.Focus(FocusState.Programmatic);
    }

    /// <summary>
    /// Shapes the box to the code it is asking for. The width of the box is
    /// itself an instruction — a nine-character field says "type nine
    /// characters" more loudly than any caption under it.
    /// </summary>
    private void ApplyCodeMode()
    {
        CodeBox.MaxLength = _longCode ? TypedCode.Length + 1 : LanDiscovery.CodeLength;
        CodeBox.PlaceholderText = _longCode ? "XXXX-XXXX" : "00";
        CodeHintText.Text = Strings.Get(_longCode ? "CodeHintLong" : "CodeHint");
        CodeHintText.Foreground = ThemeBrush("LabelTertiary");
        CodeModeLink.Content = Strings.Get(_longCode ? "CodeUseShort" : "CodeUseLong");
        CodeConnectButton.IsEnabled = false;
        FoundPanel.Visibility = Show(!_longCode && FoundList.Children.Count > 0);
    }

    /// <summary>
    /// Validates as the user types and connects the moment the code resolves to
    /// exactly one phone — making someone finish typing, read the button, aim
    /// at it and click was a step the app could simply take itself.
    /// </summary>
    private void OnCodeChanged(object sender, TextChangedEventArgs e) => EvaluateCode(mayConnect: true);

    private void EvaluateCode(bool mayConnect)
    {
        if (_longCode) { EvaluateLongCode(mayConnect); return; }

        var typed = new string((CodeBox.Text ?? string.Empty).Where(ch => !char.IsWhiteSpace(ch)).ToArray());

        if (typed.Length == 0)
        {
            CodeHintText.Text = Strings.Get("CodeHint");
            CodeHintText.Foreground = ThemeBrush("LabelTertiary");
            CodeConnectButton.IsEnabled = false;
            return;
        }

        // Named separately from "that code is wrong": the phone shows digits, so
        // a letter here means the user is reading the wrong thing off the
        // screen, and saying which thing is the useful half of the message.
        if (!typed.All(char.IsAsciiDigit))
        {
            CodeHintText.Text = Strings.Get("CodeDigitsOnly");
            CodeHintText.Foreground = ThemeBrush("WarningBrush");
            CodeConnectButton.IsEnabled = false;
            Motion.Reject(CodeBox);
            return;
        }

        // Codes start at 10 precisely so there is no "did I need the leading
        // zero" moment; a typed zero is someone who has misread the screen.
        if (typed[0] == '0')
        {
            CodeHintText.Text = Strings.Get("CodeNoLeadingZero");
            CodeHintText.Foreground = ThemeBrush("WarningBrush");
            CodeConnectButton.IsEnabled = false;
            return;
        }

        if (typed.Length < LanDiscovery.CodeLength)
        {
            CodeHintText.Text = Strings.Get("CodeHintShort");
            CodeHintText.Foreground = ThemeBrush("LabelTertiary");
            CodeConnectButton.IsEnabled = false;
            return;
        }

        var digits = LanDiscovery.NormalizeCode(typed);
        if (digits is null)
        {
            CodeHintText.Text = Strings.Get("CodeDigitsOnly");
            CodeHintText.Foreground = ThemeBrush("WarningBrush");
            CodeConnectButton.IsEnabled = false;
            return;
        }
        OnShortCodeTyped(digits, mayConnect);
    }

    /// <summary>
    /// Two digits were typed. Unlike the long code there is nothing to decode —
    /// the answer is whichever phone on this network is announcing that number,
    /// so the failure modes are "nobody yet" and "more than one" rather than
    /// "malformed".
    /// </summary>
    private void OnShortCodeTyped(string digits, bool mayConnect)
    {
        var matches = _discovery.Match(digits);
        switch (matches.Count)
        {
            case 0:
                // Not an error, and it must not read as one: the phone's next
                // beacon is at most a second away, and this same check runs
                // again the moment it lands. Saying "no phone has that code"
                // in red to someone whose phone is about to appear is how a
                // working setup gets abandoned.
                CodeHintText.Text = Strings.Get("CodeLooking");
                CodeHintText.Foreground = ThemeBrush("LabelTertiary");
                CodeConnectButton.IsEnabled = false;
                return;
            case 1:
                CodeHintText.Text = matches[0].Name is { Length: > 0 } name
                    ? string.Format(Strings.Get("CodeFoundNamed"), name)
                    : Strings.Get("CodeReady");
                CodeHintText.Foreground = ThemeBrush("AccentBrush");
                CodeConnectButton.IsEnabled = true;
                if (mayConnect) _ = ConnectToAsync(matches[0]);
                return;
            default:
                // Rare, and the only honest thing to do is ask. Picking the
                // first would connect someone to a stranger's phone without
                // ever telling them there was a choice.
                CodeHintText.Text = string.Format(
                    Strings.Get("CodeAmbiguous"),
                    string.Join(", ", matches.Select(m => m.Name ?? m.Host)));
                CodeHintText.Foreground = ThemeBrush("WarningBrush");
                CodeConnectButton.IsEnabled = false;
                return;
        }
    }

    /// <summary>
    /// The eight-character fallback. Unchanged in substance: it carries the
    /// address itself, so it can be judged without the network's help.
    /// </summary>
    private void EvaluateLongCode(bool mayConnect)
    {
        // Normalise exactly as TypedCode.Decode does, because that is what
        // judges the code a moment later. This used to strip every character
        // that was not a letter or digit, which is a wider rule than the
        // contract's "whitespace and '-'". A code typed or pasted with any
        // other separator — "ABCD.EFGH" — therefore passed this check with the
        // dot quietly removed, turned the hint green, said "ready" and
        // auto-connected, and the decoder then saw the raw nine characters and
        // rejected them as ERR_CODE_INVALID. The code was correct; the app
        // refused it and blamed the user.
        var clean = TypedCode.Normalize(CodeBox.Text);

        if (clean.Length == 0)
        {
            CodeHintText.Text = Strings.Get("CodeHintLong");
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
        if (mayConnect) OnCodeConnectClick(this, new RoutedEventArgs());
    }

    /// <summary>
    /// Rebuilds the list of phones announcing themselves right now. Each row is
    /// the device's name and the code it is showing, so the two screens can be
    /// checked against one another — and clicking one skips typing entirely.
    /// </summary>
    private void PopulateFoundList()
    {
        Fill(FoundList, _discovery.Devices);
        FoundHeader.Text = Strings.Get("CodeNearby");
        FoundPanel.Visibility = Show(!_longCode && FoundList.Children.Count > 0);
    }

    /// <summary>
    /// The same phones, on the screen people actually land on.
    ///
    /// Discovery has been running since the window opened, so by the time anyone
    /// reads the idle text the app usually already knows which phone is sharing
    /// and what code it shows. Asking them to read those two digits off the
    /// phone and type them back is asking them to relay information the app has
    /// in hand. When the list is not empty, one click is the entire pairing.
    /// </summary>
    private void PopulateIdleList()
    {
        // Only phones a code can actually pair with. One that could not bind its
        // pairing port is real and worth showing in the code box -- where the
        // error explains itself -- but offering it as a one-click row here would
        // promise something that cannot happen.
        var devices = _discovery.Devices.Where(d => d.CanPairByCode).ToList();
        Fill(IdleFoundList, devices, accentSingle: true);
        IdleFoundHeader.Text = Strings.Get("IdleNearby");
        IdleFoundPanel.Visibility = Show(devices.Count > 0);

        // "Open Relay on your phone and tap Start Sharing" above a list of
        // phones that are already sharing reads as a contradiction, and makes
        // someone wonder whether the app has noticed what it is showing them.
        IdleBody.Visibility = Show(devices.Count == 0);

        // Two accented buttons on one screen is no emphasis at all. When a
        // phone is offered, scanning becomes the alternative it actually is.
        if (Application.Current.Resources.TryGetValue(
                devices.Count > 0 ? "SecondaryButton" : "PrimaryButton", out var scanStyle) &&
            scanStyle is Style scan) ScanButton.Style = scan;
    }

    /// <param name="accentSingle">
    /// Give a lone phone the accent. On the idle screen one phone is genuinely
    /// "the one thing to do on this screen", and leaving it text-weight put the
    /// emphasis on Scan QR instead -- the slower path drawn as the obvious one.
    /// With several phones there is a choice to make, so none of them shouts.
    /// </param>
    private void Fill(Panel list, IReadOnlyList<LanDiscovery.Device> devices, bool accentSingle = false)
    {
        list.Children.Clear();
        foreach (var device in devices)
        {
            var button = new Button
            {
                // The code first, because that is the thing the eye is comparing
                // against the phone.
                Content = device.Name is { Length: > 0 } name
                    ? $"{device.Code}   {name}"
                    : $"{device.Code}   {device.Host}",
                Tag = device,
                HorizontalAlignment = HorizontalAlignment.Stretch,
                HorizontalContentAlignment = HorizontalAlignment.Left,
            };
            // Never QuietButton: that style has no fill by design, so a row
            // drawn with it reads as a caption rather than something to click.
            var wanted = accentSingle && devices.Count == 1 ? "PrimaryButton" : "SecondaryButton";
            if (Application.Current.Resources.TryGetValue(wanted, out var style) &&
                style is Style resolved) button.Style = resolved;
            button.Click += OnFoundPhoneClick;
            list.Children.Add(button);
        }
    }

    private void OnFoundPhoneClick(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: LanDiscovery.Device device }) _ = ConnectToAsync(device);
    }

    /// <summary>
    /// A phone appeared, moved or stopped sharing. Re-runs the same judgement
    /// the keystroke would have: someone who types two digits in under a second
    /// can easily beat the first beacon, and without this they are left looking
    /// at "still looking" while the phone sits in the list right below it.
    /// </summary>
    private void OnDevicesChanged()
    {
        if (ReferenceEquals(_visiblePanel, IdlePanel))
        {
            PopulateIdleList();
            ResizeToContent();
            return;
        }
        if (!ReferenceEquals(_visiblePanel, CodePanel)) return;
        PopulateFoundList();
        EvaluateCode(mayConnect: true);
        // A row appearing or leaving changes how tall the panel is, and the
        // window does not resize itself.
        ResizeToContent();
    }

    /// <summary>
    /// Hands a resolved phone to the controller. Guarded, because two paths can
    /// arrive here at once — the keystroke that completed the code and the
    /// beacon that arrived in the same moment — and connecting twice tears the
    /// first attempt down underneath itself.
    /// </summary>
    private async Task ConnectToAsync(LanDiscovery.Device device)
    {
        if (_connecting) return;

        // The beacon cannot carry keys — it is broadcast, and anything on the
        // network can read it — so a code alone is not a pairing. The phone
        // offers a port to ask on instead, and a phone that could not bind that
        // port says so by leaving it out. Then the QR is the only way in, and
        // saying that is better than "That's not a Relay code." in front of a
        // perfectly correct one, which reads as the two apps being incompatible.
        if (!device.CanPairByCode)
        {
            ShowLocalError("ERR_FULL_MODE_NEEDS_QR");
            return;
        }

        _connecting = true;
        _localError = null;
        try
        {
            var payload = await PairAsync(device.Host, device.PairingPort!.Value, device.Name);
            if (payload is null) return; // PairAsync surfaced the reason
            await _controller.ConnectAsync(payload);
        }
        finally
        {
            _connecting = false;
        }
    }

    private async void OnCodeConnectClick(object sender, RoutedEventArgs e)
    {
        if (!_longCode)
        {
            var digits = LanDiscovery.NormalizeCode(CodeBox.Text);
            if (digits is null)
            {
                ShowLocalError("ERR_CODE_INVALID");
                return;
            }
            var matches = _discovery.Match(digits);
            if (matches.Count != 1)
            {
                // Zero means the phone is not sharing or is on another network;
                // more than one needs a human to choose. Neither is a malformed
                // code, so neither says so.
                ShowLocalError(matches.Count == 0 ? "ERR_CODE_NOT_FOUND" : "ERR_CODE_AMBIGUOUS");
                return;
            }
            await ConnectToAsync(matches[0]);
            return;
        }

        var decoded = TypedCode.Decode(TypedCode.Normalize(CodeBox.Text));
        if (decoded is null)
        {
            ShowLocalError("ERR_CODE_INVALID");
            return;
        }
        // The eight-character code carries an address and nothing else. Since
        // ADR-0009 that is enough: ask at that address, on the port every phone
        // offers, and the keys come back over the exchange.
        _localError = null;
        var fetched = await PairAsync(decoded.Value.Host, PairingDefaultPort, null);
        if (fetched is not null) await _controller.ConnectAsync(fetched);
    }

    /// <summary>Where a phone offers configurations (/shared/pairing-beacon.md).</summary>
    private const int PairingDefaultPort = 47655;

    /// <summary>
    /// Asks a phone for a configuration, and turns it into something dialable.
    ///
    /// This blocks on a person: the phone holds the request until someone taps
    /// Allow, for up to a minute. So the window says what it is waiting for
    /// rather than appearing to hang — "look at your phone" is the whole
    /// instruction, and a spinner alone would not give it.
    /// </summary>
    private async Task<QrPayload?> PairAsync(string host, int pairingPort, string? name)
    {
        BusyText.Text = Strings.Get("BusyPairing");
        BusyDetailText.Text = Strings.Get("BusyPairingDetail");
        _mode = InputMode.Pairing;
        Render();

        var result = await Task.Run(() =>
            new PairingClient().Fetch(host, pairingPort, Environment.MachineName));

        // Both lines, not just the headline: leaving the pairing detail behind
        // put "Tap Allow on your phone" under "Setting up the connection", which
        // is an instruction for a prompt that has already been answered.
        _mode = InputMode.None;
        BusyText.Text = Strings.Get("BusyConnecting");
        BusyDetailText.Text = Strings.Get("BusyDetail");

        if (!result.Ok)
        {
            ShowLocalError(result.ErrorCode ?? "ERR_HOST_UNREACHABLE");
            return null;
        }

        return new QrPayload
        {
            V = QrPayloadCodec.SupportedVersion,
            Mode = QrPayload.ModeWireguard,
            Host = result.Host ?? host,
            Port = result.Port,
            Name = name,
            Wg = result.Wg,
        };
    }

    /// <summary>
    /// Samples the tunnel once a second while it is up.
    ///
    /// One second is the slowest interval at which a rate still feels live, and
    /// the fastest at which it does not jitter: shorter windows turn ordinary
    /// TCP burstiness into a number that flickers too much to read. Latency is
    /// measured every fifth tick instead, because a ping every second on a
    /// metered phone connection is traffic Relay charged the user for in order
    /// to display a number.
    /// </summary>
    private void StartStats()
    {
        if (_statsTimer is not null) return;
        _stats.Begin(DateTimeOffset.UtcNow);
        _latency = null;
        _latencyTick = 0;
        _statsTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _statsTimer.Tick += (_, _) => SampleStats();
        _statsTimer.Start();
        SampleStats();
    }

    private void StopStats()
    {
        _statsTimer?.Stop();
        _statsTimer = null;
        _stats.Reset();
        _latency = null;
    }

    private void SampleStats()
    {
        if (_latencyTick++ % 5 == 0)
        {
            var peer = WgClientConfig.ServerTunnelAddress;
            // Off the UI thread: a ping that times out would otherwise freeze the
            // window for its full timeout, which is exactly the moment someone is
            // watching it to see whether the connection is alive.
            _ = Task.Run(() =>
            {
                var measured = TunnelStats.PingPeer(peer);
                DispatcherQueue.TryEnqueue(() => _latency = measured);
            });
        }

        var reading = _stats.Update(DateTimeOffset.UtcNow, _latency);
        if (reading is null) return;
        var r = reading.Value;

        StatDownRate.Text = $"↓ {TunnelStats.Rate(r.DownPerSecond)}";
        StatUpRate.Text = $"↑ {TunnelStats.Rate(r.UpPerSecond)}";
        StatDownTotal.Text = $"{Strings.Get("StatDown")} {TunnelStats.Bytes(r.Received)}";
        StatUpTotal.Text = $"{Strings.Get("StatUp")} {TunnelStats.Bytes(r.Sent)}";
        StatLatency.Text = r.Latency is { } l
            ? $"{Strings.Get("StatLatency")} {(int)l.TotalMilliseconds} ms"
            : Strings.Get("StatLatencyUnknown");
        StatDuration.Text = $"{Strings.Get("StatConnectedFor")} {TunnelStats.Duration(r.Duration)}";
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
