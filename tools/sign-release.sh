#!/usr/bin/env bash
# Builds Amber's release APK and has it signed on a signing host: the unsigned APK goes there
# over ssh, is signed with apksigner as it is (AGP has aligned it; aligning it again could move
# bytes, see below), comes back and is checked against the expected key's fingerprint. The key
# and its passwords never leave that host. Which key signs is set by SIGN_PROPERTIES;
# SIGN_CERT_SHA256 says which one that must be.
#
# Settings come from the environment or from tools/sign-release.local (untracked; KEY=value
# lines, # for comments; an exported variable wins over the file):
#   SIGN_HOST         ssh target of the signing host, e.g. user@host (required)
#   SIGN_PROPERTIES   keystore.properties on that host with storeFile, storePassword,
#                     keyAlias and keyPassword (required)
#   SIGN_DIR          scratch directory on that host (default: amber/sign)
#   SIGN_BUILD_TOOLS  Android build-tools directory on that host (default: Android/Sdk/build-tools)
#   SIGN_CERT_SHA256  expected signer fingerprint, SHA-256 of the certificate as 64 lowercase
#                     hex characters (required; the GitHub release key's is in README.md)
#   SIGN_OUTPUT       file name of the signed APK in the project root, ending in .apk
#                     (default: amber-release.apk)
# Paths on the signing host are absolute or relative to its home directory (~/ allowed). Its
# newest build-tools must be 35.0.0 or later (apksigner --alignment-preserved).
#
# A run with the GitHub release key sets its own properties, fingerprint and output, e.g.
#   SIGN_PROPERTIES=<its keystore.properties> SIGN_CERT_SHA256=<its fingerprint> \
#     SIGN_OUTPUT=amber-github.apk tools/sign-release.sh
#
# Output: $SIGN_OUTPUT in the project root (gitignored, *.apk).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL="$ROOT/tools/sign-release.local"
SETTINGS="SIGN_HOST SIGN_PROPERTIES SIGN_DIR SIGN_BUILD_TOOLS SIGN_CERT_SHA256 SIGN_OUTPUT"

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
[ -n "${SIGN_CERT_SHA256:-}" ] || missing="$missing SIGN_CERT_SHA256"
if [ -n "$missing" ]; then
  echo "not set:$missing - export it or put it in tools/sign-release.local (see the top of tools/sign-release.sh)" >&2
  exit 1
fi
# Compared with what apksigner prints, so it has to be spelled the same way.
if ! [[ "$SIGN_CERT_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "SIGN_CERT_SHA256 must be the certificate's SHA-256 as 64 lowercase hex characters" >&2
  exit 1
fi
SIGN_DIR="${SIGN_DIR:-amber/sign}"
SIGN_BUILD_TOOLS="${SIGN_BUILD_TOOLS:-Android/Sdk/build-tools}"
SIGN_OUTPUT="${SIGN_OUTPUT:-amber-release.apk}"
# A plain file name in the project root, and an .apk so .gitignore keeps it out of commits.
if ! [[ "$SIGN_OUTPUT" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$ ]]; then
  echo "SIGN_OUTPUT must be a file name ending in .apk, without a directory (got '$SIGN_OUTPUT')" >&2
  exit 1
fi
# ssh and scp start in the remote home directory, so "~/x" and "x" are the same path there.
SIGN_PROPERTIES=${SIGN_PROPERTIES#\~/}
SIGN_DIR=${SIGN_DIR#\~/}
SIGN_BUILD_TOOLS=${SIGN_BUILD_TOOLS#\~/}

# Quotes a value for the remote shell: 'it'\''s'.
sq() { printf "'%s'" "${1//\'/\'\\\'\'}"; }

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | tail -1)
UNSIGNED="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
DEST="$ROOT/$SIGN_OUTPUT"

echo "==> building the release APK"
rm -f "$UNSIGNED"
# AMBER_GRADLE_ARGS: extra Gradle arguments, e.g. -Pamber.insertTestHook=true for a test build.
(cd "$ROOT" && ./gradlew -q :app:assembleRelease ${AMBER_GRADLE_ARGS:-})
[ -f "$UNSIGNED" ] || { echo "no unsigned release APK - is a local keystore.properties signing it already?" >&2; exit 1; }
# Signed as built, never re-aligned: the signed APK must be the unsigned one plus a signature
# and nothing else, so that a rebuild from the same commit (F-Droid's) can be compared with it
# and carry over its signature (apksigcopier). AGP's output is aligned already; if it is not,
# something changed in the build and that has to be looked at, not papered over here.
if ! "$BT/zipalign" -c -p 4 "$UNSIGNED" >/dev/null; then
  echo "the unsigned APK is not aligned (zipalign -c -p 4 fails) - not signing it; see: $BT/zipalign -c -v -p 4 $UNSIGNED" >&2
  exit 1
fi

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
  "$BT/apksigner" sign --alignment-preserved true \
    --ks "$RELEASE_storeFile" --ks-key-alias "$RELEASE_keyAlias" \
    --ks-pass env:RELEASE_storePassword --key-pass env:RELEASE_keyPassword \
    --out signed.apk unsigned.apk
  rm -f unsigned.apk'
scp -o BatchMode=yes -q "$SIGN_HOST:$SIGN_DIR/signed.apk" "$DEST"
ssh -o BatchMode=yes "$SIGN_HOST" "cd $(sq "$SIGN_DIR") && rm -f signed.apk signed.apk.idsig"

# Exactly one signer, and it is the expected key.
GOT=$("$BT/apksigner" verify --print-certs "$DEST" | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p')
if [ "$GOT" != "$SIGN_CERT_SHA256" ]; then
  echo "wrong signature: $GOT (expected exactly $SIGN_CERT_SHA256)" >&2
  rm -f "$DEST"
  exit 1
fi

# And it is the APK that was sent: every entry byte for byte, nothing added but the signature.
# The signing host can sign; it must not be able to change what is signed.
python3 -I - "$UNSIGNED" "$DEST" <<'PY' || { rm -f "$DEST"; exit 1; }
import re, struct, sys, zipfile
sent, back = zipfile.ZipFile(sys.argv[1]), zipfile.ZipFile(sys.argv[2])
a, b = {i.filename: i for i in sent.infolist()}, {i.filename: i for i in back.infolist()}
extra = [n for n in b if n not in a and not re.fullmatch(r"META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|EC|DSA))", n)]
missing = [n for n in a if n not in b]
changed = [n for n in a if n in b and sent.read(n) != back.read(n)]
if extra or missing or changed:
    sys.exit(f"signed APK differs from the one sent: extra {extra[:5]}, missing {missing[:5]}, changed {changed[:5]}")
# Byte for byte, too, which is what `apksigcopier compare` needs to match a rebuild: the sent
# entries where they were (not re-padded), their directory records unchanged, then only the v1
# files and the APK Signing Block, and the same archive comment.
def layout(path):
    data = open(path, "rb").read()
    end = data.rfind(b"PK\x05\x06")
    size, start = struct.unpack_from("<II", data, end + 12)
    return data, start, data[start:start + size], data[end + 4:end + 8] + data[end + 20:]
u, u_cd, u_dir, u_end = layout(sys.argv[1])
s, s_cd, s_dir, s_end = layout(sys.argv[2])
if s[:u_cd] != u[:u_cd] or not s_dir.startswith(u_dir) or s_end != u_end \
        or not s[u_cd:s_cd].endswith(b"APK Sig Block 42"):
    sys.exit("signed APK is not the one sent plus a signature - its entries were moved or re-padded")
PY
echo "==> signed with the key $SIGN_CERT_SHA256: $DEST ($(du -h "$DEST" | cut -f1))"
