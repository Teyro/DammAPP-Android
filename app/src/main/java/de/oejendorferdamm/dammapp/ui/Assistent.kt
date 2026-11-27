package de.oejendorferdamm.dammapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.oejendorferdamm.dammapp.daten.Einstellungen
import de.oejendorferdamm.dammapp.daten.Geheimnisse
import de.oejendorferdamm.dammapp.daten.Kern
import de.oejendorferdamm.dammapp.kern.IServ
import de.oejendorferdamm.dammapp.kern.SchulVorgaben
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Schritt(val titel: String) { WILLKOMMEN("Willkommen"), SCHULE("Schule"), KUERZEL("Dein Kürzel"), ESSEN("Mensa"), UNTIS("WebUntis"), ISERV("IServ"), FERTIG("Fertig") }

/** Schul-Vorgaben übernehmen: nur Schul-weite Werte, nichts Persönliches */
fun SchulVorgaben.anwenden(e: Einstellungen) = e.copy(
    vertretungLink = vertretungLink.ifEmpty { e.vertretungLink }, mensaAdresse = mensaAdresse.ifEmpty { e.mensaAdresse },
    obstKalender = obstKalender.ifEmpty { e.obstKalender }, schulKalender = schulKalender.ifEmpty { e.schulKalender },
    iservServer = if (iservServer.isEmpty()) e.iservServer else IServ.bereinige(iservServer),
    untisLink = untisLink.ifEmpty { e.untisLink }, untisSchule = untisSchule.ifEmpty { e.untisSchule },
    bundesland = bundesland.ifEmpty { e.bundesland }, haeuser = haeuser.ifEmpty { e.haeuser },
    nextcloudServer = nextcloudServer.ifEmpty { e.nextcloudServer }, nextcloudBenutzer = nextcloudBenutzer.ifEmpty { e.nextcloudBenutzer },
)

/** Einrichtungs-Assistent: Schule (eingebaute oder eigene Zugänge), Kürzel, Mensa, Untis, IServ – Vorhandenes ist vorausgefüllt */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AssistentSeite() {
    var schritt by remember { mutableStateOf(Schritt.WILLKOMMEN) }
    var schulDaten by remember { mutableStateOf(true) }
    var code by remember { mutableStateOf(Kern.geheim.schulOrdner.ifEmpty { Kern.geheim.freigabe }) }
    var laedt by remember { mutableStateOf(false) }
    var geladen by remember { mutableStateOf(false) }
    var meldung by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val x = Kern.einstellungen
    fun e(f: (Einstellungen) -> Einstellungen) = Kern.aendere(f)
    fun g(f: (Geheimnisse) -> Geheimnisse) = Kern.aendereGeheim(f)
    fun laden() {
        laedt = true; meldung = ""
        scope.launch {
            try {
                val v = withContext(Dispatchers.IO) { SchulVorgaben.laden(code) }
                e { v.anwenden(it) }
                g { it.copy(freigabe = v.freigabe.ifEmpty { it.freigabe }, schulOrdner = v.schulOrdner.ifEmpty { it.schulOrdner }, nextcloud = v.nextcloudPasswort.ifEmpty { it.nextcloud }) }
                geladen = true; meldung = "Vertretungsplan, Nextcloud, Mensa-Portal, Schulobst, Untis und IServ der Schule sind eingetragen."
            } catch (t: Throwable) { geladen = false; meldung = t.message ?: "Fehler beim Laden" }
            laedt = false
        }
    }
    fun weiter() {
        if (schritt == Schritt.FERTIG) { Kern.assistentFertig(); scope.launch { Kern.aktualisieren(true) }; return }
        schritt = Schritt.entries[schritt.ordinal + 1]
        // Schul-Code schon bekannt (Bestandsinstallation): gleich freischalten
        if (schritt == Schritt.SCHULE && schulDaten && !geladen && code.isNotEmpty()) laden()
    }
    BackHandler(schritt != Schritt.WILLKOMMEN) { schritt = Schritt.entries[schritt.ordinal - 1] }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text(schritt.titel) },
                navigationIcon = { if (schritt != Schritt.WILLKOMMEN) IconButton({ schritt = Schritt.entries[schritt.ordinal - 1] }) { Icon(Icons.Rounded.ArrowBack, "Zurück") } },
                actions = { TextButton({ Kern.assistentFertig() }) { Text("Später") } })
        },
        bottomBar = {
            Button(::weiter, Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(52.dp),
                enabled = !(schritt == Schritt.SCHULE && schulDaten && !geladen && x.vertretungLink.isEmpty())) {
                Text(when (schritt) { Schritt.WILLKOMMEN -> "Einrichten"; Schritt.FERTIG -> "Los geht’s"; else -> "Weiter" })
            }
        },
    ) { innen ->
        Column(Modifier.padding(innen).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LinearProgressIndicator({ schritt.ordinal / (Schritt.entries.size - 1f) }, Modifier.fillMaxWidth())
            AnimatedContent(schritt, label = "Schritt") { s ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (s) {
                        Schritt.WILLKOMMEN -> {
                            Icon(Icons.Rounded.School, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("Willkommen in der DammAPP", style = MaterialTheme.typography.headlineSmall)
                            Hinweis("In ein paar Schritten ist alles eingerichtet: Vertretungsplan, Stundenplan, Mensa, Abwesenheiten und Störungen. Was schon eingetragen ist, bleibt erhalten – du kannst es hier prüfen.")
                        }
                        Schritt.SCHULE -> {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                SegmentedButton(schulDaten, { schulDaten = true }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Von der Schule") }
                                SegmentedButton(!schulDaten, { schulDaten = false }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Eigene") }
                            }
                            if (schulDaten) {
                                Hinweis("Die Zugänge der Schule (Vertretungsplan-Ordner, Mensa-Portal, Schulobst, Untis, IServ) sind eingebaut. Zum Freischalten brauchst du den Schul-Code – das Passwort des Schul-Ordners.")
                                Eingabe("Schul-Code", code, geheim = true) { code = it; geladen = false }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    FilledTonalButton(::laden, enabled = code.isNotEmpty() && !laedt) { Text(if (geladen) "Freigeschaltet" else "Freischalten") }
                                    if (laedt) CircularProgressIndicator(Modifier.size(24.dp)) else if (geladen) Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF0B8043))
                                }
                                if (meldung.isNotEmpty()) Text(meldung, color = if (geladen) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            } else {
                                Hinweis("Du trägst die Adressen selbst ein – hier und in den nächsten Schritten.")
                                Eingabe("Freigabe-Link des Vertretungsplan-Ordners", x.vertretungLink, tastatur = KeyboardType.Uri) { v -> e { it.copy(vertretungLink = v.trim()) } }
                                Eingabe("Passwort der Freigabe", Kern.geheim.freigabe, geheim = true) { v -> g { it.copy(freigabe = v) } }
                                Eingabe("Schul-Ordner-Passwort (Untis-Sicherung)", Kern.geheim.schulOrdner, geheim = true) { v -> g { it.copy(schulOrdner = v) } }
                                Hinweis("Nextcloud der Schule (für die Untis-Sicherung):")
                                Eingabe("Server, z. B. https://cloud.schule.de", x.nextcloudServer, tastatur = KeyboardType.Uri) { v -> e { it.copy(nextcloudServer = v.trim()) } }
                                Eingabe("Benutzer", x.nextcloudBenutzer) { v -> e { it.copy(nextcloudBenutzer = v.trim()) } }
                                Eingabe("App-Passwort", Kern.geheim.nextcloud, geheim = true) { v -> g { it.copy(nextcloud = v) } }
                            }
                        }
                        Schritt.KUERZEL -> {
                            Eingabe("Kürzel, z. B. Sta", x.kuerzel) { v -> e { it.copy(kuerzel = v.trim()) } }
                            Umschalter("Vertretungsplan zeigen", x.vertretung) { an -> e { it.copy(vertretung = an) } }
                            Hinweis("So stehst du im Vertretungsplan. Daran erkennt die App deine Vertretungen, Aufsichten und Begleitungen.")
                        }
                        Schritt.ESSEN -> {
                            Umschalter("Ich esse in der Mensa", x.mensa) { an -> e { it.copy(mensa = an) } }
                            if (x.mensa) {
                                if (!schulDaten) Eingabe("Adresse des Portals (https://…)", x.mensaAdresse, tastatur = KeyboardType.Uri) { v -> e { it.copy(mensaAdresse = v.trim()) } }
                                Eingabe("Benutzer", x.mensaBenutzer) { v -> e { it.copy(mensaBenutzer = v.trim()) } }
                                Eingabe("Passwort", Kern.geheim.mensa, geheim = true) { v -> g { it.copy(mensa = v) } }
                            }
                            Hinweis("Deine Zugangsdaten fürs Kantinen-Portal – dieselben wie im Browser. Dann siehst du den Speiseplan und was du bestellt hast.")
                            Umschalter("Schulobst anzeigen", x.obst) { an -> e { it.copy(obst = an) } }
                        }
                        Schritt.UNTIS -> {
                            Umschalter("Ich nutze WebUntis", x.untisAn) { an -> e { it.copy(untisAn = an) } }
                            if (x.untisAn) {
                                if (!schulDaten) {
                                    Eingabe("Adresse (z. B. hh5586.webuntis.com)", x.untisLink, tastatur = KeyboardType.Uri) { v -> e { it.copy(untisLink = v.trim()) } }
                                    Eingabe("Schule", x.untisSchule) { v -> e { it.copy(untisSchule = v.trim()) } }
                                }
                                Eingabe("Benutzer", x.untisBenutzer) { v -> e { it.copy(untisBenutzer = v.trim()) } }
                                Eingabe("Passwort", Kern.geheim.untisPasswort, geheim = true) { v -> g { it.copy(untisPasswort = v) } }
                                Eingabe("Schlüssel (bei Zwei-Faktor, aus Untis Mobile)", Kern.geheim.untisSchluessel, geheim = true) { v -> g { it.copy(untisSchluessel = v.trim()) } }
                            }
                            Hinweis("Dein Stundenplan kommt dann aus WebUntis. Den Schlüssel findest du in WebUntis unter Profil → Freigaben → Untis Mobile.")
                        }
                        Schritt.ISERV -> {
                            val an = x.abwesenheiten || x.stoerungen
                            Umschalter("Ich nutze IServ", an) { v -> e { it.copy(abwesenheiten = v, stoerungen = v) } }
                            if (an) {
                                if (!schulDaten) Eingabe("Adresse (z. B. schule.de)", x.iservServer, tastatur = KeyboardType.Uri) { v -> e { it.copy(iservServer = IServ.bereinige(v)) } }
                                Eingabe("Benutzer (vorname.nachname)", x.iservBenutzer) { v -> e { it.copy(iservBenutzer = v.trim()) } }
                                Eingabe("Passwort", Kern.geheim.iserv, geheim = true) { v -> g { it.copy(iserv = v) } }
                                Umschalter("Abwesenheiten", x.abwesenheiten) { v -> e { it.copy(abwesenheiten = v) } }
                                Umschalter("Störungsmeldungen", x.stoerungen) { v -> e { it.copy(stoerungen = v) } }
                            }
                            Hinweis("Für Abwesenheiten (wer an der Schule heute nicht da ist) und Störungsmeldungen. Edupool-Aufgaben verbindest du später in den Einstellungen.")
                        }
                        Schritt.FERTIG -> {
                            Icon(Icons.Rounded.Verified, null, Modifier.size(56.dp), tint = Color(0xFF0B8043))
                            Text("Alles bereit", style = MaterialTheme.typography.headlineSmall)
                            Hinweis(listOfNotNull(if (x.kuerzel.isEmpty()) "Ohne Kürzel" else "Kürzel ${x.kuerzel}", "Vertretungsplan".takeIf { x.vertretung }, "Mensa".takeIf { x.mensa },
                                "Schulobst".takeIf { x.obst }, "WebUntis".takeIf { x.untisAn }, "Abwesenheiten".takeIf { x.abwesenheiten }, "Störungen".takeIf { x.stoerungen }).joinToString(" · "))
                            Hinweis("Alles lässt sich später unter Einstellungen ändern – dort kannst du den Assistenten auch neu starten.")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable private fun Hinweis(t: String) = Text(t, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable private fun Umschalter(titel: String, an: Boolean, f: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(titel, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Switch(an, f) }
}

@Composable private fun Eingabe(titel: String, wert: String, geheim: Boolean = false, tastatur: KeyboardType = KeyboardType.Text, f: (String) -> Unit) {
    var t by remember(wert) { mutableStateOf(wert) }
    OutlinedTextField(t, { t = it; f(it) }, Modifier.fillMaxWidth(), label = { Text(titel) }, singleLine = true,
        visualTransformation = if (geheim) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (geheim) KeyboardType.Password else tastatur))
}
