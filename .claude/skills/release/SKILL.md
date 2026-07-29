---
name: release
description: Use when cutting a new release of comenius-maven-plugin, publishing a version to Maven Central, or asked to "release" or "cut a release" of this plugin
---

# Release

## Overview

A release is triggered by publishing a GitHub Release with a `vX.Y.Z` tag. `.github/workflows/release.yml`
reacts to that `published` event: it sets the real Maven version transiently with `versions:set`, then
signs and deploys to Maven Central via OSSRH. `pom.xml` on `main` never carries a bare release version -
it always holds the *next target* as a `-SNAPSHOT`, before and after each release.

Publishing the release is a one-way door: Maven Central does not allow removing or overwriting published
artifacts. Pushing to `main` is also externally visible. Per this repo's CLAUDE.md / global git rules,
confirm the version number and the push/publish steps with the user before running them.

## Steps

1. Pick the version (semver): a release with `feat` commits since the last one is at least a minor bump;
   `fix`-only is a patch bump.
2. Bump `pom.xml`'s `<version>` to `X.Y.Z-SNAPSHOT` (the target version itself, still a SNAPSHOT) and
   commit: `chore: bump version to X.Y.Z-SNAPSHOT`.
3. `git push origin main`.
4. Publish the release - this is what fires the workflow:
   ```
   gh release create vX.Y.Z --title "X.Y.Z" --generate-notes --target main
   ```
5. Confirm the deploy started/succeeded:
   ```
   gh run list --workflow=release.yml --limit 1
   gh run view <id> --json status,conclusion --jq '{status,conclusion}'
   ```
6. Bump `pom.xml` to the next patch `-SNAPSHOT` (`X.Y.(Z+1)-SNAPSHOT`), commit:
   `chore: bump version to X.Y.(Z+1)-SNAPSHOT`, and push.

## Key facts

- Tags follow `vX.Y.Z` (list existing ones with `git tag -l`).
- `gh release create` without `--draft` publishes immediately and fires the workflow; a `--draft` release
  does not, which is a safe way to prepare a release without triggering the deploy until you click Publish.
- The actual release version is set only inside CI (`mvn versions:set -DnewVersion=$VERSION`, from the tag
  name stripped of `v`) - never commit a non-SNAPSHOT version to `pom.xml`.
- Signing/deploy secrets (`MAVEN_GPG_PRIVATE_KEY`, `MAVEN_USERNAME`, `MAVEN_CENTRAL_TOKEN`,
  `MAVEN_GPG_PASSPHRASE`) already live in the repo's GitHub Actions secrets.

## Common mistakes

- Committing a stripped, non-SNAPSHOT version to `pom.xml` - unnecessary, CI does this transiently.
- Skipping the pre-release SNAPSHOT bump (step 2) - the tag then points at a commit whose `pom.xml`
  version doesn't match what's being released, which is confusing later even though the build itself
  still works.
- Assuming a draft release deploys - only a *published* release fires `release.yml`.
