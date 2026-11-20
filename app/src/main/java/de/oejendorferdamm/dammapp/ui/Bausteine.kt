package de.oejendorferdamm.dammapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.oejendorferdamm.dammapp.daten.Kern
import de.oejendorferdamm.dammapp.kern.Block
import de.oejendorferdamm.dammapp.kern.PausenWetter
import de.oejendorferdamm.dammapp.kern.Zeit
import kotlinx.coroutines.launch

/** Eine Seite mit großer Titelleiste, Zurück und Ziehen zum Aktualisieren */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Seite(titel: String, zurueck: (() -> Unit)?, untertitel: String? = null, aktionen: @Composable RowScope.() -> Unit = {},
          aktualisieren: (suspend () -> Unit)? = { Kern.aktualisieren(true) }, unten: @Composable () -> Unit = {}, inhalt: LazyListScope.() -> Unit) {
    val leiste = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val scope = rememberCoroutineScope()
    var zieht by remember { mutableStateOf(false) }
    Scaffold(Modifier.nestedScroll(leiste.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(title = { Column { Text(titel, maxLines = 1, overflow = TextOverflow.Ellipsis); untertitel?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) } } },
                navigationIcon = { zurueck?.let { IconButton(it) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Zurück") } } },
                actions = aktionen, scrollBehavior = leiste)
        },
        bottomBar = unten) { rand ->
        val liste = @Composable {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = rand.calculateTopPadding() + 4.dp, bottom = rand.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), content = inhalt)
        }
        if (aktualisieren == null) liste() else
            PullToRefreshBox(isRefreshing = zieht || Kern.laedt, onRefresh = { scope.launch { zieht = true; aktualisieren(); zieht = false } }) { liste() }
    }
}

/** Karte mit farbigem Symbol und Titel (wie die Kacheln der iOS-App) */
@Composable
fun Karte(symbol: ImageVector, farbe: Color, titel: String, onClick: (() -> Unit)? = null, rechts: @Composable () -> Unit = {}, inhalt: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clip(CardDefaults.elevatedShape).clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FarbSymbol(symbol, farbe)
                Spacer(Modifier.width(10.dp))
                Text(titel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                rechts()
            }
            inhalt()
        }
    }
}

/** Symbol auf farbigem Quadrat (wie Einstellungen auf iOS/Android) */
@Composable
fun FarbSymbol(symbol: ImageVector, farbe: Color, groesse: Int = 30) {
    Box(Modifier.size(groesse.dp).clip(RoundedCornerShape((groesse * 0.28f).dp)).background(farbe), contentAlignment = Alignment.Center) {
        Icon(symbol, null, tint = Color.White, modifier = Modifier.size((groesse * 0.6f).dp))
    }
}

/** Marke links (40 dp, getönt), Titel und Untertitel */
@Composable
fun Zeile(marke: String, farbe: Color, titel: String, unter: String? = null, onClick: (() -> Unit)? = null, rechts: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(farbe.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Text(marke, color = farbe, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(titel, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            unter?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        rechts()
    }
}

/** Ein Abschnitt des Schultags: reguläres neutral, Abweichungen farbig hinterlegt; Pausenwetter rechts */
@Composable
fun StundenZeile(b: Block, wetter: PausenWetter?, laeuft: Boolean) {
    val grau = MaterialTheme.colorScheme.onSurfaceVariant
    val farbe = when (b.art) { Block.Art.VERTRETUNG -> Farben.vertretung; Block.Art.AUFSICHT -> Farben.aufsicht; else -> grau }
    val abweichend = b.art == Block.Art.VERTRETUNG || b.art == Block.Art.AUFSICHT || b.ersetzt != null
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(when { abweichend -> farbe.copy(alpha = 0.10f); laeuft -> MaterialTheme.colorScheme.secondaryContainer; else -> Color.Transparent }).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(farbe.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Text(b.marke, color = farbe, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            b.ersetzt?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = grau, textDecoration = TextDecoration.LineThrough) }
            val text = if (b.art == Block.Art.AUFSICHT) (if (b.text.startsWith("Reserve")) "Aufsicht (Reserve) · " else "Hofaufsicht · ") + b.text.removePrefix("Aufsicht ").removePrefix("Reserve ") else b.text
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                color = if (b.art == Block.Art.FREI || b.art == Block.Art.PAUSE) grau else MaterialTheme.colorScheme.onSurface)
            Text(Zeit.uhrzeit(b.von) + "–" + Zeit.uhrzeit(b.bis), style = MaterialTheme.typography.bodySmall, color = grau)
            wetter?.let { Text((if (it.drinnen) "☔ " else "🚶 ") + it.text, style = MaterialTheme.typography.labelMedium, color = if (it.drinnen) Farben.vertretung else grau) }
        }
        b.aufteilung?.takeIf { it.isNotEmpty() }?.let {
            Text("A " + it.joinToString(" "), color = Farben.aufsicht, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(CircleShape).background(Farben.aufsicht.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        wetter?.let { w -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 6.dp)) {
            Text(wetterEmoji(w.code), fontSize = 22.sp)
            w.temp?.let { Text("${Math.round(it)}°", style = MaterialTheme.typography.labelMedium, color = grau) }
        } }
    }
}

fun wetterEmoji(code: Int) = when (code) {
    0, 1 -> "☀️"; 2 -> "⛅"; 3 -> "☁️"; 45, 48 -> "🌫️"; in 51..57 -> "🌦️"; in 61..67, in 80..82 -> "🌧️"; in 71..77, 85, 86 -> "🌨️"; in 95..99 -> "⛈️"; else -> "☁️"
}

/** Kleine Zahl-Kachel (Kinder fehlen, Störungen, Aufgaben) */
@Composable
fun ZahlKachel(symbol: ImageVector, farbe: Color, zahl: String, text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ElevatedCard(modifier.clip(CardDefaults.elevatedShape).clickable(onClick = onClick), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp).fillMaxWidth()) {
            Icon(symbol, null, tint = farbe)
            Text(zahl, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Eingabe für eine Antwort mit Senden-Knopf */
@Composable
fun AntwortFeld(text: String, onText: (String) -> Unit, sendet: Boolean, senden: () -> Unit) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(text, onText, Modifier.weight(1f), placeholder = { Text("Antworten …") }, maxLines = 5, shape = RoundedCornerShape(24.dp))
        Spacer(Modifier.width(8.dp))
        FilledIconButton(senden, enabled = !sendet && text.isNotBlank(), modifier = Modifier.size(52.dp)) {
            if (sendet) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Rounded.Send, "Senden")
        }
    }
}

/** Hinweis bei Fehlern */
@Composable
fun FehlerText(t: String) {
    if (t.isNotEmpty()) Text("⚠️ $t", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}
