package de.oejendorferdamm.dammapp.kern

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Untis-Sicherung: täglich zentral aus WebUntis erzeugt, liegt im Schul-Ordner („Untis/untis-plan.json“).
 * Rückfall, wenn WebUntis nicht erreichbar ist. Rangfolge überall: Vertretungsplan > von Hand > Untis.
 */
object UntisSicherung {
    const val ADRESSE = "https://cloudfiles.s5586.schuldock.de/public.php/webdav/Untis/untis-plan.json"
    /** Kennung der Freigabe „Schul-Ordner“ (das Passwort trägt jede Lehrkraft selbst ein) */
    const val FREIGABE = "mxmLySXtyzC6XFp"

    /** Aus der Nextcloud der Schule (WebDAV, Ordner „Untis“) – mit dem App-Zugang aus den Schul-Vorgaben */
    fun ladenNextcloud(server: String, benutzer: String, passwort: String): String {
        var s = server.trim().trimEnd('/'); if (!s.startsWith("http")) s = "https://$s"
        val v = URL("$s/remote.php/dav/files/" + java.net.URLEncoder.encode(benutzer, "UTF-8").replace("+", "%20") + "/Untis/untis-plan.json").openConnection() as HttpURLConnection
        v.connectTimeout = 15_000; v.readTimeout = 30_000
        v.setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString("$benutzer:$passwort".toByteArray(), android.util.Base64.NO_WRAP))
        try {
            if (v.responseCode == 401) throw java.io.IOException("Nextcloud: App-Passwort falsch.")
            if (v.responseCode != 200) throw java.io.IOException("Untis-Sicherung in der Nextcloud nicht abrufbar (HTTP ${v.responseCode}).")
            return v.inputStream.bufferedReader().readText()
        } finally { v.disconnect() }
    }

    fun laden(passwort: String): String {
        val v = URL(ADRESSE).openConnection() as HttpURLConnection
        v.connectTimeout = 15_000; v.readTimeout = 30_000
        v.setRequestProperty("Authorization", "Basic " + android.util.Base64.encodeToString("$FREIGABE:$passwort".toByteArray(), android.util.Base64.NO_WRAP))
        try {
            if (v.responseCode == 401) throw java.io.IOException("Schul-Ordner: Passwort falsch.")
            if (v.responseCode != 200) throw java.io.IOException("Untis-Sicherung nicht abrufbar (HTTP ${v.responseCode}).")
            return v.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        } finally { v.disconnect() }
    }

    /** Meine Stunden (über das Kürzel) und die Pläne meiner Klassen aus der Sicherung; null = Kürzel nicht drin */
    fun lesen(text: String, kuerzel: String): UntisStand? = runCatching {
        val o = JSONObject(text)
        val plaene = o.getJSONObject("plaene")
        val lehrer = plaene.getJSONObject("lehrer"); val klassen = plaene.getJSONObject("klassen")
        val schluessel = lehrer.keys().asSequence().firstOrNull { it.equals(kuerzel, true) } ?: return null
        val lang = o.optJSONArray("faecher")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associate { it.optString("name") to it.optString("lang") } }.orEmpty()
        fun min(t: String) = t.split(":").mapNotNull { it.toIntOrNull() }.let { if (it.size == 2) it[0] * 60 + it[1] else 0 }
        fun texte(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
        fun stunden(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { i ->
            val s = a!!.getJSONObject(i)
            val fach = texte(s.optJSONArray("faecher")).firstOrNull().orEmpty()
            UntisStunde(s.optString("datum"), min(s.optString("von")), min(s.optString("bis")), fach, lang[fach].orEmpty(), texte(s.optJSONArray("klassen")),
                texte(s.optJSONArray("lehrerVorher")).ifEmpty { texte(s.optJSONArray("lehrer")) }, emptyList(), texte(s.optJSONArray("raeume")).joinToString(", "), s.optString("code"))
        }.sortedWith(compareBy({ it.datum }, { it.von }))
        val meine = stunden(lehrer.optJSONArray(schluessel))
        if (meine.isEmpty()) return null
        val erstellt = runCatching { OffsetDateTime.parse(o.optString("erstellt")).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }.getOrDefault(LocalDateTime.now())
        UntisStand(meine, meine.flatMap { it.klassen }.toSet().associateWith { stunden(klassen.optJSONArray(it)) }, 0, schluessel, erstellt)
    }.getOrNull()

    /**
     * „Mein Stundenplan“ zusammenführen: von Hand Eingetragenes bleibt, Untis füllt freie Stunden und aktualisiert nur
     * Stunden, die noch genau so aussehen, wie Untis sie beim letzten Mal eingetragen hat ([vorher]).
     */
    fun zusammenfuehren(hand: List<Unterricht>, untis: List<Unterricht>, vorher: List<Unterricht>): List<Unterricht> {
        fun k(u: Unterricht) = "${u.tag}|${u.stunde}"
        fun gleich(a: Unterricht, b: Unterricht) = a.klasse == b.klasse && a.fach == b.fach && a.raum == b.raum
        val alt = vorher.associateBy(::k); val neu = untis.associateBy(::k)
        val ergebnis = ArrayList<Unterricht>(); val gesehen = HashSet<String>()
        for (h in hand) {
            gesehen.add(k(h))
            val a = alt[k(h)]
            if (a != null && gleich(h, a)) neu[k(h)]?.let { ergebnis.add(it.copy(id = h.id)) } else ergebnis.add(h)
        }
        untis.filter { k(it) !in gesehen }.forEach { ergebnis.add(it) }
        return ergebnis.sortedWith(compareBy({ it.tag }, { it.stunde }))
    }
}
