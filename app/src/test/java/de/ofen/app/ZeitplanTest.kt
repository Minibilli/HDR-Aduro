package de.ofen.app

import de.ofen.app.Zeitplan.Fenster
import de.ofen.app.Zeitplan.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class ZeitplanTest {
    // 28.11.2026 ist ein Samstag (Index 5), 30.11.2026 ein Montag (Index 0)
    private fun t(s: String) = LocalDateTime.parse(s)
    private fun tage(vararg p: Pair<Int, List<Fenster>>) = List(7) { i -> p.firstOrNull { it.first == i }?.second ?: emptyList() }

    private val sa = Fenster(6 * 60, 22 * 60, 21.0)
    private val plan = Plan(true, tage(5 to listOf(sa), 0 to listOf(Fenster(6 * 60, 8 * 60, 20.0, 3))))

    @Test fun aktivImFenster() {
        assertEquals(sa, Zeitplan.aktiv(plan, t("2026-11-28T06:00"))?.f)
        assertEquals(sa, Zeitplan.aktiv(plan, t("2026-11-28T21:59"))?.f)
        assertNull(Zeitplan.aktiv(plan, t("2026-11-28T22:00")))
        assertNull(Zeitplan.aktiv(plan, t("2026-11-28T05:59")))
    }

    @Test fun ausgeschaltetNieAktiv() {
        assertNull(Zeitplan.aktiv(plan.copy(on = false), t("2026-11-28T12:00")))
        assertNull(Zeitplan.letztesEnde(plan.copy(on = false), t("2026-11-28T22:10")))
    }

    @Test fun endeMitNachholfenster() {
        assertEquals(t("2026-11-28T22:00"), Zeitplan.letztesEnde(plan, t("2026-11-28T22:30")))
        assertEquals(t("2026-11-28T22:00"), Zeitplan.letztesEnde(plan, t("2026-11-28T23:00")))
        assertNull(Zeitplan.letztesEnde(plan, t("2026-11-28T23:01")))
    }

    @Test fun naechsterWechsel() {
        assertEquals(t("2026-11-28T22:00") to false, Zeitplan.naechsterWechsel(plan, t("2026-11-28T12:00")))
        assertEquals(t("2026-11-30T06:00") to true, Zeitplan.naechsterWechsel(plan, t("2026-11-28T22:00")))
    }

    @Test fun ueberMitternachtZusammenhaengend() {
        val p = Plan(true, tage(5 to listOf(Fenster(20 * 60, 1440, 21.0)), 6 to listOf(Fenster(0, 2 * 60, 21.0))))
        assertNotNull(Zeitplan.aktiv(p, t("2026-11-28T23:59")))
        assertNotNull(Zeitplan.aktiv(p, t("2026-11-29T00:00")))
        assertEquals(t("2026-11-29T02:00") to false, Zeitplan.naechsterWechsel(p, t("2026-11-28T21:00")))
        assertEquals("heute 24:00", Zeitplan.label(t("2026-11-29T00:00"), t("2026-11-28T21:00")))
    }

    @Test fun pruefen() {
        assertNull(Zeitplan.pruefen(listOf(sa)))
        assertNotNull(Zeitplan.pruefen(listOf(Fenster(600, 500, 21.0))))
        assertNotNull(Zeitplan.pruefen(listOf(Fenster(360, 600, 21.0), Fenster(500, 700, 21.0))))
        assertNotNull(Zeitplan.pruefen(listOf(Fenster(360, 600, 40.0))))
        assertEquals(1440, Zeitplan.minuten("24:00", true))
        assertNull(Zeitplan.minuten("24:00", false))
        assertNull(Zeitplan.minuten("25:00", true))
    }
}
