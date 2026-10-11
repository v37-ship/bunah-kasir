# Receipt Spec — BU NAH Chinese Food Halal, 58mm, 32 columns

Target printer: 58mm thermal / POS-58 class (ESC/POS). All text ASCII/Latin-1.
Core vendored from the print58 project (WanggaSpa v1.13); layout is BU NAH specific.

## 1. Constants

- `WIDTH = 32`
- `DASH = "-" * 32`
- Logo (optional, default ON): pre-rendered 168-dot raster
  (`shared-spec/logo-raster.bin`, white bg / dark emblem), embedded in both apps.
  Emitted centered (`GS v 0`) right after INIT when enabled. Text preview shows
  a centered `[LOGO]` placeholder line instead.
  Toggle: "Cetak logo" / "Logo" checkbox in both apps.
- Rupiah: `Rp 25.000` — prefix `Rp ` + thousands grouped by `.`, no decimals.
- Datetime: `DD/MM/YYYY HH:mm` (no WIB suffix — fits one line: 13 + 16 = 29 chars).
- Order number: free text, suggested as `max numeric today + 1` zero-padded (`001`);
  user-editable so a revision reprint can reuse any number.

## 2. Visual layout (32-char preview)

```
             BU NAH             <- bold, normal size
       Chinese Food Halal       <- double-height bold (fits 32 cols)
Kayu Putih II No 32, Pulo Gadung,
            Jaktim              <- address wraps to 2 lines
     Buka 11.00 - 20.00 WIB    <- centered
        WA 082 1134 7293       <- centered
--------------------------------
No. Pesanan : 001
Tanggal    : 10/10/2026 13:45
Nama       : Budi
Jenis      : Take Away
--------------------------------
Layanan / Produk          Total
--------------------------------
Nasgor Ayam x2        Rp 50.000
Kwe Tiauw Ayam        Rp 25.000
Es Teh x3             Rp 15.000
--------------------------------
Subtotal               Rp 90.000
Diskon 10%            -Rp 9.000   <- only when discount > 0
--------------------------------
TOTAL                  Rp 81.000  <- bold, normal size
Bayar                  LUNAS Qris
--------------------------------
        TERIMA KASIH
 Order & free antar Kayu Putih
        *** BU NAH ***
```

## 3. Line rules

- `center(s)`: wrap at 32, center each line.
- `row(label, value)`: `label.padEnd(11) + ": " + value`. Must stay ≤ 32 —
  datetime without WIB (16 chars) is the tightest fit at 29.
- Customer block: `No. Pesanan` (free text), `Tanggal` (datetime),
  `Nama` (row omitted when empty), `Jenis` (Dine In / Take Away / Delivery),
  `Meja` (row omitted unless Dine In with a table number).
- Items: `label` = name, or `name xN` when qty > 1; line total = price × qty, right-aligned.
  Desc printed only when non-empty; no blank lines between items (compact).
- Discount: type `""` none, `"%"` percent (0–100), `"Rp"` nominal. Clamped so
  `total = subtotal − discount ≥ 0`. Label is `Diskon {n}%` or `Diskon`, amount `-Rp X`.
- Feed 2 lines, reset spacing (`ESC 2`), then cut.

## 4. ESC/POS bytes (identical both platforms)

```
INIT        1B 40
SPACING 24  1B 33 18   (compact; reset with ESC 2 before cut)
ALIGN_LEFT  1B 61 00
ALIGN_CTR   1B 61 01
BOLD_ON     1B 45 01
BOLD_OFF    1B 45 00
SIZE_NORMAL 1D 21 00
SIZE_2X     1D 21 11   (store name only)
FEED n      1B 64 n
SPACING_RST 1B 32
CUT         1D 56 01
```

Sequence: INIT → [logo raster, centered] → spacing 24 → header
(CTR, BOLD store name normal size, double-height BOLD tagline, NORMAL rest) →
LEFT meta/item rows (Nama omitted when empty, Meja only for Dine In with table) →
CTR footer (BOLD) → FEED 2 → spacing reset → CUT.

## 5. Edge cases

- Long item name + price: name wraps first, price right-aligned on the last line.
- `•` → `*` (Latin-1 has no bullet).
- `Subtotal`/`GrandTotal` are computed from items — never passed in — so printed and
  stored totals cannot disagree.
