package de.oejendorferdamm.dammapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.oejendorferdamm.dammapp.daten.Kern
import de.oejendorferdamm.dammapp.kern.UntisElement
import de.oejendorferdamm.dammapp.kern.UntisRaster
import de.oejendorferdamm.dammapp.kern.Schulzeit
import de.oejendorferdamm.dammapp.kern.UntisStunde
import de.oejendorferdamm.dammapp.kern.UntisSuche
import de.oejendorferdamm.dammapp.kern.Zeit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Stundenpläne anderer suchen: Klassen, Lehrkräfte, Räume – Favoriten mit Herz, der eigene Plan oben */
@Composable
fun StundenplaeneSeite(zurueck: () -> Unit) {
    var gewaehlt by remember { mutableStateOf<UntisElement?>(null) }
    var suche by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Kern.untisSucheVorbereiten() } }
    gewaehlt?.let { e -> BackHandler { gewaehlt = null }; PlanSeite(e) { gewaehlt = null }; return }
    Seite("Stundenpläne", zurueck, aktualisieren = { withContext(Dispatchers.IO) { Kern.untisSucheVorbereiten(neu = true) } }) {
        item { OutlinedTextField(suche, { suche = it }, Modifier.fillMaxWidth(), placeholder = { Text("Klasse, Kürzel oder Raum") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true) }
        if (Kern.untisSucheHinweis.isNotEmpty()) item { FehlerText(Kern.untisSucheHinweis) }
        if (suche.isBlank()) {
            item {
                Karte(Icons.Rounded.Favorite, MaterialTheme.colorScheme.error, "Favoriten") {
                    Kern.ichAlsElement?.let { ich -> Zeile("Ich", MaterialTheme.colorScheme.primary, "Mein Stundenplan", ich.name, onClick = { gewaehlt = ich }) }
                    Kern.untisFavoriten.filter { it.id != Kern.ichAlsElement?.id }.forEach { f ->
                        Zeile(f.art.take(1), MaterialTheme.colorScheme.secondary, f.name, f.lang.ifEmpty { f.art }, onClick = { gewaehlt = f })
                    }
                    if (Kern.untisFavoriten.isEmpty()) Text("Mit dem Herz merkst du dir Klassen, Lehrkräfte und Räume.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            listOf(1 to "Klassen", 2 to "Lehrkräfte", 4 to "Räume").forEach { (typ, titel) ->
                val liste = Kern.untisElemente.filter { it.typ == typ }
                if (liste.isNotEmpty()) item {
                    var offen by remember { mutableStateOf(typ == 1) }
                    Karte(if (typ == 1) Icons.Rounded.Groups else if (typ == 2) Icons.Rounded.Person else Icons.Rounded.MeetingRoom, MaterialTheme.colorScheme.tertiary, "$titel (${liste.size})",
                        onClick = { offen = !offen }) {
                        if (offen) liste.forEach { e -> Zeile(e.name.take(3), MaterialTheme.colorScheme.tertiary, e.name, e.lang.ifEmpty { null }, onClick = { gewaehlt = e }) }
                    }
                }
            }
            if (Kern.untisElemente.isEmpty() && Kern.untisSucheHinweis.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        } else {
            val s = suche.trim().lowercase()
            val treffer = Kern.untisElemente.filter { it.name.lowercase().contains(s) || it.lang.lowercase().contains(s) }.sortedWith(compareBy({ it.typ }, { it.name }))
            if (treffer.isEmpty()) item { Text("Nichts gefunden.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(treffer, key = { it.id }) { e -> Zeile(e.art.take(1), MaterialTheme.colorScheme.tertiary, e.name, listOf(e.art, e.lang).filter { it.isNotEmpty() }.joinToString(" · "), onClick = { gewaehlt = e }) }
        }
    }
}

/** Plan einer Klasse, Lehrkraft oder eines Raums: Tag oder Woche, Wochen vor/zurück, Herz zum Merken */
@Composable
private fun PlanSeite(e: UntisElement, zurueck: () -> Unit) {
    var ansicht by remember { mutableIntStateOf(1) }
    var montag by remember { mutableStateOf(UntisSuche.montag()) }
    var tag by remember { mutableStateOf(Zeit.heute()) }
    var roh by remember { mutableStateOf<List<UntisStunde>>(emptyList()) }
    var fehler by remember { mutableStateOf("") }
    var laedt by remember { mutableStateOf(false) }
    // Vertretungsplan über die Stunden legen (an) oder nur der reine Untis-Plan (aus) – gemerkt
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("stundenplaene", android.content.Context.MODE_PRIVATE)
    var vertretungen by remember { mutableStateOf(prefs.getBoolean("vertretungen", true)) }
    suspend fun laden() {
        laedt = true
        runCatching { withContext(Dispatchers.IO) { Kern.plan(e, montag) } }
            .onSuccess { roh = it; fehler = if (it.isEmpty()) "In dieser Woche keine Stunden." else "" }
            .onFailure { roh = emptyList(); fehler = it.message ?: "Nicht erreichbar" }
        laedt = false
        if (tag.isBefore(montag) || tag.isAfter(montag.plusDays(4))) tag = montag
    }
    LaunchedEffect(montag) { laden() }
    // mit Vertretungen: wie Untis sie meldet; ohne: der nackte Plan (ursprüngliche Stunden, keine Zusatztermine)
    val stunden = if (vertretungen) roh else roh.filter { it.code != "irregular" || it.lehrer.isNotEmpty() }.map { it.copy(code = "") }
    val istIch = e.id == Kern.ichAlsElement?.id
    fun plan(datum: String) = Kern.vertretung?.takeIf { vertretungen && it.datum == datum }
    // Bemerkungen (Begleitung …): eigene – oder die jeder anderen Lehrkraft aus der ganzen „Wichtig“-Zeile
    fun bemerkungen(datum: String) = plan(datum)?.let { p -> if (istIch) p.bemerkungen else if (e.typ == 2) de.oejendorferdamm.dammapp.kern.Bemerkungen.lesen(p.alleHinweise, e.name) else emptyList() }.orEmpty()
    fun extras(datum: String, z: Schulzeit): List<String> {
        val p = plan(datum) ?: return emptyList()
        if (z.pause) return emptyList()
        val nr = z.name.toIntOrNull() ?: -1
        return (if (istIch) p.stunden.filter { it.stunde.trim('.', ' ') == z.name }.map { "Vertretung ${it.klasse}" } else emptyList()) + bemerkungen(datum).filter { nr in it.stunden }.map { "📌 " + it.titel }
    }
    // Aufsicht in der Pause (Vertretungsplan des Tages): eigene – oder die der Lehrkraft, deren Plan das ist
    fun aufsicht(datum: String, z: Schulzeit): String? {
        if (!z.pause || !vertretungen) return null
        val p = Kern.vertretung?.takeIf { it.datum == datum } ?: return null
        val liste = if (istIch) p.pausen else if (e.typ == 2) p.alleAufsichten[e.name.lowercase()].orEmpty() else emptyList()
        return liste.firstOrNull { it.pause.lowercase().startsWith(z.name.lowercase().take(2)) }?.let { "Aufsicht ${it.ort}" + if (it.reserve) " (Reserve)" else "" }
    }
    // Fachfarbe wie in DammTable; heute schon gelaufen = ausgegraut
    val akzent = MaterialTheme.colorScheme.primary
    fun fachFarbe(s: UntisStunde): androidx.compose.ui.graphics.Color =
        if (s.entfaellt) androidx.compose.ui.graphics.Color.Gray else de.oejendorferdamm.dammapp.kern.Fachfarbe.hex(s.fach, s.fachLang)?.let(::hexFarbe) ?: akzent
    fun vorbei(s: UntisStunde): Boolean {
        val heute = Zeit.heute().toString()
        if (s.datum != heute) return s.datum < heute
        val j = java.time.LocalTime.now(); return j.hour * 60 + j.minute >= s.bis
    }
    val tage = (0..4).map { montag.plusDays(it.toLong()) }
    // Raster wie DammTable (0. Stunde 8:00–8:30 …): jede Stunde in ihrer Zeile, freie bleiben sichtbar frei
    val zeilen = UntisRaster.zeilen(stunden, Kern.einstellungen.schulzeiten)
    fun nr(z: Schulzeit) = if (z.pause) "" else "${z.name}."
    val de = Locale.GERMAN
    fun unter(s: UntisStunde) = listOfNotNull(s.klassen.joinToString(", ").takeIf { e.typ != 1 }, s.lehrer.joinToString(", ").takeIf { e.typ != 2 }, s.raum.takeIf { e.typ != 4 }).filter { it.isNotEmpty() }.joinToString(" · ")
    Seite(e.name, zurueck, untertitel = e.lang.ifEmpty { e.art }, aktualisieren = { laden() }, aktionen = {
        IconButton({ vertretungen = !vertretungen; prefs.edit().putBoolean("vertretungen", vertretungen).apply() }) {
            Icon(if (vertretungen) Icons.Rounded.SwapHoriz else Icons.Rounded.CalendarMonth, if (vertretungen) "Vertretungen ausblenden (nur Untis-Plan)" else "Vertretungen einblenden",
                tint = if (vertretungen) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val fav = Kern.favorit(e)
        IconButton({ Kern.favoritUmschalten(e) }) { Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (fav) "Aus den Favoriten" else "Zu den Favoriten", tint = MaterialTheme.colorScheme.error) }
    }) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("Tag", "Woche").forEachIndexed { i, t -> SegmentedButton(ansicht == i, { ansicht = i }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(t) } }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton({ montag = montag.minusWeeks(1) }) { Icon(Icons.Rounded.ChevronLeft, "Woche zurück") }
                Text("Woche ab " + montag.format(DateTimeFormatter.ofPattern("d. MMMM", de)), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                FilledTonalIconButton({ montag = montag.plusWeeks(1) }) { Icon(Icons.Rounded.ChevronRight, "Woche vor") }
            }
        }
        if (fehler.isNotEmpty()) item { FehlerText(fehler) }
        if (laedt) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (ansicht == 0) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tage.forEach { d -> FilterChip(d == tag, { tag = d }, { Text(d.format(DateTimeFormatter.ofPattern("EE d.", de))) }) }
                }
            }
            bemerkungen(tag.toString()).filter { it.stunden.isEmpty() }.forEach { b ->
                item { Text("📌 " + listOf(b.titel, b.zeit).filter { it.isNotEmpty() }.joinToString(" · "), color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold) }
            }
            items(zeilen.flatMap { z ->
                if (z.pause) listOf(Triple(z, null as UntisStunde?, null as String?))
                else {
                    val st = UntisRaster.in_(stunden, tag.toString(), z); val ex = extras(tag.toString(), z)
                    ex.map { Triple(z, null, it) } + st.map { Triple(z, it, null) } + (if (st.isEmpty() && ex.isEmpty()) listOf(Triple(z, null, null)) else emptyList())
                }
            }) { (z, s, extra) ->
                if (z.pause) { Text("☕ ${z.name}" + (aufsicht(tag.toString(), z)?.let { " · $it" } ?: ""), Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium,
                    color = if (aufsicht(tag.toString(), z) != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center); return@items }
                if (extra != null) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text("${nr(z)} · ${Zeit.hhmm(z.von)}", Modifier.width(96.dp))
                            Text(extra, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    return@items
                }
                if (s == null) {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text("${nr(z)} · ${Zeit.hhmm(z.von)}", Modifier.width(96.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("frei", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    return@items
                }
                ElevatedCard(Modifier.fillMaxWidth().alpha(if (vorbei(s)) 0.4f else 1f),
                    colors = CardDefaults.elevatedCardColors(containerColor = if (vorbei(s)) MaterialTheme.colorScheme.surfaceVariant else fachFarbe(s).copy(alpha = 0.16f))) {
                    Row(Modifier.padding(14.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
                        Box(Modifier.width(6.dp).fillMaxHeight().background(if (vorbei(s)) androidx.compose.ui.graphics.Color.Gray else fachFarbe(s), RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.width(48.dp), horizontalAlignment = Alignment.End) {
                            Text(nr(z), fontWeight = FontWeight.Bold)
                            Text(Zeit.hhmm(s.von), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.fachLang.ifEmpty { s.fach }, style = MaterialTheme.typography.titleMedium, textDecoration = if (s.entfaellt) TextDecoration.LineThrough else null)
                                if (s.entfaellt) Text("  Entfall", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                if (s.code == "irregular") Text("  Vertretung", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(unter(s), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Spacer(Modifier.width(34.dp))
                        tage.forEach { d ->
                            Text(d.format(DateTimeFormatter.ofPattern("EE d.", de)), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = if (d == Zeit.heute()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    zeilen.forEach { z ->
                        if (z.pause) Text(z.name + (tage.firstNotNullOfOrNull { aufsicht(it.toString(), z) }?.let { " · $it" } ?: ""), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)).padding(vertical = 1.dp),
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        else Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.width(34.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(nr(z), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(Zeit.hhmm(z.von), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            tage.forEach { d ->
                                val hier = UntisRaster.in_(stunden, d.toString(), z)
                                val mehr = extras(d.toString(), z)
                                if (hier.isEmpty() && mehr.isNotEmpty())
                                    Box(Modifier.weight(1f).heightIn(min = 50.dp).background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(10.dp)).padding(4.dp), contentAlignment = Alignment.Center) {
                                        Text(mehr.joinToString("\n"), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, lineHeight = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    }
                                else if (hier.isEmpty())
                                    Box(Modifier.weight(1f).heightIn(min = 50.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                        Text("frei", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                                    }
                                else {
                                    val s0 = hier[0]
                                    val alt = hier.all { vorbei(it) }
                                    val farbe = if (alt || s0.entfaellt) MaterialTheme.colorScheme.surfaceVariant else if (s0.code == "irregular") MaterialTheme.colorScheme.tertiaryContainer else fachFarbe(s0).copy(alpha = 0.28f)
                                    Column(Modifier.weight(1f).heightIn(min = 50.dp).alpha(if (alt) 0.45f else 1f).background(farbe, RoundedCornerShape(10.dp))
                                        .border(1.5.dp, if (alt) androidx.compose.ui.graphics.Color.Transparent else fachFarbe(s0).copy(alpha = 0.7f), RoundedCornerShape(10.dp)).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        hier.forEach { s ->
                                            Text(s.fach, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = if (s.entfaellt) TextDecoration.LineThrough else null)
                                            Text(unter(s), fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                        }
                                        mehr.forEach { Text(it, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary, maxLines = 2, lineHeight = 11.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
