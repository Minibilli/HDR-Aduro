package de.ofen.app

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * NBE-Protokoll der Aduro-Hybrid-Öfen (UDP, Port 8483) – Umsetzung nach der
 * Python-Bibliothek pyduro.
 *
 * Anfrage:  AppId(12) Serial(6) ' ' STX Funktion(2) Seq(2) PIN(10) Zeit(10) "pad " Länge(3) Nutzdaten EOT
 * Antwort:  AppId(12) Serial(6) STX Funktion(2) Seq(2) Status(1) Länge(3) Nutzdaten EOT
 */
class NbeResponse(
    val serial: String,
    val function: Int,
    val seq: Int,
    val status: Int,
    val payload: String,
    val address: String,
)

object Nbe {
    const val PORT = 8483
    private const val SRC_PORT = 1901          // Port, den auch die Aduro-App nutzt
    private const val APP_ID = "___ofenapp__"  // 12 Zeichen
    private val CS = StandardCharsets.ISO_8859_1

    const val FN_DISCOVER = 0
    const val FN_SETTINGS = 1
    const val FN_SET = 2
    const val FN_RANGE = 3
    const val FN_OPERATING = 4
    const val FN_ADVANCED = 5
    const val FN_CONSUMPTION = 6

    private var seq = 0

    fun buildFrame(serial: String, pin: String, fn: Int, payload: String, seq: Int): String {
        val ser = serial.take(6).padStart(6, '0')
        val p = pin.take(10).padEnd(10, '0')
        val ts = (System.currentTimeMillis() / 1000).toString().take(10).padStart(10, '0')
        return APP_ID + ser + " " + '\u0002' +
            String.format("%02d", fn) + String.format("%02d", seq) +
            p + ts + "pad " + String.format("%03d", payload.length) + payload + '\u0004'
    }

    fun parseResponse(raw: String, address: String): NbeResponse {
        if (raw.length < 27) throw IOException("Antwort zu kurz")
        val serial = raw.substring(12, 18)
        var i = 19 // AppId + Serial + Startzeichen
        val fn = raw.substring(i, i + 2).trim().toInt(); i += 2
        val sq = raw.substring(i, i + 2).trim().toInt(); i += 2
        val st = raw.substring(i, i + 1).trim().toIntOrNull() ?: -1; i += 1
        val size = raw.substring(i, i + 3).trim().toInt(); i += 3
        if (i + size > raw.length) throw IOException("Antwort unvollständig")
        return NbeResponse(serial, fn, sq, st, raw.substring(i, i + size), address)
    }

    /** wird von Stove.init gesetzt (Android: Socket ans Heim-WLAN binden) */
    @Volatile var binder: (DatagramSocket, String) -> Unit = { _, _ -> }

    private fun openSocket(timeoutMs: Int, target: String): DatagramSocket {
        val s = try {
            DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(SRC_PORT)) }
        } catch (e: Exception) {
            DatagramSocket() // Port 1901 belegt (z. B. Aduro-App offen) -> beliebiger Port
        }
        s.broadcast = true
        s.soTimeout = timeoutMs
        try { binder(s, target) } catch (e: Exception) { }
        return s
    }

    private fun normSerial(s: String) = s.trim().trimStart('0')

    /** Gehört die Antwort zu genau dieser Anfrage? Verspätete Antworten auf frühere Anfragen und Pakete an die
     *  Aduro-App (gleicher Port 1901) dürfen nicht als aktueller Zustand durchgehen. */
    fun matches(r: NbeResponse, serial: String, fn: Int, seq: Int): Boolean =
        r.function == fn && r.seq == seq && normSerial(r.serial) == normSerial(serial.take(6))

    /** Sendet eine Anfrage und wartet auf die passende Antwort. Immer nur eine Anfrage gleichzeitig. */
    @Synchronized
    fun send(address: String, serial: String, pin: String, fn: Int, payload: String, timeoutMs: Int = 5000): NbeResponse {
        seq = (seq + 1) % 99
        val mySeq = seq
        val data = buildFrame(serial, pin, fn, payload, mySeq).toByteArray(CS)
        openSocket(timeoutMs, address).use { sock ->
            sock.send(DatagramPacket(data, data.size, InetAddress.getByName(address), PORT))
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt) // wirft SocketTimeoutException
                try {
                    val r = parseResponse(String(pkt.data, 0, pkt.length, CS), pkt.address?.hostAddress ?: "")
                    if (matches(r, serial, fn, mySeq)) return r
                    // fremde oder veraltete Antwort – verwerfen und weiter warten
                } catch (e: Exception) {
                    // unbrauchbares Paket – weiter warten
                }
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw SocketTimeoutException()
                sock.soTimeout = left.toInt()
            }
        }
        throw SocketTimeoutException()
    }

    /** Rundruf „NBE Discovery“ an alle angegebenen Adressen; sammelt alle Antworten bis zum Zeitablauf */
    @Synchronized
    fun discoverAll(addresses: List<String>, timeoutMs: Int = 2500): List<NbeResponse> {
        seq = (seq + 1) % 99
        val data = buildFrame("<serial>", "<pin>", FN_DISCOVER, "NBE Discovery", seq).toByteArray(CS)
        val out = LinkedHashMap<String, NbeResponse>()
        openSocket(timeoutMs, addresses.firstOrNull() ?: "255.255.255.255").use { sock ->
            for (a in addresses.distinct()) try {
                sock.send(DatagramPacket(data, data.size, InetAddress.getByName(a), PORT))
            } catch (e: Exception) { }
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                sock.soTimeout = left.toInt()
                val pkt = DatagramPacket(buf, buf.size)
                try { sock.receive(pkt) } catch (e: SocketTimeoutException) { break }
                try {
                    val r = parseResponse(String(pkt.data, 0, pkt.length, CS), pkt.address?.hostAddress ?: "")
                    if (r.address.isNotBlank()) out[r.address] = r
                } catch (e: Exception) { }
            }
        }
        return out.values.toList()
    }

    /** "a=1;b=2" -> Map */
    fun kv(payload: String): Map<String, String> =
        payload.split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i > 0) it.substring(0, i).trim() to it.substring(i + 1).trim() else null
        }.toMap()
}
