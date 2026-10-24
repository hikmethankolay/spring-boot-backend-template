#!/usr/bin/env bash
# Formats the code, then runs unit and integration tests (integration tests start PostgreSQL and
# Redis with Testcontainers) and checks coverage, formatting and SpotBugs.
# Usage: scripts/test.sh [--unit] [maven args...]
#   scripts/test.sh --unit                          unit tests only (*Test), no containers
#   scripts/test.sh --unit -Dtest=UserServiceTest   one unit test class
#   scripts/test.sh -Dit.test=UserIntegrationTest   unit tests and one integration test class
source "$(dirname "$0")/lib.sh"
require_jdk

./mvnw -B -q spotless:apply

if [[ "${1:-}" == "--unit" ]]; then
  shift
  exec ./mvnw -B test "$@"
fi

configure_testcontainers
args=("$@")
# A filtered run covers only part of the code, so it would fail the coverage minimums.
[[ " $* " == *" -Dtest="* || " $* " == *" -Dit.test="* ]] && args+=(-Djacoco.haltOnFailure=false)
# The ${..+..} form keeps an empty array from failing under set -u in bash before 4.4 (macOS).
./mvnw -B verify ${args[@]+"${args[@]}"}
info "Coverage report: target/site/jacoco/index.html"
