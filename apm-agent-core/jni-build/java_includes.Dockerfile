FROM ubuntu
RUN apt-get update && apt-get install -y curl unzip

RUN mkdir /java_linux && cd /java_linux \
    && curl -L https://github.com/adoptium/temurin20-binaries/releases/download/jdk-20.0.1%2B9/OpenJDK20U-jdk_aarch64_linux_hotspot_20.0.1_9.tar.gz --output jdk.tar.gz \
    && tar --strip-components 1 -xvf jdk.tar.gz --wildcards jdk*/include \
    && rm jdk.tar.gz

RUN mkdir /java_darwin && cd /java_darwin \
    && curl -L https://github.com/adoptium/temurin20-binaries/releases/download/jdk-20.0.1%2B9/OpenJDK20U-jdk_x64_mac_hotspot_20.0.1_9.tar.gz --output jdk.tar.gz \
    && tar --strip-components 3 -xvf jdk.tar.gz --wildcards jdk*/include \
    && rm jdk.tar.gz

RUN mkdir /java_windows && cd /java_windows \
    && curl -L https://github.com/adoptium/temurin20-binaries/releases/download/jdk-20.0.1%2B9/OpenJDK20U-jdk_x64_windows_hotspot_20.0.1_9.zip --output jdk.zip \
    && unzip jdk.zip 'jdk*/include/**' \
    && rm jdk.zip \
    && mv jdk*/include ./ \
    && rm -r jdk*
