package de.oejendorferdamm.dammapp

import de.oejendorferdamm.dammapp.kern.Nextcloud
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nur mit NC_PASSWORT in der Umgebung: echte Liste des Ordners „DammAPP“ (PROPFIND über OkHttp) */
class NextcloudEchtTest {
    @Test fun liste() {
        val pw = System.getenv("NC_PASSWORT") ?: ""
        assumeTrue(pw.isNotEmpty())
        val l = Nextcloud("https://nextcloud.teyro.synology.me", "clevertouch", pw).liste(Nextcloud.PLAN_ORDNER)
        println(l)
        assertTrue(l.any { it.name.endsWith(".pdf") })
    }
}
