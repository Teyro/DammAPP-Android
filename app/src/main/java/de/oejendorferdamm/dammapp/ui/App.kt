package de.oejendorferdamm.dammapp.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.oejendorferdamm.dammapp.daten.Kern

/** Bereiche der App (auch Ziele aus Widget und Mitteilungen: dammapp://stoerungen …) */
enum class Bereich(val titel: String) {
    START("DammAPP"), VERTRETUNG("Vertretung"), STUNDENPLAN("Stundenplan"), MENSA("Speiseplan"), ABWESEND("Abwesenheiten"),
    STOERUNGEN("Störungen"), AUFGABEN("Aufgaben"), EINSTELLUNGEN("Einstellungen"), UNTIS("Stundenpläne"), FUSSBALL("Fußballplatz"), CHAT("Chat");
    companion object {
        fun aus(i: Intent?): Bereich? = i?.data?.takeIf { it.scheme == "dammapp" }?.host?.let { h -> entries.firstOrNull { it.name.equals(h, true) } }
    }
}

@Composable
fun App(ziel: MutableState<Bereich?>) {
    DammThema(Kern.einstellungen.dynamischeFarben) {
        val stapel = remember { mutableStateListOf(Bereich.START) }
        LaunchedEffect(ziel.value) { ziel.value?.let { if (it != Bereich.START && stapel.last() != it) stapel.add(it); ziel.value = null } }
        fun oeffne(b: Bereich) { if (stapel.last() != b) stapel.add(b) }
        fun zurueck() { if (stapel.size > 1) stapel.removeAt(stapel.lastIndex) }
        BackHandler(stapel.size > 1) { zurueck() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            if (Kern.assistentOffen) { AssistentSeite(); return@Surface }
            AnimatedContent(stapel.last(), label = "Bereich", transitionSpec = {
                if (targetState.ordinal >= initialState.ordinal || initialState == Bereich.START)
                    (slideInHorizontally { it / 4 } + fadeIn()) togetherWith fadeOut()
                else fadeIn() togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
            }) { b ->
                when (b) {
                    Bereich.START -> StartSeite(::oeffne)
                    Bereich.VERTRETUNG -> VertretungSeite(::zurueck)
                    Bereich.STUNDENPLAN -> StundenplanSeite(::zurueck)
                    Bereich.MENSA -> MensaSeite(::zurueck)
                    Bereich.ABWESEND -> AbwesendSeite(::zurueck)
                    Bereich.STOERUNGEN -> StoerungenSeite(::zurueck)
                    Bereich.AUFGABEN -> AufgabenSeite(::zurueck)
                    Bereich.EINSTELLUNGEN -> EinstellungenSeite(::zurueck)
                    Bereich.UNTIS -> StundenplaeneSeite(::zurueck)
                    Bereich.FUSSBALL -> FussballSeite(::zurueck)
                    Bereich.CHAT -> ChatSeite(::zurueck)
                }
            }
        }
        Dialoge()
    }
}

/** Zwei-Faktor-Code, fremde Anmeldeseite bestätigen, kurze Meldungen */
@Composable
private fun Dialoge() {
    Kern.codeHinweis?.let { hinweis ->
        var code by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = {}, title = { Text("IServ: Zwei-Faktor-Code") },
            text = { Column { Text(hinweis); Spacer(Modifier.height(12.dp))
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)) } },
            confirmButton = { TextButton({ Kern.codeEingegeben(code) }, enabled = code.length >= 6) { Text("Senden") } },
            dismissButton = { TextButton({ Kern.codeEingegeben(null) }) { Text("Abbrechen") } })
    }
    Kern.hostFrage?.let { h ->
        AlertDialog(onDismissRequest = { Kern.hostFrage = null }, title = { Text("Anmeldung über $h?") },
            text = { Text("IServ leitet die Anmeldung an $h weiter (z. B. Schuldock). Dein Passwort geht nur dorthin, wenn du das erlaubst.") },
            confirmButton = { TextButton({ Kern.hostErlauben(h) }) { Text("Erlauben") } },
            dismissButton = { TextButton({ Kern.hostFrage = null }) { Text("Nein") } })
    }
    Kern.meldung?.let { m ->
        LaunchedEffect(m) { kotlinx.coroutines.delay(4000); Kern.meldung = null }
        Box(Modifier.fillMaxSize().padding(16.dp).navigationBarsPadding(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
            Snackbar(action = { TextButton({ Kern.meldung = null }) { Text("OK") } }) { Text(m) }
        }
    }
}
