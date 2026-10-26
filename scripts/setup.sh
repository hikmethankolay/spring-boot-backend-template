#!/usr/bin/env bash
# One-time setup: checks prerequisites, creates .env with generated secrets and compiles the project.
# Without a JDK but with Docker or Podman, it skips compiling: scripts/docker.sh up builds and runs the app in containers.
# Usage: scripts/setup.sh [--force]   (--force recreates .env)
source "$(dirname "$0")/lib.sh"

force=false
[[ "${1:-}" == "--force" ]] && force=true

random_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -base64 "$1" | tr -d '\n/+='
  else
    head -c "$1" /dev/urandom | base64 | tr -d '\n/+='
  fi
}

info "Checking prerequisites"
jdk=true
if (detect_compose) 2>/dev/null; then
  info "Container runtime found"
  if ! (require_jdk) 2>/dev/null; then
    jdk=false
    warn "No JDK 25+ found: the app can still run in containers (scripts/docker.sh up); dev, test and build need a JDK."
  fi
else
  require_jdk
  warn "Docker or Podman not found: scripts/db.sh, scripts/docker.sh and integration tests need one."
fi

if [[ -f .env && "$force" == false ]]; then
  info ".env already exists (use --force to recreate it)"
else
  info "Creating .env from .env.example"
  cp .env.example .env
  # 18 random bytes give about 23 characters, over the 15 the password rule asks for.
  admin_password="$(random_secret 18)"
  sed_inplace "s|^JWT_SECRET=.*|JWT_SECRET=$(random_secret 48)|" .env
  sed_inplace "s|^MFA_ENCRYPTION_KEY=.*|MFA_ENCRYPTION_KEY=$(random_secret 48)|" .env
  sed_inplace "s|^ADMIN_PASSWORD=.*|ADMIN_PASSWORD=${admin_password}|" .env
  info "Generated JWT_SECRET, MFA_ENCRYPTION_KEY and an initial admin password: ${admin_password}"
  info "(stored in .env; change it after the first login)"
fi

chmod +x mvnw scripts/*.sh

if [[ "$jdk" == false ]]; then
  info "Done. Next: scripts/docker.sh up to build and start everything in containers, then scripts/smoke.sh."
  exit 0
fi

info "Compiling (downloads dependencies on first run)"
./mvnw -B -q -DskipTests compile

info "Done. Next: scripts/dev.sh to start the database and the app."
