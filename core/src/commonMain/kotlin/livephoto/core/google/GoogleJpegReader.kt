package livephoto.core.google

import livephoto.core.*
import livephoto.core.binary.ParseBudget
import livephoto.core.jpeg.JpegStructure
import livephoto.core.xmp.XmpCollection

/** JPEG adapter; HEIF callers supply their own content-derived carrier layout. */
internal object GoogleJpegReader {
    fun read(xmp: XmpCollection, jpeg: JpegStructure, source: SourceIdentity, budget: ParseBudget): CoreResult<List<GoogleBinding>> =
        GoogleCarrierReader.read(xmp, jpeg.primary, "image/jpeg", source, budget)
}
