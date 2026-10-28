#!/usr/bin/env bash
# Fetches app/libs/sherpa-onnx.aar: the runtime for the local Parakeet engine.
#
# sherpa-onnx (k2-fsa) publishes a ready Android AAR - the JNI library, onnxruntime and its
# Kotlin API - with every release. The version is pinned and its SHA-256 checked against the
# digest GitHub publishes for the release asset, so the build never picks up a different binary.
# Not checked in: about 50 MB, and reproducible from here.
set -euo pipefail

SHERPA_VERSION="1.13.8"
SHERPA_SHA256="633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/libs/sherpa-onnx.aar"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"

if [ -f "$OUT" ] && echo "$SHERPA_SHA256  $OUT" | sha256sum -c --status; then
  echo "==> sherpa-onnx $SHERPA_VERSION already in place"
  exit 0
fi

mkdir -p "$(dirname "$OUT")"
echo "==> fetching sherpa-onnx $SHERPA_VERSION"
curl -fSL --retry 3 -o "$OUT.part" "$URL"
echo "$SHERPA_SHA256  $OUT.part" | sha256sum -c --status || {
  echo "checksum mismatch for $URL" >&2
  rm -f "$OUT.part"
  exit 1
}
mv "$OUT.part" "$OUT"
echo "==> $(du -h "$OUT" | cut -f1)  $OUT"
