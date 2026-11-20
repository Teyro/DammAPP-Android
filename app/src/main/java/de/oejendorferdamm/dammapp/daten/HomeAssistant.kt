package de.oejendorferdamm.dammapp.daten

import de.oejendorferdamm.dammapp.kern.Netz
import org.json.JSONArray
import org.json.JSONObject

/** Home Assistant: Gute-Nacht-Aktionen, Nachtlampe, offene Fenster */
class HomeAssistant(adresse: String, token: String) {
    val basis = adresse.trim().trimEnd('/')
    val token = token.trim()
    val eingerichtet get() = basis.startsWith("http") && token.isNotEmpty()

    private fun anfrage(pfad: String, koerper: JSONObject? = null): Any? =
        Netz.json(basis + pfad, if (koerper == null) "GET" else "POST", mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"), koerper?.toString()?.toByteArray())

    fun ausfuehren(a: HAAktion): Boolean {
        val t = a.dienst.split("."); if (t.size != 2) return false
        return runCatching { anfrage("/api/services/${t[0]}/${t[1]}", if (a.ziel.isBlank()) JSONObject() else JSONObject().put("entity_id", a.ziel.trim())); true }.getOrDefault(false)
    }

    fun nachtlampe(entity: String, helligkeit: Int): Boolean = entity.isNotBlank() &&
        runCatching { anfrage("/api/services/light/turn_on", JSONObject().put("entity_id", entity.trim()).put("brightness_pct", helligkeit.coerceIn(1, 100))); true }.getOrDefault(false)

    fun offeneFenster(): List<String> {
        val l = runCatching { anfrage("/api/states") as? JSONArray }.getOrNull() ?: return emptyList()
        return (0 until l.length()).mapNotNull { l.optJSONObject(it) }.mapNotNull { s ->
            val id = s.optString("entity_id"); val a = s.optJSONObject("attributes") ?: return@mapNotNull null
            if (id.startsWith("binary_sensor.") && s.optString("state") == "on" && a.optString("device_class") in listOf("window", "door", "opening", "garage_door")) a.optString("friendly_name", id) else null
        }
    }
}
