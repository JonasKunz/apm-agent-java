#!/bin/bash

function sharedArgs() {
  echo "-std=c++20 -O2 -ftls-model=global-dynamic -fPIC -shared -I /elastic_src/jni -o /elastic_src/resources/jvmti_agent/elastic-jvmti-$1-$2.$3 /elastic_src/jni/*.cpp"
}

function clangDarwinArgs() {
  echo "-arch $1 $(sharedArgs darwin $1 so)"
}

function gccLinuxArgs() {
  case $1 in
    arm64) tls_dialect=desc;;
    x86_64) tls_dialect=gnu2;;
    **) echo >&2 "unsupported architecture"; exit 1;;
  esac

  echo "-mtls-dialect=$tls_dialect $(sharedArgs linux $1 so)"
}

function gccWindowsArgs() {
  # Remove all symbols via -s to make the dll smaller
  # If we encounter any problems on windows we should provide a buidl without "-s" to get meaningful native stacktraces
  echo "-s $(sharedArgs windows $1 dll)"
}


DOCKER_ARGS="-it --rm -v $(realpath ./../src/main):/elastic_src"

echo "Building Darwin/arm64"
docker run $DOCKER_ARGS -e "BUILD_ARGS=$(clangDarwinArgs arm64)" jni_darwin
echo "Building Darwin/x86_64"
docker run $DOCKER_ARGS -e "BUILD_ARGS=$(clangDarwinArgs x86_64)" jni_darwin
echo "Building Linux/x86_64"
docker run $DOCKER_ARGS -e "BUILD_ARGS=$(gccLinuxArgs x86_64)" jni_linux_x86_64
echo "Building Linux/arm64"
docker run $DOCKER_ARGS -e "BUILD_ARGS=$(gccLinuxArgs arm64)" jni_linux_arm64
echo "Building Windows/x86_64"
docker run $DOCKER_ARGS -e "BUILD_ARGS=$(gccWindowsArgs x86_64)" jni_windows_x86_64
