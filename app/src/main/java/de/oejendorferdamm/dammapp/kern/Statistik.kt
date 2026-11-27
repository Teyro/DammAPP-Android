package de.oejendorferdamm.dammapp.kern

import java.time.LocalDate

/**
 * Eigene Statistik aus den Vertretungsplänen (nur über mich, nie über das Kollegium): je Plan-Tag, was für mich drinstand.
 * Gezählt wird nur, was die App gesehen hat – ein späterer Stand desselben Tages ersetzt den früheren. (wie iOS)
 */
@kotlinx.serialization.Serializable
data class VertretungsStatistik(val tage: Map<String, Tag> = emptyMap()) {
    @kotlinx.serialization.Serializable
    data class Tag(val vertretungen: List<String>, val aufsichten: Int, val begleitungen: Int, val fehlt: Boolean, val ausserHaus: Boolean)

    data class Zahlen(val tage: Int = 0, val vertretungen: Int = 0, val aufsichten: Int = 0, val begleitungen: Int = 0, val fehlt: Int = 0,
                      val ausserHaus: Int = 0, val rotTage: Int = 0, val klasse: String? = null, val wochentag: java.time.DayOfWeek? = null)

    fun merken(p: VertretungsPlan): VertretungsStatistik {
        // leere Vorlage oder Plan ohne Datum nicht zählen
        val d = p.datum ?: return this
        if (p.belegt <= 0) return this
        val begl = p.bemerkungen.filter { it.was.startsWith("Begleitung ") }.sumOf { maxOf(1, it.stunden.size) }
        return copy(tage = tage + (d to Tag(p.stunden.map { it.klasse }, p.pausen.count { !it.reserve }, begl, p.fehlt, p.ausserHaus)))
    }

    /** Zahlen im Zeitraum [ab, bis] (ISO-Daten, je einschließlich) */
    fun zahlen(ab: String, bis: String = "9999-12-31"): Zahlen {
        val l = tage.filterKeys { it in ab..bis }
        val klassen = l.values.flatMap { it.vertretungen }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
        val wt = l.filterValues { it.vertretungen.isNotEmpty() }.entries.mapNotNull { (d, t) -> runCatching { LocalDate.parse(d).dayOfWeek }.getOrNull()?.let { it to t.vertretungen.size } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
        return Zahlen(l.size, l.values.sumOf { it.vertretungen.size }, l.values.sumOf { it.aufsichten }, l.values.sumOf { it.begleitungen },
            l.values.count { it.fehlt }, l.values.count { it.ausserHaus }, l.values.count { it.vertretungen.isNotEmpty() },
            klassen.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })?.key,
            wt.maxWithOrNull(compareBy<Map.Entry<java.time.DayOfWeek, Int>> { it.value }.thenByDescending { it.key })?.key)
    }

    companion object {
        /** Beginn des Schuljahrs (1. August) */
        fun schuljahrBeginn(d: LocalDate = LocalDate.now()) = LocalDate.of(if (d.monthValue >= 8) d.year else d.year - 1, 8, 1).toString()
        fun monatsBeginn(d: LocalDate = LocalDate.now()) = d.withDayOfMonth(1).toString()
    }
}
