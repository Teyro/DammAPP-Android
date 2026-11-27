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
    data class Nachricht(val id: String, val absender: String, val text: String, val zeit: Long, val verschluesselt: Boolean = false)

    data class Raum(
        val id: String, val titel: String = "", val namen: Map<String, String> = emptyMap(), val helden: List<String> = emptyList(),
        val ungelesen: Int = 0, val verschluesselt: Boolean = false, val nachrichten: List<Nachricht> = emptyList(),
        /** angepinnt = Favorit (Tag „m.favourite“, gleich wie in Element) */ val angepinnt: Boolean = false,
    ) {
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

    fun gelesen(raum: String, event: String) {
        runCatching { anfrage("/_matrix/client/v3/rooms/${enc(raum)}/read_markers", "POST", token, JSONObject().put("m.fully_read", event).put("m.read", event)) }
    }

    companion object {
        const val SERVER = "https://s5586.schuldock.de"
        private val netz = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).build()

        /** „@christian.stake:s5586.schuldock.de“ → „christian.stake“ */
        fun lokal(id: String) = id.removePrefix("@").substringBefore(":")
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

        fun anmelden(benutzer: String, passwort: String): Matrix {
            val j = anfrage("/_matrix/client/v3/login", "POST", koerper = JSONObject().put("type", "m.login.password")
                .put("identifier", JSONObject().put("type", "m.id.user").put("user", benutzer.trim().lowercase()))
                .put("password", passwort).put("initial_device_display_name", "DammAPP Android"))
            return Matrix(j.optString("access_token").ifEmpty { throw AnmeldeFehler() }, j.optString("user_id").ifEmpty { throw AnmeldeFehler() })
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
                val namen = raum.namen.toMutableMap()
                val nachrichten = if (tl.optBoolean("limited")) ArrayList() else raum.nachrichten.toMutableList() // Lücke: nur das Neue
                val ereignisse = (r.optJSONObject("state")?.optJSONArray("events")).liste() + tl.optJSONArray("events").liste()
                for (e in ereignisse) {
                    val inhalt = e.optJSONObject("content") ?: JSONObject()
                    when (val typ = e.optString("type")) {
                        "m.room.name" -> titel = inhalt.optString("name")
                        "m.room.encryption" -> verschl = true
                        "m.room.member" -> inhalt.optString("displayname").takeIf { it.isNotEmpty() && it != "null" }?.let { namen[e.optString("state_key")] = it }
                        "m.room.message", "m.room.encrypted" -> {
                            val eid = e.optString("event_id")
                            if (eid.isEmpty() || nachrichten.any { it.id == eid }) continue
                            val v = typ == "m.room.encrypted"
                            val text = if (v) "🔒 Verschlüsselte Nachricht – nur in Element/IServ lesbar" else inhalt.optString("body")
                            if (text.isEmpty()) continue // gelöscht
                            nachrichten.add(Nachricht(eid, e.optString("sender"), text, e.optLong("origin_server_ts"), v))
                        }
                    }
                }
                var angepinnt = raum.angepinnt
                for (e in r.optJSONObject("account_data")?.optJSONArray("events").liste()) if (e.optString("type") == "m.tag") angepinnt = e.optJSONObject("content")?.optJSONObject("tags")?.has("m.favourite") == true
                val helden = r.optJSONObject("summary")?.optJSONArray("m.heroes")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: raum.helden
                val ungelesen = r.optJSONObject("unread_notifications")?.takeIf { it.has("notification_count") }?.optInt("notification_count") ?: raum.ungelesen
                raum = raum.copy(titel = titel, verschluesselt = verschl, namen = namen, helden = helden, ungelesen = ungelesen, nachrichten = nachrichten.takeLast(200), angepinnt = angepinnt)
                raeume[id] = raum
            }
            return sortiert(raeume.values) to j.optString("next_batch")
        }

        private fun JSONArray?.liste(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
