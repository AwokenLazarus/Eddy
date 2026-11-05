#!/bin/bash
# Builds unmodified Bitcoin Knots for Android with the NDK.
# Usage: build-knots-android.sh <x86_64|aarch64> <knots source dir>
# Needs ANDROID_NDK_ROOT (tested with NDK r26d) and the usual Knots build tools.
set -euo pipefail
arch=$1
src=$2
api=30
host="$arch-linux-android$api"
sysroot="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
bin="$sysroot/bin"

# CMake's thread check needs pthread_cancel, which Android's libc doesn't have, then looks for a
# libpthread. Threads live in libc on Android, so empty stubs let that check pass.
for lib in pthread rt; do
  "$bin/llvm-ar" rcs "$sysroot/sysroot/usr/lib/$arch-linux-android/lib$lib.a"
done

cd "$src"
# The host name carries the API level: depends passes it to clang as the target.
make -C depends -j"$(nproc)" HOST="$host" \
  CC="$bin/$host-clang" CXX="$bin/$host-clang++" \
  AR="$bin/llvm-ar" RANLIB="$bin/llvm-ranlib" NM="$bin/llvm-nm" STRIP="$bin/llvm-strip" \
  OBJDUMP="$bin/llvm-objdump" OBJCOPY="$bin/llvm-objcopy" \
  NO_QT=1 NO_USDT=1 NO_QR=1 NO_UPNP=1 NO_BDB=1 NO_IPC=1
cmake -B "build-$arch" --toolchain "depends/$host/toolchain.cmake" \
  -DBUILD_GUI=OFF -DBUILD_BENCH=OFF -DBUILD_FUZZ_BINARY=OFF -DBUILD_TESTS=ON \
  -DWITH_ZMQ=ON -DENABLE_WALLET=ON -DWITH_BDB=OFF \
  -DCMAKE_EXE_LINKER_FLAGS=-static-libstdc++
cmake --build "build-$arch" -j"$(nproc)"
