#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define BLK 512

static int src_fd;
static unsigned long errors;

static int readn(void *buf, size_t n) {
    size_t got = 0;
    while (got < n) {
        ssize_t r = read(src_fd, (char *)buf + got, n - got);
        if (r < 0) { if (errno == EINTR) continue; return -1; }
        if (r == 0) return got == 0 ? 0 : -1;
        got += (size_t)r;
    }
    return 1;
}

static int skipn(unsigned long long n) {
    char b[8192];
    while (n) {
        size_t c = n > sizeof b ? sizeof b : (size_t)n;
        if (readn(b, c) != 1) return -1;
        n -= c;
    }
    return 0;
}

static unsigned long long num(const char *p, size_t len) {
    if ((unsigned char)p[0] & 0x80) {
        unsigned long long v = (unsigned char)p[0] & 0x7f;
        for (size_t i = 1; i < len; i++) v = (v << 8) | (unsigned char)p[i];
        return v;
    }
    unsigned long long v = 0;
    size_t i = 0;
    while (i < len && (p[i] == ' ' || p[i] == 0)) i++;
    for (; i < len && p[i] >= '0' && p[i] <= '7'; i++) v = (v << 3) | (unsigned)(p[i] - '0');
    return v;
}

static void err(const char *what, const char *path) {
    errors++;
    dprintf(1, "untar: %s %s: %s\n", what, path, strerror(errno));
}

static const char *clean(const char *s) {
    for (;;) {
        while (*s == '/') s++;
        if (s[0] == '.' && s[1] == '/') { s += 2; continue; }
        break;
    }
    if (s[0] == '.' && s[1] == 0) return "";
    for (const char *p = s; *p; ) {
        if (p[0] == '.' && p[1] == '.' && (p[2] == '/' || p[2] == 0) && (p == s || p[-1] == '/')) return NULL;
        while (*p && *p != '/') p++;
        while (*p == '/') p++;
    }
    return s;
}

static void mkparents(char *path) {
    for (char *p = path + 1; *p; p++) {
        if (*p == '/') {
            *p = 0;
            mkdir(path, 0755);
            *p = '/';
        }
    }
}

static int copyfile(const char *from, const char *to) {
    int in = open(from, O_RDONLY);
    if (in < 0) return -1;
    struct stat st;
    if (fstat(in, &st) < 0 || !S_ISREG(st.st_mode)) { close(in); errno = EINVAL; return -1; }
    unlink(to);
    int out = open(to, O_WRONLY | O_CREAT | O_TRUNC, (st.st_mode & 07777) | 0600);
    if (out < 0) { close(in); return -1; }
    char b[16384];
    ssize_t r;
    int rc = 0;
    while ((r = read(in, b, sizeof b)) > 0) {
        if (write(out, b, (size_t)r) != r) { rc = -1; break; }
    }
    if (r < 0) rc = -1;
    fchmod(out, (st.st_mode & 07777) | 0600);
    close(in);
    close(out);
    return rc;
}

static void pax(char *d, size_t n, char *path, char *link, unsigned long long *size, int *hassize) {
    size_t i = 0;
    while (i < n) {
        size_t len = 0, j = i;
        while (j < n && d[j] >= '0' && d[j] <= '9') len = len * 10 + (size_t)(d[j++] - '0');
        if (j >= n || d[j] != ' ' || len == 0 || i + len > n) break;
        char *kv = d + j + 1;
        size_t kl = len - (j - i) - 1;
        if (kl && kv[kl - 1] == '\n') kl--;
        char *eq = memchr(kv, '=', kl);
        if (eq) {
            size_t klen = (size_t)(eq - kv), vlen = kl - klen - 1;
            if (klen == 4 && !memcmp(kv, "path", 4) && vlen < 4096) { memcpy(path, eq + 1, vlen); path[vlen] = 0; }
            else if (klen == 8 && !memcmp(kv, "linkpath", 8) && vlen < 4096) { memcpy(link, eq + 1, vlen); link[vlen] = 0; }
            else if (klen == 4 && !memcmp(kv, "size", 4)) { *size = strtoull(eq + 1, NULL, 10); *hassize = 1; }
        }
        i += len;
    }
}

int exh_untar(const char *xz, const char *busybox, const char *dest) {
    int p[2];
    if (pipe(p) < 0) { dprintf(1, "untar: pipe: %s\n", strerror(errno)); return 2; }
    pid_t cp = fork();
    if (cp < 0) { dprintf(1, "untar: fork: %s\n", strerror(errno)); return 2; }
    if (cp == 0) {
        dup2(p[1], 1);
        close(p[0]);
        close(p[1]);
        char *av[] = {"busybox", "xzcat", (char *)xz, NULL};
        execv(busybox, av);
        _exit(127);
    }
    close(p[1]);
    src_fd = p[0];

    {
        char dv[8192];
        snprintf(dv, sizeof dv, "%s/dev", dest);
        mkdir(dv, 0755);
    }
    char hdr[BLK];
    char lname[4096] = "", llink[4096] = "";
    char pname[4096] = "", plink[4096] = "";
    int haveL = 0, haveK = 0, havePax = 0;
    unsigned long long psize = 0;
    int phas = 0, fatal = 0, zero = 0;
    size_t dl = strlen(dest);
    char full[8192];

    for (;;) {
        int r = readn(hdr, BLK);
        if (r == 0) break;
        if (r < 0) { dprintf(1, "untar: truncated archive\n"); fatal = 1; break; }
        int allz = 1;
        for (int i = 0; i < BLK; i++) if (hdr[i]) { allz = 0; break; }
        if (allz) { if (++zero >= 2) break; continue; }
        zero = 0;

        char type = hdr[156];
        unsigned long long size = num(hdr + 124, 12);
        unsigned mode = (unsigned)num(hdr + 100, 8);
        unsigned long long mtime = num(hdr + 136, 12);

        if (type == 'L' || type == 'K' || type == 'x') {
            if (size > 1 << 20) { dprintf(1, "untar: oversized header\n"); fatal = 1; break; }
            char *d = malloc((size_t)size + 1);
            if (!d || readn(d, (size_t)size) != 1) { free(d); fatal = 1; break; }
            d[size] = 0;
            if (skipn((BLK - size % BLK) % BLK)) { free(d); fatal = 1; break; }
            if (type == 'L') { strncpy(lname, d, sizeof lname - 1); haveL = 1; }
            else if (type == 'K') { strncpy(llink, d, sizeof llink - 1); haveK = 1; }
            else { pax(d, (size_t)size, pname, plink, &psize, &phas); havePax = 1; }
            free(d);
            continue;
        }
        if (type == 'g') { if (skipn((size + BLK - 1) / BLK * BLK)) { fatal = 1; break; } continue; }

        char name[4096], link[4096];
        if (havePax && pname[0]) strcpy(name, pname);
        else if (haveL) strcpy(name, lname);
        else {
            char pre[156] = {0}, nm[101] = {0};
            memcpy(nm, hdr, 100);
            memcpy(pre, hdr + 345, 155);
            if (!memcmp(hdr + 257, "ustar", 5) && pre[0]) snprintf(name, sizeof name, "%s/%s", pre, nm);
            else snprintf(name, sizeof name, "%s", nm);
        }
        if (havePax && plink[0]) strcpy(link, plink);
        else if (haveK) strcpy(link, llink);
        else { memcpy(link, hdr + 157, 100); link[100] = 0; }
        if (havePax && phas) size = psize;
        haveL = haveK = havePax = 0;
        pname[0] = plink[0] = 0;
        phas = 0;

        unsigned long long pad = (size + BLK - 1) / BLK * BLK;
        const char *rel = clean(name);
        int skip = !rel || (rel[0] == 'd' && rel[1] == 'e' && rel[2] == 'v' && rel[3] == '/');
        if (!rel || !*rel) skip = 1;
        if (skip || strlen(rel) + dl + 2 > sizeof full) {
            if (skipn(pad)) { fatal = 1; break; }
            continue;
        }
        snprintf(full, sizeof full, "%s/%s", dest, rel);
        mkparents(full);
        dprintf(1, "./%s\n", rel);

        if (type == '5') {
            if (mkdir(full, (mode & 07777) | 0700) < 0 && errno != EEXIST) err("mkdir", full);
            else chmod(full, (mode & 07777) | 0700);
        } else if (type == '0' || type == 0 || type == '7') {
            unlink(full);
            int fd = open(full, O_WRONLY | O_CREAT | O_TRUNC, 0600);
            if (fd < 0) { err("create", full); if (skipn(pad)) { fatal = 1; break; } continue; }
            char b[16384];
            unsigned long long left = size;
            while (left) {
                size_t c = left > sizeof b ? sizeof b : (size_t)left;
                if (readn(b, c) != 1) { fatal = 1; break; }
                if (write(fd, b, c) != (ssize_t)c) { err("write", full); errors++; }
                left -= c;
            }
            if (fatal) { close(fd); break; }
            fchmod(fd, (mode & 07777) | 0600);
            struct timespec ts[2] = {{(time_t)mtime, 0}, {(time_t)mtime, 0}};
            futimens(fd, ts);
            close(fd);
            if (skipn(pad - size)) { fatal = 1; break; }
            continue;
        } else if (type == '2') {
            unlink(full);
            if (symlink(link, full) < 0) err("symlink", full);
        } else if (type == '1') {
            const char *lr = clean(link);
            char src[8192];
            if (!lr || !*lr) { errno = EINVAL; err("bad hardlink target", full); }
            else {
                snprintf(src, sizeof src, "%s/%s", dest, lr);
                if (copyfile(src, full) < 0) err("hardlink->copy", full);
            }
        }
        if (skipn(pad)) { fatal = 1; break; }
    }

    char junk[8192];
    while (!fatal && read(src_fd, junk, sizeof junk) > 0) {}
    close(src_fd);
    int st = 0;
    waitpid(cp, &st, 0);
    if (!fatal && !(WIFEXITED(st) && WEXITSTATUS(st) == 0)) {
        dprintf(1, "untar: xzcat failed (status %d)\n", st);
        fatal = 1;
    }
    if (errors) dprintf(1, "untar: %lu errors\n", errors);
    return fatal ? 1 : (errors > 50 ? 1 : 0);
}

#ifdef UNTAR_MAIN
int main(int c, char **v) { return exh_untar(v[1], v[2], v[3]); }
#endif
