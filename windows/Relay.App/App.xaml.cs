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
        _singleInstance = new Mutex(initiallyOwned: true, @"Local\RelayAppSingleton", out var isFirst);
        if (!isFirst)
        {
            Exit();
            return;
        }

        // Safety invariant #2: undo a crashed session before anything else.
        AppController.Instance.RecoverIfCrashed();

        _window = new MainWindow();
        CreateTrayIcon();
        _window.ShowNearTray();

        // Best-effort restore on abnormal shutdown; a hard crash is covered
        // by RecoverIfCrashed on the next start.
        AppDomain.CurrentDomain.ProcessExit += (_, _) => Cleanup();
        UnhandledException += (_, e) =>
        {
            Cleanup();
            e.Handled = false;
        };
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
            IconSource = new BitmapImage(new Uri("ms-appx:///Assets/TrayIcon.ico")),
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
