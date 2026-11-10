#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/module-cache
xcrun swiftc -swift-version 5 -module-cache-path "$PWD/build/module-cache" Sources/Protocol.swift Tests/main.swift -o build/protocol-tests
build/protocol-tests
xcrun swiftc -swift-version 5 -parse-as-library -module-cache-path "$PWD/build/module-cache" Sources/Protocol.swift Sources/DeviceModel.swift Tests/ModelTests.swift -framework CoreBluetooth -framework AppKit -o build/model-tests
build/model-tests
