#!/usr/bin/env bash
# Builds app/libs/tinfoil-mobile.aar: Tinfoil's attestation verifier for Android.
#
# Tinfoil has no Kotlin SDK. tinfoil-go's `mobile` package is the verifier its Swift SDK binds
# through gomobile - it performs no I/O, takes the attestation document as bytes and returns
# what it proved (repo, release, measurements, the TLS key fingerprint) as JSON. Binding the
# same package for Android means Amber verifies with exactly the code Tinfoil ships and tests,
# instead of a re-implementation of SEV-SNP/TDX quote checking and Sigstore in Kotlin.
#
# The commit is pinned: a new tinfoil-go is a deliberate update, not whatever main is today.
# gomobile and gobind come from tinfoil-go's own go.mod (tool dependencies), so the binding
# generator is the version Tinfoil builds its framework with.
#
# The archive is checked against its SHA-256 before anything is extracted, and every run
# works in a fresh private directory (fixed name, see below): this library decides what the
# app may label "enclave verified", so a swapped download or a directory planted in a shared /tmp must not reach it.
# (Same tree as the Go module proxy's, sumdb h1:W32IF7d8YhW3E1G0k7md0rOAUxLec+h6S+pb4iQavdw=.)
#
# The same bytes on every build: the Go toolchain is the one tinfoil-go's go.mod names (an older
# system Go downloads it), build ids and the GNU build-id note are dropped, and the work
# directory has a fixed path, so two runs from different directories give an identical
# jni/arm64-v8a/libgojni.so and classes.jar.
#
# Needs: Go (version per tinfoil-go's go.mod), the Android NDK, curl, tar, sha256sum.
# Environment: ANDROID_NDK_HOME (default: NDK 27.1.12297006 under $ANDROID_HOME/ndk),
# OUT=<path> (default: app/libs/tinfoil-mobile.aar).
set -euo pipefail

TINFOIL_GO_REF="fad77808b7ace9d848399c27179b4a2d6fd2c36c"
TINFOIL_GO_SHA256="bc1871daeefc60ddbb53175ee67e54e070acd7db761d6d5b1a2a6b9331f93035"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$ROOT/app/libs/tinfoil-mobile.aar}"
case "$OUT" in /*) ;; *) OUT="$PWD/$OUT" ;; esac
# The work directory's path ends up in the library (Go records the replaced module's directory
# in its build info), so it has to be the same on every build, whichever clone or directory
# the script runs from and whoever runs it: one fixed name, created private (0700) and refused
# unless it is a real directory of ours - never one somebody else planted. An exclusive lock
# held for the whole run makes a second build wait instead of deleting the first one's files.
# A lock file or directory of somebody else's can only make the build refuse, never reach it.
WORK=/tmp/amber-tinfoil-build
LOCK="$WORK.lock"
[ ! -L "$LOCK" ] && { [ ! -e "$LOCK" ] || { [ -f "$LOCK" ] && [ -O "$LOCK" ] && [ -w "$LOCK" ]; }; } \
  || { echo "$LOCK exists but is not a file of yours - remove it or ask its owner; not building" >&2; exit 1; }
exec 9>>"$LOCK" || { echo "cannot open $LOCK - not building" >&2; exit 1; }
if ! flock -n 9; then
  echo "another Tinfoil AAR build is running ($LOCK) - waiting for it to finish ..." >&2
  flock 9
fi
rm -rf "$WORK" 2>/dev/null || true
mkdir -m 700 "$WORK" || { echo "$WORK exists and cannot be removed by you - not building" >&2; exit 1; }
trap 'rm -rf "$WORK"' EXIT   # only after our own mkdir succeeded: removes just what this run made
[ -d "$WORK" ] && [ ! -L "$WORK" ] && [ -O "$WORK" ] \
  || { echo "$WORK is not a private directory of yours" >&2; exit 1; }

# Go is often installed outside PATH on this machine; look there before giving up.
if ! command -v go >/dev/null && [ -x "$HOME/tools/go/bin/go" ]; then
  export PATH="$HOME/tools/go/bin:$PATH"
fi
command -v go >/dev/null || { echo "Go not found - install it or put it on PATH" >&2; exit 1; }

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/sdk}}"
NDK_PIN="27.1.12297006"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -d "$ANDROID_HOME/ndk/$NDK_PIN" ]; then
    ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$NDK_PIN"
  else
    echo "NDK $NDK_PIN not found - set ANDROID_NDK_HOME" >&2; exit 1
  fi
fi
export ANDROID_NDK_HOME
[ -d "$ANDROID_NDK_HOME" ] \
  || { echo "ANDROID_NDK_HOME ($ANDROID_NDK_HOME) is not a directory - no Android NDK there" >&2; exit 1; }
NDK_REV="$(sed -n 's/^Pkg.Revision *= *//p' "$ANDROID_NDK_HOME/source.properties" 2>/dev/null | head -1)"
if [ "$NDK_REV" != "$NDK_PIN" ]; then
  echo "warning: NDK at $ANDROID_NDK_HOME is ${NDK_REV:-of unknown version}, not the pinned $NDK_PIN -" \
    "the library may differ from the pinned build" >&2
fi

echo "==> fetching tinfoil-go@$TINFOIL_GO_REF"
curl -fsSL -o "$WORK/src.tar.gz" "https://github.com/tinfoilsh/tinfoil-go/archive/$TINFOIL_GO_REF.tar.gz"
echo "$TINFOIL_GO_SHA256  $WORK/src.tar.gz" | sha256sum -c --quiet - \
  || { echo "tinfoil-go archive does not match its pinned SHA-256 - not building" >&2; exit 1; }
mkdir -p "$WORK/src"
tar -xzf "$WORK/src.tar.gz" -C "$WORK/src" --strip-components=1

cd "$WORK/src"
[ -f go.mod ] || { echo "tinfoil-go has no go.mod at $WORK/src - not building" >&2; exit 1; }
# The toolchain tinfoil-go builds with (go.mod's `toolchain` line if present, else its `go`
# line); a system Go that is older fetches exactly this one. A plain `go 1.N` (N >= 21) names
# the release go1.N.0; anything else (go 1.20, rc versions) is used as written.
GO_TOOLCHAIN="$(awk '$1=="toolchain"{print $2; exit}' go.mod)"
[ -n "$GO_TOOLCHAIN" ] || GO_TOOLCHAIN="go$(awk '$1=="go"{print $2; exit}' go.mod)"
case "$GO_TOOLCHAIN" in
  go1.*) ;;
  *) echo "no Go version in tinfoil-go's go.mod" >&2; exit 1 ;;
esac
if [[ "$GO_TOOLCHAIN" =~ ^go1\.([0-9]+)$ ]] && [ "${BASH_REMATCH[1]}" -ge 21 ]; then
  GO_TOOLCHAIN="$GO_TOOLCHAIN.0"
fi
export GOTOOLCHAIN="$GO_TOOLCHAIN"
echo "==> Go toolchain $GOTOOLCHAIN"
ACTIVE_GO="$(go env GOVERSION | awk '{print $1}')"
[ "$ACTIVE_GO" = "$GOTOOLCHAIN" ] || {
  echo "the active Go is $ACTIVE_GO, not the pinned $GOTOOLCHAIN (a Go older than 1.21 cannot" \
    "fetch it) - install Go 1.21 or newer" >&2; exit 1; }
export CGO_CFLAGS="-ffile-prefix-map=$WORK=."
export GOFLAGS="-buildvcs=false"
export GOBIN="$WORK/bin"
export PATH="$GOBIN:$PATH"
echo "==> installing gomobile and gobind from tinfoil-go's go.mod"
go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind

echo "==> binding the mobile package for android/arm64 (NDK $(basename "$ANDROID_NDK_HOME"))"
mkdir -p "$(dirname "$OUT")"
# arm64 only, like the rest of Amber; -javapkg keeps the generated classes out of the
# default package namespace; -s -w drops symbol tables the app never uses; 16 KB segment
# alignment so the library also loads on devices with 16 KB memory pages; no build id,
# so nothing differs between runs.
gomobile bind \
  -target=android/arm64 \
  -androidapi=28 \
  -javapkg=sh.tinfoil \
  -trimpath \
  -ldflags="-s -w -buildid= '-extldflags=-Wl,-z,max-page-size=16384 -Wl,--build-id=none'" \
  -o "$OUT" \
  ./mobile

echo "==> $(du -h "$OUT" | cut -f1)  $OUT"
