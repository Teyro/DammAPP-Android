package de.oejendorferdamm.dammapp.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Feste Farben für Bedeutungen (wie iOS: rot = außerplanmäßig, Aufsicht orange, fehlt rot, erledigt grün) */
object Farben {
    val vertretung = Color(0xFF1A73E8)
    /** außerplanmäßig: Vertretung, Begleitung (rot, eigene Stunde rot durchgestrichen) */
    val ausserplan = Color(0xFFD93025)
    val aufsicht = Color(0xFFE8710A)
    val fehlt = Color(0xFFD93025)
    val gut = Color(0xFF1E8E3E)
    val obst = Color(0xFF34A853)
}

private val hell = lightColorScheme(primary = Color(0xFF1E5AA8), secondary = Color(0xFF545F71), tertiary = Color(0xFF6E5676))
private val dunkel = darkColorScheme(primary = Color(0xFFA6C8FF), secondary = Color(0xFFBDC7DC), tertiary = Color(0xFFDABDE2))

/** Material You: Farben aus dem Hintergrundbild (ab Android 12), sonst DammAPP-Blau */
@Composable
fun DammThema(dynamisch: Boolean, inhalt: @Composable () -> Unit) {
    val dunkelModus = isSystemInDarkTheme()
    val c = LocalContext.current
    val schema = when {
        dynamisch && Build.VERSION.SDK_INT >= 31 -> if (dunkelModus) dynamicDarkColorScheme(c) else dynamicLightColorScheme(c)
        dunkelModus -> dunkel
        else -> hell
    }
    MaterialTheme(colorScheme = schema, typography = Typography(), content = inhalt)
}
