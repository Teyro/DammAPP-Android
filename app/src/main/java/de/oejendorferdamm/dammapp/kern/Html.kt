package de.oejendorferdamm.dammapp.kern

/** HTML-Helfer (Regex wie in der iOS-App; Groß-/Kleinschreibung egal) */
object Html {
    private fun re(m: String) = Regex(m, RegexOption.IGNORE_CASE)

    /** Alle Treffer (Gruppe 1, sonst ganzer Treffer) */
    fun alle(muster: String, s: String): List<String> = re(muster).findAll(s).map { it.groups[1]?.value ?: it.value }.toList()
    fun erster(muster: String, s: String): String? = re(muster).find(s)?.let { it.groups[1]?.value ?: it.value }
    fun hat(muster: String, s: String) = re(muster).containsMatchIn(s)

    fun formulare(h: String) = re("""<form[\s\S]*?</form>""").findAll(h).map { it.value }.toList()
    fun eingaben(f: String) = re("""<input[^>]*>""").findAll(f).map { it.value }.toList()
    fun attr(tag: String, name: String) = erster("""\b$name=["']([^"']*)["']?""", tag)
    fun name(i: String) = attr(i, "name") ?: ""
    fun typ(i: String) = (attr(i, "type") ?: "text").lowercase()

    /** (href, Linktext) */
    fun links(h: String) = re("""<a\b[^>]*href=["']([^"'#][^"']*)["'][^>]*>([\s\S]*?)</a>""").findAll(h).map { it.groupValues[1] to it.groupValues[2] }.toList()

    fun spalten(h: String): List<String> {
        val t = re("""<table[\s\S]*?</table>""").find(h)?.value ?: return emptyList()
        return re("""<th[\s\S]*?</th>""").findAll(t).map { text(it.value) }.toList()
    }

    data class Zeile(val zellen: List<String>, val link: String?)
    fun tabellen(h: String): List<List<Zeile>> = re("""<table[\s\S]*?</table>""").findAll(h).map { m ->
        re("""<tr[\s\S]*?</tr>""").findAll(m.value).map { z ->
            Zeile(re("""<t[hd][\s\S]*?</t[hd]>""").findAll(z.value).map { text(it.value) }.toList(), erster("""<a\b[^>]*href=["']([^"'#][^"']*)["']""", z.value))
        }.toList()
    }.toList()

    fun absolut(href: String, von: String): String {
        if (hat("^https?://", href)) return href
        val wurzel = erster("^(https?://[^/]+)", von) ?: return href
        if (href.startsWith("//")) return "https:$href"
        if (href.startsWith("/")) return wurzel + href
        val pfad = erster("""^https?://[^/]+(/[^?#]*)""", von) ?: "/"
        return wurzel + pfad.replace(Regex("[^/]*$"), "") + href
    }

    fun kodiere(f: List<Pair<String, String>>) = f.joinToString("&") { java.net.URLEncoder.encode(it.first, "UTF-8") + "=" + java.net.URLEncoder.encode(it.second, "UTF-8") }

    fun entities(s: String): String {
        var t = s
        for ((a, b) in listOf("&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&euro;" to "€",
            "&auml;" to "ä", "&ouml;" to "ö", "&uuml;" to "ü", "&Auml;" to "Ä", "&Ouml;" to "Ö", "&Uuml;" to "Ü", "&szlig;" to "ß",
            "&eacute;" to "é", "&ndash;" to "–", "&mdash;" to "—", "&amp;" to "&")) t = t.replace(a, b)
        t = Regex("&#([xX]?)([0-9a-fA-F]+);").replace(t) { m ->
            val w = if (m.groupValues[1].isNotEmpty()) m.groupValues[2].toIntOrNull(16) else m.groupValues[2].toIntOrNull()
            w?.let { String(Character.toChars(it)) } ?: m.value
        }
        return t
    }

    fun text(html: String): String {
        var s = html.replace(re("""<br\s*/?>"""), " ")
        s = s.replace(Regex("""<!\[CDATA\[|\]\]>"""), " ")
        s = s.replace(re("""<script[\s\S]*?</script>|<style[\s\S]*?</style>"""), " ")
        s = s.replace(Regex("<[^>]*>"), " ")
        return entities(s).replace(Regex("\\s+"), " ").trim()
    }

    /** HTML → Text mit Absätzen */
    fun absatzText(h: String): String {
        var s = h.replace(re("""<br\s*/?>|</p>|</div>|</li>|</h\d>|</tr>"""), "\n")
        s = s.replace(Regex("<[^>]*>"), " ")
        s = entities(s).replace(Regex("[ \\t]+"), " ")
        s = s.replace(Regex(" *\\n[ \\n]*"), "\n")
        return s.trim()
    }

    /** CSV mit erkanntem Trenner (; Tab ,) und Anführungszeichen */
    fun csv(body: String): List<List<String>> {
        val zeilen = body.replace("﻿", "").lines().filter { it.isNotBlank() }
        val erste = zeilen.firstOrNull() ?: return emptyList()
        val trenner = listOf(';', '\t', ',').maxByOrNull { c -> erste.count { it == c } } ?: ';'
        return zeilen.map { z ->
            val aus = ArrayList<String>(); val feld = StringBuilder(); var inQ = false; var i = 0
            while (i < z.length) {
                val c = z[i]
                when {
                    c == '"' -> if (inQ && i + 1 < z.length && z[i + 1] == '"') { feld.append('"'); i++ } else inQ = !inQ
                    c == trenner && !inQ -> { aus.add(feld.toString()); feld.clear() }
                    else -> feld.append(c)
                }
                i++
            }
            aus.add(feld.toString())
            aus.map { it.trim() }
        }
    }

    /** „Mustermann, Max“ → „Max Mustermann“ */
    fun nameSchoen(n: String): String {
        val t = n.replace(Regex("\\s+"), " ").trim()
        val m = Regex("^([^,]+),\\s*(.+)$").find(t) ?: return t
        return "${m.groupValues[2].trim()} ${m.groupValues[1].trim()}"
    }
}
