@file:kotlinx.serialization.UseSerializers(LDTSerializer::class, LDSerializer::class)

package de.oejendorferdamm.dammapp.kern

import java.time.LocalDateTime

/** Ein Kalendertermin vom Gerät */
@kotlinx.serialization.Serializable
data class Termin(val titel: String, val start: LocalDateTime, val ganztags: Boolean)

/** „Morgen mitnehmen“: aus den Stunden (Sport, Schwimmen) und dem Wetter */
object Mitnehmen {
    fun hinweise(bloecke: List<Block>, wetter: WetterTag?): List<String> {
        val h = ArrayList<String>()
        val text = bloecke.filter { it.art == Block.Art.UNTERRICHT || it.art == Block.Art.VERTRETUNG }.joinToString(" · ") { (it.text + " " + (it.ersetzt ?: "")).lowercase() }
        if (text.contains("schwimm")) h.add("Schwimmsachen")
        if (text.contains("sport") && "Schwimmsachen" !in h) h.add("Sportzeug")
        if (wetter != null && wetter.regen >= 50) h.add("Regenjacke")
        return h
    }
}

/** Reihenfolge der Kacheln auf der Startseite (wie iOS, ohne Wecker und Post) */
object Startseite {
    val alle = listOf("schule", "mensa", "zahlen", "schultermine", "wetter", "termine")
    fun vollstaendig(eigen: List<String>, inDerSchule: Boolean): List<String> {
        val r = eigen.filter { it in alle }.toMutableList()
        if (r.isEmpty()) return if (inDerSchule) alle else listOf("termine", "wetter", "schule", "mensa", "zahlen", "schultermine")
        alle.forEach { if (it !in r) r.add(it) }
        return r
    }
    fun ordnung(eigen: List<String>, aus: List<String>, inDerSchule: Boolean) = vollstaendig(eigen, inDerSchule).filter { it !in aus }
}

/** Wann gibt es eine Mitteilung zum Vertretungsplan? (wie iOS) */
object VertretungsMeldung {
    data class Meldung(val id: String, val titel: String, val text: String)

    fun pruefen(v: VertretungsPlan, altDatum: String?, altSig: String?, planDa: Boolean, betrifft: Boolean): Triple<Meldung?, String, String>? {
        val datum = v.datum ?: return null
        val meine = eintraege(v)
        val sig = datum + "|" + meine.sorted().joinToString(";")
        var m: Meldung? = null
        if (planDa && altDatum != null && altDatum != datum) {
            val tag = Zeit.tag(datum)?.let { Zeit.wochentag(it) } ?: datum
            m = Meldung("vertretung-plan-$datum", "Vertretungsplan für $tag ist da", if (meine.isEmpty()) "Keine Vertretung für dich." else meine.take(3).joinToString(" · "))
        } else if (betrifft && altSig != null && altSig != sig && meine.isNotEmpty()) {
            m = Meldung("vertretung-tag", "Vertretungsplan betrifft dich", meine.take(3).joinToString(" · "))
        }
        return Triple(m, datum, sig)
    }

    fun eintraege(v: VertretungsPlan): List<String> =
        v.stunden.map { (if (it.stunde.trim('.', ' ').toIntOrNull() == null) "" else "${it.stunde}. ") + VertretungsPlan.titel(it) } +
            v.pausen.map { "${if (it.reserve) "Reserve" else "Aufsicht"} ${it.pause} · ${it.ort}" } +
            (if (v.fehlt) listOf("Du bist als fehlend eingetragen") else emptyList()) +
            (if (v.ausserHaus) listOf("Du bist außer Haus eingetragen") else emptyList())
}

/** Das gesprochene Briefing (ohne Wecker und Post) */
object Briefing {
    data class Daten(val jetzt: LocalDateTime = Zeit.jetzt(), val wetter: Wetter? = null, val termine: List<Termin> = emptyList(), val vertretung: VertretungsPlan? = null,
                     val haeuser: Map<String, List<String>> = emptyMap(), val mensa: MensaTag? = null, val mensaWahl: Pair<String, String> = "" to "",
                     val gemerkt: Pair<List<String>, List<String>> = emptyList<String>() to emptyList(), val obst: Boolean = false,
                     val schulTag: java.time.LocalDate? = null, val frei: FreierTag? = null, val schultag: List<Block> = emptyList(), val mitnehmen: Boolean = false)

    fun aufsichtWetter(bloecke: List<Block>, w: Wetter): String {
        val teile = bloecke.filter { it.art == Block.Art.AUFSICHT }.mapNotNull { b ->
            val p = w.pause(b.von, b.bis) ?: return@mapNotNull null
            val name = if (b.name.startsWith("1")) "erste Pause" else if (b.name.startsWith("2")) "zweite Pause" else b.titel
            if (p.drinnen) "$name vermutlich ${p.text}" else "$name ${p.text}"
        }
        return if (teile.isEmpty()) "" else "Hofaufsicht: " + teile.joinToString(", ") + "."
    }

    private fun uhr(d: LocalDateTime) = if (d.minute == 0) "${d.hour} Uhr" else "${d.hour} Uhr ${d.minute}"

    fun morgen(d: Daten): String {
        val h = d.jetzt.hour
        val s = ArrayList<String>()
        s.add("${if (h < 11) "Guten Morgen" else if (h < 18) "Hallo" else "Guten Abend"}! Heute ist ${Zeit.datumLang(d.jetzt.toLocalDate())}, es ist ${uhr(d.jetzt)}.")
        d.frei?.let { s.add(if (it.ferien) "Es sind ${it.name}." else "Heute ist ${it.name}.") }
        d.wetter?.let { w ->
            s.add(w.satz())
            w.heute?.let { t -> Wetter.hinweise(t, false).filter { it != "Vorsicht, glatt" }.takeIf { it.isNotEmpty() }?.let { s.add(Stundenplan.aufzaehlen(it) + ".") } }
        }
        val heute = d.termine.filter { it.start.toLocalDate() == d.jetzt.toLocalDate() }
        val ganz = heute.filter { it.ganztags }.map { it.titel }
        val zeit = heute.filter { !it.ganztags && it.start.isAfter(d.jetzt.minusMinutes(30)) }.sortedBy { it.start }
        if (ganz.isEmpty() && zeit.isEmpty()) s.add("Im Kalender steht heute nichts.") else {
            val teile = ganz + zeit.take(6).map { "um ${uhr(it.start)} ${it.titel}" }
            s.add((if (teile.size == 1) "Heute steht an: " else "Heute stehen an: ") + Stundenplan.aufzaehlen(teile) + ".")
        }
        val tag = d.schulTag ?: d.jetzt.toLocalDate()
        val schuleHeute = tag == d.jetzt.toLocalDate()
        val schultag = if (schuleHeute) d.frei == null && !Zeit.wochenende(tag) else true
        val wann = if (schuleHeute) "" else if (tag == d.jetzt.toLocalDate().plusDays(1)) "Morgen" else "Am " + Zeit.wochentag(tag)
        val schule = ArrayList<String>()
        if (schultag && d.schultag.isNotEmpty()) {
            d.vertretung?.takeIf { it.fehlt || it.ausserHaus }?.let { schule.add(if (it.fehlt) "Du bist als fehlend eingetragen." else "Du bist außer Haus eingetragen.") }
            schule.add(Stundenplan.satz(d.schultag))
            d.wetter?.let { aufsichtWetter(d.schultag, it).takeIf { x -> x.isNotEmpty() }?.let(schule::add) }
            if (d.mitnehmen) Mitnehmen.hinweise(d.schultag, if (schuleHeute) d.wetter?.heute else d.wetter?.morgen).takeIf { it.isNotEmpty() }
                ?.let { schule.add("Mitnehmen: " + it.joinToString(", ") + ".") }
        } else d.vertretung?.takeIf { schultag && (it.datum == null || it.datum == tag.toString()) }?.let { schule.add(it.satz(d.haeuser)) }
        if (schultag && d.obst) schule.add(if (schuleHeute) "Heute kommt Schulobst." else "Es kommt Schulobst.")
        d.mensa?.takeIf { schultag && it.datum == tag.toString() }?.let { m ->
            if (m.frei != null) schule.add("Die Mensa hat geschlossen.")
            else m.haupt(d.mensaWahl.first, d.mensaWahl.second, d.gemerkt.first, d.gemerkt.second)?.let { g -> schule.add(if (g.bestellt) "In der Mensa gibt es für dich ${g.text}." else "In der Mensa gibt es ${g.text}.") }
        }
        if (schule.isNotEmpty()) s.add((if (wann.isEmpty()) "" else "$wann: ") + schule.joinToString(" "))
        s.add(if (h < 18) "Einen schönen Tag!" else "Einen schönen Abend!")
        return s.filter { it.isNotEmpty() }.joinToString(" ")
    }
}
