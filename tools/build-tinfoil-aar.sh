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
# works in a fresh private directory: this library decides what the app may label "enclave
# verified", so a swapped download or a directory planted in a shared /tmp must not reach it.
# (Same tree as the Go module proxy's, sumdb h1:W32IF7d8YhW3E1G0k7md0rOAUxLec+h6S+pb4iQavdw=.)
#
# Needs: Go (version per tinfoil-go's go.mod), the Android NDK, curl, tar, sha256sum.
set -euo pipefail

TINFOIL_GO_REF="fad77808b7ace9d848399c27179b4a2d6fd2c36c"
TINFOIL_GO_SHA256="bc1871daeefc60ddbb53175ee67e54e070acd7db761d6d5b1a2a6b9331f93035"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/libs/tinfoil-mobile.aar"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

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

echo "==> fetching tinfoil-go@$TINFOIL_GO_REF"
curl -fsSL -o "$WORK/src.tar.gz" "https://github.com/tinfoilsh/tinfoil-go/archive/$TINFOIL_GO_REF.tar.gz"
echo "$TINFOIL_GO_SHA256  $WORK/src.tar.gz" | sha256sum -c --quiet - \
  || { echo "tinfoil-go archive does not match its pinned SHA-256 - not building" >&2; exit 1; }
mkdir -p "$WORK/src"
tar -xzf "$WORK/src.tar.gz" -C "$WORK/src" --strip-components=1

cd "$WORK/src"
export GOBIN="$WORK/bin"
export PATH="$GOBIN:$PATH"
echo "==> installing gomobile and gobind from tinfoil-go's go.mod"
go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind

echo "==> binding the mobile package for android/arm64 (NDK $(basename "$ANDROID_NDK_HOME"))"
mkdir -p "$(dirname "$OUT")"
# arm64 only, like the rest of Amber; -javapkg keeps the generated classes out of the
# default package namespace; -s -w drops symbol tables the app never uses; 16 KB segment
# alignment so the library also loads on devices with 16 KB memory pages.
gomobile bind \
  -target=android/arm64 \
  -androidapi=28 \
  -javapkg=sh.tinfoil \
  -trimpath \
  -ldflags="-s -w -extldflags=-Wl,-z,max-page-size=16384" \
  -o "$OUT" \
  ./mobile

echo "==> $(du -h "$OUT" | cut -f1)  $OUT"
