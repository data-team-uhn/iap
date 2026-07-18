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

# Imports a content JSON file (typically an iap:Extension) into a running IAP instance,
# without rebuilding or restarting. Wraps the Sling POST servlet's `:operation=import`,
# the same mechanism the initial-content loader uses when a bundle starts. Useful for
# iterating on extensions: deploy the JS asset with `mvn install -PautoInstallBundle`,
# and post the extension node with this script.
#
# Usage (run from the repository root):
#   ./tools/dev/extension-manager/post-extension.sh [options] <json-file> <parent-path>
#
# Arguments:
#   <json-file>    Path to the JSON node definition to import (e.g. an iap:Extension).
#   <parent-path>  The JCR path of the parent the node is created under
#                  (e.g. /Extensions/DashboardWidget). It must already exist.
#
# Options:
#   -n, --name <name>      Name for the created node (default: the JSON file name without .json).
#   -u, --url <url>        Base URL of the instance (default: $IAP_URL or http://localhost:8080).
#   -p, --password <pw>    Password for the admin user (default: $ADMIN_PASSWORD or admin).
#   -h, --help             Show this help.
#
# Example:
#   ./tools/dev/extension-manager/post-extension.sh \
#     modules/homepage/src/main/resources/SLING-INF/content/Extensions/DashboardWidget/RandomNumber.json \
#     /Extensions/DashboardWidget

set -euo pipefail

URL="${IAP_URL:-http://localhost:8080}"
PASSWORD="${ADMIN_PASSWORD:-admin}"
NAME=""

usage() {
  # Print the leading comment block (the usage docs above) without the license header.
  sed -n '/^# Imports/,/^$/p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

POSITIONAL=()
while [ $# -gt 0 ]; do
  case "$1" in
    -n|--name) NAME="$2"; shift 2 ;;
    -u|--url) URL="$2"; shift 2 ;;
    -p|--password) PASSWORD="$2"; shift 2 ;;
    -h|--help) usage 0 ;;
    -*) echo "Unknown option: $1" >&2; usage 1 ;;
    *) POSITIONAL+=("$1"); shift ;;
  esac
done

if [ "${#POSITIONAL[@]}" -ne 2 ]; then
  echo "Error: expected exactly a <json-file> and a <parent-path>." >&2
  usage 1
fi

JSON_FILE="${POSITIONAL[0]}"
PARENT_PATH="${POSITIONAL[1]}"

if [ ! -f "$JSON_FILE" ]; then
  echo "Error: JSON file not found: $JSON_FILE" >&2
  exit 1
fi

# Default the node name to the file name without its .json extension.
if [ -z "$NAME" ]; then
  NAME="$(basename "$JSON_FILE" .json)"
fi

# Strip a trailing slash from the base URL and ensure the parent path starts with one.
URL="${URL%/}"
case "$PARENT_PATH" in /*) ;; *) PARENT_PATH="/$PARENT_PATH" ;; esac

echo "Importing $JSON_FILE as '$NAME' under $PARENT_PATH on $URL ..."

# :operation=import + :contentType=json          -> parse the payload as a JSON node tree
# :name                                           -> exact node name (idempotent target)
# :replace / :replaceProperties                   -> recreate the node AND overwrite properties;
#                                                    :replaceProperties is required so typed values
#                                                    (e.g. iap:defaultOrder) override the node type's
#                                                    autocreated defaults instead of silently keeping them
# :content=<file                                  -> the file's text becomes the value of :content
STATUS="$(curl -s -o /dev/null -w '%{http_code}' -u "admin:$PASSWORD" -X POST \
  -F ":operation=import" \
  -F ":contentType=json" \
  -F ":name=$NAME" \
  -F ":replace=true" \
  -F ":replaceProperties=true" \
  -F ":content=<$JSON_FILE" \
  "$URL$PARENT_PATH")"

if [ "$STATUS" -ge 200 ] && [ "$STATUS" -lt 300 ]; then
  echo "OK ($STATUS): $URL$PARENT_PATH/$NAME"
else
  echo "Failed with HTTP $STATUS while posting to $URL$PARENT_PATH" >&2
  exit 1
fi
