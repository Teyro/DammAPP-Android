package de.oejendorferdamm.dammapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.oejendorferdamm.dammapp.daten.Kern
import de.oejendorferdamm.dammapp.kern.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

@Composable
fun VertretungSeite(zurueck: () -> Unit) {
    val e = Kern.einstellungen
    val p = Kern.vertretung
    Seite("Vertretung", zurueck, untertitel = p?.datum?.let { Zeit.tag(it) }?.let { Zeit.datumLang(it) }) {
        if (p == null) { item { FehlerText(Kern.vertretungFehler.ifEmpty { "Noch kein Vertretungsplan geladen." }) }; return@Seite }
        if (p.datum != null && p.datum != Kern.schulTag.toString()) item {
            Text("⚠️ Für ${Kern.schulTagName} liegt noch kein eigener Plan im Ordner – das ist der nächstliegende.", color = Farben.aufsicht)
        }
        item {
            Karte(Icons.Rounded.Person, Farben.vertretung, "Für dich (${e.kuerzel})") {
                if (p.fehlt) Text("Du bist als fehlend eingetragen", color = Farben.fehlt, fontWeight = FontWeight.SemiBold)
                if (p.ausserHaus) Text("Du bist außer Haus eingetragen", color = Farben.aufsicht, fontWeight = FontWeight.SemiBold)
                if (p.stunden.isEmpty() && p.pausen.isEmpty() && !p.fehlt) Text("✅ Keine Vertretung, keine Aufsicht", color = Farben.gut, fontWeight = FontWeight.Medium)
                // rot = außerplanmäßig: Vertretung bzw. Begleitung, die eigene Stunde darüber rot durchgestrichen (wie im Stundenplan)
                val rot = p.datum?.let { Zeit.tag(it) }?.let { t -> Kern.schultag(t).filter { it.art == Block.Art.VERTRETUNG } }.orEmpty()
                rot.forEach { StundenZeile(it, null, false) }
                if (rot.isEmpty()) p.stunden.forEach { s ->
                    val h = Vertretung.haus(s.klasse, e.haeuser)
                    Zeile(Stundenplan.stunde(s.stunde) + ".", Farben.ausserplan, VertretungsPlan.titel(s),
                        listOf(if (s.stunde.toIntOrNull() != null) "${s.stunde}. Stunde" else s.stunde, h).filter { it.isNotEmpty() }.joinToString(" · "))
                }
                p.pausen.forEach { a -> Zeile(a.pause.take(1) + ".P", Farben.aufsicht, if (a.reserve) "Aufsicht (Reserve)" else "Hofaufsicht", "${a.pause} · ${a.ort}") }
                p.hinweise.forEach { Text("💬 $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        if (p.alleHinweise.isNotEmpty()) item {
            Karte(Icons.Rounded.Campaign, Color(0xFFF9AB00), "Wichtig (für alle)") { p.alleHinweise.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) } }
        }
        if (p.fehlen.isNotEmpty()) item { Karte(Icons.Rounded.PersonOff, Farben.fehlt, "Es fehlen (${p.fehlen.size})") { p.fehlen.forEach { Kollege(it) } } }
        if (p.ausserListe.isNotEmpty()) item { Karte(Icons.Rounded.DirectionsWalk, Farben.aufsicht, "Außer Haus") { p.ausserListe.forEach { Kollege(it) } } }
        if (p.aufteilung.isNotEmpty()) item {
            Karte(Icons.Rounded.CallSplit, Farben.aufsicht, "Aufgeteilte Klassen") {
                p.aufteilung.toSortedMap().forEach { (std, k) -> Zeile("$std.", Farben.aufsicht, k.joinToString(", "), "$std. Stunde") }
            }
        }
        if (p.datei.isNotEmpty()) item { Text("Datei: ${p.datei.removeSuffix(".docx")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (Kern.statistik.tage.isNotEmpty()) item { StatistikKarte() }
    }
}

/** Nur über mich: wie oft war ich rot (außerplanmäßig) eingeteilt – diesen Monat und dieses Schuljahr */
@Composable
private fun StatistikKarte() {
    val s = Kern.statistik
    val m = s.zahlen(VertretungsStatistik.monatsBeginn()); val j = s.zahlen(VertretungsStatistik.schuljahrBeginn())
    Karte(Icons.Rounded.BarChart, Farben.ausserplan, "Deine Statistik") {
        @Composable fun zeile(t: String, a: Int, b: Int, farbe: Color, kopf: Boolean = false) = Row(Modifier.fillMaxWidth()) {
            val stil = if (kopf) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
            Text(t, Modifier.weight(1f), style = stil)
            listOf(a, b).forEach { Text(if (kopf) (if (it == 0) "Monat" else "Schuljahr") else "$it", Modifier.width(76.dp), style = stil, color = farbe,
                fontWeight = if (kopf) FontWeight.Normal else FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.End) }
        }
        zeile("", 0, 1, MaterialTheme.colorScheme.onSurfaceVariant, kopf = true)
        zeile("Vertretungsstunden", m.vertretungen, j.vertretungen, Farben.ausserplan)
        zeile("Tage mit Vertretung", m.rotTage, j.rotTage, Farben.ausserplan)
        zeile("Begleitungen", m.begleitungen, j.begleitungen, Farben.ausserplan)
        zeile("Aufsichten", m.aufsichten, j.aufsichten, Farben.aufsicht)
        if (j.fehlt > 0) zeile("Selbst gefehlt", m.fehlt, j.fehlt, MaterialTheme.colorScheme.onSurfaceVariant)
        if (j.ausserHaus > 0) zeile("Außer Haus", m.ausserHaus, j.ausserHaus, MaterialTheme.colorScheme.onSurfaceVariant)
        j.klasse?.let { Text("Am öftesten vertreten: $it", style = MaterialTheme.typography.bodyMedium) }
        j.wochentag?.let { Text("Häufigster Tag: " + it.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.GERMAN), style = MaterialTheme.typography.bodyMedium) }
        Text("Nur über dich und nur auf diesem Gerät. Gezählt werden die Pläne, die die App geladen hat (${j.tage} Schultage in diesem Schuljahr).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Kollege(k: String) {
    Row { Text(k, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp)); Text(Kern.einstellungen.kollegium.entries.firstOrNull { it.key.equals(k, true) }?.value ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** Stundenplan: Tag (Schultag mit Vertretung), Woche oder Klassen (aus WebUntis) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StundenplanSeite(zurueck: () -> Unit) {
    var ansicht by remember { mutableIntStateOf(0) }
    val e = Kern.einstellungen
    Seite("Stundenplan", zurueck, aktualisieren = { withContext(Dispatchers.IO) { Kern.untisLaden(true) }; Kern.aktualisieren() }) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Schultag", "Woche", "Klassen").forEachIndexed { i, t ->
                    SegmentedButton(ansicht == i, { ansicht = i }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(t) }
                }
            }
        }
        if (Kern.untisFehler.isNotEmpty()) item { FehlerText(Kern.untisFehler) }
        when (ansicht) {
            0 -> {
                val tag = Kern.schulTag
                val jetzt = Zeit.jetzt()
                item {
                    Karte(Icons.Rounded.School, Farben.vertretung, Zeit.datumLang(tag)) {
                        val b = Kern.schultag(tag, ganz = true)
                        if (b.none { it.meins }) Text("Keine eigenen Stunden eingetragen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else b.forEach { StundenZeile(it, if (it.art == Block.Art.PAUSE || it.art == Block.Art.AUFSICHT) Kern.wetter?.pause(it.von, it.bis) else null, tag == Zeit.heute() && !it.von.isAfter(jetzt) && jetzt.isBefore(it.bis)) }
                    }
                }
            }
            1 -> {
                val montag = Zeit.heute().let { if (Zeit.wochenende(it)) it.plusDays(7) else it }.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                items((0..4).map { montag.plusDays(it.toLong()) }) { d ->
                    val b = Kern.schultag(d).filter { it.zeigen }
                    Karte(Icons.Rounded.Today, if (d == Zeit.heute()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, Zeit.datumLang(d)) {
                        val frei = FreieTage.am(Kern.frei, d)
                        if (frei != null) Text("🏖️ ${frei.name}") else if (b.isEmpty()) Text("Keine Stunden", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else b.forEach { StundenZeile(it, null, false) }
                    }
                }
            }
            else -> {
                val st = Kern.untis
                if (st == null) item { Text(if (e.untisAn) "WebUntis lädt noch …" else "Die Klassen-Übersicht kommt aus WebUntis – in den Einstellungen unter „Schule“ verbinden.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else items(Untis.klassen(st)) { k ->
                    Karte(Icons.Rounded.Groups, Farben.vertretung, "Klasse ${k.klasse} · ${k.stunden} Std.") {
                        Text(k.faecher.joinToString(", ") { "${it.lang.ifEmpty { it.kurz }} (${it.stunden})" }, fontWeight = FontWeight.Medium)
                        if (k.foerder > 0) Text("Förderkreis: ${k.foerder} Std.", color = Color(0xFF00897B))
                        if (k.doppel.isNotEmpty()) Text("Zusammen mit: " + k.doppel.joinToString(", ") { name(it) }, style = MaterialTheme.typography.bodyMedium)
                        if (k.andere.isNotEmpty()) Text("Außerdem in der Klasse: " + k.andere.take(8).joinToString(", ") { "${name(it.kuerzel)} – ${it.faecher.joinToString("/")}" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun name(k: String) = Kern.einstellungen.kollegium[k]?.let { "$it ($k)" } ?: k

/** Speiseplan: alle geladenen Tage, Lieblingsessen (⭐) und Meiden (👎) per Knopf merken */
@Composable
fun MensaSeite(zurueck: () -> Unit) {
    val e = Kern.einstellungen
    val uri = LocalUriHandler.current
    val p = Kern.mensaPlan
    // Wochen zum Blättern; Start = Woche des Essenstags (am Wochenende also die nächste)
    val wochen = p?.tage.orEmpty().groupBy { (Zeit.tag(it.datum) ?: LocalDate.MIN).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }.toSortedMap().entries.toList()
    val start = wochen.indexOfFirst { w -> w.value.any { it.datum >= Kern.essenTag.toString() } }.let { if (it < 0) wochen.size - 1 else it }
    var woche by remember(wochen.size, start) { mutableIntStateOf(start) }
    Seite("Speiseplan", zurueck, untertitel = p?.guthaben?.let { "Guthaben " + "%.2f €".format(it).replace('.', ',') }, aktionen = {
        if (e.mensaAdresse.isNotEmpty()) IconButton({ runCatching { uri.openUri(e.mensaAdresse) } }) { Icon(Icons.Rounded.ShoppingCart, "Im Portal bestellen") }
    }) {
        if (p == null) { item { FehlerText(Kern.mensaFehler.ifEmpty { "Noch kein Speiseplan geladen." }) }; return@Seite }
        val gut = e.lieblingsGerichte.map(Gericht::schluessel).toSet(); val schlecht = e.nichtLecker.map(Gericht::schluessel).toSet()
        item { Text("⭐ oder 👎 antippen zum Merken, noch einmal antippen zum Abwählen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (wochen.isEmpty()) return@Seite
        val i = woche.coerceIn(0, wochen.size - 1)
        val diese = Zeit.heute().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val montag = wochen[i].key
        fun istGut(g: Gericht) = g.schluessel in gut || (e.favoriten.isNotEmpty() && g.passt(e.favoriten))
        val treffer = wochen[i].value.mapNotNull { t -> if (t.frei != null) null else t.gerichte.firstOrNull(::istGut)?.let { t to it } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton({ woche = i - 1 }, enabled = i > 0) { Icon(Icons.Rounded.ChevronLeft, "Woche zurück") }
                Text(when (montag) { diese -> "Diese Woche"; diese.plusWeeks(1) -> "Nächste Woche"; else -> "KW " + montag.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR) },
                    Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                FilledTonalIconButton({ woche = i + 1 }, enabled = i < wochen.size - 1) { Icon(Icons.Rounded.ChevronRight, "Woche vor") }
            }
        }
        if (gut.isNotEmpty() || e.favoriten.isNotEmpty()) item {
            Karte(Icons.Rounded.Star, Color(0xFFF9AB00), if (treffer.isEmpty()) "Diesmal nichts aus deinen Favoriten" else "⭐ ${treffer.size}× aus deinen Favoriten") {
                if (treffer.isNotEmpty()) Text(treffer.joinToString(" · ") { (t, g) -> (Zeit.tag(t.datum)?.dayOfWeek?.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.GERMAN) ?: "") + " " + g.text },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(wochen[i].value) { t ->
            val d = Zeit.tag(t.datum)
            Karte(Icons.Rounded.Restaurant, if (t.datum == Kern.essenTag.toString()) Color(0xFF0B8043) else MaterialTheme.colorScheme.outline,
                d?.let { SchulKalender.datumText(t.datum).replaceFirstChar { c -> c.uppercase() } + if (SchulKalender.datumText(t.datum).length < 8) " · " + Zeit.datumLang(it) else "" } ?: t.datum) {
                if (t.frei != null) Text("🏖️ Geschlossen" + (t.frei?.takeIf { it.isNotEmpty() }?.let { " – $it" } ?: ""))
                t.gerichte.forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.emoji, fontSize = 26.sp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.text, fontWeight = if (g.bestellt) FontWeight.Bold else FontWeight.Medium)
                            Text(listOf(g.menue, if (g.bestellt) "bestellt" else "").filter { it.isNotEmpty() }.joinToString(" · ") +
                                g.merkmale.joinToString("") { " · $it" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconToggleButton(g.schluessel in gut, { an -> Kern.aendere { x -> x.copy(lieblingsGerichte = if (an) (x.lieblingsGerichte + g.text).distinct() else x.lieblingsGerichte.filter { Gericht.schluessel(it) != g.schluessel },
                            nichtLecker = x.nichtLecker.filter { Gericht.schluessel(it) != g.schluessel }) } }) { Text("⭐", fontSize = 20.sp, modifier = Modifier.alpha(if (g.schluessel in gut) 1f else 0.3f)) }
                        IconToggleButton(g.schluessel in schlecht, { an -> Kern.aendere { x -> x.copy(nichtLecker = if (an) (x.nichtLecker + g.text).distinct() else x.nichtLecker.filter { Gericht.schluessel(it) != g.schluessel },
                            lieblingsGerichte = x.lieblingsGerichte.filter { Gericht.schluessel(it) != g.schluessel }) } }) { Text("👎", fontSize = 18.sp, modifier = Modifier.alpha(if (g.schluessel in schlecht) 1f else 0.3f)) }
                    }
                }
            }
        }
        item { GemerktKarte() }
    }
}

/** Gemerkte Gerichte einzeln abwählen (⭐ Lieblingsessen, 👎 nicht lecker) */
@Composable
private fun GemerktKarte() {
    val e = Kern.einstellungen
    Karte(Icons.Rounded.Star, Color(0xFFF9AB00), "Gemerkte Gerichte") {
        if (e.lieblingsGerichte.isEmpty() && e.nichtLecker.isEmpty()) Text("Noch nichts gemerkt.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        e.lieblingsGerichte.forEach { g -> GemerktZeile("⭐", g) { Kern.aendere { x -> x.copy(lieblingsGerichte = x.lieblingsGerichte - g) } } }
        e.nichtLecker.forEach { g -> GemerktZeile("👎", g) { Kern.aendere { x -> x.copy(nichtLecker = x.nichtLecker - g) } } }
        if (e.lieblingsGerichte.size + e.nichtLecker.size > 1) TextButton({ Kern.aendere { it.copy(lieblingsGerichte = emptyList(), nichtLecker = emptyList()) } }) {
            Text("Alle abwählen", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun GemerktZeile(zeichen: String, text: String, weg: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(zeichen); Spacer(Modifier.width(8.dp)); Text(text, Modifier.weight(1f))
        IconButton(weg) { Icon(Icons.Rounded.Close, "Abwählen") }
    }
}
