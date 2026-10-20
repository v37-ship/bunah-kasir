package com.bunah.kasir

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.print.PrintManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = BunahColors) { CashierScreen() }
        }
    }
}

private val BunahColors = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = Color(0xFF775652),
    secondaryContainer = Color(0xFFFFDAD6),
    onSecondaryContainer = Color(0xFF2C1512),
    background = Color(0xFFFFFBF8),
    surface = Color(0xFFFFFBF8),
)

fun btPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** Restores an in-progress cart after rotation or process death. */
private val CartSaver = Saver<SnapshotStateList<ReceiptItem>, String>(
    save = { ReceiptJson.itemsToJson(it) },
    restore = {
        runCatching { ReceiptJson.itemsFromJson(it).toMutableStateList() }
            .getOrElse { mutableStateListOf() }
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashierScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val prefs = remember { ctx.getSharedPreferences("bunah", Context.MODE_PRIVATE) }

    // Every input is rememberSaveable: rotation (activity recreation) no longer clears the order.
    var orderNo by rememberSaveable { mutableStateOf("") }
    var customer by rememberSaveable { mutableStateOf("") }
    var orderType by rememberSaveable { mutableStateOf("Take Away") }
    var tableNo by rememberSaveable { mutableStateOf("") }
    var jenisMenuOpen by rememberSaveable { mutableStateOf(false) }
    var services by remember { mutableStateOf(listOf<Svc>()) }
    val cart = rememberSaveable(saver = CartSaver) { mutableStateListOf<ReceiptItem>() }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var pickerQuery by rememberSaveable { mutableStateOf("") }
    var pickedName by rememberSaveable { mutableStateOf<String?>(null) }
    var qtyText by rememberSaveable { mutableStateOf("1") }
    var pay by rememberSaveable { mutableStateOf("LUNAS Qris") }
    var printLogo by rememberSaveable { mutableStateOf(true) }
    var discount by rememberSaveable { mutableStateOf("") }
    var discountType by rememberSaveable { mutableStateOf("%") }
    var preview by rememberSaveable { mutableStateOf("") }
    var previewOpen by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(0) } // 0=Kasir 1=Riwayat 2=Master
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var appVersion by remember { mutableStateOf("") }
    var saveMessage by rememberSaveable { mutableStateOf("") }
    // Master Layanan state is hoisted so it survives tab switches.
    var mName by rememberSaveable { mutableStateOf("") }
    var mDesc by rememberSaveable { mutableStateOf("") }
    var mPrice by rememberSaveable { mutableStateOf("") }
    var mStatus by rememberSaveable { mutableStateOf("") }
    var mFilter by rememberSaveable { mutableStateOf("") }
    var mSelected by rememberSaveable { mutableStateOf<String?>(null) }

    // ---- Report state (survives rotation) ----
    val reportToday = remember { Calendar.getInstance() }
    var reportMode by rememberSaveable { mutableStateOf(ReportMode.DAY.name) }
    var reportYear by rememberSaveable { mutableStateOf(reportToday.get(Calendar.YEAR)) }
    var reportMonth by rememberSaveable { mutableStateOf(reportToday.get(Calendar.MONTH) + 1) }
    var reportDay by rememberSaveable { mutableStateOf(reportToday.get(Calendar.DAY_OF_MONTH)) }
    var reportDayPickerOpen by rememberSaveable { mutableStateOf(false) }
    var reportMonthPickerOpen by rememberSaveable { mutableStateOf(false) }
    var reportBusy by remember { mutableStateOf(false) }
    var reportMessage by rememberSaveable { mutableStateOf("") }
    var pendingReportPath by rememberSaveable { mutableStateOf<String?>(null) }

    var history by remember { mutableStateOf(listOf<Tx>()) }
    var dailyTotal by remember { mutableStateOf(0L) }
    val db = remember { AppDb.get(ctx) }

    fun currentReportPeriod() = ReportPeriod(
        mode = if (reportMode == ReportMode.MONTH.name) ReportMode.MONTH else ReportMode.DAY,
        year = reportYear,
        month = reportMonth,
        day = reportDay,
    )

    fun savePendingReport(uri: Uri) {
        val file = pendingReportPath?.let { File(it) } ?: return
        scope.launch {
            reportMessage = try {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    } ?: error("tidak bisa menulis file")
                }
                "Tersimpan: ${file.name}"
            } catch (e: Exception) {
                "Gagal menyimpan: ${e.message}"
            }
            file.delete()
            pendingReportPath = null
        }
    }

    val saveReportPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri == null) {
            pendingReportPath?.let { File(it).delete() }
            pendingReportPath = null
            reportMessage = "Dibatalkan."
        } else {
            savePendingReport(uri)
        }
    }

    val saveReportXlsxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri ->
        if (uri == null) {
            pendingReportPath?.let { File(it).delete() }
            pendingReportPath = null
            reportMessage = "Dibatalkan."
        } else {
            savePendingReport(uri)
        }
    }

    fun generateReport(format: String) {
        val period = currentReportPeriod()
        scope.launch {
            reportBusy = true
            reportMessage = "Menyiapkan laporan ${period.slug.lowercase()}..."
            try {
                val all = withContext(Dispatchers.IO) { db.tx().all() }
                val data = withContext(Dispatchers.Default) {
                    ReportDataBuilder.build(
                        all.map { it.toReportTransaction() },
                        period,
                        ReportDataBuilder.nowText(),
                    )
                }
                if (data.count == 0) {
                    reportMessage = "Tidak ada transaksi pada ${period.title}."
                    return@launch
                }
                if (format == "pdf" || format == "print") {
                    val file = File(ctx.cacheDir, "laporan-${period.fileCode}.pdf")
                    val pages = withContext(Dispatchers.IO) {
                        PdfReportWriter.write(
                            file, data, BUNAH_REPORT_PROFILE, bunahReportLogo(ctx))
                    }
                    if (format == "print") {
                        val pm = ctx.getSystemService(PrintManager::class.java)
                            ?: error("Layanan cetak tidak tersedia")
                        pm.print(
                            "Laporan ${period.slug} ${period.title}",
                            PdfPrintAdapter(
                                file,
                                "Laporan-${period.slug}-${period.fileCode}",
                                pages,
                            ),
                            null,
                        )
                        reportMessage = "Dialog cetak dibuka ($pages halaman)."
                    } else {
                        pendingReportPath = file.absolutePath
                        reportMessage = "PDF siap ($pages halaman). Pilih lokasi simpan."
                        saveReportPdfLauncher.launch(
                            "Laporan-${period.slug}-${BUNAH_REPORT_PROFILE.fileTag}-${period.fileCode}.pdf")
                    }
                } else {
                    val file = File(ctx.cacheDir, "laporan-${period.fileCode}.xlsx")
                    withContext(Dispatchers.IO) {
                        file.outputStream().use {
                            XlsxReportWriter.write(it, data, BUNAH_REPORT_PROFILE)
                        }
                    }
                    pendingReportPath = file.absolutePath
                    reportMessage = "Excel siap. Pilih lokasi simpan."
                    saveReportXlsxLauncher.launch(
                        "Laporan-${period.slug}-${BUNAH_REPORT_PROFILE.fileTag}-${period.fileCode}.xlsx")
                }
            } catch (e: Exception) {
                reportMessage = "Gagal membuat laporan: ${e.message}"
            } finally {
                reportBusy = false
            }
        }
    }

    // ---- Bluetooth state ----
    var btGranted by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(listOf<BluetoothDevice>()) }
    var selectedAddr by rememberSaveable { mutableStateOf(prefs.getString("printer_addr", null)) }
    var btMenuOpen by rememberSaveable { mutableStateOf(false) }
    var btOn by remember { mutableStateOf(false) }

    fun hasBtPermission() = btPermissions().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    fun adapter(): BluetoothAdapter? =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter

    fun safeName(d: BluetoothDevice) = try { d.name ?: d.address } catch (e: SecurityException) { d.address }

    fun loadDevices() {
        if (!hasBtPermission()) { btGranted = false; devices = emptyList(); return }
        btGranted = true
        val a = adapter()
        btOn = a?.isEnabled == true
        val bonded = try { a?.bondedDevices?.toList() ?: emptyList() } catch (e: SecurityException) { emptyList() }
        devices = bonded.sortedBy { safeName(it) }
        if (selectedAddr == null || bonded.none { it.address == selectedAddr }) {
            selectedAddr = bonded.firstOrNull {
                val n = try { it.name ?: "" } catch (e: SecurityException) { "" }
                n.contains("583", true) || n.contains("EXP", true) ||
                    n.contains("POS", true) || n.contains("printer", true)
            }?.address ?: bonded.firstOrNull()?.address
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { loadDevices() }

    val enableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { loadDevices() }

    // SAF: no storage permission needed; the user picks where the file goes.
    fun writeExport(uri: Uri) {
        scope.launch {
            saveMessage = try {
                val rows = withContext(Dispatchers.IO) { db.tx().all() }
                val csv = StringBuilder("id,order_no,date,time,customer,order_type,table_no,discount_type,discount_value,items,subtotal,total,payment\n")
                rows.forEach { t ->
                    csv.append("${t.id},${t.orderNo},${t.date},${t.time},${t.customer},${t.orderType},${t.tableNo},")
                        .append("${t.discountType},${t.discountValue},")
                        .append("\"${t.itemsJson.replace("\"", "\"\"")}\",")
                        .append("${t.subtotal},${t.total},${t.paymentStatus}\n")
                }
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(csv.toString().toByteArray()) }
                        ?: error("tidak bisa menulis file")
                }
                "CSV tersimpan (${rows.size} transaksi)"
            } catch (e: Exception) { "Gagal export: ${e.message}" }
        }
    }

    fun backupDb(uri: Uri) {
        scope.launch {
            saveMessage = try {
                val dbFile = ctx.getDatabasePath("bunah.db")
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        dbFile.inputStream().use { it.copyTo(out) }
                    } ?: error("tidak bisa menulis file")
                }
                "Backup database tersimpan (${dbFile.length()} bytes)"
            } catch (e: Exception) { "Gagal backup: ${e.message}" }
        }
    }

    var exportMode by rememberSaveable { mutableStateOf("csv") }
    val saveDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri -> uri?.let { if (exportMode == "csv") writeExport(it) else backupDb(it) } }

    fun selectedDevice(): BluetoothDevice? = devices.firstOrNull { it.address == selectedAddr }

    fun refreshHistory() {
        scope.launch {
            val all = withContext(Dispatchers.IO) { db.tx().all() }
            history = all
            val today = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())
            dailyTotal = withContext(Dispatchers.IO) { db.tx().dailyTotal(today) }
        }
    }

    fun refreshServices() {
        scope.launch {
            services = withContext(Dispatchers.IO) { db.svc().all() }
            val today = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())
            dailyTotal = withContext(Dispatchers.IO) { db.tx().dailyTotal(today) }
        }
    }

    fun removeService(name: String) {
        scope.launch {
            withContext(Dispatchers.IO) { db.svc().delete(name) }
            val gone = cart.removeAll { it.name == name }
            mStatus = if (gone) "Dihapus: $name (juga dari keranjang)" else "Dihapus: $name"
            if (mSelected == name) { mSelected = null; mName = ""; mDesc = ""; mPrice = "" }
            refreshServices()
        }
    }

    fun cartSubtotal() = cart.sumOf { it.lineTotal }
    fun cartDiscount() = ReceiptTextFormatter.discountAmount(
        cartSubtotal(), if (discount.isBlank()) "" else discountType,
        discount.trim().toLongOrNull() ?: 0L)
    fun cartGrand() = cartSubtotal() - cartDiscount()

    fun filteredServices(): List<Svc> {
        val q = pickerQuery.trim()
        return if (q.isEmpty()) services
        else services.filter { it.name.contains(q, true) || it.desc.contains(q, true) }
    }

    fun addToCart() {
        val list = filteredServices()
        val picked = list.firstOrNull { it.name == pickedName } ?: list.firstOrNull() ?: return
        val q = qtyText.trim().toIntOrNull() ?: 1
        if (q < 1) { status = "Qty harus >= 1"; return }
        cart.removeAll { it.name == picked.name }
        cart.add(ReceiptItem(picked.name, picked.desc, picked.price).apply { qty = q })
        status = "Ditambah: ${picked.name} x$q"
    }

    suspend fun suggestOrderNo(): String {
        val today = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())
        val all = db.tx().all()
        var max = 0
        for (t in all) {
            if (t.date != today) continue
            val n = t.orderNo.filter { it.isDigit() }.toIntOrNull() ?: 0
            if (n > max) max = n
        }
        return (max + 1).toString().padStart(3, '0')
    }

    fun buildReceipt(): Receipt {
        val now = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date())
        val discVal = discount.trim().toLongOrNull() ?: 0L
        val discType = if (discount.trim().isEmpty()) "" else discountType
        return Receipt(
            StoreInfo("BU NAH", "Chinese Food Halal",
                "Kayu Putih II No 32, Pulo Gadung, Jaktim",
                "Buka 11.00 - 20.00 WIB",
                "WA 082 1134 7293"),
            orderNo.ifBlank { "000" }, now, customer, orderType, tableNo,
            cart.toList(), discType, discVal, pay,
            listOf("TERIMA KASIH",
                "Order & free antar Kayu Putih",
                "*** BU NAH ***"),
            printLogo = printLogo
        )
    }

    fun doPrint(saveToHistory: Boolean) {
        scope.launch {
            status = try {
                if (!hasBtPermission()) throw Exception("Izin Bluetooth belum diberikan.")
                val dev = selectedDevice() ?: throw Exception("Pilih printer dulu.")
                val svc = BluetoothPrinterService(adapter())
                val r = buildReceipt()
                preview = ReceiptTextFormatter.buildPreview(r)
                withContext(Dispatchers.IO) {
                    svc.print(dev, EscPosBuilder.build(r))
                    if (saveToHistory) {
                        val nowParts = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date()).split(" ")
                        db.tx().insert(Tx(orderNo = r.orderNo,
                            date = nowParts[0], time = nowParts[1],
                            customer = r.customerName,
                            orderType = r.orderType, tableNo = r.tableNo,
                            discountType = r.discountType, discountValue = r.discountValue,
                            itemsJson = ReceiptJson.itemsToJson(r.items),
                            subtotal = r.subtotal, total = r.grandTotal,
                            paymentStatus = r.paymentStatus))
                    }
                }
                if (saveToHistory) refreshHistory()
                if (saveToHistory) "Tercetak + tersimpan ke ${safeName(dev)}"
                else "Test terkirim ke ${safeName(dev)} (isi sesuai form)"
            } catch (e: Exception) { "Gagal: ${e.message}" }
        }
    }

    fun showPreview() {
        preview = ReceiptTextFormatter.buildPreview(buildReceipt())
        previewOpen = true
    }

    LaunchedEffect(Unit) {
        if (orderNo.isBlank()) {
            orderNo = withContext(Dispatchers.IO) {
                try { suggestOrderNo() } catch (e: Exception) { "001" }
            }
        }
        appVersion = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
        if (hasBtPermission()) loadDevices()
        withContext(Dispatchers.IO) {
            // Seed once only: re-seeding on every launch used to bring back services
            // the user had deliberately deleted from Master.
            if (!prefs.getBoolean("seed_v13", false)) {
                listOf("Nasgor Ayam", "Nasgor Seafood", "Bakmi Goreng Ayam",
                    "Bakmi Goreng Seafood", "Cap Cay Ayam", "Cap Cay Seafood",
                    "Omelet Sayur/Fuyunghai", "Kwe Tiauw Ayam", "Kwe Tiauw Seafood",
                    "Chicken Butter Rice", "Ca Kangkung/Buncis Jumbo")
                    .forEach { db.svc().delete(it) }
                if (db.svc().all().isEmpty()) seedServices().forEach { db.svc().upsert(it) }
                prefs.edit().putBoolean("seed_v13", true).apply()
            }
        }
        refreshServices()
        refreshHistory()
    }

    LaunchedEffect(status) {
        if (status.isNotBlank()) snackbar.showSnackbar(status)
    }

    // ---------------- Sections ----------------

    @Composable
    fun PrinterSection() {
        SectionCard("Printer Bluetooth") {
            if (!btGranted) {
                Text("Aplikasi butuh izin Bluetooth untuk mencari printer.")
                Button(onClick = { permLauncher.launch(btPermissions()) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Minta Izin Bluetooth")
                }
            } else {
                if (!btOn) {
                    Text("Bluetooth HP mati.")
                    Button(onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                        modifier = Modifier.fillMaxWidth()) { Text("Nyalakan Bluetooth") }
                }
                ExposedDropdownMenuBox(expanded = btMenuOpen, onExpandedChange = { btMenuOpen = it }) {
                    OutlinedTextField(
                        value = selectedDevice()?.let { safeName(it) } ?: "- pilih printer -",
                        onValueChange = {}, readOnly = true, label = { Text("Printer") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(btMenuOpen) },
                        modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = btMenuOpen, onDismissRequest = { btMenuOpen = false }) {
                        devices.forEach { d ->
                            DropdownMenuItem(
                                text = { Text("${safeName(d)}\n${d.address}") },
                                onClick = {
                                    selectedAddr = d.address
                                    prefs.edit().putString("printer_addr", d.address).apply()
                                    btMenuOpen = false
                                })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { loadDevices() }, modifier = Modifier.weight(1f)) {
                        Text("Refresh")
                    }
                    OutlinedButton(
                        onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                        modifier = Modifier.weight(1f)) {
                        Text("Pair Baru")
                    }
                }
                if (devices.isEmpty())
                    Text("Belum ada printer paired. Klik Pair Baru, pair EXP583, lalu Refresh.",
                        style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { doPrint(false) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Test Print")
                }
            }
        }
    }

    @Composable
    fun OrderSection() {
        SectionCard("Transaksi") {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(orderNo, { orderNo = it }, label = { Text("No. Pesanan") },
                    modifier = Modifier.weight(1f), singleLine = true)
                OutlinedButton(onClick = {
                    scope.launch {
                        orderNo = withContext(Dispatchers.IO) {
                            try { suggestOrderNo() } catch (e: Exception) { "001" }
                        }
                    }
                }, modifier = Modifier.weight(0.8f)) {
                    Text("No. Baru")
                }
            }
            OutlinedTextField(customer, { customer = it }, label = { Text("Nama") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            ExposedDropdownMenuBox(expanded = jenisMenuOpen, onExpandedChange = { jenisMenuOpen = it }) {
                OutlinedTextField(orderType, {}, readOnly = true, label = { Text("Jenis") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(jenisMenuOpen) },
                    modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = jenisMenuOpen, onDismissRequest = { jenisMenuOpen = false }) {
                    listOf("Dine In", "Take Away", "Delivery").forEach { j ->
                        DropdownMenuItem(text = { Text(j) },
                            onClick = { orderType = j; jenisMenuOpen = false })
                    }
                }
            }
            if (orderType == "Dine In")
                OutlinedTextField(tableNo, { tableNo = it }, label = { Text("Meja") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(discount, { discount = it },
                label = { Text("Diskon (kosong = tanpa diskon)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text("Tipe diskon", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = discountType == "%", onClick = { discountType = "%" },
                    label = { Text("%") })
                FilterChip(selected = discountType == "Rp", onClick = { discountType = "Rp" },
                    label = { Text("Rp") })
            }
        }
    }

    @Composable
    fun AddItemSection() {
        SectionCard("Tambah Menu") {
            if (services.isEmpty()) {
                Text("Belum ada menu. Tambahkan di tab Master.")
            } else {
                // Selected service is remembered by NAME so it survives filtering/reordering.
                val picked = filteredServices().firstOrNull { it.name == pickedName }
                    ?: filteredServices().firstOrNull()
                OutlinedButton(onClick = { pickerQuery = ""; pickerOpen = true },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(picked?.let { "${it.name} — ${ReceiptTextFormatter.rupiah(it.price)}" }
                            ?: "Pilih Menu",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    Text("▼")
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(qtyText, { qtyText = it.filter(Char::isDigit) },
                        label = { Text("Qty") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Button(onClick = { addToCart() }, modifier = Modifier.weight(1.3f)) {
                        Text("Tambah", maxLines = 1)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = printLogo, onCheckedChange = { printLogo = it })
                    Text("Cetak logo")
                }
                if (picked?.desc?.isNotBlank() == true)
                    Text(picked.desc, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    fun CartSection() {
        SectionCard(if (cart.isEmpty()) "Pesanan" else "Pesanan (${cart.size})") {
            if (cart.isEmpty()) {
                Text("Belum ada menu. Pilih menu lalu tekan Tambah.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                cart.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge)
                            Text("${item.qty} × ${ReceiptTextFormatter.rupiah(item.price)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(0.8f), horizontalAlignment = Alignment.End) {
                            Text(ReceiptTextFormatter.rupiah(item.lineTotal),
                                style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { cart.remove(item) },
                                contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("Hapus")
                            }
                        }
                    }
                    if (index != cart.lastIndex) HorizontalDivider()
                }
                HorizontalDivider()
                TotalRow("Subtotal", ReceiptTextFormatter.rupiah(cartSubtotal()))
                val disc = cartDiscount()
                if (disc > 0)
                    TotalRow("Diskon", "-${ReceiptTextFormatter.rupiah(disc)}")
                TotalRow("TOTAL", ReceiptTextFormatter.rupiah(cartGrand()), bold = true)
            }
        }
    }

    @Composable
    fun PaymentSection() {
        SectionCard("Pembayaran") {
            OutlinedTextField(pay, { pay = it }, label = { Text("Status Pembayaran") },
                modifier = Modifier.fillMaxWidth())
        }
    }

    @Composable
    fun ReportSection() {
        val period = currentReportPeriod()
        val preview = remember(history, period) {
            ReportDataBuilder.build(
                history.map { it.toReportTransaction() }, period, "")
        }
        SectionCard("Laporan") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = reportMode == ReportMode.DAY.name,
                    onClick = { reportMode = ReportMode.DAY.name },
                    label = { Text("Harian") },
                )
                FilterChip(
                    selected = reportMode == ReportMode.MONTH.name,
                    onClick = { reportMode = ReportMode.MONTH.name },
                    label = { Text("Bulanan") },
                )
            }
            OutlinedButton(
                onClick = {
                    if (reportMode == ReportMode.DAY.name) reportDayPickerOpen = true
                    else reportMonthPickerOpen = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(period.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text("▼")
            }
            Text("${preview.count} transaksi · ${ReceiptTextFormatter.rupiah(preview.total)}",
                style = MaterialTheme.typography.titleSmall)
            if (preview.count == 0) {
                Text("Tidak ada transaksi pada ${period.title}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { generateReport("pdf") },
                    enabled = preview.count > 0 && !reportBusy,
                    modifier = Modifier.weight(1f),
                ) { Text("PDF") }
                Button(
                    onClick = { generateReport("xlsx") },
                    enabled = preview.count > 0 && !reportBusy,
                    modifier = Modifier.weight(1f),
                ) { Text("Excel") }
            }
            OutlinedButton(
                onClick = { generateReport("print") },
                enabled = preview.count > 0 && !reportBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Cetak PDF (A4)") }
            if (reportBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (reportMessage.isNotEmpty())
                Text(reportMessage, style = MaterialTheme.typography.labelMedium)
        }
    }

    @Composable
    fun HistoryTab() {
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { ReportSection() }
            item {
                SectionCard("Riwayat") {
                    Text("Hari ini: ${ReceiptTextFormatter.rupiah(dailyTotal)} — ${history.size} transaksi",
                        style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            exportMode = "csv"
                            saveDocLauncher.launch("bunah-riwayat.csv")
                        }, modifier = Modifier.weight(1f)) {
                            Text("Export CSV")
                        }
                        OutlinedButton(onClick = {
                            exportMode = "db"
                            saveDocLauncher.launch("bunah-backup.db")
                        }, modifier = Modifier.weight(1f)) {
                            Text("Backup DB")
                        }
                    }
                    if (saveMessage.isNotEmpty())
                        Text(saveMessage, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (history.isEmpty())
                item { Text("Belum ada transaksi.", Modifier.padding(8.dp)) }
            items(history, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${t.orderNo} · ${t.date} ${t.time}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(t.customer.ifBlank { "-" },
                            style = MaterialTheme.typography.titleSmall)
                        Text(ReceiptTextFormatter.rupiah(t.total),
                            style = MaterialTheme.typography.titleMedium)
                        Text(t.paymentStatus, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    @Composable
    fun MasterTab() {
        val shown = services.filter {
            mFilter.isBlank() || it.name.contains(mFilter, true) || it.desc.contains(mFilter, true)
        }
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SectionCard("Master Menu") {
                    OutlinedTextField(mName, { mName = it }, label = { Text("Nama Menu") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(mDesc, { mDesc = it }, label = { Text("Deskripsi") },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(mPrice, { mPrice = it }, label = { Text("Harga") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                val price = mPrice.trim().toLongOrNull()
                                when {
                                    mName.isBlank() -> mStatus = "Nama menu wajib diisi"
                                    price == null -> mStatus = "Harga harus angka"
                                    else -> {
                                        withContext(Dispatchers.IO) {
                                            db.svc().upsert(Svc(mName.trim(), mDesc.trim(), price))
                                        }
                                        mStatus = "Tersimpan: ${mName.trim()}"
                                        mSelected = mName.trim()
                                        refreshServices()
                                    }
                                }
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Text("Simpan", maxLines = 1)
                        }
                        Button(onClick = { pendingDelete = mName.trim() },
                            enabled = mName.isNotBlank(),
                            modifier = Modifier.weight(1f)) {
                            Text("Hapus", maxLines = 1)
                        }
                    }
                    if (mSelected != null)
                        Text("Terpilih: $mSelected", style = MaterialTheme.typography.labelMedium)
                    if (mStatus.isNotEmpty())
                        Text(mStatus, style = MaterialTheme.typography.labelMedium)
                }
            }
            item {
                OutlinedTextField(mFilter, { mFilter = it }, label = { Text("Cari menu") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            if (shown.isEmpty()) {
                item { Text("Tidak ada menu yang cocok.", Modifier.padding(8.dp)) }
            } else {
                items(shown, key = { it.name }) { svc ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable {
                                mSelected = svc.name; mName = svc.name
                                mDesc = svc.desc; mPrice = svc.price.toString()
                            }) {
                                Text(svc.name, style = MaterialTheme.typography.bodyLarge)
                                Text("${svc.desc} — ${ReceiptTextFormatter.rupiah(svc.price)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { pendingDelete = svc.name }) { Text("Hapus") }
                        }
                    }
                }
            }
        }
    }

    // ---------------- Dialogs ----------------

    @Composable
    fun PickerDialog() {
        if (!pickerOpen) return
        val results = filteredServices()
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = { TextButton(onClick = { pickerOpen = false }) { Text("Tutup") } },
            title = { Text("Pilih Menu (${results.size}/${services.size})") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(pickerQuery, { pickerQuery = it },
                        label = { Text("Cari menu") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    if (results.isEmpty()) {
                        Text("Tidak ada menu yang cocok.", modifier = Modifier.padding(top = 8.dp))
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 4.dp)) {
                            items(results, key = { it.name }) { svc ->
                                Row(Modifier.fillMaxWidth()
                                    .clickable { pickedName = svc.name; pickerOpen = false }
                                    .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(svc.name, style = MaterialTheme.typography.bodyLarge)
                                        Text("${svc.desc} — ${ReceiptTextFormatter.rupiah(svc.price)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    RadioButton(selected = svc.name == pickedName,
                                        onClick = { pickedName = svc.name; pickerOpen = false })
                                }
                            }
                        }
                    }
                }
            })
    }

    @Composable
    fun PreviewDialog() {
        if (!previewOpen) return
        AlertDialog(
            onDismissRequest = { previewOpen = false },
            confirmButton = { TextButton(onClick = { previewOpen = false }) { Text("Tutup") } },
            title = { Text("Preview 58mm (32 kolom)") },
            text = {
                Box(Modifier.fillMaxWidth().heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())) {
                    Text(preview, fontFamily = FontFamily.Monospace,
                        softWrap = false, style = MaterialTheme.typography.bodySmall)
                }
            })
    }

    @Composable
    fun DeleteDialog() {
        val name = pendingDelete ?: return
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; removeService(name) }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Batal") } },
            title = { Text("Hapus menu?") },
            text = { Text("\"$name\" akan dihapus dari Master Menu dan dari keranjang.") })
    }

    // ---------------- Layout ----------------

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                AppHeader(version = appVersion)
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    Tab(selected = tab == 0, onClick = { tab = 0 },
                        text = { Text("Kasir", maxLines = 1) })
                    Tab(selected = tab == 1, onClick = { tab = 1; refreshHistory() },
                        text = { Text("Riwayat", maxLines = 1) })
                    Tab(selected = tab == 2, onClick = { tab = 2; refreshServices() },
                        text = { Text("Master", maxLines = 1) })
                }
            }
        },
        bottomBar = {
            if (tab == 0) PrintBar(onPreview = { showPreview() }, onPrint = { doPrint(true) })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(inner)) {
            val wide = maxWidth >= 600.dp
            when (tab) {
                0 -> if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1.15f).fillMaxHeight()
                            .verticalScroll(rememberScrollState()).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrinterSection()
                            OrderSection()
                            AddItemSection()
                        }
                        Column(Modifier.weight(1f).fillMaxHeight()
                            .verticalScroll(rememberScrollState()).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CartSection()
                            PaymentSection()
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrinterSection()
                        OrderSection()
                        AddItemSection()
                        CartSection()
                        PaymentSection()
                    }
                }
                1 -> HistoryTab()
                else -> MasterTab()
            }
        }
    }

    PickerDialog()
    PreviewDialog()
    DeleteDialog()

    if (reportDayPickerOpen) {
        val initial = remember(reportYear, reportMonth, reportDay) {
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(reportYear, reportMonth - 1, reportDay)
            }.timeInMillis
        }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { reportDayPickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                        c.timeInMillis = millis
                        reportYear = c.get(Calendar.YEAR)
                        reportMonth = c.get(Calendar.MONTH) + 1
                        reportDay = c.get(Calendar.DAY_OF_MONTH)
                    }
                    reportDayPickerOpen = false
                }) { Text("Pilih") }
            },
            dismissButton = {
                TextButton(onClick = { reportDayPickerOpen = false }) { Text("Batal") }
            },
        ) { DatePicker(state = pickerState) }
    }

    if (reportMonthPickerOpen) {
        AlertDialog(
            onDismissRequest = { reportMonthPickerOpen = false },
            confirmButton = {
                TextButton(onClick = { reportMonthPickerOpen = false }) { Text("Pilih") }
            },
            dismissButton = {
                TextButton(onClick = { reportMonthPickerOpen = false }) { Text("Batal") }
            },
            title = { Text("Pilih Bulan") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = { reportYear = (reportYear - 1).coerceAtLeast(1970) }) { Text("‹") }
                        Text("$reportYear", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { reportYear = (reportYear + 1).coerceAtMost(2100) }) { Text("›") }
                    }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(REPORT_MONTHS_ID.size) { i ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { reportMonth = i + 1 }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = reportMonth == i + 1,
                                    onClick = { reportMonth = i + 1 },
                                )
                                Text(REPORT_MONTHS_ID[i], Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun AppHeader(version: String) {
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo_bunah), contentDescription = "BU NAH",
                modifier = Modifier.height(44.dp).widthIn(max = 150.dp),
                contentScale = ContentScale.Fit)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Kasir", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(if (version.isEmpty()) "58mm" else "58mm · v$version",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PrintBar(onPreview: () -> Unit, onPrint: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPreview, modifier = Modifier.weight(1f)) {
                Text("Preview")
            }
            Button(onClick = onPrint, modifier = Modifier.weight(1f)) {
                Text("Print")
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun TotalRow(label: String, value: String, bold: Boolean = false) {
    val style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = style)
        Text(value, style = style)
    }
}

private fun seedServices(): List<Svc> = listOf(
    Svc("Nasgor Ayam (M)", "", 25000),
    Svc("Nasgor Ayam (L)", "", 32000),
    Svc("Nasgor Seafood (M)", "", 25000),
    Svc("Nasgor Seafood (L)", "", 32000),
    Svc("Bakmi Goreng Ayam (M)", "", 25000),
    Svc("Bakmi Goreng Ayam (L)", "", 32000),
    Svc("Bakmi Goreng Seafood (M)", "", 25000),
    Svc("Bakmi Goreng Seafood (L)", "", 32000),
    Svc("Cap Cay Ayam (M)", "", 25000),
    Svc("Cap Cay Ayam (L)", "", 32000),
    Svc("Cap Cay Seafood (M)", "", 25000),
    Svc("Cap Cay Seafood (L)", "", 32000),
    Svc("Omelet Sayur/Fuyunghai (M)", "", 25000),
    Svc("Omelet Sayur/Fuyunghai (L)", "", 32000),
    Svc("Kwe Tiauw Ayam (M)", "", 25000),
    Svc("Kwe Tiauw Ayam (L)", "", 32000),
    Svc("Kwe Tiauw Seafood (M)", "", 25000),
    Svc("Kwe Tiauw Seafood (L)", "", 32000),
    Svc("Chicken Butter Rice (M)", "", 25000),
    Svc("Chicken Butter Rice (L)", "", 32000),
    Svc("Sapo Tahu (L)", "", 32000),
    Svc("Sop Ayam (M)", "", 25000),
    Svc("Sop Ayam (L)", "", 32000),
    Svc("Jamu BK/KA 350ml", "", 15000),
    Svc("Jamu BK/KA 1 liter", "", 30000),
    Svc("Teh/es teh", "", 5000),
    Svc("Jeruk/es jeruk", "", 10000),
    Svc("Puding", "coklat/stroberi/mangga/hazelnut/alpukat/caramel", 10000),
    Svc("Snack", "Ceker/Usus/Peyek/Kacang", 25000),
)
