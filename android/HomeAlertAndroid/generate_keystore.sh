#!/usr/bin/env bash
set -euo pipefail

KEYSTORE_FILE="release-keystore.jks"
ALIAS="homealert_release"

if [ -f "$KEYSTORE_FILE" ]; then
  echo "$KEYSTORE_FILE already exists."
  exit 0
fi

read -rsp "Enter keystore password (min 6 chars): " PASS
echo
keytool -genkeypair -v \
  -keystore "$KEYSTORE_FILE" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "$PASS" \
  -keypass "$PASS" \
  -dname "CN=HomeAlertAndroid, OU=Security, O=HomeAlert, L=Local, ST=Local, C=US"

cat > keystore.properties <<EOF
storeFile=$KEYSTORE_FILE
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOF

echo "Generated $KEYSTORE_FILE and keystore.properties."
echo "Now run: ./gradlew assembleRelease"
