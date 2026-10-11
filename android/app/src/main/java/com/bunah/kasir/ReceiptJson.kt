package com.bunah.kasir

/** items_json format, identical to Bunah.App.Db.ItemsJson on Windows. */
object ReceiptJson {
    fun itemsToJson(items: List<ReceiptItem>): String =
        "[" + items.joinToString(",") { i ->
            "{\"Name\":\"${esc(i.name)}\"," +
                    "\"Desc\":\"${esc(i.desc)}\"," +
                    "\"Price\":${i.price}," +
                    "\"Qty\":${i.qty}}"
        } + "]"

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
