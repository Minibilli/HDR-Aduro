package de.ofen.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Handy ↔ Raspi. Ist das Handy mit einem Raspi gekoppelt und hat es NICHT die Steuerung,
 * zeigt die App einfach den Raspi an: alle Anfragen der Oberfläche gehen an den Raspi.
 * Ist der Raspi nicht erreichbar, spricht die App wieder selbst mit dem Ofen (nur Anzeige
 * und Bedienen – Automatiken erst nach „Notfall-Übernahme“).
 */
object Link {
    @Volatile var lastOk = 0L
    @Volatile private var lastFail = 0L

    private fun now() = System.currentTimeMillis()
    private fun link(): JSONObject? = Stove.cfg().optJSONObject("link")

    fun linked(): Boolean = link()?.let { it.optString("url").isNotBlank() && it.optString("token").isNotBlank() } == true
    fun reachable(): Boolean = now() - lastOk < 75_000L

    /** Anfrage an den Raspi; Antwort ist {ok,data} oder {error} */
    private fun call(url0: String, token: String?, path: String, body: JSONObject?, timeoutMs: Int = 6000): JSONObject {
        val con = (URL(url0.trimEnd('/') + "/api/" + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = minOf(timeoutMs, 4000); readTimeout = timeoutMs
            requestMethod = if (body == null) "GET" else "POST"
            setRequestProperty("Content-Type", "application/json")
            if (!token.isNullOrBlank()) setRequestProperty("X-Ofen-Token", token)
            doOutput = body != null
        }
        try {
            if (body != null) con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            val txt = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            lastOk = now()
            if (code == 401) throw StoveError("Der Raspi kennt dieses Handy nicht mehr – bitte unter Einstellungen → Raspi & Handys neu koppeln.")
            return try { JSONObject(txt) } catch (e: Exception) { JSONObject().put("error", "Unerwartete Antwort vom Raspi ($code)") }
        } catch (e: StoveError) {
            throw e
        } catch (e: Exception) {
            lastFail = now()
            throw StoveError("Raspi nicht erreichbar")
        } finally {
            con.disconnect()
        }
    }

    private fun call(path: String, body: JSONObject?, timeoutMs: Int = 6000): JSONObject {
        val l = link() ?: throw StoveError("Nicht mit einem Raspi gekoppelt.")
        return call(l.optString("url"), l.optString("token"), path, body, timeoutMs)
    }

    /** Raspi-Endpunkt aufrufen und Ergebnis auspacken */
    fun remote(path: String, body: JSONObject?): JSONObject {
        val r = call(path, body)
        if (r.has("error")) throw StoveError(r.optString("error"))
        return r.optJSONObject("data") ?: JSONObject()
    }

    fun ping(): Boolean = try { call("control", null, 3000); true } catch (e: Exception) { false }

    // ------------------------------------------------------------------ Koppeln
    private fun normUrl(u0: String): String {
        var u = u0.trim().trimEnd('/')
        if (u.isBlank()) throw StoveError("Bitte die Adresse des Raspi eintragen, z. B. 192.168.1.20 oder raspi.local")
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        if (!Regex("^https?://[^/]+:\\d+").containsMatchIn(u)) {
            val i = u.indexOf('/', u.indexOf("://") + 3)
            u = if (i < 0) "$u:8080" else u.substring(0, i) + ":8080" + u.substring(i)
        }
        return u
    }

    fun pair(url0: String, code: String) {
        val url = normUrl(url0)
        Control.beforePair()
        val r = call(url, null, "pair", JSONObject().put("code", code.trim()).put("name", Stove.platform.deviceName)
            .put("id", Control.deviceId()).put("kind", "phone"))
        if (r.has("error")) throw StoveError(r.optString("error"))
        val d = r.optJSONObject("data") ?: throw StoveError("Unerwartete Antwort vom Raspi")
        synchronized(Stove) {
            val c = Stove.cfg()
            c.put("link", JSONObject().put("url", url).put("token", d.optString("token"))
                .put("raspi_name", d.optString("raspi_name")).put("last_event", d.optLong("last_event")))
            c.remove("ctl")
            Stove.saveCfg(c)
        }
        // Erster Abgleich: Ofen-Zugang, Homematic und alle Einstellungen vom Raspi übernehmen
        tick()
    }

    fun unpair() {
        if (!linked()) return
        if (Control.isHolder()) try { Control.releaseRemote() } catch (e: Exception) { }
        synchronized(Stove) { val c = Stove.cfg(); c.remove("link"); Stove.saveCfg(c) }
        Control.afterUnpair()
    }

    // ------------------------------------------------------------------ Oberfläche
    /** Alle Anfragen der Oberfläche laufen hier durch */
    fun handle(path: String, bodyText: String?): JSONObject {
        if (!linked() || Control.isHolder() || path.startsWith("control") || path.startsWith("link") || path == "net")
            return Api.handle(path, bodyText)
        // Raspi war eben nicht erreichbar -> nicht bei jeder Anfrage auf die Zeitüberschreitung warten
        if (!reachable() && now() - lastFail < 20_000L) return Api.handle(path, bodyText)
        return try {
            var body = if (bodyText == null) null else try { JSONObject(bodyText.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
            if (path == "config" && body != null) {
                // Darstellung / Benachrichtigungen bleiben auf dem Handy
                val local = JSONObject()
                for (k in Control.DEVICE_CFG) if (body.has(k)) { local.put(k, body.get(k)); body.remove(k) }
                if (local.length() > 0) Api.handle("config", local.toString())
                if (body.length() == 0) return JSONObject().put("ok", true).put("data", JSONObject())
            }
            val r = call(path, body, if (path == "discover" || path == "setup" || path == "pelletstop") 25_000 else 8000)
            val d = r.optJSONObject("data")
            if (d != null && path == "status") {
                d.put("control", Control.info())
                Stove.routeInfo().let { d.put("route", it.optString("route")).put("home_wifi", it.optBoolean("home_wifi")) }
            }
            if (d != null && path == "config" && body == null) {
                val c = Stove.cfg()
                d.put("linked", true).put("app_on", Stove.appRunning()).put("vpn_app", c.optString("vpn_app", "com.zaneschepke.wireguardautotunnel"))
                    .put("notify_state", c.optBoolean("notify_state", true))
                    .put("bg_dim", c.optInt("bg_dim", 35)).put("card_alpha", c.optInt("card_alpha", 66))
            }
            r
        } catch (e: StoveError) {
            if (e.message?.startsWith("Raspi nicht erreichbar") == true) Api.handle(path, bodyText)
            else JSONObject().put("error", e.message)
        }
    }

    // ------------------------------------------------------------------ Hintergrund
    /**
     * Aus der Überwachungsschleife aufrufen (alle 10–30 s).
     * Steuerndes Handy: Herzschlag mit eigenem Zustand. Sonst: Abgleich mit dem Raspi + Meldungen.
     * Rückgabe: Kurztext für die Dauer-Benachrichtigung, wenn der Raspi den Ofen überwacht – sonst null.
     */
    fun tick(): String? {
        if (!linked()) return null
        if (Control.isHolder()) {
            val c = Control.ctl()
            try {
                val r = remote("remote/heartbeat", JSONObject().put("epoch", c.optInt("epoch")).put("forced", c.optBoolean("forced")).put("pack", Control.pack()))
                Control.onHeartbeat(r)
            } catch (e: Exception) { }
            return null
        }
        return try {
            val since = link()?.optLong("last_event") ?: 0L
            val r = remote("remote/sync", JSONObject().put("since", since))
            Control.onSync(r)
            deliver(r)
            if (Control.isHolder()) null else r.optString("summary").ifBlank { "Raspi überwacht den Ofen" }
        } catch (e: Exception) { null }
    }

    /** Meldungen bis zu dieser Nummer gelten als gesehen */
    fun seenUpTo(id: Long) {
        if (id <= 0) return
        synchronized(Stove) {
            val c = Stove.cfg(); val l = c.optJSONObject("link") ?: return
            if (l.optLong("last_event") >= id) return
            l.put("last_event", id); c.put("link", l); Stove.saveCfg(c)
        }
    }

    /** Meldungen des Raspi als Benachrichtigung zeigen */
    private fun deliver(r: JSONObject) {
        val ev = r.optJSONArray("events") ?: return
        val p = Stove.platform
        val notifyState = Stove.cfg().optBoolean("notify_state", true)
        for (i in 0 until ev.length()) {
            val e = ev.optJSONObject(i) ?: continue
            try {
                when (e.optString("kind")) {
                    "alert", "control" -> p.alert(e.optInt("aid", Alerts.CONTROL), e.optString("title"), e.optString("text"))
                    "clear" -> p.clearAlert(e.optInt("aid"))
                    "state" -> if (notifyState) p.notifyState(e.optString("title"), e.optString("text"))
                }
            } catch (x: Exception) { }
        }
        seenUpTo(r.optLong("last_event"))
    }
}
