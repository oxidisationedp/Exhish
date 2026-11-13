#!/bin/sh
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SRC=$ROOT/native/src
W=$ROOT/native/bbwork
NDK=${ANDROID_NDK_HOME:?}
BIN=$(echo "$NDK"/toolchains/llvm/prebuilt/*)/bin
LIST=${NATIVE_ARCHS:-"aarch64 armv7a x86_64"}
for A in $LIST; do
case "$A" in
aarch64) B=arm64-v8a; T=aarch64-linux-android21 ;;
armv7a) B=armeabi-v7a; T=armv7a-linux-androideabi21 ;;
x86_64) B=x86_64; T=x86_64-linux-android21 ;;
esac
rm -rf "$W"
mkdir -p "$W"
tar -xjf "$SRC/busybox-1.36.1.tar.bz2" -C "$W"
cd "$W/busybox-1.36.1"
for P in "$SRC"/abn/patches/*.patch; do
case "$P" in */050-*) continue;; esac
patch -p1 -s < "$P"
done
sed -i "1i #include <stdlib.h>" libbb/mntent_r.c
cp "$SRC/abn/osm0sis-full-unified.config" .config
sed -i "s/^CONFIG_TC=y/# CONFIG_TC is not set/" .config
for C in LOGREAD SYSLOGD KLOGD LOGGER ZCIP ARP ARPING ARPTABLES IP IPADDR IPLINK IPROUTE IPRULE IPNEIGH IFPLUGD IFUPDOWN UDHCPC UDHCPD UDHCPC6 DHCPRELAY DUMPLEASES NSLOOKUP PING PING6 TRACEROUTE TRACEROUTE6 ROUTE TELNET TELNETD HTTPD FTPD FTPGET FTPPUT TFTP TFTPD NC NETSTAT BRCTL VCONFIG NAMEIF ETHER_WAKE SSL_CLIENT INETD; do sed -i "s/^CONFIG_$C=y/# CONFIG_$C is not set/" .config; done
sed -i 's| -fuse-ld=bfd||; s| -frename-registers||; s| -fgcse-after-reload||' .config
sed -i "s/^CONFIG_EXTRA_CFLAGS=\"/CONFIG_EXTRA_CFLAGS=\"-Wno-error=implicit-function-declaration -Wno-implicit-function-declaration -Wno-error=int-conversion -Wno-error=incompatible-pointer-types -Wno-error=incompatible-function-pointer-types /" .config
sed -i "s/^CONFIG_EXTRA_LDFLAGS=\"/CONFIG_EXTRA_LDFLAGS=\"-Wl,--allow-multiple-definition /" .config
yes "" | make oldconfig >/dev/null
make -j2 CC="$BIN/$T-clang" HOSTCC=clang LD="$BIN/ld.lld" AR="$BIN/llvm-ar" NM="$BIN/llvm-nm" STRIP="$BIN/llvm-strip" OBJCOPY="$BIN/llvm-objcopy" busybox
"$BIN/llvm-strip" busybox
mkdir -p "$ROOT/app/src/main/jniLibs/$B"
cp busybox "$ROOT/app/src/main/jniLibs/$B/libbusybox.so"
cd "$ROOT"
done
