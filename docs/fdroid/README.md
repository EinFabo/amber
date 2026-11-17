# F-Droid recipe

`com.embersay.yml` is the draft for fdroiddata (`metadata/com.embersay.yml`). Do not submit it yet.

## Before the merge request (checked against F-Droid's docs and fdroiddata, 2026-11-14)

- **Not before 2027-01-27.** Inclusion Policy, quality control point 6 (added 2026-10-16): an
  app "should have been actively developed for at least 3 months" unless it has proven users
  or its author maintains other F-Droid apps. Merge requests for younger apps are closed the
  same day. The first commit is from 2026-10-27; the end of January is the safe date.
- **Prove the recipe in a fork of fdroiddata.** Its pipeline runs `fdroid build` in the build
  server's container and compares the result with the `Binaries:` APK. Not done yet.
- **Go.** fdroiddata's convention is a Go built from source (`srclibs: go@go1.x.y`,
  `./make.bash`, `GOTOOLCHAIN=local`); this recipe lets Debian's Go fetch the toolchain
  tinfoil-go pins. Allowed (recipes run `go install` in `build:`), but expect the review
  comment - and a source-built Go must then give the same `libgojni.so`, to be tried in the
  fork. tinfoil-go as a submodule instead of the tarball `tools/build-tinfoil-aar.sh` fetches
  would match "submodules over downloads".
- **`commit:` is the full hash**, not the tag (merge request template).
- **Tracking.** F-Droid ships the GitHub-signed APK, which checks GitHub for updates. From the
  release after 0.33.0 an install made by an app store never checks
  (`ReleaseChannel.installedBy`); 0.33.0 itself still would, so name a later release here.
- **Name and id: done (2026-11-17).** F-Droid lists another "Amber"
  (`com.greenart7c3.nostrsigner`), and `app.amber` read as the domain amber.app, which is not
  ours. Fabi renamed the app to Embersay, id `com.embersay`, before any store release; after
  inclusion the id is fixed. Still open: the domain embersay.com (the id reads as it), and the
  GitHub repository's name (`EinFabo/amber` in the URLs below until it moves - GitHub redirects).
- **IzzyOnDroid** is no route: its inclusion policy rejects apps made with generative AI tools.

## The Builds entry

The `Builds` entry names the latest release from the from-source build (0.33.0 / versionCode
61 / `commit: v0.33.0`), whose APK is on GitHub as `amber-0.33.0.apk`. That release is still
`app.amber`: the first release as `com.embersay` replaces the entry, and its APK is
`embersay-<version>.apk`, which the `Binaries:` line already names (F-Droid compares it with
its own build and ships our signature). With every
release, move `versionName`, `versionCode`, `commit`, `CurrentVersion` and
`CurrentVersionCode` along. Releases up to v0.31.0 fetched prebuilt binaries and can never be
named here.

## What the recipe does

- `submodules: true`: whisper.cpp and sherpa-onnx (with its eight dependencies) come from
  `third_party/`, compiled by Gradle's CMake with `FETCHCONTENT_FULLY_DISCONNECTED`. ONNX
  Runtime comes from Maven Central.
- `sudo`: a Go of 1.21 or newer (Debian `golang-go` from trixie-backports - the build server
  is Debian trixie with OpenJDK 21 since 2026); older ones ignore
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

`fdroid lint` needs an fdroiddata-like layout: a directory with `metadata/com.embersay.yml`, a
`config.yml`, and fdroiddata's `config/categories.yml` and `config/antiFeatures.yml` (plus the
icons they name; any PNG will do for lint).

```bash
mkdir -p lintdir/metadata && cp docs/fdroid/com.embersay.yml lintdir/metadata/
cd lintdir && /tmp/fdv/bin/fdroid lint com.embersay
```

For `fdroid scanner`, point `Repo:` at a local clone of the branch, `commit:` at its HEAD, and
`config.yml` at an SDK and NDK 27.1.12297006 (`sdk_path`, `ndk_paths`). Submodule URLs in the
clone must be local paths (or have network). The scanner runs the recipe's `prebuild` only, so
it does not build the AAR.

Last run (2026-11-10, fdroidserver 2.4.5, still as `app.amber`): `fdroid lint` clean;
`fdroid scanner` 0 problems.

## Scanner hits and how they are handled

The submodule hits are in code Embersay neither builds nor ships; each is a `scandelete` in the
recipe. The Tinfoil AAR is not in git at all (`app/libs/*.aar` is gitignored): the recipe builds
it in `build:`, after the scan, so the scanner never sees it and there is nothing to delete.

| Hit | Where | Why it is safe to delete |
|---|---|---|
| dependency files without lock, Gradle files, play-services and unknown Maven repos | sherpa-onnx: `flutter`, `dart-api-examples`, `nodejs-examples`, `nodejs-addon-examples`, `tauri-examples`, `scripts`, `.github`, `sherpa-onnx/rust`, `android/*WearOs` | Examples and bindings for other platforms; the CMake build uses none of them (`flutter` also covers `flutter-examples`) |
| unknown Maven repos, dependency file | whisper.cpp: `examples`, `bindings/java` | Not built (`WHISPER_BUILD_EXAMPLES=OFF`); `bindings/` itself stays because whisper's CMake reads it |
| binaries (`for-tests-*.bin`) | whisper.cpp `models/` | Tiny test fixtures, used only by whisper's own tests |
| AAR and JAR (only if built before the scan) | `app/libs/tinfoil-*` | Not in git (gitignored), so not a `scandelete`: built from source in `build:`, after the scan |

## For the merge request template

- Application ID `com.embersay`, license Apache-2.0, built from source including the speech
  runtimes and the Tinfoil verifier; no prebuilt binaries.
- Anti-feature `NonFreeNet`: Groq, OpenAI and Tinfoil, optional, user's own key.
- Not before 2027-01-27 (F-Droid's three-month rule; first commit 2026-10-27).
- Speech models are downloaded on request from Hugging Face; recognition runs on the phone.
