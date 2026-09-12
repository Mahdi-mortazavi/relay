using System.Text.Json;

namespace Relay.Core;

/// <summary>
/// A verified installer left on disk, so that a <em>later launch</em> can be
/// the one that runs it.
///
/// This exists because of a bug found on a real machine rather than in a test.
/// The laptop this was written on was running 1.9.3-test from 16 August while
/// 2.8.1 was published, and the reason was sitting in <c>%TEMP%\Relay-update</c>:
/// a 48,536,231-byte <c>Relay-Setup-x64.exe</c>, downloaded on 9 September and
/// checksum-verified — it would have been deleted otherwise — still there,
/// unrun, three days later.
///
/// <see cref="UpdateService"/> could only install inside a single run of the
/// process: start, wait two minutes, check, download, then wait for the state
/// to be Idle. Someone who opens Relay, connects straight away, and closes it
/// when they are done is never Idle during that window, and the next launch
/// began again from nothing. The installer was re-found, re-downloaded, and
/// re-abandoned, forever, and the app could not tell it had already fetched
/// the very bytes it needed.
///
/// So the fact is written down. With this record on disk, installing can happen
/// at two moments that are guaranteed to arrive — the app closing, and the app
/// starting — instead of at a coincidence.
///
/// Read and written with <see cref="JsonDocument"/> and <see cref="Utf8JsonWriter"/>
/// rather than a serializer: the file lives in a world-writable temp directory
/// and names something this app is about to execute, so every field is checked
/// explicitly and anything unexpected reads as "no pending update".
/// </summary>
/// <param name="Version">The release this installer is, e.g. "2.8.1".</param>
/// <param name="Installer">Its file name — never a path. See <see cref="Load"/>.</param>
/// <param name="Sha256">
/// The hash the release published, which the download already matched. Kept so
/// it can be matched <em>again</em> before the file is run: by then it has been
/// sitting in a temp directory across at least one reboot.
/// </param>
public sealed record PendingUpdate(string Version, string Installer, string Sha256)
{
    /// <summary>Beside the installer it describes, so both are cleaned up together.</summary>
    public const string FileName = "pending-update.json";

    /// <summary>
    /// Where downloads land. The same directory <see cref="UpdateInstaller"/>
    /// defaults to; a temp directory on purpose, so a half-finished update the
    /// app never got around to is something Windows is allowed to sweep away.
    /// </summary>
    public static string DefaultDirectory => Path.Combine(Path.GetTempPath(), "Relay-update");

    /// <summary>Records a verified download. Never throws: losing the note is not worth a crash.</summary>
    public static void Save(string directory, PendingUpdate pending)
    {
        try
        {
            Directory.CreateDirectory(directory);
            using var stream = new FileStream(
                Path.Combine(directory, FileName), FileMode.Create, FileAccess.Write, FileShare.None);
            using var writer = new Utf8JsonWriter(stream);
            writer.WriteStartObject();
            writer.WriteString("version", pending.Version);
            writer.WriteString("installer", pending.Installer);
            writer.WriteString("sha256", pending.Sha256);
            writer.WriteEndObject();
        }
        catch (Exception)
        {
            // Worst case the update is downloaded again next cycle, which is
            // where this was before the file existed.
        }
    }

    /// <summary>
    /// The recorded update, or null when there is none, the file is unreadable,
    /// or it does not say all three things.
    ///
    /// <paramref name="directory"/> plus the recorded name is a path this app
    /// will hand to <c>Process.Start</c>, so a name that is not a bare file name
    /// is rejected outright rather than combined and hoped about.
    /// </summary>
    public static PendingUpdate? Load(string directory)
    {
        try
        {
            var path = Path.Combine(directory, FileName);
            if (!File.Exists(path)) return null;

            using var document = JsonDocument.Parse(File.ReadAllText(path));
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return null;

            var version = Text(root, "version");
            var installer = Text(root, "installer");
            var sha256 = Text(root, "sha256");
            if (version is null || installer is null || sha256 is null) return null;

            // Not a path, not a traversal, not empty.
            if (installer != Path.GetFileName(installer)) return null;

            return new PendingUpdate(version, installer, sha256);
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>Forgets the record. Never throws.</summary>
    public static void Clear(string directory)
    {
        try
        {
            var path = Path.Combine(directory, FileName);
            if (File.Exists(path)) File.Delete(path);
        }
        catch (Exception)
        {
        }
    }

    private static string? Text(JsonElement root, string name) =>
        root.TryGetProperty(name, out var element) && element.ValueKind == JsonValueKind.String
        && !string.IsNullOrWhiteSpace(element.GetString())
            ? element.GetString()
            : null;
}
