package de.oejendorferdamm.dammapp.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.oejendorferdamm.dammapp.BuildConfig
import de.oejendorferdamm.dammapp.daten.Einstellungen
import de.oejendorferdamm.dammapp.daten.Geheimnisse
import de.oejendorferdamm.dammapp.daten.HAAktion
import de.oejendorferdamm.dammapp.daten.Kern
import de.oejendorferdamm.dammapp.kern.IServ
import de.oejendorferdamm.dammapp.kern.Startseite
import de.oejendorferdamm.dammapp.kern.Vertretung
import de.oejendorferdamm.dammapp.update.Update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Unterseite(val titel: String, val symbol: ImageVector, val farbe: Color) {
    AUSSEHEN("Aussehen und Startseite", Icons.Rounded.Palette, Color(0xFFD81B60)),
    SCHULE("Schule", Icons.Rounded.School, Color(0xFF1A73E8)),
    ESSEN("Mensa, Obst und Termine", Icons.Rounded.Restaurant, Color(0xFF0B8043)),
    ISERV("IServ und Edupool", Icons.Rounded.Groups, Color(0xFF00897B)),
    MITTEILUNGEN("Mitteilungen", Icons.Rounded.NotificationsActive, Color(0xFFD93025)),
    ZUHAUSE("Home Assistant", Icons.Rounded.Home, Color(0xFFE8710A)),
    ORT("Ort und Kalender", Icons.Rounded.Place, Color(0xFF5F6368)),
    UEBER("Über DammAPP", Icons.Rounded.Info, Color(0xFF3949AB)),
}

/** Einstellungen wie auf dem Gerät: Übersicht mit farbigen Symbolen, darunter je eine Unterseite */
@Composable
fun EinstellungenSeite(zurueck: () -> Unit) {
    var seite by remember { mutableStateOf<Unterseite?>(null) }
    BackHandler(seite != null) { seite = null }
    val s = seite
    if (s == null) {
        Seite("Einstellungen", zurueck, aktualisieren = null) {
            item {
                ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Unterseite.entries.forEach { u ->
                        ListItem(headlineContent = { Text(u.titel) }, supportingContent = { Text(zusammenfassung(u), maxLines = 1) },
                            leadingContent = { FarbSymbol(u.symbol, u.farbe, 34) }, trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent), modifier = Modifier.clickable { seite = u })
                    }
                }
            }
        }
    } else Seite(s.titel, { seite = null }, aktualisieren = null) {
        when (s) {
            Unterseite.AUSSEHEN -> aussehen()
            Unterseite.SCHULE -> schule()
            Unterseite.ESSEN -> essen()
            Unterseite.ISERV -> iserv()
            Unterseite.MITTEILUNGEN -> mitteilungen()
            Unterseite.ZUHAUSE -> zuhause()
            Unterseite.ORT -> ort()
            Unterseite.UEBER -> ueber()
        }
    }
}

private fun zusammenfassung(u: Unterseite): String {
    val e = Kern.einstellungen
    fun an(b: Boolean) = if (b) "an" else "aus"
    return when (u) {
        Unterseite.AUSSEHEN -> "Material You ${an(e.dynamischeFarben)} · ${Startseite.ordnung(e.kachelReihenfolge, e.kachelAus, true).size} Kacheln"
        Unterseite.SCHULE -> listOf(e.kuerzel.ifEmpty { "kein Kürzel" }, "WebUntis ${an(e.untisAn)}", "${e.unterricht.size} Stunden").joinToString(" · ")
        Unterseite.ESSEN -> "Mensa ${an(e.mensa)} · Obst ${an(e.obst)} · Schultermine ${an(e.schulKalender.isNotEmpty())}"
        Unterseite.ISERV -> "Abwesenheiten ${an(e.abwesenheiten)} · Störungen ${an(e.stoerungen)} · Edupool ${an(e.aufgaben)}"
        Unterseite.MITTEILUNGEN -> "Neuigkeiten ${an(e.mitteilungen)} · Vertretung ${an(e.vertretungDa || e.vertretungMelden)}"
        Unterseite.ZUHAUSE -> if (Kern.ha.eingerichtet) "Gute Nacht: ${e.nacht.size} Aktionen" else "nicht eingerichtet"
        Unterseite.ORT -> "${e.ortName} · ${e.bundesland} · Kalender ${an(e.termine)}"
        Unterseite.UEBER -> "Version ${BuildConfig.VERSION_NAME}"
    }
}

// MARK: Bausteine

private fun androidx.compose.foundation.lazy.LazyListScope.gruppe(titel: String, hinweis: String? = null, inhalt: @Composable ColumnScope.() -> Unit) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(titel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = inhalt)
            }
            hinweis?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp)) }
        }
    }
}

@Composable private fun Schalter(titel: String, an: Boolean, text: String? = null, f: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(titel, style = MaterialTheme.typography.bodyLarge); text?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Switch(an, f)
    }
}

/** Textfeld, das erst beim Verlassen bzw. Tippen speichert */
@Composable private fun Feld(titel: String, wert: String, geheim: Boolean = false, mehrzeilig: Boolean = false, tastatur: KeyboardType = KeyboardType.Text, f: (String) -> Unit) {
    var t by remember(wert) { mutableStateOf(wert) }
    OutlinedTextField(t, { t = it; f(it) }, Modifier.fillMaxWidth(), label = { Text(titel) }, singleLine = !mehrzeilig, minLines = if (mehrzeilig) 3 else 1,
        visualTransformation = if (geheim) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (geheim) KeyboardType.Password else tastatur))
}

private fun e(f: (Einstellungen) -> Einstellungen) = Kern.aendere(f)
private fun g(f: (Geheimnisse) -> Geheimnisse) = Kern.aendereGeheim(f)

// MARK: Seiten

private fun androidx.compose.foundation.lazy.LazyListScope.aussehen() {
    gruppe("Farben", "Material You übernimmt die Farben deines Hintergrundbilds (ab Android 12). Aus: DammAPP-Blau.") {
        Schalter("Material You", Kern.einstellungen.dynamischeFarben) { an -> e { it.copy(dynamischeFarben = an) } }
    }
    gruppe("Startseite", "Schalter: Kachel zeigen oder ausblenden. Pfeile: verschieben. Ohne eigene Reihenfolge steht in der Schulzeit die Schule oben, sonst die Termine.") {
        val namen = mapOf("schule" to "Schule (Stunden und Vertretung)", "mensa" to "Mensa", "zahlen" to "Fehlen · Störungen · Aufgaben", "schultermine" to "Schultermine", "wetter" to "Wetter", "termine" to "Termine")
        val r = Startseite.vollstaendig(Kern.einstellungen.kachelReihenfolge, Kern.inDerSchule)
        r.forEachIndexed { i, k ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(namen[k] ?: k, Modifier.weight(1f))
                IconButton({ if (i > 0) e { it.copy(kachelReihenfolge = r.toMutableList().apply { add(i - 1, removeAt(i)) }) } }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "nach oben") }
                IconButton({ if (i < r.lastIndex) e { it.copy(kachelReihenfolge = r.toMutableList().apply { add(i + 1, removeAt(i)) }) } }, enabled = i < r.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "nach unten") }
                Switch(k !in Kern.einstellungen.kachelAus, { an -> e { x -> x.copy(kachelAus = if (an) x.kachelAus - k else x.kachelAus + k) } })
            }
        }
        TextButton({ e { it.copy(kachelReihenfolge = emptyList(), kachelAus = emptyList()) } }) { Text("Automatisch anordnen") }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.schule() {
    val x = Kern.einstellungen
    gruppe("Vertretungsplan", "Cloud-Ordner mit den Word-Dateien des Vertretungsplans. Gelesen wird nur, was für dein Kürzel drinsteht. Häuser je Zeile, z. B. „Haus 1: 1a, 1b, 2a“.") {
        Schalter("Vertretungsplan zeigen", x.vertretung) { an -> e { it.copy(vertretung = an) } }
        Feld("Dein Kürzel", x.kuerzel) { v -> e { it.copy(kuerzel = v.trim()) } }
        Feld("Freigabe-Link des Ordners", x.vertretungLink, tastatur = KeyboardType.Uri) { v -> e { it.copy(vertretungLink = v.trim()) } }
        Feld("Passwort der Freigabe (falls nötig)", Kern.geheim.freigabe, geheim = true) { v -> g { it.copy(freigabe = v) } }
        Feld("Häuser", Vertretung.text(x.haeuser), mehrzeilig = true) { v -> e { it.copy(haeuser = Vertretung.haeuser(v)) } }
    }
    gruppe("Schultag", "Die dauerhafte Benachrichtigung zeigt, was gerade läuft und was danach kommt – wie die Live-Aktivität auf dem iPhone.") {
        Schalter("Schultag als Benachrichtigung", x.schulBenachrichtigung) { an -> e { it.copy(schulBenachrichtigung = an) } }
        Schalter("5 Minuten vor der Hofaufsicht erinnern", x.aufsichtErinnern) { an -> e { it.copy(aufsichtErinnern = an) } }
        Schalter("„Mitnehmen“ (Sportzeug, Regenjacke …)", x.mitnehmen) { an -> e { it.copy(mitnehmen = an) } }
    }
    gruppe("WebUntis", "In WebUntis auf deinen Namen → Freigaben → „Zugriff über Untis Mobile“ → Anzeigen. Url, Schule, Benutzer und Schlüssel abtippen – damit klappt es auch mit zweitem Faktor, ohne Passwort. Von Hand Eingetragenes bleibt – Untis füllt nur freie Stunden. Fällt WebUntis aus, nimmt die App die tägliche Untis-Sicherung aus dem Schul-Ordner.") {
        Schalter("WebUntis verbinden", x.untisAn) { an -> e { it.copy(untisAn = an) } }
        if (x.untisAn) {
            Feld("Url (z. B. hh5586.webuntis.com)", x.untisLink, tastatur = KeyboardType.Uri) { v -> e { it.copy(untisLink = v.trim()) } }
            Feld("Schule (z. B. hh5586)", x.untisSchule) { v -> e { it.copy(untisSchule = v.trim()) } }
            Feld("Benutzer", x.untisBenutzer) { v -> e { it.copy(untisBenutzer = v.trim()) } }
            Feld("Schlüssel (aus Untis Mobile)", Kern.geheim.untisSchluessel, geheim = true) { v -> g { it.copy(untisSchluessel = v) } }
            Feld("… oder Passwort (ohne zweiten Faktor)", Kern.geheim.untisPasswort, geheim = true) { v -> g { it.copy(untisPasswort = v) } }
            Schalter("Grundwoche als meinen Stundenplan übernehmen", x.untisUebernehmen) { an -> e { it.copy(untisUebernehmen = an) } }
            Feld("Schul-Ordner-Passwort (Untis-Sicherung)", Kern.geheim.schulOrdner, geheim = true) { v -> g { it.copy(schulOrdner = v) } }
            val scope = rememberCoroutineScope()
            FilledTonalButton({ scope.launch { withContext(Dispatchers.IO) { Kern.untisLaden(true) }; Kern.meldung = Kern.untisFehler.ifEmpty { "WebUntis geladen: ${Kern.einstellungen.unterricht.size} Stunden" } } }) { Text("Jetzt laden") }
            FehlerText(Kern.untisFehler)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.essen() {
    val x = Kern.einstellungen
    gruppe("Mensa", "Speiseplan aus dem Kantinen-Portal (PCA) – dieselben Zugangsdaten wie im Browser. Bestellte Gerichte stehen vorne.") {
        Schalter("Speiseplan zeigen", x.mensa) { an -> e { it.copy(mensa = an) } }
        if (x.mensa) {
            Feld("Adresse des Portals (https://…)", x.mensaAdresse, tastatur = KeyboardType.Uri) { v -> e { it.copy(mensaAdresse = v.trim()) } }
            Feld("Benutzer", x.mensaBenutzer) { v -> e { it.copy(mensaBenutzer = v.trim()) } }
            Feld("Passwort", Kern.geheim.mensa, geheim = true) { v -> g { it.copy(mensa = v) }; Kern.ablage.text("mensaAbgelehnt", null) }
            Feld("Lieblingsessen (Stichwörter, z. B. Pizza, Nudeln)", x.favoriten) { v -> e { it.copy(favoriten = v) } }
            Feld("Meiden (z. B. Schwein, Fisch)", x.meiden) { v -> e { it.copy(meiden = v) } }
        }
    }
    gruppe("Schulobst", "Öffentlicher IServ-Kalender mit den Liefertagen (Kalender → Teilen → öffentlicher Link).") {
        Schalter("Schulobst zeigen", x.obst) { an -> e { it.copy(obst = an) } }
        if (x.obst) Feld("Kalender-Link (ICS)", x.obstKalender, tastatur = KeyboardType.Uri) { v -> e { it.copy(obstKalender = v.trim()) } }
    }
    gruppe("Schultermine", "Öffentlicher Kalender der Schule (z. B. aus IServ „Abonnieren“). Leer = aus.") {
        Feld("Kalender-Link (ICS oder webcal)", x.schulKalender, tastatur = KeyboardType.Uri) { v -> e { it.copy(schulKalender = v.trim()) } }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.iserv() {
    val x = Kern.einstellungen
    gruppe("IServ", "Mit 2FA-Schlüssel (Text unter dem QR-Code beim Einrichten der Zwei-Faktor-Anmeldung) erzeugt die App die Codes selbst – sonst fragt sie danach. Passwörter liegen verschlüsselt auf dem Gerät. Abwesenheiten werden nur angezeigt.") {
        Feld("Adresse (z. B. schule.de)", x.iservServer, tastatur = KeyboardType.Uri) { v -> e { it.copy(iservServer = IServ.bereinige(v)) } }
        Feld("Benutzer", x.iservBenutzer) { v -> e { it.copy(iservBenutzer = v.trim()) }; Kern.ablage.text("iservAbgelehnt", null) }
        Feld("Passwort", Kern.geheim.iserv, geheim = true) { v -> g { it.copy(iserv = v) }; Kern.ablage.text("iservAbgelehnt", null) }
        Feld("2FA-Schlüssel (optional)", Kern.geheim.totp, geheim = true) { v -> g { it.copy(totp = v.replace(" ", "")) } }
        Schalter("Abwesenheiten (Kinder fehlen)", x.abwesenheiten) { an -> e { it.copy(abwesenheiten = an) } }
        if (x.abwesenheiten) {
            Feld("Nur diese Klassen (z. B. 2a, 2b) – leer = alle", x.abwesendKlassen) { v -> e { it.copy(abwesendKlassen = v) } }
            Schalter("Sichtschutz im Widget (nur Vorname)", x.sichtschutz) { an -> e { it.copy(sichtschutz = an) } }
        }
        Schalter("Störungsmeldungen", x.stoerungen) { an -> e { it.copy(stoerungen = an) } }
        val scope = rememberCoroutineScope()
        TextButton({ e { it.copy(abwesendQuelle = null, stoerungQuelle = null) }; scope.launch { Kern.moduleLaden(true) } }) { Text("Neu suchen (Module erkunden)") }
    }
    gruppe("Edupool-Aufgaben", "Verbinden öffnet die Freigabeseite von Edupool Boards, der Code ist schon eingetragen: anmelden, „Lesen“ anhaken – zum Antworten auf Karten auch „erstellen, ändern“ –, Laufzeit wählen (bis 90 Tage), bestätigen und zurück in die App. Dein Passwort sieht die App nie.") {
        val c = LocalContext.current
        val scope = rememberCoroutineScope()
        fun zeigen(u: String) = runCatching { CustomTabsIntent.Builder().build().launchUrl(c, Uri.parse(u)) }
        val code = Kern.edupoolCode
        when {
            code != null -> {
                Text("Warte auf Bestätigung … Code $code", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ Kern.edupoolSeite?.let { zeigen(it) } }) { Text("Seite wieder öffnen") }
                    TextButton({ Kern.edupoolCode = null }) { Text("Abbrechen") }
                }
            }
            Kern.geheim.edupool.isEmpty() -> Button({ scope.launch { Kern.edupoolVerbinden { zeigen(it) } } }) { Text("Mit Edupool Boards verbinden") }
            else -> {
                Schalter("Aufgaben zeigen", x.aufgaben) { an -> e { it.copy(aufgaben = an) } }
                Feld("Kartenfarbe deiner Aufgaben", x.aufgabenFarbe) { v -> e { it.copy(aufgabenFarbe = v.trim()) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ scope.launch { Kern.edupoolVerbinden { zeigen(it) } } }) { Text("Neu verbinden") }
                    TextButton({ g { it.copy(edupool = "") }; e { it.copy(aufgaben = false) }; Kern.aufgaben = null }) { Text("Trennen", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.mitteilungen() {
    val x = Kern.einstellungen
    gruppe("Neuigkeiten", "Ohne Push-Server fragt die App selbst nach: etwa stündlich, fest um 6:45 und nach 13:30, an Schultagen bis 8 Uhr alle 15 Minuten. Android bestimmt den genauen Zeitpunkt.") {
        Schalter("Störungen, Aufgaben und Kommentare melden", x.mitteilungen) { an -> e { it.copy(mitteilungen = an) } }
    }
    gruppe("Vertretungsplan", "„Dringend“ meldet sich mit Ton auch bei „Bitte nicht stören“-Ausnahmen für wichtige Mitteilungen.") {
        Schalter("Neuer Plan ist da", x.vertretungDa) { an -> e { it.copy(vertretungDa = an) } }
        Schalter("Plan betrifft mich", x.vertretungMelden) { an -> e { it.copy(vertretungMelden = an) } }
        Schalter("Als dringend melden", x.vertretungDringend) { an -> e { it.copy(vertretungDringend = an) } }
    }
    gruppe("System") {
        val c = LocalContext.current
        FilledTonalButton({ c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)) }) { Text("Mitteilungen in Android einstellen") }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.zuhause() {
    val x = Kern.einstellungen
    gruppe("Verbindung", "Token: in Home Assistant unten links auf deinen Namen → Sicherheit → Langlebige Zugriffstoken.") {
        Feld("Adresse (https://…)", x.haAdresse, tastatur = KeyboardType.Uri) { v -> e { it.copy(haAdresse = v.trim()) } }
        Feld("Token", Kern.geheim.ha, geheim = true) { v -> g { it.copy(ha = v.trim()) } }
    }
    gruppe("Gute Nacht", "Je Zeile eine Aktion: Name | Dienst | Ziel, z. B. „Alle Lichter aus | light.turn_off | all“. Danach bleibt die Nachtlampe gedimmt an, offene Fenster werden genannt.") {
        Feld("Aktionen", x.nacht.joinToString("\n") { "${it.name} | ${it.dienst} | ${it.ziel}" }, mehrzeilig = true) { v ->
            e { it.copy(nacht = v.lines().mapNotNull { z -> z.split("|").map(String::trim).takeIf { t -> t.size >= 2 && t[1].contains(".") }?.let { t -> HAAktion(t[0], t[1], t.getOrElse(2) { "" }) } }) }
        }
        Feld("Nachtlampe (light.…)", x.nachtlampe) { v -> e { it.copy(nachtlampe = v.trim()) } }
        Feld("Helligkeit der Nachtlampe (%)", x.nachtlampeHelligkeit.toString(), tastatur = KeyboardType.Number) { v -> v.toIntOrNull()?.let { h -> e { it.copy(nachtlampeHelligkeit = h.coerceIn(1, 100)) } } }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.ort() {
    val x = Kern.einstellungen
    gruppe("Wetter", "Für Wetter und Pausenwetter (Open-Meteo).") {
        Feld("Ort", x.ortName) { v -> e { it.copy(ortName = v) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Feld("Breite", x.lat.toString(), tastatur = KeyboardType.Decimal) { v -> v.replace(',', '.').toDoubleOrNull()?.let { d -> e { it.copy(lat = d) } } } }
            Box(Modifier.weight(1f)) { Feld("Länge", x.lon.toString(), tastatur = KeyboardType.Decimal) { v -> v.replace(',', '.').toDoubleOrNull()?.let { d -> e { it.copy(lon = d) } } } }
        }
        Feld("Bundesland (Ferien, Feiertage), z. B. HH", x.bundesland) { v -> e { it.copy(bundesland = v.trim().uppercase().take(2)) } }
    }
    gruppe("Kalender", "Termine aus den Kalendern des Geräts (Google, Exchange …) auf der Startseite und im Briefing.") {
        Schalter("Termine zeigen", x.termine) { an -> e { it.copy(termine = an) } }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.ueber() {
    gruppe("DammAPP für Android") {
        Text("Version ${BuildConfig.VERSION_NAME}")
        val c = LocalContext.current
        val scope = rememberCoroutineScope()
        var stand by remember { mutableStateOf("") }
        FilledTonalButton({ scope.launch {
            stand = "Suche …"
            val i = Update.pruefen()
            stand = when {
                i == null -> "Keine Verbindung zu GitHub."
                !Update.istNeuer(i.version, BuildConfig.VERSION_NAME) -> "Aktuell."
                !Update.darfInstallieren(c) -> { Update.erlaubnisOeffnen(c); "Bitte „Unbekannte Apps installieren“ erlauben und noch einmal tippen." }
                else -> { val f = Update.laden(c, i) {}; if (Update.signaturPasst(c, f)) { Update.installieren(c, f); "Installiere ${i.version} …" } else "Signatur passt nicht – abgebrochen." }
            }
        } }) { Text("Nach Update suchen") }
        if (stand.isNotEmpty()) Text(stand, style = MaterialTheme.typography.bodySmall)
        TextButton({ c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Teyro/DammAPP-Android"))) }) { Text("DammAPP auf GitHub") }
    }
}
