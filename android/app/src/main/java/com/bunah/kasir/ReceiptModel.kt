package com.bunah.kasir

/** One receipt line. Qty > 1 prints as "Name xN". */
data class ReceiptItem(val name: String, val desc: String, val price: Long) {
    var qty: Int = 1
    val lineTotal: Long get() = price * qty
    val label: String get() = if (qty > 1) "$name x$qty" else name
}

data class StoreInfo(val name: String, val tagline: String, val address: String,
                     val hours: String, val contact: String)
data class Receipt(
    val store: StoreInfo,
    /** Order number. User-editable so a revised receipt can reuse/increment it manually. */
    val orderNo: String,
    val dateTimeText: String,
    val customerName: String,
    val orderType: String,
    val tableNo: String,
    val items: List<ReceiptItem>,
    val discountType: String,
    val discountValue: Long,
    val paymentStatus: String,
    val footerLines: List<String>,
    val printLogo: Boolean = true
) {
    /** Sum of line totals — single source of truth, so callers cannot disagree. */
    val subtotal: Long get() = items.sumOf { it.lineTotal }

    /** Payable amount after discount. Never negative. */
    val grandTotal: Long
        get() = subtotal - ReceiptTextFormatter.discountAmount(subtotal, discountType, discountValue)
}

fun sampleReceipt() = Receipt(
    StoreInfo("BU NAH", "Chinese Food Halal",
        "Kayu Putih II No 32, Pulo Gadung, Jaktim",
        "Buka 11.00 - 20.00 WIB",
        "WA 082 1134 7293"),
    "001", "10/10/2026 13:45", "Budi", "Take Away", "-",
    listOf(
        ReceiptItem("Nasgor Ayam", "", 25000),
        ReceiptItem("Kwe Tiauw Ayam", "", 25000),
        ReceiptItem("Es Teh", "", 5000)
    ),
    "", 0L, "LUNAS Qris",
    listOf(
        "TERIMA KASIH",
        "Order & free antar Kayu Putih",
        "*** BU NAH ***"
    )
)
