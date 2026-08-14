# syntax=docker/dockerfile:1

# Ubuntu 20.04 supplies the glibc 2.31 baseline used by the target device.
# Pin the ARM64 manifest so the toolchain base cannot drift under this profile.
FROM ubuntu:20.04@sha256:722ea796ac2d57eeb3627c58a582fc1acc58be51faf815e1bce1682ae5c092f7

ARG DEBIAN_FRONTEND=noninteractive
ARG GRAALVM_URL=https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.2.4/graalvm-community-jdk-25i2-25.0.4_linux-aarch64_bin.tar.gz
ARG GRAALVM_SHA256=22286f7ecd21b9aedb3226b9bf797469e1bd3eefc491e12ef3dd49b452d230b7

LABEL org.nodel.nano.base-image="ubuntu:20.04@sha256:722ea796ac2d57eeb3627c58a582fc1acc58be51faf815e1bce1682ae5c092f7" \
      org.nodel.nano.graalvm-sha256="${GRAALVM_SHA256}"

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        ca-certificates \
        curl \
        git \
        zlib1g-dev \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fL "${GRAALVM_URL}" -o /tmp/graalvm.tar.gz \
    && echo "${GRAALVM_SHA256}  /tmp/graalvm.tar.gz" | sha256sum -c - \
    && mkdir -p /opt/graalvm /gradle-cache /workspace \
    && tar -xzf /tmp/graalvm.tar.gz -C /opt/graalvm --strip-components=1 \
    && rm /tmp/graalvm.tar.gz \
    && chmod 0777 /gradle-cache

ENV JAVA_HOME=/opt/graalvm \
    GRAALVM_HOME=/opt/graalvm \
    PATH=/opt/graalvm/bin:${PATH}

WORKDIR /workspace
