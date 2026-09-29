# Nooagram

Nooagram rebases onto published releases of [Keeperorowner/NagramXF](https://github.com/Keeperorowner/NagramXF).

## Automation

- `ayu` is the release branch and the repository default branch.
- `Sync NagramXF upstream release` checks every 15 minutes and resolves a published release tag.
- A successful rebase records its tag and commit in `nooagram-version.json`, then pushes with an explicit lease against the previous remote commit.
- If the rebase conflicts, it opens a GitHub issue with the conflicted files. Concurrent remote changes cause the leased push to fail without overwriting them.
- `Release Build` validates the pinned ancestry, runs regression tests, and publishes ARMv7, ARM64 and x86_64 APKs with `SHA256SUMS.txt` and `update.json`.
- Release names and version codes are unique. Existing release assets are not overwritten. All assets are uploaded to a draft before publishing it as latest.

## In-app update source

The app checks:

```text
https://github.com/yuhuan17520-glitch/Nooagram/releases/latest/download/update.json
```

The `assets` map contains architecture-specific URLs, sizes and checksums. Legacy ARM clients can still read `download_url` and `download_url_32`.

## Local builds

`Tools/build-nooagram.ps1` uses the same version source as CI and checks the generated APK metadata. Supply a larger `-LocalVersionCode` for a subsequent local delivery. The package remains `fork.yuhuan.nooagram` and signed updates preserve app data.

The version format is `1.2.1-12.10.1.1251+125000043`: app version, pinned upstream version and tag, then Android build code as SemVer build metadata. Commit provenance is recorded separately.

## Attachment storage

Saved attachments now go into an installation-specific child of the selected directory. Automatic quota eviction and manual clearing only delete files with matching private ownership records. Existing attachments keep their original references and are not automatically moved or adopted; unrecorded files are retained.

## Signing

Nooagram signs release APKs with a generated local keystore. The CI keystore and
passwords are stored as GitHub Actions secrets:

- `NOOAGRAM_KEYSTORE_BASE64`
- `LOCAL_PROPERTIES`

A local backup is kept outside the repository in:

```text
C:/Users/YuHuan/Documents/Codex/2026-09-07/https-github-com-risin42-nagramx-https/work/nooagram-secrets
```
