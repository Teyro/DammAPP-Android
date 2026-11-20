package de.oejendorferdamm.dammapp.daten

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import de.oejendorferdamm.dammapp.kern.Termin
import de.oejendorferdamm.dammapp.kern.Zeit
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Termine aus den Kalendern des Geräts (nur mit Erlaubnis) */
object Kalender {
    fun erlaubt(c: Context) = c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun termine(c: Context, tage: Long): List<Termin> {
        if (!erlaubt(c)) return emptyList()
        val von = Zeit.heute().atStartOfDay(Zeit.zone).toInstant().toEpochMilli()
        val bis = von + tage * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, von); android.content.ContentUris.appendId(it, bis) }.build()
        val erg = ArrayList<Termin>()
        runCatching {
            c.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY), null, null,
                CalendarContract.Instances.BEGIN)?.use { cur ->
                while (cur.moveToNext()) {
                    val titel = cur.getString(0) ?: continue
                    val ganz = cur.getInt(2) == 1
                    // Ganztägige Termine liegen in UTC – sonst rutschen sie einen Tag zurück
                    val start = if (ganz) LocalDateTime.ofInstant(Instant.ofEpochMilli(cur.getLong(1)), ZoneOffset.UTC) else LocalDateTime.ofInstant(Instant.ofEpochMilli(cur.getLong(1)), Zeit.zone)
                    erg.add(Termin(titel, start, ganz))
                }
            }
        }
        return erg.distinctBy { Triple(it.titel, it.start, it.ganztags) }
    }
}
