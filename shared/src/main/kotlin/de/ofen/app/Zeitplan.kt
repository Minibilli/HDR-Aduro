package de.ofen.app

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Zeitplan (Wochenprogramm): reine Zeitlogik ohne Android, Netzwerk oder org.json – damit sie sich mit
 * Unit-Tests prüfen lässt (app/src/test/.../ZeitplanTest.kt).
 *
 * Je Wochentag (0 = Montag … 6 = Sonntag) bis zu MAX_PRO_TAG Zeitfenster. In einem Zeitfenster soll der Ofen laufen
 * (mit Zieltemperatur und optional fester Heizstufe), außerhalb ist er aus. Ein Fenster reicht höchstens bis 24:00;
 * über Mitternacht = zwei Fenster (bis 24:00 und ab 00:00).
 */
object Zeitplan {
    const val MAX_PRO_TAG = 4

    /** So lange nach Ende eines Zeitfensters wird das Ausschalten noch nachgeholt (Ofen kurz nicht erreichbar) */
    val CATCH_UP: Duration = Duration.ofMinutes(60)

    /** von/bis in Minuten ab Mitternacht (bis 1440 = 24:00); stufe 0 = wie eingestellt, 1–3 = Niedrig/Mittel/Hoch */
    data class Fenster(val von: Int, val bis: Int, val temp: Double, val stufe: Int = 0)

    data class Plan(val on: Boolean = false, val tage: List<List<Fenster>> = List(7) { emptyList() })

    data class Aktiv(val start: LocalDateTime, val ende: LocalDateTime, val f: Fenster)

    private val HM = Regex("^([01]\\d|2[0-4]):([0-5]\\d)$")

    /** „06:30“ -> 390; „24:00“ nur als Ende erlaubt */
    fun minuten(s: String, ende: Boolean): Int? {
        val m = HM.matchEntire(s.trim()) ?: return null
        val v = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        if (v > 1440 || (v == 1440 && !ende)) return null
        return v
    }

    fun text(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    /** Fehlertext oder null, wenn der Tag in Ordnung ist (Fenster sortiert, ohne Überschneidung, Ende nach Beginn) */
    fun pruefen(fs: List<Fenster>): String? {
        if (fs.size > MAX_PRO_TAG) return "Höchstens $MAX_PRO_TAG Zeitfenster pro Tag"
        val s = fs.sortedBy { it.von }
        for (f in s) {
            if (f.von !in 0..1439 || f.bis !in 1..1440 || f.bis <= f.von) return "Ende muss nach dem Beginn liegen (${text(f.von)}–${text(f.bis)})"
            if (f.temp < 5.0 || f.temp > 30.0) return "Zieltemperatur 5–30 °C"
            if (f.stufe !in 0..3) return "Ungültige Heizstufe"
        }
        for (i in 1 until s.size) if (s[i].von < s[i - 1].bis) return "Zeitfenster überschneiden sich (${text(s[i].von)})"
        return null
    }

    private fun fenster(p: Plan, day: LocalDate): List<Aktiv> =
        (p.tage.getOrNull(day.dayOfWeek.value - 1) ?: emptyList()).sortedBy { it.von }.map {
            Aktiv(day.atStartOfDay().plusMinutes(it.von.toLong()), day.atStartOfDay().plusMinutes(it.bis.toLong()), it)
        }

    /** Zeitfenster, das gerade läuft (Beginn eingeschlossen, Ende ausgeschlossen), oder null */
    fun aktiv(p: Plan, now: LocalDateTime): Aktiv? {
        if (!p.on) return null
        return fenster(p, now.toLocalDate()).firstOrNull { !now.isBefore(it.start) && now.isBefore(it.ende) }
    }

    /** Ende eines Zeitfensters, das höchstens CATCH_UP zurückliegt (auch gestern bis 24:00), oder null */
    fun letztesEnde(p: Plan, now: LocalDateTime): LocalDateTime? {
        if (!p.on) return null
        return listOf(-1L, 0L).flatMap { fenster(p, now.toLocalDate().plusDays(it)) }
            .map { it.ende }
            .filter { !it.isAfter(now) && Duration.between(it, now) <= CATCH_UP }
            .maxOrNull()
    }

    /** Nächster Wechsel nach `now`: Zeitpunkt und ob der Ofen dann an (true) oder aus (false) sein soll.
     *  Aneinandergrenzende Fenster (z. B. 24:00 / 00:00) zählen nicht als Wechsel nach „aus“. */
    fun naechsterWechsel(p: Plan, now: LocalDateTime): Pair<LocalDateTime, Boolean>? {
        if (!p.on) return null
        val alle = (0L..8L).flatMap { fenster(p, now.toLocalDate().plusDays(it)) }
        val laeuft = aktiv(p, now) != null
        if (laeuft) {
            // Ende der zusammenhängenden Laufzeit suchen
            var ende = alle.first { !now.isBefore(it.start) && now.isBefore(it.ende) }.ende
            while (true) { ende = alle.firstOrNull { it.start == ende }?.ende ?: break }
            return ende to false
        }
        return alle.firstOrNull { it.start.isAfter(now) }?.let { it.start to true }
    }

    private val WD = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

    /** „heute 22:00“, „morgen 06:00“, „Fr 06:00“; 24:00 als „24:00“ des Vortags */
    fun label(t: LocalDateTime, now: LocalDateTime): String {
        val mitternacht = t.hour == 0 && t.minute == 0
        val tag = if (mitternacht) t.toLocalDate().minusDays(1) else t.toLocalDate()
        val hm = if (mitternacht) "24:00" else "%02d:%02d".format(t.hour, t.minute)
        return when (Duration.between(now.toLocalDate().atStartOfDay(), tag.atStartOfDay()).toDays()) {
            0L -> "heute $hm"
            1L -> "morgen $hm"
            else -> WD[tag.dayOfWeek.value - 1] + " " + hm
        }
    }
}
