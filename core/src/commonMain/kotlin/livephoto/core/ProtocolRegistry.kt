package livephoto.core

/** Stable identifiers independent of UI ordering, vendor branding, or image/container formats. */
public object ProtocolIds {
    public val GoogleV1: ProtocolId = ProtocolId("google.microvideo.v1")
    public val GoogleV2: ProtocolId = ProtocolId("google.motionphoto.v2")
    public val Oplus: ProtocolId = ProtocolId("oplus.olive")
    public val VivoModern: ProtocolId = ProtocolId("vivo.motionphoto")
    public val VivoLegacy: ProtocolId = ProtocolId("vivo.legacy-pair")
    public val Samsung: ProtocolId = ProtocolId("samsung.motionphoto")
    public val Huawei: ProtocolId = ProtocolId("huawei.movingphoto")
    public val Apple: ProtocolId = ProtocolId("apple.livephoto")
    public val Fusion: ProtocolId = ProtocolId("lpb.fusion.legacy")
}

/**
 * Immutable capability registrations. Unknown profiles remain unsupported: no protocol fallback
 * silently promotes an unimplemented image/container variant. Xiaomi/Redmi is a compatibility
 * observation, not a separate protocol registration.
 */
public class ProtocolRegistry(entries: List<ProtocolCapabilities> = emptyList()) {
    private val registrations: Map<ProtocolSelector, ProtocolCapabilities>

    init {
        require(entries.map { it.target }.distinct().size == entries.size) { "Duplicate protocol/profile registration" }
        registrations = entries.associate { it.target to detached(it) }
    }

    public val targets: List<ProtocolSelector> get() = frozenList(registrations.keys.toList())
    public fun contains(target: ProtocolSelector): Boolean = target in registrations

    public fun capabilities(target: ProtocolSelector): ProtocolCapabilities {
        val entry = registrations[target]
        return if (entry != null) detached(entry) else ProtocolCapabilities(
            target,
            Operation.entries.map { CapabilityEntry(it, Implementation.Unsupported, reasons = listOf(IssueCode("UNSUPPORTED_PROTOCOL"))) },
        )
    }

    public fun capability(target: ProtocolSelector, operation: Operation): CapabilityEntry =
        capabilities(target).operations.firstOrNull { it.operation == operation }
            ?: CapabilityEntry(operation, Implementation.Unsupported, reasons = listOf(IssueCode("CAPABILITY_UNSUPPORTED")))

    public fun withRegistration(entry: ProtocolCapabilities): ProtocolRegistry {
        require(!contains(entry.target)) { "Protocol/profile is already registered" }
        return ProtocolRegistry(registrations.values.toList() + entry)
    }

    public companion object {
        /** Planned design entries carry no claim of executable, synthetic, or device verification. */
        public fun planned(): ProtocolRegistry {
            val targets = listOf(
                ProtocolIds.GoogleV1 to "jpeg",
                ProtocolIds.GoogleV2 to "jpeg",
                ProtocolIds.GoogleV2 to "heic",
                ProtocolIds.GoogleV2 to "avif",
                ProtocolIds.Oplus to "jpeg-no-tail",
                ProtocolIds.Oplus to "oneplus-tail-bearing",
                ProtocolIds.VivoModern to "jpeg",
                ProtocolIds.VivoLegacy to "pair",
                ProtocolIds.Samsung to "jpeg-sef-mpv3",
                ProtocolIds.Samsung to "heic-sef-mpv2",
                ProtocolIds.Huawei to "basic60",
                ProtocolIds.Huawei to "honor-extended",
                ProtocolIds.Apple to "jpeg-mov",
                ProtocolIds.Apple to "heic-mov",
                ProtocolIds.Apple to "heic-mp4",
                ProtocolIds.Apple to "jpeg-mp4",
                ProtocolIds.Fusion to "jpeg",
            )
            return ProtocolRegistry(targets.map { (protocol, profile) ->
                val legacy = protocol == ProtocolIds.VivoLegacy || protocol == ProtocolIds.Fusion
                ProtocolCapabilities(ProtocolSelector(protocol, ProfileId(profile)), Operation.entries.map { operation ->
                    val disabledWriter = legacy && operation in setOf(Operation.Create, Operation.ConvertTo)
                    CapabilityEntry(
                        operation,
                        if (disabledWriter) Implementation.Unsupported else Implementation.Planned,
                        lifecycle = if (legacy) Lifecycle.Legacy else Lifecycle.Active,
                        exposure = if (disabledWriter) Exposure.Hidden else Exposure.Public,
                        reasons = listOf(IssueCode(if (disabledWriter) "CAPABILITY_UNSUPPORTED" else "CAPABILITY_PLANNED")),
                    )
                })
            })
        }
    }
}

private fun detached(entry: ProtocolCapabilities): ProtocolCapabilities = ProtocolCapabilities(
    entry.target,
    frozenList(entry.operations.map {
        it.copy(
            conditions = frozenList(it.conditions.map(::detachedCondition)),
            reasons = frozenList(it.reasons),
            verification = frozenList(it.verification),
            evidence = frozenList(it.evidence),
        )
    }),
)

private fun detachedCondition(condition: Condition): Condition = condition.copy(
    value = condition.value?.let(::detachedValue),
    operands = frozenList(condition.operands.map(::detachedCondition)),
)

private fun detachedValue(value: Value): Value = when (value) {
    is Value.ArrayValue -> Value.ArrayValue(frozenList(value.values.map(::detachedValue)))
    is Value.ObjectValue -> Value.ObjectValue(value.entries.mapValues { detachedValue(it.value) })
    else -> value
}
