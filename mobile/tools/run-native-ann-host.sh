#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ann_build_dir="${HOYOUNG_ANN_HOST_BUILD_DIR:-/tmp/hoyoung-ann-host-build}"
mkdir -p "$ann_build_dir"
c++ -std=c++17 -O3 -DNDEBUG -fPIC -shared -pthread "$task_root/app/src/main/cpp/ann_c_api.cpp" -o "$ann_build_dir/libhoyoung_ann_host.so.pending"
mv "$ann_build_dir/libhoyoung_ann_host.so.pending" "$ann_build_dir/libhoyoung_ann_host.so"
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
  ann_jdk_root="$JAVA_HOME"
elif command -v javac >/dev/null 2>&1; then
  ann_jdk_root="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
fi
if [[ -n "${ann_jdk_root:-}" ]]; then
  c++ -std=c++17 -O3 -fPIC -shared -pthread -I"$ann_jdk_root/include" -I"$ann_jdk_root/include/linux" "$task_root/app/src/main/cpp/ann_jni.cpp" -o "$ann_build_dir/libhoyoung_ann.so.pending"
  # Atomic rename keeps a previous inode intact for an already-running test worker.
  mv "$ann_build_dir/libhoyoung_ann.so.pending" "$ann_build_dir/libhoyoung_ann.so"
fi
# Assertions stay enabled in the meaningful corruption/filter/precision checks.
c++ -std=c++17 -O3 -pthread "$task_root/app/src/main/cpp/ann_host_test.cpp" -o "$ann_build_dir/ann-host-test"
"$ann_build_dir/ann-host-test"
if [[ "${HOYOUNG_ANN_SANITIZE:-0}" == "1" ]]; then
  c++ -std=c++17 -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -pthread "$task_root/app/src/main/cpp/ann_host_test.cpp" -o "$ann_build_dir/ann-host-sanitized"
  "$ann_build_dir/ann-host-sanitized"
  c++ -std=c++17 -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -pthread "$task_root/app/src/main/cpp/ann_cache_fuzz_test.cpp" -o "$ann_build_dir/ann-cache-fuzz"
  "$ann_build_dir/ann-cache-fuzz"
fi
if [[ $# -gt 0 ]]; then
  python3 "$task_root/tools/benchmark-native-ann.py" --library "$ann_build_dir/libhoyoung_ann_host.so" "$@"
fi
