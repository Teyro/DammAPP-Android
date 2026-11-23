package de.oejendorferdamm.dammapp.ui

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.oejendorferdamm.dammapp.daten.Kern
import de.oejendorferdamm.dammapp.kern.*
import kotlinx.coroutines.launch
import java.util.Locale

/** Vorlesen (Briefing, Gute Nacht) */
object Sprecher {
    private var tts: TextToSpeech? = null
    fun sprechen(c: android.content.Context, text: String) {
        val t = tts
        if (t == null) tts = TextToSpeech(c.applicationContext) { if (it == TextToSpeech.SUCCESS) { tts?.language = Locale.GERMANY; tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dammapp") } }
        else t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dammapp")
    }
    fun stopp() { tts?.stop() }
}

@Composable
fun StartSeite(oeffne: (Bereich) -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val e = Kern.einstellungen
    val jetzt = Zeit.jetzt()
    val gruss = if (jetzt.hour < 11) "Guten Morgen" else if (jetzt.hour < 18) "Hallo" else "Guten Abend"
    val w = Kern.wetter
    Seite(gruss, null, untertitel = Zeit.datumLang(jetzt.toLocalDate()), aktionen = {
        w?.jetzt?.let { Text("${wetterEmoji(w.jetztCode ?: 3)} ${Math.round(it)}°", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 4.dp)) }
        IconButton({ oeffne(Bereich.EINSTELLUNGEN) }) { Icon(Icons.Rounded.Settings, "Einstellungen") }
    }, unten = {
        BottomAppBar(actions = {
            IconButton({ oeffne(Bereich.UNTIS) }) { Icon(Icons.Rounded.ManageSearch, "Stundenpläne suchen") }
            TextButton({ Sprecher.sprechen(c, Kern.briefing()) }) { Icon(Icons.Rounded.RecordVoiceOver, null); Spacer(Modifier.width(6.dp)); Text("Briefing") }
            if (Kern.ha.eingerichtet) TextButton({ scope.launch { val t = Kern.guteNacht(); Kern.meldung = t; Sprecher.sprechen(c, t) } }) {
                Icon(Icons.Rounded.Bedtime, null); Spacer(Modifier.width(6.dp)); Text("Gute Nacht") }
        }, floatingActionButton = {
            FloatingActionButton({ oeffne(Bereich.STUNDENPLAN) }) { Icon(Icons.Rounded.CalendarViewWeek, "Stundenplan") }
        })
    }) {
        val ordnung = Startseite.ordnung(e.kachelReihenfolge, e.kachelAus, Kern.inDerSchule)
        if (e.vertretung && e.vertretungLink.isEmpty() && e.unterricht.isEmpty() && !e.untisAn) item {
            Karte(Icons.Rounded.WavingHand, MaterialTheme.colorScheme.primary, "Willkommen") {
                Text("Trag in den Einstellungen dein Kürzel, den Ordner mit dem Vertretungsplan und WebUntis ein – dann steht hier dein Schultag.")
                FilledTonalButton({ oeffne(Bereich.EINSTELLUNGEN) }) { Text("Einrichten") }
            }
        }
        for (k in ordnung) when (k) {
            "schule" -> item { SchulKarte(oeffne) }
            "mensa" -> if (e.mensa) item { MensaKarte(oeffne) }
            "zahlen" -> item { ZahlenReihe(oeffne) }
            "schultermine" -> if (Kern.schulTermine.isNotEmpty()) item { SchulTermineKarte() }
            "wetter" -> if (w?.heute != null) item { WetterKarte(w) }
            "termine" -> if (e.termine && Kern.termine.isNotEmpty()) item { TermineKarte() }
        }
    }
}

@Composable
private fun SchulKarte(oeffne: (Bereich) -> Unit) {
    val e = Kern.einstellungen
    val tag = Kern.schulTag
    val bloecke = Kern.schultag(tag, ganz = true)
    val jetzt = Zeit.jetzt()
    Karte(Icons.Rounded.School, Farben.vertretung, "Schule " + Kern.schulTagName, onClick = { oeffne(Bereich.STUNDENPLAN) }) {
        val v = Kern.vertretung
        if (v?.fehlt == true) Text("Du bist als fehlend eingetragen", color = Farben.fehlt, fontWeight = FontWeight.SemiBold)
        if (v?.ausserHaus == true) Text("Du bist außer Haus eingetragen", color = Farben.aufsicht, fontWeight = FontWeight.SemiBold)
        if (bloecke.any { it.meins }) {
            bloecke.forEach { b ->
                StundenZeile(b, if (b.art == Block.Art.PAUSE || b.art == Block.Art.AUFSICHT) Kern.wetter?.pause(b.von, b.bis) else null,
                    laeuft = tag == Zeit.heute() && !b.von.isAfter(jetzt) && jetzt.isBefore(b.bis))
            }
        } else if (v != null) {
            if (v.stunden.isEmpty() && v.pausen.isEmpty() && !v.fehlt) Text("✅ Keine Vertretung für dich", color = Farben.gut, fontWeight = FontWeight.Medium)
            v.stunden.forEach { s -> Zeile(Stundenplan.stunde(s.stunde) + ".", Farben.vertretung, VertretungsPlan.titel(s), Vertretung.haus(s.klasse, e.haeuser).ifEmpty { null }) }
            v.pausen.forEach { p -> Zeile(p.pause.take(1) + ".P", Farben.aufsicht, if (p.reserve) "Aufsicht (Reserve)" else "Hofaufsicht", "${p.pause} · ${p.ort}") }
        } else Text("Noch kein Stundenplan und kein Vertretungsplan.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        FehlerText(Kern.vertretungFehler)
        if (e.vertretung && e.vertretungLink.isNotEmpty()) TextButton({ oeffne(Bereich.VERTRETUNG) }, contentPadding = PaddingValues(0.dp)) {
            Text("Vertretungsplan" + (Kern.vertretung?.alleHinweise?.takeIf { it.isNotEmpty() }?.let { " · ${it.size} Bemerkungen" } ?: "") + " ›") }
        val packen = Kern.mitnehmen
        if (packen.isNotEmpty()) Zeile("🎒", Farben.aufsicht, (if (Kern.schulTagName == "heute") "Mitnehmen" else "${Kern.schulTagName.replaceFirstChar { it.uppercase() }} mitnehmen") + ": " + packen.joinToString(", "))
        if (Kern.obstSchultag) Zeile("🍎", Farben.obst, if (Kern.schulTagName == "heute") "Heute kommt Schulobst" else "Schulobst kommt")
    }
}

@Composable
private fun MensaKarte(oeffne: (Bereich) -> Unit) {
    val e = Kern.einstellungen
    Karte(Icons.Rounded.Restaurant, Color(0xFF0B8043), "Mensa " + Kern.essenTagName, onClick = { oeffne(Bereich.MENSA) },
        rechts = { Kern.mensaPlan?.guthaben?.let { Text("%.2f €".format(it).replace('.', ','), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) } }) {
        val t = Kern.mensaTag
        when {
            t?.frei != null -> Text("Mensa geschlossen" + (t.frei?.takeIf { it.isNotEmpty() }?.let { " – $it" } ?: ""))
            t != null -> t.haupt(e.favoriten, e.meiden, e.lieblingsGerichte, e.nichtLecker)?.let { g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.emoji, fontSize = 28.sp); Spacer(Modifier.width(10.dp))
                    Column { Text(g.text, fontWeight = FontWeight.SemiBold); Text((if (g.bestellt) "Bestellt · " else "") + g.menue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            else -> FehlerText(Kern.mensaFehler.ifEmpty { "Noch kein Speiseplan geladen" })
        }
    }
}


@Composable
private fun ZahlenReihe(oeffne: (Bereich) -> Unit) {
    val e = Kern.einstellungen
    if (!e.abwesenheiten && !e.stoerungen && !e.aufgaben) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
        // morgens IServ (ohne Ausgegraute), ab Vertretungsplan „krank + außer Haus“ laut Plan
        if (e.abwesenheiten) Kern.abwesendZahl.let { (zahl, wort) ->
            ZahlKachel(Icons.Rounded.PersonOff, Farben.fehlt, if (Kern.abwesend == null && !wort.startsWith("krank")) (if (Kern.abwesendFehler.isEmpty()) "…" else "!") else zahl,
                wort, Modifier.weight(1f).fillMaxHeight()) { oeffne(Bereich.ABWESEND) }
        }
        if (e.stoerungen) ZahlKachel(Icons.Rounded.Build, Farben.aufsicht, Kern.stoerungen?.offen?.size?.toString() ?: if (Kern.stoerungenFehler.isEmpty()) "…" else "!",
            "Störungen", Modifier.weight(1f).fillMaxHeight()) { oeffne(Bereich.STOERUNGEN) }
        if (e.aufgaben) ZahlKachel(Icons.Rounded.Checklist, Farben.gut, Kern.aufgaben?.offen?.size?.toString() ?: if (Kern.aufgabenFehler.isEmpty()) "…" else "!",
            "Aufgaben", Modifier.weight(1f).fillMaxHeight()) { oeffne(Bereich.AUFGABEN) }
    }
}

@Composable
private fun SchulTermineKarte() {
    Karte(Icons.Rounded.Event, Color(0xFF7B1FA2), "Schultermine") {
        Kern.schulTermine.take(4).forEach { t -> Zeile(SchulKalender.datumText(t.datum).take(6), Color(0xFF7B1FA2), t.titel) }
    }
}

@Composable
private fun WetterKarte(w: Wetter) {
    Karte(Icons.Rounded.WbSunny, Color(0xFF00897B), "Wetter " + Kern.einstellungen.ortName) {
        listOfNotNull(w.heute?.let { "Heute" to it }, w.morgen?.let { "Morgen" to it }).forEach { (n, t) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(wetterEmoji(t.code), fontSize = 26.sp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("$n: ${Math.round(t.min)}–${Math.round(t.max)}°", fontWeight = FontWeight.SemiBold)
                    Text((Wetter.beschreibung[t.code] ?: "") + if (t.regen >= 30) " · Regen ${t.regen} %" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        w.heute?.let { Wetter.hinweise(it, false) }?.takeIf { it.isNotEmpty() }?.let { Text("💡 " + it.joinToString(" · "), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun TermineKarte() {
    Karte(Icons.Rounded.CalendarMonth, Color(0xFFD93025), "Termine") {
        Kern.termine.take(6).forEach { t ->
            val tag = SchulKalender.datumText(t.start.toLocalDate().toString())
            Zeile(if (t.ganztags) "📅" else Zeit.uhrzeit(t.start), Color(0xFFD93025), t.titel, tag)
        }
    }
}
