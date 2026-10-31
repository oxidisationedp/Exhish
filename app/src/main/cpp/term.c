#define _GNU_SOURCE
#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>

#define SB 3000
#define DFG ((int)0xFFE6E6E6)
#define DBG ((int)0xFF000000)
#define P(i, d) ((np > (i) && par[i] > 0) ? par[i] : (d))

typedef struct {
    int cp, fg, bg, at;
} Cell;

static pthread_mutex_t mu = PTHREAD_MUTEX_INITIALIZER;
static Cell *buf;
static int rows, cols, cap, used, head, cx, cy, wrapnext, rtop, rbot, curvis = 1;
static int cfg = DFG, cbg = DBG, cat, sx, sy, outfd = -1;
static int st, np, priv, uneed, ucp;
static int par[16];
static int appcur, bpaste, alt, altcx, altcy;
static Cell *altbuf;

static Cell *line(int i) {
    return buf + (size_t)((head + i) % cap) * cols;
}

static Cell *scr(int r) {
    return line(used - rows + r);
}

static void clr(Cell *p, int n) {
    for (int i = 0; i < n; i++) {
        p[i].cp = ' ';
        p[i].fg = DFG;
        p[i].bg = cbg;
        p[i].at = 0;
    }
}

static int pal(int n) {
    static const int t[16] = {
        0xFF000000, 0xFFCD0000, 0xFF00CD00, 0xFFCDCD00,
        0xFF6495ED, 0xFFCD00CD, 0xFF00CDCD, 0xFFE5E5E5,
        0xFF7F7F7F, 0xFFFF0000, 0xFF00FF00, 0xFFFFFF00,
        0xFF5C5CFF, 0xFFFF00FF, 0xFF00FFFF, 0xFFFFFFFF};
    if (n < 16) return t[n];
    if (n < 232) {
        int v = n - 16;
        int r = v / 36, g = (v / 6) % 6, b = v % 6;
        r = r ? 55 + 40 * r : 0;
        g = g ? 55 + 40 * g : 0;
        b = b ? 55 + 40 * b : 0;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
    int g = 8 + 10 * (n - 232);
    return 0xFF000000 | (g << 16) | (g << 8) | g;
}

static void scroll_up(int n) {
    for (; n > 0; n--) {
        if (rtop == 0 && rbot == rows - 1 && !alt) {
            if (used < cap) {
                used++;
                clr(line(used - 1), cols);
            } else {
                head = (head + 1) % cap;
                clr(line(used - 1), cols);
            }
        } else {
            for (int r = rtop; r < rbot; r++) memcpy(scr(r), scr(r + 1), (size_t)cols * sizeof(Cell));
            clr(scr(rbot), cols);
        }
    }
}

static void scroll_down(int n) {
    for (; n > 0; n--) {
        for (int r = rbot; r > rtop; r--) memcpy(scr(r), scr(r - 1), (size_t)cols * sizeof(Cell));
        clr(scr(rtop), cols);
    }
}

static void lf(void) {
    if (cy == rbot) scroll_up(1);
    else if (cy < rows - 1) cy++;
}

static void put(int cp) {
    if (wrapnext) {
        cx = 0;
        lf();
        wrapnext = 0;
    }
    Cell *c = scr(cy) + cx;
    c->cp = cp;
    c->fg = cfg;
    c->bg = cbg;
    c->at = cat;
    if (cx < cols - 1) cx++;
    else wrapnext = 1;
}

static void reply(const char *s) {
    if (outfd >= 0) {
        ssize_t r = write(outfd, s, strlen(s));
        (void)r;
    }
}

static void clampc(void) {
    if (cx < 0) cx = 0;
    if (cx >= cols) cx = cols - 1;
    if (cy < 0) cy = 0;
    if (cy >= rows) cy = rows - 1;
}

static void sgr(void) {
    if (!np) {
        cfg = DFG;
        cbg = DBG;
        cat = 0;
        return;
    }
    for (int i = 0; i < np; i++) {
        int v = par[i];
        if (v == 0) {
            cfg = DFG;
            cbg = DBG;
            cat = 0;
        } else if (v == 1) cat |= 1;
        else if (v == 4) cat |= 2;
        else if (v == 7) cat |= 4;
        else if (v == 22) cat &= ~1;
        else if (v == 24) cat &= ~2;
        else if (v == 27) cat &= ~4;
        else if (v >= 30 && v <= 37) cfg = pal(v - 30);
        else if (v == 39) cfg = DFG;
        else if (v >= 40 && v <= 47) cbg = pal(v - 40);
        else if (v == 49) cbg = DBG;
        else if (v >= 90 && v <= 97) cfg = pal(v - 90 + 8);
        else if (v >= 100 && v <= 107) cbg = pal(v - 100 + 8);
        else if (v == 38 || v == 48) {
            int *t = v == 38 ? &cfg : &cbg;
            if (i + 2 < np && par[i + 1] == 5) {
                *t = pal(par[i + 2] & 255);
                i += 2;
            } else if (i + 4 < np && par[i + 1] == 2) {
                *t = 0xFF000000 | ((par[i + 2] & 255) << 16) | ((par[i + 3] & 255) << 8) | (par[i + 4] & 255);
                i += 4;
            }
        }
    }
}

static void altscreen(int on) {
    if (on == alt || !buf) return;
    size_t n = (size_t)rows * cols;
    if (on) {
        free(altbuf);
        altbuf = malloc(n * sizeof(Cell));
        for (int r = 0; r < rows; r++) memcpy(altbuf + (size_t)r * cols, scr(r), (size_t)cols * sizeof(Cell));
        altcx = cx;
        altcy = cy;
        alt = 1;
        cfg = DFG;
        cbg = DBG;
        cat = 0;
        for (int r = 0; r < rows; r++) clr(scr(r), cols);
        cx = 0;
        cy = 0;
        rtop = 0;
        rbot = rows - 1;
    } else {
        alt = 0;
        if (altbuf) {
            for (int r = 0; r < rows; r++) memcpy(scr(r), altbuf + (size_t)r * cols, (size_t)cols * sizeof(Cell));
            free(altbuf);
            altbuf = NULL;
            cx = altcx;
            cy = altcy;
        } else {
            for (int r = 0; r < rows; r++) clr(scr(r), cols);
            cx = 0;
            cy = 0;
        }
        rtop = 0;
        rbot = rows - 1;
        cfg = DFG;
        cbg = DBG;
        cat = 0;
    }
    wrapnext = 0;
}

static void csi(unsigned char b) {
    int n;
    Cell *row;
    switch (b) {
    case 'A': cy -= P(0, 1); break;
    case 'B': cy += P(0, 1); break;
    case 'C': cx += P(0, 1); break;
    case 'D': cx -= P(0, 1); break;
    case 'E': cy += P(0, 1); cx = 0; break;
    case 'F': cy -= P(0, 1); cx = 0; break;
    case 'G': cx = P(0, 1) - 1; break;
    case 'd': cy = P(0, 1) - 1; break;
    case 'H':
    case 'f': cy = P(0, 1) - 1; cx = P(1, 1) - 1; break;
    case 'J':
        n = np ? par[0] : 0;
        if (n == 0) {
            clr(scr(cy) + cx, cols - cx);
            for (int r = cy + 1; r < rows; r++) clr(scr(r), cols);
        } else if (n == 1) {
            for (int r = 0; r < cy; r++) clr(scr(r), cols);
            clr(scr(cy), cx + 1);
        } else {
            for (int r = 0; r < rows; r++) clr(scr(r), cols);
        }
        break;
    case 'K':
        n = np ? par[0] : 0;
        row = scr(cy);
        if (n == 0) clr(row + cx, cols - cx);
        else if (n == 1) clr(row, cx + 1);
        else clr(row, cols);
        break;
    case 'L':
        if (cy >= rtop && cy <= rbot)
            for (int k = P(0, 1); k > 0; k--) {
                for (int r = rbot; r > cy; r--) memcpy(scr(r), scr(r - 1), (size_t)cols * sizeof(Cell));
                clr(scr(cy), cols);
            }
        break;
    case 'M':
        if (cy >= rtop && cy <= rbot)
            for (int k = P(0, 1); k > 0; k--) {
                for (int r = cy; r < rbot; r++) memcpy(scr(r), scr(r + 1), (size_t)cols * sizeof(Cell));
                clr(scr(rbot), cols);
            }
        break;
    case 'P':
        n = P(0, 1);
        if (n > cols - cx) n = cols - cx;
        row = scr(cy);
        memmove(row + cx, row + cx + n, (size_t)(cols - cx - n) * sizeof(Cell));
        clr(row + cols - n, n);
        break;
    case '@':
        n = P(0, 1);
        if (n > cols - cx) n = cols - cx;
        row = scr(cy);
        memmove(row + cx + n, row + cx, (size_t)(cols - cx - n) * sizeof(Cell));
        clr(row + cx, n);
        break;
    case 'X':
        n = P(0, 1);
        if (n > cols - cx) n = cols - cx;
        clr(scr(cy) + cx, n);
        break;
    case 'S': scroll_up(P(0, 1)); break;
    case 'T': scroll_down(P(0, 1)); break;
    case 'm': sgr(); break;
    case 'r':
        n = P(0, 1) - 1;
        {
            int bt = P(1, rows) - 1;
            if (n >= 0 && bt < rows && n < bt) {
                rtop = n;
                rbot = bt;
            } else {
                rtop = 0;
                rbot = rows - 1;
            }
        }
        cx = 0;
        cy = 0;
        break;
    case 's': sx = cx; sy = cy; break;
    case 'u': cx = sx; cy = sy; break;
    case 'n':
        if (np && par[0] == 6) {
            char t[32];
            snprintf(t, sizeof t, "\x1b[%d;%dR", cy + 1, cx + 1);
            reply(t);
        }
        break;
    case 'c':
        if (!priv) reply("\x1b[?1;2c");
        break;
    case 'h':
    case 'l':
        if (priv == '?') {
            for (int k = 0; k < np; k++) {
                int v = par[k];
                if (v == 25) curvis = (b == 'h');
                else if (v == 1) appcur = (b == 'h');
                else if (v == 2004) bpaste = (b == 'h');
                else if (v == 47 || v == 1047 || v == 1049) altscreen(b == 'h');
            }
        }
        break;
    default: break;
    }
    clampc();
    if (b != 'm') wrapnext = 0;
}

static void esc(unsigned char b) {
    switch (b) {
    case '[':
        st = 2;
        np = 0;
        priv = 0;
        memset(par, 0, sizeof par);
        return;
    case ']':
        st = 3;
        return;
    case '(':
    case ')':
    case '*':
    case '+':
        st = 4;
        return;
    case 'M':
        if (cy == rtop) scroll_down(1);
        else if (cy > 0) cy--;
        break;
    case 'D': lf(); break;
    case 'E': cx = 0; lf(); break;
    case '7': sx = cx; sy = cy; break;
    case '8': cx = sx; cy = sy; clampc(); break;
    case 'c':
        appcur = 0;
        bpaste = 0;
        curvis = 1;
        cfg = DFG;
        cbg = DBG;
        cat = 0;
        cx = 0;
        cy = 0;
        rtop = 0;
        rbot = rows - 1;
        for (int r = 0; r < rows; r++) clr(scr(r), cols);
        break;
    default: break;
    }
    st = 0;
}

static void ctl(unsigned char b) {
    switch (b) {
    case '\n':
    case 11:
    case 12: wrapnext = 0; lf(); break;
    case '\r': cx = 0; wrapnext = 0; break;
    case 8:
        if (cx > 0) cx--;
        wrapnext = 0;
        break;
    case '\t':
        cx = (cx / 8 + 1) * 8;
        if (cx >= cols) cx = cols - 1;
        break;
    default: break;
    }
}

static void feedb(unsigned char b) {
    if (st == 0) {
        if (uneed) {
            if ((b & 0xC0) == 0x80) {
                ucp = (ucp << 6) | (b & 0x3F);
                if (--uneed == 0) put(ucp);
                return;
            }
            uneed = 0;
        }
        if (b == 0x1b) {
            st = 1;
            return;
        }
        if (b < 0x20) {
            ctl(b);
            return;
        }
        if (b < 0x80) {
            put(b);
            return;
        }
        if (b >= 0xF0) {
            uneed = 3;
            ucp = b & 7;
        } else if (b >= 0xE0) {
            uneed = 2;
            ucp = b & 15;
        } else if (b >= 0xC0) {
            uneed = 1;
            ucp = b & 31;
        }
        return;
    }
    if (st == 1) {
        esc(b);
        return;
    }
    if (st == 2) {
        if (b >= '0' && b <= '9') {
            if (!np) np = 1;
            par[np - 1] = par[np - 1] * 10 + (b - '0');
        } else if (b == ';') {
            if (!np) np = 1;
            if (np < 16) np++;
        } else if (b == '?' || b == '>' || b == '<' || b == '=') {
            priv = b;
        } else if (b >= 0x40 && b <= 0x7e) {
            csi(b);
            st = 0;
        } else if (b == 0x1b) {
            st = 1;
        }
        return;
    }
    if (st == 3) {
        if (b == 7) st = 0;
        else if (b == 0x1b) st = 5;
        return;
    }
    st = 0;
}

static void feedn(const unsigned char *b, size_t n) {
    pthread_mutex_lock(&mu);
    for (size_t i = 0; i < n; i++) feedb(b[i]);
    pthread_mutex_unlock(&mu);
}

static void rs(int nr, int nc) {
    if (buf && nr == rows && nc == cols) return;
    if (alt) {
        free(altbuf);
        altbuf = NULL;
    }
    Cell *ob = buf;
    int oc = cols, orow = rows, ou = ob ? used : 0, oh = head, ocap = cap;
    int ab = ob ? ou - orow + cy : 0;
    rows = nr;
    cols = nc;
    cap = SB + nr;
    head = 0;
    buf = calloc((size_t)cap * cols, sizeof(Cell));
    int sv = cbg;
    cbg = DBG;
    for (int i = 0; i < cap; i++) clr(buf + (size_t)i * cols, cols);
    cbg = sv;
    int off = 0, nu = ou < nr ? nr : ou;
    if (nu > cap) {
        off = nu - cap;
        nu = cap;
    }
    for (int i = 0; i < ou - off; i++) {
        Cell *s = ob + (size_t)((oh + i + off) % ocap) * oc;
        memcpy(buf + (size_t)i * cols, s, (size_t)(oc < cols ? oc : cols) * sizeof(Cell));
    }
    used = nu;
    cy = ab - off - (nu - nr);
    rtop = 0;
    rbot = nr - 1;
    wrapnext = 0;
    clampc();
    free(ob);
}

static char **strv(JNIEnv *e, jobjectArray a) {
    int n = (*e)->GetArrayLength(e, a);
    char **v = calloc((size_t)n + 1, sizeof(char *));
    for (int i = 0; i < n; i++) {
        jstring s = (*e)->GetObjectArrayElement(e, a, i);
        const char *u = (*e)->GetStringUTFChars(e, s, 0);
        v[i] = strdup(u);
        (*e)->ReleaseStringUTFChars(e, s, u);
        (*e)->DeleteLocalRef(e, s);
    }
    return v;
}

int exh_untar(const char *xz, const char *busybox, const char *dest);

static void freev(char **v) {
    for (int i = 0; v[i]; i++) free(v[i]);
    free(v);
}

JNIEXPORT jstring JNICALL Java_com_exhish_Native_tarballUrl(JNIEnv *e, jclass k) {
#if defined(__x86_64__)
    return (*e)->NewStringUTF(e, "https://stages.exherbo.org/x86_64-pc-linux-gnu/exherbo-x86_64-pc-linux-gnu-gcc-current.tar.xz");
#elif defined(__aarch64__)
    return (*e)->NewStringUTF(e, "https://stages.exherbo.org/aarch64-unknown-linux-gnueabi/exherbo-aarch64-unknown-linux-gnueabi-gcc-current.tar.xz");
#elif defined(__arm__)
    return (*e)->NewStringUTF(e, "https://stages.exherbo.org/armv7-unknown-linux-gnueabihf/exherbo-armv7-unknown-linux-gnueabihf-gcc-current.tar.xz");
#else
    return (*e)->NewStringUTF(e, "");
#endif
}

JNIEXPORT void JNICALL Java_com_exhish_Native_init(JNIEnv *e, jclass k, jint r, jint c) {
    pthread_mutex_lock(&mu);
    rs(r, c);
    pthread_mutex_unlock(&mu);
}

JNIEXPORT void JNICALL Java_com_exhish_Native_resize(JNIEnv *e, jclass k, jint fd, jint r, jint c) {
    pthread_mutex_lock(&mu);
    rs(r, c);
    pthread_mutex_unlock(&mu);
    if (fd >= 0) {
        struct winsize w = {(unsigned short)r, (unsigned short)c, 0, 0};
        ioctl(fd, TIOCSWINSZ, &w);
    }
}

JNIEXPORT jintArray JNICALL Java_com_exhish_Native_spawn(JNIEnv *e, jclass k, jstring jexe, jobjectArray jargs,
                                                         jobjectArray jenv, jstring jcwd, jint r, jint c) {
    const char *ue = (*e)->GetStringUTFChars(e, jexe, 0);
    const char *uc = (*e)->GetStringUTFChars(e, jcwd, 0);
    char *exe = strdup(ue), *cwd = strdup(uc);
    (*e)->ReleaseStringUTFChars(e, jexe, ue);
    (*e)->ReleaseStringUTFChars(e, jcwd, uc);
    char **args = strv(e, jargs), **env = strv(e, jenv);
    int m = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    jint res[2] = {-1, -1};
    if (m >= 0) {
        char sn[64];
        grantpt(m);
        unlockpt(m);
        ptsname_r(m, sn, sizeof sn);
        struct termios t;
        tcgetattr(m, &t);
        t.c_iflag |= IUTF8;
        tcsetattr(m, TCSANOW, &t);
        struct winsize w = {(unsigned short)r, (unsigned short)c, 0, 0};
        ioctl(m, TIOCSWINSZ, &w);
        pid_t p = fork();
        if (p == 0) {
            setsid();
            int s = open(sn, O_RDWR);
            ioctl(s, TIOCSCTTY, 0);
            dup2(s, 0);
            dup2(s, 1);
            dup2(s, 2);
            if (s > 2) close(s);
            signal(SIGPIPE, SIG_DFL);
            signal(SIGCHLD, SIG_DFL);
            chdir(cwd);
            if (!strcmp(exe, "@untar")) _exit(exh_untar(args[1], args[2], args[3]));
            execve(exe, args, env);
            _exit(127);
        }
        res[0] = m;
        res[1] = p;
    }
    free(exe);
    free(cwd);
    freev(args);
    freev(env);
    jintArray out = (*e)->NewIntArray(e, 2);
    (*e)->SetIntArrayRegion(e, out, 0, 2, res);
    return out;
}

JNIEXPORT jint JNICALL Java_com_exhish_Native_pump(JNIEnv *e, jclass k, jint fd) {
    unsigned char b[4096];
    ssize_t n;
    do n = read(fd, b, sizeof b);
    while (n < 0 && errno == EINTR);
    if (n <= 0) return -1;
    outfd = fd;
    feedn(b, (size_t)n);
    return (jint)n;
}

JNIEXPORT void JNICALL Java_com_exhish_Native_write(JNIEnv *e, jclass k, jint fd, jbyteArray a) {
    jsize n = (*e)->GetArrayLength(e, a);
    jbyte *p = (*e)->GetByteArrayElements(e, a, 0);
    jsize off = 0;
    while (off < n) {
        ssize_t w = write(fd, p + off, (size_t)(n - off));
        if (w < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            break;
        }
        off += (jsize)w;
    }
    (*e)->ReleaseByteArrayElements(e, a, p, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_exhish_Native_feed(JNIEnv *e, jclass k, jbyteArray a) {
    jsize n = (*e)->GetArrayLength(e, a);
    jbyte *p = (*e)->GetByteArrayElements(e, a, 0);
    feedn((const unsigned char *)p, (size_t)n);
    (*e)->ReleaseByteArrayElements(e, a, p, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_exhish_Native_banner(JNIEnv *e, jclass k) {
    static const char s[] =
        "\x1b[97m"
        "\r\n"
        "         ^__^\r\n"
        "         (oo)\\_______\r\n"
        "         (__)\\       )\\/\\\r\n"
        "             ||=====w |\r\n"
        "             ||     ||\r\n"
        "\x1b[0m"
        "\r\n";
    feedn((const unsigned char *)s, sizeof s - 1);
}

JNIEXPORT void JNICALL Java_com_exhish_Native_snapshot(JNIEnv *e, jclass k, jintArray a, jint first, jint count) {
    jsize len = (*e)->GetArrayLength(e, a);
    jint *o = (*e)->GetIntArrayElements(e, a, 0);
    pthread_mutex_lock(&mu);
    for (int i = 0; i < count; i++) {
        int li = first + i;
        if (li < 0 || li >= used) continue;
        Cell *c = line(li);
        for (int j = 0; j < cols; j++) {
            size_t idx = ((size_t)i * cols + j) * 4;
            if (idx + 3 >= (size_t)len) goto done;
            int f = c[j].fg, b = c[j].bg;
            if (c[j].at & 4) {
                int t = f;
                f = b;
                b = t;
            }
            o[idx] = c[j].cp;
            o[idx + 1] = f;
            o[idx + 2] = b;
            o[idx + 3] = c[j].at;
        }
    }
done:
    pthread_mutex_unlock(&mu);
    (*e)->ReleaseIntArrayElements(e, a, o, 0);
}

JNIEXPORT void JNICALL Java_com_exhish_Native_info(JNIEnv *e, jclass k, jintArray a) {
    pthread_mutex_lock(&mu);
    jint v[7] = {used, rows, cols, cx, cy, curvis, appcur};
    pthread_mutex_unlock(&mu);
    (*e)->SetIntArrayRegion(e, a, 0, 7, v);
}

JNIEXPORT jint JNICALL Java_com_exhish_Native_modes(JNIEnv *e, jclass k) {
    pthread_mutex_lock(&mu);
    jint m = (appcur ? 1 : 0) | (bpaste ? 2 : 0) | (alt ? 4 : 0);
    pthread_mutex_unlock(&mu);
    return m;
}

JNIEXPORT jint JNICALL Java_com_exhish_Native_waitFor(JNIEnv *e, jclass k, jint pid) {
    int s = 0;
    while (waitpid(pid, &s, 0) < 0 && errno == EINTR) {
    }
    return WIFEXITED(s) ? WEXITSTATUS(s) : 128 + WTERMSIG(s);
}

JNIEXPORT void JNICALL Java_com_exhish_Native_kill(JNIEnv *e, jclass k, jint pid) {
    if (pid > 0) kill(pid, SIGHUP);
}

JNIEXPORT void JNICALL Java_com_exhish_Native_closeFd(JNIEnv *e, jclass k, jint fd) {
    close(fd);
}
