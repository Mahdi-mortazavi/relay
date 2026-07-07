using System.Runtime.InteropServices;
using Microsoft.Win32;
using Relay.Core.Proxy;

namespace Relay.App.Services;

/// <summary>
/// The real system proxy store: HKCU WinINet settings + change notification.
/// All transactional behavior (snapshot/verify/rollback) lives in
/// <see cref="ProxySession"/>; this class only reads and writes faithfully.
/// </summary>
public sealed class WinInetProxyStore : IProxyStore
{
    private const string KeyPath = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";

    private const int InternetOptionSettingsChanged = 39;
    private const int InternetOptionRefresh = 37;

    [DllImport("wininet.dll", SetLastError = true)]
    private static extern bool InternetSetOption(
        IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength);

    public ProxySnapshot Read()
    {
        using var key = Registry.CurrentUser.OpenSubKey(KeyPath);
        return new ProxySnapshot(
            Enabled: (key?.GetValue("ProxyEnable") as int?) == 1,
            Server: key?.GetValue("ProxyServer") as string,
            Override: key?.GetValue("ProxyOverride") as string,
            AutoConfigUrl: key?.GetValue("AutoConfigURL") as string);
    }

    public void Write(ProxySnapshot snapshot)
    {
        using var key = Registry.CurrentUser.CreateSubKey(KeyPath);
        key.SetValue("ProxyEnable", snapshot.Enabled ? 1 : 0, RegistryValueKind.DWord);
        WriteOrDelete(key, "ProxyServer", snapshot.Server);
        WriteOrDelete(key, "ProxyOverride", snapshot.Override);
        WriteOrDelete(key, "AutoConfigURL", snapshot.AutoConfigUrl);
    }

    public void NotifyChanged()
    {
        InternetSetOption(IntPtr.Zero, InternetOptionSettingsChanged, IntPtr.Zero, 0);
        InternetSetOption(IntPtr.Zero, InternetOptionRefresh, IntPtr.Zero, 0);
    }

    private static void WriteOrDelete(RegistryKey key, string name, string? value)
    {
        if (value is null)
        {
            key.DeleteValue(name, throwOnMissingValue: false);
        }
        else
        {
            key.SetValue(name, value, RegistryValueKind.String);
        }
    }
}
