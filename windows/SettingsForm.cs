namespace YarnChrome;

/// <summary>白名单、定时刷新、屏幕常亮设置。</summary>
public sealed class SettingsForm : Form
{
    private const int MinSeconds = 5;
    private const int MaxSeconds = 86400;

    private readonly ListBox list = new();
    private readonly TextBox domainInput = new();
    private readonly CheckBox keepOn = new() { Text = "保持屏幕常亮（浏览器运行时不熄屏、不休眠）" };
    private readonly NumericUpDown interval = new() { Minimum = 0, Maximum = MaxSeconds, Width = 90 };

    public SettingsForm()
    {
        Text = "设置";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        StartPosition = FormStartPosition.CenterParent;
        MinimizeBox = false;
        MaximizeBox = false;
        ShowInTaskbar = false;
        ClientSize = new Size(480, 500);
        Font = new Font("Microsoft YaHei UI", 9f);

        keepOn.SetBounds(16, 16, 440, 24);
        keepOn.Checked = Store.Data.KeepScreenOn;

        var refreshLabel = new Label
        {
            Text = "定时刷新（秒，0 为关闭，5 到 86400）：",
            Left = 16,
            Top = 56,
            Width = 290,
            Height = 24,
            TextAlign = ContentAlignment.MiddleLeft,
        };
        interval.Left = 310;
        interval.Top = 56;
        interval.Value = Math.Clamp(Store.Data.RefreshIntervalSec, 0, MaxSeconds);

        var desc = new Label
        {
            Text = "仅允许访问列表内的域名及其子域名，其他页面和弹窗会被自动拦截。",
            Left = 16,
            Top = 100,
            Width = 448,
            Height = 36,
        };
        domainInput.SetBounds(16, 140, 360, 26);
        domainInput.PlaceholderText = "例如 example.com";
        var add = new Button { Text = "添加", Left = 384, Top = 139, Width = 80, Height = 28 };
        add.Click += (_, _) => AddDomain();
        domainInput.KeyDown += (_, e) =>
        {
            if (e.KeyCode != Keys.Enter) return;
            e.SuppressKeyPress = true;
            AddDomain();
        };

        list.SetBounds(16, 178, 448, 250);
        list.IntegralHeight = false;
        var remove = new Button { Text = "删除所选", Left = 16, Top = 454, Width = 96, Height = 28 };
        remove.Click += (_, _) =>
        {
            if (list.SelectedItem is not string domain) return;
            Whitelist.Remove(Whitelist.Normalize(domain) ?? domain);
            RenderList();
        };

        var close = new Button { Text = "完成", Left = 384, Top = 454, Width = 80, Height = 28, DialogResult = DialogResult.OK };
        AcceptButton = close;

        Controls.AddRange(new Control[] { keepOn, refreshLabel, interval, desc, domainInput, add, list, remove, close });
        RenderList();

        FormClosing += (_, _) =>
        {
            Store.Data.KeepScreenOn = keepOn.Checked;
            var seconds = (int)interval.Value;
            Store.Data.RefreshIntervalSec = seconds == 0 ? 0 : Math.Clamp(seconds, MinSeconds, MaxSeconds);
            Store.Save();
        };
    }

    private void AddDomain()
    {
        var text = domainInput.Text;
        if (string.IsNullOrWhiteSpace(text)) return;
        switch (Whitelist.Add(text))
        {
            case Whitelist.AddResult.Added:
                domainInput.Clear();
                RenderList();
                break;
            case Whitelist.AddResult.Invalid:
                MessageBox.Show(this, "域名格式不正确", Text, MessageBoxButtons.OK, MessageBoxIcon.Information);
                break;
            case Whitelist.AddResult.Exists:
                MessageBox.Show(this, "该域名已在白名单中", Text, MessageBoxButtons.OK, MessageBoxIcon.Information);
                break;
        }
    }

    private void RenderList()
    {
        list.Items.Clear();
        foreach (var d in Whitelist.Entries) list.Items.Add(d);
    }
}
