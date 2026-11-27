@file:kotlinx.serialization.UseSerializers(LDTSerializer::class, LDSerializer::class)

package de.oejendorferdamm.dammapp.kern

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/** Eine Aufgabe (Karte in deiner Farbe) aus Edupool Boards */
@kotlinx.serialization.Serializable
data class Aufgabe(val id: String, val titel: String, val beschreibung: String, val inhalt: List<Block>, val geaendert: LocalDateTime?,
                   val board: String, val boardTitel: String, val spalte: String, val kategorie: Int, val sort: Double, val kommentarAnzahl: Int? = null) {
    @kotlinx.serialization.Serializable
    data class Block(val typ: String, val text: String = "", val titel: String = "", val url: String = "")
}

@kotlinx.serialization.Serializable

data class AufgabenStand(val aufgaben: List<Aufgabe>, val boards: Int, val stand: LocalDateTime = Zeit.jetzt(), val farben: List<String> = emptyList()) {
    val offen get() = aufgaben.filter { !Aufgaben.kategorien[it.kategorie].erledigt }
    fun gruppen(wichtigZuerst: Boolean = false): List<Pair<Int, List<Aufgabe>>> {
        val reihe = Aufgaben.kategorien.indices.toMutableList()
        if (wichtigZuerst) reihe.sortBy { Aufgaben.kategorien[it].rang }
        return reihe.mapNotNull { i ->
            val a = aufgaben.filter { it.kategorie == i }.sortedWith(compareBy({ it.board }, { it.sort }, { it.titel }))
            if (a.isEmpty()) null else i to a
        }
    }
}

/** Edupool Boards (boards.edupool.cloud): Freigabe per Geräte-Code, dann Karten in deiner Farbe; Kommentare lesen und beantworten */
object Aufgaben {
    const val BASIS = "https://boards.edupool.cloud"
    const val MAX_BOARDS = 12

    data class Kategorie(val name: String, val kurz: String, val muster: String, val rang: Int, val erledigt: Boolean)
    val kategorien = listOf(
        Kategorie("Ideen Backlog", "Ideen", "ideen|backlog|ideas?", 3, false),
        Kategorie("Zu erledigen", "To-do", "zu ?erledigen|zu ?tun|to ?do|offen", 1, false),
        Kategorie("In Bearbeitung", "Läuft", "bearbeitung|in ?arbeit|doing|in ?progress|laufend", 0, false),
        Kategorie("Wartet auf Feedback", "Wartet", "wartet|warten|feedback|rueckmeldung|rückmeldung|review", 2, false),
        Kategorie("Erledigt", "Erledigt", "erledigt|fertig|done|abgeschlossen|geschafft", 4, true),
    )

    fun kategorie(spalte: String): Int {
        val t = spalte.lowercase().replace(Regex("[^a-zäöüß]+"), " ").trim()
        return kategorien.indexOfFirst { Regex(it.muster).containsMatchIn(t) }
    }

    class Fehler(t: String) : Exception(t)

    data class Freigabe(val deviceCode: String, val userCode: String, val seite: String, val intervall: Long, val ablaufMs: Long)

    fun freigabeStarten(): Freigabe {
        val (d, s) = anfrage("/api/auth/cli/device", "POST", JSONObject(), null)
        val o = d as? JSONObject ?: throw Fehler("Edupool Boards hat keinen Freigabe-Code geliefert (HTTP $s).")
        val dc = o.optString("device_code"); val uc = o.optString("user_code")
        if (dc.isEmpty() || uc.isEmpty()) throw Fehler("Edupool Boards hat keinen Freigabe-Code geliefert (HTTP $s).")
        val seite = o.optString("verification_uri").ifEmpty { "$BASIS/cli/authorize" } + "?user_code=" + java.net.URLEncoder.encode(uc, "UTF-8")
        return Freigabe(dc, uc, seite, maxOf(2L, o.optLong("interval", 5)), System.currentTimeMillis() + o.optLong("expires_in", 600) * 1000)
    }

    /** Einmal nachfragen: Token, null = noch nicht bestätigt */
    fun freigabeAbholen(f: Freigabe): String? {
        val (d, s) = anfrage("/api/auth/cli/device/token", "POST", JSONObject().put("device_code", f.deviceCode), null)
        val o = d as? JSONObject ?: JSONObject()
        if (o.has("token")) return o.get("token").toString()
        val fehler = o.optString("error")
        if (fehler == "authorization_pending" || fehler == "slow_down") return null
        if (fehler == "expired") throw Fehler("Der Code ist abgelaufen – bitte noch einmal verbinden.")
        if (fehler == "access_denied" || fehler == "denied") throw Fehler("Die Freigabe wurde abgelehnt.")
        throw Fehler("Freigabe nicht erhalten: ${fehler.ifEmpty { "HTTP $s" }}")
    }

    fun laden(token: String, farbe: String = "#76a5af"): AufgabenStand {
        val (bd, _) = anfrage("/api/boards", token = token)
        val boards = liste(bd).mapNotNull { b -> if (!b.has("id")) null else Triple(b.get("id").toString(), b.optString("title").ifEmpty { b.optString("name").ifEmpty { "Board ${b.get("id")}" } }, b.optString("layout_type")) }
            .filter { it.third == "kanban" }
        val ziel = farbNorm(farbe)   // leer = keine Farbe gewählt → alle Karten
        val aufgaben = ArrayList<Aufgabe>()
        val farben = sortedSetOf<String>()
        for ((id, titel, _) in boards.take(MAX_BOARDS)) {
            val spalten = liste(anfrage("/api/sections/board/$id", token = token).first).associate { it.opt("id").toString() to it.optString("title") }
            for (k in liste(anfrage("/api/cards/board/$id", token = token).first)) {
                val f = farbNorm(k.optString("color"))
                if (f.isNotEmpty()) farben.add("#$f")
                if (ziel.isNotEmpty() && f != ziel) continue
                val spalte = spalten[k.opt("section_id")?.toString() ?: ""] ?: ""
                val kat = kategorie(spalte)
                if (kat < 0 || !k.has("id")) continue
                val inhalt = inhaltAus(k)
                val (t, b) = textAus(k, inhalt)
                val anzahl = listOf("visible_comment_count", "comment_count", "comments_count").firstOrNull { k.has(it) }?.let { k.optInt(it) }
                aufgaben.add(Aufgabe(k.get("id").toString(), t, b, inhalt, zeit(k.opt("updated_at") ?: k.opt("updatedAt") ?: k.opt("created_at")),
                    id, titel, spalte, kat, k.optDouble("sort_index", 0.0), anzahl))
            }
        }
        return AufgabenStand(aufgaben, boards.size, farben = farben.toList())
    }

    /** Wer welche Farbe hat – aus dem Board „Medien“: Mitglieder mit Farbe, sonst die Farbe der Karten, denen jemand zugewiesen ist */
    fun personenFarben(token: String, titel: String = "Medien"): Map<String, String> = runCatching {
        val b = liste(anfrage("/api/boards", token = token).first).firstOrNull { it.optString("title").ifEmpty { it.optString("name") }.contains(titel, true) } ?: return emptyMap()
        val id = b.opt("id")?.toString() ?: return emptyMap()
        val m = LinkedHashMap<String, String>()
        fun nimm(personen: Any?, farbe: String? = null) {
            val a = personen as? org.json.JSONArray ?: return
            for (i in 0 until a.length()) {
                val p = a.optJSONObject(i) ?: continue
                val u = p.optJSONObject("user") ?: p
                val n = listOf("display_name", "name", "username").map { u.optString(it) }.firstOrNull { it.isNotEmpty() } ?: continue
                val f = farbNorm(p.optString("color").ifEmpty { p.optString("colour").ifEmpty { u.optString("color").ifEmpty { farbe ?: "" } } })
                if (f.isNotEmpty()) m.putIfAbsent(n, "#$f")
            }
        }
        nimm(b.opt("members") ?: b.opt("users"))
        (anfrage("/api/boards/$id", token = token).first as? JSONObject)?.let { o -> nimm(o.opt("members") ?: o.opt("users") ?: o.opt("participants")) }
        for (k in liste(anfrage("/api/cards/board/$id", token = token).first)) {
            // Karte je Person: Titel = Name („Christian S.“, „Vivi“), Kartenfarbe = Farbe der Person
            val f = farbNorm(k.optString("color")); val t = k.optString("title").trim()
            if (f.isNotEmpty() && t.length <= 25 && Regex("""^\p{Lu}[\p{L}.\- ]*$""").matches(t) && t.split(" ").size <= 3) m[t] = "#$f"
            nimm(k.opt("assignees") ?: k.opt("assigned_users") ?: k.opt("members"), k.optString("color"))
        }
        m
    }.getOrDefault(emptyMap())

    /** Farbe nach Vorname (+ Anfang des Nachnamens): „Christian Stake Admin“ → „Christian S.“, „Viviane Haist“ → „Vivi“ */
    fun farbeFuer(name: String, farben: Map<String, String>): String? {
        fun woerter(s: String) = s.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() && it != "admin" }
        val n = woerter(name); val vn = n.firstOrNull() ?: return null
        var best: Pair<Int, String>? = null
        for ((k, f) in farben.toSortedMap()) {
            val t = woerter(k); val vk = t.firstOrNull() ?: continue
            if (vk.length < 3 || !(vn.startsWith(vk) || vk.startsWith(vn))) continue
            var punkte = 1
            if (t.size > 1) {
                // mit Nachname(-Anfang): muss passen (Christian N. ≠ Christian S.)
                if (n.size < 2 || !(n[1].startsWith(t[1]) || t[1].startsWith(n[1]))) continue
                punkte = 2 + t[1].length
            }
            if (punkte > (best?.first ?: 0)) best = punkte to f
        }
        // Rückfall: voller Nachname („Herr Kurz“ → „Ben Kurz“)
        return best?.second ?: farben.entries.firstOrNull { (k, _) -> woerter(k).let { t -> t.size > 1 && t.last().length >= 3 && t.last() in n } }?.value
    }

    // MARK: Kommentare

    data class Kommentar(val id: String, val autor: String, val text: String, val zeit: LocalDateTime?)

    fun kommentare(token: String, karte: String): List<Kommentar> {
        val (d, s) = anfrage("/api/cards/$karte/comments", token = token)
        if (s !in 200..299) throw Fehler("Kommentare nicht geladen (HTTP $s).")
        return kommentareAus(d)
    }

    fun antworten(token: String, karte: String, text: String) {
        val (_, s) = anfrage("/api/cards/$karte/comments", "POST", JSONObject().put("content", text), token, schreiben = true)
        if (s == 403) throw Fehler("Zum Antworten bitte neu verbinden und dabei auch „Boards und Inhalte erstellen, ändern“ anhaken.")
        if (s !in 200..299) throw Fehler("Antwort nicht gesendet (HTTP $s).")
    }

    fun kommentareAus(d: Any?): List<Kommentar> = liste(d).mapNotNull { k ->
        val text = reinerText(k.optString("content").ifEmpty { k.optString("text").ifEmpty { k.optString("body") } })
        if (text.isEmpty()) return@mapNotNull null
        val u = k.optJSONObject("user") ?: k.optJSONObject("author") ?: JSONObject()
        val name = listOf(u.optString("display_name"), u.optString("name"), u.optString("username"), k.optString("author_name"), k.optString("user_name"), k.optString("display_name"))
            .firstOrNull { it.isNotEmpty() } ?: ""
        Kommentar(k.opt("id")?.toString() ?: java.util.UUID.randomUUID().toString(), name, text, zeit(k.opt("created_at") ?: k.opt("createdAt")))
    }.sortedBy { it.zeit ?: LocalDateTime.MIN }

    // MARK: Hilfen

    fun anfrage(pfad: String, methode: String = "GET", koerper: JSONObject? = null, token: String?, schreiben: Boolean = false): Pair<Any?, Int> {
        val kopf = HashMap<String, String>()
        kopf["Accept"] = "application/json"
        if (koerper != null) kopf["Content-Type"] = "application/json"
        if (token != null) kopf["Authorization"] = "Bearer $token"
        val a = Netz.anfrage(BASIS + pfad, methode, kopf, koerper?.toString()?.toByteArray())
        if (token != null && a.status == 401) throw Fehler("Die Freigabe ist abgelaufen oder wurde widerrufen – bitte neu verbinden.")
        if (token != null && a.status == 403 && !schreiben) throw Fehler("Die Freigabe reicht nicht – beim Verbinden „Lesen“ und alle Boards wählen.")
        return Netz.jsonAus(a.text) to a.status
    }

    /** Array direkt oder das erste Array darin ({boards: […]}, {data: […]} …) */
    fun liste(d: Any?): List<JSONObject> {
        val a = (d as? JSONArray) ?: (d as? JSONObject)?.let { o -> o.keys().asSequence().mapNotNull { o.optJSONArray(it) }.firstOrNull() } ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    fun farbNorm(c: String): String {
        var h = c.trim().lowercase().removePrefix("#")
        if (h.length == 3 && h.all { it.isLetterOrDigit() }) h = h.map { "$it$it" }.joinToString("")
        return if (h.length >= 6 && h.take(6).all { it in "0123456789abcdef" }) h.take(6) else ""
    }

    fun zeit(w: Any?): LocalDateTime? {
        if (w is Number) { val n = w.toDouble(); return java.time.Instant.ofEpochMilli((if (n > 1e12) n else n * 1000).toLong()).atZone(Zeit.zone).toLocalDateTime() }
        val s = w as? String ?: return null
        return runCatching { java.time.OffsetDateTime.parse(s).atZoneSameInstant(Zeit.zone).toLocalDateTime() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(s).atZone(Zeit.zone).toLocalDateTime() }.getOrNull()
    }

    private fun https(u: Any?): String = (u as? String)?.takeIf { it.lowercase().startsWith("http") } ?: ""

    fun inhaltAus(k: JSONObject): List<Aufgabe.Block> {
        val b = ArrayList<Aufgabe.Block>()
        val roh = k.optJSONArray("blocks")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }?.sortedBy { it.optDouble("position", 0.0) } ?: emptyList()
        for (x in roh) {
            val typ = x.optString("block_type").ifEmpty { x.optString("type").ifEmpty { "text" } }
            val d = x.optJSONObject("data") ?: JSONObject()
            fun s(n: String) = d.optString(n)
            when (typ) {
                "text" -> if (s("text").isNotBlank()) b.add(Aufgabe.Block("text", s("text").take(20000)))
                "link" -> { val u = https(d.opt("resolved_url") ?: d.opt("url")); if (u.isNotEmpty()) b.add(Aufgabe.Block("link", s("preview_description"), s("title").ifEmpty { s("description") }, u)) }
                "file" -> b.add(Aufgabe.Block("file", titel = s("filename").ifEmpty { "Datei" }))
                "image", "drawing" -> b.add(Aufgabe.Block("image", titel = s("caption").ifEmpty { s("alt_text") }, url = https(d.opt("url"))))
                "video", "audio" -> b.add(Aufgabe.Block(typ, titel = s("title").ifEmpty { s("filename") }, url = https(d.opt("url"))))
                "edupool_media" -> b.add(Aufgabe.Block("edupool_media", s("fn_kurztext").ifEmpty { s("fn_text") }.take(1000), s("title").ifEmpty { "Edupool-Medium" }))
                else -> { val t = s("text").ifEmpty { s("description").ifEmpty { s("title") } }; if (t.isNotEmpty()) b.add(Aufgabe.Block("text", t)) }
            }
        }
        for (f in listOf("content", "description", "text")) k.optString(f).takeIf { it.isNotBlank() }?.let { b.add(Aufgabe.Block("text", it)) }
        https(k.opt("link_url")).takeIf { it.isNotEmpty() }?.let { b.add(Aufgabe.Block("link", url = it)) }
        return b
    }

    fun textAus(k: JSONObject, inhalt: List<Aufgabe.Block>): Pair<String, String> {
        val texte = inhalt.filter { it.typ == "text" }.map { reinerText(it.text) }.filter { it.isNotEmpty() }.toMutableList()
        var titel = k.optString("title").trim()
        if (titel.isEmpty()) titel = if (texte.isEmpty()) inhalt.firstOrNull()?.titel ?: "Ohne Titel" else texte.removeAt(0).take(80)
        if (titel.isEmpty()) titel = "Ohne Titel"
        return titel to (texte.firstOrNull { it != titel } ?: "").take(200)
    }

    fun reinerText(s: String): String {
        var t = s.replace(Regex("""</?(b|strong|i|em|u|s|a|span|code|mark|sub|sup)\b[^>]*>""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("<[^>]+>"), " ")
        for ((a, b) in listOf("&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&amp;" to "&")) t = t.replace(a, b)
        t = t.replace(Regex("""\[([^\]]*)\]\([^)]*\)"""), "$1")
        t = t.replace(Regex("""(?m)^\s*[-*+]\s+\[[ xX]\]\s*"""), "")
        t = t.replace(Regex("""[#*_`>$]+"""), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }
}
