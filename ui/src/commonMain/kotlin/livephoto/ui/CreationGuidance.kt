package livephoto.ui

internal enum class CreationNextStep(val label: String) {
    PickVideo("选视频"),
    PickImage("选图片"),
    ConfirmCover("重选封面"),
    ReviewTrim("查看选段"),
    TrimVideo("去裁剪"),
    ReplaceVideo("换短视频"),
}

internal data class CreationBlocker(val message: String, val next: CreationNextStep)

internal fun creationBlocker(
    hasImage: Boolean,
    hasVideo: Boolean,
    coverWarning: String?,
    pendingTrim: Boolean,
    totalSize: Long?,
    canEditVideo: Boolean,
): CreationBlocker? = when {
    !hasImage && !hasVideo -> CreationBlocker("还需选择图片和视频", CreationNextStep.PickVideo)
    !hasVideo -> CreationBlocker("已选择图片，还需选择视频", CreationNextStep.PickVideo)
    !hasImage -> CreationBlocker(
        if (canEditVideo) "已选择视频，还需选择图片或确认封面" else "已选择视频，还需选择图片",
        CreationNextStep.PickImage,
    )
    coverWarning != null -> CreationBlocker(
        coverWarning, if (canEditVideo) CreationNextStep.ConfirmCover else CreationNextStep.PickImage,
    )
    pendingTrim -> CreationBlocker("请先应用裁剪或重置选段", CreationNextStep.ReviewTrim)
    totalSize != null && totalSize >= 32L * 1024 * 1024 -> if (canEditVideo)
        CreationBlocker("图片与当前视频合计达到或超过 32 MiB，请先裁剪视频或缩短选段", CreationNextStep.TrimVideo)
    else CreationBlocker("图片与当前视频合计达到或超过 32 MiB，请更换较短的视频", CreationNextStep.ReplaceVideo)
    else -> null
}
