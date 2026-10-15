package livephoto.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolRegistryTest {
    @Test
    fun publicProtocolIdentifiersMatchTheNormativeChapters() {
        assertEquals("google.microvideo.v1", ProtocolIds.GoogleV1.value)
        assertEquals("google.motionphoto.v2", ProtocolIds.GoogleV2.value)
        assertEquals("oplus.olive", ProtocolIds.Oplus.value)
        assertEquals("vivo.motionphoto", ProtocolIds.VivoModern.value)
        assertEquals("vivo.legacy-pair", ProtocolIds.VivoLegacy.value)
        assertEquals("samsung.motionphoto", ProtocolIds.Samsung.value)
        assertEquals("huawei.movingphoto", ProtocolIds.Huawei.value)
        assertEquals("apple.livephoto", ProtocolIds.Apple.value)
        assertEquals("lpb.fusion.legacy", ProtocolIds.Fusion.value)
    }

    @Test
    fun designRegistryDoesNotClaimImplementedOrDeviceVerifiedCapabilities() {
        val registry = ProtocolRegistry.planned()
        assertTrue(registry.targets.isNotEmpty())
        for (target in registry.targets) {
            for (entry in registry.capabilities(target).operations) {
                assertTrue(entry.implementation == Implementation.Planned || entry.implementation == Implementation.Unsupported)
                assertFalse(Verification.DeviceTested in entry.verification)
                assertFalse(Verification.SyntheticTested in entry.verification)
            }
        }
        assertFalse(registry.targets.any { it.protocol.value == "xiaomi" || it.protocol.value == "redmi" })
    }

    @Test
    fun unsupportedUnknownProfileDoesNotFallBackToJpegRegistration() {
        val registry = ProtocolRegistry.planned()
        val unknown = ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("unimplemented-format"))
        assertFalse(registry.contains(unknown))
        assertEquals(Implementation.Unsupported, registry.capability(unknown, Operation.Create).implementation)
        assertTrue(registry.capabilities(unknown).operations.all { it.implementation == Implementation.Unsupported })
    }

    @Test
    fun legacyReadingAndHiddenWritingHaveIndependentStatusAxes() {
        val registry = ProtocolRegistry.planned()
        val target = ProtocolSelector(ProtocolIds.Fusion, ProfileId("jpeg"))
        val reading = registry.capability(target, Operation.Detect)
        val writing = registry.capability(target, Operation.Create)
        assertEquals(Lifecycle.Legacy, reading.lifecycle)
        assertEquals(Implementation.Planned, reading.implementation)
        assertEquals(Exposure.Public, reading.exposure)
        assertEquals(Lifecycle.Legacy, writing.lifecycle)
        assertEquals(Implementation.Unsupported, writing.implementation)
        assertEquals(Exposure.Hidden, writing.exposure)
    }

    @Test
    fun duplicateRegistrationsAndOperationsAreRejectedWithoutMutatingOriginalRegistry() {
        val target = ProtocolSelector(ProtocolIds.GoogleV1, ProfileId("jpeg"))
        val entry = ProtocolCapabilities(target, listOf(CapabilityEntry(Operation.Detect, Implementation.Planned)))
        assertFailsWith<IllegalArgumentException> { ProtocolRegistry(listOf(entry, entry)) }
        assertFailsWith<IllegalArgumentException> { ProtocolCapabilities(target, entry.operations + entry.operations) }
        val registry = ProtocolRegistry(listOf(entry))
        assertFailsWith<IllegalArgumentException> { registry.withRegistration(entry) }
        val another = entry.copy(target = ProtocolSelector(ProtocolIds.GoogleV2, ProfileId("jpeg")))
        val extended = registry.withRegistration(another)
        assertFalse(registry.contains(another.target))
        assertTrue(extended.contains(another.target))
    }

    @Test
    fun registryOwnsNestedConditionsAndReturnsDetachedValues() {
        val values = mutableListOf<Value>(Value.Text("jpeg"))
        val operands = mutableListOf(Condition(ConditionOperator.Equals, "format", Value.ArrayValue(values)))
        val conditions = mutableListOf(Condition(ConditionOperator.All, operands = operands))
        val reasons = mutableListOf(IssueCode("CAPABILITY_PLANNED"))
        val operations = mutableListOf(CapabilityEntry(Operation.Create, Implementation.Planned, conditions = conditions, reasons = reasons))
        val target = ProtocolSelector(ProtocolIds.GoogleV1, ProfileId("jpeg"))
        val registry = ProtocolRegistry(listOf(ProtocolCapabilities(target, operations)))
        values.clear()
        operands.clear()
        conditions.clear()
        reasons.clear()
        operations.clear()
        val returned = registry.capability(target, Operation.Create)
        assertEquals(listOf(IssueCode("CAPABILITY_PLANNED")), returned.reasons)
        val conditionValue = returned.conditions.single().operands.single().value as Value.ArrayValue
        assertEquals(listOf(Value.Text("jpeg")), conditionValue.values)
        try {
            (returned.conditions as? MutableList<Condition>)?.clear()
        } catch (_: UnsupportedOperationException) {
            // The registry may return an immutable view.
        }
        assertEquals(1, registry.capability(target, Operation.Create).conditions.size)
    }

    @Test
    fun registryObjectValuesCannotBeChangedThroughOriginalOrReturnedMaps() {
        val nested = mutableMapOf<String, Value>("format" to Value.Text("jpeg"))
        val outer = mutableMapOf<String, Value>("media" to Value.ObjectValue(nested))
        val target = ProtocolSelector(ProtocolIds.GoogleV1, ProfileId("jpeg"))
        val entry = CapabilityEntry(Operation.Create, Implementation.Planned, conditions = listOf(
            Condition(ConditionOperator.Equals, "facts", Value.ObjectValue(outer)),
        ))
        val registry = ProtocolRegistry(listOf(ProtocolCapabilities(target, listOf(entry))))
        nested.clear()
        outer.clear()
        val returned = registry.capability(target, Operation.Create).conditions.single().value as Value.ObjectValue
        val returnedNested = returned.entries.getValue("media") as Value.ObjectValue
        assertEquals(Value.Text("jpeg"), returnedNested.entries["format"])
        try {
            (returnedNested.entries as? MutableMap<String, Value>)?.clear()
        } catch (_: UnsupportedOperationException) {
            // Immutable maps and defensive copies both satisfy registry isolation.
        }
        val next = registry.capability(target, Operation.Create).conditions.single().value as Value.ObjectValue
        assertEquals(Value.Text("jpeg"), (next.entries.getValue("media") as Value.ObjectValue).entries["format"])
    }
}
