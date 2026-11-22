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
