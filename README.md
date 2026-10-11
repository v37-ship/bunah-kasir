# BU NAH — Kasir 58mm (Windows + Android)

Receipt printer app for **BU NAH Chinese Food Halal**
(Jl. Kayu Putih Utara VI C No.37, 11.00–20.00 WIB, WA 082 1134 7293).
Core vendored from the `print58` project; layout is BU NAH specific —
see `shared-spec/receipt-spec.md` and `shared-spec/golden/`.

```
bunah-kasir/
  shared-spec/        Layout spec, sample transaction, golden fixtures, VERSION
  windows/            C# .NET 8 WPF — prints via installed print queue (RAW spooler)
  android/            Kotlin + Jetpack Compose — Bluetooth Classic (paired devices)
  tools/              verify_receipt.py (Python golden check), dotnet-smoke (C# golden check)
```

Receipt: order number (editable, suggested), date/time, name, order type
(Dine In / Take Away / Delivery), table, qty cart, optional %/Rp discount,
payment status. 32 columns, ESC/POS.

Releases (Windows single-file exe + signed Android APK) are built by CI on tags.
