# iOS: read-only Core milestone

Builds for iOS are opt-in and separate from Android, desktop Native, Compose Desktop, and Web. The `:shared` library uses the existing Kotlin Core on both iPhone arm64 and Apple Silicon simulator arm64. The small `:iosBridge` module exports the `LivePhotoCore` framework without exposing the full Kotlin Core API to Swift.

## Build and verify on an Apple Silicon Mac

Install Xcode 26.4.1 with the iOS 26.4 simulator runtime, and set `JAVA_HOME` to JDK 25. CI pins this SDK family because it matches [Kotlin 2.4.21's platform libraries](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html); Xcode 16.4 can build the Kotlin archive but did not link a Swift consumer. [Kotlin/Native's targets](https://kotlinlang.org/docs/native-target-support.html) and [Apple's Xcode tools](https://developer.apple.com/documentation/xcode/xcode-command-line-tool-reference) describe the platform requirements.

```sh
# Builds both XCFramework slices, runs Core/bridge simulator tests, and
# type-checks Swift against both slices, then links and runs a Swift simulator smoke.
bash scripts/verify-ios.sh

# Choose a particular already-created iPhone simulator (see xcrun simctl list).
IOS_SIMULATOR_UDID=<uuid> bash scripts/verify-ios.sh

# Only assemble the static framework (device + Apple Silicon simulator).
./gradlew -Plivephoto.ios=true :iosBridge:assembleLivePhotoCoreDebugXCFramework
```

The static framework is written to `iosBridge/build/XCFrameworks/debug/LivePhotoCore.xcframework`. The verifier also writes `ci-output/ios-evidence.json`, the Gradle log, the linked Swift simulator smoke log, and test XML under each module's `build/test-results/iosSimulatorArm64Test/`. It type-checks the Swift sample for both frameworks and links the simulator framework into a standalone Swift command-line smoke executable. The iPhone device slice is built but does not run on physical hardware. Linux can configure the opt-in Gradle graph, but cannot verify the iOS build or run the simulator.

## Swift entry points

Add the static XCFramework to your Xcode target and link it without embedding it. Use iOS 15.0 or later. The sample assumes the caller already obtained the original bytes, and belongs on a background worker:

```swift
import Foundation
import LivePhotoCore

let inspector = LivePhotoInspector()
let singleReport = inspector.inspect(data: originalImageData)
let pairReport = inspector.inspectPair(image: originalImageData, video: originalMovieData)
```

The combined input limit is 32 MiB. A result can be successful but not `Live`; for example a single Apple carrier is only a candidate. The result includes the Core disposition, protocol/profile when known, pairing status, and issue codes, or a structured failure. Input bytes are read-only and copied into bounded in-memory storage. Do not mutate the input while calling the bridge.

The Kotlin simulator regression fixtures test ordinary JPEG, a synthetic Apple JPEG/MOV pair, mismatched pairing, empty input, and the input limit. The linked Swift smoke independently exercises ordinary JPEG, a single Apple still, a matched pair, and a mismatched pair through the public bridge. These tests are not evidence for importing into Photos or handling every capture from Apple hardware. This milestone contains no SwiftUI app, Photos import/export, security-scoped file picker, AVFoundation media backend, file writes, or App Store packaging. Those require their own implementation and runtime checks.
