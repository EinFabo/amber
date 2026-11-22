# Privacy policy

Embersay (called Amber until November 2026) is a dictation app for Android: you speak, and the
text is typed into the app you are in.
This page says what Embersay does with your voice, your text and everything else it touches. It
applies to the app as published at https://github.com/EinFabo/amber, version 0.33.0 and later.

Embersay has no account, no advertising, no analytics and no crash reporting. Its developer runs
no server that Embersay talks to and receives nothing from the app.

Responsible for Embersay: EinFabo (https://github.com/EinFabo). Questions about this policy:
https://github.com/EinFabo/amber/issues

## What stays on the phone

- **Settings**, including the addresses of the servers you add.
- **API keys** you type in, encrypted with a key in Android's keystore. A key is only ever sent
  to the address it was typed for.
- **Statistics**: for each dictation the time, the app you dictated in, the number of words and
  how long it took - not what you said. You can clear them under Settings → Statistics.
- **History**: your recent dictations - their text, the app and the time - kept on this phone
  for 24 hours unless you change it (off, 24 hours or 7 days, under Settings → History) or
  switch on the security mode. An install from before the history starts with it off and is
  offered it once on Home. The text and the app are encrypted; the time is not, so what is past
  its time can be deleted while the phone is locked. The keys - one per day - are in Android's
  keystore and work only while the phone is unlocked, which needs a screen lock (PIN, pattern or
  password): a locked phone can neither read the history nor add to it. On Android 9 to 11 that
  check is made in software only, a weaker protection. A day's key is deleted once the whole day
  is past keeping, and "Delete all" deletes the entries and every key: the storage chip may hold
  the deleted bytes for a while, but without the key they cannot be read. Copy marks the text as
  sensitive, but some keyboards (Gboard, Samsung) keep their own clipboard history, where copied
  text may stay.
- **Diagnostics**: the last dictations with their timings and, on the phone only, their text.
  A report you copy to share holds each dictation's shape (characters, words, language), not
  its words.
- **Speech models** you download.

With the **security mode** on (Settings → Security mode), Embersay keeps none of a dictation's
words once it is in the field: no recording to try again, no last dictation, no history, no text
in Diagnostics (only how many characters and words it had, and its language, and not the app),
no record of how text went into each app; it never puts text on the clipboard, and its own
screens are kept out of screenshots and Recents. The statistics still keep each dictation's
time, word count and length, without the app - switch them off under Settings → Statistics to
keep none of it. Switching the mode on deletes what is kept at that moment; each start with the
mode on removes app names an interrupted switch may have left. Until Embersay has read its settings
after a start, it acts as if the mode were on. It does not hide that Embersay is installed, it
cannot reach text already in other apps or in a keyboard's own clipboard history, and a speech
recognition or cleanup on a server still receives what the table below says.

Without the mode, a screen of Embersay's that shows dictated words - the history, Diagnostics with
a dictation's text shown, Home's last dictation, a test field holding text - is kept out of
screenshots and the Recents picture while it does.

None of this is in Android's cloud backup or in a transfer to a new phone; Embersay excludes all
of its files from both. Removing the app removes all of it.

## What the accessibility service reads

Embersay's bubble is an Android accessibility service. Through it Embersay reads:

- which app is in front and whether a text field has focus, to show or hide the bubble;
- where the keyboard is, to keep the bubble off its keys;
- the text already in the field - only at the moment a dictation is inserted, so that the new
  words fit at the cursor.

None of this is sent anywhere. Which app a dictation went into is kept for the statistics
and the history above; the text of the field is not kept. Password fields get no bubble and are not read. The
service types your dictation into the field; it does nothing else in other apps.

## What leaves the phone, and to whom

Only what the speech recognition and the cleanup **you chose** need:

| You chose | What is sent | To whom |
|---|---|---|
| Speech recognition on the phone (Parakeet, Whisper) | nothing | - |
| Android's speech recognition | your recording goes to the speech service of your phone (Google's, on most phones); Embersay asks it to recognize on the phone, but on a phone without on-device recognition that service may send it to its maker | the maker of that service |
| Tinfoil | your recording, after Embersay has verified the server's hardware enclave | Tinfoil |
| Groq, OpenAI | your recording | that company |
| Your own server (for example Embersay PC) | your recording | the server at the address you entered |
| A cleanup (Tinfoil, Claude, Groq, OpenAI, your own server) | the recognized text of the dictation and Embersay's instructions for tidying it - not the recording, and not the text already in the field | that service |

These services receive your data under their own terms and privacy policies, and they see your
phone's network address as with any connection. Embersay sends them nothing that identifies you
beyond the API key you gave it. Every dictation is labelled in Embersay with where your voice and
your text went.

Without any of this: with speech recognition on the phone and the cleanup off - how Embersay
starts - nothing you say or write leaves the phone.

## Other connections

- **Speech models** are downloaded, when you choose one, from huggingface.co and
  keyboard.futo.org.
- **Update check**: a copy of Embersay installed from a file from GitHub asks api.github.com once
  a day whether a newer release is out. The request carries nothing about you or your phone
  beyond what any web request does. A copy installed by an app store never asks. You can
  switch the check off under Settings → Updates.
- **Model lists**: Embersay asks the servers you added which models they offer.

## Permissions

- **Microphone**: to record a dictation. Embersay records only between your tap to start and the
  end of that dictation.
- **Accessibility service**: described above.
- **Notifications**: for the notice shown while Embersay records (with a Stop button), and
  for an available update.
- **Internet**: for the connections listed above.

## Children

Embersay is not directed at children and knowingly collects nothing from anyone.

## Changes

Changes to this policy are made in this file; its history is public at
https://github.com/EinFabo/amber/commits/main/docs/PRIVACY.md.
