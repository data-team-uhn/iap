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
# Configures an ALREADY-RUNNING Keycloak for IAP's OIDC sign-in: a realm, a
# confidential client, realm roles, and the realm-role -> "groups" claim mapper
# that IAP's UserInfo processor reads. It does NOT start Keycloak (bring that up
# however you like -- docker run, compose, etc.). See docs/keycloak-oidc.md.
#
# Uses the Keycloak Admin CLI (kcadm) inside the Keycloak container by default;
# set KC_CADM to a local kcadm.sh to run against a non-Docker Keycloak instead.
# Re-runnable: existing realm/client/roles/mapper are left in place.
#
# Pass --write-env to also write KEYCLOAK_CLIENT_ID/SECRET and the front-channel realm URL into
# the compose .env (created from .env.example if missing); see docs/keycloak-oidc.md.
#
# Configuration (all overridable via environment):
#   KC_CONTAINER      Keycloak container name/id for `docker exec` (default: keycloak)
#   KC_CADM           Path to a local kcadm.sh; if set, used instead of docker exec
#   KC_PUBLIC_URL     Keycloak URL as seen by external users (default: http://localhost:8084)
#   KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD   master-realm admin creds (default: admin/admin)
#   KEYCLOAK_REALM        realm to create/use (default: iap)
#   KEYCLOAK_CLIENT_ID    client id to create/use (default: iap-sling)
#   ENV_FILE              path to the .env written by --write-env (default: <script dir>/.env)
#   IAP_PUBLIC_URL        public base URL of IAP, for the redirect URI (default: http://localhost:8080)
#   KEYCLOAK_ROLES        space-separated realm roles to create (default: "reader writer admin")
#   GROUPS_CLAIM          token claim name for the roles mapper (default: groups)
#   KEYCLOAK_GENERATE_TEST_USER   set to 1 to create a test user (default: 0)
#   TEST_USER / TEST_PASSWORD / TEST_USER_ROLE   the test user (default: test/test/writer)

set -euo pipefail

RED='\033[0;31m'; YELLOW='\033[1;33m'; GREEN='\033[0;32m'; BLUE='\033[0;34m'; DEFAULT='\033[0m'

handle_error() {
    echo -e "\n${RED}AN ERROR OCCURRED DURING KEYCLOAK SETUP${DEFAULT}" >&2
    exit 1
}
trap handle_error ERR

WRITE_ENV=false
for arg in "$@"; do
    case "$arg" in
        -h|--help)
            sed -n '/^# Configures an ALREADY-RUNNING/,/^$/p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        --write-env) WRITE_ENV=true ;;
        *) echo "Unknown argument: $arg (try --help)" >&2; exit 1 ;;
    esac
done

# ---- configuration -------------------------------------------------------------
KC_CONTAINER="${KC_CONTAINER:-keycloak}"
KEYCLOAK_ADMIN="${KEYCLOAK_ADMIN:-admin}"
KEYCLOAK_ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
KC_PUBLIC_URL="${KC_PUBLIC_URL:-http://localhost:8084}"
REALM="${KEYCLOAK_REALM:-iap}"
CLIENT_ID="${KEYCLOAK_CLIENT_ID:-iap-sling}"
IAP_PUBLIC_URL="${IAP_PUBLIC_URL:-http://localhost:8080}"
ROLES="${KEYCLOAK_ROLES:-reader writer admin}"
GROUPS_CLAIM="${GROUPS_CLAIM:-groups}"
CREATE_TEST_USER="${KEYCLOAK_GENERATE_TEST_USER:-1}"
TEST_USER="${TEST_USER:-test}"
TEST_PASSWORD="${TEST_PASSWORD:-test}"
TEST_USER_ROLE="${TEST_USER_ROLE:-writer}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${ENV_FILE:-$SCRIPT_DIR/.env}"

REDIRECT_URI="${IAP_PUBLIC_URL%/}/system/sling/oauth/callback"
# Where Keycloak returns the browser after an RP-initiated logout; must match the servlet's
# post_logout_redirect_uri ($[env:IAP_PUBLIC_URL]/login) and be registered on the client.
POST_LOGOUT_REDIRECT_URI="${IAP_PUBLIC_URL%/}/login"

# ---- kcadm wrapper -------------------------------------------------------------
# Runs a kcadm command, either via a local kcadm.sh (KC_CADM) or inside the
# Keycloak container. kcadm persists its auth token on the target filesystem, so
# the login below carries across the individual invocations.
kc() {
    if [[ -n "${KC_CADM:-}" ]]; then
        "$KC_CADM" "$@"
    else
        docker exec -i "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"
    fi
}

# Replace the KEY=... line in a file (or append it if absent). The value is passed via awk -v so
# secret characters need no shell/sed escaping; comment and unrelated lines are preserved verbatim.
write_env_var() {
    local file="$1" key="$2" value="$3" tmp
    tmp="$(mktemp)"
    awk -v k="$key" -v v="$value" '
        BEGIN { FS = OFS = "=" }
        $1 == k { print k "=" v; found = 1; next }
        { print }
        END { if (!found) print k "=" v }
    ' "$file" > "$tmp" && mv "$tmp" "$file"
}

echo -e "${YELLOW}IAP KEYCLOAK SETUP${DEFAULT}"
echo    "   realm=${REALM}  client=${CLIENT_ID}  redirect=${REDIRECT_URI}"

# ---- wait for Keycloak, then authenticate --------------------------------------
# Looping on `config credentials` doubles as the readiness check: it only
# succeeds once Keycloak is up and reachable by kcadm, avoiding version-specific
# health-endpoint/port differences.
# Note the string literal localhost:8080 -- if kcadm runs from the docker container it always
# sees itself as localhost:8080
echo -n ">> waiting for Keycloak and authenticating as ${KEYCLOAK_ADMIN}"
until kc config credentials --server "http://localhost:8080" --realm master \
        --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null 2>&1; do
    printf '.'
    sleep 2
done
echo -e "\n${GREEN}authenticated${DEFAULT}"

# ---- realm ---------------------------------------------------------------------
if kc get "realms/${REALM}" >/dev/null 2>&1; then
    echo -e "${BLUE}realm '${REALM}' already exists${DEFAULT}"
else
    kc create realms -s "realm=${REALM}" -s enabled=true >/dev/null
    echo -e "${GREEN}created realm '${REALM}'${DEFAULT}"
fi

# ---- realm roles ---------------------------------------------------------------
for role in $ROLES; do
    if kc get "roles/${role}" -r "$REALM" >/dev/null 2>&1; then
        echo -e "${BLUE}role '${role}' already exists${DEFAULT}"
    else
        kc create roles -r "$REALM" -s "name=${role}" >/dev/null
        echo -e "${GREEN}created role '${role}'${DEFAULT}"
    fi
done

# ---- confidential client -------------------------------------------------------
# Look up the client's internal id; create it if absent. PKCE (S256) is enforced
# to match pkceEnabled=true in the OIDC support feature.
client_uuid() {
    kc get clients -r "$REALM" -q "clientId=${CLIENT_ID}" --fields id --format csv --noquotes 2>/dev/null | tr -d '\r' | head -n1
}
CID="$(client_uuid || true)"
if [[ -z "$CID" ]]; then
    kc create clients -r "$REALM" \
        -s "clientId=${CLIENT_ID}" \
        -s enabled=true \
        -s publicClient=false \
        -s standardFlowEnabled=true \
        -s directAccessGrantsEnabled=false \
        -s serviceAccountsEnabled=false \
        -s "redirectUris=[\"${REDIRECT_URI}\"]" \
        -s "webOrigins=[\"${IAP_PUBLIC_URL%/}\"]" \
        -s 'attributes."post.logout.redirect.uris"='"${POST_LOGOUT_REDIRECT_URI}" \
        -s 'attributes."pkce.code.challenge.method"=S256' >/dev/null
    CID="$(client_uuid)"
    echo -e "${GREEN}created client '${CLIENT_ID}'${DEFAULT}"
else
    kc update "clients/${CID}" -r "$REALM" \
        -s "redirectUris=[\"${REDIRECT_URI}\"]" \
        -s "webOrigins=[\"${IAP_PUBLIC_URL%/}\"]" \
        -s 'attributes."post.logout.redirect.uris"='"${POST_LOGOUT_REDIRECT_URI}" >/dev/null
    echo -e "${BLUE}client '${CLIENT_ID}' already exists (redirect URIs refreshed)${DEFAULT}"
fi

# ---- realm-role -> groups claim mapper -----------------------------------------
# IAP's UserInfo processor reads a flat list from the "${GROUPS_CLAIM}" claim;
# Keycloak does not emit one by default, so project the realm roles into it.
if kc get "clients/${CID}/protocol-mappers/models" -r "$REALM" --fields name --format csv --noquotes 2>/dev/null \
        | tr -d '\r' | grep -qx "${GROUPS_CLAIM}"; then
    echo -e "${BLUE}mapper '${GROUPS_CLAIM}' already exists${DEFAULT}"
else
    kc create "clients/${CID}/protocol-mappers/models" -r "$REALM" \
        -s "name=${GROUPS_CLAIM}" \
        -s protocol=openid-connect \
        -s protocolMapper=oidc-usermodel-realm-role-mapper \
        -s "config.\"claim.name\"=${GROUPS_CLAIM}" \
        -s 'config."jsonType.label"=String' \
        -s 'config."multivalued"=true' \
        -s 'config."id.token.claim"=true' \
        -s 'config."access.token.claim"=true' \
        -s 'config."userinfo.token.claim"=true' >/dev/null
    echo -e "${GREEN}created realm-role mapper into claim '${GROUPS_CLAIM}'${DEFAULT}"
fi

# ---- optional test user --------------------------------------------------------
if [[ "$CREATE_TEST_USER" == "1" ]]; then
    if kc get users -r "$REALM" -q "username=${TEST_USER}" --fields id --format csv --noquotes 2>/dev/null | tr -d '\r' | grep -q .; then
        echo -e "${BLUE}test user '${TEST_USER}' already exists${DEFAULT}"
    else
        kc create users -r "$REALM" -s "username=${TEST_USER}" -s enabled=true \
            -s "email=${TEST_USER}@example.org" -s "firstName=test" -s "lastName=test" -s emailVerified=true >/dev/null
        kc set-password -r "$REALM" --username "$TEST_USER" --new-password "$TEST_PASSWORD" >/dev/null
        kc add-roles -r "$REALM" --uusername "$TEST_USER" --rolename "$TEST_USER_ROLE" >/dev/null
        echo -e "${GREEN}created test user '${TEST_USER}' (password '${TEST_PASSWORD}', role '${TEST_USER_ROLE}')${DEFAULT}"
    fi
fi

# ---- report the client secret + the env block IAP needs ------------------------
SECRET="$(kc get "clients/${CID}/client-secret" -r "$REALM" --fields value --format csv --noquotes 2>/dev/null | tr -d '\r' | head -n1)"

echo -e "\n${GREEN}KEYCLOAK SETUP DONE${DEFAULT}"
echo    "Set these in IAP's runtime environment (see docs/keycloak-oidc.md):"
echo -e "${YELLOW}"
echo    "  export FRONTEND_KEYCLOAK_REALM_URL=${KC_PUBLIC_URL%/}/realms/${REALM}"
echo    "  export BACKEND_KEYCLOAK_REALM_URL=${KC_PUBLIC_URL%/}/realms/${REALM} # or http://keycloak:8080/realms/${REALM} if iap is being run from Docker"
echo    "  export KEYCLOAK_CLIENT_ID=${CLIENT_ID}"
echo    "  export KEYCLOAK_CLIENT_SECRET=${SECRET}"
echo    "  export IAP_OAUTH_ENCRYPTION_PASSWORD=devpassword # replace with any actual password"
echo -e "${DEFAULT}"
echo    "(BACKEND_KEYCLOAK_BASE_URL must be the realm URL that IAP can reach, and"
echo    " FRONTEND_KEYCLOAK_BASE_URL must be the realm URL that users can reach; adjust the host if"
echo    " IAP and Keycloak are on different networks.)"

# ---- optionally sync the compose .env ------------------------------------------
if [[ "$WRITE_ENV" == true ]]; then
    if [[ ! -f "$ENV_FILE" ]]; then
        if [[ -f "$SCRIPT_DIR/.env.example" ]]; then
            cp "$SCRIPT_DIR/.env.example" "$ENV_FILE"
            echo -e "${BLUE}created ${ENV_FILE} from .env.example${DEFAULT}"
        else
            : > "$ENV_FILE"
        fi
    fi
    write_env_var "$ENV_FILE" "KEYCLOAK_CLIENT_ID" "$CLIENT_ID"
    write_env_var "$ENV_FILE" "KEYCLOAK_CLIENT_SECRET" "$SECRET"
    write_env_var "$ENV_FILE" "FRONTEND_KEYCLOAK_REALM_URL" "${KC_PUBLIC_URL%/}/realms/${REALM}"
    echo -e "${GREEN}wrote client id/secret + FRONTEND_KEYCLOAK_REALM_URL to ${ENV_FILE}${DEFAULT}"
    echo    "(BACKEND_KEYCLOAK_REALM_URL is left untouched -- it is the in-network URL, e.g."
    echo    " http://keycloak:8080/realms/${REALM}, which this script cannot infer.)"
fi
