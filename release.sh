#!/usr/bin/env bash
set -euo pipefail

# ─── Helpers ──────────────────────────────────────────────────────────────────

red()    { printf '\033[1;31m%s\033[0m' "$*"; }
green()  { printf '\033[1;32m%s\033[0m' "$*"; }
yellow() { printf '\033[1;33m%s\033[0m' "$*"; }
bold()   { printf '\033[1m%s\033[0m' "$*"; }

die() { echo "$(red "Error:") $*" >&2; exit 1; }

# ─── Prerequisites ────────────────────────────────────────────────────────────

command -v mvn >/dev/null 2>&1 || die "mvn is not installed"
command -v gh  >/dev/null 2>&1 || die "gh (GitHub CLI) is not installed"
command -v git >/dev/null 2>&1 || die "git is not installed"

# Must be run from the project root (where pom.xml lives)
[[ -f pom.xml ]] || die "pom.xml not found — run this script from the project root"

# Working tree must be clean
if [[ -n "$(git status --porcelain)" ]]; then
	die "Working tree is not clean. Commit or stash your changes first."
fi

# ─── Read current version ────────────────────────────────────────────────────

CURRENT_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)
[[ -n "$CURRENT_VERSION" ]] || die "Could not read version from pom.xml"

echo ""
echo "  Current pom.xml version: $(bold "$CURRENT_VERSION")"

# ─── Compute release version ─────────────────────────────────────────────────

RELEASE_VERSION="${CURRENT_VERSION%-SNAPSHOT}"

echo "  Proposed release version: $(green "$RELEASE_VERSION")"
echo ""
read -rp "  Release version [$RELEASE_VERSION]: " INPUT_VERSION
RELEASE_VERSION="${INPUT_VERSION:-$RELEASE_VERSION}"

# Validate version format (digits and dots only)
if [[ ! "$RELEASE_VERSION" =~ ^[0-9]+(\.[0-9]+)*$ ]]; then
	die "Invalid version format: $RELEASE_VERSION (expected e.g. 1.0.0)"
fi

# ─── Compute next SNAPSHOT version ───────────────────────────────────────────

# Increment the last numeric segment
NEXT_VERSION=$(echo "$RELEASE_VERSION" | awk -F. '{$NF=$NF+1; print}' OFS='.')
NEXT_SNAPSHOT="${NEXT_VERSION}-SNAPSHOT"

echo "  Next development version: $(yellow "$NEXT_SNAPSHOT")"
echo ""
read -rp "  Next development version [$NEXT_SNAPSHOT]: " INPUT_NEXT
NEXT_SNAPSHOT="${INPUT_NEXT:-$NEXT_SNAPSHOT}"

# ─── Confirm ──────────────────────────────────────────────────────────────────

TAG="v${RELEASE_VERSION}"

echo ""
echo "  ┌──────────────────────────────────────────┐"
echo "  │  $(bold "Release Summary")                         │"
echo "  ├──────────────────────────────────────────┤"
echo "  │  Release version : $(green "$RELEASE_VERSION")$(printf '%*s' $((20 - ${#RELEASE_VERSION})) '')│"
echo "  │  Git tag         : $(bold "$TAG")$(printf '%*s' $((20 - ${#TAG})) '')│"
echo "  │  Next snapshot   : $(yellow "$NEXT_SNAPSHOT")$(printf '%*s' $((20 - ${#NEXT_SNAPSHOT})) '')│"
echo "  └──────────────────────────────────────────┘"
echo ""
read -rp "  Proceed? [y/N]: " CONFIRM
[[ "$CONFIRM" =~ ^[Yy]$ ]] || { echo "Aborted."; exit 0; }

# ─── Execute release ─────────────────────────────────────────────────────────

echo ""
echo "$(bold "▸ Creating tag $TAG …")"
git tag -a "$TAG" -m "Release $RELEASE_VERSION"

echo "$(bold "▸ Pushing tag to origin …")"
git push origin "$TAG"

echo "$(bold "▸ Creating GitHub release …")"
gh release create "$TAG" \
	--title "$RELEASE_VERSION" \
	--generate-notes

echo ""
echo "$(green "✓") Release $(bold "$RELEASE_VERSION") created. CI will deploy to Maven Central."

# ─── Bump to next SNAPSHOT ────────────────────────────────────────────────────

echo ""
echo "$(bold "▸ Setting next development version: $NEXT_SNAPSHOT …")"
mvn -B versions:set -DnewVersion="$NEXT_SNAPSHOT" -DgenerateBackupPoms=false -q

echo "$(bold "▸ Committing version bump …")"
git add pom.xml
git commit -m "chore: bump version to $NEXT_SNAPSHOT"
git push origin HEAD

echo ""
echo "$(green "✓") Done. pom.xml is now at $(bold "$NEXT_SNAPSHOT")."
echo ""
