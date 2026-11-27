package de.oejendorferdamm.dammapp.daten

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import de.oejendorferdamm.dammapp.MainActivity
import de.oejendorferdamm.dammapp.R
import de.oejendorferdamm.dammapp.kern.Aufgaben
import de.oejendorferdamm.dammapp.kern.ChatMelden
import de.oejendorferdamm.dammapp.kern.FreierTag
import de.oejendorferdamm.dammapp.kern.Matrix
import de.oejendorferdamm.dammapp.kern.Zeit
import de.oejendorferdamm.dammapp.kern.AufgabenStand
import de.oejendorferdamm.dammapp.kern.StoerungenStand
import de.oejendorferdamm.dammapp.kern.VertretungsMeldung
import de.oejendorferdamm.dammapp.kern.VertretungsPlan

/** Mitteilungen: neue Störungen und Zustandswechsel, neue/verschobene Aufgaben und neue Kommentare, Vertretungsplan */
object Melder {
    const val KANAL = "meldungen"
    const val KANAL_DRINGEND = "vertretung_dringend"
    const val KANAL_SCHULTAG = "schultag"
    const val KANAL_AUFSICHT = "aufsicht"
    const val KANAL_UPDATE = "update"
    const val KANAL_CHAT = "chat"

    fun kanaele(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(KANAL, "Neuigkeiten (Störungen, Aufgaben, Vertretung)", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(KANAL_DRINGEND, "Vertretungsplan (dringend)", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(KANAL_CHAT, "Chat-Nachrichten", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(KANAL_UPDATE, "Neue Version von DammAPP", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(KANAL_AUFSICHT, "Erinnerung vor der Hofaufsicht", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(KANAL_SCHULTAG, "Schultag (was gerade läuft)", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }

    fun pruefen(c: Context, a: Ablage, stoerungen: StoerungenStand?, aufgaben: AufgabenStand?, vertretung: VertretungsPlan?, planDa: Boolean, betrifft: Boolean, dringend: Boolean) {
        if (vertretung != null && (planDa || betrifft)) {
            VertretungsMeldung.pruefen(vertretung, a.text("gemeldetPlanDatum"), a.text("gemeldetVertretung"), planDa, betrifft)?.let { (m, datum, sig) ->
                a.text("gemeldetPlanDatum", datum); a.text("gemeldetVertretung", sig)
                m?.let { melde(c, it.id, it.titel, it.text, "vertretung", dringend) }
            }
        }
        stoerungen?.let { s ->
            val neu = vergleiche(a, s.offen.map { it.schluessel }, "gemeldetStoerungen")
            s.offen.filter { it.schluessel in neu }.forEach { m ->
                melde(c, "stoerung-${m.schluessel.hashCode()}", "Neue Störung" + if (m.ort.isEmpty()) "" else " · ${m.ort}", m.titel + if (m.autor.isEmpty()) "" else " (von ${m.autor})", "stoerungen")
            }
            val alt = a.text("zustandStoerungen")?.lines()?.mapNotNull { z -> z.split("\t").takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap()
            if (alt != null) for (m in s.meldungen) {
                val vorher = alt[m.schluessel] ?: continue
                if (vorher != m.zustandKurz) melde(c, "stoerung-${(m.schluessel + m.zustandKurz).hashCode()}", if (m.erledigt) "Störung erledigt" else "Störung: ${m.zustandKurz}", m.titel, "stoerungen")
            }
            a.text("zustandStoerungen", s.meldungen.joinToString("\n") { "${it.schluessel}\t${it.zustandKurz}" })
        }
        aufgaben?.let { st ->
            val neu = vergleiche(a, st.aufgaben.map { "${it.id}|${it.kategorie}" }, "gemeldetAufgaben")
            st.aufgaben.filter { "${it.id}|${it.kategorie}" in neu }.forEach { x ->
                val kat = Aufgaben.kategorien[x.kategorie].name
                melde(c, "aufgabe-${x.id}", if (kat == "Erledigt") "Aufgabe erledigt" else "Aufgabe · $kat", x.titel, "aufgaben")
            }
            val alt = a.text("kommentareAufgaben")?.lines()?.mapNotNull { z -> z.split("\t").takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) } }?.toMap()
            if (alt != null) for (x in st.aufgaben) {
                val n = x.kommentarAnzahl ?: continue; val vorher = alt[x.id] ?: continue
                if (n > vorher) melde(c, "kommentar-${x.id}-$n", if (n - vorher == 1) "Neuer Kommentar" else "${n - vorher} neue Kommentare", x.titel, "aufgaben")
            }
            a.text("kommentareAufgaben", st.aufgaben.filter { it.kommentarAnzahl != null }.joinToString("\n") { "${it.id}\t${it.kommentarAnzahl}" })
        }
    }

    /** Neue Chat-Nachrichten: während der Arbeit sofort je Nachricht, sonst gesammelt und einmal abends zusammengefasst */
    fun chat(c: Context, a: Ablage, matrix: String, frei: List<FreierTag>, uhrzeit: String) {
        val t = matrix.split("\t", limit = 2).takeIf { it.size == 2 } ?: return
        val m = Matrix(t[1], t[0])
        val seit = a.text("chatWeiter")
        val j = runCatching { m.sync(seit ?: "") }.getOrNull() ?: return
        a.text("chatWeiter", j.optString("next_batch"))
        val namen = a.text("chatNamen")?.lines()?.mapNotNull { z -> z.split("\t").takeIf { it.size == 2 }?.let { it[0] to it[1] } }?.toMap()?.toMutableMap() ?: mutableMapOf()
        val neu = ChatMelden.neue(j, m.ich, namen)
        a.text("chatNamen", namen.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        val jetzt = Zeit.jetzt()
        val gesammelt = a.text("chatGesammelt")?.lines()?.mapNotNull { z -> z.split("\t").takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) } }?.toMap()?.toMutableMap() ?: mutableMapOf()
        if (seit != null && neu.isNotEmpty()) {
            if (ChatMelden.sofort(jetzt, frei)) neu.forEach { n -> melde(c, "chat-${n.id}", n.raum, (if (n.raum == n.absender) "" else "${n.absender}: ") + n.text, "chat", kanal = KANAL_CHAT) }
            else neu.forEach { gesammelt[it.raum] = (gesammelt[it.raum] ?: 0) + 1 }
        }
        if (gesammelt.isNotEmpty() && ChatMelden.faellig(jetzt, uhrzeit, a.text("chatZusammengefasst"))) {
            val (titel, text) = ChatMelden.zusammenfassung(gesammelt)
            melde(c, "chat-zusammenfassung", titel, text, "chat", kanal = KANAL_CHAT)
            a.text("chatZusammengefasst", jetzt.toLocalDate().toString()); gesammelt.clear()
        }
        a.text("chatGesammelt", gesammelt.entries.joinToString("\n") { "${it.key}\t${it.value}" }.ifEmpty { null })
    }

    /** Chat geöffnet: Gesammeltes verwerfen */
    fun chatGelesen(a: Ablage) = a.text("chatGesammelt", null)

    /** Was ist neu gegenüber dem letzten Stand? Erster Lauf: nichts (nur merken). */
    private fun vergleiche(a: Ablage, jetzt: List<String>, name: String): Set<String> {
        val alt = a.text(name)?.split("\n")?.toSet()
        a.text(name, jetzt.toSet().joinToString("\n"))
        return if (alt == null) emptySet() else jetzt.toSet() - alt
    }

    fun oeffnen(c: Context, ziel: String): PendingIntent = PendingIntent.getActivity(c, ziel.hashCode(),
        Intent(c, MainActivity::class.java).setData(Uri.parse("dammapp://$ziel")).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun melde(c: Context, id: String, titel: String, text: String, ziel: String, dringend: Boolean = false, kanal: String? = null) {
        val n = NotificationCompat.Builder(c, kanal ?: if (dringend) KANAL_DRINGEND else KANAL)
            .setSmallIcon(R.drawable.ic_mitteilung).setContentTitle(titel).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
            .setPriority(if (dringend) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(oeffnen(c, ziel)).build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify(id, 1, n) }
    }
}
