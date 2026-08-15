#!/bin/sh
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
image=nodel-nano-builder
gradle_cache=nodel-nano-gradle-cache
gradle_command='./gradlew --no-daemon -PnanoProfile :nodel-jyhost:nativeCompile'
builder_options=${NATIVE_IMAGE_OPTIONS:--J-Xmx13g}
source_binary="$repo_root/nodel-jyhost/build/native/nativeCompile/nodelhost-nano"
dist_dir="$repo_root/dist/nano"

docker image inspect "$image" >/dev/null 2>&1 || {
    echo "Missing $image; run scripts/nano/build-image.sh first." >&2
    exit 1
}

docker run --rm \
    --platform linux/arm64 \
    --user "$(id -u):$(id -g)" \
    --mount "type=bind,src=$repo_root,dst=/workspace" \
    --mount "type=volume,src=$gradle_cache,dst=/gradle-cache" \
    --env GRADLE_USER_HOME=/gradle-cache \
    --env HOME=/gradle-cache \
    --env npm_config_cache=/gradle-cache/npm-cache \
    --env "NATIVE_IMAGE_OPTIONS=$builder_options" \
    --workdir /workspace \
    "$image" \
    sh -c "$gradle_command"

[ -f "$source_binary" ] || {
    echo "Native build succeeded but did not produce $source_binary" >&2
    exit 1
}

mkdir -p "$dist_dir"
cp "$source_binary" "$dist_dir/nodelhost-nano"

if command -v sha256sum >/dev/null 2>&1; then
    (cd "$dist_dir" && sha256sum nodelhost-nano >SHA256SUMS)
else
    (cd "$dist_dir" && shasum -a 256 nodelhost-nano >SHA256SUMS)
fi

base_image=$(docker image inspect --format '{{ index .Config.Labels "org.nodel.nano.base-image" }}' "$image")
graalvm_sha=$(docker image inspect --format '{{ index .Config.Labels "org.nodel.nano.graalvm-sha256" }}' "$image")
git_description=$(git -C "$repo_root" describe --always --dirty --tags)
git_sha=$(git -C "$repo_root" rev-parse HEAD)
build_date=$(date -u '+%Y-%m-%dT%H:%M:%SZ')

{
    printf 'git_describe=%s\n' "$git_description"
    printf 'git_sha=%s\n' "$git_sha"
    printf 'base_image=%s\n' "$base_image"
    printf 'graalvm_tarball_sha256=%s\n' "$graalvm_sha"
    printf 'build_date_utc=%s\n' "$build_date"
    printf 'gradle_command=%s\n' "$gradle_command"
    printf 'native_image_options=%s\n' "$builder_options"
} >"$dist_dir/PROVENANCE.txt"

printf 'Built %s\n' "$dist_dir/nodelhost-nano"
