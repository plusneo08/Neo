using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;
using NAudio.CoreAudioApi;

namespace NeoWindows;

public record Result(string Status, string Message, object? Data = null);

/// <summary>
/// The only things a paired phone can ask for. There is no shell, no arbitrary path and no arbitrary executable:
/// apps must be allow-listed in apps.json by the PC owner. Status values match the Android app's enum.
/// </summary>
public class CommandHandler
{
    private readonly Dictionary<string, string> _apps;
    private static PerformanceCounter? _cpu;

    public CommandHandler(string dataDir)
    {
        var path = Path.Combine(dataDir, "apps.json");
        if (!File.Exists(path))
            File.WriteAllText(path, JsonSerializer.Serialize(new Dictionary<string, string>
            {
                ["chrome"] = "chrome.exe", ["edge"] = "msedge.exe", ["notepad"] = "notepad.exe",
                ["calculator"] = "calc.exe", ["explorer"] = "explorer.exe",
            }, new JsonSerializerOptions { WriteIndented = true }));
        _apps = new(JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(path)) ?? new(), StringComparer.OrdinalIgnoreCase);
    }

    public Result Run(string cmd, JsonElement args)
    {
        try
        {
            return cmd switch
            {
                "ping" => new("VERIFIED", "Connected to " + Environment.MachineName + "."),
                "status" => Status(),
                "open_app" => OpenApp(Str(args, "app")),
                "media" => Media(Str(args, "action")),
                "volume" => Volume(Str(args, "action"), Int(args, "percent", -1)),
                "lock" => Lock(),
                _ => new("UNSUPPORTED", $"Unknown command '{cmd}'."),
            };
        }
        catch (Exception e) { return new("FAILED", "Failed: " + e.Message); }
    }

    private static string Str(JsonElement a, string k) =>
        a.ValueKind == JsonValueKind.Object && a.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() ?? "" : "";
    private static int Int(JsonElement a, string k, int def) =>
        a.ValueKind == JsonValueKind.Object && a.TryGetProperty(k, out var v) && v.TryGetInt32(out var i) ? i : def;

    private Result Status()
    {
        double? cpu = null;
        try
        {
            _cpu ??= new PerformanceCounter("Processor", "% Processor Time", "_Total");
            _cpu.NextValue(); Thread.Sleep(500); cpu = Math.Round(_cpu.NextValue(), 1);
        }
        catch { /* counters can be disabled; report null rather than invent a number */ }

        var mem = new Native.MEMORYSTATUSEX();
        Native.GlobalMemoryStatusEx(mem);
        var totalMb = mem.ullTotalPhys / 1048576; var freeMb = mem.ullAvailPhys / 1048576;
        var disks = DriveInfo.GetDrives().Where(d => d.DriveType == DriveType.Fixed && d.IsReady)
            .Select(d => new { name = d.Name, free_gb = Math.Round(d.AvailableFreeSpace / 1073741824.0, 1), total_gb = Math.Round(d.TotalSize / 1073741824.0, 1) }).ToList();
        int? battery = null; bool? charging = null;
        if (Native.GetSystemPowerStatus(out var ps) && ps.BatteryLifePercent != 255) { battery = ps.BatteryLifePercent; charging = ps.ACLineStatus == 1; }

        var msg = $"{Environment.MachineName}: CPU {(cpu is null ? "n/a" : cpu + "%")}, RAM {(totalMb - freeMb) / 1024.0:F1} of {totalMb / 1024.0:F1} GB used, " +
                  string.Join(", ", disks.Select(d => $"{d.name} {d.free_gb} GB free of {d.total_gb} GB")) +
                  (battery is null ? "" : $", battery {battery}%{(charging == true ? " charging" : "")}.");
        return new("VERIFIED", msg, new { pc = Environment.MachineName, cpu_percent = cpu, ram_total_mb = totalMb, ram_free_mb = freeMb, disks, battery, charging });
    }

    private Result OpenApp(string app)
    {
        if (!_apps.TryGetValue(app.Trim(), out var exe))
            return new("UNSUPPORTED", $"'{app}' is not on this PC's allow-list. Allowed: {string.Join(", ", _apps.Keys)}.");
        var name = Path.GetFileNameWithoutExtension(exe);
        var before = Process.GetProcessesByName(name).Length;
        Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true });
        for (var i = 0; i < 10; i++)
        {
            Thread.Sleep(300);
            if (Process.GetProcessesByName(name).Length > before) return new("VERIFIED", $"{app} started (a new {name} process exists).");
        }
        return Process.GetProcessesByName(name).Length > 0
            ? new("HANDED_OFF", $"Asked {app} to open; it was already running, so I can't tell if a new window appeared.")
            : new("FAILED", $"Launched {exe} but no {name} process appeared.");
    }

    private static Result Media(string action)
    {
        byte vk = action switch { "play_pause" => 0xB3, "next" => 0xB0, "previous" => 0xB1, "stop" => 0xB2, _ => 0 };
        if (vk == 0) return new("FAILED", $"Unknown media action '{action}'.");
        Native.keybd_event(vk, 0, 0, UIntPtr.Zero); Native.keybd_event(vk, 0, 2, UIntPtr.Zero);
        return new("HANDED_OFF", $"Sent the {action} media key. Windows gives no confirmation that a player reacted.");
    }

    private static Result Volume(string action, int percent)
    {
        using var en = new MMDeviceEnumerator();
        using var dev = en.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
        var v = dev.AudioEndpointVolume;
        switch (action)
        {
            case "set":
                if (percent is < 0 or > 100) return new("FAILED", "Percent must be 0-100.");
                v.MasterVolumeLevelScalar = percent / 100f;
                var got = (int)Math.Round(v.MasterVolumeLevelScalar * 100);
                return Math.Abs(got - percent) <= 1 ? new("VERIFIED", $"PC volume is now {got}%.") : new("FAILED", $"Asked for {percent}% but Windows reports {got}%.");
            case "mute": case "unmute":
                var want = action == "mute"; v.Mute = want;
                return v.Mute == want ? new("VERIFIED", want ? "PC is muted." : "PC is unmuted.") : new("FAILED", "Mute state did not change.");
            default: return new("FAILED", $"Unknown volume action '{action}'.");
        }
    }

    private static Result Lock() => Native.LockWorkStation()
        ? new("HANDED_OFF", "Lock requested. Windows accepted the request, but I can't verify the screen is locked.")
        : new("FAILED", "Windows refused the lock request.");
}

internal static class Native
{
    [DllImport("user32.dll")] public static extern bool LockWorkStation();
    [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

    [StructLayout(LayoutKind.Sequential)]
    public class MEMORYSTATUSEX
    {
        public uint dwLength = (uint)Marshal.SizeOf(typeof(MEMORYSTATUSEX));
        public uint dwMemoryLoad; public ulong ullTotalPhys, ullAvailPhys, ullTotalPageFile, ullAvailPageFile, ullTotalVirtual, ullAvailVirtual, ullAvailExtendedVirtual;
    }
    [DllImport("kernel32.dll", SetLastError = true)] public static extern bool GlobalMemoryStatusEx([In, Out] MEMORYSTATUSEX lpBuffer);

    [StructLayout(LayoutKind.Sequential)]
    public struct SYSTEM_POWER_STATUS { public byte ACLineStatus, BatteryFlag, BatteryLifePercent, SystemStatusFlag; public int BatteryLifeTime, BatteryFullLifeTime; }
    [DllImport("kernel32.dll")] public static extern bool GetSystemPowerStatus(out SYSTEM_POWER_STATUS s);
}
