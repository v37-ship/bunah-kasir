# Android app — Bunah Kasir (Kotlin + Jetpack Compose)

## Prereqs

- Android Studio Hedgehog+, JDK 17, Android SDK 34
- Pair the thermal printer in Android Settings > Bluetooth first.

## Build

```
Open android/ in Android Studio > Sync > Run on physical device
```

Grant Nearby devices / Bluetooth permissions when asked. Pick the printer from the
dropdown, Test, then Print + Simpan. History is stored in Room (SQLite), export CSV
from the Riwayat tab.
