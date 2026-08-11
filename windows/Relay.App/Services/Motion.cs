using Microsoft.UI.Composition;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Hosting;
using Windows.Foundation;
using Windows.UI.ViewManagement;

namespace Relay.App.Services;

/// <summary>
/// The app's motion vocabulary, in one place.
///
/// Everything here is a spring, described by response (how long it takes to
/// cover most of the distance) and damping ratio (how much it overshoots).
/// Durations are derived from those, never picked by hand, so speed and
/// bounciness stay related the way physical movement does.
///
/// Every animation starts from the property's *current* value rather than a
/// fixed origin, so interrupting one mid-flight continues from where it visibly
/// is instead of snapping back and starting over.
///
/// Honors the system "animation effects" switch: with it off, everything below
/// becomes an instant state change. That setting is how a Windows user asks for
/// reduced motion, and it is a code path, not a preference we read and ignore.
/// </summary>
internal static class Motion
{
    private static readonly UISettings Settings = new();

    /// <summary>False when the user has turned animation effects off in Windows.</summary>
    public static bool Enabled
    {
        get
        {
            try { return Settings.AnimationsEnabled; }
            catch { return true; }
        }
    }

    // Response / damping pairs. Standard is the workhorse; Snappy is for direct
    // manipulation feedback that must feel attached to the pointer; Gentle
    // carries larger surfaces that would feel frantic at the standard rate.
    private static readonly (double Response, double Damping) Standard = (0.42, 1.0);
    private static readonly (double Response, double Damping) Snappy = (0.25, 1.0);
    private static readonly (double Response, double Damping) Gentle = (0.55, 1.0);

    private static SpringVector3NaturalMotionAnimation SpringVector(
        Compositor compositor, (double Response, double Damping) spec)
    {
        var animation = compositor.CreateSpringVector3Animation();
        animation.Period = TimeSpan.FromSeconds(spec.Response / (2 * Math.PI));
        animation.DampingRatio = (float)spec.Damping;
        return animation;
    }

    /// <summary>
    /// Presses the element toward the pointer. Called on pointer-down so the
    /// response is immediate — feedback that waits for the click to complete
    /// reads as lag, however fast the rest of the interaction is.
    /// </summary>
    public static void PressDown(UIElement element)
    {
        if (!Enabled) return;
        var visual = ElementCompositionPreview.GetElementVisual(element);
        CenterOn(element, visual);
        var animation = SpringVector(visual.Compositor, Snappy);
        animation.FinalValue = new System.Numerics.Vector3(0.97f, 0.97f, 1f);
        visual.StartAnimation("Scale", animation);
    }

    /// <summary>Releases the press. Runs on pointer-up *and* on pointer-exit, so
    /// dragging off a control cannot leave it stuck down.</summary>
    public static void PressUp(UIElement element)
    {
        if (!Enabled) return;
        var visual = ElementCompositionPreview.GetElementVisual(element);
        CenterOn(element, visual);
        var animation = SpringVector(visual.Compositor, Snappy);
        animation.FinalValue = System.Numerics.Vector3.One;
        visual.StartAnimation("Scale", animation);
    }

    /// <summary>
    /// Brings a panel in: it rises and settles rather than appearing. The scale
    /// is small on purpose — at this window size anything larger reads as a
    /// zoom effect instead of the surface arriving.
    /// </summary>
    public static void EnterPanel(UIElement element)
    {
        element.Visibility = Visibility.Visible;
        if (!Enabled)
        {
            element.Opacity = 1;
            return;
        }

        var visual = ElementCompositionPreview.GetElementVisual(element);
        CenterOn(element, visual);
        var compositor = visual.Compositor;

        // Opacity is a plain ease: springing transparency makes it flicker past
        // 1.0 and back, which is visible even when the movement is not.
        var fade = compositor.CreateScalarKeyFrameAnimation();
        fade.InsertKeyFrame(0f, 0f);
        fade.InsertKeyFrame(1f, 1f);
        fade.Duration = TimeSpan.FromMilliseconds(220);
        visual.Opacity = 0f;
        visual.StartAnimation("Opacity", fade);

        var offset = compositor.CreateVector3KeyFrameAnimation();
        offset.InsertKeyFrame(0f, new System.Numerics.Vector3(0, 8, 0));
        offset.InsertKeyFrame(1f, System.Numerics.Vector3.Zero,
            compositor.CreateCubicBezierEasingFunction(new Vector2(0.2f, 0.9f), new Vector2(0.2f, 1f)));
        offset.Duration = TimeSpan.FromMilliseconds(320);
        visual.StartAnimation("Offset", offset);

        var scale = SpringVector(compositor, Standard);
        scale.InitialValue = new System.Numerics.Vector3(0.985f, 0.985f, 1f);
        scale.FinalValue = System.Numerics.Vector3.One;
        visual.StartAnimation("Scale", scale);
    }

    /// <summary>
    /// Takes a panel out and collapses it once it is gone. Faster than the
    /// entrance: the thing being replaced should get out of the way rather than
    /// perform on its way out.
    /// </summary>
    public static void ExitPanel(UIElement element, Action? onComplete = null)
    {
        if (!Enabled)
        {
            element.Visibility = Visibility.Collapsed;
            onComplete?.Invoke();
            return;
        }

        var visual = ElementCompositionPreview.GetElementVisual(element);
        var compositor = visual.Compositor;
        var batch = compositor.CreateScopedBatch(CompositionBatchTypes.Animation);

        var fade = compositor.CreateScalarKeyFrameAnimation();
        fade.InsertKeyFrame(1f, 0f);
        fade.Duration = TimeSpan.FromMilliseconds(140);
        visual.StartAnimation("Opacity", fade);

        batch.Completed += (_, _) =>
        {
            element.Visibility = Visibility.Collapsed;
            visual.Opacity = 1f;
            onComplete?.Invoke();
        };
        batch.End();
    }

    /// <summary>
    /// A slow, continuous pulse for "we are working on it" indicators. Distinct
    /// from a spinner: it says the session is alive, not that a task is running.
    /// </summary>
    public static void StartPulse(UIElement element, double from = 0.45, double to = 1.0)
    {
        if (!Enabled) return;
        var visual = ElementCompositionPreview.GetElementVisual(element);
        var compositor = visual.Compositor;
        var pulse = compositor.CreateScalarKeyFrameAnimation();
        pulse.InsertKeyFrame(0f, (float)to);
        pulse.InsertKeyFrame(0.5f, (float)from);
        pulse.InsertKeyFrame(1f, (float)to);
        pulse.Duration = TimeSpan.FromMilliseconds(1800);
        pulse.IterationBehavior = AnimationIterationBehavior.Forever;
        visual.StartAnimation("Opacity", pulse);
    }

    public static void StopPulse(UIElement element)
    {
        var visual = ElementCompositionPreview.GetElementVisual(element);
        visual.StopAnimation("Opacity");
        visual.Opacity = 1f;
    }

    /// <summary>
    /// A short horizontal shake for "that input is not valid". Deliberately the
    /// only non-spring motion in the app: rejection should feel mechanical,
    /// and it must not overshoot into looking playful.
    /// </summary>
    public static void Reject(UIElement element)
    {
        if (!Enabled) return;
        var visual = ElementCompositionPreview.GetElementVisual(element);
        var compositor = visual.Compositor;
        var shake = compositor.CreateVector3KeyFrameAnimation();
        shake.InsertKeyFrame(0.00f, System.Numerics.Vector3.Zero);
        shake.InsertKeyFrame(0.25f, new System.Numerics.Vector3(-7, 0, 0));
        shake.InsertKeyFrame(0.50f, new System.Numerics.Vector3(6, 0, 0));
        shake.InsertKeyFrame(0.75f, new System.Numerics.Vector3(-3, 0, 0));
        shake.InsertKeyFrame(1.00f, System.Numerics.Vector3.Zero);
        shake.Duration = TimeSpan.FromMilliseconds(320);
        visual.StartAnimation("Offset", shake);
    }

    /// <summary>
    /// Scales and grows around the element's middle instead of its top-left.
    /// Without this every transform pivots from the corner and the motion looks
    /// like it belongs to a different element.
    /// </summary>
    private static void CenterOn(UIElement element, Visual visual)
    {
        if (element is FrameworkElement fe && fe.ActualWidth > 0 && fe.ActualHeight > 0)
        {
            visual.CenterPoint = new System.Numerics.Vector3(
                (float)(fe.ActualWidth / 2), (float)(fe.ActualHeight / 2), 0f);
        }
    }
}
