# Amber – offene Punkte

Stand 2026-10-28, nach 0.6.2. Oben das Dringende, unten die Ausbaustufen.

## Gerade dran: Blase und Tastatur

- [ ] **Wireless Debugging einrichten**, damit die Tastatur-Messungen live per `adb logcat -s Amber:V`
      mitgelesen werden können. Kopplung braucht: Kopplungs-IP:Port + 6-stelliger Code und die
      Verbindungs-IP:Port.
- [x] **Zufälliges Runterspringen** (0.6.2): Android meldete das Tastaturfenster kurz nicht
      (Gerät: „steht bei 1242“ nach 66 ms, „geschlossen“ nach 156 ms bei fokussiertem Feld). Erst
      250 ms ohne Meldung gelten jetzt als „geschlossen“.
- [ ] **Blase fährt deutlich nach der Tastatur los, springt statt zu gleiten**
      (Rückmeldung zu 0.6.1). Erst mit den Live-Logs entscheiden, Kandidaten:
  - Der Tastatur Bild für Bild folgen statt erst am Ende hinzuspringen (die Messungen während der
    Animation sind die echte, wandernde Position).
  - Flüssigere Bewegung: das Overlay-Fenster nicht pro Frame per `updateViewLayout` verschieben,
    sondern die Blase innerhalb eines kurz vergrößerten Fensters per `translationY` animieren.

## Bestätigen auf dem Gerät

- [ ] **Verarbeitungszeit der Sprache, Blase vs. App.** Ausgangsproblem: über die Blase ~5× so
      lang. Messung mit 0.6.1, gleicher Satz, ~5,8 s Aufnahme:
      Blase 1,5 s (Modell geladen, `/foreground`, Boost an) – App 0,9 s *inklusive* Modell-Laden
      (`/top-app`). Also besser, aber noch gut doppelt so lang. Die Restlücke ist die CPU-Gruppe.
      Nächster Schritt, falls gewünscht: die unsichtbare Proxy-Activity während der Erkennung
      vorne halten, dann läuft Whisper als Top-App (Preis: Tastatur blinkt kurz) – als Schalter.
- [ ] **Mehrsprachig**: Englisch Gesprochenes bleibt mit Deutsch+Englisch-Auswahl englisch.
- [ ] **Design 0.6**: wirkt es modern-schlicht oder noch „KI-mäßig“?

## Aufräumen

- [x] **Committet** (0.6.2) – vorher lag alles ab der Umbenennung nur im Arbeitsverzeichnis,
      Native-Code und Whisper-Dateien waren nicht einmal von git erfasst.
- [ ] **Release-Signatur** mit eigenem Keystore statt Debug-Schlüssel. Einmal deinstallieren
      nötig, Modelle müssen neu geladen werden.
- [ ] **Screenshots und Icon**: PNG-Icon für Installer, die das Vektor-Icon nicht lesen, und
      2–3 Screenshots.
- [x] **CI**: `tools/typecheck-android.sh` lädt jetzt die Laufzeit-Abhängigkeit des Compilers
      (`kotlinx-coroutines-core-jvm` 1.8.0) mit und läuft wieder.
- [x] **`docs/PLAN.md`** als historisch markiert.
- [ ] Optional: Paketname/App-ID von `app.amethyst` auf Amber umstellen (bedeutet Neuinstallation).

## Ausbaustufen (aus dem README)

- [ ] Lokale Modelle Parakeet TDT und Moonshine über sherpa-onnx
- [ ] ElevenLabs Scribe v2 Realtime (Streaming)
- [ ] Tinfoil mit geprüfter Enclave-Attestierung (`ENCLAVE_VERIFIED`)
- [ ] LLM-Nachbearbeitung · Command Mode auf markiertem Text · Wörterbuch und Snippets ·
      verschlüsselter Verlauf · App-Profile
