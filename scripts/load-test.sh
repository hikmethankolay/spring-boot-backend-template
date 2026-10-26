#!/usr/bin/env bash
# Runs the k6 load test (load-tests/api.js) against a running app, using the k6 container image,
# so no local k6 install is needed. All requests come from one IP, so start the target app with
# RATE_LIMIT_ENABLED=false (and REQUIRE_VERIFIED_EMAIL=false), e.g.:
#   RATE_LIMIT_ENABLED=false scripts/dev.sh
# With FAILED_LOGINS=true, also raise the login lockout, or the run locks the IP out. A short lock duration
# lets the IP's failure count expire soon after the run, before the app is restarted with the normal limit:
#   RATE_LIMIT_ENABLED=false LOGIN_MAX_ATTEMPTS=1000000 LOGIN_LOCK_DURATION=1m scripts/dev.sh
#
# Usage: scripts/load-test.sh [base-url]   (default http://localhost:8080)
# Env:   PROFILE (smoke, load, stress, spike, soak or throughput; default load), VUS (peak users, default 20),
#        DURATION (time at the peak), THINK_TIME (seconds between a user's requests, default 1),
#        USERS (returning users, default 20), FAILED_LOGINS (true adds wrong-password logins).
#        ADMIN_USERNAME and ADMIN_PASSWORD (read from .env when not set) enable the admin journey.
# See load-tests/api.js for the journeys and profiles.
# On macOS/Windows with Docker Desktop use http://host.docker.internal:8080 as the base URL.
source "$(dirname "$0")/lib.sh"
detect_container_cli

base_url="${1:-http://localhost:${PORT:-8080}}"
volume_options="ro"
[[ "${CONTAINER[0]}" == "podman" ]] && volume_options="ro,Z"

if [[ -f .env ]]; then
  ADMIN_USERNAME="${ADMIN_USERNAME:-$(sed -n 's/^ADMIN_USERNAME=//p' .env)}"
  ADMIN_PASSWORD="${ADMIN_PASSWORD:-$(sed -n 's/^ADMIN_PASSWORD=//p' .env)}"
fi
export BASE_URL="$base_url" ADMIN_USERNAME="${ADMIN_USERNAME:-}" ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"

# "-e NAME" passes the variable only when it is set, so the defaults stay in api.js.
env_options=()
for name in BASE_URL MAILPIT_URL PROFILE VUS DURATION THINK_TIME USERS FAILED_LOGINS ADMIN_USERNAME ADMIN_PASSWORD; do
  env_options+=(-e "$name")
done

info "Load testing $base_url (profile ${PROFILE:-load}, ${VUS:-20} peak users)"
"${CONTAINER[@]}" run --rm --network host \
  -v "$ROOT_DIR/load-tests:/scripts:$volume_options" \
  "${env_options[@]}" \
  docker.io/grafana/k6:1.4.0 run /scripts/api.js
