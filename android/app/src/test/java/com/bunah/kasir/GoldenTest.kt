package com.bunah.kasir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cross-language golden check: the same fixtures that tools/dotnet-smoke (C#) and
 * tools/verify_receipt.py (Python) compile against. If any of the three drift, this fails.
 * Fixtures are copied from ../../shared-spec/golden by the `copyGolden` Gradle task.
 */
class GoldenTest {

    private fun fixture(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(name)
            ?: error("golden fixture $name not on test classpath")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun base() = sampleReceipt()

    private fun cases() = mapOf(
        "promo-none" to base(),
        "promo-filled" to base().copy(orderNo = "002"),
        "discount-10pct" to base().copy(discountType = "%", discountValue = 10),
        "discount-nominal" to base().copy(discountType = "Rp", discountValue = 5000),
        "discount-clamped" to base().copy(discountType = "%", discountValue = 150),
        "delivery" to base().copy(orderType = "Delivery", customerName = "", orderNo = "012"),
        "dine-in-meja" to base().copy(orderType = "Dine In", tableNo = "7"),
        "qty-multi" to base().copy(
            items = base().items.mapIndexed { i, it -> if (i == 0) it.apply { qty = 2 } else it })
    )

    @Test
    fun `preview matches golden fixtures`() {
        for ((name, receipt) in cases()) {
            val text = fixture("$name.txt")
            assertTrue("golden $name.txt is empty", text.isNotBlank())
            val expected = text.split("\n").drop(1).joinToString("\n").trimEnd('\n')
            val actual = ReceiptTextFormatter.buildPreview(receipt)
            assertEquals("fixture $name diverged from golden", expected, actual)
        }
    }

    @Test
    fun `all lines within 32 columns`() {
        for ((_, receipt) in cases())
            for (line in ReceiptTextFormatter.buildPreview(receipt).split("\n"))
                assertTrue("line > 32 cols: '$line'", line.length <= 32)
    }

    @Test
    fun `footer fits one line each`() {
        for ((_, receipt) in cases())
            for (f in receipt.footerLines)
                assertEquals("footer wraps: $f", 1, ReceiptTextFormatter.center(f).split("\n").size)
    }

    @Test
    fun `bytes start with init and end with cut`() {
        for ((_, receipt) in cases()) {
            val b = EscPosBuilder.build(receipt)
            assertEquals(0x1B.toByte(), b[0]); assertEquals(0x40.toByte(), b[1])
            assertEquals(0x1D.toByte(), b[b.size - 3])
            assertEquals(0x56.toByte(), b[b.size - 2])
            assertEquals(0x01.toByte(), b[b.size - 1])
        }
    }

    @Test
    fun `discount never exceeds subtotal`() {
        assertEquals(20000L, ReceiptTextFormatter.discountAmount(200000, "%", 10))
        assertEquals(200000L, ReceiptTextFormatter.discountAmount(200000, "%", 150))
        assertEquals(200000L, ReceiptTextFormatter.discountAmount(200000, "Rp", 999999999))
        assertEquals(0L, ReceiptTextFormatter.discountAmount(200000, "", 10))
    }

    @Test
    fun `row labels fit 32 cols and empty name shows dash`() {
        val r = base().copy(customerName = "")
        val preview = ReceiptTextFormatter.buildPreview(r)
        assertTrue("empty name must show dash",
            preview.split("\n").any { it == "Nama       : -" })
        for (row in listOf("No. Pesanan", "Tanggal", "Nama", "Jenis", "Meja"))
            assertTrue("label row missing: $row",
                preview.split("\n").any { it.startsWith(row.padEnd(11) + ": ") })
    }
}
