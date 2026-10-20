package com.bunah.kasir

import org.json.JSONArray

/** items_json format, identical to Bunah.App.Db.ItemsJson on Windows. */
object ReceiptJson {
    fun itemsToJson(items: List<ReceiptItem>): String =
        "[" + items.joinToString(",") { i ->
            "{\"Name\":\"${esc(i.name)}\"," +
                    "\"Desc\":\"${esc(i.desc)}\"," +
                    "\"Price\":${i.price}," +
                    "\"Qty\":${i.qty}}"
        } + "]"

    /** Used by the cart Saver so an in-progress order survives rotation/process death. */
    fun itemsFromJson(json: String): List<ReceiptItem> {
        if (json.isBlank()) return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ReceiptItem(o.getString("Name"), o.optString("Desc"), o.getLong("Price"))
                .apply { qty = o.optInt("Qty", 1).coerceAtLeast(1) }
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
