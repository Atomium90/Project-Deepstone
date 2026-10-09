# Releasing Deepstone

How a version of the game goes from `main` to a download. A release is a git tag: pushing it makes
the release workflow build the game for Windows and Linux, sign what it built and prepare a
**draft** GitHub Release. The release notes and the publication are done by hand.

## Before tagging

- Every pull request of the release is merged and the CI is green on `main` (backend, frontend,
  end-to-end, SonarCloud).
- `ThisBuild / version` in `deepstone-backend/build.sbt` is the version to release. The workflow
  stops if the tag is not `v` followed by that exact version.
- The assets are complete. With the private assets repo cloned next to this one, run
  `.\sync-assets.ps1`, then `npm run audit:assets` in `frontend/`: it must say that every
  referenced file is on disk.
- `CREDITS.md` and the Credits tab list every pack in the game (a test keeps the two in step). If
  a pack was added or removed, the private assets repo and its README are updated too.
- The libraries are free of known vulnerabilities. Dependabot cannot raise security alerts for the
  backend, so check the ones that ship by hand. The jars are in the `lib/` folder of a built
  package. For each important one (logback, http4s, sqlite-jdbc, circe, HikariCP):

  ```
  gh api "/advisories?ecosystem=maven&affects=ch.qos.logback:logback-classic@1.5.38" --jq '.[] | [.severity, .ghsa_id, .summary] | @tsv'
  ```

  It prints one line per advisory that affects that exact version, and nothing when there is
  none. For the frontend, only what ships matters: `npm audit --omit=dev` in `frontend/`. It is
  expected to report the Svelte 4 advisories (server-side rendering and DOM clobbering): they do not
  apply, the game does not render on the server and never injects HTML, and the only fix is
  Svelte 5. Anything else it reports is new.
- The token `LICENSED_ASSETS_PAT` has not expired. It is a fine-grained, read-only token limited to
  the private assets repo, and the release cannot fetch the art and audio without it.

## Releasing

Push the tag from your own machine:

```
git tag v0.8.0
git push origin v0.8.0
```

The `Release` workflow then runs four jobs:

1. **Resolve tag and version**: the tag must look like `v0.8.0` and match `build.sbt`.
2. **Build**, on Windows and on Linux: backend tests, the assets copied from the private repo, the
   asset check, then a package with its own Java runtime, so players need no Java installed. A
   Windows runner and a Linux runner each build their own, a bundled runtime only runs on the
   system it was built on.
3. **Attest build provenance**: signs, for both zips, a statement that this workflow built these
   exact files from this commit.
4. **Publish draft release**: creates a draft with `deepstone-backend-<version>-windows.zip`,
   `deepstone-backend-<version>-linux.zip` and `SHA256SUMS.txt`. A release that is already
   published is never touched, the job stops instead. The checksums are also printed in the summary
   of the job, ready to paste in the notes.

## Checking the draft

Download a zip from the draft, then:

```
sha256sum -c SHA256SUMS.txt          # or Get-FileHash <zip> on Windows, and compare by eye
gh attestation verify deepstone-backend-0.8.0-windows.zip --repo Atomium90/Project-Deepstone
```

Unzip it anywhere and start `bin\deepstone-backend.bat` (Windows) or `bin/deepstone-backend`
(Linux). The console prints the address to open, usually `http://localhost:8080`. Play a few rooms:
sprites and music are there and the Credits tab lists the packs.

## Notes and publication

Write the notes in the GitHub UI. They cover everything since the previous tag, including the
versions that were never tagged: `git log <previous tag>..` lists it. Paste the checksums, and say
that Windows may warn on the first start because the files are not code-signed (SmartScreen:
"More info", then "Run anyway"). Edit the draft and publish it. A published release is final: if
something is wrong, release a new version.
