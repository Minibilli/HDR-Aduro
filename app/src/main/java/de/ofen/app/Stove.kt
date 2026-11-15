package de.ofen.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class StoveError(msg: String) : Exception(msg)

/** Gesamte Ofen-Logik: Verbindung, Status, Handbetrieb, Warnungen, Automatik. */
object Stove {
    private lateinit var ctx: Context

    fun init(c: Context) {
        if (!::ctx.isInitialized) ctx = c.applicationContext
    }

    // ------------------------------------------------------------------ Texte
    val STATE_TEXT = mapOf(
        "0" to "Warten", "2" to "Zündung", "4" to "2. Zündung", "5" to "Normalbetrieb", "6" to "Raumtemperatur erreicht",
        "9" to "Holzbetrieb", "11" to "Fallschacht heiß", "13" to "Zündung fehlgeschlagen – Tür öffnen, Brennschale auf Pellets prüfen",
        "14" to "Per Taste gestoppt", "15" to "Rauchgassensor defekt", "17" to "Fallschachtsensor defekt",
        "18" to "Brenner prüfen (gelb)", "19" to "Externe Schnecke defekt", "20" to "Alarm 7: Kein Feuer",
        "23" to "Durch Timer gestoppt", "24" to "Luftklappe geschlossen", "28" to "Tür offen",
        "32" to "Aufheizen", "33" to "CO-Sensor defekt", "34" to "Brennschale prüfen",
        "35" to "Lüfter ohne Stromaufnahme",
    )
    private val SUBSTATE_TEXT = mapOf("0" to "normal", "1" to "warten", "2" to "Startbefüllung", "4" to "Zünder an", "6" to "Reinigung")
    private val MODE_TEXT = mapOf("0" to "Heizstufe", "1" to "Raumtemperatur", "2" to "Timer")
    private val ALARM_TEXT = mapOf("0" to "OK", "1" to "vom Nutzer gestoppt", "2" to "unerwartet gestoppt")
    val BURNING = setOf("2", "4", "5", "6", "9", "11", "32")
    private val PELLET_RUN = setOf("5", "6")

    // ------------------------------------------------------------------ Konfiguration
    private val prefs get() = ctx.getSharedPreferences("ofen", Context.MODE_PRIVATE)

    @Synchronized
    fun cfg(): JSONObject = try {
        JSONObject(prefs.getString("config", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    @Synchronized
    fun saveCfg(c: JSONObject) {
        prefs.edit().putString("config", c.toString()).apply()
    }

    private fun need(): Triple<String, String, String> {
        val c = cfg()
        val ip = c.optString("ip"); val ser = c.optString("serial"); val pin = c.optString("pin")
        if (ip.isBlank() || ser.isBlank() || pin.isBlank())
            throw StoveError("Bitte zuerst IP, Seriennummer und PIN unter Einstellungen eintragen.")
        return Triple(ip, ser, pin)
    }

    fun configured(): Boolean = cfg().let { it.optString("ip").isNotBlank() && it.optString("serial").isNotBlank() && it.optString("pin").isNotBlank() }

    // ------------------------------------------------------------------ Kommunikation
    private fun call(fn: Int, payload: String): NbeResponse {
        val (ip, ser, pin) = need()
        return try {
            Nbe.send(ip, ser, pin, fn, payload)
        } catch (e: SocketTimeoutException) {
            // IP geändert? (Router vergibt dynamisch) -> im WLAN nach der Seriennummer suchen und einmal neu versuchen
            if (rediscover()) {
                val (ip2, _, _) = need()
                try { return Nbe.send(ip2, ser, pin, fn, payload) } catch (e2: Exception) { }
            }
            throw StoveError("Ofen antwortet nicht (Zeitüberschreitung)")
        } catch (e: StoveError) {
            throw e
        } catch (e: Exception) {
            throw StoveError("Ofen antwortet nicht (${e.javaClass.simpleName})")
        }
    }

    fun get(fn: Int, path: String): String {
        val r = call(fn, path)
        if (r.status != 0) throw StoveError("Ofen meldet Fehler (Status ${r.status}) bei $path")
        return r.payload
    }

    fun getKv(fn: Int, path: String): Map<String, String> = Nbe.kv(get(fn, path))

    var lastSet = JSONObject()

    /** Zeitpunkt des letzten Stoppbefehls der App – damit ein Stopp am Ofen davon unterschieden werden kann */
    @Volatile private var appStopAt = 0L

    fun set(path: String, value: String) {
        if (path == "misc.stop") appStopAt = System.currentTimeMillis()
        val r = call(Nbe.FN_SET, "$path=$value")
        lastSet = JSONObject().put("status", r.status).put("payload", r.payload.take(120))
        if (r.status != 0) throw StoveError("Ofen hat '$path = $value' abgelehnt (Status ${r.status})")
    }

    // ------------------------------------------------------------------ Ofen im WLAN finden
    /** Broadcast-Adressen der WLAN-Netze (z. B. 192.168.50.255) plus 255.255.255.255 */
    private fun broadcastAddrs(): List<String> {
        val out = mutableListOf<String>()
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                for (la in cm.getLinkProperties(n)?.linkAddresses ?: emptyList()) {
                    val b = la.address.address
                    if (b.size != 4) continue
                    val pl = la.prefixLength.coerceIn(8, 30)
                    val ip = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
                    val bc = ip or ((1 shl (32 - pl)) - 1)
                    out += "${(bc ushr 24) and 255}.${(bc ushr 16) and 255}.${(bc ushr 8) and 255}.${bc and 255}"
                }
            }
        } catch (e: Exception) { }
        out += "255.255.255.255"
        return out.distinct()
    }

    private fun normSerial(s: String) = s.trim().trimStart('0').ifBlank { s.trim() }

    /** Alle Öfen, die im WLAN auf den Rundruf antworten: Liste aus (IP, Seriennummer) */
    fun scan(): List<Pair<String, String>> =
        Nbe.discoverAll(broadcastAddrs()).map { r ->
            var ip = r.address
            var serial = normSerial(r.serial)
            for ((k, v) in Nbe.kv(r.payload)) {
                val kl = k.lowercase()
                if (kl.contains("serial") && v.isNotBlank()) serial = normSerial(v)
                if ((kl == "ip" || kl == "stoveip" || kl == "ip_address") && v.isNotBlank()) ip = v
            }
            ip to serial
        }.filter { it.first.isNotBlank() }.distinctBy { it.first }

    /** „Ofen suchen“: gefundene Öfen auflisten (speichert nichts) */
    fun discover(): JSONObject {
        val list = try { scan() } catch (e: Exception) { emptyList() }
        if (list.isEmpty()) throw StoveError("Kein Ofen im WLAN gefunden. Ist das Handy im selben WLAN wie der Ofen (VPN aus)?")
        val arr = org.json.JSONArray()
        list.forEach { arr.put(JSONObject().put("ip", it.first).put("serial", it.second)) }
        val mine = normSerial(cfg().optString("serial"))
        val best = list.firstOrNull { it.second == mine } ?: list.first()
        return JSONObject().put("ip", best.first).put("serial", best.second).put("all", arr)
    }

    /** Einrichtung in einem Schritt: Ofen mit dieser Seriennummer im WLAN suchen, IP übernehmen, Verbindung prüfen */
    fun setup(serial0: String, pin: String): JSONObject {
        val c = cfg()
        val serial = normSerial(serial0.ifBlank { c.optString("serial") })
        val pinUse = pin.ifBlank { c.optString("pin") }
        val list = try { scan() } catch (e: Exception) { emptyList() }
        val hit = list.firstOrNull { serial.isNotBlank() && it.second == serial }
            ?: (if (serial.isBlank() && list.size == 1) list.first() else null)
        if (hit == null) {
            val msg = when {
                list.isEmpty() -> "Kein Ofen im WLAN gefunden. Handy ins Heim-WLAN (VPN aus) und Ofen eingeschaltet?"
                else -> "Im WLAN antwortet ${list.joinToString { "Ofen ${it.second} (${it.first})" }} – aber nicht Seriennummer $serial. Seriennummer prüfen."
            }
            throw StoveError(msg)
        }
        val ser = hit.second
        if (pinUse.isBlank()) throw StoveError("Ofen $ser gefunden (${hit.first}), aber das Passwort fehlt.")
        val ok = try {
            Nbe.send(hit.first, ser, pinUse, Nbe.FN_OPERATING, "*").status == 0
        } catch (e: Exception) { false }
        if (!ok) throw StoveError("Ofen $ser gefunden (${hit.first}), aber er nimmt das Passwort nicht an. Passwort prüfen.")
        c.put("ip", hit.first).put("serial", ser).put("pin", pinUse); saveCfg(c)
        return JSONObject().put("ip", hit.first).put("serial", ser)
    }

    /** Dynamische IP: antwortet der Ofen nicht, im WLAN nach seiner Seriennummer suchen und neue IP übernehmen.
     *  Höchstens alle 2 min; geht nur, wenn das Handy im selben WLAN ist (nicht über VPN). */
    @Volatile private var lastRediscover = 0L
    private fun rediscover(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRediscover < 2 * 60_000L) return false
        lastRediscover = now
        val c = cfg()
        val ser = normSerial(c.optString("serial"))
        if (ser.isBlank()) return false
        val hit = try { scan().firstOrNull { it.second == ser } } catch (e: Exception) { null } ?: return false
        val old = c.optString("ip")
        if (hit.first == old) return false
        c.put("ip", hit.first).put("ip_changed_at", now).put("ip_old", old); saveCfg(c)
        try {
            if (c.optBoolean("notify_state", true))
                Notifier.stateChange(ctx, "Ofen hat eine neue IP-Adresse", "$old → ${hit.first} – von der App automatisch übernommen.")
        } catch (e: Exception) { }
        return true
    }

    fun routeInfo(): JSONObject {
        val ip = cfg().optString("ip")
        var vpn = false
        var homeWifi = false   // Handy hängt im WLAN, dessen Adressbereich zum Ofen passt (also zu Hause)
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) vpn = true
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && ip.isNotBlank()) {
                    val pre = ip.split(".").take(3)
                    if (cm.getLinkProperties(n)?.linkAddresses?.any { it.address.hostAddress?.split(".")?.take(3) == pre } == true) homeWifi = true
                }
            }
        } catch (e: Exception) { }
        val out = JSONObject().put("vpn_if", vpn).put("home_wifi", homeWifi)
        if (ip.isBlank()) return out.put("route", "none").put("local_ip", "")
        val local = try {
            DatagramSocket().use { s -> s.connect(InetAddress.getByName(ip), 1900); s.localAddress?.hostAddress ?: "" }
        } catch (e: Exception) { "" }
        val same = local.isNotBlank() && local.split(".").take(3) == ip.split(".").take(3)
        val route = when {
            vpn && local.isNotBlank() -> "vpn"
            same -> "wlan"
            else -> "none"
        }
        return out.put("route", route).put("local_ip", local)
    }

    // ------------------------------------------------------------------ Status
    @Volatile var lastStatus: JSONObject? = null

    fun readStatus(): JSONObject {
        if (!appRunning()) throw StoveError("Funktionen sind aus – auf der Hauptseite „Funktionen starten“.")
        val op = getKv(Nbe.FN_OPERATING, "*")
        if (op.isEmpty()) throw StoveError("Unerwartete Antwort vom Ofen")
        val s = JSONObject()
        s.put("fixed_power", runCatching { getKv(Nbe.FN_SETTINGS, "regulation.fixed_power")["fixed_power"] }.getOrNull() ?: JSONObject.NULL)
        s.put("target_temp", runCatching { getKv(Nbe.FN_SETTINGS, "boiler.temp")["temp"] }.getOrNull() ?: op["boiler_ref"] ?: JSONObject.NULL)
        val st = op["state"] ?: ""
        val prevState = lastState
        stateChanged(st, op["smoke_temp"], op["boiler_temp"])
        val sub = op["substate"] ?: ""
        val mode = op["operation_mode"] ?: ""
        val alarm = op["off_on_alarm"] ?: ""
        s.put("state", st).put("state_text", STATE_TEXT[st] ?: "Zustand $st")
        s.put("substate_text", SUBSTATE_TEXT[sub] ?: sub)
        s.put("room_temp", op["boiler_temp"] ?: JSONObject.NULL)
        s.put("smoke_temp", op["smoke_temp"] ?: JSONObject.NULL)
        s.put("shaft_temp", op["shaft_temp"] ?: JSONObject.NULL)
        s.put("power_kw", op["power_kw"] ?: JSONObject.NULL)
        s.put("oxygen", op["oxygen"] ?: JSONObject.NULL)
        s.put("mode", mode).put("mode_text", MODE_TEXT[mode] ?: "–")
        s.put("alarm_text", ALARM_TEXT[alarm] ?: alarm)
        s.put("raw", JSONObject(op))
        routeInfo().let { s.put("route", it.optString("route")).put("home_wifi", it.optBoolean("home_wifi")) }
        try { externalStopCheck(prevState, st) } catch (e: Exception) { }
        haltCheck(s)
        try { hopperCheck(s) } catch (e: Exception) { }
        try {
            earlyCheck(s)
        } catch (e: Exception) {
            s.put("early", JSONObject().put("enabled", true).put("stopped", early.stopped).put("msg", "Fehler: ${e.message}"))
        }
        checkWood(s)
        try {
            thermoCheck(s)
        } catch (e: Exception) {
            s.put("thermo", JSONObject().put("enabled", true).put("msg", "Fehler: ${e.message}"))
        }
        try { autoPowerCheck(s) } catch (e: Exception) {
            s.put("autopower", JSONObject().put("enabled", true).put("msg", "Fehler: ${e.message}"))
        }
        try {
            valveCheck(s)
        } catch (e: Exception) {
            s.put("valve", JSONObject().put("enabled", true).put("closed", cfg().has("hm_valve_closed")).put("msg", "Fehler: ${e.message}"))
        }
        lastStatusAt = System.currentTimeMillis()
        if (Hmip.connected()) {
            Hmip.refresh()
            s.put("rooms", Hmip.shown()).put("rooms_age", Hmip.ageSeconds()).put("rooms_error", Hmip.error)
        }
        lastStatus = s
        return s
    }

    // ------------------------------------------------------------------ Zustandsänderung melden
    @Volatile private var lastState: String? = null

    private fun stateChanged(st: String, smoke: String?, room: String?) {
        val old = lastState
        lastState = st
        if (old == null || old == st || st.isBlank()) return
        if (!cfg().optBoolean("notify_state", true)) return
        val name = STATE_TEXT[st] ?: "Zustand $st"
        val before = STATE_TEXT[old] ?: "Zustand $old"
        val extra = buildString {
            room?.toDoubleOrNull()?.let { append("Raum %.1f °C".format(it)) }
            smoke?.toDoubleOrNull()?.let { if (isNotEmpty()) append(" · "); append("Rauchgas %.0f °C".format(it)) }
        }
        Notifier.stateChange(ctx, "Ofen: $name", "vorher: $before" + if (extra.isNotEmpty()) "\n$extra" else "")
    }

    private fun smoke(s: JSONObject): Double? = s.optString("smoke_temp").toDoubleOrNull()

    // ------------------------------------------------------------------ Warnung Holzbetrieb
    private var woodArmed = false
    private var woodNotified = false

    private fun checkWood(s: JSONObject) {
        val thr = cfg().optDouble("wood_min", 150.0)
        val wood = s.optString("state") == "9" || man.wood || early.stopped
        s.put("wood_mode", wood).put("wood_min", thr).put("wood_warn", false)
        val t = smoke(s) ?: return
        if (!wood) {
            if (woodNotified) Notifier.clear(ctx, Notifier.ID_WOOD)
            woodArmed = false; woodNotified = false
            return
        }
        if (t >= thr) woodArmed = true // erst scharf, wenn das Feuer einmal richtig brennt
        if (t < thr) {
            s.put("wood_warn", true)
            if (woodArmed && !woodNotified) {
                Notifier.alert(ctx, Notifier.ID_WOOD, "Holz nachlegen", "Rauchgas ${t.toInt()} °C – unter ${thr.toInt()} °C")
                woodNotified = true
            }
        } else if (t >= thr + 5 && woodNotified) {
            Notifier.clear(ctx, Notifier.ID_WOOD)
            woodNotified = false
        }
    }

    // ------------------------------------------------------------------ Pellets früher stoppen
    class Early { var stopped = false; var aboveSince = 0L; var msg = "" }
    val early = Early()
    private const val EARLY_HOLD_MS = 30_000L
    private const val EARLY_RESTART_BELOW = 100.0

    fun cancelEarly() { early.stopped = false; early.msg = "" }

    private fun earlyCheck(s: JSONObject) {
        val c = cfg()
        val info = JSONObject().put("enabled", c.optBoolean("early_stop")).put("stopped", early.stopped).put("msg", early.msg)
        s.put("early", info)
        if (c.optBoolean("halted") || !c.optBoolean("expert")) { cancelEarly(); early.aboveSince = 0; info.put("stopped", false).put("msg", ""); return }
        val t = smoke(s) ?: return
        val st = s.optString("state")
        if (early.stopped) {
            if (st in PELLET_RUN || st in setOf("2", "4", "32", "9")) {
                cancelEarly() // Ofen läuft wieder (von Hand oder selbst)
            } else if (c.optBoolean("early_restart") && t < c.optDouble("early_restart_temp", EARLY_RESTART_BELOW)) {
                set("misc.start", "1")
                cancelEarly()
                Notifier.alert(ctx, Notifier.ID_EARLY, "Pellets starten wieder", "Holz abgebrannt (Rauchgas ${t.toInt()} °C)")
            }
            info.put("stopped", early.stopped)
            return
        }
        if (!c.optBoolean("early_stop") || st !in PELLET_RUN) { early.aboveSince = 0; return }
        val thr = c.optDouble("early_temp", 240.0)
        if (t >= thr) {
            if (early.aboveSince == 0L) early.aboveSince = System.currentTimeMillis()
            if (System.currentTimeMillis() - early.aboveSince >= EARLY_HOLD_MS) {
                set("misc.stop", "1")
                early.stopped = true; early.aboveSince = 0
                early.msg = "Pellets bei ${t.toInt()} °C Rauchgas von der App gestoppt"
                Notifier.alert(ctx, Notifier.ID_EARLY, "Pellets gestoppt", "Holz brennt – Rauchgas ${t.toInt()} °C über ${thr.toInt()} °C")
                info.put("stopped", true).put("msg", early.msg)
            }
        } else early.aboveSince = 0
    }

    // ------------------------------------------------------------------ Heizkörper im Ofenraum (Homematic IP)
    // Heizt der Ofen, wird die Heizgruppe des Raums auf „manuell“ mit niedriger Solltemperatur gestellt
    // (Ventile zu). Ist der Ofen aus und abgekühlt, kommt der vorherige Zustand zurück (meist der Wochenplan).
    class Valve { var offSince = 0L; var lastTry = 0L }
    val valve = Valve()
    @Volatile var lastStatusAt = 0L
    private val VALVE_HEAT = setOf("2", "4", "5", "6", "9", "11", "32")
    private const val VALVE_OPEN_DELAY = 15 * 60_000L
    private const val VALVE_HOT_SMOKE = 100.0
    private const val VALVE_RETRY = 2 * 60_000L

    private fun valveNotify(title: String, text: String) {
        if (cfg().optBoolean("notify_state", true)) Notifier.stateChange(ctx, title, text)
    }

    private fun valveOpen(c: JSONObject, closed: JSONObject) {
        Hmip.restoreValves(closed.optString("id"), closed.optString("mode"), closed.optDouble("set", Double.NaN))
        c.remove("hm_valve_closed"); saveCfg(c); valve.offSince = 0
        valveNotify("Heizkörper wieder auf", "${closed.optString("label")}: zurück auf ${if (closed.optString("mode") == "MANUAL") "%.1f °C".format(closed.optDouble("set")) else "Wochenplan"}")
    }

    /** Vor dem Trennen von Homematic: geschlossene Heizkörper wieder öffnen */
    fun valveRelease() {
        val c = cfg(); val closed = c.optJSONObject("hm_valve_closed") ?: return
        try { valveOpen(c, closed) } catch (e: Exception) { }
    }

    /** Aufruf ohne Ofen-Verbindung: nach 30 min ohne Status die Heizkörper sicherheitshalber öffnen */
    fun valveOfflineCheck() {
        val c = cfg(); val closed = c.optJSONObject("hm_valve_closed") ?: return
        if (lastStatusAt != 0L && System.currentTimeMillis() - lastStatusAt < 30 * 60_000L) return
        if (System.currentTimeMillis() - valve.lastTry < VALVE_RETRY) return
        valve.lastTry = System.currentTimeMillis()
        try { valveOpen(c, closed) } catch (e: Exception) { }
    }

    private fun valveCheck(s: JSONObject) {
        val c = cfg()
        val halted = c.optBoolean("halted")
        val on = c.optBoolean("hm_valve") && !halted
        val closed = c.optJSONObject("hm_valve_closed")
        val info = JSONObject().put("enabled", c.optBoolean("hm_valve")).put("closed", closed != null)
        s.put("valve", info)
        if (halted && closed == null) { if (c.optBoolean("hm_valve")) info.put("msg", "pausiert – Ofen ausgeschaltet"); return }
        if (!on && closed == null) return
        if (!Hmip.connected()) { info.put("msg", "Homematic nicht verbunden"); return }
        val id = c.optString("hm_valve_id")
        val now = System.currentTimeMillis()
        // Funktion ausgeschaltet oder anderer Raum gewählt -> alten Raum sofort zurückstellen
        if (closed != null && (!on || closed.optString("id") != id)) {
            if (now - valve.lastTry < VALVE_RETRY) { info.put("msg", "öffnet gleich wieder"); return }
            valve.lastTry = now
            valveOpen(c, closed); info.put("closed", false).put("msg", "wieder auf"); return
        }
        Hmip.refresh()
        val sen = Hmip.sensor(id)
        info.put("label", sen?.optString("label") ?: closed?.optString("label") ?: "")
        if (sen == null || !id.startsWith("g:")) { info.put("msg", "Raum nicht gefunden"); return }
        val smoke = s.optString("smoke_temp").toDoubleOrNull() ?: 0.0
        val heating = s.optString("state") in VALVE_HEAT || early.stopped || man.wood || smoke >= VALVE_HOT_SMOKE
        if (heating) {
            valve.offSince = 0
            if (closed == null) {
                if (now - valve.lastTry < VALVE_RETRY) { info.put("msg", "schließt gleich – letzter Versuch fehlgeschlagen"); return }
                valve.lastTry = now
                val temp = c.optDouble("hm_valve_temp", 5.0)
                Hmip.closeValves(id, temp)
                c.put("hm_valve_closed", JSONObject().put("id", id).put("label", sen.optString("label"))
                    .put("mode", sen.optString("mode").ifBlank { "AUTOMATIC" })
                    .put("set", sen.optDouble("set", Double.NaN).let { if (it.isNaN()) JSONObject.NULL else it }).put("since", now))
                saveCfg(c)
                valveNotify("Heizkörper zu", "${sen.optString("label")}: Ofen heizt – Heizkörper auf %.1f °C".format(temp))
                info.put("closed", true).put("msg", "zu – Ofen heizt")
            } else info.put("msg", "zu seit ${hhmm(closed.optLong("since"))} – Ofen heizt")
        } else if (closed != null) {
            if (valve.offSince == 0L) valve.offSince = now
            val at = valve.offSince + VALVE_OPEN_DELAY
            if (now >= at && now - valve.lastTry >= VALVE_RETRY) {
                valve.lastTry = now
                valveOpen(c, closed); info.put("closed", false).put("msg", "wieder auf – Ofen aus")
            } else info.put("msg", "Ofen aus – Heizkörper öffnen um ${hhmm(maxOf(at, now))}")
        } else info.put("msg", "auf – schließen, sobald der Ofen heizt")
    }

    // ------------------------------------------------------------------ Raumsteuerung (Homematic IP)
    // Startet den Ofen, wenn der gewählte Raum 0,5 °C unter dem Ziel liegt, und stoppt ihn 0,5 °C darüber.
    // Schutz für Ofen und Zünder: mindestens 60 min Laufzeit, mindestens 30 min Pause.
    class Thermo { var lastStart = 0L; var lastStop = 0L; var waitStartSent = 0L; var lastBoilerSet = 0L }
    val thermo = Thermo()
    private const val TH_HYST = 0.5
    private const val TH_MIN_RUN = 60 * 60_000L
    private const val TH_MIN_PAUSE = 30 * 60_000L
    private val TH_STOPPED = setOf("14", "23")

    private fun hhmm(ms: Long) = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).format(java.util.Date(ms))

    /** Eigenes Ziel des Ofens mindestens 2 °C über das Homematic-Ziel heben, damit er nicht vorher
     *  von selbst auf kleine Flamme geht. Der alte Wert wird gemerkt (hm_prev_boiler). */
    private fun raiseBoilerTarget(c: JSONObject, s: JSONObject, target: Double) {
        val want = minOf(35, Math.ceil(target + 2.0).toInt())
        val cur = s.optString("target_temp").toDoubleOrNull() ?: return
        val now = System.currentTimeMillis()
        if (cur >= want || now - thermo.lastBoilerSet < 60_000L) return
        thermo.lastBoilerSet = now
        if (!c.has("hm_prev_boiler")) { c.put("hm_prev_boiler", Math.round(cur).toInt()); saveCfg(c) }
        try { set("boiler.temp", want.toString()); s.put("target_temp", want) } catch (e: Exception) { }
    }

    /** Beim Zurückschalten auf den Ofen-Fühler: gemerkte Ofen-Zieltemperatur wiederherstellen */
    fun restoreBoilerTarget(c: JSONObject) {
        if (!c.has("hm_prev_boiler")) return
        val prev = c.optInt("hm_prev_boiler")
        c.remove("hm_prev_boiler")
        try { set("boiler.temp", prev.toString()) } catch (e: Exception) { }
    }

    private fun thermoCheck(s: JSONObject) {
        val c = cfg()
        val info = JSONObject().put("enabled", c.optBoolean("hm_ctrl"))
        s.put("thermo", info)
        if (!c.optBoolean("hm_ctrl")) return
        if (!c.optBoolean("expert")) {   // Raumsteuerung startet den Ofen selbsttätig -> nur im Expertenmodus
            if (c.has("hm_prev_boiler")) { restoreBoilerTarget(c); saveCfg(c) }
            info.put("enabled", false).put("msg", "nur im Expertenmodus"); return
        }
        if (c.optBoolean("halted")) { info.put("msg", "pausiert – Ofen ausgeschaltet"); return }
        if (!Hmip.connected()) { info.put("msg", "Homematic nicht verbunden"); return }
        Hmip.refresh()
        val sen = Hmip.sensor(c.optString("hm_ctrl_id"))
        val target = c.optDouble("hm_ctrl_target", 21.0)
        info.put("target", target)
        raiseBoilerTarget(c, s, target)
        if (sen == null) { info.put("msg", "Raum nicht gefunden"); return }
        val t = sen.optDouble("temp", Double.NaN)
        info.put("label", sen.optString("label")).put("temp", if (t.isNaN()) JSONObject.NULL else t)
        if (t.isNaN()) { info.put("msg", "keine Temperatur"); return }
        if (Hmip.ageSeconds() > 15 * 60) { info.put("msg", "pausiert – Homematic-Werte zu alt"); return }
        val st = s.optString("state")
        if (st == "9" || man.wood || early.stopped) { info.put("msg", "pausiert (Holzbetrieb)"); return }
        if (man.active) { info.put("msg", "pausiert (Handbetrieb)"); return }
        val now = System.currentTimeMillis()
        if (st != "0") thermo.waitStartSent = 0
        when {
            t <= target - TH_HYST && st in TH_STOPPED -> {
                val ok = now - thermo.lastStop >= TH_MIN_PAUSE
                if (ok) {
                    set("misc.start", "1"); thermo.lastStart = now
                    info.put("msg", "Ofen gestartet – Raum zu kalt")
                } else info.put("msg", "zu kalt – Start um ${hhmm(thermo.lastStop + TH_MIN_PAUSE)} (Mindestpause)")
            }
            t >= target + TH_HYST && st in PELLET_RUN -> {
                val ok = now - thermo.lastStart >= TH_MIN_RUN
                if (ok) {
                    set("misc.stop", "1"); thermo.lastStop = now
                    info.put("msg", "Ofen gestoppt – Raum warm genug")
                } else info.put("msg", "warm genug – Stopp um ${hhmm(thermo.lastStart + TH_MIN_RUN)} (Mindestlaufzeit)")
            }
            st in TH_STOPPED -> info.put("msg", "aus – startet unter ${"%.1f".format(target - TH_HYST)} °C")
            st in PELLET_RUN || st in setOf("2", "4", "32") -> info.put("msg", "heizt – stoppt ab ${"%.1f".format(target + TH_HYST)} °C")
            // „Warten“: Ofen ist bereit, brennt aber nicht – die App übernimmt Start und Stopp
            st == "0" && t >= target + TH_HYST -> {
                set("misc.stop", "1") // brennt nicht -> keine Mindestlaufzeit, keine Pause nötig
                thermo.waitStartSent = 0
                info.put("msg", "Ofen gestoppt – Raum warm genug (war in „Warten“)")
            }
            st == "0" && t <= target - TH_HYST -> {
                when {
                    now - thermo.lastStop < TH_MIN_PAUSE ->
                        info.put("msg", "zu kalt – Start um ${hhmm(thermo.lastStop + TH_MIN_PAUSE)} (Mindestpause)")
                    thermo.waitStartSent == 0L || now - thermo.waitStartSent >= 10 * 60_000L -> {
                        set("misc.start", "1"); thermo.lastStart = now; thermo.waitStartSent = now
                        info.put("msg", "Ofen gestartet – Raum zu kalt (war in „Warten“)")
                    }
                    now - thermo.waitStartSent < 5 * 60_000L ->
                        info.put("msg", "Startbefehl gesendet – warte auf Zündung")
                    else ->
                        info.put("msg", "Ofen startet aus „Warten“ nicht – neuer Versuch um ${hhmm(thermo.waitStartSent + 10 * 60_000L)}")
                }
            }
            st == "0" -> { thermo.waitStartSent = 0; info.put("msg", "Ofen wartet – Raum im Zielbereich") }
            else -> info.put("msg", "wartet – Ofen meldet „${s.optString("state_text")}“")
        }
    }

    // ------------------------------------------------------------------ Automatische Heizstufe
    // Je näher die Raumtemperatur am Ziel ist, desto kleiner die Heizstufe; sinkt sie wieder, geht die Stufe hoch.
    // Abstand zum Ziel: ab 1,5 °C darunter Hoch, ab 0,5 °C Mittel, darüber Niedrig. 0,2 °C Hysterese
    // und mindestens 10 min zwischen zwei Wechseln, damit die Stufe nicht ständig springt.
    private const val AP_HIGH = 1.5
    private const val AP_MID = 0.5
    private const val AP_HYST = 0.2
    private const val AP_MIN_GAP = 10 * 60_000L
    private val AP_PCT = mapOf(1 to "10", 2 to "50", 3 to "100")
    private val AP_NAME = mapOf(1 to "Niedrig", 2 to "Mittel", 3 to "Hoch")
    @Volatile private var apLastSet = 0L

    fun autoPowerOn() = cfg().optBoolean("auto_power")

    private fun autoPowerCheck(s: JSONObject) {
        val c = cfg()
        val info = JSONObject().put("enabled", c.optBoolean("auto_power"))
        s.put("autopower", info)
        if (!c.optBoolean("auto_power")) return
        if (c.optBoolean("halted")) { info.put("msg", "pausiert – Ofen ausgeschaltet"); return }
        val st = s.optString("state")
        if (st == "9" || man.wood) { info.put("msg", "pausiert (Holzbetrieb)"); return }
        if (man.active) { info.put("msg", "pausiert (Handbetrieb)"); return }
        // Welche Temperatur zählt: Homematic-Raum (wenn die Raumsteuerung aktiv ist) oder Ofen-Fühler
        val th = s.optJSONObject("thermo")
        val hm = c.optBoolean("hm_ctrl") && c.optBoolean("expert") && th != null && th.has("target") &&
            th.opt("temp") is Number && Hmip.ageSeconds() <= 15 * 60
        val t = if (hm) th!!.optDouble("temp") else s.optString("room_temp").toDoubleOrNull()
        val target = if (hm) th!!.optDouble("target") else s.optString("target_temp").toDoubleOrNull()
        if (t == null || target == null || t.isNaN() || target.isNaN()) { info.put("msg", "keine Temperatur"); return }
        val cur = mapOf("10" to 1, "50" to 2, "100" to 3)[s.optString("fixed_power").substringBefore('.')]
        val d = target - t
        // gewünschte Stufe mit Hysterese um die aktuelle herum
        val h = AP_HYST
        val want = when (cur) {
            3 -> if (d < AP_HIGH - h) (if (d < AP_MID - h) 1 else 2) else 3
            2 -> if (d >= AP_HIGH + h) 3 else if (d < AP_MID - h) 1 else 2
            1 -> if (d >= AP_MID + h) (if (d >= AP_HIGH + h) 3 else 2) else 1
            else -> if (d >= AP_HIGH) 3 else if (d >= AP_MID) 2 else 1
        }
        info.put("level", cur ?: JSONObject.NULL).put("want", want)
        val src = if (hm) "Raum" else "Ofen-Fühler"
        val diff = "%.1f".format(java.util.Locale.GERMANY, kotlin.math.abs(d))
        val where = if (d > 0) "$diff °C unter Ziel" else "$diff °C über Ziel"
        if (st !in PELLET_RUN) { info.put("msg", "aktiv, sobald der Ofen heizt"); return }
        if (cur == want) { info.put("msg", "${AP_NAME[want]} – $src $where"); return }
        val now = System.currentTimeMillis()
        if (cur != null && now - apLastSet < AP_MIN_GAP) {
            info.put("msg", "wechselt um ${hhmm(apLastSet + AP_MIN_GAP)} auf ${AP_NAME[want]} – $src $where"); return
        }
        set("regulation.fixed_power", AP_PCT.getValue(want)); apLastSet = now
        s.put("fixed_power", AP_PCT.getValue(want)); info.put("level", want)
        info.put("msg", "auf ${AP_NAME[want]} gestellt – $src $where")
        if (c.optBoolean("notify_state", true) && cur != null)
            try { Notifier.stateChange(ctx, "Heizstufe ${AP_NAME[want]}", "$src $where") } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ Hauptschalter (Ausschalten / Starten)
    // „Ausschalten“ stoppt den Ofen und pausiert ALLE Automatiken der App (Raumsteuerung, Pellet-Neustart nach Holz,
    // Pellets früher stoppen, Heizkörper-Steuerung), bis wieder „Starten“ gedrückt wird. Die Einstellungen bleiben erhalten.
    // Wird der Ofen am Gerät oder in der Aduro-App gestartet, gilt das ebenfalls als „Starten“.
    private val HALT_RUNNING = setOf("2", "4", "5", "6", "32")
    private var haltLastStop = 0L

    fun halted() = cfg().optBoolean("halted")

    /** Stopp-Taste am Ofen (oder in der Aduro-App): Ofen meldet neu „Per Taste gestoppt“ (14), ohne dass die App
     *  in den letzten 5 min einen Stoppbefehl gesendet hat -> wie „Ausschalten“ behandeln. Stopps des Ofens selbst
     *  (Raumtemperatur erreicht -> „Warten“, Alarme) und das Ende des Holzbetriebs (Zustand 9) zählen nicht. */
    private fun externalStopCheck(prev: String?, st: String) {
        // 14 = „Per Taste gestoppt“ (23 = Timer zählt nicht); vorher heizend, „Warten“ oder „Fallschacht heiß“
        if (prev == null || st != "14" || (prev !in HALT_RUNNING && prev !in setOf("0", "11")) || early.stopped) return
        if (System.currentTimeMillis() - appStopAt < 5 * 60_000L) return
        val c = cfg()
        if (c.optBoolean("halted")) return
        cancelEarly(); early.aboveSince = 0
        c.put("halted", true).put("halted_at", System.currentTimeMillis()).put("halted_seen_off", true).put("halted_ext", true)
        saveCfg(c)
        if (man.active || man.deadline.isNotEmpty()) try { manualAllOff() } catch (e: Exception) { }
        valveRelease()
        Notifier.stateChange(ctx, "Ofen am Gerät ausgeschaltet",
            "Die App behandelt das wie „Ausschalten“: Automatiken pausiert, nichts startet von selbst. Wieder an mit „Starten“ in der App oder am Ofen.")
    }

    fun halt() {
        cancelEarly(); early.aboveSince = 0
        val c = cfg()
        c.put("halted", true).put("halted_at", System.currentTimeMillis()).put("halted_seen_off", false); c.remove("halted_ext"); saveCfg(c)
        if (man.active || man.deadline.isNotEmpty()) try { manualAllOff() } catch (e: Exception) { }
        valveRelease()
        haltLastStop = System.currentTimeMillis()
        set("misc.stop", "1")
    }

    fun resume() {
        cancelEarly()
        val c = cfg()
        for (k in listOf("halted", "halted_at", "halted_seen_off", "halted_ext")) c.remove(k)
        saveCfg(c)
        thermo.lastStart = System.currentTimeMillis()
        set("misc.start", "1")
    }

    /** true = ausgeschaltet; trägt den Zustand in den Status ein */
    private fun haltCheck(s: JSONObject): Boolean {
        val c = cfg()
        if (!c.optBoolean("halted")) { s.put("halted", false); return false }
        val st = s.optString("state")
        val now = System.currentTimeMillis()
        if (st !in HALT_RUNNING && st != "9") {
            if (!c.optBoolean("halted_seen_off")) { c.put("halted_seen_off", true); saveCfg(c) }
        } else if (c.optBoolean("halted_seen_off") && st in HALT_RUNNING) {
            // nach dem Stopp von außen (Taste am Ofen / Aduro-App) wieder gestartet -> wie „Starten“
            for (k in listOf("halted", "halted_at", "halted_seen_off", "halted_ext")) c.remove(k)
            saveCfg(c)
            Notifier.stateChange(ctx, "Ofen wieder eingeschaltet", "Außerhalb der App gestartet – die Automatiken der App sind wieder aktiv.")
            s.put("halted", false); return false
        } else if (st in HALT_RUNNING && now - haltLastStop > 2 * 60_000L) {
            // Stoppbefehl ist offenbar nicht angekommen -> noch einmal senden
            haltLastStop = now
            try { set("misc.stop", "1") } catch (e: Exception) { }
        }
        s.put("halted", true).put("halted_at", c.optLong("halted_at")).put("halted_ext", c.optBoolean("halted_ext"))
        return true
    }

    // ------------------------------------------------------------------ Expertenmodus
    // Sicherheitsrelevante Funktionen (Handbetrieb, Ofen-Werte schreiben, Pellets früher stoppen/Neustart,
    // Raumsteuerung mit selbsttätigem Start) gehen nur, wenn der Expertenmodus nach Bestätigung der Hinweise an ist.
    const val EXPERT_TEXT_VERSION = 1

    fun expert() = cfg().optBoolean("expert")

    fun requireExpert() { if (!expert()) throw StoveError("Nur im Expertenmodus möglich (Einstellungen → Expertenmodus).") }

    fun setExpert(on: Boolean, ackVersion: Int) {
        val c = cfg()
        if (on) {
            if (ackVersion < EXPERT_TEXT_VERSION) throw StoveError("Bitte zuerst Sicherheitshinweis und Haftungsausschluss bestätigen.")
            c.put("expert", true).put("expert_ack", JSONObject().put("v", ackVersion).put("at", System.currentTimeMillis()))
            saveCfg(c); return
        }
        // Ausschalten: alles Sicherheitsrelevante beenden und abschalten
        if (man.active || man.deadline.isNotEmpty()) try { manualAllOff() } catch (e: Exception) { }
        cancelEarly(); early.aboveSince = 0
        if (c.optBoolean("hm_ctrl")) restoreBoilerTarget(c)
        c.put("expert", false).put("hm_ctrl", false).put("early_stop", false).put("early_restart", false)
        saveCfg(c)
    }

    // ------------------------------------------------------------------ App ganz beenden
    /** Vor dem Beenden: alles zurückstellen, was ohne die App sonst falsch stehen bliebe. Der Ofen selbst bleibt, wie er ist. */
    fun appQuit() {
        cancelEarly(); early.aboveSince = 0
        if (man.active || man.deadline.isNotEmpty()) try { manualAllOff() } catch (e: Exception) { }
        valveRelease()
        val c = cfg()
        if (c.optBoolean("hm_ctrl")) restoreBoilerTarget(c)
        c.remove("app_off"); c.put("app_on", false); saveCfg(c)
    }

    /** „Funktionen starten“: erst danach spricht die App mit dem Ofen und die Überwachung läuft */
    fun appOn() {
        val c = cfg()
        c.remove("app_off"); c.put("app_on", true); saveCfg(c)
    }

    /** Laufen die Funktionen? Ab Werk aus – der Nutzer startet sie auf der Hauptseite. */
    fun appRunning() = cfg().optBoolean("app_on")

    // ------------------------------------------------------------------ Pellet-Füllstand
    // Die NBE-Steuerung zählt selbst: hopper.content (kg) wird mit jeder Schneckenumdrehung kleiner
    // (Kalibrierung hopper.auger_capacity = g pro 6 min). Die App setzt den Wert beim Nachfüllen und liest ihn alle 5 min.
    class Hopper { var kg = Double.NaN; var at = 0L; var warned = false }
    val hopper = Hopper()
    private const val HOPPER_EVERY = 5 * 60_000L

    fun hopperRead(force: Boolean = false): Double {
        if (!force && System.currentTimeMillis() - hopper.at < HOPPER_EVERY) return hopper.kg
        val v = getKv(Nbe.FN_SETTINGS, "hopper.content")["content"]?.toDoubleOrNull() ?: Double.NaN
        hopper.kg = v; hopper.at = System.currentTimeMillis()
        return v
    }

    /** Füllstand im Ofen setzen (kg); absolut oder dazuzählen */
    fun hopperSet(kg: Double, add: Boolean): JSONObject {
        val cur = try { hopperRead(true) } catch (e: Exception) { Double.NaN }
        val base = if (add && !cur.isNaN() && cur > 0) cur else 0.0
        val cap = cfg().optDouble("hopper_cap", 13.0)
        val v = Math.round((base + kg).coerceIn(0.0, if (add) cap else 999.0)).toInt()
        set("hopper.content", v.toString())
        val back = hopperRead(true)
        if (back.isNaN() || Math.round(back).toInt() != v)
            throw StoveError("Der Ofen hat den Füllstand nicht übernommen (meldet ${if (back.isNaN()) "–" else back.toInt().toString()} kg).")
        hopper.warned = false
        val c = cfg(); c.put("hopper_filled_at", System.currentTimeMillis()).put("hopper_filled_kg", v); saveCfg(c)
        return JSONObject().put("kg", v)
    }

    private fun hopperCheck(s: JSONObject) {
        val c = cfg()
        val cap = c.optDouble("hopper_cap", 13.0)
        val warn = c.optDouble("hopper_warn", 3.0)
        val kg = hopperRead()
        val info = JSONObject().put("cap", cap).put("warn", warn)
        s.put("hopper", info)
        if (kg.isNaN()) return
        info.put("kg", kg).put("known", kg >= 0 && c.has("hopper_filled_at"))
        // Restlaufzeit grob aus der aktuellen Leistung (Pellets ca. 4,8 kWh/kg, Wirkungsgrad ca. 90 %)
        val kw = s.optString("power_kw").toDoubleOrNull() ?: 0.0
        if (kw > 0.3 && kg > 0) info.put("hours", kg / (kw / (4.8 * 0.9)))
        if (!info.optBoolean("known") || !c.optBoolean("hopper_notify", true)) return
        if (kg <= warn && !hopper.warned) {
            hopper.warned = true
            Notifier.alert(ctx, Notifier.ID_HOPPER, "Pellets nachfüllen", "Noch etwa ${Math.max(0, kg.toInt())} kg im Behälter")
        } else if (kg > warn + 1) hopper.warned = false
    }

    /** Manueller Stopp in der App: Raumsteuerung aus, damit sie den Ofen nicht wieder startet. */
    fun thermoOffByUser(): Boolean {
        val c = cfg()
        if (!c.optBoolean("hm_ctrl")) return false
        c.put("hm_ctrl", false); saveCfg(c)
        return true
    }

    // ------------------------------------------------------------------ Holzverbrennung Temperatur
    private const val PSTOP_PATH = "boiler.min_return"
    private var pstopRange: JSONObject? = null

    private fun pelletStopRange(): JSONObject {
        pstopRange?.let { return it }
        var lo = 270.0; var hi = 350.0; var src = "Standard"
        try {
            val txt = get(Nbe.FN_RANGE, PSTOP_PATH)
            val nums = Regex("-?\\d+(?:\\.\\d+)?").findAll(txt).map { it.value.toDouble() }.toList()
            if (nums.size >= 2 && nums[0] > 0 && nums[0] < nums[1] && nums[1] <= 1000) { lo = nums[0]; hi = nums[1]; src = "Ofen" }
        } catch (e: Exception) { }
        return JSONObject().put("min", lo.toInt()).put("max", hi.toInt()).put("source", src).also { pstopRange = it }
    }

    private fun readPelletStop(): Double {
        val txt = get(Nbe.FN_SETTINGS, PSTOP_PATH)
        val v = Nbe.kv(txt)["min_return"] ?: txt.substringAfter("=")
        return v.trim().toDouble()
    }

    fun getPelletStop(): JSONObject {
        val rg = pelletStopRange()
        return JSONObject().put("value", readPelletStop()).put("min", rg.getInt("min")).put("max", rg.getInt("max")).put("source", rg.getString("source"))
    }

    fun setPelletStop(v0: Double): JSONObject {
        val v = Math.round(v0).toInt()
        val rg = pelletStopRange()
        if (v < rg.getInt("min") || v > rg.getInt("max"))
            throw StoveError("Der Ofen erlaubt hier nur ${rg.getInt("min")}–${rg.getInt("max")} °C.")
        var back = 0.0
        for (value in listOf("$v", "$v.0")) { // manche Firmware erwartet eine Kommazahl
            set(PSTOP_PATH, value)
            repeat(3) {
                Thread.sleep(1000)
                back = readPelletStop()
                if (Math.round(back).toInt() == v) return JSONObject().put("value", v).put("verified", true)
            }
        }
        throw StoveError("Der Ofen hat $v °C nicht übernommen – er meldet weiterhin ${back.toInt()} °C. " +
            "(Antwort des Ofens: Status ${lastSet.opt("status")}, „${lastSet.opt("payload")}“)")
    }

    // ------------------------------------------------------------------ Handbetrieb
    // manual.manual_mode = 1 schaltet Handbetrieb ein, manual.output_std ist eine Bitmaske der Ausgänge.
    class Man {
        var active = false
        val deadline = mutableMapOf<Int, Long?>() // Bit -> Ende (ms) oder null = ohne Limit
        var lastKeep = 0L
        var error = ""
        var wood = false
    }
    val man = Man()
    private val manLock = Any()
    const val N_OUTPUTS = 8
    private const val MAX_MIN = 240.0

    private fun bits(): Int = man.deadline.keys.fold(0) { acc, b -> acc or (1 shl b) }

    private fun applyMan() {
        val b = bits()
        if (b != 0 && !man.active) { set("manual.manual_mode", "1"); man.active = true }
        set("manual.output_std", b.toString())
        if (b == 0 && man.active) { set("manual.manual_mode", "0"); man.active = false }
        if (b == 0) man.wood = false
        man.lastKeep = System.currentTimeMillis()
    }

    fun manualSwitch(bit: Int, on: Boolean, minutes: Double?) {
        if (bit !in 0 until N_OUTPUTS) throw StoveError("Unbekannter Ausgang")
        val c = cfg()
        val fan = if (c.has("fresh_air_bit")) c.optInt("fresh_air_bit") else -1
        val names = c.optJSONObject("output_names") ?: JSONObject()
        synchronized(manLock) {
            if (on) {
                if (!names.has((bit + 1).toString())) throw StoveError("Nur benannte Ausgänge können geschaltet werden.")
                if (man.wood && bit != fan) throw StoveError("Im Holzbetrieb ist nur das Frischluftgebläse freigegeben.")
                if (!man.active) {
                    val st = readStatus().optString("state")
                    if (st == "9") {
                        if (bit != fan) throw StoveError("Im Holzbetrieb ist nur das Frischluftgebläse freigegeben.")
                        man.wood = true
                    } else if (st in BURNING) {
                        throw StoveError("Handbetrieb geht nur bei gestopptem Ofen (im Holzbetrieb nur das Frischluftgebläse).")
                    }
                }
                if (minutes == null) {
                    if (bit != fan) throw StoveError("Ohne Zeitlimit darf nur das Frischluftgebläse laufen.")
                    man.deadline[bit] = null
                } else {
                    val m = minutes.coerceIn(0.1, MAX_MIN)
                    man.deadline[bit] = System.currentTimeMillis() + (m * 60_000).toLong()
                }
            } else {
                man.deadline.remove(bit)
            }
            applyMan()
            man.error = ""
        }
    }

    fun manualAllOff() {
        synchronized(manLock) {
            man.deadline.clear()
            try {
                set("manual.output_std", "0")
            } finally {
                set("manual.manual_mode", "0")
                man.active = false
                man.wood = false
            }
        }
    }

    fun manualState(): JSONObject {
        val now = System.currentTimeMillis()
        val c = cfg()
        val running = JSONObject()
        synchronized(manLock) {
            for ((b, d) in man.deadline) running.put(b.toString(), if (d == null) JSONObject.NULL else maxOf(0L, (d - now) / 1000))
        }
        return JSONObject()
            .put("active", man.active).put("error", man.error).put("wood", man.wood)
            .put("fresh_air_bit", if (c.has("fresh_air_bit")) c.get("fresh_air_bit") else JSONObject.NULL)
            .put("names", c.optJSONObject("output_names") ?: JSONObject())
            .put("running", running)
    }

    fun busy(): Boolean = man.active || man.deadline.isNotEmpty() || early.stopped || cfg().optBoolean("early_stop")

    /** alle 2 s: abgelaufene Ausgänge abschalten, Verbindung halten */
    fun manualTick() {
        try {
            synchronized(manLock) {
                if (!man.active && man.deadline.isEmpty()) return
                val now = System.currentTimeMillis()
                val expired = man.deadline.filter { (_, d) -> d != null && d <= now }.keys
                expired.forEach { man.deadline.remove(it) }
                if (expired.isNotEmpty()) applyMan()
                else if (man.active && now - man.lastKeep > 10_000) {
                    set("manual.keep_alive", "1"); man.lastKeep = now
                }
            }
        } catch (e: Exception) {
            man.error = e.message ?: "Fehler"
        }
    }
}
