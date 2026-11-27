package de.oejendorferdamm.dammapp.daten

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.oejendorferdamm.dammapp.kern.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    /** Nur Debug: Beispieldaten für Bildschirmfotos (nichts wird gespeichert oder geladen) */
    var vorschau = false; private set

    var einstellungen by mutableStateOf(Einstellungen()); private set
    var geheim by mutableStateOf(Geheimnisse()); private set

    var wetter by mutableStateOf<Wetter?>(null)
    var frei by mutableStateOf<List<FreierTag>>(emptyList())
    var vertretung by mutableStateOf<VertretungsPlan?>(null)
    /** Eigene Vertretungs-Statistik (nur über mich), wächst mit jedem geladenen Plan */
    var statistik by mutableStateOf(VertretungsStatistik())
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
    /** Letzte vollständige Aktualisierung (ms) – Startseite „Zuletzt aktualisiert: heute, 06:18 Uhr“ */
    var zuletzt by mutableStateOf(0L)
    /** Quellen, die beim letzten Mal nicht geklappt haben (Name → Fehler) */
    val quellenFehler get() = einstellungen.let { e -> listOf("Vertretungsplan" to (if (e.vertretung) vertretungFehler else ""), "Mensa" to (if (e.mensa) mensaFehler else ""),
        "Abwesenheiten" to (if (e.abwesenheiten) abwesendFehler else ""), "Störungen" to (if (e.stoerungen) stoerungenFehler else ""),
        "Aufgaben" to (if (e.aufgaben) aufgabenFehler else ""), "Untis" to (if (untisFehler.startsWith("Übergangslösung")) "" else untisFehler)).filter { it.second.isNotEmpty() } }
    var laedt by mutableStateOf(false)
    var meldung by mutableStateOf<String?>(null)
    /** Edupool verbinden: angezeigter Code und Freigabeseite, solange gewartet wird */
    var edupoolCode by mutableStateOf<String?>(null)
    var edupoolSeite by mutableStateOf<String?>(null)
    /** IServ: Anmeldung über eine andere Seite (z. B. Schuldock) – erst bestätigen */
    var hostFrage by mutableStateOf<String?>(null)
    /** IServ: Zwei-Faktor-Code von Hand (Hinweistext, solange gewartet wird) */
    var codeHinweis by mutableStateOf<String?>(null)
    /** Einrichtungs-Assistent – einmal für alle (neu oder schon eingerichtet), in den Einstellungen neu startbar */
    var assistentOffen by mutableStateOf(false)
    fun assistentFertig() { assistentOffen = false; app.getSharedPreferences("assistent", Context.MODE_PRIVATE).edit().putBoolean("fertig1", true).apply() }
    private val codeAntwort = SynchronousQueue<String>()

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        ablage = Ablage(app)
        einstellungen = ablage.einstellungen()
        geheim = ablage.geheim()
        wetter = ablage.lies("wetter"); frei = ablage.lies("frei") ?: emptyList(); vertretung = ablage.lies("vertretung")
        personenFarben = ablage.lies("personenFarben") ?: emptyMap()
        statistik = ablage.lies("statistik") ?: VertretungsStatistik()
        untis = ablage.lies("untis"); mensaPlan = ablage.lies("mensa"); obstTage = ablage.lies("obst") ?: emptySet()
        schulTermine = ablage.lies("schultermine") ?: emptyList()
        abwesend = ablage.lies("abwesend"); stoerungen = ablage.lies("stoerungen"); aufgaben = ablage.lies("aufgaben")
        zuletzt = ablage.zeit("zuletzt")
        Melder.kanaele(app)
        assistentOffen = !app.getSharedPreferences("assistent", Context.MODE_PRIVATE).getBoolean("fertig1", false)
    }

    fun aendere(f: (Einstellungen) -> Einstellungen) { einstellungen = f(einstellungen); if (!vorschau) ablage.speichern(einstellungen) }
    fun aendereGeheim(f: (Geheimnisse) -> Geheimnisse) { geheim = f(geheim); ablage.speichern(geheim) }

    // MARK: Abgeleitetes

    /** Tag, für den die Schul-Infos gelten: bis 16 Uhr heute, danach der nächste Schultag */
    val schulTag: LocalDate get() = Tagwahl.ziel(Zeit.jetzt(), frei)
    val schulTagName get() = Tagwahl.name(schulTag)
    val freiHeute get() = FreieTage.am(frei, Zeit.heute())
    fun ausfall(tag: LocalDate) = untis?.meine?.filter { it.datum == tag.toString() && it.entfaellt && it.bis > it.von }?.map { it.von..it.bis } ?: emptyList()
    fun schultag(tag: LocalDate = schulTag, ganz: Boolean = false) = Stundenplan.tag(tag, einstellungen.schulzeiten, einstellungen.unterricht, vertretung, einstellungen.haeuser, ausfall(tag), ganz)
    val heuteBloecke get() = if (freiHeute == null && !Zeit.wochenende(Zeit.heute())) schultag(Zeit.heute()) else emptyList()
    /** Tag fürs Essen: bis 14 Uhr heute, danach der nächste Schultag (Freitagnachmittag = Montag) */
    val essenTag: LocalDate get() = Tagwahl.ziel(Zeit.jetzt(), frei, Tagwahl.ESSEN_STUNDE)
    val essenTagName get() = Tagwahl.name(essenTag)
    val mensaTag get() = mensaPlan?.tag(essenTag.toString())
    val obstSchultag get() = einstellungen.obstBereit && schulTag.toString() in obstTage
    val wetterSchultag get() = if (schulTag == Zeit.heute()) wetter?.heute else wetter?.morgen
    val mitnehmen get() = if (einstellungen.mitnehmen) Mitnehmen.hinweise(schultag(), wetterSchultag) else emptyList()
    val inDerSchule get() = !Zeit.wochenende(Zeit.heute()) && freiHeute == null && Zeit.jetzt().hour in 6..14
    val abwesendAmTag get() = Abwesenheiten.amTag(abwesend?.eintraege ?: emptyList(), schulTag, einstellungen.klassenListe)
    /** Zahl und Wort für die Kachel: IServ ohne Ausgegraute, ab Vertretungsplan „krank + außer Haus“ */
    val abwesendZahl get() = Abwesenheiten.zahl(abwesendAmTag, einstellungen.abwesendAus, vertretung, schulTag)

    // MARK: Laden

    /** Alles neu laden (App öffnen, Ziehen zum Aktualisieren, Hintergrund) */
    suspend fun aktualisieren(erzwingen: Boolean = false) = if (vorschau) Unit else sperre.withLock {
        laedt = true
        try { withContext(Dispatchers.IO) { laden(erzwingen) } } finally { laedt = false }
        zuletzt = System.currentTimeMillis(); ablage.zeit("zuletzt", zuletzt)
        Schultag.aktualisieren(app)
        de.oejendorferdamm.dammapp.widget.Widgets.aktualisieren(app)
    }

    // MARK: Stundenpläne anderer

    var untisFavoriten by mutableStateOf<List<de.oejendorferdamm.dammapp.kern.UntisElement>>(emptyList())
    var untisElemente by mutableStateOf<List<de.oejendorferdamm.dammapp.kern.UntisElement>>(emptyList())
    var untisSucheHinweis by mutableStateOf("")
    /** Farben der Personen (Board „Medien“) für die Blasen im Störungsverlauf */
    var personenFarben by mutableStateOf<Map<String, String>>(emptyMap())
    private var untisSuche: de.oejendorferdamm.dammapp.kern.UntisSuche? = null
    private var sicherungText: String? = null

    val ichAlsElement get() = einstellungen.untisName.takeIf { it.isNotEmpty() }?.let { de.oejendorferdamm.dammapp.kern.UntisElement(2, it, "Mein Stundenplan") }
    fun favorit(e: de.oejendorferdamm.dammapp.kern.UntisElement) = untisFavoriten.any { it.id == e.id }
    fun favoritUmschalten(e: de.oejendorferdamm.dammapp.kern.UntisElement) {
        untisFavoriten = if (favorit(e)) untisFavoriten.filter { it.id != e.id } else untisFavoriten + e
        ablage.schreib("untisFavoriten", untisFavoriten)
    }

    /** App-Zugang der Schul-Nextcloud vollständig? */
    val nextcloudBereit get() = einstellungen.nextcloudServer.isNotEmpty() && einstellungen.nextcloudBenutzer.isNotEmpty() && geheim.nextcloud.isNotEmpty()

    /** Untis-Sicherung: zuerst aus der Nextcloud der Schule (ein Zugang für alles), sonst aus dem Schul-Ordner */
    private fun sicherung(): String? = sicherungText
        ?: (if (nextcloudBereit) runCatching { UntisSicherung.ladenNextcloud(einstellungen.nextcloudServer, einstellungen.nextcloudBenutzer, geheim.nextcloud) }.getOrNull() else null)?.also { sicherungText = it }
        ?: geheim.schulOrdner.takeIf { it.isNotEmpty() }?.let { runCatching { UntisSicherung.laden(it) }.getOrNull() }?.also { sicherungText = it }

    /** Liste aller Klassen, Lehrkräfte und Räume: aus WebUntis, sonst aus der Untis-Sicherung (läuft im Hintergrund-Thread) */
    fun untisSucheVorbereiten(neu: Boolean = false) {
        if (untisFavoriten.isEmpty()) untisFavoriten = ablage.lies("untisFavoriten") ?: emptyList()
        if (untisElemente.isNotEmpty() && !neu) return
        val e = einstellungen; val g = geheim
        runCatching { de.oejendorferdamm.dammapp.kern.UntisSuche.oeffnen(e.untisLink, e.untisSchule, e.untisBenutzer, g.untisPasswort, g.untisSchluessel.replace(" ", "").uppercase()) }
            .onSuccess { untisSuche = it; untisElemente = it.elemente; untisSucheHinweis = "" }
            .onFailure { f ->
                val d = sicherung()
                if (d != null) { untisElemente = de.oejendorferdamm.dammapp.kern.UntisSuche.elemente(d); untisSucheHinweis = "WebUntis nicht erreichbar – Pläne aus der Untis-Sicherung (nur diese und nächste Woche, keine Räume)." }
                else untisSucheHinweis = f.message ?: "WebUntis nicht erreichbar"
            }
    }

    /** Plan eines Elements für Mo–Fr ab [montag] (Hintergrund-Thread) */
    fun plan(e: de.oejendorferdamm.dammapp.kern.UntisElement, montag: LocalDate): List<UntisStunde> {
        untisSuche?.let { s -> runCatching { return s.plan(e, montag) } }
        if (untisSuche == null) untisSucheVorbereiten(neu = true)
        untisSuche?.let { return it.plan(e, montag) }
        val d = sicherung() ?: throw Exception(untisSucheHinweis.ifEmpty { "WebUntis nicht erreichbar." })
        val von = montag.toString(); val bis = montag.plusDays(4).toString()
        return de.oejendorferdamm.dammapp.kern.UntisSuche.plan(d, e).filter { it.datum in von..bis }
    }

    /** Neue Version auf GitHub (für den Hinweis in den Einstellungen) */
    var updateNeu by mutableStateOf<String?>(null)

    /** Wie in allen Damm-Apps: höchstens alle 6 Stunden und nur außerhalb der Stunden suchen; neue Version einmal melden */
    private suspend fun updateSuchen() {
        if (!alt("update", 360, false) || !de.oejendorferdamm.dammapp.update.UpdateZeit.erlaubt()) return
        ablage.zeit("update", System.currentTimeMillis())
        val i = de.oejendorferdamm.dammapp.update.Update.pruefen() ?: return
        if (!de.oejendorferdamm.dammapp.update.Update.istNeuer(i.version, de.oejendorferdamm.dammapp.BuildConfig.VERSION_NAME)) { updateNeu = null; return }
        updateNeu = i.version
        if (ablage.text("updateGemeldet") != i.version) {
            ablage.text("updateGemeldet", i.version)
            Melder.melde(app, "update", "DammAPP ${i.version} ist da", "Tippen zum Installieren (Einstellungen → DammAPP für Android → Nach Update suchen).", "einstellungen", kanal = Melder.KANAL_UPDATE)
        }
    }

    /** Schon eingerichtete Geräte: Nextcloud-Zugang aus den Schul-Vorgaben nachholen (Schul-Code = Schul-Ordner-Passwort) */
    private fun nextcloudNachruesten() {
        if (geheim.nextcloud.isNotEmpty() || geheim.schulOrdner.isEmpty() || !alt("vorgaben", 720, false)) return
        ablage.zeit("vorgaben", System.currentTimeMillis())
        val v = runCatching { SchulVorgaben.laden(geheim.schulOrdner) }.getOrNull() ?: return
        if (v.nextcloudPasswort.isEmpty()) return
        aendere { it.copy(nextcloudServer = it.nextcloudServer.ifEmpty { v.nextcloudServer }, nextcloudBenutzer = it.nextcloudBenutzer.ifEmpty { v.nextcloudBenutzer }) }
        aendereGeheim { it.copy(nextcloud = v.nextcloudPasswort) }
    }

    private suspend fun laden(erzwingen: Boolean) = coroutineScope {
        nextcloudNachruesten()
        launch { runCatching { updateSuchen() } }
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
            else -> r.onSuccess { vertretung = it; vertretungFehler = ""; ablage.schreib("vertretung", it)
                statistik.merken(it).takeIf { s -> s != statistik }?.let { s -> statistik = s; ablage.schreib("statistik", s) } }
                .onFailure { vertretungFehler = if (it.message?.contains("Passwort") == true) "Freigabe-Passwort fehlt oder stimmt nicht" else "Vertretungsplan nicht erreichbar" }
        }
        when (val r = m.await()) {
            null -> { mensaFehler = if (e.mensa && g.mensa.isEmpty()) "Kantinen-Passwort fehlt" else if (!mensaErlaubt) "Kantinen-Zugang wurde abgelehnt – bitte Benutzer oder Passwort prüfen" else "" }
            else -> r.onSuccess { p -> mensaPlan = p; ablage.schreib("mensa", p); mensaFehler = if (p.tag(essenTag.toString()) == null) "Für $essenTagName steht nichts im Speiseplan" else ""; ablage.text("mensaAbgelehnt", null) }
                .onFailure { x -> mensaFehler = x.message ?: "Kantine nicht erreichbar"; if (x is Mensa.Anmeldung) ablage.text("mensaAbgelehnt", Mensa.merkmal(e.mensaBenutzer, g.mensa)) }
        }
        o.await()?.let { obstTage = it; ablage.schreib("obst", it) }
        st.await()?.let { schulTermine = it; ablage.schreib("schultermine", it) }
        moduleLadenIntern(erzwingen)
        untisLaden(erzwingen)
    }

    suspend fun moduleLaden(erzwingen: Boolean = false) = if (vorschau) Unit else withContext(Dispatchers.IO) { moduleLadenIntern(erzwingen) }

    fun vorschauDaten() {
        vorschau = true
        assistentOffen = false
        val heute = Zeit.heute().let { if (Zeit.wochenende(it)) it.plusDays((8 - it.dayOfWeek.value).toLong()) else it }
        val t = heute.atTime(7, 0)
        einstellungen = Einstellungen(kuerzel = "Sta", vertretungLink = "https://cloud.example.de/s/Beispiel", haeuser = mapOf("Haus 2" to listOf("2a", "2b", "2c")),
            unterricht = (1..5).flatMap { d -> listOf(Unterricht(d, "1", "5a", "Mathe", "R12"), Unterricht(d, "4", "6b", "Deutsch", "R7")) },
            mensa = true, mensaAdresse = "https://kantine.example.de", mensaBenutzer = "x", obst = true, obstKalender = "https://x", abwesenheiten = true, stoerungen = true, aufgaben = true,
            kachelReihenfolge = Startseite.alle)
        wetter = Wetter(4.0, 3, WetterTag(61, 8.0, 2.0, 70, 20.0), WetterTag(2, 10.0, 3.0, 10, 15.0), t,
            (0 until 36).map { i -> val z = t.plusMinutes(15L * i); val m = z.hour * 60 + z.minute; val nass = m in 601..630
                WetterSchritt(z, if (nass) 63 else 2, 4 + i / 9.0, if (nass) 0.6 else 0.0, if (nass) 80 else 10, 20.0) })
        vertretung = VertretungsPlan(datum = heute.toString()).apply { stunden.add(VertretungsPlan.Stunde("2", "2c")); stunden.add(VertretungsPlan.Stunde("Frühbetreuung Mensa", "")); bemerkungen = listOf(Bemerkung(listOf(4), "Begleitung Nelson", "2c")); pausen.add(VertretungsPlan.Pause("2. Pause", "Mensa + Haltestelle"))
            alleHinweise.add("Dienstbesprechung um 14 Uhr im Lehrerzimmer") }
        mensaPlan = MensaPlan(listOf(MensaTag(heute.toString(), mutableListOf(Gericht("Menü 1", "Spaghetti Bolognese", true), Gericht("Menü 2", "Gemüsecurry mit Reis (vegan)")))), 23.40)
        obstTage = setOf(heute.toString())
        schulTermine = listOf(SchulKalender.Termin(heute.plusDays(3).toString(), "Elternsprechtag"), SchulKalender.Termin(heute.plusDays(7).toString(), "Laternenumzug"))
        abwesend = AbwesenheitenStand(listOf(Abwesenheit("Mia Klein", "2b", heute.toString(), heute.toString(), "krank"), Abwesenheit("Ben Groß", "2b", heute.minusDays(1).toString(), heute.plusDays(1).toString(), "entschuldigt")))
        einstellungen = einstellungen.copy(abwesendAus = listOf("Ben Groß")); vertretung?.apply { fehlen = listOf("Kru", "Kut", "Reh", "Kha", "Beu"); ausserListe = listOf("Ayd", "Wa+Hoff (8:30-12:15)") }
        stoerungen = StoerungenStand(listOf(
            Stoerung("Beamer flackert", "Der Beamer in der 2b flackert nach 10 Minuten.", "Kim Muster", "", Zeit.jetzt().minusHours(3), "Neu", "Raum 2b", false, true, ""),
            Stoerung("WLAN im Haus 2 langsam", "", "Alex Beispiel", "IT-Team", Zeit.jetzt().minusDays(1), "In Bearbeitung", "Haus 2", false, false, ""),
            Stoerung("Drucker Lehrerzimmer Papierstau", "", "Kim Muster", "IT-Team", Zeit.jetzt().minusDays(3), "Erledigt", "Lehrerzimmer", true, false, "")))
        aufgaben = AufgabenStand(listOf(
            Aufgabe("1", "Zeugniskonferenz vorbereiten", "Noten bis Freitag eintragen", emptyList(), Zeit.jetzt(), "b", "Klasse 2b", "In Bearbeitung", 2, 0.0, 3),
            Aufgabe("2", "Elternbrief Laternenumzug", "", emptyList(), Zeit.jetzt(), "b", "Klasse 2b", "Zu erledigen", 1, 1.0, 0)), 2)
    }

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
                    return when (f) {
                        is IServFehler.NichtGefunden -> "Angemeldet, aber: ${f.message} In den Einstellungen „Neu suchen“ tippen." + (if (s.protokoll.isEmpty()) "" else " (Geprüft: ${s.protokoll.distinct().take(8).joinToString(", ")})")
                        is IServFehler -> f.message ?: "IServ-Fehler"
                        is java.net.UnknownHostException -> "Keine Verbindung zu ${iservZugang.server} – Internet oder IServ-Adresse prüfen."
                        is java.net.SocketTimeoutException -> "IServ antwortet nicht (Zeitüberschreitung)."
                        is javax.net.ssl.SSLException -> "IServ: sichere Verbindung fehlgeschlagen (${f.message})."
                        else -> "IServ nicht erreichbar: ${f.message ?: f.javaClass.simpleName}"
                    }
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
            .onSuccess {
                aufgaben = it; aufgabenFehler = ""; ablage.schreib("aufgaben", it); ablage.zeit("aufgaben", System.currentTimeMillis())
                Aufgaben.personenFarben(geheim.edupool).takeIf { pf -> pf.isNotEmpty() }?.let { pf -> personenFarben = pf; ablage.schreib("personenFarben", pf) }
            }
            .onFailure { aufgabenFehler = it.message ?: "Edupool nicht erreichbar" }
        if (e.mitteilungen || e.vertretungMelden || e.vertretungDa) Melder.pruefen(app, ablage,
            if (e.mitteilungen && e.stoerungen) stoerungen else null, if (e.mitteilungen && e.aufgaben) aufgaben else null,
            vertretung, e.vertretungDa, e.vertretungMelden, e.vertretungDringend)
    }

    /** WebUntis: höchstens alle 6 Stunden; mit „übernehmen“ wird die Grundwoche zu „Mein Stundenplan“ */
    fun untisLaden(erzwingen: Boolean = false) {
        val e = einstellungen; val g = geheim
        // Echter Untis-Zugang schlägt die Sicherung; ohne eigenen Zugang reicht das Kürzel (Übergangslösung über die Nextcloud)
        val eigen = e.untisAn && e.untisLink.isNotEmpty() && (g.untisPasswort.isNotEmpty() || g.untisSchluessel.isNotEmpty() || Untis.qr(e.untisLink) != null)
        val ersatz = e.untisName.isNotEmpty() && (nextcloudBereit || g.schulOrdner.isNotEmpty())
        if (!eigen && !ersatz) return
        if (!alt("untis", 360, erzwingen)) return
        val kuerzel = e.untisName
        val st = runCatching {
            if (!eigen) throw java.io.IOException("Kein eigener Untis-Zugang.")
            Untis.laden(e.untisLink, e.untisSchule, e.untisBenutzer, g.untisPasswort, g.untisSchluessel.replace(" ", "").uppercase(), kuerzel)
        }
            .onSuccess { untisFehler = "" }
            .getOrElse { fehler ->
                // WebUntis fällt aus oder kein eigener Zugang: die tägliche Untis-Sicherung (Nextcloud, sonst Schul-Ordner)
                val s = sicherung()?.let { runCatching { UntisSicherung.lesen(it, kuerzel) }.getOrNull() }
                val stand = s?.stand?.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM. HH:mm"))
                untisFehler = when {
                    s == null -> fehler.message ?: "WebUntis nicht erreichbar"
                    eigen -> "WebUntis nicht erreichbar – Stand aus der Untis-Sicherung vom $stand."
                    else -> "Übergangslösung: Stundenplan aus der Untis-Sicherung der Schule (Stand $stand). Ein eigener Untis-Zugang wird empfohlen."
                }
                s
            } ?: return
        untis = st; ablage.schreib("untis", st); ablage.zeit("untis", System.currentTimeMillis())
        if (e.untisUebernehmen) {
            // Rangfolge: von Hand Eingetragenes bleibt, Untis füllt freie Stunden und pflegt nur seine eigenen Einträge
            val neu = Untis.unterricht(st, e.schulzeiten)
            val vorher = ablage.lies<List<Unterricht>>("untisUnterricht") ?: emptyList()
            val zusammen = UntisSicherung.zusammenfuehren(e.unterricht, neu, vorher)
            if (neu.isNotEmpty() && zusammen.map { it.copy(id = "") } != e.unterricht.map { it.copy(id = "") }) aendere { it.copy(unterricht = zusammen) }
            ablage.schreib("untisUnterricht", neu)
        }
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
}
