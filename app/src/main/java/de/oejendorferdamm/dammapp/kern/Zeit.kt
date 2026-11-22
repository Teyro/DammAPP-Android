@file:kotlinx.serialization.UseSerializers(LDTSerializer::class, LDSerializer::class)

package de.oejendorferdamm.dammapp.kern

import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Zeiten immer in deutscher Zeit, unabhängig von der Geräteeinstellung (wie MorgenKern.Zeit) */
object Zeit {
    val zone: ZoneId = ZoneId.of("Europe/Berlin")
    val DE: Locale = Locale.GERMANY
    fun jetzt(): LocalDateTime = LocalDateTime.now(zone)
    fun heute(): LocalDate = LocalDate.now(zone)

    /** "06:15" → 375; ungültig/leer → null */
    fun minuten(s: String): Int? {
        val t = s.split(":").mapNotNull { it.trim().toIntOrNull() }
        if (t.size != 2 || t[0] !in 0..23 || t[1] !in 0..59) return null
        return t[0] * 60 + t[1]
    }
    fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    fun uhrzeit(d: LocalDateTime) = hhmm(d.hour * 60 + d.minute)
    fun minutenVon(d: LocalDateTime) = d.hour * 60 + d.minute
    fun iso(d: LocalDate): String = d.toString()
    fun tag(iso: String): LocalDate? = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull()
    val wochentage = listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")
    fun wochentag(d: LocalDate) = wochentage[d.dayOfWeek.value - 1]
    fun wochenende(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY
    fun datumLang(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", DE))
    fun datumKurz(d: LocalDateTime): String = d.format(DateTimeFormatter.ofPattern("dd.MM. HH:mm", DE))

    /** „vor 3 Stunden“, „gestern“, „vor 4 Tagen“ */
    fun relativ(d: LocalDateTime, bezug: LocalDateTime = jetzt()): String {
        val min = java.time.Duration.between(d, bezug).toMinutes()
        return when {
            min < 1 -> "gerade eben"
            min < 60 -> "vor $min Min."
            min < 24 * 60 && d.toLocalDate() == bezug.toLocalDate() -> "vor ${min / 60} Std."
            d.toLocalDate() == bezug.toLocalDate().minusDays(1) -> "gestern"
            else -> "vor ${java.time.temporal.ChronoUnit.DAYS.between(d.toLocalDate(), bezug.toLocalDate())} Tagen"
        }
    }
}

/** Ein freier Tag (Ferien oder Feiertag) – ISO-Daten von/bis */
@kotlinx.serialization.Serializable
data class FreierTag(val von: String, val bis: String, val name: String, val ferien: Boolean)

object FreieTage {
    /** Ferien und Feiertage (OpenHolidays, ohne Schlüssel) – ein gutes Jahr voraus */
    fun laden(bundesland: String, jetzt: LocalDate = Zeit.heute()): List<FreierTag> {
        val q = "countryIsoCode=DE&subdivisionCode=DE-$bundesland&languageIsoCode=DE&validFrom=${jetzt.minusDays(7)}&validTo=${jetzt.plusDays(400)}"
        val f = Netz.json("https://openholidaysapi.org/SchoolHolidays?$q")
        val t = Netz.json("https://openholidaysapi.org/PublicHolidays?$q")
        return lesen(f, t, bundesland)
    }

    fun lesen(ferien: Any?, feiertage: Any?, bundesland: String): List<FreierTag> {
        fun name(x: JSONObject): String {
            val n = x.optJSONArray("name") ?: return "frei"
            val liste = (0 until n.length()).mapNotNull { n.optJSONObject(it) }
            return (liste.firstOrNull { it.optString("language") == "DE" } ?: liste.firstOrNull())?.optString("text") ?: "frei"
        }
        val erg = ArrayList<FreierTag>()
        (ferien as? JSONArray)?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { x ->
            if (x.has("startDate") && x.has("endDate")) erg.add(FreierTag(x.getString("startDate"), x.getString("endDate"), name(x), true)) } }
        (feiertage as? JSONArray)?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { x ->
            val landweit = x.optBoolean("nationwide", true)
            val subs = x.optJSONArray("subdivisions")
            val hier = subs != null && (0 until subs.length()).any { subs.optJSONObject(it)?.optString("code") == "DE-$bundesland" }
            if ((landweit || hier) && x.has("startDate") && x.has("endDate")) erg.add(FreierTag(x.getString("startDate"), x.getString("endDate"), name(x), false))
        } }
        return erg
    }

    /** Feiertag geht vor Ferien */
    fun am(tage: List<FreierTag>, tag: LocalDate): FreierTag? {
        val t = tag.toString()
        val treffer = tage.filter { it.von <= t && t <= it.bis }
        return treffer.firstOrNull { !it.ferien } ?: treffer.firstOrNull()
    }
}

/** Welcher Schultag gezeigt wird: heute, ab 16 Uhr der nächste (ohne Wochenende und freie Tage) */
object Tagwahl {
    const val UMSCHALT_STUNDE = 16
    /** Speiseplan schaltet schon nach dem Mittag um – freitags damit auf die nächste Woche */
    const val ESSEN_STUNDE = 14
    fun ziel(jetzt: LocalDateTime = Zeit.jetzt(), frei: List<FreierTag> = emptyList(), ab: Int = UMSCHALT_STUNDE): LocalDate {
        var d = jetzt.toLocalDate()
        if (jetzt.hour >= ab) d = d.plusDays(1)
        repeat(30) {
            if (!Zeit.wochenende(d) && FreieTage.am(frei, d) == null) return d
            d = d.plusDays(1)
        }
        return d
    }
    /** „heute“, „morgen“ oder „Montag“ */
    fun name(tag: LocalDate, jetzt: LocalDate = Zeit.heute()): String = when (tag) {
        jetzt -> "heute"
        jetzt.plusDays(1) -> "morgen"
        else -> Zeit.wochentag(tag)
    }
}

/** Kleine Netzhilfe mit Zeitlimit; Antwort als Text und Status */
object Netz {
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    data class Antwort(val text: String, val status: Int, val bytes: ByteArray = ByteArray(0))

    fun anfrage(url: String, methode: String = "GET", kopf: Map<String, String> = emptyMap(), koerper: ByteArray? = null, zeit: Int = 20_000): Antwort {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = methode
        c.connectTimeout = zeit; c.readTimeout = zeit
        c.setRequestProperty("User-Agent", UA)
        kopf.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (koerper != null) { c.doOutput = true; c.outputStream.use { it.write(koerper) } }
        try {
            val s = c.responseCode
            val b = (if (s >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            return Antwort(String(b, Charsets.UTF_8), s, b)
        } finally { c.disconnect() }
    }

    /** JSON (Objekt oder Liste); Fehlerstatus → Ausnahme */
    fun json(url: String, methode: String = "GET", kopf: Map<String, String> = emptyMap(), koerper: ByteArray? = null): Any? {
        val a = anfrage(url, methode, kopf + ("Accept" to "application/json"), koerper)
        if (a.status !in 200..299) throw java.io.IOException("HTTP ${a.status}")
        return jsonAus(a.text)
    }

    fun jsonAus(t: String): Any? = t.trim().let { s -> when {
        s.startsWith("{") -> runCatching { JSONObject(s) }.getOrNull()
        s.startsWith("[") -> runCatching { JSONArray(s) }.getOrNull()
        else -> null
    } }
}
