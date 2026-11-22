package de.oejendorferdamm.dammapp.kern

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Etwas mit eigenem Stundenplan in WebUntis: Klasse (1), Lehrkraft (2) oder Raum (4) */
@kotlinx.serialization.Serializable
data class UntisElement(val typ: Int, val name: String, val lang: String = "", val untisId: Int = 0) {
    val id get() = "$typ|${name.lowercase()}"
    val art get() = when (typ) { 1 -> "Klasse"; 2 -> "Lehrkraft"; else -> "Raum" }
}

/** Stundenpläne anderer: eine Anmeldung, dann beliebig viele Pläne; ohne WebUntis aus der Untis-Sicherung */
class UntisSuche private constructor(private val s: Untis.Sitzung, val elemente: List<UntisElement>) {
    companion object {
        fun oeffnen(link: String, schule: String, benutzer: String, passwort: String, schluessel: String): UntisSuche {
            val q = Untis.qr(link)
            val z = q?.let { it.basis to it.schule } ?: Untis.server(link, schule) ?: throw Untis.Fehler("WebUntis ist nicht eingerichtet.")
            val s = Untis.Sitzung(z.first, z.second)
            val nutzer = q?.benutzer?.ifEmpty { null } ?: benutzer.trim()
            val geheim = q?.schluessel?.ifEmpty { null } ?: schluessel
            if (geheim.isNotEmpty()) s.anmeldenMitSchluessel(nutzer, geheim)
            else s.rufe("authenticate", JSONObject().put("user", nutzer).put("password", passwort).put("client", "DammAPP"))
            fun liste(m: String, typ: Int): List<UntisElement> = (runCatching { s.rufe(m) as? JSONArray }.getOrNull() ?: JSONArray()).let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
                    val n = o.optString("name").ifEmpty { return@mapNotNull null }
                    val lang = if (typ == 2) listOf(o.optString("foreName"), o.optString("longName")).filter { it.isNotEmpty() }.joinToString(" ") else o.optString("longName")
                    UntisElement(typ, n, lang, o.optInt("id"))
                }
            }
            return UntisSuche(s, liste("getKlassen", 1) + liste("getTeachers", 2) + liste("getRooms", 4))
        }

        /** Montag der Woche (am Wochenende: der nächsten) */
        fun montag(d: LocalDate = Zeit.heute()): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { if (Zeit.wochenende(d)) it.plusDays(7) else it }

        /** Klassen und Lehrkräfte aus der Untis-Sicherung */
        fun elemente(sicherung: String): List<UntisElement> = runCatching {
            val o = JSONObject(sicherung)
            fun l(k: String, t: Int) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { UntisElement(t, it.optString("name"), it.optString("lang")) } }.orEmpty()
            l("klassen", 1) + l("lehrer", 2)
        }.getOrDefault(emptyList())

        /** Plan eines Elements aus der Untis-Sicherung */
        fun plan(sicherung: String, e: UntisElement): List<UntisStunde> = runCatching {
            val o = JSONObject(sicherung)
            val bereich = o.getJSONObject("plaene").getJSONObject(if (e.typ == 1) "klassen" else "lehrer")
            val k = bereich.keys().asSequence().firstOrNull { it.equals(e.name, true) } ?: return emptyList()
            val huelle = JSONObject().put("faecher", o.optJSONArray("faecher") ?: JSONArray())
                .put("plaene", JSONObject().put("klassen", JSONObject()).put("lehrer", JSONObject().put("x", bereich.getJSONArray(k))))
            UntisSicherung.lesen(huelle.toString(), "x")?.meine.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** Plan für Mo–Fr ab [montag] */
    fun plan(e: UntisElement, montag: LocalDate): List<UntisStunde> {
        val id = e.untisId.takeIf { it != 0 } ?: elemente.firstOrNull { it.id == e.id }?.untisId ?: throw Untis.Fehler("${e.art} „${e.name}“ gibt es in WebUntis nicht.")
        val felder = JSONArray().put("id").put("name").put("longname")
        val o = JSONObject().put("element", JSONObject().put("id", id).put("type", e.typ)).put("startDate", Untis.zahl(montag)).put("endDate", Untis.zahl(montag.plusDays(4)))
            .put("showSubstText", true).put("klasseFields", felder).put("teacherFields", felder).put("subjectFields", felder).put("roomFields", felder)
        return Untis.stunden(s.rufe("getTimetable", JSONObject().put("options", o)))
    }

    fun schliessen() { runCatching { s.rufe("logout") } }
}

/** Raster wie in DammTable: 0. Stunde 8:00–8:30 (Frühförderung) vor den Schulzeiten */
object UntisRaster {
    fun raster(zeiten: List<Schulzeit>) = listOf(Schulzeit("0", 480, 510)) + zeiten.filter { it.name != "0" }

    /** Zeilen einer Woche: ab der 1. Stunde (die 0. nur, wenn belegt) bis zur letzten belegten Stunde, Pausen dazwischen */
    fun zeilen(stunden: List<UntisStunde>, zeiten: List<Schulzeit>): List<Schulzeit> {
        val r = raster(zeiten)
        val belegt = r.indices.filter { i -> !r[i].pause && stunden.any { it.von < r[i].bis && it.bis > r[i].von } }
        if (belegt.isEmpty()) return r.filter { it.name != "0" }
        val eins = r.indexOfFirst { it.name == "1" }.takeIf { it >= 0 } ?: belegt.first()
        val start = minOf(belegt.first(), eins)
        return r.subList(start, maxOf(belegt.last(), start) + 1)
    }

    fun in_(stunden: List<UntisStunde>, datum: String, z: Schulzeit) = stunden.filter { it.datum == datum && it.von < z.bis && it.bis > z.von }
}
