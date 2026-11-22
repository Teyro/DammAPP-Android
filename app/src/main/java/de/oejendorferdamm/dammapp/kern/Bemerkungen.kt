package de.oejendorferdamm.dammapp.kern

/**
 * Eine Bemerkung aus der „Wichtig!“-Zeile des Vertretungsplans, die das eigene Kürzel betrifft.
 * Schreibweisen aus echten Plänen (Namen geändert):
 *  „FSJ Ella 2c krank-> Begleitung Kim: 1. Abc, 2.-4. Xyz, 5. Abc“ → Xyz: 2.–4. „Begleitung Kim“ (2c)
 *  „Kim: 1.Xyz, 2.,3.Def, 4.,5.Xyz“ (unter „Begleitungen:“)          → Xyz: 1., 4., 5.
 *  „Xyz 2.-4. Lio (2c)“                                               → Xyz: 2.–4. „Begleitung Lio“ (2c)
 */
@kotlinx.serialization.Serializable
data class Bemerkung(val stunden: List<Int>, val was: String, val klasse: String = "", val zeit: String = "", val text: String = "") {
    val titel get() = listOf(was, klasse).filter { it.isNotEmpty() }.joinToString(" · ")
}

object Bemerkungen {
    /** „1.-3.“, „4.“, „4. Std“, „1.–4. Std.“ */
    private val STUNDE = Regex("""(?<!\d)([0-6])\.\s*(?:[-–]\s*([0-6])\.)?\s*(?:Std\.?|Stunde)?""")
    private const val KL = """(?:VSK\s?[a-c1-3]?|[1-4][a-d])"""
    private val KLASSE = Regex("""(?<![\p{L}\d])($KL)(?![\p{L}\d])""")
    private val ZEIT = Regex("""(\d{1,2}[:.]\d{2})\s*[-–]\s*(\d{1,2}[:.]\d{2})""")
    /** „Begleitung Kim:“, „Begleitungen: Kim:“, „->Kim:“ oder „Kim (2c):“ am Absatzanfang */
    private val WER = Regex("""(?:Schul)?[Bb]egleitung(?:en)?\s*:?\s*([A-ZÄÖÜ][\p{L}-]+)(?:\s*\(?\s*$KL\s*\)?)?\s*:?|(?:^|->|>|/|;)\s*([A-ZÄÖÜ][\p{L}-]+)(?:\s*\(?\s*$KL\s*\)?)?\s*:""")
    private val KEIN_NAME = setOf("wichtig", "achtung", "hinweis", "info", "begleitung", "begleitungen", "fsj", "sb", "vertretung")

    /** „1. Abc, 2.-4. Xyz, 4.,5.Def“ → {abc: [1], xyz: [2, 3, 4], def: [4, 5]} */
    fun zuteilung(liste: String): Map<String, List<Int>> {
        val t = liste.replace(ZEIT, " ").replace(Regex("""\d{1,2}\s*Uhr|\d{2,}"""), " ").replace(KLASSE, " ")
        val aus = LinkedHashMap<String, MutableSet<Int>>()
        val nums = sortedSetOf<Int>(); var vor: Int? = null; var bis = false
        for (m in Regex("""\d|[-–]|[\p{L}?]+""").findAll(t)) {
            val w = m.value
            when {
                w[0].isDigit() -> { val d = w.toInt(); if (bis && vor != null) (minOf(vor, d)..maxOf(vor, d)).forEach { nums += it } else nums += d; vor = d; bis = false }
                w == "-" || w == "–" -> bis = vor != null
                w.lowercase() in setOf("std", "stunde", "stunden", "und", "fsj", "sb") -> {}
                nums.isEmpty() -> {}
                else -> { aus.getOrPut(w.lowercase()) { sortedSetOf() } += nums; nums.clear(); vor = null; bis = false }
            }
        }
        return aus.mapValues { it.value.toList() }
    }

    /** Begleitungen im Absatz, an denen das Kürzel beteiligt ist */
    private fun begleitungen(p: String, k: String): List<Bemerkung> {
        val aus = ArrayList<Bemerkung>()
        // „Xyz 2.-4. Lio (2c)“ – Kürzel vorn, dann Stunden und das Kind
        Regex("""(?:^|;)\s*(?i:${Regex.escape(k)})\s*:?\s*((?:\d\.?\s*[-–+,]?\s*(?:und\s*)?)+)(?:Std\.?\s*)?([A-ZÄÖÜ][\p{L}-]+)(?:\s*\(?\s*($KL)\s*\)?)?""").findAll(p).forEach { m ->
            val st = zuteilung(m.groupValues[1] + " x")["x"].orEmpty()
            if (st.isNotEmpty() && m.groupValues[2].lowercase() !in KEIN_NAME) aus += Bemerkung(st, "Begleitung " + m.groupValues[2], m.groupValues[3], "", p)
        }
        val wer = WER.findAll(p).filter { m -> m.groupValues[1].ifEmpty { m.groupValues[2] }.let { it.lowercase() !in KEIN_NAME && !it.equals(k, true) } }.toList()
        for ((i, m) in wer.withIndex()) {
            val ende = minOf(wer.getOrNull(i + 1)?.range?.first ?: p.length, p.indexOf(';', m.range.last + 1).takeIf { it >= 0 } ?: p.length)
            val st = zuteilung(p.substring(m.range.last + 1, maxOf(ende, m.range.last + 1)))[k.lowercase()] ?: continue
            // Klasse: direkt beim Namen, sonst die letzte davor im selben Satzteil („FSJ Ella 2c krank-> Begleitung Kim“)
            val klasse = KLASSE.find(m.value)?.value ?: KLASSE.findAll(p.substring(p.lastIndexOf(';', m.range.first) + 1, m.range.first)).lastOrNull()?.value.orEmpty()
            aus += Bemerkung(st, "Begleitung " + m.groupValues[1].ifEmpty { m.groupValues[2] }, klasse.replace(" ", ""), "", p)
        }
        return aus
    }

    fun lesen(absaetze: List<String>, k: String): List<Bemerkung> {
        if (k.isEmpty()) return emptyList()
        val aus = ArrayList<Bemerkung>()
        val kRe = Regex("(?i)(?<![\\p{L}])" + Regex.escape(k) + "(?![\\p{L}])")
        for (absatz in absaetze.filter { Vertretung.hat(it, k) }) {
            val b = begleitungen(absatz, k)
            if (b.isNotEmpty()) { aus += b; continue }
            // sonst: freie Bemerkung („Xyz 3. Std Elterngespräch“, „Beratung 8:00-10:00 Xyz“)
            val klasse = KLASSE.find(absatz)?.groupValues?.get(1).orEmpty()
            val zeit = ZEIT.find(absatz)?.value?.replace("-", "–")?.replace(" ", "").orEmpty()
            for (teil in absatz.split(',', ';').filter { Vertretung.hat(it, k) }) {
                val stunden = STUNDE.findAll(teil).flatMap { m ->
                    val a = m.groupValues[1].toInt(); val e = m.groupValues[2].toIntOrNull() ?: a
                    (minOf(a, e)..maxOf(a, e)).asSequence()
                }.toSortedSet().toList()
                var was = kRe.find(teil)?.let { r -> teil.substring(r.range.last + 1).replace(STUNDE, " ").trim { it.isWhitespace() || !it.isLetterOrDigit() } }
                    ?.takeIf { it.isNotEmpty() && it.length <= 40 && !it.contains("krank") }.orEmpty()
                if (was.isEmpty()) was = teil.replace(STUNDE, " ").replace(kRe, " ").replace(Regex("\\s+"), " ").trim { it.isWhitespace() || !it.isLetterOrDigit() }.let { if (it.length > 50) it.take(50) + "…" else it }
                aus.add(Bemerkung(stunden, was, klasse, if (stunden.isEmpty()) zeit else "", absatz))
            }
        }
        return aus
    }
}
