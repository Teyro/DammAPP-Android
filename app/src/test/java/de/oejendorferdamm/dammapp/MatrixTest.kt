package de.oejendorferdamm.dammapp

import de.oejendorferdamm.dammapp.kern.Matrix
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixTest {
    /** /sync auswerten: Namen aus Mitglieds-Ereignissen, Direktchat nach Gegenüber benannt, verschlüsselt erkannt, neueste zuerst, Folge-Sync hängt an */
    @Test fun syncEinarbeiten() {
        val erst = """{"next_batch":"s1","rooms":{"join":{
          "!a:s":{"summary":{"m.heroes":["@kim.muster:s"]},"unread_notifications":{"notification_count":2},
            "state":{"events":[{"type":"m.room.member","state_key":"@kim.muster:s","content":{"displayname":"Kim Muster"}}]},
            "timeline":{"events":[{"type":"m.room.message","event_id":"${'$'}1","sender":"@kim.muster:s","origin_server_ts":1700000000000,"content":{"msgtype":"m.text","body":"Hallo"}}]}},
          "!b:s":{"state":{"events":[{"type":"m.room.name","state_key":"","content":{"name":"Kollegium"}},{"type":"m.room.encryption","state_key":"","content":{}}]},
            "timeline":{"events":[{"type":"m.room.encrypted","event_id":"${'$'}2","sender":"@lea:s","origin_server_ts":1700000100000,"content":{}}]}}}}}"""
        var (r, w) = Matrix.einarbeiten(JSONObject(erst), emptyList())
        assertEquals("s1", w)
        assertEquals(listOf("Kollegium", "Kim Muster"), r.map { it.name })
        assertTrue(r[0].verschluesselt && r[0].nachrichten[0].verschluesselt)
        assertEquals(2, r[1].ungelesen)
        val dann = """{"next_batch":"s2","rooms":{"join":{"!a:s":{"timeline":{"events":[{"type":"m.room.message","event_id":"${'$'}1","sender":"@kim.muster:s","origin_server_ts":1700000000000,"content":{"body":"Hallo"}},{"type":"m.room.message","event_id":"${'$'}3","sender":"@ich:s","origin_server_ts":1700000200000,"content":{"body":"Hi!"}}]}}}}}"""
        r = Matrix.einarbeiten(JSONObject(dann), r).first
        assertEquals(listOf("Hallo", "Hi!"), r[0].nachrichten.map { it.text })
        assertEquals("ich", r[0].name("@ich:s"))
    }
}

class MatrixPinTest {
    /** Favorit-Tag = angepinnt und oben; Suche in Namen und Nachrichten */
    @Test fun kennungen() {
        assertEquals(listOf("vorname.name", "vorname.name@schule.de", "vorname.name@schule.de"),
            Matrix.kennungen(" Vorname.Name@Schule.de ").map { it.optString("user", it.optString("address")) })
        assertEquals(1, Matrix.kennungen("vorname.name").size)
    }

    @Test fun anpinnenUndSuchen() {
        val j = """{"next_batch":"s1","rooms":{"join":{"!alt:s":{"account_data":{"events":[{"type":"m.tag","content":{"tags":{"m.favourite":{"order":0.5}}}}]},"state":{"events":[{"type":"m.room.name","state_key":"","content":{"name":"Medien"}}]},"timeline":{"events":[{"type":"m.room.message","event_id":"a1","sender":"@a:s","origin_server_ts":1000,"content":{"body":"Beamer kaputt"}}]}},"!neu:s":{"state":{"events":[{"type":"m.room.name","state_key":"","content":{"name":"Kollegium"}}]},"timeline":{"events":[{"type":"m.room.message","event_id":"a2","sender":"@b:s","origin_server_ts":9000,"content":{"body":"Konferenz"}}]}}}}}"""
        val r = Matrix.einarbeiten(JSONObject(j), emptyList()).first
        assertEquals(listOf("Medien", "Kollegium"), r.map { it.name })
        assertEquals(listOf("Medien"), Matrix.suchen(r, "beamer").map { it.name })
        assertEquals(listOf("Kollegium"), Matrix.suchen(r, "koll").map { it.name })
    }
}

class ChatMeldenTest {
    @Test fun sofortOderSammeln() {
        val mi = java.time.LocalDateTime.of(2026, 11, 25, 10, 0)
        assertTrue(de.oejendorferdamm.dammapp.kern.ChatMelden.sofort(mi, emptyList()))
        assertTrue(!de.oejendorferdamm.dammapp.kern.ChatMelden.sofort(mi.withHour(20), emptyList()))
        assertTrue(!de.oejendorferdamm.dammapp.kern.ChatMelden.sofort(java.time.LocalDateTime.of(2026, 11, 28, 10, 0), emptyList()))
        assertTrue(de.oejendorferdamm.dammapp.kern.ChatMelden.faellig(mi.withHour(18), "18:00", "2026-11-24"))
        assertTrue(!de.oejendorferdamm.dammapp.kern.ChatMelden.faellig(mi.withHour(18), "18:00", "2026-11-25"))
        assertTrue(!de.oejendorferdamm.dammapp.kern.ChatMelden.faellig(mi.withHour(17), "18:00", null))
    }

    @Test fun neueUndZusammenfassung() {
        val j = JSONObject("""{"next_batch":"s2","rooms":{"join":{"!k:s":{"timeline":{"events":[{"type":"m.room.message","event_id":"${'$'}1","sender":"@a:s","origin_server_ts":1000,"content":{"body":"Hallo"}},{"type":"m.room.message","event_id":"${'$'}2","sender":"@ich:s","origin_server_ts":2000,"content":{"body":"Selbst"}}]}}}}}""")
        val namen = mutableMapOf("!k:s" to "Kollegium")
        val n = de.oejendorferdamm.dammapp.kern.ChatMelden.neue(j, "@ich:s", namen)
        assertEquals(listOf("Hallo"), n.map { it.text })
        assertEquals("Kollegium", n.first().raum)
        assertEquals("4 neue Chat-Nachrichten" to "Kollegium (3), Medien (1)", de.oejendorferdamm.dammapp.kern.ChatMelden.zusammenfassung(mapOf("Medien" to 1, "Kollegium" to 3)))
    }
}
