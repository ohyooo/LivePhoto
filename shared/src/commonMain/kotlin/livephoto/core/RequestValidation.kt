package livephoto.core

/** Shared preflight for application/protocol implementations; performs no IO and never publishes. */
public object RequestValidation {
    public fun validate(request: MutationRequest): CoreResult<Unit> {
        val policy = when (request) {
            is CreateRequest -> request.policy
            is ConvertRequest -> request.policy
            is SplitRequest -> request.policy
            is RepairRequest -> request.policy
            is SetKeyRequest -> request.policy
            is ReplaceRequest -> request.policy
            is TrimRequest -> request.policy
            is RemuxRequest -> request.policy
            is TranscodeRequest -> request.policy
            is ExtractRequest, is ExtractFrameRequest -> null
        }
        if (policy != null) {
            if (policy.conflicts == ConflictPolicy.ExplicitAuthority && policy.authority == null) {
                return invalidRequest("An explicit conflict authority must be supplied")
            }
            if (policy.conflicts == ConflictPolicy.Reject && policy.authority != null) {
                return invalidRequest("Conflict authority requires ExplicitAuthority policy")
            }
            if (policy.loss == LossPolicy.RejectUnrequested && policy.allowedLosses.isNotEmpty()) {
                return invalidRequest("Listed losses require the AllowListed policy")
            }
        }
        when (request) {
            is RepairRequest -> if (!request.dryRun && request.output == null) return invalidRequest("A repair mutation requires an output transaction")
            is RemuxRequest -> if (request.target == VideoContainer.Unknown) return invalidRequest("Unknown container cannot be a remux target")
            is TranscodeRequest -> {
                if (request.policy.transcode == TranscodePolicy.Forbid) return CoreResult.Failure(
                    CoreError(IssueCode("TRANSCODE_NOT_AUTHORIZED"), Stage.Plan, "Transcoding is forbidden by policy", recoverability = Recoverability.WithDifferentPolicy),
                )
                if (request.encoding.codec == VideoCodec.Other) return unsupportedOtherCodec()
            }
            is CreateRequest -> return validatePreference(request.preference)
            is ConvertRequest -> return validatePreference(request.preference)
            else -> Unit
        }
        return CoreResult.Success(Unit)
    }

    private fun validatePreference(preference: MediaPreference): CoreResult<Unit> {
        if (preference.imageFormat == ImageFormat.Unknown || preference.videoContainer == VideoContainer.Unknown ||
            preference.videoCodec == VideoCodec.Unknown || preference.audioCodec == AudioCodec.Unknown
        ) return invalidRequest("Unknown media formats cannot be write preferences")
        if (preference.videoCodec == VideoCodec.Other || preference.audioCodec == AudioCodec.Other) return unsupportedOtherCodec()
        return CoreResult.Success(Unit)
    }
}

private fun invalidRequest(message: String): CoreResult.Failure = CoreResult.Failure(
    CoreError(IssueCode("INVALID_ARGUMENT"), Stage.Plan, message),
)

private fun unsupportedOtherCodec(): CoreResult.Failure = CoreResult.Failure(
    CoreError(IssueCode("CAPABILITY_UNSUPPORTED"), Stage.Plan, "Other codec targets require a representable codec configuration", recoverability = Recoverability.AfterImplementation),
)
