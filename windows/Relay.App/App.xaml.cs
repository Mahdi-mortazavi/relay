using H.NotifyIcon;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media.Imaging;
using Relay.App.Services;

namespace Relay.App;

public partial class App : Application
{
    private static Mutex? _singleInstance;

    private TaskbarIcon? _tray;
    private MainWindow? _window;

    public App()
    {
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
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
                Exit();
                return;
            }

            // Safety invariant #2: undo a crashed session before anything else.
            AppController.Instance.RecoverIfCrashed();

            _window = new MainWindow();
            _window.ShowNearTray();
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

    private void CreateTrayIcon()
    {
        var open = new MenuFlyoutItem { Text = Strings.Get("TrayOpen") };
        open.Click += (_, _) => _window?.ShowNearTray();

        var disconnect = new MenuFlyoutItem { Text = Strings.Get("Disconnect") };
        disconnect.Click += async (_, _) => await AppController.Instance.DisconnectAsync();

        var exit = new MenuFlyoutItem { Text = Strings.Get("TrayExit") };
        exit.Click += async (_, _) =>
        {
            await AppController.Instance.DisconnectAsync();
            _tray?.Dispose();
            Exit();
        };

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
        };
        _tray.ForceCreate();

        AppController.Instance.StateChanged += () =>
        {
            var suffix = AppController.Instance.StateName switch
            {
                "Connected" => $" — {Strings.Get("StatusConnected")}",
                "Preparing" or "Advertising" => $" — {Strings.Get("StatusConnecting")}",
                _ => string.Empty,
            };
            _window?.DispatcherQueue.TryEnqueue(() =>
            {
                if (_tray is not null) _tray.ToolTipText = Strings.Get("AppName") + suffix;
            });
        };
    }

    private static void Cleanup()
    {
        try
        {
            AppController.Instance.DisconnectAsync().Wait(TimeSpan.FromSeconds(3));
        }
        catch (Exception)
        {
            // Never block or throw during shutdown.
        }
    }
}
