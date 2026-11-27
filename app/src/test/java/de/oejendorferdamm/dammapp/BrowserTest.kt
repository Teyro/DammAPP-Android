package de.oejendorferdamm.dammapp

import de.oejendorferdamm.dammapp.kern.Browser
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserTest {
    /** Cookies überstehen Speichern/Laden mit Domain und Pfad; altes Format wird übernommen */
    @Test fun kekseSpeichern() {
        val k = Browser.Kekse()
        val iserv = "https://s5586.schuldock.de/iserv/".toHttpUrl()
        k.saveFromResponse(iserv, listOfNotNull(Cookie.parse(iserv, "IServSession=abc; path=/iserv; secure; HttpOnly"),
            Cookie.parse(iserv, "sso=x; domain=schuldock.de; path=/")))
        val wieder = Browser.Kekse.aus(k.text() + "\nalt.de\tPHPSESSID\t42")
        fun namen(u: String) = wieder.loadForRequest(u.toHttpUrl()).map { it.name }.sorted()
        assertEquals(listOf("IServSession", "sso"), namen("https://s5586.schuldock.de/iserv/report/"))
        assertEquals(listOf("sso"), namen("https://s5586.schuldock.de/"))
        assertEquals(listOf("sso"), namen("https://idp.schuldock.de/login"))
        assertEquals(listOf("PHPSESSID"), namen("https://alt.de/x"))
    }
}
