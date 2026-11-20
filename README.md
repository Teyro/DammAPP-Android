# DammAPP für Android

Die DammAPP – der Schul-Begleiter fürs Kollegium der Schule am Öjendorfer Damm – für Android: nativ mit Jetpack Compose und
Material 3 (Material You, Hell- und Dunkelmodus). Gegenstück zur iOS-App [Guten-Morgen-App](https://github.com/Teyro/Guten-Morgen-App).

## Funktionen

- **Startseite** mit Kacheln (Reihenfolge und Sichtbarkeit einstellbar): Schultag, Mensa, Fehlen · Störungen · Aufgaben, Schultermine, Wetter, Termine
- **Schultag**: eigene Stunden aus WebUntis bzw. dem eigenen Plan, darüber der Vertretungsplan – Vertretung blau, Hofaufsicht orange,
  ausgefallene Stunden durchgestrichen, aufgeteilte Klassen, **Pausenwetter** mit „Regenpause“
- **Vertretungsplan** (Word-Dateien im Cloud-Ordner der Schule): eigene Einträge, Bemerkungen für alle, wer fehlt, außer Haus
- **Stundenplan**: Schultag, Woche und Klassen-Übersicht (Fächer, Förderkreis, Doppelbesetzung, Kolleg:innen – aus WebUntis)
- **Mensa** (PCA-Kantinenportal) mit Guthaben, bestellten Gerichten, Lieblingsessen ⭐ und „nicht lecker“ 👎 – antippen zum Merken, noch einmal zum Abwählen, Liste „Gemerkte Gerichte“
- **Schulobst** und **Schultermine** aus öffentlichen Kalendern, **Termine** aus dem Gerätekalender
- **IServ**: Abwesenheiten (wer fehlt), Störungsmeldungen nach Zustand getrennt, mit **Verlauf und Antworten** – Anmeldung auch über Schuldock und mit 2FA
- **Edupool-Aufgaben** nach Spalten, mit **Kommentaren und Antworten**
- **Widget „Schultag“** mit gleich großen Kästen für Fehlen, Störungen und Aufgaben
- **Schultag-Benachrichtigung** (was läuft, wie lange noch, was danach kommt) und **Erinnerung 5 Minuten vor der Hofaufsicht**
- **Mitteilungen**: neuer Vertretungsplan (optional dringend), Plan betrifft dich, neue Störungen und Zustandswechsel, neue/verschobene Aufgaben, neue Kommentare
- **Briefing** zum Vorlesen, **Gute Nacht** über Home Assistant (Aktionen, Nachtlampe, offene Fenster)

## Installieren

Die APK liegt bei jedem [Release](https://github.com/Teyro/DammAPP-Android/releases) („DammAPP.apk“). Ab Android 8.
Danach sucht die App unter *Einstellungen → Über DammAPP* selbst nach Updates – nur Releases dieses Repositorys mit derselben Signatur.

## Einrichten

1. *Einstellungen → Schule*: Kürzel, Freigabe-Link des Vertretungsplan-Ordners, WebUntis (Url, Schule, Benutzer, Schlüssel aus „Untis Mobile“)
2. *Einstellungen → IServ und Edupool*: IServ-Zugang (optional 2FA-Schlüssel); Edupool über „Verbinden“ – die Freigabe passiert im Browser, die App sieht kein Passwort
3. *Einstellungen → Mensa, Obst und Termine*: Kantinenportal, Schulobst- und Schulkalender
4. Widget „Schultag“ auf den Startbildschirm ziehen

Passwörter und Schlüssel liegen verschlüsselt im Android-Schlüsselspeicher des Geräts.

## Ausgelassen (Fehlende Funktionen)

Gegenüber der iOS-App fehlen – auf Wunsch oder (noch) aus technischen Gründen:

| Funktion | Warum |
|---|---|
| Wecker, Weckmusik, Nachtmodus | auf Wunsch weggelassen |
| Post & Pakete | auf Wunsch weggelassen |
| Morgenroutine in Home Assistant | hängt an der Weckzeit (Wecker fehlt) |
| Anfahrt (früher wecken bei Stau oder Glätte) | hängt am Wecker; bräuchte außerdem einen Routen-Dienst |
| Personaldaten (Gehaltsabrechnung) | braucht eine eigene Browser-Automatik fürs Portal – noch nicht übertragen |
| QR-Code-Scanner für WebUntis | Url, Schule, Benutzer und Schlüssel werden abgetippt |
| Apple Watch, Apple Health, Fokus „Schule“, Siri/Kurzbefehle | gibt es nur auf dem iPhone |
| Live-Aktivität | stattdessen die dauerhafte Schultag-Benachrichtigung mit Countdown |
| Echte Push-Mitteilungen | ohne eigenen Server nicht möglich – die App fragt selbst nach: etwa stündlich, fest um 6:45 und nach 13:30, an Schultagen bis 8 Uhr alle 15 Minuten |
| Übernahme aus „Mein Tag“ (Scriptable) | gibt es nur auf dem iPhone |
| Farbtöne fürs Widget | stattdessen Material You (Farben aus dem Hintergrundbild) |

## Entwickeln

Kotlin, Jetpack Compose, Material 3, Glance (Widget), WorkManager, kotlinx.serialization. Die Logik in `kern/` ist eine
Übersetzung von `MorgenKern` der iOS-App (dieselben Regeln, mit Tests). Push auf `entwicklung` baut und macht Bildschirmfotos
im Emulator (Beispieldaten); Push auf `main` veröffentlicht ein Release. Versionen sind Daten (Jahr.Monat.Tag).

Lizenz: GPL v3 oder neuer.
