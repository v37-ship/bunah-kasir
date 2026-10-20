package com.bunah.kasir

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil

/**
 * Dependency-free .xlsx writer.
 *
 * An xlsx file is a ZIP of XML parts. We use inline strings (no sharedStrings.xml)
 * and built-in number format 3 (#,##0), which keeps the file small and Excel-compatible.
 */
object XlsxReportWriter {
    private const val MAX_CELL_CHARS = 32000

    private data class Cell(val value: Any, val style: Int = 0)
    private data class Row(val cells: List<Cell>, val height: Float? = null)
    private class Sheet(
        val name: String,
        val rows: List<Row>,
        val widths: List<Double>,
        val freezeRows: Int = 0,
        val filterLastRow: Int = 0,
    )

    fun write(out: OutputStream, report: ReportData, profile: ReportProfile) {
        val sheets = buildList {
            add(summarySheet(report, profile))
            if (report.period.mode == ReportMode.MONTH) add(dailySheet(report))
            add(transactionsSheet(report, profile))
            add(itemsSheet(report))
        }
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            entry(zip, "[Content_Types].xml", contentTypes(sheets.size))
            entry(zip, "_rels/.rels", rootRels())
            entry(zip, "xl/workbook.xml", workbook(sheets))
            entry(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            entry(zip, "xl/styles.xml", styles(profile))
            sheets.forEachIndexed { i, sheet ->
                entry(zip, "xl/worksheets/sheet${i + 1}.xml", sheetXml(sheet))
            }
        }
    }

    // ---------------- sheets ----------------

    private fun summarySheet(r: ReportData, p: ReportProfile): Sheet {
        val rows = mutableListOf<Row>()
        rows += Row(listOf(Cell("LAPORAN ${r.period.slug.uppercase()}", 4)), 24f)
        rows += Row(listOf(Cell(p.appName)), 20f)
        rows += Row(emptyList<Cell>(), 8f)
        rows += Row(listOf(Cell("Toko", 3), Cell(p.brand.name)))
        rows += Row(listOf(Cell("Alamat", 3), Cell(p.brand.address)))
        rows += Row(listOf(Cell("Kontak", 3), Cell(p.brand.contact)))
        rows += Row(listOf(Cell("Periode", 3), Cell(r.period.title)))
        rows += Row(listOf(Cell("Dibuat", 3), Cell(r.generatedAt)))
        rows += Row(emptyList<Cell>(), 8f)
        rows += Row(listOf(Cell("Jumlah transaksi", 3), Cell(r.count.toLong())))
        rows += Row(listOf(Cell("Subtotal", 3), Cell(r.subtotal, 2)))
        rows += Row(listOf(Cell("Diskon", 3), Cell(r.discount, 2)))
        rows += Row(listOf(Cell("Total", 3), Cell(r.total, 6)))
        rows += Row(emptyList<Cell>(), 8f)
        rows += Row(listOf(
            Cell("Pembayaran", 1), Cell("Transaksi", 1), Cell("Total", 1)), 20f)
        r.payments.forEach { pay ->
            rows += Row(listOf(
                Cell(pay.label), Cell(pay.count.toLong(), 0), Cell(pay.total, 2)))
        }
        return Sheet("Ringkasan", rows, listOf(28.0, 24.0, 18.0))
    }

    private fun dailySheet(r: ReportData): Sheet {
        val rows = mutableListOf<Row>()
        rows += Row(listOf(Cell("Tanggal", 1), Cell("Transaksi", 1),
            Cell("Subtotal", 1), Cell("Diskon", 1), Cell("Total", 1)), 20f)
        r.days.forEach { d ->
            rows += Row(listOf(
                Cell(ReportDataBuilder.longDate(d.date)),
                Cell(d.count.toLong()), Cell(d.subtotal, 2),
                Cell(d.discount, 2), Cell(d.total, 2)))
        }
        rows += Row(listOf(
            Cell("TOTAL", 3), Cell(r.count.toLong(), 3),
            Cell(r.subtotal, 6), Cell(r.discount, 6), Cell(r.total, 6)))
        return Sheet("Harian", rows, listOf(16.0, 12.0, 15.0, 12.0, 15.0),
            freezeRows = 1, filterLastRow = 1 + r.days.size)
    }

    private fun transactionsSheet(r: ReportData, p: ReportProfile): Sheet {
        val headers = mutableListOf("No", "Tanggal", "Waktu")
        val widths = mutableListOf(6.0, 12.0, 11.0)
        if (p.showOrderNo) {
            headers += "No. Pesanan"; widths += 13.0
        }
        headers += listOf("Pelanggan", p.detailLabel, "Item",
            "Subtotal", "Diskon", "Total", "Pembayaran")
        widths += listOf(22.0, 22.0, 55.0, 14.0, 12.0, 14.0, 17.0)

        val rows = mutableListOf<Row>()
        rows += Row(headers.map { Cell(it, 1) }, 22f)
        r.transactions.forEachIndexed { index, tx ->
            val cells = mutableListOf<Cell>()
            cells += Cell((index + 1).toLong())
            cells += Cell(tx.date)
            cells += Cell(tx.time)
            if (p.showOrderNo) cells += Cell(tx.orderNo)
            cells += Cell(tx.customer)
            cells += Cell(tx.detail)
            val itemText = tx.items.joinToString("; ") { "${it.name} x${it.qty}" }
            cells += Cell(itemText, 5)
            cells += Cell(tx.subtotal, 2)
            cells += Cell(tx.discount, 2)
            cells += Cell(tx.total, 2)
            cells += Cell(tx.payment)
            rows += Row(cells, rowHeight(itemText, tx.customer, tx.detail))
        }
        val totalIdx = headers.indexOf("Subtotal")
        val totalRow = MutableList(headers.size) { Cell("") }
        totalRow[0] = Cell("TOTAL", 3)
        if (totalIdx >= 0) {
            totalRow[totalIdx] = Cell(r.subtotal, 6)
            totalRow[totalIdx + 1] = Cell(r.discount, 6)
            totalRow[totalIdx + 2] = Cell(r.total, 6)
        }
        rows += Row(totalRow, 20f)

        return Sheet("Transaksi", rows, widths,
            freezeRows = 1,
            filterLastRow = if (r.transactions.isEmpty()) 0 else 1 + r.transactions.size)
    }

    private fun itemsSheet(r: ReportData): Sheet {
        val rows = mutableListOf<Row>()
        rows += Row(listOf(Cell("Item", 1), Cell("Qty", 1), Cell("Pendapatan", 1)), 20f)
        r.items.forEach { item ->
            rows += Row(listOf(
                Cell(item.name), Cell(item.qty.toLong()), Cell(item.revenue, 2)))
        }
        rows += Row(listOf(
            Cell("TOTAL", 3),
            Cell(r.items.sumOf { it.qty }.toLong(), 3),
            Cell(r.items.sumOf { it.revenue }, 6)))
        return Sheet("Item", rows, listOf(42.0, 8.0, 16.0),
            freezeRows = 1, filterLastRow = if (r.items.isEmpty()) 0 else 1 + r.items.size)
    }

    private fun rowHeight(vararg values: String): Float {
        val maxChars = values.maxOfOrNull { it.length } ?: 0
        val lines = ceil(maxChars / 48.0).toInt().coerceIn(1, 6)
        return if (lines == 1) 18f else 14f * lines
    }

    // ---------------- xml parts ----------------

    private fun contentTypes(sheetCount: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        for (i in 1..sheetCount) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        append("</Types>")
    }

    private fun rootRels() =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>""" +
            """</Relationships>"""

    private fun workbook(sheets: List<Sheet>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        append("""<bookViews><workbookView/></bookViews><sheets>""")
        sheets.forEachIndexed { i, sheet ->
            append("""<sheet name="${esc(sheet.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(sheetCount: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..sheetCount) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("""<Relationship Id="rId${sheetCount + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private fun styles(p: ReportProfile): String {
        val accent = p.accentHex.removePrefix("#").uppercase(LocaleHolder.locale)
            .filter { it in "0123456789ABCDEF" }.padEnd(6, '0').take(6)
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<fonts count="5">""" +
            """<font><sz val="11"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="11"/><name val="Calibri"/></font>""" +
            """<font><b/><sz val="14"/><name val="Calibri"/></font>""" +
            """<font><sz val="9"/><color rgb="FF666666"/><name val="Calibri"/></font>""" +
            """</fonts>""" +
            """<fills count="3">""" +
            """<fill><patternFill patternType="none"/></fill>""" +
            """<fill><patternFill patternType="gray125"/></fill>""" +
            """<fill><patternFill patternType="solid"><fgColor rgb="FF$accent"/><bgColor indexed="64"/></patternFill></fill>""" +
            """</fills>""" +
            """<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>""" +
            """<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>""" +
            """<cellXfs count="7">""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>""" +
            """<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf>""" +
            """<xf numFmtId="3" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>""" +
            """<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
            """<xf numFmtId="0" fontId="3" fillId="0" borderId="0" xfId="0" applyFont="1"/>""" +
            """<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf>""" +
            """<xf numFmtId="3" fontId="2" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/>""" +
            """</cellXfs>""" +
            """<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""" +
            """</styleSheet>"""
    }

    private fun sheetXml(sheet: Sheet): String {
        val maxCols = maxOf(
            sheet.widths.size,
            sheet.rows.maxOfOrNull { it.cells.size } ?: 0,
        )
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            append("""<sheetViews><sheetView workbookViewId="0">""")
            if (sheet.freezeRows > 0) {
                append("""<pane ySplit="${sheet.freezeRows}" topLeftCell="A${sheet.freezeRows + 1}" activePane="bottomLeft" state="frozen"/>""")
            }
            append("""</sheetView></sheetViews><sheetFormatPr defaultRowHeight="15"/><cols>""")
            for (i in 0 until maxCols) {
                val w = sheet.widths.getOrElse(i) { 14.0 }
                append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""")
            }
            append("</cols><sheetData>")
            sheet.rows.forEachIndexed { r, row ->
                append("""<row r="${r + 1}"""")
                row.height?.let { append(""" ht="$it" customHeight="1"""") }
                append(">")
                row.cells.forEachIndexed { c, cell ->
                    val ref = "${colName(c)}${r + 1}"
                    when (val v = cell.value) {
                        is Long -> append(
                            """<c r="$ref" s="${cell.style}"><v>$v</v></c>""")
                        is Int -> append(
                            """<c r="$ref" s="${cell.style}"><v>$v</v></c>""")
                        is String -> if (v.isNotEmpty()) append(
                            """<c r="$ref" t="inlineStr" s="${cell.style}"><is><t xml:space="preserve">${esc(v)}</t></is></c>""")
                    }
                }
                append("</row>")
            }
            append("</sheetData>")
            if (sheet.filterLastRow > 1) {
                append("""<autoFilter ref="A1:${colName(maxCols - 1)}${sheet.filterLastRow}"/>""")
            }
            append("""<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>""")
            append("</worksheet>")
        }
    }

    private fun entry(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun colName(index: Int): String {
        var i = index + 1
        val sb = StringBuilder()
        while (i > 0) {
            val r = (i - 1) % 26
            sb.append(('A' + r))
            i = (i - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun esc(s: String): String {
        val text = if (s.length > MAX_CELL_CHARS) s.substring(0, MAX_CELL_CHARS) else s
        val b = StringBuilder(text.length + 16)
        for (ch in text) {
            when (ch) {
                '&' -> b.append("&amp;")
                '<' -> b.append("&lt;")
                '>' -> b.append("&gt;")
                '"' -> b.append("&quot;")
                '\'' -> b.append("&apos;")
                '\n' -> b.append("&#10;")
                '\r' -> Unit
                '\t' -> b.append(ch)
                else -> if (ch.code >= 32) b.append(ch)
            }
        }
        return b.toString()
    }

    /** Avoids the default-locale uppercase surprises (e.g. Turkish dotless i). */
    private object LocaleHolder {
        val locale = java.util.Locale.US
    }
}
