using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using NeoWindows;
using QRCoder;

const int Port = 8765;
Console.OutputEncoding = Encoding.UTF8;
var dataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "NEO-Windows");
Directory.CreateDirectory(dataDir);

var audit = new Audit(Path.Combine(dataDir, "audit.jsonl"));
var store = new DeviceStore(Path.Combine(dataDir, "devices.json"));
var cert = CertStore.LoadOrCreate(Path.Combine(dataDir, "server.pfx.dpapi"));
var fingerprint = Convert.ToHexString(SHA256.HashData(cert.RawData)).ToLowerInvariant();
var state = new RemoteState();
var commands = new CommandHandler(dataDir);
var json = new JsonSerializerOptions(JsonSerializerDefaults.Web);

var builder = WebApplication.CreateBuilder(args);
builder.Logging.SetMinimumLevel(LogLevel.Warning);
builder.WebHost.ConfigureKestrel(o => o.ListenAnyIP(Port, l => l.UseHttps(cert)));
var app = builder.Build();
app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(20) });

app.MapPost("/pair", async (HttpContext ctx) =>
{
    if (!state.RemoteEnabled) return Results.StatusCode(503);
    var req = await ctx.Request.ReadFromJsonAsync<PairRequest>();
    if (req is null || string.IsNullOrWhiteSpace(req.DeviceId) || string.IsNullOrWhiteSpace(req.DeviceName)) return Results.BadRequest();
    var name = new string(req.DeviceName.Where(c => !char.IsControl(c)).Take(40).ToArray());
    var ip = ctx.Connection.RemoteIpAddress?.ToString() ?? "?";
    switch (state.CheckCode(req.Code))
    {
        case CodeResult.NoCode: case CodeResult.Wrong: audit.Log("pair_bad_code", name, ip); return Results.StatusCode(401);
        case CodeResult.TooMany: audit.Log("pair_locked", name, ip); return Results.StatusCode(429);
    }
    var approved = await state.AskApprovalAsync($"Allow \"{name}\" ({ip}) to control this PC?", TimeSpan.FromSeconds(60));
    state.ClearCode(); // codes are single-use, approved or not
    if (approved is null) return Results.StatusCode(409);
    if (approved == false) { audit.Log("pair_declined", name, ip); return Results.StatusCode(403); }
    var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
    store.Add(new Device(req.DeviceId, name, DeviceStore.Hash(token), DateTime.UtcNow));
    audit.Log("paired", name, ip);
    Console.WriteLine($"Paired with \"{name}\".");
    return Results.Json(new { token, pcName = Environment.MachineName, pcId = fingerprint[..16] });
});

app.Map("/ws", async (HttpContext ctx) =>
{
    var ip = ctx.Connection.RemoteIpAddress?.ToString() ?? "?";
    if (!ctx.WebSockets.IsWebSocketRequest) { ctx.Response.StatusCode = 400; return; }
    if (!state.RemoteEnabled) { ctx.Response.StatusCode = 503; return; }
    var auth = ctx.Request.Headers.Authorization.ToString();
    var dev = auth.StartsWith("Bearer ") ? store.FindByToken(auth[7..]) : null;
    if (dev is null) { audit.Log("auth_failed", "?", ip); ctx.Response.StatusCode = 401; return; }

    using var ws = await ctx.WebSockets.AcceptWebSocketAsync();
    state.Register(dev.Id, ws);
    audit.Log("connected", dev.Name, ip);
    Console.WriteLine($"[{dev.Name}] connected");
    var buf = new byte[16 * 1024];
    try
    {
        while (ws.State == WebSocketState.Open)
        {
            var r = await ws.ReceiveAsync(buf, CancellationToken.None);
            if (r.MessageType == WebSocketMessageType.Close) break;
            if (!r.EndOfMessage) { await ws.CloseAsync(WebSocketCloseStatus.MessageTooBig, "too big", CancellationToken.None); break; }
            if (!state.RemoteEnabled || !store.Exists(dev.Id)) { await ws.CloseAsync(WebSocketCloseStatus.PolicyViolation, "revoked", CancellationToken.None); break; }

            string id = ""; Result res; var closeAfter = false; string cmd = "?";
            try
            {
                using var doc = JsonDocument.Parse(buf.AsMemory(0, r.Count));
                id = doc.RootElement.GetProperty("id").GetString() ?? "";
                cmd = doc.RootElement.GetProperty("cmd").GetString() ?? "";
                var cmdArgs = doc.RootElement.TryGetProperty("args", out var a) ? a.Clone() : default;
                if (cmd == "revoke") { store.Remove(dev.Id); res = new Result("VERIFIED", "This phone's access was revoked."); closeAfter = true; }
                else res = await Task.Run(() => commands.Run(cmd, cmdArgs)).WaitAsync(TimeSpan.FromSeconds(20));
            }
            catch (Exception e) { res = new Result("FAILED", "Bad request: " + e.Message); }

            audit.Log("command", dev.Name, $"{cmd} -> {res.Status}");
            Console.WriteLine($"[{dev.Name}] {cmd} -> {res.Status}");
            var reply = JsonSerializer.SerializeToUtf8Bytes(new { id, status = res.Status, message = res.Message, data = res.Data }, json);
            await ws.SendAsync(reply, WebSocketMessageType.Text, true, CancellationToken.None);
            if (closeAfter) { await ws.CloseAsync(WebSocketCloseStatus.NormalClosure, "revoked", CancellationToken.None); break; }
        }
    }
    catch (WebSocketException) { }
    finally { state.Unregister(dev.Id, ws); audit.Log("disconnected", dev.Name, ip); Console.WriteLine($"[{dev.Name}] disconnected"); }
});

_ = app.RunAsync();

Console.WriteLine("NEO PLUS Windows companion");
Console.WriteLine($"Listening on port {Port} (TLS). Cert fingerprint: {fingerprint}");
Console.WriteLine("Commands: pair | devices | revoke <number> | stop | resume | quit");
Console.WriteLine("Allow-listed apps live in " + Path.Combine(dataDir, "apps.json") + "\n");

while (true)
{
    var line = (Console.ReadLine() ?? "quit").Trim();
    if (state.TryResolveIfAnswer(line)) continue;
    var parts = line.Split(' ', 2, StringSplitOptions.RemoveEmptyEntries);
    switch (parts.FirstOrDefault()?.ToLowerInvariant())
    {
        case "pair": ShowPairing(); break;
        case "devices":
            var all = store.All();
            if (all.Count == 0) Console.WriteLine("No paired phones.");
            for (var i = 0; i < all.Count; i++) Console.WriteLine($"{i + 1}. {all[i].Name}  paired {all[i].PairedUtc:u}  live={(state.LiveCount > 0)}");
            break;
        case "revoke":
            var list = store.All();
            if (parts.Length == 2 && int.TryParse(parts[1], out var n) && n >= 1 && n <= list.Count)
            { store.Remove(list[n - 1].Id); state.Close(list[n - 1].Id); audit.Log("revoked", list[n - 1].Name); Console.WriteLine("Revoked " + list[n - 1].Name); }
            else Console.WriteLine("Usage: revoke <number from 'devices'>");
            break;
        case "stop": state.RemoteEnabled = false; state.CloseAll(); state.ClearCode(); audit.Log("emergency_stop", "-"); Console.WriteLine("EMERGENCY STOP: remote control disabled, all sessions closed. Type 'resume' to re-enable."); break;
        case "resume": state.RemoteEnabled = true; Console.WriteLine("Remote control enabled."); break;
        case "quit": return;
        default: Console.WriteLine("Commands: pair | devices | revoke <number> | stop | resume | quit"); break;
    }
}

void ShowPairing()
{
    if (!state.RemoteEnabled) { Console.WriteLine("Remote control is stopped. Type 'resume' first."); return; }
    var code = state.NewCode();
    var hosts = LocalAddresses();
    var host = hosts.FirstOrDefault() ?? "127.0.0.1";
    var payload = $"neoplus://pair?h={host}&p={Port}&c={code}&f={fingerprint}";
    using var gen = new QRCodeGenerator();
    // Light modules print as blocks, dark modules as spaces, which is the correct polarity on a dark console.
    Console.WriteLine(new AsciiQRCode(gen.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M)).GetGraphic(1, "  ", "██"));
    Console.WriteLine($"Scan with NEO PLUS > Devices > Scan QR. Valid for 2 minutes, single use.");
    Console.WriteLine($"Manual: address {string.Join(" or ", hosts)}  port {Port}  code {code}\nFingerprint: {fingerprint}");
    Console.WriteLine("Windows Firewall may ask to allow this app on Private networks. Allow it for LAN use.");
}

static List<string> LocalAddresses() =>
    NetworkInterface.GetAllNetworkInterfaces()
        .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType != NetworkInterfaceType.Loopback
                    && n.GetIPProperties().GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork))
        .SelectMany(n => n.GetIPProperties().UnicastAddresses)
        .Where(u => u.Address.AddressFamily == AddressFamily.InterNetwork).Select(u => u.Address.ToString()).ToList();

record PairRequest(string Code, string DeviceId, string DeviceName);
