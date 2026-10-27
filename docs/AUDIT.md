# Audit der Android-Schicht und was daraus behoben wurde

`:app` konnte in der Entwicklungsumgebung nie kompiliert werden (`dl.google.com` blockiert,
also kein Android SDK und kein AndroidX). Deshalb wurde die Schicht stattdessen auf drei
Wegen geprüft, die ohne SDK funktionieren — und jeder Weg fand Fehler, die die anderen nicht
sahen.

## Weg 1 — Logik nach `:core` ziehen und testen

Grundregel des Projekts: **alles, was schiefgehen kann, gehört nach `:core`**, weil es dort
ohne Android und ohne Gerät läuft. 111 Tests. Vier echte Fehler kamen dabei heraus:

| Fehler | Wirkung, wenn unentdeckt |
|---|---|
| Cursor wurde gesetzt, bevor geprüft war, ob das Schreiben ankam | Bei Apps, die Erfolg melden und nichts tun, wäre das anschließende Einfügen an beliebiger Stelle gelandet |
| Leeres Textfeld (`text == null`) galt als unlesbar | Diktieren in ein leeres Feld hätte **nie** funktioniert — und jedes Feld fängt leer an |
| Feld mit Platzhalter meldet den Platzhalter als Inhalt | Der diktierte Text wäre an den Platzhalter geklebt worden |
| Watchdog konnte kürzer als die Aufnahme sein | Ein langes Diktat wäre genau dann abgebrochen worden, als es transkribiert werden sollte |

Der aussagekräftigste Test ist `InsertionEngineTest`: er fährt die Einfüge-Kette gegen ein
Feld, das **Erfolg meldet und den alten Inhalt behält** — genau das Verhalten mehrerer
WebViews und Cross-Platform-Textfelder.

## Weg 2 — Unabhängiges Audit gegen die echten Android-Semantiken

Drei Befunde hätten den ersten Start kaputt aussehen lassen:

1. **Fehlendes `<queries>` im Manifest.** Bei targetSdk 35 greift Androids
   Package-Visibility-Filterung: `queryIntentServices` für den Erkennungsdienst liefert
   nichts, `SpeechRecognizer.isRecognitionAvailable()` ist auf **jedem** Gerät `false`. Die
   Engine, die ohne Schlüssel und ohne Download funktionieren soll, hätte überall sofort
   „Dieses Gerät hat keinen System-Erkenner" gemeldet.
2. **`SpeechRecognizer` vom falschen Thread.** Jede seiner Methoden prüft den aufrufenden
   Thread und wirft. `invokeOnCancellation` läuft auf dem abbrechenden Thread — und der
   Watchdog bricht von `Dispatchers.Default` ab. Die Exception wäre in einem
   Cancellation-Handler gelandet, von dort zum Default-Handler und hätte den Prozess
   beendet. Mildere Variante: `close()` lief ebenfalls auf `Default`, wo `runCatching` den
   Wurf verschluckte — der Erkenner wurde nie zerstört und der System-Dienst blieb nach
   jedem abgelaufenen Diktat gebunden.
3. **Die Proxy-Activity beendete sich, bevor die Aufnahme begann.** `start()` setzte den
   Zustand erst in einer Coroutine auf `Dispatchers.Default`, während der Collector der
   Activity auf `Main.immediate` läuft — er sah noch `Idle`, sein Prädikat traf sofort zu,
   und `finish()` lief in `onCreate`. Der Vordergrund-Anker war weg, bevor `AudioRecord`
   öffnete. Das ist der Weg, den **jeder erste Blasen-Tipp** nimmt, wenn der Mikrofon-Dienst
   nicht läuft.

Weitere behobene Befunde: abgebrochene Diktate wurden als „Unerwarteter Fehler" gemeldet
(`CancellationException` wurde geschluckt) · die gewählte Blasen-Position wurde ignoriert ·
der Scope des Bedienungshilfe-Dienstes ließ sich nach einem Rebind nicht wiederbeleben, was
die Blase angehängt aber eingefroren zurückließ · Hintergrund-Activity-Starts werden auf
manchen Hersteller-ROMs verworfen, wodurch ein Blasen-Tipp *nichts* tat — jetzt mit
sichtbarer Meldung nach drei Sekunden · `EncryptedSharedPreferences` machte Keystore-Arbeit
im Startpfad und einen AES-SIV-Entschlüsselungsvorgang pro Engine-Zeile pro Neuzeichnung ·
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` und `VIBRATE` waren überflüssig, erstes davon genau
das Play-Policy-Flag, das der Code eigentlich umgehen wollte.

## Weg 3 — Typprüfung gegen echte Framework-Jars

Robolectric veröffentlicht echte Android-Framework-Jars auf Maven Central, und das ist auch
aus abgeschotteten Umgebungen erreichbar. Mit denen plus kleinen Stubs für die fünf
AndroidX-Berührungspunkte (`tools/typecheck-stubs/`) kompilieren alle 15 Nicht-Compose-Dateien
gegen die echten Framework-Klassen für API 35 — Namen, Signaturen, Overrides, Nullability.

Das fand einen Fehler, den Lesen nicht findet:
`getSystemService(WindowManager::class.java)` liefert ein **nullbares** `WindowManager`, und
`BubbleController` benutzte es an fünf Stellen nicht-null. Ob AGPs `android.jar` das
durchgelassen hätte, ist offen — `null` ist ohnehin ein realer Fall, denn ein Context muss
den Dienst nicht anbieten.

Reproduzierbar mit `tools/typecheck-android.sh`; läuft auch in CI.

## Was ausdrücklich **nicht** geprüft ist

- **Die gesamte Compose-UI.** AndroidX liegt ausschließlich auf Googles Maven, also wurde
  keine Compose-Zeile je gegen die echten Signaturen aufgelöst. Dort wird der erste echte
  Build Fehler zeigen — erfahrungsgemäß Parameternamen und Signatur-Abweichungen.
- **Die AndroidX-Oberfläche** ist nur so genau wie `tools/typecheck-stubs/`, und die sind
  eine Transkription der echten Signaturen aus dem Gedächtnis, keine Quelle der Wahrheit.
- `android-all` enthält auch interne `@hide`-APIs. Code, der dagegen kompiliert, könnte
  theoretisch eine nicht-öffentliche API benutzen.
- **Nichts wurde je auf einem Gerät ausgeführt.** Ob die App startet, ist unbekannt. Die
  Geräte-Checkliste in `docs/PLAN.md` ist genau dafür da.

`./gradlew :app:assembleDebug` bleibt das letzte Wort.
