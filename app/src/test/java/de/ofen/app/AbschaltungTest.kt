package de.ofen.app

import de.ofen.app.Abschaltung.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class AbschaltungTest {
    // 28.11.2026 ist ein Samstag
    private fun t(s: String) = LocalDateTime.parse(s)

    @Test fun taeglichNochNichtFaellig() {
        val p = Plan("daily", time = "22:00")
        assertNull(Abschaltung.due(p, t("2026-11-28T21:59")))
        assertEquals(t("2026-11-28T22:00"), Abschaltung.next(p, t("2026-11-28T21:59")))
    }

    @Test fun taeglichFaelligUndNachholfenster() {
        val p = Plan("daily", time = "22:00")
        assertEquals(t("2026-11-28T22:00"), Abschaltung.due(p, t("2026-11-28T22:00")))
        assertEquals(t("2026-11-28T22:00"), Abschaltung.due(p, t("2026-11-28T23:00")))
        assertNull(Abschaltung.due(p, t("2026-11-28T23:01")))
    }

    @Test fun nachholenUeberMitternacht() {
        val p = Plan("daily", time = "23:30")
        assertEquals(t("2026-11-28T23:30"), Abschaltung.due(p, t("2026-11-29T00:15")))
        assertNull(Abschaltung.due(p, t("2026-11-29T00:31")))
    }

    @Test fun naechsteNachAbschaltungIstMorgen() {
        val p = Plan("daily", time = "22:00")
        assertEquals(t("2026-11-29T22:00"), Abschaltung.next(p, t("2026-11-28T22:00")))
    }

    @Test fun wochentage() {
        // nur Montag 21:00 und Freitag 23:00
        val p = Plan("week", days = listOf("21:00", "", "", "", "23:00", "", ""))
        assertNull(Abschaltung.at(p, LocalDate.parse("2026-11-28"))) // Samstag
        assertEquals(t("2026-11-30T21:00"), Abschaltung.next(p, t("2026-11-28T12:00"))) // Montag
        assertEquals(t("2026-12-04T23:00"), Abschaltung.next(p, t("2026-11-30T21:00"))) // Freitag
    }

    @Test fun einmaligNurAnDemTag() {
        val p = Plan("once", time = "22:00", date = "2026-11-28")
        assertEquals(t("2026-11-28T22:00"), Abschaltung.next(p, t("2026-11-28T10:00")))
        assertNull(Abschaltung.next(p, t("2026-11-28T22:00")))
        assertNull(Abschaltung.at(p, LocalDate.parse("2026-11-29")))
    }

    @Test fun einmaligDatum() {
        assertEquals(LocalDate.parse("2026-11-28"), Abschaltung.onceDate("22:00", t("2026-11-28T21:00")))
        assertEquals(LocalDate.parse("2026-11-29"), Abschaltung.onceDate("22:00", t("2026-11-28T22:00")))
        assertNull(Abschaltung.onceDate("25:00", t("2026-11-28T21:00")))
    }

    @Test fun ausOderUngueltigSchaltetNie() {
        assertNull(Abschaltung.next(Plan("off", time = "22:00"), t("2026-11-28T10:00")))
        assertNull(Abschaltung.next(Plan("daily", time = "2200"), t("2026-11-28T10:00")))
        assertNull(Abschaltung.due(Plan("daily", time = ""), t("2026-11-28T10:00")))
    }

    @Test fun vorwarnung() {
        val p = Plan("daily", time = "22:00")
        assertNull(Abschaltung.warnFor(p, t("2026-11-28T21:44")))
        assertEquals(t("2026-11-28T22:00"), Abschaltung.warnFor(p, t("2026-11-28T21:45")))
        assertNull(Abschaltung.warnFor(p.copy(warn = false), t("2026-11-28T21:50")))
    }

    @Test fun beschriftung() {
        val now = t("2026-11-28T12:00")
        assertEquals("heute 22:00", Abschaltung.label(t("2026-11-28T22:00"), now))
        assertEquals("morgen 07:05", Abschaltung.label(t("2026-11-29T07:05"), now))
        assertEquals("Mo 21:00", Abschaltung.label(t("2026-11-30T21:00"), now))
    }
}
