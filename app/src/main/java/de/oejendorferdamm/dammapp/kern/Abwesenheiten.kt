@file:kotlinx.serialization.UseSerializers(LDTSerializer::class, LDSerializer::class)

package de.oejendorferdamm.dammapp.kern

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

/** Eine Abwesenheit aus IServ (nur zur Anzeige – keine Statistik je Kind, kein Export) */
@kotlinx.serialization.Serializable
data class Abwesenheit(val name: String, val klasse: String, val von: String, val bis: String, val art: String, val stunden: String = "") {
    val stundenweise get() = stunden.isNotEmpty() && von == bis
}

@kotlinx.serialization.Serializable

data class AbwesenheitenStand(val eintraege: List<Abwesenheit>, val stand: LocalDateTime = Zeit.jetzt())

/** JSON-Tabellen aus IServ: Liste von Zeilen oder {data|aaData|rows: […]} */
internal object JsonTabelle {
    fun zeilen(body: String): List<List<String>>? {
        val j = Netz.jsonAus(body) ?: return null
        val a = (j as? JSONArray) ?: (j as? JSONObject)?.let { o -> (o.opt("data") ?: o.opt("aaData") ?: o.opt("rows")) as? JSONArray } ?: return null
        return (0 until a.length()).mapNotNull { i ->
            val z = a.optJSONArray(i) ?: return@mapNotNull null
            (0 until z.length()).map { k -> val c = z.opt(k); Html.text((c as? String) ?: (c as? JSONObject)?.optString("display") ?: c?.toString() ?: "") }
        }
    }
    fun leer(body: String): Boolean = (Netz.jsonAus(body) as? JSONObject)?.optJSONArray("data")?.length() == 0
}

object Abwesenheiten {
    val modulKandidaten = listOf("/iserv/absence", "/iserv/absence/", "/iserv/dsa-absence", "/iserv/dsa/absence")
    const val MODUL_MUSTER = "abwesen|absence|krankmeld|fehlzeit"
    const val FENSTER_TAGE = 21L
    val arten = mapOf("unentschuldigt" to ("🤒" to "unentschuldigt"), "entschuldigt" to ("✅" to "entschuldigt"), "krank" to ("🤒" to "krank"),
        "beurlaubt" to ("🏖️" to "beurlaubt"), "ohne" to ("❓" to "ohne Info"), "sonstig" to ("📌" to "abwesend"))

    fun laden(s: IServ, quelle: IServQuelle?): Pair<AbwesenheitenStand, IServQuelle> {
        if (quelle != null) lesen(s.lade(quelle), quelle.spalten)?.let { return AbwesenheitenStand(imFenster(it)) to quelle }
        val (q, l) = s.erkunde(MODUL_MUSTER, modulKandidaten) { x, sp -> lesen(x, sp) } ?: throw IServFehler.NichtGefunden("Abwesenheiten-Daten nicht gefunden.")
        return AbwesenheitenStand(imFenster(l)) to q
    }

    fun imFenster(l: List<Abwesenheit>, heute: LocalDate = Zeit.heute()): List<Abwesenheit> {
        val min = heute.minusDays(FENSTER_TAGE).toString(); val max = heute.plusDays(FENSTER_TAGE).toString()
        return l.filter { it.bis >= min && it.von <= max }
    }

    fun lesen(s: IServ.Seite, spalten: List<String>): List<Abwesenheit>? {
        val body = s.body.trim()
        if (s.typ.lowercase().contains("json") || body.startsWith("{") || body.startsWith("[")) {
            val z = JsonTabelle.zeilen(body)
            if (z != null && spalten.isNotEmpty()) return ausTabelle(listOf(spalten) + z)
            if (JsonTabelle.leer(body)) return emptyList()
            return null
        }
        if (s.typ.lowercase().contains("csv") || Regex("""^[^<\n]*(name|schüler)[^<\n]*[;,\t][^<\n]*\n""", RegexOption.IGNORE_CASE).containsMatchIn(body)) ausCSV(body)?.let { return it }
        val t = Html.tabellen(body).flatMap { tab -> ausTabelle(tab.map { it.zellen }) ?: emptyList() }
        return t.ifEmpty { null }
    }

    fun ausTabelle(zeilen: List<List<String>>): List<Abwesenheit>? {
        if (zeilen.size < 2) return null
        val kopf = zeilen[0].map { it.lowercase() }
        fun sp(m: String) = kopf.indexOfFirst { Regex(m).containsMatchIn(it) }.takeIf { it >= 0 }
        val sName = sp("name|schüler|schueler|kind") ?: return null
        val sVon = sp("von|beginn|start|datum|zeitraum|zeit|tag") ?: return null
        val sKlasse = sp("klasse|lerngruppe"); val sBis = sp("bis|ende"); val sArt = sp("art|grund|typ|status|kategorie"); val sStd = sp("stunde")
        return zeilen.drop(1).mapNotNull { z ->
            if (z.size <= maxOf(sName, sVon)) return@mapNotNull null
            val daten = Html.alle("""(\d{1,2}\.\d{1,2}\.(?:\d{2,4})?)""", z[sVon])
            val von = datum(daten.firstOrNull() ?: z[sVon]) ?: return@mapNotNull null
            if (z[sName].isEmpty()) return@mapNotNull null
            val bis = sBis?.let { z.getOrNull(it)?.let(::datum) } ?: daten.getOrNull(1)?.let(::datum) ?: von
            Abwesenheit(Html.nameSchoen(z[sName]), sKlasse?.let { z.getOrNull(it) } ?: "", von, bis, art(sArt?.let { z.getOrNull(it) } ?: ""),
                sStd?.let { z.getOrNull(it) } ?: uhrzeit(z[sVon]))
        }
    }

    fun ausCSV(body: String): List<Abwesenheit>? {
        val zeilen = Html.csv(body)
        val kopf = zeilen.firstOrNull()?.map { it.lowercase() } ?: return null
        fun sp(m: String) = kopf.indexOfFirst { Regex(m).containsMatchIn(it) }.takeIf { it >= 0 }
        val sVor = sp("^vorname"); val sNach = sp("^nachname|^familienname"); val sName = sp("name|schüler|schueler")
        if (sName == null && sNach == null) return null
        val sVon = sp("von|beginn|start|zeitraum|abwesenheitszeit|datum") ?: return null
        val sKlasse = sp("klasse|lerngruppe"); val sBis = sp("bis|ende"); val sArt = sp("art|grund|typ|status|kategorie|entschuldig"); val sStd = sp("stunde")
        fun f(z: List<String>, i: Int?) = i?.let { z.getOrNull(it) } ?: ""
        return zeilen.drop(1).mapNotNull { z ->
            val daten = Html.alle("""(\d{1,2}\.\d{1,2}\.(?:\d{2,4})?|\d{4}-\d{2}-\d{2})""", f(z, sVon))
            val name = if (sNach != null) listOf(f(z, sVor), f(z, sNach)).filter { it.isNotEmpty() }.joinToString(" ") else Html.nameSchoen(f(z, sName))
            val von = daten.firstOrNull()?.let(::datum) ?: return@mapNotNull null
            if (name.isEmpty()) return@mapNotNull null
            val bis = (if (sBis != null) datum(f(z, sBis)) else null) ?: daten.getOrNull(1)?.let(::datum) ?: von
            Abwesenheit(name, f(z, sKlasse), von, bis, art(f(z, sArt)), if (sStd != null) f(z, sStd) else uhrzeit(f(z, sVon)))
        }
    }

    /** „12.10.2026“, „12.10.“, „2026-10-12…“ → ISO-Tag */
    fun datum(s: String, jahr: Int? = null): String? {
        Html.erster("""(\d{4}-\d{2}-\d{2})""", s)?.let { return it }
        val m = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{2,4})?""").find(s) ?: return null
        val t = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
        if (t !in 1..31 || mo !in 1..12) return null
        var j = m.groupValues[3].toIntOrNull() ?: 0
        if (j == 0) j = jahr ?: Zeit.heute().year else if (j < 100) j += 2000
        return "%04d-%02d-%02d".format(j, mo, t)
    }

    fun art(s: String): String {
        val t = s.lowercase()
        return when {
            t.contains("unentsch") -> "unentschuldigt"
            t.contains("entschuldigt") -> "entschuldigt"
            Regex("krank|sick|ill").containsMatchIn(t) -> "krank"
            Regex("beurlaub|urlaub|leave|befrei|antrag").containsMatchIn(t) -> "beurlaubt"
            Regex("ohne|unbekannt|unknown|missing|keine info|offen").containsMatchIn(t) -> "ohne"
            else -> "sonstig"
        }
    }

    /** „12.10.2026 (08:00 - 10:00)“ → „8–10 Uhr“ */
    fun uhrzeit(s: String): String {
        val m = Regex("""\((\d{1,2}):(\d{2})\s*[-–]\s*(\d{1,2}):(\d{2})\)""").find(s) ?: return ""
        fun t(h: String, mi: String) = "${h.toInt()}" + if (mi == "00") "" else ":$mi"
        return "${t(m.groupValues[1], m.groupValues[2])}–${t(m.groupValues[3], m.groupValues[4])} Uhr"
    }

    /** Ausgegraut? (Name steht in [aus], ohne Groß/Klein und Leerzeichen am Rand) */
    fun ausgegraut(a: Abwesenheit, aus: List<String>) = aus.any { it.trim().equals(a.name.trim(), ignoreCase = true) }

    /** Personen in einer Plan-Zeile: „Wa+Hoff (8:30-12:15)“ = 2 */
    fun personen(liste: List<String>) = liste.sumOf { t -> t.substringBefore('(').split('+', '&').count { it.isNotBlank() }.coerceAtLeast(1) }

    /**
     * Zahl oben in der Kachel: morgens die Meldungen aus IServ (ohne Ausgegraute); ist der Vertretungsplan für den Tag da,
     * dessen „Es fehlen“ plus „Außer Haus“ – „7 + 2“ / „krank + außer Haus“.
     */
    fun zahl(liste: List<Abwesenheit>, aus: List<String>, plan: VertretungsPlan?, ziel: LocalDate): Pair<String, String> {
        if (plan != null && plan.datum == ziel.toString() && (plan.fehlen.isNotEmpty() || plan.ausserListe.isNotEmpty())) {
            val k = personen(plan.fehlen); val a = personen(plan.ausserListe)
            return if (a > 0) "$k + $a" to "krank + außer Haus" else "$k" to "krank"
        }
        val n = liste.count { !ausgegraut(it, aus) }
        return "$n" to "abwesend"
    }

    /** Wer fehlt am Zieltag: nach Klassen gefiltert, jede Person einmal, ganztägig zuerst */
    fun amTag(l: List<Abwesenheit>, ziel: LocalDate, klassen: List<String> = emptyList()): List<Abwesenheit> {
        val z = ziel.toString()
        val filter = klassen.map { it.lowercase().replace(" ", "") }.filter { it.isNotEmpty() }
        val rang = mapOf("unentschuldigt" to 0, "krank" to 1, "ohne" to 2, "beurlaubt" to 3, "entschuldigt" to 4, "sonstig" to 5)
        val gesehen = HashSet<String>()
        return l.filter { it.von <= z && z <= it.bis }
            .filter { e -> filter.isEmpty() || e.klasse.lowercase().replace(" ", "") in filter }
            .filter { gesehen.add(it.name) }
            .sortedWith(compareBy<Abwesenheit>({ if (it.stundenweise) 1 else 0 }, { rang[it.art] ?: 5 }, { it.name }))
    }

    /** „seit Mo“, „bis Fr“, „ab morgen da“ oder die Uhrzeit bei einzelnen Stunden */
    fun zeitText(e: Abwesenheit, ziel: LocalDate, jetzt: LocalDate = Zeit.heute()): String {
        val wt = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        val von = Zeit.tag(e.von) ?: return ""; val bis = Zeit.tag(e.bis) ?: return ""
        val z = ziel.toString()
        val teile = ArrayList<String>()
        fun kurz(d: LocalDate, tage: Long) = if (tage < 7) wt[d.dayOfWeek.value - 1] else "${d.dayOfMonth}.${d.monthValue}."
        if (e.von < z) teile.add("seit " + kurz(von, java.time.temporal.ChronoUnit.DAYS.between(von, ziel)))
        if (e.bis == z) {
            if (teile.isNotEmpty()) {
                var zurueck = ziel.plusDays(1)
                while (Zeit.wochenende(zurueck)) zurueck = zurueck.plusDays(1)
                teile.add(if (zurueck == jetzt.plusDays(1)) "ab morgen da" else "ab ${wt[zurueck.dayOfWeek.value - 1]} da")
            } else if (e.stunden.isNotEmpty()) teile.add(e.stunden)
        } else teile.add("bis " + kurz(bis, java.time.temporal.ChronoUnit.DAYS.between(ziel, bis)))
        return teile.joinToString(" · ")
    }

    /** Sichtschutz: nur Vorname + Initiale („Mia K.“) */
    fun kurzName(n: String): String { val t = n.split(" "); return if (t.size > 1) "${t[0]} ${t.last().take(1)}." else n }
}
