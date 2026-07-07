using System.Text.Json;

namespace Relay.App.Tests;

/// <summary>
/// Locates the repo-level /shared directory from the test output directory,
/// so tests always run against the single source of truth.
/// </summary>
public static class SharedContracts
{
    public static readonly string Dir = Locate();

    public static JsonDocument Json(string name) =>
        JsonDocument.Parse(File.ReadAllText(Path.Combine(Dir, name)));

    private static string Locate()
    {
        var current = new DirectoryInfo(AppContext.BaseDirectory);
        while (current is not null)
        {
            var candidate = Path.Combine(current.FullName, "shared", "test-vectors.json");
            if (File.Exists(candidate)) return Path.Combine(current.FullName, "shared");
            current = current.Parent;
        }
        throw new InvalidOperationException(
            $"Could not locate /shared above {AppContext.BaseDirectory}");
    }
}
