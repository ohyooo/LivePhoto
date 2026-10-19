package livephoto.core.compatibility

import livephoto.core.Bytes
import livephoto.core.binary.Sha256

/** Existing checked-in encoded samples. CI expands Base64 only for the Native test host. */
internal expect fun encodedFixtureData(name: String): ByteArray

internal fun encodedFixtureResource(name: String): String = when (name) {
    "remux-main" -> "windows-media/remux-main.mp4.base64"
    "frame-high-mov" -> "windows-media/frame-high.mov.base64"
    "ordinary-text-mov" -> "apple-media/ordinary-text.mov.base64"
    "ordinary-mdta-mp4" -> "apple-media/ordinary-mdta.mp4.base64"
    else -> error("Unknown encoded media fixture")
}

internal fun encodedMediaFixture(name: String): ByteArray {
    val expected = when (name) {
        "remux-main" -> "be48fea2d56bb357d2f7370f3efc9462171d4d47d61451d35b3a8c9d78cc56e6"
        "frame-high-mov" -> "5929ddf215c236013abb1dff65c2c3537a878fe4c2ad40fa0af7e46ada2efe19"
        "ordinary-text-mov" -> "9d2be28350c0c807ac7164c80968ef75ce875fb8d6da04b2a3d89165c82aa6a4"
        "ordinary-mdta-mp4" -> "0d32b1e5e477152a1b31e4e57ccd3ffc0bc1be725d0dc4a1f339b6b3d2956e2c"
        else -> error("Unknown encoded media fixture")
    }
    return encodedFixtureData(name).also { bytes ->
        check(Sha256().apply { update(Bytes(bytes)) }.finish().value == expected) { "Encoded media fixture differs" }
    }
}
