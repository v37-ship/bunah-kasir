# Windows app — Bunah Kasir (C# .NET 8 WPF)

## Prereqs (on a Windows PC)

- Windows 10/11, no .NET install needed (self-contained exe)
- Thermal printer driver installed (e.g. queue `POS-58`).
  Check exact queue name with `Get-Printer` in PowerShell.

## Build & run

```powershell
dotnet build Bunah.sln -c Release
dotnet run --project src/Bunah.App
```

Single-file exe: `dotnet publish src/Bunah.App -c Release -r win-x64 --self-contained -p:EnableWindowsTargeting=true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o publish/win-x64`

## Use

1. Master: menu list with prices (seeded from the BU NAH board).
2. Kasir: No. Pesanan (editable suggestion), Nama, Jenis, Meja, pick menu + qty,
   optional discount, payment status.
3. Preview: 32-col text preview — must match `shared-spec/receipt-spec.md`.
4. Printer: select the queue > Test Print > Print + Simpan (RAW spooler passthrough).
5. Riwayat: history, daily total, CSV export.
