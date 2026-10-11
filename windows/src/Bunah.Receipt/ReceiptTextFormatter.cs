namespace Bunah.Receipt;

/// <summary>32-column text preview + shared formatting. Must stay identical to Android version.</summary>
public static class ReceiptTextFormatter
{
    public const int Width = 32;

    public static string PhoneOrDash(string p) =>
        string.IsNullOrWhiteSpace(p) ? "-" : p.Trim();

    /// <summary>DiscountType: "" = none, "%" = percent, "Rp" = nominal. Never exceeds subtotal.</summary>
    public static long DiscountAmount(long subtotal, string type, long value) =>
        type == "%" ? Math.Clamp(subtotal * Math.Clamp(value, 0, 100) / 100, 0, subtotal)
        : type == "Rp" ? Math.Clamp(value, 0, subtotal) : 0;

    public static string DiscountLabel(string type, long value) =>
        type == "%" ? $"Diskon {Math.Clamp(value, 0, 100)}%" : "Diskon";

    public static string RupiahMinus(long v) => "-" + Rupiah(v);

    public static string Rupiah(long v) =>
        "Rp " + v.ToString("#,##0", new System.Globalization.CultureInfo("id-ID")).Replace(",", ".");

    public static string Dash() => new string('-', Width);

    public static string Center(string s) => CenterW(s, Width);

    static string CenterW(string s, int w)
    {
        var lines = Wrap(PrinterSafe(s), w);
        return string.Join("\n", lines.Select(l =>
        {
            int pad = (w - l.Length) / 2;
            return new string(' ', Math.Max(0, pad)) + l;
        }));
    }

    public static string TwoCol(string left, string right)
    {
        left = PrinterSafe(left); right = PrinterSafe(right);
        if (left.Length + 1 + right.Length <= Width)
            return left + new string(' ', Width - left.Length - right.Length) + right;
        // wrap left, keep right on last line
        var words = left.Split(' ');
        var lines = new List<string>(); var cur = "";
        foreach (var w in words)
        {
            if ((cur + " " + w).Trim().Length > Width) { lines.Add(cur.Trim()); cur = w; }
            else cur += " " + w;
        }
        lines.Add(cur.Trim());
        var out_ = new List<string>();
        for (int i = 0; i < lines.Count - 1; i++) out_.Add(lines[i]);
        var last = lines[^1];
        if (last.Length + 1 + right.Length <= Width)
            out_.Add(last + new string(' ', Width - last.Length - right.Length) + right);
        else { out_.Add(last); out_.Add(right.PadLeft(Width)); }
        return string.Join("\n", out_);
    }

    public static List<string> Wrap(string s, int w)
    {
        var res = new List<string>();
        foreach (var para in s.Split('\n'))
        {
            var words = para.Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (words.Length == 0) { res.Add(""); continue; }
            var cur = "";
            foreach (var word in words)
            {
                if (word.Length > w) // hard-break long token
                {
                    if (cur.Length > 0) { res.Add(cur); cur = ""; }
                    for (int i = 0; i < word.Length; i += w) res.Add(word.Substring(i, Math.Min(w, word.Length - i)));
                }
                else if ((cur + " " + word).Trim().Length > w) { res.Add(cur.Trim()); cur = word; }
                else cur += " " + word;
            }
            if (cur.Trim().Length > 0) res.Add(cur.Trim());
        }
        return res;
    }

    public static string PrinterSafe(string s) =>
        (s ?? "").Replace("•", "*").Replace("–", "-").Replace("—", "-");

    // Labels are padded BEFORE the colon so all colons line up ("No. Pesanan" is longest at 11).
    const int LabelWidth = 11;

    public static string Row(string label, string value) =>
        label.PadRight(LabelWidth) + ": " + value;

    public static string BuildPreview(Receipt r)
    {
        var b = new List<string>();
        if (r.PrintLogo) b.Add(Center("[LOGO]"));
        b.AddRange(new[]
        {
            Center(r.Store.Name),
            Center(r.Store.Tagline),
            Center(r.Store.Address),
            Center(r.Store.Hours),
            Center(r.Store.Contact),
            Dash(),
        });
        b.AddRange(new[]
        {
            Row("No. Pesanan", r.OrderNo),
            Row("Tanggal", r.DateTimeText),
        });
        if (!string.IsNullOrWhiteSpace(r.CustomerName))
            b.Add(Row("Nama", r.CustomerName.Trim()));
        b.Add(Row("Jenis", r.OrderType));
        if (string.Equals(r.OrderType?.Trim(), "Dine In", System.StringComparison.OrdinalIgnoreCase)
            && !string.IsNullOrWhiteSpace(r.TableNo))
            b.Add(Row("Meja", r.TableNo.Trim()));
        b.AddRange(new[]
        {
            Dash(),
            TwoCol("Layanan / Produk", "Total"),
            Dash(),
        });
        foreach (var it in r.Items)
        {
            b.Add(TwoCol(it.Label, Rupiah(it.LineTotal)));
            if (!string.IsNullOrWhiteSpace(it.Desc))
                foreach (var d in Wrap(it.Desc, Width)) b.Add(d);
        }
        long disc = r.Subtotal - r.GrandTotal;
        if (disc > 0) b.Add(Dash());
        if (disc > 0)
            b.Add(TwoCol("Subtotal", Rupiah(r.Subtotal)));
        if (disc > 0)
            b.Add(TwoCol(DiscountLabel(r.DiscountType, r.DiscountValue), RupiahMinus(disc)));
        b.Add(Dash());
        b.Add(TwoCol("TOTAL", Rupiah(r.GrandTotal)));
        b.Add(TwoCol("Bayar", r.PaymentStatus));
        b.Add(Dash());
        foreach (var f in r.FooterLines) b.Add(Center(f));
        return string.Join("\n", b);
    }

    public static Receipt Sample() => new(
        new StoreInfo("BU NAH", "Chinese Food Halal",
            "Kayu Putih II No 32, Pulo Gadung, Jaktim",
            "Buka 11.00 - 20.00 WIB",
            "WA 082 1134 7293"),
        "001", "10/10/2026 13:45", "Budi", "Take Away", "-",
        new List<ReceiptItem> {
            new("Nasgor Ayam", "", 25000),
            new("Kwe Tiauw Ayam", "", 25000),
            new("Es Teh", "", 5000),
        },
        "", 0, "LUNAS Qris",
        new List<string> {
            "TERIMA KASIH",
            "Order & free antar Kayu Putih",
            "*** BU NAH ***" });
}
