# F-Droid recipe

`app.amber.yml` is the draft for fdroiddata (`metadata/app.amber.yml`). Do not submit it yet.

## Placeholder to bump

The `Builds` entry says versionName 0.31.0 / versionCode 59 / `commit: v0.31.0`. That is a
placeholder: v0.31.0 predates the from-source build (it fetched prebuilt binaries) and F-Droid
would fail on it. Before the merge request, bump `versionName`, `versionCode`, `commit`,
`CurrentVersion` and `CurrentVersionCode` to the first release tagged from the `fdroid-build`
branch, and publish that release's APK as `amber-<versionName>.apk` on GitHub (the `Binaries:`
line points there; F-Droid compares it with its own build and ships our signature).

## What the recipe does

- `submodules: true`: whisper.cpp and sherpa-onnx (with its eight dependencies) come from
  `third_party/`, compiled by Gradle's CMake with `FETCHCONTENT_FULLY_DISCONNECTED`. ONNX
  Runtime comes from Maven Central.
- `sudo`: a Go of 1.21 or newer (Debian `golang-go` from bookworm-backports); older ones ignore
  `GOTOOLCHAIN`. `tools/build-tinfoil-aar.sh` then fetches the Go version pinned by tinfoil-go
  and builds the Tinfoil verifier AAR with gomobile.
- The AAR is built in `build:`, not `prebuild:`: `fdroid scanner` runs `prebuild` and then flags
  any AAR/JAR it finds (as Cryptocam's recipe avoids by building its AAR in `build:`).
- Release builds are byte-reproducible (`tools/check-reproducible.sh`) and signed with
  `apksigner --alignment-preserved true`, so F-Droid can copy our signature
  (`AllowedAPKSigningKeys` is the GitHub release key).

## Test with fdroidserver

```bash
python3 -m venv /tmp/fdv && /tmp/fdv/bin/pip install fdroidserver
```

`fdroid lint` needs an fdroiddata-like layout: a directory with `metadata/app.amber.yml`, a
`config.yml`, and fdroiddata's `config/categories.yml` and `config/antiFeatures.yml` (plus the
icons they name; any PNG will do for lint).

```bash
mkdir -p lintdir/metadata && cp docs/fdroid/app.amber.yml lintdir/metadata/
cd lintdir && /tmp/fdv/bin/fdroid lint app.amber
```

For `fdroid scanner`, point `Repo:` at a local clone of the branch, `commit:` at its HEAD, and
`config.yml` at an SDK and NDK 27.1.12297006 (`sdk_path`, `ndk_paths`). Submodule URLs in the
clone must be local paths (or have network). The scanner runs the recipe's `prebuild` only, so
it does not build the AAR.

Last run (2026-11-10, fdroidserver 2.4.5): `fdroid lint` clean; `fdroid scanner` 0 problems.

## Scanner hits and how they are handled

All in the submodules, none in code Amber builds or ships; each is a `scandelete` in the recipe.

| Hit | Where | Why it is safe to delete |
|---|---|---|
| dependency files without lock, Gradle files, play-services and unknown Maven repos | sherpa-onnx: `flutter`, `dart-api-examples`, `nodejs-examples`, `nodejs-addon-examples`, `tauri-examples`, `scripts`, `.github`, `sherpa-onnx/rust`, `android/*WearOs` | Examples and bindings for other platforms; the CMake build uses none of them (`flutter` also covers `flutter-examples`) |
| unknown Maven repos, dependency file | whisper.cpp: `examples`, `bindings/java` | Not built (`WHISPER_BUILD_EXAMPLES=OFF`); `bindings/` itself stays because whisper's CMake reads it |
| binaries (`for-tests-*.bin`) | whisper.cpp `models/` | Tiny test fixtures, used only by whisper's own tests |
| AAR and JAR | `app/libs/tinfoil-*` | Built from source by the recipe; produced in `build:`, after the scan |

## For the merge request template

- Application ID `app.amber`, license Apache-2.0, built from source including the speech
  runtimes and the Tinfoil verifier; no prebuilt binaries.
- Anti-feature `NonFreeNet`: Groq, OpenAI and Tinfoil, optional, user's own key.
- Not before 2027-01-27 (F-Droid's three-month rule; first commit 2026-10-27).
- Speech models are downloaded on request from Hugging Face; recognition runs on the phone.
