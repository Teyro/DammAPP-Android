package de.oejendorferdamm.dammapp

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import de.oejendorferdamm.dammapp.daten.Kern
import de.oejendorferdamm.dammapp.hintergrund.Hintergrund
import de.oejendorferdamm.dammapp.ui.App
import de.oejendorferdamm.dammapp.ui.Bereich
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Aus Mitteilung oder Widget: diesen Bereich öffnen (dammapp://stoerungen …) */
    private val ziel = mutableStateOf<Bereich?>(null)

    private val erlaubnis = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        lifecycleScope.launch { Kern.aktualisieren() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Kern.init(this)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("vorschau", false)) Kern.vorschauDaten()
        ziel.value = Bereich.aus(intent)
        setContent { App(ziel) }
        val fehlt = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Kern.einstellungen.termine) add(Manifest.permission.READ_CALENDAR)
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (Kern.vorschau) return
        if (fehlt.isNotEmpty() && savedInstanceState == null) erlaubnis.launch(fehlt.toTypedArray())
        Hintergrund.planen(this)
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { Kern.aktualisieren() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ziel.value = Bereich.aus(intent)
    }
}
