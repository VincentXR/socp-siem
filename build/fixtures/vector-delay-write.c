/* Test-only GNU/Linux interposer. Never load this into a deployed collector.
 * It makes the disk-writer completion/reader-notification race deterministic
 * without changing bytes or syscall results. Compile with:
 * cc -shared -fPIC -O2 -pthread -o vector-delay-write.so vector-delay-write.c -ldl
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static ssize_t (*underlying_write)(int, const void *, size_t);
static pthread_once_t initialized = PTHREAD_ONCE_INIT;
static void initialize(void) { underlying_write = dlsym(RTLD_NEXT, "write"); }

ssize_t write(int fd, const void *buffer, size_t length) {
    pthread_once(&initialized, initialize);
    if (!underlying_write) { errno = ENOSYS; return -1; }
    char link[64], target[4096];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
    ssize_t size = readlink(link, target, sizeof(target) - 1);
    if (size > 0) {
        target[size] = '\0';
        const char *name = strrchr(target, '/');
        if (name && strncmp(name + 1, "buffer-data-", 12) == 0 && strstr(name, ".dat")) {
            /* Fixed, bounded 100ms delay, only for Vector disk-buffer files. */
            struct timespec delay = { .tv_sec = 0, .tv_nsec = 100000000 };
            while (nanosleep(&delay, &delay) == -1 && errno == EINTR) {}
        }
    }
    return underlying_write(fd, buffer, length);
}
