# Security review of Amber PC and Amber Server (prompt for the amberPC repo)

Paste this into a Claude Code session in `~/projects/amberPC`. Amber for Android does not edit
that repo; its own side of the connection is reviewed separately (phase P5 in Amber's roadmap).

---

Review Amber PC (Windows, `src-tauri`) and Amber Server (Linux, `server`) and the shared
`crates/core` for security, as they are exposed to phones through the phone API
(`docs/phone-api.md`): in the home network and through a Cloudflare tunnel. Find real problems,
rank them by impact, fix what is clearly a bug with tests, and list the rest with a proposal.
Follow this repo's rules for commits and releases.

Cover at least:

1. **Access keys.** The key travels in the URL path (`/<key>/v1/...`) or as a Bearer header.
   Is the comparison constant-time? Key length and randomness when generated; where keys are
   stored on disk and with which permissions; rotation and removal; whether a key ever reaches a
   log line, an error message, the tunnel's access logs or a crash report. Would header-only auth
   be possible without breaking Amber for Android 0.26 (it sends the key in the path today)?
2. **Exposure.** Bind address and port; what a device in the same Wi-Fi can reach without a key;
   firewall rules the installer adds; the tunnel's configuration (what Cloudflare sees, which
   paths are exposed); the Stream Deck control port (127.0.0.1 only?).
3. **Input handling.** Upload size and duration caps for `/v1/audio/transcriptions` (WAV and Ogg
   Opus); memory and CPU limits while decoding; malformed WAV/Ogg headers; multipart parsing;
   `language`, `prompt` and `model` fields (a model id must never become a file path - path
   traversal); `/v1/chat/completions` body size, message count and length; what a request can
   make the PC do (load another model, start processes).
4. **Rate limits and abuse.** Per key and per sender; the wrong-key lockout; whether a stolen key
   can run up cloud costs when the PC forwards to a cloud provider; quotas per key.
5. **Data.** Is any audio or text written to disk (temp files, logs, caches), and is it deleted?
   Do the logs stay free of transcripts, prompts and keys? What `/v1/models` reveals about the PC.
6. **Prompt injection in the cleanup.** The phone sends its own system prompt and the dictation;
   can a dictation make the PC's model do anything beyond returning text?
7. **Dependencies.** `cargo audit` (or equivalent) for both binaries and the Tauri frontend's npm
   packages; known CVEs; pinned versions of whisper.cpp and the LLM runtime.
8. **Updates and service rights.** How updates are fetched and verified; whether Amber Server runs
   as root or a dedicated user; file permissions of its config and models.

Report: a table of findings (severity, where, what an attacker gains, fix), then the fixes you
made with their tests, then what needs a decision from Fabi. Tell Amber for Android's side what
it must change (for example header auth or a new error code), so its phase P5 can follow.
