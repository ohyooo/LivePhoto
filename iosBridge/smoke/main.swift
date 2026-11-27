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

    try runFileSmoke(ordinaryImage: ordinaryImage, image: appleImage, movie: movie, mismatched: mismatched)
}

private func runFileSmoke(ordinaryImage: Data, image: Data, movie: Data, mismatched: Data) throws {
    let files = FileManager.default
    let directory = files.temporaryDirectory.appendingPathComponent("livephoto-swift-\(UUID().uuidString)", isDirectory: true)
    try files.createDirectory(at: directory, withIntermediateDirectories: false)
    defer { try? files.removeItem(at: directory) }
    let ordinaryURL = directory.appendingPathComponent("普通照片")
    let imageURL = directory.appendingPathComponent("still.txt")
    let motionURL = directory.appendingPathComponent("motion.bin")
    let mismatchURL = directory.appendingPathComponent("mismatch.mov")
    try ordinaryImage.write(to: ordinaryURL)
    try image.write(to: imageURL)
    try movie.write(to: motionURL)
    try mismatched.write(to: mismatchURL)
    let inspector = LivePhotoInspector()

    let ordinary = inspector.inspectFile(file: ordinaryURL)
    let still = inspector.inspectFile(file: imageURL)
    try expect(ordinary.successful && ordinary.disposition == "NonLive" &&
               still.disposition == "Candidate", "File URL should inspect without relying on names")
    print("SWIFT_IOS_CASE=file-single:PASS")

    let pair = inspector.inspectFilePair(image: imageURL, video: motionURL)
    let differentPair = inspector.inspectFilePair(image: imageURL, video: mismatchURL)
    let verifiedImage = try Data(contentsOf: imageURL)
    let verifiedMovie = try Data(contentsOf: motionURL)
    let verifiedMismatch = try Data(contentsOf: mismatchURL)
    try expect(pair.successful && pair.disposition == "Live" && pair.protocolId == "apple.livephoto" &&
               !differentPair.successful && differentPair.errorCode == "INVALID_PAIR_IDENTIFIER" &&
               verifiedImage == image && verifiedMovie == movie && verifiedMismatch == mismatched,
               "The file pair result and original bytes must match their expected state")
    print("SWIFT_IOS_CASE=file-pair:PASS")

    let invalid = inspector.inspectFile(file: directory)
    let missing = inspector.inspectFile(file: directory.appendingPathComponent("missing"))
    try expect(invalid.errorCode == "INVALID_ARGUMENT" && missing.errorCode == "IO_READ_FAILED",
               "Directories and missing files must not be inspected")
    print("SWIFT_IOS_CASE=file-rejected:PASS")
}

do {
    try runSmoke()
    print("SWIFT_IOS_SMOKE=SUCCESS")
} catch {
    FileHandle.standardError.write(Data("Swift iOS smoke failed: \(error)\n".utf8))
    exit(EXIT_FAILURE)
}
