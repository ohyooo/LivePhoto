#ifndef LIVEPHOTO_POSIX_H
#define LIVEPHOTO_POSIX_H
#include <spawn.h>
#include <sys/wait.h>
#include <signal.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#ifdef __APPLE__
#include <crt_externs.h>
#else
extern char **environ;
#endif

/* OS binding shim only: opaque spawn typedefs and siginfo macros differ by SDK.
 * Policy, budgets, lifecycle, pipe draining and coroutine waits remain Kotlin. */
static inline int lp_spawn(const char *executable, char *const *argv, int input, int output) {
    posix_spawn_file_actions_t actions;
    posix_spawnattr_t attributes;
    pid_t pid = -1;
    if (posix_spawn_file_actions_init(&actions) != 0) return -1;
    if (posix_spawnattr_init(&attributes) != 0) {
        posix_spawn_file_actions_destroy(&actions); return -1;
    }
    if (posix_spawn_file_actions_addopen(&actions, 0, "/dev/null", O_RDONLY, 0) == 0 &&
        posix_spawn_file_actions_adddup2(&actions, output, 1) == 0 &&
        posix_spawn_file_actions_adddup2(&actions, output, 2) == 0 &&
        posix_spawn_file_actions_addclose(&actions, input) == 0 &&
        posix_spawn_file_actions_addclose(&actions, output) == 0 &&
        posix_spawnattr_setflags(&attributes, POSIX_SPAWN_SETPGROUP) == 0 &&
        posix_spawnattr_setpgroup(&attributes, 0) == 0) {
#ifdef __APPLE__
        char **environment = *_NSGetEnviron();
#else
        char **environment = environ;
#endif
        if (posix_spawn(&pid, executable, &actions, &attributes, argv, environment) != 0) pid = -1;
    }
    posix_spawnattr_destroy(&attributes);
    posix_spawn_file_actions_destroy(&actions);
    return (int)pid;
}

/* WNOWAIT retains PID ownership until Kotlin terminates the group then reaps. */
static inline int lp_exit(int pid, int *code) {
    siginfo_t status;
    memset(&status, 0, sizeof(status));
    if (waitid(P_PID, (id_t)pid, &status, WEXITED | WNOHANG | WNOWAIT) != 0) return -1;
    if (status.si_pid != pid) return 0;
    *code = status.si_code == CLD_EXITED ? status.si_status : 128 + status.si_status;
    return 1;
}
#endif
