using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;

namespace NeoWindows;

public enum CodeResult { Ok, NoCode, Wrong, TooMany }

/// <summary>Remote-control switch, short-lived pairing code, pending approval and live sockets.</summary>
public class RemoteState
{
    private readonly object _l = new();
    private string? _code; private DateTime _expires; private int _fails;
    private TaskCompletionSource<bool>? _pending;
    private readonly ConcurrentDictionary<string, WebSocket> _sockets = new();

    public volatile bool RemoteEnabled = true;

    public string NewCode()
    {
        lock (_l) { _code = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6"); _expires = DateTime.UtcNow.AddMinutes(2); _fails = 0; return _code; }
    }
    public void ClearCode() { lock (_l) _code = null; }

    public CodeResult CheckCode(string? supplied)
    {
        lock (_l)
        {
            if (_code is null || DateTime.UtcNow > _expires) return CodeResult.NoCode;
            if (_fails >= 5) { _code = null; return CodeResult.TooMany; }
            var ok = supplied is not null && CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(supplied), Encoding.UTF8.GetBytes(_code));
            if (!ok) { _fails++; return CodeResult.Wrong; }
            return CodeResult.Ok;
        }
    }

    /// <summary>Second factor: the person at the PC must type y. Returns false on no, timeout, or if another request is already waiting.</summary>
    public async Task<bool?> AskApprovalAsync(string prompt, TimeSpan timeout)
    {
        var tcs = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (Interlocked.CompareExchange(ref _pending, tcs, null) != null) return null; // busy
        try
        {
            Console.WriteLine($"\n>>> {prompt}  Type y or n and press Enter:");
            var done = await Task.WhenAny(tcs.Task, Task.Delay(timeout));
            return done == tcs.Task && tcs.Task.Result;
        }
        finally { Volatile.Write(ref _pending, null); }
    }
    public bool TryResolve(bool approved) { var p = Volatile.Read(ref _pending); if (p is null) return false; p.TrySetResult(approved); return true; }

    public void Register(string id, WebSocket ws) { if (_sockets.TryRemove(id, out var old)) old.Abort(); _sockets[id] = ws; }
    public void Unregister(string id, WebSocket ws) { if (_sockets.TryGetValue(id, out var cur) && cur == ws) _sockets.TryRemove(id, out _); }
    public int LiveCount => _sockets.Count;
    public void CloseAll() { foreach (var kv in _sockets) { kv.Value.Abort(); } _sockets.Clear(); }
    public void Close(string id) { if (_sockets.TryRemove(id, out var ws)) ws.Abort(); }
}

public static class RemoteStateExt
{
    public static bool TryResolveIfAnswer(this RemoteState s, string line) =>
        (line.Equals("y", StringComparison.OrdinalIgnoreCase) && s.TryResolve(true)) ||
        (line.Equals("n", StringComparison.OrdinalIgnoreCase) && s.TryResolve(false));
}
