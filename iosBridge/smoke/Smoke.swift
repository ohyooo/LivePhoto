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
