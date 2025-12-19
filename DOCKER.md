# Running Nodel with Docker

## Quick Start

Run Nodel with a single command:

```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/museumsvictoria/nodel/master/install.sh)"
```

Web UI available at http://localhost:8085

Override defaults with environment variables:

```bash
PORT=8086 NAME=nodel2 IMAGE=ghcr.io/museumsvictoria/nodel:2.3.0 \
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/museumsvictoria/nodel/master/install.sh)"
```

The installer pulls the image and runs it in a self-contained container. Node configurations are stored inside the container.

## Manual Docker Run

For more control, run Docker commands directly.

Pull the image:

```bash
docker pull ghcr.io/museumsvictoria/nodel:latest
```

For interactive testing (press Enter to shutdown):

```bash
docker run --rm -it -p 8085:8085 ghcr.io/museumsvictoria/nodel
```

For background/daemon mode:

```bash
docker run -d --name nodel -p 8085:8085 --restart unless-stopped \
  ghcr.io/museumsvictoria/nodel
```

Pass Nodel arguments directly after the image name:

```bash
docker run --rm -it -p 8086:8086 ghcr.io/museumsvictoria/nodel -p 8086
```

## Persistent Data

To persist node configurations on the host filesystem, add volume mounts:

```bash
docker run -d --name nodel -p 8085:8085 --restart unless-stopped \
  -v ./nodes:/app/nodes \
  -v ./recipes:/app/recipes \
  -v ./custom:/app/custom \
  ghcr.io/museumsvictoria/nodel
```

This mimics the traditional `java -jar nodel.jar` behaviour where directories appear in your working folder.

## Configuration

Nodel uses `bootstrap.json` for configuration. Mount your config file:

```bash
docker run -d --name nodel -p 8085:8085 \
  -v ./bootstrap.json:/app/bootstrap.json \
  -v ./nodes:/app/nodes \
  ghcr.io/museumsvictoria/nodel
```

### Example bootstrap.json

```json
{
  "NodelHostPort": 8085,
  "disableAdvertisements": false,
  "nodelRoot": "nodes",
  "recipesRoot": "recipes",
  "logsDirectory": "logs",
  "cacheDirectory": "cache"
}
```

### Configuration Options

| Setting | Default | Description |
|---------|---------|-------------|
| `NodelHostPort` | 8085 | HTTP port for web UI and REST API |
| `disableAdvertisements` | false | Disable multicast discovery |
| `nodelRoot` | nodes | Directory containing node scripts |
| `recipesRoot` | recipes | Directory containing recipe templates |
| `logsDirectory` | logs | Directory for log files |
| `cacheDirectory` | cache | Directory for cache files |
| `networkInterfaces` | (all) | Specific network interfaces to bind |
| `inclFilters` | (none) | Only host nodes matching patterns |
| `exclFilters` | (none) | Exclude nodes matching patterns |

Run `docker run --rm ghcr.io/museumsvictoria/nodel --help` for full options.

## Volumes

| Path | Purpose |
|------|---------|
| `/app/nodes` | Node scripts and configurations |
| `/app/recipes` | Recipe templates |
| `/app/custom` | User customizations |
| `/app/logs` | Log files (optional) |
| `/app/cache` | Cache files (optional) |
| `/app/bootstrap.json` | Configuration file (optional) |

## Permissions

The container runs as a non-root `nodel` user by default (UID/GID 1000). If you bind-mount host folders with incompatible ownership/permissions, either adjust the host permissions or run the container as root and enable permission fixing:

```bash
docker run -d --name nodel -p 8085:8085 --user 0 -e NODEL_FIX_PERMS=1 \
  -v ./nodes:/app/nodes \
  ghcr.io/museumsvictoria/nodel
```

## Network Mode

Nodel uses multicast discovery (224.0.0.252:5354) for node discovery.

- Docker Desktop (macOS/Windows): does not provide true `--network host`, so discovery across the host LAN is typically unavailable. Use bridge mode with port mappings (the default).
- Linux: you can use host networking for discovery on the host LAN:

```bash
docker run -d --name nodel --network host ghcr.io/museumsvictoria/nodel
```

## Hostname Configuration

Nodel reports its hostname in the web UI and discovery protocol. In Docker, the hostname is determined in this order:

1. DNS-resolvable hostname (normal operation)
2. `-Dnodel.hostname=` system property
3. `HOSTNAME` environment variable (Docker sets this automatically)
4. Container ID (fallback)

To set a custom hostname:

```bash
# Using Docker's --hostname flag
docker run -d --name nodel --hostname my-nodel-host -p 8085:8085 \
  ghcr.io/museumsvictoria/nodel

# Or via environment variable
docker run -d --name nodel -e HOSTNAME=my-nodel-host -p 8085:8085 \
  ghcr.io/museumsvictoria/nodel

# Or via JVM system property
docker run -d --name nodel -p 8085:8085 \
  -e JAVA_OPTS="-Dnodel.hostname=my-nodel-host" \
  ghcr.io/museumsvictoria/nodel
```

## JVM Tuning

Adjust JVM settings via the `JAVA_OPTS` environment variable:

```bash
docker run -d --name nodel -p 8085:8085 \
  -e JAVA_OPTS="-XX:MaxRAMPercentage=50.0 -Xms256m" \
  ghcr.io/museumsvictoria/nodel
```

Default: `-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0`

## Health Check

The container includes a health check that polls the REST API every 30 seconds. It automatically detects the port from Nodel's `.lastHTTPPort` file, so it works regardless of your bootstrap.json configuration.

## Development

For local development and building from source, use `docker-compose.yml`:

```bash
docker compose up --build -d
```

This builds from the local source and runs Nodel in self-contained mode.
