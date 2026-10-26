#!/usr/bin/env bash
# Makes the release commit and tag from main, then pushes both after asking. The pushed tag starts the release
# workflow, which publishes and signs the image and then pins its digest in deploy/kubernetes on main.
# Usage: scripts/release.sh <version>      e.g. scripts/release.sh 4.1.0
#
# The release commit moves the [Unreleased] notes of CHANGELOG.md under the new version, sets the image tag in
# deploy/kubernetes and updates the version shown in the guides' examples. Write the notes before running it.
source "$(dirname "$0")/lib.sh"

version="${1:-}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "Usage: scripts/release.sh <version>, e.g. 4.1.0 (pre-releases are tagged by hand)."
tag="v$version"

[[ "$(git rev-parse --abbrev-ref HEAD)" == main ]] || die "Release from main."
[[ -z "$(git status --porcelain)" ]] || die "The working tree has changes; commit or stash them first."
git fetch -q origin main --tags
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || die "main differs from origin/main; pull or push first."
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && die "Tag $tag already exists."

kustomization=deploy/kubernetes/kustomization.yaml
previous="$(sed -n -E 's/^ *newTag: "([^"]+)"$/\1/p' "$kustomization")"
[[ -n "$previous" ]] || die "No newTag in $kustomization."
[[ "$previous" != "$version" ]] || die "$kustomization is already at $version."
# Lines between "## [Unreleased]" and the next release heading, ignoring blank ones.
notes="$(awk '/^## \[Unreleased\]/{on=1; next} /^## \[/{on=0} on && NF' CHANGELOG.md)"
[[ -n "$notes" ]] || die "CHANGELOG.md has no notes under [Unreleased]; write them first."

info "Releasing $version (previous: $previous)"

# CHANGELOG.md: a heading for the release under [Unreleased], and compare links if the file has them.
awk -v v="$version" -v d="$(date +%F)" '
  /^## \[Unreleased\]$/ { print; print ""; print "## [" v "] - " d; next }
  /^\[Unreleased\]: .*\/compare\/v[^.]+\.[^.]+\.[^.]+\.\.\.HEAD$/ {
    base = $0; sub(/^\[Unreleased\]: /, "", base); sub(/\/compare\/.*/, "", base)
    prev = $0; sub(/.*\/compare\//, "", prev); sub(/\.\.\.HEAD$/, "", prev)
    print "[Unreleased]: " base "/compare/v" v "...HEAD"
    print "[" v "]: " base "/compare/" prev "...v" v
    next
  }
  { print }
' CHANGELOG.md > CHANGELOG.md.tmp && mv CHANGELOG.md.tmp CHANGELOG.md

# The image tag; the release workflow pins the new digest once the image is published.
escaped="${previous//./\\.}"
sed_inplace -E "s/^( *newTag: )\"$escaped\"$/\1\"$version\"/" "$kustomization"
sed_inplace -E "s/(image: [^:]+:)$escaped@/\1$version@/" deploy/kubernetes/migrate-job.yaml

# Version examples in the guides (image tag, release tag, git log range).
for guide in docs/src/content/docs/guides/production.md docs/src/content/docs/guides/new-project.md; do
  [[ -f "$guide" ]] && sed_inplace -E -e "s/:$escaped( |$)/:$version\1/g" -e "s/v$escaped(\.\.| |$)/v$version\1/g" "$guide"
done

require_jdk
./mvnw -B -q test -Dtest=KubernetesManifestTest -Dsurefire.failIfNoSpecifiedTests=false

git add -A
git commit -q -m "chore: release $version"
git tag -a "$tag" -m "$tag"
git --no-pager show --stat --format='%h %s' HEAD

read -r -p "Push main and $tag to origin? This publishes the release. [y/N] " answer
if [[ "$answer" == [yY] ]]; then
  git push --atomic origin main "$tag"
  info "Pushed. The release workflow pins the image digest on main when it is done; pull afterwards."
else
  info "Not pushed. Push with: git push --atomic origin main $tag"
  info "Or undo with: git tag -d $tag && git reset --hard HEAD~1"
fi
