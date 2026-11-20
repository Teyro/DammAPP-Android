@file:kotlinx.serialization.UseSerializers(LDTSerializer::class, LDSerializer::class)

package de.oejendorferdamm.dammapp.kern

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@kotlinx.serialization.Serializable

data class WetterTag(val code: Int, val max: Double, val min: Double, val regen: Int, val wind: Double)

/** Ein 15-Minuten-Schritt (Open-Meteo minutely_15); [regen] = Niederschlag der vorigen 15 Minuten in mm */
@kotlinx.serialization.Serializable
data class WetterSchritt(val zeit: LocalDateTime, val code: Int, val temp: Double?, val regen: Double, val chance: Int?, val boeen: Double?)

/** Wetter in einer Hofpause: Symbol, Temperatur und ob es vermutlich eine Regenpause (drinnen) wird */
@kotlinx.serialization.Serializable
data class PausenWetter(val code: Int, val temp: Double?, val regen: Double, val chance: Int, val boeen: Double, val drinnen: Boolean, val text: String)

@kotlinx.serialization.Serializable

data class Wetter(val jetzt: Double?, val jetztCode: Int?, val heute: WetterTag?, val morgen: WetterTag?, val stand: LocalDateTime, val verlauf: List<WetterSchritt> = emptyList()) {
    companion object {
        val beschreibung = mapOf(
            0 to "klar", 1 to "überwiegend klar", 2 to "teils bewölkt", 3 to "bedeckt", 45 to "neblig", 48 to "neblig mit Reif",
            51 to "leichter Nieselregen", 53 to "Nieselregen", 55 to "starker Nieselregen", 56 to "gefrierender Nieselregen", 57 to "gefrierender Nieselregen",
            61 to "leichter Regen", 63 to "Regen", 65 to "starker Regen", 66 to "gefrierender Regen", 67 to "gefrierender Regen",
            71 to "leichter Schneefall", 73 to "Schneefall", 75 to "starker Schneefall", 77 to "Schneegriesel",
            80 to "Regenschauer", 81 to "Regenschauer", 82 to "heftige Regenschauer", 85 to "Schneeschauer", 86 to "Schneeschauer",
            95 to "Gewitter", 96 to "Gewitter mit Hagel", 99 to "Gewitter mit Hagel",
        )

        /** Open-Meteo (ohne Schlüssel) */
        fun laden(lat: Double, lon: Double): Wetter {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(java.util.Locale.US, lat, lon) +
                "&current=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_speed_10m_max" +
                "&minutely_15=temperature_2m,precipitation,weather_code,wind_gusts_10m&hourly=precipitation_probability" +
                "&timezone=Europe%2FBerlin&forecast_days=2"
            return lesen(Netz.json(url) as? JSONObject ?: JSONObject())
        }

        fun lesen(o: JSONObject, jetzt: LocalDateTime = Zeit.jetzt()): Wetter {
            val c = o.optJSONObject("current") ?: JSONObject()
            val d = o.optJSONObject("daily") ?: JSONObject()
            fun tag(i: Int): WetterTag? {
                fun zahl(k: String): Double? = d.optJSONArray(k)?.let { if (i < it.length() && !it.isNull(i)) it.optDouble(i) else null }
                val code = zahl("weather_code") ?: return null
                val mx = zahl("temperature_2m_max") ?: return null
                val mn = zahl("temperature_2m_min") ?: return null
                return WetterTag(code.toInt(), mx, mn, (zahl("precipitation_probability_max") ?: 0.0).toInt(), zahl("wind_speed_10m_max") ?: 0.0)
            }
            return Wetter(if (c.has("temperature_2m")) c.optDouble("temperature_2m") else null, if (c.has("weather_code")) c.optInt("weather_code") else null,
                tag(0), tag(1), jetzt, verlauf(o))
        }

        fun verlauf(o: JSONObject): List<WetterSchritt> {
            val m = o.optJSONObject("minutely_15") ?: return emptyList()
            val zeiten = m.optJSONArray("time") ?: return emptyList()
            val f = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
            fun reihe(q: JSONObject, k: String): List<Double?> = q.optJSONArray(k)?.let { a -> (0 until a.length()).map { if (a.isNull(it)) null else a.optDouble(it) } } ?: emptyList()
            val h = o.optJSONObject("hourly") ?: JSONObject()
            val chance = HashMap<String, Int>()
            val hz = h.optJSONArray("time"); val hp = reihe(h, "precipitation_probability")
            if (hz != null) for (i in 0 until hz.length()) hp.getOrNull(i)?.let { chance[hz.getString(i).take(13)] = it.toInt() }
            val code = reihe(m, "weather_code"); val temp = reihe(m, "temperature_2m"); val regen = reihe(m, "precipitation"); val boeen = reihe(m, "wind_gusts_10m")
            return (0 until zeiten.length()).mapNotNull { i ->
                val z = zeiten.optString(i)
                val t = runCatching { LocalDateTime.parse(z, f) }.getOrNull() ?: return@mapNotNull null
                val cc = code.getOrNull(i) ?: return@mapNotNull null
                WetterSchritt(t, cc.toInt(), temp.getOrNull(i), regen.getOrNull(i) ?: 0.0, chance[z.take(13)], boeen.getOrNull(i))
            }
        }

        /** Wie schlimm ist ein Wettercode für eine Hofpause? */
        fun schwere(c: Int) = when (c) {
            in 95..99 -> 9; 65, 67, 82 -> 8; 61, 63, 66, 80, 81 -> 7; in 71..77, 85, 86 -> 6; in 51..57 -> 5; 45, 48 -> 3; 3 -> 2; 2 -> 1; else -> 0
        }
        fun schnee(t: WetterTag) = t.code in 71..77 || t.code == 85 || t.code == 86
        fun glaette(t: WetterTag) = t.code in listOf(56, 57, 66, 67) || schnee(t) || (t.min <= 0.5 && t.regen >= 40)
        fun hinweise(t: WetterTag, auto: Boolean): List<String> = buildList {
            if (glaette(t)) add("Vorsicht, glatt")
            if (t.regen >= 50 && !schnee(t)) add("Schirm mitnehmen")
            if (auto && t.min <= 0) add("Scheibe kratzen")
            if (t.max <= 3) add("Warm anziehen")
            if (t.wind >= 50) add("Sturm")
        }
    }

    /** Wetter in einer Pause [von, bis) aus dem 15-Minuten-Verlauf (Regeln wie in der iOS-App) */
    fun pause(von: LocalDateTime, bis: LocalDateTime): PausenWetter? {
        fun ueberlappung(x: WetterSchritt): Long {
            val a = maxOf(x.zeit.minusMinutes(15), von); val b = minOf(x.zeit, bis)
            return java.time.Duration.between(a, b).seconds
        }
        val alle = verlauf.filter { ueberlappung(it) > 0 }
        val haelfte = alle.filter { ueberlappung(it) >= 450 }
        val s = haelfte.ifEmpty { listOfNotNull(alle.maxByOrNull { ueberlappung(it) }) }
        val code = s.map { it.code }.maxByOrNull { schwere(it) } ?: return null
        val regen = s.sumOf { it.regen }
        val chance = s.mapNotNull { it.chance }.maxOrNull() ?: 0
        val boeen = s.mapNotNull { it.boeen }.maxOrNull() ?: 0.0
        val temps = s.mapNotNull { it.temp }
        val temp = if (temps.isEmpty()) null else temps.average()
        val gewitter = code in 95..99
        val schnee = code in 71..77 || code == 85 || code == 86
        val nass = code in 51..67 || code in 80..82
        val sturm = boeen >= 70
        val drinnen = gewitter || sturm || (if (schnee) regen >= 1.5 else (regen >= 0.3 || (nass && chance >= 60)))
        val text = when {
            gewitter -> "Gewitter – Regenpause"
            sturm -> "Sturm – Regenpause"
            drinnen -> if (schnee) "starker Schneefall – Regenpause" else "Regenpause"
            schnee -> "Schnee"
            nass -> "vielleicht Nieselregen"
            else -> "trocken"
        }
        return PausenWetter(code, temp, regen, chance, boeen, drinnen, text)
    }

    /** „Draußen sind es 4 Grad, bedeckt. Heute 2 bis 7 Grad, …“ */
    fun satz(istMorgen: Boolean = false): String {
        val t = (if (istMorgen) morgen else heute) ?: return ""
        var s = ""
        if (!istMorgen && jetzt != null) s += "Draußen sind es ${Math.round(jetzt)} Grad, ${beschreibung[jetztCode ?: t.code] ?: "wechselhaft"}. "
        s += (if (istMorgen) "Morgen " else "Heute ") + "${Math.round(t.min)} bis ${Math.round(t.max)} Grad"
        s += if (t.regen >= 60) ", Regen wahrscheinlich (${t.regen} Prozent)" else if (t.regen >= 30) ", vielleicht Regen" else ", es bleibt trocken"
        if (t.min <= 1) s += ". Vorsicht, es kann glatt sein"
        if (t.wind >= 50) s += ". Es wird stürmisch"
        return "$s."
    }
}
