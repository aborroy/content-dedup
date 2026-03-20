#!/usr/bin/env bash
# =============================================================================
# content-dedup — HTTP API smoke tests
#
# Exercises the duplicate-content-detection behaviour via the Alfresco REST
# API v1 using curl.  Covers:
#   - Happy path: first upload stores the SHA-256 hash aspect
#   - Duplicate detection: re-uploading identical bytes is rejected
#   - Different content: non-duplicate upload is accepted
#   - Hierarchy check: cdd:hierarchyCheckEnabled on a folder detects duplicates
#     in ancestor folders
#
# Prerequisites:
#   - curl  (https://curl.se)
#   - jq    (https://jqlang.github.io/jq/)
#   - sha256sum (Linux) or shasum -a 256 (macOS) — auto-detected below
#
# Usage:
#   HOST=http://localhost:8080 \
#   USERNAME=admin \
#   PASSWORD=admin \
#   bash http-tests/content-dedup.sh
# =============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
HOST="${HOST:-http://localhost:8080}"
USERNAME="${USERNAME:-admin}"
PASSWORD="${PASSWORD:-admin}"

BASE="${HOST}/alfresco/api/-default-/public/alfresco/versions/1"

# Detect SHA-256 utility
if command -v sha256sum &>/dev/null; then
    SHA_CMD="sha256sum"
elif command -v shasum &>/dev/null; then
    SHA_CMD="shasum -a 256"
else
    echo "ERROR: neither sha256sum nor shasum found — install coreutils" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
PASS=0; FAIL=0

ok()   { echo "  [PASS] $1"; PASS=$((PASS + 1)); }
fail() { echo "  [FAIL] $1"; FAIL=$((FAIL + 1)); }

# Execute curl; do NOT use --fail so we can inspect all status codes.
acs_curl() {
    curl --silent --show-error \
         --user "${USERNAME}:${PASSWORD}" \
         "$@"
}

# Returns the HTTP status code from a curl -w "%{http_code}" call.
http_status() {
    acs_curl --write-out "%{http_code}" --output /dev/null "$@"
}

create_folder() {
    local parent_id="$1" name="$2" aspect="${3:-}"
    local aspect_json=""
    if [ -n "$aspect" ]; then
        aspect_json=",\"aspectNames\":[\"${aspect}\"]"
    fi
    acs_curl --request POST \
             --header "Content-Type: application/json" \
             --data "{\"name\":\"${name}\",\"nodeType\":\"cm:folder\"${aspect_json}}" \
             "${BASE}/nodes/${parent_id}/children" \
    | jq -r '.entry.id'
}

upload_file() {
    local parent_id="$1" filename="$2" filepath="$3"
    acs_curl --request POST \
             --form "filedata=@${filepath};filename=${filename}" \
             "${BASE}/nodes/${parent_id}/children"
}

upload_file_status() {
    local parent_id="$1" filename="$2" filepath="$3"
    acs_curl --request POST \
             --form "filedata=@${filepath};filename=${filename}" \
             --write-out "%{http_code}" --output /dev/null \
             "${BASE}/nodes/${parent_id}/children"
}

upload_file_full() {
    # Returns both status code and body, separated by a newline sentinel.
    local parent_id="$1" filename="$2" filepath="$3"
    local body status
    body=$(acs_curl --request POST \
                    --form "filedata=@${filepath};filename=${filename}" \
                    "${BASE}/nodes/${parent_id}/children")
    status=$(acs_curl --request POST \
                      --form "filedata=@${filepath};filename=${filename}" \
                      --write-out "%{http_code}" --output /dev/null \
                      "${BASE}/nodes/${parent_id}/children" 2>/dev/null || true)
    echo "${body}|||${status}"
}

delete_node() {
    acs_curl --request DELETE "${BASE}/nodes/${1}?permanent=true" > /dev/null
}

get_node() {
    acs_curl "${BASE}/nodes/${1}?include=properties,aspectNames"
}

# ---------------------------------------------------------------------------
# Test data — written to temp files
# ---------------------------------------------------------------------------
TMP=$(mktemp -d)
trap 'rm -rf "${TMP}"' EXIT

printf "content-dedup-smoke-test-payload-A:1234567890abcdef" > "${TMP}/file-a.txt"
printf "content-dedup-smoke-test-payload-B:fedcba0987654321" > "${TMP}/file-b.txt"

EXPECTED_HASH_A=$(${SHA_CMD} "${TMP}/file-a.txt" | awk '{print $1}')
EXPECTED_HASH_B=$(${SHA_CMD} "${TMP}/file-b.txt" | awk '{print $1}')

echo ""
echo "================================================================="
echo "  content-dedup smoke tests"
echo "  Target: ${HOST}"
echo "================================================================="
echo ""

# ---------------------------------------------------------------------------
# Setup — create isolated test folder tree
# ---------------------------------------------------------------------------
echo "--- Setup ---"
STAMP=$(date +%s)
ROOT_ID=$(create_folder "-root-" "test-dedup-${STAMP}")
MAIN_ID=$(create_folder "${ROOT_ID}" "main-folder")
HIER_PARENT_ID=$(create_folder "${ROOT_ID}" "hier-parent")
HIER_CHILD_ID=$(create_folder "${HIER_PARENT_ID}" "hier-child" "cdd:hierarchyCheckEnabled")

echo "  Root test folder : ${ROOT_ID}"
echo "  Main folder      : ${MAIN_ID}"
echo "  Hier parent      : ${HIER_PARENT_ID}"
echo "  Hier child       : ${HIER_CHILD_ID}"
echo ""

# ---------------------------------------------------------------------------
# TC-01  First upload — must succeed (HTTP 201) and store hash
# ---------------------------------------------------------------------------
echo "--- TC-01: First upload succeeds and stores cdd:sha256Hash ---"

UPLOAD_RESP=$(upload_file "${MAIN_ID}" "file-a.txt" "${TMP}/file-a.txt")
UPLOAD_STATUS=$(echo "${UPLOAD_RESP}" | jq -r 'if .entry.id then "201" else "ERR" end' 2>/dev/null || echo "ERR")
FIRST_NODE_ID=$(echo "${UPLOAD_RESP}" | jq -r '.entry.id // empty')

if [ "${FIRST_NODE_ID}" = "" ]; then
    fail "TC-01a: Upload returned no node ID. Response: ${UPLOAD_RESP}"
else
    ok "TC-01a: Upload succeeded, nodeId=${FIRST_NODE_ID}"
fi

# Verify hash aspect
NODE_BODY=$(get_node "${FIRST_NODE_ID}")
HAS_ASPECT=$(echo "${NODE_BODY}" | jq -r '.entry.aspectNames[]? | select(. == "cdd:hashable")' || true)
if [ "${HAS_ASPECT}" = "cdd:hashable" ]; then
    ok "TC-01b: cdd:hashable aspect present on node"
else
    fail "TC-01b: cdd:hashable aspect NOT found. aspectNames: $(echo "${NODE_BODY}" | jq -r '.entry.aspectNames')"
fi

STORED_HASH=$(echo "${NODE_BODY}" | jq -r '.entry.properties["cdd:sha256Hash"] // empty')
if [ "${STORED_HASH}" = "${EXPECTED_HASH_A}" ]; then
    ok "TC-01c: cdd:sha256Hash matches expected SHA-256 (${EXPECTED_HASH_A})"
else
    fail "TC-01c: Hash mismatch. Expected=${EXPECTED_HASH_A}, Stored=${STORED_HASH}"
fi
echo ""

# ---------------------------------------------------------------------------
# TC-02  Duplicate upload to same folder — must be rejected
# ---------------------------------------------------------------------------
echo "--- TC-02: Duplicate upload to same folder is rejected ---"

DUP_STATUS=$(upload_file_status "${MAIN_ID}" "file-a-dup.txt" "${TMP}/file-a.txt")
DUP_BODY=$(acs_curl --request POST \
                    --form "filedata=@${TMP}/file-a.txt;filename=file-a-dup2.txt" \
                    "${BASE}/nodes/${MAIN_ID}/children" || true)

if [ "${DUP_STATUS}" -ge 400 ] 2>/dev/null; then
    ok "TC-02a: Duplicate upload rejected with HTTP ${DUP_STATUS}"
else
    fail "TC-02a: Expected 4xx/5xx for duplicate, got HTTP ${DUP_STATUS}"
fi

if echo "${DUP_BODY}" | grep -qi "duplicate"; then
    ok "TC-02b: Error response mentions 'Duplicate'"
else
    fail "TC-02b: Error body does not contain 'Duplicate'. Body: ${DUP_BODY}"
fi

if echo "${DUP_BODY}" | grep -q "${FIRST_NODE_ID}"; then
    ok "TC-02c: Error response references existing node ID (${FIRST_NODE_ID})"
else
    fail "TC-02c: Error body does not reference existing node ID. Body: ${DUP_BODY}"
fi
echo ""

# ---------------------------------------------------------------------------
# TC-03  Different content — must be accepted
# ---------------------------------------------------------------------------
echo "--- TC-03: Upload of different content succeeds ---"

DIFF_RESP=$(upload_file "${MAIN_ID}" "file-b.txt" "${TMP}/file-b.txt")
DIFF_NODE_ID=$(echo "${DIFF_RESP}" | jq -r '.entry.id // empty')

if [ -n "${DIFF_NODE_ID}" ]; then
    ok "TC-03a: Different content accepted, nodeId=${DIFF_NODE_ID}"
else
    fail "TC-03a: Upload of different content failed. Response: ${DIFF_RESP}"
fi

DIFF_HASH=$(get_node "${DIFF_NODE_ID}" | jq -r '.entry.properties["cdd:sha256Hash"] // empty')
if [ "${DIFF_HASH}" = "${EXPECTED_HASH_B}" ]; then
    ok "TC-03b: cdd:sha256Hash for CONTENT_B matches expected SHA-256"
else
    fail "TC-03b: Hash mismatch for CONTENT_B. Expected=${EXPECTED_HASH_B}, Stored=${DIFF_HASH}"
fi

delete_node "${DIFF_NODE_ID}"
echo ""

# ---------------------------------------------------------------------------
# TC-04  No hierarchy aspect — duplicate in parent must NOT be detected
# ---------------------------------------------------------------------------
echo "--- TC-04: Without cdd:hierarchyCheckEnabled, parent duplicate is not detected ---"

NO_ASPECT_CHILD=$(create_folder "${MAIN_ID}" "no-aspect-child")
NO_ASPECT_STATUS=$(upload_file_status "${NO_ASPECT_CHILD}" "file-a-in-child.txt" "${TMP}/file-a.txt")

if [ "${NO_ASPECT_STATUS}" -eq 201 ] 2>/dev/null; then
    ok "TC-04: Upload to child folder (no aspect) succeeded despite duplicate in parent (HTTP 201)"
else
    fail "TC-04: Expected HTTP 201 for child upload without hierarchy aspect, got ${NO_ASPECT_STATUS}"
fi

delete_node "${NO_ASPECT_CHILD}"
echo ""

# ---------------------------------------------------------------------------
# TC-05  Hierarchy check — duplicate in ancestor must be detected
# ---------------------------------------------------------------------------
echo "--- TC-05: With cdd:hierarchyCheckEnabled, ancestor duplicate is detected ---"

HIER_PARENT_RESP=$(upload_file "${HIER_PARENT_ID}" "file-in-parent.txt" "${TMP}/file-a.txt")
HIER_PARENT_NODE=$(echo "${HIER_PARENT_RESP}" | jq -r '.entry.id // empty')

if [ -z "${HIER_PARENT_NODE}" ]; then
    fail "TC-05: Could not seed parent folder. Skipping hierarchy check."
else
    ok "TC-05a: Seeded parent folder with CONTENT_A, nodeId=${HIER_PARENT_NODE}"

    HIER_CHILD_STATUS=$(upload_file_status "${HIER_CHILD_ID}" "file-in-child.txt" "${TMP}/file-a.txt")

    if [ "${HIER_CHILD_STATUS}" -ge 400 ] 2>/dev/null; then
        ok "TC-05b: Hierarchy duplicate detected and rejected (HTTP ${HIER_CHILD_STATUS})"
    else
        fail "TC-05b: Expected 4xx/5xx for hierarchy duplicate, got HTTP ${HIER_CHILD_STATUS}"
    fi

    delete_node "${HIER_PARENT_NODE}"
fi
echo ""

# ---------------------------------------------------------------------------
# TC-06  Authorisation — unauthenticated request must return 401
# ---------------------------------------------------------------------------
echo "--- TC-06: Unauthenticated request returns 401 ---"

UNAUTH_STATUS=$(curl --silent --write-out "%{http_code}" --output /dev/null \
                     --request GET "${BASE}/nodes/-root-")

if [ "${UNAUTH_STATUS}" -eq 401 ] 2>/dev/null; then
    ok "TC-06: Unauthenticated GET /nodes/-root- returns HTTP 401"
else
    fail "TC-06: Expected 401, got ${UNAUTH_STATUS}"
fi
echo ""

# ---------------------------------------------------------------------------
# Teardown
# ---------------------------------------------------------------------------
echo "--- Teardown ---"
delete_node "${ROOT_ID}"
echo "  Deleted root test folder ${ROOT_ID}"
echo ""

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo "================================================================="
echo "  Results: ${PASS} passed, ${FAIL} failed"
echo "================================================================="
echo ""

if [ "${FAIL}" -gt 0 ]; then
    exit 1
fi
