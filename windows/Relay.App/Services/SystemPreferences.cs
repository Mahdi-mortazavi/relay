using Windows.UI.ViewManagement;

namespace Relay.App.Services;

/// <summary>
/// The accessibility switches Windows exposes, read once through here so no
/// component has to remember which WinRT surface owns which one — or has to
/// decide, on its own, what to do when reading one throws.
///
/// Both properties fail safe toward the richer presentation: if the setting
/// cannot be read we render the normal UI rather than silently degrading
/// everyone's window on the strength of an exception.
/// </summary>
internal static class SystemPreferences
{
    private static readonly UISettings Settings = new();

    /// <summary>
    /// False when the user has turned animation effects off. This is how a
    /// Windows user asks for reduced motion.
    /// </summary>
    public static bool AnimationsEnabled
    {
        get
        {
            try { return Settings.AnimationsEnabled; }
            catch { return true; }
        }
    }

    /// <summary>
    /// False when the user has turned transparency effects off (Settings →
    /// Personalisation → Colours, or the "reduce transparency" accessibility
    /// switch). Translucency is the first thing to go for anyone who finds
    /// text over a moving background hard to read.
    /// </summary>
    public static bool TransparencyEnabled
    {
        get
        {
            try { return Settings.AdvancedEffectsEnabled; }
            catch { return true; }
        }
    }
}
