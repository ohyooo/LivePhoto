package livephoto.core

public data class Condition(
    public val operator: ConditionOperator,
    public val key: String? = null,
    public val value: Value? = null,
    public val operands: List<Condition> = emptyList(),
)

public data class CapabilityEntry(
    public val operation: Operation,
    public val implementation: Implementation,
    public val lifecycle: Lifecycle = Lifecycle.Active,
    public val exposure: Exposure = Exposure.Public,
    public val conditions: List<Condition> = emptyList(),
    public val reasons: List<IssueCode> = emptyList(),
    public val verification: List<Verification> = emptyList(),
    public val evidence: List<Evidence> = emptyList(),
)

public data class ProtocolCapabilities(public val target: ProtocolSelector, public val operations: List<CapabilityEntry>) {
    init { require(operations.map { it.operation }.distinct().size == operations.size) { "Duplicate operation capability" } }
}
public data class MediaCapabilities(public val backendIds: List<String>, public val operations: List<CapabilityEntry>)
public data class CapabilitySet(
    public val availability: Availability,
    public val operations: List<CapabilityEntry>,
    public val issues: List<Issue> = emptyList(),
)
public data class AnalysisResult(
    public val inspection: InspectionResult,
    public val validation: ValidationReport,
    public val capabilities: CapabilitySet,
)
