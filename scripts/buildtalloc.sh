#!/bin/bash
set -e
shopt -s nullglob
. ./config
HERE=$(cd "$(dirname "$0")" && pwd)
MA=${TALLOC_V%%.*}
REST=${TALLOC_V#*.}
MI=${REST%%.*}
RE=${REST#*.}
cd "$BUILD_DIR/talloc-$TALLOC_V"
for ARCH in $ARCHS
do
set-arch $ARCH
mkdir -p "$STATIC_ROOT/include" "$STATIC_ROOT/lib"
"$CC" -O2 -fPIC -I"$HERE/talloc-inc" -I. \
-DHAVE_CONSTRUCTOR_ATTRIBUTE=1 -DHAVE_VA_COPY=1 -DTALLOC_BUILD_VERSION_MAJOR=$MA \
-DTALLOC_BUILD_VERSION_MINOR=$MI \
-DTALLOC_BUILD_VERSION_RELEASE=$RE \
-c talloc.c -o "$STATIC_ROOT/talloc.o"
rm -f "$STATIC_ROOT/lib/libtalloc.a"
"$AR" rcs "$STATIC_ROOT/lib/libtalloc.a" "$STATIC_ROOT/talloc.o"
cp -f talloc.h "$STATIC_ROOT/include"
done
