package de.ofen.app

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * „Zündung aufzeichnen“: fragt den Ofen alle 2 s ab und schreibt jede Änderung der Betriebsdaten mit Zeit auf.
 * Damit lässt sich der tatsächliche Ablauf einer Zündung nachvollziehen (Zustand, Unterzustand, Lichtsensor,
 * Stromaufnahme, Rauchgas …). Nur lesen – schaltet nichts.
 */
object Recorder {
    private const val POLL_MS = 2_000L
    private const val ADV_EVERY = 3                 // erweiterte Daten jede 3. Abfrage
    private const val MAX_MS = 60 * 60_000L          // spätestens nach 60 min Schluss
    private const val AFTER_RUN_MS = 10 * 60_000L    // 10 min nach Erreichen von Normalbetrieb Schluss
    private const val MAX_LINES = 3000
    /** Einstellungsgruppen, die zum Verständnis der Zündung beitragen */
    private val GROUPS = listOf("ignition", "regulation", "fan", "auger", "oxygen", "misc", "manual")

    @Volatile var active = false; private set
    @Volatile var started = 0L; private set
    @Volatile private var runSince = 0L
    @Volatile private var stopReason = ""
    private val lines = ArrayList<String>()
    private var header = ""
    private var thread: Thread? = null

    private fun ts(t: Long) = String.format(Locale.ROOT, "%02d:%02d", (t - started) / 60_000, ((t - started) / 1000) % 60)
    private fun add(l: String) = synchronized(lines) { if (lines.size < MAX_LINES) lines.add(l) }

    fun start(): JSONObject {
        if (active) return info()
        synchronized(lines) { lines.clear() }
        started = System.currentTimeMillis(); runSince = 0; stopReason = ""
        val h = StringBuilder()
        h.append("HDR Aduro – Zündprotokoll\n")
        h.append("Beginn: ").append(SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date(started))).append('\n')
        h.append("Gerät: ").append(Stove.platform.deviceName).append(" (").append(Stove.platform.kind).append(")\n")
        // Einstellungen des Ofens einmal festhalten
        for (g in GROUPS) {
            val kv = try { Stove.getKv(Nbe.FN_SETTINGS, "$g.*") } catch (e: Exception) { emptyMap() }
            if (kv.isNotEmpty()) h.append("\n[").append(g).append("]\n").append(kv.toSortedMap().entries.joinToString("\n") { "  ${it.key} = ${it.value}" }).append('\n')
        }
        header = h.toString()
        active = true
        thread = Thread({ loop() }, "zuendprotokoll").apply { isDaemon = true; start() }
        return info()
    }

    fun stop(reason: String = "von Hand beendet"): JSONObject {
        if (active) { stopReason = reason; active = false; add("${ts(System.currentTimeMillis())}  ■ Ende: $reason") }
        return info()
    }

    private fun label(k: String, v: String): String = when (k) {
        "state" -> "$v (${Stove.stateName(v)})"
        "substate" -> "$v (${Stove.substateName(v)})"
        else -> v
    }

    private fun loop() {
        var prev: Map<String, String> = emptyMap()
        var prevAdv: Map<String, String> = emptyMap()
        var n = 0
        var errs = 0
        while (active) {
            val now = System.currentTimeMillis()
            try {
                val op = Stove.getKv(Nbe.FN_OPERATING, "*")
                val adv = if (n % ADV_EVERY == 0) try { Stove.getKv(Nbe.FN_ADVANCED, "*") } catch (e: Exception) { prevAdv } else prevAdv
                if (n == 0) {
                    add("${ts(now)}  ● Start – Betriebsdaten:")
                    op.toSortedMap().forEach { (k, v) -> add("         $k = ${label(k, v)}") }
                    if (adv.isNotEmpty()) { add("         – erweiterte Daten:"); adv.toSortedMap().forEach { (k, v) -> add("         $k = $v") } }
                } else {
                    val ch = (op.keys + prev.keys).filter { op[it] != prev[it] }.sorted()
                        .map { "$it ${prev[it] ?: "–"}→${op[it]?.let { v -> label(it, v) } ?: "–"}" }
                    val chA = if (adv === prevAdv) emptyList() else (adv.keys + prevAdv.keys).filter { adv[it] != prevAdv[it] }.sorted()
                        .map { "$it ${prevAdv[it] ?: "–"}→${adv[it] ?: "–"}" }
                    // Zustandswechsel hervorheben, sonst eine Zeile mit allen Änderungen
                    if ("state" in op.keys && op["state"] != prev["state"]) add("${ts(now)}  ▶ Zustand: ${label("state", op["state"] ?: "")}")
                    if (ch.isNotEmpty() || chA.isNotEmpty()) add("${ts(now)}  " + (ch + chA.map { "[erw] $it" }).joinToString(" · "))
                }
                errs = 0
                prev = op; prevAdv = adv
                if (op["state"] == "5" && runSince == 0L) { runSince = now; add("${ts(now)}  ✓ Normalbetrieb erreicht – Aufzeichnung läuft noch 10 min") }
                if (runSince > 0 && now - runSince > AFTER_RUN_MS) { stop("10 min nach Normalbetrieb"); break }
            } catch (e: Exception) {
                if (++errs == 1) add("${ts(now)}  ! Ofen antwortet nicht: ${e.message}")
            }
            if (now - started > MAX_MS) { stop("60 min erreicht"); break }
            n++
            Thread.sleep(POLL_MS)
        }
    }

    fun text(): String = header + "\nVerlauf (mm:ss seit Beginn, nur Änderungen):\n" + synchronized(lines) { lines.joinToString("\n") } + "\n"

    fun info(): JSONObject {
        val tail = synchronized(lines) { lines.takeLast(40).joinToString("\n") }
        return JSONObject().put("active", active).put("started", started).put("lines", synchronized(lines) { lines.size })
            .put("tail", tail).put("reason", stopReason)
    }
}
