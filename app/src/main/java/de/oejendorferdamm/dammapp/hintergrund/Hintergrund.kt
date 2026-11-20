package de.oejendorferdamm.dammapp.hintergrund

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import de.oejendorferdamm.dammapp.daten.Kern
import de.oejendorferdamm.dammapp.daten.Schultag
import de.oejendorferdamm.dammapp.kern.Zeit
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Hintergrund-Abfragen ohne Push: stündlich (Android entscheidet genau), dazu fest morgens um 6:45 und nach 13:30,
 * an Schultagen zwischen 7 und 8 Uhr alle 15 Minuten (Vertretungsplan).
 */
class Abruf(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        Kern.init(applicationContext)
        runCatching { Kern.aktualisieren() }
        Hintergrund.festePlanen(applicationContext)
        return Result.success()
    }
}

object Hintergrund {
    private val netz = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun planen(c: Context) {
        val wm = WorkManager.getInstance(c)
        wm.enqueueUniquePeriodicWork("stuendlich", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Abruf>(1, TimeUnit.HOURS).setConstraints(netz).build())
        festePlanen(c)
        Schultag.aktualisieren(c)
    }

    /** Nächster fester Termin: 6:45, alle 15 Min. bis 8:00 (Schultage), 13:35 */
    fun festePlanen(c: Context) {
        val jetzt = Zeit.jetzt()
        val ziele = (0..1).flatMap { plus ->
            val tag = jetzt.toLocalDate().plusDays(plus.toLong())
            val morgen = if (Zeit.wochenende(tag)) emptyList() else (0..5).map { tag.atTime(6, 45).plusMinutes(15L * it) }
            morgen + tag.atTime(13, 35)
        }
        val ziel: LocalDateTime = ziele.firstOrNull { it.isAfter(jetzt.plusMinutes(1)) } ?: return
        WorkManager.getInstance(c).enqueueUniqueWork("fest", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<Abruf>().setInitialDelay(Duration.between(jetzt, ziel).toMillis(), TimeUnit.MILLISECONDS).setConstraints(netz).build())
    }

    /** Nach dem Neustart des Geräts wieder einplanen */
    class Start : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { Kern.init(c); planen(c) }
    }
}
