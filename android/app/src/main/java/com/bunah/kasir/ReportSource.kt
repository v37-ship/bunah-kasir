package com.bunah.kasir

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

val BUNAH_REPORT_PROFILE = ReportProfile(
    brand = ReportBrand(
        "BU NAH",
        "Chinese Food Halal",
        "Kayu Putih II No 32, Pulo Gadung, Jaktim",
        "Buka 11.00 - 20.00 WIB | WA 082 1134 7293",
    ),
    appName = "Bunah Kasir",
    fileTag = "Bunah",
    showOrderNo = true,
    detailLabel = "Jenis / Meja",
    accentHex = "B3261E",
)

fun Tx.toReportTransaction(): ReportTransaction {
    val detail = buildString {
        append(orderType.ifBlank { "-" })
        if (orderType == "Dine In" && tableNo.isNotBlank()) append(" / Meja $tableNo")
    }
    return ReportTransaction(
        id = id,
        date = date,
        time = time,
        orderNo = orderNo,
        customer = customer.ifBlank { "-" },
        detail = detail,
        items = runCatching { ReceiptJson.itemsFromJson(itemsJson) }
            .getOrDefault(emptyList()),
        subtotal = subtotal,
        discount = (subtotal - total).coerceAtLeast(0L),
        total = total,
        payment = paymentStatus.ifBlank { "-" },
    )
}

fun bunahReportLogo(ctx: Context): Bitmap? = runCatching {
    BitmapFactory.decodeResource(
        ctx.resources, R.drawable.logo_bunah,
        BitmapFactory.Options().apply { inScaled = false },
    )
}.getOrNull()
