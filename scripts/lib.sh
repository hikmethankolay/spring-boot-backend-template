#!/usr/bin/env bash
# Shared helpers for the scripts in this directory. Source it, don't run it:
#   source "$(dirname "$0")/lib.sh"

set -euo pipefail
# Locale-independent regexes, sorting and tr (e.g. [a-z] misbehaves under tr_TR).
export LC_ALL=C

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

info() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# Portable in-place sed (GNU and BSD/macOS).
sed_inplace() {
  if sed --version >/dev/null 2>&1; then sed -i "$@"; else sed -i '' "$@"; fi
}

# Fails unless a JDK 25+ is available (Maven needs javac, a JRE is not enough).
require_jdk() {
  local javac_bin="javac"
  [[ -n "${JAVA_HOME:-}" ]] && javac_bin="$JAVA_HOME/bin/javac"
  command -v "$javac_bin" >/dev/null 2>&1 \
    || die "JDK 25+ not found (javac missing). Install one, e.g. 'sudo dnf install java-25-openjdk-devel', 'sudo apt install openjdk-25-jdk' or 'sdk install java 25-tem', or set JAVA_HOME."
  local version
  # Only the "javac 25..." line: JAVA_TOOL_OPTIONS adds a "Picked up ..." line before it.
  version="$("$javac_bin" -version 2>&1 | awk '/^javac /{print $2}' | cut -d. -f1)"
  [[ "$version" =~ ^[0-9]+$ ]] && (( version >= 25 )) || die "JDK 25+ required, found '$version' ($javac_bin)."
}

# Fails unless .env exists.
require_env() {
  [[ -f .env ]] || die ".env not found. Run scripts/setup.sh first."
}

# Sets COMPOSE to a working compose command (Docker or Podman).
detect_compose() {
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    COMPOSE=(docker compose)
  elif command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then
    podman compose version >/dev/null 2>&1 \
      || die "Podman found but no compose provider. Install docker-compose or podman-compose."
    COMPOSE=(podman compose)
  else
    die "Docker or Podman is required (and must be running)."
  fi
}

# Sets CONTAINER to a working container CLI (docker or podman).
detect_container_cli() {
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    CONTAINER=(docker)
  elif command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then
    CONTAINER=(podman)
  else
    die "Docker or Podman is required (and must be running)."
  fi
}

# Points Testcontainers at rootless Podman when Docker is not usable.
configure_testcontainers() {
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    return
  fi
  command -v podman >/dev/null 2>&1 || { warn "No container runtime found; integration tests will be skipped."; return; }

  local socket="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/podman/podman.sock"
  # The socket file can outlive the service, so check the unit rather than the file.
  if command -v systemctl >/dev/null 2>&1 && ! systemctl --user is-active --quiet podman.socket; then
    info "Starting the rootless Podman API socket (systemctl --user start podman.socket)"
    systemctl --user start podman.socket || true
  fi
  if [[ -S "$socket" ]]; then
    export DOCKER_HOST="unix://$socket"
    export TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-true}"
  else
    warn "Podman socket not available; integration tests will be skipped."
  fi
}
