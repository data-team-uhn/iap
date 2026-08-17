#!/bin/bash

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

# Rebuilds the frontend and pushes the resulting assets into a RUNNING instance, without a
# Maven cycle or a restart. Exists because Sling never re-imports a bundle's initial content
# into an existing repository, so redeploying the aggregated-frontend bundle (even with
# -PautoInstallBundle) leaves /libs/iap/resources untouched: the only ways to refresh the
# assets are a fresh data directory or posting the files directly, which is what this does.
#
# The build always wipes dist/ AND webpack's persistent cache first: stale same-named files
# in dist keep webpack from re-emitting them, and a stale cache makes RealContentHashPlugin
# fail on assets referencing chunks that no longer exist. A cold build costs ~15s.
#
# After pushing, reload the page with the browser cache BYPASSED (Ctrl+Shift+R): hashed
# assets are served as immutable, and entry files keep their names across builds.
#
# Usage (run from the repository root):
#   ./tools/dev/push-frontend.sh [options]
#
# Options:
#   --url <base>       Base URL of the running instance, default http://localhost:8080
#   --user <u:p>       Credentials for the POST servlet, default admin:admin
#   --skip-build       Push whatever is already in dist/ without rebuilding

set -o errexit
set -o nounset

BASE_URL="http://localhost:8080"
CREDENTIALS="admin:admin"
SKIP_BUILD=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --url) BASE_URL="$2"; shift 2 ;;
    --user) CREDENTIALS="$2"; shift 2 ;;
    --skip-build) SKIP_BUILD=yes; shift ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

FRONTEND="aggregated-frontend/src/main/frontend"
RESOURCES="dist/SLING-INF/content/libs/iap/resources"

if [[ ! -d "$FRONTEND/node" ]]; then
  echo "The bundled Node runtime is missing; run a Maven build once first." >&2
  exit 1
fi

if [[ -z "$SKIP_BUILD" ]]; then
  echo "Building the frontend (dist/ and webpack cache wiped for coherence) ..."
  (
    cd "$FRONTEND"
    rm -rf dist node_modules/.cache
    ./node/node node/yarn/dist/bin/yarn.js aggregate > /tmp/push-frontend-aggregate.log 2>&1 || {
      echo "Aggregating the module frontends FAILED; nothing was pushed. Last lines:" >&2
      tail -5 /tmp/push-frontend-aggregate.log >&2
      exit 1
    }
    ./node/node node_modules/.bin/webpack --mode=development > /tmp/push-frontend-webpack.log 2>&1 || {
      echo "The webpack build FAILED; nothing was pushed. Last lines:" >&2
      tail -5 /tmp/push-frontend-webpack.log >&2
      exit 1
    }
  ) || exit 1
fi

echo "Pushing assets to $BASE_URL/libs/iap/resources ..."
pushed=0
failures=0
cd "$FRONTEND/$RESOURCES"
for file in *; do
  [[ -f "$file" ]] || continue
  case "$file" in
    *.json|*.map) type="application/json" ;;
    *.js)         type="application/javascript" ;;
    *)            type="application/octet-stream" ;;
  esac
  # The URL must NOT have a trailing slash: with one, the POST servlet creates an
  # auto-named child instead of updating the file node.
  # curl itself failing (connection refused, timeout...) must not abort the $() under
  # errexit without a word — map it to status 000 and report it like an HTTP failure.
  status=$(curl --silent --output /dev/null --write-out '%{http_code}' --user "$CREDENTIALS" \
    --form "./$file=@$file;type=$type" "$BASE_URL/libs/iap/resources") || status="000"
  if [[ "$status" == "000" ]]; then
    echo "  FAILED (no connection): $file — is the instance running at $BASE_URL?" >&2
    failures=$((failures + 1))
  elif [[ "$status" -ge 300 ]]; then
    echo "  FAILED ($status): $file" >&2
    failures=$((failures + 1))
  else
    pushed=$((pushed + 1))
  fi
done

echo "Pushed $pushed files ($failures failures)."
echo "Reload with the cache bypassed (Ctrl+Shift+R) to pick up the new entry files."
[[ "$failures" -eq 0 ]]
