package com.bunah.kasir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class ReportTest {
    private val profile = ReportProfile(
        ReportBrand("BU NAH", "Sehat", "Jl. Test 1", "0812"),
        appName = "Bunah Kasir",
        fileTag = "Bunah",
        showOrderNo = true,
        detailLabel = "Jenis / Meja",
        accentHex = "B3261E",
    )

    private fun item(name: String, price: Long, qty: Int) =
        ReceiptItem(name, "", price).apply { this.qty = qty }

    private fun tx(
        id: Long,
        date: String,
        time: String = "10:00 WIB",
        items: List<ReceiptItem> = emptyList(),
        subtotal: Long = items.sumOf { it.lineTotal },
        total: Long = subtotal,
        payment: String = "LUNAS Qris",
    ) = ReportTransaction(
        id = id, date = date, time = time, orderNo = "B-" + id.toString().padStart(3, '0'),
        customer = "Pelanggan $id", detail = "", items = items,
        subtotal = subtotal, discount = (subtotal - total).coerceAtLeast(0),
        total = total, payment = payment,
    )

    private val oct20 = listOf(
        tx(1, "20/10/2026", "09:15 WIB",
            listOf(item("Baby Massage", 50000, 2)), total = 90000, payment = "LUNAS Qris"),
        tx(2, "20/10/2026", "14:30 WIB",
            listOf(item("Baby Swim", 25000, 1)), payment = "LUNAS Cash"),
        tx(3, "19/10/2026", items = listOf(item("Baby Swim", 25000, 1))),
        tx(4, "20/11/2026", items = listOf(item("Baby Swim", 25000, 1))),
    )

    @Test
    fun dayFilterTotalsItemsAndPayments() {
        val report = ReportDataBuilder.build(
            oct20, ReportPeriod(ReportMode.DAY, 2026, 10, 20), "now")

        assertEquals(2, report.count)
        assertEquals(1, report.days.size)
        assertEquals(125000L, report.subtotal)
        assertEquals(10000L, report.discount)
        assertEquals(115000L, report.total)
        assertEquals(listOf(1L, 2L), report.transactions.map { it.id })

        assertEquals(2, report.items.size)
        assertEquals("Baby Massage", report.items[0].name)
        assertEquals(2, report.items[0].qty)
        assertEquals(100000L, report.items[0].revenue)
        assertEquals(25000L, report.items[1].revenue)

        assertEquals(listOf("LUNAS Qris", "LUNAS Cash"), report.payments.map { it.label })
        assertEquals(1, report.payments[0].count)
        assertEquals(90000L, report.payments[0].total)
    }

    @Test
    fun monthFilterIncludesOnlyThatMonth() {
        val all = oct20 + listOf(
            tx(5, "01/10/2026"), tx(6, "31/10/2026"), tx(7, "30/09/2026"))
        val report = ReportDataBuilder.build(
            all, ReportPeriod(ReportMode.MONTH, 2026, 10, 1), "now")
        assertEquals(5, report.count)
        assertEquals(listOf("19/10/2026", "20/10/2026", "01/10/2026", "31/10/2026").sorted(),
            report.days.map { it.date }.sorted())
    }

    @Test
    fun leapFebruaryAndMonthBoundary() {
        val all = listOf(
            tx(1, "28/02/2028"), tx(2, "29/02/2028"), tx(3, "01/03/2028"))
        val report = ReportDataBuilder.build(
            all, ReportPeriod(ReportMode.MONTH, 2028, 2, 1), "now")
        assertEquals(2, report.count)
        assertEquals(listOf("28/02/2028", "29/02/2028"), report.days.map { it.date })
    }

    @Test
    fun emptyPeriodIsEmptyNotCrash() {
        val report = ReportDataBuilder.build(
            oct20, ReportPeriod(ReportMode.DAY, 2026, 12, 31), "now")
        assertEquals(0, report.count)
        assertEquals(0L, report.total)
        assertTrue(report.days.isEmpty())
        assertTrue(report.items.isEmpty())
        assertTrue(report.payments.isEmpty())
    }

    @Test
    fun labelsAndFileCodes() {
        val day = ReportPeriod(ReportMode.DAY, 2026, 10, 20)
        assertEquals("20 Oktober 2026", day.title)
        assertEquals("2026-10-20", day.fileCode)
        assertEquals("Harian", day.slug)
        val month = ReportPeriod(ReportMode.MONTH, 2026, 10, 1)
        assertEquals("Oktober 2026", month.title)
        assertEquals("2026-10", month.fileCode)
        assertEquals("20 Oktober 2026", ReportDataBuilder.longDate("20/10/2026"))
    }

    @Test
    fun xlsxHasValidPartsAndExpectedData() {
        val report = ReportDataBuilder.build(
            oct20, ReportPeriod(ReportMode.DAY, 2026, 10, 20), "20/10/2026 15:00")
        val bytes = ByteArrayOutputStream().also {
            XlsxReportWriter.write(it, report, profile)
        }.toByteArray()
        val parts = unzip(bytes)

        assertTrue(parts.containsKey("[Content_Types].xml"))
        assertTrue(parts.containsKey("xl/workbook.xml"))
        assertTrue(parts.containsKey("xl/styles.xml"))
        assertTrue(parts.containsKey("xl/worksheets/sheet1.xml"))
        assertTrue(parts.containsKey("xl/worksheets/sheet2.xml"))
        assertTrue(parts.containsKey("xl/worksheets/sheet3.xml"))

        // Every XML part must be well-formed (catches escaping bugs).
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }
            .forEach { (name, text) ->
                DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(InputSource(StringReader(text)))
                assertTrue("empty part $name", text.isNotEmpty())
            }

        val summary = parts.getValue("xl/worksheets/sheet1.xml")
        val transaksi = parts.getValue("xl/worksheets/sheet2.xml")
        val items = parts.getValue("xl/worksheets/sheet3.xml")
        assertTrue(summary.contains("BU NAH"))
        assertTrue(summary.contains("115000"))
        assertTrue(transaksi.contains("No. Pesanan"))
        assertTrue(transaksi.contains("B-001"))
        assertTrue(transaksi.contains("Pelanggan 1"))
        assertTrue(transaksi.contains("Baby Massage x2"))
        assertTrue(transaksi.contains("90000"))
        assertTrue(items.contains("Baby Massage"))
        assertTrue(items.contains("100000"))

        val out = File("build/tmp/report-sample.xlsx")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
    }


    @Test
    fun monthXlsxHasDailySheetAndEscapesText() {
        val all = listOf(
            tx(1, "01/10/2026", items = listOf(item("Nasi & Ayam", 30000, 2)))
                .copy(customer = "A & B <test> \"x\""),
            tx(2, "02/10/2026", items = listOf(item("Es <Teh>", 8000, 3))),
        )
        val report = ReportDataBuilder.build(
            all, ReportPeriod(ReportMode.MONTH, 2026, 10, 1), "20/10/2026 15:00")
        val bytes = ByteArrayOutputStream().also {
            XlsxReportWriter.write(it, report, profile)
        }.toByteArray()
        val parts = unzip(bytes)

        assertTrue(parts.getValue("xl/workbook.xml").contains("name=\"Harian\""))
        assertTrue(parts.getValue("xl/workbook.xml").contains("name=\"Transaksi\""))
        assertTrue(parts.getValue("xl/worksheets/sheet2.xml").contains("1 Oktober 2026"))
        assertTrue(parts.getValue("xl/worksheets/sheet3.xml")
            .contains("A &amp; B &lt;test&gt; &quot;x&quot;"))
        parts.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }
            .forEach { (_, text) ->
                DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(InputSource(StringReader(text)))
            }

        val out = File("build/tmp/report-sample-month.xlsx")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }
}
