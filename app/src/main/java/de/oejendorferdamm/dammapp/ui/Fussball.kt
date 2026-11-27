package de.oejendorferdamm.dammapp.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.oejendorferdamm.dammapp.daten.Kern
import de.oejendorferdamm.dammapp.kern.JSON
import de.oejendorferdamm.dammapp.kern.Nextcloud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fußballplatz-Pläne aus der Nextcloud der Schule (Ordner „DammAPP“): alle PDFs, zwischengespeichert, nur bei Änderung
 * neu geladen (ETag), offline der letzte Stand. Angezeigt mit Androids eingebautem PdfRenderer (keine Bibliothek).
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun FussballSeite(zurueck: () -> Unit) {
    val c = LocalContext.current
    val ordner = remember { File(c.cacheDir, "fussball").apply { mkdirs() } }
    val prefs = remember { c.getSharedPreferences("fussball", android.content.Context.MODE_PRIVATE) }
    var dateien by remember { mutableStateOf(runCatching { JSON.decodeFromString<List<Nextcloud.Datei>>(File(ordner, "liste.json").readText()) }.getOrDefault(emptyList())) }
    var wahl by remember { mutableStateOf(prefs.getString("wahl", "") ?: "") }
    var seiten by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var fehler by remember { mutableStateOf("") }
    val e = Kern.einstellungen
    val zugang = if (Kern.nextcloudBereit) Nextcloud(e.nextcloudServer, e.nextcloudBenutzer, Kern.geheim.nextcloud) else null

    suspend fun zeigen(name: String) {
        val d = dateien.firstOrNull { it.name == name } ?: run { seiten = emptyList(); return }
        seiten = withContext(Dispatchers.IO) {
            val datei = File(ordner, d.name); val etag = File(ordner, d.name + ".etag")
            // nur bei Änderung neu laden
            if ((!datei.exists() || runCatching { etag.readText() }.getOrDefault("") != d.etag) && zugang != null)
                runCatching { zugang.laden(Nextcloud.PLAN_ORDNER + "/" + d.name) }.onSuccess { datei.writeBytes(it); etag.writeText(d.etag) }
            if (!datei.exists()) emptyList() else runCatching {
                PdfRenderer(ParcelFileDescriptor.open(datei, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
                    (0 until r.pageCount).map { i -> r.openPage(i).use { p ->
                        // so breit wie ein großes Display, Seitenverhältnis bleibt
                        val b = Bitmap.createBitmap(1600, (1600f * p.height / p.width).toInt(), Bitmap.Config.ARGB_8888)
                        b.eraseColor(android.graphics.Color.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); b.asImageBitmap()
                    } }
                }
            }.getOrDefault(emptyList())
        }
    }

    suspend fun laden() {
        if (zugang == null) { fehler = "Die Nextcloud der Schule ist noch nicht eingerichtet (Einstellungen → Schule)."; zeigen(wahl); return }
        runCatching { withContext(Dispatchers.IO) { zugang.liste(Nextcloud.PLAN_ORDNER) }.filter { !it.ordner && it.name.endsWith(".pdf", true) }.sortedBy { it.name } }
            .onSuccess { dateien = it; fehler = ""; withContext(Dispatchers.IO) { File(ordner, "liste.json").writeText(JSON.encodeToString(kotlinx.serialization.builtins.ListSerializer(Nextcloud.Datei.serializer()), it)) } }
            .onFailure { fehler = it.message ?: "Nextcloud nicht erreichbar" }
        if (dateien.none { it.name == wahl }) wahl = dateien.firstOrNull()?.name ?: ""
        zeigen(wahl)
    }

    LaunchedEffect(Unit) { laden() }
    LaunchedEffect(wahl) { prefs.edit().putString("wahl", wahl).apply(); zeigen(wahl) }

    Seite("Fußballplatz", zurueck, aktualisieren = { laden() }) {
        if (dateien.size > 1) item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                dateien.forEachIndexed { i, d ->
                    SegmentedButton(wahl == d.name, { wahl = d.name }, SegmentedButtonDefaults.itemShape(i, dateien.size)) { Text(Nextcloud.planName(d.name)) }
                }
            }
        }
        if (fehler.isNotEmpty()) item { FehlerText(fehler) }
        if (seiten.isEmpty() && fehler.isEmpty()) item { Text("Noch kein Plan.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(seiten) { b -> Image(b, null, Modifier.fillMaxWidth().background(Color.White), contentScale = ContentScale.FillWidth) }
    }
}
