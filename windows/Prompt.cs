namespace YarnChrome;

/// <summary>单行文本输入对话框。点取消返回 null。</summary>
public static class Prompt
{
    public static string? Ask(IWin32Window owner, string title, string label, string initial)
    {
        using var form = new Form
        {
            Text = title,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            StartPosition = FormStartPosition.CenterParent,
            MinimizeBox = false,
            MaximizeBox = false,
            ShowInTaskbar = false,
            ClientSize = new Size(420, 120),
            Font = new Font("Microsoft YaHei UI", 9f),
        };
        var text = new Label { Text = label, Left = 14, Top = 14, Width = 390, AutoSize = false, Height = 20 };
        var input = new TextBox { Left = 14, Top = 40, Width = 390, Text = initial };
        var ok = new Button { Text = "确定", Left = 244, Top = 78, Width = 76, DialogResult = DialogResult.OK };
        var cancel = new Button { Text = "取消", Left = 328, Top = 78, Width = 76, DialogResult = DialogResult.Cancel };
        form.Controls.AddRange(new Control[] { text, input, ok, cancel });
        form.AcceptButton = ok;
        form.CancelButton = cancel;
        return form.ShowDialog(owner) == DialogResult.OK ? input.Text : null;
    }
}
