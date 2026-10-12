package livephoto.cli.shared

import livephoto.core.*

/** CLI adapters only. Protocol parsing/planning stays in Core, never here. */
public interface CliPlatform {
    public fun openSource(path: String, context: Context): BinarySource
    /** Release adapter-owned snapshots after borrowed sources and pending output are closed.
     * Called once per run, including acquisition failures. Implementations must be reusable. */
    public fun finish(): CoreResult<Unit> = CoreResult.Success(Unit)
    public fun output(path: String, context: Context): CliOutput
    public fun discover(ffmpegPath: String?): CliDiscovery
    public fun environment(name: String): String?
    public fun nanoTime(): Long
    public fun error(message: String): Unit
    public fun exceptionType(error: Throwable): String
    /** Must contain code symbols and line numbers only; no paths/messages/arguments. */
    public fun exceptionFrames(error: Throwable): List<DiagnosticFrame>
}

public data class CliDiscovery(public val backend: MediaBackend?, public val ffmpegPath: String?, public val issues: List<Issue>)
public data class CliOutput(public val transaction: OutputTransaction, public val assetPath: (AssetId) -> String)
public data class DiagnosticFrame(public val owner: String, public val method: String, public val line: Int)
