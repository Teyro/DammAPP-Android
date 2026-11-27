package de.oejendorferdamm.dammapp.kern

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Wie ein Browser (bzw. URLSession unter iOS): OkHttp folgt allen Weiterleitungen selbst (302/303 → GET, 307/308 behalten POST)
 * und schickt bei jeder Station die passenden Cookies – mit Domain, Pfad, Ablauf und „secure“ wie im Browser.
 */
class Browser(val kekse: Kekse = Kekse(), private val ua: String = Netz.UA) {
    data class Antwort(val text: String, val url: String, val status: Int, val typ: String)

    /** Cookie-Speicher wie im Browser; als Text dauerhaft speicherbar (Sitzung bleibt über Neustarts) */
    class Kekse : CookieJar {
        private val liste = ArrayList<Pair<String, Cookie>>() // (Host der Antwort, Cookie)

        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val jetzt = System.currentTimeMillis()
            for (c in cookies) {
                liste.removeAll { (_, x) -> x.name == c.name && x.domain == c.domain && x.path == c.path }
                if (c.expiresAt > jetzt) liste.add(url.host to c)
            }
        }

        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val jetzt = System.currentTimeMillis()
            liste.removeAll { it.second.expiresAt <= jetzt }
            return liste.map { it.second }.filter { it.matches(url) }
        }

        /** Eine Zeile je Cookie: Host \t Set-Cookie-Text */
        @Synchronized fun text() = liste.joinToString("\n") { (h, c) -> "$h\t$c" }

        companion object {
            fun aus(text: String) = Kekse().apply {
                for (z in text.lines()) {
                    val t = z.split("\t")
                    val c = when (t.size) {
                        2 -> "https://${t[0]}/".toHttpUrlOrNull()?.let { u -> Cookie.parse(u, t[1]) }
                        // altes Format (Domain, Name, Wert)
                        3 -> runCatching { Cookie.Builder().domain(t[0]).path("/").name(t[1]).value(t[2]).build() }.getOrNull()
                        else -> null
                    } ?: continue
                    liste.add(t[0] to c)
                }
            }
        }
    }

    private val netz = basis.newBuilder().cookieJar(kekse).build()

    fun anfrage(url: String, methode: String = "GET", kopf: Map<String, String> = emptyMap(), koerper: ByteArray? = null, inhaltTyp: String? = null): Antwort {
        val r = Request.Builder().url(url).header("User-Agent", ua).header("Accept-Language", "de-DE,de;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        kopf.forEach { (k, v) -> r.header(k, v) }
        r.method(methode, koerper?.toRequestBody(inhaltTyp?.toMediaTypeOrNull()) ?: if (methode == "POST") ByteArray(0).toRequestBody() else null)
        netz.newCall(r.build()).execute().use { a ->
            return Antwort(a.body?.string() ?: "", a.request.url.toString(), a.code, a.header("Content-Type") ?: "")
        }
    }

    /** Bild (Profilbild) mit den Cookies der Sitzung; null bei Fehler oder wenn kein Bild zurückkommt */
    fun bild(url: String): ByteArray? = runCatching {
        netz.newCall(Request.Builder().url(url).header("User-Agent", ua).header("Accept", "image/*").build()).execute().use { a ->
            if (a.isSuccessful && (a.header("Content-Type") ?: "").startsWith("image")) a.body?.bytes() else null
        }
    }.getOrNull()

    companion object {
        private val basis = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true).build()
    }
}
