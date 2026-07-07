using System.Collections.Concurrent;

namespace Relay.App.Services;

/// <summary>
/// In-memory, local-only diagnostic log (zero telemetry: never persisted, never
/// leaves the device). A bounded ring buffer surfaced in Advanced settings.
/// </summary>
public static class LocalLog
{
    private const int Capacity = 200;

    public readonly record struct Entry(double ElapsedSeconds, string Message);

    private static readonly long Start = Environment.TickCount64;
    private static readonly ConcurrentQueue<Entry> Buffer = new();

    public static event Action? Changed;

    public static void Add(string message)
    {
        Buffer.Enqueue(new Entry((Environment.TickCount64 - Start) / 1000.0, message));
        while (Buffer.Count > Capacity) Buffer.TryDequeue(out _);
        Changed?.Invoke();
    }

    public static IReadOnlyList<Entry> Snapshot() => Buffer.ToArray();

    public static void Clear()
    {
        while (Buffer.TryDequeue(out _)) { }
        Changed?.Invoke();
    }
}
