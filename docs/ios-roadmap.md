# iOS: read-only Core milestone

Builds for iOS are opt-in and separate from Android, desktop Native, Compose Desktop, and Web. The `:shared` library uses the existing Kotlin Core on both iPhone arm64 and Apple Silicon simulator arm64. The small `:iosBridge` module exports the `LivePhotoCore` framework without exposing the full Kotlin Core API to Swift.

The [iOS workflow for `4d7a8d3`](https://github.com/ohyooo/LivePhoto/actions/runs/45998747896) built both framework slices and ran 818 shared Core cases, 9 bridge cases, and 7 linked Swift smoke scenes on iOS 26.4 / Xcode 26.4.1. The Swift smoke covers regular files, explicit pairs, invalid paths, and unchanged original bytes; it does not cover a real Files-provider picker, Photos import, or a physical iPhone.

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

The combined input limit is 32 MiB. A result can be successful but not `Live`; for example a single Apple carrier is only a candidate. The result includes the Core disposition, protocol/profile when known, pairing status, and issue codes, or a structured failure. The Data API copies read-only input into bounded Kotlin memory. Do not mutate the input while calling the bridge.

### Read-only file URLs

For regular local files (including a caller-coordinated, readable Files URL), no full Data allocation is required:

```swift
let inspector = LivePhotoInspector()
let singleReport = inspector.inspectFile(file: localImageURL)
let pairReport = inspector.inspectFilePair(image: localImageURL, video: localMovieURL)
```

The adapter keeps the file handle open only during the call and uses positional reads of at most 64 KiB per adapter read. Core still uses memory for its own bounded parsing. The same combined 32 MiB budget applies. The files are never written and no paths or bookmarks are returned. A directory, alias represented as a filesystem symlink, FIFO, non-local URL, an empty file, and detected size/version changes are refused. This is not an atomic snapshot against a concurrent uncoordinated writer; the caller must prevent mutation while inspection is running.

Call from a worker. [Apple's Files-provider contract](https://developer.apple.com/documentation/uikit/uidocumentpickerviewcontroller) requires the caller to keep scoped access active and coordinate while reading external documents. See [the Swift smoke sample](../iosBridge/smoke/Smoke.swift) for a single coordinated read. For a pair coordinate both selected items together, using two read intents, and call `inspectFilePair` inside their accessor. Do not retain picker URLs after inspection, infer pairs from names, or use remote URLs.

The Kotlin simulator suite exercises the Data bridge, URL inspection, invalid inputs, the combined budget, short reads, mutation detection and cleanup. The linked Swift smoke exercises ordinary and Apple cases via Data, and local file URL single/pair inspection, read-only input integrity and refusal. These are not evidence for importing into Photos, Files-provider picker UI or handling every capture from Apple hardware. There is no SwiftUI app, Photos import/export, AVFoundation media backend, file writes, or App Store packaging. Those require their own implementation and runtime checks.
