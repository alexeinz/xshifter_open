#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/module-cache
SDK_PATH=$(xcrun --sdk macosx --show-sdk-path)
APP_PATH="$PWD/build/XShifter Open.app"
mkdir -p "$APP_PATH/Contents/MacOS" "$APP_PATH/Contents/Resources"
# Build both architectures so a single release supports Intel and Apple Silicon.
for APP_ARCH in arm64 x86_64; do
  xcrun swiftc -swift-version 5 -O -parse-as-library -target "$APP_ARCH-apple-macosx13.0" -sdk "$SDK_PATH" -module-cache-path "$PWD/build/module-cache" Sources/*.swift -framework SwiftUI -framework CoreBluetooth -framework AppKit -o "build/XShifterOpen-$APP_ARCH"
done
xcrun lipo -create build/XShifterOpen-arm64 build/XShifterOpen-x86_64 -output "$APP_PATH/Contents/MacOS/XShifterOpen"
cp Info.plist "$APP_PATH/Contents/Info.plist"
APP_VERSION=$(sed -n 's/^versionName=//p' ../version.properties)
APP_BUILD=$(sed -n 's/^versionCode=//p' ../version.properties)
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $APP_VERSION" "$APP_PATH/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $APP_BUILD" "$APP_PATH/Contents/Info.plist"
codesign --force --sign - "$APP_PATH"
codesign --verify --strict "$APP_PATH"
printf '%s\n' "$APP_PATH"
