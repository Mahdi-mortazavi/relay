using Xunit;

namespace Relay.App.Tests;

/// <summary>
/// Placeholder suite proving the CI unit-test pipeline runs. Phase 1 replaces
/// this with real tests driven by /shared/test-vectors.json and
/// /shared/connection-states.json.
/// </summary>
public class SkeletonTests
{
    // Mirrors "v" in /shared/qr-payload.schema.json.
    private const int SupportedQrPayloadVersion = 1;

    [Fact]
    public void Supported_QR_payload_version_is_1()
    {
        Assert.Equal(1, SupportedQrPayloadVersion);
    }
}
