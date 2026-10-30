using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;

namespace NeoWindows;

public record Device(string Id, string Name, string TokenHash, DateTime PairedUtc);

/// <summary>Paired phones. Only a SHA-256 hash of each token is stored, never the token itself.</summary>
public class DeviceStore
{
    private readonly string _path; private readonly object _l = new(); private List<Device> _devices;
    public DeviceStore(string path)
    {
        _path = path;
        _devices = File.Exists(path) ? JsonSerializer.Deserialize<List<Device>>(File.ReadAllText(path)) ?? new() : new();
    }
    public static string Hash(string token) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(token))).ToLowerInvariant();
    private void Save() => File.WriteAllText(_path, JsonSerializer.Serialize(_devices));
    public List<Device> All() { lock (_l) return _devices.ToList(); }
    public void Add(Device d) { lock (_l) { _devices.RemoveAll(x => x.Id == d.Id); _devices.Add(d); Save(); } }
    public bool Remove(string id) { lock (_l) { var n = _devices.RemoveAll(x => x.Id == id); if (n > 0) Save(); return n > 0; } }
    public bool Exists(string id) { lock (_l) return _devices.Any(x => x.Id == id); }
    public Device? FindByToken(string token)
    {
        var h = Encoding.UTF8.GetBytes(Hash(token));
        lock (_l) return _devices.FirstOrDefault(d => CryptographicOperations.FixedTimeEquals(h, Encoding.UTF8.GetBytes(d.TokenHash)));
    }
}

public class Audit
{
    private readonly string _path; private readonly object _l = new();
    public Audit(string path) => _path = path;
    public void Log(string evt, string device, string detail = "")
    {
        var line = JsonSerializer.Serialize(new { ts = DateTime.UtcNow, evt, device, detail });
        lock (_l) File.AppendAllText(_path, line + Environment.NewLine);
    }
}

/// <summary>Self-signed TLS certificate, created on first run and kept DPAPI-protected for the current Windows user.</summary>
public static class CertStore
{
    private const X509KeyStorageFlags Flags = X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable;

    public static X509Certificate2 LoadOrCreate(string path)
    {
        if (File.Exists(path))
        {
            var raw = ProtectedData.Unprotect(File.ReadAllBytes(path), null, DataProtectionScope.CurrentUser);
            return new X509Certificate2(raw, (string?)null, Flags);
        }
        using var rsa = RSA.Create(2048);
        var req = new CertificateRequest("CN=NEO-Windows", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        var san = new SubjectAlternativeNameBuilder();
        san.AddDnsName(Environment.MachineName); san.AddDnsName("localhost");
        req.CertificateExtensions.Add(san.Build());
        req.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        req.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, false));
        req.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") }, false));
        using var c = req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(5));
        var pfx = c.Export(X509ContentType.Pfx);
        File.WriteAllBytes(path, ProtectedData.Protect(pfx, null, DataProtectionScope.CurrentUser));
        return new X509Certificate2(pfx, (string?)null, Flags);
    }
}
