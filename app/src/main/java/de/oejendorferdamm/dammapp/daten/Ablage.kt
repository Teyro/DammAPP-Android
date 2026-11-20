package de.oejendorferdamm.dammapp.daten

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import de.oejendorferdamm.dammapp.kern.JSON
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Speichert Einstellungen, verschlüsselte Geheimnisse und Zwischenstände (für Widget, Hintergrund und ohne Netz) */
class Ablage(context: Context) {
    private val p = context.getSharedPreferences("dammapp", Context.MODE_PRIVATE)

    fun einstellungen(): Einstellungen = p.getString("einstellungen", null)?.let { runCatching { JSON.decodeFromString<Einstellungen>(it) }.getOrNull() } ?: Einstellungen()
    fun speichern(e: Einstellungen) = p.edit().putString("einstellungen", JSON.encodeToString(Einstellungen.serializer(), e)).apply()

    fun geheim(): Geheimnisse = p.getString("geheim", null)?.let { entschluesseln(it) }?.let { runCatching { JSON.decodeFromString<Geheimnisse>(it) }.getOrNull() } ?: Geheimnisse()
    fun speichern(g: Geheimnisse) = p.edit().putString("geheim", verschluesseln(JSON.encodeToString(Geheimnisse.serializer(), g))).apply()

    /** Zwischenstand als JSON (z. B. „vertretung“, „wetter“) */
    inline fun <reified T> lies(name: String): T? = roh(name)?.let { runCatching { JSON.decodeFromString<T>(it) }.getOrNull() }
    inline fun <reified T> schreib(name: String, wert: T?) = rohSetzen(name, wert?.let { JSON.encodeToString(kotlinx.serialization.serializer<T>(), it) })
    fun roh(name: String): String? = p.getString("stand_$name", null)
    fun rohSetzen(name: String, wert: String?) = p.edit().apply { if (wert == null) remove("stand_$name") else putString("stand_$name", wert) }.apply()

    fun text(name: String): String? = p.getString(name, null)
    fun text(name: String, wert: String?) = p.edit().apply { if (wert == null) remove(name) else putString(name, wert) }.apply()
    fun zeit(name: String): Long = p.getLong("zeit_$name", 0)
    fun zeit(name: String, wert: Long) = p.edit().putLong("zeit_$name", wert).apply()

    // MARK: Verschlüsselung (AES-GCM, Schlüssel bleibt im Android-Schlüsselspeicher)

    private fun schluessel(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    private fun verschluesseln(t: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, schluessel()) }
        return Base64.encodeToString(c.iv + c.doFinal(t.toByteArray()), Base64.NO_WRAP)
    }
    private fun entschluesseln(t: String): String? = runCatching {
        val b = Base64.decode(t, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, schluessel(), GCMParameterSpec(128, b.copyOfRange(0, 12))) }
        String(c.doFinal(b.copyOfRange(12, b.size)))
    }.getOrNull()

    companion object { private const val ALIAS = "dammapp_geheim" }
}
