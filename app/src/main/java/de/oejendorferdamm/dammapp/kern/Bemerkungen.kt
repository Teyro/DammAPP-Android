package de.oejendorferdamm.dammapp.kern

/**
 * Eine Bemerkung aus der „Wichtig!“-Zeile des Vertretungsplans, die das eigene Kürzel betrifft –
 * z. B. „FSJ Ella (Paul) 2c krank Begleitung Kim 1.-3. Xyz, 4. Def“ → Xyz: 1.–3. Stunde „Begleitung Kim“ (2c).
 */
@kotlinx.serialization.Serializable
data class Bemerkung(val stunden: List<Int>, val was: String, val klasse: String = "", val zeit: String = "", val text: String = "") {
    val titel get() = listOf(was, klasse).filter { it.isNotEmpty() }.joinToString(" · ")
}

object Bemerkungen {
    /** „1.-3.“, „4.“, „4. Std“, „1.–4. Std.“ */
    private val STUNDE = Regex("""(?<!\d)([0-6])\.\s*(?:[-–]\s*([0-6])\.)?\s*(?:Std\.?|Stunde)?""")

    fun lesen(absaetze: List<String>, k: String): List<Bemerkung> {
        if (k.isEmpty()) return emptyList()
        val aus = ArrayList<Bemerkung>()
        val kRe = Regex("(?i)(?<![\\p{L}])" + Regex.escape(k) + "(?![\\p{L}])")
        for (absatz in absaetze.filter { Vertretung.hat(it, k) }) {
            val klasse = Regex("""(?<![\w])(VSK\s?[a-c1-3]?|[1-4][a-d])(?![\w])""").find(absatz)?.groupValues?.get(1).orEmpty()
            val begleitung = Regex("""(Begleitung|begleitet|Betreuung|Schulbegleitung)\s+([A-ZÄÖÜ][\p{L}-]+)""").find(absatz)?.value.orEmpty()
            val zeit = Regex("""(\d{1,2}[:.]\d{2})\s*[-–]\s*(\d{1,2}[:.]\d{2})""").find(absatz)?.value?.replace("-", "–")?.replace(" ", "").orEmpty()
            for (teil in absatz.split(',', ';').filter { Vertretung.hat(it, k) }) {
                val stunden = STUNDE.findAll(teil).flatMap { m ->
                    val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toIntOrNull() ?: a
                    (minOf(a, b)..maxOf(a, b)).asSequence()
                }.toSortedSet().toList()
                var was = kRe.find(teil)?.let { r -> teil.substring(r.range.last + 1).replace(STUNDE, " ").trim { it.isWhitespace() || !it.isLetterOrDigit() } }
                    ?.takeIf { it.isNotEmpty() && it.length <= 40 && !it.contains("krank") }.orEmpty()
                if (was.isEmpty()) was = begleitung
                if (was.isEmpty()) was = teil.replace(STUNDE, " ").replace(kRe, " ").replace(Regex("\\s+"), " ").trim { it.isWhitespace() || !it.isLetterOrDigit() }.let { if (it.length > 50) it.take(50) + "…" else it }
                aus.add(Bemerkung(stunden, was, klasse, if (stunden.isEmpty()) zeit else "", absatz))
            }
        }
        return aus
    }
}
