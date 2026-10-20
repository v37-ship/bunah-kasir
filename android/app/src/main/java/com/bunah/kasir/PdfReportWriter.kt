package com.bunah.kasir

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * A4 (595 x 842 pt) report renderer built on Android's PdfDocument.
 * No dependencies; the same generated PDF is used for Save and for PrintManager.
 */
object PdfReportWriter {
    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 36f

    fun write(outFile: File, report: ReportData, profile: ReportProfile, logo: Bitmap?): Int {
        val doc = PdfDocument()
        try {
            val layout = Layout(doc, report, profile, logo)
            layout.render()
            FileOutputStream(outFile).use { doc.writeTo(it) }
            return layout.pageCount
        } catch (e: Exception) {
            outFile.delete()
            throw e
        } finally {
            doc.close()
        }
    }

    private class Layout(
        val doc: PdfDocument,
        val report: ReportData,
        val profile: ReportProfile,
        val logo: Bitmap?,
    ) {
        val accent: Int = runCatching {
            Color.parseColor("#" + profile.accentHex.removePrefix("#"))
        }.getOrDefault(Color.rgb(0, 105, 92))
        val soft: Int = Color.argb(26, Color.red(accent), Color.green(accent), Color.blue(accent))
        val ink: Int = Color.rgb(26, 26, 26)
        val muted: Int = Color.rgb(102, 102, 102)
        val lineColor: Int = Color.rgb(214, 214, 214)
        val danger: Int = Color.rgb(150, 40, 40)

        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f

        val pageCount: Int get() = pageNo

        fun render() {
            newPage(fullHeader = true)
            drawSummary()
            if (report.period.mode == ReportMode.MONTH) {
                drawSectionTitle("Ringkasan Harian")
                drawMonthlyTable()
                finishPage()
                newPage(fullHeader = false)
                drawSectionTitle("Rincian Transaksi")
                if (report.days.isEmpty()) {
                    drawText(canvas!!, "Tidak ada transaksi pada periode ini.",
                        MARGIN, y + 12f, paint(9f, false, muted))
                    y += 20f
                } else {
                    report.days.forEach { day ->
                        drawDayHeader(day)
                        day.transactions.forEach { drawTransaction(it) }
                    }
                }
            } else {
                drawSectionTitle("Rincian Transaksi")
                if (report.transactions.isEmpty()) {
                    drawText(canvas!!, "Tidak ada transaksi pada tanggal ini.",
                        MARGIN, y + 12f, paint(9f, false, muted))
                    y += 20f
                } else {
                    report.transactions.forEach { drawTransaction(it) }
                }
            }
            drawGrandTotal()
            finishPage()
        }

        fun finishPage() {
            val p = page ?: return
            val c = p.canvas
            c.drawRect(MARGIN, PAGE_H - 30f, PAGE_W - MARGIN, PAGE_H - 29.6f,
                fillPaint(lineColor))
            drawText(c, profile.appName, MARGIN, PAGE_H - 18f, paint(7f, false, muted))
            drawRight(c, "Halaman $pageNo", PAGE_W - MARGIN, PAGE_H - 18f,
                paint(7f, false, muted))
            doc.finishPage(p)
            page = null
            canvas = null
        }

        fun newPage(fullHeader: Boolean) {
            finishPage()
            pageNo++
            val p = doc.startPage(
                PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            page = p
            canvas = p.canvas
            y = MARGIN
            if (fullHeader) drawFullHeader() else drawContinuationHeader()
        }

        private fun ensure(height: Float) {
            if (y + height > PAGE_H - MARGIN - 20f) newPage(fullHeader = false)
        }

        private fun drawFullHeader() {
            val c = canvas ?: return
            var textX = MARGIN
            val logoBox = 52f
            if (logo != null) {
                val scale = min(logoBox / logo.width, logoBox / logo.height)
                val w = logo.width * scale
                val h = logo.height * scale
                c.drawBitmap(logo, null,
                    RectF(MARGIN, MARGIN, MARGIN + w, MARGIN + h), null)
                textX = MARGIN + logoBox + 12f
            }
            val right = PAGE_W - MARGIN
            val leftWidth = right - textX - 150f
            drawText(c, profile.brand.name, textX, MARGIN + 14f, paint(16f, true))
            drawText(c, profile.brand.tagline, textX, MARGIN + 27f,
                paint(8.5f, false, muted))
            drawTextEllipsized(c, profile.brand.address, textX, MARGIN + 40f,
                paint(8f, false, muted), leftWidth)
            drawTextEllipsized(c, profile.brand.contact, textX, MARGIN + 52f,
                paint(8f, false, muted), leftWidth)

            drawRight(c, "LAPORAN ${report.period.slug.uppercase()}", right,
                MARGIN + 14f, paint(13f, true, accent))
            drawRight(c, report.period.title, right, MARGIN + 30f, paint(10f, true))
            drawRight(c, "Dibuat ${report.generatedAt}", right, MARGIN + 43f,
                paint(7.5f, false, muted))
            c.drawRect(MARGIN, MARGIN + 64f, right, MARGIN + 65.6f, fillPaint(accent))
            y = MARGIN + 82f
        }

        private fun drawContinuationHeader() {
            val c = canvas ?: return
            val right = PAGE_W - MARGIN
            drawText(c, profile.brand.name, MARGIN, MARGIN + 10f, paint(9f, true))
            drawRight(c, "Laporan ${report.period.slug} ${report.period.title}",
                right, MARGIN + 10f, paint(8f, false, muted))
            c.drawRect(MARGIN, MARGIN + 17f, right, MARGIN + 17.6f, fillPaint(lineColor))
            y = MARGIN + 32f
        }

        private fun drawSummary() {
            ensure(70f)
            val c = canvas ?: return
            val top = y
            val box = RectF(MARGIN, top, PAGE_W - MARGIN, top + 54f)
            c.drawRoundRect(box, 8f, 8f, fillPaint(soft))
            val inner = box.width() - 24f
            val colW = inner / 4f
            val metrics = listOf(
                "TRANSAKSI" to report.count.toString(),
                "SUBTOTAL" to rupiah(report.subtotal),
                "DISKON" to rupiah(report.discount),
                "TOTAL" to rupiah(report.total),
            )
            metrics.forEachIndexed { i, (label, value) ->
                val x = box.left + 12f + i * colW
                drawText(c, label, x, top + 18f, paint(7f, false, muted))
                drawTextEllipsized(c, value, x, top + 37f,
                    paint(if (i == 3) 11f else 9.5f, true), colW - 8f)
            }
            y = top + 62f
            if (report.payments.isNotEmpty()) {
                val payText = report.payments.joinToString("   ") {
                    "${it.label}: ${it.count} (${rupiah(it.total)})"
                }
                drawTextEllipsized(c, payText, MARGIN, y + 4f,
                    paint(7.5f, false, muted), PAGE_W - 2 * MARGIN)
                y += 14f
            }
        }

        private fun drawSectionTitle(title: String) {
            ensure(26f)
            drawText(canvas!!, title, MARGIN, y + 12f, paint(11f, true))
            y += 18f
        }

        private fun drawMonthlyTable() {
            val cols = floatArrayOf(150f, 70f, 100f, 90f, 113f)
            val headers = arrayOf("Tanggal", "Transaksi", "Subtotal", "Diskon", "Total")
            drawTableHeader(cols, headers)
            report.days.forEach { day ->
                val before = pageNo
                ensure(18f)
                if (pageNo != before) drawTableHeader(cols, headers)
                drawTableRow(cols, arrayOf(
                    ReportDataBuilder.longDate(day.date),
                    day.count.toString(), rupiah(day.subtotal),
                    rupiah(day.discount), rupiah(day.total)), bold = false)
            }
            ensure(24f)
            drawTableRow(cols, arrayOf(
                "TOTAL", report.count.toString(), rupiah(report.subtotal),
                rupiah(report.discount), rupiah(report.total)),
                bold = true, topLine = true)
            y += 8f
        }

        private fun drawTableHeader(cols: FloatArray, headers: Array<String>) {
            ensure(22f)
            val c = canvas ?: return
            c.drawRect(MARGIN, y, PAGE_W - MARGIN, y + 18f, fillPaint(accent))
            var x = MARGIN
            headers.forEachIndexed { i, header ->
                if (i == 0) {
                    drawText(c, header, x + 5f, y + 12.5f,
                        paint(8f, true, Color.WHITE))
                } else {
                    drawRight(c, header, x + cols[i] - 5f, y + 12.5f,
                        paint(8f, true, Color.WHITE))
                }
                x += cols[i]
            }
            y += 18f
        }

        private fun drawTableRow(
            cols: FloatArray,
            values: Array<String>,
            bold: Boolean,
            topLine: Boolean = false,
        ) {
            val c = canvas ?: return
            if (topLine) {
                c.drawRect(MARGIN, y, PAGE_W - MARGIN, y + 0.8f, fillPaint(lineColor))
            }
            val baseline = y + 12f
            var x = MARGIN
            values.forEachIndexed { i, value ->
                val color = if (i == 0) ink else ink
                if (i == 0) {
                    drawTextEllipsized(c, value, x + 5f, baseline,
                        paint(8.5f, bold, color), cols[i] - 10f)
                } else {
                    drawRight(c, value, x + cols[i] - 5f, baseline,
                        paint(8.5f, bold, color))
                }
                x += cols[i]
            }
            y += 17f
        }

        private fun drawDayHeader(day: ReportDay) {
            ensure(32f)
            val c = canvas ?: return
            val top = y
            c.drawRect(MARGIN, top, PAGE_W - MARGIN, top + 22f, fillPaint(soft))
            drawText(c, ReportDataBuilder.longDate(day.date), MARGIN + 8f, top + 15f,
                paint(9.5f, true, accent))
            drawRight(c, "${day.count} transaksi · ${rupiah(day.total)}",
                PAGE_W - MARGIN - 8f, top + 15f, paint(8f, true))
            y = top + 28f
        }

        private fun drawTransaction(tx: ReportTransaction) {
            ensure(58f)
            var c = canvas ?: return
            val right = PAGE_W - MARGIN
            val head = buildString {
                append(tx.time)
                if (profile.showOrderNo && tx.orderNo.isNotBlank()) {
                    append("  #").append(tx.orderNo)
                }
                if (tx.customer.isNotBlank()) append("  ").append(tx.customer)
            }
            drawTextEllipsized(c, head, MARGIN, y + 10f, paint(9.5f, true),
                right - MARGIN - 100f)
            drawRight(c, rupiah(tx.total), right, y + 10f, paint(9.5f, true))
            y += 15f

            val meta = listOf(tx.detail, tx.payment).filter { it.isNotBlank() }
                .joinToString("   ·   ")
            if (meta.isNotEmpty()) {
                drawTextEllipsized(c, meta, MARGIN, y + 3f,
                    paint(7.5f, false, muted), right - MARGIN)
                y += 11f
            }

            if (tx.items.isEmpty()) {
                drawText(c, "(tanpa item)", MARGIN + 6f, y + 8f,
                    paint(8f, false, muted))
                y += 12f
            } else {
                val namePaint = paint(8.5f)
                tx.items.forEach { item ->
                    ensure(14f)
                    c = canvas ?: return
                    val text = "${item.qty} x ${item.name}"
                    val tp = TextPaint(namePaint)
                    val layout = StaticLayout.Builder
                        .obtain(text, 0, text.length, tp, 380)
                        .setMaxLines(2)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .setIncludePad(false)
                        .build()
                    val top = y
                    c.save()
                    c.translate(MARGIN + 6f, top)
                    layout.draw(c)
                    c.restore()
                    drawRight(c, rupiah(item.lineTotal), right, top + 9f,
                        paint(8.5f))
                    y = top + maxOf(12f, layout.height.toFloat())
                }
            }

            ensure(18f)
            c = canvas ?: return
            drawTextEllipsized(c,
                "Subtotal ${rupiah(tx.subtotal)}   ·   Diskon ${rupiah(tx.discount)}",
                MARGIN, y + 9f, paint(7.5f, false, muted), right - MARGIN - 130f)
            drawRight(c, "TOTAL ${rupiah(tx.total)}", right, y + 9f,
                paint(9f, true, accent))
            y += 14f
            c.drawRect(MARGIN, y, right, y + 0.7f, fillPaint(lineColor))
            y += 8f
        }

        private fun drawGrandTotal() {
            ensure(58f)
            val c = canvas ?: return
            val right = PAGE_W - MARGIN
            c.drawRect(MARGIN, y, right, y + 1.2f, fillPaint(accent))
            y += 10f
            drawText(c, "TOTAL ${report.period.title}", MARGIN, y + 12f,
                paint(10f, true))
            drawRight(c, rupiah(report.total), right, y + 14f,
                paint(14f, true, accent))
            y += 22f
            val qty = report.items.sumOf { it.qty }
            drawText(c, "${report.count} transaksi · $qty item terjual",
                MARGIN, y + 8f, paint(8f, false, muted))
            y += 12f
            if (report.discount > 0) {
                drawText(c, "Diskon ${rupiah(report.discount)}", MARGIN, y + 8f,
                    paint(8f, false, danger))
                y += 12f
            }
        }

        // ---------------- primitives ----------------

        private fun paint(size: Float, bold: Boolean = false, color: Int = ink): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = Typeface.create(
                    Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }

        private fun fillPaint(color: Int): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.FILL
            }

        private fun drawText(c: Canvas, text: String, x: Float, baseline: Float, p: Paint) {
            c.drawText(text, x, baseline, p)
        }

        private fun drawRight(c: Canvas, text: String, right: Float, baseline: Float, p: Paint) {
            c.drawText(text, right - p.measureText(text), baseline, p)
        }

        private fun drawTextEllipsized(
            c: Canvas, text: String, x: Float, baseline: Float, p: Paint, maxWidth: Float,
        ) {
            if (maxWidth <= 0f) return
            if (p.measureText(text) <= maxWidth) {
                c.drawText(text, x, baseline, p)
                return
            }
            var lo = 0
            var hi = text.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                val candidate = text.substring(0, mid) + "..."
                if (p.measureText(candidate) <= maxWidth) lo = mid else hi = mid - 1
            }
            c.drawText(text.substring(0, lo) + "...", x, baseline, p)
        }

        private fun rupiah(v: Long) = ReceiptTextFormatter.rupiah(v)
    }
}
