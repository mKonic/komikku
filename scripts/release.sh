#!/usr/bin/env bash
#
# Builds, signs and publishes a release from this machine.
#
# CI does not build releases any more: a GitHub runner takes ~15 minutes on this project where a
# workstation takes ~3. See .github/workflows/release.yml for how to turn that back on.
#
# Usage:
#   scripts/release.sh v1.2.0          # build, sign, verify, and create the GitHub release
#   scripts/release.sh v1.2.0 --dry    # build, sign and verify, but publish nothing
#
# Expects the signing keystore at $KEYSTORE (default below) and its passwords in the environment:
#   KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
# If they are unset you are prompted, so nothing lands in shell history.

set -euo pipefail

TAG="${1:-}"
DRY="${2:-}"
REPO="mKonic/komikku"
KEYSTORE="${KEYSTORE:-$HOME/dev/android/keys/my-komikku-release-key.keystore}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_HOME

if [ -z "$TAG" ]; then
    echo "usage: scripts/release.sh <tag> [--dry]" >&2
    exit 2
fi

cd "$(dirname "$0")/.."

# --- preflight -------------------------------------------------------------

[ -f "$KEYSTORE" ] || { echo "No keystore at $KEYSTORE" >&2; exit 1; }

if [ -n "$(git status --porcelain)" ]; then
    echo "Working tree is dirty. Commit or stash first - the version name comes from git." >&2
    exit 1
fi

if ! git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "Tag $TAG does not exist. Create it first, so the build stamps the right version." >&2
    exit 1
fi

if [ "$(git rev-parse HEAD)" != "$(git rev-parse "$TAG^{commit}")" ]; then
    echo "HEAD is not $TAG. Check it out first, or the APK will be stamped from the wrong commit." >&2
    exit 1
fi

BUILD_TOOLS="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)"
APKSIGNER="$BUILD_TOOLS/apksigner"
[ -x "$APKSIGNER" ] || { echo "No apksigner under $BUILD_TOOLS" >&2; exit 1; }

# The password comes from a KEY=... line in a .env kept beside the keystore, outside the repo.
# Override the location with KEYS_ENV, or set KEYSTORE_PASSWORD directly to skip the file.
ENV_FILE="${KEYS_ENV:-$(dirname "$KEYSTORE")/.env}"
if [ -z "${KEYSTORE_PASSWORD:-}" ] && [ -f "$ENV_FILE" ]; then
    KEYSTORE_PASSWORD="$(grep -E '^KEY=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '\r\n')"
fi
if [ -z "${KEYSTORE_PASSWORD:-}" ]; then
    read -rsp 'Keystore password: ' KEYSTORE_PASSWORD
    echo >&2
fi

# Read the alias out of the keystore rather than storing it: it is not a secret, and one fewer
# thing to keep in sync is one fewer way for a release to fail at the signing step.
if [ -z "${KEY_ALIAS:-}" ]; then
    KEY_ALIAS="$(keytool -list -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" 2>/dev/null |
        awk -F, '/PrivateKeyEntry/ { print $1; exit }')"
fi
[ -n "$KEY_ALIAS" ] || { echo "Could not read a key alias from $KEYSTORE - wrong password?" >&2; exit 1; }
: "${KEY_PASSWORD:=$KEYSTORE_PASSWORD}"

echo "==> Signing as '$KEY_ALIAS'"

# --- verify then build -----------------------------------------------------

echo "==> Verifying"
./gradlew --max-workers=4 spotlessCheck testDebugUnitTest :app:lintDebug

echo "==> Building release APK"
./gradlew --max-workers=4 :app:assembleRelease -Penable-updater

UNSIGNED="$(ls app/build/outputs/apk/release/*-release-unsigned.apk | head -1)"
[ -f "$UNSIGNED" ] || { echo "No unsigned APK was produced" >&2; exit 1; }

ABI="$(basename "$UNSIGNED" | sed -E 's/^app-(.*)-release-unsigned\.apk$/\1/')"
mkdir -p dist
SIGNED="dist/Komikku-${ABI}-${TAG}.apk"

"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$KEYSTORE_PASSWORD" \
    --ks-key-alias "$KEY_ALIAS" \
    --key-pass "pass:$KEY_PASSWORD" \
    --out "$SIGNED" \
    "$UNSIGNED"

# --- verify the artifact, not just the build -------------------------------

echo "==> Verifying the signed APK"
"$APKSIGNER" verify --verbose "$SIGNED" | grep -E "^Verifies|^Verified using v[23]" || {
    echo "Signature did not verify" >&2
    exit 1
}

# Every bundled .so must be 16 KB aligned or the app will not load it on a 16 KB-page device,
# which is a launch crash rather than a degraded mode. Cheap to check, expensive to miss.
echo "==> Checking native library alignment"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -oq "$SIGNED" 'lib/*' -d "$TMP"
BAD=0
for so in "$TMP"/lib/*/*.so; do
    ALIGN="$(readelf -lW "$so" | awk '/LOAD/{print $NF}' | sort -u | tr -d '\n')"
    if [ "$ALIGN" != "0x4000" ]; then
        echo "   NOT 16 KB ALIGNED: $(basename "$so") ($ALIGN)" >&2
        BAD=1
    fi
done
[ "$BAD" -eq 0 ] || { echo "Refusing to publish: see above." >&2; exit 1; }
echo "   all $(ls "$TMP"/lib/*/*.so | wc -l) libraries are 16 KB aligned"

VERSION="$("$BUILD_TOOLS/aapt2" dump badging "$SIGNED" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"
CODE="$("$BUILD_TOOLS/aapt2" dump badging "$SIGNED" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")"
echo "==> $SIGNED  ($(du -h "$SIGNED" | cut -f1))  versionName=$VERSION versionCode=$CODE"

if [ "$DRY" = "--dry" ]; then
    echo "==> Dry run, nothing published."
    exit 0
fi

# Release notes come from CHANGELOG.md's section for this tag, so the release page reads like the
# changelog rather than a dump of commit subjects.
NOTES="$(mktemp)"
trap 'rm -rf "$TMP" "$NOTES"' EXIT
awk -v tag="$TAG" '
    $0 ~ "^## \\[" tag "\\]" { found = 1; next }
    found && /^## \[/ { exit }
    found { print }
' CHANGELOG.md | sed -e '/./,$!d' > "$NOTES"

echo "==> Publishing $TAG to $REPO"
if [ -s "$NOTES" ]; then
    gh release create "$TAG" "$SIGNED" \
        --repo "$REPO" \
        --title "Komikku $TAG" \
        --notes-file "$NOTES"
else
    echo "   No '## [$TAG]' section in CHANGELOG.md; falling back to generated notes." >&2
    gh release create "$TAG" "$SIGNED" \
        --repo "$REPO" \
        --title "Komikku $TAG" \
        --generate-notes
fi

echo "==> Done: $(gh release view "$TAG" --repo "$REPO" --json url --jq .url)"
