import Darwin
import Foundation
import LivePhotoCore

private struct CheckFailure: Error, CustomStringConvertible {
    let description: String
}

private func expect(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    guard condition() else { throw CheckFailure(description: message) }
}

private func runSmoke() throws {
    guard let ordinaryImage = Data(base64Encoded: SimulatorFixtures.ordinaryJPEG),
          let appleImage = Data(base64Encoded: SimulatorFixtures.appleJPEG),
          let movie = Data(base64Encoded: SimulatorFixtures.appleMovie) else {
        throw CheckFailure(description: "Invalid synthetic fixture")
    }

    let inspector = LivePhotoInspector()
    try expect(inspector.inputLimitBytes == 32 * 1024 * 1024, "Unexpected input budget")

    let ordinary = inspector.inspect(data: ordinaryImage)
    try expect(ordinary.successful && ordinary.disposition == "NonLive" &&
               ordinary.protocolId == nil && ordinary.errorCode == nil, "Ordinary JPEG should not be live")
    print("SWIFT_IOS_CASE=ordinary-jpeg:PASS")

    let single = inspector.inspect(data: appleImage)
    try expect(single.successful && single.disposition == "Candidate" &&
               single.issueCodes.contains("PAIR_ASSET_MISSING"), "Apple still alone should be a candidate")
    print("SWIFT_IOS_CASE=apple-still-only:PASS")

    let pair = inspector.inspectPair(image: appleImage, video: movie)
    try expect(pair.successful && pair.disposition == "Live" &&
               pair.protocolId == "apple.livephoto" && pair.errorCode == nil &&
               pair.pairingMatches?.boolValue == true, "Matching Apple pair should be live")
    print("SWIFT_IOS_CASE=apple-matched-pair:PASS")

    var mismatched = movie
    let identifier = Data("00112233-4455-6677-8899-aabbccddeeff".utf8)
    guard let idRange = mismatched.range(of: identifier),
          mismatched.range(of: identifier, in: idRange.upperBound..<mismatched.endIndex) == nil else {
        throw CheckFailure(description: "Expected exactly one synthetic content identifier")
    }
    mismatched[idRange.lowerBound] = UInt8(ascii: "1")
    let differentPair = inspector.inspectPair(image: appleImage, video: mismatched)
    try expect(!differentPair.successful && differentPair.disposition == nil &&
               differentPair.errorCode == "INVALID_PAIR_IDENTIFIER", "Mismatched pair should fail explicitly")
    print("SWIFT_IOS_CASE=apple-mismatched-pair:PASS")
}

do {
    try runSmoke()
    print("SWIFT_IOS_SMOKE=SUCCESS")
} catch {
    FileHandle.standardError.write(Data("Swift iOS smoke failed: \(error)\n".utf8))
    exit(EXIT_FAILURE)
}
