#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)

exec docker build \
    --platform linux/arm64 \
    --tag nodel-nano-builder \
    "$repo_root/docker/nano"
