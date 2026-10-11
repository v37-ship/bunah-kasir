package com.bunah.kasir

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import java.io.File
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { CashierScreen() } }
    }
}

fun btPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else
        arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashierScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var orderNo by remember { mutableStateOf("") }
    var customer by remember { mutableStateOf("") }
    var orderType by remember { mutableStateOf("Take Away") }
    var tableNo by remember { mutableStateOf("") }
    var jenisMenuOpen by remember { mutableStateOf(false) }
    var services by remember { mutableStateOf(listOf<Svc>()) }
    val cart = remember { mutableStateListOf<ReceiptItem>() }
    var selService by remember { mutableStateOf(0) }
    var svcFilter by remember { mutableStateOf("") }
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerQuery by remember { mutableStateOf("") }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var qtyText by remember { mutableStateOf("1") }
    var pay by remember { mutableStateOf("LUNAS Qris") }
    var printLogo by remember { mutableStateOf(true) }
    var discount by remember { mutableStateOf("") }
    var discountType by remember { mutableStateOf("%") }
    var preview by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    // 0=Kasir 1=Riwayat 2=Master
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var appVersion by remember { mutableStateOf("") }
    var saveMessage by remember { mutableStateOf("") }
    // Master Layanan state is hoisted so it survives tab switches.
    var mName by remember { mutableStateOf("") }
    var mDesc by remember { mutableStateOf("") }
    var mPrice by remember { mutableStateOf("") }
    var mStatus by remember { mutableStateOf("") }
    var mFilter by remember { mutableStateOf("") }
    var mSelected by remember { mutableStateOf<String?>(null) }

    var history by remember { mutableStateOf(listOf<Tx>()) }
    var dailyTotal by remember { mutableStateOf(0L) }
    val db = remember {
        Room.databaseBuilder(ctx, AppDb::class.java, "bunah.db")
            .fallbackToDestructiveMigration().build()
    }

    // ---- Bluetooth state ----
    var btGranted by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf(listOf<BluetoothDevice>()) }
    var selectedAddr by remember { mutableStateOf<String?>(null) }
    var btMenuOpen by remember { mutableStateOf(false) }
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

    var exportMode by remember { mutableStateOf("csv") }
    val saveDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri -> uri?.let { if (exportMode == "csv") writeExport(it) else backupDb(it) } }

    fun ensurePermissionThen(op: () -> Unit) {
        if (hasBtPermission()) { loadDevices(); op() }
        else permLauncher.launch(btPermissions())
    }

    fun selectedDevice(): BluetoothDevice? =
        devices.firstOrNull { it.address == selectedAddr }


    fun refreshHistory() {        scope.launch {
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
    fun cartGrand() = cartSubtotal() - ReceiptTextFormatter.discountAmount(
        cartSubtotal(), if (discount.isBlank()) "" else discountType,
        discount.trim().toLongOrNull() ?: 0L)

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

    LaunchedEffect(Unit) {
        scope.launch {
            orderNo = withContext(Dispatchers.IO) {
                try { suggestOrderNo() } catch (e: Exception) { "001" }
            }
        }
        appVersion = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
        if (hasBtPermission()) loadDevices()
        scope.launch {
            val retired = listOf("Nasgor Ayam", "Nasgor Seafood", "Bakmi Goreng Ayam", "Bakmi Goreng Seafood", "Cap Cay Ayam", "Cap Cay Seafood", "Omelet Sayur/Fuyunghai", "Kwe Tiauw Ayam", "Kwe Tiauw Seafood", "Chicken Butter Rice", "Ca Kangkung/Buncis Jumbo")
            val seed = listOf(
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
                Svc("Snack", "Ceker/Usus/Peyek/Kacang", 25000))
            withContext(Dispatchers.IO) { retired.forEach { db.svc().delete(it) }; seed.forEach { db.svc().upsert(it) } }
            refreshServices()
        }
    }

    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
        Image(painterResource(R.drawable.logo_bunah), contentDescription = "BU NAH",
            modifier = Modifier.fillMaxWidth().height(72.dp), contentScale = ContentScale.Fit)
        Text("Kasir 58mm", style = MaterialTheme.typography.headlineSmall)
        if (appVersion.isNotEmpty())
            Text("Versi $appVersion", style = MaterialTheme.typography.labelSmall)
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { tab = 0 }) { Text("Kasir") }
            Button(onClick = { tab = 1; refreshHistory() }) { Text("Riwayat") }
            Button(onClick = { tab = 2; refreshServices() }) { Text("Master") }
        }
        if (tab == 1) {
            Text("Hari ini: Rp $dailyTotal — ${history.size} transaksi",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    exportMode = "csv"
                    saveDocLauncher.launch("bunah-riwayat.csv")
                }) { Text("Export CSV") }
                Button(onClick = {
                    exportMode = "db"
                    saveDocLauncher.launch("bunah-backup.db")
                }) { Text("Backup Database") }
            }
            if (saveMessage.isNotEmpty())
                Text(saveMessage, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp))
            history.forEach { t ->
                Text("${t.orderNo} | ${t.date} | ${t.customer} | Rp ${t.total} | ${t.paymentStatus}",
                    modifier = Modifier.padding(top = 2.dp))
            }
        } else if (tab == 0) {
        // ---- Printer card ----
        Text("Printer Bluetooth", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        if (!btGranted) {
            Text("Izin Bluetooth belum diberikan. Aplikasi butuh izin untuk mencari printer.",
                modifier = Modifier.padding(top = 4.dp))
            Button(onClick = { permLauncher.launch(btPermissions()) },
                modifier = Modifier.padding(top = 4.dp)) { Text("Minta Izin Bluetooth") }
        } else {
            if (!btOn) {
                Text("Bluetooth HP mati.", modifier = Modifier.padding(top = 4.dp))
                Button(onClick = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                    modifier = Modifier.padding(top = 4.dp)) { Text("Nyalakan Bluetooth") }
            }
            ExposedDropdownMenuBox(expanded = btMenuOpen, onExpandedChange = { btMenuOpen = it }) {
                OutlinedTextField(
                    value = selectedDevice()?.let { "${safeName(it)}" } ?: "- pilih printer -",
                    onValueChange = {}, readOnly = true, label = { Text("Printer") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(btMenuOpen) },
                    modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = btMenuOpen, onDismissRequest = { btMenuOpen = false }) {
                    devices.forEach { d ->
                        DropdownMenuItem(
                            text = { Text("${safeName(d)}\n${d.address}") },
                            onClick = { selectedAddr = d.address; btMenuOpen = false })
                    }
                }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { loadDevices() }) { Text("Refresh") }
                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Pair Baru") }
            }
            if (devices.isEmpty())
                Text("Belum ada printer paired. Klik Pair Baru, pair EXP583, lalu Refresh.",
                    modifier = Modifier.padding(top = 4.dp))
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(orderNo, { orderNo = it }, label = { Text("No. Pesanan") },
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                scope.launch {
                    val sug = withContext(Dispatchers.IO) { suggestOrderNo() }
                    orderNo = sug
                }
            }) { Text("No. Baru") }
        }
        OutlinedTextField(customer, { customer = it }, label = { Text("Nama") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ExposedDropdownMenuBox(expanded = jenisMenuOpen, onExpandedChange = { jenisMenuOpen = it }) {
                OutlinedTextField(orderType, {}, readOnly = true, label = { Text("Jenis") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(jenisMenuOpen) },
                    modifier = Modifier.menuAnchor().weight(1f))
                ExposedDropdownMenu(expanded = jenisMenuOpen, onDismissRequest = { jenisMenuOpen = false }) {
                    listOf("Dine In", "Take Away", "Delivery").forEach { j ->
                        DropdownMenuItem(text = { Text(j) },
                            onClick = { orderType = j; jenisMenuOpen = false })
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(tableNo, { tableNo = it }, label = { Text("Meja") },
                modifier = Modifier.width(96.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(discount, { discount = it }, label = { Text("Diskon (kosong = tanpa diskon)") },
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { discountType = "%" }, enabled = discountType != "%") { Text("%") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = { discountType = "Rp" }, enabled = discountType != "Rp") { Text("Rp") }
        }
        Text("Tambah layanan", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        if (services.isEmpty()) {
            Text("Belum ada layanan. Tambahkan di tab Master.", modifier = Modifier.padding(top = 4.dp))
        } else {
            // Selected service is remembered by NAME so it survives filtering/reordering of the catalog.
            val picked = filteredServices().firstOrNull { it.name == pickedName }
                ?: filteredServices().firstOrNull()
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { pickerQuery = ""; pickerOpen = true }) {
                    Text(picked?.let { "${it.name} — ${ReceiptTextFormatter.rupiah(it.price)}" }
                         ?: "Pilih Layanan")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(qtyText, { qtyText = it }, label = { Text("Qty") },
                    modifier = Modifier.width(72.dp))
                Button(onClick = { addToCart() }) { Text("Tambah") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = printLogo, onCheckedChange = { printLogo = it })
                Text("Cetak logo")
            }
            if (picked?.desc?.isNotBlank() == true)
                Text(picked.desc, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp))
        }

        if (pickerOpen) {
            val results = filteredServices()
            AlertDialog(
                onDismissRequest = { pickerOpen = false },
                confirmButton = {
                    TextButton(onClick = { pickerOpen = false }) { Text("Tutup") }
                },
                title = { Text("Pilih Layanan (${results.size} dari ${services.size})") },
                text = {
                    Column {
                        OutlinedTextField(pickerQuery, { pickerQuery = it },
                            label = { Text("Cari layanan") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth())
                        if (results.isEmpty()) {
                            Text("Tidak ada layanan yang cocok.", modifier = Modifier.padding(top = 8.dp))
                        } else {
                            // Bounded height so 300 services behave like 5.
                            LazyColumn(Modifier.heightIn(max = 260.dp).padding(top = 4.dp)) {
                                items(results) { svc ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(svc.name, style = MaterialTheme.typography.bodyLarge)
                                            Text("${svc.desc} — ${ReceiptTextFormatter.rupiah(svc.price)}",
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                        RadioButton(
                                            selected = svc.name == pickedName,
                                            onClick = { pickedName = svc.name; pickerOpen = false })
                                    }
                                }
                            }
                        }
                    }
                })
        }
        cart.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${item.label} — ${ReceiptTextFormatter.rupiah(item.lineTotal)}",
                    modifier = Modifier.weight(1f))
                Button(onClick = { cart.remove(item) }) { Text("Hapus") }
            }
        }
        if (cart.isNotEmpty()) {
            Text("Subtotal: ${ReceiptTextFormatter.rupiah(cartSubtotal())} — " +
                 "TOTAL: ${ReceiptTextFormatter.rupiah(cartGrand())}",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        }
        OutlinedTextField(pay, { pay = it }, label = { Text("Status Pembayaran") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { preview = ReceiptTextFormatter.buildPreview(buildReceipt()) }) { Text("Preview") }
            Button(onClick = {
                scope.launch {
                    status = try {
                        if (!hasBtPermission()) throw Exception("Izin Bluetooth belum diberikan.")
                        val dev = selectedDevice() ?: throw Exception("Pilih printer dulu.")
                        val svc = BluetoothPrinterService(adapter())
                        val r = buildReceipt()
                        withContext(Dispatchers.IO) {
                            svc.print(dev, EscPosBuilder.build(r))
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
                        refreshHistory()
                        "Tercetak + tersimpan ke ${safeName(dev)}"
                    } catch (e: Exception) { "Gagal: ${e.message}" }
                }
            }) { Text("Print") }
            Button(onClick = {
                scope.launch {
                    status = try {
                        if (!hasBtPermission()) throw Exception("Izin Bluetooth belum diberikan.")
                        val dev = selectedDevice() ?: throw Exception("Pilih printer dulu.")
                        val svc = BluetoothPrinterService(adapter())
                        val r = buildReceipt()
                        preview = ReceiptTextFormatter.buildPreview(r)
                        withContext(Dispatchers.IO) { svc.print(dev, EscPosBuilder.build(r)) }
                        "Test terkirim ke ${safeName(dev)} (isi sesuai form)"
                    } catch (e: Exception) { "Gagal: ${e.message}" }
                }
            }) { Text("Test") }
        }
        Text(status, modifier = Modifier.padding(top = 4.dp))
        Text("Preview 58mm (32 kolom):", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        Text(preview, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
        } else if (tab == 2) {
            Text("Master Layanan", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(mName, { mName = it }, label = { Text("Layanan") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(mDesc, { mDesc = it }, label = { Text("Deskripsi") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(mPrice, { mPrice = it }, label = { Text("Harga") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val price = mPrice.trim().toLongOrNull()
                        when {
                            mName.isBlank() -> mStatus = "Nama layanan wajib diisi"
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
                }) { Text("Simpan") }
                Button(onClick = { pendingDelete = mName.trim() },
                    enabled = mName.isNotBlank()) { Text("Hapus Terpilih") }
            }
            if (mSelected != null)
                Text("Terpilih: $mSelected", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 2.dp))
            Text(mStatus, modifier = Modifier.padding(top = 4.dp))

            OutlinedTextField(mFilter, { mFilter = it }, label = { Text("Cari layanan") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            val shown = services.filter {
                mFilter.isBlank() || it.name.contains(mFilter, true) || it.desc.contains(mFilter, true)
            }
            if (shown.isEmpty()) {
                Text("Tidak ada layanan yang cocok.", modifier = Modifier.padding(top = 4.dp))
            } else {
                // Bounded height so 300 services behave like 5.
                LazyColumn(Modifier.heightIn(max = 300.dp).padding(top = 4.dp)) {
                    items(shown) { svc ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable {
                                mSelected = svc.name; mName = svc.name
                                mDesc = svc.desc; mPrice = svc.price.toString()
                            }) {
                                Text(svc.name, style = MaterialTheme.typography.bodyLarge)
                                Text("${svc.desc} — ${ReceiptTextFormatter.rupiah(svc.price)}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { pendingDelete = svc.name }) { Text("✕") }
                        }
                    }
                }
            }
        }

        pendingDelete?.let { name ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                confirmButton = {
                    TextButton(onClick = { val n = name; pendingDelete = null; removeService(n) }) {
                        Text("Hapus")
                    }
                },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Batal") } },
                title = { Text("Hapus layanan?") },
                text = { Text("\"$name\" akan dihapus dari Master Layanan dan dari keranjang.") })
        }
    }
}
