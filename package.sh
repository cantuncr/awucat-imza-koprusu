#!/bin/sh
# Builds a native installer with jpackage (JDK 17 or newer, with jmods). Run it on the target OS:
#   macOS   → dist/installer/AwuCat İmza Köprüsü-1.0.0.dmg   (contains "AwuCat İmza Köprüsü.app")
#   Windows → dist/installer/AwuCat İmza Köprüsü-1.0.0.msi   (run from Git Bash / MSYS)
#   Linux   → dist/installer/awucat-signer_1.0.0_amd64.deb
#
# Environment overrides:
#   TYPE=app-image|dmg|pkg|msi|exe|deb|rpm   installer type (default: dmg / msi / deb)
#   ICON=path/to/icon.icns|.ico|.png         application icon
#   MAC_SIGN_IDENTITY="Developer ID Application: …"   sign the macOS bundle (otherwise unsigned:
#                                            users must right-click → Aç on first launch)
set -eu
cd "$(dirname "$0")"
sh build.sh

BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
VERSION=1.0.0
APP_NAME="AwuCat İmza Köprüsü"
VENDOR="AwuCat"
DESCRIPTION="AwuCat için yerel e-imza köprüsü (akıllı kart / PKCS#11)"
INPUT=build/package-input
DEST=dist/installer
rm -rf "$INPUT" "$DEST"
mkdir -p "$INPUT" "$DEST"
cp dist/awucat-signer.jar "$INPUT/"

# A small bundled runtime: Swing, the built-in HTTP server and the SunPKCS11 provider.
MODULES=java.base,java.desktop,java.logging,jdk.httpserver,jdk.crypto.cryptoki
for optional in jdk.crypto.ec jdk.localedata; do
  if "${BIN}java" --list-modules | grep -q "^$optional@"; then MODULES="$MODULES,$optional"; fi
done

set -- \
  --input "$INPUT" --dest "$DEST" \
  --main-jar awucat-signer.jar --main-class app.awucat.signer.Main \
  --app-version "$VERSION" --vendor "$VENDOR" --description "$DESCRIPTION" --copyright "MIT License" \
  --add-modules "$MODULES" \
  --java-options "--add-opens=jdk.crypto.cryptoki/sun.security.pkcs11=ALL-UNNAMED" \
  --java-options "--add-opens=jdk.crypto.cryptoki/sun.security.pkcs11.wrapper=ALL-UNNAMED" \
  --java-options "-Xmx128m"
if [ -n "${ICON:-}" ]; then set -- "$@" --icon "$ICON"; fi

case "$(uname -s)" in
  Darwin)
    if [ -n "${MAC_SIGN_IDENTITY:-}" ]; then
      set -- "$@" --mac-sign --mac-signing-key-user-name "$MAC_SIGN_IDENTITY"
    fi
    "${BIN}jpackage" "$@" --type "${TYPE:-dmg}" --name "$APP_NAME" \
      --mac-package-identifier app.awucat.signer --mac-package-name "AwuCat İmza"
    ;;
  Linux)
    # Debian package names must be lowercase ASCII, so the Turkish name goes into the description.
    "${BIN}jpackage" "$@" --type "${TYPE:-deb}" --name awucat-signer \
      --linux-package-name awucat-signer --linux-shortcut --linux-menu-group Office \
      --linux-app-category utils
    ;;
  MINGW*|MSYS*|CYGWIN*|Windows_NT)
    # Fixed upgrade UUID lets a newer MSI replace an older installation in place.
    "${BIN}jpackage" "$@" --type "${TYPE:-msi}" --name "$APP_NAME" \
      --win-menu --win-shortcut --win-dir-chooser --win-per-user-install \
      --win-upgrade-uuid 6f2f0c3e-5e2b-4d3a-9d63-7f1f9d2a4b10
    ;;
  *)
    echo "Desteklenmeyen platform: $(uname -s)" >&2
    exit 1
    ;;
esac
ls -la "$DEST"
