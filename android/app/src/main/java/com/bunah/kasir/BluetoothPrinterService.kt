package com.bunah.kasir

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.util.UUID

/**
 * EXP583 V2 over Bluetooth Classic SPP. Pair in Android Settings first.
 *
 * Some clones (and a few firmware revisions) reject the *secure* RFCOMM socket, so we try
 * secure -> insecure -> the legacy fallback. Payloads are written in chunks because a
 * single write of a full receipt is routinely truncated by slow serial bridges.
 */
class BluetoothPrinterService(private val adapter: BluetoothAdapter?) {
    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CHUNK = 4096
    }

    @SuppressLint("MissingPermission")
    fun bondedPrinters(): List<BluetoothDevice> =
        adapter?.bondedDevices?.toList() ?: emptyList()

    @SuppressLint("MissingPermission")
    fun print(device: BluetoothDevice, data: ByteArray) {
        try { adapter?.cancelDiscovery() } catch (_: SecurityException) { }

        var last: Exception? = null
        for (makeSocket in arrayOf<(BluetoothDevice) -> BluetoothSocket>({ it.createRfcommSocketToServiceRecord(SPP) },
                                                                       { it.createInsecureRfcommSocketToServiceRecord(SPP) })) {
            var socket: BluetoothSocket? = null
            try {
                socket = makeSocket(device)
                socket.connect()
                val out = socket.outputStream
                var i = 0
                while (i < data.size) {
                    out.write(data, i, minOf(CHUNK, data.size - i))
                    out.flush()
                    i += CHUNK
                }
                return // success
            } catch (e: Exception) {
                last = e
                try { socket?.close() } catch (_: Exception) { }
            }
        }
        throw last ?: Exception("Tidak bisa konek ke printer")
    }
}
