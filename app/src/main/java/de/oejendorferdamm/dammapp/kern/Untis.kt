@file:kotlinx.serialization.UseSerializers(LDTSerializer::class, LDSerializer::class)

package de.oejendorferdamm.dammapp.kern

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/** Eine Stunde aus WebUntis */
@kotlinx.serialization.Serializable
data class UntisStunde(val datum: String, val von: Int, val bis: Int, val fach: String, val fachLang: String = "", val klassen: List<String>,
                       val lehrer: List<String>, val lehrerIds: List<Int> = emptyList(), val raum: String = "", val code: String = "") {
    val entfaellt get() = code == "cancelled"
    /** Förderkreis/Förderunterricht (am Fachnamen erkannt) */
    val foerder: Boolean get() {
        val t = "$fach $fachLang".lowercase()
        return t.contains("förder") || t.contains("foerder") || fach.lowercase() in listOf("fö", "foe", "fk", "fök", "fö-k")
    }
}

@kotlinx.serialization.Serializable

data class UntisStand(val meine: List<UntisStunde>, val klassen: Map<String, List<UntisStunde>>, val ich: Int, val kuerzel: String, val stand: LocalDateTime = Zeit.jetzt())

/** Eine Klasse aus meiner Sicht */
data class KlassenInfo(val klasse: String, val faecher: List<Fach>, val stunden: Int, val foerder: Int, val doppel: List<String>, val andere: List<Kollege>) {
    data class Fach(val kurz: String, val lang: String, val stunden: Int)
    data class Kollege(val kuerzel: String, val faecher: List<String>, val stunden: Int)
}

/** TOTP (RFC 6238, SHA-1, 30 s, 6 Stellen) – für IServ und WebUntis */
object Totp {
    fun base32(s: String): ByteArray {
        val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = java.io.ByteArrayOutputStream()
        var puffer = 0; var bits = 0
        for (c in s.uppercase()) {
            val i = abc.indexOf(c); if (i < 0) continue
            puffer = (puffer shl 5) or i; bits += 5
            if (bits >= 8) { out.write((puffer shr (bits - 8)) and 255); bits -= 8 }
        }
        return out.toByteArray()
    }
    fun code(geheim: String, zeitMs: Long = System.currentTimeMillis()): String {
        val schluessel = base32(geheim.replace(" ", ""))
        if (schluessel.isEmpty()) return ""
        var zaehler = zeitMs / 1000 / 30
        val daten = ByteArray(8) { i -> (zaehler shr (8 * (7 - i)) and 0xff).toByte() }
        val mac = javax.crypto.Mac.getInstance("HmacSHA1").apply { init(javax.crypto.spec.SecretKeySpec(schluessel, "HmacSHA1")) }.doFinal(daten)
        val o = mac.last().toInt() and 0x0f
        val z = ((mac[o].toInt() and 0x7f) shl 24) or ((mac[o + 1].toInt() and 0xff) shl 16) or ((mac[o + 2].toInt() and 0xff) shl 8) or (mac[o + 3].toInt() and 0xff)
        return "%06d".format(z % 1_000_000)
    }
}

object Untis {
    class Fehler(text: String) : Exception(text)

    private val keineSchule = setOf("webuntis", "mese", "nessa", "neilo", "arche", "ajax", "erato", "kephiso", "melpomene", "poly", "peleus", "terpsichore", "thalia", "tipo", "urania", "herakles", "hepta", "asopo", "cissa")

    /** Url („hh5586.webuntis.com“, auch mit https:// oder ganzer Anmelde-Adresse) und Schule – fehlt die Schule, steckt sie meist im Servernamen */
    fun server(url: String, schule: String): Pair<String, String>? {
        zerlege(url)?.let { return it.first to schule.trim().ifEmpty { it.second } }
        var t = url.trim().lowercase()
        for (p in listOf("https://", "http://")) if (t.startsWith(p)) t = t.removePrefix(p)
        val host = t.split('/', '?', '#').firstOrNull() ?: ""
        if (!host.contains(".") || host.contains(" ")) return null
        var s = schule.trim()
        if (s.isEmpty() && host.endsWith(".webuntis.com")) { val erst = host.substringBefore("."); if (erst !in keineSchule) s = erst }
        if (s.isEmpty()) return null
        return "https://$host" to s
    }

    /** „https://server.webuntis.com/WebUntis/?school=xyz#/basic/login“ → (https://server.webuntis.com, xyz) */
    fun zerlege(link: String): Pair<String, String>? {
        val l = link.trim()
        val u = runCatching { java.net.URI(l.substringBefore("#")) }.getOrNull() ?: return null
        if (u.scheme != "https" || u.host == null) return null
        val schule = Regex("[?&]school=([^&#]+)").find(l)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: return null
        if (schule.isEmpty()) return null
        return "https://" + u.host + (if (u.port > 0) ":${u.port}" else "") to schule
    }

    fun iso(zahl: Int) = "%04d-%02d-%02d".format(zahl / 10000, zahl / 100 % 100, zahl % 100)
    fun minuten(hhmm: Int) = hhmm / 100 * 60 + hhmm % 100

    /** Ergebnis von getTimetable lesen (ohne Netz, testbar) */
    fun stunden(ergebnis: Any?): List<UntisStunde> {
        val a = ergebnis as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            fun namen(feld: String, original: Boolean = false): List<String> {
                val l = o.optJSONArray(feld) ?: return emptyList()
                return (0 until l.length()).mapNotNull { j -> l.optJSONObject(j)?.let { e ->
                    (if (original) e.optString("orgname").ifEmpty { null } else null) ?: e.optString("name").ifEmpty { null } } }
            }
            if (!o.has("date") || !o.has("startTime") || !o.has("endTime")) return@mapNotNull null
            val su = o.optJSONArray("su")?.optJSONObject(0)
            val te = o.optJSONArray("te")
            UntisStunde(iso(o.getInt("date")), minuten(o.getInt("startTime")), minuten(o.getInt("endTime")),
                su?.optString("name") ?: "", su?.optString("longname")?.ifEmpty { null } ?: su?.optString("longName") ?: "",
                namen("kl"), namen("te", true),
                te?.let { l -> (0 until l.length()).mapNotNull { j -> l.optJSONObject(j)?.let { e -> if (e.has("orgid")) e.optInt("orgid") else if (e.has("id")) e.optInt("id") else null } } } ?: emptyList(),
                namen("ro").joinToString(", "), o.optString("code"))
        }.sortedWith(compareBy({ it.datum }, { it.von }))
    }

    /** Je Wochentag der erste Tag mit regulärem Unterricht – so stören Ferien- und Projekttage nicht */
    fun grundwoche(s: List<UntisStunde>): List<UntisStunde> {
        val regulaer = s.filter { it.code != "irregular" }
        val tage = HashMap<Int, String>()
        for (x in regulaer) { val d = Zeit.tag(x.datum) ?: continue; val wt = d.dayOfWeek.value; if (tage[wt] == null || x.datum < tage[wt]!!) tage[wt] = x.datum }
        val gewaehlt = tage.values.toSet()
        return regulaer.filter { it.datum in gewaehlt }
    }

    fun klassen(st: UntisStand): List<KlassenInfo> {
        val meine = grundwoche(st.meine)
        fun ichBin(s: UntisStunde, i: Int) = (i < s.lehrerIds.size && s.lehrerIds[i] == st.ich) || (st.kuerzel.isNotEmpty() && i < s.lehrer.size && s.lehrer[i].equals(st.kuerzel, true))
        return meine.flatMap { it.klassen }.toSortedSet().map { k ->
            val hier = meine.filter { k in it.klassen }
            val faecher = LinkedHashMap<String, KlassenInfo.Fach>()
            for (s in hier) if (!s.foerder) faecher[s.fach] = (faecher[s.fach] ?: KlassenInfo.Fach(s.fach, s.fachLang, 0)).let { it.copy(stunden = it.stunden + 1) }
            val doppel = hier.flatMap { s -> s.lehrer.indices.filter { !ichBin(s, it) }.map { s.lehrer[it] } }.toSortedSet().toList()
            val andere = LinkedHashMap<String, KlassenInfo.Kollege>()
            for (s in grundwoche(st.klassen[k] ?: emptyList())) for (i in s.lehrer.indices) {
                if (ichBin(s, i)) continue
                val alt = andere[s.lehrer[i]] ?: KlassenInfo.Kollege(s.lehrer[i], emptyList(), 0)
                andere[s.lehrer[i]] = alt.copy(stunden = alt.stunden + 1, faecher = if (s.fach.isNotEmpty() && s.fach !in alt.faecher) alt.faecher + s.fach else alt.faecher)
            }
            KlassenInfo(k, faecher.values.sortedWith(compareByDescending<KlassenInfo.Fach> { it.stunden }.thenBy { it.kurz }), hier.size, hier.count { it.foerder }, doppel,
                andere.values.sortedWith(compareByDescending<KlassenInfo.Kollege> { it.stunden }.thenBy { it.kuerzel }))
        }
    }

    /** Grundwoche als eigener Stundenplan (Tag 1 = Montag) */
    fun unterricht(st: UntisStand, zeiten: List<Schulzeit>): List<Unterricht> {
        val liste = ArrayList<Unterricht>()
        for (s in grundwoche(st.meine)) {
            val wt = Zeit.tag(s.datum)?.dayOfWeek?.value ?: continue
            for (z in zeiten) {
                if (z.pause || z.von >= s.bis || z.bis <= s.von) continue
                if (liste.any { it.tag == wt && it.stunde == z.name }) continue
                liste.add(Unterricht(wt, z.name, s.klassen.joinToString(", "), s.fachLang.ifEmpty { s.fach }, s.raum))
            }
        }
        return liste
    }

    data class Qr(val basis: String, val schule: String, val benutzer: String, val schluessel: String)

    /** QR-Code aus WebUntis (Freigaben → Untis Mobile): untis://setschool?url=…&school=…&user=…&key=… */
    fun qr(text: String): Qr? {
        val t = text.trim()
        if (!t.lowercase().startsWith("untis://")) return null
        val q = t.substringAfter("?", "").split("&").mapNotNull { p -> p.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].lowercase() to java.net.URLDecoder.decode(it[1], "UTF-8") } }.toMap()
        var host = q["url"] ?: ""
        val schule = q["school"] ?: ""
        if (host.isEmpty() || schule.isEmpty()) return null
        if (!host.startsWith("https://")) host = "https://" + host.removePrefix("http://")
        host = host.trimEnd('/')
        if (host.lowercase().endsWith("/webuntis")) host = host.dropLast(9)
        return Qr(host, schule, q["user"] ?: "", q["key"] ?: "")
    }

    internal class Sitzung(val basis: String, val schule: String) {
        val url = basis + "/WebUntis/jsonrpc.do?school=" + java.net.URLEncoder.encode(schule, "UTF-8")
        var nr = 0
        var cookie = ""

        fun anmeldenMitSchluessel(benutzer: String, schluessel: String): Pair<Int, Int> {
            val u = basis + "/WebUntis/jsonrpc_intern.do?m=getUserData2017&school=" + java.net.URLEncoder.encode(schule, "UTF-8") + "&v=i2.2"
            val jetzt = System.currentTimeMillis()
            val auth = JSONObject().put("clientTime", jetzt).put("user", benutzer).put("otp", Totp.code(schluessel, jetzt))
            val e = senden(u, JSONObject().put("id", "0").put("method", "getUserData2017").put("params", JSONArray().put(JSONObject().put("auth", auth))).put("jsonrpc", "2.0")) as? JSONObject ?: JSONObject()
            val d = e.optJSONObject("userData") ?: JSONObject()
            val typ = when (d.optString("elemType").uppercase()) { "TEACHER" -> 2; "STUDENT" -> 5; "CLASS" -> 1; else -> 0 }
            return typ to d.optInt("elemId")
        }

        fun rufe(methode: String, params: JSONObject = JSONObject()): Any? {
            nr++
            return senden(url, JSONObject().put("id", "$nr").put("method", methode).put("params", params).put("jsonrpc", "2.0"))
        }

        fun senden(adresse: String, koerper: JSONObject): Any? {
            val kopf = mutableMapOf("Content-Type" to "application/json")
            if (cookie.isNotEmpty()) kopf["Cookie"] = cookie
            val c = java.net.URL(adresse).openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 30_000; c.readTimeout = 30_000; c.doOutput = true
            c.setRequestProperty("User-Agent", "DammAPP"); kopf.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(koerper.toString().toByteArray()) }
            val status = c.responseCode
            c.headerFields["Set-Cookie"]?.forEach { s -> if (s.startsWith("JSESSIONID=")) cookie = s.substringBefore(";") }
            val text = (if (status >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes()) } ?: ""
            c.disconnect()
            if (status == 404) throw Fehler("WebUntis nicht gefunden – Link prüfen.")
            val o = if (status in 200..299) runCatching { JSONObject(text) }.getOrNull() else null
            o ?: throw Fehler("WebUntis antwortet nicht wie erwartet ($status).")
            o.optJSONObject("error")?.let { f ->
                val code = f.optInt("code")
                throw Fehler(when (code) {
                    -8504 -> "WebUntis: Benutzername oder Passwort falsch. Mit zweitem Faktor bitte den Schlüssel aus „Untis Mobile“ verwenden."
                    -8998, -8997 -> "WebUntis: Der Schlüssel passt nicht (oder die Uhr des Geräts geht falsch)."
                    -8500 -> "WebUntis: Schule nicht gefunden – Link prüfen."
                    -8509, -8510 -> "Dieses WebUntis-Konto darf den Stundenplan nicht abrufen."
                    else -> "WebUntis meldet: ${f.optString("message", "Fehler")} ($code)"
                })
            }
            if (!o.has("result")) throw Fehler("Leere Antwort von WebUntis.")
            return o.get("result")
        }
    }

    internal fun zahl(d: LocalDate) = d.year * 10000 + d.monthValue * 100 + d.dayOfMonth

    /** Eigene Stunden (diese und nächste Woche) und die Pläne meiner Klassen laden */
    fun laden(link: String, schule: String = "", benutzer: String, passwort: String, schluessel: String = "", kuerzel: String, jetzt: LocalDate = Zeit.heute()): UntisStand {
        val q = qr(link)
        val z = q?.let { it.basis to it.schule } ?: server(link, schule)
            ?: throw Fehler("Bitte Url (z. B. hh5586.webuntis.com) und Schule (z. B. hh5586) eintragen – beides steht in WebUntis unter Freigaben → Untis Mobile.")
        val s = Sitzung(z.first, z.second)
        val nutzer = q?.benutzer?.ifEmpty { null } ?: benutzer.trim()
        val geheim = q?.schluessel?.ifEmpty { null } ?: schluessel
        var typ: Int; var ich: Int
        if (geheim.isNotEmpty()) { val r = s.anmeldenMitSchluessel(nutzer, geheim); typ = r.first; ich = r.second }
        else {
            if (passwort.isEmpty()) throw Fehler("Bitte Passwort oder – bei zweitem Faktor – den Schlüssel aus „Untis Mobile“ eintragen.")
            val a = s.rufe("authenticate", JSONObject().put("user", nutzer).put("password", passwort).put("client", "DammAPP")) as? JSONObject ?: JSONObject()
            typ = a.optInt("personType"); ich = a.optInt("personId")
        }
        try {
            if (typ != 2) {
                val lehrer = s.rufe("getTeachers") as? JSONArray ?: JSONArray()
                val t = (0 until lehrer.length()).mapNotNull { lehrer.optJSONObject(it) }.firstOrNull { it.optString("name").equals(kuerzel, true) }
                    ?: throw Fehler("Das WebUntis-Konto ist keinem Lehrer zugeordnet – bitte das eigene Kürzel bei „Vertretung“ eintragen.")
                typ = 2; ich = t.optInt("id")
            }
            var montag = jetzt.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            if (Zeit.wochenende(jetzt)) montag = montag.plusDays(7)
            val bis = montag.plusDays(11)
            fun plan(t: Int, id: Int): List<UntisStunde> {
                val felder = JSONArray().put("id").put("name").put("longname")
                val o = JSONObject().put("element", JSONObject().put("id", id).put("type", t)).put("startDate", zahl(montag)).put("endDate", zahl(bis))
                    .put("showSubstText", true).put("klasseFields", felder).put("teacherFields", felder).put("subjectFields", felder).put("roomFields", felder)
                return stunden(s.rufe("getTimetable", JSONObject().put("options", o)))
            }
            val meine = plan(typ, ich)
            if (meine.isEmpty()) throw Fehler("In WebUntis stehen für dich in diesen zwei Wochen keine Stunden.")
            val klassen = HashMap<String, List<UntisStunde>>()
            runCatching { s.rufe("getKlassen") as? JSONArray }.getOrNull()?.let { liste ->
                for (name in meine.flatMap { it.klassen }.toSet()) {
                    val id = (0 until liste.length()).mapNotNull { liste.optJSONObject(it) }.firstOrNull { it.optString("name") == name }?.optInt("id") ?: continue
                    klassen[name] = runCatching { plan(1, id) }.getOrDefault(emptyList())
                }
            }
            return UntisStand(meine, klassen, ich, kuerzel)
        } finally { runCatching { s.rufe("logout") } }
    }
}
