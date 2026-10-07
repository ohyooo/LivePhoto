package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.heif.HeifCodedCarrier

/** Resolve content and bounds before handing a borrowed, isolated resource to a decoder. */
internal object ProbeOperations {
    suspend fun probe(request: ProbeRequest, backend: MediaBackend?): CoreResult<MediaFacts> = attempt {
        val budget = ParseBudget(request.context)
        val session = SourceSession.open(request.media.input, request.context, budget).orThrow()
        if (request.media.snapshot != null && request.media.snapshot != session.snapshot)
            fail("SOURCE_CHANGED", "Resource snapshot no longer matches input")
        val requested = request.media.resourceId
        val resource = if (requested != null) session.inspection.layout.resources.singleOrNull { it.id == requested }
            ?: fail("INVALID_ARGUMENT", "Resource ID is not part of this source inspection")
        else session.inspection.layout.resources.firstOrNull { it.kind == ResourceKind.PrimaryImage }
            ?: session.inspection.layout.resources.singleOrNull { it.kind == ResourceKind.Video }
        val reader: BinaryReader
        val range: ByteRange
        val facts: MediaFacts
        var heifCarrier = false
        if (resource != null) {
            val extent = resource.extents.singleOrNull()
                ?: fail("CAPABILITY_UNSUPPORTED", "Probe requires one complete media extent", Stage.Validate)
            reader = session.readerFor(extent.source)
            range = extent.range
            facts = when (resource.kind) {
                ResourceKind.PrimaryImage -> session.inspection.media.firstOrNull { it.imageFormat != null }
                    ?: fail("CAPABILITY_UNSUPPORTED", "Primary image has no verified media facts", Stage.Validate)
                ResourceKind.Video -> {
                    val binding = session.bindings.singleOrNull { videoId(it.protocol) == resource.id }
                        ?: fail("CAPABILITY_UNSUPPORTED", "Video resource has no unique protocol binding", Stage.Validate)
                    if (!binding.structurallyValid || binding.video != range)
                        fail("CAPABILITY_UNSUPPORTED", "Video resource boundary is not verified", Stage.Validate)
                    videoFacts(session.videos[binding.protocol]
                        ?: fail("CAPABILITY_UNSUPPORTED", "Video resource did not pass structural validation", Stage.Validate))
                }
                else -> fail("CAPABILITY_UNSUPPORTED", "This resource is not an independently probeable image or video", Stage.Validate)
            }
        } else {
            if (request.media.input !is SourceSet.Single)
                fail("INVALID_ARGUMENT", "Select an explicit media resource for this source set")
            reader = session.readers.single()
            range = ByteRange(0uL, reader.identity().orThrow().size)
            if (session.heifItems != null) {
                HeifCodedCarrier.read(reader, budget, Stage.Validate).orThrow()
                facts = session.inspection.media.firstOrNull { it.imageFormat == ImageFormat.Heic }
                    ?: fail("CAPABILITY_UNSUPPORTED", "HEIF has no finite coded-image facts", Stage.Validate)
                heifCarrier = true
            } else facts = videoFacts(BmffVideoProbe(reader, budget).probe(range).orThrow())
        }
        session.recheck()
        if (!request.decodeCheck) return@attempt facts
        if (facts.imageFormat != null && facts.imageFormat != ImageFormat.Jpeg && !(facts.imageFormat == ImageFormat.Heic && heifCarrier))
            fail("CAPABILITY_UNSUPPORTED", "Image item graph is not yet verified for decoder access", Stage.Validate)
        if (facts.issues.any { it.severity == Severity.Error })
            fail("CORRUPTED_CONTAINER", "Structural errors prevent decoder probing", Stage.Validate)
        val decoder = backend ?: fail("CAPABILITY_UNSUPPORTED", "No media decoder backend is configured", Stage.Validate)
        // Isolate ordinary JPEG/video or the proven closed HEIC image carrier; never a live composite/pair/item view.
        val isolated = ResourceRef(SourceSet.Single(RangeSource(reader, range)))
        val result = decoder.probe(ProbeRequest(isolated, decodeCheck = true, context = request.context))
        // Recheck even a backend failure: no stale success/failure may describe changed input.
        session.recheck()
        val decoded = result.orThrow()
        if (decoded.imageFormat != null && decoded.imageFormat != facts.imageFormat ||
            decoded.videoContainer != null && decoded.videoContainer != facts.videoContainer ||
            decoded.width != null && facts.width != null && decoded.width != facts.width ||
            decoded.height != null && facts.height != null && decoded.height != facts.height ||
            decoded.duration != null && facts.duration != null && decoded.duration != facts.duration)
            fail("POSTCONDITION_FAILED", "Backend facts contradict the verified resource structure", Stage.Validate)
        // Keep backend coverage verbatim: partial or sampled decoding is never promoted to Complete.
        decoded.copy(imageFormat = decoded.imageFormat ?: facts.imageFormat,
            videoContainer = decoded.videoContainer ?: facts.videoContainer, mime = decoded.mime ?: facts.mime,
            width = decoded.width ?: facts.width, height = decoded.height ?: facts.height,
            duration = decoded.duration ?: facts.duration, tracks = decoded.tracks.ifEmpty { facts.tracks },
            issues = (facts.issues + decoded.issues).distinct())
    }
}
