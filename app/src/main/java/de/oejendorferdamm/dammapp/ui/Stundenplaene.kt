package de.oejendorferdamm.dammapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.oejendorferdamm.dammapp.daten.Kern
import de.oejendorferdamm.dammapp.kern.UntisElement
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
    var stunden by remember { mutableStateOf<List<UntisStunde>>(emptyList()) }
    var fehler by remember { mutableStateOf("") }
    var laedt by remember { mutableStateOf(false) }
    suspend fun laden() {
        laedt = true
        runCatching { withContext(Dispatchers.IO) { Kern.plan(e, montag) } }
            .onSuccess { stunden = it; fehler = if (it.isEmpty()) "In dieser Woche keine Stunden." else "" }
            .onFailure { stunden = emptyList(); fehler = it.message ?: "Nicht erreichbar" }
        laedt = false
        if (tag.isBefore(montag) || tag.isAfter(montag.plusDays(4))) tag = montag
    }
    LaunchedEffect(montag) { laden() }
    val tage = (0..4).map { montag.plusDays(it.toLong()) }
    val de = Locale.GERMAN
    fun unter(s: UntisStunde) = listOfNotNull(s.klassen.joinToString(", ").takeIf { e.typ != 1 }, s.lehrer.joinToString(", ").takeIf { e.typ != 2 }, s.raum.takeIf { e.typ != 4 }).filter { it.isNotEmpty() }.joinToString(" · ")
    Seite(e.name, zurueck, untertitel = e.lang.ifEmpty { e.art }, aktualisieren = { laden() }, aktionen = {
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
            items(stunden.filter { it.datum == tag.toString() }) { s ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.width(56.dp), horizontalAlignment = Alignment.End) {
                            Text(Zeit.hhmm(s.von), fontWeight = FontWeight.Bold)
                            Text(Zeit.hhmm(s.bis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                tage.forEach { d ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(d.format(DateTimeFormatter.ofPattern("EE d.", de)), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                            color = if (d == Zeit.heute()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        val liste = stunden.filter { it.datum == d.toString() }
                        if (liste.isEmpty()) Text("–", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        liste.forEach { s ->
                            val farbe = if (s.entfaellt) MaterialTheme.colorScheme.surfaceVariant else if (s.code == "irregular") MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
                            Column(Modifier.fillMaxWidth().background(farbe, RoundedCornerShape(10.dp)).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(Zeit.hhmm(s.von), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(s.fach, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = if (s.entfaellt) TextDecoration.LineThrough else null)
                                Text(unter(s), fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}
