@file:kotlinx.serialization.UseSerializers(LDTSerializer::class, LDSerializer::class)

package de.oejendorferdamm.dammapp.kern

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** Ein Abschnitt des Schultags laut Klingelzeiten: eine Stunde („1“ … „6“) oder eine Pause („1. Pause“) */
@kotlinx.serialization.Serializable
data class Schulzeit(val name: String, val von: Int, val bis: Int, val pause: Boolean = false) {
    companion object {
        /** Raster der Schule am Öjendorfer Damm: 8:30–13:30, Pausen 10:00 und 11:55 */
        val standard = listOf(
            Schulzeit("1", 510, 555), Schulzeit("2", 555, 600), Schulzeit("1. Pause", 600, 625, true),
            Schulzeit("3", 625, 670), Schulzeit("4", 670, 715), Schulzeit("2. Pause", 715, 740, true),
            Schulzeit("5", 740, 785), Schulzeit("6", 785, 810),
        )
    }
}

/** Eine eigene Stunde im Wochenplan; [tag] 1 = Montag … 5 = Freitag */
@kotlinx.serialization.Serializable
data class Unterricht(val tag: Int, val stunde: String, val klasse: String, val fach: String = "", val raum: String = "", val id: String = UUID.randomUUID().toString()) {
    /** „5a Mathe · R12“ */
    val text get() = listOf(klasse, fach).filter { it.isNotEmpty() }.joinToString(" ") + if (raum.isEmpty()) "" else " · $raum"
}

/** Ein Eintrag im Tagesablauf */
@kotlinx.serialization.Serializable
data class Block(val name: String, val von: LocalDateTime, val bis: LocalDateTime, val art: Art, val text: String,
                 val ersetzt: String? = null, val aufteilung: List<String>? = null) {
    enum class Art { UNTERRICHT, VERTRETUNG, AUFSICHT, PAUSE, FREI }
    val meins get() = art == Art.UNTERRICHT || art == Art.VERTRETUNG || art == Art.AUFSICHT
    val zeigen get() = meins || ersetzt != null
    /** „3.“ bzw. „1.P“ */
    val marke get() = if (name.toIntOrNull() != null) "$name." else name.replace("Pause", "P").replace(" ", "")
    val titel get() = if (name.toIntOrNull() != null) "$name. Stunde" else name
}

object Stundenplan {
    private fun zeit(tag: LocalDate, minuten: Int) = tag.atTime(minuten / 60, minuten % 60)

    /** Zeile im Vertretungsplan → Name im Raster: „3.“ → „3“, „Frühbetreuung Mensa“ → „0“ */
    fun stunde(s: String): String { val t = s.trim('.', ' '); return if (t.toIntOrNull() != null) t else if (t.lowercase().startsWith("früh")) "0" else t }

    /** „1. Pause“ ↔ „Pause 1“, „1.P“ – die Nummer zählt */
    fun pausenNummer(s: String): Int? = if (!s.lowercase().contains("p")) null else s.firstOrNull { it.isDigit() }?.digitToInt()

    /** Der Tag in Abschnitten (Regeln wie in der iOS-App); [ausfall]: ausgefallene Stunden (Minuten von–bis) laut WebUntis */
    fun tag(datum: LocalDate, zeiten: List<Schulzeit>, unterricht: List<Unterricht>, vertretung: VertretungsPlan?, haeuser: Map<String, List<String>> = emptyMap(),
            ausfall: List<IntRange> = emptyList(), ganz: Boolean = false): List<Block> {
        val wt = datum.dayOfWeek.value
        val v = vertretung?.takeIf { it.datum == null || it.datum == datum.toString() }
        val bloecke = ArrayList<Block>()
        // 0. Stunde (8:00–8:30, Frühförderung/Frühbetreuung) gehört immer zum Raster
        for (z in UntisRaster.raster(zeiten).sortedBy { it.von }) {
            val von = zeit(datum, z.von); val bis = zeit(datum, z.bis)
            if (z.pause) {
                val ps = v?.pausen?.filter { pausenNummer(it.pause) == pausenNummer(z.name) } ?: emptyList()
                if (ps.isNotEmpty()) {
                    val text = (if (ps.all { it.reserve }) "Reserve " else "Aufsicht ") + ps.joinToString(" + ") { it.ort }
                    bloecke.add(Block(z.name, von, bis, Block.Art.AUFSICHT, text))
                } else bloecke.add(Block(z.name, von, bis, Block.Art.PAUSE, "Pause"))
                continue
            }
            val s = v?.stunden?.firstOrNull { stunde(it.stunde) == z.name }
            val u = unterricht.firstOrNull { it.tag == wt && it.stunde == z.name }
            when {
                s != null -> {
                    val h = Vertretung.haus(s.klasse, haeuser)
                    bloecke.add(Block(z.name, von, bis, Block.Art.VERTRETUNG, VertretungsPlan.titel(s) + if (h.isEmpty()) "" else " · $h", ersetzt = u?.text))
                }
                u != null -> bloecke.add(
                    if (ausfall.any { it.first < z.bis && it.last > z.von }) Block(z.name, von, bis, Block.Art.FREI, "Entfall", ersetzt = u.text)
                    else Block(z.name, von, bis, Block.Art.UNTERRICHT, u.text))
                else -> bloecke.add(Block(z.name, von, bis, Block.Art.FREI, "frei"))
            }
        }
        // Begleitungen aus der „Wichtig“-Zeile („Begleitung Nelson … 3.-5. Sta“): eigene Stunde durchgestrichen, darunter „2c → Nelson“
        for (bem in v?.bemerkungen.orEmpty().filter { it.was.startsWith("Begleitung ") }) {
            val wer = bem.was.removePrefix("Begleitung ")
            for (n in bem.stunden) {
                val i = bloecke.indexOfFirst { it.name == n.toString() }
                if (i < 0 || (bloecke[i].art != Block.Art.UNTERRICHT && bloecke[i].art != Block.Art.FREI)) continue
                val alt = if (bloecke[i].art == Block.Art.UNTERRICHT) bloecke[i].text else bloecke[i].ersetzt
                bloecke[i] = bloecke[i].copy(art = Block.Art.VERTRETUNG, text = if (bem.klasse.isEmpty()) bem.was else "${bem.klasse} → $wer", ersetzt = alt)
            }
        }
        // 0. Stunde nur, wenn dort etwas ist
        if (bloecke.firstOrNull()?.let { it.name == "0" && !it.zeigen } == true) bloecke.removeAt(0)
        val mitAufteilung = bloecke.map { b -> v?.aufteilung?.get(b.name)?.takeIf { it.isNotEmpty() }?.let { b.copy(aufteilung = it) } ?: b }
        val erst = mitAufteilung.indexOfFirst { it.zeigen }
        val letzt = mitAufteilung.indexOfLast { it.zeigen }
        if (erst < 0) return emptyList()
        return if (ganz) mitAufteilung else mitAufteilung.subList(erst, letzt + 1)
    }

    fun beginn(bloecke: List<Block>) = bloecke.firstOrNull { it.meins }?.von

    /** Was gerade läuft und was danach kommt */
    fun jetzt(bloecke: List<Block>, zeit: LocalDateTime = Zeit.jetzt()): Pair<Block?, Block?> {
        val laeuft = bloecke.firstOrNull { !it.von.isAfter(zeit) && zeit.isBefore(it.bis) }
        val ab = laeuft?.bis ?: zeit
        val danach = bloecke.firstOrNull { !it.von.isBefore(ab) && it.art != Block.Art.FREI }
        return laeuft to danach
    }

    fun wechsel(bloecke: List<Block>) = bloecke.flatMap { listOf(it.von, it.bis) }.distinct().sorted()

    /** Gesprochen: „Deine Stunden: 1. 5a Mathe in R12, …“ */
    fun satz(bloecke: List<Block>): String {
        val meine = bloecke.filter { it.zeigen }
        if (meine.isEmpty()) return ""
        val teile = meine.map { b ->
            when {
                b.art == Block.Art.AUFSICHT -> "${if (b.text.startsWith("Reserve")) "Reserve" else "Aufsicht"} in der ${b.name}: ${b.text.replace("Aufsicht ", "").replace("Reserve ", "")}"
                b.art == Block.Art.FREI -> "${b.name}. ${b.ersetzt?.replace(" · ", " in ") ?: "Stunde"} fällt aus"
                b.art == Block.Art.VERTRETUNG && b.ersetzt != null -> "${b.name}. statt ${b.ersetzt.replace(" · ", " in ")} ${b.text.replace(" · ", " in ")}"
                else -> "${b.name}. ${b.text.replace(" · ", " in ")}"
            }
        }
        return "Deine Stunden: " + aufzaehlen(teile) + "."
    }

    /** „a, b und c“ */
    fun aufzaehlen(t: List<String>): String = when (t.size) {
        0 -> ""; 1 -> t[0]; else -> t.dropLast(1).joinToString(", ") + " und " + t.last()
    }
}
