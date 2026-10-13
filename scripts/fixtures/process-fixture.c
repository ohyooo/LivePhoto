/* Test-only executable: exercises real argv, pipes, exit, cancellation and descendants. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
#include <windows.h>
#include <wchar.h>
#include <io.h>
#include <fcntl.h>
static void hold(void) { Sleep(5000); }
#else
#include <unistd.h>
static void hold(void) { sleep(5); }
#endif

static int fixture(int argc, char **argv) {
    if (argc < 2) return 9;
    if (!strcmp(argv[1], "echo")) {
        for (int i = 2; i < argc; i++) printf("%zu:%s\n", strlen(argv[i]), argv[i]);
    } else if (!strcmp(argv[1], "streams")) {
        fputs("stdout-marker\n", stdout); fputs("stderr-marker\n", stderr);
    } else if (!strcmp(argv[1], "fail")) return 7;
    else if (!strcmp(argv[1], "stdin")) return getchar() == EOF ? 0 : 8;
    else if (!strcmp(argv[1], "hold")) hold();
    else if (!strcmp(argv[1], "flood")) {
        char block[4096]; memset(block, 'x', sizeof(block));
        for (int i = 0; i < 256; i++) if (fwrite(block, 1, sizeof(block), stdout) != sizeof(block)) return 6;
    } else if (!strcmp(argv[1], "descendant")) {
#ifdef _WIN32
        wchar_t executable[32768], command[32768];
        if (!GetModuleFileNameW(NULL, executable, 32768)) return 10;
        if (swprintf(command, 32768, L"\"%ls\" hold", executable) < 0) return 11;
        STARTUPINFOW startup = {0}; PROCESS_INFORMATION process = {0};
        startup.cb = sizeof(startup);
        startup.dwFlags = STARTF_USESTDHANDLES;
        startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
        startup.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
        startup.hStdError = GetStdHandle(STD_ERROR_HANDLE);
        if (!CreateProcessW(executable, command, NULL, NULL, TRUE, CREATE_NO_WINDOW,
                            NULL, NULL, &startup, &process)) return 12;
        CloseHandle(process.hThread); CloseHandle(process.hProcess);
#else
        pid_t child = fork();
        if (child < 0) return 12;
        if (child == 0) { hold(); _exit(0); }
#endif
    } else return 9;
    return 0;
}
#ifdef _WIN32
int wmain(int argc, wchar_t **wide) {
    _setmode(_fileno(stdout), _O_BINARY);
    _setmode(_fileno(stderr), _O_BINARY);
    char **argv = calloc((size_t)argc, sizeof(char *));
    if (!argv) return 13;
    for (int i = 0; i < argc; i++) {
        int size = WideCharToMultiByte(CP_UTF8, 0, wide[i], -1, NULL, 0, NULL, NULL);
        argv[i] = malloc((size_t)size);
        if (!argv[i] || !WideCharToMultiByte(CP_UTF8, 0, wide[i], -1, argv[i], size, NULL, NULL)) return 13;
    }
    int result = fixture(argc, argv);
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv); return result;
}
#else
int main(int argc, char **argv) { return fixture(argc, argv); }
#endif
