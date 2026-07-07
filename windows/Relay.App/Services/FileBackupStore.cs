using System.Text.Json;
using Relay.Core.Proxy;

namespace Relay.App.Services;

/// <summary>
/// Durable pre-connect snapshot at %LOCALAPPDATA%\Relay\proxy-backup.json —
/// what <see cref="ProxySession.RecoverIfCrashed"/> reads after a crash.
/// </summary>
public sealed class FileBackupStore : IBackupStore
{
    private static readonly string Path = System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Relay", "proxy-backup.json");

    public ProxyBackup? Load()
    {
        try
        {
            return File.Exists(Path)
                ? JsonSerializer.Deserialize<ProxyBackup>(File.ReadAllText(Path))
                : null;
        }
        catch (Exception)
        {
            // An unreadable backup must never block startup; worst case the
            // user's proxy stays as-is and they fix it in Windows settings.
            return null;
        }
    }

    public void Save(ProxyBackup backup)
    {
        Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
        File.WriteAllText(Path, JsonSerializer.Serialize(backup));
    }

    public void Delete()
    {
        try
        {
            File.Delete(Path);
        }
        catch (IOException)
        {
            // Stale file is re-examined (and discarded) on next start.
        }
    }
}
