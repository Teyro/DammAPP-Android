package de.oejendorferdamm.dammapp.kern

/** Nextcloud der Schule über WebDAV: Ordner auflisten und Dateien laden (z. B. die Fußballplatz-Pläne in „DammAPP“) */
class Nextcloud(private val server: String, private val benutzer: String, private val passwort: String) {
    @kotlinx.serialization.Serializable
    data class Datei(val name: String, val etag: String, val groesse: Long, val ordner: Boolean)

    private val auth get() = "Basic " + java.util.Base64.getEncoder().encodeToString("$benutzer:$passwort".toByteArray())

    /** WebDAV-Adresse (Ordnernamen mit Umlauten kodiert) */
    fun url(pfad: String): String {
        var s = server.trim().trimEnd('/'); if (!s.startsWith("http")) s = "https://$s"
        return "$s/remote.php/dav/files/" + (listOf(benutzer) + pfad.split('/').filter { it.isNotEmpty() })
            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }

    private fun pruefen(status: Int, was: String) {
        if (status == 401) throw java.io.IOException("Nextcloud: App-Passwort falsch.")
        if (status == 404) throw java.io.IOException("Nextcloud: $was fehlt.")
        if (status !in 200..299) throw java.io.IOException("Nextcloud nicht erreichbar (HTTP $status).")
    }

    fun liste(pfad: String): List<Datei> {
        val a = Netz.anfrage(url(pfad), "PROPFIND", mapOf("Authorization" to auth, "Depth" to "1", "Content-Type" to "application/xml"),
            """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:getetag/><d:getcontentlength/><d:resourcetype/></d:prop></d:propfind>""".toByteArray())
        pruefen(a.status, "Ordner „$pfad“")
        return lesen(a.text)
    }

    fun laden(pfad: String): ByteArray {
        val a = Netz.anfrage(url(pfad), kopf = mapOf("Authorization" to auth), zeit = 30_000)
        pruefen(a.status, "„$pfad“")
        return a.bytes
    }

    companion object {
        const val PLAN_ORDNER = "DammAPP"

        /** PROPFIND-Antwort → Dateien (der erste Eintrag ist der Ordner selbst) */
        fun lesen(xml: String): List<Datei> = Regex("""<(?:d|D):response>([\s\S]*?)</(?:d|D):response>""").findAll(xml).drop(1).mapNotNull { m ->
            val r = m.groupValues[1]
            val href = Regex("""<(?:d|D):href>([\s\S]*?)</""").find(r)?.groupValues?.get(1) ?: return@mapNotNull null
            val name = java.net.URLDecoder.decode(href.trimEnd('/').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
            val etag = (Regex("""<(?:d|D):getetag>([\s\S]*?)</""").find(r)?.groupValues?.get(1) ?: "").replace("&quot;", "").replace("\"", "")
            val groesse = Regex("""<(?:d|D):getcontentlength>(\d+)</""").find(r)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            Datei(name, etag, groesse, r.contains("collection"))
        }.toList()

        /** Lesbarer Name: „Fußballplatz_Haus1_Pausenbelegung.pdf“ → „Haus 1“, „…_TH_…“ → „Turnhalle“ */
        fun planName(datei: String): String {
            var n = datei.substringBeforeLast('.')
            for (weg in listOf("Fußballplatz", "Fussballplatz", "Pausenbelegung")) n = n.replace(weg, "", ignoreCase = true)
            n = n.replace('_', ' ').trim().replace(Regex("""(?i)^haus\s*(\d)"""), "Haus $1")
            if (n.equals("TH", true)) n = "Turnhalle"
            return n.ifEmpty { datei }
        }
    }
}
