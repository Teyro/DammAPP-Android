@file:kotlinx.serialization.UseSerializers(LDTSerializer::class, LDSerializer::class)

package de.oejendorferdamm.dammapp.kern

import java.time.LocalDate
import java.time.LocalDateTime

/** Eine Störungsmeldung aus IServ */
@kotlinx.serialization.Serializable
data class Stoerung(val titel: String, val inhalt: String, val autor: String, val bearbeiter: String, val datum: LocalDateTime?, val status: String,
                    val ort: String, val erledigt: Boolean, val unbesetzt: Boolean, val url: String) {
    val uebernommen get() = !erledigt && (bearbeiter.isNotEmpty() || !unbesetzt)
    val zustandKurz get() = when {
        erledigt -> "Erledigt"; bearbeiter.isNotEmpty() -> bearbeiter; uebernommen -> status.ifEmpty { "In Arbeit" }; else -> "Noch niemand"
    }
    val schluessel get() = titel + "|" + (datum?.toString() ?: "")
}

@kotlinx.serialization.Serializable

data class StoerungenStand(val meldungen: List<Stoerung>, val stand: LocalDateTime = Zeit.jetzt()) {
    val offen get() = meldungen.filter { !it.erledigt }.sortedByDescending { it.datum }
    val alle get() = meldungen.sortedByDescending { it.datum }
}

/** Gesprächsverlauf einer Meldung und – falls IServ eins anbietet – das Formular zum Antworten */
data class StoerungVerlauf(val eintraege: List<Eintrag>, val formular: Formular?,
    /** Profilbilder aus der Seite: Name → Adresse (lädt mit der IServ-Sitzung) */ val avatare: Map<String, String> = emptyMap()) {
    /** Profilbild zu einem Namen (Groß-/Kleinschreibung und „Admin“ egal) */
    fun avatar(name: String): String? {
        fun n(s: String) = s.lowercase().replace(Regex("""\badmin\b"""), "").split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
        val k = n(name); if (k.isEmpty()) return null
        return avatare.entries.firstOrNull { n(it.key) == k }?.value
    }
    data class Eintrag(val autor: String, val zeit: String, val text: String)
    data class Formular(val aktion: String, val felder: List<Pair<String, String>>, val textFeld: String)
}

object Stoerungen {
    val modulKandidaten = listOf("/iserv/report/", "/iserv/defect", "/iserv/defect/", "/iserv/defects", "/iserv/troubleticket", "/iserv/ticket")
    const val MODUL_MUSTER = "st(ö|oe|&ouml;)rung|defect|trouble|ticket"
    private const val ERLEDIGT = "erledigt|geschlossen|behoben|gel(ö|oe)st|abgeschlossen|closed|resolved|done|fertig"
    private const val UNBESETZT = "wer mach|niemand|unbearbeitet|^neu$|^offen$|new|open"
    private val spalten = listOf(
        "bearbeiter" to "bearbeit|zust(ä|ae)ndig|(ü|ue)bernommen|zugewiesen|verantwortlich|assign|techniker|admin",
        "autor" to "gemeldet von|erstellt von|eingereicht von|^von$|autor|verfasser|melder|ersteller|absender|benutzer|gemeldet durch",
        "titel" to "betreff|titel|title|st(ö|oe)rung|problem|bezeichnung|thema|subject|kurzbeschreibung",
        "inhalt" to "beschreibung|inhalt|nachricht|text|details|fehlerbeschreibung|description|body",
        "status" to "status|zustand",
        "ort" to "raum|ort|ger(ä|ae)t|standort|location",
        "kategorie" to "kategorie|bereich|typ|art$",
        "aktualisiert" to "aktualisier|ge(ä|ae)ndert|update",
        "datum" to "datum|erstellt|gemeldet|zeit|eingang|date|created",
    )

    fun laden(s: IServ, quelle: IServQuelle?): Pair<StoerungenStand, IServQuelle> {
        if (quelle != null) lesen(s.lade(quelle), quelle.spalten)?.let { return StoerungenStand(it) to quelle }
        val (q, l) = s.erkunde(MODUL_MUSTER, modulKandidaten) { x, sp -> lesen(x, sp) } ?: throw IServFehler.NichtGefunden("Störungsmeldungen nicht gefunden.")
        return StoerungenStand(l) to q
    }

    fun zuordnen(kopf: List<String>): Map<String, Int> {
        val z = HashMap<String, Int>(); val belegt = HashSet<Int>()
        for ((feld, m) in spalten) {
            val i = kopf.indices.firstOrNull { it !in belegt && Regex(m, RegexOption.IGNORE_CASE).containsMatchIn(kopf[it].trim()) } ?: continue
            z[feld] = i; belegt.add(i)
        }
        return z
    }

    fun meldung(zellen: List<String>, z: Map<String, Int>, link: String): Stoerung? {
        fun w(f: String) = z[f]?.let { zellen.getOrNull(it)?.trim() } ?: ""
        val titel = w("titel").ifEmpty { w("inhalt").take(80) }
        if (titel.isEmpty()) return null
        val status = w("status")
        val erledigt = Regex(ERLEDIGT, RegexOption.IGNORE_CASE).containsMatchIn(status)
        val bearbeiter = if (Regex("^[-–—]$|^(niemand|keine?r?)$", RegexOption.IGNORE_CASE).containsMatchIn(w("bearbeiter"))) "" else w("bearbeiter")
        return Stoerung(titel, if (z["titel"] != null) w("inhalt") else "", Html.nameSchoen(w("autor")), Html.nameSchoen(bearbeiter), zeit(w("datum")), status, w("ort"),
            erledigt, !erledigt && (status.isEmpty() || Regex(UNBESETZT, RegexOption.IGNORE_CASE).containsMatchIn(status)), link)
    }

    fun lesen(s: IServ.Seite, sp: List<String>): List<Stoerung>? {
        val body = s.body.trim()
        if (s.typ.lowercase().contains("json") || body.startsWith("{") || body.startsWith("[")) {
            val zeilen = JsonTabelle.zeilen(body) ?: return null
            if (sp.isEmpty()) return null
            val z = zuordnen(sp)
            if (z["titel"] == null) return null
            return zeilen.mapNotNull { meldung(it, z, "") }
        }
        if (s.typ.lowercase().contains("csv")) {
            val zeilen = Html.csv(body)
            zeilen.firstOrNull()?.let { kopf -> val z = zuordnen(kopf); if (z["titel"] != null) return zeilen.drop(1).mapNotNull { meldung(it, z, "") } }
        }
        val liste = ArrayList<Stoerung>()
        for (tab in Html.tabellen(body)) {
            if (tab.size < 2) continue
            val z = zuordnen(tab[0].zellen)
            if (z["titel"] == null || (z["datum"] == null && z["autor"] == null)) continue
            for (r in tab.drop(1)) if (r.zellen.size >= 2) meldung(r.zellen, z, r.link?.let { Html.absolut(Html.entities(it), s.url) } ?: "")?.let(liste::add)
        }
        return liste.ifEmpty { null }
    }

    /** „12.10.2026, 08:15“, „2026-10-12T…“, „heute 9:30“, „gestern“ */
    fun zeit(s: String): LocalDateTime? {
        fun uhr(d: LocalDate): LocalDateTime {
            val u = Html.erster("""(\d{1,2}:\d{2})""", s)?.let { Zeit.minuten(it) } ?: return d.atStartOfDay()
            return d.atTime(u / 60, u % 60)
        }
        if (Regex("""\d{4}-\d{2}-\d{2}""").containsMatchIn(s)) {
            runCatching { return java.time.OffsetDateTime.parse(s.trim()).atZoneSameInstant(Zeit.zone).toLocalDateTime() }
            return Zeit.tag(Html.erster("""(\d{4}-\d{2}-\d{2})""", s) ?: "")?.let { uhr(it) }
        }
        if (Regex("""\d{1,2}\.\d{1,2}\.\d{2,4}""").containsMatchIn(s)) Abwesenheiten.datum(s)?.let { Zeit.tag(it) }?.let { return uhr(it) }
        if (s.contains("heute", true)) return uhr(Zeit.heute())
        if (s.contains("gestern", true)) return uhr(Zeit.heute().minusDays(1))
        return null
    }

    // MARK: Verlauf und Antworten

    // ganze Wörter: „comment“, „comment-item“ ja – Sammelbehälter wie „comments“/„messages“ nicht
    private const val EINTRAG = """<(div|li|article|section|tr)\b[^>]*class=["'](?:[^"']*\s)?(comment|kommentar|reply|antwort|answer|message|nachricht|activity|timeline-item|post)(?:-[\w-]*)?(?=[\s"'])[^"']*["'][^>]*>"""

    /** Kommentarkopf wie in IServ: „Christian Stake am 18.11.2026 11:03“ (auch „…, 11:03 Uhr“) am Zeilenanfang */
    private val KOPF = Regex("""(?m)^[ \t]*([A-ZÄÖÜ][\p{L}'.]+(?:[ -][A-ZÄÖÜ][\p{L}'.]+){0,3})[ \t]+am[ \t]+(\d{1,2}\.\d{1,2}\.\d{2,4})(?:,?[ \t]*(?:um[ \t]*)?(\d{1,2}:\d{2}))?(?:[ \t]*Uhr)?[ \t]*:?[ \t]*\n?""")

    /** Ein Eintrag mit mehreren Kommentarköpfen → je Kommentar ein Eintrag (der Text davor bleibt beim ursprünglichen Absender) */
    fun aufteilen(e: StoerungVerlauf.Eintrag): List<StoerungVerlauf.Eintrag> {
        val treffer = KOPF.findAll(e.text).toList()
        if (treffer.isEmpty()) return listOf(e)
        fun sauber(t: String) = t.replace(Regex("""(?m)^[ \t]*(Kommentare|Kommentar|Dateien|Antworten)[ \t]*$"""), "").trim()
        val aus = ArrayList<StoerungVerlauf.Eintrag>()
        sauber(e.text.substring(0, treffer[0].range.first)).takeIf { it.isNotEmpty() }?.let { aus.add(e.copy(text = it)) }
        treffer.forEachIndexed { i, m ->
            val ende = if (i + 1 < treffer.size) treffer[i + 1].range.first else e.text.length
            val text = sauber(e.text.substring(m.range.last + 1, ende))
            val zeit = listOf(m.groupValues[2], m.groupValues[3]).filter { it.isNotEmpty() }.joinToString(" ")
            if (text.isNotEmpty()) aus.add(StoerungVerlauf.Eintrag(Html.nameSchoen(m.groupValues[1]), zeit, text))
        }
        return aus.ifEmpty { listOf(e) }
    }

    /** Bin das ich? IServ-Benutzer „christian.stake“ ↔ „Christian Stake“ / „Christian Stake-Admin“; [ichName] von Hand hat Vorrang */
    fun istIch(autor: String, benutzer: String, ichName: String = ""): Boolean {
        fun norm(s: String) = s.lowercase().replace(Regex("""[-\s]*\(?admin\)?$"""), "").replace(Regex("[._-]"), " ").trim()
        val a = norm(autor)
        if (a.isEmpty()) return false
        if (ichName.isNotBlank()) return a == norm(ichName)
        val b = norm(benutzer.substringBefore('@'))
        return b.isNotEmpty() && (a == b || a.startsWith("$b "))
    }

    fun verlauf(s: IServ, url: String): StoerungVerlauf {
        s.hostPruefen(url)
        val seite = s.lade(IServQuelle(url, "direkt"))
        return verlaufAus(seite.body, seite.url)
    }

    /** Antwort über das Formular der Detailseite schicken (nur an IServ selbst) */
    fun antworten(s: IServ, f: StoerungVerlauf.Formular, text: String) {
        val host = runCatching { java.net.URL(f.aktion).host.lowercase() }.getOrDefault("")
        if (!f.aktion.lowercase().startsWith("https:") || host != s.zugang.server) throw IServFehler.Allgemein("Antworten geht nur direkt an IServ.")
        val felder = f.felder.filter { it.first != f.textFeld } + (f.textFeld to text)
        val a = s.anfrage(f.aktion, "POST", mapOf("Referer" to f.aktion), felder)
        if (a.status !in 200..399 || IServ.istLoginSeite(a)) throw IServFehler.Allgemein("Antwort nicht gesendet (HTTP ${a.status}).")
    }

    fun verlaufAus(html: String, von: String): StoerungVerlauf {
        val ohneSkript = html.replace(Regex("""<script[\s\S]*?</script>|<style[\s\S]*?</style>|<nav[\s\S]*?</nav>|<header[\s\S]*?</header>|<footer[\s\S]*?</footer>""", RegexOption.IGNORE_CASE), " ")
        var formular: StoerungVerlauf.Formular? = null
        for (f in Html.formulare(ohneSkript)) {
            val ta = Html.erster("""(<textarea\b[^>]*>)""", f) ?: continue
            if (Html.name(ta).isEmpty()) continue
            val felder = ArrayList<Pair<String, String>>()
            for (i in Html.eingaben(f)) {
                val n = Html.name(i); val t = Html.typ(i)
                if (n.isEmpty() || t in listOf("submit", "button", "file", "image", "reset")) continue
                if ((t == "checkbox" || t == "radio") && !Html.hat("""\bchecked\b""", i)) continue
                felder.add(n to Html.entities(Html.attr(i, "value") ?: ""))
            }
            (Html.erster("""(<button\b[^>]*type=["']submit["'][^>]*>)""", f) ?: Html.erster("""(<input\b[^>]*type=["']submit["'][^>]*>)""", f))?.let { k ->
                if (Html.name(k).isNotEmpty()) felder.add(Html.name(k) to Html.entities(Html.attr(k, "value") ?: ""))
            }
            val aktion = Html.entities(Html.attr(Html.erster("""(<form\b[^>]*>)""", f) ?: "", "action") ?: "")
            formular = StoerungVerlauf.Formular(if (aktion.isEmpty()) von else Html.absolut(aktion, von), felder, Html.name(ta))
            break
        }
        val ohneFormulare = ohneSkript.replace(Regex("""<form[\s\S]*?</form>""", RegexOption.IGNORE_CASE), " ")
        val starts = Regex(EINTRAG, RegexOption.IGNORE_CASE).findAll(ohneFormulare).map { it.range.first }.toList()
        val eintraege = ArrayList<StoerungVerlauf.Eintrag>()
        for ((n, start) in starts.withIndex()) {
            val ende = if (n + 1 < starts.size) starts[n + 1] else minOf(ohneFormulare.length, start + 6000)
            val block = ohneFormulare.substring(start, maxOf(start, ende))
            val autor = Html.text(Html.erster("""class=["'][^"']*\b(?:author|autor|user|username|name|creator)\b[^"']*["'][^>]*>([\s\S]*?)</""", block) ?: "")
            val zeit = Html.text(Html.erster("""<time\b[^>]*>([\s\S]*?)</time>""", block) ?: Html.erster("""(\d{1,2}\.\d{1,2}\.\d{2,4}(?:,?\s*\d{1,2}:\d{2})?)""", block) ?: "")
            var text = Html.absatzText(block)
            for (weg in listOf(autor, zeit)) if (weg.isNotEmpty()) text = text.replace(weg, "")
            text = text.trim()
            if (text.isNotEmpty() && eintraege.none { it.text == text }) eintraege.add(StoerungVerlauf.Eintrag(Html.nameSchoen(autor), zeit, text))
        }
        if (eintraege.isEmpty()) {
            val inhalt = Html.erster("""<main\b[^>]*>([\s\S]*?)</main>""", ohneFormulare) ?: Html.erster("""<div[^>]*id=["']content["'][^>]*>([\s\S]*)""", ohneFormulare) ?: ohneFormulare
            val t = Html.absatzText(inhalt)
            if (t.isNotEmpty()) eintraege.add(StoerungVerlauf.Eintrag("", "", t.take(8000)))
        }
        // Ein Block mit mehreren Kommentaren (Sammelbehälter oder ganze Seite): an „Name am 18.11.2026 11:03“ trennen
        return StoerungVerlauf(bereinigen(eintraege.flatMap(::aufteilen)), formular, avatare(html, von))
    }

    /** Textbausteine der IServ-Seite (Knöpfe, leere Hinweise, Angaben-Spalten), die in Blasen keinen Sinn ergeben – je ganze Zeile */
    private val BAUSTEIN = Regex("""(?im)^[ \t]*(?:(?:es\s+(?:gibt|sind|wurden)\s+)?(?:noch\s+)?kein(?:e|en)?\s+(?:weitere[nrs]?\s+)?(?:kommentare?|antworten?|text|dateien|anh(?:ä|ae)nge?|beschreibung|nachrichten?)(?:\s+(?:vorhanden|verfügbar|hinzugefügt|angeh(?:ä|ae)ngt|hochgeladen))?[.!]?|(?:einen\s+|neuen?\s+)?(?:kommentare?|antwort)\s+(?:hinzufügen|schreiben|verfassen|absenden|abschicken|senden|speichern)|kommentare?(?:\s*\(\d+\))?|antworten|dateien|anh(?:ä|ae)nge?|bearbeiten|l(?:ö|oe)schen|speichern|abbrechen|zur(?:ü|ue)ck(?:\s+zur\s+(?:liste|(?:ü|ue)bersicht))?|details\s+zu\b.{0,80}|mehr\s+anzeigen|weniger\s+anzeigen|(?:status|raum|ort|ger(?:ä|ae)t|standort|kategorie|priorit(?:ä|ae)t|bearbeiter(?:in)?|zust(?:ä|ae)ndig|gemeldet(?:\s+von)?|erstellt(?:\s+von|\s+am)?|aktualisiert(?:\s+am)?|ge(?:ä|ae)ndert(?:\s+am)?|datum)\s*:.{0,60})[ \t]*$""")

    /** Profilbilder der Seite: <img> mit „avatar/profil/photo/picture“ in Adresse oder Klasse und Namen in alt/title */
    fun avatare(html: String, von: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for (tag in Html.alle("""(<img\b[^>]*>)""", html)) {
            if (!Html.hat("avatar|profil|photo|picture|userimage", tag)) continue
            val src = Html.attr(tag, "src") ?: continue
            if (src.startsWith("data:")) continue
            val name = Html.nameSchoen(Html.entities(Html.attr(tag, "alt") ?: Html.attr(tag, "title") ?: ""))
                .replace(Regex("""^(?:Profilbild|Avatar|Bild)(?:\s+(?:von|of))?\s*""", RegexOption.IGNORE_CASE), "")
            if (name.length < 3 || !name.contains(" ") || name in m) continue
            m[name] = Html.absolut(Html.entities(src), von)
        }
        return m
    }

    private val KOPF_ZEILE = Regex("""\|\s*\d{1,2}\.\d{1,2}\.\d{2,4}\s+\d{1,2}:\d{2}""")
    private val STATUS = Regex("""(?i)^\|?\s*(eingereicht|in arbeit|wer machts\??|erledigt|abgeschlossen|geschlossen|offen|neu|angenommen|abgelehnt|zurückgestellt)\s*\|?$""")

    /** Kopf der Detailseite („Details zu …“, Titel, „Name | 01.10.2026 07:51“, „| Medienproblem |“, „eingereicht“) – steht schon oben */
    fun ohneKopf(text: String): String {
        val z = text.split("\n")
        val i = z.take(10).indexOfFirst { KOPF_ZEILE.containsMatchIn(it) }
        if (i < 0) return text
        var j = i + 1
        while (j < z.size) {
            val s = z[j].trim()
            if (!(s.isEmpty() || STATUS.matches(s) || (s.contains("|") && s.length <= 60))) break
            j++
        }
        return z.drop(j).joinToString("\n")
    }

    /** Textbausteine weg, doppelte Zeilen in einer Blase weg, gleicher Text nur einmal (kurze wie „Danke“ nur je Person) */
    fun bereinigen(l: List<StoerungVerlauf.Eintrag>): List<StoerungVerlauf.Eintrag> {
        val gesehen = HashSet<String>()
        return l.mapNotNull { e ->
            val doppelt = HashSet<String>()
            val text = ohneKopf(e.text).replace(BAUSTEIN, "").split("\n")
                .filter { z -> val k = z.trim().lowercase(); k.isEmpty() || doppelt.add(k) }
                .joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()
            val t = text.lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
            if (t.isEmpty() || !gesehen.add(if (t.length >= 20) t else e.autor + "|" + t)) null else e.copy(text = text)
        }
    }
}
