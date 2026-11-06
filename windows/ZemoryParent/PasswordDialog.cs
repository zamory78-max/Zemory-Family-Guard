using System;
using System.Drawing;
using System.Windows.Forms;

namespace ZemoryParent;

/// <summary>Small RTL dialogs: set / change the entry password.</summary>
internal static class PasswordDialog
{
    /// <summary>Asks for a new password twice. Returns null when cancelled / skipped.</summary>
    public static string? AskNew(IWin32Window? owner, string title, string intro, string cancelText)
    {
        using var f = BaseForm(title, 420, 290);
        var lbl = new Label { Text = intro, Left = 16, Top = 14, Width = 372, Height = 62, RightToLeft = RightToLeft.Yes };
        var t1 = new TextBox { Left = 16, Top = 82, Width = 372, UseSystemPasswordChar = true, RightToLeft = RightToLeft.Yes, PlaceholderText = "كلمة المرور الجديدة (٤ أحرف على الأقل)" };
        var t2 = new TextBox { Left = 16, Top = 118, Width = 372, UseSystemPasswordChar = true, RightToLeft = RightToLeft.Yes, PlaceholderText = "أعد كتابتها" };
        var err = new Label { Left = 16, Top = 152, Width = 372, Height = 22, ForeColor = Color.Firebrick, RightToLeft = RightToLeft.Yes };
        var ok = new Button { Text = "حفظ", Left = 300, Top = 196, Width = 88, DialogResult = DialogResult.None };
        var cancel = new Button { Text = cancelText, Left = 16, Top = 196, Width = 130, DialogResult = DialogResult.Cancel };
        string? result = null;
        ok.Click += (_, _) =>
        {
            if (t1.Text.Length < 4) { err.Text = "كلمة المرور قصيرة (٤ أحرف على الأقل)."; return; }
            if (t1.Text != t2.Text) { err.Text = "كلمتا المرور غير متطابقتين."; return; }
            result = t1.Text;
            f.DialogResult = DialogResult.OK;
        };
        f.AcceptButton = ok;
        f.CancelButton = cancel;
        f.Controls.AddRange(new Control[] { lbl, t1, t2, err, ok, cancel });
        return f.ShowDialog(owner) == DialogResult.OK ? result : null;
    }

    /// <summary>Asks for the current password (to change or remove it).</summary>
    public static bool Confirm(IWin32Window? owner, string title)
    {
        using var f = BaseForm(title, 400, 190);
        var lbl = new Label { Text = "اكتب كلمة المرور الحالية للمتابعة:", Left = 16, Top = 16, Width = 352, RightToLeft = RightToLeft.Yes };
        var t = new TextBox { Left = 16, Top = 46, Width = 352, UseSystemPasswordChar = true, RightToLeft = RightToLeft.Yes };
        var err = new Label { Left = 16, Top = 76, Width = 352, ForeColor = Color.Firebrick, RightToLeft = RightToLeft.Yes };
        var ok = new Button { Text = "متابعة", Left = 280, Top = 104, Width = 88 };
        var cancel = new Button { Text = "إلغاء", Left = 16, Top = 104, Width = 88, DialogResult = DialogResult.Cancel };
        ok.Click += (_, _) =>
        {
            if (LockStore.Verify(t.Text)) f.DialogResult = DialogResult.OK;
            else { err.Text = "كلمة المرور غير صحيحة."; t.SelectAll(); }
        };
        f.AcceptButton = ok;
        f.CancelButton = cancel;
        f.Controls.AddRange(new Control[] { lbl, t, err, ok, cancel });
        return f.ShowDialog(owner) == DialogResult.OK;
    }

    private static Form BaseForm(string title, int w, int h) => new()
    {
        Text = title,
        Width = w,
        Height = h,
        FormBorderStyle = FormBorderStyle.FixedDialog,
        MaximizeBox = false,
        MinimizeBox = false,
        ShowInTaskbar = false,
        StartPosition = FormStartPosition.CenterScreen,
        RightToLeft = RightToLeft.Yes,
        RightToLeftLayout = true,
        Font = new Font("Segoe UI", 10f),
    };
}
