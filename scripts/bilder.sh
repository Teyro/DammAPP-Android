#!/bin/bash
# Bildschirmfotos mit Beispieldaten (nur Debug-Version): Start hell/dunkel, Bereiche, Einstellungen
set -x
mkdir -p bilder
P=de.oejendorferdamm.dammapp
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant $P android.permission.POST_NOTIFICATIONS || true
foto() { sleep "${2:-5}"; adb exec-out screencap -p > "bilder/$1.png"; }
start() { adb shell am start -S -W -n $P/.MainActivity --ez vorschau true ${1:+-d "$1"} >/dev/null; }
for modus in no yes; do
  adb shell cmd uimode night $modus
  start; foto "start_$([ $modus = yes ] && echo dunkel || echo hell)" 8
done
adb shell cmd uimode night no
adb shell input swipe 540 1800 540 600 400; foto start_gescrollt 2
for b in vertretung stundenplan mensa abwesend stoerungen aufgaben einstellungen; do start "dammapp://$b"; foto "$b" 6; done
adb logcat -d > logcat.txt
