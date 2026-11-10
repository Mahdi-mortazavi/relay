using System.Security.Cryptography;
using System.Text;

namespace Relay.Core;

/// <summary>
/// What the PC signs with. The one thing about this install that a phone can
/// recognise, and the only thing that can be: a name and an address are typed by
/// whoever is on the network.
/// </summary>
public interface IDeviceIdentity
{
    /// <summary>The base64 of the 65-byte uncompressed P-256 public key (<c>0x04 ‖ X ‖ Y</c>).</summary>
    string PublicKey { get; }

    /// <summary>The 64-byte <c>r ‖ s</c> signature over the phone's 32-byte challenge.</summary>
    byte[] Sign(byte[] challenge);
}

/// <summary>
/// This PC's identity for "Always allow" on the phone: a P-256 key pair made once
/// and kept. The private half never leaves this machine and is never sent; the
/// public half rides in the pairing request, and the phone challenges it to prove
/// the private half is held.
///
/// Contract: /shared/pairing-beacon.md → "Trusted computers"; ADR-0012. The
/// vectors in /shared/test-vectors.json → <c>trustedComputers</c> pin every byte,
/// and the phone's suite asserts the same ones.
///
/// <para>ECDSA on P-256 because both ends already have it: Android's minSdk is 26
/// and Ed25519 in the platform needs API 33, while .NET and every Android release
/// have P-256 with no dependency added. The signature is the fixed-width
/// <c>r ‖ s</c> (IEEE P1363), not DER, so there is one byte layout to pin.</para>
///
/// <para>The key sits in a file under the user's own profile. That is as strong as
/// DPAPI's CurrentUser scope would be — either way it is readable by that user's
/// processes — and it needs no extra package. A lost laptop is removed from the
/// phone's list; Relay has no server to revoke from.</para>
/// </summary>
public sealed class DeviceIdentity : IDeviceIdentity, IDisposable
{
    /// <summary>Domain separation: a signature made for anything else cannot be replayed here.</summary>
    public const string Domain = "relay-trust-v1\n";

    public const int KeyBytes = 65;
    public const int ChallengeBytes = 32;
    public const int ProofBytes = 64;

    private readonly ECDsa _key;
    private readonly byte[] _publicKey;

    private DeviceIdentity(ECDsa key, byte[] publicKey)
    {
        _key = key;
        _publicKey = publicKey;
    }

    /// <summary>Where this PC keeps its key: <c>%LOCALAPPDATA%\Relay\device-identity.p8</c>.</summary>
    public static string DefaultPath { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Relay", "device-identity.p8");

    private static readonly Lazy<DeviceIdentity?> ThisPc = new(() => TryLoadOrCreate(DefaultPath));

    /// <summary>
    /// This PC's identity, or null if it cannot be kept. Null is deliberate: a
    /// key made fresh on every run would let a person tap "Always allow" for a
    /// computer that will never be recognised again, so with nowhere to keep one
    /// the request carries no key and pairing works exactly as it always did.
    /// </summary>
    public static DeviceIdentity? ForThisPc => ThisPc.Value;

    /// <summary>A fresh identity that is not saved anywhere.</summary>
    public static DeviceIdentity Create()
    {
        var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        return new DeviceIdentity(key, ExportPublic(key)!);
    }

    /// <summary>
    /// The identity saved at <paramref name="path"/>, making and saving one if
    /// there is none. A file that cannot be read as a P-256 key is replaced:
    /// losing an identity costs one prompt on the phone, and carrying on with
    /// something that is not a key would cost the feature. Null when nothing can
    /// be written at all.
    /// </summary>
    public static DeviceIdentity? TryLoadOrCreate(string path)
    {
        try
        {
            if (File.Exists(path))
            {
                var loaded = TryLoad(path);
                if (loaded is not null) return loaded;
            }

            var created = Create();
            Save(path, created._key.ExportPkcs8PrivateKey());
            return created;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException
                                      or CryptographicException or NotSupportedException)
        {
            return null;
        }
    }

    private static DeviceIdentity? TryLoad(string path)
    {
        ECDsa? key = null;
        try
        {
            key = ECDsa.Create();
            key.ImportPkcs8PrivateKey(File.ReadAllBytes(path), out _);
            var publicKey = ExportPublic(key);
            if (publicKey is not null) return new DeviceIdentity(key, publicKey);
            key.Dispose();
            return null;
        }
        catch (Exception e) when (e is CryptographicException or ArgumentException or IOException)
        {
            key?.Dispose();
            return null;
        }
    }

    private static void Save(string path, byte[] pkcs8)
    {
        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrEmpty(directory)) Directory.CreateDirectory(directory);

        // Written beside the target and moved into place, so a crash mid-write
        // leaves the old key, or none, never half of one.
        var temp = path + ".tmp";
        File.WriteAllBytes(temp, pkcs8);
        if (!OperatingSystem.IsWindows())
        {
            File.SetUnixFileMode(temp, UnixFileMode.UserRead | UnixFileMode.UserWrite);
        }
        File.Move(temp, path, overwrite: true);
    }

    /// <inheritdoc />
    public string PublicKey => Convert.ToBase64String(_publicKey);

    /// <summary>The first 8 hex digits of SHA-256 of the key, as <c>E0EB-C2E6</c>: what the phone's list shows.</summary>
    public string Fingerprint => FingerprintOf(_publicKey);

    /// <inheritdoc />
    public byte[] Sign(byte[] challenge)
    {
        if (challenge.Length != ChallengeBytes)
            throw new ArgumentException($"A challenge is {ChallengeBytes} bytes.", nameof(challenge));
        return _key.SignData(
            Message(challenge, _publicKey), HashAlgorithmName.SHA256,
            DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
    }

    /// <summary>The exact bytes signed: <c>"relay-trust-v1" LF ‖ challenge ‖ key</c>.</summary>
    public static byte[] Message(byte[] challenge, byte[] key) =>
        [.. Encoding.ASCII.GetBytes(Domain), .. challenge, .. key];

    /// <summary>
    /// Whether <paramref name="proof"/> is <paramref name="key"/>'s signature over
    /// <paramref name="challenge"/>. The phone does this in production; it is here
    /// so the shared vectors can be asserted from this side too, which is what
    /// stops the two implementations drifting. False for anything malformed.
    /// </summary>
    public static bool Verify(byte[] key, byte[] challenge, byte[] proof)
    {
        try
        {
            if (key.Length != KeyBytes || key[0] != 0x04
                || challenge.Length != ChallengeBytes || proof.Length != ProofBytes)
                return false;

            var parameters = new ECParameters
            {
                Curve = ECCurve.NamedCurves.nistP256,
                Q = new ECPoint { X = key[1..33], Y = key[33..65] },
            };
            using var ecdsa = ECDsa.Create(parameters); // throws for a point off the curve
            return ecdsa.VerifyData(
                Message(challenge, key), proof, HashAlgorithmName.SHA256,
                DSASignatureFormat.IeeeP1363FixedFieldConcatenation);
        }
        catch (Exception) // PlatformNotSupportedException for an off-curve point on Windows CNG; a verifier must never throw
        {
            return false;
        }
    }

    /// <summary>A label for a person to compare, not a security boundary.</summary>
    public static string FingerprintOf(byte[] key)
    {
        var hex = Convert.ToHexString(SHA256.HashData(key));
        return hex[..4] + "-" + hex[4..8];
    }

    /// <summary>The uncompressed point, or null if this is not a 32-byte-coordinate P-256 key.</summary>
    private static byte[]? ExportPublic(ECDsa key)
    {
        var q = key.ExportParameters(false).Q;
        if (q.X is not { Length: 32 } x || q.Y is not { Length: 32 } y) return null;
        var bytes = new byte[KeyBytes];
        bytes[0] = 0x04;
        x.CopyTo(bytes, 1);
        y.CopyTo(bytes, 33);
        return bytes;
    }

    public void Dispose() => _key.Dispose();
}
