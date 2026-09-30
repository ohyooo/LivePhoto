package livephoto.core.implementation

import livephoto.core.*
import livephoto.core.binary.*
import livephoto.core.google.*

/** Required binary checks and decoder coverage remain independent. */
internal fun validateSession(session: SourceSession, layers: List<Layer>, requiredChecks: List<String> = emptyList(), target: ProtocolSelector? = null): CoreResult<ValidationReport> = attemptNow {
    if (target != null && session.inspection.detection.matches.none { it.target.protocol == target.protocol && (target.profile == null || it.target.profile == target.profile) }) {
        fail("UNSUPPORTED_PROTOCOL", "Requested protocol/profile is absent from the inspected binding", Stage.Validate)
    }
    val issues = session.inspection.issues
    val checks = mutableListOf<CheckResult>()
    if (Layer.Structure in layers) {
        checks += CheckResult("jpeg.markers", Layer.Structure, if (session.jpeg == null) Verdict.Warning else Verdict.Valid,
            if (session.jpeg == null) Coverage.NotRun else Coverage.Complete)
        val imageIssues = issues.filter { it.layer == Layer.Structure }
        checks += CheckResult("jpeg.frame", Layer.Structure, if (imageIssues.any { it.severity == Severity.Error }) Verdict.Invalid else if (imageIssues.isNotEmpty()) Verdict.Warning else Verdict.Valid,
            if (session.jpeg == null || imageIssues.any { it.code.value == "UNSUPPORTED_CONTAINER" }) Coverage.NotRun else Coverage.Complete, imageIssues)
        val binaryIssues = issues.filter { it.layer == Layer.Structure || it.layer == Layer.Media && it.severity == Severity.Error }
        checks += CheckResult("bmff.samples", Layer.Structure, if (binaryIssues.any { it.code.value != "UNSUPPORTED_CONTAINER" }) Verdict.Invalid else Verdict.Valid,
            if (session.videos.size == session.bindings.count { it.video != null } && session.videos.isNotEmpty()) Coverage.Complete else if (session.videos.isNotEmpty()) Coverage.Partial else Coverage.NotRun, binaryIssues)
    }
    if (Layer.Protocol in layers) {
        val protocolIssues = issues.filter { it.layer == Layer.Protocol }
        checks += CheckResult("google.binding", Layer.Protocol, if (protocolIssues.any { it.severity == Severity.Error }) Verdict.Invalid else if (protocolIssues.isNotEmpty()) Verdict.Warning else Verdict.Valid,
            if (session.bindings.isEmpty()) Coverage.NotRun else if (protocolIssues.any { it.code.value == "CAPABILITY_UNSUPPORTED" }) Coverage.Partial else Coverage.Complete, protocolIssues)
        val targetIssues = session.bindings.flatMap { binding -> session.videos[binding.protocol]?.let { googleVideoIssues(it, binding.selector, false) } ?: emptyList() }
        checks += CheckResult("google.video-profile", Layer.Protocol,
            if (targetIssues.any { it.severity == Severity.Error }) Verdict.Invalid else if (targetIssues.isNotEmpty()) Verdict.Warning else Verdict.Valid,
            if (session.videos.isEmpty()) Coverage.NotRun else if (session.videos.size < session.bindings.count { it.video != null } || targetIssues.any { it.code.value == "CAPABILITY_UNSUPPORTED" }) Coverage.Partial else Coverage.Complete, targetIssues)
    }
    if (Layer.Media in layers) {
        val mediaIssues = issues.filter { it.layer == Layer.Media }
        checks += CheckResult("media.structure", Layer.Media, if (mediaIssues.any { it.severity == Severity.Error }) Verdict.Invalid else Verdict.Valid,
            if (session.videos.size == session.bindings.count { it.video != null } && session.videos.isNotEmpty()) Coverage.Complete else if (session.videos.isNotEmpty()) Coverage.Partial else Coverage.NotRun, mediaIssues)
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
