using System.Diagnostics;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace YarnChrome;

public sealed class MainForm : Form
{
    private const string Blank = "about:blank";

    // 页面健康检测参数，与 Android 版保持一致
    private const int WatchdogIntervalMs = 3_000;
    private const int SlowMs = 1_500;              // 单次探测超过它算一次慢
    private const int SlowStreak = 3;              // 连续慢几次触发重载
    private const int HungMs = 10_000;             // 一条探测这么久不回，判定渲染进程挂死
    private const int RecoverCooldownMs = 30_000;  // 两次恢复之间的最短间隔，避免把重页面反复重载
    private const int PostLoadQuietMs = 5_000;     // 加载完成后的宽限期
    private const int LoadGraceMs = 20_000;        // 加载中超过它仍要检测（长连接、流式页面）
    private const int RenderGoneLoopMs = 10_000;   // 渲染进程在这段时间内再次崩溃，退回首页

    // 关闭 Chromium 对后台、被遮挡窗口的节流，页面持续渲染的场景（监控大屏、数字孪生）才不会越跑越慢
    private const string BrowserArgs =
        "--disable-background-timer-throttling " +
        "--disable-renderer-backgrounding " +
        "--disable-backgrounding-occluded-windows " +
        "--disable-features=CalculateNativeWinOcclusion";

    private const uint EsContinuous = 0x80000000;
    private const uint EsSystemRequired = 0x00000001;
    private const uint EsDisplayRequired = 0x00000002;

    [DllImport("kernel32.dll")]
    private static extern uint SetThreadExecutionState(uint flags);

    private readonly TableLayoutPanel topBar = new();
    private readonly Button btnBack = new() { Text = "后退" };
    private readonly Button btnRefresh = new() { Text = "刷新" };
    private readonly TextBox addressBar = new();
    private readonly Button btnStar = new() { Text = "收藏" };
    private readonly Button btnFullscreen = new() { Text = "全屏" };
    private readonly Button btnMenu = new() { Text = "菜单" };
    private readonly Panel contentPanel = new();
    private readonly Panel homePanel = new();
    private readonly Panel homeInner = new();
    private readonly TextBox homeInput = new();
    private readonly FlowLayoutPanel tiles = new();
    private readonly Label noticeLabel = new();
    private readonly Label diagLabel = new();

    private readonly System.Windows.Forms.Timer refreshTimer = new();
    private readonly System.Windows.Forms.Timer watchdogTimer = new() { Interval = WatchdogIntervalMs };
    private readonly System.Windows.Forms.Timer noticeTimer = new() { Interval = 3_000 };
    private readonly System.Windows.Forms.Timer diagTimer = new() { Interval = 1_000 };

    private WebView2 web = new();
    private CoreWebView2Environment? env;

    // 诊断模式：开启后注入采集脚本，关闭时为 null
    private Diag? diag;
    private string? diagScriptId;
    private string? reloadReason;      // 下一次导航的触发原因，写进诊断日志

    private string? currentUrl;
    private bool pageLoading;
    private long loadStartedAt;
    private long quietUntil;
    private long lastRecoverAt;
    private long lastGoneAt;
    private int slowProbes;
    private bool probing;
    private bool rebuilding;
    private bool videoFullscreen;
    private bool fullscreenBeforeVideo;

    private bool fullscreen;
    private FormWindowState restoreState;
    private Rectangle restoreBounds;

    public MainForm()
    {
        Text = "Yarn 浏览器";
        Font = new Font("Microsoft YaHei UI", 9f);
        Size = new Size(1280, 800);
        StartPosition = FormStartPosition.CenterScreen;
        KeyPreview = true;

        BuildTopBar();
        BuildHome();

        contentPanel.Dock = DockStyle.Fill;
        web.Dock = DockStyle.Fill;
        contentPanel.Controls.Add(web);
        contentPanel.Controls.Add(homePanel);
        noticeLabel.AutoSize = true;
        noticeLabel.Visible = false;
        noticeLabel.Padding = new Padding(10, 6, 10, 6);
        noticeLabel.BackColor = Color.FromArgb(0x32, 0x33, 0x36);
        noticeLabel.ForeColor = Color.White;
        contentPanel.Controls.Add(noticeLabel);
        diagLabel.AutoSize = true;
        diagLabel.Visible = false;
        diagLabel.Padding = new Padding(10, 8, 10, 8);
        diagLabel.BackColor = Color.FromArgb(0x20, 0x21, 0x24);
        diagLabel.ForeColor = Color.FromArgb(0xE8, 0xEA, 0xED);
        diagLabel.SizeChanged += (_, _) => PositionDiag();
        contentPanel.Controls.Add(diagLabel);

        Controls.Add(contentPanel);
        Controls.Add(topBar);

        refreshTimer.Tick += (_, _) => OnRefreshTick();
        watchdogTimer.Tick += async (_, _) => await ProbeAsync();
        noticeTimer.Tick += (_, _) => { noticeTimer.Stop(); noticeLabel.Visible = false; };
        diagTimer.Tick += (_, _) => { if (diag != null) diagLabel.Text = diag.Render(); };

        contentPanel.Resize += (_, _) => { PositionNotice(); PositionDiag(); };
        homePanel.Resize += (_, _) => CenterHome();
        Load += async (_, _) => await StartAsync();
        FormClosing += (_, _) =>
        {
            refreshTimer.Stop();
            watchdogTimer.Stop();
            diagTimer.Stop();
            diag?.Dispose();
            SetThreadExecutionState(EsContinuous);
        };
    }

    // ---- 界面 ----

    private void BuildTopBar()
    {
        topBar.Dock = DockStyle.Top;
        topBar.Height = 44;
        topBar.BackColor = Color.FromArgb(0xF1, 0xF3, 0xF4);
        topBar.Padding = new Padding(4);
        topBar.ColumnCount = 6;
        topBar.RowCount = 1;
        topBar.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        for (var i = 0; i < 6; i++)
        {
            topBar.ColumnStyles.Add(i == 2 ? new ColumnStyle(SizeType.Percent, 100) : new ColumnStyle(SizeType.Absolute, 64));
        }

        foreach (var b in new[] { btnBack, btnRefresh, btnStar, btnFullscreen, btnMenu })
        {
            b.Dock = DockStyle.Fill;
            b.FlatStyle = FlatStyle.Flat;
            b.FlatAppearance.BorderSize = 0;
            b.TabStop = false;
        }
        addressBar.Anchor = AnchorStyles.Left | AnchorStyles.Right;
        addressBar.Margin = new Padding(6, 0, 6, 0);
        addressBar.BorderStyle = BorderStyle.FixedSingle;
        addressBar.PlaceholderText = "输入网址";

        topBar.Controls.Add(btnBack, 0, 0);
        topBar.Controls.Add(btnRefresh, 1, 0);
        topBar.Controls.Add(addressBar, 2, 0);
        topBar.Controls.Add(btnStar, 3, 0);
        topBar.Controls.Add(btnFullscreen, 4, 0);
        topBar.Controls.Add(btnMenu, 5, 0);

        btnBack.Click += (_, _) => { if (web.CoreWebView2?.CanGoBack == true) web.CoreWebView2.GoBack(); };
        btnRefresh.Click += (_, _) => ReloadPage();
        btnStar.Click += (_, _) => ToggleBookmark();
        btnFullscreen.Click += (_, _) => SetFullscreen(true);
        btnMenu.Click += (_, _) => ShowMenu();
        addressBar.KeyDown += (_, e) =>
        {
            if (e.KeyCode != Keys.Enter) return;
            e.SuppressKeyPress = true;
            NavigateTo(addressBar.Text);
        };
        addressBar.GotFocus += (_, _) => addressBar.SelectAll();
    }

    private void BuildHome()
    {
        homePanel.Dock = DockStyle.Fill;
        homePanel.BackColor = Color.White;
        homeInner.Width = 660;
        homeInner.Height = 520;
        homeInner.Top = 80;

        var title = new Label
        {
            Text = "Yarn 浏览器",
            Font = new Font("Microsoft YaHei UI", 24f, FontStyle.Bold),
            ForeColor = Color.FromArgb(0x1A, 0x73, 0xE8),
            AutoSize = false,
            TextAlign = ContentAlignment.MiddleCenter,
            Dock = DockStyle.Top,
            Height = 64,
        };
        homeInput.Font = new Font("Microsoft YaHei UI", 11f);
        homeInput.PlaceholderText = "输入网址";
        homeInput.Dock = DockStyle.Top;
        homeInput.KeyDown += (_, e) =>
        {
            if (e.KeyCode != Keys.Enter) return;
            e.SuppressKeyPress = true;
            var raw = homeInput.Text;
            homeInput.Clear();
            NavigateTo(raw);
        };
        var gap = new Panel { Dock = DockStyle.Top, Height = 24 };
        tiles.Dock = DockStyle.Fill;
        tiles.AutoScroll = true;

        // Dock 从后往前布局：先加 Fill，再依次加 Top，最终顺序为 标题、输入框、间隔、方格
        homeInner.Controls.Add(tiles);
        homeInner.Controls.Add(gap);
        homeInner.Controls.Add(homeInput);
        homeInner.Controls.Add(title);
        homePanel.Controls.Add(homeInner);
    }

    private void CenterHome()
    {
        homeInner.Left = Math.Max(0, (homePanel.ClientSize.Width - homeInner.Width) / 2);
        homeInner.Height = Math.Max(200, homePanel.ClientSize.Height - homeInner.Top - 20);
    }

    private void RefreshTiles()
    {
        tiles.SuspendLayout();
        tiles.Controls.Clear();
        foreach (var item in Store.Data.Bookmarks.ToList())
        {
            var b = NewTile(item.Title);
            b.Click += (_, _) => OpenUrl(item.Url);
            b.MouseUp += (_, e) =>
            {
                if (e.Button != MouseButtons.Right) return;
                var menu = new ContextMenuStrip();
                menu.Items.Add("删除快捷方式", null, (_, _) =>
                {
                    Store.RemoveBookmark(item.Url);
                    RefreshTiles();
                });
                menu.Show(b, e.Location);
            };
            tiles.Controls.Add(b);
        }
        var add = NewTile("+ 添加快捷方式");
        add.Click += (_, _) => ShowAddShortcut();
        tiles.Controls.Add(add);
        tiles.ResumeLayout();
    }

    private static Button NewTile(string text) => new()
    {
        Text = text,
        Width = 150,
        Height = 64,
        Margin = new Padding(6),
        FlatStyle = FlatStyle.Flat,
        BackColor = Color.FromArgb(0xF1, 0xF3, 0xF4),
        UseMnemonic = false,
    };

    private void ShowHome(bool show)
    {
        if (show)
        {
            RefreshTiles();
            CenterHome();
            homePanel.Visible = true;
            homePanel.BringToFront();
            noticeLabel.BringToFront();
            diagLabel.BringToFront();
            if (!addressBar.Focused) addressBar.Text = "";
        }
        else
        {
            homePanel.Visible = false;
        }
        UpdateStar();
    }

    private void ShowNotice(string text)
    {
        noticeLabel.Text = text;
        noticeLabel.Visible = true;
        PositionNotice();
        noticeLabel.BringToFront();
        noticeTimer.Stop();
        noticeTimer.Start();
    }

    private void PositionNotice() =>
        noticeLabel.Location = new Point(12, Math.Max(0, contentPanel.ClientSize.Height - noticeLabel.Height - 12));

    private void PositionDiag() =>
        diagLabel.Location = new Point(Math.Max(0, contentPanel.ClientSize.Width - diagLabel.Width - 12), 12);

    private void UpdateStar() =>
        btnStar.Text = Store.HasBookmark(currentUrl) ? "已收藏" : "收藏";

    // ---- WebView2 初始化与事件 ----

    private async Task StartAsync()
    {
        ApplyKeepAwake();
        ApplyRefreshInterval();
        ShowHome(true);
        if (!await InitWebViewAsync()) return;
        watchdogTimer.Start();
    }

    /// <summary>创建环境并初始化当前 web 控件。运行时缺失时提示并退出。</summary>
    private async Task<bool> InitWebViewAsync()
    {
        try
        {
            env ??= await CoreWebView2Environment.CreateAsync(
                null,
                Store.UserDataDir,
                new CoreWebView2EnvironmentOptions { AdditionalBrowserArguments = BrowserArgs });
            await web.EnsureCoreWebView2Async(env);
        }
        catch (WebView2RuntimeNotFoundException)
        {
            MessageBox.Show(
                this,
                "未检测到 WebView2 运行时。请先安装 Microsoft Edge WebView2 Runtime（Evergreen）后再启动。",
                Text,
                MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
            Close();
            return false;
        }
        catch (Exception ex) when (ex is COMException or ArgumentException or InvalidOperationException or IOException or UnauthorizedAccessException)
        {
            // 用户数据目录不可写、被其他版本占用等：给出原因，不要让异步事件里的异常把进程带崩
            MessageBox.Show(this, "WebView2 初始化失败：" + ex.Message, Text, MessageBoxButtons.OK, MessageBoxIcon.Error);
            Close();
            return false;
        }

        var core = web.CoreWebView2;
        core.Settings.IsStatusBarEnabled = false;
        core.Settings.IsPasswordAutosaveEnabled = false;
        core.NavigationStarting += OnNavigationStarting;
        core.NavigationCompleted += OnNavigationCompleted;
        core.SourceChanged += (_, _) =>
        {
            // 单页应用的页内跳转不会触发 NavigationStarting，这里同步当前地址，书签和恢复才不会用旧地址
            var src = core.Source;
            if (!string.IsNullOrEmpty(src) && src != Blank && currentUrl != null) currentUrl = src;
            if (!addressBar.Focused && currentUrl != null) addressBar.Text = currentUrl;
            UpdateStar();
        };
        core.NewWindowRequested += (_, e) =>
        {
            // 没有用户手势的弹窗直接丢弃；有手势的在独立小窗口里打开（像新标签），关闭后原页面照常使用
            if (!e.IsUserInitiated || (e.Uri != Blank && !Permit(e.Uri)))
            {
                e.Handled = true;
                return;
            }
            OpenPopup(e);
        };
        core.DownloadStarting += (_, e) => e.Cancel = true;
        core.ProcessFailed += (_, e) => BeginInvoke(new Action(() => OnProcessFailed(e.ProcessFailedKind)));
        core.ContainsFullScreenElementChanged += (_, _) => OnVideoFullscreenChanged(core.ContainsFullScreenElement);
        core.WebMessageReceived += (_, e) => OnWebMessage(e);
        // 页面内嵌 iframe 时，子框架的上报走各自的 Frame 事件
        core.FrameCreated += (_, e) => e.Frame.WebMessageReceived += (_, m) => OnWebMessage(m);
        using (var seekStream = Assembly.GetExecutingAssembly().GetManifestResourceStream("YarnChrome.seek.js")!)
        using (var seekReader = new StreamReader(seekStream, Encoding.UTF8))
            await core.AddScriptToExecuteOnDocumentCreatedAsync(seekReader.ReadToEnd());
        if (diag != null) diagScriptId = await core.AddScriptToExecuteOnDocumentCreatedAsync(Diag.LoadScript());
        // 焦点在网页里时，控件把快捷键转成自身的 KeyDown，Handled 会回传给 WebView2
        web.KeyDown += (_, e) =>
        {
            if (HandleShortcut(e.KeyCode)) e.Handled = true;
        };
        return true;
    }

    private void OnNavigationStarting(object? sender, CoreWebView2NavigationStartingEventArgs e)
    {
        diag?.MarkNavigation(reloadReason ?? (e.Uri == Blank ? "回到首页" : "导航"));
        reloadReason = null;
        if (e.Uri == Blank)
        {
            currentUrl = null;
            pageLoading = false;
            ShowHome(true);
            return;
        }
        if (!Permit(e.Uri))
        {
            e.Cancel = true;
            return;
        }
        currentUrl = e.Uri;
        pageLoading = true;
        loadStartedAt = Stopwatch.GetTimestamp();
        slowProbes = 0;
        ShowHome(false);
        if (!addressBar.Focused) addressBar.Text = e.Uri;
    }

    private void OnNavigationCompleted(object? sender, CoreWebView2NavigationCompletedEventArgs e)
    {
        pageLoading = false;
        // 页面刚加载完主线程本来就忙，给一段宽限期再开始计慢
        quietUntil = Stopwatch.GetTimestamp() + MsToTicks(PostLoadQuietMs);
        UpdateStar();
    }

    private async void OpenPopup(CoreWebView2NewWindowRequestedEventArgs e)
    {
        var deferral = e.GetDeferral();
        var form = new Form { Text = "弹窗", Width = 900, Height = 700, StartPosition = FormStartPosition.CenterParent, ShowInTaskbar = false };
        var view = new WebView2 { Dock = DockStyle.Fill };
        form.Controls.Add(view);
        _ = form.Handle; // 先建好窗口句柄，WebView2 才能在不显示的窗口里初始化
        try
        {
            await view.EnsureCoreWebView2Async(env);
            var popup = view.CoreWebView2;
            e.NewWindow = popup;
            // 先不显示：拿到白名单内的目标地址后才弹出，被拦截则整个关掉，原页面不受影响
            popup.NavigationStarting += (_, n) =>
            {
                if (n.Uri == Blank) return;
                if (!Permit(n.Uri))
                {
                    n.Cancel = true;
                    form.BeginInvoke(new Action(form.Close));
                    return;
                }
                if (!form.Visible) form.BeginInvoke(new Action(() => { if (!form.IsDisposed && !form.Visible) form.Show(this); }));
            };
            popup.DocumentTitleChanged += (_, _) => form.Text = popup.DocumentTitle;
            popup.WindowCloseRequested += (_, _) => form.BeginInvoke(new Action(form.Close));
            popup.DownloadStarting += (_, d) => d.Cancel = true;
            popup.NewWindowRequested += (_, n) => n.Handled = true;
            form.FormClosed += (_, _) => form.Dispose();
        }
        catch (Exception ex) when (ex is COMException or ArgumentException or InvalidOperationException)
        {
            form.Dispose();
        }
        finally
        {
            deferral.Complete();
        }
    }

    private bool Permit(string url)
    {
        if (url == Blank || Whitelist.IsAllowed(url)) return true;
        var host = Uri.TryCreate(url, UriKind.Absolute, out var u) ? u.Host : url;
        ShowNotice("已拦截：" + host);
        return false;
    }

    private static long MsToTicks(int ms) => ms * Stopwatch.Frequency / 1000;

    // ---- 页面健康检测与恢复 ----

    /// <summary>
    /// 往页面主线程投一条空脚本，返回耗时就是页面的响应延迟。
    /// 连续几次过慢，或一条探测长时间不回，说明页面已卡死或被拖垮，自动恢复。
    /// </summary>
    private async Task ProbeAsync()
    {
        if (probing || rebuilding || web.CoreWebView2 == null) return;
        var now = Stopwatch.GetTimestamp();
        var loading = pageLoading && now - loadStartedAt < MsToTicks(LoadGraceMs);
        if (loading || videoFullscreen || currentUrl == null || now < quietUntil)
        {
            slowProbes = 0;
            return;
        }

        probing = true;
        try
        {
            var started = Stopwatch.GetTimestamp();
            var script = web.CoreWebView2.ExecuteScriptAsync("1");
            var finished = await Task.WhenAny(script, Task.Delay(HungMs));
            if (finished != script)
            {
                await RecoverAsync(rebuild: true);
                return;
            }
            await script;
            var latencyMs = (Stopwatch.GetTimestamp() - started) * 1000 / Stopwatch.Frequency;
            if (latencyMs >= SlowMs)
            {
                slowProbes++;
                if (slowProbes >= SlowStreak) await RecoverAsync(rebuild: false);
            }
            else
            {
                slowProbes = 0;
            }
        }
        catch (Exception ex) when (ex is InvalidOperationException or COMException)
        {
            // 导航切换或控件重建期间脚本可能失败，下一轮再测
        }
        finally
        {
            probing = false;
        }
    }

    private async Task RecoverAsync(bool rebuild)
    {
        slowProbes = 0;
        var now = Stopwatch.GetTimestamp();
        if (lastRecoverAt != 0 && now - lastRecoverAt < MsToTicks(RecoverCooldownMs)) return;
        var url = currentUrl;
        if (url == null) return;
        lastRecoverAt = now;
        if (rebuild)
        {
            reloadReason = "卡死恢复（重建控件）";
            await RebuildWebViewAsync(url);
        }
        else
        {
            reloadReason = "卡死恢复（响应慢）";
            web.CoreWebView2.Stop();
            web.CoreWebView2.Reload();
        }
    }

    /// <summary>丢弃旧的 WebView2 控件，换全新的。Cookie 在用户数据目录里，登录状态不丢。</summary>
    private async Task RebuildWebViewAsync(string? restoreUrl)
    {
        if (rebuilding) return;
        rebuilding = true;
        try
        {
            contentPanel.Controls.Remove(web);
            web.Dispose();
            web = new WebView2 { Dock = DockStyle.Fill };
            contentPanel.Controls.Add(web);
            pageLoading = false;
            currentUrl = null;
            if (!await InitWebViewAsync()) return;
            if (restoreUrl != null && Whitelist.IsAllowed(restoreUrl))
            {
                homePanel.Visible = false;
                web.CoreWebView2.Navigate(restoreUrl);
            }
            else
            {
                ShowHome(true);
            }
            noticeLabel.BringToFront();
        }
        finally
        {
            rebuilding = false;
        }
    }

    private void OnProcessFailed(CoreWebView2ProcessFailedKind kind)
    {
        switch (kind)
        {
            case CoreWebView2ProcessFailedKind.BrowserProcessExited:
                // 整个浏览器进程没了，环境对象作废，必须重建环境和控件
                env = null;
                reloadReason = "浏览器进程退出，重建控件";
                _ = RebuildWebViewAsync(currentUrl);
                break;
            case CoreWebView2ProcessFailedKind.RenderProcessExited:
            case CoreWebView2ProcessFailedKind.RenderProcessUnresponsive:
            case CoreWebView2ProcessFailedKind.FrameRenderProcessExited:
                var now = Stopwatch.GetTimestamp();
                var looping = lastGoneAt != 0 && now - lastGoneAt < MsToTicks(RenderGoneLoopMs);
                lastGoneAt = now;
                reloadReason = looping ? "渲染进程反复崩溃，回到首页" : "渲染进程崩溃恢复";
                // 短时间内再次崩溃说明页面本身会撑爆渲染进程，退回首页避免死循环
                if (looping) web.CoreWebView2.Navigate(Blank);
                else web.CoreWebView2.Reload();
                break;
        }
    }

    // ---- 诊断模式 ----

    private async Task ToggleDiagAsync()
    {
        var core = web.CoreWebView2;
        if (core == null || rebuilding) return;
        if (diag == null)
        {
            try
            {
                diag = new Diag();
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                ShowNotice("无法创建诊断日志：" + ex.Message);
                return;
            }
            var id = await core.AddScriptToExecuteOnDocumentCreatedAsync(Diag.LoadScript());
            // 等待期间控件被重建过，新控件初始化时已经按 diag 注入，这里的 id 作废
            if (core != web.CoreWebView2) return;
            diagScriptId = id;
            diagLabel.Text = diag.Render();
            diagLabel.Visible = true;
            diagLabel.BringToFront();
            diagTimer.Start();
            ShowNotice("诊断模式已开启，日志：" + diag.LogPath);
        }
        else
        {
            if (diagScriptId != null) core.RemoveScriptToExecuteOnDocumentCreated(diagScriptId);
            diagScriptId = null;
            diagTimer.Stop();
            diagLabel.Visible = false;
            diag.Dispose();
            diag = null;
            ShowNotice("诊断模式已关闭");
        }
        // 注入脚本只对新文档生效，重新加载当前页面让开关立即生效
        if (currentUrl == null) return;
        reloadReason = diag != null ? "开启诊断" : "关闭诊断";
        core.Reload();
    }

    private void OnWebMessage(CoreWebView2WebMessageReceivedEventArgs e)
    {
        if (diag == null) return;
        string? text;
        try
        {
            text = e.TryGetWebMessageAsString();
        }
        catch (ArgumentException)
        {
            return;                     // 页面发来的不是字符串，不是诊断数据
        }
        diag.Accept(text);
    }

    // ---- 导航、书签、菜单 ----

    private void NavigateTo(string raw)
    {
        raw = raw.Trim();
        if (raw.Length == 0) return;
        var url = raw.Contains("://") ? raw : "https://" + raw;
        OpenUrl(url);
        web.Focus();
    }

    private void OpenUrl(string url)
    {
        if (web.CoreWebView2 == null) return;
        if (!Uri.TryCreate(url, UriKind.Absolute, out _)) return;
        if (Permit(url)) web.CoreWebView2.Navigate(url);
    }

    private void ReloadPage()
    {
        if (web.CoreWebView2 == null) return;
        if (currentUrl == null)
        {
            RefreshTiles();
            return;
        }
        reloadReason = "手动刷新";
        web.CoreWebView2.Reload();
    }

    private void ToggleBookmark()
    {
        var url = currentUrl;
        if (url == null) return;
        if (Store.HasBookmark(url))
        {
            Store.RemoveBookmark(url);
            ShowNotice("已取消书签");
        }
        else
        {
            Store.AddBookmark(web.CoreWebView2?.DocumentTitle ?? "", url);
            ShowNotice("已添加书签");
        }
        UpdateStar();
    }

    private void ShowAddShortcut()
    {
        var rawUrl = Prompt.Ask(this, "添加快捷方式", "网址，例如 github.com", "");
        if (string.IsNullOrWhiteSpace(rawUrl)) return;
        var raw = rawUrl.Trim();
        var url = raw.Contains("://") ? raw : "https://" + raw;
        var validScheme = url.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
                          || url.StartsWith("https://", StringComparison.OrdinalIgnoreCase);
        if (!validScheme || Whitelist.Normalize(url) == null)
        {
            ShowNotice("网址格式不正确");
            return;
        }
        var name = Prompt.Ask(this, "添加快捷方式", "名称（可不填）", "") ?? "";
        Whitelist.Add(url);
        Store.AddBookmark(name, url);
        RefreshTiles();
        ShowNotice("已添加，该域名已加入白名单");
    }

    private void ShowMenu()
    {
        var menu = new ContextMenuStrip();
        menu.Items.Add("首页", null, (_, _) =>
        {
            if (web.CoreWebView2 != null) web.CoreWebView2.Navigate(Blank);
        });
        var marks = new ToolStripMenuItem("书签");
        if (Store.Data.Bookmarks.Count == 0) marks.DropDownItems.Add(new ToolStripMenuItem("暂无书签") { Enabled = false });
        foreach (var b in Store.Data.Bookmarks.ToList())
        {
            marks.DropDownItems.Add(new ToolStripMenuItem(b.Title, null, (_, _) => OpenUrl(b.Url)) { ToolTipText = b.Url });
        }
        menu.Items.Add(marks);
        menu.Items.Add(new ToolStripMenuItem("诊断模式", null, async (_, _) => await ToggleDiagAsync())
        {
            Checked = diag != null,
            ShortcutKeyDisplayString = "Ctrl+Shift+D",
        });
        menu.Items.Add("设置", null, (_, _) => ShowSettings());
        menu.Show(btnMenu, new Point(0, btnMenu.Height));
    }

    private void ShowSettings()
    {
        using var dialog = new SettingsForm();
        dialog.ShowDialog(this);
        ApplyKeepAwake();
        ApplyRefreshInterval();
        if (currentUrl != null && !Whitelist.IsAllowed(currentUrl)) web.CoreWebView2?.Navigate(Blank);
        else if (currentUrl == null) RefreshTiles();
    }

    // ---- 定时刷新与常亮 ----

    private void ApplyRefreshInterval()
    {
        refreshTimer.Stop();
        var seconds = Store.Data.RefreshIntervalSec;
        if (seconds <= 0) return;
        refreshTimer.Interval = seconds * 1000;
        refreshTimer.Start();
    }

    private void OnRefreshTick()
    {
        // 首页、视频全屏、重建中跳过本轮
        if (currentUrl == null || videoFullscreen || rebuilding || web.CoreWebView2 == null) return;
        reloadReason = "定时刷新";
        web.CoreWebView2.Reload();
    }

    private static void ApplyKeepAwake()
    {
        var flags = EsContinuous;
        if (Store.Data.KeepScreenOn) flags |= EsDisplayRequired | EsSystemRequired;
        SetThreadExecutionState(flags);
    }

    // ---- 全屏与快捷键 ----

    private void OnVideoFullscreenChanged(bool on)
    {
        videoFullscreen = on;
        if (on)
        {
            fullscreenBeforeVideo = fullscreen;
            SetFullscreen(true);
        }
        else
        {
            SetFullscreen(fullscreenBeforeVideo);
        }
    }

    private void SetFullscreen(bool on)
    {
        if (fullscreen == on) return;
        fullscreen = on;
        if (on)
        {
            restoreState = WindowState;
            restoreBounds = WindowState == FormWindowState.Normal ? Bounds : RestoreBounds;
            topBar.Visible = false;
            // 先还原再去边框：最大化状态下改边框样式，窗口会留下一圈缝隙
            WindowState = FormWindowState.Normal;
            FormBorderStyle = FormBorderStyle.None;
            // 用整个屏幕的范围而不是最大化，这样任务栏也会被盖住
            Bounds = Screen.FromControl(this).Bounds;
        }
        else
        {
            FormBorderStyle = FormBorderStyle.Sizable;
            WindowState = restoreState;
            if (restoreState == FormWindowState.Normal) Bounds = restoreBounds;
            topBar.Visible = true;
        }
    }

    private bool HandleShortcut(Keys key)
    {
        var ctrl = (ModifierKeys & Keys.Control) != 0;
        var shift = (ModifierKeys & Keys.Shift) != 0;
        if (key == Keys.F11 || (key == Keys.F && ctrl && shift))
        {
            if (videoFullscreen) web.CoreWebView2?.ExecuteScriptAsync("document.exitFullscreen&&document.exitFullscreen()");
            else SetFullscreen(!fullscreen);
            return true;
        }
        if (key == Keys.D && ctrl && shift)
        {
            _ = ToggleDiagAsync();
            return true;
        }
        if (key == Keys.F5 || (key == Keys.R && ctrl))
        {
            ReloadPage();
            return true;
        }
        if (key == Keys.L && ctrl)
        {
            if (!fullscreen)
            {
                addressBar.Focus();
                addressBar.SelectAll();
            }
            return true;
        }
        if (key == Keys.Escape && fullscreen && !videoFullscreen)
        {
            SetFullscreen(false);
            return true;
        }
        return false;
    }

    protected override bool ProcessCmdKey(ref Message msg, Keys keyData)
    {
        // 焦点在地址栏等普通控件上时走这里；焦点在网页里时走 web.KeyDown
        if (HandleShortcut(keyData & Keys.KeyCode)) return true;
        return base.ProcessCmdKey(ref msg, keyData);
    }
}
