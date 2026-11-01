# Task: model choice and compressed uploads for Amber PC and Amber Server ("baue amber PC")

When Fabi says **"baue amber PC"**, implement this page - part 1 (model choice), then part 2
(compressed uploads), each tested and committed on its own (repo rules in `CLAUDE.md`:
English, no Claude attribution) - and report in German.

## Why

Amber for Windows ("Amber PC") and the coming headless Amber Server act as Amber's own server:
recognition through `POST /v1/audio/transcriptions`, the cleanup through
`POST /v1/chat/completions`. Today an own server is `ServerProviders.CUSTOM`: no model lists,
the user types model names by hand (`OwnCleanupModel`, and the speech model field). Fabi wants
the same choice as for Groq or OpenAI: a list of the models the PC or server offers, for
recognition and for the cleanup (also the cleanup per app), with name and note.

## The contract

`~/projects/amberPC/docs/phone-api.md` is the source of truth - read it first. In short:
`GET {baseUrl}/models` (the engine's baseUrl already ends in `/<key>/v1`) answers an OpenAI
model list; an Amber server adds an `amber` object at the top level
(`{"service": "amber-pc" | "amber-server", "version": "..."}`) and one in every entry:

```json
{ "id": "acft-large-v3-turbo-q8_0", "object": "model", "owned_by": "amber",
  "amber": { "kind": "speech", "label": "Large v3 Turbo", "note": "on this PC · NVIDIA GPU (CUDA)", "default": true } }
```

`kind` is `speech` or `cleanup`; `default` marks what a request without a known model gets
(one per kind); a kind may be missing (no cleanup offered right now). The `id` goes back
unchanged as `model` in the transcription form and the chat request. Amber PC lists the one
speech and one cleanup model it uses; Amber Server lists every downloaded model and switches
to the one a request names.

## What to build

1. **`:core` - the catalog** (`core/src/main/kotlin/app/amethyst/core/engine/AmberCatalog.kt`):
   - `data class AmberCatalog(val service: String, val version: String, val speech: List<ModelChoice>, val cleanup: List<ModelChoice>)`
   - `fun parse(body: String): AmberCatalog?` - null for a list without the top-level `amber`
     object (any other server); `ModelChoice(id, label, note, recommended = default)`;
     entries of unknown kind skipped; tolerant of extra fields.
   - Fetched with the existing HTTP transport, 5 s timeout, the engine's key handling as for
     transcriptions; never on the main thread.
   - Kept per engine id in the settings (a new optional field, with a schema migration like the
     earlier ones), so the choice works offline and on a cold start.
2. **`:core` - the provider**: where `ServerProviders.forUrl(engine.baseUrl)` gives `CUSTOM`,
   an engine with a stored catalog gets a provider built from it - name "Amber PC" or
   "Amber Server" (from `service`), `speechModels`/`cleanupModels` from the catalog, address
   still editable (`fixedAddress = false`), no prices. Make the UI's "listed" checks
   (`provider.known && ...` in `Pipeline.kt` and `EngineDetailScreen.kt`) accept it - e.g. a
   `val hasModelList` on `ServerProvider` instead of `known`.
3. **When to fetch**: after the engine is saved or its connection test passes, and when the
   Recognition or Cleanup screens open (at most once a minute per engine). A failed fetch keeps
   the stored catalog. A server that is not Amber clears it.
4. **UI**: the speech model picker (`EngineDetailScreen.kt`, `choices = provider.speechModels`)
   and the cleanup picker (`Pipeline.kt`, also per app) then show the lists by themselves. A
   chosen id that is no longer offered shows as "not offered by the PC any more" and still
   works (the server falls back to its default). Non-Amber own servers keep the typed fields.
   All text in English.
5. **Tests** (`core/src/test/...`): parse an Amber list (both kinds, defaults → recommended),
   a plain OpenAI list → null, garbage → null; provider resolution with and without a stored
   catalog; the settings migration; the chosen id is what the transcription and chat
   requests carry.

## Part 2: compressed uploads

### Why

Over the Cloudflare tunnel from mobile data, dictations got slow while the PC itself answered
in 0.4-1.5 s: the time goes into uploading. `OpenAiCompatibleEngine` sends uncompressed WAV
(`audio.toWav()`, 16 kHz mono 16-bit = 32 KB per second of speech) - a 17-second dictation is
about 560 KB, several seconds on a weak uplink, and seconds more indoors. Opus at 24 kbit/s is
about a tenth of that, and whisper hears no difference.

### The contract

`~/projects/amberPC/docs/phone-api.md`, section "Compressed uploads". In short: a server that
takes more than WAV lists it in the top-level `amber` object of `GET /models`:
`"audio": ["wav", "opus"]` (missing = `["wav"]`). `"opus"` = Opus in an Ogg container,
mono, sent as `file` named `dictation.ogg` with `Content-Type: audio/ogg`; recommended 16 kHz
input, 24 kbit/s, 20 ms frames. Unknown values are ignored. The server sniffs the first
bytes, resamples itself, and answers a format it does not take with `400`.

### What to build

1. **`:core`**: `AmberCatalog` (part 1) also parses `audio` into a list of formats (missing →
   `["wav"]`), stored with the catalog. An interface `AudioEncoder` (`fun encode(audio:
   PcmAudio): EncodedAudio?` with `bytes`, `fileName`, `mimeType`; null = cannot) - `:core`
   stays pure JVM, the encoder lives in `:app`.
2. **`OpenAiCompatibleEngine`** gets an optional upload choice: when the engine's stored
   catalog lists `"opus"` and an encoder is there and returns bytes, the `file` part is
   `dictation.ogg` / `audio/ogg` with those bytes; otherwise WAV exactly as today. Other
   servers (Groq, OpenAI, plain own servers) are untouched - no catalog, no change.
   If a compressed request answers `400` (a server that announced more than it takes), send
   the same request once more as WAV and remember the fallback for that engine until the
   catalog is fetched again.
3. **`:app` - `OpusEncoder`** with `MediaCodec` (`audio/opus` encoder) and `MediaMuxer`
   (`MUXER_OUTPUT_OGG`): both exist from Android 10 (API 29); the app's minSdk is 28, so on
   Android 9 the encoder returns null and WAV is sent. Mono, 16 kHz in, 24000 bit/s. Encoding
   a dictation takes milliseconds; never on the main thread. Wire it in `EngineFactory` for
   `OPENAI_COMPATIBLE` engines.
4. **Tests** (`core/src/test/...`): the catalog's `audio` parsed (list, missing, unknown
   values); with a fake encoder the request carries `dictation.ogg` / `audio/ogg` only when
   the catalog lists `opus`; null from the encoder or no catalog → WAV; a `400` on the
   compressed request → one retry as WAV, and WAV from then on. On a device (or emulator with
   API 29+): the `OpusEncoder` output starts with `OggS` and is about a tenth of the WAV.

## Check against the real thing

Amber PC 0.4.2 or newer serves the list (Fabi's phone already has its address, home network
and tunnel). Verify with `curl -s <baseUrl>/models` that the top-level `amber` object is
there, then build, install on the phone, and check both pickers show the PC's models.

Part 2 works against any server once it lists `"opus"` in `amber.audio` - Amber PC and Amber
Server do from the version that decodes it (built in `~/projects/amberPC`; until then the
phone keeps sending WAV, which is the fallback anyway). The PC's log then shows the upload
shrink: `phone API: ..., 22 words from 8400 ms of audio in 810 ms (34 KB uploaded in ...)`
instead of about 260 KB.

## Out of scope

Apart from the upload format of part 2, no change to how recognition and cleanup requests are
made; no server-side features (they belong to `~/projects/amberPC`). Compressing for Groq or
OpenAI (they take Ogg Opus too) is a later step - not now.
