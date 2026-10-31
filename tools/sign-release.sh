#!/usr/bin/env bash
# Builds Amber's release APK and has it signed with the release key on a signing host: the
# unsigned APK goes there over ssh, is aligned and signed with apksigner, comes back and is
# checked against the release key's fingerprint. The key and its passwords never leave that
# host.
#
# Settings come from the environment or from tools/sign-release.local (untracked; KEY=value
# lines, # for comments; an exported variable wins over the file):
#   SIGN_HOST         ssh target of the signing host, e.g. user@host (required)
#   SIGN_PROPERTIES   keystore.properties on that host with storeFile, storePassword,
#                     keyAlias and keyPassword (required)
#   SIGN_DIR          scratch directory on that host (default: amber/sign)
#   SIGN_BUILD_TOOLS  Android build-tools directory on that host (default: Android/Sdk/build-tools)
#   SIGN_CERT_SHA256  expected signer fingerprint (default: Amber's release key, see README.md)
# Paths on the signing host are absolute or relative to its home directory (~/ allowed).
#
# Output: amber-release.apk in the project root (gitignored, *.apk).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL="$ROOT/tools/sign-release.local"
SETTINGS="SIGN_HOST SIGN_PROPERTIES SIGN_DIR SIGN_BUILD_TOOLS SIGN_CERT_SHA256"

# Read line by line, never sourced: a "$(" or a backtick in a value stays a character.
if [ -f "$LOCAL" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%$'\r'}
    case "$line" in ''|'#'*) continue ;; esac
    k=${line%%=*}
    v=${line#*=}
    [ "$k" != "$line" ] || k=""  # no "=" at all: not a setting
    case "$v" in \"*\"|\'*\') v=${v:1:${#v}-2} ;; esac
    case " $SETTINGS " in
      *" $k "*) [ -n "${!k:-}" ] || printf -v "$k" '%s' "$v" ;;
      *) echo "$LOCAL: unknown line '$line' (known: $SETTINGS)" >&2; exit 1 ;;
    esac
  done < "$LOCAL"
fi

missing=""
[ -n "${SIGN_HOST:-}" ] || missing="$missing SIGN_HOST"
[ -n "${SIGN_PROPERTIES:-}" ] || missing="$missing SIGN_PROPERTIES"
if [ -n "$missing" ]; then
  echo "not set:$missing - export it or put it in tools/sign-release.local (see the top of tools/sign-release.sh)" >&2
  exit 1
fi
SIGN_DIR="${SIGN_DIR:-amber/sign}"
SIGN_BUILD_TOOLS="${SIGN_BUILD_TOOLS:-Android/Sdk/build-tools}"
SIGN_CERT_SHA256="${SIGN_CERT_SHA256:-93c826f0c11a444f1c051a82f5cb2b4ec3081e0b43047926afae8c35cdb1bbde}"
# ssh and scp start in the remote home directory, so "~/x" and "x" are the same path there.
SIGN_PROPERTIES=${SIGN_PROPERTIES#\~/}
SIGN_DIR=${SIGN_DIR#\~/}
SIGN_BUILD_TOOLS=${SIGN_BUILD_TOOLS#\~/}

# Quotes a value for the remote shell: 'it'\''s'.
sq() { printf "'%s'" "${1//\'/\'\\\'\'}"; }

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | tail -1)
UNSIGNED="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
DEST="$ROOT/amber-release.apk"

echo "==> building the release APK"
rm -f "$UNSIGNED"
# AMBER_GRADLE_ARGS: extra Gradle arguments, e.g. -Pamber.insertTestHook=true for a test build.
(cd "$ROOT" && ./gradlew -q :app:assembleRelease ${AMBER_GRADLE_ARGS:-})
[ -f "$UNSIGNED" ] || { echo "no unsigned release APK - is a local keystore.properties signing it already?" >&2; exit 1; }

echo "==> signing on $SIGN_HOST"
ssh -o BatchMode=yes "$SIGN_HOST" "mkdir -p $(sq "$SIGN_DIR")"
scp -o BatchMode=yes -q "$UNSIGNED" "$SIGN_HOST:$SIGN_DIR/unsigned.apk"
# Passwords go through the environment, never the command line (ps). The properties file is
# read line by line, never evaluated: a "$(" or a backtick in a password stays a character.
ssh -o BatchMode=yes "$SIGN_HOST" "set -e
  P=$(sq "$SIGN_PROPERTIES") D=$(sq "$SIGN_DIR") B=$(sq "$SIGN_BUILD_TOOLS")"'
  while IFS="=" read -r k v || [ -n "$k" ]; do
    case "$k" in storeFile|storePassword|keyAlias|keyPassword) export "RELEASE_$k=${v%$(printf "\r")}" ;; esac
  done < "$P"
  BT=$(cd "$B" && ls -d "$PWD"/* | tail -1)
  cd "$D"
  "$BT/zipalign" -f -p 4 unsigned.apk aligned.apk
  "$BT/apksigner" sign --ks "$RELEASE_storeFile" --ks-key-alias "$RELEASE_keyAlias" \
    --ks-pass env:RELEASE_storePassword --key-pass env:RELEASE_keyPassword \
    --out signed.apk aligned.apk
  rm -f unsigned.apk aligned.apk'
scp -o BatchMode=yes -q "$SIGN_HOST:$SIGN_DIR/signed.apk" "$DEST"
ssh -o BatchMode=yes "$SIGN_HOST" "cd $(sq "$SIGN_DIR") && rm -f signed.apk signed.apk.idsig"

# Exactly one signer, and it is the release key.
GOT=$("$BT/apksigner" verify --print-certs "$DEST" | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p')
if [ "$GOT" != "$SIGN_CERT_SHA256" ]; then
  echo "wrong signature: $GOT (expected exactly $SIGN_CERT_SHA256)" >&2
  rm -f "$DEST"
  exit 1
fi

# And it is the APK that was sent: every entry byte for byte, nothing added but v1 signature
# files. The signing host can sign; it must not be able to change what is signed.
python3 -I - "$UNSIGNED" "$DEST" <<'PY' || { rm -f "$DEST"; exit 1; }
import re, sys, zipfile
sent, back = zipfile.ZipFile(sys.argv[1]), zipfile.ZipFile(sys.argv[2])
a, b = {i.filename: i for i in sent.infolist()}, {i.filename: i for i in back.infolist()}
extra = [n for n in b if n not in a and not re.fullmatch(r"META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|EC|DSA))", n)]
missing = [n for n in a if n not in b]
changed = [n for n in a if n in b and sent.read(n) != back.read(n)]
if extra or missing or changed:
    sys.exit(f"signed APK differs from the one sent: extra {extra[:5]}, missing {missing[:5]}, changed {changed[:5]}")
PY
echo "==> signed with the release key: $DEST ($(du -h "$DEST" | cut -f1))"
