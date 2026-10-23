package livephoto.core

import livephoto.core.binary.*

/** Independently hash borrowed input in bounded chunks; never trust a provider's claimed digest. */
public suspend fun sourceDigest(source: BinarySource, context: Context): CoreResult<Digest> = attempt {
    val reader = BinaryReader(source, context)
    val size = reader.identity().orThrow().size
    if (size > context.limits.maxSpoolBytes) fail("RESOURCE_LIMIT_EXCEEDED", "Digest input exceeds the input budget", Stage.Read)
    sha256Range(reader, ByteRange(0uL, size)).orThrow()
}
