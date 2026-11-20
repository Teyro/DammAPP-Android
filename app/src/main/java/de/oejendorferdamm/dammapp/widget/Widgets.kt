package de.oejendorferdamm.dammapp.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import de.oejendorferdamm.dammapp.MainActivity
import de.oejendorferdamm.dammapp.daten.Kern
import de.oejendorferdamm.dammapp.kern.Block
import de.oejendorferdamm.dammapp.kern.Stundenplan
import de.oejendorferdamm.dammapp.kern.Zeit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object Widgets {
    fun aktualisieren(c: Context) {
        CoroutineScope(Dispatchers.Default).launch { runCatching { SchultagWidget().updateAll(c) } }
    }
}

class SchultagEmpfaenger : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SchultagWidget()
}

/** Schultag-Widget: Stunden des Schultags (Vertretung blau, Aufsicht orange), darunter gleich große Kästen für Fehlen, Störungen, Aufgaben */
class SchultagWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(180.dp, 110.dp), DpSize(260.dp, 180.dp), DpSize(320.dp, 300.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        Kern.init(context)
        provideContent { GlanceTheme { Inhalt(context) } }
    }

    private fun oeffnen(c: Context, ziel: String) = actionStartActivity(Intent(c, MainActivity::class.java).setData(Uri.parse("dammapp://$ziel")))

    @Composable
    private fun Inhalt(c: Context) {
        val groesse = LocalSize.current
        val gross = groesse.height >= 280.dp
        val e = Kern.einstellungen
        val tag = Kern.schulTag
        val bloecke = Kern.schultag(tag, ganz = gross).filter { gross || it.zeigen }
        val jetzt = Zeit.jetzt()
        Column(GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).cornerRadius(24.dp).padding(12.dp)) {
            Row(GlanceModifier.fillMaxWidth().clickable(oeffnen(c, "stundenplan")), verticalAlignment = Alignment.CenterVertically) {
                Text("Schule ${Kern.schulTagName}", style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp), modifier = GlanceModifier.defaultWeight())
                Kern.wetter?.let { w -> (if (tag == Zeit.heute()) w.heute else w.morgen)?.let { t ->
                    Text("${Math.round(t.min)}–${Math.round(t.max)}°", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp)) } }
            }
            Spacer(GlanceModifier.height(6.dp))
            if (bloecke.isEmpty()) {
                Text(if (e.unterricht.isEmpty()) "Noch kein Stundenplan – App öffnen" else "Keine Stunden", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp))
            } else {
                LazyColumn(GlanceModifier.defaultWeight()) {
                    items(bloecke) { b -> Zeile(c, b, laeuft = !b.von.isAfter(jetzt) && jetzt.isBefore(b.bis)) }
                }
            }
            if (groesse.height >= 170.dp) {
                Spacer(GlanceModifier.height(6.dp))
                Zahlen(c)
            }
        }
    }

    @Composable
    private fun Zeile(c: Context, b: Block, laeuft: Boolean) {
        val farbe = when (b.art) { Block.Art.VERTRETUNG -> Color(0xFF1A73E8); Block.Art.AUFSICHT -> Color(0xFFE8710A); else -> null }
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).cornerRadius(10.dp)
            .background(if (laeuft) GlanceTheme.colors.secondaryContainer else ColorProvider(Color.Transparent)).clickable(oeffnen(c, "stundenplan")),
            verticalAlignment = Alignment.CenterVertically) {
            Box(GlanceModifier.size(30.dp, 22.dp).cornerRadius(7.dp).background(farbe?.let { ColorProvider(it.copy(alpha = 0.18f)) } ?: GlanceTheme.colors.surfaceVariant),
                contentAlignment = Alignment.Center) {
                Text(b.marke, style = TextStyle(color = farbe?.let { ColorProvider(it) } ?: GlanceTheme.colors.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 11.sp))
            }
            Spacer(GlanceModifier.width(8.dp))
            Column(GlanceModifier.defaultWeight()) {
                b.ersetzt?.let { Text(it, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp, textDecoration = TextDecoration.LineThrough)) }
                Text(b.text, maxLines = 1, style = TextStyle(color = if (b.art == Block.Art.FREI || b.art == Block.Art.PAUSE) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                    fontWeight = if (b.meins) FontWeight.Medium else FontWeight.Normal, fontSize = 13.sp))
            }
            Text(Zeit.uhrzeit(b.von), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
        }
    }

    /** Gleich große Kästen: Zahl und eine Zeile */
    @Composable
    private fun Zahlen(c: Context) {
        val e = Kern.einstellungen
        val k = buildList {
            if (e.abwesenheiten) add(Triple("Fehlen", Kern.abwesendAmTag.size.toString(), "abwesend"))
            if (e.stoerungen) add(Triple("Störungen", (Kern.stoerungen?.offen?.size ?: 0).toString(), "stoerungen"))
            if (e.aufgaben) add(Triple("Aufgaben", (Kern.aufgaben?.offen?.size ?: 0).toString(), "aufgaben"))
        }
        if (k.isEmpty()) return
        Row(GlanceModifier.fillMaxWidth()) {
            k.forEachIndexed { i, (titel, zahl, ziel) ->
                if (i > 0) Spacer(GlanceModifier.width(6.dp))
                Column(GlanceModifier.defaultWeight().height(52.dp).cornerRadius(14.dp).background(GlanceTheme.colors.surfaceVariant).padding(horizontal = 8.dp, vertical = 5.dp)
                    .clickable(oeffnen(c, ziel))) {
                    Text(titel.uppercase(), maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 9.sp, fontWeight = FontWeight.Bold))
                    Text(zahl, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}
