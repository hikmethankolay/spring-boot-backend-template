#!/usr/bin/env bash
# End-to-end smoke test against a running app: health, register, login, /me, the notes example, refresh,
# logout, then account deletion, so repeated runs leave no users behind.
# Usage: scripts/smoke.sh [base-url]   (default http://localhost:8080)
source "$(dirname "$0")/lib.sh"
command -v curl >/dev/null 2>&1 || die "curl is required."

base_url="${1:-http://localhost:${PORT:-8080}}"
username="smoke_$(date +%s)"
password="Smoke#12345-passphrase"
body_file="$(mktemp)"
trap 'rm -f "$body_file"' EXIT

# request METHOD PATH EXPECTED_STATUS [JSON_BODY] [TOKEN]
request() {
  local method="$1" path="$2" expected="$3" data="${4:-}" token="${5:-}"
  local args=(-s -o "$body_file" -w '%{http_code}' -X "$method" "$base_url$path")
  [[ -n "$data" ]] && args+=(-H 'Content-Type: application/json' -d "$data")
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  local status
  status="$(curl "${args[@]}")" || die "$method $path: connection failed (is the app running at $base_url?)"
  [[ "$status" == "$expected" ]] || die "$method $path: expected $expected, got $status: $(cat "$body_file")"
  info "$method $path -> $status"
}

json_field() {
  sed -n "s/.*\"$1\":\"\\([^\"]*\\)\".*/\\1/p" "$body_file"
}

json_number() {
  sed -n "s/.*\"$1\":\\([0-9]*\\).*/\\1/p" "$body_file"
}

request GET  /actuator/health 200
request POST /api/v1/auth/register 201 "{\"username\":\"$username\",\"email\":\"$username@example.com\",\"password\":\"$password\"}"
request POST /api/v1/auth/login 200 "{\"identifier\":\"$username\",\"password\":\"$password\"}"
token="$(json_field accessToken)"
refresh_token="$(json_field refreshToken)"
[[ -n "$token" && -n "$refresh_token" ]] || die "No tokens in login response."
request GET  /api/v1/users/me 200 "" "$token"
request POST /api/v1/notes 201 '{"title":"Smoke test","body":"Created by scripts/smoke.sh"}' "$token"
note_id="$(json_number id)"
[[ -n "$note_id" ]] || die "No id in the created note."
request GET  /api/v1/notes 200 "" "$token"
request PUT  "/api/v1/notes/$note_id" 200 '{"title":"Smoke test","body":"Edited"}' "$token"
request DELETE "/api/v1/notes/$note_id" 204 "" "$token"
request GET  "/api/v1/notes/$note_id" 404 "" "$token"
request POST /api/v1/auth/refresh 200 "{\"refreshToken\":\"$refresh_token\"}"
token="$(json_field accessToken)"
refresh_token="$(json_field refreshToken)"
request GET  /api/v1/users 403 "" "$token"
request GET  /api/v1/users/me 401
request POST /api/v1/auth/logout 204 "{\"refreshToken\":\"$refresh_token\"}" "$token"
request GET  /api/v1/users/me 401 "" "$token"
request POST /api/v1/auth/refresh 401 "{\"refreshToken\":\"$refresh_token\"}"
request POST /api/v1/auth/login 200 "{\"identifier\":\"$username\",\"password\":\"$password\"}"
request DELETE /api/v1/users/me 204 "{\"password\":\"$password\"}" "$(json_field accessToken)"
request POST /api/v1/auth/login 401 "{\"identifier\":\"$username\",\"password\":\"$password\"}"

info "Smoke test passed against $base_url"
