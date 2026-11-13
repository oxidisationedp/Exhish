#!/bin/sh
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC=$ROOT/native/src
W=$ROOT/native/work
NDK=${ANDROID_NDK_HOME:?}
LIST=${NATIVE_ARCHS:-"aarch64 armv7a x86_64"}
rm -rf "$W"
mkdir -p "$W"
cp -a "$SRC/build-proot-android/." "$W/"
mkdir -p "$W/build"
cp -a "$SRC/proot" "$W/build/proot-0.15_release"
cp -a "$SRC/talloc-2.1.14" "$W/build/talloc-2.1.14"
cd "$W"
sed -i "s|^NDK=.*|NDK=\"$NDK\"|; s|^ARCHS=.*|ARCHS='$LIST'|" config
sed -i 's|,-Ttext=\$(LOADER_ADDRESS\$1)|,-T,$(shell sh $(LDGEN) $(LOADER_ADDRESS$1))|' build/proot-0.15_release/src/GNUmakefile
export LDGEN=$ROOT/scripts/ldgen.sh
export LDOUT=$W/ld
bash "$ROOT/scripts/buildtalloc.sh"
./make-proot-for-apk.sh
for A in $LIST; do
case "$A" in
aarch64) B=arm64-v8a ;;
armv7a) B=armeabi-v7a ;;
x86_64) B=x86_64 ;;
esac
O=$W/build/root-$A/root-apk/bin
J=$ROOT/app/src/main/jniLibs/$B
mkdir -p "$J"
cp "$O/libproot.so" "$J/libproot.so"
cp "$O/libproot-loader.so" "$J/libproot_loader.so"
done
