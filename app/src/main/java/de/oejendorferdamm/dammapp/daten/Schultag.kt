package de.oejendorferdamm.dammapp.daten

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import de.oejendorferdamm.dammapp.R
import de.oejendorferdamm.dammapp.kern.Block
import de.oejendorferdamm.dammapp.kern.Stundenplan
import de.oejendorferdamm.dammapp.kern.Zeit
import java.time.Duration

/**
 * Wie die Live-Aktivität der iOS-App: eine ruhige, dauerhafte Benachrichtigung „was gerade läuft, was danach kommt“
 * während des Schultags, dazu eine Erinnerung 5 Minuten vor einer Hofaufsicht. Weitergeschaltet wird per Wecker zum nächsten Wechsel.
 */
object Schultag {
    private const val ID = 4711

    fun aktualisieren(c: Context) {
        Kern.init(c)
        val e = Kern.einstellungen
        val nm = c.getSystemService(NotificationManager::class.java)
        val bloecke = Kern.heuteBloecke
        val jetzt = Zeit.jetzt()
        val (laeuft, danach) = Stundenplan.jetzt(bloecke, jetzt)
        val ende = bloecke.lastOrNull()?.bis
        if (!e.schulBenachrichtigung || bloecke.isEmpty() || ende == null || jetzt.isAfter(ende) || jetzt.isBefore(bloecke.first().von.minusMinutes(60))) {
            nm.cancel(ID)
        } else {
            val titel = laeuft?.let { "${it.titel}: ${it.text}" } ?: danach?.let { "Gleich: ${it.titel}" } ?: "Schultag"
            val rest = laeuft?.let { "noch ${Duration.between(jetzt, it.bis).toMinutes() + 1} Min." }
            val text = listOfNotNull(rest, danach?.let { "danach ${it.marke} ${it.text} (${Zeit.uhrzeit(it.von)})" }).joinToString(" · ")
            val n = NotificationCompat.Builder(c, Melder.KANAL_SCHULTAG).setSmallIcon(R.drawable.ic_mitteilung)
                .setContentTitle(titel).setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS).setContentIntent(Melder.oeffnen(c, "stundenplan"))
                .apply { if (laeuft != null) { setUsesChronometer(true); setChronometerCountDown(true); setWhen(laeuft.bis.atZone(Zeit.zone).toInstant().toEpochMilli()) } }
                .build()
            runCatching { nm.notify(ID, n) }
        }
        planen(c, bloecke)
    }

    /** Nächster Wechsel (Stundenbeginn/-ende) und Aufsicht-Erinnerungen über den Wecker des Systems (ungefähr genau, stromsparend) */
    private fun planen(c: Context, bloecke: List<Block>) {
        val am = c.getSystemService(AlarmManager::class.java)
        val jetzt = Zeit.jetzt()
        val naechster = Stundenplan.wechsel(bloecke).firstOrNull { it.isAfter(jetzt) }
        val pi = PendingIntent.getBroadcast(c, 1, Intent(c, Empfaenger::class.java).setAction("weiter"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (naechster != null) am.setAndAllowWhileIdle(AlarmManager.RTC, naechster.atZone(Zeit.zone).toInstant().toEpochMilli() + 2000, pi) else am.cancel(pi)
        if (Kern.einstellungen.aufsichtErinnern) {
            bloecke.filter { it.art == Block.Art.AUFSICHT && it.von.minusMinutes(5).isAfter(jetzt) }.forEachIndexed { i, b ->
                val p = PendingIntent.getBroadcast(c, 10 + i, Intent(c, Empfaenger::class.java).setAction("aufsicht").putExtra("titel", b.name).putExtra("text", b.text),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, b.von.minusMinutes(5).atZone(Zeit.zone).toInstant().toEpochMilli(), p)
            }
        }
    }

    class Empfaenger : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Kern.init(c)
            when (i.action) {
                "aufsicht" -> {
                    val b = Kern.heuteBloecke.firstOrNull { it.name == i.getStringExtra("titel") }
                    val w = b?.let { Kern.wetter?.pause(it.von, it.bis) }
                    Melder.melde(c, "aufsicht-${i.getStringExtra("titel")}", "Gleich Hofaufsicht (${i.getStringExtra("titel")})",
                        (i.getStringExtra("text") ?: "") + (w?.let { " · ${it.text}" } ?: ""), "stundenplan", kanal = Melder.KANAL_AUFSICHT)
                }
                else -> aktualisieren(c)
            }
            de.oejendorferdamm.dammapp.widget.Widgets.aktualisieren(c)
        }
    }
}
