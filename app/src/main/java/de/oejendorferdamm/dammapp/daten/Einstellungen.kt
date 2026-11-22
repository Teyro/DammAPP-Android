@file:kotlinx.serialization.UseSerializers(de.oejendorferdamm.dammapp.kern.LDTSerializer::class)

package de.oejendorferdamm.dammapp.daten

import de.oejendorferdamm.dammapp.kern.IServQuelle
import de.oejendorferdamm.dammapp.kern.Schulzeit
import de.oejendorferdamm.dammapp.kern.Unterricht
import kotlinx.serialization.Serializable

/** Eine Home-Assistant-Aktion, z. B. „Alle Lichter aus“ = light.turn_off auf „all“ */
@Serializable
data class HAAktion(val name: String, val dienst: String, val ziel: String)

/** Alle Einstellungen (ohne Passwörter – die liegen verschlüsselt in [Geheim]) */
@Serializable
data class Einstellungen(
    val ortName: String = "Hamburg",
    val lat: Double = 53.5511,
    val lon: Double = 9.9937,
    val bundesland: String = "HH",
    // Vertretung
    val vertretung: Boolean = true,
    val vertretungLink: String = "",
    val kuerzel: String = "",
    val haeuser: Map<String, List<String>> = emptyMap(),
    val kollegium: Map<String, String> = emptyMap(),
    val vertretungMelden: Boolean = false,
    val vertretungDa: Boolean = false,
    val vertretungDringend: Boolean = false,
    // Schultag
    val schulzeiten: List<Schulzeit> = Schulzeit.standard,
    val unterricht: List<Unterricht> = emptyList(),
    val aufsichtErinnern: Boolean = true,
    val schulBenachrichtigung: Boolean = true,
    val mitnehmen: Boolean = true,
    // WebUntis
    val untisAn: Boolean = false,
    val untisLink: String = "",
    val untisSchule: String = "",
    val untisBenutzer: String = "",
    val untisUebernehmen: Boolean = true,
    // Mensa, Obst, Termine
    val mensa: Boolean = false,
    val mensaAdresse: String = "",
    val mensaBenutzer: String = "",
    val favoriten: String = "",
    val meiden: String = "",
    val lieblingsGerichte: List<String> = emptyList(),
    val nichtLecker: List<String> = emptyList(),
    val obst: Boolean = false,
    val obstKalender: String = "",
    val schulKalender: String = "",
    val termine: Boolean = true,
    // IServ
    val iservServer: String = "",
    val iservBenutzer: String = "",
    val iservHosts: List<String> = emptyList(),
    val abwesenheiten: Boolean = false,
    val abwesendKlassen: String = "",
    val sichtschutz: Boolean = true,
    val abwesendQuelle: IServQuelle? = null,
    val stoerungen: Boolean = false,
    val erledigteZeigen: Boolean = false,
    val stoerungQuelle: IServQuelle? = null,
    // Edupool
    val aufgaben: Boolean = false,
    val aufgabenFarbe: String = "#76a5af",
    // Mitteilungen
    val mitteilungen: Boolean = false,
    // Home Assistant (Gute Nacht)
    val haAdresse: String = "",
    val nacht: List<HAAktion> = listOf(HAAktion("Alle Lichter aus", "light.turn_off", "all")),
    val nachtlampe: String = "",
    val nachtlampeHelligkeit: Int = 15,
    // Startseite
    val kachelReihenfolge: List<String> = emptyList(),
    val kachelAus: List<String> = emptyList(),
    /** Material You: Farben aus dem Hintergrundbild (Android 12+); aus = DammAPP-Blau */
    val dynamischeFarben: Boolean = true,
) {
    val mensaBereit get() = mensa && mensaAdresse.isNotEmpty() && mensaBenutzer.isNotEmpty()
    val obstBereit get() = obst && obstKalender.isNotEmpty()
    val klassenListe get() = abwesendKlassen.split(Regex("[,; ]+")).filter { it.isNotEmpty() }
}

/** Passwörter und Schlüssel – verschlüsselt mit dem Android-Schlüsselspeicher */
@Serializable
data class Geheimnisse(
    val freigabe: String = "",
    val mensa: String = "",
    val iserv: String = "",
    val totp: String = "",
    val edupool: String = "",
    val ha: String = "",
    val untisPasswort: String = "",
    val untisSchluessel: String = "",
    /** Passwort der Freigabe „Schul-Ordner“ – für die Untis-Sicherung, wenn WebUntis ausfällt */
    val schulOrdner: String = "",
)
