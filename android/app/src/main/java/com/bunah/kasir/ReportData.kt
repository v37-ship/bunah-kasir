package com.bunah.kasir

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Report window. Month is 1-based so it matches what the UI shows. */
enum class ReportMode { DAY, MONTH }

val REPORT_MONTHS_ID = listOf(
    "Januari", "Februari", "Maret", "April", "Mei", "Juni",
    "Juli", "Agustus", "September", "Oktober", "November", "Desember",
)

data class ReportBrand(
    val name: String,
    val tagline: String,
    val address: String,
    val contact: String,
)

data class ReportProfile(
    val brand: ReportBrand,
    val appName: String,
    val fileTag: String,
    val showOrderNo: Boolean,
    val detailLabel: String,
    /** Six hex digits, no leading '#'. */
    val accentHex: String,
)

data class ReportPeriod(
    val mode: ReportMode,
    val year: Int,
    /** 1..12 */
    val month: Int,
    /** 1..31 */
    val day: Int,
) {
    val dateText: String
        get() = "${pad2(day)}/${pad2(month)}/$year"

    val title: String
        get() = if (mode == ReportMode.DAY)
            "$day ${monthName(month)} $year"
        else
            "${monthName(month)} $year"

    /** Stable code used in file names, e.g. 2026-10-20 or 2026-10. */
    val fileCode: String
        get() = if (mode == ReportMode.DAY)
            "$year-${pad2(month)}-${pad2(day)}"
        else
            "$year-${pad2(month)}"

    val slug: String
        get() = if (mode == ReportMode.DAY) "Harian" else "Bulanan"

    fun matches(date: String): Boolean = when (mode) {
        ReportMode.DAY -> date == dateText
        ReportMode.MONTH -> date.endsWith("/${pad2(month)}/$year")
    }

    companion object {
        fun today(mode: ReportMode): ReportPeriod {
            val c = Calendar.getInstance()
            return ReportPeriod(mode, c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        }

        fun fromCalendar(mode: ReportMode, c: Calendar) = ReportPeriod(
            mode, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
        )

        fun pad2(n: Int) = n.toString().padStart(2, '0')

        fun monthName(month: Int) =
            REPORT_MONTHS_ID.getOrElse(month - 1) { "Bulan $month" }
    }
}

data class ReportItem(val name: String, val qty: Int, val revenue: Long)

data class ReportTransaction(
    val id: Long,
    val date: String,
    val time: String,
    val orderNo: String,
    val customer: String,
    /** Free-form secondary line: phone/promo (Wangga) or order type/table (BU NAH). */
    val detail: String,
    val items: List<ReceiptItem>,
    val subtotal: Long,
    /** Derived from the stored subtotal/total, never from today's catalog. */
    val discount: Long,
    val total: Long,
    val payment: String,
)

data class ReportDay(val date: String, val transactions: List<ReportTransaction>) {
    val count: Int get() = transactions.size
    val subtotal: Long get() = transactions.sumOf { it.subtotal }
    val discount: Long get() = transactions.sumOf { it.discount }
    val total: Long get() = transactions.sumOf { it.total }
}

data class PaymentSummary(val label: String, val count: Int, val total: Long)

data class ReportData(
    val period: ReportPeriod,
    val generatedAt: String,
    val days: List<ReportDay>,
    val items: List<ReportItem>,
) {
    val transactions: List<ReportTransaction> get() = days.flatMap { it.transactions }
    val count: Int get() = days.sumOf { it.count }
    val subtotal: Long get() = days.sumOf { it.subtotal }
    val discount: Long get() = days.sumOf { it.discount }
    val total: Long get() = days.sumOf { it.total }

    val payments: List<PaymentSummary>
        get() = transactions
            .groupBy { it.payment.ifBlank { "-" } }
            .map { (label, list) -> PaymentSummary(label, list.size, list.sumOf { it.total }) }
            .sortedByDescending { it.total }
}

object ReportDataBuilder {
    /**
     * Filters by the stored dd/MM/yyyy date, groups by day and aggregates item sales.
     * Totals always come from the transaction's stored subtotal/total.
     */
    fun build(
        all: List<ReportTransaction>,
        period: ReportPeriod,
        generatedAt: String,
    ): ReportData {
        val filtered = all
            .filter { period.matches(it.date) }
            .sortedWith(compareBy({ dateMillis(it.date) ?: Long.MAX_VALUE }, { it.time }))

        // groupBy keeps encounter order, and the list above is already chronological.
        val days = filtered.groupBy { it.date }
            .map { (date, txs) -> ReportDay(date, txs.sortedBy { it.time }) }

        val byItem = LinkedHashMap<String, ReportItem>()
        filtered.forEach { tx ->
            tx.items.forEach { item ->
                val prev = byItem[item.name]
                byItem[item.name] = ReportItem(
                    item.name,
                    (prev?.qty ?: 0) + item.qty,
                    (prev?.revenue ?: 0L) + item.lineTotal,
                )
            }
        }
        val items = byItem.values.sortedWith(
            compareByDescending<ReportItem> { it.revenue }.thenBy { it.name })

        return ReportData(period, generatedAt, days, items)
    }

    fun dateMillis(date: String): Long? = runCatching {
        SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { isLenient = false }
            .parse(date)?.time
    }.getOrNull()

    fun longDate(date: String): String {
        val p = date.split("/")
        if (p.size == 3) {
            val d = p[0].toIntOrNull()
            val m = p[1].toIntOrNull()
            if (d != null && m != null && m in 1..12)
                return "$d ${ReportPeriod.monthName(m)} ${p[2]}"
        }
        return date
    }

    fun nowText(): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date())
}
