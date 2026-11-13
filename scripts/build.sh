#!/bin/sh
set -e
D=$(cd "$(dirname "$0")" && pwd)
sh "$D/fetchsrc.sh"
sh "$D/buildproot.sh"
sh "$D/buildbusybox.sh"
