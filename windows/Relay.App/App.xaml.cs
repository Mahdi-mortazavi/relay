using H.NotifyIcon;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media.Imaging;
using Relay.App.Services;
using Relay.Core;

namespace Relay.App;

public partial class App : Application
{
    private static Mutex? _singleInstance;
    private static EventWaitHandle? _showSignal;
    private const string ShowSignalName = @"Local\RelayAppShow";

    private TaskbarIcon? _tray;
    private UpdateService? _updates;
    private MainWindow? _window;

    public App()
    {
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        // Uninstall/repair runs "Relay.App.exe --restore-proxy" to undo an active
        // session's system proxy before the app is removed. Headless; no window.
        if (Environment.GetCommandLineArgs().Any(a =>
                string.Equals(a, "--restore-proxy", StringComparison.OrdinalIgnoreCase)))
        {
            try { AppController.Instance.RecoverIfCrashed(); } catch { }
            Exit();
            return;
        }

        // Attach the crash handler FIRST so any startup failure is written to a
        // log instead of dying silently (this app is unpackaged; a stowed COM
        // exception during startup would otherwise leave no trace).
        UnhandledException += (_, e) =>
        {
            LogStartupError(e.Exception);
            Cleanup();
            e.Handled = false;
        };

        try
        {
            _singleInstance = new Mutex(initiallyOwned: true, @"Local\RelayAppSingleton", out var isFirst);
            if (!isFirst)
            {
                // Already running in the tray: tell that instance to show its
                // window instead of silently doing nothing, then exit.
                try { EventWaitHandle.OpenExisting(ShowSignalName).Set(); } catch { }
                Exit();
                return;
            }

            // Safety invariant #2: undo a crashed session before anything else.
            // Guarded: this is the very first thing that runs, and a single
            // unreadable or ACL-locked backup file used to throw out of
            // OnLaunched on *every* launch, leaving the user with no window, no
            // tray icon, no way to disconnect, and a stale proxy forever.
            try
            {
                AppController.Instance.RecoverIfCrashed();
            }
            catch (Exception ex)
            {
                LogStartupError(ex);
            }

            _window = new MainWindow();

            // Started by Windows at login, not by a person: come up in the tray
            // rather than over whatever they are doing. A window that appears
            // unbidden at every login is the reason people turn "start with
            // Windows" back off.
            var fromStartup = Environment.GetCommandLineArgs().Any(a =>
                string.Equals(a, StartupRegistration.TrayArgument, StringComparison.OrdinalIgnoreCase));
            if (!fromStartup) _window.ShowNearTray();

            // Let a second launch (see the !isFirst path) pop this window back up.
            _showSignal = new EventWaitHandle(false, EventResetMode.AutoReset, ShowSignalName);
            ThreadPool.RegisterWaitForSingleObject(
                _showSignal,
                (_, _) => _window?.DispatcherQueue.TryEnqueue(() => _window?.ShowNearTray()),
                null, Timeout.Infinite, executeOnlyOnce: false);
            // Tray creation is best-effort: a failure here must not stop the
            // window (which is already shown) from being usable.
            try
            {
                CreateTrayIcon();
            }
            catch (Exception ex)
            {
                LogStartupError(ex);
            }

            // Best-effort restore on abnormal shutdown; a hard crash is covered
            // by RecoverIfCrashed on the next start.
            AppDomain.CurrentDomain.ProcessExit += (_, _) => Cleanup();
        }
        catch (Exception ex)
        {
            LogStartupError(ex);
            throw;
        }
    }

    /// <summary>Writes a startup failure to %LOCALAPPDATA%\Relay\startup-error.log; never throws.</summary>
    private static void LogStartupError(Exception ex)
    {
        try
        {
            var dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Relay");
            Directory.CreateDirectory(dir);
            File.WriteAllText(Path.Combine(dir, "startup-error.log"), $"{DateTime.Now:o}\n{ex}");
        }
        catch
        {
            // logging must never throw
        }
    }

    /// <summary>
    /// Loads the tray icon from the app folder by absolute path. Unpackaged apps
    /// cannot resolve ms-appx:// image URIs reliably (it throws a stowed COM
    /// E_FAIL at startup), so we never use ms-appx here.
    /// </summary>
    private static BitmapImage? LoadTrayIconSource()
    {
        try
        {
            var path = Path.Combine(AppContext.BaseDirectory, "Assets", "TrayIcon.ico");
            return File.Exists(path) ? new BitmapImage(new Uri(path)) : null;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// Builds the tray icon and its menu.
    ///
    /// Two things here are load-bearing, and both were learned from issue #18
    /// and the field reports that followed it:
    ///
    /// 1. The icon is added to the window's visual tree. A <c>TaskbarIcon</c> is
    ///    a FrameworkElement; created free-floating it has no XamlRoot, and the
    ///    flyout it hosts has no live XAML island to route input through. The
    ///    library's own WinUI sample parents it and sets ContextMenuMode; so do
    ///    we.
    /// 2. Nothing in a menu command may await an unbounded operation. Exit used
    ///    to `await DisconnectAsync()` first, so any hang anywhere below it —
    ///    say a tunnel handshake blocking on a pipe read while holding the
    ///    session lock — left the user with a menu that opened, accepted the
    ///    click, and did nothing, forever. Every command is now bounded, and
    ///    Exit has a backstop that cannot be blocked by managed code at all.
    /// </summary>
    private void CreateTrayIcon()
    {
        var open = new MenuFlyoutItem { Text = Strings.Get("TrayOpen") };
        open.Click += (_, _) => _window?.ShowNearTray();

        var disconnect = new MenuFlyoutItem { Text = Strings.Get("Disconnect") };
        disconnect.Click += (_, _) => _ = DisconnectFromTrayAsync();

        var exit = new MenuFlyoutItem { Text = Strings.Get("TrayExit") };
        exit.Click += (_, _) => _ = ExitFromTrayAsync();

        var menu = new MenuFlyout();
        menu.Items.Add(open);
        menu.Items.Add(disconnect);
        menu.Items.Add(new MenuFlyoutSeparator());
        menu.Items.Add(exit);

        var openCommand = new XamlUICommand();
        openCommand.ExecuteRequested += (_, _) => _window?.ShowNearTray();

        _tray = new TaskbarIcon
        {
            ToolTipText = Strings.Get("AppName"),
            ContextFlyout = menu,
            LeftClickCommand = openCommand,
            NoLeftClickDelay = true,
            IconSource = LoadTrayIconSource(),
            // Give the menu a real window of its own to live in. The default
            // mode assumes a hosting XAML island that an unpackaged tray app
            // does not otherwise have.
            ContextMenuMode = ContextMenuMode.SecondWindow,
        };

        // Parent it, so it has a XamlRoot. It draws nothing and takes no space.
        _window?.HostTrayIcon(_tray);

        // enablesEfficiencyMode defaults to true and puts the whole process into
        // EcoQoS — idle priority — which is not what an app forwarding a phone's
        // traffic wants.
        _tray.ForceCreate(enablesEfficiencyMode: false);

        // Keep Relay current. UpdateCheck and UpdateInstaller existed and were
        // tested for three releases with nothing calling either of them, so a
        // Windows user stayed on whatever they first installed. The service
        // waits for an idle moment before replacing the app, because the
        // installer stops Relay and a live tunnel would go with it.
        _updates = new UpdateService(
            AppVersion.Current,
            () => AppController.Instance.StateName,
            (notice, version) =>
            {
                var message = notice switch
                {
                    UpdateNotice.Installing => Strings.Format("NotifyUpdateInstalling", version),
                    UpdateNotice.Refused => Strings.Get("NotifyUpdateRefused"),
                    _ => Strings.Format("NotifyUpdateBody", version),
                };
                LocalLog.Add($"Update {notice}: {version}");
                _window?.DispatcherQueue.TryEnqueue(() =>
                {
                    try
                    {
                        _tray?.ShowNotification(
                            title: Strings.Get("NotifyUpdateTitle"), message: message,
                            icon: H.NotifyIcon.Core.NotificationIcon.Info);
                    }
                    catch (Exception)
                    {
                        // A toast is a courtesy; notifications being off is not
                        // a failure worth surfacing.
                    }
                });
            });
        _updates.Start();

        var previousState = AppController.Instance.StateName;
        AppController.Instance.StateChanged += () =>
        {
            var state = AppController.Instance.StateName;
            var suffix = state switch
            {
                "Connected" => $" — {Strings.Get("StatusConnected")}",
                "Preparing" or "Advertising" => $" — {Strings.Get("StatusConnecting")}",
                _ => string.Empty,
            };
            // Only the moment it becomes true, never on every state push:
            // Connected re-fires as traffic counters change, and a notification
            // per byte would be its own kind of broken.
            var justConnected = state == "Connected" && previousState != "Connected";
            previousState = state;

            _window?.DispatcherQueue.TryEnqueue(() =>
            {
                if (_tray is null) return;
                _tray.ToolTipText = Strings.Get("AppName") + suffix;
                if (!justConnected) return;
                // The window hides itself, and the tray icon usually lives in
                // the overflow, so without this the successful moment is the one
                // moment Relay says nothing at all.
                try
                {
                    _tray.ShowNotification(
                        title: Strings.Get("NotifyConnectedTitle"),
                        message: Strings.Get("NotifyConnectedBody"),
                        icon: H.NotifyIcon.Core.NotificationIcon.Info);
                }
                catch (Exception)
                {
                    // Toasts are a courtesy. A machine with notifications
                    // disabled, or a shell that refuses them, must not turn a
                    // working tunnel into an error.
                }
            });
        };
    }

    /// <summary>
    /// How long a tray command will wait for a clean disconnect before deciding
    /// the answer does not matter. Long enough for a registry write and a
    /// read-back, short enough that nobody reaches for Task Manager.
    /// </summary>
    private static readonly TimeSpan TrayCommandTimeout = TimeSpan.FromSeconds(5);

    private static async Task<bool> DisconnectWithTimeoutAsync()
    {
        try
        {
            var work = Task.Run(() => AppController.Instance.DisconnectAsync());
            var finished = await Task.WhenAny(work, Task.Delay(TrayCommandTimeout));
            if (finished != work)
            {
                LocalLog.Add("Disconnect did not finish in time; continuing anyway");
                return false;
            }
            await work;
            return true;
        }
        catch (Exception ex)
        {
            LocalLog.Add($"Disconnect failed: {ex.Message}");
            return false;
        }
    }

    private async Task DisconnectFromTrayAsync()
    {
        if (!await DisconnectWithTimeoutAsync())
        {
            // Say so rather than leaving a menu click that visibly did nothing.
            _window?.DispatcherQueue.TryEnqueue(() => _window?.ShowNearTray());
        }
    }

    private async Task ExitFromTrayAsync()
    {
        var clean = await DisconnectWithTimeoutAsync();

        // Exiting after a failed rollback would strand the system proxy pointing
        // at a phone that is gone, with the app that could undo it now closed.
        // Show the window so the user sees the error and its retry, and stay
        // running. A *timeout* is not that case: we know nothing, and refusing
        // to close on "we know nothing" is how the app became unclosable.
        if (clean && AppController.Instance.ErrorCode == "ERR_ROLLBACK_INCOMPLETE")
        {
            _window?.DispatcherQueue.TryEnqueue(() => _window?.ShowNearTray());
            return;
        }

        _window?.DispatcherQueue.TryEnqueue(() =>
        {
            try { _updates?.Stop(); } catch { }
            try { _tray?.Dispose(); } catch { }
            // Exit() works by closing the app's windows, so the popover has to
            // stop cancelling its own close first (issue #18).
            if (_window is not null)
            {
                _window.ExitRequested = true;
                _window.Close();
            }
            try { Exit(); } catch { }
        });

        // The backstop. Everything above is managed code that something else
        // can block; this is not. If the process is still alive shortly after
        // being told to leave, it leaves. "Only Task Manager could close it"
        // must never be a true sentence about this app again.
        _ = Task.Delay(TimeSpan.FromSeconds(3)).ContinueWith(_ =>
        {
            try { LocalLog.Add("Exit backstop fired"); } catch { }
            Environment.Exit(0);
        });
    }

    private static void Cleanup()
    {
        try
        {
            // Run off the UI thread: DisconnectAsync's continuation captures the
            // UI DispatcherQueue, so .Wait() on the UI thread would deadlock it.
            Task.Run(() => AppController.Instance.DisconnectAsync()).Wait(TimeSpan.FromSeconds(3));
        }
        catch (Exception)
        {
            // Never block or throw during shutdown.
        }
    }
}
