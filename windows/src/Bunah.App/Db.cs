using System.IO;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace Bunah.App;

public sealed class Db
{
    readonly string _path;
    public Db(string path) { _path = path; Init(); }

    static readonly string[] RetiredItems = new[]
    {
        "Nasgor Ayam",
        "Nasgor Seafood",
        "Bakmi Goreng Ayam",
        "Bakmi Goreng Seafood",
        "Cap Cay Ayam",
        "Cap Cay Seafood",
        "Omelet Sayur/Fuyunghai",
        "Kwe Tiauw Ayam",
        "Kwe Tiauw Seafood",
        "Chicken Butter Rice",
        "Ca Kangkung/Buncis Jumbo",
    };

    void SeedServices(SqliteConnection c)
    {
        // One-time cleanup of the pre-M/L catalog (v1.0/v1.1). Saved history is
        // unaffected: past items live inside transactions.items_json.
        foreach (var n in RetiredItems)
        {
            var del = new SqliteCommand("DELETE FROM services WHERE name=$n", c);
            del.Parameters.AddWithValue("$n", n);
            del.ExecuteNonQuery();
        }
        foreach (var (n, d, p) in new (string, string, long)[]
                 {
                   ("Nasgor Ayam (M)", "", 25000),
                   ("Nasgor Ayam (L)", "", 32000),
                   ("Nasgor Seafood (M)", "", 25000),
                   ("Nasgor Seafood (L)", "", 32000),
                   ("Bakmi Goreng Ayam (M)", "", 25000),
                   ("Bakmi Goreng Ayam (L)", "", 32000),
                   ("Bakmi Goreng Seafood (M)", "", 25000),
                   ("Bakmi Goreng Seafood (L)", "", 32000),
                   ("Cap Cay Ayam (M)", "", 25000),
                   ("Cap Cay Ayam (L)", "", 32000),
                   ("Cap Cay Seafood (M)", "", 25000),
                   ("Cap Cay Seafood (L)", "", 32000),
                   ("Omelet Sayur/Fuyunghai (M)", "", 25000),
                   ("Omelet Sayur/Fuyunghai (L)", "", 32000),
                   ("Kwe Tiauw Ayam (M)", "", 25000),
                   ("Kwe Tiauw Ayam (L)", "", 32000),
                   ("Kwe Tiauw Seafood (M)", "", 25000),
                   ("Kwe Tiauw Seafood (L)", "", 32000),
                   ("Chicken Butter Rice (M)", "", 25000),
                   ("Chicken Butter Rice (L)", "", 32000),
                   ("Sapo Tahu (L)", "", 32000),
                   ("Sop Ayam (M)", "", 25000),
                   ("Sop Ayam (L)", "", 32000),
                   ("Jamu BK/KA 350ml", "", 15000),
                   ("Jamu BK/KA 1 liter", "", 30000),
                   ("Teh/es teh", "", 5000),
                   ("Jeruk/es jeruk", "", 10000),
                   ("Puding", "coklat/stroberi/mangga/hazelnut/alpukat/caramel", 10000),
                   ("Snack", "Ceker/Usus/Peyek/Kacang", 25000)
                 })
        {
            var cmd = new SqliteCommand("INSERT OR IGNORE INTO services(name,desc,price) VALUES($n,$d,$p)", c);
            cmd.Parameters.AddWithValue("$n", n); cmd.Parameters.AddWithValue("$d", d);
            cmd.Parameters.AddWithValue("$p", p);
            cmd.ExecuteNonQuery();
        }
    }

    void Init()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
        using var c = new SqliteConnection($"Data Source={_path}");
        c.Open();
        new SqliteCommand("""
            CREATE TABLE IF NOT EXISTS services(name TEXT PRIMARY KEY, desc TEXT NOT NULL, price INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS transactions(
              id INTEGER PRIMARY KEY AUTOINCREMENT, order_no TEXT NOT NULL DEFAULT '',
              date TEXT NOT NULL, time TEXT NOT NULL,
              customer TEXT NOT NULL DEFAULT '', order_type TEXT NOT NULL DEFAULT 'Take Away',
              table_no TEXT NOT NULL DEFAULT '',
              discount_type TEXT NOT NULL DEFAULT '', discount_value INTEGER NOT NULL DEFAULT 0,
              items_json TEXT NOT NULL, subtotal INTEGER NOT NULL, total INTEGER NOT NULL,
              payment_status TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS settings(k TEXT PRIMARY KEY, v TEXT NOT NULL);
            """, c).ExecuteNonQuery();
        SeedServices(c);
        foreach (var (k, v) in new Dictionary<string, string> {
            ["store_name"] = "BU NAH",
            ["tagline"] = "Chinese Food Halal",
            ["address"] = "Kayu Putih II No 32, Pulo Gadung, Jaktim",
            ["hours"] = "Buka 11.00 - 20.00 WIB",
            ["contact"] = "WA 082 1134 7293",
        })
        {
            var cmd = new SqliteCommand("INSERT OR IGNORE INTO settings(k,v) VALUES($k,$v)", c);
            cmd.Parameters.AddWithValue("$k", k); cmd.Parameters.AddWithValue("$v", v);
            cmd.ExecuteNonQuery();
        }
    }

    public string Get(string k, string fb)
    {
        using var c = new SqliteConnection($"Data Source={_path}");
        c.Open();
        var cmd = new SqliteCommand("SELECT v FROM settings WHERE k=$k", c);
        cmd.Parameters.AddWithValue("$k", k);
        return cmd.ExecuteScalar()?.ToString() ?? fb;
    }

    /// <summary>Suggest the next order number: max numeric order_no today + 1, zero-padded.</summary>
    public string SuggestOrderNo(string date)
    {
        using var c = new SqliteConnection($"Data Source={_path}");
        c.Open();
        var cmd = new SqliteCommand("SELECT order_no FROM transactions WHERE date=$d", c);
        cmd.Parameters.AddWithValue("$d", date);
        int max = 0;
        using var rd = cmd.ExecuteReader();
        while (rd.Read())
        {
            var digits = new string(rd.GetString(0).Where(char.IsDigit).ToArray());
            if (int.TryParse(digits, out int n) && n > max) max = n;
        }
        return (max + 1).ToString("D3");
    }

    public sealed record TxRow(long Id, string OrderNo, string Date, string Time, string Customer,
        string OrderType, string TableNo, string DiscountType, long DiscountValue,
        string ItemsJson, long Subtotal, long Total, string Pay);

    public void InsertTx(TxRow t)
    {
        using var c = new SqliteConnection($"Data Source={_path}");
        c.Open();
        var cmd = new SqliteCommand(
            "INSERT INTO transactions(order_no,date,time,customer,order_type,table_no,discount_type,discount_value,items_json,subtotal,total,payment_status)" +
            " VALUES($o,$d,$t,$cu,$ot,$tb,$dt,$dv,$ij,$st,$to,$pa)", c);
        cmd.Parameters.AddWithValue("$o", t.OrderNo); cmd.Parameters.AddWithValue("$d", t.Date);
        cmd.Parameters.AddWithValue("$t", t.Time);
        cmd.Parameters.AddWithValue("$cu", t.Customer); cmd.Parameters.AddWithValue("$ot", t.OrderType);
        cmd.Parameters.AddWithValue("$tb", t.TableNo); cmd.Parameters.AddWithValue("$dt", t.DiscountType);
        cmd.Parameters.AddWithValue("$dv", t.DiscountValue); cmd.Parameters.AddWithValue("$ij", t.ItemsJson);
        cmd.Parameters.AddWithValue("$st", t.Subtotal); cmd.Parameters.AddWithValue("$to", t.Total);
        cmd.Parameters.AddWithValue("$pa", t.Pay);
        cmd.ExecuteNonQuery();
    }

    public List<TxRow> AllTx(int limit = 200)
    {
        var res = new List<TxRow>();
        using var c = new SqliteConnection($"Data Source={_path}");
        c.Open();
        var cmd = new SqliteCommand(
            "SELECT id,order_no,date,time,customer,order_type,table_no,discount_type,discount_value,items_json,subtotal,total,payment_status" +
            " FROM transactions ORDER BY id DESC LIMIT $n", c);
        cmd.Parameters.AddWithValue("$n", limit);
        using var rd = cmd.ExecuteReader();
        while (rd.Read()) res.Add(new TxRow(rd.GetInt64(0), rd.GetString(1), rd.GetString(2),
            rd.GetString(3), rd.GetString(4), rd.GetString(5), rd.GetString(6), rd.GetString(7),
            rd.GetInt64(8), rd.GetString(9), rd.GetInt64(10), rd.GetInt64(11), rd.GetString(12)));
        return res;
    }

    public long DailyTotal(string date)
    {
        using var c = new SqliteConnection($"Data Source={_path}");
        c.Open();
        var cmd = new SqliteCommand("SELECT COALESCE(SUM(total),0) FROM transactions WHERE date=$d", c);
        cmd.Parameters.AddWithValue("$d", date);
        return (long)(cmd.ExecuteScalar() ?? 0L);
    }

    // ---- services master ----
    public sealed record SvcRow(string Name, string Desc, long Price);

    public List<SvcRow> AllServices()
    {
        var res = new List<SvcRow>();
        using var c = new SqliteConnection($"Data Source={_path}");
        c.Open();
        var cmd = new SqliteCommand("SELECT name,desc,price FROM services ORDER BY name", c);
        using var rd = cmd.ExecuteReader();
        while (rd.Read()) res.Add(new SvcRow(rd.GetString(0), rd.GetString(1), rd.GetInt64(2)));
        return res;
    }

    public void SaveService(string name, string desc, long price)
    {
        using var c = new SqliteConnection($"Data Source={_path}");
        c.Open();
        var cmd = new SqliteCommand(
            "INSERT INTO services(name,desc,price) VALUES($n,$d,$p) " +
            "ON CONFLICT(name) DO UPDATE SET desc=$d, price=$p", c);
        cmd.Parameters.AddWithValue("$n", name);
        cmd.Parameters.AddWithValue("$d", desc);
        cmd.Parameters.AddWithValue("$p", price);
        cmd.ExecuteNonQuery();
    }

    public void DeleteService(string name)
    {
        using var c = new SqliteConnection($"Data Source={_path}");
        c.Open();
        var cmd = new SqliteCommand("DELETE FROM services WHERE name=$n", c);
        cmd.Parameters.AddWithValue("$n", name);
        cmd.ExecuteNonQuery();
    }

    public static string ToCsv(IEnumerable<TxRow> rows)
    {
        var sb = new System.Text.StringBuilder("id,order_no,date,time,customer,order_type,table_no,discount_type,discount_value,items,subtotal,total,payment\n");
        foreach (var r in rows)
            sb.Append($"{r.Id},{Csv(r.OrderNo)},{r.Date},{r.Time},{Csv(r.Customer)},{Csv(r.OrderType)},{Csv(r.TableNo)},{r.DiscountType},{r.DiscountValue},{Csv(ItemsSummary(r.ItemsJson))},{r.Subtotal},{r.Total},{Csv(r.Pay)}\n");
        return sb.ToString();
        static string Csv(string s) => s.Contains(',') || s.Contains('"') ? $"\"{s.Replace("\"", "\"\"")}\"" : s;
    }

    public static string ItemsJson(List<Receipt.ReceiptItem> items) =>
        JsonSerializer.Serialize(items.Select(i => new { i.Name, i.Desc, i.Price, i.Qty }));

    /// <summary>"Nasgor Ayam x2 @25000; ..." for CSV export.</summary>
    public static string ItemsSummary(string itemsJson)
    {
        try
        {
            var items = JsonSerializer.Deserialize<List<ReceiptJson>>(itemsJson) ?? new();
            return string.Join("; ", items.Select(i =>
                i.Qty > 1 ? $"{i.Name} x{i.Qty} @{i.Price}" : $"{i.Name} @{i.Price}"));
        }
        catch { return itemsJson ?? ""; }
    }

    private sealed record ReceiptJson(string Name, string Desc, long Price, int Qty);
}
