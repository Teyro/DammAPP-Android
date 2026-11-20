package de.oejendorferdamm.dammapp.daten

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.oejendorferdamm.dammapp.kern.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

/**
 * Hält alles, was App, Widget und Hintergrund brauchen, und lädt es (wie Kern.swift der iOS-App, ohne Wecker und Post).
 * Zustände sind Compose-States – die Oberfläche zeichnet sich bei Änderungen selbst neu.
 */
object Kern {
    lateinit var app: Context
    lateinit var ablage: Ablage
    private val sperre = Mutex()

    var einstellungen by mutableStateOf(Einstellungen()); private set
    var geheim by mutableStateOf(Geheimnisse()); private set

    var wetter by mutableStateOf<Wetter?>(null)
    var frei by mutableStateOf<List<FreierTag>>(emptyList())
    var vertretung by mutableStateOf<VertretungsPlan?>(null)
    var vertretungFehler by mutableStateOf("")
    var untis by mutableStateOf<UntisStand?>(null)
    var untisFehler by mutableStateOf("")
    var mensaPlan by mutableStateOf<MensaPlan?>(null)
    var mensaFehler by mutableStateOf("")
    var obstTage by mutableStateOf<Set<String>>(emptySet())
    var schulTermine by mutableStateOf<List<SchulKalender.Termin>>(emptyList())
    var termine by mutableStateOf<List<Termin>>(emptyList())
    var abwesend by mutableStateOf<AbwesenheitenStand?>(null)
    var abwesendFehler by mutableStateOf("")
    var stoerungen by mutableStateOf<StoerungenStand?>(null)
    var stoerungenFehler by mutableStateOf("")
    var aufgaben by mutableStateOf<AufgabenStand?>(null)
    var aufgabenFehler by mutableStateOf("")
    var laedt by mutableStateOf(false)
    var meldung by mutableStateOf<String?>(null)
    /** Edupool verbinden: angezeigter Code und Freigabeseite, solange gewartet wird */
    var edupoolCode by mutableStateOf<String?>(null)
    var edupoolSeite by mutableStateOf<String?>(null)
    /** IServ: Anmeldung über eine andere Seite (z. B. Schuldock) – erst bestätigen */
    var hostFrage by mutableStateOf<String?>(null)
    /** IServ: Zwei-Faktor-Code von Hand (Hinweistext, solange gewartet wird) */
    var codeHinweis by mutableStateOf<String?>(null)
    private val codeAntwort = SynchronousQueue<String>()

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        ablage = Ablage(app)
        einstellungen = ablage.einstellungen()
        geheim = ablage.geheim()
        wetter = ablage.lies("wetter"); frei = ablage.lies("frei") ?: emptyList(); vertretung = ablage.lies("vertretung")
        untis = ablage.lies("untis"); mensaPlan = ablage.lies("mensa"); obstTage = ablage.lies("obst") ?: emptySet()
        schulTermine = ablage.lies("schultermine") ?: emptyList()
        abwesend = ablage.lies("abwesend"); stoerungen = ablage.lies("stoerungen"); aufgaben = ablage.lies("aufgaben")
        Melder.kanaele(app)
    }

    fun aendere(f: (Einstellungen) -> Einstellungen) { einstellungen = f(einstellungen); ablage.speichern(einstellungen) }
    fun aendereGeheim(f: (Geheimnisse) -> Geheimnisse) { geheim = f(geheim); ablage.speichern(geheim) }

    // MARK: Abgeleitetes

    /** Tag, für den die Schul-Infos gelten: bis 16 Uhr heute, danach der nächste Schultag */
    val schulTag: LocalDate get() = Tagwahl.ziel(Zeit.jetzt(), frei)
    val schulTagName get() = Tagwahl.name(schulTag)
    val freiHeute get() = FreieTage.am(frei, Zeit.heute())
    fun ausfall(tag: LocalDate) = untis?.meine?.filter { it.datum == tag.toString() && it.entfaellt && it.bis > it.von }?.map { it.von..it.bis } ?: emptyList()
    fun schultag(tag: LocalDate = schulTag, ganz: Boolean = false) = Stundenplan.tag(tag, einstellungen.schulzeiten, einstellungen.unterricht, vertretung, einstellungen.haeuser, ausfall(tag), ganz)
    val heuteBloecke get() = if (freiHeute == null && !Zeit.wochenende(Zeit.heute())) schultag(Zeit.heute()) else emptyList()
    val mensaTag get() = mensaPlan?.tag(schulTag.toString())
    val obstSchultag get() = einstellungen.obstBereit && schulTag.toString() in obstTage
    val wetterSchultag get() = if (schulTag == Zeit.heute()) wetter?.heute else wetter?.morgen
    val mitnehmen get() = if (einstellungen.mitnehmen) Mitnehmen.hinweise(schultag(), wetterSchultag) else emptyList()
    val ha get() = HomeAssistant(einstellungen.haAdresse, geheim.ha)
    val inDerSchule get() = !Zeit.wochenende(Zeit.heute()) && freiHeute == null && Zeit.jetzt().hour in 6..14
    val abwesendAmTag get() = Abwesenheiten.amTag(abwesend?.eintraege ?: emptyList(), schulTag, einstellungen.klassenListe)

    // MARK: Laden

    /** Alles neu laden (App öffnen, Ziehen zum Aktualisieren, Hintergrund) */
    suspend fun aktualisieren(erzwingen: Boolean = false) = sperre.withLock {
        laedt = true
        try { withContext(Dispatchers.IO) { laden(erzwingen) } } finally { laedt = false }
        Schultag.aktualisieren(app)
        de.oejendorferdamm.dammapp.widget.Widgets.aktualisieren(app)
    }

    private suspend fun laden(erzwingen: Boolean) = coroutineScope {
        val e = einstellungen; val g = geheim
        val ziel = schulTag.toString()
        val w = async { runCatching { Wetter.laden(e.lat, e.lon) }.getOrNull() }
        val f = async { if (erzwingen || System.currentTimeMillis() - ablage.zeit("frei") > 7 * 86_400_000L || frei.isEmpty()) runCatching { FreieTage.laden(e.bundesland) }.getOrNull() else null }
        val v = async { if (e.vertretung && e.vertretungLink.isNotEmpty() && e.kuerzel.isNotEmpty()) runCatching { Vertretung.laden(e.vertretungLink, e.kuerzel, g.freigabe, Vertretung.zielTag(LocalDate.parse(ziel))) } else null }
        val mensaErlaubt = ablage.text("mensaAbgelehnt") != Mensa.merkmal(e.mensaBenutzer, g.mensa)
        val m = async { if (e.mensaBereit && g.mensa.isNotEmpty() && mensaErlaubt) runCatching { Mensa.ladePlan(e.mensaAdresse, e.mensaBenutzer, g.mensa) } else null }
        val o = async { if (e.obstBereit) runCatching { SchulKalender.tage(SchulKalender.rohtext(e.obstKalender)) }.getOrNull() else null }
        val st = async { if (e.schulKalender.isNotEmpty()) runCatching { SchulKalender.termine(SchulKalender.rohtext(e.schulKalender)) }.getOrNull() else null }
        termine = if (e.termine) Kalender.termine(app, 3) else emptyList()
        w.await()?.let { wetter = it; ablage.schreib("wetter", it) }
        f.await()?.let { frei = it; ablage.schreib("frei", it); ablage.zeit("frei", System.currentTimeMillis()) }
        when (val r = v.await()) {
            null -> { vertretung = null; vertretungFehler = "" }
            else -> r.onSuccess { vertretung = it; vertretungFehler = ""; ablage.schreib("vertretung", it) }
                .onFailure { vertretungFehler = if (it.message?.contains("Passwort") == true) "Freigabe-Passwort fehlt oder stimmt nicht" else "Vertretungsplan nicht erreichbar" }
        }
        when (val r = m.await()) {
            null -> { mensaFehler = if (e.mensa && g.mensa.isEmpty()) "Kantinen-Passwort fehlt" else if (!mensaErlaubt) "Kantinen-Zugang wurde abgelehnt – bitte Benutzer oder Passwort prüfen" else "" }
            else -> r.onSuccess { p -> mensaPlan = p; ablage.schreib("mensa", p); mensaFehler = if (p.tag(ziel) == null) "Für $schulTagName steht nichts im Speiseplan" else ""; ablage.text("mensaAbgelehnt", null) }
                .onFailure { x -> mensaFehler = x.message ?: "Kantine nicht erreichbar"; if (x is Mensa.Anmeldung) ablage.text("mensaAbgelehnt", Mensa.merkmal(e.mensaBenutzer, g.mensa)) }
        }
        o.await()?.let { obstTage = it; ablage.schreib("obst", it) }
        st.await()?.let { schulTermine = it; ablage.schreib("schultermine", it) }
        moduleLadenIntern(erzwingen)
        untisLaden(erzwingen)
    }

    suspend fun moduleLaden(erzwingen: Boolean = false) = withContext(Dispatchers.IO) { moduleLadenIntern(erzwingen) }

    private fun alt(name: String, minuten: Int, erzwingen: Boolean) = erzwingen || System.currentTimeMillis() - ablage.zeit(name) > minuten * 60_000L

    val iservZugang get() = IServZugang(IServ.bereinige(einstellungen.iservServer), einstellungen.iservBenutzer, geheim.iserv, geheim.totp)
    private val iservMerkmal get() = Mensa.merkmal(einstellungen.iservServer + "|" + einstellungen.iservBenutzer, geheim.iserv)

    /** IServ-Sitzung; Cookies bleiben gespeichert (spart neue Zwei-Faktor-Codes). [vonHand]: Code darf erfragt werden */
    fun iservSitzung(vonHand: Boolean = true): IServ {
        val kekse = Browser.Kekse.aus(ablage.text("iservKekse") ?: "")
        return IServ(iservZugang, einstellungen.iservHosts.toSet(), kekse, if (vonHand) { h -> codeErfragen(h) } else null)
    }
    private fun keksMerken(s: IServ) = ablage.text("iservKekse", s.kekse.text())

    private fun codeErfragen(hinweis: String): String? {
        codeHinweis = hinweis.ifEmpty { "6-stelligen Code aus deiner Authenticator-App eingeben." }
        val c = codeAntwort.poll(5, TimeUnit.MINUTES)
        codeHinweis = null
        return c?.ifEmpty { null }
    }
    fun codeEingegeben(code: String?) { codeAntwort.offer(code ?: "") ; codeHinweis = null }

    private fun moduleLadenIntern(erzwingen: Boolean) {
        val e = einstellungen
        if ((e.abwesenheiten || e.stoerungen) && iservZugang.vollstaendig) {
            if (ablage.text("iservAbgelehnt") == iservMerkmal && !erzwingen) {
                abwesendFehler = "IServ hat die Zugangsdaten abgelehnt – bitte prüfen."; stoerungenFehler = abwesendFehler
            } else {
                val s = iservSitzung(vonHand = erzwingen)
                fun fehler(f: Throwable): String {
                    if (f is IServFehler.Zugangsdaten) ablage.text("iservAbgelehnt", iservMerkmal)
                    if (f is IServFehler.NeuerHost) hostFrage = f.host
                    return if (f is IServFehler) f.message ?: "IServ-Fehler" else "IServ nicht erreichbar"
                }
                if (e.abwesenheiten && alt("abwesend", 10, erzwingen)) runCatching { Abwesenheiten.laden(s, e.abwesendQuelle) }
                    .onSuccess { (st, q) -> abwesend = st; abwesendFehler = ""; ablage.schreib("abwesend", st); ablage.zeit("abwesend", System.currentTimeMillis()); ablage.text("iservAbgelehnt", null)
                        if (q != e.abwesendQuelle) aendere { it.copy(abwesendQuelle = q) } }
                    .onFailure { abwesendFehler = fehler(it) }
                if (e.stoerungen && alt("stoerungen", 15, erzwingen)) runCatching { Stoerungen.laden(s, e.stoerungQuelle) }
                    .onSuccess { (st, q) -> stoerungen = st; stoerungenFehler = ""; ablage.schreib("stoerungen", st); ablage.zeit("stoerungen", System.currentTimeMillis())
                        if (q != e.stoerungQuelle) aendere { it.copy(stoerungQuelle = q) } }
                    .onFailure { stoerungenFehler = fehler(it) }
                keksMerken(s)
            }
        }
        if (e.aufgaben && geheim.edupool.isNotEmpty() && alt("aufgaben", 30, erzwingen)) runCatching { Aufgaben.laden(geheim.edupool, e.aufgabenFarbe) }
            .onSuccess { aufgaben = it; aufgabenFehler = ""; ablage.schreib("aufgaben", it); ablage.zeit("aufgaben", System.currentTimeMillis()) }
            .onFailure { aufgabenFehler = it.message ?: "Edupool nicht erreichbar" }
        if (e.mitteilungen || e.vertretungMelden || e.vertretungDa) Melder.pruefen(app, ablage,
            if (e.mitteilungen && e.stoerungen) stoerungen else null, if (e.mitteilungen && e.aufgaben) aufgaben else null,
            vertretung, e.vertretungDa, e.vertretungMelden, e.vertretungDringend)
    }

    /** WebUntis: höchstens alle 6 Stunden; mit „übernehmen“ wird die Grundwoche zu „Mein Stundenplan“ */
    fun untisLaden(erzwingen: Boolean = false) {
        val e = einstellungen; val g = geheim
        if (!e.untisAn || e.untisLink.isEmpty() || (g.untisPasswort.isEmpty() && g.untisSchluessel.isEmpty() && Untis.qr(e.untisLink) == null)) return
        if (!alt("untis", 360, erzwingen)) return
        runCatching { Untis.laden(e.untisLink, e.untisSchule, e.untisBenutzer, g.untisPasswort, g.untisSchluessel.replace(" ", "").uppercase(), e.kuerzel.ifEmpty { e.untisBenutzer }) }
            .onSuccess { st ->
                untis = st; untisFehler = ""; ablage.schreib("untis", st); ablage.zeit("untis", System.currentTimeMillis())
                if (e.untisUebernehmen) { val neu = Untis.unterricht(st, e.schulzeiten); if (neu.isNotEmpty() && neu.map { it.copy(id = "") } != e.unterricht.map { it.copy(id = "") }) aendere { it.copy(unterricht = neu) } }
            }
            .onFailure { untisFehler = it.message ?: "WebUntis nicht erreichbar" }
    }

    // MARK: Aktionen

    fun hostErlauben(h: String) { aendere { it.copy(iservHosts = (it.iservHosts + h).distinct()) }; hostFrage = null }

    /** Edupool verbinden: Code holen, Freigabeseite öffnen, dann abholen (Netzfehler brechen nicht ab) */
    suspend fun edupoolVerbinden(oeffne: (String) -> Unit) = withContext(Dispatchers.IO) {
        try {
            val f = Aufgaben.freigabeStarten()
            edupoolCode = f.userCode; edupoolSeite = f.seite
            withContext(Dispatchers.Main) { oeffne(f.seite) }
            while (System.currentTimeMillis() < f.ablaufMs && edupoolCode != null) {
                Thread.sleep(f.intervall * 1000)
                val t = try { Aufgaben.freigabeAbholen(f) } catch (x: Aufgaben.Fehler) { throw x } catch (_: Exception) { continue }
                if (t != null) {
                    aendereGeheim { it.copy(edupool = t) }; aendere { it.copy(aufgaben = true) }
                    edupoolCode = null; meldung = "Mit Edupool verbunden"
                    moduleLadenIntern(true)
                    return@withContext
                }
            }
            if (edupoolCode != null) meldung = "Der Code ist abgelaufen – bitte noch einmal verbinden."
        } catch (x: Exception) { meldung = x.message }
        edupoolCode = null
    }

    fun briefing(): String = Briefing.morgen(Briefing.Daten(wetter = wetter, termine = termine, vertretung = if (einstellungen.vertretung) vertretung else null,
        haeuser = einstellungen.haeuser, mensa = if (einstellungen.mensa) mensaTag else null, mensaWahl = einstellungen.favoriten to einstellungen.meiden,
        gemerkt = einstellungen.lieblingsGerichte to einstellungen.nichtLecker, obst = obstSchultag, schulTag = schulTag, frei = freiHeute,
        schultag = schultag(), mitnehmen = einstellungen.mitnehmen))

    /** Gute Nacht: Home-Assistant-Aktionen, Nachtlampe, offene Fenster – Text zum Vorlesen */
    suspend fun guteNacht(): String = withContext(Dispatchers.IO) {
        val h = ha
        if (!h.eingerichtet) return@withContext "Home Assistant ist noch nicht eingerichtet."
        val erledigt = einstellungen.nacht.filter { h.ausfuehren(it) }.map { it.name }.toMutableList()
        val fenster = h.offeneFenster()
        if (einstellungen.nachtlampe.isNotEmpty() && h.nachtlampe(einstellungen.nachtlampe, einstellungen.nachtlampeHelligkeit)) erledigt.add("Nachtlampe an")
        val s = ArrayList<String>()
        if (erledigt.isNotEmpty()) s.add(Stundenplan.aufzaehlen(erledigt) + " – erledigt.")
        fenster.take(4).forEach { s.add("Achtung: $it ist offen.") }
        wetter?.let { s.add(it.satz(true)) }
        s.add("Gute Nacht!")
        s.joinToString(" ")
    }
}
