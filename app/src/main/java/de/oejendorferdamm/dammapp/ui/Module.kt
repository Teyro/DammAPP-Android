package de.oejendorferdamm.dammapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.oejendorferdamm.dammapp.daten.Kern
import de.oejendorferdamm.dammapp.kern.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MARK: Abwesenheiten

@Composable
fun AbwesendSeite(zurueck: () -> Unit) {
    val e = Kern.einstellungen
    val liste = Kern.abwesendAmTag
    val ziel = Kern.schulTag
    Seite("Abwesenheiten", zurueck, untertitel = Kern.schulTagName.replaceFirstChar { it.uppercase() } + if (e.klassenListe.isEmpty()) "" else " · " + e.klassenListe.joinToString(", "),
        aktualisieren = { Kern.moduleLaden(true) }) {
        val (zahl, wort) = Kern.abwesendZahl
        val zaehlt = liste.filter { !Abwesenheiten.ausgegraut(it, e.abwesendAus) }
        val grau = liste.filter { Abwesenheiten.ausgegraut(it, e.abwesendAus) }
        val planDa = wort.startsWith("krank")
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(zahl, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(if (planDa) "$wort (Vertretungsplan)" else wort, style = MaterialTheme.typography.titleMedium)
                    if (planDa) Text("IServ: ${zaehlt.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Antippen graut aus: steht weiter in der Liste, zählt aber nicht mit. Noch einmal antippen holt zurück.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (Kern.abwesendFehler.isNotEmpty()) item { FehlerText(Kern.abwesendFehler) }
        fun umschalten(a: Abwesenheit) = Kern.aendere { x ->
            if (Abwesenheiten.ausgegraut(a, x.abwesendAus)) x.copy(abwesendAus = x.abwesendAus.filterNot { it.trim().equals(a.name.trim(), ignoreCase = true) })
            else x.copy(abwesendAus = x.abwesendAus + a.name)
        }
        val ganz = zaehlt.filter { !it.stundenweise }; val std = zaehlt.filter { it.stundenweise }
        listOf(Triple("Ganztägig", ganz, false), Triple("Einzelne Stunden", std, false), Triple("Ausgegraut – zählt nicht", grau, true)).forEach { (titel, l, aus) ->
            if (l.isNotEmpty()) item {
                Karte(Icons.Rounded.PersonOff, if (aus) MaterialTheme.colorScheme.outline else Farben.fehlt, titel) {
                    l.forEach { a ->
                        val art = Abwesenheiten.arten[a.art] ?: ("📌" to "abwesend")
                        Box(Modifier.alpha(if (aus) 0.45f else 1f)) {
                            Zeile(art.first, if (aus) MaterialTheme.colorScheme.outline else Farben.fehlt, a.name,
                                listOf(a.klasse, art.second, Abwesenheiten.zeitText(a, ziel), Abwesenheiten.gemeldetText(a) ?: "").filter { it.isNotEmpty() }.joinToString(" · "), onClick = { umschalten(a) }) {
                                Icon(if (aus) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (aus) "Wieder mitzählen" else "Ausgrauen", tint = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
        Kern.abwesend?.let { item { Text("Stand " + Zeit.uhrzeit(it.stand), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}

// MARK: Störungen

private val Stoerung.farbe get() = if (erledigt) Farben.gut else if (uebernommen) Farben.vertretung else Farben.aufsicht
private val Stoerung.symbol: ImageVector get() = if (erledigt) Icons.Rounded.CheckCircle else if (uebernommen) Icons.Rounded.Build else Icons.Rounded.Error

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoerungenSeite(zurueck: () -> Unit) {
    val st = Kern.stoerungen
    val neu = st?.offen?.filter { !it.uebernommen } ?: emptyList()
    val arbeit = st?.offen?.filter { it.uebernommen } ?: emptyList()
    val erledigt = st?.alle?.filter { it.erledigt } ?: emptyList()
    var offen by remember { mutableStateOf<Stoerung?>(null) }
    Seite("Störungen", zurueck, aktualisieren = { Kern.moduleLaden(true) }) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                ZahlKachel(Icons.Rounded.Error, Farben.aufsicht, "${neu.size}", "Noch niemand", Modifier.weight(1f).fillMaxHeight()) {}
                ZahlKachel(Icons.Rounded.Build, Farben.vertretung, "${arbeit.size}", "In Arbeit", Modifier.weight(1f).fillMaxHeight()) {}
                ZahlKachel(Icons.Rounded.CheckCircle, Farben.gut, "${erledigt.size}", "Erledigt", Modifier.weight(1f).fillMaxHeight()) {}
            }
        }
        if (Kern.stoerungenFehler.isNotEmpty()) item { FehlerText(Kern.stoerungenFehler) }
        fun gruppe(titel: String, l: List<Stoerung>, farbe: Color) {
            if (l.isEmpty()) return
            item { Text("$titel (${l.size})", style = MaterialTheme.typography.titleMedium, color = farbe, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
            items(l) { m -> StoerungZeile(m) { offen = m } }
        }
        gruppe("Noch niemand zuständig", neu, Farben.aufsicht)
        gruppe("In Arbeit", arbeit, Farben.vertretung)
        if (Kern.einstellungen.erledigteZeigen) gruppe("Erledigt", erledigt, Farben.gut)
        item {
            ListItem(headlineContent = { Text("Erledigte zeigen") }, trailingContent = {
                Switch(Kern.einstellungen.erledigteZeigen, { an -> Kern.aendere { it.copy(erledigteZeigen = an) } }) }, modifier = Modifier.clip(RoundedCornerShape(16.dp)))
        }
    }
    offen?.let { m -> ModalBottomSheet({ offen = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) { StoerungDetail(m) } }
}

@Composable
private fun StoerungZeile(m: Stoerung, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clip(CardDefaults.elevatedShape).clickable(onClick = onClick), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(m.symbol, null, tint = m.farbe, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(m.titel, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (m.erledigt) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Text(listOfNotNull(m.ort.ifEmpty { null }?.let { "📍 $it" }, m.datum?.let { "🕓 " + Zeit.relativ(it) }).joinToString("   "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Etikett(m.zustandKurz, m.farbe)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun Etikett(text: String, farbe: Color) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = farbe, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(farbe.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 3.dp))
}

@Composable
private fun StoerungDetail(m: Stoerung) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var verlauf by remember { mutableStateOf<StoerungVerlauf?>(null) }
    var fehler by remember { mutableStateOf("") }
    var antwort by remember { mutableStateOf("") }
    var sendet by remember { mutableStateOf(false) }
    suspend fun laden() { if (m.url.isEmpty()) return
        runCatching { withContext(Dispatchers.IO) { Stoerungen.verlauf(Kern.iservSitzung(), m.url) } }.onSuccess { verlauf = it; fehler = "" }.onFailure { fehler = it.message ?: "IServ nicht erreichbar" } }
    LaunchedEffect(m) { laden() }
    // Profilbilder aus IServ (Adresse → Bild), mit den gespeicherten Sitzungs-Cookies geladen
    var bilder by remember { mutableStateOf<Map<String, androidx.compose.ui.graphics.ImageBitmap>>(emptyMap()) }
    LaunchedEffect(verlauf) {
        val v = verlauf ?: return@LaunchedEffect
        if (v.avatare.isEmpty()) return@LaunchedEffect
        bilder = withContext(Dispatchers.IO) {
            val s = Kern.iservSitzung(vonHand = false)
            v.avatare.values.distinct().mapNotNull { u -> s.bild(u)?.let { b -> android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()?.let { u to it } } }.toMap()
        }
    }
    LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Surface(color = m.farbe.copy(alpha = 0.12f), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(m.symbol, null, tint = m.farbe); Spacer(Modifier.width(6.dp)); Etikett(m.zustandKurz, m.farbe) }
                    Text(m.titel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    m.datum?.let { Text(Zeit.datumKurz(it) + " · " + Zeit.relativ(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item {
            Card { Column(Modifier.padding(vertical = 6.dp)) {
                Angabe(Icons.Rounded.Place, Color(0xFFD93025), "Ort", m.ort)
                Angabe(Icons.Rounded.Person, Color(0xFF5F6368), "Gemeldet von", m.autor)
                Angabe(Icons.Rounded.Build, Farben.vertretung, "Bearbeitet von", m.bearbeiter.ifEmpty { if (m.unbesetzt) "noch niemand" else "" })
                Angabe(Icons.Rounded.Flag, m.farbe, "Status", m.status)
            } }
        }
        // leere Beschreibung und eine, die im Verlauf schon als Blase steht, nicht zeigen
        fun norm(s: String) = s.lowercase().split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString(" ")
        if (m.inhalt.isNotEmpty() && verlauf?.eintraege?.none { norm(it.text) == norm(m.inhalt) } != false) item {
            Card { Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Beschreibung", style = MaterialTheme.typography.titleSmall)
                Text(m.inhalt)
            } }
        }
        if (m.url.isNotEmpty()) item {
            Card { Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Verlauf", style = MaterialTheme.typography.titleSmall)
                val v = verlauf
                if (v == null && fehler.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Lade Verlauf …") }
                // Jeder Kommentar eine Blase wie im Chat: Technik rechts, alle anderen links; Farbe je Person aus dem Board „Medien“
                // Erste Nachricht ohne Absender = die Meldung selbst; jede Nachricht eigene Blase, Name nur beim Absenderwechsel, Datum als Trenner
                val liste = v?.eintraege.orEmpty().mapIndexed { i, e -> if (i == 0 && e.autor.isEmpty()) e.copy(autor = m.autor, zeit = m.datum?.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) ?: e.zeit) else e }
                fun tag(z: String) = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{2,4})""").find(z)?.destructured?.let { (d, mo, j) ->
                    runCatching { java.time.LocalDate.of(j.toInt().let { if (it < 100) 2000 + it else it }, mo.toInt(), d.toInt()) }.getOrNull() }
                // jede Person eine eigene Farbe wie im WhatsApp-Gruppenchat: aus dem Board „Medien“, sonst der Reihe nach ohne Doppelte
                val farben = personenFarben(liste.map { it.autor }) { Aufgaben.farbeFuer(it, Aufgaben.MEDIEN_VORGABE + Kern.personenFarben)?.let(::hexFarbe) }
                fun tagText(t: java.time.LocalDate) = when (t) { Zeit.heute() -> "Heute"; Zeit.heute().minusDays(1) -> "Gestern"
                    else -> t.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d. MMMM", java.util.Locale.GERMAN)) }
                liste.forEachIndexed { i, e ->
                    val vorher = liste.getOrNull(i - 1)
                    val t = tag(e.zeit)
                    val neuerTag = t != null && t != vorher?.let { tag(it.zeit) }
                    if (neuerTag) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(tagText(t!!), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 3.dp))
                    }
                    val mitName = neuerTag || vorher?.autor != e.autor
                    // eigene Nachrichten rechts in der Akzentfarbe (angemeldeter IServ-Benutzer bzw. „Ich bin“), alle anderen links
                    val technik = Stoerungen.istIch(e.autor, Kern.einstellungen.iservBenutzer, Kern.einstellungen.ichName)
                    val farbe = farben[e.autor] ?: namensFarbe(e.autor)
                    val initialen = e.autor.split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase().ifEmpty { "?" }
                    val bild = v?.avatar(e.autor)?.let { bilder[it] }
                    val kreis = @Composable {
                        if (bild != null) androidx.compose.foundation.Image(bild, null, Modifier.size(30.dp).clip(CircleShape).border(1.5.dp, farbe, CircleShape),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        else Box(Modifier.size(30.dp).clip(CircleShape).background(farbe), contentAlignment = Alignment.Center) {
                            Text(initialen, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                    }
                    val uhr = Regex("""\d{1,2}:\d{2}""").find(e.zeit)?.value
                    Row(Modifier.fillMaxWidth().padding(top = if (mitName) 6.dp else 0.dp), horizontalArrangement = if (technik) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
                        if (!technik) { Box(Modifier.alpha(if (mitName) 1f else 0f)) { kreis() }; Spacer(Modifier.width(8.dp)) }
                        Surface(color = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) farbe.copy(alpha = 0.5f) else farbe, // Kartenfarbe aus „Medien“; dunkel gedämpft, damit die helle Schrift lesbar bleibt
                            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (technik) 18.dp else 4.dp, bottomEnd = if (technik) 4.dp else 18.dp),
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(0.82f)) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), horizontalAlignment = if (technik) Alignment.End else Alignment.Start) {
                                if (mitName && !technik && e.autor.isNotEmpty()) Text(e.autor, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontWeight = FontWeight.SemiBold)
                                Text(e.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                uhr?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        if (technik) { Spacer(Modifier.width(8.dp)); Box(Modifier.alpha(if (mitName) 1f else 0f)) { kreis() } }
                    }
                }
                if (v != null && v.formular == null) Text("Antworten geht für diese Meldung nur in IServ.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FehlerText(fehler)
                v?.formular?.let { f -> AntwortFeld(antwort, { antwort = it }, sendet) {
                    scope.launch { sendet = true
                        runCatching { withContext(Dispatchers.IO) { Stoerungen.antworten(Kern.iservSitzung(), f, antwort) } }.onSuccess { antwort = ""; laden() }.onFailure { fehler = it.message ?: "Nicht gesendet" }
                        sendet = false }
                } }
            } }
        }
        if (m.url.isNotEmpty()) item { OutlinedButton({ runCatching { uri.openUri(m.url) } }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.OpenInBrowser, null); Spacer(Modifier.width(8.dp)); Text("In IServ öffnen") } }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun Angabe(symbol: ImageVector, farbe: Color, t: String, w: String) {
    if (w.isEmpty()) return
    ListItem(leadingContent = { FarbSymbol(symbol, farbe, 28) }, headlineContent = { Text(t, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { Text(w, fontWeight = FontWeight.Medium) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}

// MARK: Aufgaben (Edupool)

private val katFarben = listOf(Color(0xFF8E24AA), Color(0xFF1A73E8), Color(0xFFE8710A), Color(0xFFD81B60), Color(0xFF1E8E3E))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AufgabenSeite(zurueck: () -> Unit) {
    val st = Kern.aufgaben
    var erledigte by remember { mutableStateOf(false) }
    var suche by remember { mutableStateOf("") }
    var offen by remember { mutableStateOf<Aufgabe?>(null) }
    Seite("Aufgaben", zurueck, untertitel = st?.let { "${it.offen.size} offen · ${it.boards} Boards · Stand ${Zeit.uhrzeit(it.stand)}" }, aktualisieren = { Kern.moduleLaden(true) }) {
        item { OutlinedTextField(suche, { suche = it }, Modifier.fillMaxWidth(), placeholder = { Text("Aufgaben durchsuchen") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(28.dp)) }
        if (Kern.aufgabenFehler.isNotEmpty()) item { FehlerText(Kern.aufgabenFehler) }
        st?.gruppen()?.forEach { (kat, l) ->
            val k = Aufgaben.kategorien[kat]
            val a = l.filter { suche.isEmpty() || (it.titel + " " + it.beschreibung).contains(suche, true) }
            if ((!k.erledigt || erledigte) && a.isNotEmpty()) {
                item { Text("${k.name} (${a.size})", style = MaterialTheme.typography.titleMedium, color = katFarben[kat], fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
                items(a) { x ->
                    ElevatedCard(Modifier.fillMaxWidth().clip(CardDefaults.elevatedShape).clickable { offen = x }, colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(4.dp).height(40.dp).clip(CircleShape).background(katFarben[kat]))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(x.titel, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (x.beschreibung.isNotEmpty()) Text(x.beschreibung, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                Text(x.boardTitel + (x.kommentarAnzahl?.takeIf { it > 0 }?.let { " · 💬 $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        }
        item { ListItem(headlineContent = { Text("Erledigte zeigen") }, trailingContent = { Switch(erledigte, { erledigte = it }) }) }
    }
    offen?.let { a -> ModalBottomSheet({ offen = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) { AufgabeDetail(a) } }
}

@Composable
private fun AufgabeDetail(a: Aufgabe) {
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var kommentare by remember { mutableStateOf<List<Aufgaben.Kommentar>?>(null) }
    var fehler by remember { mutableStateOf("") }
    var antwort by remember { mutableStateOf("") }
    var sendet by remember { mutableStateOf(false) }
    suspend fun laden() = runCatching { withContext(Dispatchers.IO) { Aufgaben.kommentare(Kern.geheim.edupool, a.id) } }.onSuccess { kommentare = it; fehler = "" }.onFailure { fehler = it.message ?: "" }
    LaunchedEffect(a) { laden() }
    val farbe = katFarben[a.kategorie]
    LazyColumn(Modifier.fillMaxWidth().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Etikett(Aufgaben.kategorien[a.kategorie].name, farbe)
                Text(a.titel, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(a.boardTitel + if (a.spalte.isNotEmpty()) " · ${a.spalte}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card { Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (a.inhalt.isEmpty()) Text("Kein Inhalt.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                a.inhalt.forEach { b ->
                    when (b.typ) {
                        "text" -> Text(Html.absatzText(b.text).replace(Regex("""[*_`#]+"""), ""))
                        "link", "image", "video", "audio" -> if (b.url.isNotEmpty()) TextButton({ runCatching { uri.openUri(b.url) } }, contentPadding = PaddingValues(0.dp)) {
                            Text((when (b.typ) { "image" -> "🖼 "; "video" -> "🎬 "; "audio" -> "🎧 "; else -> "🔗 " }) + b.titel.ifEmpty { b.url }, maxLines = 2) }
                        "file" -> Text("📄 ${b.titel}")
                        else -> Column { Text(b.titel, fontWeight = FontWeight.SemiBold); if (b.text.isNotEmpty()) Text(b.text, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            } }
        }
        item {
            Card { Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Kommentare", style = MaterialTheme.typography.titleSmall)
                val k = kommentare
                if (k == null && fehler.isEmpty()) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                if (k != null && k.isEmpty()) Text("Noch keine Kommentare.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                k?.forEach { c ->
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(12.dp).fillMaxWidth()) {
                            Text(listOf(c.autor, c.zeit?.let { Zeit.datumKurz(it) } ?: "").filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(c.text)
                        }
                    }
                }
                FehlerText(fehler)
                AntwortFeld(antwort, { antwort = it }, sendet) {
                    scope.launch { sendet = true
                        runCatching { withContext(Dispatchers.IO) { Aufgaben.antworten(Kern.geheim.edupool, a.id, antwort) } }.onSuccess { antwort = ""; laden() }.onFailure { fehler = it.message ?: "Nicht gesendet" }
                        sendet = false }
                }
            } }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}
