package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*

/** Resolve one verified video extent while guarding all original carriers/candidates. */
internal data class VideoResource(val session: SourceSession, val reader: BinaryReader, val video: VideoStructure) {
    companion object {
        suspend fun open(ref: ResourceRef, context: Context): VideoResource {
            val session = SourceSession.open(ref.input, context, ParseBudget(context)).orThrow()
            if (ref.snapshot != null && ref.snapshot != session.snapshot) fail("SOURCE_CHANGED", "Media snapshot is stale", Stage.Plan)
            val resource = ref.resourceId?.let { id -> session.inspection.layout.resources.singleOrNull { it.id == id }
                ?: fail("INVALID_ARGUMENT", "Video resource is absent from the input") }
            val original: BinaryReader
            val range: ByteRange
            if (resource != null) {
                if (resource.kind != ResourceKind.Video) fail("INVALID_ARGUMENT", "Media operation requires a video resource")
                val extent = resource.extents.singleOrNull() ?: fail("CAPABILITY_UNSUPPORTED", "Video must have one contiguous extent")
                val binding = session.bindings.singleOrNull { videoId(it.protocol) == resource.id }
                    ?: fail("CAPABILITY_UNSUPPORTED", "Video has no unique verified binding")
                if (!binding.structurallyValid || binding.video != extent.range || binding.protocol !in session.videos)
                    fail("CAPABILITY_UNSUPPORTED", "Video resource has not passed structural validation")
                original = session.readerFor(extent.source); range = extent.range
            } else {
                if (ref.input !is SourceSet.Single || session.bindings.isNotEmpty()) fail("INVALID_ARGUMENT", "Select an explicit video resource for a live-photo source")
                original = session.readers.single(); range = ByteRange(0uL, original.identity().orThrow().size)
            }
            val reader = BinaryReader(RangeSource(original, range), context)
            val video = BmffVideoProbe(reader).probe(ByteRange(0uL, range.length)).orThrow()
            session.recheck()
            return VideoResource(session, reader, video)
        }
    }
}
