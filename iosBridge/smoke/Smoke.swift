import Foundation
import LivePhotoCore

// Type-checked against the generated device and simulator framework headers.
func inspectSamples(image: Data, movie: Data) -> (String?, String?, [String]) {
    let inspector = LivePhotoInspector()
    let single = inspector.inspect(data: image)
    let pair = inspector.inspectPair(image: image, video: movie)
    precondition(inspector.inputLimitBytes > 0)
    return (single.disposition, pair.protocolId, pair.issueCodes)
}

// URLs must be app-local or already coordinated by the caller.
func inspectFileSamples(image: URL, movie: URL) -> (String?, String?) {
    let inspector = LivePhotoInspector()
    return (inspector.inspectFile(file: image).disposition,
            inspector.inspectFilePair(image: image, video: movie).protocolId)
}

// Run off the main thread. An external Files-provider URL needs both a security
// scope and a coordinated read; never retain the coordinator's callback URL.
func inspectPickedFile(_ selectedURL: URL) throws -> LivePhotoInspection {
    let acquired = selectedURL.startAccessingSecurityScopedResource()
    defer { if acquired { selectedURL.stopAccessingSecurityScopedResource() } }
    var failure: NSError?
    var report: LivePhotoInspection?
    let coordinator = NSFileCoordinator(filePresenter: nil)
    coordinator.coordinate(readingItemAt: selectedURL, options: [], error: &failure) { readableURL in
        report = LivePhotoInspector().inspectFile(file: readableURL)
    }
    if let failure { throw failure }
    guard let report else { throw CocoaError(.fileReadUnknown) }
    return report
}
