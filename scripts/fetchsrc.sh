#!/bin/sh
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
D=$ROOT/native/src
BPA=01f83b8841358450c78333d1b33ab30d4943bec4
PROOT=v0.15_release
TALLOC_SHA=b185602756a628bac507fa8af8b9df92ace69d27c0add5dab93190ad7c3367ce
mkdir -p "$D"
cd "$D"
rm -rf abn busybox-1.36.1 busybox-1.36.1.tar.bz2 build-proot-android proot talloc-2.1.14 talloc-2.1.14.tar.gz
git clone https://github.com/green-green-avk/build-proot-android
git -C build-proot-android checkout "$BPA"
git clone --branch "$PROOT" --depth 1 https://github.com/green-green-avk/proot
curl -L -o talloc-2.1.14.tar.gz https://download.samba.org/pub/talloc/talloc-2.1.14.tar.gz
echo "$TALLOC_SHA  talloc-2.1.14.tar.gz" | sha256sum -c --quiet
tar -xzf talloc-2.1.14.tar.gz
curl -L -o busybox-1.36.1.tar.bz2 https://busybox.net/downloads/busybox-1.36.1.tar.bz2
echo "b8cc24c9574d809e7279c3be349795c5d5ceb6fdf19ca709f80cde50e47de314  busybox-1.36.1.tar.bz2" | sha256sum -c --quiet
tar -xjf busybox-1.36.1.tar.bz2
git clone https://github.com/osm0sis/android-busybox-ndk abn
git -C abn checkout 3c96be250994e00fb3feb9b5a5b902e5e0222ca3
