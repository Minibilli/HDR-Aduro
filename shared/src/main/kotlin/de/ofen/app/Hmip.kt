package de.ofen.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * Homematic IP über den Access Point (Cloud).
 * Inoffizielle Schnittstelle – dieselbe, die auch Home Assistant nutzt (Bibliothek homematicip-rest-api).
 *
 * Kopplung: connectionRequest -> blaue Taste am Access Point drücken -> isRequestAcknowledged
 *           -> requestAuthToken -> confirmAuthToken
 * Daten:    home/getCurrentState (Räume = HEATING-Gruppen, dazu einzelne Temperatursensoren)
 */
object Hmip {
    private const val LOOKUP = "https://lookup.homematic.com:48335/getHost"
    private const val REFRESH_MS = 3 * 60_000L

    class HmipError(msg: String) : Exception(msg)

    // ------------------------------------------------------------------ Hilfen
    fun normalizeAp(ap: String) = ap.replace("-", "").replace(" ", "").uppercase()

    private fun clientAuth(ap: String): String {
        val d = MessageDigest.getInstance("SHA-512").digest((ap + "jiLpVitHvWnIGD1yo7MA").toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02X".format(it) }
    }

    private fun characteristics(ap: String) = JSONObject()
        .put("clientCharacteristics", JSONObject()
            .put("apiVersion", "10")
            .put("applicationIdentifier", "homematicip-python")
            .put("applicationVersion", "1.0")
            .put("deviceManufacturer", "none")
            .put("deviceType", "Computer")
            .put("language", "de_DE")
            .put("osType", Stove.platform.osType)
            .put("osVersion", Stove.platform.osVersion))
        .put("id", ap)

    private fun headers(ap: String, token: String? = null, pin: String? = null): Map<String, String> {
        val h = mutableMapOf(
            "content-type" to "application/json",
            "accept" to "application/json",
            "VERSION" to "12",
            "CLIENTAUTH" to clientAuth(ap),
            "ACCESSPOINT-ID" to ap,
        )
        if (token != null) h["AUTHTOKEN"] = token
        if (!pin.isNullOrBlank()) h["PIN"] = pin
        return h
    }

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): Pair<Int, String> {
        val con = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            val txt = (if (code in 200..299) con.inputStream else con.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return code to txt
        } finally {
            con.disconnect()
        }
    }

    private fun lookup(ap: String): String {
        val (code, txt) = try {
            post(LOOKUP, characteristics(ap), mapOf("content-type" to "application/json"))
        } catch (e: Exception) {
            throw HmipError("Homematic-Cloud nicht erreichbar (${e.javaClass.simpleName}). Hat das Handy Internet?")
        }
        if (code != 200) throw HmipError("Access Point unbekannt (Fehler $code). Ist die ID richtig?")
        return try { JSONObject(txt).getString("urlREST") } catch (e: Exception) { throw HmipError("Unerwartete Antwort der Homematic-Cloud") }
    }

    // ------------------------------------------------------------------ Kopplung
    /** Schritt 1: Kopplungsanfrage stellen. Danach muss die blaue Taste am Access Point gedrückt werden. */
    fun connect(apRaw: String, pin: String): JSONObject {
        val ap = normalizeAp(apRaw)
        if (ap.length != 24) throw HmipError("Die Access-Point-ID hat 24 Zeichen (z. B. 3014-F711-A000-0000-0000-0001).")
        val rest = lookup(ap)
        val device = UUID.randomUUID().toString()
        val (code, txt) = post("$rest/hmip/auth/connectionRequest",
            JSONObject().put("deviceId", device).put("deviceName", if (Stove.platform.kind == "raspi") "HDR Aduro Home Raspi" else "HDR Aduro").put("sgtin", ap),
            headers(ap, pin = pin))
        if (code != 200) {
            val hint = if (txt.contains("PIN", true) || code == 403) " Ist eine PIN am Access Point vergeben?" else ""
            throw HmipError("Kopplung abgelehnt (Fehler $code).$hint")
        }
        val c = Stove.cfg()
        c.put("hm_pending", JSONObject().put("ap", ap).put("rest", rest).put("device", device).put("pin", pin).put("since", System.currentTimeMillis()))
        Stove.saveCfg(c)
        return JSONObject().put("waiting", true)
    }

    /** Schritt 2: wird regelmäßig aufgerufen, bis die Taste gedrückt wurde. */
    fun confirm(): JSONObject {
        val c = Stove.cfg()
        val p = c.optJSONObject("hm_pending") ?: throw HmipError("Keine Kopplung begonnen.")
        val ap = p.getString("ap"); val rest = p.getString("rest"); val device = p.getString("device")
        if (System.currentTimeMillis() - p.optLong("since") > 3 * 60_000L) {
            c.remove("hm_pending"); Stove.saveCfg(c)
            throw HmipError("Zeit abgelaufen. Bitte „Verbinden“ noch einmal antippen und dann die blaue Taste drücken.")
        }
        val h = headers(ap, pin = p.optString("pin"))
        val (ack, _) = post("$rest/hmip/auth/isRequestAcknowledged", JSONObject().put("deviceId", device).put("accessPointId", ap), h)
        if (ack != 200) return JSONObject().put("done", false)

        val (c1, t1) = post("$rest/hmip/auth/requestAuthToken", JSONObject().put("deviceId", device), h)
        if (c1 != 200) throw HmipError("Token-Anfrage fehlgeschlagen (Fehler $c1)")
        val token = JSONObject(t1).getString("authToken")
        val (c2, _) = post("$rest/hmip/auth/confirmAuthToken", JSONObject().put("deviceId", device).put("authToken", token), h)
        if (c2 != 200) throw HmipError("Token-Bestätigung fehlgeschlagen (Fehler $c2)")

        c.remove("hm_pending")
        c.put("hm_ap", ap).put("hm_rest", rest).put("hm_token", token)
        if (!c.has("hm_show")) c.put("hm_show", JSONArray())
        Stove.saveCfg(c)
        cache = null
        try { refresh(force = true) } catch (e: Exception) { }
        return JSONObject().put("done", true)
    }

    fun disconnect() {
        val c = Stove.cfg()
        for (k in listOf("hm_ap", "hm_rest", "hm_token", "hm_pending", "hm_ctrl", "hm_valve", "hm_valve_closed")) c.remove(k)
        Stove.saveCfg(c)
        cache = null; error = ""
    }

    fun connected(): Boolean = Stove.cfg().let { it.optString("hm_token").isNotBlank() && it.optString("hm_ap").isNotBlank() }

    // ------------------------------------------------------------------ Daten
    @Volatile private var cache: JSONArray? = null
    @Volatile private var cacheTime = 0L
    @Volatile var error = ""
    private val lock = Any()
    /** Nach einem Fehler nicht sofort wieder fragen – sonst sperrt die Cloud wegen zu vieler Anfragen (Fehler 429) */
    @Volatile private var nextTry = 0L

    /** Holt die Räume/Sensoren neu, höchstens alle 3 Minuten (außer force). */
    fun refresh(force: Boolean = false): JSONArray? {
        if (!connected()) return null
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (!force && cache != null && now - cacheTime < REFRESH_MS) return cache
            if (now < nextTry && (!force || error.contains("429"))) return cache
            val c = Stove.cfg()
            val ap = c.getString("hm_ap"); var rest = c.getString("hm_rest"); val token = c.getString("hm_token")
            try {
                var (code, txt) = post("$rest/hmip/home/getCurrentState", characteristics(ap), headers(ap, token))
                if (code != 200 && code != 403 && code != 429) { // Server gewechselt? Adresse neu nachschlagen
                    rest = lookup(ap); c.put("hm_rest", rest); Stove.saveCfg(c)
                    val r = post("$rest/hmip/home/getCurrentState", characteristics(ap), headers(ap, token)); code = r.first; txt = r.second
                }
                if (code == 403) throw HmipError("Homematic-Zugang abgelaufen – bitte unter Einstellungen neu verbinden.")
                if (code == 429) throw HmipError("Homematic-Cloud: zu viele Anfragen (Fehler 429) – neuer Versuch in 10 Minuten")
                if (code != 200) throw HmipError("Homematic-Cloud meldet Fehler $code")
                cache = parse(JSONObject(txt)); cacheTime = System.currentTimeMillis(); error = ""; nextTry = 0
            } catch (e: HmipError) {
                error = e.message ?: "Fehler"
                nextTry = System.currentTimeMillis() + if (error.contains("429")) 10 * 60_000L else 2 * 60_000L
            } catch (e: Exception) {
                error = "Homematic nicht erreichbar (${e.javaClass.simpleName})"
                nextTry = System.currentTimeMillis() + 2 * 60_000L
            }
            return cache
        }
    }

    private fun num(o: JSONObject, k: String): Any = if (o.has(k) && !o.isNull(k)) o.optDouble(k) else JSONObject.NULL

    /** Räume (Heizgruppen) und alle Geräte mit Temperaturfühler */
    private fun parse(state: JSONObject): JSONArray {
        val out = JSONArray()
        val heating = HashSet<String>()
        state.optJSONObject("groups")?.let { groups ->
            for (k in groups.keys()) if (groups.getJSONObject(k).optString("type") == "HEATING") heating += groups.getJSONObject(k).optString("id")
        }
        state.optJSONObject("groups")?.let { groups ->
            val rooms = groups.keys().asSequence().map { groups.getJSONObject(it) }
                .filter { it.optString("type") == "HEATING" && it.has("actualTemperature") && !it.isNull("actualTemperature") }
                .sortedBy { it.optString("label") }
            for (g in rooms) out.put(JSONObject()
                .put("id", "g:" + g.optString("id")).put("label", g.optString("label")).put("kind", "Raum")
                .put("temp", num(g, "actualTemperature")).put("hum", num(g, "humidity"))
                .put("set", num(g, "setPointTemperature")).put("window", g.optString("windowState"))
                .put("mode", g.optString("controlMode")))
        }
        state.optJSONObject("devices")?.let { devices ->
            val list = devices.keys().asSequence().map { devices.getJSONObject(it) }.sortedBy { it.optString("label") }
            for (d in list) {
                val chs = d.optJSONObject("functionalChannels") ?: continue
                // Raum (Heizgruppe), zu dem das Gerät gehört
                var room = ""
                for (k in chs.keys()) {
                    val gs = chs.getJSONObject(k).optJSONArray("groups") ?: continue
                    for (i in 0 until gs.length()) if (gs.optString(i) in heating) { room = "g:" + gs.optString(i); break }
                    if (room.isNotEmpty()) break
                }
                for (k in chs.keys()) {
                    val ch = chs.getJSONObject(k)
                    if (!ch.has("actualTemperature") || ch.isNull("actualTemperature")) continue
                    out.put(JSONObject()
                        .put("id", "d:" + d.optString("id")).put("label", d.optString("label")).put("kind", "Fühler")
                        .put("temp", num(ch, "actualTemperature")).put("hum", num(ch, "humidity"))
                        .put("set", num(ch, "setPointTemperature")).put("window", "").put("room", room))
                    break
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------ Befehle an Heizgruppen
    private fun command(path: String, body: JSONObject) {
        val c = Stove.cfg()
        if (!connected()) throw HmipError("Homematic nicht verbunden")
        val ap = c.getString("hm_ap"); val rest = c.getString("hm_rest"); val token = c.getString("hm_token")
        val (code, txt) = try { post("$rest/hmip/$path", body, headers(ap, token)) }
            catch (e: Exception) { throw HmipError("Homematic nicht erreichbar (${e.javaClass.simpleName})") }
        if (code == 403) throw HmipError("Homematic-Zugang abgelaufen – bitte neu verbinden.")
        if (code != 200) throw HmipError("Homematic lehnt $path ab (Fehler $code${if (txt.isNotBlank()) ": " + txt.take(80) else ""})")
    }

    private fun gid(id: String) = id.removePrefix("g:")

    /** Raum auf „manuell“ und sehr niedrige Solltemperatur -> Heizkörperventile schließen */
    fun closeValves(id: String, setPoint: Double) {
        command("group/heating/setControlMode", JSONObject().put("groupId", gid(id)).put("controlMode", "MANUAL"))
        command("group/heating/setSetPointTemperature", JSONObject().put("groupId", gid(id)).put("setPointTemperature", setPoint))
        cacheTime = 0
    }

    /** Vorherigen Zustand wiederherstellen: Wochenplan (AUTOMATIC) oder vorherige manuelle Temperatur */
    fun restoreValves(id: String, prevMode: String, prevSet: Double) {
        if (prevMode == "MANUAL" && !prevSet.isNaN())
            command("group/heating/setSetPointTemperature", JSONObject().put("groupId", gid(id)).put("setPointTemperature", prevSet))
        else
            command("group/heating/setControlMode", JSONObject().put("groupId", gid(id)).put("controlMode", "AUTOMATIC"))
        cacheTime = 0
    }

    fun ageSeconds(): Long = if (cacheTime == 0L) -1 else (System.currentTimeMillis() - cacheTime) / 1000

    fun sensors(): JSONArray = cache ?: JSONArray()

    fun sensor(id: String): JSONObject? {
        val a = sensors()
        for (i in 0 until a.length()) if (a.getJSONObject(i).optString("id") == id) return a.getJSONObject(i)
        return null
    }

    /** Heizgruppe („g:…“) zu einer Auswahl: Raum selbst oder der Raum, in dem der Fühler hängt */
    fun roomOf(id: String): String? {
        if (id.startsWith("g:")) return id
        return sensor(id)?.optString("room")?.ifBlank { null }
    }

    /** für die Startseite: nur ausgewählte Räume */
    fun shown(): JSONArray {
        val show = Stove.cfg().optJSONArray("hm_show") ?: JSONArray()
        val ids = (0 until show.length()).map { show.optString(it) }.toSet()
        val ctrl = Stove.cfg().optString("hm_ctrl_id")
        val out = JSONArray()
        val a = sensors()
        for (i in 0 until a.length()) {
            val s = a.getJSONObject(i)
            if (s.optString("id") in ids) out.put(JSONObject(s.toString()).put("ctrl", s.optString("id") == ctrl && Stove.cfg().optBoolean("hm_ctrl")))
        }
        return out
    }
}
