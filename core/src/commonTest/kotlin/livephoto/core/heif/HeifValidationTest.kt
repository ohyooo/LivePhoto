package livephoto.core.heif

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.bmff.*
import livephoto.core.memory.MemoryBinarySource
import kotlin.test.*

class HeifValidationTest {
    private val context = Context(Limits(2_000_000uL, 2_000_000uL))
    private fun input(bytes: ByteArray) = SourceSet.Single(MemoryBinarySource(Bytes(bytes), SourceId("heif-validation")))
    @Test fun heifChecksNeverClaimJpegValidationAndKeepDecoderNotRun(): Unit = runImmediate {
        val core = DefaultLivePhotoCore()
        val request = ValidationRequest(input(HeifFixtures.plain(multiple = true)), context = context)
        val structure = core.validateStructure(request).orThrow()
        assertTrue(structure.checks.none { it.id.startsWith("jpeg.") })
        assertEquals(Coverage.Complete, structure.checks.single { it.id == "heif.item-locations" }.coverage)
        assertEquals(Coverage.Complete, structure.checks.single { it.id == "heif.item-graph" }.coverage)
        assertEquals(Coverage.NotRun, structure.checks.single { it.id == "heif.metadata" }.coverage)
        assertTrue(structure.checks.all { it.layer == Layer.Structure })
        val media = core.validateMedia(request).orThrow()
        assertEquals(Verdict.Valid, media.checks.single { it.id == "heif.primary-framing" }.verdict)
        assertEquals(Coverage.Complete, media.checks.single { it.id == "heif.primary-framing" }.coverage)
        assertEquals(Coverage.NotRun, media.checks.single { it.id == "media.decode" }.coverage)
        assertEquals(Coverage.Partial, media.coverage)
    }
    @Test fun malformedPrimaryMediaDoesNotBecomeAnUnrunJpegCheckOrValidMedia(): Unit = runImmediate {
        val bytes = HeifFixtures.plain()
        val reader = BinaryReader(MemoryBinarySource(Bytes(bytes), SourceId("heif-validation-facts")), context)
        val roots = BmffReader(reader).readBoxes(ByteRange(0uL, bytes.size.toULong())).orThrow()
        val graph = HeifItemGraphReader.read(reader, roots).orThrow()
        val damaged = bytes.copyOf().also { it[graph.locations.items.single().extents.single().data.offset.toInt()] = 0x7f }
        val core = DefaultLivePhotoCore()
        val request = ValidationRequest(input(damaged), context = context)
        val structure = core.validateStructure(request).orThrow()
        assertTrue(structure.checks.none { it.id.startsWith("jpeg.") })
        assertEquals(Verdict.Valid, structure.checks.single { it.id == "heif.item-locations" }.verdict)
        val media = core.validateMedia(request).orThrow()
        assertEquals(Verdict.Invalid, media.verdict)
        assertEquals(Verdict.Invalid, media.checks.single { it.id == "heif.primary-framing" }.verdict)
        assertTrue(media.issues.any { it.severity == Severity.Error })
    }
    @Test fun unknownDependenciesAndRequiredDecoderRemainExplicitlyIncomplete(): Unit = runImmediate {
        val core = DefaultLivePhotoCore()
        val request = ValidationRequest(input(HeifFixtures.plain(unknownProperty = true)), requiredChecks = listOf("heif.derived-decoder"), context = context)
        val result = core.validateStructure(request).orThrow()
        assertEquals(Coverage.Partial, result.checks.single { it.id == "heif.item-graph" }.coverage)
        assertEquals(Coverage.NotRun, result.checks.single { it.id == "heif.derived-decoder" }.coverage)
        assertEquals(Verdict.Warning, result.verdict)
        assertEquals(Coverage.Partial, result.coverage)
    }
}
