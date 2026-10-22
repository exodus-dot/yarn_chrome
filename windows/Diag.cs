using System.Reflection;
using System.Text;
using System.Text.Json;

namespace YarnChrome;

/// <summary>
/// 诊断模式：接收注入脚本每秒上报的性能指标，汇总成浮层文字，并写入本地 CSV。
/// 只记录数值指标，不记录网址、域名和页面内容。
/// </summary>
public sealed class Diag : IDisposable
{
    private const int StaleMs = 5_000;

    private sealed class Sample
    {
        public bool Top;
        public bool Visible;
        public int Ws, Es, Msgs, Fps, LongTasks;
        public long Bytes;
        public double HandlerMs, HandlerMax, FrameGapMax, LongTaskMs, LoopLagMax;
        public double? TsLag, HeapMb;
    }

    private sealed class Entry
    {
        public string Label = "";
        public int LastSeq;
        public long LastSeen;
        public double? BaselineLag;
        public Sample Last = new();
    }

    private static readonly string LogDir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "YarnChrome", "diag");

    private readonly Dictionary<string, Entry> entries = new();
    private readonly StreamWriter log;
    private int frameCounter;

    public string LogPath { get; }

    public Diag()
    {
        Directory.CreateDirectory(LogDir);
        LogPath = Path.Combine(LogDir, $"diag-{DateTime.Now:yyyyMMdd-HHmmss}.csv");
        // 带 BOM，Excel 直接打开中文表头不乱码
        log = new StreamWriter(LogPath, false, new UTF8Encoding(true)) { AutoFlush = true };
        log.WriteLine("时间,来源,事件,WS连接,SSE连接,消息数/秒,数据量/秒,回调耗时ms/秒,单条最长ms,帧率,最长帧间隔ms,长任务数,长任务ms,事件循环延迟ms,载荷延迟ms,较打开时ms,JS堆MB,页面可见");
    }

    public static string LoadScript()
    {
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("YarnChrome.diag.js")
                           ?? throw new InvalidOperationException("缺少嵌入资源 diag.js");
        using var reader = new StreamReader(stream, Encoding.UTF8);
        return reader.ReadToEnd();
    }

    /// <summary>新导航开始：旧文档的统计作废，并在日志里记一笔，方便和刷新时间点对照。</summary>
    public void MarkNavigation(string what)
    {
        entries.Clear();
        frameCounter = 0;
        log.WriteLine($"{DateTime.Now:HH:mm:ss},,{what}" + new string(',', 15));
    }

    /// <summary>处理一条网页消息，不是诊断数据的直接忽略。</summary>
    public void Accept(string? json)
    {
        if (string.IsNullOrEmpty(json) || !json.Contains("\"yarnDiag\"")) return;
        string id;
        int seq;
        Sample s;
        try
        {
            using var doc = JsonDocument.Parse(json);
            var r = doc.RootElement;
            if (!r.TryGetProperty("yarnDiag", out _)) return;
            id = r.GetProperty("id").GetString() ?? "";
            seq = r.GetProperty("seq").GetInt32();
            s = new Sample
            {
                Top = r.GetProperty("top").GetBoolean(),
                Visible = r.GetProperty("visible").GetBoolean(),
                Ws = r.GetProperty("ws").GetInt32(),
                Es = r.GetProperty("es").GetInt32(),
                Msgs = r.GetProperty("msgs").GetInt32(),
                Bytes = r.GetProperty("bytes").GetInt64(),
                HandlerMs = r.GetProperty("handlerMs").GetDouble(),
                HandlerMax = r.GetProperty("handlerMax").GetDouble(),
                Fps = r.GetProperty("fps").GetInt32(),
                FrameGapMax = r.GetProperty("frameGapMax").GetDouble(),
                LongTasks = r.GetProperty("longTasks").GetInt32(),
                LongTaskMs = r.GetProperty("longTaskMs").GetDouble(),
                LoopLagMax = r.GetProperty("loopLagMax").GetDouble(),
                TsLag = OptDouble(r, "tsLag"),
                HeapMb = OptDouble(r, "heapMb"),
            };
        }
        catch (Exception ex) when (ex is JsonException or KeyNotFoundException or InvalidOperationException or FormatException)
        {
            return;
        }

        // 同一条消息可能同时从主控件和子框架事件到达，按序号去重
        if (!entries.TryGetValue(id, out var e))
        {
            e = new Entry { Label = s.Top ? "主页面" : $"子框架{++frameCounter}" };
            entries[id] = e;
        }
        else if (seq <= e.LastSeq)
        {
            return;
        }
        e.LastSeq = seq;
        e.LastSeen = Environment.TickCount64;
        e.Last = s;
        if (s.TsLag is { } lag && e.BaselineLag == null) e.BaselineLag = lag;
        var drift = s.TsLag is { } l && e.BaselineLag is { } b ? l - b : (double?)null;

        log.WriteLine(string.Join(',',
            DateTime.Now.ToString("HH:mm:ss"), e.Label, "",
            s.Ws, s.Es, s.Msgs, s.Bytes, F(s.HandlerMs), F(s.HandlerMax), s.Fps, F(s.FrameGapMax),
            s.LongTasks, F(s.LongTaskMs), F(s.LoopLagMax), F(s.TsLag), F(drift), F(s.HeapMb), s.Visible ? 1 : 0));
        return;
    }

    /// <summary>浮层文字。只显示最近仍在上报、且有连接或是主页面的来源。</summary>
    public string Render()
    {
        var now = Environment.TickCount64;
        foreach (var key in entries.Where(kv => now - kv.Value.LastSeen > StaleMs).Select(kv => kv.Key).ToList())
        {
            entries.Remove(key);
        }
        var sb = new StringBuilder();
        foreach (var e in entries.Values.Where(v => v.Last.Top || v.Last.Ws + v.Last.Es > 0 || v.Last.Msgs > 0))
        {
            var s = e.Last;
            if (sb.Length > 0) sb.AppendLine();
            sb.AppendLine($"[{e.Label}]{(s.Visible ? "" : " 页面不可见")}");
            sb.AppendLine($"连接  WS {s.Ws} / SSE {s.Es}    消息 {s.Msgs}/秒    {FormatBytes(s.Bytes)}/秒");
            sb.AppendLine($"回调  {F(s.HandlerMs)} ms/秒    单条最长 {F(s.HandlerMax)} ms");
            sb.AppendLine($"帧率  {s.Fps}    最长帧间隔 {F(s.FrameGapMax)} ms");
            sb.AppendLine($"长任务 {s.LongTasks} 个 / {F(s.LongTaskMs)} ms    事件循环延迟 {F(s.LoopLagMax)} ms");
            if (s.TsLag is { } lag)
            {
                var drift = e.BaselineLag is { } b ? lag - b : 0;
                sb.AppendLine($"载荷延迟 {lag / 1000:0.00} s    较打开时 {(drift >= 0 ? "+" : "")}{drift / 1000:0.00} s");
            }
            else
            {
                sb.AppendLine("载荷延迟  未识别到时间戳");
            }
            if (s.HeapMb is { } heap) sb.AppendLine($"JS 堆 {heap:0} MB");
        }
        return sb.Length == 0 ? "诊断模式：等待页面数据" : sb.ToString().TrimEnd();
    }

    public void Dispose() => log.Dispose();

    private static double? OptDouble(JsonElement r, string name) =>
        r.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number ? v.GetDouble() : null;

    private static string F(double? v) =>
        v is { } d ? d.ToString("0.#", System.Globalization.CultureInfo.InvariantCulture) : "";

    private static string FormatBytes(long n) =>
        n >= 1048576 ? $"{n / 1048576.0:0.0} MB" : n >= 1024 ? $"{n / 1024.0:0.0} KB" : $"{n} B";
}
