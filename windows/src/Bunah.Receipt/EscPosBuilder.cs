using System.Text;

namespace Bunah.Receipt;

/// <summary>ESC/POS bytes for EXP583 V2 (58mm). Latin-1 encoding.</summary>
public static class EscPosBuilder
{
    static readonly Encoding Latin1 = Encoding.GetEncoding("ISO-8859-1");

    static void Add(List<byte> b, params byte[] x) => b.AddRange(x);
    static void Text(List<byte> b, string s) => b.AddRange(Latin1.GetBytes(ReceiptTextFormatter.PrinterSafe(s)));
    static void Line(List<byte> b, string s) { Text(b, s + "\n"); }

    public static byte[] Build(Receipt r)
    {
        var b = new List<byte>();
        Add(b, 0x1B, 0x40);                       // INIT
        if (r.PrintLogo)
        {
            Add(b, 0x1B, 0x61, 0x01);             // CENTER for the logo
            Add(b, LogoRaster.Bytes);
            Line(b, "");
        }
        Add(b, 0x1B, 0x33, 0x18);                 // tighter line spacing (24 dots, default 30)
        Add(b, 0x1B, 0x61, 0x01);                 // CENTER
        Add(b, 0x1B, 0x45, 0x01);                 // store name: bold, normal size
        Line(b, ReceiptTextFormatter.PrinterSafe(r.Store.Name));
        Add(b, 0x1D, 0x21, 0x01);                 // tagline: double-height (fits 32 cols)
        Line(b, ReceiptTextFormatter.PrinterSafe(r.Store.Tagline));
        Add(b, 0x1D, 0x21, 0x00); Add(b, 0x1B, 0x45, 0x00);
        foreach (var l in ReceiptTextFormatter.Wrap(r.Store.Address, 32)) Line(b, l);
        foreach (var l in ReceiptTextFormatter.Wrap(r.Store.Hours, 32)) Line(b, l);
        foreach (var l in ReceiptTextFormatter.Wrap(r.Store.Contact, 32)) Line(b, l);

        Add(b, 0x1B, 0x61, 0x00);                 // LEFT
        Line(b, new string('-', 32));
        Line(b, ReceiptTextFormatter.Row("No. Pesanan", r.OrderNo));
        Line(b, ReceiptTextFormatter.Row("Tanggal", r.DateTimeText));
        if (!string.IsNullOrWhiteSpace(r.CustomerName))
            Line(b, ReceiptTextFormatter.Row("Nama", r.CustomerName.Trim()));
        Line(b, ReceiptTextFormatter.Row("Jenis", r.OrderType));
        if (string.Equals(r.OrderType?.Trim(), "Dine In", System.StringComparison.OrdinalIgnoreCase)
            && !string.IsNullOrWhiteSpace(r.TableNo))
            Line(b, ReceiptTextFormatter.Row("Meja", r.TableNo.Trim()));
        Line(b, new string('-', 32));
        Add(b, 0x1B, 0x45, 0x01);
        foreach (var l in ReceiptTextFormatter.TwoCol("Layanan / Produk", "Total").Split('\n')) Line(b, l);
        Add(b, 0x1B, 0x45, 0x00);
        Line(b, new string('-', 32));

        foreach (var it in r.Items)
        {
            Add(b, 0x1B, 0x45, 0x01);
            foreach (var l in ReceiptTextFormatter.TwoCol(it.Label, ReceiptTextFormatter.Rupiah(it.LineTotal)).Split('\n')) Line(b, l);
            Add(b, 0x1B, 0x45, 0x00);
            if (!string.IsNullOrWhiteSpace(it.Desc))
                foreach (var l in ReceiptTextFormatter.Wrap(it.Desc, 32)) Line(b, l);
        }

        long disc = r.Subtotal - r.GrandTotal;
        if (disc > 0) Line(b, new string('-', 32));
        if (disc > 0)
            foreach (var l in ReceiptTextFormatter.TwoCol("Subtotal", ReceiptTextFormatter.Rupiah(r.Subtotal)).Split('\n')) Line(b, l);
        if (disc > 0)
            foreach (var l in ReceiptTextFormatter.TwoCol(
                ReceiptTextFormatter.DiscountLabel(r.DiscountType, r.DiscountValue),
                ReceiptTextFormatter.RupiahMinus(disc)).Split('\n')) Line(b, l);
        Line(b, new string('-', 32));
        Add(b, 0x1B, 0x45, 0x01);
        // TOTAL AKHIR: bold, normal size (double-size overflows 32 cols).
        foreach (var l in ReceiptTextFormatter.TwoCol("TOTAL", ReceiptTextFormatter.Rupiah(r.GrandTotal)).Split('\n')) Line(b, l);
        Add(b, 0x1B, 0x45, 0x00);
        foreach (var l in ReceiptTextFormatter.TwoCol("Bayar", r.PaymentStatus).Split('\n')) Line(b, l);
        Line(b, new string('-', 32));

        Add(b, 0x1B, 0x61, 0x01); Add(b, 0x1B, 0x45, 0x01);
        foreach (var f in r.FooterLines)
            foreach (var l in ReceiptTextFormatter.Center(f).Split('\n')) Line(b, l);
        Add(b, 0x1B, 0x45, 0x00);
        Add(b, 0x1B, 0x64, 0x02);                 // feed 2
        Add(b, 0x1B, 0x32);                       // reset line spacing to default
        Add(b, 0x1D, 0x56, 0x01);                 // cut
        return b.ToArray();
    }
}
