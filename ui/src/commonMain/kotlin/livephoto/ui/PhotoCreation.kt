package livephoto.ui

import livephoto.core.*

/** UI-authorized camera-marker replacement, implemented and validated only by Core. */
public fun uiCreationPolicy(target: ProtocolSelector): MutationPolicy =
    if (target.protocol == ProtocolIds.Oplus) MutationPolicy(loss = LossPolicy.AllowListed,
        allowedLosses = listOf(MetadataLoss.OplusCameraUserComment, MetadataLoss.OplusAsciiCameraUserComment)) else MutationPolicy()
