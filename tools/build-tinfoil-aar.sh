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
# Needs: Go (version per tinfoil-go's go.mod), the Android NDK, curl, tar.
set -euo pipefail

TINFOIL_GO_REF="fad77808b7ace9d848399c27179b4a2d6fd2c36c"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/libs/tinfoil-mobile.aar"
WORK="${TMPDIR:-/tmp}/amber-tinfoil-$TINFOIL_GO_REF"

# Go is often installed outside PATH on this machine; look there before giving up.
if ! command -v go >/dev/null && [ -x "$HOME/tools/go/bin/go" ]; then
  export PATH="$HOME/tools/go/bin:$PATH"
fi
command -v go >/dev/null || { echo "Go not found - install it or put it on PATH" >&2; exit 1; }

export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/sdk}}"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
fi
export ANDROID_NDK_HOME
[ -d "$ANDROID_NDK_HOME" ] || { echo "no Android NDK found under $ANDROID_HOME/ndk" >&2; exit 1; }

if [ ! -f "$WORK/src/go.mod" ]; then
  echo "==> fetching tinfoil-go@$TINFOIL_GO_REF"
  mkdir -p "$WORK/src"
  curl -fsSL "https://github.com/tinfoilsh/tinfoil-go/archive/$TINFOIL_GO_REF.tar.gz" \
    | tar -xz -C "$WORK/src" --strip-components=1
fi

cd "$WORK/src"
export GOBIN="$WORK/bin"
export PATH="$GOBIN:$PATH"
echo "==> installing gomobile and gobind from tinfoil-go's go.mod"
go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind

echo "==> binding the mobile package for android/arm64 (NDK $(basename "$ANDROID_NDK_HOME"))"
mkdir -p "$(dirname "$OUT")"
# arm64 only, like the rest of Amber; -javapkg keeps the generated classes out of the
# default package namespace; -s -w drops symbol tables the app never uses.
gomobile bind \
  -target=android/arm64 \
  -androidapi=28 \
  -javapkg=sh.tinfoil \
  -trimpath \
  -ldflags="-s -w" \
  -o "$OUT" \
  ./mobile

echo "==> $(du -h "$OUT" | cut -f1)  $OUT"
