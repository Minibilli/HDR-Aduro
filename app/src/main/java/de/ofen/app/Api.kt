package de.ofen.app

import org.json.JSONObject

/**
 * Gleiche Schnittstelle wie die bisherige Termux-Version (/api/...),
 * nur direkt in der App statt über einen Webserver.
 */
object Api {
    private val VPN_APPS = listOf("com.zaneschepke.wireguardautotunnel", "com.wireguard.android")

    private fun ok(data: Any? = null) = JSONObject().put("ok", true).put("data", data ?: JSONObject.NULL)
    private fun err(msg: String) = JSONObject().put("error", msg)

    fun handle(path: String, bodyText: String?): JSONObject {
        val post = bodyText != null
        val b = try { if (bodyText.isNullOrBlank()) JSONObject() else JSONObject(bodyText) } catch (e: Exception) { return err("Ungültige Anfrage") }
        return try {
            if (post) handlePost(path, b) else handleGet(path)
        } catch (e: Hmip.HmipError) {
            err(e.message ?: "Homematic-Fehler")
        } catch (e: StoveError) {
            err(e.message ?: "Fehler")
        } catch (e: Exception) {
            err(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun handleGet(p: String): JSONObject = when (p) {
        "config" -> {
            val c = Stove.cfg()
            ok(JSONObject()
                .put("ip", c.optString("ip")).put("serial", c.optString("serial"))
                .put("has_pin", c.optString("pin").isNotBlank())
                .put("app_on", Stove.appRunning())
                .put("wood_min", c.optInt("wood_min", 150))
                .put("vpn_app", c.optString("vpn_app", VPN_APPS[0]))
                .put("early_stop", c.optBoolean("early_stop"))
                .put("early_restart", c.optBoolean("early_restart"))
                .put("early_temp", c.optInt("early_temp", 240))
                .put("early_restart_temp", c.optInt("early_restart_temp", 100))
                .put("notify_state", c.optBoolean("notify_state", true))
                .put("auto_power", c.optBoolean("auto_power"))
                .put("ip_changed_at", c.optLong("ip_changed_at")).put("ip_old", c.optString("ip_old"))
                .put("expert", c.optBoolean("expert")).put("expert_ver", Stove.EXPERT_TEXT_VERSION)
                .put("hm", Hmip.connected()))
        }
        "status" -> ok(Stove.readStatus())
        "net" -> ok(Stove.routeInfo())
        "manual" -> ok(Stove.manualState())
        "pelletstop" -> ok(Stove.getPelletStop())
        "hopper" -> {
            val c = Stove.cfg()
            ok(JSONObject().put("kg", Stove.hopperRead(true).let { if (it.isNaN()) JSONObject.NULL else it })
                .put("cap", c.optDouble("hopper_cap", 13.0)).put("warn", c.optDouble("hopper_warn", 3.0))
                .put("notify", c.optBoolean("hopper_notify", true))
                .put("auger", Stove.getKv(Nbe.FN_SETTINGS, "hopper.auger_capacity")["auger_capacity"] ?: ""))
        }
        "advanced" -> ok(JSONObject(Stove.getKv(Nbe.FN_ADVANCED, "*")))
        "hm" -> {
            val c = Stove.cfg()
            val show = c.optJSONArray("hm_show") ?: org.json.JSONArray()
            if (Hmip.connected()) Hmip.refresh()
            ok(JSONObject()
                .put("connected", Hmip.connected()).put("ap", c.optString("hm_ap"))
                .put("pending", c.has("hm_pending"))
                .put("sensors", Hmip.sensors()).put("show", show)
                .put("age", Hmip.ageSeconds()).put("error", Hmip.error)
                .put("ctrl", c.optBoolean("hm_ctrl")).put("ctrl_id", c.optString("hm_ctrl_id"))
                .put("ctrl_target", c.optDouble("hm_ctrl_target", 21.0))
                .put("valve", c.optBoolean("hm_valve")).put("valve_id", c.optString("hm_valve_id"))
                .put("valve_temp", c.optDouble("hm_valve_temp", 5.0)))
        }
        else -> err("Unbekannt: $p")
    }

    /** Sicherheitsrelevante Befehle nur im Expertenmodus */
    private val EXPERT_ONLY = setOf("pelletstop", "manual/switch", "manual/name", "settings/read", "settings/write")

    private fun handlePost(p: String, b: JSONObject): JSONObject {
        if (p in EXPERT_ONLY) Stove.requireExpert()
        if (p == "config" && (b.optBoolean("early_stop") || b.optBoolean("early_restart") || b.optBoolean("hm_ctrl"))) Stove.requireExpert()
        when (p) {
            "expert" -> { Stove.setExpert(b.optBoolean("on"), b.optInt("ack")); return ok(JSONObject().put("expert", Stove.expert())) }
            "config" -> {
                val c = Stove.cfg()
                for (k in listOf("ip", "serial", "pin")) {
                    if (!b.has(k)) continue
                    val v = b.optString(k).trim()
                    if (v.isNotEmpty() || k != "pin") c.put(k, v) // leeres PIN-Feld = alte PIN behalten
                }
                for (k in listOf("early_stop", "early_restart")) if (b.has(k)) c.put(k, b.optBoolean(k))
                if (b.has("early_temp")) b.optString("early_temp").toDoubleOrNull()?.let { c.put("early_temp", it.toInt().coerceIn(240, 270)) }
                if (b.optString("vpn_app") in VPN_APPS) c.put("vpn_app", b.optString("vpn_app"))
                if (b.has("early_restart_temp")) b.optString("early_restart_temp").toDoubleOrNull()?.let { c.put("early_restart_temp", it.toInt().coerceIn(50, 200)) }
                if (b.has("notify_state")) c.put("notify_state", b.optBoolean("notify_state"))
                if (b.has("auto_power")) c.put("auto_power", b.optBoolean("auto_power"))
                if (b.has("hm_ctrl")) {
                    if (c.optBoolean("hm_ctrl") && !b.optBoolean("hm_ctrl")) Stove.restoreBoilerTarget(c)
                    c.put("hm_ctrl", b.optBoolean("hm_ctrl"))
                }
                if (b.has("hm_ctrl_id")) c.put("hm_ctrl_id", b.optString("hm_ctrl_id"))
                if (b.has("hm_ctrl_target")) b.optString("hm_ctrl_target").toDoubleOrNull()?.let { c.put("hm_ctrl_target", it.coerceIn(10.0, 28.0)) }
                if (b.has("hm_valve")) c.put("hm_valve", b.optBoolean("hm_valve"))
                if (b.has("hm_valve_id")) c.put("hm_valve_id", b.optString("hm_valve_id"))
                if (b.has("hm_valve_temp")) b.optString("hm_valve_temp").toDoubleOrNull()?.let { c.put("hm_valve_temp", it.coerceIn(5.0, 16.0)) }
                if (b.has("wood_min")) b.optString("wood_min").toDoubleOrNull()?.let { c.put("wood_min", it.toInt().coerceIn(50, 400)) }
                Stove.saveCfg(c)
                return ok()
            }
            "discover" -> return ok(Stove.discover())
            "setup" -> return ok(Stove.setup(b.optString("serial").trim(), b.optString("pin").trim()))
            "start" -> { Stove.resume(); return ok() }
            "stop" -> { Stove.halt(); return ok(JSONObject().put("halted", true)) }
            "power" -> {
                val lvl = mapOf("1" to "10", "2" to "50", "3" to "100")[b.optString("level")]
                    ?: return err("Heizstufe muss 1, 2 oder 3 sein")
                Stove.set("regulation.fixed_power", lvl)
                // Stufe von Hand gewählt -> Automatik aus, sonst würde sie gleich wieder umgestellt
                val c = Stove.cfg(); val wasAuto = c.optBoolean("auto_power")
                if (wasAuto) { c.put("auto_power", false); Stove.saveCfg(c) }
                return ok(JSONObject().put("auto_off", wasAuto))
            }
            "target" -> {
                val t = b.optString("temp").toDoubleOrNull()?.toInt()
                if (t == null || t !in 5..35) return err("Zieltemperatur 5–35 °C")
                Stove.set("boiler.temp", t.toString()); return ok()
            }
            "hopper/fill" -> {
                val kg = b.optString("kg").toDoubleOrNull() ?: return err("Ungültige Menge")
                return ok(Stove.hopperSet(kg, b.optBoolean("add")))
            }
            "hopper/config" -> {
                val c = Stove.cfg()
                b.optString("cap").toDoubleOrNull()?.let { c.put("hopper_cap", it.coerceIn(5.0, 200.0)) }
                b.optString("warn").toDoubleOrNull()?.let { c.put("hopper_warn", it.coerceIn(0.0, 50.0)) }
                if (b.has("notify")) c.put("hopper_notify", b.optBoolean("notify"))
                Stove.saveCfg(c); return ok()
            }
            "pelletstop" -> {
                val v = b.optString("value").toDoubleOrNull() ?: return err("Ungültiger Wert")
                return ok(Stove.setPelletStop(v))
            }
            "manual/switch" -> {
                val out = b.optString("output").toIntOrNull() ?: return err("Ungültige Angabe")
                val m = b.opt("minutes")
                val mins = if (m == null || m == JSONObject.NULL || m.toString() == "" || m.toString() == "stop") null
                    else m.toString().toDoubleOrNull() ?: return err("Ungültige Angabe")
                Stove.manualSwitch(out - 1, b.optBoolean("on"), mins)
                return ok()
            }
            "manual/off" -> { Stove.manualAllOff(); return ok() }
            "manual/name" -> {
                val o = b.optString("output").toIntOrNull() ?: 0
                if (o !in 1..Stove.N_OUTPUTS) return err("Unbekannter Ausgang")
                val c = Stove.cfg()
                val names = c.optJSONObject("output_names") ?: JSONObject()
                val nm = b.optString("name").trim().take(30)
                if (nm.isNotEmpty()) names.put(o.toString(), nm) else names.remove(o.toString())
                c.put("output_names", names)
                if (b.optBoolean("fresh_air")) c.put("fresh_air_bit", o - 1)
                else if (b.has("fresh_air") && c.has("fresh_air_bit") && c.optInt("fresh_air_bit") == o - 1) c.remove("fresh_air_bit")
                Stove.saveCfg(c)
                return ok()
            }
            "hm/connect" -> return ok(Hmip.connect(b.optString("ap"), b.optString("pin")))
            "hm/confirm" -> return ok(Hmip.confirm())
            "hm/disconnect" -> {
                Stove.valveRelease()
                Stove.cfg().let { if (it.optBoolean("hm_ctrl")) { Stove.restoreBoilerTarget(it); Stove.saveCfg(it) } }
                Hmip.disconnect(); return ok()
            }
            "hm/refresh" -> { Hmip.refresh(force = true); if (Hmip.error.isNotBlank()) return err(Hmip.error); return ok() }
            "hm/show" -> {
                val c = Stove.cfg()
                val id = b.optString("id")
                val cur = c.optJSONArray("hm_show") ?: org.json.JSONArray()
                val list = (0 until cur.length()).map { cur.optString(it) }.filter { it != id }.toMutableList()
                if (b.optBoolean("show")) list.add(id)
                c.put("hm_show", org.json.JSONArray(list)); Stove.saveCfg(c)
                return ok()
            }
            "settings/read" -> {
                val g = b.optString("group").trim()
                if (g.isEmpty() || g.contains(".")) return err("Ungültige Gruppe")
                val kv = Stove.getKv(Nbe.FN_SETTINGS, "$g.*")
                return ok(JSONObject(kv))
            }
            "settings/write" -> {
                val path = b.optString("path").trim(); val v = b.optString("value").trim()
                if (path.count { it == '.' } != 1 || path.contains("*")) return err("Pfad muss 'gruppe.name' sein")
                Stove.set(path, v); return ok()
            }
        }
        return err("Unbekannt: $p")
    }
}
