@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package livephoto.adapters.process

import kotlinx.cinterop.*
import platform.posix.*

internal actual fun isAbsoluteExecutable(path: String): Boolean = path.startsWith("/")

internal actual fun startProcess(arguments: List<String>): ChildProcess? = memScoped {
    val descriptors = allocArray<IntVar>(2)
    if (pipe(descriptors) != 0) return null
    val input = descriptors[0]
    val output = descriptors[1]
    var transferred = false
    val actions = alloc<posix_spawn_file_actions_t>()
    val attributes = alloc<posix_spawnattr_t>()
    var actionsReady = false
    var attributesReady = false
    try {
        if (input <= 2 || output <= 2) return null
        // Only the read end is nonblocking: a writer must not lose pipe data.
        if (fcntl(input, F_SETFD, FD_CLOEXEC) != 0 || fcntl(output, F_SETFD, FD_CLOEXEC) != 0 ||
            fcntl(input, F_SETFL, O_NONBLOCK) != 0) return null
        if (posix_spawn_file_actions_init(actions.ptr) != 0) return null
        actionsReady = true
        if (posix_spawnattr_init(attributes.ptr) != 0) return null
        attributesReady = true
        if (posix_spawn_file_actions_addopen(actions.ptr, 0, "/dev/null", O_RDONLY, 0u) != 0 ||
            posix_spawn_file_actions_adddup2(actions.ptr, output, 1) != 0 ||
            posix_spawn_file_actions_adddup2(actions.ptr, output, 2) != 0 ||
            posix_spawn_file_actions_addclose(actions.ptr, input) != 0 ||
            posix_spawn_file_actions_addclose(actions.ptr, output) != 0 ||
            posix_spawnattr_setflags(attributes.ptr, POSIX_SPAWN_SETPGROUP.convert()) != 0 ||
            posix_spawnattr_setpgroup(attributes.ptr, 0) != 0) return null
        val argv = allocArray<CPointerVar<ByteVar>>(arguments.size + 1)
        arguments.forEachIndexed { index, value -> argv[index] = value.cstr.ptr }
        argv[arguments.size] = null
        val pid = alloc<pid_tVar>()
        if (posix_spawn(pid.ptr, arguments.first(), actions.ptr, attributes.ptr, argv, environ) != 0) return null
        transferred = true
        PosixChild(pid.value, input)
    } finally {
        if (attributesReady) posix_spawnattr_destroy(attributes.ptr)
        if (actionsReady) posix_spawn_file_actions_destroy(actions.ptr)
        close(output)
        if (!transferred) close(input)
    }
}

private class PosixChild(private val pid: pid_t, private val pipe: Int) : ChildProcess {
    override var failed: Boolean = false
        private set
    private var result: Int? = null
    private var terminated = false
    override fun read(buffer: ByteArray): Int = buffer.usePinned {
        val count = platform.posix.read(pipe, it.addressOf(0), buffer.size.convert()).toInt()
        when {
            count > 0 -> count
            count == 0 -> -1
            errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR -> 0
            else -> -2
        }
    }
    override fun exitCode(): Int? {
        if (result != null || failed) return result
        memScoped {
            val status = alloc<siginfo_t>()
            memset(status.ptr, 0, sizeOf<siginfo_t>().convert())
            // Retain the zombie until tree termination: its PID cannot be reused
            // for an unrelated process group between exit observation and kill.
            val waited = waitid(P_PID, pid.convert(), status.ptr, WEXITED or WNOHANG or WNOWAIT)
            if (waited == 0 && status.si_pid == pid) {
                result = if (status.si_code == CLD_EXITED) status.si_status else 128 + status.si_status
            } else if (waited < 0 && errno != EINTR) failed = true
        }
        return result
    }
    override fun terminateTree(): Boolean {
        if (terminated) return true
        // The group was allocated atomically by posix_spawn, not a post-start race.
        val success = kill(-pid, SIGKILL) == 0 || errno == ESRCH
        if (success) terminated = true
        return success
    }
    override fun close(): Boolean {
        val closed = platform.posix.close(pipe) == 0
        var reaped: Boolean
        do { reaped = waitpid(pid, null, WNOHANG) == pid } while (!reaped && errno == EINTR)
        return closed && reaped
    }
}
