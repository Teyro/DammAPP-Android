package de.oejendorferdamm.dammapp

import de.oejendorferdamm.dammapp.kern.Unterricht
import de.oejendorferdamm.dammapp.kern.UntisSicherung
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UntisSicherungTest {
    @Test
    fun handSchlaegtUntis() {
        val vorher = listOf(Unterricht(1, "1", "2b", "Ma"), Unterricht(2, "1", "2b", "Ma"))
        val hand = listOf(vorher[0], Unterricht(2, "1", "4a", "Eigen"))
        val neu = listOf(Unterricht(1, "1", "2b", "De"), Unterricht(2, "1", "2b", "De"), Unterricht(3, "2", "3a", "Mu"))
        val z = UntisSicherung.zusammenfuehren(hand, neu, vorher)
        assertEquals("De", z.first { it.tag == 1 }.fach)
        assertEquals("Eigen", z.first { it.tag == 2 }.fach)
        assertEquals("Mu", z.first { it.tag == 3 }.fach)
        assertEquals(3, z.size)
    }

    @Test
    fun sicherungLesen() {
        val json = """{"erstellt":"2026-11-23T05:30:00.000Z","faecher":[{"name":"Ma","lang":"Mathe"}],
          "plaene":{"klassen":{"2b":[{"datum":"2026-11-23","von":"08:30","bis":"09:15","faecher":["Ma"],"lehrer":["Sta"],"raeume":["R1"],"klassen":["2b"]}]},
          "lehrer":{"Sta":[{"datum":"2026-11-23","von":"08:30","bis":"09:15","faecher":["Ma"],"lehrer":["Ver"],"lehrerVorher":["Sta"],"raeume":["R1"],"klassen":["2b"]}]}}}"""
        val st = UntisSicherung.lesen(json, "sta")!!
        assertEquals(510, st.meine[0].von)
        assertEquals("Mathe", st.meine[0].fachLang)
        assertEquals(listOf("Sta"), st.meine[0].lehrer)
        assertEquals(1, st.klassen["2b"]!!.size)
        assertNull(UntisSicherung.lesen(json, "xyz"))
    }
}

class UntisSucheTest {
    private val json = """{"faecher":[{"name":"Ma","lang":"Mathe"}],"klassen":[{"name":"2b","lang":""}],"lehrer":[{"name":"Sta","lang":"Anna Stark"}],
      "plaene":{"klassen":{"2b":[{"datum":"2026-11-23","von":"08:30","bis":"09:15","faecher":["Ma"],"lehrer":["Sta"],"raeume":["R1"],"klassen":["2b"]}]},"lehrer":{}}}"""

    @Test
    fun elementeUndPlanAusSicherung() {
        val e = de.oejendorferdamm.dammapp.kern.UntisSuche.elemente(json)
        assertEquals(listOf("2b", "Sta"), e.map { it.name })
        val p = de.oejendorferdamm.dammapp.kern.UntisSuche.plan(json, e[0])
        assertEquals(1, p.size)
        assertEquals("Mathe", p[0].fachLang)
    }

    @Test
    fun personenFarbe() {
        val f = mapOf("Anna Stark" to "#ff0000", "Ben Kurz" to "#00ff00")
        assertEquals("#ff0000", de.oejendorferdamm.dammapp.kern.Aufgaben.farbeFuer("anna stark", f))
        assertEquals("#00ff00", de.oejendorferdamm.dammapp.kern.Aufgaben.farbeFuer("Herr Kurz", f))
        assertNull(de.oejendorferdamm.dammapp.kern.Aufgaben.farbeFuer("Niemand", f))
    }
}

class UntisRasterTest {
    @Test
    fun zeilenVonErsterBisLetzterStunde() {
        val st = listOf(de.oejendorferdamm.dammapp.kern.UntisStunde("2026-11-23", 480, 510, "FF", klassen = emptyList(), lehrer = emptyList()),
            de.oejendorferdamm.dammapp.kern.UntisStunde("2026-11-27", 555, 600, "Ma", klassen = emptyList(), lehrer = emptyList()))
        val z = de.oejendorferdamm.dammapp.kern.UntisRaster.zeilen(st, de.oejendorferdamm.dammapp.kern.Schulzeit.standard)
        assertEquals("0", z.first().name)
        assertEquals("6", z.last().name)   // alle Stunden bis zum Schluss
        assertEquals(0, de.oejendorferdamm.dammapp.kern.UntisRaster.in_(st, "2026-11-27", z[1]).size)   // Freitag 1. Stunde frei
        assertEquals("1", de.oejendorferdamm.dammapp.kern.UntisRaster.zeilen(listOf(st[1]), de.oejendorferdamm.dammapp.kern.Schulzeit.standard).first().name)
    }
}

class BemerkungenTest {
    private val absaetze = listOf("Jg 3 Verkehrsunterricht", "4,5 Jährigen Wqx (8:30-13:00); Hox (8:30-11:55)", "FSJ Sofie VSK krank Xyz 4. Std Termin",
        "FSJ Mira krank Begleitung Lio 1.-4. Abc, 5. Def", "FSJ Ella (Paul) 2c krank Begleitung Kim 1.-3. Xyz, 4. Def")

    @Test
    fun begleitungUndTermin() {
        val b = de.oejendorferdamm.dammapp.kern.Bemerkungen.lesen(absaetze, "Xyz")
        assertEquals(2, b.size)
        assertEquals(listOf(4), b[0].stunden); assertEquals("Termin", b[0].was)
        assertEquals(listOf(1, 2, 3), b[1].stunden); assertEquals("Begleitung Kim", b[1].was); assertEquals("2c", b[1].klasse)
        assertEquals(listOf(listOf(5), listOf(4)), de.oejendorferdamm.dammapp.kern.Bemerkungen.lesen(absaetze, "Def").map { it.stunden })
        assertEquals("8:30–13:00", de.oejendorferdamm.dammapp.kern.Bemerkungen.lesen(absaetze, "Wqx").first().zeit)
        assertEquals(0, de.oejendorferdamm.dammapp.kern.Bemerkungen.lesen(absaetze, "Nix").size)
    }
}

class AufsichtenTest {
    @Test
    fun alleAufsichtenAusDenPausenzeilen() {
        fun tc(t: String) = "<w:tc><w:tcPr><w:tcW w:w=\"1000\"/></w:tcPr><w:p><w:r><w:t>$t</w:t></w:r></w:p></w:tc>"
        fun tr(vararg z: String) = "<w:tr>" + z.joinToString("") + "</w:tr>"
        val xml = "<w:document><w:body><w:p><w:r><w:t>Freitag, 27.11.2026</w:t></w:r></w:p><w:tbl>" +
            tr(tc("Klasse"), tc("4a"), tc("4b")) + tr(tc("1"), tc("Xyz"), tc("")) + tr(tc(""), tc("Wichtig!"), tc("1. Pause"), tc("2. Pause")) +
            tr(tc(""), tc("Mensa + Haltestelle"), tc("Abc"), tc("Xyz+4")) + tr(tc(""), tc("Fußballplatz"), tc("(Def)"), tc("Abc")) + "</w:tbl></w:body></w:document>"
        val p = de.oejendorferdamm.dammapp.kern.Vertretung.auswerten(xml, "Xyz")
        assertEquals(listOf("Mensa + Haltestelle", "Fußballplatz"), p.alleAufsichten["abc"]!!.map { it.ort })
        assertEquals(true, p.alleAufsichten["def"]!!.first().reserve)
        assertEquals("Mensa + Haltestelle", p.pausen.first().ort)
    }
}
