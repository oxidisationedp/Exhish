#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define K_TCGETS  0x5401
#define K_TCSETS  0x5402
#define K_TCSETSW 0x5403
#define K_TCSETSF 0x5404
#define K_CBAUD   0x100f

struct k_termios {
    unsigned int c_iflag, c_oflag, c_cflag, c_lflag;
    unsigned char c_line;
    unsigned char c_cc[19];
};

struct g_termios {
    unsigned int c_iflag, c_oflag, c_cflag, c_lflag;
    unsigned char c_line;
    unsigned char c_cc[32];
    unsigned int c_ispeed, c_ospeed;
};

int tcgetattr(int fd, struct g_termios *t) {
    struct k_termios k;
    if (ioctl(fd, K_TCGETS, &k) < 0) return -1;
    memset(t, 0, sizeof *t);
    t->c_iflag = k.c_iflag;
    t->c_oflag = k.c_oflag;
    t->c_cflag = k.c_cflag;
    t->c_lflag = k.c_lflag;
    t->c_line = k.c_line;
    memcpy(t->c_cc, k.c_cc, sizeof k.c_cc);
    t->c_ispeed = t->c_ospeed = k.c_cflag & K_CBAUD;
    return 0;
}

int tcsetattr(int fd, int act, const struct g_termios *t) {
    struct k_termios k;
    unsigned long req;
    switch (act) {
        case 0: req = K_TCSETS; break;
        case 1: req = K_TCSETSW; break;
        case 2: req = K_TCSETSF; break;
        default: errno = EINVAL; return -1;
    }
    memset(&k, 0, sizeof k);
    k.c_iflag = t->c_iflag;
    k.c_oflag = t->c_oflag;
    k.c_cflag = t->c_cflag;
    k.c_lflag = t->c_lflag;
    k.c_line = t->c_line;
    memcpy(k.c_cc, t->c_cc, sizeof k.c_cc);
    return ioctl(fd, req, &k);
}

int isatty(int fd) {
    struct k_termios k;
    if (ioctl(fd, K_TCGETS, &k) < 0) return 0;
    return 1;
}

int ttyname_r(int fd, char *buf, size_t len) {
    char p[64];
    ssize_t n;
    if (!isatty(fd)) return errno ? errno : ENOTTY;
    snprintf(p, sizeof p, "/proc/self/fd/%d", fd);
    n = readlink(p, buf, len ? len - 1 : 0);
    if (n < 0) return errno;
    buf[n] = 0;
    return 0;
}

char *ttyname(int fd) {
    static char buf[128];
    return ttyname_r(fd, buf, sizeof buf) == 0 ? buf : NULL;
}
