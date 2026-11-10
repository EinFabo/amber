# F-Droid-ready build - implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Amber builds entirely from pinned sources (no prebuilt native binaries except ONNX
Runtime from Maven Central), byte-for-byte reproducibly, so F-Droid can build it and ship it with
our GitHub signature.

**Architecture:** sherpa-onnx v1.13.8 and its CMake dependencies, and whisper.cpp v1.9.5, become
git submodules under `third_party/`, compiled by the app's one CMake project with
`FETCHCONTENT_FULLY_DISCONNECTED`. ONNX Runtime comes from the official Maven AAR; its headers and
`libonnxruntime.so` are unpacked for CMake. sherpa-onnx's Kotlin API is compiled from the
submodule. The Tinfoil AAR keeps its gomobile script, made byte-identical across runs. The model
is Marmalade STT (`app.marmalade.stt`, in F-Droid, reproducible): see its
`app/src/main/cpp/CMakeLists.txt` and `app/build.gradle.kts` at commit
`c51648c0fe7cca8f92a7300809efd1c0a51261ce` on github.com/maxwhipw/marmalade-stt-android.

**Tech Stack:** Gradle 8.14 / AGP 8.9.2, CMake 3.22.1, NDK 27.1.12297006, Go + gomobile, git
submodules, fdroidserver (for checks), apksigcopier.

**Spec:** `docs/OPEN.md`, section "F-Droid release" (findings of 2026-11-10).

## Global Constraints

- Branch `fdroid-build`, worktree `/home/fabi/projects/amethyst-fdroid`. Commits carry Fabi's name
  only: no `Co-Authored-By`, no `Claude-Session`, no "Generated with" line.
- arm64-v8a only. `ndkVersion = "27.1.12297006"`. CMake 3.22.1. minSdk 28.
- sherpa-onnx **v1.13.8** with TTS, speaker diarization, C API, binaries, websocket, portaudio,
  python, tests, check all **OFF**; JNI **ON**. No espeak-ng or piper code may reach the APK.
- ONNX Runtime: `com.microsoft.onnxruntime:onnxruntime-android:1.28.0` from Maven Central, one
  version constant feeding both the runtime dependency and the CMake headers/lib.
- whisper.cpp **v1.9.5** with `app/src/main/cpp/patch-whisper.cmake` applied to a copy in the
  build tree, never to the submodule's working tree.
- Every native library: 16 KB segment alignment (`-Wl,-z,max-page-size=16384`), no GNU build-id
  (`-Wl,--build-id=none`), no absolute build paths (`-ffile-prefix-map`).
- `app/src/main/kotlin/app/amethyst/engine/SherpaRuntime.kt` keeps its imports
  (`com.k2fsa.sherpa.onnx.*`) and behaviour; `:core` is not touched.
- All text in English (code comments, docs, script output). README and NOTICE updated in the
  task that changes what they describe.
- Checks that must stay green after every task: `./gradlew :core:test`,
  `python3 -I tools/check_refs.py`, `python3 -I tools/check_strings.py`,
  `tools/typecheck-android.sh`, `./gradlew :app:lintDebug :app:assembleDebug`.

## Review Focus

1. A clone without `--recursive` (or with an empty `third_party/x`) must stop at configure time
   with a message naming `git submodule update --init --recursive`, not a CMake stack trace or a
   download.
2. A dependency that sherpa-onnx would still fetch from the network must fail the build
   (`FETCHCONTENT_FULLY_DISCONNECTED`), not be downloaded silently.
3. The whisper patch must really be applied: a configure where `patch-whisper.cmake` fails or
   finds nothing to patch must fail the build.
4. Parakeet must still load on the phone: a mismatch between the ORT headers sherpa is compiled
   against and the `libonnxruntime.so` that ships shows only at runtime (`UnsatisfiedLinkError`
   or a crash in `OfflineRecognizer`). Task 2 checks the shipped `.so` is the Maven one; Task 6
   is the device test.
5. Our signed GitHub APK must equal F-Droid's unsigned build plus our signature. Re-aligning in
   `tools/sign-release.sh` (`zipalign -f -p 4`) would break that; Task 4 proves it with
   `apksigcopier compare`.

---

### Task 1: whisper.cpp as a submodule, the CMake project made offline

**Files:**
- Create: `.gitmodules`, submodule `third_party/whisper.cpp` (tag `v1.9.5`)
- Modify: `app/src/main/cpp/CMakeLists.txt`, `app/src/main/cpp/patch-whisper.cmake` (only if it
  needs to report "nothing patched"), `app/build.gradle.kts` (CMake arguments),
  `.github/workflows/android.yml` (checkout with `submodules: recursive`), `README.md` (Building:
  `git clone --recursive`, `git submodule update --init --recursive`), `CLAUDE.md` (Building block)

**Interfaces:**
- Produces: CMake cache variable `THIRD_PARTY_DIR` (absolute path to `<repo>/third_party`), passed
  by `app/build.gradle.kts` as `-DTHIRD_PARTY_DIR=...`; the `FETCHCONTENT_FULLY_DISCONNECTED ON`
  setting and a CMake function `amber_require_submodule(<dir>)` that Task 2 reuses.

- [ ] **Step 1:** `git submodule add https://github.com/ggml-org/whisper.cpp third_party/whisper.cpp`,
  check out tag `v1.9.5`, mark it `shallow = true` in `.gitmodules`. Confirm the tree matches the
  tarball the CMake file pins today: `git -C third_party/whisper.cpp archive --prefix=whisper.cpp-1.9.5/ v1.9.5`
  is not byte-identical to GitHub's tarball, so compare file lists and contents with
  `diff -r` against the extracted pinned tarball instead (SHA-256
  `ff1a9053feb509ff9d7729703355541ae9690073a6b1c40eb692c962e0dc1720`). Expected: no differences.
- [ ] **Step 2:** In `CMakeLists.txt`: `FATAL_ERROR` when `THIRD_PARTY_DIR` is unset;
  `amber_require_submodule(whisper.cpp)` fails with
  `"third_party/whisper.cpp is empty. Run: git submodule update --init --recursive"` when
  `${THIRD_PARTY_DIR}/whisper.cpp/CMakeLists.txt` is missing; `FETCHCONTENT_FULLY_DISCONNECTED ON`;
  copy `third_party/whisper.cpp` (excluding `.git`) to `${CMAKE_BINARY_DIR}/whisper-src`, run
  `patch-whisper.cmake` on the copy with `execute_process` and fail on a non-zero result, point
  `FETCHCONTENT_SOURCE_DIR_WHISPER` at the copy, and add
  `-ffile-prefix-map=${copy}=third_party/whisper.cpp`. Drop the `URL`/`URL_HASH`/`PATCH_COMMAND`
  declaration. Keep the job pools, flags and `amethyst_whisper` target as they are.
- [ ] **Step 3:** Verify Review Focus 1 and 3: temporarily move
  `third_party/whisper.cpp` aside, run `./gradlew :app:assembleDebug`, expect the message from
  Step 2; move it back. Then make `patch-whisper.cmake`'s search string not match (temporary
  edit), expect a configure failure; revert.
- [ ] **Step 4:** `./gradlew :app:assembleDebug` with the network cut for CMake (e.g.
  `unshare -rn` around the CMake configure if available, otherwise check the configure log for
  no `Downloading`/`-- Populating` lines). Expected: builds; `git -C third_party/whisper.cpp status
  --porcelain` is empty (the patch went to the copy).
- [ ] **Step 5:** `unzip` the debug APK; `readelf -lW lib/arm64-v8a/libamethyst_whisper.so`
  shows `LOAD` alignment `0x4000`; `strings` on it finds no `/home/` and no absolute path of the
  worktree.
- [ ] **Step 6:** Run the Global Constraints checks, update README/CLAUDE.md/CI, commit
  ("whisper.cpp as a submodule, the native build offline").

### Task 2: sherpa-onnx from source against ONNX Runtime from Maven Central

**Files:**
- Create: submodules under `third_party/` for sherpa-onnx (tag `v1.13.8`) and every dependency
  its `cmake/*.cmake` fetches for an Android JNI build with TTS off (expected, per Marmalade:
  kaldi-native-fbank, kissfft, kaldi-decoder, kaldifst, openfst, eigen, simple-sentencepiece,
  json) - each at the exact tag/commit sherpa-onnx v1.13.8's cmake file names.
- Modify: `app/src/main/cpp/CMakeLists.txt`, `app/build.gradle.kts`, `gradle/libs.versions.toml`
  (`onnxruntime = "1.28.0"`, library `onnxruntime-android`), `app/proguard-rules.pro` (keep rules
  still match the classes), `tools/typecheck-android.sh` / `tools/typecheck-stubs/SherpaOnnxStub.kt`
  (if the typecheck needs the real Kotlin API now), `.github/workflows/android.yml` (drop the
  fetch step), `README.md`, `CLAUDE.md` (drop `tools/fetch-sherpa-aar.sh` line), `NOTICE` (sherpa
  built without TTS; add each compiled dependency with its license: kaldi-native-fbank Apache-2.0,
  kissfft BSD-3-Clause, kaldi-decoder Apache-2.0, kaldifst Apache-2.0, openfst Apache-2.0, Eigen
  MPL-2.0, simple-sentencepiece Apache-2.0, nlohmann/json MIT - verify each against the
  submodule's LICENSE file)
- Delete: `tools/fetch-sherpa-aar.sh`, the `sherpaAar` file dependency and its `preBuild` check

**Interfaces:**
- Consumes: `THIRD_PARTY_DIR`, `amber_require_submodule(<dir>)`, `FETCHCONTENT_FULLY_DISCONNECTED`
  from Task 1.
- Produces: `libsherpa-onnx-jni.so` (loaded by sherpa's Kotlin API via
  `System.loadLibrary("sherpa-onnx-jni")`) and the Maven `libonnxruntime.so` in the APK; Kotlin
  classes `com.k2fsa.sherpa.onnx.{OfflineRecognizer, OfflineRecognizerConfig, OfflineModelConfig,
  OfflineTransducerModelConfig, FeatureConfig}` compiled from
  `third_party/sherpa-onnx/sherpa-onnx/kotlin-api/` (only the files these need, listed
  explicitly in Gradle).

- [ ] **Step 1:** Add the submodules (shallow). For each dependency, read the URL/version from
  `third_party/sherpa-onnx/cmake/<dep>.cmake` at v1.13.8 and pin the submodule to that tag or
  commit. Record the mapping (FetchContent name -> directory -> pinned ref) in a comment at the
  top of the CMake block.
- [ ] **Step 2:** Gradle: one version constant from the catalog feeds
  `implementation(libs.onnxruntime.android)` and an `onnxRuntimeAar` configuration
  (`...@aar`); a `Sync` task `extractOnnxRuntime` unpacks `headers/**` and
  `jni/arm64-v8a/libonnxruntime.so` to `build/onnxruntime`; every `configureCMake*`/
  `buildCMake*` task depends on it. CMake arguments `-DORT_DIR=<that dir>`,
  `-DANDROID_STL=c++_static`. `packaging.jniLibs.pickFirsts += "lib/*/libonnxruntime.so"`.
  `cFlags`/`cppFlags += "-ffile-prefix-map=<repo root>=."`;
  `-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,--build-id=none`.
- [ ] **Step 3:** CMake: `ENV{SHERPA_ONNXRUNTIME_INCLUDE_DIR}` / `ENV{SHERPA_ONNXRUNTIME_LIB_DIR}`
  from `ORT_DIR`; `FETCHCONTENT_SOURCE_DIR_<NAME>` for each dependency; the
  simple-sentencepiece copy in the build tree (sherpa rewrites its sources on Android) with its
  own `-ffile-prefix-map`; `SHERPA_ONNX_ENABLE_*` per Global Constraints;
  `BUILD_SHARED_LIBS OFF`; `add_subdirectory(third_party/sherpa-onnx ...)` (or a copy, as
  Marmalade does, if sherpa writes into its own tree - check `git status` of the submodule after
  a build). The existing job pools apply to sherpa too.
- [ ] **Step 4:** Kotlin API: add the needed `kotlin-api/*.kt` files to the `main` source set
  (explicit file list, not the whole directory - most files pull in TTS/diarization classes).
  Delete the AAR dependency, its `preBuild` check and `tools/fetch-sherpa-aar.sh`.
- [ ] **Step 5:** Verify the APK: `./gradlew :app:assembleDebug :app:assembleRelease`;
  `unzip -l` lists exactly `libamethyst_whisper.so`, `libsherpa-onnx-jni.so`,
  `libonnxruntime.so`, `libgojni.so` plus the two androidx ones, and **no**
  `libsherpa-onnx-c-api.so`/`-cxx-api.so`; `strings libsherpa-onnx-jni.so | grep -ci 'espeak\|piper'`
  prints `0`; `sha256sum` of the shipped `libonnxruntime.so` equals the one inside the Maven AAR
  in the Gradle cache; `readelf -lW` shows `0x4000` alignment and `readelf -n` shows no
  `NT_GNU_BUILD_ID` for every `.so` we compile; no `/home/` in `strings`.
- [ ] **Step 6:** Review Focus 2: temporarily remove one `FETCHCONTENT_SOURCE_DIR_*` line, expect
  the configure to fail (not download); revert. `git submodule foreach git status --porcelain`
  is empty after a build.
- [ ] **Step 7:** `./gradlew :app:testDebugUnitTest` (Robolectric, includes `Screenshots`) and the
  Global Constraints checks pass. Note the release APK size before/after in the commit message.
  Commit ("sherpa-onnx from source without TTS, ONNX Runtime from Maven Central").

### Task 3: the Tinfoil AAR, byte-identical across runs

**Files:**
- Modify: `tools/build-tinfoil-aar.sh`, `README.md` (Building)

**Interfaces:**
- Produces: `tools/build-tinfoil-aar.sh` honouring `ANDROID_NDK_HOME`, `GOTOOLCHAIN` and an
  optional `OUT=<path>`; output `app/libs/tinfoil-mobile.aar` whose `jni/arm64-v8a/libgojni.so`
  and `classes.jar` entries are identical across two runs.

- [ ] **Step 1:** Build twice (`OUT=/tmp/.../a.aar`, `OUT=/tmp/.../b.aar`, different working
  directories - the script already uses a fresh `mktemp -d`), unzip both, `sha256sum` every
  entry. Expected today: record which entries differ.
- [ ] **Step 2:** Fix the differences: pin the Go toolchain to the version tinfoil-go's `go.mod`
  names (`GOTOOLCHAIN=go<version>` exported by the script, so Debian's older `golang-go` fetches
  the right one), `-buildid=` in `-ldflags`, `-extldflags=-Wl,-z,max-page-size=16384 -Wl,--build-id=none`,
  `CGO_CFLAGS=-ffile-prefix-map=<work dir>=.`, and keep `-trimpath`. Zip timestamps of the AAR
  itself do not matter (AGP repacks the entries), the entry contents do.
- [ ] **Step 3:** Re-run Step 1. Expected: every entry identical. `readelf -lW` on `libgojni.so`
  shows `0x4000` alignment.
- [ ] **Step 4:** Commit ("Tinfoil AAR: the same bytes on every build").

### Task 4: a reproducible release APK, signed without changing its bytes

**Files:**
- Create: `tools/check-reproducible.sh`
- Modify: `app/build.gradle.kts` (release: `isCrunchPngs = false`; anything else the check
  finds), `tools/sign-release.sh` (no re-zipalign when the input is already aligned - check with
  `zipalign -c -p 4`; sign the AGP output as is), `README.md` (a "Reproducible builds" section:
  how to rebuild a release and compare, with `apksigcopier compare`)

**Interfaces:**
- Consumes: Tasks 1-3.
- Produces: `tools/check-reproducible.sh [<git ref>]` - clones the ref (default `HEAD`) with
  submodules into two fresh temp directories at different paths, builds
  `:app:assembleRelease` in each (Tinfoil AAR via the script in each), compares the two unsigned
  APKs byte for byte and prints `reproducible: <sha256>` or the differing entries (via
  `diffoscope` when installed, else `unzip` + `sha256sum` per entry) and exits 1.

- [ ] **Step 1:** Write `tools/check-reproducible.sh` as specified; run it on the branch.
  Expected at first: likely differences. Record them.
- [ ] **Step 2:** Fix each source of difference in the build (not in the script). Usual
  suspects: PNG crunching, `vcsInfo` (keep it on: both clones are at the same commit), native
  `__FILE__` paths, build-ids, Gradle/R8 nondeterminism (R8 is deterministic for the same
  inputs), the JDK (pin Temurin 21 in README and CI).
- [ ] **Step 3:** Re-run. Expected: `reproducible: <sha256>`, exit 0.
- [ ] **Step 4:** Review Focus 5: sign one of the two APKs with a throwaway key through the same
  steps `tools/sign-release.sh` uses on the signing host (locally, `apksigner` from build-tools
  35.0.0), then `apksigcopier compare signed.apk --unsigned other-unsigned.apk`. Expected:
  success. `pip install apksigcopier` in a venv under the scratchpad if missing.
- [ ] **Step 5:** CI: the workflow builds `:app:assembleRelease` too and prints the unsigned APK's
  SHA-256 (no signing in CI). Commit ("Reproducible release build, signed without re-aligning").

### Task 5: the F-Droid recipe and a clean scan

**Files:**
- Create: `docs/fdroid/app.amber.yml` (the draft for the fdroiddata merge request), 
  `docs/fdroid/README.md` (how to test it with fdroidserver, what to paste into the MR template)
- Modify: `docs/OPEN.md` (F-Droid section: what is done, what is left)

**Interfaces:**
- Consumes: Tasks 1-4.

- [ ] **Step 1:** Write the recipe: `Categories: [System, Writing]`, `License: Apache-2.0`,
  `AuthorName: EinFabo`, `SourceCode`/`IssueTracker`/`Changelog` on github.com/EinFabo/amber,
  `AntiFeatures: NonFreeNet` with an en-US reason naming Groq, OpenAI and Tinfoil and that they
  are optional with the user's own key, `RepoType: git`, `Repo`, `Binaries:
  https://github.com/EinFabo/amber/releases/download/v%v/amber-%v.apk`,
  `AllowedAPKSigningKeys: c3f69e720b6f195fc0b45dd538a0844e02c196e73b2909fc88d959be96d68e11`, one
  `Builds` entry for the next release's versionName/versionCode with `commit: v<version>`,
  `subdir: app`, `submodules: true`, `sudo` installing `golang-go`, `prebuild` running
  `../tools/build-tinfoil-aar.sh` with `ANDROID_NDK_HOME=$$NDK$$`, `ndk: 27.1.12297006`,
  `gradle: [yes]`, `scandelete` for submodule paths that hold binaries; `AutoUpdateMode: Version`,
  `UpdateCheckMode: Tags`, `CurrentVersion`/`CurrentVersionCode`.
- [ ] **Step 2:** In a venv under the scratchpad, `pip install fdroidserver`; run
  `fdroid lint` on the recipe (in a minimal fdroiddata-like layout) and `fdroid scanner` on a
  clean recursive clone of the branch. Expected: no errors; every scanner hit is either fixed or
  covered by a `scandelete`/`scanignore` with a one-line reason in the README.
- [ ] **Step 3:** Update `docs/OPEN.md`, commit ("F-Droid recipe draft and scanner run").

### Task 6: device check (controller, not a subagent)

- [ ] Install the branch's debug build on the Pixel (adb via Windows `adb.exe`, keep the screen on
  during the test and reset afterwards). Parakeet: a German and an English dictation recognize
  correctly; Whisper: one dictation; Tinfoil: "Verified enclave" still shows. Note the Parakeet
  wait time against 0.31.0 in `docs/OPEN.md`; the 80-sentence accuracy run stays for Fabi.
