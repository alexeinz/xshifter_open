#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
APP_PATH="${1:-$PWD/build/XShifter Open.app}"
OUTPUT_PATH="${2:-$PWD/../dist}"
APP_VERSION=$(sed -n 's/^versionName=//p' ../version.properties)
mkdir -p "$OUTPUT_PATH"
OUTPUT_PATH=$(cd "$OUTPUT_PATH" && pwd)
codesign --verify --strict "$APP_PATH"
ditto -c -k --keepParent "$APP_PATH" "$OUTPUT_PATH/XShifter-Open-$APP_VERSION-macOS-universal.zip"
STAGE_PATH=$(mktemp -d "${TMPDIR:-/tmp}/xshifter-dmg.XXXXXX")
trap 'rm -rf "$STAGE_PATH"' EXIT
ditto "$APP_PATH" "$STAGE_PATH/XShifter Open.app"
ln -s /Applications "$STAGE_PATH/Applications"
hdiutil create -volname "XShifter Open" -srcfolder "$STAGE_PATH" -format UDZO -ov "$OUTPUT_PATH/XShifter-Open-$APP_VERSION-macOS-universal.dmg"
hdiutil verify "$OUTPUT_PATH/XShifter-Open-$APP_VERSION-macOS-universal.dmg"
