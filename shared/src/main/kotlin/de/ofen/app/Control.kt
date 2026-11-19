package de.ofen.app

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/**
 * Wer hat die Steuerung? – Raspi und Handys parallel nutzen.
 *
 * Bedienen und Anzeigen geht auf allen Geräten. Die AUTOMATIKEN (Raumsteuerung, automatische Heizstufe,
 * Pellets früher stoppen / Neustart, Heizkörper, „Ausschalten“-Überwachung) laufen aber immer nur auf
 * genau einem Gerät – sonst würden sich zwei Regler gegenseitig den Ofen starten und stoppen.
 *
 * Der Raspi ist die Zentrale und merkt sich, wer die Steuerung hat:
 *  - kind = "raspi"  → der Raspi selbst
 *  - kind = "phone"  → ein gekoppeltes Handy (id/name)
 *  - kind = "none"   → freigegeben, keine Automatik aktiv
 *
 * Das steuernde Gerät ist die Quelle für Einstellungen und laufenden Zustand („Paket“): Heizkörper zu,
 * ausgeschaltet, Mindestlaufzeiten … Alle anderen spiegeln das Paket. So macht nach einer Übergabe das
 * neue Gerät genau dort weiter, wo das alte aufgehört hat.
 *
 * Sicherheit:
 *  - Ein steuerndes Handy meldet sich alle 30 s beim Raspi. Ohne Kontakt pausiert es seine Automatik
 *    nach 2 min; der Raspi holt sich die Steuerung nach 3 min zurück (abschaltbar → dann „freigegeben“).
 *  - Startet der Raspi neu, wartet er 90 s („Startschutz“), ob sich ein Handy mit Notfall-Übernahme meldet.
 *  - Notfall-Übernahme am Handy nur, wenn der Raspi nicht erreichbar ist. Meldet er sich wieder,
 *    gewinnt die neuere Übernahme (höhere Epoche), bei Gleichstand der Raspi.
 */
object Control {
    const val HB_MS = 30_000L
    const val PHONE_STALE_MS = 2 * 60_000L
    const val PHONE_LOST_MS = 3 * 60_000L
    const val BOOT_GUARD_MS = 90_000L
    private const val CODE_MS = 10 * 60_000L

    /** Einstellungen + laufender Zustand, die mit der Steuerung wandern (exakt gespiegelt, auch „nicht gesetzt“) */
    val STATE_KEYS = listOf(
        "wood_min", "early_stop", "early_restart", "early_temp", "early_restart_temp", "auto_power",
        "hm_ctrl", "hm_ctrl_id", "hm_ctrl_target", "hm_valve", "hm_valve_id", "hm_valve_temp",
        "hm_valve_closed", "hm_prev_boiler",
        "halted", "halted_at", "halted_seen_off", "halted_ext", "warm_hold",
        "expert", "expert_ack", "output_names", "fresh_air_bit",
        "hopper_cap", "hopper_warn", "hopper_notify", "hopper_filled_at", "hopper_filled_kg",
    )
    /** Zugangsdaten: werden nur übernommen, wenn sie gesetzt sind (nie gelöscht) */
    val CRED_KEYS = listOf("ip", "serial", "pin", "hm_ap", "hm_rest", "hm_token")
    /** Einstellungen, die nur das Gerät mit der Steuerung ändern darf */
    val LOCKED_CFG = setOf(
        "early_stop", "early_restart", "early_temp", "early_restart_temp", "auto_power",
        "hm_ctrl", "hm_ctrl_id", "hm_ctrl_target", "hm_valve", "hm_valve_id", "hm_valve_temp", "wood_min",
    )
    /** Gehören nur zu diesem Gerät (Darstellung, Benachrichtigungen) – werden am Handy nie zum Raspi geschickt */
    val DEVICE_CFG = setOf("vpn_app", "bg_dim", "card_alpha", "notify_state")

    private fun now() = System.currentTimeMillis()
    private val raspi get() = Stove.platform.kind == "raspi"

    // ------------------------------------------------------------------ Gerät
    fun deviceId(): String {
        val c = Stove.cfg()
        c.optString("dev_id").takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        c.put("dev_id", id); Stove.saveCfg(c)
        return id
    }

    fun myName(): String =
        if (raspi) Stove.cfg().optString("dev_name").ifBlank { "Raspi" } else Stove.platform.deviceName

    // ------------------------------------------------------------------ Wer steuert
    fun ctl(): JSONObject {
        Stove.cfg().optJSONObject("ctl")?.let { return it }
        return if (raspi) JSONObject().put("kind", "raspi").put("id", deviceId()).put("name", myName()).put("epoch", 1).put("since", now())
        else JSONObject()
    }

    private fun saveCtl(o: JSONObject) {
        synchronized(Stove) { val c = Stove.cfg(); c.put("ctl", o); Stove.saveCfg(c) }
    }

    /** Ist dieses Gerät das steuernde? (Handy ohne Raspi: immer) */
    fun isHolder(): Boolean {
        val c = ctl()
        return if (raspi) c.optString("kind") == "raspi"
        else !Link.linked() || (c.optString("kind") == "phone" && c.optString("id") == deviceId())
    }

    /** Darf dieses Gerät JETZT automatisch schalten? */
    fun active(): Boolean {
        if (raspi) return isHolder() && now() >= guardUntil
        if (!Link.linked()) return true
        if (!isHolder()) return false
        return ctl().optBoolean("forced") || now() - Link.lastOk < PHONE_STALE_MS
    }

    /** Darf dieses Gerät Automatik-Einstellungen ändern? Raspi auch, wenn freigegeben. */
    fun mayConfigure(): Boolean = if (raspi) ctl().optString("kind") != "phone" else isHolder()

    fun holderLabel(): String {
        val c = ctl()
        return when (c.optString("kind")) {
            "raspi" -> if (raspi) "diesem Raspi" else c.optString("name").let { n -> if (n.isBlank() || n == "Raspi") "dem Raspi" else "dem Raspi „$n“" }
            "phone" -> if (!raspi && c.optString("id") == deviceId()) "diesem Handy" else "dem Handy „${c.optString("name")}“"
            "none" -> "keinem Gerät"
            else -> "dem Raspi"
        }
    }

    fun passiveMsg(): String = when {
        Recorder.observing() -> "pausiert – Zündprotokoll, die App beobachtet nur"
        ctl().optString("kind") == "none" -> "pausiert – Steuerung ist freigegeben"
        !raspi && isHolder() && !active() -> "pausiert – Raspi nicht erreichbar"
        raspi && isHolder() && !active() -> "Startschutz – noch ${guardLeft()} s"
        !raspi && Link.linked() && !Link.reachable() -> "Raspi nicht erreichbar – Automatik läuft dort, falls er läuft"
        else -> "läuft auf ${holderLabel()}"
    }

    fun lockedMsg(): String = when (ctl().optString("kind")) {
        "none" -> "Die Steuerung ist freigegeben. Zum Ändern zuerst „Steuerung übernehmen“."
        else -> "Die Steuerung liegt gerade bei ${holderLabel()}. Zum Ändern hier zuerst „Steuerung übernehmen“."
    }

    // ------------------------------------------------------------------ Paket (Einstellungen + Zustand)
    @Volatile private var remote: JSONObject? = null

    /** Meldungen der Automatiken vom steuernden Gerät (nur zum Anzeigen) */
    fun remoteInfo(key: String): JSONObject? =
        if (!raspi && Link.linked() && !Link.reachable()) null   // Raspi weg: alte Meldungen nicht weiter anzeigen
        else remote?.optJSONObject(key)

    fun pack(): JSONObject {
        val c = Stove.cfg()
        val st = JSONObject(); for (k in STATE_KEYS) if (c.has(k)) st.put(k, c.get(k))
        val cr = JSONObject(); for (k in CRED_KEYS) c.optString(k).takeIf { it.isNotBlank() }?.let { cr.put(k, it) }
        val rt = JSONObject()
            .put("early_stopped", Stove.early.stopped).put("early_msg", Stove.early.msg)
            .put("th_start", Stove.thermo.lastStart).put("th_stop", Stove.thermo.lastStop).put("th_wait", Stove.thermo.waitStartSent)
            .put("ap_last", Stove.apLastSet).put("valve_off", Stove.valve.offSince)
        val info = JSONObject()
        if (isHolder()) Stove.lastStatus?.let { s -> for (k in listOf("early", "thermo", "autopower", "valve")) s.optJSONObject(k)?.takeIf { !it.optBoolean("remote") }?.let { info.put(k, it) } }
        else remote?.let { r -> for (k in r.keys()) info.put(k, r.get(k)) }
        return JSONObject().put("state", st).put("cred", cr).put("rt", rt).put("info", info).put("at", now())
    }

    fun applyPack(p: JSONObject?) {
        if (p == null) return
        val st = p.optJSONObject("state") ?: JSONObject()
        val cr = p.optJSONObject("cred") ?: JSONObject()
        synchronized(Stove) {
            val c = Stove.cfg()
            for (k in STATE_KEYS) if (st.has(k)) c.put(k, st.get(k)) else c.remove(k)
            for (k in CRED_KEYS) cr.optString(k).takeIf { it.isNotBlank() }?.let { c.put(k, it) }
            Stove.saveCfg(c)
        }
        p.optJSONObject("rt")?.let { rt ->
            Stove.early.stopped = rt.optBoolean("early_stopped"); Stove.early.msg = rt.optString("early_msg"); Stove.saveEarly()
            Stove.thermo.lastStart = rt.optLong("th_start"); Stove.thermo.lastStop = rt.optLong("th_stop")
            Stove.thermo.waitStartSent = rt.optLong("th_wait")
            Stove.apLastSet = rt.optLong("ap_last"); Stove.valve.offSince = rt.optLong("valve_off")
        }
        remote = p.optJSONObject("info")
    }

    /** Automatiken sauber beenden: Heizkörper zurück, Ofen-Ziel zurück, Handbetrieb aus */
    private fun cleanup() {
        try { if (Stove.man.active || Stove.man.deadline.isNotEmpty()) Stove.manualAllOff() } catch (e: Exception) { }
        Stove.cancelEarly(); Stove.early.aboveSince = 0
        try { Stove.valveRelease() } catch (e: Exception) { }
        try { val c = Stove.cfg(); if (c.optBoolean("hm_ctrl") && c.has("hm_prev_boiler")) { Stove.restoreBoilerTarget(c); Stove.saveCfg(c) } } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ Meldungen (Raspi → Handys)
    private val events = ArrayDeque<JSONObject>()
    private var lastEv = 0L

    fun event(kind: String, title: String, text: String, alertId: Int = 0) {
        synchronized(events) {
            lastEv = maxOf(lastEv + 1, now())
            events.addLast(JSONObject().put("id", lastEv).put("kind", kind).put("aid", alertId).put("title", title).put("text", text))
            while (events.size > 60) events.removeFirst()
        }
    }

    private fun eventsSince(id: Long): JSONArray = synchronized(events) { JSONArray(events.filter { it.optLong("id") > id }) }
    private fun lastEventId(): Long = synchronized(events) { lastEv }

    // ================================================================== Raspi
    @Volatile private var guardUntil = 0L
    @Volatile private var phoneSeen = 0L
    private fun guardLeft() = maxOf(0L, (guardUntil - now()) / 1000)

    /** Beim Start des Raspi-Servers */
    fun startRaspi() {
        val c = ctl()
        if (c.optString("kind") == "raspi") guardUntil = now() + BOOT_GUARD_MS
        phoneSeen = now()
        saveCtl(c)
        newCode()
    }

    private fun autoBack() = Stove.cfg().optBoolean("ctl_auto_back", true)

    private fun setOwner(kind: String, id: String, name: String, why: String, epoch: Int = 0) {
        val old = ctl()
        if (raspi && kind != "raspi" && (Stove.man.active || Stove.man.deadline.isNotEmpty()))
            try { Stove.manualAllOff() } catch (e: Exception) { }
        val ep = maxOf(old.optInt("epoch") + 1, epoch)
        saveCtl(JSONObject().put("kind", kind).put("id", id).put("name", name).put("epoch", ep).put("since", now()))
        if (kind == "raspi") guardUntil = 0
        if (kind == "phone") phoneSeen = now()
        if (raspi) {
            if (kind == "none") cleanup()   // niemand steuert -> nichts darf „zu“ oder verstellt bleiben
            if (kind != "raspi") remote = null
            val title = when (kind) { "raspi" -> "Steuerung: Raspi"; "phone" -> "Steuerung: Handy „$name“"; else -> "Steuerung freigegeben" }
            event("control", title, why, Alerts.CONTROL)
        }
    }

    /** Raspi: regelmäßig aufrufen (Überwachungsschleife) */
    fun tickRaspi() {
        val c = ctl()
        if (c.optString("kind") == "phone" && now() - phoneSeen > PHONE_LOST_MS) {
            val n = c.optString("name")
            if (autoBack()) setOwner("raspi", deviceId(), myName(), "Handy „$n“ meldet sich seit 3 min nicht – der Raspi hat die Steuerung übernommen.")
            else setOwner("none", "", "", "Handy „$n“ meldet sich seit 3 min nicht – Steuerung freigegeben, Automatiken aus.")
        }
    }

    // ------------------------------------------------------------------ Kopplung (Raspi)
    @Volatile private var code = ""
    @Volatile private var codeAt = 0L
    private val badTries = ArrayDeque<Long>()
    private val rnd = SecureRandom()

    fun newCode(): String {
        code = (100000 + rnd.nextInt(900000)).toString(); codeAt = now()
        return code
    }

    private fun currentCode(): String { if (now() - codeAt > CODE_MS) newCode(); return code }

    private fun clients(): JSONObject = Stove.cfg().optJSONObject("clients") ?: JSONObject()

    /** Token prüfen → Geräte-ID oder null */
    fun clientByToken(token: String?): String? {
        if (token.isNullOrBlank()) return null
        val cl = clients()
        for (id in cl.keys()) if (cl.optJSONObject(id)?.optString("token") == token) return id
        return null
    }

    fun clientName(id: String): String = clients().optJSONObject(id)?.optString("name") ?: "Gerät"

    private val lastSeen = mutableMapOf<String, Long>()

    /** Neues Gerät koppeln (ohne Token, nur mit dem Code vom Touchscreen) */
    fun pair(b: JSONObject): JSONObject {
        synchronized(badTries) {
            while (badTries.isNotEmpty() && now() - badTries.first() > CODE_MS) badTries.removeFirst()
            if (badTries.size >= 5) throw StoveError("Zu viele falsche Codes – bitte 10 Minuten warten.")
            if (b.optString("code").trim() != currentCode()) {
                badTries.addLast(now())
                throw StoveError("Code stimmt nicht. Den aktuellen Code zeigt der Raspi unter Einstellungen → Raspi & Handys.")
            }
        }
        val id = b.optString("id").ifBlank { UUID.randomUUID().toString() }.take(64)
        val name = b.optString("name").trim().ifBlank { "Gerät" }.take(40)
        val token = ByteArray(24).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        synchronized(Stove) {
            val c = Stove.cfg(); val cl = c.optJSONObject("clients") ?: JSONObject()
            cl.put(id, JSONObject().put("name", name).put("token", token).put("at", now()).put("kind", b.optString("kind").ifBlank { "browser" }))
            c.put("clients", cl); Stove.saveCfg(c)
        }
        newCode()
        event("info", "Neues Gerät gekoppelt", name)
        return JSONObject().put("token", token).put("id", id).put("raspi_name", myName()).put("last_event", lastEventId())
    }

    private fun removeClient(id: String) {
        synchronized(Stove) {
            val c = Stove.cfg(); val cl = c.optJSONObject("clients") ?: JSONObject()
            cl.remove(id); c.put("clients", cl); Stove.saveCfg(c)
        }
        val c = ctl()
        if (c.optString("kind") == "phone" && c.optString("id") == id)
            setOwner("raspi", deviceId(), myName(), "Gekoppeltes Handy „${c.optString("name")}“ entfernt – der Raspi steuert.")
    }

    // ------------------------------------------------------------------ Anfragen von Handys (Raspi)
    fun remote(path: String, b: JSONObject, client: String): JSONObject {
        lastSeen[client] = now()
        val name = clientName(client)
        val c = ctl()
        val mine = c.optString("kind") == "phone" && c.optString("id") == client
        return when (path) {
            "remote/sync" -> JSONObject().put("ctl", ctl()).put("pack", pack())
                .put("events", eventsSince(b.optLong("since"))).put("last_event", lastEventId())
                .put("summary", Stove.lastStatus?.let { Stove.summary(it) } ?: "").put("raspi_name", myName())
            "remote/take" -> {
                val p = pack()   // Zustand VOR der Übergabe
                if (!mine) setOwner("phone", client, name, "Handy „$name“ hat die Steuerung übernommen.")
                JSONObject().put("ctl", ctl()).put("pack", p).put("last_event", lastEventId())
            }
            "remote/heartbeat" -> {
                if (mine) {
                    phoneSeen = now(); applyPack(b.optJSONObject("pack"))
                    JSONObject().put("holder", true).put("ctl", ctl()).put("last_event", lastEventId())
                } else if (b.optBoolean("forced") && b.optInt("epoch") > c.optInt("epoch")) {
                    applyPack(b.optJSONObject("pack"))
                    setOwner("phone", client, name, "Handy „$name“ hatte die Steuerung per Notfall-Übernahme – der Raspi lässt sie dort.", b.optInt("epoch"))
                    JSONObject().put("holder", true).put("ctl", ctl()).put("last_event", lastEventId())
                } else JSONObject().put("holder", false).put("ctl", ctl()).put("pack", pack()).put("last_event", lastEventId())
            }
            "remote/release" -> {
                if (mine) {
                    applyPack(b.optJSONObject("pack"))
                    if (autoBack()) setOwner("raspi", deviceId(), myName(), "Handy „$name“ hat freigegeben – der Raspi steuert weiter.")
                    else setOwner("none", "", "", "Handy „$name“ hat die Steuerung freigegeben.")
                }
                JSONObject().put("ctl", ctl()).put("last_event", lastEventId())
            }
            else -> throw StoveError("Unbekannt: $path")
        }
    }

    // ------------------------------------------------------------------ Bedienung am Raspi (Touchscreen / Browser)
    fun takeLocal(): JSONObject {
        if (raspi) {
            val c = ctl()
            if (c.optString("kind") != "raspi") {
                val why = if (c.optString("kind") == "phone") "Am Raspi übernommen – vorher hatte sie Handy „${c.optString("name")}“." else "Am Raspi übernommen."
                setOwner("raspi", deviceId(), myName(), why)
            } else guardUntil = 0   // Startschutz vorzeitig beenden
            return info()
        }
        return takeRemote()
    }

    fun releaseLocal(): JSONObject {
        if (raspi) {
            if (isHolder()) setOwner("none", "", "", "Am Raspi freigegeben – keine Automatik aktiv.")
            return info()
        }
        return releaseRemote()
    }

    // ================================================================== Handy
    /** Handy übernimmt vom Raspi */
    fun takeRemote(): JSONObject {
        if (!Link.linked()) throw StoveError("Nicht mit einem Raspi gekoppelt.")
        if (isHolder()) return info()
        val r = Link.remote("remote/take", JSONObject())
        Link.seenUpTo(r.optLong("last_event"))
        applyPack(r.optJSONObject("pack"))
        saveCtl(r.optJSONObject("ctl") ?: JSONObject())
        if (!isHolder()) throw StoveError("Übernahme hat nicht geklappt.")
        return info()
    }

    /** Handy gibt an den Raspi zurück (Einstellung am Raspi: zurück an den Raspi oder freigegeben) */
    fun releaseRemote(): JSONObject {
        if (!Link.linked() || !isHolder()) return info()
        val r = try { Link.remote("remote/release", JSONObject().put("epoch", ctl().optInt("epoch")).put("pack", pack())) }
            catch (e: StoveError) { throw StoveError("Raspi nicht erreichbar – die Steuerung bleibt auf diesem Handy.") }
        saveCtl(r.optJSONObject("ctl") ?: JSONObject())
        Link.seenUpTo(r.optLong("last_event"))
        remote = null
        return info()
    }

    /** Notfall: Raspi ist weg, Handy übernimmt ohne Rückfrage */
    fun forceLocal(): JSONObject {
        if (!Link.linked()) throw StoveError("Nicht mit einem Raspi gekoppelt.")
        if (isHolder()) return info()
        if (Link.ping()) throw StoveError("Der Raspi ist erreichbar – bitte normal „Übernehmen“.")
        saveCtl(JSONObject().put("kind", "phone").put("id", deviceId()).put("name", myName())
            .put("epoch", ctl().optInt("epoch") + 1).put("since", now()).put("forced", true))
        Stove.platform.alert(Alerts.CONTROL, "Notfall-Übernahme", "Dieses Handy steuert jetzt allein. Meldet sich der Raspi wieder, wird abgeglichen.")
        return info()
    }

    /** Beim „Funktionen beenden“ am Handy: Steuerung an den Raspi zurückgeben */
    fun handOverOnQuit(): Boolean {
        if (raspi || !Link.linked() || !isHolder()) return false
        return try { releaseRemote(); !isHolder() } catch (e: Exception) { false }
    }

    /** Antwort auf den Herzschlag des steuernden Handys */
    fun onHeartbeat(r: JSONObject) {
        val c = r.optJSONObject("ctl") ?: return
        if (r.optBoolean("holder")) {
            Link.seenUpTo(r.optLong("last_event"))   // eigene Meldungen hat das Handy selbst schon gezeigt
            if (ctl().optBoolean("forced") || ctl().optInt("epoch") != c.optInt("epoch")) saveCtl(c)
            return
        }
        // Raspi oder ein anderes Handy hat übernommen -> sofort aufhören
        saveCtl(c)
        Link.seenUpTo(r.optLong("last_event"))
        applyPack(r.optJSONObject("pack"))
        Stove.platform.alert(Alerts.CONTROL, "Steuerung abgegeben", "Die Steuerung liegt jetzt bei ${holderLabel()}. Dieses Handy zeigt nur noch an.")
    }

    /** Antwort auf den Abgleich eines nicht steuernden Handys */
    fun onSync(r: JSONObject) {
        r.optJSONObject("ctl")?.let { saveCtl(it) }
        if (!isHolder()) applyPack(r.optJSONObject("pack"))
    }

    /** Beim Koppeln: eigene Automatiken sauber beenden, danach übernimmt der Raspi */
    fun beforePair() { if (!raspi && !Link.linked()) cleanup() }

    /** Beim Entkoppeln: Automatiken hier ausschalten, damit nicht zwei Geräte regeln */
    fun afterUnpair() {
        synchronized(Stove) {
            val c = Stove.cfg()
            for (k in listOf("hm_ctrl", "auto_power", "early_stop", "early_restart", "hm_valve")) c.put(k, false)
            for (k in listOf("hm_valve_closed", "hm_prev_boiler", "ctl")) c.remove(k)
            Stove.saveCfg(c)
        }
        Stove.cancelEarly(); remote = null
    }

    // ------------------------------------------------------------------ Anzeige
    fun info(): JSONObject {
        val c = ctl()
        val o = JSONObject()
            .put("role", Stove.platform.kind).put("me", myName())
            .put("kind", c.optString("kind").ifBlank { if (raspi) "raspi" else "phone" })
            .put("name", c.optString("name")).put("since", c.optLong("since")).put("epoch", c.optInt("epoch"))
            .put("holder", isHolder()).put("active", active()).put("label", holderLabel())
        if (raspi) {
            o.put("linked", true).put("guard_s", if (isHolder()) guardLeft() else 0)
                .put("auto_back", autoBack())
            if (c.optString("kind") == "phone") o.put("phone_seen_s", (now() - phoneSeen) / 1000)
        } else {
            val l = Link.linked()
            o.put("linked", l)
            if (l) o.put("reachable", Link.reachable()).put("forced", c.optBoolean("forced"))
                .put("raspi_name", Stove.cfg().optJSONObject("link")?.optString("raspi_name") ?: "")
                .put("contact_s", if (Link.lastOk == 0L) -1 else (now() - Link.lastOk) / 1000)
        }
        return o
    }

    /** Seite „Raspi & Handys“ */
    fun linkInfo(): JSONObject {
        if (raspi) {
            val cl = clients(); val arr = JSONArray()
            for (id in cl.keys()) {
                val x = cl.optJSONObject(id) ?: continue
                arr.put(JSONObject().put("id", id).put("name", x.optString("name")).put("kind", x.optString("kind")).put("at", x.optLong("at"))
                    .put("seen_s", lastSeen[id]?.let { (now() - it) / 1000 } ?: -1))
            }
            val addrs = try {
                java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>().map { it.hostAddress }
            } catch (e: Exception) { emptyList() }
            return JSONObject().put("role", "raspi").put("name", myName()).put("code", currentCode()).put("addrs", JSONArray(addrs))
                .put("code_s", maxOf(0L, (codeAt + CODE_MS - now()) / 1000)).put("clients", arr).put("auto_back", autoBack())
        }
        val l = Stove.cfg().optJSONObject("link")
        return JSONObject().put("role", "phone").put("linked", Link.linked())
            .put("url", l?.optString("url") ?: "").put("raspi_name", l?.optString("raspi_name") ?: "")
            .put("reachable", Link.reachable())
    }

    fun linkPost(p: String, b: JSONObject): JSONObject = when (p) {
        "link/code" -> { if (!raspi) throw StoveError("Nur am Raspi"); newCode(); linkInfo() }
        "link/name" -> {
            if (!raspi) throw StoveError("Nur am Raspi")
            val n = b.optString("name").trim().take(30)
            synchronized(Stove) { val c = Stove.cfg(); if (n.isBlank()) c.remove("dev_name") else c.put("dev_name", n); Stove.saveCfg(c) }
            val c = ctl(); if (c.optString("kind") == "raspi") saveCtl(c.put("name", myName()))
            linkInfo()
        }
        "link/remove" -> { if (!raspi) throw StoveError("Nur am Raspi"); removeClient(b.optString("id")); linkInfo() }
        "link/auto_back" -> {
            if (!raspi) throw StoveError("Nur am Raspi")
            synchronized(Stove) { val c = Stove.cfg(); c.put("ctl_auto_back", b.optBoolean("on")); Stove.saveCfg(c) }
            linkInfo()
        }
        "link/pair" -> { Link.pair(b.optString("url"), b.optString("code")); linkInfo() }
        "link/unpair" -> { Link.unpair(); linkInfo() }
        else -> throw StoveError("Unbekannt: $p")
    }
}
