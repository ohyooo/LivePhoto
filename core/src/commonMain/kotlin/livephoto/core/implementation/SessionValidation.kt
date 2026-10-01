package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*

/** Required binary checks and decoder coverage remain independent. */
internal fun validateSession(session: SourceSession, layers: List<Layer>, requiredChecks: List<String> = emptyList(), target: ProtocolSelector? = null): CoreResult<ValidationReport> = attemptNow {
    if (target != null && session.inspection.detection.matches.none { it.target.protocol == target.protocol && (target.profile == null || it.target.profile == target.profile) }) {
        fail("UNSUPPORTED_PROTOCOL", "Requested protocol/profile is absent from the inspected binding", Stage.Validate)
    }
    val selectedTarget = target ?: session.inspection.detection.primaryProtocol?.takeIf { it.protocol !in setOf(ProtocolIds.GoogleV1, ProtocolIds.GoogleV2) }
    val selectedBindings = if (selectedTarget == null) session.bindings else session.bindings.filter { it.protocol == selectedTarget.protocol && (selectedTarget.profile == null || it.profile == selectedTarget.profile) }
    val scopedProtocol = selectedBindings.flatMap { it.issues }.filter { it.layer == Layer.Protocol }
    val issues = session.inspection.issues.filter { it.layer != Layer.Protocol } + scopedProtocol
    val checks = mutableListOf<CheckResult>()
    val auxiliaryRanges = session.inspection.layout.resources.filter { it.kind == ResourceKind.GainMap }.flatMap { it.extents }.map { it.range }.toSet()
    val verifiedAuxiliary = session.gainMaps.map { it.range }.toSet()
    val auxiliaryIssues = issues.filter { it.layer == Layer.Media && it.location?.range in auxiliaryRanges }
    if (auxiliaryRanges.isNotEmpty() && (Layer.Structure in layers || Layer.Media in layers)) {
        val complete = auxiliaryRanges.all { it in verifiedAuxiliary }
        checks += CheckResult("auxiliary.jpeg", if (Layer.Structure in layers) Layer.Structure else Layer.Media,
            if (auxiliaryIssues.any { it.severity == Severity.Error }) Verdict.Invalid else if (!complete || auxiliaryIssues.isNotEmpty()) Verdict.Warning else Verdict.Valid,
            if (complete) Coverage.Complete else if (verifiedAuxiliary.isNotEmpty()) Coverage.Partial else Coverage.NotRun, auxiliaryIssues)
    }
    if (Layer.Structure in layers) {
        checks += CheckResult("jpeg.markers", Layer.Structure, if (session.jpeg == null) Verdict.Warning else Verdict.Valid,
            if (session.jpeg == null) Coverage.NotRun else Coverage.Complete)
        val imageIssues = issues.filter { it.layer == Layer.Structure }
        checks += CheckResult("jpeg.frame", Layer.Structure, if (imageIssues.any { it.severity == Severity.Error }) Verdict.Invalid else if (imageIssues.isNotEmpty()) Verdict.Warning else Verdict.Valid,
            if (session.jpeg == null || imageIssues.any { it.code.value in setOf("UNSUPPORTED_CONTAINER", "CAPABILITY_UNSUPPORTED", "UNKNOWN_PROTOCOL_VARIANT") }) Coverage.NotRun else Coverage.Complete, imageIssues)
        val binaryIssues = issues.filter { it.layer == Layer.Structure || it.layer == Layer.Media && it.severity == Severity.Error }
        checks += CheckResult("bmff.samples", Layer.Structure, if (binaryIssues.any { it.severity == Severity.Error }) Verdict.Invalid else if (binaryIssues.isNotEmpty()) Verdict.Warning else Verdict.Valid,
            if (session.videos.size == session.bindings.count { it.video != null } && session.videos.isNotEmpty()) Coverage.Complete else if (session.videos.isNotEmpty()) Coverage.Partial else Coverage.NotRun, binaryIssues)
    }
    if (Layer.Protocol in layers) {
        val protocolIssues = issues.filter { it.layer == Layer.Protocol }
        val apple = selectedTarget?.protocol == ProtocolIds.Apple
        checks += CheckResult(if (apple) "apple.pair" else "google.binding", Layer.Protocol, if (protocolIssues.any { it.severity == Severity.Error }) Verdict.Invalid else if (protocolIssues.isNotEmpty()) Verdict.Warning else Verdict.Valid,
            if (selectedBindings.isEmpty()) Coverage.NotRun else if (protocolIssues.any { it.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNKNOWN_PROTOCOL_VARIANT") }) Coverage.Partial else Coverage.Complete, protocolIssues)
        val targetIssues = selectedBindings.flatMap { binding -> if (binding.protocol == ProtocolIds.Apple) emptyList() else session.videos[binding.protocol]?.let { googleVideoIssues(it, binding.selector, false) } ?: emptyList() }
        checks += CheckResult(if (apple) "apple.media-profile" else "google.video-profile", Layer.Protocol,
            if (targetIssues.any { it.severity == Severity.Error }) Verdict.Invalid else if (targetIssues.isNotEmpty()) Verdict.Warning else Verdict.Valid,
            if (selectedBindings.none { it.protocol in session.videos }) Coverage.NotRun else if (selectedBindings.any { it.video != null && it.protocol !in session.videos } || targetIssues.any { it.code.value in setOf("CAPABILITY_UNSUPPORTED", "UNSUPPORTED_CONTAINER", "UNKNOWN_PROTOCOL_VARIANT") }) Coverage.Partial else Coverage.Complete, targetIssues)
    }
    if (Layer.Media in layers) {
        val mediaIssues = issues.filter { it.layer == Layer.Media }
        checks += CheckResult("media.structure", Layer.Media, if (mediaIssues.any { it.severity == Severity.Error }) Verdict.Invalid else if (mediaIssues.isNotEmpty()) Verdict.Warning else Verdict.Valid,
            if (session.videos.size == session.bindings.count { it.video != null } && auxiliaryRanges.all { it in verifiedAuxiliary } && (session.videos.isNotEmpty() || auxiliaryRanges.isNotEmpty())) Coverage.Complete else if (session.videos.isNotEmpty() || verifiedAuxiliary.isNotEmpty()) Coverage.Partial else Coverage.NotRun, mediaIssues)
        checks += CheckResult("media.decode", Layer.Media, Verdict.Warning, Coverage.NotRun,
            listOf(Issue(IssueCode("CAPABILITY_UNSUPPORTED"), Severity.Warning, Layer.Media)))
    }
    for (required in requiredChecks) {
        if (checks.none { it.id == required }) checks += CheckResult(required, Layer.Structure, Verdict.Warning, Coverage.NotRun,
            listOf(Issue(IssueCode("CAPABILITY_UNSUPPORTED"), Severity.Warning, Layer.Structure)))
        val index = checks.indexOfFirst { it.id == required }
        val check = checks[index]
        if (check.coverage != Coverage.Complete && check.issues.none { it.code.value == "CAPABILITY_UNSUPPORTED" }) checks[index] = check.copy(verdict = if (check.verdict == Verdict.Invalid) Verdict.Invalid else Verdict.Warning,
            issues = check.issues + Issue(IssueCode("CAPABILITY_UNSUPPORTED"), Severity.Warning, check.layer))
    }
    val coverage = if (checks.isEmpty() || checks.all { it.coverage == Coverage.NotRun }) Coverage.NotRun else if (checks.any { it.coverage != Coverage.Complete }) Coverage.Partial else Coverage.Complete
    val verdict = if (checks.any { it.verdict == Verdict.Invalid || it.issues.any { issue -> issue.severity == Severity.Error } } || issues.any { it.layer in layers && it.severity == Severity.Error }) Verdict.Invalid else if (coverage != Coverage.Complete || checks.any { it.verdict == Verdict.Warning }) Verdict.Warning else Verdict.Valid
    ValidationReport(verdict, coverage, frozenList(checks), frozenList((issues.filter { it.layer in layers } + checks.flatMap { it.issues }).distinct()), session.snapshot)
}
