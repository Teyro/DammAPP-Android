@file:kotlinx.serialization.UseSerializers(LDTSerializer::class, LDSerializer::class)

package de.oejendorferdamm.dammapp.kern

import java.net.HttpURLConnection
import java.net.URL

data class IServZugang(val server: String, val benutzer: String, val passwort: String, val totp: String = "") {
    val vollstaendig get() = server.isNotEmpty() && benutzer.isNotEmpty() && passwort.isNotEmpty()
}

/** Wo die Daten eines Moduls liegen (einmal gefunden, dann direkt geladen) */
@kotlinx.serialization.Serializable
data class IServQuelle(val url: String, val art: String, val referer: String = "", val spalten: List<String> = emptyList())

sealed class IServFehler(text: String) : Exception(text) {
    class Zugangsdaten(t: String) : IServFehler(t)
    class ZweiFaktor(t: String) : IServFehler(t)
    class NeuerHost(val host: String) : IServFehler("Die Anmeldung läuft über $host – bitte in der App bestätigen.")
    class NichtGefunden(t: String) : IServFehler(t)
    class Allgemein(t: String) : IServFehler(t)
}

/**
 * Eine IServ-Sitzung wie im Browser: folgt Weiterleitungen, vorgeschalteter Anmeldung (Schuldock), Rücksprung-Formularen
 * und dem zweiten Faktor. Cookies hält [kekse] (wird dauerhaft gespeichert – so bleibt die Sitzung über Neustarts).
 */
class IServ(val zugang: IServZugang, val erlaubteHosts: Set<String> = emptySet(), val kekse: Browser.Kekse = Browser.Kekse(),
            /** Zwei-Faktor-Code von Hand (nur in der App; im Hintergrund null) */
            val codeAbfrage: ((String) -> String?)? = null) {
    data class Seite(val body: String, val url: String, val status: Int, val typ: String)

    val basis get() = "https://" + zugang.server

    companion object {
        /** „https://schule.de/iserv/…“ → „schule.de“ */
        fun bereinige(s: String) = s.trim().replace(Regex("^https?://", RegexOption.IGNORE_CASE), "").replace(Regex("/.*$"), "").lowercase()

        fun istLoginSeite(s: Seite) = Html.hat("/iserv/(auth|app)/login", s.url) || Html.hat("""name=["']_password["']""", s.body)

        data class CodeFormular(val action: String, val felder: List<Pair<String, String>>, val codeFeld: String, val ziffern: List<String>, val versteckt: List<String>)

        fun codeFormular(html: String): CodeFormular? {
            for (f in Html.formulare(html)) {
                if (Html.hat("""name=["']_password["']""", f)) continue
                val eingaben = Html.eingaben(f)
                val code = eingaben.firstOrNull { i -> Html.typ(i) !in listOf("hidden", "checkbox", "radio", "submit", "button", "password") && Html.hat("code|otp|totp|token|2fa|auth|factor", Html.name(i)) } ?: continue
                val felder = ArrayList<Pair<String, String>>()
                for (i in eingaben) {
                    val n = Html.name(i); if (n.isEmpty()) continue
                    if (Html.typ(i) == "hidden") felder.add(n to Html.entities(Html.attr(i, "value") ?: ""))
                    if (Html.typ(i) == "checkbox" && Html.hat("trust|remember|vertrau|merken", i)) felder.add(n to (Html.attr(i, "value") ?: "1"))
                }
                val ziffern = eingaben.filter { Html.typ(it) != "hidden" && Html.hat("""digit_?\d""", Html.name(it)) }.map { Html.name(it) }
                    .sortedBy { Html.erster("""digit_?(\d)""", it)?.toIntOrNull() ?: 0 }
                val versteckt = eingaben.filter { Html.typ(it) == "hidden" && Html.hat("otp|code|totp", Html.name(it)) && !Html.hat("csrf|token", Html.name(it)) }.map { Html.name(it) }
                Html.erster("""(<button[^>]*name=["'][^"']+["'][^>]*>)""", f)?.let { k -> if (Html.typ(k) != "button") felder.add(Html.name(k) to (Html.attr(k, "value") ?: "")) }
                return CodeFormular(Html.entities(Html.erster("""<form[^>]*action=["']([^"']*)["']""", f) ?: ""), felder, Html.name(code), ziffern, versteckt)
            }
            return null
        }

        fun anmeldeFormular(html: String): String? {
            val formen = Html.formulare(html).filter { !Html.hat("logout|abmelden", Html.erster("(<form[^>]*>)", it) ?: "") }
            return formen.firstOrNull { f -> Html.eingaben(f).any { Html.typ(it) == "password" } }
                ?: formen.firstOrNull { f -> Html.eingaben(f).any { Html.hat("user|login|account|mail", Html.name(it)) && Html.typ(it) !in listOf("hidden", "checkbox", "submit") } }
        }

        /** Link oder Formular zur vorgeschalteten Anmeldung (Schuldock, SSO …) */
        fun ssoLink(html: String, von: String): Pair<String?, String?>? {
            for ((href, t) in Html.links(html)) {
                val h = Html.entities(href)
                if (Html.hat("schuldock|single sign|sso|oauth|openid|oidc|saml|login mit|anmelden mit", Html.text(t) + " " + h) && !Html.hat("logout|hilfe|help|datenschutz", h))
                    return Html.absolut(h, von) to null
            }
            for (f in Html.formulare(html)) if (Html.eingaben(f).none { Html.typ(it) == "password" } && Html.hat("schuldock|sso|oauth|openid|oidc|saml|login mit", f)) return null to f
            return null
        }

        fun weiterleitung(b: String, von: String): String? {
            for (m in Html.alle("(<meta\\b[^>]*>)", b)) if (Html.hat("""http-equiv=["']?refresh""", m)) {
                Html.erster("""content=["'][^"']*?url=([^"'>]+)["']""", m)?.let { return Html.absolut(Html.entities(it.trim()), von) }
            }
            if (Html.hat("""/iserv/auth/auth\b""", von)) {
                (Html.erster("""url=([^"'\s>]+)""", b) ?: Html.erster("""(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']""", b)
                    ?: Html.erster("""data-(?:redirect|url|target|href)[a-z-]*=["']([^"']+)["']""", b))?.let { return Html.absolut(Html.entities(it), von) }
            }
            return null
        }

        /** Adressen, die etwas ändern würden – nie aufrufen */
        fun aendert(u: String) = Html.hat("""logout|abmelden|delete|loeschen|löschen|remove|/add\b|hinzu|/edit\b|bearbeit|/new\b|create|update|confirm|accept|reject|assign|toggle""", u)

        fun datenKandidaten(html: String, von: String, muster: String = "absence|abwesen"): List<String> {
            val urls = Html.alle("""(?:data-[a-z-]*(?:url|source|src|endpoint|api)[a-z-]*|src|href|action)=["']([^"']+)["']""", html).map { Html.entities(it) }
                .filter { Html.hat("json|api|list|data|export|csv|absence|abwesen|sick|krank|defect|ticket", it) && !Html.hat("""\.(css|js|png|svg|ico|woff2?)(\?|$)""", it) }
                .map { Html.absolut(it, von) } +
                // auch Pfade aus Skripten (fetch/ajax) wie im alten Abwesenheiten-Widget
                Html.alle("""["'](/iserv/[^"'\s<>]*(?:$muster)[^"'\s<>]*)["']""", html).map { Html.absolut(Html.entities(it), von) }
            return urls.filter { !aendert(it) }.distinct()
        }
    }

    // MARK: Netz (Weiterleitungen selbst, damit die Cookies jeder Station ankommen)

    private val browser = Browser(kekse)

    fun anfrage(url: String, methode: String = "GET", kopf: Map<String, String> = emptyMap(), felder: List<Pair<String, String>>? = null): Seite =
        browser.anfrage(url, methode, kopf, felder?.let { Html.kodiere(it).toByteArray() }, if (felder != null) "application/x-www-form-urlencoded" else null)
            .let { Seite(it.text, it.url, it.status, it.typ) }

    /** Seite laden; folgt auch <meta refresh> und IServs Zwischenseite /iserv/auth/auth */
    fun hole(pfad: String, kopf: Map<String, String> = emptyMap()): Seite {
        var url = if (pfad.lowercase().startsWith("http")) pfad else basis + pfad
        repeat(4) {
            val s = anfrage(url, kopf = kopf)
            url = weiterleitung(s.body, s.url) ?: return s
        }
        throw IServFehler.Allgemein("Zu viele Weiterleitungen")
    }

    fun autoFormulare(start: Seite): Seite {
        var s = start
        repeat(4) {
            val f = Html.formulare(s.body).firstOrNull { x ->
                val e = Html.eingaben(x)
                e.isNotEmpty() && e.all { Html.typ(it) in listOf("hidden", "submit") } &&
                    (Html.hat("""SAMLResponse|RelayState|name=["'](code|state|id_token|session_state)["']""", x) || Html.hat("""\.submit\(\)|onload=""", s.body))
            } ?: return s
            val felder = Html.eingaben(f).filter { Html.typ(it) == "hidden" && Html.name(it).isNotEmpty() }.map { Html.name(it) to Html.entities(Html.attr(it, "value") ?: "") }
            val ziel = Html.absolut(Html.entities(Html.erster("""<form[^>]*action=["']([^"']*)["']""", f) ?: s.url), s.url)
            val get = (Html.erster("""<form[^>]*method=["']?(\w+)""", f) ?: "").lowercase() == "get"
            s = if (get) anfrage(ziel + (if (ziel.contains("?")) "&" else "?") + Html.kodiere(felder), kopf = mapOf("Referer" to s.url))
                else anfrage(ziel, "POST", mapOf("Referer" to s.url), felder)
        }
        return s
    }

    fun hostPruefen(url: String) {
        if (!url.lowercase().startsWith("https:")) throw IServFehler.Zugangsdaten("Die Anmeldeseite ist nicht verschlüsselt – abgebrochen.")
        val host = runCatching { URL(url).host.lowercase() }.getOrDefault("")
        if (host == zugang.server || host in erlaubteHosts) return
        throw IServFehler.NeuerHost(host)
    }

    fun zweiterFaktor(start: Seite): Seite {
        var s = start
        val quellen = ArrayList<String>()
        if (zugang.totp.isNotEmpty()) quellen.add("erzeugt")
        if (codeAbfrage != null) quellen += listOf("hand", "hand")
        var hinweis = ""
        for (q in quellen) {
            val f = codeFormular(s.body) ?: return s
            val code = if (q == "erzeugt") {
                val rest = 30 - (System.currentTimeMillis() / 1000 % 30).toInt()
                if (rest < 4) Thread.sleep((rest + 1) * 1000L)
                Totp.code(zugang.totp)
            } else (codeAbfrage?.invoke(hinweis) ?: throw IServFehler.ZweiFaktor("Abgebrochen")).filter { it.isDigit() }
            val felder = f.felder.toMutableList()
            if (f.ziffern.size >= 4) f.ziffern.forEachIndexed { i, n -> felder.add(n to (code.getOrNull(i)?.toString() ?: "")) } else felder.add(f.codeFeld to code)
            f.versteckt.forEach { felder.add(it to code) }
            val ziel = Html.absolut(f.action.ifEmpty { s.url }, s.url)
            hostPruefen(ziel)
            val antwort = anfrage(ziel, "POST", mapOf("Referer" to s.url, "Origin" to (Html.erster("^(https?://[^/]+)", s.url) ?: basis)), felder)
            var nach = autoFormulare(antwort)
            if (codeFormular(nach.body) == null) {
                nach = hole("/iserv/")
                if (codeFormular(nach.body) == null) return nach
            }
            hinweis = if (q == "erzeugt") "Der erzeugte Code wurde nicht angenommen – 2FA-Schlüssel prüfen." else "Der Code wurde nicht angenommen."
            s = nach
        }
        throw IServFehler.ZweiFaktor(if (quellen.isEmpty()) "IServ will einen Zwei-Faktor-Code – bitte in der App eingeben oder den 2FA-Schlüssel hinterlegen." else "IServ hat den Zwei-Faktor-Code nicht angenommen.")
    }

    fun anmelden(): Seite {
        if (!zugang.vollstaendig) throw IServFehler.Zugangsdaten("IServ-Zugang fehlt (Adresse, Benutzer, Passwort).")
        var s = hole("/iserv/auth/login")
        ssoLink(s.body, s.url)?.let { (url, formular) ->
            if (url != null) s = hole(url)
            else if (formular != null) s = autoFormulare(Seite(formular.replace(Regex("""<input[^>]*type=["'](?!hidden)[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE), ""), s.url, 200, ""))
            s = autoFormulare(s)
        }
        var passwortGeschickt = false
        for (runde in 0 until 5) {
            s = autoFormulare(s)
            if (codeFormular(s.body) != null) { s = zweiterFaktor(s); break }
            val form = anmeldeFormular(s.body) ?: break
            val felder = ArrayList<Pair<String, String>>()
            var hatPasswort = false
            for (i in Html.eingaben(form)) {
                val n = Html.name(i); val t = Html.typ(i)
                if (n.isEmpty()) continue
                when {
                    t == "hidden" -> felder.add(n to Html.entities(Html.attr(i, "value") ?: ""))
                    t == "password" -> { felder.add(n to zugang.passwort); hatPasswort = true }
                    t in listOf("text", "email") && Html.hat("user|login|name|mail|account", n) -> felder.add(n to zugang.benutzer)
                    t == "checkbox" && Html.hat("remember|merken|angemeldet", n) -> felder.add(n to (Html.attr(i, "value") ?: "on"))
                }
            }
            Html.erster("""(<button[^>]*name=["'][^"']+["'][^>]*>)""", form)?.let { k -> if (Html.typ(k) != "button") felder.add(Html.name(k) to (Html.attr(k, "value") ?: "")) }
            if (hatPasswort && passwortGeschickt) throw IServFehler.Zugangsdaten("IServ hat die Anmeldung nicht angenommen – Passwort prüfen.")
            passwortGeschickt = passwortGeschickt || hatPasswort
            val ziel = Html.absolut(Html.entities(Html.erster("""<form[^>]*action=["']([^"']*)["']""", form) ?: s.url), s.url)
            hostPruefen(ziel)
            s = anfrage(ziel, "POST", mapOf("Referer" to s.url, "Origin" to (Html.erster("^(https?://[^/]+)", s.url) ?: basis)), felder)
            val b = s.body
            if (Html.hat("""Anmeldung fehlgeschlagen|falsches Passwort|Passwort (ist )?(falsch|ungültig)|Benutzername oder Passwort|Invalid (credentials|username or password)|Ungültige(r)? (Benutzername|Anmelde)""", b))
                throw IServFehler.Zugangsdaten("IServ hat die Zugangsdaten abgelehnt.")
            if (Html.hat("gesperrt|zu viele|too many", b) && Html.hat("anmeld|login", b)) throw IServFehler.Zugangsdaten("IServ hat die Anmeldung vorübergehend gesperrt – später erneut versuchen.")
            s = autoFormulare(s)
            if (!istLoginSeite(s) && anmeldeFormular(s.body) == null && codeFormular(s.body) == null) break
        }
        var start = hole("/iserv/")
        if (codeFormular(start.body) != null) start = zweiterFaktor(start)
        if (istLoginSeite(start)) throw IServFehler.Zugangsdaten("Nach der Anmeldung wieder auf der Login-Seite – Anmeldung nicht erkannt.")
        return start
    }

    /** Startseite mit gültiger Sitzung (meldet sich nur an, wenn nötig) */
    fun startseite(): Seite {
        val s = hole("/iserv/")
        if (istLoginSeite(s)) return anmelden()
        if (codeFormular(s.body) != null) return zweiterFaktor(s)
        return s
    }

    fun lade(q: IServQuelle): Seite {
        val kopf = if (q.art == "daten") mapOf("Accept" to "application/json, text/html;q=0.8", "X-Requested-With" to "XMLHttpRequest", "Referer" to q.referer.ifEmpty { "$basis/iserv/" }) else emptyMap()
        var s = hole(q.url, kopf)
        if (istLoginSeite(s) || codeFormular(s.body) != null) {
            if (codeFormular(s.body) != null) zweiterFaktor(s) else anmelden()
            s = hole(q.url, kopf)
        }
        return s
    }

    /** Erkundung: Navigation → Modulseite → Datenadressen (nur lesen) */
    fun <T> erkunde(muster: String, kandidaten: List<String>, lesen: (Seite, List<String>) -> T?): Pair<IServQuelle, T>? {
        val start = startseite()
        val nav = Html.links(start.body).mapNotNull { (href, t) -> val h = Html.entities(href); if (Html.hat(muster, h + " " + Html.text(t))) Html.absolut(h, start.url) else null }
        val gesehen = HashSet<String>()
        for (url in (nav + kandidaten.map { basis + it }).filter { !aendert(it) }) {
            if (url in gesehen || gesehen.size >= 8) continue
            gesehen.add(url)
            val s = runCatching { hole(url) }.getOrNull() ?: continue
            if (s.status >= 400) continue
            lesen(s, emptyList())?.let { return IServQuelle(s.url, "direkt") to it }
            val spalten = Html.spalten(s.body)
            val kand = datenKandidaten(s.body, s.url, muster).sortedBy { if (Regex("export|csv").containsMatchIn(it)) 0 else 1 }
            for (d in kand.take(8)) {
                if (d in gesehen) continue
                gesehen.add(d)
                val q = IServQuelle(d, "daten", s.url, spalten)
                val x = runCatching { lade(q) }.getOrNull() ?: continue
                if (x.status >= 400) continue
                lesen(x, spalten)?.let { return q to it }
            }
        }
        return null
    }
}
