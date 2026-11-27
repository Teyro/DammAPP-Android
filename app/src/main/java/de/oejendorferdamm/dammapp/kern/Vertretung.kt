@file:kotlinx.serialization.UseSerializers(LDTSerializer::class, LDSerializer::class)

package de.oejendorferdamm.dammapp.kern

import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipInputStream

/** Was im Vertretungsplan (Word-Datei im Cloud-Ordner der Schule) für ein Kürzel steht */
@kotlinx.serialization.Serializable
data class VertretungsPlan(
    var datum: String? = null,
    val stunden: MutableList<Stunde> = mutableListOf(),
    val pausen: MutableList<Pause> = mutableListOf(),
    var fehlt: Boolean = false,
    var ausserHaus: Boolean = false,
    val hinweise: MutableList<String> = mutableListOf(),
    /** Die ganze „Wichtig“-Zelle (für alle) */
    val alleHinweise: MutableList<String> = mutableListOf(),
    var fehlen: List<String> = emptyList(),
    /** Aufgeteilte Klassen je Stunde („A“ in der Klassenspalte): "3" → [4a, 4b] */
    val aufteilung: MutableMap<String, MutableList<String>> = mutableMapOf(),
    var ausserListe: List<String> = emptyList(),
    var datei: String = "",
    /** Wie viele Zellen im Stundenteil ausgefüllt sind (leere Vorlage = 0) */
    var belegt: Int = 0,
    /** Bemerkungen aus der „Wichtig“-Zeile für das eigene Kürzel (Begleitung, Termin …) */
    var bemerkungen: List<Bemerkung> = emptyList(),
    /** Aufsichten aller Lehrkräfte aus den Pausenzeilen: Kürzel (klein) → Pausen – für die Pläne der anderen */
    val alleAufsichten: MutableMap<String, MutableList<Pause>> = mutableMapOf(),
) {
    @kotlinx.serialization.Serializable
    data class Stunde(val stunde: String, val klasse: String)
    companion object {
        /** „Vertretung 2c“ – bei Zeilen ohne Stundennummer („Frühbetreuung Mensa“) deren Titel */
        fun titel(s: Stunde) = if (s.stunde.trim('.', ' ').toIntOrNull() != null) "Vertretung ${s.klasse}" else listOf(s.stunde, s.klasse).filter { it.isNotEmpty() }.joinToString(" ")
    }
    /** „(Sta)“ im Plan = nur Reserve */
    @kotlinx.serialization.Serializable
    data class Pause(val pause: String, val ort: String, val reserve: Boolean = false)

    fun satz(haeuser: Map<String, List<String>> = emptyMap()): String {
        val t = ArrayList<String>()
        if (fehlt) t.add("du bist als fehlend eingetragen")
        if (ausserHaus) t.add("du bist außer Haus eingetragen")
        t += stunden.map { s ->
            val h = Vertretung.haus(s.klasse, haeuser)
            (if (s.stunde.toIntOrNull() != null) "${s.stunde}. Stunde" else s.stunde) + " " + s.klasse + (if (h.isEmpty()) "" else " in $h")
        }
        t += pausen.map { "${it.pause} Aufsicht ${it.ort}" + if (it.reserve) " als Reserve" else "" }
        return if (t.isEmpty()) "Laut Vertretungsplan hast du keine Vertretung." else "Vertretung: " + t.joinToString(", ") + "."
    }
}

object Vertretung {
    /** Haus einer Klasse laut Einstellung („Haus 1“: [1a, 1b]); "2a–2b" zählt nach der ersten */
    fun haus(klasse: String, haeuser: Map<String, List<String>>): String {
        fun norm(s: String) = s.lowercase().replace(" ", "")
        val k = norm(klasse.split("–").firstOrNull() ?: "")
        if (k.isEmpty()) return ""
        return haeuser.toSortedMap().entries.firstOrNull { e -> e.value.any { norm(it) == k } }?.key ?: ""
    }

    /** „Haus 1: 1a, 1b“ je Zeile ↔ Map */
    fun haeuser(text: String): Map<String, List<String>> {
        val h = LinkedHashMap<String, List<String>>()
        for (zeile in text.lines()) {
            val teile = zeile.split(":", limit = 2)
            if (teile.size != 2) continue
            val name = teile[0].trim()
            val klassen = teile[1].split(Regex("[,; ]+")).filter { it.isNotEmpty() }
            if (name.isNotEmpty() && klassen.isNotEmpty()) h[name] = klassen
        }
        return h
    }
    fun text(haeuser: Map<String, List<String>>) = haeuser.toSortedMap().entries.joinToString("\n") { "${it.key}: ${it.value.joinToString(", ")}" }

    /** "https://host/s/TOKEN" → (host, token) */
    fun zerlege(link: String): Pair<String, String>? {
        val m = Regex("""^(https://[^/\s]+)(?:/index\.php)?/s/([A-Za-z0-9]+)/?$""").find(link.trim()) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    data class Kandidat(val name: String, val zeit: Long, val rang: Int, val abstand: Double, val tag: String?)

    /** Der Plan für den Zieltag (Wochenende: Freitag) – Regeln wie in der iOS-App */
    fun laden(link: String, kuerzel: String, passwort: String = "", ziel: String = zielTag(Zeit.heute())): VertretungsPlan {
        val (host, token) = zerlege(link) ?: throw java.io.IOException("Ungültiger Ordner-Link")
        val auth = "Basic " + Base64.getEncoder().encodeToString("$token:$passwort".toByteArray())
        val propfind = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getlastmodified/></d:prop></d:propfind>""".toByteArray()
        var basis = "$host/public.php/dav/files/$token/"
        var a = Netz.anfrage(basis, "PROPFIND", mapOf("Authorization" to auth, "Depth" to "1"), propfind)
        if (a.status != 207) {
            basis = "$host/public.php/webdav/"
            a = Netz.anfrage(basis, "PROPFIND", mapOf("Authorization" to auth, "Depth" to "1"), propfind)
        }
        if (a.status == 401) throw java.io.IOException("Passwort für den Ordner nötig")
        if (a.status != 207) throw java.io.IOException("Ordner nicht erreichbar (HTTP ${a.status})")
        val reihe = reihenfolge(wordDateien(a.text), ziel)
        var beste: Pair<VertretungsPlan, List<Double>>? = null
        for (k in reihe.take(4)) {
            if (beste != null && k.rang > beste.second[0]) break
            val url = basis + k.name.split("/").joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val d = runCatching { Netz.anfrage(url, kopf = mapOf("Authorization" to auth), zeit = 30_000) }.getOrNull() ?: continue
            if (d.status != 200) continue
            val xml = dokument(d.bytes) ?: continue
            val p = auswerten(xml, kuerzel)
            p.datei = k.name
            k.tag?.let { p.datum = it }
            val wert = listOf(k.rang.toDouble(), k.abstand, -p.belegt.toDouble(), -k.zeit.toDouble())
            if (beste == null || vergleiche(wert, beste.second) < 0) beste = p to wert
        }
        return beste?.first ?: throw java.io.IOException("Kein lesbarer Vertretungsplan im Ordner")
    }

    private fun vergleiche(a: List<Double>, b: List<Double>): Int { for (i in a.indices) { val c = a[i].compareTo(b[i]); if (c != 0) return c }; return 0 }

    /** Wochentag im Dateinamen (1 = Montag … 5 = Freitag) oder 0 */
    fun nameWochentag(name: String): Int = listOf("montag", "dienstag", "mittwoch", "donnerstag", "freitag").indexOfFirst { name.lowercase().contains(it) } + 1

    fun tagDerWoche(ziel: String, wt: Int): String? = Zeit.tag(ziel)?.let { d -> d.plusDays((wt - d.dayOfWeek.value).toLong()).toString() }

    /** rang 0: Datum im Namen = Ziel, 1: Wochentag im Namen = Ziel, 2: Rest nach Abstand; innerhalb zuerst die neueste */
    fun reihenfolge(dateien: List<Pair<String, Long>>, ziel: String): List<Kandidat> =
        dateien.map { (name, zeit) ->
            val ausName = nameDatum(name, zeit)
            val wt = nameWochentag(name)
            val woche = if (wt > 0) tagDerWoche(ziel, wt) else null
            when {
                ausName == ziel -> Kandidat(name, zeit, 0, 0.0, ausName)
                ausName == null && woche == ziel -> Kandidat(name, zeit, 1, 0.0, woche)
                else -> {
                    val tag = ausName ?: woche
                    Kandidat(name, zeit, 2, tag?.let { abstand(it, ziel) + if (it > ziel) 0.5 else 0.0 } ?: 99.0, tag)
                }
            }
        }.sortedWith(compareBy<Kandidat>({ it.rang }, { it.abstand }, { -it.zeit }))

    fun zielTag(heute: LocalDate): String = when (heute.dayOfWeek.value) {
        6 -> heute.minusDays(1); 7 -> heute.minusDays(2); else -> heute
    }.toString()

    fun abstand(a: String, b: String): Int {
        val x = Zeit.tag(a) ?: return 999; val y = Zeit.tag(b) ?: return 999
        return kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(x, y)).toInt()
    }

    /** Datum nur, wenn es im Dateinamen steht („3. Mittwoch 411“ = 4.11., „Freitag_09.10.“) */
    fun nameDatum(name: String, zeitMs: Long): String? {
        var n = name.replace(Regex("(?i)\\.docx"), "")
        n = n.replace(Regex("""^\s*\d\.\s*"""), "")
        var t = 0; var m = 0
        val r1 = Regex("""\d{1,2}\.\d{1,2}""").find(n)
        if (r1 != null) { val z = r1.value.split("."); t = z[0].toInt(); m = z[1].toInt() }
        else Regex("""(?<![\d.])\d{3,4}(?![\d.])""").find(n)?.let { val z = it.value; t = z.dropLast(2).toIntOrNull() ?: 0; m = z.takeLast(2).toIntOrNull() ?: 0 }
        if (t !in 1..31 || m !in 1..12) return null
        val bezug = java.time.Instant.ofEpochMilli(zeitMs).atZone(Zeit.zone).toLocalDate()
        return listOf(bezug.year - 1, bezug.year, bezug.year + 1).mapNotNull { j -> runCatching { LocalDate.of(j, m, t) }.getOrNull() }
            .minByOrNull { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(it, bezug)) }?.toString()
    }

    /** PROPFIND-Antwort → Word-Dateien mit Änderungszeit (ms), neueste zuerst */
    fun wordDateien(xml: String): List<Pair<String, Long>> {
        val f = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
        return treffer("""<d:response>([\s\S]*?)</d:response>""", xml).mapNotNull { r ->
            val href = treffer("""<d:href>([^<]*)</d:href>""", r).firstOrNull() ?: return@mapNotNull null
            val z = treffer("""<d:getlastmodified>([^<]*)</d:getlastmodified>""", r).firstOrNull() ?: return@mapNotNull null
            val zeit = runCatching { ZonedDateTime.parse(z, f).toInstant().toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            val name = java.net.URLDecoder.decode(href.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
            if (!name.lowercase().endsWith(".docx") || name.startsWith("~$")) null else name to zeit
        }.sortedByDescending { it.second }
    }

    /** word/document.xml aus der .docx */
    fun dokument(b: ByteArray): String? = try {
        ZipInputStream(b.inputStream()).use { z ->
            var e = z.nextEntry
            var erg: String? = null
            while (e != null) {
                if (e.name == "word/document.xml") { erg = String(z.readBytes(), Charsets.UTF_8); break }
                e = z.nextEntry
            }
            erg
        }
    } catch (_: Exception) { null }

    class Zelle(val text: String, val xml: String, val von: Int, val bis: Int) { val mitte get() = (von + bis) / 2.0 }

    fun treffer(muster: String, s: String, gruppe: Int = 1): List<String> =
        Regex(muster).findAll(s).map { m -> if (gruppe < m.groupValues.size) m.groupValues[gruppe] else m.value }.toList()

    fun text(xml: String): String {
        var s = xml.replace("</w:p>", " ").replace("<w:tab/>", " ").replace("<w:br/>", " ")
        s = s.replace(Regex("<[^>]+>"), "")
        for ((a, b) in listOf("&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'")) s = s.replace(a, b)
        return s.replace(Regex("\\s+"), " ").trim()
    }

    fun zeilen(xml: String): List<List<Zelle>> = treffer("""<w:tr[ >][\s\S]*?</w:tr>""", xml, 0).map { tr ->
        var pos = 0
        treffer("""<w:tc>[\s\S]*?</w:tc>""", tr, 0).map { tc ->
            val b = treffer("""<w:tcW w:w="(\d+)"""", tc).firstOrNull()?.toIntOrNull() ?: 0
            Zelle(text(tc), tc, pos, pos + b).also { pos += b }
        }
    }

    /** Kommt das Kürzel als eigenes Wort vor? „Wal+4“ ja, „Walter“ nein */
    fun hat(text: String, k: String): Boolean = text.lowercase().split(Regex("[^\\p{L}]+")).contains(k.lowercase())

    fun kuerzelListe(t: String): List<String> = t.split(Regex("[,;/]")).flatMap { teil ->
        val w = teil.trim().split(" ").filter { it.isNotEmpty() }
        if (w.all { it.length <= 8 && it.all(Char::isLetter) }) w else listOf(w.joinToString(" "))
    }.filter { it.isNotEmpty() }

    fun auswerten(xml: String, kuerzel: String): VertretungsPlan {
        val p = VertretungsPlan()
        Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})""").find(text(xml))?.let { m ->
            p.datum = "%04d-%02d-%02d".format(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        val alle = zeilen(xml)
        val k = kuerzel.trim()
        val kopfI = alle.indexOfFirst { it.firstOrNull()?.text?.lowercase() == "klasse" }
        if (kopfI < 0) return p
        val kopf = alle[kopfI].drop(1).filter { it.text.isNotEmpty() }
        var pausenSpalten: List<Zelle>? = null
        for ((i, z) in alle.withIndex()) {
            val titel = z.firstOrNull()?.text ?: continue
            val t = titel.lowercase()
            if (t.startsWith("es fehlen")) {
                val s = z.drop(1).joinToString(" ") { it.text }
                p.fehlen = kuerzelListe(s); if (k.isNotEmpty() && hat(s, k)) p.fehlt = true; continue
            }
            if (t.startsWith("außer haus") || t.startsWith("ausser haus")) {
                p.ausserListe = kuerzelListe(z.drop(1).joinToString(" ") { it.text })
                if (k.isNotEmpty() && z.drop(1).any { hat(it.text, k) }) p.ausserHaus = true; continue
            }
            if (t.startsWith("wichtig") && z.size > 1) {
                val absaetze = treffer("""<w:p[ >][\s\S]*?</w:p>""", z[1].xml, 0).map { text(it) }.filter { it.isNotEmpty() }
                p.alleHinweise += absaetze
                if (k.isNotEmpty()) { p.hinweise += absaetze.filter { hat(it, k) }; p.bemerkungen = p.bemerkungen + Bemerkungen.lesen(absaetze, k) }
            }
            val pausen = z.filter { Regex("""^\d\.\s*[Pp]ause$""").matches(it.text) }
            if (pausen.isNotEmpty()) { pausenSpalten = pausen; continue }
            val sp = pausenSpalten
            if (sp != null) {
                val ort = z.lastOrNull { it.bis <= sp[0].von + 5 && it.text.isNotEmpty() }?.text ?: ""
                // alle Kürzel dieser Zeile merken (für die Stundenpläne der anderen)
                for (s in sp) {
                    val c = z.firstOrNull { it.mitte >= s.von && it.mitte < s.bis && it.text.isNotEmpty() } ?: continue
                    val name = s.text.replace(Regex("\\s+"), " ")
                    for (teil in c.text.split(',', ';', '/', '+', ' ')) {
                        val kz = teil.trim { !it.isLetter() }
                        if (kz.length !in 2..5 || !kz.all(Char::isLetter)) continue
                        p.alleAufsichten.getOrPut(kz.lowercase()) { mutableListOf() }.add(VertretungsPlan.Pause(name, ort, teil.contains("(")))
                    }
                }
                if (k.isEmpty()) continue
                for (s in sp) {
                    val c = z.firstOrNull { it.mitte >= s.von && it.mitte < s.bis } ?: continue
                    if (!hat(c.text, k)) continue
                    val name = s.text.replace(Regex("\\s+"), " ")
                    val reserve = Regex("""\(\s*""" + Regex.escape(k) + """\s*\)""", RegexOption.IGNORE_CASE).containsMatchIn(c.text)
                    p.pausen.add(VertretungsPlan.Pause(name, ort, reserve))
                }
                continue
            }
            if (i <= kopfI || titel.isEmpty() || t.startsWith("wichtig")) continue
            p.belegt += z.drop(1).count { it.text.isNotEmpty() }
            val geteilt = z.drop(1).filter { it.text == "A" }.flatMap { c -> kopf.filter { it.von < c.bis && it.bis > c.von }.map { it.text } }
            if (geteilt.isNotEmpty()) {
                val nr = titel.trim('.', ' ')
                val liste = p.aufteilung.getOrPut(nr) { mutableListOf() }
                geteilt.forEach { if (it !in liste) liste.add(it) }
            }
            if (k.isEmpty()) continue
            for (c in z.drop(1)) {
                if (c.text.isEmpty() || !hat(c.text, k)) continue
                val ueber = kopf.filter { it.von < c.bis && it.bis > c.von }
                // Zeile über alle Klassen (Frühbetreuung Mensa) → keine Klasse
                val klasse = if (ueber.size >= 4 && ueber.size > kopf.size / 2) "" else if (ueber.size > 1) "${ueber.first().text}–${ueber.last().text}" else (kopf.firstOrNull { c.mitte >= it.von && c.mitte < it.bis }?.text ?: "")
                // Zeile „B“ (Betreuung) bzw. „Maxi“ = 6. Stunde
                val st = if (t == "b" || t.startsWith("maxi")) "6" else titel
                if (p.stunden.none { it.stunde == st && it.klasse == klasse }) p.stunden.add(VertretungsPlan.Stunde(st, klasse))
            }
        }
        return p
    }
}
