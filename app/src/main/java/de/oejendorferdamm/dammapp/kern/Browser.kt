package de.oejendorferdamm.dammapp.kern

import java.net.HttpURLConnection
import java.net.URL

/** Wie ein Browser: Cookies je Host, Weiterleitungen selbst (damit die Cookies jeder Station ankommen) */
class Browser(val kekse: Kekse = Kekse()) {
    data class Antwort(val text: String, val url: String, val status: Int, val typ: String)

    /** Einfacher Cookie-Speicher je Host (Domain-Cookies gelten auch für Unterhosts) */
    class Kekse(val daten: MutableMap<String, MutableMap<String, String>> = mutableMapOf()) {
        @Synchronized fun kopf(host: String): String =
            daten.filterKeys { host == it || host.endsWith(".$it") }.values.flatMap { it.entries }.joinToString("; ") { "${it.key}=${it.value}" }
        @Synchronized fun setze(host: String, kopfzeilen: List<String>) {
            for (z in kopfzeilen) {
                val teile = z.split(";").map { it.trim() }
                val nv = teile[0].split("=", limit = 2)
                val n = nv.getOrNull(0) ?: ""; val v = nv.getOrNull(1) ?: ""
                if (n.isEmpty()) continue
                val domain = teile.firstOrNull { it.lowercase().startsWith("domain=") }?.substringAfter("=")?.trimStart('.')?.lowercase() ?: host
                val weg = teile.any { it.lowercase() == "max-age=0" } || teile.any { it.lowercase().startsWith("expires=") && it.contains("1970") }
                if (weg || v.isEmpty() || v == "deleted") daten[domain]?.remove(n) else daten.getOrPut(domain) { mutableMapOf() }[n] = v
            }
        }
        /** Als Text speichern (eine Zeile je Cookie: domain\tname\twert) */
        @Synchronized fun text() = daten.flatMap { (d, m) -> m.map { "$d\t${it.key}\t${it.value}" } }.joinToString("\n")
        companion object {
            fun aus(text: String) = Kekse().apply { text.lines().forEach { z -> val t = z.split("\t"); if (t.size == 3) daten.getOrPut(t[0]) { mutableMapOf() }[t[1]] = t[2] } }
        }
    }

    fun anfrage(url: String, methode: String = "GET", kopf: Map<String, String> = emptyMap(), koerper: ByteArray? = null, inhaltTyp: String? = null): Antwort {
        var adresse = url; var m = methode; var daten = koerper
        repeat(10) {
            val u = URL(adresse)
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.requestMethod = m; c.connectTimeout = 20_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", Netz.UA); c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9")
            kopf.forEach { (k, v) -> c.setRequestProperty(k, v) }
            kekse.kopf(u.host.lowercase()).takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("Cookie", it) }
            daten?.let { d -> c.doOutput = true; inhaltTyp?.let { c.setRequestProperty("Content-Type", it) }; c.outputStream.use { it.write(d) } }
            val status = c.responseCode
            kekse.setze(u.host.lowercase(), c.headerFields.entries.filter { it.key != null && it.key.equals("Set-Cookie", true) }.flatMap { it.value })
            val ort = c.getHeaderField("Location")
            if (status in 300..399 && ort != null) {
                adresse = Html.absolut(ort, adresse)
                if (status != 307 && status != 308) { m = "GET"; daten = null }
                c.disconnect(); return@repeat
            }
            val text = runCatching { (if (status >= 400) c.errorStream else c.inputStream)?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull() ?: ""
            val typ = c.contentType ?: ""
            c.disconnect()
            return Antwort(text, adresse, status, typ)
        }
        throw java.io.IOException("Zu viele Weiterleitungen")
    }
}
