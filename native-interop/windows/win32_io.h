#ifndef LIVEPHOTO_WIN32_IO_H
#define LIVEPHOTO_WIN32_IO_H
#include <windows.h>

/* Preserve LastError inside the same C call. Kotlin/Native state transitions,
 * pin release or GC must not run between an API failure and GetLastError. */
static inline int lp_win32_read(void *handle, void *buffer, unsigned int length,
                               unsigned int *count, void *offset, unsigned int *error) {
    DWORD transferred = 0;
    BOOL result = ReadFile((HANDLE)handle, buffer, (DWORD)length, &transferred, (LPOVERLAPPED)offset);
    DWORD code = result ? ERROR_SUCCESS : GetLastError();
    *count = (unsigned int)transferred;
    *error = (unsigned int)code;
    return (int)result;
}
static inline int lp_win32_write(void *handle, const void *buffer, unsigned int length,
                                unsigned int *count, void *offset, unsigned int *error) {
    DWORD transferred = 0;
    BOOL result = WriteFile((HANDLE)handle, buffer, (DWORD)length, &transferred, (LPOVERLAPPED)offset);
    DWORD code = result ? ERROR_SUCCESS : GetLastError();
    *count = (unsigned int)transferred;
    *error = (unsigned int)code;
    return (int)result;
}
static inline int lp_win32_peek(void *handle, unsigned int *available, unsigned int *error) {
    DWORD bytes = 0;
    BOOL result = PeekNamedPipe((HANDLE)handle, NULL, 0, NULL, &bytes, NULL);
    DWORD code = result ? ERROR_SUCCESS : GetLastError();
    *available = (unsigned int)bytes;
    *error = (unsigned int)code;
    return (int)result;
}
#endif
