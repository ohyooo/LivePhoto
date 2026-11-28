package livephoto.ui

import livephoto.core.*

/** UI-authorized camera-marker replacement, implemented and validated only by Core. */
public fun uiCreationPolicy(target: ProtocolSelector): MutationPolicy =
    if (target.protocol == ProtocolIds.Oplus) MutationPolicy(loss = LossPolicy.AllowListed,
        allowedLosses = listOf(MetadataLoss.OplusCameraUserComment, MetadataLoss.OplusAsciiCameraUserComment)) else MutationPolicy()

/** App-generated JPEG name only; does not rename inputs or override a user-chosen path.
 * See https://developer.android.com/media/platform/motion-photo-format#filename-pattern.
 */
public fun motionPhotoJpegFileName(stem: String, protocol: ProtocolId): String =
    if (protocol == ProtocolIds.GoogleV2) "${stem}_MP.jpg" else "$stem.jpg"
