# A ready local fallback and an honest progress ring

Agreed with Fabi on 2026-11-01. Replaces the open item "A ready local fallback while a server
recognizes" in `docs/OPEN.md`.

## Why

- **The fallback is slow.** A server that hangs is waited for up to 60 s plus the recording's
  length (`OpenAiCompatibleEngine.requestTimeoutMs`); only then does the local model load
  (Parakeet cold: seconds) and recognize the whole recording.
- **The ring is a guess.** It fills against a learned recognition time only: the cleanup is not
  in it (it waits at 90-98 % while the chat server works), the upload is not measured, a
  fallback shows no ring at all, and the learned rates live in memory - every restart begins
  from the defaults again.

## Part 1: prepare the fallback on the phone

- **Setting**: `FallbackSettings.prepare` (off by default), a switch under "When the server
  fails": "Prepare on the phone while you speak". Shown only when the fallback is a local
  model (Automatic, or Parakeet/Whisper picked); it acts only while that model is downloaded.
  No preparation for Off or for another server as the fallback (a second upload, a second bill).
- **While recording** a dictation (not a command) with a server engine: the local model loads
  at once, and the recording's finished chunks go to a local `ChunkedTranscription`, as a local
  dictation's do (the local engine's chunk length). A dictation a double tap turns into a
  command drops them.
- **After the stop**, the server request and the local tail run side by side
  (`ReadyFallback.race`):
  - the server answers: the local text is discarded and the local work stopped;
  - the server fails in a recoverable way (no connection, timeout, 5xx): the local text is
    taken at once, labelled as today (`Fallback.trustAfter`); if the local recognition failed
    too, the other fallback candidates are tried as today;
  - the server hangs: after twice its learned time, at least 4 s
    (`ReadyFallback.deadlineMs`), the local text is taken once it is there and the request
    cancelled; if the local text is not ready by then, whichever finishes first wins. The label
    says the recording may have reached the server (the upload was under way); a toast says
    "The server took too long - used the text recognized on the phone.";
  - a refused key or a bad request stands, as today: that needs the user.
- **Diagnostics**: one line - prepared with which engine, how many chunks, when the local text
  was ready, who won.

## Part 2: one ring for the whole wait

- `WaitProgress` (`:core`): the steps of one dictation's wait - upload, recognition,
  cleanup - each weighted by its expected time. A step's share fills from a real signal where
  there is one, else by the clock against the expected time:
  - upload (servers): bytes sent of the body (`HttpRequest.onUpload`);
  - recognition: chunked local recognition by the audio still pending, a server by the clock;
  - cleanup: a streamed reply by the characters received against the dictation's length, else
    by the clock against a time learned per cleanup model.
- It never goes back. A switch to the fallback re-plans the rest from where the ring stands. The
  creep that keeps it short of full applies only to what runs by the clock.
- The learned rates are kept across restarts: recognition per engine and model, upload per
  engine, cleanup per model.
- `DictationState.Transcribing` carries the `WaitProgress`; the bubble and the orb read it per
  frame.
- Streaming (`"stream": true`, server-sent events) only for services known to take it
  (Tinfoil, Groq, OpenAI). Amber PC's chat route answers in one piece, so Amber servers and
  other own servers keep the clock.

## Part 3: the page "When the server fails"

- The radio list stays. Under it the switch, with "Uses the phone's processor and battery while
  you dictate." and "If the server takes much longer than usual, the text from the phone is
  used."
- The summary in Settings says so: "Parakeet · prepared".

## Tests (`core/src/test/...`)

- `ReadyFallback`: which engine is prepared (mode, kind, ready, prepare off); the deadline; the
  race - server first, recoverable failure takes the local text, a refused key stands, a hang
  takes the local text after the deadline, a local text later than the deadline still wins over
  a hanging server, a failed local text waits for the server.
- `WaitProgress`: weights, real signals, the clock's creep, never backwards, re-planning.
- `RecognitionEstimate`: rates survive a round trip through their stored form.
- Transport: upload progress reaches the total; a streamed body arrives in pieces and whole.
- `ChatCleanup`: a streamed reply is put together, its finish reason read, progress reported; a
  server that ignores `stream` and answers JSON still works.
