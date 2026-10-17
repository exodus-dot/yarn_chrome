using System.Globalization;

namespace YarnChrome;

/// <summary>域名白名单：按域名匹配，自动包含子域名。规则与 Android 版一致。</summary>
public static class Whitelist
{
    public enum AddResult { Added, Invalid, Exists }

    private static readonly IdnMapping Idn = new();

    /// <summary>把用户输入整理成纯域名（小写、punycode），无法识别返回 null。</summary>
    public static string? Normalize(string input)
    {
        var s = input.Trim().ToLowerInvariant();
        if (s.Length == 0) return null;
        var scheme = s.IndexOf("://", StringComparison.Ordinal);
        if (scheme >= 0) s = s[(scheme + 3)..];
        var cut = s.IndexOfAny(new[] { '/', '?', '#' });
        if (cut >= 0) s = s[..cut];
        var at = s.LastIndexOf('@');
        if (at >= 0) s = s[(at + 1)..];
        var colon = s.IndexOf(':');
        if (colon >= 0) s = s[..colon];
        if (s.StartsWith("*.", StringComparison.Ordinal)) s = s[2..];
        s = s.Trim('.');
        if (s.Length == 0 || s.Any(char.IsWhiteSpace)) return null;
        try
        {
            return Idn.GetAscii(s);
        }
        catch (ArgumentException)
        {
            return null;
        }
    }

    public static IReadOnlyList<string> Entries => Store.Data.Whitelist;

    public static AddResult Add(string input)
    {
        var domain = Normalize(input);
        if (domain == null) return AddResult.Invalid;
        if (Store.Data.Whitelist.Contains(domain)) return AddResult.Exists;
        Store.Data.Whitelist.Add(domain);
        Store.Save();
        return AddResult.Added;
    }

    public static void Remove(string domain)
    {
        Store.Data.Whitelist.Remove(domain);
        Store.Save();
    }

    /// <summary>只放行 http/https 且主机命中白名单（本域或其子域）的地址。</summary>
    public static bool IsAllowed(string? url)
    {
        if (string.IsNullOrWhiteSpace(url)) return false;
        // 反斜杠在不同解析器里含义不同，可被用来伪装主机名，一律拒绝
        if (url.Contains('\\')) return false;
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return false;
        if (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps) return false;
        var host = uri.IdnHost.ToLowerInvariant().Trim('.');
        if (host.Length == 0) return false;
        return Store.Data.Whitelist.Any(d => host == d || host.EndsWith("." + d, StringComparison.Ordinal));
    }
}
