docker build -t java_includes -f java_includes.Dockerfile .
docker build -t jni_darwin -f jni_darwin.Dockerfile .
docker build -t jni_linux_x86_64 -f jni_linux_x86_64.Dockerfile .
docker build -t jni_linux_arm64 -f jni_linux_arm64.Dockerfile .
docker build -t jni_windows_x86_64 -f jni_windows_x86_64.Dockerfile .
