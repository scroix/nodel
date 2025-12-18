#!/bin/sh
set -e

# Optional: fix ownership of mounted volumes (can be slow on large trees).
# Enable with NODEL_FIX_PERMS=1 (runs as root before dropping privileges).
if [ "${NODEL_FIX_PERMS:-}" = "1" ] && [ "$(id -u)" = "0" ]; then
  for dir in /app/nodes /app/recipes /app/logs /app/cache /app/custom; do
    if [ -d "$dir" ]; then
      chown -R nodel:nodel "$dir" 2>/dev/null || true
    fi
  done
fi

# Verify JAR exists
if [ ! -f /app/nodelhost.jar ]; then
  echo "ERROR: nodelhost.jar not found" >&2
  exit 1
fi

# Drop privileges and run Nodel
# Configuration: mount bootstrap.json to /app/bootstrap.json (see DOCKER.md)
# "$@" passes any args to Nodel (e.g., `docker run nodel -p 8086`)
# tail -f /dev/null keeps stdin open (Nodel reads stdin; EOF triggers graceful exit)
if [ "$(id -u)" = "0" ]; then
  exec tail -f /dev/null | su-exec nodel java ${JAVA_OPTS:-} -jar /app/nodelhost.jar "$@"
else
  exec tail -f /dev/null | java ${JAVA_OPTS:-} -jar /app/nodelhost.jar "$@"
fi
