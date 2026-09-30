package livephoto.core.xmp

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.jpeg.JpegParser
import livephoto.core.xml.ExpandedName
import livephoto.core.xml.XmlCData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class XmpTest {
    private val googleUri = "http://ns.google.com/photos/1.0/camera/"
    private val rdfUri = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private val context = Context(Limits(1_000_000uL, 1_000_000uL))
    private fun packet(body: String): XmpPacket = value(XmpReader.parse(Bytes(body.encodeToByteArray()), context))
    private fun rdf(descriptions: String): String = "<r:RDF xmlns:r='$rdfUri' xmlns:g='$googleUri'>$descriptions</r:RDF>"

    @Test
    fun rdfPrefixAliasesAndElementVersusAttributeFormsHaveTheSameScalarMeaning() {
        val alias = packet("<bar:RDF xmlns:bar='$rdfUri' xmlns:motion='$googleUri'><bar:Description motion:MotionPhoto='1'><motion:MotionPhoto>1</motion:MotionPhoto></bar:Description></bar:RDF>")
        assertEquals("1", value(alias.scalar(googleUri, "MotionPhoto")))
        assertEquals(2, alias.properties(googleUri, "MotionPhoto").size)
        val ordinary = packet("<r:RDF xmlns:r='$rdfUri' xmlns:g='urn:unrelated'><r:Description g:MotionPhoto='ordinary text containing MotionPhoto'/></r:RDF>")
        assertEquals(null, value(ordinary.scalar(googleUri, "MotionPhoto")))
        assertEquals("ordinary text containing MotionPhoto", value(ordinary.scalar("urn:unrelated", "MotionPhoto")))
    }

    @Test
    fun conflictingDescriptionsDoNotChooseFirstOrLastValues() {
        val conflicting = packet(rdf("<r:Description g:MotionPhoto='1'/><r:Description><g:MotionPhoto>0</g:MotionPhoto></r:Description>"))
        assertEquals(IssueCode("CONFLICTING_METADATA"), assertIs<CoreResult.Failure>(conflicting.scalar(googleUri, "MotionPhoto")).error.code)
        assertIs<CoreResult.Failure>(XmpWriter.merge(conflicting, mapOf(ExpandedName(googleUri, "MotionPhoto") to "1"), context))
    }

    @Test
    fun structuredResourcesAreNotGuessedAsScalarOffsets() {
        val resource = packet(rdf("<r:Description><g:MicroVideoOffset r:resource='bytes:123'/></r:Description>"))
        assertEquals(IssueCode("CAPABILITY_UNSUPPORTED"), assertIs<CoreResult.Failure>(resource.scalar(googleUri, "MicroVideoOffset")).error.code)
        val structured = packet(rdf("<r:Description><g:MicroVideoOffset><r:Seq/></g:MicroVideoOffset></r:Description>"))
        assertIs<CoreResult.Failure>(structured.scalar(googleUri, "MicroVideoOffset"))
        assertIs<CoreResult.Failure>(XmpReader.parse(Bytes(rdf("<r:Description r:about='different-asset'/>").encodeToByteArray()), context))
    }

    @Test
    fun mergingAndRemovingOnlyNamedPropertiesPreservesOrdinaryMetadataAndPrefixes() {
        val input = "<m:xmpmeta xmlns:m='adobe:ns:meta/' xmlns:lp0='urn:ordinary'><r:RDF xmlns:r='$rdfUri' xmlns:g='$googleUri'><r:Description lp0:copyright='A &amp; B' g:MotionPhoto='1' g:MotionPhotoPresentationTimestampUs='-1'><!--ordinary--><lp0:title><![CDATA[Motion Photo ordinary]]></lp0:title></r:Description></r:RDF></m:xmpmeta>"
        val merged = value(XmpWriter.merge(packet(input), mapOf(
            ExpandedName(googleUri, "MotionPhoto") to null,
            ExpandedName(googleUri, "MotionPhotoPresentationTimestampUs") to "0",
        ), context))
        val reparsed = value(XmpReader.parse(merged, context))
        assertEquals(null, value(reparsed.scalar(googleUri, "MotionPhoto")))
        assertEquals("0", value(reparsed.scalar(googleUri, "MotionPhotoPresentationTimestampUs")))
        assertEquals("A & B", value(reparsed.scalar("urn:ordinary", "copyright")))
        val title = reparsed.properties("urn:ordinary", "title").single().element!!
        assertEquals("Motion Photo ordinary", (title.children.single() as XmlCData).text)
        assertEquals("urn:ordinary", reparsed.document.root.namespaces["lp0"])
    }

    @Test
    fun exactIntegerSpellingIsKeptAndInvalidPropertyNamesCannotProduceMalformedSuccess() {
        val decimal = "18446744073709551615"
        val created = value(XmpWriter.create(mapOf(ExpandedName(googleUri, "MicroVideoOffset") to decimal), context))
        assertEquals(decimal, value(value(XmpReader.parse(created, context)).scalar(googleUri, "MicroVideoOffset")))
        assertIs<CoreResult.Failure>(XmpWriter.create(mapOf(ExpandedName("", "field") to "1"), context))
        assertIs<CoreResult.Failure>(XmpWriter.create(mapOf(ExpandedName(googleUri, "1invalid") to "1"), context))
    }

    @Test
    fun missingOrMultipleAuthoritativeRdfRootsAreMalformed() {
        for (xml in listOf("<ordinary/>", "<x><r:RDF xmlns:r='$rdfUri'><r:Description/></r:RDF><r:RDF xmlns:r='$rdfUri'><r:Description/></r:RDF></x>")) {
            assertIs<CoreResult.Failure>(XmpReader.parse(Bytes(xml.encodeToByteArray()), context))
        }
    }

    @Test
    fun adobeEnvelopeIsAuthoritativeButUnknownOrNestedRdfWrappersAreNot() {
        val direct = rdf("<r:Description g:MotionPhoto='1'/>")
        for (name in listOf("xmpmeta", "xapmeta")) {
            assertEquals("1", value(packet("<m:$name xmlns:m='adobe:ns:meta/'>$direct</m:$name>").scalar(googleUri, "MotionPhoto")))
        }
        for (xml in listOf(
            "<unknown><payload>$direct</payload></unknown>",
            "<m:xmpmeta xmlns:m='adobe:ns:meta/'><payload>$direct</payload></m:xmpmeta>",
        )) assertIs<CoreResult.Failure>(XmpReader.parse(Bytes(xml.encodeToByteArray()), context))
        val unknownSubtree = packet(rdf("<r:Description><g:foreign>$direct</g:foreign></r:Description>"))
        assertEquals(null, value(unknownSubtree.scalar(googleUri, "MotionPhoto")))
        val originalForeign = unknownSubtree.properties(googleUri, "foreign").single().element
        val edited = value(XmpWriter.merge(unknownSubtree, mapOf(ExpandedName(googleUri, "MicroVideoOffset") to "123"), context))
        val reparsed = value(XmpReader.parse(edited, context))
        assertEquals(originalForeign, reparsed.properties(googleUri, "foreign").single().element)
        assertEquals(null, value(reparsed.scalar(googleUri, "MotionPhoto")))
    }

    @Test
    fun emptyRdfCanGainADefaultDescriptionWithoutDroppingItsAdobeEnvelope() {
        val empty = packet("<m:xmpmeta xmlns:m='adobe:ns:meta/' xmlns:u='urn:ordinary' u:copyright='keep'><r:RDF xmlns:r='$rdfUri'/></m:xmpmeta>")
        assertEquals(null, value(empty.scalar(googleUri, "MotionPhoto")))
        val updated = value(XmpWriter.merge(empty, mapOf(ExpandedName(googleUri, "MotionPhoto") to "1"), context))
        val reparsed = value(XmpReader.parse(updated, context))
        assertEquals("1", value(reparsed.scalar(googleUri, "MotionPhoto")))
        assertEquals("keep", reparsed.document.root.attribute("urn:ordinary", "copyright"))
    }

    @Test
    fun identifiedSubjectsAndQualifiedLiteralsCannotSilentlyLoseTheirBindings() {
        assertIs<CoreResult.Failure>(XmpReader.parse(Bytes(rdf("<r:Description r:ID='other'/>").encodeToByteArray()), context))
        val qualified = packet("<r:RDF xmlns:r='$rdfUri' xmlns:g='$googleUri' xmlns:u='urn:ordinary'><r:Description><g:MotionPhoto u:qualifier='keep'>1</g:MotionPhoto></r:Description></r:RDF>")
        assertIs<CoreResult.Failure>(qualified.scalar(googleUri, "MotionPhoto"))
        assertIs<CoreResult.Failure>(XmpWriter.merge(qualified, mapOf(ExpandedName(googleUri, "MotionPhoto") to "0"), context))
    }

    @Test
    fun multipleJpegPacketsRemainSeparateAndBlockUnreconciledRewrite(): Unit = runImmediate {
        val first = rdf("<r:Description g:MotionPhoto='1'/>")
        val second = rdf("<r:Description g:MotionPhoto='0'/>")
        val jpeg = soi() + xmpApp(first) + xmpApp(second) + imageTail()
        val reader = BinaryReader(TestSource(jpeg), context)
        val collection = value(XmpReader.readJpeg(reader, value(JpegParser.parse(reader))))
        assertEquals(2, collection.packets.size)
        assertFalse(collection.rewriteAllowed)
        assertEquals(IssueCode("CONFLICTING_METADATA"), assertIs<CoreResult.Failure>(collection.scalar(googleUri, "MotionPhoto")).error.code)
        assertEquals(IssueCode("UNSAFE_METADATA_REWRITE"), assertIs<CoreResult.Failure>(XmpWriter.merge(collection, emptyMap(), context)).error.code)
    }

    @Test
    fun extendedChunkRangesAreExactAndNeverSilentlyReassembledForRewrite(): Unit = runImmediate {
        val main = xmpApp(rdf("<r:Description g:MotionPhoto='1'/>"))
        val extended = extendedApp(total = 10u, offset = 4u, data = bytes(1, 2, 3))
        val jpeg = soi() + main + extended + imageTail()
        val reader = BinaryReader(TestSource(jpeg), context)
        val collection = value(XmpReader.readJpeg(reader, value(JpegParser.parse(reader))))
        val chunk = collection.extended.single()
        assertEquals("0123456789abcdef0123456789abcdef", chunk.guid)
        assertEquals(10u, chunk.totalLength)
        assertEquals(4u, chunk.offset)
        assertEquals(Bytes(bytes(1, 2, 3)), value(reader.readExactly(chunk.data.offset, chunk.data.length.toUInt())))
        assertFalse(collection.rewriteAllowed)
        assertFalse(collection.extendedComplete)
        kotlin.test.assertTrue(collection.issues.any { it.severity == Severity.Error && it.layer == Layer.Structure })
        assertIs<CoreResult.Failure>(XmpWriter.merge(collection, emptyMap(), context))
    }

    @Test
    fun invalidExtendedChunkBoundsAndReferencedExtendedMetadataRejectRewrite(): Unit = runImmediate {
        for (extended in listOf(extendedApp(10u, 9u, bytes(1, 2, 3)), extendedApp(UInt.MAX_VALUE, 0u, bytes(1)))) {
            val reader = BinaryReader(TestSource(soi() + extended + imageTail()), context)
            assertIs<CoreResult.Failure>(XmpReader.readJpeg(reader, value(JpegParser.parse(reader))))
        }
        val referenced = packet("<r:RDF xmlns:r='$rdfUri' xmlns:n='http://ns.adobe.com/xmp/note/'><r:Description n:HasExtendedXMP='0123456789abcdef0123456789abcdef'/></r:RDF>")
        assertIs<CoreResult.Failure>(XmpWriter.merge(referenced, emptyMap(), context))
    }

    @Test
    fun conflictingOverlappingOrEmptyExtendedChunkSetsAreStructuredFailures(): Unit = runImmediate {
        val variants = listOf(
            extendedApp(10u, 0u, bytes(1, 2)) + extendedApp(11u, 2u, bytes(3)),
            extendedApp(10u, 0u, bytes(1, 2, 3)) + extendedApp(10u, 2u, bytes(4, 5)),
            extendedApp(10u, 0u, bytes(1, 2)) + extendedApp(10u, 0u, bytes(1, 2)),
            extendedApp(0u, 0u, byteArrayOf()),
            extendedApp(10u, 0u, byteArrayOf()),
        )
        for (extended in variants) {
            val reader = BinaryReader(TestSource(soi() + extended + imageTail()), context)
            assertIs<CoreResult.Failure>(XmpReader.readJpeg(reader, value(JpegParser.parse(reader))))
        }
    }

    @Test
    fun boundedXmpMutationsNeverProduceUncheckedExceptions() {
        val golden = rdf("<r:Description g:MotionPhoto='1'><g:ordinary>Motion</g:ordinary></r:Description>").encodeToByteArray()
        for (input in boundedMutations(golden)) {
            when (val result = XmpReader.parse(Bytes(input), context)) {
                is CoreResult.Success -> result.value.scalar(googleUri, "MotionPhoto")
                is CoreResult.Failure -> kotlin.test.assertTrue(result.error.code.value.isNotBlank())
            }
        }
    }

    private fun xmpApp(xml: String): ByteArray = app("http://ns.adobe.com/xap/1.0/\u0000".encodeToByteArray() + xml.encodeToByteArray())
    private fun extendedApp(total: UInt, offset: UInt, data: ByteArray): ByteArray = app(
        "http://ns.adobe.com/xmp/extension/\u0000".encodeToByteArray() + "0123456789abcdef0123456789abcdef".encodeToByteArray() + big32(total) + big32(offset) + data,
    )
    private fun app(payload: ByteArray): ByteArray = bytes(0xff, 0xe1, (payload.size + 2) ushr 8, (payload.size + 2) and 255) + payload
    private fun big32(value: UInt): ByteArray = ByteArray(4) { (value shr ((3 - it) * 8)).toByte() }
    private fun soi(): ByteArray = bytes(0xff, 0xd8)
    private fun imageTail(): ByteArray = bytes(0xff, 0xda, 0, 8, 1, 1, 0, 0, 0x3f, 0, 0x11, 0xff, 0xd9)
    private fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
