#!/usr/bin/env bash
# Checks that Amber's release APK is reproducible: clones a commit twice, into two fresh
# directories whose paths differ in length (so an absolute path that leaks into the build
# shows up), builds the unsigned release APK in each and compares the two byte for byte.
#
#   tools/check-reproducible.sh [<git ref>]     (default: HEAD)
#
# Prints "reproducible: <sha256>" and exits 0 when the two APKs are identical. Otherwise it
# names what differs - with diffoscope when it is installed, else entry by entry (SHA-256 of
# each entry's content, and the zip metadata around it) - and exits 1.
#
# The clones come from this repository, not from GitHub, so the ref must be committed. Each
# submodule is cloned from the copy already checked out here when that holds the pinned
# commit; only a missing one is fetched from its upstream URL. Each clone builds its own
# Tinfoil AAR (tools/build-tinfoil-aar.sh, which needs Go and network for the Go modules) with
# its own Go build cache, and runs Gradle without a daemon and without the build cache, so
# nothing built by one clone can stand in for the other's output. After the verdict it checks
# that sherpa-onnx's text-to-speech part (espeak-ng, piper) stayed out of the APK; if it did
# not, the script exits 1 even when the two APKs are identical.
#
# Environment: ANDROID_HOME (default: $HOME/Android/sdk), KEEP=1 keeps the work directory
# (both clones and their APKs) instead of removing it, TMPDIR picks where it is made.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REF="${1:-HEAD}"
COMMIT="$(git -C "$ROOT" rev-parse --verify --quiet "$REF^{commit}")" \
  || { echo "not a commit: $REF" >&2; exit 2; }
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/sdk}}"
[ -d "$ANDROID_HOME" ] || { echo "no Android SDK at $ANDROID_HOME - set ANDROID_HOME" >&2; exit 2; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/amber-repro.XXXXXX")"
if [ "${KEEP:-}" = 1 ]; then
  trap 'echo "kept: $WORK"' EXIT
else
  trap 'rm -rf "$WORK"' EXIT
fi
# Different depths and lengths: a path that leaks into the APK makes the two differ.
DIRS=("$WORK/a" "$WORK/second-clone/at-a-longer-path/b")
APK=app/build/outputs/apk/release/app-release-unsigned.apk

# The repository objects behind each submodule checked out here (the worktree's own modules
# first, then the main repository's), so the clones need no network for them.
local_module() {
  local name="$1" path="$2" sha="$3" dir
  for dir in "$ROOT/$path" \
             "$(git -C "$ROOT" rev-parse --absolute-git-dir)/modules/$name" \
             "$(cd "$ROOT" && cd "$(git rev-parse --git-common-dir)" && pwd)/modules/$name"; do
    if [ -e "$dir" ] && git -C "$dir" cat-file -e "$sha^{commit}" 2>/dev/null; then
      echo "$dir"
      return 0
    fi
  done
  return 1
}

clone() {
  local dest="$1" name path sha src
  mkdir -p "$(dirname "$dest")"
  git clone -q --no-checkout "$ROOT" "$dest"
  git -C "$dest" checkout -q --detach "$COMMIT"
  git -C "$dest" submodule init -q
  while read -r key path; do
    name="${key#submodule.}"
    name="${name%.path}"
    sha="$(git -C "$dest" ls-tree "$COMMIT" -- "$path" | awk '{print $3}')"
    if [ -n "$sha" ] && src="$(local_module "$name" "$path" "$sha")"; then
      git -C "$dest" config "submodule.$name.url" "$src"
    else
      echo "    $path: not here at ${sha:-?}, fetching it from upstream"
    fi
  done < <(git -C "$dest" config -f .gitmodules --get-regexp '^submodule\..*\.path$')
  # A local path is the "file" protocol, which git refuses for submodules unless allowed.
  git -C "$dest" -c protocol.file.allow=always submodule update -q --recursive --no-recommend-shallow
}

echo "==> commit $COMMIT"
echo "==> $(java -version 2>&1 | head -1)"
for d in "${DIRS[@]}"; do
  echo "==> cloning into $d"
  clone "$d"
  echo "==> Tinfoil AAR in $d"
  # Its own Go build cache, so the second build compiles everything again instead of taking
  # the first one's objects (which would hide a difference between them).
  (cd "$d" && GOCACHE="$d.gocache" tools/build-tinfoil-aar.sh >/dev/null)
  echo "==> :app:assembleRelease in $d"
  (cd "$d" && ./gradlew -q --no-daemon --no-build-cache :app:assembleRelease)
  [ -f "$d/$APK" ] || { echo "no unsigned release APK in $d - is a keystore.properties signing it?" >&2; exit 1; }
  sha256sum "$d/$APK"
done

A="${DIRS[0]}/$APK"
B="${DIRS[1]}/$APK"

# The verdict first, so a failing check below cannot hide it.
if cmp -s "$A" "$B"; then
  echo "reproducible: $(sha256sum "$A" | cut -d' ' -f1)"
  SAME=1
else
  SAME=0
fi

# The text-to-speech part of sherpa-onnx stays out (espeak-ng, piper): no entry named after it,
# and nothing of it in sherpa-onnx's library or the app's code. Not "espeak" after a "w":
# sherpa-onnx's speaker-embedding code says "wespeaker", which is not TTS (a "libespeak" is
# still caught). Case-sensitive: "PipeReader" is not piper. (Go's string table in libgojni.so
# runs "pipe" into "reflect", so that library's content is not searched.)
TTS_OK=1
python3 -I - "$A" <<'PY' || TTS_OK=0
import re, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
pattern = re.compile(rb"(?<!w)espeak|piper")
named = [i.filename for i in z.infolist() if re.search(r"espeak|piper", i.filename)]
if named:
    sys.exit("text-to-speech files in the APK (espeak/piper): " + ", ".join(named))
searched = [i for i in z.infolist() if re.fullmatch(r"lib/[^/]+/libsherpa[^/]*\.so|classes\d*\.dex", i.filename)]
if not any(i.filename.endswith(".so") for i in searched):
    sys.exit("no sherpa-onnx library in the APK to check for text-to-speech code")
hits = [i.filename for i in searched if pattern.search(z.read(i))]
if hits:
    sys.exit("text-to-speech code in the APK (espeak/piper): " + ", ".join(hits))
PY

if [ "$SAME" = 1 ]; then
  [ "$TTS_OK" = 1 ] || exit 1
  exit 0
fi

echo "NOT reproducible: the two unsigned APKs differ" >&2
if command -v diffoscope >/dev/null; then
  diffoscope "$A" "$B" >&2 || true
else
  python3 -I - "$A" "$B" >&2 <<'PY' || true
import hashlib, sys, zipfile
za, zb = zipfile.ZipFile(sys.argv[1]), zipfile.ZipFile(sys.argv[2])
ia, ib = za.infolist(), zb.infolist()
na, nb = [i.filename for i in ia], [i.filename for i in ib]
for n in sorted(set(na) - set(nb)):
    print(f"only in the first:  {n}")
for n in sorted(set(nb) - set(na)):
    print(f"only in the second: {n}")
if [n for n in na if n in nb] != [n for n in nb if n in na]:
    print("entry order differs")
mb = {i.filename: i for i in ib}
for a in ia:
    b = mb.get(a.filename)
    if b is None:
        continue
    da, db = za.read(a), zb.read(b)
    if da != db:
        print(f"content differs:    {a.filename}  {hashlib.sha256(da).hexdigest()} != {hashlib.sha256(db).hexdigest()}")
    # Sizes and offsets follow from content; they only say something when the content is equal.
    keys = ("date_time", "compress_type", "external_attr", "extra", "flag_bits") + (("compress_size",) if da == db else ())
    meta = [k for k in keys if getattr(a, k) != getattr(b, k)]
    if meta:
        print(f"metadata differs:   {a.filename}  ({', '.join(meta)})")
if za.comment != zb.comment:
    print("archive comment differs")
PY
fi
exit 1
