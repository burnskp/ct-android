#!/usr/bin/env bash
set -euo pipefail

for name in ANDROID_KEYSTORE_PATH ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
    if [[ -z "${!name:-}" ]]; then
        echo "Missing release signing setting: $name" >&2
        exit 1
    fi
done
[[ -f "$ANDROID_KEYSTORE_PATH" ]] || { echo "Release keystore does not exist." >&2; exit 1; }
[[ "$ANDROID_KEY_ALIAS" != androiddebugkey ]] || { echo "Debug signing keys cannot be used for public releases." >&2; exit 1; }
if [[ "${1:-}" == --check ]]; then exit 0; fi
if [[ $# != 2 ]]; then
    echo "Usage: bash scripts/sign-release.sh INPUT_UNSIGNED_APK OUTPUT_APK" >&2
    exit 1
fi

sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
build_tools="$sdk_root/build-tools/36.0.0"
for tool in zipalign apksigner; do
    [[ -x "$build_tools/$tool" ]] || { echo "Set ANDROID_HOME to an SDK with Build Tools 36.0.0." >&2; exit 1; }
done
[[ -f "$1" ]] || { echo "Unsigned APK does not exist." >&2; exit 1; }
umask 077
signing_tmp="$(mktemp -d)"
trap 'rm -rf "$signing_tmp"' EXIT
"$build_tools/zipalign" -f -p 4 "$1" "$signing_tmp/aligned.apk"
"$build_tools/apksigner" sign \
    --ks "$ANDROID_KEYSTORE_PATH" --ks-key-alias "$ANDROID_KEY_ALIAS" \
    --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD \
    --out "$signing_tmp/signed.apk" "$signing_tmp/aligned.apk"
"$build_tools/apksigner" verify --verbose --print-certs "$signing_tmp/signed.apk" > "$signing_tmp/verification.txt"
if grep -qi 'CN=Android Debug' "$signing_tmp/verification.txt"; then
    echo "Debug signing certificates cannot be used for public releases." >&2
    exit 1
fi
mkdir -p "$(dirname "$2")"
cp "$signing_tmp/signed.apk" "$2"
echo "Verified release APK: $2"
