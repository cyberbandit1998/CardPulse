#!/usr/bin/env bash
# Sets up the fixed signing key for a build, if one was provided (see the README, "Updating in place").
#
# Reads KEYSTORE_BASE64, KEYSTORE_PASSWORD and KEY_ALIAS from the environment. Exports the CARDPULSE_* settings
# that app/build.gradle.kts reads through $GITHUB_ENV, and records whether a key was used in $GITHUB_OUTPUT.
set -euo pipefail

if [ -z "${KEYSTORE_BASE64:-}" ]; then
  echo "fixed_key=false" >> "$GITHUB_OUTPUT"
  echo "No signing key is set up, so this build gets a throwaway debug key (the README explains how to set one up)."
  exit 0
fi

: "${KEYSTORE_PASSWORD:?The CARDPULSE_KEYSTORE_PASSWORD secret is missing}"
: "${KEY_ALIAS:?The CARDPULSE_KEY_ALIAS secret is missing}"

keystore="${RUNNER_TEMP:-/tmp}/cardpulse.jks"
printf '%s' "$KEYSTORE_BASE64" | base64 -d > "$keystore"

# Fail now, with the reason, rather than quietly producing a build signed with a different key.
# (keytool writes its errors to stdout, so both streams are captured.)
if ! check=$(keytool -list -keystore "$keystore" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" 2>&1); then
  echo "The signing key could not be opened, so the build was stopped instead of using a different key:" >&2
  echo "$check" >&2
  exit 1
fi

{
  echo "CARDPULSE_KEYSTORE_PATH=$keystore"
  echo "CARDPULSE_KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD"
  echo "CARDPULSE_KEY_ALIAS=$KEY_ALIAS"
} >> "$GITHUB_ENV"
echo "fixed_key=true" >> "$GITHUB_OUTPUT"
echo "Using the fixed signing key."
