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

# Fills a running IAP instance with sample data for the submission dashboards: a demo schema
# (with one active version) and a batch of submissions in assorted lifecycle states, some of
# them carrying reviews so the "My review queue" widget has something to show.
#
# This has to run against a live instance rather than ship as Sling-Initial-Content because a
# submission's mandatory `schemaVersion` REFERENCE needs the schema version's UUID, which only
# exists once the schema is stored; the script imports the schema first, reads the UUID back,
# and then generates the submissions. The submissions are created with plain Sling POST
# requests rather than `:operation=import`, because the JSON content importer stores untyped
# values as strings, which Oak rejects for the typed REFERENCE property; the POST servlet's
# `@TypeHint=Reference` handles the conversion properly.
#
# All content is created with the admin user, so every submission shows up in admin's
# "My submissions"; reviews alternate between admin and another (fake) reviewer, so only some
# submissions show up in admin's "My review queue".
#
# Re-running the script is safe: nodes are imported with :replace, so the same names are
# overwritten in place.
#
# Usage (run from the repository root):
#   ./tools/dev/test-data/generate-test-data.sh [options]
#
# Options:
#   -c, --count <n>        How many submissions to create (default: 40).
#   -u, --url <url>        Base URL of the instance (default: $IAP_URL or http://localhost:8080).
#   -p, --password <pw>    Password for the admin user (default: $ADMIN_PASSWORD or admin).
#   -h, --help             Show this help.

set -euo pipefail

URL="${IAP_URL:-http://localhost:8080}"
PASSWORD="${ADMIN_PASSWORD:-admin}"
COUNT=40

usage() {
  # Print the leading comment block (the usage docs above) without the license header.
  sed -n '/^# Fills/,/^$/p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [ $# -gt 0 ]; do
  case "$1" in
    -c|--count) COUNT="$2"; shift 2 ;;
    -u|--url) URL="$2"; shift 2 ;;
    -p|--password) PASSWORD="$2"; shift 2 ;;
    -h|--help) usage 0 ;;
    *) echo "Unknown option: $1" >&2; usage 1 ;;
  esac
done

URL="${URL%/}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Imports one node tree; $1 = parent path, $2 = node name, $3 = the JSON content.
import_node() {
  local status
  status="$(curl -s -o /dev/null -w '%{http_code}' -u "admin:$PASSWORD" -X POST \
    -F ":operation=import" \
    -F ":contentType=json" \
    -F ":name=$2" \
    -F ":replace=true" \
    -F ":replaceProperties=true" \
    --form-string ":content=$3" \
    "$URL$1")"
  if [ "$status" -lt 200 ] || [ "$status" -ge 300 ]; then
    echo "Failed with HTTP $status while importing $1/$2" >&2
    exit 1
  fi
}

# The schema is only imported if not already present: replacing it would fail anyway once
# submissions reference its version (deleting a referenced node violates referential
# integrity), and skipping keeps the version's UUID stable across re-runs.
if [ "$(curl -s -o /dev/null -w '%{http_code}' -u "admin:$PASSWORD" "$URL/Schemas/DemoStudy.json")" = 200 ]; then
  echo "/Schemas/DemoStudy already exists, keeping it as-is."
else
  echo "Importing the demo schema as /Schemas/DemoStudy ..."
  import_node "/Schemas" "DemoStudy" "$(cat "$SCRIPT_DIR/DemoStudy.json")"
fi

VERSION_UUID="$(curl -s -u "admin:$PASSWORD" "$URL/Schemas/DemoStudy/1.0.json" \
  | python3 -c "import json, sys; print(json.load(sys.stdin)['jcr:uuid'])")"
echo "Schema version 1.0 has UUID $VERSION_UUID"

# Creates one node with the Sling POST servlet; $1 = node path, then the remaining arguments
# are curl -F property assignments. The node is deleted first (if present), because a plain
# POST cannot change the primary type of an existing node, and the submission and its review
# must be created in separate requests: combining them makes the POST servlet auto-create the
# parent as nt:unstructured before the submission's own properties are applied.
post_node() {
  local nodepath="$1"
  shift
  curl -s -o /dev/null -u "admin:$PASSWORD" -X POST -F ":operation=delete" "$URL$nodepath" || true
  local status
  status="$(curl -s -o /dev/null -w '%{http_code}' -u "admin:$PASSWORD" -X POST "$@" \
    "$URL$nodepath")"
  if [ "$status" -lt 200 ] || [ "$status" -ge 300 ]; then
    echo "Failed with HTTP $status while creating $nodepath" >&2
    exit 1
  fi
}

# Cycles for some variety across the generated submissions
STATUSES=(draft submitted in-review in-review approved rejected)
TOPICS=("cardiac imaging" "gene therapy" "sleep patterns" "novel biomarkers" "wearable sensors"
  "immunotherapy response" "dietary interventions" "cognitive decline" "post-surgical recovery"
  "antibiotic resistance")
REVIEW_STATUSES=(in-progress changes-requested)
REVIEWERS=(admin jdoe)

echo "Creating $COUNT submissions under /Submissions ..."
for i in $(seq 1 "$COUNT"); do
  STATUS="${STATUSES[$(( i % ${#STATUSES[@]} ))]}"
  TOPIC="${TOPICS[$(( i % ${#TOPICS[@]} ))]}"
  post_node "/Submissions/demo-$i" \
    -F "jcr:primaryType=sub:Submission" \
    --form-string "title=Study #$i: ${TOPIC^}" \
    -F "status=$STATUS" \
    -F "schemaVersion=$VERSION_UUID" \
    -F "schemaVersion@TypeHint=Reference"
  # Submissions under review get an open review, alternating between admin (visible in admin's
  # review queue) and another reviewer (not visible); approved ones get a finished review.
  if [ "$STATUS" = "in-review" ]; then
    REVIEWER="${REVIEWERS[$(( i % ${#REVIEWERS[@]} ))]}"
    REVIEW_STATUS="${REVIEW_STATUSES[$(( i % ${#REVIEW_STATUSES[@]} ))]}"
    post_node "/Submissions/demo-$i/Review1" \
      -F "jcr:primaryType=sub:Review" \
      -F "reviewer=$REVIEWER" \
      -F "status=$REVIEW_STATUS"
  elif [ "$STATUS" = "approved" ]; then
    post_node "/Submissions/demo-$i/Review1" \
      -F "jcr:primaryType=sub:Review" \
      -F "reviewer=admin" \
      -F "status=approved"
  fi
done

echo "Done: /Schemas/DemoStudy plus $COUNT submissions (statuses cycling through: ${STATUSES[*]})."
