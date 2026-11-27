package de.oejendorferdamm.dammapp.kern

import kotlinx.serialization.Serializable
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Eingebaute Zugangsdaten der Schule (Vertretungsplan-Ordner, Mensa-Portal, Obst-Kalender, Untis-Schule, IServ …) –
 * dieselbe Datei wie in der iOS-App (Guten-Morgen-App/schule/vorgaben.enc), verschlüsselt mit dem Schul-Code
 * (Passwort des Schul-Ordners): AES-256-GCM, Schlüssel per PBKDF2-SHA256. Nichts Persönliches darin.
 */
@Serializable
data class SchulVorgaben(
    val vertretungLink: String = "", val freigabe: String = "", val schulOrdner: String = "", val mensaAdresse: String = "",
    val obstKalender: String = "", val schulKalender: String = "", val iservServer: String = "", val untisLink: String = "",
    val untisSchule: String = "", val bundesland: String = "", val haeuser: Map<String, List<String>> = emptyMap(),
    val nextcloudServer: String = "", val nextcloudBenutzer: String = "", val nextcloudPasswort: String = "",
) {
    class FalscherCode : Exception("Der Schul-Code stimmt nicht.")

    @Serializable private data class Huelle(val v: Int, val runden: Int, val salz: String, val daten: String)

    companion object {
        const val QUELLE = "https://raw.githubusercontent.com/Teyro/Guten-Morgen-App/main/schule/vorgaben.enc"

        fun entschluesseln(text: String, code: String): SchulVorgaben {
            val h = JSON.decodeFromString<Huelle>(text)
            require(h.v == 1) { "Unbekanntes Format" }
            val salz = Base64.getDecoder().decode(h.salz); val inhalt = Base64.getDecoder().decode(h.daten)
            val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(code.trim().toCharArray(), salz, h.runden, 256)).encoded
            val klar = try {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), GCMParameterSpec(128, inhalt, 0, 12))
                    doFinal(inhalt, 12, inhalt.size - 12)
                }
            } catch (_: javax.crypto.AEADBadTagException) { throw FalscherCode() }
            return JSON.decodeFromString(String(klar))
        }

        /** Laden und entschlüsseln (blockierend – im IO-Thread aufrufen) */
        fun laden(code: String): SchulVorgaben {
            val c = URL(QUELLE).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000; c.readTimeout = 20_000; c.useCaches = false
            try {
                if (c.responseCode != 200) throw java.io.IOException("Die Vorgaben der Schule sind nicht erreichbar (${c.responseCode}).")
                return entschluesseln(c.inputStream.bufferedReader().readText(), code)
            } finally { c.disconnect() }
        }
    }
}
