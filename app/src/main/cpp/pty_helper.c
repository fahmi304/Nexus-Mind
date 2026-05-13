/*
 * pty_helper — allocates a POSIX PTY and relays stdin/stdout between the
 * caller (bridge.js via spawn) and the child process.  Named libpty-helper.so
 * so Android AGP packages it in lib/<abi>/ and API 29+ can execute it.
 *
 * Usage: pty_helper <command> [args...]
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define BUF 4096

static int g_master = -1;

static void on_sigwinch(int sig) {
    (void)sig;
    struct winsize ws;
    if (g_master >= 0 && ioctl(STDIN_FILENO, TIOCGWINSZ, &ws) == 0)
        ioctl(g_master, TIOCSWINSZ, &ws);
}

static void write_all(int fd, const char *buf, ssize_t n) {
    ssize_t written = 0;
    while (written < n) {
        ssize_t w = write(fd, buf + written, (size_t)(n - written));
        if (w <= 0) return;
        written += w;
    }
}

int main(int argc, char *argv[]) {
    if (argc < 2) {
        fprintf(stderr, "usage: pty-helper <command> [args...]\n");
        return 1;
    }

    g_master = posix_openpt(O_RDWR | O_NOCTTY);
    if (g_master < 0) { perror("posix_openpt"); return 1; }
    if (grantpt(g_master) < 0)  { perror("grantpt");  return 1; }
    if (unlockpt(g_master) < 0) { perror("unlockpt"); return 1; }

    /* ptsname is not thread-safe but fine for single-threaded use here */
    char *sname = ptsname(g_master);
    if (!sname) { perror("ptsname"); return 1; }
    char slave_name[256];
    strncpy(slave_name, sname, sizeof(slave_name) - 1);
    slave_name[sizeof(slave_name) - 1] = '\0';

    /* Seed PTY window size from our own stdin if it's a tty */
    struct winsize ws;
    ws.ws_row = 24; ws.ws_col = 80; ws.ws_xpixel = 0; ws.ws_ypixel = 0;
    ioctl(STDIN_FILENO, TIOCGWINSZ, &ws);
    ioctl(g_master, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) { perror("fork"); return 1; }

    if (pid == 0) {
        /* ── child: new session + slave PTY as stdio ── */
        setsid();
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) { perror("open slave"); _exit(1); }
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        close(g_master);
        execvp(argv[1], argv + 1);
        perror("execvp");
        _exit(127);
    }

    /* ── parent: relay stdin <-> master ── */
    signal(SIGWINCH, on_sigwinch);

    char buf[BUF];
    struct pollfd fds[2];
    int status = 0;

    for (;;) {
        fds[0].fd = STDIN_FILENO; fds[0].events = POLLIN;
        fds[1].fd = g_master;     fds[1].events = POLLIN;

        int r = poll(fds, 2, 200);
        if (r < 0 && errno == EINTR) continue;

        if (r > 0) {
            if (fds[0].revents & POLLIN) {
                ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
                if (n <= 0) break;
                write_all(g_master, buf, n);
            }
            if (fds[1].revents & POLLIN) {
                ssize_t n = read(g_master, buf, sizeof(buf));
                if (n <= 0) break;
                write_all(STDOUT_FILENO, buf, n);
            }
        }

        /* non-blocking check whether child exited */
        pid_t ret = waitpid(pid, &status, WNOHANG);
        if (ret == pid) goto done;
    }

    waitpid(pid, &status, 0);
done:
    close(g_master);
    g_master = -1;
    return WIFEXITED(status) ? WEXITSTATUS(status) : 1;
}
