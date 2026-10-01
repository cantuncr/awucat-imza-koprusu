#!/bin/sh
# Builds dist/awucat-signer.jar with nothing but a JDK (11 or newer to build; the jar runs on
# Java 8+ because we compile with --release 8 and reach Java 9+ APIs through reflection).
#
#   sh build.sh            → dist/awucat-signer.jar
#   JAVA_HOME=/path sh build.sh   → use a specific JDK
set -eu
cd "$(dirname "$0")"

BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
OUT=build/classes
rm -rf build dist
mkdir -p "$OUT" dist

# shellcheck disable=SC2046
"${BIN}javac" --release 8 -encoding UTF-8 -Xlint:all,-options -d "$OUT" \
  $(find src/main/java -name '*.java' | sort)

# Add-Opens lets the optional "remaining PIN tries" lookup read SunPKCS11 internals on Java 9+
# when the jar is started with `java -jar`; everything else works without it.
cat > build/MANIFEST.MF <<'EOF'
Manifest-Version: 1.0
Main-Class: app.awucat.signer.Main
Implementation-Title: AwuCat İmza Köprüsü
Implementation-Version: 1.0.0
Add-Opens: jdk.crypto.cryptoki/sun.security.pkcs11 jdk.crypto.cryptoki/sun.security.pkcs11.wrapper
EOF

"${BIN}jar" --create --file dist/awucat-signer.jar --manifest build/MANIFEST.MF -C "$OUT" .
echo "OK: dist/awucat-signer.jar ($(wc -c < dist/awucat-signer.jar | tr -d ' ') bytes)"
