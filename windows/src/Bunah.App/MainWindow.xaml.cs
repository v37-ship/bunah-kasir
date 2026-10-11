using System.IO;
using System.Windows;
using System.Windows.Controls;
using Bunah.Receipt;
using ReceiptModel = Bunah.Receipt.Receipt;
using SvcRow = Bunah.App.Db.SvcRow;

namespace Bunah.App;

public partial class MainWindow : Window
{
    readonly WindowsPrinterService _printer = new();
    static readonly string DbPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
        "Bunah", "bunah.db");
    readonly Db _db = new(DbPath);
    readonly List<ReceiptItem> _cart = new();
    ReceiptModel? _current;

    public MainWindow()
    {
        InitializeComponent();
        VersionStatus.Text = $"v{AppInfo.Version}";
        PreviewKeyDown += OnKey;
        OrderNoBox.Text = _db.SuggestOrderNo(DateTime.Now.ToString("dd/MM/yyyy"));
        Loaded += OnLoaded;
    }

    /// <summary>Init runs after the window shows and never throws, so a DB/printer
    /// problem can't cause a silent force-close at startup.</summary>
    void OnLoaded(object sender, RoutedEventArgs e)
    {
        try { OnRefreshPrinters(null, null); } catch (Exception ex) { StatusText.Text = "Printer: " + ex.Message; }
        try { OnRefreshServices(); } catch (Exception ex) { SvcStatus.Text = "Database: " + ex.Message; }
        try { OnPreview(null, null); } catch (Exception ex) { StatusText.Text = "Error: " + ex.Message; }
        try { OnRefreshHistory(null, null); } catch (Exception ex) { DailyTotalText.Text = "Riwayat: " + ex.Message; }
    }

    // ---------- menu ----------
    void OnKey(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key == System.Windows.Input.Key.System &&
            (e.SystemKey == System.Windows.Input.Key.LeftAlt || e.SystemKey == System.Windows.Input.Key.RightAlt))
        {
            if (!e.IsRepeat)
                MainMenu.Visibility = MainMenu.Visibility == Visibility.Visible
                    ? Visibility.Collapsed : Visibility.Visible;
            e.Handled = true;
        }
        else if (e.Key == System.Windows.Input.Key.Escape && MainMenu.Visibility == Visibility.Visible)
        {
            MainMenu.Visibility = Visibility.Collapsed;
            e.Handled = true;
        }
    }

    void OnPrinterChanged(object? s, SelectionChangedEventArgs e)
    {
        if (PrinterBox.SelectedItem is not null)
            PrinterStatus.Text = "Printer: " + PrinterBox.SelectedItem;
    }

    void OnRefreshPrinters(object? s, RoutedEventArgs? e)
    {
        PrinterBox.ItemsSource = WindowsPrinterService.ListPrinters();
        if (PrinterBox.Items.Count == 0)
            StatusText.Text = "Printer tidak ditemukan. Install driver thermal dulu.";
    }

    // ---------- services master ----------
    List<SvcRow> _services = new();

    void OnRefreshServices()
    {
        _services = _db.AllServices();
        ServiceGrid.ItemsSource = _services;
        ApplyServiceFilter();
    }

    /// <summary>Filter the picker as you type; shows "Nama — Deskripsi — Rp harga".</summary>
    void ApplyServiceFilter()
    {
        var q = ServiceFilter.Text.Trim();
        var rows = string.IsNullOrEmpty(q)
            ? _services
            : _services.Where(s => s.Name.Contains(q, StringComparison.OrdinalIgnoreCase)
                                || s.Desc.Contains(q, StringComparison.OrdinalIgnoreCase)).ToList();
        FilteredServices = rows;
        if (FilteredServices.Count > 0) PickedService = FilteredServices[0];
    }

    List<SvcRow> FilteredServices
    {
        get => _filtered;
        set { _filtered = value; ServiceList.ItemsSource = value; }
    }
    List<SvcRow> _filtered = new();

    SvcRow? PickedService
    {
        get => _picked;
        set { _picked = value; ServiceDetail.Text = value is null ? "" : $"{value.Name} — {value.Desc} — {ReceiptTextFormatter.Rupiah(value.Price)}"; }
    }
    SvcRow? _picked;

    void OnServiceFilterChanged(object? s, System.Windows.Controls.TextChangedEventArgs e) => ApplyServiceFilter();

    void OnPickService(object? s, System.Windows.Controls.SelectionChangedEventArgs e)
    {
        if (ServiceList.SelectedItem is SvcRow row) PickedService = row;
    }

    void OnServiceSelected(object? s, SelectionChangedEventArgs e)
    {
        if (ServiceGrid.SelectedItem is not SvcRow svc) return;
        SvcName.Text = svc.Name;
        SvcDesc.Text = svc.Desc;
        SvcPrice.Text = svc.Price.ToString();
    }

    void OnSaveService(object? s, RoutedEventArgs? e)
    {
        var name = SvcName.Text.Trim();
        if (name.Length == 0) { SvcStatus.Text = "Nama layanan wajib diisi."; return; }
        if (!long.TryParse(SvcPrice.Text.Trim(), out long price))
        { SvcStatus.Text = "Harga harus angka."; return; }
        _db.SaveService(name, SvcDesc.Text.Trim(), price);
        SvcStatus.Text = $"Tersimpan: {name}";
        OnRefreshServices();
    }

    void OnDeleteService(object? s, RoutedEventArgs? e)
    {
        var name = SvcName.Text.Trim();
        if (name.Length == 0) return;
        _db.DeleteService(name);
        SvcName.Clear(); SvcDesc.Clear(); SvcPrice.Clear();
        int removed = _cart.RemoveAll(i => i.Name == name);
        SvcStatus.Text = removed > 0
            ? $"Dihapus: {name} (juga {removed} baris dari keranjang)"
            : $"Dihapus: {name}";
        OnRefreshServices();
        RefreshCart();
    }

    // ---------- cart ----------
    void OnAddToCart(object? s, RoutedEventArgs? e)
    {
        if (PickedService is not SvcRow svc) { StatusText.Text = "Pilih layanan dulu."; return; }
        if (!int.TryParse(QtyBox.Text.Trim(), out int qty) || qty < 1)
        { StatusText.Text = "Qty harus angka >= 1."; return; }
        var existing = _cart.FirstOrDefault(i => i.Name == svc.Name);
        if (existing is not null) _cart.Remove(existing);
        _cart.Add(new ReceiptItem(svc.Name, svc.Desc, svc.Price) { Qty = qty });
        RefreshCart();
        StatusText.Text = $"Ditambah: {svc.Name} x{qty}";
    }

    void OnRemoveFromCart(object? s, RoutedEventArgs? e)
    {
        if (s is Button { DataContext: ReceiptItem item }) _cart.Remove(item);
        RefreshCart();
    }

    void OnClearCart(object? s, RoutedEventArgs? e)
    {
        _cart.Clear();
        RefreshCart();
    }

    void RefreshCart()
    {
        CartGrid.ItemsSource = null;
        CartGrid.ItemsSource = _cart.ToList();
        OnPreview(null, null);
    }

    // ---------- receipt ----------
    ReceiptModel BuildReceipt()
    {
        var now = DateTime.Now;
        long.TryParse(DiscountBox.Text.Trim(), out long discVal);
        string discType = string.IsNullOrWhiteSpace(DiscountBox.Text) ? ""
            : ((ComboBoxItem)DiscountTypeBox.SelectedItem).Content.ToString()!;
        return new ReceiptModel(
            new StoreInfo("BU NAH", "Chinese Food Halal",
                "Kayu Putih II No 32, Pulo Gadung, Jaktim",
                "Buka 11.00 - 20.00 WIB",
                "WA 082 1134 7293"),
            OrderNoBox.Text.Trim(), now.ToString("dd/MM/yyyy HH:mm"),
            CustomerBox.Text.Trim(),
            ((System.Windows.Controls.ComboBoxItem)OrderTypeBox.SelectedItem).Content.ToString()!,
            TableBox.Text.Trim(),
            _cart.ToList(),
            discType, discVal,
            ((ComboBoxItem)PayBox.SelectedItem).Content.ToString()!,
            new List<string> {
                "TERIMA KASIH",
                "Order & free antar Kayu Putih",
                "*** BU NAH ***" },
            PrintLogo: LogoBox.IsChecked == true);
    }

    void OnPreview(object? s, RoutedEventArgs? e)
    {
        try
        {
            _current = BuildReceipt();
            PreviewText.Text = ReceiptTextFormatter.BuildPreview(_current);
            TotalText.Text = "TOTAL: " + ReceiptTextFormatter.Rupiah(_current.GrandTotal);
        }
        catch (Exception ex) { StatusText.Text = "Error: " + ex.Message; }
    }

    void OnTestPrint(object? s, RoutedEventArgs? e)
    {
        if (PrinterBox.SelectedItem is null) { StatusText.Text = "Pilih printer dulu."; return; }
        try
        {
            var test = BuildReceipt();
            PreviewText.Text = ReceiptTextFormatter.BuildPreview(test);
            _printer.Print(PrinterBox.SelectedItem.ToString()!, EscPosBuilder.Build(test));
            StatusText.Text = "Test print terkirim (isi sesuai form).";
        }
        catch (Exception ex) { StatusText.Text = "Gagal print: " + ex.Message; }
    }

    void OnNewOrderNo(object? s, RoutedEventArgs? e)
    {
        OrderNoBox.Text = _db.SuggestOrderNo(DateTime.Now.ToString("dd/MM/yyyy"));
        OnPreview(null, null);
    }

    void OnPrint(object? s, RoutedEventArgs? e)
    {
        if (PrinterBox.SelectedItem is null) { StatusText.Text = "Pilih printer dulu."; return; }
        try
        {
            OnPreview(null, null);
            _printer.Print(PrinterBox.SelectedItem.ToString()!, EscPosBuilder.Build(_current!));
            var dt = DateTime.Now;
            _db.InsertTx(new Db.TxRow(0, _current!.OrderNo, dt.ToString("dd/MM/yyyy"), dt.ToString("HH:mm"), _current!.CustomerName,
                _current!.OrderType, _current!.TableNo, _current!.DiscountType, _current!.DiscountValue,
                Db.ItemsJson(_current!.Items), _current!.Subtotal, _current!.GrandTotal, _current!.PaymentStatus));
            OrderNoBox.Text = _db.SuggestOrderNo(DateTime.Now.ToString("dd/MM/yyyy"));
            OnRefreshHistory(null, null);
            StatusText.Text = $"Tercetak + tersimpan. Total {ReceiptTextFormatter.Rupiah(_current!.GrandTotal)}.";
        }
        catch (Exception ex) { StatusText.Text = "Gagal print: " + ex.Message; }
    }

    // ---------- history ----------
    void OnRefreshHistory(object? s, RoutedEventArgs? e)
    {
        try
        {
            var rows = _db.AllTx();
            HistoryGrid.ItemsSource = rows;
            var today = DateTime.Now.ToString("dd/MM/yyyy");
            DailyTotalText.Text = $"Hari ini ({today}): {ReceiptTextFormatter.Rupiah(_db.DailyTotal(today))} — {rows.Count} transaksi";
        }
        catch (Exception ex) { DailyTotalText.Text = "Gagal baca riwayat: " + ex.Message; }
    }

    void OnExportCsv(object? s, RoutedEventArgs? e)
    {
        var dlg = new Microsoft.Win32.SaveFileDialog { FileName = "riwayat.csv", Filter = "CSV|*.csv" };
        if (dlg.ShowDialog() == true)
        {
            File.WriteAllText(dlg.FileName, Db.ToCsv(_db.AllTx(100000)));
            DailyTotalText.Text = "CSV tersimpan: " + dlg.FileName;
        }
    }

    // ---------- help menu ----------
    string AboutDetail()
    {
        int txCount = 0;
        try { txCount = _db.AllTx(100000).Count; } catch { }
        return string.Join("\n", new[] {
            $"Aplikasi : Bunah Kasir 58mm",
            $"Runtime  : {AppInfo.Runtime}",
            $"OS       : {Environment.OSVersion}",
            $"Printer  : {(PrinterBox.SelectedItem?.ToString() ?? "-")}",
            $"Kertas   : 58mm, 32 kolom, Font A",
            $"Database : {DbPath}",
            $"Transaksi: {txCount} tersimpan",
        });
    }

    void OnAbout(object? s, RoutedEventArgs? e) =>
        new AboutWindow(AboutDetail()) { Owner = this }.ShowDialog();

    void OnGuide(object? s, RoutedEventArgs? e) => MessageBox.Show(
        "CARA PAKAI\n\n" +
        "1. Install driver printer thermal (mis. POS-58).\n" +
        "2. Pilih printer di dropdown, klik Refresh bila kosong.\n" +
        "3. Di tab Master Layanan, atur daftar layanan & harga.\n" +
        "4. Di tab Kasir: isi No. Pesanan, Nama, Jenis, Meja, pilih menu + qty.\n" +
        "5. Klik Print + Simpan — struk tercetak dan otomatis masuk Riwayat.\n" +
        "6. Tekan Alt untuk tampil/sembunyi menu Bantuan.",
        "Panduan Pakai", MessageBoxButton.OK, MessageBoxImage.Information);

    void OnBackupDb(object? s, RoutedEventArgs? e)
    {
        var dlg = new Microsoft.Win32.SaveFileDialog
            { FileName = $"bunah-{DateTime.Now:yyyyMMdd}.db", Filter = "SQLite DB|*.db" };
        if (dlg.ShowDialog() == true)
        {
            File.Copy(DbPath, dlg.FileName, overwrite: true);
            MessageBox.Show("Backup tersimpan: " + dlg.FileName, "Backup Database",
                MessageBoxButton.OK, MessageBoxImage.Information);
        }
    }

    void OnCheckUpdate(object? s, RoutedEventArgs? e)
    {
        try
        {
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(
                AppInfo.ReleasesUrl) { UseShellExecute = true });
        }
        catch (Exception ex) { MessageBox.Show("Gagal buka browser: " + ex.Message); }
    }

    void OnCopyDiagnostics(object? s, RoutedEventArgs? e)
    {
        try
        {
            var info = $"Bunah v{AppInfo.Version} (build {AppInfo.BuildTime})\n" +
                AboutDetail() + "\nStatus: " + StatusText.Text;
            Clipboard.SetText(info);
            StatusText.Text = "Info diagnostik disalin ke clipboard.";
        }
        catch (Exception ex) { StatusText.Text = "Gagal salin: " + ex.Message; }
    }
}
