@file:kotlinx.serialization.UseSerializers(LDTSerializer::class, LDSerializer::class)

package de.oejendorferdamm.dammapp.kern

import java.time.LocalDate
import java.time.LocalDateTime

/** Ein Gericht aus dem Speiseplan der Kantine */
@kotlinx.serialization.Serializable
data class Gericht(val menue: String, val text: String, val bestellt: Boolean = false, val zusatz: String = "") {
    val schluessel get() = schluessel(text)
    companion object { fun schluessel(t: String) = t.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") }

    /** Emoji zum Gericht (Regeln aus dem Kantinen-Skript) */
    val emoji: String get() {
        val regeln = listOf(
            "pizza" to "🍕", "burger" to "🍔", "suppe|eintopf|chili con|soljanka|brühe" to "🍲",
            "schnitzel|frikadelle|bulette|gulasch|braten|kotelett|geschnetzelt|cordon|steak|hackfleisch|rinder|schweine" to "🥩",
            "currywurst|bratwurst|würstchen|wuerstchen|hot ?dog|wurst" to "🌭",
            "fisch|lachs|seelachs|kabeljau|forelle|hering|thunfisch|scholle|backfisch|pangasius|alaska" to "🐟",
            "hähnchen|haehnchen|huhn|geflügel|gefluegel|chicken|pute|nugget|hühner" to "🍗",
            "spaghetti|nudel|pasta|lasagne|penne|tortellini|ravioli|maultasche|spätzle|makkaroni|fusilli|farfalle|gnocchi|tagliatelle|rigatoni|bolognese|carbonara" to "🍝",
            "pfannkuchen|eierkuchen|crêpe|crepe|waffel|kaiserschmarrn|milchreis|grießbrei|griessbrei|germknödel|dampfnudel|pancake|arme ritter" to "🥞",
            "taco|wrap|burrito|tortilla|quesadilla|fajita|döner|doener|gyros|kebab" to "🌯", "rind|schwein|hack|fleisch" to "🥩",
            "curry|reis|risotto|paella|bami|nasi" to "🍛", "auflauf|gratin|quiche|moussaka|überbacken|ueberbacken" to "🥘",
            """omelett|rührei|ruehrei|spiegelei|eier\b|\bei\b""" to "🍳", "kartoffel|pommes|püree|pueree|kroketten|rösti|roesti|bratkartoffel|wedges" to "🥔",
            "salat|bowl|gemüse|gemuese|veggie|vegan|vegetarisch|falafel|tofu|linsen|kichererbsen|bulgur|couscous" to "🥗",
            "pudding|quark|joghurt|dessert|kuchen|obst|kompott|muffin" to "🍮",
        )
        return regeln.firstOrNull { Regex(it.first, RegexOption.IGNORE_CASE).containsMatchIn(text) }?.second ?: "🍽️"
    }
    val merkmale: List<String> get() = buildList {
        if (text.contains("vegan", true)) add("🌱 vegan")
        else if (Regex("""vegetarisch|veggie|\(v\)|\bvegetar""", RegexOption.IGNORE_CASE).containsMatchIn(text)) add("🌱 veggie")
        if (Regex("fisch|lachs|seelachs|kabeljau|forelle|scholle|backfisch", RegexOption.IGNORE_CASE).containsMatchIn(text)) add("🐟 Fisch")
        if (text.contains("schwein", true)) add("🐖 Schwein")
    }
    fun passt(stichwoerter: String, mitZusatz: Boolean = false): Boolean {
        val woerter = stichwoerter.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val t = (text + if (mitZusatz) " $zusatz" else "").lowercase()
        return woerter.any { t.contains(it) }
    }
}

@kotlinx.serialization.Serializable

data class MensaTag(val datum: String, val gerichte: MutableList<Gericht> = mutableListOf(), var frei: String? = null) {
    /** Wie im Kantinen-Widget: bestellt → Lieblingsessen → erstes nicht gemiedenes → erstes */
    fun haupt(favoriten: String = "", meiden: String = "", gut: List<String> = emptyList(), schlecht: List<String> = emptyList()): Gericht? {
        val g = gut.map(Gericht::schluessel).toSet(); val s = schlecht.map(Gericht::schluessel).toSet()
        return gerichte.firstOrNull { it.bestellt } ?: gerichte.firstOrNull { it.schluessel in g } ?: gerichte.firstOrNull { favoriten.isNotEmpty() && it.passt(favoriten) }
            ?: gerichte.firstOrNull { (meiden.isEmpty() || !it.passt(meiden, true)) && it.schluessel !in s } ?: gerichte.firstOrNull()
    }
}

@kotlinx.serialization.Serializable

data class MensaPlan(val tage: List<MensaTag>, val guthaben: Double? = null, val stand: LocalDateTime = Zeit.jetzt()) {
    fun tag(iso: String) = tage.firstOrNull { it.datum == iso }
}

/** Speiseplan aus dem Kantinen-Portal (PCA, PrimeFaces) – Adresse aus den Einstellungen */
object Mensa {
    private const val LOGIN = "/web-cantine/app/login"
    private const val DASH = "/web-cantine/app/portal/dashboard"
    private const val PLAN = "/web-cantine/app/portal/food/menuPlan?menuplanEdit=false"
    class Anmeldung(t: String) : Exception("Anmeldung abgelehnt: $t")

    /** Merkmal der Zugangsdaten, um abgelehnte nicht immer wieder zu probieren (Kontosperre) */
    fun merkmal(b: String, p: String) = (b + "\u0001" + p).toByteArray().fold(5381L) { h, c -> (h shl 5) + h + c }.toString()

    fun ladePlan(adresse: String, benutzer: String, passwort: String): MensaPlan {
        val basis = adresse.trim().trimEnd('/')
        if (!basis.startsWith("https://")) throw java.io.IOException("Kantinen-Adresse fehlt")
        val b = Browser()
        fun hole(url: String, referer: String) = b.anfrage(if (url.startsWith("http")) url else basis + url, kopf = mapOf("Referer" to referer))
        fun post(url: String, felder: List<Pair<String, String>>, referer: String) = b.anfrage(if (url.startsWith("http")) url else basis + url, "POST",
            mapOf("Faces-Request" to "partial/ajax", "X-Requested-With" to "XMLHttpRequest", "Referer" to referer),
            Html.kodiere(felder).toByteArray(), "application/x-www-form-urlencoded; charset=UTF-8").text

        val login = hole(LOGIN, basis).text
        val action = Html.erster("""<form[^>]*id="loginForm"[^>]*action="([^"]+)"""", login)
            ?: throw java.io.IOException(if (login.contains("wartung", true)) "Kantinenportal wird gewartet" else "Anmeldeseite nicht erkannt")
        val vs = Html.erster("""name="javax\.faces\.ViewState"[^>]*value="([^"]*)"""", login) ?: "e1s1"
        val antwort = post(Html.entities(action), listOf(
            "javax.faces.partial.ajax" to "true", "javax.faces.source" to "loginButton", "javax.faces.partial.execute" to "loginForm",
            "javax.faces.partial.render" to "loginForm", "loginButton" to "loginButton", "loginForm" to "loginForm", "loginInfo" to "",
            "username" to benutzer, "password" to passwort, "javax.faces.ViewState" to vs), basis + LOGIN)
        if (!antwort.contains("<redirect", true)) throw Anmeldung(Html.text(antwort).take(80))
        val guthaben = guthabenAus(hole(DASH, basis + LOGIN).text)
        val planSeite = hole(PLAN, basis + DASH)
        if (planSeite.text.contains("id=\"loginForm\"")) throw Anmeldung("Sitzung abgelehnt")
        val tage = tageAus(planSeite.text).toMutableList()
        Html.erster("""name="javax\.faces\.ViewState"[^>]*value="([^"]*)"""", planSeite.text)?.let { vs2 ->
            var ziel2 = Html.erster("""<form[^>]*id="itemsForm"[^>]*action="([^"]+)"""", planSeite.text)?.let(Html::entities) ?: ""
            if (!ziel2.contains("execution=")) ziel2 = planSeite.url
            val xml = post(ziel2, listOf("javax.faces.partial.ajax" to "true", "javax.faces.source" to "nextButton", "javax.faces.partial.execute" to "@form",
                "javax.faces.partial.render" to "@form", "javax.faces.ViewState" to vs2, "nextButton" to "nextButton", "itemsForm" to "itemsForm"), planSeite.url)
            val naechste = tageAus(Html.alle("""<!\[CDATA\[([\s\S]*?)\]\]>""", xml).joinToString("\n"))
            tage += naechste.filter { n -> tage.none { it.datum == n.datum } }
        }
        return MensaPlan(tage.sortedBy { it.datum }, guthaben)
    }

    fun guthabenAus(html: String): Double? {
        val i = html.indexOf("balancePanel")
        val t = Html.text(if (i >= 0) html.substring(i, minOf(html.length, i + 6000)) else html)
        fun greif(m: String): Double? = Regex(m, RegexOption.IGNORE_CASE).find(t)?.value?.let { Regex("""-?[\d.]+,\d{2}""").find(it)?.value }
            ?.replace(".", "")?.replace(",", ".")?.toDoubleOrNull()
        return greif("""Verf(ü|ue)gbar:?\s*-?[\d.]+,\d{2}\s*€""") ?: greif("""GUTHABEN\s*\(GESAMT\)\s*-?[\d.]+,\d{2}\s*€""")
    }

    fun isoDatum(s: String) = Regex("""(\d{2})\.(\d{2})\.(\d{4})""").find(s)?.let { "${it.groupValues[3]}-${it.groupValues[2]}-${it.groupValues[1]}" }

    fun tageAus(html: String): List<MensaTag> {
        val kopf = Html.erster("""(<thead[\s\S]*?</thead>)""", html) ?: ""
        val namen = Html.alle("""(<th[\s\S]*?</th>)""", kopf).map(Html::text).filter { Regex("Men(ü|ue)").containsMatchIn(it) }
            .map { it.replace(Regex("""\s*-?[\d.]+,\d{2}\s*€\s*"""), "").trim() }
        val koerper = html.split("id=\"menuplan_data\"").getOrNull(1) ?: html
        val tage = ArrayList<MensaTag>()
        for (zeile in Html.alle("""(<tr[^>]*data-ri="\d+"[\s\S]*?</tr>)""", koerper)) {
            val zellen = Html.alle("""(<td\b[\s\S]*?</td>)""", zeile)
            val datum = zellen.firstOrNull()?.let { isoDatum(Html.text(it)) } ?: continue
            val tag = MensaTag(datum)
            var i = 0
            for (z in zellen.drop(1)) {
                val klassen = Html.erster("""class="([^"]*)"""", z) ?: ""
                if ("menu-cell" !in klassen.split(" ")) continue
                val nr = i++
                if (z.contains("dayOffOverlay")) { tag.frei = Html.text(Html.erster("""dayOffOverlay[^>]*>([\s\S]*?)</div>""", z) ?: ""); continue }
                val roh = Html.erster("""innerFoodNameContainer[^>]*>([\s\S]*?)</div>""", z) ?: continue
                val gericht = Html.text(roh.replace(Regex("""<span class="additive-info">[\s\S]*?</span>"""), ""))
                val zusatz = Html.text(Html.erster("""<span class="additive-info">([\s\S]*?)</span>""", roh) ?: "")
                if (gericht.isEmpty()) continue
                val bestellt = Html.hat("""\b(selected|ordered|reserved|reservation|booked|ui-state-active|is-ordered)\b""", klassen) ||
                    Regex("""aria-checked="true"|checked="checked"|\bfa-check\b""").containsMatchIn(z)
                tag.gerichte.add(Gericht(namen.getOrNull(nr) ?: "Menü ${nr + 1}", gericht, bestellt, zusatz))
            }
            if (tag.frei != null && tag.gerichte.isNotEmpty()) tag.frei = null
            tage.add(tag)
        }
        return tage
    }
}

/** Termine aus einem öffentlichen Kalender (ICS), z. B. Schulobst und Schultermine */
object SchulKalender {
    @kotlinx.serialization.Serializable
    data class Termin(val datum: String, val titel: String)

    private fun zeilen(ics: String) = ics.replace("\r\n", "\n").replace("\n ", "").replace("\n\t", "").split("\n")
    private fun datum(w: String): LocalDate? = w.take(8).let { z -> if (z.length != 8) null else runCatching { LocalDate.of(z.take(4).toInt(), z.substring(4, 6).toInt(), z.substring(6, 8).toInt()) }.getOrNull() }

    /** Alle Tage (ISO) mit Termin – ganztägig bis vor DTEND, mit Uhrzeit nur der Starttag */
    fun tage(ics: String): Set<String> {
        val erg = HashSet<String>()
        var start: LocalDate? = null; var ende: LocalDate? = null; var ganztags = false
        for (z in zeilen(ics)) {
            val i = z.indexOf(':'); if (i < 0) continue
            val name = z.substring(0, i).split(";").first().uppercase(); val wert = z.substring(i + 1).trim()
            when {
                name == "BEGIN" && wert == "VEVENT" -> { start = null; ende = null; ganztags = false }
                name == "DTSTART" -> { start = datum(wert); ganztags = wert.length == 8 }
                name == "DTEND" -> ende = datum(wert)
                name == "END" && wert == "VEVENT" -> {
                    val s = start ?: continue
                    var e = (if (ganztags) ende else null)?.takeIf { it.isAfter(s) } ?: s.plusDays(1)
                    if (e.isAfter(s.plusDays(60))) e = s.plusDays(60)
                    var d = s
                    while (d.isBefore(e)) { erg.add(d.toString()); d = d.plusDays(1) }
                }
            }
        }
        return erg
    }

    /** Benannte Termine ab heute, die nächsten [tage] Tage – sortiert, ohne Dubletten */
    fun termine(ics: String, ab: LocalDate = Zeit.heute(), tage: Long = 45): List<Termin> {
        val bis = ab.plusDays(tage)
        val erg = ArrayList<Termin>()
        var start: LocalDate? = null; var ende: LocalDate? = null; var titel = ""
        for (z in zeilen(ics)) {
            val i = z.indexOf(':'); if (i < 0) continue
            val name = z.substring(0, i).split(";").first().uppercase(); val wert = z.substring(i + 1).trim()
            when {
                name == "BEGIN" && wert == "VEVENT" -> { start = null; ende = null; titel = "" }
                name == "DTSTART" -> start = datum(wert)
                name == "DTEND" -> ende = datum(wert)
                name == "SUMMARY" -> titel = wert.replace("\\,", ",").replace("\\n", " ").replace("\\", "")
                name == "END" && wert == "VEVENT" -> {
                    val s = start ?: continue
                    if (!s.isBefore(bis) || titel.isEmpty()) continue
                    if (!s.isBefore(ab)) erg.add(Termin(s.toString(), titel))
                    else if (ende != null && ende.isAfter(ab)) erg.add(Termin(ab.toString(), titel))
                }
            }
        }
        return erg.sortedBy { it.datum }.distinct()
    }

    /** ISO-Datum → „Mo, 17.11.“ (heute/morgen ausgeschrieben) */
    fun datumText(iso: String, jetzt: LocalDate = Zeit.heute()): String {
        val d = Zeit.tag(iso) ?: return iso
        val tage = java.time.temporal.ChronoUnit.DAYS.between(jetzt, d)
        if (tage == 0L) return "heute"
        if (tage == 1L) return "morgen"
        return "${Zeit.wochentag(d).take(2)}, ${d.dayOfMonth}.${d.monthValue}."
    }

    fun rohtext(adresse: String): String {
        val a = Netz.anfrage(adresse.trim().replace("webcal://", "https://"))
        if (a.status != 200) throw java.io.IOException("Kalender nicht erreichbar (HTTP ${a.status})")
        return a.text
    }
}
