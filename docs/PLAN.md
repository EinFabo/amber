> **Historical.** The plan for how the very first APK got from a container onto the phone
> (back then still "Amethyst"). Builds now happen locally, see README → *Building*; open items
> are in [`OPEN.md`](OPEN.md).

# Amethyst — wie die App zum Testen aufs Handy kommt

## Context

Amethyst ist fertig geschrieben: 8 Commits, 84 Dateien, 111 grüne Tests in `:core`, alle
Audit-Befunde eingearbeitet. Was fehlt, ist eine installierbare APK.

Zwei Dinge stehen dem im Weg, und beide sind Umgebungs-Einstellungen, keine Code-Probleme:

| Blocker | Folge |
|---|---|
| `dl.google.com` blockiert | Kein Android SDK, keine AndroidX-/Compose-Artefakte → `:app` ist hier nicht baubar |
| `GH_TOKEN` ungültig | Kein Repo, kein Push, keine GitHub-Actions-Builds |

**Und ein drittes, dringenderes Problem:** der Code existiert ausschließlich in diesem
ephemeren Container. Wird die Session recycelt, sind alle 8 Commits weg. Das wird zuerst
gelöst, unabhängig von allem anderen.

---

## Schritt 1 — Alles in einem Bündel, mit Kopier-Befehlen

Ziel: **ein** Archiv, das Quellcode, Git-Historie und alle Pläne enthält, plus die Befehle,
mit denen du es auspackst und weiterverwendest.

Dazu kommen die Dokumente mit ins Repository, damit sie nicht getrennt vom Code herumliegen:

- `docs/PLAN.md` — dieser Plan (Routen zur APK, Verifikationsschritte, Geräte-Checkliste).
- `docs/AUDIT.md` — die Befunde des unabhängigen `:app`-Audits und was daraus behoben wurde,
  inklusive der drei kritischen Fehler und der Grenzen der Prüfung.

Danach: committen, Archiv packen (Repo samt `.git`, ohne `build/`), per Datei-Anhang
schicken, und den Befehls-Block ausgeben.

### Der vierte Weg zur APK, der von keiner meiner Blockaden abhängt

Das Archiv ist ein vollständiges Git-Repository auf Branch `main`. Du kannst es von **jedem**
Rechner mit `git` in dein eigenes GitHub-Repository pushen — ohne dass Claude GitHub-Zugang
braucht. Der Workflow `.github/workflows/android.yml` liegt bereits drin, läuft beim ersten
Push los und legt die APK als Artifact `amethyst-debug-apk` ab. Du lädst sie im Browser
herunter, auch am Handy.

Das umgeht meine beiden Blockaden vollständig und braucht keinen Android-Studio-Download —
nur ein GitHub-Konto und `git`.

---

## Schritt 2 — Vor dem ersten Build: echte Typprüfung der Android-Dateien

Das ist neu möglich und wichtig genug, um es vor jeden Build zu stellen. Auf Maven Central
(erreichbar) liegen unter `org.robolectric:android-all` **echte Android-Framework-Jars**, für
Android 15 = API 35 also passend zu `compileSdk 35`. Damit lassen sich die `android.*`-Aufrufe
hier wirklich auflösen — nicht nur die Syntax prüfen.

Vorgehen:

1. `org.robolectric:android-all:15-robolectric-13954326` über ein Hilfsprojekt im Scratchpad
   ziehen (wie schon für `kotlin-compiler-embeddable` gemacht).
2. Winzige Stubs im Scratchpad für die zwei Berührungspunkte, die nur auf Googles Maven
   liegen: die generierte `R`-Klasse und `androidx.core.content.ContextCompat`.
3. Den Kotlin-Compiler über die **framework-nahen** Dateien laufen lassen, mit `:core` +
   `android-all` + Stubs im Klassenpfad. Vollständig prüfbar sind damit:
   `accessibility/TextInserter.kt`, `accessibility/AmethystAccessibilityService.kt`,
   `bubble/BubbleView.kt`, `bubble/BubbleController.kt`, `capture/AudioCapture.kt`,
   `capture/MicForegroundService.kt`, `capture/MicProxyActivity.kt`,
   `engine/AndroidOnDeviceEngine.kt`, `dictation/DictationController.kt`,
   `data/StrategyStore.kt`.
4. Jeden echten Fehler beheben.

Genau in diesen Dateien hat das Audit alle drei kritischen Fehler gefunden — es ist die
Schicht, in der Plattform-Semantik steckt. Die Compose-UI bleibt unprüfbar (AndroidX liegt
ausschließlich auf Googles Maven); Compose-Fehler sind aber meist einfache
Signatur-Abweichungen, die der erste echte Build sofort zeigt.

**Grenze, die ich benennen muss:** `android-all` enthält auch interne `@hide`-APIs. Code, der
dagegen kompiliert, könnte theoretisch eine nicht-öffentliche API benutzen. Amethyst benutzt
nur öffentliche APIs, und der Zweck ist, Namens-, Signatur- und Override-Fehler zu finden —
dafür ist es verlässlich.

---

## Schritt 3 — Die APK. Drei Wege, du wählst einen

### Route A — `dl.google.com` freigeben (Empfehlung)
Cloud-Environment-Menü in der Titelleiste dieser Session → *Edit* → *Network access* →
`dl.google.com` unter *Allowed domains* eintragen, „Allow package managers" angehakt lassen.
Greift die Änderung in dieser Session nicht, zieht eine neue Session sie.

Dann: `tools/setup-android-sdk.sh` installiert ein minimales SDK, ich baue, arbeite die
Compile-Fehler in Minuten ab und **schicke dir die fertige APK als Datei in den Chat**. Du
brauchst keinen PC — nur das Handy und „Installation aus unbekannten Quellen" für die Datei.

Schnellster Weg zum Ziel und der mit der kürzesten Rückkopplung bei Fehlern.

### Route B — GitHub verbinden
https://claude.ai/connect-github, dort die Claude-GitHub-App installieren. Dann lege ich das
Repository an, pushe die 8 Commits, und der vorhandene Workflow
`.github/workflows/android.yml` baut die APK als Artifact `amethyst-debug-apk`. Das lädst du
im Browser herunter — auch direkt am Handy.

Dauerhaft reproduzierbare Builds und der Code liegt sicher. Dafür 3–5 Minuten pro
Fehlerrunde statt Sekunden. Falls der Token danach zwar gültig ist, aber kein Repo anlegen
darf: leg auf GitHub ein leeres Repository an, dann pushe ich dorthin.

### Route C — Du baust selbst auf einem PC
Nur sinnvoll, wenn du einen Rechner mit Android Studio hast oder einrichten willst. Archiv
aus Schritt 1 entpacken, Projekt in Android Studio öffnen, *Run*. Android Studio bringt SDK
und AndroidX selbst mit.

Schnellster Dev-Loop auf deinem Gerät mit Debugger — aber die Compile-Fehler des ersten
Builds musst du mir dann vorlesen, statt dass ich sie selbst sehe.

**Beide Freigaben zusammen sind die beste Kombination:** A für schnelle Runden jetzt, B damit
der Code nicht nur in einem Container liegt.

---

## Verifikation

**Jetzt, ohne Freigabe:**
```bash
gradle :core:test                 # 111 Tests, müssen grün bleiben
python3 -I tools/check_refs.py    # Imports, R.*-IDs, Manifest-Klassen
# neu: Typprüfung der framework-nahen Dateien gegen android-all
```

**Sobald Route A offen ist:**
```bash
tools/setup-android-sdk.sh        # SDK holen, :core:test + :app:assembleDebug
ls -lh app/build/outputs/apk/debug/*.apk
```

**Auf deinem Gerät, in dieser Reihenfolge** — sie grenzt Fehler ein, statt sie zu vermischen:

1. APK installieren, App öffnen → Startbild in Schwarz/Violett, kein Crash.
2. Mikrofon erlauben → „Mikrofon-Dienst läuft" wird grün.
3. **Testdiktat** im Testfeld der App. Amethyst ist dabei im Vordergrund, wo Android das
   Mikrofon nie verweigert, und das Ziel ist ein gewöhnliches Eingabefeld. Klappt das, stehen
   Mikrofon, Engine, Normalisierung und Einfügen.
4. Bedienungshilfe aktivieren → Blase erscheint, ziehbar, rastet am Rand ein.
5. Blase in WhatsApp, Chrome-Adressleiste, Gmail. Die **Diagnose-Karte** zeigt pro Diktat
   Engine, Latenz, benutzte Einfüge-Methode und Ziel-App.
6. Eigene Engine (Tinfoil oder eigener Server) eintragen → **„Verbindung testen"** zeigt
   Latenz, ohne dass du sprechen musst.
7. Gerät neu starten, ohne die App zu öffnen, Blase antippen → die Proxy-Activity muss
   einspringen. Hier treiben Hersteller-ROMs am ehesten quer.

Geht Schritt 3 und Schritt 5 nicht, liegt es am Einfügen. Geht Schritt 3 schon nicht, ist es
Mikrofon oder Engine — und die Diagnose-Karte sagt, welches. `adb logcat -s Amethyst:V` zeigt
Zustände, gewählte Einfüge-Strategie und Latenzen, falls du ein Kabel zur Hand hast.

Schritte 1–7 kann nur du ausführen. Ich werde kein Ergebnis behaupten, das ich nicht gesehen
habe.
