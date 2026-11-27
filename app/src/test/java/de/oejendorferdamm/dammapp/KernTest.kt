package de.oejendorferdamm.dammapp

import de.oejendorferdamm.dammapp.kern.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class KernTest {
    @Test fun totpWieRfc6238() {
        // RFC 6238, SHA-1, T = 59 s → 94287082 (8 Stellen) → 287082
        assertEquals("287082", Totp.code("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", 59_000))
    }

    @Test fun stundenplanMitVertretungUndAufsicht() {
        val tag = LocalDate.of(2026, 11, 20) // Freitag
        val u = listOf(Unterricht(5, "1", "5a", "Mathe", "R12"), Unterricht(5, "4", "6b", "Deutsch", "R7"))
        val v = VertretungsPlan(datum = "2026-11-20").apply { stunden.add(VertretungsPlan.Stunde("2", "2c")); pausen.add(VertretungsPlan.Pause("2. Pause", "Mensa")) }
        val b = Stundenplan.tag(tag, Schulzeit.standard, u, v, mapOf("Haus 2" to listOf("2c")))
        assertEquals(listOf("1", "2", "1. Pause", "3", "4", "2. Pause"), b.map { it.name })
        assertEquals("Vertretung 2c · Haus 2", b[1].text)
        assertEquals(Block.Art.AUFSICHT, b[5].art)
        assertEquals(Block.Art.FREI, b[3].art)
    }

    @Test fun essenFreitagnachmittagNaechsteWoche() {
        val fr = { h: Int -> LocalDateTime.of(2026, 11, 20, h, 0) } // Freitag
        assertEquals(LocalDate.of(2026, 11, 20), Tagwahl.ziel(fr(13), ab = Tagwahl.ESSEN_STUNDE))
        assertEquals(LocalDate.of(2026, 11, 23), Tagwahl.ziel(fr(14), ab = Tagwahl.ESSEN_STUNDE))
        assertEquals(LocalDate.of(2026, 11, 20), Tagwahl.ziel(fr(14))) // Schule bleibt bis 16 Uhr
    }

    @Test fun pausenwetterRegenpause() {
        val t0 = LocalDateTime.of(2026, 11, 20, 10, 0)
        val w = Wetter(4.0, 61, null, null, t0, listOf(
            WetterSchritt(t0.plusMinutes(15), 63, 5.0, 0.6, 80, 20.0), WetterSchritt(t0.plusMinutes(30), 63, 5.0, 0.5, 80, 20.0)))
        val p = w.pause(t0, t0.plusMinutes(25))!!
        assertTrue(p.drinnen)
        assertEquals("Regenpause", p.text)
    }

    @Test fun vertretungsplanLesen() {
        fun zelle(t: String, w: Int) = """<w:tc><w:tcPr><w:tcW w:w="$w"/></w:tcPr><w:p><w:r><w:t>$t</w:t></w:r></w:p></w:tc>"""
        val xml = "<w:p><w:t>Freitag, 20.11.2026</w:t></w:p><w:tbl>" +
            "<w:tr>" + zelle("Klasse", 1000) + zelle("2a", 1000) + zelle("2c", 1000) + "</w:tr>" +
            "<w:tr>" + zelle("2", 1000) + zelle("", 1000) + zelle("Sta", 1000) + "</w:tr>" +
            "<w:tr>" + zelle("B", 1000) + zelle("+Sta", 2000) + "</w:tr>" +
            "<w:tr>" + zelle("Es fehlen", 1000) + zelle("Mül, Kra", 2000) + "</w:tr></w:tbl>"
        val p = Vertretung.auswerten(xml, "Sta")
        assertEquals("2026-11-20", p.datum)
        assertEquals(listOf(VertretungsPlan.Stunde("2", "2c"), VertretungsPlan.Stunde("6", "2a–2c")), p.stunden) // „B“ = 6. Stunde
        assertEquals(listOf("Mül", "Kra"), p.fehlen)
    }

    @Test fun stoerungVerlaufUndFormular() {
        val html = """<main><div class="comment"><span class="author">Muster, Kim</span><time>19.11.2026 08:15</time><p>Beamer geht nicht.</p></div>
            <form method="post" action="/iserv/defect/42/comment"><input type="hidden" name="_token" value="abc&amp;1"><textarea name="c[content]"></textarea>
            <button type="submit" name="c[submit]">Senden</button></form></main>"""
        val v = Stoerungen.verlaufAus(html, "https://schule.de/iserv/defect/42")
        assertEquals(listOf("Beamer geht nicht."), v.eintraege.map { it.text })
        assertEquals("Kim Muster", v.eintraege[0].autor)
        assertEquals("https://schule.de/iserv/defect/42/comment", v.formular?.aktion)
        assertEquals("abc&1", v.formular?.felder?.first()?.second)
    }

    @Test fun edupoolKommentare() {
        val a = org.json.JSONArray("""[{"id":2,"content":"<p>Danke!</p>","user":{"name":"Kim"},"created_at":"2026-11-20T10:00:00Z"},{"id":1,"content":"Bis Freitag","author_name":"Alex","created_at":"2026-11-19T08:00:00Z"}]""")
        val k = Aufgaben.kommentareAus(a)
        assertEquals(listOf("Bis Freitag", "Danke!"), k.map { it.text })
        assertEquals(listOf("Alex", "Kim"), k.map { it.autor })
    }

    @Test fun abwesenheitenCsv() {
        val l = Abwesenheiten.ausCSV("Vorname;Nachname;Klasse;Zeitraum;Status\nMia;Klein;2b;20.11.2026 (08:00 - 10:00);entschuldigt\n")!!
        assertEquals("Mia Klein", l[0].name)
        assertEquals("8–10 Uhr", l[0].stunden)
        assertEquals("entschuldigt", l[0].art)
    }

    @Test fun einstellungenAlsJson() {
        val e = de.oejendorferdamm.dammapp.daten.Einstellungen(kuerzel = "Sta", haeuser = mapOf("Haus 1" to listOf("1a")))
        val zurueck = JSON.decodeFromString<de.oejendorferdamm.dammapp.daten.Einstellungen>(JSON.encodeToString(de.oejendorferdamm.dammapp.daten.Einstellungen.serializer(), e))
        assertEquals(e, zurueck)
        // ältere Stände ohne neue Felder
        assertEquals("Hamburg", JSON.decodeFromString<de.oejendorferdamm.dammapp.daten.Einstellungen>("{\"kuerzel\":\"X\"}").ortName)
    }

    /** Montag, 23.11.2026 (echter Plan): Frühbetreuung Mensa +Sta, „Begleitung Nelson 1. FSJ Abdul, 2.Schu, 3.-5. Sta“ */
    @Test fun fruehUndBegleitung() {
        val tag = LocalDate.of(2026, 11, 23)
        val u = mutableListOf(Unterricht(1, "3", "4a", "Mathe"), Unterricht(1, "0", "VSK", "Frühförderung"))
        val v = VertretungsPlan(datum = "2026-11-23").apply { stunden.add(VertretungsPlan.Stunde("Frühbetreuung Mensa", "")) }
        v.bemerkungen = Bemerkungen.lesen(listOf("Begleitung Leon 2c: 1. Schu, Mal 2.-4.  FSJ Sophia VSK krank;          FSJ Lola 1b krank;          FSJ Nehir 1c krank;   FSJ Sude 1c Seminar-> Begleitung Roan 1. Mad, 2. Brü, 3. Mey            Begleitung Emir 1a: 3.+4. Bal  FSJ Ellie 2c Seminar-> Begleitung Nelson 1. FSJ Abdul, 2.Schu, 3.-5. Sta  FSJ Elmira krank 1b: SB Meggy 1.-5. Begleitung Emir"), "Sta")
        assertEquals(listOf(3, 4, 5), v.bemerkungen.first().stunden)
        val b = Stundenplan.tag(tag, Schulzeit.standard, u, v, ganz = true)
        assertEquals("0", b.first().name); assertEquals("Frühbetreuung Mensa", b.first().text); assertEquals("VSK Frühförderung", b.first().ersetzt)
        val drei = b.first { it.name == "3" }
        assertEquals("2c → Nelson", drei.text); assertEquals("4a Mathe", drei.ersetzt); assertEquals(Block.Art.VERTRETUNG, drei.art)
        assertEquals("2c → Nelson", b.first { it.name == "5" }.text)
        assertEquals(Block.Art.FREI, b.first { it.name == "2" }.art)
        v.stunden.clear(); u.removeAt(1)
        assertEquals("1", Stundenplan.tag(tag, Schulzeit.standard, u, v, ganz = true).first().name)
    }

    /** Eingebaute Schul-Vorgaben: mit scripts/vorgaben.mjs (iOS-Repo, Node) verschlüsselt, hier entschlüsselt */
    @Test fun schulVorgaben() {
        val d = "{\"v\":1,\"runden\":300000,\"salz\":\"W6QEfqoNzwvhrr8d1DnPlQ==\",\"daten\":\"Nk6uPoabjllI6YhWmXpG4hTDFOTrk622Nzc8pUjEF7+uZWa1r4ZiRKjGiD5YU0HTUvdpg9mUuJvunAP3sTQZtqgrEMmU3BKxeHFvuw9WzaAaj/sk01p6nL2hEzEvHHVdjfU=\"}"
        val v = SchulVorgaben.entschluesseln(d, "test")
        assertEquals("https://x/s/abc", v.vertretungLink); assertEquals("pw", v.freigabe); assertEquals("HH", v.bundesland)
        assertThrows(SchulVorgaben.FalscherCode::class.java) { SchulVorgaben.entschluesseln(d, "falsch") }
    }

    /** Abwesend: morgens IServ ohne Ausgegraute, ab Vertretungsplan „krank + außer Haus“ (echte Zeilen 23.11.) */
    @Test fun abwesendZahl() {
        val tag = LocalDate.of(2026, 11, 23)
        val l = listOf("Anna Beispiel", "Ben Muster", "Cem Probe").map { Abwesenheit(it, "", "2026-11-23", "2026-11-23", "krank") }
        assertEquals("3" to "abwesend", Abwesenheiten.zahl(l, emptyList(), null, tag))
        assertEquals("2", Abwesenheiten.zahl(l, listOf(" ben muster "), null, tag).first)
        val p = VertretungsPlan(datum = "2026-11-23").apply { fehlen = listOf("Kru", "Kut", "Reh", "Kha", "Beu"); ausserListe = listOf("Ayd", "Wa+Hoff (8:30-12:15)") }
        assertEquals("5 + 3" to "krank + außer Haus", Abwesenheiten.zahl(l, emptyList(), p, tag))
        assertEquals("3", Abwesenheiten.zahl(l, emptyList(), p, tag.plusDays(1)).first)
    }

    /** IServ-Seite ohne Kommentar-Klassen: an „Name am Datum Uhrzeit“ in einzelne Blasen trennen (auch zwei Kommentare derselben Person) */
    @Test fun chatKommentareGetrennt() {
        val html = """<main><h1>Verbindung zum Tablet</h1><p>Das Tablet verbindet sich nicht mit dem Board.</p><h2>Kommentare</h2>
            <div class="panel"><div class="panel-heading">Christian Stake am 18.11.2026 11:03</div><div class="panel-body">Schaue ich mir an.</div></div>
            <div class="panel"><div class="panel-heading">Christian Stake am 18.11.2026 11:05</div><div class="panel-body">Kabel getauscht<br>Bitte testen.</div></div>
            <div class="panel"><div class="panel-heading">Linda Beutel am 19.11.2026 08:00</div><div class="panel-body">Danke, geht wieder!</div></div></main>"""
        val e = Stoerungen.verlaufAus(html, "https://s5586.schuldock.de/iserv/report/show/50").eintraege
        assertEquals(4, e.size)
        assertTrue(e[0].text.contains("verbindet sich nicht"))
        assertEquals(StoerungVerlauf.Eintrag("Christian Stake", "18.11.2026 11:03", "Schaue ich mir an."), e[1])
        assertEquals("Kabel getauscht\nBitte testen.", e[2].text)
        assertEquals("Linda Beutel", e[3].autor)
        // Sammelbehälter „comments“ macht nicht alles zu einer Blase
        val s = """<main><div class="comments"><div class="comment"><span class="author">Kim Muster</span><time>19.11.2026 08:15</time><p>Eins</p></div>
            <div class="comment"><span class="author">Kim Muster</span><time>19.11.2026 08:16</time><p>Zwei</p></div></div></main>"""
        assertEquals(listOf("Eins", "Zwei"), Stoerungen.verlaufAus(s, "https://schule.de/x").eintraege.map { it.text })
        assertTrue(Stoerungen.istIch("Christian Stake-Admin", "christian.stake"))
        assertFalse(Stoerungen.istIch("Linda Beutel", "christian.stake"))
        assertTrue(Stoerungen.istIch("Linda Beutel", "christian.stake", "Linda Beutel"))
    }

    /** Nextcloud-Ordner „DammAPP“ (PROPFIND) und Meldezeitpunkt aus dem IServ-Export */
    @Test fun nextcloudUndMeldezeit() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/remote.php/dav/files/clevertouch/DammAPP/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
            <d:response><d:href>/remote.php/dav/files/clevertouch/DammAPP/Fu%c3%9fballplatz_TH_Pausenbelegung.pdf</d:href><d:propstat><d:prop><d:getetag>&quot;def&quot;</d:getetag><d:getcontentlength>175404</d:getcontentlength><d:resourcetype/></d:prop></d:propstat></d:response></d:multistatus>"""
        val l = Nextcloud.lesen(xml)
        assertEquals(listOf(Nextcloud.Datei("Fußballplatz_TH_Pausenbelegung.pdf", "def", 175404, false)), l)
        assertEquals("Turnhalle", Nextcloud.planName(l[0].name))
        assertEquals("Haus 1", Nextcloud.planName("Fußballplatz_Haus1_Pausenbelegung.pdf"))
        assertEquals("https://nextcloud.teyro.synology.me/remote.php/dav/files/clevertouch/DammAPP/Pl%C3%A4ne", Nextcloud("https://nextcloud.teyro.synology.me/", "clevertouch", "x").url("DammAPP/Pläne"))
        val a = Abwesenheiten.ausCSV("Name;Klasse;Von;Bis;Art;Erstellt\nMuster, Kim;;23.11.2026;23.11.2026;krank;22.11.2026 18:40:00\n")!!
        assertEquals("2026-11-22T18:40", a[0].gemeldet)
        assertEquals("gemeldet So, 22.11. um 18:40 Uhr", Abwesenheiten.gemeldetText(a[0], LocalDate.of(2026, 11, 23)))
        assertEquals("gemeldet um 18:40 Uhr", Abwesenheiten.gemeldetText(a[0], LocalDate.of(2026, 11, 22)))
        assertNull(Abwesenheiten.ausCSV("Name;Von\nKim;23.11.2026\n")!![0].gemeldet)
    }

    /** Textbausteine („Kein weiterer Kommentar“, „Kommentar hinzufügen“, Angaben-Spalten) und Wiederholungen fliegen raus */
    @Test fun chatBereinigen() {
        fun e(a: String, t: String) = StoerungVerlauf.Eintrag(a, "", t)
        val l = listOf(e("", "Das Board bleibt schwarz.\nStatus: Neu\nRaum: 104\nDas Board bleibt schwarz.\nKein weiterer Kommentar."),
            e("Kim Muster", "Kommentar hinzufügen"), e("Kim Muster", "Schaue ich mir morgen an."), e("Kim Muster", "Schaue ich mir  morgen an."),
            e("Kim Muster", "Danke"), e("Linda Beutel", "Danke"))
        assertEquals(listOf("Das Board bleibt schwarz.", "Schaue ich mir morgen an.", "Danke", "Danke"), Stoerungen.bereinigen(l).map { it.text })
    }

    @Test fun chatKopfDerDetailseite() {
        val t = "Details zu Kalendereintrag\nVertretungsplan\nZurück zur Liste\n\nKalendereintrag Vertretungsplan\nKim Muster | 01.10.2026 07:51\n| Medienproblem |\neingereicht\nHallo :)\n\nIch sehe den Kalender nicht.\n\nEs wurden noch keine Dateien angehängt!"
        assertEquals(listOf("Hallo :)\n\nIch sehe den Kalender nicht."), Stoerungen.bereinigen(listOf(StoerungVerlauf.Eintrag("Kim Muster", "", t))).map { it.text })
    }

    @Test fun personenFarben() {
        val f = mapOf("Christian S." to "#76a5af", "Christian N." to "#7aab5c", "Vivi" to "#8c7fc2")
        assertEquals("#76a5af", Aufgaben.farbeFuer("Christian Stake Admin", f))
        assertEquals("#7aab5c", Aufgaben.farbeFuer("Christian Normann", f))
        assertEquals("#8c7fc2", Aufgaben.farbeFuer("Viviane Haist Admin", f))
        assertEquals(null, Aufgaben.farbeFuer("Christian Xaver", f))
        val m = Aufgaben.MEDIEN_VORGABE
        assertEquals("#80a4ae", Aufgaben.farbeFuer("Christian Stake", m))
        assertEquals("#79a75a", Aufgaben.farbeFuer("Christian Normann Admin", m))
        assertEquals("#8b7dbe", Aufgaben.farbeFuer("Viviane Haist", m))
    }

    @Test fun avatare() {
        val html = """<header><img class="avatar" src="/iserv/core/avatar/user/kim.muster" alt="Kim Muster"></header><div class="comment"><span class="author">Lea Beutel</span><p>Geht wieder.</p></div><img src="/logo.png" alt="Schule am Damm">"""
        val v = Stoerungen.verlaufAus(html, "https://schule.de/iserv/report/7")
        assertEquals("https://schule.de/iserv/core/avatar/user/kim.muster", v.avatar("Kim Muster Admin"))
        assertEquals(null, v.avatar("Schule am Damm"))
    }

    /** Ohne Namen am Bild: Profilbild (auch als Hintergrundbild) im Beitrag gehört zur Person des Beitrags */
    @Test fun avatarImBeitrag() {
        val html = """<div class='comment'><span class='avatar' style='background-image: url(&quot;/iserv/core/avatar/user/lea.beutel&quot;)'></span><span class='author'>Lea Beutel</span><p>Geht wieder.</p></div>"""
        assertEquals("https://schule.de/iserv/core/avatar/user/lea.beutel", Stoerungen.verlaufAus(html, "https://schule.de/iserv/report/7").avatar("Lea Beutel"))
    }

    /** Eigene Statistik: späterer Stand ersetzt, leere Vorlage zählt nicht, Reserve-Aufsicht nicht */
    @Test fun statistik() {
        val p = VertretungsPlan(datum = "2026-11-23", belegt = 5).apply {
            stunden.add(VertretungsPlan.Stunde("2", "2c")); pausen.add(VertretungsPlan.Pause("1. Pause", "Hof")); pausen.add(VertretungsPlan.Pause("2. Pause", "Mensa", true))
            bemerkungen = listOf(Bemerkung(listOf(3, 4, 5), "Begleitung Nelson", "2c"))
        }
        var s = VertretungsStatistik().merken(p).merken(p.copy(stunden = mutableListOf(VertretungsPlan.Stunde("1", "4a"))))
        s = s.merken(VertretungsPlan(datum = "2026-11-24", belegt = 3).apply { stunden.add(VertretungsPlan.Stunde("5", "4a")) }).merken(VertretungsPlan(datum = "2026-11-25"))
        val z = s.zahlen("2026-11-01")
        assertEquals(listOf(2, 2, 2, 1, 3), listOf(z.tage, z.vertretungen, z.rotTage, z.aufsichten, z.begleitungen))
        assertEquals("4a", z.klasse)
        assertEquals("2025-08-01", VertretungsStatistik.schuljahrBeginn(LocalDate.of(2026, 3, 2)))
    }
}
