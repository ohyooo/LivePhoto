package livephoto.core.xml

import livephoto.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class XmlParserTest {
    private fun context(bytes: ULong = 64_000uL, depth: UInt = 64u, items: ULong = 10_000uL): Context =
        Context(Limits(64_000uL, 64_000uL, maxMetadataBytes = bytes, maxDepth = depth, maxItems = items))
    private fun parse(xml: String, context: Context = context()): CoreResult<XmlDocument> = XmlParser.parse(Bytes(xml.encodeToByteArray()), context)

    @Test
    fun namespaceBindingsFollowUrisIncludingRebindingAndUnqualifiedAttributes() {
        val document = value(parse("<r xmlns='urn:outer' attr='plain'><p:c xmlns:p='urn:first' p:flag='yes'><p:c xmlns:p='urn:second'/></p:c><c xmlns=''/></r>"))
        assertEquals(ExpandedName("urn:outer", "r"), document.root.name.expanded)
        assertEquals("plain", document.root.attribute("", "attr"))
        assertEquals(null, document.root.attribute("urn:outer", "attr"))
        val first = document.root.elements("urn:first", "c").single()
        assertEquals("yes", first.attribute("urn:first", "flag"))
        assertEquals(ExpandedName("urn:second", "c"), first.elements("urn:second", "c").single().name.expanded)
        assertEquals(1, document.root.elements("", "c").size)
    }

    @Test
    fun aliasesForTheSameNamespaceCannotIntroduceDuplicateExpandedAttributes() {
        malformed("<r xmlns:a='urn:same' xmlns:b='urn:same' a:field='one' b:field='two'/>")
        malformed("<r value='one' value='two'/>")
        malformed("<p:r/>")
        malformed("<r xmlns:xml='urn:wrong'/>")
        malformed("<r xmlns:p='http://www.w3.org/XML/1998/namespace'/>")
    }

    @Test
    fun invalidQualifiedNamesAndNumericReferenceSpellingsAreRejected() {
        for (xml in listOf(
            "<p:1bad xmlns:p='urn:p'/>",
            "<r xmlns:p='urn:p' p:1attr='x'/>",
            "<r xmlns:1p='urn:p'/>",
            "<r>&#+65;</r>",
            "<r>&#x+41;</r>",
            "<r>&#x-1;</r>",
        )) malformed(xml)
    }

    @Test
    fun dtdExternalEntitiesAndInvalidUnicodeCannotReachNetworkOrExpand() {
        for (xml in listOf(
            "<!DOCTYPE r SYSTEM 'https://invalid.example/external'><r/>",
            "<!DOCTYPE r [<!ENTITY value 'repeated'>]><r>&value;</r>",
            "<r>&unknown;</r>",
            "<r>&#0;</r>",
            "<r>&#xD800;</r>",
            "<r>&#xFFFE;</r>",
            "<r>&#xFFFF;</r>",
            "<r>&#x110000;</r>",
            "<r>\u0000</r>",
            "<r>\uFFFE</r>",
        )) malformed(xml)
        for (invalidUtf8 in listOf(byteArrayOf(0xc0.toByte(), 0x80.toByte()), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))) {
            val result = XmlParser.parse(Bytes("<r>".encodeToByteArray() + invalidUtf8 + "</r>".encodeToByteArray()), context())
            assertIs<CoreResult.Failure>(result)
        }
    }

    @Test
    fun xmlLiteralNormalizationDiffersFromNumericWhitespaceReferences() {
        val document = value(parse("<r v='A\tB\r\nC\rD&#x9;&#xA;&#xD;'>L1\r\nL2\rL3 &#x1F600;</r>"))
        assertEquals("A B C D\t\n\r", document.root.attribute("", "v"))
        assertEquals("L1\nL2\nL3 \uD83D\uDE00", (document.root.children.single() as XmlText).text)
        val reserialized = value(XmlWriter.write(document, context()))
        assertEquals(document, value(XmlParser.parse(reserialized, context())))
    }

    @Test
    fun unknownMetadataCommentsCdataAndInstructionsSurviveSerialization() {
        val document = value(parse("<?xpacket begin='?'?><r xmlns:u='urn:ordinary' u:copyright='A &amp; B'><!--ordinary--><u:title><![CDATA[Motion & Photo <unknown>]]></u:title><?custom retain?></r>"))
        val serialized = value(XmlWriter.write(document, context()))
        val reparsed = value(XmlParser.parse(serialized, context()))
        assertEquals(document, reparsed)
        assertEquals("A & B", reparsed.root.attribute("urn:ordinary", "copyright"))
        assertEquals("Motion & Photo <unknown>", (reparsed.root.elements("urn:ordinary", "title").single().children.single() as XmlCData).text)
    }

    @Test
    fun malformedDeclarationsAndDocumentGrammarFail() {
        for (xml in listOf(
            "<?xml version='1.1'?><r/>",
            "<?xml version='1.0' standalone='yes' encoding='UTF-8'?><r/>",
            "<?xml version='1.0' encoding='UTF&#x2D;8'?><r/>",
            "<r/><second/>", "text<r/>", "<r><!--x--y--></r>", "<r>]]></r>", "<r><c></r>",
            "&#32;<r/>", "<r/>&#9;", "<?a:b?><r/>", "<?xml version='&#49;.0'?><r/>",
        )) malformed(xml)
        value(parse("<?xml version='1.0' encoding='UTF-8' standalone='yes'?><r/>"))
    }

    @Test
    fun byteDepthAndItemBudgetsRejectInputsBeforeUnboundedRetaining() {
        val xml = "<r><a><b/></a></r>"
        value(parse(xml, context(bytes = xml.encodeToByteArray().size.toULong(), depth = 2u)))
        val byteFailure = assertIs<CoreResult.Failure>(parse(xml, context(bytes = xml.encodeToByteArray().size.toULong() - 1uL)))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), byteFailure.error.code)
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(parse("<r><a><b><c/></b></a></r>", context(depth = 2u))).error.code)
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), assertIs<CoreResult.Failure>(parse(xml, context(items = 2uL))).error.code)
    }

    @Test
    fun boundedMutationInputsOnlyProduceStructuredOutcomes() {
        val golden = "<r xmlns:p='urn:p' p:value='ordinary &#x9;'><p:c><![CDATA[Motion]]></p:c></r>".encodeToByteArray()
        for (input in boundedMutations(golden)) {
            when (val result = XmlParser.parse(Bytes(input), context())) {
                is CoreResult.Success -> assertTrue(result.value.root.name.expanded.local.isNotBlank())
                is CoreResult.Failure -> assertTrue(result.error.code.value.isNotBlank())
            }
        }
    }

    @Test
    fun writerRejectsEscapingBeyondBudgetAndInvalidModifiedText() {
        val ordinary = value(parse("<r a='&amp;&amp;&amp;&amp;&amp;&amp;'/>"))
        val result = assertIs<CoreResult.Failure>(XmlWriter.write(ordinary, context(bytes = 16uL)))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), result.error.code)
        val invalidRoot = ordinary.root.copy(children = listOf(XmlText("\u0000")))
        assertIs<CoreResult.Failure>(XmlWriter.write(XmlDocument(invalidRoot, listOf(invalidRoot)), context()))
    }

    @Test
    fun largeTextParsingChecksCancellationDuringTheWork() {
        var polls = 0
        val limitedContext = context().copy(cancellation = Cancellation { ++polls >= 5 })
        val result = assertIs<CoreResult.Failure>(parse("<r>" + "ordinary".repeat(2000) + "</r>", limitedContext))
        assertEquals(IssueCode("CANCELLED"), result.error.code)
    }

    @Test
    fun oddAlignedSupplementaryNamesDoNotSkipAllCancellationPollingBoundaries() {
        var polls = 0
        val limitedContext = context().copy(cancellation = Cancellation { ++polls >= 5 })
        // UTF-16 positions 3,5,7,... skip exact multiples of 4096 when walking by code point.
        val name = "ab" + "\uD83D\uDE00".repeat(6000)
        val result = assertIs<CoreResult.Failure>(parse("<$name/>", limitedContext))
        assertEquals(IssueCode("CANCELLED"), result.error.code)
    }

    @Test
    fun oversizedCallerDepthBudgetDoesNotRemoveSafeImplementationDepthLimit() {
        val largeBudget = context(depth = 100_000u)
        val deeplyNested = "<r>".repeat(300) + "</r>".repeat(300)
        val parseFailure = assertIs<CoreResult.Failure>(parse(deeplyNested, largeBudget))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), parseFailure.error.code)
        var element = XmlElement(XmlName("r", ExpandedName("", "r")), emptyList(), emptyMap(), emptyList())
        repeat(300) { element = XmlElement(XmlName("r", ExpandedName("", "r")), emptyList(), emptyMap(), listOf(element)) }
        val writeFailure = assertIs<CoreResult.Failure>(XmlWriter.write(XmlDocument(element, listOf(element)), largeBudget))
        assertEquals(IssueCode("RESOURCE_LIMIT_EXCEEDED"), writeFailure.error.code)
    }

    private fun malformed(xml: String) { assertIs<CoreResult.Failure>(parse(xml), xml) }
    private fun <T> value(result: CoreResult<T>): T = assertIs<CoreResult.Success<T>>(result).value
}
