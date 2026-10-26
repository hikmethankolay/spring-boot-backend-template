#!/usr/bin/env bash
# Turns this template into your own project: renames the Maven coordinates, the application
# name and the base Java package, and points the repository links (image, docs site, security
# advisories, code owners) to your GitHub repository. Run it once, right after copying the template.
#
# Usage: scripts/rename-project.sh [--repo owner/name] <groupId> <artifactId> [basePackage]
#   --repo defaults to the GitHub repository of the 'origin' remote.
# Example: scripts/rename-project.sh com.acme order-service com.acme.orders
source "$(dirname "$0")/lib.sh"

old_group="com.hikmethankolay"
old_artifact="spring-boot-template"
old_package="com.hikmethankolay.template"
old_owner="hikmethankolay"
old_name="spring-boot-backend-template"

repo=""
args=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) [[ $# -ge 2 ]] || die "--repo needs a value (owner/name)"; repo="$2"; shift 2 ;;
    --repo=*) repo="${1#--repo=}"; shift ;;
    *) args+=("$1"); shift ;;
  esac
done
set -- ${args[@]+"${args[@]}"}

[[ $# -ge 2 ]] || die "Usage: scripts/rename-project.sh [--repo owner/name] <groupId> <artifactId> [basePackage]"
new_group="$1"
new_artifact="$2"
new_package="${3:-$new_group.$(echo "$new_artifact" | tr -cd 'a-zA-Z0-9' | tr '[:upper:]' '[:lower:]')}"

java_name='[a-z_][a-z0-9_]*'
[[ "$new_group" =~ ^$java_name(\.$java_name)*$ ]] || die "Invalid groupId '$new_group' (e.g. com.acme)."
[[ "$new_artifact" =~ ^[a-z][a-z0-9-]*$ ]] || die "Invalid artifactId '$new_artifact' (lowercase, digits, '-')."
[[ "$new_package" =~ ^$java_name(\.$java_name)*$ ]] || die "Invalid package '$new_package' (e.g. com.acme.orders)."
grep -q "<artifactId>$old_artifact</artifactId>" pom.xml || die "Project was already renamed."

if [[ -z "$repo" ]]; then
  github_remote='github\.com[:/]([^/]+)/([^/]+)$'
  if [[ "$(git remote get-url origin 2>/dev/null || true)" =~ $github_remote ]]; then
    repo="${BASH_REMATCH[1]}/${BASH_REMATCH[2]%.git}"
  fi
fi
[[ -n "$repo" ]] || die "No GitHub 'origin' remote found. Pass your repository: --repo owner/name"
[[ "$repo" =~ ^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$ ]] || die "Invalid repository '$repo' (owner/name)."
lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }
[[ "$(lower "$repo")" != "$old_owner/$old_name" ]] \
  || die "'origin' is still the template. Create your repository (Use this template), or pass --repo owner/name."
owner="${repo%%/*}"
name="${repo#*/}"

info "Renaming $old_group:$old_artifact ($old_package) -> $new_group:$new_artifact ($new_package), repository $repo"

# Maven coordinates. The parent's groupId is org.springframework.boot, so the replacement is unambiguous.
sed_inplace -e "s|<groupId>$old_group</groupId>|<groupId>$new_group</groupId>|" pom.xml

# Application/artifact name everywhere it appears in tracked files.
grep -rlI --exclude-dir=.git --exclude-dir=target --exclude-dir=node_modules --exclude-dir=dist --exclude-dir=.astro \
  --exclude=rename-project.sh --exclude=package-lock.json "$old_artifact" . | while read -r file; do
  sed_inplace "s|$old_artifact|$new_artifact|g" "$file"
done

# Java packages: move the source trees, then rewrite package and import statements.
old_path="${old_package//.//}"
new_path="${new_package//.//}"
for root in src/main/java src/test/java; do
  [[ -d "$root/$old_path" ]] || continue
  mkdir -p "$root/$new_path"
  tmp_dir="$(mktemp -d)"
  mv "$root/$old_path"/* "$tmp_dir"/
  mv "$tmp_dir"/* "$root/$new_path"/
  rmdir "$tmp_dir"
  # Remove now-empty parent directories of the old package.
  find "$root" -type d -empty -delete
done
grep -rlI --exclude-dir=.git --exclude-dir=target --exclude-dir=node_modules --exclude-dir=dist --exclude-dir=.astro \
  --exclude=rename-project.sh --exclude=package-lock.json "$old_package" . | while read -r file; do
  sed_inplace "s|$old_package|$new_package|g" "$file"
done

# Repository links. Image names and Pages hosts are lowercase on GitHub. 'Getting fixes from the template later'
# in new-project.md keeps pointing to the template on purpose, and so does ci.yml, whose rename job runs only there.
grep -rlI --exclude-dir=.git --exclude-dir=target --exclude-dir=node_modules --exclude-dir=dist --exclude-dir=.astro \
  --exclude=rename-project.sh --exclude=package-lock.json --exclude=CHANGELOG.md --exclude=new-project.md \
  --exclude=ci.yml \
  -e "$old_owner" -e "$old_name" . | while read -r file; do
  sed_inplace \
    -e "s|ghcr\.io/$old_owner/$old_name|ghcr.io/$(lower "$repo")|g" \
    -e "s|$old_owner\.github\.io/$old_name|$(lower "$owner").github.io/$name|g" \
    -e "s|$old_owner/$old_name|$repo|g" \
    -e "s|https://$old_owner\.github\.io'|https://$(lower "$owner").github.io'|g" \
    -e "s|$old_name|$name|g" \
    "$file"
done
sed_inplace "s|@$old_owner|@$owner|g" .github/CODEOWNERS CODE_OF_CONDUCT.md
sed_inplace "s|github\.com/$old_owner)|github.com/$owner)|g" CODE_OF_CONDUCT.md

# The template's release history and its gitleaks exceptions (fingerprints of its own commits) do not apply to the
# new project.
rm -f .gitleaksignore
cat > CHANGELOG.md <<'EOF2'
# Changelog

Notable changes to this project. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]
EOF2

info "Done. Review the changes (git status), then run scripts/test.sh."
info "Still yours to update: the README, LICENSE (copyright holder) and the image tag in deploy/kubernetes/kustomization.yaml."
