#!/bin/bash
# Builds Lightning Fork for Android.
# Usage: build-lightning-fork-android.sh <amd64|arm64> <lightning-fork source dir>
# Needs ANDROID_NDK_ROOT and the Go version in the source's go.mod.
set -euo pipefail
goarch=$1
src=$2
case "$goarch" in
  amd64) arch=x86_64 ;;
  arm64) arch=aarch64 ;;
  *) echo "unknown architecture $goarch" >&2; exit 2 ;;
esac
bin="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
tags="autopilotrpc chainrpc invoicesrpc peersrpc routerrpc signrpc walletrpc watchtowerrpc wtclientrpc neutrinorpc bridgerpc offersrpc"

cd "$src"
mkdir -p "out-$goarch"
for tool in lnd lncli; do
  CGO_ENABLED=1 GOOS=android GOARCH="$goarch" \
    CC="$bin/$arch-linux-android30-clang" CXX="$bin/$arch-linux-android30-clang++" \
    go build -trimpath -tags "$tags" -ldflags "-s -w" -o "out-$goarch/$tool" "./cmd/$tool"
done
