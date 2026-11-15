package de.ofen.raspi

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import de.ofen.app.Api
import de.ofen.app.Control
import de.ofen.app.Stove
import de.ofen.app.StoveError
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * HDR Aduro Home Raspi
 *
 * Läuft dauerhaft auf dem Raspberry Pi: überwacht den Ofen, führt die Automatiken aus (wenn der Raspi
 * die Steuerung hat) und stellt die Oberfläche unter http://<raspi>:8080 bereit – für den Touchscreen
 * (Chromium im Kiosk-Modus, localhost) und für gekoppelte Handys und Browser im Heimnetz / per VPN.
 *
 * Umgebungsvariablen: OFEN_DATA (Ordner für config.json, Standard ~/.hdr-aduro), OFEN_PORT (Standard 8080)
 */
fun main() {
    val dir = File(System.getenv("OFEN_DATA") ?: (System.getProperty("user.home") + "/.hdr-aduro"))
    val port = System.getenv("OFEN_PORT")?.toIntOrNull() ?: 8080
    Stove.init(RaspiPlatform(dir))
    Stove.appOn()   // der Raspi ist dafür da: Funktionen laufen immer
    Control.startRaspi()
    RaspiPlatform.log("HDR Aduro Home Raspi startet – Daten in $dir, Port $port")

    Thread(::monitorLoop, "ofen-monitor").apply { isDaemon = true }.start()
    Thread(::manualLoop, "ofen-manual").apply { isDaemon = true }.start()

    val page = Thread.currentThread().contextClassLoader.getResourceAsStream("web/index.html")?.readBytes()
        ?: error("web/index.html fehlt im Paket")

    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.executor = Executors.newFixedThreadPool(8)
    server.createContext("/") { ex ->
        if (ex.requestURI.path == "/" || ex.requestURI.path == "/index.html") send(ex, 200, page, "text/html; charset=utf-8")
        else send(ex, 404, "nicht gefunden".toByteArray(), "text/plain; charset=utf-8")
    }
    server.createContext("/api/") { ex -> api(ex) }
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(0)
        try { if (Stove.man.active || Stove.man.deadline.isNotEmpty()) Stove.manualAllOff() } catch (e: Exception) { }
    })
    server.start()
}

private fun send(ex: HttpExchange, code: Int, body: ByteArray, type: String) {
    ex.responseHeaders.add("Content-Type", type)
    ex.responseHeaders.add("Cache-Control", "no-store")
    ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
    if (body.isNotEmpty()) ex.responseBody.use { it.write(body) } else ex.close()
}

private fun json(ex: HttpExchange, code: Int, o: JSONObject) = send(ex, code, o.toString().toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")

private fun api(ex: HttpExchange) {
    try {
        val path = ex.requestURI.path.removePrefix("/api/").trim('/')
        val post = ex.requestMethod.equals("POST", true)
        val text = if (post) ex.requestBody.bufferedReader(Charsets.UTF_8).use { it.readText() } else null
        val local = ex.remoteAddress.address.isLoopbackAddress
        if (path == "pair") {
            val b = try { JSONObject(text?.ifBlank { "{}" } ?: "{}") } catch (e: Exception) { JSONObject() }
            return json(ex, 200, try { JSONObject().put("ok", true).put("data", Control.pair(b)) } catch (e: StoveError) { JSONObject().put("error", e.message) })
        }
        val client = Control.clientByToken(ex.requestHeaders.getFirst("X-Ofen-Token"))
        if (!local && client == null) return json(ex, 401, JSONObject().put("error", "Dieses Gerät ist noch nicht gekoppelt.").put("auth", true))
        if (path.startsWith("remote/")) {
            if (client == null) return json(ex, 403, JSONObject().put("error", "Nur für gekoppelte Handys"))
            val b = try { JSONObject(text?.ifBlank { "{}" } ?: "{}") } catch (e: Exception) { JSONObject() }
            return json(ex, 200, try { JSONObject().put("ok", true).put("data", Control.remote(path, b, client)) } catch (e: Exception) { JSONObject().put("error", e.message ?: "Fehler") })
        }
        // Am Raspi nicht vorgesehen: Funktionen beenden, VPN-App, Handy-Bridge
        if (path == "app/quit") return json(ex, 200, JSONObject().put("error", "Am Raspi laufen die Funktionen immer."))
        json(ex, 200, Api.handle(path, text))
    } catch (e: Exception) {
        try { json(ex, 500, JSONObject().put("error", e.message ?: e.javaClass.simpleName)) } catch (x: Exception) { }
    }
}

/** Wie OfenService in der App: Ofen regelmäßig abfragen (Warnungen, Automatiken) */
private fun monitorLoop() {
    var lastErr = ""
    while (true) {
        try { Control.tickRaspi() } catch (e: Exception) { }
        if (Stove.configured()) {
            try {
                Stove.readStatus()
                if (lastErr.isNotEmpty()) { RaspiPlatform.log("Ofen antwortet wieder"); lastErr = "" }
            } catch (e: Exception) {
                val m = e.message ?: e.javaClass.simpleName
                if (m != lastErr) { RaspiPlatform.log("Ofen: $m"); lastErr = m }
                Stove.valveOfflineCheck()
            }
        }
        val pause = if (Stove.busy() || Stove.lastStatus?.optBoolean("wood_mode") == true) 10_000L
            else if (Stove.cfg().optBoolean("hm_ctrl")) 20_000L else 30_000L
        // in kleinen Schritten schlafen, damit Übergaben (Startschutz, 3-min-Grenze) zügig greifen
        val end = System.currentTimeMillis() + pause
        while (System.currentTimeMillis() < end) {
            Thread.sleep(2_000)
            try { Control.tickRaspi() } catch (e: Exception) { }
        }
    }
}

private fun manualLoop() {
    while (true) {
        Stove.manualTick()
        Thread.sleep(2_000)
    }
}
