using System.Text.Json;

namespace YarnChrome;

public sealed class Bookmark
{
    public string Title { get; set; } = "";
    public string Url { get; set; } = "";
}

public sealed class AppData
{
    public List<string> Whitelist { get; set; } = new();
    public List<Bookmark> Bookmarks { get; set; } = new();
    public int RefreshIntervalSec { get; set; }
    public bool KeepScreenOn { get; set; }
}

/// <summary>白名单、书签与设置，统一存放在 %APPDATA%\YarnChrome\data.json（UTF-8）。</summary>
public static class Store
{
    private static readonly string Dir =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "YarnChrome");
    private static readonly string FilePath = Path.Combine(Dir, "data.json");
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };
    private static AppData? data;

    /// <summary>WebView2 的用户数据目录（Cookie、缓存）。</summary>
    public static string UserDataDir => Path.Combine(Dir, "webview");

    public static AppData Data => data ??= Load();

    private static AppData Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                return JsonSerializer.Deserialize<AppData>(File.ReadAllText(FilePath)) ?? new AppData();
            }
        }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException)
        {
            // 文件损坏时保留一份副本再用默认值启动，不要静默覆盖用户数据
            try { File.Copy(FilePath, FilePath + ".bad", true); } catch (IOException) { }
        }
        return new AppData();
    }

    public static void Save()
    {
        Directory.CreateDirectory(Dir);
        var tmp = FilePath + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(Data, Json));
        File.Move(tmp, FilePath, true);
    }

    public static bool HasBookmark(string? url) =>
        !string.IsNullOrEmpty(url) && Data.Bookmarks.Any(b => b.Url == url);

    public static void AddBookmark(string title, string url)
    {
        if (HasBookmark(url)) return;
        var name = string.IsNullOrWhiteSpace(title) ? (Uri.TryCreate(url, UriKind.Absolute, out var u) ? u.Host : url) : title.Trim();
        Data.Bookmarks.Add(new Bookmark { Title = name, Url = url });
        Save();
    }

    public static void RemoveBookmark(string url)
    {
        Data.Bookmarks.RemoveAll(b => b.Url == url);
        Save();
    }
}
