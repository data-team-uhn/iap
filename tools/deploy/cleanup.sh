#!/usr/bin/env bash
#
# Copyright 2026 DATA @ UHN. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Undoes everything generate_compose.py and `docker compose up` between them created: the
# containers, the volumes holding the repository, the image built for the mail server, and the
# generated files. What is left is a directory holding only what git tracks.
#
# This throws away the repository along with everything in it. It asks first unless given --yes.
#
# Usage:
#   ./cleanup.sh [--yes] [directory]
#
# `directory` is where the Compose file was written, for when generate_compose.py was given
# --output; it defaults to this script's own directory.

set -euo pipefail

RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; DEFAULT='\033[0m'

usage() {
    cat <<'USAGE'
Removes everything generate_compose.py and `docker compose up` between them created: the
containers, the volumes holding the repository, the image built for the mail server, and the
generated files.

Usage: ./cleanup.sh [--yes] [directory]

  -y, --yes   Do not ask for confirmation.
  directory   Where the Compose file was written, for when generate_compose.py was given
              --output. Defaults to this script's own directory.

The repository and everything stored in it is thrown away. Regenerate with generate_compose.py.
USAGE
}

ASSUME_YES=false
TARGET=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        -y|--yes)  ASSUME_YES=true ;;
        -h|--help) usage; exit 0 ;;
        -*)        echo -e "${RED}Unknown option: $1${DEFAULT}" >&2; exit 1 ;;
        *)
            [[ -n "$TARGET" ]] && { echo -e "${RED}Only one directory can be given${DEFAULT}" >&2; exit 1; }
            TARGET="$1"
            ;;
    esac
    shift
done

if [[ -z "$TARGET" ]]; then
    TARGET="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fi

if [[ ! -d "$TARGET" ]]; then
    echo -e "${RED}Not a directory: ${TARGET}${DEFAULT}" >&2
    exit 1
fi
# Resolve it before anything is deleted relative to it: every removal below names this variable,
# and a `rm -rf "$TARGET/SSL_CONFIG"` with TARGET somehow empty would mean something very
# different.
TARGET="$(cd "$TARGET" && pwd)"
if [[ "$TARGET" == "/" ]]; then
    echo -e "${RED}Refusing to clean up the filesystem root${DEFAULT}" >&2
    exit 1
fi

COMPOSE_FILE="$TARGET/docker-compose.yml"

# `docker compose` is the current form; fall back to the standalone binary for older installs.
COMPOSE=()
if docker compose version >/dev/null 2>&1; then
    COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE=(docker-compose)
fi

### What is there to remove?

GENERATED=()
for entry in docker-compose.yml .env SSL_CONFIG mail; do
    [[ -e "$TARGET/$entry" ]] && GENERATED+=("$entry")
done

echo -e "${BLUE}Cleaning up ${TARGET}${DEFAULT}"
if [[ -f "$COMPOSE_FILE" && ${#COMPOSE[@]} -gt 0 ]]; then
    echo "  - the containers, the network, and the volumes holding the repository"
    echo "  - the image built for the mail server"
fi
if [[ ${#GENERATED[@]} -gt 0 ]]; then
    echo "  - generated files: ${GENERATED[*]}"
fi
if [[ ${#GENERATED[@]} -eq 0 && ! -f "$COMPOSE_FILE" ]]; then
    echo -e "${GREEN}Nothing to clean up.${DEFAULT}"
    exit 0
fi

if [[ "$ASSUME_YES" != true ]]; then
    echo -e "${YELLOW}The repository and everything stored in it will be lost.${DEFAULT}"
    read -r -p "Continue? [y/N] " answer
    case "$answer" in
        [yY]|[yY][eE][sS]) ;;
        *) echo "Nothing was removed."; exit 0 ;;
    esac
fi

### The containers, volumes, network and built image

if [[ -f "$COMPOSE_FILE" ]]; then
    if [[ ${#COMPOSE[@]} -eq 0 ]]; then
        echo -e "${YELLOW}Docker is not available; leaving any containers and volumes alone.${DEFAULT}"
        echo -e "${YELLOW}Run this again from a machine with Docker to finish the job.${DEFAULT}"
    else
        echo -e "${BLUE}Stopping and removing the containers, volumes and network${DEFAULT}"
        # --volumes takes the named volumes with it, which is where the repository lives;
        # --rmi local removes the mail server image built from mailcatcher/ without touching the
        # pulled ones; --remove-orphans catches containers from an earlier, differently generated
        # version of this file.
        "${COMPOSE[@]}" -f "$COMPOSE_FILE" down --volumes --rmi local --remove-orphans \
            || echo -e "${YELLOW}Compose reported a problem; carrying on with the files.${DEFAULT}"
    fi
fi

### The generated files

for entry in "${GENERATED[@]}"; do
    echo -e "${BLUE}Removing ${entry}${DEFAULT}"
    rm -rf "${TARGET:?}/${entry}"
done

echo -e "${GREEN}Done. Regenerate with generate_compose.py whenever you need it again.${DEFAULT}"
