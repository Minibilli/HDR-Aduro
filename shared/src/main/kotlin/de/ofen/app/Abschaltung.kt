package de.ofen.app

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Abschaltzeit: reine Zeitlogik ohne Android, Netzwerk oder org.json – damit sie sich mit
 * Unit-Tests prüfen lässt (app/src/test/.../AbschaltungTest.kt).
 *
 * Modi: "off" (aus), "once" (einmal am Tag `date` um `time`), "daily" (täglich um `time`),
 *       "week" (je Wochentag eigene Zeit, `days[0]` = Montag … `days[6]` = Sonntag, "" = keine Abschaltung).
 */
object Abschaltung {
    val MODES = setOf("off", "once", "daily", "week")

    /** So lange nach der Abschaltzeit wird sie noch nachgeholt (Ofen kurz nicht erreichbar, Handy kurz aus).
     *  Später nicht mehr – sonst würde z. B. morgens um 6 Uhr ein Ofen ausgehen, der um 22 Uhr hätte aus sein sollen. */
    val CATCH_UP: Duration = Duration.ofMinutes(60)

    /** Vorwarnung so lange vor der Abschaltzeit */
    val WARN: Duration = Duration.ofMinutes(15)

    data class Plan(
        val mode: String = "off",
        val time: String = "",
        val date: String = "",
        val days: List<String> = List(7) { "" },
        val warn: Boolean = true,
    )

    private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    fun parseTime(s: String): LocalTime? = s.trim().takeIf { TIME.matches(it) }?.let { LocalTime.parse(it) }

    /** Abschaltzeitpunkt an diesem Tag, oder null */
    fun at(p: Plan, day: LocalDate): LocalDateTime? {
        val t = when (p.mode) {
            "once" -> if (runCatching { LocalDate.parse(p.date) }.getOrNull() == day) p.time else null
            "daily" -> p.time
            "week" -> p.days.getOrNull(day.dayOfWeek.value - 1)
            else -> null
        } ?: return null
        return parseTime(t)?.let { day.atTime(it) }
    }

    /** Nächster Abschaltzeitpunkt nach `now` (höchstens 8 Tage voraus), oder null */
    fun next(p: Plan, now: LocalDateTime): LocalDateTime? =
        (0L..8L).asSequence()
            .mapNotNull { at(p, now.toLocalDate().plusDays(it)) }
            .firstOrNull { it.isAfter(now) }

    /** Fälliger Abschaltzeitpunkt: liegt höchstens CATCH_UP zurück (auch über Mitternacht), sonst null */
    fun due(p: Plan, now: LocalDateTime): LocalDateTime? =
        listOf(-1L, 0L)
            .mapNotNull { at(p, now.toLocalDate().plusDays(it)) }
            .filter { !it.isAfter(now) && Duration.between(it, now) <= CATCH_UP }
            .maxOrNull()

    /** Steht die nächste Abschaltung innerhalb der Vorwarnzeit an? */
    fun warnFor(p: Plan, now: LocalDateTime): LocalDateTime? =
        if (!p.warn) null else next(p, now)?.takeIf { Duration.between(now, it) <= WARN }

    /** Datum für „einmalig“: heute, wenn die Uhrzeit noch kommt, sonst morgen */
    fun onceDate(time: String, now: LocalDateTime): LocalDate? {
        val t = parseTime(time) ?: return null
        val today = now.toLocalDate()
        return if (today.atTime(t).isAfter(now)) today else today.plusDays(1)
    }

    private val WD = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

    /** „heute 22:00“, „morgen 22:00“ oder „Fr 22:00“ */
    fun label(t: LocalDateTime, now: LocalDateTime): String {
        val hm = "%02d:%02d".format(t.hour, t.minute)
        val d = Duration.between(now.toLocalDate().atStartOfDay(), t.toLocalDate().atStartOfDay()).toDays()
        return when (d) {
            0L -> "heute $hm"
            1L -> "morgen $hm"
            else -> WD[t.dayOfWeek.value - 1] + " " + hm
        }
    }
}
