using Relay.Core;
using Xunit;

namespace Relay.App.Tests;

public class UpdateCheckTests
{
    private static bool Newer(string latest, string current)
    {
        var l = UpdateCheck.Parse(latest);
        var c = UpdateCheck.Parse(current);
        return l is not null && c is not null && UpdateCheck.Compare(l, c) > 0;
    }

    [Theory]
    [InlineData("1.3.2", "1.3.1")]
    [InlineData("1.4.0", "1.3.9")]
    [InlineData("2.0.0", "1.99.99")]
    [InlineData("v1.3.2", "1.3.1")]
    // String comparison says "1.10.0" < "1.9.0", which would hide every update
    // after the ninth. This is the bug the class exists to avoid.
    [InlineData("1.10.0", "1.9.0")]
    [InlineData("1.3.10", "1.3.9")]
    [InlineData("1.4.0-rc1", "1.3.1")]
    public void Offers_a_newer_release(string latest, string current)
        => Assert.True(Newer(latest, current));

    [Theory]
    [InlineData("1.3.1", "1.3.1")]
    [InlineData("v1.3.1", "1.3.1")]
    [InlineData("1.3", "1.3.0")]
    [InlineData("1.3.0", "1.3.1")]
    [InlineData("0.9.9", "1.0.0")]
    [InlineData("1.9.0", "1.10.0")]
    [InlineData("1.3.1-rc1", "1.3.1")]
    public void Does_not_offer_the_build_you_have_or_an_older_one(string latest, string current)
        => Assert.False(Newer(latest, current));

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("latest")]
    [InlineData("1")]
    [InlineData("1.2.3.4.5")]
    [InlineData("1.-2.3")]
    [InlineData("x.y.z")]
    public void Unreadable_versions_parse_to_nothing(string? version)
        => Assert.Null(UpdateCheck.Parse(version));

    [Fact]
    public void A_missing_component_counts_as_zero()
    {
        Assert.Equal(0, UpdateCheck.Compare([1, 3], [1, 3, 0]));
        Assert.True(UpdateCheck.Compare([1, 3, 1], [1, 3]) > 0);
    }
}
