#ifndef TALLOC_REPLACE_SHIM_H
#define TALLOC_REPLACE_SHIM_H
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
#include <stdint.h>
#include <inttypes.h>
#include <stdbool.h>
#include <stdarg.h>
#include <string.h>
#include <strings.h>
#include <errno.h>
#include <limits.h>
#include <unistd.h>
#include <fcntl.h>
#include <time.h>
#include <ctype.h>
#include <sys/types.h>
#include <sys/stat.h>
#ifndef discard_const
#define discard_const(ptr) ((void *)((uintptr_t)(ptr)))
#endif
#ifndef discard_const_p
#define discard_const_p(type, ptr) ((type *)discard_const(ptr))
#endif
#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif
#ifndef MAX
#define MAX(a, b) ((a) > (b) ? (a) : (b))
#endif
#ifndef _PRIVATE_
#define _PRIVATE_
#endif
#ifndef _PUBLIC_
#define _PUBLIC_
#endif
#ifndef _DEPRECATED_
#define _DEPRECATED_
#endif
#ifndef _WARN_UNUSED_RESULT_
#define _WARN_UNUSED_RESULT_
#endif
#ifndef PRINTF_ATTRIBUTE
#define PRINTF_ATTRIBUTE(a, b) __attribute__((format(printf, a, b)))
#endif
#endif
