package livephoto.android

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

internal object AndroidPrivateCache {
    private const val INPUT_PREFIX = "livephoto-input-"
    private const val INPUT_SUFFIX = ".snapshot"
    private const val OUTPUT_PREFIX = "livephoto-output-"
    private const val OUTPUT_SUFFIX = ".stage"

    private val temporaryNames = listOf(
        temporaryName(INPUT_PREFIX, INPUT_SUFFIX),
        temporaryName(OUTPUT_PREFIX, OUTPUT_SUFFIX),
    )

    fun newInput(directory: File): File = newFile(directory, INPUT_PREFIX, INPUT_SUFFIX)
    fun newOutput(directory: File): File = newFile(directory, OUTPUT_PREFIX, OUTPUT_SUFFIX)

    // Only call on cold start of the process that owns all media operations,
    // before any operation can create files. Never run this on Activity recreation.
    fun reclaimAfterProcessDeath(directory: File) {
        try {
            directory.listFiles { _, name -> temporaryNames.any { it.matches(name) } }
                .orEmpty()
                .forEach { file ->
                    if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        // Best effort: a file may already have been evicted by Android.
                        file.delete()
                    }
                }
        } catch (_: SecurityException) {
            // Inaccessible cache must not prevent the application from starting.
        }
    }

    private fun newFile(directory: File, prefix: String, suffix: String): File =
        Files.createFile(directory.resolve("$prefix${UUID.randomUUID()}$suffix").toPath()).toFile()

    private fun temporaryName(prefix: String, suffix: String): Regex {
        val identifier = "[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}"
        // Decimal IDs cover legacy files named by Android's File.createTempFile.
        return Regex(Regex.escape(prefix) + "(?:$identifier|-?[0-9]{1,20})" + Regex.escape(suffix))
    }
}
