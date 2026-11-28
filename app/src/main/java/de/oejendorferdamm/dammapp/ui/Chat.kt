package de.oejendorferdamm.dammapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.oejendorferdamm.dammapp.daten.Kern
import de.oejendorferdamm.dammapp.daten.Melder
import de.oejendorferdamm.dammapp.kern.Aufgaben
import de.oejendorferdamm.dammapp.kern.Matrix
import de.oejendorferdamm.dammapp.kern.Zeit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Zustand des Chats (Matrix/Element der Schule): angemeldet mit den IServ-Zugangsdaten, aktuell per Long-Poll, solange der Chat offen ist */
object Chat {
    var raeume by mutableStateOf<List<Matrix.Raum>>(emptyList())
    var fehler by mutableStateOf("")
    var laedt by mutableStateOf(false)
    private var matrix: Matrix? = null
    private var weiter = ""
    val ich get() = matrix?.ich ?: ""

    /** läuft, bis der Aufrufer (LaunchedEffect) beendet wird */
    suspend fun schleife() {
        if (matrix == null) Kern.geheim.matrix.split("\t").takeIf { it.size == 2 }?.let { matrix = Matrix(it[1], it[0]) }
        while (kotlin.coroutines.coroutineContext.isActive) {
            try {
                var m = matrix
                if (m == null) {
                    val b = Kern.einstellungen.iservBenutzer; val p = Kern.geheim.iserv
                    if (b.isEmpty() || p.isEmpty()) { fehler = "Bitte zuerst IServ in den Einstellungen einrichten – der Chat nutzt dieselbe Anmeldung."; return }
                    laedt = true
                    m = withContext(Dispatchers.IO) { Matrix.anmelden(b, p) }
                    matrix = m; Kern.aendereGeheim { it.copy(matrix = m.ich + "\t" + m.token) }
                }
                laedt = raeume.isEmpty()
                val j = withContext(Dispatchers.IO) { m.sync(weiter, if (weiter.isEmpty()) 0 else 25_000) }
                val (r, w) = Matrix.einarbeiten(j, raeume)
                raeume = r; weiter = w; fehler = ""; laedt = false
            } catch (e: Matrix.AnmeldeFehler) {
                if (matrix == null) { fehler = e.message ?: ""; laedt = false; return }
                matrix = null; weiter = ""; Kern.aendereGeheim { it.copy(matrix = "") } // Token abgelaufen → neu anmelden
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                fehler = e.message ?: "Chat nicht erreichbar"; laedt = false; delay(10_000)
            }
        }
    }

    private val bilder = android.util.LruCache<String, ImageBitmap>(80)

    /** Profil-/Vorschaubild (angemeldet geladen, im Speicher gemerkt) */
    suspend fun bild(mxc: String, groesse: Int): ImageBitmap? {
        val k = "$mxc|$groesse"
        bilder.get(k)?.let { return it }
        val m = matrix ?: return null
        val b = withContext(Dispatchers.IO) { runCatching { m.medien(mxc, groesse).let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }.getOrNull() } ?: return null
        bilder.put(k, b); return b
    }

    /** Anhang ganz laden und mit passender App öffnen (Galerie, PDF-Anzeige …) */
    suspend fun oeffnen(c: android.content.Context, a: Matrix.Anhang) {
        val m = matrix ?: return
        runCatching {
            val f = withContext(Dispatchers.IO) {
                java.io.File(c.cacheDir, "chat").apply { mkdirs() }.resolve(a.name.replace("/", "-").ifEmpty { "Anhang" }).apply { writeBytes(m.medien(a.mxc)) }
            }
            val u = androidx.core.content.FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", f)
            val typ = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: when (a.art) { "bild" -> "image/*"; "video" -> "video/*"; "audio" -> "audio/*"; else -> "*/*" }
            c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(u, typ).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { fehler = it.message ?: "Anhang lässt sich nicht öffnen" }
    }

    suspend fun senden(raum: String, text: String) {
        val m = matrix ?: return
        runCatching { withContext(Dispatchers.IO) { m.senden(raum, text) } }.onFailure { fehler = it.message ?: "" }
    }

    /** Anpinnen sofort zeigen, dann an den Server; bei Fehler zurück */
    suspend fun anpinnen(r: Matrix.Raum) {
        val m = matrix ?: return; val an = !r.angepinnt
        fun setze(w: Boolean) { raeume = Matrix.sortiert(raeume.map { if (it.id == r.id) it.copy(angepinnt = w) else it }) }
        setze(an)
        runCatching { withContext(Dispatchers.IO) { m.anpinnen(r.id, an) } }.onFailure { fehler = it.message ?: ""; setze(!an) }
    }

    suspend fun gelesen(r: Matrix.Raum) {
        val m = matrix ?: return; val letzte = r.nachrichten.lastOrNull() ?: return
        if (r.ungelesen == 0) return
        raeume = raeume.map { if (it.id == r.id) it.copy(ungelesen = 0) else it }
        withContext(Dispatchers.IO) { m.gelesen(r.id, letzte.id) }
    }
}

private val zone = ZoneId.of("Europe/Berlin")
private fun zeitKurz(ms: Long): String {
    val z = Instant.ofEpochMilli(ms).atZone(zone)
    return when (z.toLocalDate()) {
        Zeit.heute() -> z.format(DateTimeFormatter.ofPattern("HH:mm"))
        Zeit.heute().minusDays(1) -> "Gestern"
        else -> z.format(DateTimeFormatter.ofPattern("dd.MM.yy"))
    }
}

/** Profilbild; ohne Foto Initialen im Kreis (Containerfarbe oder Farbe der Person) */
@Composable
private fun ChatBild(name: String, groesse: Int = 44, mxc: String = "", farbe: Color? = null) {
    val i = name.split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1) }.uppercase()
    Box(Modifier.size(groesse.dp).clip(CircleShape).background(farbe ?: MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Text(i.ifEmpty { "?" }, color = if (farbe != null) Color.White else MaterialTheme.colorScheme.onPrimaryContainer, style = if (groesse > 32) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelSmall)
        if (mxc.isNotEmpty()) MatrixBild(mxc, 96, Modifier.matchParentSize())
    }
}

/** Bild vom Chat-Server; solange es lädt: nichts */
@Composable
private fun MatrixBild(mxc: String, groesse: Int, modifier: Modifier = Modifier) {
    val b by produceState<ImageBitmap?>(null, mxc) { value = Chat.bild(mxc, groesse) }
    b?.let { Image(it, null, modifier, contentScale = ContentScale.Crop) }
}

/** Liste der Chats; ein Chat öffnet sich auf derselben Seite (Zurück führt zur Liste) */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatSeite(zurueck: () -> Unit) {
    var offen by remember { mutableStateOf<String?>(null) }
    var suche by remember { mutableStateOf("") }
    var menue by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Melder.chatGelesen(Kern.ablage); Chat.schleife() }
    BackHandler(offen != null) { offen = null }
    offen?.let { id -> ChatRaum(id) { offen = null }; return }
    Seite("Chat", zurueck, aktualisieren = null) {
        item {
            OutlinedTextField(suche, { suche = it }, Modifier.fillMaxWidth(), placeholder = { Text("Chats durchsuchen") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (suche.isNotEmpty()) IconButton({ suche = "" }) { Icon(Icons.Rounded.Close, "Leeren") } }, singleLine = true, shape = RoundedCornerShape(28.dp))
        }
        if (Chat.fehler.isNotEmpty()) item { Text(Chat.fehler, color = MaterialTheme.colorScheme.error) }
        if (Chat.laedt && Chat.raeume.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        else if (Chat.raeume.isEmpty() && Chat.fehler.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Forum, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Text("Keine Chats", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(Matrix.suchen(Chat.raeume, suche), key = { it.id }) { r ->
            val l = r.nachrichten.lastOrNull()
            ListItem(
                modifier = Modifier.animateItem().clip(RoundedCornerShape(20.dp)).combinedClickable(onLongClick = { menue = r.id }) { offen = r.id },
                leadingContent = { ChatBild(r.name, mxc = r.bild) },
                headlineContent = { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (r.ungelesen > 0) FontWeight.Bold else null)
                    if (r.angepinnt) { Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.PushPin, "Angepinnt", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary) }
                    DropdownMenu(menue == r.id, { menue = null }) {
                        DropdownMenuItem({ Text(if (r.angepinnt) "Loslösen" else "Anpinnen") }, { menue = null; scope.launch { Chat.anpinnen(r) } },
                            leadingIcon = { Icon(Icons.Rounded.PushPin, null) })
                    }
                } },
                supportingContent = { l?.let { Text((if (r.gruppe) r.name(it.absender).substringBefore(" ") + ": " else "") + it.text, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        l?.let { Text(zeitKurz(it.zeit), style = MaterialTheme.typography.labelMedium, color = if (r.ungelesen > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (r.ungelesen > 0) Badge { Text("${r.ungelesen}") }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
        }
    }
}

/** Ein Gespräch: eigene Blasen in der Primärfarbe rechts, andere links, Eingabefeld mit Senden-Knopf unten */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatRaum(id: String, zurueck: () -> Unit) {
    val r = Chat.raeume.firstOrNull { it.id == id } ?: run { LaunchedEffect(Unit) { zurueck() }; return }
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    val liste = rememberLazyListState()
    val l = r.nachrichten.asReversed()
    val c = LocalContext.current
    val dunkel = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val farben = if (Kern.einstellungen.chatFarben) Kern.farbenPersonen else null
    LaunchedEffect(r.nachrichten.lastOrNull()?.id) { liste.animateScrollToItem(0); Chat.gelesen(r) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) { ChatBild(r.name, 36, r.bild); Spacer(Modifier.width(12.dp)); Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                navigationIcon = { IconButton(zurueck) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } })
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), enabled = !r.verschluesselt, maxLines = 5, shape = RoundedCornerShape(28.dp),
                        placeholder = { Text(if (r.verschluesselt) "Verschlüsselter Chat – nur in Element" else "Nachricht") })
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton({ val t = text.trim(); text = ""; scope.launch { Chat.senden(r.id, t) } }, Modifier.size(56.dp), enabled = text.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Rounded.Send, "Senden")
                    }
                }
            }
        }) { rand ->
        LazyColumn(Modifier.fillMaxSize().padding(rand), state = liste, reverseLayout = true, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(l.size, key = { l[it].id }) { i ->
                val n = l[i]; val aelter = l.getOrNull(i + 1); val neuer = l.getOrNull(i - 1)
                val eigen = n.absender == Chat.ich
                val erste = aelter?.absender != n.absender; val letzte = neuer?.absender != n.absender
                val name = r.name(n.absender)
                val farbe = if (eigen) null else farben?.let { Aufgaben.farbeFuer(name, it) }?.let(::hexFarbe)
                val hinten = if (eigen) MaterialTheme.colorScheme.primary else farbe?.let { if (dunkel) it.copy(alpha = 0.5f) else it } ?: MaterialTheme.colorScheme.surfaceContainerHighest
                val vorne = if (eigen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                val form = RoundedCornerShape(20.dp, 20.dp, if (eigen || !letzte) 20.dp else 4.dp, if (!eigen || !letzte) 20.dp else 4.dp)
                Column {
                    if (aelter == null || n.zeit - aelter.zeit > 3_600_000) Text(zeitKurz(n.zeit), Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Row(Modifier.fillMaxWidth().padding(top = if (erste) 6.dp else 0.dp), horizontalArrangement = if (eigen) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
                        if (!eigen && r.gruppe) { Box(Modifier.size(28.dp)) { if (letzte) ChatBild(name, 28, r.bilder[n.absender].orEmpty(), farbe) }; Spacer(Modifier.width(6.dp)) }
                        Column(Modifier.widthIn(max = 300.dp), horizontalAlignment = if (eigen) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (!eigen && r.gruppe && erste) Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
                            n.anhang?.let { a ->
                                val bild = if (a.art == "bild") a.mxc else a.vorschau
                                val tippen = Modifier.clickable { scope.launch { Chat.oeffnen(c, a) } }
                                if (bild.isNotEmpty()) {
                                    val seiten = if (a.breite > 0 && a.hoehe > 0) a.breite.toFloat() / a.hoehe else 4f / 3
                                    Box(Modifier.width(if (seiten >= 1) 240.dp else 240.dp * seiten).aspectRatio(seiten).clip(form).background(MaterialTheme.colorScheme.surfaceContainerHighest).then(tippen), contentAlignment = Alignment.Center) {
                                        MatrixBild(bild, 800, Modifier.matchParentSize())
                                        if (a.art == "video") Icon(Icons.Rounded.PlayCircle, "Video abspielen", Modifier.size(48.dp), tint = Color.White)
                                    }
                                } else Surface(tippen, color = hinten, shape = form) {
                                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(if (a.art == "audio") Icons.Rounded.GraphicEq else Icons.Rounded.Description, null, tint = vorne)
                                        Text(a.name.ifEmpty { a.text }, color = vorne, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (n.anhang == null || n.anhang.beschriftung.isNotEmpty()) Surface(color = hinten, shape = form) {
                                Text(n.text, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyLarge,
                                    fontStyle = if (n.verschluesselt) FontStyle.Italic else null, color = vorne)
                            }
                        }
                    }
                }
            }
        }
    }
}
