package de.oejendorferdamm.dammapp.kern

import org.json.JSONObject
import java.time.LocalDateTime

/** Wann neue Chat-Nachrichten melden: während der Arbeit (und auf dem Weg) sofort, zu Hause nur abends als Zusammenfassung. */
object ChatMelden {
    /** shortcut: ohne Standort nur nach Uhrzeit (Schultag 6–17 Uhr = Arbeit) – Ortung nachrüsten, wenn das nicht reicht */
    fun sofort(jetzt: LocalDateTime, frei: List<FreierTag>): Boolean =
        !Zeit.wochenende(jetzt.toLocalDate()) && FreieTage.am(frei, jetzt.toLocalDate()) == null && jetzt.hour in 6 until 17

    data class Neu(val id: String, val raum: String, val absender: String, val text: String)

    /** Neue Nachrichten anderer aus einem /sync ab „since“; [namen] = bekannte Raumnamen (Folge-Syncs liefern sie nicht mit) */
    fun neue(j: JSONObject, ich: String, namen: MutableMap<String, String>): List<Neu> =
        Matrix.einarbeiten(j, emptyList()).first.flatMap { r ->
            if (r.name != "Chat") namen[r.id] = r.name
            r.nachrichten.filter { it.absender != ich }.map { Neu(it.id, namen[r.id] ?: r.name, r.name(it.absender), it.text) }
        }

    /** „Kollegium (3), Medien (1)“ – meiste zuerst */
    fun zusammenfassung(gesammelt: Map<String, Int>): Pair<String, String> {
        val summe = gesammelt.values.sum()
        return (if (summe == 1) "1 neue Chat-Nachricht" else "$summe neue Chat-Nachrichten") to
            gesammelt.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).joinToString(", ") { "${it.key} (${it.value})" }
    }

    /** Zusammenfassung fällig: ab [uhrzeit], einmal am Tag */
    fun faellig(jetzt: LocalDateTime, uhrzeit: String, zuletzt: String?): Boolean =
        Zeit.minutenVon(jetzt) >= (Zeit.minuten(uhrzeit) ?: 18 * 60) && zuletzt != jetzt.toLocalDate().toString()
}
