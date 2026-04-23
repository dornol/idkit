# Releasing

Release is driven by pushing a SemVer tag: `.github/workflows/maven-publish.yml`
picks it up and publishes to Maven Central automatically. Everything below is
what has to be true *before* the tag push, plus a short verification pass
afterwards.

## Pre-release

- [ ] `./gradlew build` is green locally (Temurin 11 via toolchain)
- [ ] Test CI is green on `origin/main` for the commit you're about to tag
- [ ] `build.gradle.kts` `version = "X.Y.Z"` matches the tag you are about to create
- [ ] `CHANGELOG.md` has an `## [X.Y.Z] - YYYY-MM-DD` section covering every
      user-visible change since the previous tag
- [ ] No un-pushed commits on `main` — the workflow builds the tagged commit
      from `origin`, not your local working tree

### Additional for a major release (X bump in SemVer)

- [ ] `CHANGELOG.md` has a `### Changed` or `### Removed` entry with a
      migration note users can act on (not just "we changed X")
- [ ] `README.md` usage examples still compile and behave correctly under the
      new default
- [ ] `./gradlew jmh -Pjmh.fork=1 -Pjmh.warmup=1 -Pjmh.iterations=1` smoke run
      completes without unexpected exceptions (optional, ~10 min — catches
      behavioral regressions that unit tests miss)

## Release

```bash
git tag X.Y.Z
git push origin X.Y.Z
```

That's it — `.github/workflows/maven-publish.yml` takes over. Tag format must
match the pattern `*.*.*` in the workflow trigger (no `v` prefix — matches
the `version` in `build.gradle.kts`).

## Verify

- [ ] `maven-publish` workflow run is green:
      https://github.com/dornol/idkit/actions/workflows/maven-publish.yml
- [ ] Artifact appears on Central Portal (propagation can take up to ~1 h):
      https://central.sonatype.com/artifact/io.github.dornol/idkit
- [ ] Create a GitHub Release from the tag and paste the matching
      `CHANGELOG.md` section as the body — this is what appears under
      "Releases" for the repo and what users subscribe to

## If something goes wrong

- **Tag pushed, workflow failed *before* publish**: fix the cause, delete the
  tag (`git push --delete origin X.Y.Z` plus `git tag -d X.Y.Z` locally),
  then retag the corrected commit. Safe only while nothing has been
  published.
- **Workflow published a broken artifact**: a published version cannot be
  removed from Maven Central. Publish `X.Y.Z+1` with the fix and add a
  deprecation note to the `[X.Y.Z]` CHANGELOG entry pointing to the patch.
