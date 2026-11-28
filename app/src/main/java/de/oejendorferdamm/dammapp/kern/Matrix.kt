package de.oejendorferdamm.dammapp.kern

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Chat über den Matrix-Server der Schule (IServ-Messenger, Element): Anmeldung mit den IServ-Zugangsdaten,
 * Räume mit Nachrichten über /sync, Antworten als Text.
 * shortcut: keine Ende-zu-Ende-Verschlüsselung, keine Einladungen, kein Nachladen älterer Nachrichten – nachrüsten, wenn die Schule E2EE erzwingt.
 */
class Matrix(val token: String, /** eigene Matrix-ID (@benutzer:server) */ val ich: String) {
    /** [anhang] gesetzt: Bild/Video/Audio/Datei – [text] ist dann nur Beschriftung bzw. „📷 Foto“ */
    data class Nachricht(val id: String, val absender: String, val text: String, val zeit: Long, val verschluesselt: Boolean = false, val anhang: Anhang? = null)

    data class Anhang(val art: String, val mxc: String, val name: String, val beschriftung: String = "", val breite: Int = 0, val hoehe: Int = 0, val vorschau: String = "") {
        val text: String get() = beschriftung.ifEmpty { when (art) { "bild" -> "📷 Foto"; "video" -> "🎬 Video"; "audio" -> "🎤 Sprachnachricht"; else -> "📎 $name" } }
    }

    data class Raum(
        val id: String, val titel: String = "", val namen: Map<String, String> = emptyMap(), val helden: List<String> = emptyList(),
        val ungelesen: Int = 0, val verschluesselt: Boolean = false, val nachrichten: List<Nachricht> = emptyList(),
        /** angepinnt = Favorit (Tag „m.favourite“, gleich wie in Element) */ val angepinnt: Boolean = false,
        /** Matrix-ID → Profilbild (mxc://…); [avatar] = Bild des Raums */ val bilder: Map<String, String> = emptyMap(), val avatar: String = "",
    ) {
        /** Bild für die Liste: Raumbild, im Direktchat das der anderen Person */
        val bild: String get() = if (avatar.isEmpty() && helden.size == 1) bilder[helden[0]] ?: "" else avatar
        val name: String get() = titel.ifEmpty { helden.joinToString(", ") { namen[it] ?: lokal(it) }.ifEmpty { "Chat" } }
        val gruppe: Boolean get() = titel.isNotEmpty() || helden.size > 1
        fun name(von: String) = namen[von] ?: lokal(von)
        val letzte: Long get() = nachrichten.lastOrNull()?.zeit ?: 0
    }

    class AnmeldeFehler : Exception("Anmeldung am Chat fehlgeschlagen – IServ-Benutzername und Passwort prüfen.")

    fun sync(seit: String, warten: Int = 0): JSONObject {
        val filter = """{"room":{"timeline":{"limit":30},"state":{"lazy_load_members":true}}}"""
        var q = "filter=" + enc(filter) + "&timeout=$warten"
        if (seit.isNotEmpty()) q += "&since=" + enc(seit)
        return anfrage("/_matrix/client/v3/sync?$q", token = token, frist = warten / 1000L + 30)
    }

    fun senden(raum: String, text: String) {
        anfrage("/_matrix/client/v3/rooms/${enc(raum)}/send/m.room.message/dm${System.currentTimeMillis()}", "PUT", token,
            JSONObject().put("msgtype", "m.text").put("body", text))
    }

    /** Anpinnen = Favorit in Matrix (erscheint auch in Element oben) */
    fun anpinnen(raum: String, an: Boolean) {
        anfrage("/_matrix/client/v3/user/${enc(ich)}/rooms/${enc(raum)}/tags/m.favourite", if (an) "PUT" else "DELETE", token, if (an) JSONObject().put("order", 0.5) else null)
    }

    /** Bild/Datei laden (mit Anmeldung, wie Element); [groesse] > 0 = Vorschaubild */
    fun medien(mxc: String, groesse: Int = 0): ByteArray {
        val p = medienPfad(mxc, groesse) ?: throw Exception("Kein Anhang")
        val r = Request.Builder().url(SERVER + p).header("User-Agent", Netz.UA).header("Authorization", "Bearer $token").build()
        netz.newBuilder().readTimeout(60, TimeUnit.SECONDS).build().newCall(r).execute().use { a ->
            if (!a.isSuccessful) throw Exception("Anhang nicht ladbar (HTTP ${a.code})")
            return a.body?.bytes() ?: ByteArray(0)
        }
    }

    fun gelesen(raum: String, event: String) {
        runCatching { anfrage("/_matrix/client/v3/rooms/${enc(raum)}/read_markers", "POST", token, JSONObject().put("m.fully_read", event).put("m.read", event)) }
    }

    companion object {
        const val SERVER = "https://s5586.schuldock.de"
        private val netz = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).build()

        /** „@vorname.name:s5586.schuldock.de“ → „vorname.name“ */
        fun lokal(id: String) = id.removePrefix("@").substringBefore(":")
        /** mxc://server/id → Pfad zum angemeldeten Laden (v1) */
        fun medienPfad(mxc: String, groesse: Int = 0): String? {
            val t = mxc.removePrefix("mxc://").split("/")
            if (!mxc.startsWith("mxc://") || t.size != 2 || t.any { it.isEmpty() }) return null
            return if (groesse > 0) "/_matrix/client/v1/media/thumbnail/${enc(t[0])}/${enc(t[1])}?width=$groesse&height=$groesse&method=scale"
            else "/_matrix/client/v1/media/download/${enc(t[0])}/${enc(t[1])}"
        }

        /** Anhang aus dem Inhalt (msgtype m.image/m.video/m.audio/m.file) */
        fun anhang(c: JSONObject): Anhang? {
            val art = when (c.optString("msgtype")) { "m.image" -> "bild"; "m.video" -> "video"; "m.audio" -> "audio"; "m.file" -> "datei"; else -> return null }
            // shortcut: verschlüsselte Anhänge (content.file) werden nicht entschlüsselt
            val mxc = c.optString("url").ifEmpty { c.optJSONObject("file")?.optString("url").orEmpty() }.ifEmpty { return null }
            val body = c.optString("body"); val datei = c.optString("filename")
            val info = c.optJSONObject("info") ?: JSONObject()
            return Anhang(art, mxc, datei.ifEmpty { body }, if (datei.isNotEmpty() && body != datei) body else "", info.optInt("w"), info.optInt("h"), info.optString("thumbnail_url"))
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun anfrage(pfad: String, methode: String = "GET", token: String? = null, koerper: JSONObject? = null, frist: Long = 30): JSONObject {
            val r = Request.Builder().url(SERVER + pfad).header("User-Agent", Netz.UA)
            token?.let { r.header("Authorization", "Bearer $it") }
            r.method(methode, koerper?.toString()?.toRequestBody("application/json".toMediaType()))
            netz.newBuilder().readTimeout(frist, TimeUnit.SECONDS).build().newCall(r.build()).execute().use { a ->
                val j = runCatching { JSONObject(a.body?.string() ?: "") }.getOrDefault(JSONObject())
                if (a.code == 401 || a.code == 403) throw AnmeldeFehler()
                if (a.code !in 200..299) throw Exception("Chat-Server antwortet nicht (HTTP ${a.code})" + j.optString("error").let { if (it.isEmpty()) "." else ": $it." })
                return j
            }
        }

        /** IServ-Login ist oft die E-Mail (Schuldock-SSO), der Chat kennt meist nur den Namen davor → nacheinander probieren */
        fun kennungen(benutzer: String): List<JSONObject> {
            val b = benutzer.trim().lowercase(); val at = b.indexOf("@")
            if (at <= 0) return listOf(JSONObject().put("type", "m.id.user").put("user", b))
            return listOf(JSONObject().put("type", "m.id.user").put("user", b.substring(0, at)),
                JSONObject().put("type", "m.id.thirdparty").put("medium", "email").put("address", b),
                JSONObject().put("type", "m.id.user").put("user", b))
        }

        fun anmelden(benutzer: String, passwort: String): Matrix {
            for (k in kennungen(benutzer)) {
                val j = try { anfrage("/_matrix/client/v3/login", "POST", koerper = JSONObject().put("type", "m.login.password")
                    .put("identifier", k).put("password", passwort).put("initial_device_display_name", "DammAPP Android")) } catch (e: AnmeldeFehler) { continue }
                val t = j.optString("access_token"); val u = j.optString("user_id")
                if (t.isNotEmpty() && u.isNotEmpty()) return Matrix(t, u)
            }
            throw AnmeldeFehler()
        }

        /** Angepinnte oben, sonst neueste zuerst */
        fun sortiert(r: Collection<Raum>) = r.sortedWith(compareByDescending<Raum> { it.angepinnt }.thenByDescending { it.letzte })

        /** Suche in Chat-Namen und Nachrichten (Groß-/Kleinschreibung egal) */
        fun suchen(r: List<Raum>, text: String): List<Raum> = text.trim().let { t -> if (t.isEmpty()) r else r.filter { x -> x.name.contains(t, true) || x.nachrichten.any { it.text.contains(t, true) } } }

        /** Antwort von /sync in die bekannten Räume einarbeiten; Räume neueste zuerst + Merkzeichen für den nächsten Abruf */
        fun einarbeiten(j: JSONObject, alt: List<Raum>): Pair<List<Raum>, String> {
            val raeume = LinkedHashMap(alt.associateBy { it.id })
            val rooms = j.optJSONObject("rooms") ?: JSONObject()
            rooms.optJSONObject("leave")?.keys()?.forEach { raeume.remove(it) }
            val join = rooms.optJSONObject("join") ?: JSONObject()
            for (id in join.keys()) {
                val r = join.optJSONObject(id) ?: continue
                var raum = raeume[id] ?: Raum(id)
                val tl = r.optJSONObject("timeline") ?: JSONObject()
                var titel = raum.titel; var verschl = raum.verschluesselt
                val namen = raum.namen.toMutableMap(); val bilder = raum.bilder.toMutableMap(); var avatar = raum.avatar
                val nachrichten = if (tl.optBoolean("limited")) ArrayList() else raum.nachrichten.toMutableList() // Lücke: nur das Neue
                val ereignisse = (r.optJSONObject("state")?.optJSONArray("events")).liste() + tl.optJSONArray("events").liste()
                for (e in ereignisse) {
                    val inhalt = e.optJSONObject("content") ?: JSONObject()
                    when (val typ = e.optString("type")) {
                        "m.room.name" -> titel = inhalt.optString("name")
                        "m.room.encryption" -> verschl = true
                        "m.room.avatar" -> avatar = inhalt.optString("url")
                        "m.room.member" -> {
                            inhalt.optString("displayname").takeIf { it.isNotEmpty() && it != "null" }?.let { namen[e.optString("state_key")] = it }
                            if (inhalt.has("avatar_url")) bilder[e.optString("state_key")] = inhalt.optString("avatar_url").takeIf { it != "null" }.orEmpty()
                        }
                        "m.room.message", "m.room.encrypted" -> {
                            val eid = e.optString("event_id")
                            if (eid.isEmpty() || nachrichten.any { it.id == eid }) continue
                            val v = typ == "m.room.encrypted"
                            val anhang = if (v) null else anhang(inhalt)
                            val text = if (v) "🔒 Verschlüsselte Nachricht – nur in Element/IServ lesbar" else anhang?.text ?: inhalt.optString("body")
                            if (text.isEmpty()) continue // gelöscht
                            nachrichten.add(Nachricht(eid, e.optString("sender"), text, e.optLong("origin_server_ts"), v, anhang))
                        }
                    }
                }
                var angepinnt = raum.angepinnt
                for (e in r.optJSONObject("account_data")?.optJSONArray("events").liste()) if (e.optString("type") == "m.tag") angepinnt = e.optJSONObject("content")?.optJSONObject("tags")?.has("m.favourite") == true
                val helden = r.optJSONObject("summary")?.optJSONArray("m.heroes")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: raum.helden
                val ungelesen = r.optJSONObject("unread_notifications")?.takeIf { it.has("notification_count") }?.optInt("notification_count") ?: raum.ungelesen
                raum = raum.copy(titel = titel, verschluesselt = verschl, namen = namen, helden = helden, ungelesen = ungelesen, nachrichten = nachrichten.takeLast(200), angepinnt = angepinnt, bilder = bilder, avatar = avatar)
                raeume[id] = raum
            }
            return sortiert(raeume.values) to j.optString("next_batch")
        }

        private fun JSONArray?.liste(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
