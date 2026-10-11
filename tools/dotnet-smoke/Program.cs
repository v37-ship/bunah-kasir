using System.Globalization;
using System.Text;
using Bunah.Receipt;

// Generates and verifies shared-spec/golden fixtures.
//   receipt-smoke --write      regenerate golden files (only when output legitimately changed)
//   receipt-smoke              verify current output matches golden
// Exit code 0 = match, 1 = mismatch (prints diffs), 2 = bad args.

var goldenDir = ResolveGolden();
var mode = args.Contains("--write");

var R = ReceiptTextFormatter.Sample;

var cases = new (string Name, Receipt R)[]
{
    ("promo-none", R()),
    ("promo-filled", R() with { OrderNo = "002" }),
    ("qty-multi", R() with {
        Items = R().Items.Select((i, idx) => idx == 0 ? i with { Qty = 2 } : i).ToList() }),
    ("dine-in-meja", R() with { OrderType = "Dine In", TableNo = "7" }),
    ("discount-10pct", R() with { DiscountType = "%", DiscountValue = 10 }),
    ("discount-nominal", R() with { DiscountType = "Rp", DiscountValue = 5000 }),
    ("discount-clamped", R() with { DiscountType = "%", DiscountValue = 150 }),
    ("delivery", R() with { OrderType = "Delivery", CustomerName = "", OrderNo = "012" }),
};

foreach (var (name, r) in cases)
{
    var preview = ReceiptTextFormatter.BuildPreview(r);
    var bytes = EscPosBuilder.Build(r);
    var text = $"# bytes={bytes.Length}\n{preview}\n";
    var path = Path.Combine(goldenDir, name + ".txt");

    if (mode)
    {
        Directory.CreateDirectory(goldenDir);
        File.WriteAllText(path, text);
        Console.WriteLine($"written {Path.GetFileName(path)} (bytes={bytes.Length})");
        continue;
    }

    if (!File.Exists(path)) { Console.WriteLine($"MISSING golden {name}.txt — run with --write"); return 2; }
    var expected = File.ReadAllText(path);
    if (expected != text)
    {
        Console.WriteLine($"MISMATCH {name}.txt");
        foreach (var (a, b) in expected.Split('\n').Zip(text.Split('\n')))
            if (a != b) Console.WriteLine($"  expected: '{a}'\n  actual  : '{b}'");
        return 1;
    }
}

if (!mode)
{
    // structural invariants that golden text alone can't express
    var r = ReceiptTextFormatter.Sample();
    var bytes = EscPosBuilder.Build(r);
    if (bytes[0] != 0x1B || bytes[1] != 0x40) { Console.WriteLine("missing INIT"); return 1; }
    if (!(bytes[^3] == 0x1D && bytes[^2] == 0x56 && bytes[^1] == 0x01)) { Console.WriteLine("missing CUT"); return 1; }
    if (ReceiptTextFormatter.BuildPreview(r).Split('\n').Any(l => l.Length > 32))
    { Console.WriteLine("line > 32 cols"); return 1; }
    var s = Encoding.GetEncoding("ISO-8859-1").GetString(bytes);
    if (!s.Contains("BU NAH") || !s.Contains("No. Pesanan") || !s.Contains("Nasgor Ayam"))
    { Console.WriteLine("content missing"); return 1; }
    if (!s.Contains("Take Away")) { Console.WriteLine("jenis missing"); return 1; }
    if (cases.Any(c => ReceiptTextFormatter.BuildPreview(c.R).Split('\n').Any(l => l.Length > 32)))
    { Console.WriteLine("some fixture has a line > 32 cols"); return 1; }
    Console.WriteLine($"OK: {cases.Length} golden fixtures + invariants pass");
}
return 0;

static string ResolveGolden()
{
    var dir = new DirectoryInfo(AppContext.BaseDirectory);
    while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "shared-spec", "sample-transaction.json")))
        dir = dir.Parent;
    if (dir is null) throw new InvalidOperationException("repo root not found");
    return Path.Combine(dir.FullName, "shared-spec", "golden");
}
