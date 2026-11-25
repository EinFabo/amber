# Google Play Console: what to fill in for Embersay

Written 2026-11-23 for the store review (`docs/plans/2026-11-22-store-review.md`, item 54), from
the code on the branch `store-review`. Policy links are the ones checked during the review. Copy
the texts as they are; change them only together with the app.

## Before the first upload

- **Account age.** A personal developer account created after 2023-11-13 must run a closed test
  with at least 12 testers who stay opted in for 14 days in a row before it can apply for
  production (https://support.google.com/googleplay/android-developer/answer/14151465). Start it
  with the first internal build; it runs while the rest is finished.
- **Play App Signing.** The GitHub and F-Droid builds of `com.embersay` are signed with the
  GitHub key (`c3f69e72...`, see `docs/fdroid/com.embersay.yml`), not with the store key, which
  signs only the builds in Fabi's own store. Two ways, to be chosen:
  - *Upload the GitHub key as the app signing key* ("use your own key"): Play, GitHub and
    F-Droid builds then update one another. The trade-off: the key leaves Fabi's hands
    once, and Google holds a copy of it for good.
  - *Let Google make the app signing key* (the default): the GitHub key stays only Fabi's, with
    an upload key of its own. The trade-off: Play installs and GitHub or F-Droid installs
    cannot update each other; someone who switches source has to uninstall first.

  Build the bundle with `./gradlew :app:bundleRelease` (native libraries stay uncompressed in
  the bundle, symbol tables included).
- **Privacy policy.** A public URL on your domain (the same text as `docs/PRIVACY.md`), with the
  controller's name, postal address and a contact e-mail filled in - the app still shows the
  placeholder line in red until they are in. Set `PrivacyPolicy.PRIVACY_URL` to the same address.
- **Developer verification.** Play apps are registered automatically. If GitHub builds are signed
  with a different key than the one Play uses, register that key for `com.embersay` as well.

## Accessibility API declaration (Permissions declaration form)

Embersay is **not** an accessibility tool (`isAccessibilityTool` is not set).

- **Core functionality:** Dictation into other apps.
- **Does the app collect or share personal and sensitive data through the AccessibilityService
  API?** No. Nothing the service reads leaves the phone; the text of a field is read only at the
  moment of insertion, to fit the new words in at the cursor. The app's name is kept on the phone
  for the statistics and the history.
- **Description (paste):**

  > Embersay is a voice dictation app. Its accessibility service shows a small dictation bubble
  > over other apps while a text field is selected, keeps it clear of the keyboard, and inserts
  > the text the user dictated at the cursor of that field. To do that it reads which app is in
  > front and whether a text field is selected, where the keyboard is, and - only while inserting
  > - the text of the selected field, so the new words fit in. It never reads password fields,
  > performs no taps or gestures, takes no action the user did not start with a tap on the
  > bubble, and nothing it reads leaves the device. If a field does not accept the text directly,
  > it pastes it from the clipboard and clears the clipboard afterwards.

- **Video (YouTube, unlisted), in this order, in one take:**
  1. Fresh install, open Embersay, Quick setup.
  2. The step "Let Embersay insert your text": the disclosure ("What Embersay reads and
     does") is on screen with the "Agree and open settings" button, its rows are not behind a
     tap; scroll slowly through them.
  3. Tap "Not now" - show that nothing was turned on - then open it again from Home's setup card.
  4. Tap "Agree and open settings", turn the service on in Android's settings, come back.
  5. Open a messaging app, tap a text field: the bubble appears. Tap it, speak a sentence, tap
     again: the text appears in the field.
  6. Caption each step on screen (what is shown and why).

## Foreground service declaration

- **Type:** microphone (`FOREGROUND_SERVICE_MICROPHONE`).
- **Use case:** Voice recording started by the user.
- **Description (paste):**

  > The microphone foreground service runs only while the user dictates: it starts when the user
  > taps the dictation bubble and the microphone opens, and stops as soon as the recording ends.
  > While it runs, a notification "Recording…" with a Stop button is shown. The audio is turned
  > into text for the text field the user is writing in.

- **Impact if the task were deferred or interrupted:** "The user is speaking now. A delayed or
  interrupted recording loses their words."
- **Video:** in another app, tap the bubble → pull down the shade to show "Recording…" with
  Stop → speak → tap Stop (or the bubble) → the notification is gone and the text is in the
  field.

Note: the invisible `MicProxyActivity` is still in the app as the last fallback (Android 9/10,
and only on newer Android when a direct start is silenced). Keep it out of the videos. If review
asks about it: it exists so that a dictation the user started still records on phones that
refuse microphone access to a background process; it opens only after the user's tap and only
for that recording.

## Data safety

No account, no ads, no analytics, no advertising ID.

| Data type | Collected | Shared | Optional | Purpose | Notes |
|---|---|---|---|---|---|
| Audio → Voice or sound recordings | Yes | No | Yes | App functionality | Only when the user picks a server speech recognition (Groq, OpenAI, Tinfoil, their own server). On-device recognition sends nothing. Android's speech recognition may send audio to its maker (Google). |
| App activity → Other user-generated content (the dictated text) | Yes | No | Yes | App functionality | Only when the user turns on a cleanup (the text goes to the cleanup service they chose). |

- "Collected" here means "leaves the device", even straight to the service the user picked.
  Transfers the user starts and expects are not "shared".
- **Is all data encrypted in transit?** **No** - the user may use their own server over plain
  http on the local network. (Known services are https only: `network_security_config.xml`.)
- **Is the data processed ephemerally?** Do not claim it - Embersay cannot vouch for the
  retention of the services the user picks.
- **Can users request deletion?** The developer keeps no data. Data on the phone (history,
  statistics, diagnostics) is deleted in the app or with the app.

## App content

- **App access:** "No login needed. Quick setup downloads a speech model (about 80 MB first,
  then about 650 MB) and works without an API key. To test: open any messaging app, tap a text
  field, tap the round bubble, speak, tap it again."
- **Ads:** No.
- **Content rating (IARC):** utility / productivity; no user-to-user interaction, no content
  sharing, no location.
- **Target audience:** 18 and over (keeps the app out of the Families program).
- **News, health, financial features, government:** No.
- **AI-generated content:** out of scope - an optional cleanup that tidies the user's own
  dictation is a productivity feature
  (https://support.google.com/googleplay/android-developer/answer/14094294).
- **Trader status (EU DSA):** non-trader while the app earns nothing.

## Store listing

- Texts: `fastlane/metadata/android/{en-US,de-DE}/` (title, short and full description,
  changelogs) - within Play's limits; brand names stay out of the title and short description.
- **Still to make (Fabi / next session on the Pixel):** phone screenshots without alpha (Play
  allows up to 2.3:1, so the current 1080x2340 fits; they are RGBA and partly outdated), a feature graphic
  1024x500, the 512 px icon as a full square.
